package com.stripe.mpp.methods.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.mpp.error.VerificationFailedException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin wrapper around the Stripe Java SDK, injected into {@link StripeChargeIntent} so
 * tests can stub the API call without real credentials.
 */
class StripeApi {

    static final class Result {
        private final String id;
        private final String status;

        Result(String id, String status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }
        String status() { return status; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Result)) return false;
            Result result = (Result) o;
            return Objects.equals(id, result.id)
                && Objects.equals(status, result.status);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, status);
        }

        @Override
        public String toString() {
            return "Result[id=" + id + ", status=" + status + "]";
        }
    }

    Result createAndConfirm(
        String secretKey,
        long amountMinorUnits,
        String currency,
        String spt,
        List<String> paymentMethodTypes,
        Map<String, String> metadata
    ) {
        try {
            StripeClient client = new StripeClient(secretKey);

            PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amountMinorUnits)
                .setCurrency(currency)
                .setConfirm(true)
                .addAllPaymentMethodType(paymentMethodTypes)
                .putExtraParam("shared_payment_granted_token", spt);

            if (metadata != null && !metadata.isEmpty()) {
                builder.putAllMetadata(metadata);
            }

            PaymentIntent pi = client.paymentIntents().create(builder.build());
            return new Result(pi.getId(), pi.getStatus());

        } catch (StripeException e) {
            throw new VerificationFailedException(e.getMessage());
        }
    }
}
