package com.stripe.mpp.server;

import com.stripe.mpp.BodyDigest;
import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.InvalidChallengeException;
import com.stripe.mpp.error.MalformedCredentialException;
import com.stripe.mpp.error.ParseException;
import com.stripe.mpp.error.PaymentExpiredException;

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
        return verifyOrChallenge(
            authorization, intent, request, realm, secretKey, methodName,
            description, meta, expires, null
        );
    }

    /**
     * Verify the Authorization header credential or issue a new challenge, binding the
     * credential to {@code body} when one is present.
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
        String expires,
        Object body
    ) {
        Credential credential;
        try {
            credential = parseCredential(authorization);
        } catch (MalformedCredentialException e) {
            return new VerifyResult.Challenged(createChallenge(
                methodName, intent, request, realm, secretKey, description, meta, expires, body
            ));
        }

        try {
            Map<String, Object> echoRequest = assertCredential(credential, intent, realm, secretKey, methodName);
            // Canonical JSON avoids Integer-vs-Long mismatches after decoding.
            if (!Json.compact(echoRequest).equals(Json.compact(request))) {
                throw new InvalidChallengeException(credential.challenge().id(), "request does not match");
            }
            if (!Objects.equals(credential.challenge().opaqueRaw(), ChallengeId.encodeOpaque(meta))) {
                throw new InvalidChallengeException(credential.challenge().id(), "opaque data does not match");
            }
            assertBodyDigest(credential, body);
        } catch (ParseException | InvalidChallengeException | PaymentExpiredException e) {
            return new VerifyResult.Challenged(createChallenge(
                methodName, intent, request, realm, secretKey, description, meta, expires, body
            ));
        }

        Receipt receipt = intent.verify(credential, request);
        return new VerifyResult.Verified(credential, receipt);
    }

    /** Parse the Authorization header into a Credential. */
    static Credential parseCredential(String authorization) {
        if (authorization == null) {
            throw new MalformedCredentialException("missing Authorization header");
        }
        String payment = extractPaymentScheme(authorization);
        if (payment == null) {
            throw new MalformedCredentialException("missing Payment scheme");
        }
        try {
            return Credential.fromAuthorization(payment);
        } catch (ParseException e) {
            throw new MalformedCredentialException(e.getMessage());
        }
    }

    /**
     * Verify challenge provenance (HMAC binding, method/route match) and expiry for a parsed
     * credential. Whether the echoed request and opaque match the server's expectations stays
     * with the caller: the charge path checks them against the current route, while standalone
     * lifecycle calls trust the HMAC-bound echo as-is.
     *
     * @return the request decoded from the echoed challenge
     */
    static Map<String, Object> assertCredential(
        Credential credential,
        Intent intent,
        String realm,
        String secretKey,
        String methodName
    ) {
        ChallengeEcho echo = credential.challenge();
        if (echo == null || echo.id() == null || echo.realm() == null
            || echo.method() == null || echo.intent() == null
            || echo.request() == null || echo.request().isEmpty()) {
            throw new InvalidChallengeException(null, "missing required challenge fields");
        }

        Map<String, Object> echoRequest = ChallengeId.b64urlDecodeToMap(echo.request());
        String echoOpaque = echo.opaqueRaw();

        String expectedId = ChallengeId.generateWithOpaque(
            secretKey, echo.realm(), echo.method(), echo.intent(),
            echoRequest, echo.expires(), echo.digest(), echoOpaque
        );
        if (!secureCompare(echo.id(), expectedId)) {
            throw new InvalidChallengeException(echo.id(), "challenge binding does not match");
        }

        if (!realm.equals(echo.realm()) || !methodName.equals(echo.method())
            || !intent.name().equals(echo.intent())) {
            throw new InvalidChallengeException(echo.id(), "method or route does not match");
        }

        if (echo.expires() == null) {
            throw new InvalidChallengeException(echo.id(), "missing expiry");
        }
        try {
            if (Instant.parse(echo.expires()).isBefore(Instant.now())) {
                throw new PaymentExpiredException(echo.expires());
            }
        } catch (DateTimeParseException e) {
            throw new InvalidChallengeException(echo.id(), "invalid expiry");
        }

        return echoRequest;
    }

    /** Fail closed unless the current body and the echoed digest are both absent or match. */
    static void assertBodyDigest(Credential credential, Object body) {
        String digest = credential.challenge().digest();
        boolean matches = body == null
            ? digest == null
            : digest != null && BodyDigest.verify(digest, body);
        if (!matches) {
            throw new InvalidChallengeException(
                credential.challenge().id(), "body digest does not match"
            );
        }
    }

    static Challenge createChallenge(
        String methodName, Intent intent, Map<String, Object> request,
        String realm, String secretKey, String description,
        Map<String, Object> meta, String expires
    ) {
        return createChallenge(
            methodName, intent, request, realm, secretKey, description, meta, expires, null
        );
    }

    static Challenge createChallenge(
        String methodName, Intent intent, Map<String, Object> request,
        String realm, String secretKey, String description,
        Map<String, Object> meta, String expires, Object body
    ) {
        String resolvedExpires = expires;
        if (resolvedExpires == null) {
            resolvedExpires = Instant.now().plusSeconds(DEFAULT_EXPIRES_MINUTES * 60L).toString();
        }
        String digest = body == null ? null : BodyDigest.compute(body);
        return Challenge.create(
            secretKey, realm, methodName, intent.name(), request,
            resolvedExpires, digest, description, meta
        );
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
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
