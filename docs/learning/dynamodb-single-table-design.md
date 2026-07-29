# DynamoDB Single-Table Design — Notes Before Reading the AWS Article

A pre-read summary of [Creating a single-table design with Amazon DynamoDB](https://aws.amazon.com/blogs/compute/creating-a-single-table-design-with-amazon-dynamodb/)
(AWS Compute Blog). Read this for the vocabulary, then read the article for the worked example.

---

## 1. Why single-table design exists

DynamoDB has no joins — instead of normalizing and joining at query time, you denormalize up front into
one table shaped so every app query is a single request. (TyLink: one table instead of separate
`urls`/`users` tables joined at read time.)

## 2. Partition key vs. sort key

**Partition key**: hashed, locates an item in ~constant time regardless of table size, no relationship
to other keys (unlike a SQL primary key). **Sort key**: orders/filters items within a partition key
(range queries, prefix matches).

Sort keys compare **lexicographically** (as strings): `SK > POST#2024-01-05` only sorts right if the
value is string-sortable — ISO-8601 dates (`2024-01-05`, not `1/5/2024`), zero-padded numbers (`00042`,
not `42`, else `"9"` sorts after `"10"`). (TyLink: base table's partition key is the short code — see
`../plans/00-overview.md`.)

## 3. LSI vs. GSI

| | Local Secondary Index (LSI) | Global Secondary Index (GSI) |
|---|---|---|
| Partition key | Same as base table | Can differ from base table |
| Created | Only at table creation | Any time |
| Throughput | Shares the base table's | Its own |
| Consistency | Strong or eventual | Eventual only |
| Size limit | 10 GB per partition | None |

- LSI's 10 GB limit is shared, not per-index — base table + all LSIs combined, per partition key value —
  so an unbounded-growth key hits it regardless of which LSI you add.
- A GSI doesn't remove hot-partition risk, it relocates it (`system-design-concepts.md` #2/#3) — a
  low-cardinality GSI key still funnels everything into one partition.
- GSI projection: `ALL` (every attribute, costs more) vs. `KEYS_ONLY`/`INCLUDE` (cheaper, may need a
  follow-up `GetItem`).

(TyLink: GSI1 needs its own partition key, hence a GSI; its eventual consistency is why
`system-design-concepts.md` #4/#5 flags `TransactWriteItems` for read-your-writes.)

## 4. Adjacency list pattern

Store unrelated entity types in one table by prefixing keys with a type tag (`USER#123`, `RACE#1`) —
related items share a partition key or sort-key prefix, so one query returns the whole related set, but
only queryable from one side. Reversing a many-to-many relationship needs one of:

- **GSI with the relationship flipped** — one write, DynamoDB replicates it; costs eventual consistency
  on the reverse read.
- **A second, reversed item in the base table** — strongly consistent, but the app must keep both in
  sync via `TransactWriteItems` (~double write cost).
- **A denormalized Set attribute per side** (`ADD`/`DELETE`) — no GSI or edge items, but bounded by the
  400 KB item cap and needs a `BatchGetItem` to hydrate records.

## 5. Index / key overloading

Give a GSI's keys generic names (`GSI1PK`/`GSI1SK`) instead of entity-specific ones, so one GSI serves
several unrelated access patterns instead of needing one GSI per relationship (table cap: 20 GSIs):

| Item type | GSI1PK | GSI1SK | Access pattern served |
|---|---|---|---|
| Movie↔Actor edge | `ACTOR#<actorId>` | `MOVIE#<movieId>` | "movies for a given actor" |
| Order | `CUSTOMER#<customerId>` | `ORDER#<date>` | "a customer's orders by date" |
| Employee | `DEPARTMENT#<deptId>` | `EMPLOYEE#<name>` | "employees in a department" |

`GSI1PK` is just a string DynamoDB never interprets — all three patterns share one index instead of
three.

**Note**: a GSI with a different partition key than the base table isn't itself overloading — that's
just what a GSI is for. Overloading means reusing the *same* GSI's key slots across unrelated entity
types.

## 6. Access-pattern-first modeling

List every query the app must answer before designing the schema, then shape keys/indexes to fit that
list — the opposite of relational design, which normalizes first and expects new joins to just work
later. (TyLink: base table and GSI1 exist because those were the known access patterns, not a "natural"
model — `system-design-concepts.md` #11.)

## 7. The article's design process

1. Extract every access pattern from user stories (new app) or query logs (migration).
2. Assign the partition key by entity type, sort key for filtering/ordering within it.
3. Add LSIs for alternate sort orders on the same partition key; GSIs for entirely different query
   angles (different partition key).
4. Nest tightly-related data (e.g. a time series) as JSON inside one item instead of spreading it across
   many rows.
5. Test the resulting schema against every access pattern from step 1 before building anything — a
   pattern not checked here is a pattern the schema probably doesn't serve.

## 8. Trade-offs

Predictable, horizontally-scalable performance, at the cost of query flexibility and upfront design
effort — a pattern nobody thought of in step 1 is expensive or impossible to bolt on later, unlike
adding a new join in a normalized schema.

## 9. Practical limits

- Max item size: 400 KB. Split large/repeating data (e.g. telemetry) across multiple sort-key-ordered
  items, or compress (GZIP), instead of one item.
- Short attribute names help — every item repeats its own schema.

---

See `../plans/05-references.md` for this and other reference links, and `system-design-concepts.md` for
the broader system-design vocabulary behind TyLink's design decisions.
