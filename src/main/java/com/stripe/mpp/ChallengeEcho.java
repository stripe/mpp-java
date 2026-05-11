package com.stripe.mpp;

import java.util.Map;
import java.util.Objects;

/**
 * The challenge fields echoed back inside a Credential's Authorization header.
 */
public final class ChallengeEcho {
    private final String id;
    private final String realm;
    private final String method;
    private final String intent;
    private final String request;
    private final String expires;
    private final String digest;
    private final Map<String, Object> opaque;

    public ChallengeEcho(
        String id,
        String realm,
        String method,
        String intent,
        String request,
        String expires,
        String digest,
        Map<String, Object> opaque
    ) {
        this.id = id;
        this.realm = realm;
        this.method = method;
        this.intent = intent;
        this.request = request;
        this.expires = expires;
        this.digest = digest;
        this.opaque = opaque;
    }

    public String id() { return id; }
    public String realm() { return realm; }
    public String method() { return method; }
    public String intent() { return intent; }
    public String request() { return request; }
    public String expires() { return expires; }
    public String digest() { return digest; }
    public Map<String, Object> opaque() { return opaque; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChallengeEcho)) return false;
        ChallengeEcho that = (ChallengeEcho) o;
        return Objects.equals(id, that.id)
            && Objects.equals(realm, that.realm)
            && Objects.equals(method, that.method)
            && Objects.equals(intent, that.intent)
            && Objects.equals(request, that.request)
            && Objects.equals(expires, that.expires)
            && Objects.equals(digest, that.digest)
            && Objects.equals(opaque, that.opaque);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, realm, method, intent, request, expires, digest, opaque);
    }

    @Override
    public String toString() {
        return "ChallengeEcho["
            + "id=" + id
            + ", realm=" + realm
            + ", method=" + method
            + ", intent=" + intent
            + ", request=" + request
            + ", expires=" + expires
            + ", digest=" + digest
            + ", opaque=" + opaque
            + "]";
    }
}
