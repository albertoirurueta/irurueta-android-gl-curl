---
name: iru-update-java-springboot-documentation
description: "/iru-update-java-springboot-documentation [<page-or-area>] [mode:, on-existing: lines] - creates or refreshes the Antora docs site of a DDD/hexagonal Spring Boot service (Java or Kotlin, per springboot-stack.yml) from the code: overview, architecture, use cases, database model, REST/gRPC/GraphQL/messaging API pages with generated-reference links, configuration and metrics tables, and a User interface section with a Hilla frontend. Bootstraps via iru-setup-antora, adds Kroki, marks underivable facts as TODO, asks Update/Keep as is/Replace on existing pages. Idempotent. Use to create or re-sync a service's docs with its code."
model: sonnet
---

# Update Java Spring Boot Documentation

Create or refresh the Antora documentation for a Spring Boot service. This skill does double duty on purpose: the
first run scaffolds the page set, and every later run updates it from the code — which is the only way
documentation of this depth survives contact with an evolving service.

The governing rule: **everything on these pages is derived from something in the repository.** The contracts in
`apis/`, the `@ConfigurationProperties` classes, the Micrometer calls, the ports in `domain`, the use cases in
`application`, the persistence entities in `infrastructure/database/*`. Where something genuinely cannot be
derived — why a technology was chosen, what a use case is *for*, a business constraint — write an explicit
`TODO:` marker naming what's needed and from whom. **Never write plausible-sounding prose in place of a fact you
don't have**: a confidently wrong architecture page is worse than an obviously incomplete one, because nobody
re-checks it.

## Step 0 — Resolve scope and read the ground truth

Parse the argument: absent means every page; a page name or area (`architecture`, `use-cases`,
`database-model`, `apis/rest`, `apis/grpc`, `apis/graphql`, `apis/messaging`, `configuration`, `metrics`,
`overview`) means just that one. The argument may also carry `key: value` lines (as `iru-setup-java-springboot`
sends them): `mode` (`new`/`existing`, informational — it never means "update silently") and `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`). Ignore any other key.

**Announce the mode before writing anything.** If none of the Step 2 pages exist yet, say this run is **creating
the documentation page set from scratch**. Otherwise say it is **updating existing documentation**, list the
pages found, and — unless `on-existing` is `keep`/`update`/`replace`, in which case apply it to every page and
just print the warning — ask once with `AskUserQuestion` (one question covering the existing pages):

- **Update (Recommended)** — "Refresh the derived parts (tables, diagrams, endpoint/property/metric lists) and
  keep hand-written prose, per Step 2's re-run rule. Contradictions between prose and code are listed, not
  silently overwritten."
- **Keep as is** — "Don't touch existing pages; only create pages that are missing. Existing pages may stay out
  of date with the code — the report lists which ones this run would have changed."
- **Replace** — "Regenerate every existing page from the code. Pages match the code exactly, but every
  hand-written sentence (the 'why' sections only a human can supply) is lost — recoverable only from git."
- **Stop** — "End now without changing anything."

Read `springboot-stack.yml` for the declared stack, including its `ci:` block (`enabled`, `pages`) — Step 7
uses it to decide where the API reference links point — and `project.language` (`java`/`kotlin`, absent ⇒ `java`)
and `project.buildTool` (`maven`/`gradle`, absent ⇒ `maven`), which decide the language/build-tool wording below.
Confirm both against the disk (`pom.xml` vs `settings.gradle.kts` + `gradlew`; `src/main/kotlin` vs
`src/main/java`) — where they disagree the disk wins, and the mismatch goes in the report.
Treat the manifest as **intent**, not truth: the code is truth. Where
they disagree (a database in the manifest with no adapter module, an adapter for something the manifest doesn't
list), say so in the report — that's a real finding about the repository, not a documentation problem.

**Language and build-tool variants.** With `java` + `maven` every step below applies exactly as written. Otherwise
apply these substitutions wherever a step names the build, the doc-comment tool, or quotes code:

| Concern | Java + Maven | Kotlin + Maven | Kotlin + Gradle |
|---|---|---|---|
| Module dependency source | each module's `pom.xml` | each module's `pom.xml` | each project's `build.gradle.kts` (`project(":…")` dependencies) + `gradle/libs.versions.toml` |
| Boundary enforcement (Step 4) | `banned-dependencies` enforcer rules + ArchUnit test | same | the ArchUnit test only (no enforcer on Gradle) — plus the declared `project(":…")` dependencies themselves |
| Doc comments read for use cases (Step 5) | Javadoc | KDoc | KDoc |
| Code reference published | Javadoc in the Maven site | Dokka, one site per module (`<module>/target/dokka`) | Dokka (`build/dokka/html`, root-aggregated or per module) |
| Source snippets on pages | `[source,java]` | `[source,kotlin]` | `[source,kotlin]` |
| Build / unit tests / integration tests | `mvn verify` / `mvn test` / `mvn verify` (Failsafe `*IT`) | same | `./gradlew build` / `./gradlew test` / `./gradlew integrationTest` |
| Run locally | `mvn install -DskipTests`, then `mvn -pl boot spring-boot:run` | same | `./gradlew :boot:bootRun` (Gradle resolves sibling projects itself) |
| Generated API docs (local build output) | `<module>/target/generated-docs/` | same | `<module>/build/generated-docs/` |

Every code snippet a page quotes (a `@ConfigurationProperties` class, a meter registration, an exception mapping)
is copied from the real source, so it is in the project's language — never translate Kotlin into Java for a page
or vice versa. Kotlin-specific facts are worth stating where they apply: a `data class` with `val` properties is
immutable configuration (`@ConfigurationProperties` binds through the constructor), a nullable type (`String?`)
is what makes a property optional, and a default argument value is the property's default.

Then gather the facts, using the `Explore` agent for the broad structural sweeps rather than reading every file
into this context, and reading directly only the specific files it identifies:

| Page | Source of truth |
|---|---|
| Overview | `README.md`, root pom `<description>`, the use cases in `application` |
| Architecture | the module tree, each module's pom (or `build.gradle.kts`) dependencies, `stack.concurrency`, the adapters present |
| Use cases | `<base>.application.usecase.*` — the ports each one calls, the exceptions each one declares/throws |
| Database model | `infrastructure/database/*/entity/*`, the Spring Data repositories, the migration files (Flamingock change classes, Liquibase changelogs, Flyway scripts) — the migrations are where indexes and constraints actually live |
| REST API | `apis/rest-server/*.yaml` and the `@RestControllerAdvice` exception mapping |
| gRPC API | `apis/grpc-server/*.proto` and the gRPC exception handler |
| GraphQL API | `apis/graphql-server/*.graphqls` and the `DataFetcherExceptionResolver`, plus the `@Controller` classes — the SDL says what is exposed, the controllers say what is actually resolved |
| Messaging API | `apis/messaging/*-async-api-v*.yaml` (channels, directions, messages) and `apis/messaging/*.avsc` (payload fields), cross-checked against the `spring.cloud.stream.bindings.*` configuration — the bindings are what the service actually does, the AsyncAPI file is what it claims to do |
| Configuration | every `@ConfigurationProperties` class, every `application*.yml`, and which of them carry `@RefreshScope` |
| Metrics | `infrastructure/metrics/*` — every meter name, its type, and its tag keys |

## Step 1 — Ensure the site exists, with Mermaid and Kroki

If `docs/antora.yml` is absent, invoke `iru-setup-antora` first (via the `Agent` tool with `general-purpose`, or
`Skill` directly) to get the toolchain, playbook, and ROOT module in place.

Then make sure **Kroki is wired in addition to Mermaid** — `iru-setup-antora` installs Mermaid but not Kroki, and
this page set needs both. Mermaid is right for flows, sequences, and state; Kroki adds PlantUML, C4, Graphviz, and
ERD, which is what the database-model and architecture pages need.

```bash
cd docs && npm i asciidoctor-kroki
```

In `docs/antora-playbook.yml`, add Kroki to the `asciidoc.extensions` list, keeping the existing entries:

```yaml
asciidoc:
  attributes:
    mathjax-tex-tags: ams
    mathjax-tex-packages: ams
    # Fetch diagrams at build time and commit nothing generated; images end up in the built site.
    kroki-fetch-diagram: true
    # Default is the public https://kroki.io. Point at a self-hosted instance if the source must not
    # leave the network — diagram source is sent to whichever server this names.
    kroki-server-url: https://kroki.io
  extensions:
    - '@djencks/asciidoctor-mathjax'
    - asciidoctor-kroki
```

Flag in the report that with the public Kroki server, **every diagram's source text is sent to a third-party
service at build time**. For a service whose architecture or database model is sensitive, self-host Kroki (a single
container) and change `kroki-server-url`. This is a genuine consideration, not boilerplate — the database model
page describes the data layout of a real system.

Verify both work by building the site in Step 12; a Kroki block with the extension missing silently renders as a
literal code block rather than failing, which is easy to miss.

Also add the `api-docs-url` attribute to **`docs/antora.yml`** (the component descriptor, not the playbook — the
playbook block above is for build-time extension settings, while this is a content attribute the pages
reference). Steps 7–9 all link to their generated reference through it:

```yaml
# docs/antora.yml — alongside name/version/title/nav
asciidoc:
  attributes:
    api-docs-url: 'https://<owner>.github.io/<repo>/api'
```

Step 7 explains how to resolve the value and what each link path is. If the repository has no `apis/` directory at
all, skip the attribute — there are no API pages to link from.

## Step 2 — Page set and navigation

Create under `docs/modules/ROOT/pages/`, and add each to `nav.adoc` preserving existing entries:

```
index.adoc                    (service overview)
architecture.adoc
use-cases.adoc
database-model.adoc
apis/rest.adoc
apis/grpc.adoc
apis/graphql.adoc
apis/messaging.adoc
configuration.adoc
metrics.adoc
```

Omit a page whose subject doesn't exist (no gRPC server → no `apis/grpc.adoc`); note the omission in the report so
its absence is a recorded decision.

When the manifest's `frontend` is `hilla` (a Vaadin Hilla React frontend under `src/main/frontend/`), the service
also has a user interface: add the **User interface** section — `ui/index.adoc`, `ui/navigation.adoc` (from the
file-based routes under `src/main/frontend/views/`), one `ui/screens/<View>.adoc` per routed view and one
`ui/components/<Component>.adoc` per reusable component — exactly as `reference/ui-docs.md` (next to this file)
describes, including its screenshot synchronisation (§5) against the `.ui-screenshots/` the frontend's Playwright
UI tests produce. Run that synchronisation once, after Step 11 and before Step 12's build. With `frontend: none`
there is no UI section.

Every page carries this immediately after its `= Title` heading, matching the rest of this catalog:

```
NOTE: This documentation was generated with the assistance of AI. Please report any inaccuracies.
```

On a re-run (Step 0's **Update**, the default), **update pages rather than rewriting them**: preserve hand-written prose (especially the "why"
sections that only a human can supply) and refresh the derived parts — tables, diagrams, endpoint lists, property
lists. If a hand-written statement now contradicts the code, don't silently overwrite it: update it and list the
contradiction in the report, since it may mean the code changed without the intent changing. With **Keep as is**,
existing pages are left byte-for-byte untouched and only missing pages (and their `nav.adoc` entries) are added;
with **Replace**, existing pages are regenerated in full.

## Step 3 — `index.adoc` — service overview

What the service is for and what it offers. Short — a page someone reads in a minute:

- One paragraph on purpose, in domain terms.
- What it offers: the capabilities, as a list, derived from the use cases and the API contracts.
- What it depends on, at one line each.
- Where to go next: links to the other pages, plus the generated API references on GitHub Pages — the same
  `{api-docs-url}/...` links Steps 7–9 use, gathered here so the overview is a working index of everything the
  service publishes.
- **Kotlin only** — the code reference: the Dokka KDoc site the build workflow publishes under `doc/api/kdoc/`
  (`iru-setup-java-springboot-github-workflows`), linked as `{api-docs-url}/kdoc/index.html[KDoc reference^]` when
  Gradle aggregates Dokka at the root, or one `{api-docs-url}/kdoc/<module-path>/index.html[<module> KDoc^]` line per
  module otherwise (always per module on Maven, whose Dokka plugin doesn't aggregate). Confirm the layout against the
  workflow's `Merge documentation` step, the same way Step 7 confirms the API paths. The same `ci.pages`/`ci.enabled`
  fallbacks as Step 7 apply: the `documentation` artifact, or the local output (`build/dokka/html` after
  `./gradlew dokkaGeneratePublicationHtml`, or `<module>/target/dokka` after `mvn dokka:dokka`).
- **Kotlin only** — a short "Build, test and run" list with the commands from Step 0's variant table for the
  project's build tool (build, unit tests, integration tests, run locally), so a reader doesn't assume Maven
  conventions in a Gradle build or vice versa. Java services keep the page exactly as it was.

Mark `TODO:` for the business purpose if `README.md` and the pom description don't state it — that's a question
for the service's owner, not something to infer from class names.

## Step 4 — `architecture.adoc` — technical architecture

The deepest page, and the one with the highest chance of drifting into invention. Discipline: state what the code
does, and mark the rationale `TODO:` unless it's recorded somewhere.

- **Hexagonal structure** — the module table, what each module may depend on, and the two mechanisms that enforce
  it (the `banned-dependencies` enforcer rules and the ArchUnit test). Name the test class so a reader can go look.
  On Gradle there is no enforcer: name the ArchUnit test and say the allowed edges are the `project(":…")`
  dependencies declared in each `build.gradle.kts` (Step 0's variant table).
- **Concurrency model** — reactive, blocking, or both, taken from the actual starters in the poms (or the Gradle
  build scripts and version catalog). For Kotlin, also say whether coroutines are in play (`suspend` controller/use
  case functions, `kotlinx-coroutines-reactor`), since that changes how a reader should read a reactive flow.
  Explain the consequence (blocking with virtual threads enabled, or a Netty event loop that must never be blocked). The
  *reason* for the choice is a `TODO:` unless recorded.
- **Per-technology sections** — one each for the databases, cache, Kafka, and clients present. For each: what it
  is used for in this service, which module adapts it, which ports it implements. Include the "why this
  technology" guidance the stack manifest recorded if it's there; otherwise `TODO:`. For each downstream client,
  also name the contract it is generated from (`apis/rest-client/<name>/` or `apis/grpc-client/<name>/`) and the
  fact that the same contract drives the API mock (`stack.apiMock` — Microcks or WireMock) that stands in for that
  service in integration tests and local runs, so a reader knows where to add a response rather than inventing a
  fixture.
- **Dependency diagram** — a Mermaid `flowchart` of the service and its external dependencies (each database,
  cache, Kafka topic direction, and downstream service via REST/gRPC), generated from the modules and bindings
  actually present:

  ````
  [mermaid]
  ....
  flowchart LR
    subgraph service["<service-name>"]
      api["api/*"] --> app["application"]
      app --> domain["domain"]
      infra["infrastructure/*"] -.implements ports.-> domain
    end
    infra --> mongo[(MongoDB)]
    infra --> redis[(Redis)]
    infra -- produces --> topicOut{{orders.events.v1}}
    topicIn{{payments.events.v1}} -- consumed by --> api
    infra --> payments["payments service (REST)"]
  ....
  ````

- **Module dependency diagram** — a Kroki C4 or PlantUML component diagram of the internal module graph, which
  reads better than Mermaid for the ports-and-adapters shape:

  ````
  [plantuml,module-dependencies,svg]
  ....
  @startuml
  ...
  @enduml
  ....
  ````

- **Cross-cutting concerns** — the dynamic-configuration mechanism, how secrets reach the service, the graceful
  shutdown behaviour, and the health probes. Each is a fact readable from the configuration files.

## Step 5 — `use-cases.adoc`

One section per class in `<base>.application.usecase`. For each, derived from the code:

- **What it does**, from the class/method Javadoc (KDoc for Kotlin). If there is none, `TODO:` — and note in the report that the use
  case is undocumented in code, which is worth fixing at the source.
- **Trigger** — which REST endpoint, gRPC method, or Kafka topic reaches it (trace inbound from the
  controllers/services/consumers).
- **Steps** — the ordered calls it makes, as a numbered list.
- **Dependencies** — every port it uses, and which adapter (hence which database, client, or topic) backs each.
- **Exceptions** — every exception it can raise, why, and what the caller sees (the HTTP status or gRPC code from
  the exception-mapping advice).
- **A Mermaid sequence diagram** of the flow, including the failure branch — a happy-path-only diagram hides the
  half of the behaviour that matters during an incident:

  ````
  [mermaid]
  ....
  sequenceDiagram
    participant C as Client
    participant R as RestController
    participant U as PlaceOrderUseCase
    participant P as OrderRepository (port)
    participant A as MongoOrderAdapter
    C->>R: POST /v1/orders
    R->>U: placeOrder(command)
    U->>P: save(order)
    P->>A: save(entity)
    A-->>U: Order
    U-->>R: OrderId
    R-->>C: 201 Created
    Note over U,P: On conflict, ConflictException → 409
  ....
  ````

## Step 6 — `database-model.adoc`

One section per database engine. Read the entity classes **and** the migration files — indexes and constraints are
usually declared in the migration, not the entity, so an entity-only read produces a model page that's missing
exactly the information an operator needs.

Per collection/table/node label:

- What it holds and why it exists.
- A field table: **name, type, mandatory, indexed, constraints (length, pattern, range, enum values), default,
  description**. Every column filled or explicitly `TODO:`.
- Which indexes exist, on which fields, whether unique, and — where derivable from the repository query methods —
  which query each index serves. An index nobody can attribute to a query is worth flagging.
- For relational models: the relationships (cardinality, FK constraints, cascade behaviour).
- For Neo4j: node labels, relationship types and directions, and their properties.

Then a diagram per engine — Kroki's ERD/PlantUML for relational, a Mermaid `erDiagram` for document stores
(showing references between documents even where the database doesn't enforce them, since the *application* does),
and a graph diagram for Neo4j.

Note the migration tool in use and where its changelogs live, so a reader knows how a model change is made.

## Step 7 — `apis/rest.adoc`

### Linking to the generated reference — applies to Steps 7, 8, 8a and 9 alike

**Every API page must link to its generated reference, and each page links to its own.** The REST page links to
the OpenAPI HTML, the gRPC page to the protobuf HTML, the messaging page to the AsyncAPI HTML. These are the
pages `iru-setup-java-springboot-apis` generates into `target/generated-docs/` and the build workflow publishes to
GitHub Pages next to this site. Without the link, the two halves of the API documentation — the prose that explains
and the reference that enumerates — sit on the same site with nothing connecting them, and the reader who needs the
exhaustive field list never finds it.

Put the link **near the top of the page**, right after the intro and before the version summary, not buried at the
bottom: someone opening `apis/rest.adoc` to integrate against the service wants the reference in the first screen.

The generated HTML is **not Antora content**, so `xref:` cannot reach it — an `xref:` to it fails the build or
silently resolves to nothing. It has to be a URL. Define the base once as an attribute in `docs/antora.yml` so all
three pages share it and a domain change is a single edit:

```yaml
# docs/antora.yml
asciidoc:
  attributes:
    api-docs-url: 'https://<owner>.github.io/<repo>/api'
```

Resolve that value rather than guessing it:

- `gh api repos/{owner}/{repo}/pages --jq .html_url` is authoritative when Pages is already configured — it
  accounts for a custom domain, which a URL derived from the remote won't.
- Otherwise derive `https://<owner>.github.io/<repo>` from `git remote get-url origin`, and check for a `CNAME`
  file in the published output or a configured custom domain before settling on it.
- If GitHub Pages isn't set up yet, still write the attribute with the derived URL, and say in the report that the
  links stay dead until Pages is enabled and the build workflow has run once on the main branch.
- **When the manifest says docs are not deployed to Pages** (`ci.pages: false`, or `ci.enabled: false`), don't
  write a github.io `api-docs-url` at all — that site will never exist. Instead:
  - with `ci.enabled: true` and `ci.pages: false`, the build workflow uploads the merged docs as a `documentation`
    workflow artifact: replace each `{api-docs-url}/...` link with a sentence telling the reader the generated
    reference is in that artifact under the same relative path (e.g. `api/rest-server/v1/index.html`), and link the
    repository's Actions page for the workflow;
  - with `ci.enabled: false`, tell the reader to build it locally (the module's documentation execution writes it
    under `target/generated-docs/` — `build/generated-docs/` on Gradle) and name the Maven command (on Gradle, the
    `./gradlew` documentation task the module registers);
  - in both cases still link the source contract (below), and say in the report which variant was used and that a
    private repository on a paid plan can switch to Pages later by setting `ci.pages: true` and re-running this
    skill.

Then link per contract version — `v1` and `v2` each get their own line, since each generates its own page:

```adoc
{api-docs-url}/rest-server/v1/index.html[OpenAPI v1 reference^]
{api-docs-url}/grpc-server/v1/index.html[Protobuf v1 reference^]
{api-docs-url}/messaging/v1/index.html[AsyncAPI v1 reference^]
```

GraphQL publishes **two** references rather than one, because `graphql-codegen-maven-plugin` generates code and the
documentation comes from a separate npm toolchain (`iru-setup-java-springboot-apis` Step 3b):

```adoc
{api-docs-url}/graphql-server/v1/index.html[GraphQL v1 reference^]
{api-docs-url}/graphql-server/v1/voyager/index.html[GraphQL v1 schema graph^]
```

The Voyager graph is optional, so **check `apis/graphql-server/docs/package.json` for `graphql-voyager` before
linking it** — a link to a page that was never generated is worse than no link. If the whole GraphQL documentation
toolchain is absent, link the SDL in the repository instead and name SpectaQL in the report as the way to add a
reference; don't invent the URL either way.

Whatever the toolchain, also say where a reader can reach the **live** schema if the service enables
`spring.graphql.schema.printer.enabled` or the GraphiQL endpoint (`spring.graphql.graphiql.enabled`), noting that
those are normally on in `local`/`dev` profiles only.

The trailing `^` opens the reference in a new tab, so the reader doesn't lose their place in the prose.

**Confirm those paths against the repository rather than copying them.** They must match two things that can drift:
the `output`/`-o` directories configured in the module poms' documentation executions, and the `Merge documentation`
step in `.github/workflows/build.yml` that decides where under `doc/api/` each one lands. If the workflow publishes
somewhere else, the workflow wins — fix the link, and note the discrepancy in the report.

Alongside each generated-reference link, link the **source contract** in the repository too (a `blob` URL to
`apis/rest-server/<spec>.yaml`, `apis/grpc-server/<file>.proto`, or `apis/messaging/<file>.yaml`). The generated
page is the readable form; the contract is the authoritative one, and a reader filing a bug needs to point at it.

One caveat that the build will not catch for you: if `api-docs-url` is undefined, Antora logs
`skipping reference to missing attribute: api-docs-url`, leaves the literal `{api-docs-url}/...[...]` text in the
rendered page, and **still exits 0**. Step 12 checks for exactly this.

### Page content

Derived from every spec in `apis/rest-server/`, one section per version:

- **Version summary** — what functionality each version provides, and the status of each (current, deprecated,
  planned removal). This is the table someone consults before integrating.
- **Entities** — per schema: what it represents, then a field table with **description, type, mandatory,
  deprecated (with reason), constraints** (`maxLength`, `pattern`, `minimum`, `format`).
- **Endpoints** — per operation:
  - HTTP method and full path, path parameters marked;
  - required request headers;
  - whether authorization is required and of what kind (Basic, Bearer/JWT, or a named OAuth2 flow with the
    required scopes) — read this from the spec's `securitySchemes` **and** cross-check it against the
    `SecurityFilterChain` rules in `api/rest-server`. If the two disagree, that's a security finding: report it
    rather than documenting one of them;
  - request and response bodies with their schema references;
  - **every error response**: HTTP status, the error body schema, and what causes it. Cross-check against the
    `@RestControllerAdvice` mapping so an exception the code can throw but the spec doesn't document shows up as a
    gap.
- The generated-reference and contract links described above: one
  `{api-docs-url}/rest-server/<version>/index.html[OpenAPI <version> reference^]` per spec in `apis/rest-server/`,
  plus a link to each spec file in the repository. Don't duplicate the whole reference here — this page explains,
  the generated docs enumerate.

## Step 8 — `apis/grpc.adoc`

Same shape, from `apis/grpc-server/*.proto`:

- Version summary per `package <service>.vN`.
- **Messages and enums** — per type, and per field: description, type, mandatory, deprecated (with reason),
  constraints. Note that protobuf has no native optionality for `proto3` scalars beyond `optional`, so state which
  fields are semantically required and enforced in code.
- **RPCs** — per method: whether it's unary, server-streaming, client-streaming, or bidirectional **and why**
  (streaming is a design decision worth a sentence); the full path (`/<package>.<Service>/<Method>`); required
  metadata/headers; whether authorization is required and how (typically a JWT in the `authorization` metadata
  key); and every error: gRPC `Status.Code`, the error detail payload, and its cause — cross-checked against the
  gRPC exception handler.
- The generated-reference and contract links, per the shared rules in Step 7: one
  `{api-docs-url}/grpc-server/<version>/index.html[Protobuf <version> reference^]` per `package <service>.vN`, plus
  a link to each `.proto` file in the repository. This is the `protoc-gen-doc` output — if Step 3's survey of
  `apis/grpc-server`'s pom shows the documentation execution fell back to a `docker run pseudomuto/protoc-gen-doc`
  step, or isn't wired at all, say so on the page instead of linking to a reference that was never generated.
- The field-numbering and `reserved` discipline, so a reader knows how to evolve the contract safely.

## Step 8a — `apis/graphql.adoc`

From `apis/graphql-server/*.graphqls`, cross-checked against the `@Controller` classes in `api/graphql-server` and
the `spring.graphql.*` configuration. The page must cover three things: the **data models**, the **operations**, and
the **endpoints** they are reached through.

- **Endpoints** — the section a REST-trained reader most needs, because there is normally exactly one. State the
  HTTP endpoint (`spring.graphql.path`, default `/graphql`) and that every query and mutation is a `POST` to it;
  the WebSocket path for subscriptions (`spring.graphql.websocket.path`) if any `Subscription` field exists, or say
  plainly that subscriptions aren't served; the GraphiQL explorer path (`spring.graphql.graphiql.path`, default
  `/graphiql`) **and in which profiles it is enabled**, since leaving it on in production exposes the whole schema;
  the SSE/multipart path if configured; and the authentication expected on each. Read every one of these from the
  `application*.yml` files rather than quoting the defaults — a service that moved its path and a page that says
  `/graphql` is worse than no page.
- **Version summary** per SDL file, following the same convention as the other pages. Note that GraphQL carries no
  version in the request, so which schema a client gets is decided by the endpoint it calls — say explicitly how
  versions are separated here (separate endpoints, or a single evolving schema with deprecations).
- **Data models** — per `type`, `input`, `interface`, `union` and `enum`: what it represents, then a field table with
  **description, type, nullable, deprecated (with the `@deprecated` reason), and arguments**. GraphQL's `!` is the
  non-null marker: read it as the mandatory column rather than inventing one.
- **Operations** — per `Query`, `Mutation` and `Subscription` field: what it does, its arguments and their
  constraints, what it returns, whether authorization is required and of what kind, and which use case backs it.
  Cross-check every field against the controllers: **a field in the SDL that no `@Controller` method resolves is a
  finding**, not a documentation gap — it returns `null` at runtime. (`spring.graphql.schema.inspection` reports
  exactly this at startup; say whether it's enabled.)
- **Errors** — GraphQL answers `200 OK` with an `errors` array, so document the `extensions.classification` values
  the `DataFetcherExceptionResolver` produces and what causes each. A reader who expects HTTP status codes will
  otherwise assume every response succeeded.
- **Limits** — the configured query depth and complexity limits, and the paginated fields, since those are what a
  client must design its queries around. If no limits are configured, say so plainly: it's an availability risk,
  not a blank cell.
- The generated-reference links described in Step 7: the SpectaQL reference, the Voyager schema graph if it was
  generated, the SDL in the repository, and the live-schema pointer where one exists.

## Step 9 — `apis/messaging.adoc`

From the AsyncAPI specification (`apis/messaging/*-async-api-v*.yaml`), the AVRO schemas
(`apis/messaging/*.avsc`), and the Spring Cloud Stream bindings. The AsyncAPI file already states the topics,
directions, and messages, so most of this page is a transcription of it — but it is a hand-written contract, so
**verify it against the bindings rather than trusting it**, and report any channel it declares that no binding
implements (or vice versa) as a finding about the repository.

Note when reading it that an AsyncAPI 3 operation's `action` is written from this service's point of view — `send`
is a message it produces, `receive` one it consumes — and that the topic name is the channel's `address`, not the
channel key. If the document is an older 2.x one, its `publish`/`subscribe` mean the *opposite* of the intuitive
reading (`subscribe` is what the service sends); documenting the arrows backwards is the easiest mistake to make on
this page.

- **Topic summary** — every topic the service produces to or consumes from, its purpose, direction, the consumer
  group used, and the DLQ topic if configured. Topic names come from the bindings configuration and should match
  the AsyncAPI channel keys; say so if they don't.
- **Version summary per topic** — what each schema version provides, and which are still in flight.
- **Messages** — per AVRO record: what event it represents and when it's emitted; then per field: description,
  type, mandatory (a field with no default is mandatory), deprecated (with reason), constraints, and its default.
- The registry's compatibility mode and the subject naming strategy in use, since those determine what schema
  change is legal.
- Which use case produces or consumes each message, linking to `use-cases.adoc`.
- The generated-reference and contract links, per the shared rules in Step 7: one
  `{api-docs-url}/messaging/<version>/index.html[AsyncAPI <version> reference^]` per
  `apis/messaging/*-async-api-v*.yaml`, plus a link to each AsyncAPI file and to the `.avsc` schemas it references.
  Both parts matter here: the generated page renders the AVRO payloads inline (the AsyncAPI document `$ref`s the
  `.avsc` files), so it is the only place a reader sees topics and payload fields together.

As with the REST and gRPC pages, don't restate what the generated HTML already shows well. What justifies this
page is everything the generator can't know: consumer groups, DLQ topics, which use case produces or consumes each
message, and the registry's compatibility mode.

## Step 10 — `configuration.adoc`

The table whose purpose is to answer "what can we change about this service's behaviour without a code change?" —
so it must be complete for the service's own properties.

Columns: **Property | Description | Type | Default | Dynamically changeable | Critical | Notes**

- **Only the service's own properties** (the `@ConfigurationProperties` prefixes from
  `infrastructure/configuration`, and any other project-specific key in `application*.yml`). A Spring Boot
  property goes in only when it's genuinely important to operating *this* service (e.g.
  `spring.threads.virtual.enabled`, a connection-pool size that has been tuned, a Kafka consumer concurrency) —
  mark such rows so it's clear they're framework properties, not the service's own.
- **Default** — the actual default from the code or the YAML, not a guess. If a property has no default and must
  be supplied, say "required" rather than leaving it blank.
- **Dynamically changeable** — Yes only where the bean is genuinely `@RefreshScope`d *and* the configured dynamic
  mechanism can deliver the change. "Restart required" and "Redeploy required" are the other two values.
  Getting this column wrong is actively harmful during an incident, so derive it from the annotations, never
  from intent.
- **Critical** — flag (e.g. `⚠`) the properties that are performance or availability levers: timeouts, retry
  counts, pool sizes, batch sizes, circuit-breaker thresholds, feature toggles that shed load, cache TTLs. The
  purpose of the flag is that during a degradation someone can scan this column for the fastest temporary
  mitigation. Add a short sentence per flagged row saying which symptom it addresses and in which direction to
  move it.
- **A legend** below the table explaining the flag, the "dynamically changeable" values, and the framework-property
  marker.
- Never put a real credential, endpoint, or token in an example value — reference the environment variable or
  secret name instead.

## Step 11 — `metrics.adoc`

Columns: **Metric | Description | Type | Tags (and possible values) | Notes**

- Every meter the service publishes from `infrastructure/metrics`, plus any notable framework meter the team
  actually watches (marked as framework-provided).
- **Type** — counter, gauge, timer, distribution summary, long task timer — read from the Micrometer call.
- **Tags** — every tag key and its possible values. Where a tag's values come from an enum, list them; where they
  can't be enumerated, say so and flag the cardinality risk, because that's the field that takes down a metrics
  backend.
- **Notes** — what a change in this metric indicates, and whether it backs an alert or a dashboard panel.
- Add a short section on where metrics are scraped from (`/actuator/prometheus` on the management port) and how to
  view them locally (the compose `observability` profile → Prometheus on 9090, Grafana on 3000).

## Step 12 — Build and verify

```bash
cd docs && npx antora antora-playbook.yml
```

Fix every error and every warning that indicates a real problem: a broken `xref:`, a malformed AsciiDoc table (the
most common failure with tables this wide), invalid Mermaid or PlantUML syntax, a missing nav entry.

Then verify what the build won't catch:

- **Kotlin only: the KDoc link(s) on the overview resolve the same way** — no literal `{api-docs-url}` in the built
  HTML, and the path matches where the workflow publishes Dokka (`doc/api/kdoc/…`).
- **Every Mermaid and Kroki diagram actually rendered** — open the built HTML and check. A Kroki block with a
  syntax error, or with the extension unregistered, renders as a literal code block without failing the build. For
  Mermaid, also run the check of `reference/mermaid.md` (`npm i --no-save mermaid@11 jsdom@31 puppeteer@25`, then
  `npm run validate:mermaid` or the repository's `scripts/mermaid/validate-mermaid.mjs modules --render`) and fix every
  diagram it reports: write class-diagram stereotypes and placeholders in labels per its rules. When the repository has
  neither, run the copy of an installed `iru-setup-antora`, `iru-update-antora` or `iru-build-docs` from `docs/`; with
  none installed, rely on the visual check above and recommend `/iru-update-antora` (it copies `scripts/mermaid/`).
- **Each of `apis/rest.adoc`, `apis/grpc.adoc` and `apis/messaging.adoc` that exists carries its generated-reference
  link, and the link resolved.** Two distinct checks, because neither fails the build:
  - Grep the build log for `skipping reference to missing attribute: api-docs-url`. Antora logs that warning,
    leaves the literal `{api-docs-url}/...` text in the page, and still exits 0 — so the page looks finished and
    ships a visibly broken link.
  - Grep the built HTML (`docs/build/site/**/apis/*.html`) for the expected `href` on each page. An `<a href>`
    containing `{api-docs-url}` means the attribute never resolved; no matching `<a>` at all means the link was
    never written.
- The link targets match where the build workflow actually publishes (`doc/api/rest-server/…`,
  `doc/api/grpc-server/…`, `doc/api/messaging/…`), cross-checked against `.github/workflows/build.yml` rather than
  assumed. If Pages has already published at least once, fetching one of the URLs is the definitive check; a 404
  before the first main-branch build is expected, not a defect.
- No page contains an invented fact where a `TODO:` belongs. Re-read the architecture and use-case pages
  specifically with that question in mind — they're where invention creeps in.

## Step 13 — Report

Summarize:

- Pages created, pages updated, pages omitted (and why).
- The language/build-tool variant applied (and whether the disk overrode the manifest), and for Kotlin the KDoc
  link(s) written and the build commands named on the overview.
- Whether Kroki had to be installed and the playbook amended.
- Whether this run created the page set from scratch or updated it, the option chosen in Step 0 (Update / Keep
  as is / Replace, asked or from `on-existing`), and per page: created / updated / replaced / kept as is.
- The `api-docs-url` value resolved, how it was resolved (the Pages API, the git remote, or a custom domain) — or,
  with `ci.pages: false`/`ci.enabled: false`, that no Pages URL was written and which artifact/local-build wording
  replaced the links — and
  the generated-reference link written on each API page. Call out explicitly any API page whose reference isn't
  generated at all — a gRPC documentation execution that was never wired, or messaging documentation missing
  because the AsyncAPI step was skipped — and whether GitHub Pages has published yet, since until it has, every one
  of these links 404s.
- **Every `TODO:` written, grouped by page**, so the user has a concrete list of what only a human can supply. This
  is the most useful part of the report — present it as a list, not a count.
- **Every discrepancy found between the code and the manifest, the spec and the security configuration, or the
  spec and the exception mapping.** These are findings about the service, not about the documentation: an
  undocumented error response, an endpoint whose spec security doesn't match its filter chain, a property that
  looks dynamic but isn't `@RefreshScope`d, a metric with unbounded tag cardinality, an index no query uses.
- Whether hand-written prose was preserved or contradicted on a re-run.
- With `frontend: hilla`: the `ui/` pages created/updated/removed and the screenshot synchronisation result (images
  added, replaced, unchanged, deleted; states left "screenshot pending").
- The build result and the path to `docs/build/site/index.html`.

Warn explicitly:

- **Review the derived content before trusting it**, particularly the architecture rationale and the use-case
  descriptions — those are the pages where a plausible sentence can be wrong.
- **The configuration table's "dynamically changeable" and "critical" columns get used under pressure.** If either
  is wrong, someone will make an incident worse by acting on it. Verify them.
- With the public Kroki server, diagram source leaves the network at build time; self-host if the model is
  sensitive.
- Re-run this skill after any change to a contract, an entity, a configuration property, a metric, or a use case —
  the pages are derived, so they go stale the moment the code moves.
