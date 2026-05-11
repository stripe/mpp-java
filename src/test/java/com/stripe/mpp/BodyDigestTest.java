package com.stripe.mpp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BodyDigestTest {

    @Test
    void computeStartsWithAlgorithmPrefix() {
        String digest = BodyDigest.compute("hello world");
        assertThat(digest).startsWith("sha-256=");
    }

    @Test
    void computeIsIdempotent() {
        String d1 = BodyDigest.compute("test body");
        String d2 = BodyDigest.compute("test body");
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    void computeDiffersForDifferentInputs() {
        String d1 = BodyDigest.compute("body one");
        String d2 = BodyDigest.compute("body two");
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void verifyMatchesComputed() {
        String body = "the request body";
        String digest = BodyDigest.compute(body);
        assertThat(BodyDigest.verify(digest, body)).isTrue();
    }

    @Test
    void verifyRejectsWrongDigest() {
        assertThat(BodyDigest.verify("sha-256=AAAA", "body")).isFalse();
    }

    @Test
    void computeWorksForMap() {
        Map<String, Object> body = Map.of("key", "value");
        String digest = BodyDigest.compute(body);
        assertThat(digest).startsWith("sha-256=");
        assertThat(BodyDigest.verify(digest, body)).isTrue();
    }
}
