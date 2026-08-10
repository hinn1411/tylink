# Testing Concurrent Code

`ShortenUrlIdempotencyIT`'s class Javadoc scopes out testing the
`IdempotencyAlreadyInProgressException` race ("both need real time/thread
orchestration disproportionate to this test"). This note is the resource
list and worked example for when that tradeoff *does* look proportionate —
testing a race condition deterministically instead of relying on chance
timing.

---

## 1. Why naive concurrent tests are flaky

Firing two threads (or two `curl` requests) at the same code path doesn't
reliably reproduce a race: most of the time, the first call finishes —
writes its result, marks state `DONE` — before the second one even starts.
The race window (the gap between "marked in-progress" and "marked done") is
often microseconds wide. A test — or manual repro — built on hoping two
calls land inside that window is non-deterministic: it passes on your
machine, fails in CI, or vice versa.

## 2. The technique: force the interleaving

Instead of hoping for the right timing, pin one thread inside the race
window with a synchronization primitive, then release the second thread
once you know the first is parked there.

```java
CountDownLatch releaseThread1 = new CountDownLatch(1);
UrlRepository blockingRepo = url -> {
    releaseThread1.await();      // t1 parks here — the idempotency record
    realRepo.save(url);          // is already marked INPROGRESS, but t1
};                                // hasn't completed yet
ShortenUrlHandler blockedHandler = new ShortenUrlHandler(blockingRepo);

Thread t1 = new Thread(() -> blockedHandler.handleRequest(event, null));
t1.start();
awaitIdempotencyRecordIsInProgress();  // poll DynamoDB Local, not Thread.sleep
APIGatewayV2HTTPResponse second = blockedHandler.handleRequest(event, null);

releaseThread1.countDown();
t1.join();

assertEquals(409, second.getStatusCode());
```

Two things make this deterministic rather than flaky:
- **A blocking point** (`CountDownLatch`, `CyclicBarrier`, or a `Semaphore`)
  that pins thread 1 exactly inside the race window, instead of letting it
  run to completion.
- **A poll, not a sleep**, to know when it's safe to fire thread 2 —
  `Thread.sleep(100)` guesses at timing; polling for the actual state (an
  `INPROGRESS` record present) waits for the real precondition.

## 3. Where to draw the test boundary

Not everything reachable by a race is yours to test. The `INPROGRESS`
bookkeeping in this codebase — the conditional write that decides whether a
second caller collides with a live execution — lives inside
`DynamoDBPersistenceStore` (AWS Lambda Powertools), not in
`ShortenUrlHandler`. Powertools' own test suite already covers that logic.

What's actually tylink's to verify is usage correctness: is `@Idempotent`
on the right method, is the JMESPath config (`idempotencyKey`, `longUrl`)
pointed at the right fields, is the table wired up. `ShortenUrlIdempotencyIT`'s
two existing tests (`sameKeySameLongUrl_replaysSameShortCode`,
`sameKeyDifferentLongUrl_returns409`) already prove that wiring end-to-end
without needing to force the race — they exercise the same code path
sequentially, which is enough to show the annotation and config are
connected correctly.

## 4. Manual/exploratory verification (curl, load-test tools)

Firing concurrent `curl`/Postman requests hits the same timing problem as a
naive two-thread test — a fast DynamoDB `PutItem` usually completes before
a second HTTP request lands, so you likely won't observe `INPROGRESS` even
if you fire requests "at the same time" from a shell script. To actually
see it:

- Use a tool built for true concurrency, not sequential scripting —
  `hey -c 2 -n 2`, `vegeta`, `ab -c 2 -n 2`, or GNU `parallel` firing
  simultaneously — rather than two backgrounded `curl &` calls, which still
  have OS-scheduling skew.
- Widen the race window on purpose: temporarily add a `Thread.sleep(...)`
  inside the idempotent method (or point at a deliberately slow endpoint)
  so there's enough time for both requests to land while the first is
  still `INPROGRESS`. Remove it afterward — this is exploratory, not
  something to ship.

Even then, this is a one-off manual check, not a repeatable regression
test — it answers "does this behave as expected right now" but won't catch
a future regression the way an automated test would. That gap is exactly
why §2's latch-based approach is worth reaching for if this needs to become
an actual, permanent test.

## References

- Brian Goetz, *Java Concurrency in Practice*, Chapter 12: "Testing
  Concurrent Programs"
- Martin Fowler, ["Eradicating Non-Determinism in Tests"][fowler-nondeterminism]
- [Awaitility][awaitility] — polling assertions instead of hand-rolled poll
  loops or sleeps
- [`java.util.concurrent.CountDownLatch`][countdownlatch] /
  [`CyclicBarrier`][cyclicbarrier] Javadoc
- [JCStress][jcstress] (OpenJDK) — lower-level memory-model/race testing,
  for raw concurrent data structures rather than a single business-logic
  race
- [AWS Lambda Powertools — Idempotency utility][powertools-idempotency] —
  docs for the `INPROGRESS`/conditional-write behavior this note is scoped
  around

[fowler-nondeterminism]: https://martinfowler.com/articles/nonDeterminism.html
[awaitility]: https://github.com/awaitility/awaitility
[countdownlatch]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CountDownLatch.html
[cyclicbarrier]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CyclicBarrier.html
[jcstress]: https://github.com/openjdk/jcstress
[powertools-idempotency]: https://docs.powertools.aws.dev/lambda/java/utilities/idempotency/

## Interview questions

<details>
<summary>Why does firing two threads (or two curl requests) at the same endpoint usually fail to reproduce a race condition?</summary>

The race window — the gap between a resource being marked "in progress" and
marked "done" — is often microseconds wide. Without deliberately pausing
one execution inside that window, the first call usually completes before
the second one starts, so the test passes by accident rather than proving
the race is handled. This makes such tests non-deterministic — they can
pass or fail depending on machine load, JIT warmup, or scheduling.
</details>

<details>
<summary>What's wrong with using <code>Thread.sleep()</code> to coordinate two threads in a concurrency test?</summary>

A sleep guesses at timing rather than waiting for an actual precondition —
it either wastes time (if the condition is already true) or fails
intermittently under load (if the sleep isn't long enough). Polling for
observable state (e.g., an idempotency record showing `INPROGRESS`) or
using a synchronization primitive (`CountDownLatch`) waits for the real
precondition instead of a guess.
</details>

<details>
<summary>Should a test verify that AWS Lambda Powertools' <code>@Idempotent</code> correctly detects concurrent in-progress requests?</summary>

No — that logic lives inside Powertools' `DynamoDBPersistenceStore`, and
Powertools' own test suite already covers it. tylink's tests should verify
tylink's usage is wired correctly (annotation placement, JMESPath config,
table setup), which the existing replay/conflict tests already do without
needing to force a race.
</details>

<details>
<summary>You need to prove a specific race condition is handled correctly, and it must be a repeatable, deterministic test — not a one-off manual check. What's the general pattern?</summary>

Pin one thread inside the race window with a synchronization primitive
(e.g. inject a `CountDownLatch`-based blocking stub at the point you want
to pause), start it, poll for the observable state that proves it's parked
in the window (not a sleep), then trigger the second execution and assert
on its outcome, finally releasing the first thread. This forces the
interleaving instead of hoping for it.
</details>
