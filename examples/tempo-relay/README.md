# Tempo relay charge

This example runs a Java HTTP endpoint that issues a Moderato pathUSD charge, validates the
credential through Tempo API, and asks the relay to finalize it.

```sh
export TEMPO_API_KEY=tempo:sk:...
export MPP_RECIPIENT=0xYourRecipientAddress
export MPP_SECRET_KEY=$(openssl rand -base64 32)
./gradlew runTempoRelayExample
```

The API key stays in the server process and needs the `mpp:write` scope.

| Route | Description |
| --- | --- |
| `GET /api/health` | Free health check |
| `GET /api/photo` | `0.01` pathUSD relay-backed charge |

The paid flow is:

1. The server returns a `tempo/charge` challenge.
2. The payer signs a pull transaction and retries with an MPP credential.
3. The Java SDK calls `POST /v1/mpp/validate`, then `POST /v1/mpp/broadcast`.
4. The relay receipt is returned in `Payment-Receipt`.
