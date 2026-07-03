package com.stripe.mpp.error;

public class PaymentMethodUnsupportedException extends PaymentException {
    public PaymentMethodUnsupportedException(String method) {
        super(method != null ? "Payment method \"" + method + "\" is not supported." : "Payment method is not supported.",
            400, BASE_URI + "/method-unsupported", "Method Unsupported", PAYMENT_REQUIRED_HINT);
    }

    public PaymentMethodUnsupportedException() {
        this(null);
    }
}
