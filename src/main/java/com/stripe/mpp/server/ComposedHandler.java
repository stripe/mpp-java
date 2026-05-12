package com.stripe.mpp.server;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.Credential;
import com.stripe.mpp.error.ParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles multi-method payment challenges. Create via {@link com.stripe.mpp.Mpp#compose}.
 *
 * <pre>{@code
 * ComposedHandler composed = Mpp.compose(
 *     tempoHandler.chargeDescriptor(tempoIntent, "1.000000", "USDC", "0xRecipient"),
 *     stripeHandler.chargeDescriptor(stripeIntent, "1.00", "usd", null)
 * );
 *
 * VerifyResult result = composed.charge(request.getHeader("Authorization"));
 * if (result instanceof VerifyResult.Challenged) {
 *     List<String> headers = Challenge.toWwwAuthenticate(
 *         ((VerifyResult.Challenged) result).challenges()
 *     );
 *     headers.forEach(h -> response.addHeader("WWW-Authenticate", h));
 * } else {
 *     VerifyResult.Verified verified = (VerifyResult.Verified) result;
 *     response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
 * }
 * }</pre>
 */
public final class ComposedHandler {
    private final List<ChargeDescriptor> descriptors;

    public ComposedHandler(List<ChargeDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            throw new IllegalArgumentException("At least one ChargeDescriptor is required");
        }
        this.descriptors = List.copyOf(descriptors);
    }

    /**
     * Verify the Authorization header against all composed methods, or issue challenges for all.
     *
     * <p>If the credential targets a recognized method, only that method is verified.
     * If verification fails or no credential is present, fresh challenges are issued for every method.
     */
    public VerifyResult charge(String authorization) {
        if (authorization != null) {
            String paymentScheme = Verify.extractPaymentScheme(authorization);
            if (paymentScheme != null) {
                try {
                    Credential credential = Credential.fromAuthorization(paymentScheme);
                    String credMethod = credential.challenge().method();
                    for (ChargeDescriptor d : descriptors) {
                        if (d.handler().method().name().equals(credMethod)) {
                            VerifyResult result = d.handler().charge(
                                authorization, d.intent(), d.amount(), d.currency(),
                                d.recipient(), d.description(), d.meta(), d.expires()
                            );
                            if (result instanceof VerifyResult.Verified) {
                                return result;
                            }
                            break;
                        }
                    }
                } catch (ParseException e) {
                    // fall through to re-challenge
                }
            }
        }

        return new VerifyResult.Challenged(buildChallenges());
    }

    private List<Challenge> buildChallenges() {
        List<Challenge> challenges = new ArrayList<>(descriptors.size());
        for (ChargeDescriptor d : descriptors) {
            challenges.add(Verify.createChallenge(
                d.handler().method().name(), d.intent(), d.handler().buildRequest(d),
                d.handler().realm(), d.handler().secretKey(),
                d.description(), d.meta(), d.expires()
            ));
        }
        return challenges;
    }
}
