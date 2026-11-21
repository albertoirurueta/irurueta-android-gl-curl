# Hexagonal architecture in a Quarkus multi-module build

**Read this only when the skill's Step 0 detected a hexagonal multi-module layout** — sibling modules named (or
suffixed) `domain`, `application`, `infrastructure`/`adapters`, and `api`/`rest`/`web`, wired through a parent
`pom.xml` `<modules>` or `settings.gradle(.kts)` `include(...)`, with dependencies pointing inward. A single-module
Quarkus service, or a loosely multi-module one without that shape, follows its existing feature packages instead,
and none of the rules below apply to it.

It answers two questions, in order: **which module does this code belong in**, and **what may that module
import**. The module names below are this catalog's defaults; map them onto the repository's actual names (e.g.
`core` for `domain`, `adapters/persistence` for `infrastructure/database`) before using the tables.

## The one rule

**Dependencies point inward.** `domain` depends on nothing. Every other module depends on `domain`. No module
depends on an adapter. Whenever an outer module provides a capability the inner code needs, the capability is a
**port** (an interface) in `domain`, and the outer module provides the **adapter** implementing it.

## Module reference

| Module | Responsibility | May depend on |
|---|---|---|
| `domain` | Entities, value objects, aggregates, domain services, domain events, domain exceptions, **port interfaces**. | Nothing in the project. Plain Java/Kotlin — no `io.quarkus.*`, no `jakarta.enterprise.*`, no `jakarta.persistence.*`, no `jakarta.ws.rs.*`, no Mutiny. (`jakarta.validation` annotations only if the module already uses them.) |
| `application` | Use cases — one class per use case, orchestrating domain objects through ports. | `domain`. CDI annotations (`@ApplicationScoped`, `jakarta.inject.Inject`) and `jakarta.transaction.Transactional` **only if this module already uses them**; otherwise use cases are plain classes and the app module produces them as beans. |
| `infrastructure/database/<engine>` | Hibernate/Panache entities and repositories, queries, entity ↔ domain mapping; implements the domain repository ports. | `domain` |
| `infrastructure/clients/<name>` | REST Client interfaces (`@RegisterRestClient`) / gRPC stubs and their adapters; implements the domain client port; maps DTOs ↔ domain. | `domain` |
| `infrastructure/messaging` | Outgoing messages (`@Channel Emitter`, Kafka/AMQP producers); implements the domain publisher port. | `domain` |
| `infrastructure/configuration` | `@ConfigMapping` interfaces and their mapping to domain types; implements any domain configuration port. | `domain` |
| `api/rest` | Quarkus REST resources, request/response DTOs, exception mappers. | `application`, `domain` |
| `api/messaging` | Incoming messages (`@Incoming` consumers). | `application`, `domain` |
| app/`boot` module (the one applying the `io.quarkus` plugin / `quarkus-maven-plugin`) | Packaging, `application.properties`, CDI producers wiring framework-free use cases, cross-module bean selection. The composition root. | every module |

## Quarkus-specific consequences

- **Only the app module runs Quarkus augmentation.** Library modules contribute beans only if they're indexed:
  a Jandex index (`io.smallrye:jandex-maven-plugin` / `org.kordamp.gradle.jandex`, or an empty
  `META-INF/beans.xml`) or `quarkus.index-dependency.<name>.*` in the app module. When an adapter's bean "doesn't
  exist" at build time, check the index before anything else — and if the module has none, report it rather than
  adding a dependency from the app module on implementation details.
- **Use cases without CDI.** If `application` has no CDI annotations, keep it that way: write the use case as a
  plain class with constructor parameters, and add a `@Produces @ApplicationScoped` method in the app module that
  builds it from the ports. Put `@Transactional` on the adapter or the producing/inbound layer the module already
  uses, never into `domain`.
- **Entities never cross the port.** A Panache entity is the persistence model; the adapter maps it to the domain
  type. A `domain` signature with an `@Entity`, a REST DTO, a REST Client DTO, or a Mutiny type is in the wrong
  place.
- **Reactive types stay at the edge.** Ports in `domain` are plain (or `suspend` in a coroutine-based Kotlin
  service); a reactive adapter adapts. Only introduce `Uni` in a port if the module's existing ports already do.

## What this forbids — each fails the structural check if the repository has one

- `io.quarkus.*`, `jakarta.enterprise.*`, `jakarta.persistence.*`, `jakarta.ws.rs.*`, or `io.smallrye.mutiny.*` in
  `domain`.
- `application` importing `infrastructure` or `api`.
- One `infrastructure` module importing another (orchestration across adapters is a use case's job).
- `api` importing `infrastructure` — a resource calls a use case, never a repository adapter.
- A generated type (OpenAPI DTO, protobuf, Avro) or a persistence entity escaping its module.

Structural checks, where they exist, are `maven-enforcer-plugin` `bannedDependencies` rules and/or an ArchUnit
test (usually in the app module, named `*ArchitectureTest`). Gradle builds may use ArchUnit or Konsist. This skill
doesn't run them; the group skill does.

## Where a given piece of work goes

| The task asks for | It goes in |
|---|---|
| A business rule, invariant, or calculation | `domain` |
| Something the outside world provides | `domain` (the port) + the adapter module (the implementation) |
| A use case / orchestration across ports | `application` |
| A query, index, entity mapping, migration | `infrastructure/database/<engine>` (migrations in whichever module holds `db/migration`, usually the app module) |
| A new config property | `infrastructure/configuration` (`@ConfigMapping`), value in the app module's `application.properties` |
| Calling another service | `infrastructure/clients/<name>` |
| Publishing a message | `infrastructure/messaging` |
| A REST endpoint | `api/rest` |
| Consuming a message | `api/messaging` |
| Bean wiring across modules, producers for framework-free use cases | app module |

## Order when a task spans modules

`domain` (types and port) → the adapter → `application` (use case) → `api` (entry point) → app module (wiring,
config, migrations) only if needed. Writing `domain` last produces a port shaped around a table row instead of a
business capability. Ports are named for intent (`OrderRepository`, `PaymentGateway`), adapters for technology
(`PanacheOrderRepositoryAdapter`).

## Testing implication

`domain` and `application` have no framework on their classpath, so their tests are plain JUnit + Mockito/MockK
and run in milliseconds. Adapters get plain unit tests for mapping and error translation, `@QuarkusComponentTest`
where CDI matters, and `@QuarkusTest`s — in the app module, which is the only one that can boot the application —
for the real database/HTTP behaviour, run by the group skill.
