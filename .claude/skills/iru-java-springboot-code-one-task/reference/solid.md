# SOLID in a hexagonal Spring Boot service

Read this when a task introduces or changes an interface, adds a branch on type, or grows an existing class's
responsibilities.

Hexagonal architecture is largely SOLID applied at module scale — so most of these principles are already
enforced structurally, and what's left is the class-level judgement each one asks for.

## Single responsibility

A class has one reason to change. In this architecture that maps onto the module layout directly: a use case
orchestrates, an adapter talks to one technology, a mapper converts, an entity enforces its own invariants.

Practical signals a class has outgrown one responsibility:

- it needs "and" to describe (`OrderService` validates orders **and** sends email **and** writes audit rows);
- two unrelated tasks keep editing the same class for unrelated reasons;
- its test class needs several unrelated sets of mocks to set up.

The recurring failure is the god-service — a `FooService` that accumulates every operation on `Foo`. In this
architecture the antidote is structural: **one class per use case**, named for the action (`PlaceOrderUseCase`,
`CancelOrderUseCase`), each with a single public method. Ten small use-case classes beat one service with ten
methods, because each has exactly one reason to change and its own focused test.

When a task says "add a method to `XService`", check whether it should be a new use-case class instead. If the
plan is explicit about the target class, follow the plan and note the observation.

## Open/closed

Open for extension, closed for modification: adding a behaviour shouldn't mean editing the code that dispatches
to it.

The signal is a `switch`/`if-else` chain over a type or an enum that grows every time a variant is added. Replace
it with polymorphism — an interface with one implementation per variant. Spring makes this cheap: inject
`List<PaymentMethodHandler>` or `Map<String, PaymentMethodHandler>` and let the container supply every
implementation. Adding a variant then means adding a class, not editing a dispatcher.

Don't over-apply it. A switch over a closed enum that genuinely won't grow (`OrderStatus` with three values) is
clearer than three classes. Java's sealed interfaces plus exhaustive pattern matching are a good middle ground
when the set is genuinely closed — the compiler catches a missed case.

## Liskov substitution

Any implementation of a port must be usable wherever the port is declared, without the caller knowing which one
it got. In this architecture, ports are the contract, so this is really a rule about **adapters**:

- An adapter must not strengthen preconditions — if the port accepts a null filter, no implementation may reject
  it.
- An adapter must not throw an exception the port doesn't document. A `MongoOrderRepository` that throws
  `MongoWriteException` breaks every caller written against `OrderRepository`; it must translate to a domain
  exception.
- An adapter must not weaken guarantees. If the port's Javadoc promises ordering or idempotency, every
  implementation delivers it.

This is why a port's Javadoc must state what an implementation guarantees — without it, substitutability can't
be checked, and a second adapter (a second database, say) will quietly behave differently.

## Interface segregation

Clients shouldn't depend on methods they don't use. Concretely: **prefer several small, purpose-shaped ports
over one broad one.**

A `Repository` port with fifteen methods forces every adapter to implement all fifteen and every test to stub
methods the test doesn't care about. Split by the use cases that need them — `OrderRepository` for the
write side and a separate `OrderSearchPort` for querying (which is also what makes a CQRS split natural later,
if it comes).

This matters most where two adapters serve one aggregate. When both a system-of-record database and a search
index back the same aggregate, one port with two implementations creates an ambiguous-bean problem and hides
which technology serves which call. Two ports make it explicit.

Segregate by *client need*, not by arbitrary size. Splitting a cohesive three-method port into three
one-method interfaces is churn.

## Dependency inversion

High-level modules depend on abstractions, not on details — and in this architecture the abstraction is owned by
the **inner** module. That last part is what makes it dependency *inversion* rather than merely dependency
injection: `domain` declares `OrderRepository`, and `infrastructure/database/mongodb` implements it, so the
arrow points inward against the direction of control flow.

The mistakes worth naming:

- **Declaring the port in the adapter module.** Then `domain` or `application` must import the adapter to see the
  interface, and the dependency is no longer inverted — it's just indirection. The port belongs in `domain`.
- **A port shaped around the technology.** `OrderRepository.findByMongoQuery(String json)` is an interface in
  name only. Name and shape ports by intent (`findByCustomer(CustomerId)`), never by the mechanism.
- **Constructor injection, always.** No `@Autowired` fields — they defeat `final` and make the class untestable
  without a container. `@RequiredArgsConstructor` over `private final` fields is the standard shape.

The payoff is the reason `domain` and `application` tests need no Spring context: every dependency is an
interface, so a unit test passes mocks. That is precisely what this skill's unit tests rely on.
