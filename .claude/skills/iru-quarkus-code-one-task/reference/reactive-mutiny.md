# Reactive code: Mutiny, coroutines, virtual threads

Read this when the task returns or consumes `Uni`/`Multi`, uses a reactive client (reactive SQL client, Hibernate
Reactive, reactive Mongo/Redis, reactive messaging), writes a Kotlin `suspend` function, or uses
`@RunOnVirtualThread`.

## Pick the model the module already uses

| The module… | Write new code as… |
|---|---|
| uses blocking Hibernate ORM / JDBC / blocking clients with plain return types | **imperative** — plain return types on worker threads. Don't introduce `Uni` for one method. |
| returns `Uni`/`Multi` throughout, uses reactive clients | **Mutiny** — compose `Uni`/`Multi`, never block. |
| (Kotlin) uses `suspend fun` endpoints and `Flow` | **coroutines** — `suspend`/`Flow`; bridge to Mutiny only at a library boundary. |
| uses `@RunOnVirtualThread` | **imperative on virtual threads** — blocking style, with the pinning rules below. |

Use Mutiny **only when** the call chain is genuinely non-blocking end to end (a reactive datasource, a reactive
client, streaming) or the task asks for it. A `Uni` wrapped around a blocking call is strictly worse than a
plain method.

**Kotlin: coroutines vs Mutiny — one per service.** Quarkus supports both (`quarkus-kotlin` +
`kotlinx-coroutines`, `Uni.awaitSuspending()`, `uni { }`/`asUni()`), but mixing them freely in one codebase trades
consistency for nothing. Follow the module's choice; convert only at a deliberate boundary (e.g. a library that
returns `Uni`, awaited once with `awaitSuspending()` in a `suspend fun`).

## The cardinal rule: never block an event-loop thread

Code in a `Uni`/`Multi` pipeline, a `suspend` endpoint, a `@NonBlocking` method, or a Vert.x handler runs on an
event-loop thread shared by many requests. In there, never:

- call JDBC, blocking Hibernate ORM/Panache, a blocking REST client, `Files.*`, or `Thread.sleep`;
- call `.await().indefinitely()` / `.await().atMost(...)` (Mutiny) or `runBlocking { }` (Kotlin);
- use `synchronized` around anything that waits.

If a reactive flow must call blocking code, move that step off the event loop
(`.emitOn(Infrastructure.getDefaultWorkerPool())`, `.runSubscriptionOn(...)`, or `@Blocking` on the entry
point; `withContext(Dispatchers.IO)` in Kotlin) — and say so in the report, because it is usually a sign the step
should be a different method.

## Writing Mutiny

- Build pipelines with the operator families: `onItem().transform` (sync map), `onItem().transformToUni` /
  `chain` (async flatMap), `Uni.combine().all().unis(...)` (parallel), `onFailure().recoverWithItem/Uni`,
  `onFailure(SpecificException.class).retry().withBackOff(...).atMost(n)`, `ifNoItem().after(d).fail()`.
- Return the `Uni`; never subscribe inside a service and discard the result (nothing happens until someone
  subscribes, and a fire-and-forget subscription loses failures).
- `Uni<Void>` via `.replaceWithVoid()`; null items are allowed in `Uni` but not in `Multi`.
- Failures travel as failures — don't catch and return `null`. Map domain errors with `onFailure().transform`.
- Hibernate Reactive: all work inside one `@WithSession`/`@WithTransaction` (or `Panache.withTransaction`),
  never across separately subscribed `Uni`s; a reactive session is not thread-safe.

## Context

`ThreadLocal`s don't follow a reactive pipeline across thread hops. Use request-scoped beans, the Vert.x
duplicated context, or MDC as Quarkus propagates them — never a hand-rolled `ThreadLocal` for request state.

## Virtual threads

`@RunOnVirtualThread` (Java 21+) gives blocking code worker semantics without a bounded pool. Rules: no
`synchronized` block or method that performs blocking I/O while holding the monitor (pins the carrier — use a
`ReentrantLock`); don't cache expensive objects in `ThreadLocal`s (one per virtual thread); don't add it to code
that already runs on a worker thread just because it's available.

## Testing

- Mutiny: `uni.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().assertItem(x)` and
  `AssertSubscriber.create(n)` for `Multi` — no CDI needed, so these are plain unit tests. `.await().indefinitely()`
  is acceptable **in tests only**.
- Coroutines: `kotlinx-coroutines-test`'s `runTest { }`, `coEvery`/`coVerify` with MockK.
- Hibernate Reactive/`@WithTransaction` code is exercised by `@QuarkusTest` + `@RunOnVertxContext`/
  `UniAsserter` against the Dev Services database — a container-stage test the group skill runs.
