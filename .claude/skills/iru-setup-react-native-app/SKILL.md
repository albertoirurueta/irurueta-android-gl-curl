---
name: iru-setup-react-native-app
description: "Invoked as /iru-setup-react-native-app with optional key: value args (toolchain: expo|metro), usually by iru-setup-typescript-repository. Scaffolds a React Native app on Expo or the bare CLI/Metro toolchain with Jest, ESLint, Prettier, Maestro UI flows, accessibility checks and optional Sonar and EAS."
model: sonnet
---

# Setup React Native App

Scaffold a new React Native app (New Architecture only, Cordova-free; for a Capacitor-based hybrid app use `iru-setup-ionic-app` instead) on one of two toolchains, then layer on this
catalog's standard test/lint/build/Sonar tooling using explicit example templates kept in this skill's
`reference/` directory and verified end-to-end while building it:

- **`expo`** — `create-expo-app`'s `blank-typescript` template, a **managed** Expo app (JS/TS + Expo config
  plugins, no committed `ios/`/`android/` folders); native projects are generated on demand via `eas build` or
  `npx expo prebuild` (Continuous Native Generation) — see the `native-builds` question in Step 2. Templates:
  `reference/expo-templates.md` (`npm install`, `npm test -- --coverage`, `npx expo lint`, `npm run typecheck`, and
  `npx expo export --platform web` all run clean against them).
- **`metro`** — the React Native Community CLI's default TypeScript template: a bare app with committed `android/`
  (Gradle) and `ios/` (Xcode + CocoaPods) projects, bundled by Metro run directly (`npx react-native start`).
  Templates: `reference/metro-templates.md` (`npm install`, `npm test -- --coverage`, lint, typecheck, Prettier,
  `./gradlew assembleDebug`, and `bundle exec pod install` all run clean against them; Step 8 records the iOS
  build outcome).

Every template is genericized (no real repo/org/person names); only the `<placeholder>` values are filled in per
project using Steps 2–4. Load **only** the reference file for the resolved toolchain.

## Step 0 — Resolve inputs

This skill can be invoked stand-alone (`/iru-setup-react-native-app`) or as a step inside another skill (e.g.
`iru-setup-typescript-repository` with `flavor: react-native`, or the catalog's `iru-setup-repository` front door),
which resolves these same inputs itself and passes them through `args` as `key: value` lines, one per line, e.g.:

```
toolchain: expo
app-name: My App
app-slug: my-app
app-directory: mobile
app-id: com.example.myapp
license: MIT License
developer-name: Jane Doe
developer-email: jane@example.com
organization-url: https://github.com/example-org
open-source: yes
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_my-app
sonar-host-url: https://sonarcloud.io
native-builds: eas
distribution: store
accessibility: enforce
visibility: public
ci: yes
on-existing: ask
mode: new
```

Parse any such lines from `args` now. Every field found here is resolved — skip asking about it in Step 2. Only
fields genuinely missing from `args` still need a question. If `args` is absent or doesn't look like this format,
treat everything as unset and ask normally.

Recognized keys: `toolchain` (`expo`/`metro`), `app-name`, `app-slug` (kebab-case; defaults to a slugified
`app-name` if omitted), `app-directory` (the subdirectory to scaffold into — see Step 1 if unset), `app-id` (the
reverse-DNS bundle identifier / Android application id, e.g. `com.example.myapp` — required for `metro`, optional
for `expo`), `license`, `developer-name`, `developer-email`, `organization-url`, `open-source` (`yes`/`no`),
`sonar` (`cloud`/`self-hosted`/`none`) plus, only when `sonar` is `cloud` or `self-hosted`,
`sonar-organization`/`sonar-project-key`/`sonar-host-url`, `native-builds` (`eas`/`local` — **expo only**; ignored
with a one-line note for `metro`), `distribution` (`none`/`internal`/`store`), `accessibility`
(`enforce`/`report`/`off`/`none` — the UI contract's automated accessibility level, written to `.ui-quality.yml`; default
`enforce`), `visibility`
(`public`/`private`/`internal` — the GitHub repository's visibility, used only to key Step 2's Sonar
recommendation), `ci` (`yes`/`no` — whether the run invoking this skill generates GitHub Actions workflows at all;
only consulted by Step 2's Sonar question), `on-existing` (`ask`/`keep`/`update`/`replace`, default `ask` — how
Step 1 treats each pre-existing tooling file; meant for scripted runs), and `mode` (`new`/`existing`).

`mode: existing` is this catalog's shared signal (set by a front door or orchestrator that already knows this is an
established repository) that this run is an **update**: Step 1 announces it as such, but still warns and asks per
pre-existing file at the resolved `app-directory` unless `on-existing` says otherwise — `mode: existing` alone never
means "update silently". `mode: new` or an unset `mode` follows the same survey.

**Accessibility level `none` and assistive-technology templates.** `accessibility` takes `enforce`, `report`, `off` or
`none` (UI contract §8; the stand-alone question gains a fourth option, **None — opt out entirely**, e.g. a mandated
non-compliant design). With `none`: write `accessibility: none` to `.ui-quality.yml`, do not install or configure the
accessibility test tooling (`eslint-plugin-react-native-a11y` and the role-query accessibility assertions), and omit the accessibility-specific attributes from the generated templates.
With `enforce`, `report` or `off`: include the assistive-technology attributes from the contract's §8 platform
subsections in every generated template — `accessibilityLabel`/`accessibilityRole` on touchables and icon buttons, `allowFontScaling`-safe text, reduced-motion checks (`AccessibilityInfo`) — and, for `enforce`/`report` only, the automated checks. In
`mode: existing`, a prior `.ui-quality.yml` follows this skill's existing Update / Keep / Replace prompt and its
`accessibility` value is never silently flipped (**Update** sets it only when `args` supplied it).

## Step 1 — Survey the repository

First resolve **where** to scaffold, unless `app-directory` was already supplied via `args`:

- If the repository root has no files other than dotfiles/directories (`.git`, `.github`, `.gitignore` alone —
  nothing else), it's safe to scaffold directly into the root: offer `.` as the default `app-directory`.
- Otherwise — the common case, since most repositories already carry at least a `README.md` or `LICENSE` — both
  scaffolders refuse to write into a non-empty directory. Confirmed: `create-expo-app` exits with `"The directory
  <dir> has files that might be overwritten"` and makes **no changes** the moment it finds even one pre-existing
  file; the React Native CLI warns `The directory <dir> contains files that will be overwritten`, asks `Do you
  want to replace existing files? (y/N)`, and — answered No, which is also what a non-interactive shell resolves
  to — exits leaving the files untouched (never answer Yes: it overwrites them). So this is safe to attempt but
  never silently succeeds. Default `app-directory` to the resolved `app-slug` (see Step 2) and confirm it with the
  user rather than assuming silently.

Then check, at that `app-directory`, whether `package.json` already exists, and detect its toolchain with the
catalog-wide rule: `expo` in `dependencies` → **expo** (even when `android/`/`ios/` exist — that is
prebuild/Continuous Native Generation output); a `react-native` dependency without `expo`, plus `metro.config.js`,
committed `android/` + `ios/` projects, or `@react-native-community/cli` in `devDependencies` → **metro**.

- **Doesn't exist**: say "Scaffolding the <Expo | bare React Native (Metro)> app **from scratch** at
  `<app-directory>`." (the toolchain is resolved in Step 2 if `args` didn't supply it) and skip straight to Step 2.
  There is nothing to preserve.
- **Exists and is a React Native app (expo or metro)**: this skill owns this scaffold's tooling. If the `toolchain`
  arg was supplied and disagrees with the detected one, warn — naming the evidence (e.g. "`args` says `metro` but
  `package.json` depends on `expo` — the `android/`/`ios/` folders here are prebuild output") — and ask with
  `AskUserQuestion`: **Use the detected toolchain (Recommended)** / **Stop** (this skill never converts an app
  between toolchains — migrating Expo ↔ bare is a project of its own). Without a `toolchain` arg, adopt the
  detected one and say so. Then say "**Updating an existing <Expo | Metro> app** at `<app-directory>` — found:
  <list of this skill's files present>." The scaffolder (Step 6) is never re-run, and `App.tsx`/`app.json`/
  `assets/`/`index.*`/the native projects' sources/any other application source are never touched. Survey the
  resolved toolchain's tooling files — expo: the `package.json` scripts/devDependencies merge, `jest.config.js`,
  the `tsconfig.json` `types` merge, `__tests__/App.test.tsx`, `eslint.config.js`, `.prettierrc`/`.prettierignore`,
  `eas.json`, `.maestro/smoke.yaml`, `sonar-project.properties`; metro: the `package.json` scripts/devDependencies
  merge, `jest.config.js`, `jest.setup.js`, `__tests__/App.test.tsx`, `.eslintrc.js` (or `eslint.config.js`),
  `.prettierignore`, `.maestro/smoke.yaml`, `sonar-project.properties`; both: the UI contract files —
  `.ui-quality.yml` at the **repository root**, `src/dev/ui-gallery/**` (the gallery and its test),
  `.maestro/config.yaml`, `.maestro/subflows/ui-capture.yaml`, `.maestro/ui/App.yaml` — and resolve one action per
  file that already exists:
  - **`on-existing` is `keep`/`update`/`replace`** (from Step 0): apply it to every existing file without asking
    (still print the warning).
  - **Otherwise**: ask with `AskUserQuestion`, batching up to 4 files per call (one question each), naming any
    concrete contradiction with this run's choices (e.g. "your `eas.json` has a `submit` block but you chose
    `distribution: internal`", "`tsconfig.json` lacks a `types` entry for Jest", "`jest.config.js` has no `lcov`
    reporter"):
    - **Update (Recommended)** — merge: add what's missing and correct outdated parts using the reference
      template (missing scripts/devDependencies, the `types: ["jest"]` line, the `coverage/*` ESLint ignore,
      `eas.json` profiles and the `submit` block per `distribution`, the metro coverage keys/ignore patterns),
      keeping everything this skill doesn't own (e.g. an older metro project's `preset: 'react-native'`). The user
      reviews a diff; customizations survive, but a customized part this skill owns may be rewritten.
    - **Keep as is** — leave the file untouched. Nothing customized changes, but it may stay out of date or
      disagree with this run's choices — Step 8 lists each mismatch.
    - **Replace** — regenerate from the reference template; every customization in the file is lost
      (recoverable only from git history). For `__tests__/App.test.tsx` recommend **Keep** instead — once the app
      has real tests, Replace would overwrite them with the placeholder test — and likewise for
      `src/dev/ui-gallery/registry.tsx` and `.maestro/ui/*.yaml` once they list the app's real components. For
      `.ui-quality.yml`, name any contradiction with this run's `accessibility` value (e.g. "`.ui-quality.yml` says
      `report` but you chose `enforce`"); Update rewrites only the `accessibility` line and keeps narrowed
      `directions`/`themes`.
    - **Stop** (first batch only) — leave `app-directory` untouched entirely; report this and end here.
  - Defer the `sonar-project.properties` and `eas.json` questions until Step 2 resolves `sonar`/`distribution`, so
    they can name any contradiction; with `sonar: none`, Update/Replace of `sonar-project.properties` mean
    removing it and Keep leaves it as a reported mismatch.
  - Pre-fill Step 2's defaults from the existing project — expo: `app.json`/`package.json` (name, slug, license,
    author, `expo.ios.bundleIdentifier`/`expo.android.package` as `app-id`, `expo.scheme` as the gallery's deep-link
    scheme); `accessibility` from an existing `.ui-quality.yml`; metro: `app.json` `displayName`,
    `package.json`, `android/app/build.gradle`'s `applicationId` and `PRODUCT_BUNDLE_IDENTIFIER` in
    `ios/<AppName>.xcodeproj/project.pbxproj` as `app-id` (if the two differ, report it — don't rewrite either).
- **Exists but has no `react-native` dependency at all** (an unrelated Node project already living at that path):
  warn the user explicitly and ask whether to scaffold into a different subdirectory instead, or stop. Don't attempt
  to convert or merge into a non-React-Native project (this hard stop applies regardless of `on-existing` — no
  Update/Keep/Replace is offered here).

## Step 2 — Collect the required inputs

For any field Step 0 already resolved from `args`, use that value directly and don't ask about it again. For
everything else, ask the user directly (plain conversation, pre-filling defaults found in Step 1 if updating an
existing scaffold):

When an existing `package.json` (or app config such as `app.json`) already supplies an identity value Step 0
didn't — typical in `mode: existing`, where orchestrators omit identity keys — use it as resolved and confirm it in
one line instead of asking.

**toolchain** first (unless resolved by `args` or detected in Step 1), with `AskUserQuestion`:

- **Expo (Recommended for most new apps)** — the Expo framework: EAS cloud builds/submissions and updates,
  Continuous Native Generation (no native folders to maintain — `npx expo prebuild` regenerates them from
  `app.json` + config plugins), Expo Router file-based routing, the Expo SDK modules, and a web export. Native
  customization goes through config plugins or the Expo Modules API; local builds need no Xcode/Android Studio
  when EAS builds in the cloud.
- **Metro — bare React Native CLI** — `@react-native-community/cli`'s template with the `android/` and `ios/`
  native projects committed and owned by the team: full control of Gradle, Xcode build settings, `AppDelegate`/
  `MainApplication` and any native SDK, at the cost of maintaining them across React Native upgrades (the Upgrade
  Helper diff) and needing **CocoaPods (Ruby + Bundler), Xcode, and Android Studio/the Android SDK + a JDK locally**
  to build. Navigation is React Navigation (no Expo Router); builds and store uploads run through
  Gradle/`xcodebuild` in CI, not EAS.

Say plainly that **Expo also uses Metro** as its bundler — the choice is between the Expo framework/toolchain and
driving the bare React Native CLI + Metro yourself, not between two bundlers.

Then:

- **app-name** — the display name, e.g. `My App` (expo: `app.json`'s `expo.name`; metro: `app.json`'s
  `displayName`, Android `app_name`, iOS `CFBundleDisplayName`).
- **app-slug** — kebab-case, e.g. `my-app` (`package.json`'s `name`; expo also `app.json`'s `expo.slug`; offer a
  slugified `app-name` as the default rather than asking open-endedly).
- **`<AppName>`** (metro only, derived — don't ask unless it's ambiguous) — the PascalCase project name the CLI
  needs (`init <AppName>`): letters and digits only, starting with a letter, e.g. `MyApp` from `My App`. It names
  `app.json`'s `name` (the registered JS module), the Xcode project/workspace/scheme `ios/<AppName>.xcworkspace` /
  `-scheme <AppName>`, and the Gradle root project. Show it in the confirmation line.
- **app-id** — reverse-DNS bundle identifier / application id, e.g. `com.example.myapp` (lowercase segments, at
  least two, each starting with a letter; no hyphens, since it doubles as the Android package/Kotlin namespace).
  **Required up front for `metro`** — the CLI bakes it into `applicationId`/`namespace`, the Kotlin package
  directories, and `PRODUCT_BUNDLE_IDENTIFIER`, and changing it afterwards is a package rename. Suggest
  `<reversed organization-url host>.<app-slug without hyphens>` (e.g. `com.example.myapp` from
  `https://example.com` + `my-app`). For `expo` it's optional: ask once ("set the bundle id / package now?"); when
  given, Step 6 writes it to `app.json` as `expo.ios.bundleIdentifier`/`expo.android.package` and the Maestro flow;
  otherwise both stay unset as in the scaffold.
- **Developer name**, **developer email**, **organizationUrl**.

Then, unless Step 0 already resolved a `license` value from `args`, ask about the **license** with
`AskUserQuestion` (mirrors this catalog's other setup skills):

- MIT License (recommended for app source not meant for redistribution as a library)
- Apache License 2.0
- No license (proprietary / all rights reserved)
- Other — ask for the license's display name directly afterward

If a license is chosen (anything but "No license"), remind the user in Step 8 to also add a matching `LICENSE`
file at the repository root. `create-expo-app`'s own template ships its **own** `LICENSE` file (Expo's MIT
license for the template code itself, not the project), which Step 6 replaces/removes per the chosen license
here; the React Native CLI template ships none (confirmed), so for metro Step 6 writes one only when the
`app-directory` is `.` and the repository root has none (otherwise the root `LICENSE` covers it). The
`iru-check-license` skill can verify/backfill source headers against it once a real one is in place.

Then resolve `open-source`, `sonar`, `native-builds` (expo only), and `distribution` — skip any Step 0 already
resolved from `args`. When invoked stand-alone with none of them pre-resolved, ask `open-source` *first*
(`AskUserQuestion`: yes/no) and derive the recommended default for `sonar` from that answer, per this catalog's
shared convention:

- **open-source** — is this repository open source? Yes / No.
- **sonar** — whether to wire up a SonarQube/SonarCloud scan (`sonar-project.properties`, typically consumed by a
  CI workflow such as `iru-setup-typescript-github-workflows`, so it's worth setting up here even if that workflow
  comes later). The recommendation keys on both `open-source` and `visibility`. If `visibility` wasn't supplied via
  `args`, resolve it only now (only when this question is actually asked): `gh repo view --json visibility -q
  .visibility` when the repository has a GitHub remote (lowercase the result and tell the user), otherwise ask
  **Public** / **Private** (Public recommended when open source); `internal` counts as private. If `open-source:
  yes` but `visibility` is `private`/`internal`, warn about the contradiction and ask which is right. Recommend
  **SonarCloud** (`cloud`) only when the repository is public **and** open source; otherwise state that SonarCloud
  is free only for public open-source projects (per its current pricing — a private project needs a paid plan) and
  recommend **None** (`none`), offering **self-hosted SonarQube** as the second option. If `ci: no` was supplied via
  `args`, say plainly that no CI workflow would send reports — analysis would only run when someone runs the
  scanner manually with a token — and recommend **None**; if the user still picks `cloud`/`self-hosted`, write
  `sonar-project.properties` anyway and say so in Step 8:
  - `cloud` — ask for the `sonar.organization` key, offering `<owner>-github` (the owner parsed in Step 3) as the
    suggested default. Default `sonar.projectKey` to `<owner>_<repo>` (also from Step 3) and `sonar.host.url` to
    `https://sonarcloud.io`, confirming both with the user rather than assuming silently.
  - `self-hosted` — ask for `sonar.host.url` directly (no sensible default), plus `sonar.organization` only if
    that server has organizations enabled, and `sonar.projectKey`.
  - `none` — skip `sonar-project.properties` entirely (Step 5/6).
- **native-builds** (**expo only** — never asked for `metro`, whose native projects are always built locally/in CI
  with Gradle and `xcodebuild`; a `native-builds` value passed in `args` for metro is ignored with a one-line
  note) — how should this app's native builds run? Present as a bounded choice:
  - **`eas`** (recommended default) — build in Expo's own cloud via `eas build --platform <ios|android|all>
    --profile <profile>`. State explicitly: EAS's free plan currently includes **15 builds per platform per
    month**; once that's exceeded mid-month, either upgrade to a paid EAS plan or fall back to local builds for
    the rest of the period. No local native toolchain is required to trigger a cloud build.
  - **`local`** — skip Expo's build cloud (and its monthly cap) entirely, at the cost of needing a full native
    toolchain wherever builds run: either `eas build --local` (still driven by `eas-cli` and this skill's
    `eas.json` profiles, but compiles on the invoking machine/CI runner — Xcode + a valid Apple developer identity
    for iOS, the Android SDK/NDK + a JDK for Android), or `npx expo prebuild` to materialize the native `ios/`/
    `android/` projects once and build them directly with `xcodebuild`/`./gradlew` from there (note: `expo
    prebuild` output is meant to be regenerated, not hand-edited, whenever `app.json`/config plugins change — hand
    edits to a prebuilt `ios/`/`android/` folder are lost on the next `prebuild` run).
  - This answer doesn't change anything this skill writes to disk beyond `eas.json`'s `cli` block — it's recorded
    here so `iru-setup-typescript-github-workflows` can wire the matching CI job later without asking again.
- **accessibility** (stand-alone runs only, when `args` didn't supply it; `AskUserQuestion`) — "Support accessibility
  and assistive technologies?" **Enforce (Recommended)** — `eslint-plugin-react-native-a11y` errors fail lint
  (and the group skills' UI stage); **Report only** — reported as warnings, never failing; **Off** — the plugin's rules
  are off (the accessible-by-default coding rules still apply). Say that React Native has no automated native audit
  (contrast, touch-target size): the check is the lint plugin plus the tests' role/label queries. Written to
  `.ui-quality.yml`.
- **distribution** — `none`/`internal`/`store`:
  - `none` — builds are for local install/manual QA only. Expo: `eas.json`'s `submit` block is omitted; metro:
    release builds stay signed with the template's debug keystore / unsigned for iOS. No store credentials are
    ever asked for or referenced by this skill.
  - `internal` — builds are shared without production submission. Expo: an EAS internal-distribution install link,
    or added manually to TestFlight/Play internal testing; `eas.json`'s `submit` block is still omitted — that path
    doesn't call `eas submit`. Metro: the workflows skill uploads to Google Play's internal testing track and
    TestFlight.
  - `store` — production submission. Expo: `eas.json` gains a `submit.production` block, and Step 8's report flags
    the credentials (`EXPO_TOKEN`/`APP_STORE_CONNECT_API_KEY_ID`+`_ISSUER_ID`+`_KEY_BASE64`/
    `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`) that `iru-setup-typescript-github-workflows`'s release workflow will need.
    Metro: Play production track + TestFlight as the staging point for App Store review.
  - Metro has **no EAS**, so for `internal`/`store` Step 8 names the secrets the workflows skill's Gradle/
    `xcodebuild` release jobs need instead: the Android upload keystore (`ANDROID_KEYSTORE_BASE64`/
    `ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD`), `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, and
    the App Store Connect API key (`APP_STORE_CONNECT_API_KEY_ID`/`_ISSUER_ID`/`_KEY_BASE64`, which drives
    automatic cloud-managed signing) — plus a `.p12` signing certificate/password and provisioning profile only
    for a team that manages signing manually. This skill itself never collects or stores any of these
    credentials, only names them (details: `reference/metro-templates.md`, "Native build and signing notes").

## Step 3 — Infer repository information

Don't ask for these — derive them from the current repository, the same way `iru-setup-java-library` and
`iru-setup-typescript-library` do:

- **Owner/repo and host**: parse `git remote get-url origin` (handles both `git@host:owner/repo.git` and
  `https://host/owner/repo.git` forms). Used only for Step 2's `sonar.organization`/`sonar.projectKey` defaults.
  If there's no `origin` remote yet, ask the user directly instead of leaving these blank.
- **Initial-screen text**: read the scaffold right after Step 6 runs, to fill the Maestro skeleton's
  `assertVisible` with whatever the placeholder screen actually renders — expo: `App.tsx`'s text (`"Open up
  App.tsx to start working on your app!"` in the `blank-typescript` template as of Expo SDK 57); metro: the
  heading of `@react-native/new-app-screen`'s `NewAppScreen` (`"Welcome to React Native"` in
  `node_modules/@react-native/new-app-screen/src/NewAppScreen.js` as of React Native 0.87). Confirm against the
  actual file rather than assuming these exact strings, since templates change.

## Step 4 — Look up dependency versions

Every devDependency version this skill installs beyond what the scaffolder itself resolves must be looked up at
run time with `npm view <package> version` (or, if that's unavailable — offline, registry unreachable — fall back
to the version recorded in the table below, which is what this skill's author actually observed on the npm
registry while building and verifying this skill). Note in Step 8's report whenever a fallback was used instead of
a live lookup.

| Package | Toolchain | npm view command | October 2026 fallback (September 2026 value) |
|---|---|---|---|
| `create-expo-app` | expo | `npm view create-expo-app version` | `5.0.0` (always run as `@latest`; scaffolds `expo@~57.0.29`, `react-native@0.86.3`) |
| `@react-native-community/cli` | metro | `npm view @react-native-community/cli version` | `20.2.0` (always run as `@latest`; scaffolds `react-native@0.87.1`, `react@19.2.3`) |
| `jest-expo` | expo | `npm view jest-expo version` | `57.0.6` (`57.0.5`) — keep in lockstep with the scaffolded `expo` SDK's major |
| `jest` | expo | see the caveat below — **do not** just take `npm view jest version` | `29.7.0` |
| `@types/jest` | expo | `npm view @types/jest version` | `30.0.0` |
| `@testing-library/react-native` | both | `npm view @testing-library/react-native version` | `14.1.0` (`14.0.1`) |
| `eslint` | expo | see the caveat below — **do not** just take `npm view eslint version` | `9.39.5` |
| `eslint-config-expo` | expo | `npm view eslint-config-expo version` | `57.0.2` — keep in lockstep with the scaffolded `expo` SDK's major |
| `prettier` | expo | `npm view prettier version` | `3.9.10` (`3.9.7`) |
| `eas-cli` | expo | `npm view eas-cli version` | `24.14.0` (`24.6.0`) — never installed as a dependency, always invoked via `npx eas-cli@latest`; only for `eas.json`'s `cli.version` constraint |
| `eslint-plugin-react-native-a11y` | both | `npm view eslint-plugin-react-native-a11y version` | `3.5.1` (unchanged since November 2024; peer range `eslint` ≤ 8, hence expo's `overrides` entry) |

**Metro installs only `@testing-library/react-native` and `eslint-plugin-react-native-a11y`.** Everything else (`jest`, `@types/jest`, `eslint`,
`prettier`, `typescript`, the `@react-native/*` presets/configs) stays at the versions the CLI template pins —
confirmed in October 2026: `eslint ^8.19.0`, `prettier 2.8.8`, `jest ^29.6.3`, `@types/jest ^29.5.13`,
`typescript ^6.0.3`. Upgrading ESLint to 9/10 or Prettier to 3 there is a migration (eslintrc → flat config,
reformat) for the project to do deliberately, not part of this scaffold.

**`jest` version caveat (expo, discovered verifying this skill in October 2026):** `npm view jest version` had moved
to the `30.x` line, but `jest-expo@57` depends on `babel-jest ^29` — install the Jest major that matches: read
`npm view jest-expo@<version> dependencies.babel-jest` and install the latest `jest` of that major (`npm view
jest@29 version`), which is the combination verified end-to-end.

**`eslint` version caveat (expo, discovered verifying this skill in September 2026, still true in October):**
`npm view eslint version` resolves the bare `latest` dist-tag, which had already moved to the `10.x` line — but
`eslint-config-expo`'s own installer (invoked by `npx expo lint`'s auto-configure path, see Step 7) explicitly
requests `eslint@^9.0.0`, not `latest`, and that's the combination actually verified end-to-end while building
this skill. Resolve the `eslint` version to install like this instead of trusting the bare `latest` tag:

1. `npm view eslint-config-expo peerDependencies.eslint` — read off the range it declares (`>=8.10` at
   verification time, which is wide enough to permit `10.x` too, but is *not* what Expo's own tooling targets).
2. `npm view eslint dist-tags --json` — prefer the `maintenance` dist-tag's version over `latest` when one exists
   and satisfies step 1's range; that's the line `eslint-config-expo`/`expo lint` is actually built and tested
   against (`9.39.5` in both verification runs).
3. If the resolved version differs from the bare `latest` tag, say so explicitly in Step 8's report.

## Step 5 — Reference templates

Read exactly one toolchain file now, plus `reference/ui-templates.md` for both toolchains:

| Toolchain | File | Contents |
|---|---|---|
| `expo` | `reference/expo-templates.md` | `package.json` scripts/devDependencies merge, `jest.config.js`, the `tsconfig.json` `types` merge, `__tests__/App.test.tsx`, the `npx expo lint`-generated `eslint.config.js`, `.prettierrc`, `.prettierignore`, `eas.json` (+ the version-ownership rule for EAS), `.maestro/smoke.yaml`, `sonar-project.properties` |
| `metro` | `reference/metro-templates.md` | the exact file list the CLI template ships, the identity fix-ups, `package.json` merge, `jest.config.js`, `jest.setup.js`, `__tests__/App.test.tsx`, `.eslintrc.js` ignores, `.prettierignore`, `.maestro/smoke.yaml`, `sonar-project.properties` (and why native sources are excluded), native build and signing notes |
| both | `reference/ui-templates.md` | `.ui-quality.yml` (repository root), the dev-only UI gallery `src/dev/ui-gallery/{index.ts,registry.tsx,UiGalleryHost.tsx,__tests__/UiGalleryHost.test.tsx}`, `.maestro/config.yaml`, the screenshot helper `.maestro/subflows/ui-capture.yaml`, the sample capture flow `.maestro/ui/App.yaml` |

Only substitute `<placeholder>` values using Steps 2–4; every other line, script, and option is copied as shown.

## Step 6 — Scaffold the project

Skip the scaffold command entirely if Step 1 found an already-existing React Native app — go straight to writing
the tooling files per Step 1's per-file decisions (the last bullet of the matching toolchain below) and then
Step 7.

### `toolchain: expo`

From the repository root:

```bash
npx create-expo-app@latest <app-directory> --template blank-typescript --no-install --no-agents-md
```

Confirmed non-interactive end-to-end with exactly these three flags:

- `--template blank-typescript` selects the TypeScript blank template (not the default template, which pulls in
  Expo Router and extra example screens this skill doesn't need).
- `--no-install` skips the automatic `npm install` so Step 7 can add this skill's own `devDependencies` in the
  same install pass instead of a second one.
- `--no-agents-md` skips the template's own generated `AGENTS.md`/`CLAUDE.md`/`.claude/settings.json` — this
  catalog already has its own `CLAUDE.md`/skill conventions for the consuming repository, so the template's
  generic Expo-version-reminder files would only add noise (and a stray, unrelated `CLAUDE.md` at the scaffold
  root).
- `<app-directory>` must point at an empty (or nonexistent) path — confirmed `create-expo-app` refuses outright
  and makes **no changes** the instant it finds even one pre-existing file there (see Step 1). Never pass `.`
  unless Step 1 confirmed the repository root is genuinely empty of non-dotfiles.
- Confirmed: when run inside a directory that's already part of a git repository (the normal case for this
  catalog — scaffolding into a subdirectory of an existing repo), `create-expo-app` detects the enclosing `.git`
  and does **not** initialize a nested one. As of `create-expo-app` 5.0.0 it prints a trailing `You are creating
  a project inside of an existing Git repository. Skip initializing a new git repository? (Y/n)` prompt after the
  files are written; in a non-interactive shell (how this skill runs it) the prompt resolves on its own without
  blocking and without creating a nested `.git` — `--yes` doesn't suppress it. In an interactive terminal answer
  **Y**. It only runs its own `git init` when there is no enclosing git repository at all — if that happens,
  remove the stray nested `.git` (`rm -rf <app-directory>/.git`) before the surrounding repository's own history
  is affected; check with `git status` right after scaffolding either way.

Then, at `<app-directory>`:

- Replace `LICENSE` with the project's chosen license (Step 2) — or delete it if "No license" was chosen. The
  scaffold's own `LICENSE` is Expo's MIT license for the *template*, not a license for this project's code, and
  leaving it in place would misrepresent the project's actual licensing.
- Set `app.json`'s `expo.name` to `<app-name>` and `expo.slug` to `<app-slug>` (Step 2) — `create-expo-app`
  defaults both to the scaffold directory's own name, which is usually not the same as the app name/slug the user
  gave. When Step 2 resolved `app-id`, also set `expo.ios.bundleIdentifier` and `expo.android.package` to it.
- Merge the `package.json` scripts/devDependencies fragment into the scaffolded `package.json` (don't replace the
  file — it already has correct `dependencies`/`main` from the scaffold), and set its `version` to `0.1.0-dev.0`
  (new scaffolds only — never rewrite an existing project's version; that is `iru-react-native-bump-version`'s job).
- Write `jest.config.js`, merge the `tsconfig.json` fragment, create `__tests__/App.test.tsx`, write
  `.prettierrc`/`.prettierignore`, write `eas.json` (gated per Step 2's `distribution` answer), create
  `.maestro/smoke.yaml`, and write `sonar-project.properties` (only when `sonar` ≠ `none`) — all from
  `reference/expo-templates.md`. For a file that already existed, apply Step 1's decision: **Update** merges and
  shows the diff, **Keep as is** leaves it untouched, **Replace** overwrites it (with `sonar: none`,
  Update/Replace remove an existing `sonar-project.properties`).
- UI contract wiring: write `.ui-quality.yml` at the repository root (with `<accessibility>`), the
  `src/dev/ui-gallery/**` files and the three Maestro files from `reference/ui-templates.md`; on a new scaffold also
  replace `index.ts` with the gallery-wrapping registration and add `expo.scheme: "<app-slug>"` to `app.json` (both
  in `reference/expo-templates.md`). On an existing app, `index.ts` and `app.json` are application source/config this
  skill doesn't own: print the two changes for the user and report "UI gallery not wired into the entry" until
  they're applied.

### `toolchain: metro`

From the repository root (pick the flags from `npx @react-native-community/cli@latest init --help` if a future CLI
renames them — these are the October 2026 / CLI 20.2.0 names):

```bash
npx @react-native-community/cli@latest init <AppName> --directory <app-directory> --package-name <app-id> \
  --skip-install --install-pods false --skip-git-init --pm npm
```

Confirmed non-interactive end-to-end (about 5 seconds, no prompt) with exactly these flags:

- `<AppName>` is the PascalCase project name from Step 2 (the CLI rejects names with spaces/hyphens); it names
  the JS module, the Xcode project/scheme and the Gradle project. `--directory <app-directory>` writes the project
  there instead of `./<AppName>` — the same empty-or-nonexistent rule as Expo applies (see Step 1; never answer Yes
  to the "replace existing files?" prompt).
- `--package-name <app-id>` sets `applicationId`/`namespace` (with the Kotlin sources under
  `android/app/src/main/java/<app-id as path>/`) and `PRODUCT_BUNDLE_IDENTIFIER` for Debug and Release — but also
  overwrites `package.json`'s `name` and `android/settings.gradle`'s `rootProject.name` with the id; the identity
  fix-ups below undo that.
- `--skip-install` defers `npm install` to Step 7 (one install pass with this skill's devDependency);
  `--install-pods false` defers CocoaPods to Step 7 too (it needs `node_modules` and a Bundler-managed CocoaPods);
  `--skip-git-init` keeps the CLI from running `git init` (confirmed: no nested `.git` is created);
  `--pm npm` pins the package manager (`yarn`/`bun` also accepted — use the repository's own if it already has a
  lockfile at the root).
- `--title` is deliberately **not** used: confirmed it changes no display name in the TypeScript template.
- The scaffold writes its own `README.md` and `.gitignore` inside `<app-directory>` — leave them; the root
  `.gitignore` is `iru-setup-typescript-gitignore`'s job and the root `README.md` is `iru-setup-readme`'s.

Then, at `<app-directory>`, following `reference/metro-templates.md`:

- Apply the **identity fix-ups**: `package.json` `name` → `<app-slug>`, `android/settings.gradle`
  `rootProject.name` → `'<AppName>'`, display name → `<app-name>` in `app.json` `displayName`,
  `android/app/src/main/res/values/strings.xml` `app_name`, and `ios/<AppName>/Info.plist` `CFBundleDisplayName`.
  Never change `app.json`'s `name`.
- License: the CLI template ships no `LICENSE`; when `<app-directory>` is `.`, write the chosen license as the root
  `LICENSE` (or nothing for "No license"); otherwise rely on the repository-root `LICENSE` (Step 8 reminds).
- Merge the `package.json` scripts/devDependency fragment (keeping the template's own version pins) and set
  `version` to `0.1.0-dev.0` (new scaffolds only), write
  `jest.config.js` and `jest.setup.js`, replace `__tests__/App.test.tsx`, add the `ignorePatterns` line to
  `.eslintrc.js`, write `.prettierignore` (keep `.prettierrc.js` as shipped), create `.maestro/smoke.yaml`, and
  write `sonar-project.properties` (only when `sonar` ≠ `none`). For a file that already existed, apply Step 1's
  decision exactly as for Expo.
- UI contract wiring: the same shared files as expo from `reference/ui-templates.md`, then on a new scaffold the
  gallery-wrapping `index.js` and the `<app-slug>` deep-link scheme (Android `intent-filter`, iOS
  `CFBundleURLTypes`) from `reference/metro-templates.md`; on an existing app print those three edits instead.

## Step 7 — Install dependencies and verify

### `toolchain: expo` — install and generate the ESLint config

From `<app-directory>`:

1. `npm install` — installs the scaffold's own `expo`/`react`/`react-native`/`expo-status-bar` dependencies.
2. `npm i -D jest-expo@<version> jest@<version> @types/jest@<version> @testing-library/react-native@<version>`
   (Step 4's versions). Confirmed clean, no peer-dependency conflicts, as long as `react-test-renderer` is never
   added (see `reference/expo-templates.md`'s `package.json` note).
3. `npm i -D eslint@<version> eslint-config-expo@<version>` (Step 4's versions) — **install these two before**
   running `npx expo lint`, not after. Confirmed: running `npx expo lint` on a project with neither package
   installed triggers `expo lint`'s own interactive-looking auto-configure flow, which itself installs
   `eslint@^9.0.0`+`eslint-config-expo` via a nested `npm install` — but that very first invocation then fails
   with `Error: Cannot find module 'eslint'` (the newly-installed package isn't visible to the already-running
   Node process's module resolution). Pre-installing both packages explicitly first, then running `npx expo lint`,
   completes non-interactively in one shot with no prompt and no failure — this is the verified path.
4. `npx expo lint` — generates `eslint.config.js` since none exists yet (`No ESLint config found. Configuring
   automatically.`), and reports `0` problems against the scaffold's own `App.tsx`/`index.ts`. Add `"coverage/*"`
   to the generated file's `ignores` array afterward, per `reference/expo-templates.md`.
5. `npm i -D prettier@<version> eslint-plugin-react-native-a11y@<version>` (Step 4's versions) — after the
   `overrides` entry is in `package.json` (`reference/expo-templates.md`), otherwise npm fails with `ERESOLVE`; then
   write the final `eslint.config.js` with the accessibility block.
6. `npx expo install react-dom react-native-web @expo/metro-runtime` — required for `npx expo export --platform
   web` (confirmed: without these three, that command fails outright with `CommandError: It looks like you're
   trying to use web support but don't have the required dependencies installed`, naming exactly these packages).
   `expo install` (not `npm install`) is used deliberately — it resolves each package to the version actually
   compatible with the scaffolded Expo SDK, rather than each package's own independent `latest` tag. This
   `--platform web` export is the fast, native-toolchain-free build-sanity check this catalog's
   `iru-setup-typescript-github-workflows` CI uses for this toolchain instead of a full native build.
7. Verify: `npm test -- --coverage && npm run lint && npm run typecheck && npx expo export --platform web` (then
   `grep -rl ui-gallery-ready dist` must print nothing — the gallery is not in the production bundle), and
   `npm run format` (expect only the scaffold's own un-reformatted files — offer a one-off `npx prettier --write .`).

### `toolchain: metro` — install, then build both native projects

From `<app-directory>`:

1. `npm install`, then `npm i -D @testing-library/react-native@<version> eslint-plugin-react-native-a11y@<version>`
   (Step 4) and `npm uninstall react-test-renderer @types/react-test-renderer` (new scaffolds only — see the
   reference file). Then `npx prettier --write src/dev index.js .eslintrc.js` once (`reference/ui-templates.md`).
2. Verify the JS side: `npm test -- --coverage && npm run lint && npm run typecheck && npm run format` (`format`
   is `prettier --check "**/*.{js,jsx,ts,tsx}"` on metro) — all four pass on the fresh scaffold with the reference
   templates (if `format` flags a file this skill wrote, run
   `npx prettier --write <file>` once; the reference blocks are already formatted).
3. **Android** — `cd android && ./gradlew assembleDebug`, with `ANDROID_HOME`/`ANDROID_SDK_ROOT` exported (e.g.
   `$HOME/Library/Android/sdk` on macOS) and a JDK 17+ on `PATH`. If no Android SDK is found, skip and mark the
   step **unverified locally** in Step 8 — never fake it. A cold first build downloads Gradle and the React
   Native/Hermes artifacts and took about 19 minutes during verification — run it in the background and report
   progress rather than timing out.
4. **iOS** (macOS with Xcode only; otherwise **unverified locally**, never a blocker) — with a UTF-8 locale and a
   Ruby 3.x that has Bundler (e.g. an rbenv/asdf Ruby; the template's `.bundle/config` installs gems into
   `vendor/bundle`):
   ```bash
   export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
   bundle install
   cd ios && bundle exec pod install && cd ..
   xcodebuild -workspace ios/<AppName>.xcworkspace -scheme <AppName> -configuration Debug -sdk iphonesimulator \
     -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
   ```
   `pod install` creates `ios/<AppName>.xcworkspace`, `ios/Podfile.lock` and `Gemfile.lock` — commit all three
   (the metro CI jobs hash the lockfiles). If Ruby/Bundler/CocoaPods isn't usable, stop at that sub-step, report
   it, and leave the rest for the user.
5. Neither native build needs a device; the Maestro flows (smoke and the `ui-gallery` capture flow — which do) stay unverified unless a simulator or
   emulator is already booted — and then only behind the repository-wide `.integration-test.lock`, with Metro
   (`npx react-native start`) on port 8081, as `iru-typescript-test`'s `e2e` selector does it.

## Step 8 — Report

Summarize what was generated: the resolved `toolchain`, `app-name`/`app-slug`/`app-directory` (and `<AppName>`
and `app-id` for metro, or `app-id` / "not set" for expo), the license chosen (or "none"), and the mode announced
in Step 1 — **scaffolded from scratch**, **updated an existing <Expo | Metro> app** (Step 6's scaffold skipped),
or the user chose to stop (including a toolchain mismatch they stopped on).

State explicitly what was included versus omitted, and why:

- **`toolchain`**: `expo`/`metro`, and where it came from (`args`, detected, or the user's answer).
- **`open-source`**: yes/no, as resolved in Step 2.
- **`sonar`**: `cloud`/`self-hosted`/`none` — if `cloud`/`self-hosted`, `sonar-project.properties` was written
  with the resolved `sonar.organization`/`sonar.projectKey`; if `none`, note that this is expected when the
  repository isn't public and open source (SonarCloud is free only for public open-source projects) unless the
  user explicitly chose it. State the `visibility` used and where it came from (`args`, `gh`, or the user); if
  `ci: no` was supplied and Sonar was still configured, say no CI step sends reports. For metro, add that the
  native `android/`/`ios/` sources are deliberately excluded from Sonar (the reference file explains why).
- **`native-builds`** (expo): `eas`/`local` — restate the EAS free-tier cap (15 builds/platform/month) if `eas` was
  chosen, or the native-toolchain requirement if `local` was chosen. For metro: "not applicable — native builds
  run with Gradle/`xcodebuild`".
- **`accessibility`**: `enforce`/`report`/`off`/`none`, where it came from (`args`, an existing `.ui-quality.yml`, or the
  question), the `.ui-quality.yml` action (created / updated / replaced / kept as is, with any mismatch), and that
  React Native's automated check is the `eslint-plugin-react-native-a11y` lint plus role queries — no native
  contrast/touch-target audit. Also whether the UI gallery is wired into the entry (`index.ts`/`index.js`) and the
  `<app-slug>` scheme registered, or printed for the user (existing app).
- **`distribution`**: `none`/`internal`/`store` — expo `store`: list the exact secrets (`EXPO_TOKEN`/
  `APP_STORE_CONNECT_API_KEY_ID`+`_ISSUER_ID`+`_KEY_BASE64`/`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`) that will still
  need to exist before a CI-driven `eas submit` succeeds; expo `none`/`internal`: note that `eas.json`'s `submit`
  block was deliberately omitted. Metro `internal`/`store`: list `ANDROID_KEYSTORE_BASE64`/
  `ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, and
  `APP_STORE_CONNECT_API_KEY_ID`/`_ISSUER_ID`/`_KEY_BASE64` (plus certificate/profile secrets only for manual
  signing), and say the release build type still signs with the template's debug keystore until CI injects the
  upload key; metro `none`: nothing to create.
- Which devDependency versions came from a live `npm view` lookup versus this skill's recorded fallback, and (expo)
  whether the `eslint`/`jest` version caveats (Step 4) resulted in a version older than the registry's bare
  `latest` tag — if so, say so plainly and name `eslint-config-expo`/`jest-expo` as the package that pins that
  older line. For metro, list the template-pinned versions kept (`eslint` 8, `prettier` 2.8.8, `jest` 29).
- **Metro native verification**: the outcome of `./gradlew assembleDebug`, `bundle install`, `bundle exec pod
  install`, and the simulator `xcodebuild` — each **passed**, **failed** (with the first error line), or
  **unverified locally** (and why: no Android SDK, not macOS, no usable Ruby/Bundler). This skill's own October
  2026 verification passed `assembleDebug` (Gradle 9.4.1, JDK 25), `bundle install` + `pod install` (Ruby 3.4.6,
  CocoaPods 1.15.2), and the simulator `xcodebuild` of the scaffold (Xcode 27).

Then report, per tooling file, the action actually taken — **created / updated / replaced / kept as is /
removed** — and, for every kept file, each mismatch with this run's choices (e.g. a `submit` block still present
after `distribution: internal`, a metro `jest.config.js` without the `lcov` reporter). If any existing tooling file
was replaced, explicitly list what could have been lost — any script or config option beyond what the reference
templates show — and tell the user to check `git diff` for anything they need to re-add. For metro, also list the
identity fix-ups applied (and any id mismatch found between Android and iOS on an existing project).

Finish with an explicit **Warn explicitly** block:

- **The user must review every generated file before building, committing, or submitting to a store.** Confirm
  the license choice matches an actual repository-root `LICENSE` file (or that none is intended — suggest the
  `iru-check-license` skill otherwise). Expo: `app.json`'s `ios.bundleIdentifier`/`android.package` must be set
  to real values before any build/EAS/Maestro step that needs them (left unset unless `app-id` was given), and
  `npm test -- --coverage && npx expo lint && npm run typecheck` must succeed before relying on this scaffold.
  Metro: the `app-id` is now baked into the native projects — changing it later is a package rename; the
  `release` build type is signed with the public debug keystore until a real upload key is configured.
- If `sonar: cloud` or `sonar: self-hosted` was chosen, a `SONAR_TOKEN` repository secret and an actual
  SonarCloud/SonarQube project matching `sonar.projectKey` still need to exist before a CI-driven Sonar scan will
  succeed — this skill only writes `sonar-project.properties`, it does not run a scan itself.
- If `distribution` is `store` (expo) or `internal`/`store` (metro), the App Store Connect / Google Play accounts
  and credentials named above still need to exist and be registered as repository secrets before a CI-driven
  upload will succeed — this skill never collects or stores those credentials itself.
- **Unverified locally, and never a blocker**: expo — `eas build`/`eas submit` (need an Expo account and project
  linking) and any `native-builds: local` native compile (`eas build --local`/`npx expo prebuild` +
  `xcodebuild`/`./gradlew`); both toolchains — the Maestro flow (needs the Maestro CLI plus a booted
  simulator/emulator or device, and for a metro debug build a running Metro server) and the `.maestro/ui/App.yaml`
  gallery capture flow with its `.ui-screenshots/` output (same needs, plus a debug/development build — the gallery
  is stripped from release builds), and any store upload; metro —
  whichever native step Step 7 could not run on this machine. Everything else (expo: `npm install`, `npm test --
  --coverage`, `npx expo lint`, `npm run typecheck`, `npx expo export --platform web`; metro: `npm install`, the
  four JS checks, `./gradlew assembleDebug`, `pod install`, the simulator `xcodebuild`) was run for real while
  building this skill.
- No root `.gitignore` was written by this skill — `node_modules/`, `coverage/`, and per toolchain `.expo/`,
  `dist/`, (once generated) `ios/`/`android/` for expo, or the Gradle/Xcode/CocoaPods/Bundler outputs
  (`android/app/build/`, `android/.gradle/`, `ios/Pods/`, `ios/build/`, `vendor/bundle/`, `.xcode.env.local`) for
  metro, should be ignored before the first commit; the `iru-setup-typescript-gitignore` skill covers this (the
  metro template also writes its own `<app-directory>/.gitignore`, which already covers most of them).
