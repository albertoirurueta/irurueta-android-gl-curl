---
name: iru-setup-macos-repository
description: "Invoke as `/iru-setup-macos-repository`, optionally with `args` (key: value lines such as `ui`, `architecture`, `branching`, `mode`, `ci`, `macos-ci`, `distribution`, `sonar`, `on-existing`, identity fields) to skip matching questions. End-to-end bootstrap of a native macOS desktop app repository (Swift 6, XcodeGen): collects identity and pipeline choices once, then runs `iru-setup-macos-app`, `iru-setup-antora`, `iru-setup-swift-gitignore`, `iru-setup-macos-github-workflows`, `iru-setup-changelog` and `iru-setup-readme` in order. Use for macOS-only apps; for apps also targeting iOS/watchOS use `iru-setup-swift-repository` (`flavor: app`)."
model: sonnet
---

# Setup macOS Repository

Bootstrap a brand-new (or catch up an existing) native macOS desktop application repository in one pass by
orchestrating six skills: `iru-setup-macos-app` (the XcodeGen `project.yml` — one macOS app target with unit and UI
test targets, a UI-free local `Packages/Core` Swift package holding models, services and view models or controller
logic, entitlements with the App Sandbox and hardened runtime, export-options plists, a DMG script, SwiftLint/
swift-format configs), `iru-setup-antora` (Antora documentation site), `iru-setup-swift-gitignore` (root
`.gitignore`), `iru-setup-macos-github-workflows` (CI/CD + security workflows), `iru-setup-changelog` (root
`CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does not duplicate any of their logic — it
collects the shared parameters once and passes each skill only the `args` keys that skill's own Step 0 actually
accepts, so the user isn't asked the same question twice and no sub-skill sees a key it doesn't recognize.

Each of Steps 4–9 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt, e.g.
`Skill({skill: "iru-setup-macos-app", args: "..."})`) rather than calling `Skill(...)` directly — every sub-skill
re-derives what it needs from the files the previous steps wrote, so only a short completion summary flows back.
Every sub-agent is launched with `run_in_background: false` — each step depends on the previous one's files being on
disk.

Scope: a **macOS-only** desktop app. A multi-platform Apple app (iOS + macOS, …) is `iru-setup-swift-repository`'s
`flavor: app` (via `iru-setup-apple-app`); a Swift package is its `flavor: library`. Mac Catalyst, "Designed for iPad"
and cross-platform desktop stacks (Electron, Tauri, Compose Multiplatform Desktop) are not scaffolded — mention them
only if asked.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

Invoked stand-alone (`/iru-setup-macos-repository`) or by the catalog's `iru-setup-repository` front door (project
type `macos-app`), so parse `args` first, as `key: value` lines, one per line, e.g.:

```
ui: swiftui
architecture: mvvm
branching: gitflow
mode: new
open-source: yes
visibility: public
ci: yes
macos-ci: all
ci-tests: full
pages: yes
distribution: internal
sandbox: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_field-notes
sonar-host-url: https://sonarcloud.io
integration-branch: develop
stable-branch: main
runner: macos-26
```

Recognized keys:

- **Shape and mode**: `ui` (`swiftui`/`appkit`), `architecture` (`mvvm`/`mvc`), `branching` (`gitflow`/`main-only`),
  `mode` (`new`/`existing`).
- **Shared pipeline vocabulary**: `open-source` (`yes`/`no`), `visibility` (`public`/`private`/`internal`), `ci`
  (`yes`/`no`), `macos-ci` (`all`/`stable`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `accessibility`
  (`enforce`/`report`/`off`/`none` — the UI tests' automated accessibility level, written to `.ui-quality.yml`), `on-existing`
  (`ask`/`keep`/`update`/`replace` — never asked; forwarded to the component sub-skills only when it arrived here via
  `args`), `distribution` (`none`/`internal`/`store`), `sonar` (`cloud`/`self-hosted`/`none`) plus, only when set,
  `sonar-organization`/`sonar-project-key`/`sonar-host-url`, `integration-branch`, `stable-branch`, `runner`
  (macOS runner label), `xcode-version`.
- **Identity fields** named in Step 2: `app-name`, `bundle-id`, `team-id`, `version`, `build-number`, `license`,
  `developer-name`, `developer-email`, `organization-url`.
- **macOS toggles**, passed through untouched if supplied, otherwise left to `iru-setup-macos-app`'s defaults:
  `min-macos`, `sandbox` (`yes`/`no`), `category` (`LSApplicationCategoryType`).
- `publish` is accepted and ignored (an app is not published as a package) — note it in the report if supplied.

Every key found here is resolved — skip the matching question in Steps 1–3. If `args` is absent or doesn't look like
this format, treat everything as unset and ask normally.

## Step 1 — Resolve the UI framework, architecture, branching model and mode

Survey the repository root first — `project.yml`, `Project.swift`, `*.xcodeproj`, `Packages/Core/`,
`Packages/Core/Sources/Core/ViewModels/`, `Packages/Core/Sources/Core/Controllers/`, `App/Sources/`, any
`*.entitlements` — since their presence decides `ui`, `architecture` and `mode` without a question in the common
cases. If the manifest declares targets for more than one Apple platform (iOS + macOS, …), stop and point the user at
`iru-setup-swift-repository` — this skill only scaffolds macOS-only apps.

Unless `ui` was supplied via `args`, resolve it:

- App sources with `@main struct … : App` → `swiftui`; an `NSApplicationDelegate` with `NSWindowController`/
  `NSViewController` subclasses and no SwiftUI `App` → `appkit`. Confirm the detected value with the user rather than
  assuming it silently.
- Nothing on disk yet → `AskUserQuestion` with two options:
  - **SwiftUI (Recommended)** (`swiftui`) — "Apple's recommended way to build new Mac apps: `WindowGroup`, a
    `Settings` scene, menu commands and an optional menu bar extra, all in SwiftUI; drop to AppKit through
    representables only where needed."
  - **AppKit** (`appkit`) — "Programmatic AppKit (`NSApplicationDelegate`, `NSWindowController`, `NSViewController`,
    a main menu built in code, no storyboards): the classic Cocoa MVC stack, for apps that need AppKit's full control
    or that grow out of an existing AppKit codebase."

Unless `architecture` was supplied via `args`, resolve it:

- `Packages/Core/Sources/Core/ViewModels/` exists → `mvvm`; `…/Controllers/` → `mvc`. Confirm.
- `ui: swiftui` → `mvvm`, without asking. Tell the user why MVC isn't offered: SwiftUI has no controllers — views
  observe `@Observable` models and per-screen view models — so a "controller" layer would be an empty wrapper; if
  they want Cocoa MVC, choose `appkit`.
- `ui: appkit` → `AskUserQuestion`:
  - **MVC (Recommended)** (`mvc`) — "Cocoa's own pattern: `NSViewController`s are the controllers, the model and its
    services live in `Packages/Core` and are unit-tested there; views are plain AppKit views."
  - **MVVM** (`mvvm`) — "`@Observable` view models in `Packages/Core`; the `NSViewController` binds to them through
    AppKit's Observation tracking (macOS 26+). The same view models would serve a later SwiftUI port."

Unless `branching` was supplied via `args`, resolve it with `AskUserQuestion` (two options):

- **gitflow** (recommended, this catalog's house pattern) — an integration branch (`develop`) plus a stable branch
  (`main`, releases only); `iru-setup-macos-github-workflows` generates `build.yml`/`release.yml`/`sync.yml`, and
  `sync.yml` opens the post-release version-bump PR back into the integration branch.
- **main-only** — a single stable branch; no `sync.yml`. Step 3's integration-branch question is skipped.

If `mode` wasn't supplied, check whether `project.yml` (or any other artifact in the inventory below) exists: if it
does, default to `existing` and confirm with `AskUserQuestion` (`existing` pre-selected; `new` as the other option,
noting that either way every sub-skill still asks Update / Keep as is / Replace for each file it owns that already
exists); if nothing exists, default to `new` without asking.

### Setup mode

Right after resolving `mode`, print a short **Setup mode** statement:

- `mode: new` → "Setting up this repository **from scratch** — no prior setup found."
- `mode: existing` → "**Updating an existing repository** — prior setup found:" followed by an inventory table of
  every component this run owns, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence checked |
  |---|---|
  | Project manifest | `project.yml` (`MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`), any committed `*.xcodeproj` |
  | Core package | `Packages/Core/Package.swift` + `Sources/Core/{Models,Services,ViewModels or Controllers}` + `Tests/CoreTests` |
  | App target | `App/Sources/`, `App/Resources/` (entitlements, asset catalog, Info.plist), `App/Tests/`, `App/UITests/` |
  | Distribution files | `ExportOptions-*.plist`, `scripts/make-dmg.sh` |
  | Quality config | `.swiftlint.yml`, `.swift-format`, `.ui-quality.yml` |
  | Sonar config | `sonar-project.properties` |
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

**`mode: existing` with `project.yml` already on disk**: skip every identity question — `iru-setup-macos-app` reads
its defaults from the existing manifest when the user chooses Update or Replace there, and `iru-setup-readme` derives
identity from the files too. Resolve `license` only if `args` supplied it, and record in the final report that
identity was taken from the existing files.

Otherwise, for any field not resolved from `args`, ask directly (plain conversation — free text), using **exactly**
the key names `iru-setup-macos-app` reads:

- **app-name** — the display name shown in the menu bar and Finder (`CFBundleDisplayName`, e.g. `Field Notes`);
  `iru-setup-macos-app` derives the Xcode target, scheme, module and `.app` name from its PascalCase form
  (`FieldNotes`).
- **bundle-id** — reverse-DNS `CFBundleIdentifier` (e.g. `com.example.fieldnotes`).
- **team-id** — the Apple Developer Team ID; may be left empty (local builds and tests don't need it; Developer ID
  signing, notarization and App Store uploads do — CI supplies it then).
- **developer name**, **developer email**, **organizationUrl** — recorded by the scaffold's report and seen by
  `iru-setup-readme`/`iru-check-license`.
- Leave `version`/`build-number`/`min-macos`/`sandbox`/`category` unset unless `args` supplied them — the scaffold
  defaults them (`1.0.0`/`1`/macOS `26.0`/`yes`/productivity) and explains each — `min-macos` 26.0 keeps the build
  universal for Intel Macs on Tahoe and turns AppKit Observation tracking on by default; 15.0 and 27.0 are offered.

Then resolve the **license** with `AskUserQuestion`, unless `args` supplied one: MIT License (recommended for an app)
/ Apache License 2.0 / No license (proprietary) / Other (ask name + URL afterward).

## Step 3 — Collect the pipeline parameters

Ask directly, presenting each default so a blank reply accepts it (skip any resolved from `args`, skip the
integration branch when `branching: main-only`, and skip every workflow-only row once `ci: no` is resolved — if `ci`
is still unresolved, ask the open-source/visibility/`ci` questions below first):

| Parameter | `args` key | Default | Consumed by |
|---|---|---|---|
| Integration branch | `integration-branch` | `develop` | `iru-setup-macos-github-workflows` (gitflow only) |
| Stable branch | `stable-branch` | `main` | `iru-setup-macos-github-workflows` |
| macOS runner label | `runner` | `macos-26` (GA image) — but its newest Xcode is 26.6, and `iru-setup-macos-app`'s templates were verified on Xcode 27.1; offer `xcode-27` (the newer-Xcode preview label, with `xcode-version: 27.1`) and let the user pick, stating the trade-off (GA stability vs. the Xcode the scaffold was verified with) | `iru-setup-macos-github-workflows` |

Then resolve, with `AskUserQuestion`, in this order (skip any already resolved). Quote the cost facts "per GitHub's
current pricing" (verified October 2026 against docs.github.com) and tell the user to double-check their plan:

- **open-source** — Yes / No. Drives the recommended defaults below.
- **visibility** — if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
  (lowercase it) and tell the user the detected value; otherwise ask **Public** / **Private** (Public recommended when
  open source). Open source + private/internal is contradictory — warn and ask which is right. `internal` behaves like
  `private` below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** (standard
  GitHub-hosted runners, macOS included, are free for public repositories). Private/internal: offer **Yes** first but
  state that private repositories consume the account's included minutes — 2,000/month on GitHub Free, 3,000 on Pro
  and Team, 50,000 on Enterprise Cloud — then are billed per minute (Linux 2-core $0.006, **macOS $0.062 — roughly
  10×**), and that building, testing and notarizing a Mac app needs a macOS runner. **No** = "no workflows — build,
  test and release locally (re-run `iru-setup-macos-github-workflows` later)". `ci: no` ⇒ Step 7 is skipped entirely
  and `macos-ci`/`ci-tests`/`pages` aren't asked.
- **macos-ci** (only with `ci: yes`) — "When should the macOS build-and-test job run?"
  - **Every push and pull request** (`all`) — recommended for public: complete verification on every change, free on a
    public repository.
  - **Stable branch and manual runs only** (`stable`) — recommended for private/internal: SwiftLint and the Antora
    docs still run on Linux on every push/PR; the macOS job only runs on pushes to the stable branch and
    `workflow_dispatch` — a single macOS run costs as much as ~10 Linux runs.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?"
  `cloud`/`self-hosted`/`none`. Recommend **`cloud`** only when open source **and** public; otherwise state that
  SonarCloud is free only for public/open-source projects (a private project needs a paid plan) and recommend
  **`none`**, offering **self-hosted** (Community Build is free, but you run the server). With `ci: no`, say nothing
  would send reports and recommend `none`. For `cloud`/`self-hosted` collect `sonar-organization`/
  `sonar-project-key`/`sonar-host-url` (SonarCloud: suggest `<owner>-github` / `<owner>_<repo>` /
  `https://sonarcloud.io` from `git remote get-url origin`).
- **ci-tests** (only with `ci: yes`) — "Which tests should CI run?"
  - **Unit tests only** (`unit`, recommended for private/internal) — "`Packages/Core` tests with coverage plus the
    app target's unit tests; the XCUITest UI tests stay local."
  - **Unit + UI tests** (`full`, recommended for public) — "Also the XCUITest UI tests, which launch the app on the
    runner's desktop — complete verification, several more macOS minutes per run."
- **accessibility** — "Support accessibility and assistive technologies?" — **Enforce (Recommended)** (`enforce`: every
  `performAccessibilityAudit()` issue fails the UI tests) / **Report only** (`report`: issues listed, never failing) /
  **Off** (`off`: the audit isn't run; labels, contrast and keyboard access are still coded). **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value. The scaffold writes it to
  `.ui-quality.yml`; the UI tests capture the main window in left-to-right/right-to-left × light/dark into the
  gitignored `.ui-screenshots/`, uploaded by CI as a 7-day artifact.
- **pages** (only with `ci: yes`) — public: **yes** without asking (say so in the report). Private/internal: ask —
  **No (Recommended)** ("CI still builds the Antora + DocC docs and uploads them as a workflow artifact; nothing is
  published") vs **Yes** ("requires GitHub Pro, Team, or Enterprise Cloud; the site is PUBLIC unless you're on
  Enterprise Cloud with private Pages access control").
- **distribution** — `none`/`internal`/`store`. Default **`internal`** when open source (a notarized DMG on GitHub
  Releases is the public download); otherwise ask with **`internal`** recommended:
  - `none` — "CI only; build and run locally from Xcode."
  - `internal` — "Each release is signed with a Developer ID Application certificate, notarized with `notarytool`,
    stapled, packaged as a DMG (also signed, notarized and stapled) and attached to a GitHub Release. Needs a paid
    Apple Developer Program membership."
  - `store` — "Each release is archived and uploaded to App Store Connect for TestFlight/Mac App Store review (the App
    Sandbox is mandatory). Needs a paid membership and an App Store Connect API key."
  With `distribution: store`, force `sandbox: yes` and say why.

These values pass through unchanged to Step 4 (`iru-setup-macos-app`) and Step 7 (`iru-setup-macos-github-workflows`),
so neither re-asks them.

## Step 4 — Run `iru-setup-macos-app`

Always invoke it, in either `mode`. When scaffold files already exist it announces that it is updating and asks, per
file group, Update / Keep as is / Replace / Stop itself (unless `on-existing` decides). Format Steps 1–3's answers as
`key: value` lines using exactly the keys it accepts:

```
Agent({
  description: "Run iru-setup-macos-app",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-macos-app\", args: \"ui: <ui>\\narchitecture: <architecture>\\n
    app-name: <app-name>\\nbundle-id: <bundle-id>\\nteam-id: <..., only if supplied>\\n
    version: <..., only if supplied>\\nbuild-number: <..., only if supplied>\\nmin-macos: <..., only if supplied>\\n
    sandbox: <..., only if supplied or forced by store>\\ncategory: <..., only if supplied>\\n
    license: <license display name, or 'none'>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    visibility: <visibility>\\nci: <ci>\\nsonar: <sonar>\\nsonar-organization: <..., if set>\\n
    sonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\ndistribution: <distribution>\\n
    accessibility: <accessibility>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}). Report back: whether it scaffolded from scratch
    or updated an existing project, the action taken per file group (created / updated / replaced / kept as is, or
    that the user chose Stop), which versions came from a live lookup versus the recorded fallback, the min-macos it
    resolved, and the pass/fail/unverified result of each verification command it ran (xcodegen, build-for-testing,
    unit/UI tests, Core coverage, SwiftLint, DocC, archive/export, DMG).",
  run_in_background: false
})
```

Omit every `only if supplied` line whose value wasn't resolved (never send an empty key). It doesn't accept
`branching`, `integration-branch`, `stable-branch`, `macos-ci`, `ci-tests`, `pages`, `runner` or `xcode-version` —
those are Step 7's alone. If it reports **Stop**, stop here too.

## Step 5 — Run `iru-setup-antora`

`Agent({description: "Run iru-setup-antora", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-antora\"}) with no args (or, only if `on-existing` arrived via this orchestrator's own args,
args: \"on-existing: <on-existing>\" — the only key it accepts) — it derives the component name, title, and version
itself (project.yml's name and MARKETING_VERSION), and asks Update / Keep / Replace itself if docs exist. Report
back: created vs updated, the action taken, and whether the site build succeeded.", run_in_background: false})`.

Run it before Step 7 so the workflows skill's survey finds `docs/antora.yml`/`docs/antora-playbook.yml` in place for
its Antora + DocC docs job. If it asks for a starting version, answer with `MARKETING_VERSION` (`1.0.0` for the
defaults).

## Step 6 — Run `iru-setup-swift-gitignore`

`Agent({description: "Run iru-setup-swift-gitignore", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-swift-gitignore\", args: \"flavor: app\\non-existing: <..., only if supplied via args>\"})
— it detects project.yml (XcodeGen, so the .xcodeproj is ignored), Packages/Core and the DMG/export outputs itself;
when it asks whether to commit Package.resolved, an app commits it. Report back only the action taken on the root
.gitignore (created / updated / replaced / kept as is).", run_in_background: false})`.

Run it after Steps 4–5 and before Step 7, so the generated `.xcodeproj`, `DerivedData/`, `*.xcresult`, exported
archives/DMGs, signing material (`*.p12`, `*.p8`, `*.cer`) and the Antora output are ignored before any CI report
appears locally.

## Step 7 — Run `iru-setup-macos-github-workflows`

**Skip entirely when `ci: no`** — no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no
`sync.yml`/`sync_versions.py`. Record it in Step 10 as "skipped (ci: no)", noting that the shared security block
(Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with it and that
`/iru-setup-macos-github-workflows` adds it later.

Otherwise:

```
Agent({
  description: "Run iru-setup-macos-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-macos-github-workflows\", args: \"branching: <branching>\\n
    integration-branch: <integration-branch, gitflow only>\\nstable-branch: <stable-branch>\\n
    runner: <runner>\\nxcode-version: <..., only if supplied>\\nmacos-ci: <macos-ci>\\nci-tests: <ci-tests>\\n
    open-source: <open-source>\\nvisibility: <visibility>\\npages: <pages>\\ndistribution: <distribution>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}). Report back:
    whether it created the pipeline from scratch or updated an existing one; per file (build.yml, release.yml,
    sync.yml + sync_versions.py, security.yml, .github/dependabot.yml) the action taken (created / updated /
    replaced / kept as is, with any mismatch listed for a kept file, or Stop); when the macOS jobs run; whether docs go
    to GitHub Pages or a workflow artifact; any open gap it flagged (missing docs/antora.yml, empty team id); and the
    full required-secrets/settings table it produced.",
  run_in_background: false
})
```

Omit `integration-branch` for `main-only`. The workflows skill detects `ui`, the scheme names, the export plists and
the DMG script from disk itself. The shared `license`/`developer-*`/`organization-url` keys are never passed. A
**Stop** there is reported, not treated as a failure.

## Step 8 — Run `iru-setup-changelog`

`Agent({description: "Run iru-setup-changelog", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-changelog\"}) with no args (or args: \"on-existing: <on-existing>\" only if it arrived via
this orchestrator's own args), working from the repository's own tag/release history. Report back only the action
taken on CHANGELOG.md (created — with the version range reconstructed — / updated / replaced / kept as is).",
run_in_background: false})`. Independent of Steps 4–7; run it before Step 9.

## Step 9 — Run `iru-setup-readme`

`Agent({description: "Run iru-setup-readme", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages; no when ci is no>\\n
on-existing: <..., only if supplied via args>\"}) — those are the only keys it accepts — deriving everything else
from the repository (project.yml, Packages/Core/Package.swift, the entitlements, the Antora docs, the workflows,
sonar-project.properties, CHANGELOG.md). Report back only which sections were included vs. omitted and why.",
run_in_background: false})`. Run it last. A desktop app has no dependency snippet — its Installation section is a
"download the notarized DMG from GitHub Releases / Mac App Store" section plus "build and run locally" (`xcodegen
generate` + Xcode, or `xcodebuild -scheme <AppName>`).

## Step 10 — Report

Summarize all six delegated skills together: repeat the **Setup mode**, the resolved `ui`, `architecture`,
`branching` and `mode`, the app identity and license, and the pipeline parameters actually applied. Then a
per-component table — `project.yml`, `Packages/Core`, the app target, distribution files, quality config (incl.
`.ui-quality.yml`), Sonar
config, the Antora site, `.gitignore`, each workflow file + `sync_versions.py`, `.github/dependabot.yml`,
`CHANGELOG.md`, `README.md` — with the action taken for each: **created / updated / replaced / kept as is / skipped
(ci: no)** (or not reached, after a Stop), and for anything kept as is, the mismatches its sub-skill listed.

State explicitly what was wired or deliberately omitted, and why:

- **`ui`** and **`architecture`** — and, for SwiftUI, that MVC wasn't offered and why.
- **`sandbox`** — on or off, and that the Mac App Store requires it.
- **`open-source`** / **`visibility`** (and whether visibility came from `args`, `gh repo view`, or a question).
- **`ci`**: with `no`, no workflows/`security.yml`/Dependabot were generated.
- **`macos-ci`** / **`ci-tests`**: when the macOS job runs and whether it runs the XCUITest UI tests, restating the
  ~10× macOS cost on private repositories.
- **`accessibility`**: the level written to `.ui-quality.yml` (`enforce`/`report`/`off`/`none`) and whether it came from
  `args` or the user.
- **`pages`**: `yes` (Antora + DocC deployed to GitHub Pages) or `no` (uploaded as a workflow artifact).
- **`distribution`**: which release job exists (Developer ID + notarized DMG, or App Store Connect upload) and the
  signing secrets it needs.
- **`sonar`**: whether `sonar-project.properties` and the CI Sonar step were included.
- **Versions**: which came from a live lookup versus a recorded fallback (scaffold and workflows).

Unless Step 7 was skipped, reproduce the **required-secrets/settings table** `iru-setup-macos-github-workflows`
reported, verbatim, plus its repository-settings notes (Pages source = GitHub Actions for `pages: yes`; "Workflow
permissions" allowing Actions to create pull requests for gitflow's `sync.yml`).

List every decision made on the user's behalf: the SwiftUI-first `ui` recommendation, `architecture` forced to `mvvm`
for SwiftUI, the MIT-first license recommendation, `version`/`min-macos`/`sandbox`/`category` left to the scaffold's
defaults, `pages` defaulted to `yes` for a public repository, `macos-ci`'s visibility-based recommendation.

Finish with an explicit **Warn explicitly** block, repeating each sub-skill's closing warning:

- **Review every generated/updated file before building, committing, or relying on CI.** Confirm the license matches
  a root `LICENSE` (`iru-check-license` can generate one and backfill headers), and that `xcodegen generate` +
  `xcodebuild test -scheme <AppName> -destination 'platform=macOS'` succeed locally.
- The `.xcodeproj` is generated and gitignored — every clone (and CI) runs `xcodegen generate` first.
- Developer ID signing, notarization, stapling and App Store uploads need a paid Apple Developer Program membership,
  certificates and an App Store Connect API key — none of the six skills can exercise them without credentials; do a
  `workflow_dispatch` dry run before a real release.
- Every secret in the table and the SonarCloud project must exist before CI can pass — nothing here creates accounts
  or credentials.
