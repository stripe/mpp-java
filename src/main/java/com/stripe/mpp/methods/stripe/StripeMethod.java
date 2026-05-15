package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MPP payment method for Stripe.
 *
 * <pre>{@code
 * StripeMethod stripe = StripeMethod.of(secretKey, networkId).build();
 *
 * StripeMethod stripe = StripeMethod.of(secretKey, networkId)
 *     .paymentMethods(List.of("card", "link"))
 *     .metadata(Map.of("source", "mpp"))
 *     .build();
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

    /**
     * Starts a builder for a Stripe payment method.
     *
     * @param secretKey Stripe secret API key
     * @param networkId Stripe profile/network identifier sent to the client in the challenge
     */
    public static Builder of(String secretKey, String networkId) {
        return new Builder(secretKey, networkId);
    }

    public static final class Builder {
        private final String secretKey;
        private final String networkId;
        private List<String> paymentMethods;
        private Map<String, String> metadata;

        private Builder(String secretKey, String networkId) {
            this.secretKey = secretKey;
            this.networkId = networkId;
        }

        /** Restrict which Stripe payment method types the client may use (e.g. {@code "card"}, {@code "link"}). */
        public Builder paymentMethods(List<String> paymentMethods) {
            this.paymentMethods = paymentMethods;
            return this;
        }

        /** Metadata attached to created Stripe PaymentIntents. */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public StripeMethod build() {
            return new StripeMethod(secretKey, networkId, paymentMethods, metadata, StripeDefaults.DEFAULT_DECIMALS);
        }
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
