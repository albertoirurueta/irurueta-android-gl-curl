---
name: iru-dotnet-code-one-task
description: "Invoked as /iru-dotnet-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one .NET/C# (libraries, services, CLIs; not Windows desktop) plan task and its tests, compile-checks, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-dotnet-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(dotnet *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *)
---

# Implement one plan task (.NET)

Carry out a single task's implementation, tests, and checkbox update against the real codebase — nothing else.
This is the narrowest execution unit in the `iru-code` pipeline: `iru-dotnet-code-one-task-group` calls it once
per task in a bucket (potentially many at once, in parallel agents), then handles license headers, doc comments,
and all test/coverage/quality validation itself, once for the whole bucket, instead of repeating that validation
here for every single task. It uses a medium model on purpose — the plan already carries the hard reasoning;
this skill just executes one task's implementation.

**Windows desktop app repositories are out of scope.** If a `*.csproj` in the solution has `<OutputType>WinExe</OutputType>`
with `<UseWPF>`, `<UseWinUI>`, or `<UseWindowsForms>` set to `true` (a WPF, WinUI 3, or Windows Forms app — `iru-explore`
reports `Project type: windows-app`, plan key `windows`), stop and hand the request to `iru-windows-code-one-task`
instead (or tell the caller to, when it isn't installed): it knows the UI-free Core project split, MVVM/MVC layouts,
and the host-dependent compile checks this skill doesn't. Tasks touching only that repository's `src/<App>.Core`
project are still `windows` tasks.

## Step 0 — Detect a command-line tool

Decide once, from the files, whether this is a **CLI-shaped** project: a `*.csproj` with
`<OutputType>Exe</OutputType>`, none of `UseWPF`/`UseWinUI`/`UseWindowsForms`, and a `System.CommandLine` (or
`Spectre.Console.Cli`) reference or `PackAsTool`/`PublishAot` set — `iru-explore` reports `Project type: cli-tool
(dotnet)`. In the `iru-setup-dotnet-cli` layout that is `src/<Tool>/` (the thin command tree, assembly `<bin>`) plus a
framework-free `src/<Tool>.Core/` (logic, exit codes, output, terminal policy) with `tests/<Tool>.Core.Tests/` and
`tests/<Tool>.Cli.Tests/`.

- **CLI-shaped**: read `reference/README.md`, then `reference/cli.md`, and `reference/cli-contract.md` whenever the task
  adds or changes a command, flag, output, error or exit path. When the task adds or changes the terminal UI (a
  `Terminal.Gui` reference, `src/<Tool>/Tui/`, a `browse`-style command), also read `reference/ui-contract.md` and
  `reference/ui-implementation.md`. Their rules apply on top of Steps 2–3 below.
- **Anything else** (library, service, plain console app): read nothing from `reference/` — Steps 1–6 are the whole
  guidance.

## Step 1 — Re-check the current code state

Read the actual current content of the file(s) the task touches before editing — don't assume any "current code
state" notes passed in with the task are still accurate; other tasks in the same bucket may be landing
concurrently, or the file may have changed for unrelated reasons.

## Step 2 — Implement exactly what the task specifies

The named file(s), the described class/method/property, the behavior, the interface/base class it implements.
Follow this repository's conventions from `CLAUDE.md` (target framework/language version, `var`/nullable
reference type usage (only if code already uses it), full XML doc comments (`<summary>`/`<param>`/`<returns>`/
`<exception>`) on every public/protected member, `ArgumentException`/`ArgumentNullException` for invalid
arguments, no new NuGet dependencies). Don't add anything the task didn't ask for — no speculative abstractions,
no unrelated cleanup.

For a CLI-shaped project (Step 0): logic, result records and new error codes go in `src/<Tool>.Core` (no
System.CommandLine/Spectre types there); a command is a thin `ToolCommand` in `src/<Tool>/Commands/` registered in
`CliApp.BuildRootCommand`; every new JSON output type is registered in `Output/CliJsonContext.cs`; never renumber exit
codes or reshape the error envelope; and no reflection-based JSON or reflection over unknown types (Native AOT). Then
compile-check each project you touched with `dotnet build <project> -c Release` — warnings are errors and the trim/AOT
analyzers run in the build, so fix any IL2xxx/IL3xxx, StyleCop or CA error before reporting. If a command or option
changed, regenerate the CLI reference (`dotnet run --project src/<Tool> -c Release -- gen-docs --output-dir .`) or
note in Step 6 that it still needs regenerating.

For a terminal-UI task, **decompose first** (`reference/ui-contract.md` §2): write the `Screen › Region › Component`
tree with each view's inputs (the Core state it renders), intents and visual states, reuse existing views, keep state
and transitions in Core, and build the views as composed `View`s in `src/<Tool>/Tui/` per
`reference/ui-implementation.md`. Keep the tree for Step 6.

## Step 3 — Write or update the tests

Follow the existing test style in the same project/namespace — whichever test framework this repository already
uses (xUnit, NUnit, or MSTest), and Moq/NSubstitute only where already used. Cover the new/changed behavior,
including edge cases implied by the XML doc `<exception>` contracts (e.g. null/invalid-argument cases). For a
CLI-shaped project, unit-test Core logic in `tests/<Tool>.Core.Tests` and test each new or changed command in
`tests/<Tool>.Cli.Tests` through the in-process `CliRunner` — exit code, stdout and stderr for success, `--json`
golden output, and every exit code the command can emit (see `reference/cli.md` "Testing"). A terminal-UI task also
adds `[TestCategory("UI")]` view tests in `tests/<Tool>.Cli.Tests/Tui/` per `reference/ui-implementation.md`: every
visual state × each `.ui-quality.yml` direction rendered on the ANSI driver at a fixed size, keyboard behaviour
asserted once, a `UiScreenshots.SaveText` capture per state and direction, and `AccessibilityCheck.Apply` with the
asserted `tui-*` checks after each capture (add the helpers if they are missing). Do not
run the test suite yourself — `iru-dotnet-code-one-task-group` runs it once for the whole bucket in its own
consolidated validation pass.

## Step 4 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if
no real choice is being offered) only when:

- Implementing the task reveals it's ambiguous in a way that changes correctness or scope and can't be safely
  inferred — conflicting instructions, a choice only the user can make (e.g. which of two APIs to break), or a
  missing decision. Don't ask about things with an obvious best-practice answer.
- The task's described approach appears infeasible against the real code as it stands (e.g. it names a type or
  member that doesn't exist and isn't a trivial typo) — not something you can resolve by writing the code
  slightly differently.

Do not stop merely because the task is nontrivial — implement it. Do not report the task done to work around a
blocker; report it as blocked instead (Step 6), with enough detail — what was tried, what failed, why — for the
caller to surface it.

## Step 5 — Check off this task's checkbox now

If this task did not stop on a blocker (Step 4), update `implementation_plan.md` at the repository root before
reporting back — don't leave this for the caller. Re-read the file fresh (not any earlier cached view — other
tasks in the same bucket may be landing concurrently) and locate this task's own checkbox line by the exact text
passed in with the task. Flip its `[ ]` to `[x]`, and do the same for every sub-task checkbox that was part of
the work just completed, adding a short note naming the files touched, e.g. `- [x] Task 2. **Implement
`SimpleWidget`** — implemented, tests added; group validation pending.` — the same "pending" wording
`iru-dotnet-code-one-task-group` uses, since the coverage/quality outcome isn't known until it validates the
whole bucket in its own Step 3/4. Edit only this task's own line(s) — never rewrite surrounding lines or other
tasks' checkboxes, since sibling tasks in a parallel bucket may be editing this same file at nearly the same
moment. This is what lets an interrupted run resume without re-attempting a task whose implementation and tests
already landed, even if the interruption happened before `iru-dotnet-code-one-task-group` got to run its own
group-wide validation.

If this task stopped on a blocker instead, leave its checkbox untouched — there's nothing finished to mark done.

## Step 6 — Report the outcome

Hand control back to the caller with a short summary: the file(s) touched, the tests added/updated, and whether
this task stopped on a blocker per Step 4. For a terminal-UI task, add the `Screen › Region › Component`
decomposition and the UI tests added with the captures they produce (`screen/<View>/<state>--<dir>--no-color.txt`). This is what `iru-dotnet-code-one-task-group` records for this task before
running its own consolidated license/doc/test/coverage/quality validation across the whole bucket; this skill
has already checked off this task's own checkbox with a pending-validation note (Step 5) — the caller only needs
to replace that note with the final validation outcome once the whole bucket passes, not create the checkbox
entry itself. This skill itself never captures a quality baseline and never runs tests/coverage/quality checks.
