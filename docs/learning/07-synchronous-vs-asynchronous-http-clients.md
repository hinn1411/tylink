# Synchronous vs. Asynchronous HTTP Clients

Apache (`ApacheHttpClient`) and Netty (`netty-nio-client`) are both on this
project's classpath — see `mvn dependency:tree`, both resolve as transitive
dependencies of `software.amazon.awssdk:dynamodb`. Only Apache is actually
used (`DynamoDbClient`, the synchronous client `ShortenUrlHandler` calls);
Netty backs `DynamoDbAsyncClient`, which nothing in this codebase uses.

---

## 1. Core difference

| | Synchronous (Apache) | Asynchronous (Netty) |
|---|---|---|
| Call model | Blocks the calling thread until the response arrives | Returns a `CompletableFuture` immediately; response delivered via an event-loop callback |
| Concurrency mechanism | One OS thread per in-flight request | A small pool of event-loop threads multiplexes many in-flight requests |
| Code shape | Plain sequential code | Callback/`CompletableFuture` chains |

## 2. In this project

```java
DynamoDbClient.create()        // sync — used by ShortenUrlHandler
DynamoDbAsyncClient.create()   // async — unused; nothing in src/ references it
```

`DynamoDbClient.create()` resolves to `ApacheHttpClient` because it's the
default sync implementation SDK v2 picks up when present on the classpath
([docs][sdk-http-clients]).

## 3. When each wins (general, not project-specific)

**Sync (thread-per-request) wins when:**
- The calling code is already sequential/blocking (matches this codebase).
- Load is naturally bounded per process (e.g., one Lambda invocation = one
  in-flight call — see `06-lambda-execution-environments-and-connection-reuse.md`).
- Simplicity and debuggability matter more than raw throughput.

**Async (event-loop) wins when:**
- One process must hold thousands of concurrent connections — a
  thread-per-connection model runs out of OS threads/memory long before an
  event loop does.
- A single unit of work needs to fan out N independent calls in parallel
  and combine results, without blocking a thread per call.

## 4. Why async doesn't help `ShortenUrlHandler` specifically

Each invocation makes exactly one downstream call (`putItem`). Async's whole
value proposition — many threads' worth of concurrent I/O multiplexed onto
a few event-loop threads — has nothing to multiplex here: no concurrent
calls within one invocation, and Lambda already achieves concurrency across
invocations by adding *environments*, not by juggling requests within one
(§1 of `06-...md`). Netty would only earn its keep if a handler needed to
fire off several parallel AWS calls (e.g., a DynamoDB write + an S3 put) and
join the results — that's a fan-out shape none of this codebase's handlers
have today.

## References

- [AWS SDK for Java v2 — configuring an HTTP client][sdk-http-clients]
- [AWS SDK for Java v2 — async programming][sdk-async]
- [Netty project][netty]
- [`java.nio.channels.Selector` (the JDK's own non-blocking I/O primitive)][java-nio-selector]

[sdk-http-clients]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration.html
[sdk-async]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/asynchronous.html
[netty]: https://netty.io/wiki/user-guide-for-4.x.html
[java-nio-selector]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Selector.html

## Interview questions

<details>
<summary>What's the fundamental difference between a blocking and a non-blocking HTTP client?</summary>

Blocking: the calling thread is parked until the response arrives — one
thread per in-flight request. Non-blocking: the thread issues the request
and returns immediately; a small event-loop thread pool later delivers the
response via callback/future, so many in-flight requests share few threads.
</details>

<details>
<summary>Why can Netty handle far more concurrent connections per process than Apache's blocking client?</summary>

Apache ties up one OS thread per in-flight request — threads are expensive
(stack memory, context-switch cost), so thread-per-connection caps out in
the thousands. Netty's event loop uses non-blocking sockets (`Selector`),
so a handful of threads can service thousands of concurrent connections
without blocking on any single one.
</details>

<details>
<summary>Given a Lambda function that makes one DynamoDB call per invocation, would switching it to <code>DynamoDbAsyncClient</code> improve throughput? Why or why not?</summary>

No. Throughput here is bound by Lambda's concurrency model (more
environments = more throughput), not by thread exhaustion within one
process — there's no concurrent I/O within a single invocation for an event
loop to multiplex. Async adds callback complexity with no throughput gain
in this shape. It would help if a single invocation needed to fan out
several independent calls in parallel.
</details>

<details>
<summary>What's a scenario where switching this same handler to the async client <em>would</em> pay off?</summary>

If one invocation needed to issue multiple independent downstream calls
concurrently — e.g., writing to DynamoDB and putting an object to S3 in
parallel, then joining both results — async lets both fire without one
blocking a thread while waiting on the other.
</details>

<details>
<summary>Both <code>apache-client</code> and <code>netty-nio-client</code> are on this project's classpath even though only one is used. Why, and is that a problem?</summary>

`software.amazon.awssdk:dynamodb` declares both as transitive runtime
dependencies so either `DynamoDbClient` (sync) or `DynamoDbAsyncClient`
(async) works out of the box. Only Apache is actually invoked here, so
Netty is dead weight — it inflates the deployed jar and Lambda cold-start
init cost for no benefit, and could be excluded via a Maven `<exclusion>`
if that cost mattered.
</details>
