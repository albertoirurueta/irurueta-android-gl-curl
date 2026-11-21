# React Native — handled by the `react-native` key

React Native apps (both toolchains: **expo** — `expo` in `dependencies` — and **metro** — a bare React Native CLI
app, `react-native` without `expo`) now have their own coding key. Tasks for them should be tagged `react-native` in
`implementation_plan.md` and are implemented by **`iru-react-native-code-one-task`** (validated by
`iru-react-native-code-one-task-group`), whose `reference/` covers Expo Router and CNG, the bare CLI's committed
native projects and New Architecture, native modules, state and data, UI and platform, RNTL testing, security, and
TSDoc.

**If `iru-react-native-code-one-task` is installed** in this repository, you shouldn't be reading this file:
`SKILL.md` Step 1 hands a React Native task straight to it. Do that now if it hasn't happened, rather than
implementing the task with this file.

## Minimal fallback (only when the `react-native` key isn't installed)

Apply `code-style.md`, `tsdoc.md`, `testing.md`, and `react.md`, plus:

- **Detect the toolchain**: `expo` in `dependencies` → expo, even if `android/`/`ios/` exist (they're `expo
  prebuild` output); otherwise metro.
- **expo: never edit `android/` or `ios/`.** Native configuration goes through `app.json`/`app.config.*` and config
  plugins; anything needing custom native code is a blocker to report. **metro**: `android/`/`ios/` are committed
  source — keep both platforms building, and report any native change as needing `pod install`/a Gradle build.
- **Expo Router route files** (`app/**` or `src/app/**`) must default-export their screen component — the one
  documented default-export exception; keep route files thin and logic in tested non-route modules. Metro apps use
  React Navigation with typed param lists; never `any`.
- **Platform files** (`Foo.ios.tsx` / `Foo.android.tsx` / `Foo.tsx`) for substantial differences,
  `Platform.select`/`Platform.OS` for small ones; identical exports in every variant.
- **Testing**: Jest with the project's preset (`jest-expo` or `@react-native/jest-preset`) and
  `@testing-library/react-native` — on RNTL 14+ `render`/`fireEvent`/`renderHook` are async (`await render(...)`).
  Query by role/label first; mock native modules at their boundary. Never run Maestro/Detox flows here.
- **Security and UI basics**: no secrets in the JS bundle or app config; tokens only in `expo-secure-store`/
  `react-native-keychain`; validate deep-link params; `StyleSheet.create`; safe areas via
  `react-native-safe-area-context`; `FlatList` for unbounded lists; accessible role + label on every control.

Say in Step 7's report that the fallback was used because the `react-native` key isn't installed.
