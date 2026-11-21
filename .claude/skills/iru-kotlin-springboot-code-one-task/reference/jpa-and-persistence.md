# JPA and persistence in Kotlin

Read this when the task adds or changes a JPA entity, embeddable, repository, query, transaction boundary, or
database migration. Before the first entity in a module, `build-plugins.md`'s `jpa` + `all-open` check must already
have passed (Step 2 of the skill).

## Entities are regular classes

```kotlin
/**
 * Persistence model of an order. Mapped to and from the domain [Order]; never exposed outside this adapter.
 *
 * @property customer the owning customer, loaded lazily.
 * @property status the current status.
 */
@Entity
@Table(name = "purchase_order")
class OrderEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    var customer: CustomerEntity,

    @Enumerated(EnumType.STRING)
    var status: OrderStatus,
) {
    /** Database identifier; `null` until the entity is first persisted. */
    @Id
    @GeneratedValue
    var id: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as OrderEntity
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "OrderEntity(id=$id, status=$status)"
}
```

- **A regular `class`, never a `data class`** — see `code-style.md`. Mutable persistent state is `var`; the
  constructor takes what a valid new instance needs.
- **`equals`/`hashCode` on the identifier only.** Two transient instances (`id == null`) are never equal; a constant
  `hashCode` keeps the hash stable across the persist that assigns the id (so a `Set` holding the entity doesn't
  lose it). Compare classes with `Hibernate.getClass(...)` (or `HibernateProxy`-aware equivalent) so a lazy proxy
  equals its target. If the entity has a natural business key that never changes, using it instead is fine.
- **`toString` never touches an association** — it would trigger lazy loading (or a
  `LazyInitializationException` outside a transaction). List the id and simple columns only.
- **Ids**: a generated `Long?`/`UUID?` `var` with a `null` default, set by the provider. A client-assigned id
  (`UUID` generated in the constructor) is fine too, and makes `equals` simpler — match what the module does.
- **Use the `@field:`/property targets only where needed** — Jakarta Persistence annotations on a constructor `var`
  apply to the field automatically for most mappings; Bean Validation annotations on a constructor property need
  `@field:NotBlank`, or they land on the constructor parameter and are ignored.
- **Collections**: `@OneToMany(mappedBy = ...) val lines: MutableList<OrderLineEntity> = mutableListOf()` kept
  private-ish (expose a read-only view and mutate through methods that keep both sides in sync).
- **Enums** are `@Enumerated(EnumType.STRING)`, never the ordinal default.

The domain type is separate: an adapter maps entity ↔ domain (an extension function such as
`fun OrderEntity.toDomain(): Order` works well). In a hexagonal reactor this is mandatory
(`hexagonal-architecture.md`); in a layered service, follow what the project does, but don't return an entity
from a REST controller in new code.

## Associations are LAZY

`@ManyToOne` and `@OneToOne` default to `EAGER` — set `fetch = FetchType.LAZY` on every new one. (`@OneToMany`/
`@ManyToMany` are already lazy.) Load what a use case needs explicitly — a `JOIN FETCH` query, an `@EntityGraph` on
the repository method, or a DTO projection — instead of relying on eager defaults or touching associations in a
loop (the N+1 problem). Lazy loading of `@ManyToOne` works only because the entity is `open` (the `all-open` check
in `build-plugins.md`); a `final` entity silently loads eagerly.

## Configuration that must hold

- `spring.jpa.open-in-view=false`. Open-in-view keeps a session open for the whole web request, so lazy loads in
  the controller or the JSON serializer "work" — and issue queries outside any transaction. With it off, a lazy
  access outside a transaction fails fast, which is the point. If the project has it on, don't flip it in an
  unrelated task (it can break existing endpoints) — note it in the report.
- `spring.jpa.hibernate.ddl-auto=validate` (or `none`) in every non-test profile; schema changes go through the
  project's migration tool (Flyway `db/migration/V<n>__<description>.sql`, or Liquibase changelogs). A task that
  adds or changes an entity's mapping **adds a migration** in the same task — a new, never-edited-after-merge
  file with the next version number. Never edit an already-applied migration. Tests may use `create-drop` only
  if the project's test profile already does.

## Repositories

- Spring Data interfaces (`interface OrderJpaRepository : JpaRepository<OrderEntity, Long>`). Use
  `findByIdOrNull(id)` (Kotlin extension in `org.springframework.data.repository`) instead of `findById(id)` +
  `Optional`.
- Derived query names for simple lookups; `@Query` with named parameters (`:customerId`) for anything else. Never
  build JPQL/SQL by string concatenation with input.
- Return `List<T>` / `T?` / `Page<T>` — never `Optional` in a Kotlin signature.
- For read-heavy paths, a DTO projection (`interface OrderSummary { val id: Long; val total: BigDecimal }` or a
  constructor expression) instead of loading entity graphs.

## Transactions

- `@Transactional` sits on the **service / use-case** method that defines the unit of work (or on the adapter in a
  hexagonal reactor, where `application` has no Spring) — not on the controller, and not on repository interfaces
  (Spring Data's are already transactional per call). Reads get `@Transactional(readOnly = true)`.
- The bean must be proxyable: `kotlin-spring` opens `@Transactional` classes and `@Component`s; a `final` method on
  a hand-instantiated or unannotated class is silently not intercepted.
- **Self-invocation bypasses the proxy.** `this.otherMethod()` from inside the same bean skips `@Transactional`,
  `@Cacheable`, `@Async`, `@Retryable` — the annotation on `otherMethod` is silently ignored. If a method needs its
  own transactional semantics (`REQUIRES_NEW`, `readOnly`), move it to a separate bean or use
  `TransactionTemplate`. Don't "fix" it with `AopContext.currentProxy()` or self-injection.
- Checked exceptions don't exist in Kotlin, so rollback rules are simple: any `RuntimeException`/`Error` thrown out
  of the method rolls back. If a domain exception must roll back and extends `Exception` directly, it won't —
  domain exceptions should extend `RuntimeException`.
- Don't call a remote service or publish a message inside a database transaction expecting atomicity — that's the
  dual-write problem (`distributed-patterns.md`).
- `@Transactional` on a `suspend` function needs a `ReactiveTransactionManager` — see
  `coroutines-and-webflux.md`. With blocking JPA, keep service methods non-suspending.

## Testing persistence

Unit-test mappers and query-building logic with plain JUnit + MockK. A `@DataJpaTest` against an **embedded**
database (H2/HSQLDB on the test classpath) is a fast slice and may run as a unit test if the module already uses
it. A `@DataJpaTest` or `@SpringBootTest` against a real PostgreSQL/MySQL through Testcontainers is an
**integration test** (`*IT`) and out of this skill's scope — see `testing.md`.
