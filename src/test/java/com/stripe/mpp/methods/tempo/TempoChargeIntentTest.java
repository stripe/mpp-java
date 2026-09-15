package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.store.MemoryStore;
import com.stripe.mpp.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoChargeIntentTest {

    static final String RPC_URL = "https://rpc.example.com";

    static final String TOKEN_CONTRACT = TempoDefaults.TESTNET_PATH_USD;
    static final String SENDER    = "0x1234567890123456789012345678901234567890";
    static final String RECIPIENT = "0xabcdef1234567890abcdef1234567890abcdef12";
    static final long   AMOUNT_ATOMIC = 1_000_000L;
    static final int     CHAIN_ID = TempoDefaults.TESTNET_CHAIN_ID;

    static final Map<String, Object> REQUEST = Map.of(
        "amount", String.valueOf(AMOUNT_ATOMIC),
        "currency", TOKEN_CONTRACT,
        "recipient", RECIPIENT
    );

    static final Map<String, Object> REQUEST_WITH_CHAIN = Map.of(
        "amount", String.valueOf(AMOUNT_ATOMIC),
        "currency", TOKEN_CONTRACT,
        "recipient", RECIPIENT,
        "methodDetails", Map.of("chainId", CHAIN_ID)
    );

    static final ChallengeEcho ECHO = new ChallengeEcho(
        "chal-id", "api.example.com", "tempo", "charge", "e30", "2099-01-01T00:00:00Z", null, null
    );

    static final String BOUND_MEMO = Attribution.encode(ECHO.realm(), ECHO.id());

    static Credential txCredential(String rawTx) {
        return txCredential(rawTx, null);
    }

    static Credential txCredential(String rawTx, String source) {
        return new Credential(ECHO, Map.of("type", "transaction", "signature", rawTx), source);
    }

    static Credential hashCredential(String txHash) {
        return hashCredential(txHash, null);
    }

    static Credential hashCredential(String txHash, String source) {
        return new Credential(ECHO, Map.of("type", "hash", "hash", txHash), source);
    }

    static String didPkh(int chainId, String address) {
        return "did:pkh:eip155:" + chainId + ":" + address;
    }

    /** Bound TransferWithMemo log matching REQUEST and ECHO. */
    static Map<String, Object> successReceipt() {
        return receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, BOUND_MEMO);
    }

    static Map<String, Object> receiptWithLog(String contract, String from, String to, long amount) {
        return receiptWithTopics(
            contract, from, to, amount,
            List.of(TempoChargeIntent.TRANSFER_TOPIC, topic(from), topic(to)),
            from
        );
    }

    static Map<String, Object> receiptWithMemoLog(
        String contract, String from, String to, long amount, String memo
    ) {
        return receiptWithTopics(
            contract, from, to, amount,
            List.of(TempoChargeIntent.TRANSFER_WITH_MEMO_TOPIC, topic(from), topic(to), memo),
            from
        );
    }

    static Map<String, Object> receiptWithTopics(
        String contract, String from, String to, long amount, List<String> topics, String receiptFrom
    ) {
        String amountData = "0x" + String.format("%064x", amount);
        return Map.of(
            "status", "0x1",
            "from", receiptFrom,
            "logs", List.of(Map.of(
                "address", contract,
                "topics", topics,
                "data", amountData
            ))
        );
    }

    static String topic(String address) {
        return "0x000000000000000000000000" + address.substring(2);
    }

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

    static TempoChargeIntent intent(TempoRpc rpc, Store store) {
        return new TempoChargeIntent(RPC_URL, 5, 0, rpc, store);
    }

    @Test
    void pullPaymentBroadcastsAndReturnsReceipt() {
        StubRpc rpc = new StubRpc("0xdeadbeef", successReceipt(), 0);
        Receipt receipt = intent(rpc).verify(txCredential("0xsignedtx"), REQUEST);

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.reference()).isEqualTo("0xdeadbeef");
        assertThat(receipt.method()).isEqualTo("tempo");
    }

    @ParameterizedTest
    @ValueSource(strings = {SENDER, RECIPIENT})
    void transactionAcceptsSourceMatchingTransferSender(String receiptSender) {
        Map<String, Object> receipt = new HashMap<>(successReceipt());
        receipt.put("from", receiptSender);

        Receipt result = intent(new StubRpc("0xdeadbeef", receipt, 0))
            .verify(txCredential("0xsignedtx", didPkh(CHAIN_ID, SENDER)), REQUEST_WITH_CHAIN);

        assertThat(result.reference()).isEqualTo("0xdeadbeef");
    }

    @Test
    void transactionRejectsSourceDifferingFromTransferSender() {
        assertThatThrownBy(() -> intent(new StubRpc("0xdeadbeef", successReceipt(), 0))
            .verify(txCredential("0xsignedtx", didPkh(CHAIN_ID, RECIPIENT)), REQUEST_WITH_CHAIN))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void transactionRejectsInvalidSourceBeforeBroadcast() {
        StubRpc rpc = new StubRpc("0xdeadbeef", successReceipt(), 0) {
            @Override String sendRawTransaction(String rpcUrl, String rawTx) {
                throw new AssertionError("invalid source must not be broadcast");
            }
        };
        for (String source : List.of("not-a-did", didPkh(1, SENDER))) {
            assertThatThrownBy(() -> intent(rpc)
                .verify(txCredential("0xsignedtx", source), REQUEST_WITH_CHAIN))
                .isInstanceOf(VerificationFailedException.class);
        }
    }

    @Test
    void pullPaymentWaitsForReceiptToMine() {
        StubRpc rpc = new StubRpc("0xdeadbeef", successReceipt(), 2);
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
        StubRpc rpc = new StubRpc("0xtx", null, Integer.MAX_VALUE);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void pushPaymentVerifiesExistingHash() {
        StubRpc rpc = new StubRpc(null, successReceipt(), 0);
        Receipt receipt = intent(rpc).verify(hashCredential("0xpushedtx"), REQUEST);

        assertThat(receipt.reference()).isEqualTo("0xpushedtx");
    }

    @Test
    void replayedHashIsRejectedAcrossIntentsSharingAStore() {
        Store store = new MemoryStore();

        Receipt first = intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx"), REQUEST);
        assertThat(first.reference()).isEqualTo("0xpushedtx");

        assertThatThrownBy(() -> intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("already used");
    }

    @Test
    void nonMatchingHashDoesNotConsumeReplayClaim() {
        Store store = new MemoryStore();
        Map<String, Object> nonMatchingReceipt = Map.of(
            "status", "0x1", "from", SENDER, "logs", List.of()
        );

        assertThatThrownBy(() -> intent(new StubRpc(null, nonMatchingReceipt, 0), store)
            .verify(hashCredential("0xunrelated"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");

        Receipt receipt = intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xunrelated"), REQUEST);
        assertThat(receipt.reference()).isEqualTo("0xunrelated");
    }

    @Test
    void pullHashCannotBeReplayedAsPush() {
        Store store = new MemoryStore();

        Receipt first = intent(new StubRpc("0xshared", successReceipt(), 0), store)
            .verify(txCredential("0xsignedtx"), REQUEST);
        assertThat(first.reference()).isEqualTo("0xshared");

        assertThatThrownBy(() -> intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xshared"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("already used");
    }

    @Test
    void unknownPayloadTypeThrows() {
        Credential bad = new Credential(ECHO, Map.of("type", "proof", "proof", "0xsig"), null);

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

    @Test
    void wrongTokenContractThrows() {
        String otherContract = "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead";
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(otherContract, SENDER, RECIPIENT, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongRecipientThrows() {
        String otherRecipient = "0x9999999999999999999999999999999999999999";
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(TOKEN_CONTRACT, SENDER, otherRecipient, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongAmountThrows() {
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC - 1), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongSenderThrows() {
        Map<String, Object> tampered = new HashMap<>(successReceipt());
        tampered.put("from", RECIPIENT);
        StubRpc rpc = new StubRpc("0xtx", tampered, 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void noLogsThrows() {
        Map<String, Object> receipt = Map.of("status", "0x1", "from", SENDER, "logs", List.of());
        StubRpc rpc = new StubRpc("0xtx", receipt, 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xsignedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void pushRejectsPlainTransferWithoutChallengeBoundMemo() {
        StubRpc rpc = new StubRpc(null, receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(rpc).verify(hashCredential("0xstolen"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("memo is not bound to this challenge");
    }

    @Test
    void pushRejectsMemoBoundToADifferentChallenge() {
        String stolenMemo = Attribution.encode(ECHO.realm(), "other-challenge");
        StubRpc rpc = new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, stolenMemo), 0);

        assertThatThrownBy(() -> intent(rpc).verify(hashCredential("0xstolen"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("memo is not bound to this challenge");
    }

    @Test
    void pushRejectsMemoBoundToADifferentRealm() {
        String stolenMemo = Attribution.encode("other.example.com", ECHO.id());
        StubRpc rpc = new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, stolenMemo), 0);

        assertThatThrownBy(() -> intent(rpc).verify(hashCredential("0xstolen"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("memo is not bound to this challenge");
    }

    @Test
    void pushRejectsArbitraryNonMppMemo() {
        String arbitrary = "0x" + String.format("%064x", 42);
        StubRpc rpc = new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, arbitrary), 0);

        assertThatThrownBy(() -> intent(rpc).verify(hashCredential("0xpushedtx"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("memo is not bound to this challenge");
    }

    @Test
    void unboundMemoDoesNotConsumeReplayClaim() {
        Store store = new MemoryStore();
        StubRpc plain = new StubRpc(null, receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(plain, store).verify(hashCredential("0xunrelated"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("memo is not bound");

        Receipt receipt = intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xunrelated"), REQUEST);
        assertThat(receipt.reference()).isEqualTo("0xunrelated");
    }

    @Test
    void pushAcceptsChallengeBoundMemoAlongsideAPlainTransfer() {
        String senderTopic = topic(SENDER);
        String recipientTopic = topic(RECIPIENT);
        String amountData = "0x" + String.format("%064x", AMOUNT_ATOMIC);
        Map<String, Object> receipt = Map.of(
            "status", "0x1",
            "from", SENDER,
            "logs", List.of(
                Map.of(
                    "address", TOKEN_CONTRACT,
                    "topics", List.of(TempoChargeIntent.TRANSFER_TOPIC, senderTopic, recipientTopic),
                    "data", amountData
                ),
                Map.of(
                    "address", TOKEN_CONTRACT,
                    "topics", List.of(TempoChargeIntent.TRANSFER_WITH_MEMO_TOPIC,
                        senderTopic, recipientTopic, BOUND_MEMO),
                    "data", amountData
                )
            )
        );
        Receipt result = intent(new StubRpc(null, receipt, 0)).verify(hashCredential("0xpushedtx"), REQUEST);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void explicitMemoMustMatchExactly() {
        String merchantMemo = "0x" + "ab".repeat(32);
        Map<String, Object> request = new HashMap<>(REQUEST);
        request.put("memo", merchantMemo);

        Receipt result = intent(new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, merchantMemo), 0))
            .verify(hashCredential("0xpushedtx"), request);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void explicitMemoMismatchIsRejected() {
        String merchantMemo = "0x" + "ab".repeat(32);
        String otherMemo = "0x" + "cd".repeat(32);
        Map<String, Object> request = new HashMap<>(REQUEST);
        request.put("memo", merchantMemo);

        assertThatThrownBy(() -> intent(new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, otherMemo), 0))
            .verify(hashCredential("0xpushedtx"), request))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void explicitMemoDoesNotRequireChallengeBinding() {
        String merchantMemo = "0x" + "ab".repeat(32);
        Map<String, Object> request = new HashMap<>(REQUEST);
        request.put("memo", merchantMemo);

        Receipt result = intent(new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, merchantMemo), 0))
            .verify(hashCredential("0xpushedtx"), request);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void explicitMemoInMethodDetailsIsHonored() {
        String merchantMemo = "0x" + "ab".repeat(32);
        Map<String, Object> request = Map.of(
            "amount", String.valueOf(AMOUNT_ATOMIC),
            "currency", TOKEN_CONTRACT,
            "recipient", RECIPIENT,
            "methodDetails", Map.of("chainId", CHAIN_ID, "memo", merchantMemo)
        );

        Receipt result = intent(new StubRpc(null,
            receiptWithMemoLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, merchantMemo), 0))
            .verify(hashCredential("0xpushedtx"), request);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void contractAddressMatchIsCaseInsensitive() {
        Map<String, Object> receipt = receiptWithMemoLog(
            TOKEN_CONTRACT.toUpperCase(), SENDER, RECIPIENT, AMOUNT_ATOMIC, BOUND_MEMO);
        StubRpc rpc = new StubRpc("0xtx", receipt, 0);

        Receipt result = intent(rpc).verify(txCredential("0xsignedtx"), REQUEST);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void parseCredentialSourceAbsentIsNull() {
        assertThat(TempoChargeIntent.parseCredentialSource(null, CHAIN_ID)).isNull();
        assertThat(TempoChargeIntent.parseCredentialSource("", CHAIN_ID)).isNull();
    }

    @Test
    void parseCredentialSourceValidReturnsAddress() {
        assertThat(TempoChargeIntent.parseCredentialSource(didPkh(CHAIN_ID, SENDER), CHAIN_ID))
            .isEqualTo(SENDER);
    }

    @Test
    void parseCredentialSourceChainMismatchRejected() {
        assertThatThrownBy(() ->
            TempoChargeIntent.parseCredentialSource(didPkh(1, SENDER), CHAIN_ID))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Credential source is invalid");
    }

    @Test
    void parseCredentialSourceAcceptsStringChainId() {
        assertThat(TempoChargeIntent.parseCredentialSource(didPkh(CHAIN_ID, SENDER), String.valueOf(CHAIN_ID)))
            .isEqualTo(SENDER);
    }

    @Test
    void parseCredentialSourceRejectsNonNumericChainId() {
        assertThatThrownBy(() ->
            TempoChargeIntent.parseCredentialSource(didPkh(CHAIN_ID, SENDER), "not-a-number"))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Credential source is invalid");
    }

    @Test
    void parseCredentialSourceRejectsMalformedVariants() {
        List<String> malformed = List.of(
            "not-a-valid-did",
            "did:pkh:solana:" + CHAIN_ID + ":" + SENDER,
            "did:pkh:eip155:04217:" + SENDER,
            "did:pkh:eip155:not-a-number:" + SENDER,
            "did:pkh:eip155:" + CHAIN_ID + ":extra:" + SENDER,
            "did:pkh:eip155:" + CHAIN_ID + ":not-an-address"
        );
        for (String source : malformed) {
            assertThatThrownBy(() -> TempoChargeIntent.parseCredentialSource(source, CHAIN_ID))
                .as("case: %s", source)
                .isInstanceOf(VerificationFailedException.class)
                .hasMessageContaining("Credential source is invalid");
        }
    }

    @Test
    void hashAcceptsSourceMatchingTransferSender() {
        Map<String, Object> receipt = receiptWithMemoLog(
            TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, BOUND_MEMO);
        Receipt result = intent(new StubRpc(null, receipt, 0))
            .verify(hashCredential("0xpushedtx", didPkh(CHAIN_ID, SENDER)), REQUEST_WITH_CHAIN);
        assertThat(result.reference()).isEqualTo("0xpushedtx");
    }

    @Test
    void hashAcceptsSourceWhenReceiptSenderDiffers() {
        Map<String, Object> receipt = new HashMap<>(receiptWithMemoLog(
            TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC, BOUND_MEMO));
        receipt.put("from", RECIPIENT);

        Receipt result = intent(new StubRpc(null, receipt, 0))
            .verify(hashCredential("0xpushedtx", didPkh(CHAIN_ID, SENDER)), REQUEST_WITH_CHAIN);
        assertThat(result.reference()).isEqualTo("0xpushedtx");
    }

    @Test
    void hashRejectsSourceDifferingFromTransferSender() {
        Map<String, Object> receipt = receiptWithMemoLog(
            TOKEN_CONTRACT, RECIPIENT, RECIPIENT, AMOUNT_ATOMIC, BOUND_MEMO);

        assertThatThrownBy(() -> intent(new StubRpc(null, receipt, 0))
            .verify(hashCredential("0xpushedtx", didPkh(CHAIN_ID, SENDER)), REQUEST_WITH_CHAIN))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void malformedSourceDoesNotConsumeReplayClaim() {
        Store store = new MemoryStore();

        assertThatThrownBy(() -> intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx", "not-a-did"), REQUEST_WITH_CHAIN))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Credential source is invalid");

        Receipt receipt = intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx"), REQUEST);
        assertThat(receipt.reference()).isEqualTo("0xpushedtx");
    }

    @Test
    void wrongChainSourceDoesNotConsumeReplayClaim() {
        Store store = new MemoryStore();

        assertThatThrownBy(() -> intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx", didPkh(1, SENDER)), REQUEST_WITH_CHAIN))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Credential source is invalid");

        Receipt receipt = intent(new StubRpc(null, successReceipt(), 0), store)
            .verify(hashCredential("0xpushedtx"), REQUEST);
        assertThat(receipt.reference()).isEqualTo("0xpushedtx");
    }
}
