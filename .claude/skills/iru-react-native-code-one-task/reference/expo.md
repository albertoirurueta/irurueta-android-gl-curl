# Expo toolchain

Rules for a project whose `package.json` `dependencies` contain `expo` — whether or not `android/`/`ios/` exist on
disk. Read together with `code-style.md`; `native-modules.md` covers custom native code in detail.

Expo bundles with Metro too (`metro.config.js`, when present, extends `expo/metro-config`'s `getDefaultConfig`);
what makes this the **expo** toolchain is that the Expo framework owns the native projects, the config, and the
SDK module versions.

## Versions: the SDK decides

- The `expo` major is the **SDK version** (SDK 57 ↔ `expo@57`, React Native 0.86, React 19.2 in October 2026); every
  `expo-*` package and the React Native version are pinned to what that SDK supports
  (`node_modules/expo/bundledNativeModules.json`). Read the installed `expo` major first and use the matching
  versioned docs (`https://docs.expo.dev/versions/v<major>.0.0/`) for any SDK API — Expo renames and moves APIs
  between SDKs, so an API remembered from another SDK is a guess. If the project ships an `AGENTS.md` (the SDK 57
  template does), follow it.
- Packages are added with `npx expo install <pkg>` (it resolves the SDK-compatible version), never a bare
  `npm install` — but adding a package is a new dependency, which this skill reports as a blocker unless the task
  asked for it.

## Continuous Native Generation — never hand-edit `android/` or `ios/`

- With CNG, `android/` and `ios/` are **generated** by `npx expo prebuild` from `app.json`/`app.config.*`, the
  installed packages, and their config plugins; they are usually gitignored and rebuilt on every EAS/local build.
  A hand edit there is silently lost on the next prebuild — **never** make one, even when the directories exist
  locally (a developer ran `npx expo run:ios`) and even when the change "only" touches `Info.plist`,
  `AndroidManifest.xml`, `build.gradle`, or `Podfile`.
- If `android/`/`ios/` are **committed** and there is no `prebuild` in the build pipeline, the project has opted out
  of CNG ("bare workflow" with Expo). That is ambiguous enough to stop and ask (Step 5 of `SKILL.md`) before
  touching native code — don't assume either way.
- Native configuration goes, in order of preference:
  1. **`app.json` / `app.config.{ts,js}`** fields — `ios.infoPlist`, `ios.entitlements`, `ios.bundleIdentifier`,
     `android.permissions`, `android.package`, `android.intentFilters`, `scheme`, `plugins`, `extra`.
  2. **A package's own config plugin**, listed in `plugins` with its options (`["expo-camera", {
     "cameraPermission": "…" }]`) — check the package's docs for the plugin's option names.
  3. **A local config plugin** (`plugins/with-<thing>.ts`, wired via `plugins: ["./plugins/with-<thing>"]`) using
     `expo/config-plugins` mods (`withInfoPlist`, `withAndroidManifest`, `withAppBuildGradle`, …) — only when the
     task asks for a native setting no field or package plugin exposes. Keep plugins idempotent (re-running
     prebuild must not duplicate entries) and prefer the typed mods over `withDangerousMod` string patching.
- **Any change to `app.json`/`app.config.*`, `plugins`, or a config plugin requires a new native build** (a new
  development build or EAS Build) — it doesn't reach a running app over Metro or an EAS Update. Say so in the report;
  the group skill runs a throwaway `npx expo prebuild --no-install` check when a config plugin changed.

## App config

- `app.json` is static; `app.config.ts` (or `.js`) is dynamic and receives `({ config })` — if both exist, the
  dynamic file decides what of the static one survives. Edit whichever the project uses; don't introduce the
  other.
- **Nothing secret in app config.** `extra`, `expo.*`, and every `EXPO_PUBLIC_*` environment variable are embedded in
  the app bundle/manifest and readable by anyone with the app (`security.md`). Read config at runtime through
  `expo-constants` (`Constants.expoConfig?.extra`) or `process.env.EXPO_PUBLIC_X` written literally (Expo inlines
  only literal `process.env.EXPO_PUBLIC_*` references).
- `scheme` is the deep-link scheme — changing it breaks existing links; only when the task says so.

## Expo Router

- Routes live in `app/` — **or `src/app/`** (the SDK 57 default template puts them there; the `src/app/` directory
  is used when `app/` doesn't exist). Detect which before creating a file. Every file there is a route; non-route
  code (components, hooks, utils) lives outside it.
- File conventions: `index.tsx` (`/`), `orders/[id].tsx` (dynamic), `[...rest].tsx` (catch-all), `(tabs)/` (group,
  not in the URL), `_layout.tsx` (navigator: `<Stack>`, `<Tabs>`, `<Slot>`, or a native tabs layout), `+not-found.tsx`,
  `+html.tsx` (web only), `+api.ts` (server API route — only when the project deploys an Expo server).
- Route files **default-export** the screen (`code-style.md`). Keep them thin: read params, compose feature
  components, wire navigation.
- Params: `useLocalSearchParams<{ id: string }>()` for the focused route (`useGlobalSearchParams` only when the
  task needs updates while unfocused). **Params are untrusted strings** (deep links reach them) — validate and parse
  them (`Number.parseInt`, a schema) before use, and handle `string[]` for repeated params.
- Navigate with `expo-router` APIs only: `<Link href="/orders/42">`, `router.push`/`replace`/`back`/`dismiss`,
  `useRouter()`, `<Redirect href>`. Object form for dynamic routes: `{ pathname: "/orders/[id]", params: { id } }`.
  Don't import `@react-navigation/*` navigators directly; reach React Navigation options via `<Stack.Screen
  options={...} />` or the layout's `screenOptions`.
- **Typed routes**: when `experiments.typedRoutes` is on (it is in the SDK 57 template), `href` is checked against
  the generated `.expo/types/router.d.ts`; use literal routes or the `Href` type, never `as any`. The types are
  generated by `expo start`/`expo customize tsconfig.json` — a `tsc` error about a missing route on a clean checkout
  is a missing generation run, not something to cast away.
- Protected/auth routes: `<Stack.Protected guard={isSignedIn}>` (SDK 53+) or a `<Redirect>` in the group layout —
  but the server/API still authorizes every request; client-side guards are UX only (`security.md`).

## SDK modules and EAS awareness

- Prefer an Expo SDK module (`expo-image`, `expo-secure-store`, `expo-file-system`, `expo-notifications`,
  `expo-camera`, `expo-sqlite`, …) over a third-party equivalent when one is already installed or the task permits
  adding one — they track the SDK's React Native version and ship config plugins.
- Expo Go only contains Expo's bundled native modules; code using a library with custom native code needs a
  development build (`expo-dev-client`). Don't write code that silently degrades in Expo Go unless the task asks.
- **EAS Update** (`expo-updates` installed): an OTA update can only ship JavaScript and assets compatible with the
  installed binary's **runtime version**. A task that changes native code, a config plugin, or a native dependency
  needs a new binary — and, if `runtimeVersion` uses a manual string policy, a runtime version bump. Never bump
  `runtimeVersion` or `version` as a side effect; report that the change is not OTA-compatible.
- **EAS Build** (`eas.json`): build profiles, credentials, and environment variables live there and in EAS — not in
  code. Don't edit `eas.json` unless the task is about builds.

## Platforms

- `Platform.OS` can also be `"web"` in an Expo app that targets web (`react-native-web` installed, `web` in
  `app.json`). Platform files `Foo.web.tsx` / `Foo.native.tsx` exist for that split; the SDK 57 template uses them
  (`app-tabs.web.tsx`, `use-color-scheme.web.ts`). Keep the exported API identical across variants.
- The SDK 57 template names files in **kebab-case** (`themed-text.tsx`, `use-theme.ts`) while this catalog's
  scaffold and many projects use `PascalCase` components — follow whichever the project already uses.
