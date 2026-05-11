package com.stripe.mpp.error;

public class PaymentExpiredException extends PaymentException {
    public PaymentExpiredException(String expires) {
        super(expires != null ? "Payment expired at " + expires + "." : "Payment has expired.",
            402, BASE_URI + "/payment-expired", "Payment Expired");
    }

    public PaymentExpiredException() {
        this(null);
    }
}
