package com.stripe.mpp.methods.tempo;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link Store} for tests, development, and single-process deployments.
 *
 * <p>Claims are lost on restart and are not shared with other processes or instances. Use a
 * durable shared {@link Store} in production when replay protection must survive either case.
 */
public final class MemoryStore implements Store {
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String key, String value) {
        return values.putIfAbsent(key, value) == null;
    }
}
