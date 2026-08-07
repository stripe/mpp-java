package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeId;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Json;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentException;
import com.stripe.mpp.error.PaymentExpiredException;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.ValidationResult;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;

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
    private static final String IDEMPOTENCY_KEY_PREFIX = "mpp_java_";
    // Relay error codes safe to forward to payers as failure details; "expired" maps to a
    // typed exception before this set is consulted, and all remaining codes map to a
    // generic failure.
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
    private final URI validateUrl;
    private final URI broadcastUrl;
    private final HttpClient http;

    private TempoRelay(Builder builder) {
        this.apiKey = builder.apiKey;
        URI apiBaseUrl = normalizeBaseUrl(builder.apiBaseUrl);
        this.validateUrl = apiBaseUrl.resolve("v1/mpp/validate");
        this.broadcastUrl = apiBaseUrl.resolve("v1/mpp/broadcast");
        this.http = builder.http != null
            ? builder.http
            : HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Start configuring Tempo API's relay with an API key that has the {@code mpp:write} scope. */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    ValidationResult validate(Credential credential) {
        Map<String, Object> request = challengeRequest(credential);
        post(validateUrl, relayBody(credential, request), null);
        return new ValidationResult(credential, request, Map.of());
    }

    Receipt broadcast(Credential credential) {
        String body = relayBody(credential, challengeRequest(credential));
        return toReceipt(post(broadcastUrl, body, idempotencyKey(credential, body)));
    }

    /** Validate then broadcast, building the relay request body once. */
    Receipt verify(Credential credential) {
        String body = relayBody(credential, challengeRequest(credential));
        post(validateUrl, body, null);
        return toReceipt(post(broadcastUrl, body, idempotencyKey(credential, body)));
    }

    private static String relayBody(Credential credential, Map<String, Object> request) {
        Map<String, Object> input = credential.toEnvelope(request);
        // The relay rejects an empty source; omit it like the reference SDKs do.
        if ("".equals(credential.source())) input.remove("source");
        return Json.compact(input);
    }

    private static Receipt toReceipt(Map<String, Object> response) {
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

    /** POST to the relay and return the parsed response, which is always a success envelope. */
    private Map<String, Object> post(URI url, String body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(url)
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("tempo-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body));
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
        Map<String, Object> parsed;
        try {
            parsed = Json.parseMap(response.body());
        } catch (RuntimeException e) {
            throw failure();
        }
        if (!Boolean.TRUE.equals(parsed.get("success"))) throw failure(parsed);
        return parsed;
    }

    /**
     * The request the relay validates against is always decoded from the credential's own
     * HMAC-bound challenge, never taken from the caller.
     */
    private static Map<String, Object> challengeRequest(Credential credential) {
        try {
            return ChallengeId.b64urlDecodeToMap(credential.challenge().request());
        } catch (RuntimeException e) {
            throw new VerificationFailedException("invalid challenge request");
        }
    }

    @SuppressWarnings("unchecked")
    private static String idempotencyKey(Credential credential, String body) {
        Object payloadValue = credential.payload();
        if (payloadValue instanceof Map<?, ?>) {
            Map<String, Object> payload = (Map<String, Object>) payloadValue;
            Object signature = payload.get("signature");
            if ("transaction".equals(payload.get("type")) && signature instanceof String) {
                String value = (String) signature;
                try {
                    if (value.startsWith("0x") && value.length() > 2) {
                        byte[] transaction = Hex.decodeStrict(value, 2, value.length() - 2);
                        return IDEMPOTENCY_KEY_PREFIX + "0x"
                            + Hex.toHexString(new Keccak.Digest256().digest(transaction));
                    }
                } catch (DecoderException ignored) {
                    // Fall through to the canonical credential hash.
                }
            }
        }

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8));
            return IDEMPOTENCY_KEY_PREFIX + "0x" + Hex.toHexString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static PaymentException failure(Map<String, Object> response) {
        Object errorValue = response.get("error");
        if (!(errorValue instanceof Map<?, ?>)) return failure();
        Object codeValue = ((Map<?, ?>) errorValue).get("code");
        if (!(codeValue instanceof String)) return failure();
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
        private HttpClient http;

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
