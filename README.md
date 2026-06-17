# mpp-java

Java SDK for the [**Machine Payments Protocol**](https://mpp.dev)

> **Note:** This is an experimental SDK and the API is subject to change.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## Installation

### Gradle

```groovy
implementation 'com.stripe:mpp-java:0.1.1'
```

### Maven

```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>mpp-java</artifactId>
    <version>0.1.1</version>
</dependency>
```

## Usage

### Testnet

```java
// Token contract address for the currency you accept
String currency = TempoDefaults.TESTNET_PATH_USD;

// Your server's realm — identifies this API in payment challenges
String realm = "api.example.com";

// Secret used to secure payment challenges
// https://mpp.dev/protocol/challenges#challenge-binding
String mppSecret = System.getenv("MPP_SECRET_KEY");

TempoMethod tempo = TempoMethod.of().testnet().build();
MppHandler mppHandler = Mpp.create(tempo, realm, mppSecret);

// In your HTTP handler: verify if MPP payment has been provided
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

Test the endpoint using the [Tempo CLI](https://mpp.dev):

```sh
tempo request https://your-api.com/your-endpoint
```

### Mainnet

```java
// Token contract address for the currency you accept
String currency = TempoDefaults.MAINNET_USDC;

// Your server's realm — identifies this API in payment challenges
String realm = "api.example.com";

// Secret used to secure payment challenges
// https://mpp.dev/protocol/challenges#challenge-binding
String mppSecret = System.getenv("MPP_SECRET_KEY");

TempoMethod tempo = TempoMethod.of().build(); // mainnet is the default
MppHandler mppHandler = Mpp.create(tempo, realm, mppSecret);

// In your HTTP handler: verify if MPP payment has been provided
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

## Releasing

1. Create a release branch and update `VERSION_NAME` in `gradle.properties` and the version references in this README.
2. Open a PR and let CI pass.
3. Merge the PR to `main`.
4. Tag the merge commit and push the tag:
   ```sh
   git checkout main && git pull
   git tag v<version>
   git push origin v<version>
   ```
5. The [Publish workflow](.github/workflows/publish.yml) will automatically build, test across Java 11/17/21, and publish to Maven Central.
6. Verify the artifact appears on [Maven Central](https://central.sonatype.com/artifact/com.stripe/mpp-java).
