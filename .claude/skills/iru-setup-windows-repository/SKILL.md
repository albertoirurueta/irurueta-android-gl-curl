---
name: iru-setup-windows-repository
description: "/iru-setup-windows-repository [args] bootstraps a Windows desktop app repository on .NET 10 end to end, WPF, WinUI 3 or Windows Forms (MVVM or MVC): collects ui, architecture, identity and the shared pipeline parameters once (with Actions-minutes, Windows-multiplier, Sonar and Pages cost notes), then runs iru-setup-windows-app, iru-setup-antora, iru-setup-windows-gitignore, iru-setup-windows-github-workflows (skipped when ci: no), iru-setup-changelog and iru-setup-readme via iru-isolated-skill-executor. Announces from-scratch vs update; key: value args pre-resolve answers. Use to bootstrap or catch up a Windows app repository in one pass."
model: sonnet
---

# Setup Windows Repository

Bootstrap a brand-new (or catch up an existing) Windows desktop application repository in one pass by orchestrating
six skills: `iru-setup-windows-app` (the .NET 10 solution — a UI-free `src/<AppName>.Core` project holding models,
services and view models or controllers, the WPF/WinUI 3/WinForms app project, MSTest tests with coverlet, optional
FlaUI UI tests, StyleCop + .NET analyzers, DocFX, optional MSIX packaging), `iru-setup-antora` (Antora documentation
site), `iru-setup-windows-gitignore` (root `.gitignore`), `iru-setup-windows-github-workflows` (CI/CD + security
workflows), `iru-setup-changelog` (root `CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does
not duplicate any of their logic — it collects the shared parameters once and passes each skill only the `args` keys
that skill's own Step 0 actually accepts, so the user isn't asked the same question twice and no sub-skill sees a key
it doesn't recognize.

Each of Steps 4–9 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt, e.g.
`Skill({skill: "iru-setup-windows-app", args: "..."})`) rather than calling `Skill(...)` directly — every sub-skill
re-derives what it needs from the files the previous steps wrote, so only a short completion summary flows back,
keeping the scaffold's long template listings out of this orchestrator's context. Every sub-agent is launched with
`run_in_background: false` — each step depends on the previous one's files being on disk.

Scope: this catalog's Windows setup scaffolds **desktop apps** built with C# on .NET. A plain .NET class library or
ASP.NET Core service is out of scope here (no `iru-setup-dotnet-*` scaffold exists yet) — say so if asked. .NET MAUI,
Avalonia, Uno Platform, React Native for Windows and Electron are not scaffolded either; mention them as alternatives
only if the user asks.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

Invoked stand-alone (`/iru-setup-windows-repository`) or by the catalog's `iru-setup-repository` front door (project
type `windows-app`), so parse `args` first, as `key: value` lines, one per line, e.g.:

```
ui: wpf
architecture: mvvm
branching: gitflow
mode: new
open-source: yes
visibility: public
ci: yes
ci-tests: full
pages: yes
distribution: internal
signing: artifact-signing
winget: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_bookshelf
sonar-host-url: https://sonarcloud.io
integration-branch: develop
stable-branch: main
```

Recognized keys:

- **Shape and mode**: `ui` (`wpf`/`winui`/`winforms`), `architecture` (`mvvm`/`mvc`), `branching`
  (`gitflow`/`main-only`), `mode` (`new`/`existing`).
- **Shared pipeline vocabulary**: `open-source` (`yes`/`no`), `visibility` (`public`/`private`/`internal`), `ci`
  (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `on-existing` (`ask`/`keep`/`update`/`replace` —
  never asked; forwarded to the component sub-skills only when it arrived here via `args`), `distribution`
  (`none`/`internal`/`store`), `sonar` (`cloud`/`self-hosted`/`none`) plus, only when set, `sonar-organization`/
  `sonar-project-key`/`sonar-host-url`, `integration-branch`, `stable-branch`, `accessibility`
  (`enforce`/`report`/`off`/`none` — the catalog's UI-contract key: the automated accessibility level the UI tests apply,
  written to `.ui-quality.yml`; every Windows app has a UI, so it is always forwarded to `iru-setup-windows-app`).
- **Windows release keys**: `signing` (`artifact-signing`/`certificate`/`none`), `winget` (`yes`/`no`).
- **Identity fields** named in Step 2: `app-name`, `display-name`, `root-namespace`, `publisher`,
  `publisher-display-name`, `package-name`, `version`, `license`, `developer-name`, `developer-email`,
  `organization-url`.
- **Scaffold toggles**, passed through untouched if supplied, otherwise left to `iru-setup-windows-app`'s defaults:
  `msix` (`yes`/`no`), `ui-tests` (`flaui`/`none`), `rids` (default `win-x64;win-arm64`).
- `publish` is accepted and ignored (an app is not published to NuGet) — note it in the report if supplied.

Every key found here is resolved — skip the matching question in Steps 1–3. If `args` is absent or doesn't look like
this format, treat everything as unset and ask normally.

## Step 1 — Resolve the UI stack, architecture, branching model and mode

Survey the repository root first — `*.sln`/`*.slnx`, every `*.csproj` (look for `<UseWPF>`, `<UseWinUI>`,
`<UseWindowsForms>`, `<OutputType>WinExe`, a `net*-windows*` target framework), `Directory.Build.props`,
`src/*.Core/ViewModels/`, `src/*.Core/Controllers/`, `Package.appxmanifest`, `packaging/AppxManifest.xml` — since
their presence decides `ui`, `architecture` and `mode` without a question in the common cases.

Unless `ui` was supplied via `args`, resolve it:

- An app project with `<UseWinUI>true` → `winui`; `<UseWPF>true` → `wpf`; `<UseWindowsForms>true` → `winforms` (a
  project with both WPF and WinForms flags → ask which one is the primary UI). Confirm the detected value with the
  user rather than assuming it silently.
- Nothing on disk yet → `AskUserQuestion` with three options:
  - **WPF (Recommended)** (`wpf`) — "Mature, fully supported XAML stack on .NET 10 with the Fluent theme; MVVM with
    the CommunityToolkit.Mvvm; builds on Linux/macOS CI for compile checks; ships as a zip or MSIX."
  - **WinUI 3** (`winui`) — "Microsoft's recommended UI for new native Windows apps (Windows App SDK 2.x, Fluent,
    `x:Bind`, Mica); MVVM; builds only on Windows; ships as an MSIX."
  - **Windows Forms** (`winforms`) — "Designer-driven forms; fastest to build simple tools; MVC or MVVM; builds on
    Linux/macOS CI for compile checks."

Unless `architecture` was supplied via `args`, resolve it:

- `src/<AppName>.Core/ViewModels/` exists → `mvvm`; `src/<AppName>.Core/Controllers/` → `mvc`. Confirm.
- `ui: wpf` or `ui: winui` → `mvvm`, without asking. Tell the user why MVC isn't offered: XAML apps are built around
  data binding and commands, which is MVVM; Microsoft's WPF/WinUI guidance and the MVVM Toolkit assume it. If the
  user insists on MVC, suggest `winforms`.
- `ui: winforms` → `AskUserQuestion`:
  - **MVC (Recommended)** (`mvc`) — "The form implements a small `IMainView` interface from the Core project; a
    `MainController` in Core handles its events, updates the model and pushes results back. Fits the designer's
    event-driven forms; controllers are unit-tested against a fake view."
  - **MVVM** (`mvvm`) — "MVVM Toolkit view models in Core, bound with `BindingSource` and .NET 8+ `Button.Command`
    binding. Same view models a later WPF/WinUI port could reuse."

Unless `branching` was supplied via `args`, resolve it with `AskUserQuestion` (two options):

- **gitflow** (recommended, this catalog's house pattern) — an integration branch (`develop`, carrying
  `<Version>x.y.z-dev</Version>` in `Directory.Build.props`) plus a stable branch (`main`, releases only);
  `iru-setup-windows-github-workflows` generates `build.yml`/`release.yml`/`sync.yml`, and `sync.yml` opens the
  post-release version-bump PR back into the integration branch.
- **main-only** — a single stable branch; no `sync.yml`. Step 3's integration-branch question is skipped.

If `mode` wasn't supplied, check whether a `*.sln`/`*.slnx` (or any other artifact in the inventory below) exists: if
one does, default to `existing` and confirm with `AskUserQuestion` (`existing` pre-selected; `new` as the other
option, noting that either way every sub-skill still asks Update / Keep as is / Replace for each file it owns that
already exists); if nothing exists, default to `new` without asking.

### Setup mode

Right after resolving `mode`, print a short **Setup mode** statement:

- `mode: new` → "Setting up this repository **from scratch** — no prior setup found."
- `mode: existing` → "**Updating an existing repository** — prior setup found:" followed by an inventory table of
  every component this run owns, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence checked |
  |---|---|
  | Solution and build props | `*.slnx`/`*.sln`, `global.json`, `Directory.Build.props` (`<Version>`), `Directory.Packages.props` |
  | Core project | `src/<AppName>.Core/*.csproj` + `Models/`, `Services/`, `ViewModels/` or `Controllers/` |
  | App project | `src/<AppName>/*.csproj` (`UseWPF`/`UseWinUI`/`UseWindowsForms`), `App.xaml`/`Program.cs`, `app.manifest` |
  | Tests | `tests/<AppName>.Core.Tests/`, `tests/<AppName>.UITests/`, `tests/coverlet.runsettings` |
  | UI quality config | root `.ui-quality.yml` (accessibility level, screenshot directions/themes) |
  | Packaging | `src/<AppName>/Package.appxmanifest` (WinUI) or `packaging/AppxManifest.xml` + `packaging/build-msix.ps1` |
  | Quality config | `.editorconfig`, `stylecop.json` |
  | API docs | `docfx.json` |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` |
  | `.gitignore` | root `.gitignore` |
  | Workflows | each of `.github/workflows/build.yml`/`release.yml`/`sync.yml`/`security.yml`, `.github/scripts/sync_versions.py` |
  | Dependabot | `.github/dependabot.yml` |
  | `CHANGELOG.md` | root `CHANGELOG.md` |
  | `README.md` | root `README.md` |
  | `LICENSE` | root `LICENSE*` (reported only — no sub-skill here writes it) |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it."
  Those questions are asked by the component sub-skills (Steps 4–9) — never by this orchestrator too.

## Step 2 — Collect the app's identity

**`mode: existing` with a solution already on disk**: skip every identity question — `iru-setup-windows-app` reads
its defaults from the existing project files and manifests when the user chooses Update or Replace there, and
`iru-setup-readme` derives identity from the files too. Resolve `license` only if `args` supplied it, and record in
the final report that identity was taken from the existing files.

Otherwise, for any field not resolved from `args`, ask directly (plain conversation — free text), using **exactly**
the key names `iru-setup-windows-app` reads:

- **app-name** — a PascalCase C# identifier (e.g. `Bookshelf`): the solution, project, assembly, executable and root
  namespace name.
- **display-name** — the window title and MSIX `DisplayName` (e.g. `Bookshelf Desktop`); offer `app-name` as the
  default.
- **publisher** — the MSIX publisher distinguished name, `CN=<organization>` (it must match the signing certificate's
  subject exactly, or the package won't install — say so); only when `msix` will be produced (WinUI, or `distribution`
  ≠ `none`).
- **publisher-display-name** — shown in Settings → Apps; offer the organization name.
- **package-name** — the MSIX identity name; offer `<owner>.<app-name>` from `git remote get-url origin`.
- **developer name**, **developer email**, **organizationUrl** — recorded by the scaffold's report and seen by
  `iru-setup-readme`/`iru-check-license`.
- Leave `root-namespace`/`version` unset unless `args` supplied them — `iru-setup-windows-app` defaults them to
  `app-name` and, from the `branching` it receives, `1.0.0-dev` (gitflow) / `1.0.0` (main-only).

Then resolve the **license** with `AskUserQuestion`, unless `args` supplied one: MIT License (recommended for an app)
/ Apache License 2.0 / No license (proprietary) / Other (ask name + URL afterward).

## Step 3 — Collect the pipeline parameters

Ask directly, presenting each default so a blank reply accepts it (skip any resolved from `args`, skip the
integration branch when `branching: main-only`, and skip every workflow-only row once `ci: no` is resolved — if `ci`
is still unresolved, ask the open-source/visibility/`ci` questions below first):

| Parameter | `args` key | Default | Consumed by |
|---|---|---|---|
| Integration branch | `integration-branch` | `develop` | `iru-setup-windows-github-workflows` (gitflow only) |
| Stable branch | `stable-branch` | `main` | `iru-setup-windows-github-workflows` |

Then resolve, with `AskUserQuestion`, in this order (skip any already resolved). Quote the cost facts "per GitHub's
current pricing" (verified October 2026 against docs.github.com) and tell the user to double-check their plan:

- **open-source** — Yes / No. Drives the recommended defaults below.
- **visibility** — if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
  (lowercase it) and tell the user the detected value; otherwise ask **Public** / **Private** (Public recommended when
  open source). Open source + private/internal is contradictory — warn and ask which is right. `internal` behaves like
  `private` below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** (standard
  GitHub-hosted runners are free for public repositories). Private/internal: offer **Yes** first but state that private
  repositories consume the account's included minutes — 2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on
  Enterprise Cloud — then are billed per minute (Linux 2-core $0.006, **Windows $0.010 — roughly 1.7×**), and that
  this pipeline keeps everything it can (Core tests, coverage, Sonar for WPF/WinForms, docs) on Linux and uses
  Windows only for the app build, UI tests and packaging. **No** = "no workflows — build, test and release locally
  (re-run `iru-setup-windows-github-workflows` later)". `ci: no` ⇒ Step 7 is skipped entirely and `ci-tests`/`pages`
  aren't asked.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?"
  `cloud`/`self-hosted`/`none`. Recommend **`cloud`** only when open source **and** public; otherwise state that
  SonarCloud is free only for public/open-source projects (a private project needs a paid plan) and recommend
  **`none`**, offering **self-hosted** (Community Build is free, but you run the server). With `ci: no`, say nothing
  would send reports (only a manual `dotnet sonarscanner` run with a token) and recommend `none`. For
  `cloud`/`self-hosted` collect `sonar-organization`/`sonar-project-key`/`sonar-host-url` (SonarCloud: suggest
  `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io` from `git remote get-url origin`).
- **ci-tests** (only with `ci: yes`) — "Which tests should CI run?"
  - **Unit tests only** (`unit`, recommended for private/internal) — "The Core tests and coverage on Linux, plus a
    Windows build of the app; the FlaUI desktop UI tests stay local."
  - **Unit + UI tests** (`full`, recommended for public) — "Also the FlaUI UI tests on a Windows runner (it has an
    interactive desktop session) — complete verification, several more Windows minutes per run." Only offered when
    the scaffold has UI tests (`ui-tests` ≠ `none`).
- **pages** (only with `ci: yes`) — public: **yes** without asking (say so in the report). Private/internal: ask —
  **No (Recommended)** ("CI still builds the Antora + DocFX docs and uploads them as a workflow artifact; nothing is
  published") vs **Yes** ("requires GitHub Pro, Team, or Enterprise Cloud; the site is PUBLIC unless you're on
  Enterprise Cloud with private Pages access control").
- **distribution** — `none`/`internal`/`store`. Default **`internal`** when open source (a GitHub Release is the
  public download); otherwise ask with **`internal`** recommended:
  - `none` — "CI only; build and install locally."
  - `internal` — "Each release publishes self-contained zips per architecture and, when signed, an MSIX plus an
    `.appinstaller` file (auto-update from the latest GitHub Release) to GitHub Releases."
  - `store` — "Submit the MSIX to the Microsoft Store through Partner Center (free developer account; the Store signs
    and updates the app). Needs a Partner Center app reservation and an Entra ID app registration."
- **signing** (only with `distribution` ≠ `none`) — `AskUserQuestion`:
  - **Azure Artifact Signing (Recommended)** (`artifact-signing`) — "Microsoft's managed signing service (formerly
    Trusted Signing); no certificate file to store; builds SmartScreen reputation on the publisher identity. Needs an
    Azure subscription and identity validation."
  - **Certificate (.pfx)** (`certificate`) — "An OV/EV code-signing certificate stored as a base64 secret and used with
    SignTool. EV certificates no longer bypass SmartScreen."
  - **None** (`none`) — "Unsigned zips only — no MSIX (an unsigned MSIX can't be installed), and Windows SmartScreen
    warns on first run." With `distribution: store`, `none` is fine: the Store signs the MSIX itself — say so. Never
    offer `none` for `ui: winui` with `distribution: internal`: a WinUI app ships only as an MSIX, so an unsigned
    release would have nothing installable (`iru-setup-windows-github-workflows` rejects that combination).
- **winget** (only with `distribution: internal`, `signing` ≠ `none`, and a public repository) — "Also open a
  pull request to `microsoft/winget-pkgs` on every release with `wingetcreate`?" Yes / **No (Recommended until the
  first manual submission is merged)** — explain that the very first WinGet submission is manual and that the job
  needs a `WINGET_TOKEN` (a classic PAT with `public_repo`).

- **accessibility** — unless `args` supplied it (or an existing `.ui-quality.yml` already sets it — confirm that value
  instead): "Support accessibility and assistive technologies?" — **Enforce (Recommended)** ("Axe.Windows violations fail
  the UI tests") / **Report only** ("listed in the UI-test report, never failing") / **Off** ("not run; the coding
  defaults still apply"). **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value. It applies whether or not CI runs the UI tests (`ci-tests`), since they also run locally.

These values pass through unchanged to Step 4 (`iru-setup-windows-app`) and Step 7
(`iru-setup-windows-github-workflows`), so neither re-asks them.

## Step 4 — Run `iru-setup-windows-app`

Always invoke it, in either `mode`. When scaffold files already exist it announces that it is updating and asks, per
file group, Update / Keep as is / Replace / Stop itself (unless `on-existing` decides). Format Steps 1–3's answers as
`key: value` lines using exactly the keys it accepts:

```
Agent({
  description: "Run iru-setup-windows-app",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-windows-app\", args: \"ui: <ui>\\narchitecture: <architecture>\\n
    app-name: <app-name>\\ndisplay-name: <display-name>\\nroot-namespace: <..., only if supplied>\\n
    publisher: <..., if resolved>\\npublisher-display-name: <..., if resolved>\\npackage-name: <..., if resolved>\\n
    branching: <branching>\\nversion: <..., only if supplied>\\nrids: <..., only if supplied>\\nmsix: <..., only if supplied>\\n
    ui-tests: <..., only if supplied>\\naccessibility: <accessibility>\\nlicense: <license display name, or 'none'>\\n
    developer-name: <developer-name>\\ndeveloper-email: <developer-email>\\norganization-url: <organization-url>\\n
    open-source: <open-source>\\nvisibility: <visibility>\\nci: <ci>\\nsonar: <sonar>\\n
    sonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\n
    distribution: <distribution>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}). Report back:
    whether it scaffolded from scratch or updated an existing solution, the action taken per file group (created /
    updated / replaced / kept as is, or that the user chose Stop), which versions came from a live lookup versus the
    recorded fallback, whether MSIX packaging and UI tests were written, the action on .ui-quality.yml and the
    accessibility level it holds, and the pass/fail/unverified result of each
    verification command it ran (restore, build, Core tests + coverage, analyzers, DocFX; Windows-only stages).",
  run_in_background: false
})
```

Send `msix: no` (unless `args` supplied `msix`) when `ui` is `wpf`/`winforms`, `distribution: internal` and
`signing: none` — an unsigned MSIX can't be installed, so the scaffold must not write `packaging/` and the release
workflow must not spend Windows minutes building one. Omit every `only if supplied`/`if resolved` line whose value
wasn't resolved (never send an empty key). `branching`
picks the scaffold's default `version` (`1.0.0-dev` for gitflow, `1.0.0` for main-only). It ignores
`integration-branch`, `stable-branch`, `ci-tests`, `pages`, `signing` and `winget` — those are Step 7's alone. If it reports **Stop**, stop here too — there's no coherent solution to build docs or CI around yet.
Warn the user before launching: the first restore downloads every NuGet package (and the Windows App SDK for WinUI).

## Step 5 — Run `iru-setup-antora`

`Agent({description: "Run iru-setup-antora", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-antora\"}) with no args (or, only if `on-existing` arrived via this orchestrator's own args,
args: \"on-existing: <on-existing>\" — the only key it accepts) — it derives the component name, title, and version
itself (the solution name; <Version> in Directory.Build.props with the -dev suffix stripped), and asks Update / Keep /
Replace itself if docs exist. Report back: created vs updated, the action taken, and whether the site build
succeeded.", run_in_background: false})`.

Run it before Step 7 so the workflows skill's survey finds `docs/antora.yml`/`docs/antora-playbook.yml` in place for
its Antora + DocFX docs job. If it asks for a starting version, answer with `<Version>` minus `-dev` (`1.0.0` for the
defaults).

## Step 6 — Run `iru-setup-windows-gitignore`

`Agent({description: "Run iru-setup-windows-gitignore", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-windows-gitignore\"}) with no args (or, only if `on-existing` arrived via this
orchestrator's own args, args: \"on-existing: <on-existing>\") — it detects MSIX packaging and docfx.json itself.
Report back only the action taken on the root .gitignore (created / updated / replaced / kept as is) and the result
of its git check-ignore safety check.", run_in_background: false})`.

Run it after Steps 4–5 and before Step 7, so `bin/`, `obj/`, `TestResults/`, signing certificates, MSIX outputs and
the Antora/DocFX output are ignored before any CI report appears locally.

## Step 7 — Run `iru-setup-windows-github-workflows`

**Skip entirely when `ci: no`** — no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no
`sync.yml`/`sync_versions.py`. Record it in Step 10 as "skipped (ci: no)", noting that the shared security block
(Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with it and that
`/iru-setup-windows-github-workflows` adds it later.

Otherwise:

```
Agent({
  description: "Run iru-setup-windows-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-windows-github-workflows\", args: \"branching: <branching>\\n
    integration-branch: <integration-branch, gitflow only>\\nstable-branch: <stable-branch>\\n
    ci-tests: <ci-tests>\\nopen-source: <open-source>\\nvisibility: <visibility>\\npages: <pages>\\n
    distribution: <distribution>\\nsigning: <signing, if resolved>\\nwinget: <winget, if resolved>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}). Report back:
    whether it created the pipeline from scratch or updated an existing one; per file (build.yml, release.yml,
    sync.yml + sync_versions.py, security.yml, .github/dependabot.yml) the action taken (created / updated /
    replaced / kept as is, with any mismatch listed for a kept file, or Stop); which jobs run on Linux vs Windows;
    whether docs go to GitHub Pages or a workflow artifact; any open gap it flagged (missing docs/antora.yml, missing
    publisher identity); and the full required-secrets/settings table it produced.",
  run_in_background: false
})
```

Omit `integration-branch` for `main-only`, and `signing`/`winget` when they weren't asked. The workflows skill
detects `ui`, the solution layout, MSIX packaging and UI tests from disk itself. The shared `license`/`developer-*`/
`organization-url` keys are never passed. A **Stop** there is reported, not treated as a failure — Steps 4–6's output
stands on its own.

## Step 8 — Run `iru-setup-changelog`

`Agent({description: "Run iru-setup-changelog", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-changelog\"}) with no args (or args: \"on-existing: <on-existing>\" only if it arrived via
this orchestrator's own args), working from the repository's own tag/release history. Report back only the action
taken on CHANGELOG.md (created — with the version range reconstructed — / updated / replaced / kept as is).",
run_in_background: false})`. Independent of Steps 4–7; run it before Step 9 so the README's Documentation section
sees it.

## Step 9 — Run `iru-setup-readme`

`Agent({description: "Run iru-setup-readme", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages; no when ci is no>\\n
on-existing: <..., only if supplied via args>\"}) — those are the only keys it accepts — deriving everything else
from the repository (the .slnx, Directory.Build.props, the app project's UseWPF/UseWinUI/UseWindowsForms flag, the
MSIX manifest, docfx.json, the Antora docs, the workflows, CHANGELOG.md). Report back only which sections were
included vs. omitted and why.", run_in_background: false})`. Run it last so badges/status table/documentation links
see the finished state. A desktop app has no dependency snippet — its Installation section is a "download from GitHub
Releases / install the MSIX / `winget install` / Microsoft Store" section plus "build and run locally" (`dotnet run
--project src/<AppName>` on Windows).

## Step 10 — Report

Summarize all six delegated skills together: repeat the **Setup mode**, the resolved `ui`, `architecture`,
`branching` and `mode`, the app identity and license, and the pipeline parameters actually applied. Then a
per-component table — solution/build props, Core project, app project, tests, `.ui-quality.yml`, packaging, quality config, DocFX, the
Antora site, `.gitignore`, each workflow file + `sync_versions.py`, `.github/dependabot.yml`, `CHANGELOG.md`,
`README.md` — with the action taken for each: **created / updated / replaced / kept as is / skipped (ci: no)** (or
not reached, after a Stop), and for anything kept as is, the mismatches its sub-skill listed.

State explicitly what was wired or deliberately omitted, and why:

- **`ui`** and **`architecture`** — and, for WPF/WinUI, that MVC wasn't offered and why.
- **`open-source`** / **`visibility`** (and whether visibility came from `args`, `gh repo view`, or a question).
- **`ci`**: with `no`, no workflows/`security.yml`/Dependabot were generated; `/iru-setup-windows-github-workflows`
  adds them later.
- **`ci-tests`**: `unit`/`full` — whether CI runs the FlaUI UI tests on a Windows runner (restating the ~1.7× Windows
  cost on private repositories).
- **`pages`**: `yes` (Antora + DocFX deployed to GitHub Pages) or `no` (uploaded as a workflow artifact).
- **`accessibility`**: the level written to `.ui-quality.yml` (Axe.Windows in the FlaUI UI tests), and that the UI
  tests capture `.ui-screenshots/` (gitignored; uploaded by the CI UI job as a 7-day `ui-screenshots` artifact when
  `ci-tests: full`).
- **`distribution`** / **`signing`** / **`winget`**: which release artifacts and signing secrets exist.
- **`sonar`**: whether the CI Sonar step was included; if `none`, whether that's the expected default or a user
  choice; if configured with `ci: no`, that no CI step sends reports.
- **Versions**: which came from a live lookup versus a recorded fallback (scaffold and workflows).

Unless Step 7 was skipped, reproduce the **required-secrets/settings table** `iru-setup-windows-github-workflows`
reported, verbatim (it's already scoped to these options), plus its repository-settings notes (Pages source = GitHub
Actions for `pages: yes`; "Workflow permissions" allowing Actions to create pull requests for gitflow's `sync.yml`).

List every decision made on the user's behalf: the WPF-first `ui` recommendation, `architecture` forced to `mvvm` for
WPF/WinUI, the MIT-first license recommendation, `display-name`/`package-name` defaults, `version` left to the
scaffold's default, `pages` defaulted to `yes` for a public repository, the Artifact Signing recommendation, and
`winget` defaulting to no until the first manual submission.

Finish with an explicit **Warn explicitly** block, repeating each sub-skill's closing warning:

- **Review every generated/updated file before building, committing, or relying on CI.** Confirm the license matches
  a root `LICENSE` (`iru-check-license` can generate one and backfill headers), and that `dotnet build` and
  `dotnet test` succeed locally — on Windows for anything beyond the Core project and the WPF/WinForms compile check.
- Running the app, the FlaUI UI tests, WinUI builds, MSIX packaging and signing are Windows-only — none of the six
  skills can exercise them on macOS/Linux; they are verified by the generated Windows CI job or on a Windows machine.
- The MSIX `Publisher` must match the signing certificate subject (or the Artifact Signing certificate profile's
  subject) exactly; a mismatch only fails at packaging/signing time.
- Every secret in the table, the SonarCloud project, the Azure Artifact Signing account/certificate profile, the
  Partner Center app reservation and the first WinGet submission must exist before the release jobs can pass —
  nothing here creates accounts or credentials.
- The Sonar scan, signing, Store submission, WinGet PR, CodeQL's analysis and every Windows runner job are never
  exercised locally by any of the six skills — only their YAML is validated.
