# Tests

Every task's implementation comes with the tests that prove it. This skill **writes** them and does not **run**
them — the calling `iru-java-code-one-task-group` runs the suite, coverage, and quality checks once for the whole
task group. Write tests that will pass and that will hold the coverage gate up; don't invoke Maven here.

## The fixed rules come first

`mandatory-rules.md` (always read) fixes the parts of a test that are not up for matching: JUnit assertions and
never AssertJ, Mockito mocks/spies and never hand-written fakes, `methodBeingTested_whenCondition_expectedResult`
method names, one `*Test` class per public class (`*IT` for integration tests), standard member ordering, and
full Javadoc in test sources. Those apply even when the package's existing tests do otherwise.

## Follow the package you're in, for everything else

Before writing a test, read an existing test in the same package and match what the rules above leave open: how
fixtures are built, whether `@Nested` is used, how parameterised cases are sourced, whether the class uses
`@ExtendWith(MockitoExtension.class)` or `mock(...)` calls. Defaults for a project that doesn't yet have a
convention: JUnit 5 (Jupiter), `org.junit.jupiter.api.Assertions`, and Mockito via `MockitoExtension`.

## What to cover

- **The behaviour the task added or changed**, through the public API — the happy path plus the meaningful
  variations.
- **Every `@throws` in the Javadoc.** Each documented exception gets a test asserting it is thrown for the
  documented condition. This is the single most useful habit here: it keeps the documentation honest and it is
  where most real defects surface.
- **Boundaries**: empty and single-element collections, zero, negative, the maximum, the first and last valid
  value, `null` where `null` is permitted.
- **Not the trivia.** A generated accessor, a record component, or a constant needs no test. Coverage is a gate,
  not a goal; tests that assert nothing meaningful cost maintenance and prove nothing.

## Writing the test

- **One behaviour per test method**, named `methodBeingTested_whenCondition_expectedResult` (see
  `mandatory-rules.md`).
- **Arrange, act, assert**, in that order and visibly separated. Keep the assertion at the end; a test whose
  assertions are scattered through setup is hard to diagnose when it fails.
- **Assert the specific thing.** `assertEquals(expected, actual)` over `assertTrue(expected.equals(actual))` —
  the failure message is the difference between a one-second and a ten-minute diagnosis.
- **`assertThrows`** for exception contracts, and assert on the exception where the message or type carries
  information: `final var e = assertThrows(IllegalArgumentException.class, () -> ...);`
- **`@ParameterizedTest`** with `@ValueSource`/`@CsvSource`/`@MethodSource` instead of a loop inside one test or
  five near-identical methods — a parameterised case reports which input failed.
- **`@Nested`** to group cases around one scenario when the class is getting long, and `@DisplayName` where the
  method name can't carry the intent.
- **No shared mutable state between tests.** Fresh fixtures in `@BeforeEach`, not static fields; never rely on
  the order tests run in.
- **No `Thread.sleep`.** Inject a `Clock`, await a condition, or make the seam synchronous — a sleep is either
  flaky or slow, and usually both.
- **Don't test private methods directly**, and don't widen a member's visibility to test it. Exercise it through
  the public behaviour that uses it; if that's genuinely impossible, the design is telling you the logic wants to
  be its own type — which is a finding to report, not a refactor to perform mid-task.
- **`final var` and `final` fixtures** apply here as much as in production code (`code-style.md`), as do named
  constants instead of magic numbers; test classes get the same member ordering as any other type
  (`class-member-ordering.md`), and every test class, test method, field, and helper gets Javadoc
  (`mandatory-rules.md`).

## Mocking, carefully

- Mock what you don't own or can't afford: a remote client, the filesystem, a clock, a slow collaborator. Use
  the real thing for a value object or a record — a mock of a value object is pure ceremony.
- **Never a hand-written fake.** When a collaborator needs a stand-in, it's a Mockito `mock`/`@Mock` or, when
  most of the real behaviour is wanted, a `spy`/`@Spy` — never a `Fake*`/`InMemory*`/`Stub*` class or an
  anonymous implementation written for the test (`mandatory-rules.md`).
- **Don't mock the type under test**, and don't `spy` on it to stub away the part the task just changed. The
  one exception is an abstract class, tested through a `CALLS_REAL_METHODS` mock instead of a throwaway
  subclass.
- **Verify interactions only when the interaction *is* the behaviour** (a message was published, a repository
  was asked to save). Otherwise assert on the result; over-verified tests break on every harmless refactor.
- Prefer constructor injection of the mock (or `@InjectMocks`) over reflection or a static hook.
- Remember Mockito's own limits before making a collaborator `final` in production code — see the `final`
  section of `code-style.md`.

## What this skill does not do

No running the suite, no coverage report, no quality/lint run, no license headers, no Javadoc audit — all of
those belong to `iru-java-code-one-task-group`, once per task group. If a test can't be made to pass without
something outside the task's scope, say so in the report; don't delete the assertion to make the file green.
