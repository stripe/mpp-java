package com.stripe.mpp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An MPP payment challenge sent to the client in the WWW-Authenticate header.
 */
public final class Challenge {
    private final String id;
    private final String method;
    private final String intent;
    private final Map<String, Object> request;
    private final String realm;
    private final String requestB64;
    private final String digest;
    private final String expires;
    private final String description;
    private final Map<String, Object> opaque;

    public Challenge(
        String id,
        String method,
        String intent,
        Map<String, Object> request,
        String realm,
        String requestB64,
        String digest,
        String expires,
        String description,
        Map<String, Object> opaque
    ) {
        this.id = id;
        this.method = method;
        this.intent = intent;
        this.request = request;
        this.realm = realm;
        this.requestB64 = requestB64;
        this.digest = digest;
        this.expires = expires;
        this.description = description;
        this.opaque = opaque;
    }

    public String id() { return id; }
    public String method() { return method; }
    public String intent() { return intent; }
    public Map<String, Object> request() { return request; }
    public String realm() { return realm; }
    public String requestB64() { return requestB64; }
    public String digest() { return digest; }
    public String expires() { return expires; }
    public String description() { return description; }
    public Map<String, Object> opaque() { return opaque; }

    /**
     * Create a new challenge with a cryptographically bound ID.
     */
    public static Challenge create(
        String secretKey,
        String realm,
        String method,
        String intent,
        Map<String, Object> request,
        String expires,
        String description,
        Map<String, Object> meta
    ) {
        String requestB64 = ChallengeId.b64urlEncode(Json.compact(request));
        String id = ChallengeId.generate(secretKey, realm, method, intent, request, expires, null, meta);
        return new Challenge(id, method, intent, request, realm, requestB64, null, expires, description, meta);
    }

    public static Challenge create(
        String secretKey, String realm, String method, String intent, Map<String, Object> request
    ) {
        return create(secretKey, realm, method, intent, request, null, null, null);
    }

    /**
     * Parse challenges from one or more WWW-Authenticate header values.
     */
    public static List<Challenge> fromWwwAuthenticate(List<String> wwwAuthenticateHeaders) {
        List<Challenge> challenges = new ArrayList<>();
        for (String header : wwwAuthenticateHeaders) {
            for (String authParams : extractPaymentAuthParamChunks(header)) {
                Map<String, String> params = Parsing.parseAuthParams(authParams);
                String id = Parsing.requireString(params, "id");
                String realm = Parsing.requireString(params, "realm");
                String method = Parsing.requireString(params, "method");
                Parsing.validatePaymentMethodId(method);
                String intent = Parsing.requireString(params, "intent");
                String requestB64 = Parsing.requireString(params, "request");
                Map<String, Object> request = ChallengeId.b64urlDecodeToMap(requestB64);
                Map<String, Object> opaque = null;
                String opaqueVal = params.get("opaque");
                if (opaqueVal != null && !opaqueVal.isEmpty()) {
                    opaque = ChallengeId.b64urlDecodeToMap(opaqueVal);
                }
                challenges.add(new Challenge(
                    id,
                    method,
                    intent,
                    request,
                    realm,
                    requestB64,
                    params.get("digest"),
                    params.get("expires"),
                    params.get("description"),
                    opaque
                ));
            }
        }
        return challenges;
    }

    /**
     * Locate the auth-params portion that follows "Payment " in the header.
     * Handles multi-scheme headers like "Bearer token, Payment id=...".
     */
    static String extractPaymentAuthParams(String header) {
        List<String> chunks = extractPaymentAuthParamChunks(header);
        return chunks.isEmpty() ? null : chunks.get(0);
    }

    private static List<String> extractPaymentAuthParamChunks(String header) {
        List<AuthScheme> schemes = authSchemes(header, 0);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < schemes.size(); i++) {
            AuthScheme scheme = schemes.get(i);
            if (!"Payment".equalsIgnoreCase(scheme.name)) continue;

            int end = i + 1 < schemes.size() ? schemes.get(i + 1).index : header.length();
            String chunk = header.substring(scheme.paramsStart, end).replaceAll(",\\s*$", "").trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
        }
        return chunks;
    }

    private static List<AuthScheme> authSchemes(String header, int offset) {
        List<AuthScheme> schemes = new ArrayList<>();
        boolean inQuote = false;
        boolean escaped = false;
        int i = offset;

        while (i < header.length()) {
            char c = header.charAt(i);
            if (inQuote) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                i++;
                continue;
            }

            if (c == '"') {
                inQuote = true;
                i++;
                continue;
            }

            if (schemeBoundary(header, i) && isSchemeStart(c)) {
                int tokenEnd = i + 1;
                while (tokenEnd < header.length() && isSchemeChar(header.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                int paramsStart = tokenEnd;
                if (paramsStart < header.length() && Character.isWhitespace(header.charAt(paramsStart))) {
                    while (paramsStart < header.length() && Character.isWhitespace(header.charAt(paramsStart))) {
                        paramsStart++;
                    }
                    if (paramsStart >= header.length() || header.charAt(paramsStart) != '=') {
                        schemes.add(new AuthScheme(i, paramsStart, header.substring(i, tokenEnd)));
                        i = paramsStart;
                        continue;
                    }
                }
            }

            i++;
        }
        return schemes;
    }

    private static boolean schemeBoundary(String header, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(header.charAt(i))) i--;
        return i < 0 || header.charAt(i) == ',';
    }

    private static boolean isSchemeStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isSchemeChar(char c) {
        return isSchemeStart(c)
            || (c >= '0' && c <= '9')
            || c == '.' || c == '_' || c == '~' || c == '+' || c == '/' || c == '-';
    }

    private static final class AuthScheme {
        private final int index;
        private final int paramsStart;
        private final String name;

        private AuthScheme(int index, int paramsStart, String name) {
            this.index = index;
            this.paramsStart = paramsStart;
            this.name = name;
        }
    }

    public static List<Challenge> fromWwwAuthenticate(String wwwAuthenticate) {
        return fromWwwAuthenticate(List.of(wwwAuthenticate));
    }

    /**
     * Serialize to the WWW-Authenticate header value.
     */
    public String toWwwAuthenticate() {
        return Parsing.formatWwwAuthenticate(this);
    }

    /**
     * Serialize multiple challenges to a list of WWW-Authenticate header values — one per challenge.
     * Use {@code response.addHeader("WWW-Authenticate", h)} for each entry.
     */
    public static List<String> toWwwAuthenticate(List<Challenge> challenges) {
        return challenges.stream()
            .map(Challenge::toWwwAuthenticate)
            .collect(Collectors.toList());
    }

    /**
     * Convert to the echo form included inside a Credential.
     */
    public ChallengeEcho toEcho() {
        return new ChallengeEcho(id, realm, method, intent, requestB64, expires, digest, opaque);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Challenge)) return false;
        Challenge challenge = (Challenge) o;
        return Objects.equals(id, challenge.id)
            && Objects.equals(method, challenge.method)
            && Objects.equals(intent, challenge.intent)
            && Objects.equals(request, challenge.request)
            && Objects.equals(realm, challenge.realm)
            && Objects.equals(requestB64, challenge.requestB64)
            && Objects.equals(digest, challenge.digest)
            && Objects.equals(expires, challenge.expires)
            && Objects.equals(description, challenge.description)
            && Objects.equals(opaque, challenge.opaque);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, method, intent, request, realm, requestB64, digest, expires, description, opaque);
    }

    @Override
    public String toString() {
        return "Challenge["
            + "id=" + id
            + ", method=" + method
            + ", intent=" + intent
            + ", request=" + request
            + ", realm=" + realm
            + ", requestB64=" + requestB64
            + ", digest=" + digest
            + ", expires=" + expires
            + ", description=" + description
            + ", opaque=" + opaque
            + "]";
    }
}
