---
name: iru-setup-typescript-repository
description: "/iru-setup-typescript-repository [args] bootstraps an npm/TypeScript repository end to end for flavor library, react, nextjs, angular, nestjs, react-native or ionic: collects flavor, identity and the shared pipeline parameters once, then runs the flavor scaffold skill, iru-setup-antora, iru-setup-typescript-gitignore, iru-setup-typescript-github-workflows (skipped when ci is no), iru-setup-changelog and iru-setup-readme via iru-isolated-skill-executor. Announces from-scratch vs update with a component inventory; key: value args pre-resolve answers. Use to bootstrap or catch up a TypeScript repository in one pass."
model: sonnet
---

# Setup TypeScript Repository

Bootstrap a brand-new (or catch up an existing) npm/TypeScript repository in one pass, for whichever of seven
flavors it is, by orchestrating six existing skills: the flavor's own scaffold skill (`iru-setup-typescript-library`,
`iru-setup-react-web`, `iru-setup-nextjs-web`, `iru-setup-angular-web`, `iru-setup-nestjs-app`,
`iru-setup-react-native-app`, or `iru-setup-ionic-app`),
`iru-setup-antora` (Antora documentation site), `iru-setup-typescript-gitignore` (root `.gitignore`),
`iru-setup-typescript-github-workflows` (CI/CD workflows), `iru-setup-changelog` (root `CHANGELOG.md`), and
`iru-setup-readme` (root `README.md`). This skill does not duplicate any of their logic — it collects the shared
parameters once and passes each skill only the `args` keys it actually accepts, so the user isn't asked the same
question repeatedly.

Each of Steps 5–10 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt, e.g.
`Skill({skill: "iru-setup-typescript-library", args: "..."})`) rather than calling `Skill(...)` directly — every
sub-skill re-derives whatever it needs from the filesystem this orchestrator has just written to, so only a short
completion summary needs to flow back, keeping all six sub-skills' full transcripts out of this orchestrator's own
context.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-typescript-repository`) or driven by another orchestrator, so
parse `args` first, as `key: value` lines, one per line, e.g.:

```
flavor: library
mode: new
open-source: yes
publish: yes
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_my-library
sonar-host-url: https://sonarcloud.io
visibility: public
ci: yes
ci-tests: full
pages: yes
accessibility: enforce
integration-branch: develop
stable-branch: main
node-version: 24
package-manager: npm
```

Recognized keys: `flavor` (`library`/`react`/`nextjs`/`angular`/`nestjs`/`react-native`/`ionic`), `mode`
(`new`/`existing`), `open-source` (`yes`/`no`), `publish` (`yes`/`no` — library only), `distribution`
(`none`/`internal`/`store` for react-native/ionic; `none`/`internal` for nestjs/nextjs), `sonar`
(`cloud`/`self-hosted`/`none`) plus, only when set, `sonar-organization`/`sonar-project-key`/`sonar-host-url`,
`toolchain` (`expo`/`metro` — react-native only), `native-builds` (`eas`/`local` — react-native with
`toolchain: expo` only), `framework` (`angular`/`react` — ionic's own UI framework only), the nestjs-only keys `module-system` (`esm`/`cjs`), `orm` (`none`/`typeorm`/`prisma`/`mongoose`/`drizzle`),
`database` (`postgresql`/`mysql`/`mongodb`), `http` (`express`/`fastify`), the nextjs-only keys `router`
(`app`/`pages`), `output` (`server`/`static`), `cache-components` (`yes`/`no`), `styling`
(`tailwind`/`css-modules`), `e2e` (`playwright`/`none`), `integration-branch`, `stable-branch`, `node-version`,
`package-manager`, `visibility` (`public`/`private`/`internal`), `ci` (`yes`/`no`), `ci-tests` (`unit`/`full`),
`pages` (`yes`/`no`), `accessibility` (`enforce`/`report`/`off`/`none` — UI flavors only: `react`/`nextjs`/`angular`/
`react-native`/`ionic`; ignored with a one-line note for `library`/`nestjs`), `on-existing` (`ask`/`keep`/`update`/`replace` — never asked; forwarded to every
file-writing sub-skill only when it arrived here via `args`), and every identity field named in Steps 2–3 below (`name`, `scope`, `app-id`, `description`,
`license`, `developer-name`, `developer-email`, `organization-url`, `directory`, `storybook`). Every key found here
is resolved — skip the matching question in Steps 1–4. Only keys genuinely missing from `args` still need asking.
If `args` is absent or doesn't look like this format, treat everything as unset and ask normally.

## Step 1 — Resolve the flavor and mode

Unless `flavor` was already supplied via `args`, first look for an `iru-explore` report earlier in this
conversation (e.g. `iru-setup-repository` ran it) and read its `- Project type:` line verbatim; when it's one of
the values below, pre-select the matching flavor and confirm it with a single `AskUserQuestion` (pre-selected
flavor first, "Something else" second — which falls through to the rounds below) instead of asking from scratch.
Without such a report, don't run `iru-explore` just for this — ask the rounds.

| `Project type:` (iru-explore) | `flavor` |
|---|---|
| `typescript-library` | `library` |
| `react-web` | `react` |
| `nextjs` | `nextjs` |
| `angular-web` | `angular` |
| `nestjs` | `nestjs` |
| `react-native-app (expo)` / `react-native-app (metro)` | `react-native` (and `toolchain` from the parenthesis) |
| `ionic-app (angular)` / `ionic-app (react)` | `ionic` (and `framework` from the parenthesis) |

Otherwise resolve it in **two short rounds** (`AskUserQuestion` allows at most four options per question, and
seven flavors don't fit in one):

- **Round 1**: *Library (published npm package)* / *Web app (React + Vite, Next.js, or Angular)* / *Backend
  service (NestJS)* / *Mobile app (React Native or Ionic)*. "Backend service" sets `flavor: nestjs` directly — it's
  the only backend flavor, so no second round.
- **Round 2a** (only if "Web app" was picked): *React (Vite single-page app)* / *Next.js (React framework — server
  rendering or static export)* / *Angular (Angular CLI)*. Sets `flavor` to `react`, `nextjs`, or `angular`.
- **Round 2b** (only if "Mobile app" was picked): *React Native (Expo framework, or a bare React Native CLI app
  on Metro)* / *Ionic (Capacitor hybrid app, Angular or React UI)*. Sets `flavor` to `react-native` or `ionic`
  accordingly; React Native's own `toolchain` is resolved once in Step 3.
- **If `ionic` was picked**, ask one further bounded question — Ionic's own UI framework, a separate concern from
  this orchestrator's `flavor` and recorded as the `framework` key: **Angular** or **React**. `iru-setup-ionic-app`
  and (later) `iru-setup-typescript-github-workflows` both need this value.
- **If `react-native` was picked**, resolve its **`toolchain`** here, once, before the setup-mode announcement
  (it decides which components exist): use `args`' `toolchain` if supplied, else the `iru-explore` parenthesis
  (`react-native-app (expo)`/`(metro)`), else detect it on an existing repository with the catalog-wide rule —
  `expo` in `package.json` `dependencies` → `expo` (even when `android/`/`ios/` exist: they're `expo prebuild`
  output); a `react-native` dependency without `expo`, plus `metro.config.js`, committed `android/` + `ios/`, or
  `@react-native-community/cli` in `devDependencies` → `metro` — and tell the user what was detected and why.
  Only when none of those resolve it (a new repository), ask with `AskUserQuestion` (header `Toolchain`):
  - **Expo (Recommended)** — "The Expo framework: `create-expo-app`, Expo Router, native projects generated on
    demand (`expo prebuild`), cloud or local builds via EAS. Least native tooling to maintain."
  - **Metro (bare React Native CLI)** — "`@react-native-community/cli init`: committed `android/` and `ios/`
    projects you own and edit (Gradle, CocoaPods/Xcode), React Navigation, Metro run directly. Full native
    control; CI builds Android on Linux and iOS on macOS."
  Mention that Expo also bundles with Metro under the hood — the choice is about which toolchain owns the native
  projects, not the bundler. Never re-ask it later: Steps 3, 5 and 8 reuse this value.

Map the resolved `flavor` to its scaffold skill for the rest of this run:

| `flavor` | Scaffold skill |
|---|---|
| `library` | `iru-setup-typescript-library` |
| `react` | `iru-setup-react-web` |
| `nextjs` | `iru-setup-nextjs-web` |
| `angular` | `iru-setup-angular-web` |
| `nestjs` | `iru-setup-nestjs-app` |
| `react-native` | `iru-setup-react-native-app` |
| `ionic` | `iru-setup-ionic-app` |

If `mode` wasn't supplied via `args`, don't ask about it as an isolated question — check whether `package.json`
already exists at the repository root: if it does, default to `existing` and confirm with `AskUserQuestion`
(`existing` pre-selected; `new` as the other option, noting that "new" treats the repository as a fresh scaffold
target and that some flavors scaffold into a subdirectory rather than the root — see Step 3); if it doesn't,
default to `new` without asking further. Any other owned artifact already present (see the inventory below)
still makes this run an **update for that component**, even under `mode: new` — its sub-skill will warn and ask.

### Setup mode announcement

Right after resolving `mode`, before asking anything else, print a short **Setup mode** statement:

For `react-native`, prefix the statement with the resolved toolchain (e.g. "React Native app, **metro**
toolchain (detected: committed `android/`/`ios/`, no `expo` dependency)").

- `mode: new` with no owned artifact present → "Setting up this repository **from scratch** — no prior setup
  found."
- Otherwise → "**Updating an existing repository** — prior setup found:" followed by an inventory table of
  every component this run owns, each marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence checked |
  |---|---|
  | Build manifest / toolchain | `package.json`, lockfile, `tsconfig.json`, flavor config (`angular.json`, `vite.config.*`, `next.config.*`, `nest-cli.json`, `app.json`, `capacitor.config.*`; react-native metro: `metro.config.js`, `Gemfile` + `Gemfile.lock`) |
  | Native projects | react-native with `toolchain: metro` only: `android/` (`android/app/build.gradle`, Gradle wrapper) and `ios/` (`ios/Podfile` + `Podfile.lock`, `ios/<AppName>.xcodeproj`, `ios/<AppName>.xcworkspace`) — committed source on metro; on `expo` they're prebuild output and not inventoried |
  | Source layout | `src/` (or the flavor's app directory; `app/`/`pages/` for Next.js without `src/`) |
  | E2E test config | nextjs: `playwright.config.*` (+ `e2e/`); nestjs: `vitest.config.e2e.ts` or `test/jest-e2e.json` (+ `test/*.e2e-spec.ts`) — row only for these two flavors |
  | Container image | `Dockerfile`, `.dockerignore` — row only for nestjs, and nextjs with `output: server` |
  | ORM schema | nestjs with `orm` ≠ `none`: `prisma/schema.prisma` + `prisma.config.ts` (prisma), `drizzle.config.ts`, TypeORM data source / `src/**/migrations/` — row only when applicable |
  | UI quality config | `.ui-quality.yml` at the repository root — row only for the UI flavors (react, nextjs, angular, react-native, ionic) |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` |
  | `.gitignore` | `.gitignore` |
  | CI workflows | each of `.github/workflows/build.yml`, `release.yml`, `sync.yml` (+ `.github/scripts/sync_versions.py`), `security.yml` |
  | Dependabot | `.github/dependabot.yml` |
  | Sonar config | `sonar-project.properties` |
  | Changelog | `CHANGELOG.md` |
  | README | `README.md` |
  | License | `LICENSE` (or `LICENSE.md`/`COPYING`) |

  "Partial" means some but not all of a component's files exist (e.g. `docs/antora.yml` without a playbook,
  `build.yml` without `security.yml`). Then add: "For each component that already exists you'll be asked
  whether to keep it, update it, or replace it." (If `on-existing` came in via `args`, say instead that its
  value will be applied to every existing component without asking.)

This orchestrator never asks the keep/update/replace question itself — each sub-skill asks it for the files it
owns, so nothing is asked twice.

## Step 2 — Collect the project's identity

**`mode: existing` with the scaffold manifest already on disk** (`package.json` at the repository root): skip
every identity question in this step — the scaffold sub-skill (Step 5) reads the identity from the existing
`package.json` when it updates or keeps it, and asks only for anything it genuinely can't find (e.g. if the
user picks Replace there), while `iru-setup-readme` derives identity from the existing build files itself.
Resolve `license` only if `args` supplied it, and record in the final report that project identity was taken
from the existing files rather than asked.

For any field Step 0 already resolved from `args`, use that value directly. For everything else, ask the user
directly (plain conversation — these are free-text project-identity fields, not a bounded choice):

- **name** — the project name. Mapped to each scaffold skill's own key: `package-name` (library), `app-name`
  (react, nextjs, nestjs, react-native, ionic), or `name` (angular).
- **scope** — **library only**: the npm scope without the leading `@` (optional — ask whether the package should be
  scoped at all before asking for the scope itself).
- **app-id** — **ionic and react-native**: the reverse-DNS application id / bundle id, e.g. `com.example.myapp`.
  Required for react-native with `toolchain: metro` (the CLI bakes it into `applicationId` and
  `PRODUCT_BUNDLE_IDENTIFIER` at init, so changing it later is a package rename, and the metro `release.yml` uploads
  under it); for `toolchain: expo` ask it too but accept "not yet" (the scaffold then leaves
  `expo.ios.bundleIdentifier`/`expo.android.package` unset). Resolve it once here and reuse it.
- **app-slug** — **react-native only**: kebab-case; default to a slugified `name` rather than asking open-endedly,
  confirming with the user only if they want something different.
- **description** — one line. **Only asked for `library`/`react`/`nextjs`/`nestjs`/`ionic`** — `iru-setup-angular-web` and
  `iru-setup-react-native-app` accept no `description` key at all, so don't collect or pass one for those two
  flavors (a decision made here rather than inventing a key neither skill reads).
- **Developer name**, **developer email**, **organizationUrl**.

Then resolve the **license** with `AskUserQuestion` (one consistent choice reused across every flavor, since the
sub-skills' own recommended default order varies slightly and there's no need to track which):

- MIT License (recommended)
- Apache License 2.0
- No license (proprietary / all rights reserved)
- Other — ask for the license's display name (and SPDX id, if applicable) directly afterward

## Step 3 — Collect flavor-specific inputs

Only ask what applies to the resolved `flavor`; skip this step entirely for `library` (it has nothing here beyond
Step 2). For any field Step 0 already resolved from `args`, use that value directly.

- **`angular`**: **directory** — where to scaffold. If the repository root has no tracked files besides dotfiles,
  offer `.` as the default (the Angular project becomes the repository); otherwise ask explicitly, since
  `iru-setup-angular-web` can collide with existing root files. Then **storybook** (`AskUserQuestion`, default
  **No**) — needed here (not left to the sub-skill) because it also decides `docs-tool` in Step 8.
- **`react`**: **storybook** (`AskUserQuestion`, default **No**) — same reason as angular. **`keep-oxlint` is
  deliberately left unasked here** — it doesn't affect anything downstream of `iru-setup-react-web`, so that skill
  asks it directly when invoked without the key, instead of this orchestrator collecting it redundantly.
- **`react-native`**: **app-directory** — where to scaffold (if the repository root is otherwise empty, offer `.`;
  otherwise default to the resolved `app-slug` and confirm). `toolchain` was already resolved in Step 1. **Only
  for `toolchain: expo`: native-builds** (`AskUserQuestion`, default
  **`eas`** — cloud builds via Expo, noting the free tier's 15-builds/platform/month cap; **`local`** the
  alternative, needing a full native toolchain). This is collected here, not left to the scaffold skill alone,
  because `iru-setup-typescript-github-workflows` also needs it (Step 8) to pick the right `release.yml` variant.
  Never ask or forward `native-builds` for `toolchain: metro` — its committed native projects are always built
  locally (Gradle on Linux, `xcodebuild` on macOS).
- **`ionic`**: `framework` was already resolved in Step 1. **`add-android`/`add-ios` are deliberately left unasked
  here** — they don't affect anything outside `iru-setup-ionic-app` itself, so that skill asks them directly.
- **`nestjs`**: **orm** (`AskUserQuestion`, default **None**; options *None* / *TypeORM* / *Prisma* / *Other
  (Mongoose or Drizzle)* — a follow-up picks between those two) and, only when `orm` ≠ `none`, **database**
  (*PostgreSQL* (recommended) / *MySQL* / *MongoDB*; `mongoose` implies `mongodb`, so don't ask then, and don't
  offer MongoDB for TypeORM/Prisma/Drizzle unless the user insists). Collected here, not left to the scaffold,
  because `iru-setup-typescript-github-workflows` needs `orm` (Prisma needs a `prisma generate` step before
  typecheck/build, and an ORM means the e2e suite starts a real database with Testcontainers) — which in turn
  shapes Step 4's `ci-tests` explanation. **`module-system` and `http` are deliberately left unasked here** — they
  don't affect anything outside `iru-setup-nestjs-app`, so that skill asks (or defaults: `esm`, `express`) them
  itself.
- **`nextjs`**: **output** (`AskUserQuestion`): *Server (Node server, `output: 'standalone'`)* (recommended when
  the app needs server rendering at request time, Route Handlers, Server Actions, or a proxy) / *Static export
  (`output: 'export'`, plain HTML/JS — can be hosted on GitHub Pages)*. Then **e2e** (*Playwright* (recommended) /
  *None*). Both are collected here because they change `release.yml` (Pages deploy vs container image) and
  `build.yml` (Playwright step), and decide which of `pages`/`distribution`/`ci-tests` Step 4 asks. **`router`,
  `cache-components`, and `styling` are deliberately left unasked here** — they don't affect anything outside
  `iru-setup-nextjs-web`, so that skill asks (or defaults: `app`, `no`, `tailwind`) them itself.

`distribution` (react-native/ionic/nestjs/nextjs) is resolved in Step 4 alongside `open-source`/`sonar`, since its default
depends on `open-source` per this catalog's shared convention.

## Step 4 — Collect the shared pipeline parameters

Ask the user directly, presenting each with its default so a plain "yes"/blank reply accepts it (skip any Step 0
already resolved from `args`):

| Parameter | Default |
|---|---|
| Integration branch | `develop` |
| Stable branch | `main` |
| Node version | `24` |
| Package manager | `npm` |

These map directly onto `iru-setup-typescript-github-workflows`'s own placeholders (Step 8) — nothing to translate.

Then resolve, with `AskUserQuestion`, per this catalog's shared open-source-driven convention, in this order
(skip any key Step 0 already resolved):

- **open-source** — is this repository open source? Yes / No. Drives the recommended defaults below.
- **visibility** — if the repository has a GitHub remote, detect it with `gh repo view --json visibility -q
  .visibility` (`PUBLIC`/`PRIVATE`/`INTERNAL`, lowercased) and tell the user the detected value; otherwise (no
  remote yet, `gh` unavailable, not GitHub) ask **Public** / **Private**, recommending **Public** when
  open-source is Yes, otherwise **Private**. If open-source is Yes but visibility is `private`/`internal`, warn
  that these contradict each other (an open-source project's repository is normally public; a private one
  forfeits free Actions minutes and free SonarCloud) and ask which one is right. `internal` is treated like
  `private` for every cost decision below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** —
  standard GitHub-hosted runners are free for public repositories. Private/internal: still offer **Yes** first,
  but state, per GitHub's current pricing (tell the user to double-check their plan), that private
  repositories consume the account's included minutes — 2,000/month on GitHub Free, 3,000 on Pro and Team,
  50,000 on Enterprise Cloud — then are billed per minute; for `react-native` (`toolchain: expo` with
  `native-builds: local`, or `toolchain: metro`) and `ionic`, add that the iOS release job runs on macOS, whose
  minutes cost roughly 10× Linux, so a few dozen iOS builds a month can exhaust 2,000 minutes on their own — and
  for `toolchain: metro` that `build.yml` also has a macOS iOS simulator-build job, which the workflows skill
  limits to stable-branch pushes and manual runs on a private repository unless `ci-tests` is `full`. Offer **No** as "no workflows — build, test, and
  release locally (re-run `iru-setup-typescript-github-workflows` later to add them)". With `ci: no`, Step 8 is
  skipped entirely (no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no sync script),
  `ci-tests` and `pages` are not asked, and the report states that the shared security block (Dependabot,
  dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with it.
- **publish** — **library only**: default **Yes** when open-source is Yes; otherwise ask with **No** recommended
  (publishing to the public npm registry is meant for redistributable packages).
- **distribution** — **react-native/ionic**: `none`/`internal`/`store`. Default **`store`** when open-source
  is Yes; otherwise ask with **`internal`** recommended (a closed-source app rarely targets the public stores).
  **nestjs, and nextjs with `output: server`**: `none`/`internal` only — there is no store. `internal` = the
  release builds the multi-stage, non-root `Dockerfile` image and pushes it to GitHub Container Registry (GHCR)
  tagged with the release version; `none` = the scaffold still writes the `Dockerfile` for local use and
  `build.yml` still builds the production output, but no `release.yml` is generated — deploying is the user's
  follow-up. Ask with **`internal`** recommended (a service with nothing to deploy rarely makes sense), noting
  that GHCR packages of a public repository are free while private packages count against the account's Packages
  storage/transfer quota. **nextjs with `output: static`**: don't ask — set `distribution: none`; the static export
  is deployed by `release.yml` to GitHub Pages when `pages: yes` (below), otherwise uploaded as a workflow
  artifact.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?"
  `cloud`/`self-hosted`/`none`. Recommend **`cloud`** only when open-source is Yes **and** visibility is
  `public`; otherwise state that SonarCloud is free only for public open-source projects (a private project
  needs a paid plan) and recommend **`none`**, offering **self-hosted SonarQube** (Community Build is free, but
  you run the server) as the second option. When `ci: no`, say plainly that nothing would send reports
  automatically — analysis would only run when someone runs the scanner manually with a token — and recommend
  `none`; if the user still picks cloud/self-hosted, the scaffold writes `sonar-project.properties` only and the
  report notes that no CI step sends reports. When `cloud`/`self-hosted` is chosen, also
  collect `sonar-organization`/`sonar-project-key`/`sonar-host-url` the same way every sub-skill's own Step 2 does
  (SonarCloud: suggest `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io`, inferred from `git remote
  get-url origin`; self-hosted: ask for `sonar.host.url` directly, plus `sonar.organization` only if that server has
  organizations enabled, and `sonar.projectKey`).

Then, **only for a UI flavor** (`react`/`nextjs`/`angular`/`react-native`/`ionic` — never `library`/`nestjs`) and
only when `args` didn't supply it:

- **accessibility** — "Support accessibility and assistive technologies?" **Enforce (Recommended)** ("violations fail
  the UI tests — axe-core in the Playwright tests on the web, `eslint-plugin-react-native-a11y` plus role queries on
  React Native") / **Report only** ("violations are listed in test and group reports but never fail anything") /
  **Off** ("the automated checks don't run; the accessible-by-default coding rules still apply"). **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value. The scaffold
  writes it to `.ui-quality.yml` at the repository root, which the coding, test and documentation skills read. It
  costs no CI minutes either way. Asked regardless of `ci` — local UI test runs read it too.

Then, **only when `ci: yes`**:

- **ci-tests** — "Which tests should CI run?" Only asked for `react`/`angular`/`ionic`, the flavors whose
  `build.yml` has a Playwright e2e step, plus `nextjs` with `e2e: playwright`, `nestjs`, and `react-native` with
  `toolchain: metro` (Maestro flows on an Android emulator; on a private repository `full` also runs the macOS
  iOS simulator build on every pull request): **Unit tests only**
  ("lint, typecheck, unit tests with coverage — cheaper: fewer minutes, faster feedback; e2e stays local",
  recommended for private/internal) / **Unit + e2e** (`full` — "also runs the e2e suite on every build; complete
  verification, considerably more minutes", recommended for public). Phrase the e2e part per flavor: Playwright
  browsers install + run (react/angular/ionic, and nextjs against a production `next build`); for `nestjs`, the
  `test:e2e` suite (supertest against the bootstrapped app) — and when `orm` ≠ `none`, a real `database` started by
  Testcontainers (GitHub-hosted Linux runners have Docker; it adds image pulls and minutes); for `react-native`
  metro, a release APK installed on a `reactivecircus/android-emulator-runner` emulator and the `.maestro/` flows
  run against it (roughly 10–20 extra Linux minutes per run). For
  `library`/`react-native` with `toolchain: expo`, and `nextjs` with `e2e: none`, there's no e2e step in this pipeline, so don't ask —
  leave `ci-tests` unset (unless `args` supplied it) and let the workflows skill default it.
- **pages** — the Antora docs site (Step 6) is always built, and for `react`/`angular` — and `nextjs` with
  `output: static` — `release.yml` also deploys the web app itself to GitHub Pages (for `nestjs` and server-output
  `nextjs`, Pages only ever hosts the docs; the app ships as the `distribution` image). Public: default **yes**
  without asking (state it in Step 11). Private/internal: ask **No (Recommended)** ("CI still builds the docs —
  and for react/angular/static nextjs the release bundle — and uploads them as workflow artifacts; nothing is
  published") vs **Yes** ("requires GitHub Pro,
  Team, or Enterprise Cloud; the site will be PUBLIC on the internet unless you're on Enterprise Cloud with
  private Pages access control"). For `react`/`angular`/static `nextjs`, add that with **No** the app isn't
  hosted anywhere by this pipeline — the release produces a downloadable bundle and hosting it elsewhere is the
  user's follow-up.

These values (`open-source`, `visibility`, `ci`, `publish`/`distribution`, `sonar` + detail keys, `ci-tests`,
`pages`, `accessibility`, plus Step 3's flavor-specific keys) are passed through unchanged to the sub-skills that accept them — Step 5 (the scaffold skill), Step 8
(`iru-setup-typescript-github-workflows`), and Step 10 (`iru-setup-readme`) — so no sub-skill re-asks them.

## Step 5 — Run the flavor's scaffold skill

Always invoke the scaffold skill, in both modes — it owns the keep/update/replace decision for its own files.
When `package.json` (or any other file the scaffold writes) already exists, the scaffold skill announces that it
is updating, warns, and asks per file whether to **Update** (merge its template in, keeping customizations),
**Keep as is** (the old `mode: existing` skip), or **Replace** (regenerate, losing customizations) — or applies
`on-existing` if this orchestrator forwards it. This orchestrator doesn't ask that question itself, so it's
never asked twice. Format Steps 2–4's answers as `key: value` lines using **exactly** the keys the resolved
flavor's skill accepts (never an invented key) — always including `mode`, `visibility`, and `ci`, plus
`on-existing` only if it arrived via Step 0's `args` — and invoke via `iru-isolated-skill-executor`. In
`mode: existing` with identity questions skipped (Step 2), omit the identity keys; the scaffold reads them from
the existing files. One example per flavor:

```
# flavor: library
Agent({
  description: "Run setup-typescript-library",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-typescript-library\", args: \"package-name: <name>\\nscope: <scope, if set>\\n
    description: <description>\\nlicense: <license>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\nopen-source: <open-source>\\n
    publish: <publish>\\nsonar: <sonar>\\nsonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\n
    sonar-host-url: <..., if set>\\nintegration-branch: <integration-branch>\\nvisibility: <visibility>\\n
    ci: <ci>\\nmode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}). Report back: per file it owns,
    whether it was created, updated, replaced, or kept as is (or whether the user chose to stop), which
    devDependency versions came from a live registry lookup versus a fallback, and any value it resolved on its
    own.",
  run_in_background: false
})
```

`integration-branch` (Step 4's answer, default `develop`) is forwarded **only** to the `library` flavor: it's the
`baseBranch` `iru-setup-typescript-library` writes into `.changeset/config.json` when `publish: yes`, and without
it that file silently defaults to `main` while Step 8's workflows run on `develop`. The other six scaffold skills
have no such key — never send it to them. `accessibility` is the opposite: never sent to the library (or nestjs)
scaffold — they have no UI.

The other six flavors follow the same shape, with only the `args` keys and skill name changed:

- **`react`**: `iru-setup-react-web` — `app-name`, `description`, `license`, `developer-name`, `developer-email`,
  `organization-url`, `open-source`, `sonar` (+ detail keys), `storybook`, `accessibility`, `visibility`, `ci`, `mode` (+ `on-existing` if
  supplied). No `publish`/`distribution` key exists for this skill — never send one. `keep-oxlint` is deliberately omitted (Step 3) so the sub-skill asks it.
- **`nextjs`**: `iru-setup-nextjs-web` — `app-name`, `description`, `license`, `developer-name`, `developer-email`,
  `organization-url`, `open-source`, `sonar` (+ detail keys), `output`, `e2e`, `distribution`, `accessibility`,
  `package-manager`, `node-version`, `visibility`, `ci`, `mode` (+ `on-existing` if supplied), and `router`/`cache-components`/
  `styling` **only if they arrived via Step 0's `args`** (otherwise the sub-skill asks them — Step 3).
  `node-version` is the `Dockerfile` base image's Node major for `output: server`. `pages` is deliberately not
  sent: a static export's GitHub Pages project-site `basePath` comes from the `PAGES_BASE_PATH` environment variable
  that `iru-setup-typescript-github-workflows`' release job sets, read by `next.config` whatever `pages` is. No
  `publish`/`storybook`/`pages` key exists for this skill — never send one.
- **`nestjs`**: `iru-setup-nestjs-app` — `app-name`, `description`, `license`, `developer-name`, `developer-email`,
  `organization-url`, `open-source`, `sonar` (+ detail keys), `orm`, `database` (only when `orm` ≠ `none`),
  `distribution`, `package-manager`, `node-version`, `visibility`, `ci`, `mode` (+ `on-existing` if supplied), and
  `module-system`/`http` **only if they arrived via Step 0's `args`** (otherwise the sub-skill asks them — Step 3).
  `node-version` is the `Dockerfile` base image's Node major. No `publish`/`storybook`/`pages` key exists for this
  skill — never send one.
- **`angular`**: `iru-setup-angular-web` — `name`, `directory`, `license`, `developer-name`, `developer-email`,
  `organization-url`, `open-source`, `sonar` (+ detail keys), `storybook`, `accessibility`, `visibility`, `ci`, `mode` (+ `on-existing` if
  supplied). No `description` key exists for this skill — never send one.
- **`react-native`**: `iru-setup-react-native-app` — `app-name`, `app-slug`, `app-directory`, `license`,
  `developer-name`, `developer-email`, `organization-url`, `open-source`, `sonar` (+ detail keys), `toolchain`,
  `app-id` (always for `metro`, where it is required; for `expo` only when the user gave one),
  `native-builds` (**`toolchain: expo` only** — never sent for metro), `distribution`, `accessibility`, `visibility`, `ci`, `mode`
  (+ `on-existing` if supplied). No `description` key exists for this
  skill — never send one.
- **`ionic`**: `iru-setup-ionic-app` — `framework`, `app-name`, `app-id`, `description`, `license`,
  `developer-name`, `developer-email`, `organization-url`, `open-source`, `distribution`, `sonar` (+ detail keys),
  `accessibility`, `visibility`, `ci`, `mode` (+ `on-existing` if supplied). `add-android`/`add-ios` are deliberately omitted (Step 3)
  so the sub-skill asks them.

Each sub-skill parses these itself (its own Step 0) and only asks the user about anything genuinely left out —
`AskUserQuestion` surfaces to the user the same way whether invoked directly or from inside this sub-agent. If it
reports that the user chose to **stop** (e.g. its manifest exists but is unusable), stop this skill here too —
there's no coherent project identity to build Antora docs or CI workflows around yet. If the user chose **Keep
as is** for `package.json`, continue: the existing scaffold is presumed valid enough for the rest of the
pipeline to build against.

## Step 6 — Run `iru-setup-antora`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-antora", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-antora\"}) with no args (or, only if
`on-existing` arrived via Step 0's args, with args \"on-existing: <on-existing>\") — it derives the Antora
component name, title, and version straight from the repository's package.json. Report back: whether it ran from
scratch or as an update, the action taken (created / updated / replaced / kept as is), which files/pages were
created vs. already present, and whether the site build succeeded.", run_in_background: false})`. When the site
already exists, `iru-setup-antora` itself warns and asks Update (fill gaps) / Keep / Replace.

Run this *before* Step 8, not after, even though the user might expect them in the other order:
`iru-setup-antora` is quick to detect as "already done", and `iru-setup-typescript-github-workflows`'s own survey
(Step 1 there) checks whether `docs/antora.yml`/`docs/antora-playbook.yml` already exist — running Antora setup
first means that check finds everything in place instead of flagging a gap.

## Step 7 — Run `iru-setup-typescript-gitignore`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-typescript-gitignore", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-typescript-gitignore\"}) with no args (or, only if
`on-existing` arrived via Step 0's args, with args \"on-existing: <on-existing>\") — it derives everything else it
needs (package manager, framework, Playwright, docs tooling) by exploring the repository directly. Report back
only the action taken: created, updated (merged diff accepted), replaced, or kept as is.", run_in_background:
false})`. If `.gitignore` exists, the sub-skill shows a diff and asks Update / Keep / Replace itself. Run this after Steps 5–6, not before:
detection depends on the scaffold's lockfile/framework signals and the Antora config Step 6 just wrote. Run it
*before* Step 8, so `node_modules/`, `dist/`, `coverage/`, and the Antora build output are already ignored before
CI-generated reports show up locally.

## Step 8 — Run `iru-setup-typescript-github-workflows`

**Skip this step entirely when `ci: no`** (Step 4): don't invoke the sub-skill — no `.github/workflows/*`, no
`security.yml`, no `.github/dependabot.yml`, no sync script are written, and any workflow files already on disk
are left untouched. Record "skipped (ci: no)" for the CI workflows and Dependabot components in Step 11's
report, state that the shared security block (Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) was
skipped with it, and that re-running `/iru-setup-typescript-github-workflows` later adds it all.

Otherwise, first derive `docs-tool` from the resolved `flavor` and Step 3's `storybook` answer — checked against what each
scaffold skill actually wires up, not assumed:

| `flavor` | `docs-tool` |
|---|---|
| `library` | `typedoc` (`iru-setup-typescript-library` always writes `typedoc.json`) |
| `angular` | `compodoc` (`iru-setup-angular-web` always adds a `docs` script running Compodoc) |
| `react` | `storybook` if Step 3's `storybook` answer was Yes, else `none` |
| `react-native` | `none` — `iru-setup-react-native-app` has no Storybook option at all |
| `ionic` | `none` — `iru-setup-ionic-app` has no Storybook option at all |
| `nextjs` / `nestjs` | read from disk after Step 5, not assumed: `typedoc` if `typedoc.json` exists or a `package.json` script runs `typedoc`, `compodoc` if a script runs `@compodoc/compodoc`/`compodoc`, else `none` |

Format Steps 1–4's answers as `key: value` lines and invoke via `iru-isolated-skill-executor`:

```
Agent({
  description: "Run setup-typescript-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-typescript-github-workflows\", args: \"flavor: <flavor>\\n
    integration-branch: <integration-branch>\\nstable-branch: <stable-branch>\\nnode-version: <node-version>\\n
    package-manager: <package-manager>\\nopen-source: <open-source>\\npublish: <publish, library only>\\n
    distribution: <distribution, react-native/ionic/nestjs/nextjs only>\\nsonar: <sonar>\\n
    sonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\n
    toolchain: <toolchain, react-native only>\\nnative-builds: <native-builds, react-native expo only>\\n
    framework: <framework, ionic only>\\n
    orm: <orm, nestjs only>\\noutput: <output, nextjs only>\\ne2e: <e2e, nextjs only>\\n
    docs-tool: <docs-tool>\\nvisibility: <visibility>\\nci-tests: <ci-tests, if resolved>\\npages: <pages>\\n
    mode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}). Report back: whether it ran from
    scratch or as an update, and per file (build.yml/release.yml/sync.yml/security.yml/.github/dependabot.yml)
    whether it was created, updated, replaced, or kept as is (or whether the user chose to stop), any mismatch it
    listed for a kept file, and the full required-secrets table it produced for this flavor/options.",
  run_in_background: false
})
```

Omit `publish`/`distribution`/`toolchain`/`native-builds`/`framework`/`orm`/`output`/`e2e` entirely when they don't apply to
the resolved `flavor` (never send a key the flavor doesn't use — `distribution` is sent for
react-native/ionic/nestjs/nextjs, `toolchain` for every react-native run, `native-builds` only for react-native
with `toolchain: expo`), and omit `ci-tests` when Step 4 left it unset (`library`/`react-native` with
`toolchain: expo`/`nextjs` with `e2e: none`). `database` and the scaffold-only keys `module-system`, `http`, `router`, `cache-components`, and
`styling` are never sent to the workflows skill — it accepts none of them and reads what it needs (lockfile,
`package.json` scripts, `next.config.*`, the ORM's config) from disk. `ci` itself is never forwarded — invoking the workflows skill means CI is wanted. `mode` is
always passed but is informational there: for every workflow file already present,
`iru-setup-typescript-github-workflows` announces the update, warns, and asks Update / Keep / Replace / Stop
per file (its Step 2) unless `on-existing` was forwarded. The four `security-*` keys
are deliberately never passed — they each default to `yes` inside `iru-setup-typescript-github-workflows` itself,
and this orchestrator doesn't ask about them separately; note this once in Step 11's report rather than re-asking
per run. If it reports that the workflow files already existed and the user chose to stop, note that in Step
11's report rather than treating it as a failure of this skill — the
scaffold, the Antora docs, and the `.gitignore` from Steps 5–7 are still valid on their own.

## Step 9 — Run `iru-setup-changelog`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-changelog", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-changelog\"}) with no args (or, only if
`on-existing` arrived via Step 0's args, with args \"on-existing: <on-existing>\") — it works entirely from the
repository's own git tag/GitHub Release history. Report back only the action taken (created, updated with
missing releases, replaced, or kept as is) and the version range reconstructed.", run_in_background: false})`.
Position relative to Steps 5–8 doesn't matter functionally — it's independent of all of them. If `CHANGELOG.md`
already exists, the sub-skill warns and asks Keep (the old skip) / Update (append missing releases only) /
Replace itself. Running it before `iru-setup-readme` means the README's Documentation
section sees it already in place.

## Step 10 — Run `iru-setup-readme`

Invoke via `iru-isolated-skill-executor`: `Agent({description: "Run setup-readme", subagent_type:
"iru-isolated-skill-executor", prompt: "Invoke Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\n
pages: <pages, if resolved>\\non-existing: <on-existing, only if supplied via args>\"}) — with `sonar: none` it suppresses Sonar
badges/links even if Sonar config exists on disk, with `ci: no` it omits CI badges, and with `pages: no` it
doesn't link a github.io documentation site; it derives everything else by exploring the repository directly
(package.json, the Antora docs, .gitignore, the workflows and Sonar config, and CHANGELOG.md). Report back only
the action taken (created, updated, replaced, or kept as is) and which sections were included vs. omitted and
why.",
run_in_background: false})`. Run this last, after every other skill, so its badges/status-table/documentation-links
sections see the finished state of Steps 5–9 instead of a sparser mid-run snapshot. In `mode: new`, most
CI/Sonar/changelog-derived sections will still be sparse or absent (no commits/tags/CI runs yet) — expected;
`iru-setup-readme` omits what it can't confirm rather than inventing it.

## Step 11 — Report

Summarize the outcome of all six delegated skills together: repeat the **Setup mode** statement from Step 1
(from scratch vs updating an existing repository), the resolved `flavor` and `mode`, the project identity and
license, and the pipeline parameters used. Then give a per-component table — the scaffold's own files
(`package.json` and toolchain config, source skeleton, and for react-native `metro` the committed `android/`/`ios/`
native projects; for the UI flavors `.ui-quality.yml`, as the scaffold reported it), the Antora docs site, the `.gitignore`, each workflow file
(`build.yml`/`release.yml`/`sync.yml` where applicable/`security.yml`), `.github/dependabot.yml`,
`sonar-project.properties`, `CHANGELOG.md`, and `README.md` — with the action actually taken, as each sub-skill
reported it: **created / updated / replaced / kept as is / skipped (ci: no)** (or "stopped" per a stop choice in
Step 5 or 8). For every component kept as is, include the mismatches its sub-skill listed against this run's
choices.

State explicitly which of publishing/distribution and Sonar analysis were wired, or deliberately omitted and why:

- **`open-source`** / **`visibility`**: as resolved in Step 4 (and whether visibility was detected via `gh` or
  asked). When `private`/`internal`, restate the cost context given in Step 4 (included Actions minutes per plan,
  macOS ~10× for the iOS release job) and tell the user to double-check their plan's current pricing.
- **`ci`**: yes/no. With `no`, state that no workflows, `security.yml`, or `.github/dependabot.yml` were
  generated, that the shared security block (Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) was
  skipped with them, and that `/iru-setup-typescript-github-workflows` can add them later.
- **`ci-tests`** (when `ci: yes`): `unit`/`full`, or unset for `library`/`react-native`/`nextjs` with `e2e: none`,
  and whether the e2e block (Playwright, or the NestJS `test:e2e` suite with Testcontainers) was included in
  `build.yml`.
- **`pages`** (when `ci: yes`): yes (defaulted for a public repository, or chosen) / no (docs — and the
  `react`/`angular`/static-`nextjs` release bundle — uploaded as workflow artifacts only; for those flavors the app
  isn't hosted anywhere by this pipeline, so hosting it is a follow-up for the user).
- **Flavor-specific choices**: react-native `toolchain` (and whether it came from `args`, the `iru-explore`
  report, detection — naming the signal — or the question) plus `native-builds` for `expo`; for `metro`, the
  native projects' action as the scaffold reported it and the `build.yml` jobs the workflows skill generated
  (`android`, `ios` with its cost gate, `e2e-android` when `ci-tests: full`); nestjs `orm`/`database` (+ the `module-system`/`http` the scaffold resolved),
  nextjs `output`/`e2e` (+ the `router`/`cache-components`/`styling` the scaffold resolved).
- **`publish`** (library) / **`distribution`** (react-native, ionic, nestjs, server-output nextjs — `internal` =
  container image pushed to GHCR; static nextjs is always `none`): whether the corresponding scaffold config and
  `release.yml` job were included — `yes`/non-`none` unless the user opted out (or the project isn't open source
  and declined it), stated plainly rather than left implicit.
- **`sonar`**: `cloud`/`self-hosted`/`none`, and whether `sonar-project.properties` and the `Run SonarQube/SonarCloud
  analysis` workflow step were included — if `none`, note whether that's the expected default (not open source,
  not public, or `ci: no`) or a direct user choice; if cloud/self-hosted with `ci: no`, note that no CI step sends
  reports.
- **`docs-tool`**: the value derived in Step 8 and why (flavor, plus the `storybook` answer for `react`).
- **`accessibility`** (UI flavors): `enforce`/`report`/`off`/`none`, where it came from (`args` or the question), and the
  `.ui-quality.yml` action (created / updated / replaced / kept as is — with any mismatch when kept). For
  `library`/`nestjs`: "not applicable — no UI".

Unless Step 8 was skipped (`ci: no`), reproduce the **consolidated required-secrets/settings table**
`iru-setup-typescript-github-workflows` reported in Step 8 verbatim (it's already scoped to exactly this flavor/options — don't re-derive or expand it), plus its
repository-settings notes (GitHub Pages source = GitHub Actions when `pages: yes`, "Workflow permissions" allowing PR creation for
`sync.yml`, the npmjs.com trusted-publisher relationship for `publish: yes`).

List every decision made on the user's behalf: the license question's fixed MIT-first ordering (Step 2), leaving
`keep-oxlint`/`add-android`/`add-ios`/`module-system`/`http`/`router`/`cache-components`/`styling` for their
sub-skills to ask (Step 3), forcing `distribution: none` for a static Next.js export (Step 4), the mobile flavors' `docs-tool: none`
default (Step 8), and the four `security-*` flags left at their `yes` default (Step 8).

Finish with an explicit **Warn explicitly** block, repeating each sub-skill's own closing warning:

- **Review every generated/updated file before building, committing, or relying on CI.** Confirm the license
  matches an actual `LICENSE` file if one was chosen (`iru-check-license` can generate one and backfill headers),
  and that `npm run build && npm test && npm run typecheck` (or the flavor's equivalent scripts) succeed.
- If `sonar: cloud`/`self-hosted` was chosen, a `SONAR_TOKEN` repository secret and an actual SonarCloud/SonarQube
  project matching `sonar.projectKey` still need to exist before a CI-run Sonar scan will succeed.
- If `distribution: internal` was chosen for `nestjs`/`nextjs`, the image is pushed to GHCR with the workflow's
  `GITHUB_TOKEN` (`packages: write`); a new package is private by default even for a public repository — change
  its visibility in the package settings if it should be pullable anonymously.
- If `publish: yes`/`distribution: store` was chosen, the registry/store credentials named in the secrets table
  still need to exist before a CI-driven publish/submit will succeed — nothing in this pipeline creates or stores
  them.
- Every devDependency/action version the scaffold and workflows skills wrote was resolved via a run-time lookup (or
  its recorded fallback) — re-run `npm outdated` before relying on this scaffold long-term.
