---
name: iru-setup-java-springboot-testcontainers
description: "Invoked as `/iru-setup-java-springboot-testcontainers` (args: `stack-file`, `mode`, `on-existing`), normally by `iru-setup-java-springboot`. Sets up `compose.yaml` (stack services, Prometheus/Grafana, API mocks) and a Testcontainers integration-test harness for a Spring Boot service, verified with the build's integration tests."
model: sonnet
---

# Setup Java Spring Boot Testcontainers

Give the service one definition of its runtime dependencies, used by both `mvn verify` and a developer running the
app locally. The single most valuable property of this setup is that those two paths **cannot drift**: the
integration tests start the same `compose.yaml` a developer starts, so "works on my machine" and "passes in CI"
converge by construction.

Every image tag is pinned to a concrete version — never `latest`. An unpinned tag turns a passing test suite into
a time bomb that fails on an unrelated day for an unrelated reason.

## Step 0 — Resolve inputs and check prerequisites

Parse `args` for `key: value` lines: `stack-file: <path>`, defaulting to `springboot-stack.yml`; `mode`
(`new`/`existing`, informational — it never means "update silently"); and `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`). Read the manifest — including its `ci:` block, which only
affects what Step 7 reports (see there) — and its `project.language` (`java` | `kotlin`; absent ⇒ `java`) and
`project.buildTool` (`maven` | `gradle`; absent ⇒ `maven`). **Java (or absent) follows every step exactly as
written.** Kotlin writes the test classes in `src/test/kotlin` from the Kotlin templates in Step 2; Gradle replaces
the Maven Failsafe/`pom.xml` parts as Steps 2, 5 and 6 say. `compose.yaml`, the observability files and the mock
configuration are identical for every language and build tool. Then build the list of services needed:

| Manifest | Compose service | Image | Default port |
|---|---|---|---|
| `databases[].engine: mongodb` | `mongodb` | `mongo:<tag>` | 27017 |
| `databases[].engine: couchbase` | `couchbase` | `couchbase/server:<tag>` | 8091–8096, 11210 |
| `databases[].engine: postgresql` | `postgres` | `pgvector/pgvector:pg<tag>` (or `postgres:<tag>` if vectors are definitely unused) | 5432 |
| `databases[].engine: mariadb` | `mariadb` | `mariadb:<tag>` | 3306 |
| `databases[].engine: elasticsearch` | `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:<tag>` | 9200 |
| `databases[].engine: neo4j` | `neo4j` | `neo4j:<tag>` | 7687, 7474 |
| `databases[].engine: qdrant` | `qdrant` | `qdrant/qdrant:<tag>` | 6333, 6334 |
| `caches` contains `redis` | `redis` | `redis:<tag>` | 6379 |
| `messaging.enabled` | `kafka` | `apache/kafka:<tag>` (KRaft, no ZooKeeper) | 9092 |
| `messaging.enabled` | `schema-registry` | `confluentinc/cp-schema-registry:<tag>` | 8081 |
| `metrics: true` | `prometheus` | `prom/prometheus:<tag>` | 9090 |
| `metrics: true` | `grafana` | `grafana/grafana:<tag>` | 3000 |
| `restClients`, `grpcClients` and/or `graphqlClients` non-empty | `microcks` **or** `wiremock` (see below) | `quay.io/microcks/microcks-uber:<tag>` / `wiremock/wiremock:<tag>` | 8080 (+ 9090 for Microcks gRPC) |

`caches` containing `caffeine` needs no container — it's in-process. Note that in the report so its absence
doesn't look like an omission.

### Choosing the API mocking tool

If `stack.restClients`, `stack.grpcClients` and `stack.graphqlClients` are all empty, there is nothing downstream to mock — skip every
mock-related part of this skill and say so in the report. Otherwise the stack needs exactly one mock service,
because an integration test that calls a real downstream service is not an integration test of this service: it
is slow, it fails when someone else deploys, and it can't reproduce the error responses the adapter is supposed
to handle.

Read `stack.apiMock` from the manifest if it's recorded there and use it without re-asking. If it isn't, use
`AskUserQuestion` with the default preselected by this rule:

| Situation | Default | Why |
|---|---|---|
| Any gRPC or GraphQL client (alone, or alongside REST) | **Microcks** | It mocks REST, gRPC and GraphQL from the same contracts in one container, consuming the OpenAPI spec, the `.proto` and the SDL directly with no descriptor build step. One tool covering every protocol is worth more than any per-protocol advantage. |
| REST clients only | **WireMock** | Far lighter and faster to start, and its stub DSL gives finer per-test control over responses, delays, and faults. Note when offering it that a `wiremock-grpc-extension` exists, so choosing WireMock is not a dead end if gRPC arrives later — it just costs a descriptor-set build step then. GraphQL is the weaker case: every operation is one `POST /graphql`, so stubs match on request body rather than path and the SDL is never read by the mock, leaving contract and mock free to drift. |

Present both options either way and let the user override — a team already running Microcks for contract testing
elsewhere should keep using it even for a REST-only service. Write the answer back to the manifest as
`stack.apiMock: microcks | wiremock | none` so a re-run, the code-generation skills, and any later reader all
agree on one choice.

Take each tag from the manifest if recorded there; otherwise look up the current stable release for each image and
**write the resolved tags back into the manifest**, so the compose file, the tests, and later OpenTofu engine
versions all agree on one number.

Check `docker info` succeeds. If Docker isn't running, still write every file — they're correct regardless — but
skip Step 6's verification and say clearly in the report that the setup is unverified.

### Existing files

Say whether this run is **creating the stack from scratch** or **updating an existing one** (list which exist of
`compose.yaml`/`docker-compose.yml`, the `*IT` base class(es), the Prometheus/Grafana provisioning files, and the
mock's import/mapping configuration). For each one that exists, warn and — unless `on-existing` says otherwise —
ask with `AskUserQuestion` (up to 4 per call, one question each), naming concrete drift (e.g. "your
`compose.yaml` has no `redis` service but the manifest now lists Redis", "it pins `mongo:7.0` but the manifest
records `mongo:8.0`"):

- **Update (Recommended)** — add missing services/healthchecks/properties and align image tags with the manifest;
  keep every service, volume, env var, and test helper this skill doesn't own. Your customizations survive, but a
  customized service definition this skill owns may be rewritten.
- **Keep as is** — leave it untouched; it may lack containers for newly added technologies or pin different tags
  than the manifest — Step 7 lists each mismatch.
- **Replace** — regenerate from this skill's templates; every customization in that file is lost (recoverable
  only from git history). Never offered for an `*IT` class that contains real test logic beyond the generated base.

## Step 1 — Write `compose.yaml`

Write `compose.yaml` at the repository root (Compose's current default filename; Spring Boot's docker-compose
support looks for `compose.yaml`/`compose.yml`/`docker-compose.yaml`/`docker-compose.yml` in that order).

Requirements for every service:

- **A pinned image tag.**
- **A `healthcheck`** with a real readiness probe, not a sleep. This is what makes the whole setup deterministic —
  `ComposeContainer` and Spring's compose support both wait for health, so a service with no healthcheck is
  considered ready the instant its container starts, and tests then fail intermittently against a database that
  hasn't finished initialising. Use each engine's own probe:
  - MongoDB: `mongosh --eval "db.adminCommand('ping')"`
  - PostgreSQL: `pg_isready -U <user> -d <db>`
  - MariaDB: `healthcheck.sh --connect --innodb_initialized` (or `mariadb-admin ping`)
  - Couchbase: `curl -sf http://localhost:8091/pools/default`
  - Elasticsearch: `curl -sf http://localhost:9200/_cluster/health?wait_for_status=yellow`
  - Neo4j: `cypher-shell -u neo4j -p <pw> "RETURN 1"`
  - Qdrant: a TCP/HTTP probe on 6333 (`/readyz`)
  - Redis: `redis-cli ping`
  - Kafka: `kafka-broker-api-versions.sh --bootstrap-server localhost:9092`
  - Schema Registry: `curl -sf http://localhost:8081/subjects`
- **`depends_on` with `condition: service_healthy`** where there's a real ordering need — the schema registry must
  not start before Kafka is healthy, or it exits and takes the test run with it.
- **Development-only credentials**, obviously fake and clearly labelled as such in a comment. `compose.yaml` is
  committed, so it must contain nothing that is also true anywhere real. Never reuse a value from a deployed
  environment even "temporarily".
- **Named volumes** for anything whose state is worth keeping across local restarts (databases), and **no**
  volume for the throwaway ones. Testcontainers tears everything down regardless.
- **Resource-sensible defaults** — Elasticsearch in particular needs `discovery.type=single-node`,
  `xpack.security.enabled=false` for local use, and a bounded `ES_JAVA_OPTS` heap (e.g. `-Xms512m -Xmx512m`), or it
  will happily consume most of a developer's laptop.
- Kafka on the `apache/kafka` KRaft image needs its controller/listener environment set (`KAFKA_NODE_ID`,
  `KAFKA_PROCESS_ROLES`, `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`, `KAFKA_CONTROLLER_QUORUM_VOTERS`,
  `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`). Consult the image's
  current documentation for the exact variable set for the pinned tag rather than reproducing a remembered
  configuration — this is the single most error-prone service in the file.
- **Advertised listeners must work from both sides.** Kafka is reachable from the host on a mapped port and from
  other containers on the compose network; advertise both listeners, or the schema registry can reach the broker
  and the tests can't (or vice versa).

If `metrics: true`, also write:

- `observability/prometheus/prometheus.yml` — scraping the service's actuator endpoint
  (`/actuator/prometheus`) on the management port, with a short `scrape_interval` (5–15s) suitable for local
  development. Note that from inside the Prometheus container the app on the host is `host.docker.internal`
  (macOS/Windows) — add the `extra_hosts: ["host.docker.internal:host-gateway"]` entry that makes this work on
  Linux too.
- `observability/grafana/provisioning/datasources/prometheus.yaml` — provisioning the Prometheus datasource, so
  Grafana comes up already connected instead of needing manual setup.
- Optionally a starter dashboard under `observability/grafana/provisioning/dashboards/` using the service's own
  metrics from `infrastructure/metrics`.

Add a `profiles:` key to the `prometheus`/`grafana` services (e.g. `profiles: ["observability"]`) so integration
tests don't pay to start them. Then `docker compose --profile observability up` gives a developer the full local
stack, while `ComposeContainer` starts only the data services. Say in the report which services are behind the
profile.

The mock service, unlike Prometheus and Grafana, gets **no** profile — it must start for integration tests, and a
developer running the service locally needs it just as much, since without it every downstream call fails.

### The API mock service

Whichever tool Step 0 selected, one invariant holds: **the mock is driven by the contracts already in
`apis/rest-client/<name>/` and `apis/grpc-client/<name>/`** — the same files
`iru-setup-java-springboot-apis` generates each client from. Never hand-write a parallel set of fixtures
describing the downstream API. Two descriptions of the same contract diverge, and the day they do, the client
compiles against one and passes its tests against the other.

**Microcks** — mount the contracts read-only and let it import them:

```yaml
microcks:
  image: quay.io/microcks/microcks-uber:<pinned-tag>   # all-in-one; no separate MongoDB/Keycloak needed
  environment:
    KEYCLOAK_ENABLED: "false"                          # local/test only — no auth in front of the mock
  ports:
    - "8585:8080"     # REST mocks + the web console
    - "9090:9090"     # gRPC mocks
  volumes:
    - ./apis/rest-client:/apis/rest-client:ro
    - ./apis/grpc-client:/apis/grpc-client:ro
    - ./apis/graphql-client:/apis/graphql-client:ro    # omit any directory the stack doesn't have
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/api/health"]
```

Check the pinned image's own documentation for its current artifact-import mechanism rather than reproducing a
remembered flag — the uber image has carried more than one over time. The two mechanisms that work today are an
import declared in the container's configuration at startup, and a small init step that `POST`s each contract to
`/api/artifact/upload`. If you use the init step, make it a `depends_on: condition: service_healthy` one-shot
compose service so importing can't race the mock's startup, and make it idempotent — re-uploading the same
artifact must not create a second copy.

Microcks addresses a mock by service name and version taken from the contract itself: REST mocks live under
`/rest/<Service+Name>/<version>/<path>`, gRPC mocks answer on port 9090 for the fully-qualified service in the
`.proto`, and GraphQL mocks are served over the REST-style path for the schema's service name. Derive those URLs
from the contracts, don't invent them, and say in the report exactly what each client's base URL becomes.

A GraphQL SDL, like a `.proto`, carries no examples of its own — Microcks needs a companion examples artifact next
to the schema in `apis/graphql-client/<name>/` naming each operation and its response. Check the pinned version's
supported formats rather than assuming, and report any GraphQL client left with no examples: it mocks nothing, and
the failure shows up as an integration test reading an empty `data` object far from its cause.

**WireMock** — mount its root and the contracts:

```yaml
wiremock:
  image: wiremock/wiremock:<pinned-tag>
  command: ["--global-response-templating", "--disable-banner", "--verbose"]
  ports:
    - "8585:8080"
  volumes:
    - ./wiremock:/home/wiremock:ro                     # mappings/ and __files/
    - ./apis/rest-client:/apis/rest-client:ro          # for the validation extension
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/__admin/health"]
```

WireMock doesn't read OpenAPI natively, so wire the contract in from both ends:

- **Generate the stub mappings from each spec's `examples`** into `wiremock/mappings/<name>/`, one file per
  operation, rather than writing them from scratch. Every generated mapping names the contract and operation it
  came from and states that it is generated — otherwise the next person edits the mapping instead of the spec.
  JSON has no comments and WireMock refuses to start on an unknown top-level field (a `"comment"` key fails with
  `Unrecognized field "comment"`), so put that note in the mapping's `"metadata"` object, e.g.
  `"metadata": { "generated": "from apis/rest-client/<name>/<name>-api.yaml, operation getX, example 'found' — edit the spec" }`.
- **Add the OpenAPI validation extension** so a request or response that violates the contract fails the test
  instead of quietly passing. This is what buys back most of the contract-fidelity that Microcks gets for free,
  and it is the difference between a mock that proves something and a mock that agrees with whatever the client
  sends. Confirm the extension's current Maven coordinates and the way the image loads extensions against its
  documentation before writing it in.
- **For gRPC** (only if the user chose WireMock despite having gRPC clients, or adds gRPC later): the
  `wiremock-grpc-extension` needs a **descriptor set**, not the raw `.proto` — generate it with
  `protoc --descriptor_set_out=... --include_imports` from `apis/grpc-client/<name>/` into `wiremock/grpc/`, as a
  build step so it regenerates when the contract changes. Say plainly in the report that this step exists and
  that Microcks wouldn't have needed it.

For both tools: pin the tag, add a real healthcheck (the mock is a `depends_on: condition: service_healthy`
target for nothing, but `ComposeContainer` waits on it), and keep every response body in the contract or the
generated mappings — **never put a real captured production payload in either**, since those routinely contain
personal data and land in a committed file.

## Step 2 — The integration-test base class

Put it in a module every `*IT` can reach. Two workable placements — pick one and say which:

- **Preferred**: a `src/test/java` class in `boot` plus a duplicate-free approach for the adapter modules by
  making the base class a `test-jar` published by a small shared test module. Cleanest, but adds a module.
- **Simpler and usually right for a new service**: put the base class in `boot`'s test sources for the
  full-context tests, and in each adapter module put a narrower base class that starts only the one container that
  module needs. Adapter integration tests then don't start Kafka to test a MongoDB mapper — meaningfully faster,
  and the failure messages are far clearer.

Write the shared base class to launch the compose file:

```java
import org.testcontainers.containers.ComposeContainer;      // Testcontainers 2.x: core classes keep this package
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public abstract class AbstractComposeIT {

    // Compose runs inside this pinned image, so no local `docker compose` CLI is needed (Testcontainers 2.x removed
    // withLocalCompose(false); this constructor is its replacement — new ComposeContainer(file) runs the local CLI).
    private static final DockerImageName COMPOSE_IMAGE = DockerImageName.parse("docker:<pinned-tag>-cli");

    // The same compose.yaml a developer runs locally: one definition, no drift.
    @SuppressWarnings("resource")
    protected static final ComposeContainer ENVIRONMENT =
            new ComposeContainer(COMPOSE_IMAGE, new File("../compose.yaml"))
                    .withExposedService("mongodb", 27017, Wait.forHealthcheck())
                    // ... one withExposedService per required service, each with a real wait strategy ...
                    .withStartupTimeout(Duration.ofMinutes(3));

    static { ENVIRONMENT.start(); }

    @DynamicPropertySource
    static void springProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> "mongodb://%s:%d/test".formatted(
                ENVIRONMENT.getServiceHost("mongodb", 27017),
                ENVIRONMENT.getServicePort("mongodb", 27017)));
        // ... one binding per service ...
    }
}
```

Points that matter:

- **The compose file path is relative to the module's working directory**, which for a Maven module is that
  module's own directory — hence `../compose.yaml` from a top-level module and `../../../compose.yaml` from
  `infrastructure/database/<engine>`. Prefer resolving it from a system property or by walking up to the directory
  containing `compose.yaml`, so the same base class works from any module depth. Getting this wrong is the most
  common failure of this setup.
- **`ComposeContainer` assigns random host ports** — never hardcode one. Every property must come from
  `getServicePort`.
- **Wait strategies**: prefer `Wait.forHealthcheck()` where the service defines one in Step 1 (it's the whole
  reason to define them), falling back to `Wait.forLogMessage`/`forHttp` where a healthcheck isn't practical.
  `Wait.forListeningPort()` alone is the weakest option — a listening port doesn't mean an initialised database.
- **Start the environment once per JVM**, as a `static` singleton (as above), not per test class. Restarting a
  Kafka + schema-registry + database stack per class makes the suite unusably slow. Note in the report that this
  means tests must not assume an empty database: each `*IT` cleans up what it creates, or uses unique keys.
- **Testcontainers 2.x (managed by Spring Boot 4)** renamed module artifacts to `testcontainers-<module>`
  (`testcontainers-postgresql`, `testcontainers-junit-jupiter`, ...) and moved module classes to
  `org.testcontainers.<module>` with no type parameter (`org.testcontainers.postgresql.PostgreSQLContainer`,
  `org.testcontainers.mongodb.MongoDBContainer`); core classes (`ComposeContainer`, `GenericContainer`, `Wait`) stay
  in `org.testcontainers.containers`. Pin the `docker:<tag>-cli` compose image like every other tag and record it in
  the manifest. Only on Testcontainers 1.x use the old names and `withLocalCompose(false)`.
- **Reuse across runs**: mention `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` as a big
  local-iteration win, and that it must stay off in CI (where a fresh environment per run is the point).

Where a single container is enough, prefer Spring Boot's `@ServiceConnection` on a `@Container` field over
`@DynamicPropertySource` — it derives the connection properties automatically and is far less code. Use it for the
narrow per-adapter base classes, and keep `@DynamicPropertySource` for the compose-based one, where Spring can't
infer the mapping.

### Pointing every client at the mock

This only works if each `infrastructure/clients/<name>` adapter reads its base URL from configuration — a
`@ConfigurationProperties` binding such as `clients.<name>.base-url`, never a hardcoded host. Verify that before
writing the bindings; if an adapter hardcodes its URL, fix the adapter, because no test wiring can redirect it.

In the compose-based base class, add one `@DynamicPropertySource` binding per client, resolved from the mock's
randomly-assigned host port:

```java
registry.add("clients.payments.base-url", () -> "http://%s:%d/rest/Payments+API/1.0.0".formatted(
        ENVIRONMENT.getServiceHost("microcks", 8080),
        ENVIRONMENT.getServicePort("microcks", 8080)));
// gRPC clients point at the Microcks gRPC port (9090) with the target's fully-qualified service name.
```

For the **narrow per-adapter base classes** — the ones that start only what that module needs — a client adapter's
`*IT` needs the mock and nothing else: no database, no Kafka. That is the case where the tool's own Testcontainers
module beats the compose file: `MicrocksContainer` with `withMainArtifacts(...)` pointed at the contract, or
`WireMockContainer` with the generated mappings. It starts in a couple of seconds instead of bringing up the whole
stack, and the contract is still the single source both paths read. Use the compose service for full-context
`boot` tests and the dedicated module for per-adapter tests, and state which is which in the report so nobody
concludes the two disagree.

Add the matching test-scope dependency to each `infrastructure/clients/<name>/pom.xml`
(`io.github.microcks:microcks-testcontainers` or `org.wiremock.integrations.testcontainers:wiremock-testcontainers-module`),
with the version in the root pom's `<dependencyManagement>` like everything else — never inline in the module. On
**Gradle**, the equivalent is a version + library alias in `gradle/libs.versions.toml` (e.g.
`microcks-testcontainers = { module = "io.github.microcks:microcks-testcontainers", version.ref = "microcks-testcontainers" }`,
version resolved from the latest stable release, not invented) and `testImplementation(libs.microcks.testcontainers)`
in `infrastructure/clients/<name>/build.gradle.kts` — skip either edit if `iru-setup-java-springboot-pom` already
placed it, and never put a literal version in a module build file.

### Kotlin (`project.language: kotlin`)

The same design — one compose-based base class in `boot`, narrow `@ServiceConnection` bases per adapter module —
written in Kotlin under `src/test/kotlin`, following the project's Kotlin test rules (JUnit 5, `kotlin.test`
assertions, backtick names, KDoc, named constants, no `!!`, constructor `@Autowired` injection in test classes).
Testcontainers 2.x (managed by Spring Boot 4) renamed artifacts to `testcontainers-<module>` and moved module classes
to `org.testcontainers.<module>` (e.g. `org.testcontainers.postgresql.PostgreSQLContainer`, no type parameter);
core classes (`ComposeContainer`, `Wait`) stay in `org.testcontainers.containers`, and the JUnit extension is
`org.testcontainers.junit.jupiter` from `testcontainers-junit-jupiter`. On Testcontainers 1.x use the old names.

Testcontainers 2.x also dropped `withLocalCompose(...)`: `ComposeContainer(File)` runs the local `docker compose`
CLI, while `ComposeContainer(DockerImageName, File)` runs compose inside that pinned image (the 1.x
`withLocalCompose(false)` behaviour) — so no compose CLI is needed on the machine or CI runner. Pin the image tag
like every other one and record it in the manifest.

The compose-based base class (in `boot`):

```kotlin
@SpringBootTest
// detekt flags a class whose members all live in its companion; this one exists to be extended by the *IT classes.
@Suppress("UtilityClassWithPublicConstructor")
abstract class AbstractComposeIT {
    /** The shared compose environment and its property bindings. */
    companion object {
        private const val POSTGRES = "postgres"
        private const val POSTGRES_PORT = 5432
        private const val STARTUP_MINUTES = 3L
        private const val COMPOSE_FILE = "compose.yaml"

        /** Compose runs inside this pinned image, so no local `docker compose` CLI is needed. */
        private val COMPOSE_IMAGE = DockerImageName.parse("docker:<pinned-tag>-cli")

        /** Started once per JVM; ports are random, so every property comes from `getServicePort`. */
        @JvmStatic
        val environment: ComposeContainer =
            ComposeContainer(COMPOSE_IMAGE, findComposeFile())
                .withExposedService(POSTGRES, POSTGRES_PORT, Wait.forHealthcheck())
                // ... one withExposedService per required service ...
                .withStartupTimeout(Duration.ofMinutes(STARTUP_MINUTES))
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun springProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                val host = environment.getServiceHost(POSTGRES, POSTGRES_PORT)
                val port = environment.getServicePort(POSTGRES, POSTGRES_PORT)
                "jdbc:postgresql://$host:$port/<db>"
            }
            // ... credentials from compose.yaml's development-only values, one binding per service/client ...
        }

        /** Walks up from the module's working directory, so this works at any module depth. */
        private fun findComposeFile(): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, COMPOSE_FILE) }
                .firstOrNull { it.isFile }
                ?: error("$COMPOSE_FILE not found above ${File("").absolutePath}")
    }
}
```

`@JvmStatic` on the companion members is what makes Spring see a static `@DynamicPropertySource` method and what
starts the environment once per JVM (the companion initializer runs once). Walking up to `compose.yaml` removes the
module-depth problem entirely — it works from `boot` (Maven or Gradle both run tests in the module directory).

A narrow per-adapter base (in `infrastructure/database/<engine>`), plus a test-only
`@SpringBootApplication class <Engine>TestApplication` in the module's base test package so `@SpringBootTest` finds a
configuration (an adapter module has no application class of its own):

```kotlin
@SpringBootTest
@ActiveProfiles("<engine>")          // load the module's own application-<engine>.yml (ddl-auto: validate etc.)
@Testcontainers
@Suppress("UtilityClassWithPublicConstructor") // extended by the *IT classes; detekt would otherwise flag it
abstract class AbstractPostgresqlIT {
    /** The shared container, static through the companion so every subclass reuses it. */
    companion object {
        /** Same image and tag as compose.yaml / springboot-stack.yml. */
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:<tag>").asCompatibleSubstituteFor("postgres"))
    }
}
```

`asCompatibleSubstituteFor("postgres")` is required whenever the image isn't the module's default one (the
`pgvector/pgvector` image here); `@ServiceConnection` picks the connection type from the container class. The
`*IT` classes `iru-setup-java-springboot-modules` wrote extend these bases and take their beans through the
constructor (`class JpaOrderRepositoryAdapterIT(@Autowired private val adapter: JpaOrderRepositoryAdapter) :
AbstractPostgresqlIT()`); the full-context smoke test `<Name>ApplicationIT` extends `AbstractComposeIT` and drives
one use case end to end through the real adapter.

## Step 3 — Local development via Spring Boot's compose support

In `boot`, ensure `spring-boot-docker-compose` is a `runtime`/`optional` dependency (on Gradle,
`developmentOnly(libs.spring.boot.docker.compose)`; the pom skill places it either way) and configure the `local`
profile:

```yaml
spring:
  docker:
    compose:
      enabled: true
      file: ../compose.yaml
      lifecycle-management: start_and_stop
      skip:
        in-tests: true          # the Testcontainers harness owns the stack during tests
```

`skip.in-tests: true` is essential — without it, Spring's compose support and `ComposeContainer` both try to
manage the same stack and fight over ports.

Confirm the `local` profile's connection properties point at the compose services' **fixed host ports** (the ones
mapped in `compose.yaml`), which is the one context where fixed ports are correct — a developer needs a stable
`localhost:27017` to point a GUI client at.

The same applies to `clients.<name>.base-url`: under `local` it points at the mock's fixed port
(`http://localhost:8585/...`), so running the service locally exercises the downstream calls without anyone
holding a credential for the real downstream service. Make sure no other profile inherits that value — a deployed
profile that silently falls back to the mock URL is a failure mode that looks like success right up until
production traffic goes to a container that isn't there. Set the deployed profiles' base URLs explicitly, with no
default.

## Step 4 — Migration and seed data in tests

Integration tests must exercise the real schema, not an ad-hoc one:

- Let the module's own migration tooling (Flamingock / Liquibase / Flyway) run against the container, so a broken
  migration fails a test rather than a deploy. Verify this is enabled in the test profile — several teams disable
  migrations in tests and then discover the migration is wrong in production.
- Add a `src/test/resources/application-test.yml` per module for test-only overrides, and keep seed data as
  fixtures the test creates, not as extra migration files — a seed migration will eventually run somewhere real.
- If Kafka is in play, note that topics are auto-created by default in the test broker; for tests that depend on
  partition count, create them explicitly via an `AdminClient` in the base class.
- **Mock responses are fixtures too, and they belong in the contract.** A test that needs a specific downstream
  response adds an `example` to the OpenAPI operation (Microcks turns each named example into a mock response
  matched on the request) or a mapping under `wiremock/mappings/` — not an inline stub built in the test class.
  The exception is the failure cases: a per-test override for a 500, a timeout, or a malformed body is exactly
  what the tool's Testcontainers module and admin API are for, and those paths deserve tests as much as the happy
  one. Say in the report which client error paths have coverage and which don't, because an adapter's exception
  translation is usually the least-tested code in the reactor.

## Step 5 — Failsafe and coverage wiring

`iru-setup-java-springboot-pom` already binds Failsafe to `verify` with the `**/*IT.java` include and JaCoCo's
`prepare-agent-integration`/`report-integration` executions. Verify, rather than assume:

- `*IT` classes are picked up by Failsafe and **not** by Surefire (so `mvn test` stays fast and Docker-free).
- Integration coverage lands in `jacoco-it.exec` and is picked up by the `coverage` module's `report-aggregate`.
- `mvn test` succeeds with Docker stopped. If it doesn't, an integration test is misnamed as `*Test` — fix the
  name. A unit-test phase that needs Docker breaks every developer's inner loop.

**Gradle** — the `integrationTest` task is defined once, by the `iru.integration-test` convention plugin in
`build-logic/` that `iru-setup-java-springboot-pom` writes: a `Test` task over the `test` source set including only
`**/*IT*`, while `test` excludes `**/*IT*`, with `check` depending on it. **This skill never redefines or
reconfigures it** — no `tasks.register("integrationTest")`, no extra source set. Verify instead that every module
holding an `*IT` applies `id("iru.integration-test")` (report any that don't — the pom skill owns the fix), that
`./gradlew test` succeeds with Docker stopped (same misnaming rule as above), and that the `coverage` project's
`testCodeCoverageReport` includes the `integrationTest` execution data. Test dependencies (`spring-boot-testcontainers`,
`testcontainers-junit-jupiter`, `testcontainers-<module>`) come from the version catalog, never with a literal
version in a module's `build.gradle.kts`.

## Step 6 — Verify

Delegate each to `iru-gate-runner` and ask for a compact result:

```bash
docker compose config          # compose.yaml is valid and every variable resolves
mvn -q test                    # unit tests pass with no Docker involvement
mvn -q verify                  # integration tests pass against the real containers
docker compose --profile observability up -d && docker compose down -v   # the local stack comes up and tears down
```

On Gradle, replace the two Maven lines with `./gradlew test` (with Docker stopped, if practical) and
`./gradlew integrationTest`. If Gradle itself runs inside a container (a CI container job, or
`docker run ... gradle:<tag>` with `/var/run/docker.sock` mounted), mount the project at the **same absolute path**
as on the host (compose bind-mounts are resolved by the host daemon) and set
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` (Docker Desktop) so the tests reach the mapped ports.

If the stack has a mock service, also confirm it is actually serving the contracts rather than merely running —
a mock container that started with zero imported artifacts passes a healthcheck and fails every test:

```bash
curl -sf localhost:8585/api/services            # Microcks: every client contract appears, with its version
curl -sf localhost:8585/__admin/mappings        # WireMock: the generated mappings are loaded, count > 0
```

Then check that at least one client `*IT` really reached the mock (Microcks records invocations; WireMock has
`/__admin/requests`). An adapter test that passes without a single recorded request is testing nothing — usually
a base URL that never got overridden.

Fix what fails. Expect to iterate on: the compose file path in the base class, Kafka's advertised listeners,
Elasticsearch's memory settings, and the mock's artifact import (the most common mock failure is a contract with
no `examples`, which imports cleanly and then has no response to serve). If the first `mvn verify` is very slow,
check that the environment is being started once rather than per class.

Confirm the observability stack actually works before claiming it does: with the app running under the `local`
profile, Prometheus at `localhost:9090` should show the service's target as `UP`, and Grafana at `localhost:3000`
should have the Prometheus datasource already provisioned.

## Step 7 — Report

Summarize:

- Whether the stack was created from scratch or updated, and per pre-existing file the action taken (**created /
  updated / replaced / kept as is**), with each mismatch left in a kept file.
- Where the integration tests run, per the manifest's `ci:` block: with `ci.tests: full`, CI runs them through
  Failsafe against this same compose stack; with **`ci.tests: unit`** (or `ci.enabled: false`), **the `*IT` tests
  this skill generates run locally only** — `iru-setup-java-springboot-github-workflows` runs Maven with
  `-DskipITs` (on Gradle, `./gradlew test` without `integrationTest`), so no Docker/Testcontainers stack starts in CI and they're verified only when someone runs
  `mvn verify` locally. Say this plainly so nobody assumes CI exercised them; this is also why Step 5's
  `*IT`-vs-`*Test` naming discipline matters even more there (a Docker-dependent test misnamed `*Test` would run,
  and fail, in CI).
- Every compose service written, with its pinned image tag, its healthcheck, and whether it sits behind the
  `observability` profile. Note that Caffeine intentionally has no container, if it's in the stack.
- The language and build tool (`java`/`kotlin`, `maven`/`gradle`); on Gradle, which modules apply
  `iru.integration-test` and any that hold an `*IT` without it.
- Where the integration-test base class(es) live and which containers each starts.
- Which services use `@ServiceConnection` versus `@DynamicPropertySource`, and how the compose file path is
  resolved.
- **The mocking tool chosen, why it was the default for this stack, and the contract each mock was built from** —
  one line per client naming its spec under `apis/rest-client/`/`apis/grpc-client/`, the base URL it resolves to
  in tests and under the `local` profile, and whether its `*IT` uses the compose service or the narrow
  Testcontainers module. If the service has no clients, say that no mock was added and why.
- Whether migrations run during integration tests.
- The result of each command in Step 6 — and if Docker wasn't available, say plainly that nothing was verified.
- The image tags written back into `springboot-stack.yml`.

Warn explicitly:

- **`compose.yaml` contains development-only credentials and is committed.** Nothing in it may match a real
  environment's values, now or later.
- **Image tags are pinned deliberately.** Bumping them is a decision that belongs in a reviewed commit, not a
  side effect of a rebuild — but they do need periodic bumping, since a pinned tag also pins its CVEs.
- The container stack needs real memory (Elasticsearch, Couchbase, and Kafka each want hundreds of megabytes);
  give the figure a developer's Docker VM will actually need, and — when `ci.tests: full` — note that CI runners
  have a hard ceiling: if the full stack won't fit on a standard GitHub runner, say so now rather than discovering
  it in the first CI run.
- Integration tests share one environment per JVM, so each test must clean up after itself or use unique keys.
- `testcontainers.reuse.enable` is a local-only optimisation and must never be set in CI.
- **A mock proves conformance to the contract, not to the downstream service.** Every test can pass against a
  perfect mock of a spec the other team stopped honouring months ago. Two things keep that from biting: pull each
  client contract from the downstream service's own published artifact rather than editing the local copy by
  hand, and run a real contract test against the actual service periodically — nightly or pre-release, never in
  the PR loop. Microcks can drive that verification directly from the same contract; with WireMock it's a
  separate job. State which one this repository now has, and if the answer is neither, say so plainly rather than
  leaving the impression the mock covers it.
- **The mock is reachable with no authentication and answers anything the contract describes.** Keep it bound to
  the compose network and localhost, never expose it from a shared host, and never point a deployed profile at
  it.
