package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPP payment method for Tempo. Obtain instances via {@link Tempo#method()}.
 *
 * <pre>{@code
 * TempoMethod tempo = Tempo.method();          // mainnet
 * TempoMethod tempo = Tempo.method(true);      // testnet (Moderato)
 *
 * MppHandler server = Mpp.create(tempo, "api.example.com", secretKey);
 *
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     tempo.chargeIntent(),
 *     "10.000000", "USDC", "0xRecipient"
 * );
 * }</pre>
 */
public class TempoMethod implements Method {
    private final String rpcUrl;
    private final int chainId;
    private final int decimals;
    private final TempoChargeIntent chargeIntent;

    TempoMethod(String rpcUrl, int chainId) {
        this(rpcUrl, chainId, TempoDefaults.DEFAULT_DECIMALS);
    }

    TempoMethod(String rpcUrl, int chainId, int decimals) {
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
        this.decimals = decimals;
        this.chargeIntent = new TempoChargeIntent(rpcUrl);
    }

    static TempoMethod mainnet() {
        return new TempoMethod(TempoDefaults.MAINNET_RPC, TempoDefaults.MAINNET_CHAIN_ID);
    }

    static TempoMethod testnet() {
        return new TempoMethod(TempoDefaults.TESTNET_RPC, TempoDefaults.TESTNET_CHAIN_ID);
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
