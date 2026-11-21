---
name: iru-java-springboot-code-one-task-group
description: "Invoked as /iru-java-springboot-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one Spring Boot task bucket: one quality baseline, iru-java-springboot-code-one-task per task (parallel when allowed), then validates the bucket once (license, Javadoc, boundaries, coverage, analysis, locked integration) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(docker *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Spring Boot)

Carry out every Spring Boot task in one plan-group bucket end to end: one quality baseline up front, implement
each task (in parallel where safe), then validate everything the bucket touched in a single consolidated pass
instead of once per task.

It is the hexagonal-reactor counterpart to `iru-java-code-one-task-group`, and it is built on the same four
language-level skills — `iru-java-code-quality` for Checkstyle/PMD/SpotBugs static analysis, `iru-java-test` for
Surefire runs, `iru-java-coverage` for JaCoCo, and `iru-java-javadoc` — each invoked through an `iru-gate-runner`
agent so its report never lands in this context. Prefer those skills over a hand-written `mvn` command wherever
they're installed: they already know how to scope a run and how to read the report, and a raw command here
duplicates that logic badly. Each falls back to an explicit `mvn` invocation only when the skill isn't installed
in the consuming repository.

Four differences from the plain-Java group skill follow from the architecture rather than from preference, and
they shape the whole validation order:

1. **This skill owns integration testing — and its serialization.** `iru-java-springboot-code-one-task` runs unit
   tests only, on purpose — a task-level integration run would start the same Testcontainers stack once per task,
   which is exactly the redundancy group-level validation exists to remove. So the container-backed pass happens
   here, once per bucket, and because the whole reactor shares one compose stack, only one such pass may be in
   flight repository-wide at a time (Step 0 and Step 3.8a).
2. **Structural and static checks come first, containers last.** A module-boundary violation is caught by the
   `maven-enforcer-plugin` banned-dependencies rules and the ArchUnit test in seconds, and a Checkstyle/PMD/SpotBugs
   defect in barely longer; discovering either *after* a multi-container integration run wastes minutes for no
   extra information. By the time containers start, everything cheaper has already passed.
3. **The coverage gate is measured from unit tests alone.** Integration tests will raise the aggregate — that's
   welcome, and Step 3.9 reports it — but they never satisfy the gate. Three reasons, and they matter enough that
   the whole coverage stage is built around them: unit coverage is the number the bucket's own tasks control and
   can act on; it is available whether or not Docker is, so the verdict never becomes unreliable at exactly the
   moment the integration stage is unverified; and a class whose coverage arrives only through a container-backed
   test is a class with no fast feedback loop, which is a finding worth surfacing rather than a pass to hide
   behind an aggregate.
4. **Everything is module-scoped.** In a reactor of a dozen modules, an unscoped `mvn` per bucket dominates the
   wall clock. Use `-pl <module1>,<module2> -am` built from the modules the bucket actually touched.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

Everything below assumes one rule, and getting it wrong corrupts results rather than merely slowing them down:

**Only one integration-test run may be in flight against this repository at any moment.** The Testcontainers
harness this catalog scaffolds (`iru-setup-java-springboot-testcontainers`) starts a single `compose.yaml` through
`ComposeContainer`, and the services in it — MongoDB, PostgreSQL, Kafka, Elasticsearch — hold state that two
concurrent runs would share. A second run started while the first is mid-suite does not just contend for CPU and
host ports: it sees the other run's rows, topics, and indices, so tests fail for reasons nothing in the code
explains, or worse, pass because the other run happened to leave the right data behind. Both outcomes are
indistinguishable from a real result, which is why this is a hard serialization rule and not a performance note.

This applies at three levels:

1. **Across buckets/groups.** If several task groups each need integration tests, they run one after another,
   never overlapping — enforced by the lock in Step 3.8a, which holds regardless of what the caller does.
2. **Within this bucket.** Step 2's per-task agents never run integration tests at all (the per-task skill forbids
   it categorically), so the only container-backed run is Step 3.8's single invocation.
3. **Within the Maven run itself.** Never add `-T`/`--threads` to the `verify` command, and never enable parallel
   Failsafe forks for this run — two `*IT` classes running concurrently share the same compose stack just as two
   groups would.

Everything expensive is also run in a separate `iru-gate-runner` agent — unit tests and integration tests alike —
so the raw Surefire/Failsafe output never lands in this skill's context. Those agents report back failing tests
only: names, reasons, stack traces, and nothing else on a green run.

## Step 1 — Capture one pre-change quality baseline for the whole bucket

Collect the class(es) every task in this bucket is expected to touch, and the module each lives in, from the
tasks' own descriptions. Capture a single pre-change baseline covering all of them by delegating to the
`iru-gate-runner` agent rather than running the quality skill directly:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-java-code-quality\", args: \"<ClassName1,ClassName2,...>\"}), then report
    back only the list of issues found for these classes. This is a multi-module Maven reactor — scope the run
    with -pl <module1>,<module2> -am covering these modules: <module list>.",
  run_in_background: false
})
```

Record the returned issues as this bucket's baseline — empty if every file is new. Step 3.7's quality check
compares against it, so only defects this bucket introduces are flagged, not the repository's pre-existing
Checkstyle/PMD/SpotBugs backlog. Skip this if `iru-java-code-quality` isn't installed, and say so in Step 5: a
bucket validated with no static analysis at all is worth knowing about.

Also record two things for Step 3.8, now rather than after twenty minutes of implementation work:

- **Whether Docker is available**: run `docker info`. If it isn't, note it now — the integration stage will report
  as unverified rather than pretending to pass.
- **Whether this reactor actually has integration tests at all**: `find . -name '*IT.java' -not -path '*/target/*'`.
  A bucket in a reactor with no `*IT` classes skips Step 3.8's lock and run entirely; say so in Step 5 rather than
  reporting an integration stage that never existed. Note that this changes nothing about the coverage gate in
  Step 3.5, which never depended on integration tests in the first place.

Don't check whether the integration lock is free here — a lock acquired now and held through Step 2's
implementation work would block every other group for the entire bucket, which is the opposite of what it's for.
Acquire it in Step 3.8a, immediately before the run that needs it.

## Step 2 — Implement each task via `iru-java-springboot-code-one-task`

For every task in the bucket, invoke `iru-java-springboot-code-one-task` — it resolves the target module, implements
exactly what the task specifies, writes and runs its own **unit** tests, and checks off its own checkbox.

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response, so they run
  concurrently:
  ```
  Agent({
    description: "Implement <task N> via iru-java-springboot-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-java-springboot-code-one-task\", args: \"<the task's full text, including
      its exact implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text,
      and any relevant Current code state context>\"}). Report back: the modules and files touched, the ports
      added or implemented, the unit tests added/updated and their result, anything deliberately left to group
      integration validation, any deviation from the skill's reference/ guidance, and whether the task stopped on
      a blocker. Unit tests only — do not run `mvn verify`, Failsafe, any *IT class, or anything that starts
      Docker or a container: several of these agents run concurrently and the whole reactor shares one compose
      stack, so a container-backed run here would collide with its siblings and with the group's own integration
      stage.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**One extra ordering constraint this architecture imposes, regardless of the parallelizable verdict.** If two
tasks in the bucket are related as *port and adapter* — one adds an interface in `domain`, another implements it in
an `infrastructure` or `api` module — the port must land first, or the implementing task compiles against a type
that doesn't exist yet. Check for that relationship in the bucket's task descriptions before fanning out. If you
find one and the group is marked parallelizable, run those two sequentially (port first) while the rest still run
in parallel, and note the deviation in Step 5's report. The plan usually catches this and marks the group
non-parallel; when it doesn't, the failure looks like a spurious compile error rather than a plan defect.

As each task's agent reports back, re-read `implementation_plan.md` and confirm its box is actually checked. If it
isn't — a rare concurrent-write race when two parallel tasks finish at nearly the same moment — flip it yourself
now with the same "group validation pending" note. Notify the user that this specific task's implementation and
unit tests landed. This makes progress visible per task even though full validation is deferred.

If a task reports it stopped on a blocker, record it and exclude that task's code from Step 3's validation —
surface the blocker in Step 5 without blocking the rest of the bucket.

## Step 3 — Validate the whole bucket once

Run the following once for the entire bucket, in this order. The sequence is deliberate and runs cheapest-first:
headers and docs, then the structural checks, then unit tests, coverage, and static analysis — everything that can
gate the bucket — and only then the container-backed stage, so containers start solely against code that has
already passed every other check. **Steps 3.1–3.7 are the gates; 3.8 and 3.9 add information on top of them.** One
practical consequence worth internalising: a bucket can be fully validated on a machine with no Docker at all, and
the only thing lost is the integration result and the aggregate figure.

Build the module list from every module the bucket's tasks touched, and reuse it for every scoped command below.

1. **License headers**, for every file the bucket added or modified, via `iru-gate-runner`: `Agent({description:
   "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified.
   Report back only which files were missing a header vs. fixed vs. already compliant, and — if no header
   convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a skip
   choice for the rest of the run. Skip if `iru-check-license` isn't installed.

2. **Javadoc**, for every class the bucket added or modified, via `iru-gate-runner`: `Agent({description: "Update
   Javadoc for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-javadoc\",
   args: \"<ClassName1,ClassName2,...>\"}) scoped to every class this bucket's tasks added or modified. This is a
   multi-module reactor — scope with -pl <module list> -am. Report back only whether Javadoc was added/updated and
   for which members, and whether the Javadoc build verification passed."})`. If the build failed, fix and
   re-invoke until it passes. Skip if `iru-java-javadoc` isn't installed. Note that the per-task skill already
   requires full Javadoc, so this should mostly confirm rather than backfill — a lot of missing Javadoc here means
   the per-task skill was bypassed.

3. **Module boundaries** — the structural check, and the one with no equivalent in the plain-Java group skill. Via
   `iru-gate-runner`:
   ```
   Agent({
     description: "Verify hexagonal module boundaries",
     subagent_type: "iru-gate-runner",
     prompt: "Run `mvn -q -B validate` at the reactor root to execute the maven-enforcer-plugin
       banned-dependencies rules, then run the ArchUnit architecture test (a test class under the boot module,
       typically named *ArchitectureTest — locate it with grep rather than assuming the name) via
       `mvn -q -B -pl boot -am test -Dtest=<ArchUnitTestClass> -DskipITs`. Report back only: whether the enforcer
       rules passed, whether the ArchUnit test passed, and for any failure the exact rule/assertion violated and
       the offending class and import.",
     run_in_background: false
   })
   ```
   Any violation is a task implementing code in the wrong module or importing outward. **Fix the code, not the
   rule** — move the class, or introduce the port the adapter should have implemented. Relax an enforcer rule or
   an ArchUnit assertion only if it is genuinely wrong for this project, and record that decision for Step 5. Then
   re-run this check. If neither the enforcer rules nor an ArchUnit test exist in this repository, note that and
   continue — don't create them here, that's the setup skills' job.

4. **Scoped unit tests**, across every class the bucket affected, via `iru-gate-runner`: `Agent({description: "Run
   unit tests for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\",
   args: \"<selector covering every test class affected by this bucket>\"}), telling it this is a multi-module
   reactor to be scoped with -pl <module list> -am. If that skill isn't installed, fall back to `mvn -q -B -pl
   <module list> -am test -Dtest='<selector>' -DskipITs`. Surefire only — do not run verify or Failsafe at this
   stage. If everything passes, report back only that. If anything fails, report back only the failing test names,
   the failure reason, and the stack trace for each."})`. The per-task skill already ran each task's own tests;
   this catches cross-task breakage within the bucket. Fix whichever task's code a failure traces to and re-invoke
   until green.

5. **Unit-test coverage** — the 80% gate, and the reason it sits here rather than after the containers. Via
   `iru-gate-runner`:
   ```
   Agent({
     description: "Check unit-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-java-coverage\", args: \"<selector covering the bucket's affected tests,
       plus every target class this bucket changed or added>\"}), telling it this is a multi-module reactor to be
       scoped with -pl <module list> -am. The measurement must come from UNIT tests only: Surefire with -DskipITs,
       no Failsafe, no *IT class, nothing that starts Docker or a container. If that skill isn't installed, fall
       back to `mvn -q -B -pl <module list> -am clean jacoco:prepare-agent test jacoco:report -Dtest='<selector>'
       -DskipITs` and read each module's target/site/jacoco/jacoco.csv — never the aggregate under
       coverage/target/site/jacoco-aggregate/, which mixes in integration execution data. Report back, per target
       class, its line coverage percentage and branch coverage percentage, and confirm the run was unit-only.",
     run_in_background: false
   })
   ```
   For any class under 80%, add tests for the uncovered branches and lines — **unit tests, via the task that owns
   that class** — then re-run 3.4 and re-check here until it clears.

   **An integration test never closes this gap.** If a class sits under 80% on unit coverage and the argument for
   accepting it is that an `*IT` exercises it, that is precisely the case this gate exists to surface: the logic
   has no fast feedback loop, so every future change to it costs a container run to validate. Either unit-test the
   logic — which usually means the adapter is doing work that belongs in `domain` or `application`, where it would
   be trivially testable — or record it in Step 5 as an accepted exception with that reasoning. Do not silence it
   by pointing at Step 3.9's aggregate.

   Two classes of genuine exception, both to be named in Step 5 rather than waved through: a thin adapter that is
   almost entirely framework wiring with no branching logic of its own, and generated code (which the reactor
   already excludes from JaCoCo by configuration — a generated class appearing here means the exclusions need
   fixing, which is a build-configuration finding, not a coverage one).

6. **Full unit suite**, to catch regressions elsewhere in the reactor, via `iru-gate-runner`: `Agent({description:
   "Run full unit test suite", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\"})
   with no selector to run the whole suite, or fall back to `mvn -q -B test -DskipITs` across the whole reactor if
   it isn't installed. Surefire only — do not run verify. If everything passes, report back only that. If anything
   fails, report back only the failing test names, the failure reason, and the stack trace for each."})`.
   Reactor-wide on purpose: this is the check for a bucket's change breaking an unrelated module. Fix any
   regression and re-invoke until green.

7. **Code quality** — Checkstyle, PMD, and SpotBugs over the same class set as Step 1, diffed against that
   baseline. Via `iru-gate-runner`: `Agent({description: "Check for new quality issues in task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-code-quality\", args:
   \"<ClassName1,ClassName2,...>\"}), scoped with -pl <module list> -am. Compare the reported issues against this
   pre-change baseline: <baseline from Step 1>. Report back only the issues that are newly appearing (not present
   in the baseline), each with its tool (Checkstyle/PMD/SpotBugs), rule, file, and line. Ignore any finding in a
   generated-sources or generated package — the reactor excludes those by configuration, so a finding there means
   the exclusions need fixing, which is worth reporting separately rather than fixing as a code issue."})`.

   Any new issue is a defect this bucket introduced — trace it to its task, fix it, re-run 3.4 and 3.6 to confirm
   the fix broke nothing, and re-check. Treat SpotBugs findings with particular seriousness: unlike most Checkstyle
   rules, they describe real defects (null dereferences, ignored return values, exposed internal representation)
   rather than style. Leave an issue in place only if fixing it would contradict what its task explicitly
   specifies; record that and why. Skip this item if `iru-java-code-quality` isn't installed.

   This runs before the containers deliberately: static analysis costs a fraction of an integration suite, and
   there is no sense discovering a null-dereference bug after twenty minutes of container time.

8a. **Acquire the repository-wide integration-test lock.** Do this immediately before the run in 3.8, never
   earlier — the lock exists to serialize the container-backed run, not this bucket's implementation work.

   Skip 3.8a–3.8b entirely — don't take the lock, don't run `verify` — if either Step 1 probe came back negative:

   - **Docker unavailable**: report the integration stage as **unverified**, name it explicitly in Step 5, and
     continue to Step 4 — with no `verify` run there is no aggregate for 3.9 to read either, and the bucket's
     coverage verdict from 3.5 already stands on its own. An unverified stage is a known gap; a silently skipped
     one reads as a pass.
   - **No `*IT` classes in the reactor**: there is no integration stage to run or serialize. Say that in Step 5,
     rather than reporting a stage that never existed.

   The lock is a directory at the reactor root, because `mkdir` either creates it or fails — atomically, with no
   read-then-write window that two waiting groups could both pass through. A lock *file* written with the `Write`
   tool has exactly that window and must not be used instead.

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — the lock is yours. Immediately record who holds it, so a later waiter can tell a live run
     from an abandoned one: write `.integration-test.lock/owner` (via the `Write` tool) containing this bucket's
     identifying text (the task numbers/titles it covers), the module list, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`.
     Then ensure `.integration-test.lock/` is listed in the repository's root `.gitignore`, adding the line if
     it's missing — the lock is transient local state and must never be committed.
   - **`HELD`** — another group is running integration tests right now. Tell the user this bucket is waiting
     (naming the holder from `cat .integration-test.lock/owner`, if it's readable), then poll: re-run the `mkdir`
     roughly every 30 seconds. Do not proceed, do not run `verify` anyway, and do not "just check" by running a
     single `*IT` class — a single class starts the same shared stack.

   Two bounded escapes from the wait, so a crashed run can't deadlock the pipeline forever:

   - **Stale lock.** If `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder has
     been running for over an hour, which for a container-backed suite means it almost certainly died without
     releasing. Tell the user what the owner file says and that you are breaking a stale lock, remove it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), and acquire it fresh. Say so in
     Step 5 — a broken lock is worth knowing about even when it was the right call.
   - **Wait timeout.** If the lock is still held after roughly 45 minutes of polling and is not yet stale, stop
     waiting. Report the integration stage as **unverified — blocked by a concurrent integration run**, continue
     to Step 4 (skipping 3.9, which has no aggregate to read), and name it in Step 5. Blocking the whole bucket
     indefinitely behind another group's suite is worse than an explicitly unverified stage — and everything that
     gates the bucket has already passed by this point.

8. **Integration tests** — the stage this group skill exists to own, and the only place in the whole pipeline
   where containers start. Only now, once everything above is clean and the lock is held:
   ```
   Agent({
     description: "Run integration tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Run `mvn -q -B -pl <module list> -am verify` to execute the Failsafe *IT tests against the
       Testcontainers stack started from the repository's compose.yaml. Run exactly this one command — do NOT add
       -T/--threads, do NOT enable parallel Failsafe forks, and do NOT run any *IT class separately alongside it:
       every *IT shares one compose stack and one set of databases, so anything concurrent corrupts the run's
       results rather than merely slowing it down. This needs a running Docker daemon and starts real containers,
       so it is slow — expect several minutes; wait for it rather than backgrounding it. Report back only:
       whether all integration tests passed; for any failure, the failing test name, the failure reason, and the
       stack trace; and separately, whether any failure was an environment problem (Docker unavailable, image
       pull failure, port conflict, container startup timeout, insufficient memory, a compose service that never
       became healthy) rather than a test assertion failure. Do not dump the Failsafe or Maven output.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never issue it in the same response as another `mvn`-running agent, and never fan
   out several of them. `run_in_background: false` is not optional here: the lock is only meaningful if this skill
   holds it for exactly as long as the containers are up, which requires waiting for the agent to finish.

   - **An environment failure** rather than an assertion failure: report it as unverified rather than as a code
     defect, and say which. Don't spend the bucket's time debugging a container that won't pull. One environment
     cause worth checking before concluding anything, since it is both common and self-inflicted: a developer's
     local stack already running from Spring Boot's compose support (`docker compose ps`). Report it if you find
     it — **never** run `docker compose down` against a stack this skill didn't start.
   - **A genuine assertion failure**: trace it to the task that owns the code, fix, and re-run. Keep holding the
     lock across the fix-and-re-run cycle if the fix is quick; if it turns into a long debugging session, release
     the lock (3.8b), fix, then re-acquire (3.8a) before re-running — other groups shouldn't queue behind an
     idle lock. If the fix changes production code rather than only test setup, re-run 3.4–3.7 afterwards: a
     change made this late still has to clear the unit tests, the coverage gate, and static analysis. A failure
     here that unit tests missed is usually a real wiring, mapping, or query defect —
     precisely what integration tests are for — so treat it as a finding worth reporting even once fixed.
   - **Testcontainers reuse** (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`) makes
     concurrent runs strictly worse, not better: reused containers are shared *by design*, so two overlapping
     runs land in literally the same database. It changes nothing about the rule above — it just raises the cost
     of breaking it.

8b. **Release the lock**, as soon as 3.8's agent has reported back and any fix-and-re-run cycle has finished:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release on **every** exit path, not just the happy one — integration tests passed, they failed, they failed for
   an environment reason, a task turned out to be blocked, the user interrupted, or this skill is about to report a
   hard stop. A lock left behind after a failure blocks every subsequent group for a full hour until the staleness
   escape in 3.8a fires. If you took the lock, you own releasing it before this skill returns.

9. **Aggregate coverage — reported, never gated.** The reactor's `coverage` module runs JaCoCo
   `report-aggregate`, and because 3.8's `verify` produced both unit and integration execution data, the aggregate
   reflects both. Read it only to quantify what the integration tests added on top of the unit coverage already
   gated in 3.5. Via `iru-gate-runner`: `Agent({description: "Read aggregate coverage for task group",
   subagent_type: "iru-gate-runner", prompt: "Read the aggregated JaCoCo report at
   coverage/target/site/jacoco-aggregate/jacoco.csv (produced by the verify run) and report back, per target class
   in this list — <ClassName1,ClassName2,...> — its line and branch coverage percentage. If that file doesn't
   exist, say so rather than falling back to a per-module report, and name which per-module reports you did
   find."})`.

   For each class, note the delta against its unit-only figure from 3.5. That delta is the point of this step:

   - **A small delta** means the class is genuinely unit-tested and the integration tests are checking wiring
     rather than logic. Nothing to do.
   - **A large delta** — a class at, say, 82% unit and 97% aggregate — means a substantial share of its behaviour
     is only ever exercised with containers running. It passed the gate, so it isn't a failure, but it is worth
     one line in Step 5: it predicts where the next slow, hard-to-debug change will be.

   Never treat this number as a gate, never re-run 3.5 against it, and never let it retroactively excuse a class
   that failed 3.5 — it measures a different thing on purpose. Skip this step entirely, with a one-line note, if
   3.8 was unverified or skipped for any reason (Docker unavailable, an environment failure, the lock wait timing
   out, or no `*IT` classes in the reactor): with no integration run there is no aggregate to read, and — this is
   the payoff of gating on unit coverage — **nothing about the bucket's coverage verdict changes**. Never re-run
   `verify` here to refresh the aggregate; that is 3.8's run, and it needs 3.8a's lock.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean (or leaves only explicitly-accepted issues), update each task's note in
`implementation_plan.md`, replacing the "group validation pending" placeholder with the final outcome — including
the module, coverage, and whether integration tests covered it:

```
- [x] Task 3. **Add `OrderRepository.findByCustomer`** — port in domain, adapter in
  infrastructure/database/mongodb; unit tests (87% line coverage, unit-only) + MongoOrderRepositoryAdapterIT
  (96% aggregate); module boundaries clean, no new Checkstyle/PMD/SpotBugs issues.
```

Quote the **unit-only** figure as the coverage result, and the aggregate — where 3.9 produced one — as a separate,
clearly-labelled number. A single unattributed percentage is the thing this whole arrangement exists to avoid: it
reads as the gate while silently including coverage the gate never counted.

If the integration stage was unverified, say so in the note rather than implying it passed, and give the reason —
`integration tests unverified (Docker unavailable)` or `integration tests unverified (lock held by another group)`
is actionable; silence reads as a pass. The coverage figure stands regardless, since it never depended on that
stage. Then notify the user that group validation completed, summarizing the bucket-wide result.

Before writing these notes, confirm the integration lock is released (Step 3.8b). If it isn't — because a failure
path skipped the release — release it now: this skill must never return while still holding it.

If any task was recorded as blocked in Step 2, leave its checkbox unchecked with its blocker note — don't validate
a task that never finished implementing.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **Per task**: the modules and files touched, ports added or implemented, tests added/updated, **unit-only
  coverage achieved** (and the aggregate separately, where 3.9 produced one), and code-quality outcome.
- **The module-boundary result** (Step 3.3), and any enforcer rule or ArchUnit assertion deliberately relaxed, with
  the reasoning. This is the one outcome most worth escalating — a relaxed architecture rule is a decision the
  whole service inherits.
- **The static-analysis result** (Step 3.7): how many new Checkstyle/PMD/SpotBugs findings this bucket introduced
  and how many were fixed, or that `iru-java-code-quality` isn't installed and no static analysis ran at all.
- **Any class that cleared the coverage gate only narrowly, or whose aggregate far exceeds its unit coverage**
  (Step 3.9) — the second is the more useful signal: it names the code that can only be verified with containers,
  which is where the next change will be slow and hard to debug.
- **Any coverage exception accepted** (Step 3.5), with the reasoning — a thin wiring-only adapter, or a
  generated class that shouldn't have been measured at all (which is a build-configuration finding, not a coverage
  one).
- **The integration-test result** (Step 3.8), stated as passed, failed-and-fixed, or **unverified** with the
  reason. Never let "unverified" read as "passed".
- **How the integration lock behaved** (Step 3.8a–3.8b), whenever it was anything other than an immediate
  uncontended acquire: how long this bucket waited behind another group, whether a stale lock was broken (and what
  its owner file said), whether the wait timed out, and confirmation that the lock was released. Contention is
  worth surfacing because it is the pipeline's one genuinely serial stage — several integration-heavy groups in a
  plan means wall-clock time the caller can't parallelize away, and that's a scheduling fact `iru-code` and the
  user should see rather than infer from a slow run.
- **Any integration failure that unit tests missed**, even once fixed — it says something about where this
  service's test coverage is thin.
- Whether a new quality issue was left in place as unavoidable and why; whether license-header generation was
  skipped by the user; whether any generated-source exclusion looked misconfigured (Step 3.5 or 3.7); whether a
  port/adapter ordering deviation was applied (Step 2); and which task(s), if any, stopped on a blocker, with
  enough detail for the caller to surface it further up.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
