package com.stripe.mpp.server;

import java.util.Map;

/**
 * Parameters for a single charge, passed to {@link MppHandler#charge(String, ChargeRequest)}.
 *
 * <p>Required parameters are supplied via {@link #of}; optional ones via fluent setters:
 *
 * <pre>{@code
 * VerifyResult result = mppHandler.charge(
 *     request.getHeader("Authorization"),
 *     ChargeRequest.of(tempo.chargeIntent(), "0.50", TempoDefaults.MAINNET_USDC, "0xRecipient")
 *         .description("Paid endpoint")
 * );
 * }</pre>
 */
public final class ChargeRequest {
    private final Intent intent;
    private final String amount;
    private final String currency;
    private final String recipient;
    private String description;
    private Map<String, Object> meta;
    private String expires;

    private ChargeRequest(Intent intent, String amount, String currency, String recipient) {
        this.intent    = intent;
        this.amount    = amount;
        this.currency  = currency;
        this.recipient = recipient;
    }

    /**
     * Create a charge request with the required fields.
     *
     * @param intent    the payment intent (e.g. {@code tempo.chargeIntent()})
     * @param amount    decimal amount (e.g. {@code "0.50"})
     * @param currency  token contract address for Tempo; currency code for Stripe (e.g. {@code "usd"})
     * @param recipient wallet address for Tempo; Stripe profile ID for Stripe
     */
    public static ChargeRequest of(Intent intent, String amount, String currency, String recipient) {
        return new ChargeRequest(intent, amount, currency, recipient);
    }

    /** Human-readable description shown in wallet UIs. */
    public ChargeRequest description(String description) {
        this.description = description;
        return this;
    }

    /** Opaque metadata passed through the challenge unchanged. */
    public ChargeRequest meta(Map<String, Object> meta) {
        this.meta = meta;
        return this;
    }

    /** ISO-8601 expiry; defaults to 5 minutes from now if unset. */
    public ChargeRequest expires(String expires) {
        this.expires = expires;
        return this;
    }

    public Intent intent()      { return intent; }
    public String amount()      { return amount; }
    public String currency()    { return currency; }
    public String recipient()   { return recipient; }
    public String description() { return description; }
    public Map<String, Object> meta()    { return meta; }
    public String expires()     { return expires; }
}
