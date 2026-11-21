---
name: iru-java-code-one-task-group
description: "Invoked as /iru-java-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one Java/Maven task bucket: one quality baseline, iru-java-code-one-task per task (parallel when allowed), then validates the bucket once (license, Javadoc, tests, 80% coverage, quality) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(mvn *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent
---

# Implement one task-group bucket (Java)

Carry out every Java task in one plan-group bucket end to end: capture one quality baseline for the whole bucket
up front, implement each task (in parallel where safe), then validate everything the bucket touched in a single
consolidated pass instead of once per task — this is what cuts the token cost and wall-clock time this catalog's
per-task validation used to spend redundantly. It is the Java/Maven counterpart to `iru-dotnet-code-one-task-group` —
same shape, but built on `iru-java-code-quality`, `iru-java-test`, `iru-java-coverage`, and `iru-java-javadoc`. It uses a medium
model on purpose: the plan already carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Capture one pre-change quality baseline for the whole bucket

Before implementing anything, collect the set of class(es) every task in this bucket is expected to touch (from
each task's own description). Capture a single pre-change quality baseline covering all of them by delegating to
the `iru-gate-runner` agent rather than running `iru-java-code-quality` directly: `Agent({description: "Capture
pre-change quality baseline for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
\"iru-java-code-quality\", args: \"<ClassName1,ClassName2,...>\"}), then report back only the list of issues found
for these classes."})`. `iru-gate-runner` runs in its own separate context and reports back just the issue list,
keeping unneeded report content out of the main context window. Record the returned issues as this bucket's
pre-change baseline — an empty baseline if every file is new. This is what Step 4's quality check compares
against, so it flags only issues this bucket's tasks introduce, not pre-existing ones. Skip this baseline
capture if the `iru-java-code-quality` skill is unavailable in this repository.

## Step 2 — Implement each task via `iru-java-code-one-task`

For every task in the bucket, invoke `iru-java-code-one-task` — it implements exactly what the task specifies and
writes/updates its tests, nothing more (license headers, Javadoc, and all validation now live here instead).

- **Bucket marked `Parallelizable: yes`**: invoke `iru-java-code-one-task` for every task in the bucket concurrently
  — issue all of the `Agent` calls below together, in the same response, so they run in parallel rather than one
  after another:
  ```
  Agent({
    description: "Implement <task N> via java-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-java-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the files touched, the tests added/updated, and
      whether the task stopped on a blocker instead of finishing.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time, in the plan's order, waiting for each to
  finish before starting the next — the plan marked this bucket non-parallel because its tasks have a real
  ordering dependency (e.g. one task's code depends on another's, or two tasks would touch the same file).

`iru-java-code-one-task` already checks off that task's own checkbox (and its sub-tasks') in
`implementation_plan.md` itself, with a "group validation pending" note, before it reports back — e.g. `- [x]
Task 2. **Implement `SimpleWidget`** — implemented, tests added; group validation pending.` As each task's agent
reports back (whether run in parallel or sequentially), re-read `implementation_plan.md` and confirm that box is
actually checked; if it isn't (a rare concurrent-write race when two tasks in a parallel bucket finished at
nearly the same moment and one edit clobbered the other), flip it yourself now, using the same note. Notify the
user that this specific task's implementation and tests landed. This is what makes progress visible per task as
it happens, even though full validation is deferred to Step 3/4.

If a task's agent reports it stopped on a blocker, record it and skip validating that specific task's code in
Steps 3–4 below (there's nothing finished to validate) — surface the blocker in this skill's own Step 5 report
without blocking the rest of the bucket's already-finished tasks from being validated.

## Step 3 — Validate the whole bucket once

Once every task that didn't block has been implemented (Step 2), run the following once for the entire bucket —
not once per task:

1. **License headers**, for every file any task in the bucket added or modified, by delegating to `iru-gate-runner`:
   `Agent({description: "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added
   or modified. Report back only which files were missing a header vs. fixed vs. already compliant, and — if no
   header convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. If the
   user chose to skip header generation, respect that choice for the rest of this run. Skip this item if
   `iru-check-license` is unavailable in this repository.
2. **Javadoc**, for every class any task in the bucket added or modified, by delegating to `iru-gate-runner`:
   `Agent({description: "Update Javadoc for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-java-javadoc\", args: \"<ClassName1,ClassName2,...>\"}) scoped to every class this bucket's
   tasks added or modified. Report back only whether Javadoc was added/updated and for which members, and
   whether the Javadoc build verification passed."})`. If it reports the build failed, fix the reported issues
   and re-invoke until it reports success. Skip this item if `iru-java-javadoc` is unavailable in this repository.
3. **Scoped tests**, for every class affected across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Run tests for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-test\", args:
   \"<comma/wildcard selector covering every test class affected by this bucket>\"}) (fall back to `mvn test
   -Dtest=<...>` directly if the java-test skill is unavailable). If everything passes, report back only that
   all tests passed. If anything fails, report back only the failing test names, the failure reason, and the
   stack trace for each."})`. If failures are reported, identify which task's code they trace back to (by
   file/class), fix that task's implementation and/or tests, and re-invoke this same check — repeat until it
   reports all tests passed. Don't move on with a red test.
4. **Coverage**, for every changed/new class across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Check coverage for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-java-coverage\",
   args: \"<selector covering the bucket's affected tests, plus every target class this bucket changed or
   added>\"}) (fall back to `mvn clean jacoco:prepare-agent test jacoco:report -Dtest=<...>` and reading
   `target/site/jacoco/jacoco.csv` if unavailable). Report back, per target class, its line coverage percentage
   and branch coverage percentage."})`. For any class under 80%, add tests for its uncovered branches/lines (via
   the task that owns that class), re-run Step 3.3 to confirm, then re-check here.
5. **Full suite**, to catch regressions this bucket's changes may have caused elsewhere, by delegating to
   `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Run `mvn
   clean test`. If everything passes, report back only that all tests passed. If anything fails, report back only
   the failing test names, the failure reason, and the stack trace for each."})`. Fix any regression, then
   re-invoke until it reports all tests passed.
6. **Code quality**, for the same class set as Step 1, by delegating to `iru-gate-runner`: `Agent({description: "Check
   for new quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-java-code-quality\", args: \"<ClassName1,ClassName2,...>\"}). Compare the reported issues against this
   pre-change baseline: <baseline from Step 1>. Report back only the issues that are newly appearing (not present
   in the baseline)."})`. Any issue reported is a regression introduced by this bucket — trace it to the task
   that owns the affected class, fix it (then re-run Steps 3.3–3.5 to confirm the fix didn't break anything), and
   re-check until none remain. Leave a new issue in place only if fixing it is genuinely unavoidable (e.g. it
   would contradict what its task explicitly specifies) — record that, and why, for Step 4. Skip this item if
   `iru-java-code-quality` is unavailable in this repository.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean for the bucket (or leaves only explicitly-accepted unavoidable issues), update each
task's note in `implementation_plan.md` — replacing the "group validation pending" placeholder from Step 2 — with
the final outcome: coverage achieved and code-quality result for that task's own class(es), e.g. `- [x] Task 2.
**Implement `SimpleWidget`** — tests in SimpleWidgetTest (94% line coverage), no new Checkstyle/PMD/SpotBugs
issues.` Save the file, then notify the user that group validation completed and summarize the bucket-wide result
(tests, coverage, quality, doc/license outcome).

If any task was recorded as blocked in Step 2, leave its checkbox unchecked and its blocker note in place — do
not run Step 3's validation against a task that never finished implementing.

## Step 5 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
files touched, tests added/updated, coverage achieved, and code-quality outcome — including whether a new issue
was left in place as unavoidable and why, whether license-header generation was skipped by the user, and which
task(s), if any, stopped on a blocker (with enough detail for the caller to surface it further up).
`implementation_plan.md`'s checkboxes are already checked (by `iru-java-code-one-task` itself, backfilled here in
Step 2 if needed) and finalized with their validation outcome (Step 4), and the user-facing progress
notifications have already gone out (Steps 2 and 4) — the caller does not need to redo that bookkeeping for this
bucket.
