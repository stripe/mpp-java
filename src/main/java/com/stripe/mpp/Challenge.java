package com.stripe.mpp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An MPP payment challenge sent to the client in the WWW-Authenticate header.
 */
public record Challenge(
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
                params.get("request"),
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
     * Convert to the echo form included inside a Credential.
     */
    public ChallengeEcho toEcho() {
        return new ChallengeEcho(id, realm, method, intent, requestB64, expires, digest, opaque);
    }
}
