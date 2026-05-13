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

    @SuppressWarnings("unchecked")
    static Credential parseAuthorization(String header) {
        String token = stripScheme(header, "payment ");

        Map<String, Object> envelope = b64DecodeToMap(token);

        Object challengeObj = envelope.get("challenge");
        if (!(challengeObj instanceof Map)) throw new ParseException("Credential missing required field: challenge");
        Map<String, Object> challengeMap = (Map<String, Object>) challengeObj;

        if (!challengeMap.containsKey("id")) throw new ParseException("Credential challenge missing required field: id");

        Map<String, Object> opaque = null;
        if (challengeMap.get("opaque") instanceof Map) {
            opaque = (Map<String, Object>) challengeMap.get("opaque");
        }

        ChallengeEcho echo = new ChallengeEcho(
            str(challengeMap, "id"),
            str(challengeMap, "realm"),
            str(challengeMap, "method"),
            str(challengeMap, "intent"),
            str(challengeMap, "request"),
            str(challengeMap, "expires"),
            str(challengeMap, "digest"),
            opaque
        );

        Object payload = envelope.get("payload");
        if (payload == null) throw new ParseException("Credential missing required field: payload");

        String source = envelope.get("source") instanceof String ? (String) envelope.get("source") : header;
        return new Credential(echo, payload, source);
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    static String formatAuthorization(Credential credential) {
        ChallengeEcho echo = credential.challenge();

        Map<String, Object> challengeMap = new java.util.LinkedHashMap<>();
        challengeMap.put("id", echo.id());
        challengeMap.put("realm", echo.realm());
        challengeMap.put("method", echo.method());
        challengeMap.put("intent", echo.intent());
        challengeMap.put("request", echo.request());
        if (echo.expires() != null) challengeMap.put("expires", echo.expires());
        if (echo.digest() != null)  challengeMap.put("digest", echo.digest());
        if (echo.opaque() != null)  challengeMap.put("opaque", echo.opaque());

        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("challenge", challengeMap);
        envelope.put("payload", credential.payload());
        if (credential.source() != null) envelope.put("source", credential.source());

        return "Payment " + b64Encode(envelope);
    }

    // --- Payment-Receipt ---

    @SuppressWarnings("unchecked")
    static Receipt parsePaymentReceipt(String header) {
        String token = stripScheme(header, "payment-receipt ");
        Map<String, Object> map = b64DecodeToMap(token);

        String status = str(map, "status");
        if (status == null) throw new ParseException("Missing status");

        String timestampStr = str(map, "timestamp");
        if (timestampStr == null) throw new ParseException("Missing timestamp");
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampStr);
        } catch (DateTimeParseException e) {
            throw new ParseException("Invalid timestamp format: " + timestampStr);
        }

        String reference = str(map, "reference");
        if (reference == null) throw new ParseException("Missing reference");

        String method = str(map, "method");
        if (method == null) method = "";

        Object extra = map.get("extra");

        return new Receipt(status, timestamp, reference, method, str(map, "external_id"), extra);
    }

    static String formatPaymentReceipt(Receipt receipt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", receipt.status());
        map.put("timestamp", receipt.timestampIso8601());
        map.put("reference", receipt.reference());
        if (receipt.method() != null && !receipt.method().isEmpty())
            map.put("method", receipt.method());
        if (receipt.externalId() != null)
            map.put("external_id", receipt.externalId());
        if (receipt.extra() != null)
            map.put("extra", receipt.extra());
        return b64Encode(map);
    }

    // --- Helpers ---

    private static String stripScheme(String header, String scheme) {
        String lower = header.toLowerCase();
        if (!lower.startsWith(scheme)) return header;
        return header.substring(scheme.length()).stripLeading();
    }
}
