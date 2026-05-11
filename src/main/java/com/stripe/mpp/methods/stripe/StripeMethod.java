package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPP payment method for Stripe. Obtain instances via {@link Stripe#method}.
 *
 * <pre>{@code
 * StripeMethod stripe = Stripe.method(secretKey, networkId);
 *
 * MppHandler server = Mpp.create(stripe, "api.example.com", secretKey);
 *
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     stripe.chargeIntent(),
 *     "10.00", "usd", networkId
 * );
 * }</pre>
 */
public class StripeMethod implements Method {

    private final String networkId;
    private final List<String> paymentMethods;
    private final Map<String, String> metadata;
    private final StripeChargeIntent chargeIntent;

    StripeMethod(
        String secretKey,
        String networkId,
        List<String> paymentMethods,
        Map<String, String> metadata,
        int decimals
    ) {
        this.networkId      = networkId;
        this.paymentMethods = paymentMethods;
        this.metadata       = metadata;
        this.chargeIntent   = new StripeChargeIntent(secretKey, decimals, new StripeApi());
    }

    @Override public String name() { return "stripe"; }

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(StripeChargeIntent.class);
    }

    /** Returns the pre-configured charge intent to pass to {@link com.stripe.mpp.server.MppHandler#charge}. */
    public StripeChargeIntent chargeIntent() { return chargeIntent; }

    @Override
    public Map<String, Object> transformRequest(Map<String, Object> request) {
        Map<String, Object> methodDetails = new LinkedHashMap<>();
        methodDetails.put("networkId", networkId);
        if (paymentMethods != null) methodDetails.put("paymentMethods", paymentMethods);
        if (metadata       != null) methodDetails.put("metadata", metadata);

        Map<String, Object> result = new LinkedHashMap<>(request);
        result.put("methodDetails", methodDetails);
        return result;
    }
}
