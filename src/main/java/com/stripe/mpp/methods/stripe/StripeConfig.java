package com.stripe.mpp.methods.stripe;

import java.util.List;
import java.util.Map;

/**
 * Fluent configuration builder for {@link StripeMethod}.
 *
 * <pre>{@code
 * // Minimal
 * StripeMethod stripe = StripeConfig.of(secretKey, networkId).build();
 *
 * // With optional settings
 * StripeMethod stripe = StripeConfig.of(secretKey, networkId)
 *     .paymentMethods(List.of("card", "link"))
 *     .metadata(Map.of("source", "mpp"))
 *     .build();
 * }</pre>
 */
public final class StripeConfig {
    private final String secretKey;
    private final String networkId;
    private List<String> paymentMethods;
    private Map<String, String> metadata;

    private StripeConfig(String secretKey, String networkId) {
        this.secretKey = secretKey;
        this.networkId = networkId;
    }

    /**
     * @param secretKey Stripe secret API key
     * @param networkId Stripe profile/network identifier sent to the client in the challenge
     */
    public static StripeConfig of(String secretKey, String networkId) {
        return new StripeConfig(secretKey, networkId);
    }

    /** Restrict which Stripe payment method types the client may use (e.g. {@code "card"}, {@code "link"}). */
    public StripeConfig paymentMethods(List<String> paymentMethods) {
        this.paymentMethods = paymentMethods;
        return this;
    }

    /** Metadata attached to created Stripe PaymentIntents. */
    public StripeConfig metadata(Map<String, String> metadata) {
        this.metadata = metadata;
        return this;
    }

    public StripeMethod build() {
        return new StripeMethod(secretKey, networkId, paymentMethods, metadata, StripeDefaults.DEFAULT_DECIMALS);
    }
}
