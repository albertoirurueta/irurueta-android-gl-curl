# Distributed patterns

Read this when a task involves Kafka, a second datastore, cross-service consistency, or anything the plan
describes as eventually consistent, a projection, an outbox, a saga, or a compensating action.

**These patterns are here so you recognise one when the plan asks for it and implement it correctly — not so you
introduce one on your own initiative.** Each adds real operational complexity. Adding an outbox table or a read
model that the plan didn't ask for is out of scope for a task; if a task looks like it *needs* one to be correct,
that is a blocker to report, with the reasoning.

## Eventual consistency

The baseline assumption behind everything below. Within one aggregate, one transaction, consistency is
immediate. Across aggregates or services it is **eventual**: the change is visible here now, and elsewhere
shortly.

What that obliges you to write:

- **Idempotent consumers.** At-least-once delivery is the norm, so a message may arrive twice. Deduplicate on a
  business key or an event id, or make the operation naturally idempotent (set a state rather than increment a
  counter). A consumer that isn't idempotent is a bug even if it hasn't shown up yet.
- **Tolerance for out-of-order and stale reads.** Don't assume a read immediately after a write sees it.
- **No silent lost update.** If a message can't be processed, it goes to a dead-letter topic — never swallowed.

The honest framing: eventual consistency is a *business* decision, not a technical one. "The customer sees their
order confirmed before the warehouse knows about it" is either acceptable or it isn't, and the plan should say.
If it doesn't and correctness depends on it, ask.

## Transactional outbox

**Problem.** A service must update its database *and* publish an event. There is no transaction spanning both
(the dual-write problem). Write the database then publish, and a crash in between loses the event; publish then
write, and a crash leaves an event describing something that never happened.

**Mechanism.** Write the event into an `outbox` table **in the same local transaction** as the business data. A
separate relay process reads the outbox and publishes to Kafka, marking rows sent. The event is published if and
only if the transaction committed.

**Guarantee.** At-least-once, and ordering is preserved across service instances.

**Costs.** The relay may republish after a crash, so consumers must be idempotent. There's an outbox table to
operate and prune. And it's easy to update the business data and forget to write the outbox row — which is why,
if the codebase has an outbox, *every* state change that should emit an event must go through the same path.

**Where the code goes.** The outbox table and the relay are `infrastructure/database/<engine>` concerns. The
domain publishes a domain event through its port; that the port's adapter writes a row rather than calling Kafka
is invisible to the domain. Requires a database with transactions — so it does not apply to a Couchbase or
plain-MongoDB adapter without one.

## Listen-to-yourself

**Problem.** The same dual-write problem, solved in the opposite order.

**Mechanism.** Publish the event to Kafka **first**, respond to the caller immediately, then consume the
service's *own* event in a separate consumer and apply the database change there. Each process writes to exactly
one system, so there is no dual write.

**Benefits.** Fast caller response; heavy work deferred; the decision logic stays pure and side-effect-free,
hence very testable; decision and execution can be scaled and deployed independently.

**Costs, which are sharper than the outbox's.** The caller may believe the data is updated when it isn't, and a
downstream consumer reading immediately after the event may find nothing. Worse, **validation that fails during
execution has no caller left to report to** — the request already returned success, so a rejection has to be
handled out-of-band. Latency also rises, since processing crosses two consumers.

**Naming matters here.** Emit `OrderSubmitted`, not `OrderCreated` — the event names the decision, not a state
that doesn't exist yet. Mis-naming it is what makes downstream consumers assume too much.

Choose it over the outbox when a fast response matters more than immediate local consistency, and when the
operation can't be rejected after the fact. Otherwise prefer the outbox.

## Saga

**Problem.** A business transaction spans several services or aggregates, and there is no distributed
transaction available.

**Mechanism.** A sequence of local transactions, each publishing an event that triggers the next. On failure,
**compensating transactions** undo the completed steps — semantically, not by rollback (a refund, not an
un-charge).

Two shapes:

- **Choreography** — each service reacts to the others' events. No coordinator; simpler for short flows, but the
  overall sequence exists nowhere explicit and becomes hard to follow past three or four steps.
- **Orchestration** — a coordinator holds the state machine and tells each participant what to do. More
  moving parts, but the flow is readable in one place and far easier to debug. Prefer it for anything non-trivial.

**What implementing one obliges.** Every step needs a compensating action, and those must themselves be
idempotent and able to run out of order. Sagas have **no isolation**: intermediate states are visible to
everyone, so the model needs explicit states (`PENDING`, `CONFIRMED`, `CANCELLING`) rather than pretending the
operation is atomic. That state usually belongs on the aggregate, in `domain`.

**Where the code goes.** An orchestrator is `application` logic — it orchestrates ports. Its persistence and
messaging are adapters. Compensating logic that enforces a business rule belongs in `domain`.

## CQRS

**Problem.** One model serves both writes and reads badly — the write model accumulates validation and
invariants while queries need denormalised, pre-joined shapes, and reporting queries overload the transactional
store.

**Mechanism.** Separate the write model from the read model. Commands go through the domain aggregates;
queries hit a **projection** — a materialised view shaped exactly for what the query needs, updated by consuming
the write side's events.

**Costs.** Two models, likely two stores, an event path between them, and projection logic to monitor. The read
model **lags**, so it is eventually consistent by construction. And projections must be idempotent: an event
processed twice, or missed, corrupts the read model, and rebuilding it is its own operational procedure.

**When it fits.** Read-heavy workloads with genuinely different read and write shapes — and specifically in this
architecture, when Elasticsearch or Neo4j sits alongside a system of record. Not for a bank transfer needing
immediate consistent feedback.

**Where the code goes.** Two separate ports in `domain` — the write repository and the query port — which is
interface segregation doing real work (see `solid.md`). The projection updater consumes events in
`api/consumers` and writes through the query side's adapter. A query port may legitimately return a
read-optimised type rather than a full aggregate; that type is still a domain type, not a DTO.

**Note.** CQRS does not require event sourcing. Most services that benefit from CQRS should not adopt event
sourcing at the same time — it's a much larger commitment.

## Choosing between them

| Need | Pattern |
|---|---|
| Update state and publish an event reliably, with a transactional database | Transactional outbox |
| Same, but the response must be fast and the operation can't be rejected later | Listen-to-yourself |
| Same, but no transactional database available | Listen-to-yourself, or accept at-least-once with an idempotent consumer — flag the limitation |
| Coordinate a multi-service business transaction | Saga (orchestration unless the flow is trivial) |
| Queries need a different shape or store than writes | CQRS with a projection |
| Two aggregates must be consistent *instantly* | None of these — reconsider the aggregate boundary; they may be one aggregate |

That last row matters most. Reaching for a saga to keep two aggregates in lockstep usually means the boundary
was drawn in the wrong place, and merging them is simpler and more correct than coordinating them.
