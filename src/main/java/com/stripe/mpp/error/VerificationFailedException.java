package com.stripe.mpp.error;

public class VerificationFailedException extends PaymentException {
    public VerificationFailedException(String reason) {
        super(reason != null ? "Payment verification failed: " + reason + "." : "Payment verification failed.",
            402, BASE_URI + "/verification-failed", "Verification Failed");
    }

    public VerificationFailedException() {
        this(null);
    }
}
