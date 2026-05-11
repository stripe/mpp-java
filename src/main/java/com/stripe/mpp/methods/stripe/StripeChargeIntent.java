package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentExpiredException;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.Intent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Server-side intent that verifies Stripe payments using Shared Payment Granted Tokens (SPT).
 *
 * <p>The client credential payload must contain:
 * <ul>
 *   <li>{@code "spt"} — the Shared Payment Granted Token from Stripe</li>
 *   <li>{@code "externalId"} — optional external tracking identifier</li>
 * </ul>
 *
 * <pre>{@code
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     Stripe.chargeIntent(secretKey),
 *     "10.00", "usd", networkId
 * );
 * }</pre>
 */
public class StripeChargeIntent implements Intent {

    private final String secretKey;
    private final int decimals;
    private final StripeApi stripeApi;

    public StripeChargeIntent(String secretKey) {
        this(secretKey, StripeDefaults.DEFAULT_DECIMALS, new StripeApi());
    }

    StripeChargeIntent(String secretKey, int decimals, StripeApi stripeApi) {
        this.secretKey = secretKey;
        this.decimals = decimals;
        this.stripeApi = stripeApi;
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

        String spt = (String) payload.get("spt");
        if (spt == null) {
            throw new VerificationFailedException("missing spt in payload");
        }
        String externalId = (String) payload.get("externalId");

        String expires = credential.challenge().expires();
        if (expires != null) {
            try {
                if (Instant.parse(expires).isBefore(Instant.now())) {
                    throw new PaymentExpiredException(expires);
                }
            } catch (DateTimeParseException e) {
                throw new VerificationFailedException("invalid expiry format");
            }
        }

        String amount   = (String) request.get("amount");
        String currency = (String) request.get("currency");

        long amountMinorUnits;
        try {
            amountMinorUnits = new BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
        } catch (Exception e) {
            throw new VerificationFailedException("invalid amount: " + amount);
        }

        Map<String, String> metadata = extractMetadata(request);
        StripeApi.Result result = stripeApi.createAndConfirm(secretKey, amountMinorUnits, currency, spt, metadata);

        if ("requires_action".equals(result.status())) {
            throw new com.stripe.mpp.error.PaymentActionRequiredException(
                "PaymentIntent " + result.id() + " requires action");
        }
        if (!"succeeded".equals(result.status())) {
            throw new VerificationFailedException(
                "PaymentIntent " + result.id() + " has status: " + result.status());
        }

        return Receipt.success(result.id(), "stripe", externalId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractMetadata(Map<String, Object> request) {
        Object methodDetails = request.get("methodDetails");
        if (!(methodDetails instanceof Map<?, ?>)) return null;
        Object meta = ((Map<?, ?>) methodDetails).get("metadata");
        if (!(meta instanceof Map<?, ?>)) return null;
        return (Map<String, String>) meta;
    }
}
