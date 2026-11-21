# Spring Boot development reference

Best practices, architectural patterns, and code-style rules for implementing a task in a DDD/hexagonal
Spring Boot service. These files exist so `iru-java-springboot-code-one-task` can load **only** what the task in
front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

| Read | When |
|---|---|
| `hexagonal-architecture.md` | **Always.** It decides which module the code belongs in and what that module may import. Getting this wrong is the one mistake that can't be fixed later without moving code between modules. |
| `mandatory-rules.md` | **Always.** The non-negotiable rules for production *and* test code: JUnit not AssertJ, Mockito mocks/spies not fakes, `method_whenCondition_expectedResult` test names, one `*Test`/`*IT` class per public class, `var` with type witnesses, records with defensive copies, `final` parameters, Javadoc on every member, named constants, modern collection accessors, method references, `switch` expressions, no redundant `this`, utility predicates, LF line endings — plus the Spring-only ones: pinned locales, and Lombok `@RequiredArgsConstructor`/`@Slf4j`. |
| `code-style.md` | **Always.** Records, Lombok, MapStruct-versus-manual, `final`, type inference, public-to-private member ordering, Javadoc. |
| `ddd-tactical-patterns.md` | The task touches `domain` — an entity, value object, aggregate, domain service, or domain event. |
| `solid.md` | The task introduces or changes an interface, adds a branch on type, or grows an existing class's responsibilities. Also read it when a task asks for something that smells like a god-service. |
| `distributed-patterns.md` | The task involves Kafka, a second datastore, cross-service consistency, or anything the plan describes as "eventually consistent", "projection", "outbox", "saga", or "compensating". |

If a task is a one-line change inside an existing adapter, `hexagonal-architecture.md`, `mandatory-rules.md`, and
`code-style.md` are the whole reading list. Don't read the rest speculatively.

## The three rules that override everything else

1. **Dependencies point inward.** `domain` depends on nothing. Everything else depends on `domain`. Nothing
   depends on an adapter. See `hexagonal-architecture.md`.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for; they
   never license adding an outbox table, a CQRS read model, or a saga that the plan didn't ask for. Patterns in
   `distributed-patterns.md` are there so you recognise one when the plan calls for it and implement it
   correctly — not so you introduce one on your own initiative.
3. **Unit tests only.** This skill never runs integration tests. See the skill's own Step 6.

`mandatory-rules.md` sits alongside these: where any other file here says "match the surrounding code", its
rules are the exception — the code you write follows them even where the module's existing code doesn't.

## Sources

These files were compiled from the following, cross-checked against each other. Where sources disagreed
(notably on MapStruct-versus-manual mapping, where most write-ups assume MapStruct always wins) this
catalog's own stated preference takes precedence and is called out as such in the relevant file.

- Ports and adapters in Spring Boot: <https://www.arhohuttunen.com/hexagonal-architecture-spring-boot/>,
  <https://kamilmazurek.pl/hexagonal-architecture-template>
- DDD aggregates and value objects: <https://www.baeldung.com/spring-persisting-ddd-aggregates>,
  <https://github.com/SAP/curated-resources-for-domain-driven-design/blob/main/blog/0004-how-to-develop-aggregates.md>,
  <https://medium.com/unil-ci-software-engineering/clean-ddd-lessons-validation-and-immutability-a82292ba2a93>
- SOLID in Spring: <https://www.javacodegeeks.com/2024/09/spring-boot-and-solid-a-perfect-match.html>
- Transactional outbox: <https://microservices.io/patterns/data/transactional-outbox.html>
- Saga: <https://microservices.io/patterns/data/saga.html>
- CQRS: <https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs>
- Listen-to-yourself: <https://developer.confluent.io/courses/microservices/the-listen-to-yourself-pattern/>,
  <https://codeopinion.com/listen-to-yourself-pattern-is-it-an-alternative-to-the-outbox-pattern/>
- MapStruct with Lombok: <https://bootify.io/spring-data/mapstruct-with-maven-and-lombok.html>
