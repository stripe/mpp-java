package com.stripe.mpp.error;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentExceptionTest {
    @Test
    void paymentRequiredProblemDetailsIncludeHint() {
        PaymentException error = new PaymentRequiredException("api.example.com", "Paid endpoint");

        Map<String, Object> details = error.toProblemDetails("ch_123");

        assertThat(error.getHint()).isEqualTo(PaymentException.PAYMENT_REQUIRED_HINT);
        assertThat(details).containsEntry("hint", PaymentException.PAYMENT_REQUIRED_HINT);
        assertThat(details).containsEntry("challengeId", "ch_123");
    }

    @Test
    void malformedCredentialProblemDetailsIncludeHint() {
        PaymentException error = new MalformedCredentialException("bad base64");

        Map<String, Object> details = error.toProblemDetails();

        assertThat(error.getHint()).isEqualTo(PaymentException.MALFORMED_CREDENTIAL_HINT);
        assertThat(details).containsEntry("hint", PaymentException.MALFORMED_CREDENTIAL_HINT);
    }

    @Test
    void methodUnsupportedProblemDetailsIncludePaymentRequiredHint() {
        PaymentException error = new PaymentMethodUnsupportedException("unknown");

        Map<String, Object> details = error.toProblemDetails();

        assertThat(error.getHint()).isEqualTo(PaymentException.PAYMENT_REQUIRED_HINT);
        assertThat(details).containsEntry("hint", PaymentException.PAYMENT_REQUIRED_HINT);
    }

    @Test
    void problemDetailsOmitHintWhenUnset() {
        PaymentException error = new InvalidChallengeException("ch_123", "expired");

        Map<String, Object> details = error.toProblemDetails();

        assertThat(error.getHint()).isNull();
        assertThat(details).doesNotContainKey("hint");
    }
}
