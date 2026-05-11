package com.stripe.mpp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * An MPP payment receipt sent by the server in the Payment-Receipt header.
 */
public final class Receipt {
    private final String status;
    private final Instant timestamp;
    private final String reference;
    private final String method;
    private final String externalId;
    private final Object extra;

    public Receipt(String status, Instant timestamp, String reference, String method, String externalId, Object extra) {
        this.status = status;
        this.timestamp = timestamp;
        this.reference = reference;
        this.method = method;
        this.externalId = externalId;
        this.extra = extra;
    }

    public String status() { return status; }
    public Instant timestamp() { return timestamp; }
    public String reference() { return reference; }
    public String method() { return method; }
    public String externalId() { return externalId; }
    public Object extra() { return extra; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Receipt)) return false;
        Receipt receipt = (Receipt) o;
        return Objects.equals(status, receipt.status)
            && Objects.equals(timestamp, receipt.timestamp)
            && Objects.equals(reference, receipt.reference)
            && Objects.equals(method, receipt.method)
            && Objects.equals(externalId, receipt.externalId)
            && Objects.equals(extra, receipt.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, timestamp, reference, method, externalId, extra);
    }

    @Override
    public String toString() {
        return "Receipt["
            + "status=" + status
            + ", timestamp=" + timestamp
            + ", reference=" + reference
            + ", method=" + method
            + ", externalId=" + externalId
            + ", extra=" + extra
            + "]";
    }
}
