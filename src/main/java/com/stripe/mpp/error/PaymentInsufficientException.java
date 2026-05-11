package com.stripe.mpp.error;

public class PaymentInsufficientException extends PaymentException {
    public PaymentInsufficientException(String reason) {
        super(reason != null ? "Payment insufficient: " + reason + "." : "Payment amount is insufficient.",
            402, BASE_URI + "/payment-insufficient", "Payment Insufficient");
    }

    public PaymentInsufficientException() {
        this(null);
    }
}
