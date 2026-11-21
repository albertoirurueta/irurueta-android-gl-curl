# Testing a Quarkus service

Every task's implementation comes with the tests that prove it. Quarkus has four test levels that trade isolation
for realism; which level a test is decides **who runs it** — this skill, or the calling group skill — because
two of the levels can start containers.

## The four levels

| Level | Annotation | Boots | Starts containers? | Run by |
|---|---|---|---|---|
| 1. Plain unit | none (JUnit 5 + Mockito / MockK) | nothing | never | **this skill** (and the group's coverage gate) |
| 2. Component | `@io.quarkus.test.component.QuarkusComponentTest` (`quarkus-junit-component`, formerly `quarkus-junit5-component`) | only the CDI beans under test; everything else mocked | never | **this skill** (and the group's coverage gate) |
| 3. Full app | `@io.quarkus.test.junit.QuarkusTest` (also `@QuarkusMainTest` for command mode) | the whole application in the test JVM, real HTTP on the test port (8081 by default) | **yes, via Dev Services**, whenever a Dev-Services-capable extension (JDBC/reactive datasource, Kafka, AMQP, RabbitMQ, Pulsar, Redis, MongoDB, Keycloak/OIDC, Elasticsearch, Infinispan, Apicurio, LGTM, Compose Dev Services, …) has no URL configured for the test profile; also via any `@QuarkusTestResource`/`@WithTestResource` that starts a Testcontainer | **the group skill only** |
| 4. Packaged | `@io.quarkus.test.junit.QuarkusIntegrationTest` (also `@QuarkusMainIntegrationTest`), classes named `*IT` | the packaged jar/native binary/container image as a separate process | yes, same rule as level 3 | **the group skill only** (Maven `verify`/Failsafe; Gradle `quarkusIntTest`/`testNative`) |

**Write the lowest level that can prove the behaviour.** Most of a task's tests are level 1 or 2; add a level-3
test for what only the full app can show (HTTP contract, serialization, validation, security annotations,
persistence mappings and queries, migrations) and a level-4 test only when the plan asks for packaged-artifact
coverage.

## Level 1 — plain unit tests

The default. Any class whose logic doesn't depend on CDI behaviour — constructor-injected services, mappers,
value objects, domain logic, exception mappers' `toResponse`, Mutiny pipelines (`UniAssertSubscriber`) — is
tested with `new` plus mocks. Java: JUnit 5 assertions and Mockito (`java.md`). Kotlin: JUnit 5 and MockK
(`kotlin.md`). No Quarkus annotation, no container, milliseconds.

## Level 2 — `@QuarkusComponentTest`

For a bean whose behaviour involves CDI itself: config injection, interceptors, `Instance<T>`/`@All`, events,
field injection that makes `new` impractical.

```java
@QuarkusComponentTest
@TestConfigProperty(key = "greeting.name", value = "Ann")
class GreetingServiceComponentTest {

    /** Bean under test, built by the component container. */
    @Inject
    GreetingService service;

    /** Collaborator replaced by a Mockito mock for every test. */
    @InjectMock
    Clock2 clock;

    /** Verifies the morning greeting when the clock reports 9 h. */
    @Test
    void greet_whenMorning_returnsGoodMorning() {
        when(clock.hour()).thenReturn(MORNING_HOUR);

        assertEquals("Good morning Ann", service.greet());
    }
}
```

- `@InjectMock` here is `io.quarkus.test.InjectMock` (Mockito-based, needs `quarkus-junit-mockito` /
  `quarkus-junit5-mockito`); unmocked collaborators the bean injects are auto-mocked.
- Supply configuration with `@TestConfigProperty`; `@ConfigMapping` interfaces work.
- Not available: HTTP layer, datasource, Dev Services, other extensions' beans (a Panache repository is fine to
  `@InjectMock`; a real `EntityManager` is not).
- Kotlin: `@Inject lateinit var` / `@InjectMock lateinit var` fields; Mockito's `` `when` `` (MockK doesn't
  integrate with `@InjectMock` unless the project uses the Quarkiverse `quarkus-junit5-mockk` extension — then
  follow it).

## Level 3 — `@QuarkusTest` (write it; don't run it here)

- RestAssured (`given().when().get(...).then().statusCode(...)`) for the HTTP contract; it already targets the
  test port.
- `@io.quarkus.test.junit.mockito.InjectMock` (normal-scoped beans only) / `@InjectSpy`, `QuarkusMock` per
  method, `PanacheMock` for Java active-record statics, `@TestSecurity` for secured endpoints,
  `@TestTransaction` to roll back a test's writes.
- **Minimise `@TestProfile`s.** Every distinct profile restarts the application for the classes using it. Reuse an
  existing `QuarkusTestProfile` class; add a new one only when the task genuinely needs different wiring, and say
  so in the report.
- Prefer Dev Services over a hand-written Testcontainers `QuarkusTestResourceLifecycleManager`; write a test
  resource only for something Dev Services doesn't provide (WireMock for a REST Client is the usual one).
- Naming stays `*Test` (it runs in Surefire / the Gradle `test` task). Only level-4 classes are `*IT`.

## Level 4 — `@QuarkusIntegrationTest`

Black box: no injection (`@Inject` fields stay null), no `@InjectMock`; HTTP/messaging only. Usually a subclass of
the level-3 test (`class FooResourceIT extends FooResourceTest`). Lives in `src/test` (Maven) or
`src/native-test` / `src/integrationTest` (Gradle).

## What this skill may run

Only levels 1 and 2, only the classes the task touched, and always **with Dev Services disabled** — so a
misclassified test fails fast with a "datasource deactivated / URL not set" configuration error instead of
quietly starting a container next to a sibling task's run:

```bash
# Maven (the -D is forwarded to the Surefire JVM)
mvn -q -pl <module> -am test -Dtest='FooTest,FooComponentTest' -Dquarkus.devservices.enabled=false -DskipITs
# Gradle (system properties don't reach the test JVM; the environment does)
QUARKUS_DEVSERVICES_ENABLED=false ./gradlew :<module>:test --tests 'com.example.FooTest' --tests 'com.example.FooComponentTest'
```

Never `@QuarkusTest`, `@QuarkusIntegrationTest`, `mvn verify`, `quarkusIntTest`, `testNative`, `quarkus:dev`, or
`quarkus:test` here — even a container-free `@QuarkusTest` binds the shared test port, so two parallel task agents
running one would collide. Those run once, in the group skill.

## Coverage implication (for the group's gate)

The group skill gates 80% coverage on levels 1 and 2 only. Two Quarkus facts shape what you write:

- Coverage earned only through a `@QuarkusTest` does **not** count toward the gate when that test needs
  containers. Logic worth testing should be reachable from a level-1/2 test.
- `@QuarkusComponentTest` coverage is only visible to JaCoCo when `quarkus-jacoco` (Quarkus ≥ 3.31) is on the
  test classpath; without it, a class tested *only* by a component test reports 0%. If the module lacks
  `quarkus-jacoco`, cover the core branches with a plain unit test too, or flag it in the report.

## Rules shared with the language files

Test method names, assertion library, mocking library, one test class per production class, Javadoc/KDoc on
tests, and member ordering come from `java.md` / `kotlin.md` — apply them to every level. The docs' examples use
AssertJ; this catalog doesn't: use JUnit assertions (and Hamcrest only inside RestAssured's `body(...)`).
