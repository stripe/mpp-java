package com.stripe.mpp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * An MPP payment receipt sent by the server in the Payment-Receipt header.
 */
public record Receipt(
    String status,
    Instant timestamp,
    String reference,
    String method,
    String externalId,
    Object extra
) {
    /**
     * Create a success receipt with the current timestamp.
     */
    public static Receipt success(String reference, String method, String externalId) {
        return new Receipt("success", Instant.now(), reference, method, externalId, null);
    }

    public static Receipt success(String reference, String method) {
        return success(reference, method, null);
    }

    public static Receipt success(String reference) {
        return success(reference, "tempo", null);
    }

    /**
     * Parse a Receipt from a Payment-Receipt header value.
     */
    public static Receipt fromPaymentReceipt(String header) {
        return Parsing.parsePaymentReceipt(header);
    }

    /**
     * Serialize to the Payment-Receipt header value.
     */
    public String toPaymentReceipt() {
        return Parsing.formatPaymentReceipt(this);
    }

    String timestampIso8601() {
        return DateTimeFormatter.ISO_INSTANT.format(timestamp);
    }
}
