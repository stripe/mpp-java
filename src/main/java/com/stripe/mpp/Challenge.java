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
            // Find each "Payment " scheme in the header. Auth-params are comma-separated, so
            // we cannot simply split by comma; instead we locate the scheme token boundary.
            String authParams = extractPaymentAuthParams(header);
            if (authParams == null) continue;

            Map<String, String> params = Parsing.parseAuthParams(authParams);
            String request = params.get("request");
            if (request != null && request.length() > Parsing.MAX_PAYLOAD_SIZE) {
                throw new com.stripe.mpp.error.ParseException(
                    "Request parameter exceeds maximum length of " + Parsing.MAX_PAYLOAD_SIZE + " bytes"
                );
            }
            Map<String, Object> opaque = null;
            String opaqueVal = params.get("opaque");
            if (opaqueVal != null && !opaqueVal.isEmpty()) {
                opaque = ChallengeId.b64urlDecodeToMap(opaqueVal);
            }
            challenges.add(new Challenge(
                params.get("id"),
                params.get("method"),
                params.get("intent"),
                null,
                params.get("realm"),
                request,
                params.get("digest"),
                params.get("expires"),
                params.get("description"),
                opaque
            ));
        }
        return challenges;
    }

    /**
     * Locate the auth-params portion that follows "Payment " in the header.
     * Handles multi-scheme headers like "Bearer token, Payment id=...".
     */
    static String extractPaymentAuthParams(String header) {
        String lower = header.toLowerCase();
        // Match at start or after a scheme boundary (", " before the scheme token)
        if (lower.startsWith("payment ")) return header.substring("payment ".length());
        int idx = lower.indexOf(", payment ");
        if (idx >= 0) return header.substring(idx + ", payment ".length());
        return null;
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
