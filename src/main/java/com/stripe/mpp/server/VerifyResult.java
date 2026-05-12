package com.stripe.mpp.server;

import com.stripe.mpp.Challenge;
import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;

import java.util.List;
import java.util.Objects;

/**
 * The result of a payment verification attempt.
 *
 * <pre>{@code
 * VerifyResult result = server.charge(authHeader, chargeIntent, amount, currency, recipient);
 * if (result instanceof VerifyResult.Challenged) {
 *     VerifyResult.Challenged challenged = (VerifyResult.Challenged) result;
 *     response.setHeader("WWW-Authenticate", challenged.challenge().toWwwAuthenticate());
 * } else {
 *     VerifyResult.Verified verified = (VerifyResult.Verified) result;
 *     handleSuccess(verified.receipt());
 * }
 * }</pre>
 */
public interface VerifyResult {
    /**
     * Payment is required — return the challenge(s) in WWW-Authenticate header(s) with HTTP 402.
     *
     * <p>Single-method case: use {@link #challenge()} or {@link #challenges()}.
     * Multi-method case (compose): use {@link #challenges()} and set one header per entry.
     */
    final class Challenged implements VerifyResult {
        private final List<Challenge> challenges;

        public Challenged(Challenge challenge) {
            this.challenges = List.of(challenge);
        }

        public Challenged(List<Challenge> challenges) {
            if (challenges == null || challenges.isEmpty()) {
                throw new IllegalArgumentException("At least one challenge is required");
            }
            this.challenges = List.copyOf(challenges);
        }

        /** The first (or only) challenge. Equivalent to {@code challenges().get(0)}. */
        public Challenge challenge() { return challenges.get(0); }

        /** All challenges — one per payment method. */
        public List<Challenge> challenges() { return challenges; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Challenged)) return false;
            Challenged that = (Challenged) o;
            return Objects.equals(challenges, that.challenges);
        }

        @Override
        public int hashCode() {
            return Objects.hash(challenges);
        }

        @Override
        public String toString() {
            return "Challenged[challenges=" + challenges + "]";
        }
    }

    /**
     * Payment was successfully verified — the receipt can be returned in a Payment-Receipt header.
     */
    final class Verified implements VerifyResult {
        private final Credential credential;
        private final Receipt receipt;

        public Verified(Credential credential, Receipt receipt) {
            this.credential = credential;
            this.receipt = receipt;
        }

        public Credential credential() { return credential; }
        public Receipt receipt() { return receipt; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Verified)) return false;
            Verified verified = (Verified) o;
            return Objects.equals(credential, verified.credential)
                && Objects.equals(receipt, verified.receipt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(credential, receipt);
        }

        @Override
        public String toString() {
            return "Verified[credential=" + credential + ", receipt=" + receipt + "]";
        }
    }
}
