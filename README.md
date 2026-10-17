# Payment Gateway Challenge

## Overview

A simplified payment gateway built with Java 17 and Spring Boot for the
[Checkout.com engineering assessment](https://github.com/cko-recruitment/.github/blob/main/profile/README.md).
It validates merchant requests, forwards valid requests to the supplied bank simulator,
and stores definitive Authorized or Declined outcomes in memory. The API exposes
`POST /payments` and `GET /payments/{id}`.

## Quick Start

Requirements: JDK 17 and Docker with Docker Compose for the bank simulator.
The examples and smoke script also use Bash, Python 3, and curl with `--fail-with-body`
support. Gradle is provided through the wrapper; automated tests do not require Docker.

From the repository root:

```bash
./gradlew clean build
docker compose up -d
./gradlew bootRun
```

Wait for the application to start. The gateway listens on `http://localhost:8090`
and the bank simulator on `http://localhost:8080`.
[Swagger UI](http://localhost:8090/swagger-ui/index.html) documents the endpoints.
`bootRun` stays in the foreground; run the examples and smoke script in a second terminal.
Stop the gateway with Ctrl+C and the simulator with `docker compose down` when finished.

| Environment variable | Default | Purpose |
|---|---|---|
| `BANK_BASE_URL` | `http://localhost:8080` | Bank base URL; the client appends `/payments` |
| `BANK_CONNECT_TIMEOUT` | `2s` | Connection timeout |
| `BANK_READ_TIMEOUT` | `2s` | Read timeout |

## API Examples

Use the following synthetic card data only with the simulator. This example chooses
a future expiry year and retains the returned ID for the subsequent GET:

```bash
expiry_year="$(python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).year + 2)')"
payment_response="$(curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "{\"card_number\":\"2222405343240001\",\"expiry_month\":12,\"expiry_year\":$expiry_year,\"currency\":\"GBP\",\"amount\":1050,\"cvv\":\"007\"}" \
  http://localhost:8090/payments)"
printf '%s\n' "$payment_response"
payment_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$payment_response")"
curl --fail-with-body --silent --show-error "http://localhost:8090/payments/$payment_id"
```

`amount` is expressed in minor currency units: `1050` GBP means **£10.50**.
For a request with expiry year 2028, a representative POST response is:

```http
HTTP/1.1 201 Created
Location: /payments/550e8400-e29b-41d4-a716-446655440000
X-Request-ID: 7ac6c963-c974-44be-a5c1-11f315497808
```

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

- **Authorized:** the bank explicitly returned `authorized: true`.
- **Declined:** the bank explicitly returned `authorized: false`.
- **Rejected:** invalid merchant input was rejected before any bank call.

Authorized and Declined both return **201**, because both create a retrievable payment
record. Rejected input returns **400** without a payment ID or stored record.
GET returns **200** with the same stored business fields and ID; it neither calls the
bank nor revalidates a historical payment's expiry. Each independently processed POST
gets a new UUID, including repeated submissions of the same request.

The official simulator uses the final card digit: `1/3/5/7/9` authorizes,
`2/4/6/8` declines, and `0` returns a bank HTTP 503. These are simulator rules,
not card-network validation rules.

## Architecture

```text
Merchant -> PaymentGatewayController -> PaymentGatewayService -> BankClient -> Bank simulator
                                       |
                                       +-> PaymentsRepository (ConcurrentHashMap)
```

- **Controller:** HTTP routing, request validation and response status/headers.
- **Service:** calls the bank, maps definitive outcomes, generates a UUID and stores a
  sanitized `PaymentRecord`; maps stored records to the shared POST/GET `PaymentResponse`.
- **BankClient:** sends `card_number`, `expiry_date` (`MM/yyyy`), `currency`, `amount`
  and `cvv`; checks the bank response and classifies transport/protocol failures.
- **Repository:** stores immutable records containing only the card's last four digits
  alongside the payment result, expiry, currency and amount.

## Validation and Error Handling

All request fields are required; missing values and explicit `null` are rejected.

| Field | Implemented validation |
|---|---|
| `card_number` | String of 14–19 ASCII digits; no Luhn or card-brand check |
| `expiry_month` | JSON integer, 1–12 |
| `expiry_year` | JSON integer, 1000–9999; combined year/month strictly after the current UTC month |
| `currency` | Exactly `USD`, `GBP` or `EUR`; no trimming or case conversion |
| `amount` | Signed 32-bit JSON integer in minor currency units; zero and negative values accepted |
| `cvv` | String of 3–4 ASCII digits; leading zeros preserved |

Strict Jackson configuration rejects numeric PAN/CVV, string amounts, fractional or
scientific-notation amounts, and integer overflow before the bank is called.

| Condition | HTTP | Error `code` |
|---|---:|---|
| Invalid POST input, including malformed JSON | 400 | `INVALID_PAYMENT_REQUEST` |
| Invalid payment UUID syntax | 400 | `INVALID_PAYMENT_ID` |
| Unknown payment ID | 404 | `PAYMENT_NOT_FOUND` |
| Unsupported method | 405 | `METHOD_NOT_ALLOWED` |
| Unsupported content type | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| Invalid/unexpected bank response | 502 | `BANK_RESPONSE_INVALID` |
| Bank HTTP 503 or non-timeout connection failure | 503 | `BANK_UNAVAILABLE` |
| Bank timeout | 504 | `BANK_TIMEOUT` |
| Unexpected application failure | 500 | `INTERNAL_ERROR` |

Only HTTP 200 with an explicit boolean `authorized` produces a payment result.
Upstream failures create no record and are never converted into Declined or Rejected.
Protocol errors retain Spring's standard headers, including `Allow` for HTTP 405.

Example invalid-input response:

```json
{
  "status": "Rejected",
  "code": "INVALID_PAYMENT_REQUEST",
  "message": "Payment request validation failed",
  "request_id": "7ac6c963-c974-44be-a5c1-11f315497808",
  "details": [{"field": "expiry_month", "message": "must be between 1 and 12"}]
}
```

`details` is omitted when safe field-level errors are unavailable. GET, protocol and
upstream errors omit the business `status`. Every request receives a server-generated
`X-Request-ID`, used in error responses and MDC logging, then removed from MDC in `finally`.

## Testing

```bash
./gradlew test
```

- **Controller tests:** raw JSON parsing, validation boundaries, HTTP contracts and
  rejection before calling the mocked BankClient.
- **Service tests:** independent payment IDs, sanitized storage and no record on bank failure.
- **BankClient tests:** outbound wire format, boolean outcomes, invalid responses, HTTP
  errors, connection refusal and a real local HTTP read timeout with one outbound request.
- **Expiry validator test:** December-to-January rollover with a fixed UTC clock.
- **Application-context integration tests:** MockMvc drives the real MVC, validation,
  service, client and repository; the client uses real HTTP to a local bank stub.
  They check POST→GET, invalid-input isolation and captured logs for PAN/CVV leakage.

These tests do not start a gateway network listener. To exercise the running gateway
and the official bank simulator, start both as above, then run in a second terminal:

```bash
./scripts/smoke-test.sh
```

The script checks Authorized/GET, Declined/GET, bank 503 and invalid-input 400.
Set `GATEWAY_URL` if the gateway is not at `http://localhost:8090`.

## Design Decisions and Trade-offs

- **In-memory storage:** the assignment permits a repository test double.
  `ConcurrentHashMap` supports the individual concurrent reads/writes used here, but
  records disappear on restart and are not shared across instances. The bank call and
  local write are not atomic; a crash between them can lose the local result.
- **No automatic retries:** a timeout leaves the bank's authorization outcome uncertain.
  Retrying could duplicate a payment. This implementation returns an infrastructure
  error without inventing a payment outcome; duplicate-request protection is not implemented.
- **Sensitive data:** full PAN and CVV are used for the outbound bank call but excluded
  from stored records and public responses. Application logging uses request metadata,
  timing and controlled error categories rather than payloads or raw exception contents.
  Request DTOs have no value-printing `toString()`. This does not guarantee immediate
  erasure of sensitive strings from JVM memory.
- **Amount:** the assignment requires an integer but specifies no positivity constraint.
  Accepting zero and negative integers is a deliberate exercise-specific interpretation,
  not a recommendation for a real payment product.
- **Expiry:** this implementation interprets “in the future” as strictly after the
  current UTC month, so the current month is rejected. This is an explicit assumption;
  production card-validity rules may differ.
- **Scope:** a concrete BankClient and direct record mapping keep the two-endpoint
  solution small. Extra interfaces, mapping frameworks and a payment state machine
  are not needed for the implemented flow. The 201/error mappings and exact currency
  whitelist are implementation choices rather than additional official requirements.

## Production Considerations

A production system would need durable storage, merchant authentication and resource
authorization, end-to-end idempotency, and recovery/reconciliation of uncertain outcomes.
Its handling of card data would also require a PCI DSS assessment. This submission is
not production-ready and has not been assessed or certified for PCI DSS compliance.
