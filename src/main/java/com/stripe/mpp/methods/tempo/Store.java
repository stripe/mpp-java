package com.stripe.mpp.methods.tempo;

import java.util.Optional;

/**
 * Atomic key-value store used for Tempo replay protection.
 *
 * <p>Production deployments with multiple processes or instances should provide a durable,
 * shared implementation. Replay claims must not expire: removing a successful transaction's
 * claim allows that payment to be reused.
 */
public interface Store {

    /** Loads a value, if present. */
    Optional<String> get(String key);

    /** Stores a value, replacing any existing entry. */
    void put(String key, String value);

    /**
     * Atomically stores {@code value} only when {@code key} is absent.
     *
     * @return {@code true} when the value was inserted, or {@code false} when the key was
     *     already present
     */
    boolean putIfAbsent(String key, String value);

    /** Removes a value. */
    void delete(String key);
}
