---
name: iru-setup-kmp-github-workflows
description: "Invoke as `/iru-setup-kmp-github-workflows`, optionally with `args` (key: value lines such as `branching`, `ios-ci`, `ci-tests`, `distribution`, `pages`, `sonar`, `on-existing`); normally called by `iru-setup-kmp-repository`. Creates build, release, sync and security workflows plus Dependabot for a Kotlin Multiplatform app."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(python3 *) Bash(ruby *) Bash(actionlint *) Bash(./gradlew *) Bash(xcrun *)
---

# Setup KMP GitHub Workflows

Scaffold (or update) the CI/CD pipeline for a Kotlin Multiplatform app built by `iru-setup-kmp-app`. It combines the
two sibling pipelines this catalog already has — `iru-setup-android-github-workflows` (Gradle, Android Lint SARIF,
signed bundle → Google Play / Firebase, `sync.yml`) and `iru-setup-swift-github-workflows` (macOS runner choice,
XcodeGen, `xcodebuild`, App Store Connect API key → TestFlight, Swift CodeQL on macOS) — around one cost rule: **the
JVM/Android half runs on cheap Linux runners on every change; the iOS half needs macOS, whose minutes cost roughly 10×
Linux on a private repository, so when it runs is an explicit choice (`ios-ci`).**

Files (templates in `reference/workflow-templates.md`, conditional markers explained there):

| File | Written when |
|---|---|
| `.github/workflows/build.yml` | always |
| `.github/workflows/release.yml` | `distribution` ≠ `none` |
| `.github/workflows/sync.yml` + `.github/scripts/sync_versions.py` | `branching: gitflow` |
| `.github/workflows/security.yml` | always (jobs gated by `security-*`) |
| `.github/dependabot.yml` | always |

The templates assume `iru-setup-kmp-app`'s layout and task names (its Step 8 contract table): `shared/` on the AGP
KMP library plugin, `androidApp/`, `iosApp/project.yml` (XcodeGen, scheme `iosApp`), root Kover aggregation,
`:shared:lint` via `com.android.lint`, Dokka on `:shared`. If `shared/build.gradle.kts` or `iosApp/project.yml` is
missing (e.g. the wizard's single `composeApp/` layout, or a committed `.xcodeproj`), warn, ask whether to stop or
continue, and when continuing, read the real task names from `./gradlew tasks --all` and adapt every Gradle/Xcode
command (drop `xcodegen generate` for a committed project), listing each adaptation in the report.

## Step 0 — Resolve inputs

Parse `args` (`key: value` lines) first, e.g.:

```
branching: gitflow
integration-branch: develop
stable-branch: main
java-version: 17
runner: macos-26
ios-ci: all
ci-tests: full
open-source: yes
visibility: public
pages: yes
distribution: store
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_my-app
sonar-host-url: https://sonarcloud.io
mode: new
```

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `branching` | `gitflow` / `main-only` | `gitflow` |
| `integration-branch` / `stable-branch` | branch names | `develop` / `main` (confirm against `git branch -a`) |
| `java-version` | `actions/setup-java` JDK | `17` — the scaffold's JVM target; JDK 17 and 21 both verified |
| `runner` | macOS runner label | `macos-26` (GA image; `xcode-27` is the newer-Xcode preview image — same trade-off `iru-setup-swift-github-workflows` documents; look labels up at run time) |
| `xcode-version` | `maxim-lobanov/setup-xcode` | the chosen runner image's `(default)` Xcode (`https://raw.githubusercontent.com/actions/runner-images/main/images/macos/<runner>-arm64-Readme.md`), fallback `26.6` for `macos-26` |
| `ios-ci` | `all` / `stable` / `none` — when the macOS `ios` job runs | see Standalone resolution |
| `ci-tests` | `unit` / `full` — `unit`: K/N simulator tests + `xcodebuild build`; `full`: also `xcodebuild test` of `iosAppTests` | public `full`; private/internal ask, `unit` recommended. Irrelevant with `ios-ci: none` |
| `pages` | `yes` / `no` | public `yes` without asking; private/internal ask, `no` recommended |
| `distribution` | `none` / `internal` / `store` | `store` when `open-source: yes`, otherwise ask, `internal` recommended |
| `play-track` | Google Play track (`distribution: store`) | `production` (the Android sibling's choice); suggest `internal` for a first rollout |
| `sonar` (+ coordinates) | `cloud` / `self-hosted` / `none` | from the root `build.gradle.kts` `sonar {}` block when present (host `https://sonarcloud.io` → `cloud`); else see Standalone resolution |
| `open-source` | `yes` / `no` | `gh repo view --json isPrivate` if unresolved |
| `visibility` | `public` / `private` / `internal` (`internal` behaves like `private`) | see Standalone resolution |
| `mode` | `new` / `existing` | informational only — every existing file is still asked about |
| `on-existing` | `ask` / `keep` / `update` / `replace` | `ask` |
| `security-dependency-review` / `security-codeql` / `security-osv` / `security-gitleaks` | gate one `security.yml` job each | `yes` |
| `security-codeql-swift` | the macOS Swift CodeQL job | `yes` for public (free), `no` for private/internal (macOS cost) unless the user opts in |
| `license` / `developer-*` / `organization-url` / `publish` / `ui` / identity keys | shared vocabulary | accepted and ignored — named in the report |

### Standalone resolution of `visibility`, `sonar`, `ios-ci`, `ci-tests`, `pages`

In this order, skipping any `args` supplied. Quote cost facts "per GitHub's current pricing" (verified October 2026
against docs.github.com) and tell the user to double-check their plan: standard GitHub-hosted runners are **free
for public repositories**; private/internal ones consume the account's included minutes — **2,000/month on GitHub
Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud** — then are billed per minute (Linux 2-core $0.006, **macOS
$0.062, ~10×**). A KMP `ios` job (Kotlin/Native compile + framework link + simulator boot) typically takes 15–30
macOS minutes, i.e. 150–300 minutes of a private budget per run.

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercase), else ask Public/Private
   (Public recommended when open source). Open source + private is contradictory — warn and ask.
2. **`sonar`** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" `cloud`
   recommended only for public + open source; otherwise `none` recommended, `self-hosted` offered (SonarCloud is free
   only for public/open-source projects; Community Build is free but self-run). Skipped when a `sonar {}` block
   already answers it. With `sonar` ≠ `none` but no `sonar {}` block in the root `build.gradle.kts`, warn: the
   `./gradlew sonar` step needs `iru-setup-kmp-app`'s block (or a `sonar-project.properties`) first.
3. **`ios-ci`** — **Every push and PR** (`all`, recommended for public) / **Stable branch + manual runs only**
   (`stable`, recommended for private/internal — the Linux job still checks every change) / **Never** (`none` — iOS
   verified locally only; say that iOS breakage then goes unnoticed by CI).
4. **`ci-tests`** (only when `ios-ci` ≠ `none`) — **Unit tests only** (`unit`) / **Unit + app tests** (`full`), with
   the same recommendation split and the note that `full` adds an app launch on the simulator per run.
5. **`pages`** — public: `yes`. Private/internal: **No (Recommended)** ("docs still built and uploaded as the
   `docs-site` artifact") / **Yes** ("requires Pro/Team/Enterprise Cloud; the site is PUBLIC unless Enterprise Cloud
   private Pages").

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 1 — Survey the repository

- Module layout and task names: `settings.gradle.kts` includes, `shared/build.gradle.kts` (plugins: KMP library
  plugin, `com.android.lint`, Kover, detekt, ktlint, Dokka), `androidApp/build.gradle.kts` (the four `SIGNING_*`
  env reads — the release workflow exports exactly those names), root `build.gradle.kts` (`kover(...)` aggregation,
  `sonar {}`), `iosApp/project.yml` (scheme name), `gradle/libs.versions.toml`.
- `applicationId` from `androidApp/build.gradle.kts` (for `r0adkll/upload-google-play`'s `packageName`).
- `docs/antora.yml`/`docs/antora-playbook.yml` — missing → recommend running `iru-setup-antora` first (the docs job
  has nothing to build otherwise).
- `docs/package-lock.json` — the docs job runs `npm ci` with the setup-node npm cache keyed on it, so it fails at
  `actions/setup-node` without a committed lockfile. `docs/` present without one (a site from the old per-package
  install) → recommend `/iru-update-antora` first; no `docs/` → `iru-setup-antora`, which commits it.
- Branch names (`git branch -a`).
- Existing files: `.github/workflows/build.yml`, `release.yml`, `sync.yml`, `security.yml`,
  `.github/scripts/sync_versions.py`, `.github/dependabot.yml`; also note sibling-named files (`develop.yml`,
  `main.yml`, `publish.yml`) that would duplicate this pipeline.

### Announce the mode, then warn and ask per existing file

- None exist: "**Creating the CI/CD pipeline from scratch**." Every file is *created*.
- Any exists: "**Updating an existing CI/CD pipeline** — found: <paths>." Unless `on-existing` is `keep`/`update`/
  `replace` (apply it to every file, still printing the warning), ask with `AskUserQuestion`, one question per file
  (`sync.yml` + `sync_versions.py` count as one; batch up to 4), naming each concrete mismatch (e.g. "`build.yml`
  runs the macOS job on every push but you chose `ios-ci: stable`", "`build.yml` deploys to Pages but you chose
  `pages: no`", "`build.yml` still runs `./gradlew sonar` but `sonar` is `none`", "an Android-sibling `develop.yml`
  exists — it would run the same Gradle checks twice"):
  - **Update (Recommended)** — merge using the templates as reference; keep every step/job/entry this skill doesn't
    own (notifications, extra matrix legs, other Dependabot ecosystems); you review a diff first. Customizations
    survive, but a customized part this skill owns may be rewritten.
  - **Keep as is** — untouched; it may disagree with this run's choices — the report lists each mismatch.
    Recommended only for a `.github/dependabot.yml` that already has `gradle` and `github-actions` entries.
  - **Replace** — regenerate from the template; every customization is lost (git history only). Never the default.
  - **Stop** (first question only) — change nothing; report the existing files and end.

## Step 2 — Look up action versions at run time

Resolve every `uses:` major with `git ls-remote --tags https://github.com/<owner>/<repo>` (the REST `/tags`
listing can miss floating majors — the Android sibling's finding) and fall back to this table, confirmed on
2026-10-15:

| Action | Version | Floating major? |
|---|---|---|
| `actions/checkout` | `v7` | yes |
| `actions/setup-java` | `v6` | yes |
| `gradle/actions/setup-gradle` | `v6` | yes |
| `actions/cache` | `v6` | yes |
| `actions/setup-node` | `v7` | yes |
| `actions/setup-python` | `v7` | yes |
| `actions/upload-artifact` | `v7` | yes |
| `actions/upload-pages-artifact` / `actions/deploy-pages` | `v5` / `v5` | yes |
| `maxim-lobanov/setup-xcode` | `v1` | yes |
| `EnricoMi/publish-unit-test-result-action` (and its `/macos` variant) | `v2` | yes |
| `github/codeql-action` (`init`/`analyze`/`upload-sarif`) | `v4` | yes (don't use `/releases/latest` — it returns a `codeql-bundle-*` tag) |
| `r0adkll/upload-google-play` | `v1` | yes |
| `wzieba/Firebase-Distribution-Github-Action` | `v1` | yes |
| `apple-actions/upload-testflight-build` | `v5` | yes (inputs `app-path`, `issuer-id`, `api-key-id`, `api-private-key`) |
| `gitleaks/gitleaks-action` | `v3` | yes |
| `actions/dependency-review-action` | **`v5.0.0`** | **no** — pin the exact tag |
| `google/osv-scanner-action` reusable workflow | **`v2.7.0`** | **no** — pin the exact tag |
| `xcodegen` / `swiftlint` (Homebrew, installed only when the image lacks them) | `2.46.0` / `0.65.1` | n/a |

## Step 3 — Render the templates

Read `reference/workflow-templates.md`, resolve the markers for this run, and substitute: `<integration-branch>`,
`<stable-branch>`, `<java-version>`, `<runner>`, `<xcode-version>`, `<application-id>`, `<play-track>`. Notes that
matter when adapting them:

- **`android` job** — one Gradle invocation per concern (`--continue` static analysis; tests + `koverXmlReport` +
  `koverVerify`; `assembleDebug`) so a lint failure doesn't hide test results. Three SARIF uploads, each with its own
  `category` (GitHub rejects two uploads with the same category in one run). Sonar runs `./gradlew sonar` (root
  project; the scaffold's `sonar {}` block points at Kover's `build/reports/kover/report.xml` and each module's
  JUnit/Lint/detekt reports, which the preceding steps produced) and is skipped for fork PRs, which never get
  `SONAR_TOKEN`.
- **`ios` job** — caches `~/.konan` (the Kotlin/Native toolchain lives outside Gradle's cache; without it every run
  re-downloads ~1 GB). Picks the first iPhone on the **newest** installed iOS runtime by UDID — a bare
  `name=` destination resolves against `OS:latest` and fails when that device only exists on an older runtime
  (verified locally). `xcodebuild` runs `CODE_SIGNING_ALLOWED=NO` for simulator builds; the project's pre-build phase
  (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`) builds the `Shared` framework inside the Xcode build.
  `ios-ci: stable` adds the job-level `if:`; `ios-ci: none` drops the whole job.
- **`docs` job** — runs on every event (it validates the docs); only a push to the stable branch uploads the Pages
  artifact and triggers `deploy-docs`. Dokka output `shared/build/dokka/html` is merged under the Antora site's
  `api/`, the same layout the Android stack uses.
- **`release.yml`** — `workflow_dispatch` takes a `tag` input so a dry run checks out a real tag. Android: decodes
  `ANDROID_KEYSTORE_BASE64` to `androidApp/release.jks` (matched by `*.jks` in `iru-setup-kmp-gitignore`) and exports
  `SIGNING_STORE_FILE=release.jks` (resolved relative to the `androidApp` module), `SIGNING_STORE_PASSWORD`,
  `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` — the exact names `androidApp/build.gradle.kts` reads. iOS: one App
  Store Connect API key drives automatic signing (`-allowProvisioningUpdates -authenticationKeyPath …`), the export
  (`method: app-store-connect`) and the TestFlight upload; `TEAM_ID` from the `APPLE_TEAM_ID` secret overrides
  `Config.xcconfig`. Both `internal` and `store` upload iOS builds to TestFlight — submitting a TestFlight build for
  App Store review stays a deliberate manual step in App Store Connect (state it in the report). Only the API-key
  signing path is generated; for a team that shares certificates via `fastlane match`, adapt the iOS job from
  `iru-setup-swift-github-workflows`' `release.yml` (its `Fetch distribution signing certificates` step).
- **`sync.yml`** — branch `sync_<released version>` (release `1.0.1` → `sync_1.0.1`); if the merge conflicts it opens the PR from the release commit titled `Sync <release> (merge conflicts to resolve)` without bumping, so a conflicting sync never lowers the integration branch's development version. Patch bump `x.y.z` → `x.y.(z+1)-SNAPSHOT` into `gradle.properties` `VERSION_NAME`; `VERSION_CODE`
  and `iosApp/Configuration/Config.xcconfig` are never touched (release-only values, set by `iru-kmp-bump-version`
  on the release branch — the same rule that skill applies). README rows / `docs/antora.yml` follow the Android
  sibling's script.
- **`security.yml`** — CodeQL `java-kotlin` uses `build-mode: manual` with `--no-daemon --no-build-cache
  --no-configuration-cache` so the extractor sees a real `kotlinc` run of `:shared:compileAndroidMain` and
  `:androidApp:compileDebugKotlin` (an up-to-date or cached task extracts nothing). CodeQL has **no Kotlin/Native
  extractor**: `iosMain` is never analyzed, only the JVM compilations of `commonMain`/`androidMain`/`androidApp`.
  CodeQL's Kotlin extractor also lags new Kotlin releases — if the job fails with an unsupported-Kotlin-version
  error, that is the cause (check the CodeQL changelog), not the build. The Swift job needs macOS and a full Xcode
  build (which links the Kotlin/Native framework first) — gated by `security-codeql-swift`. Its `generic/platform=iOS
  Simulator` build passes `ARCHS=arm64`: without it Xcode also requests `x86_64` and the pre-build phase fails with
  "Xcode Requested Architecture Not Configured in Gradle … ios_x64" (verified locally; the scaffold declares only
  `iosArm64`/`iosSimulatorArm64`).

## Step 4 — Write the files

Apply Step 1's per-file decision (create / Update after a diff / Keep as is / Replace). `sync.yml` and
`sync_versions.py` are always written together (gitflow only); `release.yml` only when `distribution` ≠ `none`;
`security.yml` and `.github/dependabot.yml` always. If an existing `.github/dependabot.yml` has other ecosystems
(`npm` for `docs/`, …), keep them and only add/update `gradle` and `github-actions`. A `swift` Dependabot entry is
not added — the scaffold's iOS app has no SwiftPM dependencies; add one if the project later gains a `Package.swift`.

## Step 5 — Verify

In a throwaway copy (or `$TMPDIR`), never in a way that commits anything:

1. `actionlint` (with `shellcheck` on `PATH`) over every written workflow; if `actionlint` isn't installed, fall
   back to `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>` (a PyYAML parse turns the `on:` key into `True` — a
   false flag, never quote `on:`).
2. `python3 -m py_compile .github/scripts/sync_versions.py`.
3. When the scaffold is present, run the `android` job's Gradle commands locally through `iru-gate-runner` to confirm
   the task names and report paths the workflow references exist.

Everything that needs GitHub's runners or secrets stays **unverified locally** — say so in the report.

## Step 6 — Report

Summarize: the mode (from scratch / updating), and per file the action taken — **created / updated / replaced /
kept as is** (or Stop) — with each mismatch of a kept file, and any adaptation made for a non-standard layout. State
what was wired and why: `branching` (no `sync.yml` for `main-only` — say so plainly), `visibility` (and where it came
from), `ios-ci` (when the macOS job runs, restating the ~10× macOS cost for private repositories, or that iOS is
unchecked by CI with `none`), `ci-tests`, `pages`, `sonar` (and whether the root `sonar {}` block exists),
`distribution` (which release jobs exist; that TestFlight → App Store submission is manual), the security jobs
included/omitted, and any ignored `args` keys. Then the required-secrets table — only the rows that apply:

| Secret | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | Sonar scan auth | `sonar` ≠ `none` |
| `ANDROID_KEYSTORE_BASE64` | release keystore, decoded to `androidApp/release.jks` | `distribution` ≠ `none` |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | exported as `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` | `distribution` ≠ `none` |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service account for `r0adkll/upload-google-play` | `distribution: store` |
| `FIREBASE_APP_ID` / `FIREBASE_SERVICE_CREDENTIALS` | Firebase App Distribution upload | `distribution: internal` |
| `APP_STORE_CONNECT_API_KEY_ID` / `APP_STORE_CONNECT_API_KEY_ISSUER_ID` / `APP_STORE_CONNECT_API_KEY_BASE64` | App Store Connect API key: automatic signing, export, TestFlight upload | `distribution` ≠ `none` |
| `APPLE_TEAM_ID` | Apple Developer Team ID (overrides `Config.xcconfig`'s `TEAM_ID`) | `distribution` ≠ `none` |
| `GITHUB_TOKEN` | `sync.yml`'s `gh` calls, CodeQL/gitleaks | automatic |

Repository settings to call out: Settings → Actions → General → "Workflow permissions" must allow GitHub Actions to
create pull requests (gitflow `sync.yml`); with `pages: yes`, Settings → Pages → Source = **GitHub Actions** (and a
Pro/Team/Enterprise Cloud plan for a private repository — the site is public unless Enterprise Cloud private
Pages); a Google Play app record must exist (the first AAB is usually uploaded by hand) and the bundle id must
exist in App Store Connect before the first TestFlight upload.

### Known quirks / verification notes (2026-10-15)

- **Rendered and linted for real** in `$TMPDIR/iru-verify/kmp/iru-setup-kmp-github-workflows/` (outside this
  repository): three combinations — (1) gitflow + `sonar` + `ios-ci: all` + `ci-tests: full` + `pages: yes` +
  `distribution: store` + every security job incl. Swift CodeQL; (2) main-only + `ios-ci: stable` +
  `ci-tests: unit` + `pages: no` + `distribution: internal`; (3) gitflow + `ios-ci: none` + `pages: no` +
  `distribution: none` + `sonar: none` — each passed `actionlint` 1.7.12 with shellcheck (exit 0) and a Ruby YAML
  parse. `actionlint` was downloaded into a temp directory from its GitHub release, not installed.
- `sync_versions.py` was run against a fixture (`VERSION_NAME=1.1.0`, `VERSION_CODE=4`, README rows, `antora.yml`)
  with `RELEASE_VERSION=1.1.0 NEXT_SNAPSHOT=1.1.1-SNAPSHOT`: `VERSION_NAME` → `1.1.1-SNAPSHOT`, `VERSION_CODE`
  untouched, README rows → `1.1.1-SNAPSHOT`/`1.1.0`, `antora.yml` → `1.1.0`.
- The simulator-picker snippet was run locally (Xcode 27.0): it chose `iPhone 18 Pro` on
  `com.apple.CoreSimulator.SimRuntime.iOS-27-0`; every Gradle task and report path the workflows reference
  (`testAndroidHostTest`, `testDebugUnitTest`, `iosSimulatorArm64Test` → `shared/build/test-results/iosSimulatorArm64Test/*.xml`,
  `koverXmlReport`/`koverVerify`, `:shared:lint` → `lint-results-androidMain.sarif`, `:androidApp:lint` →
  `lint-results-debug.sarif`, `shared/build/reports/detekt/detekt.sarif`, `:shared:dokkaGenerate` →
  `shared/build/dokka/html`, `xcodebuild test -scheme iosApp`) was produced by `iru-setup-kmp-app`'s own
  verification run.
- **UI screenshot uploads (2026-10-16)**: the two `Upload UI screenshots` steps were added to rendered copies of
  combinations (1)–(3) in `$TMPDIR/iru-verify/kmp/ui-contract/wf/` and all three passed `actionlint` 1.7.12 again;
  combination (3) (`ios-ci: none`) has only the Android upload. The captures themselves come from
  `iru-setup-kmp-app`'s UI tests (`--android` from the Linux test step, `--ios` from `iosSimulatorArm64Test` and,
  with `ci-tests: full`, the `ImageRenderer` XCTest).
- **Unverified locally, never a blocker**: every job on GitHub's runners (incl. the `~/.konan` cache, Homebrew
  installs on the image), the Sonar scan, CodeQL extraction (notably Kotlin 2.4 support in the `java-kotlin`
  extractor), OSV-Scanner/gitleaks/dependency-review results, the signed Android bundle and Play/Firebase uploads,
  and the iOS archive/export/TestFlight upload (no Apple credentials here).

**Warn explicitly:**

- Review every generated workflow before relying on it; do a `workflow_dispatch` dry run of `release.yml` with an
  existing tag before a real release — it signs and uploads.
- Every secret above and the matching Sonar project, Play Console service account, Firebase project, App Store
  Connect API key and Apple team must exist before CI passes; this skill creates none of them.
- On a private repository, the `ios` job and `codeql-swift` consume macOS minutes at ~10× — revisit `ios-ci` if the
  budget runs out.
