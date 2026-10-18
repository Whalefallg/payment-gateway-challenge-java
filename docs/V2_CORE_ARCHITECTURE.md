# Payment Gateway V2-Core Architecture

## 1. Architecture

```text
Merchant
   │ HTTP + Idempotency-Key
   ▼
RequestCorrelationFilter
   ▼
PaymentGatewayController
   ▼
PaymentGatewayService ───────────────┐
   │ admission / replay / mapping    │
   ▼                                 │
PaymentRepository                    │
   │ ConcurrentHashMap snapshots     │
   ▼                                 │
PaymentIntent + PaymentAttempt       │
                                     ▼
                         PaymentExecutionService
                                     │ claim READY → IN_FLIGHT
                                     ▼
                                  BankClient
                                     ▼
                                Bank Simulator

RecoveryService ── reads/transitions unresolved snapshots only; never calls BankClient
```

The controller handles HTTP concerns. `PaymentGatewayService` owns application admission and
replay decisions. `PaymentExecutionService` is the only normal path from an admitted keyed
payment to a bank call. The repository owns aggregate atomicity. `BankClient` owns the exact
simulator wire contract and transport/protocol classification.

## 2. V1 versus V2

| Concern | V1 | V2-Core |
|---|---|---|
| Business identity | Created after bank result | Stable ID at keyed admission |
| Duplicate request | New payment and bank call | Existing aggregate, no bank call |
| Concurrency | Independent map puts | Atomic admission and execution claim |
| Lifecycle | Authorized / Declined only | READY, IN_FLIGHT, UNKNOWN, terminal results |
| Timeout | 504, no record | Keyed payment remains UNKNOWN |
| Bank attempt | Implicit HTTP call | Explicit attempt inside aggregate |
| Recovery | None | Safe in-memory stale/late-evidence decisions |
| Storage | ConcurrentHashMap | ConcurrentHashMap, with explicit limitations |

Unkeyed requests retain V1 behavior for API compatibility. The V2 guarantees require an
`Idempotency-Key`.

## 3. PaymentIntent and PaymentAttempt

`PaymentIntent` is one merchant-requested logical authorization. Its payment ID is stable and
unrelated to the per-HTTP `X-Request-ID`. It is an immutable aggregate snapshot containing one
`PaymentAttempt` in V2-Core.

An attempt is a logical bank submission, not an HTTP invocation. A timeout does not create a
second attempt. Because the simulator supplies neither downstream idempotency nor inquiry,
V2-Core never resends an unresolved authorization. The attempt has a generated bank request
identity for correlation/modeling, but the adapter does not pretend the simulator enforces it.

Neither aggregate stores PAN or CVV. It stores last four digits for display and a keyed HMAC
fingerprint for admission comparison. CVV is excluded entirely. Aggregate lists are copied and
all updates replace the whole intent-and-attempt snapshot.

## 4. State machine

Allowed transitions are centralized in the pure Java `PaymentTransitionPolicy`:

```text
READY -> IN_FLIGHT
READY -> FAILED_NO_EFFECT
IN_FLIGHT -> AUTHORIZED | DECLINED | UNKNOWN
UNKNOWN -> AUTHORIZED | DECLINED | FAILED_NO_EFFECT
```

All other transitions fail. Repository operations also require an expected current state, so
only one concurrent caller can claim `READY`. Terminal states cannot be overwritten. UNKNOWN
accepts a result only through controlled definitive-evidence handling.

## 5. Idempotency design

The key is scoped by merchant: `(merchantId, idempotencyKey)`. Under a short admission lock the
repository checks the idempotency index, creates the payment aggregate, inserts it into the
payment map, and publishes the index entry. This keeps the two in-memory indexes consistent in
one JVM. Bank I/O never occurs under that lock or in `ConcurrentHashMap.compute`.

The canonical fingerprint covers operation type (`AUTHORIZE`), amount, currency, expiry, and
PAN-derived identity using HMAC-SHA-256. The configured secret is never logged. With no configured
secret a strong random process-local value is generated, which correctly emphasizes that this
store is process-local. CVV-only changes are intentionally indistinguishable. Historical keyed
replay skips only the now-expired-card check; it still requires structurally valid matching
semantics and never reauthorizes.

## 6. ConcurrentHashMap atomicity

`ConcurrentHashMap` protects its own map operations. The repository additionally uses:

- one short synchronized section for coordinated payment/idempotency admission;
- `computeIfPresent` plus expected-state comparison for atomic aggregate transitions;
- immutable snapshots so intent and attempt cannot be observed at different versions.

This provides one-JVM atomic admission and state changes. It is not a database transaction,
does not coordinate separate gateway instances, does not persist across restart, and cannot
make an HTTP side effect atomic with a local update.

## 7. Failure scenarios

| Scenario | Decision |
|---|---|
| Duplicate, same semantics | Return existing snapshot; no bank call or new attempt |
| Duplicate, changed semantics | 409; no mutation or bank call |
| 100 concurrent duplicates | One admission and one execution claim |
| Explicit bank `authorized=true` | AUTHORIZED |
| Explicit bank `authorized=false` | DECLINED |
| Timeout / response lost | UNKNOWN; no retry |
| Invalid success response | UNKNOWN because the bank may have acted |
| Simulator HTTP 503 / connection failure | UNKNOWN; no proof of no side effect |
| Late authoritative result | UNKNOWN to matching definitive terminal state |
| Conflicting late result after terminal | Refused |
| JVM restart | All payments and keys are lost |

An HTTP invocation count is not treated as an authorization count. Tests use both concepts
where a fake bank can expose them.

## 8. UNKNOWN handling

`UNKNOWN` means the request may have reached the bank and the local process cannot prove its
outcome. Treating it as Declined would invite a duplicate authorization. `RecoveryService` can:

- mark stale `IN_FLIGHT` records UNKNOWN;
- accept controlled, definitive AUTHORIZED, DECLINED, or no-effect evidence;
- reject conflicting evidence against a terminal result;
- explicitly refuse retry of IN_FLIGHT or UNKNOWN work.

Diagnostic categories are fixed safe strings. They contain no request payload, PAN, CVV, or
derived instrument identity. No scheduled authorization takeover exists.

## 9. In-memory recovery limitations

The recovery service is useful only while the same JVM and map contents remain alive. A process
crash can lose an admitted payment, an outcome, or both. An old thread may still finish after a
stale decision; expected-state transitions keep it from overwriting a terminal observation, but
there is no lease that can retract a bank request already sent. Full reconciliation is impossible
without a durable local record and suitable bank capabilities.

## 10. Evolution toward PostgreSQL

A production evolution would replace repository snapshots with normalized intent, attempt, and
idempotency tables. A unique constraint on `(merchant_id, idempotency_key)` would arbitrate
admission. Version columns or conditional `UPDATE ... WHERE status=? AND version=?` statements
would claim transitions. Intent and attempt changes would commit in one database transaction.

Before dispatch, a durable attempt would be committed. After restart, workers could inspect
unfinished attempts. They still must not blindly resend unless the bank accepts a durable
downstream idempotency key. Prefer bank inquiry/reconciliation to resolve ambiguity. Operational
jobs would use row locking or safe work claiming, bounded retention, audit history, encryption,
and merchant authorization.

PostgreSQL durability narrows local crash gaps; it does not by itself make bank authorization
exactly once. That requires a compatible downstream protocol and reconciliation.

## 11. Deliberate omissions

- **PostgreSQL** was omitted because the exercise explicitly explores correct one-process map
  semantics and honest durability limits.
- **Redis** would add another state system without making the bank side effect atomic.
- **Kafka/RabbitMQ** would add delivery and reconciliation concerns without a downstream
  idempotency guarantee.
- **Kubernetes/microservices** would worsen the deliberately single-JVM coordination model.
- Capture, refund, settlement, webhooks, outbox, vaulting, and general workflow/state-machine
  frameworks are outside V2-Core scope.
