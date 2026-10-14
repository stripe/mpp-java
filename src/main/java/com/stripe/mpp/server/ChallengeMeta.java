package com.stripe.mpp.server;

import org.bouncycastle.util.encoders.Hex;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opaque challenge metadata helpers for server-issued challenges. */
final class ChallengeMeta {
    private ChallengeMeta() {}

    /** Reserved opaque key for HMAC-bound, per-issuance challenge randomness. */
    static final String NONCE_KEY = "_mppx_nonce";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Adds fresh issuance randomness while preserving application and route metadata. */
    static Map<String, Object> withNonce(Map<String, Object> meta) {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta != null) {
            out.putAll(meta);
        }
        out.put(NONCE_KEY, "0x" + Hex.toHexString(nonce));
        return out;
    }

    /** Removes issuance-only metadata when comparing stable route requirements. */
    static Map<String, Object> routeMeta(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Map<String, Object> rest = new LinkedHashMap<>(meta);
        rest.remove(NONCE_KEY);
        return rest.isEmpty() ? null : rest;
    }
}
