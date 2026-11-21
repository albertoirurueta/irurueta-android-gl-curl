---
name: iru-dotnet-test
description: "/iru-dotnet-test [selector] runs a .NET project or solution's tests via dotnet test; selector is a class, Class.Method, namespace/wildcard, trait filter, project/solution path, or `ui` for UI-automation tests. Optional lines: project:, include-ui: yes, lock: held. Windows-desktop aware; reports tests that need Windows as unverified."
model: haiku
---

# .NET Test

Run the project or solution's tests through the .NET CLI, scoping the run to whatever the user asked for. This
skill only runs tests and reports results — it does not fix failures unless asked to as a follow-up.

## Step 0 — Parse the arguments and detect Windows-desktop projects

- The first line is the **selector** (Step 1's table), or empty for the full suite, or the literal `ui` (Step 1's
  "UI tests" rows). Further `key: value` lines, all optional:
  - `project: <path>` — the test project (or solution) to run; overrides the auto-detection below.
  - `include-ui: yes` — don't add the default `TestCategory!=UI` exclusion (rarely wanted: it makes an ordinary run
    drive the desktop).
  - `lock: held` — the caller already holds the repository-wide `.integration-test.lock` (only meaningful with `ui`).
- **Host**: Windows when `uname -s` fails or prints `MINGW*`/`MSYS*`/`CYGWIN*`, or `$OS` is `Windows_NT`; otherwise
  Linux/macOS.
- **Windows-desktop projects** — classify every `*.csproj` in the solution (`dotnet sln <file> list`) or repository:
  - *UI app*: `<UseWPF>true`, `<UseWindowsForms>true`, or `<UseWinUI>true` (a `WinExe` app or a UI library).
  - *Windows test project*: a test project (references MSTest/xUnit/NUnit or `Microsoft.NET.Test.Sdk`, or
    `IsTestProject`) whose `<TargetFramework(s)>` contains `-windows` — e.g. `tests/<App>.UITests` (FlaUI).
  - *Portable test project*: any other test project (e.g. `tests/<App>.Core.Tests` on `net10.0`).
  - *UI tests present*: `grep -rln 'TestCategory("UI")' --include=*.cs <test dirs>` (xUnit: `[Trait("Category",
    "UI")]`; NUnit: `[Category("UI")]`).
  - *Terminal UI tests*: UI-category tests in a **portable** test project whose build graph references
    `Terminal.Gui` (a `iru-setup-dotnet-cli` `ui: tui` tool's `tests/<Tool>.Cli.Tests/Tui/`) — they render through
    Terminal.Gui's ANSI driver in-process, so `ui` runs them on any host, without the lock or an app build.
  No UI app and no Windows test project → a plain .NET repository: skip everything Windows-specific below and behave
  exactly as Steps 1–3 describe.
- **Test runner**: Microsoft.Testing.Platform (MTP) when `global.json` has `"test": { "runner":
  "Microsoft.Testing.Platform" }`; otherwise VSTest (the default — also for MSTest 4 projects that don't opt in).
  With MTP, `dotnet test` takes `--project <path>` / `--solution <path>` instead of a positional path; the `--filter`
  syntax below is unchanged for MSTest.

## Step 1 — Determine scope from the argument

The skill may be invoked with no argument (run every test in the discovered solution/project) or with a selector
describing what to run, and optionally which project or solution to run it against.

### Locating the solution or project

If the working directory (or repository root) contains a single `.sln`/`.slnx` file, `dotnet test` targets it by
default — no need to name it explicitly. If there are multiple solution files, or multiple test projects (e.g.
`*.Tests.csproj`, `*.UnitTests.csproj`) and no solution, ask which one to target unless the user's selector already
names it or it can be inferred unambiguously (e.g. only one test project contains the class named in the selector).
Pass the chosen file as the first positional argument to `dotnet test` (`--project`/`--solution` under MTP).

**Windows-desktop repository on a non-Windows host**: never run the whole solution — a `net*-windows*` test project
in it makes the run abort ("You must install or update .NET to run this application … Framework:
'Microsoft.WindowsDesktop.App' … No frameworks were found", `Test Run Aborted.`, exit 1 under VSTest; a "Handshake
failure", exit 150 under MTP) even though the portable projects' tests passed. Instead run **each portable test
project** separately (one `dotnet test <project>` per project, same filter), and list every Windows test project as
**unverified (needs Windows)** in the report. A test project referencing a WinUI project can't build off Windows
either — same treatment. On Windows, the solution is fine.

### Selector mapping

| What the user wants | Parameter | Example |
|---|---|---|
| Everything | *(none)* | `dotnet test` |
| A specific project or solution | positional path | `dotnet test MySolution.sln` / `dotnet test tests/Foo.Tests/Foo.Tests.csproj` |
| One test class | `--filter` with `FullyQualifiedName~` | `dotnet test --filter "FullyQualifiedName~FooTests"` |
| One method in a class | `--filter` with `FullyQualifiedName~Class.Method` | `dotnet test --filter "FullyQualifiedName~FooTests.TestSomething"` |
| Several methods in one class | `--filter` with `\|` between `FullyQualifiedName~` clauses | `dotnet test --filter "FullyQualifiedName~FooTests.Method1\|FullyQualifiedName~FooTests.Method2"` |
| Several classes | `--filter` with `\|` between `FullyQualifiedName~` clauses | `dotnet test --filter "FullyQualifiedName~FooTests\|FullyQualifiedName~BarTests"` |
| Classes matching a name pattern | `--filter` with `~` (contains match) | `dotnet test --filter "FullyQualifiedName~ServiceTests"` |
| All classes in a namespace | `--filter` with the namespace as a `~` prefix | `dotnet test --filter "FullyQualifiedName~MyProject.Services"` |
| Excluding some classes/methods | `--filter` with `!~` | `dotnet test --filter "FullyQualifiedName!~SlowTests"` |
| Tests tagged with a category/trait | `--filter` with the trait's property name | `dotnet test --filter "Category=fast"` (NUnit), `dotnet test --filter "TestCategory=fast"` (MSTest), or `dotnet test --filter "Category=fast"` for an xUnit `[Trait("Category","fast")]` |
| Excluding a category/trait | `--filter` with `!=` | `dotnet test --filter "Category!=slow"` |
| Trait/category boolean expression | `--filter` with `&`, `\|`, `!`, parentheses | `dotnet test --filter "Category=fast&Priority=1"` |
| **Windows desktop: ordinary run (default)** | the selector's filter **AND** `TestCategory!=UI` | `dotnet test tests/App.Core.Tests --filter "(FullyQualifiedName~FooTests\|FullyQualifiedName~BarTests)&TestCategory!=UI"`; with no selector `--filter "TestCategory!=UI"` |
| **Windows desktop: UI tests (`ui`)** | `--filter "TestCategory=UI"` on the UI-test project | `dotnet test tests/App.UITests --filter "TestCategory=UI"` (Windows only, lock — Step 2) |

Notes:
- `~` means "contains" (substring match); `=` means exact match; `!~`/`!=` negate either form. Parenthesize an `|`
  group before adding `&TestCategory!=UI` — `&` binds tighter than `|`.
- `FullyQualifiedName` is `Namespace.ClassName.MethodName` — matching a namespace or class prefix with `~` selects
  every test under it.
- Which trait/category property names are recognized depends on the test framework/adapter in use: MSTest uses
  `TestCategory` and `Priority`, NUnit uses `Category`, xUnit exposes whatever key was passed to
  `[Trait("Key", "Value")]`. Check the test project's `<PackageReference>` entries (`MSTest`, `MSTest.TestFramework`,
  `NUnit`, `xunit`) if unsure which applies — and use the matching property for the UI exclusion
  (`Category!=UI` for NUnit/xUnit).
- The default UI exclusion applies only when Step 0 found UI tests; tests with no category at all are still included
  by `TestCategory!=UI`. `include-ui: yes` drops it.
- If the selector doesn't match any test, VSTest reports "No test matches the given testcase filter" and exits
  successfully having run zero tests, rather than failing the build the way Maven Surefire does for an unmatched
  `-Dtest` selector — call this out explicitly in the report (Step 3) rather than treating it as a pass. Under
  Microsoft.Testing.Platform a zero-test run instead **fails with exit code 8** — also "matched nothing", not a
  test failure.

## Step 2 — Build and run the command

Compose the full `dotnet test` invocation from the working directory at the repository root (or the solution/
project directory if there's no root-level solution):

```bash
dotnet test [<path-to-.sln-or-.csproj>] --filter "<expression>"
```

Combine a project/solution path with a filter expression when both are needed, e.g.:

```bash
dotnet test tests/Foo.Tests/Foo.Tests.csproj --filter "FullyQualifiedName~FooTests"
```

Windows-desktop additions:

- **Non-Windows host**: append `-p:EnableWindowsTargeting=true` whenever a project in the build graph uses WPF or
  Windows Forms (harmless if `Directory.Build.props` already sets it; without it the build fails with `NETSDK1100`).
- **Microsoft.Testing.Platform**: `dotnet test --project <path> --filter "<expression>"` (or `--solution <path>`).
- **`ui` selector, terminal UI tests** (Step 0) — on any host, no lock, no app build:
  `dotnet test <portable test project> --filter "TestCategory=UI"`, with the screenshot-directory handling of step 0
  and the collection/report of step 4 below (terminal captures are `.txt` with theme `no-color`; accessibility is the
  asserted `tui-color-only`/`tui-help-line`/`tui-keyboard` checks, not an automated audit — say so).
- **`ui` selector, desktop UI tests** — only on a Windows host with an interactive desktop session; otherwise don't
  run anything and report **unverified (needs Windows)** (the captures and the accessibility check with it). Then:
  0. **Screenshot directory** — resolve `UI_SCREENSHOTS_DIR` (an absolute path from the environment, else
     `<repo root>/.ui-screenshots`) and export it for the run. On a **full** UI run (no narrower selector) delete and
     recreate that directory first so it holds exactly this run; on a scoped run leave other captures alone. Read
     `.ui-quality.yml` at the repository root (`accessibility: enforce|report|off|none`, `directions`, `themes`; a missing
     file means `enforce`, `[ltr, rtl]`, `[light, dark]`) — the tests read it themselves; this skill only reports the
     level applied. Under `none` (opted out entirely) or `off` (rules apply, checks not run) the accessibility check is not applicable: report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) — never "unverified" and never a failure.
  1. **Lock** — unless `lock: held` was passed, take the repository-wide lock from the repository root
     (`git rev-parse --show-toplevel`) before building: `mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED ||
     echo HELD`. `ACQUIRED` → write `.integration-test.lock/owner` with "iru-dotnet-test ui", the project, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`, and make sure `.integration-test.lock/` is in the root `.gitignore`. `HELD` → tell
     the user who holds it (`cat .integration-test.lock/owner`) and retry about every 30 seconds; a lock older than 60
     minutes (`find .integration-test.lock -maxdepth 0 -mmin +60`) is stale — say so, remove it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`) and acquire fresh; still held after ~45
     minutes → report **unverified — blocked by a concurrent UI/integration run**. Release it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`) on **every** exit path when this skill
     took it; never touch it when `lock: held`.
  2. **Build the app** the UI tests launch. The `iru-setup-windows-app` UI tests' `AppLauncher` reads `APP_AUMID`
     first, then `APP_EXE`, then the newest `src/<App>/bin/**/<App>.exe` (WPF/WinForms only).
     - **WPF / WinForms**: `dotnet build src/<App> -c <Configuration>`; set `APP_EXE` to the built `.exe` only when
       the tests can't find it themselves.
     - **WinUI (packaged) — `APP_AUMID` is required**: a packaged WinUI exe can't start without package identity, so
       build, sign, and install an MSIX first, mirroring the UI step of `iru-setup-windows-github-workflows`'
       `build.yml`. In PowerShell 7 from the repository root, with MSBuild on `PATH` (a Visual Studio Developer
       PowerShell; on an Arm host use `ARM64`/`win-arm64` instead of `x64`/`win-x64`):
       ```powershell
       [xml] $manifest = Get-Content -Raw src/<App>/Package.appxmanifest
       $identity = $manifest.Package.Identity
       $cert = New-SelfSignedCertificate -Type Custom -Subject $identity.Publisher -KeyUsage DigitalSignature `
         -FriendlyName 'Local UI-test signing' -CertStoreLocation 'Cert:\CurrentUser\My' `
         -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')
       $cer = Join-Path $env:TEMP 'uitest.cer'
       Export-Certificate -Cert $cert -FilePath $cer | Out-Null
       Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null  # elevated
       $appx = Join-Path $env:TEMP 'uitest-appx\'
       msbuild src/<App>/<App>.csproj /restore /p:Configuration=Release /p:Platform=x64 `
         /p:RuntimeIdentifier=win-x64 /p:SelfContained=true /p:WindowsAppSDKSelfContained=true `
         /p:GenerateAppxPackageOnBuild=true /p:AppxBundle=Never /p:UapAppxPackageBuildMode=SideloadOnly `
         /p:AppxPackageSigningEnabled=true /p:PackageCertificateThumbprint=$($cert.Thumbprint) "/p:AppxPackageDir=$appx"
       if ($LASTEXITCODE -ne 0) { throw 'MSIX build failed.' }
       $msix = Get-ChildItem $appx -Recurse -Filter *.msix | Select-Object -First 1
       Add-AppxPackage -Path $msix.FullName
       $family = (Get-AppxPackage -Name $identity.Name).PackageFamilyName
       $env:APP_AUMID = "$family!App"
       ```
       Run step 3 in the same PowerShell session so it inherits `APP_AUMID`. Trusting the certificate in
       `LocalMachine\TrustedPeople` needs an elevated shell — if that's refused, ask the user to run the block
       themselves; never use a real signing certificate here. Afterwards tell the user what stays installed (the
       package — `Get-AppxPackage -Name <Identity Name> | Remove-AppxPackage` removes it — and the self-signed
       certificate in `CurrentUser\My` and `LocalMachine\TrustedPeople`); remove them only if asked.
       **Unverified locally — needs Windows**: this is the same command sequence CI's `build.yml` runs, but MSIX
       packaging and `Add-AppxPackage` can't execute on macOS/Linux, so it hasn't been run outside CI. If
       `APP_AUMID` can't be produced, report the UI stage **unverified** with the reason — never run the WinUI UI
       tests without it.
  3. `dotnet test tests/<App>.UITests --filter "TestCategory=UI"` (plus any narrower selector the caller passed,
     parenthesized and `&`-joined). Run it in the foreground — never in parallel with another test run.
  4. **Collect and validate captures** — FlaUI captures are written by the tests' `UiScreenshots` helper straight
     into the directory, so normally nothing needs collecting; any `*.png` a test wrote under `TestResults/` or the
     test project's `bin/` (a test that bypassed the helper) is moved into the directory only when its name already
     fits the contract, and reported otherwise. Validate every file under the directory against
     `^(component|screen|flow)/([A-Z][A-Za-z0-9]*|[a-z0-9]+(-[a-z0-9]+)*)/[a-z0-9]+(-[a-z0-9]+)*--(ltr|rtl)--(light|dark|high-contrast|no-color)(--[a-z0-9]+(-[a-z0-9]+)*)*\.(png|txt)$`
     (relative path; `a11y-report.txt` at the root is the accessibility report, not a capture), count per kind and per
     `<kind>/<Id>`, and list malformed names. Accessibility: under `enforce` the violations are test failures whose
     message starts with `[a11y]`; under `report` they are the `<context>\t<rule-id>: <message>` lines of
     `<screenshots dir>/a11y-report.txt`. Summarize either as violation count + rule ids per context — never the raw
     scan output.

Run it with the Bash tool. Do not add unrelated flags (e.g. `--no-build`, `-v`, `--logger`) unless the user asks
for them or a prior step in this conversation already established they're needed.

## Step 3 — Report results

Summarize the `dotnet test` output concisely:
- Total tests run, passed, failed, skipped (the CLI prints a `Passed! - Failed: 0, Passed: X, Skipped: Y,
  Total: Z` or `Failed!` summary line per test project under VSTest; a `Test run summary:` block with
  `total/failed/succeeded/skipped` under Microsoft.Testing.Platform).
- If everything passed, say so briefly — don't paste the full log.
- If there are failures, show the failing test names and the relevant assertion/exception output (the console
  output usually includes it inline; check the `.trx` log under `TestResults/` if `--logger trx` was used and
  output was truncated), but don't dump unrelated passing-test noise.
- Windows desktop: which test projects ran, which were **unverified (needs Windows)** (and why), whether the default
  `TestCategory!=UI` exclusion was applied, and — for `ui` — whether the run was an environment failure (the app
  didn't launch, no desktop session, no `APP_AUMID` for WinUI, tests reported **skipped**) rather than an assertion
  failure. Skipped or not-run UI tests are never "passed". Report how the lock behaved when this skill took it.
- `ui` runs (desktop and terminal): the screenshot directory used and whether it was cleared (full run) or kept
  (scoped run); screenshot count per kind (`component`/`screen`/`flow`) and per `<kind>/<Id>`; malformed names; the
  `.ui-quality.yml` level applied (`enforce`/`report`/`off`/`none`, or "missing — defaults"); accessibility violations as
  count + rule ids per context (Axe.Windows for desktop, asserted `tui-*` checks for terminal UIs); and "captures
  unverified (needs Windows)" whenever the desktop stage couldn't run.
- Do not attempt to fix failing tests or modify source/test code unless the user asks for that as a next step.

## Known quirks

Verified in `$TMPDIR/iru-verify/windows/gates/` on macOS 27 (arm64), .NET SDK 10.0.402, MSTest 4.5.1 (metapackage)
+ coverlet.collector 10.1.0, a `net10.0` Core test project and a `net10.0-windows` FlaUI 5.0.0 UI-test project:

- `dotnet test` on the whole `.slnx` (VSTest): the Core tests passed, then the `net10.0-windows` test host failed to
  start (`Microsoft.WindowsDesktop.App` framework not found), `Test Run Aborted.`, **exit 1**. `--filter
  "TestCategory!=UI"` does not help — the host never starts. Per-project runs of the portable project exit 0.
- Same under Microsoft.Testing.Platform (`global.json` `test.runner`, test projects with `EnableMSTestRunner` +
  `OutputType Exe`): a "Handshake failure" for the Windows test project, **exit 150**. MTP also refuses to run at all
  ("All projects must use that test runner") while any test project still uses VSTest.
- `--filter "TestCategory=UI"` against the Core project: VSTest prints "No test matches the given testcase filter"
  and exits **0**; MTP exits **8** ("zero tests ran").
- `TestCategory=Slow`, `FullyQualifiedName~A.M|FullyQualifiedName~B`, `TestCategory!=UI&TestCategory!=Slow`, and
  `(FullyQualifiedName~A|FullyQualifiedName~B)&TestCategory!=UI` all selected exactly the expected tests. Each
  `[DataRow]` counts as a separate test in the totals.
- Building a WPF project on macOS without `EnableWindowsTargeting` fails with `NETSDK1100`; with it, WPF and the
  `net10.0-windows` test project compile (so a UI test still gets a compile check off Windows).
- UI contract (2026-10-16): the `UiScreenshots`/`AccessibilityCheck` helpers the `ui` step relies on compiled
  against FlaUI.UIA3 5.0.0 + Axe.Windows 2.4.2 on macOS; the Terminal.Gui 2.5.0 view tests of a `ui: tui` CLI ran on
  macOS through the ANSI driver and wrote `screen/BrowseView/<state>--<dir>--no-color.txt` captures.
- **Unverified locally (needs Windows)**: actually running FlaUI UI tests (captures and Axe.Windows scans included) (and whether hosted `windows-latest` runners
  give them an interactive session), building/running a WinUI project, and the lock-protected `ui` flow end to end.
