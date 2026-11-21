# `App/Sources` templates — `ui: swiftui` (`architecture: mvvm`)

A native SwiftUI Mac app: a `WindowGroup` main window whose view owns a per-window `@Observable`
`GreetingViewModel` (from `Packages/Core`), a `Settings` scene (⌘, — tabbed, as Mac settings windows are), and a
`Commands` type adding a **Greeting** menu that acts on the frontmost window through a focused value. A
`MenuBarExtra` is not written by default (see the commented block in `<AppName>App.swift`).

Placeholders work as described in SKILL.md's "Reference files" section; rendered, built and tested on 2026-10-16.

### `App/Sources/<AppName>App.swift` (ui: swiftui)

```swift
import SwiftUI

/// The app's entry point: its scenes and menu bar commands.
@main
struct <AppName>App: App {
    init() {
        LaunchOptions.apply()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .defaultSize(width: 480, height: 280)
        .commands {
            GreetingCommands()
        }

        Settings {
            SettingsView()
        }

        // A menu bar extra, off by default. Uncomment to add one; set INFOPLIST_KEY_LSUIElement: YES in
        // project.yml only for a menu-bar-only utility with no Dock icon.
        // MenuBarExtra("<app-name>", systemImage: "hand.wave") {
        //     SettingsLink()
        //     Button("Quit <app-name>") { NSApplication.shared.terminate(nil) }
        //         .keyboardShortcut("q")
        // }
    }
}
```

### `App/Sources/ContentView.swift` (ui: swiftui)

```swift
import Core
import SwiftUI

/// The main window: a name field, a Greet button and the resulting greeting.
struct ContentView: View {
    @State private var viewModel = GreetingViewModel()
    @AppStorage(SettingsKeys.greetsOnSubmit) private var greetsOnSubmit = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $viewModel.name)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("nameField")
                .onSubmit {
                    if greetsOnSubmit && viewModel.canGreet {
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
        .padding(20)
        .frame(minWidth: 360, minHeight: 200)
        .navigationTitle("<app-name>")
        .focusedSceneValue(\.greetingViewModel, viewModel)
    }
}

#Preview {
    ContentView()
}
```

### `App/Sources/GreetingCommands.swift` (ui: swiftui)

```swift
import Core
import SwiftUI

extension FocusedValues {
    /// The greeting view model of the frontmost main window, read by ``GreetingCommands``.
    @Entry var greetingViewModel: GreetingViewModel?
}

/// The Greeting menu. Disabled, not hidden, when the frontmost window can't greet.
struct GreetingCommands: Commands {
    @FocusedValue(\.greetingViewModel) private var viewModel

    var body: some Commands {
        CommandMenu("Greeting") {
            Button("Greet") {
                viewModel?.greet()
            }
            .keyboardShortcut("g", modifiers: [.command, .shift])
            .disabled(viewModel?.canGreet != true)
        }
    }
}
```

### `App/Sources/SettingsView.swift` (ui: swiftui)

```swift
import SwiftUI

/// The keys of the app's preferences in the standard user defaults.
enum SettingsKeys {
    /// Whether pressing Return in the name field greets, as the Greet button does.
    static let greetsOnSubmit = "greetsOnSubmit"

    /// Every key above; `LaunchOptions` removes them for a UI-test launch.
    static let all = [greetsOnSubmit]
}

/// The Settings window (⌘,), one tab per pane as on every Mac app.
struct SettingsView: View {
    @AppStorage(SettingsKeys.greetsOnSubmit) private var greetsOnSubmit = true

    var body: some View {
        TabView {
            Tab("General", systemImage: "gearshape") {
                Form {
                    Toggle("Greet when pressing Return", isOn: $greetsOnSubmit)
                        .accessibilityIdentifier("greetsOnSubmitToggle")
                }
                .formStyle(.grouped)
            }
        }
        .frame(width: 420, height: 180)
    }
}

#Preview {
    SettingsView()
}
```
