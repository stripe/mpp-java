package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.Mpp;
import com.stripe.mpp.server.MppHandler;
import com.stripe.mpp.server.VerifyResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the challenge the sample app (java-mpp-example.onrender.com) issues on /tempo.
 *
 * The deployed app advertised eip155:42431 (testnet) instead of eip155:4217 (mainnet),
 * causing purl to fail with "invalid chain ID" during gas estimation.
 * This test pins the expected mainnet values to catch regressions.
 */
class TempoSampleAppChallengeTest {

    static final String REALM     = "java-mpp-example.onrender.com";
    static final String SECRET    = "test-secret";
    static final String AMOUNT    = "0.010000";
    static final String ASSET     = "0x20c000000000000000000000b9537d11c60e8b50";
    static final String RECIPIENT = "0x0000000000000000000000000000000000000001";

    @Test
    void mainnetChallengeUsesCorrectChainId() {
        TempoMethod tempo = Tempo.method(); // mainnet, no args
        MppHandler mpp = Mpp.create(tempo, REALM, SECRET);

        VerifyResult result = mpp.charge(null, tempo.chargeIntent(), AMOUNT, ASSET, RECIPIENT);

        assertThat(result).isInstanceOf(VerifyResult.Challenged.class);
        Map<String, Object> request = ((VerifyResult.Challenged) result).challenge().request();

        // No top-level "chain" field — matches climate.stripe.dev reference format
        assertThat(request).doesNotContainKey("chain");
        // Canonical integer form read by purl and mppx TypeScript SDK
        assertThat(((Map<?, ?>) request.get("methodDetails")).get("chainId")).isEqualTo(4217);
    }

    @Test
    void mainnetChallengeConvertsAmountToAtomicUnits() {
        TempoMethod tempo = Tempo.method();
        MppHandler mpp = Mpp.create(tempo, REALM, SECRET);

        VerifyResult result = mpp.charge(null, tempo.chargeIntent(), AMOUNT, ASSET, RECIPIENT);

        Map<String, Object> request = ((VerifyResult.Challenged) result).challenge().request();
        assertThat(request.get("amount")).isEqualTo("10000");
    }

    @Test
    void testnetChallengeUsesModerateChainId() {
        TempoMethod tempo = Tempo.method(true); // testnet (Moderato)
        MppHandler mpp = Mpp.create(tempo, REALM, SECRET);

        VerifyResult result = mpp.charge(null, tempo.chargeIntent(), AMOUNT, ASSET, RECIPIENT);

        Map<String, Object> request = ((VerifyResult.Challenged) result).challenge().request();
        assertThat(request).doesNotContainKey("chain");
        assertThat(((Map<?, ?>) request.get("methodDetails")).get("chainId")).isEqualTo(42431);
    }
}
