# SOLID in a Kotlin Spring Boot service

Read this when a task introduces or changes an interface, adds a branch on type, or grows an existing class's
responsibilities. In a hexagonal reactor most of SOLID is already enforced structurally; in a layered service it is
the class-level judgement that keeps services from turning into god-classes.

## Single responsibility

One reason to change. A use case/service orchestrates, an adapter/repository talks to one technology, a mapper
converts, an entity enforces its own invariants.

Signals a class has outgrown one responsibility: it needs "and" to describe; unrelated tasks keep editing it; its
test needs several unrelated sets of mocks.

The recurring failure is the god-service (`OrderService` with every operation on `Order`). In a hexagonal reactor
the antidote is **one class per use case**, named for the action (`PlaceOrderUseCase`, `CancelOrderUseCase`),
each with a single public function — a Kotlin `operator fun invoke(...)` makes the call site read naturally
(`placeOrder(command)`) if the project uses that style. In a layered service, split by sub-capability when a
service passes a few hundred lines. When a task says "add a method to `XService`" and it should be a new class,
follow the plan if it's explicit about the target and note the observation.

## Open/closed

Adding a behaviour shouldn't mean editing the code that dispatches to it. The signal is a `when` over a type or
enum that grows with every variant, duplicated in several places. Options, in order of preference:

1. **A sealed hierarchy with an exhaustive `when`** when the set is genuinely closed and owned by this service —
   the compiler flags every `when` that misses a new case, which is the safety open/closed is really after.
2. **Polymorphism via Spring**: an interface with one bean per variant, injected as `List<PaymentHandler>` or
   `Map<String, PaymentHandler>`; adding a variant means adding a class.

Don't over-apply it — a `when` over a three-value enum that won't grow is clearer than three classes.

## Liskov substitution

Any implementation of a port/interface must be usable wherever the interface is declared:

- Don't strengthen preconditions — if the interface accepts a nullable filter (`filter: Filter?`), no
  implementation may reject `null`.
- Don't throw what the interface doesn't document. Kotlin won't force you to declare it, which makes this easier
  to break silently: a JPA adapter leaking `DataIntegrityViolationException` breaks callers written against the
  port. Translate to the documented domain exception.
- Don't weaken guarantees (ordering, idempotency, "returns null when absent" vs "throws").

This is why a port's KDoc must state what implementations guarantee.

## Interface segregation

Prefer several small, purpose-shaped interfaces over one broad one: `OrderRepository` for the write side and
`OrderSearchPort` for queries, rather than one fifteen-function repository every adapter and test must deal with.
Especially when two technologies serve one aggregate (a database and a search index), two ports make it explicit
which serves which call and avoid an ambiguous-bean problem. Segregate by client need, not arbitrary size.

## Dependency inversion

High-level code depends on abstractions owned by the **inner** layer: `domain` declares `OrderRepository`; the
persistence adapter implements it. Mistakes worth naming:

- **Declaring the interface in the adapter module/package** — the dependency isn't inverted, only indirected.
- **An interface shaped around the technology** (`findByJpql(query: String)`) — name and shape by intent
  (`findByCustomer(customerId)`).
- **Field injection or `lateinit` dependencies** — constructor injection via the primary constructor, always
  (`code-style.md`). It's what lets a unit test pass `mockk()` collaborators with no Spring context.
