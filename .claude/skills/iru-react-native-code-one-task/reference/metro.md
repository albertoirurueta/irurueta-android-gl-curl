# Metro toolchain (bare React Native CLI)

Rules for a project with `react-native` in `dependencies` and **no** `expo` — created by
`npx @react-native-community/cli@latest init`, with committed `android/` (Gradle) and `ios/` (Xcode + CocoaPods)
projects and Metro run directly (`npx react-native start`). Read together with `code-style.md`;
`native-modules.md` covers Turbo Modules and Fabric components in detail.

(An Expo app also bundles with Metro — "metro" here names the toolchain without the Expo framework.)

## Project shape (React Native 0.87 CLI TypeScript template, October 2026)

```
App.tsx                 # default-exported root component (SafeAreaProvider in the template)
index.js                # AppRegistry.registerComponent(appName, () => App) — appName from app.json
app.json                # { "name", "displayName" } — name must match the native registration
__tests__/App.test.tsx  # Jest, preset "@react-native/jest-preset" (jest.config.js)
babel.config.js         # presets: ['module:@react-native/babel-preset']
metro.config.js         # mergeConfig(getDefaultConfig(__dirname), config) from @react-native/metro-config
.eslintrc.js            # extends '@react-native' (ESLint 8 legacy config in the template)
.prettierrc.js          # singleQuote, trailingComma: 'all', arrowParens: 'avoid'
tsconfig.json           # extends @react-native/typescript-config (strict, types: ["jest"])
android/                # Gradle (Groovy DSL): settings.gradle, build.gradle, gradle.properties, app/build.gradle,
                        #   app/src/main/java/<package>/MainActivity.kt + MainApplication.kt
ios/                    # Podfile, <AppName>.xcodeproj, <AppName>.xcworkspace (after pod install),
                        #   <AppName>/AppDelegate.swift, Info.plist, PrivacyInfo.xcprivacy
```

Older projects may have `App.tsx` + `MainApplication.java`/`AppDelegate.mm`, Kotlin DSL Gradle files, or a flat
ESLint config — detect, don't assume, and follow what exists.

## Navigation: React Navigation

- The CLI template ships no navigator; the project's own choice is usually **React Navigation** (`@react-navigation/
  native` + `@react-navigation/native-stack` / `bottom-tabs`, with `react-native-screens` and
  `react-native-safe-area-context`). Follow whichever API the project already uses:
  - **Static API** (`createNativeStackNavigator({ screens: { Home: HomeScreen } })` + `createStaticNavigation`) —
    types come from `StaticParamList<typeof RootStack>` and the `RootNamespace` declaration merge.
  - **Dynamic API** (`<Stack.Navigator><Stack.Screen name="Home" component={HomeScreen} /></Stack.Navigator>`) —
    declare a `RootStackParamList` type (`{ Home: undefined; Order: { readonly id: string } }`) and type screens
    with `NativeStackScreenProps<RootStackParamList, "Order">`; declare it globally once
    (`declare global { namespace ReactNavigation { interface RootParamList extends RootStackParamList {} } }`) so
    `useNavigation()` is typed. Never type navigation as `any`.
- Add a screen to the existing navigator; don't introduce a second `NavigationContainer` (there is exactly one, at
  the root) or restructure navigators unless the task says so.
- Route params are serializable values only (ids, not objects/functions) — they persist in navigation state and
  arrive from deep links. Validate params that a deep link can reach (`security.md`).
- Deep linking: the `linking` prop/config on the container (prefixes + path config) — plus the native side
  (`ios/<App>/Info.plist` URL types / associated domains, `AndroidManifest.xml` intent filters), edited together.

## Metro, Babel, and module resolution

- `metro.config.js` merges onto `getDefaultConfig(__dirname)` — add only what the task needs (an extra
  `resolver.assetExts`/`sourceExts` entry, `watchFolders` for a monorepo package) and keep the `mergeConfig` shape.
  Metro config changes need a Metro restart with `--reset-cache`; say so in the report.
- Path aliases need **both** `babel-plugin-module-resolver` in `babel.config.js` and `paths` in `tsconfig.json` —
  only use the alias that already exists; never add one for a single task.
- Babel plugins that must be last (`react-native-worklets/plugin` for Reanimated 4, formerly
  `react-native-reanimated/plugin`) stay last.

## Committed native code — you own it

`android/` and `ios/` are source code here: edit them directly when the task requires it, keep both platforms in
step, and never leave one building and the other not. The group skill builds whichever side the bucket touched.

### Android (`android/`)

- Gradle root is `android/` (`./gradlew` lives there, not at the repository root); the app module is `:app`.
  React Native's Gradle plugin (`com.facebook.react`) handles bundling, Hermes, Codegen, and autolinking
  (`autolinkLibrariesWithApp()` in `app/build.gradle`, `autolinkLibrariesFromCommand()` in `settings.gradle`).
- `android/gradle.properties` holds `newArchEnabled=true`, `hermesEnabled=true`, `reactNativeArchitectures`,
  `edgeToEdgeEnabled` — don't flip them as a side effect. SDK levels live in `android/build.gradle` `ext`.
- New Kotlin code goes under `android/app/src/main/java/<package>/` (Kotlin preferred over Java for new files, the
  template is Kotlin). Permissions and intent filters in `android/app/src/main/AndroidManifest.xml` — request only
  what the task needs, and runtime-request dangerous permissions (`PermissionsAndroid`) from JS.
- `versionName`/`versionCode`/`applicationId` in `android/app/build.gradle` belong to the release process
  (`iru-react-native-bump-version`) — never change them in a feature task.
- Native unit tests (when the task adds native logic worth testing): JUnit under `android/app/src/test/java/…`,
  run by `./gradlew testDebugUnitTest` from `android/` in the group skill.

### iOS (`ios/`)

- `ios/Podfile` drives CocoaPods (`use_native_modules!` autolinks, `use_react_native!`); always open/build the
  **`.xcworkspace`**, never the `.xcodeproj`. After adding a native dependency or a Codegen spec, `pod install`
  (`cd ios && bundle exec pod install` when a `Gemfile` exists) must run — the group skill does it; this skill
  only notes that it's needed. Never commit `ios/Pods/` (unless the project already does) or edit generated
  files under it.
- New native files must be **added to the Xcode project** (`project.pbxproj`) to compile — creating a `.swift`/`.mm`
  file on disk is not enough. Prefer putting app-level native modules in a local pod/library or a
  `codegenConfig`-driven module the Podfile already picks up; if a file has to be added to the app target, edit
  `project.pbxproj` carefully (file reference + build file + group + sources phase) or report it as needing Xcode.
- `Info.plist` usage descriptions (`NSCameraUsageDescription`, …) are required for any protected resource — a
  missing one crashes on first access. `PrivacyInfo.xcprivacy` must declare required-reason APIs a change starts
  using.
- `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` are release-owned, like the Android version fields.
- Native tests: XCTest in an `<App>Tests` target when present (`xcodebuild test`, simulator-bound — the group skill
  decides whether it can run).

## New Architecture

- React Native ≥ 0.76 enables the New Architecture by default and 0.82+ removed the legacy architecture: Fabric
  renderer, Turbo Modules, bridgeless mode, JSI. New native modules and components are **Turbo Modules / Fabric
  components with Codegen specs** (`native-modules.md`) — never `RCT_EXPORT_MODULE` bridge-only modules or
  `ReactContextBaseJavaModule` without a spec in new code.
- Libraries must support the New Architecture; check the library's docs (or reactnative.directory) before relying
  on one — which is a dependency decision this skill doesn't make alone.

## Hermes

- Hermes is the JS engine (`hermesEnabled=true`, default in the template). Write standard modern JS/TS; avoid
  engine-specific assumptions (`Intl` coverage is narrower than V8's for some locales — test the formatting you
  rely on). Hermes bytecode is **not** code protection (`security.md`).
