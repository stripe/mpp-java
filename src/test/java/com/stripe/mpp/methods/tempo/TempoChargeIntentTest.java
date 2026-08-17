package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.ChallengeEcho;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoChargeIntentTest {

    static final String RPC_URL = "https://rpc.example.com";

    // Realistic addresses used across all tests
    static final String TOKEN_CONTRACT = TempoDefaults.TESTNET_PATH_USD;
    static final String SENDER    = "0x1234567890123456789012345678901234567890";
    static final String RECIPIENT = "0xabcdef1234567890abcdef1234567890abcdef12";
    static final long   AMOUNT_ATOMIC = 1_000_000L; // 1 token with 6 decimals

    // Request as it arrives at verify() — amount already in atomic units, currency = contract address
    static final Map<String, Object> REQUEST = Map.of(
        "amount", String.valueOf(AMOUNT_ATOMIC),
        "currency", TOKEN_CONTRACT,
        "recipient", RECIPIENT
    );

    static final ChallengeEcho ECHO = new ChallengeEcho(
        "chal-id", "api.example.com", "tempo", "charge", "e30", "2099-01-01T00:00:00Z", null, null
    );

    static Credential txCredential(String rawTx) {
        return new Credential(ECHO, Map.of("type", "transaction", "signature", rawTx), null);
    }

    static Credential hashCredential(String txHash) {
        return new Credential(ECHO, Map.of("type", "hash", "hash", txHash), null);
    }

    /** Build a receipt whose Transfer log exactly matches REQUEST. */
    static Map<String, Object> successReceipt() {
        return receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC);
    }

    /** Build a receipt containing one ERC-20 Transfer log with the given parameters. */
    static Map<String, Object> receiptWithLog(String contract, String from, String to, long amount) {
        String senderTopic    = "0x000000000000000000000000" + from.substring(2);
        String recipientTopic = "0x000000000000000000000000" + to.substring(2);
        String amountData     = "0x" + String.format("%064x", amount);
        return Map.of(
            "status", "0x1",
            "from", from,
            "logs", List.of(Map.of(
                "address", contract,
                "topics", List.of(TempoChargeIntent.TRANSFER_TOPIC, senderTopic, recipientTopic),
                "data", amountData
            ))
        );
    }

    // --- Stub RPC ---

    static class StubRpc extends TempoRpc {
        private final String txHashOnSend;
        private final Map<String, Object> receipt;
        private final int nullReceiptsBeforeResult;
        private int sendCalls = 0;
        private int receiptCalls = 0;

        StubRpc(String txHashOnSend, Map<String, Object> receipt, int nullReceiptsBeforeResult) {
            this.txHashOnSend = txHashOnSend;
            this.receipt = receipt;
            this.nullReceiptsBeforeResult = nullReceiptsBeforeResult;
        }

        @Override String sendRawTransaction(String rpcUrl, String rawTx) {
            sendCalls++;
            return txHashOnSend;
        }

        @Override Map<String, Object> getTransactionReceipt(String rpcUrl, String txHash) {
            return receiptCalls++ < nullReceiptsBeforeResult ? null : receipt;
        }
    }

    static TempoChargeIntent intent(TempoRpc rpc) {
        return new TempoChargeIntent(RPC_URL, 5, 0, rpc);
    }

    // --- Existing transport / broadcast tests ---

    @Test
    void pullPaymentBroadcastsAndReturnsReceipt() {
        StubRpc rpc = new StubRpc("0xdeadbeef", successReceipt(), 0);
        Receipt receipt = intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST);

        assertThat(receipt.status()).isEqualTo("success");
        assertThat(receipt.reference()).isEqualTo("0xdeadbeef");
        assertThat(receipt.method()).isEqualTo("tempo");
        assertThat(rpc.sendCalls).isEqualTo(1);
    }

    @Test
    void pullPaymentWaitsForReceiptToMine() {
        StubRpc rpc = new StubRpc("0xdeadbeef", successReceipt(), 2);
        Receipt receipt = intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST);

        assertThat(receipt.reference()).isEqualTo("0xdeadbeef");
    }

    @Test
    void revertedTransactionThrows() {
        StubRpc rpc = new StubRpc("0xbadtx", Map.of("status", "0x0"), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("reverted");
    }

    @Test
    void receiptTimeoutThrows() {
        StubRpc rpc = new StubRpc("0xtx", null, Integer.MAX_VALUE);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void transactionWithWrongRecipientIsRejectedBeforeBroadcast() {
        String otherRecipient = "0x9999999999999999999999999999999999999999";
        StubRpc rpc = new StubRpc("0xtx", successReceipt(), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(otherRecipient, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("transaction does not contain a Transfer");
        assertThat(rpc.sendCalls).isZero();
    }

    @Test
    void transactionWithWrongAmountIsRejectedBeforeBroadcast() {
        StubRpc rpc = new StubRpc("0xtx", successReceipt(), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC - 1)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("transaction does not contain a Transfer");
        assertThat(rpc.sendCalls).isZero();
    }

    @Test
    void transactionWithWrongTokenIsRejectedBeforeBroadcast() {
        String otherContract = "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead";
        StubRpc rpc = new StubRpc("0xtx", successReceipt(), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(otherContract, RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("transaction does not contain a Transfer");
        assertThat(rpc.sendCalls).isZero();
    }

    @Test
    void malformedTransactionIsRejectedBeforeBroadcast() {
        StubRpc rpc = new StubRpc("0xtx", successReceipt(), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential("0xdeadbeef"), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("invalid Tempo transaction type");
        assertThat(rpc.sendCalls).isZero();
    }

    @Test
    void transactionWithoutSignatureIsRejectedBeforeBroadcast() {
        StubRpc rpc = new StubRpc("0xtx", successReceipt(), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC, false)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("malformed Tempo transaction");
        assertThat(rpc.sendCalls).isZero();
    }

    @Test
    void pushPaymentVerifiesExistingHash() {
        StubRpc rpc = new StubRpc(null, successReceipt(), 0);
        Receipt receipt = intent(rpc).verify(hashCredential("0xpushedtx"), REQUEST);

        assertThat(receipt.reference()).isEqualTo("0xpushedtx");
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

    // --- Transfer log validation tests ---

    @Test
    void wrongTokenContractThrows() {
        String otherContract = "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead";
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(otherContract, SENDER, RECIPIENT, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongRecipientThrows() {
        String otherRecipient = "0x9999999999999999999999999999999999999999";
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(TOKEN_CONTRACT, SENDER, otherRecipient, AMOUNT_ATOMIC), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongAmountThrows() {
        StubRpc rpc = new StubRpc("0xtx", receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC - 1), 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void wrongSenderThrows() {
        String otherSender = "0x8888888888888888888888888888888888888888";
        // log.from matches SENDER but receipt.from is otherSender — mismatch
        Map<String, Object> baseReceipt = receiptWithLog(TOKEN_CONTRACT, SENDER, RECIPIENT, AMOUNT_ATOMIC);
        // Rebuild receipt with a different "from" field
        Map<String, Object> tampered = new java.util.HashMap<>(baseReceipt);
        tampered.put("from", otherSender);
        StubRpc rpc2 = new StubRpc("0xtx", tampered, 0);

        assertThatThrownBy(() -> intent(rpc2).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void noLogsThrows() {
        Map<String, Object> receipt = Map.of("status", "0x1", "from", SENDER, "logs", List.of());
        StubRpc rpc = new StubRpc("0xtx", receipt, 0);

        assertThatThrownBy(() -> intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST))
            .isInstanceOf(VerificationFailedException.class)
            .hasMessageContaining("Transfer");
    }

    @Test
    void transferWithMemoTopicAccepted() {
        // TransferWithMemo has amount in data and memo in topics[3]; should still verify.
        String senderTopic    = "0x000000000000000000000000" + SENDER.substring(2);
        String recipientTopic = "0x000000000000000000000000" + RECIPIENT.substring(2);
        String memoTopic      = "0x" + String.format("%064x", 42); // arbitrary memo
        String amountData     = "0x" + String.format("%064x", AMOUNT_ATOMIC);
        Map<String, Object> receipt = Map.of(
            "status", "0x1",
            "from", SENDER,
            "logs", List.of(Map.of(
                "address", TOKEN_CONTRACT,
                "topics", List.of(TempoChargeIntent.TRANSFER_WITH_MEMO_TOPIC,
                    senderTopic, recipientTopic, memoTopic),
                "data", amountData
            ))
        );
        StubRpc rpc = new StubRpc("0xtx", receipt, 0);

        Receipt result = intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST);
        assertThat(result.status()).isEqualTo("success");
    }

    @Test
    void contractAddressMatchIsCaseInsensitive() {
        // Vary the casing of the contract address in the log
        Map<String, Object> receipt = receiptWithLog(TOKEN_CONTRACT.toUpperCase(), SENDER, RECIPIENT, AMOUNT_ATOMIC);
        StubRpc rpc = new StubRpc("0xtx", receipt, 0);

        Receipt result = intent(rpc).verify(txCredential(rawTransferTx(RECIPIENT, AMOUNT_ATOMIC)), REQUEST);
        assertThat(result.status()).isEqualTo("success");
    }

    private static String rawTransferTx(String recipient, long amount) {
        return rawTransferTx(TOKEN_CONTRACT, recipient, amount, true);
    }

    private static String rawTransferTx(String tokenContract, String recipient, long amount) {
        return rawTransferTx(tokenContract, recipient, amount, true);
    }

    private static String rawTransferTx(String recipient, long amount, boolean includeSignature) {
        return rawTransferTx(TOKEN_CONTRACT, recipient, amount, includeSignature);
    }

    private static String rawTransferTx(
        String tokenContract,
        String recipient,
        long amount,
        boolean includeSignature
    ) {
        Function function = new Function(
            "transfer",
            Arrays.asList(new Address(recipient), new Uint256(BigInteger.valueOf(amount))),
            Collections.emptyList()
        );
        byte[] callData = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));
        byte[] toAddress = Numeric.hexStringToByteArray(tokenContract);

        List<RlpType> callItems = new ArrayList<>();
        callItems.add(RlpString.create(toAddress));
        callItems.add(RlpString.create(BigInteger.ZERO));
        callItems.add(RlpString.create(callData));

        List<RlpType> callsList = new ArrayList<>();
        callsList.add(new RlpList(callItems));

        List<RlpType> fields = new ArrayList<>();
        fields.add(RlpString.create(BigInteger.valueOf(6342)));      // chain_id
        fields.add(RlpString.create(BigInteger.ONE));                // max_priority_fee_per_gas
        fields.add(RlpString.create(BigInteger.ONE));                // max_fee_per_gas
        fields.add(RlpString.create(BigInteger.valueOf(5_000_000L)));// gas_limit
        fields.add(new RlpList(callsList));                          // calls
        fields.add(new RlpList(new ArrayList<>()));                  // access_list
        fields.add(RlpString.create(BigInteger.ZERO));               // nonce_key
        fields.add(RlpString.create(BigInteger.ONE));                // nonce
        fields.add(RlpString.create(new byte[]{}));                  // valid_before
        fields.add(RlpString.create(new byte[]{}));                  // valid_after
        fields.add(RlpString.create(new byte[]{}));                  // fee_token
        fields.add(RlpString.create(new byte[]{}));                  // fee_payer_signature
        fields.add(new RlpList(new ArrayList<>()));                  // tempo_auth_list
        if (includeSignature) {
            fields.add(RlpString.create(new byte[65]));              // signature
        }

        byte[] rlpSigned = RlpEncoder.encode(new RlpList(fields));
        byte[] signedTx = new byte[1 + rlpSigned.length];
        signedTx[0] = (byte) 0x76;
        System.arraycopy(rlpSigned, 0, signedTx, 1, rlpSigned.length);
        return Numeric.toHexString(signedTx);
    }
}
