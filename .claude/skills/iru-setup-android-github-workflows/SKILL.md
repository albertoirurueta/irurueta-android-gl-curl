---
name: iru-setup-android-github-workflows
description: "Invoked as /iru-setup-android-github-workflows with optional key: value args (flavor: library|app is required), usually by iru-setup-android-repository. Creates or updates the GitHub Actions build, test, lint, coverage, docs, release and security workflows plus Dependabot for an Android/Gradle repository, asking Update/Keep/Replace for existing files."
model: sonnet
---

# Setup Android GitHub Workflows

Scaffold (or update) the GitHub Actions workflows plus a Dependabot config for an Android/Gradle repository
produced by `iru-setup-android-library` or `iru-setup-android-app`, for whichever `branching` model the
repository actually uses:

- **`branching: gitflow`** (default) — **`develop.yml`** (push to the integration branch + `workflow_dispatch`),
  **`main.yml`** (triggered by a published GitHub Release instead — the same pipeline, plus the actual
  publish/store step), and **`sync.yml`** + its companion `.github/scripts/sync_versions.py` (opens a pull
  request merging the released branch back into the integration branch and bumping the development
  `-SNAPSHOT` version once a release lands; the branch is `sync_<released version>`, e.g. release `1.0.1` →
  `sync_1.0.1`).
- **`branching: main-only`** — **`main.yml`** (push/PR to the stable branch — build and test only, no
  publish/store step) and **`publish.yml`** (triggered by a published GitHub Release — the actual publish/store
  step). No `sync.yml` — there's no second branch to sync back into.
- **`security.yml`** and **`.github/dependabot.yml`** — generated (or updated) regardless of `branching`: a
  `dependency-review` job, a CodeQL job (`java-kotlin`, `build-mode: autobuild`), an OSV-Scanner job, a
  `gitleaks` job (each individually omittable via `args`), plus two opt-in jobs (OWASP dependency-check,
  `MobSF/mobsfscan` — apps only), and grouped weekly Dependabot updates for the `gradle` and `github-actions`
  ecosystems.

Every pipeline stage differs slightly by `flavor`:

- **`flavor: library`** — runs `<lib-module>:dokkaGenerate`, publishes Dokka + Antora docs to GitHub Pages,
  runs `:<lib-module>:sonar` when `sonar` isn't `none`, and — only in `main.yml`, only when `publish: yes` —
  publishes to Maven Central (a plain snapshot publish under `gitflow`'s `develop.yml`, a full
  `assembleRelease` + `publishAndReleaseToMavenCentral` under the release-triggered workflow).
- **`flavor: app`** — no Dokka/Antora-docs job, runs `:<app-module>:sonar` instead of `:<lib-module>:sonar`,
  and — only in the release-triggered workflow, only when `distribution` isn't `none` — decodes a keystore,
  runs `:<app-module>:bundleRelease`, and uploads to Google Play (`distribution: store`) or Firebase App
  Distribution (`distribution: internal`).

This skill is designed for the Gradle project shape `iru-setup-android-library`/`iru-setup-android-app`
produce — Gradle task names (`test`, `lint`, `<lib-module>:dokkaGenerate`, `<lib-module>:sonar`,
`createDebugUnitTestCoverageReport`, `lib:publishToMavenCentral`), the `mavenPublishing`/`sonar {}` Gradle DSL
blocks, and the `libraryVersion`/`versionName`/`versionCode` version scheme (`iru-android-bump-version`) are
all assumed. If neither `lib/build.gradle.kts` nor `app/build.gradle.kts` exists at the repository root, warn
the user this skill's templates assume that layout and most of Step 1's survey won't resolve, then use
`AskUserQuestion` to ask whether to stop here or continue anyway (treating every Gradle-derived placeholder in
Step 3+ as an open gap to ask about directly, called out again in the final report).

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-android-github-workflows`) or as a step inside another
skill, which resolves the parameters below itself and passes them through `args` as `key: value` lines, one
per line, e.g.:

```
flavor: library
branching: gitflow
integration-branch: develop
stable-branch: main
java-version: 17
run-instrumented-tests: yes
api-level: 35
open-source: yes
visibility: public
ci-tests: full
pages: yes
publish: yes
sonar: cloud
sonar-organization: example-org
sonar-project-key: example-org_example-library
sonar-host-url: https://sonarcloud.io
mode: new
on-existing: ask
security-dependency-review: yes
security-codeql: yes
security-osv: yes
security-gitleaks: yes
security-owasp-dependency-check: no
security-mobsfscan: no
```

Parse any such lines first; for each key found there, use that value directly and skip the matching
fact-finding step below. If `args` is absent or doesn't look like this format, treat everything as unset and
gather every fact via Step 1's survey / `AskUserQuestion` instead. Bounded choices (`flavor`, `branching`,
`visibility`, `ci-tests`, `pages`, `sonar`, `publish`/`distribution`, the `security-*` toggles) go through `AskUserQuestion` with at most four
options per question when not resolved by `args` or Step 1's survey; free-text values (branch names, the
Sonar organization/key/host, `java-version`, `api-level`) are asked as plain conversation.

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `flavor` | `library` or `app` | Required — detect from Step 1 if not given, ask if ambiguous |
| `branching` | `gitflow` or `main-only` | `gitflow` |
| `integration-branch` | Branch `develop.yml` pushes on (gitflow only) | `develop` |
| `stable-branch` | Branch releases are published from | `main` |
| `java-version` | JDK version for `actions/setup-java` | `17` (matches the reference repository, and `iru-setup-android-library`/`-app`'s `sourceCompatibility`) |
| `run-instrumented-tests` | Whether to run `connectedAndroidTest` on an emulator | An explicit value in `args` always wins. Otherwise derived from `ci-tests` (`full` ⇒ `yes`, `unit` ⇒ `no`). Neither supplied ⇒ resolve `ci-tests` per "Standalone resolution" below |
| `ci-tests` | `unit`/`full` — `unit` = JVM unit tests (`test`) only; `full` = unit + instrumented `connectedAndroidTest` on an emulator | Only consulted to derive `run-instrumented-tests` when that key wasn't supplied; ask per "Standalone resolution" below if neither was |
| `visibility` | `public`/`private`/`internal` — GitHub repository visibility, drives the cost-sensitive recommendations below | Per "Standalone resolution" below |
| `pages` | `yes`/`no` — whether the docs are deployed to GitHub Pages (`yes`) or only built and uploaded as a workflow artifact (`no`) | Public: `yes` without asking. Private/internal: ask, `no` recommended. Irrelevant (not asked) when no `lib/` module exists — the app flavor has no docs job |
| `api-level` | Emulator API level for `reactivecircus/android-emulator-runner` | `35` (matches the reference repository; independent of `compileSdk` — see Step 12) |
| `open-source` | `yes`/`no` | `gh repo view --json isPrivate` if unresolved otherwise |
| `publish` (library only) | `yes`/`no` — gates the Maven Central publish steps | `yes` only when `open-source: yes`; otherwise ask, `no` recommended |
| `distribution` (app only) | `none`/`internal`/`store` — gates the release upload step | `store` only when `open-source: yes`; otherwise ask, `none` recommended |
| `sonar` | `cloud`/`self-hosted`/`none` | `cloud` only when `open-source: yes` **and** `visibility: public`. Otherwise state that SonarCloud is free only for public/open-source projects (a private project needs a paid plan) and recommend `none`, offering self-hosted SonarQube (`self-hosted`, free Community Build, but you run the server) as the second option — the same wording `iru-setup-android-library`/`-app` use in their Step 2 |
| `sonar-organization` / `sonar-project-key` / `sonar-host-url` | Sonar coordinates | Only asked when `sonar` isn't `none`; read from `lib/build.gradle.kts`'s or `app/build.gradle.kts`'s existing `sonar {}` block first (Step 1) |
| `mode` | `new`/`existing` | Whether this is a first-time setup or a re-run against an existing pipeline. Informational only: Step 1 still checks every file on disk, and `mode: existing` does **not** mean "update silently" — each pre-existing file still goes through Step 1's Update / Keep / Replace / Stop question |
| `on-existing` | `ask`/`keep`/`update`/`replace` | `ask` (default) — one question per pre-existing file in Step 1. `keep`/`update`/`replace` applies that choice to every pre-existing file without asking (the warning is still printed). Only for scripted runs |
| `security-dependency-review` / `security-codeql` / `security-osv` / `security-gitleaks` | `yes`/`no`, each gates one `security.yml` job | `yes` |
| `security-owasp-dependency-check` | `yes`/`no` — opt-in OWASP Dependency-Check job | `no` |
| `security-mobsfscan` | `yes`/`no` — opt-in `MobSF/mobsfscan` job, **app flavor only** | `no` |
| `license` / `developer-name` / `developer-email` / `organization-url` | This catalog's shared scaffold-args vocabulary | **Not used by this skill's generated files** — accepted only so an orchestrator can pass its full shared-args set without this skill erroring on unrecognized keys; note this in the final report if any were supplied |

`.github/dependabot.yml` has no opt-out key — it's always generated/updated, same as the Java/TypeScript
siblings. There is no `ci` key: invoking this skill means CI is wanted (an orchestrator that resolved `ci: no`
skips this skill entirely rather than calling it).

### Standalone resolution of `visibility`, `sonar`, `ci-tests` and `pages`

When this skill runs without an orchestrator (or one left some of these keys unset), resolve them in this order
after `open-source`, skipping any key `args` already supplied. Quote the cost facts below "per GitHub's current
pricing" (verified October 2026 against docs.github.com) and tell the user to double-check their own plan:

1. **`visibility`** — if not in `args` and the repository has a GitHub remote, run
   `gh repo view --json visibility -q .visibility` (returns `PUBLIC`/`PRIVATE`/`INTERNAL`; lowercase it) and tell
   the user the detected value. No remote / `gh` unavailable / not GitHub → `AskUserQuestion` **Public** /
   **Private**, recommending Public when `open-source: yes`, otherwise Private. If `open-source: yes` but
   `visibility` is `private`/`internal`, warn that this is contradictory (an open-source project's repository is
   normally public, and a private repository forfeits the free-for-public pricing below) and ask which is right.
   `internal` behaves like `private` for every decision below.
2. **`sonar`** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" with the
   defaults in the table above (SonarCloud is free for public/open-source projects; a private project needs a
   paid plan; self-hosted SonarQube Community Build is free but you run the server). Skipped when Step 1 finds an
   existing `sonar {}` block (it already answers this).
3. **`ci-tests`** (only when neither `run-instrumented-tests` nor `ci-tests` was supplied) — `AskUserQuestion`:
   - **Unit tests only** (`unit`, recommended for private/internal) — "JVM unit tests (`./gradlew test`) only:
     fewer minutes, faster feedback. Instrumented `src/androidTest` tests stay local."
   - **Unit + instrumented** (`full`, recommended for public) — "Also boots an Android emulator
     (`reactivecircus/android-emulator-runner`, Linux runner with KVM) and runs `connectedAndroidTest` — complete
     verification, but the emulator boot plus the test run adds several minutes to every CI run."
   For a private/internal repository, state that GitHub-hosted Linux runner minutes are free only for public
   repositories; private/internal ones consume the account's included minutes — 2,000/month on GitHub Free,
   3,000 on Pro and Team, 50,000 on Enterprise Cloud — then are billed per minute (Linux 2-core $0.006/min). This
   pipeline runs entirely on Linux runners, so the macOS ~10× / Windows ~1.7× multipliers don't apply here.
4. **`pages`** (only when a `lib/` module exists — the docs job is library-only):
   - Public → `yes` without asking (state it in the report).
   - Private/internal → `AskUserQuestion`: **No (Recommended)** — "CI still builds the Dokka + Antora docs and
     uploads them as a workflow artifact; nothing is published." / **Yes** — "Requires GitHub Pro, Team, or
     Enterprise Cloud (Pages isn't available for private repositories on GitHub Free); the site will be PUBLIC on
     the internet unless you're on Enterprise Cloud with private Pages access control."

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

**Templates.** The workflow, script and Dependabot templates of Steps 4, 5, 7 and 8 are files under this skill's `templates/` directory, not inline text. Read only the template files for the `branching` variant resolved in Step 1 (`templates/gitflow/` or `templates/main-only/`, plus `templates/common/`; the index in Step 3 says which applies); never read the other variant's files.

## Step 1 — Survey the target repository

Gather every fact Step 0 didn't already resolve via `args`, and decide whether this is a fresh setup or an
update:

- **Module layout**: check for `lib/build.gradle.kts` and `app/build.gradle.kts`. Both present → this is the
  `iru-setup-android-library` scaffold shape (a `lib/` library plus an `app/` Compose sample/demo module —
  Step 3's test-result and coverage globs must cover **both** modules regardless of `flavor`, since the sample
  app has its own tests). Only `app/build.gradle.kts` present → the `iru-setup-android-app` scaffold shape
  (`flavor: app`, no `lib/` module at all — every `lib/**` glob and the Dokka/Sonar-on-`lib` steps are omitted
  entirely, not just gated). Only `lib/build.gradle.kts` present → a library repository with no sample app
  module; omit every `app/**` glob and the app-release job. Record which of `<lib-module>`/`<app-module>`
  (Gradle project paths — almost always `lib`/`app`, but read the actual `include(...)` lines in
  `settings.gradle.kts` to be sure) actually exist.
- **`flavor`** (skip if supplied via `args`): `app/build.gradle.kts` present with `com.android.application`
  and no `lib/build.gradle.kts` → `app`; `lib/build.gradle.kts` present with `com.android.library` → `library`
  (regardless of whether a sample `app/` module also exists). Ask if genuinely ambiguous.
- **Existing `sonar {}` block**: grep `lib/build.gradle.kts`/`app/build.gradle.kts` for `sonar {` — if present,
  read its `sonar.organization`/`sonar.projectKey`/`sonar.host.url` properties directly rather than asking
  the user again; treat this as `sonar: cloud` (host is `https://sonarcloud.io` or unset) or `sonar:
  self-hosted` (any other host). If absent, fall back to Step 0's `sonar` resolution.
- **Existing `mavenPublishing {}` block** (library only): grep `lib/build.gradle.kts` for
  `mavenPublishing {` — presence implies `publish: yes` already at scaffold time; record the `coordinates(...)`
  group/artifact id for the placeholder table (Step 8). Absence doesn't necessarily mean `publish: no` — the
  user may still want CI to publish once the block is added; don't silently downgrade `publish` from what
  `args`/Step 0 resolved, just note the mismatch.
- **Existing signing config** (app only): grep `app/build.gradle.kts` for `signingConfigs` /
  `hasReleaseSigning` — `iru-setup-android-app`'s template reads `SIGNING_STORE_FILE`,
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` from the environment (not from CI
  secrets directly) — the release workflow must therefore *export* those exact env var names from the
  `ANDROID_KEYSTORE_*`/`ANDROID_KEY_*` secrets (Step 4's `main.yml`/`publish.yml` templates do this). If the
  build script instead reads different env var names, note the mismatch and adjust the template.
- **Existing store/distribution plugin** (app only, `distribution: store`): grep `app/build.gradle.kts` for
  `com.github.triplet.play` (Gradle Play Publisher plugin). If present, prefer `./gradlew
  :<app-module>:publishBundle` in the release workflow over the workflow-level `r0adkll/upload-google-play`
  action (Step 4 notes both forms); if absent, use the workflow-level action, which needs no Gradle-side
  wiring at all.
- **Vendored `jacococli.jar`**: `find . -path '*/jacoco-*/lib/jacococli.jar' -maxdepth 4` (or similar). Present
  → this repository still carries the reference repository's older vendored-JaCoCo-CLI layout; the coverage
  conversion step's fallback template (Step 3) applies and should be the one actually emitted. Absent (the
  common case for a fresh `iru-setup-android-library`/`-app` scaffold, per that skill's own verified finding
  that AGP's `createDebugUnitTestCoverageReport` already emits XML directly) → emit only the AGP-path step,
  skip the fallback template entirely rather than generating a step referencing a jar that doesn't exist.
- **`enableUnitTestCoverage = true` per module**: grep `lib/build.gradle.kts`'s and `app/build.gradle.kts`'s
  `buildTypes { debug { ... } }` block for this flag before deciding which module(s) the coverage-conversion
  step (Step 4) targets — confirmed by actually running the task that `flavor: library`'s sample `app/`
  module (`iru-setup-android-library`'s own Step 7 template) does **not** set it, so
  `:<app-module>:createDebugUnitTestCoverageReport` fails outright there (`Cannot locate tasks that match
  '...'`, not a silent no-op) — never emit a coverage task against a module that doesn't enable it.
- **`docs/antora.yml` / `docs/antora-playbook.yml`** (library flavor, or app flavor if it happens to have
  docs): if either is missing, the Antora build step has nothing to build against — recommend running
  `iru-setup-antora` first, or confirm the user will do so before merging this workflow. The docs steps also need `docs/package.json` and the committed `docs/package-lock.json` (CI runs `npm ci` in `docs/`, then `npx pagefind --site build/site`). If `docs/` exists but has no lockfile (a site from the old per-package install), recommend `/iru-update-antora` instead: `actions/setup-node`'s npm cache fails the job without it.
- **Branch names** (skip if supplied via `args`): confirm the integration/stable branch names actually match
  this repository's branching model (`git branch -a`) rather than assuming gitflow defaults; for
  `branching: main-only`, only `stable-branch` matters.
- **README/Antora version-snippet wording** (needed for `sync_versions.py`, gitflow only): grep for the same
  marker text `iru-android-bump-version`'s Step 8 looks for — `grep -n "implementation \['\"]" README.md` and
  `grep -rl "implementation.*[0-9]\+\.[0-9]\+\.[0-9]\+" docs/modules/ROOT/pages/*.adoc`. Note the exact line
  shape found (Groovy `implementation 'group:artifact:version'` vs. Kotlin-DSL
  `implementation("group:artifact:version")`) so Step 5's `sync_versions.py` regex matches it.
- **Existing workflows**: check whether `.github/workflows/develop.yml`, `.github/workflows/main.yml`,
  `.github/workflows/sync.yml`, `.github/scripts/sync_versions.py`, `.github/workflows/publish.yml`,
  `.github/workflows/security.yml`, and `.github/dependabot.yml` already exist (only the subset relevant to
  the resolved `branching`, plus the two always-present files).

### Prior setup — Update / Keep / Replace

Tell the user which of the two this run is doing:

- **Creating from scratch** — none of the relevant files exist: skip straight to Step 2 and create everything.
- **Updating an existing pipeline** — at least one relevant file exists: warn the user, listing each existing
  file by path (files that don't exist yet are still created from scratch alongside). `mode: existing` alone
  doesn't skip this — every pre-existing file is still decided individually.

For each pre-existing file, unless `on-existing` (Step 0) is `keep`/`update`/`replace` — in which case apply it
to every such file without asking, still printing the warning — ask with `AskUserQuestion`, one question per
file (batch up to 4 per call; a further call for the rest). Before asking, compare the file against this run's
resolved choices and name every concrete mismatch in the question, e.g. "your current `develop.yml` boots an
emulator for `connectedAndroidTest` but you chose `ci-tests: unit`", "`main.yml` deploys to GitHub Pages but you
chose `pages: no`", "`develop.yml` still runs `:lib:sonar` but `sonar` is `none`", "`main.yml` publishes to Maven
Central but `publish` is `no`". Options, with the consequence in each description:

- **Update (Recommended)** — "Merge: add what's missing and correct outdated parts (action versions, gated
  steps, stale values) using this skill's template as reference; keep everything this skill doesn't own (custom
  steps/jobs/entries). You review a diff; your customizations survive, but a customized part this skill owns may
  be rewritten."
- **Keep as is** — "Leave the file untouched. Nothing you customized changes, but it may stay out of date or
  disagree with this run's choices (the mismatches named above) — the report will list each one." Recommend
  this instead of Update only for `.github/dependabot.yml` when it already has `gradle` and `github-actions`
  entries.
- **Replace** — "Regenerate from this skill's template. Result matches this house pattern exactly, but every
  customization in the file is lost (recoverable only from git history)." Never recommended by default.
- **Stop** — "End this skill now without changing any file" (offer it on the first question only; choosing it
  for any file means no file is written at all). Report which file(s) already exist and end here.

How each choice is applied in Step 12:

- **Update** — treat the existing file as the base to edit, not something to overwrite wholesale:
  - For `develop.yml`/`main.yml` (gitflow) or `main.yml` (main-only): preserve any step/job this skill doesn't
    own (build/test/lint/coverage/SARIF-upload/optional-Sonar/optional-Dokka-Antora-Pages-or-artifact/optional-
    publish-or-release) — e.g. a Slack notification step, an extra matrix entry, or an additional module's build
    step stays untouched. Only add missing stages or correct outdated ones (wrong action version, wrong task
    name, a stage that should now be gated on/off per `sonar`/`publish`/`distribution`/`run-instrumented-tests`/
    `pages`), and don't reorder foreign steps.
  - For `sync.yml`/`sync_versions.py` (gitflow) or `publish.yml` (main-only): preserve any file the script
    updates beyond `lib/build.gradle.kts`/`app/build.gradle.kts`/`README.md`/the Antora docs that a prior
    customization added, and preserve any extra workflow step the same way.
  - For `security.yml`: preserve any job this skill doesn't own; only add/remove/update the
    dependency-review/CodeQL/OSV-Scanner/gitleaks/OWASP-dependency-check/mobsfscan jobs per the resolved
    `security-*` args.
  - For `.github/dependabot.yml`: preserve any `updates:` entry for an ecosystem this skill doesn't manage
    (e.g. `npm` for `docs/`); only add/update the `gradle` and `github-actions` entries.
  Show the user the diff of each updated file before writing it.
- **Keep as is** — don't write the file; record each mismatch with this run's choices for Step 14's report.
- **Replace** — write the filled-in template from Steps 4–8 over the file.

## Step 2 — Look up action versions at run time

**Versions are looked up at run time, not hardcoded from this file.** Use the GitHub Releases/tags API
(`gh api repos/<owner>/<action>/releases/latest`, or `.../tags` — see the "Floating major tag" column below,
since not every action publishes one) for every `uses:` action major, (the Antora toolchain carries no versions in the workflow: it runs `npm ci` from the committed `docs/package-lock.json`, see `iru-setup-antora`'s `reference/toolchain.md`). If a
lookup is unreachable, fall back to the versions confirmed current as of **September 2026**, listed here
(all confirmed to resolve as an actual `git ls-remote --tags` ref during this skill's own
verification pass — see Step 12 for the two that don't have a floating major tag):

| Action | Fallback version | Floating major tag? | Notes |
|---|---|---|---|
| `actions/checkout` | `v7` | yes | |
| `actions/setup-java` | `v6` | yes | |
| `gradle/actions/setup-gradle` | `v6` | yes | subpath action inside the `gradle/actions` monorepo |
| `reactivecircus/android-emulator-runner` | `v2` | yes | only when `run-instrumented-tests: yes` |
| `EnricoMi/publish-unit-test-result-action` | `v2` | yes | |
| `github/codeql-action` (`init`/`analyze`/`upload-sarif`) | `v4` | yes | resolve via `.../tags`, not `.../releases/latest` — that endpoint returns the unrelated `codeql-bundle-*` tag |
| `actions/upload-pages-artifact` | `v5` | yes | only when `pages: yes` |
| `actions/deploy-pages` | `v5` | yes | only when `pages: yes` |
| `actions/upload-artifact` | `v7` | yes | always for the `Upload UI screenshots` step, and also for `Upload docs artifact` when `pages: no` (replaces the Pages upload/deploy); fallback matches the TypeScript/Swift siblings' — not part of this skill's own September 2026 `git ls-remote` pass, so resolve it live |
| `actions/setup-node` | `v7` | yes | used only for the Antora build and Pagefind index steps (Node 24, which satisfies Antora's Node 20 minimum) |
| `actions/setup-python` | `v7` | yes | used only by `sync.yml`, `branching: gitflow` only |
| `actions/dependency-review-action` | **`v5.0.0`** | **no** | this action has never published a floating `v5` tag — only exact-version tags (`v5.0.0`, `v4.9.0`, …); pin the exact version and re-check at generation time whether a floating major now exists |
| `google/osv-scanner-action` (reusable workflow) | **`v2.6.0`** | **no** | same situation — pin the workflow ref to the exact tag, e.g. `google/osv-scanner-action/.github/workflows/osv-scanner-reusable.yml@v2.7.0` |
| `gitleaks/gitleaks-action` | `v3` | yes | |
| `r0adkll/upload-google-play` | `v1` | yes | `flavor: app`, `distribution: store`, workflow-level upload only |
| `wzieba/Firebase-Distribution-Github-Action` | `v1` | yes | `flavor: app`, `distribution: internal` |

`GITHUB_TOKEN`'s automatic scopes are sufficient for every job above except where a secret is explicitly named
in Step 4/6's templates.

## Step 3 — Reference workflows this skill's templates are derived from

**Templates.** Every template of Steps 4, 5, 7 and 8 is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that generated file's template. Read only the files for the `branching` variant resolved in Step 1, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/develop.yml` | `templates/gitflow/.github/workflows/develop.yml` | `branching: gitflow` |
| `.github/workflows/main.yml` | `templates/gitflow/.github/workflows/main.yml` | `branching: gitflow` (skeleton: copy `develop.yml`'s `build` job steps verbatim where marked) |
| `.github/workflows/sync.yml` | `templates/gitflow/.github/workflows/sync.yml` | `branching: gitflow` only (written together with `sync_versions.py`) |
| `.github/scripts/sync_versions.py` | `templates/gitflow/.github/scripts/sync_versions.py` | `branching: gitflow` only (written together with `sync.yml`) |
| `.github/workflows/main.yml` | `templates/main-only/.github/workflows/main.yml` | `branching: main-only` |
| `.github/workflows/publish.yml` | `templates/main-only/.github/workflows/publish.yml` | `branching: main-only` |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | always, for every `branching` (individual jobs gated by the `security-*` args) |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | always, for every `branching` |

The `main-only` branching variant's `main.yml`/`publish.yml` templates (Step 6) are genericized directly from a
real Android library repository's actual workflows:
<https://github.com/albertoirurueta/irurueta-android-glutils/blob/main/.github/workflows/main.yml> and
<https://github.com/albertoirurueta/irurueta-android-glutils/blob/main/.github/workflows/publish.yml>. That
repository has no `develop.yml` — it pushes/PRs directly against `main` — which is exactly the `main-only`
branching model. The `gitflow` variant's `develop.yml`/`main.yml`/`sync.yml` (Step 4/5) are this skill's own
adaptation of the same pipeline onto two branches, following the same integration/stable-branch split and
`sync.yml` shape this catalog's `iru-setup-java-github-workflows`/`iru-setup-typescript-github-workflows`
already establish for their own ecosystems (see Step 6 for exactly what changed and why).

Every `<placeholder>` below is resolved from Step 1's survey / Step 0's `args` before writing the real files —
the full resolution table is in Step 8. Drop whichever blocks this repository's `flavor`/`sonar`/`publish`/
`distribution`/`run-instrumented-tests` values gate off, per the inline comments in the templates, including
the comment itself — it's authoring guidance, not part of the generated file.

## Step 4 — `develop.yml` / `main.yml` templates (`branching: gitflow`)

`develop.yml` and `main.yml` share the same build/test/lint/coverage/docs pipeline; only the trigger differs
(a push to the integration branch vs. a published GitHub Release), and `main.yml` appends the actual
publish/store step. Both are shown as one annotated template — write `main.yml` by copying every step under
`develop.yml`'s `build` job verbatim, changing only the `on:` block, and appending the release-only steps
marked below.

### `develop.yml`

Template: `templates/gitflow/.github/workflows/develop.yml` — read it in full before filling placeholders

Notes specific to this template:

- Only **one** of the two `Run tests` steps applies per repository (`run-instrumented-tests: yes`/`no`) — keep
  the matching one and drop the other, plus the `Enable KVM` step when `no`.
- Only **one** of the two `Convert unit tests coverage results` steps applies (Step 1's vendored-jar survey) —
  keep the AGP-path step unless a vendored `jacococli.jar` was actually found.
- Only **one** of `Upload Pages artifact` (`pages: yes`, plus the `deploy-docs` job) and `Upload docs artifact`
  (`pages: no`, no `deploy-docs` job and therefore no `pages: write`/`id-token: write` anywhere) applies.
- `<lib-module>:dokkaGenerate` and the app's own lint SARIF upload are both present in the same `Run tests`
  script regardless of `flavor` when both `lib/` and `app/` modules exist (the `iru-setup-android-library`
  sample-app shape) — only drop the `app/**`-scoped steps entirely when no `app/` module exists at all, and
  only drop `<lib-module>:dokkaGenerate`/the Sonar-on-lib/Antora block when no `lib/` module exists at all
  (`flavor: app` with no sample library).
- The `Publish snapshot to Maven Central` step's five `ORG_GRADLE_PROJECT_*` env vars and `--no-configuration-
  cache` flag are carried over verbatim from the reference repository's `publish.yml` (Step 3) — Vanniktech's
  Maven Publish plugin reads Maven Central credentials/signing material from exactly these Gradle project
  properties, and its publish tasks are confirmed incompatible with Gradle's configuration cache.

### `main.yml`

Identical to every step under `develop.yml`'s `build` job above, verbatim — including the same
`run-instrumented-tests`/vendored-jar/`sonar`/`flavor` gating — except the trigger, and the release-only
addition appended at the end of the job:

Template: `templates/gitflow/.github/workflows/main.yml` — read it in full before filling placeholders

Notes specific to this template:

- `<app-module>/release.jks` is written at build time from a base64 secret and is never committed — confirm
  `*.jks` is gitignored (`iru-setup-android-gitignore` already covers this and explicitly anticipates this
  exact path).
- `workflow_dispatch` is added alongside `release: [released]` (the reference repository's `publish.yml` has
  neither `workflow_dispatch` nor a push trigger — this skill adds `workflow_dispatch` for a manual re-run
  without cutting a new release, consistent with the Java/TypeScript siblings' `main.yml`/`release.yml`).

## Step 5 — `sync.yml` + `sync_versions.py` templates (`branching: gitflow` only)

Runs once a GitHub Release is published, provided it was published from the stable branch. Opens a pull
request that merges the released branch back into the integration branch and bumps the development
`-SNAPSHOT` version via the companion `sync_versions.py` script — a direct Gradle port of
`iru-setup-java-github-workflows`'s `sync.yml`/`sync_versions.py` (same branch-merge/PR-creation shell logic,
different version-bump target files), using the exact same rewrite rules `iru-android-bump-version` uses so
the two stay consistent.

### `sync.yml`

Template: `templates/gitflow/.github/workflows/sync.yml` — read it in full before filling placeholders

Notes specific to this template:

- The sync branch is `sync_<released version>` (release `1.0.1` → `sync_1.0.1`). If merging the stable branch
  conflicts, the workflow aborts the merge, points the branch at the release commit, skips the bump and opens the
  PR titled `Sync <release> (merge conflicts to resolve)`. A conflicting sync never bumps, so it cannot lower the
  integration branch's development version.
- This uses `gh pr create` directly (matching the Java sibling's `sync.yml`), not
  `peter-evans/create-pull-request` — the Java/TypeScript siblings both open the sync PR via a hand-rolled
  git+`gh` sequence rather than that action, and this skill follows the same convention for consistency.
- The version bump is a **patch** bump (`x.y.z` → `x.y.(z+1)-SNAPSHOT`), matching this catalog's existing
  Maven `-SNAPSHOT` convention and `iru-android-bump-version`'s own Step 5 documented cadence — not a minor
  bump like the Java sibling's `sync.yml` (Maven's own convention there is a minor bump). If this repository
  does minor/major-level releases too, flag it as an open gap in the final report.
- `CHANGELOG.md` is deliberately not touched here, for the same reason as the Java/TypeScript siblings: its
  release section is written before the tag is published and arrives on `<integration-branch>` via the merge
  step above.
- The `permissions:` block is necessary but not sufficient — the repository's Settings → Actions → General →
  "Workflow permissions" must also allow GitHub Actions to create pull requests, or `gh pr create` fails with a
  permissions error even with the right scopes declared here. Flag this in the final report.
- `git add` only stages `<lib-module>/build.gradle.kts` when a `lib/` module exists, and only
  `<app-module>/build.gradle.kts` when an `app/` module exists (per Step 1's module-layout survey) — drop
  whichever path doesn't apply. The `--ignore-errors` flag (plus the trailing `|| true`) is what lets the same
  step survive a repository with no `README.md` or no `docs/` tree yet: without it `git add` aborts with
  `fatal: pathspec 'README.md' did not match any files` and the whole sync run fails before committing anything.
  Keep the flag even after dropping module paths — the README/docs paths are still optional.

### `sync_versions.py`

Companion script at `.github/scripts/sync_versions.py`. Reads `RELEASE_VERSION`/`NEXT_SNAPSHOT` from the
environment and rewrites every version reference the merge step doesn't already carry forward, using the exact
same rewrite targets and regex shapes as `iru-android-bump-version` (Steps 3/4/8 of that skill), so a manual
`/iru-android-bump-version` run and this CI-driven script never drift apart:

Template: `templates/gitflow/.github/scripts/sync_versions.py` — read it in full before filling placeholders

Notes specific to this template:

- `update_lib_module`/`update_app_module` both no-op cleanly when the matching module doesn't exist, so this
  script is safe to write for either an `iru-setup-android-library`-only, `iru-setup-android-app`-only, or
  combined repository.
- The README regex is the exact `\s*\(?` form `iru-android-bump-version`'s Step 8 verified against both the
  Groovy (`implementation 'group:artifact:version'`) and Kotlin-DSL (`implementation("group:artifact:version")`)
  forms — reuse it verbatim rather than reinventing a stricter pattern.
- `update_antora_component_version` deliberately writes the **release** version, not the next snapshot, to
  `docs/antora.yml` — the Antora component version tracks the latest actual release, matching the Java
  sibling's convention.

## Step 6 — What changed from the reference `main.yml`/`publish.yml`

Recorded explicitly since Step 3 says the `gitflow` templates are this skill's own adaptation, not a literal
copy:

- **Two branches instead of one.** The reference repository triggers its whole pipeline on push/PR to `main`
  and release events, with no second branch. `gitflow`'s `develop.yml` reruns the identical pipeline on every
  push to the integration branch instead, and `main.yml` keeps the release trigger but now sits alongside
  `develop.yml` rather than being the only workflow.
- **`crazy-max/ghaction-github-pages`, pushing to a `gh-pages` branch, replaced by
  `actions/upload-pages-artifact`/`actions/deploy-pages`.** The plan's explicit departure from the reference
  repository — GitHub's own Pages-from-Actions deploy path, matching the Java/TypeScript siblings, instead of
  a branch-push action. Needs the repository's Settings → Pages → "Build and deployment" → Source set to
  **GitHub Actions**, not "Deploy from a branch" (flag in the final report).
- **The vendored-`jacococli.jar` conversion step is now the fallback, not the default.** The reference
  workflow always ran it (against a stale `.exec` path); `iru-setup-android-library`/`-app`'s own verification
  confirmed AGP's `createDebugUnitTestCoverageReport` task emits the XML report directly under the AGP version
  those skills resolve — Step 4's template uses that path by default (see Step 1's survey for when the
  fallback is still the right choice).
- **`lint { sarifReport = true }` is not needed** — confirmed a no-op deprecated property; `lib/build/reports/
  lint-results-debug.sarif` (and the app equivalent) is written unconditionally regardless, matching
  `iru-setup-android-library`/`-app`'s own finding.
- **A `sync.yml` was added.** The reference (`main-only`) repository has no equivalent — there's no second
  branch to sync back into — but `gitflow`'s two-branch model needs one, following this catalog's Java/
  TypeScript pattern.
- **Action versions were bumped** from the reference's `actions/checkout@v4`/`actions/setup-java@v2`/
  `crazy-max/ghaction-github-pages@v2` (its era's current majors) to the versions Step 2 resolves — the
  reference repository's own pins are stale relative to September 2026.
- **`workflow_dispatch` was added to `develop.yml`/`main.yml`'s triggers** — the reference has neither, this
  skill adds it (per this catalog's own convention) for a manual re-run.
- **`Send to Sonarqube` → `Run SonarCloud analysis`, gated by `sonar` instead of always-on** — the reference
  always ran `:lib:sonar` unconditionally; this skill only emits the step when `sonar` isn't `none` (Step 0).
- **`security-events: write` was added to the top-level `permissions:` block** of every workflow that runs
  `github/codeql-action/upload-sarif` (`develop.yml`, `main.yml`, and Step 7's main-only `main.yml`). The
  reference grants only `checks`/`pull-requests`/`contents`, under which the Lint SARIF upload fails with
  "Resource not accessible by integration" — `security.yml` already grants it per job (Step 8).

## Step 7 — `main.yml` / `publish.yml` templates (`branching: main-only`)

For `main-only`, the reference repository's own `main.yml`/`publish.yml` (Step 3) becomes this branching
variant's templates almost unchanged — only the same departures Step 6 lists (Pages-from-Actions instead of
`gh-pages`-branch push, the AGP-first coverage path, the gated Sonar step, bumped action versions,
`run-instrumented-tests`/`flavor` gating) apply. `main.yml` triggers on push/PR to the stable branch (build and
test only — no publish/store step); `publish.yml` triggers on a published release and does the actual
publish/store step, using the exact same release-only block as gitflow's `main.yml` (Step 4).

### `main.yml`

Template: `templates/main-only/.github/workflows/main.yml` — read it in full before filling placeholders

### `publish.yml`

Template: `templates/main-only/.github/workflows/publish.yml` — read it in full before filling placeholders

Notes specific to this variant:

- This is the reference repository's actual permission/trigger shape (`permissions: checks: write,
  pull-requests: write, contents: write` on `main.yml`; `permissions: contents: read` on `publish.yml`),
  preserved except for one addition — `security-events: write` on `main.yml`, without which the
  `github/codeql-action/upload-sarif` Lint steps fail with "Resource not accessible by integration" — plus the
  action versions, Pages mechanism, coverage-conversion default, and Sonar gating changes (Step 6).
- No `sync.yml`/`sync_versions.py` for this branching model — there's only one long-lived branch, so nothing
  needs syncing back. State this plainly in the final report so it doesn't read as an oversight.

## Step 8 — `security.yml` and `.github/dependabot.yml` templates

Generated (or updated) regardless of `branching` — runs on every pull request and push to the integration/
stable branches (or just the stable branch for `main-only`), plus a weekly schedule so CodeQL/OSV-Scanner
findings don't go stale between pushes. Each job is individually omittable via the matching `security-*` arg
(Step 0); drop that whole job's YAML — not just its `if:` — when the resolved value is `no`:

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

Notes specific to this template:

- `build-mode: autobuild` (per the plan's spec) rather than a hand-rolled `./gradlew compile` step like the
  Java sibling's `codeql` job uses — CodeQL's autobuild support for Gradle/Kotlin projects should invoke
  `./gradlew` itself; this is **unverified locally** (Step 12) — if autobuild ever fails against a particular
  module layout, fall back to an explicit `run: ./gradlew :<lib-module>:compileDebugKotlin
  :<app-module>:compileDebugKotlin` build step with `build-mode: manual` instead, the same pattern the Java
  sibling uses.
- `actions/dependency-review-action@v5.0.0` and the OSV-Scanner reusable-workflow ref are pinned to an exact
  tag, not a floating major — see Step 2's table for why.
- None of the four always-on jobs need a secret beyond the automatically provided `GITHUB_TOKEN`. The two
  opt-ins need `NVD_API_KEY` (OWASP Dependency-Check only, and only to raise NVD's public rate limit — the
  scan itself works without it, just slower) — see Step 13's secrets table.
- If `github.com` public-repository default CodeQL setup is simpler for this repository than a workflow-based
  CodeQL job, note that as an alternative in the final report instead of writing the `codeql` job, same as the
  Java/TypeScript siblings.

### `.github/dependabot.yml` template

Always generated (or updated) regardless of the `security-*` args — grouped weekly updates for the `gradle`
ecosystem (the repository's own dependencies/plugins) and for the workflows this skill itself just wrote
(`github-actions`):

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

If `.github/dependabot.yml` already exists with entries for other ecosystems (e.g. `npm` for `docs/`),
preserve them — only add or update the `gradle` and `github-actions` entries above (see Step 1's Update
path).

## Step 9 — Placeholder resolution table

| Placeholder | Resolved from |
|---|---|
| `<integration-branch>` / `<stable-branch>` | Step 1 branch survey / `args` |
| `<java-version>` | Step 0 (`args`, default `17`) |
| `<api-level>` | Step 0 (`args`, default `35`) |
| `<lib-module>` / `<app-module>` | Step 1's `settings.gradle.kts` `include(...)` survey — almost always `lib`/`app` |
| `<application-id>` | `app/build.gradle.kts`'s `defaultConfig.applicationId`, `distribution: store` only |
| `<jacoco-cli-version>` | The vendored `jacoco-<ver>/lib/jacococli.jar` directory name found in Step 1's survey — only needed when that fallback template is emitted |
| `<sonar-organization>` / `<sonar-project-key>` / `<sonar-host-url>` | Step 0/1 Sonar survey / `args`; only needed when `sonar` isn't `none` |
| `<artifact-id>` | `lib/build.gradle.kts`'s `mavenPublishing { coordinates(...) }` (library) or the app's project name (OWASP Dependency-Check `project:` field) |
| `<owner>` / `<repo>` | The current repository's `git remote` (for report text only — no template above embeds a real repo/org name) |

Apply the `flavor`/`sonar`/`publish`/`distribution`/`run-instrumented-tests`/`pages`/`branching`/vendored-jar gating
resolved in Steps 0–1: drop every block the templates above mark with an "Omit this … if …" comment (and that
comment itself) when the matching value doesn't apply.

## Step 10 — Conditional-blocks table

| Block | Appears when |
|---|---|
| `Enable KVM` + emulator-based `Run tests` step | `run-instrumented-tests: yes` |
| Plain (non-emulator) `Run tests` step | `run-instrumented-tests: no` |
| `<lib-module>:dokkaGenerate` in the test script, Sonar-on-lib, Antora/Dokka merge | a `lib/` module exists (library flavor, or a library+sample-app combined repo) |
| `Upload Pages artifact` step + `deploy-docs` job (with its `pages: write`/`id-token: write`) | a `lib/` module exists and `pages: yes` |
| `Upload docs artifact` (`actions/upload-artifact`) step, no `deploy-docs` job | a `lib/` module exists and `pages: no` |
| `<app-module>/**` test-result/SARIF globs, `:<app-module>:sonar` | an `app/` module exists |
| `Upload UI screenshots` (`actions/upload-artifact`, `if: always()`, 7-day retention, `if-no-files-found: ignore`) | always — in the same `build` job that runs the tests, no extra job or runner; it simply uploads nothing when no UI test captured anything |
| AGP-path coverage-conversion step targeting `<lib-module>` (`flavor: library`) or `<app-module>` (`flavor: app`) | default — no vendored `jacococli.jar` found (Step 1); targets only the module with `enableUnitTestCoverage = true` (Step 1 survey) — never both |
| Vendored-`jacococli.jar` fallback conversion step | a `jacoco-*/lib/jacococli.jar` was found, or the user asked for it |
| `Run SonarCloud analysis` step (`:<lib-module>:sonar` or `:<app-module>:sonar`) | `sonar` is `cloud` or `self-hosted` |
| `Publish snapshot to Maven Central` (develop.yml) / `Release build` + `Publish to Maven Central` (main.yml/publish.yml) | `flavor: library`, `publish: yes` |
| `Decode Android keystore` + `Build signed release bundle` + store/Firebase upload | `flavor: app`, `distribution` isn't `none` |
| `Upload to Google Play` (`r0adkll/upload-google-play`) vs. `./gradlew :<app-module>:publishBundle` | `distribution: store` — action if no `com.github.triplet.play` plugin found (Step 1), Gradle task if found |
| `Distribute via Firebase App Distribution` | `distribution: internal` |
| `sync.yml` + `sync_versions.py` | `branching: gitflow` only |
| `owasp-dependency-check` job | `security-owasp-dependency-check: yes` |
| `mobsfscan` job | `security-mobsfscan: yes`, app flavor only |

## Step 11 — File matrix

| `branching` × `flavor` | Files written |
|---|---|
| `gitflow` / `library` | `.github/workflows/develop.yml`, `main.yml`, `sync.yml`, `.github/scripts/sync_versions.py`, `security.yml`, `.github/dependabot.yml` |
| `gitflow` / `app` | Same six files (the release job inside `main.yml` differs internally per Step 4) |
| `main-only` / `library` | `.github/workflows/main.yml`, `publish.yml`, `security.yml`, `.github/dependabot.yml` |
| `main-only` / `app` | Same four files (the release job inside `publish.yml` differs internally per Step 7) |

## Step 12 — Write the files

Write every file from Step 11's matrix with the filled-in templates from Steps 4–8 — or, for a file that already
existed, apply Step 1's per-file choice (Update: carefully merge; Replace: overwrite; Keep as is: leave
untouched). `sync.yml` and `sync_versions.py` are written together — never write one
without the other, and never write either for `main-only`. `security.yml` and `.github/dependabot.yml` are
always written (or updated), independent of `flavor`/`branching`/`publish`/`distribution`/`sonar` — only their
own `security-*` args gate individual jobs within `security.yml`, and `.github/dependabot.yml` has no gate at
all.

## Step 13 — Known quirks / verification notes

Recorded from this skill's own verification pass, run entirely in `$TMPDIR/iru-verify/android/workflows/`
(created fresh, outside this repository) — never a blocker, folded into the templates above where a template
change was warranted:

- **`actions/dependency-review-action` and the `google/osv-scanner-action` reusable workflow have no floating
  major tag.** Every other action in Step 2's table resolves a `git ls-remote --tags`-confirmed floating major
  (`v7`, `v6`, `v2`, `v5`, `v3`, `v1`, …) — these two only publish exact-version tags (`v5.0.0`, `v2.6.0`, …
  confirmed via `git ls-remote --tags` returning zero matches for a bare `v5`/`v2` ref, while the exact tag
  resolves). Step 2's/Step 8's templates pin the exact resolved version for both; re-run the same
  `git ls-remote --tags https://github.com/<owner>/<repo> <tag>` check at generation time in case either has
  since published a floating major.
- **The GitHub REST `/tags` endpoint can miss a floating major tag that genuinely exists.**
  `reactivecircus/android-emulator-runner` and `EnricoMi/publish-unit-test-result-action` both looked
  tag-less for `v2` via `gh api repos/<owner>/<repo>/tags` (only full `v2.NN.0`-style tags came back), but
  `git ls-remote --tags https://github.com/<owner>/<repo> v2` confirmed the floating `v2` ref does exist for
  both. Prefer `git ls-remote --tags` over the REST `/tags` listing when confirming whether a floating major
  tag exists — the REST endpoint's pagination/ordering isn't reliable for this.
- **`develop.yml`/`main.yml`'s docs steps run in `docs/` from the committed lockfile.** `Install docs toolchain` is
  `npm ci` with `working-directory: docs`, followed by `npx antora -v`, the build and `npx pagefind --site build/site`
  (the single CI snippet in `iru-setup-antora`'s `reference/toolchain.md`). The old `mkdir -p docs-toolchain` install
  does not work as a toolchain: `npx` in `docs/` does not see a sibling directory's `node_modules`, so the playbook's
  extensions are not found and the Antora version is whatever `latest` is that day.
- **YAML-validated every rendered file** (all four `branching`×`flavor` combinations, `run-instrumented-tests:
  yes` for the library variant, `no` for the app variant, `sonar: cloud`, `publish: yes`/`distribution:
  store`) with `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))"` — every file parsed cleanly.
  As with the TypeScript sibling's own note: PyYAML round-trips the top-level `on:` key as the boolean `True`
  under YAML 1.1's bare-word resolution, not the string `"on"` GitHub's own parser expects — this is a false
  flag in the validator, not a defect in the generated workflow; don't quote `on:` in the template to "fix" it.
- **`sync_versions.py` was exercised against copies of `iru-setup-android-library`'s own scaffold** —
  `1.0.0-SNAPSHOT` → release `1.0.0` → next `1.0.1-SNAPSHOT`-shaped inputs (the patch bump `sync.yml`'s
  `versions` step actually computes — `${MAJOR}.${MINOR}.${NEXT_PATCH}-SNAPSHOT`) correctly rewrote
  `lib/build.gradle.kts`'s `val libraryVersion`, `app/build.gradle.kts`'s `versionName`, and a sample
  README/`antora.yml` copy's version rows/`version:` field, `py_compile`-clean. `versionCode` was correctly
  left untouched (the script never writes anything but a `-SNAPSHOT` value into `NEXT_SNAPSHOT`, and
  `update_app_module` has no `versionCode` logic at all, by design — see the template's own comment).
- **`replace_dependency_snippets`'s marker match only fires on a *bare* `Latest release`/`Latest snapshot`
  line, not on one embedded inside a Markdown table row.** Confirmed by first testing against a README with
  the dependency snippet placed directly under the status-table's own `| Latest release | ... |` row (no
  separate bare marker) — the snippet was silently left at its old version, no error. Re-tested against the
  shape `iru-setup-readme` actually generates (the status table's `Latest release` **row** handled by the
  separate row-label regex, plus a distinct `## Installation` section with its own bare `Latest release` line
  immediately before the snippet) and both mechanisms fired correctly. This is inherited unchanged from the
  Java/TypeScript siblings' own `sync_versions.py` — not a regression introduced here — but confirm a
  repository's actual README puts a bare marker line ahead of its dependency snippet (not just inside the
  status table) before trusting this script's Installation-section rewrite.
- **Gradle steps run against the generated `iru-setup-android-library` scaffold at `$TMPDIR/iru-verify/android/library`**: `export
  JAVA_HOME=$(/usr/libexec/java_home -v 21)` (JDK 21 preferred over the machine's newer default, per this
  catalog's own Android-skill convention); `./gradlew test lint lib:dokkaGenerate` (first invocation, no
  `--offline`) succeeded and confirmed every path this skill's templates reference:
  `lib/build/test-results/testDebugUnitTest/*.xml`, `lib/build/reports/lint-results-debug.sarif`,
  `lib/build/dokka/html/index.html`. `./gradlew :lib:createDebugUnitTestCoverageReport` then confirmed
  `lib/build/reports/coverage/test/debug/report.xml` and the raw
  `lib/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` both exist at exactly the
  paths Step 4's AGP-path template uses — matching `iru-setup-android-library`'s own Step 11 finding exactly
  (this skill's authoring didn't need to rediscover it independently). `./gradlew :app:testDebugUnitTest
  :app:lint` against the same scaffold's sample `app/` module confirmed
  `app/build/reports/lint-results-debug.sarif` is written the same way (`Wrote SARIF report to
  .../app/build/reports/lint-results-debug.sarif`); `:app:testDebugUnitTest` itself printed `NO-SOURCE` and
  wrote no `test-results/` at all, because `iru-setup-android-library`'s own sample `app/` module (Step 7 of
  that skill) has no `src/test` directory — confirmed harmless (the test-result glob in Step 4's
  `Publish test results` step simply matches nothing for that module, `EnricoMi/publish-unit-test-result-
  action` tolerates an empty glob), but means the app-module test-results path itself was **not**
  independently re-confirmed here beyond `iru-setup-android-app`'s own Step 7 verification (a real, standalone
  app repository, unlike this sample module).
- **`:<app-module>:createDebugUnitTestCoverageReport` is a hard failure, not a no-op, against
  `iru-setup-android-library`'s own sample `app/` module** — confirmed by actually running it:
  `FAILURE: Build failed with an exception. * What went wrong: Selection failed. Cannot locate tasks that
  match ':app:createDebugUnitTestCoverageReport' as task 'createDebugUnitTestCoverageReport' not found in
  project ':app'.` That module's `buildTypes { debug { ... } }` block never sets `enableUnitTestCoverage =
  true` (only `lib/build.gradle.kts` does — see `iru-setup-android-library`'s Step 6 template) — an earlier
  draft of this skill's `Convert unit tests coverage results` step ran the task against **both**
  `<lib-module>` and `<app-module>` unconditionally, which would have broken every `flavor: library`
  `develop.yml`/`main.yml` run generated against that exact scaffold shape. Fixed before this file was
  finalized: Step 4's template now targets `<lib-module>` only for `flavor: library` (`<app-module>` only for
  `flavor: app`, per `iru-setup-android-app`'s own template, which DOES set the flag) — see Step 1's survey
  and Step 10's conditional-blocks table.
- **The vendored-`jacococli.jar` fallback conversion command was run for real**, against a downloaded
  `org.jacoco.cli-0.8.13-nodeps.jar` (from `repo1.maven.org`, since neither scaffold vendors one — no
  `~/.gradle/caches` copy was found locally either) and the `.exec`/class-file paths confirmed above: `java
  -jar org.jacoco.cli-0.8.13-nodeps.jar report lib/build/outputs/unit_test_code_coverage/debugUnitTest/
  testDebugUnitTest.exec --classfiles lib/build/tmp/kotlin-classes/debug --sourcefiles lib/src/main/java --xml
  <out>/report.xml` produced a valid JaCoCo-format XML report — confirming the fallback template's command
  line is correct, not just plausible.
- **The Antora copy step was verified at the shell-command level only** (`mkdir -p`/`cp -r` against the
  scaffold's real `lib/build/dokka/html/` output into a throwaway `docs/build/site/api/` directory, confirming
  the copy succeeds and lands `index.html` where expected) — a full `cd docs && npx antora
  antora-playbook.yml` run was **not** repeated here (the scaffold's own `docs/` setup and Antora build are
  `iru-setup-antora`'s/the scaffold skills' responsibility to verify, not this skill's).
- **`Upload UI screenshots` (UI contract), October 2026**: the `develop.yml` template still parses as YAML with the
  step added (`yaml.safe_load`, step present with `if: always()`, `retention-days: 7`, `if-no-files-found:
  ignore`), `actions/upload-artifact@v7` resolves (`git ls-remote --tags`), and the scaffolds' Robolectric UI tests
  were confirmed to write `.ui-screenshots/` at the repository root during a plain `./gradlew test`
  (`iru-setup-android-app`'s verification) — so the step needs no extra Gradle invocation. The upload itself only
  runs on GitHub; the device-capture path under `connected_android_test_additional_output/` is unverified (no
  emulator).
- **Unverified locally, never a blocker**: the emulator-based `Run tests` step (`reactivecircus/android-
  emulator-runner`, needs real KVM-backed hardware acceleration this environment doesn't reliably provide for
  a full `connectedAndroidTest` run), the `Run SonarCloud analysis` step (needs a real `SONAR_TOKEN` and
  SonarCloud project), Maven Central publishing (needs real signing/publishing credentials), the Google
  Play/Firebase App Distribution uploads (need real store/Firebase credentials), CodeQL actually analyzing this
  project (`build-mode: autobuild` against a real Gradle/Android module was not run through the CodeQL CLI
  locally), and both opt-in jobs (OWASP Dependency-Check, `mobsfscan`) beyond confirming their action/CLI
  names are plausible.

## Step 14 — Report

Summarize what happened: whether this run created the pipeline from scratch or updated an existing one, and,
per file in Step 11's matrix, the action actually taken — **created / updated / replaced / kept as is** (or
nothing written, if the user chose Stop) — and whether it came from the user's answer or `on-existing`; for every
file kept as is, list each mismatch with this run's choices (Step 1); any open gaps noted along the way (missing `docs/antora.yml`, a
`com.github.triplet.play` plugin found and used instead of the workflow-level upload action, README/Antora
wording that didn't match `sync_versions.py`'s default regexes, a vendored `jacococli.jar` found and used
instead of the AGP-path default, any shared-`args` key supplied but unused by this skill).

State explicitly what was wired versus omitted, and why:

- **`flavor`**: `library` or `app` — which pipeline shape (Dokka/Antora/Sonar-on-lib vs. Sonar-on-app/
  signing-and-store) was generated.
- **`branching`**: `gitflow` (`develop.yml`/`main.yml`/`sync.yml`) or `main-only` (`main.yml`/`publish.yml`,
  no `sync.yml` — state this omission plainly).
- **`visibility`**: `public`/`private`/`internal`, and whether it came from `args`, `gh repo view`, or a question.
- **`run-instrumented-tests`**: yes/no — which `Run tests` step variant was used, and whether the `Enable KVM`
  step was included; say whether it came from an explicit `run-instrumented-tests`, from `ci-tests`
  (`full`/`unit`), or from the standalone question.
- **`pages`** (library only): `yes` — Dokka + Antora deployed to GitHub Pages via `deploy-docs`; `no` — docs
  still built but only uploaded as the `docs-site` workflow artifact, no `deploy-docs` job, no Pages
  permissions. State whether `yes` was the public-repository default or a user choice on a private repository
  (and, if so, the plan requirement and the site's public visibility).
- **`sonar`**: `cloud`/`self-hosted`/`none` — if not `none`, the Sonar step and `SONAR_TOKEN` row were
  included; if `none`, both were omitted, and note whether that's because the project isn't open source or a
  direct user choice.
- **`publish`** (library only) / **`distribution`** (app only): yes/no or `none`/`internal`/`store` — which
  publish/store jobs and secret rows were generated versus omitted.
- **Security block**: which of the four always-on `security-*` jobs and the two opt-ins were included versus
  omitted, and confirm `.github/dependabot.yml` was written regardless (it has no opt-out).
- **Coverage-conversion path**: AGP-direct (default) or the vendored-`jacococli.jar` fallback, and why.

Then give the required-secrets/environments table — list only the rows that apply to what was actually
generated (an omitted row is one fewer secret the user needs to create):

| Secret | Purpose | Only needed when |
|---|---|---|
| `SONAR_TOKEN` | Auth token for the SonarQube/SonarCloud scan | `sonar` is `cloud` or `self-hosted` |
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | Maven Central Publishing Portal credentials | `flavor: library`, `publish: yes` |
| `SIGNING_MEMORY_KEY` / `SIGNING_MEMORY_KEY_ID` / `SIGNING_IN_MEMORY_KEY_PASSWORD` | In-memory GPG signing key, its id, and its passphrase, for Vanniktech's Maven Publish plugin | `flavor: library`, `publish: yes` |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore, decoded to `<app-module>/release.jks` at build time | `flavor: app`, `distribution` isn't `none` |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | Exported as `SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/`SIGNING_KEY_PASSWORD` — the exact env vars `iru-setup-android-app`'s `app/build.gradle.kts` reads via `System.getenv()` | `flavor: app`, `distribution` isn't `none` |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service-account JSON for `r0adkll/upload-google-play`'s `serviceAccountJsonPlainText` input | `flavor: app`, `distribution: store`, workflow-level upload (no Gradle Play Publisher plugin found) |
| `FIREBASE_APP_ID` / `FIREBASE_SERVICE_CREDENTIALS` | Firebase App Distribution upload | `flavor: app`, `distribution: internal` |
| `NVD_API_KEY` | Raises the NVD lookup rate limit for OWASP Dependency-Check (the scan runs without it, just slower) | `security-owasp-dependency-check: yes` |
| `GITHUB_TOKEN` | `sync.yml`'s `gh` calls, `security.yml`'s CodeQL/gitleaks jobs | Automatic — no setup needed |

`sync.yml` also needs the repository setting under Settings → Actions → General → "Workflow permissions" set
to allow GitHub Actions to create pull requests — the `permissions:` block in the workflow itself is not
sufficient on its own. With `pages: yes`, the Pages deploy jobs need Settings → Pages → "Build and deployment" →
Source set to **GitHub Actions** (and, for a private/internal repository, a GitHub Pro/Team/Enterprise Cloud plan;
the site is public unless Enterprise Cloud private access control is used); with `pages: no` there is no Pages
setting to change. Call all of this out explicitly, since each is easy to miss and fails on the very first
real run without it.

**Warn explicitly:**

- **The user must review every generated (or updated) workflow file before relying on it.** Branch names,
  module names, and the store/signing/publish setup were inferred from this repository's current state and
  may need correction — and because this pipeline handles signing keys and publishes/distributes artifacts
  publicly, a bad assumption here has real consequences. Recommend a dry run via `workflow_dispatch` before
  trusting it on a real release.
- Every secret in the table above, the matching SonarCloud project / Maven Central Publishing Portal
  registration / Google Play Console service account / Firebase project, must already exist before CI can
  pass — this skill only writes the workflow YAML, it never creates any of those accounts or credentials
  itself.
- Which toolchain versions (Step 2) came from a live lookup versus this skill's recorded September 2026
  fallback, and specifically that `actions/dependency-review-action`/`google/osv-scanner-action`'s reusable
  workflow are pinned to an exact tag rather than a floating major (Step 13) — re-check both at the next
  regeneration in case a floating major has since been published.
- **Unverified locally, and never a blocker** (Step 13): the emulator-based instrumented-test run, the actual
  SonarCloud scan, Maven Central publishing, the Google Play/Firebase App Distribution uploads, CodeQL
  actually analyzing this project, and both opt-in security jobs beyond a plausibility check of their
  action/CLI names.
