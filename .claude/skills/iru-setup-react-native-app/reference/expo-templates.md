# Expo templates (`toolchain: expo`)

The tooling `iru-setup-react-native-app` layers on top of `npx create-expo-app@latest <app-directory> --template
blank-typescript` when `toolchain` is `expo`. These are genericized example templates — first verified together
end-to-end in September 2026 and re-verified in October 2026 against `create-expo-app` 5.0.0 (Expo SDK
`expo@~57.0.29`, `react-native@0.86.3`, `react@19.2.3`, Node 24.20, npm 11.19): `npm install`, `npm test --
--coverage`, `npx expo lint`, `npm run typecheck`, and `npx expo export --platform web` all run clean against them —
kept verbatim below except for the placeholders. Only substitute `<placeholder>` values using SKILL.md's Steps 2–4;
every other line, script, and option is copied as shown. "Step N" below always means a step of SKILL.md.

### `package.json` — scripts to merge in

Set `package.json`'s `version` to `0.1.0-dev.0` (the scaffold starts on the plain `1.0.0`): this catalog's npm
pre-release convention, so the first `iru-release` run has a development version to turn into a release.
`app.json`'s `expo.version` (`1.0.0`) is left alone — `iru-react-native-bump-version` only writes it from a
release value.

`create-expo-app`'s own template already writes `name`/`version`/`main`/`dependencies`/`start`/`android`/`ios`/
`web`. Merge these five additional scripts and the `devDependencies` below into that same file — don't replace it
wholesale:

```json
{
  "scripts": {
    "test": "jest",
    "coverage": "jest --coverage",
    "lint": "expo lint",
    "format": "prettier --check .",
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "@testing-library/react-native": "<testing-library-react-native-version>",
    "@types/jest": "<types-jest-version>",
    "eslint": "<eslint-version>",
    "eslint-config-expo": "<eslint-config-expo-version>",
    "jest": "<jest-version>",
    "jest-expo": "<jest-expo-version>",
    "eslint-plugin-react-native-a11y": "<eslint-plugin-react-native-a11y-version>",
    "prettier": "<prettier-version>"
  },
  "overrides": {
    "eslint-plugin-react-native-a11y": {
      "eslint": "$eslint"
    }
  }
}
```

**The `overrides` entry is required** for the accessibility lint plugin (UI contract §8):
`eslint-plugin-react-native-a11y` 3.5.1 (its latest release, November 2024) still declares `eslint` `^3 … ^8` as its
peer range, so `npm install` fails with `ERESOLVE` next to ESLint 9 — confirmed. `"$eslint"` tells npm to satisfy
that peer with the project's own `eslint` devDependency; its rules were confirmed to run unchanged under ESLint 9.39
flat config (and 10.13). Never use `--legacy-peer-deps` instead — it would hide every other peer conflict too.

**Do not add `react-test-renderer` as a dependency.** Confirmed while verifying this skill:
`@testing-library/react-native@14` no longer declares it as a dependency or peer dependency (it renders through
its own internal `test-renderer`), and installing `react-test-renderer@latest` alongside the scaffold's pinned
`react` version fails with `ERESOLVE` (`react-test-renderer@19.3.0` requires `react@^19.3.0`, but
`create-expo-app`'s `blank-typescript` template at SDK 57 pins `react@19.2.3`).

### `jest.config.js`

```js
module.exports = {
  preset: "jest-expo",
  collectCoverage: true,
  coverageReporters: ["text", "lcov"],
  collectCoverageFrom: [
    "**/*.{ts,tsx}",
    "!**/coverage/**",
    "!**/node_modules/**",
    "!**/babel.config.js",
    "!**/jest.config.js",
    "!**/.expo/**",
  ],
};
```

Confirmed: `npm test -- --coverage` (i.e. `jest --coverage`) writes `coverage/lcov.info` (from the `lcov`
reporter, used by `sonar.javascript.lcov.reportPaths` below) plus an HTML report under `coverage/lcov-report/` —
both must be excluded from ESLint's/Prettier's scans and from `.gitignore` (the `iru-setup-typescript-gitignore`
skill covers the latter).

### `tsconfig.json` — merge into the scaffold's

```json
{
  "extends": "expo/tsconfig.base",
  "compilerOptions": {
    "strict": true,
    "types": ["jest"]
  }
}
```

**The `"types": ["jest"]` line is not optional** (TypeScript 6, which the scaffold ships as `typescript ~6.0.3`,
defaults `types` to `[]`, so no `@types/*` package is picked up implicitly any more) — this is the single most
important quirk found verifying this
skill: `create-expo-app`'s scaffolded `tsconfig.json` only sets `{"extends": "expo/tsconfig.base",
"compilerOptions": {"strict": true}}`, and `expo/tsconfig.base` itself never restricts `types`. Despite
`@types/jest` being installed, `npm run typecheck` (`tsc --noEmit`) still fails on any test file with `error
TS2593: Cannot find name 'test'` / `error TS2304: Cannot find name 'expect'` until `types: ["jest"]` is added
explicitly to `compilerOptions`. Confirmed: adding this one line alone (no other change) makes `tsc --noEmit`
pass clean against the test skeleton below.

### `__tests__/App.test.tsx`

```tsx
import { render, screen } from "@testing-library/react-native";

import App from "../App";

test("renders the root screen", async () => {
  // @testing-library/react-native's `render` is async as of v14 — always await it. Confirmed: omitting the
  // `await` here makes `screen.toJSON()` throw "`render` function has not been called", because `screen` is
  // only populated once the underlying `act()` call inside `render()` resolves.
  await render(<App />);
  expect(screen.toJSON()).not.toBeNull();
});
```

Confirmed this skeleton passes with `66.66%` line coverage on the scaffold's own `App.tsx`/`index.ts` out of the
box (`App.tsx` itself at 100%; `index.ts`'s `registerRootComponent` call is the only uncovered line, which is
expected — it's entry-point wiring, not app logic).

### `eslint.config.js` — generated by `npx expo lint`, not hand-written

Don't write this file directly. Step 7 pre-installs `eslint`/`eslint-config-expo` (Step 4's versions) as
`devDependencies` and then runs `npx expo lint` once, which detects there's no ESLint config yet and generates
this file itself. The generated shape, confirmed while verifying this skill:

```js
// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require("eslint-config-expo/flat");

module.exports = defineConfig([
  expoConfig,
  {
    ignores: ["dist/*", "coverage/*"],
  }
]);
```

Then extend it with the accessibility lint block — the final file (verified: `npx expo lint` passes on the
scaffold, reports `react-native-a11y/has-valid-accessibility-role` as an error on a bad `accessibilityRole` with
`accessibility: enforce`, as a warning with `report`, and nothing with `off`):

```js
// https://docs.expo.dev/guides/using-eslint/
const fs = require("node:fs");
const path = require("node:path");
const { defineConfig } = require("eslint/config");
const expoConfig = require("eslint-config-expo/flat");
const reactNativeA11y = require("eslint-plugin-react-native-a11y");

// UI contract §8: the accessibility level lives in .ui-quality.yml at the repository root (missing file → enforce).
function accessibilitySeverity() {
  for (let dir = __dirname; ; dir = path.dirname(dir)) {
    const file = path.join(dir, ".ui-quality.yml");
    if (fs.existsSync(file)) {
      const level = /^accessibility:\s*(enforce|report|off|none)\b/m.exec(
        fs.readFileSync(file, "utf8"),
      )?.[1];
      return { report: "warn", off: "off", none: "off" }[level] ?? "error";
    }
    if (fs.existsSync(path.join(dir, ".git")) || path.dirname(dir) === dir) return "error";
  }
}
const a11ySeverity = accessibilitySeverity();

module.exports = defineConfig([
  expoConfig,
  {
    files: ["**/*.{js,jsx,ts,tsx}"],
    plugins: { "react-native-a11y": reactNativeA11y },
    // The plugin's `all` preset minus has-accessibility-hint, which demands a hint on every labelled element.
    rules: Object.fromEntries(
      Object.keys(reactNativeA11y.configs.all.rules)
        .filter((rule) => rule !== "react-native-a11y/has-accessibility-hint")
        .map((rule) => [rule, a11ySeverity]),
    ),
  },
  {
    ignores: ["dist/*", "coverage/*"],
  },
]);
```

After `npx expo lint` generates the file with only `"dist/*"` in `ignores`, add `"coverage/*"` to that array
(Jest's coverage HTML report under `coverage/lcov-report/*.html` would otherwise be linted as JS/HTML and fail).
`eslint-config-expo/flat`'s own rule set was confirmed to carry no stylistic/formatting rules (no `quotes`,
`semi`, `indent`, `comma-dangle`), so no `eslint-config-prettier` layer is needed to avoid ESLint/Prettier
fighting each other, unlike some other ESLint presets.

### `.prettierrc`

```json
{
  "semi": true,
  "singleQuote": false,
  "trailingComma": "all",
  "printWidth": 100
}
```

### `.prettierignore`

```
node_modules/
coverage/
dist/
web-build/
.expo/
android/
ios/
.ui-quality.yml
```

(`.ui-quality.yml` only matters when `<app-directory>` is `.` — Prettier would otherwise re-align the contract's
comment column.)

**Required, not optional** — without it, `prettier --check .` recurses into `coverage/lcov-report/` (generated
HTML) and the auto-generated `eslint.config.js`/`index.ts` and reports false-positive formatting mismatches
(confirmed: 11 files flagged on a freshly scaffolded project before this file was added, versus 3 — the
scaffold's own un-reformatted `App.tsx`/`eslint.config.js`/`index.ts`, which is expected and left for the user
to `prettier --write` once — after it; the October 2026 re-verification also flagged `tsconfig.json` when the
`types` merge was written with a JSON serializer's line breaks, so write that merge Prettier-formatted or include
it in the one-off `prettier --write`).

### `eas.json`

```json
{
  "cli": {
    "version": ">= <eas-cli-version>",
    "appVersionSource": "remote"
  },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal"
    },
    "preview": {
      "distribution": "internal"
    },
    "production": {
      "autoIncrement": true
    }
  }
}
```

**Only when `distribution: store`** (Step 2), add a `submit` block after `build`:

```json
  "submit": {
    "production": {}
  }
```

`<eas-cli-version>` is Step 4's resolved `eas-cli` version (see its fallback table). This file's correctness was
confirmed structurally (valid JSON; matches `eas.json`'s documented schema shape) — the build/submit profiles
themselves are **unverified locally**: running an actual `eas build`/`eas submit` needs an Expo account, project
linking (`eas init`), and (for `store`) real Apple/Google credentials, none of which are available in this
skill's verification environment.

**Who owns the version for this toolchain** (`appVersionSource: "remote"` + `autoIncrement: true`) — decided here
so `iru-release`/`iru-react-native-bump-version` don't have to guess:

- **Build numbers** (`ios.buildNumber` / `android.versionCode`) are owned by **EAS**: with `appVersionSource:
  "remote"` they're stored on EAS's servers and `autoIncrement` bumps them on every `production` build. They must
  not be written into `app.json` or bumped by any catalog skill — `eas build:version:set`/`eas build:version:sync`
  are the only sanctioned ways to touch them.
- **The user-facing version** (`1.4.0` on the store listing) is read from **`app.json`'s `expo.version`** —
  `package.json`'s `version` is never consulted by Expo/EAS or the stores.
- Therefore `iru-react-native-bump-version` (the `react-native` key `iru-release` discovers and prefers over
  `typescript` for React Native apps) always sets `package.json`'s `version`, and sets `expo.version` in `app.json`
  (or the equivalent `version` field in `app.config.ts`/`app.config.js` when the project uses a dynamic config) to
  the **same value only when it is a release value**: a `-dev.N` pre-release string is not a valid store version,
  so the "next development" bump stays in `package.json` alone and `expo.version` keeps the last released value
  until the next release. When a project opts out of `appVersionSource: "remote"`, that skill bumps
  `ios.buildNumber`/`android.versionCode` in the app config on a release value instead.

### UI contract wiring — `index.ts`, the deep-link scheme, and the shared UI templates

Write the shared files from `ui-templates.md` (`.ui-quality.yml` at the repository root, `src/dev/ui-gallery/**`,
`.maestro/config.yaml`, `.maestro/subflows/ui-capture.yaml`, `.maestro/ui/App.yaml`), then:

- **`index.ts`** — replace the scaffold's one-liner registration (new scaffolds only; on an existing app this is
  application source — print the change instead of making it):

  ```ts
  import { registerRootComponent } from "expo";

  import App from "./App";

  // registerRootComponent calls AppRegistry.registerComponent('main', () => App).
  // In development builds the root is wrapped with the UI gallery (a `<scheme>://ui-gallery?…` deep link renders one
  // component/screen state for the Maestro screenshot flows); `__DEV__` is false in production, so the `require` is
  // folded away and the gallery never ships.
  registerRootComponent(__DEV__ ? require("./src/dev/ui-gallery").withUiGallery(App) : App);
  ```

  Confirmed: `npx expo export --platform web` (a production build) contains no gallery code; `--dev` does.
- **`app.json`** — add `"scheme": "<app-slug>"` under `expo` (the `<app-scheme>` the capture subflow opens; a
  development build registers it on both platforms). An app that already has a `scheme` keeps it, and the subflow
  uses that value.
- An app using **Expo Router** (not this scaffold's blank template) has no `App` to wrap: add the gallery as a
  dev-only route instead (`app/ui-gallery.tsx` exporting `__DEV__ ? require(...).UiGalleryRoute : NotFound`), per the
  coding skill's `ui-implementation.md` — report it as a follow-up rather than restructuring routes here.

### `.maestro/smoke.yaml`

```yaml
appId: <app-id>
---
- launchApp
- assertVisible: "<initial-screen-text>"
```

`<app-id>` — the app's bundle identifier / Android application id. When Step 2 resolved `app-id` (optional for
`expo`), Step 6 also writes it to `app.json` as `expo.ios.bundleIdentifier` and `expo.android.package` and fills
it in here. Otherwise (`create-expo-app`'s scaffold sets neither by default) leave the literal `<app-id>`
placeholder in place and flag in Step 8's report that it must be filled in before this flow can run.
`<initial-screen-text>` — from Step 3's read of the scaffolded `App.tsx`. **Unverified locally**: running this flow
needs the Maestro CLI plus a booted simulator/emulator or a device, none of which this skill's verification
exercised — only the YAML's structural shape (two documents, `appId` header then a step list) was validated.

### `sonar-project.properties` (only when `sonar` ≠ `none`)

```
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.sources=.
sonar.tests=.
sonar.test.inclusions=**/__tests__/**,**/*.test.ts,**/*.test.tsx
sonar.exclusions=android/**,ios/**,node_modules/**,coverage/**,.expo/**,dist/**
sonar.test.exclusions=android/**,ios/**,node_modules/**,coverage/**,.expo/**,dist/**
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

Omit the `sonar.organization` line entirely for `sonar: self-hosted` servers without organizations enabled (per
Step 2). The source/test split follows the same pattern as this catalog's React/Angular/Ionic scaffolds
(`sonar.sources` and `sonar.tests` name the **same** root, and `sonar.test.inclusions` is what marks the test
files): the blank Expo template keeps `App.tsx` at the repository root and its tests under `__tests__/`, so `.`
is the only root that covers both — but naming `.` as sources and `__tests__` as a *separate* tests root makes
every `__tests__/*` file match both, which the scanner rejects outright ("File ... can't be indexed twice").
With the inclusions pattern, `__tests__/**` (and any colocated `*.test.ts(x)`) is indexed once, as test code, and
everything else under `.` is production code. `sonar.test.exclusions` mirrors `sonar.exclusions` so a
`__tests__/` folder inside `node_modules/`/`android/`/`ios/` is never picked up as this project's tests.
`android/**`/`ios/**` are excluded even though this scaffold doesn't commit those folders by default — they're
excluded defensively for the moment `npx expo prebuild` (Step 2's `native-builds: local` path) or an EAS local
build materializes them.
