package com.stripe.mpp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public final class ChallengeId {
    private ChallengeId() {}

    /**
     * Generate an HMAC-SHA256 challenge ID bound to the given parameters.
     * The ID is base64url-encoded (no padding).
     */
    public static String generate(
        String secretKey,
        String realm,
        String method,
        String intent,
        Map<String, Object> request,
        String expires,
        String digest,
        Map<String, Object> opaque
    ) {
        return generateWithOpaque(
            secretKey, realm, method, intent, request, expires, digest,
            encodeOpaque(opaque)
        );
    }

    /** Generate an ID using the exact wire-format opaque value. */
    public static String generateWithOpaque(
        String secretKey,
        String realm,
        String method,
        String intent,
        Map<String, Object> request,
        String expires,
        String digest,
        String opaque
    ) {
        String requestB64 = b64urlEncode(Json.compact(request));

        String input = String.join("|",
            realm,
            method,
            intent,
            requestB64,
            expires != null ? expires : "",
            digest != null ? digest : "",
            opaque != null ? opaque : ""
        );

        byte[] hmac = hmacSha256(
            secretKey.getBytes(StandardCharsets.UTF_8),
            input.getBytes(StandardCharsets.UTF_8)
        );
        return b64urlEncodeBytes(hmac);
    }

    /**
     * Encode opaque metadata to the wire form bound by {@link #generateWithOpaque}
     * (base64url of compact JSON), or null when there is no metadata.
     */
    public static String encodeOpaque(Map<String, Object> opaque) {
        return opaque != null ? b64urlEncode(Json.compact(opaque)) : null;
    }

    public static String b64urlEncode(String str) {
        return b64urlEncodeBytes(str.getBytes(StandardCharsets.UTF_8));
    }

    public static String b64urlEncodeBytes(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] b64urlDecode(String encoded) {
        // Add padding if needed before decoding
        int pad = (4 - encoded.length() % 4) % 4;
        String padded = encoded + "=".repeat(pad);
        return Base64.getUrlDecoder().decode(padded);
    }

    /** Decode a base64url string to a JSON map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> b64urlDecodeToMap(String encoded) {
        byte[] bytes;
        try {
            bytes = b64urlDecode(encoded);
        } catch (IllegalArgumentException e) {
            throw new com.stripe.mpp.error.ParseException("Invalid base64url encoding", e);
        }
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        try {
            return Json.MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new com.stripe.mpp.error.ParseException("Expected JSON object in base64 field: " + e.getMessage());
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }
}
