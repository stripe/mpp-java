package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPP payment method for Tempo.
 *
 * <pre>{@code
 * TempoMethod tempo = TempoMethod.mainnet().build();
 * TempoMethod tempo = TempoMethod.testnet().build();
 * TempoMethod tempo = TempoMethod.testnet().debug().build();
 * TempoMethod tempo = TempoMethod.custom("http://localhost:8545", 1337).build();
 * }</pre>
 */
public class TempoMethod implements Method {
    private final String rpcUrl;
    private final int chainId;
    private final int decimals;
    private final TempoChargeIntent chargeIntent;

    TempoMethod(String rpcUrl, int chainId) {
        this(rpcUrl, chainId, TempoDefaults.DEFAULT_DECIMALS, false);
    }

    TempoMethod(String rpcUrl, int chainId, boolean debug) {
        this(rpcUrl, chainId, TempoDefaults.DEFAULT_DECIMALS, debug);
    }

    TempoMethod(String rpcUrl, int chainId, int decimals) {
        this(rpcUrl, chainId, decimals, false);
    }

    TempoMethod(String rpcUrl, int chainId, int decimals, boolean debug) {
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
        this.decimals = decimals;
        this.chargeIntent = new TempoChargeIntent(rpcUrl, debug);
    }

    /** Starts a builder targeting Tempo mainnet (chain 4217). */
    public static Builder mainnet() {
        return new Builder(TempoDefaults.MAINNET_RPC, TempoDefaults.MAINNET_CHAIN_ID);
    }

    /** Starts a builder targeting Tempo testnet / Moderato (chain 42431). */
    public static Builder testnet() {
        return new Builder(TempoDefaults.TESTNET_RPC, TempoDefaults.TESTNET_CHAIN_ID);
    }

    /** Starts a builder targeting a custom RPC endpoint and chain ID. */
    public static Builder custom(String rpcUrl, int chainId) {
        return new Builder(rpcUrl, chainId);
    }

    public static final class Builder {
        private final String rpcUrl;
        private final int chainId;
        private boolean debug = false;

        private Builder(String rpcUrl, int chainId) {
            this.rpcUrl  = rpcUrl;
            this.chainId = chainId;
        }

        /** Enables INFO-level logging of raw JSON-RPC request/response bodies. */
        public Builder debug() { this.debug = true; return this; }

        public TempoMethod build() {
            return new TempoMethod(rpcUrl, chainId, TempoDefaults.DEFAULT_DECIMALS, debug);
        }
    }

    @Override public String name() { return "tempo"; }

    public int chainId() { return chainId; }
    public String rpcUrl() { return rpcUrl; }
    /** Returns the CAIP-2 network string (e.g. {@code "eip155:4217"}) for display purposes. */
    public String network() { return "eip155:" + chainId; }

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(TempoChargeIntent.class);
    }

    /** Returns the pre-configured charge intent to pass to {@link com.stripe.mpp.server.MppHandler#charge}. */
    public TempoChargeIntent chargeIntent() { return chargeIntent; }

    /**
     * Converts the decimal amount to atomic units and injects {@code methodDetails.chainId}
     * — the canonical format expected by purl and the mppx TypeScript SDK.
     */
    @Override
    public Map<String, Object> transformRequest(Map<String, Object> request) {
        String amount = (String) request.get("amount");
        if (amount == null) return request;
        try {
            String atomic = new BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact()
                .toString();
            Map<String, Object> result = new LinkedHashMap<>(request);
            result.put("amount", atomic);
            result.put("methodDetails", Map.of("chainId", chainId));
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid amount: " + amount, e);
        }
    }
}
