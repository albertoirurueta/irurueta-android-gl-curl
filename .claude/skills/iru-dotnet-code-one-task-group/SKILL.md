---
name: iru-dotnet-code-one-task-group
description: "Invoked as /iru-dotnet-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one .NET/C# (not Windows desktop) task bucket: one quality baseline, iru-dotnet-code-one-task per task (parallel when allowed), then validates the bucket once (license, XML docs, tests, 80% coverage, quality, CLI AOT and UI stages) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(dotnet *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(uname *) Skill Agent
---

# Implement one task-group bucket (.NET)

Carry out every .NET/C# task in one plan-group bucket end to end: capture one quality baseline for the whole
bucket up front, implement each task (in parallel where safe), then validate everything the bucket touched in a
single consolidated pass instead of once per task — this is what cuts the token cost and wall-clock time this
catalog's per-task validation used to spend redundantly. It is the .NET/C# counterpart to
`iru-java-code-one-task-group` — same shape, but built on `iru-dotnet-code-quality`, `iru-dotnet-test`, `iru-dotnet-coverage`, and
`iru-dotnet-docfx`. It uses a medium model on purpose: the plan already carries the hard reasoning; this skill drives
execution of it.

**Windows desktop app repositories are out of scope.** If a `*.csproj` in the solution has `<OutputType>WinExe</OutputType>`
with `<UseWPF>`, `<UseWinUI>`, or `<UseWindowsForms>` set to `true` (`iru-explore`'s `Project type: windows-app`, plan
key `windows`), stop and hand the bucket to `iru-windows-code-one-task-group` instead (or tell the caller to, when it
isn't installed). The gate skills this skill calls are Windows-desktop aware on their own (`iru-dotnet-test` excludes
`[TestCategory("UI")]` tests by default and skips `net*-windows*` test projects off Windows), so a plain .NET
repository that merely contains a small WinForms/WPF tool still validates correctly here — but its UI tests are never
run by this skill.

**Command-line tools (CLI shape).** Detect it once, before Step 1: a `*.csproj` with `<OutputType>Exe</OutputType>`,
none of `UseWPF`/`UseWinUI`/`UseWindowsForms`, and a `System.CommandLine` (or `Spectre.Console.Cli`) reference or
`PackAsTool`/`PublishAot` — in the `iru-setup-dotnet-cli` layout `src/<Tool>/` (assembly `<bin>`), `src/<Tool>.Core/`,
`tests/<Tool>.Core.Tests/`, `tests/<Tool>.Cli.Tests/`, `tests/coverlet.runsettings` (`Include [<Tool>.Core]*`). When it
applies, Step 3 changes as follows (everything else is unchanged):

- **Coverage (3.4)** targets only the bucket's changed/new classes in `src/<Tool>.Core`, measured from
  `tests/<Tool>.Core.Tests` with the repository's runsettings; changed classes in `src/<Tool>` (commands, the
  System.CommandLine wiring) are reported "not measured" — they are covered behaviourally by `tests/<Tool>.Cli.Tests`,
  never by lowering the bar. If logic that can be unit-tested landed in `src/<Tool>`, move it to Core (via the task that
  owns it) instead of exempting it.
- **Scoped tests (3.3) and the full suite (3.5)** include `tests/<Tool>.Cli.Tests`, which runs the real command tree
  in-process. Its `[TestCategory("PublishedBinary")]` tests are inconclusive (skipped) unless `<BIN>_BINARY` is set —
  that is expected, not a failure; 3.7 runs them against the published binary.
- **3.7 Native AOT publish check** — only when the bucket touched `src/<Tool>`, `src/<Tool>.Core`,
  `Directory.Build.props`, `Directory.Packages.props` or `global.json`. Resolve the host RID from `uname -sm`
  (`Darwin arm64` → `osx-arm64`, `Darwin x86_64` → `osx-x64`, `Linux x86_64` → `linux-x64`, `Linux aarch64` →
  `linux-arm64`; on Windows `win-x64`/`win-arm64` from `PROCESSOR_ARCHITECTURE`) and delegate:
  `Agent({description: "Native AOT publish check for task group", subagent_type: "iru-gate-runner", prompt: "Run
  `dotnet publish src/<Tool> -c Release -r <host-rid> -o <scratch dir outside the repository>` (a full project build).
  Report back only: pass/fail, every `warning`/`error` line with an IL2xxx/IL3xxx/NETSDK code verbatim (file:line),
  and the published executable's path and size. Then, if it passed, run `<BIN>_BINARY=<path> dotnet test
  tests/<Tool>.Cli.Tests -c Release --filter TestCategory=PublishedBinary` and report its pass/fail counts."})`. Any
  IL trim/AOT warning is a regression even if the build "succeeded" (a package-level warning such as IL2104/IL3053 can
  appear only at publish): trace it to the task that introduced the reflection, dynamic code or incompatible package,
  fix it there (source-generated JSON, generics, explicit registration — never a blanket `NoWarn`/
  `UnconditionalSuppressMessage`), and re-run 3.3–3.7. A missing native toolchain (Xcode command-line tools,
  `clang`/`zlib1g-dev`, the MSVC workload) makes this check "unverified" — report it, never "passed". Verified on
  macOS arm64 (osx-arm64) and Linux arm64 (the `mcr.microsoft.com/dotnet/sdk:10.0-noble-aot` image) on 2026-10-16;
  Windows hosts follow the same command but were not run.
- **3.8 Generated CLI reference** — only when a task added or changed a command, argument or option: run
  `dotnet run --project src/<Tool> -c Release -- gen-docs --output-dir .` and check `git diff --exit-code --
  docs/modules/ROOT/pages/cli-reference.adoc`; if it changed, keep the regenerated page as part of the bucket and say
  so in Step 5 (never hand-edit it).
- **3.9 Terminal UI stage** — only when the bucket touched the terminal UI (`src/<Tool>/Tui/`, a `Terminal.Gui` view
  or the command hosting it, or Core state a view renders). The full suite (3.5) excludes `[TestCategory("UI")]`
  tests, so run them here: `Agent({description: "Run terminal UI tests for task group", subagent_type:
  "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-test\", args: \"ui\nproject: tests/<Tool>.Cli.Tests\"}).
  Report back only: pass/fail counts, each failure's test name and first message line, whether the filter matched no
  tests, and the UI-contract summary — captures per kind and per <kind>/<Id>, malformed names, and the
  .ui-quality.yml level applied with its accessibility violations (count + rule ids)."})`. Then, from that summary and the tasks' reported `Screen › Region › Component`
  trees, check that every view the bucket added or changed has a `.txt` capture for each of its visual states × each
  `.ui-quality.yml` `directions` entry (missing file → `[ltr, rtl]`); a gap is a missing test in the owning task — add
  it and re-run. Accessibility is the asserted terminal checks (`tui-color-only`, `tui-help-line`, `tui-keyboard` —
  no automated audit exists for terminals) at the `.ui-quality.yml` level: `enforce` → they failed their tests, fix
  the view and re-run; `report` → list the `a11y-report.txt` violations in Step 5; `off` → say so. No UI tests at all
  for a changed view → "terminal UI untested", never a pass.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Capture one pre-change quality baseline for the whole bucket

Before implementing anything, collect the set of type(s) every task in this bucket is expected to touch (from
each task's own description). Capture a single pre-change quality baseline covering all of them by delegating to
the `iru-gate-runner` agent rather than running `iru-dotnet-code-quality` directly: `Agent({description: "Capture
pre-change quality baseline for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
\"iru-dotnet-code-quality\", args: \"<TypeName1,TypeName2,...>\"}), then report back only the list of issues found for
these types."})`. `iru-gate-runner` runs in its own separate context and reports back just the issue list, keeping
unneeded report content out of the main context window. Record the returned issues as this bucket's pre-change
baseline — an empty baseline if every file is new. This is what Step 4's quality check compares against, so it
flags only issues this bucket's tasks introduce, not pre-existing ones. Skip this baseline capture if the
`iru-dotnet-code-quality` skill is unavailable in this repository.

## Step 2 — Implement each task via `iru-dotnet-code-one-task`

For every task in the bucket, invoke `iru-dotnet-code-one-task` — it implements exactly what the task specifies and
writes/updates its tests, nothing more (license headers, doc comments, and all validation now live here instead).

- **Bucket marked `Parallelizable: yes`**: invoke `iru-dotnet-code-one-task` for every task in the bucket
  concurrently — issue all of the `Agent` calls below together, in the same response, so they run in parallel
  rather than one after another:
  ```
  Agent({
    description: "Implement <task N> via dotnet-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-dotnet-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the files touched, the tests added/updated, and
      whether the task stopped on a blocker instead of finishing.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time, in the plan's order, waiting for each to
  finish before starting the next — the plan marked this bucket non-parallel because its tasks have a real
  ordering dependency (e.g. one task's code depends on another's, or two tasks would touch the same file).

`iru-dotnet-code-one-task` already checks off that task's own checkbox (and its sub-tasks') in
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
2. **XML doc comments**, for every type any task in the bucket added or modified, by delegating to `iru-gate-runner`:
   `Agent({description: "Update XML doc comments for task group", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-dotnet-docfx\", args: \"<TypeName1,TypeName2,...>\"}) scoped to every type this bucket's tasks
   added or modified. Report back only whether doc comments were added/updated and for which members, and
   whether the DocFX build verification passed (or was skipped because `docfx` isn't installed)."})`. If it
   reports the build failed, fix the reported issues and re-invoke until it reports success or a clean skip. Skip
   this item if `iru-dotnet-docfx` is unavailable in this repository.
3. **Scoped tests**, for every type affected across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Run tests for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-test\", args:
   \"<comma/wildcard selector covering every test class affected by this bucket>\"}) (fall back to `dotnet test
   --filter \"FullyQualifiedName~<...>\"` directly if the dotnet-test skill is unavailable). If everything passes,
   report back only that all tests passed. If anything fails, report back only the failing test names, the
   failure reason, and the stack trace for each."})`. If failures are reported, identify which task's code they
   trace back to (by file/class), fix that task's implementation and/or tests, and re-invoke this same check —
   repeat until it reports all tests passed. Don't move on with a red test.
4. **Coverage**, for every changed/new class across the bucket, by delegating to `iru-gate-runner`: `Agent({description:
   "Check coverage for task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-coverage\",
   args: \"<selector covering the bucket's affected tests, plus every target class this bucket changed or
   added>\"}) (fall back to `dotnet test --filter ... --collect:\"XPlat Code Coverage\"` and reading
   `coverage.cobertura.xml` if unavailable). Report back, per target class, its line coverage percentage and
   branch coverage percentage."})`. For any class under 80%, add tests for its uncovered branches/lines (via the
   task that owns that class), re-run Step 3.3 to confirm, then re-check here.
5. **Full suite**, to catch regressions this bucket's changes may have caused elsewhere, by delegating to
   `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt: "Invoke
   Skill({skill: \"iru-dotnet-test\"}) with no selector (fall back to `dotnet test` if unavailable — it skips test
   projects that can't run on this host and excludes UI-category tests). If everything passes, report back only that all tests passed. If anything fails, report back only the
   failing test names, the failure reason, and the stack trace for each."})`. Fix any regression, then re-invoke
   until it reports all tests passed.
6. **Code quality**, for the same type set as Step 1, by delegating to `iru-gate-runner`: `Agent({description: "Check
   for new quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-dotnet-code-quality\", args: \"<TypeName1,TypeName2,...>\"}). Compare the reported issues against this
   pre-change baseline: <baseline from Step 1>. Report back only the issues that are newly appearing (not present
   in the baseline)."})`. Any issue reported is a regression introduced by this bucket — trace it to the task
   that owns the affected type, fix it (then re-run Steps 3.3–3.5 to confirm the fix didn't break anything), and
   re-check until none remain. Leave a new issue in place only if fixing it is genuinely unavoidable (e.g. it
   would contradict what its task explicitly specifies) — record that, and why, for Step 4. Skip this item if
   `iru-dotnet-code-quality` is unavailable in this repository.
7. **Native AOT publish check**, 8. **generated CLI reference** and 9. **terminal UI stage** — CLI shape only, under
   the conditions and with the commands given in "Command-line tools (CLI shape)" above.

## Step 4 — Finalize progress notes and notify the user

Once Step 3 passes clean for the bucket (or leaves only explicitly-accepted unavoidable issues), update each
task's note in `implementation_plan.md` — replacing the "group validation pending" placeholder from Step 2 — with
the final outcome: coverage achieved and code-quality result for that task's own class(es), e.g. `- [x] Task 2.
**Implement `SimpleWidget`** — tests in SimpleWidgetTests (94% line coverage), no new StyleCop/CA issues.` Save
the file, then notify the user that group validation completed and summarize the bucket-wide result (tests,
coverage, quality, doc/license outcome).

If any task was recorded as blocked in Step 2, leave its checkbox unchecked and its blocker note in place — do
not run Step 3's validation against a task that never finished implementing.

## Step 5 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
files touched, tests added/updated, coverage achieved, and code-quality outcome — including whether a new issue
was left in place as unavoidable and why, the terminal UI stage's result when it ran (UI tests, captures per view
and any missing state × direction, malformed names), whether license-header generation was skipped by the user, and which
task(s), if any, stopped on a blocker (with enough detail for the caller to surface it further up).
`implementation_plan.md`'s checkboxes are already checked (by `iru-dotnet-code-one-task` itself, backfilled here
in Step 2 if needed) and finalized with their validation outcome (Step 4), and the user-facing progress
notifications have already gone out (Steps 2 and 4) — the caller does not need to redo that bookkeeping for this
bucket.
