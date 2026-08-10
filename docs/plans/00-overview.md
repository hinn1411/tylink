# TyLink — AWS Serverless URL Shortener: Overview

## Context

A URL Shortener. It includes:
1. Implement an end-to-end Serverless system
2. Scale the system by utilizing AWS built-in configuration
3. Verify & monitor system behavior under high traffic

The project is split into 3 phases, documented one file per phase in this `plans/` folder:
- `01-imp.md` — build the functional service
- `02-phase2-scaling.md` — research, scale, and resolve challenges
- `03-phase3-load-testing.md` — test and verify at scale
- `04-cicd-and-iac.md` — CI/CD pipeline shape and the SAM-vs-CDK decision
- `05-references.md` — reference docs and reading list

## Decisions Already Made

- **No custom domain** Add Route53 domain later
- **Utilize AWS Free Tier feature** DAX, WAF, and AWS Distributed Load Testing cost real money. These are marked **Optional/Deferred**. We will try them later
- **Budget** 15-20$/month

---

## Original Functional Requirements (as given)

**Authentication & Authorization**:
- [x] Log in by username and password
- [x] Log in by Google

**Core features**:
- [x] Encode a long URL into a shortened version
- [x] Decode/restore shortened URL to original URL
- [ ] Add an expiration time for the encoded URL (Optional)

**Management features**:
- [x] Get all created URLs of a user
- [x] Delete a URL
- [x] Update a URL

## Additional Functional Requirements

| # | Requirement | Why it's needed beyond the original list |
|---|---|---|
| F1 | ~~Custom domain~~ **(deferred)** — use CloudFront domain now. add Route 53 + ACM custom domain later | Keeps the door open to add branding later without re-architecting anything |
| F2 | **Redirect uses HTTP 302 (or 307), never 301** | 301 is cached permanently by browsers — it will silently break your own Update/Delete/Expire features the first time a client caches it |
| F3 | **Pagination on "list all URLs of a user"** | A DynamoDB `Query` naturally paginates via `LastEvaluatedKey`; the API must expose a cursor/next-token, not assume a small fixed page |
| F4 | **Idempotent create endpoint** (client-supplied `Idempotency-Key` header) | Client retries on a flaky connection must not silently create duplicate short links |
| F5 | **Idempotent delete + distinct 410 vs 404 semantics** | Deleting an already-deleted resource should succeed, not error; "gone" vs "never existed" matters once caching is introduced |
| F6 | **Basic abuse protection on create** (simple rate limit + optional domain blocklist) | A public "accept any long URL" endpoint is a classic phishing/spam vector |
| F7 | **API versioning** | Cheap now, expensive to retrofit |
| F8 | **Private URL visibility** — a link can be created as owner-only; a non-owner (or anonymous caller) who probes the correct short code gets 404, never the destination | Supports personal/internal links (e.g. a private doc or dashboard) without bolting on a separate access-control system later |

## Additional Non-Functional Requirements

| # | Requirement | Justification |
|---|---|---|
| N1 | **Explicit latency SLOs** (e.g. redirect p99 < 500ms server-side, CRUD p99 < 500ms) | Gives Phase 3 load-test results a pass/fail meaning instead of just numbers |
| N2 | **Explicit availability target** (e.g. 99.9%) | Anchor for later discussing "what would get us to 99.99%" (Global Tables) without building it |
| N3 | **Least-privilege IAM per Lambda** — one execution role per function, scoped to only what it touches | The 5 Phase 1 Lambdas (plus a 6th async cleanup function added in Phase 2) have very different blast radii (create vs delete vs redirect); a compromised redirect function shouldn't be able to delete data |
| N4 | **Decouple business logic from AWS infrastructure** | The prerequisite for meaningfully unit-testing a Java Lambda handler — without this boundary, "unit test" degenerates into mocking the DynamoDB SDK client directly |
| N5 | **Cost guardrail**: AWS Budgets + SNS alert (~$15–20/mo) | Personal project; DAX/CloudFront/provisioned concurrency/a runaway retry loop can produce a surprise bill |
| N6 | **Observability SLO** (e.g. mean-time-to-detect < 5 min via CloudWatch Alarms) | Turns "add monitoring" into a testable requirement |

---

## AWS-Heavy Tech Stack

**Core (default, free-tier friendly):**

| Addition | Role | Why |
|---|---|---|
| Amazon CloudFront (default `*.cloudfront.net`, no Route 53 yet) | Single entry point in front of API Gateway | Free tier: 1TB out + 10M requests/mo + 2M CloudFront Function invocations, **every month, permanently** (this became an ongoing always-free tier in Dec 2021, not just a 12-month trial). Gives you edge caching, and later a one-line swap to a custom domain, for ~$0 now |
| AWS Systems Manager Parameter Store (SecureString) | Store the Google OAuth client secret | Free (Standard tier), vs Secrets Manager's ~$0.40/secret/mo |
| AWS KMS — one customer-managed key (CMK) | (a) DynamoDB table encryption with a CMK instead of the AWS-owned default; (b) envelope-encrypt `longUrl` for a "private link" bonus feature | ~$1/mo flat — see `02-phase2-scaling.md` for why this is a real, not decorative, use case. Delete the key when done experimenting if you want $0 |
| AWS Lambda Powertools for Java (v2+, not v1 — EOL Dec 2025) | Structured logging, X-Ray helpers, EMF custom metrics, **Idempotency utility** (backed by DynamoDB conditional writes) | Directly implements F4/N4/N6, and Java is first-class supported |
| AWS X-Ray (active tracing) | End-to-end service map: API Gateway → Lambda → DynamoDB | Free tier: 100,000 traces/mo recorded, 1M retrieved |
| CloudWatch Logs/Alarms/Dashboards + **Lambda Insights** extension | CPU/memory visibility (base Lambda metrics don't include this — Lambda Insights is the specific answer) | Directly answers the "CPU/mem" ask in the original stack |
| AWS Budgets + SNS | Cost guardrail (N5) | Free |
| GitHub OIDC provider + scoped IAM deploy role (no long-lived keys) | CI/CD auth | Current AWS-recommended practice, natively supported by `sam pipeline bootstrap` |
| k6 (run locally) | Load testing scripting/dev loop | Free, scriptable, has pass/fail `thresholds` |
| AWS SAM only | IaC | See `04-cicd-and-iac.md` — sufficient for the whole stack |
| DynamoDB Local (Docker) | Integration test layer, offline in CI | Free |

**Optional / Deferred (real cost — switch on briefly for a specific experiment, then tear down):**

| Addition | Why deferred | When to actually use it |
|---|---|---|
| Amazon DynamoDB Accelerator (DAX) | No free tier, ever — bills per node-hour whether used or not; HA needs ≥3 nodes | Only after Phase 2's baseline load test *measures* a real hot-key/read-latency problem that CloudFront edge caching doesn't solve (e.g. per-request side effects needed on every hit) |
| AWS WAF | ~$5/mo per Web ACL + per-rule + per-request cost, no free tier | Optional Phase 2 hardening exercise once the core system works; not required to learn the throttling/rate-limiting *concepts*, which HTTP API's built-in per-route/stage rate & burst limits cover for free (usage plans/API keys are a REST-API-only feature, not available on the HTTP API this project uses) |
| Route 53 hosted zone + custom domain | $0.50/mo hosted zone + domain purchase | Whenever you're ready to brand it — plugs into the existing CloudFront distribution with no re-architecture |
| AWS Distributed Load Testing on AWS (Fargate-based) | Cost per test run (bounded, but not free) | One-time Phase 3 capstone run only, torn down immediately after; k6 run locally is the default/repeated dev-loop tool |
