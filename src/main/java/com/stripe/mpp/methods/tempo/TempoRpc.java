package com.stripe.mpp.methods.tempo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stripe.mpp.error.VerificationFailedException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class TempoRpc {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicInteger ID_SEQ = new AtomicInteger(1);
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private final HttpClient http;

    TempoRpc() {
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    TempoRpc(HttpClient http) {
        this.http = http;
    }

    String sendRawTransaction(String rpcUrl, String rawTx) {
        Object result = call(rpcUrl, "eth_sendRawTransaction", List.of(rawTx));
        if (!(result instanceof String)) {
            throw new VerificationFailedException("unexpected eth_sendRawTransaction response");
        }
        return (String) result;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> getTransactionReceipt(String rpcUrl, String txHash) {
        Object result = call(rpcUrl, "eth_getTransactionReceipt", List.of(txHash));
        return result == null ? null : (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private Object call(String rpcUrl, String method, List<Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        body.put("id", ID_SEQ.getAndIncrement());

        try {
            String requestBody = JSON.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(TIMEOUT)
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseMap = JSON.readValue(response.body(), Map.class);
            if (responseMap.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
                throw new VerificationFailedException("RPC error: " + error.get("message"));
            }
            return responseMap.get("result");
        } catch (VerificationFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new VerificationFailedException("RPC call failed: " + e.getMessage());
        }
    }
}
