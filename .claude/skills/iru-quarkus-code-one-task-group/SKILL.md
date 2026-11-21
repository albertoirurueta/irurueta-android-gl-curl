---
name: iru-quarkus-code-one-task-group
description: "Invoked as /iru-quarkus-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Quarkus (Java or Kotlin) task bucket: one baseline, iru-quarkus-code-one-task per task (parallel when allowed), then validates once (license, docs, unit-stage tests, 80% coverage, analysis, locked container stage); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(./mvnw *) Bash(./gradlew *) Bash(gradle *) Bash(QUARKUS_DEVSERVICES_ENABLED=false *) Bash(docker *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Quarkus)

Carry out every Quarkus task in one plan-group bucket end to end: one quality baseline up front, implement each
task (in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead
of once per task.

It is built on the same language-level gate skills as the plain Java/Kotlin and Spring Boot group skills —
`iru-java-test`, `iru-java-coverage`, `iru-java-code-quality`, `iru-java-javadoc`, `iru-kotlin-code-quality`,
`iru-kotlin-kdoc`, `iru-check-license` — each invoked through an `iru-gate-runner` agent so its report never lands
in this context. Prefer those skills over a hand-written build command wherever they're installed; each falls
back to an explicit command only when the skill isn't installed in the consuming repository.

Four Quarkus facts shape the whole validation order:

1. **A test's annotation decides whether it can start a container.** Plain JUnit and `@QuarkusComponentTest`
   never do. `@QuarkusTest` boots the whole application and Quarkus **Dev Services** start a container for every
   Dev-Services-capable extension (datasource, Kafka, Redis, Keycloak, …) with no URL configured — so whether a
   `@QuarkusTest` needs Docker is a property of its module's configuration, not of the test. `@QuarkusIntegrationTest`
   runs the packaged artifact and starts the same services. Step 1 classifies every test class into a **unit
   stage** and a **container stage** once, and every later step uses that classification.
2. **The coverage gate is measured from the unit stage alone** — plain tests, component tests, and container-free
   `@QuarkusTest`s. Container-stage tests raise the aggregate (Step 3.9 reports it) but never satisfy the gate: unit
   coverage is what the bucket's tasks control, it's available whether or not Docker is, and a class covered only
   through containers has no fast feedback loop — a finding to surface, not a pass to hide behind an aggregate.
3. **The container stage is serialized repository-wide.** Dev Services containers, a Compose Dev Services stack,
   shared/reused containers, and the fixed test HTTP port (8081) are shared state; two overlapping runs corrupt each
   other's results. Only one container-stage run may be in flight at a time (Step 0, Step 3.8a).
4. **The unit stage runs with Dev Services disabled** (`-Dquarkus.devservices.enabled=false` for Maven, the
   `QUARKUS_DEVSERVICES_ENABLED=false` environment variable for Gradle, whose `-D` doesn't reach the test JVM).
   That turns any classification mistake into a fast, explicit configuration failure instead of a container
   silently started outside the lock.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one container-stage run may be in flight against this repository at any moment.** A second run started
while the first is mid-suite does not just contend for CPU and ports: with Dev Services container sharing/reuse,
a Compose Dev Services stack, or a fixed test port, it sees or breaks the other run's database rows, topics, and
sockets, so tests fail for reasons nothing in the code explains — or pass because the other run left the right
data behind. Both outcomes are indistinguishable from a real result, which is why this is a hard serialization
rule and not a performance note. It applies at three levels:

1. **Across buckets/groups**: enforced by the lock in Step 3.8a — the same `.integration-test.lock` directory every
   container-backed group skill in this catalog uses, so a Quarkus group also waits behind a Spring Boot, NestJS,
   or PHP group's integration run in the same repository.
2. **Within this bucket**: Step 2's per-task agents run unit-stage classes only, with Dev Services disabled.
3. **Within the build itself**: never add `-T`/`--threads`, Gradle `--parallel`/`maxParallelForks > 1`, or parallel
   Failsafe forks to the container-stage command.

Every test run is delegated to an `iru-gate-runner` agent that reports failing tests only — names, reasons, stack
traces — never the raw Surefire/Failsafe/Gradle output.

## Step 1 — Detect the build, classify the tests, capture one baseline

### 1a. Detect once

From the files (an `iru-explore` report earlier in the conversation is a valid shortcut):

- **Build tool**: Maven (`pom.xml`, `./mvnw` if present) or Gradle (`build.gradle(.kts)`, `./gradlew` if present).
  The **app module** applies `quarkus-maven-plugin` / `id("io.quarkus")`; in a multi-module build that's the only
  module that can run `@QuarkusTest`.
- **Languages** of the modules the bucket will touch (Kotlin: `src/main/kotlin`, `quarkus-kotlin`, the Kotlin
  plugin). A bucket may touch both.
- **Architecture**: hexagonal multi-module (sibling `domain`/`application`/`infrastructure`/`api` modules) vs.
  single-module/layered. Step 3.3 runs only for the former.
- **Coverage engine**: `quarkus-jacoco` on the test classpath (and the Quarkus version — component-test coverage
  needs ≥ 3.31), `jacoco-maven-plugin` / Gradle `jacoco`, or Kover (`org.jetbrains.kotlinx.kover`).
- **Docker**: run `docker info`. If it fails, note now that the container stage will report unverified.

Collect the module list the bucket's tasks will touch (always including the app module when any task touches
code its `@QuarkusTest`s exercise) and reuse it for every scoped command.

### 1b. Classify every test class — the rule

Run these per module (`<M>` = module directory, `.` for a single-module build). Patterns are portable ERE,
anchored to an annotation **at the start of a line** (`^[[:space:]]*@…`) so a Javadoc/KDoc or comment mention
(`* see {@link QuarkusTest}`, `// unlike @QuarkusTest`) never misclassifies a class; `([^A-Za-z0-9_]|$)` stops
`@QuarkusTest` from matching `@QuarkusTestResource`. A class annotated on the same line as other annotations
(`@QuarkusTest @TestProfile(...)`) still matches; one written after another annotation on the same line
(`@Tag("x") @QuarkusTest`) doesn't — if the counts look short, re-check with `grep -n '@Quarkus'`.

```bash
# Container stage, always: packaged-artifact tests (Maven src/test; Gradle src/native-test, src/integrationTest)
grep -rlE '^[[:space:]]*@Quarkus(Main)?IntegrationTest([^A-Za-z0-9_]|$)' <M>/src --include='*.java' --include='*.kt'
# Full-app tests: unit or container stage, decided by the module rule below
grep -rlE '^[[:space:]]*@Quarkus(Main)?Test([^A-Za-z0-9_]|$)' <M>/src/test --include='*.java' --include='*.kt'
# Unit stage, always
grep -rlE '^[[:space:]]*@QuarkusComponentTest([^A-Za-z0-9_]|$)' <M>/src/test --include='*.java' --include='*.kt'
# Explicit containers: these files (and @QuarkusTest classes using them) are container stage
grep -rlE '^[[:space:]]*@(QuarkusTestResource|WithTestResource|Testcontainers)([^A-Za-z0-9_]|$)|^import[[:space:]]+(static[[:space:]]+)?org\.testcontainers\.' <M>/src/test --include='*.java' --include='*.kt'
# Dev-Services-capable extensions in the module's build (add gradle/libs.versions.toml for a version catalog)
find <M> -maxdepth 1 \( -name pom.xml -o -name build.gradle -o -name build.gradle.kts \) -exec grep -hoE 'quarkus-(jdbc-(postgresql|mysql|mariadb|mssql|oracle|db2)|reactive-(pg|mysql|mssql|oracle|db2)-client|mongodb-(client|panache)|redis-client|redis-cache|messaging-(kafka|amqp|rabbitmq|pulsar)|kafka-(client|streams)|oidc|elasticsearch-(rest|java)-client|hibernate-search-orm-elasticsearch|infinispan-client|apicurio-registry-avro|observability-devservices-lgtm|neo4j)([^A-Za-z0-9-]|$)' {} +
# What the test profile configures for those services
find <M> \( -path '*/src/main/resources/application*' -o -path '*/src/test/resources/application*' \) -exec grep -HnE '^(%test\.)?(quarkus\.(devservices\.enabled|datasource(\.[A-Za-z"_-]+)?\.(db-kind|jdbc\.url|reactive\.url|devservices\.enabled|active))|kafka\.bootstrap\.servers|quarkus\.(mongodb\.connection-string|redis\.hosts|oidc\.auth-server-url))' {} +
```

Then classify:

| Test class | Stage |
|---|---|
| `@QuarkusIntegrationTest` / `@QuarkusMainIntegrationTest` / any `*IT` | **container** — always (it also needs a packaged build) |
| `@QuarkusComponentTest` | **unit** |
| No Quarkus annotation and no Testcontainers reference | **unit** |
| No Quarkus annotation but uses Testcontainers (`@Testcontainers`, `org.testcontainers.*`) | **container** |
| `@QuarkusTest` / `@QuarkusMainTest` (or a class extending one — grep for subclasses of each name) | **container** if *any* of: (a) the module's build has a Dev-Services-capable extension from the list above whose service is not satisfied **in-process** for the test profile (in-process = `db-kind` `h2`/`derby` for `%test`, or the datasource/extension explicitly `active=false` for `%test`); (b) the class, its `@TestProfile`, or its `@QuarkusTestResource`/`@WithTestResource` starts a Testcontainer; (c) the project uses Compose Dev Services (a compose file Quarkus is configured to start). Otherwise **unit** (container-free). |

Why a configured URL doesn't make a `@QuarkusTest` unit-stage: with a `%test` URL Dev Services stay off, but the
test now needs that external database/broker running — it's still not a self-contained, Docker-free test, so it
belongs in the container stage (and the run reports a connection failure as an environment problem). Only an
in-process substitute (H2/Derby, an inactive extension, an in-JVM mock resource such as WireMock) makes a
`@QuarkusTest` genuinely container-free.

**The guard.** Because the unit stage always runs with Dev Services disabled, a container-dependent
`@QuarkusTest` misclassified as unit fails immediately at application start ("Datasource '<default>' was
deactivated automatically because its URL is not set", "Dev Services … disabled", an unsatisfied Kafka/Redis
config) instead of starting a container outside the lock. When that happens in Step 3.4 or 3.6, move the class
to the container stage, note it for Step 5, and re-run — never re-enable Dev Services in the unit stage. The
opposite mistake (a container-free test classified as container) only costs speed.

Record the result as two lists of class names per module — `unitStage` and `containerStage` — plus whether the
container stage is empty. Re-run the classification for test classes the bucket adds after Step 2.

### 1c. Capture one pre-change quality baseline

Collect the classes every task is expected to touch, split by language, and capture one baseline per tool via
`iru-gate-runner` (issue the two agents together when both languages are present — they don't start containers):

```
Agent({
  description: "Capture pre-change quality baseline for task group (Java)",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-java-code-quality\", args: \"<JavaClass1,JavaClass2,...>\"}), then report
    back only the list of issues found for these classes. <Maven: scope with -pl <module list> -am. | Gradle:
    run only the touched modules' tasks.>",
  run_in_background: false
})
Agent({
  description: "Capture pre-change quality baseline for task group (Kotlin)",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-kotlin-code-quality\", args: \"<KotlinClass1,KotlinClass2,...>\"}), then
    report back only the issues found for these classes, per tool (detekt/ktlint) and rule id, and whether each
    tool is wired at all.",
  run_in_background: false
})
```

Record the results as this bucket's baseline (empty for new files). Skip a tool whose skill isn't installed and
say so in Step 5 — a bucket validated with no static analysis is worth knowing about.

Don't touch the integration lock here: a lock held through Step 2's implementation work would block every other
group for the whole bucket. Acquire it in Step 3.8a, immediately before the run that needs it.

## Step 2 — Implement each task via `iru-quarkus-code-one-task`

For every task, invoke `iru-quarkus-code-one-task` — it detects the language/architecture, checks the Kotlin
all-open/no-arg plumbing, implements exactly the task, writes its tests, runs its unit-stage tests with Dev
Services disabled, and checks off its own checkbox.

- **`Parallelizable: yes`**: issue all the `Agent` calls in the same response so they run concurrently:
  ```
  Agent({
    description: "Implement <task N> via iru-quarkus-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-quarkus-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: language, architecture, the modules and files
      touched, any build change (Kotlin all-open/no-arg), the plain/component tests added and their result, every
      @QuarkusTest/@QuarkusIntegrationTest written but not run, any class tested only by a component test or only
      by a @QuarkusTest, any deviation from the skill's reference/ guidance, and whether the task stopped on a
      blocker. Run plain JUnit and @QuarkusComponentTest classes only, with Dev Services disabled — never a
      @QuarkusTest, @QuarkusIntegrationTest, *IT, `mvn verify`, `quarkusIntTest`, `quarkusDev`, or anything that
      starts Docker or binds the test port: several of these agents run concurrently, and the group owns the
      container stage.",
    run_in_background: false
  })
  ```
- **`Parallelizable: no`**: invoke them one at a time in plan order.

**Ordering constraints Quarkus adds, regardless of the verdict:**

- **Build-file edits.** If two Kotlin tasks may each add all-open/no-arg configuration to the same build file (e.g.
  both add the module's first `@Entity`), run them sequentially — concurrent edits to one `pom.xml`/
  `build.gradle.kts` race. The same holds for two tasks that both add a migration to the same Flyway location
  (version-number collision) or edit the same `application.properties` block.
- **Hexagonal port and adapter.** If one task adds a port in `domain` and another implements it in an adapter
  module, the port lands first.

Note any such deviation from the plan's verdict for Step 5. As each agent reports back, re-read
`implementation_plan.md` and confirm its box is checked; if a concurrent-write race lost it, flip it yourself with
the same "group validation pending" note, and notify the user that this task's implementation and unit-stage tests
landed. A task that stopped on a blocker is recorded and its code excluded from Step 3.

After Step 2, **re-run Step 1b** for the test classes the bucket added, so `unitStage`/`containerStage` include
them. If any task changed the build (Kotlin plugin config, a new extension the plan asked for), also re-run the
Dev-Services extension probe — a new datasource extension moves every `@QuarkusTest` in that module to the
container stage.

## Step 3 — Validate the whole bucket once

Run these once for the entire bucket, in order, cheapest first. **Steps 3.1–3.7 are the gates; 3.8 and 3.9 add
information on top of them** — a bucket can be fully gated on a machine with no Docker at all.

Notation: `<unit selector>` is the bucket-affected `unitStage` classes (Maven `-Dtest='A,B,C'`; Gradle one
`--tests '<fqcn>'` per class, attached to each owning module's `test` task). `<dev-services-off>` is
`-Dquarkus.devservices.enabled=false` appended to a Maven command, or `QUARKUS_DEVSERVICES_ENABLED=false`
prefixed to a Gradle command.

1. **License headers**, for every file the bucket added or modified, via `iru-gate-runner`:
   `Agent({description: "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks
   added or modified (sources, tests, migrations, build files touched). Report back only which files were missing
   a header vs. fixed vs. already compliant, and — if no header convention existed anywhere in the repo — whether
   the user chose to skip or generate one."})`. Respect a skip choice for the rest of the run. Skip if not
   installed.

2. **Doc comments**, per language, via `iru-gate-runner` (both agents in one response when both apply):
   - Java: `Invoke Skill({skill: \"iru-java-javadoc\", args: \"<JavaClass1,...>\"})` — scoped with
     `-pl <module list> -am` on Maven; report whether Javadoc was added/updated, for which members, and whether
     the Javadoc build passed.
   - Kotlin: `Invoke Skill({skill: \"iru-kotlin-kdoc\", args: \"<KotlinClass1,...>\"})` — report whether KDoc was
     added/updated, any `module.md` created, and whether the Dokka build passed (or that Dokka isn't wired).

   If a build failed, fix and re-invoke until it passes. Skip a skill that isn't installed and say so. The per-task
   skill already requires full Javadoc/KDoc, so this should mostly confirm — a lot of backfill here means the
   per-task skill was bypassed.

3. **Module boundaries — hexagonal builds only.** Skip with a one-line note for a single-module/layered service.
   For a hexagonal build, via `iru-gate-runner`:
   ```
   Agent({
     description: "Verify hexagonal module boundaries",
     subagent_type: "iru-gate-runner",
     prompt: "Maven: run `./mvnw -q -B validate` (or mvn) at the root to execute any maven-enforcer-plugin
       bannedDependencies rules. Then locate the architecture test (grep for com.tngtech.archunit or
       com.lemonappdev.konsist under src/test; typically *ArchitectureTest) and run just that class with Dev
       Services disabled: Maven `-pl <its module> -am test -Dtest=<Class> -Dquarkus.devservices.enabled=false
       -Dsurefire.failIfNoSpecifiedTests=false`; Gradle `QUARKUS_DEVSERVICES_ENABLED=false ./gradlew
       :<its module>:test --tests '<fqcn>'`. Report back only: whether the enforcer rules passed (or don't exist),
       whether the architecture test passed (or doesn't exist), and for any failure the exact rule violated and
       the offending class and import.",
     run_in_background: false
   })
   ```
   Any violation is a task putting code in the wrong module or importing outward — **fix the code, not the rule**;
   relax a rule only if it is genuinely wrong for this project, and record that for Step 5. If neither check
   exists, note it and continue; don't create them here.

4. **Scoped unit-stage tests**, via `iru-gate-runner`: `Agent({description: "Run unit-stage tests for task
   group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\", args: \"<unit
   selector>\"}), telling it: <Maven: scope with -pl <module list> -am, append -Dquarkus.devservices.enabled=false
   -DskipITs -Dsurefire.failIfNoSpecifiedTests=false | Gradle: prefix the command with
   QUARKUS_DEVSERVICES_ENABLED=false and put each --tests right after its module's :test task>. These classes are
   plain JUnit, @QuarkusComponentTest, and container-free @QuarkusTest classes only — do not run verify, Failsafe,
   quarkusIntTest, or any other class. If the skill isn't installed, run the equivalent command directly. If
   everything passes, report back only that. If anything fails, report the failing test names, the failure reason,
   and the stack trace for each — and flag separately any failure at Quarkus application start-up caused by a
   missing/deactivated datasource, a disabled Dev Service, or a Docker error."})`. A start-up failure of that kind
   is Step 1b's guard firing: reclassify the class to the container stage and re-run. Any other failure: fix the
   owning task's code and re-run until green.

5. **Unit-stage coverage — the 80% gate.** Via `iru-gate-runner`:
   ```
   Agent({
     description: "Check unit-stage coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-java-coverage\", args: \"<unit selector> <TargetClass1,TargetClass2,...>\"})
       for every target class this bucket changed or added. The measurement must come from the UNIT STAGE only:
       exactly these test classes, Dev Services disabled (<dev-services-off>), no verify, no Failsafe, no
       quarkusIntTest, nothing that starts Docker. <Maven: scope with -pl <module list> -am.> Quarkus specifics:
       (1) Classes exercised by @QuarkusComponentTest (and, in the same run, by any container-free @QuarkusTest)
       are only visible to JaCoCo through io.quarkus:quarkus-jacoco (Quarkus >= 3.31). Keep its data in its OWN
       file and merge — never point it at the JaCoCo agent's exec file: two writers on one file in one JVM lose data
       (a component-tested class drops to 0% as soon as a @QuarkusTest shares the run). If the build already wires
       this (a surefire/test-task `jacoco-agent.destfile` of <build dir>/jacoco-quarkus.exec plus a jacoco `merge`
       execution or a report reading both files), use it: Maven `test jacoco:merge@<merge execution id>
       jacoco:report@<report execution id>`, Gradle `test jacocoTestReport`. Otherwise, ad hoc: Maven — run
       `jacoco:prepare-agent test` with -Djacoco.exclClassLoaders='*QuarkusClassLoader'
       -Djacoco-agent.destfile=target/jacoco-quarkus.exec -Dquarkus.jacoco.report=false, merge the two files with
       the JaCoCo CLI (`mvn dependency:copy -Dartifact=org.jacoco:org.jacoco.cli:<jacoco version>:jar:nodeps
       -DoutputDirectory=target/jacoco-cli`, then `java -jar target/jacoco-cli/org.jacoco.cli-<v>-nodeps.jar merge
       target/jacoco.exec target/jacoco-quarkus.exec --destfile target/jacoco-merged.exec`), then
       `jacoco:report -Djacoco.dataFile=target/jacoco-merged.exec`. Gradle — add to the throwaway init script, per
       project: `tasks.withType<Test>().configureEach { val q = project.layout.buildDirectory
       .file(\"jacoco-quarkus.exec\").get().asFile; jvmArgumentProviders.add(CommandLineArgumentProvider {
       listOf(\"-Djacoco-agent.destfile=\" + q.absolutePath) }); doFirst { q.delete() };
       extensions.findByType(JacocoTaskExtension::class.java)?.excludeClassLoaders = listOf(\"*QuarkusClassLoader\") }`
       and `tasks.withType<JacocoReport>().configureEach { executionData(project.fileTree(project.layout
       .buildDirectory) { include(\"jacoco/test.exec\", \"jacoco-quarkus.exec\") }) }` — a JVM argument, because
       the Quarkus Gradle plugin resets the Test task's systemProperty() values. Add QUARKUS_JACOCO_REPORT=false to
       the environment so quarkus-jacoco doesn't write (and wait for) its own second report. (2) Report from the
       merged data only; quarkus-jacoco's own target/jacoco-report/ (if the project enables it) covers the same
       classes and is not added on top.
       (3) Kover: report its figures and say whether component-test classes show any coverage. Report back, per
       target class, line and branch coverage with the report each came from, confirm the run was unit-stage only,
       and list any target class at 0% that a @QuarkusComponentTest in the selection exercises (coverage invisible
       rather than absent).",
     run_in_background: false
   })
   ```
   For any class under 80%, add unit-stage tests (plain or component) for the uncovered branches **via the task
   that owns that class**, then re-run 3.4 and re-check here until it clears.

   **Component-test coverage that JaCoCo can't see** (no `quarkus-jacoco`, or Quarkus < 3.31): a class tested only
   by a `@QuarkusComponentTest` reads 0%. Don't fail the bucket on an artefact and don't wave it through — either
   add plain unit tests for its core branches (usually possible with constructor injection), or record it in Step 5
   as **unmeasured** with the component test named, and recommend adding `io.quarkus:quarkus-jacoco` (test scope) as
   a follow-up. Never add the extension here unasked — it's a build change other modules inherit.

   **A container-stage test never closes a gap.** If the argument for accepting a class under 80% is that a
   Dev-Services-backed `@QuarkusTest` exercises it, that is exactly what this gate exists to surface: the logic has
   no fast feedback loop. Unit-test it (often by moving logic out of the resource/adapter into a plain class), or
   record an accepted exception with that reasoning. Two genuine exception classes, named in Step 5 rather than
   waved through: thin wiring with no branching logic (a resource that only delegates, a producer method), and
   generated code (which should be excluded from JaCoCo by configuration — appearing here is a build-config
   finding).

6. **Full unit-stage suite**, reactor-/project-wide, via `iru-gate-runner`: `Agent({description: "Run full
   unit-stage suite", subagent_type: "iru-gate-runner", prompt: "Run every unit-stage test class in the
   repository with Dev Services disabled. Maven: `./mvnw -q -B test -Dtest='!*IT,!<each containerStage class>'
   -Dquarkus.devservices.enabled=false -DskipITs -Dsurefire.failIfNoSpecifiedTests=false` (the '!*IT' entry is
   required — a -Dtest exclusion list replaces Surefire's default excludes). Gradle:
   `QUARKUS_DEVSERVICES_ENABLED=false ./gradlew test <one --tests '<fqcn>' per unitStage class, after each module's
   :test task> --continue`. Use iru-java-test if installed, passing these selectors. Report back only that
   everything passed, or the failing test names, reasons, and stack traces, flagging start-up failures caused by
   a missing datasource / disabled Dev Service / Docker separately."})`. Fix any regression and re-invoke until
   green; reclassify on a guard failure as in 3.4.

7. **Code quality**, diffed against Step 1c's baseline, via `iru-gate-runner` — one agent per language present
   (both in one response): `iru-java-code-quality` for the Java classes (Checkstyle/PMD/SpotBugs) and
   `iru-kotlin-code-quality` for the Kotlin classes (detekt/ktlint), each prompted with "Compare the reported
   issues against this pre-change baseline: <baseline>. Report back only issues newly appearing, each with its
   tool, rule id, file, and line. Ignore findings in generated sources (target/generated-sources,
   build/generated, Quarkus-generated classes) — report them separately as an exclusion-config finding."
   Any new issue is a defect this bucket introduced: trace it to its task, fix it, re-run 3.4 and 3.6, and re-check.
   Treat SpotBugs findings and detekt `potential-bugs` rules as real defects. Leave an issue only if fixing it would
   contradict what its task explicitly specifies; record that and why. A tool that reports "not wired" is noted
   in Step 5, not counted as a pass.

8a. **Acquire the repository-wide integration-test lock**, immediately before 3.8 — never earlier.

   Skip 3.8a–3.8b entirely (no lock, no run) when:

   - **Docker is unavailable** (Step 1a): report the container stage as **unverified (Docker unavailable)**, skip
     3.9, continue to Step 4. A container stage that never ran is a known gap; a silently skipped one reads as a
     pass.
   - **The container stage is empty** (no container `@QuarkusTest`, no `*IT`, no Testcontainers test): say so in
     Step 5 rather than reporting a stage that never existed.

   The lock is a directory at the repository root, because `mkdir` either creates it or fails — atomically:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     task numbers/titles, the module list, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is
     listed in the root `.gitignore`, adding the line if it's missing.
   - **`HELD`** — another group (of any stack) is running container-backed tests. Tell the user this bucket is
     waiting (naming the holder from `cat .integration-test.lock/owner` if readable) and poll the `mkdir` roughly
     every 30 seconds. Don't run anything container-backed in the meantime — not even "just one" `@QuarkusTest`.

   Two bounded escapes:

   - **Stale lock**: if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Tell the user what the owner file says, break it (`rm -f .integration-test.lock/owner &&
     rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout**: still held after ~45 minutes and not stale → stop waiting, report the container stage as
     **unverified — blocked by a concurrent integration run**, skip 3.9, continue to Step 4.

8. **Container stage** — the only place in the pipeline where Dev Services or Testcontainers start. Only now, with
   everything above clean and the lock held:
   ```
   Agent({
     description: "Run container-stage tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Run exactly one command. Maven: `./mvnw -B -pl <module list incl. app module> -am verify
       -DskipITs=false -Dtest='<containerStage @QuarkusTest/Testcontainers classes, comma-separated>'
       -Dsurefire.failIfNoSpecifiedTests=false` — Surefire runs the container @QuarkusTest classes, Failsafe runs
       the @QuarkusIntegrationTest *IT classes against the packaged jar (Quarkus projects default skipITs=true,
       hence -DskipITs=false). If the container stage has no @QuarkusTest classes, use -Dtest=NONE with the same
       failIfNoSpecifiedTests flag. Gradle: `./gradlew <:app:>test <one --tests per containerStage @QuarkusTest
       class> <:app:>quarkusIntTest --continue` (omit the test part if there are none; omit quarkusIntTest if there
       are no *IT classes; use a custom integrationTest task instead if the build defines one). Do NOT add
       -T/--threads, --parallel, or parallel forks, and do NOT run any other test command alongside it: the
       Dev Services containers and the test HTTP port are shared state. This starts real containers and packages
       the app — expect several minutes; wait for it. Report back only: whether all tests passed; for any failure,
       the test name, reason, and stack trace; and separately, whether a failure was environmental (Docker
       unavailable, image pull failure, port 8081 already in use, container start-up timeout, out of memory, an
       external service configured by URL that isn't running) rather than an assertion failure. Do not dump build
       output.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another build-running agent. `run_in_background:
   false` is mandatory: the lock only means something if it's held exactly as long as containers are up.

   - **Environmental failure**: report unverified with the reason; don't spend the bucket debugging a container that
     won't pull. Worth checking first, since it's common and self-inflicted: a developer's `quarkus dev` session
     holding port 8081/8080 or a Dev Services container (`docker ps`). Report it — **never** stop a container or
     process this skill didn't start.
   - **Assertion failure**: trace it to the owning task, fix, re-run. Keep the lock for a quick fix; for a long
     debugging session release it (3.8b), fix, re-acquire (3.8a) before re-running. If the fix changed production
     code, re-run 3.4–3.7 afterwards. A failure here that unit-stage tests missed is usually a real mapping,
     query, serialization, or wiring defect — report it even once fixed.
   - **Dev Services container reuse** (`quarkus.datasource.devservices.reuse=true`, `testcontainers.reuse.enable`)
     makes concurrent runs strictly worse — reused containers are shared by design. It changes nothing about the
     lock rule, it only raises the cost of breaking it.

8b. **Release the lock** as soon as 3.8's agent has reported and any fix-and-re-run cycle is done:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release on **every** exit path — passed, failed, environmental failure, a blocked task, user interruption, a
   hard stop. A lock left behind blocks every later group (of every stack) for an hour.

9. **Aggregate coverage — reported, never gated.** 3.8's run produced execution data from both engines (the
   agent's exec file and `quarkus-jacoco`'s, kept separate as in 3.5). Via `iru-gate-runner`, build one report from
   their merge exactly as 3.5 does — the project's own merge/report executions (Maven `jacoco:merge@<id>
   jacoco:report@<id>`, Gradle `jacocoTestReport`) or the JaCoCo CLI merge — **without re-running any test**, and
   report line/branch per target class (or say no data exists and why). If the project only has `quarkus-jacoco`'s
   own report (`target/jacoco-report/jacoco.xml` / `build/jacoco-report/jacoco.xml`), read that instead. Note each
   class's delta against its 3.5 figure: a small delta means the container tests check wiring; a large one (82%
   unit vs. 97% aggregate) means much of the class's behaviour is only exercised with containers running — worth
   one line in Step 5. Never treat it as the gate, never re-run 3.5 against it, never re-run `verify` here to
   refresh it (that needs 3.8a's lock). Skip with a one-line note when 3.8 was unverified or skipped — **the
   bucket's coverage verdict doesn't change**.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes (or leaves only explicitly accepted issues), replace each task's "group validation pending"
note in `implementation_plan.md` with the final outcome, quoting the **unit-stage** coverage as the result and any
aggregate separately:

```
- [x] Task 3. **Add `OrderResource.findByCustomer`** — orders feature package (resource, service, repository);
  unit + component tests (86% line, unit stage); OrderResourceTest (@QuarkusTest, container stage) passed,
  93% aggregate (quarkus-jacoco); no new Checkstyle/PMD/SpotBugs issues.
```

If the container stage was unverified, say so with the reason — `container stage unverified (Docker unavailable)`
or `(lock held by another group)` — rather than implying it passed. Flag unmeasured component-test coverage the
same way. Then notify the user that group validation completed, summarizing the bucket-wide result.

Before writing these notes, confirm the integration lock is released (3.8b); if a failure path skipped the release,
release it now. A task recorded as blocked in Step 2 keeps its checkbox unchecked with its blocker note.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **Per task**: language, modules and files touched, build changes (Kotlin all-open/no-arg), tests added, unit-stage
  coverage (aggregate separately where 3.9 produced one), and code-quality outcome.
- **The test classification** (Step 1b): how many classes landed in each stage, which `@QuarkusTest` classes were
  container-free and why, and any reclassification the guard forced (a class that tried to reach a disabled Dev
  Service).
- **The module-boundary result** (3.3) for hexagonal builds, and any rule deliberately relaxed with the reasoning —
  the outcome most worth escalating.
- **Static analysis** (3.7): new findings introduced/fixed per tool, or which tools aren't installed/wired.
- **Coverage**: classes that cleared the gate narrowly, accepted exceptions with reasoning, **unmeasured**
  component-test-only classes (and the `quarkus-jacoco` recommendation), and any class whose aggregate far
  exceeds its unit-stage figure.
- **The container stage** (3.8): passed, failed-and-fixed, or **unverified** with the reason — never let
  "unverified" read as "passed" — plus any failure unit-stage tests missed, even once fixed.
- **How the lock behaved**, whenever it wasn't an immediate uncontended acquire: wait time, a broken stale lock
  (with its owner text), a timeout, and confirmation of release.
- Any quality issue left in place and why; whether license-header generation was skipped; generated-source
  exclusion problems; ordering deviations applied in Step 2; and blocked tasks with enough detail to surface them.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.

## Verification notes

Exercised in `$TMPDIR/iru-verify/quarkus/` against Quarkus 3.40.1 projects generated with
`quarkus-maven-plugin:create` (Java/Maven, Kotlin/Maven, Kotlin/Gradle 9.7 KTS in a `gradle:8-jdk21` container),
each with `jdbc-postgresql` + Panache, a plain unit test, a `@QuarkusComponentTest`, and `@QuarkusTest`s:

- Step 1b's grep/find commands classified the Java and Kotlin classes as expected (including Gradle's
  `src/native-test` `*IT`) and the extension probe found `quarkus-jdbc-postgresql` in both `pom.xml` and
  `build.gradle.kts`.
- With Docker running, the `@QuarkusTest` started a Dev Services `postgres` container; with
  `-Dquarkus.devservices.enabled=false` the same test failed at start-up with "Datasource '<default>' was
  deactivated automatically because its URL is not set" and no container started (the guard). Plain + component
  tests passed under the same flag (Maven) and `QUARKUS_DEVSERVICES_ENABLED=false` (Gradle).
- `-Dtest='!X'` alone made Surefire also run `*IT` classes; `-Dtest='!*IT,!X'` ran only the unit stage — hence the
  explicit `!*IT` in 3.6.
- The 3.8 Maven command (`verify -DskipITs=false -Dtest=<container classes>
  -Dsurefire.failIfNoSpecifiedTests=false`) ran the two container `@QuarkusTest`s via Surefire and the
  `@QuarkusIntegrationTest` via Failsafe, then wrote `target/jacoco-report/`. Gradle exposes `quarkusIntTest` and
  `testNative`.
- Coverage: the plain JaCoCo agent reported **0%** for a class exercised only by `@QuarkusComponentTest`, with or
  without `quarkus-jacoco`. With `quarkus-jacoco` present and no `@QuarkusTest` in the run, its data went to
  `<module>/jacoco.exec`. Pointing `jacoco-agent.destfile` at the agent's own `target/jacoco.exec` looked fine for a
  pure unit-stage run but, with a `@QuarkusTest` sharing the run, dropped the component-tested class to its plain-test
  figure and the resource to 0% (Maven, reproduced). The separate-file pattern — `jacoco-quarkus.exec` +
  `exclClassLoaders=*QuarkusClassLoader` + merge — gave 100% for both, with and without a `@QuarkusTest`, on Maven
  (JaCoCo CLI merge, then `jacoco:report -Djacoco.dataFile=…`) and Gradle (init script; `systemProperty()` from an
  init script was silently ignored, the `jvmArgumentProviders` form works).
- Classification greps are anchored at line start: a test class whose Javadoc mentions `@QuarkusTest`,
  `@QuarkusIntegrationTest`, and `@QuarkusComponentTest` matched the unanchored pattern but none of the anchored
  ones.
- Not verified: the Gradle `quarkusIntTest` container stage, the `jacoco:merge@<id>` form against a
  setup-generated build (that configuration was verified by `iru-setup-quarkus-service`), Kover with component tests,
  Compose Dev Services, `@QuarkusMainTest`, hexagonal multi-module builds with enforcer/ArchUnit, and Quarkus
  versions before 3.31 beyond the documented absence of component-test coverage support.
