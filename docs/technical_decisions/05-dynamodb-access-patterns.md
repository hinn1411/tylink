# Decision: DynamoDB Access-Pattern Design for the URL Shortener

## Context
Design access paths to satisfy Functional requirements

## 1. Redirect by short code (public, hot path)

`PK=URL#<shortCode>`, `SK=METADATA` → `GetItem`.

- Hottest path in the system, needs O(1) point lookup.
- Short code (random Base62) is the partition key itself, not a counter —
  spreads load evenly, avoids a hot partition and guessable/sequential codes.
- `SK` is a fixed literal rather than omitted, so a future item type
  (e.g. `SK=CLICK#<ts>`) could share the same `PK` without colliding.

## 2. List a user's URLs, paginated (F3)

GSI1: `GSI1_PK=USER#<userId>`, `GSI1_SK=URL#<createdAt>#<shortCode>` → `Query` + `LastEvaluatedKey`.

- Base table is keyed by code, not user — a GSI is the only way to serve
  "all links owned by user X".
- `createdAt` leads the sort key so results come back chronologically for
  free; `shortCode` is just a tiebreaker suffix.
- GSIs are eventually consistent — acceptable for a list view.
- `ownerId`/`GSI1_PK`/`GSI1_SK` are only written when `create` has a real
  authenticated caller. `create` itself never requires auth for `PUBLIC`
  links — an anonymous caller can shorten a URL with no owner at all;
  that item just carries no GSI1 keys and is invisible to this pattern by
  construction (there's no `userId` to list it under).

## 3. Owner-only decode for private links (F8)

Same item as #1, plus two attributes: `ownerId`, `visibility` (`PUBLIC`/`PRIVATE`).
`redirect/decode` does the same `GetItem`, then:
- `PUBLIC` → redirect anyone.
- `PRIVATE` → caller (from an optional JWT — this route can't force auth)
  must match `ownerId`, else **404** (never 403, to avoid confirming the
  code exists).

Why attributes, not keys: authorization depends on *who's asking right
now* — a runtime fact — not on the item's static key shape.

This is also why `create` gates auth on **visibility**, not on the request
as a whole: `PRIVATE` needs a real `ownerId` to check against later, so it
requires an authenticated caller; `PUBLIC` doesn't, so anonymous callers
can use it too.

**Rejected**: a dedicated GSI (`GSI_PK=USER#<userId>`, `GSI_SK=URL#<shortCode>`)
to enforce privacy via key design. Collides with #2's `GSI1_SK` format
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
never change `shortCode` or `createdAt`. `ownerId`/GSI1 keys are present
only when `create` had an authenticated caller — anonymous callers can
still create `PUBLIC` links with no owner.

## Conclusion

One table, one item type, one GSI. Every attribute below lives on the same
item (`PK=URL#<shortCode>`, `SK=METADATA`) — nothing here is split across
item types or a second index:

```
PK          URL#<shortCode>              — identity, redirect lookup key
SK          METADATA                     — fixed literal, room for future item types
GSI1_PK     USER#<userId>, optional      — list-by-user partition key (authenticated creators only)
GSI1_SK     URL#<createdAt>#<shortCode>, optional — chronological order + tiebreaker
longUrl     string                       — the only field `update` touches
ownerId     USER#<userId>, optional      — private-decode ownership check (authenticated creators only)
visibility  PUBLIC | PRIVATE             — gates the ownership check
status      ACTIVE | DELETED             — soft delete
createdAt   ISO-8601, set once           — never changes, incl. on update
expiresAt   ISO-8601, optional           — TTL attribute + explicit read-time check
deletedAt   ISO-8601                     — set on soft delete
purgeAt     epoch seconds, optional      — cleanup horizon for soft-deleted items
```

`ownerId`/`GSI1_PK`/`GSI1_SK` are written together or not at all: `PRIVATE`
links always require an authenticated caller (F8's ownership check needs a
real owner), while `PUBLIC` links may come from an anonymous caller and
simply omit all three — such a link can't appear in the list-by-user
pattern, since there's no `userId` to list it under.

All four access patterns resolve against this one shape: redirect and
private decode are the same `GetItem` on `(PK, SK)`; list is a `Query` on
`GSI1_PK`; update is an `UpdateItem` that only ever touches `longUrl`
(and `expiresAt`, if made editable). No access pattern required a second
GSI or a second item type.
