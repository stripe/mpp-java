package com.stripe.mpp;

import com.stripe.mpp.server.ChargeDescriptor;
import com.stripe.mpp.server.ComposedHandler;
import com.stripe.mpp.server.Method;
import com.stripe.mpp.server.MppHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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
 * if (result instanceof VerifyResult.Challenged) {
 *     VerifyResult.Challenged challenged = (VerifyResult.Challenged) result;
 *     // HTTP 402 — return the challenge
 *     response.setHeader("WWW-Authenticate", challenged.challenge().toWwwAuthenticate());
 * } else {
 *     VerifyResult.Verified verified = (VerifyResult.Verified) result;
 *     // Payment verified — return the receipt
 *     response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
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
     * Compose multiple payment methods into a single handler that presents all challenges at once.
     *
     * <pre>{@code
     * ComposedHandler composed = Mpp.compose(
     *     tempoHandler.chargeDescriptor(tempoIntent, "1.000000", "USDC", "0xRecipient"),
     *     stripeHandler.chargeDescriptor(stripeIntent, "1.00", "usd", null)
     * );
     *
     * VerifyResult result = composed.charge(request.getHeader("Authorization"));
     * if (result instanceof VerifyResult.Challenged) {
     *     List<String> headers = Challenge.toWwwAuthenticate(
     *         ((VerifyResult.Challenged) result).challenges()
     *     );
     *     headers.forEach(h -> response.addHeader("WWW-Authenticate", h));
     * } else {
     *     VerifyResult.Verified verified = (VerifyResult.Verified) result;
     *     response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
     * }
     * }</pre>
     */
    public static ComposedHandler compose(ChargeDescriptor... descriptors) {
        return new ComposedHandler(Arrays.asList(descriptors));
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
