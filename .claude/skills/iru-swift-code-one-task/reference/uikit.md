# UIKit

Conventions for UIKit code in an iOS/iPadOS (or visionOS-compatible) app target: a UIKit app (`ui: uikit` —
`iru-swift-code-one-task` Step 1 says how to detect it), or UIKit pieces inside a SwiftUI app (a representable, a
hosted screen). Everything in `code-style.md`, `docc.md`, `testing.md`, `architecture.md` and `ios.md` still applies;
this file adds how to write UIKit itself well — MVC the Cocoa Touch way, and MVVM through UIKit's Observation
tracking. It mirrors `appkit.md` for the Mac.

Every snippet shape below was type-checked against the iOS 27.1 simulator SDK (Xcode 27.1, Swift 6 language mode)
with deployment targets iOS 18 and iOS 26; see "Verification" at the end.

## Availability: read the deployment target first

Read `IPHONEOS_DEPLOYMENT_TARGET` (XcodeGen `deploymentTarget`/`settings`, Tuist `deploymentTargets`, or the
`.xcodeproj`) before using any API below:

| API | Minimum iOS |
|---|---|
| `UIAction`/`UIButton(configuration:primaryAction:)`, `addAction(_:for:)`, diffable data sources | 14–15 |
| `UIHostingConfiguration`, `UIHostingController.sizingOptions` | 16 |
| `registerForTraitChanges(_:handler:)`, `traitOverrides`, `UITraitDefinition` | 17 |
| `UITab`, `UITabBarController.tabs`/`mode = .tabSidebar`, automatic trait tracking, Observation tracking **opt-in** (`UIObservationTrackingEnabled`) | 18 |
| `updateProperties()`/`setNeedsUpdateProperties()`, Observation tracking **on by default**, `UIMainMenuSystem`, `windowScene(_:didUpdateEffectiveGeometry:)` + `isInteractivelyResizing` | 26 |
| `UISceneClosureConfirmation` (`UIWindowScene.closureConfirmation`), `UISceneAccessory` | 27 |

Anything newer than the deployment target goes behind `if #available(iOS NN, *)` (or an `@available(iOS NN, *)`
override — see MVVM below) with a working fallback; never raise the deployment target inside a task.

## Programmatic UI, scene-based lifecycle, no storyboards

The catalog's UIKit scaffold builds every screen **in code**: no `Main.storyboard`, no `.xib`, no
`UIMainStoryboardFile`/`UISceneStoryboardFile` in `Info.plist` (the launch screen is the `UILaunchScreen` dictionary,
not a storyboard). A repository that already uses storyboards keeps them for the screens that have them (rule 1 of
`README.md`) — add outlets/actions there rather than converting a screen as a side effect.

**The scene-based lifecycle is mandatory**: an app built with the 27 SDK that adopts no `UIWindowSceneDelegate`
fails to launch. So:

- One `@main final class AppDelegate: UIResponder, UIApplicationDelegate` for **process-wide** work only: launch
  setup, `BGTaskScheduler` registration, the `UNUserNotificationCenter` delegate, remote-notification registration,
  and `application(_:configurationForConnecting:options:)` returning a `UISceneConfiguration` (set
  `configuration.delegateClass = SceneDelegate.self` there, or name the class in the `Info.plist`
  `UIApplicationSceneManifest` → `UISceneConfigurations` → `UISceneDelegateClassName` = `$(PRODUCT_MODULE_NAME).SceneDelegate`
  — match whichever the project uses). It never creates windows.
- One `final class SceneDelegate: UIResponder, UIWindowSceneDelegate` per scene kind, with `var window: UIWindow?`.
  `scene(_:willConnectTo:options:)` is the **composition root** for that window: build services (or take shared ones
  from the app delegate's container), the Core controllers/view models, the root view controller, then
  `UIWindow(windowScene:)`, `rootViewController`, `makeKeyAndVisible()`.
- Foreground/background transitions are **per scene**: `sceneDidBecomeActive`, `sceneWillResignActive`,
  `sceneWillEnterForeground`, `sceneDidEnterBackground` (save here), `sceneDidDisconnect` (release this scene's
  resources). The old `applicationDidEnterBackground(_:)` family is not called once scenes are adopted — app-wide
  state uses `UIApplication.willEnterForegroundNotification`/`didEnterBackgroundNotification`.
- State restoration: return an `NSUserActivity` from `stateRestorationActivity(for:)` and read
  `session.stateRestorationActivity` back in `scene(_:willConnectTo:options:)` — UI state only (selection, tab), never
  data.
- Dependencies reach view controllers through **initializers**, never through
  `UIApplication.shared.delegate as! AppDelegate`, singletons or `UIApplication.shared.connectedScenes` lookups.

## View controller lifecycle

```swift
final class GreetingViewController: UIViewController {

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
    title = String(localized: "Greeting")
    controller.view = self         // wire once: actions, delegates, accessibility identifiers
  }

  // MARK: Private

  private let controller: GreetingController
}
```

- `init(nibName: nil, bundle: nil)` + an `@available(*, unavailable)` `init?(coder:)`.
- `loadView()` (or `viewDidLoad()` adding subviews to the default `view` — match the project) builds the hierarchy
  once; `viewDidLoad()` wires actions/delegates/identifiers once; `viewWillAppear(_:)`/`viewDidAppear(_:)` start
  visible-only work; `viewWillDisappear(_:)`/`viewDidDisappear(_:)` cancel it (stored `Task`s);
  `viewWillLayoutSubviews()`/`viewDidLayoutSubviews()` react to size; `updateProperties()` (iOS 26+) sets content
  that depends on observable state. Always call `super`.
- Never touch `view` from `init` (it forces `loadView()` early). `view.backgroundColor = .systemBackground` (semantic
  colors, never hard-coded white/black — Dark Mode).
- Composite screens use **child view controllers** (`addChild(_:)`, add the child's view, `didMove(toParent:)`) or a
  container (`UINavigationController`, `UITabBarController`, `UISplitViewController`), not one controller owning
  everything. A child/region is a UI-contract component (`ui-contract.md`, `ui-implementation.md`): it renders a
  value-type state through `render(_:)` and reports user intents through closures (`var onOpen: ((ID) -> Void)?`) or a
  `@MainActor` delegate — never a `UIControl` event or a model mutation.

## Auto Layout in code

- Every view you add: `translatesAutoresizingMaskIntoConstraints = false`, then one
  `NSLayoutConstraint.activate([...])` of anchor constraints. No frame arithmetic for layout.
- Pin content to `view.safeAreaLayoutGuide` (bars, home indicator, Dynamic Island) and text to
  `layoutMarginsGuide`/`readableContentGuide`; never to raw `view` edges for content.
- `UIStackView` (`axis`, `alignment`, `spacing`) for rows/columns; set hugging/compression-resistance priorities where
  two views compete. A label holding user text gets `numberOfLines = 0`.
- Text uses `UIFont.preferredFont(forTextStyle:)` + `adjustsFontForContentSizeCategory = true` (or
  `UIFontMetrics(forTextStyle:).scaledFont(for:)` for a custom font) — Dynamic Type is not optional (`ios.md`).

## Navigation containers

- **`UINavigationController(rootViewController:)`** for push/pop; push with
  `navigationController?.pushViewController(_:animated:)`; each screen configures its own bar through
  `navigationItem` (title, `UIBarButtonItem(systemItem:primaryAction:)`, `searchController`). Large titles via
  `navigationBar.prefersLargeTitles` once, at the container.
- **`UITabBarController`** with the iOS 18 `tabs` API: `UITab(title:image:identifier:viewControllerProvider:)`
  (stable `identifier`s) and `mode = .tabSidebar` on iPad so the tab bar can become a sidebar — the UIKit side of
  SwiftUI's `.sidebarAdaptable`. Below iOS 18, `viewControllers` + `tabBarItem`. Each tab owns its own navigation
  controller; never nest a tab bar inside a navigation stack.
- **`UISplitViewController(style: .doubleColumn/.tripleColumn)`** for iPad list–detail; it collapses to a stack in a
  compact width on its own — don't branch on the device (`ios.md`).
- Modal: `present(_:animated:)` with `sheetPresentationController` detents for sheets; a popover sets
  `popoverPresentationController?.sourceItem` (required on iPad, else it crashes).

## Collection views: compositional layout + diffable data source

The default for any list or grid — `UITableView` and hand-written `numberOfItems`/`cellForItemAt`/`insertRows`
bookkeeping only where the file already uses them:

```swift
let layout = UICollectionViewCompositionalLayout.list(
  using: UICollectionLayoutListConfiguration(appearance: .insetGrouped))

let registration = UICollectionView.CellRegistration<UICollectionViewListCell, Note.ID> { cell, _, id in
  guard let note = notesByID[id] else { return }      // the list state, keyed by id
  var content = cell.defaultContentConfiguration()
  content.text = note.title
  cell.contentConfiguration = content
  cell.accessories = [.disclosureIndicator()]
}

dataSource = UICollectionViewDiffableDataSource(collectionView: collectionView) { collectionView, indexPath, id in
  collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: id)
}

var snapshot = NSDiffableDataSourceSnapshot<Section, Note.ID>()
snapshot.appendSections([.pinned, .all])
snapshot.appendItems(pinnedIDs, toSection: .pinned)
snapshot.reconfigureItems(editedIDs)            // content changed, identity didn't
dataSource.apply(snapshot, animatingDifferences: true)
```

- Item identifiers are **stable IDs** (`Note.ID`), not whole model values: an edit is `reconfigureItems(_:)`, not a
  delete + insert, so the cell keeps position and selection. Section identifiers are a small `Hashable` enum.
- `UICollectionView.CellRegistration` (created once, in `viewDidLoad`, never inside the cell provider) + content
  configurations (`defaultContentConfiguration()`, `UIListContentConfiguration`) — no reuse-identifier strings, no
  custom cell subclass just to set a label.
- Grids and custom layouts: `UICollectionViewCompositionalLayout { sectionIndex, environment in … }` with
  `NSCollectionLayoutItem`/`Group`/`Section` sized by fractions/estimates that read
  `environment.container.effectiveContentSize` — not by device.
- Selection goes to the controller/view model (`collectionView(_:didSelectItemAt:)` →
  `dataSource.itemIdentifier(for:)` → an intent call), not straight into model mutation.
- The whole list's state (item states with stable ids + paging flags) comes from the view model/Core controller;
  the collection component diffs it into a snapshot and pages from `collectionView(_:willDisplay:forItemAt:)` — the
  full `render(_:)` pattern, and how to capture its states in UI tests, is in `ui-implementation.md`.

## Target-action and `UIAction`

- New controls use **closure actions**: `UIButton(configuration: .filled(), primaryAction: UIAction(title:) { [weak
  self] _ in self?.greet() })`, `control.addAction(UIAction { … }, for: .editingChanged)`,
  `UIBarButtonItem(systemItem:primaryAction:)`, `UIMenu(children: [UIAction(title:image:) { _ in … }])`. Capture
  `self` weakly.
- Classic **target-action** (`addTarget(self, action: #selector(nameReturned(_:)), for: .editingDidEndOnExit)` with an
  `@objc private func`) where the file already uses it, and for **responder-chain** commands: keyboard shortcuts and
  menu items with a `nil` target, declared once as `@MainActor @objc protocol <Feature>Commands` and referenced as
  `#selector(<Feature>Commands.newNote(_:))` (`@MainActor` is required for a `UIViewController` to conform under
  Swift 6). Validate with `canPerformAction(_:withSender:)`/`validate(_:)`.
- Keyboard shortcuts: `override var keyCommands: [UIKeyCommand]?` on the responder that handles them; the iPad menu
  bar (iPadOS 26+) through `UIMainMenuSystem` (`ios.md`).
- Action closures and delegate callbacks translate events into **controller/view-model intents**; they contain no
  feature logic.

## The trait system

- Traits (size classes, `userInterfaceStyle`, `preferredContentSizeCategory`, display scale, layout direction) flow
  down from the window scene. React to changes with `registerForTraitChanges([UITraitHorizontalSizeClass.self,
  UITraitPreferredContentSizeCategory.self]) { (self: Self, previous: UITraitCollection) in … }` (iOS 17+) — never
  the deprecated `traitCollectionDidChange(_:)` in new code.
- Since iOS 18, reading `traitCollection` inside `layoutSubviews()`, `updateProperties()`, `draw(_:)` or a cell's
  configuration update handler is **tracked automatically** — no registration needed there.
- Override a trait for a subtree with `traitOverrides` (`traitOverrides.userInterfaceStyle = .dark`); an app-specific
  ambient value is a `UITraitDefinition` + `UITraitCollection`/`UIMutableTraits` extensions, bridged to SwiftUI with
  a `UITraitBridgedEnvironmentKey` when hosted SwiftUI must see it.
- Layout decisions read the **size class / container size**, never `UIDevice.current.userInterfaceIdiom` or screen
  bounds (`UIScreen.main` is deprecated and unavailable on visionOS).

## SwiftUI inside UIKit, UIKit inside SwiftUI

- **`UIHostingController(rootView:)`** to push/present/embed a SwiftUI screen; set `sizingOptions =
  [.preferredContentSize]` for popovers/sheets that should fit their content. Pass shared `@Observable` models
  explicitly (`.environment(store)` on the root view) — the environment does not cross the boundary; built-in traits
  do.
- **`UIHostingConfiguration { RowView(item:) }.margins(.horizontal, 16)`** as a cell's `contentConfiguration` — the
  cheap way to reuse a SwiftUI row in a UIKit list (one screen/row at a time migration).
- **`UIViewRepresentable`/`UIViewControllerRepresentable`** to put a UIKit view/controller in SwiftUI:
  `makeUIView(context:)` creates and configures once; `updateUIView(_:context:)` pushes SwiftUI state in, only when
  it differs (no feedback loops); `makeCoordinator()` returns a `@MainActor final class Coordinator: NSObject`
  delegate/target that writes back through a `Binding`; `static func dismantleUIView(_:coordinator:)` detaches
  delegates/targets; `sizeThatFits(_:uiView:context:)` when the view's height depends on the proposed width.

## MVC the Cocoa Touch way (`architecture: mvc`)

The layering (details and test seams in `architecture.md`):

- **Model**: value types and `@Observable` stores/services in `Packages/Core` (no UIKit import).
- **Controller logic**: a `@MainActor final class <Feature>Controller` in `Packages/Core/Sources/Core/Controllers/`
  that owns the feature's decisions, calls services, and drives a **passive view protocol**
  (`@MainActor protocol <Feature>View: AnyObject { func showMessage(_:); func setGreetEnabled(_:) }`) held `weak`.
  Unit-tested in Core with a fake view.
- **`UIViewController`** (app target) is the UIKit adapter — the "controller" in Apple's MVC diagram, but a thin one:
  builds views, implements `<Feature>View` by setting control values, forwards every action/delegate callback to the
  Core controller as an intent (`controller.greetRequested(name:)`, started as a stored `Task` cancelled in
  `viewWillDisappear(_:)`). No `if` a test would want to cover — the "massive view controller" is the failure mode
  this layering exists to prevent.
- **Views**: UIKit controls and small `UIView` subclasses; no model access, no services.

## MVVM with Observation tracking (`architecture: mvvm`)

The view model is a `@MainActor @Observable final class` in `Packages/Core/Sources/Core/ViewModels/` (no UIKit
import). The view controller reads its properties inside **tracked methods**; UIKit records what was read and re-runs
the method when one of those properties changes — no KVO, Combine, `NotificationCenter` or manual reload:

```swift
@available(iOS 26, *)
override func updateProperties() {                  // iOS 26+: the dedicated tracked method
  super.updateProperties()
  messageLabel.text = viewModel.message              // tracked read
  greetButton.isEnabled = viewModel.canGreet         // tracked read
}

override func viewWillLayoutSubviews() {            // iOS 18 path (with the Info.plist opt-in)
  super.viewWillLayoutSubviews()
  if #unavailable(iOS 26) {
    messageLabel.text = viewModel.message
    greetButton.isEnabled = viewModel.canGreet
  }
}
```

- **iOS 26+**: tracking is on by default. Put observable-driven content in `updateProperties()`
  (`UIViewController` and `UIView`); `setNeedsUpdateProperties()` requests a pass manually. It is also tracked in
  `viewWillLayoutSubviews()`, `UIView.layoutSubviews()`/`updateConstraints()`/`draw(_:)` — use those for
  *layout* that depends on the model, `updateProperties()` for content/styling.
- **iOS 18–25**: tracking works only with the Boolean `Info.plist` key **`UIObservationTrackingEnabled`** = `YES`
  (set it through the generator manifest's `info` properties — never assume it), and only in the layout-family
  methods (`viewWillLayoutSubviews()`, `layoutSubviews()`, `updateConstraints()`, `draw(_:)`) — `updateProperties()`
  doesn't exist there. With a deployment target below 26, write the `@available(iOS 26, *)` override plus the
  `#unavailable(iOS 26)` layout fallback above, or just the layout method — match the project.
- Below 18, or for a non-tracked context: `withObservationTracking({ … }, onChange: { Task { @MainActor in … } })`
  re-registering in `onChange` — a deliberate, commented fallback.
- Tracked methods only read and assign view state: no service calls, no model mutation, no `Task` creation (that
  loops). User events go to view-model **methods**; text input is pushed with `viewModel.name = field.text ?? ""`
  from an `.editingChanged` action.

## What must not be done

- Business/feature logic, service calls or persistence in `UIViewController`/`UIView` subclasses or scene/app
  delegates (untestable, outside the coverage bar).
- An app without a `UIWindowSceneDelegate`, windows created in the app delegate, or work in the deprecated
  `applicationDidEnterBackground(_:)` family once scenes are adopted.
- Reaching dependencies through `UIApplication.shared.delegate`, singletons or `connectedScenes`/`windows` lookups.
- New storyboards/xibs, force-unwrapped `@IBOutlet`s in programmatic code, frame-based layout, content pinned outside
  the safe area, fixed font sizes.
- Branching layout on `userInterfaceIdiom`/`UIScreen.main.bounds` instead of size classes/container size.
- KVO/Combine/`NotificationCenter` to bind new UI to an `@Observable` view model, or `reloadData()` where a diffable
  snapshot applies.
- iOS 26/27 APIs (`updateProperties()`, `UIMainMenuSystem`, `isInteractivelyResizing`, `UISceneClosureConfirmation`)
  without an availability guard below their minimum.

## Verification

`@main` `UIApplicationDelegate` with `application(_:configurationForConnecting:options:)` + `delegateClass`,
`UIWindowSceneDelegate` (`scene(_:willConnectTo:options:)`, the four transition callbacks, `sceneDidDisconnect`,
`stateRestorationActivity(for:)`, `sizeRestrictions`), `UINavigationController`, `UITabBarController.tabs` with
`UITab(title:image:identifier:viewControllerProvider:)` and `mode = .tabSidebar`, programmatic Auto Layout/
`UIStackView`/safe-area and margin guides, `UIButton(configuration:primaryAction:)`, `addAction(_:for:)`,
`addTarget(_:action:for:)`, `UIBarButtonItem(systemItem:primaryAction:)`, `keyCommands`, `@MainActor @objc` command
protocol, `UIPointerInteraction`, `registerForTraitChanges` with a `(self: Self, previous:)` handler, a custom
`UITraitDefinition` with `UIMutableTraits`, `UICollectionViewCompositionalLayout.list`, `CellRegistration`,
`UICollectionViewDiffableDataSource` + `NSDiffableDataSourceSnapshot.reconfigureItems`, `UIHostingController.sizingOptions`,
`UIHostingConfiguration.margins`, `UIViewRepresentable` (coordinator, `dismantleUIView`, `sizeThatFits(_:uiView:context:)`),
`UIViewControllerRepresentable`, `UITraitBridgedEnvironmentKey`, child view controller containment,
`UISplitViewController(style:)`, sheet detents/popover `sourceItem`, `UIFontMetrics`, `traitOverrides`, a
section-provider compositional layout reading `effectiveContentSize`,
a Core `@MainActor` controller with a passive-view protocol, an `@Observable` view model read in
`viewWillLayoutSubviews()` and in an `@available(iOS 26, *) override func updateProperties()`, and
`withObservationTracking` were type-checked with `xcrun -sdk iphonesimulator swiftc -typecheck -target
arm64-apple-ios18.0-simulator -swift-version 6` and again with `-ios26.0` (Xcode 27.1, iOS 27.1 SDK); `UIMainMenuSystem`
needed `-ios26.0`/`@available(iOS 26, *)` and `UISceneClosureConfirmation` `@available(iOS 27, *)`. The compiler
rejects `updateProperties()` and `isInteractivelyResizing` below iOS 26. `UIObservationTrackingEnabled` is an
`Info.plist` key, not an SDK symbol: it was confirmed as a string in the iOS 18.5 simulator runtime's `UIKitCore` (and
is absent from 26.4's, where tracking is on by default). The automatic re-run of tracked methods was proven at runtime
by `iru-setup-apple-app`'s own verification: its UIKit MVVM scaffold at `min-deployment-target: 18.0` passes every
test on an iOS 18.5 simulator with the `UIObservationTrackingEnabled` key, and the same project without the key fails
three view-controller tests (the view never re-renders) — so the opt-in is required below iOS 26, and only unit tests
(not the UI test) catch its absence.
