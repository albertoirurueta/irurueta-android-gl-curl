# Metro templates (`toolchain: metro`)

The tooling `iru-setup-react-native-app` layers on top of a bare React Native Community CLI project
(`npx @react-native-community/cli@latest init`, SKILL.md Step 6) when `toolchain` is `metro`. Verified end-to-end
in October 2026 against `@react-native-community/cli` 20.2.0's default TypeScript template (`react-native@0.87.1`,
`react@19.2.3`, Node 24.20, npm 11.19, JDK 25, Xcode 27, Ruby 3.4.6 via rbenv): `npm install`, `npm test --
--coverage` (100% lines on `App.tsx`), `npm run lint`, `npm run typecheck`, `npm run format`, `cd android &&
./gradlew assembleDebug` (Gradle 9.4.1 wrapper, `compileSdk` 37 / `minSdk` 24 — about 19 minutes on a cold Gradle
cache), and `bundle install && cd ios && bundle exec pod install` all run clean against these templates; see
SKILL.md Step 8 for the iOS `xcodebuild` outcome and what stayed unverified. Every block is genericized — only
substitute the `<placeholder>` values from SKILL.md Steps 2–4. "Step N" below always means a step of SKILL.md.

## What the template ships (exact file names)

Recorded from the real scaffold so the other React Native skills (gates, workflows, bump-version, coding) can rely
on them — confirm against the actual directory when updating an older project, since templates move:

| Path (relative to `<app-directory>`) | Notes |
|---|---|
| `package.json` | `name` (see the identity quirk below), `version` `0.0.1`, scripts `android`/`ios`/`lint` (`eslint .`)/`start` (`react-native start`)/`test` (`jest`); `engines.node` `>= 22.11.0` |
| `App.tsx`, `index.js` | `index.js` is the `AppRegistry.registerComponent(appName, …)` entry, `appName` read from `app.json` |
| `app.json` | only `{"name": "<AppName>", "displayName": "<AppName>"}` — `name` is the registered JS module name |
| `__tests__/App.test.tsx` | template test uses `react-test-renderer` — replaced below |
| `jest.config.js` | `module.exports = { preset: '@react-native/jest-preset' };` (the dedicated preset package, **not** the legacy `preset: 'react-native'` older templates used) |
| `.eslintrc.js` | legacy eslintrc format — `{ root: true, extends: '@react-native' }` — with `eslint` `^8.19.0` and `@react-native/eslint-config` |
| `.prettierrc.js` | `{ arrowParens: 'avoid', singleQuote: true, trailingComma: 'all' }`, `prettier` pinned to `2.8.8` |
| `babel.config.js` | `presets: ['module:@react-native/babel-preset']` |
| `metro.config.js` | `mergeConfig(getDefaultConfig(__dirname), {})` from `@react-native/metro-config` |
| `tsconfig.json` | `extends: "@react-native/typescript-config"` — Jest globals already typed, no `types` merge needed (`tsc --noEmit` passes as shipped) |
| `.watchmanconfig`, `README.md`, `.gitignore` | the template's own `README.md`/`.gitignore` land **inside** `<app-directory>` |
| `Gemfile`, `.bundle/config` | Gemfile pins `cocoapods >= 1.13, != 1.15.0, != 1.15.1`, `xcodeproj < 1.26.0`, `activesupport`, `concurrent-ruby < 1.3.4`, plus `bigdecimal`/`logger`/`benchmark`/`mutex_m`/`nkf` for Ruby 3.4; `ruby ">= 2.6.10"`. `.bundle/config` sets `BUNDLE_PATH: "vendor/bundle"` |
| `android/` | Groovy Gradle: `settings.gradle`, `build.gradle` (`minSdkVersion`/`compileSdkVersion`/`targetSdkVersion` in `ext`), `gradle.properties`, `gradlew`, `gradle/wrapper/`, `app/build.gradle` (`namespace`, `applicationId`, `versionCode 1`, `versionName "1.0"`, `release` build type signed with `signingConfigs.debug`), `app/debug.keystore` (committed on purpose), `app/proguard-rules.pro`, `app/src/main/java/<package/path>/MainActivity.kt` + `MainApplication.kt`, `app/src/main/res/values/strings.xml` (`app_name`) |
| `ios/` | `Podfile` (target `<AppName>`), `<AppName>.xcodeproj` (`PRODUCT_BUNDLE_IDENTIFIER`, `MARKETING_VERSION = 1.0`, `CURRENT_PROJECT_VERSION = 1` in `project.pbxproj`), `<AppName>/AppDelegate.swift` (`withModuleName: "<AppName>"`), `<AppName>/Info.plist` (`CFBundleDisplayName`), `<AppName>/PrivacyInfo.xcprivacy`, `.xcode.env` (committed; `NODE_BINARY`). `pod install` adds `<AppName>.xcworkspace`, `Podfile.lock` (commit both) and the ignored `Pods/`, `build/`, `.xcode.env.local` |

## Identity fix-ups (always, right after the scaffold)

- **`package.json` `name`** → `<app-slug>`. Confirmed quirk: passing `--package-name com.example.myapp` makes the
  CLI also write `com.example.myapp` into `package.json`'s `name` **and** `android/settings.gradle`'s
  `rootProject.name` (without the flag both are `<AppName>`, and the ids fall back to `com.<appname>` /
  `org.reactjs.native.example.$(PRODUCT_NAME:rfc1034identifier)`). Reset `rootProject.name` to `'<AppName>'` too.
- **Display name** → `<app-name>` in three places: `app.json` `displayName`,
  `android/app/src/main/res/values/strings.xml` `app_name`, and `ios/<AppName>/Info.plist` `CFBundleDisplayName`
  (`/usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName '<app-name>'" ios/<AppName>/Info.plist` on macOS, or a
  text edit of the `<string>` after the key elsewhere). Confirmed: the CLI's `--title` flag changes none of these
  in the TypeScript template, so don't rely on it.
- **Never change `app.json`'s `name`** — it's the module name `AppRegistry` registers and that
  `MainActivity.getMainComponentName()` and `AppDelegate`'s `withModuleName:` look up; renaming it alone gives a
  red-screen "has not been registered" at launch.
- `applicationId`/`namespace` and `PRODUCT_BUNDLE_IDENTIFIER` are set by `--package-name <app-id>` (confirmed:
  both Debug and Release configurations, and the Kotlin sources move to `java/<app-id as path>/`). Only when
  updating an existing project whose ids are still the template defaults, offer to change them — that is a
  package rename across `build.gradle`, the Kotlin package declarations/directories and `project.pbxproj`, so show
  the diff and confirm.

## `package.json` — merge, don't replace

Merge these scripts (`lint`/`test` already exist with the same values) and the one devDependency; keep the
template's own pins for `eslint` (8.x), `prettier` (2.8.8), `jest` (29.x), `@types/jest`, `typescript` and every
`@react-native/*` package — `@react-native/eslint-config` declares `eslint ^8 || ^9` / `prettier >=2` and the
template's `.eslintrc.js` is eslintrc-format, which ESLint 10 no longer reads, so bumping them is a separate,
deliberate migration rather than part of this scaffold:

```json
{
  "scripts": {
    "test": "jest",
    "coverage": "jest --coverage",
    "lint": "eslint .",
    "format": "prettier --check \"**/*.{js,jsx,ts,tsx}\"",
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "@testing-library/react-native": "<testing-library-react-native-version>",
    "eslint-plugin-react-native-a11y": "<eslint-plugin-react-native-a11y-version>"
  }
}
```

`eslint-plugin-react-native-a11y` (UI contract §8) installs cleanly next to the template's ESLint 8 — its peer range
stops at `^8`, so no `overrides` entry is needed here (unlike expo's ESLint 9).

Also set `package.json`'s `version` to `0.1.0-dev.0` (the template starts on the plain `0.0.1`): this catalog's npm
pre-release convention, so the first `iru-release` run has a development version to turn into `0.1.0`. The native
store versions (`versionName "1.0"`/`versionCode 1`, `MARKETING_VERSION = 1.0`/`CURRENT_PROJECT_VERSION = 1`) are
left as the template wrote them — `iru-react-native-bump-version` sets them only from a release value.

`format` is scoped to source files on purpose: `@react-native/eslint-config` runs Prettier on JS/TS through ESLint
and the template's Prettier 2.8.8 has no business reformatting the native projects' JSON/YAML/Markdown.

**Don't switch to `@react-native/eslint-config/flat`.** Confirmed by the gate verification: that flat export
crashes under ESLint 9.39, so keep the template's `.eslintrc.js` + ESLint 8.57 combination.

Then `npm uninstall react-test-renderer @types/react-test-renderer` — only the template's own test used them, and
it is replaced below. Confirmed: `@testing-library/react-native@14` brings its own peer `test-renderer@1`
(installed automatically by npm 7+), so nothing else is needed. On an existing project (Update), remove them only
if no remaining test imports `react-test-renderer`.

## `jest.config.js`

```js
module.exports = {
  preset: '@react-native/jest-preset',
  setupFiles: ['./jest.setup.js'],
  collectCoverage: true,
  coverageReporters: ['text', 'lcov'],
  collectCoverageFrom: [
    '**/*.{ts,tsx}',
    '!**/coverage/**',
    '!**/node_modules/**',
    '!**/android/**',
    '!**/ios/**',
    '!**/vendor/**',
  ],
};
```

On an older project whose config still says `preset: 'react-native'`, keep that value (Update) — the coverage
keys are what this skill owns. `coverage/lcov.info` is what `sonar.javascript.lcov.reportPaths` reads.

## `jest.setup.js`

```js
/* eslint-env jest */
// react-native-safe-area-context's SafeAreaProvider renders nothing until a native layout event reports the
// insets, which never happens under Jest — use the library's own mock so the tree below it actually renders.
jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);
```

Confirmed necessary: the template's `App.tsx` wraps everything in `SafeAreaProvider`, and without this mock the
test passes but only 60% of `App.tsx`'s lines run (the `AppContent` body never renders); with it, 100% lines /
50% branches (the uncovered branch is the `useColorScheme() === 'dark'` ternary). The `/* eslint-env jest */`
comment is required — `@react-native/eslint-config` otherwise flags `jest` as `no-undef` in a non-test file. The
block above is already Prettier-formatted with the template's `.prettierrc.js`.

## `__tests__/App.test.tsx` — replaces the template's `react-test-renderer` test

```tsx
/**
 * @format
 */

import { render, screen } from '@testing-library/react-native';

import App from '../App';

test('renders the root screen', async () => {
  // @testing-library/react-native's `render` is async as of v14 — always await it.
  await render(<App />);
  expect(screen.toJSON()).not.toBeNull();
});
```

## `.eslintrc.js` — the template's file plus ignores

```js
const fs = require('fs');
const path = require('path');

// UI contract §8: the accessibility level lives in .ui-quality.yml at the repository root (missing file → enforce).
function accessibilitySeverity() {
  for (let dir = __dirname; ; dir = path.dirname(dir)) {
    const file = path.join(dir, '.ui-quality.yml');
    if (fs.existsSync(file)) {
      const match = /^accessibility:\s*(enforce|report|off|none)\b/m.exec(
        fs.readFileSync(file, 'utf8'),
      );
      const level = match ? match[1] : 'enforce';
      return { report: 'warn', off: 'off', none: 'off' }[level] || 'error';
    }
    if (fs.existsSync(path.join(dir, '.git')) || path.dirname(dir) === dir) {
      return 'error';
    }
  }
}
const a11ySeverity = accessibilitySeverity();
const a11yRules = require('eslint-plugin-react-native-a11y').configs.all.rules;

module.exports = {
  root: true,
  extends: '@react-native',
  plugins: ['react-native-a11y'],
  // The plugin's `all` preset minus has-accessibility-hint, which demands a hint on every labelled element.
  rules: Object.fromEntries(
    Object.keys(a11yRules)
      .filter(rule => rule !== 'react-native-a11y/has-accessibility-hint')
      .map(rule => [rule, a11ySeverity]),
  ),
  ignorePatterns: ['coverage/', 'vendor/', 'android/', 'ios/'],
};
```

The accessibility block reads the level from `.ui-quality.yml` (`enforce` → error, `report` → warning, `off` → rules
off); confirmed `npm run lint` passes on the scaffold plus the UI gallery files with it.

Kept in the template's own legacy eslintrc format (ESLint 8). `coverage/` is required: Jest's HTML report ships
`coverage/lcov-report/*.js` files ESLint would otherwise lint. `vendor/` (the Bundler gem path), `android/` and
`ios/` (Gradle/Xcode build outputs and `Pods/` can contain JS) are excluded for the same reason. Confirmed:
`npm run lint` reports 0 problems on the scaffold with this file. `@react-native/eslint-config` already layers
`eslint-config-prettier`, so no extra Prettier-compat config is needed. If the project has moved to a flat
`eslint.config.js` (`@react-native/eslint-config/flat`), put the same paths in its `ignores` instead.

## `.prettierrc.js` — keep the template's file unchanged

## `.prettierignore`

```
node_modules/
coverage/
vendor/
android/
ios/
.ui-quality.yml
```

Required — confirmed: without it, `prettier --check .` already fails on the pristine scaffold
(`ios/<AppName>/Images.xcassets/AppIcon.appiconset/Contents.json` and `Images.xcassets/Contents.json`), and after
a build it would also walk Gradle/Xcode/CocoaPods output and Jest's HTML report. With it, `npm run format` (scoped to
`**/*.{js,jsx,ts,tsx}`) passes on the scaffold plus every file above; the ignore file still matters for an ad hoc
`npx prettier --check .` and for editor integrations.

## UI contract wiring — `index.js`, the deep-link scheme, and the shared UI templates

Write the shared files from `ui-templates.md` (`.ui-quality.yml` at the repository root, `src/dev/ui-gallery/**`,
`.maestro/config.yaml`, `.maestro/subflows/ui-capture.yaml`, `.maestro/ui/App.yaml`), then `npx prettier --write
src/dev index.js .eslintrc.js` once (those blocks are in the expo Prettier style), and:

- **`index.js`** (new scaffolds only; on an existing app print the change instead):

  ```js
  /**
   * @format
   */

  import { AppRegistry } from 'react-native';
  import App from './App';
  import { name as appName } from './app.json';

  // In development builds the root is wrapped with the UI gallery (a `<scheme>://ui-gallery?…` deep link renders one
  // component/screen state for the Maestro screenshot flows); `__DEV__` is false in release builds, so the `require`
  // is folded away and the gallery never ships.
  AppRegistry.registerComponent(appName, () =>
    __DEV__ ? require('./src/dev/ui-gallery').withUiGallery(App) : App,
  );
  ```

  Confirmed: `npx react-native bundle --platform android --dev false` contains no gallery code; `--dev true` does.
- **The deep-link scheme** `<app-slug>` on both native projects (React Native "Linking" docs) — the React Native
  0.88 template's `ios/<AppName>/SceneDelegate.swift` already forwards `openURLContexts` to `RCTLinkingManager` and
  passes the launch `connectionOptions`, so only the scheme itself is missing (an older template whose
  `AppDelegate` lacks the `RCTLinkingManager` forwarding needs it added — report that rather than editing it):
  - `android/app/src/main/AndroidManifest.xml` — a second `<intent-filter>` inside the `.MainActivity` activity:

    ```xml
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="<app-slug>" />
            </intent-filter>
    ```
  - `ios/<AppName>/Info.plist` — a top-level `CFBundleURLTypes` entry:

    ```xml
    	<key>CFBundleURLTypes</key>
    	<array>
    		<dict>
    			<key>CFBundleURLSchemes</key>
    			<array>
    				<string><app-slug></string>
    			</array>
    		</dict>
    	</array>
    ```

  Verified on the React Native 0.88 template: `plutil -lint` accepts the edited `Info.plist` and
  `./gradlew :app:processDebugMainManifest` merges the `intent-filter` (the scheme appears in the merged manifest).
  The full `assembleDebug`/`xcodebuild` and opening the link on a device were not re-run with them — Step 7's native
  builds exercise them.

## `.maestro/smoke.yaml`

```yaml
appId: <app-id>
---
- launchApp
- assertVisible: "Welcome to React Native"
```

`<app-id>` is the `app-id` resolved in Step 2 (the same value as `applicationId`/`PRODUCT_BUNDLE_IDENTIFIER`).
`"Welcome to React Native"` is the heading `@react-native/new-app-screen`'s `NewAppScreen` renders in the
template's `App.tsx` — confirm against the installed package (Step 3) and change it together with `App.tsx` once
the real first screen exists. **Unverified locally**: a debug build loads its JS from Metro on port 8081, so the
flow needs the Maestro CLI, a booted simulator/emulator with the app installed, and `npx react-native start`
running (serialized behind `.integration-test.lock` by the gate skills); a release build embeds the bundle.

## `sonar-project.properties` (only when `sonar` ≠ `none`)

```
sonar.organization=<sonar-organization>
sonar.projectKey=<sonar-project-key>
sonar.sources=.
sonar.tests=.
sonar.test.inclusions=**/__tests__/**,**/*.test.ts,**/*.test.tsx
sonar.exclusions=android/**,ios/**,vendor/**,node_modules/**,coverage/**
sonar.test.exclusions=android/**,ios/**,vendor/**,node_modules/**,coverage/**
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

**Decision: the committed native sources (`android/app/src/**`, `ios/<AppName>/**`) are not analyzed by Sonar** —
only the TypeScript app is. Reasons: the `sonar-scanner` CLI the workflows run can't analyze Java without
`sonar.java.binaries` (a compiled Gradle build) nor Objective-C/C++ (Turbo Module `.mm` files) without a
build-wrapper/compilation database, and it fails rather than skips when it finds them; native code has no lcov
coverage, so including it would drag the coverage figure down for code Jest can never reach. Native code is
instead checked by Android Lint/`assembleDebug` and `xcodebuild` (the `iru-react-native-code-one-task-group` gates
and the metro CI jobs) and by CodeQL in `security.yml`. A team that wants Kotlin/Swift issues in Sonar can narrow
the exclusions to the build outputs (`android/**/build/**`, `android/.gradle/**`, `ios/Pods/**`, `ios/build/**`)
and add `sonar.coverage.exclusions=android/**,ios/**` — as long as no Java/Objective-C/C++ files exist there —
which this skill doesn't do by default. The source/test split otherwise matches the Expo template (same root,
tests marked by `sonar.test.inclusions`, so no file is indexed twice).

## Native build and signing notes (no EAS)

- **Android**: `cd android && ./gradlew assembleDebug` (needs `ANDROID_HOME`/`ANDROID_SDK_ROOT` pointing at an SDK
  with the template's `compileSdk` platform, plus a JDK the template's Gradle supports — JDK 17+; JDK 25 worked)
  writes `android/app/build/outputs/apk/debug/app-debug.apk`. The `release` build type is signed with
  `signingConfigs.debug` out of the box — fine for `distribution: none`, never for a store upload.
  `android/app/debug.keystore` is the template's public debug key and is committed on purpose (the template's
  `.gitignore` has `*.keystore` + `!debug.keystore`); a real upload keystore is never committed.
- **iOS** (macOS only): `bundle install` (Ruby 3.x recommended — the Gemfile's Ruby 3.4 gems exist for that line;
  the macOS system Ruby 2.6 with Bundler 1.17 is not what was verified), then `cd ios && bundle exec pod install`,
  run with a UTF-8 locale — confirmed: under a non-UTF-8 shell CocoaPods aborts with
  `Unicode Normalization not appropriate for ASCII-8BIT (Encoding::CompatibilityError)`, fixed by
  `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`. Commit `Gemfile.lock`, `ios/Podfile.lock` and
  `ios/<AppName>.xcworkspace` — the metro CI jobs hash both lockfiles for caching. Then
  `xcodebuild -workspace ios/<AppName>.xcworkspace -scheme <AppName> -configuration Debug -sdk iphonesimulator
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`.
- **Distribution without EAS** — `iru-setup-typescript-github-workflows` (`flavor: react-native`, `toolchain:
  metro`) wires the release jobs; this skill only names what they will need when `distribution` ≠ `none`:
  `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` (the
  upload keystore, injected through the Android Gradle Plugin's `android.injected.signing.*` properties, so
  `build.gradle` needn't change), `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (Play internal track for `internal`,
  production track for `store`), and `APP_STORE_CONNECT_API_KEY_ID` / `APP_STORE_CONNECT_API_KEY_ISSUER_ID` /
  `APP_STORE_CONNECT_API_KEY_BASE64` (an App Manager/Admin API key driving `xcodebuild`'s automatic cloud-managed
  signing and the TestFlight upload). Only a team that manages its distribution certificate manually also needs a
  `.p12` signing certificate + password and a provisioning profile as secrets — the workflows skill asks.
