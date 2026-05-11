package com.stripe.mpp;

/**
 * An MPP payment credential sent by the client in the Authorization header.
 */
public record Credential(
    ChallengeEcho challenge,
    Object payload,
    String source
) {
    /**
     * Parse a Credential from an Authorization header value.
     */
    public static Credential fromAuthorization(String header) {
        return Parsing.parseAuthorization(header);
    }

    /**
     * Serialize to an Authorization header value.
     */
    public String toAuthorization() {
        return Parsing.formatAuthorization(this);
    }
}
