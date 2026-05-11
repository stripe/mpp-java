package com.stripe.mpp;

import com.stripe.mpp.error.ParseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wire-format parsing and serialization for MPP HTTP headers.
 */
final class Parsing {
    private Parsing() {}

    static final int MAX_PAYLOAD_SIZE = 16384;

    // Matches: key="quoted value" or key=token
    private static final Pattern AUTH_PARAM = Pattern.compile(
        "(\\w+)=(?:\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|([^\\s,]+))"
    );

    // --- Encoding helpers ---

    static String b64Encode(Object data) {
        String str = data instanceof Map ? Json.compact(data) : data.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(str.getBytes(StandardCharsets.UTF_8));
    }

    /** Decode base64url to a parsed JSON object, or return the raw string if not JSON. */
    static Object b64Decode(String encoded) {
        byte[] bytes = ChallengeId.b64urlDecode(encoded);
        String str = new String(bytes, StandardCharsets.UTF_8);
        try {
            return Json.parse(str);
        } catch (Exception e) {
            return str;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> b64DecodeToMap(String encoded) {
        Object decoded = b64Decode(encoded);
        if (decoded instanceof Map) return (Map<String, Object>) decoded;
        throw new ParseException("Expected JSON object in base64 field");
    }

    // --- Auth-params parser ---

    static Map<String, String> parseAuthParams(String input) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = AUTH_PARAM.matcher(input);
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            // Unescape backslash sequences in quoted strings
            if (m.group(2) != null) value = value.replace("\\\"", "\"").replace("\\\\", "\\");
            params.put(key, value);
        }
        return params;
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    // --- WWW-Authenticate (Challenge) ---

    static String formatWwwAuthenticate(Challenge challenge) {
        List<String> parts = new ArrayList<>();
        parts.add("id=" + quote(challenge.id()));
        parts.add("realm=" + quote(challenge.realm()));
        parts.add("method=" + quote(challenge.method()));
        parts.add("intent=" + quote(challenge.intent()));
        parts.add("request=" + quote(challenge.requestB64()));
        if (challenge.expires() != null)     parts.add("expires=" + quote(challenge.expires()));
        if (challenge.digest() != null)      parts.add("digest=" + quote(challenge.digest()));
        if (challenge.description() != null) parts.add("description=" + quote(challenge.description()));
        if (challenge.opaque() != null)      parts.add("opaque=" + quote(b64Encode(challenge.opaque())));
        return "Payment " + String.join(", ", parts);
    }

    // --- Authorization (Credential) ---

    static Credential parseAuthorization(String header) {
        String schemePart = stripScheme(header, "payment ");
        Map<String, String> params = parseAuthParams(schemePart);

        Map<String, Object> opaque = null;
        String opaqueVal = params.get("opaque");
        if (opaqueVal != null && !opaqueVal.isEmpty()) {
            opaque = b64DecodeToMap(opaqueVal);
        }

        ChallengeEcho echo = new ChallengeEcho(
            params.get("id"),
            params.get("realm"),
            params.get("method"),
            params.get("intent"),
            params.get("request"),
            params.get("expires"),
            params.get("digest"),
            opaque
        );

        String payloadB64 = params.get("payload");
        if (payloadB64 == null) throw new ParseException("Missing payload");
        if (payloadB64.length() > MAX_PAYLOAD_SIZE) throw new ParseException("Payload exceeds maximum size");

        Object payload = b64Decode(payloadB64);
        return new Credential(echo, payload, header);
    }

    static String formatAuthorization(Credential credential) {
        ChallengeEcho echo = credential.challenge();
        List<String> parts = new ArrayList<>();
        parts.add("id=" + quote(echo.id()));
        parts.add("realm=" + quote(echo.realm()));
        parts.add("method=" + quote(echo.method()));
        parts.add("intent=" + quote(echo.intent()));
        parts.add("request=" + quote(echo.request()));
        parts.add("payload=" + quote(b64Encode(credential.payload())));
        if (echo.expires() != null) parts.add("expires=" + quote(echo.expires()));
        if (echo.digest() != null)  parts.add("digest=" + quote(echo.digest()));
        if (echo.opaque() != null)  parts.add("opaque=" + quote(b64Encode(echo.opaque())));
        return "Payment " + String.join(", ", parts);
    }

    // --- Payment-Receipt ---

    static Receipt parsePaymentReceipt(String header) {
        String schemePart = stripScheme(header, "payment-receipt ");
        Map<String, String> params = parseAuthParams(schemePart);

        String status = params.get("status");
        if (status == null) throw new ParseException("Missing status");

        String timestampStr = params.get("timestamp");
        if (timestampStr == null) throw new ParseException("Missing timestamp");
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampStr);
        } catch (DateTimeParseException e) {
            throw new ParseException("Invalid timestamp format: " + timestampStr);
        }

        String reference = params.get("reference");
        if (reference == null) throw new ParseException("Missing reference");

        String method = params.getOrDefault("method", "");

        Object extra = null;
        String extraVal = params.get("extra");
        if (extraVal != null && !extraVal.isEmpty()) {
            extra = b64Decode(extraVal);
        }

        return new Receipt(status, timestamp, reference, method, params.get("external_id"), extra);
    }

    static String formatPaymentReceipt(Receipt receipt) {
        List<String> parts = new ArrayList<>();
        parts.add("status=" + quote(receipt.status()));
        parts.add("timestamp=" + quote(receipt.timestampIso8601()));
        parts.add("reference=" + quote(receipt.reference()));
        if (receipt.method() != null && !receipt.method().isEmpty())
            parts.add("method=" + quote(receipt.method()));
        if (receipt.externalId() != null)
            parts.add("external_id=" + quote(receipt.externalId()));
        if (receipt.extra() != null)
            parts.add("extra=" + quote(b64Encode(receipt.extra())));
        return "Payment-Receipt " + String.join(", ", parts);
    }

    // --- Helpers ---

    private static String stripScheme(String header, String scheme) {
        String lower = header.toLowerCase();
        if (!lower.startsWith(scheme)) return header;
        return header.substring(scheme.length()).stripLeading();
    }
}
