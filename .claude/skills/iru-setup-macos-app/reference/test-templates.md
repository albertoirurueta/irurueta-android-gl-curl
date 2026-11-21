# `App/Tests` and `App/UITests` templates

- **`<AppName>Tests`** (`bundle.unit-test`, hosted by the app, Swift Testing): tests of the app-target glue — the
  SwiftUI views lay out, or the AppKit view controller wires its widgets to the controller/view model, the menu bar
  is complete, Settings writes its preference. The screen logic itself is tested in `Packages/Core` (CoreTests);
  the app target is **not** part of the coverage bar.
- **`<AppName>UITests`** (`bundle.ui-testing`, XCTest/XCUIAutomation — Swift Testing has no UI driver): launches the
  real app, types a name, clicks Greet and reads the greeting. It drives the real desktop session, so it is never
  run in parallel with another UI-test run (the Swift gates serialize every app `xcodebuild test` behind
  `.simulator-test.lock`), and it needs the Accessibility/Automation permission described in SKILL.md.

- **`App/Tests/Support/UIScreenshots.swift`** — the UI contract's shared screenshot helper (`UIQuality` reads
  `.ui-quality.yml`; `UIScreenshots` writes `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` under
  `UI_SCREENSHOTS_DIR` or `<repository root>/.ui-screenshots`, and attaches every capture to the test as
  `<kind>~<Id>~<file>`), compiled into **both** test targets (`project.yml` lists it in the UI-test target's sources).

The accessibility identifiers (`nameField`, `greetButton`, `greetingLabel`, `errorLabel`, `greetsOnSubmitToggle`)
are identical for both `ui` values, so the UI test is shared. Placeholders and markers work as described in
SKILL.md's "Reference files" section; rendered and run on 2026-10-16.

### `App/Tests/Support/UIScreenshots.swift`

Component tests call `try UIScreenshots.render(<SwiftUI view>, id: "<Type>", state: "<state>", direction:, theme:)`
(SwiftUI-native leaves only — `ImageRenderer` draws `List`/`TextField`/`Toggle` as placeholders) or
`try UIScreenshots.capture(<NSView>, id:, state:, direction:, theme:)` for AppKit views, from a
`@Test(arguments: UIQuality.directions, UIQuality.themes)`. The same text as `iru-setup-apple-app`'s helper.

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

### `App/Sources/LaunchOptions.swift`

```swift
import Foundation

/// Launch arguments the app understands. Shared by both `ui` values.
enum LaunchOptions {
    /// Passed by the UI tests: start from a known state (the app's own preferences back to their defaults).
    static let uiTesting = "--uitesting"

    /// Removes the app's own preferences when `arguments` contains ``uiTesting``.
    ///
    /// Only the keys the app owns are removed, never the whole defaults domain (which also holds AppKit/SwiftUI
    /// window state).
    ///
    /// - Parameters:
    ///   - arguments: The process arguments, `CommandLine.arguments` in the app.
    ///   - defaults: The preferences to reset.
    ///   - keys: The preference keys to remove.
    /// - Returns: Whether the app was launched for UI testing.
    @discardableResult
    static func apply(
        arguments: [String] = CommandLine.arguments,
        defaults: UserDefaults = .standard,
        keys: [String] = SettingsKeys.all
    ) -> Bool {
        guard arguments.contains(uiTesting) else {
            return false
        }
        for key in keys {
            defaults.removeObject(forKey: key)
        }
        return true
    }
}
```

The SwiftUI `App` calls `LaunchOptions.apply()` in its `init()`; the AppKit `AppDelegate` in
`applicationWillFinishLaunching(_:)`, before it registers the defaults. Add every new preference key to
`SettingsKeys.all`.

### `App/Tests/LaunchOptionsTests.swift`

```swift
import Foundation
import Testing

@testable import <AppName>

@Suite("LaunchOptions")
struct LaunchOptionsTests {
    private func makeDefaults() throws -> (UserDefaults, String) {
        let domain = "<bundle-id>.tests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: domain))
        defaults.set(false, forKey: SettingsKeys.greetsOnSubmit)
        return (defaults, domain)
    }

    @Test("--uitesting removes the app's preferences")
    func resetsForUITesting() throws {
        let (defaults, domain) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: domain) }
        #expect(LaunchOptions.apply(arguments: ["app", "--uitesting"], defaults: defaults))
        // The suite's own value is gone (a registered default may still answer object(forKey:)).
        #expect(defaults.persistentDomain(forName: domain)?[SettingsKeys.greetsOnSubmit] == nil)
    }

    @Test("a normal launch leaves the preferences alone")
    func normalLaunch() throws {
        let (defaults, domain) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: domain) }
        #expect(!LaunchOptions.apply(arguments: ["app"], defaults: defaults))
        #expect(defaults.persistentDomain(forName: domain)?[SettingsKeys.greetsOnSubmit] as? Bool == false)
    }
}
```

### `App/Tests/<AppName>Tests.swift` (ui: swiftui)

```swift
import AppKit
import SwiftUI
import Testing

@testable import <AppName>

/// Smoke tests of the SwiftUI layer: the views lay out in an AppKit hosting view. Screen logic is tested in
/// Packages/Core (CoreTests).
@MainActor
@Suite("SwiftUI views")
struct <AppName>Tests {
    @Test("the main window content lays out at its minimum size")
    func contentViewLaysOut() {
        let host = NSHostingView(rootView: ContentView())
        host.layoutSubtreeIfNeeded()
        #expect(host.fittingSize.width >= 360)
        #expect(host.fittingSize.height >= 200)
    }

    @Test("the settings window content has a fixed size")
    func settingsViewLaysOut() {
        let host = NSHostingView(rootView: SettingsView())
        host.layoutSubtreeIfNeeded()
        #expect(host.fittingSize == NSSize(width: 420, height: 180))
    }

    @Test("the settings key is stable")
    func settingsKey() {
        #expect(SettingsKeys.greetsOnSubmit == "greetsOnSubmit")
    }
}
```

### `App/Tests/TestDefaults.swift` (ui: appkit)

```swift
import Foundation

@testable import <AppName>

/// An isolated, empty user-defaults suite for one test, so tests never read or write the app's real preferences.
func makeTestDefaults(greetsOnSubmit: Bool = true) -> UserDefaults {
    let suiteName = "<bundle-id>.tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName) ?? .standard
    defaults.removePersistentDomain(forName: suiteName)
    defaults.set(greetsOnSubmit, forKey: SettingsKeys.greetsOnSubmit)
    return defaults
}
```

### `App/Tests/MainViewControllerTests.swift` (ui: appkit, architecture: mvc)

```swift
import AppKit
import Core
import Testing

@testable import <AppName>

@MainActor
@Suite("MainViewController (MVC)")
struct MainViewControllerTests {
    private func makeLoadedController(greetsOnSubmit: Bool = true) -> MainViewController {
        let controller = MainViewController(defaults: makeTestDefaults(greetsOnSubmit: greetsOnSubmit))
        controller.loadViewIfNeeded()
        return controller
    }

    private func type(_ text: String, into controller: MainViewController) {
        controller.nameField.stringValue = text
        controller.controlTextDidChange(
            Notification(name: NSControl.textDidChangeNotification, object: controller.nameField))
    }

    @Test("starts with Greet disabled and no greeting")
    func initialState() {
        let controller = makeLoadedController()
        #expect(!controller.greetButton.isEnabled)
        #expect(controller.greetingLabel.stringValue.isEmpty)
        #expect(controller.errorLabel.isHidden)
    }

    @Test("clicking Greet shows the greeting for the typed name")
    func greetButton() {
        let controller = makeLoadedController()
        type("Ada", into: controller)
        #expect(controller.greetButton.isEnabled)
        controller.greetButton.performClick(nil)
        #expect(controller.greetingLabel.stringValue == "Hello, Ada!")
    }

    @Test("Return greets only when the setting is on", arguments: [true, false])
    func returnKey(greetsOnSubmit: Bool) {
        let controller = makeLoadedController(greetsOnSubmit: greetsOnSubmit)
        type("Ada", into: controller)
        controller.nameField.sendAction(controller.nameField.action, to: controller.nameField.target)
        #expect(controller.greetingLabel.stringValue == (greetsOnSubmit ? "Hello, Ada!" : ""))
    }

    @Test("a blank name shows the validation error")
    func blankName() {
        let controller = makeLoadedController()
        type("  ", into: controller)
        controller.greet(nil)
        #expect(!controller.errorLabel.isHidden)
        #expect(controller.errorLabel.stringValue == "Enter a name first.")
        #expect(controller.greetingLabel.stringValue.isEmpty)
    }

    @Test("the Greet menu item follows the button; other items stay enabled")
    func menuValidation() {
        let controller = makeLoadedController()
        let greet = NSMenuItem(title: "Greet", action: #selector(MainViewController.greet(_:)), keyEquivalent: "")
        let other = NSMenuItem(title: "Other", action: #selector(NSText.copy(_:)), keyEquivalent: "")
        #expect(!controller.validateMenuItem(greet))
        type("Ada", into: controller)
        #expect(controller.validateMenuItem(greet))
        #expect(controller.validateMenuItem(other))
    }
}
```

### `App/Tests/MainViewControllerTests.swift` (ui: appkit, architecture: mvvm)

```swift
import AppKit
import Core
import Testing

@testable import <AppName>

@MainActor
@Suite("MainViewController (MVVM)")
struct MainViewControllerTests {
    /// Loads the controller into a window, so AppKit runs layout (and with it Observation tracking) on demand.
    private func makeShownController(greetsOnSubmit: Bool = true) -> (MainViewController, NSWindow) {
        let controller = MainViewController(defaults: makeTestDefaults(greetsOnSubmit: greetsOnSubmit))
        let window = NSWindow(contentViewController: controller)
        window.layoutIfNeeded()
        return (controller, window)
    }

    private func type(_ text: String, into controller: MainViewController, window: NSWindow) {
        controller.nameField.stringValue = text
        controller.controlTextDidChange(
            Notification(name: NSControl.textDidChangeNotification, object: controller.nameField))
        window.layoutIfNeeded()
    }

    @Test("starts with Greet disabled and no greeting")
    func initialState() {
        let (controller, _) = makeShownController()
        #expect(!controller.greetButton.isEnabled)
        #expect(controller.greetingLabel.stringValue.isEmpty)
        #expect(controller.errorLabel.isHidden)
    }

    @Test("typing writes the view model; Observation tracking re-renders the view")
    func greetButton() {
        let (controller, window) = makeShownController()
        type("Ada", into: controller, window: window)
        #expect(controller.viewModel.name == "Ada")
        #expect(controller.greetButton.isEnabled)
        controller.greetButton.performClick(nil)
        window.layoutIfNeeded()
        #expect(controller.greetingLabel.stringValue == "Hello, Ada!")
    }

    @Test("Return greets only when the setting is on", arguments: [true, false])
    func returnKey(greetsOnSubmit: Bool) {
        let (controller, window) = makeShownController(greetsOnSubmit: greetsOnSubmit)
        type("Ada", into: controller, window: window)
        controller.nameField.sendAction(controller.nameField.action, to: controller.nameField.target)
        window.layoutIfNeeded()
        #expect(controller.greetingLabel.stringValue == (greetsOnSubmit ? "Hello, Ada!" : ""))
    }

    @Test("a blank name shows the validation error")
    func blankName() {
        let (controller, window) = makeShownController()
        type("  ", into: controller, window: window)
        controller.greet(nil)
        window.layoutIfNeeded()
        #expect(!controller.errorLabel.isHidden)
        #expect(controller.errorLabel.stringValue == "Enter a name first.")
    }

    @Test("the Greet menu item follows the view model; other items stay enabled")
    func menuValidation() {
        let (controller, window) = makeShownController()
        let greet = NSMenuItem(title: "Greet", action: #selector(MainViewController.greet(_:)), keyEquivalent: "")
        let other = NSMenuItem(title: "Other", action: #selector(NSText.copy(_:)), keyEquivalent: "")
        #expect(!controller.validateMenuItem(greet))
        type("Ada", into: controller, window: window)
        #expect(controller.validateMenuItem(greet))
        #expect(controller.validateMenuItem(other))
    }
}
```

### `App/Tests/AppShellTests.swift` (ui: appkit)

```swift
import AppKit
import Testing

@testable import <AppName>

@MainActor
@Suite("App shell")
struct AppShellTests {
    @Test("the menu bar has the standard menus in order")
    func menuTitles() {
        let menu = MainMenu.make(appName: "Demo")
        #expect(menu.items.map(\.title) == ["Demo", "File", "Edit", "Greeting", "Window", "Help"])
    }

    @Test("Settings… is ⌘, and Greet is ⇧⌘G")
    func keyEquivalents() throws {
        let menu = MainMenu.make(appName: "Demo")
        let settings = try #require(menu.item(withTitle: "Demo")?.submenu?.item(withTitle: "Settings…"))
        #expect(settings.keyEquivalent == ",")
        #expect(settings.action == #selector(AppDelegate.showSettings(_:)))
        let greet = try #require(menu.item(withTitle: "Greeting")?.submenu?.item(withTitle: "Greet"))
        #expect(greet.keyEquivalent == "g")
        #expect(greet.keyEquivalentModifierMask == [.command, .shift])
    }

    @Test("install registers the Window, Services and Help menus")
    func install() {
        let application = NSApplication.shared
        let previous = application.mainMenu
        defer { application.mainMenu = previous }
        MainMenu.install(in: application, appName: "Demo")
        #expect(application.windowsMenu?.title == "Window")
        #expect(application.servicesMenu?.title == "Services")
        #expect(application.helpMenu?.title == "Help")
    }

    @Test("the main window is titled, resizable and remembers its frame")
    func mainWindow() throws {
        let window = try #require(MainWindowController().window)
        #expect(window.title == "<app-name>")
        #expect(window.styleMask.contains(.resizable))
        #expect(window.frameAutosaveName == "MainWindow")
    }

    @Test("the settings checkbox reads and writes the preference")
    func settings() {
        let defaults = makeTestDefaults(greetsOnSubmit: false)
        let controller = SettingsWindowController(defaults: defaults)
        #expect(controller.greetsOnSubmitCheckbox.state == .off)
        controller.greetsOnSubmitCheckbox.performClick(nil)
        #expect(defaults.bool(forKey: SettingsKeys.greetsOnSubmit))
        defaults.set(false, forKey: SettingsKeys.greetsOnSubmit)
        controller.showWindow(nil)
        #expect(controller.greetsOnSubmitCheckbox.state == .off)
        controller.close()
    }

    @Test("the app quits after its last window closes and restores state securely")
    func appDelegate() {
        let delegate = AppDelegate()
        #expect(delegate.applicationShouldTerminateAfterLastWindowClosed(NSApplication.shared))
        #expect(delegate.applicationSupportsSecureRestorableState(NSApplication.shared))
    }
}
```

### `App/UITests/<AppName>UITests.swift`

Besides the behaviour tests (run once, left-to-right and light), the UI contract's screen tests:
`testCapturesTheMainWindow` relaunches the app per `.ui-quality.yml` direction × theme (right-to-left through
`-AppleLanguages (ar)` + `-AppleTextDirection YES -NSForceRightToLeftWritingDirection YES`, dark through
`-AppleInterfaceStyle Dark` — app-scoped launch arguments, so the desktop's own appearance never changes) and
records `.ui-screenshots/screen/<ScreenType>/default--<direction>--<theme>.png`; `testMainWindowAccessibility` runs
`performAccessibilityAudit()` at the configured level (`enforce` fails, `report` passes, both attach each issue as
`a11y~<ScreenType>~<rule>`; `off` skips). The sandboxed host can't write the repository, so the captures reach
`.ui-screenshots/` through the `.xcresult` (`iru-swift-test`'s `ui` run exports them).

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
        // -ApplePersistenceIgnoreState YES: start from a fresh window instead of restoring the last session.
        // --uitesting (LaunchOptions) resets the app's preferences. Keep it LAST: the argument domain reads
        // "-key value" pairs, so "--uitesting" placed first would swallow "-ApplePersistenceIgnoreState" as its
        // value, and a SwiftUI app then restores a session with no window open.
        app.launchArguments += ["-ApplePersistenceIgnoreState", "YES"]
        // The UI contract's matrix: language/direction and appearance for this launch only.
        app.launchArguments +=
            direction == .rtl
            ? [
                "-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA", "-AppleTextDirection", "YES",
                "-NSForceRightToLeftWritingDirection", "YES",
            ]
            : ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if theme == .dark {
            app.launchArguments += ["-AppleInterfaceStyle", "Dark"]
        }
        app.launchArguments.append("--uitesting")
        app.launch()
        return app
    }

    @MainActor
    func testGreetsTheTypedName() throws {
        let app = launchApp()
        let window = app.windows.firstMatch
        let nameField = window.textFields["nameField"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 10))
        XCTAssertFalse(window.buttons["greetButton"].isEnabled)

        nameField.click()
        nameField.typeText("Ada")
        window.buttons["greetButton"].click()

        let greeting = window.staticTexts["greetingLabel"]
        XCTAssertTrue(greeting.waitForExistence(timeout: 5))
        XCTAssertEqual(greeting.value as? String, "Hello, Ada!")
    }

    @MainActor
    func testGreetMenuCommand() throws {
        let app = launchApp()
        let nameField = app.windows.firstMatch.textFields["nameField"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 10))
        nameField.click()
        nameField.typeText("Grace")

        app.menuBars.menuBarItems["Greeting"].click()
        app.menuBars.menuItems["Greet"].click()

        let greeting = app.windows.firstMatch.staticTexts["greetingLabel"]
        XCTAssertTrue(greeting.waitForExistence(timeout: 5))
        XCTAssertEqual(greeting.value as? String, "Hello, Grace!")
    }

    /// Captures the main window in every `.ui-quality.yml` direction × theme (UI contract §6–§7).
    @MainActor
    func testCapturesTheMainWindow() throws {
        for (direction, theme) in UIQuality.matrix {
            let app = launchApp(direction: direction, theme: theme)
            let window = app.windows.firstMatch
            XCTAssertTrue(window.buttons["greetButton"].waitForExistence(timeout: 10))
            UIScreenshots.record(
                window.screenshot().pngRepresentation,
                at: UIScreenshots.path(.screen, "<ScreenType>", state: "default", direction: direction, theme: theme))
            app.terminate()
        }
    }

    /// Runs Xcode's accessibility audit at the `.ui-quality.yml` level (UI contract §8).
    @MainActor
    func testMainWindowAccessibility() throws {
        try XCTSkipIf(UIQuality.accessibility == .off || UIQuality.accessibility == .none, "accessibility: off or none in .ui-quality.yml")
        let app = launchApp()
        XCTAssertTrue(app.windows.firstMatch.buttons["greetButton"].waitForExistence(timeout: 10))
        try app.performAccessibilityAudit { issue in
            let note = XCTAttachment(string: issue.compactDescription)
            note.name = "a11y~<ScreenType>~\(Self.ruleID(issue.auditType))"
            note.lifetime = .keepAlways
            self.add(note)
            return UIQuality.accessibility == .report  // true = accept the issue, false = fail the test
        }
    }

    private static func ruleID(_ type: XCUIAccessibilityAuditType) -> String {
        let names: [(XCUIAccessibilityAuditType, String)] = [
            (.contrast, "contrast"), (.elementDetection, "element-detection"), (.hitRegion, "hit-region"),
            (.sufficientElementDescription, "sufficient-element-description"), (.action, "action"),
            (.parentChild, "parent-child"),
        ]
        return names.first { type.contains($0.0) }?.1 ?? "other"
    }
}
```
