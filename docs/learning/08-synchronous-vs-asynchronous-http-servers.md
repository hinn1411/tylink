# Synchronous vs. Asynchronous HTTP Servers

Complement to `07-synchronous-vs-asynchronous-http-clients.md` — same distinction,
server side. General systems knowledge, not TyLink-specific: this project has no
long-running HTTP server (API Gateway + Lambda replace this whole layer — see §5),
but the concepts underpin most traditional backend services.

---

## 1. What a server does per request

Accept a connection → read the request → do work (often blocking on I/O: a DB
query, a downstream call, disk) → write the response. The design question is:
**what does a server's threads do while waiting on that I/O?**

## 2. Thread-per-request (blocking) model

One OS thread owns a connection for its entire lifetime, including while
blocked waiting on slow I/O. Examples: Apache HTTPD's prefork/worker MPM,
classic servlet containers, Ruby's Puma, Python's sync `gunicorn` workers.

- **Pros**: simple, linear code; a stack trace shows exactly what a request
  is doing; safe default for CPU-bound work.
- **Cons**: doesn't scale to many *concurrently open but mostly idle* connections
  — OS threads cost real memory (typically ~1 MB of stack each) and
  context-switch time. This is the **C10K problem**: past a few thousand
  concurrent connections, a thread-per-connection server runs out of threads
  before it runs out of CPU or bandwidth ([Kegel, 1999][c10k]).

## 3. Event-loop (async / non-blocking) model

A small, fixed number of threads use non-blocking sockets plus an OS
readiness-notification mechanism (`select`/`poll`/`epoll`/`kqueue`/IOCP) to
service thousands of connections — a thread only does work when a socket is
actually ready, never sitting blocked on an idle one. Examples: Nginx
(event-driven worker processes), Node.js (single-threaded event loop +
libuv), Netty, Vert.x.

- **Pros**: handles C10K-and-beyond concurrency with a tiny, fixed thread
  count and low per-connection memory.
- **Cons**: one slow or accidentally-blocking call on an event-loop thread
  stalls *every* connection sharing that loop — "never block the event
  loop" is the cardinal rule in Node.js/Netty. Async/callback code is
  harder to reason about and to get a coherent stack trace from.

**Netty's specific model** ([user guide][netty]): a "boss" `EventLoopGroup`
accepts incoming connections; a "worker" `EventLoopGroup` handles the I/O of
already-accepted connections. Genuinely blocking work (a JDBC call, disk I/O)
must be explicitly offloaded to a separate thread pool — running it directly
on an event-loop thread reintroduces the exact blocking problem the model
exists to avoid.

## 4. A third model: cheap threads (Java virtual threads, Go goroutines)

Virtual threads (Java 21, [JEP 444][jep444], standard/final — not a preview
feature) are instances of `java.lang.Thread` that **aren't tied to a specific
OS thread**. The JVM multiplexes many virtual threads onto a small pool of
OS ("carrier") threads; when a virtual thread blocks on I/O, the JVM
suspends *it* and frees the carrier thread for other virtual threads. Go's
goroutines work on the same underlying idea (an M:N scheduler over OS
threads).

The pitch: **write plain blocking, thread-per-request-looking code, get
event-loop-like scalability** — no callbacks, no `CompletableFuture` chains.
This is the strongest current argument against reaching for an async
framework by default: if your runtime supports cheap virtual/green threads,
you often don't need the reactor model's complexity to get its scalability.

## 5. Where TyLink sits

TyLink has no server process at all — API Gateway terminates HTTP and
invokes Lambda per request; there's no thread pool or event loop in this
codebase to choose between. The closest analog is Lambda's own concurrency
model (`06-lambda-execution-environments-and-connection-reuse.md`): one
execution environment handles one invocation at a time, and concurrency
comes from AWS provisioning more environments — conceptually closer to
"thread/process-per-request," except AWS manages the scaling, not
application code.

## 6. Real servers, at a glance

| Server | Model |
|---|---|
| Apache HTTPD (prefork/worker MPM) | Thread/process-per-connection |
| Nginx | Event-driven worker processes (epoll/kqueue) |
| Node.js | Single-threaded event loop (libuv) |
| Netty | Multi-reactor: boss/worker `EventLoopGroup`s |
| Go `net/http` | Goroutine-per-request (M:N scheduler, not OS thread-per-request) |
| Java (post-JDK 21, using virtual threads) | Thread-per-request code, virtual-thread scheduling underneath |

## References

- [The C10K problem (Dan Kegel)][c10k]
- [Netty user guide — threading model][netty]
- [JEP 444: Virtual Threads][jep444]
- [Oracle — Virtual Threads guide][oracle-vthreads]
- [Node.js — the event loop][node-event-loop]

[c10k]: http://www.kegel.com/c10k.html
[netty]: https://netty.io/wiki/user-guide-for-4.x.html
[jep444]: https://openjdk.org/jeps/444
[oracle-vthreads]: https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
[node-event-loop]: https://nodejs.org/en/learn/asynchronous-work/event-loop-timers-and-nexttick

## Interview questions

<details>
<summary>What is the C10K problem, and what architectural change solved it?</summary>

The observation (Dan Kegel, 1999) that a thread/process-per-connection
server can't scale past roughly 10,000 concurrent connections on one
machine — thread stack memory and context-switch overhead dominate long
before CPU or bandwidth do. Event-driven servers (non-blocking sockets +
`epoll`/`kqueue`, a small fixed thread count) solved it by decoupling
"number of open connections" from "number of threads."
</details>

<details>
<summary>Why is running a blocking JDBC call directly on a Netty event-loop thread dangerous?</summary>

That thread is shared by many connections in its `EventLoopGroup`. Blocking
it on I/O stalls every other connection assigned to that same loop until
the call returns — the opposite of the model's purpose. Blocking work must
be offloaded to a separate thread pool.
</details>

<details>
<summary>How do Java virtual threads let you write blocking-style code but still get event-loop-like scalability?</summary>

A virtual thread isn't tied to one OS thread — the JVM runs it on a small
pool of carrier threads and, when it blocks on I/O, suspends just that
virtual thread and frees its carrier thread for other virtual threads. Code
reads like ordinary blocking thread-per-request code; the runtime, not the
programmer, does the multiplexing that an event loop would otherwise
require explicit callbacks for.
</details>

<details>
<summary>Why does Nginx handle far more idle keep-alive connections than Apache's prefork MPM, on the same hardware?</summary>

Prefork ties up one OS process/thread per connection regardless of whether
it's actively doing anything — idle keep-alive connections still cost a
full thread. Nginx's event-driven workers hold a connection's state cheaply
and only spend CPU on it when the OS reports the socket is actually ready,
so idle connections cost memory for connection state, not a parked thread.
</details>

<details>
<summary>Serverless platforms (Lambda) don't expose a thread pool or event loop to application code. What replaces that concern, and who manages it?</summary>

The execution-environment model: each concurrent invocation gets its own
environment (conceptually similar to thread/process-per-request), but AWS
provisions and scales those environments, subject to account concurrency
limits — the tradeoff moves from "which server threading model do I pick"
to "what are my platform's concurrency/scaling quotas." See
`06-lambda-execution-environments-and-connection-reuse.md`.
</details>
