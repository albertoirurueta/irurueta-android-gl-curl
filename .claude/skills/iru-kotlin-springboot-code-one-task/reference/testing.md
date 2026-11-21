# Testing

Read this whenever the task writes tests (almost always). `mandatory-rules.md` has the non-negotiables (JUnit 5,
MockK, naming, one class per class, `*Test` vs `*IT`, `runTest`); this file says **which kind** of test to write
and how.

## Unit first

Pick the cheapest level that can verify the behaviour, in this order:

1. **Plain unit test** — JUnit 5 + MockK, no Spring context. Instantiate the class with its constructor, pass
   `mockk<Port>()` collaborators. This covers domain types, use cases/services, mappers, validators, exception
   translation, and most adapter logic. Runs in milliseconds; this is where nearly all new tests go.
2. **Slice test** — only when the behaviour *is* the framework wiring (request mapping, JSON shape, validation
   annotations, a derived query), the module's existing tests already use that slice, **and** it starts no
   container:
   - `@WebMvcTest(OrderController::class)` + `MockMvc` (Kotlin DSL: `mockMvc.get("/orders/1") { ... }.andExpect
     { status { isOk() } }`), collaborators replaced with `@MockkBean` (springmockk) — or `@MockitoBean` only if
     the project already uses Mockito there (`mandatory-rules.md`).
   - `@WebFluxTest` + `WebTestClient` for WebFlux.
   - `@JsonTest` for serialization shape.
   - `@DataJpaTest` against an **embedded** database (H2/HSQLDB on the test classpath).
   Spring Boot 4 moved slice annotations into per-technology test modules — e.g.
   `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (`spring-boot-starter-webmvc-test`) and
   `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (`spring-boot-starter-data-jpa-test`);
   Spring Boot 3 has them under `org.springframework.boot.test.autoconfigure.*`. Import from wherever the module's
   existing tests do; if the needed test starter is missing, that's a blocker (new dependency), not something to
   add silently.
3. **Integration test (`*IT`)** — `@SpringBootTest` with real infrastructure through Testcontainers. **Out of this
   skill's scope to run** (Step 7 of the skill); you may write one when the task explicitly asks for it, and the
   group skill runs it under its lock.

## MockK patterns

```kotlin
class PlaceOrderUseCaseTest {
    private val orders = mockk<OrderRepository>()
    private val events = mockk<OrderEventPublisher>(relaxUnitFun = true)
    private val useCase = PlaceOrderUseCase(orders, events, FIXED_CLOCK)

    @Test
    fun `place saves the order and publishes OrderPlaced`() {
        val saved = slot<Order>()
        every { orders.save(capture(saved)) } answers { saved.captured }

        useCase.place(COMMAND)

        assertEquals(CUSTOMER_ID, saved.captured.customerId)
        verify(exactly = 1) { events.publish(match { it is OrderPlaced }) }
    }

    @Test
    fun `quote suspends on the pricing client`() = runTest {
        coEvery { pricing.unitPrice(SKU) } returns UNIT_PRICE
        assertEquals(UNIT_PRICE * 2, useCase.quote(SKU))
        coVerify { pricing.unitPrice(SKU) }
    }

    private companion object { /* constants */ }
}
```

- `every`/`verify` for regular functions, `coEvery`/`coVerify` for `suspend` ones.
- `slot<T>()` + `capture(slot)` to assert on an argument; `match { }` for a predicate.
- `answers { firstArg<T>() }` to echo an argument (e.g. a repository `save`).
- `mockkStatic`/`mockkObject` only when unavoidable, and always `unmockkAll()` in `@AfterEach` — they're global.
- `confirmVerified(mock)` when the test's point is "nothing else was called".
- Prefer injecting a `Clock` (fixed in tests via `Clock.fixed(...)`) over mocking `Instant.now()`.

## Integration tests: `@SpringBootTest` + Testcontainers `@ServiceConnection`

When a task asks for one, write it as `*IT`, with containers declared once and shared:

```kotlin
@SpringBootTest
@Testcontainers
class OrderRepositoryAdapterIT(@Autowired private val adapter: OrderRepositoryAdapter) {

    @Test
    fun `findByCustomer returns only that customer's orders`() { ... }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
```

- `@ServiceConnection` (Spring Boot 3.1+) wires the container's URL/credentials — no `@DynamicPropertySource`
  needed for supported services. The `companion object` + `@JvmStatic` shape is what the JUnit extension needs to
  see a static, class-shared container.
- Testcontainers 2.x (managed by Spring Boot 4) renamed artifacts to `testcontainers-<module>` and moved classes
  to `org.testcontainers.<module>` (e.g. `org.testcontainers.postgresql.PostgreSQLContainer`, non-generic);
  1.x uses `org.testcontainers.containers.PostgreSQLContainer<Nothing>`. Import whatever the project's version
  provides.
- Reuse the project's existing container setup (a shared `@TestConfiguration`, a base config class, or a
  `compose.yaml` started through `ComposeContainer`) instead of declaring a new container per class — one stack per
  run is what the group skill's lock assumes.
- Don't run it — the group skill does (`iru-kotlin-springboot-code-one-task-group` Step 3.8).

## Test placement and naming

- `src/test/kotlin/<same package as production>/<ClassName>Test.kt`. Integration tests live where the project
  keeps them (same source set with the `IT` suffix for Maven Failsafe, or a separate `src/integrationTest/kotlin`
  / `src/testIntegration/kotlin` source set in Gradle — check the build).
- Test names per `mandatory-rules.md` (backticks unless the module uses `method_whenCondition_expected`).
- Constructor arguments with `@Autowired` (as above) are the Kotlin-idiomatic way to inject into a Spring test
  class; `lateinit var` is acceptable only for `@MockkBean`/`@MockitoBean`/`@TempDir` fields.
