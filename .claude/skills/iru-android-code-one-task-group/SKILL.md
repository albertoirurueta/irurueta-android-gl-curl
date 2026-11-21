---
name: iru-android-code-one-task-group
description: "Invoked as /iru-android-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one Android/Kotlin task bucket: one quality baseline, iru-android-code-one-task per task (parallel when allowed), then validates the bucket once (license, KDoc, unit tests, 80% coverage, lint/detekt, UI stage) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(./gradlew *) Bash(gradle *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent
---

# Implement one task-group bucket (Android)

Carry out every Android/Kotlin task in one plan-group bucket end to end: capture one quality baseline for the
whole bucket up front, implement each task (in parallel where safe), then validate everything the bucket touched
in a single consolidated pass instead of once per task — this is what cuts the token cost and wall-clock time this
catalog's per-task validation used to spend redundantly. It is the Gradle/Kotlin counterpart to
`iru-java-code-one-task-group`/`iru-typescript-code-one-task-group` — same shape, but built on
`iru-android-code-quality`, `iru-android-test`, `iru-android-coverage`, and `iru-android-dokka`, and aware that a
bucket may span two Gradle modules (`lib`, `app`) rather than one project. It uses a medium model on purpose: the
plan already carries the hard reasoning; this skill drives execution of it.

**Kotlin Multiplatform projects are out of scope.** If the Kotlin Multiplatform plugin (`kotlin("multiplatform")`/`org.jetbrains.kotlin.multiplatform`, directly or via a `libs.versions.toml` alias) is applied anywhere in the build — a KMP app's Android host module included — stop and hand the request to `iru-kmp-code-one-task-group` instead (or tell the caller to, when it isn't installed); this skill assumes a plain Android `lib`/`app` layout.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Detect the touched module(s), then capture one pre-change quality baseline for the whole bucket

Before implementing anything, determine which Gradle module(s) this bucket's tasks touch — `lib`, `app`, or both —
using the same heuristic `iru-android-code-one-task`'s own Step 1 uses per task: a path under `lib/`/`app/` in the
task's own text wins; otherwise infer from what's described (a public/internal class or `View` meant for
consumers → `lib`; an `Activity`/`Composable`/anything under a package ending in `.app` → `app`); default to `lib`
if genuinely ambiguous. Record this module set once and reuse it for every gate call below, scoping each one with
a `module: <module>` line, instead of re-deriving it per gate.

Then collect the set of class(es)/file(s) every task in this bucket is expected to touch (from each task's own
description). Capture a single pre-change quality baseline covering all of them, per touched module, by delegating
to the `iru-gate-runner` agent rather than running `iru-android-code-quality` directly: `Agent({description:
"Capture pre-change quality baseline for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
Skill({skill: \"iru-android-code-quality\", args: \"module: <module>\\n<ClassName1,ClassName2,...>\"}), then report
back only the list of issues found for these classes, broken down by tool (Lint/Detekt/Spotless/ktlint) and
whether each tool was wired."})`. `iru-gate-runner` runs in its own separate context and reports back just the
issue list, keeping unneeded report content out of the main context window. Record the returned issues as this
bucket's pre-change baseline, per module — an empty baseline if every file is new. This is what Step 3.6's quality
check compares against, so it flags only issues this bucket's tasks introduce, not pre-existing ones. Skip this
baseline capture if the `iru-android-code-quality` skill is unavailable in this repository.

## Step 2 — Implement each task via `iru-android-code-one-task`

For every task in the bucket, invoke `iru-android-code-one-task` — it detects the task's own module, implements
exactly what the task specifies, and writes/updates its tests, nothing more (license headers, KDoc, and all
validation now live here instead).

- **Bucket marked `Parallelizable: yes`**: invoke `iru-android-code-one-task` for every task in the bucket
  concurrently — issue all of the `Agent` calls below together, in the same response, so they run in parallel
  rather than one after another:
  ```
  Agent({
    description: "Implement <task N> via android-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-android-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the module detected, the files touched, the tests
      added/updated, the compile-check result, and whether the task stopped on a blocker instead of finishing.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time, in the plan's order, waiting for each to
  finish before starting the next — the plan marked this bucket non-parallel because its tasks have a real
  ordering dependency (e.g. one task's code depends on another's, or two tasks would touch the same file).

`iru-android-code-one-task` already checks off that task's own checkbox (and its sub-tasks') in
`implementation_plan.md` itself, with a "group validation pending" note, before it reports back — e.g. `- [x]
Task 2. **Implement `CameraOrientationResolver`** — implemented, tests added; group validation pending.` As each
task's agent reports back (whether run in parallel or sequentially), re-read `implementation_plan.md` and confirm
that box is actually checked; if it isn't (a rare concurrent-write race when two tasks in a parallel bucket
finished at nearly the same moment and one edit clobbered the other), flip it yourself now, using the same note.
Notify the user that this specific task's implementation and tests landed. This is what makes progress visible per
task as it happens, even though full validation is deferred to Step 3/4.

If a task's agent reports it stopped on a blocker, or that its compile-check failed for a reason outside its own
scope, record it and skip validating that specific task's code in Steps 3–4 below (there's nothing finished to
validate) — surface it in this skill's own Step 5 report without blocking the rest of the bucket's already-finished
tasks from being validated.

## Step 3 — Validate the whole bucket once

Once every task that didn't block has been implemented (Step 2), run the following once for the entire bucket —
not once per task, but once per touched module (Step 1) where a gate is module-scoped:

1. **License headers**, for every file any task in the bucket added or modified, by delegating to `iru-gate-runner`:
   `Agent({description: "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added
   or modified. Report back only which files were missing a header vs. fixed vs. already compliant, and — if no
   header convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. If the
   user chose to skip header generation, respect that choice for the rest of this run. Skip this item if
   `iru-check-license` is unavailable in this repository.
2. **KDoc**, for every class/file any task in the bucket added or modified, by delegating to `iru-gate-runner`:
   `Agent({description: "Update KDoc for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-android-dokka\", args: \"<ClassName1,ClassName2,...>\"}) scoped to every class this bucket's
   tasks added or modified. Report back only which members were documented/completed and in which files, whether
   any module.md/package.md include had to be created or extended, and whether the dokkaGenerate build
   verification passed."})`. If it reports the build failed, fix the reported issues and re-invoke until it
   reports success. Skip this item if `iru-android-dokka` is unavailable in this repository.
3. **Scoped unit tests**, once per module this bucket touched, each scoped to every test class/file affected by
   this bucket in that module, by delegating to `iru-gate-runner`: `Agent({description: "Run tests for task
   group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-android-test\", args: \"module:
   <module>\\n<class name, class+method, or wildcard/package pattern covering every test affected by this bucket
   in :<module>>\"}) (fall back to `./gradlew :<module>:testDebugUnitTest --tests '<pattern>'` directly if the
   android-test skill is unavailable). If everything passes, report back only that all tests passed. If anything
   fails, report back only the failing test names (classname#name), the failure reason, and the first
   stack-trace line for each."})`. If failures are reported, identify which task's code they trace back to (by
   file/class), fix that task's implementation and/or tests, and re-invoke this same check for the affected
   module(s) — repeat until it reports all tests passed. Don't move on with a red test.
4. **Coverage**, once per module this bucket touched, for every changed/new class in that module, by delegating to
   `iru-gate-runner`: `Agent({description: "Check coverage for task group", subagent_type: "iru-gate-runner",
   prompt: "Invoke Skill({skill: \"iru-android-coverage\", args: \"module: <module>\\n<ClassName1,ClassName2,...>\"}).
   Report back, per target class, its unit-test line coverage percentage and branch coverage percentage
   (testDebugUnitTest-based only), plus any already-on-disk instrumented coverage totals reported for information
   — clearly labeled as such and never treated as satisfying the bar."})`. The bar is **80% line coverage from
   unit tests only** — instrumented (`connectedAndroidTest`) coverage is informational only, reported when a
   device/emulator already produced it, and never counted toward or against this bar, since this skill never runs
   `connectedAndroidTest` itself. For any class under 80% unit-test line coverage, add tests for its uncovered
   lines/branches (via the task that owns that class), re-run Step 3.3 for that module to confirm, then re-check
   coverage here.
5. **Full suite**, to catch regressions this bucket's changes may have caused elsewhere, by delegating to
   `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Run
   `./gradlew test`. If everything passes, report back only that all tests passed. If anything fails, report back
   only the failing test names, the failure reason, and the first stack-trace line for each."})`. `test` is the
   aggregate lifecycle task that runs every module's unit-test variants — on this catalog's own library scaffold
   `:lib:test` resolves to `testDebugUnitTest` only (verified against a generated scaffold: no `testReleaseUnitTest` task exists for
   the library module, so it is no slower than the scoped run), while a module that declares a release unit-test
   variant also runs `testReleaseUnitTest` and is noticeably slower than the scoped `testDebugUnitTest`-only runs in
   Step 3.3; run the plan's literal `./gradlew test` here for full-suite regression coverage across every module and
   variant it declares, but if wall-clock time becomes a problem for a large repository, `./gradlew testDebugUnitTest` (across all modules) is an acceptable
   lighter-weight substitute that still exercises the variant this bucket actually changed — note in the Step 5
   report which one was actually run. Fix any regression, then re-invoke until it reports all tests passed.
6. **Code quality**, for the same class/module set as Step 1, by delegating to `iru-gate-runner`: `Agent({description:
   "Check for new quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-android-code-quality\", args: \"module: <module>\\n<ClassName1,ClassName2,...>\"}). Compare the reported
   issues against this pre-change baseline: <baseline from Step 1>. Report back only the issues that are newly
   appearing (not present in the baseline), broken down by tool (Lint/Detekt/Spotless/ktlint)."})`. Any issue
   reported is a regression introduced by this bucket — trace it to the task that owns the affected class, fix it
   (then re-run Steps 3.3–3.5 to confirm the fix didn't break anything), and re-check until none remain. Leave a
   new issue in place only if fixing it is genuinely unavoidable (e.g. it would contradict what its task explicitly
   specifies) — record that, and why, for Step 4. Skip this item if `iru-android-code-quality` is unavailable in
   this repository.
7. **UI** — only when the bucket added or changed a screen, component, list or other UI (a `@Composable`, a
   `View`/`Fragment`/`Activity`, a layout or a `RecyclerView` adapter). Read `.ui-quality.yml` at the repository
   root (missing (new UI project) file = `accessibility: enforce`, `directions: [ltr, rtl]`, `themes: [light, dark]`). Then, once per
   touched module, by delegating to `iru-gate-runner`: `Agent({description: "Run UI tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-android-test\", args: \"module:
   <module>\\nui: jvm\\n<test classes covering every screen/component this bucket touched>\"}) — and, only when this
   bucket added or changed a flow test under src/androidTest, a second run with `ui: device`. Report back only:
   pass/fail with failing test names, the screenshot count per kind, any malformed screenshot names, the
   accessibility violations (count + check ids) and the level applied, and whether the device stage was
   unverified and why."})`. The Robolectric/Roborazzi JVM stage always runs (no emulator needed); a device stage
   with no attached device/emulator is **unverified** — say so, never count it as passed. Then check, for every
   screen/component the bucket added or changed (the `Screen › Region › Component` trees the tasks reported),
   that `.ui-screenshots/<kind>/<Id>/` holds a capture for each of its visual states × the configured directions ×
   themes; a missing capture is a gap to fix in that task's UI test (via the task that owns it), then re-run this
   item. Apply the accessibility level: `enforce` — a failing check fails the group, fix it in the owning task and
   re-run Steps 3.3 and 3.7; `report` — list the violations in Step 4/5's summary without failing; `off` — note that
   the checks were not run.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean for the bucket (or leaves only explicitly-accepted unavoidable issues), update each
task's note in `implementation_plan.md` — replacing the "group validation pending" placeholder from Step 2 — with
the final outcome: unit-test line coverage achieved and code-quality result for that task's own class(es), e.g.
`- [x] Task 2. **Implement `CameraOrientationResolver`** — tests in CameraOrientationResolverTest (94% unit-test
line coverage), no new Lint/Detekt issues.` For a UI task, append the UI outcome, e.g. `UI: 12 captures (OrderRow, OrdersScreen), accessibility
enforce — 0 violations; device stage unverified (no emulator).` Save the file, then notify the user that group validation completed
and summarize the bucket-wide result (tests, coverage, quality, doc/license outcome), per module if the bucket
spanned both `lib` and `app`.

If any task was recorded as blocked in Step 2, leave its checkbox unchecked and its blocker note in place — do
not run Step 3's validation against a task that never finished implementing.

## Step 5 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
module it lives in, the files touched, tests added/updated, unit-test coverage achieved, and code-quality outcome
— including whether a new issue was left in place as unavoidable and why, whether license-header generation was
skipped by the user, whether instrumented coverage was available and reported for information on any class, which
of `./gradlew test`/`testDebugUnitTest` Step 3.5 actually ran, the UI stage's outcome when it ran (Step 3.7 — screenshot counts per kind, missing
captures, accessibility violations and the level applied, and any device stage reported unverified), and which task(s), if any, stopped on a blocker
(with enough detail for the caller to surface it further up). `implementation_plan.md`'s checkboxes are already
checked (by `iru-android-code-one-task` itself, backfilled here in Step 2 if needed) and finalized with their
validation outcome (Step 4), and the user-facing progress notifications have already gone out (Steps 2 and 4) —
the caller does not need to redo that bookkeeping for this bucket.

**Warn explicitly**: every Gradle invocation in Step 3 needs `JAVA_HOME` on JDK 17+ and `ANDROID_HOME`/
`local.properties` set, the same prerequisites `iru-android-test`/`iru-android-coverage`/`iru-android-code-quality`/
`iru-android-dokka` each check on their own — a missing one fails at configuration time before any gate runs, and
looks like a gate failure rather than an environment problem; a first run on a clean machine also downloads
Robolectric's `android-all` jars (can be hundreds of MB) and may take minutes, which is not a hang. This skill's
own author did not run any of the `./gradlew` commands above against a real scaffold — no verification sub-task
was assigned for writing this skill itself — so every gate command and report path named above is **unverified
locally by this skill's author**; each is exercised instead by the underlying gate skill's own verification (see
`iru-android-test`/`iru-android-coverage`/`iru-android-code-quality`/`iru-android-dokka`'s own "Known quirks"
sections) and by the Android scaffold and workflows skills' own verification passes, not by a dry run of this
orthestration skill.
