---
name: iru-rust-code-one-task-group
description: "Invoked as /iru-rust-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Rust task bucket: one baseline, iru-rust-code-one-task per task (parallel when allowed), then validates once (license, rustdoc, tests, 80% coverage, clippy/deny, locked container tests); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(cargo *) Bash(rustup show *) Bash(rustup target list *) Bash(uname *) Bash(docker info *) Bash(command -v *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Rust)

Carry out every `rust` task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task. It is built on the Rust gate skills — `iru-rust-code-quality`, `iru-rust-test`, `iru-rust-coverage`,
`iru-rust-rustdoc` — each invoked through an `iru-gate-runner` agent so its report never lands in this context; fall
back to the explicit command shown only when a gate skill isn't installed in the consuming repository.

Three things shape the validation order:

1. **The coverage gate is per changed file, and end-to-end runs count.** cargo-llvm-cov instruments the binary that
   `assert_cmd` tests spawn, so `main.rs` and the command paths exercised end to end are measured — *as long as the
   tests forward `LLVM_PROFILE_FILE` after `env_clear()`* (verified: without it `main.rs` reads 0% and stray
   `default_*.profraw` files appear). A suspicious 0% on a file the end-to-end tests clearly exercise is checked for
   that first.
2. **Clippy is the bucket's linter, run once.** The per-task skill only `cargo check`s and formats its own files;
   clippy (with the crate's `[lints]` — typically a pedantic subset with `-D warnings` in CI), the workspace-wide
   `cargo fmt --check`, and `cargo deny` run here, diffed against one baseline.
3. **Only container-backed tests need the lock.** Plain CLI end-to-end tests spawn the binary with temp dirs and no
   fixed ports — they run in the normal test stages, in parallel. Tests marked `#[ignore = "…"]` that start Docker
   (testcontainers-rs) or touch a shared service run last, once, behind the repository-wide lock.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one container-backed run may be in flight against this repository at any moment** — enforced across buckets
by the lock (Step 3.7a), within this bucket by never running `#[ignore]`d tests from Step 2's per-task agents (the
per-task skill only compile-checks), and within the run by issuing that stage as a single gate call. The lock
directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same `.integration-test.lock`
every other group skill and `iru-rust-test` use, so a polyglot repository serializes them all together. Never hold
it while implementing (Step 2) or while running the container-free gates.

**Never both**: when this skill holds the lock it passes `lock: held` to `iru-rust-test`; when it doesn't, it must
not pass `lock: held`, and the gate takes the lock itself.

## Step 1 — Detect the shape and scope once, then capture one pre-change quality baseline

Detect once and reuse for every gate call below (the same facts `iru-rust-code-one-task` detects):

- **Workspace**: `cargo metadata --no-deps --format-version 1 --locked` — members, each package's targets
  (lib/bin/test/example/bench/`build.rs`), `features` (non-empty → the gates run with `features: all`, the CI
  convention), an `xtask` member (tooling — excluded from coverage) and its `.cargo/config.toml` alias.
- **CLI contract**: an exit-code enum mapped to `ExitCode`, `--format`/`--json`, an error envelope — and the
  end-to-end test files (`tests/cli.rs`, `tests/cmd/*.trycmd`), golden files (`tests/golden/`), insta snapshots.
  Check once that the end-to-end helper forwards `LLVM_PROFILE_FILE` when it calls `env_clear()`; if it doesn't, note
  it now — 3.4 will under-report `main.rs` and end-to-end-only paths, and the fix belongs in the bucket only if the
  bucket touches that helper (otherwise report it).
- **Container-backed tests**: `#[ignore = "…"]` tests whose reason or body points at Docker/testcontainers/a real
  service (`grep -rn "#\[ignore" --include=*.rs`), plus `testcontainers` in `[dev-dependencies]`. None → Step 3.7 is
  skipped and Step 5 says so. `#[ignore]`d tests for other reasons (slow, network) are listed, never run here.
- **Quality tools wired**: `[lints]`/`[workspace.lints]`, `clippy.toml`, `rustfmt.toml`, `deny.toml` (cargo-deny
  runs only when it exists), `rust-toolchain.toml` components.
- **Docs generation**: an `xtask` that writes committed generated docs (CLI reference `.adoc`/partial, man pages,
  completions) — look for its command in `.cargo/config.toml`, `xtask/src/main.rs`, the CI workflow, or a
  `README`/`CONTRIBUTING` note.
- **Terminal UI**: `ratatui` in any member's dependencies, the TUI module (`src/tui/`), its capture helper
  (`src/tui/ui_screenshots.rs`) and `.ui-quality.yml` at the repository root (missing (new UI project) = `accessibility: enforce`,
  `directions: [ltr, rtl]`, `themes: [light, dark]`). Note whether the TUI has a `Theme` with light/dark palettes —
  the themes 3.5a expects are the listed ones it defines plus `no-color` (only `no-color` without a palette).
- **Docker**: `docker info` now (only matters when container-backed tests exist), recorded for 3.7.
- **Bucket scope**, from each task's text (paths win; otherwise `iru-rust-code-one-task`'s Step 1 inference): the
  packages and files every task is expected to touch.

Record this once as the bucket's **validation scope**, then capture a single pre-change baseline:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-rust-code-quality\", args: \"<file1,file2,... every .rs file the bucket is
    expected to touch, plus Cargo.toml if a task changes dependencies>\"}). Report back only the per-tool (clippy,
    rustc lints, rustfmt, cargo-deny), per-rule-id issue counts and the issues for these files (rule id, file:line),
    plus which tools ran and which were not installed/configured.",
  run_in_background: false
})
```

Record it as the bucket's baseline — empty if every file is new. Step 3.6 diffs against it. Skip if
`iru-rust-code-quality` isn't installed, and say so in Step 5.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 2 — Implement each task via `iru-rust-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-rust-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-rust-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any relevant
      Current code state context>\"}). Report back: the shape detected, the packages and files touched, the tests
      added/updated (unit, end-to-end, TUI, doctests) and every golden/snapshot file created or changed, the
      compile-check result per package, generated artifacts that need regenerating, any deviation from the skill's
      reference/, and whether the task stopped on a blocker. Format only your own files and compile-check only — do
      not run tests, clippy, coverage, cargo deny, cargo doc, or a workspace-wide cargo fmt: sibling agents run
      concurrently and the group validates once.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**Ordering constraints Rust imposes regardless of the verdict** — run these tasks sequentially (provider first), and
note the deviation in Step 5:

- Two tasks editing the same file — especially `src/cli.rs` (the `Command` enum), `src/lib.rs` (the dispatch and the
  `mod` declarations), a `mod.rs`/parent module that declares new child modules, `src/exit.rs`/`src/output.rs`/
  `src/error.rs`, the `schema` command, a shared test helper, or one golden/snapshot file.
- Any task that changes `Cargo.toml` or `Cargo.lock` (a dependency, a feature, a workspace member) — Cargo rewrites
  the lock file and two concurrent edits lose one.
- One task's type, trait, error variant or `pub fn` used by another (declaration first).

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a
concurrent-write race), flip it yourself with the same "group validation pending" note. Notify the user that this
task's implementation and tests landed. A task that stopped on a blocker — or whose compile-check failed for a reason
outside its scope that a re-run didn't clear — is recorded and excluded from Step 3's validation, without blocking
the rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the scope first: the union of the files each agent reported, cross-checked against `git status`,
`git diff --name-only`, and `git diff --name-only --cached`. Drop `target/`, `*.profraw`, `Cargo.lock` (for the
source-file gates), and generated output. List production `.rs` files (by package), test files, golden/snapshot
files, and manifests separately. Then run, in this order — **3.1–3.6 gate the bucket; 3.7 runs only once they pass**.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args: \"<file1,file2,...>\"})
   scoped to every .rs file this bucket's tasks added or modified (sources, tests, examples, build.rs, xtask) — not
   Cargo.toml/Cargo.lock, golden files, snapshots, or generated output. Report back only which files were missing a
   header vs. fixed vs. already compliant, and — if no header convention existed anywhere in the repo — whether the
   user chose to skip or generate one."})`. Respect a skip choice for the rest of the run. Skip if
   `iru-check-license` isn't installed.

2. **Rustdoc**, via `iru-gate-runner`: `Agent({description: "Update rustdoc for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-rust-rustdoc\", args: \"<production .rs files (or item
   paths) this bucket added or modified, comma-separated — no tests/, no xtask unless the bucket changed it>\"}).
   Report back only which items were documented/completed and in which files, and whether `cargo doc --no-deps`
   with RUSTDOCFLAGS=\"-D warnings\" and `cargo test --doc` passed."})`. Fix and re-invoke until both pass. Skip if
   `iru-rust-rustdoc` isn't installed (fallback: run those two commands through `iru-gate-runner`). Then, **if the
   bucket changed the command tree** (`src/cli.rs`, a clap `Args`/`Subcommand`, help text) **and the repository
   commits generated CLI docs** (Step 1), regenerate them once via `iru-gate-runner` (`cargo xtask <docs command>`,
   reporting only which generated files changed) and include those files in the bucket's diff — never hand-edit
   them. A lot of backfilling here means the per-task skill was bypassed.

3. **Scoped tests**, via `iru-gate-runner`: `Agent({description: "Run scoped tests for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-rust-test\", args: \"<test-name filters covering every
   test this bucket added or changed and the modules it touched, e.g. commands::greet cli:: — or one expr: nextest
   filterset>\\npackage: <the package, when the bucket touched one member>\\nfeatures: <all, when the package
   declares features>\"}). (Fallback: `cargo test --locked -p <pkg> <filter>`.) If everything passes, report back
   only that, with the count run. If anything fails, report back only the failing test names, the assertion line
   and file:line for each, whether it was a build failure rather than a test failure, and whether a filter matched
   no tests."})`. Trace each failure to its task, fix, and re-invoke until green. A filter that matched nothing is a
   scoping mistake to fix, not a pass. End-to-end tests are part of this stage (no lock — they need no containers).

4. **Coverage — the 80% gate per changed source file**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-rust-coverage\", args: \"<every production .rs file this bucket changed or
       added, comma-separated, e.g. src/commands/list.rs,src/output.rs>\\nbar: 80\\npackage: <the package, when one
       member>\\nfeatures: <all, when the package declares features>\"}) — container-free tests only (no ignored
       tests), end-to-end binary runs included. Report back, per target file, line % (and region %), the uncovered
       line ranges for files under 80%, and any target the report doesn't contain at all (and why: not compiled,
       excluded as tooling).",
     run_in_background: false
   })
   ```
   **Gate: every changed/added production source file at ≥ 80% line coverage.** For anything under the bar, add tests
   via the task that owns the file (unit tests first; an end-to-end row when the uncovered lines are wiring), re-run
   3.3, re-check here. Accepted exceptions — each named in Step 5 with its uncovered lines, never silently:
   - `xtask/` and `build.rs` — dev tooling, reported "not measured".
   - `src/main.rs` — measured through the end-to-end tests; it is "not measured" only when the crate has no
     end-to-end tests of the binary **and** it is truly thin (gathers facts, calls the library, converts the exit —
     no branching of its own). A `main.rs` with logic under the bar is a finding: move the logic into the library.
   - Terminal-bound lines that need a real TTY — the `ratatui::run(...)`/event-loop function, a real-prompt adapter
     (`dialoguer` behind a `Confirm`-style trait) — when they are the *only* uncovered lines of a file that's still
     under the bar. Logic hiding next to them is not covered by this exception.
   - Code reachable only from `#[ignore]`d container-backed tests is **not** an exception — test it with a fake or
     record it as a shortfall.
   A suspicious 0% on a file the end-to-end tests exercise → check the `LLVM_PROFILE_FILE` forwarding (Step 1)
   before writing tests at it.

5. **Full suite including doctests**, via `iru-gate-runner`: `Agent({description: "Run full test suite",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-rust-test\", args: \"features: <all when
   features exist>\"}) with no filters — every workspace member, unit + integration + end-to-end tests and doctests,
   ignored tests excluded. (Fallback: `cargo test --workspace --locked` then `cargo test --doc --workspace
   --locked`.) If everything passes, report back only that, with counts per target kind; otherwise only the failing
   test names, assertion lines and file:line, and whether any failure is a doctest."})`. Fix regressions and
   re-invoke until green. When Step 1 found a terminal UI, add `\nui: yes` to the args and ask for the UI-capture
   summary too (directory cleared, captures per kind, malformed names, accessibility rule-id counts, level applied) —
   this unfiltered run is the full UI run that clears and refills `.ui-screenshots/`.

5a. **Terminal-UI captures and accessibility** — only when the bucket added or changed a TUI screen or component
   (a file in the TUI module, a `Widget`/`StatefulWidget` impl, a screen's key handling); otherwise say "no UI
   changes" in Step 5. Using 3.5's run (no extra test run):

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
     `tui-keyboard`, `tui-no-color`) — no automated audit exists for terminal UIs; say so.
   - Nothing here needs a terminal, device or container, so this stage is never "unverified" for environment
     reasons. A PNG next to a `.txt` is optional and never required.

6. **Code quality — clippy, rustfmt, cargo-deny**, diffed against Step 1's baseline, via `iru-gate-runner`:
   `Agent({description: "Check for new quality issues in task group", subagent_type: "iru-gate-runner", prompt:
   "Invoke Skill({skill: \"iru-rust-code-quality\", args: \"<every .rs file this bucket added or modified, tests
   included, comma-separated>\\nbaseline: <baseline from Step 1>\"}). Report back only newly appearing issues (not in
   the baseline), each with rule id (clippy::<lint>, rustc lint name, rustfmt, cargo-deny:<check>:<code>), file and
   line, how many would fail a CI that denies warnings, and any tool that was not installed or failed to run."})`.
   Any new issue is a regression this bucket introduced — trace it to its task, fix it (rustfmt issues: run
   `cargo fmt --all` once now that no sibling is editing), re-run 3.3 and 3.5, and re-check. Never add `#[allow]`, a
   `[lints]` relaxation, a `clippy.toml` threshold change, or a `deny.toml` exception to make a new issue disappear;
   a narrowly scoped `#[expect(lint, reason = "…")]` is acceptable only where the task's own specification forces the
   pattern, recorded in Step 5. A new `cargo-deny` finding from a dependency a task added is reported with the
   crate and advisory/license — a plan-level decision. Skip if `iru-rust-code-quality` isn't installed (fallback:
   `cargo clippy --all-targets --all-features --locked -- -D warnings` and `cargo fmt --all --check` through
   `iru-gate-runner`).

7a. **Acquire the repository-wide lock for the container-backed stage** — immediately before 3.7, never earlier.
   Skip 3.7a–3.7b entirely (no lock, no run) when:

   - **Step 1 found no container-backed `#[ignore]`d tests** — say so in Step 5 ("no container-backed tests").
   - **The bucket touched nothing those tests exercise** (no file in the modules they call, no change to the
     persistence/client code they cover) — say so in Step 5.
   - **Docker is unavailable** (Step 1's probe, or `docker info` re-run now) — report the stage as **unverified
     (Docker unavailable)** and continue to Step 4. Never "skipped" without a reason, never "passed".

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     identifying text (task numbers/titles), "Rust container-backed tests", the package, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the repository's root `.gitignore` (add the
     line if missing) — transient local state, never committed.
   - **`HELD`** — another group is running a container-backed stage. Tell the user this bucket is waiting (naming the
     holder from `cat .integration-test.lock/owner` if readable), then re-run the `mkdir` roughly every 30 seconds.
     Don't run the tests anyway.
   - **Stale lock** — `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory: its holder almost
     certainly died. Tell the user what the owner file says, break it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes: stop waiting, report the stage as
     **unverified — blocked by a concurrent integration run**, continue to Step 4, and name it in Step 5.

7. **Container-backed tests** — only now, with 3.1–3.6 clean and the lock held, as one gate call:
   ```
   Agent({
     description: "Run container-backed Rust tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-rust-test\", args: \"<filters naming the container-backed tests, or
       expr:<filterset>>\\nignored: only\\npackage: <pkg>\\nfeatures: <all when features exist>\\nlock: held\"}) —
       the caller already holds .integration-test.lock, so do not acquire or release it. Run exactly this one
       invocation; wait for it rather than backgrounding it. Report back only: whether all selected tests passed; for
       each failure the test name, assertion line and file:line; separately any environment problem (Docker
       unavailable, image pull failure, container start timeout, port in use) as opposed to an assertion failure;
       and whether the filters matched no tests.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another test-running agent. Filters matter: `ignored:
   only` alone would also run `#[ignore]`d tests that need the network or are merely slow.

   - **Environment failure** — report the stage as **unverified** with the cause, not as a code defect and not as
     passed. Never stop or remove a container this skill didn't start.
   - **A filter that matched nothing** — fix the selection and re-run; never record it as passed.
   - **Assertion failure** — trace it to its task and fix. Keep the lock across a quick fix-and-re-run; for a long
     debugging session release it (3.7b), fix, and re-acquire before re-running. If the fix touched production code,
     re-run 3.3–3.6 afterwards.

7b. **Release the lock** as soon as 3.7's agent has reported back and any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release it on **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a hard
   stop. If you took the lock, you own releasing it before this skill returns.

Coverage from container-backed tests is never measured or quoted as a gate figure; the only coverage numbers this
skill reports are 3.4's container-free figures.

## Step 4 — Finalize progress notes and notify the user

Confirm the lock is released (3.7b) if it was ever taken; release it now if a failure path skipped it — this skill
must never return while holding it.

Replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome, quoting the
per-file coverage and the container-backed result separately:

```
- [x] Task 3. **Add `list` command with `--limit`/`--cursor`** — src/commands/list.rs (94% lines), src/cli.rs (100%);
  9 e2e cases + golden list-default.json; no new clippy/rustfmt/cargo-deny issues; container tests: none in repo.
- [x] Task 4. **Add order list screen** — src/tui/orders.rs (91% lines); UI: OrdersScreen + OrderRow captured
  (5 states × ltr/rtl × no-color), accessibility enforce: 0 violations.
```

If a stage was unverified, say so with the reason — `container tests unverified (Docker unavailable)` or
`… (lock held by another group)` is actionable; silence reads as a pass. Leave blocked tasks unchecked with their
blocker note. Notify the user that group validation completed, with the bucket-wide result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The shape detected** (workspace/packages, features, CLI contract or not, end-to-end test style, container-backed
  tests, quality tools wired, docs generator) and the validation scope.
- **Per task**: packages/files touched, tests added/updated, golden/snapshot files changed (and whether a JSON change
  was additive), per-file coverage, and the quality outcome.
- **Coverage exceptions accepted**, each with its uncovered lines and reasoning (tooling, thin `main.rs`,
  terminal-bound lines), and any `LLVM_PROFILE_FILE` forwarding problem found.
- **The results** of 3.3 and 3.5 (doctests included), the generated-docs regeneration (files changed, or "no
  command-tree change"), and **the container-backed result** (3.7): passed, failed-and-fixed, **unverified** with
  the reason, or "no container-backed tests" — never let "unverified" read as "passed".
- **The UI stage** (3.5a): per added/changed screen/component the states × directions × themes captured (or the
  gaps fixed), malformed names, the accessibility level applied and its result (violations fixed under `enforce`,
  rule-id counts under `report`, "off"), and that the checks are asserted TUI checks rather than an automated audit
  — or "no UI changes".
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire (wait time, a broken stale lock and its
  owner text, a timeout) and confirmation it was released.
- Whether a new quality issue was left in place (with the `#[expect]` reason), any new `cargo-deny` finding from an
  added dependency, whether license-header generation was skipped, whether a sequential-ordering deviation was
  applied in Step 2, and which task(s), if any, stopped on a blocker.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.

## Verification notes

This orchestration skill was not dry-run end to end by its author (the gate skills were written concurrently). What
was verified, in `$TMPDIR/iru-verify/cli/rust-coding/` with Rust 1.99.1, cargo-nextest 0.9.148 and cargo-llvm-cov
0.9.1, against a clap + Ratatui CLI crate built to `iru-rust-code-one-task`'s reference (see that skill's notes):

- The underlying commands the gates wrap all ran green on it: `cargo nextest run` (54 tests), `cargo test --doc`,
  `cargo llvm-cov` (per-file line % from the summary), `cargo clippy --all-targets --all-features --locked -- -D
  warnings`, `cargo fmt --check`, `RUSTDOCFLAGS="-D warnings" cargo doc --no-deps`.
- The coverage facts this skill's gate relies on: end-to-end tests spawning the binary through
  `env!("CARGO_BIN_EXE_<bin>")` **do** count — `main.rs` went from 0% to 100% once the test helper forwarded
  `LLVM_PROFILE_FILE` after `env_clear()`; the only lines left uncovered in the TUI files were the `ratatui::run`
  call and the event loop (`browse.rs` 86.8%, `tui/mod.rs` 91.5%), which is why that exception is narrowly worded.
- Concurrent `cargo check` runs from parallel agents serialize on Cargo's build-directory lock ("Blocking waiting for
  file lock") rather than failing; concurrent edits to `Cargo.toml`/`Cargo.lock` are why dependency-changing tasks
  are forced sequential.
- Stage 3.5a (UI captures and accessibility, added for the UI contract) was written without a Rust toolchain on the
  authoring machine: the capture-name validation it relies on (in `iru-rust-test`) was exercised with Python, the
  Rust side is unverified.
- **Unverified locally**: the lock path with real testcontainers-rs tests (no container-backed tests in the
  verification crate), the `xtask` docs regeneration step (owned by the scaffold), cargo-deny findings in a diff, and
  Windows/Linux hosts (only cross-`cargo check`ed from macOS).
