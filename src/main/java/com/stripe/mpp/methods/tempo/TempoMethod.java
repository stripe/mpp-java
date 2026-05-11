package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.util.List;

/**
 * MPP payment method for Tempo, an EVM-based stablecoin payment network.
 *
 * <pre>{@code
 * TempoMethod tempo = TempoMethod.create(
 *     "https://rpc.example.com",
 *     "eip155:84532"                  // chain ID
 * );
 * MppHandler server = Mpp.create(tempo, "api.example.com", secretKey);
 *
 * // In your HTTP handler:
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     tempo.chargeIntent(),
 *     "10.000000", "USDC", "0xRecipient"
 * );
 * }</pre>
 */
public class TempoMethod implements Method {
    private final String chain;
    private final String memo;
    private final String feePayer;
    private final TempoChargeIntent chargeIntent;

    private TempoMethod(String rpcUrl, String chain, String memo, String feePayer) {
        this.chain = chain;
        this.memo = memo;
        this.feePayer = feePayer;
        this.chargeIntent = new TempoChargeIntent(rpcUrl);
    }

    public static TempoMethod create(String rpcUrl, String chain) {
        return new TempoMethod(rpcUrl, chain, null, null);
    }

    public static TempoMethod create(String rpcUrl, String chain, String memo, String feePayer) {
        return new TempoMethod(rpcUrl, chain, memo, feePayer);
    }

    @Override public String name()    { return "tempo"; }
    @Override public String chain()   { return chain; }
    @Override public String memo()    { return memo; }
    @Override public String feePayer(){ return feePayer; }

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(TempoChargeIntent.class);
    }

    /** Returns the pre-configured charge intent to pass to {@link com.stripe.mpp.server.MppHandler#charge}. */
    public TempoChargeIntent chargeIntent() { return chargeIntent; }
}
