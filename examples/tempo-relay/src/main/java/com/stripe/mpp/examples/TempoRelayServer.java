package com.stripe.mpp.examples;

import com.stripe.mpp.Json;
import com.stripe.mpp.Mpp;
import com.stripe.mpp.error.PaymentException;
import com.stripe.mpp.methods.tempo.TempoMethod;
import com.stripe.mpp.server.ChargeRequest;
import com.stripe.mpp.server.MppHandler;
import com.stripe.mpp.server.VerifyResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP server that accepts a Moderato pathUSD charge through Tempo API's relay. */
public final class TempoRelayServer {
    private static final String PATH_USD = "0x20c0000000000000000000000000000000000000";

    private TempoRelayServer() {}

    public static void main(String[] args) throws IOException {
        String apiKey = required("TEMPO_API_KEY");
        String recipient = required("MPP_RECIPIENT");
        String secretKey = System.getenv().getOrDefault(
            "MPP_SECRET_KEY", "mpp-java-tempo-relay-development-secret-key"
        );
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        TempoMethod tempo = TempoMethod.of().testnet().relay(apiKey).build();
        MppHandler payments = Mpp.create(tempo, "localhost:" + port, secretKey);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/api/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            send(exchange, 200, Map.of("status", "ok"));
        });
        server.createContext("/api/photo", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            ChargeRequest charge = ChargeRequest.of(
                tempo.chargeIntent(), "0.01", PATH_USD, recipient
            ).description("Relay-backed Java example");
            try {
                VerifyResult result = payments.charge(
                    exchange.getRequestHeaders().getFirst("Authorization"), charge
                );
                if (result instanceof VerifyResult.Challenged) {
                    exchange.getResponseHeaders().add(
                        "WWW-Authenticate",
                        ((VerifyResult.Challenged) result).challenge().toWwwAuthenticate()
                    );
                    send(exchange, 402, Map.of("error", "payment required"));
                    return;
                }

                VerifyResult.Verified verified = (VerifyResult.Verified) result;
                exchange.getResponseHeaders().set(
                    "Payment-Receipt", verified.receipt().toPaymentReceipt()
                );
                send(exchange, 200, Map.of("ok", true, "message", "relay payment accepted"));
            } catch (PaymentException error) {
                VerifyResult.Challenged retry = (VerifyResult.Challenged) payments.charge(null, charge);
                exchange.getResponseHeaders().add(
                    "WWW-Authenticate", retry.challenge().toWwwAuthenticate()
                );
                exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
                send(exchange, error.getHttpStatus(), error.toProblemDetails());
            }
        });

        server.start();
        System.out.println("Tempo relay example listening on http://127.0.0.1:" + port);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }

    private static void send(HttpExchange exchange, int status, Map<String, Object> body)
        throws IOException {
        byte[] bytes = Json.compact(body).getBytes(StandardCharsets.UTF_8);
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
