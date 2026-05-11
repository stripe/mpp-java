package com.stripe.mpp;

import java.util.Map;

/**
 * The challenge fields echoed back inside a Credential's Authorization header.
 */
public record ChallengeEcho(
    String id,
    String realm,
    String method,
    String intent,
    String request,
    String expires,
    String digest,
    Map<String, Object> opaque
) {
    public ChallengeEcho {
        // request is stored as the raw base64url string from the wire
    }
}
