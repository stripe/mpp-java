package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import com.stripe.mpp.Mpp;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentExpiredException;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.MppHandler;
import com.stripe.mpp.server.ValidationResult;
import com.stripe.mpp.server.VerifyResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoRelayTest {
    private static final Map<String, Object> REQUEST = Map.of(
        "amount", "10000",
        "currency", TempoDefaults.TESTNET_PATH_USD,
        "methodDetails", Map.of("chainId", TempoDefaults.TESTNET_CHAIN_ID),
        "recipient", "0xabcdef1234567890abcdef1234567890abcdef12"
    );
    private static final ChallengeEcho ECHO = new ChallengeEcho(
        "challenge_123",
        "api.example.com",
        "tempo",
        "charge",
        ChallengeId.b64urlEncode(Json.compact(REQUEST)),
        "2099-01-01T00:00:00Z",
        null,
        null
    );
    private static final Credential CREDENTIAL = new Credential(
        ECHO,
        Map.of("signature", "0x1234", "type", "transaction"),
        "did:pkh:eip155:42431:0x123"
    );

    @Test
    void validationIsNonMutatingAndPreservesTheConfiguredBasePath() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> Reply.json(200, Map.of("success", true)));
            TempoChargeIntent intent = intent(server);

            ValidationResult result = intent.validate(CREDENTIAL, Map.of("untrusted", true));

            assertThat(result.credential()).isSameAs(CREDENTIAL);
            assertThat(result.request()).isEqualTo(REQUEST);
            assertThat(server.calls).hasSize(1);
            Call call = server.calls.get(0);
            assertThat(call.path).isEqualTo("/relay/v1/mpp/validate");
            assertThat(call.apiKey).isEqualTo("test-api-key");
            assertThat(call.idempotencyKey).isNull();
            assertThat(call.body).containsEntry("source", CREDENTIAL.source());
            assertThat(challenge(call).get("request")).isEqualTo(REQUEST);
        }
    }

    @Test
    void omitsAnEmptyCredentialSource() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> Reply.json(200, Map.of("success", true)));
            Credential credential = new Credential(ECHO, CREDENTIAL.payload(), "");

            intent(server).validate(credential, REQUEST);

            assertThat(server.calls.get(0).body).doesNotContainKey("source");
        }
    }

    @Test
    void malformedChallengeRequestFailsWithoutCallingTheRelay() throws Exception {
        try (RelayServer server = new RelayServer()) {
            TempoChargeIntent intent = intent(server);
            Credential credential = new Credential(
                new ChallengeEcho(
                    "id", "api.example.com", "tempo", "charge", "not-base64url!",
                    "2099-01-01T00:00:00Z", null, null
                ),
                CREDENTIAL.payload(),
                CREDENTIAL.source()
            );

            assertThatThrownBy(() -> intent.validate(credential, REQUEST))
                .isInstanceOf(VerificationFailedException.class)
                .hasMessageContaining("invalid challenge request");
            assertThat(server.calls).isEmpty();
        }
    }

    @Test
    void forwardsTheExactSpecOpaqueValue() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> Reply.json(200, Map.of("success", true)));
            String opaque = ChallengeId.b64urlEncode("xy wrong");
            String header = "Payment id=\"challenge_opaque\", realm=\"api.example.com\", "
                + "method=\"tempo\", intent=\"charge\", request=\""
                + ChallengeId.b64urlEncode(Json.compact(REQUEST))
                + "\", expires=\"2099-01-01T00:00:00Z\", opaque=\"" + opaque + "\"";
            Challenge challenge = Challenge.fromWwwAuthenticate(header).get(0);
            Credential credential = new Credential(
                challenge.toEcho(), CREDENTIAL.payload(), CREDENTIAL.source()
            );

            intent(server).validate(credential, REQUEST);

            assertThat(challenge(server.calls.get(0)).get("opaque")).isEqualTo(opaque);
        }
    }

    @Test
    void verifyValidatesThenBroadcastsAndReturnsTheRelayReceipt() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> call.path.endsWith("/validate")
                ? Reply.json(200, Map.of("success", true))
                : successReceipt());
            TempoChargeIntent intent = intent(server);

            Receipt receipt = intent.verify(CREDENTIAL, REQUEST);

            assertThat(server.paths()).containsExactly(
                "/relay/v1/mpp/validate",
                "/relay/v1/mpp/broadcast"
            );
            assertThat(receipt.status()).isEqualTo("success");
            assertThat(receipt.method()).isEqualTo("tempo");
            assertThat(receipt.reference()).isEqualTo("0xabc");
            assertThat(receipt.externalId()).isEqualTo("order_123");
            assertThat(receipt.timestamp()).isEqualTo(Instant.parse("2026-07-22T00:00:00Z"));
        }
    }

    @Test
    void handlerAcceptsTheRelayIntentAndUsesItsSplitLifecycle() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> call.path.endsWith("/validate")
                ? Reply.json(200, Map.of("success", true))
                : successReceipt());
            TempoRelay relay = TempoRelay.builder("test-api-key")
                .apiBaseUrl(server.baseUrl())
                .build();
            TempoMethod method = TempoMethod.of().testnet().relay(relay).build();
            TempoChargeIntent intent = method.chargeIntent();
            MppHandler handler = Mpp.create(method, "api.example.com", "secret");
            Challenge challenge = ((VerifyResult.Challenged) handler.charge(
                null, intent, "0.010000", TempoDefaults.TESTNET_PATH_USD,
                "0xabcdef1234567890abcdef1234567890abcdef12"
            )).challenge();
            Credential credential = new Credential(
                challenge.toEcho(), CREDENTIAL.payload(), CREDENTIAL.source()
            );

            VerifyResult result = handler.charge(
                credential.toAuthorization(), intent, "0.010000",
                TempoDefaults.TESTNET_PATH_USD,
                "0xabcdef1234567890abcdef1234567890abcdef12"
            );

            assertThat(result).isInstanceOf(VerifyResult.Verified.class);
            assertThat(server.paths()).containsExactly(
                "/relay/v1/mpp/validate",
                "/relay/v1/mpp/broadcast"
            );
        }
    }

    @Test
    void transactionBroadcastUsesAStableTransactionHashIdempotencyKey() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> successReceipt());
            TempoChargeIntent intent = intent(server);

            intent.broadcast(CREDENTIAL, REQUEST);
            intent.broadcast(CREDENTIAL, REQUEST);

            assertThat(server.calls).hasSize(2);
            String first = server.calls.get(0).idempotencyKey;
            assertThat(first).isEqualTo(
                "mpp_0x56570de287d73cd1cb6092bb8fdee6173974955fdef345ae579ee9f475ea7432"
            );
            assertThat(server.calls.get(1).idempotencyKey).isEqualTo(first);
        }
    }

    @Test
    void proofBroadcastUsesTheCrossSdkCanonicalCredentialHash() throws Exception {
        try (RelayServer server = new RelayServer()) {
            server.respond(call -> successReceipt());
            TempoChargeIntent intent = intent(server);
            Credential proof = new Credential(
                ECHO,
                Map.of("proof", "proof_123", "type", "proof"),
                CREDENTIAL.source()
            );

            intent.broadcast(proof, REQUEST);

            assertThat(server.calls.get(0).idempotencyKey).isEqualTo(
                "mpp_0x2f1e58d9f7fa16847ec115a9d6262177de8ab45dd184b21891aa42d88d8e4770"
            );
        }
    }

    @Test
    void mapsRelayErrorsWithoutExposingPrivateMessages() throws Exception {
        try (RelayServer server = new RelayServer()) {
            TempoChargeIntent intent = intent(server);
            server.respond(call -> Reply.json(200, Map.of(
                "error", Map.of("code", "temporarily_unavailable", "message", "private detail"),
                "success", false
            )));

            assertThatThrownBy(() -> intent.validate(CREDENTIAL, REQUEST))
                .isInstanceOfSatisfying(VerificationFailedException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("Payment verification failed.");
                    assertThat(error.getMessage()).doesNotContain("private detail");
                    assertThat(error.getDetails()).containsEntry("code", "temporarily_unavailable");
                    assertThat(error.getDetails()).containsEntry("retry", "same_credential");
                    assertThat(error.toProblemDetails()).containsEntry("details", error.getDetails());
                });

            server.respond(call -> Reply.json(200, Map.of(
                "error", Map.of("code", "expired", "message", "private detail"),
                "success", false
            )));
            assertThatThrownBy(() -> intent.validate(CREDENTIAL, REQUEST))
                .isInstanceOf(PaymentExpiredException.class)
                .hasMessage("Payment has expired.");

            server.respond(call -> Reply.json(200, Map.of(
                "error", Map.of("code", "insufficient_funds", "message", "private detail"),
                "success", false
            )));
            assertThatThrownBy(() -> intent.validate(CREDENTIAL, REQUEST))
                .isInstanceOfSatisfying(VerificationFailedException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("Payment verification failed.");
                    assertThat(error.getDetails()).containsEntry("code", "insufficient_funds");
                });

            server.respond(call -> Reply.json(403, Map.of(
                "error", Map.of("code", "insufficient_funds", "message", "private detail")
            )));
            assertThatThrownBy(() -> intent.validate(CREDENTIAL, REQUEST))
                .isInstanceOfSatisfying(VerificationFailedException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("Payment verification failed.");
                    assertThat(error.getDetails()).isEmpty();
                });
        }
    }

    @Test
    void rejectsMalformedOrMismatchedRelayReceipts() throws Exception {
        try (RelayServer server = new RelayServer()) {
            TempoChargeIntent intent = intent(server);
            server.respond(call -> Reply.json(200, Map.of(
                "receipt", Map.of(
                    "method", "stripe",
                    "reference", "0xabc",
                    "timestamp", "2026-07-22T00:00:00Z"
                ),
                "success", true
            )));

            assertThatThrownBy(() -> intent.broadcast(CREDENTIAL, REQUEST))
                .isInstanceOf(VerificationFailedException.class)
                .hasMessage("Payment verification failed.");
        }
    }

    private static TempoChargeIntent intent(RelayServer server) {
        TempoRelay relay = TempoRelay.builder("test-api-key")
            .apiBaseUrl(server.baseUrl())
            .build();
        return TempoMethod.of().testnet().relay(relay).build().chargeIntent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> challenge(Call call) {
        return (Map<String, Object>) call.body.get("challenge");
    }

    private static Reply successReceipt() {
        return Reply.json(200, Map.of(
            "receipt", Map.of(
                "externalId", "order_123",
                "method", "tempo",
                "reference", "0xabc",
                "timestamp", "2026-07-22T00:00:00Z"
            ),
            "success", true
        ));
    }

    private static final class Call {
        final String path;
        final String apiKey;
        final String idempotencyKey;
        final Map<String, Object> body;

        Call(HttpExchange exchange) throws IOException {
            this.path = exchange.getRequestURI().getPath();
            this.apiKey = exchange.getRequestHeaders().getFirst("tempo-api-key");
            this.idempotencyKey = exchange.getRequestHeaders().getFirst("idempotency-key");
            String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            this.body = Json.parseMap(json);
        }
    }

    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static Reply json(int status, Map<String, Object> body) {
            return new Reply(status, Json.compact(body));
        }
    }

    private static final class RelayServer implements AutoCloseable {
        final HttpServer server;
        final List<Call> calls = new ArrayList<>();
        volatile Function<Call, Reply> responder;

        RelayServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void respond(Function<Call, Reply> responder) {
            this.responder = responder;
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/relay");
        }

        List<String> paths() {
            List<String> paths = new ArrayList<>();
            for (Call call : calls) paths.add(call.path);
            return paths;
        }

        void handle(HttpExchange exchange) throws IOException {
            Call call = new Call(exchange);
            calls.add(call);
            Reply reply = responder.apply(call);
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
