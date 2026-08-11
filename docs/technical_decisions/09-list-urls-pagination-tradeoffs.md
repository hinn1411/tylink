# Decision: `list` Queries by `userId` Only, and Accepts a Non-Guaranteed Page Size

## Context

`ListUrlsHandler` (`GET /v1/urls`) paginates `DynamoDbUrlRepository.listByOwner` through GSI1
(`GSI1_PK=USER#<ownerId>`, `GSI1_SK=URL#<createdAt>#<shortCode>`). An earlier iteration of this
query added `visibility = PRIVATE` to its `FilterExpression`, restricting the endpoint to a
user's private links only. That restriction wasn't actually required: F3 in
`docs/plans/00-overview.md` specifies "list all URLs of a user," and `docs/plans/
01-implementation.md`'s original design for this query calls only for "a `FilterExpression`
excluding `status = DELETED` items." The `visibility` clause was scope creep beyond what either
document asked for, discovered while investigating why a returned page could have fewer items
than the requested `limit` — so it's worth separating two different questions this decision
answers: why query by `userId` alone (this section), and what to do about `limit`-vs-returned-
count generally (below), since the first significantly shrinks the second rather than solving it.

## Why query by `userId` only

- Matches F3 and the original implementation plan as written — no product requirement asks for
  a private-only list view.
- A single key condition, single filter clause request is simpler to reason about than one
  juggling two independent predicates.
- It was also the dominant cause of short pages: `PUBLIC` and `PRIVATE` links share one GSI1
  partition per user with no ordering relationship between the two, so a `visibility = PRIVATE`
  filter could discard the majority of items DynamoDB reads for a user whose links skew public.
  `status <> DELETED` alone is expected to have much better selectivity — soft-deleted items are
  a minority case, not a default one.

## DynamoDB pagination mechanics (why a page can still be short)

A `Query` with both `Limit` and `FilterExpression` runs the filter *after* the read:

1. DynamoDB reads up to `Limit` items from the index that match the `KeyConditionExpression`,
   in key order.
2. `FilterExpression` is applied to those items only; items it excludes still count against the
   `Limit` that was already spent reading them.
3. `LastEvaluatedKey` (returned to the caller as an opaque `nextCursor`, see `CursorCodec`) is a
   bookmark — the key of the last item *read*, not the last item *returned*. It's populated
   whenever the read was cut short by `Limit`, independent of how many items survived the filter.
4. On the next call, `ExclusiveStartKey` resumes the key-ordered scan immediately after that
   bookmark. It is not an offset — there's no way to jump to "item 101" without having walked
   there via `LastEvaluatedKey`.

Net effect: a response can have `items.size() < limit` (even `0`) while `nextCursor` is still
non-null. Callers must keep paginating on a non-null cursor rather than treating a short page as
the end of the list.

## Trade-offs considered for guaranteeing a full page

These would make `items.size()` equal `limit` on every page (barring true end-of-data), at a
cost. None is adopted now — see Decision.

| Approach | How | Pros | Cons |
|---|---|---|---|
| **Bounded Query-loop** | Repository loops, re-querying with the previous page's `LastEvaluatedKey`, until either `limit` items are collected or a fixed iteration/read cap is hit | No schema change; bounded worst-case latency and read cost per request | More read capacity per request than a single `Query`; adds loop/state-tracking complexity to the repository; still not a hard guarantee — a pathological user (e.g. mostly soft-deleted links) can still get a short page once the cap is hit |
| **Full/unbounded scan** | Same loop, no cap | Always returns a full page if enough matching items exist anywhere in the partition | Unbounded latency and read cost — one API call could scan a user's entire link history; real risk of Lambda timeout for a heavy user |
| **Extra GSI** (tried this session, reverted per explicit direction — see git history) | A second index containing only non-deleted items, so the query needs no `FilterExpression` at all | Exact guarantee, no read amplification, no loop | Extra write cost on every create/soft-delete to keep the index in sync; another index to reason about for a guarantee the app doesn't currently need; mirrors a trade-off `05-dynamodb-access-patterns.md` already rejected for a similar privacy-indexing case |

## Decision

Keep the single-clause filter (`status <> DELETED`) and accept the `limit`-vs-returned-count gap
as documented behavior, with no server-side fix for now. Dropping the `visibility` clause already
removes the main source of large gaps; the remaining gap is bounded by how many of a user's links
are soft-deleted, which is expected to be small in the common case. If usage data later shows
this is a real problem, the bounded Query-loop is the next thing to try — it's schema-neutral and
its worst case is boundable, unlike a full scan, and it doesn't carry the extra-GSI's ongoing
write/maintenance cost for a guarantee not yet shown to be necessary.
