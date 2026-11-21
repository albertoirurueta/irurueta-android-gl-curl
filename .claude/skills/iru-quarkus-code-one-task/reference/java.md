# Java in a Quarkus module

The rules in this file are **mandatory** for every line of Java production and test code this skill writes or
rewrites — they take precedence over "match the surrounding code" everywhere else in `reference/`. They are the
same agreements this catalog applies to every Java repository (`iru-java-code-one-task/reference/`), restated here
so this skill stays self-contained, with the few adjustments Quarkus's build-time proxies and Panache require.

Scope: they bind the code you write for this task. Don't restyle untouched code; name pre-existing violations
(an AssertJ import, a `private @Inject` field, a hand-written fake) in the report instead.

## Tests

- **JUnit 5 assertions** (`org.junit.jupiter.api.Assertions`), never AssertJ — not even though the Quarkus docs'
  examples use it. Hamcrest only inside RestAssured's `.body(...)`.
- **Mockito mocks/spies, never hand-written fakes** — no `FakeOrderRepository`, no `InMemory*`, no anonymous
  implementation in a test. In Quarkus tests that means `@InjectMock`/`@InjectSpy`/`QuarkusMock`, not an
  `@Alternative` bean written for tests. (An `@Alternative` enabled by an *existing* `QuarkusTestProfile` is the
  module's convention — reuse it, don't add another.)
- **Names**: `methodBeingTested_whenCondition_expectedResult` (`constructor_…` for constructors).
- **One test class per public class**, same package: `FooService` → `FooServiceTest` (plain),
  `FooServiceComponentTest` when a component test is also needed, `FooResourceTest` (`@QuarkusTest`),
  `FooResourceIT` (`@QuarkusIntegrationTest`). `*Test` for everything Surefire runs, `*IT` only for level 4.
- **Arrange / act / assert**, one behaviour per test, `assertThrows` for every documented `@throws`,
  `@ParameterizedTest` instead of loops, no `Thread.sleep`, no shared mutable state.
- **Javadoc in test sources too**: the class, every test method (scenario + expected outcome), fields, constants,
  helpers.
- Test classes and members are package-private (JUnit 5 and Quarkus both prefer it).

## Code style

- **`final var`** for every local whose type can be inferred; explicit type witnesses for generic factories
  (`List.<String>of()`), not a declared type.
- **`final` parameters, always.** `private final` fields for constructor-injected collaborators.
- **`final` classes — except where Quarkus must subclass.** Don't make a class or method `final` when it is a
  normal-scoped bean (`@ApplicationScoped`, `@RequestScoped`), has an interceptor binding (`@Transactional`,
  fault tolerance, caching, metrics), is an `@Entity`, or is a Panache entity/repository. `@Singleton`/
  `@Dependent` beans without interceptors, DTO records, value objects, utilities, and exception types may be final.
- **Records** for DTOs, commands, results, events, value objects — invariants checked in the compact constructor,
  collections defensively copied (`List.copyOf`). Never for entities.
- **Visibility: the narrowest that works, and package-private where Quarkus needs to reach in** — injected
  fields, `@Observes` methods, producer methods, `@Scheduled` methods are package-private, never `private`.
- **Panache entities keep public fields** (that's how Panache works) — the one sanctioned exception to
  encapsulation here; everything else gets private state.
- **Lombok** only if the module already uses it (Quarkus doesn't need it for injection or config). Never add the
  dependency.
- Named constants for every magic number/string (`private static final`, `SCREAMING_SNAKE_CASE`), including in
  tests.
- Modern idioms: `getFirst()`/`getLast()`, method references over trivial lambdas, `switch` expressions over
  `if`/`else` chains on one value, stream predicates (`anyMatch`, `noneMatch`) over manual scans, no redundant
  `this.`, `Objects.requireNonNull`/`isBlank()`-style utility predicates.
- **LF line endings** in anything the code produces: `"\n"`, never `System.lineSeparator()`, `%n`, or `println`
  into output that's compared or persisted.
- Exceptions: unchecked, domain-specific, carrying the offending value; never swallow; never catch `Exception` to
  return `null`. Translate at the boundary (resource → exception mapper, client adapter → domain exception).
- Member order: constants, fields, constructors, public → private methods, nested types.

## Javadoc

Every type, constructor, method, and field — public, package-private, and private, production and test — with a
standalone summary sentence, `@param` for every parameter (and record component), `@return`, and `@throws` for
every exception a caller can handle. A bean's class Javadoc states its scope-relevant contract (thread-safety for
`@ApplicationScoped`, whether it may block). A `@ConfigMapping` interface documents every property method. The
group skill audits this with `iru-java-javadoc`; a build with `-Xdoclint:all` fails on a missing `@param`.
