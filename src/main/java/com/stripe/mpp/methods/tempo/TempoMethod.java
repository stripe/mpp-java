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
    private final String chain;
    private final int decimals;
    private final TempoChargeIntent chargeIntent;

    TempoMethod(String rpcUrl, String chain) {
        this(rpcUrl, chain, TempoDefaults.DEFAULT_DECIMALS);
    }

    TempoMethod(String rpcUrl, String chain, int decimals) {
        this.rpcUrl = rpcUrl;
        this.chain = chain;
        this.decimals = decimals;
        this.chargeIntent = new TempoChargeIntent(rpcUrl);
    }

    static TempoMethod mainnet() {
        return new TempoMethod(TempoDefaults.MAINNET_RPC, TempoDefaults.MAINNET_CHAIN);
    }

    static TempoMethod testnet() {
        return new TempoMethod(TempoDefaults.TESTNET_RPC, TempoDefaults.TESTNET_CHAIN);
    }

    @Override public String name()  { return "tempo"; }
    @Override public String chain() { return chain; }
    public String rpcUrl() { return rpcUrl; }

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(TempoChargeIntent.class);
    }

    /** Returns the pre-configured charge intent to pass to {@link com.stripe.mpp.server.MppHandler#charge}. */
    public TempoChargeIntent chargeIntent() { return chargeIntent; }

    /**
     * Converts the decimal amount string to atomic (integer) units before embedding it in the
     * challenge. Tempo clients parse the challenge amount as a U256 integer, so passing a decimal
     * like "0.010000" would cause an "invalid digit" parse error on the client side.
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

            // Also expose chain ID as methodDetails.chainId (integer) — the canonical
            // format expected by purl and the mppx TypeScript SDK. The top-level "chain"
            // CAIP-2 string is kept for backwards compatibility.
            String chainVal = (String) request.get("chain");
            if (chainVal != null && chainVal.startsWith("eip155:")) {
                try {
                    long chainId = Long.parseLong(chainVal.substring("eip155:".length()));
                    result.put("methodDetails", Map.of("chainId", chainId));
                } catch (NumberFormatException ignored) {}
            }

            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid amount: " + amount, e);
        }
    }
}
