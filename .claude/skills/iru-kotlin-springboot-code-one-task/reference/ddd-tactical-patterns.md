# DDD tactical patterns in Kotlin

Read this when a task touches domain code — in a hexagonal reactor the `domain` module, in a layered service the
model/service classes that hold business rules. It covers value objects, entities, aggregates, domain services,
domain events, and domain exceptions, and where each one's logic belongs.

## Value object

A value, not a thing: no identity, no lifecycle; two with equal state are the same value (`Money`, `EmailAddress`,
`DateRange`, `OrderStatus`).

- **Immutable**: `data class` with `val` properties, or `@JvmInline value class` for a single wrapped value. An
  operation returns a new instance (`operator fun plus(other: Money): Money`).
- **Always valid**: every invariant checked in `init` with `require(...)`. A function taking an `EmailAddress`
  then needs no format check, because an invalid one cannot exist.
- **Read-only collections**, copied on the way in (`val lines: List<Line>` assigned from `lines.toList()`) so a
  caller's mutable list can't leak in.
- **Prefer a value type over a bare primitive** whenever the value has rules: `@JvmInline value class OrderId(val
  value: UUID)` costs one line and makes passing a `CustomerId` where an `OrderId` was meant a compile error.
- **Push behaviour into value objects** — a `DateRange` that knows `overlaps(other)`, a `Money` that rejects a
  currency mismatch. It's the most effective way to stop entities becoming bags of data beside thousand-line
  services.

Value classes have one caveat with frameworks: Spring/Jackson bind them, but JPA can't map one directly as an
`@Id` or column without a converter — another reason the persistence entity is a separate type.

## Entity

Identity and a lifecycle; equality by identifier, never by state.

- Identified by a value-object id (`OrderId`), not a raw `UUID`/`Long`.
- A regular `class` (not a `data class` — its generated `equals`/`copy` contradict identity semantics) with
  `equals`/`hashCode` on the id only.
- State is `private set` (`var status: OrderStatus = OrderStatus.NEW; private set`) and changes only through
  methods named for the business operation (`order.cancel(reason)`, not `order.status = CANCELLED`).
- Every transition validates it is legal from the current state and throws a domain exception if not —
  an exhaustive `when (status)` makes the rule table explicit and compiler-checked.
- A domain entity is **not** a persistence entity; the `@Entity` class lives in the persistence adapter and maps
  to this one.

## Aggregate and aggregate root

A cluster of entities and value objects with one consistency boundary, accessed only through its root.

- **All access through the root.** Repositories return roots only; internal members are exposed as read-only
  views (`val lines: List<OrderLine> get() = _lines.toList()`), never the backing `MutableList`.
- **The root enforces invariants** spanning its members.
- **One transaction, one aggregate.** Anything spanning two aggregates is eventually consistent — see
  `distributed-patterns.md`.
- **Reference other aggregates by id** (`val customerId: CustomerId`), never by object.
- **Keep aggregates small** — if two entities don't need to be consistent in the same instant, they're separate
  aggregates.

One port per aggregate root: `OrderRepository` loads and saves the whole `Order`.

## Domain service

Logic that genuinely belongs to no single entity or value object — a rule spanning aggregates, a calculation
needing several inputs. Check first whether it belongs on an entity or value object; it usually does.

In a hexagonal `domain` module a domain service is a plain class taking ports as constructor parameters, with no
Spring annotation (`boot` wires it). A top-level function is fine for a stateless, dependency-free rule. **A domain
service is not a use case**: if what you're writing is mostly a sequence of port calls, it belongs in
`application`.

## Domain event

Something that happened, named in past tense (`OrderPlaced`, `PaymentCaptured`): an immutable `data class` in the
domain, containing domain types only — never an AVRO class or DTO. A `sealed interface OrderEvent` grouping an
aggregate's events gives consumers an exhaustive `when`. Include the aggregate id, the time it occurred (from an
injected `Clock`), and the values a consumer needs. Publishing goes through a domain port
(`OrderEventPublisher`); mapping to AVRO happens in the producer adapter.

## Domain exception

Rule violations throw domain exceptions defined in the domain, from a small hierarchy rooted in
`RuntimeException` (so `@Transactional` rolls back — Kotlin has no checked exceptions):

```kotlin
sealed class DomainException(message: String) : RuntimeException(message)
class OrderNotFoundException(val orderId: OrderId) : DomainException("order $orderId not found")
class OrderAlreadyCancelledException(val orderId: OrderId) : DomainException("order $orderId already cancelled")
```

The API layer maps them to transport status codes (`@RestControllerAdvice` with an exhaustive `when` over the
sealed hierarchy), which keeps `HttpStatus` out of the domain. Reserve `require`/`IllegalArgumentException` for
programming errors; a business rule a caller may want to handle is a domain exception. Where an outcome is
expected rather than exceptional, a sealed result type (`code-style.md`) is often clearer than an exception.

## Ubiquitous language

Name types and functions after the business's terms. If the task says "cancel an order", the function is
`cancel()`, not `deactivate()`. When the task uses a term the code doesn't, prefer the task's term and note the
mismatch in the report.
