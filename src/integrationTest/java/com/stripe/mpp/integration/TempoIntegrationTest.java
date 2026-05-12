package com.stripe.mpp.integration;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Mpp;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.methods.tempo.Tempo;
import com.stripe.mpp.methods.tempo.TempoChargeIntent;
import com.stripe.mpp.methods.tempo.TempoMethod;
import com.stripe.mpp.server.MppHandler;
import com.stripe.mpp.server.VerifyResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests that run against a live Tempo dev node.
 *
 * Start the node first:
 * <pre>
 *   make node            # docker compose up -d --wait
 *   make test-integration
 *   make node-stop
 * </pre>
 *
 * Or point at an existing node:
 * <pre>
 *   TEMPO_RPC_URL=http://localhost:8545 ./gradlew integrationTest
 * </pre>
 */
@Tag("integration")
class TempoIntegrationTest {

    // Well-known Hardhat/Foundry/Anvil dev account #0 — pre-funded on --dev nodes.
    static final String SENDER_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    // Dev account #1 — used as the payment recipient.
    static final String RECIPIENT  = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    static String     rpcUrl;
    static long       chainId;
    static Credentials signer;
    static HttpClient  http;

    @BeforeAll
    static void connect() throws Exception {
        rpcUrl  = System.getProperty("tempo.rpc.url", "http://localhost:8545");
        http    = HttpClient.newHttpClient();
        chainId = fetchChainId(rpcUrl, http);
        signer  = Credentials.create(SENDER_KEY);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /** Client signs a transaction and passes it raw — server broadcasts and verifies. */
    @Test
    void transactionCredentialVerifies() throws Exception {
        String rawTx = buildSignedTx(nextNonce(), BigInteger.valueOf(1_000L));

        TempoChargeIntent intent = Tempo.chargeIntent(rpcUrl);
        Credential credential = txCredential(rawTx);

        Receipt receipt = intent.verify(credential, baseRequest());

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.method()).isEqualTo("tempo");
        assertThat(receipt.reference()).startsWith("0x");
    }

    /** Client broadcasts first and passes only the hash — server polls for the receipt. */
    @Test
    void hashCredentialVerifies() throws Exception {
        String rawTx  = buildSignedTx(nextNonce(), BigInteger.valueOf(1_000L));
        String txHash = rpc("eth_sendRawTransaction", List.of(rawTx));

        TempoChargeIntent intent = Tempo.chargeIntent(rpcUrl);
        Credential credential = hashCredential(txHash);

        Receipt receipt = intent.verify(credential, baseRequest());

        assertThat(receipt.reference()).isEqualTo(txHash);
        assertThat(receipt.status()).isEqualTo("success");
    }

    /** Full 402 → payment → verified roundtrip at the SDK level, no HTTP server needed. */
    @Test
    void fullMppRoundTrip() throws Exception {
        TempoMethod tempo  = Tempo.method(rpcUrl, (int) chainId);
        MppHandler  server = Mpp.create(tempo, "localhost", "test-secret");

        // Step 1: no auth → server issues challenge
        VerifyResult r1 = server.charge(null, tempo.chargeIntent(), "0.010000", "USDC", RECIPIENT);
        assertThat(r1).isInstanceOf(VerifyResult.Challenged.class);
        Challenge challenge = ((VerifyResult.Challenged) r1).challenge();

        // Step 2: client builds a transaction and wraps it in a credential
        String rawTx = buildSignedTx(nextNonce(), BigInteger.valueOf(1_000L));
        Credential credential = new Credential(challenge.toEcho(), Map.of("transaction", rawTx), null);

        // Step 3: retry with the credential
        VerifyResult r2 = server.charge(
            credential.toAuthorization(),
            tempo.chargeIntent(),
            "0.010000", "USDC", RECIPIENT
        );

        assertThat(r2).isInstanceOf(VerifyResult.Verified.class);
        Receipt receipt = ((VerifyResult.Verified) r2).receipt();
        assertThat(receipt.method()).isEqualTo("tempo");
        assertThat(receipt.status()).isEqualTo("success");
    }

    /** A tampered challenge ID is rejected and a fresh challenge is re-issued. */
    @Test
    void tamperedChallengeIsRejected() throws Exception {
        TempoMethod tempo  = Tempo.method(rpcUrl, (int) chainId);
        MppHandler  server = Mpp.create(tempo, "localhost", "test-secret");

        VerifyResult r1 = server.charge(null, tempo.chargeIntent(), "0.010000", "USDC", RECIPIENT);
        Challenge challenge = ((VerifyResult.Challenged) r1).challenge();

        // Swap in a different payload that has never been broadcast.
        ChallengeEcho badEcho = new ChallengeEcho(
            "tampered-id",
            challenge.realm(), challenge.method(), challenge.intent(),
            challenge.requestB64(), challenge.expires(), challenge.digest(), challenge.opaque()
        );
        Credential credential = new Credential(badEcho, Map.of("transaction", "0xdeadbeef"), null);

        // Server should re-challenge rather than verify.
        VerifyResult r2 = server.charge(
            credential.toAuthorization(),
            tempo.chargeIntent(),
            "0.010000", "USDC", RECIPIENT
        );
        assertThat(r2).isInstanceOf(VerifyResult.Challenged.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> baseRequest() {
        return Map.of("amount", "0.010000", "currency", "USDC", "recipient", RECIPIENT);
    }

    private static Credential txCredential(String rawTx) {
        return new Credential(stubEcho(), Map.of("transaction", rawTx), null);
    }

    private static Credential hashCredential(String txHash) {
        return new Credential(stubEcho(), Map.of("hash", txHash), null);
    }

    private static ChallengeEcho stubEcho() {
        return new ChallengeEcho(
            "test-id", "localhost", "tempo", "charge",
            "e30", "2099-01-01T00:00:00Z", null, null
        );
    }

    private String buildSignedTx(BigInteger nonce, BigInteger valueWei) throws Exception {
        RawTransaction tx = RawTransaction.createEtherTransaction(
            nonce,
            BigInteger.valueOf(875_000_000L), // gas price (0.875 Gwei)
            BigInteger.valueOf(21_000L),       // gas limit for a plain transfer
            RECIPIENT,
            valueWei
        );
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, signer);
        return Numeric.toHexString(signed);
    }

    private BigInteger nextNonce() throws Exception {
        String hex = rpc("eth_getTransactionCount", List.of(signer.getAddress(), "latest"));
        return Numeric.decodeQuantity(hex);
    }

    @SuppressWarnings("unchecked")
    private <T> T rpc(String method, List<Object> params) throws Exception {
        var body = Map.of("jsonrpc", "2.0", "method", method, "params", params, "id", 1);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(rpcUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> response = mapper.readValue(resp.body(), Map.class);
        if (response.containsKey("error")) {
            throw new RuntimeException("RPC error: " + response.get("error"));
        }
        return (T) response.get("result");
    }

    private static long fetchChainId(String url, HttpClient client) throws Exception {
        var body = Map.of("jsonrpc", "2.0", "method", "eth_chainId", "params", List.of(), "id", 1);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = mapper.readValue(resp.body(), Map.class);
        return Numeric.decodeQuantity((String) response.get("result")).longValue();
    }
}
