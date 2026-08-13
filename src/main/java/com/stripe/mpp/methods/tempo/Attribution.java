package com.stripe.mpp.methods.tempo;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MPP attribution memo verification for TIP-20 {@code transferWithMemo}.
 *
 * <p>Ported from the canonical binding scheme in {@code wevm/mppx}'s
 * {@code src/tempo/Attribution.ts} (also reimplemented identically in
 * {@code tempoxyz/mpp-go}'s {@code pkg/tempo/attribution.go}) so this SDK
 * verifies the same challenge-bound memo layout the reference client emits.
 *
 * <h2>Byte layout (32 bytes)</h2>
 * <pre>
 * | Offset | Size | Field                                       |
 * |--------|------|---------------------------------------------|
 * | 0..3   | 4    | TAG = keccak256("mpp")[0..3]                 |
 * | 4      | 1    | version (0x01)                               |
 * | 5..14  | 10   | serverFingerprint = keccak256(realm)[0..9]   |
 * | 15..24 | 10   | clientFingerprint = keccak256(clientId)[0..9] or 0s |
 * | 25..31 | 7    | challengeNonce = keccak256(challengeId)[0..6] |
 * </pre>
 *
 * This class is verification-only (the Java SDK is server-side); it does not
 * need an {@code encode} counterpart.
 */
final class Attribution {

    private static final byte[] TAG = keccak256("mpp".getBytes(StandardCharsets.UTF_8));
    private static final byte VERSION = 0x01;

    private static final int TAG_SIZE = 4;
    private static final int VERSION_OFFSET = TAG_SIZE;
    private static final int SERVER_OFFSET = 5;
    private static final int SERVER_SIZE = 10;
    private static final int CHALLENGE_OFFSET = 25;
    private static final int CHALLENGE_SIZE = 7;
    private static final int MEMO_SIZE = 32;

    private Attribution() {}

    /** Returns true if {@code memo} carries the MPP tag and a recognized version. */
    static boolean isMppMemo(byte[] memo) {
        if (memo.length != MEMO_SIZE) return false;
        if (!Arrays.equals(Arrays.copyOfRange(memo, 0, TAG_SIZE), TAG)) return false;
        return memo[VERSION_OFFSET] == VERSION;
    }

    /**
     * Returns true if {@code memo}'s server fingerprint (bytes 5-14) matches
     * {@code keccak256(realm)[0..9]}.
     */
    static boolean verifyServer(byte[] memo, String realm) {
        if (!isMppMemo(memo)) return false;
        byte[] expected = fingerprint(realm);
        byte[] actual = Arrays.copyOfRange(memo, SERVER_OFFSET, SERVER_OFFSET + SERVER_SIZE);
        return Arrays.equals(actual, expected);
    }

    /**
     * Returns true if {@code memo}'s challenge nonce (bytes 25-31) matches
     * {@code keccak256(challengeId)[0..6]}.
     */
    static boolean verifyChallengeBinding(byte[] memo, String challengeId) {
        if (!isMppMemo(memo)) return false;
        if (challengeId == null || challengeId.isEmpty()) return false;
        byte[] expected = challengeNonce(challengeId);
        byte[] actual = Arrays.copyOfRange(memo, CHALLENGE_OFFSET, CHALLENGE_OFFSET + CHALLENGE_SIZE);
        return Arrays.equals(actual, expected);
    }

    /**
     * Returns true if {@code memoHex} decodes to 32 bytes carrying the MPP attribution
     * tag/version — i.e. it claims to be an attribution memo, regardless of whether it's
     * actually bound to any particular challenge. Malformed or non-attribution memos (an
     * application-defined memo unrelated to MPP, for instance) return false.
     */
    static boolean looksLikeAttributionMemo(String memoHex) {
        try {
            return isMppMemo(decodeHex32(memoHex));
        } catch (DecoderException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Convenience: decodes a {@code 0x}-prefixed 32-byte hex memo and checks
     * both server and challenge binding. Returns false (rather than throwing)
     * for any malformed input — an unparseable memo is simply not bound.
     */
    static boolean isBoundToChallenge(String memoHex, String realm, String challengeId) {
        byte[] memo;
        try {
            memo = decodeHex32(memoHex);
        } catch (DecoderException | IllegalArgumentException e) {
            return false;
        }
        return verifyServer(memo, realm) && verifyChallengeBinding(memo, challengeId);
    }

    private static byte[] decodeHex32(String hex) {
        if (hex == null) throw new IllegalArgumentException("null memo");
        String stripped = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        byte[] decoded = Hex.decodeStrict(stripped);
        if (decoded.length != MEMO_SIZE) throw new IllegalArgumentException("memo is not 32 bytes");
        return decoded;
    }

    private static byte[] fingerprint(String value) {
        if (value == null || value.isEmpty()) return new byte[SERVER_SIZE];
        return Arrays.copyOfRange(keccak256(value.getBytes(StandardCharsets.UTF_8)), 0, SERVER_SIZE);
    }

    private static byte[] challengeNonce(String challengeId) {
        return Arrays.copyOfRange(
            keccak256(challengeId.getBytes(StandardCharsets.UTF_8)), 0, CHALLENGE_SIZE);
    }

    private static byte[] keccak256(byte[] input) {
        return new Keccak.Digest256().digest(input);
    }
}
