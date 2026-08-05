package com.stripe.mpp.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class PaymentException extends RuntimeException {
    public static final String BASE_URI = "https://paymentauth.org/problems";

    private final int httpStatus;
    private final String type;
    private final String title;
    private final Map<String, Object> details;

    public PaymentException(String message, int httpStatus, String type, String title) {
        this(message, httpStatus, type, title, null);
    }

    public PaymentException(
        String message,
        int httpStatus,
        String type,
        String title,
        Map<String, Object> details
    ) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
        this.title = title;
        this.details = details == null || details.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public PaymentException(String message) {
        this(message, 402, BASE_URI + "/payment-error", "Payment Error");
    }

    public int getHttpStatus() { return httpStatus; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public Map<String, Object> getDetails() { return details; }

    public Map<String, Object> toProblemDetails() {
        return toProblemDetails(null);
    }

    public Map<String, Object> toProblemDetails(String challengeId) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type);
        problem.put("title", title);
        problem.put("status", httpStatus);
        problem.put("detail", getMessage());
        if (!details.isEmpty()) {
            problem.put("details", details);
        }
        if (challengeId != null) {
            problem.put("challengeId", challengeId);
        }
        return problem;
    }
}
