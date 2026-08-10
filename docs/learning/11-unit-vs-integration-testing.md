# Unit vs. Integration Testing

A unit test isolates one piece of code and fakes everything it talks to. An
integration test removes some of those fakes and lets the code talk to a
*real* dependency — a real database engine, a real file system, a real
queue — to prove the two actually work together, not just that the code
calls the fake correctly. This project has three layers of exactly this
shape stacked on top of each other; reading them side by side is the
clearest way to see why the third layer earns its cost.

---

## 1. The same feature, tested at three depths

| Test class | What's real | What's faked | What it proves |
|---|---|---|---|
| `ShortenUrlHandlerTest` | Handler logic | `UrlRepository` (the interface) | Validation, status codes, response shape — zero DynamoDB involvement |
| `DynamoDbUrlRepositoryTest` | Request-building logic | `DynamoDbClient` (the AWS SDK object) | `DynamoDbUrlRepository` builds the right `PutItemRequest`/`GetItemRequest`/`QueryRequest` objects |
| `UrlTableIT` | Everything, including a real DynamoDB engine | Nothing | The schema the code assumes actually matches a real table |

`DynamoDbUrlRepositoryTest` mocks the client directly
(`functions/src/test/java/com/tylink/repository/dynamodb/DynamoDbUrlRepositoryTest.java:52`):

```java
dynamoDb = mock(DynamoDbClient.class);
repository = new DynamoDbUrlRepository(dynamoDb, TABLE_NAME);
```

That mock will accept a `PutItemRequest` with a typo'd attribute name, a
wrong `AttributeType`, or a key shape that doesn't match the real table —
because a mock has no opinion about what DynamoDB actually enforces. It
returns whatever the test told it to return, nothing more.

`UrlTableIT` has no mock at all
(`functions/src/test/java/com/tylink/repository/dynamodb/UrlTableIT.java`).
It spins up a real DynamoDB engine (DynamoDB Local, via Testcontainers)
with the exact PK/SK schema declared in `template.yaml`, then does a real
`putItem`/`getItem` round trip:

```java
client.createTable(CreateTableRequest.builder()
        .tableName(TABLE_NAME)
        .keySchema(
                KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
        .build());

client.putItem(...);
GetItemResponse response = client.getItem(...);   // real engine, real round trip
```

This is the only test in the suite that would catch "the code assumes `PK`
and `SK` are the key names, but `template.yaml` was changed to `pk`/`sk`" —
a bug the other two tests above would pass straight through, because
neither of their fakes was ever told to care.

## 2. Why this is the general rule, not a DynamoDB quirk

A mock encodes an assumption about how the real dependency behaves. If
that assumption is wrong, every test built on the mock is wrong in exactly
the same way — and nothing catches it, because the test and the code share
the same incorrect belief. Only a test against the real dependency can
surface that kind of bug.

`ShortenUrlIdempotencyIT` is the same story one layer up: it's the only
test that exercises a real DynamoDB-backed `DynamoDBPersistenceStore`
behind the `@Idempotent` annotation. `ShortenUrlHandlerTest` never
configures a real persistence store, so it can prove the handler's
*validation logic* is correct but can't prove the idempotency wiring
(JMESPath config, table setup) is actually connected end-to-end — see
`10-testing-concurrent-code.md` §3 for where that test draws its own
boundary.

## 3. The tradeoff — why not integration-test everything

| | Unit test | Integration test |
|---|---|---|
| Speed | Milliseconds | Seconds (container startup) |
| What it proves | Logic is correct, given assumed dependency behavior | Code and the real dependency actually agree |
| Failure signal | Precise — points at one function | Broad — could be the code, the config, or the infra |
| Cost to run | Free, every save | Needs Docker — see below |

The right ratio is mostly unit tests (fast, tight, run constantly) plus a
thin layer of integration tests at the exact boundary where the code
touches something real — enough to catch "the mock lied," not enough to
make every build take minutes. This ratio is usually called the **test
pyramid**.

## 4. Why this project gates integration tests behind a Maven profile

`*IT.java` needs Docker (Testcontainers spins up DynamoDB Local), and
`sam build --use-container` runs Maven inside a nested build container with
no Docker socket mounted in — so `mvn clean install` would fail there if
`UrlTableIT` ran by default. `maven-failsafe-plugin`'s execution lives in an
opt-in `integration-test` profile instead: `mvn test` runs only `*Test.java`
(no Docker needed), and `mvn verify -Pintegration-test` runs both. See
`docs/technical_decisions/06-integration-tests-as-profile.md` for the full
root-cause writeup and `03-testcontainers-ryuk.md` for a related Docker
Desktop gotcha.

## References

- Martin Fowler, ["UnitTest"][fowler-unit] and ["IntegrationTest"][fowler-integration]
- Martin Fowler, ["TestPyramid"][fowler-pyramid]
- [Testcontainers — "Why Testcontainers"][testcontainers] — the technique
  `UrlTableIT` and `ShortenUrlIdempotencyIT` use: a real, disposable
  Docker-based engine instead of an in-memory stand-in
- Google Testing Blog, ["Test Sizes"][google-test-sizes] — an alternate
  small/medium/large framing that maps closely to unit/integration/e2e
- `docs/technical_decisions/06-integration-tests-as-profile.md` — why
  integration tests are a separate Maven profile in this repo specifically

[fowler-unit]: https://martinfowler.com/bliki/UnitTest.html
[fowler-integration]: https://martinfowler.com/bliki/IntegrationTest.html
[fowler-pyramid]: https://martinfowler.com/bliki/TestPyramid.html
[testcontainers]: https://testcontainers.com/getting-started/
[google-test-sizes]: https://testing.googleblog.com/2010/12/test-sizes.html

## Interview questions

<details>
<summary>What's the fundamental difference between a unit test and an integration test?</summary>

A unit test isolates one piece of code and fakes (mocks/stubs) everything
it depends on, so it only proves the code's own logic is correct given
assumed behavior from those dependencies. An integration test replaces at
least one of those fakes with the real thing — a real database, a real
file system — to prove the code and that dependency actually agree, not
just that the code calls the fake as expected.
</details>

<details>
<summary>In this codebase, <code>DynamoDbUrlRepositoryTest</code> mocks <code>DynamoDbClient</code> and passes. Why isn't that enough to trust the repository works against real DynamoDB?</summary>

The mock has no opinion about what DynamoDB actually enforces — it will
accept a request with the wrong attribute name, wrong type, or wrong key
shape and just return whatever the test configured it to return. It only
proves the repository *builds* a request that looks right by the test's
own assumptions; it can't prove those assumptions match the real table's
schema. Only a test against a real engine (`UrlTableIT`) can catch that
class of bug.
</details>

<details>
<summary>Why does this project run integration tests via a separate <code>mvn verify -Pintegration-test</code> step instead of running them as part of every build?</summary>

Two reasons: cost (spinning up a real DynamoDB Local container via
Testcontainers takes seconds, not milliseconds, so running it on every
save would slow down the fast feedback loop unit tests are meant to
provide) and environment (`sam build --use-container` runs Maven inside a
nested build container with no Docker socket mounted in, so a
Testcontainers-based test would fail there even if the code is correct).
Gating it behind an opt-in profile keeps the default build fast and
Docker-independent while still letting `mvn verify -Pintegration-test`
(or CI) exercise the real-infrastructure path explicitly.
</details>

<details>
<summary>You have 100% unit test coverage with mocks on a new database-backed feature. Is that sufficient confidence to ship? Why or why not?</summary>

Not by itself. Coverage measures how much code the tests execute, not
whether the assumptions baked into the mocks match the real dependency's
actual behavior. A mock can't enforce a real database's schema
constraints, type coercion, or query semantics — so a bug in the boundary
between the code and the real system (wrong key names, wrong types,
mismatched schema) can hide behind 100% mocked coverage indefinitely. A
thin integration-test layer at that exact boundary is what closes the
gap.
</details>
