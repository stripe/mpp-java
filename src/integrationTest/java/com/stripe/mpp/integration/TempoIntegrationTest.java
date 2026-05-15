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
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    static final String SENDER_KEY      = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    // Dev account #1 — used as the payment recipient.
    static final String RECIPIENT       = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    // TIP-20 token contract (the faucet address configured in docker-compose.yml).
    static final String TOKEN_CONTRACT  = "0x20c0000000000000000000000000000000000000";

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

        // Token amount matches what buildSignedTx sends (1_000 atomic units = 0.001000 tokens)
        BigInteger tokenAmount = BigInteger.valueOf(1_000L);
        String chargeAmount = "0.001000";

        // Step 1: no auth → server issues challenge
        VerifyResult r1 = server.charge(null, tempo.chargeIntent(), chargeAmount, TOKEN_CONTRACT, RECIPIENT);
        assertThat(r1).isInstanceOf(VerifyResult.Challenged.class);
        Challenge challenge = ((VerifyResult.Challenged) r1).challenge();

        // Step 2: client builds a transaction and wraps it in a credential
        String rawTx = buildSignedTx(nextNonce(), tokenAmount);
        Credential credential = new Credential(challenge.toEcho(), Map.of("type", "transaction", "signature", rawTx), null);

        // Step 3: retry with the credential
        VerifyResult r2 = server.charge(
            credential.toAuthorization(),
            tempo.chargeIntent(),
            chargeAmount, TOKEN_CONTRACT, RECIPIENT
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

        VerifyResult r1 = server.charge(null, tempo.chargeIntent(), "0.001000", TOKEN_CONTRACT, RECIPIENT);
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
            "0.001000", TOKEN_CONTRACT, RECIPIENT
        );
        assertThat(r2).isInstanceOf(VerifyResult.Challenged.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> baseRequest() {
        return Map.of("amount", "1000", "currency", TOKEN_CONTRACT, "recipient", RECIPIENT);
    }

    private static Credential txCredential(String rawTx) {
        return new Credential(stubEcho(), Map.of("type", "transaction", "signature", rawTx), null);
    }

    private static Credential hashCredential(String txHash) {
        return new Credential(stubEcho(), Map.of("type", "hash", "hash", txHash), null);
    }

    private static ChallengeEcho stubEcho() {
        return new ChallengeEcho(
            "test-id", "localhost", "tempo", "charge",
            "e30", "2099-01-01T00:00:00Z", null, null
        );
    }

    /**
     * Build a signed Tempo 0x76 transaction that calls transfer(address,uint256)
     * on the TIP-20 token contract.
     *
     * <p>Tempo uses a custom transaction type (0x76) distinct from legacy EVM transactions.
     * RLP field order (tempo-primitives TempoTransaction):
     * chain_id, max_priority_fee_per_gas, max_fee_per_gas, gas_limit, calls, access_list,
     * nonce_key, nonce, valid_before, valid_after, fee_token, fee_payer_signature,
     * tempo_auth_list — followed by the secp256k1 signature bytes (r||s||v, 65 bytes).
     */
    private String buildSignedTx(BigInteger nonce, BigInteger tokenAmount) throws Exception {
        // ABI-encode transfer(address, uint256) call data
        Function function = new Function(
            "transfer",
            Arrays.asList(new Address(RECIPIENT), new Uint256(tokenAmount)),
            Collections.emptyList()
        );
        byte[] callData = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));
        byte[] toAddress = Numeric.hexStringToByteArray(TOKEN_CONTRACT);

        // Fetch gas price from the dev node
        BigInteger gasPrice = Numeric.decodeQuantity(
            (String) rpc("eth_gasPrice", List.of()));

        // Call item: [to_address, value=0, input=callData]
        List<RlpType> callItems = new ArrayList<>();
        callItems.add(RlpString.create(toAddress));
        callItems.add(RlpString.create(BigInteger.ZERO));
        callItems.add(RlpString.create(callData));

        List<RlpType> callsList = new ArrayList<>();
        callsList.add(new RlpList(callItems));

        // Transaction fields in Tempo order
        List<RlpType> fields = new ArrayList<>();
        fields.add(RlpString.create(BigInteger.valueOf(chainId)));   // chain_id
        fields.add(RlpString.create(gasPrice));                      // max_priority_fee_per_gas
        fields.add(RlpString.create(gasPrice));                      // max_fee_per_gas
        fields.add(RlpString.create(BigInteger.valueOf(5_000_000L)));// gas_limit
        fields.add(new RlpList(callsList));                          // calls
        fields.add(new RlpList(new ArrayList<>()));                  // access_list []
        fields.add(RlpString.create(BigInteger.ZERO));               // nonce_key = 0
        fields.add(RlpString.create(nonce));                         // nonce
        fields.add(RlpString.create(new byte[]{}));                  // valid_before = None
        fields.add(RlpString.create(new byte[]{}));                  // valid_after  = None
        fields.add(RlpString.create(new byte[]{}));                  // fee_token    = None
        fields.add(RlpString.create(new byte[]{}));                  // fee_payer_signature = None
        fields.add(new RlpList(new ArrayList<>()));                  // tempo_auth_list []

        // Signing input: 0x76 || RLP_list(fields)
        // Sign.signMessage will keccak256-hash this before signing — matching Tempo's signature_hash()
        byte[] rlpFields = RlpEncoder.encode(new RlpList(fields));
        byte[] signingInput = new byte[1 + rlpFields.length];
        signingInput[0] = (byte) 0x76;
        System.arraycopy(rlpFields, 0, signingInput, 1, rlpFields.length);

        Sign.SignatureData sigData = Sign.signMessage(signingInput, signer.getEcKeyPair());

        // Signature: r(32) || s(32) || v(0 or 1)  — Tempo uses parity bit, not 27/28
        byte[] sigBytes = new byte[65];
        System.arraycopy(padTo32(sigData.getR()), 0, sigBytes, 0, 32);
        System.arraycopy(padTo32(sigData.getS()), 0, sigBytes, 32, 32);
        sigBytes[64] = (byte) (sigData.getV()[0] - 27);

        // Signed transaction: fields + signature bytes appended as RLP byte string
        List<RlpType> signedFields = new ArrayList<>(fields);
        signedFields.add(RlpString.create(sigBytes));

        byte[] rlpSigned = RlpEncoder.encode(new RlpList(signedFields));
        byte[] signedTx = new byte[1 + rlpSigned.length];
        signedTx[0] = (byte) 0x76;
        System.arraycopy(rlpSigned, 0, signedTx, 1, rlpSigned.length);

        return Numeric.toHexString(signedTx);
    }

    private static byte[] padTo32(byte[] bytes) {
        if (bytes.length == 32) return bytes;
        if (bytes.length > 32) return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return padded;
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
