---
name: iru-setup-quarkus-github-workflows
description: "Invoked as /iru-setup-quarkus-github-workflows with optional key: value args (branches, ci-tests, pages, distribution, sonar, native, security-*, on-existing), usually by iru-setup-quarkus-repository. Creates or updates build, release, sync and security workflows plus Dependabot for a Quarkus service, from quarkus-stack.yml."
model: sonnet
---

# Setup Quarkus GitHub Workflows

Scaffold (or update) the CI/CD and security pipeline for a Quarkus service:

| File | Trigger | Does |
|---|---|---|
| `.github/workflows/build.yml` | push to `<integration-branch>`/`<stable-branch>`, pull requests to them, `workflow_dispatch` | build + tests per `ci-tests`, 80% coverage gate, quality reports, optional Sonar, docs (Pages deploy from `<stable-branch>` or artifact), optional native job |
| `.github/workflows/release.yml` | GitHub Release published — **only when `distribution: registry`** | container image `<version>` + `latest` pushed to the registry |
| `.github/workflows/sync.yml` + `.github/scripts/sync_versions.py` | GitHub Release published from `<stable-branch>` | PR from `sync_<released version>` merging the release back into `<integration-branch>` with the next `-SNAPSHOT` |
| `.github/workflows/security.yml` | pushes, pull requests, weekly | dependency review (+ Gradle dependency submission), CodeQL, OSV-Scanner, gitleaks |
| `.github/dependabot.yml` | — | weekly grouped updates for the build, the actions, and (when present) the Dockerfile and docs toolchain |

Used by `iru-setup-quarkus-repository`; also runs standalone, where it resolves `visibility` itself and asks `ci-tests`/`pages`/`sonar` with visibility-aware cost notes (it never asks `ci`).

It fits the catalog's release flow: `iru-release` cuts `release_x.y.z` from `<integration-branch>`, bumps the version
with `iru-java-bump-version` (Maven `versions:set`, or Gradle's `gradle.properties` `version=`), and opens a PR into
`<stable-branch>`; merging it runs `build.yml` on `<stable-branch>` (docs to Pages); publishing the GitHub Release
runs `release.yml` (image) and `sync.yml` (next snapshot back on `<integration-branch>`).

Every action reference uses the major that was current when this skill was written (checked against the GitHub
API): `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/*@v6`, `actions/setup-node@v7`,
`actions/setup-python@v7`, `actions/upload-artifact@v7`, `crazy-max/ghaction-github-pages@v5`,
`docker/setup-buildx-action@v4`, `docker/login-action@v4`, `docker/metadata-action@v6`,
`docker/build-push-action@v7`, `github/codeql-action/*@v4`, `actions/dependency-review-action@v5`,
`google/osv-scanner-action/...@v2.7.0`, `gitleaks/gitleaks-action@v3`. Re-check them when updating an old pipeline —
Dependabot's `github-actions` entry keeps them current afterwards.

**Templates.** The workflow, script and Dependabot templates of Step 3 are files under this skill's `templates/` directory, not inline text. This skill has a single variant (`common`, with the build-tool/`ci-tests`/`pages`/`sonar`/native alternatives gated inside each file by guidance comments); read the template files for the units being written (the index at the top of Step 3 says which), each in full, and nothing else.

## Step 1 — Resolve inputs and survey the repository

Parse `args` (`key: value` lines, one per line) first; every key found is resolved and its survey/question below is
skipped:

```
integration-branch: develop
stable-branch: main
java-version: 21
open-source: yes
visibility: public
ci-tests: full
pages: yes
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_orders-service
sonar-host-url: https://sonarcloud.io
distribution: registry
registry: ghcr.io
image-builder: jib
native: no
mode: new
on-existing: ask
```

Ignore any other key (`ci` included — invoking this skill means CI is wanted). `mode` is informational; `on-existing`
pre-answers Step 2's per-file question. The `security-*` keys (`security-dependency-review`/`security-codeql`/`security-osv`/`security-gitleaks`, each `yes`/`no`) default to `yes`.

Then read `quarkus-stack.yml` if it exists — it's the authoritative record: `project.buildTool`/`language`/`layout`/
`javaVersion`/`artifactId`, `visibility`, `ci.tests`, `ci.pages`, `sonar.*`, `distribution.*`, `native`. Values from
`args` win over the manifest (an orchestrator passing them reflects what the user just chose); the manifest wins over
detection. Without a manifest, detect:

- **Build tool / layout** — `pom.xml` (reactor if it has `<modules>`; the app module is the one whose pom applies
  `quarkus-maven-plugin`) or `settings.gradle.kts` (multi-project if it `include`s; the app project applies
  `id("io.quarkus")`). Not a Quarkus build at all → warn and ask (`AskUserQuestion`) **Stop** / **Continue anyway**
  (every command becomes an open gap to confirm).
- **Language** — `src/main/kotlin` / `quarkus-kotlin` → Kotlin, else Java.
- **Java version** — `maven.compiler.release` / `java { sourceCompatibility }`; ask (default `21`) if absent.
- **Coverage gate, quality tools, Sonar** — confirm the build has the JaCoCo check, Checkstyle/PMD/SpotBugs or
  detekt/ktlint, Dokka, and (for `sonar` ≠ `none`) `sonar-maven-plugin`/`org.sonarqube` with `sonar.projectKey`. A
  missing piece isn't fixed here — `iru-setup-quarkus-service` owns the build — but every workflow step that would
  call it is dropped, and the report says so.
- **Native** — a `native` Maven profile is always generated by `create`; treat `native` as `yes` only when the
  manifest says so or `args` does.
- **Container image** — `quarkus-container-image-jib` in the build → `image-builder: jib`; otherwise `docker` when
  `src/main/docker/Dockerfile.jvm` exists (in `app/` for hexagonal).
- **Docs** — `docs/antora.yml` + `docs/antora-playbook.yml` (+ `docs/package-lock.json` for `npm ci`). Missing →
  recommend running `iru-setup-antora` first; the docs job would have nothing to build.
- **Branches** — confirm `<integration-branch>`/`<stable-branch>` against `git branch -a` (or ask); `sync.yml` needs
  both.
- **README/Antora wording** for `sync_versions.py` — `grep -n -i "current development version\|latest release"
  README.md`; note any deviation from the default row labels so Step 3's script matches the real wording.

**Cost-aware choices** (only those still unresolved, in this order, `AskUserQuestion`; prices per GitHub's/
SonarSource's current pricing — tell the user to double-check their plan):

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercased) when there's a GitHub remote;
   otherwise ask **Public** / **Private** (Public recommended when open source). Open source + private is
   contradictory — say so. `internal` = `private` below. Public repositories run standard runners free; private
   ones consume the included minutes (2,000/month Free, 3,000 Pro/Team, 50,000 Enterprise Cloud), then ~$0.006/min
   for Linux 2-core — this pipeline is Linux-only; the native job is the expensive one (often 10+ minutes).
2. **`sonar`** — when neither `args` nor the manifest nor the build resolves it: ask open source first if unknown,
   then "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" — **SonarCloud** only for
   a public open-source repository, otherwise **None** recommended, **self-hosted** second.
3. **`ci-tests`** — **Unit only** (`unit`; recommended for private) — "plain + `@QuarkusComponentTest` classes, Dev
   Services off, every `@QuarkusTest` excluded: no containers, fastest, cheapest; container-backed tests stay local
   (or run by the coding skills' group stage)". **Full** (`full`; recommended for public) — "also `@QuarkusTest`
   classes with Dev Services containers and `@QuarkusIntegrationTest` against the packaged jar — complete
   verification; GitHub's Ubuntu runners ship Docker, so no extra setup, but considerably more minutes".
4. **`pages`** — public: `yes` without asking (say so in Step 7). Private: **No (Recommended)** — "docs still built,
   uploaded as a workflow artifact" — vs **Yes** — "needs Pro/Team/Enterprise Cloud; the site is public unless
   Enterprise Cloud private Pages".
5. **`distribution`** — when unresolved: **None** / **Registry** (GHCR by default — free for public repositories,
   storage/transfer quotas for private ones), then `image-builder` if a Jib extension or Dockerfile doesn't decide it.

## Step 2 — Announce scratch vs update, decide per existing file

Check `build.yml`, `release.yml`, `sync.yml` + `.github/scripts/sync_versions.py` (one unit — partial if only one
exists), `security.yml`, `.github/dependabot.yml`. None → "creating the CI/CD pipeline **from scratch**". Any → "
**updating an existing CI/CD setup**", list each as present/absent/partial, read each existing one, and name every
concrete contradiction with this run's choices ("`build.yml` runs `-DskipITs=false` but `ci-tests: unit`", "it deploys
to Pages but `pages: no`", "it has a Sonar step but `sonar: none`", "there's a `release.yml` but `distribution:
none`"). Unless `on-existing` decides, ask per unit (`AskUserQuestion`, ≤ 4 per call):

- **Update (Recommended)** — merge: add/correct what this skill owns (stages, gating, action majors, commands),
  keep every other step/job/entry; diff shown before writing.
- **Keep as is** — untouched; mismatches listed in Step 7.
- **Replace** — regenerate from the template; customizations lost (git history keeps them).
- **Stop** (first question only) — end without changing anything.

With `distribution: none`, an existing `release.yml` that only publishes the image is offered for removal (ask; never
delete silently).

## Step 3 — Templates

**Templates.** Every template below is a file under `templates/common/<path of the generated file>`, holding the exact bytes of that generated file's template. Read the files for the units being written, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/common/.github/workflows/build.yml` | always: build, tests per `ci-tests`, coverage gate, quality reports, optional Sonar, docs, optional native job |
| `.github/workflows/release.yml` | `templates/common/.github/workflows/release.yml` | only when `distribution: registry`: container image pushed on GitHub Release |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | always, written together with `sync_versions.py`: PR merging the release back into the integration branch with the next `-SNAPSHOT` |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | always, written together with `sync.yml` |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | drop each whole job whose `security-*` flag is `no`; not written at all if all four are `no` |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | always written, no opt-out |

Resolve every `<placeholder>` from Step 1. Lines/blocks marked `# Maven:`/`# Gradle:`, `# ci-tests: …`,
`# pages: …`, `# sonar …`, `# native …`, `# jib`/`# docker`, `# hexagonal` are alternatives or gates: keep the one that
applies and **drop the others together with these guidance comments** — a step that can never run is dead YAML.
`<app-dir>` is `app` for a hexagonal build and `.` for a single module; `<app-module>` is `-pl app -am ` (Maven) or
`:app:` (Gradle) for hexagonal, empty for single-module. `<artifact-id>` is the service's artifactId (single module)
or the base artifactId without `-app` (hexagonal) — the image name.

### `build.yml`

Template: `templates/common/.github/workflows/build.yml` — read it in full before filling placeholders

Gating summary for `build.yml`: keep exactly one of the two test steps (`ci-tests`); drop the static-analysis step
for Kotlin; drop the Sonar step for `sonar: none`; drop the `native` job unless `native: yes`; keep exactly one of
"Deploy to GitHub Pages"/"Upload documentation" (`pages`); keep exactly one command per `# Maven/Gradle/Java/Kotlin`
alternative and drop `cache: maven` or the `Set up Gradle` step accordingly. Gradle's `-D` flags reach Quarkus's
build (native/container-image config) but not the test JVM — that's why the unit step passes Dev Services' switch as
the `QUARKUS_DEVSERVICES_ENABLED` environment variable for both build tools.

### `release.yml` (`distribution: registry` only)

Template: `templates/common/.github/workflows/release.yml` — read it in full before filling placeholders

Notes: `-Dquarkus.container-image.*` (Jib) works for Gradle's `build` too — Quarkus reads them as build-time
configuration. The image name comes from the service, not from `${{ github.repository }}`, so a repository rename
doesn't change it. A snapshot image per `<integration-branch>` push is deliberately not published (a registry full
of `-SNAPSHOT` tags costs storage on private repositories); add one to `build.yml` only if a deployment pipeline
consumes it.

### `sync.yml`

The library pipeline's Sync workflow (`iru-setup-java-github-workflows`), with the version bump made build-tool
aware:

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

Gradle: drop the two Maven-only steps (JDK + `versions:set`) — the script rewrites `gradle.properties`. The bump is
always minor (`1.3.0` → `1.4.0-SNAPSHOT`), matching `iru-release`'s default "next upcoming version"; flag it in
Step 7 if the team releases patches from `<integration-branch>` too.

The branch is `sync_<released version>` (release `1.0.1` → `sync_1.0.1`). If the merge conflicts, the PR opens from the
release commit for manual resolution, with no bump, so a patch sync never lowers `<integration-branch>`'s version.

### `.github/scripts/sync_versions.py`

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

### `security.yml`

The shared security block every workflows skill in this catalog embeds. Drop a job's whole entry when its
`security-*` flag is `no`; if all four are `no`, don't write the file at all (say so in Step 7).

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

### `.github/dependabot.yml` (always written — no opt-out)

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

For `directory: /<app-dir>/…` write `/src/main/docker` (single module) or `/app/src/main/docker` (hexagonal). When
updating an existing `dependabot.yml`, keep entries for ecosystems this skill doesn't manage.

## Step 4 — Fill and gate

Substitute every placeholder from Step 1, apply every gate described above (keeping one alternative per
`# Maven/Gradle/Java/Kotlin` choice), and strip the guidance comments. Never invent a value: an unresolved fact
(e.g. no Antora playbook, no Sonar project key) is either asked or reported as an open gap in Step 7.

Notes that change what is generated:

- **Hexagonal Maven**: tests, coverage, and Sonar run from the root as written (`verify` walks the reactor; the
  `coverage` module aggregates). Image/native steps target the app module (`-pl app -am`), and the docs job uses
  `install` + `site site:stage` (multi-module site needs the modules installed and staged).
- **Hexagonal Gradle**: `quarkusIntTest`/native/image tasks are `:app:…`; Dokka aggregates at the root
  (`dokkaGeneratePublicationHtml` → `build/dokka/html`).
- **No Docker in `ci-tests: unit`** — no service containers, no `docker` login; everything that would need Docker is
  excluded by the grep, which anchors on the annotation at the start of a line (a Javadoc mention of `@QuarkusTest`
  must not exclude a plain test).

## Step 5 — Validate

- Run `actionlint` on every generated workflow — `actionlint` if installed, else
  `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest -color` — and fix every finding (shellcheck
  findings in `run:` blocks included). `.github/dependabot.yml` is not a workflow: parse it with
  `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/dependabot.yml`.
- Run the build commands the workflows contain locally first, through the `iru-gate-runner` agent (compact
  results): the chosen test step (for `unit`, with the same grep/excludes and `QUARKUS_DEVSERVICES_ENABLED=false`),
  the static-analysis step, and the docs commands. A workflow whose commands were never run is a guess. Docker
  unavailable locally → the `full` step is **unverified**, not failed.
- Never trigger `release.yml` or `sync.yml` for real as a test. Recommend a throwaway pre-release/tag on a fork, or
  review of the first real release run.

## Step 6 — Write the files

Write (or, per Step 2, update/replace — and skip kept units) the workflows, `sync_versions.py` (only together with
`sync.yml`), `security.yml`, and `.github/dependabot.yml`. With `distribution: none`, write no `release.yml`.

## Step 7 — Report: secrets, settings, choices, warnings

Required secrets (Settings → Secrets and variables → Actions) — list only the rows that apply:

| Secret | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | SonarCloud/SonarQube token for `sonar:sonar` / `./gradlew sonar` | `sonar` is `cloud` or `self-hosted` (cloud on a private repository also needs a paid SonarCloud plan) |
| `REGISTRY_USERNAME` / `REGISTRY_PASSWORD` | Registry credentials for the image push | `distribution: registry` with a registry other than GHCR (GHCR uses the automatic `GITHUB_TOKEN` + `packages: write`) |

`GITHUB_TOKEN` needs no setup and covers Pages, GHCR, Sync and every `security.yml` job (gitleaks needs
`GITLEAKS_LICENSE` only for organization accounts on its paid tier — mention it). Repository settings to check:

- **Settings → Actions → General → Workflow permissions** must allow GitHub Actions to create pull requests, or
  `sync.yml` fails on `gh pr create` despite its `permissions:` block.
- **Pages** (`pages: yes`): source = `gh-pages` branch. Private repositories need Pro/Team/Enterprise Cloud, and the
  site is public unless Enterprise Cloud private Pages.
- **Code scanning** must be enabled for CodeQL to upload; **Dependency graph** for dependency review (on by default
  for public repositories).
- **GHCR**: the first push creates the package as *private*; make it public (package settings) if it should be
  pullable anonymously, and link it to the repository.

Then summarize: scratch vs update and, per unit, **created / updated / replaced / kept as is** (with mismatches) or
stopped; the build tool/language/layout and Java version used; `visibility` (and its source) with the
minutes note for private repositories; `ci-tests` (and, for `unit`, that container-backed tests only run locally or
in the coding skills' group stage); `pages`; `sonar` (+ keys) or why omitted; `distribution`/registry/image builder
(or why `release.yml` wasn't written); `native` (and its cost); which security jobs were included; and every open
gap (no Antora setup, a build without the JaCoCo check or Sonar plugin, README wording `sync_versions.py` won't match,
patch releases the minor-bump default doesn't handle).

Warn explicitly: **review every workflow before pushing it.** Branch names, the Java version, image coordinates and
the registry were inferred from this repository and may need correcting; `release.yml` publishes an image and
`sync.yml` pushes branches and opens pull requests, so verify both against the first real release. Nothing in these
files contains a credential, and it must stay that way.
