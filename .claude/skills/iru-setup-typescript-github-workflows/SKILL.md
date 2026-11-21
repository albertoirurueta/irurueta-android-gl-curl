---
name: iru-setup-typescript-github-workflows
description: "/iru-setup-typescript-github-workflows [args] creates or updates build.yml, release.yml, sync.yml, security.yml and dependabot.yml for an npm/TypeScript repository. Key arg flavor (required): library, react, angular, react-native, ionic, nestjs or nextjs. Also asks or takes ci-tests, pages, sonar, distribution, on-existing. Asks Update/Keep/Replace/Stop on existing files."
model: sonnet
---

# Setup TypeScript GitHub Workflows

Scaffold (or update) up to four GitHub Actions workflows plus a Dependabot config for an npm/TypeScript
repository, for whichever `flavor` the repository actually is:

- **`build.yml`** — runs on every push to the integration branch, every push to the stable branch, every pull
  request, and `workflow_dispatch`. Install, lint, format check, typecheck, unit tests with coverage, an
  optional SonarQube/SonarCloud scan (gated by `sonar`, skipped on pull requests from a fork), Playwright e2e
  for the web flavors (`react`, `angular`, `ionic`, only when `ci-tests: full`), a docs build gated by
  `docs-tool` merged with an Antora build, and a GitHub Pages deploy of the merged docs site (only when
  `pages: yes`; with `pages: no` the merged site is uploaded as a workflow artifact instead) — the deploy/upload
  only runs on a push to the stable branch. The `nestjs`/`nextjs` flavors use their own `build.yml` template
  (Step 3) with the same shape plus `prisma generate` (NestJS + Prisma), the production `build`, the e2e suite
  per `ci-tests` (NestJS `test:e2e` with Testcontainers on the runner's Docker; Next.js Playwright), and an
  unpushed image build when a container image is distributed. The `react-native` flavor with `toolchain: metro`
  keeps that same `build` job and adds native jobs (Step 3's metro `build.yml` template): an `android` job on
  Linux, an `ios` job on macOS gated by `visibility`/`ci-tests` (macOS minutes cost ~10× Linux), and — only with
  `ci-tests: full` — an `e2e-android` job running the Maestro flows on an emulator.
- **`release.yml`** — flavor-specific; see Step 3. Always triggered by `release: [published]` (plus
  `workflow_dispatch` for a manual re-run) so a continuous-deployment push to the stable branch never
  accidentally re-publishes or re-signs a release artifact. For `react`/`angular` it deploys the web app to
  Pages only when `pages: yes`; with `pages: no` it builds the bundle and uploads it as an artifact only. For
  `nextjs` with `output: static` it does the same with the static export (`out/`); for `nestjs` and `nextjs`
  with `output: server` it pushes a container image to GHCR when `distribution: internal`, and isn't written
  at all when `distribution: none`.
- **`sync.yml`** — **library/react/angular/nestjs/nextjs flavors only** (the ones that keep a version in
  `package.json` meaningfully bumped between releases; `react-native`/`ionic` app versions are usually driven
  by store submission tooling instead — see Step 1). Triggered when a GitHub Release is published from the stable
  branch. Opens a pull request into the integration branch that merges the released branch back in and bumps
  the development prerelease version in `package.json` (and its lockfile), `README.md`, and the Antora docs,
  via the companion script `.github/scripts/sync_versions.py`. The branch is `sync_<released version>` (release
  `1.0.1` → `sync_1.0.1`); on a merge conflict the PR opens from the release commit without a bump.
- **`security.yml`** — runs on every pull request and push to the integration/stable branches: a
  `dependency-review` job (`actions/dependency-review-action@v5`), a CodeQL job
  (`github/codeql-action@v4`, language `javascript-typescript`, `build-mode: none` — no compile step is
  needed for JS/TS analysis; `nestjs`/`nextjs` also scan the `actions` language — the workflows themselves;
  `react-native` with `toolchain: metro` also scans `actions`, the committed Android Kotlin/Java sources
  (`java-kotlin`, manual Gradle compile) and, on public repositories only, the iOS Swift sources (`swift`, manual
  simulator build on macOS) — see the `security.yml` notes),
  an OSV-Scanner job (the `google/osv-scanner-action` reusable workflow), and a
  `gitleaks` job (`gitleaks/gitleaks-action@v3`) — each individually omittable via `args` (defaulting to on;
  see Step 1).
- **`.github/dependabot.yml`** — grouped weekly dependency updates for the `npm` ecosystem and for
  `github-actions` itself, plus `docker` (base-image bumps) when the repository has a `Dockerfile` (the
  `nestjs`/`nextjs`-server image), and `gradle` (`/android`) + `bundler` (the Gemfile that pins CocoaPods) for
  `react-native` with `toolchain: metro`. Always generated (or updated) regardless of the four `security-*` `args` keys
  above — it's baseline repository hygiene, not an optional CI job.

This skill is designed for npm/TypeScript projects produced by the matching scaffold skill
(`iru-setup-typescript-library`, `iru-setup-react-web`, `iru-setup-angular-web`,
`iru-setup-react-native-app`, `iru-setup-ionic-app`) — the templates in Step 3 assume the fixed npm script
names those skills wire up (`build`, `test`, `coverage`, `lint`, `format`, `typecheck`, plus `docs` for
`docs-tool: typedoc`/`compodoc` and `build-storybook` — the script Storybook's own installer writes — for
`docs-tool: storybook`). The `nestjs`/`nextjs` flavors come from `iru-setup-nestjs-app` /
`iru-setup-nextjs-web` and use that contract's names instead: `lint`, `format:check` (the non-writing check —
`format` writes), `typecheck`, `test` (unit), `coverage` (unit tests with lcov at `coverage/lcov.info` — the
same name `react` uses, even though Nest's own CLI calls it `test:cov`), `test:e2e`, `build`, `start`, plus
`docs` per `docs-tool`. If
`package.json` is missing at the repository root, don't stop automatically: warn the user that this skill's
templates assume an npm/TypeScript project and most of Step 1's survey won't resolve, then use
`AskUserQuestion` to ask whether to stop here or continue anyway. If they choose to continue, proceed through
the remaining steps but treat every `package.json`-derived placeholder in Step 3/4 (script names, Node
version, package manager) as an open gap to ask the user about directly, and call this out prominently again
in Step 8's report.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

**Templates.** The workflow, script and Dependabot templates of Step 3 are files under this skill's `templates/` directory, not inline text. Read only the template files for the `flavor`/`toolchain`/`output`/`distribution` variant resolved in Step 1 (the index at the top of Step 3 says which applies); never read the others.

## Step 1 — Survey the target repository

This skill can be invoked stand-alone (`/iru-setup-typescript-github-workflows`) or as a step inside another
skill, which resolves the parameters below itself and passes them through `args` as `key: value` lines, one
per line, e.g.:

```
flavor: library
mode: new
integration-branch: develop
stable-branch: main
node-version: 24
package-manager: npm
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_my-library
sonar-host-url: https://sonarcloud.io
docs-tool: typedoc
visibility: public
ci-tests: full
pages: yes
on-existing: ask
security-dependency-review: yes
security-codeql: yes
security-osv: yes
security-gitleaks: yes
```

A `nestjs`/`nextjs` run adds its flavor-specific keys (other keys as above; `publish` doesn't apply):

```
flavor: nestjs          # or nextjs
distribution: internal  # none | internal (nestjs, nextjs output: server)
orm: prisma             # nestjs only: none | typeorm | prisma | mongoose | drizzle
output: static          # nextjs only: server | static
e2e: playwright         # nextjs only: playwright | none
docs-tool: compodoc     # nestjs: compodoc | typedoc | none ; nextjs: typedoc | none
```

A `react-native` run adds:

```
flavor: react-native
toolchain: metro        # expo | metro
native-builds: eas      # expo only: eas | local — never sent for metro
distribution: store     # none | internal | store
ci-tests: unit          # metro: full adds the Maestro emulator job; expo: no e2e step
docs-tool: none
```

Parse any such lines from `args` first. For each key found there, use that value directly — skip the
corresponding fact-finding bullet below entirely for it. If `args` is absent or doesn't look like this
format, treat everything as unset and gather every fact below as usual.

- **`mode`** (`new`/`existing`, only ever supplied via `args` — never surveyed or asked): this catalog's shared
  front-door signal, set by `iru-setup-typescript-repository`/`iru-setup-repository`. It is informational
  only: `mode: existing` no longer means "update silently" — Step 2 still announces and asks per existing file
  unless `on-existing` says otherwise. It's echoed in Step 8's report.
- **`on-existing`** (`ask`/`keep`/`update`/`replace`, only ever supplied via `args`; default `ask`): how Step 2
  treats a workflow file that already exists. `ask` asks per file; any other value is applied to every existing
  file without asking (the warning is still printed).
- **`visibility`** (skip if supplied via `args`): if the repository has a GitHub remote, run `gh repo view --json
  visibility -q .visibility` (returns `PUBLIC`/`PRIVATE`/`INTERNAL` — lowercase it) and tell the user the
  detected value. Otherwise (no remote yet, `gh` unavailable, not GitHub) ask with `AskUserQuestion` —
  **Public** / **Private** — recommending **Public** when `open-source: yes`, otherwise **Private**. If
  `open-source: yes` but `visibility` is `private`/`internal`, warn that the two contradict each other (an
  open-source project's repository is normally public, and a private one forfeits free Actions minutes and free
  SonarCloud) and ask which one is right. `internal` is treated exactly like `private` for every cost decision
  below.
- **`ci-tests`** (skip if supplied via `args`): ask with `AskUserQuestion` (header `CI tests`):
  - **Unit tests only** — "Lint, typecheck, and unit tests with coverage (Vitest/Jest/Karma) only — fewer
    minutes, faster feedback. Playwright e2e browser tests stay local." Recommend for private/internal repos.
  - **Unit + e2e** (`full`) — "Also installs Playwright browsers and runs the e2e suite on every build (web
    flavors) — complete verification, considerably more minutes." Recommend for public repos.
  For `library` and `react-native` with `toolchain: expo` (no e2e step in this pipeline) the choice has no
  effect on `build.yml` — say so and default to `unit` without asking unless a Playwright config or another e2e
  runner (Detox, Maestro) is actually configured in the repository. For `react-native` with `toolchain: metro`,
  word **Unit + e2e** as "Also builds a release APK and runs the Maestro flows (`.maestro/`) on an Android
  emulator booted by `reactivecircus/android-emulator-runner` on a KVM-enabled Linux runner — roughly 10–20 extra
  Linux minutes per run, and runs the macOS iOS simulator build on every pull request too (see the cost context
  below)"; **Unit tests only** keeps the Maestro flows local (they need a booted emulator/simulator and Metro,
  which `iru-typescript-test`'s `e2e` selector runs behind `.integration-test.lock`). For `nestjs`, word **Unit + e2e** as "Also runs the `test:e2e`
  suite — with Testcontainers starting the real database in the runner's Docker when an ORM is configured;
  GitHub's `ubuntu-latest` runners ship Docker, so nothing extra is installed, but each run pulls the database
  image". For `nextjs` with `e2e: none` the choice has no effect (default `unit` without asking).
- **`pages`** (skip if supplied via `args`; only relevant when this run would deploy anything to Pages, i.e.
  `docs-tool` isn't `none` or the Antora docs exist, or `flavor` is `react`/`angular`, or `flavor: nextjs`
  with `output: static`): `visibility: public` ⇒
  `pages: yes` without asking (state it in Step 8). `private`/`internal` ⇒ ask with `AskUserQuestion` (header
  `Pages`):
  - **No (Recommended)** — "CI still builds the docs (and, for react/angular, the production web bundle) and
    uploads them as workflow artifacts; nothing is published."
  - **Yes** — "Per GitHub's current pricing, Pages on a private/internal repository requires GitHub Pro, Team,
    or Enterprise Cloud (not GitHub Free), and the site is PUBLIC on the internet unless the organization is on
    Enterprise Cloud and publishes it with private access control."
  For `react`/`angular` (and `nextjs` with `output: static`, whose static export is the app), say explicitly
  that `release.yml` deploys the *web app itself* to Pages, so `pages: no`
  means the release workflow only builds and uploads the bundle — hosting the app elsewhere (Netlify, Vercel,
  Cloudflare Pages, S3/CloudFront, an internal server, …) is left to the user and out of this skill's scope.

- **`flavor`** (skip if supplied via `args`): if not given, detect it with the same rule Tasks 12–17 of this
  catalog's TypeScript group share — inspect `package.json`: `@vaadin/hilla`/`hilla-spring-boot-starter` in a
  parent `pom.xml`, or a `src/main/frontend/` directory → `hilla-frontend` (not one of this skill's seven
  flavors — warn the user this skill doesn't cover Hilla frontends and stop); `@nestjs/core` in dependencies →
  `nestjs`; `next` in dependencies → `nextjs` (checked before the `react` rule — every Next.js app also depends
  on `react`); `expo` or `react-native` in
  dependencies → `react-native`; `@ionic/angular` or `@ionic/react` + `@capacitor/core` → `ionic`;
  `@angular/core` → `angular`; `react` + `vite` (or `@vitejs/plugin-react`) → `react`; otherwise → `library`.
  Ask the user to confirm if the detection is ambiguous (e.g. both `react` and `@capacitor/core` present).
- **`toolchain`** (`react-native` only; skip if supplied via `args`): the catalog-wide rule — `expo` in
  `dependencies` → `expo`, even when `android/`/`ios/` exist (they're `expo prebuild` / Continuous Native
  Generation output); a `react-native` dependency without `expo`, plus `metro.config.js`, committed `android/` +
  `ios/` projects, or `@react-native-community/cli` in `devDependencies` → `metro`. Expo also bundles with Metro
  under the hood — the value names which of the two toolchains the user chose (Expo framework vs. the bare React
  Native Community CLI app whose native projects are committed and built directly), not the bundler. Ask
  (**Expo** recommended / **Metro (bare React Native CLI)**) only if neither signal resolves it. Everything below
  marked "metro" applies only to `toolchain: metro`; `toolchain: expo` keeps every pre-existing react-native
  behaviour unchanged.
- **Package manager** (skip if `package-manager` supplied via `args`): `packageManager` field in
  `package.json`, else lockfile present — `package-lock.json` → `npm`, `pnpm-lock.yaml` → `pnpm`,
  `yarn.lock` → `yarn` (and for `yarn`, check for `.yarnrc.yml` to tell Yarn Berry apart from Yarn Classic —
  Berry uses `yarn install --immutable`, Classic uses `yarn install --frozen-lockfile`). Default `npm` if
  none of these resolve anything.
- **Node version** (skip if `node-version` supplied via `args`): read `engines.node` from `package.json`. If
  absent, ask the user, offering `24` as the default — this catalog's default Node version for new
  repositories (the current Active LTS as of September 2026), and the one the `iru-setup-typescript-*`
  scaffold skills write into a generated `package.json`. Whenever `engines.node` differs from `24`, use what
  `package.json` says (CI must build what the project actually targets) and note the difference in Step 8's
  report.
- **`open-source`** (skip if supplied via `args`): default from `visibility` (public ⇒ likely open source) and
  confirm with the user; ask directly if `visibility` itself was asked rather than detected.
- **`publish`** (library flavor only; skip entirely if supplied via `args`): grep `package.json` for
  `"private": true` (implies `publish: no`) or a `publishConfig` block (implies `publish: yes`). If neither
  is conclusive, default to `publish: no` rather than stopping — tell the user npm publishing isn't wired up
  and ask only if they want it added now, or confirm `publish: no` is correct.
- **`distribution`** (react-native/ionic flavors only; skip entirely if supplied via `args`): ask the user
  directly — `none` (CI builds only, nothing is signed or uploaded), `internal` (a signed build artifact, or
  a Firebase App Distribution / TestFlight-internal push if they opt in — ask a follow-up yes/no, default
  "just the artifact, no extra secrets"), or `store` (Google Play / App Store Connect production track,
  needs the signing/API-key secrets in Step 7's table). For `react-native` with `toolchain: metro` the three
  values mean: `none` — `release.yml` only proves the release builds (an AAB signed with the template's debug
  keystore, uploaded as a workflow artifact, plus an unsigned iOS archive); `internal` — the AAB is signed with
  the upload keystore and sent to Google Play's **internal testing** track, and the iOS build is uploaded to
  **TestFlight**; `store` — same signing, Play **production** track, and TestFlight as the staging point for
  App Store review (submitting the build for review stays a manual App Store Connect step, as in
  `iru-setup-swift-github-workflows`). No Firebase follow-up question for metro — Play's internal track is the
  internal channel.
- **`distribution`** (`nestjs`, and `nextjs` with `output: server`; skip if supplied via `args`): `internal`
  when a root `Dockerfile` exists (the scaffolds write one only for `internal`), else `none`; confirm with the
  user. `internal` ⇒ `release.yml` pushes the image to GHCR (`ghcr.io/<owner>/<image-name>`); `none` ⇒ no
  `release.yml` at all (the GitHub Release/tag itself is the release; deployment is the user's own pipeline).
  `store` doesn't apply to these flavors. Not asked for `nextjs` with `output: static`: there it is always
  `none` (the orchestrator forwards `distribution: none`), and its release is the Pages deploy, gated by `pages`.
- **`output`** (`nextjs` only; skip if supplied via `args`): `next.config.(ts|mjs|js)` sets `output: 'export'`
  → `static`, `output: 'standalone'` (or no `output`) → `server`. For `static`, also check that the config reads
  the GitHub Pages base path from the environment — e.g. `basePath: process.env.PAGES_BASE_PATH ?? ''` (plus
  `images: { unoptimized: true }`, which `next/image` requires under `output: 'export'`); `release.yml` passes
  `actions/configure-pages`'s `base_path` output (`/<repository>` for a project site, empty for a
  `<owner>.github.io` repository or a custom domain) in that variable. If the config hardcodes or omits
  `basePath`, note it as an open gap: a project site would load every asset from the wrong root.
- **`orm`** (`nestjs` only; skip if supplied via `args`): `prisma` when `prisma/schema.prisma` exists or
  `prisma`/`@prisma/client` is a dependency (adds the `prisma generate` step — the generated client is not
  committed, and lint/typecheck/tests import it); `typeorm`/`mongoose`/`drizzle` from `@nestjs/typeorm`/
  `@nestjs/mongoose`/`drizzle-orm`; else `none`. Only `prisma` changes the generated workflow.
- **`e2e`** (`nextjs` only; skip if supplied via `args`): `playwright` when `playwright.config.ts` and a
  `test:e2e` script exist, else `none`.
- **`Dockerfile`** (`nestjs`/`nextjs`): note whether a root `Dockerfile` exists — it gates the image build
  check in `build.yml`, `release.yml`'s GHCR job, and Dependabot's `docker` entry. `distribution: internal`
  without a `Dockerfile` is an open gap (re-run the scaffold skill), never a reason to invent one here.
- **`native-builds`** (react-native flavor with `toolchain: expo` only — never asked or used for `metro`, whose
  native projects are always built locally by Gradle/`xcodebuild`; skip if supplied via `args`): ask the user `eas` (Expo
  Application Services — simplest, but the free tier is capped at 15 builds/platform/month) or `local`
  (self-hosted Gradle on `ubuntu-latest` + `xcodebuild` on `macos-latest`, no EAS account needed but slower
  runners and the repository must manage its own signing material).
- **`framework`** (ionic flavor only; skip if supplied via `args`): detect from `package.json`
  (`@ionic/angular` vs `@ionic/react`), confirm with the user if ambiguous.
- **`docs-tool`** (skip if supplied via `args`): `typedoc.json` present → `typedoc`; `.compodocrc`/`compodoc`
  script → `compodoc`; `.storybook/` directory → `storybook`; none of these found → ask the user, defaulting
  to `typedoc` for `library`, `compodoc` for `angular` and `nestjs`, `typedoc` for `nextjs`, `none` for
  `react-native`/`ionic` (mobile apps rarely publish a public API doc site) unless the survey found a doc tool
  already configured. `storybook` isn't offered for `nestjs` (no UI).
- **`sonar`** (skip entirely if supplied via `args`): check for `sonar-project.properties` at the repository
  root and its `sonar.organization`/`sonar.projectKey`/`sonar.host.url` keys — this house pattern's
  `SonarSource/sonarqube-scan-action@v8` step reads that file automatically with no extra `with:` args, so
  the config lives there, not inline in the workflow. If present, treat that as `sonar: cloud` (when
  `sonar.host.url` is `https://sonarcloud.io` or unset) or `sonar: self-hosted` (any other host). If absent,
  default to `sonar: none` (omit the scan step entirely) rather than generating a step that can't succeed;
  ask the user ("Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?") only if
  they want Sonar wired up now instead — recommend `cloud` only when the repository is public **and**
  `open-source: yes` (SonarCloud/SonarQube Cloud is free for public open-source projects; a private project
  needs a paid plan), otherwise recommend `none` and offer self-hosted SonarQube (Community Build is free, but
  you run the server) as the alternative.
- **Antora docs**: check for `docs/antora.yml`, `docs/antora-playbook.yml`, **and** `docs/package.json` +
  `docs/package-lock.json`. `iru-setup-antora` produces all four (its Step 3 installs Antora, the
  mermaid/mathjax extensions and Pagefind (pinned) into `docs/package.json`, and its Step 11 keeps `docs/package.json` and
  `docs/package-lock.json` committed precisely so CI can `cd docs && npm ci`). If any is missing, the docs
  steps in Step 3 have nothing to build against — `actions/setup-node`'s `cache-dependency-path:
  docs/package-lock.json` fails outright without the lockfile — so tell the user to run the `iru-setup-antora`
  skill first, or confirm they'll do so before merging this workflow. The docs toolchain is always npm-managed
  (that's what `iru-setup-antora` writes) regardless of the root project's `package-manager`.
- **Branch names** (skip the integration branch if `integration-branch` supplied via `args`, likewise
  `stable-branch`): confirm the integration branch (commonly `develop`) and the stable branch (commonly
  `main`) actually match this repository's branching model — run `git branch -a` or ask, don't assume
  gitflow defaults.
- **README/Antora version-snippet wording** (only needed for `sync.yml`'s companion script,
  `sync_versions.py`, and only for the `library`/`react`/`angular`/`nestjs`/`nextjs` flavors that get a `sync.yml` at all):
  grep for the marker text and its surrounding lines — `grep -B2 -A2 -i "current development version\|latest
  release\|latest snapshot" README.md`, and for Antora pages, `grep -rl "\"version\"\|<version>"
  docs/modules/ROOT/pages/*.adoc`. From that context, find the exact row label used for the development row
  (e.g. `Current development version`) and the exact marker text preceding each version mention. Note any
  deviation from the `iru-setup-readme` skill's default convention now so Step 4 can adjust the template. If
  `README.md` doesn't exist yet, tell the user `sync_versions.py`'s README step will need hand-adjustment once
  the file exists.
- **Existing npm scripts**: confirm `package.json`'s `scripts` block actually has `build`, `test`,
  `coverage`, `lint`, `format`, `typecheck`, and — per `docs-tool` — `docs` (`typedoc`/`compodoc`) or
  `build-storybook` (`storybook`; the script Storybook's own installer writes, so no scaffold adds a separate
  `docs` alias for it) — these are the exact script names `build.yml` invokes via `<package-manager-run>
  <name>`. Every scaffold skill this catalog ships wires all of them (`iru-setup-typescript-library`,
  `iru-setup-react-web`, `iru-setup-angular-web` Step 5, `iru-setup-react-native-app`, `iru-setup-ionic-app`
  Step 6), but a hand-started repository may not: note any that are missing or named differently so Step 4
  can adjust the template rather than generate a step that fails immediately with "Missing script".
  For `nestjs`/`nextjs`, check the contract names from the intro instead — `lint`, `format:check`,
  `typecheck`, `coverage`, `build`, `test:e2e` (when e2e runs in CI), and `docs` per `docs-tool`. A Nest CLI
  project that still has only `test:cov` / a Next.js project with only `format` is the same open gap (adjust the
  `run:` line to the real name, and say so in Step 8), not something to rename in `package.json` here.
- **Web build output directory** (`react`/`angular` only): `react` (Vite) writes to `dist/`; `angular`'s
  application builder writes to `dist/<project>/browser/` — read the actual value from `angular.json`
  (`projects.<project>.architect.build.options.outputPath`, plus its `browser` sub-key when `outputPath` is an
  object; the default is `dist/<project>` with the deployable site under its `browser/` subfolder). This
  resolves `<web-dist-dir>` for `release.yml`'s Pages deploy — deploying the bare `dist/` on Angular publishes
  a root with no `index.html`.
- **iOS workspace/scheme** (`react-native` with `native-builds: local` only): `npx expo prebuild` names the
  generated Xcode project after the app (`app.json`'s `expo.name`, with non-alphanumeric characters stripped —
  e.g. `My App` → `ios/MyApp.xcworkspace`, scheme `MyApp`), **not** `App` like Capacitor does. Resolve
  `<ios-workspace>`/`<ios-scheme>` by running `npx expo prebuild --platform ios` once locally (or `--no-install`
  when CocoaPods isn't available) and reading the basename of `ios/*.xcworkspace`; if prebuild can't be run
  here, derive the name from `expo.name` and flag it as unverified in Step 8's report.
- **Native project facts** (`react-native` with `toolchain: metro` only), read from the committed native projects
  rather than assumed:
  - `<ios-workspace>`/`<ios-scheme>`: the React Native Community CLI template names both after `app.json`'s
    `name` — `ios/<AppName>.xcodeproj` is committed, `ios/<AppName>.xcworkspace` appears after the first `pod
    install`, and the shared scheme is `<AppName>`. Read the basename of `ios/*.xcodeproj` (the workspace may not
    exist yet in a fresh clone) and confirm the scheme with `xcodebuild -list -project ios/<AppName>.xcodeproj`
    when Xcode is available.
  - `<android-package-name>`: `applicationId` in `android/app/build.gradle` (Groovy DSL in the template), needed
    for the Play upload when `distribution` isn't `none`.
  - `<rn-java-version>`: `17` — React Native's Gradle plugin declares `jvmToolchain(17)` and the official
    environment guide installs JDK 17; use a different value only if `android/` explicitly configures another
    toolchain. `android/gradle/wrapper/gradle-wrapper.properties` and `android/build.gradle`'s `ndkVersion`/
    `compileSdkVersion`/`buildToolsVersion` need no workflow input — `android-actions/setup-android` accepts the
    SDK licenses so the Android Gradle Plugin downloads any missing NDK/platform/build-tools on first use.
  - `<ruby-version>`: `.ruby-version` at the app root when present (then drop the `ruby-version:` input —
    `ruby/setup-ruby` reads the file itself); otherwise `3.4`, which satisfies the template Gemfile's `ruby
    ">= 2.6.10"` and is the line its `bigdecimal`/`logger`/`benchmark`/`mutex_m`/`nkf` gems exist for. Check that
    `Gemfile.lock` and `ios/Podfile.lock` are committed — `bundler-cache: true` and the Pods cache key both hash
    them; a missing lockfile is an open gap (re-run `bundle install` / `bundle exec pod install` locally and
    commit it), not something to generate here. The template also commits `.bundle/config`
    (`BUNDLE_PATH: vendor/bundle`), which matches the path `ruby/setup-ruby`'s `bundler-cache` installs into, and
    `ios/.xcode.env` (how Xcode's build phases find `node`) — both must stay committed. Every iOS job sets
    `LANG: en_US.UTF-8`: `pod install` fails with `Encoding::CompatibilityError` under a non-UTF-8 locale
    (reproduced locally).
  - **Release signing hooks**: whether `android/app/build.gradle`'s `release` build type still uses
    `signingConfigs.debug` (the template default) or a project-specific `signingConfigs.release`. The metro
    `release.yml` signs through the Android Gradle Plugin's `android.injected.signing.*` properties, which override
    either, so nothing in `build.gradle` has to change — only note it in Step 8.
  - **App directory**: if `package.json`/`android/`/`ios/` live in a subdirectory (the scaffold's `app-directory`
    isn't `.`), every metro job needs `defaults: run: working-directory: <app-directory>` plus prefixed paths in
    `uses:` inputs (`cache-dependency-path`, artifact paths, `hashFiles`) — note it as an adjustment in Step 8.
- **Playwright** (`react`/`angular`/`ionic` only, and only when `ci-tests: full`): check for a
  `playwright.config.ts` and an `e2e`/`e2e:ci` script. If absent, the e2e step in `build.yml` has nothing to
  run — note this as an open gap; the scaffold skills for these flavors are expected to have wired it already.
  With `ci-tests: unit` the e2e step isn't generated, so skip this check. For `nextjs` (`e2e: playwright`)
  check `playwright.config.ts` + `test:e2e` the same way, and that its `webServer` serves what the CI step
  just built when `CI` is set (`next start` for `output: server`; a static file server over `out/` for
  `output: static`, since `next start` refuses an export build). For `nestjs`, check for the `test:e2e`
  script and its config (`vitest.config.e2e.ts` or `test/jest-e2e.json`).

**Ordering and cost context.** Resolve `visibility` before asking `sonar`, `ci-tests`, or `pages`, and ask
those three in the order `sonar` → `ci-tests` → `pages`. This skill never asks whether CI is wanted at all —
invoking it means it is (an orchestrator that resolved `ci: no` skips this skill entirely). When `visibility`
is `private`/`internal`, state once, per GitHub's current pricing (tell the user to double-check their plan):
standard GitHub-hosted runners are free for public repositories, while private/internal ones consume the
account's included minutes (2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud) and
are then billed per minute; **macOS runner minutes cost roughly 10× Linux** (Linux 2-core $0.006/min, macOS
$0.062/min). That matters here for the `react-native` (`toolchain: expo` with `native-builds: local`, and every
`toolchain: metro` project) and `ionic` flavors, whose `release.yml` `ios` job runs on macOS: a single iOS archive often takes 15–30 minutes, which at the ~10× macOS
rate is roughly 150–300 Linux-equivalent minutes each (an estimate — check your plan's billing page), so a few
dozen iOS builds a month can exhaust a 2,000-minute Free budget on their own (EAS builds run on Expo's infrastructure, not on Actions minutes). Dependabot update runs on standard
hosted runners are free.

`react-native` with `toolchain: metro` is the one flavor here that also runs macOS in **`build.yml`** (the `ios`
simulator build — `pod install` plus a full React Native compile, typically 10–20 minutes, i.e. ~100–200
Linux-equivalent minutes per run on a private repository) and, on public repositories only, in `security.yml`'s
`swift` CodeQL leg. So the `ios` job is gated like the Swift and Ionic iOS jobs, from the resolved
`visibility`/`ci-tests` rather than an extra question:

| `visibility` | `ci-tests` | `ios` job runs on |
|---|---|---|
| `public` | any | every pull request and push (macOS is free on public repositories) |
| `private`/`internal` | `full` | every pull request and push — the user opted into complete verification |
| `private`/`internal` | `unit` | pushes to `<stable-branch>` and `workflow_dispatch` only (`if:` on the job), so day-to-day pull requests spend no macOS minutes; the Android job and the JS checks still run everywhere |

State the resulting gate in Step 8, and that a team that never wants macOS CI minutes can delete the `ios` job and
rely on `release.yml`'s archive (or a local `xcodebuild`).

The four `security-*` keys each default to `yes` when not supplied via `args` and are not otherwise
surveyed — this skill always adds/keeps the corresponding `security.yml` job unless the user (or an
orchestrator's `args`) explicitly opts out. `.github/dependabot.yml` has no matching opt-out key — it's
always generated/updated.

## Step 2 — Announce scratch vs update, and decide per existing file

Check whether `.github/workflows/build.yml`, `.github/workflows/release.yml`, `.github/workflows/sync.yml`,
`.github/scripts/sync_versions.py`, `.github/workflows/security.yml`, and `.github/dependabot.yml` already
exist (skip the `sync.yml`/script check entirely for the `react-native`/`ionic` flavors, which never get
one). Treat `sync.yml` and its script as one unit for this check — either both exist or neither should.

Tell the user which of the two this run is doing:

- **None exist**: "Creating the CI/CD workflows **from scratch** — no prior workflow files found." Skip the
  rest of this step and go straight to Step 4.
- **Any exists**: "**Updating existing CI/CD setup** — found: <list of the files above that exist>." Then, for
  each existing file, **warn** and decide what to do with it:
  - If `on-existing` is `keep`/`update`/`replace` (Step 1), apply that to every existing file without asking
    (still print the warning listing them).
  - Otherwise ask with `AskUserQuestion` — batch up to 4 files per call, one question each (header = the file
    name, shortened to ≤ 12 characters, e.g. `build.yml`, `dependabot`). Before asking, compare the file with
    this run's resolved choices and name any concrete contradiction in the question text (e.g. "your current
    `build.yml` runs Playwright e2e but you chose `ci-tests: unit`", "your `build.yml`/`release.yml` deploys to
    Pages but you chose `pages: no`", "your `build.yml` runs a Sonar scan but `sonar: none`"). Options, with
    these consequences in their descriptions:
    - **Update (Recommended)** — "Merge: add what's missing and correct outdated parts (action versions, gated
      steps, stale values) using this skill's template as reference; keep everything this skill doesn't own
      (custom steps/jobs/entries). You review a diff; your customizations survive, but a customized part this
      skill owns may be rewritten."
    - **Keep as is** — "Leave the file untouched. Nothing you customized changes, but it may stay out of date
      or disagree with this run's choices (e.g. still runs a Sonar step after `sonar: none`, still deploys to
      Pages after `pages: no`, still runs e2e after `ci-tests: unit`) — the report will list each mismatch."
    - **Replace** — "Regenerate from this skill's template. Result matches this house pattern exactly, but every
      customization in the file is lost (recoverable only from git history)."
    - **Stop** — "End this skill now without changing any file." If chosen for any file, make no changes at
      all, report which files exist, and end here.
  - `mode: existing` alone does **not** skip this question — it only confirms an update run is expected.

Per-file handling once decided:

- **Keep as is**: don't touch the file; in Step 8 list every point where it disagrees with this run's
  resolved `sonar`/`ci-tests`/`pages`/`publish`/`distribution`/`docs-tool`/`security-*` values.
- **Replace**: write the filled-in template from Step 4 over it, as for a new file.
- **Update**: proceed to Step 4 treating the existing file as the base to edit, not something to overwrite
  wholesale, and show the user the resulting diff (`git diff --no-index <current> <draft>`) before writing:
  - For `build.yml`: preserve any step that isn't one of the pipeline stages this skill owns
    (install/lint/format/typecheck/test/coverage, optional Sonar, optional e2e, `prisma generate`/build/image build
    check for nestjs/nextjs, docs build, Pages deploy or
    docs-artifact upload, and for react-native/metro the `android`/`ios`/`e2e-android` jobs and the `ios` job's
    `if:` gate) — e.g. a Slack notification step or an extra matrix entry stays untouched. Only add
    missing stages or correct outdated ones (wrong action version, wrong script name, a stage that should now
    be gated on/off per `sonar`/`docs-tool`/`ci-tests`/`pages`, etc.), and don't reorder steps this skill
    doesn't own.
  - For `release.yml`: preserve any job/step outside this flavor's owned jobs; only correct the
    publish/build/signing/deploy jobs per the resolved `publish`/`distribution`/`toolchain`/`native-builds`/`output`/`pages` values (the GHCR image job for
    nestjs/nextjs-server, the static-export Pages job for nextjs-static, the `android`/`ios` signing and
    Play/TestFlight upload jobs for react-native/metro). An existing `fastlane/Fastfile` driving the metro release
    is foreign — keep it and report it rather than replacing it with raw `xcodebuild` steps.
  - For `sync.yml`/`sync_versions.py`: preserve any file the script updates beyond
    `package.json`/`README.md`/the Antora docs that a prior customization added, and preserve any extra
    workflow step the same way. Only correct the pipeline stages this skill owns.
  - For `security.yml`: preserve any job this skill doesn't own; only add/remove/update the
    `dependency-review`/CodeQL/OSV-Scanner/gitleaks jobs per the resolved `security-*` `args` (Step 1).
  - For `.github/dependabot.yml`: preserve any `updates:` entry for an ecosystem this skill doesn't manage
    (e.g. `maven` for a sibling backend); only add/update the `npm`, `github-actions`, (when a `Dockerfile` exists) `docker`, and (react-native/metro) `gradle`/`bundler` entries.

Missing files are always created from the template, whatever was decided for the existing ones.

## Step 3 — Reference templates

**Templates.** Every template below is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that generated file's template. Read only the files for the variant resolved in Step 1, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/generic/.github/workflows/build.yml` | `library`, `react`, `angular`, `ionic`, and `react-native` with `toolchain: expo` (the base the metro jobs are appended to) |
| `.github/workflows/build.yml` | `templates/nestjs-nextjs/.github/workflows/build.yml` | `nestjs` and `nextjs` flavors |
| `.github/workflows/build.yml` | `templates/react-native-metro/.github/workflows/build.yml.append` | `react-native` with `toolchain: metro` (jobs appended after the generic template's `build` job) |
| `.github/workflows/release.yml` | `templates/library/.github/workflows/release.yml` | `library` flavor |
| `.github/workflows/release.yml` | `templates/react-angular/.github/workflows/release.yml` | `react` and `angular` flavors |
| `.github/workflows/release.yml` | `templates/nextjs-static/.github/workflows/release.yml` | `nextjs` flavor with `output: static` |
| `.github/workflows/release.yml` | `templates/nestjs-nextjs-server/.github/workflows/release.yml` | `nestjs`, and `nextjs` with `output: server`, with `distribution: internal` |
| `.github/workflows/release.yml` | `templates/react-native-expo-eas/.github/workflows/release.yml` | `react-native` with `toolchain: expo` and `native-builds: eas` |
| `.github/workflows/release.yml` | `templates/react-native-expo-local/.github/workflows/release.yml` | `react-native` with `toolchain: expo` and `native-builds: local` |
| `.github/workflows/release.yml` | `templates/react-native-metro/.github/workflows/release.yml` | `react-native` with `toolchain: metro` |
| `.github/workflows/release.yml` | `templates/ionic/.github/workflows/release.yml` | `ionic` flavor |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | `library`, `react`, `angular`, `nestjs`, `nextjs` flavors only |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | same flavors as `sync.yml` |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | every flavor (jobs individually omittable via the `security-*` args) |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | every flavor (the `docker`/`gradle` additions below stay inline) |

These are genericized examples showing the full pipeline. Copy the structure; resolve every `<placeholder>`
using Step 1's survey before writing the real files in Step 4 (the full resolution table is in Step 4), and
drop whichever blocks this repository's `sonar`/`docs-tool`/`publish`/`distribution` values gate off, per the
inline comments in the templates below. **Versions are looked up at run time, not hardcoded from this file**:
query the npm registry (`npm view <package> version`) for every devDependency version this skill's own
supporting files reference, and the GitHub Releases API (`gh api repos/<owner>/<action>/releases/latest`, or
`.../tags` when an action publishes tags without a matching GitHub Release, as `github/codeql-action` and
`SonarSource/sonarqube-scan-action` do) for every `uses:` action major. If either lookup is unreachable, fall
back to the versions confirmed current as of **September 2026**, listed here:

| Action | Fallback version | Notes |
|---|---|---|
| `actions/checkout` | `v7` | |
| `actions/setup-node` | `v7` | |
| `actions/upload-artifact` | `v7` | |
| `actions/download-artifact` | `v8` | note this trails a different major than `upload-artifact` — check both independently, they don't move in lockstep |
| `actions/upload-pages-artifact` | `v5` | |
| `actions/deploy-pages` | `v5` | |
| `actions/configure-pages` | `v6` | |
| `actions/setup-python` | `v7` | used only by `sync.yml` |
| `actions/setup-java` | `v6` | used only by the react-native/ionic Android jobs (Gradle needs a JDK) |
| `android-actions/setup-android` | `v4` | Android SDK/cmdline-tools setup for the Android release jobs (and every react-native/metro Android job) |
| `gradle/actions/setup-gradle` | `v6` | react-native/metro Android jobs — Gradle wrapper/dependency cache (`v6.4.0` latest as of October 2026), same action `iru-setup-android-github-workflows` uses |
| `reactivecircus/android-emulator-runner` | `v2` | react-native/metro `e2e-android` job only (`ci-tests: full`) — `v2.38.0` latest as of October 2026 |
| `ruby/setup-ruby` | `v1` | react-native/metro iOS jobs — Ruby + `bundler-cache` for the Gemfile that pins CocoaPods (`v1.329.0` latest as of October 2026; the project only publishes the floating `v1` major) |
| `maxim-lobanov/setup-xcode` | `v1` | react-native/metro iOS jobs, same action `iru-setup-swift-github-workflows` uses |
| `apple-actions/upload-testflight-build` | `v5` | react-native/metro `release.yml`, `distribution` ≠ `none` — same action and API-key inputs (`issuer-id`/`api-key-id`/`api-private-key`) as `iru-setup-swift-github-workflows` |
| `pnpm/action-setup` | `v6` | only when `package-manager: pnpm` — `setup-node`'s built-in `cache: pnpm` needs `pnpm` already on `PATH`, which this action provides; without it the cache step fails with "Dependency lock file is not found" even though `pnpm-lock.yaml` exists |
| `actions/dependency-review-action` | `v5` | |
| `github/codeql-action` (`init`/`analyze`) | `v4` | resolve via `.../tags`, not `.../releases/latest` — that endpoint returns the unrelated `codeql-bundle-*` tag |
| `SonarSource/sonarqube-scan-action` | `v8` | resolve via `.../tags` too, for the same reason |
| `google/osv-scanner-action` (reusable workflow) | `v2` | pin the workflow ref, e.g. `google/osv-scanner-action/.github/workflows/osv-scanner-reusable.yml@v2.7.0` |
| `gitleaks/gitleaks-action` | `v3` | |
| `actions/cache` | `v6` | `nextjs` — persists `.next/cache` between builds, as Next.js's CI guide recommends; react-native/metro — caches `ios/Pods` keyed on `ios/Podfile.lock` |
| `docker/setup-buildx-action` | `v4` | `nestjs`/`nextjs` (`output: server`) with a `Dockerfile` — image build check and GHCR release |
| `docker/login-action` | `v4` | GHCR release only |
| `docker/metadata-action` | `v6` | GHCR release only |
| `docker/build-push-action` | `v7` | image build check (`push: false`) and GHCR release |
| `changesets/action` | `v2` | library flavor only |
| `expo/expo-github-action` | `v9` | react-native/`native-builds: eas` only — the repository publishes `v5`…`v9` tags (`git ls-remote --tags`), so the ref needs the `v` prefix like every other action here; a bare `@9` doesn't resolve |
| `r0adkll/upload-google-play` | `v1` | ionic/`distribution: store` Android upload, and react-native/metro with `distribution` `internal` (track `internal`) or `store` (track `production`) |
| `wzieba/Firebase-Distribution-Github-Action` | `v1` | only if the user opted into Firebase App Distribution for `distribution: internal` |

npm's trusted publishing (OIDC, no `NPM_TOKEN` secret, automatic provenance) requires **npm ≥ 11.5.1** —
`actions/setup-node@v7` bundles a current npm already, but if a project pins an older npm via `packageManager`,
flag that as an open gap in Step 8; the `npm publish` step in `release.yml` will otherwise fail with an
auth error instead of using OIDC.

When validating a filled-in workflow with `python3 -c "import yaml; yaml.safe_load(...)"` (or any other
YAML-1.1 tool), expect the top-level `on:` key to round-trip as the boolean `True`, not the string `"on"` —
PyYAML's default resolver still treats bare `on`/`off`/`yes`/`no` as booleans per YAML 1.1, while GitHub's own
workflow parser special-cases `on` as a string. This is a false flag in the *validator*, not a bug in the
generated workflow; don't "fix" it by quoting `on:` in the template (GitHub's parser is stricter about that
than you'd expect for some older Actions runners) — just don't treat that particular diff as an error when
comparing parsed YAML structure.

### `build.yml` template

Template: `templates/generic/.github/workflows/build.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-build-generic.md` — read it in full together with the template.

### `build.yml` template — `nestjs`/`nextjs` flavors

Same triggers, concurrency, docs and Pages tail as the template above; the difference is the middle of the
`build` job (contract script names, `prisma generate`, the production build, the flavor's e2e suite, and the
image build check). Every step marked for one flavor is dropped for the other.

Template: `templates/nestjs-nextjs/.github/workflows/build.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-build-nestjs-nextjs.md` — read it in full together with the template.

### `build.yml` template — `react-native` flavor, `toolchain: metro`

The `build` job is the generic `build.yml` template's `build` job above, **verbatim** — install, lint, format
check, typecheck, `coverage` (Jest with `@react-native/jest-preset`, lcov at `coverage/lcov.info`), the optional
Sonar scan, the docs/Antora build and the Pages/artifact tail, with no Playwright block — plus its `deploy-docs`
job when `pages: yes`. Only the job's display name changes. Append these jobs after it (`toolchain: expo` keeps
the generic template alone — its native projects are generated, not committed, so there is nothing native to
build in CI outside `release.yml`):

Template: `templates/react-native-metro/.github/workflows/build.yml.append` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-build-react-native-metro.md` — read it in full together with the template.

### `release.yml` template — `library` flavor

Template: `templates/library/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-library.md` — read it in full together with the template.

### `release.yml` template — `react`/`angular` flavors

This flavor's release *deploys the web app itself* to GitHub Pages, so `pages` (Step 1) changes its shape:
`pages: yes` → the template below as written; `pages: no` → keep the job but rename it `Build production
bundle`, drop its `pages: write`/`id-token: write` permissions and `environment:` block, keep `Upload build
artifact`, and drop the three Pages steps (`Configure Pages`, `Upload Pages artifact`, `Deploy to GitHub
Pages`). The release then produces a downloadable, versioned bundle and nothing is hosted — see the notes
below the template for what to tell the user.

Template: `templates/react-angular/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-react-angular.md` — read it in full together with the template.

### `release.yml` template — `nextjs` flavor, `output: static`

The static export *is* the app, so this mirrors the react/angular template: `pages: yes` deploys `out/` to
Pages; `pages: no` keeps the job as `Build static export`, drops its `pages: write`/`id-token: write`
permissions, `environment:`, `Configure Pages`, `Upload Pages artifact`, and `Deploy to GitHub Pages`, and the
build step's `PAGES_BASE_PATH` env (no Pages site, so no base path to inject) — leaving a downloadable bundle.

Template: `templates/nextjs-static/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-nextjs-static.md` — read it in full together with the template.

### `release.yml` template — `nestjs`, and `nextjs` with `output: server` (`distribution: internal`)

Builds the repository's multi-stage `Dockerfile` (NestJS: compiled `dist/` on `node:<lts>-slim` or distroless,
non-root; Next.js: the `output: 'standalone'` server) and pushes it to GitHub Container Registry. With
`distribution: none` don't write `release.yml` at all — an `on: release` workflow with no jobs is invalid, the
GitHub Release/tag is itself the release, and Step 8 reports the file as "not generated (distribution: none)".

Template: `templates/nestjs-nextjs-server/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-nestjs-nextjs-server.md` — read it in full together with the template.

### `release.yml` template — `react-native` flavor, `toolchain: expo`, `native-builds: eas`

Template: `templates/react-native-expo-eas/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to both `toolchain: expo` variants: `reference/notes-release-react-native-expo.md` — read it in full together with the template.

### `release.yml` template — `react-native` flavor, `toolchain: expo`, `native-builds: local`

Template: `templates/react-native-expo-local/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to both `toolchain: expo` variants: `reference/notes-release-react-native-expo.md` — read it in full together with the template.

### `release.yml` template — `react-native` flavor, `toolchain: metro`

Triggered like every sibling release workflow — a published GitHub Release (which creates the `v<version>` tag
`iru-release` cuts) plus `workflow_dispatch` — and built from the committed `android/`/`ios/` projects directly
(no prebuild step: on metro they are source, not generated output). Version numbers come from the files
`iru-react-native-bump-version` already bumped on the release branch (`versionName`/`versionCode` in
`android/app/build.gradle`, `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` in the `.pbxproj`); this workflow never
edits them. The signing/upload model reuses the Android and Swift workflow skills': an upload keystore decoded
from secrets plus `r0adkll/upload-google-play` (as `iru-setup-android-github-workflows`' workflow-level upload),
and `xcodebuild` cloud-managed signing with an App Store Connect API key plus `apple-actions/upload-testflight-build`
(as `iru-setup-swift-github-workflows`' `signing: api-key` path).

Template: `templates/react-native-metro/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-react-native-metro.md` — read it in full together with the template.

### `release.yml` template — `ionic` flavor

Template: `templates/ionic/.github/workflows/release.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-release-ionic.md` — read it in full together with the template.

### `sync.yml` template (library/react/angular/nestjs/nextjs flavors only)

Runs once a GitHub Release is published, provided it was published from the stable branch. Opens a pull
request that merges the released branch back into the integration branch and bumps the development
prerelease version via the companion `sync_versions.py` script (below). The branch is named after the released
version (`sync_<released version>`); a conflicting merge falls back to a PR from the release commit with no bump
(see `reference/notes-sync.md`). Skip this whole workflow — don't
generate it — for the `react-native`/`ionic` flavors: their app version is normally driven by store submission
tooling (EAS `autoIncrement`, or the build number bumped alongside a store listing — for `toolchain: metro`,
`iru-react-native-bump-version` run by `iru-release` on the release branch), not by a `package.json`
prerelease convention; note this omission explicitly in Step 8's report so the user isn't left wondering why
no `sync.yml` was written for those flavors.

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-sync.md` — read it in full together with the template.

### `sync_versions.py` template (library/react/angular/nestjs/nextjs flavors only)

Companion script for `sync.yml`, at `.github/scripts/sync_versions.py`. Reads `RELEASE_VERSION`/
`NEXT_VERSION` from the environment and rewrites `README.md` and any Antora page carrying the same version
mentions — `package.json`/its lockfile are already bumped by the previous `sync.yml` step (see the note
above), so this script never touches them:

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-sync-versions.md` — read it in full together with the template.

### `security.yml` template

Runs on every pull request and on every push to the integration/stable branches, plus a weekly schedule so
CodeQL and OSV-Scanner findings don't go stale between pushes. Each job below is individually omittable via
the matching `security-*` `args` key from Step 1 (default `yes`); drop that whole job's YAML — not just its
`if:` — when the resolved value is `no`:

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

Notes specific to this template: `reference/notes-security.md` — read it in full together with the template.

### `.github/dependabot.yml` template

Always generated (or updated) regardless of the four `security-*` flags above — grouped weekly updates for
the `npm` ecosystem (the repository's own dependencies) and for the workflows this skill itself just wrote
(`github-actions`):

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

For `nestjs`/`nextjs` with a root `Dockerfile` (Step 1), add a third entry so the `node:<lts>-slim`/distroless
base images get digest and patch bumps too:

```yaml
  - package-ecosystem: docker
    directory: /
    schedule:
      interval: weekly
```

For the `ionic` flavor, add a second `npm` entry with `directory: /android` only if that directory has its own
`package.json` (Capacitor's default layout doesn't create one — Gradle manages Android dependencies
separately and isn't an ecosystem Dependabot's `npm` updater covers; a `gradle` ecosystem entry is a
reasonable follow-up but out of this skill's generated scope, note it in Step 8 instead of adding it silently).

For `react-native` with `toolchain: metro`, the committed native projects carry their own dependency manifests,
so add two entries (the `npm` entry still covers every React Native package, including the ones autolinking
pulls into Gradle and CocoaPods):

```yaml
  - package-ecosystem: gradle
    directory: /android
    schedule:
      interval: weekly
    groups:
      gradle-dependencies:
        patterns:
          - "*"

  # The Gemfile pins CocoaPods and its activesupport/xcodeproj constraints
  - package-ecosystem: bundler
    directory: /
    schedule:
      interval: weekly
```

Dependabot has no CocoaPods ecosystem, so `ios/Podfile.lock` is refreshed only when `pod install` runs after an
`npm` bump — say so in Step 8. Expect most `gradle` pull requests to touch only the Gradle wrapper and the
handful of versions in `android/build.gradle`'s `ext {}` block; the Android Gradle Plugin itself is versioned by
React Native's Gradle plugin and moves with `react-native` upgrades, which should go through the React Native
Upgrade Helper rather than an isolated Dependabot bump.

If `.github/dependabot.yml` already exists with entries for other ecosystems (e.g. `maven` for a sibling
backend), preserve them — only add or update the `npm` and `github-actions` entries above (see Step 2).

## Step 4 — Fill the templates

Resolve every placeholder from Step 1's survey before writing the real files. Don't invent a value — ask the
user or note it as an open gap in Step 8's report instead of silently guessing.

| Placeholder | Resolved from |
|---|---|
| `<integration-branch>` / `<stable-branch>` | Step 1 branch survey / `args` |
| `<node-version>` | Step 1 (`engines.node`, default `24`) / `args` |
| `<package-manager>` | Step 1 (lockfile/`packageManager`) / `args` — one of `npm`/`pnpm`/`yarn` |
| `<install-command>` | derived from `<package-manager>`: `npm ci` / `pnpm install --frozen-lockfile` / `yarn install --frozen-lockfile` (`--immutable` for Yarn Berry) |
| `<package-manager-run>` | derived from `<package-manager>`: `npm run` / `pnpm run` / `yarn run` — runs an npm **script** by name |
| `<package-manager-exec>` | derived from `<package-manager>`: `npx` / `pnpm exec` / bare `yarn` (Classic and Berry both run a `node_modules/.bin` binary as `yarn <bin>`) — runs an installed **binary** that has no npm script alias, e.g. `changeset` in `release.yml`'s `version-pr` job |
| `<lockfile-name>` | derived from `<package-manager>`: `package-lock.json` / `pnpm-lock.yaml` / `yarn.lock` |
| `<bump-version-command>` | derived from `<package-manager>` — see the `sync.yml` template's notes |
| `<sonar-host-url>` | Step 1 Sonar survey / `args`; only needed when `sonar: self-hosted` |
| `<web-dist-dir>` | `react`/`angular` only (release.yml's Pages deploy): `dist` for `react` (Vite's default `build.outDir`); `dist/<project>/browser` for `angular`, read from `angular.json`'s build `outputPath` per Step 1 (never the bare `dist`, which holds no `index.html` on Angular) |
| `<ios-workspace>` / `<ios-scheme>` | `react-native` with `native-builds: local` only: the basename of `ios/*.xcworkspace` after `npx expo prebuild --platform ios` (both share the name — `expo prebuild` derives it from `app.json`'s `expo.name` with non-alphanumeric characters stripped), per Step 1's survey. Not used by `ionic`, whose Capacitor project is always `ios/App/App.xcworkspace` + scheme `App` |
| `<ios-workspace>` / `<ios-scheme>` (metro) | `react-native` with `toolchain: metro`: the basename of the committed `ios/*.xcodeproj` (the React Native CLI template names project, workspace and shared scheme after `app.json`'s `name`, e.g. `ios/MyApp.xcworkspace` + scheme `MyApp`), per Step 1 |
| `<android-package-name>` | `android/app/build.gradle`'s `applicationId`, when `flavor: ionic` and `distribution: store`, or `react-native` with `toolchain: metro` and `distribution` ≠ `none` |
| `<rn-java-version>` | react-native/metro: `17` (React Native's Gradle plugin `jvmToolchain(17)`), per Step 1 |
| `<ruby-version>` | react-native/metro: `.ruby-version` when present (then drop the input), else `3.4` |
| `<macos-runner>` | react-native/metro iOS jobs: `macos-26` (the GitHub-hosted GA image, same default as `iru-setup-swift-github-workflows`' `runner`) |
| `<xcode-version>` | react-native/metro iOS jobs: `latest-stable` (`maxim-lobanov/setup-xcode` resolves it against the image), or an exact version when React Native's release notes require one the image's default doesn't meet |
| `<ios-ci-condition>` | react-native/metro `build.yml` `ios` job: keep the template's `if:` only for `private`/`internal` + `ci-tests: unit`; drop it otherwise (Step 1's cost table) |
| `<emulator-api-level>` | react-native/metro `e2e-android` job: `35` |
| `<play-track>` | react-native/metro: `internal` for `distribution: internal`, `production` for `distribution: store` |
| `<team-id>` | react-native/metro with `distribution` ≠ `none`: the Apple Developer Team ID, asked directly (no run-time source) |
| `<image-name>` | `nestjs`/`nextjs` (`output: server`) with `distribution: internal`: `package.json`'s `name` without its `@scope/` prefix, lowercase (GHCR path segment; the scaffolds set it to the repository slug) — the final reference is `ghcr.io/<owner-lowercased>/<image-name>` |

Also apply the `sonar`/`docs-tool`/`publish`/`distribution`/`native-builds`/`flavor`/`ci-tests`/`pages` gating
resolved in Step 1:
drop every block the templates above mark with an "Omit this … if …" comment when the matching value is
`no`/`none`/doesn't apply to this flavor, and drop that same comment itself — it's guidance for filling the
template, not part of the generated workflow file. GitHub Pages publishing (in `build.yml`, and in
`release.yml` for `react`/`angular` and `nextjs` with `output: static`) is gated only by `pages` — never by
`sonar`/`publish`: `pages: yes` keeps
the deploy steps/jobs; `pages: no` swaps them for `actions/upload-artifact` steps and drops the
`pages: write`/`id-token: write` permissions and `github-pages` environment only the deploy needed.
`ci-tests: unit` drops the Playwright e2e block from `build.yml` (and, for `nestjs`, the `test:e2e` step).
For `nestjs`/`nextjs`, use the dedicated `build.yml` template and apply its per-flavor markers: `prisma
generate` only for `nestjs` + `orm: prisma`; `.next/cache` and Playwright only for `nextjs` (Playwright only
with `e2e: playwright` and `ci-tests: full`); the two Docker steps only with `distribution: internal` and a
`Dockerfile`; the `security.yml` CodeQL matrix variant and Dependabot's `docker` entry per Step 3. `release.yml`
for these flavors: `nextjs` + `output: static` → the static-export template; `nestjs` or `nextjs` +
`output: server` → the GHCR template when `distribution: internal`, **no file** when `distribution: none`.
For `react-native`, `toolchain` picks the templates: `expo` → the generic `build.yml` plus the `native-builds`
`eas`/`local` `release.yml`; `metro` → the metro `build.yml` (generic `build` job + `android`/`ios`, plus
`e2e-android` only with `ci-tests: full`), the metro `release.yml` (unsigned/debug-signed proof builds for
`distribution: none`, signed Play + TestFlight uploads otherwise), the metro CodeQL matrix (with the `swift` leg
only for `visibility: public`), and Dependabot's `gradle`/`bundler` entries.

## Step 5 — Handle missing supporting files

Before writing the workflows, make sure the files they depend on exist:

- **`docs/antora.yml` / `docs/antora-playbook.yml` missing**: recommend running the `iru-setup-antora` skill
  now; the Antora build step in `build.yml` will fail without them.
- **`sonar-project.properties` missing, and `sonar` isn't `none`**: ask the user for `sonar.organization` (if
  using SonarCloud), `sonar.projectKey`, and `sonar.host.url` (default `https://sonarcloud.io` unless they run
  self-hosted SonarQube), then create it at the repository root **using the resolved flavor's own scaffold
  template** — the source/test layout and the lcov path differ per flavor, and the scanner rejects a
  `sonar.tests` root that doesn't exist (`library`'s `test/` and `nestjs`'s e2e `test/` are the only separate test roots; every other
  flavor colocates tests) as well as a wrong `lcov` path (Angular and Ionic-Angular write per-project
  `coverage/<project>/lcov.info`). The common head is always:

  ```properties
  sonar.organization=<org>
  sonar.projectKey=<project-key>
  sonar.host.url=<sonar-host-url>
  ```

  and the rest mirrors the scaffold skill's own `sonar-project.properties` section, which is the canonical
  source — copy from there rather than from this table if the two ever disagree:

  | `flavor` | Mirror | Layout keys |
  |---|---|---|
  | `library` | `iru-setup-typescript-library` Step 5 | `sonar.sources=src`, `sonar.tests=test`, `sonar.javascript.lcov.reportPaths=coverage/lcov.info`, `sonar.exclusions=**/dist/**` |
  | `react` | `iru-setup-react-web` Step 8 | `sonar.sources=src`, `sonar.tests=src`, `sonar.test.inclusions=**/*.test.tsx,**/*.test.ts`, `sonar.exclusions=**/e2e/**,**/dist/**`, `sonar.javascript.lcov.reportPaths=coverage/lcov.info` |
  | `angular` | `iru-setup-angular-web` Step 13 | `sonar.sources=src`, `sonar.tests=src`, `sonar.test.inclusions=**/*.spec.ts`, `sonar.javascript.lcov.reportPaths=coverage/<project>/lcov.info` (`<project>` = the `angular.json` project name), `sonar.exclusions=**/dist/**,**/docs/api/**` |
  | `react-native` | `iru-setup-react-native-app` Step 5 | `sonar.sources=.`, `sonar.tests=.`, `sonar.test.inclusions=**/__tests__/**,**/*.test.ts,**/*.test.tsx`, `sonar.exclusions`/`sonar.test.exclusions=android/**,ios/**,node_modules/**,coverage/**,.expo/**,dist/**` (metro: also `vendor/**`, where a local `bundle install --path vendor/bundle` puts gems), `sonar.javascript.lcov.reportPaths=coverage/lcov.info` |
  | `ionic` | `iru-setup-ionic-app` Step 10 | `sonar.sources=src`, `sonar.tests=src`, `sonar.test.inclusions=**/*.spec.ts` (`framework: angular`) or `**/*.test.ts,**/*.test.tsx` (`react`), `sonar.javascript.lcov.reportPaths=coverage/app/lcov.info` (`angular` — the Ionic starter's fixed `app` project key) or `coverage/lcov.info` (`react`), `sonar.exclusions=android/**,ios/**,e2e/**,coverage/**,www/**` (`angular`) / `...,dist/**` (`react`) |
  | `nestjs` | `iru-setup-nestjs-app` | `sonar.sources=src`, `sonar.tests=src,test`, `sonar.test.inclusions=**/*.spec.ts,**/*.e2e-spec.ts`, `sonar.exclusions=**/dist/**,**/docs/api/**` (plus the generated Prisma client directory when it lives under `src/`), `sonar.javascript.lcov.reportPaths=coverage/lcov.info` |
  | `nextjs` | `iru-setup-nextjs-web` | `sonar.sources=src` (or `app` when the project has no `src/` directory), `sonar.tests=src,e2e`, `sonar.test.inclusions=**/*.test.ts,**/*.test.tsx,e2e/**`, `sonar.exclusions=**/.next/**,**/out/**,**/docs/api/**`, `sonar.javascript.lcov.reportPaths=coverage/lcov.info` |

  Skip this entirely when `sonar: none` — there's nothing to add, and the `Run SonarQube/SonarCloud analysis`
  step is omitted from `build.yml` anyway.
- **`.changeset/config.json` missing, `flavor: library`, and `publish: yes`**: recommend the user re-run
  `iru-setup-typescript-library` (or `npx changeset init`) so the `version-pr` job in `release.yml` has
  something to act on. Skip this step entirely if `publish: no` — that job is omitted.
- **`Gemfile.lock` or `ios/Podfile.lock` missing, `flavor: react-native`, `toolchain: metro`**: flag it — the
  iOS jobs' `bundler-cache` and Pods cache key hash them, and an uncommitted `Podfile.lock` lets every CI run
  resolve different pod versions. Recommend running `bundle install` and `cd ios && bundle exec pod install`
  locally and committing both (`iru-setup-react-native-app` does this for a fresh metro scaffold).
- **`ExportOptions.plist` missing under `ios/`, `flavor` is `react-native` (`toolchain: expo`) or `ionic`, and
  `distribution` isn't `none`** (the metro `release.yml` writes its own inline): flag this prominently — the iOS export step in `release.yml` will fail without it. This
  file is project-specific (team id, export method) and this skill doesn't generate it.
- **`next.config` not reading `PAGES_BASE_PATH`, `flavor: nextjs`, `output: static`, `pages: yes`**: flag it —
  the project site's assets would 404 — and show the two lines to add (`basePath: process.env.PAGES_BASE_PATH ??
  ''`, `images: { unoptimized: true }`); the config belongs to `iru-setup-nextjs-web`, so don't rewrite it here
  without the user's go-ahead.
- **`Dockerfile` missing with `distribution: internal` (`nestjs`, `nextjs` + `output: server`)**: recommend
  re-running the scaffold skill (`iru-setup-nestjs-app` / `iru-setup-nextjs-web`) for its multi-stage, non-root
  `Dockerfile` + `.dockerignore`; omit the image steps/job until it exists and report the gap.
- **`.gitignore` missing entries for `dist/`, `coverage/`, `docs/api/`, `docs/build/`, `storybook-static/`**:
  add whichever are absent, so the build output the workflow generates never gets committed by accident (the
  `iru-setup-typescript-gitignore` skill is the canonical source for this list — defer to it if present rather
  than duplicating logic here).

## Step 6 — Write the workflow files

Write (or, per Step 2's per-file decision, carefully update or replace — and never touch a file kept as is) `.github/workflows/build.yml`, `.github/workflows/release.yml`, and
`.github/workflows/security.yml` with the filled-in templates from Step 4, plus `.github/dependabot.yml` and
any supporting files created in Step 5. For `library`/`react`/`angular`/`nestjs`/`nextjs` flavors only, also write
`.github/workflows/sync.yml` together with `.github/scripts/sync_versions.py` — never write one without the
other, and never write either for `react-native`/`ionic` (Step 3). `security.yml` and
`.github/dependabot.yml` are always written (or updated), independent of `sonar`/`publish`/`distribution` —
only their own `security-*` flags (Step 1) gate individual jobs within `security.yml`, and
`.github/dependabot.yml` has no gate at all.

## Step 7 — List required repository secrets and settings

Report the secrets that must exist under the target repository's Settings → Secrets and variables → Actions —
this skill cannot create them itself. List only the rows that apply to what was actually generated (per
`flavor`/`sonar`/`publish`/`distribution`/`native-builds` from Step 1) — an omitted row is one fewer secret
the user needs to go create:

| Secret | Purpose | Only needed when |
|---|---|---|
| `SONAR_TOKEN` | Auth token for the SonarQube/SonarCloud scan | `sonar` is `cloud` or `self-hosted` |
| `EXPO_TOKEN` | Auth token for `expo/expo-github-action`, used by `eas build`/`eas submit` | `flavor: react-native`, `native-builds: eas` |
| `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | Signs the Android release bundle — for metro, the Play App Signing **upload** keystore (`base64 -i upload.keystore`), passed to Gradle as `android.injected.signing.*` properties | `flavor: react-native` (`toolchain: metro`, or `toolchain: expo` with `native-builds: local`) or `flavor: ionic`, and `distribution` isn't `none` |
| `APP_STORE_CONNECT_API_KEY_ID` / `APP_STORE_CONNECT_API_KEY_ISSUER_ID` / `APP_STORE_CONNECT_API_KEY_BASE64` | Drives `xcodebuild`'s automatic cloud-managed signing (`-allowProvisioningUpdates` + the `-authenticationKey*` trio) on both the archive and the `.ipa` export — the key needs the App Manager or Admin role; no certificate/profile secrets are needed unless the team switched to the manually-managed alternative described under the react-native template. For metro it also authenticates `apple-actions/upload-testflight-build` | `flavor: react-native` (`toolchain: metro`, or `toolchain: expo` with `native-builds: local`) or `flavor: ionic`, and `distribution` isn't `none` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Uploads the signed `.aab` straight to a Play Console track — the service account needs release permissions for the app in Play Console → Users and permissions | `flavor: ionic`, `distribution: store`; or `flavor: react-native`, `toolchain: metro`, `distribution` `internal` (internal track) or `store` (production track) |
| `FIREBASE_ANDROID_APP_ID` / `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase App Distribution push | `flavor: ionic` (or `react-native` with `toolchain: expo`), `distribution: internal`, and the user opted into Firebase App Distribution over a plain artifact upload (Step 1) |
| *(none for CI)* | The metro `build.yml` (`android`/`ios`/`e2e-android`) and its CodeQL legs need no secret — the simulator build is unsigned and the e2e APK uses the template's debug keystore | `flavor: react-native`, `toolchain: metro` |
| *(none)* | The `nestjs`/`nextjs` GHCR push authenticates with `GITHUB_TOKEN` (`packages: write`), the static-export Pages deploy with the Pages OIDC token, and Testcontainers needs no credentials — no secret row for these flavors beyond `SONAR_TOKEN` | `flavor: nestjs`/`nextjs` |

`GITHUB_TOKEN` needs no setup — GitHub Actions provides it automatically, used by `sync.yml`'s `gh` calls and
`security.yml`'s `gitleaks`/CodeQL jobs. `sync.yml` also needs the repository setting under Settings → Actions
→ General → "Workflow permissions" set to allow GitHub Actions to create pull requests — the
`permissions: contents: write / pull-requests: write` block in the workflow itself is not sufficient on its
own. When `pages: yes`, `build.yml`'s Pages deploy (and the `react`/`angular`/`nextjs`-static `release.yml`
deploy) needs
Settings → Pages → "Build and deployment" → Source set to **GitHub Actions** — and on a private/internal
repository, a GitHub Pro, Team, or Enterprise Cloud plan (Pages isn't available for private repositories on
GitHub Free), with the published site publicly reachable unless the organization uses Enterprise Cloud's
private Pages access control. With `pages: no`, none of this applies — omit the Pages notes.
The `nestjs`/`nextjs` GHCR image job needs no secret, but: the first push creates a **private** package (even
from a public repository) — make it public, or grant the deploying cluster/host a pull token, under the
package's settings; and if the organization restricts package creation, Settings → Packages (organization) must
allow members/Actions to publish. The Testcontainers e2e step needs Docker on the runner — GitHub-hosted
`ubuntu-latest` has it; a self-hosted runner must provide it.
`release.yml`'s `library` flavor `publish` job needs the trusted-publisher relationship
configured on npmjs.com for this package (Package settings → Trusted Publisher → GitHub Actions), naming this
repository, the `release.yml` workflow file, and the `publish` job/environment exactly — publish will
otherwise fail with an authentication error even though `id-token: write` is set correctly here. Call all of
this out explicitly in Step 8's report, since each is easy to miss and fails on the very first real run
without it.
For `react-native` with `toolchain: metro` and `distribution` ≠ `none`, also call out: the app must already exist
in Play Console with **one AAB uploaded manually** (the Play Developer API can't create an app or its first
release), Play App Signing enrolled so the secret is the upload key, and the service account granted release
permissions; and the app record must exist in App Store Connect for the bundle id in the Xcode project, with
`<team-id>` matching the team that owns it.

## Step 8 — Report and warn

Summarize what happened: whether this run created the workflows from scratch or updated an existing setup
(Step 2's announcement, plus `mode` if supplied), and per file — `build.yml`/`release.yml`/`sync.yml`
(+`sync_versions.py`, when applicable)/`security.yml` and `.github/dependabot.yml` — the action taken:
**created / updated / replaced / kept as is**, or none at all on Step 2's stop path (say whether the choice was
asked or came from `on-existing`). For every file **kept as is**, list each point where it disagrees with this
run's choices (e.g. still runs e2e after `ci-tests: unit`, still deploys to Pages after `pages: no`, still
scans with Sonar after `sonar: none`);
whether `sonar-project.properties`/`.changeset/config.json` were added versus already present; and any open
gaps noted in Steps 1/4/5 (missing Playwright config, missing `ExportOptions.plist`, an `<ios-workspace>`
derived from `expo.name` rather than read from a real `expo prebuild` run, README/Antora wording that didn't
match `sync_versions.py`'s default regexes, non-patch-level releases the bump default doesn't handle, etc.).

State explicitly what was wired versus omitted, and why:

- **`visibility`**: the value and whether it was detected via `gh` or asked; when `private`/`internal`,
  repeat the cost context from Step 1 (included minutes per plan, macOS ~10×) and which generated jobs run on
  macOS (the `ios` release job for `react-native` `expo`/`local`, `react-native` `metro` and `ionic`, plus the
  metro `build.yml` `ios` job and its gate), telling the user to
  double-check their plan's current pricing.
- **`ci-tests`**: `unit` or `full`, and whether that removed the Playwright e2e block (web flavors, `nextjs`
  with `e2e: playwright`) or the Testcontainers `test:e2e` step (`nestjs`), or had no effect
  (`library`/`react-native` with `toolchain: expo`/`nextjs` with `e2e: none`, which have no e2e step here); for
  metro, whether the Maestro `e2e-android` job was added and how it moved the `ios` job's gate.
  Whenever a UI/e2e job was generated (Playwright, or the metro Maestro job), say that it uploads `.ui-screenshots/`
  as the `ui-screenshots` artifact (7 days, `if: always()`) and that the UI gallery captures of React Native
  components/screens stay local.
- **`pages`**: `yes` (deploy to GitHub Pages — defaulted without asking for a public repository, or chosen by
  the user for a private one) or `no` (docs, and for `react`/`angular`/`nextjs`-static the release bundle, uploaded as workflow
  artifacts only). For `react`/`angular`/`nextjs`-static with `pages: no`, state plainly that the app is not hosted anywhere by
  this pipeline and list adding a deploy step for the user's chosen host as a follow-up.

- **`flavor`**: which of the flavor-specific `release.yml` variants was generated.
- **`distribution`/`output`** (`nestjs`/`nextjs` only): GHCR image (`ghcr.io/<owner>/<image-name>`, tags
  `<version>` + `latest`) or "release.yml not generated (distribution: none)"; for `nextjs` `output: static`,
  the static-export deploy (or artifact with `pages: no`) and whether `next.config` reads `PAGES_BASE_PATH`;
  whether `prisma generate` was wired (`orm: prisma`), whether the unpushed image build check and Dependabot's
  `docker` entry were added (both need a `Dockerfile`), and any contract script (`format:check`, `coverage`,
  `test:e2e`) that had to be mapped to a differently named script. List deploying the image as a follow-up.
- **`sonar`**: `cloud`/`self-hosted`/`none` — if not `none`, the Sonar step and `SONAR_TOKEN` row were
  included; if `none`, both were omitted, and note whether that's because the project isn't open source / the
  repository isn't public (SonarCloud needs a paid plan for private projects) or a direct user choice.
- **`publish`** (library only): yes/no — if `yes`, the `publish` job and (if `.changeset/config.json` exists)
  the `version-pr` job were generated; if `no`, both were omitted and `release.yml` was not written for the
  library flavor (no jobs would remain) — say so explicitly rather than listing it among the created files.
- **`toolchain`** (react-native only): `expo` or `metro`, and whether it came from `args` or was detected (name
  the signal — `expo` dependency, `metro.config.js`, committed `android/`/`ios/`). For `metro`: the `ios` job's
  gate from Step 1's cost table (every PR/push vs. stable-branch pushes + manual runs), whether `e2e-android`
  was generated (`ci-tests: full`) and with Maestro or Detox, the resolved `<ios-workspace>`/`<ios-scheme>`,
  `<android-package-name>`, `<rn-java-version>` and `<ruby-version>`, whether `Gemfile.lock`/`ios/Podfile.lock`
  are committed, whether the CodeQL `swift` leg was included (public) or omitted (private/internal — re-add the
  matrix entry to opt in, at macOS rates), and that Dependabot can't update CocoaPods.
- **`distribution`/`native-builds`** (react-native/ionic only): which signing/store jobs were generated versus
  the plain-artifact fallback, and which secret rows were included versus left out of Step 7's table. For a
  signed iOS job, state that it relies on `xcodebuild`'s automatic cloud-managed signing (API key with the App
  Manager/Admin role, `-allowProvisioningUpdates`) and that a team using a manually managed distribution
  certificate needs the keychain-import alternative described under the react-native template instead. For
  metro, name the Play track (`internal`/`production`) and that iOS builds stop at TestFlight (App Review
  submission stays manual), or — for `distribution: none` — that only debug-signed/unsigned proof builds run.
- **`<web-dist-dir>`** (react/angular only): the directory `release.yml` deploys to Pages, or uploads as the
  release artifact when `pages: no` (`dist` or
  `dist/<project>/browser`) and where it was read from.
- **`docs-tool`**: which tool's build step was wired into `build.yml`, and whether its output merges under
  `docs/build/site/api/` or a sibling `storybook/` path.
- **Security block**: which of `security-dependency-review`/`security-codeql`/`security-osv`/
  `security-gitleaks` were included in `security.yml` versus omitted per Step 1's `args`/answers, and confirm
  `.github/dependabot.yml` was written regardless (it has no opt-out).
- **`sync.yml`**: generated for `library`/`react`/`angular`/`nestjs`/`nextjs`; intentionally omitted for `react-native`/`ionic`
  — state this plainly so it doesn't read as an oversight.

Finish with an explicit warning: **the user must review the generated (or updated) workflow files before
relying on them.** Branch names, script names, and the store/signing setup were inferred from this
repository's current state and may need correction — and because this pipeline handles signing keys and
publishes artifacts publicly, a bad assumption here has real consequences. Recommend a dry run (manually
triggering via `workflow_dispatch` where added) before trusting it on a real release. For `sync.yml`
specifically, recommend verifying it against a real (or test) release before relying on it — confirm the
computed next-version and the exact files it touches match expectations, and confirm the "Workflow
permissions" repository setting from Step 7 is enabled. For `release.yml`'s `library` publish job, recommend
confirming the npmjs.com trusted-publisher configuration before the very first release, since there is no
local way to dry-run OIDC trusted publishing.
