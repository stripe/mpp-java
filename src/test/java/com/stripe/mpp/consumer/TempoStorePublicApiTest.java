package com.stripe.mpp.consumer;

import com.stripe.mpp.methods.tempo.MemoryStore;
import com.stripe.mpp.methods.tempo.Store;
import com.stripe.mpp.methods.tempo.TempoChargeIntent;
import com.stripe.mpp.methods.tempo.TempoMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TempoStorePublicApiTest {

    @Test
    void externalConsumersCanConfigureReplayStorage() {
        Store store = new MemoryStore();

        TempoChargeIntent intent = new TempoChargeIntent("https://rpc.example.com", store);
        TempoMethod method = TempoMethod.custom("https://rpc.example.com", 1337)
            .store(store)
            .build();

        assertThat(intent).isNotNull();
        assertThat(method.chargeIntent()).isNotNull();
    }
}
