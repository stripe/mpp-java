package com.stripe.mpp.store;

import java.util.concurrent.ConcurrentHashMap;

/** Process-local {@link Store} for tests and development. Claims are lost on restart. */
public final class MemoryStore implements Store {
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String key, String value) {
        return values.putIfAbsent(key, value) == null;
    }
}
