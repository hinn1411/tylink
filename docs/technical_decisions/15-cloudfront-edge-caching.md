# Decision: CloudFront Redirect Caching Is Gated on Visibility, Not Just Status Code

## Context

CloudFront now fronts `HttpApi` (`infrastructures/cloudfront.yaml`), with a single ordered cache
behavior on `/v1/urls/*`. The goal is caching `GET /v1/urls/{shortCode}`'s 307 response at the
edge, since it removes Lambda and DynamoDB from the hot path entirely on a cache hit — the
idiomatic first move for a URL shortener's read scaling (`docs/plans/06-system-design-deep-dive.md`).

The redirect response isn't always caller-independent, though. `RedirectUrlHandler` treats a
`PRIVATE` URL differently depending on who's asking: the owner gets a 307, everyone else gets the
same 404 as a nonexistent short code (by design — private-URL existence isn't supposed to leak).
CloudFront's cache is a shared cache: whatever gets stored for a given path is served to the next
caller who hits it, regardless of who they are.

## Root cause

If a `PRIVATE` URL's successful redirect were cached, the first (owner) request would populate
the edge cache with the real `Location`, and the next caller to request that same short code —
anonymous, or a different authenticated user — would receive the owner's private redirect target
straight from the cache, without the authorization check in `RedirectUrlHandler` ever running
again. Caching correctness for this route isn't just about freshness; it's a data-exposure
question.

## Decision

- `RedirectUrlHandler` only sets `Cache-Control: public, max-age=<TIME_TO_LIVE>`
  when `shortUrl.visibility() == Visibility.PUBLIC`. A `PRIVATE` URL's successful response carries
  no `Cache-Control` header at all, `TIME_TO_LIVE` is `300` (5 minutes).
- No other response path (404 not-found, 404 private-non-owner, 410 gone, 500 lookup-failed) sets
  any cache header. `RedirectCachePolicy`'s `DefaultTTL: 0` is the backstop: any response with no
  `Cache-Control`/`Expires` header defaults to a 0-second TTL, so none of these paths need to be
  special-cased in code to stay uncached — the absence of a header is itself the "don't cache"
  signal. `MaxTTL: 300` on the same policy caps whatever the origin sends, so a future change to
  the env var can't silently push the real TTL past what the cache policy allows.
- Freshness vs. Update/Delete is resolved as **short TTL only, no invalidation call** from
  `UpdateUrlHandler`/`DeleteUrlHandler` — an updated or deleted `PUBLIC` URL can serve its old
  target for up to 5 minutes after the write. This was one of two options
  `docs/plans/06-system-design-deep-dive.md` already sanctioned ("short max-age + explicit
  invalidation... or accept bounded staleness as a documented trade-off"); TTL-only was chosen
  because it needs no new IAM permission, no CloudFront SDK dependency, and no
  failure-handling decision for a best-effort invalidation call. Revisit with explicit
  invalidation only if 5 minutes of staleness turns out to matter in practice.
- Every other route (`/v1/auth/login`, `POST /v1/urls`, `GET /v1/urls`) sits behind CloudFront's
  managed `CachingDisabled` policy — always a pass-through, regardless of any header they set.
- All cache behaviors use the managed `AllViewerExceptHostHeader` origin request policy: it
  forwards `Authorization` (required — both the custom Lambda authorizer and the native JWT
  authorizer need it) while letting CloudFront set the `Host` header to the API Gateway origin
  domain, which `HttpApi` requires since it has no custom domain of its own.
