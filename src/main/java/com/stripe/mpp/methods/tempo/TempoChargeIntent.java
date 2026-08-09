package com.stripe.mpp.methods.tempo;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.VerificationFailedException;
import com.stripe.mpp.server.Intent;
import com.stripe.mpp.server.ValidationResult;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Server-side intent that verifies Tempo payments.
 *
 * <p>Supports two credential payload shapes produced by the Tempo client SDK:
 * <ul>
 *   <li>{@code "transaction"} — a signed raw EVM transaction (pull flow); the server
 *       broadcasts it via {@code eth_sendRawTransaction} and polls for the receipt.</li>
 *   <li>{@code "hash"} — a transaction hash already broadcast by the client (push flow);
 *       the server polls for the receipt directly.</li>
 * </ul>
 *
 * <pre>{@code
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     Tempo.chargeIntent(),          // or Tempo.chargeIntent(true) for testnet
 *     "10.000000", "USDC", "0xRecipient"
 * );
 * }</pre>
 */
public class TempoChargeIntent implements Intent {
    static final int DEFAULT_MAX_RETRIES = 20;
    static final long DEFAULT_RETRY_DELAY_MS = 500;

    static final String TRANSFER_TOPIC =
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final String TRANSFER_WITH_MEMO_TOPIC =
        "0x57bc7354aa85aed339e000bccffabbc529466af35f0772c8f8ee1145927de7f0";
    private static final String ERC20_TRANSFER_SELECTOR = "a9059cbb";
    private static final int TEMPO_TRANSACTION_TYPE = 0x76;

    private final String rpcUrl;
    private final int maxRetries;
    private final long retryDelayMs;
    private final TempoRpc rpc;

    public TempoChargeIntent(String rpcUrl) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, new TempoRpc());
    }

    TempoChargeIntent(String rpcUrl, TempoRpc rpc) {
        this(rpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS, rpc);
    }

    TempoChargeIntent(String rpcUrl, int maxRetries, long retryDelayMs, TempoRpc rpc) {
        this.rpcUrl = rpcUrl;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.rpc = rpc;
    }

    @Override
    public String name() { return "charge"; }

    @Override
    @SuppressWarnings("unchecked")
    public Receipt verify(Credential credential, Map<String, Object> request) {
        if (!(credential.payload() instanceof Map<?, ?>)) {
            throw new VerificationFailedException("missing or invalid payload");
        }
        Map<String, Object> payload = (Map<String, Object>) credential.payload();

        String type = (String) payload.get("type");
        if ("transaction".equals(type)) {
            // Pull: client signed the tx, server broadcasts it.
            return verifyTransaction((String) payload.get("signature"), request);
        }
        if ("hash".equals(type)) {
            // Push: client already broadcast, server just verifies the receipt.
            return verifyHash((String) payload.get("hash"), request);
        }
        throw new VerificationFailedException("unrecognized payload type: " + type);
    }

    private Receipt verifyTransaction(String rawTx, Map<String, Object> request) {
        validateTransactionPayment(rawTx, request);
        String txHash = rpc.sendRawTransaction(rpcUrl, rawTx);
        return awaitReceipt(txHash, request);
    }

    private Receipt verifyHash(String txHash, Map<String, Object> request) {
        return awaitReceipt(txHash, request);
    }

    private Receipt awaitReceipt(String txHash, Map<String, Object> request) {
        for (int i = 0; i < maxRetries; i++) {
            Map<String, Object> receipt = rpc.getTransactionReceipt(rpcUrl, txHash);
            if (receipt != null) {
                if (!"0x1".equals(receipt.get("status"))) {
                    throw new VerificationFailedException("transaction reverted");
                }
                if (!matchTransferLogs(receipt, request)) {
                    throw new VerificationFailedException(
                        "transaction logs contain no Transfer matching the request currency, recipient, and amount"
                    );
                }
                return Receipt.success(txHash, "tempo");
            }
            if (i < maxRetries - 1) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new VerificationFailedException("transaction receipt timeout");
    }

    /**
     * Returns true if the receipt contains at least one ERC-20 Transfer (or TransferWithMemo)
     * log that matches the request's currency (token contract), recipient, sender, and amount.
     *
     * The request amount must already be in atomic units (i.e. after transformRequest has run).
     */
    @SuppressWarnings("unchecked")
    private boolean matchTransferLogs(Map<String, Object> receipt, Map<String, Object> request) {
        String currency  = (String) request.get("currency");
        String recipient = (String) request.get("recipient");
        String amountStr = (String) request.get("amount");
        String sender    = (String) receipt.get("from");

        if (currency == null || recipient == null || amountStr == null) return false;

        BigInteger expectedAmount;
        try {
            expectedAmount = new BigInteger(amountStr);
        } catch (NumberFormatException e) {
            return false;
        }

        List<Object> logs = (List<Object>) receipt.get("logs");
        if (logs == null) return false;

        for (Object logObj : logs) {
            Map<String, Object> log = (Map<String, Object>) logObj;

            String logAddress = (String) log.get("address");
            if (logAddress == null || !logAddress.equalsIgnoreCase(currency)) continue;

            List<String> topics = (List<String>) log.get("topics");
            if (topics == null || topics.size() < 3) continue;

            String topic0 = topics.get(0);
            boolean isTransfer         = TRANSFER_TOPIC.equalsIgnoreCase(topic0);
            boolean isTransferWithMemo = TRANSFER_WITH_MEMO_TOPIC.equalsIgnoreCase(topic0);
            if (!isTransfer && !isTransferWithMemo) continue;
            if (isTransferWithMemo && topics.size() < 4) continue;

            String fromAddress = "0x" + topics.get(1).substring(topics.get(1).length() - 40);
            String toAddress   = "0x" + topics.get(2).substring(topics.get(2).length() - 40);

            if (!toAddress.equalsIgnoreCase(recipient)) continue;
            if (sender != null && !fromAddress.equalsIgnoreCase(sender)) continue;

            String data = (String) log.get("data");
            if (data == null || data.length() < 66) continue;

            try {
                String dataHex = data.startsWith("0x") || data.startsWith("0X")
                    ? data.substring(2) : data;
                BigInteger logAmount = new BigInteger(dataHex, 16);
                if (logAmount.equals(expectedAmount)) return true;
            } catch (NumberFormatException e) {
                // malformed data field, skip this log
            }
        }

        return false;
    }

    private static void validateTransactionPayment(String rawTx, Map<String, Object> request) {
        String currency  = (String) request.get("currency");
        String recipient = (String) request.get("recipient");
        String amountStr = (String) request.get("amount");

        if (currency == null || recipient == null || amountStr == null) {
            throw new VerificationFailedException("missing transaction payment constraints");
        }

        BigInteger expectedAmount;
        try {
            expectedAmount = new BigInteger(amountStr);
        } catch (NumberFormatException e) {
            throw new VerificationFailedException("invalid transaction amount");
        }

        byte[] bytes = decodeHex(rawTx);
        if (bytes.length < 2 || Byte.toUnsignedInt(bytes[0]) != TEMPO_TRANSACTION_TYPE) {
            throw new VerificationFailedException("invalid Tempo transaction type");
        }

        RlpValue transaction = RlpValue.decode(bytes, 1);
        if (transaction.end != bytes.length || !transaction.isList() || transaction.items().size() != 14) {
            throw new VerificationFailedException("malformed Tempo transaction");
        }
        if (transaction.items().get(13).data().length != 65) {
            throw new VerificationFailedException("malformed Tempo transaction signature");
        }

        RlpValue calls = transaction.items().get(4);
        if (!calls.isList()) {
            throw new VerificationFailedException("malformed Tempo transaction calls");
        }

        String expectedCurrency = normalizeAddress(currency);
        String expectedRecipient = normalizeAddress(recipient);
        for (RlpValue call : calls.items()) {
            if (!call.isList() || call.items().size() < 3) continue;
            String to = normalizeAddress(call.items().get(0).data());
            if (!expectedCurrency.equals(to)) continue;

            byte[] input = call.items().get(2).data();
            if (input.length < 68) continue;
            String selector = Hex.toHexString(Arrays.copyOfRange(input, 0, 4));
            if (!ERC20_TRANSFER_SELECTOR.equals(selector)) continue;

            String actualRecipient = normalizeAddress(Arrays.copyOfRange(input, 16, 36));
            BigInteger actualAmount = new BigInteger(1, Arrays.copyOfRange(input, 36, 68));
            if (expectedRecipient.equals(actualRecipient) && expectedAmount.equals(actualAmount)) {
                return;
            }
        }

        throw new VerificationFailedException(
            "transaction does not contain a Transfer matching the request currency, recipient, and amount"
        );
    }

    private static byte[] decodeHex(String rawTx) {
        if (rawTx == null || !rawTx.startsWith("0x") || rawTx.length() <= 2) {
            throw new VerificationFailedException("missing or invalid transaction signature");
        }
        try {
            return Hex.decodeStrict(rawTx, 2, rawTx.length() - 2);
        } catch (DecoderException e) {
            throw new VerificationFailedException("missing or invalid transaction signature");
        }
    }

    private static String normalizeAddress(String address) {
        if (address == null || !address.startsWith("0x") || address.length() != 42) {
            throw new VerificationFailedException("invalid transaction address");
        }
        return address.toLowerCase();
    }

    private static String normalizeAddress(byte[] bytes) {
        if (bytes.length != 20) {
            throw new VerificationFailedException("invalid transaction address");
        }
        return "0x" + Hex.toHexString(bytes);
    }

    private static final class RlpValue {
        private final byte[] data;
        private final List<RlpValue> items;
        private final int end;

        private RlpValue(byte[] data, List<RlpValue> items, int end) {
            this.data = data;
            this.items = items;
            this.end = end;
        }

        private boolean isList() {
            return items != null;
        }

        private byte[] data() {
            if (data == null) throw new VerificationFailedException("malformed RLP scalar");
            return data;
        }

        private List<RlpValue> items() {
            if (items == null) throw new VerificationFailedException("malformed RLP list");
            return items;
        }

        private static RlpValue decode(byte[] bytes, int offset) {
            if (offset >= bytes.length) throw new VerificationFailedException("truncated RLP");
            int prefix = Byte.toUnsignedInt(bytes[offset]);
            if (prefix < 0x80) {
                return new RlpValue(new byte[] {bytes[offset]}, null, offset + 1);
            }
            if (prefix <= 0xb7) {
                int length = prefix - 0x80;
                int start = offset + 1;
                int end = checkedEnd(bytes, start, length);
                return new RlpValue(Arrays.copyOfRange(bytes, start, end), null, end);
            }
            if (prefix <= 0xbf) {
                int lengthOfLength = prefix - 0xb7;
                int start = offset + 1;
                int length = readLength(bytes, start, lengthOfLength);
                int dataStart = start + lengthOfLength;
                int end = checkedEnd(bytes, dataStart, length);
                return new RlpValue(Arrays.copyOfRange(bytes, dataStart, end), null, end);
            }
            if (prefix <= 0xf7) {
                int length = prefix - 0xc0;
                return decodeList(bytes, offset + 1, length);
            }
            int lengthOfLength = prefix - 0xf7;
            int start = offset + 1;
            int length = readLength(bytes, start, lengthOfLength);
            return decodeList(bytes, start + lengthOfLength, length);
        }

        private static RlpValue decodeList(byte[] bytes, int start, int length) {
            int end = checkedEnd(bytes, start, length);
            List<RlpValue> items = new ArrayList<>();
            int cursor = start;
            while (cursor < end) {
                RlpValue item = decode(bytes, cursor);
                items.add(item);
                cursor = item.end;
            }
            if (cursor != end) throw new VerificationFailedException("malformed RLP list");
            return new RlpValue(null, items, end);
        }

        private static int readLength(byte[] bytes, int start, int lengthOfLength) {
            int end = checkedEnd(bytes, start, lengthOfLength);
            int length = 0;
            for (int i = start; i < end; i++) {
                length = (length << 8) | Byte.toUnsignedInt(bytes[i]);
            }
            return length;
        }

        private static int checkedEnd(byte[] bytes, int start, int length) {
            if (length < 0 || start < 0 || start > bytes.length - length) {
                throw new VerificationFailedException("truncated RLP");
            }
            return start + length;
        }
    }
}

/** Relay-backed charge intent with explicit split validation and broadcast hooks. */
final class TempoRelayChargeIntent extends TempoChargeIntent {
    private final TempoRelay relay;

    TempoRelayChargeIntent(String rpcUrl, TempoRelay relay) {
        // Every rpc-reaching entry point is overridden below, so no TempoRpc is needed.
        super(rpcUrl, null);
        this.relay = relay;
    }

    @Override
    public ValidationResult validate(Credential credential, Map<String, Object> request) {
        return relay.validate(credential);
    }

    @Override
    public Receipt broadcast(Credential credential, Map<String, Object> request) {
        return relay.broadcast(credential);
    }

    @Override
    public Receipt verify(Credential credential, Map<String, Object> request) {
        return relay.verify(credential);
    }
}
