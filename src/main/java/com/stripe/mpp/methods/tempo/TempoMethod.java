package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.util.List;

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
    private final String chain;
    private final TempoChargeIntent chargeIntent;

    private TempoMethod(String rpcUrl, String chain) {
        this.chain = chain;
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

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(TempoChargeIntent.class);
    }

    /** Returns the pre-configured charge intent to pass to {@link com.stripe.mpp.server.MppHandler#charge}. */
    public TempoChargeIntent chargeIntent() { return chargeIntent; }
}
