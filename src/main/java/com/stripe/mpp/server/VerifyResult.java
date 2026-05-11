package com.stripe.mpp.server;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;

/**
 * The result of a payment verification attempt.
 *
 * <pre>{@code
 * VerifyResult result = server.charge(authHeader, chargeIntent, amount, currency, recipient);
 * switch (result) {
 *     case VerifyResult.Challenged c -> response.setHeader("WWW-Authenticate", c.challenge().toWwwAuthenticate());
 *     case VerifyResult.Verified v   -> handleSuccess(v.receipt());
 * }
 * }</pre>
 */
public sealed interface VerifyResult {
    /**
     * Payment is required — return the challenge in a WWW-Authenticate header with HTTP 402.
     */
    record Challenged(Challenge challenge) implements VerifyResult {}

    /**
     * Payment was successfully verified — the receipt can be returned in a Payment-Receipt header.
     */
    record Verified(Credential credential, Receipt receipt) implements VerifyResult {}
}
