package com.stripe.mpp.error;

import java.util.Map;

public class VerificationFailedException extends PaymentException {
    public VerificationFailedException(String reason) {
        this(reason, null);
    }

    public VerificationFailedException(String reason, Map<String, Object> details) {
        super(reason != null ? "Payment verification failed: " + reason + "." : "Payment verification failed.",
            402, BASE_URI + "/verification-failed", "Verification Failed", details);
    }

    public VerificationFailedException() {
        this(null);
    }
}
