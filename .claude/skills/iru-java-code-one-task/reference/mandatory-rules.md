# Mandatory rules

The rules in this file are **not preferences** — they apply to every line of production and test code this
skill writes or rewrites, and they take precedence over anything softer elsewhere in `reference/` (including
"match the surrounding code"). They are what the reviewers and the quality gates downstream expect, and they
are the same rules this catalog distributes to every Java repository through this skill.

Scope, so the rules don't turn a task into a restyle: they bind **the code you write or rewrite for this task**.
They do not license converting untouched code that predates them. If a file you're adding to already breaks one
(an AssertJ import, a `System.lineSeparator()`, a test class covering three subclasses), write your new code to
the rule anyway, leave the old code alone, and name the pre-existing violation in the report so it can become
its own task.

The snippets below leave out Javadoc and imports to keep each one focused on its rule; the code you write
doesn't.

## Tests

### JUnit assertions, never AssertJ

Assert with `org.junit.jupiter.api.Assertions` (`assertEquals`, `assertTrue`, `assertThrows`, `assertAll`, …).
Do not add AssertJ to a project, and do not import it in a new test even where the project already has it on the
classpath. Hamcrest likewise only where JUnit genuinely can't express the assertion.

```java
assertEquals(expected, actual);                  // yes
assertThat(actual).isEqualTo(expected);          // no — AssertJ
```

### Mockito mocks and spies, never hand-written fakes

Don't write fake, stub, or "test" implementations of a production interface or class to be used by tests — no
`FakeOrderRepository`, no `InMemoryClock`, no anonymous `new OrderRepository() { ... }` inside a test. Use a
Mockito `mock(...)`/`@Mock` and stub what the test needs, or a `spy(...)`/`@Spy` when the real behaviour is
wanted with one method overridden.

```java
@Mock
private OrderRepository orderRepository;          // yes

when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

private static final class FakeOrderRepository implements OrderRepository { ... }   // no
```

To test the concrete behaviour of an abstract class, mock it with
`mock(AbstractThing.class, withSettings().useConstructor(...).defaultAnswer(CALLS_REAL_METHODS))` (or `spy` an
instance) instead of writing a throwaway subclass. Real value objects and records are still used as-is —
mocking a value type is ceremony, not isolation.

### Test method names: `methodBeingTested_whenCondition_expectedResult`

Every test method is named in three underscore-separated parts — the method under test, the condition, and the
expected result — each in `lowerCamelCase`, returning `void`:

```java
@Test
void quote_whenOrderIsNull_throwsIllegalArgumentException() { ... }

@Test
void quote_whenRateTableHasTwoCarriers_returnsTwoQuotes() { ... }

@Test
void constructor_whenClockIsNull_throwsIllegalArgumentException() { ... }
```

Use `constructor` as the first part when testing a constructor. A `@ParameterizedTest` follows the same shape
(`isValid_whenInputIsBlank_returnsFalse`).

### One test class per public class

- Every public class gets **its own** test class, named after it: `ShippingQuoter` → `ShippingQuoterTest`.
- **Never group several implementations of an abstract class (or an interface) into one shared test class**, and
  don't put them behind a shared abstract test base either — each concrete subclass gets its own test class
  exercising its own behaviour.
- The test class mirrors the production class's package.

### Test class suffixes: `*Test` for unit tests, `*IT` for integration tests

A unit test class name ends in `Test`; an integration test class name (anything needing a real database,
broker, HTTP server, container, or the filesystem beyond a `@TempDir`) ends in `IT`. Surefire and Failsafe pick
classes up by exactly these suffixes, so a misnamed class either silently doesn't run or runs in the wrong phase.

### Standard member ordering in test classes too

A test class is ordered like any other type (`class-member-ordering.md`): constants, then fields (mocks
included), then lifecycle methods (`@BeforeAll`, `@BeforeEach`, `@AfterEach`, `@AfterAll`), then the test
methods, then private helper methods, then nested types (`@Nested` classes, helper records). A private helper
never sits between two test methods.

### Javadoc in test sources

Test sources get the same full Javadoc as production code (see "Full Javadoc" below): the test class, every test
method, every lifecycle method, every field and constant, and every helper. A test method's Javadoc states the
scenario and the expected outcome in a sentence.

## Java style

### Type inference wherever possible — generic static factories included

Use `final var` for every local whose type can be inferred. For a generic static factory with no argument to
infer from, give the type argument as an explicit type witness instead of falling back to a declared type:

```java
final var empty = List.<String>of();             // yes
final List<String> empty = List.of();            // no

final var byId = new HashMap<String, Order>();   // yes
final Map<String, Order> byId = new HashMap<>(); // no
```

The narrow exceptions (a numeric width that matters, a supertype the variable must be, an unwritable inferred
type) are listed in `code-style.md`; outside those, `var` is the rule.

### Records for immutable types, validated in the compact constructor

Any immutable carrier of values — value object, DTO, result, event, command — is a `record`. Its invariants are
checked in the **compact constructor**, so an instance that exists is always valid.

### Defensively copy collections held by a record

A record component that is a collection is copied in the compact constructor (`List.copyOf(...)`,
`Set.copyOf(...)`, `Map.copyOf(...)`). Without the copy the record exposes the caller's mutable collection, and
SpotBugs' `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` fails the build.

```java
public record Order(String id, List<OrderItem> items) {

    public Order {
        Objects.requireNonNull(id, "id is required");
        items = List.copyOf(items);   // also rejects a null list or null elements
    }
}
```

### `final` on parameters

Every method and constructor parameter is `final`. Checkstyle's `FinalParameters`
enforces it in the quality gate, so a missing `final` is a build failure one step later, not a style nit.

### Full Javadoc on every type, method, constructor, and field

Every type, method, constructor, and field — public or private, production or test — carries Javadoc. Details
and tag rules are in `javadoc.md`; this rule widens it to *every* member, private ones included.

### Named constants, never magic numbers

Any literal whose meaning isn't self-evident (`0`, `1`, `-1` as plain arithmetic identities and `""` are the only
usual exceptions) is a named `static final` constant in `SCREAMING_SNAKE_CASE`. That applies to test code too:
expected values, ids, and sizes used in a test are constants of the test class.

```java
private static final int MAX_RETRIES = 3;        // yes
for (var attempt = 0; attempt < MAX_RETRIES; attempt++) { ... }

for (var attempt = 0; attempt < 3; attempt++) { ... }   // no
```

### Stream predicates over manual loops for a simple scan

A loop that only checks whether any/all/none of the elements match, or finds the first match, is a stream:

```java
return items.stream().anyMatch(Objects::isNull);                 // yes

for (final var item : items) { if (item == null) { return true; } }   // no
return false;
```

Keep a loop where it reads better — several accumulators, early exits with side effects, checked exceptions.

### Modern collection accessors

Use the Java 21 sequenced-collection accessors:

```java
items.getFirst()                   // yes
items.get(0)                       // no

items.getLast()                    // yes
items.get(items.size() - 1)        // no
```

(Only when the project's Java release is 21 or later; on an older release, keep `get(0)` and note it.)

### Method references over lambdas

When a lambda only forwards its arguments to one method, use the method reference:

```java
items.stream().anyMatch(Objects::isNull)          // yes
items.stream().anyMatch(item -> item == null)     // no

names.forEach(log::info)                          // yes
names.forEach(name -> log.info(name))             // no
```

### `switch` over chained conditions

Choosing one result out of several by value is a `switch` expression, not an `if`/`else if` chain:

```java
// Yes
return switch (upstreamValue.trim().toLowerCase(Locale.ROOT)) {
    case "accepted" -> DayOffStatus.ACCEPTED;
    case "pending" -> DayOffStatus.PENDING;
    default -> DayOffStatus.UNKNOWN;
};

// No
if ("accepted".equals(value)) { return DayOffStatus.ACCEPTED; }
else if ("pending".equals(value)) { return DayOffStatus.PENDING; }
else { return DayOffStatus.UNKNOWN; }
```

Normalise case with an explicit `Locale.ROOT`, never the default locale.

### No redundant `this`

Qualify a field with `this.` only when a parameter or local actually shadows it (typically a constructor
assignment):

```java
scopesBySubject = buildIndex(properties);         // yes
this.scopesBySubject = buildIndex(properties);    // no — nothing is shadowing the field

this.rates = rates;                               // fine — the parameter shadows the field
```

### Utility predicates over multi-branch conditions

Use a null-safe utility predicate from a library **already on the classpath** instead of spelling out the
null-and-empty check:

```java
if (!StringUtils.hasText(email)) { ... }                    // yes (Spring)
if (StringUtils.isBlank(email)) { ... }                     // yes (Apache Commons Lang)
if (email == null || email.isBlank()) { ... }               // no

if (CollectionUtils.isEmpty(authorities)) { ... }           // yes
if (authorities == null || authorities.isEmpty()) { ... }   // no
```

Never add a dependency just to get one of these — if none is on the classpath, the explicit condition stays,
and that's not a deviation worth reporting.

### LF line endings, never the platform's own

Any line ending the code produces is `"\n"`, so output is identical on every OS and in every test:

```java
// Yes
private static final String LINE_ENDING = "\n";
final var indenter = new DefaultIndenter("  ", "\n");

// No
System.lineSeparator()
System.getProperty("line.separator")
String.format("...%n")                          // %n is the platform separator
writer.newLine() / printWriter.println(...)     // both write the platform separator
SPEC_MAPPER.writerWithDefaultPrettyPrinter()    // indents with DefaultIndenter.SYSTEM_LINEFEED
```

For Jackson pretty-printing, configure a `DefaultPrettyPrinter` whose object and array indenters use
`new DefaultIndenter("  ", "\n")`.
