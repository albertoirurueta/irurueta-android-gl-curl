# SwiftUI

Conventions specific to SwiftUI views and view models in an app target. Everything in `code-style.md`, `docc.md`,
and `testing.md` still applies; this file adds what's specific to view composition and Observation.

## Small, composed views

A screen is a tree of small views, each responsible for one piece of the UI, not one large `body` with deeply
nested `if`/`HStack`/`VStack` chains. Extract a subview once a `body` needs a comment to explain a section — that
comment is naming the view you should have extracted:

```swift
struct OrientationBadge: View {

  let orientationDegrees: Int
  let onRecalculate: () -> Void

  var body: some View {
    HStack {
      Text("\(orientationDegrees)°")
      Button(action: onRecalculate) {
        Image(systemName: "arrow.clockwise")
      }
    }
  }
}
```

This is the UI contract's component shape (`ui-contract.md`, mapped in `ui-implementation.md`): a component takes
one value-type state (or a few `let`s) plus `on<Intent>` closures, never a whole view model; `Binding` only for
form-field controls; content is passed in with `@ViewBuilder`, not by wrapping another screen. Decompose a design into
`Screen › Region › Component` before writing the first view.

A view struct that only reads its own stored properties and has no identity of its own needs no explicit `init`
— the memberwise initializer SwiftUI synthesizes is enough; write one explicitly only to give a parameter a
default value or to validate/transform an input.

## `@Observable` view models, `@State`, `@Bindable`, `@Environment`

- **`@Observable`** (the `Observation` framework macro, not the older `ObservableObject` protocol) on a
  reference type that holds a screen's mutable state — it tracks per-property access automatically, so a view
  only re-renders for the specific properties it actually reads, unlike `ObservableObject`'s `@Published`, which
  invalidates on every change to any published property.
- **`@State`** in the view that owns an `@Observable` model's lifetime — creates and keeps it alive across
  recomposition:

  ```swift
  struct CounterScreen: View {

    @State private var viewModel: CounterViewModel

    init(counter: Counter) {
      _viewModel = State(initialValue: CounterViewModel(counter: counter))
    }

    var body: some View {
      Button("Increment: \(viewModel.displayValue)") {
        Task { await viewModel.increment() }
      }
    }
  }
  ```

- **`@Bindable`** when a child view needs a two-way `Binding` into an `@Observable` model it doesn't own (passed
  in from a parent) — `@Bindable var viewModel: CounterViewModel` inside the child, then `$viewModel.someField`
  for a binding to one of its properties.
- **`@Environment`** to read a value injected higher in the view tree (the app's own `@Observable` services, or a
  SwiftUI environment key) instead of threading a dependency through every intermediate view's initializer —
  reach for it for something genuinely ambient (a theme, a session, a feature flag), not as a shortcut around
  passing an explicit parameter for something only one branch of the tree needs.
- **A view model that touches UI state is `@MainActor`** — see `code-style.md`'s strict-concurrency section;
  `@Observable` doesn't imply main-actor isolation on its own, so an `@Observable` type meant to back a view
  still needs the `@MainActor` attribute explicitly.

## Lists and grids

Any data-driven or potentially long collection is a `List`, or a `LazyVStack`/`LazyHStack`/`LazyVGrid` inside a
`ScrollView`, with `ForEach` over `Identifiable` item states from the view model — never `ForEach` inside a plain
`VStack`/`HStack` or a `ScrollView` without a lazy stack. Paging, selection and the item component are in
`ui-implementation.md`'s "Collections".

## `NavigationStack`

Use `NavigationStack` (not the deprecated `NavigationView`) for a push/pop hierarchy, with a typed path
(`NavigationStack(path: $path) { ... }`, `path: NavigationPath` or `[Route]` for a `Hashable`/`Codable` route
enum) when the app needs programmatic navigation (deep links, restoring state) — a plain, uncontrolled
`NavigationStack { }` with `.navigationDestination(for:)` is enough when the app only ever pushes in response to
direct user taps.

## `#Preview`

Every view meant to be visually reviewed gets at least one `#Preview` alongside it — the macro that replaced
`PreviewProvider`:

```swift
#Preview {
  OrientationBadge(orientationDegrees: 90, onRecalculate: {})
}
```

Add a second, named preview (`#Preview("Dark") { ... }`) whenever the view has non-trivial color/contrast logic
worth checking in dark mode, or needs representative sample data that isn't obvious from the default preview.

## Platform conditionals

Guard platform-specific code with `#if os(...)` at the narrowest scope that needs it — a whole file only when the
entire file is platform-specific, a single property/modifier otherwise:

```swift
var body: some View {
  content
#if os(watchOS)
    .navigationBarTitleDisplayMode(.inline)
#endif
}
```

For behavior that varies across more than a couple of platforms, prefer an extension providing a single
cross-platform API (e.g. a `Color` initializer bridging `UIColor`/`NSColor`) over `#if os(...)` scattered through
every call site — see `app-structure.md` for where such cross-platform shims belong in a multi-target app.

## macOS scenes and view models

Mac-only scene work — `WindowGroup`/`Window`/`UtilityWindow`/`Settings`/`MenuBarExtra`, `openWindow`, `Commands`
and focused values, sandboxed file access — is in `macos.md`. Whether a screen gets a view model at all (and where
it lives — `Packages/Core/Sources/Core/ViewModels/`) is decided by `architecture.md`: no pass-through view models.
iOS/iPadOS scene concerns (`scenePhase`, multiple windows, size classes, `.sidebarAdaptable`, Dynamic Type) are in
`ios.md`; visionOS windows, volumes, immersive spaces and ornaments in `visionos.md`.

## Testing

See `testing.md`'s Swift Testing section for a `@MainActor`-marked `@Suite`/`@Test` exercising a view model
directly (the common, fast case — assert on the model's published state after calling its methods, without
touching SwiftUI's rendering at all). Reach for XCUITest (also in `testing.md`) only for an actual UI-automation
scenario — tapping through a real running app — not as the default way to test a view's logic.

On top of those, a new or changed component or screen gets the UI tests of `ui-implementation.md`: an
`ImageRenderer` capture per visual state × `.ui-quality.yml` direction × theme (SwiftUI-native content only — `List`,
`TextField`, `Toggle` render as placeholders and are captured by XCUITest instead), and the screen's XCUITest
captures plus `performAccessibilityAudit()`. Each `#Preview` state is a good list of the states to capture.
