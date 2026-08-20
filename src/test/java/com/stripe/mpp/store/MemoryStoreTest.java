package com.stripe.mpp.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @Test
    void claimIsRecordedOnceOnly() {
        MemoryStore store = new MemoryStore();

        assertThat(store.tryClaim("key")).isTrue();
        assertThat(store.tryClaim("key")).isFalse();
    }
}
