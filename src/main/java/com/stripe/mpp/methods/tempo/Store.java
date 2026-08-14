package com.stripe.mpp.methods.tempo;

/**
 * Atomic store used for Tempo replay protection.
 *
 * <p>Production deployments with multiple processes or instances should provide a durable,
 * shared implementation. Replay claims must not expire: removing a successful transaction's
 * claim allows that payment to be reused.
 */
@FunctionalInterface
public interface Store {
    /**
     * Atomically stores {@code value} only when {@code key} is absent.
     *
     * @return {@code true} when the value was inserted, or {@code false} when the key was
     *     already present
     */
    boolean putIfAbsent(String key, String value);
}
