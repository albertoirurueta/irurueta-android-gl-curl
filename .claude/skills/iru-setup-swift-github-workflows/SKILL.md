---
name: iru-setup-swift-github-workflows
description: "/iru-setup-swift-github-workflows [args] creates or updates build.yml, release.yml, sync.yml, security.yml and dependabot.yml for a Swift repository from iru-setup-swift-library (flavor library) or iru-setup-apple-app (flavor app). Key args: flavor (required), platforms, runner, signing, ci-tests, pages, on-existing. Asks Update/Keep/Replace/Stop on existing files."
model: sonnet
---

# Setup Swift GitHub Workflows

Scaffold (or update) the GitHub Actions workflows plus a Dependabot config for a Swift repository produced by
`iru-setup-swift-library` (a bare SwiftPM package, `flavor: library`) or `iru-setup-apple-app` (an
XcodeGen/Tuist-generated native app, `flavor: app`):

- **`build.yml`** — runs on every push to the integration/stable branches and every pull request: lint
  (SwiftLint + `swift format lint --strict`), build, test with coverage, an optional SonarQube/SonarCloud scan,
  a DocC + Antora documentation build published to GitHub Pages (only when the push lands on the stable
  branch; with `pages: no` it is still built but uploaded as a workflow artifact instead). `ci-tests` decides
  whether the app flavor's XCUITest bundle runs in CI. The flavor changes what "build/test" means underneath (Step 4/5).
- **`release.yml`** — triggered when a GitHub Release is published. For a library this verifies the release is
  sound (semver tag, a manifest that still parses) rather than publishing anywhere — a SwiftPM package's actual
  release artifact is the git tag itself, nothing this workflow uploads (Step 6). For an app it archives, signs,
  exports, uploads to TestFlight, and (for macOS) notarizes (Step 7).
- **`sync.yml`** (`branching: gitflow` only) — once a release is published from the stable branch, opens a pull
  request merging it back into the integration branch and bumps the development version, reusing
  `iru-swift-bump-version`'s exact rewrite targets (`version.txt`/`CHANGELOG.md`/README for a library,
  `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` for an app) so a manual `/iru-swift-bump-version` run and this
  CI-driven script never drift apart (Step 8). The branch is `sync_<released version>` (release `1.0.1` →
  `sync_1.0.1`); if the merge conflicts, the PR opens from the release commit without a bump, so a patch sync
  never lowers the integration branch's development version.
- **`security.yml`** and **`.github/dependabot.yml`** — generated (or updated) regardless of `branching`: a
  `dependency-review` job, a CodeQL job (`swift`, which only runs on a macOS runner — Linux CodeQL has no Swift
  extractor), an OSV-Scanner job, a `gitleaks` job (each individually omittable via `args`), and grouped weekly
  Dependabot updates for the `swift` and `github-actions` ecosystems (Step 9).

This skill is designed for the project shapes `iru-setup-swift-library`/`iru-setup-apple-app` produce — a root
`Package.swift` with a `<Name>` library target (library flavor), or a `project.yml`/`Project.swift` generator
manifest plus a `Packages/Core` local SwiftPM package and per-platform `Targets/<Platform>/` directories (app
flavor). If neither shape is found at the repository root, warn the user this skill's templates assume one of
them and most of Step 1's survey won't resolve, then use `AskUserQuestion` to ask whether to stop here or
continue anyway (treating every derived placeholder in Step 4+ as an open gap to ask about directly, called out
again in the final report).

**No single real reference repository exists for this skill to genericize from**, unlike its Java/TypeScript/
Android siblings (Step 3 explains why and what these templates are actually derived from instead). **CocoaPods
is never generated** — every template here assumes SwiftPM (for dependencies and, for an app, the local
`Packages/Core` package) exclusively, matching `iru-setup-swift-library`'s and `iru-setup-apple-app`'s own
scaffolds. **macOS runner minutes cost roughly 10x Linux minutes** on GitHub-hosted runners — every job in
`build.yml`/`release.yml` that needs Xcode runs on the chosen `<runner>` (macOS) for exactly that reason; the
library flavor's `ubuntu-latest` matrix leg (Step 4) and the app flavor's Linux `lint`/`sonar`/`docs`/`site`/
`version` jobs (Steps 5 and 7) deliberately avoid that cost where they safely can.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-swift-github-workflows`) or as a step inside another skill,
which resolves the parameters below itself and passes them through `args` as `key: value` lines, one per line,
e.g.:

```
flavor: library
platforms: ios, macos
runner: macos-26
xcode-version: 26.6
integration-branch: develop
stable-branch: main
branching: gitflow
release-please: no
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_example-library
sonar-host-url: https://sonarcloud.io
security-dependency-review: yes
security-codeql: yes
security-osv: yes
security-gitleaks: yes
visibility: public
ci-tests: full
pages: yes
```

Parse any such lines first; for each key found there, use that value directly and skip the matching
fact-finding step below. If `args` is absent or doesn't look like this format, treat everything as unset and
gather every fact via Step 1's survey / `AskUserQuestion` instead. Bounded choices (`flavor`, `runner`,
`branching`, `generator`, `signing`, `sonar`, `publish`/`distribution`, `release-please`, the `security-*`
toggles, `visibility`, `ci-tests`, `pages`, the per-file existing-workflow question) go through `AskUserQuestion` with at most four options per question when not resolved by `args` or
Step 1's survey; free-text values (branch names, the Sonar organization/key/host, `xcode-version`) are asked as
plain conversation.

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `flavor` | `library` or `app` | Required — detect from Step 1 if not given (a root `Package.swift` with no generator manifest → `library`; `project.yml`/`Project.swift` → `app`), ask if ambiguous |
| `platforms` | Comma-separated platform list | Library: read from `Package.swift`'s `platforms:` array (Step 1); app: read from `project.yml`/`Project.swift`'s per-platform targets (Step 1) |
| `runner` | `macos-26` (GitHub-hosted GA image) or `xcode-27` (the newer-Xcode preview image label — see the note below) | `macos-26` |
| `xcode-version` | Passed to `maxim-lobanov/setup-xcode`'s `xcode-version` input | Looked up in Step 2 against the chosen `<runner>` image's own documented default |
| `integration-branch` | Branch `build.yml` also runs on (gitflow only) | `develop` |
| `stable-branch` | Branch releases are published from, and Pages deploys from | `main` |
| `branching` | `gitflow` or `main-only` | `gitflow` |
| `generator` | `xcodegen` or `tuist` (app only) | Detect from Step 1 (`project.yml` vs. `Project.swift`); ask only if genuinely ambiguous |
| `signing` | `fastlane-match`/`api-key`/`none` (app only) | Detect from `fastlane/Fastfile`'s existing lanes (Step 1) if `iru-setup-apple-app` already ran; otherwise ask, default `none` recommended unless `distribution` isn't `none`. Only `fastlane-match` **with** a `fastlane/Fastfile` on disk switches the iOS release leg to Fastlane (Step 7); every other combination uses plain `xcodebuild` with the App Store Connect API key |
| `release-please` | `yes`/`no` (library only) — an optional automated changelog/version-bump PR job in `release.yml` | `no` — this catalog already has a hand-driven release path (`iru-release`/`iru-swift-bump-version`); release-please is an alternative, automated path some teams prefer instead, not a required addition |
| `open-source` | `yes`/`no` | `gh repo view --json isPrivate` if unresolved otherwise |
| `publish` (library only) | `yes`/`no` — informational only for this skill (gates nothing here; a SwiftPM library "publishes" by existing at a tagged git URL, per `iru-setup-swift-library`) | Accepted so an orchestrator can pass its full shared-args set without error; noted in the final report if supplied |
| `distribution` (app only) | `none`/`internal`/`store` — `release.yml` is written only when it isn't `none`; `internal`/`store` pick the Fastfile lane (`beta`/`release`) on the Fastlane path | `store` only when `open-source: yes`; otherwise ask, `none` recommended |
| `sonar` | `cloud`/`self-hosted`/`none` | `cloud` when `open-source: yes` **and** `visibility: public`; otherwise ask (see "Standalone resolution" below), `none` recommended, with the paid-plan caveat stated |
| `sonar-organization` / `sonar-project-key` / `sonar-host-url` | Sonar coordinates | Only asked when `sonar` isn't `none`; read from an existing `sonar-project.properties` first (Step 1) |
| `mode` | `new`/`existing` | Informational: whether the caller considers this a first-time setup or a re-run. It no longer short-circuits anything — the workflow files actually on disk decide, per file, whether Step 1's warn-and-ask applies (`mode: existing` still asks per existing file unless `on-existing` says otherwise) |
| `visibility` | `public`/`private`/`internal` — the GitHub repository's visibility; `internal` behaves like `private` for every cost decision | See "Standalone resolution" below |
| `ci-tests` | `unit`/`full` — which tests `build.yml` runs (Step 4/5) | Public: `full`; private/internal: ask, `unit` recommended (see below) |
| `pages` | `yes`/`no` — deploy the DocC + Antora site to GitHub Pages (`yes`), or only build it and upload it as a workflow artifact (`no`) | Public: `yes` without asking (stated in the report); private/internal: ask, `no` recommended (see below) |
| `on-existing` | `ask`/`keep`/`update`/`replace` — how Step 1 treats each workflow file that already exists | `ask` |
| `security-dependency-review` / `security-codeql` / `security-osv` / `security-gitleaks` | `yes`/`no`, each gates one `security.yml` job | `yes` |
| `license` / `developer-name` / `developer-email` / `organization-url` | This catalog's shared scaffold-args vocabulary | **Not used by this skill's generated files** — accepted only so an orchestrator can pass its full shared-args set without this skill erroring on unrecognized keys; note this in the final report if any were supplied |

**The `runner` choice, explained.** GitHub's own hosted macOS runner fleet (`actions/runner-images`) currently
ships two relevant image families: a stable, generally-available image labeled by macOS major version
(`macos-26`, tracking macOS 26 "Tahoe"), and a newer-Xcode preview image labeled by Xcode major version instead
(`xcode-27`, tracking the Xcode 27 public preview, itself running on a newer macOS major the GA label hasn't
adopted yet) — confirmed live via `gh api repos/actions/runner-images/contents/images/macos` returning exactly
`macos-14-Readme.md`, `macos-15-Readme.md`, `macos-26-Readme.md` (plus each `-arm64` variant), and
`xcode-27-arm64-Readme.md`, during this skill's own verification (September 2026). `runs-on: <runner>` accepts
either label verbatim whether it's satisfied by GitHub's own hosted fleet or by a self-hosted/Actions Runner
Controller (ARC) pool an organization labels the same way by convention (e.g. pinning a self-managed macOS
runner group to a specific Xcode and naming its label after that version) — the workflow YAML is identical
either way, since `runs-on:` only ever matches a label string. Default to **`macos-26`**: it's the stable,
generally-available image (fewer surprise outages than a public-preview image). Neither `macos-26` (the arm64
image) nor the `xcode-27` preview image ships SwiftLint preinstalled — only the Intel `macos-26-intel` image lists
it (see the correction below) — so the library flavor's SwiftLint step (Step 4) keeps its `command -v swiftlint ||
brew install swiftlint` guard on either label, and the app flavor lints on Linux instead. Recommend `xcode-27` only
when the project genuinely needs an Xcode 27 feature the `macos-26` image's own newest available Xcode doesn't have
yet (per that image's own `### Xcode` table, `macos-26` currently tops out at `26.6`, not `27.x`) — and warn that a
public-preview image can change or be pulled with less notice than a GA one. **Look up the
currently available labels at run time** rather than trusting this skill's own September 2026 snapshot: `gh api
repos/actions/runner-images/contents/images/macos --jq '.[].name'` lists every `<label>-Readme.md`/
`<label>-arm64-Readme.md` file (strip the suffix for the label itself), and `curl -sL
"https://raw.githubusercontent.com/actions/runner-images/main/images/macos/<label>-arm64-Readme.md"` shows that
image's own `### Xcode` table (available versions, which one is `(default)`) and whether it lists SwiftLint
under `### Linters` — resolve `xcode-version`'s default from whichever `(default)`-marked row applies to the
chosen `<runner>`.

**Correction (re-checked live on 2026-10-16), and what it means for the app flavor.** The README of
`actions/runner-images` maps `macos-26`/`macos-latest` to the **arm64** image (`macos-26-arm64-Readme.md`) and only
`macos-26-intel`/`macos-26-large` to the x64 `macos-26-Readme.md`; SwiftLint 0.65.1 is listed **only on the Intel
image** — neither `macos-26` (arm64) nor `xcode-27` lists it, which is why the library flavor's `command -v swiftlint ||
brew install swiftlint` guard stays, and why the app flavor (Step 5) lints on Linux with the SwiftLint release binary
instead. The images' "Installed Simulators" tables decide the app flavor's visionOS leg: `macos-26` (arm64) ships
visionOS 26.2/26.4/26.5 and `xcode-27` ships visionOS 27.0 (Apple Vision Pro), the Intel image none — Step 5's
runner-image table has the full per-platform list. The app scaffold (`iru-setup-apple-app`) was verified with Xcode
27.1 (`project.yml` `options.xcodeVersion`), which only `xcode-27` offers today: for `flavor: app` pick
`xcode-version` the way `iru-setup-macos-github-workflows` does — the manifest's `xcodeVersion` when the chosen image
has it, otherwise the image default with a warning, recommending `runner: xcode-27` + `xcode-version: 27.1`.

**Standalone resolution of `visibility`, `sonar`, `ci-tests`, `pages`.** When an orchestrator
(`iru-setup-swift-repository`, the `iru-setup-repository` front door) invoked this skill it has already resolved
these and passed them in `args` — use them. When invoked stand-alone, resolve whichever are missing, in this order,
after `open-source` (never ask `ci` — invoking this skill means CI is wanted):

1. **`visibility`**: if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
   (returns `PUBLIC`/`PRIVATE`/`INTERNAL` — lowercase it) and tell the user the detected value; otherwise (no
   remote yet, `gh` unavailable, not GitHub) ask with `AskUserQuestion` — **Public** / **Private** — recommending
   **Public** when `open-source: yes`, otherwise **Private**. If `open-source: yes` but `visibility` is `private`/
   `internal`, warn that the two contradict (an open-source project's repository is normally public, and a private
   repository forfeits the free-for-public-repos pricing below) and ask which one is right.
2. **`sonar`** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" Recommend
   **`cloud`** only when the repository is public **and** open source; otherwise recommend **`none`**, with
   **self-hosted** as the alternative, stating that SonarCloud (SonarQube Cloud) is free for public/open-source
   projects but a private project needs a paid plan, while self-hosted SonarQube Community Build is free but you
   run the server.
3. **`ci-tests`** — "Which tests should CI run?" Options: **Unit tests only** — "unit-test targets only (cheaper:
   fewer macOS minutes, faster feedback); the app's XCUITest UI-test bundle, and any library integration-test
   target, stay local" (recommended for private/internal) / **Unit + UI tests** — "unit tests plus XCUITest UI
   tests on every selected simulator/platform destination (complete verification, uses considerably more macOS
   minutes — each UI-test run boots a simulator and launches the app)" (recommended for public).
4. **`pages`** (the DocC + Antora site): public → `yes` without asking (say so in the report). Private/internal →
   ask: **No (Recommended)** — "CI still builds the docs (validating them) and uploads them as a workflow
   artifact; nothing is published" / **Yes** — "requires GitHub Pro, Team, or Enterprise Cloud (not GitHub Free);
   the site will be PUBLIC on the internet unless you're on Enterprise Cloud with private Pages access control".

State the cost facts in these questions (per GitHub's current pricing — tell the user to double-check their own
plan): standard GitHub-hosted runners are **free for public repositories**; private/internal repositories consume
the account's included minutes — **2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise
Cloud** — then are billed per minute, and **macOS runner minutes cost roughly 10× Linux minutes** (per-minute
rates: Linux 2-core $0.006, macOS $0.062). Nearly every job this skill generates runs on macOS, so for a private
repository state plainly that a few dozen macOS builds a month (each `build.yml` run being tens of macOS minutes,
×10 against the budget) can exhaust 2,000 included minutes — `ci-tests: unit` is the main lever this skill offers
against that. Dependabot version/security update runs on standard hosted runners are free.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

**Templates.** The workflow, script and Dependabot templates of Steps 4–9 are files under this skill's `templates/` directory, not inline text. Read only the template files for the `flavor` (`library` or `app`) resolved in Step 0/1 (the index at the top of Step 3 says which applies); never read the others.

## Step 1 — Survey the target repository

Gather every fact Step 0 didn't already resolve via `args`, and decide whether this is a fresh setup or an
update:

- **Project shape / `flavor`** (skip if supplied via `args`): a root `Package.swift` with no `project.yml`/
  `Project.swift`/`*.xcodeproj` at the root → `library`; any of `project.yml` (XcodeGen), `Project.swift`
  (Tuist), or a checked-in `*.xcodeproj` → `app`. Both present (an app repository with its own root
  `Package.swift` in addition to `Packages/Core`'s) → prefer `app`, same disambiguation
  `iru-swift-bump-version`'s Step 2 uses; ask via `AskUserQuestion` only if genuinely ambiguous.
- **`platforms`** (skip if supplied via `args`): library — parse `Package.swift`'s `platforms:` array
  (`.iOS(.v17)` → `ios`, `.macOS(.v14)` → `macos`, etc.; an absent `platforms:` key with no non-Apple-only
  imports implies `linux` is at least implicitly supported, per `iru-setup-swift-library`'s own Step 2 note —
  don't add a `linux` matrix leg unless the package's own source is confirmed Apple-API-free, see Step 4). App —
  parse `project.yml`'s `targets:` platform values or `Project.swift`'s `destinations:`/`Target.target(...)`
  blocks (one entry per selected platform, per `iru-setup-apple-app`'s own template shape).
- **`generator`** (app only, skip if supplied via `args`): `project.yml` present → `xcodegen`; `Project.swift`
  present → `tuist`. If both are present (a mid-migration repository), ask which one CI should drive.
- **Test targets** (for `ci-tests`): library — list `Package.swift`'s `.testTarget(...)` names and flag any
  integration-test target (name containing `IntegrationTests`, or one the user identifies as such); app — read
  each platform's UI-test target name from the manifest (`<AppName>UITests-<Platform>` in
  `iru-setup-apple-app`'s scaffold) so `-skip-testing:` names the real target, and confirm one scheme per platform
  named `<AppName>-<Platform>` (`iOS` for `ios`/`ipados`, `watchOS`, `visionOS`, `macOS`) whose test action lists
  `Core/CoreTests` + `<AppName>Tests-<Platform>` + `<AppName>UITests-<Platform>` — the matrix legs of Step 5. Note the
  iOS target's `TARGETED_DEVICE_FAMILY` (`"2"` alone → `<ios-device-prefix>` `iPad`), whether the watch target is
  embedded in the iOS app (`embed: true` → companion) or standalone (`WKWatchOnly`), and whether `fastlane/Fastfile`
  exists at all (it is optional — `iru-setup-apple-app` never requires it).
- **`signing`/`distribution`** (app only, skip whichever is supplied via `args`): grep `fastlane/Fastfile` for
  `sync_signing`/`match(` (→ `signing: fastlane-match`) or `connect_api_key`/`app_store_connect_api_key(` (→
  `signing: api-key`); for `distribution`, a `release`/`upload_to_app_store` lane implies `store`, a `beta`/
  `upload_to_testflight` lane with no `release` lane implies `internal`, neither implies `none` — matching
  exactly what `iru-setup-apple-app`'s own Step 5 conditionally writes. If `fastlane/Fastfile` doesn't exist yet,
  fall back to Step 0's normal ask.
- **Existing `sonar-project.properties`**: if present, read its `sonar.organization`/`sonar.projectKey`/
  `sonar.host.url` directly rather than asking the user again (written by `iru-setup-swift-library`/
  `iru-setup-apple-app`, Step 9/Step 5 of those skills respectively) — treat a `https://sonarcloud.io` host (or
  no host line) as `sonar: cloud`, anything else as `sonar: self-hosted`. Absence falls back to Step 0's
  resolution as usual.
- **`version.txt`/`CHANGELOG.md`** (library, informational for `sync.yml`/`release.yml`): note whether each
  exists — `sync.yml`'s companion script (Step 8) only rewrites `version.txt` if it already exists (mirroring
  `iru-swift-bump-version`'s own "don't create one unless something reads it" rule).
- **`docs/antora.yml`/`docs/antora-playbook.yml`**: if either is missing, the Antora build step in `build.yml`
  has nothing to build against — recommend running `iru-setup-antora` first, or confirm the user will do so
  before merging this workflow. The docs job runs `npm ci` in `docs/` and then Pagefind, so `docs/package.json` and the
  committed `docs/package-lock.json` are needed as well. If `docs/` exists but has no lockfile (a site from the old per-package install), recommend `/iru-update-antora` instead: `actions/setup-node`'s npm cache fails the job without it.
- **Branch names** (skip whichever is supplied via `args`): confirm the integration/stable branch names actually
  match this repository's branching model (`git branch -a`) rather than assuming gitflow defaults; for
  `branching: main-only`, only `stable-branch` matters.
- **Existing workflows**: check whether `.github/workflows/build.yml`, `release.yml`, `sync.yml`,
  `.github/scripts/sync_versions.py`, `security.yml`, and `.github/dependabot.yml` already exist (only the
  subset relevant to the resolved `branching`, plus the three always-present files).

### Announce the mode, then warn and ask per existing file

Tell the user which of the two this run is before writing anything:

- **None of the relevant files exist**: "**Creating the CI/CD pipeline from scratch** — no workflow files found."
  Skip straight to Step 2; every Step 12 file is *created*.
- **Any relevant file already exists**: "**Updating an existing CI/CD pipeline** — found: <list of paths>." For
  each existing file, **warn** and resolve what to do with it. If `on-existing` is `keep`/`update`/`replace`, apply
  it to every existing file without asking (still print the warning and the list). Otherwise (`on-existing: ask`
  or unset — and regardless of `mode`), ask with `AskUserQuestion`, one question per file, batching up to four
  files per call (`sync.yml` + `sync_versions.py` count as one artifact). When an existing file contradicts this
  run's choices, say so concretely in its question — e.g. "your current `build.yml` runs the `<AppName>UITests-iOS`
  bundle but you chose `ci-tests: unit`", "your current `build.yml` deploys to Pages but you chose `pages: no`",
  "your current `build.yml` still runs a Sonar scan but you chose `sonar: none`". Options, with these consequences
  stated in their descriptions:
  - **Update (Recommended)** — merge: add what's missing and correct outdated parts using this skill's templates as
    reference, keeping everything this skill doesn't own; you review a diff before it's written. Customizations
    survive, but a customized part this skill owns may be rewritten. Concretely:
    - For `build.yml`/`release.yml`: preserve any step/job this skill doesn't own (a Slack notification, an
      extra matrix entry, a hand-added deployment target) — only add missing stages or correct outdated ones
      (wrong action version, wrong path, a stage that should now be gated on/off per `sonar`/`distribution`/
      `signing`/`release-please`/`ci-tests`/`pages`), and don't reorder foreign steps.
    - For `sync.yml`/`sync_versions.py`: preserve any file the script updates beyond what Step 8's template
      touches, and preserve any extra workflow step the same way.
    - For `security.yml`: preserve any job this skill doesn't own; only add/remove/update the
      dependency-review/CodeQL/OSV-Scanner/gitleaks jobs per the resolved `security-*` args.
    - For `.github/dependabot.yml`: preserve any `updates:` entry for an ecosystem this skill doesn't manage;
      only add/update the `swift` and `github-actions` entries.
  - **Keep as is** — leave the file untouched. Nothing customized changes, but it may stay out of date or disagree
    with this run's choices (e.g. still runs UI tests after `ci-tests: unit`, still deploys to Pages after
    `pages: no`, still runs a Sonar step after `sonar: none`) — Step 14 lists each mismatch.
  - **Replace** — regenerate from this skill's template. The result matches this house pattern exactly, but every
    customization in the file is lost (recoverable only from git history). Never recommended by default.
  - **Stop** — end this skill now without changing any file (including ones already decided). Report which
    file(s) already exist and end here.

Record each file's decision; Step 13 applies it and Step 14 reports it as **created / updated / replaced / kept
as is**.

## Step 2 — Look up action/tool versions at run time

**Versions are looked up at run time, not hardcoded from this file.** Use the GitHub Releases/tags API
(`gh api repos/<owner>/<action>/releases/latest`, or `git ls-remote --tags` for the "floating major tag?"
column — the REST `/tags` listing can miss a real floating tag, the same caveat the Android sibling's own Step
2/13 documents) for every `uses:` action, and the Homebrew formula/cask JSON API
(`https://formulae.brew.sh/api/formula/<name>.json` / `.../api/cask/<name>.json`, reading `.versions.stable`/
`.version` — reachable over plain HTTPS with no `brew` installed, confirmed in `iru-setup-apple-app`'s own
verification) for `swiftlint`/`xcodegen`/`tuist`/`xcbeautify`, since none of those are `uses:` actions. If a
lookup is unreachable, fall back to the versions confirmed current as of **September 2026**, listed here (every
`uses:` action below was confirmed to resolve as a real `gh api`/`git ls-remote --tags` ref during this skill's
own verification):

| Action / tool | Fallback version | Floating major tag? | Notes |
|---|---|---|---|
| `actions/checkout` | `v7` | yes | |
| `maxim-lobanov/setup-xcode` | `v1` | yes | |
| `actions/setup-node` | `v7` | yes | used only for the Antora build and Pagefind index steps (Node 24) |
| `actions/setup-python` | `v7` | yes | used only by `sync.yml`, `branching: gitflow` only |
| `actions/upload-artifact` | `v7` | yes | uploads each `.xcresult` bundle, the Core coverage/SwiftLint reports, the Antora site and DocC archive (app flavor `build.yml`) and the notarized macOS `.zip` (app flavor `release.yml`) |
| `actions/download-artifact` | `v8` | yes | app flavor `build.yml` only — the `sonar` and `site` jobs (same as `iru-setup-macos-github-workflows`) |
| `actions/cache` | `v6` | yes | caches `.build` keyed on `Package.resolved`/`Package.swift`, library flavor (macOS and Linux legs) |
| `actions/upload-pages-artifact` | `v5` | yes | |
| `actions/deploy-pages` | `v5` | yes | |
| `actions/dependency-review-action` | **`v5.0.0`** | **no** | never published a floating `v5` tag — only exact-version tags; pin the exact version and re-check at generation time |
| `github/codeql-action` (`init`/`analyze`) | `v4` | yes | resolve via `git ls-remote --tags`, not `.../releases/latest` — that endpoint returns the unrelated `codeql-bundle-*` tag (confirmed again during this skill's own verification, same finding as the Android sibling's Step 13) |
| `google/osv-scanner-action` (reusable workflow) | **`v2.6.0`** | **no** | only exact-version tags; pin the workflow ref to the exact tag |
| `gitleaks/gitleaks-action` | `v3` | yes | |
| `SonarSource/sonarqube-scan-action` | `v8` | yes | only when `sonar` isn't `none` |
| `googleapis/release-please-action` | `v5` | yes | only when `release-please: yes` (library only) |
| `apple-actions/import-codesign-certs` | `v7` | yes | app flavor, `macos` selected, `distribution` isn't `none` — imports the Developer ID Application `.p12` the `developer-id` export needs (Step 7). (`apple-actions/upload-testflight-build` is no longer used: `-exportArchive` with `destination: upload` uploads directly) |
| `swift-actions/setup-swift` | `v2` | yes | library flavor's `ubuntu-latest` matrix leg — an alternative to the official `swift` Docker image (below) |
| `swift` Docker image (`container: swift:<tag>`) | `6.3-noble` | n/a — not a `uses:` action | the **open-source Linux Swift toolchain lags the Apple-toolchain-bundled Swift version** — confirmed during this skill's own verification: this machine's Xcode 27/Swift 6.4 has no Linux `swift:6.4*` tag published yet on Docker Hub, only up to `6.3.3`; re-check `https://hub.docker.com/v2/repositories/library/swift/tags` at generation time rather than assuming the two track each other. **Update 2026-10-16**: `6.4-noble`/`6.4.1-noble` are now published — the app flavor's Linux `lint` job uses `<linux-swift-tag>` = `6.4-noble` |
| `swiftlint` (Homebrew formula, not a `uses:` action) | `0.65.1` | n/a | library flavor: preinstalled only on the Intel `macos-26-intel` image (Step 0's correction); `brew install`ed when missing. App flavor: the `swiftlint_linux_{amd64,arm64}.zip` release asset of `<swiftlint-version>` (`gh release view --repo realm/SwiftLint --json tagName`) in the Linux `lint` job |
| `xcodegen` (Homebrew formula) | `2.46.0` | n/a | app flavor, `generator: xcodegen` |
| `tuist` (Homebrew **cask**, not formula — `iru-setup-apple-app`'s own Step 4 finding) | `4.208.0` | n/a | app flavor, `generator: tuist` |
| `xcbeautify` (Homebrew formula) | `3.2.1` | n/a | no longer used by the app flavor — `xcodebuild -quiet` keeps logs short and the `.xcresult` summary is printed with `xcresulttool` |

`GITHUB_TOKEN`'s automatic scopes are sufficient for every job above except where a secret is explicitly named
in Step 6/7/9's templates.

## Step 3 — Why this skill's templates have no single reference repository

**Templates.** Every template in Steps 4–9 is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that generated file's template. Read only the files for the `flavor` resolved in Step 0/1 (plus the `common` ones that apply), each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/library/.github/workflows/build.yml` | `flavor: library` (Step 4) |
| `.github/workflows/build.yml` | `templates/app/.github/workflows/build.yml` | `flavor: app` (Step 5) |
| `.github/workflows/release.yml` | `templates/library/.github/workflows/release.yml` | `flavor: library` (Step 6) |
| `.github/workflows/release.yml` | `templates/app/.github/workflows/release.yml` | `flavor: app`, only when `distribution` is not `none` (Step 7) |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | both flavors, only when `branching: gitflow` (Step 8) |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | both flavors, only when `branching: gitflow` (Step 8) |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | both flavors, always (individual jobs omittable via the `security-*` args) (Step 9) |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | both flavors, always (Step 9) |
| `.github/scripts/lcov_to_sonar_generic.py` | `templates/common/.github/scripts/lcov_to_sonar_generic.py` | both flavors, whenever `sonar` is not `none` (Step 9) |

Unlike `iru-setup-java-github-workflows`/`iru-setup-typescript-github-workflows`/
`iru-setup-android-github-workflows`, each of which genericizes its templates from one real repository's actual
workflow files, no equivalent real Swift/Apple repository was available to derive this skill's templates from.
Instead, every template in Steps 4–9 is assembled from:

- The exact, verified commands `iru-setup-swift-library`, `iru-setup-apple-app`,
  `iru-swift-coverage`, and `iru-swift-bump-version` already established and verified for this catalog's Swift
  stack — the build-products path, the `.xctest` binary naming, the `llvm-cov`/`xccov` invocations, the DocC
  flags, the version-file rewrite targets — reused here verbatim rather than reinvented.
- This skill's own verification pass (Step 13), run against `iru-setup-swift-library`'s actual generated scaffold.
- The Java/TypeScript/Android siblings' own conventions for the parts that are ecosystem-agnostic — the
  Dependabot/CodeQL/dependency-review/OSV-Scanner/gitleaks security block shape, the Pages-deploy-from-Actions
  mechanism, the gitflow `sync.yml` merge-back-and-bump pattern — carried over for consistency across this
  catalog rather than invented fresh for Swift.

Every `<placeholder>` below is resolved from Step 1's survey / Step 0's `args` before writing the real files —
the full resolution table is in Step 10. Drop whichever blocks this repository's `flavor`/`sonar`/
`distribution`/`signing`/`release-please`/`branching` values gate off, per the inline comments in the templates,
including the comment itself — it's authoring guidance, not part of the generated file.

## Step 4 — `build.yml` (`flavor: library`)

Template: `templates/library/.github/workflows/build.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-build-library.md` — read it in full together with the template.

## Step 5 — `build.yml` (`flavor: app`)

A complete, separate template — the app flavor shares only the trigger and the security/sync files with Step 4. It
follows `iru-setup-apple-app`'s **scaffold contract** (its Step 8 table) and the job layout
`iru-setup-macos-github-workflows` verified for the macOS desktop app: **what can run on Linux runs on
`ubuntu-latest`** (SwiftLint/`swift format` in a `swift` container, the Sonar scan, Antora, the docs merge/deploy),
and only what needs Xcode uses the macOS `<runner>`. Jobs: `lint` (Linux) → `core` (macOS: `Packages/Core` tests +
the 80% line bar + DocC) and `apps` (macOS, **one matrix leg per selected platform scheme** —
`<AppName>-iOS`/`-watchOS`/`-visionOS`/`-macOS`, each running `Core/CoreTests` + `<AppName>Tests-<P>` +
`<AppName>UITests-<P>`), then `sonar` (Linux), `docs` → `site` → `deploy-docs` (Linux). **No job needs Fastlane.**

**Conditional markers** (app templates in Steps 5 and 7): a line `# >>> <condition>` opens a block written only when
the condition holds and `# <<< <condition>` closes it; marker lines are never written, blocks nest, and a
comma-separated condition must hold entirely. Markers also appear inside `run: |` scripts.

| Condition | Holds when |
|---|---|
| `gitflow` / `main-only` | `branching` |
| `generator: xcodegen` / `generator: tuist` | `generator` |
| `platform: iOS` / `watchOS` / `visionOS` / `macOS` | that target exists (`ios` and/or `ipados` → the one `iOS` target) |
| `watchos-standalone` | `watchOS` selected without `iOS` (otherwise the watch app is embedded in the iOS archive) |
| `ci-tests: unit` / `ci-tests: full` | `ci-tests` |
| `pages: yes` / `pages: no` | `pages` |
| `sonar` | `sonar` ≠ `none` |
| `distribution: internal` / `distribution: store` | `distribution` (`release.yml` is written only when it is not `none`) |
| `fastlane` / `no-fastlane` | `fastlane` = `fastlane/Fastfile` exists **and** `signing: fastlane-match`; `no-fastlane` otherwise |
| `app-store-job` | `release.yml` only: the `app-store` matrix has at least one leg — `platform: iOS` with `no-fastlane`, `platform: visionOS`, or `watchos-standalone` |

Extra placeholders: `<ios-device-prefix>` (`iPhone`; `iPad` when only `ipados` was selected — `TARGETED_DEVICE_FAMILY`
`"2"`), `<linux-swift-tag>` (the `swift` Docker image for the lint job — `<major.minor>-noble` of the scaffold's
Swift, e.g. `6.4-noble`), `<swiftlint-version>` (`gh release view --repo realm/SwiftLint --json tagName`, e.g.
`0.65.1`), `<repo>`.

Template: `templates/app/.github/workflows/build.yml` — read it in full before filling placeholders

**Runner images vs. the per-platform legs** — looked up live on 2026-10-16 (`gh api
repos/actions/runner-images/contents/images/macos --jq '.[].name'`, then each `<label>-Readme.md`'s `### Xcode` and
"Installed Simulators" tables; the repository README maps the labels). Re-check at generation time and state the
result in the report:

| Label (image) | Xcode | Simulator runtimes (device names) | SwiftLint |
|---|---|---|---|
| `macos-26`, `macos-latest` (`macos-26-arm64`, macOS 26.6.2) | 26.0.1–**26.6 (default)** | iOS 26.2/26.4/26.5 (iPhone 17 family), watchOS 26.2/26.4/26.5 (Series 11, SE 3, Ultra 3), **visionOS 26.2/26.4/26.5 (Apple Vision Pro)** | not listed |
| `xcode-27` (`xcode-27-arm64`, public preview, macOS 27.0.1) | **27.0 (default)**, 27.1, 27.2 beta | iOS 27.0 (iPhone 17/17e/18 Pro/Air), watchOS 27.0 (Series 12, SE 3, Ultra 4), **visionOS 27.0 (Apple Vision Pro)** | not listed |
| `macos-26-intel`, `macos-26-large` (`macos-26`, x64) | 26.0.1–26.6 | iOS and watchOS 26.x — **no visionOS runtime** | 0.65.1 |

So on both arm64 labels the visionOS leg **does run its tests**; on an Intel label (or a self-hosted Mac without the
visionOS component) it builds for testing and emits the "unverified" warning. Fastlane (2.239–2.241) and xcbeautify
3.2.1 are preinstalled everywhere but unused here; XcodeGen is not (the `brew install` guard). `xcode-version`
follows `iru-setup-macos-github-workflows`' rule: `project.yml`'s `options.xcodeVersion` (the scaffold's `27.1`) when
the chosen image offers it, otherwise the image default **with a warning** that the scaffold was verified with Xcode
27.1 only — recommend `runner: xcode-27` + `xcode-version: 27.1` (and declare `xcode-27` in `actionlint.yaml`'s
`self-hosted-runner.labels` when linting: actionlint 1.7.12 doesn't know the preview label).

Notes specific to this template: `reference/notes-build-app.md` — read it in full together with the template.

## Step 6 — `release.yml` (`flavor: library`)

Template: `templates/library/.github/workflows/release.yml` — read it in full before filling placeholders

**This workflow never publishes anything** — a SwiftPM library's release artifact **is** the git tag a consumer's
`.package(url:, from:/exact:)` resolves against (per `iru-swift-bump-version`'s own Step 5); `verify-release`
only confirms the tag/manifest are sound *after* the tag already exists, so a broken release is caught
immediately (surfaced as a failed check against the just-published Release) rather than only when a consumer's
own build breaks later. Runs on `ubuntu-latest` with the official `swift` container deliberately (this skill's
intro note on macOS-minutes cost) — neither check needs Xcode.

## Step 7 — `release.yml` (`flavor: app`)

Written only when `distribution` ≠ `none` (with `distribution: none` there is nothing to ship — say so in the report).
Same markers as Step 5. **Plain `xcodebuild` by default — Fastlane is never required**: every App Store Connect
platform (iOS with its embedded watch app, visionOS, a standalone watchOS app) is archived with automatic signing
authenticated by the App Store Connect API key (`-allowProvisioningUpdates`, cloud-managed certificates), then
`-exportArchive` with `method: app-store-connect` + `destination: upload` signs and uploads the build to App Store
Connect (TestFlight) in the same step — the path the author's docs show (irurueta docs:
apps/apple/ci-cd-xcode-cloud-and-command-line.adoc) and `iru-setup-macos-github-workflows`' `app-store` job uses. A
selected macOS target ships outside the store with Developer ID + notarization, reusing that skill's `developer-id`
steps without the DMG (this scaffold has no `scripts/make-dmg.sh`). **Only when `fastlane/Fastfile` exists and
`signing: fastlane-match`** (`fastlane`) does the iOS leg run the Fastfile's own `match` + `beta`/`release` lane
instead; visionOS/watchOS/macOS stay on the plain `xcodebuild` jobs either way (the Fastfile has no lanes for them).
`signing: none` with `distribution` ≠ `none` gets the plain `xcodebuild` path (which needs the API key) — warn that
`iru-setup-apple-app` recorded `none`.

Template: `templates/app/.github/workflows/release.yml` — read it in full before filling placeholders

`<release-lane>` is `beta` for `distribution: internal` and `release` for `distribution: store` (the Fastfile lanes
`iru-setup-apple-app` gates in). The `app-store` job exists only under `app-store-job` — with `fastlane` and only the
iOS target (plus an embedded watch app) its matrix would be empty, which GitHub Actions rejects.

Notes specific to this template: `reference/notes-release-app.md` — read it in full together with the template.

## Step 8 — `sync.yml` + `sync_versions.py` (`branching: gitflow` only)

Runs once a GitHub Release is published, provided it was published from the stable branch. Opens a pull request
that merges the released branch back into the integration branch and bumps the development version — a direct
port of `iru-setup-java-github-workflows`'s/`iru-setup-android-github-workflows`'s own `sync.yml` shell logic,
with the version-bump step replaced by exactly the rewrite rules `iru-swift-bump-version` already verified.
The sync branch is named after the released version (`sync_<released version>`, e.g. release `1.0.1` →
`sync_1.0.1`). If merging the stable branch conflicts, the merge step aborts, the branch is created from the
release commit, the bump steps are skipped and the PR title says "merge conflicts to resolve" — a conflicting
(patch) sync never bumps, so it cannot lower the integration branch's development version:

### `sync.yml`

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

Notes specific to this template:

- **A patch bump** (`x.y.z` → `x.y.(z+1)`), matching `iru-swift-bump-version`'s own Step 5 documented convention
  for both the library and app cases (there is no `-SNAPSHOT`/`-dev.N`-style pre-release marker in either Swift
  case to gate a minor-vs-patch choice on, unlike the Java sibling's Maven `-SNAPSHOT` convention). If this
  repository does minor/major-level releases too, flag it as an open gap in the final report.
- **Write exactly one `Commit the version bump` step**, with the `git add` line for the resolved `flavor` and
  (app only) `generator` — the two variants above are alternatives, never both. Every path on that line must
  exist in the repository: `git add` fails on the first unmatched pathspec *before* staging anything, so a
  catch-all list with `2>/dev/null || true` would swallow the error and commit nothing at all. Keep only the
  files `sync_versions.py` can actually touch — `version.txt` (library), `project.yml` **or** `Project.swift`
  (app, by generator), `CHANGELOG.md`, `README.md`, `docs/antora.yml`, `docs/modules/ROOT/pages` — and drop any
  of those the repository genuinely lacks (e.g. no Antora docs: remove both `docs/…` entries; Step 1's survey
  knows). If a path may legitimately be absent, add it on its own line (`git add <path> || true`) so its failure
  can't mask the others.
- Same "Workflow permissions" repository-setting caveat as every sibling's `sync.yml` — the `permissions:` block
  here is necessary but not sufficient; Settings → Actions → General → "Workflow permissions" must also allow
  GitHub Actions to create pull requests, or `gh pr create` fails despite the token having the right scopes.
- `CHANGELOG.md` is deliberately not given its own rewrite logic beyond what `sync_versions.py` does (see
  below) — matching every sibling's own reasoning, its release section is written before the tag is published
  and arrives on `<integration-branch>` via the merge step above.

### `.github/scripts/sync_versions.py`

Reads `RELEASE_VERSION`/`NEXT_VERSION` from the environment and rewrites exactly the same files
`iru-swift-bump-version` rewrites for each flavor, reusing that skill's own verified regexes so a manual
`/iru-swift-bump-version` run and this CI-driven script never drift apart:

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

Notes specific to this template:

- **`update_version_txt`/`update_readme_package_snippets` are called with `release_version`, not
  `next_version`** — a deliberate correctness choice, not an oversight, caught and fixed while verifying this
  skill (see "Known quirks" below). `version.txt` and a library's README `.package(...)` snippet must always
  name a version that actually exists as a git tag; `next_version` never does (it's the *next*, not-yet-released
  value) — writing it there would advertise a dependency version a consumer's `swift build` can't resolve. The
  merge step that runs immediately before this script already carries the correct `release_version` value
  forward from the stable branch (where `iru-swift-bump-version`/`iru-release` set it before the tag was cut),
  so these two calls are a defensive reaffirmation, not the primary mechanism.
- Every `update_*` function no-ops cleanly when its target file doesn't exist — safe to run against either a
  library-only or app-only repository without branching the script itself on `flavor`.
- `update_changelog` here only reopens a fresh `[Unreleased]` heading — unlike `iru-swift-bump-version`'s own
  `CHANGELOG.md` rewrite (which also inserts the dated release-version heading), this script's release section
  was already written and tagged before the Release was published, so only the fresh `[Unreleased]` reopening is
  this script's job; adjust the regex per `iru-swift-bump-version`'s own verified form if a repository's
  `CHANGELOG.md` needs the fuller rewrite instead.
- `update_project_swift`'s regex assumes the same `"MARKETING_VERSION": "..."` dictionary-literal shape
  `iru-swift-bump-version`'s own Step 4 verified — a `Project.swift` with a different `SettingsDictionary`
  construction needs manual review after this script runs (same caveat that skill's own report step states).

## Step 9 — `security.yml`, `.github/dependabot.yml`, and `lcov_to_sonar_generic.py`

Runs on every pull request and on every push to the integration/stable branches (just the stable branch for
`main-only`), plus a weekly schedule so CodeQL/OSV-Scanner findings don't go stale between pushes. Each job is
individually omittable via the matching `security-*` `args` key (Step 0); drop that whole job's YAML — not just
its `if:` — when the resolved value is `no`:

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

Notes specific to this template:

- `codeql`'s `Generate Xcode project` step is emitted for `flavor: app` only (drop it for a library — `swift
  build` needs no generated project); it must come *before* `codeql-action/init`, since autobuild inspects the
  checkout for something to build the moment the extractor starts, and the app scaffold's `.xcodeproj` only
  exists after `xcodegen generate`/`tuist generate`.
- `codeql`'s `runs-on: <runner>` and its own `maxim-lobanov/setup-xcode` step make this the only `security.yml`
  job that runs on a macOS runner (and therefore the only one subject to the ~10x minutes-cost note) — every
  other language this catalog's `iru-setup-*-github-workflows` siblings cover runs CodeQL on `ubuntu-latest`;
  Swift genuinely cannot.
- `actions/dependency-review-action@v5.0.0` and the OSV-Scanner reusable-workflow ref are pinned to an exact
  tag, not a floating major — see Step 2's table for why.
- None of the four jobs need a secret beyond the automatically provided `GITHUB_TOKEN`.
- If `github.com` public-repository default CodeQL setup is simpler for this repository than a workflow-based
  CodeQL job, note that as an alternative in the final report instead of writing the `codeql` job, same as every
  sibling.
- **`flavor: app`**: the generated project holds one scheme per platform plus XcodeGen's `Core` package scheme, so
  autobuild has to guess; prefer `build-mode: manual` with one explicit build between `init` and `analyze`, as
  `iru-setup-macos-github-workflows` does — `xcodebuild build -project <AppName>.xcodeproj -scheme <AppName>-<first
  platform> -destination 'generic/platform=<first platform> Simulator' -derivedDataPath build/dd-codeql
  CODE_SIGNING_ALLOWED=NO -quiet` (`generic/platform=macOS` for a macOS-only selection). That scheme also compiles
  `Packages/Core`, so every Swift source the app ships is extracted except other platforms' UI targets.

### `.github/dependabot.yml`

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

Always generated (or updated) regardless of the `security-*` args — grouped weekly updates for the `swift`
ecosystem (`Package.swift`'s own `dependencies:`, and, for an app, `Packages/Core/Package.swift`'s — Dependabot's
`swift` ecosystem support scans every `Package.swift` it finds under `directory: /` recursively, no separate
entry needed per nested package) and for the workflows this skill itself just wrote (`github-actions`). For
`flavor: app` there is **no root `Package.swift`** — point the `swift` entry at `directory: /Packages/Core`, the
only manifest (as `iru-setup-macos-github-workflows` does), rather than relying on a recursive scan. If
`.github/dependabot.yml` already exists with entries for other ecosystems, preserve them — only add or update
the `swift` and `github-actions` entries above (see Step 1's per-file Update path).

### `.github/scripts/lcov_to_sonar_generic.py`

Referenced by Step 4's library-flavor coverage-conversion step. A small, from-scratch SonarQube Generic Test
Coverage XML converter — verified end-to-end during this skill's own verification pass (Step 13) against a real
`coverage.lcov` produced by `xcrun llvm-cov export -format=lcov`:

Template: `templates/common/.github/scripts/lcov_to_sonar_generic.py` — read it in full before filling placeholders

Written whenever `sonar` isn't `none` — for `flavor: library` (Step 4) **and** `flavor: app`, whose `core` job (Step 5)
converts `Packages/Core`'s lcov from the repository root so paths read `Packages/Core/Sources/…`. The app flavor no
longer fetches SonarSource's `xccov-to-sonarqube-generic.sh`: the app schemes' `.xcresult` never attributes the
statically linked Core, and the UI layer is excluded from coverage (`sonar.coverage.exclusions=Targets/**`).

## Step 10 — Placeholder resolution table

| Placeholder | Resolved from |
|---|---|
| `<integration-branch>` / `<stable-branch>` | Step 1 branch survey / `args` |
| `<runner>` | Step 0 (`args`, default `macos-26`) |
| `<xcode-version>` | Step 0/2 |
| `<linux-swift-tag>` | Step 2's live Docker Hub lookup, fallback `6.3-noble` (library); app flavor's `lint` job: `<major.minor>-noble` of the scaffold's Swift, `6.4-noble` on 2026-10-16 |
| `<swiftlint-version>` | App flavor: `gh release view --repo realm/SwiftLint --json tagName -q .tagName`, fallback `0.65.1` |
| `<ios-device-prefix>` | App flavor: `iPhone`, or `iPad` when the iOS target's `TARGETED_DEVICE_FAMILY` is `"2"` (only `ipados` selected) |
| `<release-lane>` | App flavor, `fastlane` only: `beta` (`distribution: internal`) / `release` (`distribution: store`) |
| `<Name>` | Library: `Package.swift`'s target/product name (Step 1). Not used for the app flavor. |
| `<AppName>` / `<app-name>` | App: `project.yml`'s `name:` / `Project.swift`'s `Project(name:` (Step 1) |
| `<repo>` | Parsed from `git remote get-url origin`, used only in DocC's `--hosting-base-path <repo>/api` — the `/api` suffix is fixed (it is where the Pages merge step copies the archive), only the `<repo>` segment is substituted |
| `<team-id>` | No longer a placeholder for the app flavor — `release.yml` fills `teamID`/`DEVELOPMENT_TEAM` at run time from the `APPLE_TEAM_ID` secret |
| `<sonar-host-url>` / `<sonar-organization>` / `<sonar-project-key>` | Step 0/1 Sonar survey / `args`; only needed when `sonar` isn't `none` |
| `<generator>` | Step 1 (`project.yml` vs. `Project.swift`) |
| UI-test skip (app) | No placeholder: Step 5's `ci-tests: unit` marker block adds `-skip-testing:<AppName>UITests-$PLATFORM` to each matrix leg |
| `<skip-integration-tests>` | Library: `--skip <integration-test target>` only when `ci-tests: unit` **and** Step 1 finds an integration-test `testTarget` in `Package.swift` (e.g. `<Name>IntegrationTests`); empty otherwise. `swift test --skip` takes a test-identifier regex — confirm the flag against the target toolchain when first emitting it |

Apply the `flavor`/`sonar`/`distribution`/`signing`/`release-please`/`branching`/`ci-tests`/`pages` gating resolved in Steps 0–1:
drop every block the templates above mark with an "Omit this … if …" comment (and that comment itself) when the
matching value doesn't apply. The app templates (Steps 5 and 7) use `# >>> <condition>` / `# <<< <condition>` markers
instead — resolved per Step 5's condition table, marker lines never written.

## Step 11 — Conditional-blocks table

| Block | Appears when |
|---|---|
| `build-test-linux` job (Step 4) | `flavor: library`, and Step 1 confirms the package's `Sources/` has no Apple-only imports |
| DocC/Antora build steps | Any push landing on `<stable-branch>` — both flavors, regardless of `pages` |
| `Upload Pages artifact` step + `deploy-docs` job | The above, and `pages: yes` |
| `Upload docs artifact` step (`actions/upload-artifact`) | The above, and `pages: no` |
| `-skip-testing:<AppName>UITests-<Platform>` flags | `flavor: app`, `ci-tests: unit` |
| `apps` matrix legs `<AppName>-iOS`/`-watchOS`/`-visionOS`/`-macOS` | `flavor: app`, one per selected platform target (watchOS always its own leg; visionOS tests only when the runner has a visionOS runtime) |
| `Check Automation Mode (macOS UI tests)` step | `flavor: app`, `macos` selected, `ci-tests: full` |
| `--skip <integration-test target>` | `flavor: library`, `ci-tests: unit`, and an integration-test target exists |
| `Run SonarQube analysis` step + its coverage-conversion step | `sonar` is `cloud` or `self-hosted` |
| `lcov_to_sonar_generic.py` (vendored file) | `sonar` isn't `none` (both flavors) |
| `release-please` job in `release.yml` | `flavor: library`, `release-please: yes` |
| `release.yml` (app) | `flavor: app`, `distribution` isn't `none` |
| `app-store` job (plain `xcodebuild` archive + export with `destination: upload`) | `flavor: app`, `app-store-job` — iOS without the Fastlane path, visionOS, or a standalone watchOS app |
| `fastlane-ios` job (`fastlane ios <release-lane>`) | `flavor: app`, iOS selected, `fastlane/Fastfile` exists **and** `signing: fastlane-match` |
| `macos-developer-id` job | `flavor: app`, `macos` selected, `distribution` isn't `none` |
| `codeql` job | `security-codeql: yes` |
| `codeql`'s `Generate Xcode project` step (before `codeql-action/init`) | `flavor: app` |
| `dependency-review` / `osv-scanner` / `gitleaks` jobs | matching `security-*: yes` |
| `sync.yml` + `sync_versions.py` | `branching: gitflow` only |

## Step 12 — File matrix

| `flavor` × `branching` | Files written |
|---|---|
| `library` / `gitflow` | `.github/workflows/build.yml`, `release.yml`, `sync.yml`, `.github/scripts/sync_versions.py`, `.github/scripts/lcov_to_sonar_generic.py` (only if `sonar` isn't `none`), `security.yml`, `.github/dependabot.yml` |
| `library` / `main-only` | Same, minus `sync.yml`/`sync_versions.py` |
| `app` / `gitflow` | `.github/workflows/build.yml`, `release.yml` (only if `distribution` isn't `none`), `sync.yml`, `.github/scripts/sync_versions.py`, `.github/scripts/lcov_to_sonar_generic.py` (only if `sonar` isn't `none`), `security.yml`, `.github/dependabot.yml` |
| `app` / `main-only` | Same, minus `sync.yml`/`sync_versions.py` |

## Step 13 — Write the files, then verify

Apply Step 1's per-file decision to every file from Step 12's matrix, using the filled-in templates from Steps
4–9: write a file that doesn't exist (*created*); merge an existing one per **Update** after showing the diff
(*updated*); leave it untouched per **Keep as is** (*kept as is*); or overwrite it per **Replace** (*replaced*). `sync.yml` and `sync_versions.py` are written together — never write one
without the other, and never write either for `main-only`. `security.yml` and `.github/dependabot.yml` are
always written (or updated), independent of `flavor`/`branching`/`sonar`/`distribution` — only their own
`security-*` args gate individual jobs within `security.yml`.

Run through the `iru-gate-runner` agent where output could be long. Verification directory:
`$TMPDIR/iru-verify/swift/workflows/` (created fresh; never inside this repository):

1. Render both the `library` and `app` variants of every YAML file to disk (every combination of `sonar`/
   `distribution`/`signing`/`branching`/`release-please`/`ci-tests`/`pages` that changes which blocks appear at
   least once each).
2. YAML-validate every rendered file: `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>` (Ruby ships with macOS,
   confirmed present in this environment) — or `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))"`
   if PyYAML is available. The top-level `on:` key round-tripping as the boolean `True` under YAML 1.1's
   bare-word resolution is a known false flag in a Python-based validator, not a defect in the generated
   workflow (same note the Java/TypeScript/Android siblings record) — don't quote `on:` in the template to "fix"
   it.
3. Resolve every `uses:` ref with `gh api repos/<owner>/<repo>/git/ref/tags/<ref>` or `git ls-remote --tags
   https://github.com/<owner>/<repo> <ref>`, recording which actions lack a floating major tag (Step 2's table
   already reflects this skill's own such run).
4. Run the library flavor's `build.yml` shell steps for real against `iru-setup-swift-library`'s existing generated scaffold, copied first
   (never mutate the original) — see "Known quirks / verification notes" below for exact commands and results.

5. **App flavor** — render Steps 5/7 for at least two `iru-setup-apple-app` variants (copies, never the originals)
   and run `actionlint` with `shellcheck` on `PATH` (declare `xcode-27` under `self-hosted-runner.labels` in an
   `actionlint.yaml` passed with `-config-file`), then each job's commands locally: SwiftLint/`swift format`, the
   `core` step verbatim, `xcodegen generate`, the destination step, `build-for-testing` + `test-without-building`
   per scheme (shut the simulators down afterwards), the DocC step, the release `version` check and an unsigned
   `xcodebuild archive … CODE_SIGNING_ALLOWED=NO` per uploaded platform.

### Known quirks / verification notes — app flavor (2026-10-16, Xcode 27.1 / Swift 6.4)

- **Verified for real** in `$TMPDIR/iru-verify/apple/iru-setup-swift-github-workflows/` on copies of
  `iru-setup-apple-app`'s verified scaffolds (originals untouched): `ios-watchos-visionos` (SwiftUI MVVM, iOS +
  companion watchOS + visionOS, assembled from the `ios+ipados+visionos-swiftui` and `ios-swiftui-mvvm` copies) rendered
  gitflow / `ci-tests: full` / `pages: yes` / `sonar` / `distribution: store` / plain `xcodebuild` on `xcode-27`; and
  `uikit-mvc-ios` (UIKit MVC, iOS only) rendered main-only / `ci-tests: unit` / `pages: no` / `distribution: internal`
  / the `fastlane` path on `macos-26` (no `app-store` job); plus a marker-coverage render (standalone watchOS + macOS,
  `generator: tuist`, `ci-tests: full`). **actionlint 1.7.12 + shellcheck: 0 findings** for every `build.yml`/
  `release.yml`/`security.yml`, and a Ruby YAML parse of every file; `py_compile` of both scripts. The shared
  `sync.yml` (Step 8, unchanged) reports one shellcheck *style* note (SC2129, repeated `>> "$GITHUB_OUTPUT"`) —
  harmless.
- **Executed locally** (XcodeGen 2.46.0 and SwiftLint 0.65.1 from temp downloads): `swiftlint lint --strict` and
  `swift format lint --strict --recursive Targets Packages/Core/Sources Packages/Core/Tests` — clean on both; the `core`
  step verbatim — 7 and 8 Core tests, **100.00%** lines, the 80% check passing; `lcov_to_sonar_generic.py` →
  `Packages/Core/Sources/Core/…` paths; the DocC step (static hosting under `/my-app/api/`); the destination step →
  `platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0`, `platform=watchOS Simulator,name=Apple Watch Series 12
  (46mm),OS=27.0`, and for visionOS an empty destination + the "unverified" warning (exit 0), or exit 1 when the
  runtime is marked required; `build-for-testing` on `generic/platform=<P> Simulator` + `test-without-building` —
  `MyApp-iOS` **11/11** and `MyApp-watchOS` **11/11** (each incl. its XCUITest; the watch tests run only under the
  watchOS scheme), `MyApp-visionOS` build-for-testing succeeded (tests unverified: no visionOS runtime installed
  here), UIKit `MyApp-iOS` with `-skip-testing:MyAppUITests-iOS` **17/17** with only the `CoreTests` and
  `MyAppTests-iOS` bundles run; the release `version` check (`v1.0.0`/`1.0.0` pass, `v1.0.1` fails); unsigned archives
  — `MyApp-iOS.xcarchive` contains `MyApp.app/Watch/MyApp.app` (`com.example.myapp.watchkitapp`, `WKApplication`,
  companion id) and `MyApp-visionOS.xcarchive` is an `xros` 27.0 build; both export plists rendered and `plutil
  -lint`-clean with `teamID` replaced.
- **Not verified**: the Linux `lint` container (Docker's VM disk filled while pulling `swift:6.4-noble`; nothing was
  pruned) — the same SwiftLint version and `swift format` passed on macOS; signed archives, `-exportArchive` uploads,
  the Developer ID export/notarization, `fastlane ios beta|release` (Fastlane isn't installed locally) and Xcode
  26.6 on `macos-26` (the scaffold targets Xcode 27.1).
- XcodeGen also generates a `Core` scheme for the local package — never pick it as an app leg.

### Known quirks / verification notes (September 2026, against Xcode 27.0 / Swift 6.4, no `brew` installed)

- **Verified for real** in `$TMPDIR/iru-verify/swift/workflows/library-copy` (a fresh `cp -R` of `iru-setup-swift-library`'s own
  `$TMPDIR/iru-verify/swift/library` scaffold, package `ExampleLibrary`, `.build`/`Package.resolved`/
  `coverage.lcov` removed first since the copied `.build` carried stale absolute-path symlinks back to the
  original directory):
  - `swift build` — succeeds, including re-resolving `swift-docc-plugin` from the local package cache.
  - `swift test --enable-code-coverage --parallel` — succeeds, `1 test in 0 suites passed` (the XCTest-framed
    test from `iru-setup-swift-library`'s own template).
  - The `Export lcov coverage` step's `find`-based binary lookup + `xcrun llvm-cov export -format=lcov
    --ignore-filename-regex='(Tests/|DerivedSources)'` — succeeds, produces the exact `SF:`/`FN:`/`DA:`/
    `end_of_record` shape `iru-swift-coverage` already documents.
  - `swift format lint --strict --recursive Sources Tests` — exits `0` against the scaffold's own
    `.swift-format` (written by `iru-setup-swift-library`).
  - `python3 .github/scripts/lcov_to_sonar_generic.py coverage.lcov` — produces valid, well-formed
    `sonarqube-generic-coverage.xml` (confirmed with `xml.dom.minidom.parse`), with **project-relative** `path`
    attributes (`Sources/ExampleLibrary/ExampleLibrary.swift`), not the absolute paths `llvm-cov` itself emits —
    the `os.path.relpath` rewrite in the script is required for this, not cosmetic; a first draft without it
    produced Sonar-unusable absolute paths.
  - The DocC step: `swift package --allow-writing-to-directory docc-output generate-documentation --target
    ExampleLibrary --transform-for-static-hosting --hosting-base-path example-library --output-path
    docc-output` — **fails** with an `NSCocoaErrorDomain` "couldn't be moved" (or, with the parent pre-created
    but not the leaf, an `Operation not permitted`) error unless the **parent** of `--output-path`'s directory
    already exists before the command runs (`mkdir -p docc-output` first) — confirmed by first running without
    any pre-created directory (fails), then with only `docs/build/site` pre-created while targeting
    `docs/build/site/api` (still fails, permission error), then with `docc-output` itself pre-created (succeeds,
    produces `index.html`/`documentation/`/`data/`/etc.). This refines `iru-setup-swift-library`'s own Step 10
    note that `--output-path` alone is enough — in this skill's own CI context (a script computing the path
    fresh, not an interactive shell that may have already `cd`'d through intermediate directories) the `mkdir
    -p` is not optional.
- **All ten rendered `build.yml`/`release.yml`/`sync.yml`/`security.yml`/`dependabot.yml` files (both `flavor`
  variants, `sonar`/`distribution`/`signing`/`branching`/`release-please` combinations exercised at least once
  each) YAML-validated clean** with `ruby -ryaml -e 'YAML.load_file(ARGV[0])'` — Ruby ships with macOS, confirmed
  present in this environment, no PyYAML fallback needed.
- **A real bug was found and fixed in `sync_versions.py` while verifying it end-to-end, not just
  `py_compile`-checked**: an earlier draft called `update_version_txt(next_version)` and
  `update_readme_package_snippets(next_version)` — writing the **next**, not-yet-released version into
  `version.txt` and the library's README `.package(url:, from:/exact:)` snippet. Running the script against a
  sample fixture (`version.txt: 1.0.0`, a README snippet at `1.0.0`, `RELEASE_VERSION=1.1.0`,
  `NEXT_VERSION=1.1.1`) exposed the problem concretely: both files ended up advertising `1.1.1`, a version with
  **no git tag at all** — a consumer copy-pasting that snippet would get an unresolvable `swift build` error, and
  `version.txt`'s own documented purpose (`iru-swift-bump-version`'s Step 3: "a plain one-line file holding the
  **current released** version") was violated outright. Fixed to call both with `release_version` instead;
  re-run against the same fixture correctly produced `1.1.0` in both files (verified: `version.txt` → `1.1.0`,
  README's `from:` → `"1.1.0"`), while `project.yml`/`Project.swift`'s `MARKETING_VERSION` and `Project.swift`'s/
  `project.yml`'s `CURRENT_PROJECT_VERSION` correctly still bump to `next_version`/`+1` (`1.1.1`/`2`) — that half
  *is* a genuine "next development version" concept, unlike the library's tag-bound files. `docs/antora.yml`'s
  `version:` field and `CHANGELOG.md`'s `[Unreleased]` reopening were both already correct in the first draft
  (`release_version` and no-argument respectively) and needed no fix. The version embedded in this SKILL.md
  reflects the fixed, re-verified form.
- **`gem install --user-install slather` genuinely fails in this environment** — not merely "unreachable", a
  real, reproducible failure: this machine's system Ruby is `2.6.10` (macOS's bundled interpreter; no `brew`,
  `rbenv`, `rvm`, or `asdf` present to install a newer one), and `slather`'s own dependency chain (`securerandom
  >= 0.3`) requires Ruby `>= 3.1.0`. Confirmed via `gem env`: only one Ruby is on this machine's `PATH` at all.
  Separately from the install failure, slather's own design fundamentally requires an `.xcodeproj`/
  `.xcworkspace` to locate build settings — it has no path for a bare SwiftPM package with neither, which
  `iru-setup-swift-library`'s scaffold never produces. **Both problems independently rule out slather for the
  library flavor** — this skill's `lcov_to_sonar_generic.py` (verified above) is the actual default for exactly
  the reason anticipated when this skill was designed, not a fallback of convenience.
- **SonarSource's `xccov-to-sonarqube-generic.sh` was located and read in full** (at
  `SonarSource/sonar-scanning-examples/swift-coverage/swift-coverage-example/xccov-to-sonarqube-generic.sh` —
  not the more guessable `swift-coverage/xccov-to-sonarqube-generic.sh` path) — confirmed by reading its actual
  source that it takes exactly one positional argument, an `.xcresult` directory path, and shells out to `xcrun
  xccov view --archive`; it **cannot** consume an lcov file at all. This confirms the flavor split in Steps 4/5:
  the library flavor's `swift test`-based pipeline never produces an `.xcresult`, so this script only fits the
  app flavor's `xcodebuild test`-based one. Not independently re-run end-to-end against a real `.xcresult` here
  (that needs an actual generated `.xcodeproj`, which `xcodegen`/`tuist` being uninstallable in this environment
  — no `brew` — rules out, the same limitation `iru-setup-apple-app`'s own verification records); its logic was
  confirmed by reading the script's source, not by executing it.
- **GitHub's hosted macOS runner image labels were confirmed live**, not assumed:
  `gh api repos/actions/runner-images/contents/images/macos --jq '.[].name'` returned
  `macos-14-Readme.md`/`macos-14-arm64-Readme.md`, `macos-15-Readme.md`/`macos-15-arm64-Readme.md`,
  `macos-26-Readme.md`/`macos-26-arm64-Readme.md`, and `xcode-27-arm64-Readme.md` (no non-arm64 `xcode-27`
  variant published yet). `macos-26`'s `### Xcode` table lists `26.6 (default)` down to `26.0.1`; `xcode-27`'s
  lists only `27.0 (beta) (default)`. SwiftLint 0.65.1 appears under `### Linters` only in the x64
  `macos-26-Readme.md`, i.e. the `macos-26-intel` image (re-checked 2026-10-16, Step 0's correction) — not on the
  arm64 `macos-26` nor on `xcode-27`. This confirms Step 0's runner-choice guidance (default `macos-26` for GA
  stability, not for a preinstalled SwiftLint) and Step 4's `command -v swiftlint >/dev/null ||` guard (required
  unconditionally on both labels).
- **`actions/dependency-review-action` and `google/osv-scanner-action`'s reusable workflow still have no
  floating major tag** (re-confirmed via `git ls-remote --tags` returning zero matches for a bare `v5`/`v2`),
  same finding the Android sibling's own Step 13 already recorded for the Java/Kotlin ecosystem — this is an
  action-level fact, not a Swift-specific one, but re-verified here rather than assumed.
- **`github/codeql-action`'s `/releases/latest` endpoint returns the unrelated `codeql-bundle-v2.27.0` tag, not
  a CLI version** — re-confirmed here; `git ls-remote --tags https://github.com/github/codeql-action v4`
  resolves the real floating major used in Step 9's template.
- **The Linux Swift Docker toolchain lags the Apple-toolchain-bundled Swift version** — this machine reports
  Swift `6.4` (`swift --version`, bundled with Xcode 27.0), but Docker Hub's `library/swift` repository's newest
  `6.x` tags top out at `6.3.3`/`6.3-noble` (confirmed via the Docker Hub v2 tags API) — no `6.4` tag exists yet.
  Step 2's table records `6.3-noble` as the fallback specifically because of this gap, not as a guess; re-check
  at generation time rather than assuming the two numbers track each other release-for-release.
- **Never executed, and never a blocker** — recorded here explicitly rather than silently assumed correct:
  `swiftlint` itself (no `brew`, matching every other Swift-stack skill in this catalog's own recorded
  limitation), `xcbeautify`/`xcodegen`/`tuist` and therefore the entire app-flavor `build.yml`/`release.yml`
  pipeline beyond its YAML/placeholder structure, the `SonarSource/sonarqube-scan-action` step itself (needs a
  real `SONAR_TOKEN` and Sonar project), `googleapis/release-please-action`, `fastlane match`/`pilot`,
  `apple-actions/upload-testflight-build`, `xcrun notarytool submit`/`stapler staple` against a real signed
  archive, and the `codeql`/`osv-scanner`/`gitleaks`/`dependency-review` jobs' actual scan behavior (their
  `uses:` refs were confirmed to resolve, per point 3 of Step 13's own list above, but the scans themselves were
  never run against this repository).

## Step 14 — Report

Summarize what happened: the mode announced in Step 1 (from scratch vs updating an existing pipeline), and for
each file in Step 12's matrix the action actually taken — **created / updated / replaced / kept as is** (or that
the user chose Stop) — listing, for every file kept as is, each concrete mismatch with this run's choices (e.g.
still runs UI tests after `ci-tests: unit`, still deploys to Pages after `pages: no`, still runs Sonar after
`sonar: none`); any open gaps noted along the way (missing `docs/antora.yml`, a `Packages/Core`
with no `swift-docc-plugin` dependency for the app flavor's DocC step, README/version wording that didn't match
`sync_versions.py`'s default regexes, an `APPLE_TEAM_ID` secret the user still needs to add).

State explicitly what was wired versus omitted, and why:

- **`flavor`**: `library` or `app` — which pipeline shape (`ubuntu-latest` matrix leg + lcov-to-generic-XML vs.
  Linux lint + Core coverage bar + one `apps` matrix leg per platform scheme + archive/sign/upload) was generated.
- **Platforms** (app only): the matrix legs written (`<AppName>-iOS`/`-watchOS`/`-visionOS`/`-macOS`), that watchOS
  has its own leg even when embedded in the iOS app, and — from Step 5's runner-image table re-checked for the chosen
  `<runner>` — whether the visionOS leg will run its tests there or only build for testing ("unverified (no visionOS
  simulator runtime installed)"); which release jobs exist (`app-store` legs, `fastlane-ios`, `macos-developer-id`)
  and that no job requires Fastlane unless `fastlane/Fastfile` + `signing: fastlane-match` chose the `fastlane-ios`
  job.
- **`runner`**: `macos-26` or `xcode-27` — restate the GA-vs-preview tradeoff and whether the `brew install
  swiftlint` guard actually fires for this choice.
- **`branching`**: `gitflow` (`sync.yml` included) or `main-only` (state this omission plainly).
- **`sonar`**: `cloud`/`self-hosted`/`none` — which coverage-conversion step/file was generated (or omitted).
- **`distribution`**/**`signing`** (app only): which `release.yml` steps and secrets are actually needed as a
  result.
- **`release-please`** (library only): whether the optional job was included.
- **`visibility`**: `public`/`private`/`internal`, and whether it came from `args`, `gh repo view`, or the user —
  restate the Actions-minutes consequence (free on public; for private/internal, the plan's included minutes with
  every macOS minute counting roughly 10×).
- **`ci-tests`**: `unit` (app: `-skip-testing:` per UI-test target; library: any integration-test target skipped,
  or "no effect — no integration-test target") or `full` (unit + XCUITest on every selected destination); note
  that Sonar coverage then reflects only the tests CI ran.
- **`pages`**: `yes` (deployed to GitHub Pages; for public repos defaulted without asking) or `no` (docs built
  and uploaded as the `docs-site` workflow artifact, no Pages deploy, no github.io site to link).
- **Security block**: which of the four `security-*` jobs were included versus omitted, and confirm
  `.github/dependabot.yml` was written regardless.

Then give the required-secrets/environments table — list only the rows that apply to what was actually
generated:

| Secret | Purpose | Only needed when |
|---|---|---|
| `SONAR_TOKEN` | Auth token for the SonarQube/SonarCloud scan | `sonar` is `cloud` or `self-hosted` |
| `MATCH_GIT_URL` / `MATCH_PASSWORD` | `fastlane match`'s certs-repo URL and its encryption passphrase | `flavor: app`, `signing: fastlane-match` |
| `MATCH_GIT_BASIC_AUTHORIZATION` | base64 of `<git-user>:<personal-access-token>` — how `match` clones a private certs repository over HTTPS on the runner (an SSH deploy key via `webfactory/ssh-agent` is the alternative for a `git@…` URL) | `flavor: app`, `signing: fastlane-match` |
| `APP_STORE_CONNECT_API_KEY_ID` / `_ISSUER_ID` / `_KEY_BASE64` | App Store Connect API key (`_KEY_BASE64` = base64 of `AuthKey_<id>.p8`, the same secrets `iru-setup-apple-app`'s Fastfile reads) — `-allowProvisioningUpdates` + `-exportArchive` upload (Admin or App Manager role), `notarytool`, and fastlane's `APP_STORE_CONNECT_API_KEY_PATH` JSON on the Fastlane path | `flavor: app`, `distribution` isn't `none` — **both** signing modes, since `match` certificates never authenticate an upload or a notarization |
| `APPLE_TEAM_ID` | Apple Developer Team ID — `DEVELOPMENT_TEAM` for the archives and the export plists' `teamID` (`plutil -replace` at run time) | `flavor: app`, `distribution` isn't `none` (plain `xcodebuild` jobs) |
| `DEVELOPER_ID_CERTIFICATE_P12_BASE64` / `DEVELOPER_ID_CERTIFICATE_PASSWORD` | Exported Developer ID Application certificate + key (`.p12`, base64) for the macOS `developer-id` archive/export (automatic signing can't create one — only the Account Holder can) | `flavor: app`, `macos` selected, `distribution` isn't `none` |
| `GITHUB_TOKEN` | `sync.yml`'s `gh` calls, `security.yml`'s CodeQL/gitleaks jobs | Automatic — no setup needed |

`sync.yml` also needs the repository setting under Settings → Actions → General → "Workflow permissions" set to
allow GitHub Actions to create pull requests. When `pages: yes`, the Pages deploy job needs Settings → Pages →
"Build and deployment" → Source set to **GitHub Actions** (and, for a private/internal repository, a GitHub Pro,
Team, or Enterprise Cloud plan — the published site is public unless Enterprise Cloud private access control is
used). Call out whichever apply explicitly.

**Warn explicitly:**

- **The user must review every generated (or updated) workflow file before relying on it.** Branch names, the
  Apple Developer Team ID, and the signing/distribution setup were inferred or asked for directly and may need
  correction — and because this pipeline handles signing keys and can publish to TestFlight/App Store, a bad
  assumption here has real consequences. Recommend a dry run via `workflow_dispatch` (supplying an existing
  tag in its `tag` input — both `release.yml` flavors read `github.event.release.tag_name || inputs.tag`) before
  trusting it on a real release.
- Every secret in the table above, and the matching SonarCloud project / Apple Developer Program membership /
  `fastlane match` certs repository / App Store Connect API key, must already exist before CI can pass — this
  skill only writes the workflow YAML, it never creates any of those accounts or credentials itself.
- Which toolchain versions (Step 2) came from a live lookup versus this skill's recorded September 2026
  fallback, and specifically that `actions/dependency-review-action`/`google/osv-scanner-action`'s reusable
  workflow are pinned to an exact tag rather than a floating major, and that the Linux Swift Docker tag can lag
  the Apple-toolchain Swift version — re-check all three at the next regeneration.
- **`gem install --user-install slather` does not work in this catalog's own verification environment** (Ruby
  too old) and slather's own design doesn't apply to a bare SwiftPM package regardless of Ruby version — the
  library flavor's `lcov_to_sonar_generic.py` conversion is the one this skill actually generates and verified;
  don't substitute slather in without first confirming both problems don't apply to the target repository/CI
  runner.
- **Unverified locally, and never a blocker** (Step 13): for the library flavor SwiftLint/`xcodegen`/`tuist`
  themselves (no `brew` in that run's environment); for the app flavor everything that needs GitHub's runners or
  Apple accounts — the Linux `lint` container, `setup-xcode`, Homebrew `xcodegen`, `tuist`, signed archives,
  `-exportArchive` uploads, Developer ID export, notarization/stapling, the Fastlane lane, `gh release upload`, and
  visionOS **test** runs (no local visionOS runtime; GitHub's arm64 images have one); the actual SonarQube scan,
  `release-please`, and the security jobs' real scan behavior (only their `uses:` refs were confirmed to resolve).
