# React Native development reference

Rules for implementing one task in an existing React Native app — either toolchain this catalog supports:

- **`expo`** — the Expo framework: `expo` in `package.json` `dependencies`, Expo Router (usually), Continuous Native
  Generation (`npx expo prebuild` regenerates `android/`/`ios/` from `app.json`/`app.config.*` + config plugins),
  Expo SDK modules, EAS Build/Update.
- **`metro`** — a bare React Native Community CLI app: `react-native` without `expo`, committed and hand-maintained
  `android/` (Gradle) and `ios/` (Xcode + CocoaPods) projects, Metro run directly (`npx react-native start`), React
  Navigation for routing.

Expo also bundles with Metro under the hood (its `metro.config.js` extends `expo/metro-config`); "metro" names the
toolchain users pick when they *don't* use the Expo framework, not a different bundler.

These files exist so `iru-react-native-code-one-task` loads **only** what the task in front of it needs, instead of
carrying every React Native rule in context on every task.

## Read this much, and no more

Detect the toolchain first (`iru-react-native-code-one-task` Step 1 — **`expo` in `dependencies` → `expo`, even if
`android/`/`ios/` exist** (they're prebuild output); `react-native` without `expo`, plus `metro.config.js` /
committed `android/` + `ios/` / `@react-native-community/cli` → `metro`), then read:

| Read | When |
|---|---|
| `mandatory-rules.md` | **Always.** The non-negotiable rules for every line written: toolchain boundaries (never edit Expo-generated `android/`/`ios/` or Codegen output, both platforms keep building, no new dependency/config change, no release-owned version fields), no `any`/`@ts-ignore`, named exports, TSDoc, no secrets in the bundle, tokens only in the keystore, validated deep links, accessible controls, virtualized lists, effect cleanup, awaited RNTL 14 APIs, and never running a gate here. |
| `code-style.md` | **Always.** The catalog's TypeScript agreements (condensed) plus RN-specific style: components, hooks, the default-export exception, file layout, no new native dependency without the toolchain's wiring. |
| `expo.md` | Toolchain is **expo** — always (routing, config, what may and may not be edited). |
| `metro.md` | Toolchain is **metro** — always (navigation, Metro config, committed native projects, New Architecture). |
| `native-modules.md` | The task adds or changes native (Kotlin/Java, Swift/Obj-C++) code, a Turbo Module / Fabric component spec, an Expo module, or a config plugin. |
| `state-and-data.md` | The task adds or changes state, a hook, context, server data fetching, persistence (AsyncStorage/MMKV/SQLite), or offline behaviour. |
| `ui-and-platform.md` | The task adds or changes a screen/component's layout, styling, lists, images, animations, accessibility, or platform-specific behaviour. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI. `ui-contract.md` is the catalog-wide UI contract (repeated verbatim in every UI coding skill — never edit it here alone); `ui-implementation.md` maps it onto React Native: screen = container + presentational view, typed state props and `on…` events, `FlatList`/`SectionList` + memoized item component, RNTL component/screen tests, the dev-only UI gallery and the Maestro `ui-capture` screenshot subflow (or Detox `e2e/support/ui-screenshots.ts`), theme/RTL switching, and the `eslint-plugin-react-native-a11y` level read from `.ui-quality.yml`. |
| `testing.md` | The task writes or updates tests — nearly every task. |
| `security.md` | The task touches auth/tokens, secrets or config values, storage of anything sensitive, deep links/intents, WebViews, networking/TLS, logging, or permissions. |
| `tsdoc.md` | The task adds or changes an exported function, component, hook, type, or a native-module spec. |

Read exactly **one** toolchain file (`expo.md` *or* `metro.md`), never both. A one-line change inside an existing
component is `mandatory-rules.md` + `code-style.md` + the toolchain file + `testing.md`. Don't read the rest speculatively.

If the consuming repository also has `iru-typescript-code-one-task` installed, its `reference/code-style.md`,
`testing.md`, and `tsdoc.md` are the long-form originals of what this key's files condense — consult them only when
this key's condensed rule doesn't answer the question; this skill must keep working without them.

## The three rules that override everything else

1. **Match the surrounding code — except for `mandatory-rules.md`.** Every other rule here is the default for *new*
   code. If the file or feature you're
   editing consistently does something else (Redux instead of context, `fireEvent` instead of `userEvent`,
   styled-components instead of `StyleSheet`), follow it and report the divergence rather than restyling the file.
2. **Respect the toolchain's ownership of native code.** In an **expo** project `android/` and `ios/` are generated —
   never hand-edit them; native changes go through `app.json`/`app.config.*`, a config plugin, or a local Expo
   module. In a **metro** project they are source code you own, edited directly and reviewed like any other
   source — and every change to them must keep both platforms building.
3. **A task implements what the task says, and this skill doesn't validate.** No unrequested abstraction, state
   layer, navigation restructure, dependency, or native module. Never run the test suite, coverage, lint,
   `tsc`, Gradle, `pod install`, `xcodebuild`, Metro, `expo prebuild`, or an e2e flow here —
   `iru-react-native-code-one-task-group` does all of that once for the whole task group.

## Sources

Compiled from the official documentation below, cross-checked against a throwaway React Native Community CLI
0.87 TypeScript template and a `create-expo-app` SDK 57 default template (October 2026), with this catalog's own
TypeScript agreements (`iru-typescript-code-one-task/reference/`) taking precedence where they are stricter
(notably on `any`, `readonly`, and named exports).

- React Native docs: <https://reactnative.dev/docs/getting-started>, <https://reactnative.dev/docs/typescript>,
  <https://reactnative.dev/docs/platform-specific-code>, <https://reactnative.dev/docs/accessibility>,
  <https://reactnative.dev/docs/optimizing-flatlist-configuration>, <https://reactnative.dev/docs/performance>,
  <https://reactnative.dev/docs/security>, <https://reactnative.dev/docs/testing-overview>,
  <https://reactnative.dev/docs/linking>, <https://reactnative.dev/docs/metro>,
  <https://reactnative.dev/docs/the-new-architecture/landing-page>,
  <https://reactnative.dev/docs/turbo-native-modules-introduction>,
  <https://reactnative.dev/docs/fabric-native-components-introduction>,
  <https://reactnative.dev/docs/the-new-architecture/using-codegen>, <https://reactnative.dev/docs/hermes>
- Expo docs: <https://docs.expo.dev/router/introduction/>, <https://docs.expo.dev/workflow/continuous-native-generation/>,
  <https://docs.expo.dev/workflow/prebuild/>, <https://docs.expo.dev/config-plugins/introduction/>,
  <https://docs.expo.dev/workflow/configuration/>, <https://docs.expo.dev/modules/overview/>,
  <https://docs.expo.dev/modules/module-api/>, <https://docs.expo.dev/develop/unit-testing/>,
  <https://docs.expo.dev/guides/environment-variables/>, <https://docs.expo.dev/versions/latest/sdk/securestore/>,
  <https://docs.expo.dev/eas-update/introduction/>, <https://docs.expo.dev/build/introduction/>,
  <https://docs.expo.dev/router/reference/typed-routes/>
- React Navigation docs: <https://reactnavigation.org/docs/getting-started>,
  <https://reactnavigation.org/docs/typescript>, <https://reactnavigation.org/docs/static-configuration>,
  <https://reactnavigation.org/docs/deep-linking>, <https://reactnavigation.org/docs/testing>
- React Native Testing Library (Callstack): <https://callstack.github.io/react-native-testing-library/>,
  <https://callstack.github.io/react-native-testing-library/docs/api/queries>,
  <https://callstack.github.io/react-native-testing-library/docs/api/events/user-event>
- Jest docs (React Native preset, timers, mocks): <https://jestjs.io/docs/tutorial-react-native>,
  <https://jestjs.io/docs/timer-mocks>
- Maestro docs: <https://docs.maestro.dev/> (`openLink`, `takeScreenshot`, `runFlow`, workspace `config.yaml`); Detox docs: <https://wix.github.io/Detox/docs/introduction/getting-started>
- Reanimated docs: <https://docs.swmansion.com/react-native-reanimated/>; Gesture Handler:
  <https://docs.swmansion.com/react-native-gesture-handler/>
- TSDoc specification: <https://tsdoc.org/>; TypeDoc: <https://typedoc.org/>
- `eslint-plugin-react-native-a11y`: <https://github.com/FormidableLabs/eslint-plugin-react-native-a11y>;
  React Native Appearance/I18nManager/Linking: <https://reactnative.dev/docs/appearance>,
  <https://reactnative.dev/docs/i18nmanager>, <https://reactnative.dev/docs/linking> (full list in
  `ui-implementation.md`)
- OWASP Mobile Application Security (MASVS): <https://mas.owasp.org/MASVS/>
- This catalog's TypeScript agreements: `iru-typescript-code-one-task/reference/code-style.md`, `testing.md`,
  `tsdoc.md`, `react.md`.
