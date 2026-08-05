package com.stripe.mpp.server;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;
import com.stripe.mpp.error.PaymentException;
import com.stripe.mpp.error.VerificationFailedException;

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
     * Validate a credential without broadcasting or consuming it.
     *
     * <p>This is an advisory pre-check. Implementations must not settle, reserve, or otherwise
     * mutate payment state. Legacy intents that only implement {@link #verify} do not support
     * validation-only calls.
     */
    default ValidationResult validate(Credential credential, Map<String, Object> request)
        throws PaymentException {
        throw new VerificationFailedException(
            name() + " does not support non-mutating credential validation"
        );
    }

    /**
     * Perform the terminal payment operation and return its receipt.
     *
     * <p>Callers accepting payment must validate immediately before invoking this hook. New
     * intents should implement both {@link #validate} and this method, and inherit the default
     * {@link #verify} composition.
     */
    default Receipt broadcast(Credential credential, Map<String, Object> request)
        throws PaymentException {
        throw new VerificationFailedException(
            name() + " does not support credential broadcast"
        );
    }

    /**
     * Validate and broadcast the credential, returning a receipt on success.
     *
     * <p>Existing intents may continue overriding this combined hook. New intents should
     * implement {@link #validate} and {@link #broadcast}; this default re-validates immediately
     * before the terminal operation.
     */
    default Receipt verify(Credential credential, Map<String, Object> request)
        throws PaymentException {
        validate(credential, request);
        return broadcast(credential, request);
    }
}
