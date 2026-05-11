package com.stripe.mpp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Utilities for computing and verifying SHA-256 digests of request bodies.
 * The digest format is {@code sha-256=<base64>} as per RFC 9530.
 */
public final class BodyDigest {
    private BodyDigest() {}

    /**
     * Compute a SHA-256 digest of the given body. Maps are JSON-serialized first.
     */
    public static String compute(Object body) {
        byte[] bytes = toBytes(body);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(bytes);
            return "sha-256=" + Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    /**
     * Verify that a body matches the given digest string.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public static boolean verify(String digest, Object body) {
        String expected = compute(body);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            digest.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static byte[] toBytes(Object body) {
        if (body instanceof byte[] b) return b;
        String str = body instanceof Map ? Json.compact(body) : body.toString();
        return str.getBytes(StandardCharsets.UTF_8);
    }
}
