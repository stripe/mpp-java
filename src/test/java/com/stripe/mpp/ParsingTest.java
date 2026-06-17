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
        String header = "Bearer foo, Payment id=\"abc\", realm=\"x\", method=\"tempo\", intent=\"charge\", request=\"e30\"";
        var challenges = Challenge.fromWwwAuthenticate(header);
        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).id()).isEqualTo("abc");
    }

    @Test
    void challengeFromWwwAuthenticateHandlesMergedPaymentChallenges() {
        String first = "Payment id=\"one\", realm=\"api.example.com\", method=\"tempo\", intent=\"charge\", request=\"e30\"";
        String second = "Payment id=\"two\", realm=\"api.example.com\", method=\"stripe\", intent=\"charge\", request=\"e30\"";

        var challenges = Challenge.fromWwwAuthenticate(first + ", Bearer realm=\"fallback\", " + second);

        assertThat(challenges).hasSize(2);
        assertThat(challenges.get(0).id()).isEqualTo("one");
        assertThat(challenges.get(1).id()).isEqualTo("two");
    }

    @Test
    void challengeFromWwwAuthenticateIgnoresPaymentInsideQuotes() {
        String first = "Payment id=\"one\", realm=\"api, Payment realm\", method=\"tempo\", intent=\"charge\", request=\"e30\"";
        String second = "Payment id=\"two\", realm=\"api.example.com\", method=\"stripe\", intent=\"charge\", request=\"e30\"";

        var challenges = Challenge.fromWwwAuthenticate(first + ", " + second);

        assertThat(challenges).hasSize(2);
        assertThat(challenges.get(0).realm()).isEqualTo("api, Payment realm");
        assertThat(challenges.get(1).id()).isEqualTo("two");
    }

    @Test
    void challengeFromWwwAuthenticateAllowsWhitespaceAroundParamEquals() {
        String header = "Payment id=\"abc\", realm = \"api.example.com\", method=\"tempo\", intent=\"charge\", request=\"e30\"";

        var challenges = Challenge.fromWwwAuthenticate(header);

        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).realm()).isEqualTo("api.example.com");
    }

    @Test
    void challengeFromWwwAuthenticateRejectsDuplicateParameters() {
        String header = "Payment id=\"abc\", realm=\"api.example.com\", method=\"tempo\", intent=\"charge\", request=\"e30\", id=\"def\"";

        assertThatThrownBy(() -> Challenge.fromWwwAuthenticate(header))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void challengeEscapesQuotedStringDescription() {
        Challenge original = Challenge.create("secret", "example.com", "tempo", "charge", Map.of(),
            null, "Pay \"premium\" API", null);

        String header = original.toWwwAuthenticate();

        assertThat(header).contains("description=\"Pay \\\"premium\\\" API\"");
        var parsed = Challenge.fromWwwAuthenticate(header);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).description()).isEqualTo("Pay \"premium\" API");
    }

    @Test
    void challengeEscapesBackslashDescription() {
        Challenge original = Challenge.create("secret", "example.com", "tempo", "charge", Map.of(),
            null, "Path C:\\tempo\\api", null);

        String header = original.toWwwAuthenticate();

        assertThat(header).contains("description=\"Path C:\\\\tempo\\\\api\"");
        var parsed = Challenge.fromWwwAuthenticate(header);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).description()).isEqualTo("Path C:\\tempo\\api");
    }

    @Test
    void challengeRejectsCrlfDescription() {
        Challenge challenge = Challenge.create("secret", "example.com", "tempo", "charge", Map.of(),
            null, "Line one\r\nLine two", null);

        assertThatThrownBy(challenge::toWwwAuthenticate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CR or LF");
    }

    @Test
    void challengeRejectsOversizedRequestParameter() {
        String oversizedRequest = "a".repeat(Parsing.MAX_PAYLOAD_SIZE + 1);
        String header = "Payment id=\"abc\", realm=\"api\", method=\"tempo\", intent=\"charge\", request=\""
            + oversizedRequest
            + "\"";

        assertThatThrownBy(() -> Challenge.fromWwwAuthenticate(header))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("Request parameter exceeds");
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

    @Test
    void credentialWithoutSourceParsesAsNullSource() {
        String json = "{\"challenge\":{\"id\":\"abc\",\"realm\":\"r\",\"method\":\"tempo\",\"intent\":\"charge\"},\"payload\":{}}";
        String header = "Payment " + ChallengeId.b64urlEncode(json);

        Credential parsed = Credential.fromAuthorization(header);

        assertThat(parsed.source()).isNull();
    }

    @Test
    void credentialParseRejectsInvalidMethodId() {
        String json = "{\"challenge\":{\"id\":\"abc\",\"realm\":\"r\",\"method\":\"Tempo\",\"intent\":\"i\"},\"payload\":{}}";
        String bad = "Payment " + ChallengeId.b64urlEncode(json);

        assertThatThrownBy(() -> Credential.fromAuthorization(bad))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("payment method ID");
    }

    @Test
    void credentialParseRejectsNonStringMethod() {
        String json = "{\"challenge\":{\"id\":\"abc\",\"realm\":\"r\",\"method\":true,\"intent\":\"i\"},\"payload\":{}}";
        String bad = "Payment " + ChallengeId.b64urlEncode(json);

        assertThatThrownBy(() -> Credential.fromAuthorization(bad))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("method");
    }

    // --- Receipt (Payment-Receipt) ---

    @Test
    void receiptRoundTrips() {
        Receipt original = new Receipt("success", Instant.parse("2025-01-01T12:00:00Z"),
            "ref-123", "tempo", "ext-456", null);

        String header = original.toPaymentReceipt();
        // Value is a bare base64url token — no scheme prefix, no quotes
        assertThat(header).doesNotContain(" ");
        assertThat(header).doesNotContain("\"");

        Receipt parsed = Receipt.fromPaymentReceipt(header);
        assertThat(parsed.status()).isEqualTo("success");
        assertThat(parsed.reference()).isEqualTo("ref-123");
        assertThat(parsed.method()).isEqualTo("tempo");
        assertThat(parsed.externalId()).isEqualTo("ext-456");
        assertThat(parsed.timestamp()).isEqualTo(Instant.parse("2025-01-01T12:00:00Z"));
        Map<?, ?> wireReceipt = (Map<?, ?>) Parsing.b64Decode(header);
        assertThat(wireReceipt.get("externalId")).isEqualTo("ext-456");
        assertThat(wireReceipt.containsKey("external_id")).isFalse();
    }

    @Test
    void receiptSuccessFactory() {
        Receipt r = Receipt.success("ref-789", "tempo", "ext-000");
        assertThat(r.status()).isEqualTo("success");
        assertThat(r.reference()).isEqualTo("ref-789");
        assertThat(r.method()).isEqualTo("tempo");
    }

    @Test
    void receiptSuccessFactoryPreservesExtra() {
        Map<String, Object> extra = Map.of("trace_id", "trace-123");

        Receipt r = Receipt.success("ref-789", "tempo", "ext-000", extra);

        assertThat(r.extra()).isSameAs(extra);
    }

    @Test
    void receiptParseAcceptsLegacyExternalIdKey() {
        String header = ChallengeId.b64urlEncode(
            "{\"method\":\"tempo\",\"reference\":\"ref-123\",\"status\":\"success\","
                + "\"timestamp\":\"2025-01-01T12:00:00Z\",\"external_id\":\"legacy-ext\"}"
        );

        Receipt parsed = Receipt.fromPaymentReceipt(header);

        assertThat(parsed.externalId()).isEqualTo("legacy-ext");
    }

    @Test
    void receiptParseRejectsMissingMethod() {
        String header = ChallengeId.b64urlEncode(
            "{\"reference\":\"ref-123\",\"status\":\"success\",\"timestamp\":\"2025-01-01T12:00:00Z\"}"
        );

        assertThatThrownBy(() -> Receipt.fromPaymentReceipt(header))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("method");
    }

    @Test
    void receiptParseRejectsInvalidMethodId() {
        String header = ChallengeId.b64urlEncode(
            "{\"method\":\"tempo-pay\",\"reference\":\"ref-123\",\"status\":\"success\","
                + "\"timestamp\":\"2025-01-01T12:00:00Z\"}"
        );

        assertThatThrownBy(() -> Receipt.fromPaymentReceipt(header))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("payment method ID");
    }

    @Test
    void receiptParseRejectsNonStringMethod() {
        String header = ChallengeId.b64urlEncode(
            "{\"method\":true,\"reference\":\"ref-123\",\"status\":\"success\","
                + "\"timestamp\":\"2025-01-01T12:00:00Z\"}"
        );

        assertThatThrownBy(() -> Receipt.fromPaymentReceipt(header))
            .isInstanceOf(com.stripe.mpp.error.ParseException.class)
            .hasMessageContaining("method");
    }
}
