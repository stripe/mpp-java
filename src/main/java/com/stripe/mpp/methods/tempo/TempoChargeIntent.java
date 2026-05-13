package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.Intent;

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

    private final String rpcUrl;
    private final int maxRetries;
    private final long retryDelayMs;
    private final TempoRpc rpc;

    public TempoChargeIntent(String rpcUrl) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc());
    }

    public TempoChargeIntent(String rpcUrl, boolean debug) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc(debug));
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
            return verifyTransaction((String) payload.get("signature"));
        }
        if ("hash".equals(type)) {
            // Push: client already broadcast, server just verifies the receipt.
            return verifyHash((String) payload.get("hash"));
        }
        throw new VerificationFailedException("unrecognized payload type: " + type);
    }

    private Receipt verifyTransaction(String rawTx) {
        String txHash = rpc.sendRawTransaction(rpcUrl, rawTx);
        return awaitReceipt(txHash);
    }

    private Receipt verifyHash(String txHash) {
        return awaitReceipt(txHash);
    }

    private Receipt awaitReceipt(String txHash) {
        for (int i = 0; i < maxRetries; i++) {
            Map<String, Object> receipt = rpc.getTransactionReceipt(rpcUrl, txHash);
            if (receipt != null) {
                if (!"0x1".equals(receipt.get("status"))) {
                    throw new VerificationFailedException("transaction reverted");
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
}
