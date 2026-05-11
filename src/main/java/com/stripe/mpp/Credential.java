package com.stripe.mpp;

import java.util.Objects;

/**
 * An MPP payment credential sent by the client in the Authorization header.
 */
public final class Credential {
    private final ChallengeEcho challenge;
    private final Object payload;
    private final String source;

    public Credential(ChallengeEcho challenge, Object payload, String source) {
        this.challenge = challenge;
        this.payload = payload;
        this.source = source;
    }

    public ChallengeEcho challenge() { return challenge; }
    public Object payload() { return payload; }
    public String source() { return source; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Credential)) return false;
        Credential that = (Credential) o;
        return Objects.equals(challenge, that.challenge)
            && Objects.equals(payload, that.payload)
            && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(challenge, payload, source);
    }

    @Override
    public String toString() {
        return "Credential[challenge=" + challenge + ", payload=" + payload + ", source=" + source + "]";
    }
}
