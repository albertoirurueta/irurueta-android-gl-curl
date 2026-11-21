---
name: iru-setup-typescript-gitignore
description: "/iru-setup-typescript-gitignore [args] creates or updates the root .gitignore for a TypeScript/npm project, adding framework-specific paths when detected (Angular, React, NestJS, Next.js, React Native, Ionic, Hilla, Playwright, docs). Optional on-existing arg. Shows a diff and asks Update/Keep as is/Replace before touching an existing file."
model: haiku
---

# Setup TypeScript Gitignore

Create or refresh the root `.gitignore` for a TypeScript/npm project. Never overwrite an existing `.gitignore`
without showing the user exactly what would change and getting explicit approval first — a `.gitignore` can carry
hand-added, project-specific entries that must not be silently dropped. Mirrors the `iru-setup-java-gitignore`
skill's contract for the npm/TypeScript ecosystem.

## Step 0 — Parse `args`

This skill takes a single optional key, as a `key: value` line in `args`: `on-existing` (`ask`/`keep`/`update`/
`replace`, default `ask`) — how to treat an existing `.gitignore` in scripted runs (an orchestrator forwards it
only if its own `args` received it). Ignore any other key (orchestrators may pass shared keys such as `mode`;
`mode: existing` alone doesn't skip Step 5's question). If `args` is absent or unparseable, treat `on-existing`
as `ask`.

## Step 1 — Check whether `.gitignore` already exists at the repository root

- **Not found**: tell the user "Creating `.gitignore` **from scratch** — none found." Continue to Step 2.
  Because there's nothing to lose, the final write happens without needing approval — go straight from
  composing the content to writing it, then report in Step 6.
- **Found**: tell the user "**Updating existing `.gitignore`**" and warn that one already exists, so this skill
  will propose changes for them to review rather than overwrite it silently. Read its current content now — it
  matters later, both to avoid proposing duplicate entries and because Step 4 must preserve any hand-written,
  project-specific lines this skill doesn't know how to regenerate. If `on-existing: keep`, stop here: leave the
  file untouched and go straight to Step 6's report. Otherwise continue to Step 2.

## Step 2 — The universal base template

Every TypeScript/npm project gets this baseline, regardless of framework or package manager:

```gitignore
# Dependencies
node_modules/

# Build output
dist/
build/
out/

# Test / coverage output
coverage/

# TypeScript incremental build info
*.tsbuildinfo

# Lint cache
.eslintcache

# Environment files (never commit real secrets; the example file documents the shape)
.env*
!.env.example

# npm debug logs
npm-debug.log*

# OS noise
.DS_Store

# IDE noise
.idea/

# VS Code — ignore the directory but keep the shared, non-machine-specific config files
.vscode/*
!.vscode/settings.json
!.vscode/extensions.json
!.vscode/launch.json
!.vscode/tasks.json
```

This section is unconditional — include it verbatim regardless of framework.

**Note on `dist/`/`build/`**: a handful of projects intentionally commit their build output (e.g. a GitHub Pages
site served straight from a `docs/`-as-`dist/` branch, or a library that vendors its compiled output into the repo
for consumers without a build step). If the target repository already has tracked files under `dist/`, `build/`, or
`out/` (check with `git ls-files -- dist build out` when `.gitignore` doesn't already exist, before assuming), ask
the user via `AskUserQuestion` whether to keep ignoring that directory or drop it from the template — don't ignore a
directory out from under files that are already tracked.

## Step 3 — Detect project-specific additions

Don't guess; only add a section below when the thing it targets is actually present in the target repository. Use
the same manifest signals `iru-explore` and the sibling TypeScript setup skills rely on: `package.json`,
`angular.json`, `nest-cli.json`, `next.config.*`, `app.json`, `metro.config.js`, `capacitor.config.*`,
`prisma/schema.prisma`, `pom.xml`, `docs/antora.yml` (or `docs/antora-playbook.yml`), and `typedoc.json`.

- **Package manager–specific debug logs**: inspect `package.json`'s `packageManager` field and the lockfile present
  at the root.
  - `pnpm-lock.yaml` (or `packageManager: pnpm@...`) → pnpm project: add
    ```gitignore
    pnpm-debug.log*
    .pnpm-store/
    ```
  - `yarn.lock` (or `packageManager: yarn@...`) → Yarn project: add
    ```gitignore
    yarn-error.log*
    ```
  - `package-lock.json` (or no lockfile / npm default) → nothing extra; `npm-debug.log*` in the base template
    already covers it.
  - More than one lockfile can exist in a transitional repo — include every block that matches; don't guess a
    manager that has no lockfile evidence.

- **Angular** (`angular.json` at the root, or `@angular/core` in `package.json` dependencies): add
  ```gitignore
  .angular/
  ```
  (the Angular CLI's build/dev-server cache directory).

- **React / Vite** (`vite.config.ts`/`vite.config.js` at the root, or `vite` in `package.json` devDependencies):
  add
  ```gitignore
  .vite/
  *.local
  ```
  (`.vite/` is Vite's own cache directory when it lands at the project root rather than nested under
  `node_modules/.vite`; `*.local` is the entry `npm create vite@latest` itself scaffolds, for local env overrides
  beyond the `.env*` pattern already in the base template). If a `.storybook/` directory or a `storybook`-scoped
  package (`storybook`, `@storybook/react-vite`, `@storybook/angular-vite`, etc.) is present in
  `package.json` devDependencies, also add:
  ```gitignore
  storybook-static/
  ```

- **NestJS** (`nest-cli.json` at the root, or `@nestjs/core` in `package.json` dependencies): add
  ```gitignore
  logs/
  *.log
  .temp/
  .tmp/
  pids
  *.pid
  *.seed
  *.pid.lock
  report.[0-9]*.[0-9]*.[0-9]*.[0-9]*.json
  test-results/
  ```
  Everything except `test-results/` is taken from the `.gitignore` that `nest new` (`@nestjs/cli` 12) itself
  writes, minus what the base template already covers (`dist/`, `build/`, `coverage/`, `*.tsbuildinfo`, the `.env*`
  family, debug logs, `.idea/`, `.vscode/*`); `report.*.json` is Node's diagnostic-report file name.
  `test-results/` is where the scaffold's JUnit reporter (Vitest or Jest) writes CI test reports — skip the line if
  the Playwright block below already adds it. Nest's own template also lists Eclipse/Sublime files (`.project`,
  `.classpath`, `.settings/`, `*.launch`, `.c9/`, `*.sublime-workspace`) and `.nyc_output`; this skill leaves those
  to the base template's IDE policy and the Istanbul/nyc block below rather than guessing at editors.

- **Next.js** (`next.config.js`/`.mjs`/`.ts` at the root, or `next` in `package.json` dependencies): add
  ```gitignore
  .next/
  next-env.d.ts
  .vercel
  *.pem
  .pnp
  .pnp.*
  .yarn/*
  !.yarn/patches
  !.yarn/plugins
  !.yarn/releases
  !.yarn/versions
  ```
  This mirrors the `.gitignore` that `create-next-app` (Next.js 16) writes, minus what the base template already
  covers (`out/` — the static-export output — `build/`, `coverage/`, `*.tsbuildinfo`, `.env*`, debug logs,
  `.DS_Store`). **`next-env.d.ts` is ignored, not committed**: `create-next-app` ignores it and `next dev`/`next
  build`/`next typegen` regenerate it, which is why the type-check script is `next typegen && tsc --noEmit` (run it
  before `tsc` in a clean checkout). Its Yarn Plug'n'Play lines are harmless for npm/pnpm projects and are kept so
  the file stays recognisably create-next-app's; `.env*` here has no exception in the upstream template, but this
  skill's base `!.env.example` is kept.

- **Prisma client generated inside the source tree** (`prisma/schema.prisma` or `prisma.config.ts` exists, or
  `prisma` is in `package.json` devDependencies): read the `generator client { … output = "…" }` path from the
  schema (relative to the schema file's directory) and, when it resolves inside the repository (Prisma 7's
  `provider = "prisma-client"` requires an explicit `output`, conventionally `../src/generated/prisma`), add that
  directory relative to the root, e.g.
  ```gitignore
  src/generated/prisma/
  ```
  The generated client is never committed — `npx prisma generate` recreates it (locally after every schema change,
  and as a CI step), per the NestJS coding reference (`iru-nestjs-code-one-task` `reference/persistence.md`). Never
  ignore `prisma/migrations/` — migrations are committed. With the legacy `prisma-client-js` provider and no
  `output`, the client lands in `node_modules/` and needs no line.

- **Repository-wide integration-test lock** (NestJS, Next.js, or React Native — either toolchain, `expo` or
  `metro` — detected, or a `.integration-test.lock/` directory already exists): add
  ```gitignore
  .integration-test.lock/
  ```
  This is the atomic `mkdir` lock `iru-nestjs-code-one-task-group` / `iru-nextjs-code-one-task-group` /
  `iru-react-native-code-one-task-group` and `iru-typescript-test`'s React Native `e2e` selector (and the JVM
  coding skills) take to serialize container-backed/e2e stages; it must never be committed, and adding it here
  saves those skills from having to patch `.gitignore` mid-run.

- **React Native** (`react-native` or `expo` in `package.json` dependencies, or `app.json` with an `"expo"` key, and
  no `capacitor.config.*` — see the Ionic/Capacitor case below, which takes precedence when both signals are
  present). Look for that `package.json` at the root **and** in an app subdirectory (`iru-setup-react-native-app`
  scaffolds into `<app-directory>`, usually the app slug, whenever the root isn't empty). Pick the toolchain with
  the catalog-wide rule — `expo` in `dependencies` → **Expo** (even when `android/`/`ios/` exist: that's
  prebuild output); `react-native` without `expo`, plus `metro.config.js`, committed `android/` + `ios/`, or
  `@react-native-community/cli` in `devDependencies` → **Metro** (a bare React Native CLI app) — and add the one
  matching block below. **Path prefix**: every line below containing a `/` before its end is anchored to the
  `.gitignore`'s directory by git, so when the app lives in `<app-directory>` ≠ `.`, prefix those lines with
  `<app-directory>/` (e.g. `mobile/android/app/build/`, `mobile/ios/Pods/`); slash-free patterns (`*.p12`,
  `DerivedData/`, `xcuserdata/`, `.metro-health-check*`) match at any depth and stay as they are.

  - **Expo** (`toolchain: expo`): add
    ```gitignore
    .expo/
    .expo-shared/
    expo-env.d.ts
    web-build/
    .metro-health-check*
    *.jks
    *.p8
    *.p12
    *.mobileprovision
    *.keystore
    android/app/build/
    android/.gradle/
    android/local.properties
    ios/Pods/
    ios/build/
    DerivedData/
    ```
    These paths assume a prebuilt layout (`android/`, `ios/` at the app root, as `expo prebuild` produces them). If
    the project stays fully managed (no `android/`/`ios/` directories committed), the `android/`/`ios/`-prefixed
    lines are harmless no-ops but still worth including since `expo prebuild` can be run later. `create-expo-app`'s
    own template goes further and ignores the generated native folders outright (`/ios`, `/android`, under a
    `# generated native folders` comment, which Continuous Native Generation regenerates); offer those two lines
    instead of the `android/…`/`ios/…` ones when `git ls-files -- android ios` (prefixed) is empty, and never when
    the team already commits prebuild output.
  - **Metro** (`toolchain: metro`): add
    ```gitignore
    # Android / Gradle
    android/app/build/
    android/build/
    android/.gradle/
    android/.kotlin/
    android/app/.cxx/
    android/local.properties
    *.iml
    *.hprof
    *.keystore
    !debug.keystore
    *.jks

    # iOS / Xcode / CocoaPods
    ios/Pods/
    ios/build/
    ios/.xcode.env.local
    xcuserdata/
    *.xcuserstate
    *.xccheckout
    *.moved-aside
    *.hmap
    *.ipa
    *.pbxuser
    !default.pbxuser
    *.mode1v3
    !default.mode1v3
    *.mode2v3
    !default.mode2v3
    *.perspectivev3
    !default.perspectivev3
    DerivedData/

    # Signing material
    *.p8
    *.p12
    *.mobileprovision

    # Ruby / Bundler (CocoaPods is pinned by the Gemfile)
    vendor/bundle/

    # Metro / bundle artifacts
    .metro-health-check*
    *.jsbundle

    # fastlane
    **/fastlane/report.xml
    **/fastlane/Preview.html
    **/fastlane/screenshots
    **/fastlane/test_output
    ```
    Compared, line by line, against the `.gitignore` that `@react-native-community/cli` 20.2.0's default
    TypeScript template (React Native 0.87) writes into the app directory, and against what an actual
    `npm install` + `./gradlew assembleDebug` + `bundle install` + `bundle exec pod install` + `xcodebuild` run
    left untracked in October 2026 (`android/.gradle/`, `android/build/`, `android/app/build/`,
    `android/app/.cxx/`, `ios/Pods/`, `ios/build/`, `ios/.xcode.env.local`, `vendor/bundle/`) — verified with
    the base template + this block, `<app-directory>/`-prefixed, as the only `.gitignore`: every one of those
    paths ends up ignored and every file the template means to commit (`android/app/debug.keystore`,
    `.bundle/config`, `Gemfile`/`Gemfile.lock`, `ios/Podfile`/`Podfile.lock`, `ios/.xcode.env`,
    `ios/<AppName>.xcworkspace`) stays visible to `git status`. Deliberate
    differences from the template: its unanchored `.gradle` and `local.properties` are written as the concrete
    `android/…` paths above (its `build/`, `.idea`, `/coverage` and `.DS_Store` are already covered by the base
    template's `build/`, `.idea/`, `coverage/`, `.DS_Store` — the explicit `android/…/build/`/`ios/build/` lines
    stay so the block still works if a project drops the base `build/` line for a committed build directory);
    `**/Pods/` and `/vendor/bundle/` become `ios/Pods/` and `vendor/bundle/` (prefix both with `<app-directory>/`
    as above); signing material (`*.jks`, `*.p8`, `*.p12`, `*.mobileprovision`) is added — an upload keystore or
    an App Store Connect key must never be committed. **Keep `!debug.keystore`**: the template commits
    `android/app/debug.keystore` on purpose (the public debug signing key Gradle's `debug` — and, until a real
    upload key is injected, `release` — build type uses), so a bare `*.keystore` would break a fresh clone's
    `assembleDebug`. **Don't ignore `.bundle/`**: the template commits `.bundle/config` (it sets
    `BUNDLE_PATH: "vendor/bundle"`), and `Gemfile.lock`, `ios/Podfile.lock`, `ios/.xcode.env` and
    `ios/<AppName>.xcworkspace` are committed too — none of them may match an ignore line. Metro's own transform
    cache lives in the OS temp directory, not the project, so `.metro-health-check*` is its only in-tree file.
    If Detox is configured (`.detoxrc.js`/`.detoxrc.json`, or `detox` in `devDependencies`), also add its default
    artifacts directory:
    ```gitignore
    artifacts/
    ```
    (or the `artifacts.rootDir` the Detox config sets). Maestro writes its reports outside the project by
    default, so it needs no line.

- **Ionic / Capacitor** (`capacitor.config.ts`/`capacitor.config.json` at the root, or `@capacitor/core` in
  `package.json` dependencies): add
  ```gitignore
  .capacitor/
  *.jks
  *.p8
  *.p12
  *.mobileprovision
  *.keystore
  android/app/build/
  android/.gradle/
  android/local.properties
  ios/App/Pods/
  ios/App/App.xcworkspace/xcuserdata/
  DerivedData/
  ```
  Capacitor nests the generated iOS project under `ios/App/` (unlike bare React Native's `ios/<AppName>/`), so the
  Pods/xcuserdata paths are prefixed accordingly — verify the actual nested folder name against `ios/` once it
  exists (`npx cap add ios` names the Xcode project after the app), and adjust the line if the project used a
  different name.

- **UI screenshots** (a UI project — React, Angular, Ionic, Next.js, React Native (either toolchain), or a Hilla
  frontend — detected, a `.ui-quality.yml` at the root, or a `.ui-screenshots/` directory already exists; never for a
  plain library or NestJS service): add
  ```gitignore
  # UI test screenshots (UI contract) — regenerated on every full UI run; docs copy what they use into Antora images/ui/
  .ui-screenshots/
  ```
  The catalog's UI tests (Playwright helpers, Maestro capture flows, Detox helpers) write one PNG per state ×
  direction × theme there and `iru-typescript-test` clears it at the start of every full UI run; the only committed
  copies are the ones the documentation skills place under `docs/modules/ROOT/images/ui/`.

- **Playwright** (`playwright.config.ts`/`playwright.config.js` at the root, or `@playwright/test` in
  `package.json` devDependencies): add
  ```gitignore
  playwright-report/
  test-results/
  blob-report/
  .playwright/
  ```
  (`test-results/` may already have been added by the NestJS block — don't list it twice.)

- **Turborepo** (`turbo.json` at the root): add
  ```gitignore
  .turbo/
  ```

- **Istanbul/nyc coverage** (`.nycrc`/`.nycrc.json` at the root, or `nyc` in `package.json` devDependencies —
  most projects in this catalog use Vitest's or Jest's built-in coverage instead, already covered by the base
  `coverage/` entry, so this is rare): add
  ```gitignore
  .nyc_output/
  ```

- **Antora documentation build output** (`docs/antora.yml` or `docs/antora-playbook.yml` exists — see the
  `iru-setup-antora` skill): add
  ```gitignore
  docs/build/
  ```

- **TypeDoc / Compodoc API docs** (`typedoc.json` at the root, or a `docs`/`typedoc`/`compodoc` script in
  `package.json` invoking `typedoc` or `@compodoc/compodoc`; also `@compodoc/compodoc` itself in devDependencies):
  add
  ```gitignore
  docs/api/
  ```
  If both Antora and TypeDoc/Compodoc are present and TypeDoc/Compodoc is configured to output *inside* the Antora
  tree (e.g. `docs/modules/ROOT/assets/api/`, matching how the TypeScript workflows skill merges API docs under
  the Antora build), use that actual `out` path from `typedoc.json`/the `docs` script instead of the generic
  `docs/api/` — don't duplicate an entry already covered by `docs/build/` above.

- **Spring Boot + Vaadin Hilla frontend at a Maven root** (`pom.xml` at the root depends on
  `hilla-spring-boot-starter` / `com.vaadin:hilla`, or a `src/main/frontend/` directory exists): the frontend
  toolchain lives inside a Maven project, so the generated/installed paths sit under `src/main/frontend/` and
  `node_modules/` sits at the Maven root rather than the repository root the base template assumes. Add:
  ```gitignore
  node_modules/
  src/main/frontend/generated/
  src/main/frontend/vite.generated.ts
  pnpmfile.js
  .npmrc
  ```
  This block is **verified against Vaadin's own published `.gitignore` template** (Vaadin docs, "Using source
  control with Vaadin Flow" — the Hilla frontend is generated by the same `vaadin-maven-plugin`/
  `vaadin-gradle-plugin` tooling as Flow): `node_modules/`, `src/main/frontend/generated/`, and
  `vite.generated.ts` are explicitly listed there as plugin-generated and never committed, and so are the
  plugin-written `pnpmfile.js` and `.npmrc`. That same source explicitly calls out `tsconfig.json`, `types.d.ts`,
  and `src/main/frontend/index.html` as auto-generated **only if missing** — they should be tracked, not ignored,
  once the project customizes any of them, so this skill deliberately does **not** add them here. No official
  Vaadin/Hilla source mentions a `.vaadin/` directory in the `.gitignore` template, so this skill does not add one
  — if a future Hilla version introduces one, fold it in here once confirmed rather than guessing.

- Don't invent additional entries beyond what's actually detected — if the project has no Storybook, no native
  mobile shell, no docs tooling, no NestJS/Next.js/Prisma, or no Hilla frontend, the composed `.gitignore` should
  simply not mention them.

## Step 4 — Compose the proposed content

Concatenate Step 2's base template with whichever Step 3 sections actually matched, each under its own comment
header for readability (e.g. `# Angular`, `# NestJS`, `# Next.js`, `# React Native (Metro)`,
`# Prisma generated client`, `# Playwright`, `# UI test screenshots …`, `# Vaadin Hilla frontend`). If Step 1 found an existing `.gitignore`,
compose two drafts:

- **Merged** (the Update draft): keep any of the existing file's lines that aren't already covered by the
  composed template (hand-added project-specific ignores must survive), and don't repeat a line that's already
  present verbatim.
- **Template-only** (the Replace draft): exactly the composed template, without the existing file's extra
  lines. Note which of the existing lines it would drop, for Step 5.

## Step 5 — If `.gitignore` already existed, get approval before writing

Skip this step entirely if Step 1 found no existing file — proceed straight to Step 6's write.

- Show the user the merged draft as a diff against the current file (writing the draft to a temp path and
  running `git diff --no-index <current> <draft>` gives a clean unified diff). If the merged draft is identical
  to the current file, say so, treat the outcome as "kept as is", and skip the question.
- If `on-existing` is `update` or `replace`, apply it without asking (still show the diff of what is being
  written). Otherwise ask via `AskUserQuestion` (header `.gitignore`), with these consequences in the option
  descriptions:
  - **Update (Recommended)** — "Write the merged draft: add the missing entries from this skill's template and
    keep every line you added by hand. You've seen the diff; nothing you customized is dropped."
  - **Keep as is** — "Leave `.gitignore` untouched. Your entries stay exactly as they are, but build/coverage/
    tooling output the template would ignore (listed in the diff) may show up as untracked files."
  - **Replace** — "Write the template-only draft. Matches this house template exactly, but the hand-added lines
    listed below are lost (recoverable only from git history)." — list those lines in the question text.
  There is no partial-apply option — if the user wants only some of the proposed lines, let them say so in
  free text and revise the draft before writing.
- Only proceed to Step 6's write on **Update** or **Replace**; on **Keep as is**, write nothing.

## Step 6 — Write `.gitignore` and report

Write the chosen draft (Step 4's merged or template-only draft per Step 5's decision, incorporating any edits from
Step 5's review) to `.gitignore` at the repository root — or nothing, if it was kept as is. Then summarize: whether
this run was from scratch or an update, the action taken (**created / updated / replaced / kept as is**, and
whether that came from the user's answer or from `on-existing`), which conditional sections from Step 3 were
included versus skipped and why (e.g. "no Playwright section — no `playwright.config.ts` or `@playwright/test`
found", "no Hilla section — no `src/main/frontend/` and no Hilla dependency in `pom.xml`"), and — if `.gitignore`
already existed — which hand-added lines were preserved (Update) or dropped (Replace).

**Warn explicitly**:

- Review the generated `.gitignore` before committing — detection is heuristic (manifest/config-file signals), not
  a guarantee that every relevant path was found.
- If the repository already had files tracked under a directory this skill now proposes to ignore (e.g. a
  deliberately committed `dist/`), `git rm -r --cached` that directory manually after reviewing the diff —
  writing `.gitignore` alone does not untrack already-committed files.
- Which values were inferred versus verified: the Vaadin Hilla block (Step 3) was checked against Vaadin's current
  published source-control documentation, and the NestJS and Next.js blocks against the `.gitignore` files that
  `nest new` (`@nestjs/cli` 12) and `create-next-app` (Next.js 16) generate, as part of authoring this skill; the
  Prisma path is read from the project's own schema; the React Native Metro block was compared against the
  `.gitignore` the `@react-native-community/cli` 20.2.0 template writes and the untracked output of a real native
  build (October 2026); every other Step 3 section is inferred
  purely from local manifest/config-file signals in the target repository and should be spot-checked against the
  project's actual generated output once its scaffold exists.
