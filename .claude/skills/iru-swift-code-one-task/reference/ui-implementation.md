# UI implementation on Apple platforms (SwiftUI, UIKit, AppKit)

`ui-contract.md` (next to this file) is the rule set; this file maps each rule onto an Apple app target (iOS/iPadOS,
macOS, watchOS, visionOS) or a Swift package that ships SwiftUI views, and onto the test tools Xcode already has —
Swift Testing + `ImageRenderer` for components, XCUITest (XCUIAutomation) for screens and flows,
`performAccessibilityAudit()` for accessibility. Read it together with `swiftui.md`, `uikit.md` or `appkit.md`
(whichever framework the touched UI uses), `architecture.md` and `testing.md`. A command-line tool has no UI — none
of this applies to it. When the repository's own UI code consistently does something else, follow the code and
report the divergence.

## Screens, components and navigation

| Contract term | SwiftUI | UIKit | AppKit |
|---|---|---|---|
| Screen | The root `View` of a `NavigationStack` destination/`WindowGroup`/`Window`, owning the view model (`@State private var viewModel`) or reading an `@Observable` store, passing plain state down | A `UIViewController` (thin adapter, `uikit.md`) that owns the view model/Core controller and hosts child view controllers/components | An `NSViewController` (window content) owned by an `NSWindowController`, same shape as UIKit |
| Component | A small `struct …: View` taking one value-type state + closures | A `UIView` subclass with `render(_ state:)` + closure properties, or a child `UIViewController` for a self-contained region (child containment: `addChild`, add view, `didMove(toParent:)`) | An `NSView` subclass with `render(_:)`, or a child `NSViewController` (`addChild`, `NSSplitViewController`/`NSTabViewController`) |
| Navigation | One `NavigationStack(path:)` + `.navigationDestination(for:)` table per window, or a router `@Observable` holding the path | One coordinator / the `SceneDelegate` composition root building `UINavigationController`/`UITabBarController` and pushing | The app delegate / one window controller opening windows and swapping content |

- **Presentational components never own a view model**, never call a service, never read `@Environment` stores or
  `UIApplication.shared`/`NSApp`. The screen (or its view controller) owns the view model from `Packages/Core`
  (`architecture.md`), maps its state into component states and forwards component events to view-model intents.
- **UI state lives in Core.** Item and component states that a view model computes (`OrderRowState`) are plain
  `struct`s in `Packages/Core` (Foundation only — `Identifiable`, `Equatable`, `Sendable`), so their derivation is
  unit-tested at the Core coverage bar; the view only renders them. Colours/images are mapped from domain enums in
  the view, never stored in the state.
- **Composition**: SwiftUI passes content in with `@ViewBuilder` parameters (`Card<Content: View>`) and reusable
  styling as `ViewModifier`/`ButtonStyle`, never by wrapping a screen in another screen's subclass. UIKit/AppKit
  compose child views and child view controllers; subclass only `UIViewController`/`NSViewController`/`UIView`/
  `NSView` themselves, never another feature's controller.
- **Navigation is one table.** Components emit `onOpen(id)`; only the screen turns it into a path append, a push or
  a window — never a `NavigationLink` buried in a reusable row that hard-codes its destination (a `NavigationLink(value:)`
  whose destination the screen registers is fine). The docs skills read the navigation table to draw the flows.

## Inputs, events and state

```swift
/// One order row. Inputs: `state`. Events: `onOpen(id)`. States: default, selected, long-content.
struct OrderRow: View {
  let state: OrderRowState
  let onOpen: (OrderRowState.ID) -> Void

  var body: some View {
    Button { onOpen(state.id) } label: {
      VStack(alignment: .leading) {
        Text(state.title).font(.headline)
        Text(state.subtitle).font(.subheadline).foregroundStyle(.secondary)
      }
    }
    .accessibilityAddTraits(state.isSelected ? .isSelected : [])
  }
}
```

- **Inputs**: one `let state: <Component>State` (or a few `let`s), never `@ObservedObject`/`@Bindable` of a whole view
  model in a leaf. **Events**: `let on<Intent>: (Payload) -> Void` closures named after the intent; UIKit/AppKit
  components expose `var on<Intent>: ((Payload) -> Void)?` (or a `@MainActor` delegate protocol where the file already
  uses delegates) and translate `UIAction`/target-action internally — no `UIControl`/`NSEvent` leaves the component.
- **`Binding` only for form fields** whose value is the control (`TextField(text:)`, `Toggle(isOn:)`, `Picker`,
  `Slider`): `let text: Binding<String>` or `@Binding`; the screen supplies `$viewModel.query` through `@Bindable`.
  Never a `Binding` to an array, a domain object or another component's state.
- **Visual states** (`default`, `loading`, `empty`, `error`, `disabled`, …) are cases or flags of the component's
  state, each with a `#Preview("Loading")` — and each captured by a test (below).
- **Localization/direction**: `String(localized:)`/`LocalizedStringKey`, `.leading`/`.trailing` (SwiftUI),
  `leadingAnchor`/`trailingAnchor` (UIKit/AppKit), SF Symbols that mirror (`chevron.forward`, not `chevron.right`), no
  fixed widths for text.

## Collections — virtualized containers over keyed state

The collection component's state holds the whole list (`OrderListState`: items with stable ids + paging flags); the
view model in Core owns it and appends pages. A `ForEach` inside a plain `VStack`/`HStack`, `forEach` adding subviews
to a `UIStackView`/`NSStackView`, or `reloadData()` on every change is a contract violation.

```swift
// Packages/Core — no SwiftUI/UIKit/AppKit import.
struct OrderRowState: Identifiable, Equatable, Sendable { let id: UUID; let title: String; let subtitle: String; let isSelected: Bool }
struct OrderListState: Equatable, Sendable { var items: [OrderRowState] = []; var isLoadingMore = false; var hasMore = true }

@MainActor @Observable final class OrdersViewModel {
  private(set) var list = OrderListState()
  func loadMore() async {
    guard list.hasMore, !list.isLoadingMore else { return }
    list.isLoadingMore = true
    defer { list.isLoadingMore = false }
    guard let page = try? await paging.page(after: list.items.last?.id) else { return }
    list.items += page.rows
    list.hasMore = page.hasMore
  }
  // init(paging:), open(_:), …
}
```

**SwiftUI** — `List` (or `LazyVStack`/`LazyHStack`/`LazyVGrid` inside a `ScrollView`, `TabView` with `.page`) with
`ForEach` over the `Identifiable` items; paging from the last row's `onAppear`:

```swift
struct OrderList: View {
  let state: OrderListState
  let onOpen: (OrderRowState.ID) -> Void
  let onLoadMore: () -> Void

  var body: some View {
    List {
      ForEach(state.items) { item in
        OrderRow(state: item, onOpen: onOpen)
          .onAppear { if item.id == state.items.last?.id { onLoadMore() } }
      }
      if state.isLoadingMore { ProgressView().frame(maxWidth: .infinity) }
    }
    .accessibilityIdentifier("orderList")
  }
}
```

**UIKit** — `UICollectionView` + compositional layout + `UICollectionViewDiffableDataSource<Section, Item.ID>` +
`CellRegistration` (`uikit.md`), wrapped in a child `OrderListViewController` that exposes `render(_:)`/`onOpen`/
`onLoadMore`; item content from a `UIListContentConfiguration`, or the SwiftUI item component through
`UIHostingConfiguration { OrderRow(…) }`:

```swift
func render(_ state: OrderListState) {
  let previous = itemsByID
  itemsByID = Dictionary(uniqueKeysWithValues: state.items.map { ($0.id, $0) })
  var snapshot = NSDiffableDataSourceSnapshot<Int, OrderRowState.ID>()
  snapshot.appendSections([0])
  snapshot.appendItems(state.items.map(\.id))
  snapshot.reconfigureItems(state.items.filter { item in previous[item.id].map { $0 != item } ?? false }.map(\.id))
  dataSource.apply(snapshot, animatingDifferences: !previous.isEmpty)
}
// collectionView(_:willDisplay:forItemAt:) on the last index → onLoadMore?(); didSelectItemAt → onOpen?(id)
```

**AppKit** — view-based `NSTableView` (or `NSCollectionView`) with `NSTableViewDiffableDataSource<Int, Item.ID>`
whose cell provider reuses with `makeView(withIdentifier:owner:)`, same `render(_:)` shape; changed rows are refreshed
with `reloadData(forRowIndexes:columnIndexes:)` for the ids whose state changed, selection goes out through
`tableViewSelectionDidChange(_:)` → `onOpen?(id)`.

Selection, expansion and scroll anchors live in the view model keyed by id (`selectedID`), never in a reused cell.
Many tabs: SwiftUI `TabView` with `.sidebarAdaptable`/`.page`, UIKit `UITabBarController.tabs` with
`viewControllerProvider` (created lazily). The sketches above were type-checked for iOS 18, macOS 15 and visionOS 2.

## UI tests — what runs where

| Level | Where | How |
|---|---|---|
| Component | The app's hosted unit-test target (`Targets/<P>/Tests`, `App/Tests`) — or the package's test target for a SwiftUI package (`swift test`) | Swift Testing, one parameterized `@Test(arguments: UIQuality.directions, UIQuality.themes)` per visual state: render fixture states with `UIScreenshots.render` (SwiftUI) or `UIScreenshots.capture` (UIKit/AppKit views); UIKit/AppKit events by sending actions (`sendActions(for: .primaryActionTriggered)`, `performClick(nil)`) and asserting the closure's payload |
| Screen | The UI-test target (`…/UITests`), XCUITest | Launch with `--uitesting --fixture <data-set>` (the composition root swaps in fake repositories), assert states and that taps reach the view model (visible effect), capture with `XCUIScreen.main.screenshot()` |
| Flow | The UI-test target | One test per flow driving real navigation, capturing `flow/<flow>/NN-<step>--…` at each step |

- **SwiftUI component events**: SwiftUI has no first-party API to tap a view inside a unit test, so a SwiftUI
  component's events are asserted where it is hosted — the screen's XCUITest taps it by accessibility identifier and
  checks the effect. Don't add a view-inspection dependency unless the repository already has one; report the
  screen-level coverage as the component's event test.
- **`ImageRenderer` limits**: it draws SwiftUI-native content only. `List`, `ScrollView`, `TextField`, `SecureField`,
  `Toggle`, `Map`, web views and any `UIViewRepresentable`/`NSViewRepresentable` come out as a yellow placeholder —
  verified. Capture leaf components (rows, cards, badges, empty/error views) with it; capture collections and screens
  through XCUITest.
- **Where they run**: iOS/iPadOS/watchOS/visionOS tests run in a simulator (`xcodebuild test -destination
  'platform=iOS Simulator,name=…'`), serialized by `.simulator-test.lock`; macOS unit tests run on the host, macOS
  XCUITest drives the real desktop (`testing.md`). No simulator/runtime, or not macOS → the UI stage is
  **unverified**. This skill writes the tests; `iru-swift-code-one-task-group` runs them through `iru-swift-test`.
- **Determinism**: `--uitesting` disables animations (`UIView.setAnimationsEnabled(false)`), skips onboarding and
  permission prompts and starts from an in-memory store; inject a fixed `Clock`/`Date` provider and pin a locale per
  variant; fixed simulator model per destination; fixtures (`minimal-data`, `typical-data`, `many-items`, `long-content`
  with Arabic/Hebrew strings) are Core values shared by unit tests and the `--fixture` switch.

### Switching direction, theme, contrast and text size

| Matrix cell | Component test (`ImageRenderer` / native view) | XCUITest (whole app) |
|---|---|---|
| right-to-left | `.environment(\.layoutDirection, .rightToLeft)`; UIKit `window.traitOverrides.layoutDirection = .rightToLeft` (iOS 17+); AppKit `userInterfaceLayoutDirection = .rightToLeft` on the view tree — all three verified to flip leading/trailing | `launchArguments += ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-AppleTextDirection", "YES", "-NSForceRightToLeftWritingDirection", "YES"]` |
| dark | `.environment(\.colorScheme, .dark)`; UIKit `window.overrideUserInterfaceStyle = .dark`; AppKit `view.appearance = NSAppearance(named: .darkAqua)` | iOS/visionOS `XCUIDevice.shared.appearance = .dark` before `launch()`; macOS `-AppleInterfaceStyle Dark`; watchOS is always dark (capture `dark` only, and say so) |
| high contrast (optional) | — (`colorSchemeContrast` is read-only in the environment) | `xcrun simctl ui <device> increase_contrast enabled` before the run, captured as theme `high-contrast` |
| largest text (optional) | `.environment(\.dynamicTypeSize, .accessibility5)`; UIKit `traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge` | `launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]`, variant `large-text` |

Behaviour assertions run once (left-to-right, light); only the captures iterate the matrix. Relaunch the app per
matrix cell — launch arguments apply at launch.

## Screenshot helper

One file, **`UIScreenshots.swift`**, in a `Support/` folder of the unit-test target (`Targets/<P>/Tests/Support/`,
`App/Tests/Support/`, or `Tests/<Target>Tests/Support/` in a package) and compiled into the UI-test target too (add
the file to that target's sources in `project.yml`/`Project.swift` — one file, two targets). It resolves
`UI_SCREENSHOTS_DIR`, else `<repository root>/.ui-screenshots` (walking up from `#filePath` to `.git`), writes the
contract's file name, and **also attaches every capture to the test** under the same path with `/` written as `~`
(`component~OrderRow~selected--rtl--dark.png`). The simulator can write host paths (the gate passes the directory as
`TEST_RUNNER_UI_SCREENSHOTS_DIR`, which `xcodebuild` forwards as `UI_SCREENSHOTS_DIR`); a sandboxed macOS host app or a
device cannot — then `iru-swift-test` recovers the files with `xcrun xcresulttool export attachments`. It also reads
`.ui-quality.yml` (`UIQuality`). Verified with Swift 6.4 / Xcode 27.1: `swift test` on macOS, `xcodebuild test` on an
iOS 27 simulator (files written and recovered from the `.xcresult`), type-checked for visionOS 2 and watchOS 11.

```swift
import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import Testing
import UniformTypeIdentifiers
import XCTest

/// `.ui-quality.yml` (UI contract §8); a missing or unreadable file means the defaults.
enum UIQuality {
  enum Accessibility: String { case enforce, report, off, none }

  static let accessibility = Accessibility(rawValue: values["accessibility"]?.first ?? "") ?? .enforce
  static let directions = (values["directions"] ?? ["ltr", "rtl"]).compactMap(UIScreenshots.Direction.init)
  static let themes = (values["themes"] ?? ["light", "dark"]).compactMap(UIScreenshots.Theme.init)
  static var matrix: [(UIScreenshots.Direction, UIScreenshots.Theme)] {
    directions.flatMap { direction in themes.map { (direction, $0) } }
  }

  private static let values: [String: [String]] = {
    guard let root = UIScreenshots.repositoryRoot(),
          let text = try? String(contentsOf: root.appending(path: ".ui-quality.yml"), encoding: .utf8)
    else { return [:] }
    var result: [String: [String]] = [:]
    for line in text.split(separator: "\n") {
      let content = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
      let parts = content.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
      guard parts.count == 2, !parts[1].isEmpty else { continue }
      result[parts[0]] = parts[1].trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        .split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
    }
    return result
  }()
}

/// Saves captures as `<dir>/<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` and attaches them.
enum UIScreenshots {
  enum Kind: String { case component, screen, flow }
  enum Direction: String { case ltr, rtl }
  enum Theme: String { case light, dark }

  static func path(
    _ kind: Kind, _ id: String, state: String, direction: Direction, theme: Theme, variants: [String] = []
  ) -> String {
    "\(kind.rawValue)/\(id)/" + ([state, direction.rawValue, theme.rawValue] + variants).joined(separator: "--") + ".png"
  }

  @MainActor
  static func record(_ png: Data, at path: String) {
    let name = path.replacingOccurrences(of: "/", with: "~")
    if Test.current != nil {
      Attachment.record(png, named: name)
    } else {
      let attachment = XCTAttachment(data: png, uniformTypeIdentifier: UTType.png.identifier)
      attachment.name = name
      attachment.lifetime = .keepAlways
      XCTContext.runActivity(named: "Screenshot \(path)") { $0.add(attachment) }
    }
    guard let url = directory()?.appending(path: path) else { return }
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? png.write(to: url)
  }

  /// SwiftUI-native content only (see the ImageRenderer limits above).
  @MainActor
  static func render(
    _ view: some View, _ kind: Kind = .component, id: String, state: String, direction: Direction, theme: Theme,
    variants: [String] = [], width: CGFloat = 390
  ) throws {
    let renderer = ImageRenderer(content: view.frame(width: width).background(.background)
      .environment(\.layoutDirection, direction == .rtl ? .rightToLeft : .leftToRight)
      .environment(\.colorScheme, theme == .dark ? .dark : .light))
    renderer.scale = 2
    let image = try #require(renderer.cgImage, "ImageRenderer produced no image for \(id)")
    record(try #require(pngData(image)), at: path(kind, id, state: state, direction: direction, theme: theme,
                                                  variants: variants))
  }

  // capture(_ view: UIView | NSView, …) — same signature with `size: CGSize`, behind `#if os(iOS) || os(visionOS)` /
  // `#elseif os(macOS)`: a UIWindow with traitOverrides.layoutDirection + overrideUserInterfaceStyle and
  // UIGraphicsImageRenderer.pngData { view.layer.render(in: $0.cgContext) }; or NSView.appearance +
  // userInterfaceLayoutDirection (recursively) + bitmapImageRepForCachingDisplay/cacheDisplay → .png.

  static func pngData(_ image: CGImage) -> Data? {
    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)
    else { return nil }
    CGImageDestinationAddImage(destination, image, nil)
    return CGImageDestinationFinalize(destination) ? data as Data : nil
  }

  static func repositoryRoot(file: String = #filePath) -> URL? {
    var url = URL(filePath: file).deletingLastPathComponent()
    while url.path() != "/" {
      if FileManager.default.fileExists(atPath: url.appending(path: ".git").path()) { return url }
      url.deleteLastPathComponent()
    }
    return nil
  }

  private static func directory() -> URL? {
    if let path = ProcessInfo.processInfo.environment["UI_SCREENSHOTS_DIR"], !path.isEmpty { return URL(filePath: path) }
    return repositoryRoot()?.appending(path: ".ui-screenshots")
  }
}
```

The scaffolds (`iru-setup-apple-app`, `iru-setup-macos-app`) ship the complete file, `capture(_:)` included — copy it
from there when a repository lacks it, and match the project's `.swift-format` indentation. Usage:

```swift
@Suite("OrderRow") @MainActor
struct OrderRowTests {
  @Test("selected", arguments: UIQuality.directions, UIQuality.themes)
  func selected(direction: UIScreenshots.Direction, theme: UIScreenshots.Theme) throws {
    try UIScreenshots.render(OrderRow(state: .fixture(selected: true), onOpen: { _ in }), id: "OrderRow",
                             state: "selected", direction: direction, theme: theme)
  }
}
// XCUITest: UIScreenshots.record(XCUIScreen.main.screenshot().pngRepresentation,
//   at: UIScreenshots.path(.screen, "OrdersScreen", state: "typical-data", direction: d, theme: t))
```

In a multiplatform app, append the platform variant (`variants: ["ios"]`, `"macos"`, `"watchos"`, `"visionos"`)
when the same `<Id>` renders on several platforms, then the form factor (`"tablet"`) where it differs.

## Accessibility checks

- **Coding defaults** (`ios.md` "Dynamic Type and accessibility" applies on every platform): every control has a label
  (`Button("Add", systemImage: "plus")`, `.accessibilityLabel` for icon-only), decorative images
  `.accessibilityHidden(true)`, rows combined (`.accessibilityElement(children: .combine)`), 44×44 pt hit targets,
  text styles + `@ScaledMetric`/`UIFontMetrics`, semantic colours that pass contrast in both appearances,
  `accessibilityReduceMotion` honoured, `AccessibilityNotification.Announcement`/`UIAccessibility.post` for async
  changes. `accessibilityIdentifier` is for tests only.
- **Automated check**: one XCUITest per screen calls `try app.performAccessibilityAudit(for: .all) { issue in … }`
  (Xcode 15+, iOS 17/macOS 14/watchOS 10/visionOS 1+). Its issue handler attaches every issue
  (`XCTAttachment(string: issue.compactDescription)` named `a11y~<Screen>~<rule>`, kept always — `iru-swift-test`
  counts these) and returns `false` to fail the test or `true` to accept it: `false` when `UIQuality.accessibility ==
  .enforce`, `true` when `.report`; with `.off` or `.none` the test skips itself (`try XCTSkipIf(UIQuality.accessibility == .off || UIQuality.accessibility == .none)`). Rule ids come from `issue.auditType`: `contrast`, `element-detection`,
  `hit-region`, `sufficient-element-description` everywhere; `dynamic-type`, `text-clipped`, `trait` on
  iOS/watchOS/visionOS only; `action`, `parent-child` on macOS only — the compiler rejects the others per platform.
  Ignoring a known false positive is a commented `return true` for that exact element and audit type, never a blanket
  `true`.
- SwiftLint has no accessibility rules; the audit is the gate.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- VoiceOver: `accessibilityLabel`/`Value`/`Hint`/`Traits` (SwiftUI `.accessibilityLabel`, `.accessibilityElement(children:)`, `.accessibilityAction`); decorative images `.accessibilityHidden(true)`; UIKit sets `isAccessibilityElement` and `UIAccessibility.post(notification:argument:)` for announcements.
- Dynamic Type (`Font.TextStyle`/`UIFontMetrics`, `@ScaledMetric`) without clipping; check `accessibilityReduceMotion`, `accessibilityDifferentiateWithoutColor`, Increase Contrast (`colorSchemeContrast`/`UIAccessibility.isDarkerSystemColorsEnabled`), Reduce Transparency.
- Platform notes: iOS/iPadOS targets >= 44pt; macOS: full keyboard access, `NSAccessibility` roles on AppKit custom views; watchOS: concise labels, Digital Crown actions; visionOS: gaze/pinch targets >= 60pt, label spatial controls.

## Sources

- SwiftUI `ImageRenderer`: <https://developer.apple.com/documentation/swiftui/imagerenderer>
- `List`: <https://developer.apple.com/documentation/swiftui/list>; Creating performant scrollable stacks:
  <https://developer.apple.com/documentation/swiftui/creating-performant-scrollable-stacks>; `LazyVGrid`:
  <https://developer.apple.com/documentation/swiftui/lazyvgrid>
- `UICollectionViewDiffableDataSource`: <https://developer.apple.com/documentation/uikit/uicollectionviewdiffabledatasource-9tqpa>;
  `UICollectionView.CellRegistration`: <https://developer.apple.com/documentation/uikit/uicollectionview/cellregistration>;
  `UIHostingConfiguration`: <https://developer.apple.com/documentation/swiftui/uihostingconfiguration>
- `NSTableViewDiffableDataSource`: <https://developer.apple.com/documentation/appkit/nstableviewdiffabledatasource>;
  `makeView(withIdentifier:owner:)`: <https://developer.apple.com/documentation/appkit/nstableview/makeview(withidentifier:owner:)>
- Implementing a container view controller: <https://developer.apple.com/documentation/uikit/creating-a-custom-container-view-controller>
- XCUIAutomation `XCUIScreen`: <https://developer.apple.com/documentation/xcuiautomation/xcuiscreen>; `XCUIDevice.appearance`:
  <https://developer.apple.com/documentation/xctest/xcuidevice/appearance>; `XCTAttachment`:
  <https://developer.apple.com/documentation/xctest/xctattachment>; Swift Testing attachments:
  <https://developer.apple.com/documentation/testing/attachments>
- `performAccessibilityAudit(for:_:)`: <https://developer.apple.com/documentation/xcuiautomation/xcuiapplication/performaccessibilityaudit(for:_:)>;
  WWDC23 Perform accessibility audits for your app: <https://developer.apple.com/videos/play/wwdc2023/10035/>
- Right to left (HIG): <https://developer.apple.com/design/human-interface-guidelines/right-to-left>; Preparing your
  interface for localization: <https://developer.apple.com/documentation/xcode/preparing-your-interface-for-localization>;
  Testing localizations (launch arguments): <https://developer.apple.com/documentation/xcode/testing-localizations-when-running-your-app>
- `xcresulttool export attachments` (`xcrun xcresulttool help export attachments`, Xcode 16+)
- irurueta docs: apps/apple/accessibility.adoc (audits in UI tests, Dynamic Type), apps/apple/ui-tests-test-plans-and-coverage.adoc
  (XCUITest, launch arguments, attachments)
