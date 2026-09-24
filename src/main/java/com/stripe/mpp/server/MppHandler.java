package com.stripe.mpp.server;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.MalformedCredentialException;
import com.stripe.mpp.error.ParseException;

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
     * Validate a credential without broadcasting or consuming it.
     *
     * <p>The echoed challenge is HMAC-checked, matched to this handler, and checked for expiry
     * before the intent's non-mutating validation hook runs.
     */
    public ValidationResult validateCredential(String authorization, Intent intent) {
        return validateCredential(authorization, intent, null);
    }

    /** Validate a credential and its binding to the current request body. */
    public ValidationResult validateCredential(String authorization, Intent intent, Object body) {
        return validateCredential(Verify.parseCredential(authorization), intent, body);
    }

    /** Validate a parsed credential without broadcasting or consuming it. */
    public ValidationResult validateCredential(Credential credential, Intent intent) {
        return validateCredential(credential, intent, null);
    }

    /** Validate a parsed credential and its binding to the current request body. */
    public ValidationResult validateCredential(Credential credential, Intent intent, Object body) {
        Map<String, Object> request = prepareCredential(credential, intent, body);
        return intent.validate(credential, request);
    }

    /**
     * Re-validate and perform the terminal payment operation for a credential.
     */
    public Receipt broadcastCredential(String authorization, Intent intent) {
        return broadcastCredential(authorization, intent, null);
    }

    /** Re-validate the credential and body before performing the terminal payment operation. */
    public Receipt broadcastCredential(String authorization, Intent intent, Object body) {
        return broadcastCredential(Verify.parseCredential(authorization), intent, body);
    }

    /** Re-validate and perform the terminal payment operation for a parsed credential. */
    public Receipt broadcastCredential(Credential credential, Intent intent) {
        return broadcastCredential(credential, intent, null);
    }

    /** Re-validate a parsed credential and body before the terminal payment operation. */
    public Receipt broadcastCredential(Credential credential, Intent intent, Object body) {
        Map<String, Object> request = prepareCredential(credential, intent, body);
        return intent.verify(credential, request);
    }

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
        return charge(
            authorization, intent, amount, currency, recipient,
            description, meta, expires, null
        );
    }

    /**
     * Verify a payment credential or issue a challenge bound to the current request body.
     */
    public VerifyResult charge(
        String authorization,
        Intent intent,
        String amount,
        String currency,
        String recipient,
        String description,
        Map<String, Object> meta,
        String expires,
        Object body
    ) {
        requireSupported(intent);

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
        if (method.feePayer() != null) request.put("fee_payer", method.feePayer());
        if (method.chain()    != null) request.put("chain",     method.chain());

        request = method.transformRequest(request);

        return Verify.verifyOrChallenge(
            authorization, intent, request, realm, secretKey,
            method.name(), description, meta, expires, body
        );
    }

    /** Convenience overload with required fields only. */
    public VerifyResult charge(String authorization, Intent intent, String amount, String currency, String recipient) {
        return charge(authorization, intent, amount, currency, recipient, null, null, null);
    }

    /** Convenience overload with required fields and a request body. */
    public VerifyResult charge(
        String authorization, Intent intent, String amount, String currency,
        String recipient, Object body
    ) {
        return charge(
            authorization, intent, amount, currency, recipient, null, null, null, body
        );
    }

    /** Convenience overload using all defaults. */
    public VerifyResult charge(String authorization, Intent intent) {
        return charge(authorization, intent, null, null, null, null, null, null);
    }

    /** Overload accepting a {@link ChargeRequest} — avoids trailing nulls for optional fields. */
    public VerifyResult charge(String authorization, ChargeRequest req) {
        return charge(authorization, req.intent(), req.amount(), req.currency(),
                      req.recipient(), req.description(), req.meta(), req.expires(), req.body());
    }

    /**
     * Mint a fresh payment challenge for the given charge, e.g. for the WWW-Authenticate
     * header of an error response.
     */
    public Challenge challenge(ChargeRequest req) {
        requireSupported(req.intent());
        Map<String, Object> request = buildRequest(chargeDescriptor(req));
        return Verify.createChallenge(
            method.name(), req.intent(), request, realm, secretKey,
            req.description(), req.meta(), req.expires(), req.body()
        );
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
        return chargeDescriptor(
            intent, amount, currency, recipient, description, meta, expires, null
        );
    }

    /** Create a composed charge slot with a request body to bind. */
    public ChargeDescriptor chargeDescriptor(
        Intent intent,
        String amount,
        String currency,
        String recipient,
        String description,
        Map<String, Object> meta,
        String expires,
        Object body
    ) {
        return new ChargeDescriptor(
            this, intent, amount, currency, recipient, description, meta, expires, body
        );
    }

    /** Convenience overload with required fields only. */
    public ChargeDescriptor chargeDescriptor(Intent intent, String amount, String currency, String recipient) {
        return chargeDescriptor(intent, amount, currency, recipient, null, null, null);
    }

    /** Overload accepting a {@link ChargeRequest} — avoids trailing nulls for optional fields. */
    public ChargeDescriptor chargeDescriptor(ChargeRequest req) {
        return chargeDescriptor(req.intent(), req.amount(), req.currency(), req.recipient(),
                                req.description(), req.meta(), req.expires(), req.body());
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
        if (method.feePayer() != null) request.put("fee_payer", method.feePayer());
        if (method.chain()    != null) request.put("chain",     method.chain());

        return method.transformRequest(request);
    }

    private Map<String, Object> prepareCredential(
        Credential credential, Intent intent, Object body
    ) {
        requireSupported(intent);
        try {
            Map<String, Object> request = Verify.assertCredential(
                credential, intent, realm, secretKey, method.name()
            );
            Verify.assertBodyDigest(credential, body);
            return request;
        } catch (ParseException e) {
            throw new MalformedCredentialException(e.getMessage());
        }
    }

    private void requireSupported(Intent intent) {
        if (method.intents().stream().noneMatch(type -> type.isInstance(intent))) {
            throw new IllegalArgumentException("Method does not support " + intent.getClass().getSimpleName() + " intents");
        }
    }
}
