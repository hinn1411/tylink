# Rate Limiting and AWS API Gateway Throttling

How rate limiting works as a concept, and precisely how it's enforced in AWS API Gateway for an
HTTP API — prep reading for F6 (`../plans/00-overview.md`: "basic abuse protection on create"),
not an implementation guide. Read `01-system-design-concepts.md` §10 first for the one-paragraph
framing this doc expands.

---

## 1. Rate limiting as backpressure

When a producer sends work faster than a consumer can process it, something has to give: buffer
it, drop it, or push back — **backpressure**. **Rate limiting** is the deliberate, edge-applied
form of backpressure, meant to protect against bursts or abuse rather than smooth organic load.

## 2. The two algorithms that matter here

- **Token bucket**: a bucket holds up to `burst` tokens and refills at `rate` tokens/second; each
  request consumes one token; an empty bucket rejects. Tolerates short bursts above the steady
  rate. **This is what API Gateway uses.**
- **Sliding window**: counts requests continuously over a rolling window instead of resetting at
  a fixed boundary, avoiding the "up to 2× at the boundary" flaw a naive fixed-window counter has.
  **This is what AWS WAF's rate-based rules use.**

## 3. HTTP API throttling — the actual mechanics

API Gateway's throttle is a token bucket, evaluated per account per Region: default **10,000
requests/second steady-state, 5,000 burst** — one bucket shared across every HTTP API, REST API,
and WebSocket API in that account+Region, not a separate budget per API. A stage or route override
(`ThrottlingRateLimit`/`ThrottlingBurstLimit`) can tighten this for one route, but never raise it
above the account ceiling. A rejected request gets **HTTP 429**,
`{"message":"Too Many Requests"}`, entirely inside API Gateway — before it ever reaches a Lambda
authorizer or function, which is why a throttled burst leaves no trace in Lambda logs or traces,
only in an API Gateway access log if one is configured.

TyLink's `HttpApi` has no throttle config today — all five `/v1/*` routes share the single account
bucket.

REST APIs additionally support **usage plans + API keys** — a per-client throttle and quota tied
to an API key. HTTP API has no such feature; `../technical_decisions/13-xray-http-api-vs-rest-api.md`
accepted that gap when choosing HTTP API for tracing reasons.

## 4. Worked example: the account bucket can't see one abusive client

One client hammering the create route at a sustained 200 requests/second is under 2% of the
10,000/second refill rate — the bucket never drains, and API Gateway's own throttle never trips.
**It exists to protect API Gateway's own capacity, not to detect which client is misbehaving** —
it has no concept of caller identity at all.

Contrast a WAF rate-based rule at, say, 1,000 requests per 5-minute window keyed on source IP: the
same 200 requests/second reaches that threshold in 5 seconds. Same traffic, invisible to one
layer, caught almost immediately by the other — because only the second layer keys on caller
identity.

## 5. AWS WAF rate-based rules — and where they can attach

A WAF rate-based rule counts requests per client over a rolling window (60/120/300-default/600
seconds), minimum threshold 10 requests, keyed on source IP by default. It evaluates before any
Lambda/Cognito authorizer runs.

**Structural constraint: a WAF Web ACL cannot attach directly to an HTTP API at all** — confirmed
via the CloudFormation `WebACLAssociation` resource's supported target list (ALB, REST API,
AppSync, Cognito pool, App Runner, Amplify, Verified Access — no HTTP API) and AWS's
WAF-for-API-Gateway guide, which is REST-API-only throughout. For CloudFront, the Web ACL is
instead set directly on the distribution's own config.

For TyLink, that makes **CloudFront** — already the stack's entry point, see
`../technical_decisions/15-cloudfront-edge-caching.md` — the only place a Web ACL could attach. It
has no WAF association today. Cost: ~$5/month per Web ACL, no free tier, which is why it's in
`../plans/00-overview.md`'s Optional/Deferred column.

## 6. TyLink's actual gap, and the two-layer shape of the answer

F6 is unimplemented — no throttle config, no WAF, anywhere in the stack.

- **Layer 1 (free, today)**: a per-route throttle override on the create route. Tunable
  independently of other routes, but still account/route-scoped — can't distinguish one abusive
  client (§3, §4).
- **Layer 2 (paid, deferred)**: a WAF rate-based rule on CloudFront, keyed on source IP — adds
  exactly the per-caller distinction Layer 1 can't provide.

F6's other half, an optional domain blocklist, is a separate application-level content check, not
a rate-limiting concern.

## Where this connects

`01-system-design-concepts.md` §10 is the seed this doc expands.
`../technical_decisions/13-xray-http-api-vs-rest-api.md` names the usage-plans gap §3 explains.
`../technical_decisions/15-cloudfront-edge-caching.md` establishes CloudFront as §5's WAF
attachment point. `../plans/00-overview.md` defines F6 and the WAF cost reasoning.

## References

- HTTP API throttling: https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-throttling.html
- API Gateway quotas: https://docs.aws.amazon.com/apigateway/latest/developerguide/limits.html
- Usage plans and API keys: https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-api-usage-plans.html
- Use AWS WAF to protect your REST APIs: https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-control-access-aws-waf.html
- WAF rate-based rule settings: https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-rate-based-high-level-settings.html
- CloudFormation `AWS::WAFv2::WebACLAssociation`: https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-wafv2-webaclassociation.html
