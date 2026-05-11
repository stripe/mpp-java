package com.stripe.mpp;

import com.stripe.mpp.error.PaymentException;
import com.stripe.mpp.server.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests simulating the full MPP request/response cycle.
 */
class IntegrationTest {

    // --- Test stubs ---

    static class ChargeIntent implements Intent {
        @Override
        public String name() { return "charge"; }

        @Override
        public Receipt verify(Credential credential, Map<String, Object> request) throws PaymentException {
            // In a real implementation this would verify the payment on-chain or via Stripe.
            return Receipt.success("tx-ref-12345", "test");
        }
    }

    static class TestMethod implements Method {
        @Override
        public String name() { return "test"; }

        @Override
        public List<Class<? extends Intent>> intents() {
            return List.of(ChargeIntent.class);
        }
    }

    // --- Tests ---

    @Test
    void firstRequestReceivesChallenge() {
        MppHandler server = Mpp.create(new TestMethod(), "api.example.com", "super-secret");
        Intent intent = new ChargeIntent();

        // No Authorization header — should return a challenge
        VerifyResult result = server.charge(null, intent, "10.000000", "USDC", "0xRecipient");

        assertThat(result).isInstanceOf(VerifyResult.Challenged.class);
        Challenge challenge = ((VerifyResult.Challenged) result).challenge();
        assertThat(challenge.id()).isNotNull();
        assertThat(challenge.realm()).isEqualTo("api.example.com");
        assertThat(challenge.method()).isEqualTo("test");
        assertThat(challenge.intent()).isEqualTo("charge");
        assertThat(challenge.expires()).isNotNull();
    }

    @Test
    void challengeSerializesToValidWwwAuthenticate() {
        MppHandler server = Mpp.create(new TestMethod(), "api.example.com", "super-secret");

        VerifyResult result = server.charge(null, new ChargeIntent(), "10.000000", "USDC", "0xRecipient");
        Challenge challenge = ((VerifyResult.Challenged) result).challenge();
        String header = challenge.toWwwAuthenticate();

        assertThat(header).startsWith("Payment id=");
        assertThat(header).contains("realm=\"api.example.com\"");
        assertThat(header).contains("method=\"test\"");
        assertThat(header).contains("intent=\"charge\"");
        assertThat(header).contains("request=");
    }

    @Test
    void validCredentialIsVerified() {
        String secretKey = "super-secret";
        String realm = "api.example.com";
        MppHandler server = Mpp.create(new TestMethod(), realm, secretKey);
        Intent intent = new ChargeIntent();

        // Step 1: get the challenge
        VerifyResult step1 = server.charge(null, intent, "10.000000", "USDC", "0xRecipient");
        Challenge challenge = ((VerifyResult.Challenged) step1).challenge();

        // Step 2: build a credential from the challenge echo
        Map<String, Object> payload = Map.of("signature", "0xSigData");
        Credential credential = new Credential(challenge.toEcho(), payload, null);
        String authHeader = credential.toAuthorization();

        // Step 3: present the credential to the server
        VerifyResult step2 = server.charge(authHeader, intent, "10.000000", "USDC", "0xRecipient");

        assertThat(step2).isInstanceOf(VerifyResult.Verified.class);
        VerifyResult.Verified verified = (VerifyResult.Verified) step2;
        assertThat(verified.receipt().status()).isEqualTo("success");
        assertThat(verified.receipt().reference()).isEqualTo("tx-ref-12345");
    }

    @Test
    void tamperedChallengeIdForcesNewChallenge() {
        MppHandler server = Mpp.create(new TestMethod(), "api.example.com", "super-secret");
        Intent intent = new ChargeIntent();

        VerifyResult step1 = server.charge(null, intent, "10.000000", "USDC", "0xRecipient");
        Challenge challenge = ((VerifyResult.Challenged) step1).challenge();

        // Tamper with the challenge ID
        ChallengeEcho tamperedEcho = new ChallengeEcho(
            "TAMPERED_ID",
            challenge.realm(),
            challenge.method(),
            challenge.intent(),
            challenge.requestB64(),
            challenge.expires(),
            challenge.digest(),
            challenge.opaque()
        );
        Credential credential = new Credential(tamperedEcho, Map.of("sig", "x"), null);
        String authHeader = credential.toAuthorization();

        VerifyResult result = server.charge(authHeader, intent, "10.000000", "USDC", "0xRecipient");
        // Should return a new challenge, not verify
        assertThat(result).isInstanceOf(VerifyResult.Challenged.class);
    }

    @Test
    void wrongAmountForcesNewChallenge() {
        MppHandler server = Mpp.create(new TestMethod(), "api.example.com", "super-secret");
        Intent intent = new ChargeIntent();

        // Get challenge for 10 USDC
        VerifyResult step1 = server.charge(null, intent, "10.000000", "USDC", "0xRecipient");
        Challenge challenge = ((VerifyResult.Challenged) step1).challenge();

        // Build credential for 10 USDC challenge
        Credential credential = new Credential(challenge.toEcho(), Map.of("sig", "x"), null);
        String authHeader = credential.toAuthorization();

        // Try to use it for a 20 USDC request
        VerifyResult result = server.charge(authHeader, intent, "20.000000", "USDC", "0xRecipient");
        assertThat(result).isInstanceOf(VerifyResult.Challenged.class);
    }

    @Test
    void receiptRoundTripsViaHeader() {
        Receipt receipt = Receipt.success("ref-999", "test", "ext-111");
        String header = receipt.toPaymentReceipt();
        Receipt parsed = Receipt.fromPaymentReceipt(header);

        assertThat(parsed.status()).isEqualTo("success");
        assertThat(parsed.reference()).isEqualTo("ref-999");
        assertThat(parsed.method()).isEqualTo("test");
        assertThat(parsed.externalId()).isEqualTo("ext-111");
    }
}
