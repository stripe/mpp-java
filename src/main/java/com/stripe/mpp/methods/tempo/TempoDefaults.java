package com.stripe.mpp.methods.tempo;

class TempoDefaults {
    static final String MAINNET_RPC      = "https://rpc.tempo.xyz";
    static final String TESTNET_RPC      = "https://rpc.moderato.tempo.xyz";
    static final int    MAINNET_CHAIN_ID = 4217;
    static final int    TESTNET_CHAIN_ID = 42431;
    static final int    DEFAULT_DECIMALS = 6;

    /** USDC contract on Tempo mainnet (chain 4217). Pass as {@code currency} to {@code charge()}. */
    static final String MAINNET_USDC     = "0x20C000000000000000000000b9537d11c60E8b50";
    /** PATH_USD contract on Tempo testnet / Moderato (chain 42431). Pass as {@code currency} to {@code charge()}. */
    static final String TESTNET_PATH_USD = "0x20c0000000000000000000000000000000000000";
}
