package com.stripe.mpp;

import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Build the credential's wire envelope ({@code challenge}, {@code payload}, {@code source?})
     * using the given representation of the echoed request — the base64url string for the
     * Authorization header, or the decoded map for the relay API. This is the single canonical
     * serialization of the echoed challenge fields.
     */
    public Map<String, Object> toEnvelope(Object request) {
        Map<String, Object> challengeMap = new LinkedHashMap<>();
        challengeMap.put("id", challenge.id());
        challengeMap.put("realm", challenge.realm());
        challengeMap.put("method", challenge.method());
        challengeMap.put("intent", challenge.intent());
        challengeMap.put("request", request);
        if (challenge.expires() != null) challengeMap.put("expires", challenge.expires());
        if (challenge.digest() != null)  challengeMap.put("digest", challenge.digest());
        if (challenge.opaqueRaw() != null) challengeMap.put("opaque", challenge.opaqueRaw());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("challenge", challengeMap);
        envelope.put("payload", payload);
        if (source != null) envelope.put("source", source);
        return envelope;
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
