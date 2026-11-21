# `Targets/<Platform>/Tests` and `Targets/<Platform>/UITests` templates

- **`<AppName>Tests-<Platform>`** (`bundle.unit-test`, hosted by the app — `TEST_HOST` pinned in `project.yml`,
  Swift Testing): tests of the app-target glue — the SwiftUI screen lays out, the UIKit view controller wires its
  widgets to the controller/view model, the scene manifest names `SceneDelegate`, `--uitesting` is honoured. The
  screen logic itself is tested in `Packages/Core` (`CoreTests`); the app target is **not** part of the coverage bar.
- **`<AppName>UITests-<Platform>`** (`bundle.ui-testing`, XCTest/XCUIAutomation — Swift Testing has no UI driver):
  launches the real app with `--uitesting`, types a name, taps Greet and reads the greeting (watchOS: only checks
  the screen appears, since typing goes through the system input sheet). Every app `xcodebuild test` is serialized
  behind `.simulator-test.lock` by the Swift gates.

- **`Targets/<Platform>/Tests/Support/UIScreenshots.swift`** — the UI contract's shared screenshot helper
  (`UIQuality` reads `.ui-quality.yml`; `UIScreenshots` writes `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png`
  under `UI_SCREENSHOTS_DIR` or `<repository root>/.ui-screenshots` and attaches each capture to the test), compiled
  into **both** test targets (the UI-test target lists the file in its sources — `project-templates.md`).

One copy per platform folder; the files use `#if os(...)` so the same text compiles on every platform. Rendered and
run on 2026-10-16 (see SKILL.md Known quirks for the per-platform results).

### `Targets/<Platform>/Tests/Support/UIScreenshots.swift` (every platform)

Component tests call `try UIScreenshots.render(<SwiftUI view>, id: "<Type>", state: "<state>", direction:, theme:)`
from a `@Test(arguments: UIQuality.directions, UIQuality.themes)` (SwiftUI-native leaves only — `ImageRenderer` draws
`List`/`TextField`/`Toggle` as placeholders), or `UIScreenshots.capture(<UIView/NSView>, …)` for UIKit/AppKit views.

```swift
import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import Testing
import UniformTypeIdentifiers
import XCTest

#if os(iOS) || os(visionOS)
    import UIKit
#elseif os(macOS)
    import AppKit
#endif

// Shared by the unit-test and UI-test targets (UI contract: iru-swift-code-one-task reference/ui-contract.md).

/// The repository's `.ui-quality.yml`; a missing or unreadable file means the defaults.
enum UIQuality {
    enum Accessibility: String { case enforce, report, off, none }

    static let accessibility = Accessibility(rawValue: values["accessibility"]?.first ?? "") ?? .enforce
    static let directions = (values["directions"] ?? ["ltr", "rtl"]).compactMap(UIScreenshots.Direction.init)
    static let themes = (values["themes"] ?? ["light", "dark"]).compactMap(UIScreenshots.Theme.init)

    /// Every direction × theme pair to capture.
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

/// Saves captures as `<UI_SCREENSHOTS_DIR | <repository root>/.ui-screenshots>/<kind>/<Id>/
/// <state>--<direction>--<theme>[--<variant>…].png` and attaches each one to the running test as
/// `<kind>~<Id>~<file>`, so `iru-swift-test` can recover it from the `.xcresult` when the test process can't write
/// to the host disk (a sandboxed macOS host app, a device).
enum UIScreenshots {
    enum Kind: String { case component, screen, flow }
    enum Direction: String { case ltr, rtl }
    enum Theme: String { case light, dark }

    /// The contract's relative path for one capture.
    static func path(
        _ kind: Kind, _ id: String, state: String, direction: Direction, theme: Theme, variants: [String] = []
    ) -> String {
        "\(kind.rawValue)/\(id)/" + ([state, direction.rawValue, theme.rawValue] + variants).joined(separator: "--")
            + ".png"
    }

    /// Writes PNG data under the screenshots directory (best effort) and attaches it to the current test.
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
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? png.write(to: url)
    }

    /// Renders a SwiftUI component with `ImageRenderer`. SwiftUI-native content only: `List`, `ScrollView`,
    /// `TextField`, `Toggle` and UIKit/AppKit-backed views render as placeholders — capture those in XCUITest.
    @MainActor
    static func render(
        _ view: some View, _ kind: Kind = .component, id: String, state: String, direction: Direction,
        theme: Theme, variants: [String] = [], width: CGFloat = 390
    ) throws {
        let content = view.frame(width: width).background(.background)
            .environment(\.layoutDirection, direction == .rtl ? .rightToLeft : .leftToRight)
            .environment(\.colorScheme, theme == .dark ? .dark : .light)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        let image = try #require(renderer.cgImage, "ImageRenderer produced no image for \(id)")
        let png = try #require(pngData(image))
        record(png, at: path(kind, id, state: state, direction: direction, theme: theme, variants: variants))
    }

    #if os(iOS) || os(visionOS)
        /// Captures a UIKit view (a component, or a view controller's `view`) at a fixed size.
        @MainActor
        static func capture(
            _ view: UIView, _ kind: Kind = .component, id: String, state: String, direction: Direction,
            theme: Theme, size: CGSize = CGSize(width: 390, height: 844), variants: [String] = []
        ) throws {
            let window = UIWindow(frame: CGRect(origin: .zero, size: size))
            window.traitOverrides.layoutDirection = direction == .rtl ? .rightToLeft : .leftToRight
            window.overrideUserInterfaceStyle = theme == .dark ? .dark : .light
            view.frame = window.bounds
            window.addSubview(view)
            window.isHidden = false
            view.layoutIfNeeded()
            let png = UIGraphicsImageRenderer(bounds: view.bounds).pngData { view.layer.render(in: $0.cgContext) }
            record(png, at: path(kind, id, state: state, direction: direction, theme: theme, variants: variants))
        }
    #elseif os(macOS)
        /// Captures an AppKit view (a component, or a view controller's `view`) at a fixed size.
        @MainActor
        static func capture(
            _ view: NSView, _ kind: Kind = .component, id: String, state: String, direction: Direction,
            theme: Theme, size: CGSize = CGSize(width: 480, height: 320), variants: [String] = []
        ) throws {
            view.frame = CGRect(origin: .zero, size: size)
            view.appearance = NSAppearance(named: theme == .dark ? .darkAqua : .aqua)
            setDirection(direction == .rtl ? .rightToLeft : .leftToRight, of: view)
            view.layoutSubtreeIfNeeded()
            let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
            view.cacheDisplay(in: view.bounds, to: bitmap)
            let png = try #require(bitmap.representation(using: .png, properties: [:]))
            record(png, at: path(kind, id, state: state, direction: direction, theme: theme, variants: variants))
        }

        @MainActor
        private static func setDirection(_ direction: NSUserInterfaceLayoutDirection, of view: NSView) {
            view.userInterfaceLayoutDirection = direction
            view.subviews.forEach { setDirection(direction, of: $0) }
        }
    #endif

    /// PNG-encodes a `CGImage` with ImageIO (no UIKit/AppKit needed).
    static func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// The directory holding `.git`, walking up from this source file.
    static func repositoryRoot(file: String = #filePath) -> URL? {
        var url = URL(filePath: file).deletingLastPathComponent()
        while url.path() != "/" {
            if FileManager.default.fileExists(atPath: url.appending(path: ".git").path()) { return url }
            url.deleteLastPathComponent()
        }
        return nil
    }

    private static func directory() -> URL? {
        if let path = ProcessInfo.processInfo.environment["UI_SCREENSHOTS_DIR"], !path.isEmpty {
            return URL(filePath: path)
        }
        return repositoryRoot()?.appending(path: ".ui-screenshots")
    }
}
```


### `Targets/<Platform>/Tests/LaunchOptionsTests.swift` (every platform)

```swift
import Testing

@testable import <AppName>

#if os(iOS) || os(visionOS)
    import UIKit
#endif

@MainActor
@Suite("LaunchOptions")
struct LaunchOptionsTests {
    @Test("a normal launch changes nothing")
    func normalLaunch() {
        #expect(!LaunchOptions.apply(arguments: ["app"]))
    }

    @Test("--uitesting is recognized")
    func uiTestingLaunch() {
        #if os(iOS) || os(visionOS)
            defer { UIView.setAnimationsEnabled(true) }
        #endif
        #expect(LaunchOptions.apply(arguments: ["app", LaunchOptions.uiTesting]))
        #if os(iOS) || os(visionOS)
            #expect(!UIView.areAnimationsEnabled)
        #endif
    }
}
```

### `Targets/<Platform>/Tests/<AppName>Tests.swift` (ui: swiftui or a non-iOS platform)

```swift
import SwiftUI
import Testing

@testable import <AppName>

#if os(iOS) || os(visionOS)
    import UIKit
#elseif os(macOS)
    import AppKit
#endif

/// Smoke tests of the SwiftUI layer. The screen logic is tested in Packages/Core (CoreTests).
@MainActor
@Suite("SwiftUI views")
struct <AppName>Tests {
    @Test("the greeting screen lays out")
    func contentViewLaysOut() {
        #if os(iOS) || os(visionOS)
            let host = UIHostingController(rootView: ContentView())
            let size = host.sizeThatFits(in: CGSize(width: 390, height: 844))
            #expect(size.width > 0)
            #expect(size.height > 0)
        #elseif os(macOS)
            let host = NSHostingView(rootView: ContentView())
            host.layoutSubtreeIfNeeded()
            #expect(host.fittingSize.width > 0)
        #else
            _ = ContentView()
        #endif
    }
}
```

### `Targets/iOS/Tests/GreetingViewControllerTests.swift` (ui: uikit, architecture: mvc)

```swift
import Core
import Testing
import UIKit

@testable import <AppName>

@MainActor
@Suite("GreetingViewController (MVC)")
struct GreetingViewControllerTests {
    private func makeLoadedController() -> GreetingViewController {
        let controller = GreetingViewController()
        controller.loadViewIfNeeded()
        return controller
    }

    private func type(_ text: String, into controller: GreetingViewController) {
        controller.nameField.text = text
        controller.nameField.sendActions(for: .editingChanged)
    }

    @Test("starts with Greet disabled and no greeting")
    func initialState() {
        let controller = makeLoadedController()
        #expect(controller.title == "<app-name>")
        #expect(!controller.greetButton.isEnabled)
        #expect(controller.greetingLabel.text?.isEmpty != false)
        #expect(controller.errorLabel.isHidden)
    }

    @Test("tapping Greet shows the greeting for the typed name")
    func greetButton() {
        let controller = makeLoadedController()
        type("Ada", into: controller)
        #expect(controller.greetButton.isEnabled)
        controller.greetButton.sendActions(for: .primaryActionTriggered)
        #expect(controller.greetingLabel.text == "Hello, Ada!")
    }

    @Test("Return greets only when Greet is enabled", arguments: [("Ada", "Hello, Ada!"), ("  ", "")])
    func returnKey(name: String, expected: String) {
        let controller = makeLoadedController()
        type(name, into: controller)
        #expect(controller.textFieldShouldReturn(controller.nameField))
        #expect((controller.greetingLabel.text ?? "") == expected)
    }

    @Test("a blank name shows the validation error")
    func blankName() {
        let controller = makeLoadedController()
        type("  ", into: controller)
        controller.greet()
        #expect(!controller.errorLabel.isHidden)
        #expect(controller.errorLabel.text == "Enter a name first.")
        #expect(controller.greetingLabel.text?.isEmpty != false)
    }
}
```

### `Targets/iOS/Tests/GreetingViewControllerTests.swift` (ui: uikit, architecture: mvvm)

```swift
import Core
import Testing
import UIKit

@testable import <AppName>

@MainActor
@Suite("GreetingViewController (MVVM)")
struct GreetingViewControllerTests {
    /// Loads the controller and lays its view out once, so UIKit records which view-model properties
    /// `viewWillLayoutSubviews()` read (Observation tracking).
    private func makeLaidOutController() -> GreetingViewController {
        let controller = GreetingViewController()
        controller.loadViewIfNeeded()
        controller.view.layoutIfNeeded()
        return controller
    }

    private func type(_ text: String, into controller: GreetingViewController) {
        controller.nameField.text = text
        controller.nameField.sendActions(for: .editingChanged)
        controller.view.layoutIfNeeded()
    }

    @Test("starts with Greet disabled and no greeting")
    func initialState() {
        let controller = makeLaidOutController()
        #expect(controller.title == "<app-name>")
        #expect(!controller.greetButton.isEnabled)
        #expect(controller.greetingLabel.text?.isEmpty != false)
        #expect(controller.errorLabel.isHidden)
    }

    @Test("typing writes the view model; Observation tracking re-renders the view")
    func greetButton() {
        let controller = makeLaidOutController()
        type("Ada", into: controller)
        #expect(controller.viewModel.name == "Ada")
        #expect(controller.greetButton.isEnabled)
        controller.greetButton.sendActions(for: .primaryActionTriggered)
        controller.view.layoutIfNeeded()
        #expect(controller.greetingLabel.text == "Hello, Ada!")
    }

    @Test("Return greets only when the name can be greeted", arguments: [("Ada", "Hello, Ada!"), ("  ", "")])
    func returnKey(name: String, expected: String) {
        let controller = makeLaidOutController()
        type(name, into: controller)
        #expect(controller.textFieldShouldReturn(controller.nameField))
        controller.view.layoutIfNeeded()
        #expect((controller.greetingLabel.text ?? "") == expected)
    }

    @Test("a blank name shows the validation error")
    func blankName() {
        let controller = makeLaidOutController()
        type("  ", into: controller)
        controller.greet()
        controller.view.layoutIfNeeded()
        #expect(!controller.errorLabel.isHidden)
        #expect(controller.errorLabel.text == "Enter a name first.")
    }
}
```


These MVVM tests never refresh the view by hand — they only call `layoutIfNeeded()`, so they pass **only** when
Observation tracking actually re-runs `viewWillLayoutSubviews()`. Verified: with `min-deployment-target: 18.0` on
an iOS 18.5 simulator they pass with `UIObservationTrackingEnabled` and three of them fail without it.


### `Targets/iOS/Tests/AppShellTests.swift` (ui: uikit)

```swift
import Testing
import UIKit

@testable import <AppName>

@MainActor
@Suite("App shell")
struct AppShellTests {
    @Test("Info.plist declares the scene manifest with SceneDelegate")
    func sceneManifest() throws {
        let manifest = try #require(
            Bundle.main.object(forInfoDictionaryKey: "UIApplicationSceneManifest") as? [String: Any])
        let configurations = try #require(manifest["UISceneConfigurations"] as? [String: Any])
        let roles = try #require(configurations["UIWindowSceneSessionRoleApplication"] as? [[String: Any]])
        let delegateClassName = try #require(roles.first?["UISceneDelegateClassName"] as? String)
        #expect(NSClassFromString(delegateClassName) == SceneDelegate.self)
    }

    @Test("every window starts at the greeting screen inside a navigation controller")
    func rootViewController() {
        let root = SceneDelegate.makeRootViewController()
        #expect(root.topViewController is GreetingViewController)
    }

    @Test("the app delegate finishes launching")
    func appDelegate() {
        #expect(AppDelegate().application(UIApplication.shared, didFinishLaunchingWithOptions: nil))
    }
}
```

### `Targets/<Platform>/UITests/<AppName>UITests.swift` (every platform)

The screen tests of the catalog's UI contract (`iru-swift-code-one-task`'s `reference/ui-contract.md`): the behaviour
test runs once (left-to-right, light), `testCapturesTheGreetingScreen` relaunches the app per `.ui-quality.yml`
direction × theme (right-to-left through `-AppleLanguages (ar)` + `-AppleTextDirection`/`-NSForceRightToLeftWritingDirection`;
dark through `XCUIDevice.shared.appearance`, or `-AppleInterfaceStyle Dark` on macOS; watchOS is dark only) and
records `.ui-screenshots/screen/<ScreenType>/default--<direction>--<theme>--<platform-variant>.png`, and
`testGreetingScreenAccessibility` runs `performAccessibilityAudit()` at the configured level (`enforce` fails,
`report` passes, both attach each issue as `a11y~<ScreenType>~<rule>`; `off` skips).

```swift
import XCTest

/// End-to-end tests that launch the real app and drive it like a person (XCUIAutomation).
final class <AppName>UITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    private func launchApp(
        direction: UIScreenshots.Direction = .ltr, theme: UIScreenshots.Theme = .light
    ) -> XCUIApplication {
        let app = XCUIApplication()
        #if os(macOS)
            // Start from a fresh window instead of restoring the last session. Keep --uitesting LAST: the
            // argument domain reads "-key value" pairs, so a leading "--uitesting" would swallow the next flag.
            app.launchArguments += ["-ApplePersistenceIgnoreState", "YES"]
            if theme == .dark {
                app.launchArguments += ["-AppleInterfaceStyle", "Dark"]
            }
        #elseif !os(watchOS)
            XCUIDevice.shared.appearance = theme == .dark ? .dark : .light
        #endif
        app.launchArguments +=
            direction == .rtl
            ? [
                "-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-AppleTextDirection", "YES",
                "-NSForceRightToLeftWritingDirection", "YES",
            ]
            : ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchArguments.append("--uitesting")
        app.launch()
        return app
    }

    #if os(watchOS)
        @MainActor
        func testShowsTheGreetingScreen() throws {
            let app = launchApp()
            let greetButton = app.buttons["greetButton"]
            XCTAssertTrue(greetButton.waitForExistence(timeout: 10))
            XCTAssertFalse(greetButton.isEnabled)
        }
    #else
        @MainActor
        func testGreetsTheTypedName() throws {
            let app = launchApp()
            let nameField = app.textFields["nameField"]
            XCTAssertTrue(nameField.waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["greetButton"].isEnabled)

            press(nameField)
            nameField.typeText("Ada")
            press(app.buttons["greetButton"])

            let greeting = app.staticTexts["greetingLabel"]
            XCTAssertTrue(greeting.waitForExistence(timeout: 5))
            #if os(macOS)
                XCTAssertEqual(greeting.value as? String, "Hello, Ada!")
            #else
                XCTAssertEqual(greeting.label, "Hello, Ada!")
            #endif
        }

        /// Taps on iOS and visionOS, clicks on macOS.
        @MainActor
        private func press(_ element: XCUIElement) {
            #if os(macOS)
                element.click()
            #else
                element.tap()
            #endif
        }
    #endif

    /// Captures the greeting screen in every `.ui-quality.yml` direction × theme (UI contract §6–§7).
    @MainActor
    func testCapturesTheGreetingScreen() throws {
        #if os(watchOS)
            let matrix = UIQuality.matrix.filter { $0.1 == .dark }  // watchOS has no light appearance
        #else
            let matrix = UIQuality.matrix
        #endif
        for (direction, theme) in matrix {
            let app = launchApp(direction: direction, theme: theme)
            XCTAssertTrue(app.buttons["greetButton"].waitForExistence(timeout: 10))
            UIScreenshots.record(
                app.windows.firstMatch.screenshot().pngRepresentation,
                at: UIScreenshots.path(
                    .screen, "<ScreenType>", state: "default", direction: direction, theme: theme,
                    variants: ["<platform-variant>"]))
            app.terminate()
        }
    }

    /// Runs Xcode's accessibility audit at the `.ui-quality.yml` level (UI contract §8).
    @MainActor
    func testGreetingScreenAccessibility() throws {
        try XCTSkipIf(UIQuality.accessibility == .off || UIQuality.accessibility == .none, "accessibility: off or none in .ui-quality.yml")
        let app = launchApp()
        XCTAssertTrue(app.buttons["greetButton"].waitForExistence(timeout: 10))
        try app.performAccessibilityAudit { issue in
            let note = XCTAttachment(string: issue.compactDescription)
            note.name = "a11y~<ScreenType>~\(Self.ruleID(issue.auditType))"
            note.lifetime = .keepAlways
            self.add(note)
            return UIQuality.accessibility == .report  // true = accept the issue, false = fail the test
        }
    }

    private static func ruleID(_ type: XCUIAccessibilityAuditType) -> String {
        var names: [(XCUIAccessibilityAuditType, String)] = [
            (.contrast, "contrast"), (.elementDetection, "element-detection"), (.hitRegion, "hit-region"),
            (.sufficientElementDescription, "sufficient-element-description"),
        ]
        #if os(macOS)
            names += [(.action, "action"), (.parentChild, "parent-child")]
        #else
            names += [(.dynamicType, "dynamic-type"), (.textClipped, "text-clipped"), (.trait, "trait")]
        #endif
        return names.first { type.contains($0.0) }?.1 ?? "other"
    }
}
```
