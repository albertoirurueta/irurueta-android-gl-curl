# Mandatory rules

The rules in this file are **not preferences** — they apply to every line of production, test, native, and config
code this skill writes or rewrites, and they take precedence over anything softer elsewhere in `reference/`
(including "match the surrounding code"). Each one is either enforced by a gate the group skill runs, or a defect
that ships to every installed copy of the app and can't be recalled by a server deploy.

Scope, so the rules don't turn a task into a restyle: they bind **the code you write or rewrite for this task**.
They do not license converting untouched code that predates them. If a file you're adding to already breaks one (an
`any`, a token in AsyncStorage, an unlabeled icon button), write your new code to the rule anyway, leave the old code
alone, and name the pre-existing violation in the report so it can become its own task — except where the violation
is a security defect your change would extend (then stop and report, Step 5 of `SKILL.md`).

## Toolchain boundaries

1. **expo: never create or edit anything under `android/` or `ios/`** — even if the directories exist. Native
   configuration goes through `app.json`/`app.config.*`, a config plugin, or a local Expo module (`expo.md`).
2. **Never edit generated code**: Codegen output (`android/app/build/generated/**`, `ios/build/generated/**`),
   `ios/Pods/**`, `.expo/**`, `expo-env.d.ts`, `nativewind-env.d.ts`-style generated declarations.
3. **metro: a native change keeps both platforms building.** A module, permission, or deep link added on one
   platform has its counterpart (or an explicit, documented unsupported path) on the other.
4. **No new dependency — JS or native — and no config change the task didn't ask for**: `package.json` dependencies,
   `app.json` `plugins`, `babel.config.js`, `metro.config.js`, `jest.config.js`/`jest` key, `tsconfig.json`,
   ESLint/Prettier config, `Podfile`, Gradle files, `gradle.properties`, `eas.json`. Needing one is a blocker to
   report.
5. **Never touch release-owned values**: `package.json` `version`, `expo.version`/`runtimeVersion`/
   `ios.buildNumber`/`android.versionCode`, Android `versionName`/`versionCode`, iOS
   `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`. They belong to the release process.

## TypeScript

6. **No `any`** (explicit or via `as any`), **no `@ts-ignore`**, and no `@ts-expect-error` without a one-line reason
   naming the upstream typing bug. No `eslint-disable` to make a task pass (an existing, justified rule-scoped one may
   stay).
7. **Named exports**, except the toolchain-required default exports (Expo Router route files, `App.tsx` in a metro
   app, a Codegen spec's `TurboModuleRegistry.getEnforcing` export) — and say in the report when one was used.
8. **TSDoc on every exported symbol** you add or change (`tsdoc.md`), including component props interfaces and hooks.
9. **No `console.log`/`console.debug`** left in code; never log tokens, credentials, PII, or full payloads with any
   logger.

## Security

10. **No secrets in the bundle or app config** — not in source, `extra`, `EXPO_PUBLIC_*`, `.env` values compiled into
    the app, `Info.plist`, or `strings.xml` (`security.md`).
11. **Tokens and credentials only in the platform keystore** (`expo-secure-store` / `react-native-keychain`), never in
    AsyncStorage, MMKV without encryption, persisted query/redux state, or files.
12. **Every deep-link / route / push-payload parameter is validated** before use, and no link triggers a
    state-changing action without user confirmation.
13. **WebViews and `Linking.openURL` use allow-lists**; no file-access or mixed-content WebView flags; no cleartext
    HTTP or ATS exceptions beyond the template's development ones.

## UI

14. **Every interactive element has an accessible role and name** (`accessibilityRole`/`role` + visible text or
    `accessibilityLabel`); icon-only controls always have a label.
15. **Unbounded lists use a virtualized list** (`FlatList`/`SectionList`/the project's FlashList or LegendList) with a
    stable `keyExtractor` — never `ScrollView` + `.map()`.
16. **Effects clean up** every listener, subscription, timer, and in-flight request they start.
17. **Safe areas via `react-native-safe-area-context`** — never React Native's deprecated `SafeAreaView`, never a
    hardcoded notch/status-bar padding.

## Tests

18. **Every behaviour the task adds or changes has a Jest test**, including each documented `@throws`/rejection and
    each platform branch the task adds.
19. **RNTL APIs awaited when the installed major is 14+** (`await render(...)`, `await fireEvent...`,
    `await renderHook(...)`); `userEvent` interactions always awaited.
20. **No real-time sleeps** (fake timers instead), **no snapshot-only tests**, **no mocking of the unit under test** —
    mock native modules and network at their boundary.
21. **Never run** tests, coverage, lint, `tsc`, Gradle, CocoaPods, `xcodebuild`, Metro, `expo prebuild`, or an e2e
    flow from this skill — the group skill does, once per bucket, with the e2e stage serialized behind the
    repository-wide `.integration-test.lock`.
