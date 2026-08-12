# Decision: List Endpoint Queries by Owner Only, and Accepts a Non-Guaranteed Page Size

## Context

A paginated "list a user's items" endpoint queries a DynamoDB secondary index keyed by owner,
ordered by creation time, then filters out soft-deleted items server-side. An earlier version of
this query also filtered to private-only items, restricting the endpoint to a user's private items
alone. That restriction wasn't actually required — the product requirement was "list all of a
user's items" — and it turned out to be the main cause of a separate problem, discovered while
investigating it: a returned page could have far fewer items than the requested page size. So this
decision covers two related questions: why filter by owner alone, not also by visibility, and what
to do about the page-size gap in general, since fixing the first mostly — but doesn't fully — fix
the second.

## Why filter by owner only, not also by visibility

- Matches the actual product requirement — nothing asks for a private-only list view.
- A single filter condition is simpler to reason about than two independent ones.
- It was also the dominant cause of short pages: public and private items share one index
  partition per owner with no ordering relationship between the two, so filtering to private-only
  could discard the majority of items read for a user whose items skew public. Excluding
  soft-deleted items alone is expected to have much better selectivity — soft-deleted items are a
  minority case, not a default one.

## Why a page can still come back short

A DynamoDB `Query` that combines a `Limit` with a `FilterExpression` applies the filter *after*
the read, not before:

1. DynamoDB reads up to `Limit` items matching the key condition, in key order.
2. `FilterExpression` is then applied to those items only; items it excludes still count against
   the `Limit` already spent reading them.
3. The pagination cursor returned to the caller (`LastEvaluatedKey`) is a bookmark — the key of
   the last item *read*, not the last item *returned*. It's set whenever the read was cut short
   by `Limit`, independent of how many items survived the filter.
4. The next call resumes the key-ordered scan immediately after that bookmark
   (`ExclusiveStartKey`); it is not an offset — there's no way to jump to "item 101" without
   walking there via the bookmark.

Net effect: a response can return fewer items than requested, even zero, while the cursor is still
non-null. Callers must keep paginating on a non-null cursor rather than treating a short page as
the end of the list.

## Options considered for guaranteeing a full page

These would make the returned count always equal the requested limit (barring true end-of-data),
at a cost. None is adopted now — see Decision.

| Approach | How | Pros | Cons |
|---|---|---|---|
| **Bounded retry loop** | Re-query using the previous page's `LastEvaluatedKey`, repeatedly, until either the requested count is filled or a fixed iteration/read cap is hit | No schema change; bounded worst-case latency and read cost per request | More read cost per request than a single query; adds loop/state-tracking complexity; still not a hard guarantee — a pathological user (e.g. mostly soft-deleted items) can still get a short page once the cap is hit |
| **Unbounded retry loop** | Same loop, no cap | Always returns a full page if enough matching items exist anywhere in the partition | Unbounded latency and read cost — one request could scan a user's entire history; real risk of timeout for a heavy user |
| **Dedicated index for non-deleted items** | A second secondary index containing only non-deleted items, so the query needs no `FilterExpression` at all | Exact guarantee, no read amplification, no loop | Extra write cost on every create/delete to keep the index in sync; another index to reason about, for a guarantee not currently needed |

## Decision

Keep the single filter (exclude soft-deleted items) and accept the page-size gap as documented
behavior, with no server-side fix for now. Dropping the visibility filter already removes the main
source of large gaps; the remaining gap is bounded by how many of a user's items are soft-deleted,
expected to be small in the common case. If usage data later shows this is a real problem, the
bounded retry loop is the next thing to try — schema-neutral, boundable worst case, without the
dedicated index's ongoing write/maintenance cost for a guarantee not yet shown to be necessary.
