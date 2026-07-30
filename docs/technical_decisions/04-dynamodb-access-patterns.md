# Decision: DynamoDB Access-Pattern Design for the URL Shortener

## Context

DynamoDB has no query planner — every access pattern must be baked into
the key schema up front. Four patterns to support: redirect, list-by-user,
private-link decode, update.

## 1. Redirect by short code (public, hot path)

`PK=URL#<shortCode>`, `SK=METADATA` → `GetItem`.

- Hottest path in the system, needs O(1) point lookup.
- Short code (random Base62) is the partition key itself, not a counter —
  spreads load evenly, avoids a hot partition and guessable/sequential codes.
- `SK` is a fixed literal rather than omitted, so a future item type
  (e.g. `SK=CLICK#<ts>`) could share the same `PK` without colliding.

## 2. List a user's URLs, paginated (F3)

GSI1: `GSI1PK=USER#<userId>`, `GSI1SK=URL#<createdAt>#<shortCode>` → `Query` + `LastEvaluatedKey`.

- Base table is keyed by code, not user — a GSI is the only way to serve
  "all links owned by user X".
- `createdAt` leads the sort key so results come back chronologically for
  free; `shortCode` is just a tiebreaker suffix.
- GSIs are eventually consistent — acceptable for a list view.

## 3. Owner-only decode for private links (F8)

Same item as #1, plus two attributes: `ownerId`, `visibility` (`PUBLIC`/`PRIVATE`).
`redirect/decode` does the same `GetItem`, then:
- `PUBLIC` → redirect anyone.
- `PRIVATE` → caller (from an optional JWT — this route can't force auth)
  must match `ownerId`, else **404** (never 403, to avoid confirming the
  code exists).

Why attributes, not keys: authorization depends on *who's asking right
now* — a runtime fact — not on the item's static key shape.

**Rejected**: a dedicated GSI (`GSI_PK=USER#<userId>`, `GSI_SK=URL#<shortCode>`)
to enforce privacy via key design. Collides with #2's `GSI1SK` format
(one attribute, two incompatible value shapes), and still depends on the
Lambda scoping queries to the caller's own `userId` — so it adds an index
without adding a guarantee the attribute check doesn't already give.

Same item shape as #1 also means soft-delete/TTL logic applies unchanged
to private links — no forked 404/410 path.

## 4. Update

`UpdateItem` on `longUrl` (and `expiresAt` if editable) only. `shortCode`
and `createdAt` never change.

**Rejected**: delete + recreate. A new short code breaks already-shared
links (that's "create", not "update"), isn't atomic, and would shift the
item's position in GSI1's sort order mid-pagination.

## Summary

| Pattern | Key(s) | Mechanism |
|---|---|---|
| Redirect (public) | `PK=URL#<shortCode>`, `SK=METADATA` | `GetItem` |
| List user's links | GSI1 `USER#<userId>` / `URL#<createdAt>#<shortCode>` | `Query` |
| Private decode | same item + `ownerId`/`visibility` | `GetItem` + Lambda check, 404 on mismatch |
| Update | same item, unchanged key | `UpdateItem` on `longUrl` |

No second GSI. Privacy is attribute + app logic, not key design. Updates
never change `shortCode` or `createdAt`.
