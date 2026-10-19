# Payment Gateway V2-Lite contract

V2-Lite keeps the public V1 payment contract and only the in-memory idempotency safety needed by
the keyed extension. This document is the acceptance contract for the refactor.

## Public API

- `POST /payments` returns `201 Created` plus `Location` for a new, definitive `Authorized` or
  `Declined` result. Response fields and status spelling remain unchanged.
- `GET /payments/{id}` returns `200 OK` for a stored payment. Malformed UUIDs, missing payments,
  unsupported methods/media types, malformed JSON, and validation failures retain their existing
  status codes and sanitized error shapes.
- Existing amount, currency, card, expiry, and JSON-token validation is unchanged. Rejection occurs
  before bank I/O or persistence.

## Requests without Idempotency-Key

Every unkeyed request is independent. It validates, calls the bank first, and persists exactly one
record only after an explicit `Authorized` or `Declined` result. A mapped or unexpected bank failure
creates no record and retains the V1 502/503/504 response without payment identity or business
status. This deliberately small compatibility path has no admission, execution claim, replay, or
cross-request deduplication behavior.

## Requests with Idempotency-Key

The key is scoped by merchant. Atomic `admitAndClaim` compares a keyed HMAC fingerprint and either:

- creates one `IN_FLIGHT` record and grants its caller execution ownership;
- returns the existing record for the same payment semantics; or
- reports a conflict without changing the existing record.

Only `CREATED` grants permission to invoke the bank. Observing `IN_FLIGHT` never grants ownership.
Bank I/O happens outside repository atomic sections. A replay is `202 Accepted` while `IN_FLIGHT`,
and `200 OK` when `AUTHORIZED`, `DECLINED`, or `UNKNOWN`; it never invokes the bank again.

The fingerprint covers operation, amount, currency, expiry, and the full instrument identity using
HMAC-SHA-256. Neither PAN nor CVV is stored. CVV and CVV-derived data are intentionally excluded, so
a CVV-only change is a replay. A historical replay must still satisfy structural validation, but an
expiry violation caused only by time passing is ignored after the stored fingerprint matches.

## State and time semantics

The persisted keyed lifecycle is:

`IN_FLIGHT -> AUTHORIZED | DECLINED | UNKNOWN`

An unkeyed definitive record is created directly as `AUTHORIZED` or `DECLINED`. `REJECTED` is an API
result and is never persisted. `UNKNOWN` means the bank invocation may have taken effect but its
outcome is unresolved; it is not a decline, a confirmed no-effect result, or a completed
authorization. V2-Lite has no retry, takeover, recovery, or reconciliation path from `UNKNOWN`.

- `createdAt`: when the record first becomes visible in this repository. For keyed records this is
  admission time; for unkeyed records it is definitive-persistence time after bank success.
- `updatedAt`: the instant of the latest applied state change. Idempotent/no-op operations do not
  change it. It is never before `createdAt`.
- `completedAt`: present only for `AUTHORIZED` or `DECLINED`, at the instant that definitive outcome
  is first persisted. It is absent for both `IN_FLIGHT` and `UNKNOWN`.

Definitive outcomes are immutable: applying the same outcome is an idempotent no-op, applying the
opposite outcome reports an explicit conflict, and `markUnknown` cannot overwrite either terminal
state. Repository operations enforce these rules atomically.

## Boundaries and known limitations

The repository is an in-memory, single-JVM implementation. Guarantees last only while the same map
survives; they do not coordinate multiple processes and are lost on restart. V2-Lite does not claim
durability or exactly-once bank authorization. New keys and unkeyed requests can authorize again.

The characterization and final tests cover HTTP compatibility, validation-before-I/O, bank failure
mapping, sensitive-data exclusion, merchant scoping, semantic conflicts, historical replay,
deterministic concurrent duplicate ownership, independent keys, ambiguity-to-`UNKNOWN`, and atomic
terminal-state protection.
