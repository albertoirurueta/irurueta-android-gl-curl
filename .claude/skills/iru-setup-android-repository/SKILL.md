---
name: iru-setup-android-repository
description: "Invoke as /iru-setup-android-repository [key: value args]. End-to-end bootstrap of an Android/Gradle library or app repository: asks flavor, branching, identity and shared pipeline inputs once, then chains scaffold, Antora docs, gitignore, GitHub workflows, changelog and README via iru-isolated-skill-executor. Use to create or catch up a full Android repository setup in one pass instead of running six skills separately."
model: sonnet
---

# Setup Android Repository

Bootstrap a brand-new (or catch up an existing) Android/Gradle repository in one pass, for either of its two
flavors, by orchestrating six existing skills: the flavor's own scaffold skill (`iru-setup-android-library` — a
`lib/` library module plus a Compose `app/` sample module — or `iru-setup-android-app` — a single `app/`
application module), `iru-setup-antora` (Antora documentation site), `iru-setup-android-gitignore` (root and
per-module `.gitignore`), `iru-setup-android-github-workflows` (CI/CD + security workflows), `iru-setup-changelog`
(root `CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does not duplicate any of their logic
— it collects the shared parameters once and passes each skill only the `args` keys that skill's own Step 0
actually accepts, so the user isn't asked the same question repeatedly and no sub-skill sees a key it doesn't
recognize.

Each of Steps 4–9 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt, e.g.
`Skill({skill: "iru-setup-android-library", args: "..."})`) rather than calling `Skill(...)` directly — every
sub-skill re-derives whatever it needs from the filesystem this orchestrator has just written to, so only a short
completion summary needs to flow back, keeping all six sub-skills' full transcripts (and the Android scaffold's
very long template listings) out of this orchestrator's own context. Every sub-agent is launched with
`run_in_background: false` — each step depends on the previous one's files being on disk.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-android-repository`) or driven by another orchestrator (the
catalog's `iru-setup-repository` front door), so parse `args` first, as `key: value` lines, one per line, e.g.:

```
flavor: library
branching: gitflow
mode: new
open-source: yes
visibility: public
ci: yes
ci-tests: full
pages: yes
publish: yes
accessibility: enforce
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_my-android-library
sonar-host-url: https://sonarcloud.io
integration-branch: develop
stable-branch: main
java-version: 17
min-sdk: 26
api-level: 35
```

Recognized keys:

- **Flavor and mode**: `flavor` (`library`/`app`), `branching` (`gitflow`/`main-only`), `mode` (`new`/`existing`).
- **Shared pipeline vocabulary**: `open-source` (`yes`/`no`), `visibility` (`public`/`private`/`internal`), `ci`
  (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `on-existing` (`ask`/`keep`/`update`/`replace` —
  never asked; forwarded to the component sub-skills only when it arrived here via `args`), `publish` (`yes`/`no` —
  `library` only),
  `distribution` (`none`/`internal`/`store` — `app` only), `accessibility` (`enforce`/`report`/`off`/`none` — both
  flavors; forwarded to the scaffold skill, which writes it to `.ui-quality.yml`), `sonar` (`cloud`/`self-hosted`/`none`) plus, only when
  set, `sonar-organization`/`sonar-project-key`/`sonar-host-url`, `integration-branch`, `stable-branch`,
  `java-version`, `min-sdk`, `compile-sdk`, `api-level`, `run-instrumented-tests` (`yes`/`no` — the workflows
  skill's own key; when supplied it wins over the `ci-tests` mapping in Step 3).
- **Identity fields** named in Step 2: `group-id`, `artifact-id`, `namespace`, `description`, `version`
  (`library`); `application-id`, `namespace`, `app-name`, `version-name`, `version-code` (`app`); and, for both,
  `license`, `developer-name`, `developer-email`, `organization-url`.
- **Scaffold toolchain toggles**, passed through untouched if supplied but never asked by this orchestrator (see
  Step 2): `built-in-kotlin`, `junit`, `coverage-tool`, `static-analysis`, and `ui` (`app` only).

Every key found here is resolved — skip the matching question in Steps 1–3. Only keys genuinely missing from
`args` still need asking. If `args` is absent or doesn't look like this format, treat everything as unset and ask
normally.

## Step 1 — Resolve the flavor, branching model and mode

Survey the repository root first — `settings.gradle.kts`, `lib/build.gradle.kts`, `app/build.gradle.kts` — since
their presence decides both `flavor` and `mode` without a question in the common cases.

Unless `flavor` was already supplied via `args`, resolve it:

- `lib/build.gradle.kts` exists and applies `com.android.library` → `library` (whether or not a sample `app/`
  module also exists); `app/build.gradle.kts` exists and applies `com.android.application` with no
  `lib/build.gradle.kts` → `app`. Confirm the detected value with the user rather than assuming it silently.
- Nothing on disk yet, or genuinely ambiguous → `AskUserQuestion` with two options: **Library** (a redistributable
  `lib/` module published as an AAR, plus a Compose sample `app/` module that depends on it) / **App** (a single
  `app/` application module installed on devices or uploaded to a store, never published to Maven Central).

Map the resolved `flavor` to its scaffold skill for the rest of this run:

| `flavor` | Scaffold skill | Scaffold manifest checked in Step 4 |
|---|---|---|
| `library` | `iru-setup-android-library` | `settings.gradle.kts` (+ `lib/build.gradle.kts`) |
| `app` | `iru-setup-android-app` | `settings.gradle.kts` (+ `app/build.gradle.kts`) |

Unless `branching` was already supplied via `args`, resolve it with `AskUserQuestion` (two options):

- **gitflow** (recommended, this catalog's house pattern) — an integration branch (`develop`, carrying a
  `-SNAPSHOT` version) plus a stable branch (`main`, releases only); `iru-setup-android-github-workflows` generates
  `develop.yml`/`main.yml`/`sync.yml`, and `sync.yml` opens the post-release version-bump PR back into the
  integration branch.
- **main-only** — a single stable branch; `iru-setup-android-github-workflows` generates `main.yml`/`publish.yml`
  and no `sync.yml`. Step 3's integration-branch question is skipped for this model.

If `mode` wasn't supplied via `args`, don't ask about it as an isolated question — check whether
`settings.gradle.kts` (or any other artifact in the inventory below) already exists: if `settings.gradle.kts` does,
default to `existing` and confirm with `AskUserQuestion` (`existing` pre-selected; `new` as the other option,
noting that either way every sub-skill still asks Update / Keep as is / Replace for each file it owns that already
exists — `mode` only tells them whether to expect prior setup); if nothing exists, default to `new` without asking
further — there's nothing on disk yet to conflict with. Any owned artifact present (not just the build manifest)
means the run is an update for that component, whatever `mode` says.

### Setup mode

Before asking anything else (right after resolving `mode`), print a short **Setup mode** statement:

- `mode: new` → "Setting up this repository **from scratch** — no prior setup found."
- `mode: existing` → "**Updating an existing repository** — prior setup found:" followed by an inventory table of
  every component this run owns, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence checked |
  |---|---|
  | Gradle build | `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, the wrapper |
  | Modules | `lib/build.gradle.kts` + `lib/src/`, `app/build.gradle.kts` + `app/src/` |
  | Sonar config | a `sonar {` block in `lib/build.gradle.kts`/`app/build.gradle.kts` |
  | `.ui-quality.yml` | root `.ui-quality.yml` (UI-test accessibility level and screenshot matrix, owned by the scaffold skill) |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` |
  | `.gitignore` | root `.gitignore`, `lib/.gitignore`, `app/.gitignore` |
  | Workflows | each of `.github/workflows/develop.yml`/`main.yml`/`sync.yml`/`publish.yml`/`security.yml`, `.github/scripts/sync_versions.py` |
  | Dependabot | `.github/dependabot.yml` |
  | `CHANGELOG.md` | root `CHANGELOG.md` |
  | `README.md` | root `README.md` |
  | `LICENSE` | root `LICENSE*` (reported only — no sub-skill here writes it) |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it."
  Those questions are asked by the component sub-skills themselves (Steps 4–9) — this orchestrator never asks them
  too, so nothing is asked twice.

## Step 2 — Collect the project's identity

**`mode: existing` with the scaffold manifest already on disk** (`settings.gradle.kts` at the repository root):
skip every identity question in this step — the scaffold skill reads its defaults from the existing
`lib/build.gradle.kts`/`app/build.gradle.kts` itself when the user chooses Update or Replace there, and asks only
what it still can't derive; `iru-setup-readme` derives identity from the existing build files too — so asking them
here is a wasted question (verified against an existing repository). Resolve `license` only if `args` supplied it,
and record in the final report that project identity was taken from the existing files rather than asked.

For any field Step 0 already resolved from `args`, use that value directly. For everything else, ask the user
directly (plain conversation — these are free-text project-identity fields, not a bounded choice). Only ask what
applies to the resolved `flavor`, using **exactly** the key names the scaffold skill reads:

- **`library`**: **group-id** (the Maven Central `groupId`, e.g. `com.example`), **artifact-id** (the
  `artifactId` and `rootProject.name`, e.g. `my-android-library`), **namespace** (the Android `namespace` and base
  Kotlin package, e.g. `com.example.myandroidlibrary` — need not equal `group-id`; the sample `app/` module's
  namespace is always `<namespace>.app`, so don't ask for it), **description** (one line, for the published
  `pom`). Leave **version** unset unless `args` supplied it — `iru-setup-android-library` applies its own
  `1.0.0-SNAPSHOT` default, which is what the gitflow `sync.yml` expects on the integration branch.
- **`app`**: **application-id** (reverse-DNS, e.g. `com.example.myapp`), **namespace** (offer `application-id`
  as the default rather than asking open-endedly), **app-name** (the launcher label). Leave **version-name**/
  **version-code** unset unless `args` supplied them — `iru-setup-android-app` defaults them to `1.0.0`/`1`.
- **Both**: **developer name**, **developer email**, **organizationUrl**. For a library they populate the
  published `pom`'s `developers` block; for an app they're only recorded in the scaffold's report — still worth
  collecting once here so `iru-setup-readme` and any later `iru-check-license` run see consistent authorship.

Then resolve the **license** with `AskUserQuestion`, unless `args` supplied one (one consistent choice reused
across both flavors, matching the four options every other setup skill in this catalog offers; the two scaffold
skills merely differ on which they recommend first — Apache for a library, MIT for an app — so present the
recommendation that matches the resolved `flavor`):

- Apache License 2.0 (recommended for `library`) — `Apache License 2.0` / `http://www.apache.org/licenses/LICENSE-2.0.txt`
- MIT License (recommended for `app`) — `The MIT License` / `https://opensource.org/license/mit`
- No license (proprietary / all rights reserved)
- Other — ask for the license's display name and URL directly afterward

The scaffold toolchain toggles — **`built-in-kotlin`**, **`junit`**, **`coverage-tool`**, **`static-analysis`**,
and (`app` only) **`ui`** — are **deliberately left unasked here**: nothing downstream of the scaffold needs them
as inputs (`iru-setup-android-gitignore` detects Detekt/ktlint from `detekt.yml` and the version catalog, and
`iru-setup-android-github-workflows` reads `enableUnitTestCoverage`/the `sonar {}` block straight from the module
build files), so the scaffold skill asks them itself with its own defaults (`no`/`4`/`jacoco`/`lint`/`compose`)
when invoked without them. Pass them through only if `args` supplied them.

## Step 3 — Collect the pipeline parameters

Ask the user directly, presenting each with its default so a plain "yes"/blank reply accepts it (skip any Step 0
already resolved from `args`, skip the integration branch entirely when `branching: main-only`, and skip every
row consumed only by `iru-setup-android-github-workflows` — branches, Java version, emulator API level — once
`ci: no` is resolved below; if `ci` is still unresolved, ask the open-source/visibility/`ci` questions first):

| Parameter | `args` key | Default | Consumed by |
|---|---|---|---|
| Integration branch | `integration-branch` | `develop` | `iru-setup-android-github-workflows` (gitflow only) |
| Stable branch | `stable-branch` | `main` | `iru-setup-android-github-workflows` |
| Java version | `java-version` | `17` | `iru-setup-android-github-workflows` (`actions/setup-java`) |
| Min SDK | `min-sdk` | `26` | `iru-setup-android-library` / `iru-setup-android-app` |
| Emulator API level | `api-level` | `35` | `iru-setup-android-github-workflows` (`reactivecircus/android-emulator-runner`) — only needed when instrumented tests run in CI |

Notes on these, so the two halves of the pipeline can't disagree:

- **Java version** is a CI-only parameter here: neither scaffold skill accepts a `java-version` key — both write
  `sourceCompatibility`/`jvmTarget` 17 from their templates, which is exactly why the workflows skill's default is
  `17`. If the user overrides it, warn that the scaffold's Gradle files still target 17 and would need a matching
  hand edit (Step 10 repeats this).
- **`compile-sdk`** is *not* asked here: `iru-setup-android-library`/`-app` resolve it themselves from the locally
  installed SDK platforms (coupled with the Compose BOM lookup — see their Step 2), which this orchestrator can't
  do better. Pass it through only if `args` supplied it. The emulator **API level** is independent of `compileSdk`
  (an emulator image must exist for it — `35` is the verified default) and only feeds the workflows.
- **Instrumented tests in CI** are no longer a separate row: they follow `ci-tests` below (`full` ⇒
  `run-instrumented-tests: yes`, `unit` ⇒ `no`), unless `run-instrumented-tests` was explicitly supplied via
  `args`, which always wins.

Then resolve, with `AskUserQuestion`, per this catalog's shared open-source- and visibility-driven convention, in
this order (skip any Step 0 already resolved). Quote the cost facts "per GitHub's current pricing" (verified
October 2026 against docs.github.com) and tell the user to double-check their own plan:

- **open-source** — is this repository open source? Yes / No. Drives the recommended defaults below.
- **visibility** — if the repository has a GitHub remote, run `gh repo view --json visibility -q .visibility`
  (returns `PUBLIC`/`PRIVATE`/`INTERNAL`; lowercase it) and tell the user the detected value. No remote yet /
  `gh` unavailable / not GitHub → ask **Public** / **Private**, recommending Public when open-source is Yes,
  otherwise Private. If open-source is Yes but `visibility` is `private`/`internal`, warn that this is
  contradictory (an open-source project's repository is normally public; a private repository forfeits the
  free-for-public pricing below) and ask which is right. `internal` behaves like `private` everywhere below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?"
  - Public: recommend **Yes** — standard GitHub-hosted runners are free for public repositories.
  - Private/internal: still offer **Yes** first, but state that private/internal repositories consume the
    account's included minutes — 2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud —
    then are billed per minute (Linux 2-core $0.006/min; this pipeline runs only on Linux runners, so the macOS
    ~10× / Windows ~1.7× multipliers don't apply), and that an emulator-based instrumented-test run adds several
    minutes per build. Offer **No** as "no workflows — build, test, and release locally (re-run
    `iru-setup-android-github-workflows` later to add them)".
  - `ci: no` ⇒ Step 7 is skipped entirely (no `.github/workflows/*`, no `security.yml`, no
    `.github/dependabot.yml`, no `sync.yml`/`sync_versions.py`); `ci-tests` and `pages` are not asked, and the
    workflow-only rows of the table above aren't either.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?"
  `cloud`/`self-hosted`/`none`. Recommend **`cloud`** only when open-source is Yes **and** `visibility` is
  `public`; otherwise state that SonarCloud (SonarQube Cloud) is free only for public/open-source projects (a
  private project needs a paid plan) and recommend **`none`**, offering **self-hosted SonarQube** (Community
  Build is free, but you run the server) as the second option. When `ci: no`, say plainly that nothing would send
  reports automatically — analysis would only run when someone runs `./gradlew :lib:sonar`/`:app:sonar` manually
  with a token — and recommend `none`; if the user still picks cloud/self-hosted, the scaffold writes the
  `sonar {}` block only and Step 10 reports that no CI step sends reports. When `cloud`/`self-hosted` is chosen,
  also collect `sonar-organization`/`sonar-project-key`/`sonar-host-url` the same way both scaffold skills' own
  Step 2 does (SonarCloud: suggest `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io`, inferred from
  `git remote get-url origin`; self-hosted: ask for `sonar.host.url` directly, plus `sonar.organization` only if
  that server has organizations enabled, and `sonar.projectKey`).
- **ci-tests** (only when `ci: yes`, and skipped when `run-instrumented-tests` was supplied) — "Which tests should
  CI run?"
  - **Unit tests only** (`unit`) — "JVM unit tests (`./gradlew test`, Robolectric included) only: cheaper — fewer
    minutes, faster feedback. Instrumented `src/androidTest` tests stay local." Recommend for private/internal.
  - **Unit + instrumented** (`full`) — "Also boots an Android emulator (`reactivecircus/android-emulator-runner`
    on a Linux runner with KVM) and runs `connectedAndroidTest` — complete verification, but uses considerably
    more minutes." Recommend for public. Both scaffolds always generate a placeholder test under
    `src/androidTest`, so `full` has something to run from day one.
- **pages** (only when `ci: yes` and `flavor: library` — the app flavor's workflows have no docs deploy):
  - Public: **yes** without asking (state it in Step 10's report).
  - Private/internal: ask — **No (Recommended)** ("CI still builds the Dokka + Antora docs and uploads them as a
    workflow artifact; nothing is published") vs **Yes** ("requires GitHub Pro, Team, or Enterprise Cloud — not
    available for private repositories on GitHub Free; the site will be PUBLIC on the internet unless you're on
    Enterprise Cloud with private Pages access control").
- **publish** — **`library` only**: Maven Central via the `com.vanniktech.maven.publish` plugin. Default **Yes**
  when open-source is Yes; otherwise ask with **No** recommended (Maven Central is meant for redistributable
  artifacts other projects depend on, rarely appropriate for closed-source code). Never collected or passed for
  `app` — neither `iru-setup-android-app` nor the app half of the workflows skill has a publish path.
- **distribution** — **`app` only**: `none`/`internal`/`store`. Default **`store`** when open-source is Yes;
  otherwise ask with **`internal`** (Firebase App Distribution) recommended, `none` as the local-install-only
  alternative. Never collected or passed for `library`. The `store` sub-choice — workflow-level
  `r0adkll/upload-google-play` action versus the Gradle Play Publisher plugin — is deliberately left to
  `iru-setup-android-app` to ask (it isn't an `args` key), and `iru-setup-android-github-workflows` detects the
  outcome from `app/build.gradle.kts` afterward.
- **accessibility** — both flavors (the library's `app/` sample has a UI test too): "Automated accessibility
  checks in UI tests?" — **Enforce (Recommended)** ("Accessibility Test Framework violations fail the UI tests"),
  **Report only** ("listed in test output, never failing"), **Off** ("not run; the coding defaults still apply"). **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value.
  Asked once here, alongside the other shared inputs, and forwarded to the scaffold skill (Step 4), which writes it
  to `.ui-quality.yml`.

Resolve the effective `run-instrumented-tests` for Step 7: the explicit `args` value if one was supplied,
otherwise `yes` for `ci-tests: full` and `no` for `ci-tests: unit`.

These values (`open-source`, `visibility`, `publish`/`distribution`, `sonar` + detail keys, plus `ci` and `accessibility` for the
scaffold and `ci-tests`/`pages` for the workflows) are passed through unchanged to Step 4 (the scaffold skill) and
Step 7 (`iru-setup-android-github-workflows`) so neither sub-skill re-asks them —
and so the workflows never contain a Maven Central publish job for a `lib/build.gradle.kts` with no
`mavenPublishing {}` block, or a `:lib:sonar`/`:app:sonar` step for a build file with no `sonar {}` block.

## Step 4 — Run the flavor's scaffold skill

Always invoke the scaffold skill, in either `mode`. When `settings.gradle.kts` (or any other scaffold file) already
exists, the scaffold skill itself announces that it's updating an existing project and asks, per file group it
owns, Update / Keep as is / Replace / Stop (unless `on-existing` decides) — **Keep as is** on every group is the
old "leave the Gradle project untouched" outcome. This orchestrator doesn't ask that question itself, so it's
never asked twice. Format Steps 2–3's answers as `key: value` lines using **exactly** the keys the resolved
flavor's skill accepts (never an invented key) and invoke via `iru-isolated-skill-executor`:

```
# flavor: library
Agent({
  description: "Run iru-setup-android-library",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-android-library\", args: \"group-id: <group-id>\\n
    artifact-id: <artifact-id>\\nnamespace: <namespace>\\ndescription: <description>\\n
    version: <version, only if supplied>\\nmin-sdk: <min-sdk>\\ncompile-sdk: <compile-sdk, only if supplied>\\n
    license: <license display name, or 'none'>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    publish: <publish>\\nsonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nbuilt-in-kotlin: <..., only if supplied>\\njunit: <..., only if supplied>\\n
    coverage-tool: <..., only if supplied>\\nstatic-analysis: <..., only if supplied>\\nvisibility: <visibility>\\n
    accessibility: <accessibility>\\nci: <ci>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}).
    Report back: whether it scaffolded from scratch or updated an existing project, the action taken per file group
    (created / updated / replaced / kept as is, or that the user chose to stop) including `.ui-quality.yml`, which toolchain versions came from a live
    lookup versus the recorded fallback, the compile-sdk it resolved, whether its Gradle verification build
    passed, and any value it resolved on its own (repository URL, inception year).",
  run_in_background: false
})
```

```
# flavor: app
Agent({
  description: "Run iru-setup-android-app",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-android-app\", args: \"application-id: <application-id>\\n
    namespace: <namespace>\\napp-name: <app-name>\\nmin-sdk: <min-sdk>\\n
    compile-sdk: <compile-sdk, only if supplied>\\ntarget-sdk: <target-sdk, only if supplied>\\n
    version-name: <..., only if supplied>\\nversion-code: <..., only if supplied>\\n
    license: <license display name, or 'none'>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\ndistribution: <distribution>\\nui: <..., only if supplied>\\n
    built-in-kotlin: <..., only if supplied>\\njunit: <..., only if supplied>\\n
    coverage-tool: <..., only if supplied>\\nstatic-analysis: <..., only if supplied>\\nvisibility: <visibility>\\n
    accessibility: <accessibility>\\nci: <ci>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}).
    Report back: whether it scaffolded from scratch or updated an existing project, the action taken per file group
    (created / updated / replaced / kept as is, or that the user chose to stop) including `.ui-quality.yml`, which toolchain versions came from a live lookup
    versus the recorded fallback, the compile-sdk/target-sdk it resolved, whether the release signing config and
    the distribution plugin (Firebase App Distribution / Gradle Play Publisher / none) were written, whether its
    Gradle verification build passed, and any value it resolved on its own.",
  run_in_background: false
})
```

Omit every `only if supplied` line whose value wasn't resolved (never send an empty key). Neither scaffold skill
accepts `branching`, `integration-branch`, `stable-branch`, `java-version`, `api-level`, `run-instrumented-tests`,
`ci-tests` or `pages` — those are Step 7's alone, so never send them here. `publish` is sent only for
`library`, `distribution` only for `app`.

Each scaffold skill parses these itself (its own Step 0) and only asks the user about anything genuinely left
out — `AskUserQuestion` surfaces to the user the same way whether invoked directly or from inside this
sub-agent. If it reports that the user chose **Stop**, stop this skill here too —
there's no coherent Gradle project to build Antora docs or CI workflows around yet. The scaffold's own
verification (`./gradlew --offline help`, then an `assembleDebug`) downloads Gradle, SDK components and every
dependency on first run — warn the user that this step is the slow one before launching it.

## Step 5 — Run `iru-setup-antora`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-antora", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-antora\"}) with no args (or, only if
`on-existing` arrived via this orchestrator's own args, args: \"on-existing: <on-existing>\" — the only key it
accepts) — it derives the Antora component name, title, and version itself from the repository (the Gradle project
name in settings.gradle.kts, git tags/GitHub releases, or the module's version with any -SNAPSHOT suffix
stripped), and if docs already exist it asks Update / Keep / Replace itself. Report back: whether it created the
site from scratch or updated an existing one, the action taken (created / updated / replaced / kept as is), which
files/pages were created vs. already present, and whether the site build succeeded.",
run_in_background: false})`.

Run this *before* Step 7, not after: `iru-setup-antora` is quick to detect as "already done", and
`iru-setup-android-github-workflows`'s own survey (Step 1 there) checks whether `docs/antora.yml`/
`docs/antora-playbook.yml` exist before generating its Dokka + Antora Pages deploy job — running Antora setup
first means that check finds everything in place instead of flagging a gap. In a repository with no tags or
releases yet, `iru-setup-antora` may ask for a starting version; answer with the scaffold's version minus any
`-SNAPSHOT` suffix (`1.0.0` for the defaults) rather than inventing another.

## Step 6 — Run `iru-setup-android-gitignore`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-android-gitignore", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-android-gitignore\"}) with no args
(or, only if `on-existing` arrived via this orchestrator's own args, args: \"on-existing: <on-existing>\") — it
derives the module list from settings.gradle.kts's include(...) lines and detects mode itself from whether a root
.gitignore already exists, asking Update / Keep / Replace per existing file. Report back only, per root and
per-module .gitignore, the action taken (created / updated / replaced / kept as is).", run_in_background: false})`.

`iru-setup-android-gitignore` also accepts `mode` and `modules` keys, but both are deliberately left for it to
detect: its own `.gitignore`-presence check is the more precise `mode` signal for this file (a `mode: new`
repository can still carry a hand-written `.gitignore` that deserves the diff-and-approve path, never a silent
overwrite), and the module list must come from the `settings.gradle.kts` Step 4 just wrote, not from this
orchestrator's guess. Run this after Steps 4–5, not before: detection depends on the scaffold's
`settings.gradle.kts`/`detekt.yml`/version-catalog signals and on the Antora config Step 5 just wrote. Run it
*before* Step 7, so `build/`, `.gradle/`, `local.properties`, keystores, `google-services.json` and the Antora
build output are already ignored before CI-generated reports show up locally.

## Step 7 — Run `iru-setup-android-github-workflows`

**Skip this step entirely when `ci: no`** — no `.github/workflows/*`, no `security.yml`, no
`.github/dependabot.yml`, no `sync.yml`/`sync_versions.py`. Record it in Step 10 as "skipped (ci: no)", noting
that the shared security block (Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with it
and can be added later by running `/iru-setup-android-github-workflows` on its own.

Otherwise, format Steps 1–3's answers as `key: value` lines and invoke via `iru-isolated-skill-executor`:

```
Agent({
  description: "Run iru-setup-android-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-android-github-workflows\", args: \"flavor: <flavor>\\n
    branching: <branching>\\nintegration-branch: <integration-branch, gitflow only>\\n
    stable-branch: <stable-branch>\\njava-version: <java-version>\\n
    run-instrumented-tests: <effective run-instrumented-tests, Step 3>\\nci-tests: <ci-tests, if resolved>\\n
    api-level: <api-level>\\nopen-source: <open-source>\\nvisibility: <visibility>\\n
    pages: <pages, library only>\\npublish: <publish, library only>\\ndistribution: <distribution, app only>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nmode: <mode>\\non-existing: <..., only if supplied via args>\"}). Report back:
    whether it created the pipeline from scratch or updated an existing one, and per file — develop.yml/main.yml/
    sync.yml (gitflow) or main.yml/publish.yml (main-only), security.yml, .github/dependabot.yml and
    .github/scripts/sync_versions.py — the action taken (created / updated / replaced / kept as is, with any
    mismatch it listed for a kept file, or that the user chose to stop), whether docs go to GitHub Pages or only
    to a workflow artifact, the coverage-conversion path it
    chose, any open gap it flagged (missing docs/antora.yml, README/Antora wording not matching sync_versions.py,
    a Gradle Play Publisher plugin found), and the full required-secrets/environments table it produced for this
    flavor/branching/options.",
  run_in_background: false
})
```

Omit `integration-branch` when `branching: main-only`, `publish` and `pages` for `app`, and `distribution` for
`library` (never send a key the flavor doesn't use). `run-instrumented-tests` is always sent with the effective
value from Step 3, so the workflows skill never re-derives or re-asks it; `ci-tests` travels alongside only for
its report. The six `security-*` keys are deliberately never passed — the four
always-on jobs default to `yes` and the two opt-ins (`security-owasp-dependency-check`, `security-mobsfscan`) to
`no` inside `iru-setup-android-github-workflows` itself, and this orchestrator doesn't ask about them; note this
once in Step 10's report rather than re-asking per run. The shared `license`/`developer-*`/`organization-url`
keys are likewise never passed here: the workflows skill accepts them only to tolerate a full shared-args set
and would report them as "supplied but unused".

If it reports that the user chose **Stop** on its prior-setup question, note that in Step 10's report
rather than treating it as a failure of this skill — the scaffold, the Antora docs, and the `.gitignore` files
from Steps 4–6 are still valid on their own.

## Step 8 — Run `iru-setup-changelog`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-changelog", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-changelog\"}) with no args (or, only if
`on-existing` arrived via this orchestrator's own args, args: \"on-existing: <on-existing>\" — the only key it
accepts), working entirely from the repository's own git tag/GitHub Release history. Report back only the action
taken on CHANGELOG.md (created — with the version range reconstructed — / updated with missing releases /
replaced / kept as is).", run_in_background: false})`.
Position relative to Steps 4–7 doesn't matter functionally — it's independent of all of them. In `mode: new`
(no tags yet) it either bootstraps a minimal `## [Unreleased]`-only file or stops per the user's choice; in
`mode: existing`, real tag/release history may exist to backfill. If `CHANGELOG.md` already exists, it warns and
asks Keep as is / Update (append missing releases) / Replace itself, regardless of `mode` — whatever it reports
goes into Step 10's report. Running it before `iru-setup-readme` means the README's Documentation section sees it already
in place.

## Step 9 — Run `iru-setup-readme`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run iru-setup-readme", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\n
pages: <pages; no for flavor app or when ci is no>\\non-existing: <..., only if supplied via args>\"}) — those are
the only keys it accepts (`sonar: none` suppresses Sonar badges/links even if Sonar config exists on disk,
`ci: no` omits CI badges, `pages: no` keeps it from linking a github.io docs site that won't exist), deriving
everything it needs by exploring the repository directly (build.gradle.kts/settings.gradle.kts, the
Antora docs, .gitignore, the workflows and the sonar {} block, and CHANGELOG.md). Report back only which sections
were included vs. omitted and why, and — for a library — whether the Gradle dependency snippet it wrote uses the
implementation(\"group:artifact:version\") shape.", run_in_background: false})`. Run this last, after every
other skill, so its badges/status-table/documentation-links sections see the finished state of Steps 4–8 instead
of a sparser mid-run snapshot. In `mode: new`, most CI/Sonar/changelog-derived sections will still be sparse or
absent (no commits/tags/CI runs yet) — expected; `iru-setup-readme` omits what it can't confirm rather than
inventing it. `README.md` may or may not already exist depending on `mode`; `iru-setup-readme` announces scratch vs update and
asks Update / Keep / Replace itself — nothing further to confirm here.

For a gitflow **library**, the README's dependency snippet is what `sync.yml`'s `sync_versions.py` rewrites after
each release — if `iru-setup-readme` reports a snippet shape other than the one `iru-setup-android-github-workflows`
recorded in Step 7 (Groovy `implementation 'g:a:v'` vs. Kotlin-DSL `implementation("g:a:v")`), flag the mismatch
in Step 10 so the user reconciles one of the two before the first release.

## Step 10 — Report

Summarize the outcome of all six delegated skills together: repeat the **Setup mode** (from scratch, or updating
an existing repository), the resolved `flavor`, `branching` and `mode`, the project identity and license, and the
pipeline parameters used (the Step 3 table with the values actually applied). Then a per-component table — the
scaffold's own files (`settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, the wrapper,
`lib/`/`app/`, `.ui-quality.yml`), the Antora docs site, the root and per-module `.gitignore` files, each workflow file
(`develop.yml`/`main.yml`/`sync.yml` + `sync_versions.py`, or `main.yml`/`publish.yml`, plus `security.yml`) and
`.github/dependabot.yml`, `CHANGELOG.md`, and `README.md` — with the action actually taken for each: **created /
updated / replaced / kept as is / skipped (ci: no)** (or not reached, after a Stop in Step 4 or 7), and, for
anything kept as is, the mismatches its sub-skill listed.

State explicitly which of publishing/distribution and Sonar analysis were wired, or deliberately omitted and why:

- **`open-source`**: yes/no, as resolved in Step 3.
- **`visibility`**: `public`/`private`/`internal`, and whether it came from `args`, `gh repo view`, or a question.
- **`ci`**: yes/no — with `no`, state that no workflows, `security.yml` or `.github/dependabot.yml` were
  generated (the shared security block was skipped with them) and that `/iru-setup-android-github-workflows` adds
  them later.
- **`ci-tests`**: `unit`/`full` (ci: yes only), and the effective `run-instrumented-tests` it mapped to — or that
  an explicit `run-instrumented-tests` overrode it.
- **`pages`** (library, ci: yes only): `yes` — docs deployed to GitHub Pages (public-repository default, or a user
  choice on a private repository with the plan requirement and public-site caveat restated); `no` — docs built
  and uploaded as a workflow artifact only.
- **`publish`** (`library`) / **`distribution`** (`app`): whether the `mavenPublishing {}` block and the Maven
  Central publish job — or the signing config, the Firebase App Distribution / Google Play upload, and the
  store-upload mechanism `iru-setup-android-app` chose — were included; `yes`/non-`none` unless the user opted out
  (or the project isn't open source and declined it), stated plainly rather than left implicit.
- **`sonar`**: `cloud`/`self-hosted`/`none`, and whether the per-module `sonar {}` block and the `:lib:sonar`/
  `:app:sonar` workflow step were included — if `none`, note whether that's the expected default (not open
  source, private/internal, or `ci: no`) or a direct user choice; if configured with `ci: no`, that no CI step
  sends reports.
- **`run-instrumented-tests`**: yes/no — whether the emulator (KVM) job was generated.
- **`accessibility`**: `enforce`/`report`/`off`/`none` as written to `.ui-quality.yml` (and the file's action), the sample
  UI test's capture count the scaffold reported, and that every CI run uploads `.ui-screenshots/` as a 7-day
  `ui-screenshots` artifact (ci: yes only).
- **Toolchain versions**: which came from a live lookup versus the recorded fallback, as reported by the scaffold
  (Gradle/AGP/Kotlin/Compose BOM…) and the workflows skill (action tags).

Unless Step 7 was skipped (`ci: no` — then say there are no CI secrets to create), reproduce the **consolidated
required-secrets/settings table** `iru-setup-android-github-workflows` reported in Step 7 verbatim (it's already scoped to exactly this flavor/branching/options — don't re-derive or expand it):
`SONAR_TOKEN` for a non-`none` `sonar`; `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and
`SIGNING_MEMORY_KEY`/`SIGNING_MEMORY_KEY_ID`/`SIGNING_IN_MEMORY_KEY_PASSWORD` for `publish: yes`;
`ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD` for an app whose
`distribution` isn't `none`, plus `PLAY_SERVICE_ACCOUNT_JSON` (`store`) or `FIREBASE_APP_ID`/
`FIREBASE_SERVICE_CREDENTIALS` (`internal`). Then its repository-settings notes: GitHub Pages source set to
**GitHub Actions** for the docs deploy (`pages: yes` only), and — gitflow only — "Workflow permissions" allowing GitHub Actions to
create pull requests for `sync.yml`. Add the two non-secret files the app scaffold never writes but CI/local
builds need: `app/google-services.json` (`distribution: internal`, downloaded from the Firebase console,
gitignored) and `local.properties` pointing at the SDK (local builds only, gitignored).

List every decision made on the user's behalf: the license question's flavor-dependent recommendation (Step 2);
leaving `built-in-kotlin`/`junit`/`coverage-tool`/`static-analysis`/`ui` and the `store` upload mechanism for
their scaffold skill to ask (Steps 2–3); `compile-sdk` resolved by the scaffold from the local SDK rather than
asked (Step 3); `java-version` feeding only the workflows (Step 3); `iru-setup-android-gitignore`'s `mode`/`modules`
left to its own detection (Step 6); `run-instrumented-tests` derived from `ci-tests` (Step 3); `pages`
defaulted to `yes` for a public repository without asking (Step 3); and the six `security-*` flags left at their
defaults (Step 7).

Finish with an explicit **Warn explicitly** block, repeating each sub-skill's own closing warning:

- **Review every generated/updated file before building, committing, or relying on CI.** Confirm the license
  matches an actual `LICENSE` file at the root if one was chosen (`iru-check-license` can generate one and
  backfill Kotlin headers), and that `./gradlew assembleDebug test lint` succeeds locally — the first run
  downloads Gradle, SDK components and every dependency.
- Branch names, module names and the signing/publish/store setup in the workflows were inferred from the
  resolved parameters and the scaffold's files — a wrong assumption there publishes or signs the wrong thing.
  Do a `workflow_dispatch` dry run before trusting a real release.
- Every secret in the table above, and the matching SonarCloud project / Maven Central Publishing Portal
  registration / Google Play Console service account / Firebase project, must already exist before CI can pass
  — nothing in this pipeline creates or stores any account or credential.
- If `java-version` was overridden from `17`, the scaffold's `sourceCompatibility`/`jvmTarget` still say 17 and
  need a matching hand edit in `lib/build.gradle.kts`/`app/build.gradle.kts`.
- Every toolchain and action version was resolved via a run-time lookup or its recorded September 2026
  fallback — re-check them (Dependabot's `gradle` + `github-actions` entries will start proposing bumps) before
  relying on this scaffold long-term. The emulator instrumented-test run, the Sonar scan, Maven Central
  publishing and the store/Firebase uploads are never exercised locally by any of the six skills — only their
  YAML is validated.
