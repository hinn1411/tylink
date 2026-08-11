# Decision: Short-Code Generation Algorithm

## Context
Need a scheme to generate the short code used as the DynamoDB partition key
for redirect lookups (`PK=URL#<shortCode>`, see
`05-dynamodb-access-patterns.md`). The obvious default — an auto-increment
counter — creates a single hot write partition (capped ~1,000 WCU/partition)
and produces guessable/enumerable codes.

## Alternatives Considered

| Approach | Pros | Cons |
|---|---|---|
| Auto-increment counter → Base62 | Never collides; shortest codes at any scale | Hot partition on the counter item; sequential → enumerable |
| Pre-generated Key Generation Service (KGS) | No collision retries; keys pre-vettable | Extra table/queue + replenishment job; overkill at this scale |
| Hash-based (MD5/SHA truncated) | Deterministic → free dedup of same URL | Truncated hash still collides → needs salt-and-retry anyway; no natural support for re-shortening same URL with different settings |
| UUID v4, truncated | Trivial, stdlib everywhere | Truncating throws away UUID's collision resistance → same problem as random+retry, less control over alphabet |
| Snowflake-style ID (timestamp+worker+seq) | No coordinator; sortable by time; zero retries | Longer codes; time-sortable → guessable, same as auto-increment; needs a stable worker/machine ID, awkward for stateless Lambda |
| Counter w/ per-node range allocation | Fixes single-hot-counter problem, shorter/ordered codes | Needs persistent per-worker state, fights Lambda's stateless model |
| **Random + conditional write (chosen)** | No coordinator/extra infra; spreads writes across partitions; non-enumerable; fits stateless Lambda + SnapStart | Non-deterministic (no free dedup, not needed here); rare collision-retry loop; fixed code length |

## Conclusion

Random 7-char Base62 string (`SecureRandom`, ~3.5×10^13 combinations), used
**as the DynamoDB partition key itself**, written with `PutItem` + condition
`attribute_not_exists(PK)`, retrying (rare) on `ConditionalCheckFailedException`.

Chosen approach is the direct fix for the hot-partition/enumerable-ID
anti-pattern above, with zero extra infrastructure — matching this project's
learning goal (`docs/plans/00-overview.md`) of teaching DynamoDB scaling
patterns without over-building.

Gotcha for Phase 2's SnapStart work: the `SecureRandom` entropy for code
generation must be drawn **inside the handler invocation**, not a static
initializer — otherwise the snapshot bakes in reused entropy across restores.
