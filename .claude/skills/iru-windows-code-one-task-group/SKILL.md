---
name: iru-windows-code-one-task-group
description: "Invoked as /iru-windows-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one Windows desktop (.NET 10) task bucket: one baseline, iru-windows-code-one-task per task (parallel when allowed), then validates once (license, XML docs, Core tests, 80% Core coverage, analyzers, locked FlaUI UI (Windows only)); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(dotnet *) Bash(uname *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Windows desktop app)

Carry out every `windows` task in one plan-group bucket end to end: one analyzer baseline up front, implement each
task (in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of
once per task. It is the Windows desktop counterpart to `iru-dotnet-code-one-task-group`, built on the same .NET gate
skills — `iru-dotnet-code-quality`, `iru-dotnet-test`, `iru-dotnet-coverage`, `iru-dotnet-docfx` — each invoked through
an `iru-gate-runner` agent so its report never lands in this context; fall back to the explicit command shown only
when a gate skill isn't installed in the consuming repository.

Three things shape the validation order:

1. **Coverage is measured on Core only.** `src/<App>.Core` (plain `net10.0`) holds the logic and runs anywhere; the
   app project (XAML, code-behind, forms, UI services) is reported "not measured" and never fails the 80% gate.
2. **Part of the solution only runs — or, for WinUI, only builds — on Windows.** The cheap, portable gates run first
   everywhere; the UI stage runs only on a Windows host. A non-Windows host reports it "unverified (needs Windows)".
3. **The UI stage drives the real desktop and goes last, behind the lock.** FlaUI launches the app and moves the
   mouse/keyboard in the interactive session; two overlapping runs fight over focus and windows. This skill takes the
   repository-wide `.integration-test.lock` once around the whole UI stage (Step 3.7) and passes `lock: held` to the
   gate; cheaper gates run first so a bucket that's red on Core never holds the lock.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything that drives the desktop

**Only one desktop-UI (or other integration) run may be in flight against this repository at any moment** —
enforced across buckets by the lock (Step 3.7a), within this bucket by never running UI tests from Step 2's per-task
agents (the per-task skill only compile-checks), and within the run by issuing the UI stage as a single gate call. The
lock directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same `.integration-test.lock`
every other group skill in this catalog uses (`iru-java-springboot-code-one-task-group`,
`iru-kmp-code-one-task-group`, `iru-react-native-code-one-task-group`, …), so a polyglot repository serializes them
all together. Never hold it while implementing (Step 2) or while running the Core gates.

**Never both**: when this skill holds the lock it passes `lock: held` to `iru-dotnet-test`; when it doesn't, it must
not pass `lock: held`, and the gate takes the lock itself.

## Step 1 — Detect the shape and scope once, then capture one pre-change analyzer baseline

Detect once and reuse for every gate call below (the same facts `iru-windows-code-one-task` detects):

- **Projects**: the solution (`*.slnx`/`*.sln`); the app project(s) (`WinExe` + `UseWPF`/`UseWinUI`/
  `UseWindowsForms`) → `ui`; the Core project (`net10.0`, referenced by the app); `tests/<App>.Core.Tests`;
  `tests/<App>.UITests` (any `net*-windows*` test project, normally FlaUI with `[TestCategory("UI")]`).
- **Architecture**: `ViewModels/` (MVVM) or `Controllers/` + `Views/I<X>View.cs` (MVC) in Core.
- **Host**: Windows (`uname -s` absent or `MINGW*`/`MSYS*`/`CYGWIN*`, or `$OS` = `Windows_NT`) or not. Off Windows:
  the UI stage and any WinUI app build are **unverified (needs Windows)** — decide it now so every note says so
  consistently. A Windows host without an interactive desktop session (a service account, a Server Core container)
  also makes the UI stage unverified, with that reason.
- **Bucket scope**, from each task's text (paths win; otherwise `iru-windows-code-one-task`'s Step 1 inference), and
  the **gates it needs**:

  | Bucket touched | Core gates (tests, coverage, quality) | UI stage (Windows only) |
  |---|---|---|
  | Core (`src/<App>.Core`, `tests/<App>.Core.Tests`) | yes — coverage gate on the changed Core classes | only if a view binds to a changed view-model member (WPF `{Binding}` paths aren't compile-checked), a UI-state record a component renders changed, or the task added a UI test |
  | WPF/WinForms app project (XAML, code-behind, forms, app services) | quality + compile (`EnableWindowsTargeting` off Windows); coverage: "not measured" | yes |
  | WinUI app project | quality + compile on Windows only, else **unverified (needs Windows)**; coverage: "not measured" | yes |
  | `tests/<App>.UITests` | compile only | yes |
  | `Package.appxmanifest` / `packaging/` / publish profiles | — (packaging is verified by `release.yml` on Windows; report "packaging unverified" off Windows) | — |

Record this once as the bucket's **validation scope** and reuse it. When the UI stage is in scope, also record the
bucket's **UI inventory** from the tasks' reported `Screen › Region › Component` trees (and, failing that, the
windows/pages/forms/`UserControl`s the diff added or changed): each screen/component `Id` with its visual states, plus
`.ui-quality.yml` at the repository root (`accessibility`, `directions`, `themes`; missing (new UI project) file → `enforce`,
`[ltr, rtl]`, `[light, dark]`). Step 3.7c checks captures against it.

Then collect the files every task is expected to touch and capture a single pre-change baseline via `iru-gate-runner`:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-dotnet-code-quality\", args: \"<file1,file2,... or TypeName1,TypeName2,...>\"}).
    Report back only the per-tool (StyleCop SA/SX, CA, MSTEST, CS/IDE), per-rule-id issue counts and the issues for
    these files, plus which projects were analyzed and which were skipped as not analyzable on this host (a WinUI
    project off Windows).",
  run_in_background: false
})
```

Record it as the bucket's baseline — empty if every file is new. Step 3.6 diffs against it. Skip if
`iru-dotnet-code-quality` isn't installed, and say so in Step 5.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 2 — Implement each task via `iru-windows-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-windows-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-windows-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any relevant
      Current code state context>\"}). Report back: the shape detected (ui, architecture, host), the projects and
      files touched, the tests added/updated per test project, the compile-check result per project, bindings or DI
      registrations only a run can verify, any deviation from the skill's reference/, and whether the task stopped on
      a blocker. Compile-check only — do not run tests, coverage, analyzers reports, launch the app, or run UI tests:
      sibling agents run concurrently and the group's UI stage is serialized behind a repository-wide lock.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**One ordering constraint regardless of the verdict**: tasks where one adds a Core interface/view model/view interface
and another implements or binds to it (a service implementation in the app project, a XAML view, a form implementing
`I<X>View`), or two tasks editing the same XAML file, `*.Designer.cs`, `App.xaml(.cs)`/`Program.cs` DI registration,
`Directory.Packages.props`, or MSIX manifest, run sequentially (declaration first). Check before fanning out; if the
group was marked parallelizable, run just those sequentially and note the deviation in Step 5.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a
concurrent-write race), flip it yourself with the same "group validation pending" note. Notify the user that this
task's implementation and tests landed. A task that stopped on a blocker — or whose compile-check failed for a reason
outside its scope that a re-run didn't clear — is recorded and excluded from Step 3's validation, without blocking
the rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the file scope first: the union of the files each agent reported, cross-checked against `git status`,
`git diff --name-only`, and `git diff --name-only --cached`. Drop generated output (`bin/`, `obj/`, `*.g.cs`,
`TestResults/`, `build/`). Then run, in this order — **3.1–3.6 gate the bucket; 3.7 runs only once they pass**.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args: \"<file1,file2,...>\"})
   scoped to every C#, XAML, and PowerShell file this bucket's tasks added or modified (not *.Designer.cs, *.g.cs,
   *.csproj, or manifests). Report back only which files were missing a header vs. fixed vs. already compliant, and —
   if no header convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect
   a skip choice for the rest of the run. Skip if `iru-check-license` isn't installed.

2. **XML doc comments**, via `iru-gate-runner`: `Agent({description: "Update XML doc comments for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-docfx\", args: \"<TypeName1,TypeName2,...
   — Core and app-project types this bucket added or modified, no test classes>\"}). Report back only which members
   were documented/completed and in which files, and whether the DocFX build (root docfx.json, metadata from the Core
   project) passed, was skipped, or was unverified for a WinUI project off Windows."})`. Fix and re-invoke until the
   build passes. Skip if `iru-dotnet-docfx` isn't installed.

3. **Scoped Core tests**, via `iru-gate-runner`: `Agent({description: "Run Core tests for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-test\", args: \"<FullyQualifiedName~ selector covering
   every test class affected by this bucket>\\nproject: tests/<App>.Core.Tests\"}). (Fallback: `dotnet test
   tests/<App>.Core.Tests --filter \"(FullyQualifiedName~<...>)&TestCategory!=UI\"`.) If everything passes, report back
   only that, with the count run. If anything fails, report back only the failing test names, the failure reason, and
   the first stack-trace line for each, and say whether the filter matched no tests."})`. Trace each failure to its
   task, fix, and re-invoke until green. A filter that matched nothing is a scoping mistake to fix, not a pass. Skip
   (and say so) when the bucket touched no Core code and added no Core tests.

4. **Coverage — the 80% gate on Core**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check Core coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-dotnet-coverage\", args: \"FullyQualifiedName~<Core root namespace,
       e.g. Bookshelf.Core>\\n<Core ClassName1,ClassName2,... this bucket changed or added>\\nproject:
       tests/<App>.Core.Tests\\nbar: 80\"}) — positional selector first (the whole Core test namespace, so every test
       class exercising a target class counts; the skill ANDs TestCategory!=UI), then the target classes, then the
       key: value lines. Use tests/coverlet.runsettings when present.
       Report back, per target class, line and branch coverage percentage and uncovered line numbers for classes under
       80%; list separately every app-project (UI layer) type in this bucket as not measured.",
     run_in_background: false
   })
   ```
   **Gate: every changed/added Core class at ≥ 80% line coverage** (branch coverage is reported, not gated). App-project
   types are "not measured" and never fail the gate — but if one holds real logic (a branchy converter, a service
   implementation with rules), say so in Step 5: that logic belongs in Core behind an interface. For anything under the
   bar, add tests via the task that owns the class, re-run 3.3, re-check here.

5. **Full suite excluding UI tests**, via `iru-gate-runner`: `Agent({description: "Run full non-UI test suite",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-dotnet-test\"}) with no selector — every test
   project that can run on this host, with TestCategory=UI excluded by default. (Fallback: `dotnet test
   tests/<App>.Core.Tests --filter \"TestCategory!=UI\"`, and on Windows also the solution.) If everything passes,
   report back only that, plus which test projects were skipped as not runnable on this host; otherwise only the
   failing test names, reasons, and first stack-trace lines."})`. Fix regressions and re-invoke until green.

6. **Code quality**, diffed against Step 1's baseline, via `iru-gate-runner`: `Agent({description: "Check for new
   quality issues in task group", subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill:
   \"iru-dotnet-code-quality\", args: \"<file1,file2,...>\\nbaseline: <baseline from Step 1>\"}). Report back only newly
   appearing issues (not in the baseline), each with rule id, file, and line, plus any project not analyzable on this
   host."})`. Any new issue is a regression this bucket introduced — trace it to its task, fix it, re-run 3.3 and 3.5,
   and re-check. Leave one in place only if fixing it would contradict what its task explicitly specifies, and record
   why. Skip if `iru-dotnet-code-quality` isn't installed.

7a. **Acquire the repository-wide lock for the UI stage** — immediately before 3.7, never earlier. Skip 3.7a–3.7b
   entirely (no lock, no run) when:

   - **Step 1's table has no UI stage for this bucket** (a Core-only bucket with no binding-relevant change) — say so
     in Step 5.
   - **No UI-test project exists** — report "no UI tests in this repository", not a pass.
   - **The host can't run it** (not Windows, or no interactive desktop session) — report the UI stage as
     **unverified (needs Windows)** (or the precise reason) and continue to Step 4. Never "skipped" without a reason,
     never "passed".

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     identifying text (task numbers/titles), "Windows desktop UI stage", the UI-test project, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the repository's root `.gitignore` (add the
     line if missing) — transient local state, never committed.
   - **`HELD`** — another group is running a UI/integration stage. Tell the user this bucket is waiting (naming the
     holder from `cat .integration-test.lock/owner` if readable), then re-run the `mkdir` roughly every 30 seconds.
     Don't run UI tests anyway.
   - **Stale lock** — `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory: its holder almost
     certainly died. Tell the user what the owner file says, break it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes: stop waiting, report the UI stage as
     **unverified — blocked by a concurrent UI/integration run**, continue to Step 4, and name it in Step 5.

7. **UI stage** — only now, with 3.1–3.6 clean and the lock held, as one gate call:
   ```
   Agent({
     description: "Run desktop UI tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-dotnet-test\", args: \"ui\\nproject: tests/<App>.UITests\\nlock: held\"}) — build
       the app first (WPF/WinForms: Release or Debug, whichever the UI tests resolve APP_EXE/the newest built exe
       from; WinUI: build a self-signed MSIX, install it with Add-AppxPackage and export APP_AUMID from
       Get-AppxPackage, exactly as that skill's `ui` step 2 describes — unverified locally, needs Windows), then run
       only TestCategory=UI in the same shell. The caller already holds .integration-test.lock — do not acquire or
       release it. Run exactly this one invocation; wait for it rather than backgrounding it. Report back only:
       whether all UI tests passed; for each failure the test name, reason, and first message line; separately any
       environment problem (app failed to launch, no interactive desktop, WinUI APP_AUMID not produced — cert trust
       needing elevation, MSIX build or Add-AppxPackage failure — tests reported skipped) as opposed to an assertion
       failure; what the WinUI step left installed (package, self-signed cert); whether the filter matched no
       tests; and the gate's UI-contract summary — screenshot count per kind and per <kind>/<Id> directory, malformed
       capture names, Axe.Windows violations (count + rule ids per context, never raw dumps) and the .ui-quality.yml
       level applied.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another test-running agent.

   - **Environment failure or all tests skipped** — report the stage as **unverified** with the cause, not as a
     code defect and not as passed.
   - **A filter that matched nothing** — fix the scope and re-run; never record it as passed.
   - **Assertion failure** — trace it to its task and fix. Typical causes the Core run can't see: a WPF `{Binding}`
     path that no longer matches a renamed view-model property, a missing `Mode=OneWay` on a WinUI `x:Bind`, a DI
     registration missing for a new view model/window, a missing `AutomationId`. Keep the lock across a quick
     fix-and-re-run; for a long debugging session release it (3.7b), fix, and re-acquire before re-running. If the
     fix touched Core, re-run 3.3–3.6 afterwards.

7c. **UI-contract check** — from 3.7's summary and a listing of `.ui-screenshots/` (`find .ui-screenshots -name
   '*.png'`, or `$UI_SCREENSHOTS_DIR` when set), while still holding the lock so a fix-and-re-run stays serialized:

   - **Captures**: for every screen/component in the UI inventory, `<kind>/<Id>/` must hold a PNG for each of its
     visual states × each `directions` × each `themes` entry (`<state>--<dir>--<theme>.png`). A missing combination,
     an `Id` with no directory, or a malformed name is a gap in that task's UI tests — add the missing tests/captures
     through the task that owns the view and re-run 3.7. A UI-test project with no capture helper at all (older
     repository) is reported as "captures not produced — the UI tests predate the UI contract", not as a pass.
   - **Accessibility**, at the `.ui-quality.yml` level: `enforce` — Axe.Windows violations already failed their
     tests in 3.7; fix them (accessible names, automation ids, labels, keyboard access) and re-run. `report` — list
     the violations (rule ids and counts per context) in Step 5 and the plan notes; they never fail the bucket.
     `off` — say "accessibility checks off (.ui-quality.yml)".
   - Off Windows (or whenever 3.7 was unverified) this check is **unverified (needs Windows)** as well — never
     "passed" because no captures exist.

7b. **Release the lock** as soon as 3.7's agent has reported back and any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release it on **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a hard
   stop. If you took the lock, you own releasing it before this skill returns.

## Step 4 — Finalize progress notes and notify the user

Confirm the lock is released (3.7b) if it was ever taken; release it now if a failure path skipped it.

Replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome, quoting Core
coverage and the UI result separately:

```
- [x] Task 3. **Add `BookDetailsViewModel` with save validation** — tests in BookDetailsViewModelTests (Core 94%
  lines; UI layer not measured); UI tests unverified (needs Windows); no new StyleCop/CA issues.
- [x] Task 4. **Add `OrderRow` component and virtualized `OrderList`** — UI tests passed; captures
  component/OrderRow 4 states × ltr,rtl × light,dark (16) complete; Axe.Windows (enforce) 0 violations.
```

If the UI stage (or a WinUI build) was unverified, say so with the reason — `UI tests unverified (needs Windows)` or
`UI tests unverified (lock held by another group)` is actionable; silence reads as a pass. Leave blocked tasks
unchecked with their blocker note. Notify the user that group validation completed, with the bucket-wide result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The shape detected** (`ui`, architecture, Core/test projects, host) and the validation scope.
- **Per task**: projects/files touched, tests added/updated, Core coverage achieved (and "not measured" UI-layer
  types), and the analyzer outcome.
- **The Core results** (3.3, 3.5) and **the UI result** (3.7): passed, failed-and-fixed, **unverified** with the
  reason, or not in scope — plus the WinUI build/packaging status when the bucket touched them.
- **The UI-contract result** (3.7c): screenshots per kind and per screen/component, missing state × direction ×
  theme combinations (fixed or why not), malformed names, the accessibility level applied and its violations (fixed
  under `enforce`, listed under `report`), or "unverified (needs Windows)".
- **Coverage exceptions accepted**, with reasoning; UI-layer logic that should move to Core.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire (wait time, a broken stale lock and its
  owner text, a timeout) and confirmation it was released.
- Whether a new analyzer issue was left in place as unavoidable, whether license-header generation was skipped,
  whether a sequential-ordering deviation was applied in Step 2, and which task(s), if any, stopped on a blocker.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.

**Warn explicitly**: on a non-Windows host the `net10.0-windows` UI-test project can't even start its test host
(`Microsoft.WindowsDesktop.App` is not available for macOS/Linux — verified), which is why the gates exclude it there
(its csproj sets `IsTestProject=false` off Windows; its `[OSCondition(OperatingSystems.Windows)]` would only report
the tests as skipped); a WinUI project doesn't build off Windows at all. This
orchestration skill was not dry-run end to end by its author; the gate commands (`--filter "TestCategory!=UI"`,
per-project runs, `--settings tests/coverlet.runsettings --collect "XPlat Code Coverage"`, per-project SARIF builds)
were exercised on macOS by the `iru-dotnet-*` gate skills' own verification, and the UI stage and lock behavior on
Windows are **unverified locally (needs Windows)**.
