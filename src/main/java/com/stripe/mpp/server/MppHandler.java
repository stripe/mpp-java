package com.stripe.mpp.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The main server-side MPP handler. Create one per application with your payment method,
 * realm, and secret key, then call {@link #charge} on each incoming request.
 *
 * <pre>{@code
 * MppHandler server = MppHandler.create(myMethod, "api.example.com", secretKey);
 *
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     chargeIntent,
 *     "10.000000", "USD", "0xRecipient"
 * );
 * if (result instanceof VerifyResult.Challenged) {
 *     VerifyResult.Challenged challenged = (VerifyResult.Challenged) result;
 *     response.setHeader("WWW-Authenticate", challenged.challenge().toWwwAuthenticate());
 * } else {
 *     VerifyResult.Verified verified = (VerifyResult.Verified) result;
 *     response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
 * }
 * }</pre>
 */
public class MppHandler {
    private final Method method;
    private final String realm;
    private final String secretKey;
    private final Map<String, Object> defaults;

    private MppHandler(Method method, String realm, String secretKey, Map<String, Object> defaults) {
        this.method = method;
        this.realm = realm;
        this.secretKey = secretKey;
        this.defaults = defaults;
    }

    public static MppHandler create(Method method, String realm, String secretKey) {
        return new MppHandler(method, realm, secretKey, Map.of());
    }

    public static MppHandler create(Method method, String realm, String secretKey, Map<String, Object> defaults) {
        return new MppHandler(method, realm, secretKey, Map.copyOf(defaults));
    }

    public Method method() { return method; }
    public String realm() { return realm; }
    public String secretKey() { return secretKey; }
    public Map<String, Object> defaults() { return defaults; }

    /**
     * Verify a payment credential or issue a new challenge.
     *
     * @param authorization  the Authorization header value (may be null)
     * @param intent         the intent to authorize
     * @param amount         the charge amount (e.g. "10.000000"); falls back to defaults
     * @param currency       the currency code (e.g. "USDC"); falls back to defaults
     * @param recipient      the recipient address; falls back to defaults
     * @param description    optional human-readable description shown in wallets
     * @param meta           optional opaque metadata passed through the challenge
     * @param expires        optional ISO-8601 expiry; defaults to 5 minutes from now
     */
    public VerifyResult charge(
        String authorization,
        Intent intent,
        String amount,
        String currency,
        String recipient,
        String description,
        Map<String, Object> meta,
        String expires
    ) {
        if (!method.intents().contains(intent.getClass())) {
            throw new IllegalArgumentException("Method does not support " + intent.getClass().getSimpleName() + " intents");
        }

        String resolvedCurrency  = currency  != null ? currency  : (String) defaults.get("currency");
        String resolvedRecipient = recipient != null ? recipient : (String) defaults.get("recipient");
        String resolvedAmount    = amount    != null ? amount    : (String) defaults.get("amount");

        if (resolvedCurrency  == null) throw new IllegalArgumentException("Currency is required");
        if (resolvedRecipient == null) throw new IllegalArgumentException("Recipient is required");
        if (resolvedAmount    == null) throw new IllegalArgumentException("Amount is required");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount",    resolvedAmount);
        request.put("currency",  resolvedCurrency);
        request.put("recipient", resolvedRecipient);
        if (method.memo()     != null) request.put("memo",      method.memo());
        if (method.feePayer() != null) request.put("fee_payer", method.feePayer());
        if (method.chain()    != null) request.put("chain",     method.chain());

        request = method.transformRequest(request);

        return Verify.verifyOrChallenge(
            authorization, intent, request, realm, secretKey,
            method.name(), description, meta, expires
        );
    }

    /** Convenience overload with required fields only. */
    public VerifyResult charge(String authorization, Intent intent, String amount, String currency, String recipient) {
        return charge(authorization, intent, amount, currency, recipient, null, null, null);
    }

    /** Convenience overload using all defaults. */
    public VerifyResult charge(String authorization, Intent intent) {
        return charge(authorization, intent, null, null, null, null, null, null);
    }

    /**
     * Create a {@link ChargeDescriptor} pre-configured with the given parameters for use
     * with {@link com.stripe.mpp.Mpp#compose}.
     */
    public ChargeDescriptor chargeDescriptor(
        Intent intent,
        String amount,
        String currency,
        String recipient,
        String description,
        Map<String, Object> meta,
        String expires
    ) {
        return new ChargeDescriptor(this, intent, amount, currency, recipient, description, meta, expires);
    }

    /** Convenience overload with required fields only. */
    public ChargeDescriptor chargeDescriptor(Intent intent, String amount, String currency, String recipient) {
        return chargeDescriptor(intent, amount, currency, recipient, null, null, null);
    }

    /** Build the request map for this handler and descriptor (applies defaults and method transforms). */
    Map<String, Object> buildRequest(ChargeDescriptor d) {
        String resolvedCurrency  = d.currency()  != null ? d.currency()  : (String) defaults.get("currency");
        String resolvedRecipient = d.recipient() != null ? d.recipient() : (String) defaults.get("recipient");
        String resolvedAmount    = d.amount()    != null ? d.amount()    : (String) defaults.get("amount");

        if (resolvedCurrency  == null) throw new IllegalArgumentException("Currency is required");
        if (resolvedRecipient == null) throw new IllegalArgumentException("Recipient is required");
        if (resolvedAmount    == null) throw new IllegalArgumentException("Amount is required");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount",    resolvedAmount);
        request.put("currency",  resolvedCurrency);
        request.put("recipient", resolvedRecipient);
        if (method.memo()     != null) request.put("memo",      method.memo());
        if (method.feePayer() != null) request.put("fee_payer", method.feePayer());
        if (method.chain()    != null) request.put("chain",     method.chain());

        return method.transformRequest(request);
    }
}
