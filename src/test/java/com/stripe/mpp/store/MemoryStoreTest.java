package com.stripe.mpp.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void claimIsRecordedOnceOnly() {
        MemoryStore store = new MemoryStore();
        assertThat(store.tryClaim("key")).isTrue();
        assertThat(store.tryClaim("key")).isFalse();
    }

    @Test
    void deadlineIsExclusiveAndExpiredInputCannotReclaim() {
        MutableClock clock = new MutableClock();
        MemoryStore store = new MemoryStore(clock);
        Instant deadline = START.plusSeconds(10);
        assertThat(store.tryClaim("key", deadline)).isTrue();
        clock.now = deadline.minusNanos(1);
        assertThat(store.tryClaim("key", deadline)).isFalse();
        assertThat(store.tryClaim("before", deadline)).isTrue();
        clock.now = deadline;
        assertThat(store.tryClaim("key", deadline)).isFalse();
        assertThat(store.tryClaim("at", deadline)).isFalse();
        clock.now = deadline.plusNanos(1);
        assertThat(store.tryClaim("after", deadline)).isFalse();
    }

    @Test
    void nextOperationReclaimsUntouchedKeysButKeepsPermanentAndLiveClaims() throws Exception {
        MutableClock clock = new MutableClock();
        MemoryStore store = new MemoryStore(clock);
        for (int i = 0; i < 10_000; i++) {
            assertThat(store.tryClaim("expired:" + i, START.plusSeconds(1))).isTrue();
        }
        store.tryClaim("permanent");
        store.tryClaim("live", START.plusSeconds(20));
        clock.now = START.plusSeconds(1);
        // A permanent operation must also sweep keys that are never requested again.
        assertThat(store.tryClaim("next")).isTrue();
        assertThat(entries(store).keySet()).isEqualTo(java.util.Set.of("permanent", "live", "next"));
        assertThat(queue(store)).hasSize(1);
        assertThat(store.tryClaim("permanent", START.plusSeconds(30))).isFalse();
        assertThat(store.tryClaim("live")).isFalse();
    }

    @Test
    void replacementSurvivesCleanupOfOtherDeadlines() throws Exception {
        MutableClock clock = new MutableClock();
        MemoryStore store = new MemoryStore(clock);
        store.tryClaim("key", START.plusSeconds(1));
        store.tryClaim("other", START.plusSeconds(2));
        clock.now = START.plusSeconds(1);
        assertThat(store.tryClaim("key", START.plusSeconds(10))).isTrue();
        clock.now = START.plusSeconds(2);
        assertThat(store.tryClaim("key", START.plusSeconds(20))).isFalse();
        assertThat(entries(store).keySet()).isEqualTo(java.util.Set.of("key"));
        assertThat(queue(store)).hasSize(1);
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        MemoryStore store = new MemoryStore(new MutableClock());
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return store.tryClaim("key", START.plusSeconds(10));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeIsSampledAfterAcquiringTheClaimLock() throws Exception {
        MutableClock clock = new MutableClock();
        MemoryStore store = new MemoryStore(clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attempting = new CountDownLatch(1);
        Future<Boolean> result;
        try {
            synchronized (store) {
                result = executor.submit(() -> {
                    attempting.countDown();
                    return store.tryClaim("key", START.plusSeconds(1));
                });
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = START.plusSeconds(1);
            }
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(entries(store)).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void legacyLambdaStillWorksAndExpiredDeadlineDoesNotCallIt() {
        AtomicInteger calls = new AtomicInteger();
        Store store = key -> calls.incrementAndGet() == 1;
        assertThat(store.tryClaim("key", Instant.MIN)).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(store.tryClaim("key", Instant.MAX)).isTrue();
        assertThat(store.tryClaim("key", Instant.MAX)).isFalse();
    }

    private static Map<?, ?> entries(MemoryStore store) throws Exception {
        Field field = MemoryStore.class.getDeclaredField("claims");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(store);
    }

    private static Collection<?> queue(MemoryStore store) throws Exception {
        Field field = MemoryStore.class.getDeclaredField("expirations");
        field.setAccessible(true);
        return (Collection<?>) field.get(store);
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
