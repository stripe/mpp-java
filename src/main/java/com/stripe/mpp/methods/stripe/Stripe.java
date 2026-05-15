package com.stripe.mpp.methods.stripe;

import java.util.List;
import java.util.Map;

/**
 * Factory for Stripe payment objects.
 *
 * <pre>{@code
 * StripeMethod stripe = Stripe.method(secretKey, networkId);
 *
 * MppHandler server = Mpp.create(stripe, "api.example.com", mppSecretKey);
 *
 * // In your HTTP handler:
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     stripe.chargeIntent(),
 *     "10.00", "usd", networkId
 * );
 * }</pre>
 */
public final class Stripe {
    private Stripe() {}

    /**
     * Returns a {@link StripeMethod} with default settings.
     *
     * @param secretKey Stripe secret API key
     * @param networkId Stripe profile/network identifier sent to the client in the challenge
     */
    public static StripeMethod method(String secretKey, String networkId) {
        return method(secretKey, networkId, null, null);
    }

    /**
     * Returns a {@link StripeMethod} with full configuration.
     *
     * @param secretKey      Stripe secret API key
     * @param networkId      Stripe network identifier sent to the client in the challenge
     * @param paymentMethods allowed payment method types (e.g. {@code List.of("card", "link")}); may be null
     * @param metadata       optional metadata attached to created PaymentIntents; may be null
     */
    public static StripeMethod method(
        String secretKey,
        String networkId,
        List<String> paymentMethods,
        Map<String, String> metadata
    ) {
        return new StripeMethod(
            secretKey, networkId, paymentMethods, metadata,
            StripeDefaults.DEFAULT_DECIMALS
        );
    }

    /**
     * Returns a standalone {@link StripeChargeIntent} for use when you manage
     * the {@link StripeMethod} yourself.
     */
    public static StripeChargeIntent chargeIntent(String secretKey) {
        return new StripeChargeIntent(secretKey);
    }
}
