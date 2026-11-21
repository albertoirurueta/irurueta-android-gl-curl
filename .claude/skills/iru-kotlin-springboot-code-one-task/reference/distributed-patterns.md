# Distributed patterns

Read this when a task involves Kafka (or another broker), a second datastore, cross-service consistency, or
anything the plan describes as eventually consistent, a projection, an outbox, a saga, or a compensating action.

**These patterns are here so you recognise one when the plan asks for it and implement it correctly — not so you
introduce one on your own initiative.** Each adds real operational complexity. If a task looks like it *needs* one
to be correct and the plan didn't ask for it, that's a blocker to report, with the reasoning.

Module/package placement below uses the hexagonal names; in a layered service read "domain port" as "interface in
the service layer" and "adapter" as "the persistence/messaging component".

## Eventual consistency

Within one aggregate and one transaction, consistency is immediate; across aggregates or services it is
**eventual**. What that obliges you to write:

- **Idempotent consumers.** At-least-once delivery is the norm — deduplicate on an event id or business key, or
  make the operation naturally idempotent (set a state rather than increment).
- **Tolerance for out-of-order and stale reads.**
- **No silent lost update.** An unprocessable message goes to a dead-letter topic (Spring Kafka
  `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, or whatever the project wires) — never swallowed in a
  `catch`.

Whether eventual consistency is acceptable is a business decision; if the plan doesn't say and correctness
depends on it, ask.

## Transactional outbox

**Problem**: update the database *and* publish an event with no transaction spanning both (the dual write).
**Mechanism**: write the event to an `outbox` table in the **same local transaction** as the business change; a
relay publishes outbox rows to the broker and marks them sent. **Guarantee**: at-least-once, ordered.
**Costs**: idempotent consumers required; a table to operate and prune; every state change that should emit an
event must go through the same path.

**Where the code goes**: the outbox entity, its repository, and the relay are persistence-adapter concerns. The
domain publishes through its event port; that the adapter writes a row rather than calling Kafka is invisible to
it. In Kotlin, the relay is a `@Scheduled` (or ShedLock-guarded) bean, or a coroutine loop in an
application-owned `CoroutineScope` — never `GlobalScope`. With R2DBC/coroutines, the business write and the outbox
insert must sit in the same `TransactionalOperator.executeAndAwait` block (`coroutines-and-webflux.md`).
Requires a transactional database.

## Listen-to-yourself

Publish the event first, respond to the caller, then consume your own event and apply the database change there —
each step writes to exactly one system. Fast responses and pure, testable decision logic; but the caller may
believe data is updated before it is, and **validation failing during execution has no caller left to report
to**. Name events for the decision (`OrderSubmitted`, not `OrderCreated`). Choose it over the outbox only when a
fast response matters more than immediate local consistency and the operation can't be rejected later.

## Saga

A business transaction across services/aggregates as a sequence of local transactions, each triggering the next;
failures run **compensating** transactions (a refund, not an un-charge). Choreography (services react to each
other's events) suits two or three steps; orchestration (a coordinator holding the state machine) is easier to
follow and debug beyond that — prefer it. Every step needs an idempotent compensation able to run out of order;
sagas have no isolation, so model explicit states on the aggregate (a `sealed`/`enum` `SagaState`: `PENDING`,
`CONFIRMED`, `COMPENSATING`) with an exhaustive `when` for transitions. The orchestrator is application logic;
its persistence and messaging are adapters; compensation rules that are business rules live in the domain.

## CQRS

Separate write model (commands through aggregates) from read model (a **projection** shaped for the query, updated
by consuming write-side events). Costs: two models, likely two stores, lag by construction, idempotent projections,
a rebuild procedure. Fits read-heavy workloads with genuinely different shapes — e.g. Elasticsearch next to a
system of record. Two ports in the domain (write repository + query port); the projection updater consumes events
in the consumer module and writes through the query side's adapter. CQRS does not require event sourcing.

## Choosing between them

| Need | Pattern |
|---|---|
| Update state and publish reliably, transactional DB | Transactional outbox |
| Same, fast response, operation can't be rejected later | Listen-to-yourself |
| Same, no transactional DB | Listen-to-yourself, or at-least-once + idempotent consumer — flag the limitation |
| Multi-service business transaction | Saga (orchestration unless trivial) |
| Queries need a different shape/store than writes | CQRS with a projection |
| Two aggregates must be consistent *instantly* | None — reconsider the boundary; they may be one aggregate |

## Kotlin specifics

- Kafka listeners (`@KafkaListener`) may be `suspend` only on Spring Kafka versions that support it; otherwise keep
  them blocking and hand off explicitly. Don't `runBlocking` inside a listener on a shared consumer thread unless
  the project already does — it ties up the partition.
- Event payload classes are immutable `data class`es; map them to/from AVRO/JSON in the adapter, with
  `jackson-module-kotlin` for JSON (`build-plugins.md`).
- Retry/backoff around remote calls: use what the project has (Spring Retry, Resilience4j's Kotlin extensions,
  or a small `suspend` retry helper with `delay`) — never `Thread.sleep` in a coroutine.
