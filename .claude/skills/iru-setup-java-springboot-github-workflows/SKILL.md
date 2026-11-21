---
name: iru-setup-java-springboot-github-workflows
description: "Invoked as `/iru-setup-java-springboot-github-workflows` (args: `stack-file`, branches, `security-*`, `ci-tests`, `pages`, `sonar`, `on-existing`), normally by `iru-setup-java-springboot`. Creates build, deploy, undeploy (optionally staged-rollout and promote) and security workflows plus Dependabot for a Spring Boot service, from the manifest."
model: sonnet
---

# Setup Java Spring Boot GitHub Workflows

Generate the workflows for a Spring Boot service (three, plus `promote.yml` when rolling deployments are enabled). This is deliberately a different pipeline from the
library-oriented `iru-setup-java-github-workflows`: a service is not published to Maven Central and is not
GPG-signed — it is built into a container image and deployed. The shared stages (test, analyse, Sonar, Antora,
GitHub Pages) follow the same house pattern as that skill, so the two are recognisably related; the publish/sign/
sync stages are replaced by image build, OpenTofu apply, and teardown.

| Workflow | Trigger | Does |
|---|---|---|
| `build.yml` | push to integration/main branch, and every pull request | build, unit tests, integration tests (only when `ci.tests: full`), static analysis, coverage, Sonar (when `sonar.mode` isn't `none`), docs; publishes Pages from the main branch only when `ci.pages: yes`, otherwise uploads the built docs as a workflow artifact |
| `deploy.yml` | `workflow_dispatch` (environment input) and optionally on release published | build + push image, OIDC auth, `tofu apply`, smoke check — or, with rolling deployments, a staged rollout (Step 2b) |
| `promote.yml` | `workflow_dispatch` (environment + target inputs); **only with rolling deployments** (not for `strategy: rolling`) | advance a rollout one step, or roll back / abort it (Step 2c) |
| `undeploy.yml` | `workflow_dispatch` only, with typed confirmation | scale to zero, or `tofu destroy` an environment |
| `security.yml` | push to main branch, every pull request, weekly schedule | dependency review, CodeQL, OSV-Scanner, gitleaks — each individually omittable |
| `.github/dependabot.yml` | n/a (config, not a workflow) | grouped weekly updates for `maven`, `docker`, `github-actions`, and `npm` when a frontend is present |

**No credential is ever written into a workflow file.** Cloud access is keyless via OIDC; everything else is a
repository or environment secret referenced by name.

## Templates

Every workflow and configuration template this skill writes is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that template; the steps below point at them. Read only the files for the variant resolved in Step 0 ("Language and build tool", plus whether rolling deployments are enabled), each in full, before filling placeholders; never read the others:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/maven/.github/workflows/build.yml` | `java` + `maven`; `kotlin` + `maven` (the base that the Kotlin + Maven deltas of Step 1 modify) |
| `.github/workflows/build.yml` | `templates/kotlin-gradle/.github/workflows/build.yml` | `kotlin` + `gradle` |
| `.github/workflows/deploy.yml` | `templates/maven/.github/workflows/deploy.yml` | Single-job `deploy.yml` (rolling deployments off, or `strategy: rolling`); `maven` variants |
| `.github/workflows/deploy.yml (Gradle variant steps)` | `templates/kotlin-gradle/.github/workflows/deploy.yml.jdk-tag-image` | `kotlin` + `gradle`: replaces the JDK, tag and image steps of the single-job `deploy.yml` |
| `step `Load rollout functions` of deploy.yml, promote.yml and undeploy.yml (written to `$RUNNER_TEMP/rollout.sh`)` | `templates/rolling/rollout-functions/common.sh` | Rolling deployments with `strategy` `blue-green`, `canary` or `linear`: the runtime-independent helpers, first in every rollout library |
| `step `Load rollout functions` of deploy.yml, promote.yml and undeploy.yml (appended after the common helpers)` | `templates/rolling/rollout-functions/ecs.sh` | Rolling deployments on AWS ECS Fargate |
| `step `Load rollout functions` of deploy.yml, promote.yml and undeploy.yml (appended after the common helpers)` | `templates/rolling/rollout-functions/cloud-run.sh` | Rolling deployments on Google Cloud Run |
| `step `Load rollout functions` of deploy.yml, promote.yml and undeploy.yml (appended after the common helpers)` | `templates/rolling/rollout-functions/kubernetes.sh` | Rolling deployments on EKS / GKE Autopilot (Argo Rollouts) |
| `.github/workflows/deploy.yml (staged)` | `templates/rolling/workflows/deploy.yml` | Rolling deployments with `strategy` `blue-green`, `canary` or `linear` (replaces the single-job `deploy.yml`) |
| `.github/workflows/promote.yml` | `templates/rolling/workflows/promote.yml` | Rolling deployments with `strategy` other than `rolling` |
| `.github/workflows/undeploy.yml` | `templates/common/.github/workflows/undeploy.yml` | Every variant (the rolling deltas are described after the template) |
| `.github/workflows/security.yml` | `templates/maven/.github/workflows/security.yml` | `java`/`kotlin` + `maven` (the Gradle changes are the fragments below); skipped entirely when all four `security-*` flags are `no` |
| `.github/workflows/security.yml (dependency-submission job)` | `templates/kotlin-gradle/.github/workflows/security.yml.dependency-submission` | `kotlin` + `gradle`, when `security-dependency-review` is on (the template lines carry the indent of the numbered list item they sit in, which the generated workflow file does not have) |
| `.github/workflows/security.yml (dependency-review job)` | `templates/kotlin-gradle/.github/workflows/security.yml.dependency-review` | `kotlin` + `gradle`, when `security-dependency-review` is on (the template lines carry the indent of the numbered list item they sit in, which the generated workflow file does not have) |
| `.github/workflows/security.yml (CodeQL job)` | `templates/kotlin-gradle/.github/workflows/security.yml.codeql-build` | `kotlin` + `gradle`, when `security-codeql` is on: replaces the matrix and the `Autobuild` step (the template lines carry the indent of the numbered list item they sit in, which the generated workflow file does not have) |
| `.github/dependabot.yml` | `templates/maven/.github/dependabot.yml` | `java`/`kotlin` + `maven` (always generated) |
| `.github/dependabot.yml (Gradle entry)` | `templates/kotlin-gradle/.github/dependabot.yml.gradle-entry` | `kotlin` + `gradle`: replaces the `maven` entry |

## Step 0 — Survey and resolve inputs

Parse `args` for `key: value` lines:

```
stack-file: springboot-stack.yml
integration-branch: develop
main-branch: main
security-dependency-review: yes
security-codeql: yes
security-osv: yes
security-gitleaks: yes
visibility: private
ci-tests: unit
pages: no
sonar: none
mode: existing
on-existing: ask
```

- **`stack-file`**/**`integration-branch`**/**`main-branch`** — as before.
- **`visibility`** (`public`/`private`/`internal`), **`ci-tests`** (`unit`/`full`), **`pages`** (`yes`/`no`),
  **`sonar`** (`cloud`/`self-hosted`/`none`) — overrides for the manifest's `visibility`, `ci.tests`, `ci.pages`,
  and `sonar.mode` (see "Resolving the CI choices" below). Normally absent: `iru-setup-java-springboot` records
  them in the manifest, which is enough.
- **`rolling`** (`yes`/`no`), **`deployment-strategy`** (`rolling`/`blue-green`/`canary`/`linear`), **`advance`**
  (`auto`/`manual`/`hybrid`), **`steps`** (comma list of percentages), **`bake-minutes`** — rolling deployments (see
  "Rolling deployments" below). Normally absent: the manifest's `deploy.rolling` block is the source of truth when
  `stack-file` is given; the `args` lines apply when there is no such block or when the caller passes them
  explicitly. Absent or `rolling: no` ⇒ rolling is off and Steps 2b/2c, the `promote.yml` rows and every "rolling"
  paragraph below are skipped.
- **`mode`** (`new`/`existing`) — informational; this skill still decides scratch-vs-update per file from what's on
  disk, and `mode: existing` does **not** mean "update silently" — existing files are still asked about below.
- **`on-existing`** (`ask`/`keep`/`update`/`replace`, default `ask`) — how to treat each workflow/config file that
  already exists (see "Existing workflow files" below).
- **`security-dependency-review`**/**`security-codeql`**/**`security-osv`**/**`security-gitleaks`** — each
  `yes`/`no`, default `yes` when not supplied. Individually gate the matching job in `security.yml` (Step 3b);
  `.github/dependabot.yml` itself is always generated regardless of these four flags, since dependency updates and
  vulnerability scanning are two different concerns. Any not supplied via `args` is asked with `AskUserQuestion`
  (default yes) rather than silently assumed, unless this skill is running unattended as part of
  `iru-setup-java-springboot`'s Step 8, in which case the default stands without asking.

Read `springboot-stack.yml` for the cloud, the Java version, `project.language` and `project.buildTool` (see
"Language and build tool" below), `visibility`, the `ci:` block (`ci.tests`,
`ci.pages`), `sonar.mode`
(`cloud`/`self-hosted`/`none` — absent means `cloud`, for backward compatibility with a manifest written before
this field existed) and its `sonar.organization`/`sonar.projectKey`/`sonar.hostUrl`, `frontend`
(`none`/`hilla`), and which technologies are in play. **`sonar.mode` is the source of truth for whether `build.yml`
gets a Sonar step at all** — this skill no longer asks reactively whether to add or omit it. **`frontend: hilla`
is the source of truth for whether `build.yml` gets a Node/Vitest step, `security.yml`'s CodeQL matrix gets a
`javascript-typescript` language, and `.github/dependabot.yml` gets an `npm` ecosystem entry** — see Step 1 and
Step 3b. Then survey what's actually on disk — the workflows must match reality, not the manifest's intent:

- The reactor's modules, and the `boot` module's directory (that's what produces the image).
- Whether `coverage/` exists and where `report-aggregate` writes (`coverage/target/site/jacoco-aggregate/jacoco.xml`)
  — this is the path Sonar reads, when `sonar.mode` isn't `none`.
- When `sonar.mode` isn't `none`: whether `sonar-maven-plugin` and the `sonar.*` properties actually exist in the
  root pom (written by `iru-setup-java-springboot-pom` per the same `sonar.mode` field). If they don't — a
  manifest/pom drift, e.g. the pom was generated by an older `iru-setup-java-springboot-pom` that predates the
  `sonar:` block — say so in Step 6's report and offer to
  re-run `iru-setup-java-springboot-pom` rather than silently generating a `mvn sonar:sonar` step that's certain
  to fail. When `sonar.mode: none`, skip this check entirely: no Sonar step is generated, so there's nothing on
  the pom side to verify.
- Whether `docs/antora.yml` and `docs/antora-playbook.yml` exist, and which extensions `docs/package.json` lists
  (the install step must match — including `asciidoctor-kroki` if the docs use Kroki).
- Where the API documentation generators write (`*/target/generated-docs/...`), from the module poms.
- Whether `apis/graphql-server/` holds an SDL and an `apis/graphql-server/docs/package.json`, and whether that
  toolchain includes `graphql-voyager` as well as `spectaql` — the docs job's GraphQL step must match what's
  actually pinned there, and its `package-lock.json` must be committed for `npm ci`.
- Whether `apis/messaging/` holds an AsyncAPI specification and an `apis/messaging/docs/package.json`
  (set up by `iru-setup-java-springboot-apis`). If so, the docs job must also produce the AsyncAPI HTML; note
  whether `apis/messaging/docs/package-lock.json` is committed, since that decides `npm ci` versus `npm install`,
  and which module's pom carries the `frontend-maven-plugin` executions.
- When `frontend: hilla`: which module actually carries `src/main/frontend/` and the `vaadin-maven-plugin`
  execution (written by `iru-setup-java-springboot-hilla` — normally `boot`), and whether that module's
  `package-lock.json` is committed (it should be — `vaadin:prepare-frontend` generates it alongside `package.json`
  at the module root, next to `pom.xml`, not inside `src/main/frontend/`; confirm the real path rather than
  assuming `src/main/frontend/package-lock.json`, which is not where it lives). If the module or its
  `vitest.config.ts` doesn't exist yet, still generate the Node/Vitest step in `build.yml` but mark it as
  requiring `/iru-setup-java-springboot-hilla` first, and say so in the report.
- Whether `infra/envs/<env>/` directories exist and which environments they define. If `infra/` doesn't exist,
  still generate `deploy.yml`/`undeploy.yml` but mark the OpenTofu steps as requiring
  `/iru-setup-java-springboot-platform` first, and say so in the report.
- **Rolling deployments.** Resolve `deploy.rolling` (`enabled`, `strategy`, `advance`, `steps`, `bakeMinutes`) as
  described above and validate it like the orchestrator does (`steps` ascending, ending in 100, `blue-green` is
  `[100]`; `rolling` with `manual`/`hybrid` is contradictory — ask which to change). Rolling is **forced off** when
  `ci.enabled: false` (no workflows exist to drive it — say so). When on, survey `infra/envs/*` for the OpenTofu
  outputs `rollout_candidate_url`, `rollout_gate`, `rollout_alarm_names`, `rollout_target_groups`, `rollout_name`
  (grep the `outputs.tf` files): which runtime they imply (`cloudwatch` ⇒ ECS, `cloud-monitoring` ⇒ Cloud Run,
  `prometheus` ⇒ Kubernetes) decides the rollout-functions block and the auth/kubeconfig steps in Step 2b. If they
  are missing, still generate the workflows but mark the rollout steps as requiring
  `/iru-setup-java-springboot-platform` (with `rolling: yes`) first and say so in the report. For `strategy:
  rolling` only Step 2's single-job template (plus the settle step) is generated.
- The branch model: run `git branch -a`, don't assume gitflow. Confirm the integration and main branch names.
- Whether GitHub Environments already exist (`gh api repos/{owner}/{repo}/environments`) — `deploy.yml` and
  `undeploy.yml` depend on them for approval gates.
- Whether `compose.yaml` exists and how heavy the container stack is: integration tests on a standard GitHub
  runner (4 vCPU / 16 GB) can't host Elasticsearch + Couchbase + Kafka + several databases simultaneously. If the
  stack looks too large, say so now and offer either a larger runner or splitting integration tests into a matrix
  by module. Skip this check when `ci.tests: unit` — no container stack runs in CI then.

### Language and build tool

`project.language` (`java`/`kotlin`, absent ⇒ `java`) and `project.buildTool` (`maven`/`gradle`, absent ⇒ `maven`;
`gradle` only ever appears with `kotlin`) pick one of three variants. Confirm them against the disk the same way as
everything else: `pom.xml` at the root ⇒ Maven, `settings.gradle.kts` + `gradlew` ⇒ Gradle, `src/main/kotlin` in the
modules ⇒ Kotlin. If the manifest and the disk disagree, the disk wins for this run — say so in the report.

| Variant | Templates | What changes |
|---|---|---|
| `java` + `maven` | Steps 1–3b exactly as written | nothing — the Java path is the base template |
| `kotlin` + `maven` | Steps 1–3b as written, plus "Kotlin + Maven" in Step 1 | static analysis is detekt + ktlint instead of Checkstyle/PMD/SpotBugs, code docs are Dokka instead of Javadoc; every `mvn` build/test/Sonar/image command is unchanged |
| `kotlin` + `gradle` | the Gradle templates in Steps 1, 2 and 3b | every job uses `gradle/actions/setup-gradle` and `./gradlew`; `security.yml` gains `dependency-submission`; Dependabot uses the `gradle` ecosystem |

For Gradle, also survey: that `gradlew`, `gradle/wrapper/gradle-wrapper.properties` and
`gradle/wrapper/gradle-wrapper.jar` are committed (CI uses the wrapper only — never a runner-installed `gradle`);
that the `integrationTest` task and the `:coverage` project's `testCodeCoverageReport` task exist
(`./gradlew tasks --all`, both written by `iru-setup-java-springboot-pom`'s `build-logic/` convention plugins); where
the aggregated JaCoCo XML lands (`coverage/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml`
by default); whether the root project aggregates Dokka (`dokka(project(":…"))` dependencies ⇒ one site at
`build/dokka/html`) or only each module runs it (⇒ one `build/dokka/html` per module); that `gradle.properties`
carries a `version=` line (the deploy workflow's image tag reads it); and which tasks generate the API documentation
(`./gradlew -q :<project>:tasks --group documentation` for each project applying a codegen plugin — the root-level
`./gradlew tasks --group documentation` doesn't list subproject tasks like `generateRestServerV1Docs` — the OpenAPI
html2 and protoc-gen-doc equivalents
`iru-setup-java-springboot-apis` registers), and that they write under `<module>/build/generated-docs/`.

### Resolving the CI choices

The manifest is the source of truth, `args` overrides it, and only what is still unresolved is asked. Being
invoked at all means CI is wanted, so `ci` itself is never asked here (a manifest with `ci.enabled: false` is
contradictory with running this skill — say so, and ask whether to proceed and flip it to `true`, or stop).

1. **`visibility`** — `args`, else the manifest's `visibility`, else `gh repo view --json visibility -q
   .visibility` (lowercased; tell the user the detected value), else ask with `AskUserQuestion` — **Public** /
   **Private** — recommending Public when the manifest's `openSource: true`, otherwise Private. `internal`
   behaves like `private` for every decision below. If `openSource: true` but visibility is `private`/`internal`,
   warn that this is contradictory (a private repo forfeits the free-for-public-repos pricing) and ask which is
   right.
2. **`sonar`** — `args`, else the manifest's `sonar.mode`. Only when neither has it (a manifest written before the
   `sonar:` block existed), ask "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to
   it?", recommending `cloud` only when the repository is public **and** open source, otherwise `none` (with
   `self-hosted` as the alternative) — SonarCloud is free for public/open-source projects; a private project needs
   a paid plan. If `sonar.mode: cloud` and visibility is `private`/`internal`, point that out in the report.
3. **`ci-tests`** — `args`, else the manifest's `ci.tests`, else ask "Which tests should CI run?":
   - **Unit tests only** (`unit`) — Surefire `*Test` only; Failsafe `*IT` integration tests and the
     Testcontainers/docker compose stack stay local. Cheaper: fewer minutes, faster feedback. Recommended for
     private/internal repositories.
   - **Unit + integration** (`full`) — Surefire plus Failsafe integration tests against the Testcontainers stack
     (databases, Kafka + Schema Registry, the API mock, …). Complete verification, but uses considerably more
     minutes. Recommended for public repositories.
   For a private/internal repository, state the cost facts per GitHub's current pricing (tell the user to
   double-check their plan): standard GitHub-hosted runners are free for public repositories; private/internal
   ones consume the account's included minutes — 2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on
   Enterprise Cloud — then are billed per minute (Linux 2-core $0.006/min). Every workflow here runs on
   `ubuntu-latest`, so the macOS/Windows multipliers don't apply.
4. **`pages`** — `args`, else the manifest's `ci.pages`, else: public ⇒ `yes` without asking (say so in the
   report); private/internal ⇒ ask with **No (Recommended)** — "CI still builds the Antora site, Maven site, and
   API docs and uploads them as a workflow artifact; nothing is published" — versus **Yes** — "GitHub Pages for a
   private/internal repository requires GitHub Pro, Team, or Enterprise Cloud (not GitHub Free), and the site
   will be **publicly accessible on the internet** unless the organization is on Enterprise Cloud and publishes it
   with private access control".

When any of these was resolved by asking (or by `gh`) rather than read from the manifest, offer to record it in
`springboot-stack.yml` (`visibility:`, `ci: { enabled: true, tests: …, pages: … }`, `sonar.mode`) so the next
run and `iru-update-java-springboot-documentation` see the same choice; an `args` override is applied for this run
only and not written back unless the user agrees.

### Existing workflow files

Say up front whether this run is **creating the workflows from scratch** (none of `build.yml`, `deploy.yml`,
`promote.yml`, `undeploy.yml`, `security.yml`, `.github/dependabot.yml` exist) or **updating existing ones** (list
which exist), and **whether rolling deployments are on or off** (strategy, advance, steps, bake minutes when on) with
the state of the two files it changes — `deploy.yml`: *absent*, *single-job* (today's template), or *staged*
(contains `deploy-green`); `promote.yml`: *absent*, *present*, or *partial* (exists but does not match the current
`steps`/`advance`/runtime). The usual Update / Keep as is / Replace / Stop prompt applies to `promote.yml` like any
other file, with these rolling-specific warnings: switching `deploy.yml` between single-job and staged is a
**behavioural change** (replace/update it only with an explicit yes — a rollout in flight must not be orphaned, so
check the cloud split via `/iru-springboot-rolling-deployments`'s in-progress check first); a `promote.yml` found
while rolling is off is *stale* (offer to remove it — never delete without confirmation); `promote.yml` carries
the `target` options generated from `steps`, so a changed `steps` list makes an existing one *partial*.
For each one that exists, warn and — unless `on-existing` is `keep`/`update`/`replace`, in which case apply it to
every file without asking (still print the warning) — ask with `AskUserQuestion` (up to 4 files per call, one
question each), stating concretely where the existing file contradicts this run's choices (e.g. "your current
`build.yml` runs `mvn verify` with Failsafe/Testcontainers but you chose `ci-tests: unit`", "it deploys to
`gh-pages` but `pages: no`", "it still runs `sonar:sonar` after `sonar: none`"):

- **Update (Recommended)** — merge: add what's missing and correct the stages this skill owns (action versions,
  gated steps, test scope, Pages vs artifact, Sonar) using the templates below; keep every step/job this skill
  doesn't own (a Slack notification, an extra matrix leg). You review a diff; your customizations survive, but a
  customized part of a stage this skill owns may be rewritten.
- **Keep as is** — leave the file untouched. Nothing you customized changes, but it may stay out of date or
  disagree with this run's choices — Step 6's report lists each mismatch.
- **Replace** — regenerate from the template. Matches this house pattern exactly, but every customization in the
  file is lost (recoverable only from git history).
- **Stop** — end this skill now without changing any file.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 1 — `build.yml`

Structure it as two jobs so a pull request gets fast feedback and Pages publishing only happens on the main branch.
The template below is the `ci.tests: full` / `ci.pages: yes` shape; the `unit` and `pages: no` variants follow it.

Template: `templates/maven/.github/workflows/build.yml` — read it in full before filling placeholders

Adjust to the survey: `npm ci` only works if `docs/package-lock.json` is committed — if it isn't, fall back to the
explicit `npm i` list `iru-setup-antora` uses, **including `asciidoctor-kroki`** if the playbook requires it, or a
Kroki-diagram page will fail the build. The `mvn site` invocation at the reactor root aggregates every module's
reports; verify the aggregate actually lands in `./target/site` for this reactor (a multi-module `site` sometimes
needs `site:stage`) and use whichever path is real.

**`ci.tests: unit`** — CI runs Surefire only, with no Testcontainers/Docker stack:

- Rename the Maven step to `Build and unit tests` and run `mvn -B -ntp clean verify -DskipITs` (keeping
  `-Pproduction -Dvaadin.ci.build=true` when `frontend: hilla`). `-DskipITs` is Failsafe's own skip property, so
  `*IT` classes — the only ones that start the Testcontainers/compose stack written by
  `iru-setup-java-springboot-testcontainers` — never run, while Surefire `*Test` classes and the `coverage/`
  module's `report-aggregate` still do; the JaCoCo aggregate (and therefore Sonar's coverage) then reflects unit
  tests only. Replace the step's comment accordingly (no Failsafe, no Docker needed).
- Drop `**/target/failsafe-reports/**` from the artifact-upload paths.
- Add a one-line comment at the top of the job stating that integration tests run locally only
  (`mvn verify`), so nobody assumes CI exercised them.
- Verify against the survey that the reactor really separates the two (Failsafe bound with `*IT` includes, as
  `iru-setup-java-springboot-pom` configures it). If some test that needs Docker is a Surefire `*Test`, it would
  still run — and fail — in CI; flag that class by name in the report rather than silently passing.

**`ci.tests: full`** — the template as written: `verify` runs Surefire, then Failsafe against the Testcontainers
stack, then the aggregate report.

**`ci.pages: no`** — keep the `docs` job (it still validates that the Antora site, the Maven site, and every API
doc generator build) but:

- Replace the `Deploy to GitHub Pages` step with an artifact upload of the merged tree:

  ```yaml
      - name: Upload documentation
        # ci.pages: no — nothing is published; download the artifact from the run to browse the docs.
        if: success()
        uses: actions/upload-artifact@v7
        with:
          name: documentation
          path: ./doc
          retention-days: 14
  ```

- Drop the job's `permissions: contents: write` (only the `gh-pages` push needed it) — the workflow-level
  `contents: read` is enough — and the `.nojekyll` touch, which only matters to Pages.
- Rename the job to `Build documentation`. Keep its `if:` (main-branch pushes only) so pull requests don't pay
  for a docs build twice; widening it to every run is the user's call.
- Say in the report that no github.io site will exist, so `iru-update-java-springboot-documentation`'s
  `api-docs-url` attribute (which points the Antora API pages at `https://<owner>.github.io/<repo>/api`) would be
  a dead link — flag it for the user.

If the manifest's `sonar.mode` is `none`, drop the entire `Run SonarCloud analysis` step from `build.yml` rather
than leaving it in with a permanently-false condition — dead YAML that can never run is worse than no YAML.

The `Frontend UI tests` and `Upload UI screenshots` steps (both templates) exist only when `frontend: hilla`,
`ci.tests: full`, and `<web-module>/playwright.config.ts` exists — drop both otherwise (with `ci.tests: unit` the UI
tests stay a local/group-validation stage, so no Chromium download is paid on every run).

If the manifest's `frontend` isn't `hilla`, drop the `Set up Node` and `Frontend unit tests and coverage` steps,
the `-Pproduction -Dvaadin.ci.build=true` flags on the Maven build step, and the `<web-module>/coverage/**`
artifact-upload path entirely — same reasoning as the Sonar step above, and there's no web module for any of it
to point at.

If the repository has no `apis/messaging/`, drop the AsyncAPI step, the `-Dasyncapi.docs.skip=true` flag, and the
second `cache-dependency-path` entry entirely rather than leaving dead YAML behind. If it does:

- The step is guarded by `hashFiles(...) != ''` as well, so the workflow survives someone deleting the messaging
  contracts without a workflow edit.
- The same `apis/messaging/docs/package-lock.json` caveat applies: without a committed lock file `npm ci` fails
  outright — commit it, or fall back to `npm install`, and say which in the report.
- The loop over `../*-async-api-v*.yaml` picks up `v2` automatically when it's added, so a new contract version
  needs no workflow change. Keep the filename convention and the loop in step with each other.
- The published layout ends up as `doc/api/messaging/v1/index.html` and
  `doc/api/graphql-server/v1/index.html` (with the Voyager graph at
  `doc/api/graphql-server/v1/voyager/index.html`), next to `doc/api/rest-server/v1/` and
  `doc/api/grpc-server/v1/`. Those are the URLs the Antora API pages link to, so changing them means updating
  `/iru-update-java-springboot-documentation`'s output too.
- Voyager's page loads `voyager.standalone.js` and `voyager.css` by **relative** path, so those two files must be
  copied alongside `index.html`. The merge step's directory copy preserves that automatically; a merge step
  rewritten to copy only `index.html` files would silently produce a blank graph.

### Kotlin + Maven (`language: kotlin`, `buildTool: maven`)

Same two jobs, same `mvn` build/test/Sonar commands and the same `ci.tests`/`ci.pages`/`sonar.mode`/`frontend`
variants — `iru-setup-java-springboot-pom` keeps Surefire `*Test`, Failsafe `*IT`, JaCoCo and the `coverage/`
`report-aggregate` identical for Kotlin. Only the quality and code-documentation tools differ, because the pom wires
detekt/ktlint/Dokka instead of Checkstyle/PMD/SpotBugs/Javadoc:

- In the `build` job, replace the `Static analysis and site report` step with:

  ```yaml
      - name: Static analysis (detekt + ktlint)
        # detekt-maven-plugin and ktlint-maven-plugin replace Checkstyle/PMD/SpotBugs for Kotlin. Both read
        # sources only, so no compile is needed; their XML reports are what the Sonar Kotlin sensor imports
        # (sonar.kotlin.detekt.reportPaths / sonar.kotlin.ktlint.reportPaths in the root pom), so this step must
        # stay before the Sonar step.
        run: mvn -B -ntp detekt:check ktlint:check
  ```

  Use the goals the pom actually binds: if it already binds `detekt:check`/`ktlint:check` to `verify`, the
  `mvn clean verify` step runs them and this step is redundant — drop it and say so. The `detekt`/`ktlint` prefixes
  resolve because the plugins are declared in the root pom; if the survey finds them only in a profile, call them by
  coordinates (`com.github.ozsie:detekt-maven-plugin:check`, `com.github.gantsign.maven:ktlint-maven-plugin:check`).
- Add `**/target/detekt*.xml` and `**/target/ktlint*.xml` (whatever report paths the pom configures) to the
  `Upload test reports` artifact paths, so a failing lint run leaves its findings attached.
- In the `docs` job, keep `mvn -B -ntp site -DskipTests -Djacoco.skip` (Surefire/JaCoCo/JXR reports — the pom no
  longer configures Javadoc/Checkstyle/PMD/SpotBugs reports, so the site simply doesn't have those sections) and add
  the Dokka build as its own step before `Install docs toolchain`:

  ```yaml
      - name: Generate KDoc (Dokka)
        # dokka-maven-plugin runs per module and writes <module>/target/dokka/ — Dokka's Maven plugin has no
        # multi-module aggregation, so each module gets its own site.
        run: mvn -B -ntp dokka:dokka
  ```

  and, in `Merge documentation`, copy each module's Dokka site under `./doc/api/kdoc/<module-path>/` (that's the
  location `iru-update-java-springboot-documentation` links its code reference to):

  ```bash
          while IFS= read -r dir; do
            module="$(dirname "$(dirname "$dir")")"; module="${module#./}"
            mkdir -p "./doc/api/kdoc/$module" && cp -r "$dir"/. "./doc/api/kdoc/$module/"
          done < <(find . -type d -path '*/target/dokka' -not -path './node_modules/*')
  ```

  Skip a module whose `target/dokka` has no `index.html` (an aggregator `pom` module with no sources).
- CodeQL keeps `autobuild` (Step 3b): it builds a Maven reactor with Kotlin sources correctly.

### Gradle (`language: kotlin`, `buildTool: gradle`)

A Gradle build has no `mvn site`, Surefire/Failsafe or `report-aggregate`; the equivalents come from the
`build-logic/` convention plugins `iru-setup-java-springboot-pom` writes (`iru.integration-test` registers
`integrationTest` over `**/*IT*` while `test` excludes it; `iru.coverage` + the `coverage` project's
`jacoco-report-aggregation` produce `testCodeCoverageReport`; `iru.quality` applies detekt, ktlint and Dokka). The
template below is the `ci.tests: full` / `ci.pages: yes` / `sonar.mode` ≠ `none` shape with every optional step
present; apply the same variant rules as the Maven template (drop what the manifest/survey rules out):

Template: `templates/kotlin-gradle/.github/workflows/build.yml` — read it in full before filling placeholders

Gradle-specific variant rules, on top of the Maven template's:

- **`ci.tests: unit`** — drop the `Integration tests` step (the `Build and unit tests` step already excludes
  `integrationTest`), so the aggregate covers unit tests only; add the same one-line "integration tests run locally
  only (`./gradlew integrationTest`)" comment at the top of the job. Verify against the survey that no Docker-needing
  test class escapes the `**/*IT*` pattern into `test`, and flag it by name if one does.
- **`ci.pages: no`**, **`sonar.mode: none`**, **`frontend` ≠ `hilla`**, no `apis/messaging/` or
  `apis/graphql-server/docs/` — exactly as for Maven (drop the `-Pvaadin.*` flags along with the Node steps, and
  the `SONAR_TOKEN` env along with the Sonar step).
- **Sonar wiring** — the `sonar` task only exists when `iru-setup-java-springboot-pom` applied `org.sonarqube` in the
  root `build.gradle.kts` (it does so when `sonar.mode` isn't `none`); check for it the way Step 0 checks the pom,
  including that `sonar.coverage.jacoco.xmlReportPaths` points at the aggregated XML and
  `sonar.kotlin.detekt.reportPaths`/`sonar.kotlin.ktlint.reportPaths` at the reports the quality step writes.
- **No `mvn site`** — Gradle has no site equivalent; the test and coverage HTML reports travel in the `build` job's
  `test-reports` artifact instead of being published. Say so in the report, so nobody looks for `mvn-site/` on the
  published site.
- **Never `gradle`, always `./gradlew`** — the wrapper pins the Gradle version the build was verified with, and
  `setup-gradle` validates its checksum.

## Step 2 — `deploy.yml`

Template: `templates/maven/.github/workflows/deploy.yml` — read it in full before filling placeholders

Points to resolve from the survey rather than copy blindly:

- **Registry authentication.** For ECR, prefer `aws-actions/amazon-ecr-login` (which yields a short-lived token
  from the OIDC session) over `REGISTRY_USERNAME`/`REGISTRY_PASSWORD` secrets; for Artifact Registry, prefer
  `gcloud auth configure-docker`. Use the OIDC-derived credential where the registry supports it and drop the
  username/password secrets entirely — fewer stored secrets is strictly better. Only keep them for a third-party
  registry.
- **`apply` runs the plan file** that was just produced, so what's applied is exactly what was planned. Don't
  replace this with a bare `tofu apply -auto-approve`.
- **The approval gate lives in the GitHub Environment**, not in the YAML. Tell the user to configure required
  reviewers on the `prod` environment — without that, `workflow_dispatch` on `prod` is one click from anyone with
  write access.
- If deploying on release publication is wanted, add `release: types: [published]` to the triggers with the
  environment pinned to `prod`, and note that this removes the manual gate unless the Environment requires
  reviewers.
- Buildpacks need a container daemon; `ubuntu-latest` has one. If the org requires a daemonless build, note Jib as
  the alternative and that it needs different plugin configuration in `boot/pom.xml`.

### Gradle variant (`buildTool: gradle`)

Same workflow, triggers, concurrency, permissions, OIDC and OpenTofu steps; replace the JDK, tag and image steps
with:

Template: `templates/kotlin-gradle/.github/workflows/deploy.yml.jdk-tag-image` — read it in full before filling placeholders

- `:boot:` is the Gradle path of the `boot` project from `settings.gradle.kts` — use the real one from the survey.
- `bootBuildImage` depends on `bootJar` (written to `boot/build/libs/`, not `boot/target/`); nothing else in the
  workflow reads the jar, so no path changes elsewhere.
- With `frontend: hilla`, add `-Pvaadin.productionMode=true -Pvaadin.ci.build=true` and the `Set up Node` step from
  Step 1's Gradle template, or the image ships a development-mode frontend.
- `IMAGE_REGISTRY` (the registry host, e.g. `ghcr.io` or `<account>.dkr.ecr.<region>.amazonaws.com`) is one more
  environment variable than the Maven variant needs — list it in Step 4's table only for the Gradle variant.

## Step 2b — Rolling `deploy.yml` (only when rolling deployments are enabled)

Generated **instead of** the single-job `deploy.yml` of Step 2 when rolling is enabled (`deploy.rolling.enabled: true`
in the manifest, or `rolling: yes` in `args`) **and** `strategy` is `blue-green`, `canary` or `linear`. With rolling
disabled or absent, Step 2's template is used byte-for-byte as written — nothing in this Step applies.

Design (decided by the platform skill, repeated here because the workflows implement it): **the workflow drives the
traffic shifting and the rollout state lives in the cloud** — never in GitHub (no artifacts, variables or run
history are read back). Every run first asks the platform "is a rollout in progress, what is the current split?", so
`deploy.yml`, `promote.yml` and `undeploy.yml` are stateless and safe to re-run. A new version ("green", the
*candidate*) always deploys at **0 % production traffic**, is smoke-tested on its candidate URL, and is then shifted
in steps while the previous version ("blue", the *stable* one) keeps running for rollback.

`strategy: rolling` has no second environment and no traffic split. For it, generate Step 2's single-job template
plus **one extra step after `Tofu apply`** that waits for the runtime's native rolling update to settle and fail the
deploy if it does not — ECS: `aws ecs wait services-stable --cluster "${{ vars.ECS_CLUSTER }}" --services "${{ vars.ECS_SERVICE }}"`
(the deployment circuit breaker the platform skill enables rolls back on failure); Cloud Run: nothing extra (the
revision only takes traffic once healthy); Kubernetes: `kubectl rollout status deployment/<name> --timeout=10m`. It
generates **no `promote.yml`** (there is nothing to promote between) and `advance` is `auto`. Rollback is weaker: re-run
`deploy.yml` with the previous `image_tag`. Say so in the report.

### Contract with the platform skill

The templates below rely on exactly this from `iru-setup-java-springboot-platform` (its "Rolling deployments"
section); if the generated `infra/` disagrees, fix the mismatch in the generated workflows and report it:

| Runtime | The workflow assumes |
|---|---|
| ECS Fargate | `tofu output -json rollout_target_groups` = `{"blue":{"name":…,"arn":…},"green":{…}}`; `rollout_alarm_names` = JSON list; slot services named `<ECS_SERVICE>-blue` / `<ECS_SERVICE>-green` in cluster `ECS_CLUSTER`; per-slot variables `image_tag_blue` / `image_tag_green`; the production listener is the only listener whose default action forwards to **both** target groups (the test listener forwards to green only) and has `ignore_changes` on its weights; the deploy role may also call `elasticloadbalancing:AddTags`, `RemoveTags`, `DescribeTags` on that listener (the workflow stores *which slot is the candidate* in a listener tag `rollout-candidate` — a 50/50 split cannot say which side is stable) |
| Cloud Run | a service `CLOUD_RUN_SERVICE` whose `traffic` is pinned and `ignore_changes`-d, so a new revision gets 0 %; the deploy identity may update traffic and tags on that service; `image_tag` variable |
| EKS / GKE Autopilot | `rollout_name` = an Argo `Rollout` in namespace `K8S_NAMESPACE` whose **first steps are `setWeight: 0` then `pause: {}`** (the new version is deployed at 0 %, and the first `promote` moves it to step 1) for `canary`, or `autoPromotionEnabled: false` for `blueGreen`; `image_tag` variable; for `auto`/`hybrid` an `AnalysisTemplate` referenced from the steps |

### The rollout functions (runtime-specific, embedded in `deploy.yml` and `promote.yml` and `undeploy.yml`)

Both workflows (and `undeploy.yml`) need the same logic, so each writes the same shell library to
`$RUNNER_TEMP/rollout.sh` in a step called `Load rollout functions` and `source`s it. Use the block for the detected
runtime and embed it **verbatim and identically** in every file. Shared contract of every block: `rollout_init`
(read the state from the cloud), `rollout_active` (a rollout is in progress), `rollout_current` (candidate percent),
`rollout_set <pct>`, `rollout_gate_ok`, `rollout_finalise`, `rollout_rollback` (restore 100 % stable, retire the
candidate), `rollout_abort` (restore 100 % stable, leave the candidate running for debugging). The runtime-independent
helpers `next_step` and `gate_wait` are identical everywhere and come first. The workflow passes `STEPS` (the
manifest's steps, space-separated, e.g. `10 50 100`), `BAKE_MINUTES`, `ADVANCE` and `TF_DIR` as `env`.

Template: `templates/rolling/rollout-functions/common.sh` — read it in full before filling placeholders

**AWS — ECS Fargate** (needs `ECS_CLUSTER`, `ECS_SERVICE` environment variables):

Template: `templates/rolling/rollout-functions/ecs.sh` — read it in full before filling placeholders

(`scale_slot_down` scales *the slot it is given* to zero and clears the `rollout-candidate` marker: the old stable slot at
finalise, the candidate at rollback.)

**Google Cloud — Cloud Run** (needs `CLOUD_RUN_SERVICE`; `GCP_REGION`, `GCP_PROJECT_ID`; optional `ERROR_RATE_MAX`,
default `0.02`). State is two revision **tags**: `stable` on the revision serving production and `candidate` on the new one.

Template: `templates/rolling/rollout-functions/cloud-run.sh` — read it in full before filling placeholders

**EKS / GKE Autopilot — Argo Rollouts** (needs `K8S_NAMESPACE`; the `kubectl-argo-rollouts` plugin is installed by a
workflow step, see the templates). Percentages are derived from the `Rollout`'s `currentStepIndex`; the analysis
(`AnalysisTemplate`) runs inside the cluster, so the gate is "the Rollout is not Degraded/aborted".

Template: `templates/rolling/rollout-functions/kubernetes.sh` — read it in full before filling placeholders

### The staged `deploy.yml`

Maven shown; the Gradle variant swaps only the JDK/Gradle/tag/registry/image steps exactly as in Step 2's Gradle
variant (and publishes the same `tag` job output). The AWS ECS runtime is shown; the other runtimes are the swaps
listed under the template. `<…>` are filled from the survey and manifest; `STEPS`/`BAKE_MINUTES`/`ADVANCE`/
`STRATEGY` are written as literals from `deploy.rolling` — they are not secrets.

Template: `templates/rolling/workflows/deploy.yml` — read it in full before filling placeholders

Notes on the template, to apply when generating:

- **`advance: manual`** — omit the `shift` job (and `shift` from `rollback`'s `needs`/`if`) and add a last job
  `announce` (`needs: smoke-candidate`) that writes the candidate URL and "run **promote.yml** with target `next`" to
  `$GITHUB_STEP_SUMMARY`. The run ends with the new version deployed at 0 %.
- **`blue-green`** — `STEPS: "100"`: the shift is a single cutover. With `advance: hybrid` the human confirmation is
  the whole point of the strategy, so on `prod` the cutover must wait for `promote.yml`: add
  `&& inputs.environment != 'prod'` to the `shift` job's `if` and generate the `announce` job (as for `manual`) with
  `if: inputs.environment == 'prod'`; `dev` auto-advances.
- **First deploy** of an environment (no rollout outputs yet) is applied at 100 % and the staged jobs are skipped.
- **Cloud Run** — auth with `google-github-actions/auth@v3` + `google-github-actions/setup-gcloud@v3`; env
  `CLOUD_RUN_SERVICE`, `GCP_REGION`, `GCP_PROJECT_ID`, optional `ERROR_RATE_MAX` from `vars`; the `Plan and apply`
  step passes `-var="image_tag=$TAG"` and detects a first deploy with `has_output rollout_candidate_url`; after
  `apply` it calls `rollout_mark_candidate` (the revision exists only now) followed by `echo "marked=true" >>
  "$GITHUB_OUTPUT"`, then `echo "candidate_url=$(rollout_candidate_url)"` (fall back to `tfout rollout_candidate_url`) and the same
  0 % guard; there is no slot or ECS scaling code.
- **EKS / GKE** — after cloud auth add `aws eks update-kubeconfig --name "${{ vars.EKS_CLUSTER_NAME }}"` /
  `google-github-actions/get-gke-credentials@v3` (`cluster_name: ${{ vars.GKE_CLUSTER_NAME }}`), install the Argo
  Rollouts kubectl plugin (a **pinned** release from `argoproj/argo-rollouts`, checksum-verified against the
  release's `argo-rollouts-checksums.txt`; resolve the current version live), `-var="image_tag=$TAG"`, and in the
  candidate smoke test use `kubectl run smoke --rm -i --restart=Never --image=curlimages/curl -n "$K8S_NAMESPACE" --
  -fsS "$CANDIDATE_URL"` when the URL is cluster-internal (`*.svc.cluster.local`) because the runner cannot resolve it.
  The Rollout deploys paused at its first step (0 %); `rollout_active` is then true until it is promoted to 100 %.
  Detect a first deploy with `has_output rollout_name`, and write `marked=true` to `$GITHUB_OUTPUT` right after the
  `apply` that starts the new Rollout revision.
- **`marked`** must be written to `$GITHUB_OUTPUT` before any later command can fail: outputs written by a step that
  then fails are still published, which is what lets `rollback` act on a failed apply but not on a refused deploy.
- Untrusted inputs never reach a shell as `${{ … }}` text: `image_tag` and `target` go through `env`
  (`IMAGE_TAG_INPUT`, `TAG`, `TARGET`); the only `${{ inputs.environment }}` inside scripts is a `choice` input, whose
  value is limited to the generated option list. The Gradle variant keeps its `meta` step as in Step 2.

## Step 2c — `promote.yml` (only when rolling deployments are enabled and `strategy` is not `rolling`)

The human "this step is safe" signal. A separate `workflow_dispatch` workflow — **not** a GitHub Environment
approval: required reviewers on private repositories need GitHub Enterprise, so they cannot be the mechanism on
every plan, and a job waiting for approval would hold a runner open between steps. `promote.yml` is stateless: it
reads the current split from the cloud, validates, moves one step, and ends.

Template: `templates/rolling/workflows/promote.yml` — read it in full before filling placeholders

- The `target` options are generated from the manifest: `next`, every step as a quoted string, `rollback`, `abort`
  (choice inputs are static, so a changed `steps` list means regenerating this file — that is what
  `/iru-springboot-rolling-deployments` does).
- Use the same cloud auth / kubeconfig / plugin steps as `deploy.yml` for the detected runtime (Cloud Run, EKS/GKE).
  The `env:` block carries the same runtime variables as `deploy.yml`.
- **Do not add `needs`-style approval or Environment reviewers here** — see Step 4's advice on who may dispatch it.

## Step 3 — `undeploy.yml`

This workflow destroys things, so its guards are the feature, not the ceremony.

Template: `templates/common/.github/workflows/undeploy.yml` — read it in full before filling placeholders

**When rolling deployments are enabled** (and `strategy` is not `rolling`), change the template as follows — with
rolling off it stays exactly as above:

- It already shares `concurrency: deploy-${{ inputs.environment }}` with `deploy.yml`; `promote.yml` uses the same
  group, so a teardown never interleaves with a deploy or a promotion.
- Add the workflow-level `env:` block (`TF_DIR`, and the runtime variables `ECS_CLUSTER`/`ECS_SERVICE`, or
  `CLOUD_RUN_SERVICE`/`GCP_REGION`/`GCP_PROJECT_ID`, or `K8S_NAMESPACE`) and, after `Tofu init`, two steps — `Load
  rollout functions` (the same block as `deploy.yml`) and **`Reset the rollout to the stable version`**:
  ```yaml
      - name: Reset the rollout to the stable version
        # Never tear down (or scale to zero) an environment that is mid-rollout without first returning all traffic to
        # the stable version: otherwise the "stop" leaves a half-shifted split behind for the next deploy to trip over.
        run: |
          source "$RUNNER_TEMP/rollout.sh"
          if has_output rollout_target_groups || has_output rollout_name || has_output rollout_gate; then
            rollout_init
            if rollout_active; then rollout_rollback; fi
          fi
  ```
  (the guard makes it a no-op for an environment that was never deployed or has no rollout outputs, and fails the
  teardown if the state cannot be read, rather than skipping the reset).
- For `mode: stop`, scale **both** ECS slot services (`$ECS_SERVICE-blue`, `$ECS_SERVICE-green`) to zero as well as
  passing the `desired_count`/`min_instances` variables the platform skill defines; on Cloud Run and Kubernetes the
  existing variables already cover it.

Additional guards to put in, and to explain in the report:

- **`stop` is the default**, and it's reversible: re-running `deploy.yml` brings the service back with its data
  intact. `destroy` is not reversible.
- **Data-bearing resources should have deletion protection enabled in the OpenTofu configuration**, so `destroy`
  fails on them rather than silently deleting a production database. That means `destroy` on `prod` will error
  until someone deliberately removes the protection — which is correct behaviour, not a bug. Say this explicitly.
- Recommend restricting `prod` in the Environment settings to required reviewers **and** a deployment branch rule,
  so this workflow can't be dispatched from an arbitrary branch (required reviewers on a *private* repository need
  GitHub Enterprise; see "Who may promote" in Step 4).
- Consider omitting `prod` from the `destroy` path entirely for a service holding real data, and say so as a
  recommendation.

## Step 3b — `security.yml` and `.github/dependabot.yml`

The shared security block every workflows skill in this catalog embeds (see `iru-setup-java-github-workflows` for
the library-repository equivalent). Four independent jobs, each gated by the matching `security-*` `args` key from
Step 0 (default on) — omit a job's steps entirely when its flag is `no`, rather than disabling it with a
permanently-false `if:`.

Template: `templates/maven/.github/workflows/security.yml` — read it in full before filling placeholders

Drop whichever `jobs:` entries the matching `security-*` flag turned off — a job that never runs because its own
condition is always false is dead YAML, same reasoning as the Sonar step above. If all four are off, still write
`security.yml` with a comment explaining it currently declares no jobs would be more confusing than simply not
writing the file — in that case, skip `security.yml` entirely and say so in Step 6's report.

**Kotlin + Maven** — the template as written. The `java-kotlin` CodeQL language covers Kotlin, and `autobuild`
compiles a Maven reactor (Kotlin included, since `kotlin-maven-plugin` compiles in-process). Dependency review
works from the committed poms exactly as for Java.

**Gradle** — three changes, because GitHub's dependency graph can't read Gradle build scripts statically and
CodeQL can't extract Kotlin without a real compilation:

1. **`dependency-submission` job** (generated whenever `security-dependency-review` is on — dependency review has
   nothing to compare without it). It resolves the build's dependency graph with Gradle and submits it to GitHub,
   on pushes (the base snapshot) and on pull requests (the head snapshot the review compares against):

   Template: `templates/kotlin-gradle/.github/workflows/security.yml.dependency-submission` — read it in full before filling placeholders

   and make `dependency-review` wait for it and tolerate a snapshot still being processed:

   Template: `templates/kotlin-gradle/.github/workflows/security.yml.dependency-review` — read it in full before filling placeholders

   Also add `push: branches: [ <integration-branch>, <main-branch> ]` (both, not just main) so each base branch a
   pull request targets has a submitted snapshot to compare against.
2. **CodeQL builds manually.** Replace the matrix and the `Autobuild` step with a per-language build mode
   (`build-mode: none` is not an option for Kotlin — CodeQL then skips every Kotlin file with a warning):

   Template: `templates/kotlin-gradle/.github/workflows/security.yml.codeql-build` — read it in full before filling placeholders

   CodeQL supports a bounded Kotlin range (1.8.0 up to 2.4.x at the time of writing — see
   https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/); if `project.kotlinVersion` is
   newer than the range the live page lists, say so in the report — extraction fails until CodeQL catches up.
3. **Dependabot uses the `gradle` ecosystem** — see below.

`.github/dependabot.yml` is **always** generated, independent of the four flags above — dependency-update PRs and
vulnerability scanning are separate concerns, and this catalog's convention keeps Dependabot on unconditionally:

Template: `templates/maven/.github/dependabot.yml` — read it in full before filling placeholders

Omit the `npm` entry entirely when the manifest's `frontend: none` — an ecosystem entry for a toolchain that
doesn't exist yet gives Dependabot nothing to scan and just adds noise to its config. When `frontend: hilla`, set
`directory` to the actual web module (survey Step 0; `iru-setup-java-springboot-hilla` currently always chooses
`boot`, so `/boot` unless the survey finds otherwise) — `package.json`/`package-lock.json` live at that module's
root next to its `pom.xml`, never inside `src/main/frontend/`. If the repository's Antora
docs (`docs/package.json`) or the AsyncAPI/GraphQL documentation toolchains (`apis/messaging/docs/package.json`,
`apis/graphql-server/docs/package.json`) already have their own `package-lock.json` files, list each as a
separate `npm` entry pointed at its own `directory` regardless of `frontend`, since those are genuinely present on
disk — the `frontend` gate is specifically about a Hilla application frontend, not the documentation toolchains
`iru-setup-java-springboot-apis`/`iru-setup-antora` already manage.

For **`buildTool: gradle`**, replace the `maven` entry with a `gradle` one. Dependabot's Gradle support reads
`gradle/libs.versions.toml` and the build scripts, and also bumps the wrapper (`gradle-wrapper.properties`); the
`build-logic/` included build declares plugin dependencies of its own, so list it as a second directory:

Template: `templates/kotlin-gradle/.github/dependabot.yml.gradle-entry` — read it in full before filling placeholders

Drop `"/build-logic"` if the survey finds no `build-logic/build.gradle.kts`. Kotlin + Maven keeps the `maven` entry.

## Step 4 — Required secrets, variables, and environments

Report these as a table; this skill cannot create them.

| Name | Kind | Purpose |
|---|---|---|
| `SONAR_TOKEN` | repository secret | SonarCloud/SonarQube scan — **only listed when `sonar.mode` isn't `none`**; omit this row entirely for `sonar.mode: none` (for `cloud` on a private/internal repository, note that the SonarCloud project needs a paid plan) |
| `AWS_DEPLOY_ROLE_ARN` | environment secret | OIDC role assumed per environment (AWS) |
| `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT` | environment secrets | OIDC identity (Google Cloud) |
| `AWS_REGION` / `GCP_PROJECT_ID`, `GCP_REGION` | environment variables | not secret, but environment-specific |
| `IMAGE_REPOSITORY` | environment variable | the registry path the image is pushed to |
| `IMAGE_REGISTRY` | environment variable | the registry host `docker/login-action` logs in to — **Gradle variant only**, and only while the `REGISTRY_*` secrets below are used |
| `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | environment secrets | registry push credentials — **only for a third-party registry**; omit both when the image goes to ECR or Artifact Registry through the OIDC-derived login (Step 2) |
| `ECS_CLUSTER`, `ECS_SERVICE` | environment variables | **rolling, ECS only** — the cluster and the base service name (slot services are `<ECS_SERVICE>-blue` / `-green`); must match the platform skill's resources |
| `CLOUD_RUN_SERVICE` | environment variable | **rolling, Cloud Run only** — the service whose revisions are tagged `stable`/`candidate`; reuses `GCP_PROJECT_ID`/`GCP_REGION`; optional `ERROR_RATE_MAX` (default `0.02`) tunes the 5xx gate |
| `EKS_CLUSTER_NAME` or `GKE_CLUSTER_NAME`, `K8S_NAMESPACE` | environment variables | **rolling, Kubernetes only** — kubeconfig and the namespace of the Argo `Rollout` (its name comes from the `rollout_name` output) |
| `<PROVIDER>_API_KEY` | environment secret | LLM provider key, if `stack.ai.enabled` — never in the repository |
| third-party provider tokens (`MONGODB_ATLAS_*`, `CONFLUENT_CLOUD_*`, …) | environment secrets | needed by the OpenTofu providers for managed services outside the cloud account |

`frontend: hilla` needs no new secret or variable of its own — `npm ci`/`npx vitest`/`vaadin:build-frontend` all
run unauthenticated against the public npm registry and Maven Central, so this table is unchanged by it.

`GITHUB_TOKEN` needs no setup and is all four `security.yml` jobs (Step 3b) require — none of `dependency-review`,
CodeQL, OSV-Scanner, or gitleaks need a secret of their own in the free/public-repository tier of each action.
The Gradle variant adds no secret either: `gradle/actions/setup-gradle` and `dependency-submission` use
`GITHUB_TOKEN` (the latter with the job-level `contents: write` shown in Step 3b), and with `cache-provider: basic`
there is no Develocity/Gradle account or access key involved. Kotlin itself (detekt, ktlint, Dokka) needs nothing.
Also required, and easy to miss:

- **A GitHub Environment per deployment environment**, with required reviewers on `prod` where the plan allows it
  (public repositories, or GitHub Pro/Team/Enterprise on private ones — for private repositories on the lower plans
  required reviewers are an Enterprise feature) and a **deployment branch rule on `prod`** limiting it to the main
  branch. The rolling templates also refuse a `prod` run from any other branch themselves, but the branch rule is
  the control that holds.
- **Who may promote (rolling deployments only).** `promote.yml` is a `workflow_dispatch` workflow, so anyone with
  **repository write access** can dispatch it — including `target: 100`, `rollback` and `abort` on `prod`. This is
  deliberately **not** gated by Environment required reviewers (unavailable on private repositories below Enterprise,
  and a job waiting for approval would hold a runner open between steps). Treat that as a **security finding to
  state in the report**: the "this step is safe" signal is as trustworthy as your write-access list. Mitigate by
  restricting `prod` with a deployment branch rule, a ruleset/CODEOWNERS on `.github/workflows/`, keeping write
  access small, and — on a private Enterprise repository — adding required reviewers to the `prod` Environment (the
  `promote` job already declares `environment:`, so it then pauses for approval with no template change). The metric
  gate still runs in `promote.yml`, so a human cannot promote past a failing gate.
- **GitHub Pages** configured to serve from the `gh-pages` branch (Settings → Pages), or the docs job publishes
  into a branch nobody reads — only when `ci.pages: yes`. For a private/internal repository that needs GitHub
  Pro, Team, or Enterprise Cloud, and the site is public unless Enterprise Cloud private Pages access control is
  used. Omit this bullet entirely when `ci.pages: no`.
- For a **private/internal repository**, every run of these workflows consumes the account's included Actions
  minutes (see Step 0) — mention it so the user can watch their usage.
- The **OIDC trust policy** on the cloud side must name this repository *and* the environment. If it's scoped only
  to the repository, any branch can deploy to production — flag this as a real finding if the platform skill's
  output shows a wildcard subject.
- **CodeQL** requires "Code scanning" to be enabled for the repository (Settings → Code security) — the workflow
  uploads results there. **Dependency review** requires Dependency graph to be enabled (on by default for public
  repositories). Both are one-time repository settings this skill cannot flip itself; say so in Step 6's report.

## Step 5 — Validate

- Check every workflow's YAML parses: `gh workflow list` after pushing, or locally with `python3 -c "import
  yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/<f>.yml` for each file.
- Confirm every action reference is a current major version, and every secret/variable name used appears in Step
  4's table — a typo'd secret name silently evaluates to empty, which usually surfaces as a confusing
  authentication failure much later.
- Lint every workflow with `actionlint` when it's available (`actionlint`, or `docker run --rm -v "$PWD":/repo -w
  /repo rhysd/actionlint:latest`) — it catches expression, `needs`/`if`, permission and shellcheck errors that a
  plain YAML parse doesn't. Report it as not run, rather than as passing, when neither is available.
- Confirm the Maven commands actually work locally first (`mvn -B -ntp clean verify` — or `mvn -B -ntp clean
  verify -DskipITs` for `ci.tests: unit`, confirming no Docker-dependent test runs in it —, `mvn -B -ntp site
  -DskipTests -Djacoco.skip`, `mvn -B -ntp generate-resources`) — delegate to `iru-gate-runner` for a compact
  result. A workflow whose commands were never run locally is a guess. Kotlin + Maven adds `mvn -B -ntp
  detekt:check ktlint:check` and `mvn -B -ntp dokka:dokka`. Gradle: `./gradlew build -x integrationTest`
  (plus `./gradlew integrationTest` for `ci.tests: full`), `./gradlew :coverage:testCodeCoverageReport` (confirm the
  XML lands where Sonar is configured to read it), `./gradlew detekt ktlintCheck --continue`,
  `./gradlew dokkaGeneratePublicationHtml <api-doc-tasks>`, and `./gradlew --no-daemon --no-build-cache clean
  assemble testClasses` (the CodeQL compile).
- If the GraphQL step was added, run its commands locally too (`npm ci` in `apis/graphql-server/docs`, then
  `spectaql` and `build-voyager.mjs`) and **open both pages in a browser**. SpectaQL's output is server-rendered so
  a wrong SDL path fails loudly, but Voyager renders client-side: a bad introspection payload or a missing
  `voyager.standalone.js` gives a blank page with only a console error, and the workflow reports success.
- If the AsyncAPI step was added, run its commands locally too (`npm ci` in `apis/messaging/docs`, then
  `asyncapi validate` and `asyncapi generate fromTemplate`) and confirm an `index.html` is produced with the AVRO
  field names in it. `asyncapi validate` exits 1 on a malformed document, a `$ref` to a missing `.avsc`, or an
  `.avsc` that isn't valid AVRO — so it is a real gate, not decoration — while an out-of-date `asyncapi:` version
  is only reported as information and exits 0, so a new specification release won't break the pipeline. Note that
  `npm ci` here has failed before on an unpublished transitive dependency of the CLI, so a green run on your
  machine with a warm cache isn't proof CI will install cleanly.
- If the Node/Vitest step was added (`frontend: hilla`), run its commands locally too, **in the same order as the
  workflow**: `mvn -B -ntp clean verify -Pproduction -Dvaadin.ci.build=true` (Gradle: `./gradlew build -x
  integrationTest -Pvaadin.productionMode=true -Pvaadin.ci.build=true`) first, then `npm ci` and
  `npx vitest run --coverage` in the web module. Running the npm/vitest commands before the Maven build fails on
  a module-not-found error from `vite.generated.ts` (it imports compiled helpers under `<web-module>/target/`)
  that has nothing to do with the frontend tests — confirmed while writing `iru-setup-java-springboot-hilla`, and
  the single most likely way this ordering gets silently swapped in a future edit.
- Rolling deployments: lint `deploy.yml` and `promote.yml` with `actionlint` the same way, run `bash -n` on the
  embedded rollout-functions block for the runtime (it is a shell library — `shellcheck` it too when available), and
  read `promote.yml`'s `target` options against the manifest's `steps`. Never trigger `deploy.yml`, `promote.yml` or
  `undeploy.yml` as a test: the first real exercise should be a deploy to `dev` followed by `promote` steps and one
  deliberate `rollback`, watched to completion. Anything that needs a live cloud (the PromQL query endpoint, ALB
  listener discovery, `kubectl argo rollouts` semantics, the tag-based candidate marker) is **unverified** until that
  `dev` rehearsal — report it as such.
- Do **not** trigger `deploy.yml` or `undeploy.yml` as a test. Recommend instead: a dry run of `build.yml` by
  pushing to a throwaway branch with the trigger temporarily widened, and a first `deploy.yml` run against `dev`
  only, watched to completion.
- Check `security.yml` and `.github/dependabot.yml` parse the same way as the other workflows, and confirm every
  `security-*` flag from Step 0 produced exactly the jobs it should — a flag left `no` must mean the job's whole
  entry is absent from the file, not present-but-disabled.

## Step 6 — Report

Summarize: whether this run created the workflows from scratch or updated existing ones, and per file the action
actually taken (**created / updated / replaced / kept as is**) — for every file kept as is, each mismatch with
this run's choices (e.g. still runs integration tests after `ci-tests: unit`, still deploys to Pages after
`pages: no`, still runs a Sonar step after `sonar: none`); the resolved `visibility` and how (args, manifest,
`gh`, or asked), `ci.tests` and `ci.pages` (and whether they were written back to the manifest), plus the
Actions-minutes note for a private/internal repository; the branch names and Java version resolved; the
language/build-tool variant used (`java`+`maven`, `kotlin`+`maven`, or `kotlin`+`gradle`), how it was resolved
(manifest, or the disk overriding it), and — for Gradle — the `setup-gradle` cache provider (`basic`, and that
switching to the default enhanced provider means accepting the Gradle Technologies Terms of Use), the image tag
scheme (`<version>-<short sha>` from `gradle.properties`), whether `dependency-submission` was added, whether Dokka
publishes one aggregated site or one per module under `doc/api/kdoc/`, that no Maven site is published, and whether
`project.kotlinVersion` falls inside CodeQL's supported Kotlin range; whether the
Sonar step was included (and, if so, `sonar.mode` and the resolved organization/project key/host URL) or omitted
because `sonar.mode: none`, and whether the pom's own Sonar wiring matched what Step 0 expected; whether the docs
job's Antora install matches `docs/package.json` (including Kroki); whether the AsyncAPI and GraphQL documentation
steps were included and what they publish under `doc/api/messaging/` and `doc/api/graphql-server/` (and whether
the Voyager graph was part of it); which environments `deploy.yml`/`undeploy.yml` offer and where they came
from; the container-image build approach chosen; which of the four `security.yml` jobs were included versus
omitted (and by which flag), whether the CodeQL matrix includes `javascript-typescript` (and why, per the
manifest's `frontend` field), and whether `.github/dependabot.yml` includes the `npm` ecosystem and at which
`directory` (and why, per the manifest's `frontend` field); when `frontend: hilla`, whether the `Set up Node` and
`Frontend unit tests and coverage` steps were added, the resolved web module and `package-lock.json` path (and
whether it matched or differed from the plan's `src/main/frontend/package-lock.json` assumption — it does differ:
the real path is `<web-module>/package-lock.json`), the `actions/setup-node` major version used (`v7`, confirmed
current against the live GitHub API — no discrepancy found at the time of writing) and whether it matches the
docs job's own `actions/setup-node` version; the full secrets/variables/environments table from Step 4; and any
gap — missing `infra/`, missing Sonar config, missing GitHub Environments, a container stack too large for a
standard runner, Code scanning or Dependency graph not yet enabled for `security.yml`'s jobs to report into, or a
missing `<web-module>/src/main/frontend/` (`frontend: hilla` but `iru-setup-java-springboot-hilla` hasn't run
yet).

Also report the rolling-deployment outcome: **on or off** (and why — `ci: no` forces off), the strategy, advance
mode, `steps`/`bakeMinutes`, the runtime the rollout functions were generated for, the `deploy.yml` shape
(single-job / staged) and the action taken, `promote.yml`'s action (**created / updated / replaced / kept as is /
not generated** — `strategy: rolling` or rolling off — **/ removed**), the new environment variables from Step 4, any
`rollout_*` output the platform skill has not produced yet, the "who may promote" security finding, and which parts
could not be verified without a live cloud.

Warn explicitly:

- **Review all workflows before pushing.** `deploy.yml` and `undeploy.yml` change and destroy real
  infrastructure; a wrong environment name or a missing approval gate has immediate consequences.
- **Configure required reviewers on `prod` before the workflows land on the main branch**, not after — until then,
  anyone with write access can dispatch a production deploy or teardown. With rolling deployments that includes
  `promote.yml`; on plans without Environment reviewers the deployment branch rule and the write-access list are the
  only controls (Step 4, "Who may promote").
- **Verify the OIDC trust policy is scoped to the repository *and* the environment.**
- With `ci.tests: full`, integration tests need Docker on the runner; if the compose stack is too heavy for
  `ubuntu-latest`, the first `build.yml` run will fail on resource exhaustion rather than on a code defect — deal
  with it now if Step 0 flagged it. With `ci.tests: unit`, integration tests run only when someone runs
  `mvn verify` locally — say so plainly.
- Nothing in these files contains a credential, and it must stay that way: every new value goes in as a secret or
  variable reference.
