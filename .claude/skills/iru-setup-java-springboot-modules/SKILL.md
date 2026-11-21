---
name: iru-setup-java-springboot-modules
description: "Invoked as `/iru-setup-java-springboot-modules` (args: `stack-file`, `mode`, `on-existing`), normally by `iru-setup-java-springboot` after the pom skill. Scaffolds a hexagonal Spring Boot service's source tree: module packages, ports, adapter skeletons, boot class, `application.yml`, migrations and an ArchUnit test, for Java or Kotlin."
model: sonnet
---

# Setup Java Spring Boot Modules

Turn a reactor of empty modules into a service skeleton that compiles, starts, and demonstrates the hexagonal
pattern well enough that the first real feature has an obvious shape to follow.

What "demonstrates" means here matters: this skill writes **one worked example per adapter kind**, not a set of
empty files. A single `Order` aggregate, its `OrderRepository` port, one adapter implementing that port against
the chosen database, one REST endpoint, one use case. A developer's first task is to rename it or delete it — both
of which are easier than inventing the layering from an empty tree. Name the example after the service's actual
domain if the manifest's `project.description` makes that obvious; otherwise use `Example` and say so in the
report.

## Step 0 — Resolve inputs and check the reactor exists

Parse `args` for `key: value` lines: `stack-file: <path>`, defaulting to `springboot-stack.yml` at the repository
root (read it in full; if it's missing, stop and point the user at `/iru-setup-java-springboot`); `mode`
(`new`/`existing`, informational — it never means "update silently"); and `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`).

Read `project.language` (`java` | `kotlin`; absent ⇒ `java`) and `project.buildTool` (`maven` | `gradle`; absent
⇒ `maven`; `gradle` only ever appears with `kotlin`). **With `language: java` (or absent) everything below applies
exactly as written** and the "Kotlin" section is skipped. With `language: kotlin`, Steps 1–11 apply with the
substitutions in the **Kotlin** section after Step 0 — read it before writing any file.

Verify the build exists:

- **Maven** — `pom.xml` at the root and a `pom.xml` in every module directory the manifest names.
- **Gradle** — `settings.gradle.kts` at the root `include`s every module (as `:`-paths mirroring the directory tree,
  e.g. `:infrastructure:database:postgresql`) and each module directory has a `build.gradle.kts`; the
  `build-logic/` convention plugins and `gradle/libs.versions.toml` exist.

If not, stop and tell the user to run `/iru-setup-java-springboot-pom` first — this skill writes sources into
modules, it doesn't create modules, and it never edits a `pom.xml`, `build.gradle.kts`, `settings.gradle.kts` or
the version catalog.

Derive from the manifest:

- `<base>` — `project.basePackage`; `<base-path>` — the same with `.` replaced by `/`.
- `<Name>` — the service name in PascalCase, from `project.artifactId` (e.g. `orders-service` → `Orders`), used
  for the application class `<Name>Application`.
- Whether each module exists, from `modules:`.
- `stack.concurrency` — decides every return type below: blocking → plain values and `List<T>`; reactive →
  `Mono<T>`/`Flux<T>`; both → blocking on the inbound side and reactive on the outbound side unless the manifest
  says otherwise, and say in the report which way you resolved it.

Never overwrite a `.java` or `.kt` file that already contains code you didn't generate. Check first, skip and report
instead — this rule holds whatever `on-existing` says, and **Replace** below never applies to hand-written code.

Say whether this run is **scaffolding from scratch** (no sources in any module yet) or **updating an existing
source tree** (list the modules that already have sources). Then, for each generated artifact this skill owns that
already exists — `boot/src/main/resources/application*.yml`, the ArchUnit test, each engine's migration/changelog
directory, the worked example (aggregate/port/adapter/use case/endpoint) — warn and, unless `on-existing` says
otherwise, ask with `AskUserQuestion` (up to 4 per call, one question each):

- **Update (Recommended)** — add what's missing (a property block for a newly added technology, a migration
  directory for a new engine, a rule in the ArchUnit test for a new module) and correct stale values; keep
  everything else, including your own keys and code. Your customizations survive, but a generated part this skill
  owns may be rewritten.
- **Keep as is** — leave it untouched; it may not reflect the current manifest (e.g. no configuration for a newly
  added database) — Step 13 lists each gap.
- **Replace** — regenerate from this skill's templates; customizations in that file are lost (recoverable only
  from git history). Only offered for files that are wholly generated (profile YAML, the ArchUnit test), never for
  a class containing hand-written logic.

## Kotlin (`project.language: kotlin`)

Skip this section for Java. For Kotlin, Steps 1–11 keep their structure, packages, ports, configuration files,
migrations and report content; only the source language and the idioms below change. If
`iru-kotlin-springboot-code-one-task` is installed, its `reference/` files (`code-style.md`, `mandatory-rules.md`,
`testing.md`, `jpa-and-persistence.md`, `coroutines-and-webflux.md`, `hexagonal-architecture.md`) are the
authoritative rules for every line written here — the scaffold is the first code a developer copies, so it must
already obey them. The essentials are restated so this skill stands alone.

### Substitutions

| Java step says | Kotlin writes |
|---|---|
| `src/main/java`, `src/test/java` | `src/main/kotlin`, `src/test/kotlin` (same packages). The Maven reactor compiles both roots; never put Kotlin under `java/`. |
| Records / Lombok `@Value` for value objects | `data class` with `val`s and invariants in `init { require(...) }`; `@JvmInline value class` for single-field ids (`OrderId`). **No Lombok** anywhere. |
| Aggregate root | A **regular class** (never a data class): identity-based `equals`/`hashCode`, state changed only through named methods (`confirm()`), `var ... private set` for mutable state — a data class's `copy()` would let callers bypass the transitions. |
| MapStruct mappers (`@Mapper(componentModel = "spring")`) | **Hand-written** `internal` extension functions in the same `mapper/` package, one file per aggregate (`OrderMappings.kt`: `fun OrderEntity.toDomain()`, `fun Order.toEntity()`), using named arguments. **No MapStruct, no kapt/KSP** — the build skill doesn't add them. Same for DTO ↔ domain (API modules) and domain ↔ AVRO (producers/consumers). |
| `Optional<T>` | `T?`; Spring Data's `findById` → `findByIdOrNull(id)` (`org.springframework.data.repository.findByIdOrNull`). No `!!` anywhere. |
| JPA `@Entity` | A regular class (never a data class) with `var` columns, `@Enumerated(EnumType.STRING)`, `LAZY` `@ManyToOne`, `equals` on the id via `Hibernate.getClass(...)`, constant `hashCode`, a `toString` that touches no association. The build's `kotlin-jpa` + `all-open` config opens it and adds the no-arg constructor — never write `open` by hand. |
| Use case class with constructor injection | Plain Kotlin class with `private val` constructor ports and **no Spring annotation**; `boot/config/UseCaseConfiguration` declares it with `@Bean fun placeOrderUseCase(...): PlaceOrderUseCase`. `application` keeps no Spring dependency. |
| `@Component` adapters, `@RestController`, etc. | Same annotations; dependencies as `private val` primary-constructor parameters — no `@Autowired`, no `lateinit var`. `kotlin-spring` opens them. |
| `@ConfigurationProperties` record/class + `@RefreshScope` | An immutable `data class` bound through its constructor, annotated `@RefreshScope @Validated @ConfigurationProperties("<prefix>")`, with Jakarta constraints on the **field** target (`@field:Positive`, `@field:Size(...)`) and typed properties (`Duration`, `URI`, `BigDecimal`). Register with `@ConfigurationPropertiesScan` on the application class. **Keep `@Validated`**: `kotlin-spring` opens a class because of it, and `@RefreshScope` needs a CGLIB-proxyable (open) class — with `@RefreshScope` alone the class stays `final` and the scoped proxy can't be created. Without dynamic config (`none`), drop `@RefreshScope` only. |
| Constants class for metric names | An `object OrderMetrics` holding `const val` names and tag keys. |
| `Consumer<Message<...>>` bean | `@Bean fun orderPlacedConsumer(...): Consumer<Message<OrderPlaced>> = Consumer { ... }` (same binding names). |
| `Mono`/`Flux` for `stack.concurrency: reactive` | Ports and use cases use `suspend fun` (single/optional results as `T`/`T?`) and `Flow<T>` for streams; adapters bridge with `kotlinx-coroutines-reactor` (`awaitSingleOrNull()`, `asFlow()`) or use `CoroutineCrudRepository`. `domain` then needs `kotlinx-coroutines-core` for `Flow` — if the build doesn't provide it, return `List<T>` and report the gap. Never `runBlocking`/`GlobalScope`; `@Transactional` on a `suspend fun` needs a `ReactiveTransactionManager` (blocking JPA keeps non-suspending methods). |
| `<Name>Application` with `main` | `@SpringBootApplication @ConfigurationPropertiesScan class <Name>Application` plus top-level `fun main(args: Array<String>) { runApplication<<Name>Application>(*args) }`. |
| Javadoc on every port | KDoc on every public declaration (`@property`, `@param`, `@return`, `@throws`), production and test. |
| Mockito unit tests | JUnit 5 + **MockK** (`mockk<Port>()`, `every`/`verify`, `slot`/`capture`), assertions from `kotlin.test`, backtick test names, constants in a `private companion object`, data classes used as-is (never mocked). Never AssertJ, never hand-written fakes. |

Every other rule still binds: named constants instead of magic numbers, LF line endings, pinned locales
(`spring.messages.fallback-to-system-locale: false` and `spring.web.locale` in `application.yml`), `val` over
`var`, read-only collection types in public APIs. The `application*.yml` files are identical to the Java path.

### Templates (shape verified end to end — see Step 12)

Domain aggregate, value object and port:

```kotlin
/** ... KDoc with @property for each constructor property ... */
@JvmInline
value class OrderId(val value: UUID) {
    /** Factory for new identities. */
    companion object {
        /** @return a fresh [OrderId]. */
        fun random(): OrderId = OrderId(UUID.randomUUID())
    }
}

data class Money(val amount: BigDecimal, val currency: Currency) {
    init {
        require(amount.signum() >= 0) { "amount must not be negative: $amount" }
    }
}

class Order(val id: OrderId, val customerId: String, val total: Money, status: OrderStatus = OrderStatus.PLACED) {
    /** The current status; changes only through [confirm]. */
    var status: OrderStatus = status
        private set

    /** @throws ConflictException if the order is already confirmed. */
    fun confirm() {
        if (status == OrderStatus.CONFIRMED) throw ConflictException("order ${id.value} is already confirmed")
        status = OrderStatus.CONFIRMED
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Order && id == other.id)
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Order(id=${id.value}, status=$status)"
}

/** Persistence port for [Order]. Implementations return `null` — never throw — when an order does not exist. */
interface OrderRepository {
    fun save(order: Order): Order
    fun findById(id: OrderId): Order?
}
```

Domain exceptions as a `sealed class DomainException(message: String, cause: Throwable? = null) :
RuntimeException(message, cause)` with `NotFoundException`, `ConflictException`, `ValidationException` subclasses
(plus one for an unavailable downstream when the stack has clients) — `RuntimeException` so a thrown one rolls a
transaction back, sealed so the API modules' exception mapping is an exhaustive `when`. Keep the `cause`: the client
adapters translate transport exceptions into these, and detekt's `SwallowedException` fails the build when the
caught exception isn't passed on.

Persistence adapter and mapper (JPA shown; other engines keep the same adapter/mapper split):

```kotlin
internal fun OrderEntity.toDomain(): Order =
    Order(id = OrderId(id), customerId = customerId, total = Money(totalAmount, Currency.getInstance(currency)),
        status = status)

internal fun Order.toEntity(): OrderEntity =
    OrderEntity(id = id.value, customerId = customerId, totalAmount = total.amount,
        currency = total.currency.currencyCode, status = status)

@Component
class JpaOrderRepositoryAdapter(private val repository: OrderJpaRepository) : OrderRepository {
    @Transactional
    override fun save(order: Order): Order = repository.save(order.toEntity()).toDomain()

    @Transactional(readOnly = true)
    override fun findById(id: OrderId): Order? = repository.findByIdOrNull(id.value)?.toDomain()
}
```

Make each migration's column types match the entity mapping exactly — `ddl-auto: validate` fails the first `*IT`
on, e.g., a `CHAR(3)` column mapped by a `String` (Hibernate expects `VARCHAR`).

Refreshable configuration and its port adapter:

```kotlin
@RefreshScope
@Validated
@ConfigurationProperties("orders.ordering")
data class OrderingProperties(
    @field:Positive val maximumOrderTotal: BigDecimal = DEFAULT_MAXIMUM_ORDER_TOTAL,
    @field:Size(min = CURRENCY_CODE_LENGTH, max = CURRENCY_CODE_LENGTH) val currency: String = DEFAULT_CURRENCY,
) {
    private companion object {
        const val CURRENCY_CODE_LENGTH = 3
        const val DEFAULT_CURRENCY = "EUR"
        val DEFAULT_MAXIMUM_ORDER_TOTAL = BigDecimal("10000")
    }
}

@Component
class OrderingConfigurationAdapter(private val properties: OrderingProperties) : OrderingConfigurationProvider {
    override fun maximumOrderTotal(): Money =
        Money(properties.maximumOrderTotal, Currency.getInstance(properties.currency))
}
```

When `stack.dynamicConfig` isn't `none`, also write `<Area>ConfigurationRefreshTest` (a `*Test` — it starts a
Spring context but no container) in `infrastructure/configuration`'s test sources, next to a minimal test-only
`@SpringBootApplication @ConfigurationPropertiesScan class ConfigurationTestApplication`. It proves the
configuration is actually dynamic — the defect Step 5 warns about:

```kotlin
@SpringBootTest(properties = ["orders.ordering.maximum-order-total=100"])
class OrderingConfigurationRefreshTest(
    @Autowired private val provider: OrderingConfigurationProvider,
    @Autowired private val environment: ConfigurableEnvironment,
    @Autowired private val refresher: ContextRefresher,
) {
    @Test
    fun `maximumOrderTotal reflects a refreshed property`() {
        assertEquals(BigDecimal("100"), provider.maximumOrderTotal().amount)
        TestPropertyValues.of("orders.ordering.maximum-order-total=$REFRESHED").applyTo(environment)
        refresher.refresh()
        assertEquals(BigDecimal(REFRESHED), provider.maximumOrderTotal().amount)
    }

    private companion object {
        const val REFRESHED = "200"
    }
}
```

Composition root:

```kotlin
@SpringBootApplication
@ConfigurationPropertiesScan
class OrdersApplication

fun main(args: Array<String>) {
    runApplication<OrdersApplication>(*args)
}

@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {
    @Bean
    fun placeOrderUseCase(
        orders: OrderRepository,
        configuration: OrderingConfigurationProvider,
    ): PlaceOrderUseCase = PlaceOrderUseCase(orders, configuration)
}
```

Use-case unit test:

```kotlin
class PlaceOrderUseCaseTest {
    private val orders = mockk<OrderRepository>()
    private val configuration = mockk<OrderingConfigurationProvider>()
    private val useCase = PlaceOrderUseCase(orders, configuration)

    @Test
    fun `place saves the order when the total is within the maximum`() {
        val saved = slot<Order>()
        every { configuration.maximumOrderTotal() } returns MAXIMUM
        every { orders.save(capture(saved)) } answers { saved.captured }

        val order = useCase.place(CUSTOMER_ID, WITHIN_MAXIMUM)

        assertEquals(CUSTOMER_ID, order.customerId)
        verify(exactly = 1) { orders.save(any()) }
    }

    @Test
    fun `place throws ValidationException when the total exceeds the maximum`() {
        every { configuration.maximumOrderTotal() } returns MAXIMUM
        assertFailsWith<ValidationException> { useCase.place(CUSTOMER_ID, ABOVE_MAXIMUM) }
        verify(exactly = 0) { orders.save(any()) }
    }

    private companion object { /* CUSTOMER_ID, MAXIMUM, WITHIN_MAXIMUM, ABOVE_MAXIMUM */ }
}
```

Also write a plain unit test for every mapper file (`OrderMappingsTest`: entity → domain → entity round trip) and
each configuration adapter (constructing the properties data class directly). The templates above omit KDoc and
imports for brevity; the generated files carry both.

## Step 1 — Package layout

Create `src/main/java`, `src/main/resources`, and `src/test/java` under each module, with these packages:

| Module | Main package | Contains |
|---|---|---|
| `domain` | `<base>.domain` | `model/` (entities, value objects, aggregates), `port/` (the interfaces outer modules implement), `service/` (pure domain services), `exception/` (domain exceptions) |
| `application` | `<base>.application` | `usecase/` (one class per use case), `exception/` |
| `infrastructure/database/<engine>` | `<base>.infrastructure.database.<engine>` | `entity/` (persistence models), `repository/` (Spring Data repositories), `adapter/` (port implementations), `mapper/` (MapStruct), `config/` |
| `infrastructure/configuration` | `<base>.infrastructure.configuration` | `properties/` (`@ConfigurationProperties` beans), `adapter/` (port implementations that expose configuration as domain types) |
| `infrastructure/clients/<name>` | `<base>.infrastructure.clients.<name>` | `adapter/`, `mapper/`, `config/` — generated client code lands under `target/generated-sources` |
| `infrastructure/producers` | `<base>.infrastructure.producers` | `adapter/`, `mapper/` (domain → AVRO), `config/` |
| `infrastructure/metrics` | `<base>.infrastructure.metrics` | `adapter/`, `config/` |
| `api/rest-server` | `<base>.api.rest` | `controller/` (implementing the generated interfaces), `mapper/` (DTO ↔ domain), `error/` (`@RestControllerAdvice`) |
| `api/grpc-server` | `<base>.api.grpc` | `service/` (extending the generated base classes), `mapper/`, `error/` |
| `api/graphql-server` | `<base>.api.graphql` | `controller/` (implementing the generated resolver interfaces), `mapper/`, `error/` (a `DataFetcherExceptionResolver`), `config/` (query depth/complexity limits) |
| `api/consumers` | `<base>.api.consumers` | `consumer/` (the `Consumer<Message<...>>` beans), `mapper/` (AVRO → domain) |
| `boot` | `<base>` | `<Name>Application`, `config/` (composition-root `@Configuration`) |

`domain`'s package holds no Spring annotations at all — the enforcer rule
`iru-setup-java-springboot-pom` added makes that a build failure, not a guideline. On a Gradle build there is no
enforcer: the module graph (`project(":...")` dependencies) and Step 11's ArchUnit test are the only checks, which
is why that test is mandatory there.

## Step 2 — `domain`: the ports

This is the module that makes the architecture work, so write it first and write it properly. The rule the user's
architecture requires: **`domain` is shared by every other module, so every capability an outer module provides is
declared here as an interface**, and outer modules only ever depend inward on this one. Nothing outside `domain`
is visible to `application`, and no two adapters can see each other.

Write, adapted to the actual stack:

- **Model** — one aggregate root with a couple of value objects, using records or Lombok `@Value` for
  immutability. No persistence annotations, no JSON annotations, no `@Component`.
- **Ports** in `<base>.domain.port`, one interface per outbound capability the manifest calls for:
  - `<Aggregate>Repository` — one per database engine role. Method signatures follow `stack.concurrency`.
    For a CQRS setup (Elasticsearch or Neo4j alongside a system of record) define **two** ports —
    e.g. `OrderRepository` and `OrderSearchPort` — so it's clear which adapter serves which, rather than one
    interface with two implementations racing to be the primary bean.
  - `<Aggregate>EventPublisher` — if `stack.messaging.directions` includes `produce`. Takes domain events, knows
    nothing about Kafka, topics, or AVRO.
  - `<Name>Client` — one per downstream service named in `stack.restClients`/`stack.grpcClients`/
    `stack.graphqlClients`, expressed in domain types only. One port per *service*, not per protocol: which
    transport reaches it is an adapter detail the domain must not encode.
  - `MetricsPublisher` — if `stack.metrics`. Methods named for what is being measured
    (`recordOrderAccepted(OrderType)`), not for Micrometer primitives, so the domain never mentions counters or
    gauges.
  - `<Name>ConfigurationProvider` — if the domain needs to read dynamically-changeable configuration as domain
    types.
- **Domain exceptions** — a small hierarchy (`NotFoundException`, `ConflictException`, `ValidationException`)
  that the API modules map to HTTP status codes or gRPC status codes. Getting this right here is what stops
  `HttpStatus` leaking inward.

Add a Javadoc comment on every port explaining the contract and what an implementation must guarantee — these
interfaces are the architecture's documentation, and the Javadoc report the reactor generates will surface them.

## Step 3 — `application`: the use cases

One class per use case, named for the action (`PlaceOrderUseCase`), each with a single public method. It
depends only on `domain` ports via constructor injection, orchestrates them, and contains the application logic
that isn't domain-invariant. Write one worked example that touches at least a repository port and, if messaging is
enabled, an event publisher port — so the pattern of "use case composes ports" is visible.

If the manifest's concurrency model is reactive, the use case returns `Mono`/`Flux` and must not call anything
blocking; add a comment saying so, since this is the single most common way a reactive service quietly becomes a
broken one.

Write a unit test for the example use case that mocks every port with Mockito. It's the template for how use cases
get tested — no Spring context, no database.

## Step 4 — `infrastructure/database/<engine>`: the persistence adapters

Per engine directory in the manifest, write:

- **Entity** — the persistence model, separate from the domain model, carrying that engine's annotations
  (`@Document` for MongoDB/Elasticsearch, `@Entity`/`@Table` for JPA, `@Node` for Neo4j, `@Document` for
  Couchbase). Keeping it separate is the whole point: the database's shape may drift from the domain's.
- **Spring Data repository** — the interface extending the engine's repository type (or its reactive variant).
- **MapStruct mapper** — entity ↔ domain, `@Mapper(componentModel = "spring")`. The root pom sets
  `unmappedTargetPolicy=ERROR`, so an unmapped field fails compilation instead of silently returning null.
- **Adapter** — a `@Component` implementing the `domain` port, delegating to the Spring Data repository and the
  mapper. This is the only class in the module the rest of the service can reach through the port.
- **Migrations/index management**, per this engine's tooling from the manifest:
  - **Flamingock** — `src/main/java/<base-path>/infrastructure/database/mongodb/migration/` with one
    `@Change` class creating the example collection's indexes, enabled via `@EnableFlamingock` with a
    `@Stage(location = "<base-path>.infrastructure.database.mongodb.migration")` on the module's configuration
    class (the equivalent of Mongock's `migration-scan-package` property).
  - **Liquibase (SQL)** — `src/main/resources/db/changelog/db.changelog-master.yaml` including a first changeset
    that creates the example table and its indexes. If PostgreSQL was chosen and vectors may be stored, add a
    changeset with `CREATE EXTENSION IF NOT EXISTS vector;` and note it needs the `pgvector` image or a
    superuser grant.
  - **Flyway** — `src/main/resources/db/migration/V1__create_<table>.sql`.
  - **Liquibase for MongoDB / Couchbase / Neo4j** — the extension's own changelog format under
    `src/main/resources/db/changelog/`, with a first changeset creating the example indexes.
- **An integration test** (`*IT`) that exercises the adapter against the engine's Testcontainer. Write the test
  class here but let `iru-setup-java-springboot-testcontainers` own the base class it extends and the container
  wiring — this skill only writes the test that uses it, so the two skills don't both define the container.

Set `spring.data.<engine>.*` connection properties in the module's own `src/main/resources/application-<engine>.yml`
and import it from `boot`'s `application.yml`, so each adapter owns its own configuration surface.

## Step 5 — `infrastructure/configuration`: refreshable configuration

This module exists so that "what can be changed at runtime" is one auditable list.

- One `@ConfigurationProperties(prefix = "<service-prefix>.<area>")` record or class per area of tunable
  behaviour, with `jakarta.validation` constraints so a bad value fails fast at startup rather than at first use.
- Annotate each with `@RefreshScope` (or `@ConfigurationProperties` + `@RefreshScope` on a `@Bean` method) so a
  configuration refresh is picked up without a restart. Without this, dynamic configuration silently isn't dynamic
  — the single most common defect in a Spring Cloud Config setup.
- An adapter implementing the domain's configuration port, mapping properties to domain types so `domain` never
  reads `@Value` or knows a property name.
- Wire the mechanism from `stack.dynamicConfig`:
  - **kubernetes** — enable `spring.cloud.kubernetes.config`/`.secrets` with the ConfigMap and Secret names, set
    `spring.cloud.kubernetes.reload.enabled=true` with `mode: polling` or `event`, and note that watching
    requires RBAC to `get`/`list`/`watch` ConfigMaps — which the OpenTofu configuration must grant.
  - **aws-secrets-manager** — configure `spring.config.import: aws-secretsmanager:<name>` (and
    `aws-parameterstore:` for non-secret properties). Refresh comes from
    `management.endpoint.refresh` being invoked or the Spring Cloud AWS reload support; state which one you wired.
  - **spring-cloud-config** — `spring.config.import: configserver:<url>`, plus Spring Cloud Bus if the manifest
    asked for broadcast refresh.
  - **none** — write the `@ConfigurationProperties` beans anyway, without `@RefreshScope`, and note in the report
    that changes need a redeploy.
- Expose the `refresh` actuator endpoint (`management.endpoints.web.exposure.include`) only where it's actually
  needed, and never on a publicly-reachable port. Say so in the report — an open `refresh` endpoint is a real
  vulnerability, not a convenience.

**Every property defined here must end up in the documentation's configuration table** with its default, whether
it's dynamically changeable, and whether it's a critical performance/incident lever. Emit the list of properties
you created as part of Step 11's report so `iru-update-java-springboot-documentation` can seed that table.

## Step 6 — `infrastructure/clients/<name>`: outbound integrations

Per client, write an adapter implementing the domain's client port that:

- calls the **generated** client (OpenAPI-generated API class, or gRPC stub) — never a hand-rolled HTTP call;
- maps domain types ↔ generated DTOs with MapStruct, so generated types never escape the module;
- translates transport failures into domain exceptions, so no caller ever catches a
  `WebClientResponseException` or a `StatusRuntimeException`;
- carries timeout and retry configuration bound from `@ConfigurationProperties`, not hardcoded. A client with no
  timeout is an outage waiting for its trigger — set connect and read timeouts explicitly and mention the values
  chosen in the report;
- **takes its base URL (or gRPC target) from that same `@ConfigurationProperties` binding**, conventionally
  `clients.<name>.base-url`, with **no default value in the deployed profiles**. This is what lets the
  integration harness redirect the client to the API mock in the compose stack (`stack.apiMock` — Microcks or
  WireMock, wired by `iru-setup-java-springboot-testcontainers`); an adapter that hardcodes a host cannot be
  tested against anything and will reach a real downstream service from CI. Set the `local` profile's value to
  the mock's fixed compose port, and leave every deployed profile's value to be supplied by configuration.

`iru-setup-java-springboot-apis` owns the generator configuration and the spec files; this skill writes the adapter
that consumes the generated code. If the generated classes don't exist yet, write the adapter against the API
shape the starter spec declares and note that it compiles only after `mvn generate-sources`.

## Step 7 — `infrastructure/producers` and `api/consumers`: Kafka

Only if `stack.messaging.enabled`.

**Producers** (`directions` includes `produce`):

- An adapter implementing the domain's event-publisher port, using Spring Cloud Stream's `StreamBridge` (or a
  declared output binding) to send to the topic.
- A MapStruct mapper from domain event → the AVRO-generated class.
- Bindings in configuration: `spring.cloud.stream.bindings.<name>-out-0.destination`, the Kafka binder's broker
  list, and the Confluent serializer settings — `value.serializer` set to the AVRO serializer and
  `schema.registry.url` pointing at the registry.

**Consumers** (`directions` includes `consume`):

- A `@Bean Consumer<Message<<AvroType>>>` (or `Function<...>` when the consumer replies), named to match
  `spring.cloud.function.definition`.
- A MapStruct mapper AVRO → domain, then a call into the `application` use case.
- Explicit error handling: configure a dead-letter topic
  (`spring.cloud.stream.kafka.bindings.<name>-in-0.consumer.enableDlq=true`) and a bounded retry policy. A
  consumer with infinite retries on a poison message stalls the partition — write the DLQ configuration now, not
  after the first incident.
- Set `spring.cloud.stream.bindings.<name>-in-0.group` so the consumer group is stable across restarts, and say
  in the report what group name was chosen.

Note explicitly in the report that AVRO schema evolution rules (backward/forward compatibility) are enforced by
the schema registry's compatibility setting, and that this must be configured in the registry itself — the
scaffold can't do it.

Every `destination` and `group` chosen here has a second home: the AsyncAPI specification in `apis/messaging/`
(`iru-setup-java-springboot-apis`) declares one channel per topic, keyed by the same destination name, and that
file is what the published messaging documentation is generated from. Report the destinations and groups you set
so they can be reconciled with it — a binding whose topic doesn't appear as an AsyncAPI channel is a topic nobody
outside this repository can discover.

## Step 8 — `infrastructure/metrics`

Only if `stack.metrics`. Implement the domain's `MetricsPublisher` port over Micrometer's `MeterRegistry`:

- One method per measurement, each translating domain arguments into the metric name plus its tags.
- Keep the **tag cardinality bounded** — tag values must come from enums or small closed sets, never from an id,
  an email, a free-text field, or anything user-supplied. Unbounded tag cardinality is the standard way to take
  down a Prometheus instance; state this in the generated class's Javadoc and in the report.
- A constants class listing every metric name and every tag key, so the documentation's metrics table and the code
  can't drift.
- Emit the list of metrics created (name, type, tags, meaning) in Step 11's report so
  `iru-update-java-springboot-documentation` can seed the metrics table.

## Step 9 — `api/rest-server`, `api/grpc-server`, `api/graphql-server`

- **REST**: controllers implementing the **generated** interfaces from `apis/rest-server/`, a MapStruct mapper
  between generated DTOs and domain types, and a `@RestControllerAdvice` mapping each domain exception to a
  status code and a consistent error body (use RFC 9457 `ProblemDetail`, which Spring supports natively). Every
  error the advice can produce must appear in the API documentation page — emit the mapping in Step 11's report.
- **gRPC**: services extending the generated base classes from `apis/grpc-server/`, mappers, and an exception
  handler translating domain exceptions to `StatusRuntimeException` with the right `Status.Code` plus error
  details.
- **GraphQL**: `@Controller` classes implementing the **generated** resolver interfaces from
  `apis/graphql-server/`, annotating each method `@QueryMapping`/`@MutationMapping`/`@SchemaMapping`; MapStruct
  mappers between the generated model types and domain types; and a `DataFetcherExceptionResolver` translating
  domain exceptions into GraphQL errors with a stable `extensions.classification` — GraphQL answers `200 OK` with
  an `errors` array rather than an HTTP status, so exception mapping is entirely the application's job and there
  is no framework default that means anything to a client.
  - Add a `config/` class with `MaxQueryDepthInstrumentation` and `MaxQueryComplexityInstrumentation` beans.
    Unlike REST, where the URL bounds the work a request can ask for, a GraphQL query can nest arbitrarily deep;
    with no limit configured a single request can walk the object graph until the service falls over. Report the
    numbers chosen as a starting point to tune.
  - **Avoid the N+1 trap in the worked example.** A field resolver that loads a related aggregate per parent
    object issues one query per row; batch it with a `@BatchMapping` method or a `DataLoader`. Write the example
    the batched way, because whichever shape is scaffolded is the one that gets copied.
- If `stack.security.enabled`, add the security configuration here (not in `boot`): a `SecurityFilterChain` (or
  `SecurityWebFilterChain` for WebFlux) with the JWT resource-server configuration and per-endpoint
  authorization rules. Deny by default and permit explicitly — including for the actuator endpoints, where
  `/actuator/health` is typically open and everything else is not.

## Step 10 — `boot`: the composition root and configuration

- **`<Name>Application`** in `<base>` with `@SpringBootApplication`. Because the other modules' packages are all
  under `<base>`, the default component scan already finds them — no `scanBasePackages` list to maintain. Verify
  that assumption holds for the actual packages written and add an explicit `scanBasePackages` only if it
  doesn't.
- **`src/main/resources/application.yml`** — the shared baseline:
  - `spring.application.name` from the artifactId, and `spring.profiles.group` / `spring.config.import` pulling in
    each adapter module's own configuration file.
  - `spring.threads.virtual.enabled: true` if the concurrency model is blocking and Java is 21+ — it's the reason
    blocking is a reasonable default at this Java level. Do **not** set it for reactive.
  - `management.endpoints.web.exposure.include` limited to what's needed (`health`, `info`, `prometheus`, and
    `refresh` only if dynamic configuration needs it), `management.endpoint.health.probes.enabled: true` for
    Kubernetes liveness/readiness, and `management.server.port` on a separate port so actuator isn't exposed
    through the public ingress.
  - `server.shutdown: graceful` plus a `spring.lifecycle.timeout-per-shutdown-phase`, so a rolling deploy drains
    in-flight requests instead of dropping them.
- **Per-profile files** — `application-local.yml`, `application-dev.yml`, `application-prod.yml`:
  - `local` — points at the docker compose stack (`localhost` ports), human-readable console logging at `DEBUG`
    for `<base>`, and `spring.docker.compose.enabled: true` so the stack starts with the app.
  - `dev`/`prod` — structured JSON logging (`logging.structured.format.console: ecs` — Boot 3.4+ supports this
    natively, so no Logback XML is needed; verify the property name against the Boot version in use), `INFO`
    level, and every endpoint, host, and credential resolved from the environment or the secret manager. **No
    literal credential, host, or token in any committed profile file** — placeholders only
    (`${DATABASE_URI}`), with no defaults that would accidentally work against something real.
- **`config/`** — `@Configuration` classes that wire beans crossing module boundaries (e.g. selecting the primary
  repository adapter when CQRS gives two). This is the only place such a decision belongs.
- **A smoke test** `<Name>ApplicationIT` that starts the context against the Testcontainers stack. It's the test
  that catches a broken bean graph, which unit tests never will.

## Step 11 — The ArchUnit test

Write `<base>.architecture.HexagonalArchitectureTest` in `boot`'s test sources (it's the only module that sees
every other one, so it's the only place the whole graph can be checked). The Maven enforcer rules from
`iru-setup-java-springboot-pom` catch violations at module granularity; ArchUnit catches them at **package**
granularity, which is where they actually creep in — a domain class importing `org.springframework.data`, an
adapter imported directly instead of through its port.

Assert at least:

- `<base>.domain..` depends on no other `<base>` package, and on no `org.springframework..` or `jakarta.persistence..` class.
- `<base>.application..` may access `<base>.domain..` only.
- `<base>.infrastructure..` may access `<base>.domain..` only (never `application`, never another
  `infrastructure` subpackage).
- `<base>.api..` may access `<base>.application..` and `<base>.domain..` only.
- Classes named `*Controller`/`*Consumer` reside only in `<base>.api..`; `*Adapter` only in
  `<base>.infrastructure..`; `*UseCase` only in `<base>.application..`.
- The layered-architecture rule (`Architectures.layeredArchitecture()`) expressing the same thing as one
  declaration, so a new module can't be added outside the layering by accident.

Exclude generated sources from the ArchUnit import (`ImportOption.DoNotIncludeTests` where appropriate, plus a
location filter for `generated-sources`), or generated DTOs will fail the naming rules.

**Kotlin** — the same test, in `boot/src/test/kotlin/<base-path>/architecture/HexagonalArchitectureTest.kt`. ArchUnit
reads bytecode, so it checks Kotlin classes exactly as Java ones, and it works unchanged on Gradle (where the other
modules arrive on `boot`'s test classpath as jars — it is the **only** package-level boundary check there). Rules are
`@ArchTest val` properties (ArchUnit reads Kotlin's backing fields; no `@JvmField` needed). Two details that matter:
the generators `iru-setup-java-springboot-apis` configures emit into `*.generated.*` packages, so a location filter on
`/generated/` excludes them; and a rule over a module group the stack doesn't have (`api`, `clients`, a database)
fails with "failed to check any classes" unless marked `.allowEmptyShould(true)`:

```kotlin
@AnalyzeClasses(
    packages = [HexagonalArchitectureTest.BASE],
    importOptions = [ImportOption.DoNotIncludeTests::class, HexagonalArchitectureTest.ExcludeGenerated::class],
)
class HexagonalArchitectureTest {
    @ArchTest
    val domainIsIndependent: ArchRule =
        noClasses().that().resideInAPackage("$BASE.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$BASE.application..", "$BASE.infrastructure..", "$BASE.api..", "$BASE.config..",
                "org.springframework..", "jakarta.persistence..",
            )

    @ArchTest
    val applicationDependsOnDomainOnly: ArchRule =
        noClasses().that().resideInAPackage("$BASE.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$BASE.infrastructure..", "$BASE.api..", "$BASE.config..", "org.springframework..",
            )

    @ArchTest
    val infrastructureDependsOnDomainOnly: ArchRule =
        noClasses().that().resideInAPackage("$BASE.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("$BASE.application..", "$BASE.api..")

    // database, configuration, clients, producers, metrics never see each other ...
    @ArchTest
    val infrastructureAreasAreIsolated: ArchRule =
        slices().matching("$BASE.infrastructure.(*)..").should().notDependOnEachOther()

    // ... nor do two engines or two clients inside their groups
    @ArchTest
    val databaseEnginesAreIsolated: ArchRule =
        slices().matching("$BASE.infrastructure.database.(*)..").should().notDependOnEachOther()
            .allowEmptyShould(true)

    @ArchTest
    val clientsAreIsolated: ArchRule =
        slices().matching("$BASE.infrastructure.clients.(*)..").should().notDependOnEachOther()
            .allowEmptyShould(true)

    @ArchTest
    val apiNeverUsesInfrastructure: ArchRule =
        noClasses().that().resideInAPackage("$BASE.api..")
            .should().dependOnClassesThat().resideInAPackage("$BASE.infrastructure..")
            .allowEmptyShould(true)

    @ArchTest
    val adaptersResideInInfrastructure: ArchRule =
        classes().that().haveSimpleNameEndingWith("Adapter").should().resideInAPackage("$BASE.infrastructure..")

    @ArchTest
    val useCasesResideInApplication: ArchRule =
        classes().that().haveSimpleNameEndingWith("UseCase").should().resideInAPackage("$BASE.application..")

    @ArchTest
    val entryPointsResideInApi: ArchRule =
        classes().that().haveSimpleNameEndingWith("Controller").or().haveSimpleNameEndingWith("Consumer")
            .should().resideInAPackage("$BASE.api..")
            .allowEmptyShould(true)

    @ArchTest
    val layering: ArchRule =
        layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer(DOMAIN).definedBy("$BASE.domain..")
            .layer(APPLICATION).definedBy("$BASE.application..")
            .layer(INFRASTRUCTURE).definedBy("$BASE.infrastructure..")
            .optionalLayer(API).definedBy("$BASE.api..")
            .layer(BOOT).definedBy(BASE, "$BASE.config..")
            .whereLayer(BOOT).mayNotBeAccessedByAnyLayer()
            .whereLayer(API).mayOnlyBeAccessedByLayers(BOOT)
            .whereLayer(INFRASTRUCTURE).mayOnlyBeAccessedByLayers(BOOT)
            .whereLayer(APPLICATION).mayOnlyBeAccessedByLayers(API, BOOT)
            .whereLayer(DOMAIN).mayOnlyBeAccessedByLayers(APPLICATION, INFRASTRUCTURE, API, BOOT)

    /** Drops generated code (OpenAPI/protobuf/GraphQL/AVRO output lives in `*.generated.*` packages). */
    class ExcludeGenerated : ImportOption {
        override fun includes(location: Location): Boolean = !location.contains("/generated/")
    }

    /** Package and layer names shared by the rules. */
    companion object {
        /** The service's base package. */
        const val BASE = "<base>"
        private const val DOMAIN = "Domain"
        private const val APPLICATION = "Application"
        private const val INFRASTRUCTURE = "Infrastructure"
        private const val API = "Api"
        private const val BOOT = "Boot"
    }
}
```

Add `.allowEmptyShould(true)` to any other rule whose module group the manifest doesn't have, and give every rule a
one-line KDoc in the generated file. A negative check is cheap and worth doing once: a throwaway class in one
infrastructure module importing another's must fail `infrastructureAreasAreIsolated` (run `clean` first — a stale
class file in `target/`/`build/` keeps failing the test after the source is deleted).

## Step 12 — Verify

Delegate to the `iru-gate-runner` agent, asking for a compact result:

```bash
mvn -q clean test          # Maven (Java or Kotlin): everything compiles, unit tests and the ArchUnit test pass
./gradlew clean test       # Gradle (Kotlin): same — `test` excludes **/*IT* by the build-logic convention
```

The build must compile and the ArchUnit test must pass. If ArchUnit fails, **fix the code, not the rule** — a
relaxed rule on day one is a rule that never applies. The one exception is a rule that's wrong for this project
(e.g. the domain legitimately uses `spring-context` annotations by the team's choice); if so, change it
deliberately and report it as a deviation.

Don't run `mvn verify` (or `./gradlew integrationTest`) here: the `*IT` tests need the Testcontainers harness that
`iru-setup-java-springboot-testcontainers` sets up in the next step, so integration tests failing at this point is
expected rather than a defect.

On a first run inside `iru-setup-java-springboot` (which runs this skill before `-apis` and `-testcontainers`), two
things can't compile yet, and both are **compile** errors in `test`, not runtime failures: the `api/*` controllers
and `infrastructure/clients/<name>` adapters (the generated interfaces/clients they use don't exist until
`iru-setup-java-springboot-apis` wires the generators), and every `*IT` class (its base class — `AbstractComposeIT`,
`Abstract<Engine>IT` — is written by `iru-setup-java-springboot-testcontainers`; Gradle's `test` and Maven's
`test-compile` compile `*IT` sources too). Then run the command for the modules that need neither (e.g.
`./gradlew :domain:test :application:test :infrastructure:configuration:test`, Maven `-pl`), report the rest as
"verified by the orchestrator's Step 10", and don't treat it as a failure of this skill.

## Step 13 — Report

Summarize:

- The language and build tool used (`java`/`kotlin`, `maven`/`gradle`) — for Kotlin, that mappers are hand-written
  extension functions (no MapStruct/Lombok) and use cases are wired as `@Bean`s in `boot/config`.
- Whether the source tree was scaffolded from scratch or updated, and per pre-existing artifact the action taken
  (**created / updated / replaced / kept as is**), with each gap left in a kept file.
- Which modules got sources, and which were skipped because they already contained hand-written code.
- Every **port** defined in `domain` and which adapter implements it.
- The example aggregate/use case/endpoint written, and what it's called, so the user knows exactly what to rename
  or delete first.
- The **configuration properties** created, each with its prefix, default, whether it's `@RefreshScope`d, and
  whether it's a critical tuning lever — formatted so it can be pasted into the documentation's configuration
  table.
- The **metrics** created, each with name, type, and tag keys — same, for the metrics table.
- The **domain-exception → HTTP status / gRPC status** mapping, for the API documentation pages.
- Kafka bindings, consumer group names, and DLQ configuration, if any.
- The result of Step 12, and any ArchUnit rule deliberately relaxed.

Warn explicitly:

- **Every generated profile file uses placeholders, not real endpoints or credentials.** The service will not
  start against real infrastructure until those are supplied from the environment or the secret manager, and that
  is intentional — do not "fix" it by committing a value.
- The `refresh` actuator endpoint, if exposed, must not be reachable from outside the cluster.
- Metric tag cardinality must stay bounded; the generated code shows the pattern but can't enforce it.
- The example domain/use case/endpoint is scaffolding, not a feature — it exists to be replaced.
