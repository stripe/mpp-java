package com.stripe.mpp.error;

public class PaymentActionRequiredException extends PaymentException {
    public PaymentActionRequiredException(String reason) {
        super(reason != null ? "Payment requires action: " + reason + "." : "Payment requires action.",
            402, BASE_URI + "/payment-action-required", "Payment Action Required");
    }

    public PaymentActionRequiredException() {
        this(null);
    }
}
