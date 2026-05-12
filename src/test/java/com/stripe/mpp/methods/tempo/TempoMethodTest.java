package com.stripe.mpp.methods.tempo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoMethodTest {

    static final TempoMethod METHOD = new TempoMethod("http://rpc.example.com", 1);

    @Test
    void convertsDecimalAmountToAtomicUnits() {
        Map<String, Object> result = METHOD.transformRequest(
            Map.of("amount", "0.010000", "currency", "USDC", "recipient", "0xRecipient")
        );
        assertThat(result.get("amount")).isEqualTo("10000");
    }

    @Test
    void convertsLargerDecimalAmount() {
        Map<String, Object> result = METHOD.transformRequest(
            Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xRecipient")
        );
        assertThat(result.get("amount")).isEqualTo("10000000");
    }

    @Test
    void injectsMethodDetailsChainId() {
        Map<String, Object> result = METHOD.transformRequest(
            Map.of("amount", "1.000000", "currency", "USDC", "recipient", "0xABC")
        );
        assertThat(result.get("currency")).isEqualTo("USDC");
        assertThat(result.get("recipient")).isEqualTo("0xABC");
        assertThat(result).doesNotContainKey("chain");
        assertThat(((Map<?, ?>) result.get("methodDetails")).get("chainId")).isEqualTo(1);
    }

    @Test
    void rejectsAmountWithTooManyDecimalPlaces() {
        assertThatThrownBy(() -> METHOD.transformRequest(
            Map.of("amount", "0.0000001", "currency", "USDC", "recipient", "0xRecipient")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
