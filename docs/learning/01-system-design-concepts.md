# System Design Concepts — Notes from the TyLink Planning Discussion

A beginner-friendly recap of the system-design concepts that came up while designing TyLink's short-code generation and read/write scaling, with pointers into Martin Kleppmann's *Designing Data-Intensive Applications* (DDIA) for deeper reading.

**Page-number caveat**: page numbers below are approximate, from the 1st edition paperback/ebook. They shift a little between print runs and formats (and DDIA has a 2nd edition in progress as of 2025). Chapter and section **names** are stable — if a page number is off, use the book's index or table of contents to find the named section.

---

## 1. Scalability & describing performance

**What it means**: "Scalable" isn't one number — you first describe your load (requests/sec, read/write ratio, fan-out) and your performance (usually **latency percentiles** like p50/p99, not averages, because averages hide the slow outliers that actual users feel — and a single slow call inside a fan-out request drags down the whole request's latency, "tail latency amplification").

**Where it showed up**: TyLink's N1 requirement — p99 < 1000ms, applied uniformly across redirect and CRUD — is exactly this kind of percentile-based SLO.

**Read in DDIA**: Chapter 1, *Reliable, Scalable, and Maintainable Applications* — sections "Describing Load" and "Describing Performance" (~p. 10–18).

---

## 2. Partitioning and hot spots

**What it means**: Splitting a dataset across many nodes (shards) so no single machine holds everything. Two common strategies: **key-range partitioning** (ordered, good for range scans, but sequential writes concentrate on one partition) and **hash partitioning** (spreads keys evenly, but loses range-scan ability). Either way, if too many operations land on one specific key, that key's partition becomes a **hot spot** no matter how many nodes exist — adding nodes doesn't help a single overloaded key.

**Where it showed up**: this is *the* core reasoning behind TyLink's short-code design — an auto-increment counter is a classic hash/range-partitioning hot-spot mistake (every write hits one key); a random Base62 code spreads writes across the whole hash space instead.

**Read in DDIA**: Chapter 6, *Partitioning* — "Partitioning by Hash of Key" and "Skewed Workloads and Relieving Hot Spots" (~p. 203–207).

---

## 3. Hot key reads vs. many-distinct-key reads

**What it means**: A read hotspot ("one key read 10,000×/sec") is a *different* problem from "10,000 different keys, each read normally" — the latter spreads naturally across partitions and is fine; the former is capped by a single partition's throughput ceiling no matter what your key design is. Only caching, replication, or splitting that one hot key differently (e.g. appending a random suffix and fanning reads out, then merging) fixes a true single-key hotspot.

**Where it showed up**: today's question — 10,000 different short codes all pointing at facebook.com is *fine* (distinct keys, spreads naturally); a single link going viral (one key, many reads) is the actual hot-spot case that needs caching.

**Read in DDIA**: same section as #2 — "Skewed Workloads and Relieving Hot Spots" (~p. 206) explicitly covers the "one hot key" case and the technique of splitting/randomizing it.

---

## 4. Replication and replication lag

**What it means**: Copying data across nodes for availability and read scaling. The catch is **replication lag** — followers/replicas are usually *eventually consistent*, meaning a read right after a write might not see that write yet. Named sub-problems: **read-your-writes consistency** (a user should see their own update immediately, even if others don't yet), **monotonic reads** (never see time go backwards across reads), **consistent prefix reads** (see writes in an order that makes causal sense).

**Where it showed up**: TyLink's GSI1 (list-by-user) is eventually consistent by default — exactly this trade-off. The doc even calls out `TransactWriteItems` as the fix if you need read-your-writes on the list view.

**Read in DDIA**: Chapter 5, *Replication* — "Problems with Replication Lag" (~p. 161–167).

---

## 5. Secondary indexes / derived data and staleness

**What it means**: A secondary index (or a cache, or a materialized view) is *derived* from the primary data — it's a second copy, kept in sync asynchronously. Anything derived can lag behind the source of truth. This is a general pattern, not just a database feature.

**Where it showed up**: DynamoDB GSIs are secondary indexes (eventually consistent, see #4); DynamoDB TTL deletion lagging up to 48 hours behind the `expiresAt` you set is the same "derived state can lag" idea — which is why TyLink checks `expiresAt` explicitly at read time instead of trusting TTL as an enforcement mechanism.

**Read in DDIA**: Chapter 11, *Stream Processing* — "Maintaining Derived State" (~p. 452–457); also Chapter 6's "Partitioning and Secondary Indexes" (~p. 207–211).

---

## 6. Caching and cache invalidation

**What it means**: Caching moves reads closer to the client (or off the origin system entirely), trading raw throughput for a **staleness window** — a cached value can outlive the truth it was copied from. The classic hard problem is invalidation: how/when do you know a cached value is no longer valid?

**Where it showed up**: TyLink's CloudFront edge cache in front of redirects, and the explicit trade-off noted in `02-phase2-scaling.md`: an updated/deleted link can keep serving stale content until the cache entry's `max-age` expires or you explicitly invalidate it.

**Read in DDIA**: DDIA doesn't have one dedicated "caching" chapter, but treats caches as a form of derived data — Chapter 11, "Maintaining Derived State" (~p. 452–457) and Chapter 3, *Storage and Retrieval* intro on indexes/caches as redundant, read-optimized copies (~p. 79–83).

---

## 7. Storage engines and tombstones (soft delete)

**What it means**: Many storage engines never truly delete-in-place on a write path; they append a **tombstone** marker meaning "this key is deleted" and clean up the real bytes later during compaction. This lets the system distinguish "deleted" from "never existed" without expensive in-place rewrites.

**Where it showed up**: TyLink's soft delete (`status = DELETED` + `deletedAt`, real removal deferred to a `purgeAt` TTL) is the exact same idea, one layer up — application-level tombstones instead of storage-engine ones — and it's *why* the redirect Lambda can return 410 (deleted) vs 404 (never existed), a distinction a hard delete can't make.

**Read in DDIA**: Chapter 3, *Storage and Retrieval* — "Hash Indexes" section, the paragraph on deleting a key by appending a special deletion record/tombstone (~p. 72–76).

---

## 8. Consensus and coordination (why a shared counter is expensive)

**What it means**: A single, globally-agreed-upon value (like an auto-increment counter, or "who is the leader") needs **coordination** among nodes — this is provably harder and slower than operations that don't need agreement, especially across a network with delays and failures. This is why a naive shared counter becomes a bottleneck: every increment has to be agreed on by whatever holds the counter.

**Where it showed up**: this is the theoretical reason "auto-increment counter" and "Snowflake-style ID with a worker ID" both need some form of centralized coordination (a counter service, or a machine-ID allocator) — while G (random + conditional write) needs *no* coordination at all, which is exactly why it fits Lambda's stateless, uncoordinated execution model so well.

**Read in DDIA**: Chapter 9, *Consistency and Consensus* — "Linearizability" (~p. 324–333) for why a shared counter behaves like a single coordinated register, and "Fault-Tolerant Consensus" (~p. 364–370) for why agreement across nodes is inherently costly.

---

## 9. Idempotency and delivery guarantees

**What it means**: Networks can duplicate or retry requests (client timeout + retry, at-least-once delivery). An **idempotent** operation produces the same result no matter how many times it's applied — this is what makes retries safe. Systems that can't guarantee exactly-once delivery instead make operations idempotent and accept at-least-once delivery underneath.

**Where it showed up**: TyLink's F4 requirement — the Powertools Idempotency utility keyed on `Idempotency-Key`, gating entry *before* a short code is generated, so a retried create can't mint a second code for the same logical request.

**Read in DDIA**: Chapter 11, *Stream Processing* — "Fault Tolerance" → "Idempotence" (~p. 476–478); Chapter 8, *The Trouble with Distributed Systems*, for *why* retries and duplicate delivery happen in the first place (unreliable networks, ~p. 279–286).

---

## 10. Backpressure and rate limiting

**What it means**: When a producer sends work faster than a consumer can process it, something has to give: buffer it (risking unbounded memory/latency growth), drop it, or signal the producer to slow down (**backpressure**). **Rate limiting** (e.g. token-bucket) is a deliberate, explicit version of this applied at a system's edge to protect it from bursts or abuse.

**Where it showed up**: API Gateway's per-route/stage throttling (token-bucket rate & burst limits), client-side exponential backoff+jitter, and reserved Lambda concurrency to protect DynamoDB from a pile-up — all backpressure/rate-limiting mechanisms at different layers of TyLink.

**Read in DDIA**: Chapter 11, *Stream Processing* — "Message Passing Dataflow" section discusses backpressure when a consumer falls behind a producer (~p. 441–444).

---

## 11. Data modeling for access patterns (NoSQL vs. normalized)

**What it means**: Relational modeling normalizes data and lets you query it many different ways after the fact with joins. Document/NoSQL modeling (like DynamoDB single-table design) does the opposite: you decide your access patterns *up front* and shape the data (including duplicating it across a base table and indexes) specifically to serve those patterns fast, at the cost of flexibility for patterns you didn't plan for.

**Where it showed up**: TyLink's single-table design — base table for the redirect-by-code hot path, GSI1 for list-by-user — is a textbook example of access-pattern-driven modeling instead of normalized modeling.

**Read in DDIA**: Chapter 2, *Data Models and Query Languages* — the relational-vs-document comparison, especially "Are Document Databases Repeating History?" (~p. 30–39).

---

## Quick-reference table

| # | Concept | DDIA Chapter | Approx. pages |
|---|---|---|---|
| 1 | Scalability, latency percentiles | 1 | 10–18 |
| 2 | Partitioning & hot spots | 6 | 203–207 |
| 3 | Hot key vs. many-key reads | 6 | 206 |
| 4 | Replication lag | 5 | 161–167 |
| 5 | Secondary indexes / derived data | 11, 6 | 452–457, 207–211 |
| 6 | Caching & invalidation | 11, 3 | 452–457, 79–83 |
| 7 | Tombstones / soft delete | 3 | 72–76 |
| 8 | Consensus & coordination | 9 | 324–333, 364–370 |
| 9 | Idempotency & delivery guarantees | 11, 8 | 476–478, 279–286 |
| 10 | Backpressure & rate limiting | 11 | 441–444 |
| 11 | Access-pattern-driven data modeling | 2 | 30–39 |

See also `../plans/05-references.md` for the AWS-specific docs and the broader reading list this project uses.
