---
name: iru-quarkus-code-one-task
description: "Invoked as /iru-quarkus-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one Quarkus (Java or Kotlin, Maven or Gradle) plan task and its tests, ticks the checkbox with a group-validation-pending note; Dev Services containers never started; no baseline or full gates, which iru-quarkus-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(./mvnw *) Bash(./gradlew *) Bash(gradle *) Bash(QUARKUS_DEVSERVICES_ENABLED=false *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *)
---

# Implement one plan task (Quarkus)

Carry out a single task's implementation, tests, and checkbox update in a Quarkus service — nothing else. This is
the narrowest execution unit for a Quarkus service in the `iru-code` pipeline; the calling
`iru-quarkus-code-one-task-group` validates the whole bucket afterwards.

Three things make it different from the plain Java/Kotlin coding skills, and all three matter:

1. **Quarkus decides at build time.** Bean discovery, proxies, interceptors, and some configuration are fixed
   during augmentation, so a class that compiles can still be invisible, unproxyable, or removed. The CDI rules in
   `reference/cdi-and-beans.md` are always read, and for Kotlin the compiler-plugin check (Step 2) runs before any
   code is written.
2. **Two of Quarkus's four test levels start containers.** `@QuarkusTest` boots the whole application and starts
   Dev Services containers (a database, Kafka, Keycloak, …) whenever a service has no URL configured;
   `@QuarkusIntegrationTest` does the same against the packaged artifact. Several of these task agents run in
   parallel, so this skill runs only the two levels that never start anything — plain JUnit and
   `@QuarkusComponentTest` — and runs them with Dev Services switched off (Step 7).
3. **Architecture is detected, not assumed.** Hexagonal module rules apply only to a hexagonal multi-module build;
   a single-module service keeps its feature-package layout (Step 0).

It uses a medium model on purpose: the plan carries the hard reasoning, and the framework guidance lives in
`reference/` rather than being re-derived per task.

## Step 0 — Detect the project shape

Settle these once, from the files themselves (an `iru-explore` report earlier in the conversation, with its
`Project type:` `java-quarkus`/`kotlin-quarkus` and `Build & layout:` lines, is a valid shortcut):

- **Build tool**: `pom.xml` (+ `mvnw` → use `./mvnw`) or `build.gradle.kts`/`build.gradle` (+ `gradlew` → use
  `./gradlew`; Kotlin DSL or Groovy). Multi-module: Maven `<modules>`, Gradle `include(...)` in
  `settings.gradle*`. The **app module** is the one applying `io.quarkus:quarkus-maven-plugin` /
  `id("io.quarkus")`.
- **Language of the module the task touches**: Kotlin if it has `src/main/kotlin` (or Kotlin sources under
  `src/main/java`), the `quarkus-kotlin` extension, and the `kotlin-maven-plugin` / `kotlin("jvm")` plugin;
  Java otherwise. A mixed module uses both language files for the files of each language.
- **Architecture**: hexagonal multi-module (sibling `domain`, `application`, `infrastructure`/`adapters`,
  `api`/`rest`/`web` modules with inward-pointing dependencies) vs. single-module / layered. State which.
- **Quarkus version** (`quarkus.platform.version` / `quarkusPlatformVersion` in `gradle.properties`), and whether
  a **native build** exists (`native` profile, `quarkus.native.enabled`, `Dockerfile.native*` used by CI, a
  `testNative` task in CI) — that gates `reference/native.md`.

## Step 1 — Read only the reference material this task needs

This skill ships a `reference/` directory next to this file. Read `reference/README.md` first — it is short and
routes you to the rest — then read only what it points at. The routing, restated:

| Read | When |
|---|---|
| `reference/java.md` or `reference/kotlin.md` | **Always — the language of the module being edited.** |
| `reference/cdi-and-beans.md` | **Always.** |
| `reference/testing.md` | **Always.** |
| `reference/hexagonal-architecture.md` | Step 0 found a hexagonal multi-module build. Never otherwise. |
| `reference/rest.md` | A `@Path` resource, exception mapper, filter, or REST Client. |
| `reference/persistence-panache.md` | An entity, Panache entity/repository, query, `@Transactional`, or migration. |
| `reference/reactive-mutiny.md` | `Uni`/`Multi`, reactive clients, Hibernate Reactive, `suspend` endpoints, `@RunOnVirtualThread`. |
| `reference/configuration.md` | A configuration property, `@ConfigMapping`, or profile value. |
| `reference/native.md` | A native build exists **and** the task adds reflection, resources, proxies, or serialization. |

Don't read the optional files speculatively. If `reference/` is missing (the skill directory was copied without
it), follow the repository's own `CLAUDE.md` and the surrounding code, and say so in Step 9's report.

## Step 2 — Kotlin only: check the compiler-plugin plumbing before writing code

Skip for a Java module. For a Kotlin module, follow `reference/kotlin.md` Part 1: check the module's effective
build (own build file plus parent pom `<pluginManagement>`, Gradle convention plugins, version catalog) for the
all-open and no-arg annotations this task relies on — all-open for any normal-scoped or intercepted bean and for
`@Entity`; no-arg the moment an entity has constructor parameters. If something is missing, add it now where the
project centralises build config, compile once (`-pl <module> -am test-compile -q` / `:<module>:compileTestKotlin`),
and record the change for Step 9. Anything beyond that plumbing (a test library, coroutines, `quarkus-jacoco`) is
not added here — it's a blocker to report (Step 6).

## Step 3 — Resolve where the code goes

- **Hexagonal build**: use `reference/hexagonal-architecture.md` to pick the module and confirm what it may
  import; order cross-module work domain → adapter → application → api → app module.
- **Single-module / layered**: put the code in the existing feature package beside its siblings
  (`<base>.<feature>` with its resource, service, repository/entity), following their layering. Don't create a new
  top-level layer package or module the plan didn't ask for.

If the task's named path contradicts the architecture (a Panache query in `domain`, a REST type in a use case),
that's a genuine conflict — stop and ask (Step 6) rather than quietly "correcting" it.

## Step 4 — Re-check the current code state

Read the actual current content of every file the task touches, plus one or two neighbours in the same package
or module: injection style (constructor vs. package-private field), Panache shape (active record vs. repository),
blocking vs. Mutiny vs. coroutines, RESTEasy Classic vs. Quarkus REST, `application.properties` vs. YAML, the test
style. Don't trust "current code state" notes passed with the task — sibling tasks may be landing concurrently.
Match the module in everything except the language file's mandatory rules, which the code you write follows even
where existing code doesn't (leave that existing code alone and name the violation in Step 9).

## Step 5 — Implement exactly what the task specifies

The named files, classes, methods, endpoints, properties, migrations — applying the reference files Step 1
selected and the repository's `CLAUDE.md` where more specific. Non-negotiable:

- **Every new bean has a bean-defining annotation** and an explicit scope that fits (`cdi-and-beans.md`); no
  `private` injected fields; no `final` (Java) / non-opened (Kotlin) normal-scoped or intercepted beans.
- **Threading follows the signature** (`rest.md`, `reactive-mutiny.md`): nothing blocking in a `Uni`/`Multi`/
  `suspend`/`@NonBlocking` path.
- **`@Transactional` on the calling service/resource**, never on an entity or repository; every entity change
  ships with its migration when Flyway/Liquibase owns the schema (`persistence-panache.md`).
- **Configuration through `@ConfigMapping`/`@ConfigProperty`**, with a value or default in
  `application.properties` so the app still starts; no secrets committed (`configuration.md`).
- **The language file in full** for every line written — Javadoc (Java) / KDoc (Kotlin) included.
- **No new runtime dependency or Quarkus extension** unless the task explicitly calls for it. If one seems
  unavoidable, that's a blocker (Step 6). In a Quarkus BOM-managed build, extensions never get a `<version>`.

Don't add anything the task didn't ask for — no new layer, no switch of persistence or reactive model, no
speculative `@RegisterForReflection`, no unrelated cleanup.

## Step 6 — When to interrupt the user

Keep interruptions rare. Stop and use `AskUserQuestion` (or plain text if no real choice is offered) only when:

- the task is ambiguous in a way that changes correctness or scope and can't be safely inferred;
- the task names a type, member, extension, or property that doesn't exist and isn't a trivial typo;
- implementing it as written would violate the detected architecture (hexagonal boundary) or require changing the
  module's persistence/reactive model;
- it needs a new extension, dependency, or test library (beyond Step 2's Kotlin plumbing), or a change to a
  build-time-fixed property the plan didn't mention.

Don't stop merely because the task is nontrivial. Don't report the task done to work around a blocker — report it
as blocked (Step 9) with what was tried, what failed, and why.

## Step 7 — Write tests; run only levels 1 and 2, with Dev Services off

Write the tests `reference/testing.md` calls for: plain unit tests for logic, `@QuarkusComponentTest` where CDI
behaviour matters, and — when the behaviour is HTTP contract, serialization, validation, security, or a real
query/migration — a `@QuarkusTest` (`*Test`) the group skill will run. Cover every documented `@throws`, the
boundaries, and the happy path; one test class per production class.

Then run the **plain and `@QuarkusComponentTest` classes this task added or changed**, and nothing else. Classify
them by annotation first (`grep -lE '^[[:space:]]*@Quarkus(Test|IntegrationTest|MainTest|MainIntegrationTest)([^A-Za-z0-9_]|$)'
<files>` must return none of the files you pass — anchored at line start so a Javadoc/KDoc mention doesn't count):

```bash
# Maven
./mvnw -q -pl <module> -am test -Dtest='FooTest,FooComponentTest' -Dquarkus.devservices.enabled=false -DskipITs
# Gradle
QUARKUS_DEVSERVICES_ENABLED=false ./gradlew :<module>:test --tests 'com.example.FooTest' --tests 'com.example.FooComponentTest'
```

(Use `mvn`/`gradle` if there's no wrapper; drop `-pl`/`:<module>:` for a single-module build. Add
`-Dsurefire.failIfNoSpecifiedTests=false` only when `-am` pulls in upstream modules that have no matching test.)

**Never run, by any means:** a `@QuarkusTest`/`@QuarkusMainTest` class (it boots the app, binds the shared test
port, and may start Dev Services containers — parallel siblings would collide), a `@QuarkusIntegrationTest`/`*IT`
class, `mvn verify`/`install`/`package`, Failsafe, `quarkusIntTest`, `testNative`, `quarkus:dev`/`quarkus:test`/
`quarkusDev`, a native build, or a bare unscoped suite. If a run fails with "datasource … deactivated because its
URL is not set", a Dev Services start-up error, or a Docker error, a container-dependent test slipped into the
selection — remove it from this run (it's the group's), don't enable Dev Services.

If a test fails, fix the implementation or the test and re-run until green. If behaviour can only be proven at
level 3, write that `@QuarkusTest`, leave it unrun, and say so explicitly in Step 9 — don't run it to check and
don't delete an assertion to get green.

## Step 8 — Check off this task's checkbox now

Unless the task stopped on a blocker, re-read `implementation_plan.md` fresh (siblings may be writing it
concurrently), find this task's own checkbox line by the exact text passed in, flip `[ ]` to `[x]` for it and every
sub-task the work covered, and add a short note naming the module/files and the pending validation:

```
- [x] Task 3. **Add `OrderResource.findByCustomer`** — resource + service in orders feature package, Panache
  query in OrderRepository; unit + component tests added, OrderResourceTest (@QuarkusTest) written for the group
  run; group validation pending.
```

Edit only this task's line(s). A blocked task's checkbox stays untouched.

## Step 9 — Report the outcome

Hand back a short summary:

- **Language, architecture, and module(s)/files touched** — the caller scopes its validation with this.
- **Build changes** (Kotlin all-open/no-arg additions from Step 2), with the file changed.
- **Tests**: the plain and component tests added/updated and their result; and, separately, every `@QuarkusTest`/
  `@QuarkusIntegrationTest` written but **not run** (the group's container stage), with why each needed that
  level.
- **Coverage risk**: any class whose logic is exercised only by a component test (invisible to JaCoCo without
  `quarkus-jacoco` ≥ 3.31) or only by a `@QuarkusTest`.
- **Blocker**, if any, with enough detail to surface it.
- **Deliberate deviations from `reference/`** (matching a module's field injection, active-record style, YAML
  config, RESTEasy Classic) — so the caller sees a decision, not an oversight.
- **Pre-existing violations** of the language file's mandatory rules noticed and left alone.

This skill never captures a quality baseline, adds license headers, runs coverage or code-quality checks, runs the
full suite, or runs any test that can start a container.

## Verification notes

Exercised in a throwaway directory (`$TMPDIR/iru-verify/quarkus/`) with Quarkus 3.40.1 projects generated by
`quarkus-maven-plugin:create` (Java + Maven, Kotlin + Maven, Kotlin + Gradle 9.7 KTS in a `gradle:8-jdk21`
container), each with `rest-jackson`, `hibernate-orm-panache(-kotlin)`, and `jdbc-postgresql`:

- The Maven and Gradle Kotlin all-open/no-arg snippets in `reference/kotlin.md` compile, and a Kotlin Panache
  entity with constructor parameters (`class Book(var title: String, var author: String) : PanacheEntity()`)
  persisted and loaded under `@QuarkusTest` against Dev Services PostgreSQL. A `mockkObject(Book)` companion stub
  works in a plain unit test.
- `@QuarkusComponentTest` with `io.quarkus.test.InjectMock` + `@TestConfigProperty` passes in Java and Kotlin
  (`quarkus-junit-component` + `quarkus-junit-mockito`).
- `-Dquarkus.devservices.enabled=false` reaches the Surefire JVM: a datasource-backed `@QuarkusTest` then fails
  fast ("Datasource '<default>' was deactivated automatically because its URL is not set") and no container
  starts. `QUARKUS_DEVSERVICES_ENABLED=false` with `./gradlew test --tests …` ran the plain + component tests with
  no container.
- The annotation-classification `grep -E '…([^A-Za-z0-9_]|$)'` pattern matched exactly the expected classes in
  Java and Kotlin sources (BSD/ugrep `grep`).
- Not verified: Quarkus REST `suspend fun` dispatch, reactive Panache, native builds, RESTEasy Classic projects,
  hexagonal multi-module Jandex indexing, and the Groovy-DSL plugin ids — those follow the Quarkus and Kotlin docs.
