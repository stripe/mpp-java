# mpp-java

Java SDK for the [**Machine Payments Protocol**](https://mpp.dev)

> **Note:** This is an experimental SDK and the API is subject to change.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## Usage

### Tempo

The `currency` parameter must be the token contract address — the server verifies it against
the ERC-20 Transfer logs in the transaction receipt. On testnet (Moderato) this is PATH_USD;
on mainnet it is USDC.

```java
// Testnet:  TempoDefaults.TESTNET_PATH_USD = "0x20c0000000000000000000000000000000000000"
// Mainnet:  TempoDefaults.MAINNET_USDC     = "0x20C000000000000000000000b9537d11c60E8b50"
String currency = TempoDefaults.TESTNET_PATH_USD;

TempoMethod tempo = TempoMethod.of().testnet().build();
MppHandler mppHandler = Mpp.create(tempo, "api.example.com", System.getenv("MPP_SECRET_KEY"));

// In your HTTP handler:
VerifyResult result = mppHandler.charge(
    request.getHeader("Authorization"),
    ChargeRequest.of(tempo.chargeIntent(), "0.50", currency, "0xYourWalletAddress")
        .description("Paid endpoint")
);

if (result instanceof VerifyResult.Challenged) {
    response.setHeader("WWW-Authenticate",
        ((VerifyResult.Challenged) result).challenge().toWwwAuthenticate());
    response.setStatus(402);
} else {
    VerifyResult.Verified verified = (VerifyResult.Verified) result;
    response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
    // serve your content
}
```

The endpoint can be tested using the [Tempo CLI](https://mpp.dev):

```sh
tempo request https://your-api.com/your-endpoint
```

### Tempo + Stripe

`networkId` is an arbitrary identifier sent to the client in the Stripe challenge so it knows
which Stripe profile to pay. Use your Stripe profile or network ID (e.g. `STRIPE_PROFILE_ID`).

```java
TempoMethod tempo = TempoMethod.of().testnet().build();
MppHandler tempoHandler = Mpp.create(tempo, "api.example.com", System.getenv("MPP_SECRET_KEY"));

StripeMethod stripe = StripeMethod.of(System.getenv("STRIPE_SECRET_KEY"), System.getenv("STRIPE_PROFILE_ID")).build();
MppHandler stripeHandler = Mpp.create(stripe, "api.example.com", System.getenv("MPP_SECRET_KEY"));

ComposedHandler mppHandler = Mpp.compose(
    tempoHandler.chargeDescriptor(ChargeRequest.of(tempo.chargeIntent(), "0.50", TempoDefaults.TESTNET_PATH_USD, "0xYourWalletAddress")),
    stripeHandler.chargeDescriptor(ChargeRequest.of(stripe.chargeIntent(), "0.50", "usd", System.getenv("STRIPE_PROFILE_ID")))
);

// In your HTTP handler:
VerifyResult result = mppHandler.charge(request.getHeader("Authorization"));

if (result instanceof VerifyResult.Challenged) {
    List<String> headers = Challenge.toWwwAuthenticate(
        ((VerifyResult.Challenged) result).challenges());
    headers.forEach(h -> response.addHeader("WWW-Authenticate", h));
    response.setStatus(402);
} else {
    VerifyResult.Verified verified = (VerifyResult.Verified) result;
    response.setHeader("Payment-Receipt", verified.receipt().toPaymentReceipt());
    // serve your content
}
```

The `402` response includes one `WWW-Authenticate` header per method; the client picks one and retries with the matching credential.
