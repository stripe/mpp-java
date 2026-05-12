package com.stripe.mpp.server;

import java.util.Map;
import java.util.Objects;

/**
 * A pre-configured charge slot used with {@link ComposedHandler}.
 * Bundles a handler with the charge parameters for one payment method.
 */
public final class ChargeDescriptor {
    private final MppHandler handler;
    private final Intent intent;
    private final String amount;
    private final String currency;
    private final String recipient;
    private final String description;
    private final Map<String, Object> meta;
    private final String expires;

    public ChargeDescriptor(
        MppHandler handler,
        Intent intent,
        String amount,
        String currency,
        String recipient,
        String description,
        Map<String, Object> meta,
        String expires
    ) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.intent = Objects.requireNonNull(intent, "intent");
        this.amount = amount;
        this.currency = currency;
        this.recipient = recipient;
        this.description = description;
        this.meta = meta;
        this.expires = expires;
    }

    public MppHandler handler() { return handler; }
    public Intent intent() { return intent; }
    public String amount() { return amount; }
    public String currency() { return currency; }
    public String recipient() { return recipient; }
    public String description() { return description; }
    public Map<String, Object> meta() { return meta; }
    public String expires() { return expires; }
}
