package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.ValidationResult;
import com.stripe.mpp.store.MemoryStore;
import com.stripe.mpp.store.Store;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>A qualifying Transfer of the requested token, recipient and amount is not
 * enough. Unless the merchant set an explicit memo, the matched logs must include
 * a {@code TransferWithMemo} whose memo is bound to this challenge (MPP attribution
 * tag, server fingerprint of the challenge realm, and nonce
 * {@code keccak256(challengeId)[0..6]}). That is what stops a third party from
 * presenting someone else's settled transaction as their own payment.
 *
 * <p>Create the intent once and reuse it so its replay store is shared across requests:
 *
 * <pre>{@code
 * TempoChargeIntent chargeIntent = Tempo.chargeIntent();
 *
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     chargeIntent,
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
    private static final String REPLAY_KEY_PREFIX = "tempo:hash:";
    private static final Pattern PKH_SOURCE =
        Pattern.compile("^did:pkh:eip155:(0|[1-9]\\d*):([^:]+)$");
    private static final Pattern ADDRESS =
        Pattern.compile("^0x[a-fA-F0-9]{40}$");

    private final String rpcUrl;
    private final int maxRetries;
    private final long retryDelayMs;
    private final TempoRpc rpc;
    private final Store store;

    public TempoChargeIntent(String rpcUrl) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc(), new MemoryStore());
    }

    /**
     * Constructs an intent with an explicit replay-protection store.
     *
     * <p>Use a durable store in production, shared across processes and instances.
     */
    public TempoChargeIntent(String rpcUrl, Store store) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc(), store);
    }

    TempoChargeIntent(String rpcUrl, TempoRpc rpc) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, rpc, new MemoryStore());
    }

    TempoChargeIntent(String rpcUrl, int maxRetries, long retryDelayMs, TempoRpc rpc) {
        this(rpcUrl, maxRetries, retryDelayMs, rpc, new MemoryStore());
    }

    TempoChargeIntent(String rpcUrl, int maxRetries, long retryDelayMs, TempoRpc rpc, Store store) {
        this.rpcUrl = rpcUrl;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.rpc = rpc;
        this.store = Objects.requireNonNull(store, "store");
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
            return verifyTransaction((String) payload.get("signature"), request, credential);
        }
        if ("hash".equals(type)) {
            // Push: client already broadcast, server just verifies the receipt.
            return verifyHash((String) payload.get("hash"), request, credential);
        }
        throw new VerificationFailedException("unrecognized payload type: " + type);
    }

    private Receipt verifyTransaction(String rawTx, Map<String, Object> request, Credential credential) {
        String sourceAddress = parseCredentialSource(credential.source(), chainIdFrom(request));
        String txHash = rpc.sendRawTransaction(rpcUrl, rawTx);
        return claimOnce(awaitReceipt(txHash, request, credential, sourceAddress));
    }

    private Receipt verifyHash(String txHash, Map<String, Object> request, Credential credential) {
        // Validate the declared payer before reserving the hash so a malformed
        // source cannot burn an otherwise valid payment.
        String sourceAddress = parseCredentialSource(credential.source(), chainIdFrom(request));
        return claimOnce(awaitReceipt(txHash, request, credential, sourceAddress));
    }

    /** Records first use of the settled transaction, rejecting a hash that was already claimed. */
    private Receipt claimOnce(Receipt receipt) {
        String txHash = receipt.reference();
        if (!store.tryClaim(REPLAY_KEY_PREFIX + txHash.toLowerCase(Locale.ROOT))) {
            throw new VerificationFailedException("transaction hash already used: " + txHash);
        }
        return receipt;
    }

    private Receipt awaitReceipt(
        String txHash,
        Map<String, Object> request,
        Credential credential,
        String sourceAddress
    ) {
        for (int i = 0; i < maxRetries; i++) {
            Map<String, Object> receipt = rpc.getTransactionReceipt(rpcUrl, txHash);
            if (receipt != null) {
                if (!"0x1".equals(receipt.get("status"))) {
                    throw new VerificationFailedException("transaction reverted");
                }
                String expectedSender = sourceAddress != null ? sourceAddress : (String) receipt.get("from");
                List<MatchedLog> matched = matchTransferLogs(receipt, request, expectedSender);
                if (matched.isEmpty()) {
                    throw new VerificationFailedException(
                        "transaction logs contain no Transfer matching the request currency, recipient, and amount"
                    );
                }
                if (memoFrom(request) == null) {
                    assertChallengeBoundMemo(matched, credential);
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
     * Collects ERC-20 Transfer / TransferWithMemo logs that match the request's
     * currency, recipient, amount, expected sender, and (when set) merchant memo.
     *
     * <p>The request amount must already be in atomic units (i.e. after
     * transformRequest has run).
     */
    @SuppressWarnings("unchecked")
    private List<MatchedLog> matchTransferLogs(
        Map<String, Object> receipt,
        Map<String, Object> request,
        String expectedSender
    ) {
        String currency  = (String) request.get("currency");
        String recipient = (String) request.get("recipient");
        String amountStr = (String) request.get("amount");
        String expectedMemo = normalizeMemo(memoFrom(request));

        if (currency == null || recipient == null || amountStr == null) return List.of();

        BigInteger expectedAmount;
        try {
            expectedAmount = new BigInteger(amountStr);
        } catch (NumberFormatException e) {
            return List.of();
        }

        List<Object> logs = (List<Object>) receipt.get("logs");
        if (logs == null) return List.of();

        List<MatchedLog> matched = new ArrayList<>();
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
            if (expectedMemo != null && !isTransferWithMemo) continue;

            String fromAddress = "0x" + topics.get(1).substring(topics.get(1).length() - 40);
            String toAddress   = "0x" + topics.get(2).substring(topics.get(2).length() - 40);

            if (!toAddress.equalsIgnoreCase(recipient)) continue;
            if (expectedSender != null && !fromAddress.equalsIgnoreCase(expectedSender)) continue;

            if (expectedMemo != null) {
                String logMemo = normalizeMemo(topics.get(3));
                if (logMemo == null || !logMemo.equals(expectedMemo)) continue;
            }

            String data = (String) log.get("data");
            if (data == null || data.length() < 66) continue;

            try {
                String dataHex = data.startsWith("0x") || data.startsWith("0X")
                    ? data.substring(2) : data;
                BigInteger logAmount = new BigInteger(dataHex, 16);
                if (!logAmount.equals(expectedAmount)) continue;
                matched.add(new MatchedLog(isTransferWithMemo, isTransferWithMemo ? topics.get(3) : null));
            } catch (NumberFormatException e) {
                // malformed data field, skip this log
            }
        }

        return matched;
    }

    private static void assertChallengeBoundMemo(List<MatchedLog> matched, Credential credential) {
        String realm = credential.challenge().realm();
        String challengeId = credential.challenge().id();
        for (MatchedLog log : matched) {
            if (!log.memo) continue;
            if (Attribution.verifyServer(log.memoValue, realm)
                && Attribution.verifyChallengeBinding(log.memoValue, challengeId)) {
                return;
            }
        }
        throw new VerificationFailedException("memo is not bound to this challenge");
    }

    /**
     * Parses a credential source. {@code null} or empty if absent; the
     * address for a {@code did:pkh:eip155} DID matching {@code expectedChainId};
     * otherwise raises.
     */
    static String parseCredentialSource(String source, Object expectedChainId) {
        if (source == null || source.isEmpty()) return null;
        ParsedPkh parsed = parsePkhSource(source);
        Integer expected = parseChainIdValue(expectedChainId);
        if (parsed == null || (expected != null && parsed.chainId != expected)) {
            throw new VerificationFailedException("Credential source is invalid");
        }
        return parsed.address;
    }

    static Object chainIdFrom(Map<String, Object> request) {
        Object details = request.get("methodDetails");
        if (!(details instanceof Map<?, ?>)) return null;
        return ((Map<?, ?>) details).get("chainId");
    }

    static Integer parseChainIdValue(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof String) {
            try {
                return Integer.valueOf((String) raw);
            } catch (NumberFormatException e) {
                throw new VerificationFailedException("Credential source is invalid");
            }
        }
        throw new VerificationFailedException("Credential source is invalid");
    }

    static ParsedPkh parsePkhSource(String source) {
        Matcher match = PKH_SOURCE.matcher(source);
        if (!match.matches()) return null;
        if (!ADDRESS.matcher(match.group(2)).matches()) return null;
        return new ParsedPkh(match.group(2), Integer.parseInt(match.group(1)));
    }

    static String memoFrom(Map<String, Object> request) {
        Object top = request.get("memo");
        if (top instanceof String && !((String) top).isEmpty()) return (String) top;
        Object details = request.get("methodDetails");
        if (details instanceof Map<?, ?>) {
            Object nested = ((Map<?, ?>) details).get("memo");
            if (nested instanceof String && !((String) nested).isEmpty()) return (String) nested;
        }
        return null;
    }

    static String normalizeMemo(String memo) {
        if (memo == null) return null;
        String value = memo.trim();
        if (value.isEmpty()) return null;
        if (!value.startsWith("0x") && !value.startsWith("0X")) value = "0x" + value;
        return value.toLowerCase(Locale.ROOT);
    }

    static final class ParsedPkh {
        final String address;
        final int chainId;

        ParsedPkh(String address, int chainId) {
            this.address = address;
            this.chainId = chainId;
        }
    }

    private static final class MatchedLog {
        final boolean memo;
        final String memoValue;

        MatchedLog(boolean memo, String memoValue) {
            this.memo = memo;
            this.memoValue = memoValue;
        }
    }
}

/** Relay-backed charge intent with explicit split validation and broadcast hooks. */
final class TempoRelayChargeIntent extends TempoChargeIntent {
    private final TempoRelay relay;

    TempoRelayChargeIntent(String rpcUrl, TempoRelay relay) {
        // Every rpc-reaching entry point is overridden below, so no TempoRpc is needed.
        super(rpcUrl, (TempoRpc) null);
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
