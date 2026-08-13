package com.stripe.mpp.methods.tempo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @Test
    void putIfAbsentClaimsAnUnusedKey() {
        MemoryStore store = new MemoryStore();
        assertThat(store.putIfAbsent("k", "v1")).isTrue();
        assertThat(store.get("k")).contains("v1");
    }

    @Test
    void putIfAbsentRejectsAnAlreadyClaimedKey() {
        MemoryStore store = new MemoryStore();
        store.putIfAbsent("k", "v1");

        assertThat(store.putIfAbsent("k", "v2")).isFalse();
        // The original value is preserved — a rejected claim must not overwrite it.
        assertThat(store.get("k")).contains("v1");
    }

    @Test
    void getReturnsEmptyForUnknownKey() {
        MemoryStore store = new MemoryStore();
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void deleteRemovesAClaim() {
        MemoryStore store = new MemoryStore();
        store.putIfAbsent("k", "v1");
        store.delete("k");

        assertThat(store.get("k")).isEmpty();
        // Deleting frees the key for a fresh claim.
        assertThat(store.putIfAbsent("k", "v2")).isTrue();
    }

    @Test
    void putReplacesAnExistingValueUnconditionally() {
        MemoryStore store = new MemoryStore();
        store.put("k", "v1");
        store.put("k", "v2");

        assertThat(store.get("k")).contains("v2");
    }

    @Test
    void putIfAbsentIsAtomicUnderConcurrentClaims() throws InterruptedException {
        // The whole point of putIfAbsent for replay protection is that exactly one
        // of N concurrent claimants wins — simulate a burst of concurrent replay
        // attempts against the same key and assert exactly one succeeds.
        MemoryStore store = new MemoryStore();
        int attempts = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            int attempt = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (store.putIfAbsent("shared-key", "attempt-" + attempt)) {
                    winners.incrementAndGet();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(winners.get()).isEqualTo(1);
    }
}
