---
name: iru-setup-quarkus-service
description: "Invoked as /iru-setup-quarkus-service with optional key: value args, usually by iru-setup-quarkus-repository. Interviews for identity, language, build tool, layout and extensions, writes quarkus-stack.yml, scaffolds a Quarkus service (single or hexagonal multi-module) and wires coverage, quality tooling and a tested sample feature."
model: sonnet
---

# Setup Quarkus Service

Scaffold a Quarkus service, or catch an existing one up, so that everything downstream in this catalog works on it
from day one: the coding skills (`iru-quarkus-code-one-task(-group)`) find the layout, test levels, coverage engine
and quality tools they expect; the gate skills (`iru-java-test`, `iru-java-coverage`, `iru-java-code-quality`,
`iru-java-javadoc`, `iru-kotlin-code-quality`, `iru-kotlin-kdoc`) find something wired to run; and
`iru-setup-quarkus-github-workflows` finds the commands it puts into CI.

This skill is the **interviewer and scaffolder** for the service itself. It doesn't write the Antora site,
`.gitignore`, workflows, `CHANGELOG.md`, `README.md` or `LICENSE` — `iru-setup-quarkus-repository` chains the skills
that do. The verified templates it applies live in `reference/` (see `reference/README.md` for which to load —
**only** the ones the chosen language/build tool/layout needs).

Non-negotiables, because the rest of the catalog depends on them:

- **Java 21 minimum** (offer 21 by default; 25 when the stream lists it). Refuse anything lower.
- **The platform version and extension ids come from the live code.quarkus.io API**, never from memory.
- **Generated code follows `iru-quarkus-code-one-task/reference/`** (`java.md`/`kotlin.md`, `cdi-and-beans.md`,
  `testing.md`, `configuration.md`, `hexagonal-architecture.md`) — the sample code in `reference/` already does.
- **No credentials in the repository.** Production connection settings come from environment variables only.

## Step 0 — Parse inputs, detect the setup mode, check the repository is a safe starting point

Parse `args` first, as `key: value` lines, one per line, e.g.:

```
mode: new
open-source: yes
visibility: public
ci: yes
ci-tests: full
pages: yes
distribution: registry
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_orders-service
sonar-host-url: https://sonarcloud.io
group-id: com.example
artifact-id: orders-service
package: com.example.orders
language: kotlin
build-tool: gradle
layout: single
on-existing: ask
```

Recognized keys: `mode` (`new`/`existing`), `open-source` (`yes`/`no`), `visibility` (`public`/`private`/
`internal`), `ci` (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `distribution` (`none`/
`registry`), `sonar` (`cloud`/`self-hosted`/`none`) plus — only when not `none` — `sonar-organization`,
`sonar-project-key`, `sonar-host-url`; `on-existing` (`ask`/`keep`/`update`/`replace`, default `ask`, scripted runs
only — never asked); and the identity/shape keys `group-id`, `artifact-id`, `version`, `package`, `description`,
`developer-name`, `developer-email`, `organization-url`, `license` (a display name, or `none`), `java-version`,
`language` (`java`/`kotlin`), `build-tool` (`maven`/`gradle`), `layout` (`single`/`hexagonal`). Every key found is
resolved — skip its question below. Ignore any other key. If `args` is absent or doesn't look like this format, treat
everything as unset.

**Detect the mode.** `mode` from `args` wins. Otherwise: an existing `quarkus-stack.yml`, or a root `pom.xml`/
`build.gradle.kts`/`build.gradle` that is a Quarkus build (`quarkus-maven-plugin`, `io.quarkus` plugin, or
`quarkus-bom` anywhere in the build files) means `existing`; nothing → `new`. Any owned artifact already on disk makes
that artifact an update even under `mode: new`.

**Hard stop only for an incompatible repository.** A root build that is *not* Quarkus — a Spring Boot build
(`spring-boot-starter-parent`/`org.springframework.boot`), an Android Gradle build (`com.android.*`), or any other
JVM build with its own `src/main` — can't be turned into this scaffold without a migration. Ask with
`AskUserQuestion`: **Stop (Recommended)** — "end now, nothing written; this build isn't Quarkus" — or **Continue
anyway** — "every build file will be offered for Replace, and the existing sources are left where they are, outside
whatever this skill generates". This applies even when `mode: existing` came via `args`.

**Setup mode announcement.** Before asking anything else, print:

- nothing owned present → "Setting up this Quarkus service **from scratch** — no prior setup found."
- otherwise → "**Updating an existing Quarkus service** — prior setup found:" and an inventory table, each row
  **absent / present / partial** with the evidence:

  | Component | Evidence |
  |---|---|
  | Manifest | `quarkus-stack.yml` |
  | Build | `pom.xml` (+ module poms) or `build.gradle.kts` + `settings.gradle.kts` + `gradle.properties` (partial: some missing, or `<modules>`/`include(...)` naming directories that don't exist) |
  | Build wrapper | `mvnw`, `.mvn/wrapper/` or `gradlew`, `gradle/wrapper/` |
  | Coverage wiring | `quarkus-jacoco` + JaCoCo report/check in the build |
  | Quality config | `checkstyle.xml` (Java) / `detekt.yml` + `.editorconfig` (Kotlin), and the plugins in the build |
  | Sources | `src/main/{java,kotlin}`, `src/test/…` (per module when hexagonal) |
  | Configuration | `src/main/resources/application.properties` / `application.yaml` (in `app/` when hexagonal) |
  | Container files | `src/main/docker/Dockerfile.*`, `.dockerignore` |
  | Sonar config | `sonar.*` in `pom.xml` / a `sonar {}` block in `build.gradle.kts` |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it." (Or,
  when `on-existing` came via `args`, which choice will be applied to all of them.)

If `quarkus-stack.yml` exists, read it and offer its values as the defaults for every question below, so a re-run is
a cheap "add one more extension" pass.

**Prerequisites** (report what's missing; none is a hard stop — files can still be written, they just can't be
verified in Step 9): `java -version` (21+), `mvn -v` (needed by `create` even for a Gradle build — or Docker, see
Step 7), `docker info` (Dev Services and Testcontainers), `curl --version`, `git rev-parse --show-toplevel`.

## Step 1 — Resolve the platform and the extension catalogue from the live API

```bash
curl -fsS https://code.quarkus.io/api/streams -o "$TMPDIR/quarkus-streams.json"
```

Keep the entry with `"recommended": true` and `status: FINAL` — its `key` (e.g. `io.quarkus.platform:3.40`),
`platformVersion` (e.g. `3.40.1`) and `javaCompatibility.versions`. Offer that platform with `AskUserQuestion`
(recommended), the previous FINAL stream as the conservative choice, and "other". Never offer a non-FINAL stream
(`BETA`/`CR`) as the default; if the user picks one, warn it isn't production-ready. If code.quarkus.io is
unreachable, try the registry (`curl -fsS https://registry.quarkus.io/client/platforms`, first stream's first
release); if both fail, **stop** — don't fall back to remembered versions.

Then fetch the catalogue for that stream (it lists core *and* Quarkiverse extensions, ~1,000 entries):

```bash
curl -fsS "https://code.quarkus.io/api/extensions/stream/<stream-key>?platformOnly=false" -o "$TMPDIR/quarkus-extensions.json"
```

Every extension id you intend to pass to `create` must appear there (`id`, e.g. `io.quarkus:quarkus-rest`;
`create` accepts the short form `rest`). If an id from Step 4's table is missing, find its replacement by `name`
and say so — never guess. Test-only artifacts (`quarkus-junit`, `quarkus-junit-component`, `quarkus-junit-mockito`,
`quarkus-jacoco`, `rest-assured`) aren't extensions in this catalogue; they're managed by the BOM and added in Step 8.
(Note the 3.40 renames: `quarkus-junit5*` → `quarkus-junit*`; the old ids still resolve but `create` writes the new
ones — follow what `create` writes.)

## Step 2 — Identity

Ask in plain conversation (skip what `args` or the manifest resolved): **groupId**, **artifactId**, **version**
(default `1.0.0-SNAPSHOT` — `iru-release` and the Sync workflow expect `X.Y.Z-SNAPSHOT`), **base package** (need not
equal groupId), **one-line description**, **developer name / email / organizationUrl**. Then **license** with
`AskUserQuestion`: Apache License 2.0 (recommended) / MIT / No license (proprietary — common for services) / Other.

Derive, don't ask: owner/repo/host from `git remote get-url origin` (ask for the intended URL if there is no
remote), inception year from `git log --reverse --format=%ad --date=format:%Y | head -1` (else the current year).

## Step 3 — Shape: language, build tool, layout, Java version

Use `AskUserQuestion` for each (skip what's resolved):

- **Language** — **Java** (recommended unless the repository already has Kotlin sources) / **Kotlin** — all-open/
  no-arg compiler plugins, MockK, detekt/ktlint/Dokka instead of Checkstyle/PMD/SpotBugs/Javadoc.
- **Build tool** — **Maven** (recommended for Java, and for any existing Maven repository) / **Gradle (Kotlin DSL)**
  (recommended for a new Kotlin service). Gradle is always the Kotlin DSL (`-DbuildTool=gradle-kotlin-dsl`).
- **Layout** — **Single module** (recommended for a small service: feature packages, resource → service →
  repository) / **Hexagonal multi-module** (`domain`/`application`/`infrastructure/*`/`api/*`/`app`, dependency
  direction enforced by ArchUnit — worth it when the domain is rich or several adapters share it; see
  `reference/hexagonal.md`).
- **Java version** — `21` (recommended) or the highest LTS in the stream's `javaCompatibility.versions` (e.g. `25`).

## Step 3b — Open source, visibility, CI, Sonar, distribution

In this order, skipping anything `args`/the manifest resolved; `AskUserQuestion` for each; cost facts are per
GitHub's/SonarSource's current pricing — say so and tell the user to double-check their plan:

- **Open source** — Yes / No, no default. Drives the recommendations below.
- **Visibility** — detect with `gh repo view --json visibility -q .visibility` (lowercase it) when there's a GitHub
  remote; otherwise ask **Public** (recommended when open source) / **Private**. Open source + private/internal is
  contradictory — say so and ask which is right. `internal` counts as `private` below.
- **CI** — "Generate GitHub Actions workflows (CI/CD)?" Public: **Yes** (standard runners are free). Private: still
  **Yes** first, stating that runs consume the included minutes (2,000/month Free, 3,000 Pro/Team, 50,000 Enterprise
  Cloud) then bill per minute (Linux 2-core ≈ $0.006/min; this pipeline is Linux-only — a native build job is the
  expensive one, several minutes per run). **No** — "build, test and release locally; run
  `/iru-setup-quarkus-github-workflows` later". Recorded only; this skill writes no workflows.
- **Sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" **SonarCloud**
  recommended only for a public, open-source repository; otherwise **None** recommended (SonarCloud is free only for
  public/open-source projects), **self-hosted SonarQube** second (free Community Build, you run the server). With
  `ci: no`, say nothing would send reports automatically and recommend None. For cloud/self-hosted collect
  `sonar-organization` (suggest `<owner>-github`; self-hosted only if the server has organizations),
  `sonar-project-key` (suggest `<owner>_<repo>`), `sonar-host-url` (`https://sonarcloud.io`, or ask).
- **CI tests** (only with `ci: yes`) — **Unit only** (`unit`; recommended for private): plain + component tests,
  Dev Services off, every `@QuarkusTest` excluded, no Docker in CI. **Full** (`full`; recommended for public): also
  `@QuarkusTest` with Dev Services containers and `@QuarkusIntegrationTest` against the packaged artifact (GitHub's
  Ubuntu runners have Docker).
- **Pages** (only with `ci: yes`) — public: **yes** without asking (say so in the report); private: **No
  (Recommended)** — "CI builds the docs and uploads them as an artifact" — vs **Yes** — "needs Pro/Team/Enterprise
  Cloud; the site is public unless Enterprise Cloud private Pages".
- **Distribution** — does CI publish a container image? **Registry** (recommended when the service is deployed as a
  container — default `ghcr.io/<owner>/<artifact-id>`, free for public repositories, storage/transfer quotas for
  private ones) / **None** (CI builds and tests only). With Registry, ask the **image builder**: **Jib**
  (`quarkus-container-image-jib`, recommended — no Docker daemon needed to push, reproducible layers) or **Dockerfile**
  (`src/main/docker/Dockerfile.jvm` with `docker/build-push-action`). This decides one extension here and the publish
  job in `iru-setup-quarkus-github-workflows`.

## Step 4 — Technologies

Work through these groups in order. Present each option with its "right when" guidance, then ask (plain
conversation for multi-select, `AskUserQuestion` for bounded single choices). Collect extension ids as you go and
validate each against Step 1's catalogue. For Kotlin, use the `-kotlin` Panache variants.

| Group | Options → extension ids (short form) | Right when |
|---|---|---|
| REST server | always `rest`, `rest-jackson`; offer `smallrye-openapi` (OpenAPI + Swagger UI) and `hibernate-validator` (Bean Validation, recommended) | every service in this scaffold exposes HTTP |
| Persistence | **None** / **Hibernate ORM + Panache** `hibernate-orm-panache` (`-kotlin`) — blocking, recommended default / **Hibernate Reactive + Panache** `hibernate-reactive-panache` (`-kotlin`) — only for a fully reactive (Mutiny) service / **MongoDB + Panache** `mongodb-panache` (`-kotlin`) | relational data with transactions → ORM; document data → MongoDB |
| Database (relational) | exactly one: **PostgreSQL** (recommended) `jdbc-postgresql` (reactive: `reactive-pg-client`), MySQL `jdbc-mysql`/`reactive-mysql-client`, MariaDB `jdbc-mariadb`, SQL Server `jdbc-mssql`, Oracle `jdbc-oracle`, DB2 `jdbc-db2` | — |
| Migrations (relational) | **Flyway** `flyway` (recommended) / **Liquibase** `liquibase` / None (Hibernate drop-and-create in dev/test only — say it's unsuitable for production) | always pick one for anything deployed |
| Messaging | None / **Kafka** `messaging-kafka` / AMQP 1.0 `messaging-amqp` / RabbitMQ `messaging-rabbitmq` / Pulsar `messaging-pulsar`; ask directions (produce/consume) | events between services; Kafka unless the platform already runs another broker |
| REST clients | `rest-client-jackson`; ask one **name** per downstream service | calling other HTTP services (WireMock via `@QuarkusTestResource` in tests) |
| gRPC | `grpc` (server and/or clients; `.proto` in `src/main/proto`) | binary contracts between internal services |
| Security | None / **OIDC** `oidc` (validates bearer tokens; Keycloak Dev Service in dev/test) / **SmallRye JWT** `smallrye-jwt` (verify JWTs with a known key) | endpoints that must be authorized |
| Observability | **Health** `smallrye-health` (recommended always — liveness/readiness for any orchestrator), **Metrics** `micrometer-registry-prometheus`, **Tracing** `opentelemetry`, **JSON logs** `logging-json` | recommend all four for a deployed service |
| Native | **No** (recommended) / **Yes** — native executable built in CI from `create`'s `native` profile; warn that a native build takes minutes and GBs of RAM per run and that reflection-heavy code needs `@RegisterForReflection` (`iru-quarkus-code-one-task/reference/native.md`) | fast start-up / low memory (serverless, scale-to-zero) |
| Kotlin extras | `kotlin` (always for Kotlin); coroutines need no extension | — |
| Anything else | any id from the catalogue (cache, scheduler, mailer, …) — warn when it conflicts with the chosen layout (e.g. a `-panache` active-record style vs the hexagonal "entities never cross the port" rule) | — |

Don't pass `arc` — it isn't in the catalogue, and `create` adds `quarkus-arc` itself. Always add, when
`distribution: registry` with Jib, `container-image-jib`. Hexagonal builds additionally use `io.smallrye:jandex-maven-plugin` / `org.kordamp.gradle.jandex`
and `com.tngtech.archunit:archunit-junit5` — plugins/test libraries, not extensions.

## Step 5 — Confirm the whole plan before writing anything

One compact summary: identity, platform + Java version, language/build tool/layout, open source/visibility/CI/
ci-tests/pages/Sonar/distribution (+ image builder), every extension id, the migration tool, client names, native, and
— for hexagonal — the exact module list (from the table in `reference/hexagonal.md`, only the modules the choices call
for). Ask for confirmation or corrections; loop back as needed. **No file is written before this confirmation.**

## Step 6 — Write `quarkus-stack.yml`

At the repository root; meant to be committed. Every later run (and `iru-setup-quarkus-github-workflows`) reads it.
If it already exists, warn and — unless `on-existing` decides — ask **Update (Recommended)** (rewrite with this run's
answers, keep unknown keys) / **Keep as is** (later steps use the *old* values; list each difference in Step 10) /
**Replace** (fresh file, unknown keys and comments lost).

```yaml
# Generated by /iru-setup-quarkus-service. Records what this service was scaffolded with.
# iru-setup-quarkus-github-workflows and re-runs of this skill read it; keep it in sync by re-running the skill.
project:
  groupId: com.example
  artifactId: orders-service
  version: 1.0.0-SNAPSHOT
  basePackage: com.example.orders
  description: Handles the order lifecycle.
  javaVersion: "21"
  quarkusVersion: "3.40.1"        # platform version (io.quarkus.platform:quarkus-bom)
  quarkusStream: "io.quarkus.platform:3.40"
  language: java                  # java | kotlin
  buildTool: maven                # maven | gradle (Gradle = Kotlin DSL)
  layout: single                  # single | hexagonal
  license: Apache License 2.0     # or none
  repository:
    host: github.com
    owner: example
    name: orders-service
  inceptionYear: "2026"
  developer:
    name: Jane Doe
    email: jane@example.com
    organizationUrl: https://github.com/jane
openSource: true
visibility: public                # public | private | internal
sonar:
  mode: cloud                     # cloud | self-hosted | none
  organization: example-github    # unset when mode: none
  projectKey: example_orders-service
  hostUrl: https://sonarcloud.io
ci:
  enabled: true                   # false ⇒ iru-setup-quarkus-repository skips the workflows step
  tests: full                     # unit (no containers in CI) | full (Dev Services + @QuarkusIntegrationTest)
  pages: true
distribution:
  mode: registry                  # none | registry
  registry: ghcr.io               # registry host when mode: registry
  imageBuilder: jib               # jib | docker
extensions: [rest, rest-jackson, hibernate-validator, smallrye-openapi, hibernate-orm-panache, jdbc-postgresql,
             flyway, smallrye-health, micrometer-registry-prometheus, opentelemetry, logging-json,
             container-image-jib]
persistence: hibernate-orm-panache  # none | hibernate-orm-panache | hibernate-reactive-panache | mongodb-panache
databases: [postgresql]
migrations: flyway                # flyway | liquibase | none
messaging: { broker: none, directions: [] }   # kafka | amqp | rabbitmq | pulsar | none
rest: true
restClients: []                   # one name per downstream service
grpc: { server: false, clients: [] }
security: none                    # none | oidc | smallrye-jwt
observability: { health: true, metrics: true, tracing: true, jsonLogging: true }
native: false
modules: []                       # hexagonal only: the module directories created, e.g. [domain, application, infrastructure/database/postgresql, api/rest, app, coverage]
```

## Step 7 — Scaffold with `create` (new builds only)

Skip this step when the repository already has a Quarkus build (Step 0) — an existing build is post-processed in
place (Step 8), never regenerated by `create` unless the user chose **Replace** for the build component.

`create` always writes into a new `<artifactId>/` subdirectory, so run it in a scratch directory **outside** the
repository and copy the result in:

```bash
work="$TMPDIR/iru-setup-quarkus-service/$(date +%s)"; mkdir -p "$work" && cd "$work"
mvn -B -ntp io.quarkus.platform:quarkus-maven-plugin:<platform-version>:create \
  -DprojectGroupId=<group-id> -DprojectArtifactId=<artifact-id> -DprojectVersion=<version> \
  -DpackageName=<base-package> -DjavaVersion=<java-version> \
  -DbuildTool=<maven|gradle-kotlin-dsl> -DnoCode=true \
  -Dextensions='<comma-separated short ids, incl. kotlin for Kotlin>'
```

- `-DnoCode=true` — no starter `GreetingResource`; this skill writes its own sample (Step 8) that follows the
  catalog's rules.
- No local Maven: `docker run --rm -v "$work":/w -w /w maven:3-eclipse-temurin-21 mvn …` (same arguments).
- Gradle: `create` writes the wrapper (`gradlew`, `gradle/wrapper/*`, Gradle 9.7.0 for 3.40) — use it, don't run a
  system `gradle wrapper`.

Copy into the repository root (single) — `pom.xml` or `build.gradle.kts` + `settings.gradle.kts` +
`gradle.properties`, the wrapper, `src/main/resources/application.properties`, `src/main/docker/`, `.dockerignore`.
**Never copy `create`'s `README.md` or `.gitignore`** (`iru-setup-readme`/`iru-setup-java-gitignore` own them). For a
hexagonal layout copy only the wrapper and `src/main/docker/` (into `app/src/main/docker/`), and build the reactor/
multi-project from `reference/hexagonal.md`. Each destination file that already exists goes through the
per-artifact question in Step 8.

## Step 8 — Post-process to this catalog's conventions

Load `reference/README.md`, then only the files it routes you to. For every artifact below that already exists,
warn and — unless `on-existing` decides — ask with `AskUserQuestion` (batch up to four per call, name concrete
mismatches with this run's choices, e.g. "your pom has no `quarkus-jacoco`", "it runs Checkstyle but the language
is Kotlin"):

- **Update (Recommended)** — "Merge this skill's blocks in (missing plugins/dependencies/properties, outdated
  versions this skill owns) and keep everything else. You review a diff first."
- **Keep as is** — "Leave it; the report lists what it's missing against this run's choices."
- **Replace** — "Regenerate it from the template; customizations are lost (recoverable from git)."
- **Stop** (first question only) — "End now; nothing more is written."

For **Update**, write the merged result to a temp path, show `git diff --no-index <file> <draft>`, and write only
once accepted. Sources and tests are never replaced wholesale: the sample feature is written only where no source
exists yet (a repository with real code gets no sample, and says so).

Apply, per `reference/`:

1. **Identity** — `name`/`description`/`url`/`inceptionYear`/`licenses`/`developers`/`scm` in the pom (Maven), or
   `group`/`version` moved to `gradle.properties` (Gradle).
2. **Test stack** — `quarkus-junit-component`, `quarkus-junit-mockito`, `rest-assured`, `quarkus-jacoco` (+ MockK for
   Kotlin), BOM-managed.
3. **Kotlin compiler plugins** — all-open (`Path`, `ApplicationScoped`, `RequestScoped`, `Entity`, `QuarkusTest`) and
   no-arg (`Entity`, `Embeddable`, `MappedSuperclass`) exactly as `iru-quarkus-code-one-task/reference/kotlin.md`.
4. **Coverage** — JaCoCo agent (excluding the Quarkus class loader) + `quarkus-jacoco` data into one merged
   report with the **80% line check** wired into `verify`/`check`; `jacoco-agent.destfile` pointed at the Quarkus
   data file so `@QuarkusComponentTest` coverage is visible; `%test.quarkus.jacoco.report=false`.
5. **Quality + docs** — Java: Checkstyle (+ root `checkstyle.xml`), PMD, SpotBugs, Javadoc with doclint, Maven
   reporting/site. Kotlin: detekt (+ `detekt.yml`), ktlint (+ `.editorconfig`), Dokka (+ `module.md`).
6. **Integration tests** — Maven keeps `create`'s Failsafe binding and `skipITs=true` default (CI's `full` passes
   `-DskipITs=false`); Gradle uses the plugin's `quarkusIntTest` over `src/integrationTest`.
7. **Unit-only hook** — Gradle builds read `-PexcludeTestsFile=…` in the `test` task (Maven needs nothing: Surefire's
   `-Dsurefire.excludesFile` is a CLI property).
8. **Sonar** (`sonar` ≠ `none`) — Maven `sonar.*` properties + `sonar-maven-plugin`; Gradle `org.sonarqube` plugin
   + `sonar {}` (coverage XML; detekt/ktlint report paths for Kotlin). No `sonar-project.properties`.
9. **Container image** (`distribution: registry`, Jib) — the `container-image-jib` extension only; coordinates come
   from CI.
10. **Configuration** — `application.properties` from `reference/sample-code.md`'s template: only the blocks for the
    chosen extensions, production values from the environment, Dev Services for dev/test. If the module already
    uses `application.yaml`, merge into that instead — never both formats.
11. **Sample feature + tests** (only where no sources exist) — `reference/sample-code.md` (single) or
    `reference/hexagonal.md` (per module, with producers in `app`, `ArchitectureTest`, Jandex, and a `coverage`
    module on Maven). With persistence + Flyway, `db/migration/V1__baseline.sql`.
12. **Format** — Kotlin: `./gradlew ktlintFormat` / `./mvnw ktlint:format` once, so the generated build scripts and
    sources start clean.

## Step 9 — Verify

Run each gate through the `iru-gate-runner` agent (compact pass/fail + first real error back), fixing what breaks
before reporting success — a scaffold that doesn't build isn't done. Commands per build tool are listed at the end
of `reference/maven.md` / `reference/gradle.md` (and `reference/hexagonal.md`):

1. Compile + code generation (`./mvnw -B verify -DskipTests -Djacoco.skip` / `./gradlew assemble`).
2. **Unit stage** — Dev Services off, `@QuarkusTest` classes excluded, coverage check on — must be green on its own
   (it's what `ci-tests: unit` runs and what the coding skills' coverage gate measures).
3. **Container stage** — the full run (`./mvnw verify -DskipITs=false` / `./gradlew check quarkusIntTest`). Needs
   Docker; if `docker info` fails, report it **unverified** (environment), never as a code failure. If a
   `.integration-test.lock/` directory exists at the repository root, another run holds the container stage —
   wait for it (as `iru-quarkus-code-one-task-group` does) rather than starting containers alongside it.
4. Quality + docs — `iru-java-code-quality` + `./mvnw javadoc:javadoc` / `./gradlew javadoc` (Java), or
   `iru-kotlin-code-quality` + Dokka (Kotlin). Zero findings on the scaffold is the expectation.
5. Hexagonal: the ArchUnit test ran inside 2.

Run all of them regardless of `ci.tests` — `unit` only changes what CI runs. Don't run a native build here.

## Step 10 — Report and warn

- Setup mode (from scratch / updating) and, per component of Step 0's inventory, the action taken: **created /
  updated / replaced / kept as is** (with every mismatch listed for a kept one) — or stopped.
- Identity, platform + stream, Java version, language/build tool/layout, every extension (and any id that had to be
  replaced via the live catalogue), the module list (hexagonal).
- The recorded pipeline choices (`openSource`, `visibility` and where it came from, `ci`, `ci-tests`, `pages`,
  `sonar` + keys, `distribution` + builder) — this skill only records CI/Pages choices for the workflows skill.
- Each Step 9 gate's result, and anything unverified and why (no Docker, no Maven/JDK, a native build not run).
- Open gaps: an extension that couldn't be resolved, a kept build file missing coverage/quality wiring, an existing
  `application.yaml` merged instead of properties, sources that already existed (no sample written).

Warn explicitly: **review every generated file before committing** — especially the build files (pinned plugin
versions were current when written; re-resolve them periodically), `application.properties` (every production value
must come from the environment), and the sample feature, which exists only so the gates have something to run and
should be replaced by the first real feature. Production database/broker/OIDC settings, the Sonar token, and registry
credentials are never written to the repository.
