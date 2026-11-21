---
name: iru-setup-windows-gitignore
description: "/iru-setup-windows-gitignore [args] creates or updates the root .gitignore for a .NET Windows desktop app repository (iru-setup-windows-app layout or any sln/slnx desktop solution): build output, IDE state, test and coverage output, MSIX output, signing material, DocFX, UI screenshots. Args: mode, on-existing. Shows a diff and asks before touching an existing file."
model: haiku
---

# Setup Windows Gitignore

Create or refresh the root `.gitignore` of a Windows desktop application built on .NET (WPF, WinUI 3 / Windows App
SDK, or Windows Forms). The template is a trimmed, house-style version of what a desktop solution actually produces —
not a copy of GitHub's 400-line `VisualStudio.gitignore`, which ignores things this catalog's scaffold commits (publish
profiles) and carries decades of irrelevant tooling. Never overwrite an existing `.gitignore` without showing the user
exactly what would change and getting explicit approval first — it can carry hand-added, project-specific entries that
must not be dropped silently.

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines, skipping any question already answered:

- `mode: new|existing` — if omitted, detect it: a root `.gitignore` already present means `existing`; its absence
  means `new`. `mode: existing` never means "update silently" — the existing file still goes through Step 5's Update /
  Keep as is / Replace question (unless `on-existing` decides it).
- `on-existing: ask|keep|update|replace` — how Step 5 treats an existing `.gitignore`. Default `ask`.
  `keep`/`update`/`replace` applies that choice without asking (the warning and diff are still printed). Only meant for
  scripted runs; an orchestrator passes it only if its own `args` received it.

Any other key (e.g. the full shared-args set an orchestrator forwards) is ignored and named in the report.

## Step 1 — Survey the repository

Detect the following before composing content — each gates a Step 3 conditional section:

- `docfx.json` at the root or under `docs/` → DocFX in use. Read its `metadata[].dest` (default `api`) and
  `build.dest` (default `_site`) so Step 3 ignores the folders it actually writes, relative to the `docfx.json`.
- `packaging/` with an `AppxManifest.xml`, a `Package.appxmanifest` anywhere, or a `*.wapproj` → MSIX packaging in use.
- `*.sln`/`*.slnx` and every `*.csproj` — confirms this is a .NET repository; if neither exists, warn that this skill
  targets .NET desktop solutions and ask whether to continue.
- `docs/antora.yml` → Antora (its lines are unconditional anyway).
- A tracked file under a path the template would ignore (`git ls-files -- 'bin/*' 'obj/*' '*/bin/*' '*/obj/*'
  '*.user' '.vs/*' '*.pfx'`) — flag each one in the report: writing `.gitignore` never untracks anything.
- The root `.gitignore`, if present — read it.

Tell the user which of the two this run is doing:

- **Creating from scratch** — no root `.gitignore`: write Step 4's content without asking.
- **Updating existing** — a root `.gitignore` exists: warn up front that Step 5 will ask whether to Update, Keep as
  is, or Replace it (or apply `on-existing`) rather than overwrite anything silently.

## Step 2 — The root `.gitignore` base template

Unconditional — include it verbatim:

```gitignore
# .NET build output (per project) and the .NET 8+ artifacts output layout
[Bb]in/
[Oo]bj/
artifacts/
publish/

# Visual Studio / Rider / VS Code user state
.vs/
*.user
*.suo
*.userprefs
*.rsuser
.idea/
.vscode/*
!.vscode/settings.json
!.vscode/tasks.json
!.vscode/launch.json
!.vscode/extensions.json

# Test results and coverage (dotnet test, coverlet, ReportGenerator)
TestResults/
*.trx
coverage*.xml
coverage*.json
*.coverage
*.coveragexml
coveragereport/

# Analyzer SARIF logs written by `dotnet build -p:ErrorLog=...` (iru-dotnet-code-quality)
*.sarif

# NuGet
*.nupkg
*.snupkg
.nuget/packages/

# Signing material — never commit (code-signing certificates and keys)
*.pfx
*.p12
*.cer
*.key

# Antora documentation build output (see iru-setup-antora)
docs/build/
docs/node_modules/

# OS files
.DS_Store
Thumbs.db
ehthumbs.db
Desktop.ini

# Repository-wide lock serializing desktop UI-test runs (iru-dotnet-test ui, iru-windows-code-one-task-group)
.integration-test.lock/

# UI-test screenshots (state × direction × theme), regenerated on every full UI run; docs copy what they use
.ui-screenshots/
```

Notes on specific lines:

- `[Bb]in/`/`[Oo]bj/` are **unanchored**, so they match `src/<AppName>/bin/`, `tests/<AppName>.Core.Tests/obj/` and
  every other project's output. There is deliberately no bare `build/` line: nothing in the scaffold writes a
  `build/` folder except the DocFX/Antora outputs handled explicitly, and an unanchored `build/` would hide a
  hand-written `build/` folder of MSBuild `.targets` some solutions keep.
- `*.snk` (strong-name key) is **not** ignored: a public strong-name key is routinely committed. A private signing
  certificate (`*.pfx`) never is — CI decodes it from a secret.
- `*.sarif` covers the `dotnet-code-quality.sarif` log `iru-dotnet-code-quality` writes at the repository root.
- `.vscode/*` keeps the shared `settings.json`/`tasks.json`/`launch.json`/`extensions.json` tracked.
- `Properties/PublishProfiles/*.pubxml` stays **tracked**: WinUI's publish profiles (one per RID) are read by
  `dotnet publish -p:PublishProfile=win-x64` and contain no credentials (unlike Web Deploy profiles, which is why
  GitHub's `VisualStudio.gitignore` ignores `*.pubxml`). Never add `*.pubxml` here.
- `.integration-test.lock/` is the atomic-`mkdir` lock directory (with its owner file) that the Windows UI-test stages
  create — always transient, never committed.
- `.ui-screenshots/` holds the PNGs the FlaUI UI tests capture (`<kind>/<Id>/<state>--<direction>--<theme>.png`); the
  test gate clears it at the start of every full UI run, and the documentation skills copy the images they use into
  the Antora module's `images/ui/` — the only committed copies. `.ui-quality.yml` (the accessibility level and the
  screenshot matrix) is configuration and stays tracked; never ignore it.

## Step 3 — Detect project-specific additions

Only add a section when Step 1 found what it targets; every line flush-left (git does not trim leading whitespace — a
snippet copied with Markdown indentation silently matches nothing):

- **MSIX packaging** (`packaging/AppxManifest.xml`, a `Package.appxmanifest`, or a `*.wapproj` found):
  ```gitignore
  # MSIX / App Installer build output
  AppPackages/
  BundleArtifacts/
  *.msix
  *.msixbundle
  *.appx
  *.appxbundle
  *.msixupload
  *.appxupload
  *.appinstaller
  packaging/layout/
  ```
  `packaging/layout/` is the staging folder `packaging/build-msix.ps1` fills before `makeappx pack`; `*.appinstaller`
  is generated by the release workflow, never hand-written in this scaffold. If the repository keeps a hand-written
  `.appinstaller` template tracked, drop that line and say so.
- **DocFX** (`docfx.json` found): ignore the two folders it generates — the `metadata[].dest` folder (API `.yml`
  files, its generated `toc.yml` and `.manifest`) and the `build.output`/`build.dest` site — as paths relative to the
  `docfx.json` directory. For `iru-setup-windows-app`'s root `docfx.json` (`"dest": "docfx/api"`, `"output":
  "build/docfx"`):
  ```gitignore
  # DocFX output (docfx metadata / docfx build)
  /docfx/api/
  /build/docfx/
  ```
  With DocFX's defaults (no `dest`/`output` set) the folders are `api/` and `_site/` instead. Prefix each with the
  `docfx.json`'s directory when it is not at the root. Before ignoring the metadata folder wholesale, check
  `git ls-files <dest>/` — if hand-written files live there (e.g. an `index.md` landing page in an older DocFX
  layout), ignore only `<dest>/*.yml` and `<dest>/.manifest` instead, and say so. The hand-written `docfx/index.md`,
  `docfx/toc.yml` and any `docfx/articles/` stay tracked.
- Don't invent entries beyond what was detected.

## Step 4 — Compose the proposed content

Step 2's base template plus the Step 3 sections that matched, each under its comment header. If a root `.gitignore`
exists, merge: keep every existing line not already covered (hand-added ignores survive) and don't repeat a line
already present verbatim.

Before proposing, check the template doesn't hide source the scaffold commits — in the working tree, run
`git check-ignore -v` on whichever of these exist and refuse to add a line that matches any of them:
`Directory.Build.props`, `Directory.Packages.props`, `global.json`, `*.slnx`, `src/*/Properties/PublishProfiles/*.pubxml`,
`packaging/AppxManifest.xml`, `packaging/Assets/*.png`, `src/*/Assets/*.png`, `src/*/Package.appxmanifest`,
`tests/coverlet.runsettings`, `docfx.json`, `docfx/index.md`, `docfx/toc.yml`.

## Step 5 — If a `.gitignore` already existed, warn and ask Update / Keep as is / Replace

Skip when Step 1 found no root `.gitignore` — write it straight away.

Otherwise, if the proposed content differs from disk:

- Show the proposed **Update** content as a diff (write the draft to a temp path and run
  `git diff --no-index <current> <draft>`). Say which hand-added lines a **Replace** would drop, and name any
  conflicting existing line (e.g. `*.pubxml`, which would hide the WinUI publish profiles; a bare `[Aa]rtifacts/`
  that hides a hand-written folder; `*.png`, which would hide the MSIX visual assets).
- Unless `on-existing` is `keep`/`update`/`replace` (apply it, still printing the warning and diff), ask via
  `AskUserQuestion`, with the consequence in each option:
  - **Update (Recommended)** — "Merge: add the template lines this file is missing; keep every hand-added line.
    Your customizations survive."
  - **Keep as is** — "Leave the file untouched. Nothing you customized changes, but the missing lines in the diff
    (e.g. `*.pfx`, `TestResults/`, `AppPackages/`) stay unignored — the report lists them."
  - **Replace** — "Overwrite with this skill's template only. Matches the house pattern exactly, but every
    hand-added line is lost (recoverable only from git history)."
  If the user wants only some lines, let them say so in free text and revise the draft.

## Step 6 — Write and report

Write the composed content to `.gitignore`. Then summarize:

- Whether this run created from scratch or updated an existing file, the action taken — **created / updated /
  replaced / kept as is** — and whether it came from the user or `on-existing`; when kept as is, the template lines it
  still lacks.
- Which Step 3 sections were included vs. skipped and why (e.g. "no MSIX section — no `packaging/AppxManifest.xml`,
  `Package.appxmanifest` or `.wapproj`").
- The result of Step 4's `git check-ignore` safety check (every listed source file still tracked).
- Any already-tracked file under a newly ignored path (Step 1), with the `git rm -r --cached <path>` it needs.

**Warn explicitly**:

- Review the generated/updated `.gitignore` before committing — detection is heuristic.
- Writing `.gitignore` alone never untracks a file that's already committed (e.g. a `bin/` folder or a `.pfx`): run
  `git rm -r --cached <path>` for each, and rotate any certificate that was ever committed.
- Never commit code-signing certificates (`*.pfx`) or their passwords; CI decodes them from secrets (or uses Azure
  Artifact Signing, which needs no certificate file at all).

## Verification notes

Verified on 2026-10-16 in `$TMPDIR/iru-verify/windows/iru-setup-windows-gitignore/` (outside this repository): wrote
the composed `.gitignore` (base template + MSIX + DocFX sections) into a `git init`-ed directory seeded with one file
per pattern and per source file the scaffold commits, then ran `git check-ignore -v` on each path — see the table
below. Git's own matching was exercised; no Windows tooling was needed for this skill.

| Path | Expected | Result |
|---|---|---|
| `src/App/bin/Release/net10.0-windows/App.dll`, `src/App.Core/obj/project.assets.json`, `tests/App.Core.Tests/Bin/x` | ignored | ignored — `[Bb]in/` / `[Oo]bj/` |
| `artifacts/bin/x`, `publish/win-x64/App.exe` | ignored | ignored |
| `.vs/App/v18/.suo`, `App.csproj.user`, `.idea/x`, `.vscode/x.json` | ignored | ignored |
| `.vscode/settings.json` | **tracked** | **not ignored** (negation) |
| `tests/App.Core.Tests/TestResults/guid/coverage.cobertura.xml`, `x.trx`, `coverage.opencover.xml`, `dotnet-code-quality.sarif` | ignored | ignored |
| `signing.pfx`, `cert.cer`, `cert.p12` | ignored | ignored |
| `AppPackages/App_1.0.0.0_x64.msix`, `App.msix`, `App.msixbundle`, `App.appinstaller`, `packaging/layout/App.exe` | ignored | ignored |
| `build/docfx/index.html`, `docfx/api/App.Core.MainViewModel.yml`, `docfx/api/toc.yml`, `docfx/api/.manifest` | ignored | ignored |
| `docfx/index.md`, `docfx/toc.yml` | **tracked** | **not ignored** |
| `docs/build/site/index.html`, `.integration-test.lock/owner`, `Thumbs.db`, `.DS_Store` | ignored | ignored |
| `.ui-screenshots/screen/MainWindow/default--rtl--dark.png` | ignored | ignored (re-verified 2026-10-16 for the UI contract) |
| `.ui-quality.yml` | **tracked** | **not ignored** |
| `Directory.Build.props`, `Directory.Packages.props`, `global.json`, `App.slnx`, `docfx.json`, `tests/coverlet.runsettings` | **tracked** | **not ignored** |
| `src/App/Properties/PublishProfiles/win-x64.pubxml`, `src/App/Package.appxmanifest`, `src/App/Assets/Square44x44Logo.png` | **tracked** | **not ignored** |
| `packaging/AppxManifest.xml`, `packaging/Assets/StoreLogo.png`, `packaging/build-msix.ps1`, `keys/App.snk` | **tracked** | **not ignored** |
