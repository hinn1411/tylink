# Java Lambda Cold Starts

Why the first AWS SDK call after a cold start is slower than every call after it — and why that
extra latency isn't tied to whichever client or resource happens to make that first call.

---

## Init Duration doesn't cover everything

A Lambda `REPORT` log line's `Init Duration` measures class loading, static initializers, and
constructor execution — object *construction*. It does not measure any cost deferred until an
object is first *used*. That cost is billed to whatever code runs first inside the handler's own
`Duration`, not to `Init Duration`.

## Why the first SDK call is slow

On a cold start, several one-time costs are paid lazily, on the first real network call the
process makes — scoped to the whole process, not to a specific client instance:

- **Credential resolution** — the default credentials provider resolves temporary credentials
  once per process and caches them for the process's lifetime.
- **JIT compilation** — request signing, HTTP client setup, and marshalling are shared library
  code, JIT-compiled on first real use, once per process.
- **DNS resolution** for the service endpoint, cached after the first lookup.

None of these are scoped to one client instance. A second, independent SDK client making its own
first call in the same invocation does not pay them again — only the chronologically first AWS SDK
call in the whole invocation does.

## Practical implication

Call order decides *where* this cost shows up in a trace, not *whether* it's paid. Whichever AWS
call happens to run first looks slow — that's an artifact of ordering, not a property of that
specific call or the resource it touches. Reordering code doesn't remove the cost, it just
relocates which call appears to carry it.

**Mitigation:** Lambda SnapStart restores from a pre-initialized snapshot instead of re-paying
these costs on every cold start. Gotcha: any entropy source (e.g. `SecureRandom`) must be drawn
per-invocation, not in a static initializer, or the snapshot bakes in reused entropy across
restores.

## References

- [Understanding execution environment lifecycle][lambda-lifecycle]
- [Lambda execution role and container credentials][lambda-execution-role]
- [Improving startup performance with Lambda SnapStart][snapstart]
- `docs/learning/06-lambda-execution-environments-and-connection-reuse.md` — per-environment
  connection pool reuse across warm invocations

[lambda-lifecycle]: https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html
[lambda-execution-role]: https://docs.aws.amazon.com/lambda/latest/dg/lambda-intro-execution-role.html
[snapstart]: https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html
