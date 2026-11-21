---
name: iru-setup-macos-github-workflows
description: "Invoke as `/iru-setup-macos-github-workflows`, optionally with `args` (key: value lines such as `branching`, `macos-ci`, `ci-tests`, `distribution`, `pages`, `sonar`, `on-existing`); normally called by `iru-setup-macos-repository`. Creates build, release, sync and security workflows plus Dependabot for a macOS desktop app."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(python3 *) Bash(ruby *) Bash(actionlint *) Bash(xcodegen *) Bash(xcodebuild *) Bash(xcrun *) Bash(swift *) Bash(swiftlint *) Bash(plutil *) Bash(mkdir *)
---

# Setup macOS GitHub Workflows

Scaffold (or update) the CI/CD pipeline for a macOS desktop app built by `iru-setup-macos-app`, around one cost rule:
**what can run on Linux runs on `ubuntu-latest`** — SwiftLint and `swift format` (in a `swift` container), the Antora
build, the Sonar scan, the docs merge/deploy, `sync.yml`, dependency review, OSV-Scanner and gitleaks — and only what
needs Xcode uses the macOS `<runner>`: the Core tests and coverage, the app tests, DocC, CodeQL `swift`, and the
release jobs. macOS minutes cost **~10×** Linux minutes on a private repository ($0.062 vs $0.006 per minute, per
GitHub's current pricing), so when the macOS job runs is an explicit choice (`macos-ci`), and it `needs: lint` — a
lint failure never spends macOS minutes.

Files (templates in `reference/workflow-templates.md`; its conditional markers and placeholders are explained there):

| File | Written when |
|---|---|
| `.github/workflows/build.yml` | always |
| `.github/scripts/lcov_to_sonar_generic.py` (lcov → SonarQube generic coverage XML) | `sonar` ≠ `none` |
| `.github/workflows/release.yml` | `distribution` ≠ `none` |
| `.github/workflows/sync.yml` + `.github/scripts/sync_versions.py` | `branching: gitflow` |
| `.github/workflows/security.yml` | always (jobs gated by `security-*`) |
| `.github/dependabot.yml` | always |

Sources (cited as "irurueta docs: apps/apple/<page>.adoc"): `ci-cd-xcode-cloud-and-command-line.adoc` (archive/export
with an App Store Connect API key and `-allowProvisioningUpdates`, `destination: upload`, `notarytool --key/--key-id/
--issuer` on CI, the `ASC_KEY_ID`/`ASC_ISSUER_ID`/`ASC_PRIVATE_KEY` secrets written with `umask 077` and removed in an
`always` step, GitHub-hosted runner labels, the Xcode 26+ SDK upload minimum) and
`macos-distribution-outside-the-app-store.adoc` (Developer ID export, `codesign --verify --deep --strict`, "notarize
what you ship, outermost container only", `notarytool` accepts zip/dmg/pkg but never a bare `.app`, a zip can't be
stapled, sign the DMG with Developer ID Application, staple, Gatekeeper). Plus Apple's "Customizing the notarization
workflow" and GitHub's `actions/runner-images` macOS READMEs. Everything uses Xcode's own tools — **no Fastlane**.

The templates assume `iru-setup-macos-app`'s **scaffold contract** (its Step 8 table): `project.yml` (`name:` =
`<AppName>`, `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` unquoted in `settings.base`), scheme `<AppName>` whose test
action runs `Core/CoreTests` + `<AppName>Tests` + `<AppName>UITests`, `Packages/Core` with the `swift-docc-plugin`,
`App/Resources/<AppName>.entitlements`, `ExportOptions-{copy|developer-id|app-store}.plist`, `scripts/make-dmg.sh`,
`.swiftlint.yml`, `.swift-format`, and `sonar-project.properties` when `sonar` ≠ `none`. If the layout differs (a
committed `.xcodeproj` without `project.yml`, a Tuist `Project.swift`, no `Packages/Core`, a UI-test target with
another name, no export plist for the chosen `distribution`), warn, ask whether to stop or continue, and when
continuing read the real names (`xcodebuild -list`, the manifest) and adapt every command — drop `xcodegen generate`
for a committed project — listing each adaptation in the report.

## Step 0 — Resolve inputs

Parse `args` (`key: value` lines) first, e.g. (what `iru-setup-macos-repository` passes):

```
branching: gitflow
integration-branch: develop
stable-branch: main
runner: macos-26
macos-ci: all
ci-tests: full
open-source: yes
visibility: public
pages: yes
distribution: internal
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_field-notes
sonar-host-url: https://sonarcloud.io
mode: new
```

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `branching` | `gitflow` / `main-only` | `gitflow` when an integration branch exists (`git branch -a`), else ask |
| `integration-branch` / `stable-branch` | branch names | `develop` / `main` (confirm against `git branch -a`) |
| `runner` | macOS runner label | `macos-26` (GA image). `xcode-27` is the preview image with Xcode 27.x — see Step 2 |
| `xcode-version` | `maxim-lobanov/setup-xcode` input | Step 2: `project.yml`'s `options.xcodeVersion` when the chosen image offers it, else the image's `(default)` Xcode — with a warning |
| `macos-ci` | `all` / `stable` — when the macOS job (and CodeQL `swift`) runs | see Standalone resolution |
| `ci-tests` | `unit` / `full` — `full` adds the XCUITest UI tests to the macOS job | public `full`; private/internal ask, `unit` recommended |
| `pages` | `yes` / `no` | public `yes` without asking; private/internal ask, `no` recommended |
| `distribution` | `none` / `internal` / `store` | from the committed export plist (`ExportOptions-developer-id.plist` → `internal`, `ExportOptions-app-store.plist` → `store`, `ExportOptions-copy.plist` only → `none`); ask only when several or none exist |
| `sonar` (+ coordinates) | `cloud` / `self-hosted` / `none` | `sonar-project.properties` present → read it (`sonarcloud.io` host → `cloud`); else see Standalone resolution |
| `open-source` | `yes` / `no` | `gh repo view --json isPrivate` if unresolved |
| `visibility` | `public` / `private` / `internal` (`internal` behaves like `private`) | see Standalone resolution |
| `mode` | `new` / `existing` | informational only — every existing file is still asked about |
| `on-existing` | `ask` / `keep` / `update` / `replace` | `ask` |
| `security-dependency-review` / `security-osv` / `security-gitleaks` | gate one `security.yml` job each | `yes` |
| `security-codeql` | the macOS CodeQL `swift` job | `yes` for public (free); `no` for private/internal (macOS cost) unless the user opts in — say which in the report |
| `ci`, `license`, `developer-*`, `organization-url`, `ui`, `architecture`, `sandbox`, `min-macos`, `publish`, identity keys | shared vocabulary | accepted and ignored — `ui`/scheme/plists are detected from disk; named in the report |

### Standalone resolution of `visibility`, `macos-ci`, `ci-tests`, `pages`, `sonar`

In this order, skipping any `args` supplied. Quote cost facts "per GitHub's current pricing" (verified October 2026)
and tell the user to double-check their plan: standard GitHub-hosted runners are **free for public repositories**;
private/internal ones consume the account's included minutes — **2,000/month on GitHub Free, 3,000 on Pro and Team,
50,000 on Enterprise Cloud** — then are billed per minute (**Linux 2-core $0.006, macOS $0.062 — ~10×**). The macOS
job typically takes 8–15 minutes (more with UI tests), i.e. 80–150 minutes of a private budget per run; a release
adds 15–40 (notarization waits are billed too).

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercase), else ask Public/Private (Public
   recommended when open source). Open source + private is contradictory — warn and ask.
2. **`macos-ci`** — **Every push and pull request** (`all`, recommended for public — free there) / **Stable branch and
   manual runs only** (`stable`, recommended for private/internal — SwiftLint, `swift format` and the Antora docs still
   run on Linux on every push and PR; the macOS job, the Sonar scan, the DocC merge and CodeQL `swift` run only on
   pushes to the stable branch, `workflow_dispatch` (and CodeQL's weekly schedule)). Say plainly that with `stable` a
   broken test or build on the integration branch is only caught when it reaches the stable branch or someone runs
   the workflow by hand.
3. **`ci-tests`** — **Unit tests only** (`unit`: `CoreTests` + `<AppName>Tests`; the XCUITest bundle stays local) /
   **Unit + UI tests** (`full`: also `<AppName>UITests`, which launch the app on the runner's desktop session — a few
   more macOS minutes per run).
4. **`pages`** — public: `yes`. Private/internal: **No (Recommended)** ("the Antora + DocC site is still built and
   uploaded as the `docs-site` artifact") / **Yes** ("requires Pro/Team/Enterprise Cloud; the site is PUBLIC unless
   Enterprise Cloud private Pages").
5. **`sonar`** — skipped when `sonar-project.properties` answers it. "Send CI analysis and coverage reports to
   SonarCloud / SonarQube?" `cloud` recommended only for public + open source; otherwise `none` recommended,
   `self-hosted` offered (SonarCloud is free only for public/open-source projects; Community Build is free but
   self-run). With `sonar` ≠ `none` but no `sonar-project.properties`, warn: the scan needs `iru-setup-macos-app`'s
   file (re-run it with that `sonar`), or pass the coordinates as `-D` args — offer to add them to the step.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 1 — Survey the repository

- **Scaffold detection**: `project.yml` (`name:` → `<AppName>`, `INFOPLIST_KEY_CFBundleDisplayName` → `<app-name>`,
  `options.xcodeVersion`, the scheme's test targets — the UI-test target name), `Packages/Core/Package.swift`
  (`swift-docc-plugin` present? — without it the DocC step fails: offer to drop it or add the dependency),
  `ExportOptions-*.plist` (which `method`; whether `teamID` is empty — CI fills it from `APPLE_TEAM_ID` either way),
  `scripts/make-dmg.sh` (required for `internal`), `sonar-project.properties`, `.swiftlint.yml`/`.swift-format`.
- `docs/antora.yml`/`docs/antora-playbook.yml` — missing → recommend running `iru-setup-antora` first (the docs job
  fails without them).
- `docs/package-lock.json` — the docs job runs `npm ci` with the setup-node npm cache keyed on it, so it fails at
  `actions/setup-node` without a committed lockfile. `docs/` present without one (a site from the old per-package
  install) → recommend `/iru-update-antora` first; no `docs/` → `iru-setup-antora`, which commits it.
- `<repo>` from `git remote get-url origin` (DocC's `--hosting-base-path <repo>/api`; GitHub project Pages serve at
  `https://<owner>.github.io/<repo>/`).
- Branch names (`git branch -a`).
- Existing files: `.github/workflows/build.yml`, `release.yml`, `sync.yml`, `security.yml`,
  `.github/scripts/sync_versions.py`, `.github/scripts/lcov_to_sonar_generic.py`, `.github/dependabot.yml`; also
  sibling-named files (`ci.yml`, `macos.yml`, `swift.yml`, `codeql.yml`, a `fastlane/` setup) that would duplicate this
  pipeline.

### Announce the mode, then warn and ask per existing file

- None exist: "**Creating the CI/CD pipeline from scratch**." Every file is *created*.
- Any exists: "**Updating an existing CI/CD pipeline** — found: <paths>." Unless `on-existing` is `keep`/`update`/
  `replace` (apply it to every file, still printing the warning), ask with `AskUserQuestion`, one question per file
  (`sync.yml` + `sync_versions.py` count as one, `build.yml` + `lcov_to_sonar_generic.py` as one; batch up to 4),
  naming each concrete mismatch (e.g. "`build.yml` runs the macOS job on every push but you chose `macos-ci: stable`",
  "`build.yml` runs SwiftLint on the macOS runner — on Linux it costs ~1/10", "`build.yml` deploys to Pages but you
  chose `pages: no`", "`release.yml` uploads to App Store Connect but `ExportOptions-developer-id.plist` says
  `internal`", "an existing `codeql.yml` would run CodeQL twice"):
  - **Update (Recommended)** — merge using the templates as reference; keep every step/job/entry this skill doesn't
    own (notifications, extra jobs, other Dependabot ecosystems); you review a diff first. Customizations survive, but
    a customized part this skill owns may be rewritten.
  - **Keep as is** — untouched; it may disagree with this run's choices — the report lists each mismatch. Recommended
    only for a `.github/dependabot.yml` that already has `swift` and `github-actions` entries.
  - **Replace** — regenerate from the template; every customization is lost (git history only). Never the default.
  - **Stop** (first question only) — change nothing; report the existing files and end.

## Step 2 — Look up versions and runner images at run time

Resolve every `uses:` major with `git ls-remote --tags https://github.com/<owner>/<repo>` (the REST `/tags` listing
can miss floating majors); fall back to this table, confirmed on 2026-10-16:

| Action / tool | Version | Floating major? |
|---|---|---|
| `actions/checkout` | `v7` | yes |
| `maxim-lobanov/setup-xcode` | `v1` | yes |
| `actions/setup-node` / `actions/setup-python` | `v7` / `v7` | yes |
| `actions/upload-artifact` / `actions/download-artifact` | `v7` / `v8` | yes |
| `actions/upload-pages-artifact` / `actions/deploy-pages` | `v5` / `v5` | yes |
| `apple-actions/import-codesign-certs` | `v7` | yes (inputs `p12-file-base64`, `p12-password`) |
| `SonarSource/sonarqube-scan-action` | `v8` | yes |
| `github/codeql-action` (`init`/`analyze`) | `v4` | yes (don't use `/releases/latest` — it returns a `codeql-bundle-*` tag) |
| `gitleaks/gitleaks-action` | `v3` | yes |
| `actions/dependency-review-action` | **`v5.0.0`** | **no** — pin the exact tag |
| `google/osv-scanner-action` reusable workflow | **`v2.7.0`** | **no** — pin the exact tag |
| SwiftLint (`<swiftlint-version>`; `swiftlint_linux_{amd64,arm64}.zip` release assets) | `gh release view --repo realm/SwiftLint --json tagName` → `0.65.1` | n/a |
| `swift` Docker image (`<linux-swift-tag>`) | `<major.minor>-noble` of the local toolchain's Swift (`swift --version`; Docker Hub `library/swift` tags) → `6.4-noble` | n/a |
| XcodeGen (Homebrew, installed on the runner only when missing) | `2.46.0` | n/a |

**Runner images and `xcode-version`.** List the labels with `gh api repos/actions/runner-images/contents/images/macos
--jq '.[].name'` and read the chosen image's `### Xcode` table from
`https://raw.githubusercontent.com/actions/runner-images/main/images/macos/<runner>-arm64-Readme.md`. On 2026-10-16:
`macos-26` (GA, macOS 26.6) offers Xcode 26.0.1–**26.6 (default)**; `xcode-27` (public preview, macOS 27.0.1) offers
**27.0 (default)**, 27.1 and a 27.2 beta. Neither image lists SwiftLint any more (the lint job installs it on Linux;
the macOS job doesn't need it), `xcodegen` isn't preinstalled (the `brew install` guard handles it), `xcbeautify` is
(unused — `-quiet` keeps logs short). Pick `xcode-version`:

- `project.yml`'s `options.xcodeVersion` (major.minor, e.g. `27.1` — the Xcode the scaffold was generated and verified
  with) when the chosen image offers it.
- Otherwise the image's `(default)` version, **with a warning**: `iru-setup-macos-app` verified its templates with Xcode
  27.1 only; building with an older Xcode (e.g. 26.6 on `macos-26`) is unverified — recommend `runner: xcode-27` +
  `xcode-version: 27.1`, noting that a preview image can change or be pulled with less notice than a GA one. Uploads to
  App Store Connect need Xcode 26+ (irurueta docs: apps/apple/ci-cd-xcode-cloud-and-command-line.adoc).

## Step 3 — Render the templates

Read `reference/workflow-templates.md`, resolve the markers for this run, and substitute the placeholders it lists.
`<app-name>` lands inside a double-quoted bash string (the DMG volume name): escape `"`, `$` and backticks. Notes that
matter when adapting them:

- **`lint` job (Linux)** — `container: swift:<linux-swift-tag>` provides `swift format` and the SourceKit library the
  dynamically linked Linux SwiftLint binary loads; the job installs `curl`/`unzip` only if the image lacks them.
  `--reporter github-actions-logging` turns violations into PR annotations; with `sonar`, a second non-failing run
  writes SwiftLint's `sonarqube` (generic external issues) report as the `swiftlint-report` artifact.
- **`macos` job** — `needs: lint`; `macos-ci: stable` adds the job-level `if:` (stable-branch push or
  `workflow_dispatch`). **Core coverage is the bar**: `swift test --package-path Packages/Core --enable-code-coverage`,
  then `llvm-cov` against `$(swift build --show-bin-path)/CoreTests.xctest/Contents/MacOS/CoreTests` and
  `codecov/default.profdata` (`--show-bin-path` resolves both the Swift 6.4 `.build/out/Products/Debug` layout and older
  `.build/<triple>/debug` ones), ignoring `Tests/`, `DerivedSources`, `.build/`; an inline check fails below 80% lines.
  The app scheme's `xccov` report never sees the statically linked Core, and the UI layer (`App/Sources`) is "not
  measured" (also excluded in `sonar-project.properties`). App tests: `xcodebuild test -project <AppName>.xcodeproj
  -scheme <AppName> -destination 'platform=macOS' -derivedDataPath build/dd` with ad-hoc signing
  (`CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=`) — `CODE_SIGNING_ALLOWED=NO` would kill the UI-test
  runner on arm64; `-skip-testing:<AppName>UITests` for `unit`. `full` first checks `automationmodetool` reports
  "DOES NOT REQUIRE" authentication (GitHub's images enable it in `configure-machine.sh` and assert it in their image
  tests; a self-hosted Mac needs `sudo automationmodetool enable-automationmode-without-authentication` once, run by an
  administrator — never by this skill) so a misconfigured runner fails fast instead of timing out. Runners are fresh
  VMs, so the local `.simulator-test.lock` isn't needed in CI. DocC runs on every macOS run (`--warnings-as-errors` is
  the documentation gate) with `--transform-for-static-hosting --hosting-base-path <repo>/api`. UI contract: the app-test step passes
  `TEST_RUNNER_UI_SCREENSHOTS_DIR=${{ github.workspace }}/.ui-screenshots`; because the sandboxed host app can't write
  the workspace, the next step rebuilds `.ui-screenshots/<kind>/<Id>/<file>.png` from the captures the tests attached
  to the `.xcresult` (`xcrun xcresulttool export attachments` + its `manifest.json`), and the job uploads
  `.ui-screenshots/` as a 7-day `ui-screenshots` artifact (`if: always()`, `if-no-files-found: ignore`) — inside the
  existing macOS job, no extra runner or job.
- **`sonar` job (Linux)** — `needs: [lint, macos]`, so it runs only when the macOS job ran and passed; downloads
  `core-coverage` (`sonarqube-generic-coverage.xml`, converted from `coverage.lcov` by `lcov_to_sonar_generic.py`
  from the repository root so paths read `Packages/Core/Sources/…`) and `swiftlint-report`, and passes
  `-Dsonar.externalIssuesReportPaths=swiftlint-sonar.json`. The scaffold's `sonar.swift.swiftLint.reportPaths` key
  belongs to the community sonar-swift plugin and is ignored by SonarCloud — leave it, it's harmless. Skipped for
  fork PRs (no `SONAR_TOKEN`).
- **`docs` → `site` → `deploy-docs`** — `docs` builds Antora on every event; `site` merges the `antora-site` and
  `docc-api` artifacts into `site/` + `site/api/` (+ `.nojekyll`), the layout every catalog stack uses. `pages: yes`:
  `site` and `deploy-docs` run only on a stable-branch push. `pages: no`: `site` uploads `docs-site` whenever the macOS
  job ran (with `macos-ci: stable` a PR validates the Antora half only).
- **`release.yml`** — `release: [released]` + `workflow_dispatch` with a `tag` input (dry run). Both jobs first check
  that `project.yml` `MARKETING_VERSION` is numeric `x.y.z` and equals the tag. `internal` (`developer-id` job): import
  the `.p12` with `apple-actions/import-codesign-certs` (Developer ID certificates can't be created by
  `-allowProvisioningUpdates`; only the Account Holder can create one), write the API key, **archive with manual
  Developer ID signing** (`CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="Developer ID Application"
  DEVELOPMENT_TEAM=$APPLE_TEAM_ID` — no development certificate or profile needed on the runner), export with a
  temporary copy of `ExportOptions-developer-id.plist` whose `teamID` is set by `plutil -replace` (+ the API key and
  `-allowProvisioningUpdates`), `codesign --verify --deep --strict`, then: `ditto` zip → `notarytool submit --wait
  --output-format json` (the status is checked to be `Accepted`, otherwise the notary log is printed and the job
  fails) → `stapler staple`/`validate` the app → `scripts/make-dmg.sh` with `DMG_SIGN_IDENTITY` set to the imported
  identity's SHA-1 (`security find-identity`) → notarize and staple the DMG → `spctl --assess --type open --context
  context:primary-signature`. The DMG (`<AppName>-<version>.dmg`) is kept as a workflow artifact and attached to the
  GitHub Release (`gh release upload --clobber`) for a release event, or on a dispatch run with `attach: true`.
  `store` (`app-store` job): automatic signing with the API key (`-allowProvisioningUpdates`, cloud-managed
  certificates), then `-exportArchive` with `ExportOptions-app-store.plist` (`destination: upload`) uploads the build;
  submitting it for review stays manual in App Store Connect. App Store Connect rejects a reused
  `CURRENT_PROJECT_VERSION` — a re-run after a successful upload needs a bump (`iru-swift-bump-version`).
- **`sync.yml`** — after a release from the stable branch: merge it into the integration branch and run
  `sync_versions.py` with `NEXT_VERSION=x.y.(z+1)` — `iru-swift-bump-version` Step 5's app convention (no `-SNAPSHOT`/
  `-dev` marker on `MARKETING_VERSION`; App Store Connect and notarization take `x.y.z` only), the same patch bump
  `iru-setup-swift-github-workflows`' app flavor uses: `MARKETING_VERSION` → next patch and `CURRENT_PROJECT_VERSION`
  + 1 with that skill's quote-tolerant first-occurrence regexes, README "Current development version"/"Latest
  release" rows and `docs/antora.yml` (latest release) when present. The branch is `sync_<released version>`
  (release `1.4.1` → `sync_1.4.1`); if the merge conflicts, the PR opens from the release commit without a bump, so a
  conflicting (patch) sync never lowers the integration branch's development version. The commit stages with `git add -u` — a pathspec
  list aborts the whole add when one path matches nothing.
- **`security.yml`** — CodeQL `swift` needs macOS (no Linux Swift extractor): `xcodegen generate`, then a manual
  `xcodebuild build … -destination 'generic/platform=macOS' CODE_SIGNING_ALLOWED=NO` between `init` and `analyze`.
  With `macos-ci: stable` it runs on the weekly schedule, stable-branch pushes and `workflow_dispatch` only. OSV-Scanner
  reads `Packages/Core/Package.resolved`. Dependabot's `swift` entry points at `/Packages/Core` (the only
  `Package.swift`; the root has none).

## Step 4 — Write the files

Apply Step 1's per-file decision (create / Update after a diff / Keep as is / Replace). `sync.yml` and
`sync_versions.py` are always written together (gitflow only); `lcov_to_sonar_generic.py` only with `sonar` ≠
`none`; `release.yml` only when `distribution` ≠ `none`; `security.yml` and `.github/dependabot.yml` always. If an
existing `.github/dependabot.yml` has other ecosystems (`npm` for `docs/`, …), keep them and only add/update `swift`
and `github-actions`. With `distribution: internal` but no `scripts/make-dmg.sh`, or a `distribution` whose export
plist is missing, stop and point at `iru-setup-macos-app` (Update) rather than writing a release job that can't run.

## Step 5 — Verify

In a throwaway copy (or `$TMPDIR`), never in a way that commits anything:

1. `actionlint` (with `shellcheck` on `PATH`) over every written workflow, with an `actionlint.yaml` declaring
   `self-hosted-runner: labels: [xcode-27]` when `runner: xcode-27` (actionlint 1.7.12 doesn't know the preview
   label); if `actionlint` isn't installed, fall back to `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>` (a PyYAML
   parse turns the `on:` key into `True` — a false flag, never quote `on:`).
2. `python3 -m py_compile .github/scripts/*.py`.
3. On macOS with the scaffold present, run the `lint` and `macos` job commands locally through `iru-gate-runner`
   (they're long): SwiftLint/`swift format`, the Core coverage step (it must print the TOTAL line and pass the 80%
   check), `xcodegen generate`, the app-test step for the chosen `ci-tests` (UI tests take over the desktop — hold
   `.simulator-test.lock` and expect the Automation Mode check to fail on a developer Mac that requires
   authentication), and the DocC step.

Everything that needs GitHub's runners, certificates, the notary service or App Store Connect stays **unverified
locally** — say so.

## Step 6 — Report

Summarize: the mode (from scratch / updating), and per file the action taken — **created / updated / replaced / kept
as is** (or Stop) — with each mismatch of a kept file, and any adaptation for a non-standard layout. State what was
wired and why: the job/runner layout (Linux: `lint`, `sonar`, `docs`, `site`, `deploy-docs`, `sync`, dependency
review, OSV, gitleaks; macOS `<runner>`: `macos`, CodeQL `swift`, the release job — restating the ~10× macOS cost
for private repositories); `runner`/`xcode-version` (and the warning when the image lacks the scaffold's Xcode);
`macos-ci` (when the macOS job runs); `ci-tests`; `branching` (no `sync.yml` for `main-only` — say so plainly);
`visibility` (and where it came from); `pages`; `sonar`; `distribution` (which release job exists; that App Store
review submission is manual); the security jobs included/omitted (CodeQL `swift` default for private); any ignored
`args` keys. Then the required-secrets table — only the rows that apply:

| Secret | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | Sonar scan auth | `sonar` ≠ `none` |
| `APPLE_TEAM_ID` | Apple Developer Team ID — `DEVELOPMENT_TEAM` for the archive and the export plist's `teamID` | `distribution` ≠ `none` |
| `ASC_KEY_ID` / `ASC_ISSUER_ID` | App Store Connect API key ID and issuer UUID | `distribution` ≠ `none` |
| `ASC_PRIVATE_KEY` | the raw content of `AuthKey_<key id>.p8` (downloadable once; not base64) — `notarytool` and `-authenticationKeyPath`. Team key; **Developer** role is enough for notarization, **Admin or App Manager** for `-allowProvisioningUpdates` (store archive/upload, developer-id export profiles) | `distribution` ≠ `none` |
| `MACOS_CERTIFICATE_P12_BASE64` / `MACOS_CERTIFICATE_PASSWORD` | the **Developer ID Application** certificate + private key exported from Keychain Access as `.p12` (base64: `base64 -i cert.p12 \| pbcopy`) and its export password | `distribution: internal` |
| `GITHUB_TOKEN` | release-asset upload, `sync.yml`'s `gh` calls, CodeQL/gitleaks | automatic |

Repository settings to call out: Settings → Actions → General → "Workflow permissions" must allow GitHub Actions to
create pull requests (gitflow `sync.yml`); with `pages: yes`, Settings → Pages → Source = **GitHub Actions** (and a
Pro/Team/Enterprise Cloud plan for a private repository — the site is public unless Enterprise Cloud private Pages);
for `internal`, a Developer ID Application certificate created by the Account Holder; for `store`, the bundle ID
registered and an app record created in App Store Connect before the first upload. Optionally scope the release
secrets to a GitHub environment (e.g. `release`, with required reviewers) and add `environment: release` to the
release job.

Tell `iru-setup-readme` / the orchestrator: workflows are named **Build** (`build.yml`, the CI badge), **Release**,
**Sync**, **Security**; the GitHub Release asset is `<AppName>-<version>.dmg` (signed, notarized, stapled — the
"download" link for `internal`); docs land at `https://<owner>.github.io/<repo>/` with the DocC reference under
`api/documentation/core/` (`pages: yes`).

### Known quirks / verification notes (2026-10-16)

- **Rendered and checked for real** in `$TMPDIR/iru-verify/macos/iru-setup-macos-github-workflows/` (copies of
  `iru-setup-macos-app`'s verified variants, outside this repository; originals untouched): (1) `swiftui-mvvm` —
  gitflow, `macos-ci: all`, `ci-tests: full`, `pages: yes`, `sonar: cloud`, `distribution: internal`; (2) `appkit-mvc`
  — main-only, `macos-ci: stable`, `ci-tests: unit`, `pages: no`, `sonar: none`, `distribution: store` (with an
  `ExportOptions-app-store.plist` rendered from the scaffold's template); plus three more marker combinations
  (gitflow/stable/unit/pages no/sonar/internal without CodeQL; main-only/all/full/pages yes/store with CodeQL on
  `xcode-27`; gitflow/stable/full/pages yes/sonar/`distribution: none`). Every workflow passed `actionlint` 1.7.12
  with shellcheck (exit 0) and a Ruby YAML parse; both Python scripts compile. actionlint rejects `runs-on: xcode-27`
  as an unknown label until it is declared in `actionlint.yaml` (it knows `macos-26`).
- **Steps executed locally** (Xcode 27.1 / Swift 6.4, macOS 27.0.1 arm64, XcodeGen 2.46.0 and SwiftLint 0.65.1 from a
  temp download): `swiftlint lint --strict` (0 violations) and the `sonarqube` reporter (valid `{"issues": []}`),
  `swift format lint --strict`; the Core coverage step verbatim — 7/8 tests, **100.00%** lines for both variants, the
  80% check passing (and failing with exit 1 on a 50% fixture); `lcov_to_sonar_generic.py` (3 files,
  `Packages/Core/Sources/Core/…` paths); `xcodegen generate`; `xcodebuild test` unit-only for `appkit-mvc` — **21/21
  passed**; the DocC step (static hosting output with `/my-mac-app/api/` links) merged into a fake Antora `site/`; the
  CodeQL manual build command (0 warnings); the release version guard (`v1.0.0`/`1.0.0` pass; `v1.0.1` and
  `1.0.0-beta.1` fail); `xcodebuild archive` with ad-hoc signing in place of Developer ID (universal `x86_64 arm64`),
  the same archive with `CODE_SIGN_IDENTITY="Developer ID Application"` failing **only** with `No "Developer ID
  Application" signing certificate matching team ID … was found` (settings accepted), the `plutil -replace teamID`
  patch on both export plists, the developer-id export failing only for the missing certificate, a copy-app export +
  `codesign --verify --deep --strict`, the `ditto` notarization zip, `scripts/make-dmg.sh` unsigned and with
  `DMG_SIGN_IDENTITY=-` (identifier `<bundle-id>.dmg`), and `spctl --assess --type open` **rejecting** the ad-hoc DMG
  (exit 3 — the gate is meaningful); `sync_versions.py` on a fixture (`MARKETING_VERSION: 1.4.0`,
  `CURRENT_PROJECT_VERSION: 7`, README rows, `antora.yml` `1.3.2`) with `RELEASE_VERSION=1.4.0 NEXT_VERSION=1.4.1` →
  `1.4.1` / `8`, README `1.4.1`/`1.4.0`, `antora.yml` `1.4.0`, the scaffold's comment lines untouched, `git add -u`
  staging exactly those three files; a `-dev` `NEXT_VERSION` exits 1; the `sync.yml` version computation (`v1.4.1` →
  next `1.4.2`, branch `sync_1.4.1`; `v1.4` rejected).
- **UI tests on this host**: the Automation Mode check printed "This device requires user authentication to enable
  Automation Mode" and failed as designed; the full `xcodebuild test` then failed only in the UI-test runner ("Timed
  out while enabling automation mode") while CoreTests + `<AppName>Tests` passed — the same machine passed the UI tests
  in `iru-setup-macos-app`'s run after the user authorized the system prompt. Hosted images ship with authentication
  disabled (runner-images `configure-machine.sh` + its `AutomationModeTool` image test); not observed here.
- **The Linux `lint` container was not run**: the local Docker VM's disk was full while pulling `swift:6.4-noble`
  (nothing was pruned — other projects' images). The same SwiftLint version and `swift format` (Swift 6.4) commands
  passed on macOS; the Linux SwiftLint asset names (`swiftlint_linux_amd64.zip` with `swiftlint` + `swiftlint-static`)
  were confirmed from the 0.65.1 release.
- **Unverified locally, never a blocker**: every job on GitHub's runners and images (`maxim-lobanov/setup-xcode`
  selecting `27.1` on `xcode-27`, Homebrew `xcodegen`, the container job); building with Xcode 26.6 on `macos-26`;
  `import-codesign-certs`, Developer ID signing and export, `notarytool` (status handling included), stapling,
  signed-DMG notarization and the Gatekeeper pass; the App Store Connect archive/upload with cloud-managed signing;
  `gh release upload`; the Sonar scan and its SwiftLint external-issues import; Pages deployment; CodeQL extraction;
  OSV-Scanner/gitleaks/dependency-review results; the Antora half of the docs job (no Antora site in the scaffold
  copies — identical to the KMP/Windows siblings' steps).

**Warn explicitly:**

- Review every generated workflow before relying on it; do a `workflow_dispatch` dry run of `release.yml` with an
  existing tag before a real release — it signs and notarizes (`internal`) or uploads to App Store Connect (`store`).
- Never commit the `.p12`, the `.p8` key or certificates; they live only in the secrets above (and `.gitignore` covers
  `*.p8`/`*.p12`/`*.cer`).
- Every secret above, the Sonar project, the Developer ID certificate and the App Store Connect app record must exist
  before the release jobs pass; this skill creates none of them. Apple asks for at most 75 notarizations a day.
- On a private repository the macOS job (and CodeQL `swift`, if enabled) consumes macOS minutes at ~10× — revisit
  `macos-ci`/`ci-tests` if the budget runs out.
