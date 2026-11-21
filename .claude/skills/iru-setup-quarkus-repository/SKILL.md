---
name: iru-setup-quarkus-repository
description: "Invoke as /iru-setup-quarkus-repository, optionally with args (key: value lines: language, build-tool, layout, java-version, mode, open-source, visibility, ci, ci-tests, pages, distribution, sonar, branches, on-existing, identity keys) to skip matching questions. End-to-end bootstrap of a Quarkus service repository (Java or Kotlin, Maven or Gradle, single or hexagonal): asks the shared inputs once, then runs iru-setup-quarkus-service, iru-setup-antora, iru-setup-java-gitignore, iru-setup-quarkus-github-workflows, iru-setup-changelog and iru-setup-readme in order. Use to bootstrap or catch up a Quarkus repository in one pass."
model: sonnet
---

# Setup Quarkus Repository

Bootstrap a brand-new Quarkus service repository — or catch up an existing one — in one pass by orchestrating six
existing skills: `iru-setup-quarkus-service` (interviews the technology choices, writes `quarkus-stack.yml`, scaffolds
the build with `quarkus-maven-plugin:create` and post-processes it to this catalog's conventions),
`iru-setup-antora` (Antora documentation site), `iru-setup-java-gitignore` (root `.gitignore`),
`iru-setup-quarkus-github-workflows` (`build.yml`/`release.yml`/`sync.yml`/`security.yml` + Dependabot),
`iru-setup-changelog` (root `CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does not duplicate
their logic — it collects the shared parameters once and passes each sub-skill only the `args` keys it accepts.

Each of Steps 3–8 invokes its sub-skill via the `iru-isolated-skill-executor` agent rather than calling `Skill(...)`
directly: every sub-skill re-derives what it needs from the files the previous steps wrote (and
`iru-setup-quarkus-github-workflows` reads `quarkus-stack.yml`), so only a short completion summary has to come back,
keeping six sub-skills' transcripts out of this orchestrator's context. `AskUserQuestion`s raised inside a sub-skill
still reach the user — tell the executor to let them through rather than answering them itself.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill runs stand-alone (`/iru-setup-quarkus-repository`) or from the `iru-setup-repository` front door, so parse
`args` first, as `key: value` lines, one per line, e.g.:

```
mode: new
open-source: yes
visibility: public
ci: yes
ci-tests: full
pages: yes
distribution: registry
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_orders-service
sonar-host-url: https://sonarcloud.io
language: java
build-tool: maven
layout: single
on-existing: ask
```

Recognized keys: `mode` (`new`/`existing`), `open-source` (`yes`/`no`), `visibility` (`public`/`private`/
`internal`), `ci` (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`), `distribution` (`none`/`registry`),
`sonar` (`cloud`/`self-hosted`/`none`) and — only when not `none` — `sonar-organization`, `sonar-project-key`,
`sonar-host-url`; `language` (`java`/`kotlin`), `build-tool` (`maven`/`gradle`), `layout` (`single`/`hexagonal`),
`java-version`, `integration-branch`, `stable-branch`; the identity keys `group-id`, `artifact-id`, `version`,
`package`, `description`, `developer-name`, `developer-email`, `organization-url`, `license`; and `on-existing`
(`ask`/`keep`/`update`/`replace` — never asked; forwarded to every file-writing sub-skill **only** when it arrived
here). Every key found is resolved — skip its question below. Ignore unknown keys (e.g. `publish`, which applies to
libraries). If `args` is absent or doesn't look like this format, treat everything as unset.

If `mode` wasn't supplied, don't ask it as an isolated question: a Quarkus build at the root (`pom.xml`/
`build.gradle.kts` mentioning `quarkus`) or a `quarkus-stack.yml` → default `existing` and confirm with
`AskUserQuestion` (`existing` pre-selected; `new` treats the repository as blank — every sub-skill still warns and asks
before touching a file that exists); otherwise `new` without asking. Under `mode: new`, any owned artifact already on
disk is still an update for that component.

### Setup mode announcement

Right after resolving `mode`, before asking anything else, survey the components this run owns and print:

- `mode: new` and nothing present → "Setting up this repository **from scratch** — no prior setup found."
- Otherwise → "**Updating an existing repository** — prior setup found:" and an inventory table, one row per
  component, marked **absent / present / partial** with the evidence:

  | Component | Evidence to check |
  |---|---|
  | Manifest | `quarkus-stack.yml` |
  | Build | `pom.xml` (+ module poms) or `build.gradle.kts` + `settings.gradle.kts` + `gradle.properties`; wrapper (`mvnw`/`gradlew`) |
  | Sources | `src/main/{java,kotlin}` (or per module: `domain/`, `application/`, `infrastructure/*`, `api/*`, `app/`) |
  | Quality config | `checkstyle.xml` / `detekt.yml` + `.editorconfig` |
  | Sonar config | `sonar.*` in `pom.xml` / `sonar {}` in `build.gradle.kts` |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` (partial if only some) |
  | `.gitignore` | `.gitignore` |
  | Workflows | each of `.github/workflows/build.yml`, `release.yml`, `sync.yml` (+ `.github/scripts/sync_versions.py`; partial if only one of the pair), `security.yml` |
  | Dependabot | `.github/dependabot.yml` |
  | Changelog | `CHANGELOG.md` |
  | README | `README.md` |
  | License | `LICENSE` / `LICENSE.txt` / `LICENSE.md` (not written by this run — listed so a mismatch with the chosen license shows) |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it." (If
  `on-existing` came via `args`, say which choice will be applied to every existing component instead.)

The keep/update/replace questions themselves are asked inside each sub-skill — never here — so nothing is asked twice.

## Step 1 — Collect the project's identity

**`mode: existing` with a Quarkus build on disk**: skip this step — `iru-setup-quarkus-service` reads the identity
from the build (or `quarkus-stack.yml`) and asks only for what it can't find; record in Step 9 that identity came from
the existing files.

Otherwise ask directly (plain conversation, free-text) for whatever `args` didn't resolve: **groupId**, **artifactId**,
**base package** (need not equal groupId), **one-line description**, **developer name / email / organizationUrl**.
Leave `version` to the service skill's default (`1.0.0-SNAPSHOT`). Then the **license** with `AskUserQuestion`:

- Apache License 2.0 (recommended) — `The Apache License, Version 2.0` / `http://www.apache.org/licenses/LICENSE-2.0.txt`
- MIT License — `The MIT License` / `https://opensource.org/license/mit`
- No license (proprietary / all rights reserved — common for internal services)
- Other — ask for the display name and URL afterwards

## Step 2 — Collect the shape and the pipeline parameters

Ask directly, each with its default so a blank reply accepts it:

| Parameter | Default |
|---|---|
| Integration branch | `develop` |
| Stable branch | `main` |
| Java version | `21` (offer `25` as the alternative; never below 21) |

Then, with `AskUserQuestion`, in this order, skipping anything Step 0 resolved (in `mode: existing`, the service
skill detects language/build tool/layout from the build — don't ask those three):

- **Language** — **Java** (recommended unless the repository already has Kotlin) / **Kotlin**.
- **Build tool** — **Maven** (recommended for Java) / **Gradle (Kotlin DSL)** (recommended for a new Kotlin service).
- **Layout** — **Single module** (recommended for a small service) / **Hexagonal multi-module** (`domain`/
  `application`/`infrastructure/*`/`api/*`/`app`, boundaries enforced by ArchUnit).
- **open-source** — Yes / No, no default. Drives the recommendations below.
- **visibility** — `gh repo view --json visibility -q .visibility` (lowercased) when there's a GitHub remote — tell
  the user the detected value; otherwise ask **Public** (recommended when open source) / **Private**. Open source +
  private/internal is contradictory: warn and ask which is right. `internal` behaves like `private` below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** — standard
  GitHub-hosted runners are free for public repositories. Private/internal: still offer **Yes** first, but state
  (per GitHub's current pricing — tell the user to double-check their plan) that runs consume the included minutes —
  2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud — then bill per minute; this pipeline
  runs on Linux runners (about $0.006/minute for 2-core), and a native build job, if chosen later, is the expensive
  part. **No** — "no workflows — build, test, and release locally (run `/iru-setup-quarkus-github-workflows` later)".
  `ci: no` skips Step 6 entirely and skips `ci-tests`/`pages`.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" Recommend
  **SonarCloud** (`cloud`) only when open source **and** public; otherwise say SonarCloud is free only for
  public/open-source projects (a private project needs a paid plan, per SonarSource's current pricing) and recommend
  **None**, offering **self-hosted SonarQube** second (free Community Build, you run the server). With `ci: no`, say
  nothing would send reports automatically — analysis only runs when someone runs the scanner manually with a token —
  and recommend `none`; if the user still picks cloud/self-hosted, only the build side is configured and Step 9 says
  no CI step sends reports. For cloud/self-hosted collect `sonar-organization` (suggest `<owner>-github`; self-hosted
  only if the server has organizations), `sonar-project-key` (suggest `<owner>_<repo>`), `sonar-host-url`
  (`https://sonarcloud.io`, or ask).
- **ci-tests** (only with `ci: yes`) — **Unit only** (`unit`; recommended for private/internal) — "plain and
  `@QuarkusComponentTest` tests only, Dev Services off, every `@QuarkusTest` excluded: no containers in CI, fewer
  minutes; container-backed tests stay local" — vs **Full** (`full`; recommended for public) — "also `@QuarkusTest`
  with Dev Services containers and `@QuarkusIntegrationTest` against the packaged jar on the runner's Docker —
  complete verification, considerably more minutes".
- **pages** (only with `ci: yes`) — public: **yes** without asking (say so in Step 9). Private/internal: **No
  (Recommended)** — "CI still builds the Antora docs and the API docs and uploads them as a workflow artifact" — vs
  **Yes** — "requires GitHub Pro, Team, or Enterprise Cloud; the site is PUBLIC on the internet unless the
  organization is on Enterprise Cloud with private Pages access control".
- **distribution** — "Publish a container image from CI when a release is published?" **Registry** (recommended when
  the service is deployed as a container; GHCR by default — free for public repositories, storage/transfer quotas for
  private ones) / **None** (CI builds and tests only; no `release.yml`). The image builder (Jib or Dockerfile) is asked
  inside `iru-setup-quarkus-service`.

These values are forwarded only to the sub-skills that accept them: identity + shape + `open-source`/`visibility`/
`ci`/`ci-tests`/`pages`/`distribution`/`sonar` (+ keys) to Step 3 (the service skill records them in
`quarkus-stack.yml`); `integration-branch`/`stable-branch`/`java-version` + the same pipeline keys to Step 6;
`sonar`/`ci`/`pages` to Step 8; `on-existing` — only when it came via `args` — to every file-writing sub-skill
(Steps 3–8).

## Step 3 — Run `iru-setup-quarkus-service`

Always invoke it, whatever `mode` is — it owns the keep/update/replace decision for the manifest and every build/
source/config file. Format Steps 1–2's answers as `key: value` lines (omit keys that weren't resolved, so the
sub-skill asks them; omit identity keys in `mode: existing`) and invoke:

```
Agent({
  description: "Run setup-quarkus-service",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-quarkus-service\", args: \"mode: <mode>\\ngroup-id: <group-id>\\n
    artifact-id: <artifact-id>\\npackage: <base-package>\\ndescription: <description>\\n
    developer-name: <developer-name>\\ndeveloper-email: <developer-email>\\norganization-url: <organization-url>\\n
    license: <license-name, or none>\\nlanguage: <language>\\nbuild-tool: <build-tool>\\nlayout: <layout>\\n
    java-version: <java-version>\\nopen-source: <open-source>\\nvisibility: <visibility>\\nci: <ci>\\n
    ci-tests: <ci-tests, only if ci: yes>\\npages: <pages, only if ci: yes>\\ndistribution: <distribution>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\non-existing: <on-existing, only if supplied via args>\"}). Let every
    AskUserQuestion it raises through to the user. Report back: setup mode, per component (manifest, build, wrapper,
    coverage wiring, quality config, sources, configuration, container files, Sonar config) whether it was created,
    updated, replaced, kept as is (with mismatches), or the user stopped; the resolved platform version, language,
    build tool, layout, image builder, native yes/no, and extension list; and each verification gate's result
    (compile, unit stage, container stage, quality, docs) including anything unverified and why.",
  run_in_background: false
})
```

The technology interview (persistence, messaging, clients, security, observability, native, image builder) happens
inside that skill — this orchestrator doesn't pre-ask it. If the sub-skill reports that the user **stopped** before a
usable Quarkus build existed (new repository) or the repository's build isn't Quarkus and the user stopped, stop this
skill too — there's nothing to build docs or CI around. A **Keep as is** on an existing build is fine: continue.

## Step 4 — Run `iru-setup-antora`

`Agent({description: "Run setup-antora", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-antora\"}) with no args (or, only if Step 0's args carried it, args \"on-existing:
<on-existing>\") — it derives the component name, title, and version from the repository (pom.xml, or
gradle.properties/settings.gradle.kts for a Gradle build, and README). Report back: scratch or update, the action
taken for an existing site, which files/pages were created vs already present, and whether the site build
succeeded.", run_in_background: false})`.

Run it *before* Step 6: `iru-setup-quarkus-github-workflows` checks for `docs/antora.yml`/`docs/antora-playbook.yml`
(and `docs/package-lock.json` for `npm ci`) in its survey — running Antora first means its docs job has something to
build instead of a gap to report.

## Step 5 — Run `iru-setup-java-gitignore`

`Agent({description: "Run setup-java-gitignore", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-java-gitignore\"}) with no args (or, only if Step 0's args carried it, args \"on-existing:
<on-existing>\" — the only key it accepts), deriving everything from the repository. Report back only whether
.gitignore was created, updated, replaced, or kept as is, and which categories of entries it added.",
run_in_background: false})`.

After Steps 3–4 (it detects Maven `target/` or Gradle `build/`/`.gradle/`, Kotlin's `.kotlin/`, Quarkus leftovers —
`.quarkus/`, stray `jacoco.exec` — the `.integration-test.lock/` directory the coding skills use, and the Antora build
output from what's now on disk), and before Step 6 so build output is ignored before CI reports appear locally.

## Step 6 — Run `iru-setup-quarkus-github-workflows`

**Skip this step entirely when `ci: no`**: don't invoke the sub-skill, write no `.github/workflows/*`,
`security.yml`, `.github/dependabot.yml`, or sync script, and leave any existing workflow files untouched. Record
"skipped (ci: no)" for workflows and Dependabot, and that the shared security block (Dependabot, dependency-review,
CodeQL, OSV-Scanner, gitleaks) was skipped with them — `/iru-setup-quarkus-github-workflows` adds it all later.

Otherwise:

```
Agent({
  description: "Run setup-quarkus-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-quarkus-github-workflows\", args: \"integration-branch: <integration-branch>\\n
    stable-branch: <stable-branch>\\njava-version: <java-version>\\nopen-source: <open-source>\\n
    visibility: <visibility>\\nci-tests: <ci-tests>\\npages: <pages>\\ndistribution: <distribution>\\n
    sonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nmode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}) — it
    reads quarkus-stack.yml for the build tool, language, layout, image builder, registry and native choice. Let
    every AskUserQuestion through. Report back: scratch or update; per file (build.yml/release.yml/sync.yml +
    sync_versions.py/security.yml/.github/dependabot.yml) created, updated, replaced, kept as is (with mismatches),
    not written (release.yml with distribution: none) or stopped; whether actionlint passed; the required-secrets
    table and repository-settings notes; and every open gap it flagged.",
  run_in_background: false
})
```

`ci` itself is never forwarded (invoking the skill means CI is wanted), and the four `security-*` keys are left at
their `yes` default — note that once in Step 9 rather than asking per run. If the user chose to stop inside it, note
it in Step 9; the build, docs, and `.gitignore` from Steps 3–5 stand on their own.

## Step 7 — Run `iru-setup-changelog`

`Agent({description: "Run setup-changelog", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-changelog\"}) with no args (or, only if Step 0's args carried it, args \"on-existing:
<on-existing>\"), working from the repository's own git tag/GitHub Release history. Report back only whether
CHANGELOG.md was created (and the version range reconstructed), updated (releases appended), replaced, or kept as
is.", run_in_background: false})`. Independent of Steps 3–6; it runs before Step 8 so the README's Documentation
section links an existing `CHANGELOG.md`. In `mode: new` (no tags) it bootstraps an `[Unreleased]`-only file or stops
per its own question.

## Step 8 — Run `iru-setup-readme`

`Agent({description: "Run setup-readme", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages, or no when ci: no>\\n
on-existing: <on-existing, only if supplied via args>\"}) — sonar: none suppresses Sonar badges/links, ci: no omits
CI badges, pages: no avoids a github.io docs link — deriving everything else from the repository (the build files,
quarkus-stack.yml, the Antora docs, the workflows, CHANGELOG.md). For a service, the installation section should be
'run it locally' (./mvnw quarkus:dev or ./gradlew quarkusDev, Docker for Dev Services) and the container image when a
release.yml publishes one, not a dependency snippet. Report back only whether README.md was created, updated,
replaced, or kept as is, and which sections were included vs omitted and why.", run_in_background: false})`.

Run it last: its badges, status table and links are only as complete as what Steps 3–7 left on disk. In `mode: new`
many sections stay sparse (no tags, no CI runs yet) — expected; it omits what it can't confirm.

## Step 9 — Report

Summarize all six delegated skills together: repeat Step 0's **Setup mode** statement; the identity and license (or
"taken from the existing files"); language/build tool/layout, Java version, platform version and extensions (from
Step 3); the branches; then a per-component table — manifest, build, sources, quality config, Sonar config, Antora
docs, `.gitignore`, each workflow file (`build.yml`/`release.yml`/`sync.yml` + script/`security.yml`),
`.github/dependabot.yml`, `CHANGELOG.md`, `README.md` — with the action actually taken: **created / updated / replaced
/ kept as is / skipped (ci: no) / not written (distribution: none)** (or stopped), plus every mismatch a sub-skill
listed for a kept file. Include Step 3's verification results (and anything unverified, e.g. no Docker for the
container stage).

State explicitly what was wired or deliberately omitted, and why:

- **`open-source`** / **`visibility`** — as resolved, and where visibility came from (`args`, `gh repo view`, the user).
- **`ci`** — with `no`: no workflows, no `security.yml`, no Dependabot, the shared security block skipped,
  `/iru-setup-quarkus-github-workflows` adds them later. For a private/internal repository with `ci: yes`, repeat the
  included-minutes note (per GitHub's current pricing — double-check your plan), and the native job's cost if chosen.
- **`ci-tests`** — `unit` (no containers in CI; `@QuarkusTest`/`@QuarkusIntegrationTest` run only locally and in the
  coding skills' group stage) or `full` — omitted when `ci: no`.
- **`pages`** — `yes` (defaulted for public, or chosen) / `no` (docs uploaded as a workflow artifact, no github.io
  site) — omitted when `ci: no`.
- **`distribution`** — `registry` (image builder, registry, `release.yml` written) or `none` (no `release.yml`).
- **`sonar`** — `cloud`/`self-hosted`/`none`; whether the build's Sonar config and the CI analysis step were included;
  for `none`, whether that's the expected default (not open source, not public, or `ci: no`) or a direct choice; for
  cloud/self-hosted with `ci: no`, that no CI step sends reports.
- The four `security-*` jobs left at their `yes` default (Step 6).

Unless Step 6 was skipped, reproduce the required-secrets table and repository-settings notes
`iru-setup-quarkus-github-workflows` reported (workflow permissions allowing PR creation for `sync.yml`, Pages source,
Code scanning, GHCR package visibility) verbatim.

Finish with **Warn explicitly**, repeating the sub-skills' own warnings: **review every generated file before
building, committing, or relying on CI** — `./mvnw verify` / `./gradlew check` should pass locally (Docker running for
Dev Services); the license must match an actual `LICENSE` file if one was chosen (`/iru-check-license` can generate
one and backfill headers); production settings (database URL, credentials, OIDC issuer) come only from the
environment; a `SONAR_TOKEN` secret and a matching Sonar project must exist before a CI scan succeeds; the workflows'
branch names, image coordinates, and the Sync workflow's minor-bump default should be checked before the first real
release; and the sample feature exists only so the gates have something to run — replace it with the first real
feature.
