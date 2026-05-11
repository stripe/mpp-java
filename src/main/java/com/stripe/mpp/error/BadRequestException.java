package com.stripe.mpp.error;

public class BadRequestException extends PaymentException {
    public BadRequestException(String reason) {
        super(reason != null ? "Bad request: " + reason + "." : "Bad request.",
            400, BASE_URI + "/bad-request", "Bad Request");
    }

    public BadRequestException() {
        this(null);
    }
}
