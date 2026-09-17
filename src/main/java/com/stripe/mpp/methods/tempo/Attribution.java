package com.stripe.mpp.methods.tempo;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * MPP attribution memo encoding for TIP-20 {@code transferWithMemo}.
 *
 * <p>Tempo clients write this
 * 32-byte value so a payment can be bound to a specific challenge. Layout:
 *
 * <pre>
 *  0..3   TAG = keccak256("mpp")[0..3]
 *  4      version (0x01)
 *  5..14  serverId = keccak256(serverId)[0..9]
 *  15..24 clientId = keccak256(clientId)[0..9] or zeros
 *  25..31 nonce    = keccak256(challengeId)[0..6]
 * </pre>
 *
 * <p>The encoding matches the mppx and mpp-rb SDKs so a memo produced by one
 * verifies on the others.
 */
public final class Attribution {
    private static final int VERSION = 0x01;
    private static final byte[] TAG = Arrays.copyOf(keccak256(bytes("mpp")), 4);

    /** First 4 bytes of {@code keccak256("mpp")}, as {@code 0x}-prefixed hex. */
    public static final String TAG_HEX = "0x" + Hex.toHexString(TAG);

    private Attribution() {}

    /**
     * Encodes a 32-byte attribution memo bound to {@code serverId} and {@code challengeId}.
     *
     * @return a {@code 0x}-prefixed 64-character hex string
     */
    public static String encode(String serverId, String challengeId) {
        return encode(serverId, challengeId, null);
    }

    /**
     * Encodes a 32-byte attribution memo, optionally including a client fingerprint.
     */
    public static String encode(String serverId, String challengeId, String clientId) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(challengeId, "challengeId");
        byte[] buf = new byte[32];
        System.arraycopy(TAG, 0, buf, 0, 4);
        buf[4] = VERSION;
        System.arraycopy(fingerprint(serverId), 0, buf, 5, 10);
        if (clientId != null) {
            System.arraycopy(fingerprint(clientId), 0, buf, 15, 10);
        }
        System.arraycopy(challengeNonce(challengeId), 0, buf, 25, 7);
        return "0x" + Hex.toHexString(buf);
    }

    /** Returns {@code true} if {@code memo} has the MPP tag and version byte. */
    public static boolean isMppMemo(String memo) {
        if (memo == null || memo.length() != 66) return false;
        if (!memo.startsWith("0x") && !memo.startsWith("0X")) return false;
        String lower = memo.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(TAG_HEX)) return false;
        try {
            return Integer.parseInt(lower.substring(10, 12), 16) == VERSION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if {@code memo} is an MPP memo whose server fingerprint
     * matches {@code serverId}.
     */
    public static boolean verifyServer(String memo, String serverId) {
        if (serverId == null || !isMppMemo(memo)) return false;
        String memoServer = memo.substring(12, 32).toLowerCase(Locale.ROOT);
        return Hex.toHexString(fingerprint(serverId)).equals(memoServer);
    }

    /**
     * Returns {@code true} if {@code memo} is an MPP memo whose nonce equals
     * {@code keccak256(challengeId)[0..6]}.
     */
    public static boolean verifyChallengeBinding(String memo, String challengeId) {
        if (challengeId == null) return false;
        Decoded decoded = decode(memo);
        if (decoded == null) return false;
        String expected = "0x" + Hex.toHexString(challengeNonce(challengeId));
        return decoded.nonce().equalsIgnoreCase(expected);
    }

    /**
     * Decodes an MPP attribution memo, or {@code null} if it is not one.
     */
    public static Decoded decode(String memo) {
        if (!isMppMemo(memo)) return null;
        String lower = memo.toLowerCase(Locale.ROOT);
        int version = Integer.parseInt(lower.substring(10, 12), 16);
        String serverFingerprint = "0x" + lower.substring(12, 32);
        String clientHex = "0x" + lower.substring(32, 52);
        String nonce = "0x" + lower.substring(52);
        String clientFingerprint = "0x00000000000000000000".equals(clientHex) ? null : clientHex;
        return new Decoded(version, serverFingerprint, clientFingerprint, nonce);
    }

    /** Decoded fields of an MPP attribution memo. */
    public static final class Decoded {
        private final int version;
        private final String serverFingerprint;
        private final String clientFingerprint;
        private final String nonce;

        Decoded(int version, String serverFingerprint, String clientFingerprint, String nonce) {
            this.version = version;
            this.serverFingerprint = serverFingerprint;
            this.clientFingerprint = clientFingerprint;
            this.nonce = nonce;
        }

        public int version() { return version; }
        /** 10-byte server fingerprint ({@code 0x} + 20 hex chars). */
        public String serverFingerprint() { return serverFingerprint; }
        /** 10-byte client fingerprint, or {@code null} if anonymous. */
        public String clientFingerprint() { return clientFingerprint; }
        /** 7-byte challenge-bound nonce ({@code 0x} + 14 hex chars). */
        public String nonce() { return nonce; }
    }

    private static byte[] fingerprint(String value) {
        return Arrays.copyOf(keccak256(bytes(value)), 10);
    }

    private static byte[] challengeNonce(String challengeId) {
        return Arrays.copyOf(keccak256(bytes(challengeId)), 7);
    }

    private static byte[] keccak256(byte[] data) {
        return new Keccak.Digest256().digest(data);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
