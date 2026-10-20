# Payment Gateway

A Java 17 / Spring Boot implementation of the [Checkout.com Payment Gateway Challenge](https://github.com/cko-recruitment/.github/blob/main/profile/README.md). `POST /payments` validates a card payment and requests bank authorization; `GET /payments/{id}` retrieves the result without exposing the full card number or CVV. Normal bank decisions remain `Authorized` and `Declined`.

The application is a small modular monolith with an in-memory repository. Optional idempotency prevents duplicate authorization for a retained merchant/key pair. Explicit `Unknown` and `InFlight` states extend the challenge for uncertain or unfinished authorization; they do not provide recovery or an exactly-once payment guarantee.

## Run locally

Prerequisites: JDK 17, Docker with Compose and a running daemon. The smoke script also requires Bash, Python 3 and curl. Gradle uses the included wrapper; automated tests run without Docker. Ports 8090 (gateway), 8080 (bank) and 2525 (simulator administration) must be available.

The final implementation is on `feature/payment-gateway-v2`; the repository's default `main` branch contains the starter project.

```bash
git clone --branch feature/payment-gateway-v2 https://github.com/Whalefallg/payment-gateway-challenge-java.git
cd payment-gateway-challenge-java
./gradlew clean build
docker compose up -d
./gradlew bootRun
```

Keep `bootRun` running and use a second terminal for API examples and smoke tests. Swagger UI is at <http://localhost:8090/swagger-ui/index.html>. Stop the gateway with Ctrl-C and the simulator with `docker compose down`.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `BANK_BASE_URL` | `http://localhost:8080` | Bank simulator URL |
| `BANK_CONNECT_TIMEOUT` | `2s` | Connection timeout |
| `BANK_READ_TIMEOUT` | `2s` | Response timeout |
| `PAYMENT_FINGERPRINT_SECRET` | Random per process | HMAC key |

The generated HMAC key remains stable for the lifetime of the in-memory store. A configured secret must contain at least 32 UTF-8 bytes; use a securely generated random value. This length check does not measure entropy. A shared secret alone does not provide shared storage or multi-instance idempotency.

## Create and retrieve a payment

Run these commands in the same Bash or Zsh terminal. The expiry year is generated in UTC so the example stays valid.

```bash
expiry_year=$(python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).year + 2)')
payment_request="{\"card_number\":\"2222405343240001\",\"expiry_month\":12,\"expiry_year\":${expiry_year},\"currency\":\"GBP\",\"amount\":1050,\"cvv\":\"007\"}"
payment_response=$(curl --fail --silent --show-error \
  -H 'Content-Type: application/json' -d "$payment_request" \
  http://localhost:8090/payments)
printf '%s\n' "$payment_response"
payment_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<< "$payment_response")
curl --fail --silent --show-error "http://localhost:8090/payments/$payment_id"
```

The POST returns HTTP 201, `Location: /payments/{id}`, `X-Request-ID`, and a body such as:

```json
{
  "id": "e28c59e0-f9ea-4608-aad7-9651ad261ca5",
  "status": "Authorized",
  "card_number_last_four": "0001",
  "expiry_month": 12,
  "expiry_year": 2030,
  "currency": "GBP",
  "amount": 1050
}
```

GET returns HTTP 200 with the stored payment representation. Authorization is not capture or settlement. The original bank simulator authorizes odd final card digits, declines nonzero even digits, and returns HTTP 503 for a final digit of zero.

## Validation assumptions

All six request fields are required. JSON scalar coercion is disabled: numeric fields must be JSON integers and card number/CVV must be strings.

| Field | Accepted values |
| --- | --- |
| `card_number` | 14–19 ASCII digits |
| `expiry_month` | Integer 1–12 |
| `expiry_year` | Integer 1000–9999; combined year/month must be strictly after the current UTC month |
| `currency` | `USD`, `GBP` or `EUR` |
| `amount` | Signed 32-bit integer in minor currency units |
| `cvv` | 3–4 ASCII digits; leading zeros are preserved |

The current UTC month is rejected. Zero and negative amounts are accepted because the challenge requires an integer but does not specify positivity. Fractional/exponent-form numbers, integer overflow, missing/null fields and incorrect JSON types are rejected. Invalid payments produce HTTP 400 with `Rejected`, without admission or bank I/O.

## Optional idempotency

For a payment the client may retry, supply a nonblank `Idempotency-Key` of at most 255 characters. Keys are scoped by `X-Merchant-ID`, which defaults to `test-merchant` and is only a mock scope, not authentication.

```bash
curl -i -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-order-1' \
  -H 'X-Merchant-ID: demo-merchant' \
  -d "$payment_request" http://localhost:8090/payments
```

Repeating this command returns the same payment without another bank call. Changing payment semantics under the same merchant/key returns HTTP 409. The HMAC-SHA-256 fingerprint covers the operation, full PAN, expiry, currency and amount; **CVV is deliberately excluded**. A matching historical replay is allowed after the card expires, but must still pass structural validation.

Every request without a key creates an independent payment, including repeats after an uncertain outcome. Both keyed and unkeyed requests are admitted before calling the bank. Only the caller receiving `CREATED` from atomic admission may execute the authorization. Keys have no eviction policy and remain retained until process restart.

## Responses and uncertain outcomes

`Unknown` is an additional safety semantic, not a bank decline. A timeout, unavailable bank or invalid bank response leaves an admitted payment with an uncertain outcome. Mapped bank failures return `payment_id` and `Unknown` for both keyed and unkeyed requests, so the result can be queried. There is no automatic retry or recovery.

| Request / result | HTTP | Meaning |
| --- | --- | --- |
| New payment with a valid bank decision | 201 | `Authorized` or `Declined`; includes `Location` |
| Matching keyed replay with `InFlight` | 202 | Admission exists without a recorded outcome; includes `Location` |
| Matching keyed replay with a recorded outcome | 200 | Existing `Authorized`, `Declined` or `Unknown`; includes `Location` |
| GET existing payment | 200 | Stored state, including `InFlight` or `Unknown` |
| Invalid payment | 400 | `Rejected`; no bank call |
| Invalid idempotency key or malformed payment UUID | 400 | Input error |
| Same merchant/key with different payment semantics | 409 | Existing payment is unchanged |
| GET missing payment | 404 | Payment not found |
| Unsupported method / content type | 405 / 415 | Request cannot be handled; 405 includes `Allow` |
| Invalid/unexpected bank response | 502 | `Unknown` with `payment_id` |
| Bank unavailable or connection failure | 503 | `Unknown` with `payment_id` |
| Bank timeout | 504 | `Unknown` with `payment_id` |
| Unexpected internal failure | 500 | Sanitized error; no payment ID in the generic error body |

Application API responses include `X-Request-ID`; error bodies include the matching `request_id`. HTTP 200 for an `Unknown` replay means successful retrieval, not successful authorization. HTTP 202 does not guarantee that an execution worker is still running: local state-write failure can leave `InFlight` indefinitely. After a generic 500, a keyed client can replay to inspect retained state; an unkeyed client may have no payment ID to query. See the [failure boundaries and design decisions](docs/DESIGN_DECISIONS.md).

## Verification

```bash
./gradlew clean build
./gradlew test --rerun-tasks
# With the gateway and simulator running:
./scripts/smoke-test.sh
```

Tests cover strict validation, bank transport and timeout mapping, sanitized responses/logs, historical replay, controlled concurrent admission, independent merchants/keys, and local state-write failures. Integration tests use a local HTTP bank stub and require no external service.

The smoke script checks independent unkeyed requests, authorization/decline retrieval, queryable bank-503 uncertainty, keyed replay/conflict, keyed `Unknown` replay, invalid requests, response headers and sensitive-field exclusion. It uses unique keys and cleans up its temporary files. Set `GATEWAY_URL` to test another gateway URL.

## Deliberate limits

- Payment state and idempotency exist only within one JVM lifetime. There is no persistence, shared store, eviction or crash recovery.
- Bank authorization and local state updates are not one transaction. A bank side effect can occur before a local failure; `InFlight` or `Unknown` may remain indefinitely. No retry, reconciliation or exactly-once guarantee is claimed.
- `X-Merchant-ID` is client-controlled. GET does not enforce ownership. Authentication and authorization are outside this challenge.
- PAN/CVV are transient inputs to the bank request. The repository stores last four digits and an HMAC fingerprint; application logs exclude raw payloads and arbitrary request paths. This is not a claim of PCI compliance or a production vault.

[Design decisions](docs/DESIGN_DECISIONS.md) explain why these boundaries were chosen.
