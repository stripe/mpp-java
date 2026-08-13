package com.stripe.mpp.methods.tempo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionTest {

    static final String REALM = "api.example.com";
    static final String CHALLENGE_ID = "chal-id";

    @Test
    void boundMemoVerifiesForCorrectRealmAndChallenge() {
        String memo = TempoChargeIntentTest.attributionMemo(REALM, CHALLENGE_ID);
        assertThat(Attribution.looksLikeAttributionMemo(memo)).isTrue();
        assertThat(Attribution.isBoundToChallenge(memo, REALM, CHALLENGE_ID)).isTrue();
    }

    @Test
    void memoBoundToDifferentChallengeFailsBinding() {
        String memo = TempoChargeIntentTest.attributionMemo(REALM, CHALLENGE_ID);
        assertThat(Attribution.isBoundToChallenge(memo, REALM, "different-challenge")).isFalse();
    }

    @Test
    void memoBoundToDifferentRealmFailsBinding() {
        String memo = TempoChargeIntentTest.attributionMemo(REALM, CHALLENGE_ID);
        assertThat(Attribution.isBoundToChallenge(memo, "other.example.com", CHALLENGE_ID)).isFalse();
    }

    @Test
    void arbitraryNonAttributionMemoIsNotFlaggedAsAttribution() {
        // A memo that just happens to be 32 bytes but doesn't carry the MPP tag/version —
        // e.g. an application-defined memo unrelated to MPP attribution at all.
        String memo = "0x" + "2a".repeat(32); // 32 bytes of 0x2a, no MPP tag
        assertThat(Attribution.looksLikeAttributionMemo(memo)).isFalse();
        assertThat(Attribution.isBoundToChallenge(memo, REALM, CHALLENGE_ID)).isFalse();
    }

    @Test
    void malformedMemoDoesNotThrow() {
        assertThat(Attribution.looksLikeAttributionMemo("not-hex-at-all")).isFalse();
        assertThat(Attribution.looksLikeAttributionMemo("0x1234")).isFalse(); // too short
        assertThat(Attribution.looksLikeAttributionMemo(null)).isFalse();
        assertThat(Attribution.isBoundToChallenge("not-hex-at-all", REALM, CHALLENGE_ID)).isFalse();
    }

    @Test
    void emptyChallengeIdNeverBinds() {
        String memo = TempoChargeIntentTest.attributionMemo(REALM, CHALLENGE_ID);
        assertThat(Attribution.isBoundToChallenge(memo, REALM, "")).isFalse();
    }
}
