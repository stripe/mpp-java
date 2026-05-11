package com.stripe.mpp.error;

public class PaymentRequiredException extends PaymentException {
    public PaymentRequiredException(String realm, String description) {
        super(buildMessage(realm, description), 402,
            BASE_URI + "/payment-required", "Payment Required");
    }

    public PaymentRequiredException() {
        this(null, null);
    }

    private static String buildMessage(String realm, String description) {
        StringBuilder sb = new StringBuilder("Payment is required");
        if (realm != null) sb.append(" for \"").append(realm).append("\"");
        if (description != null) sb.append(" (").append(description).append(")");
        return sb.append(".").toString();
    }
}
