---
name: iru-typescript-code-one-task-group
description: "Invoked as /iru-typescript-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one TypeScript task bucket: one baseline, iru-typescript-code-one-task per task (parallel when allowed), then validates once (license, TSDoc, tests, 80% coverage, quality, locked Playwright UI); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(lsof *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (TypeScript)

Carry out every TypeScript task in one plan-group bucket end to end: capture one quality baseline for the whole
bucket up front, implement each task (in parallel where safe), then validate everything the bucket touched in a
single consolidated pass instead of once per task — this is what cuts the token cost and wall-clock time this
catalog's per-task validation used to spend redundantly. It is the npm/TypeScript counterpart to
`iru-java-code-one-task-group` — same shape, but built on `iru-typescript-code-quality`, `iru-typescript-test`,
`iru-typescript-coverage`, and `iru-typescript-tsdoc`. It uses a medium model on purpose: the plan already carries
the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Detect the toolchain once, then capture one pre-change quality baseline for the whole bucket

Before anything else, detect once — and reuse for every step below rather than re-deriving it per gate call — the
same framework/runner/package-manager facts `iru-typescript-test`, `-coverage`, `-code-quality`, `-tsdoc`, and
`-code-one-task` each detect on their own: read `package.json` (and the root `pom.xml`/`src/main/frontend/` for a
Hilla-hosted frontend) to classify the project as `hilla-frontend` / `react-native` / `ionic` / `angular` /
`react` / `library`; read `package.json`/`angular.json` to identify the test runner (Vitest, Jest, or the Angular
CLI's `@angular/build:unit-test` builder); read `packageManager` in `package.json`, else the lockfile present
(`package-lock.json` → npm, `pnpm-lock.yaml` → pnpm, `yarn.lock` → yarn) to identify the package manager. This is
what tells Step 3.5 which full-suite command to run (`npm test` / `pnpm test` / `yarn test`) and lets every gate
prompt below name the exact invocation the matching skill (or its documented fallback) expects, instead of making
each gate re-detect the same facts independently.

**React Native is its own key.** If the classification is `react-native` (an `expo` or bare React Native CLI/`metro`
app) and `iru-react-native-code-one-task-group` is installed in this repository (its `SKILL.md` under
`.claude/skills/` at the repository root, or listed among the available skills), hand the whole bucket to it —
`Skill({skill: "iru-react-native-code-one-task-group", args: "<this bucket's full text, unchanged>"})` — relay its Step 5 report as this skill's own (noting the hand-off, and that the plan should tag
these tasks `react-native`), and stop: it owns the React Native-specific stages this skill doesn't run (native
Android/iOS builds for metro apps, the Expo prebuild check, and the device e2e stage behind
`.integration-test.lock`). Only when it isn't installed, continue below — and then say in Step 5 that those
React Native stages were not run.

Then collect the set of file(s)/module(s) every task in this bucket is expected to touch (from each task's own
description). Capture a single pre-change quality baseline covering all of them by delegating to the
`iru-gate-runner` agent rather than running `iru-typescript-code-quality` directly: `Agent({description: "Capture
pre-change quality baseline for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
\"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}), then report back only the list of issues found for
these files."})`. `iru-gate-runner` runs in its own separate context and reports back just the issue list, keeping
unneeded report content out of the main context window. Record the returned issues as this bucket's pre-change
baseline — an empty baseline if every file is new. This is what Step 3.6's quality check compares against, so it
flags only issues this bucket's tasks introduce, not pre-existing ones. Skip this baseline capture if the
`iru-typescript-code-quality` skill is unavailable in this repository.

## Step 2 — Implement each task via `iru-typescript-code-one-task`

For every task in the bucket, invoke `iru-typescript-code-one-task` — it implements exactly what the task
specifies and writes/updates its tests, nothing more (license headers, TSDoc, and all validation now live here
instead).

- **Bucket marked `Parallelizable: yes`**: invoke `iru-typescript-code-one-task` for every task in the bucket
  concurrently — issue all of the `Agent` calls below together, in the same response, so they run in parallel
  rather than one after another:
  ```
  Agent({
    description: "Implement <task N> via typescript-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-typescript-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the files touched, the tests added/updated, and
      whether the task stopped on a blocker instead of finishing.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time, in the plan's order, waiting for each to
  finish before starting the next — the plan marked this bucket non-parallel because its tasks have a real
  ordering dependency (e.g. one task's code depends on another's, or two tasks would touch the same file).

`iru-typescript-code-one-task` already checks off that task's own checkbox (and its sub-tasks') in
`implementation_plan.md` itself, with a "group validation pending" note, before it reports back — e.g. `- [x]
Task 2. **Implement `useOrderTotal`** — implemented, tests added; group validation pending.` As each task's agent
reports back (whether run in parallel or sequentially), re-read `implementation_plan.md` and confirm that box is
actually checked; if it isn't (a rare concurrent-write race when two tasks in a parallel bucket finished at
nearly the same moment and one edit clobbered the other), flip it yourself now, using the same note. Notify the
user that this specific task's implementation and tests landed. This is what makes progress visible per task as
it happens, even though full validation is deferred to Step 3.

If a task's agent reports it stopped on a blocker, record it and skip validating that specific task's code in
Step 3 below (there's nothing finished to validate) — surface the blocker in this skill's own Step 5 report
without blocking the rest of the bucket's already-finished tasks from being validated.

## Step 3 — Validate the whole bucket once

Once every task that didn't block has been implemented (Step 2), first derive the validation scope: the union of
the file(s) each task's agent reported as touched (Step 2), cross-checked against `git status` and `git diff
--name-only` (plus `git diff --name-only --cached` for anything already staged) run fresh right now — a task's own
report can miss a file it changed as a side effect (e.g. a barrel `index.ts` re-export, a generated `angular.json`
edit), and the plain diff catches those too. Use this combined file list as the scope for every gate below. Then
run the following once for the entire bucket — not once per task:

1. **License headers**, for every file in the bucket's scope, by delegating to `iru-gate-runner`: `Agent({description:
   "Add license headers for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-check-license\", args: \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified.
   Report back only which files were missing a header vs. fixed vs. already compliant, and — if no header
   convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. If the user
   chose to skip header generation, respect that choice for the rest of this run. Skip this item if
   `iru-check-license` is unavailable in this repository.
2. **TSDoc**, for the same file scope, by delegating to `iru-gate-runner`: `Agent({description: "Update TSDoc for
   task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-tsdoc\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified. Report back only which
   exported symbols were documented/completed and in which files, and whether the doc-build verification (TypeDoc
   or Compodoc, per the project's flavor) passed."})`. If it reports the doc build failed, fix the reported issues
   and re-invoke until it reports success. Skip this item if `iru-typescript-tsdoc` is unavailable in this
   repository.
3. **Scoped tests**, for every test file affected across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Run tests for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\",
   args: \"<file/directory/name-pattern selector covering every test affected by this bucket>\"}) (fall back to
   `npx vitest run <selector>` / `npx jest <selector>` / `npx ng test --watch=false --include=<glob>`, per the
   detected runner, directly if the typescript-test skill is unavailable). If everything passes, report back only
   that all tests passed. If anything fails, report back only the failing test names, the failure reason, and the
   assertion message for each."})`. If failures are reported, identify which task's code they trace back to (by
   file), fix that task's implementation and/or tests, and re-invoke this same check — repeat until it reports all
   tests passed. Don't move on with a red test.
4. **Coverage**, for every changed/new file across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Check coverage for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-typescript-coverage\", args: \"<selector covering the bucket's affected tests, plus every target file this
   bucket changed or added>\"}) (fall back to the detected runner's own coverage invocation — `npx vitest run
   --coverage --coverage.reporter=json-summary`, `npx jest --coverage --coverageReporters=json-summary`, or `npx ng
   test --coverage --watch=false --coverage-reporters=json-summary` — and reading
   `coverage/coverage-summary.json` if unavailable). Report back, per target file, its line coverage percentage and
   branch coverage percentage."})`. For any file under 80%, add tests for its uncovered lines/branches (via the
   task that owns that file), re-run Step 3.3 to confirm, then re-check here.
5. **Full suite**, to catch regressions this bucket's changes may have caused elsewhere, by delegating to
   `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Run
   `npm test`" (substitute `pnpm test` / `yarn test` per the package manager detected in Step 1). If everything
   passes, report back only that all tests passed. If anything fails, report back only the failing test names, the
   failure reason, and the assertion message for each."})`. Fix any regression, then re-invoke until it reports
   all tests passed.
6. **Code quality**, for the same file scope as Step 1, by delegating to `iru-gate-runner`: `Agent({description: "Check
   for new quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}). Compare the reported issues against this pre-change
   baseline: <baseline from Step 1>. Report back only the issues that are newly appearing (not present in the
   baseline)."})`. Any issue reported is a regression introduced by this bucket — trace it to the task that owns
   the affected file, fix it (then re-run Steps 3.3–3.5 to confirm the fix didn't break anything), and re-check
   until none remain. Leave a new issue in place only if fixing it is genuinely unavoidable (e.g. it would
   contradict what its task explicitly specifies) — record that, and why, for Step 4. Skip this item if
   `iru-typescript-code-quality` is unavailable in this repository.
7. **UI stage** — only when the bucket added or changed UI (a component, screen, story, style sheet or UI spec — the
   task reports' `Screen › Region › Component` trees say which) in a `react`/`angular`/`ionic`/`hilla-frontend`
   project. Otherwise record "UI stage: not applicable" and skip. Per the UI contract (`reference/ui-contract.md`
   §6–§8 of `iru-typescript-code-one-task`):
   - **No Playwright setup** (`@playwright/test` + `playwright.config.*` absent; for Hilla, in the web module) →
     **unverified (no Playwright UI-test setup)**, naming the components/screens left without captures; Cypress-only
     → "Cypress present, not run". Never scaffold Playwright here.
   - **Acquire the repository-wide lock** first — the same atomic `mkdir .integration-test.lock` protocol the other
     catalog group skills use (`ACQUIRED` → write `.integration-test.lock/owner` with this bucket's tasks, the stage
     `Playwright UI tests` and `date -u +'%Y-%m-%dT%H:%M:%SZ'`; `HELD` → tell the user, re-try about every 30
     seconds; older than 60 minutes (`find .integration-test.lock -maxdepth 0 -mmin +60`) → break it and note it;
     still held after about 45 minutes → **unverified — blocked by a concurrent run**). Before running, check every
     `webServer` port of `playwright.config.*` with `lsof -nP -iTCP:<port> -sTCP:LISTEN`; something already
     listening → **unverified (port <port> in use by <process>)** — don't kill it.
   - **Run the UI tests** for the bucket's specs: `Agent({description: "Run UI tests for task group",
     subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"ui
     <spec files covering this bucket's components, screens and flows>\"}) with CI=1 and lock: held (fall back to
     `CI=1 npx playwright test <specs> --reporter=list,json` with UI_SCREENSHOTS_DIR=<repo root>/.ui-screenshots
     if the skill isn't installed). Report back only: pass/fail per spec with the first error line of each failure;
     tests that passed only on retry; environment failures (browser executable missing, web server not starting,
     port in use, a Hilla web module whose prepare-frontend never ran) separately from assertion failures; the
     screenshot count per kind; malformed screenshot names; accessibility violations as a count plus rule ids; the
     .ui-quality.yml level applied.", run_in_background: false})`. Scoped specs keep the earlier captures of other
     components (the gate clears `.ui-screenshots/` only for a full UI run).
   - **Check the matrix**: for every component/screen the bucket added or changed, and every visual state its task
     report or stories name, `.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>*.png` must exist for each
     direction × theme in `.ui-quality.yml` (defaults `[ltr, rtl]` × `[light, dark]`) — `find .ui-screenshots -path
     '*/<Id>/*' -name '*.png'`. A missing cell is a gap: add the story/spec through the task that owns the
     component and re-run this stage.
   - **Apply the accessibility level**: `enforce` → every violation is a failure to fix (markup, label, contrast
     token) and re-run; `report` → list the rule ids and counts in Step 4/5 without failing; `off` → say the
     automated check is off.
   - **Assertion failure** → trace to the task, fix, re-run (and Steps 3.3–3.5 again if production code changed).
     **Environment failure** → **unverified** with the reason (for a missing browser, name the exact
     `npx playwright install chromium` the user can run — never install browsers yourself).
   - **Release the lock** (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`) as soon as this stage
     and any fix-and-re-run cycle is done — on every exit path, including failures, unverified outcomes and
     interruptions.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean for the bucket (or leaves only explicitly-accepted unavoidable issues), update each
task's note in `implementation_plan.md` — replacing the "group validation pending" placeholder from Step 2 — with
the final outcome: coverage achieved and code-quality result for that task's own file(s), e.g. `- [x] Task 2.
**Implement `useOrderTotal`** — tests in useOrderTotal.test.ts (94% line coverage), no new ESLint/tsc/Prettier
issues.` For a task that touched UI, append the UI outcome, e.g. `UI: OrderList 4 states × 2 dirs × 2 themes
captured, axe clean (enforce)` — or `UI unverified (Playwright browser missing)`; silence would read as a pass.
Save the file, then notify the user that group validation completed and summarize the bucket-wide result
(tests, coverage, quality, doc/license outcome, UI stage).

If any task was recorded as blocked in Step 2, leave its checkbox unchecked and its blocker note in place — do
not run Step 3's validation against a task that never finished implementing.

## Step 5 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
files touched, tests added/updated, coverage achieved, code-quality outcome, and for UI tasks the decomposition
tree, the screenshot matrix result (captured / missing cells) and the accessibility result at the `.ui-quality.yml`
level, or the UI stage's **unverified** reason and how the lock behaved when it wasn't an immediate acquire — including whether a new issue
was left in place as unavoidable and why, whether license-header generation was skipped by the user, and which
task(s), if any, stopped on a blocker (with enough detail for the caller to surface it further up).
`implementation_plan.md`'s checkboxes are already checked (by `iru-typescript-code-one-task` itself, backfilled
here in Step 2 if needed) and finalized with their validation outcome (Step 4), and the user-facing progress
notifications have already gone out (Steps 2 and 4) — the caller does not need to redo that bookkeeping for this
bucket.
