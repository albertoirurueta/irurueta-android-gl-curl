# iOS interop — how Kotlin looks from Swift, and Swift in `iosApp`

The shared module is consumed by `iosApp` as an Apple framework (`Shared`, static, built by
`./gradlew :shared:embedAndSignAppleFrameworkForXcode` from Xcode's pre-build phase). By default Kotlin is exported
through an **Objective-C header** (`Shared.h`), and Swift sees Kotlin through that Objective-C lens. Every `public`
declaration in the exported module(s) is part of that API — design it for Swift callers, not just Kotlin ones.

## Look at the real header instead of guessing

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64     # macOS only
# → shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h
```

(Task name and path verified on the catalog layout; use `:composeApp:` on the older layout and the framework
`baseName` the module configures.) Read it — never edit it — when unsure how a declaration surfaces in Swift.
`swift_name(...)` attributes show the exact Swift spelling. Framework doc comments in the header also state which
exceptions a method converts to `NSError`.

## How Kotlin constructs map to Swift (Objective-C export)

Verified against a generated header unless marked otherwise:

| Kotlin | Swift sees |
|---|---|
| `class Foo` | `class Foo` (prefixed `SharedFoo` in Objective-C; Swift name `Foo`). Final — Swift can't subclass unless the class is `open`. |
| `interface Repo` | a protocol `Repo` — Swift types can implement it (useful for fakes and Swift-implemented services passed to Kotlin). |
| top-level function/property in `Foo.kt` | a static on a class `FooKt` (`FooKt.doThing()`); a file named `Platform.ios.kt` becomes `Platform_iosKt`. |
| extension on a Kotlin **class** | an Objective-C category: callable as a member, `viewModel.watchState(onEach:)`. |
| extension on an **interface** or generic receiver | a static on the file class taking the receiver first: `InteropKt.watchAny(flow, onEach:)`. |
| `sealed interface`/`sealed class` | a protocol/base class plus one class per subtype (`LoadStateContent`, `LoadStateFailed`); a `data object` subtype is a singleton accessed as `LoadStateLoading.shared`. **No exhaustive `switch`** — Swift needs `if let x = s as? LoadStateContent` / `switch s { case let c as LoadStateContent: … default: … }`. |
| `enum class Mode { FAST, SLOW }` | a class with static instances `Mode.fast`, `Mode.slow`, plus `entries`; not a Swift `enum`, so `switch` needs a `default`. |
| `data class` | a class with properties, `doCopy(...)` (since `copy` is reserved in Objective-C), `isEqual`/`hash`. |
| generic class `Box<T>` | a lightweight generic `Box<T>` — generics survive on **classes**, are **erased** on interfaces (`StateFlow<T>` arrives as `Kotlinx_coroutines_coreStateFlow` with `value: Any?`) and on functions. |
| default arguments | **dropped**: `fun greet(name: String, excited: Boolean = false)` is `greet(name:excited:)` — Swift must pass every argument. |
| `suspend fun refresh(): Int` | `refresh(completionHandler:)` plus a Swift `async` variant (`try await vm.refresh()`); results are boxed (`KotlinInt`). |
| `Flow`/`StateFlow` | an interface Swift can't usefully iterate — needs SKIE, KMP-NativeCoroutines, or a hand-written wrapper (`native-ui.md`). |
| `Int`, `Long`, `Boolean` (non-null) | `Int32`, `Int64`, `Bool`; nullable or generic positions box them (`KotlinInt?`). |
| `List<T>`, `Map<K,V>`, `Set<T>` | `[T]`, `[K: V]`, `Set<T>` (bridged to `NSArray`/`NSDictionary`/`NSSet`). |
| `Unit`-returning function | `Void`. |
| exceptions | fatal unless declared with `@Throws` (see below). |

## Shaping a Swift-friendly API

- **Concrete over generic** at the boundary: a per-screen wrapper beats a generic helper whose type parameter is
  erased.
- **Overloads or parameter objects instead of default arguments** for Swift-facing functions with optional
  parameters (unless the project uses SKIE, which restores default arguments, or Swift export).
- **Sealed result/state types are fine**, but give Swift an easy way to branch: a property on the base type
  (`val isLoading: Boolean`) or a Swift extension in `iosApp` that maps the Kotlin subtypes onto a Swift `enum` — keep
  that mapping in one place.
- **No `Flow` parameters or returns on new Swift-facing APIs** unless the project has a Flow bridge; expose
  `StateFlow` + a wrapper, or callbacks.
- **Expected failures as values** (a sealed result) rather than exceptions.
- **`@Throws(...)`** on every Swift-facing function that can throw something Swift should handle. A `suspend`
  function without `@Throws` converts only `CancellationException`; with `@Throws`, list `CancellationException::class`
  too, or cancellation becomes fatal (`mandatory-rules.md`). The listed types must match the KDoc `@throws`.
- **Names**: `@ObjCName(swiftName = "describe")` (opt-in `kotlin.experimental.ExperimentalObjCName`) when the Kotlin
  name is unidiomatic in Swift or collides with an Objective-C/NSObject member (`description`, `hash`, `init…`,
  `copy…`, `new…`). Verified: `@ObjCName(swiftName = "describeIt")` on `fun description2()` produced
  `describeIt()` in Swift.
- **Hide Kotlin-only API**: `@HiddenFromObjC` (opt-in `kotlin.experimental.ExperimentalObjCRefinement`) removes a
  public declaration from the header (verified absent); `@ShouldRefineInSwift` exports it with a `__` prefix so a
  Swift extension in `iosApp` can wrap it with a nicer API. Better still: make it `internal` if nothing outside the
  module needs it.
- **Keep the header small**: every public declaration (and everything its signatures reference) is exported.
  `internal` is the default for implementation details.
- Don't expose platform-agnostic types Swift already has a better form of without reason (e.g. return `String`,
  not a Kotlin value class wrapper, from a Swift-facing API — value classes are exported as their underlying type
  anyway).

## Flow and coroutine bridges

Use what the project has (detect the Gradle plugin in the shared module's build script):

- **SKIE** (`co.touchlab.skie`): sealed classes/enums become exhaustively switchable Swift enums (via `onEnum(of:)`),
  `Flow`/`StateFlow` become `AsyncSequence`s, `suspend` functions become cancellable `async` functions, default
  arguments are supported. Write Kotlin normally; Swift uses the SKIE forms.
- **KMP-NativeCoroutines** (`com.rickclephas.kmp.nativecoroutines`): annotate Swift-facing `suspend`/`Flow`/`StateFlow`
  declarations with `@NativeCoroutines`/`@NativeCoroutinesState` (the KSP plugin generates the Swift-callable
  variants); Swift uses the library's `asyncFunction(for:)`/`asyncSequence(for:)`. A new Swift-facing coroutine API
  without the annotation is invisible to its Swift helpers — add it.
- **Neither**: completion-handler/`async` for `suspend` (built in) and hand-written wrappers for flows
  (`native-ui.md`). Don't add a bridge to solve a task — report it.

## Swift export (experimental)

Kotlin also ships an experimental **Swift export** that generates Swift API directly instead of an Objective-C header
(the Kotlin Gradle plugin registers an `embedSwiftExportForXcode` task — present in the verified build). It is opt-in
(`kotlin { swiftExport { … } }` plus switching Xcode's build phase to that task) and its coverage of Kotlin features is
still incomplete. Use it only if the project already does; never switch a project to it as part of a task. If it's on,
the mapping table above doesn't apply — read the generated Swift interface instead.

## Framework integration — don't break it

- The Xcode pre-build phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode` (it reads Xcode's environment
  variables — `CONFIGURATION`, `SDK_NAME`, `ARCHS` — and only works when invoked from an Xcode build). Its framework
  search path and `-framework Shared` linker flag live in `iosApp/project.yml` (XcodeGen). Changing the framework's
  `baseName`, `isStatic`, or exported modules is a build change with Xcode-side consequences — out of scope unless the
  task asks.
- **Never edit the generated framework or header**, never commit it, and never copy Kotlin-generated code into
  `iosApp`.
- Resources for Compose Multiplatform are packaged by the Gradle plugin as part of that same task; don't add them to
  the Xcode project by hand.

## Swift in `iosApp`

When the task writes Swift (the SwiftUI host, a Swift implementation of a Kotlin interface, XCTest tests):

- Swift 6 language conventions as in `iru-swift-code-one-task/reference/code-style.md` (value types first, `let`,
  `guard` early exits, access control, `@MainActor` for UI-bound types, no force unwraps/force casts except where a
  Kotlin generic was erased and the type is certain — comment why).
- `import Shared` (the framework `baseName`) to reach Kotlin; Kotlin classes are reference types — treat a Kotlin
  `data class` received in Swift as immutable.
- A Swift class implementing a Kotlin protocol (e.g. a Keychain-backed `SecureStore` the shared module declares as
  an interface) is passed into Kotlin through a factory/initializer — keep that wiring in the app's composition root
  (the `@main App` or a `DependencyContainer`), not scattered through views.
- Kotlin `suspend` calls from Swift: `try await` from a `Task { }` or `.task { }`; a `Task` started from a view is
  cancelled with the view — a cancelled Kotlin coroutine surfaces as a thrown error (only if `CancellationException`
  is converted — see above); handle it as cancellation, not as a failure to show the user.
- Swift files are picked up by `iosApp/project.yml`'s source globs; regenerate with `xcodegen generate` (from
  `iosApp/`) after changing `project.yml`. Never hand-edit the generated `iosApp.xcodeproj`.
- DocC `///` comments on public/internal Swift types and members (`kdoc.md`).
