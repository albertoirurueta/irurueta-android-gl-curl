# `iosApp/` templates

The SwiftUI host app. `iosApp/project.yml` is the XcodeGen manifest; run `xcodegen generate` inside `iosApp/` to create the (gitignored) `iosApp.xcodeproj`. `Info.plist` is a committed file, not XcodeGen-generated.

Conditional markers (`// >>> ui: compose` … `// <<< ui: compose`, `# >>> sonar` …) and `__pkg__` work as described in SKILL.md's "Reference files" section; every file below was rendered and built for both `ui` values on 2026-10-15.

### `iosApp/project.yml`

```yaml
name: iosApp
options:
  deploymentTarget:
    iOS: "<ios-deployment-target>"
  createIntermediateGroups: true
configFiles:
  Debug: Configuration/Config.xcconfig
  Release: Configuration/Config.xcconfig
settings:
  base:
    SWIFT_VERSION: "6.0"
    # Kotlin/Native frameworks are built by Gradle, not Xcode: the pre-build script below needs to run outside
    # Xcode's user-script sandbox.
    ENABLE_USER_SCRIPT_SANDBOXING: NO
targets:
  iosApp:
    type: application
    platform: iOS
    sources:
      - path: iosApp
        excludes:
          - Info.plist
    settings:
      base:
        # PRODUCT_NAME stays the target name (iosApp.app) — the test bundle's TEST_HOST points at it; the launcher
        # label comes from CFBundleDisplayName = $(APP_NAME) in Info.plist.
        PRODUCT_MODULE_NAME: iosApp
        PRODUCT_BUNDLE_IDENTIFIER: "$(BUNDLE_ID)"
        DEVELOPMENT_TEAM: "$(TEAM_ID)"
        INFOPLIST_FILE: iosApp/Info.plist
        TARGETED_DEVICE_FAMILY: "1,2"
        # The Shared.framework that :shared:embedAndSignAppleFrameworkForXcode builds for this configuration/SDK.
        FRAMEWORK_SEARCH_PATHS:
          - "$(inherited)"
          - "$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
        OTHER_LDFLAGS:
          - "$(inherited)"
          - "-framework"
          - "Shared"
    preBuildScripts:
      - name: Compile Kotlin Framework
        basedOnDependencyAnalysis: false
        script: |
          if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
            echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
            exit 0
          fi
          cd "$SRCROOT/.."
          ./gradlew :shared:embedAndSignAppleFrameworkForXcode
  iosAppTests:
    type: bundle.unit-test
    platform: iOS
    sources:
      - path: iosAppTests
    dependencies:
      - target: iosApp
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: "$(BUNDLE_ID).tests"
        DEVELOPMENT_TEAM: "$(TEAM_ID)"
        GENERATE_INFOPLIST_FILE: YES
        # Import-only: the static Shared framework is already linked into the host app (BUNDLE_LOADER).
        FRAMEWORK_SEARCH_PATHS:
          - "$(inherited)"
          - "$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
schemes:
  iosApp:
    build:
      targets:
        iosApp: all
        iosAppTests: [test]
    test:
      config: Debug
      gatherCoverageData: true
      coverageTargets:
        - iosApp
      targets:
        - iosAppTests
    run:
      config: Debug
    archive:
      config: Release
```

### `iosApp/.swiftlint.yml`

```yaml
included:
  - iosApp
  - iosAppTests
excluded:
  - build
  - DerivedData
opt_in_rules:
  - closure_spacing
  - empty_count
  - explicit_init
  - first_where
  - force_unwrapping
  - implicitly_unwrapped_optional
  - overridden_super_call
  - redundant_nil_coalescing
  - sorted_imports
analyzer_rules:
  - unused_import
line_length:
  warning: 120
  error: 160
```

### `iosApp/Configuration/Config.xcconfig`

```text
// App identity and version for the iOS app. iru-kmp-bump-version keeps the two version keys in step with the
// root gradle.properties on every release: MARKETING_VERSION = the release version (never a -SNAPSHOT value —
// App Store Connect rejects it), CURRENT_PROJECT_VERSION = VERSION_CODE.
TEAM_ID=<team-id>
BUNDLE_ID=<bundle-id>
APP_NAME=<app-name>
MARKETING_VERSION=<marketing-version>
CURRENT_PROJECT_VERSION=<version-code>
```

### `iosApp/iosApp/ContentView.swift` (ui: compose)

```swift
import Shared
import SwiftUI
import UIKit

/// Embeds the shared Compose Multiplatform UI (`MainViewController()` in shared/src/iosMain).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
```

### `iosApp/iosApp/ContentView.swift` (ui: native)

```swift
import Shared
import SwiftUI

/// Owns the screen's model and passes its state down to the stateless `GreetingScreen`.
struct ContentView: View {
    @StateObject private var model = GreetingModel()

    var body: some View {
        GreetingScreen(state: model.state, onTap: model.tap)
    }
}

/// Stateless greeting screen over the shared `GreetingUiState`.
///
/// Inputs: `state`. Events: `onTap` when the counter button is tapped. Visual states: `default`, `long-content`.
struct GreetingScreen: View {
    let state: GreetingUiState
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(state.message)
                .font(.title2)
                .multilineTextAlignment(.center)
            Button(state.buttonLabel, action: onTap)
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
```

### `iosApp/iosApp/GreetingModel.swift` (ui: native)

```swift
import Combine
import Shared

/// SwiftUI-facing wrapper around the shared, immutable `GreetingUiState`.
@MainActor
final class GreetingModel: ObservableObject {
    @Published private(set) var state: GreetingUiState

    init(state: GreetingUiState = GreetingUiState.companion.initial()) {
        self.state = state
    }

    func tap() {
        state = state.clicked()
    }
}
```

### `iosApp/iosApp/Info.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>$(DEVELOPMENT_LANGUAGE)</string>
	<key>CFBundleDisplayName</key>
	<string>$(APP_NAME)</string>
	<key>CFBundleExecutable</key>
	<string>$(EXECUTABLE_NAME)</string>
	<key>CFBundleIdentifier</key>
	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>$(PRODUCT_NAME)</string>
	<key>CFBundlePackageType</key>
	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
	<key>CFBundleShortVersionString</key>
	<string>$(MARKETING_VERSION)</string>
	<key>CFBundleVersion</key>
	<string>$(CURRENT_PROJECT_VERSION)</string>
	<key>LSRequiresIPhoneOS</key>
	<true/>
	<key>CADisableMinimumFrameDurationOnPhone</key>
	<true/>
	<key>ITSAppUsesNonExemptEncryption</key>
	<false/>
	<key>UILaunchScreen</key>
	<dict/>
	<key>UISupportedInterfaceOrientations</key>
	<array>
		<string>UIInterfaceOrientationPortrait</string>
		<string>UIInterfaceOrientationLandscapeLeft</string>
		<string>UIInterfaceOrientationLandscapeRight</string>
	</array>
	<key>UISupportedInterfaceOrientations~ipad</key>
	<array>
		<string>UIInterfaceOrientationPortrait</string>
		<string>UIInterfaceOrientationPortraitUpsideDown</string>
		<string>UIInterfaceOrientationLandscapeLeft</string>
		<string>UIInterfaceOrientationLandscapeRight</string>
	</array>
</dict>
</plist>
```

### `iosApp/iosApp/MainApp.swift`

```swift
import SwiftUI

@main
struct MainApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

### `iosApp/iosAppTests/GreetingScreenTests.swift` (ui: native)

```swift
@testable import iosApp
import Shared
import SwiftUI
import XCTest

final class GreetingScreenTests: XCTestCase {
    private let longMessage = "Hello, " + String(repeating: "a very long platform name ", count: 6)

    @MainActor
    func testEveryStateIsCapturedInEveryDirectionAndTheme() throws {
        let states: [(String, GreetingUiState)] = [
            ("default", GreetingUiState(message: "Hello, Test!", clicks: 0)),
            ("long-content", GreetingUiState(message: longMessage, clicks: 12)),
            ("rtl-content", GreetingUiState(message: "مرحبا بالعالم", clicks: 0))
        ]
        for (name, state) in states {
            for variant in UIScreenshots.matrix {
                try UIScreenshots.capture(
                    GreetingScreen(state: state, onTap: {}),
                    kind: "screen",
                    id: "GreetingScreen",
                    state: name,
                    variant: variant
                )
            }
        }
    }
}
```

### `iosApp/iosAppTests/UIScreenshots.swift` (ui: native)

The UI contract's screenshot helper for SwiftUI (`ImageRenderer`). `xcodebuild test` forwards `UI_SCREENSHOTS_DIR` when it is
passed as `TEST_RUNNER_UI_SCREENSHOTS_DIR`; otherwise the repository root is derived from `#filePath`.

```swift
import SwiftUI
import XCTest

/// One cell of the direction × theme screenshot matrix.
struct UIVariant {
    let direction: LayoutDirection
    let scheme: ColorScheme

    var segments: String {
        (direction == .rightToLeft ? "rtl" : "ltr") + "--" + (scheme == .dark ? "dark" : "light")
    }
}

/// Writes UI-contract screenshots:
/// `<UI_SCREENSHOTS_DIR | <repo>/.ui-screenshots>/<kind>/<Id>/<state>--<dir>--<theme>--ios.png`
/// (`ios` because the same screen also renders natively on Android).
enum UIScreenshots {
    /// The repository root, derived from this file's location (`<repo>/iosApp/iosAppTests/UIScreenshots.swift`).
    static let repositoryRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()

    /// `UI_SCREENSHOTS_DIR` (passed to `xcodebuild test` as `TEST_RUNNER_UI_SCREENSHOTS_DIR`),
    /// else `<repo>/.ui-screenshots`.
    static var directory: URL {
        if let path = ProcessInfo.processInfo.environment["UI_SCREENSHOTS_DIR"], !path.isEmpty {
            return URL(fileURLWithPath: path)
        }
        return repositoryRoot.appendingPathComponent(".ui-screenshots")
    }

    /// The `.ui-quality.yml` matrix (defaults: ltr + rtl × light + dark).
    static var matrix: [UIVariant] {
        let directions = list("directions") ?? ["ltr", "rtl"]
        let themes = (list("themes") ?? ["light", "dark"]).filter { $0 == "light" || $0 == "dark" }
        return directions.flatMap { direction in
            themes.map { theme in
                UIVariant(
                    direction: direction == "rtl" ? .rightToLeft : .leftToRight,
                    scheme: theme == "dark" ? .dark : .light
                )
            }
        }
    }

    /// Renders `view` at `size` in `variant` and writes the PNG.
    @MainActor
    static func capture(
        _ view: some View,
        kind: String,
        id: String,
        state: String,
        variant: UIVariant,
        size: CGSize = CGSize(width: 360, height: 640)
    ) throws {
        let content = view
            .frame(width: size.width, height: size.height)
            .background(Color(uiColor: .systemBackground))
            .environment(\.layoutDirection, variant.direction)
            .environment(\.colorScheme, variant.scheme)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        let png = try XCTUnwrap(renderer.uiImage?.pngData(), "ImageRenderer produced no image for \(id)/\(state)")
        let file = directory
            .appendingPathComponent(kind)
            .appendingPathComponent(id)
            .appendingPathComponent([state, variant.segments, "ios"].joined(separator: "--") + ".png")
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try png.write(to: file)
    }

    private static func list(_ key: String) -> [String]? {
        let url = repositoryRoot.appendingPathComponent(".ui-quality.yml")
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        for line in text.split(separator: "\n") {
            let content = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
            let trimmed = content.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("\(key):") else {
                continue
            }
            let value = trimmed.dropFirst(key.count + 1).trimmingCharacters(in: CharacterSet(charactersIn: " []"))
            return value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        }
        return nil
    }
}
```

### `iosApp/iosAppTests/iosAppTests.swift` (ui: compose)

```swift
@testable import iosApp
import Shared
import UIKit
import XCTest

final class IosAppTests: XCTestCase {
    func testSharedGreetingNamesThePlatform() {
        XCTAssertTrue(Greeting().greet().hasPrefix("Hello, "))
    }

    @MainActor
    func testComposeViewControllerIsCreated() {
        let controller = MainViewControllerKt.MainViewController()
        XCTAssertNotNil(controller.view)
    }
}
```

### `iosApp/iosAppTests/iosAppTests.swift` (ui: native)

```swift
@testable import iosApp
import Shared
import XCTest

final class IosAppTests: XCTestCase {
    @MainActor
    func testInitialStateGreetsThePlatform() {
        let model = GreetingModel()
        XCTAssertTrue(model.state.message.hasPrefix("Hello, "))
        XCTAssertEqual(model.state.clicks, 0)
    }

    @MainActor
    func testTapIncrementsTheCounter() {
        let model = GreetingModel()
        model.tap()
        XCTAssertEqual(model.state.clicks, 1)
        XCTAssertEqual(model.state.buttonLabel, "Tapped 1 time")
    }
}
```
