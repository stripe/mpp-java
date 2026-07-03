package com.stripe.mpp.error;

import java.util.HashMap;
import java.util.Map;

public class PaymentException extends RuntimeException {
    public static final String BASE_URI = "https://paymentauth.org/problems";
    public static final String PAYMENT_REQUIRED_HINT =
        "Use a supported wallet to pay for this resource using one of the supported "
            + "payment methods returned in the WWW-Authenticate header. See https://mpp.dev/tools/wallet.md";
    public static final String MALFORMED_CREDENTIAL_HINT =
        "Use a supported wallet to construct valid credentials for one of the supported "
            + "payment methods returned in the WWW-Authenticate header. See https://mpp.dev/tools/wallet.md";

    private final int httpStatus;
    private final String type;
    private final String title;
    private final String hint;

    public PaymentException(String message, int httpStatus, String type, String title) {
        this(message, httpStatus, type, title, null);
    }

    public PaymentException(String message, int httpStatus, String type, String title, String hint) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
        this.title = title;
        this.hint = hint;
    }

    public PaymentException(String message) {
        this(message, 402, BASE_URI + "/payment-error", "Payment Error");
    }

    public int getHttpStatus() { return httpStatus; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getHint() { return hint; }

    public Map<String, Object> toProblemDetails() {
        return toProblemDetails(null);
    }

    public Map<String, Object> toProblemDetails(String challengeId) {
        Map<String, Object> details = new HashMap<>();
        details.put("type", type);
        details.put("title", title);
        details.put("status", httpStatus);
        details.put("detail", getMessage());
        if (hint != null) {
            details.put("hint", hint);
        }
        if (challengeId != null) {
            details.put("challengeId", challengeId);
        }
        return details;
    }
}
