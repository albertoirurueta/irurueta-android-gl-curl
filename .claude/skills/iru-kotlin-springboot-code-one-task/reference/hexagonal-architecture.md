# Architecture: where the code goes

Read this before writing any code. It answers two questions, in order: **which module or package does this code
belong in**, and **what is it allowed to depend on**.

## First: is this repository a hexagonal reactor?

The module rules below apply **only when the repository is laid out as a hexagonal (ports-and-adapters)
multi-module build** — the shape `iru-setup-java-springboot` scaffolds. Treat it as one when most of these hold:

- Build modules named `domain`, `application`, `boot`, and module groups `infrastructure/*` and `api/*` (Maven
  `<modules>` in the root `pom.xml`; Gradle `include(...)` in `settings.gradle(.kts)`).
- Boundary enforcement exists: `maven-enforcer-plugin` `bannedDependencies` rules, an ArchUnit test (typically
  `*ArchitectureTest` under `boot`), or Gradle module dependencies that only point inward.
- `domain` has no Spring dependency at all.

**If it isn't**, don't impose this layout and don't create modules. Follow the structure the repository already
has — see "Layered or feature-packaged services" at the end of this file — and keep only the portable parts of the
idea: dependencies point from web → service → persistence, never back, and business rules don't import the
framework where the existing code avoids it.

## The one rule (hexagonal)

**Dependencies point inward.** `domain` is the centre and depends on nothing. Every other module depends on
`domain`. No module depends on an adapter. Business logic never imports a framework, a driver, or a generated DTO.

Whenever an outer module provides a capability the inner code needs, that capability is declared in `domain` as an
interface (a port) and the outer module implements it (an adapter). A use case that saves an order calls
`OrderRepository`, an interface in `domain`.

## Module reference

| Module | Responsibility | May depend on |
|---|---|---|
| `domain` | Entities, value objects, aggregates, domain services, domain events, domain exceptions, and **the port interfaces** every outer module implements. | Nothing in this project. Kotlin stdlib (and `jakarta.validation` only if already used there). No Spring, no JPA, no coroutines-reactor. |
| `application` | Use cases — one class per use case, orchestrating domain objects and ports. | `domain` (plus `kotlinx-coroutines-core` if use cases are `suspend` and the module already has it). |
| `infrastructure/database/<engine>` | Implements persistence ports: queries, persistence entities (`jpa-and-persistence.md`), entity ↔ domain mapping. | `domain` |
| `infrastructure/configuration` | `@ConfigurationProperties` holders, mapping configuration to domain types. | `domain` |
| `infrastructure/clients/<name>` | One module per outbound REST/gRPC client; implements the client port, maps API DTOs ↔ domain. | `domain` |
| `infrastructure/producers` | Kafka producers implementing event-publisher ports; domain → AVRO mapping. | `domain` |
| `infrastructure/metrics` | Implements the metrics port. | `domain` |
| `api/rest-server` | Incoming REST (implementing generated interfaces where the build generates them). | `application`, `domain` |
| `api/grpc-server` | Incoming gRPC. | `application`, `domain` |
| `api/consumers` | Incoming Kafka messages. | `application`, `domain` |
| `boot` | Start-up, composition root, cross-module wiring. | Every module. |

## What this forbids

Each of these fails the build (enforcer rules, the ArchUnit test — which inspects bytecode, so it applies to
Kotlin classes exactly as to Java ones — or Gradle's module graph), so writing one wastes a build cycle:

- **A Spring, Spring Data, or JPA import in `domain`** — no `@Entity`, `@Component`, `@Service`, `@Transactional`,
  `@Value`, `HttpStatus`, `ResponseEntity`. If domain code seems to need one, the need belongs in an adapter.
- **`application` importing anything from `infrastructure` or `api`.**
- **One `infrastructure` module importing another.** If a write must also emit a metric, the use case orchestrates
  both ports.
- **An `api` module importing an `infrastructure` module.** A controller calls a use case.
- **A generated type escaping its module** (OpenAPI DTO, protobuf message, AVRO record) — map to a domain type
  inside the generating module.
- **A persistence entity used as a domain entity.** Separate types on purpose.
- **Kotlin-specific leak**: a public extension function in an adapter module that takes or returns a domain type
  is fine; one that `domain` or `application` would need to import is a dependency pointing outward. Keep adapter
  extensions `internal`.

## Where a given piece of work goes (hexagonal)

| The task asks for | It goes in |
|---|---|
| A business rule, invariant, or calculation | `domain` (entity, value object, or domain service) |
| An interface for something the outside world provides | `domain` (the port); the implementation in the adapter module |
| A use case or orchestration across ports | `application` |
| A query, index, entity, or migration | `infrastructure/database/<engine>` |
| A configuration property | `infrastructure/configuration` |
| Calling another service | `infrastructure/clients/<name>` |
| Publishing a Kafka message | `infrastructure/producers` |
| Recording a metric | `infrastructure/metrics` |
| A REST endpoint | `api/rest-server` |
| A gRPC method | `api/grpc-server` |
| Consuming a Kafka topic | `api/consumers` |
| Cross-module bean wiring, choosing between adapters | `boot` |

Since `application` and `domain` have no Spring, their classes carry no `@Service`/`@Component`; `boot` declares
them as beans (`@Bean fun placeOrder(orders: OrderRepository) = PlaceOrderUseCase(orders)`) or via the
project's existing wiring convention. `@Transactional`, if needed, goes on the adapter or on a `boot`-level
decorator — follow what the reactor already does.

## Order of work when a task spans modules

1. `domain` — types and the port interface (the contract).
2. The adapter implementing the port.
3. `application` — the use case composing ports.
4. `api` — the entry point calling the use case.
5. `boot` — only if new wiring is genuinely needed.

## Ports are named for intent, not technology

`PaymentPort`, not `StripePort`; `OrderRepository`, not `PostgresOrderRepository`. The adapter's class name may
name the technology (`JpaOrderRepositoryAdapter`).

## Layered or feature-packaged services

Most single-module Kotlin Spring Boot services are organised either **by layer** (`controller/`, `service/`,
`repository/`, `model|entity/`, `dto/`, `config/`) or **by feature** (`orders/`, `customers/`, each holding its own
controller, service, repository, entities). Detect which from the existing packages and put new code where its
siblings live:

| The task asks for | Layered | By feature |
|---|---|---|
| An endpoint | `controller` (or `web`/`api`) | `<feature>/<Feature>Controller.kt` |
| Business logic / a use case | `service` | `<feature>/<Feature>Service.kt` (or a use-case class) |
| An entity / query | `entity`/`model` + `repository` | `<feature>/` |
| A request/response type | `dto` (or alongside the controller) | `<feature>/` |
| Configuration | `config` | `config` or `<feature>/` |

Rules that still hold: controllers stay thin (validate input, call a service, map the result); services hold the
logic and the transaction boundary; repositories hold queries only; entities never leave the service layer as API
responses in new code; dependencies flow controller → service → repository, never backwards and never sideways
between features' internals (call another feature's service, not its repository). If the existing code violates
these, match the module for consistency and note it — don't refactor it in an unrelated task.

## Testing implication

In a hexagonal reactor `domain` and `application` tests need no Spring context: plain JUnit + MockK for the ports.
In a layered service the same holds for services and mappers. Anything needing a real database, broker, or HTTP
server is an `*IT` and out of scope for this skill.
