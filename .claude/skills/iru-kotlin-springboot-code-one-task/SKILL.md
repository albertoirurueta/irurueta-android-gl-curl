---
name: iru-kotlin-springboot-code-one-task
description: "Invoked as /iru-kotlin-springboot-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one Kotlin Spring Boot (Maven or Gradle) plan task and its tests, compile-checks, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-kotlin-springboot-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(./mvnw *) Bash(./gradlew *) Bash(gradle *) Bash(javap *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *)
---

# Implement one plan task (Kotlin Spring Boot)

Carry out a single task's implementation, unit tests, and checkbox update in a Kotlin Spring Boot service —
nothing else. This is the narrowest execution unit for a `kotlin-springboot` task in the `iru-code` pipeline, the
Kotlin counterpart to `iru-java-springboot-code-one-task`.

Three things make it different from a generic "write some Kotlin" pass, and all three matter:

1. **The build is checked before any code is written.** Kotlin classes are `final` by default; Spring proxies and
   Hibernate lazy-loading both subclass. Without the `kotlin-spring`/`kotlin-jpa`/all-open plugins, a
   `@Transactional` method silently runs without a transaction and a `LAZY` association silently loads eagerly —
   nothing fails, which is exactly why Step 2 checks first.
2. **The architecture is detected, not assumed.** Hexagonal module rules apply only when the repository is a
   hexagonal reactor; a layered or feature-packaged service is followed as it is (Step 3).
3. **It runs unit tests itself, and only unit tests.** Integration tests are categorically out of scope — see
   Step 7. The calling group skill owns them, behind a repository-wide lock.

It uses a medium model on purpose: the plan carries the hard reasoning, and this skill's guidance lives in
`reference/` rather than being re-derived per task.

## Step 0 — Detect the project shape

Settle these once, from the files rather than assumptions (this skill is copied into other repositories — never
assume the catalog's own layout):

- **Build tool**: `pom.xml` → Maven (`./mvnw` when the wrapper exists, else `mvn`). `settings.gradle(.kts)` /
  `build.gradle(.kts)` → Gradle (`./gradlew` when the wrapper exists, else `gradle`); note whether build scripts
  are Kotlin DSL or Groovy, and whether versions/plugins come from `gradle/libs.versions.toml` or convention
  plugins in `buildSrc/`/`build-logic/`.
- **Modules**: Maven `<modules>`; Gradle `include(...)`. The module that owns the file the task names (or will own
  it — Step 3).
- **Languages**: `src/main/kotlin` and whether `src/main/java` also exists in that module (mixed sources change the
  Maven build requirements — `reference/build-plugins.md`).
- **Versions**: Spring Boot (parent/plugin version: 3.x vs 4.x changes the Jackson Kotlin module's group id and the
  test-slice packages) and Kotlin (Boot-managed `kotlin.version` or the Gradle Kotlin plugin version).
- **Stack**: MVC (`-webmvc`/`-web`) vs WebFlux; JPA vs R2DBC vs MongoDB; coroutines on the classpath.
- **Test conventions**: from two or three existing test classes in the target module — assertion library (JUnit
  `Assertions` vs `kotlin.test`), mocking (MockK, springmockk, or Mockito), naming (backticks vs
  `method_whenCondition_expected`), and where `*IT` classes live (same source set vs a Gradle
  `integrationTest`/`testIntegration` source set).

## Step 1 — Read only the reference material this task needs

Read `reference/README.md` first — it is short and routes you to the rest — then read only what it points at. The
routing, restated:

| Read | When |
|---|---|
| `reference/build-plugins.md` | **Always** — Step 2 depends on it. |
| `reference/hexagonal-architecture.md` | **Always** — Step 3 depends on it. |
| `reference/mandatory-rules.md` | **Always** — the non-negotiable rules for production and test code. |
| `reference/code-style.md` | **Always** — data classes, sealed results, constructor injection, configuration properties, extension/scope functions. |
| `reference/testing.md` | The task writes or changes tests (nearly always). |
| `reference/jpa-and-persistence.md` | The task touches a JPA entity, repository, query, transaction boundary, or migration. |
| `reference/coroutines-and-webflux.md` | The task touches `suspend`/`Flow`, WebFlux, R2DBC, `WebClient`, or launches a coroutine. |
| `reference/ddd-tactical-patterns.md` | The task touches domain code: entity, value object, aggregate, domain service, event, or exception. |
| `reference/solid.md` | The task introduces or changes an interface, adds a branch on type, or grows a class's responsibilities. |
| `reference/distributed-patterns.md` | The task involves a broker, a second datastore, or anything called eventually consistent, a projection, an outbox, a saga, or compensating. |

Don't read the optional files speculatively. If `reference/` is missing (the skill directory was copied without
it), proceed using the repository's own `CLAUDE.md` conventions and the surrounding code's style, still apply
Step 2's checks from what this file says, and say so in Step 9's report.

## Step 2 — Check the Kotlin/Spring build plumbing before writing code

Using `reference/build-plugins.md`, check the target module's effective build configuration (its own build file
plus whatever it inherits: root pom `<pluginManagement>`, Gradle root/convention plugins, the version catalog) for
what **this task** will rely on:

| The task will add or rely on | Required |
|---|---|
| Any Spring bean (always, in practice) | `kotlin-spring` (all-open preset), `-Xjsr305=strict`, `kotlin-reflect` |
| A JPA `@Entity`, `@MappedSuperclass`, or `@Embeddable` | `kotlin-jpa` (no-arg) **and** all-open for those three annotations |
| A Kotlin class (de)serialized as JSON (DTO, event) | `jackson-module-kotlin` (`tools.jackson.module` on Boot 4, `com.fasterxml.jackson.module` on Boot 3) |
| Mixed Java + Kotlin sources in a Maven module | Both source roots in the Kotlin executions, `maven-compiler-plugin` `default-compile`/`default-testCompile` re-bound after `kotlinc`, and no `<sourceDirectory>src/main/kotlin` override |

If something required is missing, **add it now, before writing code**, where the project centralises build
configuration (root pom / convention plugin / version catalog), with versions left to the Spring Boot BOM. Keep
the change minimal, and record it for Step 9 — it affects every module that inherits it. Anything beyond this
baseline plumbing (a new library, coroutines, springmockk, a test starter) is **not** added here: it's a blocker to
report per Step 6.

Compile the module once after a build change (Maven `-pl <module> -am -o test-compile -q`, Gradle
`:<module>:compileTestKotlin`) so a broken plugin configuration fails here rather than inside Step 7's test run.

## Step 3 — Resolve where the code goes and what it may depend on

Use `reference/hexagonal-architecture.md`:

1. **Is this a hexagonal reactor?** (Modules named `domain`/`application`/`boot` plus `infrastructure/*` and
   `api/*`, boundary enforcement via enforcer rules, ArchUnit, or inward-only Gradle module dependencies.) If yes,
   the module table and its forbidden imports apply. If not, identify the existing layered or feature packaging and
   put new code beside its siblings — never create hexagonal modules in a repository that doesn't have them.
2. **Which module/package does each piece belong in?** Use the task's named path if given; otherwise derive it from
   the placement tables.
3. **Does the task span modules?** In a hexagonal reactor follow the order: `domain` (types + port) → adapter →
   `application` → `api` → `boot` (only if new wiring is needed).

If the task's named file path contradicts the module rules (a repository query in `application`, a Spring
annotation in `domain`), that is a genuine conflict — stop and ask per Step 6 rather than quietly "correcting" it.

## Step 4 — Re-check the current code state

Read the actual current content of the file(s) the task touches before editing — other tasks in the same bucket may
be landing concurrently. Also read one or two neighbouring files in the same module/package and match their
conventions for everything except `reference/mandatory-rules.md`, which the code you write follows even where the
existing code doesn't (leave that existing code alone and name the violation in Step 9's report).

## Step 5 — Implement exactly what the task specifies

The named file(s), the described class/function/property, the behaviour, the port it implements. Apply
`reference/code-style.md` and whichever pattern files Step 1 selected, plus the repository's own `CLAUDE.md` where
it's more specific. Non-negotiable:

- **No dependency pointing the wrong way** — re-check Step 3's answer against every import you add.
- **`reference/mandatory-rules.md` in full**, production and test code.
- **KDoc** on every public/internal declaration, with `@param`/`@property`/`@return`/`@throws` — the `@throws` list
  is what Step 7's tests are written against.
- **No hand-written `open`** on beans or entities — that's the compiler plugins' job (Step 2).
- **A migration with every mapping change** when the project uses Flyway/Liquibase (`reference/jpa-and-persistence.md`).
- **No new runtime dependency** beyond Step 2's baseline plumbing unless the task explicitly calls for it, and never
  a hardcoded version where a BOM or parent manages it.

Don't add anything the task didn't ask for — no speculative abstraction, no unrelated cleanup, no coroutines on a
blocking stack, and none of `reference/distributed-patterns.md`'s patterns on your own initiative.

## Step 6 — When to interrupt the user

Keep interruptions rare. Stop and use `AskUserQuestion` (or plain text if no real choice is offered) only when:

- The task is ambiguous in a way that changes correctness or scope and can't be safely inferred.
- The task's approach is infeasible against the real code (it names a type or member that doesn't exist and isn't
  a trivial typo).
- **The task would require violating a module boundary** (hexagonal reactor) as written.
- **The task needs a dependency that isn't baseline plumbing** — a test starter, springmockk, coroutines, a client
  library — that the plan didn't call for.
- **The task appears to require a distributed pattern** (outbox, projection, saga) the plan didn't ask for.

Don't stop merely because the task is nontrivial. Don't report the task done to work around a blocker; report it as
blocked (Step 9) with what was tried and why it failed.

## Step 7 — Write and run unit tests only

Write or update tests per `reference/testing.md` and `reference/mandatory-rules.md`: plain JUnit 5 + MockK first
(`coEvery`/`runTest` for `suspend`), a slice (`@WebMvcTest`, `@WebFluxTest`, `@JsonTest`, `@DataJpaTest` on an
embedded database) only when the behaviour is the framework wiring, the module already uses that slice, and it
starts no container. Cover the edge cases implied by the KDoc `@throws` contracts.

Run the tests, scoped to what this task touched:

```bash
# Maven
mvn -pl <module> -am -o test -Dtest='<TestClass1>,<TestClass2>' -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
# Gradle
./gradlew :<module>:test --tests '<TestClass1>' --tests '<TestClass2>'
```

Drop `-o` if offline resolution fails; in a single-module Maven build omit `-pl`/`-am`; in a single-project Gradle
build use `test` without the `:<module>:` prefix. A backtick test name is selected with quotes
(`--tests 'FooTest.place saves the order'` / `-Dtest='FooTest#place saves the order'`), or just select the class.

**Never run any of these, or any variant of them:**

- `mvn verify`, `mvn install`, `mvn deploy`, a bare `mvn` with no phase, or anything invoking Failsafe.
- Gradle `check`, `build`, `integrationTest` (or `intTest`/`testIntegration`/any JVM test suite other than `test`),
  `bootRun`, or `--continuous`.
- Any `*IT` class, by any means — `-Dtest=SomethingIT` and `--tests '*IT'` included.
- Anything that starts Docker, a `ComposeContainer`, a `@Testcontainers` test, or a `@SpringBootTest` that needs
  a real database/broker.

Integration tests need Docker and minutes per run; at task level the same stack would start once per task, and
concurrent tasks would share — and corrupt — its state. The group skill runs them once per bucket under a
repository-wide lock.

**One Gradle trap to check first:** if the build has no separate integration task and `*IT` classes live in
`src/test/kotlin`, the `test` task runs them too. Then never run `./gradlew test` without a `--tests` filter that
names only `*Test` classes, and mention the missing split in Step 9's report.

If a unit test fails, fix the implementation or the test and re-run until green. Don't hand back a red test. If
some behaviour genuinely can't be verified without the real technology, write the unit tests that *are*
meaningful, leave the rest to the group's integration pass, and say so in Step 9 — don't run the integration test
to check, and don't delete the assertion to make the file pass.

## Step 8 — Check off this task's checkbox now

If this task did not stop on a blocker, update `implementation_plan.md` at the repository root before reporting
back. Re-read it fresh (parallel tasks may be editing it), locate this task's own checkbox line by the exact text
passed in, flip `[ ]` to `[x]` for it and every sub-task the work covered, and add a short note naming the
module(s) and files touched, e.g.:

```
- [x] Task 3. **Add `OrderRepository.findByCustomer`** — port in domain, JPA adapter in
  infrastructure/database/postgresql (+ V7 migration); unit tests added; group validation pending.
```

Keep the "group validation pending" wording — the group skill replaces it with the final outcome. Edit only this
task's own line(s). If this task stopped on a blocker, leave its checkbox untouched.

## Step 9 — Report the outcome

Hand back a short summary:

- **The module(s)/package(s) touched** and the files in each; whether the repository was treated as hexagonal or
  layered/feature-packaged.
- **Any build change made in Step 2** (which plugin/option/dependency, in which file, and why this task needed it).
- **The port(s) added or implemented**, and any migration added.
- **The unit tests added/updated, and their result.**
- **Anything deliberately left to the group's integration pass**, and why.
- **Whether this task stopped on a blocker**, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`** (e.g. matching a module's `method_whenCondition_expected` test
  names or kotlin-logging) so the caller sees it was a decision.
- **Pre-existing violations of `reference/mandatory-rules.md`** noticed in the files touched (AssertJ, Mockito in a
  MockK module, `!!`, `lateinit` bean dependencies, `data class` entities, platform line endings) and left alone,
  plus build findings (open-in-view on, `*IT` running in the Gradle `test` task, a missing all-open for entities in
  a module that already has them).

This skill never captures a quality baseline, adds license headers, runs coverage or code-quality checks, runs the
full suite, or runs any integration test.

## Verification notes

The build snippets in `reference/build-plugins.md` were exercised in `$TMPDIR/iru-verify/kotlin-springboot/` with a
small mixed Java + Kotlin Spring Boot 4.1.1 app (an `@Entity` with a `LAZY` `@ManyToOne`, an `@Service
@Transactional` class, a data-class request DTO, a Java class referencing a Kotlin class and vice versa):

- **Maven** (local Maven 3.9, JDK 26 runtime, `java.version` 21, Boot-managed Kotlin 2.3.21): `mvn test` green —
  MockK unit tests with `coEvery`/`runTest`, `@SpringBootTest` on H2 asserting the service is a CGLIB proxy and an
  entity reference is a `HibernateProxy`, Jackson 3 + `tools.jackson.module:jackson-module-kotlin` binding a data
  class with a default, `@DataJpaTest`, and `@WebMvcTest` + springmockk 5.0.1 `@MockkBean`. `mvn verify` also ran
  a Testcontainers 2.x `@ServiceConnection` PostgreSQL `*IT` through Failsafe (Docker available).
- **Gradle KTS** (`gradle:8-jdk21` Docker image, Gradle 8.14.6, Kotlin plugin 2.3.21, Boot plugin 4.1.1): the same
  sources and tests, `gradle clean test` green.
- **Negative controls**: declaring `kotlin-maven-plugin` before `maven-compiler-plugin` *without* re-binding
  `default-compile` fails a Java → Kotlin reference with `cannot find symbol`; a `<sourceDirectory>src/main/kotlin`
  override compiles but drops the Java sources (`NoClassDefFoundError` at test time); with only the `jpa` preset,
  entities stay `final` on Kotlin 2.2.0–2.3.10 and are opened from 2.3.20 (so the explicit all-open stays).
- Not verified: Groovy DSL Gradle builds, Spring Boot 3.x (Jackson 2 group id, Boot 3 slice packages, springmockk
  4.x), Kover, R2DBC/coroutine transactions against a live database.
