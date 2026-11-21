# DDD tactical patterns

Read this when a task touches `domain`. It covers the building blocks — value objects, entities, aggregates,
domain services, domain events, and domain exceptions — and where each one's logic belongs.

## Value object

A value object represents a **value, not a thing**: it has no identity and no lifecycle. Two value objects with
equal state are the same value. `Money`, `EmailAddress`, `DateRange`, `Quantity`, `OrderStatus`.

Rules:

- **Immutable.** No setters, no mutating methods. An operation returns a *new* instance
  (`money.plus(other)` returns a new `Money`).
- **A Java `record`**, which gives immutability, `equals`, and `hashCode` for free — equality by state is
  exactly the semantics a value object needs.
- **Always valid.** Validate every invariant in the compact constructor and throw if violated. This is the
  property that pays off everywhere else: a method taking a non-null `EmailAddress` needs no format check,
  because an invalid one cannot exist.
- **Defensively copy** any collection or mutable field — a record is only shallowly immutable.

Prefer a value object over a bare `String`/`BigDecimal`/`long` whenever the value has rules. `record
OrderId(UUID value)` costs three lines and makes it impossible to pass a `CustomerId` where an `OrderId` was
meant.

**Push behaviour into value objects.** When modelling an aggregate, move as much logic as possible out of the
entity and into the value objects it holds — a `DateRange` that knows about overlap, a `Money` that knows about
currency mismatch. This is the single most effective way to stop an entity becoming a bag of data with a
thousand-line service beside it.

## Entity

An entity has **identity and a lifecycle**: it is the same entity across state changes. Equality is by
identifier, never by state.

- Identified by a value-object id (`OrderId`), not a raw `UUID` or `Long`.
- `equals`/`hashCode` on the identifier **only**.
- Mutating methods are named for the business operation (`order.cancel(reason)`), not `setStatus(...)`. A setter
  exposes state; a named method enforces a rule.
- Every state transition validates that it is legal from the current state, and throws a domain exception if
  not.
- A domain entity is **not** a persistence entity. The `@Document`/`@Entity`-annotated class lives in
  `infrastructure/database/<engine>` and is mapped to this one.

## Aggregate and aggregate root

An aggregate is a cluster of entities and value objects treated as **one unit with one consistency boundary**.
The aggregate root is the single entity through which the outside world touches it.

- **All access goes through the root.** No repository returns an inner member, and no caller holds a reference
  to one. If external code needs to reach inside, either the operation belongs on the root, or the boundary is
  drawn wrong.
- **The root enforces the invariants** that span its members — an `Order` guarantees its total matches its
  lines, so no caller can leave it inconsistent.
- **One transaction, one aggregate.** Anything spanning two aggregates is eventually consistent, coordinated by
  a domain event or a saga — see `distributed-patterns.md`.
- **Reference other aggregates by id**, never by object reference. An `Order` holds a `CustomerId`, not a
  `Customer`. Holding the object drags an unbounded graph into memory and blurs the boundary.
- **Keep aggregates small.** A large aggregate is a contention point: every write locks the whole thing. If the
  invariants don't actually require two entities to be consistent *in the same instant*, they belong to
  different aggregates.
- **A value object is never an aggregate root** — it has no identity to be a root of.

One port per aggregate root, named for the aggregate: `OrderRepository` deals in `Order`, loads the whole
aggregate, and saves the whole aggregate.

## Domain service

A domain service holds business logic that genuinely doesn't belong to a single entity or value object —
typically a rule spanning several aggregates, or a calculation needing inputs from more than one.

Use one only when the logic has nowhere better to live. The failure mode is the anaemic model: entities reduced
to getters and setters, with all behaviour in services. Before writing a domain service, check whether the logic
belongs on an entity or a value object — it usually does.

A domain service lives in `domain`, so it takes and returns domain types and depends only on ports. It carries
no Spring annotation; `boot` wires it if it needs to be a bean.

**A domain service is not a use case.** A use case (in `application`) orchestrates: load via a port, call domain
logic, save, publish. A domain service *is* domain logic. If what you are writing is mostly a sequence of port
calls, it is a use case.

## Domain event

A domain event records **something that happened**, in past tense: `OrderPlaced`, `PaymentCaptured`. It is an
immutable record, defined in `domain`, containing domain types only — never an AVRO class, never a DTO. Mapping
to AVRO happens in `infrastructure/producers`.

Include what a consumer needs: the aggregate id, the time it occurred, and the relevant values. Publishing goes
through a `domain` port (`OrderEventPublisher`); the domain never knows a broker exists.

## Domain exception

Domain rule violations throw **domain** exceptions, defined in `domain`, from a small hierarchy — typically
`NotFoundException`, `ConflictException`, `ValidationException`, plus specific subclasses where a caller must
distinguish them.

The `api` modules map these to transport status codes. This is what keeps `HttpStatus` out of the domain: the
domain says "this order is already cancelled", and `api/rest-server` decides that means `409`.

Reserve `IllegalArgumentException` for genuine programming errors — a null where a value was required. A
business rule violation is a domain exception, because a caller may reasonably want to handle it.

## Ubiquitous language

Name types and methods after the terms the business actually uses. If the domain experts say "cancel an order",
the method is `cancel()`, not `deactivate()` or `setStatusInactive()`. When a task's description uses a term the
code doesn't, prefer the task's term and note the mismatch — it usually means the code drifted from the
language, and that drift is worth surfacing.
