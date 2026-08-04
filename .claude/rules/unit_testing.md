# Unit Testing Rules

Applies to `*Test.java` (JUnit 5 + Mockito, no AWS/Docker). See a sibling
`integration_testing.md` for `*IT.java` conventions once one exists — those tests run against
real infrastructure via Testcontainers and don't share these rules (e.g. one expensive setup
may legitimately back several assertions).

- MUST name test methods `unitOfWork_state_expectedBehavior()` (e.g.
  `extractOwnerId_missingRequestContext_returnsNull`)
- MUST cover exactly one behavior per test
- MUST structure each test as Arrange/Act/Assert, separated by blank lines
- MUST assign the call under test to a local variable rather than asserting on it inline
- MUST extract repeated object/event construction into private static helper methods on the
  test class
- MUST use `@BeforeEach` for setup shared by every test in the class instead of repeating it
  per test
- MUST use `@AfterEach` for teardown when a test allocates a resource that needs releasing
  (e.g. closing a client); omit both hooks when there's nothing to share or release
