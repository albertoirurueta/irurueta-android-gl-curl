# `Targets/iOS/Sources` templates — UIKit (`ui: uikit`, iOS/iPadOS target only)

A programmatic UIKit app — **no storyboard, no nib, no `UIMainStoryboardFile`**: `AppDelegate` (`@main`,
`UIApplicationDelegate`) does process-wide setup only; `SceneDelegate` (`UIWindowSceneDelegate`, named in the
Info.plist `UIApplicationSceneManifest` that `project.yml` writes) creates each `UIWindow` with a
`UINavigationController` root; `GreetingViewController` builds its views in code with Auto Layout
(`GreetingLayout`) and sets an accessibility identifier on every control. From the 27 SDK a UIKit app **must**
adopt the scene life cycle or it fails to launch (irurueta docs: apps/apple/app-structure-and-lifecycle.adoc,
"Mandatory with the 27 SDK"; apps/apple/uikit-and-appkit-interop.adoc). UIKit types are `@MainActor`, so every
file here runs on the main actor.

The view controller is the only file that differs between the two architectures:

- **`mvc`** (Recommended — Cocoa Touch's native pattern) — `GreetingViewController` owns the widgets, receives
  `UIAction`/delegate events and forwards each to `Core`'s `GreetingController`, which decides what to show and
  calls back through the `GreetingView` protocol the view controller conforms to.
- **`mvvm`** — `GreetingViewController` writes input into `Core`'s `@Observable` `GreetingViewModel` and reads its
  state in `viewWillLayoutSubviews()`. UIKit's automatic **Observation tracking** records every observable property
  read there and lays the view out again when one changes — on by default from iOS 26; on iOS 18–25 only when
  Info.plist sets `UIObservationTrackingEnabled` = YES (`project.yml` adds it for `observation-tracking-opt-in`).
  `viewWillLayoutSubviews()` is used rather than `updateProperties()` because `updateProperties()` exists only from
  iOS 26 (`API_AVAILABLE(ios(26.0))` in the 27.1 SDK's `UIViewController.h`); with a deployment target of 26+ either
  works (irurueta docs: apps/apple/uikit-and-appkit-interop.adoc, "View Controllers").

Both variants were rendered, built and tested (unit + XCUITest) on 2026-10-16 — `mvvm` on iOS 27.0 and, with
`min-deployment-target: 18.0` + the opt-in key, on iOS 18.5.


### `Targets/iOS/Sources/AppDelegate.swift` (ui: uikit)

```swift
import UIKit

/// Process-wide setup. Each window belongs to a ``SceneDelegate``: from the 27 SDK a UIKit app must adopt the
/// scene life cycle (the `UIApplicationSceneManifest` in Info.plist) or it fails to launch.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        LaunchOptions.apply()
        return true
    }
}
```

### `Targets/iOS/Sources/SceneDelegate.swift` (ui: uikit)

```swift
import UIKit

/// Builds the window of each scene in code (no storyboard): a navigation controller around the greeting screen.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else {
            return
        }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = Self.makeRootViewController()
        window.makeKeyAndVisible()
        self.window = window
    }

    /// The root of every window: the greeting screen inside a navigation controller.
    static func makeRootViewController() -> UINavigationController {
        UINavigationController(rootViewController: GreetingViewController())
    }
}
```

### `Targets/iOS/Sources/GreetingViewController.swift` (ui: uikit, architecture: mvc)

```swift
import Core
import UIKit

/// The greeting screen. Owns the widgets and forwards every event to ``GreetingController``, which calls back
/// through ``GreetingView``.
final class GreetingViewController: UIViewController, GreetingView, UITextFieldDelegate {
    let nameField = UITextField()
    let greetButton = UIButton(type: .system)
    let greetingLabel = UILabel()
    let errorLabel = UILabel()

    private let controller: GreetingController

    /// Creates the screen.
    ///
    /// - Parameter controller: The screen's decision logic; inject one built with a service double in tests.
    init(controller: GreetingController = GreetingController()) {
        self.controller = controller
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported: this view controller has no storyboard")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "<app-name>"
        GreetingLayout.install(
            nameField: nameField, greetButton: greetButton, greetingLabel: greetingLabel, errorLabel: errorLabel,
            in: view)
        nameField.delegate = self
        nameField.addAction(UIAction { [weak self] _ in self?.nameDidChange() }, for: .editingChanged)
        greetButton.addAction(UIAction { [weak self] _ in self?.greet() }, for: .primaryActionTriggered)
        controller.view = self
        controller.viewDidLoad()
    }

    // MARK: Events

    /// The Greet button.
    func greet() {
        controller.greetRequested()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if greetButton.isEnabled {
            controller.greetRequested()
        }
        textField.resignFirstResponder()
        return true
    }

    private func nameDidChange() {
        controller.nameDidChange(nameField.text ?? "")
    }

    // MARK: GreetingView

    func showGreeting(_ message: String) {
        greetingLabel.text = message
        errorLabel.text = nil
        errorLabel.isHidden = true
    }

    func showError(_ message: String) {
        greetingLabel.text = nil
        errorLabel.text = message
        errorLabel.isHidden = false
    }

    func setGreetEnabled(_ isEnabled: Bool) {
        greetButton.isEnabled = isEnabled
    }
}
```

### `Targets/iOS/Sources/GreetingViewController.swift` (ui: uikit, architecture: mvvm)

```swift
import Core
import UIKit

/// The greeting screen. Writes input into ``GreetingViewModel`` and renders its state in
/// `viewWillLayoutSubviews()`, which UIKit's Observation tracking re-runs whenever a view-model property read there
/// changes.
final class GreetingViewController: UIViewController, UITextFieldDelegate {
    let nameField = UITextField()
    let greetButton = UIButton(type: .system)
    let greetingLabel = UILabel()
    let errorLabel = UILabel()

    /// The screen's state and actions.
    let viewModel: GreetingViewModel

    /// Creates the screen.
    ///
    /// - Parameter viewModel: The screen's state; inject one built with a service double in tests.
    init(viewModel: GreetingViewModel = GreetingViewModel()) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported: this view controller has no storyboard")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "<app-name>"
        GreetingLayout.install(
            nameField: nameField, greetButton: greetButton, greetingLabel: greetingLabel, errorLabel: errorLabel,
            in: view)
        nameField.delegate = self
        nameField.addAction(UIAction { [weak self] _ in self?.nameDidChange() }, for: .editingChanged)
        greetButton.addAction(UIAction { [weak self] _ in self?.greet() }, for: .primaryActionTriggered)
    }

    /// Renders the view model. Every property read here becomes a tracked dependency: on by default from iOS 26,
    /// opted in with `UIObservationTrackingEnabled` in Info.plist on iOS 18. (`updateProperties()` is the iOS 26+
    /// alternative for content that doesn't affect layout.)
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        greetButton.isEnabled = viewModel.canGreet
        greetingLabel.text = viewModel.message
        errorLabel.text = viewModel.errorMessage
        errorLabel.isHidden = viewModel.errorMessage == nil
    }

    // MARK: Events

    /// The Greet button.
    func greet() {
        viewModel.greet()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if viewModel.canGreet {
            viewModel.greet()
        }
        textField.resignFirstResponder()
        return true
    }

    private func nameDidChange() {
        viewModel.name = nameField.text ?? ""
    }
}
```

### `Targets/iOS/Sources/GreetingLayout.swift` (ui: uikit)

```swift
import UIKit

/// Lays out the greeting screen in code with Auto Layout (no storyboard or nib), and tags each control with the
/// accessibility identifier the UI tests query.
@MainActor
enum GreetingLayout {
    /// Styles the controls and adds them to `view` in a vertical stack pinned to its safe area.
    static func install(
        nameField: UITextField,
        greetButton: UIButton,
        greetingLabel: UILabel,
        errorLabel: UILabel,
        in view: UIView
    ) {
        view.backgroundColor = .systemBackground
        nameField.placeholder = "Name"
        nameField.borderStyle = .roundedRect
        nameField.autocorrectionType = .no
        nameField.returnKeyType = .done
        nameField.accessibilityIdentifier = "nameField"
        var configuration = UIButton.Configuration.filled()
        configuration.title = "Greet"
        greetButton.configuration = configuration
        greetButton.accessibilityIdentifier = "greetButton"
        greetingLabel.font = .preferredFont(forTextStyle: .title2)
        greetingLabel.adjustsFontForContentSizeCategory = true
        greetingLabel.numberOfLines = 0
        greetingLabel.accessibilityIdentifier = "greetingLabel"
        errorLabel.font = .preferredFont(forTextStyle: .body)
        errorLabel.adjustsFontForContentSizeCategory = true
        errorLabel.textColor = .systemRed
        errorLabel.numberOfLines = 0
        errorLabel.isHidden = true
        errorLabel.accessibilityIdentifier = "errorLabel"

        let stack = UIStackView(arrangedSubviews: [nameField, greetButton, greetingLabel, errorLabel])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
        ])
    }
}
```


`Targets/iOS/Sources/LaunchOptions.swift` is the shared file from `swiftui-templates.md`. When a SwiftUI view is
later hosted in this UIKit app (`UIHostingController`) or a UIKit view in SwiftUI (`UIViewRepresentable`), a
representable's target-action `Coordinator` must be `@MainActor` under Swift 6. iOS 26-only APIs
(`updateProperties()`, `isInteractivelyResizing`) and the iPadOS 27 `UISceneClosureConfirmation` need
`if #available` guards whenever `min-deployment-target` is below their version.
