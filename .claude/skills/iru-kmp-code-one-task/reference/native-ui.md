# Native UI over shared state (`ui: native`)

With `ui: native` the shared module holds logic and presentation state only; Android UI is Jetpack Compose in
`androidApp`, iOS UI is SwiftUI in `iosApp`. Both consume the **same** shared `ViewModel`/state holder. Read
`ios-interop.md` too whenever the task changes something Swift calls.

## The shared side: one presentation API, two consumers

- A screen's presentation logic is a shared `ViewModel` (multiplatform `androidx.lifecycle.ViewModel`, see
  `shared-logic.md`) or a plain state-holder class in `commonMain`, exposing:
  - `val state: StateFlow<XUiState>` — one immutable `data class` (or sealed interface) per screen;
  - plain, non-`suspend` functions for user intents (`fun onRetry()`, `fun onQueryChange(query: String)`), which
    launch work in `viewModelScope` themselves.
- Keep intent functions non-`suspend` and state as a single `StateFlow`: it is the shape both Compose and SwiftUI
  consume most easily, and it avoids exporting `suspend` functions to Swift at all for the common case.
- No platform types, no localized strings (return enums/error codes; each UI maps them to its own string resources),
  no formatting for display that depends on locale (do it in the UI layer or behind an injected formatter).

## Android — Jetpack Compose in `androidApp`

- Same conventions as `iru-android-code-one-task`'s Compose guidance: stateless composables, state hoisting,
  `modifier: Modifier = Modifier` first optional parameter, Material 3 theme, `@Preview` with sample state.
- Obtain the shared `ViewModel` with `viewModel { XViewModel(deps) }` / `viewModel(factory = …)` from
  `androidx.lifecycle.viewmodel.compose`, or `koinViewModel()` if the project uses Koin; collect with
  `collectAsStateWithLifecycle()`.
- Strings in `androidApp/src/main/res/values/strings.xml`; never hardcode user-facing text.
- Android-only concerns (permissions UI, `Activity` results, notifications) live in `androidApp`, not in the shared
  module, unless shared logic needs the result — then it's an interface the app implements.
- Tests: `androidApp/src/test/` JVM unit tests (JUnit 4 + MockK/Robolectric per the Android catalog conventions) for
  any app-only logic. Compose UI tests for screens and components run on the JVM too — Robolectric +
  `createComposeRule()` + `@GraphicsMode(NATIVE)` in `androidApp/src/test`, capturing screenshots and running the
  accessibility check (`ui-implementation.md`); only flow tests (`androidApp/src/androidTest`) need a
  device/emulator the task-level skill never starts.
- Lists: `LazyColumn`/`LazyRow`/`LazyVerticalGrid` with `key`/`contentType` over the shared state's items
  (`ui-implementation.md`).

## iOS — SwiftUI in `iosApp`

### Observing a `StateFlow` from Swift

Swift can't collect a Kotlin `Flow` directly through the Objective-C header. Use what the project already has, in
this order of preference:

1. **SKIE** (`co.touchlab.skie` Gradle plugin applied to the shared module): `StateFlow<T>` is exported as a Swift
   `AsyncSequence`; observe with `for await state in viewModel.state { … }` inside a `.task { }` modifier, or SKIE's
   SwiftUI `Observing` helper. `suspend` functions become `async throws`.
2. **KMP-NativeCoroutines** (`com.rickclephas.kmp.nativecoroutines` + `@NativeCoroutines`/`@NativeCoroutinesState`
   annotations on the Kotlin side): use its Swift package's `asyncSequence(for: viewModel.stateFlow)` /
   `@StateViewModel`/`@ObservedViewModel` helpers.
3. **Neither** (the plain Objective-C export): a small, **non-generic**, per-screen Kotlin wrapper in `iosMain` that
   collects the flow on the main dispatcher and calls a Swift-supplied callback, returning a handle Swift uses to
   cancel, plus a plain getter for the current value:

   ```kotlin
   // iosMain — exported helpers; Swift holds the Cancellable and cancels it on disappear.
   /** Handle returned to Swift to stop an observation started by [watchState]. */
   public fun interface Cancellable { public fun cancel() }

   /** Current state, typed (StateFlow.value reaches Swift as an untyped `Any?` because interface generics are erased). */
   public val ProfileViewModel.currentState: ProfileUiState get() = state.value

   /** Calls [onEach] on the main thread for every new [ProfileUiState] until the returned handle is cancelled. */
   public fun ProfileViewModel.watchState(onEach: (ProfileUiState) -> Unit): Cancellable {
       val job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { state.collect(onEach) }
       return Cancellable { job.cancel() }
   }
   ```

   An extension on a Kotlin **class** is exported as an Objective-C category, so Swift calls it like a member:
   `viewModel.watchState { … }`, `viewModel.currentState` (verified in a generated `Shared.h`). A generic helper
   (`fun <T> StateFlow<T>.watch(...)`) is an extension on an *interface*, so it is exported as a static function on
   the file's class instead (`InteropKt.watch(flow, onEach:)`) with `T` erased to `Any?` — forcing casts on every
   call. Keep these wrappers concrete.

Don't add SKIE or KMP-NativeCoroutines to solve a task — that's a build/tooling decision (report it as a blocker or a
suggestion).

### Owning the shared `ViewModel` in SwiftUI

- Wrap it in a Swift `@Observable` (or `ObservableObject`) class owned with `@State` (or `@StateObject`) by the view
  that represents the screen, so the Kotlin `ViewModel` lives exactly as long as the screen:

  ```swift
  @MainActor
  @Observable
  final class ProfileModel {
      private let viewModel: ProfileViewModel
      private(set) var state: ProfileUiState
      private var watcher: Cancellable?

      init(viewModel: ProfileViewModel) {
          self.viewModel = viewModel
          self.state = viewModel.currentState
      }

      func start() {
          watcher = viewModel.watchState { [weak self] in self?.state = $0 }
      }
      func stop() { watcher?.cancel(); watcher = nil }
      func retry() { viewModel.onRetry() }

      deinit { /* MainActor-isolated cleanup happens in stop(); call viewModel.clear() here if the project exposes it */ }
  }
  ```

  Start observing in `.task { }`/`.onAppear`, stop in `.onDisappear`. A Kotlin `ViewModel` used outside an Android
  `ViewModelStore` never gets `onCleared()` automatically — the iOS side must trigger cleanup (a Kotlin
  `fun clear()` wrapper in `iosMain` that calls the protected cleanup path, a `ViewModelStore` owned by the Swift
  model, or the helper the project's interop library provides) or `viewModelScope` coroutines leak.
- Avoid force-casts (`as!`): they are only needed where generics were erased, and the concrete wrappers above remove
  that need. When unsure how a declaration looks from Swift, read the generated header
  (`shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h` after
  `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`) rather than guessing — see `ios-interop.md`.
- UI updates on the main actor: mark the Swift model `@MainActor`; callbacks dispatched on Kotlin's
  `Dispatchers.Main` arrive on the main thread.

### SwiftUI conventions

- Small views, `let` inputs, `@State` for view-local state, `@Binding` for child edits, computed `body` free of side
  effects; `.task { }` for async work tied to the view's lifetime. Split each screen into a root view that owns the
  model and a stateless view taking `state` + closures (`ui-implementation.md`), so tests can render every state.
- Collections: `List(state.items, id: \.id)` or `LazyVStack`/`LazyVGrid` inside a `ScrollView` — never a plain
  `VStack { ForEach }` over shared data.
- User-facing strings via the String Catalog (`Localizable.xcstrings`) — `Text("profile.title")` with a key, not
  Kotlin-provided English.
- Accessibility: `.accessibilityLabel`, `.accessibilityHint`, Dynamic Type-friendly fonts (`.font(.body)`), no fixed
  frames that clip scaled text.
- New Swift files belong to the `iosApp` target through `iosApp/project.yml` sources globs (XcodeGen) — usually a
  new file under an existing source path needs no manifest change; adding a new folder outside those paths, a new
  target, or a resource type needs a `project.yml` edit, then `xcodegen generate`. Never hand-edit the generated
  `iosApp.xcodeproj`. If the repository has a committed, hand-maintained `.xcodeproj` and no `project.yml`, a new Swift
  file must be added to the project's target — report that the file needs adding in Xcode if you can't do it safely.
- Tests: XCTest in `iosApp/iosAppTests/` (`@testable import iosApp`, `import Shared`) for Swift-side logic such as the
  observable model's mapping; drive the Kotlin `ViewModel` with fake dependencies constructed from Swift (Swift can
  implement exported Kotlin interfaces). See `testing.md`. SwiftUI screens/components also get `ImageRenderer`
  screenshot tests through `iosAppTests/UIScreenshots.swift` (`ui-implementation.md`).
