package com.stripe.mpp.store;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Process-local {@link Store} for tests and development.
 *
 * <p>Claims live in memory and are lost on restart. They are only visible to one process, so a
 * multi-instance deployment gets no replay protection from this store. Configure a durable, shared
 * {@link Store} in production.
 *
 * <p>Expired claims are removed on the next claim operation, including claims for untouched keys.
 * No background thread runs; an idle store retains expired entries until the next operation.
 */
public final class MemoryStore implements Store {
    private final Map<String, Claim> claims = new HashMap<>();
    private final PriorityQueue<Claim> expirations =
        new PriorityQueue<>(Comparator.comparing(claim -> claim.expiresAt));
    private final Clock clock;

    public MemoryStore() {
        this(Clock.systemUTC());
    }

    MemoryStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized boolean tryClaim(String key) {
        return claim(key, null);
    }

    @Override
    public synchronized boolean tryClaim(String key, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        return claim(key, expiresAt);
    }

    private boolean claim(String key, Instant expiresAt) {
        Objects.requireNonNull(key, "key");
        Instant now = clock.instant();
        while (!expirations.isEmpty() && !now.isBefore(expirations.peek().expiresAt)) {
            Claim expired = expirations.remove();
            claims.remove(expired.key, expired);
        }
        if ((expiresAt != null && !clock.instant().isBefore(expiresAt)) || claims.containsKey(key)) {
            return false;
        }
        Claim claim = new Claim(key, expiresAt);
        claims.put(key, claim);
        if (expiresAt != null) expirations.add(claim);
        return true;
    }

    private static final class Claim {
        final String key;
        final Instant expiresAt;

        Claim(String key, Instant expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
        }
    }
}
