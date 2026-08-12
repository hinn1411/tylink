# Decision: EMF Metrics (Cold Start, Idempotency Miss, Short-Code Collision Retries)

## Context

Select EMF metrics to monitor important states of the system

## `IdempotencyMiss`: no matching "hit" metric

Miss rate drives real DynamoDB write cost and load-test capacity because it will touch DB. It's also a health signal: near-zero suggests a client bug reusing one key for separate
creates; staying high under retry load suggests dedup isn't working (TTL too short, keys not
reused).

We measure miss rate because Powertools' Idempotency module has no metrics
integration, and only runs the `@Idempotent` method body on a miss — a hit returns the cached
response without executing it. The call is the first line for the same reason: reaching it proves
a miss, and putting it first to prevent early return in the future.

```java
@Idempotent
APIGatewayV2HTTPResponse createUrlIdempotent(IdempotentCreateRequest request) {
    metrics.addMetric("IdempotencyMiss", 1, MetricUnit.COUNT); // a hit never reaches this line
    ...
}
```

Hit rate is derived in CloudWatch instead of tracked directly:

```
hitRate = 1 - (IdempotencyMiss / Invocations)
```

## `ShortCodeCollisionRetries`
Short code generation can collide. In that case, we want to monitor user attempts

```java
while (!saved && attempts < MAX_SHORT_CODE_ATTEMPTS) {
    shortUrl = ShortUrl.create(ShortCodeUtils.generate(), ...);
    saved = urlRepository.save(shortUrl);
    attempts++;
}
metrics.addMetric("ShortCodeCollisionRetries", attempts - 1, MetricUnit.COUNT);
```

`MAX_SHORT_CODE_ATTEMPTS = 3` mirrors Powertools' own `IdempotencyHandler.MAX_RETRIES = 2` — not a
collision-probability calculation. At 7-char base62 (~3.5×10^13 combinations) this should almost
never fire; the metric confirms that empirically.
