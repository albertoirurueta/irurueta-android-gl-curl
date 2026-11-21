# Project structure — modules, source sets, and where code goes

## The layouts this skill meets

Detect which one the repository uses (`settings.gradle.kts` `include(...)` plus each module's `plugins {}` block); never
restructure one into another as part of a task.

1. **Catalog scaffold (`iru-setup-kmp-app`)** — the default:
   - `shared/` — the KMP module (`org.jetbrains.kotlin.multiplatform` + an Android plugin, below). Targets: Android,
     `iosArm64`, `iosSimulatorArm64`, producing a static framework named `Shared`. With `ui: compose` it also holds the
     Compose Multiplatform UI (`App()` in `commonMain`, `MainViewController()` in `iosMain`); with `ui: native` it
     holds logic and state only.
   - `androidApp/` — `com.android.application`, the `MainActivity` and (for `ui: native`) the Jetpack Compose UI;
     depends on `:shared`.
   - `iosApp/` — the SwiftUI host app. `iosApp/project.yml` (XcodeGen) generates `iosApp/iosApp.xcodeproj`, which is
     gitignored; sources in `iosApp/iosApp/`, XCTest unit tests in `iosApp/iosAppTests/`, version settings in
     `iosApp/Configuration/Config.xcconfig`. A pre-build script phase runs
     `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.
2. **Older JetBrains wizard layout** — a single `composeApp/` module that is both the KMP module and the Android
   application (`com.android.application` + `androidTarget()`), plus `iosApp/` (often a committed `.xcodeproj`). Some
   variants also have a `shared/` logic module and/or a `server/` module (ignore `server/` — it's not this skill's
   scope; report a task that targets it).
3. **`androidTarget()` + `com.android.library` shared module** — same three-module shape as (1), but the Android
   target is declared with `androidTarget()` and the module applies `com.android.library` with a top-level
   `android {}` block.

### Which Android plugin is the shared module using?

| Shared module applies | Android declared as | Android main / host-test source sets | Compile tasks | Host-test task |
|---|---|---|---|---|
| `com.android.kotlin.multiplatform.library` | `kotlin { android { … } }` (`androidLibrary { … }` on older AGP 8.x) | `androidMain` / `androidHostTest` (exists only when `withHostTestBuilder {}` is configured) | `compileAndroidMain`, `compileAndroidHostTest` | `testAndroidHostTest` |
| `com.android.library` or `com.android.application` | `kotlin { androidTarget() }` + top-level `android { … }` | `androidMain` / `androidUnitTest` (and `androidInstrumentedTest`) | `compileDebugKotlinAndroid`, `compileDebugUnitTestKotlinAndroid` | `testDebugUnitTest` |

The first row was verified locally (see the skill's "Verification notes"); the second row is the long-standing
`androidTarget()` naming from the Kotlin docs and is **unverified locally** against AGP 9. Either way, confirm with
`./gradlew :<module>:tasks --all` rather than trusting this table — task names are what the plugin says they are.

## Source sets and the default hierarchy template

The Kotlin Gradle plugin's **default hierarchy template** creates intermediate source sets automatically from the
declared targets — don't declare `dependsOn` edges by hand unless the module already opted out of the template. For
an Android + iOS app:

```
commonMain
├── androidMain                      (Android: JVM/ART — android.*, java.*)
└── nativeMain
    └── appleMain
        └── iosMain                  (all iOS targets — platform.UIKit, platform.Foundation, …)
            ├── iosArm64Main         (device)
            └── iosSimulatorArm64Main (Apple-silicon simulator)
```

(`compileNativeMainKotlinMetadata`, `compileAppleMainKotlinMetadata`, `compileIosMainKotlinMetadata` exist as tasks in
the verified build — the template is active.) Test source sets mirror the tree: `commonTest`, `iosTest`, the Android
host-test set from the table above. Code for iOS goes in `iosMain` — never in `iosArm64Main`/`iosSimulatorArm64Main`
unless it genuinely differs between device and simulator (essentially never in app code).

Source directories: `<module>/src/<sourceSet>/kotlin/<package path>/`. Compose Multiplatform resources:
`<module>/src/commonMain/composeResources/` (see `compose-multiplatform.md`).

## Deciding the module and source set from a task's text

1. **A path in the task wins**: `shared/src/iosMain/...` → `shared`, `iosMain`. `androidApp/...` → `androidApp`.
   `iosApp/iosApp/...` → the Swift app (a `.swift` file).
2. **Otherwise infer from what's described**:
   - Business rules, validation, mapping, repositories, use cases, networking, persistence, `ViewModel`/state → the
     shared module, `commonMain`.
   - "On Android, …" / an Android framework type (`Context`, `Activity`, Keystore, WorkManager) → `shared`'s
     `androidMain` (behind a common interface) if shared code needs it; `androidApp` if only the Android app needs it.
   - "On iOS, …" / an Apple framework (`UIKit`, `Foundation`, Keychain, `NSUserDefaults`) → `shared`'s `iosMain` if
     shared code needs it; `iosApp` Swift if only the iOS app needs it and it doesn't touch shared code.
   - A Compose screen/component with `ui: compose` → `shared` `commonMain` (`composeApp` on the older layout).
   - A screen with `ui: native` → `androidApp` (Jetpack Compose) and/or `iosApp` (SwiftUI) — see `native-ui.md`.
   - `MainActivity`, the Android manifest, app icon, `versionName` → `androidApp`. `iOSApp.swift`, `Info.plist`,
     `project.yml`, `Config.xcconfig` → `iosApp`.
3. **Ambiguous**: default to `commonMain` of the shared module and say so in the report.

A single task often spans source sets (a common interface + two actual implementations + a Swift caller). Do the work
in this order: `commonMain` (interface/types) → `androidMain` → `iosMain` → `androidApp` / `iosApp` callers.

## `expect`/`actual` vs interface + DI

Both connect common code to platform APIs. Choose deliberately:

| Use | When |
|---|---|
| **Interface in `commonMain` + implementation per platform, injected** (constructor or DI module) | The default for anything with behavior, state, or dependencies: secure storage, a database driver factory, a location provider, an analytics sink. Testable in `commonTest` with a fake; implementations can take platform dependencies (`Context`) in their constructors; more than two implementations are possible. |
| **`expect fun` / `expect val`** | A small, stateless, platform fact or factory with no dependencies: `platformName()`, `currentTimeMillis()`-style shims the stdlib lacks, `createHttpEngine()`. Stable language feature. |
| **`expect class` / `expect object`** | Only when a type must exist in common code but be *implemented* by a platform type, and an interface can't express it (e.g. Room's `expect object AppDatabaseConstructor : RoomDatabaseConstructor<…>`). Expect/actual **classes are Beta**: the compiler warns unless the build passes `-Xexpect-actual-classes`. If the module doesn't already pass it, prefer an interface. |
| **`actual typealias`** | The platform already has the exact type you need: `expect class AtomicRef<T>` → `actual typealias AtomicRef<T> = java.util.concurrent.atomic.AtomicReference<T>`. The aliased type must match the `expect` signature member-for-member. Only for the target where a matching type exists; the other target writes a real `actual class`. |

Rules for either:

- An `actual` lives in the same package as its `expect`, with the same name, in a file carrying the platform suffix.
- `expect` declarations carry the KDoc; actuals don't repeat it unless their behavior differs observably.
- Never add `expect`/`actual` for something the stdlib or an existing multiplatform dependency already provides.
- Every `expect` needs an `actual` for **every** target of the module (both iOS targets are covered by one in
  `iosMain`). The compile-check of each target is what catches a missing one — that's why Step 5 compiles both.
- An `expect fun` with a default value for a parameter: defaults go on the `expect` only.

## Module dependencies

- `androidApp` → `implementation(project(":shared"))`; never the reverse.
- iOS consumes the shared module only as the exported framework; Swift code never reaches into `build/`.
- If more shared modules exist (`core`, `feature-x`), the framework-exported module re-exports them with
  `export(project(":core"))` in the framework config only when Swift needs their types directly — adding an
  `export(...)` is a public-API decision for the iOS side, not something to do casually.
- Dependencies are declared per source set in the module's `kotlin { sourceSets { commonMain.dependencies { … } } }`,
  versions in `gradle/libs.versions.toml`. Never hardcode a version in a build script when the catalog exists.
