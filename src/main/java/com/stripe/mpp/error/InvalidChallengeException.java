package com.stripe.mpp.error;

public class InvalidChallengeException extends PaymentException {
    public InvalidChallengeException(String challengeId, String reason) {
        super(buildMessage(challengeId, reason), 402,
            BASE_URI + "/invalid-challenge", "Invalid Challenge");
    }

    public InvalidChallengeException() {
        this(null, null);
    }

    private static String buildMessage(String challengeId, String reason) {
        String idPart = challengeId != null ? " \"" + challengeId + "\"" : "";
        String reasonPart = reason != null ? ": " + reason : "";
        return "Challenge" + idPart + " is invalid" + reasonPart + ".";
    }
}
