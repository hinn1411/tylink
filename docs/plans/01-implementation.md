# Phase 1 — Build the Cloud-Native Serverless Service

Goal: a fully working URL shortener satisfying every functional requirement, deployed on AWS, within free tier. See `00-overview.md` for the full requirement list and tech stack.

## Short-Code Generation Algorithm (foundation for everything else)

- **Short-code generation**: random 7-char Base62 string (`SecureRandom`, ~3.5×10^13 combinations) used **as the DynamoDB partition key itself**, written with `PutItem` + condition `attribute_not_exists(PK)`, retrying (rare) on `ConditionalCheckFailedException`. This avoids the classic auto-increment-counter mistake, which creates a single hot write partition (capped ~1,000 WCU/partition) and produces guessable/enumerable codes. (Full reasoning in `02-phase2-scaling.md`.)

## Core Data Model

- **DynamoDB single-table design** (two access patterns: redirect-by-code, and list-by-user):
  - Base table: `PK = URL#<shortCode>`, `SK = METADATA` — fast point lookup, the hot redirect path.
  - GSI1: `GSI1PK = USER#<userId>`, `GSI1SK = URL#<createdAt>#<shortCode>` — supports paginated, sorted "list all URLs of a user" (F3).
  - Trade-off to know: GSIs are eventually consistent. If you need strong read-your-writes immediately after create on the list view, that's a deliberate reason to consider `TransactWriteItems` across two tables instead — not needed by default.
- **Expiration (bonus)**: store `expiresAt`, enable DynamoDB TTL for storage cleanup, **and** check `expiresAt < now()` explicitly at read time — TTL deletion can lag up to 48 hours, so it's a janitor, not an enforcement mechanism.
- **Soft delete, not hard delete** (required for F5's 410-vs-404 semantics): the delete Lambda sets a `status = DELETED` attribute (+ `deletedAt`) on the item instead of calling `DeleteItem`. The redirect/decode Lambda then returns 410 Gone when it finds an item with `status = DELETED`, and 404 only when no item exists at all — a hard delete can't distinguish these two cases, since both look identical (absent item) on the next lookup. A separate TTL attribute (e.g. `purgeAt`, 30-90 days out) can still garbage-collect soft-deleted items later via DynamoDB TTL.
- **Private URL visibility** (F8): every base-table item keeps the exact same shape (`PK=URL#<shortCode>`, `SK=METADATA`), plus a `visibility` attribute (`PUBLIC` | `PRIVATE`). `create` never requires auth for `PUBLIC` links — anonymous callers can shorten a URL with no `ownerId` at all, and such links simply can't appear in a future "list a user's URLs" query. `PRIVATE` links do require auth, since the ownership check below needs a real `ownerId` to compare against; `ownerId` (and the GSI1 key pair) are only present on items created by an authenticated caller. One `redirect/decode` Lambda handles both visibilities:
  - `visibility=PUBLIC` — redirect for any caller, authenticated or not (unchanged from today).
  - `visibility=PRIVATE` — the caller's identity must match `ownerId`, or the Lambda returns **404** (never 403 — a 403 would confirm the code exists, which is itself a leak). Since this route must stay reachable by anonymous callers for public links, it can't carry a hard API Gateway JWT authorizer; instead the Lambda optionally parses an `Authorization` header if present and treats a missing/invalid token as "no owner match" for private items.
  - Keeping one item shape (rather than a different `SK` per visibility) means the soft-delete and TTL logic above applies identically to public and private links — no forked 404/410 logic to maintain.

## Steps

1. Finalize the API contract (6 operations), the DynamoDB schema and short-code algorithm above, and the 302 redirect decision (F2).
2. Scaffold: `sam init`, Maven Java project, layered architecture (`handler` → `service` → `repository`), Lambda Powertools dependency.
3. **Auth**: Cognito User Pool + username/password; Google as a federated IdP (OAuth client in Google Cloud Console, IdP + Hosted UI + callback URLs in Cognito). Routes that *always* require a caller can use API Gateway's **native JWT/Cognito authorizer** (zero custom code). `create` can't — it must stay reachable by anonymous callers (F8 allows anonymous `PUBLIC` creates), and a native JWT authorizer is all-or-nothing per route with no "optional" mode. It instead sits behind a custom **Lambda authorizer** (`ExtractTokenAuthorizerHandler`) that never denies a request: it verifies the token when one's present (against the User Pool's JWKS) and passes the caller's id through in the authorizer context, or an empty context when there's none. `redirect/decode` has the identical "must stay open to anonymous callers" problem for public links (see below) and is a natural candidate for the same authorizer later, though it isn't wired up yet. See `docs/technical_decisions/04-dynamodb-access-patterns.md`.
4. **API Gateway type: HTTP API**, not REST API — native JWT authorizer support, and ~3.5x cheaper per request; reserve REST API only if you later need per-stage native caching or API keys (we get caching from CloudFront instead). Mount all routes under a `/v1` prefix (F7) from day one — cheap now, expensive to retrofit once clients exist.
5. Build 5 Lambdas, each behind its own route:
   - `create` — validates the long URL (optionally checks it against a domain blocklist, F6), generates the short code, writes with `attribute_not_exists(PK)` (collision-avoidance — see Short-Code Generation Algorithm above; this is a *different* mechanism from client-retry idempotency). Gate entry with the **Powertools Idempotency utility** keyed on the client's `Idempotency-Key` header (F4) — this must run *before* a new code is generated, or a retried request will just mint a second, different short code for the same logical request.
   - `redirect/decode` — `GetItem`, checks `expiresAt`, `status`, and `visibility` (404 if absent, 410 if soft-deleted, 404 if private and caller doesn't match `ownerId`, otherwise 302/307 redirect). See "Private URL visibility" above.
   - `list` — paginated `Query` on GSI1, with a `FilterExpression` excluding `status = DELETED` items (soft-deleted items still exist in the table, see Core Data Model above — they must not resurface in a user's list)
   - `update` — `UpdateItem` on the existing `(PK, SK)` item, touching only `longUrl` (and `expiresAt` if editable). Keeps the short code and `createdAt` stable — no delete-and-recreate: that would mint a new code (breaking already-shared links), isn't atomic, and would shift the item's position in GSI1's sort order mid-pagination.
   - `delete` — soft delete only (sets `status = DELETED`, see Core Data Model above), idempotent (deleting an already-deleted item still returns success)

   A 6th Lambda (async TTL/Streams cleanup) is added in Phase 2, not here — see `02-phase2-scaling.md`, "Async cleanup resilience."
6. CloudFront in front of the HTTP API using its default domain (no Route 53 yet).
7. Local dev/test: `sam local start-api` + DynamoDB Local via docker-compose; JUnit5 + Mockito unit tests against the `UrlRepository` interface (N4 — see `03-phase3-load-testing.md` for the full testing strategy).
8. CI/CD v1: GitHub Actions + OIDC, build + unit test + `sam deploy` to dev on push to main (see `04-cicd-and-iac.md`).

## Verification
- All APIs access via CloudFront URL
- Unit tests & Integration tests pass
- CI/CD trigger (Optional)
