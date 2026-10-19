# Payment Gateway V2-Lite

A Java 17 / Spring Boot authorization gateway with one immutable payment record,
merchant-scoped idempotency, atomic in-process execution ownership, and honest handling of
uncertain bank outcomes. It deliberately retains `ConcurrentHashMap` storage.

This is a modular-monolith exercise. It is not a durable or PCI-certified payment system.

## Run

Requirements: JDK 17 and Docker Compose.

```bash
./gradlew clean test
docker compose up -d
./gradlew bootRun
```

The gateway listens on `http://localhost:8090`, the supplied simulator on
`http://localhost:8080`, and Swagger UI is at
`http://localhost:8090/swagger-ui/index.html`.

| Environment variable | Default | Purpose |
|---|---|---|
| `BANK_BASE_URL` | `http://localhost:8080` | Bank base URL |
| `BANK_CONNECT_TIMEOUT` | `2s` | Bank connection timeout |
| `BANK_READ_TIMEOUT` | `2s` | Bank response timeout |
| `PAYMENT_FINGERPRINT_SECRET` | random per process | HMAC secret for instrument identity |

For stable idempotency fingerprints throughout one deployment, configure a random
`PAYMENT_FINGERPRINT_SECRET` containing at least 32 UTF-8 bytes. Short configured secrets fail
application startup. When omitted, the process generates 32 random bytes, which is suitable
only for this non-durable, single-process exercise. Never log or expose the secret, canonical
payment input, or derived fingerprint.

## API

The authentication layer is outside this challenge. `X-Merchant-ID` provides a test-only
merchant scope and defaults to `test-merchant`; it must not be treated as authentication.
Production must derive merchant identity from authenticated credentials, authorize POST access,
and enforce resource ownership on every GET. This API is not production-grade multi-tenant
security.

```bash
expiry_year="$(python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).year + 2)')"
curl --fail-with-body -i http://localhost:8090/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-42' \
  -H 'X-Merchant-ID: demo-merchant' \
  -d "{\"card_number\":\"2222405343240001\",\"expiry_month\":12,\"expiry_year\":$expiry_year,\"currency\":\"GBP\",\"amount\":1050,\"cvv\":\"007\"}"
```

A definitive result returns `201 Created` and a retrievable representation:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "Authorized",
  "card_number_last_four": "0001",
  "expiry_month": 12,
  "expiry_year": 2028,
  "currency": "GBP",
  "amount": 1050
}
```

Replay the same merchant, key, and payment semantics to receive the existing state without
another bank request. Reuse the key with a different amount, currency, expiry, or card and
the gateway returns `409 IDEMPOTENCY_CONFLICT`. Different merchants may reuse the same key.
CVV is deliberately excluded from the fingerprint and therefore cannot be used to detect a
CVV-only change; storing even a derived CVV value would be inappropriate.

POST response status is intentional and covered by tests:

| Situation | HTTP status |
|---|---:|
| Newly admitted request with a returned result | `201 Created` |
| Replay of IN_FLIGHT work | `202 Accepted` |
| Replay of AUTHORIZED, DECLINED, or UNKNOWN | `200 OK` |
| Same key with different semantics | `409 Conflict` |

`202` does not promise background retry or eventual resolution; it only reports that the one
claimed invocation is still in progress. `UNKNOWN` is returned as `200` on replay because no
automatic work remains and the current uncertain state is the retrievable result. Initial mapped
bank failures retain their existing 502/503/504 error codes for compatibility.

```bash
curl http://localhost:8090/payments/550e8400-e29b-41d4-a716-446655440000
```

Every response has a server-generated `X-Request-ID`. The payment ID is a separate stable
business identity. A keyed timeout returns a safe `504` error containing `payment_id` and
`status: Unknown`; GET and replay continue to expose that in-memory state without retrying.
HTTP 503, connection failure, timeout, and invalid/malformed success responses are all treated
as ambiguous after dispatch and become `Unknown`; the simulator cannot prove no side effect.

For backward compatibility, a POST without `Idempotency-Key` retains V1 behavior: every
call is independent, and upstream failures do not create a retrievable record. Such calls
have no cross-request idempotency or UNKNOWN recovery guarantee. New integrations should
always supply an idempotency key.

## States

```text
IN_FLIGHT ──> AUTHORIZED
      ├─────> DECLINED
      └─────> UNKNOWN (no automatic outgoing transition)
```

`AUTHORIZED` means authorization only; capture, settlement, refund, and webhooks are outside
scope. Validation rejection is an API result (`Rejected`), not a bank decline.

## Tests

```bash
./gradlew test
```

The suite covers the original V1 contract, merchant-scoped conflicts, deterministic concurrency,
one execution owner, timeout/invalid/503 handling, UNKNOWN replay, terminal-state protection,
and PAN/CVV exclusion from stored
snapshots, API output, error output, and normal logs.

For a manual simulator check:

```bash
docker compose up -d
./gradlew bootRun
# in another terminal
./scripts/smoke-test.sh
```

## Important limitations

- State and idempotency records disappear on JVM restart.
- Atomicity and execution claims apply to one JVM only; multiple gateway instances are unsafe.
- The bank call and map update cannot be one atomic transaction.
- The simulator has no downstream idempotency key or inquiry API. An `UNKNOWN` payment is
  intentionally not retried and cannot be reconciled automatically.
- Raw PAN and CVV necessarily exist transiently in request objects and the outbound bank call.
  They are never stored in payment aggregates; this is not a PCI vault or certification claim.
- A process-local HMAC identity is a privacy improvement over a plain PAN hash, not tokenization.
- `X-Merchant-ID` is caller-controlled test input. There is no authentication or GET ownership
  enforcement in this challenge implementation.

See [docs/V2_LITE_CONTRACT.md](docs/V2_LITE_CONTRACT.md) for the exact behavior and safety
contract.
