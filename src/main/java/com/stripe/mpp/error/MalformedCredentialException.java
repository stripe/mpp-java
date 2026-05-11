package com.stripe.mpp.error;

public class MalformedCredentialException extends PaymentException {
    public MalformedCredentialException(String reason) {
        super(reason != null ? "Credential is malformed: " + reason + "." : "Credential is malformed.",
            402, BASE_URI + "/malformed-credential", "Malformed Credential");
    }

    public MalformedCredentialException() {
        this(null);
    }
}
