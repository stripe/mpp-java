package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Client-side helper that creates MPP credentials for Stripe payment challenges.
 *
 * <p>Supply a {@code createSpt} function that calls your Stripe SDK to produce a
 * Shared Payment Granted Token given the payment parameters from the challenge.
 *
 * <pre>{@code
 * StripeClientMethod client = new StripeClientMethod(
 *     params -> stripeSDK.createSharedPaymentToken(params),
 *     "card",      // optional payment method type
 *     "order-123"  // optional local external ID; must also be bound in the challenge request
 * );
 *
 * // On a 402 response:
 * Challenge challenge = Challenge.fromWwwAuthenticate(wwwAuthHeader).get(0);
 * Credential credential = client.createCredential(challenge);
 * nextRequest.setHeader("Authorization", credential.toAuthorization());
 * }</pre>
 */
public class StripeClientMethod {

    /**
     * Parameters passed to the {@code createSpt} function.
     */
    public static final class SptParams {
        private final String amount;
        private final String currency;
        private final String networkId;
        private final String paymentMethod;

        public SptParams(String amount, String currency, String networkId, String paymentMethod) {
            this.amount = amount;
            this.currency = currency;
            this.networkId = networkId;
            this.paymentMethod = paymentMethod;
        }

        public String amount() { return amount; }
        public String currency() { return currency; }
        public String networkId() { return networkId; }
        public String paymentMethod() { return paymentMethod; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SptParams)) return false;
            SptParams sptParams = (SptParams) o;
            return Objects.equals(amount, sptParams.amount)
                && Objects.equals(currency, sptParams.currency)
                && Objects.equals(networkId, sptParams.networkId)
                && Objects.equals(paymentMethod, sptParams.paymentMethod);
        }

        @Override
        public int hashCode() {
            return Objects.hash(amount, currency, networkId, paymentMethod);
        }

        @Override
        public String toString() {
            return "SptParams["
                + "amount=" + amount
                + ", currency=" + currency
                + ", networkId=" + networkId
                + ", paymentMethod=" + paymentMethod
                + "]";
        }
    }

    private final Function<SptParams, String> createSpt;
    private final String paymentMethod;
    private final String externalId;

    public StripeClientMethod(Function<SptParams, String> createSpt) {
        this(createSpt, null, null);
    }

    public StripeClientMethod(Function<SptParams, String> createSpt, String paymentMethod, String externalId) {
        this.createSpt     = createSpt;
        this.paymentMethod = paymentMethod;
        this.externalId    = externalId;
    }

    /**
     * Create a credential that satisfies the given challenge.
     */
    @SuppressWarnings("unchecked")
    public Credential createCredential(Challenge challenge) {
        Map<String, Object> request = challenge.request() != null
            ? challenge.request()
            : ChallengeId.b64urlDecodeToMap(challenge.requestB64());

        String amount    = (String) request.get("amount");
        String currency  = (String) request.get("currency");
        String networkId = null;

        Object md = request.get("methodDetails");
        if (md instanceof Map<?, ?>) {
            networkId = (String) ((Map<?, ?>) md).get("networkId");
        }

        String sptId = createSpt.apply(new SptParams(amount, currency, networkId, paymentMethod));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spt", sptId);
        Object requestExternalId = request.get("externalId");
        if (requestExternalId instanceof String) {
            payload.put("externalId", requestExternalId);
        } else if (externalId != null) {
            throw new IllegalArgumentException("externalId must be bound by the challenge request");
        }

        return new Credential(challenge.toEcho(), payload, null);
    }
}
