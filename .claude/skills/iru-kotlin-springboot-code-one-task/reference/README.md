# Kotlin Spring Boot development reference

Best practices, build requirements, architectural patterns, and code-style rules for implementing a task in a
Kotlin Spring Boot service — hexagonal multi-module reactor or plain layered/feature-packaged service, Maven or
Gradle. These files exist so `iru-kotlin-springboot-code-one-task` can load **only** what the task in front of it
actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

| Read | When |
|---|---|
| `build-plugins.md` | **Always**, before writing code. Kotlin's `final`-by-default classes break Spring proxies and Hibernate silently unless `kotlin-spring`, `kotlin-jpa` (+ `all-open` for JPA annotations), `-Xjsr305=strict`, `kotlin-reflect`, and `jackson-module-kotlin` are wired. The skill checks them first and adds what the task needs. |
| `hexagonal-architecture.md` | **Always.** Decides whether this is a hexagonal reactor (module rules apply) or a layered/feature service (follow its structure), then where the code goes and what it may depend on. |
| `mandatory-rules.md` | **Always.** JUnit 5 with project-consistent assertions (never AssertJ added), MockK not Mockito (springmockk `@MockkBean` for slices), test names per project convention (backticks by default), one `*Test`/`*IT` per class, `runTest`, `val` over `var`, immutability, no `!!`, null safety at Java boundaries, KDoc on public API, named constants, stdlib idioms, LF line endings, pinned locales, logging. |
| `code-style.md` | **Always.** Data classes (never for entities), sealed results, primary-constructor injection, `@ConfigurationProperties` constructor binding, extension and scope function discipline, member ordering, mapping. |
| `testing.md` | The task writes or changes tests (nearly always): unit-first, which slice when, MockK patterns, `@SpringBootTest` + Testcontainers `@ServiceConnection` as `*IT`. |
| `jpa-and-persistence.md` | The task touches a JPA entity, repository, query, transaction boundary, or migration. |
| `coroutines-and-webflux.md` | The task touches a `suspend` function, `Flow`, WebFlux, R2DBC/reactive repository, `WebClient`, or launches a coroutine. |
| `ddd-tactical-patterns.md` | The task touches domain code — an entity, value object, aggregate, domain service, domain event, or domain exception. |
| `solid.md` | The task introduces or changes an interface, adds a branch on type, or grows a class's responsibilities. |
| `distributed-patterns.md` | The task involves Kafka/a broker, a second datastore, cross-service consistency, or anything called "eventually consistent", "projection", "outbox", "saga", or "compensating". |

A one-line change inside an existing class needs only the four always-read files (and `testing.md` if it touches
a test). Don't read the rest speculatively.

## The three rules that override everything else

1. **Dependencies point inward — in the structure the repository actually has.** In a hexagonal reactor,
   `domain` depends on nothing and nothing depends on an adapter. In a layered service, web → service →
   persistence, never back. Never impose the hexagonal layout on a repository that doesn't use it. See
   `hexagonal-architecture.md`.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for; they
   never license adding an outbox, a projection, a saga, coroutines, or a new library the plan didn't ask for. The
   only build change the skill makes on its own is the Kotlin/Spring baseline plumbing in `build-plugins.md`, and
   only when the task needs it.
3. **Unit tests only.** This skill never runs integration tests, Testcontainers, or anything that starts Docker.
   See the skill's own Step 7.

`mandatory-rules.md` sits alongside these: where any other file says "match the surrounding code", its rules are
the exception — the code you write follows them even where the module's existing code doesn't.

## Sources

Compiled from the following, cross-checked against each other and against real builds (see "Verification notes"
in the skill). Where the internal docs and a live build disagreed, the verified behaviour wins and is called out in
the relevant file (notably Maven mixed-source compile order, and `kotlin-jpa` opening entities only from Kotlin
2.3.20).

- irurueta docs: `backend/springboot/java-or-kotlin.adoc` (kotlin-spring / kotlin-jpa, Kotlin vs Java)
- irurueta docs: `backend/springboot/concurrency-alternatives.adoc` (coroutine transactions, `executeAndAwait`,
  mixed Java + Kotlin Maven/Gradle setup)
- irurueta docs: `backend/springboot/core-concepts.adoc`, `hibernate.adoc`, `spring-data-jpa.adoc` (proxies and
  self-invocation, entity pitfalls, open-in-view, LAZY associations, `ddl-auto` + migrations)
- irurueta docs: `backend/springboot/unit-and-integration-testing.adoc` (test levels, slices, Testcontainers
  `@ServiceConnection`)
- irurueta docs: `backend/springboot/architectural-patterns.adoc` (hexagonal/layered structure)
- irurueta docs: `programming-languages/kotlin/testing.adoc`, `null-safety.adoc`, `data-classes-and-destructuring.adoc`,
  `sealed-classes-and-enums.adoc`, `extension-functions-and-scope-functions.adoc`, `coroutines-basics.adoc`,
  `lexical-structure-and-style.adoc`, `build-and-tooling.adoc`
- Kotlin all-open / no-arg plugins: <https://kotlinlang.org/docs/all-open-plugin.html>,
  <https://kotlinlang.org/docs/no-arg-plugin.html>
- Kotlin Maven configuration (mixed sources): <https://kotlinlang.org/docs/maven-configure-project.html>
- Spring Framework Kotlin support and coroutines:
  <https://docs.spring.io/spring-framework/reference/languages/kotlin.html>,
  <https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html>
- Spring Boot Kotlin support and testing: <https://docs.spring.io/spring-boot/reference/features/kotlin.html>,
  <https://docs.spring.io/spring-boot/reference/testing/index.html>
- Spring guide: <https://spring.io/guides/tutorials/spring-boot-kotlin/>
- MockK: <https://mockk.io/>; springmockk: <https://github.com/Ninja-Squad/springmockk>
- kotlinx-coroutines-test: <https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/>
- Coroutine transaction context issue: <https://github.com/spring-projects/spring-framework/issues/28290>
- Kotlin coding conventions: <https://kotlinlang.org/docs/coding-conventions.html>
- Ports and adapters, DDD, outbox, saga, CQRS, listen-to-yourself: as cited in
  `iru-java-springboot-code-one-task/reference/README.md` (microservices.io, learn.microsoft.com, Confluent).
