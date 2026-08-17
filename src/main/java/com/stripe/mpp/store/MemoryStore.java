package com.stripe.mpp.store;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link Store} for tests and development.
 *
 * <p>Claims live in memory and are lost on restart. They are only visible to one process, so a
 * multi-instance deployment gets no replay protection from this store. Configure a durable, shared
 * {@link Store} in production.
 */
public final class MemoryStore implements Store {
    private final Set<String> claims = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryClaim(String key) {
        return claims.add(key);
    }
}
