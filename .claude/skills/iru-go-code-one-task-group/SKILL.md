---
name: iru-go-code-one-task-group
description: "Invoked as /iru-go-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one Go task bucket: one quality baseline, iru-go-code-one-task per task (parallel when allowed), then validates the bucket once (license, godoc, tests, 80% coverage, golangci-lint, UI captures, locked integration stage) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(go *) Bash(gofmt *) Bash(command -v *) Bash(docker info*) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Go)

Carry out every `go` task in one plan-group bucket end to end: one quality baseline up front, implement each task (in
parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once per
task. It is built on the Go gate skills — `iru-go-test`, `iru-go-coverage`, `iru-go-code-quality`, `iru-go-godoc` —
each invoked through an `iru-gate-runner` agent so its report never lands in this context; fall back to the explicit
command shown only when a gate skill isn't installed in the consuming repository.

Three things shape the validation order:

1. **Coverage counts in-process and testscript tests together.** `-coverpkg=./...` lets a command in
   `internal/cli` be covered by `cmd/<bin>`'s testscript suite and by `internal/cli`'s in-process table tests alike,
   so the 80% gate is applied **per changed file** from one whole-module run — never per package in isolation, which
   would under-count a thin `main` and its CLI package.
2. **The race detector runs on the full suite.** Scoped tests (3.3) run first for fast feedback; the full suite (3.5)
   runs with `-race` (when cgo is available), because a race between two tasks' code only shows when both are
   exercised.
3. **Only container-backed tests take the lock.** A CLI's end-to-end tests (testscript, `os/exec` of the built
   binary) start no containers and share no ports, so they run in 3.3/3.5 with no lock. Tests behind
   `//go:build integration` (testcontainers-go, a real database) are a separate, final stage behind the
   repository-wide `.integration-test.lock` (Step 0) — and only when the repository has any.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one container-backed run may be in flight against this repository at any moment** — enforced across buckets by
the lock (Step 3.7a), within this bucket by never running `integration`-tagged tests from Step 2's per-task agents
(the per-task skill runs no tests at all), and within the run by issuing the stage as a single gate call. The lock
directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same `.integration-test.lock`
every other group skill in this catalog uses (`iru-fastapi-code-one-task-group`, `iru-java-springboot-code-one-task-group`,
`iru-rust-code-one-task-group`, …), so a polyglot repository serializes them all together. Never hold it while
implementing (Step 2) or while running the container-free gates.

**Never both**: when this skill holds the lock it passes `lock: held` to `iru-go-test`; when it doesn't, it must not
pass `lock: held`, and the gate takes the lock itself.

## Step 1 — Detect the shape and scope once, then capture one pre-change baseline

Detect once and reuse for every gate call below (the same facts `iru-go-code-one-task` detects):

- **Module(s)**: the `go.mod` owning each task's paths — module path, `go` directive; a `go.work` workspace (every
  gate runs per module directory, since `./...` doesn't cross module boundaries); `vendor/`.
- **Layout**: `cmd/<bin>/` binaries, `internal/` packages, generated files (`// Code generated … DO NOT EDIT.` — never
  in any scope below).
- **Tests**: testscript (`grep -rln "testscript.Main\|testscript.Run" --include=*_test.go` → the package that runs the
  scripts, e.g. `./cmd/<bin>`, and its `Dir`); teatest; golden files and their `-update` flags; **`integration`-tagged
  files** (`grep -rl "^//go:build.*integration" --include=*_test.go`) — none → Step 3.7 is skipped and Step 5 says so.
- **Race detector**: `go env CGO_ENABLED` = `1` and a C compiler present (macOS always qualifies) → `race: yes`; else
  `race: no` and Step 5 says the full suite ran without `-race` and why.
- **Quality tools wired**: `.golangci.yml` (v2 when `version: "2"`), `command -v golangci-lint`, `command -v
  govulncheck`; a missing tool is reported, not installed by this skill.
- **Terminal UI** — `charm.land/bubbletea/v2` (or v1) in `go.mod`, the TUI package(s) (`internal/tui/`), the capture
  helper (`ui_screenshots_test.go`) and `.ui-quality.yml` at the repository root (missing (new UI project) = `accessibility: enforce`,
  `directions: [ltr, rtl]`, `themes: [light, dark]`). Note whether the TUI adapts to the background (a `Theme` built
  with `lipgloss.LightDark` from `tea.BackgroundColorMsg`) — the themes 3.5a expects are the listed ones it defines
  plus `no-color` (only `no-color` for a single palette).
- **Docker** — `docker info` now, recorded for Step 3.7 (only when integration-tagged tests exist).

Then collect the files every task is expected to touch and capture a single pre-change baseline via `iru-gate-runner`:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-go-code-quality\", args: \"<file1.go,file2.go,...>\"}) in the Go module at
    <module dir>. Report back only the issues found for these files, itemized per rule id (`<linter>` or
    `<linter>:<rule>` such as revive:exported, gosec:G204, staticcheck:SA1019, errcheck; gofmt/goimports per file;
    govet; govulncheck:<GO-ID>): rule id, file:line — plus which tools ran and which were missing.",
  run_in_background: false
})
```

Record it as the bucket's baseline — empty if every file is new. Step 3.6 diffs against it so only issues this bucket
introduces are flagged. Skip if `iru-go-code-quality` isn't installed, and say so in Step 5.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 2 — Implement each task via `iru-go-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-go-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-go-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any relevant
      Current code state context>\"}). Report back: the shape detected (module, go directive, CLI framework, TUI,
      test harness), the packages and files touched, the tests/.txtar scripts/golden files added or updated (and
      whether a golden file was hand-written), the go build/go vet result, interface changes (commands, flags, exit
      codes, JSON fields/schemaVersion), any go.mod/go.sum change, any deviation from the skill's reference/, and
      whether the task stopped on a blocker. Format and compile-check only — do not run go test, coverage,
      golangci-lint, govulncheck, or anything that starts Docker: sibling agents run concurrently and the group
      validates once.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**Ordering constraints Go imposes regardless of the verdict** — run these tasks sequentially (provider first), and
note the deviation in Step 5:

- Two tasks editing the same file — especially `internal/cli/root.go` (command registration, global flags),
  `exitcode.go`/`errors.go`/`output.go` (the contract), a shared golden file or `.txtar` script, or `go.mod`/`go.sum`
  (two concurrent `go get`/`go mod tidy` runs clobber each other).
- One task's package, type, or function used by another's command or test.
- A task that adds a `//go:generate` input and one that consumes the generated code.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a concurrent-write
race), flip it yourself with the same "group validation pending" note. Notify the user that this task's
implementation and tests landed. A task that stopped on a blocker — or whose compile-check failed for a reason outside
its scope that a re-run didn't clear — is recorded and excluded from Step 3's validation, without blocking the rest of
the bucket.

Before Step 3, once all agents are done, run `go build ./...` and `go vet ./...` yourself from each touched module's
root: parallel tasks each compiled against a tree the others were still changing, and only this combined check sees
the final state. Fix (via the owning task's code) anything it reports.

## Step 3 — Validate the whole bucket once

Derive the scope first: the union of the files each agent reported, cross-checked against `git status`,
`git diff --name-only`, and `git diff --name-only --cached`. List separately: production `.go` files (no
`_test.go`, no generated files), test files (`_test.go`, `testdata/script/*.txtar`, golden files), and the touched
packages (`./internal/cli`, …). Then run, in this order — **3.1–3.6 gate the bucket; 3.7 runs only once they pass**.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args: \"<file1.go,file2.go,...>\"})
   scoped to every .go file this bucket added or modified, tests included (not generated files with a `Code generated
   … DO NOT EDIT.` header, not go.mod/go.sum, not .txtar or golden files). Report back only which files were missing a
   header vs. fixed vs. already compliant, and — if no header convention existed anywhere in the repo — whether the
   user chose to skip or generate one."})`. A header must sit **above** the `//go:build` line and the package comment,
   separated from the package comment by a blank line, or it becomes the package's doc comment — check one fixed file
   (verified: both a `//` and a `/* */` header above `//go:build integration` keep the constraint effective).
   Respect a skip choice for the rest of the run. Skip if `iru-check-license` isn't installed.

2. **Doc comments**, via `iru-gate-runner`: `Agent({description: "Update Go doc comments for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-go-godoc\", args: \"<the packages this bucket
   added or modified, e.g. ./internal/cli,./internal/tui — production code only>\"}) in the Go module at <module dir>.
   Report back only which identifiers and package comments were added or fixed, in which files, and whether the check
   (revive exported/package-comments rules, or the go vet + grep fallback, plus go doc per package) passed."})`. Fix and
   re-invoke until the check passes. Skip if `iru-go-godoc` isn't installed. A lot of backfilling here means the per-task
   skill was bypassed.

3. **Scoped tests**, via `iru-gate-runner`: `Agent({description: "Run scoped Go tests for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-go-test\", args: \"<the touched packages plus the package
   that runs the testscript suite when a command changed, e.g. ./internal/cli ./cmd/greeter>\\nrace: no\"}) in the Go
   module at <module dir>. (Fallback: `go test -count=1 <packages>`.) Never the integration tag. If everything passes,
   report back only that, with the count run. If anything fails, report back only the failing test names (leaf
   subtests, or the `FAIL: <script>.txtar:<line>` line for a testscript failure), the failure message, and say whether
   a package had no test files or a run pattern matched nothing."})`. Trace each failure to its task, fix, and re-invoke
   until green. A failing golden comparison is either a bug or an intended interface change: if the task changed the
   output on purpose, refresh the file with that test's own update flag (`go test ./<pkg> -run '^<TestName>$' -update`),
   **read the diff**, and confirm it matches the task — never refresh to make red go green. A touched package with no
   test files is a scoping mistake to fix by adding tests, not a pass.

4. **Coverage — the 80% gate per changed file**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check Go coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-go-coverage\", args: \"<every production .go file this bucket changed or
       added, comma-separated, e.g. internal/cli/list.go,internal/catalog/list.go>\\nbar: 80\\ncoverpkg: ./...\"}) in
       the Go module at <module dir> — container-free tests only (no integration tag). (Fallback: `go test -count=1
       -covermode=atomic -coverpkg=./... -coverprofile=build/coverage/cover.out ./...` and `go tool cover -func`.)
       Report back, per target file, its statement coverage % and, for files under 80%, the uncovered line ranges;
       state that branch coverage is not available in Go.",
     run_in_background: false
   })
   ```
   **Gate: every changed/added production file at ≥ 80% statement coverage.** For any file under it, add tests via the
   task that owns the file, re-run 3.3, re-check here. Genuine exceptions, each named in Step 5 with its reasoning:
   `cmd/<bin>/main.go` (a few lines of wiring — covered only through testscript re-execution, which counts, so it is
   usually fine), the TTY-only branch that starts a Bubble Tea program (covered up to the TTY gate), a second-signal
   `os.Exit` path, generated code. Logic that can only be reached through a real terminal or a container belongs behind
   an interface so a fast test can reach it — say so rather than writing tests chasing it.

5. **Full suite with the race detector**, via `iru-gate-runner`: `Agent({description: "Run full Go test suite with
   -race", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-go-test\", args: \"./...\\nrace: <yes
   from Step 1, else no>\"}) in the Go module at <module dir> (for a go.work workspace, once per module). (Fallback:
   `go test -count=1 -race ./...`.) Never the integration tag. If everything passes, report back only that and the
   count; otherwise only the failing test names, messages, and any `WARNING: DATA RACE` with the two goroutines' top
   frames."})`. Fix regressions and races and re-invoke until green. A race is always a defect in this bucket's code or
   tests (shared state between parallel tests counts) — never suppressed. When Step 1 found a terminal UI, add
   `\nui: yes` to the args and ask for the UI-capture summary too (directory cleared, captures per kind, malformed
   names, accessibility rule-id counts, level applied) — this unfiltered `./...` run is the full UI run that clears
   and refills `.ui-screenshots/`.

5a. **Terminal-UI captures and accessibility** — only when the bucket added or changed a TUI screen or component (a
   file in the TUI package, a sub-model, a delegate/render function); otherwise say "no UI changes" in Step 5. Using
   3.5's run (no extra test run):

   - **Coverage of the matrix**: for every screen/component the bucket added or changed (the type names from the
     tasks' `Screen › Region › Component` trees, cross-checked against the diff), list
     `.ui-screenshots/<screen|component>/<Id>/` (`find … -name '*.txt'`). Every visual state its doc comment lists
     must have a capture, and every captured `<state>` must exist for each `.ui-quality.yml` direction × each expected
     theme (Step 1). A missing combination or an Id with no directory → add the capture through the task that owns
     the component, re-run 3.3 and 3.5, re-check. Flows the bucket changed need their `flow/<name>/NN-<step>` steps.
   - **Malformed names** reported by the gate → fix the test that wrote them (the documentation skills ignore them).
   - **Accessibility at the `.ui-quality.yml` level**: `enforce` → a violation already failed a test in 3.5 — fix the
     component (never weaken the check); `report` → record the rule-id counts from the gate's summary for Step 5;
     `off` → say "accessibility checks off". These are the asserted TUI checks (`tui-color-only`, `tui-help-line`,
     `tui-keyboard`) — no automated audit exists for terminal UIs; say so.
   - Nothing here needs a terminal, device or container, so this stage is never "unverified" for environment
     reasons. A PNG next to a `.txt` is optional and never required.

6. **Code quality**, diffed against Step 1's baseline, via `iru-gate-runner`: `Agent({description: "Check for new Go
   quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-go-code-quality\", args: \"<production and test .go files this bucket touched>\\nbaseline: <baseline from Step
   1>\"}) in the Go module at <module dir> — golangci-lint v2 (with its gofmt/goimports formatters), go vet,
   govulncheck when installed. Report back only newly appearing issues (not in the baseline), each with rule id, file,
   and line, plus any tool that was missing or failed to run."})`. Any new issue is a regression this bucket
   introduced — trace it to its task, fix it, re-run 3.3 and 3.5, and re-check. Treat `gosec`, `errorlint`,
   `staticcheck` and any `govulncheck` finding reachable from code this bucket added or a module it upgraded as the most
   serious. Never add a `//nolint` to make a new issue disappear — except the narrow, documented cases in
   `iru-go-code-one-task`'s `reference/` (gosec G204 on an argv `exec.CommandContext`), each with a specific linter and a
   reason. Leave an issue only if fixing it would contradict what its task explicitly specifies, and record why. Skip if
   `iru-go-code-quality` isn't installed — then still run `gofmt -l <files>` and `go vet ./...` here.

7a. **Acquire the repository-wide lock for the container-backed stage** — immediately before 3.7, never earlier. Skip
   3.7a–3.7b entirely (no lock, no run) when:

   - **Step 1 found no `integration`-tagged test files** — the normal case for a CLI; say "no container-backed tests"
     in Step 5.
   - **The bucket touched nothing those tests exercise** (no package they import, no shared test helper) — say so.
   - **Docker is unavailable** (Step 1's probe, or `docker info` re-run now) — report the stage as **unverified
     (Docker unavailable)** and continue to Step 4. Never "skipped" without a reason, never "passed".

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     identifying text (task numbers/titles), "Go integration tests", the module directory, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the repository's root `.gitignore` (add the
     line if missing) — transient local state, never committed.
   - **`HELD`** — another group is running a container-backed stage. Tell the user this bucket is waiting (naming the
     holder from `cat .integration-test.lock/owner` if readable), then re-run the `mkdir` roughly every 30 seconds.
     Don't run the integration tests anyway.
   - **Stale lock** — `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory: its holder almost
     certainly died. Tell the user what the owner file says, break it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes: stop waiting, report the stage as
     **unverified — blocked by a concurrent integration run**, continue to Step 4, and name it in Step 5.

7. **Container-backed stage** — only now, with 3.1–3.6 clean and the lock held, as one gate call:
   ```
   Agent({
     description: "Run Go integration tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-go-test\", args: \"./...\\ntags: integration\\nlock: held\"}) in the Go module
       at <module dir> — the caller already holds .integration-test.lock, so do not acquire or release it. (Fallback:
       `go test -count=1 -tags integration ./...`.) Run exactly this one invocation; wait for it rather than
       backgrounding it, and don't run anything else that starts containers concurrently. Report back only: whether all
       tests passed; for each failure the test name and message; and separately whether any failure was an environment
       problem (Docker unavailable, image pull failure, container startup timeout, port in use) rather than an
       assertion failure.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another test-running agent.

   - **Environment failure** — report the stage as **unverified** with the cause, not as a code defect and not as
     passed. Never stop or remove a container this skill didn't start.
   - **Assertion failure** — trace it to its task and fix. Keep the lock across a quick fix-and-re-run; for a long
     debugging session release it (3.7b), fix, and re-acquire before re-running. If the fix touched production code,
     re-run 3.3–3.6 afterwards.

   Integration-test coverage is never quoted as the gate figure; the only coverage number this skill reports is 3.4's
   container-free figure.

7b. **Release the lock** as soon as 3.7's agent has reported back and any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release it on **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a hard
   stop. If you took the lock, you own releasing it before this skill returns.

## Step 4 — Finalize progress notes and notify the user

Confirm the lock is released (3.7b) if it was ever taken; release it now if a failure path skipped it.

Replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome, quoting
per-file coverage and the race/integration results:

```
- [x] Task 3. **Add `list` command with `--limit`/`--cursor`** — internal/cli/list.go (91%), internal/catalog/list.go
  (88%); list_test.go + list.txtar + list.golden.json; -race clean; no new golangci-lint issues; no container-backed tests.
- [x] Task 4. **Add order list screen** — internal/tui/orders.go (90%); UI: OrdersScreen + OrderList captured
  (5 states × ltr/rtl × no-color), accessibility enforce: 0 violations.
```

If a stage was unverified, say so with the reason — `integration unverified (Docker unavailable)`, `-race not run (cgo
unavailable)`, `govulncheck not installed` — silence reads as a pass. Leave blocked tasks unchecked with their blocker
note. Notify the user that group validation completed, with the bucket-wide result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The shape detected** (module/workspace, `go` directive, CLI framework, TUI, testscript package, integration-tagged
  tests or none, race detector availability, quality tools present) and the validation scope.
- **Per task**: packages/files touched, tests/scripts/golden files added or updated, per-file statement coverage, and the
  quality outcome; interface changes (commands, flags, exit codes, JSON fields/`schemaVersion`) and whether the generated
  CLI reference/man pages need regenerating; any `go.mod`/`go.sum` change.
- **The test results** (3.3, 3.5 incl. races found and fixed) and **the container-backed result** (3.7): passed,
  failed-and-fixed, **unverified** with the reason, or "no container-backed tests".
- **The UI stage** (3.5a): per added/changed screen/component the states × directions × themes captured (or the
  gaps fixed), malformed names, the accessibility level applied and its result (violations fixed under `enforce`,
  rule-id counts under `report`, "off"), and that the checks are asserted TUI checks rather than an automated audit
  — or "no UI changes".
- **Coverage exceptions accepted**, with reasoning; logic reachable only through a TTY or a container that should move
  behind an interface.
- **Golden files refreshed** in 3.3, each with the reason the output change was intended.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire (wait time, a broken stale lock and its
  owner text, a timeout) and confirmation it was released.
- Whether a new quality issue was left in place as unavoidable, whether license-header generation was skipped, whether
  a sequential-ordering deviation was applied in Step 2, and which task(s), if any, stopped on a blocker.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.

## Verification notes

The gate commands this skill relies on were exercised on 2026-10-16 in `$TMPDIR/iru-verify/cli/go-coding/greeter`
(outside this repository; macOS 27 arm64, Go 1.27.2, golangci-lint 2.15.0, govulncheck) against a Cobra + Bubble Tea v2
CLI shaped like the `iru-setup-go-cli` contract — see `iru-go-code-one-task`'s Verification notes for the module
versions:

- `go test -race ./...` (in-process Cobra tests, teatest v2, a testscript suite run through `TestMain` +
  `testscript.Main`) passed; the testscript package took ~5.5 s, the rest ~1 s each.
- `go test -covermode=atomic -coverpkg=./... -coverprofile=… ./...` merged into a 91% module total, and per-file
  figures included statements reached only through testscript's re-executed binary (`internal/cli/signal.go`) — the
  reason 3.4 measures per file from one whole-module run rather than per package (`./cmd/greeter` alone reported
  56.8% of `./...`).
- `golangci-lint run` (v2 config from `iru-go-code-one-task`'s `reference/code-style.md`) and `govulncheck ./...`
  reported 0 issues / no vulnerabilities; `golangci-lint config verify` accepted the config.
- Stage 3.5a (UI captures and accessibility, added for the UI contract) was written without a Go toolchain on the
  authoring machine: the capture-name validation it relies on (in `iru-go-test`) was exercised with Python, the Go
  side is unverified.
- **Not verified**: this orchestration skill was not dry-run end to end by its author, and the `iru-go-*` gate skills
  were being written concurrently — their args are used exactly as the shared contract fixes them (`iru-go-test`:
  packages, `run:`/`skip:`/`tags:`/`race:`/`lock: held`; `iru-go-coverage`: targets, `bar:`/`coverpkg:`/`tags:`;
  `iru-go-code-quality`: files, `baseline:`; `iru-go-godoc`: packages/files/identifiers). No repository with
  `integration`-tagged testcontainers-go tests was exercised, so the lock path (3.7a–3.7b) is unverified here; it is
  the same protocol the other group skills use. Linux and Windows hosts are unverified.
