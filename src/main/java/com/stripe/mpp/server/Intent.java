package com.stripe.mpp.server;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentException;

import java.util.Map;

/**
 * A payment intent defines the action being authorized and how to verify it.
 * Implement this interface to create custom payment flows.
 */
public interface Intent {
    /**
     * The intent name used in the challenge (e.g., "charge", "subscribe").
     */
    String name();

    /**
     * Verify the credential against the request and return a receipt on success.
     * Throw a {@link PaymentException} subclass on failure.
     */
    Receipt verify(Credential credential, Map<String, Object> request) throws PaymentException;
}
