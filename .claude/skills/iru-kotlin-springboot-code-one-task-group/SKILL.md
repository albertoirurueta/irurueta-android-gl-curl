---
name: iru-kotlin-springboot-code-one-task-group
description: "Invoked as /iru-kotlin-springboot-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Kotlin Spring Boot (Maven or Gradle) task bucket: one baseline, iru-kotlin-springboot-code-one-task per task (parallel when allowed), then validates once (license, KDoc, tests, 80% coverage, detekt/ktlint, locked Testcontainers); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(./mvnw *) Bash(./gradlew *) Bash(gradle *) Bash(docker *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Kotlin Spring Boot)

Carry out every Kotlin Spring Boot task in one plan-group bucket end to end: one quality baseline up front,
implement each task (in parallel where safe), then validate everything the bucket touched in a single consolidated
pass instead of once per task.

It is the Kotlin counterpart to `iru-java-springboot-code-one-task-group` and is built on the same language-level
gate skills, each invoked through an `iru-gate-runner` agent so its report never lands in this context:
`iru-java-test` (Surefire or the Gradle `test` task, Kotlin sources included), `iru-java-coverage` (JaCoCo, or
Kover where applied), `iru-kotlin-code-quality` (detekt + ktlint), `iru-kotlin-kdoc` (KDoc + Dokka), and — for Java
files in a mixed module — `iru-java-code-quality` and `iru-java-javadoc`. Prefer those skills over hand-written
`mvn`/`gradle` commands wherever they're installed; each falls back to an explicit command only when the skill
isn't installed in the consuming repository.

Four principles shape the validation order, the same as the Java group skill:

1. **This skill owns integration testing — and its serialization.** The per-task skill runs unit tests only; the
   container-backed pass happens here, once per bucket, and only one such pass may be in flight repository-wide
   at a time (Step 0 and Step 3.8a).
2. **Cheap checks first, containers last.** A boundary violation, a detekt finding, or a red unit test is found in
   seconds; discovering it after a multi-container run wastes minutes for no extra information.
3. **The coverage gate is measured from unit tests alone.** Integration tests raise the aggregate (Step 3.9
   reports it) but never satisfy the gate: unit coverage is what the bucket controls, it's available with or
   without Docker, and a class covered only through containers has no fast feedback loop — a finding, not a pass.
4. **Everything is module-scoped.** Maven `-pl <module1>,<module2> -am`; Gradle `:<module>:<task>` task paths
   (Gradle builds the projects a task depends on automatically, so there is no `-am` to add). Single-module
   builds drop the scoping.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one integration-test run may be in flight against this repository at any moment.** Testcontainers stacks
(a shared `compose.yaml` started through `ComposeContainer`, or shared static containers with Testcontainers
reuse) hold state two concurrent runs would share: they see each other's rows, topics, and indices, so tests fail
for reasons nothing in the code explains — or pass because the other run left the right data behind. Both are
indistinguishable from a real result, so this is a hard serialization rule, not a performance note.

It applies at three levels:

1. **Across buckets/groups** — enforced by the lock in Step 3.8a, regardless of what the caller does.
2. **Within this bucket** — Step 2's per-task agents never run integration tests (the per-task skill forbids it),
   so Step 3.8's single invocation is the only container-backed run.
3. **Within the build itself** — never add `-T`/`--threads` (Maven) or `--parallel` (Gradle) to the integration
   command; pass `--no-parallel` to Gradle when `gradle.properties` sets `org.gradle.parallel=true`; and never
   enable parallel Failsafe forks or JUnit parallel execution for this run. If the build itself configures
   `maxParallelForks > 1` on the integration task, report it as a risk in Step 5.

Every test run — unit and integration — is delegated to an `iru-gate-runner` agent that reports failing tests
only (names, reasons, stack traces; nothing on a green run).

## Step 1 — Detect the build once, then capture one quality baseline

**Detect, once, for the whole bucket** (record the answers — every later step reuses them):

- **Build tool**: `pom.xml` → Maven (`./mvnw` if present); `settings.gradle(.kts)`/`build.gradle(.kts)` →
  Gradle (`./gradlew` if present). Kotlin DSL or Groovy.
- **Module list**: the modules the bucket's tasks touch, from the tasks' file paths. Maven form
  `-pl a,b -am`; Gradle form `:a:<task> :b:<task>` (root project: `<task>` with no prefix).
- **Architecture**: hexagonal reactor or layered/feature service — same criteria as
  `iru-kotlin-springboot-code-one-task/reference/hexagonal-architecture.md` (modules `domain`/`application`/`boot`
  plus `infrastructure/*`/`api/*`, boundary enforcement present).
- **Java sources in the touched modules**: `find <module>/src -name '*.java' -not -path '*/build/*' -not -path
  '*/target/*' | head -1`. If any, the Java gate skills run alongside the Kotlin ones.
- **Integration-test wiring** (Step 3.8 needs it):
  - Maven: `maven-failsafe-plugin` declared (or inherited — `spring-boot-starter-parent` only manages it; it runs
    only when a pom lists it in `<plugins>`). `*IT` classes: `find . -name '*IT.kt' -o -name '*IT.java' | grep -v
    -e /target/ -e /build/`.
  - Gradle: list test-type tasks with
    `./gradlew -q :<module>:tasks --all | grep -iE '^(integrationTest|intTest|testIntegration|e2eTest)\b'`, and look for `testing { suites { register<JvmTestSuite>(...) } }`,
    `tasks.register<Test>(...)`, or a `src/integrationTest`/`src/testIntegration` source set in the build files.
    Then check whether the plain `test` task excludes `*IT` (`filter { excludeTestsMatching("*IT") }`,
    `exclude("**/*IT*")`). **If `*IT` classes exist and no separate task or exclusion does, the `test` task itself
    starts containers** — record that: Step 3.6 then has to run under the lock (see there), and Step 5 reports the
    missing split as a build-configuration finding.
- **Coverage tool**: JaCoCo (Maven plugin, or Gradle `jacoco`) or Kover (`org.jetbrains.kotlinx.kover`). For Kover,
  check whether its report would pull in the integration task: `./gradlew :<module>:koverXmlReport --dry-run |
  grep -iE 'integrationTest|intTest|testIntegration'`. If it does, Step 3.5 must exclude that task with `-x
  <task>`.
- **Docker**: `docker info` succeeds or not. If not, the integration stage will be reported unverified.

**Capture the baseline** for the classes the bucket's tasks are expected to touch:

```
Agent({
  description: "Capture pre-change Kotlin quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-kotlin-code-quality\", args: \"<ClassName1,ClassName2,... or file paths>\"}),
    then report back only the list of detekt/ktlint issues found for these files (tool, rule id, file, line).
    Build: <Maven|Gradle>; scope the run to these modules: <Maven -pl list | Gradle :module paths>.",
  run_in_background: false
})
```

If the touched modules also hold Java sources, capture a second baseline the same way with `iru-java-code-quality`
(Checkstyle/PMD/SpotBugs) — in its own agent, issued in the same response so both run concurrently. Record the
returned issues as this bucket's baselines (empty when every file is new); Step 3.7 diffs against them. Skip a
baseline whose skill isn't installed, and say so in Step 5 — a bucket validated with no static analysis is worth
knowing about.

Don't touch the integration lock here — acquiring it now would block every other group for the whole
implementation phase. It's taken in Step 3.8a, immediately before the run that needs it.

## Step 2 — Implement each task via `iru-kotlin-springboot-code-one-task`

For every task in the bucket, invoke `iru-kotlin-springboot-code-one-task` — it checks the Kotlin/Spring build
plumbing, resolves where the code goes, implements exactly what the task specifies, writes and runs its own
**unit** tests, and checks off its own checkbox.

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-kotlin-springboot-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-kotlin-springboot-code-one-task\", args: \"<the task's full text, including
      its exact implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and
      any relevant Current code state context>\"}). Report back: the modules and files touched, any build change
      it made (compiler plugins, dependencies), the ports added or implemented, migrations added, the unit tests
      added/updated and their result, anything deliberately left to group integration validation, any deviation
      from the skill's reference/ guidance, and whether the task stopped on a blocker. Unit tests only — do not
      run `mvn verify`, Failsafe, Gradle check/build/integrationTest, any *IT class, or anything that starts
      Docker or a container: several of these agents run concurrently and share one container stack.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**Two ordering constraints this stack imposes, regardless of the parallelizable verdict:**

1. **Port and adapter.** If one task adds an interface (in `domain`, or a service-layer interface in a layered
   service) and another implements it, the interface lands first.
2. **Shared build files.** If more than one task in the bucket will need a build change (the first `@Entity` in a
   module adding `kotlin-jpa`/all-open, a dependency the plan names), two agents editing the same root pom,
   convention plugin, or `libs.versions.toml` concurrently will clobber each other. Run the first such task alone,
   then fan out the rest.

When either applies to a parallelizable bucket, run the constrained tasks sequentially while the rest still run in
parallel, and note the deviation in Step 5.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if a concurrent-write
race lost it, flip it yourself with the same "group validation pending" note. Notify the user that this task's
implementation and unit tests landed. If a task reports a blocker, record it and exclude its code from Step 3 —
surface it in Step 5 without blocking the rest of the bucket. Collect every build change the agents report: Step
3's commands must still work after them, and Step 5 lists them.

## Step 3 — Validate the whole bucket once

Run the following once for the entire bucket, in this order — cheapest first. **Steps 3.1–3.7 are the gates; 3.8
and 3.9 add information on top of them.** A bucket can be fully validated on a machine with no Docker; only the
integration result and the aggregate figure are lost.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified (Kotlin, Java, build files,
   migrations). Report back only which files were missing a header vs. fixed vs. already compliant, and — if no
   header convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a
   skip choice for the rest of the run. Skip if `iru-check-license` isn't installed.

2. **KDoc**, via `iru-gate-runner`: `Agent({description: "Update KDoc for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-kotlin-kdoc\", args: \"<ClassName1,ClassName2,... or
   file paths>\"}) scoped to every Kotlin file this bucket added or modified. Build: <Maven|Gradle>, modules:
   <module list>. Report back only whether KDoc was added/updated and for which declarations, whether any Dokka
   module/package include was created, and whether the Dokka build verification passed."})`. If touched Java files
   exist, run `iru-java-javadoc` for them in a second agent in the same response. If a doc build failed, fix and
   re-invoke until it passes. Skip a skill that isn't installed. The per-task skill already requires KDoc, so this
   should mostly confirm — a lot of backfilling here means the per-task skill was bypassed.

3. **Structural checks — only for a hexagonal reactor, and only what exists.** For a layered/feature service, skip
   this item with a one-line note. Otherwise, via `iru-gate-runner`:
   ```
   Agent({
     description: "Verify hexagonal module boundaries",
     subagent_type: "iru-gate-runner",
     prompt: "Locate the architecture checks this repository actually has, and run only those:
       (a) Maven maven-enforcer-plugin bannedDependencies rules — `mvn -q -B validate` at the reactor root;
       (b) an ArchUnit test (grep the test sources for `com.tngtech.archunit`, typically a *ArchitectureTest under
       the boot module; ArchUnit inspects compiled bytecode, so it checks Kotlin classes too) — Maven
       `mvn -q -B -pl <module> -am test -Dtest=<ArchTestClass> -Dsurefire.failIfNoSpecifiedTests=false -DskipITs`,
       Gradle `./gradlew :<module>:test --tests '<ArchTestClass>'`;
       (c) any Gradle module-dependency check task the build defines (grep the build files for a task that
       verifies project dependencies; there is no enforcer equivalent in Gradle by default — in Gradle the module
       graph in each build.gradle(.kts) is itself the boundary, so also report any `project(\":...\")` dependency
       in the touched modules that points outward per the domain/application/infrastructure/api/boot rules).
       Report back only: which checks exist, whether each passed, and for any failure the exact rule/assertion
       violated and the offending class, import, or project dependency.",
     run_in_background: false
   })
   ```
   Any violation means a task put code in the wrong module or imported outward. **Fix the code, not the rule** —
   move the class, or introduce the port the adapter should have implemented — then re-run. Relax a rule only if
   it's genuinely wrong for this project, and record that for Step 5. If no checks exist, note it and continue —
   creating them is the setup skills' job.

4. **Scoped unit tests**, via `iru-gate-runner`: `Agent({description: "Run unit tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\", args: \"<selector covering
   every *Test class affected by this bucket>\"}). Build: <Maven|Gradle>; scope to modules <Maven -pl list -am |
   Gradle :module:test task paths>. Unit tests only: Maven Surefire with -DskipITs, never verify/Failsafe; Gradle
   the `test` task only, never check/build/integrationTest, and select only *Test classes (never *IT). If that
   skill isn't installed, fall back to `mvn -q -B -pl <modules> -am test -Dtest='<selector>'
   -Dsurefire.failIfNoSpecifiedTests=false -DskipITs` or `./gradlew <:module:test ...> --tests '<pattern>'`. If
   everything passes, report back only that. If anything fails, report only the failing test names, the failure
   reason, and the stack trace for each."})`. This catches cross-task breakage within the bucket. Fix whichever
   task's code a failure traces to and re-invoke until green.

5. **Unit-test coverage** — the 80% gate. Via `iru-gate-runner`:
   ```
   Agent({
     description: "Check unit-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-java-coverage\", args: \"<selector covering the bucket's affected *Test
       classes> <every target class this bucket changed or added>\"}). Build: <Maven|Gradle>, coverage tool:
       <JaCoCo|Kover>, modules: <module list>. The measurement must come from UNIT tests only: Maven Surefire with
       -DskipITs, no Failsafe; Gradle the `test` task only<, and pass `-x <integration task>` because the Kover
       report otherwise depends on it — include this clause only when Step 1's dry-run showed it>. Nothing that
       starts Docker or a container, no *IT class. Never read an aggregate report (JaCoCo report-aggregate, a
       merged exec, or a Kover report that includes integration tests). Report back, per target class, its line
       and branch coverage percentage — attributing Kotlin compiler-generated classes (FooKt, Foo.Companion,
       lambdas) to their source class — and confirm the run was unit-only.",
     run_in_background: false
   })
   ```
   For any class under 80%, add **unit** tests through the task that owns it, then re-run 3.4 and re-check here.

   **An integration test never closes this gap.** If the argument for accepting a class under 80% is that an `*IT`
   exercises it, that's precisely what this gate surfaces: logic with no fast feedback loop. Either unit-test it —
   which usually means moving logic out of an adapter into the domain/service layer where it's trivially testable
   — or record it in Step 5 as an accepted exception with that reasoning. Genuine exceptions, named in Step 5: a
   thin adapter that is almost entirely framework wiring (a Spring Data interface, a `@ConfigurationProperties`
   data class with no logic, the `main` function / `@SpringBootApplication` class), and generated code (which
   should be excluded by configuration — a generated class appearing here is a build-configuration finding).

6. **Full unit suite**, via `iru-gate-runner`: `Agent({description: "Run full unit test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\"}) with no selector to run the whole unit
   suite, or fall back to `mvn -q -B test -DskipITs` (Maven) / `./gradlew test` (Gradle, every project's `test`
   task; never check or build). If everything passes, report back only that. If anything fails, report only the
   failing test names, the failure reason, and the stack trace for each."})`. Repository-wide on purpose: this is
   the check for a change breaking an unrelated module. Fix any regression and re-invoke until green.

   **Gradle with `*IT` classes inside the `test` task** (Step 1 found no separate integration task and no
   exclusion): an unfiltered `./gradlew test` starts containers, so it is *not* a unit run. Don't run it here.
   Instead run it as the integration stage itself — under the lock, in 3.8 — and say in Step 5 that the build
   mixes unit and integration tests in one task.

7. **Static analysis**, diffed against Step 1's baselines. Via `iru-gate-runner`: `Agent({description: "Check for
   new Kotlin quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-kotlin-code-quality\", args: \"<same scope as Step 1>\"}), scoped to <module list>. Compare against this
   pre-change baseline: <Kotlin baseline from Step 1>. Report back only issues newly appearing (not in the
   baseline), each with its tool (detekt/ktlint), rule id, file, and line. Ignore findings in generated sources
   (build/generated, target/generated-sources) and report separately if any appear, since that means the
   exclusions need fixing."})`. If the touched modules hold Java sources, run `iru-java-code-quality` against its
   own baseline in a second agent in the same response.

   Any new issue is a defect this bucket introduced — trace it to its task, fix it, re-run 3.4 and 3.6 to confirm
   the fix broke nothing, and re-check. Treat detekt's `potential-bugs`/`exceptions`/`coroutines` rule sets (and
   SpotBugs, for Java) with particular seriousness — they describe defects, not style. A ktlint formatting finding
   is fixed in place; the project's `ktlintFormat`/`spotlessApply` may be used only if `git diff --stat` afterwards
   shows it touched nothing outside the bucket's own files (revert anything else it reformatted).
   Leave an issue only if fixing it contradicts what its task explicitly specifies; record that and why. Skip a
   tool whose skill isn't installed, and say so in Step 5.

8a. **Acquire the repository-wide integration-test lock.** Immediately before 3.8, never earlier.

   Skip 3.8a–3.8b entirely if Step 1 found **Docker unavailable** (report the integration stage **unverified**,
   name it in Step 5, continue to Step 4 — 3.9 has no aggregate either, and 3.5's verdict stands on its own) or
   **no `*IT` classes and no integration task** (no stage to run; say so in Step 5 rather than reporting one that
   never existed).

   The lock is a directory at the repository root, because `mkdir` either creates it or fails, atomically. A lock
   *file* written with the `Write` tool has a read-then-write window and must not be used instead.

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via `Write`) with this bucket's identifying
     text (task numbers/titles), the module list, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure
     `.integration-test.lock/` is listed in the root `.gitignore`, adding the line if missing.
   - **`HELD`** — another group is running integration tests. Tell the user this bucket is waiting (naming the
     holder from `cat .integration-test.lock/owner`), then re-run the `mkdir` roughly every 30 seconds. Don't
     proceed, and don't "just check" by running a single `*IT` — it starts the same shared stack.

   Two bounded escapes:

   - **Stale lock** — if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder
     almost certainly died. Tell the user what the owner file says, remove it (`rm -f .integration-test.lock/owner
     && rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held after ~45 minutes and not stale: stop waiting, report the integration stage as
     **unverified — blocked by a concurrent integration run**, continue to Step 4 (skipping 3.9), and name it in
     Step 5.

8. **Integration tests** — the only place in the pipeline where containers start. Build the command from Step 1:

   | Build | Integration command |
   |---|---|
   | Maven with Failsafe | `mvn -q -B -pl <modules> -am verify` |
   | Gradle with a separate integration task | `./gradlew --no-parallel <:module:integrationTest ...>` (the detected task name, per touched module that has one) |
   | Gradle with `*IT` inside `test` (no split) | `./gradlew --no-parallel test` — the full suite from 3.6, run here under the lock |

   ```
   Agent({
     description: "Run integration tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Run exactly this one command: `<integration command from the table>`. It executes the *IT tests
       against Testcontainers. Do NOT add -T/--threads or --parallel, do NOT enable parallel forks, and do NOT run
       any *IT class separately alongside it: every *IT shares one container stack, so anything concurrent
       corrupts the results. This needs a running Docker daemon and starts real containers, so it is slow — wait
       for it rather than backgrounding it. Report back only: whether all integration tests passed; for any
       failure, the failing test name, the failure reason, and the stack trace; and separately, whether any
       failure was an environment problem (Docker unavailable, image pull failure, port conflict, container
       startup timeout, insufficient memory, a compose service that never became healthy) rather than an assertion
       failure. Do not dump the build output.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never alongside another build-running agent. `run_in_background: false` is not
   optional: the lock is only meaningful if held for exactly as long as the containers are up.

   Note that Maven `verify` also runs anything else bound to `verify` in this build (detekt/ktlint `check` goals,
   `jacoco:check`); a failure from one of those is not an integration failure — report it as what it is and fix it
   like a 3.7 finding.

   - **Environment failure**: report unverified, say which, don't debug a container that won't pull. Check one
     common self-inflicted cause first: a developer stack already running from Spring Boot's Docker Compose
     support (`docker compose ps`). Report it — **never** `docker compose down` a stack this skill didn't start.
   - **Assertion failure**: trace to the owning task, fix, re-run. Keep the lock across a quick fix; for a long
     debugging session release it (3.8b), fix, re-acquire (3.8a). If the fix changes production code, re-run
     3.4–3.7 afterwards. A failure unit tests missed is usually a real wiring, mapping, query, or migration
     defect — a finding worth reporting even once fixed. Common Kotlin-specific causes worth checking first: an
     entity that is `final` (all-open missing — lazy loading or proxies misbehave), a `@Transactional` method on a
     class the `kotlin-spring` preset didn't open, a missing no-arg constructor, a Jackson binding failure from a
     missing `jackson-module-kotlin`, or a migration that doesn't match the entity under `ddl-auto=validate`.
   - **Testcontainers reuse** (`testcontainers.reuse.enable=true`) makes concurrent runs strictly worse — reused
     containers are shared by design. It changes nothing about the rule above.

8b. **Release the lock**, as soon as 3.8's agent has reported back and any fix-and-re-run cycle has finished:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release on **every** exit path — pass, failure, environment failure, a blocked task, a user interruption, or a
   hard stop. A leaked lock blocks every other group for an hour.

9. **Aggregate coverage — reported, never gated.** Via `iru-gate-runner`: `Agent({description: "Read aggregate
   coverage for task group", subagent_type: "iru-gate-runner", prompt: "Read whatever aggregate coverage report
   the integration run in this build produced — Maven: a JaCoCo report-aggregate (commonly
   coverage/target/site/jacoco-aggregate/jacoco.csv or .xml) or a merged report produced from jacoco.exec +
   jacoco-it.exec; Gradle: a Kover report whose variant includes the integration task, or a jacocoTestReport /
   custom JacocoReport task fed with the integration task's execution data. Do not run any build to produce one.
   Report back, per target class in this list — <ClassName1,ClassName2,...> — its line and branch coverage
   percentage. If no aggregate exists, say so and name which reports you did find."})`.

   For each class, note the delta against its unit-only figure from 3.5. A small delta means the integration tests
   check wiring rather than logic. A large one (82% unit, 97% aggregate) means much of the behaviour is exercised
   only with containers — it passed the gate, but it's worth one line in Step 5. Never gate on this number, never
   re-run 3.5 against it, never let it excuse a class that failed 3.5, and never re-run the integration build to
   refresh it. Skip this step with a one-line note if 3.8 was unverified or skipped.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean (or leaves only explicitly-accepted issues), replace each task's "group validation
pending" note in `implementation_plan.md` with the final outcome:

```
- [x] Task 3. **Add `OrderRepository.findByCustomer`** — port in domain, JPA adapter in
  infrastructure/database/postgresql (+ V7 migration); unit tests (88% line coverage, unit-only) +
  JpaOrderRepositoryAdapterIT (96% aggregate); boundaries clean, no new detekt/ktlint issues.
```

Quote the **unit-only** figure as the coverage result and the aggregate, where 3.9 produced one, as a separate,
labelled number. If the integration stage was unverified, say so with the reason (`integration tests unverified
(Docker unavailable)`, `(lock held by another group)`) — silence reads as a pass. Notify the user that group
validation completed, summarizing the bucket-wide result.

Before writing these notes, confirm the integration lock is released (3.8b); if a failure path skipped it,
release it now — this skill never returns while holding it. Leave a blocked task's checkbox unchecked with its
blocker note.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **Detected shape**: Maven or Gradle, hexagonal or layered/feature, Kotlin-only or mixed with Java, JaCoCo or
  Kover, how integration tests are wired.
- **Per task**: modules and files touched, build changes made (compiler plugins, dependencies — these affect every
  module that inherits them), ports added or implemented, migrations, tests added/updated, **unit-only coverage**
  (and the aggregate separately), and the static-analysis outcome.
- **The structural result** (3.3) — or that the repository isn't hexagonal / has no checks — and any rule
  deliberately relaxed, with the reasoning. A relaxed architecture rule is the outcome most worth escalating.
- **Static analysis** (3.7): new detekt/ktlint (and Checkstyle/PMD/SpotBugs) findings introduced and fixed, or
  which tools weren't installed or wired.
- **Coverage exceptions accepted** (3.5), and any class that cleared the gate narrowly or whose aggregate far
  exceeds its unit coverage (3.9).
- **The integration-test result** (3.8): passed, failed-and-fixed, or **unverified** with the reason.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire: wait time, a broken stale lock
  (and its owner text), a timeout, and confirmation it was released.
- **Build-configuration findings**: `*IT` classes running inside the Gradle `test` task, a Kover report that
  depended on the integration task, `maxParallelForks > 1` on the integration task, generated sources not excluded
  from coverage or detekt, `open-in-view` left on.
- Any integration failure unit tests missed (even once fixed); any quality issue left in place and why; whether
  license-header generation was skipped by the user; any ordering deviation applied in Step 2; and which task(s)
  stopped on a blocker, with enough detail for the caller to surface it.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
