package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoChargeIntentTest {

    static final String RPC_URL = "https://rpc.example.com";
    static final Map<String, Object> REQUEST = Map.of(
        "amount", "10.000000", "currency", "USDC", "recipient", "0xRecipient"
    );
    static final ChallengeEcho ECHO = new ChallengeEcho(
        "chal-id", "api.example.com", "tempo", "charge", "e30", "2099-01-01T00:00:00Z", null, null
    );

    static Credential txCredential(String rawTx) {
        return new Credential(ECHO, Map.of("transaction", rawTx), null);
    }

    static Credential hashCredential(String txHash) {
        return new Credential(ECHO, Map.of("hash", txHash), null);
    }

    // --- Stub RPC ---

    static class StubRpc extends TempoRpc {
        private final String txHashOnSend;
        private final Map<String, Object> receipt;
        private final int nullReceiptsBeforeResult;
        private int receiptCalls = 0;

        StubRpc(String txHashOnSend, Map<String, Object> receipt, int nullReceiptsBeforeResult) {
            this.txHashOnSend = txHashOnSend;
            this.receipt = receipt;
            this.nullReceiptsBeforeResult = nullReceiptsBeforeResult;
        }

        @Override String sendRawTransaction(String rpcUrl, String rawTx) { return txHashOnSend; }

        @Override Map<String, Object> getTransactionReceipt(String rpcUrl, String txHash) {
            return receiptCalls++ < nullReceiptsBeforeResult ? null : receipt;
        }
    }

    static TempoChargeIntent intent(TempoRpc rpc) {
        return new TempoChargeIntent(RPC_URL, 5, 0, rpc);
    }

    // --- Tests ---

    @Test
    void pullPaymentBroadcastsAndReturnsReceipt() {
        StubRpc rpc = new StubRpc("0xdeadbeef", Map.of("status", "0x1"), 0);
        Receipt receipt = intent(rpc).verify(txCredential("0xsignedtx"), REQUEST);

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.reference()).isEqualTo("0xdeadbeef");
        assertThat(receipt.method()).isEqualTo("tempo");
    }

    @Test
    void pullPaymentWaitsForReceiptToMine() {
        // Receipt not available immediately — returns null twice, then succeeds.
        StubRpc rpc = new StubRpc("0xdeadbeef", Map.of("status", "0x1"), 2);
        Receipt receipt = intent(rpc).verify(txCredential("0xsignedtx"), REQUEST);

        assertThat(receipt.reference()).isEqualTo("0xdeadbeef");
    }

    @Test
    void revertedTransactionThrows() {
        StubRpc rpc = new StubRpc("0xbadtx", Map.of("status", "0x0"), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("reverted");
    }

    @Test
    void receiptTimeoutThrows() {
        // Always returns null — exhausts all retries.
        StubRpc rpc = new StubRpc("0xtx", null, Integer.MAX_VALUE);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void pushPaymentVerifiesExistingHash() {
        // Client already broadcast — credential carries the tx hash directly.
        StubRpc rpc = new StubRpc(null, Map.of("status", "0x1"), 0);
        Receipt receipt = intent(rpc).verify(hashCredential("0xpushedtx"), REQUEST);

        assertThat(receipt.reference()).isEqualTo("0xpushedtx");
    }

    @Test
    void unknownPayloadTypeThrows() {
        Credential bad = new Credential(ECHO, Map.of("proof", "0xsig"), null);

        assertThatThrownBy(() -> intent(new StubRpc(null, null, 0)).verify(bad, REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("unrecognized payload type");
    }

    @Test
    void missingPayloadThrows() {
        Credential bad = new Credential(ECHO, "not-a-map", null);

        assertThatThrownBy(() -> intent(new StubRpc(null, null, 0)).verify(bad, REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("missing or invalid payload");
    }
}
