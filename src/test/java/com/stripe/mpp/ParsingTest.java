package com.stripe.mpp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsingTest {

    // --- Challenge (WWW-Authenticate) ---

    @Test
    void challengeRoundTrips() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        Challenge original = Challenge.create("secret", "example.com", "tempo", "charge", request,
            "2099-01-01T00:00:00Z", "Test payment", null);

        String header = original.toWwwAuthenticate();
        assertThat(header).startsWith("Payment ");
        assertThat(header).contains("id=");
        assertThat(header).contains("realm=\"example.com\"");
        assertThat(header).contains("method=\"tempo\"");
        assertThat(header).contains("intent=\"charge\"");
        assertThat(header).contains("expires=\"2099-01-01T00:00:00Z\"");
        assertThat(header).contains("description=\"Test payment\"");

        var parsed = Challenge.fromWwwAuthenticate(header);
        assertThat(parsed).hasSize(1);
        Challenge c = parsed.get(0);
        assertThat(c.id()).isEqualTo(original.id());
        assertThat(c.realm()).isEqualTo("example.com");
        assertThat(c.method()).isEqualTo("tempo");
        assertThat(c.intent()).isEqualTo("charge");
        assertThat(c.expires()).isEqualTo("2099-01-01T00:00:00Z");
    }

    @Test
    void challengeFromWwwAuthenticateHandlesMultipleSchemes() {
        // Only "Payment" schemes should be parsed
        String header = "Bearer foo, Payment id=\"abc\", realm=\"x\", method=\"tempo\", intent=\"charge\", request=\"eyJ9\"";
        var challenges = Challenge.fromWwwAuthenticate(header);
        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).id()).isEqualTo("abc");
    }

    // --- Credential (Authorization) ---

    @Test
    void credentialRoundTrips() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        Challenge challenge = Challenge.create("secret", "example.com", "tempo", "charge", request,
            "2099-01-01T00:00:00Z", null, null);

        Map<String, Object> payload = Map.of("type", "transaction", "signature", "0xTxHash");
        Credential original = new Credential(challenge.toEcho(), payload, "did:pkh:eip155:1:0xABC");

        String authHeader = original.toAuthorization();
        assertThat(authHeader).startsWith("Payment ");
        // New format: single base64-JSON token — no auth-params key=value syntax
        assertThat(authHeader).doesNotContain("payload=");

        Credential parsed = Credential.fromAuthorization(authHeader);
        assertThat(parsed.challenge().id()).isEqualTo(challenge.id());
        assertThat(parsed.challenge().realm()).isEqualTo("example.com");
        assertThat(parsed.challenge().method()).isEqualTo("tempo");
        assertThat(parsed.challenge().intent()).isEqualTo("charge");
        assertThat(parsed.source()).isEqualTo("did:pkh:eip155:1:0xABC");
    }

    @Test
    void credentialParseFailsOnMissingPayload() {
        // base64-JSON envelope with challenge but no payload field
        String json = "{\"challenge\":{\"id\":\"abc\",\"realm\":\"r\",\"method\":\"m\",\"intent\":\"i\"}}";
        String bad = "Payment " + ChallengeId.b64urlEncode(json);
        assertThatThrownBy(() -> Credential.fromAuthorization(bad))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("payload");
    }

    // --- Receipt (Payment-Receipt) ---

    @Test
    void receiptRoundTrips() {
        Receipt original = new Receipt("success", Instant.parse("2025-01-01T12:00:00Z"),
            "ref-123", "tempo", "ext-456", null);

        String header = original.toPaymentReceipt();
        assertThat(header).startsWith("Payment-Receipt ");
        assertThat(header).contains("status=\"success\"");
        assertThat(header).contains("reference=\"ref-123\"");
        assertThat(header).contains("method=\"tempo\"");
        assertThat(header).contains("external_id=\"ext-456\"");

        Receipt parsed = Receipt.fromPaymentReceipt(header);
        assertThat(parsed.status()).isEqualTo("success");
        assertThat(parsed.reference()).isEqualTo("ref-123");
        assertThat(parsed.method()).isEqualTo("tempo");
        assertThat(parsed.externalId()).isEqualTo("ext-456");
        assertThat(parsed.timestamp()).isEqualTo(Instant.parse("2025-01-01T12:00:00Z"));
    }

    @Test
    void receiptSuccessFactory() {
        Receipt r = Receipt.success("ref-789", "tempo", "ext-000");
        assertThat(r.status()).isEqualTo("success");
        assertThat(r.reference()).isEqualTo("ref-789");
        assertThat(r.method()).isEqualTo("tempo");
    }
}
