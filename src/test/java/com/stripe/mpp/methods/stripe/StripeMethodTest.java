package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeMethodTest {

    @Test
    void transformRequestUsesPaymentMethodTypesAndExternalId() {
        StripeMethod method = StripeMethod.of("sk_test_xxx", "net_xxx")
            .paymentMethods(List.of("card", "link"))
            .metadata(Map.of("orderId", "order-42"))
            .externalId("server-order-123")
            .build();

        Map<String, Object> transformed = method.transformRequest(Map.of("amount", "10.00", "currency", "usd"));
        Map<?, ?> methodDetails = (Map<?, ?>) transformed.get("methodDetails");

        assertThat(transformed).containsEntry("externalId", "server-order-123");
        assertThat(methodDetails.get("networkId")).isEqualTo("net_xxx");
        assertThat(methodDetails.get("paymentMethodTypes")).isEqualTo(List.of("card", "link"));
        assertThat(methodDetails.get("metadata")).isEqualTo(Map.of("orderId", "order-42"));
    }

    @Test
    void defaultMethodUsesCardPaymentMethodType() {
        StripeMethod method = Stripe.method("sk_test_xxx", "net_xxx");

        Map<String, Object> transformed = method.transformRequest(Map.of("amount", "10.00", "currency", "usd"));

        Map<?, ?> methodDetails = (Map<?, ?>) transformed.get("methodDetails");
        assertThat(methodDetails.get("paymentMethodTypes")).isEqualTo(List.of("card"));
    }

    @Test
    void nullPaymentMethodsDefaultToCard() {
        StripeMethod method = Stripe.method("sk_test_xxx", "net_xxx", null, null);

        Map<String, Object> transformed = method.transformRequest(Map.of("amount", "10.00", "currency", "usd"));

        Map<?, ?> methodDetails = (Map<?, ?>) transformed.get("methodDetails");
        assertThat(methodDetails.get("paymentMethodTypes")).isEqualTo(List.of("card"));
    }

    @Test
    void rejectsEmptyPaymentMethodTypes() {
        assertThatThrownBy(() -> StripeMethod.of("sk_test_xxx", "net_xxx")
                .paymentMethods(List.of())
                .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("paymentMethods");
    }

    @Test
    void clientEchoesRequestBoundExternalId() {
        Map<String, Object> request = Map.of(
            "amount", "10.00",
            "currency", "usd",
            "externalId", "server-order-123",
            "methodDetails", Map.of("networkId", "net_xxx")
        );
        Challenge challenge = challenge(request);
        StripeClientMethod client = new StripeClientMethod(params -> "spt_xxx", "card", "local-order-999");

        Credential credential = client.createCredential(challenge);
        Map<?, ?> payload = (Map<?, ?>) credential.payload();

        assertThat(payload.get("spt")).isEqualTo("spt_xxx");
        assertThat(payload.get("externalId")).isEqualTo("server-order-123");
    }

    @Test
    void clientRejectsLocalExternalIdWithoutRequestBinding() {
        Challenge challenge = challenge(Map.of("amount", "10.00", "currency", "usd"));
        StripeClientMethod client = new StripeClientMethod(params -> "spt_xxx", "card", "local-order-999");

        assertThatThrownBy(() -> client.createCredential(challenge))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("externalId");
    }

    private static Challenge challenge(Map<String, Object> request) {
        String requestB64 = ChallengeId.b64urlEncode(Json.compact(request));
        return new Challenge(
            "ch_123",
            "stripe",
            "charge",
            request,
            "api.example.com",
            requestB64,
            null,
            "2099-01-01T00:00:00Z",
            null,
            null
        );
    }
}
