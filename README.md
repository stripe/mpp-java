# MPP Java SDK POC

Java 11 proof-of-concept SDK for the [Machine Payments Protocol](https://mpp.dev).

## Install

```groovy
dependencies {
    implementation "io.github.raubrey2014:mpp-java-poc:0.1.0"
}
```

## Tempo

The `currency` parameter must be the token contract address — the server verifies it against
the ERC-20 Transfer logs in the transaction receipt. On testnet (Moderato) this is PATH_USD;
on mainnet it is USDC.

```java
// Testnet:  TempoDefaults.TESTNET_PATH_USD = "0x20c0000000000000000000000000000000000000"
// Mainnet:  TempoDefaults.MAINNET_USDC     = "0x20C000000000000000000000b9537d11c60E8b50"
String currency = TempoDefaults.TESTNET_PATH_USD;

TempoMethod tempo = Tempo.method(true); // true = Moderato testnet
MppHandler payment = Mpp.create(tempo, "api.example.com", System.getenv("MPP_SECRET_KEY"));

// In your HTTP handler:
VerifyResult result = payment.charge(
    request.getHeader("Authorization"),
    tempo.chargeIntent(),
    "0.50", currency, "0xYourWalletAddress",
    "Paid endpoint", null, null
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

The client presents a Tempo credential in `Authorization`:

```json
{"transaction": "0x..."}
```

or, if already broadcast:

```json
{"hash": "0x..."}
```

## Tempo + Stripe

```java
TempoMethod tempo = Tempo.method(true);
MppHandler tempoHandler = Mpp.create(tempo, "api.example.com", System.getenv("MPP_SECRET_KEY"));

StripeMethod stripe = Stripe.method(System.getenv("STRIPE_SECRET_KEY"), "us-east-1");
MppHandler stripeHandler = Mpp.create(stripe, "api.example.com", System.getenv("MPP_SECRET_KEY"));

ComposedHandler payment = Mpp.compose(
    tempoHandler.chargeDescriptor(tempo.chargeIntent(), "0.50", TempoDefaults.TESTNET_PATH_USD, "0xYourWalletAddress"),
    stripeHandler.chargeDescriptor(stripe.chargeIntent(), "0.50", "usd", null)
);

// In your HTTP handler:
VerifyResult result = payment.charge(request.getHeader("Authorization"));

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
