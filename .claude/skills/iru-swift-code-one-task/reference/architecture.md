# App architecture: MVVM vs MVC, and the testable core

How an Apple app this catalog scaffolds splits its code between the UI-framework-free `Packages/Core` package and the
app target, how to tell whether a repository uses **MVVM** or **MVC**, and the rules for each. It covers macOS desktop
apps (`iru-setup-macos-app`, `ui: swiftui|appkit`, `architecture: mvvm|mvc`) and iOS/iPadOS/visionOS apps
(`iru-setup-apple-app`, SwiftUI or UIKit); the Core/app split and the protocol-typed services apply to every app
project (`app-structure.md`). Read `swiftui.md`, `uikit.md` or `appkit.md` for the UI-side mechanics.

## Detect before writing

Work out three things once (`iru-swift-code-one-task` Step 1), from the repository, never from assumptions:

1. **Which app target** the task touches, and its platform — from the generator manifest, never from folder names
   alone:

   | Layout found | Scaffold | Platform of each target |
   |---|---|---|
   | `App/Sources/`, `App/Tests/`, `App/UITests/` + one macOS app target in `project.yml`/`Project.swift` | `iru-setup-macos-app` | macOS only |
   | `Targets/<Platform>/Sources/` (+ `Tests/`, `UITests/`) — `Targets/iOS/` (iPhone **and** iPad: one target), `Targets/macOS/`, `Targets/watchOS/`, `Targets/visionOS/` | `iru-setup-apple-app` | the `<Platform>` folder name, confirmed against the manifest target's `platform`/`supportedDestinations` (XcodeGen) or `destinations` (Tuist) |
   | Anything else (hand-maintained `.xcodeproj`, custom folders) | — | the target's `SDKROOT`/`SUPPORTED_PLATFORMS` (`iphoneos`, `macosx`, `xros`, `watchos`) |

   iPadOS is not a separate target: an iOS target whose `TARGETED_DEVICE_FAMILY` includes `2` (XcodeGen
   `supportedDestinations: [iOS, iPadOS]`, Tuist `.iPad`) is an iPad app too — `ios.md`'s iPadOS section applies.
   A visionOS destination on an iOS target (`supportedDestinations: [..., visionOS]`, Tuist `.appleVision`) makes
   `visionos.md` apply to it as well.
2. **UI framework** of that target:
   - `swiftui` — the app target has a `@main struct …: App` with `var body: some Scene`.
   - `uikit` — the target has a `@main` `UIApplicationDelegate` (or a `main.swift` calling `UIApplicationMain`), a
     `UIWindowSceneDelegate`, and `UIViewController` subclasses, and no SwiftUI `App`. SwiftUI hosted in
     `UIHostingController`/`UIHostingConfiguration` doesn't change this, nor does a SwiftUI `App` using
     `@UIApplicationDelegateAdaptor` make it UIKit.
   - `appkit` — the app target has a `@main` `NSApplicationDelegate` (or a `main.swift` calling
     `NSApplication.shared.run()`) and `NSWindowController`/`NSViewController` subclasses, and no SwiftUI `App`.
     SwiftUI views hosted in `NSHostingController`/`NSHostingView` don't change this.
   - visionOS and watchOS targets are SwiftUI (`visionos.md`).
3. **Architecture**, from the `Packages/Core` layout (the scaffold contract — identical for both scaffolds):

   | Layout found | Architecture |
   |---|---|
   | `Packages/Core/Sources/Core/ViewModels/` | **MVVM** |
   | `Packages/Core/Sources/Core/Controllers/` (UI-free controllers + their passive-view protocols) | **MVC** |
   | Neither, SwiftUI app | **MV** (views read `@Observable` stores directly) — add a view model only per the rules below |
   | Neither, AppKit or UIKit app | **MVC** (Cocoa/Cocoa Touch default) — logic in `NSViewController`s/`UIViewController`s is legacy to move out only when a task asks |
   | Both | Mixed, decided **per target** — UIKit/AppKit targets use `Controllers/` (MVC), SwiftUI targets (incl. watchOS/visionOS) use `ViewModels/` (MVVM). This is the scaffolded UIKit-MVC iOS target + SwiftUI-platforms mix; a feature on a UIKit/AppKit target goes in `Controllers/`, the same feature on a SwiftUI target in `ViewModels/` — never "follow the majority" |

   One `Packages/Core` serves every platform target of the app, so the folders are app-wide: a UIKit iOS target
   and an AppKit macOS target share one `ViewModels/` or `Controllers/` folder (with both folders present, each
   target uses the one its UI framework calls for, per the table). `ViewModels/` works for every UI
   framework; `Controllers/` only for UIKit/AppKit targets — a SwiftUI (or visionOS/watchOS) target next to a
   `Controllers/`-only Core uses MV or adds `ViewModels/` per the plan, and the report says so.
4. **Platform scope**: a macOS target reads `macos.md`, an iOS/iPadOS target `ios.md`, a visionOS target
   `visionos.md`; code shared across platforms in one target is guarded with `#if os(...)` (`swiftui.md`).

Valid combinations — don't introduce another one in a task:

| UI | Architectures | Notes |
|---|---|---|
| SwiftUI | `mvvm` (scaffold default), or MV | SwiftUI has no controllers; an MVC request for a SwiftUI app is a plan error to report. |
| UIKit | `mvc` (Cocoa Touch's native pattern) or `mvvm` | UIKit MVVM relies on Observation tracking — on by default from iOS 26 (`updateProperties()`), opt-in with `UIObservationTrackingEnabled` on iOS 18 (`uikit.md`). |
| AppKit | `mvc` (scaffold default — Cocoa's native pattern) or `mvvm` | AppKit MVVM relies on Observation tracking (`appkit.md`). |

## Where code goes

**`Packages/Core`** (library product `Core`; imports **Foundation and Observation only** — never SwiftUI, AppKit,
UIKit, `UniformTypeIdentifiers` panels, `NSWorkspace`, `@AppStorage`):

- `Models/` — value types (`struct`, `enum`), `Codable`/`Sendable` where they cross boundaries, and `@Observable`
  stores that own shared mutable state.
- `Services/` — service **protocols** (`protocol NoteSyncing: Sendable { func sync() async throws }`) and the pure
  implementations that need no UI (parsers, formatters, persistence on Foundation APIs, network clients on
  `URLSession`).
- `ViewModels/` (MVVM) — one `@MainActor @Observable final class <Screen>ViewModel` per screen that has real logic.
- `Controllers/` (MVC) — one `@MainActor final class <Feature>Controller` per feature plus its passive-view protocol
  (`@MainActor protocol <Feature>View: AnyObject`), in the same folder (or a sibling `Views/` folder in Core if the
  repository already has one — match it).

**App target** (`App/Sources/` in the macOS scaffold, `Targets/<Platform>/Sources/` in the multiplatform one): the
composition root (`App`, `AppDelegate`, or UIKit's `SceneDelegate`), SwiftUI views, UIKit/AppKit view controllers and
views, `Commands`/menus, background-task and notification registration, and the concrete implementations of Core
service protocols that **must** touch UI or a platform framework (an `NSOpenPanel`-backed `FilePicking`, an
`NSWorkspace`/`UIApplication`-backed `URLOpening`, an `AVCaptureDevice`-backed `CameraAuthorizing`). The composition
root is the only place that names concrete service types. A protocol implemented identically on several platforms
on a UI-free framework (`UserNotifications`, `Security`) can live in Core.

Dependency direction is the rule: Core imports nothing from the app; the app imports Core. A Core type that needs
something UI-ish gets a protocol in Core and an implementation in the app target.

## Protocol-typed services and test doubles

- Every external effect (network, disk outside simple value encoding, clock, randomness, panels, notifications,
  on-device models) is reached through a protocol, injected through an **initializer** (a store or view model that
  uses it) or — in SwiftUI only — an `@Entry` environment value (views that use it directly).
- A protocol refines `Sendable` when the conforming type does its work off the main actor.
- Test doubles are ordinary conforming types in the test target (an `actor Recording…` that records calls, a `struct
  Canned…` that returns fixed values) — no mocking framework unless the repository already uses one.
- Never make a service a global singleton or a `static let shared` that views/controllers reach for directly.

## MVVM rules

- A view model is a `@MainActor @Observable final class` in `Core/ViewModels/`, created by the view (SwiftUI
  `@State private var viewModel`) or passed to the view controller's initializer (UIKit/AppKit), with its services
  passed in.
- It exposes **state** (`private(set) var` where the view must not write; plain `var` for two-way inputs bound with
  `@Bindable`/`$viewModel.x` in SwiftUI or pushed from an action/delegate in UIKit/AppKit) and **intents** as methods
  (`func greet()`, `func load() async`). Derived state is a computed property.
- No SwiftUI/UIKit/AppKit types in it — no `Color`, `Image`, `UIColor`, `NSColor`, `UIImage`, `Binding`, `NSAlert`. Map to domain values
  (an enum the view turns into a color) and let the view render them.
- **No pass-through view models.** A view model that only re-exposes store properties one by one doubles the code and
  creates two sources of truth. Per Apple's recommended shape ("MV with Observation"), a screen that mostly displays
  and edits shared data reads the `@Observable` store directly (`@Environment(Store.self)`); introduce a view model
  only for a screen with logic worth testing on its own — validation, multi-step flows, paging, derived filtering.
  Observation makes update granularity identical either way, so "performance" is not a reason to add one.
- Async work: `func load() async` awaited from `.task { await viewModel.load() }` (SwiftUI) or a stored `Task` the
  view controller cancels in `viewWillDisappear()` (AppKit) / `viewWillDisappear(_:)` (UIKit). Loading/error state is
  explicit (`enum LoadState`).
- Tested directly in Core (`@MainActor` suite, fakes for services) — no SwiftUI, UIKit or AppKit involved.

## MVC rules (UIKit and AppKit)

- **Model**: Core value types and `@Observable` stores.
- **Controller logic** in Core: `@MainActor final class <Feature>Controller` holds the decisions — validates input,
  calls services, updates the model — and tells the view what to show through `weak var view: (any <Feature>View)?`
  (passive view: `func showMessage(_:)`, `func setGreetEnabled(_:)`, `func showError(_:)`). It receives user events
  as intent methods (`nameChanged(_:)`, `greetRequested(name:)`).
- **`UIViewController`/`NSViewController`** (app target) is the UI adapter: builds views, implements `<Feature>View`
  by setting control values, forwards every action/delegate callback to the Core controller. It contains no `if` a
  test would want to cover. `NSWindowController` owns only window concerns (`appkit.md`); UIKit containers
  (`UINavigationController`, `UITabBarController`) and the `SceneDelegate` own only navigation/window concerns
  (`uikit.md`).
- **Views** never talk to the model or services.
- Tested in Core with a fake `<Feature>View` that records what it was told (`uikit.md`/`appkit.md`/`testing.md`).
- The same Core controller + view protocol can back a UIKit iOS screen and an AppKit Mac screen of one app — each
  platform target implements the protocol in its own view controller.

## Testing and the coverage bar

- The 80% line-coverage gate is measured on `Packages/Core` only (`swift test --enable-code-coverage` there). The app
  targets (`Targets/**`) are "not measured" — their own unit tests still run and must pass, but never count toward the
  bar (Sonar's `sonar.coverage.exclusions=Targets/**` matches). SwiftUI views, UIKit/AppKit views/view controllers and the composition root are the UI layer:
  reported "not measured", not 0%, and not padded with tests that only instantiate views.
- XCUITest UI tests (`testing.md`) cover the real windows/menus end to end; they never count toward the coverage bar.
  Neither do component screenshot tests (`ui-implementation.md`) — they live in the app's test target. Item and
  component UI states (`OrderRowState`, `OrderListState`) are Core value types computed by the view model/controller,
  so their derivation *is* unit-tested at the bar.
- A task that adds logic to a view/view controller instead of Core is a design smell to fix in the task (move it to
  the view model/controller) — not a reason to lower the bar.

## Feature modules

When `Packages/Core` grows, split by feature/layer into further library targets of the same package (e.g. `Model`,
`Services`, `<Feature>`) with one-way dependencies — but only when the plan asks for it; a task never restructures
packages as a side effect.

## What must not be done

- Import SwiftUI/UIKit/AppKit (or any UI framework, `BackgroundTasks`, `WidgetKit`) in `Packages/Core`.
- Business logic in SwiftUI `body`, view modifiers, `UIViewController`s, `NSViewController`s, `NSWindowController`s or
  app/scene delegates.
- A view model per screen "for consistency" that only forwards store properties.
- An MVC controller that holds a strong reference to its view, or a view model that holds one to its view.
- Services reached through singletons, `NSApp.delegate`/`UIApplication.shared.delegate` or global functions instead
  of injected protocols.
- MVC (a passive-view controller) for a SwiftUI or visionOS screen.
- Switching a repository's architecture (MVVM ↔ MVC, MV → MVVM wholesale) inside a feature task.
