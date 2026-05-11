package com.stripe.mpp.error;

public class InvalidPayloadException extends PaymentException {
    public InvalidPayloadException(String reason) {
        super(reason != null ? "Credential payload is invalid: " + reason + "." : "Credential payload is invalid.",
            402, BASE_URI + "/invalid-payload", "Invalid Payload");
    }

    public InvalidPayloadException() {
        this(null);
    }
}
