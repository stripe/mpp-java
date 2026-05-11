package com.stripe.mpp;

import com.stripe.mpp.server.VerifyResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValueTypesTest {

    @Test
    void challengePreservesRecordStyleApiAndValueSemantics() {
        Map<String, Object> request = Map.of("amount", "10.00");
        Map<String, Object> opaque = Map.of("order", "order-123");
        Challenge challenge = new Challenge(
            "id",
            "tempo",
            "charge",
            request,
            "api.example.com",
            "request-b64",
            "sha-256=abc",
            "2099-01-01T00:00:00Z",
            "description",
            opaque
        );
        Challenge equivalent = new Challenge(
            "id",
            "tempo",
            "charge",
            request,
            "api.example.com",
            "request-b64",
            "sha-256=abc",
            "2099-01-01T00:00:00Z",
            "description",
            opaque
        );

        assertThat(challenge.id()).isEqualTo("id");
        assertThat(challenge.method()).isEqualTo("tempo");
        assertThat(challenge.intent()).isEqualTo("charge");
        assertThat(challenge.request()).isSameAs(request);
        assertThat(challenge.realm()).isEqualTo("api.example.com");
        assertThat(challenge.requestB64()).isEqualTo("request-b64");
        assertThat(challenge.digest()).isEqualTo("sha-256=abc");
        assertThat(challenge.expires()).isEqualTo("2099-01-01T00:00:00Z");
        assertThat(challenge.description()).isEqualTo("description");
        assertThat(challenge.opaque()).isSameAs(opaque);
        assertThat(challenge).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
    }

    @Test
    void challengeEchoPreservesRecordStyleApiAndValueSemantics() {
        Map<String, Object> opaque = Map.of("order", "order-123");
        ChallengeEcho echo = new ChallengeEcho(
            "id",
            "api.example.com",
            "tempo",
            "charge",
            "request-b64",
            "2099-01-01T00:00:00Z",
            "sha-256=abc",
            opaque
        );
        ChallengeEcho equivalent = new ChallengeEcho(
            "id",
            "api.example.com",
            "tempo",
            "charge",
            "request-b64",
            "2099-01-01T00:00:00Z",
            "sha-256=abc",
            opaque
        );

        assertThat(echo.id()).isEqualTo("id");
        assertThat(echo.realm()).isEqualTo("api.example.com");
        assertThat(echo.method()).isEqualTo("tempo");
        assertThat(echo.intent()).isEqualTo("charge");
        assertThat(echo.request()).isEqualTo("request-b64");
        assertThat(echo.expires()).isEqualTo("2099-01-01T00:00:00Z");
        assertThat(echo.digest()).isEqualTo("sha-256=abc");
        assertThat(echo.opaque()).isSameAs(opaque);
        assertThat(echo).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
    }

    @Test
    void credentialPreservesRecordStyleApiAndValueSemantics() {
        ChallengeEcho echo = new ChallengeEcho("id", "realm", "tempo", "charge", "request", null, null, null);
        Map<String, Object> payload = Map.of("hash", "0xabc");
        Credential credential = new Credential(echo, payload, "0xsource");
        Credential equivalent = new Credential(echo, payload, "0xsource");

        assertThat(credential.challenge()).isSameAs(echo);
        assertThat(credential.payload()).isSameAs(payload);
        assertThat(credential.source()).isEqualTo("0xsource");
        assertThat(credential).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
    }

    @Test
    void receiptPreservesRecordStyleApiAndValueSemantics() {
        Instant timestamp = Instant.parse("2025-01-01T00:00:00Z");
        Map<String, Object> extra = Map.of("network", "tempo");
        Receipt receipt = new Receipt("success", timestamp, "ref", "tempo", "external", extra);
        Receipt equivalent = new Receipt("success", timestamp, "ref", "tempo", "external", extra);

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.timestamp()).isSameAs(timestamp);
        assertThat(receipt.reference()).isEqualTo("ref");
        assertThat(receipt.method()).isEqualTo("tempo");
        assertThat(receipt.externalId()).isEqualTo("external");
        assertThat(receipt.extra()).isSameAs(extra);
        assertThat(receipt).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
    }

    @Test
    void verifyResultsPreserveRecordStyleApiAndValueSemantics() {
        ChallengeEcho echo = new ChallengeEcho("id", "realm", "tempo", "charge", "request", null, null, null);
        Challenge challenge = new Challenge("id", "tempo", "charge", null, "realm", "request", null, null, null, null);
        Credential credential = new Credential(echo, Map.of("hash", "0xabc"), null);
        Receipt receipt = new Receipt("success", Instant.parse("2025-01-01T00:00:00Z"), "ref", "tempo", null, null);

        VerifyResult.Challenged challenged = new VerifyResult.Challenged(challenge);
        VerifyResult.Challenged equivalentChallenged = new VerifyResult.Challenged(challenge);
        VerifyResult.Verified verified = new VerifyResult.Verified(credential, receipt);
        VerifyResult.Verified equivalentVerified = new VerifyResult.Verified(credential, receipt);

        assertThat(challenged.challenge()).isSameAs(challenge);
        assertThat(challenged).isEqualTo(equivalentChallenged).hasSameHashCodeAs(equivalentChallenged);
        assertThat(verified.credential()).isSameAs(credential);
        assertThat(verified.receipt()).isSameAs(receipt);
        assertThat(verified).isEqualTo(equivalentVerified).hasSameHashCodeAs(equivalentVerified);
    }
}
