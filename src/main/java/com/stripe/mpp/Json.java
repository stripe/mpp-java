package com.stripe.mpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Map;

public class Json {
    // Keys sorted alphabetically for deterministic serialization across runs and languages.
    static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    public static String compact(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new com.stripe.mpp.error.ParseException("Invalid JSON: " + e.getMessage());
        }
    }

    static Object parse(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new com.stripe.mpp.error.ParseException("Invalid JSON: " + e.getMessage());
        }
    }
}
