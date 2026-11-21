# `Packages/Core` templates

The UI-framework-free local Swift package (library product `Core`; imports **Foundation and Observation only** —
never SwiftUI, UIKit, AppKit or WatchKit) that holds every model, service and view-model/controller of the app, so
the coverage bar (100% lines) is measured headless with `swift test` on the macOS host. Its folder layout is the
**architecture contract** the Swift coding skill (`iru-swift-code-one-task`, `reference/architecture.md`) detects —
the same contract `iru-setup-macos-app` uses:

| `architecture` | Folders under `Packages/Core/Sources/Core/` |
|---|---|
| `mvvm` | `Models/`, `Services/`, `ViewModels/` — one `@MainActor @Observable` view model per screen, used unchanged by a SwiftUI view (`@State`) on every platform and by a UIKit view controller (Observation tracking in `viewWillLayoutSubviews()`). |
| `mvc` | `Models/`, `Services/`, `Controllers/` — one UI-free `@MainActor` controller per screen holding its decisions, plus the `<Screen>View` passive-view protocol next to it. The UIKit `UIViewController` owns the widgets and lifecycle, conforms to the protocol and forwards every event to the controller. |

`swiftui-screen` = `architecture: mvvm` **or** any SwiftUI target selected (iOS with `ui: swiftui`, macOS, watchOS,
visionOS): a UIKit-MVC app with a watchOS/visionOS/macOS target gets both `Controllers/` and `ViewModels/` (verified:
12 Core tests, 100% lines).

Placeholders and `>>>`/`<<<` markers work as described in SKILL.md's "Reference files" section. Every file below was
rendered and tested (`swift test --enable-code-coverage` + `llvm-cov`: **100.00%** lines, regions and functions of
`Core` for both architectures) on 2026-10-16.


### `Packages/Core/Package.swift`

```swift
// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [
        // >>> platform: iOS
        .iOS(.v<ios-major>),
        // <<< platform: iOS
        // Always declared, even without a macOS app target: `swift test` (the coverage bar) runs on the macOS host,
        // and @Observable needs macOS 14+.
        .macOS(.v<macos-major>),
        // >>> platform: watchOS
        .watchOS(.v<watchos-major>),
        // <<< platform: watchOS
        // >>> platform: visionOS
        .visionOS(.v<visionos-major>),
        // <<< platform: visionOS
    ],
    products: [
        .library(name: "Core", targets: ["Core"])
    ],
    dependencies: [
        // Only for `swift package generate-documentation --target Core` (iru-swift-docc, the Pages job);
        // a command plugin, never linked into the app.
        .package(url: "https://github.com/swiftlang/swift-docc-plugin", from: "<swift-docc-plugin-version>")
    ],
    targets: [
        .target(
            name: "Core",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "CoreTests",
            dependencies: ["Core"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
```


`<ios-major>`/`<macos-major>`/`<watchos-major>`/`<visionos-major>` come from SKILL.md Step 5's per-platform
deployment-target table (all equal from 26 on: `26.0` → `.v26` everywhere; `18.0` → `.iOS(.v18)`, `.macOS(.v15)`,
`.watchOS(.v11)`, `.visionOS(.v2)`). Each must be a case `PackageDescription` declares for this tools version
(`.v26`/`.v27` need `swift-tools-version: 6.2`) — a missing case fails at manifest-parse time. The first run
resolves `swift-docc-plugin` from GitHub (network).


### `Packages/Core/Sources/Core/Core.docc/Core.md`

```markdown
# ``Core``

The models, services and presentation logic of <app-name>, free of UIKit, AppKit and SwiftUI.

## Overview

The app target only builds windows and views; every decision it shows lives here, so it is unit-tested headless
with `swift test`.

## Topics

### Models

- ``Greeting``

### Services

- ``GreetingProviding``
- ``GreetingService``
- ``GreetingError``

<!-- >>> swiftui-screen -->
### View Models

- ``GreetingViewModel``
<!-- <<< swiftui-screen -->
<!-- >>> architecture: mvc -->
### Controllers

- ``GreetingController``
- ``GreetingView``
<!-- <<< architecture: mvc -->
```

### `Packages/Core/Sources/Core/Models/Greeting.swift`

```swift
/// A greeting addressed to one person.
public struct Greeting: Equatable, Sendable {
    /// The name of the person being greeted, without surrounding whitespace.
    public let name: String

    /// Creates a greeting for an already validated name.
    ///
    /// - Parameter name: The person's name, trimmed and non-empty.
    public init(name: String) {
        self.name = name
    }

    /// The text shown to the person, for example `Hello, Ada!`.
    public var message: String {
        "Hello, \(name)!"
    }
}
```

### `Packages/Core/Sources/Core/Services/GreetingService.swift`

```swift
import Foundation

/// The errors a ``GreetingProviding`` service reports.
public enum GreetingError: Error, Equatable, Sendable {
    /// The name was empty or contained only whitespace.
    case emptyName

    /// A sentence describing the error, suitable for showing next to the name field.
    public var message: String {
        switch self {
        case .emptyName:
            "Enter a name first."
        }
    }
}

/// Creates greetings. Screens depend on this protocol, so tests can inject a double.
public protocol GreetingProviding: Sendable {
    /// Returns the greeting for a name typed by the person.
    ///
    /// - Parameter name: The raw text of the name field.
    /// - Returns: A greeting for the trimmed name.
    /// - Throws: ``GreetingError/emptyName`` when `name` is blank.
    func greeting(for name: String) throws(GreetingError) -> Greeting
}

/// The default ``GreetingProviding`` implementation.
public struct GreetingService: GreetingProviding {
    /// Creates the service.
    public init() {}

    /// Returns the greeting for a name typed by the person.
    ///
    /// - Parameter name: The raw text of the name field.
    /// - Returns: A greeting for `name` without its leading and trailing whitespace.
    /// - Throws: ``GreetingError/emptyName`` when `name` is blank.
    public func greeting(for name: String) throws(GreetingError) -> Greeting {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw .emptyName
        }
        return Greeting(name: trimmed)
    }
}
```

### `Packages/Core/Sources/Core/ViewModels/GreetingViewModel.swift` (swiftui-screen)

```swift
import Foundation
import Observation

/// The state and actions of the greeting screen.
///
/// A SwiftUI view owns it with `@State`; a UIKit view controller reads it inside `viewWillLayoutSubviews()`, where
/// Observation tracking re-runs the method whenever a property it read changes.
@MainActor
@Observable
public final class GreetingViewModel {
    /// The text of the name field.
    public var name = ""

    /// The last greeting, or an empty string before the first one or after an error.
    public private(set) var message = ""

    /// The validation message to show, or `nil` when the last action succeeded.
    public private(set) var errorMessage: String?

    @ObservationIgnored private let service: any GreetingProviding

    /// Creates the view model.
    ///
    /// - Parameter service: The service that builds greetings; inject a double in tests.
    public init(service: any GreetingProviding = GreetingService()) {
        self.service = service
    }

    /// Whether the Greet action is available: `true` once the name has a non-whitespace character.
    public var canGreet: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Greets the current name, or sets ``errorMessage`` when the service rejects it.
    public func greet() {
        do {
            message = try service.greeting(for: name).message
            errorMessage = nil
        } catch {
            message = ""
            errorMessage = error.message
        }
    }
}
```

### `Packages/Core/Sources/Core/Controllers/GreetingView.swift` (architecture: mvc)

```swift
/// The view a ``GreetingController`` drives.
///
/// The UIKit view controller of the greeting screen conforms to it; tests use a recording double.
@MainActor
public protocol GreetingView: AnyObject {
    /// Shows a greeting and clears any error.
    ///
    /// - Parameter message: The greeting text, or an empty string to clear it.
    func showGreeting(_ message: String)

    /// Shows a validation error and clears the greeting.
    ///
    /// - Parameter message: The error text.
    func showError(_ message: String)

    /// Enables or disables the Greet action.
    ///
    /// - Parameter isEnabled: Whether the action can run.
    func setGreetEnabled(_ isEnabled: Bool)
}
```

### `Packages/Core/Sources/Core/Controllers/GreetingController.swift` (architecture: mvc)

```swift
import Foundation

/// The decisions of the greeting screen: what to show for each event the view reports.
///
/// The view controller owns the widgets and forwards target-action events here; this type never imports UIKit,
/// so it is unit-tested headless.
@MainActor
public final class GreetingController {
    /// The view this controller updates. Held weakly: the view controller owns the controller.
    public weak var view: (any GreetingView)?

    private let service: any GreetingProviding
    private var name = ""

    /// Creates the controller.
    ///
    /// - Parameter service: The service that builds greetings; inject a double in tests.
    public init(service: any GreetingProviding = GreetingService()) {
        self.service = service
    }

    /// Puts the view into its initial state. Call it once the view is loaded.
    public func viewDidLoad() {
        view?.showGreeting("")
        view?.setGreetEnabled(false)
    }

    /// Records the new name and enables the Greet action only for a non-blank name.
    ///
    /// - Parameter name: The current text of the name field.
    public func nameDidChange(_ name: String) {
        self.name = name
        view?.setGreetEnabled(!name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    /// Greets the current name, or shows the service's validation error.
    public func greetRequested() {
        do {
            view?.showGreeting(try service.greeting(for: name).message)
        } catch {
            view?.showError(error.message)
        }
    }
}
```

### `Packages/Core/Tests/CoreTests/GreetingServiceTests.swift`

```swift
import Testing

@testable import Core

@Suite("GreetingService")
struct GreetingServiceTests {
    private let service = GreetingService()

    @Test("greets a trimmed name")
    func greetsTrimmedName() throws {
        let greeting = try service.greeting(for: "  Ada \n")
        #expect(greeting == Greeting(name: "Ada"))
        #expect(greeting.message == "Hello, Ada!")
    }

    @Test("rejects a blank name", arguments: ["", "   ", "\n\t"])
    func rejectsBlankName(_ name: String) {
        #expect(throws: GreetingError.emptyName) {
            try service.greeting(for: name)
        }
    }

    @Test("describes every error")
    func describesErrors() {
        #expect(GreetingError.emptyName.message == "Enter a name first.")
    }
}
```

### `Packages/Core/Tests/CoreTests/StubGreetingService.swift`

```swift
@testable import Core

/// A ``GreetingProviding`` double that returns or throws a fixed result.
struct StubGreetingService: GreetingProviding {
    let result: Result<Greeting, GreetingError>

    func greeting(for name: String) throws(GreetingError) -> Greeting {
        try result.get()
    }
}
```

### `Packages/Core/Tests/CoreTests/GreetingViewModelTests.swift` (swiftui-screen)

```swift
import Testing

@testable import Core

@MainActor
@Suite("GreetingViewModel")
struct GreetingViewModelTests {
    @Test("starts empty and cannot greet")
    func initialState() {
        let viewModel = GreetingViewModel()
        #expect(viewModel.name.isEmpty)
        #expect(viewModel.message.isEmpty)
        #expect(viewModel.errorMessage == nil)
        #expect(!viewModel.canGreet)
    }

    @Test("can greet once the name has a non-blank character", arguments: [("Ada", true), ("  ", false)])
    func canGreet(name: String, expected: Bool) {
        let viewModel = GreetingViewModel()
        viewModel.name = name
        #expect(viewModel.canGreet == expected)
    }

    @Test("greet shows the service's greeting and clears the error")
    func greetSucceeds() {
        let viewModel = GreetingViewModel(service: StubGreetingService(result: .success(Greeting(name: "Ada"))))
        viewModel.greet()
        #expect(viewModel.message == "Hello, Ada!")
        #expect(viewModel.errorMessage == nil)
    }

    @Test("greet shows the error and clears the greeting")
    func greetFails() {
        let viewModel = GreetingViewModel()
        viewModel.name = "Ada"
        viewModel.greet()
        viewModel.name = " "
        viewModel.greet()
        #expect(viewModel.message.isEmpty)
        #expect(viewModel.errorMessage == "Enter a name first.")
    }
}
```

### `Packages/Core/Tests/CoreTests/GreetingControllerTests.swift` (architecture: mvc)

```swift
import Testing

@testable import Core

/// A ``GreetingView`` double that records what the controller asked it to show.
@MainActor
final class RecordingGreetingView: GreetingView {
    private(set) var greetings: [String] = []
    private(set) var errors: [String] = []
    private(set) var greetEnabled: [Bool] = []

    func showGreeting(_ message: String) {
        greetings.append(message)
    }

    func showError(_ message: String) {
        errors.append(message)
    }

    func setGreetEnabled(_ isEnabled: Bool) {
        greetEnabled.append(isEnabled)
    }
}

@MainActor
@Suite("GreetingController")
struct GreetingControllerTests {
    @Test("viewDidLoad clears the greeting and disables Greet")
    func initialState() {
        let view = RecordingGreetingView()
        let controller = GreetingController()
        controller.view = view
        controller.viewDidLoad()
        #expect(view.greetings == [""])
        #expect(view.greetEnabled == [false])
    }

    @Test("Greet is enabled only for a non-blank name")
    func enablesGreet() {
        let view = RecordingGreetingView()
        let controller = GreetingController()
        controller.view = view
        controller.nameDidChange("Ada")
        controller.nameDidChange("   ")
        #expect(view.greetEnabled == [true, false])
    }

    @Test("greetRequested shows the service's greeting")
    func greetSucceeds() {
        let view = RecordingGreetingView()
        let controller = GreetingController(service: StubGreetingService(result: .success(Greeting(name: "Ada"))))
        controller.view = view
        controller.greetRequested()
        #expect(view.greetings == ["Hello, Ada!"])
        #expect(view.errors.isEmpty)
    }

    @Test("greetRequested shows the validation error for a blank name")
    func greetFails() {
        let view = RecordingGreetingView()
        let controller = GreetingController()
        controller.view = view
        controller.nameDidChange(" ")
        controller.greetRequested()
        #expect(view.errors == ["Enter a name first."])
        #expect(view.greetings.isEmpty)
    }

    @Test("does nothing without a view")
    func noView() {
        let controller = GreetingController()
        controller.viewDidLoad()
        controller.nameDidChange("Ada")
        controller.greetRequested()
        #expect(controller.view == nil)
    }
}
```


**Legacy placeholder.** Scaffolds made by earlier versions of this skill have a flat
`Packages/Core/Sources/Core/Greeter.swift` + `Tests/CoreTests/GreeterTests.swift` pair. Never delete them: an Update
run adds the folders above alongside (both compile together), and the report suggests removing `Greeter` once the
app sources no longer use it.
