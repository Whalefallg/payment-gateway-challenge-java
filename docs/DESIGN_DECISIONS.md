# Design decisions

The [README](../README.md) describes the API, setup and validation contract. This document records the reasoning and failure boundaries behind the implementation.

## Why an in-memory repository?

The challenge permits a storage test double. Two maps are sufficient for payment lookup and merchant/key lookup. Immutable `PaymentRecord` snapshots avoid shared mutable payment objects. A short admission lock publishes the payment and key index together; per-payment `compute` operations protect conditional state transitions. Bank I/O runs outside the admission lock.

There is no eviction or durable storage. All records, keys and the generated fingerprint secret share one process lifetime. `clear()` is a test utility, not an online administrative operation. Multi-instance coordination and restart recovery would require a different storage boundary and are deliberately excluded.

## Why optional idempotency and one admission path?

The original payment request does not require an idempotency header. Requests without a key remain independent, including identical repeats. They still create an `InFlight` record before bank I/O, allowing mapped bank failures to return a queryable payment ID.

For keyed requests, a preliminary lookup allows replay of an expired card's previously accepted payment without requiring it to be valid today. This lookup does not grant execution ownership. If two lookups both miss, atomic `admitAndClaim()` returns `CREATED` to only one caller; the other receives an existing payment or a conflict. Only `CREATED` reaches the single bank-call site.

The fingerprint uses HMAC-SHA-256 rather than an unkeyed digest, so stored fingerprints alone cannot be used to test candidate PANs without the secret. It includes the full PAN, not merely the last four digits. CVV is excluded because it is authorization evidence, not payment identity; changing CVV alone must not cause another authorization. Replay still validates request structure.

## Why Unknown, and why no automatic retry?

A failed transport does not reliably indicate whether the bank executed the request. The simulator provides neither an idempotent bank-operation contract nor an inquiry endpoint. Treating uncertainty as `Declined`, or retrying automatically, could cause an unsafe second authorization. `Unknown` makes this uncertainty explicit while preserving normal `Authorized`/`Declined` responses.

The same conservative mapping applies to a bank 503, malformed response and connection failure. Some individual failures may imply that no request was sent, but this implementation does not claim a reliable no-side-effect classification or build a separate retry policy around it.

There is no recovery worker or reconciliation API. Adding one without a reliable bank inquiry/idempotency contract would create complexity without resolving the underlying ambiguity.

## Failure boundaries

| Failure | Local behavior | Client behavior |
| --- | --- | --- |
| Mapped bank failure | Attempt to record `Unknown` | 502/503/504 with `payment_id` and state |
| Unexpected bank-client runtime exception | Attempt to record `Unknown`, then propagate | Sanitized 500 without payment ID |
| `complete()` throws after the bank returns | Propagate local failure; no second bank call | Sanitized 500; if no write occurred, payment remains `InFlight` |
| `markUnknown()` throws while handling a bank error | Propagate the local failure with the bank error suppressed | Sanitized 500; payment may remain `InFlight` |
| Process exits | In-memory state is lost | No durable replay or recovery |

`complete()` is outside the bank exception handler. A local completion failure must not be mislabeled as a bank failure or trigger another authorization. When both the bank and the uncertainty write fail, the local exception remains primary and the bank exception is preserved as suppressed evidence internally. The HTTP handler logs a safe error category/correlation ID, not raw exception messages or payloads.

A keyed replay can reveal the last retained state after a generic 500, but does not resume execution. An unkeyed client that did not receive an ID cannot recover it through this API. `X-Request-ID` supports correlation, but the current safe logs do not guarantee enough evidence to reconstruct a bank outcome. These are explicit take-home limits.

## State and time invariants

Persisted transitions are `InFlight → Authorized | Declined | Unknown`. `Rejected` describes validation failure and is never persisted. `Unknown` is absorbing in this implementation and is not a definitive bank outcome. A later uncertainty write cannot overwrite a definitive result; an opposite definitive result raises an explicit conflict. No-op writes preserve timestamps.

`createdAt` records admission, `updatedAt` records the last state change, and `completedAt` exists only for `Authorized` or `Declined` and equals that transition's `updatedAt`. The model rejects updates dated before creation. These are wall-clock timestamps; a backward clock adjustment can cause a local state-write failure rather than silently fabricate ordering.

The guarantee is one execution owner per retained merchant/key admission in one JVM. It is not an end-to-end exactly-once guarantee across bank side effects, process loss or unkeyed submissions.
