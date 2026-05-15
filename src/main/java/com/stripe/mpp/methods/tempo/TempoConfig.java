package com.stripe.mpp.methods.tempo;

/**
 * Fluent configuration builder for {@link TempoMethod}.
 *
 * <pre>{@code
 * // Testnet with debug logging
 * TempoMethod tempo = TempoConfig.testnet().debug().build();
 *
 * // Mainnet, no extras
 * TempoMethod tempo = TempoConfig.mainnet().build();
 *
 * // Local dev node
 * TempoMethod tempo = TempoConfig.custom("http://localhost:8545", 1337).build();
 * }</pre>
 */
public final class TempoConfig {
    private final String rpcUrl;
    private final int chainId;
    private boolean debug = false;

    private TempoConfig(String rpcUrl, int chainId) {
        this.rpcUrl   = rpcUrl;
        this.chainId  = chainId;
    }

    /** Targets Tempo mainnet (chain 4217). */
    public static TempoConfig mainnet() {
        return new TempoConfig(TempoDefaults.MAINNET_RPC, TempoDefaults.MAINNET_CHAIN_ID);
    }

    /** Targets Tempo testnet / Moderato (chain 42431). */
    public static TempoConfig testnet() {
        return new TempoConfig(TempoDefaults.TESTNET_RPC, TempoDefaults.TESTNET_CHAIN_ID);
    }

    /** Targets a custom RPC endpoint and chain ID — useful for local dev nodes. */
    public static TempoConfig custom(String rpcUrl, int chainId) {
        return new TempoConfig(rpcUrl, chainId);
    }

    /** Enables INFO-level logging of raw JSON-RPC request/response bodies. */
    public TempoConfig debug() {
        this.debug = true;
        return this;
    }

    public TempoMethod build() {
        return new TempoMethod(rpcUrl, chainId, TempoDefaults.DEFAULT_DECIMALS, debug);
    }
}
