---
name: iru-setup-java-github-workflows
description: "Invoked as /iru-setup-java-github-workflows with optional key: value args, usually by iru-setup-java-library-repository. Creates or updates the develop, main, sync and security GitHub Actions workflows plus Dependabot for a Java/Maven library: build, static analysis, JaCoCo, Sonar, docs to Pages, Maven Central publish and version sync."
model: sonnet
---

# Setup Java GitHub Workflows

Scaffold (or update) four GitHub Actions workflows plus a Dependabot config for a Java/Maven library:

- **`develop.yml`** — runs on every push to the integration branch. Build, test, static analysis, coverage, an
  optional SonarQube/SonarCloud scan (via the `sonar-maven-plugin`, not a standalone `sonar-scanner` step, gated
  by `sonar`), an Antora docs build, a publish to GitHub Pages (or, when `pages: no`, a workflow-artifact upload
  of the same docs), and an optional publish to Maven Central (gated by `publish`). With `ci-tests: unit` the test
  run skips Failsafe integration tests (`-DskipITs`).
- **`main.yml`** — the same pipeline, but triggered when a GitHub Release is published rather than on every push.
- **`sync.yml`** — triggered when a GitHub Release is published from the stable branch. Opens a pull request into
  the integration branch that merges the released branch back in and bumps the development snapshot version in
  `pom.xml`, `README.md`, and the Antora docs, via the companion script `.github/scripts/sync_versions.py`. This
  closes the gap where a release branch's version/doc bump never makes it back into the integration branch without
  a manual follow-up. The branch is `sync_<released version>` (release `1.0.1` → `sync_1.0.1`); on a merge conflict
  the PR opens from the release commit with no bump, for manual resolution, so a patch sync never lowers the
  integration branch's development version.
- **`security.yml`** — runs on every pull request and push to the integration/stable branches: a
  `dependency-review` job (`actions/dependency-review-action@v5`), a CodeQL job (`github/codeql-action@v4` for
  Java, with the JDK set up by `actions/setup-java@v6` using `cache: maven` so the CodeQL build doesn't re-download
  the whole dependency tree on every run — the same major and cache setting `develop.yml`/`main.yml` use), an
  OSV-Scanner job (the `google/osv-scanner-action` reusable workflow), and a `gitleaks` job
  (`gitleaks/gitleaks-action@v3`) — each individually omittable via `args` (defaulting to on; see Step 1).
- **`.github/dependabot.yml`** — grouped weekly dependency updates for the `maven` ecosystem and for
  `github-actions` itself. Always generated (or updated) regardless of the four `security-*` `args` keys above —
  it's baseline repository hygiene, not an optional CI job.

This skill is designed for Maven projects — the templates in Step 3 assume Maven coordinates, profiles, and
`mvn` commands. If `pom.xml` is missing at the repository root, don't stop automatically: warn the user that this
skill's templates assume Maven and most of Step 1's survey won't resolve, then use `AskUserQuestion` to ask
whether to stop here or continue anyway. If they choose to continue, proceed through the remaining steps but treat
every Maven-derived placeholder in Step 3/4 (Java version, publishing server id, signing/extras profile ids,
`mvn` commands) as an open gap to ask the user about directly, and call this out prominently again in Step 8's
report.

**Templates.** The workflow, script and Dependabot templates of Step 3 are files under this skill's `templates/` directory, not inline text. This skill has a single variant (`common`); read the template files for the workflows being written (the index at the top of Step 3 says which), each in full, and nothing else.

## Step 1 — Survey the target repository

This skill can be invoked stand-alone (`/iru-setup-java-github-workflows`) or as a step inside another skill, such as
`iru-setup-java-library-repository`, which resolves the six pipeline parameters below itself (plus, since it
collects them anyway in its own Step 1, `groupId`/`artifactId`) and passes them through `args` as `key: value`
lines, one per line, e.g.:

```
integration-branch: develop
java-version: 21
publishing-server-id: central
extras-profile-id: build-extras
sign-profile-id: sign
settings-file: mvnsettings.xml
group-id: com.example
artifact-id: my-library
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_my-library
sonar-host-url: https://sonarcloud.io
security-dependency-review: yes
security-codeql: yes
security-osv: yes
security-gitleaks: yes
visibility: public
ci-tests: full
pages: yes
mode: new
on-existing: ask
```

Parse any such lines from `args` first. For each of `integration-branch`, `java-version`,
`publishing-server-id`, `extras-profile-id`, `sign-profile-id`, `settings-file`, `group-id`, `artifact-id`,
`open-source`, `publish`, `sonar` (+ `sonar-organization`/`sonar-project-key`/`sonar-host-url`), and
`security-dependency-review`/`security-codeql`/`security-osv`/`security-gitleaks`, `visibility`, `ci-tests`,
`pages`, `mode`, and `on-existing` found there, use that value
directly — skip the corresponding fact-finding bullet below entirely for it, since it's already resolved (and, for
`group-id`/`artifact-id`, skips re-reading them from `pom.xml`). If `args` is absent or doesn't look like this
format, treat everything as unset and gather every fact below as usual.

`publish` and `sonar` gate this skill's own generated content — they aren't independently re-derived from
`pom.xml` beyond what Step 1 already surveys below (the Central/Nexus plugin and SonarQube/SonarCloud config
bullets); if `args` doesn't supply them, fall back to whatever that survey finds (a `central-publishing-maven-plugin`
or `nexus-staging-maven-plugin` present implies `publish: yes`; a configured `sonar-maven-plugin` implies `sonar:
cloud` or `sonar: self-hosted` depending on `sonar.host.url`). When neither `args` nor the survey resolves
`publish` or `sonar` (a stand-alone run against a `pom.xml` with no publishing plugin and no `sonar-maven-plugin`),
first ask **open-source** with `AskUserQuestion` — is this repository open source? Yes / No, no default, skipped
when `args` already carries `open-source` — because it drives the recommended default of both follow-up
questions, the same way `iru-setup-java-library`'s own Step 4 asks them: recommend `publish: yes` when open
source and `no` otherwise (Maven Central is for redistributable artifacts); for `sonar`, first resolve
`visibility` and then ask it as described under "Visibility, CI test scope, and Pages" below (SonarCloud
recommended only for a public open-source repository; otherwise **None**, with **self-hosted SonarQube** (`sonar:
self-hosted`) as the second option) — then collect `sonar-organization`/`sonar-project-key`/
`sonar-host-url` for `cloud`/`self-hosted` as the SonarQube/SonarCloud bullet below describes. The four
`security-*` keys each default to `yes` when not supplied via `args` and are not otherwise
surveyed — this skill always adds/keeps the corresponding `security.yml` job unless the user (or an orchestrator's
`args`) explicitly opts out. `.github/dependabot.yml` has no matching opt-out key — it's always generated/updated.

Ignore any other key (e.g. `ci`) — invoking this skill means CI is wanted, so it never asks whether to generate
workflows at all. `mode` (`new`/`existing`) is informational: it doesn't skip any question — every pre-existing
file is still handled per Step 2 — and `on-existing` (`ask` default / `keep` / `update` / `replace`) is what lets a
scripted run skip Step 2's per-file question.

**Visibility, CI test scope, and Pages (cost-aware choices).** Resolve these after `open-source` is known and
before the `sonar` question, skipping any already supplied via `args`:

1. **`visibility`** — if not in `args`: when the repository has a GitHub remote, run `gh repo view --json
   visibility -q .visibility` (returns `PUBLIC`/`PRIVATE`/`INTERNAL`; lowercase it) and tell the user the detected
   value; otherwise (no remote yet, `gh` unavailable, not GitHub) ask with `AskUserQuestion` — **Public** /
   **Private** — recommending Public when `open-source: yes`, otherwise Private. If `open-source: yes` but the
   visibility is `private`/`internal`, warn that this is contradictory (an open-source project's repository is
   normally public, and a private repository forfeits the free-for-public pricing below) and ask which is right.
   `internal` behaves like `private` for every decision below. State the cost facts per GitHub's current pricing
   (tell the user to double-check their plan): standard GitHub-hosted runners are free for public repositories;
   private/internal repositories consume the account's included minutes (2,000/month on GitHub Free, 3,000 on Pro
   and Team, 50,000 on Enterprise Cloud), then are billed per minute — this pipeline runs on Linux runners
   (`ubuntu-latest`), the cheapest rate (about $0.006/minute for 2-core).
2. **`sonar`** — when it still has to be asked (see the paragraph above), reword it as "Configure SonarCloud /
   SonarQube and send CI analysis and coverage reports to it?" and recommend **SonarCloud** only when the
   repository is public **and** open source; otherwise state that SonarCloud (SonarQube Cloud) is free only for
   public/open-source projects and a private project needs a paid plan, and recommend **None**, offering
   **self-hosted SonarQube** (Community Build is free, but you run the server) as the second option.
3. **`ci-tests`** — "Which tests should CI run?" with `AskUserQuestion` (header e.g. `CI tests`):
   - **Unit only** (`unit`; recommended for private/internal repositories) — "Surefire `*Test` unit tests only
     (cheaper: fewer minutes, faster feedback); Failsafe `*IT` integration tests stay local. CI adds `-DskipITs`."
   - **Full** (`full`; recommended for public repositories) — "Surefire unit tests plus Failsafe `*IT`
     integration tests (complete verification, uses more minutes)."
   First check `pom.xml`: if it declares no `maven-failsafe-plugin`, or declares it without binding its
   `integration-test`/`verify` goals (as this catalog's `iru-setup-java-library` template does), integration tests
   don't run in CI either way — tell the user `ci-tests` has no practical effect until Failsafe executions are
   configured, still record the answer (default `unit` for private, `full` for public, without asking when it
   makes no difference), and say so in Step 8.
4. **`pages`** — public repositories default to **yes** without asking (state it in Step 8's report). For
   private/internal repositories, ask with `AskUserQuestion` (header e.g. `Pages`):
   - **No (Recommended)** — "CI still builds the Antora docs and the Maven site report and uploads them as a
     workflow artifact; nothing is published."
   - **Yes** — "Requires GitHub Pro, Team, or Enterprise Cloud (not GitHub Free); the site will be PUBLIC on the
     internet unless the organization is on Enterprise Cloud and publishes it with private access control."

Gather the remaining facts before writing anything; every placeholder in Step 3's templates must be resolved from
a real answer, not guessed. If `pom.xml` is missing and the user chose to continue anyway, skip the
`pom.xml`-derived facts below and ask the user for equivalent values directly instead:

- **Maven coordinates** (skip `groupId`/`artifactId` if supplied via `args`): read whichever of
  `groupId`/`artifactId`/`version` weren't already supplied from `pom.xml` — `version` is always read here, since
  it isn't known yet at the point `iru-setup-java-library-repository` collects its own Step 1 fields.
- **Java version** (skip if supplied via `args`): read `maven.compiler.source`/`maven.compiler.target` (or
  `maven.compiler.release`) from `pom.xml` properties. If `pom.xml` declares none of them (or there's no `pom.xml`
  and the user chose to continue anyway), ask the user, offering `21` as the default — this catalog's default Java
  version for new repositories, and the one `iru-setup-java-library` writes into a generated `pom.xml`. Whenever
  the value found in `pom.xml` differs from `21`, use what `pom.xml` says (CI must build what the project actually
  targets) and note the difference in Step 8's report.
- **Central/Nexus publishing plugin** (skip entirely if `publish` was supplied via `args`; skip only the
  `publishing-server-id` half if that alone was supplied): grep `pom.xml` for `central-publishing-maven-plugin`
  (the current Sonatype Central Publishing Portal plugin) or `nexus-staging-maven-plugin` (the older OSSRH
  plugin). If found, treat that as `publish: yes` and record the `publishingServerId`/`serverId` value it declares
  (e.g. `central`) — the workflow's `setup-java` `server-id` and the Maven settings file's `<server><id>` must
  both match this value. If neither plugin is configured, treat that as `publish: no` by default rather than
  stopping — tell the user Maven Central publishing isn't wired up in `pom.xml` and ask only if they want it added
  now (Step 5 can add it) or confirm `publish: no` is correct. When `publish: no` (from `args` or this survey),
  the deploy step, its `setup-java` signing inputs, and `mvnsettings.xml` are all omitted — see Step 3/5/6.
- **GPG signing profile** (skip if `sign-profile-id` supplied via `args`): find the Maven profile that binds
  `maven-gpg-plugin`'s `sign` goal. Record its profile id (e.g. `sign`).
- **Extras profile** (skip if `extras-profile-id` supplied via `args`): find the profile — typically active by
  default — that attaches sources/javadoc jars (e.g. via `maven-source-plugin` + `maven-javadoc-plugin`). Record
  its id (e.g. `build-extras`). This is the profile the plain test/coverage run excludes (`-P '!<id>'`) so a quick
  `mvn test` doesn't rebuild javadoc every time, while the final `deploy` includes it.
- **Static analysis / coverage plugins**: confirm `jacoco-maven-plugin`, `maven-checkstyle-plugin`,
  `spotbugs-maven-plugin`, and `maven-pmd-plugin` are configured under `<reporting>` — these feed the `mvn site`
  report. Not required to proceed, but note any that are missing so the user knows that section of the site report
  will be empty.
- **SonarQube/SonarCloud config** (skip entirely if `sonar` was supplied via `args`): check `pom.xml` for the
  `sonar-maven-plugin` (`org.sonarsource.scanner.maven:sonar-maven-plugin`) and its `sonar.organization`/
  `sonar.projectKey`/`sonar.host.url` properties. This house pattern runs the scan via `mvn sonar:sonar` in the
  workflow (Step 3), so the config lives in `pom.xml` itself — not a standalone `sonar-project.properties` file
  (that file, and the `warchant/setup-sonar-scanner` action plus a separate `sonar-scanner` CLI step, are the
  older pattern this skill no longer generates). If the plugin is present, treat that as `sonar: cloud` (when
  `sonar.host.url` is `https://sonarcloud.io` or unset) or `sonar: self-hosted` (any other host), and this skill
  wires up the `Run SonarCloud analysis` step (Step 3) — but if the `sonar.*` properties are incomplete, flag this
  and offer to add them in Step 5 if the user can give you `sonar.organization`/`sonar.projectKey` now. If the
  plugin is absent, default to `sonar: none` (omit the analysis step entirely — see Step 3) rather than generating
  a step that can't succeed; ask the user only if they want Sonar wired up now instead. If a stray
  `sonar-project.properties` still exists from before this house pattern changed, note in Step 8 that it's now
  redundant (nothing reads it once `pom.xml` carries the same settings) and offer to remove it.
- **Antora docs**: check for `docs/antora.yml` and `docs/antora-playbook.yml`. If either is missing, the docs step
  in Step 3 has nothing to build against — tell the user to run the `iru-setup-antora` skill first (the docs step runs `npm ci` in `docs/`, so `docs/package.json` and the committed `docs/package-lock.json` are needed too), or confirm they'll
  do so before merging this workflow. If `docs/` exists but has no lockfile (a site from the old per-package install), recommend `/iru-update-antora` instead: `actions/setup-node`'s npm cache fails the job without it.
- **Maven settings file used for deploy** (skip if `settings-file` supplied via `args`): check for an XML file
  referenced by a `--settings` flag in any existing deploy step, defaulting to `mvnsettings.xml` at the repository
  root if none is referenced yet. If it doesn't exist, this skill creates it (Step 6).
- **Branch names** (skip the integration branch if `integration-branch` supplied via `args`): confirm the
  integration branch (commonly `develop`) and the stable branch (commonly `main`) actually match this
  repository's branching model — run `git branch -a` or ask, don't assume gitflow defaults. Both are needed for
  `sync.yml` (Step 3): the stable branch is what it checks the release was published from, the integration branch
  is where its pull request lands.
- **README/Antora version-snippet wording** (only needed for `sync.yml`'s companion script,
  `sync_versions.py`): rather than reading `README.md` and every matched Antora page in full, grep for just the
  marker text and its surrounding lines — `grep -B2 -A2 -i "current development version\|latest release\|latest
  snapshot" README.md`, and for Antora pages, `grep -rl "<version>" docs/modules/ROOT/pages/*.adoc` to find which
  pages mention a version at all, then `grep -B2 -A2 "<version>" <matched-page>` on each one found. From that
  context, find the exact row label used for the development-snapshot row (e.g. `Current development version`)
  and the exact marker text preceding each `<version>` tag (e.g. `Latest release`/`Latest snapshot`, possibly with
  a trailing qualifier like `Latest release shown here`). `sync_versions.py`'s regexes must match this
  repository's actual wording, not just the `iru-setup-readme` skill's default convention — note any deviation now so
  Step 4 can adjust the template correctly. If `README.md` doesn't exist yet, or was not produced by the
  `iru-setup-readme` skill, tell the user `sync_versions.py`'s README step will need hand-adjustment once the file
  exists.

## Step 2 — Announce scratch vs update, and decide per existing file

Check whether `.github/workflows/develop.yml`, `.github/workflows/main.yml`, `.github/workflows/sync.yml`,
`.github/scripts/sync_versions.py`, `.github/workflows/security.yml`, and `.github/dependabot.yml` already exist.
Treat `sync.yml` and its script as one unit for this check — either both exist or neither should (if only one does,
the unit is **partial**; say so).

- **None exist**: tell the user this skill is **creating the CI/CD pipeline from scratch**, skip the rest of this
  step and go straight to Step 4 — create everything from scratch.
- **Any exists**: tell the user this skill is **updating an existing CI/CD setup**, list each file as present /
  absent / partial, and warn that prior workflow files were found. Absent files are simply created. For each
  existing unit (`develop.yml`, `main.yml`, `sync.yml`+`sync_versions.py`, `security.yml`,
  `.github/dependabot.yml`), read it and compare it against this run's resolved choices, naming each concrete
  contradiction in its question — e.g. "your current `develop.yml` runs a SonarCloud step but this run chose
  `sonar: none`", "it deploys to GitHub Pages but you chose `pages: no`", "it runs integration tests (no
  `-DskipITs`) but you chose `ci-tests: unit`", "it has no Maven Central deploy step but `publish: yes`".

  If `on-existing` (Step 1) is `keep`, `update`, or `replace`, apply that choice to every existing unit without
  asking (still print the warning). Otherwise ask with `AskUserQuestion`, one question per existing unit, batching
  up to 4 questions per call (a second call for the fifth), each explaining the consequences:

  - **Update (Recommended)** — "Merge: add what's missing and correct outdated parts (action versions, gated
    steps, stale values) using this skill's template as reference; keep every custom step/job/entry this skill
    doesn't own. You review a diff; your customizations survive, but a customized part this skill owns may be
    rewritten."
  - **Keep as is** — "Leave the file untouched. Nothing you customized changes, but it may stay out of date or
    disagree with this run's choices (the mismatches named above) — the report lists each one."
  - **Replace** — "Regenerate from this skill's template. Result matches the house pattern exactly, but every
    customization in the file is lost (recoverable only from git history)."
  - **Stop** — "End this skill now without changing any file" (only offered in the first question of the run;
    choosing it ends the skill — report which file(s) already exist and make no changes).

  Then, per unit:

  - **Keep**: don't write that file in Step 6; record each mismatch with this run's choices for Step 8.
  - **Replace**: write that file fresh from the filled-in template in Step 6, exactly as for a new file.
  - **Update**: proceed to Step 4, but treat the existing file as the base to edit, not as something to overwrite
    wholesale, and show the user a diff before writing it in Step 6:
    - For `develop.yml`/`main.yml`: preserve any step that isn't one of the pipeline stages this skill owns
      (build/test, static analysis, coverage, site report, optional SonarQube, Antora build, GitHub Pages
      publish or docs-artifact upload, optional Maven Central publish) — e.g. a Slack notification step or an
      extra test matrix stays untouched. Only add missing stages or correct outdated ones (wrong action version,
      wrong profile id, a stage that should now be gated on/off per `publish`/`sonar`/`pages`, a missing or
      stale `-DskipITs` per `ci-tests`, etc.), and don't reorder steps this skill doesn't own.
    - For `sync.yml`/`sync_versions.py`: preserve any file the script updates beyond `pom.xml`/`README.md`/the
      Antora docs that a prior customization added, and preserve any extra workflow step (e.g. a Slack
      notification) the same way. Only correct the pipeline stages this skill owns (branch/version computation,
      the merge, the version bump, the PR creation) or fix a stale placeholder value.
    - For `security.yml`: preserve any job this skill doesn't own; only add/remove/update the
      `dependency-review`/CodeQL/OSV-Scanner/gitleaks jobs per the resolved `security-*` `args` (Step 1).
    - For `.github/dependabot.yml`: preserve any `updates:` entry for an ecosystem this skill doesn't manage
      (e.g. `npm` for `docs/`); only add/update the `maven` and `github-actions` entries.

## Step 3 — Reference templates

**Templates.** Every template below is a file under `templates/common/<path of the generated file>`, holding the exact bytes of that generated file's template. Read the files for the units being written, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/develop.yml` | `templates/common/.github/workflows/develop.yml` | always: push to the integration branch pipeline |
| `.github/workflows/main.yml` | `templates/common/.github/workflows/main.yml` | always: same pipeline, triggered by a GitHub Release being published |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | always, written together with `sync_versions.py`: opens the sync PR back into the integration branch after a release |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | always, written together with `sync.yml`: rewrites version references after a release |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | always; drop each whole job whose `security-*` arg resolved to `no` |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | always, regardless of the `security-*` flags |

These are genericized examples — based on a real Java/Maven repository's actual `develop.yml`/`main.yml` — showing
the full pipeline: checkout → JDK setup (with signing configured, when `publish: yes`) → test + coverage + site
report → SonarQube scan (when `sonar` isn't `none`) → Antora docs build → merge docs with the site report →
publish to GitHub Pages (or upload the merged docs as a workflow artifact when `pages: no`) → deploy to Maven
Central (when `publish: yes`). Copy the structure; resolve every `<placeholder>` using Step 1's survey before
writing the real files in Step 4, and drop or swap whichever blocks this repository's
`publish`/`sonar`/`pages`/`ci-tests` values gate, per the inline comments in the templates below.

### `develop.yml` template

Template: `templates/common/.github/workflows/develop.yml` — read it in full before filling placeholders

### `main.yml` template

Identical pipeline; the only difference is the trigger — a GitHub Release being published instead of a push:

Template: `templates/common/.github/workflows/main.yml` — read it in full before filling placeholders

Notes on placeholders that are genuinely project-specific and must come from Step 1, not from these templates:

- `<integration-branch>`: the branch `develop.yml` triggers on (e.g. `develop`).
- `<java-version>`: e.g. `21` (this catalog's default for new repositories).
- `<publishing-server-id>`: the id shared by the Central/Nexus plugin config and the settings file's `<server>`.
  Not needed at all when `publish: no` — the `setup-java` signing inputs and the deploy step that use it are
  omitted entirely.
- `<sign-profile-id>` / `<extras-profile-id>`: the two profile ids found in Step 1. When `publish: no`,
  `<sign-profile-id>` isn't needed (the whole `Deploy to maven central` step — the only place it's used — is
  omitted); `<extras-profile-id>` is still needed, since the plain test run always excludes it (`-P
  '!<extras-profile-id>'`) regardless of publishing.
- `<settings-file>`: the Maven settings XML path used for the deploy step (Step 6 creates it, only when
  `publish: yes` — see Step 5).
- `<skip-its>`: ` -DskipITs` (with a leading space) when `ci-tests: unit`; empty when `ci-tests: full`.
- Pages: these templates publish by pushing to the `gh-pages` branch with the job's `GITHUB_TOKEN` — there is no
  `pages: write`/`id-token: write` permission block to drop when `pages: no`; just swap the deploy step for the
  artifact upload as commented. If an existing workflow being updated uses `actions/configure-pages`/
  `upload-pages-artifact`/`deploy-pages` instead, `pages: no` replaces those three with the artifact upload and
  drops the `pages: write`/`id-token: write` permissions only they needed.

### `sync.yml` template

Runs once a GitHub Release is published, provided it was published from the stable branch. Opens a pull request
that merges the released branch back into the integration branch and bumps the development snapshot version via
the companion `sync_versions.py` script (below):

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

Notes specific to this template:

- The sync branch is named after the released version (`sync_1.0.1`), so the "already synced" guard is per release.
  On a merge conflict (usual for patch releases) the merge is aborted and the PR opens from `origin/<stable-branch>`
  with no bump and a "merge conflicts to resolve" title; a conflicting sync never runs the version script.
- `<stable-branch>`: the branch releases are published from (e.g. `main`, sometimes `master`) — resolved in Step 1,
  used both in the `if:` guard and the merge source.
- The snapshot bump always assumes a minor-version bump with the patch reset to `0` (e.g. release `1.3.0` → next
  snapshot `1.4.0-SNAPSHOT`) — this matches the conventional gitflow pattern where a release branch is cut from a
  pre-bumped `-SNAPSHOT` on the integration branch. If the target repository does patch-level releases too, flag
  this as an open gap in Step 8 rather than silently guessing which bump the user wants.
- `CHANGELOG.md` is deliberately not touched by this workflow: whatever process turns the release branch's
  `[Unreleased]` section into a dated release section (by hand, or via a skill like this repository's own
  `/iru-release`) already happens before the tag is published, so it arrives on `<integration-branch>` unchanged via
  the merge step above.
- The `permissions:` block is necessary but not sufficient — flag in Step 7 that the repository's Settings → Actions
  → General → "Workflow permissions" must also allow GitHub Actions to create pull requests, or `gh pr create` will
  fail with a permissions error even though the token has the right scopes declared here.

### `sync_versions.py` template

Companion script for `sync.yml`, at `.github/scripts/sync_versions.py`. Reads `RELEASE_VERSION`/`NEXT_SNAPSHOT`
from the environment and rewrites every version reference the merge step doesn't already carry forward. The
`<artifact-id>` and README/Antora regexes below are placeholders — resolve them from Step 1's survey of this
repository's actual `pom.xml` artifact id and README/Antora wording before writing the real file:

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

Notes specific to this template:

- `<artifact-id>`: the Maven `artifactId` from Step 1's survey — the `update_pom` regex anchors on the
  `<artifactId>` element immediately preceding the `<version>` to bump, so it never touches a dependency or plugin
  version elsewhere in `pom.xml`.
- `update_antora_component_version` and `update_antora_pages` both no-op cleanly if `docs/antora.yml` or
  `docs/modules/ROOT/pages` don't exist, so this script is safe to write even before the `iru-setup-antora` skill has
  run — no need to gate its creation on Antora being present.
- The Antora component version (`docs/antora.yml`) is intentionally set to the release version, not the next
  snapshot — matching the convention (used by this repository's own `/iru-release`-style workflow, if present) that
  the Antora component version tracks the latest actual release.

### `security.yml` template

Runs on every pull request and on every push to the integration/stable branches, plus a weekly schedule so CodeQL
and OSV-Scanner findings don't go stale between pushes. Each job below is individually omittable via the matching
`security-*` `args` key from Step 1 (default `yes`); drop that whole job's YAML — not just its `if:` — when the
resolved value is `no`, so a skipped-and-green job doesn't sit in the workflow forever:

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

Notes specific to this template:

- `<stable-branch>`: same branch resolved in Step 1 for `sync.yml` (e.g. `main`).
- The `codeql` job's `Build` step reuses the same `-P '!<extras-profile-id>'` convention as `develop.yml`'s `Run
  tests` step — CodeQL only needs compiled classes, not the full test/coverage/site pipeline.
- If `github.com` public-repository default CodeQL setup is simpler for this repository than a workflow-based
  CodeQL job (e.g. the user wants GitHub to manage the CodeQL configuration itself), note that as an alternative
  in Step 8's report instead of writing the `codeql` job — recommending the repository's Settings → Code security
  → "CodeQL analysis" → **Set up** → **Default** — but default to the workflow job above unless the user asks for
  that instead, since default setup can't be reviewed or customized from this repository's own files.
- None of these four jobs need a repository secret beyond the automatically provided `GITHUB_TOKEN` — don't add
  any of them to Step 7's secrets table.

### `.github/dependabot.yml` template

Always generated (or updated) regardless of the four `security-*` flags above — grouped weekly updates for the
`maven` ecosystem (the repository's own dependencies/plugins) and for the workflows this skill itself just wrote
(`github-actions`):

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

If `.github/dependabot.yml` already exists with entries for other ecosystems (e.g. `npm` for `docs/`), preserve
them — only add or update the `maven` and `github-actions` entries above (see Step 2).

## Step 4 — Fill the templates

Substitute every placeholder from Step 1's survey. If any required fact wasn't resolvable there (e.g. no signing
profile exists yet, or no Central/Nexus plugin is configured), don't invent a value — ask the user or note it as an
open gap in Step 8's report instead of silently guessing.

Also apply the `publish`/`sonar`/`security-*` gating resolved in Step 1: drop every block the templates above mark
with an "Omit this … if …" comment when the matching value is `no`/`none`, and drop that same comment itself — it's
guidance for filling the template, not part of the generated workflow file. Apply `pages` the same way: keep
`Deploy to GitHub Pages` and drop `Upload documentation` when `pages: yes`, the reverse when `pages: no` (the
Antora build and docs merge stay either way). Apply `ci-tests` via `<skip-its>`.

## Step 5 — Handle missing supporting files

Before writing the workflows, make sure the files they depend on exist:

- **`docs/antora.yml` / `docs/antora-playbook.yml` missing**: recommend running the `iru-setup-antora` skill now; the
  Antora build step in the workflow will fail without them. The workflow runs `npm ci` in `docs/`, so also keep
  `docs/package.json` and `docs/package-lock.json` committed.
- **`sonar-maven-plugin`/`sonar.*` properties missing from `pom.xml`, and `sonar` isn't `none`**: ask the user for
  `sonar.organization` (if using SonarCloud), `sonar.projectKey`, and `sonar.host.url` (default
  `https://sonarcloud.io` unless they run self-hosted SonarQube), then add them directly to `pom.xml`. Skip this
  entirely when `sonar: none` — there's nothing to add, and the `Run SonarCloud analysis` step is omitted from the
  workflow templates (Step 3) anyway. Look up the current latest
  `org.sonarsource.scanner.maven:sonar-maven-plugin` version from Maven Central rather than hardcoding a version
  here — this plugin releases fairly often. Add to `<properties>`:

  ```xml
  <sonar.organization><org></sonar.organization>
  <sonar.projectKey><project-key></sonar.projectKey>
  <sonar.host.url><sonar-host-url></sonar.host.url>
  <sonar.coverage.jacoco.xmlReportPaths>${project.build.directory}/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>
  ```

  and add the plugin itself to `<build><plugins>` (it needs no `<executions>` — the workflow invokes its
  `sonar:sonar` goal directly, not through a lifecycle phase):

  ```xml
  <!-- SonarCloud analysis -->
  <plugin>
    <groupId>org.sonarsource.scanner.maven</groupId>
    <artifactId>sonar-maven-plugin</artifactId>
    <version><sonar-maven-plugin-version></version>
  </plugin>
  ```

  `sonar.coverage.jacoco.xmlReportPaths` must point at wherever the `jacoco-maven-plugin`'s `report` execution
  writes its XML (confirm the actual path already configured in `pom.xml` — `${project.build.directory}/site/jacoco/jacoco.xml`
  is this house pattern's default) so the scan doesn't rely on autodetection finding it. `sonar.java.source` needs
  no explicit setting: the plugin reads `maven.compiler.source`/`maven.compiler.release` automatically, so the
  scan always tracks whatever Java version `pom.xml` already targets.

- **Maven settings file (`<settings-file>` from Step 3) missing, and `publish: yes`**: create it, matching the
  `server-id` resolved in Step 1. Skip this entirely when `publish: no` — nothing in the templates references a
  settings file once the `Deploy to maven central` step is omitted:

  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <settings>
    <servers>
      <server>
        <id><publishing-server-id></id>
        <username>${env.OSSRH_USERNAME}</username>
        <password>${env.OSSRH_PASSWORD}</password>
      </server>
    </servers>

    <profiles>
      <profile>
        <id>ossrh</id>
        <activation>
          <activeByDefault>true</activeByDefault>
        </activation>
        <properties>
          <gpg.executable>gpg</gpg.executable>
          <gpg.keyname>${env.SIGNING_KEY_ID}</gpg.keyname>
          <gpg.passphrase>${env.SIGNING_PASSWORD}</gpg.passphrase>
        </properties>
      </profile>
    </profiles>

    <pluginGroups>
      <pluginGroup>org.sonatype.plugins</pluginGroup>
    </pluginGroups>
  </settings>
  ```

- **`.gitignore` missing an entry for `/docs/build/` and `/target/`**: add them if absent, so the build output the
  workflow generates never gets committed by accident.

## Step 6 — Write the workflow files

Write (or, per Step 2, carefully update or replace — and skip any unit the user chose to keep)
`.github/workflows/develop.yml`, `.github/workflows/main.yml`,
`.github/workflows/sync.yml`, and `.github/workflows/security.yml` with the filled-in templates from Step 4, plus
`.github/scripts/sync_versions.py`, `.github/dependabot.yml`, and any other supporting files created in Step 5.
`sync.yml` and `sync_versions.py` are written together — never write one without the other. `security.yml` and
`.github/dependabot.yml` are always written (or updated), independent of `publish`/`sonar` — only their own
`security-*` flags (Step 1) gate individual jobs within `security.yml`, and `.github/dependabot.yml` has no gate at
all.

## Step 7 — List required repository secrets and settings

Report the secrets that must exist under the target repository's Settings → Secrets and variables → Actions —
this skill cannot create them itself. List only the rows that apply to what was actually generated (per
`publish`/`sonar` from Step 1) — an omitted row is one fewer secret the user needs to go create:

| Secret | Purpose | Only needed when |
|---|---|---|
| `SONAR_TOKEN` | Auth token for the SonarQube/SonarCloud scan, passed as `-Dsonar.token` to `mvn sonar:sonar` | `sonar` is `cloud` or `self-hosted` |
| `OSSRH_USERNAME` / `OSSRH_PASSWORD` | Maven Central (Sonatype) publishing credentials | `publish: yes` |
| `SIGNING_KEY` / `SIGNING_KEY_ID` / `SIGNING_PASSWORD` | GPG private key, its key id, and its passphrase, for artifact signing | `publish: yes` |
| `SONATYPE_STAGING_PROFILE_ID` | Only needed with the legacy `nexus-staging-maven-plugin`; if the target repo uses `central-publishing-maven-plugin` this secret is unused and can be left unset or removed from the deploy step | `publish: yes`, and only with `nexus-staging-maven-plugin` |

None of `security.yml`'s four jobs need a secret beyond `GITHUB_TOKEN` (automatic) — don't add a row for them.

`GITHUB_TOKEN` needs no setup — GitHub Actions provides it automatically. `sync.yml` uses it too (no extra secret),
but also needs the repository setting under Settings → Actions → General → "Workflow permissions" set to allow
GitHub Actions to create pull requests — the `permissions: contents: write / pull-requests: write` block in the
workflow itself is not sufficient on its own. Call this out explicitly in Step 8's report, since it's easy to miss
and `sync.yml` will fail silently-ish (a `gh pr create` permissions error) on the very first real release without it.

## Step 8 — Report and warn

Summarize what happened: whether this run created the pipeline from scratch or updated an existing one, and, per
unit (`develop.yml`, `main.yml`, `sync.yml`/`sync_versions.py`, `security.yml`, `.github/dependabot.yml`), the
action taken — **created**, **updated**, **replaced**, **kept as is** (listing each mismatch with this run's
choices found in Step 2), or untouched because of Step 2's stop path — and whether that came from the user or
`on-existing`; whether `pom.xml`'s `sonar-maven-plugin`/`sonar.*` properties and the settings XML were added versus
already present (and whether a stale `sonar-project.properties` was flagged for removal); and any open gaps noted
in Steps 1/4 (missing Central plugin, missing signing profile, missing Antora setup, README/Antora wording that
didn't match `sync_versions.py`'s default regexes, patch-level releases the minor-bump default doesn't handle,
etc.).

State explicitly what was wired versus omitted, and why:

- **`publish`**: yes/no — if `yes`, the `Deploy to maven central` step, the `setup-java` signing inputs, and
  `<settings-file>` were all generated; if `no`, all three were omitted and the corresponding `OSSRH_*`/`SIGNING_*`
  secret rows were left out of Step 7's table.
- **`sonar`**: `cloud`/`self-hosted`/`none` — if not `none`, the `Run SonarCloud analysis` step and `SONAR_TOKEN`
  row were included; if `none`, both were omitted, and note whether that's because the project isn't open source
  (SonarCloud is free only for open-source projects) or a direct user choice.
- **`visibility`**: the resolved value and where it came from (`args`, `gh repo view`, or the user), plus the
  Actions-minutes note for private/internal repositories (included minutes per plan, then per-minute billing —
  per GitHub's current pricing; double-check your plan).
- **`ci-tests`**: `unit` (Run tests appends `-DskipITs`; Failsafe `*IT` tests only run locally) or `full` — and,
  if `pom.xml` binds no Failsafe executions, that the choice currently has no practical effect.
- **`pages`**: `yes` (docs published to the `gh-pages` branch — defaulted without asking for a public repository)
  or `no` (docs uploaded as the `documentation` workflow artifact; nothing published, so the README/Antora must not
  link a github.io documentation URL — `iru-setup-readme` handles that when given `pages: no`). For a private
  repository with `pages: yes`, repeat that Pages needs Pro/Team/Enterprise Cloud and the site is public unless
  published with Enterprise Cloud private access control.
- **Security block**: which of `security-dependency-review`/`security-codeql`/`security-osv`/`security-gitleaks`
  were included in `security.yml` versus omitted per Step 1's `args`/answers, and confirm `.github/dependabot.yml`
  was written regardless (it has no opt-out).

Finish with an explicit warning: **the user must review the generated (or updated) workflow files before relying on
them.** Branch names, profile ids, the Java version, and the Maven Central publishing setup were inferred from this
repository's current state and may need correction — and because this pipeline handles signing keys and publishes
artifacts publicly, a bad assumption here has real consequences. Recommend a dry run (e.g. pushing to a throwaway
branch with the trigger temporarily adjusted, or manually triggering via `workflow_dispatch` if added) before
trusting it on the real `develop`/iru-release flow. For `sync.yml` specifically, recommend verifying it against a real
(or test) release before relying on it — confirm the computed next-snapshot version and the exact files it
touches match expectations, and confirm the "Workflow permissions" repository setting from Step 7 is enabled.
