package com.stripe.mpp;

import com.stripe.mpp.server.Method;
import com.stripe.mpp.server.MppHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Main entry point for the MPP (Machine Payments Protocol) Java SDK.
 *
 * <h2>Server usage</h2>
 * <pre>{@code
 * MppHandler server = Mpp.create(myMethod, "api.example.com", "my-secret-key");
 *
 * // In your HTTP handler:
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     chargeIntent,
 *     "10.000000", "USDC", "0xRecipientAddress"
 * );
 * switch (result) {
 *     case VerifyResult.Challenged c ->
 *         // HTTP 402 — return the challenge
 *         response.setHeader("WWW-Authenticate", c.challenge().toWwwAuthenticate());
 *     case VerifyResult.Verified v ->
 *         // Payment verified — return the receipt
 *         response.setHeader("Payment-Receipt", v.receipt().toPaymentReceipt());
 * }
 * }</pre>
 *
 * <h2>Client usage</h2>
 * <pre>{@code
 * // Parse the challenge from a 402 response
 * List<Challenge> challenges = Challenge.fromWwwAuthenticate(wwwAuthenticateHeader);
 * Challenge challenge = challenges.get(0);
 *
 * // Build a credential with your payment payload
 * Credential credential = new Credential(challenge.toEcho(), myPayload, null);
 *
 * // Send the credential in the next request
 * nextRequest.setHeader("Authorization", credential.toAuthorization());
 * }</pre>
 */
public final class Mpp {
    private Mpp() {}

    /**
     * Create a new {@link MppHandler} configured with the given payment method, realm, and secret key.
     */
    public static MppHandler create(Method method, String realm, String secretKey) {
        return MppHandler.create(method, realm, secretKey);
    }

    /**
     * Generate an HMAC-SHA256 challenge ID bound to the given parameters.
     */
    public static String generateChallengeId(
        String secretKey, String realm, String method, String intent,
        Map<String, Object> request, String expires, String digest, Map<String, Object> opaque
    ) {
        return ChallengeId.generate(secretKey, realm, method, intent, request, expires, digest, opaque);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    public static boolean secureCompare(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
