# Phase 1 — Build the Cloud-Native Serverless Service

Goal: a fully working URL shortener satisfying every functional requirement, deployed on AWS, within free tier. See `00-overview.md` for the full requirement list and tech stack.

## Core Data Model & Algorithm (foundation for everything else)

- **Short-code generation**: random 7-char Base62 string (`SecureRandom`, ~3.5×10^13 combinations) used **as the DynamoDB partition key itself**, written with `PutItem` + condition `attribute_not_exists(PK)`, retrying (rare) on `ConditionalCheckFailedException`. This avoids the classic auto-increment-counter mistake, which creates a single hot write partition (capped ~1,000 WCU/partition) and produces guessable/enumerable codes. (Full reasoning in `02-phase2-scaling.md`.)
- **DynamoDB single-table design** (two access patterns: redirect-by-code, and list-by-user):
  - Base table: `PK = URL#<shortCode>`, `SK = METADATA` — fast point lookup, the hot redirect path.
  - GSI1: `GSI1PK = USER#<userId>`, `GSI1SK = URL#<createdAt>#<shortCode>` — supports paginated, sorted "list all URLs of a user" (F3).
  - Trade-off to know: GSIs are eventually consistent. If you need strong read-your-writes immediately after create on the list view, that's a deliberate reason to consider `TransactWriteItems` across two tables instead — not needed by default.
- **Expiration (bonus)**: store `expiresAt`, enable DynamoDB TTL for storage cleanup, **and** check `expiresAt < now()` explicitly at read time — TTL deletion can lag up to 48 hours, so it's a janitor, not an enforcement mechanism.
- **Soft delete, not hard delete** (required for F5's 410-vs-404 semantics): the delete Lambda sets a `status = DELETED` attribute (+ `deletedAt`) on the item instead of calling `DeleteItem`. The redirect/decode Lambda then returns 410 Gone when it finds an item with `status = DELETED`, and 404 only when no item exists at all — a hard delete can't distinguish these two cases, since both look identical (absent item) on the next lookup. A separate TTL attribute (e.g. `purgeAt`, 30-90 days out) can still garbage-collect soft-deleted items later via DynamoDB TTL.

## Steps

1. Finalize the API contract (6 operations), the DynamoDB schema and short-code algorithm above, and the 302 redirect decision (F2).
2. Scaffold: `sam init`, Maven Java project, layered architecture (`handler` → `service` → `repository`), Lambda Powertools dependency.
3. **Auth**: Cognito User Pool + username/password; Google as a federated IdP (OAuth client in Google Cloud Console, IdP + Hosted UI + callback URLs in Cognito). Use API Gateway's **native JWT/Cognito authorizer** on the protected CRUD routes — zero custom authorizer Lambda code needed. The redirect route stays public/unauthenticated (anyone must be able to click a link without logging in) — a deliberate asymmetric-auth decision.
4. **API Gateway type: HTTP API**, not REST API — native JWT authorizer support, and ~3.5x cheaper per request; reserve REST API only if you later need per-stage native caching or API keys (we get caching from CloudFront instead). Mount all routes under a `/v1` prefix (F7) from day one — cheap now, expensive to retrofit once clients exist.
5. Build 5 Lambdas, each behind its own route:
   - `create` — validates the long URL (optionally checks it against a domain blocklist, F6), generates the short code, writes with `attribute_not_exists(PK)` (collision-avoidance — see Core Data Model above; this is a *different* mechanism from client-retry idempotency). Gate entry with the **Powertools Idempotency utility** keyed on the client's `Idempotency-Key` header (F4) — this must run *before* a new code is generated, or a retried request will just mint a second, different short code for the same logical request.
   - `redirect/decode` — `GetItem`, checks `expiresAt` and `status` (404 if absent, 410 if soft-deleted, otherwise 302/307 redirect)
   - `list` — paginated `Query` on GSI1, with a `FilterExpression` excluding `status = DELETED` items (soft-deleted items still exist in the table, see Core Data Model above — they must not resurface in a user's list)
   - `update`
   - `delete` — soft delete only (sets `status = DELETED`, see Core Data Model above), idempotent (deleting an already-deleted item still returns success)

   A 6th Lambda (async TTL/Streams cleanup) is added in Phase 2, not here — see `02-phase2-scaling.md`, "Async cleanup resilience."
6. CloudFront in front of the HTTP API using its default domain (no Route 53 yet).
7. Local dev/test: `sam local start-api` + DynamoDB Local via docker-compose; JUnit5 + Mockito unit tests against the `UrlRepository` interface (N4 — see `03-phase3-load-testing.md` for the full testing strategy).
8. First deploy: `sam deploy --guided` to a personal dev stack; AWS Budgets alert (N5); a baseline CloudWatch dashboard (5xx rate, DynamoDB throttles).
9. CI/CD v1: GitHub Actions + OIDC, build + unit test + `sam deploy` to dev on push to main (see `04-cicd-and-iac.md`).

## Project Structure to Scaffold

- `template.yaml` — SAM template: Cognito User Pool + Google IdP, DynamoDB table + GSI1, HTTP API + JWT authorizer, the 5 Phase 1 Lambdas with per-function IAM roles, KMS CMK, CloudFront distribution
- `pom.xml` — Maven build, Powertools dependency, shading plugin for the Lambda artifact
- `src/main/java/.../repository/UrlRepository.java` — the interface boundary between handlers and DynamoDB (N4) — get this right first, it's what makes unit testing possible
- `src/main/java/.../model/UrlItem.java` — single-table item model (PK/SK/GSI1PK/GSI1SK mapping)
- `.github/workflows/deploy.yaml` — CI/CD pipeline (OIDC auth, build/test/deploy)

## Verification

Phase 1 is done when: all 6 operations work end-to-end against a deployed dev stack (`sam deploy --guided`), reachable via the CloudFront URL, with Cognito username/password **and** Google login both functional, unit tests passing, and the GitHub Actions pipeline green on a push to main.
