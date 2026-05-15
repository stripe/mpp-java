package com.stripe.mpp.methods.tempo;

/**
 * Factory for Tempo payment objects.
 *
 * <p>Prefer the named builders on {@link TempoMethod} directly:
 *
 * <pre>{@code
 * TempoMethod tempo = TempoMethod.testnet().build();
 * TempoMethod tempo = TempoMethod.mainnet().build();
 * TempoMethod tempo = TempoMethod.custom("http://localhost:8545", 1337).build();
 * }</pre>
 *
 * <p>The static helpers here are kept for backwards compatibility.
 */
public final class Tempo {
    private Tempo() {}

    /** Returns a {@link TempoMethod} configured for Tempo mainnet. */
    public static TempoMethod method() {
        return TempoMethod.mainnet().build();
    }

    /**
     * Returns a {@link TempoMethod} for mainnet or testnet (Moderato).
     *
     * @param testnet {@code true} for Moderato testnet, {@code false} for mainnet
     */
    public static TempoMethod method(boolean testnet) {
        return (testnet ? TempoMethod.testnet() : TempoMethod.mainnet()).build();
    }

    /**
     * Returns a {@link TempoMethod} pointed at a custom RPC URL and chain ID.
     *
     * @param rpcUrl  JSON-RPC endpoint (e.g. {@code "http://localhost:8545"})
     * @param chainId numeric EVM chain ID (e.g. {@code 1337})
     */
    public static TempoMethod method(String rpcUrl, int chainId) {
        return TempoMethod.custom(rpcUrl, chainId).build();
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
     *
     * @param rpcUrl JSON-RPC endpoint (e.g. {@code "http://localhost:8545"})
     */
    public static TempoChargeIntent chargeIntent(String rpcUrl) {
        return new TempoChargeIntent(rpcUrl);
    }
}
