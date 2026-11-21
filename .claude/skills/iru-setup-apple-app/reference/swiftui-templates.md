# `Targets/<Platform>/Sources` templates — SwiftUI (`ui: swiftui`, every non-iOS platform)

Written for every selected platform whose target is SwiftUI: iOS/iPadOS with `ui: swiftui`, and **always** for
`macOS`, `watchOS` and `visionOS` (SKILL.md Step 2 explains why those stay SwiftUI). One copy per platform folder;
the files are identical across platforms — platform differences are `#if os(...)` inside them (irurueta docs:
apps/apple/multiplatform-projects.adoc, "Conditional Compilation"). The screen is MVVM: the view owns a per-screen
`@State` `GreetingViewModel` from `Packages/Core` (irurueta docs: apps/apple/app-architecture.adoc, "MV with
Observation vs MVVM"). Accessibility identifiers `nameField`, `greetButton`, `greetingLabel`, `errorLabel` are the
same as the UIKit screen's, so the UI test is shared.

Rendered, built and tested on 2026-10-16 for iOS (iOS 27.0 simulator), watchOS (watchOS 27.0 simulator), macOS
(unit tests) and visionOS (build only — see SKILL.md Known quirks).


### `Targets/<Platform>/Sources/<AppName>App.swift` (ui: swiftui or a non-iOS platform)

```swift
import SwiftUI

/// The app's entry point: one window group showing the greeting screen.
@main
struct <AppName>App: App {
    init() {
        LaunchOptions.apply()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```


`App` is `@MainActor`, so `init()` may call the main-actor `LaunchOptions.apply()`. A file that also imports
RealityKit (a future visionOS volume/immersive space) must spell the body `some SwiftUI.Scene` — `Scene` is
ambiguous there.


### `Targets/<Platform>/Sources/ContentView.swift` (ui: swiftui or a non-iOS platform)

```swift
import Core
import SwiftUI

/// The greeting screen: a name field, a Greet button and the resulting greeting.
struct ContentView: View {
    @State private var viewModel = GreetingViewModel()

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Name", text: $viewModel.name)
                    #if !os(watchOS)
                        .textFieldStyle(.roundedBorder)
                    #endif
                    .accessibilityIdentifier("nameField")
                    .onSubmit {
                        if viewModel.canGreet {
                            viewModel.greet()
                        }
                    }
                Button("Greet") {
                    viewModel.greet()
                }
                .disabled(!viewModel.canGreet)
                .accessibilityIdentifier("greetButton")
                Text(viewModel.message)
                    .font(.title2)
                    .accessibilityIdentifier("greetingLabel")
                if let errorMessage = viewModel.errorMessage {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("errorLabel")
                }
                Spacer(minLength: 0)
            }
            .padding()
            .navigationTitle("<app-name>")
        }
    }
}

#Preview {
    ContentView()
}
```


`.textFieldStyle(.roundedBorder)` doesn't exist on watchOS, hence the postfix `#if`. On watchOS the text field
opens the system text-input sheet.


### `Targets/<Platform>/Sources/LaunchOptions.swift` (every platform, both `ui` values)

```swift
import Foundation

#if os(iOS) || os(visionOS)
    import UIKit
#endif

/// Launch arguments the app understands. Shared by every platform and both `ui` values.
enum LaunchOptions {
    /// Passed by the UI tests: start from a known state, with animations off where the platform allows it.
    static let uiTesting = "--uitesting"

    /// Applies ``uiTesting`` when `arguments` contains it.
    ///
    /// - Parameter arguments: The process arguments, `CommandLine.arguments` in the app.
    /// - Returns: Whether the app was launched for UI testing.
    @MainActor
    @discardableResult
    static func apply(arguments: [String] = CommandLine.arguments) -> Bool {
        guard arguments.contains(uiTesting) else {
            return false
        }
        #if os(iOS) || os(visionOS)
            UIView.setAnimationsEnabled(false)
        #endif
        return true
    }
}
```


The UI tests pass `--uitesting` (last argument). On iOS/iPadOS/visionOS it turns UIKit animations off; on macOS and
watchOS it only reports the flag. Add app-owned state resets here (e.g. clearing the app's own `UserDefaults`
keys) as the app grows.
