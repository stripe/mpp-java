package com.stripe.mpp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeIdTest {

    @Test
    void generateIsDeteministic() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        String id1 = ChallengeId.generate("secret", "example.com", "tempo", "charge", request, null, null, null);
        String id2 = ChallengeId.generate("secret", "example.com", "tempo", "charge", request, null, null, null);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void generateDiffersWithDifferentKey() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        String id1 = ChallengeId.generate("secret1", "example.com", "tempo", "charge", request, null, null, null);
        String id2 = ChallengeId.generate("secret2", "example.com", "tempo", "charge", request, null, null, null);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generateDiffersWithDifferentRealm() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        String id1 = ChallengeId.generate("secret", "realm1.com", "tempo", "charge", request, null, null, null);
        String id2 = ChallengeId.generate("secret", "realm2.com", "tempo", "charge", request, null, null, null);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generateProducesBase64Url() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        String id = ChallengeId.generate("secret", "example.com", "tempo", "charge", request, null, null, null);
        // Base64url uses - and _ instead of + and /, and has no padding (no =)
        assertThat(id).doesNotContain("+", "/", "=");
    }

    @Test
    void b64urlRoundTrip() {
        String original = "Hello, World!";
        String encoded = ChallengeId.b64urlEncode(original);
        byte[] decoded = ChallengeId.b64urlDecode(encoded);
        assertThat(new String(decoded)).isEqualTo(original);
    }

    @Test
    void generateWithExpires() {
        Map<String, Object> request = Map.of("amount", "10.000000", "currency", "USDC", "recipient", "0xABC");
        String withExpires    = ChallengeId.generate("secret", "example.com", "tempo", "charge", request, "2025-01-01T00:00:00Z", null, null);
        String withoutExpires = ChallengeId.generate("secret", "example.com", "tempo", "charge", request, null, null, null);
        assertThat(withExpires).isNotEqualTo(withoutExpires);
    }
}
