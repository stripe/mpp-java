package com.stripe.mpp.error;

import java.util.HashMap;
import java.util.Map;

public class PaymentException extends RuntimeException {
    public static final String BASE_URI = "https://paymentauth.org/problems";

    private final int httpStatus;
    private final String type;
    private final String title;

    public PaymentException(String message, int httpStatus, String type, String title) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
        this.title = title;
    }

    public PaymentException(String message) {
        this(message, 402, BASE_URI + "/payment-error", "Payment Error");
    }

    public int getHttpStatus() { return httpStatus; }
    public String getType() { return type; }
    public String getTitle() { return title; }

    public Map<String, Object> toProblemDetails() {
        return toProblemDetails(null);
    }

    public Map<String, Object> toProblemDetails(String challengeId) {
        Map<String, Object> details = new HashMap<>();
        details.put("type", type);
        details.put("title", title);
        details.put("status", httpStatus);
        details.put("detail", getMessage());
        if (challengeId != null) {
            details.put("challengeId", challengeId);
        }
        return details;
    }
}
