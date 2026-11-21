---
name: iru-windows-code-one-task
description: "Invoked as /iru-windows-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one Windows desktop (.NET 10; WPF, WinUI 3 or Windows Forms) plan task and its tests, per-project compile-check, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-windows-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(dotnet build *) Bash(dotnet restore *) Bash(dotnet sln *) Bash(dotnet --version) Bash(dotnet --info) Bash(uname *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *)
---

# Implement one plan task (Windows desktop app)

Carry out a single task's implementation, tests, and checkbox update in a Windows desktop app on .NET — nothing else.
This is the narrowest execution unit for a `windows` task in the `iru-code` pipeline: `iru-windows-code-one-task-group`
calls it once per task in a bucket (potentially many at once, in parallel agents), then handles license headers, XML
doc auditing, and all test/coverage/analyzer/UI-test validation itself, once for the whole bucket. It uses a medium
model on purpose — the plan carries the hard reasoning; this skill executes one task against code agreements that
live in `reference/`.

Two things make it different from `iru-dotnet-code-one-task`:

1. **Logic lives in a UI-free Core project.** The 80% coverage bar is measured on `src/<App>.Core` (plain `net10.0`,
   runs anywhere); XAML, code-behind and forms are a thin shell. Where a piece of code goes is therefore the first
   decision, not an afterthought (`reference/architecture.md`).
2. **Part of the solution only builds or runs on Windows.** WPF/WinForms compile on Linux/macOS with
   `EnableWindowsTargeting`, but a WinUI project's XAML compiler is a Windows executable, and nothing UI runs off
   Windows. Step 5 says what can be checked where — and "unavailable" is never reported as "passed".

## Step 0 — Detect the project shape once

Settle these from the files, never from assumptions (this skill is copied into other repositories):

- **Solution and projects**: the root `*.slnx`/`*.sln` (`dotnet sln <file> list`), else every `*.csproj` outside
  `bin/`/`obj/`.
- **App project and UI stack**: the `*.csproj` with `<OutputType>WinExe</OutputType>` and one of `<UseWPF>true`,
  `<UseWinUI>true`, `<UseWindowsForms>true` (or a `net*-windows*` TFM with one of them) → `ui: wpf|winui|winforms`.
  Several app projects → note each; the task's paths decide which one it touches. None at all → this isn't a Windows
  desktop app: report it as a blocker (the task was mis-tagged — `dotnet` is the key for libraries/services).
- **Core project**: the `net10.0` (no `-windows`) project the app references — `src/<App>.Core` in the
  `iru-setup-windows-app` layout. Its folders decide the **architecture**: `ViewModels/` → `mvvm`;
  `Controllers/` (+ `Views/I<X>View.cs` interfaces) → `mvc`; both → note it and follow the folder the task names. No
  Core project (logic in the app project) → say so in Step 8 and still put new testable logic in a UI-free class.
- **Packages and settings** (`Directory.Build.props`, `Directory.Packages.props`, the csproj files): CommunityToolkit.Mvvm,
  Microsoft.Extensions.Hosting (Generic Host DI), Microsoft.Xaml.Behaviors.Wpf, StyleCop.Analyzers + `stylecop.json`,
  `TreatWarningsAsErrors`, `AnalysisLevel`/`AnalysisMode`, `GenerateDocumentationFile`, `EnableWindowsTargeting`,
  central package management (`ManagePackageVersionsCentrally` → package versions live in `Directory.Packages.props`,
  never in a csproj), `LangVersion`, `Nullable`.
- **Tests**: `tests/<App>.Core.Tests` (MSTest 4 / xUnit / NUnit — whichever is referenced), `tests/<App>.UITests`
  (FlaUI + `[TestCategory("UI")]`), the test runner (VSTest by default; Microsoft.Testing.Platform when `global.json`
  has `"test": { "runner": "Microsoft.Testing.Platform" }` or the test csproj sets `EnableMSTestRunner`/
  `UseMicrosoftTestingPlatformRunner`), and two or three existing tests for naming and fake style.
- **Packaging**: `src/<App>/Package.appxmanifest` (WinUI single-project MSIX), `packaging/AppxManifest.xml` +
  `packaging/build-msix.ps1` (WPF/WinForms MSIX), `Properties/PublishProfiles/*.pubxml`, `app.manifest`.
- **Host**: `uname -s` (absent / `MINGW*`/`MSYS*`/`CYGWIN*`, or `$OS` = `Windows_NT` → Windows). Off Windows: WPF/
  WinForms/UI-test projects compile only with `EnableWindowsTargeting=true` (the scaffold sets it in
  `Directory.Build.props`; pass it on the command line otherwise), and a WinUI project doesn't compile at all.

## Step 1 — Decide the project(s), then read only the reference material this task needs

Use `reference/architecture.md`'s "Deciding where the code goes": a path in the task wins; otherwise models,
services (interfaces and pure implementations), view models (`mvvm`) and controllers + view interfaces (`mvc`) →
Core; XAML/code-behind/forms, UI-touching service implementations (dialogs, file pickers, navigation, clipboard),
DI registration, manifests → the app project; genuinely ambiguous → Core, and say so in Step 8. Record the set of
projects the task touches — Step 5 derives the compile-checks from it.

This skill ships a `reference/` directory next to this file. Read `reference/README.md` first — it is short and
routes you to the rest — then read only what it points at. The routing, restated:

| Read | When |
|---|---|
| `reference/code-style.md` | **Always** — C# 14/.NET 10 conventions, async rules, member ordering, naming. |
| `reference/architecture.md` | **Always** — Core vs app split, MVVM vs MVC rules, code-behind limits, Generic Host DI. |
| `reference/testing.md` | The task writes or updates tests — nearly every task. |
| `reference/xml-docs.md` | The task adds or changes a public/protected type or member. |
| `reference/mvvm-toolkit.md` | `mvvm`: a view model, observable property, command, validation, or messenger. |
| `reference/mvc-winforms.md` | `mvc`: a controller, a view interface, a model raising change events, or a form implementing a view. |
| `reference/wpf.md` | `ui: wpf` and the task touches XAML, code-behind, resources/styles, `App.xaml(.cs)`, or threading in the app. |
| `reference/winui.md` | `ui: winui` and the task touches XAML, code-behind, navigation, windowing, or `Package.appxmanifest`. |
| `reference/winforms.md` | `ui: winforms` and the task touches a form, a designer file, a user control, layout, or binding. |
| `reference/ui-contract.md` + `reference/ui-implementation.md` | The task adds or changes a screen, component, list or other UI (window, page, form, `UserControl`, `DataTemplate`, collection control, navigation). |
| `reference/packaging.md` | `Package.appxmanifest`, `packaging/AppxManifest.xml`, capabilities, versions, publish properties/profiles, RIDs. |
| `reference/security.md` | Secrets/credentials, settings files, file/registry access, loading XAML or other external input, elevation, MSIX capabilities, process launch. |

Read at most one of `wpf.md`/`winui.md`/`winforms.md` (the detected stack) and at most one of
`mvvm-toolkit.md`/`mvc-winforms.md` (the detected architecture). If `reference/` is missing (the skill directory was
copied without it), proceed using the repository's own `CLAUDE.md` conventions and the surrounding code's style, and
say so in Step 8 — don't guess at what the reference would have said.

## Step 2 — Re-check the current code state

Read the actual current content of every file the task touches before editing — other tasks in the same bucket may be
landing concurrently. For a view model or controller, also read the view that binds to it (`*.xaml` bindings by
property/command name, the form implementing `I<X>View`) and the DI registration (`App.xaml.cs` / `Program.cs`): a
renamed property silently breaks a `{Binding}` that no compiler checks (WPF), while `x:Bind` (WinUI) and an interface
(MVC) fail the build. Note what the surrounding code does about the things `reference/` has opinions on; a file that
consistently does something else wins, and the divergence goes in Step 8.

## Step 3 — Implement exactly what the task specifies

Apply `reference/code-style.md`, `reference/architecture.md`, and whichever optional files Step 1 selected, plus the
repository's own `CLAUDE.md` where more specific. Work in dependency order: Core models/service interfaces → Core
view models or controllers → app-project service implementations → XAML/forms → DI registration.

When the task touches UI, **decompose first** (`reference/ui-contract.md` §2): write the `Screen › Region › Component`
tree with each component's inputs, events and visual states, search for existing `UserControl`s/templates to reuse,
then build leaves → composed components → screens → navigation, following `reference/ui-implementation.md` (UI-state
records in Core, `UserControl`s with dependency-property inputs and command/`EventHandler<T>` outputs, collections in
a virtualizing recycling container over a keyed `ObservableCollection`). Keep the tree for Step 8.

Non-negotiable, because a gate or the other platform enforces them:

- **No UI types in Core** — no `System.Windows`, `Microsoft.UI.Xaml`, `System.Windows.Forms`, `Dispatcher`,
  `MessageBox`, or file dialogs in `src/<App>.Core`; it targets plain `net10.0` and its tests must run on Linux.
- **Logic in Core, not in code-behind** — code-behind only wires the view (`InitializeComponent`, `DataContext`/
  `ViewModel` assignment, view-only event handlers that forward to a command or the controller).
- **XML doc comments** on every public/protected member (`GenerateDocumentationFile` + `TreatWarningsAsErrors` turn a
  missing one into a build error).
- **No new NuGet package, project, target framework, or MSIX capability** unless the task explicitly calls for it — a
  blocker to report (Step 6) otherwise. With central package management a new package needs a `PackageVersion` in
  `Directory.Packages.props` and a version-less `PackageReference`.
- **Never hand-edit generated code** — `*.g.cs`, `*.g.i.cs`, the MVVM Toolkit's generated partials, or a WinForms
  `InitializeComponent` beyond what the designer round-trips (`reference/winforms.md`).

Don't add anything the task didn't ask for — no speculative abstractions, no unrelated cleanup, no restyling.

## Step 4 — Write or update the tests

Follow `reference/testing.md` and the existing test style: Core logic → `tests/<App>.Core.Tests` (MSTest 4 by default:
`[TestClass]`/`[TestMethod]`/`[DataRow]`, `Assert.ThrowsExactly`), fakes for services, view-model tests asserting
`PropertyChanged`, `CanExecute`, and awaited `AsyncRelayCommand.ExecutionTask`; controller tests with a fake view.
A UI-automation test goes to `tests/<App>.UITests` only when the task asks for one, is marked
`[TestCategory("UI")]` and `[OSCondition(OperatingSystems.Windows)]`, and launches the app through the scaffold's
`AppLauncher` (`APP_AUMID` → `APP_EXE` → newest built exe). Cover the edge cases implied by the XML-doc
`<exception>` contracts. A task that adds or changes a screen or component also adds its FlaUI UI tests per
`reference/ui-contract.md` §6–8 and `reference/ui-implementation.md`: every visual state with minimal/typical/
many-items data, behaviour asserted once (`ltr`/`light`), a `UiScreenshots.Save(…)` capture per state ×
`UiQuality.Matrix` (direction × theme from `.ui-quality.yml`), and `AccessibilityCheck.Run(…)` after each capture;
add the `UiQuality`/`UiScreenshots`/`AccessibilityCheck` helpers if the repository lacks them — but a missing
`Axe.Windows` package is a new dependency (Step 6): add it only when the plan names it, else report it. Do not run the tests —
`iru-windows-code-one-task-group` runs them once per bucket.

## Step 5 — Compile-check each touched project

A broken compile in one task blocks every other task in a parallel bucket from validating. Build the touched
projects (each builds its references) plus the Core test project when Core changed — never the whole solution when
one project suffices:

| Touched | Command (non-Windows host) | On Windows |
|---|---|---|
| Core (`src/<App>.Core`) | `dotnet build src/<App>.Core` and `dotnet build tests/<App>.Core.Tests` | same |
| WPF/WinForms app project | `dotnet build src/<App> -p:EnableWindowsTargeting=true` | `dotnet build src/<App>` |
| UI-test project (`net10.0-windows`) | `dotnet build tests/<App>.UITests -p:EnableWindowsTargeting=true` | `dotnet build tests/<App>.UITests` |
| WinUI app project | **compile-check unavailable (needs Windows)** — still build Core | `dotnet build src/<App> -p:Platform=x64` (the `Platforms` list is `x64;ARM64`; add `-r win-x64` if the project sets no default RID) |

Passing `-p:EnableWindowsTargeting=true` is harmless when `Directory.Build.props` already sets it. Keep
`TreatWarningsAsErrors` as the repository configured it: an analyzer warning in code this task wrote is the task's
own error to fix now (StyleCop ordering/spacing, `CA` rules, `CS1591` missing XML doc, `MVVMTK*` generator
diagnostics). A WPF build also compiles the XAML (markup compile pass) — a typo in an element or `x:Class` fails
here; a misspelled `{Binding Path}` does **not** (WPF bindings are resolved at run time — re-read Step 2's binding
list instead).

Outcomes:

- **Failure in code this task wrote or edited**: fix it — it's the task's own code, not a finding to defer.
- **Failure outside the task's scope** (a sibling task's in-flight edit, a pre-existing error): don't fix unrelated
  code; re-run once if it looks like a transient race with a concurrently-writing sibling, then note it in Step 8.
- **`dotnet` can't run at all** (no SDK matching `global.json`, no network for the first restore): report
  "compile-check unavailable" with the reason — the group's validation still catches real errors.

## Step 6 — When to interrupt the user

Keep interruptions rare. Stop and use `AskUserQuestion` (or plain text if no real choice is offered) only when:

- The task is ambiguous in a way that changes correctness or scope and can't be safely inferred.
- The task's approach is infeasible against the real code (it names a type/member that doesn't exist, or demands UI
  code in Core, or MVC in a WPF/WinUI app that is MVVM by layout).
- The task needs a new NuGet package, project, target framework, MSIX capability, or elevation
  (`requireAdministrator`).
- The task needs a secret embedded in the app or its settings files (`reference/security.md`) — a design problem to
  report.

Don't stop merely because the task is nontrivial. Don't report the task done to work around a blocker; report it as
blocked (Step 8). A conflict between `reference/` and the surrounding code is not a reason to stop: follow the
surrounding code and report the divergence.

## Step 7 — Check off this task's checkbox now

If this task did not stop on a blocker, update `implementation_plan.md` at the repository root before reporting back.
Re-read it fresh (sibling tasks may be editing it), locate this task's own checkbox line by the exact text passed in,
flip `[ ]` to `[x]` for it and every sub-task the work covered, and add a short note naming the projects and files
touched, e.g.:

```
- [x] Task 3. **Add `BookDetailsViewModel` with save validation** — Core ViewModels/ + MainWindow.xaml binding,
  Core.Tests BookDetailsViewModelTests; group validation pending.
```

Keep the "group validation pending" wording — `iru-windows-code-one-task-group` replaces it with the final outcome.
Edit only this task's own line(s). If this task stopped on a blocker, leave its checkbox untouched.

## Step 8 — Report the outcome

Hand back a short summary:

- **The shape detected** (`ui`, architecture, Core project, test framework/runner, host) and **the projects touched**
  with the reference files actually read.
- **The files touched** and **the tests added/updated** per test project (not run), including any `[TestCategory("UI")]`
  test.
- **For a UI task**: the `Screen › Region › Component` decomposition (new vs reused components, inputs, events,
  visual states) and the UI tests added with the screenshot names they will produce
  (`<kind>/<Id>/<state>--<dir>--<theme>.png`) and whether they run the accessibility check.
- **The compile-check result per project**: passed, failed on this task's code and fixed, failed outside this task's
  scope, or unavailable (and why — e.g. WinUI on a non-Windows host).
- **Bindings or registrations that only a run can verify** (WPF `{Binding}` paths, DI registrations, resources looked
  up by key) — named so the group's UI stage or a reviewer can check them.
- **Whether this task stopped on a blocker**, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`**, and pre-existing violations noticed and left alone (UI types in
  Core, logic in code-behind, `async void` outside an event handler, secrets in settings).

This skill never captures a quality baseline, adds license headers, runs tests/coverage/analyzer reports, runs DocFX,
launches the app, or builds an MSIX.

## Verification notes

Exercised in `$TMPDIR/iru-verify/windows/` on macOS 27 (arm64) with the .NET SDK 10.0.402, against a solution shaped
like the `iru-setup-windows-app` contract (`Directory.Build.props` with `EnableWindowsTargeting`, `TreatWarningsAsErrors`,
`AnalysisLevel latest-recommended`, `GenerateDocumentationFile`, StyleCop.Analyzers 1.2.0-beta.556 as a
`GlobalPackageReference`; `src/Bookshelf.Core` net10.0 with CommunityToolkit.Mvvm 8.4.2; a WPF app with Generic Host
10.0.13; MSTest 4.5.1 Core tests; a `net10.0-windows` FlaUI 5.0.0 UI-test project):

- `dotnet build` of Core, the WPF app (XAML markup compile included) and the `net10.0-windows` UI-test project
  succeeded on macOS with `EnableWindowsTargeting=true`; without it the WPF project failed with `NETSDK1100`.
- Partial-property `[ObservableProperty] public partial string Name { get; set; }` with
  `[NotifyCanExecuteChangedFor]` and `[RelayCommand(CanExecute = …)]` compiled with `LangVersion latest` (C# 14) and
  produced no `MVVMTK*` diagnostics.
- Analyzer interactions that surfaced as build errors under `TreatWarningsAsErrors` (recorded in
  `reference/code-style.md` and `reference/testing.md`): StyleCop `SA1200` fires on file-scoped-namespace-style
  `using`s unless `stylecop.json` sets `orderingRules.usingDirectivesPlacement: outsideNamespace`; `CA1707` rejects
  `Method_Scenario_Result` test names and `SA0001` fires in test projects that turn `GenerateDocumentationFile` off,
  unless the test projects relax them; MSTest 4's analyzers flag `Assert.IsTrue(true)` (`MSTEST0032`).
- UI contract (2026-10-16, `$TMPDIR/iru-verify/windows/ui-contract/`): the `reference/ui-implementation.md` WPF
  `UserControl` + recycling `ListBox` (XAML markup compile included) and the WinForms virtual-mode `UserControl`
  built with 0 diagnostics (`WFO1000` fires when the input property lacks `[DesignerSerializationVisibility]`); the
  `SyncById` keyed diff passed 6 MSTest cases; the `UiQuality`/`UiScreenshots`/`AccessibilityCheck` helpers compiled
  against FlaUI.UIA3 5.0.0 + Axe.Windows 2.4.2; the WinUI theme/direction hook compiled against the Windows App SDK
  reference assemblies. **Unverified locally (needs Windows)**: running any capture or Axe.Windows scan.
- A minimal WinUI 3 project (Windows App SDK 2.5.1, `net10.0-windows10.0.19041.0`, `UseWinUI`) failed on macOS with
  `XamlCompiler output file "…/output.json" was not created. The XAML compiler may have crashed.` — hence
  "compile-check unavailable (needs Windows)" for WinUI. **Unverified locally (needs Windows)**: the WinUI build and
  every run/packaging step.
