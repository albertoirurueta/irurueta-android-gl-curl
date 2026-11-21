# Mandatory rules

The rules in this file are **not preferences** — they apply to every line of production and test code this skill
writes or rewrites, and they take precedence over anything softer elsewhere in `reference/` (including "match the
surrounding code"). They are the Kotlin counterpart of the rules `iru-java-springboot-code-one-task` enforces for
Java services, adapted where Kotlin has a better native answer (null safety instead of `Optional`, `val` instead of
`final`, KDoc instead of Javadoc, MockK instead of Mockito).

Scope, so the rules don't turn a task into a restyle: they bind **the code you write or rewrite for this task**.
They do not license converting untouched code that predates them. If a file you're adding to already breaks one
(an AssertJ import, a `!!`, a `System.lineSeparator()`), write your new code to the rule anyway, leave the old code
alone, and name the pre-existing violation in the report so it can become its own task.

Where a rule says "consistent with the project", detect the convention once (Step 3 of the skill reads neighbouring
tests) and follow it; where the project has no convention yet, the default stated here applies.

The snippets below leave out KDoc and imports to keep each one focused on its rule; the code you write doesn't.

## Tests

### JUnit 5, with assertions consistent with the project — never AssertJ added

Tests run on JUnit 5 (Jupiter). Assert with whichever of these the module's existing tests already use:
`org.junit.jupiter.api.Assertions` (`assertEquals`, `assertTrue`, `assertThrows`, `assertAll`) or `kotlin.test`
(`assertEquals`, `assertFailsWith`, `assertNotNull` — which also smart-casts). With no existing tests, use
`kotlin.test` if `kotlin-test-junit5` is on the test classpath, otherwise JUnit's `Assertions`. Never mix both in
one test class. Don't add AssertJ, Kotest assertions, Strikt, or Hamcrest to a project, and don't import them in a
new test even where they're already on the classpath — unless the module's tests are written in them throughout
(then match, and say so in the report).

```kotlin
assertEquals(expected, actual)                       // yes
val error = assertFailsWith<IllegalArgumentException> { Money(-1) }   // yes (kotlin.test)
assertThat(actual).isEqualTo(expected)               // no — AssertJ
```

### MockK, never Mockito — and never hand-written fakes

Mock collaborators with MockK (`mockk<T>()`, `every { } returns`, `verify { }`, `coEvery`/`coVerify` for `suspend`
functions, `slot<T>()`/`capture`, `spyk(...)` when real behaviour is wanted with one member overridden,
`mockkObject` for an `object`). Mockito can't mock Kotlin `final` classes without the inline mock maker, and has no
first-class support for `suspend` functions, default arguments, or objects.

```kotlin
private val orderRepository = mockk<OrderRepository>()           // yes
every { orderRepository.findById(ORDER_ID) } returns order

class FakeOrderRepository : OrderRepository { ... }              // no — hand-written fake
object : OrderRepository { override fun findById(...) = ... }    // no — anonymous fake
```

Spring test slices replace a bean with a mock through:

- `@MockkBean` / `@SpykBean` from `com.ninja-squad:springmockk` — when it's on the test classpath (springmockk 5.x
  for Spring Boot 4, 4.x for Spring Boot 3);
- otherwise `@MockitoBean` (Spring Framework 6.2+) — **only** if the project already uses Mockito in slice tests.
  Don't introduce Mockito to a MockK project; if a slice needs a bean mock and neither library is available, that's
  a blocker to report (adding springmockk is a new dependency).

Prefer `mockk<T>()` (strict) over `mockk<T>(relaxed = true)`: a relaxed mock returns defaults for anything not
stubbed, which hides a missing stub as a wrong value. Use `relaxUnitFun = true` when only `Unit`-returning calls
need no stubbing. Real value objects and data classes are used as-is — mocking a value type is ceremony.

### Test names follow the project's convention

Kotlin allows backtick-quoted names, and they're the default for new test classes when the module has no
convention:

```kotlin
@Test
fun `quote throws when the order is empty`() { ... }
```

If the module's existing tests use `methodBeingTested_whenCondition_expectedResult` (the Java catalog convention),
use that instead — consistency within a module beats either style. Either way, a name states the method/behaviour,
the condition, and the expected outcome. Constructor tests start with `constructor` / `` `constructor ...` ``.

### One test class per class, `*Test` for unit tests and `*IT` for integration tests

- Every production class with behaviour gets **its own** test class named after it: `ShippingQuoter` →
  `ShippingQuoterTest`, in the same package. A file of top-level functions `Strings.kt` → `StringsTest`.
- **Never group several implementations of an interface or sealed hierarchy into one shared test class**, and
  don't hide them behind a shared abstract test base — each implementation gets its own test class.
- A unit test class ends in `Test`; an integration test class — anything needing a real database, broker, HTTP
  server, container, or filesystem beyond `@TempDir` — ends in `IT`. Surefire/Failsafe (and most Gradle
  `integrationTest` setups) select by exactly these suffixes, so a misnamed class either silently doesn't run or
  runs in the wrong phase. This skill runs `*Test` classes only.

### Coroutines in tests: `runTest`, never `runBlocking`

A test calling a `suspend` function is `= runTest { ... }` (kotlinx-coroutines-test), which also skips `delay` via
virtual time. Inject a `TestDispatcher`/`CoroutineDispatcher` where production code launches its own coroutines.

### Member ordering and KDoc in test classes too

Test classes follow `code-style.md`'s ordering (companion/constants, then properties and mocks, then lifecycle
methods, then tests, then private helpers). Test classes and every test function carry KDoc stating the scenario
and the expected outcome in one sentence — unless the module's detekt config explicitly excludes test sources from
the documentation rules *and* the module's existing tests carry none, in which case match the module and say so.

## Kotlin style

### `val` over `var`, and immutable types

- Properties and locals are `val` unless they are genuinely reassigned. A `var` in new code needs a reason (a JPA
  entity's mutable state, a counter in a tight loop).
- Expose read-only collection types (`List`, `Set`, `Map`), never `MutableList` etc. from a public API. When a
  class receives a collection it keeps, copy it (`items.toList()`) so a caller's later mutation can't reach in.
- Value objects and DTOs are immutable (`data class` with `val` properties, or `@JvmInline value class`), with
  invariants checked in `init` via `require(...)` — see `code-style.md`.

### Null safety — no `!!`

- Never use `!!` in production code. Express absence in the type (`T?`) and handle it with `?.`, `?:`, `let`,
  `requireNotNull(x) { "message" }` (argument), or `checkNotNull(x) { "message" }` (state). In tests,
  `assertNotNull` (kotlin.test) or `checkNotNull` instead of `!!`.
- No `Optional` in Kotlin APIs — return `T?`. At a Java boundary that returns `Optional` (Spring Data
  `findById`), convert immediately (`findByIdOrNull(id)` from `org.springframework.data.repository.findByIdOrNull`,
  or `.orElse(null)`).
- Treat platform types from unannotated Java APIs as nullable until proven otherwise; declare the Kotlin type
  explicitly at the boundary rather than letting a platform type propagate.
- No `lateinit` for injected dependencies (constructor injection — `code-style.md`). `lateinit` is acceptable only
  for test-framework-injected fields (`@MockkBean lateinit var`, `@TempDir lateinit var`).

### KDoc on every public declaration

Every public and internal class, interface, object, function, property, and constructor parameter carries KDoc —
production and test sources. Use `@param` for plain parameters, `@property` for primary-constructor properties,
`@return` unless the function returns `Unit`, `@throws` for every exception a caller can reasonably handle (the
`@throws` list is what the tests are written against), and `[Symbol]` links rather than backticks for
references. Private members get KDoc that says *why* when the reason isn't obvious from the code. A port
interface's KDoc is the contract every adapter must honour.

### Named constants, never magic numbers

Any literal whose meaning isn't self-evident (`0`, `1`, `-1` as arithmetic identities, `""`, and `true`/`false`
are the usual exceptions) is a named constant: `const val` in a `companion object` (private when only the class
uses it) or at top level in the file for primitives and `String`s; a plain `val` in the companion for anything
else. `SCREAMING_SNAKE_CASE`. Test values (ids, expected amounts) are constants of the test class's companion.

```kotlin
private companion object {
    const val MAX_RETRIES = 3
}
repeat(MAX_RETRIES) { ... }        // yes
repeat(3) { ... }                  // no
```

### Collection and stdlib idioms over manual loops

`any`/`all`/`none`/`firstOrNull`/`associateBy`/`groupBy`/`sumOf` instead of a loop that only scans, finds, or
accumulates once; `first()`/`last()`/`firstOrNull()` instead of `[0]`/`[size - 1]`; function references
(`::isValid`, `Order::id`) when a lambda only forwards; `when` instead of an `if`/`else if` chain choosing one
result by value; `isNullOrBlank()`/`isNullOrEmpty()` instead of spelled-out null-and-empty checks. Keep a loop
where it reads better (several accumulators, early exits with side effects).

### No redundant `this`

Qualify with `this.` only when a parameter or local shadows the member (rare with primary-constructor properties).

### LF line endings, never the platform's own

Any line ending the code produces is `"\n"`, so output is identical on every OS and in every test. Kotlin's
`StringBuilder.appendLine` and `buildString { appendLine(...) }` write `\n` and are fine. These write the
platform separator and are not: `System.lineSeparator()`, `PrintWriter.println`, `BufferedWriter.newLine()`,
`String.format("%n")`. For Jackson pretty-printing, configure a pretty printer whose indenters use `"\n"`.

### Locales are pinned, never the platform's own

`lowercase()`/`uppercase()` (Kotlin's are locale-invariant — prefer them over the deprecated
`toLowerCase()`/`toUpperCase()`), and pass `Locale.ROOT` (or an explicit, intended `Locale`) to `String.format`,
`"...".format(...)`, `DateTimeFormatter.ofPattern`, `NumberFormat`, and `MessageSource.getMessage` — never rely on
`Locale.getDefault()`. In the service's main configuration (`application.yml`/`.properties`), pin both fallbacks:

```yaml
spring:
  messages:
    fallback-to-system-locale: false   # else an untranslated language resolves to the SERVER's
  web:
    locale: en                         # else a request with no Accept-Language resolves to the SERVER's
```

### Logging

A logger is a `private val log = LoggerFactory.getLogger(Foo::class.java)` inside the class's companion object (or
`private val log = KotlinLogging.logger {}` if the project already uses kotlin-logging) — match the module. Use
parameterized messages (`log.info("placed order {}", id)`) or the kotlin-logging lambda form, never string
templates that are built whether or not the level is enabled for anything expensive. Never log a secret, token, or
personal data. No `println`.
