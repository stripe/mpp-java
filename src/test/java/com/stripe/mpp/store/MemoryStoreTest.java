package com.stripe.mpp.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @Test
    void putIfAbsentDoesNotReplaceAnExistingValue() {
        MemoryStore store = new MemoryStore();

        assertThat(store.putIfAbsent("key", "first")).isTrue();
        assertThat(store.putIfAbsent("key", "second")).isFalse();
    }
}
