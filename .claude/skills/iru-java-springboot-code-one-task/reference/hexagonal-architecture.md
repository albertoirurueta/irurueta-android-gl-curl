# Hexagonal architecture: modules and allowed dependencies

Read this before writing any code. It answers two questions, in order: **which module does this code belong
in**, and **what is that module allowed to import**. A task implemented in the wrong module is not a style
problem — it is a structural defect that costs a cross-module move to fix.

## The one rule

**Dependencies point inward.** `domain` is the centre and depends on nothing. Every other module depends on
`domain`. No module depends on an adapter. Business logic never imports a framework, a driver, or a
generated DTO.

The corollary that makes this practical: **whenever an outer module provides a capability the inner code needs,
that capability is declared in `domain` as an interface (a port), and the outer module implements it (an
adapter).** A use case that needs to save an order does not know a database exists; it calls
`OrderRepository`, which is an interface in `domain`.

## Module reference

| Module | Responsibility | May depend on |
|---|---|---|
| `domain` | Domain entities, value objects, aggregates, domain services, domain events, domain exceptions, and **the port interfaces every outer module implements**. Shared by every other module — which is exactly why it must stay free of infrastructure. | Nothing in this project. Plain Java, plus `jakarta.validation` annotations if the project already uses them there. |
| `application` | Application logic and use cases — one class per use case, orchestrating domain objects and ports. | `domain` |
| `infrastructure/database/<engine>` | One module per database technology (mongodb, couchbase, postgresql, …). Implements the `domain` repository/persistence ports: the real queries, the persistence entities, and the mapping from persistence entity to domain entity. | `domain` |
| `infrastructure/configuration` | Refreshable configuration beans holding current configuration; may map configuration to domain types. Implements any `domain` configuration port. | `domain` |
| `infrastructure/clients/<name>` | One module per REST or gRPC client. Implements the `domain` client port: makes the request, and maps between domain entities and the API DTOs. | `domain` |
| `infrastructure/producers` | Kafka producers that send messages to their topics. Implements the `domain` event-publisher ports and maps domain entities to AVRO. | `domain` |
| `infrastructure/metrics` | Implements the `domain` metrics port: sends metrics with the right tags, mapping from domain entities to tag values. | `domain` |
| `api/rest-server` | Handles incoming REST requests. | `application`, `domain` |
| `api/grpc-server` | Handles incoming gRPC requests. | `application`, `domain` |
| `api/consumers` | Handles incoming Kafka messages by consuming their topics. | `application`, `domain` |
| `boot` | Application start-up and general configuration. The composition root. | Every module. |

## What this forbids, explicitly

These are the violations that actually happen. Each one fails the build (via the `maven-enforcer-plugin`
`bannedDependencies` rules and the ArchUnit test), so writing one wastes a build cycle rather than sneaking
through:

- **A Spring, Spring Data, or JPA import in `domain`.** No `@Entity`, `@Document`, `@Component`, `@Service`,
  `@Autowired`, `@Transactional`, `@Value`, `HttpStatus`, or `ResponseEntity`. If domain code seems to need
  one, the need belongs in an adapter.
- **`application` importing anything from `infrastructure` or `api`.** A use case that needs a database calls a
  port. A use case that needs to know the HTTP status code is doing the API module's job.
- **One `infrastructure` module importing another.** The mongodb adapter cannot call the metrics adapter. If a
  database write must also emit a metric, the *use case* orchestrates both ports — that ordering is application
  logic, not persistence logic.
- **An `api` module importing an `infrastructure` module.** A controller calls a use case, or a domain service.
  It never calls a repository adapter directly, even when that would be fewer lines.
- **A generated type escaping its module.** An OpenAPI DTO, a protobuf message, or an AVRO record must be
  mapped to a domain type inside the module that generated it. If a generated class appears in a `domain` or
  `application` signature, the mapping is in the wrong place.
- **A persistence entity used as a domain entity.** They are separate types on purpose: the database's shape is
  free to drift from the domain's, and coupling them means every schema change is a domain change.

## Where a given piece of work goes

| The task asks for | It goes in |
|---|---|
| A new business rule, invariant, or calculation | `domain` (entity, value object, or domain service) |
| A new interface for something the outside world provides | `domain` — the port. The implementation goes in the adapter module. |
| A new use case, or orchestration across several ports | `application` |
| A new query, index, or persistence mapping | `infrastructure/database/<engine>` |
| A new configuration property, or making one refreshable | `infrastructure/configuration` |
| Calling another service | `infrastructure/clients/<name>` (implementing a `domain` port) |
| Publishing a Kafka message | `infrastructure/producers` (implementing a `domain` port) |
| Recording a metric | `infrastructure/metrics` (implementing a `domain` port) |
| A new REST endpoint | `api/rest-server` — implementing the generated interface |
| A new gRPC method | `api/grpc-server` — extending the generated base class |
| Consuming a Kafka topic | `api/consumers` |
| Bean wiring that crosses module boundaries, or picking between two adapters | `boot` |

## The pattern to follow when a task spans modules

Most non-trivial tasks touch two or three modules. The order that works:

1. **`domain` first** — the types and the port interface. This is the contract; everything else is written
   against it.
2. **The adapter** — implement the port in whichever `infrastructure` module owns that technology.
3. **`application`** — the use case that composes the ports.
4. **`api`** — the entry point that calls the use case.
5. **`boot`** — only if new cross-module wiring is genuinely needed.

Writing `domain` last is what produces a port shaped around a database row instead of around the business
capability.

## Ports are named for intent, not technology

`PaymentPort`, not `StripePort`. `OrderRepository`, not `MongoOrderRepository`. `MetricsPublisher`, not
`PrometheusClient`. The port names what the business needs; the adapter's own class name is free to say which
technology provides it (`MongoOrderRepositoryAdapter`). A port named after a vendor has already leaked the
decision it was supposed to hide.

## Testing implication

Because `domain` and `application` have no framework on their classpath, their tests need **no Spring context**
— plain JUnit plus Mockito for the ports. Those tests run in milliseconds, and they are the tests this skill
writes and runs. Anything that needs a real database, broker, or HTTP server is an integration test, is named
`*IT`, and is explicitly **out of scope** for this skill.
