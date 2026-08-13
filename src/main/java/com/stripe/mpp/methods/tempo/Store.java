package com.stripe.mpp.methods.tempo;

import java.util.Optional;

/**
 * Minimal replay-protection contract for the Tempo charge verifier.
 *
 * <p>Ported from {@code tempoxyz/mpp-go}'s {@code pkg/tempo.Store} so in-memory,
 * Redis, or SQL-backed implementations can all satisfy the same interface.
 * Implementations must not allow a {@link #putIfAbsent} key to become reusable
 * after later expiry — a used payment proof or transaction hash must stay
 * claimed for the life of the store, not just for a TTL window.
 */
interface Store {

    /** Loads a replay-protection value, if present. */
    Optional<String> get(String key);

    /** Stores a replay-protection value, replacing any existing entry. */
    void put(String key, String value);

    /**
     * Atomically stores {@code value} under {@code key} only if the key is
     * currently unused.
     *
     * @return {@code true} if this call claimed the key (it was previously
     *     absent), {@code false} if the key was already claimed.
     */
    boolean putIfAbsent(String key, String value);

    /** Removes a replay-protection value. */
    void delete(String key);
}
