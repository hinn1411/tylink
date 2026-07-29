# DynamoDB Schema Modeling Exercises

Practice turning access patterns into keys and indexes, on small generic scenarios,
before designing TyLink's own table. Read `dynamodb-single-table-design.md` first if
any of the terms below (PK/SK, LSI, GSI, adjacency list) are unfamiliar.

For each exercise: read the scenario and access patterns, then write down your own
PK/SK/index design before expanding the solution sketch. There's more than one valid
design for most of these — the point is the reasoning ("why this key, why this kind
of index"), not matching the sketch exactly.

---

## 1. Warm-up — key-only lookup

**Scenario**: A user profile store. Every user has an ID, name, and email.

**Access patterns**:
1. Get a user's profile by user ID.

**Your task**: Design the partition key (and decide whether you need a sort key at all).

<details>
<summary>Solution sketch</summary>

`PK = USER#<userId>`, no sort key needed — one access pattern, one item per user, a
partition-key-only lookup is enough. A sort key only earns its keep once a partition
key needs to hold *more than one item*.
</details>

---

## 2. Adding a sort key

**Scenario**: A blogging platform. Each author has many posts.

**Access patterns**:
1. Get all posts by a given author.
2. Get a given author's posts published after a given date.

**Your task**: Design PK and SK so both patterns are single queries.

<details>
<summary>Solution sketch</summary>

`PK = AUTHOR#<authorId>`, `SK = POST#<publishedDate>#<postId>`. Pattern 1 is a query
on PK alone; pattern 2 adds a `SK > POST#<date>` condition — this only works because
the sort key starts with the sortable/filterable field (the date).

**Caveat**: DynamoDB compares string sort keys **lexicographically** (byte-by-byte),
not chronologically. `SK > POST#2024-01-05` only sorts correctly if `publishedDate`
is in a sortable string format — ISO-8601 (`2024-01-05`) works; `1/5/2024` doesn't
(`"1/"` sorts before `"12"`, so month/day order breaks the comparison). Same logic
applies to numbers used in sort keys: zero-pad them (`00042` not `42`), or the string
`"9"` will sort after `"10"`.
</details>

---

## 3. One-to-many via adjacency list

**Scenario**: An e-commerce customer and their orders.

**Access patterns**:
1. Get a customer's profile.
2. Get all of a customer's orders.
3. Get both (profile + orders) in one query.

**Your task**: Design one item collection (shared partition key) that stores both
entity types, and a sort key that distinguishes them.

<details>
<summary>Solution sketch</summary>

`PK = CUSTOMER#<customerId>` for both entity types. Profile item: `SK = PROFILE`.
Order items: `SK = ORDER#<orderId>`. Pattern 3 is a plain `Query` on PK with no SK
condition — it returns the whole item collection (profile + all orders) in one call,
which is the adjacency-list pattern's whole point: one partition key, multiple
entity types, one query.
</details>

---

## 4. LSI exercise — same partition key, different order

**Scenario**: Extend exercise 3's e-commerce orders. Each order has a status
(`PENDING`, `SHIPPED`, `DELIVERED`) and a creation date.

**Access patterns** (in addition to exercise 3's):
4. Get a customer's orders sorted/filtered by status instead of by order ID.

**Your task**: You already sorted this customer's orders by `ORDER#<orderId>` in
exercise 3. Pattern 4 needs a *different* sort order on the *same* partition key
(still scoped to one customer). What index type fits, and what's its main constraint?

<details>
<summary>Solution sketch</summary>

A **Local Secondary Index (LSI)**: same partition key (`CUSTOMER#<customerId>`),
alternate sort key (e.g. `status#createdAt`). LSIs exist exactly for "same PK,
different SK" cases. The catch: LSIs must be declared when the table is created —
you can't add one later without recreating the table, so this only works if you knew
about pattern 4 up front.

**Caveat**: the commonly-quoted "10 GB per partition" LSI limit isn't 10 GB *for the
LSI alone* — it's 10 GB combined, across the base table item collection **and** every
LSI on it, for that one partition key value. If one customer could plausibly place an
unbounded number of orders over time, an LSI on `CUSTOMER#<customerId>` risks hitting
that ceiling for your highest-volume customers — worth checking before committing to
an LSI over, say, a GSI (which has no such per-key size cap).
</details>

---

## 5. GSI exercise — a different partition key entirely

**Scenario**: Same orders as above, but now customer support needs to pull up an
order by its order ID alone, without knowing which customer placed it.

**Access patterns**:
5. Get an order by order ID only (no customer ID available).

**Your task**: The base table's partition key is `CUSTOMER#<customerId>` — order ID
is only in the sort key. What kind of index does pattern 5 need, and what do you give
up compared to reading the base table directly?

<details>
<summary>Solution sketch</summary>

A **Global Secondary Index (GSI)** with `orderId` as its partition key. Unlike an LSI,
a GSI can have a *different* partition key than the base table, and can be added or
removed at any time (no table recreation needed). The trade-off: GSIs are only
eventually consistent, and have their own provisioned/on-demand throughput separate
from the base table.

**Another dimension to decide**: what the GSI **projects** (copies over) from the
base table item. `ALL` copies every attribute — simplest, but doubles storage/write
cost for that data. `KEYS_ONLY` or `INCLUDE` (a chosen attribute subset) cost less to
store and write, but if support needs an attribute that wasn't projected, you pay for
a second `GetItem` against the base table to fetch it. For a support lookup like this
(low volume, need the full order), `ALL` is usually the simpler choice; a
high-write-volume GSI used only to fetch a handful of fields would favor
`KEYS_ONLY`/`INCLUDE`.
</details>

---

## 6. Reversing a many-to-many relationship

**Scenario**: A movie database. A movie has many actors; an actor appears in many
movies.

**Access patterns**:
1. Get all actors in a given movie.
2. Get all movies a given actor has appeared in.

**Your task**: Design a base-table adjacency-list layout for pattern 1. Then work out
*two* different ways to serve pattern 2 (the reverse direction), and weigh their
trade-offs — you don't need a GSI to reverse a relationship; it's one option, not the
only one.

<details>
<summary>Solution sketch</summary>

Base table: `PK = MOVIE#<movieId>`, `SK = ACTOR#<actorId>` (plus a `PK = MOVIE#<id>,
SK = METADATA` item for the movie's own attributes) — a `Query` on PK alone answers
pattern 1.

Two ways to serve pattern 2:

- **A GSI**: `GSI_PK = ACTOR#<actorId>`, `GSI_SK = MOVIE#<movieId>`, populated from the
  same items used for pattern 1 (each already has both IDs, so no extra items to
  write). One `PutItem` on the base table is all your app does — DynamoDB replicates
  it into the GSI automatically. Cost: the GSI is only eventually consistent
  (typically sub-second lag).
- **A second, reversed item in the base table**: also write `PK = ACTOR#<actorId>`,
  `SK = MOVIE#<movieId>` for the same relationship, so pattern 2 is answered by the
  base table directly, with strong consistency. Cost: your application now owns
  keeping both items in sync — every write/update/delete of the relationship must
  touch both items (typically via `TransactWriteItems`, which costs roughly double
  the write capacity and has stricter size/item-count limits), and a missed update on
  one side silently desyncs the two directions.
- **A denormalized Set attribute** (only viable if cardinality is small and bounded):
  skip edge items entirely — store `actorIds` as a `String Set` directly on the movie
  item, and `movieIds` as a `String Set` directly on the actor item, updated with
  `ADD`/`DELETE` on the set. No GSI, no second item type. This only works while a
  given actor's or movie's set stays well under the 400 KB item cap (fine for most
  actors' filmographies; would break down for an actor who's "played themselves" in
  thousands of archive-footage credits), and you still need a follow-up
  `BatchGetItem` to turn the ID list into full records.

None of these is "index overloading" by itself — that term specifically means
reusing the *same* GSI's key slots across other, unrelated entity types elsewhere in
the table (see `dynamodb-single-table-design.md` #5 for a worked example). Using one
GSI to reverse one relationship is just... using a GSI.
</details>

---

## 7. Capstone — combine everything

**Scenario**: A small library system: books, authors, and members who check books out.

**Access patterns**:
1. Get all books by a given author.
2. Get a given member's currently checked-out books.
3. Get a given member's checkout history sorted by checkout date.
4. Get all overdue items across *all* members, sorted by due date (for a daily
   overdue-notice job).

**Your task**: Design a base table plus whatever combination of LSI/GSI you need to
satisfy all four patterns with single queries. Identify, for each pattern, whether
it's served by the base table, an LSI, or a GSI, and why.

<details>
<summary>Solution sketch</summary>

**Base table** (adjacency-list style):
- `PK = AUTHOR#<authorId>`, `SK = BOOK#<bookId>`
- `PK = MEMBER#<memberId>`, `SK = CHECKOUT#<checkoutDate>#<bookId>`, with a `status`
  attribute (`OUT` / `RETURNED`) and a `dueDate` attribute on each item.

| Pattern | Served by | Query |
|---|---|---|
| 1. Books by author | Base table | `PK = AUTHOR#<id>` |
| 2. Member's current checkouts | LSI | `PK = MEMBER#<id>` on an LSI keyed by `status` |
| 3. Member's checkout history, by date | Base table | `PK = MEMBER#<id>` (SK already sorts by date) |
| 4. Overdue items, all members, by due date | GSI | `GSI_PK = STATUS#OUT`, `GSI_SK < <today>` |

**Why patterns 2 and 3 need different treatment**, despite sharing `PK =
MEMBER#<memberId>`: pattern 3 wants the *entire* history, which the base table
already returns sorted (SK starts with `checkoutDate`). Pattern 2 wants only the
still-`OUT` subset — plain "query PK" can't isolate that, since it returns returned
books too. Two ways to narrow it down:
- Filter the base-table query by `status = OUT` — simplest, but DynamoDB still reads
  every item in the partition (full history) before filtering, so cost scales with
  history size, not just the current-checkout count.
- Add an **LSI** keyed by `status` (or `status#checkoutDate` for date order within a
  status) — same "same PK, different order" reasoning as exercise 4. Cheaper at
  scale, at the cost of one more index to maintain.

**Why pattern 4 needs a GSI**: "overdue across every member" has no single partition
key — neither the base table nor an LSI (which shares the base table's PK) can serve
a query that spans every member. A GSI can, since it's allowed its own partition key:
`GSI_PK = STATUS#OUT`, `GSI_SK = dueDate`, queried as `GSI_PK = STATUS#OUT AND
GSI_SK < <today>`. "Overdue" falls out of the range condition — nothing needs to be
separately marked overdue ahead of time.

**Gotcha**: `STATUS#OUT` is a low-cardinality partition key — every outstanding
checkout in the entire library lands in that one GSI partition, the same hot-key
problem as `system-design-concepts.md` (#2/#3), just relocated onto a GSI instead of
the base table. Two fixes:
- **Shard the GSI key**: `GSI_PK = STATUS#OUT#<0-9>` (hash the checkout ID into a
  fixed number of buckets), query all shards in parallel, merge client-side.
- **Skip the GSI for this pattern**: it's a once-daily batch job, not a
  latency-sensitive read, so a parallel `Scan` with a `status = OUT` filter over the
  base table avoids permanently provisioning a hot GSI partition for one infrequent
  job — arguably the better default here; reserve the sharded GSI for when overdue
  lookups need to be frequent and low-latency.
</details>

---

Once these feel comfortable, `../plans/00-overview.md` has TyLink's actual base table
+ GSI1 design as a real-world worked example to compare your reasoning against.
