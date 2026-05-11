# MPP Java SDK POC

Java 11 proof-of-concept SDK for the [Machine Payments Protocol](https://mpp.dev).

This library currently supports:

- Core MPP types: `Challenge`, `ChallengeEcho`, `Credential`, and `Receipt`
- Server-side charge verification with `MppHandler`
- Tempo charge challenges via `Tempo.method()` and `Tempo.chargeIntent()`
- Tempo credential verification for raw transaction payloads (`transaction`) and already-broadcast transaction hashes (`hash`)
- Manual client-side challenge parsing and credential serialization

It does not yet include a high-level automatic client transport like Ruby's
`Mpp::Client::Transport` from the [Go and Ruby SDK announcement](https://mpp.dev/blog/go-and-ruby-sdks).

## Install

The package is configured as a Gradle Java library.

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "io.github.raubrey2014:mpp-java-poc:0.1.0"
}
```

For local development:

```sh
./gradlew test
```

## Charge For A Route

This mirrors the Ruby server flow from the blog post: create an MPP server with a
payment method, call `charge`, then either return a `402` challenge or continue
with the verified credential and receipt.

```java
import com.stripe.mpp.Mpp;
import com.stripe.mpp.methods.tempo.Tempo;
import com.stripe.mpp.methods.tempo.TempoMethod;
import com.stripe.mpp.server.MppHandler;
import com.stripe.mpp.server.VerifyResult;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ServerExample {
    public static void main(String[] args) throws IOException {
        String secretKey = System.getenv("MPP_SECRET_KEY");

        TempoMethod tempo = Tempo.method(true); // Moderato testnet
        MppHandler payment = Mpp.create(tempo, "api.example.com", secretKey);

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/paid", exchange -> {
            VerifyResult result = payment.charge(
                exchange.getRequestHeaders().getFirst("Authorization"),
                tempo.chargeIntent(),
                "0.50",
                "USDC",
                "0x742d35Cc6634c0532925a3b844bC9e7595F8fE00",
                "Paid endpoint",
                null,
                null
            );

            if (result instanceof VerifyResult.Challenged) {
                VerifyResult.Challenged challenged = (VerifyResult.Challenged) result;
                exchange.getResponseHeaders().add(
                    "WWW-Authenticate",
                    challenged.challenge().toWwwAuthenticate()
                );
                exchange.sendResponseHeaders(402, -1);
                exchange.close();
                return;
            }

            VerifyResult.Verified verified = (VerifyResult.Verified) result;
            String body = "{\"data\":\"paid content\",\"payer\":\""
                + verified.credential().source()
                + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add(
                "Payment-Receipt",
                verified.receipt().toPaymentReceipt()
            );
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
```

The first request without a valid `Authorization` header returns:

```http
HTTP/1.1 402 Payment Required
WWW-Authenticate: Payment id="...", realm="api.example.com", method="tempo", intent="charge", request="...", expires="...", description="Paid endpoint"
```

The next request presents a payment credential in `Authorization`. If the
credential is valid and the Tempo transaction verifies, the handler returns a
`VerifyResult.Verified` containing both the credential and receipt.

## Make A Paid Request

The Ruby SDK example uses `Mpp::Client::Transport` to automatically retry after
a `402`. This Java POC exposes the lower-level pieces today: parse the challenge,
build a `Credential`, and send it in a second request.

```java
import com.stripe.mpp.Challenge;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class ClientExample {
    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("https://api.example.com/paid");

        HttpResponse<String> first = http.send(
            HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (first.statusCode() != 402) {
            System.out.println(first.statusCode());
            return;
        }

        List<String> headers = first.headers().allValues("WWW-Authenticate");
        Challenge challenge = Challenge.fromWwwAuthenticate(headers).get(0);

        // For Tempo, payload must contain either:
        // - "transaction": a signed raw EVM transaction for server broadcast, or
        // - "hash": a transaction hash already broadcast by the client.
        Map<String, Object> payload = Map.of(
            "hash",
            "0x..."
        );
        Credential credential = new Credential(challenge.toEcho(), payload, null);

        HttpResponse<String> paid = http.send(
            HttpRequest.newBuilder(uri)
                .header("Authorization", credential.toAuthorization())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        paid.headers().firstValue("Payment-Receipt").ifPresent(header -> {
            Receipt receipt = Receipt.fromPaymentReceipt(header);
            System.out.println(receipt.status());
        });
        System.out.println(paid.statusCode());
    }
}
```

## Tempo Support

Use `Tempo.method()` and `Tempo.chargeIntent()` for mainnet, or pass `true` for
Moderato testnet.

```java
TempoMethod mainnet = Tempo.method();
TempoMethod testnet = Tempo.method(true);
```

`TempoChargeIntent` accepts the credential payload shapes produced by Tempo
clients:

- `{"transaction": "0x..."}` broadcasts the raw signed transaction through the configured Tempo RPC.
- `{"hash": "0x..."}` verifies an already-broadcast transaction receipt.

## Custom Payment Methods

Implement `Method` and `Intent` to add another payment method.

```java
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.Method;

import java.util.List;
import java.util.Map;

class ChargeIntent implements Intent {
    @Override
    public String name() {
        return "charge";
    }

    @Override
    public Receipt verify(Credential credential, Map<String, Object> request) {
        return Receipt.success("payment-reference", "custom");
    }
}

class CustomMethod implements Method {
    @Override
    public String name() {
        return "custom";
    }

    @Override
    public List<Class<? extends Intent>> intents() {
        return List.of(ChargeIntent.class);
    }
}
```
