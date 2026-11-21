# Persistence: Hibernate ORM, Panache, transactions, migrations

Read this when the task touches an entity, a Panache entity or repository, a query, a transaction boundary, or a
schema migration.

## Detect which persistence shape the module uses — then follow it

| Signal | Shape |
|---|---|
| `quarkus-hibernate-orm-panache` / `-panache-kotlin`; entities `extends PanacheEntity(Base)` (Java) or `: PanacheEntity()` with a `companion object : PanacheCompanion<T>` (Kotlin) | Panache **active record** |
| `implements PanacheRepository<T>` / `PanacheRepositoryBase<T, ID>` classes (Kotlin: `io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository`) | Panache **repository** |
| `quarkus-hibernate-reactive-panache(-kotlin)`; methods return `Uni<…>` | **reactive** Panache (read `reactive-mutiny.md` too) |
| plain `EntityManager` / Jakarta Data / no Panache | plain Hibernate ORM |

Add new persistence code **in the shape the module already uses**. Never mix active record and repository for
the same entity, and never convert a module from one to the other as a side effect. If the module has no
persistence yet and the plan doesn't say, prefer the **repository** pattern: it is mockable with `@InjectMock`/
MockK and needs no `PanacheMock`. In a hexagonal build, the repository or entity lives only in the persistence
adapter module, behind a domain port (see `hexagonal-architecture.md`).

## Entities

- Java active-record entities use **public fields** — Panache rewrites field access into generated accessors at
  build time. That is a documented exception to `java.md`'s encapsulation/`final` rules; don't add getters/setters
  to a Panache entity that doesn't already have them. Entities are never `record`s, never `final`.
- Kotlin entities are regular classes, **never `data class`** (generated `equals`/`hashCode` over mutable,
  generated-id state breaks Hibernate's identity semantics). `lateinit var` for required non-id columns, or
  constructor `var` properties — the latter needs the **no-arg** plugin (`kotlin.md`).
- `PanacheEntity` gives a generated `Long id`; extend `PanacheEntityBase` and declare your own `@Id` for any other
  key type, matching siblings.
- Associations: `@ManyToOne(fetch = FetchType.LAZY)` (the JPA default is EAGER); never expose a mutable
  collection directly in a DTO. Watch for N+1 — use a fetch join or `@BatchSize` when listing with associations.
- Put derived query helpers next to the data: static methods on the active-record entity (Kotlin: in the
  companion), or methods on the repository. Use Panache's shortened HQL with **named parameters**
  (`find("status = :status", Parameters.with("status", s))`) — never string-concatenate user input into a query.
- Pagination via `.page(Page.of(index, size))`/`.range(...)`; sorting via `Sort.by(...)`; projections via
  `.project(Dto.class)` — don't load whole entities to return two columns.

## Transactions

- `@jakarta.transaction.Transactional` goes on the **calling service or resource method**, never on the entity
  or repository. Every write (`persist`, `delete`, update of a managed entity) must run inside one; Panache won't
  start one for you, and a write with no transaction fails at runtime.
- Default `TxType.REQUIRED`. Use `REQUIRES_NEW` only for work that must commit independently (audit, outbox
  write the plan calls for). Rollback is on unchecked exceptions by default; use `rollbackOn`/`dontRollbackOn`
  when a checked or "expected" exception changes that.
- `@Transactional` is an interceptor: it doesn't apply to self-invocation, `private` methods, or (Kotlin) a class
  that isn't opened. Programmatic alternative: `QuarkusTransaction.requiringNew().run { ... }` when the module
  uses it.
- Reactive: `@WithTransaction` / `Panache.withTransaction(() -> …)` and `@WithSession`; never `@Transactional`
  on a `Uni`-returning method.
- Keep remote calls and message publishing **outside** the transaction unless the plan asks for an outbox.

## Schema management

- Detect which mechanism owns the schema: `quarkus-flyway` (`src/main/resources/db/migration/V<n>__*.sql`) or
  `quarkus-liquibase` (`db/changeLog.*`), versus `quarkus.hibernate-orm.database.generation` /
  `quarkus.hibernate-orm.schema-management.strategy` alone.
- Where Flyway/Liquibase owns it, every entity change ships **with a new migration** in the next version slot —
  never edit an applied migration, never rely on Hibernate's `update`. Production stays
  `validate`/`none`; `drop-and-create` is acceptable only in `%dev`/`%test`, matching what's already configured.
- A migration must work on the real database kind (`quarkus.datasource.db-kind`), not just H2.
- `import.sql` seeds data only under `drop-and-create`; don't add production data there.

## Testing implication

- Service logic over a **repository** → plain unit test with a Mockito/MockK mock of the repository, or a
  `@QuarkusComponentTest` with `@InjectMock`.
- Service logic over **active record** → Java static calls need `PanacheMock.mock(Entity.class)`
  (`quarkus-panache-mock`), which only works inside `@QuarkusTest` — i.e. the group's container stage when a
  datasource exists. Kotlin companion calls can be stubbed with MockK's `mockkObject(Entity)` (the companion) in a
  plain unit test, every companion method the code calls stubbed — an unstubbed one hits Panache's
  un-enhanced default and throws. Either way, prefer pushing branching logic into a plain class that receives the
  query results, so it can be unit-tested without Quarkus.
- Queries, mappings, and migrations themselves are verified by a `@QuarkusTest` against the Dev Services
  database — write it, but leave running it to the group skill (`testing.md`).
