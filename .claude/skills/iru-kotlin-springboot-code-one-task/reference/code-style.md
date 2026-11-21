# Code style

This catalog's Kotlin + Spring Boot preferences. The non-negotiable rules — the ones that hold even when the
surrounding code does otherwise — live in `mandatory-rules.md`, always read alongside this file. Everything here is
consistent with them.

Beyond those: **match the surrounding code.** If the module you are editing already does something a specific way
(kotlin-logging vs. SLF4J, a mapper style, an error type), follow it and note the divergence in the report rather
than converting the file to this document's preference as a side effect of an unrelated task.

## Data classes for DTOs and value objects — never for JPA entities

Use a `data class` (all `val` properties) for request/response DTOs, commands, queries, events, and multi-field
value objects; a `@JvmInline value class` for single-field wrappers (`OrderId`, `EmailAddress`) that should cost
nothing at runtime. Validate in `init`:

```kotlin
/**
 * A monetary amount in a specific currency.
 *
 * @property amount the amount; never negative.
 * @property currency the ISO currency.
 * @throws IllegalArgumentException if [amount] is negative.
 */
data class Money(val amount: BigDecimal, val currency: Currency) {
    init {
        require(amount.signum() >= 0) { "amount must not be negative: $amount" }
    }
}
```

- `require` throws `IllegalArgumentException`, `check` throws `IllegalStateException` — both take a lazy message.
- A data class's `copy()` bypasses nothing (it calls the primary constructor, so `init` runs), but it does let a
  caller change any property — if a value has state transitions with rules, it is an entity with named methods,
  not a data class.
- **Never a JPA `@Entity` as a `data class`**: its `equals`/`hashCode`/`toString` cover every constructor property
  — mutable state, a generated id that is `null` before persist, and lazy associations that `toString` will happily
  load. See `jpa-and-persistence.md`. The *domain* type an entity maps to can be a data class.
- Jackson needs `jackson-module-kotlin` to bind data classes (see `build-plugins.md`).

## Sealed hierarchies for closed sets of outcomes

When an operation has a fixed set of results or errors, model them as a `sealed interface`/`sealed class` and
consume them with an exhaustive `when` (no `else`) — the compiler then flags every consumer when a case is added:

```kotlin
sealed interface PlaceOrderResult {
    data class Placed(val orderId: OrderId) : PlaceOrderResult
    data class Rejected(val reason: RejectionReason) : PlaceOrderResult
    data object OutOfStock : PlaceOrderResult
}
```

Use sealed results for *expected* business outcomes a caller must handle; keep exceptions for programming errors
and truly exceptional failures. Use an `enum class` when the cases carry no per-case data. Don't wrap everything in
`Result<T>` — Kotlin's `Result` swallows `CancellationException` inside `runCatching`, which breaks coroutine
cancellation; prefer a sealed type or a plain exception.

## Constructor injection through the primary constructor

Every Spring bean takes its dependencies as `private val` primary-constructor parameters. No `@Autowired` (a
single constructor needs none), no field injection, no `lateinit var` for a bean dependency:

```kotlin
@Service
class PlaceOrderUseCase(
    private val orders: OrderRepository,
    private val clock: Clock,
) { ... }
```

`lateinit` for a bean defeats null safety (an uninitialised access is a runtime exception), forces `var`, and makes
the class untestable without a container. Optional dependencies are `ObjectProvider<T>` or a nullable parameter
with a default, not `lateinit`.

## `@ConfigurationProperties` with constructor binding

Configuration is an immutable class bound through its constructor — no `var`, no `lateinit`:

```kotlin
/**
 * Settings for the pricing client.
 *
 * @property baseUrl the pricing service's base URL.
 * @property timeout per-request timeout.
 */
@ConfigurationProperties("clients.pricing")
data class PricingProperties(
    val baseUrl: URI,
    val timeout: Duration = Duration.ofSeconds(2),
)
```

Register it with `@ConfigurationPropertiesScan` on the application class or `@EnableConfigurationProperties(...)`
on a configuration class — whichever the project already uses. Constructor binding is implicit for a single
constructor (Spring Boot 3+); `@ConstructorBinding` is only needed when there are several. Validate with
`@Validated` + Jakarta constraints (on Kotlin properties these need the `@field:` use-site target, e.g.
`@field:NotBlank`) or with `init { require(...) }`. Prefer typed properties (`Duration`, `URI`, `DataSize`) over
strings.

## Extension functions — sparingly

Use an extension function when it reads as a natural operation on a type you don't own and is used in several
places (`fun OrderEntity.toDomain(): Order` for a mapper is a good fit). Don't use one to reach into another
module's internals, to add business logic to a framework type, or as a way to avoid deciding which class owns a
behaviour — if the behaviour belongs to a domain type you own, make it a member. Keep extensions `private` or
`internal` unless other modules genuinely need them, and put shared ones in a file named for the receiver
(`OrderMappings.kt`).

## Scope functions — with discipline

| Function | Use for | Returns |
|---|---|---|
| `let` | null-safe transform (`x?.let { ... }`), limiting a temporary's scope | lambda result |
| `apply` | configuring an object you just created | the object |
| `also` | a side effect that doesn't change the value (logging, an assertion) | the object |
| `run` / `with` | computing a result from an object's members | lambda result |

Rules: never nest scope functions (`it` inside `it` is unreadable — name the lambda parameter or use a local
`val`); don't chain more than two; don't use `?.let { } ?: run { }` as an if/else when either branch is non-trivial
— write the `if`. A scope function that only saves one local variable isn't saving anything.

## Classes are final unless Spring needs them open

Leave classes and members `final` (the Kotlin default). Never write `open` by hand on a Spring bean or JPA entity —
the compiler plugins in `build-plugins.md` do it; a hand-written `open` usually means a plugin is missing. Use
`internal` for module-private types in a multi-module build.

## Class member ordering

Inside a class: properties (constructor properties first, by virtue of the header), `init` blocks, secondary
constructors, public functions, internal/protected, private functions, nested/inner classes, then the
`companion object` last (Kotlin coding conventions). Overloads stay contiguous. Insert new members in their band
and **don't reorder a file that already violates the order** — a reshuffle buries the real change.

## Mapping

Write mappers by hand as extension functions or a small mapper class — Kotlin's named arguments make a
field-by-field mapping short and readable, and a hand-written mapper is ordinary, testable code. Use MapStruct only
if the module already uses it (it works with Kotlin via `kapt`/KSP and is a build-configuration commitment, not a
per-task decision).

## Other conventions

- **Exceptions**: `IllegalArgumentException` (via `require`) for invalid arguments; domain rule violations throw a
  domain exception (`ddd-tactical-patterns.md`). Kotlin has no checked exceptions — document with `@throws` in
  KDoc, and add `@Throws(...)` only where Java callers need it.
- **Don't catch and swallow.** Handle meaningfully or let it propagate. Never catch `Throwable`/`Exception` in a
  coroutine without rethrowing `CancellationException`.
- **Expression bodies** (`fun total() = lines.sumOf { it.amount }`) for single-expression functions; give public
  ones an explicit return type.
- **Named arguments** for any call with several parameters of the same type, or a boolean literal.
- **String templates** over concatenation; raw strings (`"""..."""` + `trimIndent()`) for multi-line text.
- **No new runtime dependency** unless the task explicitly calls for it, apart from the Kotlin/Spring baseline
  plumbing in `build-plugins.md`. Anything else is a blocker to report.
