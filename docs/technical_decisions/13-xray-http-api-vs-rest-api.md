# Decision: Keep HTTP API, Not REST API, for Authorizer Tracing

## Context

We wanted visibility into how long a custom Lambda authorizer takes on a *specific* slow request,
not just its average latency. AWS X-Ray can automatically correlate an API Gateway request with
everything it triggers — including a custom authorizer — into a single trace, but only for REST
APIs; HTTP APIs have never supported this, and still don't today. Migrating from HTTP API to REST
API looked like the direct way to get that correlation.

## Options considered

1. **Migrate to REST API (rejected).**
   - REST API costs roughly 3.5x more per request than HTTP API, and API Gateway's free tier only
     covers the first 12 months of an AWS account's life, not an ongoing allowance.
   - Custom Lambda authorizers work differently between the two API types: REST API doesn't
     support the simplified true/false authorization response HTTP API offers, only a full IAM
     policy document — the authorizer's response logic would need rewriting.
   - REST API has no equivalent of HTTP API's built-in, configuration-only JWT validation — routes
     that currently get token verification for free would need a second hand-written authorizer.
   - Every backend function's request/response shape differs between the two API types, so every
     function reading the incoming request would need updating.
   - REST API has historically higher baseline latency than HTTP API — a regression risk for a
     latency-sensitive route.
   - One independent upside, not the deciding factor: REST API supports per-client request quotas,
     which HTTP API doesn't — a separate, already-known gap unrelated to this tracing question.
2. **Do nothing.** The authorizer is a Lambda function like any other, so it already has its own
   trace and its own latency dashboard — this answers "how slow is the authorizer generally," not
   "what did it do for this one slow request."
3. **Have the authorizer hand its own trace ID to the request it authorizes (chosen).** A small,
   targeted change that gets exact per-request correlation without the cost of migrating.

## Decision

Keep HTTP API. Implement option 3.

## Workaround: how it works

Confirmed directly, not assumed: one request that passes through the authorizer and then its
backend function produces two separate, unrelated traces — HTTP API doesn't link them. There's no
automatic way to jump from one to the other, so the workaround makes that lookup possible by hand:

1. The authorizer reads its own trace ID (available to any Lambda invocation with tracing enabled)
   and includes it in the small payload it already returns to grant access.
2. That payload is forwarded to the backend function as part of the incoming request, the same way
   the caller's verified identity already is.
3. The backend function already logs its full incoming request, so the authorizer's trace ID ends
   up in its logs automatically — no extra logging code needed.
4. To investigate a specific slow request: find the authorizer's trace ID in that request's logs,
   then look that trace up directly.

**What this doesn't give us:** a single view showing the authorizer and its backend request as one
timeline. That still requires REST API and is the accepted trade-off — this workaround provides
exact correlation through a manual second lookup instead of one automatic combined view.
