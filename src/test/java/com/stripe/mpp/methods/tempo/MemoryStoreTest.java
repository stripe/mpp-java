package com.stripe.mpp.methods.tempo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @Test
    void putIfAbsentDoesNotReplaceAnExistingValue() {
        MemoryStore store = new MemoryStore();

        assertThat(store.putIfAbsent("key", "first")).isTrue();
        assertThat(store.putIfAbsent("key", "second")).isFalse();
    }

    @Test
    void putIfAbsentAllowsExactlyOneConcurrentClaim() throws InterruptedException {
        MemoryStore store = new MemoryStore();
        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                int attempt = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (store.putIfAbsent("shared", "attempt-" + attempt)) {
                        winners.incrementAndGet();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(winners.get()).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
