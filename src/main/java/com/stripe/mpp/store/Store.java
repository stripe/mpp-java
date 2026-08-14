package com.stripe.mpp.store;

/**
 * Atomic store for replay protection.
 *
 * <p>Production implementations must be durable, shared across instances, and retain claims
 * indefinitely.
 */
@FunctionalInterface
public interface Store {
    /**
     * Atomically stores a replay claim when {@code key} is absent.
     *
     * @param key namespaced replay-claim key
     * @param value value associated with the claim
     * @return {@code true} when the value was inserted, or {@code false} when the key was
     *     already present
     */
    boolean putIfAbsent(String key, String value);
}
