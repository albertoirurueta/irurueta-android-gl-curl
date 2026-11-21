### 4a — Databases (multi-select, may be none)

- **MongoDB** — good when data fits in single independent documents, the structure changes freely over time, and
  many indexes are needed to search that data. Not for workloads that need real transactional guarantees.
  - ids: `data-mongodb` (blocking) / `data-mongodb-reactive` (reactive).
  - Index and schema-change management is **mandatory**; ask with `AskUserQuestion`:
    - **Flamingock** (recommended) — purpose-built for MongoDB change units, integrates cleanly with Spring Data,
      and is the actively-developed successor to Mongock (which is sunsetting, end-of-life 2026-12-31). Add
      `io.flamingock:flamingock-bom` (import scope in dependency management), `io.flamingock:flamingock-community`,
      `io.flamingock:flamingock-springboot-integration`, and the `io.flamingock:flamingock-processor` annotation
      processor (version in the root pom).
    - **Liquibase for MongoDB** — only worth it when Liquibase is already used for another database in the same
      service and one migration tool for everything is worth more than Flamingock's better MongoDB ergonomics. Add
      `org.liquibase.ext:liquibase-mongodb`.
  - Testcontainers image: `mongo:<pinned-tag>`.
- **Couchbase** — good when data fits in single independent documents, the structure changes freely, and no
  indexes beyond lookup-by-id are needed. Not for workloads needing transactional guarantees. In that specific
  shape it usually outperforms MongoDB.
  - ids: `data-couchbase` / `data-couchbase-reactive`.
  - Index management is mandatory: **Liquibase for Couchbase** (`org.liquibase.ext:liquibase-couchbase`).
  - Testcontainers image: `couchbase/server:<pinned-tag>`.
- **Relational / SQL** — good when data must be structured in a very specific way, aggregation queries run over
  it, or transactional guarantees are mandatory. Generally slower than MongoDB or Couchbase for
  document-shaped access.
  - Engine (`AskUserQuestion`, exactly one): **PostgreSQL** (recommended) or **MariaDB**. If PostgreSQL is
    chosen, note that the `pgvector` extension gives it vector storage, so it can also back Spring AI embeddings
    (see 4h) — and pin the Testcontainers image to `pgvector/pgvector:<pg-tag>` rather than plain `postgres` so
    that stays available.
  - ids: blocking → `data-jpa` + (`postgresql` | `mariadb`); reactive → `data-r2dbc` + (`postgresql` |
    `mariadb`) — Initializr adds the R2DBC driver alongside the JDBC one automatically.
  - Data-model versioning and index creation is mandatory; ask with `AskUserQuestion`:
    - **Liquibase** (recommended default) — id `liquibase`.
    - **Flyway** — id `flyway`.
  - Testcontainers image: `pgvector/pgvector:<tag>` (or `postgres:<tag>` if vectors are definitely not needed) /
    `mariadb:<tag>`.
- **Elasticsearch** — good for full-text search, faceted search (e-commerce), and log aggregation. Almost always
  used *alongside* another database in a CQRS arrangement, not as the system of record — say so explicitly, and
  if it's the only database selected, ask the user to confirm that's intentional.
  - ids: `data-elasticsearch`.
  - Testcontainers image: `docker.elastic.co/elasticsearch/elasticsearch:<tag>`.
- **Neo4j** — good when the relationships between entities matter as much as or more than the entities: fraud
  detection, recommendation engines, social graphs, knowledge graphs, dependency graphs. Typically used alongside
  another database in a CQRS arrangement.
  - ids: `data-neo4j`.
  - Migration management is mandatory: **Liquibase for Neo4j** (`org.liquibase.ext:liquibase-neo4j`).
  - Testcontainers image: `neo4j:<tag>`.
- **Qdrant** — the preferred choice over PostgreSQL + `pgvector` when a large volume of vectors is stored: a
  dedicated vector engine outperforms sharing one PostgreSQL instance between application data and embeddings.
  - ids: `spring-ai-vectordb-qdrant` (Qdrant reaches the service through Spring AI's vector store abstraction).
  - Testcontainers image: `qdrant/qdrant:<tag>`.

### 4b — Caches (multi-select, may be none)

- **Caffeine** — best for a local, per-node cache. Each replica keeps its own copy, so it's ideal for small or
  static, infrequently-changing data. Fastest option, no network hop.
  - ids: `cache` (Spring's cache abstraction). Caffeine itself is not an Initializr dependency — add
    `com.github.ben-manes.caffeine:caffeine` explicitly, with its version in the root pom.
- **Redis** — best for a cache shared across all replicas: large cached datasets, or data that changes often
  enough that every node must see the change at once. Slightly slower than a local cache because of the network
  hop.
  - ids: `data-redis` / `data-redis-reactive`, plus `cache` if `@Cacheable` is wanted on top.
  - Testcontainers image: `redis:<tag>`.

If both are selected, note that the conventional arrangement is a two-level cache (Caffeine in front of Redis)
and that it needs deliberate invalidation design — flag it for the architecture documentation page.

### 4c — Spring Security (yes/no)

Needed when OAuth login flows must be implemented, or endpoints must be properly authorized rather than open.

- ids: `security`, plus `oauth2-resource-server` when the service validates incoming JWTs (the usual case for a
  microservice behind a gateway) and/or `oauth2-client` when it initiates login flows itself. Ask which of the
  two applies; both is valid.

### 4d — Spring Cloud Stream / Kafka (yes/no)

Needed for Kafka messaging — publishing or consuming public or private events. This is the standard tool for
eventual consistency in non-transactional architectures (microservices) and for saga orchestration over public
events.

- ids: `cloud-stream`, `kafka`. Add `kafka-streams` only if the user says they need stateful stream processing.
- AVRO is the message format. `avro-maven-plugin` generates Java classes from the schemas in `apis/messaging/` —
  configured by `iru-setup-java-springboot-apis`.
- The same directory also holds an **AsyncAPI specification** describing the topics, their direction, and which
  message each carries — the messaging equivalent of the OpenAPI spec. It is documentation only (no Java is
  generated from it), rendered to HTML on every build by `@asyncapi/html-template` and published to GitHub Pages
  alongside the REST and gRPC pages.
- Creates the `infrastructure/producers/` module (outbound) and/or the `api/consumers/` module (inbound) — ask
  which directions this service needs; at least one must be true.
- Testcontainers needs both a **Kafka broker** image and a **Confluent Schema Registry** image, since AVRO
  serialization resolves schemas through the registry at runtime.

### 4e — REST server (yes/no)

Needed to serve requests over a REST API.

- ids: `web` (blocking) / `webflux` (reactive) / both. Always add `validation`.
- Contract-first: OpenAPI specs live in `apis/rest-server/`, and `openapi-generator-maven-plugin` generates the
  controller interfaces and DTOs into `api/rest-server/`, plus HTML documentation.

### 4f — gRPC server (yes/no)

Needed to serve requests over gRPC.

- ids: `spring-grpc-server` (Spring Boot 4 ships first-party gRPC support; in older lines this was the separate
  `spring-grpc`/`net.devh` starter — trust Step 1's catalogue). Reactive and blocking service implementations
  use the same starter; the difference is in the generated stub flavour.
- Contract-first: protobuf definitions live in `apis/grpc-server/`, and `protobuf-maven-plugin` generates the
  service base classes and messages into `api/grpc-server/`, plus HTML documentation via `protoc-gen-doc`.

### 4g — GraphQL server (yes/no)

Needed to serve requests over a GraphQL API — one endpoint whose clients choose the fields they want, instead of
one endpoint per resource shape.

- ids: `graphql`, **plus a transport starter**: `web` (blocking) or `webflux` (reactive), matching
  `stack.concurrency`. This pairing is not optional and is the easiest thing to get wrong here:
  `spring-boot-starter-graphql` brings the GraphQL engine and `spring-graphql`, but **no HTTP transport at all** —
  Initializr's `graphql` id alone resolves to `spring-boot-starter-graphql` and `spring-boot-starter-graphql-test`
  and nothing else. Without `web` or `webflux` the application starts and serves no `/graphql` endpoint, which
  looks like a routing bug rather than a missing dependency. If REST (4e) is also enabled, the transport starter is
  already there and isn't added twice.
- Also add `validation`, as for REST — the generated input types carry Bean Validation annotations.
- Creates the `api/graphql-server/` module.
- Contract-first: SDL schema files live in `apis/graphql-server/`, and `graphql-codegen-maven-plugin` generates the
  resolver interfaces and model types into `api/graphql-server/`. As with REST, the generator owns the interfaces
  and the hand-written `@Controller` implements them.
- **The SDL is needed twice**: once at build time by the generator, and once at *runtime* by Spring for GraphQL,
  which loads the schema from `classpath:graphql/**/` (`.graphqls`/`.gqls`) to wire and validate the resolvers. The
  contract lives outside the module, so the build copies it onto the classpath —
  `iru-setup-java-springboot-apis` sets that up. A service whose generated code compiles but whose SDL never
  reached the classpath fails at startup complaining the schema is empty.

### 4h — Clients (multi-select, may be none)

- **REST clients** — needed to call other services' REST APIs. Ask for the **name of each** client (one per
  downstream service, e.g. `payments`, `inventory`); each becomes an
  `infrastructure/clients/<name>/` module and an `apis/rest-client/<name>/` spec directory, with
  `openapi-generator-maven-plugin` generating the client and DTOs.
  - ids: `spring-restclient` (blocking) / `spring-webclient` (reactive).
- **gRPC clients** — needed to call other services' gRPC APIs. Ask for the **name of each**; each becomes an
  `infrastructure/clients/<name>/` module and an `apis/grpc-client/<name>/` spec directory, with
  `protobuf-maven-plugin` generating the stubs.
  - ids: `spring-grpc-client`.
- **GraphQL clients** — needed to call other services' GraphQL APIs. Ask for the **name of each**; each becomes an
  `infrastructure/clients/<name>/` module and an `apis/graphql-client/<name>/` SDL directory, with
  `graphql-codegen-maven-plugin` generating the request/response and projection classes.
  - ids: `graphql` (the same starter as 4g — it carries `HttpGraphQlClient`/`HttpSyncGraphQlClient`, so a service
    that only *consumes* GraphQL still needs it), plus the transport starter for `stack.concurrency` if 4e or 4g
    hasn't already added one.
  - Unlike the REST and gRPC client generators, this one needs a **runtime dependency** alongside the generated
    code — `io.github.kobylynskyi:graphql-java-codegen`, matching the plugin version, which supplies the
    `GraphQLRequest`/`GraphQLResponseProjection` base classes the generated classes extend. Without it the module
    generates fine and then fails to compile.

A single downstream service reached over more than one protocol keeps **one** `infrastructure/clients/<name>/`
module with a generator execution per protocol — the module is named after the service, not the transport.

**If — and only if — at least one client of any kind was named**, ask which API mocking tool the docker compose
stack should run, using `AskUserQuestion`. Integration tests must never call a real downstream service: that
couples this repository's CI to someone else's uptime and makes error-path coverage impossible. Both tools are
driven by the contracts in `apis/rest-client/<name>/`, `apis/grpc-client/<name>/` and
`apis/graphql-client/<name>/`, so neither introduces a second description of the downstream API. Preselect the
default by what was chosen above:

- **Microcks** — the default whenever **any gRPC or GraphQL client** exists, with or without REST clients. It mocks
  REST, gRPC and GraphQL from the same contracts in one container, consuming the OpenAPI spec, the `.proto` and the
  SDL directly, and can later verify the real downstream service against those same contracts.
- **WireMock** — the default when the service has **REST clients only**. Lighter, faster to start, with a
  finer-grained stub DSL for delays and faults. Mention when offering it that a `wiremock-grpc-extension` exists,
  so it isn't a dead end if gRPC arrives later — it just adds a descriptor-set build step at that point. For
  GraphQL it is a weaker fit: every operation is a `POST /graphql`, so stubs have to match on the request body
  rather than on the path, and the SDL isn't consumed at all — the mock and the contract stop being connected,
  which is the property this whole arrangement exists to preserve.

Record the answer as `stack.apiMock`. `iru-setup-java-springboot-testcontainers` adds the container and points
every client's base URL at it; if no client was named, set `stack.apiMock: none` and no mock is added.

### 4i — Observability (ask each; recommend all three)

- **Logging** — needed to get log traces into a centralized server; by default they only go to the local console.
  Add the logging starter and configure structured (JSON) output for deployed profiles while keeping
  human-readable console output for the `local`/`dev` profile. The user can always turn logging off via
  configuration. Recommend it.
- **Metrics** — needed to report technical and functional metrics and get real insight into service behaviour.
  - ids: `actuator`, `prometheus` (Micrometer's Prometheus registry is the backend), optionally `otlp-metrics` if
    metrics should also go out over OTLP.
  - Creates the `infrastructure/metrics/` module: a domain-defined metrics port, implemented here over
    Micrometer, including the tags each metric carries and the mapping from domain entities to tag values.
  - Local execution gets **Prometheus and Grafana** containers in the docker compose stack so metrics are
    actually visible while developing.
- **Tracing** — needed to see how each received request flowed and what response was produced.
  - ids: `distributed-tracing`, `opentelemetry`. (In Boot 4 these resolve to
    `spring-boot-starter-opentelemetry`.)

### 4j — Spring AI (yes/no)

Used to integrate with LLMs — building "agentic" services or MCP servers/clients.

If yes:

1. Warn first that this **requires a paid subscription with an LLM provider, billed separately from any cloud
   spend** — token cost is the user's, not this scaffold's.
2. Offer the providers actually present in Step 1's catalogue. As observed at the time of writing these include:
   Anthropic Claude (`spring-ai-anthropic`), OpenAI (`spring-ai-openai`), Google GenAI
   (`spring-ai-google-genai`), Amazon Bedrock (`spring-ai-bedrock-converse`), Mistral AI (`spring-ai-mistral`),
   DeepSeek (`spring-ai-deepseek`), and Ollama (`spring-ai-ollama`, local models — no subscription needed).
   Present the live list, not this one. If the deployment platform from Step 3 is AWS, mention Bedrock, and if
   it's Google Cloud mention Google GenAI, as the options that authenticate via the platform's own IAM instead of
   a separate API key.
3. If embeddings will be stored, ask for the vector store and keep it consistent with 4a:
   `spring-ai-vectordb-qdrant` (preferred at volume), `spring-ai-vectordb-pgvector` (fine when PostgreSQL is
   already in play and vector volume is modest), or none.
4. Also offer `spring-ai-mcp-server` / `spring-ai-mcp-client` if the service is meant to expose or consume MCP.
5. **Never write an API token into the repository in clear text.** State this explicitly to the user, and record
   it as a required manual step in Step 11's report:
   - The generated configuration references the token only as `${<PROVIDER>_API_KEY}` — an environment variable
     resolved at runtime, with **no default value and no example key committed anywhere**.
   - For local development: export it in the shell, or put it in a `.env` file that is **git-ignored** (this
     skill adds `.env` to `.gitignore`). Never in `application.yml`.
   - For CI: a GitHub Actions secret, exposed to the job as an environment variable.
   - For deployed environments: the platform's secret manager — AWS Secrets Manager or Google Secret Manager —
     injected into the container by the OpenTofu configuration, never baked into the image.
   - Tell the user that if a token has ever been committed, rotating it at the provider is the only real fix, and
     `/iru-check-security` can scan the repository for accidentally committed secrets.

### 4k — Dynamic configuration (`AskUserQuestion`, exactly one)

Needed to change service behaviour while it is running, with no restart and no redeployment. Default the
recommendation from Step 3's platform choice:

- **Kubernetes ConfigMaps and Secrets** — recommended when deploying to GKE, or to Cloud Run/EKS backed by
  Kubernetes. Add `org.springframework.cloud:spring-cloud-starter-kubernetes-client-config` (it has no
  Initializr id in the current catalogue, so declare it explicitly with its version in the root pom, and verify
  the Spring Cloud release train in use actually ships a build compatible with the chosen Boot version — report
  it as an open gap if it doesn't).
- **AWS Secrets Manager / Parameter Store** — recommended when deploying to AWS. Add
  `io.awspring.cloud:spring-cloud-aws-starter-secrets-manager` (and
  `spring-cloud-aws-starter-parameter-store` for non-secret dynamic properties), with the
  `io.awspring.cloud:spring-cloud-aws-dependencies` BOM imported in the root pom.
- **Spring Cloud Config Server** — the platform-independent option: add `cloud-config-client`, and `cloud-bus`
  as well if a config change must be broadcast to every replica at once rather than polled.
- **None** — configuration changes require a redeploy. Acceptable for a service with no operationally-tunable
  behaviour, but note in Step 11's report that this removes the fastest mitigation lever during an incident.

Whichever is chosen, every dynamically reloadable property must be bound in the
`infrastructure/configuration/` module on `@ConfigurationProperties` beans annotated `@RefreshScope`, so a
refresh is picked up without a restart — and every one of them belongs in the documentation's configuration table
with its "dynamically changeable" column set accordingly.

### 4l — Anything else from Initializr

Show the user that the remaining groups in Step 1's catalogue are available (Developer Tools, Template Engines,
Spring Cloud Discovery/Routing/Circuit Breaker, Ops, Testing, …) and offer to add any by id or name. Warn that
anything outside the groups above is typically **less relevant to this architecture**, and that some entries
(template engines, `data-rest`, `cloud-gateway`) actively conflict with the contract-first, hexagonal shape this
scaffold imposes — flag such a conflict rather than silently accepting it.

Always include, regardless of the user's answers: `lombok` (boilerplate generation — **Java only**),
`configuration-processor` (configuration metadata), `validation`, `testcontainers`, and `docker-compose`.
For Java, MapStruct has no Initializr id — it's added explicitly in the root pom together with the
`lombok-mapstruct-binding` needed to make the two annotation processors coexist. For **Kotlin**, send neither
`lombok` nor MapStruct: data classes and properties replace Lombok, mappers are plain extension functions (MapStruct
would need kapt, which is in maintenance mode), and Initializr itself adds `kotlin-reflect`, the Kotlin Jackson
module, and `kotlin-test-junit5` when `language=kotlin`. Note that `configuration-processor` only processes Java
sources; Kotlin `@ConfigurationProperties` classes still bind correctly, they just get no generated IDE metadata.

**Generator plugin names in this step and in Step 8 are the Maven ones.** On a Gradle build the same generators run as
Gradle plugins — `org.openapi.generator`, `com.google.protobuf` (+ `grpc-kotlin`), an AVRO plugin
(`com.github.davidmc24.gradle.plugin.avro` or an equivalent maintained one), `com.vaadin` for Hilla — chosen and
wired by `iru-setup-java-springboot-apis`/`-hilla` from the manifest; the choices in Step 4 don't change.
