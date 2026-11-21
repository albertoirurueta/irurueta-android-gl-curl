# AppKit

Conventions for AppKit code in a macOS app target: an AppKit app (`ui: appkit` — `iru-swift-code-one-task` Step 1
says how to detect it), or AppKit pieces inside a SwiftUI app (a representable, a hosted window). Everything in
`code-style.md`, `docc.md`, `testing.md`, `architecture.md` and `macos.md` still applies; this file adds how to write
AppKit itself well — MVC the Cocoa way, and MVVM through AppKit's Observation tracking.

Every snippet shape below was type-checked against the macOS 27 SDK (Xcode 27.1, Swift 6 language mode, deployment
target macOS 15); see "Verification" at the end.

## Programmatic UI, no storyboards

The catalog's AppKit scaffold builds every window, view and menu **in code** — no `Main.storyboard`, no `.xib`, no
`NSMainStoryboardFile`/`NSMainNibFile` in `Info.plist`. New UI follows that. A repository that already uses
storyboards/xibs keeps them for the screens that have them (rule 1 of `README.md`): add outlets/actions there rather
than converting a screen to code as a side effect.

## Entry point and app delegate

- One `@main final class AppDelegate: NSObject, NSApplicationDelegate` with a `static func main()` that creates
  `NSApplication.shared`, assigns the delegate, sets `.setActivationPolicy(.regular)` and calls `run()` (or a
  `main.swift` doing the same — match the project). It is the **composition root**: it builds the concrete services,
  the Core controllers/view models, and the window controllers, and injects them through initializers.
- `applicationDidFinishLaunching(_:)` installs `NSApp.mainMenu`, shows the first window controller, calls
  `NSApp.activate()`. Implement `applicationSupportsSecureRestorableState(_:)` returning `true`, and
  `applicationShouldTerminateAfterLastWindowClosed(_:)` deliberately (single-window utility → `true`).
- The delegate **keeps strong references** to the window controllers it creates (a property or a collection); an
  unreferenced `NSWindowController` is deallocated and its window disappears. Remove the reference in
  `windowWillClose(_:)` (via `NSWindowDelegate`) for transient windows.

## `NSWindowController` and `NSWindow`

- One `NSWindowController` subclass per kind of window, created with a `convenience init` that builds the window
  from its content view controller: `NSWindow(contentViewController:)`, then `title`, `styleMask` (`[.titled,
  .closable, .miniaturizable, .resizable]`), `setContentSize(_:)`, `setFrameAutosaveName("<App>.<Window>")` (the
  window reopens where the user left it), then `self.init(window:)`.
- A bare `NSWindow` not owned by a window controller sets `isReleasedWhenClosed = false`.
- The window controller owns window-level concerns (title, toolbar, frame, close/restore); it does **no** feature
  logic and no model access beyond handing the content view controller what it needs.
- Sheets: `window.beginSheet(_:)` / `NSAlert.beginSheetModal(for:)`, not app-modal `runModal()` for anything a
  window owns. A non-essential sheet sets `preventsApplicationTerminationWhenModal = false` so Quit isn't blocked.

## `NSViewController` lifecycle

```swift
final class GreetingViewController: NSViewController {

  // MARK: Internal

  init(controller: GreetingController) {
    self.controller = controller
    super.init(nibName: nil, bundle: nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  override func loadView() {
    view = makeRootView()          // build the hierarchy; no data loading here
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    controller.view = self         // wire once: delegates, targets, identifiers
  }

  // MARK: Private

  private let controller: GreetingController
}
```

- `init(nibName: nil, bundle: nil)` + an `@available(*, unavailable)` `init?(coder:)`; dependencies arrive through
  the initializer, never through singletons or `NSApp.delegate as! AppDelegate`.
- `loadView()` creates `view` and its subviews; `viewDidLoad()` wires delegates/targets/accessibility identifiers
  once; `viewWillAppear()`/`viewDidAppear()` start window-bound work (first responder, refresh);
  `viewWillDisappear()`/`viewDidDisappear()` cancel it (stored `Task`s). `viewWillLayout()`/`viewDidLayout()` are
  for layout and — in MVVM — tracked reads (below). Always call `super`.
- Never touch `view` from `init` (it forces `loadView()` early).
- Child view controllers (`addChild(_:)` + adding its view, or `NSSplitViewController`/`NSTabViewController`) for
  composite screens, rather than one view controller owning everything. Each child/region is a UI-contract component
  (`ui-contract.md`, `ui-implementation.md`): `render(_:)` of a value-type state in, intent closures or a `@MainActor`
  delegate out.

## Auto Layout in code

- Every view you add: `translatesAutoresizingMaskIntoConstraints = false`, then one
  `NSLayoutConstraint.activate([...])` with anchor constraints (`leadingAnchor`, `topAnchor`, `widthAnchor`,
  `constraint(equalTo:constant:)`, `lessThanOrEqualTo:`). No frame arithmetic for layout, no magic window sizes.
- Prefer `NSStackView` (`orientation`, `alignment`, `spacing`) for rows/columns of controls; set content hugging /
  compression resistance priorities where two views compete.
- The root view gets minimum-size constraints so the window's `windowResizability`/content limits make sense.

## Target-action, delegates and data sources

- **Target-action**: `control.target = self; control.action = #selector(greet(_:))` with
  `@objc private func greet(_ sender: Any?)` (or a typed sender). Menu items usually have **no target** — the action
  travels up the responder chain from the key window to whichever controller implements it.
- **Control events** (`addTarget(_:action:for:)`, `NSControl.Events`): the tracking events (`.trackingBegan`,
  `.trackingEndedInside`, `.trackingEndedOutside`, `.trackingCancelled`, …) and the method itself are available from
  macOS 11, but `.valueChanged`, `.primaryActionTriggered`, `.menuActionTriggered` and `.trackingRepeated` are
  **macOS 27+** in the 27 SDK — the compiler rejects them below 27, so guard them with `if #available(macOS 27, *)`
  and keep the plain `action` as the fallback. Use them instead of subclassing a control to override `mouseDown`.
- **Delegates/data sources**: the view controller (or a small dedicated `NSObject` helper it owns) conforms to
  `NSTextFieldDelegate`, `NSTableViewDataSource`/`NSTableViewDelegate`, `NSWindowDelegate`, …; AppKit holds
  delegates weakly, so the owner keeps the helper alive. View-based tables: `makeView(withIdentifier:owner:)` reuse,
  `numberOfRows(in:)`; prefer `NSTableViewDiffableDataSource` + `NSDiffableDataSourceSnapshot` for changing data over
  hand-written `insertRows`/`removeRows` bookkeeping. Every data-driven list or grid is a view-based `NSTableView`/
  `NSOutlineView` or an `NSCollectionView` over keyed item states — never one subview per item in an `NSStackView`
  (`ui-implementation.md`, "Collections").
- Delegate callbacks translate events into **controller/view-model calls**; they don't contain the feature logic.

## Menus built in code

- The main menu is built by one function/type (`MainMenuBuilder`) returning an `NSMenu`: an app menu (About →
  `NSApplication.orderFrontStandardAboutPanel(_:)`, Settings… ⌘, , Hide, Quit → `NSApplication.terminate(_:)` ⌘Q),
  File, Edit (standard `undo:`/`redo:`, `NSText.cut/copy/paste/selectAll(_:)` selectors so text fields work), View,
  Window (`NSWindow.performMiniaturize(_:)`, assigned to `NSApp.windowsMenu`), Help. Missing an Edit menu silently
  breaks ⌘C/⌘V in every text field — keep it.
- App-specific actions are declared once as `@MainActor @objc protocol <Feature>Commands { func newItem(_ sender:
  Any?) }` (the `@MainActor` is required: an `NSViewController` conforming to a nonisolated `@objc` protocol is a
  Swift 6 isolation error) and referenced as `#selector(<Feature>Commands.newItem(_:))` with a `nil` target.
- Enable/disable through `NSMenuItemValidation.validateMenuItem(_:)` on the responder that implements the action —
  disable, don't remove (`macos.md`). `keyEquivalent` + `keyEquivalentModifierMask` for shortcuts; no standard
  shortcut collisions.
- A SwiftUI-declared menu inside AppKit: `NSHostingMenu(rootView:)` (macOS 14.4+) with `Section`/`Button` content.

## SwiftUI inside AppKit, AppKit inside SwiftUI

- **`NSHostingController(rootView:)`** as a window's or pane's content view controller; trim
  `sizingOptions` (`[.minSize, .intrinsicContentSize]`) to what the container needs.
- **`NSHostingView(rootView:)`** to embed a SwiftUI view in an AppKit hierarchy (a table cell, a pane); in tables set
  `sizingOptions = [.intrinsicContentSize]` to keep measurement cheap.
- **`NSViewRepresentable`** / `NSViewControllerRepresentable` to put an AppKit control in SwiftUI:
  `makeNSView(context:)` creates and configures once, `updateNSView(_:context:)` pushes SwiftUI state in (only when
  it differs, to avoid feedback loops), `makeCoordinator()` returns an `NSObject` delegate that writes back through a
  `Binding`, `static func dismantleNSView(_:coordinator:)` clears the delegate.
- Pass shared `@Observable` models across the boundary explicitly (`.environment(store)` on the hosted root view);
  hosted SwiftUI does not inherit anything from AppKit.

## MVC the Cocoa way (`architecture: mvc`)

The layering (details and test seams in `architecture.md`):

- **Model**: value types and `@Observable` stores/services in `Packages/Core` (no AppKit import).
- **Controller logic**: a `@MainActor final class <Feature>Controller` in `Packages/Core/Sources/Core/Controllers/`
  that owns the feature's decisions, calls services, and drives a **passive view protocol**
  (`@MainActor protocol <Feature>View: AnyObject { func showMessage(_:); func setGreetEnabled(_:) }`) held
  `weak`. Unit-tested in Core with a fake view.
- **View controller** (app target): builds the views, implements `<Feature>View` by setting control values, and
  forwards every user event (action, delegate callback) to the Core controller as an intent
  (`controller.greetRequested(name:)`). It never decides anything a test would want to check.
- **Views**: AppKit controls and small `NSView` subclasses; no model access, no services.

## MVVM with Observation tracking (`architecture: mvvm`)

The view model is a `@MainActor @Observable final class` in `Packages/Core/Sources/Core/ViewModels/` (no AppKit
import). The `NSViewController` reads its properties inside **tracked methods**, and AppKit re-runs the method when
a property it read changes — no KVO, `NotificationCenter`, Combine or manual invalidation:

```swift
override func viewWillLayout() {
  super.viewWillLayout()
  messageLabel.stringValue = viewModel.message   // tracked read
  greetButton.isEnabled = viewModel.canGreet     // tracked read
}

@objc private func greet(_ sender: Any?) {
  viewModel.greet()                              // intent → view model; AppKit re-runs viewWillLayout()
}
```

- Tracked methods (per Apple's "Updating views automatically with observation tracking in AppKit"):
  `NSView.updateConstraints()`, `layout()`, `draw(_:)`, and `NSViewController.viewWillLayout()` — don't assume
  other overrides are tracked. Only properties actually **read** there become dependencies — read what you display.
- **Availability**: on by default from macOS 26; on macOS 15 it requires the Boolean `Info.plist` key
  `NSObservationTrackingEnabled` = `YES` (set it through the generator manifest's `info` properties — never assume
  it). Below 15, or where a non-tracked context must react, use `withObservationTracking({ … }, onChange: { … })`
  and re-register in `onChange` (hop back with `Task { @MainActor in … }`) — a deliberate, commented fallback, not
  the default.
- Keep tracked methods cheap and side-effect-free apart from setting view state: no service calls, no model
  mutation, no `Task` creation (that would loop).
- User events go to view-model **methods** (intents); text input is pushed with `viewModel.name =
  field.stringValue` from the delegate callback.

## What must not be done

- Business/feature logic, service calls or persistence inside `NSViewController`/`NSWindowController`/`NSView`
  subclasses (it can't be unit-tested and isn't in the coverage bar).
- Reaching dependencies through `NSApp.delegate`, global singletons or `NSApp.windows` lookups.
- Force-unwrapped `@IBOutlet`s in new programmatic code, frame-based layout, or a missing Edit menu.
- KVO/`NotificationCenter`/Combine to bind new UI to an `@Observable` view model (use Observation tracking).
- `.valueChanged`/`.primaryActionTriggered` control events without an availability guard below macOS 27.
- Blocking the main thread or `runModal()` from async code (`macos.md`).

## Verification

`@main` `AppDelegate` with `static func main()`, `NSWindowController` convenience init (`setFrameAutosaveName`,
`isReleasedWhenClosed`), `NSViewController` lifecycle overrides, programmatic Auto Layout/`NSStackView`,
target-action, `NSTextFieldDelegate`/`NSSearchFieldDelegate`, `NSTableViewDataSource`/`NSTableViewDiffableDataSource`,
`NSMenu` built in code with the standard selectors, `@MainActor @objc` command protocol + `NSMenuItemValidation`,
`addTarget(_:action:for:)` (tracking events unguarded; `.valueChanged` rejected below macOS 27 and accepted behind
`if #available(macOS 27, *)`), `NSHostingController`/`NSHostingView` `sizingOptions`, `NSHostingMenu`,
`NSViewRepresentable` with coordinator and `dismantleNSView`, a Core `@MainActor` controller with a passive-view
protocol, an `@Observable` view model read in `viewWillLayout()`, and the `withObservationTracking` fallback were all
type-checked with `xcrun swiftc -typecheck -target arm64-apple-macos15 -swift-version 6` against the macOS 27 SDK
(Xcode 27.1). The automatic re-run of tracked methods is runtime behavior taken from Apple's documentation and the
irurueta docs page, not exercised here.
