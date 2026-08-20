package com.stripe.mpp.store;

/**
 * Atomic claim store for replay protection.
 *
 * <p>Production implementations must be durable and shared across processes / servers.
 *
 * <p>Invariant: exactly one caller may see {@code true} for a given {@code key}.
 */
@FunctionalInterface
public interface Store {
    /**
     * Atomically claims {@code key} if no claim already exists.
     *
     * @param key namespaced replay-claim key
     * @return {@code true} for a fresh claim or {@code false} if the key was already claimed
     */
    boolean tryClaim(String key);
}
