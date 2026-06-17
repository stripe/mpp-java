package com.stripe.mpp.methods.stripe;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.InvalidChallengeException;
import com.stripe.mpp.error.PaymentActionRequiredException;
import com.stripe.mpp.error.PaymentExpiredException;
import com.stripe.mpp.error.VerificationFailedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeChargeIntentTest {

    static final Map<String, Object> REQUEST = Map.of(
        "amount", "10.00", "currency", "usd", "recipient", "net_xxx",
        "methodDetails", Map.of("paymentMethodTypes", List.of("card"))
    );
    static final Map<String, Object> REQUEST_WITH_METADATA = Map.of(
        "amount", "10.00", "currency", "usd", "recipient", "net_xxx",
        "methodDetails", Map.of(
            "networkId", "net_xxx",
            "paymentMethodTypes", List.of("card", "link"),
            "metadata", Map.of("orderId", "order-42")
        )
    );
    static final Map<String, Object> REQUEST_WITH_EXTERNAL_ID = Map.of(
        "amount", "10.00", "currency", "usd", "recipient", "net_xxx",
        "externalId", "server-order-123",
        "methodDetails", Map.of("paymentMethodTypes", List.of("card"))
    );
    static final ChallengeEcho ECHO = new ChallengeEcho(
        "chal-id", "api.example.com", "stripe", "charge", "e30", "2099-01-01T00:00:00Z", null, null
    );
    static final ChallengeEcho EXPIRED_ECHO = new ChallengeEcho(
        "chal-id", "api.example.com", "stripe", "charge", "e30", "2000-01-01T00:00:00Z", null, null
    );

    static Credential credential(String spt) {
        return new Credential(ECHO, Map.of("spt", spt), null);
    }

    static Credential credentialWithExternalId(String spt, String externalId) {
        return new Credential(ECHO, Map.of("spt", spt, "externalId", externalId), null);
    }

    // --- Stub Stripe API ---

    static class StubStripeApi extends StripeApi {
        private final StripeApi.Result result;
        String lastSpt;
        long lastAmount;
        String lastCurrency;
        List<String> lastPaymentMethodTypes;
        Map<String, String> lastMetadata;

        StubStripeApi(StripeApi.Result result) {
            this.result = result;
        }

        @Override
        StripeApi.Result createAndConfirm(
            String secretKey, long amountMinorUnits, String currency,
            String spt, List<String> paymentMethodTypes, Map<String, String> metadata
        ) {
            this.lastSpt      = spt;
            this.lastAmount   = amountMinorUnits;
            this.lastCurrency = currency;
            this.lastPaymentMethodTypes = paymentMethodTypes;
            this.lastMetadata = metadata;
            return result;
        }
    }

    static StripeChargeIntent intent(StripeApi api) {
        return new StripeChargeIntent("sk_test_xxx", StripeDefaults.DEFAULT_DECIMALS, api);
    }

    // --- Tests ---

    @Test
    void successfulChargeReturnsReceipt() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Receipt receipt = intent(api).verify(credential("spt_xxx"), REQUEST);

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.reference()).isEqualTo("pi_abc123");
        assertThat(receipt.method()).isEqualTo("stripe");
        assertThat(receipt.externalId()).isNull();
    }

    @Test
    void externalIdIncludedInReceipt() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Receipt receipt = intent(api).verify(
            credentialWithExternalId("spt_xxx", "server-order-123"),
            REQUEST_WITH_EXTERNAL_ID);

        assertThat(receipt.externalId()).isEqualTo("server-order-123");
    }

    @Test
    void forgedPayloadExternalIdThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));

        assertThatThrownBy(() -> intent(api).verify(
                credentialWithExternalId("spt_xxx", "attacker-order-999"),
                REQUEST_WITH_EXTERNAL_ID))
            .isInstanceOf(InvalidChallengeException.class)
            .hasMessageContaining("externalId");
    }

    @Test
    void payloadOnlyExternalIdIsNotAttributed() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));

        Receipt receipt = intent(api).verify(credentialWithExternalId("spt_xxx", "attacker-order-999"), REQUEST);

        assertThat(receipt.externalId()).isNull();
    }

    @Test
    void amountConvertedToMinorUnits() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        intent(api).verify(credential("spt_xxx"), REQUEST);

        assertThat(api.lastAmount).isEqualTo(1000L); // "10.00" * 100 = 1000 cents
        assertThat(api.lastCurrency).isEqualTo("usd");
        assertThat(api.lastSpt).isEqualTo("spt_xxx");
        assertThat(api.lastPaymentMethodTypes).containsExactly("card");
    }

    @Test
    void requiresActionThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "requires_action"));

        assertThatThrownBy(() -> intent(api).verify(credential("spt_xxx"), REQUEST))
            .isInstanceOf(PaymentActionRequiredException.class)
            .hasMessageContaining("requires action");
    }

    @Test
    void nonSucceededStatusThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "requires_payment_method"));

        assertThatThrownBy(() -> intent(api).verify(credential("spt_xxx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("requires_payment_method");
    }

    @Test
    void missingPayloadThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Credential bad = new Credential(ECHO, "not-a-map", null);

        assertThatThrownBy(() -> intent(api).verify(bad, REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("missing or invalid payload");
    }

    @Test
    void missingSptThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Credential bad = new Credential(ECHO, Map.of("externalId", "order-1"), null);

        assertThatThrownBy(() -> intent(api).verify(bad, REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("missing spt");
    }

    @Test
    void expiredChallengeThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Credential expired = new Credential(EXPIRED_ECHO, Map.of("spt", "spt_xxx"), null);

        assertThatThrownBy(() -> intent(api).verify(expired, REQUEST))
            .isInstanceOf(PaymentExpiredException.class);
    }

    @Test
    void stripeApiExceptionBecomesVerificationFailed() {
        StripeApi throwingApi = new StripeApi() {
            @Override
            StripeApi.Result createAndConfirm(
                String secretKey, long amountMinorUnits, String currency,
                String spt, List<String> paymentMethodTypes, Map<String, String> metadata
            ) {
                throw new VerificationFailedException("card_declined");
            }
        };

        assertThatThrownBy(() -> intent(throwingApi).verify(credential("spt_xxx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("card_declined");
    }

    @Test
    void metadataPassedToStripeApi() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Receipt receipt = intent(api).verify(credential("spt_xxx"), REQUEST_WITH_METADATA);
        assertThat(receipt.status()).isEqualTo("success");
        assertThat(api.lastPaymentMethodTypes).containsExactly("card", "link");
        assertThat(api.lastMetadata).containsEntry("orderId", "order-42");
    }

    @Test
    void missingPaymentMethodTypesThrows() {
        StubStripeApi api = new StubStripeApi(new StripeApi.Result("pi_abc123", "succeeded"));
        Map<String, Object> badRequest = Map.of(
            "amount", "10.00", "currency", "usd", "recipient", "net_xxx",
            "methodDetails", Map.of("networkId", "net_xxx")
        );

        assertThatThrownBy(() -> intent(api).verify(credential("spt_xxx"), badRequest))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("paymentMethodTypes");
    }
}
