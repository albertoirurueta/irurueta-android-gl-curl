# `App/Sources` templates — `ui: appkit` (`architecture: mvc` or `mvvm`)

A programmatic AppKit app — no storyboard, no nib: `main.swift` installs the `AppDelegate`, the main menu is built
in code (`MainMenu`), the main window is an `NSWindowController` whose content is an `NSViewController` laid out
with Auto Layout in `loadView()`, and Settings… (⌘,) opens a second window controller. The view controller is the
only file that differs between the two architectures:

- **`mvc`** — Cocoa MVC with a UI-free controller core: `MainViewController` owns the widgets, receives
  target-action and delegate events, and forwards each one to `Core`'s `GreetingController`, which decides what to
  show and calls back through the `GreetingView` protocol the view controller conforms to.
- **`mvvm`** — `MainViewController` writes user input into `Core`'s `@Observable` `GreetingViewModel` and reads
  its state in `viewWillLayout()`; AppKit's Observation tracking (on by default since macOS 26, opt-in with the
  `NSObservationTrackingEnabled` Info.plist key on macOS 15) re-runs that method whenever a property it read
  changes — no KVO, no Combine, no manual refresh calls.

Placeholders and markers work as described in SKILL.md's "Reference files" section; both variants were rendered,
built and tested on 2026-10-16.

### `App/Sources/main.swift` (ui: appkit)

```swift
import AppKit

// Programmatic AppKit entry point: with no main storyboard or nib, nothing would create the delegate, so it is
// installed here before the run loop starts. NSApplication holds its delegate weakly; this global keeps it alive.
let appDelegate = AppDelegate()
NSApplication.shared.delegate = appDelegate
NSApplication.shared.run()
```

### `App/Sources/AppDelegate.swift` (ui: appkit)

```swift
import AppKit

/// Builds the menu bar and the main window, opens Settings, and quits after the last window closes.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var mainWindowController: MainWindowController?
    private lazy var settingsWindowController = SettingsWindowController()

    func applicationWillFinishLaunching(_ notification: Notification) {
        LaunchOptions.apply()
        UserDefaults.standard.register(defaults: [SettingsKeys.greetsOnSubmit: true])
        MainMenu.install(in: NSApplication.shared, appName: "<app-name>")
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let controller = MainWindowController()
        controller.showWindow(nil)
        mainWindowController = controller
        NSApplication.shared.activate()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
        true
    }

    /// Shows the Settings window (App menu › Settings…, ⌘,). Reached through the responder chain.
    @objc func showSettings(_ sender: Any?) {
        settingsWindowController.showWindow(sender)
    }
}
```

### `App/Sources/MainMenu.swift` (ui: appkit)

```swift
import AppKit

/// The menu bar, built in code. Items name an action and no target, so AppKit sends each action up the responder
/// chain of the key window (text fields, the view controller, the window, the app delegate) and asks the receiver
/// to validate it.
@MainActor
enum MainMenu {
    /// Builds the menu bar and registers its Services, Window and Help menus with the application.
    static func install(in application: NSApplication, appName: String) {
        let mainMenu = make(appName: appName)
        application.mainMenu = mainMenu
        application.servicesMenu = mainMenu.item(withTitle: appName)?.submenu?.item(withTitle: "Services")?.submenu
        application.windowsMenu = mainMenu.item(withTitle: "Window")?.submenu
        application.helpMenu = mainMenu.item(withTitle: "Help")?.submenu
    }

    /// Returns the complete menu bar: the app, File, Edit, Greeting, Window and Help menus.
    static func make(appName: String) -> NSMenu {
        let mainMenu = NSMenu(title: "Main Menu")
        let menus = [
            appMenu(appName: appName), fileMenu(), editMenu(), greetingMenu(), windowMenu(), helpMenu(appName: appName),
        ]
        for menu in menus {
            let item = NSMenuItem(title: menu.title, action: nil, keyEquivalent: "")
            item.submenu = menu
            mainMenu.addItem(item)
        }
        return mainMenu
    }

    private static func appMenu(appName: String) -> NSMenu {
        let menu = NSMenu(title: appName)
        menu.addItem(
            withTitle: "About \(appName)",
            action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)),
            keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Settings…", action: #selector(AppDelegate.showSettings(_:)), keyEquivalent: ",")
        menu.addItem(.separator())
        let services = menu.addItem(withTitle: "Services", action: nil, keyEquivalent: "")
        services.submenu = NSMenu(title: "Services")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Hide \(appName)", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let hideOthers = menu.addItem(
            withTitle: "Hide Others",
            action: #selector(NSApplication.hideOtherApplications(_:)),
            keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        menu.addItem(
            withTitle: "Show All",
            action: #selector(NSApplication.unhideAllApplications(_:)),
            keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit \(appName)", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        return menu
    }

    private static func fileMenu() -> NSMenu {
        let menu = NSMenu(title: "File")
        menu.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        return menu
    }

    private static func editMenu() -> NSMenu {
        let menu = NSMenu(title: "Edit")
        menu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = menu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        menu.addItem(.separator())
        menu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        menu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        menu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        menu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        return menu
    }

    private static func greetingMenu() -> NSMenu {
        let menu = NSMenu(title: "Greeting")
        let greet = menu.addItem(
            withTitle: "Greet", action: #selector(MainViewController.greet(_:)), keyEquivalent: "g")
        greet.keyEquivalentModifierMask = [.command, .shift]
        return menu
    }

    private static func windowMenu() -> NSMenu {
        let menu = NSMenu(title: "Window")
        menu.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        menu.addItem(withTitle: "Zoom", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(
            withTitle: "Bring All to Front",
            action: #selector(NSApplication.arrangeInFront(_:)),
            keyEquivalent: "")
        return menu
    }

    private static func helpMenu(appName: String) -> NSMenu {
        let menu = NSMenu(title: "Help")
        menu.addItem(withTitle: "\(appName) Help", action: #selector(NSApplication.showHelp(_:)), keyEquivalent: "?")
        return menu
    }
}
```

### `App/Sources/MainWindowController.swift` (ui: appkit)

```swift
import AppKit

/// Owns the main window: its size, style and remembered frame.
final class MainWindowController: NSWindowController {
    /// Creates the main window around a ``MainViewController``.
    convenience init() {
        let window = NSWindow(contentViewController: MainViewController())
        window.title = "<app-name>"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 480, height: 280))
        window.contentMinSize = NSSize(width: 360, height: 200)
        // Reopens at the size and position the person left it in.
        window.setFrameAutosaveName("MainWindow")
        self.init(window: window)
    }
}
```

### `App/Sources/MainViewController.swift` (ui: appkit, architecture: mvc)

```swift
import AppKit
import Core

/// The greeting screen. Owns the widgets and forwards every event to ``GreetingController``, which calls back
/// through ``GreetingView``.
final class MainViewController: NSViewController, GreetingView, NSTextFieldDelegate, NSMenuItemValidation {
    let nameField = NSTextField()
    let greetButton = NSButton(title: "Greet", target: nil, action: nil)
    let greetingLabel = NSTextField(labelWithString: "")
    let errorLabel = NSTextField(labelWithString: "")

    private let controller: GreetingController
    private let defaults: UserDefaults

    /// Creates the screen.
    ///
    /// - Parameters:
    ///   - controller: The screen's decision logic; inject one built with a service double in tests.
    ///   - defaults: Where the Settings window stores its preferences.
    init(controller: GreetingController = GreetingController(), defaults: UserDefaults = .standard) {
        self.controller = controller
        self.defaults = defaults
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported: this view controller has no nib")
    }

    override func loadView() {
        view = GreetingLayout.makeView(
            nameField: nameField, greetButton: greetButton, greetingLabel: greetingLabel, errorLabel: errorLabel)
        nameField.delegate = self
        nameField.target = self
        nameField.action = #selector(nameFieldSubmitted(_:))
        greetButton.target = self
        greetButton.action = #selector(greet(_:))
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        controller.view = self
        controller.viewDidLoad()
    }

    // MARK: Events

    func controlTextDidChange(_ notification: Notification) {
        controller.nameDidChange(nameField.stringValue)
    }

    /// The Greet button and the Greeting › Greet menu item (⇧⌘G).
    @objc func greet(_ sender: Any?) {
        controller.greetRequested()
    }

    @objc private func nameFieldSubmitted(_ sender: NSTextField) {
        if defaults.bool(forKey: SettingsKeys.greetsOnSubmit) && greetButton.isEnabled {
            controller.greetRequested()
        }
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        menuItem.action == #selector(greet(_:)) ? greetButton.isEnabled : true
    }

    // MARK: GreetingView

    func showGreeting(_ message: String) {
        greetingLabel.stringValue = message
        errorLabel.stringValue = ""
        errorLabel.isHidden = true
    }

    func showError(_ message: String) {
        greetingLabel.stringValue = ""
        errorLabel.stringValue = message
        errorLabel.isHidden = false
    }

    func setGreetEnabled(_ isEnabled: Bool) {
        greetButton.isEnabled = isEnabled
    }
}
```

### `App/Sources/MainViewController.swift` (ui: appkit, architecture: mvvm)

```swift
import AppKit
import Core

/// The greeting screen. Writes input into ``GreetingViewModel`` and renders its state in `viewWillLayout()`,
/// which AppKit's Observation tracking re-runs whenever a view-model property read there changes.
final class MainViewController: NSViewController, NSTextFieldDelegate, NSMenuItemValidation {
    let nameField = NSTextField()
    let greetButton = NSButton(title: "Greet", target: nil, action: nil)
    let greetingLabel = NSTextField(labelWithString: "")
    let errorLabel = NSTextField(labelWithString: "")

    /// The screen's state and actions.
    let viewModel: GreetingViewModel
    private let defaults: UserDefaults

    /// Creates the screen.
    ///
    /// - Parameters:
    ///   - viewModel: The screen's state; inject one built with a service double in tests.
    ///   - defaults: Where the Settings window stores its preferences.
    init(viewModel: GreetingViewModel = GreetingViewModel(), defaults: UserDefaults = .standard) {
        self.viewModel = viewModel
        self.defaults = defaults
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported: this view controller has no nib")
    }

    override func loadView() {
        view = GreetingLayout.makeView(
            nameField: nameField, greetButton: greetButton, greetingLabel: greetingLabel, errorLabel: errorLabel)
        nameField.delegate = self
        nameField.target = self
        nameField.action = #selector(nameFieldSubmitted(_:))
        greetButton.target = self
        greetButton.action = #selector(greet(_:))
    }

    /// Renders the view model. Every property read here becomes a tracked dependency (macOS 26+ by default).
    override func viewWillLayout() {
        super.viewWillLayout()
        greetButton.isEnabled = viewModel.canGreet
        greetingLabel.stringValue = viewModel.message
        errorLabel.stringValue = viewModel.errorMessage ?? ""
        errorLabel.isHidden = viewModel.errorMessage == nil
    }

    // MARK: Events

    func controlTextDidChange(_ notification: Notification) {
        viewModel.name = nameField.stringValue
    }

    /// The Greet button and the Greeting › Greet menu item (⇧⌘G).
    @objc func greet(_ sender: Any?) {
        viewModel.greet()
    }

    @objc private func nameFieldSubmitted(_ sender: NSTextField) {
        if defaults.bool(forKey: SettingsKeys.greetsOnSubmit) && viewModel.canGreet {
            viewModel.greet()
        }
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        menuItem.action == #selector(greet(_:)) ? viewModel.canGreet : true
    }
}
```

### `App/Sources/GreetingLayout.swift` (ui: appkit)

```swift
import AppKit

/// Lays out the greeting screen in code with Auto Layout (no nib), and tags each control with the accessibility
/// identifier the UI tests query.
@MainActor
enum GreetingLayout {
    /// Returns the screen's root view containing the given controls.
    static func makeView(
        nameField: NSTextField, greetButton: NSButton, greetingLabel: NSTextField, errorLabel: NSTextField
    ) -> NSView {
        nameField.placeholderString = "Name"
        nameField.setAccessibilityIdentifier("nameField")
        greetButton.bezelStyle = .push
        greetButton.setAccessibilityIdentifier("greetButton")
        greetingLabel.font = .preferredFont(forTextStyle: .title2)
        greetingLabel.setAccessibilityIdentifier("greetingLabel")
        errorLabel.textColor = .systemRed
        errorLabel.isHidden = true
        errorLabel.setAccessibilityIdentifier("errorLabel")

        let stack = NSStackView(views: [nameField, greetButton, greetingLabel, errorLabel])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false

        let root = NSView()
        root.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: root.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: root.bottomAnchor, constant: -20),
            nameField.widthAnchor.constraint(equalTo: stack.widthAnchor),
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 360),
            root.heightAnchor.constraint(greaterThanOrEqualToConstant: 200),
        ])
        return root
    }
}
```

### `App/Sources/SettingsWindowController.swift` (ui: appkit)

```swift
import AppKit

/// The keys of the app's preferences in the standard user defaults.
enum SettingsKeys {
    /// Whether pressing Return in the name field greets, as the Greet button does.
    static let greetsOnSubmit = "greetsOnSubmit"

    /// Every key above; `LaunchOptions` removes them for a UI-test launch.
    static let all = [greetsOnSubmit]
}

/// The Settings window (⌘,): one checkbox stored in the user defaults.
final class SettingsWindowController: NSWindowController {
    let greetsOnSubmitCheckbox = NSButton(checkboxWithTitle: "Greet when pressing Return", target: nil, action: nil)
    private let defaults: UserDefaults

    /// Creates the Settings window.
    ///
    /// - Parameter defaults: Where the preferences are stored.
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 120),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: true)
        window.title = "Settings"
        super.init(window: window)

        greetsOnSubmitCheckbox.target = self
        greetsOnSubmitCheckbox.action = #selector(toggleGreetsOnSubmit(_:))
        greetsOnSubmitCheckbox.setAccessibilityIdentifier("greetsOnSubmitToggle")
        greetsOnSubmitCheckbox.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView()
        content.addSubview(greetsOnSubmitCheckbox)
        NSLayoutConstraint.activate([
            greetsOnSubmitCheckbox.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
            greetsOnSubmitCheckbox.topAnchor.constraint(equalTo: content.topAnchor, constant: 20),
        ])
        window.contentView = content
        window.center()
        refresh()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported: this window controller has no nib")
    }

    override func showWindow(_ sender: Any?) {
        refresh()
        super.showWindow(sender)
    }

    @objc func toggleGreetsOnSubmit(_ sender: NSButton) {
        defaults.set(sender.state == .on, forKey: SettingsKeys.greetsOnSubmit)
    }

    private func refresh() {
        greetsOnSubmitCheckbox.state = defaults.bool(forKey: SettingsKeys.greetsOnSubmit) ? .on : .off
    }
}
```
