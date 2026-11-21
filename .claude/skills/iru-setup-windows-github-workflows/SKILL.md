---
name: iru-setup-windows-github-workflows
description: "/iru-setup-windows-github-workflows [args] creates or updates build.yml, release.yml, sync.yml, security.yml and dependabot.yml for a .NET Windows desktop app repository from iru-setup-windows-app, keeping Linux-capable jobs on ubuntu-latest. Args: ci-tests, pages, distribution, signing, winget, sonar, on-existing. Asks Update/Keep/Replace/Stop on existing files."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(python3 *) Bash(ruby *) Bash(actionlint *) Bash(dotnet *) Bash(pwsh *) Bash(docfx *) Bash(mkdir *)
---

# Setup Windows GitHub Workflows

Scaffold (or update) the CI/CD pipeline for a Windows desktop app built by `iru-setup-windows-app`, around one cost
rule: **everything that can run on Linux runs on `ubuntu-latest`** — the Core build/tests/coverage, the WPF/WinForms
compile (`EnableWindowsTargeting`), formatting, DocFX + Antora, CodeQL, and Sonar for WPF/WinForms — and only what
needs Windows uses `windows-latest`: the WinUI build, the solution build/tests on Windows, the FlaUI desktop UI tests,
MSIX packaging, signing, WinGet and the Store. Windows minutes cost **~1.7×** Linux minutes on a private repository
($0.010 vs $0.006 per minute, per GitHub's current pricing).

Files (templates in `reference/workflow-templates.md`; its conditional markers and placeholders are explained there):

| File | Written when |
|---|---|
| `.github/workflows/build.yml` | always |
| `.github/workflows/release.yml` | `distribution` ≠ `none` |
| `.github/scripts/make_appinstaller.py` (renders the `.appinstaller` template at release time) | `distribution: internal` with MSIX packaging and `signing` ≠ `none` |
| `.github/workflows/sync.yml` + `.github/scripts/sync_versions.py` | `branching: gitflow` |
| `.github/workflows/security.yml` | always (jobs gated by `security-*`) |
| `.github/dependabot.yml` | always |

Sources: Microsoft Learn — CI for WinUI (`https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/ci-for-winui3`),
MSIX + SignTool (`https://learn.microsoft.com/en-us/windows/msix/package/sign-app-package-using-signtool`), code
signing options and SmartScreen reputation (`https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options`,
`…/smartscreen-reputation`), Azure Artifact Signing (`https://learn.microsoft.com/en-us/azure/artifact-signing/` and
`https://github.com/Azure/artifact-signing-action`), App Installer files
(`https://learn.microsoft.com/en-us/windows/msix/app-installer/how-to-create-appinstaller-file` — 2021 schema),
WinGet (`https://learn.microsoft.com/en-us/windows/package-manager/package/`, `https://github.com/microsoft/winget-create`),
the Microsoft Store Developer CLI (`https://github.com/microsoft/msstore-cli`), .NET publishing
(`https://learn.microsoft.com/en-us/dotnet/core/deploying/`) and SonarScanner for .NET; the fact base of the planned
Windows Apps section of irurueta docs (https://github.com/albertoirurueta/docs/issues/191) and irurueta docs:
programming-languages/csharp/build-and-tooling.adoc and testing.adoc.

The templates assume `iru-setup-windows-app`'s **scaffold contract** (its Step 8 table): `<AppName>.slnx`,
`src/<AppName>.Core` (`net10.0`), `src/<AppName>` (`UseWPF`/`UseWindowsForms` on `net10.0-windows`, or `UseWinUI` on
`net10.0-windows10.0.26100.0` with `Platforms x64;ARM64`), `tests/<AppName>.Core.Tests`, optional
`tests/<AppName>.UITests` (`[TestCategory("UI")]`, `APP_EXE`/`APP_AUMID`), `tests/coverlet.runsettings` (Cobertura +
OpenCover, UI assembly excluded), root `docfx.json` → `build/docfx/`, `Directory.Build.props` `<Version>`, and for
WPF/WinForms MSIX `packaging/AppxManifest.xml` + `packaging/build-msix.ps1`. If the layout differs (a `.sln`, no
`*.Core` project, a `.wapproj` packaging project, no runsettings), warn, ask whether to stop or continue, and when
continuing read the real project paths (`dotnet sln <file> list`) and adapt every command, listing each adaptation
in the report.

## Step 0 — Resolve inputs

Parse `args` (`key: value` lines) first, e.g. (what `iru-setup-windows-repository` passes):

```
branching: gitflow
integration-branch: develop
stable-branch: main
ci-tests: full
open-source: yes
visibility: public
pages: yes
distribution: internal
signing: artifact-signing
winget: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_bookshelf
sonar-host-url: https://sonarcloud.io
mode: new
```

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `branching` | `gitflow` / `main-only` | `gitflow` when an integration branch exists or `<Version>` ends in `-dev`, else `main-only`; confirm |
| `integration-branch` / `stable-branch` | branch names | `develop` / `main` (confirm against `git branch -a`) |
| `ci-tests` | `unit` / `full` — `full` adds the FlaUI desktop UI tests to the `windows` job | public `full`, private/internal ask with `unit` recommended; forced to `unit` (say so) when there is no `tests/<AppName>.UITests` |
| `pages` | `yes` / `no` | public `yes` without asking; private/internal ask, `no` recommended |
| `distribution` | `none` / `internal` / `store` | `internal` when `open-source: yes`, otherwise ask with `internal` recommended |
| `signing` | `artifact-signing` / `certificate` / `none` | asked when `distribution` ≠ `none`; Artifact Signing recommended |
| `winget` | `yes` / `no` | asked only for `distribution: internal` + `signing` ≠ `none` + public; `no` recommended until the first manual submission is merged |
| `winget-package-id` | WinGet `PackageIdentifier` | the MSIX `Identity/@Name` (e.g. `JaneDoe.Bookshelf`); must equal the identifier of the first manual submission |
| `java-version` | `actions/setup-java` JDK for SonarScanner | `21` |
| `sonar` (+ coordinates) | `cloud` / `self-hosted` / `none` | see Standalone resolution |
| `open-source` | `yes` / `no` | `gh repo view --json isPrivate` if unresolved |
| `visibility` | `public` / `private` / `internal` (`internal` behaves like `private`) | see Standalone resolution |
| `mode` | `new` / `existing` | informational only — every existing file is still asked about |
| `on-existing` | `ask` / `keep` / `update` / `replace` | `ask` |
| `security-dependency-review` / `security-codeql` / `security-osv` / `security-gitleaks` | gate one `security.yml` job each | `yes` |
| `ci`, `license`, `developer-*`, `organization-url`, `ui`, `architecture`, `msix`, `ui-tests`, `rids`, identity keys | shared vocabulary | accepted and ignored — `ui`, MSIX packaging, UI tests and RIDs are detected from disk (Step 1); named in the report |

### Standalone resolution of `visibility`, `sonar`, `ci-tests`, `pages`, `distribution`, `signing`, `winget`

In this order, skipping any `args` supplied. Quote cost facts "per GitHub's current pricing" (verified October 2026)
and tell the user to double-check their plan: standard GitHub-hosted runners are **free for public repositories**;
private/internal ones consume the account's included minutes — **2,000/month on GitHub Free, 3,000 on Pro and Team,
50,000 on Enterprise Cloud** — then are billed per minute (**Linux 2-core $0.006, Windows $0.010 — ~1.7×**). The
`windows` job typically takes 5–10 minutes (WinUI: 10–20 — the Windows App SDK restore is large), each release matrix
leg 10–20 Windows minutes.

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercase), else ask Public/Private (Public
   recommended when open source). Open source + private is contradictory — warn and ask.
2. **`sonar`** — "Send CI analysis and coverage reports to SonarCloud / SonarQube?" `cloud` recommended only for
   public + open source; otherwise `none` recommended, `self-hosted` offered (SonarCloud is free only for
   public/open-source projects; Community Build is free but self-run). For `cloud`/`self-hosted` collect
   `sonar-organization` (cloud only) / `sonar-project-key` / `sonar-host-url` — suggest `<owner>-github` /
   `<owner>_<repo>` / `https://sonarcloud.io` from `git remote get-url origin`. No Sonar file is written: SonarScanner
   for .NET takes them on its command line.
3. **`ci-tests`** (only when `tests/<AppName>.UITests` exists) — **Unit tests only** (`unit`: Core tests on Linux +
   the Windows build and non-UI tests) / **Unit + UI tests** (`full`: also the FlaUI tests on the Windows runner, which
   has an interactive desktop — several more Windows minutes per run; for WinUI it also packages and installs the app).
4. **`pages`** — public: `yes`. Private/internal: **No (Recommended)** ("docs still built and uploaded as the
   `docs-site` artifact") / **Yes** ("requires Pro/Team/Enterprise Cloud; the site is PUBLIC unless Enterprise Cloud
   private Pages").
5. **`distribution`** — **None** ("CI only") / **Internal (GitHub Releases)** ("self-contained zips per
   architecture and, when signed, an MSIX + `.appinstaller` auto-update file on each GitHub Release") / **Microsoft
   Store** ("MSIX submitted through Partner Center; the Store signs and updates the app — needs a Partner Center app
   reservation and an Entra ID app registration").
6. **`signing`** (only with `distribution` ≠ `none`) — **Azure Artifact Signing (Recommended)** ("Microsoft's managed
   signing service, formerly Trusted Signing; no certificate file stored; builds SmartScreen reputation; needs an Azure
   subscription and identity validation") / **Certificate (.pfx)** ("an OV/EV code-signing certificate as a base64
   secret, used with SignTool; EV no longer bypasses SmartScreen") / **None** ("unsigned zips only — no MSIX on the
   release, since an unsigned MSIX can't be installed; SmartScreen warns on first run"; with `store` it is fine — the
   Store signs the MSIX).
7. **`winget`** (only `distribution: internal` + `signing` ≠ `none` + public) — **No (Recommended until the first
   manual submission is merged)** / **Yes** ("each release opens a `wingetcreate update` PR to `microsoft/winget-pkgs`;
   needs a `WINGET_TOKEN`, a classic PAT with `public_repo`; the first submission is manual — `wingetcreate new`").

**Invalid combinations** (from `args` or answers — report and re-ask, never silently change): `ui: winui` +
`distribution: internal` + `signing: none` (WinUI ships only an MSIX, and an unsigned MSIX can't be installed —
choose a signing method or `store`); `distribution: store` with WPF/WinForms but no `packaging/` (the Store needs an
MSIX — re-run `iru-setup-windows-app` with `msix: yes`, or choose `internal`); `winget: yes` on a private repository
(winget-pkgs needs publicly downloadable URLs) or without signing.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 1 — Survey the repository

- **Scaffold detection**: the `WinExe` project under `src/` (its name is `<AppName>`; `UseWPF`/`UseWindowsForms` →
  `ui: wpf|winforms`, `UseWinUI` → `ui: winui`), `<AppName>.slnx`, `tests/<AppName>.Core.Tests`,
  `tests/<AppName>.UITests` (→ the `flaui` condition), `tests/coverlet.runsettings`, `docfx.json`, MSIX packaging
  (`packaging/AppxManifest.xml` + `packaging/build-msix.ps1`, or WinUI's `src/<AppName>/Package.appxmanifest` — always
  present for WinUI), the RIDs (`build-msix.ps1`'s `ValidateSet` / WinUI `<RuntimeIdentifiers>`; drop the `win-arm64`
  matrix leg when only `win-x64` is listed), `Directory.Build.props` `<Version>` (`-dev` suffix → gitflow hint), the
  MSIX `Identity/@Name` and `@Publisher` (the `winget-package-id` default; the `Publisher` must equal the signing
  certificate's subject or the Artifact Signing certificate profile's subject — remind the user).
- `docs/antora.yml`/`docs/antora-playbook.yml` — missing → recommend running `iru-setup-antora` first (the docs job
  fails without them).
- `docs/package-lock.json` — the docs job runs `npm ci` with the setup-node npm cache keyed on it, so it fails at
  `actions/setup-node` without a committed lockfile. `docs/` present without one (a site from the old per-package
  install) → recommend `/iru-update-antora` first; no `docs/` → `iru-setup-antora`, which commits it.
- Branch names (`git branch -a`).
- Existing files: `.github/workflows/build.yml`, `release.yml`, `sync.yml`, `security.yml`,
  `.github/scripts/sync_versions.py`, `.github/scripts/make_appinstaller.py`, `.github/dependabot.yml`; also note
  sibling-named files (`dotnet.yml`, `ci.yml`, `main.yml`, `codeql.yml`) that would duplicate this pipeline.

### Announce the mode, then warn and ask per existing file

- None exist: "**Creating the CI/CD pipeline from scratch**." Every file is *created*.
- Any exists: "**Updating an existing CI/CD pipeline** — found: <paths>." Unless `on-existing` is `keep`/`update`/
  `replace` (apply it to every file, still printing the warning), ask with `AskUserQuestion`, one question per file
  (`sync.yml` + `sync_versions.py` count as one, `release.yml` + `make_appinstaller.py` as one; batch up to 4), naming
  each concrete mismatch (e.g. "`build.yml` runs everything on `windows-latest` — the Core tests and docs could run on
  Linux at ~1/1.7 the cost", "`build.yml` deploys to Pages but you chose `pages: no`", "`release.yml` signs with a
  `.pfx` but you chose Artifact Signing", "an existing `codeql.yml` would run CodeQL twice"):
  - **Update (Recommended)** — merge using the templates as reference; keep every step/job/entry this skill doesn't
    own (notifications, extra matrix legs, other Dependabot ecosystems); you review a diff first. Customizations
    survive, but a customized part this skill owns may be rewritten.
  - **Keep as is** — untouched; it may disagree with this run's choices — the report lists each mismatch.
    Recommended only for a `.github/dependabot.yml` that already has `nuget` and `github-actions` entries.
  - **Replace** — regenerate from the template; every customization is lost (git history only). Never the default.
  - **Stop** (first question only) — change nothing; report the existing files and end.

## Step 2 — Look up versions at run time

Resolve every `uses:` major with `git ls-remote --tags https://github.com/<owner>/<repo>` (the REST `/tags` listing
can miss floating majors) and the two .NET tools from the NuGet flat container
(`curl -fsSL https://api.nuget.org/v3-flatcontainer/<id>/index.json` → highest version without a `-` suffix); fall back
to this table, confirmed on 2026-10-16:

| Action / tool | Version | Floating major? |
|---|---|---|
| `actions/checkout` | `v7` | yes |
| `actions/setup-dotnet` (`global-json-file: global.json`) | `v6` | yes |
| `actions/setup-java` / `actions/setup-node` / `actions/setup-python` | `v6` / `v7` / `v7` | yes |
| `actions/cache` | `v6` | yes |
| `actions/upload-artifact` / `actions/download-artifact` | `v7` / `v8` | yes |
| `actions/upload-pages-artifact` / `actions/deploy-pages` | `v5` / `v5` | yes |
| `microsoft/setup-msbuild` | `v3` | yes |
| `azure/login` / `azure/artifact-signing-action` (formerly `azure/trusted-signing-action`) | `v3` / `v2` | yes |
| `microsoft/microsoft-store-apppublisher` (sets up `msstore`; formerly `microsoft/setup-msstore-cli`) | `v1` | yes |
| `github/codeql-action` (`init`/`analyze`) | `v4` | yes (don't use `/releases/latest` — it returns a `codeql-bundle-*` tag) |
| `gitleaks/gitleaks-action` | `v3` | yes |
| `actions/dependency-review-action` | **`v5.0.0`** | **no** — pin the exact tag |
| `google/osv-scanner-action` reusable workflow | **`v2.7.0`** | **no** — pin the exact tag |
| `dotnet-sonarscanner` (`<sonarscanner-version>`) | `12.0.0` | NuGet |
| `docfx` (`<docfx-version>`) | `2.81.0` | NuGet |
| `wingetcreate` | downloaded at run time from `https://aka.ms/wingetcreate/latest` (1.12.13.0 on 2026-10-16) | n/a |

## Step 3 — Render the templates

Read `reference/workflow-templates.md`, resolve the markers for this run, and substitute the placeholders it lists.
Notes that matter when adapting them:

- **`core` job (Linux)** — WPF/WinForms: restore, build and `dotnet format` the whole solution (the FlaUI project
  compiles everywhere; it is not a test project off Windows). WinUI: only `tests/<AppName>.Core.Tests` (which builds
  Core) — and `dotnet format` once per project, because formatting a project does not check its references (verified).
  Coverage: `dotnet test tests/<AppName>.Core.Tests … --settings tests/coverlet.runsettings --collect "XPlat Code
  Coverage" --results-directory TestResults` → `TestResults/<guid>/coverage.cobertura.xml` (the 80% line bar, read by
  an inline Python check) + `coverage.opencover.xml` (Sonar). The UI assembly is excluded by the runsettings, so the
  bar measures Core; the UI layer is "not measured". Analyzer findings fail the build itself (warnings as errors), so
  no SARIF is uploaded: the per-project logs share one tool name, and code scanning rejects several same-tool runs in
  one upload.
- **`windows` job** — `dotnet build <AppName>.slnx -c Release` (WinUI builds here only) and `dotnet test … --filter
  "TestCategory!=UI"`. With `ci-tests: full`: WPF/WinForms run the UI tests with `APP_EXE` pointing at
  `src/<AppName>/bin/Release/net10.0-windows/<AppName>.exe`; WinUI first builds an MSIX with MSBuild signed by a
  throwaway self-signed certificate whose subject is the manifest's `Publisher`, trusts it (`LocalMachine\TrustedPeople`),
  installs it with `Add-AppxPackage` and exports `APP_AUMID=<PackageFamilyName>!App` — a packaged WinUI exe cannot start
  without package identity. Runners are fresh VMs, so the local `.integration-test.lock` isn't needed in CI. The UI
  step sets `UI_SCREENSHOTS_DIR` to the workspace's `.ui-screenshots/`, and a following `actions/upload-artifact` step
  (`if: always()`, `retention-days: 7`, `if-no-files-found: ignore`) uploads the captures (and the
  `a11y-report.txt` of `accessibility: report`) as the `ui-screenshots` artifact — no extra job or runner (rendered
  for `ui: wpf` and `ui: winui` and checked with actionlint on 2026-10-16).
- **`sonar` job** — `begin` → `dotnet build` → Core tests (OpenCover + TRX) → `end`, with
  `sonar.coverage.exclusions=src/<AppName>/**,tests/**` so the UI project is not counted as uncovered. SonarScanner's
  MSBuild targets force `TreatWarningsAsErrors=false` during the analysis build (verified in 12.0.0's
  `SonarQube.Integration.targets`), so its own rules never break the build. Linux for WPF/WinForms; Windows for WinUI
  (the scanner analyzes what the build compiles). Skipped for fork PRs, which never get `SONAR_TOKEN`.
- **`docs` job** — DocFX (`docfx docfx.json --warningsAsErrors`, Core-only metadata, so Linux for every `ui`) into
  `build/docfx/`, merged under the Antora site's `api/` — the layout the other stacks use. Only a push to the stable
  branch uploads the Pages artifact and triggers `deploy-docs`.
- **`release.yml`** — `release: [released]` + `workflow_dispatch` with a `tag` input (dry run), like the Android/KMP
  siblings. The `package` matrix (`win-x64`, `win-arm64` on `windows-latest`) first checks that the tag equals
  `Directory.Build.props` `<Version>` and that it has no `-dev` suffix. WPF/WinForms: `dotnet publish` self-contained
  single-file (never trimming/AOT — NETSDK1168/NETSDK1175) for the zip, and `packaging/build-msix.ps1 -RuntimeIdentifier
  <rid> -Version <x.y.z>` for the MSIX (unsigned: no `WINDOWS_CERTIFICATE_PATH` is set there). WinUI: Microsoft's
  CI-for-WinUI recipe — `msbuild /restore` with `GenerateAppxPackageOnBuild=true`, `AppxBundle=Never`,
  `UapAppxPackageBuildMode=SideloadOnly`, `Platform=x64|ARM64`, `SelfContained=true`, `AppxPackageSigningEnabled=false`
  (and `WindowsAppSDKSelfContained=true` for `internal`, so a sideloaded MSIX doesn't depend on the Windows App Runtime
  framework package being installed; the Store installs that dependency itself). Then **one signing step** for every
  file — the zip's exe (SmartScreen) and, for `internal`, the MSIX: `azure/artifact-signing-action` after an OIDC
  `azure/login` (the job runs in the `release` environment, whose federated credential the Azure app trusts), or
  `signtool sign /fd SHA256 /tr <timestamp> /td SHA256` with the PFX decoded from `WINDOWS_CERTIFICATE_PFX_BASE64` to a
  temp file that is deleted afterwards. `github-release` (Linux) attaches the zips, the signed MSIX files and — rendered
  by `make_appinstaller.py` from the committed manifest's `Name`/`Publisher` — one `<AppName>-<arch>.appinstaller` per
  architecture whose own `Uri` is `releases/latest/download/…` (App Installer 2021 schema: `MainPackage` with
  `ProcessorArchitecture`, `OnLaunch` checks every 12 h with a prompt, `AutomaticBackgroundTask`). `winget` runs
  `wingetcreate update <winget-package-id> --version <x.y.z> --urls "<url>|x64" "<url>|arm64" --submit`. `store`
  bundles the per-architecture MSIX files with `makeappx bundle`, keeps the bundle as an artifact, then runs `msstore
  reconfigure` + `msstore publish src/<AppName> --inputDirectory artifacts/store --appId <STORE_PRODUCT_ID>` (which
  commits the submission for certification; add `--noCommit` to keep a draft).
- **`sync.yml`** — after a release from the stable branch: merge it into the integration branch and run
  `sync_versions.py` with `NEXT_DEV=x.(y+1).0-dev` — the minor bump `iru-dotnet-bump-version`'s Step 5 defines and
  `iru-release` uses: `Directory.Build.props` `<Version>` (or `<VersionPrefix>`/`<VersionSuffix>`), every
  `Package.appxmanifest`/`AppxManifest.xml` outside `bin/`/`obj/`/`AppPackages/`/`artifacts/` → `x.(y+1).0.0`
  (`MinVersion`/`MaxVersionTested` untouched), README rows and `docs/antora.yml`. The commit stages with `git add -u`
  — a pathspec list aborts the whole add when one path matches nothing (verified). The branch is
  `sync_<released version>` (release `1.4.0` → `sync_1.4.0`). If the merge conflicts, the PR is opened from the release
  commit (title "Sync 1.4.0 (merge conflicts to resolve)") without bumping; a conflicting sync never bumps, so it cannot
  lower the integration branch's development version.
- **`security.yml`** — CodeQL `csharp` on Linux: `build-mode: manual` with `dotnet build <AppName>.slnx --no-incremental
  -p:UseSharedCompilation=false` for WPF/WinForms (precise, sees source-generated code); `build-mode: none` for WinUI
  (the app can't build on Linux, and a Windows CodeQL job would cost ~1.7× — buildless extraction still covers every
  `.cs` file, but code generated from XAML at build time is not analyzed). OSV-Scanner is kept for the shared block,
  but it finds NuGet packages only through lock files (`packages.lock.json`) — with central package management and no
  lock files the NuGet audit inside every restore (NU1901–NU1904 are errors with warnings-as-errors) is the effective
  vulnerability gate.

## Step 4 — Write the files

Apply Step 1's per-file decision (create / Update after a diff / Keep as is / Replace). `sync.yml` and
`sync_versions.py` are always written together (gitflow only); `release.yml` only when `distribution` ≠ `none`,
with `make_appinstaller.py` only for `internal` + MSIX + signed; `security.yml` and `.github/dependabot.yml` always. If
an existing `.github/dependabot.yml` has other ecosystems (`npm` for `docs/`, …), keep them and only add/update `nuget`
and `github-actions`.

## Step 5 — Verify

In a throwaway copy (or `$TMPDIR`), never in a way that commits anything:

1. `actionlint` (with `shellcheck` on `PATH`) over every written workflow; if `actionlint` isn't installed, fall back
   to `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>` (a PyYAML parse turns the `on:` key into `True` — a false flag,
   never quote `on:`). With PowerShell 7 available, parse every `shell: pwsh` script with
   `[System.Management.Automation.Language.Parser]::ParseInput` (replace `${{ … }}` expressions with a dummy first).
2. `python3 -m py_compile .github/scripts/*.py`.
3. When the scaffold is present and `dotnet` is installed, run the `core` job's commands and the DocFX step locally
   through `iru-gate-runner` (they're long) to confirm the paths the workflows reference.

Everything that needs Windows runners, GitHub, certificates or Partner Center stays **unverified locally** — say so.

## Step 6 — Report

Summarize: the mode (from scratch / updating), and per file the action taken — **created / updated / replaced / kept
as is** (or Stop) — with each mismatch of a kept file, and any adaptation for a non-standard layout. State what was
wired and why: the detected `ui`, MSIX packaging, UI tests and RIDs; the job/runner layout (Linux: `core`, `docs`,
CodeQL, Sonar for WPF/WinForms; Windows: `windows`, Sonar for WinUI, every release packaging job — restating the
~1.7× Windows cost for private repositories); `branching` (no `sync.yml` for `main-only` — say so plainly);
`visibility` (and where it came from); `ci-tests`; `pages`; `sonar`; `distribution`/`signing`/`winget` (which release
jobs exist, that the first WinGet submission and the Partner Center reservation are manual); the security jobs; any
ignored `args` keys. Then the required-secrets table — only the rows that apply:

| Secret / variable | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` (secret) | Sonar scan auth | `sonar` ≠ `none` |
| `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_SUBSCRIPTION_ID` (secrets) | OIDC `azure/login` of an Entra app with a federated credential for `repo:<owner>/<repo>:environment:release` and the **Artifact Signing Certificate Profile Signer** role | `signing: artifact-signing` |
| `ARTIFACT_SIGNING_ENDPOINT` / `ARTIFACT_SIGNING_ACCOUNT` / `ARTIFACT_SIGNING_CERTIFICATE_PROFILE` (repository **variables**) | the account's regional endpoint (e.g. `https://eus.codesigning.azure.net/`), account and certificate profile names | `signing: artifact-signing` |
| `WINDOWS_CERTIFICATE_PFX_BASE64` / `WINDOWS_CERTIFICATE_PASSWORD` | code-signing `.pfx` (base64) and its password for SignTool | `signing: certificate` |
| `WINGET_TOKEN` | classic PAT with `public_repo` for `wingetcreate --submit` | `winget: yes` |
| `PARTNER_CENTER_TENANT_ID` / `PARTNER_CENTER_CLIENT_ID` / `PARTNER_CENTER_CLIENT_SECRET` / `PARTNER_CENTER_SELLER_ID` | Entra app registered in Partner Center (Manager role) for `msstore reconfigure` | `distribution: store` |
| `STORE_PRODUCT_ID` | the Store product ID of the reserved app | `distribution: store` |
| `GITHUB_TOKEN` | release uploads, `sync.yml`'s `gh` calls, CodeQL/gitleaks | automatic |

Repository settings to call out: Settings → Actions → General → "Workflow permissions" must allow GitHub Actions to
create pull requests (gitflow `sync.yml`); with `pages: yes`, Settings → Pages → Source = **GitHub Actions** (and a
Pro/Team/Enterprise Cloud plan for a private repository — the site is public unless Enterprise Cloud private Pages);
with Artifact Signing, an environment named **`release`** (optionally with required reviewers) whose name matches the
federated credential; with `store`, the app reserved in Partner Center with its `Identity/@Name`/`@Publisher` copied into
the manifest.

### Known quirks / verification notes (2026-10-16)

- **Rendered and checked for real** in `$TMPDIR/iru-verify/windows/iru-setup-windows-github-workflows/` (copies of
  `iru-setup-windows-app`'s verified variants, outside this repository): (1) `wpf` — gitflow, `ci-tests: full`,
  `pages: yes`, `sonar: cloud`, `distribution: internal`, `signing: artifact-signing`, `winget: yes`; (2)
  `winforms-mvc` — main-only, `ci-tests: unit`, `pages: no`, `sonar: none`, `internal`, `signing: certificate`; (3)
  `winui` — gitflow, `ci-tests: full`, `pages: yes`, `sonar: self-hosted`, `distribution: store`, `signing: none`; every
  security job in all three. All workflows passed `actionlint` 1.7.12 with shellcheck (exit 0) and a Ruby YAML parse;
  every `pwsh` script parsed cleanly with PowerShell 7.6.7's parser (all downloaded into temp directories, never
  installed).
- **Linux steps executed** with .NET SDK 10.0.402 on macOS arm64: the `core` commands for all three (WPF/WinForms
  solution restore/build/format, WinUI Core-test-project build + per-project format; Core tests 9/13/9 passed,
  coverage 100% so the inline 80% check passed — and failed as intended on a 50% fixture), DocFX 2.81.0 with
  `--warningsAsErrors` (0 warnings), the CodeQL `manual` build command (WPF/WinForms), the release `dotnet publish`
  single-file for `win-x64` (only `<AppName>.exe` + pdb/xml), and the release version guard, files-to-sign and
  `Compress-Archive` steps in PowerShell 7 (the guard rejects `1.0.0-dev` and a tag mismatch). Off Windows the WinUI
  solution build fails at `XamlCompiler.exe: cannot execute binary file`, exactly as the scaffold documents — hence
  the Linux job's Core-only path.
- `make_appinstaller.py` rendered per-architecture files for the WPF (`packaging/AppxManifest.xml`) and WinUI
  (`Package.appxmanifest`) copies; a structural check against the 2021 schema shape (root namespace and attributes,
  element order `MainPackage` → `UpdateSettings`, mandatory `ProcessorArchitecture`, four-part versions ≤ 65535,
  `OnLaunch` attributes) passed, including a `Publisher` containing `&` and quotes (escaped correctly). No App
  Installer XSD validation or install was possible here.
- `sync_versions.py` against the WPF and WinUI copies (`<Version>1.4.0</Version>`, manifests `1.4.0.0`, README rows,
  `antora.yml`) with `RELEASE_VERSION=1.4.0 NEXT_DEV=1.5.0-dev`: `<Version>` → `1.5.0-dev`, manifests → `1.5.0.0`,
  `MinVersion`/`MaxVersionTested` and a manifest copy under `bin/` untouched, README → `1.5.0-dev`/`1.4.0`,
  `antora.yml` → `1.4.0`; a non-`-dev` `NEXT_DEV` exits 1. The `sync.yml` version computation was run in bash
  (`v1.4.0` → `1.5.0-dev`, `sync_1.4.0`; `v1.4` rejected).
- **Unverified locally, never a blocker**: every job on GitHub's runners; the Windows solution build/tests and the
  FlaUI run; the WinUI self-signed package-and-install step; `build-msix.ps1`'s `makeappx` half and the WinUI MSBuild
  packaging (`AppxPackageDir`, `WindowsAppSDKSelfContained` for a packaged app); Artifact Signing (OIDC, MSIX
  signing), SignTool with a real certificate; the GitHub Release upload; App Installer downloading from GitHub release
  URLs (redirects to a CDN) and its updates; `wingetcreate update` (installer type must match the first manual
  submission — MSIX here, the zip only when the app has no MSIX); `msstore publish` picking the bundle from
  `--inputDirectory` for a WPF/WinForms project (fallback: upload the `store-msixbundle` artifact in Partner Center by
  hand); the Sonar scan; CodeQL extraction (`none` mode for WinUI); OSV-Scanner/gitleaks/dependency-review results;
  Pages deployment; the Antora half of the docs job (no Antora site in the scaffold copies — identical to the KMP/Android
  siblings' verified steps).

**Warn explicitly:**

- Review every generated workflow before relying on it; do a `workflow_dispatch` dry run of `release.yml` with an
  existing tag before a real release — it signs and uploads (and with `store`, submits for certification).
- The MSIX `Identity/@Publisher` must equal the signing certificate's (or Artifact Signing profile's) subject exactly,
  or signing fails; for the Store it must equal the Partner Center values.
- Release assets of a **private** repository need authentication, so App Installer and WinGet can't download them —
  `internal` on a private repository only suits people with repository access (download manually); host the MSIX
  elsewhere for real auto-updates. The `ms-appinstaller:` protocol is disabled by default: users download the
  `.appinstaller` file and open it.
- Every secret/variable above and the Sonar project, Azure Artifact Signing account + certificate profile (identity
  validation takes days), Partner Center reservation and first WinGet submission must exist before the release jobs
  pass; this skill creates none of them.
- On a private repository the `windows` job runs on every push and PR at ~1.7× Linux cost; `ci-tests: full` adds the
  UI-test minutes — revisit it if the budget runs out.
