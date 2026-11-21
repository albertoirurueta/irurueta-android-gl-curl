---
name: iru-setup-dotnet-gitignore
description: "Invoked as /iru-setup-dotnet-gitignore, optionally with args mode: new|existing and on-existing: ask|keep|update|replace. Creates or updates the root .gitignore of a plain .NET library, service or CLI repository, never overwriting an existing one without a diff and approval. Windows desktop apps use iru-setup-windows-gitignore."
model: haiku
---

# Setup .NET Gitignore

Create or refresh the root `.gitignore` of a plain .NET repository — a class library, a service, or a command-line
tool (`iru-setup-dotnet-cli`). The template is a trimmed, house-style version of what such a solution actually
produces — not a copy of GitHub's 400-line `VisualStudio.gitignore`, which carries decades of irrelevant tooling. Never
overwrite an existing `.gitignore` without showing the user exactly what would change and getting explicit approval
first — it can carry hand-added, project-specific entries that must not be dropped silently.

**Windows desktop apps are out of scope**: if a `*.csproj` sets `<UseWPF>`, `<UseWinUI>` or `<UseWindowsForms>` to
`true`, or the repository has `packaging/AppxManifest.xml`/a `Package.appxmanifest`/a `*.wapproj`, stop and point
to `iru-setup-windows-gitignore` (it adds the MSIX/App Installer section and keeps WinUI publish profiles tracked).

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines, skipping any question already answered:

- `mode: new|existing` — if omitted, detect it: a root `.gitignore` already present means `existing`; its absence
  means `new`. `mode: existing` never means "update silently" — the existing file still goes through Step 5's Update /
  Keep as is / Replace question (unless `on-existing` decides it).
- `on-existing: ask|keep|update|replace` — how Step 5 treats an existing `.gitignore`. Default `ask`.
  `keep`/`update`/`replace` applies that choice without asking (the warning and diff are still printed). Only meant for
  scripted runs; an orchestrator passes it only if its own `args` received it.

Any other key (e.g. the full shared-args set `iru-setup-cli-repository` forwards: `language`, `bin-name`, `ui`, …) is
ignored and named in the report.

## Step 1 — Survey the repository

Detect the following before composing content — each gates a Step 3 conditional section:

- `docfx.json` at the root or under `docs/` → DocFX in use. Read its `metadata[].dest` (default `api`) and
  `build.output`/`build.dest` (default `_site`) so Step 3 ignores the folders it actually writes, relative to the
  `docfx.json`.
- `*.sln`/`*.slnx` and every `*.csproj`/`*.fsproj` — confirms this is a .NET repository; if none exists, warn that
  this skill targets .NET solutions and ask whether to continue. Note a CLI (`<OutputType>Exe</OutputType>` with a
  `System.CommandLine` reference or `PackAsTool`) for the report.
- The Windows-desktop signals above → stop (see the scope note).
- `docs/antora.yml` → Antora (its lines are unconditional anyway).
- A tracked file under a path the template would ignore (`git ls-files -- 'bin/*' 'obj/*' '*/bin/*' '*/obj/*'
  'artifacts/*' '*.user' '.vs/*' '*.pfx' '*.nupkg'`) — flag each one in the report: writing `.gitignore` never
  untracks anything.
- The root `.gitignore`, if present — read it.

Tell the user which of the two this run is doing:

- **Creating from scratch** — no root `.gitignore`: write Step 4's content without asking.
- **Updating existing** — a root `.gitignore` exists: warn up front that Step 5 will ask whether to Update, Keep as
  is, or Replace it (or apply `on-existing`) rather than overwrite anything silently.

## Step 2 — The root `.gitignore` base template

Unconditional — include it verbatim:

```gitignore
# .NET build output (per project) and the .NET 8+ artifacts output layout
# (artifacts/ also holds `dotnet publish -o artifacts/publish/<rid>`, `dotnet pack` packages,
# and the generated man page and completion scripts of a command-line tool)
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
*.key

# Antora documentation build output (see iru-setup-antora)
docs/build/
docs/node_modules/

# OS files
.DS_Store
Thumbs.db
ehthumbs.db
Desktop.ini

# Repository-wide lock serializing container-backed integration-test runs
.integration-test.lock/
```

Notes on specific lines:

- `[Bb]in/`/`[Oo]bj/` are **unanchored**, so they match `src/<ToolName>/bin/`, `tests/<ToolName>.Core.Tests/obj/` and
  every other project's output. There is deliberately no bare `build/` line: nothing in the scaffold writes a `build/`
  folder except the DocFX/Antora outputs handled explicitly, and an unanchored `build/` would hide a hand-written
  `build/` folder of MSBuild `.targets` some solutions keep.
- `artifacts/` is unanchored on purpose (the .NET 8+ `UseArtifactsOutput` layout and `iru-setup-dotnet-cli`'s
  `artifacts/publish`, `artifacts/nupkg`, `artifacts/man`, `artifacts/completions` are all generated). If a
  repository keeps hand-written files in a folder named `artifacts`, anchor it (`/artifacts/`) and say so.
- `*.snk` (strong-name key) is **not** ignored: a public strong-name key is routinely committed. A private signing
  certificate (`*.pfx`) never is — CI decodes it from a secret. `*.cer` is not ignored either (a public certificate is
  harmless and sometimes needed for verification); add it only if the repository wants it.
- `*.sarif` covers the per-project analyzer logs under `obj/` and the `dotnet-code-quality.sarif` log
  `iru-dotnet-code-quality` writes at the repository root.
- `.vscode/*` keeps the shared `settings.json`/`tasks.json`/`launch.json`/`extensions.json` tracked.
- `docs/modules/ROOT/pages/cli-reference.adoc` (written by a CLI's hidden `gen-docs` command) is generated **but
  committed** — the Antora site builds from it. Nothing here may match it.
- `.integration-test.lock/` is the atomic-`mkdir` lock directory (with its owner file) that container-backed test
  stages create — always transient, never committed.

## Step 3 — Detect project-specific additions

Only add a section when Step 1 found what it targets; every line flush-left (git does not trim leading whitespace — a
snippet copied with Markdown indentation silently matches nothing):

- **DocFX** (`docfx.json` found): ignore the two folders it generates — the `metadata[].dest` folder (API `.yml`
  files, its generated `toc.yml` and `.manifest`) and the `build.output`/`build.dest` site — as paths relative to the
  `docfx.json` directory. For `iru-setup-dotnet-cli`'s root `docfx.json` (`"dest": "docfx/api"`, `"output":
  "build/docfx"`):
  ```gitignore
  # DocFX output (docfx metadata / docfx build)
  /docfx/api/
  /build/docfx/
  ```
  With DocFX's defaults (no `dest`/`output` set) the folders are `api/` and `_site/` instead (`/api/`, `/_site/`).
  Prefix each with the `docfx.json`'s directory when it is not at the root. Before ignoring the metadata folder
  wholesale, check `git ls-files <dest>/` — if hand-written files live there (e.g. an `index.md` landing page in an
  older DocFX layout), ignore only `<dest>/*.yml` and `<dest>/.manifest` instead, and say so. The hand-written
  `docfx/index.md`, `docfx/toc.yml` and any `docfx/articles/` stay tracked.
- **Terminal UI captures** (a `*.csproj` references `Terminal.Gui`, or a root `.ui-quality.yml` exists):
  ```gitignore
  # UI-test captures (terminal UI text snapshots per state × direction), regenerated on every full UI run
  .ui-screenshots/
  ```
  The UI tests write `.ui-screenshots/<kind>/<Id>/<state>--<direction>--no-color.txt` (and `a11y-report.txt` under
  `accessibility: report`); the documentation skills copy what they use into Antora's `images/ui/`. Never ignore
  `.ui-quality.yml` — it is configuration.
- Don't invent entries beyond what was detected.

## Step 4 — Compose the proposed content

Step 2's base template plus the Step 3 sections that matched, each under its comment header. If a root `.gitignore`
exists, merge: keep every existing line not already covered (hand-added ignores survive) and don't repeat a line
already present verbatim.

Before proposing, check the template doesn't hide source the scaffold commits — in the working tree, run
`git check-ignore -v` on whichever of these exist and refuse to add a line that matches any of them:
`Directory.Build.props`, `Directory.Packages.props`, `global.json`, `*.slnx`, `*.sln`, `stylecop.json`,
`.editorconfig`, `tests/coverlet.runsettings`, `docfx.json`, `docfx/index.md`, `docfx/toc.yml`,
`docs/modules/ROOT/pages/cli-reference.adoc`, `src/*/*.csproj`.

## Step 5 — If a `.gitignore` already existed, warn and ask Update / Keep as is / Replace

Skip when Step 1 found no root `.gitignore` — write it straight away.

Otherwise, if the proposed content differs from disk:

- Show the proposed **Update** content as a diff (write the draft to a temp path and run
  `git diff --no-index <current> <draft>`). Say which hand-added lines a **Replace** would drop, and name any
  conflicting existing line (e.g. a bare `build/` that hides a hand-written MSBuild folder; `*.adoc` or `docs/`, which
  would hide the generated CLI reference page; `*.json`, which would hide `global.json`).
- Unless `on-existing` is `keep`/`update`/`replace` (apply it, still printing the warning and diff), ask via
  `AskUserQuestion`, with the consequence in each option:
  - **Update (Recommended)** — "Merge: add the template lines this file is missing; keep every hand-added line.
    Your customizations survive."
  - **Keep as is** — "Leave the file untouched. Nothing you customized changes, but the missing lines in the diff
    (e.g. `artifacts/`, `TestResults/`, `*.pfx`) stay unignored — the report lists them."
  - **Replace** — "Overwrite with this skill's template only. Matches the house pattern exactly, but every
    hand-added line is lost (recoverable only from git history)."
  If the user wants only some lines, let them say so in free text and revise the draft.

## Step 6 — Write and report

Write the composed content to `.gitignore`. Then summarize:

- Whether this run created from scratch or updated an existing file, the action taken — **created / updated /
  replaced / kept as is** — and whether it came from the user or `on-existing`; when kept as is, the template lines it
  still lacks.
- Which Step 3 sections were included vs. skipped and why (e.g. "no DocFX section — no `docfx.json`").
- The result of Step 4's `git check-ignore` safety check (every listed source file still tracked).
- Any already-tracked file under a newly ignored path (Step 1), with the `git rm -r --cached <path>` it needs.

**Warn explicitly**:

- Review the generated/updated `.gitignore` before committing — detection is heuristic.
- Writing `.gitignore` alone never untracks a file that's already committed (e.g. a `bin/` folder, a `.nupkg` or a
  `.pfx`): run `git rm -r --cached <path>` for each, and rotate any certificate or key that was ever committed.
- Never commit signing certificates (`*.pfx`), NuGet API keys or their passwords; CI reads them from secrets (or uses
  NuGet trusted publishing, which needs no long-lived key at all).

## Verification notes

Verified on 2026-10-16 in `$TMPDIR/iru-verify/cli/dotnet/gitignore/` (outside this repository): wrote the composed
`.gitignore` (base template + the DocFX section for `iru-setup-dotnet-cli`'s `docfx.json`) into a `git init`-ed copy
of a rendered `iru-setup-dotnet-cli` scaffold that had been built, tested with coverage, Native AOT-published, packed
and had `gen-docs` and DocFX run, plus one seeded file per remaining pattern, then ran `git check-ignore -v` on each
path and `git status --porcelain --ignored` on the whole tree — see the table below. Git's own matching was
exercised; no other tooling was needed for this skill.

| Path | Expected | Result |
|---|---|---|
| `src/Greetly/bin/Release/net10.0/greetly.dll`, `src/Greetly.Core/obj/project.assets.json`, `tests/Greetly.Cli.Tests/Bin/x` | ignored | ignored — `[Bb]in/` / `[Oo]bj/` |
| `artifacts/publish/osx-arm64/greetly`, `artifacts/nupkg/Greetly.Tool.0.1.0-dev.nupkg`, `artifacts/man/greetly.1`, `artifacts/completions/_greetly`, `publish/x` | ignored | ignored |
| `TestResults/<guid>/coverage.cobertura.xml`, `x.trx`, `coverage.opencover.xml`, `src/Greetly/obj/Greetly.sarif`, `dotnet-code-quality.sarif` | ignored | ignored |
| `.vs/x`, `Greetly.csproj.user`, `.idea/x`, `.vscode/x.json`, `.DS_Store`, `.integration-test.lock/owner` | ignored | ignored |
| `.vscode/settings.json` | **tracked** | **not ignored** (negation) |
| `signing.pfx`, `cert.p12`, `nuget.key`, `x.snupkg` | ignored | ignored |
| `build/docfx/index.html`, `docfx/api/Greetly.Core.ExitCodes.yml`, `docfx/api/toc.yml`, `docs/build/site/index.html` | ignored | ignored |
| `docfx/index.md`, `docfx/toc.yml`, `docs/modules/ROOT/pages/cli-reference.adoc` | **tracked** | **not ignored** |
| `Directory.Build.props`, `Directory.Packages.props`, `global.json`, `Greetly.slnx`, `stylecop.json`, `.editorconfig`, `docfx.json`, `tests/coverlet.runsettings`, `src/Greetly/Greetly.csproj`, `keys/Greetly.snk` | **tracked** | **not ignored** |
| `.ui-screenshots/screen/BrowseView/default--rtl--no-color.txt` (with the terminal-UI section, `ui: tui`) | ignored | ignored (2026-10-16) |
| `.ui-quality.yml` | **tracked** | **not ignored** |
