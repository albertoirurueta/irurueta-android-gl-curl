# Code style

This catalog's TypeScript agreements (the same ones `iru-typescript-code-one-task/reference/code-style.md` and
`react.md` apply to any npm/TypeScript and React project, condensed here so this skill stays self-contained) plus
what is specific to React Native. Everything below is a default for new code: **match the surrounding code**, and
report a divergence instead of restyling a file the task didn't ask you to restyle.

## TypeScript agreements (inherited)

- **Strict typing, no `any`.** Both toolchains' templates ship `"strict": true` (the CLI template extends
  `@react-native/typescript-config`, Expo's extends `expo/tsconfig.base`). Use `unknown` plus a type guard, a
  generic, or a union. No non-null assertions (`!`) in place of narrowing; no `as` to force unrelated shapes
  together (only after a validating guard). Prefer string-literal unions over `enum` unless the project already
  uses enums.
- **Discriminated unions over optional-field grab-bags** — `{ status: "idle" } | { status: "loading" } |
  { status: "success"; data } | { status: "error"; error }` for request/screen state.
- **`readonly` by default**: `readonly` props/fields, `readonly T[]`/`ReadonlyArray<T>` for inputs and outputs the
  caller must not mutate, `const` over `let`, return new objects instead of mutating.
- **Named exports for every module you create** — except where the toolchain requires a default export (below).
- **ESM syntax** (`import`/`export`) in TypeScript source. Config files that Metro/Babel/Jest load as CommonJS
  (`metro.config.js`, `babel.config.js`, `jest.config.js`, `.prettierrc.js`, `react-native.config.js`) keep
  `module.exports` — never convert them.
- **Naming**: `UpperCamelCase` types/components (no `I` prefix), `lowerCamelCase` functions/values, `use*` hooks,
  predicates read as booleans (`isOnline`), `SCREAMING_SNAKE_CASE` only for true global constants. Component
  files are `PascalCase.tsx`.
- **Errors**: throw `Error` subclasses with `.name` set and `{ cause }` when re-throwing; never swallow in a bare
  `catch`; validate at the boundary (props, navigation params, deep-link params, API responses, native-module
  results). No `console.log` left behind — release builds keep `console.*` calls unless the project strips them,
  and they reach the device log (see `security.md`).
- **Async**: `async`/`await`; `Promise.all` for independent work; no floating promises (`void` + comment when a
  promise is deliberately not awaited); `AbortController` for a fetch a screen may abandon on unmount/blur.
- **No new runtime dependency** unless the task asks for it — in React Native that also means **no new native
  dependency**: a package with native code changes the build (`pod install`, Gradle sync, a config plugin, a
  rebuilt dev client) and is a blocker to report, not a decision to make. No dead or commented-out code, no
  `TODO`s for work the task covers, no magic numbers/strings.
- **TSDoc on every exported symbol** you add or change (`tsdoc.md`).

## React conventions (inherited)

- **Function components only** (the one exception: an error boundary still needs a class — reuse the project's
  existing one). Named export, `PascalCase` function, props interface `<Component>Props` with `readonly` fields,
  destructured in the signature.
- **Rules of hooks and exhaustive deps** — `@react-native/eslint-config` and `eslint-config-expo` both enable
  `react-hooks`; fix the dependency, never suppress the rule. Custom hooks start with `use`.
- `useCallback`/`useMemo` only with a reason — a prop of a memoized child, a `FlatList` `renderItem`/
  `keyExtractor`, a dependency of another hook — or not at all where the React Compiler is enabled
  (`babel-plugin-react-compiler`, or `experiments.reactCompiler` in an Expo app config).
- **State colocated** as low as possible; derive instead of duplicating; `useReducer` once transitions depend on
  each other; server data is not component state (`state-and-data.md`).
- **Keys** from stable data ids, never the array index for a dynamic list.

## The default-export exception

- **expo + Expo Router**: every route file under `app/` (`app/(tabs)/index.tsx`, `app/orders/[id].tsx`,
  `_layout.tsx`, `+not-found.tsx`) must **default-export** its screen/layout component — Expo Router resolves the
  route by file location. Give it a real `PascalCase` name (`export default function OrderDetailScreen()`), keep
  the file thin, and export nothing else from it except what Expo Router itself reads (`unstable_settings`,
  `ErrorBoundary`, `generateStaticParams` on web). Non-route code never lives under `app/` — every file there
  becomes a route.
- **metro**: `App.tsx` is default-exported by the CLI template and imported by `index.js`
  (`AppRegistry.registerComponent(appName, () => App)`) — keep that; every other module uses named exports.
  React Navigation screens are ordinary components → named exports.
- Say in the report whenever a default export was used.

## Project layout — detect, don't assume

- **expo**: routes in `app/` (or `src/app/`); shared code in the folders the project already has (`components/`,
  `hooks/`, `constants/` in the default template; `src/features/<name>/` in this catalog's scaffold). Assets in
  `assets/`. App config in `app.json` or `app.config.{ts,js}` (`expo.md`).
- **metro**: `App.tsx` + `index.js` at the app root; app source under `src/` (`src/screens/`, `src/navigation/`,
  `src/features/<name>/`, or whatever exists); native projects in `android/` and `ios/` (`metro.md`).
- Prefer feature folders (`src/features/orders/OrderList.tsx`, `useOrders.ts`, `index.ts` re-exporting the
  feature's public surface) for new feature code when the project has no stronger convention; promote to shared
  `components/`/`hooks/`/`lib/` only when a second feature needs it. Don't deep-import another feature's internals.
- Use the path alias the project's `tsconfig.json` (and, for metro, `babel-plugin-module-resolver`) already
  defines (`@/*` in the Expo template). **Never add a new alias for one task** — in a metro project an alias needs
  matching Babel *and* TypeScript config, and a mismatch only fails at bundle time.

## Platform-specific code

- Platform files — `Foo.ios.tsx` / `Foo.android.tsx` (plus `Foo.tsx` or `Foo.native.tsx` as the fallback, and
  `Foo.web.tsx` for an Expo web target) — when the divergence is substantial; import them as `./Foo` and Metro
  picks the right one. Both platform files must export the **same** named symbols with the same types (keep a
  shared `Foo.types.ts` when the props are non-trivial), or `tsc` sees only one of them and the other drifts.
- `Platform.select({ ios: ..., android: ..., default: ... })` / `Platform.OS` for a small, local difference.
- Never guess a platform behaviour — a prop documented as iOS-only or Android-only silently no-ops on the other
  platform; check the component's docs page.

## Lint and format

- ESLint config: `@react-native/eslint-config` (metro, `.eslintrc.js` or flat `eslint.config.js`) or
  `eslint-config-expo` (expo, `eslint.config.js` flat config); Prettier via `.prettierrc.js`/`.prettierrc`. Write
  code that already satisfies them (`react-native/no-inline-styles` is a warning in the CLI config — use
  `StyleSheet`). Never edit the lint config to make a task pass.
