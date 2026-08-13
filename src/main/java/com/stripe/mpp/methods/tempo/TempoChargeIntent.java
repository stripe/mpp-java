package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.ValidationResult;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Server-side intent that verifies Tempo payments.
 *
 * <p>Supports two credential payload shapes produced by the Tempo client SDK:
 * <ul>
 *   <li>{@code "transaction"} — a signed raw EVM transaction (pull flow); the server
 *       broadcasts it via {@code eth_sendRawTransaction} and polls for the receipt.</li>
 *   <li>{@code "hash"} — a transaction hash already broadcast by the client (push flow);
 *       the server polls for the receipt directly.</li>
 * </ul>
 *
 * <pre>{@code
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     Tempo.chargeIntent(),          // or Tempo.chargeIntent(true) for testnet
 *     "10.000000", "USDC", "0xRecipient"
 * );
 * }</pre>
 */
public class TempoChargeIntent implements Intent {
    static final int DEFAULT_MAX_RETRIES = 20;
    static final long DEFAULT_RETRY_DELAY_MS = 500;

    static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final String TRANSFER_WITH_MEMO_TOPIC =
        "0x57bc7354aa85aed339e000bccffabbc529466af35f0772c8f8ee1145927de7f0";

    private final String rpcUrl;
    private final int maxRetries;
    private final long retryDelayMs;
    private final TempoRpc rpc;

    public TempoChargeIntent(String rpcUrl) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc());
    }

    TempoChargeIntent(String rpcUrl, TempoRpc rpc) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, rpc);
    }

    TempoChargeIntent(String rpcUrl, int maxRetries, long retryDelayMs, TempoRpc rpc) {
        this.rpcUrl = rpcUrl;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.rpc = rpc;
    }

    @Override
    public String name() { return "charge"; }

    @Override
    @SuppressWarnings("unchecked")
    public Receipt verify(Credential credential, Map<String, Object> request) {
        if (!(credential.payload() instanceof Map<?, ?>)) {
            throw new VerificationFailedException("missing or invalid payload");
        }
        Map<String, Object> payload = (Map<String, Object>) credential.payload();

        String type = (String) payload.get("type");
        if ("transaction".equals(type)) {
            // Pull: client signed the tx, server broadcasts it.
            return verifyTransaction((String) payload.get("signature"), request, credential.challenge());
        }
        if ("hash".equals(type)) {
            // Push: client already broadcast, server just verifies the receipt.
            return verifyHash((String) payload.get("hash"), request, credential.challenge());
        }
        throw new VerificationFailedException("unrecognized payload type: " + type);
    }

    private Receipt verifyTransaction(String rawTx, Map<String, Object> request, ChallengeEcho challenge) {
        String txHash = rpc.sendRawTransaction(rpcUrl, rawTx);
        return awaitReceipt(txHash, request, challenge);
    }

    private Receipt verifyHash(String txHash, Map<String, Object> request, ChallengeEcho challenge) {
        return awaitReceipt(txHash, request, challenge);
    }

    private Receipt awaitReceipt(String txHash, Map<String, Object> request, ChallengeEcho challenge) {
        for (int i = 0; i < maxRetries; i++) {
            Map<String, Object> receipt = rpc.getTransactionReceipt(rpcUrl, txHash);
            if (receipt != null) {
                if (!"0x1".equals(receipt.get("status"))) {
                    throw new VerificationFailedException("transaction reverted");
                }
                if (!matchTransferLogs(receipt, request, challenge)) {
                    throw new VerificationFailedException(
                        "transaction logs contain no Transfer matching the request currency, recipient, and amount"
                    );
                }
                return Receipt.success(txHash, "tempo");
            }
            if (i < maxRetries - 1) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new VerificationFailedException("transaction receipt timeout");
    }

    /**
     * Returns true if the receipt contains at least one ERC-20 Transfer (or TransferWithMemo)
     * log that matches the request's currency (token contract), recipient, sender, and amount.
     *
     * <p>A {@code TransferWithMemo} log only counts as a match if its memo (topics[3]) either
     * doesn't use the MPP attribution layout at all (an application-defined memo unrelated to
     * MPP — left alone, since it isn't a credential-binding signal either way), or — when it
     * does carry MPP attribution structure — is bound to this specific challenge (server
     * fingerprint matches the challenge realm and the nonce matches the challenge ID). This
     * stops a credential replay where an attacker submits a transaction whose memo was bound to
     * a *different* challenge (or forged) but otherwise matches this challenge's currency,
     * recipient, and amount. A plain {@code Transfer} (no memo at all) is still accepted,
     * preserving the existing non-memo flow.
     *
     * The request amount must already be in atomic units (i.e. after transformRequest has run).
     */
    @SuppressWarnings("unchecked")
    private boolean matchTransferLogs(
        Map<String, Object> receipt, Map<String, Object> request, ChallengeEcho challenge
    ) {
        String currency  = (String) request.get("currency");
        String recipient = (String) request.get("recipient");
        String amountStr = (String) request.get("amount");
        String sender    = (String) receipt.get("from");

        if (currency == null || recipient == null || amountStr == null) return false;

        BigInteger expectedAmount;
        try {
            expectedAmount = new BigInteger(amountStr);
        } catch (NumberFormatException e) {
            return false;
        }

        List<Object> logs = (List<Object>) receipt.get("logs");
        if (logs == null) return false;

        for (Object logObj : logs) {
            Map<String, Object> log = (Map<String, Object>) logObj;

            String logAddress = (String) log.get("address");
            if (logAddress == null || !logAddress.equalsIgnoreCase(currency)) continue;

            List<String> topics = (List<String>) log.get("topics");
            if (topics == null || topics.size() < 3) continue;

            String topic0 = topics.get(0);
            boolean isTransfer         = TRANSFER_TOPIC.equalsIgnoreCase(topic0);
            boolean isTransferWithMemo = TRANSFER_WITH_MEMO_TOPIC.equalsIgnoreCase(topic0);
            if (!isTransfer && !isTransferWithMemo) continue;
            if (isTransferWithMemo && topics.size() < 4) continue;

            String fromAddress = "0x" + topics.get(1).substring(topics.get(1).length() - 40);
            String toAddress   = "0x" + topics.get(2).substring(topics.get(2).length() - 40);

            if (!toAddress.equalsIgnoreCase(recipient)) continue;
            if (sender != null && !fromAddress.equalsIgnoreCase(sender)) continue;

            if (isTransferWithMemo) {
                String memo = topics.get(3);
                // If the memo uses the MPP attribution layout at all, it must be bound to
                // *this* challenge — an attribution memo minted for a different challenge
                // (or realm) must not be able to satisfy this one, even if every other field
                // lines up. A memo that isn't MPP-attribution-shaped at all (e.g. an
                // application-defined memo unrelated to MPP) is left alone here; it isn't a
                // credential-binding signal either way.
                if (Attribution.looksLikeAttributionMemo(memo)
                    && !Attribution.isBoundToChallenge(memo, challenge.realm(), challenge.id())) {
                    continue;
                }
            }

            String data = (String) log.get("data");
            if (data == null || data.length() < 66) continue;

            try {
                String dataHex = data.startsWith("0x") || data.startsWith("0X")
                    ? data.substring(2) : data;
                BigInteger logAmount = new BigInteger(dataHex, 16);
                if (logAmount.equals(expectedAmount)) return true;
            } catch (NumberFormatException e) {
                // malformed data field, skip this log
            }
        }

        return false;
    }
}

/** Relay-backed charge intent with explicit split validation and broadcast hooks. */
final class TempoRelayChargeIntent extends TempoChargeIntent {
    private final TempoRelay relay;

    TempoRelayChargeIntent(String rpcUrl, TempoRelay relay) {
        // Every rpc-reaching entry point is overridden below, so no TempoRpc is needed.
        super(rpcUrl, null);
        this.relay = relay;
    }

    @Override
    public ValidationResult validate(Credential credential, Map<String, Object> request) {
        return relay.validate(credential);
    }

    @Override
    public Receipt broadcast(Credential credential, Map<String, Object> request) {
        return relay.broadcast(credential);
    }

    @Override
    public Receipt verify(Credential credential, Map<String, Object> request) {
        return relay.verify(credential);
    }
}
