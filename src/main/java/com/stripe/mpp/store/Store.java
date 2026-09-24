package com.stripe.mpp.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Atomic claim store for replay protection.
 *
 * <p>Production implementations must be durable and shared across processes / servers.
 *
 * <p>At most one caller may claim a key while its claim is active. Claims made without an
 * expiry remain active permanently.
 */
@FunctionalInterface
public interface Store {
    /**
     * Atomically claims {@code key} if no active claim already exists, retaining it permanently.
     *
     * @param key namespaced replay-claim key
     * @return {@code true} for a fresh claim or {@code false} if the key was already claimed
     */
    boolean tryClaim(String key);

    /**
     * Atomically claims {@code key} until an absolute acceptance deadline.
     *
     * <p>The deadline must come from an authenticated challenge bound to the payment proof.
     * Implementations must reject deadlines at or before the current time. Expiring implementations
     * must check the deadline as part of their atomic claim decision, and may reclaim expired claims.
     * A caller must never accept an expired proof merely because its claim has been reclaimed.
     *
     * <p>The default implementation preserves compatibility with existing stores by retaining
     * claims permanently. Override this method to provide atomic deadline checks and expiry cleanup.
     *
     * @param key namespaced replay-claim key
     * @param expiresAt exclusive acceptance deadline
     * @return {@code true} for a fresh claim, or {@code false} for an expired or already claimed key
     */
    default boolean tryClaim(String key, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        return Instant.now().isBefore(expiresAt) && tryClaim(key);
    }
}
