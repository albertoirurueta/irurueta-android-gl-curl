# Coroutines and WebFlux

Read this when the task adds or changes a `suspend` function, a `Flow`, a WebFlux/`coRouter` endpoint, an R2DBC
or reactive repository, a `WebClient` call, or anything that launches a coroutine. If the service is blocking
Spring MVC + JPA and the task doesn't ask for coroutines, **don't introduce them** — on a blocking stack, virtual
threads (`spring.threads.virtual.enabled=true`, if the project uses them) are the scaling answer, and a `suspend`
function wrapping a blocking JPA call gains nothing.

## Which stack is this?

Detect before writing: `spring-boot-starter-webflux` vs `-webmvc`/`-web`; `spring-boot-starter-data-r2dbc` or
reactive Mongo vs JPA; `kotlinx-coroutines-reactor` on the classpath; existing handlers returning `suspend`/`Flow`
vs `Mono`/`Flux`. Follow the existing style — don't convert `Mono` code to coroutines (or back) in an unrelated
task.

## Handlers

`suspend fun` controller methods work in both Spring MVC and WebFlux; `Flow<T>` return types stream in WebFlux.

```kotlin
@RestController
@RequestMapping("/orders")
class OrderController(private val placeOrder: PlaceOrderUseCase) {

    @GetMapping("/{id}")
    suspend fun find(@PathVariable id: Long): OrderResponse =
        placeOrder.find(OrderId(id))?.toResponse() ?: throw OrderNotFoundException(id)
}
```

- Bridge reactive types with `kotlinx-coroutines-reactor`: `mono.awaitSingle()`, `awaitSingleOrNull()`,
  `flux.asFlow()`, `WebClient...awaitBody<T>()`, `awaitExchange { }`.
- Repositories: `CoroutineCrudRepository<T, ID>` (suspend functions + `Flow`) for R2DBC/reactive Mongo.

## Never block an event-loop thread

On WebFlux the request runs on a small Netty event-loop pool. Never call blocking I/O (JDBC/JPA, `RestTemplate`,
`Thread.sleep`, `File` reads, `.block()`) from a `suspend` handler or reactive chain. When a blocking library is
unavoidable, isolate it: `withContext(Dispatchers.IO) { blockingCall() }`, and say so in the report. Use `delay`,
never `Thread.sleep`, inside coroutines.

## Never `GlobalScope`, never `runBlocking` in production paths

- **`GlobalScope.launch`/`async`** escapes structured concurrency: no cancellation when the request is cancelled,
  no error propagation, and — critically — **no transaction**: a coroutine launched inside a transactional method
  runs in its own context, so its writes escape the transaction. Use `coroutineScope { }` to run children in
  parallel within the caller's lifetime, or inject an application-owned `CoroutineScope` bean (with a
  `SupervisorJob`) for genuinely fire-and-forget work, and say why.
- **`runBlocking`** inside a handler or a service called from one blocks the thread it runs on — on WebFlux, an
  event-loop thread. It belongs only in `main`-like entry points and (as `runTest`) never in tests either.
- Rethrow `CancellationException` from any `catch (e: Exception)` around suspending code; `runCatching` around
  suspend calls swallows it — avoid it there.

## Transactions on suspend functions

`@Transactional` on a `suspend fun` (or a method returning `Mono`/`Flux`/`Flow`) uses Spring's **reactive**
transaction management: it needs a `ReactiveTransactionManager` bean (e.g. `R2dbcTransactionManager`), not the
thread-bound JPA `PlatformTransactionManager`. With JPA, keep transactional methods non-suspending.

The robust pattern keeps the whole unit of work inside one `TransactionalOperator.executeAndAwait` block:

```kotlin
@Service
class TransferFunds(
    private val accounts: AccountRepository,          // CoroutineCrudRepository
    private val transactions: TransactionalOperator,  // TransactionalOperator.create(reactiveTransactionManager)
) {
    suspend fun transfer(from: AccountId, to: AccountId, amount: Money) {
        transactions.executeAndAwait {
            accounts.debit(from, amount)
            accounts.credit(to, amount)
        }
    }
}
```

Everything inside the block participates in the transaction; anything launched in a *new* coroutine
(`launch`, `async` in another scope) does not. Known pitfall: context loss across suspension points with naive
`@Transactional` + `suspend` (spring-framework#28290) — prefer `executeAndAwait` for multi-step writes.

## Coroutine context

- Inject dispatchers (`CoroutineDispatcher` constructor parameter, defaulting to `Dispatchers.IO`) instead of
  hard-coding them, so tests can pass a `StandardTestDispatcher`.
- MDC/tracing context doesn't follow coroutines automatically: use `kotlinx-coroutines-slf4j`'s `MDCContext()` or
  Micrometer context propagation, whichever the project already wires.

## Testing

- `= runTest { ... }` for any test calling a `suspend` function; virtual time makes `delay` instant.
- MockK: `coEvery { repo.findById(ID) } returns entity`, `coVerify(exactly = 1) { ... }`, `coJustRun { ... }`.
- `Flow`s: collect with `toList()` inside `runTest`, or use Turbine if the project already has it.
- WebFlux endpoints: `@WebFluxTest` + `WebTestClient` is a slice (no container) and may run as a unit test if the
  module already uses it; anything against a real database or broker is an `*IT` (`testing.md`).
