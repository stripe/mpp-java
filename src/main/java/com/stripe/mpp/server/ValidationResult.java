package com.stripe.mpp.server;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;

import java.util.Map;
import java.util.Objects;

/** The non-mutating result of validating a payment credential. */
public final class ValidationResult {
    private final Credential credential;
    private final Map<String, Object> request;
    private final Map<String, Object> details;

    public ValidationResult(
        Credential credential,
        Map<String, Object> request,
        Map<String, Object> details
    ) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.request = Map.copyOf(request);
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Credential credential() { return credential; }
    public ChallengeEcho challenge() { return credential.challenge(); }
    public Map<String, Object> request() { return request; }
    public Map<String, Object> details() { return details; }
    public String method() { return credential.challenge().method(); }
    public String intent() { return credential.challenge().intent(); }
    public String source() { return credential.source(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValidationResult)) return false;
        ValidationResult that = (ValidationResult) o;
        return Objects.equals(credential, that.credential)
            && Objects.equals(request, that.request)
            && Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(credential, request, details);
    }

    @Override
    public String toString() {
        return "ValidationResult[credential=" + credential
            + ", request=" + request
            + ", details=" + details + "]";
    }
}
