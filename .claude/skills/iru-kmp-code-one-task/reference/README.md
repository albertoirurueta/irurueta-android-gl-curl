# Kotlin Multiplatform development reference

General code agreements for implementing a task in a Kotlin Multiplatform (KMP) mobile app that targets Android and
iOS — the layout `iru-setup-kmp-app` scaffolds (`shared/` KMP module + `androidApp/` + `iosApp/`, with `ui: compose`
for Compose Multiplatform shared UI or `ui: native` for Jetpack Compose + SwiftUI over shared logic), and the
JetBrains wizard's older single `composeApp/` layout. These files exist so `iru-kmp-code-one-task` can load **only**
what the task in front of it actually needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the module and source set first (`iru-kmp-code-one-task` Steps 0–1), then read:

| Read | When |
|---|---|
| `mandatory-rules.md` | **Always.** The non-negotiables: no platform API in `commonMain`, tests in the right source set, no `!!`, KDoc on public API, no secrets in source, no edits to generated code. |
| `code-style.md` | **Always.** Kotlin conventions, explicit API mode where enabled, visibility, naming, no platform types in a `commonMain` public API. |
| `project-structure.md` | **Always.** Modules, the source-set hierarchy and default hierarchy template, where code goes (`commonMain` first), `expect`/`actual` vs interface + DI, `actual typealias`. |
| `testing.md` | The task writes or updates tests — nearly every task. |
| `kdoc.md` | The task adds or changes a public/`internal` class, interface, object, function, or property (Kotlin), or a public Swift type in `iosApp`. |
| `shared-logic.md` | The task touches coroutines/`Flow`, networking (Ktor), serialization, dates, persistence (SQLDelight / Room / DataStore), DI (Koin or manual), a shared `ViewModel`, or non-UI resources. |
| `compose-multiplatform.md` | The task adds or changes a `@Composable` in a KMP module (`ui: compose`): shared UI, `Res` resources, navigation, previews, `MainViewController`, UIKit/SwiftUI interop. |
| `native-ui.md` | The task touches `androidApp` Jetpack Compose UI or `iosApp` SwiftUI over shared state (`ui: native`), or wires a shared `ViewModel`/state holder into either. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a screen, component, list or other UI — the catalog-wide UI contract (verbatim) and its KMP mapping: shared view model state, `Screen › Region › Component` decomposition, `Lazy*`/SwiftUI `List` collections, `runComposeUiTest` on the Android host (Robolectric) and the iOS simulator, `ImageRenderer`/XCUITest for native iOS, the screenshot helpers with the `--android`/`--ios` variant, the accessibility check. |
| `ios-interop.md` | The task changes a declaration Swift calls (any `public` API of the framework module, especially `suspend`, `Flow`, sealed types, generics, exceptions), or writes Swift in `iosApp`. |
| `security.md` | The task touches credentials/tokens, storage of sensitive data, networking/TLS, logging of user data, R8/ProGuard rules, `Info.plist`/ATS, or input from outside the app. |

A task that only changes logic inside one existing `commonMain` function, with no signature change and no UI, needs
the three always-read files plus `testing.md`, and nothing else. Don't read the rest speculatively. Read at most one
of `compose-multiplatform.md`/`native-ui.md` for a given piece of UI — a screen is either shared Compose
Multiplatform UI or a native screen per platform, never both. Any UI task also reads `ui-contract.md` and
`ui-implementation.md`.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here (except `mandatory-rules.md`) is the default for *new* code. If
   the file or source set you're editing consistently does something else, follow it and note the divergence in the
   report rather than restyling untouched code as a side effect of an unrelated task.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They never
   license adding a library (Koin, SKIE, Mokkery, SQLDelight…), an `expect`/`actual` pair, a new target, or a
   "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond compile-checking the source sets it touched, it never runs the test suite,
   coverage, detekt/ktlint/Android Lint/SwiftLint, license headers, a Dokka build, or anything that boots a
   simulator/emulator — the calling `iru-kmp-code-one-task-group` does all of that once per task group.

`mandatory-rules.md` is the exception to rule 1: the code you write follows it even where the surrounding code
doesn't (leave the old code alone and name the pre-existing violation in the report).

## Sources

Compiled from the following, cross-checked against each other and against a throwaway verification build (Kotlin
2.4.21, AGP 9.4.1 `com.android.kotlin.multiplatform.library`, Gradle 9.8.1, Compose Multiplatform 1.12.2 — see the
skill's "Verification notes"), with this catalog's own stated preferences taking precedence where they are stricter
(notably on `!!`, on fakes over mocks, and on keeping `commonMain` free of platform types).

- Kotlin Multiplatform overview: <https://kotlinlang.org/docs/multiplatform/get-started.html>
- Project structure, targets, source sets: <https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html>
- Hierarchical project structure and the default hierarchy template:
  <https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html>
- Expected and actual declarations (incl. `actual typealias`, Beta status of expect/actual classes):
  <https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html>
- Use platform-specific APIs (interfaces + DI vs expect/actual):
  <https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html>
- Adding dependencies / multiplatform libraries: <https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html>
- Android target via the Android-KMP library plugin (`com.android.kotlin.multiplatform.library`):
  <https://developer.android.com/kotlin/multiplatform/plugin>
- Test your multiplatform app: <https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html>
- Interoperability with Swift/Objective-C: <https://kotlinlang.org/docs/native-objc-interop.html>
- Kotlin–Swift interopedia: <https://github.com/kotlin-hands-on/kotlin-swift-interopedia>
- Swift export (experimental): <https://kotlinlang.org/docs/native-swift-export.html>
- iOS integration methods (direct integration, `embedAndSignAppleFrameworkForXcode`):
  <https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html>
- Explicit API mode: <https://kotlinlang.org/docs/api-guidelines-simplicity.html#use-explicit-api-mode>
- Kotlin coding conventions: <https://kotlinlang.org/docs/coding-conventions.html>
- KDoc and Dokka: <https://kotlinlang.org/docs/kotlin-doc.html>, <https://kotlinlang.org/docs/dokka-introduction.html>
- Compose Multiplatform: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform.html>
- Compose Multiplatform resources (`Res`): <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources.html>
- Compose Multiplatform navigation: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation.html>
- Compose Multiplatform ViewModel / lifecycle: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-viewmodel.html>,
  <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-lifecycle.html>
- SwiftUI / UIKit integration: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-swiftui-integration.html>,
  <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-uikit-integration.html>
- Testing Compose Multiplatform UI: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html>
- UI contract and its KMP mapping: `ui-contract.md` (catalog-wide, verbatim) and `ui-implementation.md`'s own
  Sources list.
- Compose Multiplatform accessibility: <https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-accessibility.html>
- Kotlin coroutines (incl. `Dispatchers.IO` on Native) and kotlinx-coroutines-test:
  <https://kotlinlang.org/docs/coroutines-guide.html>, <https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/>
- Ktor client (multiplatform engines, `MockEngine`, certificate pinning per engine): <https://ktor.io/docs/client-engines.html>,
  <https://ktor.io/docs/client-testing.html>
- kotlinx.serialization: <https://kotlinlang.org/docs/serialization.html>; kotlinx-datetime:
  <https://github.com/Kotlin/kotlinx-datetime>
- SQLDelight (multiplatform): <https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/>
- Room for KMP: <https://developer.android.com/kotlin/multiplatform/room>; DataStore for KMP:
  <https://developer.android.com/kotlin/multiplatform/datastore>
- Koin for KMP: <https://insert-koin.io/docs/reference/koin-mp/kmp/>
- Turbine: <https://github.com/cashapp/turbine>; Mokkery: <https://mokkery.dev/>
- SKIE: <https://skie.touchlab.co/>; KMP-NativeCoroutines: <https://github.com/rickclephas/KMP-NativeCoroutines>
- Android Keystore: <https://developer.android.com/privacy-and-security/keystore>; Apple Keychain services:
  <https://developer.apple.com/documentation/security/keychain_services>; App Transport Security:
  <https://developer.apple.com/documentation/bundleresources/information_property_list/nsapptransportsecurity>
- R8 keep rules: <https://developer.android.com/build/shrink-code>
- Sibling references this file set was aligned with: `iru-android-code-one-task/reference/` (Kotlin style, KDoc,
  Compose) and `iru-swift-code-one-task/reference/` (Swift style, SwiftUI, XCTest) in this catalog.
