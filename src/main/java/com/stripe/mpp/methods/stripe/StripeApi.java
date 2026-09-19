package com.stripe.mpp.methods.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
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
        private final boolean idempotentReplayed;

        Result(String id, String status) {
            this(id, status, false);
        }

        Result(String id, String status, boolean idempotentReplayed) {
            this.id = id;
            this.status = status;
            this.idempotentReplayed = idempotentReplayed;
        }

        String id() { return id; }
        String status() { return status; }
        boolean idempotentReplayed() { return idempotentReplayed; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Result)) return false;
            Result result = (Result) o;
            return Objects.equals(id, result.id)
                && Objects.equals(status, result.status)
                && idempotentReplayed == result.idempotentReplayed;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, status, idempotentReplayed);
        }

        @Override
        public String toString() {
            return "Result[id=" + id + ", status=" + status
                + ", idempotentReplayed=" + idempotentReplayed + "]";
        }
    }

    Result createAndConfirm(
        String secretKey,
        long amountMinorUnits,
        String currency,
        String spt,
        List<String> paymentMethodTypes,
        Map<String, String> metadata,
        String challengeId
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

            RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(buildIdempotencyKey(challengeId, spt))
                .build();

            PaymentIntent pi = client.paymentIntents().create(builder.build(), options);

            boolean idempotentReplayed = false;
            if (pi.getLastResponse() != null) {
                idempotentReplayed = pi.getLastResponse().headers()
                    .firstValue("Idempotent-Replayed")
                    .map(Boolean::parseBoolean)
                    .orElse(false);
            }

            return new Result(pi.getId(), pi.getStatus(), idempotentReplayed);

        } catch (StripeException e) {
            throw new VerificationFailedException(e.getMessage());
        }
    }

    static String buildIdempotencyKey(String challengeId, String spt) {
        return "mpp_" + challengeId + "_" + spt;
    }
}
