package com.stripe.mpp.consumer;

import com.stripe.mpp.methods.tempo.TempoChargeIntent;
import com.stripe.mpp.methods.tempo.TempoMethod;
import com.stripe.mpp.store.Store;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TempoStorePublicApiTest {

    @Test
    void externalConsumersCanConfigureReplayStorage() {
        Store store = (key, value) -> true;

        TempoChargeIntent intent = new TempoChargeIntent("https://rpc.example.com", store);
        TempoMethod method = TempoMethod.custom("https://rpc.example.com", 1337)
            .store(store)
            .build();

        assertThat(intent).isNotNull();
        assertThat(method.chargeIntent()).isNotNull();
    }
}
