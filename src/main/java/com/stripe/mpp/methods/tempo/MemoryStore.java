package com.stripe.mpp.methods.tempo;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-process replay-protection {@link Store}.
 *
 * <p>Suitable for a single server instance or tests. Multi-instance
 * deployments should supply a shared external {@link Store} (e.g. Redis)
 * instead, so a claim made against one instance is visible to the others —
 * this in-memory store only protects a single process.
 */
final class MemoryStore implements Store {

    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }

    @Override
    public boolean putIfAbsent(String key, String value) {
        // ConcurrentHashMap.putIfAbsent returns the *previous* value (null if
        // the key was absent and this call inserted it), giving the atomic
        // claim semantics Store requires without any external locking.
        return values.putIfAbsent(key, value) == null;
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }
}
