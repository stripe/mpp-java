package com.stripe.mpp.server;

import java.util.List;

/**
 * A payment method describes how payments are processed (e.g., Stripe, Tempo).
 * Implement this interface to integrate a payment provider.
 */
public interface Method {
    /**
     * The method name used in challenges (e.g., "stripe", "tempo").
     */
    String name();

    /**
     * The intent types this method supports.
     */
    List<Class<? extends Intent>> intents();

    /** Optional: blockchain/network memo field. */
    default String memo() { return null; }

    /** Optional: fee payer address. */
    default String feePayer() { return null; }

    /** Optional: chain identifier. */
    default String chain() { return null; }
}
