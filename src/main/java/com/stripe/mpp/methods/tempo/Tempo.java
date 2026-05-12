package com.stripe.mpp.methods.tempo;

/**
 * Factory for Tempo payment objects.
 *
 * <pre>{@code
 * TempoMethod tempo = Tempo.method();                  // mainnet
 * TempoMethod tempo = Tempo.method(true);              // testnet (Moderato)
 *
 * MppHandler server = Mpp.create(tempo, "api.example.com", secretKey);
 *
 * // In your HTTP handler:
 * VerifyResult result = server.charge(
 *     request.getHeader("Authorization"),
 *     Tempo.chargeIntent(),
 *     "10.000000", "USDC", "0xRecipient"
 * );
 * }</pre>
 */
public final class Tempo {
    private Tempo() {}

    /** Returns a {@link TempoMethod} configured for Tempo mainnet. */
    public static TempoMethod method() {
        return method(false);
    }

    /**
     * Returns a {@link TempoMethod} configured for Tempo mainnet or testnet (Moderato).
     *
     * @param testnet {@code true} for Moderato testnet, {@code false} for mainnet
     */
    public static TempoMethod method(boolean testnet) {
        return testnet ? TempoMethod.testnet() : TempoMethod.mainnet();
    }

    /** Returns a {@link TempoChargeIntent} that submits payments on Tempo mainnet. */
    public static TempoChargeIntent chargeIntent() {
        return chargeIntent(false);
    }

    /**
     * Returns a {@link TempoChargeIntent} for mainnet or testnet (Moderato).
     *
     * @param testnet {@code true} for Moderato testnet, {@code false} for mainnet
     */
    public static TempoChargeIntent chargeIntent(boolean testnet) {
        return new TempoChargeIntent(testnet ? TempoDefaults.TESTNET_RPC : TempoDefaults.MAINNET_RPC);
    }

    /**
     * Returns a {@link TempoChargeIntent} pointed at a custom RPC URL.
     * Useful for local development nodes or private networks.
     *
     * @param rpcUrl JSON-RPC endpoint (e.g. {@code "http://localhost:8545"})
     */
    public static TempoChargeIntent chargeIntent(String rpcUrl) {
        return new TempoChargeIntent(rpcUrl);
    }

    /**
     * Returns a {@link TempoMethod} pointed at a custom RPC URL and chain ID.
     * Useful for local development nodes or private networks.
     *
     * @param rpcUrl  JSON-RPC endpoint (e.g. {@code "http://localhost:8545"})
     * @param chainId numeric EVM chain ID (e.g. {@code 1337})
     */
    public static TempoMethod method(String rpcUrl, int chainId) {
        return new TempoMethod(rpcUrl, chainId);
    }
}
