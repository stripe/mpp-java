package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentException;
import com.stripe.mpp.error.PaymentExpiredException;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.ValidationResult;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Configuration and client for Tempo API's MPP relay. */
public final class TempoRelay {
    public static final URI DEFAULT_API_BASE_URL = URI.create("https://api.tempo.xyz/");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> ERROR_CODES = Set.of(
        "already_used",
        "broadcast_failed",
        "expired",
        "invalid_payment",
        "insufficient_funds",
        "policy_denied",
        "screen_rejected",
        "simulation_failed",
        "temporarily_unavailable",
        "unsupported",
        "unknown"
    );
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
        "already_used",
        "broadcast_failed",
        "invalid_payment",
        "insufficient_funds",
        "simulation_failed",
        "unsupported",
        "temporarily_unavailable"
    );

    private final String apiKey;
    private final URI apiBaseUrl;
    private final HttpClient http;

    private TempoRelay(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiBaseUrl = normalizeBaseUrl(builder.apiBaseUrl);
        this.http = builder.http;
    }

    /** Start configuring Tempo API's relay with an API key that has the {@code mpp:write} scope. */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    ValidationResult validate(Credential credential) {
        Map<String, Object> input = relayInput(credential);
        Map<String, Object> response = post("v1/mpp/validate", input, null);
        if (!Boolean.TRUE.equals(response.get("success"))) throw failure(response);
        return new ValidationResult(
            credential,
            ChallengeId.b64urlDecodeToMap(credential.challenge().request()),
            Map.of()
        );
    }

    Receipt broadcast(Credential credential) {
        Map<String, Object> input = relayInput(credential);
        Map<String, Object> response = post(
            "v1/mpp/broadcast", input, idempotencyKey(credential, input)
        );
        if (!Boolean.TRUE.equals(response.get("success"))) throw failure(response);

        Object value = response.get("receipt");
        if (!(value instanceof Map<?, ?>)) throw failure();
        Map<?, ?> receipt = (Map<?, ?>) value;
        Object method = receipt.get("method");
        Object reference = receipt.get("reference");
        Object timestamp = receipt.get("timestamp");
        Object externalId = receipt.get("externalId");
        if (!"tempo".equals(method) || !(reference instanceof String)
            || !(timestamp instanceof String)
            || (externalId != null && !(externalId instanceof String))) {
            throw failure();
        }

        try {
            return new Receipt(
                "success",
                Instant.parse((String) timestamp),
                (String) reference,
                (String) method,
                (String) externalId,
                null
            );
        } catch (RuntimeException e) {
            throw failure();
        }
    }

    private Map<String, Object> post(
        String path,
        Map<String, Object> input,
        String idempotencyKey
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(apiBaseUrl.resolve(path))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("tempo-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(Json.compact(input)));
        if (idempotencyKey != null) request.header("idempotency-key", idempotencyKey);

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception e) {
            throw failure();
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure();
        try {
            return Json.parseMap(response.body());
        } catch (RuntimeException e) {
            throw failure();
        }
    }

    private static Map<String, Object> relayInput(Credential credential) {
        ChallengeEcho echo = credential.challenge();
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("id", echo.id());
        challenge.put("realm", echo.realm());
        challenge.put("method", echo.method());
        challenge.put("intent", echo.intent());
        challenge.put("request", ChallengeId.b64urlDecodeToMap(echo.request()));
        if (echo.expires() != null) challenge.put("expires", echo.expires());
        if (echo.digest() != null) challenge.put("digest", echo.digest());
        if (echo.opaqueRaw() != null) challenge.put("opaque", echo.opaqueRaw());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("challenge", challenge);
        input.put("payload", credential.payload());
        if (credential.source() != null) input.put("source", credential.source());
        return input;
    }

    @SuppressWarnings("unchecked")
    private static String idempotencyKey(
        Credential credential,
        Map<String, Object> input
    ) {
        Object payloadValue = credential.payload();
        if (payloadValue instanceof Map<?, ?>) {
            Map<String, Object> payload = (Map<String, Object>) payloadValue;
            Object signature = payload.get("signature");
            if ("transaction".equals(payload.get("type")) && signature instanceof String
                && isHex((String) signature)) {
                byte[] hash = Hash.sha3(Numeric.hexStringToByteArray((String) signature));
                return "mppx_" + Numeric.toHexString(hash);
            }
        }

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(Json.compact(input).getBytes(StandardCharsets.UTF_8));
            return "mppx_" + Numeric.toHexString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isHex(String value) {
        if (value == null || !value.startsWith("0x") || value.length() <= 2
            || (value.length() - 2) % 2 != 0) return false;
        for (int i = 2; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static PaymentException failure(Map<String, Object> response) {
        Object errorValue = response.get("error");
        if (!(errorValue instanceof Map<?, ?>)) return failure();
        Object codeValue = ((Map<?, ?>) errorValue).get("code");
        if (!(codeValue instanceof String) || !ERROR_CODES.contains(codeValue)) return failure();
        String code = (String) codeValue;
        if ("expired".equals(code)) return new PaymentExpiredException();
        if (!SAFE_ERROR_CODES.contains(code)) return failure();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        if ("temporarily_unavailable".equals(code)) details.put("retry", "same_credential");
        return new VerificationFailedException(null, details);
    }

    private static VerificationFailedException failure() {
        return new VerificationFailedException();
    }

    private static URI normalizeBaseUrl(URI value) {
        Objects.requireNonNull(value, "apiBaseUrl");
        if (!"http".equalsIgnoreCase(value.getScheme())
            && !"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("Relay API base URL must use HTTP or HTTPS");
        }
        if (value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("Relay API base URL must not include a query or fragment");
        }
        String base = value.toString();
        return URI.create(base.endsWith("/") ? base : base + "/");
    }

    /** Builder for {@link TempoRelay}. */
    public static final class Builder {
        private final String apiKey;
        private URI apiBaseUrl = DEFAULT_API_BASE_URL;
        private HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Tempo API key is required");
            }
            this.apiKey = apiKey;
        }

        /** Override the Tempo API base URL, preserving any path prefix. */
        public Builder apiBaseUrl(URI apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /** Override the HTTP client used for relay calls. */
        public Builder httpClient(HttpClient http) {
            this.http = Objects.requireNonNull(http, "http");
            return this;
        }

        public TempoRelay build() {
            return new TempoRelay(this);
        }
    }
}
