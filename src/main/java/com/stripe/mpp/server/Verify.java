package com.stripe.mpp.server;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.ParseException;
import com.stripe.mpp.error.PaymentException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless payment verification logic.
 */
public final class Verify {
    private Verify() {}

    static final int DEFAULT_EXPIRES_MINUTES = 5;

    /**
     * Verify the Authorization header credential or issue a new challenge.
     *
     * @return {@link VerifyResult.Challenged} if payment is required,
     *         {@link VerifyResult.Verified} if the credential was accepted.
     */
    public static VerifyResult verifyOrChallenge(
        String authorization,
        Intent intent,
        Map<String, Object> request,
        String realm,
        String secretKey,
        String methodName,
        String description,
        Map<String, Object> meta,
        String expires
    ) {
        if (authorization == null) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        String paymentScheme = extractPaymentScheme(authorization);
        if (paymentScheme == null) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        Credential credential;
        try {
            credential = Credential.fromAuthorization(paymentScheme);
        } catch (ParseException e) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        ChallengeEcho echo = credential.challenge();

        // Decode the echoed request and opaque back to maps for HMAC verification
        Map<String, Object> echoRequest;
        Map<String, Object> echoOpaque;
        try {
            echoRequest = (echo.request() == null || echo.request().isEmpty())
                ? Map.of()
                : ChallengeId.b64urlDecodeToMap(echo.request());
            echoOpaque = echo.opaque();
        } catch (ParseException e) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Recompute the challenge ID and compare using constant-time comparison
        String expectedId = ChallengeId.generate(
            secretKey, echo.realm(), echo.method(), echo.intent(),
            echoRequest, echo.expires(), echo.digest(), echoOpaque
        );
        if (!secureCompare(echo.id(), expectedId)) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Verify echoed fields match the current server state
        if (!realm.equals(echo.realm()) || !methodName.equals(echo.method()) || !intent.name().equals(echo.intent())) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Verify echoed request matches expected request. Compare via canonical JSON
        // rather than Java object equality to avoid Integer vs Long mismatches that
        // arise when Jackson deserializes numeric values from the echoed base64 request.
        if (!Json.compact(echoRequest).equals(Json.compact(request))) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Verify echoed meta/opaque matches
        if (echoOpaque != null || meta != null) {
            String echoOpaqueJson = Json.compact(echoOpaque != null ? echoOpaque : Map.of());
            String metaJson = Json.compact(meta != null ? meta : Map.of());
            if (!echoOpaqueJson.equals(metaJson)) {
                return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
            }
        }

        // Challenges must always have an expiry (fail closed)
        if (echo.expires() == null) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Reject expired challenges
        try {
            Instant expiry = Instant.parse(echo.expires());
            if (expiry.isBefore(Instant.now())) {
                return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
            }
        } catch (DateTimeParseException e) {
            return new VerifyResult.Challenged(createChallenge(methodName, intent, request, realm, secretKey, description, meta, expires));
        }

        // Delegate to the intent for payment-method-specific verification
        try {
            Receipt receipt = intent.verify(credential, request);
            return new VerifyResult.Verified(credential, receipt);
        } catch (PaymentException e) {
            throw e;
        }
    }

    static Challenge createChallenge(
        String methodName, Intent intent, Map<String, Object> request,
        String realm, String secretKey, String description,
        Map<String, Object> meta, String expires
    ) {
        String resolvedExpires = expires;
        if (resolvedExpires == null) {
            resolvedExpires = Instant.now().plusSeconds(DEFAULT_EXPIRES_MINUTES * 60L).toString();
        }
        return Challenge.create(secretKey, realm, methodName, intent.name(), request, resolvedExpires, description, meta);
    }

    /**
     * Return the full "Payment ..." auth-params string from the Authorization header.
     * Auth-params are comma-separated, so we cannot split by comma — instead we find
     * the scheme token boundary and return everything from there to end of string.
     */
    static String extractPaymentScheme(String header) {
        String lower = header.toLowerCase();
        if (lower.startsWith("payment ")) return header;
        int idx = lower.indexOf(", payment ");
        if (idx >= 0) return header.substring(idx + 2); // keep "Payment ..." part
        return null;
    }

    static boolean secureCompare(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
