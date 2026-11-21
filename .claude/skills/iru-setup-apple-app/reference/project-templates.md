# Project-level templates

The generator manifest (XcodeGen `project.yml`, or Tuist `Project.swift` + `Tuist.swift`), the lint/format configs,
`.ui-quality.yml`, `fastlane/Fastfile` and `sonar-project.properties` — everything outside `Targets/<Platform>/…` and
`Packages/Core`.

Placeholders and `# >>> <condition>` / `# <<< <condition>` markers work as described in SKILL.md's "Reference files"
section. Per-target blocks are repeated once per selected platform, with `<Platform>` one of `iOS` (for `ios` and/or
`ipados`), `macOS`, `watchOS`, `visionOS`. The XcodeGen manifest was generated with XcodeGen 2.46.0 and built/tested
with Xcode 27.1 on 2026-10-16 for `ios+watchos` SwiftUI, `ios` UIKit MVC, `ios` UIKit MVVM (26.0 and 18.0),
`ios+ipados+visionos` SwiftUI and `ios+macos` SwiftUI (see SKILL.md's Known quirks).

### `project.yml` (generator: xcodegen)

```yaml
name: <AppName>
options:
  bundleIdPrefix: <bundle-id-prefix>
  deploymentTarget:
    # One line per selected platform, each from SKILL.md Step 5's per-platform deployment-target table.
    # >>> platform: iOS
    iOS: "<ios-deployment-target>"
    # <<< platform: iOS
    # >>> platform: macOS
    macOS: "<macos-deployment-target>"
    # <<< platform: macOS
    # >>> platform: watchOS
    watchOS: "<watchos-deployment-target>"
    # <<< platform: watchOS
    # >>> platform: visionOS
    visionOS: "<visionos-deployment-target>"
    # <<< platform: visionOS
  xcodeVersion: "<xcode-version>"
configs:
  Debug: debug
  Release: release
settings:
  base:
    SWIFT_VERSION: "6.0"
    SWIFT_STRICT_CONCURRENCY: complete
    # Written unquoted on purpose — iru-swift-bump-version and sync.yml's sync_versions.py rewrite these two
    # keys by regex (quote-tolerant, but the unquoted form is the canonical one this catalog verifies against).
    MARKETING_VERSION: <marketing-version>
    CURRENT_PROJECT_VERSION: <current-project-version>
packages:
  Core:
    path: Packages/Core
targets:
  # ---- Repeat this block (app + unit tests + UI tests) once per selected <Platform>. ----
  <AppName>-<Platform>:
    type: application
    platform: <Platform>
    deploymentTarget: "<platform-deployment-target>"
    sources:
      - path: Targets/<Platform>/Sources
    dependencies:
      - package: Core
      # >>> platform: iOS, watchos-companion
      # Embeds the watch app into the iOS app's build product (Watch/ folder).
      - target: <AppName>-watchOS
        embed: true
      # <<< platform: iOS, watchos-companion
    settings:
      base:
        # <bundle-id> on every platform except a companion watch app (<watch-bundle-id>).
        PRODUCT_BUNDLE_IDENTIFIER: <platform-bundle-id>
        # The target is named <AppName>-<Platform>, which would otherwise yield a <AppName>_<Platform> module and a
        # <AppName>-<Platform>.app product — pin both so `@testable import <AppName>` resolves and release.yml's
        # `build/export/<AppName>.ipa` path matches. The test targets then need TEST_HOST pinned too (below).
        PRODUCT_NAME: <AppName>
        PRODUCT_MODULE_NAME: <AppName>
        # >>> platform: iOS
        # "1" = iPhone only, "2" = iPad only, "1,2" = both — from Step 2's ios/ipados selection.
        TARGETED_DEVICE_FAMILY: "<device-family>"
        # <<< platform: iOS
        # >>> platform: visionOS
        TARGETED_DEVICE_FAMILY: "7"
        # <<< platform: visionOS
        # >>> platform: macOS, distribution: internal|store
        # Required for notarytool to accept the archive (see the Fastfile's notarize_release lane).
        ENABLE_HARDENED_RUNTIME: true
        # <<< platform: macOS, distribution: internal|store
    info:
      path: Targets/<Platform>/Info.plist
      properties:
        CFBundleDisplayName: <app-name>
        NSHumanReadableCopyright: "Copyright © <developer-name>"
        ITSAppUsesNonExemptEncryption: false
        # >>> platform: iOS
        UILaunchScreen: {}
        # <<< platform: iOS
        # >>> platform: iOS, ui: uikit
        # Scene-based life cycle — mandatory with the 27 SDK. No storyboard keys anywhere.
        UIApplicationSceneManifest:
          # true when ipados is selected (several windows on iPad), false for iPhone only.
          UIApplicationSupportsMultipleScenes: <supports-multiple-scenes>
          UISceneConfigurations:
            UIWindowSceneSessionRoleApplication:
              - UISceneConfigurationName: Default Configuration
                UISceneDelegateClassName: "$(PRODUCT_MODULE_NAME).SceneDelegate"
        # <<< platform: iOS, ui: uikit
        # >>> platform: iOS, observation-tracking-opt-in
        # UIKit Observation tracking is on by default from iOS 26; on iOS 18–25 it needs this opt-in.
        UIObservationTrackingEnabled: true
        # <<< platform: iOS, observation-tracking-opt-in
        # >>> platform: macOS
        LSMinimumSystemVersion: "<macos-deployment-target>"
        # <<< platform: macOS
        # >>> platform: watchOS
        # Single-target (extension-free) watch app: installd rejects an embedded watch app without this key.
        WKApplication: true
        # <<< platform: watchOS
        # >>> platform: watchOS, watchos-companion
        WKCompanionAppBundleIdentifier: <bundle-id>
        # <<< platform: watchOS, watchos-companion
        # >>> platform: watchOS, watchos-standalone
        WKWatchOnly: true
        # <<< platform: watchOS, watchos-standalone
        # >>> platform: visionOS
        UIApplicationSceneManifest:
          UIApplicationSupportsMultipleScenes: true
          UIApplicationPreferredDefaultSceneSessionRole: UIWindowSceneSessionRoleApplication
        # <<< platform: visionOS
  <AppName>Tests-<Platform>:
    type: bundle.unit-test
    platform: <Platform>
    deploymentTarget: "<platform-deployment-target>"
    sources:
      - path: Targets/<Platform>/Tests
    dependencies:
      - target: <AppName>-<Platform>
      - package: Core
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
        # XcodeGen derives TEST_HOST from the *target* name (<AppName>-<Platform>.app), but PRODUCT_NAME makes the
        # product <AppName>.app — without these two lines `xcodebuild test` fails with "Could not find test host".
        # macOS: "$(BUILT_PRODUCTS_DIR)/<AppName>.app/Contents/MacOS/<AppName>".
        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/<AppName>.app/<AppName>"
        BUNDLE_LOADER: "$(TEST_HOST)"
  <AppName>UITests-<Platform>:
    type: bundle.ui-testing
    platform: <Platform>
    deploymentTarget: "<platform-deployment-target>"
    sources:
      - path: Targets/<Platform>/UITests
      # The UI contract's screenshot helper lives in the unit-test target and is compiled here too (one file).
      - path: Targets/<Platform>/Tests/Support/UIScreenshots.swift
    dependencies:
      - target: <AppName>-<Platform>
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
schemes:
  # ---- One scheme per selected <Platform>. ----
  <AppName>-<Platform>:
    build:
      targets:
        <AppName>-<Platform>: all
    test:
      config: Debug
      gatherCoverageData: true
      targets:
        - package: Core/CoreTests
        - <AppName>Tests-<Platform>
        - <AppName>UITests-<Platform>
    run:
      config: Debug
    archive:
      config: Release
```

- **One target per platform, on purpose.** The author's docs build iOS/iPadOS/macOS/visionOS from *one*
  multiplatform target (`supportedDestinations` in XcodeGen, Supported Destinations in Xcode; irurueta docs:
  apps/apple/multiplatform-projects.adoc). This skill keeps the `Targets/<Platform>/` layout and the
  `<AppName>-<Platform>` schemes instead, because the Swift coding/gate skills, `iru-explore` and the workflows skill
  key on it; the iOS and visionOS targets share `<bundle-id>` (one App Store Connect record, one product per
  platform). Only watchOS *must* be separate.
- **visionOS** is a native visionOS SwiftUI app (`platform: visionOS`, SDK `xros`/`xrsimulator`,
  `TARGETED_DEVICE_FAMILY` 7, the `XROS_DEPLOYMENT_TARGET` build setting underneath) — not the "Designed for iPad"
  compatibility mode (irurueta docs: apps/apple/visionos.adoc, "Bringing iPad and iPhone Apps to visionOS").
- `ipados`-without-`ios`: the same `<AppName>-iOS` target with `<device-family>` → `"2"`.
- `watchos-companion` = `watchos` + (`ios` or `ipados`); `watchos-standalone` = `watchos` without them.

### `Tuist.swift` (generator: tuist)

```swift
import ProjectDescription

let tuist = Tuist(
    project: .tuist(
        compatibleXcodeVersions: .upToNextMajor("<xcode-version>")
    )
)
```

### `Project.swift` (generator: tuist)

One app/unit-test/UI-test `Target.target(...)` triple per selected platform, collected into one `targets:` array;
shown for iOS/iPadOS (the visionOS app target follows; its test targets mirror the iOS ones with
`destinations: .visionOS`).

```swift
import ProjectDescription

let baseSettings: SettingsDictionary = [
    "SWIFT_VERSION": "6.0",
    "SWIFT_STRICT_CONCURRENCY": "complete",
    // Swift string literals, so these stay quoted here (unlike project.yml) — iru-swift-bump-version and
    // sync_versions.py match exactly this `"KEY": "value"` shape.
    "MARKETING_VERSION": "<marketing-version>",
    "CURRENT_PROJECT_VERSION": "<current-project-version>",
]

// Applied to every *app* target (never to the test targets, whose module names must stay distinct).
let appSettings: SettingsDictionary = baseSettings.merging([
    "PRODUCT_MODULE_NAME": "<AppName>",
]) { _, new in new }

let iosTarget = Target.target(
    name: "<AppName>-iOS",
    // [.iPhone, .iPad] for ios+ipados, .iPhone for ios only, .iPad for ipados only.
    destinations: [.iPhone, .iPad],
    product: .app,
    // The product (and so Tuist's TEST_HOST) is <AppName>.app although the target is <AppName>-iOS.
    productName: "<AppName>",
    bundleId: "<bundle-id>",
    deploymentTargets: .iOS("<ios-deployment-target>"),
    infoPlist: .extendingDefault(with: [
        "CFBundleDisplayName": "<app-name>",
        "NSHumanReadableCopyright": "Copyright © <developer-name>",
        "ITSAppUsesNonExemptEncryption": false,
        "UILaunchScreen": [:],
        // >>> ui: uikit
        "UIApplicationSceneManifest": [
            "UIApplicationSupportsMultipleScenes": <supports-multiple-scenes>,
            "UISceneConfigurations": [
                "UIWindowSceneSessionRoleApplication": [
                    [
                        "UISceneConfigurationName": "Default Configuration",
                        "UISceneDelegateClassName": "$(PRODUCT_MODULE_NAME).SceneDelegate",
                    ],
                ],
            ],
        ],
        // <<< ui: uikit
        // >>> observation-tracking-opt-in
        "UIObservationTrackingEnabled": true,
        // <<< observation-tracking-opt-in
    ]),
    sources: ["Targets/iOS/Sources/**"],
    dependencies: [
        .package(product: "Core"),
        // >>> watchos-companion
        .target(name: "<AppName>-watchOS"),
        // <<< watchos-companion
    ],
    settings: .settings(base: appSettings)
)

let iosTestTarget = Target.target(
    name: "<AppName>Tests-iOS",
    destinations: [.iPhone, .iPad],
    product: .unitTests,
    bundleId: "<bundle-id>.tests",
    deploymentTargets: .iOS("<ios-deployment-target>"),
    sources: ["Targets/iOS/Tests/**"],
    dependencies: [.target(name: "<AppName>-iOS"), .package(product: "Core")]
)

let iosUITestTarget = Target.target(
    name: "<AppName>UITests-iOS",
    destinations: [.iPhone, .iPad],
    product: .uiTests,
    bundleId: "<bundle-id>.uitests",
    deploymentTargets: .iOS("<ios-deployment-target>"),
    sources: ["Targets/iOS/UITests/**", "Targets/iOS/Tests/Support/UIScreenshots.swift"],
    dependencies: [.target(name: "<AppName>-iOS")]
)

// >>> platform: visionOS
let visionTarget = Target.target(
    name: "<AppName>-visionOS",
    destinations: .visionOS,
    product: .app,
    productName: "<AppName>",
    bundleId: "<bundle-id>",
    deploymentTargets: .visionOS("<visionos-deployment-target>"),
    infoPlist: .extendingDefault(with: [
        "CFBundleDisplayName": "<app-name>",
        "UIApplicationSceneManifest": [
            "UIApplicationSupportsMultipleScenes": true,
            "UIApplicationPreferredDefaultSceneSessionRole": "UIWindowSceneSessionRoleApplication",
        ],
    ]),
    sources: ["Targets/visionOS/Sources/**"],
    dependencies: [.package(product: "Core")],
    settings: .settings(base: appSettings)
)
// <<< platform: visionOS

// The project is named <AppName> (never the display name <app-name>), so `tuist generate` writes
// <AppName>.xcodeproj — the same file XcodeGen's `name: <AppName>` produces and the contract names.
let project = Project(
    name: "<AppName>",
    packages: [.local(path: "Packages/Core")],
    targets: [iosTarget, iosTestTarget, iosUITestTarget]
    // Append the other platforms' triples (macOS: destinations .macOS; watchOS: .watchOS with WKApplication: true;
    // visionOS: visionTarget + its tests), the same way project.yml repeats its targets block.
)
```

**Syntax-checked only** (`swiftc -parse` exits 0, also for the UIKit/visionOS additions); `-parse` never resolves
`ProjectDescription`, so the API surface is unconfirmed until `tuist generate` runs (Tuist isn't available in this
skill's verification environment). Earlier versions of this template pinned `PRODUCT_NAME` in settings instead of
`productName:` — an Update replaces that, because Tuist derives `TEST_HOST` from the product name.

### `.swiftlint.yml`

```yaml
included:
  - Targets
  - Packages/Core/Sources
  - Packages/Core/Tests
excluded:
  - "**/.build"
  - "**/*.generated.swift"
  - DerivedData
  - build
disabled_rules:
  # swift-format (.swift-format) owns formatting and adds trailing commas to multi-line collection literals.
  - trailing_comma
opt_in_rules:
  - closure_spacing
  - empty_count
  - explicit_init
  - fatal_error_message
  - first_where
  - force_unwrapping
  - implicitly_unwrapped_optional
  - missing_docs
  - overridden_super_call
  - prohibited_super_call
  - redundant_nil_coalescing
  - sorted_imports
analyzer_rules:
  - unused_import
# The documentation bar iru-swift-docc reads: every public/open declaration of Packages/Core needs a /// comment.
missing_docs:
  warning: [open, public]
line_length:
  warning: 120
  error: 160
identifier_name:
  excluded: [id]
reporter: json
```

Run as `swiftlint lint --strict` from the repository root (0 violations for every verified variant).
`unused_import` is an analyzer rule (`swiftlint analyze` with a compiler log), not an `opt_in_rules` entry.

### `.swift-format`

```json
{
  "version": 1,
  "lineLength": 120,
  "indentation": { "spaces": 4 },
  "tabWidth": 4,
  "respectsExistingLineBreaks": true,
  "lineBreakBeforeControlFlowKeywords": false,
  "lineBreakBeforeEachArgument": false,
  "rules": {
    "AllPublicDeclarationsHaveDocumentation": true,
    "NeverForceUnwrap": true,
    "NeverUseForceTry": true,
    "OrderedImports": true
  }
}
```

Run as `swift format lint --strict --recursive Targets Packages/Core/Sources Packages/Core/Tests` (clean for every
verified variant). Without this file the formatter defaults to 2-space indentation and fails every template.
`OrderedImports` wants every unconditional import — `@testable import` included — above any `#if … import` block.

### `.ui-quality.yml`

The UI contract's quality settings (`iru-swift-code-one-task`'s `reference/ui-contract.md` §8), read by the
`UIScreenshots.swift` helper in the UI tests and by `iru-swift-test`/`iru-swift-code-one-task-group`. Committed —
only `.ui-screenshots/` is gitignored. `<accessibility>` is Step 0/2's `accessibility` (default `enforce`).

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: <accessibility>      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

### `fastlane/Fastfile`

**Fastlane is kept on purpose, and it is optional.** The macOS desktop skills (`iru-setup-macos-app`,
`iru-setup-macos-github-workflows`) use plain `xcodebuild`/`notarytool`/`stapler` because the author's docs show
everything working with Xcode alone (irurueta docs: apps/apple/macos-distribution-outside-the-app-store.adoc,
apps/apple/ci-cd-xcode-cloud-and-command-line.adoc). This skill writes a Fastfile for teams that want `match`-based
signing and TestFlight/App Store lanes, but nothing depends on it: `xcodebuild test`/`archive`/`-exportArchive` with an
App Store Connect API key (the contract in SKILL.md Step 8) is the path `iru-setup-swift-github-workflows` can run
without Fastlane. Deleting `fastlane/` never breaks the scaffold.

Base shape (written whatever `signing`/`distribution` resolved to). **The whole `platform :ios` block exists only
when an iOS/iPadOS target was selected** — the `test` lane and the `build`/`beta`/`release` lanes below all pass
`scheme: "<AppName>-iOS"`, so none of them is written without that target. Without it, drop `platform :ios`
entirely, put the `sync_signing`/`connect_api_key` private lanes (when gated in) inside the `platform :mac` block
below when `macos` was selected, and use `default_platform(:mac)`; with neither iOS/iPadOS nor macOS selected
(watchOS/visionOS only), write no Fastfile — the workflows skill uploads those with `xcodebuild -exportArchive`:

```ruby
default_platform(:ios)

platform :ios do
  desc "Run unit and UI tests"
  lane :test do
    run_tests(
      scheme: "<AppName>-iOS",
      destination: "platform=iOS Simulator,name=<first-available-simulator-name>"
    )
  end
end
```

**Only when `signing: fastlane-match`**, add before the `test` lane:

```ruby
  desc "Sync distribution signing certificates and profiles"
  private_lane :sync_signing do
    match(type: "appstore", readonly: is_ci)
  end
```

**Only when `signing: api-key`**, add instead:

```ruby
  desc "Authenticate to App Store Connect with an API key"
  private_lane :connect_api_key do
    app_store_connect_api_key(
      key_id: ENV["APP_STORE_CONNECT_API_KEY_ID"],
      issuer_id: ENV["APP_STORE_CONNECT_API_KEY_ISSUER_ID"],
      key_content: ENV["APP_STORE_CONNECT_API_KEY_BASE64"],
      is_key_content_base64: true
    )
  end
```

**Only when an iOS/iPadOS target was selected AND `signing` isn't `none` AND `distribution` isn't `none`**, add a
`build` lane and, per `distribution`, `beta`/`release` lanes (inside the same `platform :ios` block, replacing its
closing `end`):

```ruby
  desc "Build a signed, distributable archive"
  lane :build do
    sync_signing if <signing == fastlane-match>
    connect_api_key if <signing == api-key>
    build_app(
      scheme: "<AppName>-iOS",
      export_method: "app-store",
      xcargs: <"-allowProvisioningUpdates" only when signing == api-key, omitted otherwise>
    )
  end

  # Only when distribution is internal or store:
  desc "Upload the latest build to TestFlight"
  lane :beta do
    build
    upload_to_testflight(skip_waiting_for_build_processing: true)
  end

  # Only when distribution is store:
  desc "Submit the latest build to App Store review"
  lane :release do
    build
    upload_to_app_store(skip_screenshots: true, skip_metadata: true, force: true)
  end
end
```

A visionOS build is uploaded the same way with `scheme: "<AppName>-visionOS"` (add a lane only when asked — the
workflows skill uploads it with `xcodebuild -exportArchive` directly).

**Only when `macos` was selected AND `distribution` isn't `none` AND `signing` isn't `none`**, add:

```ruby
platform :mac do
  desc "Build, notarize, and staple the macOS release archive"
  lane :notarize_release do
    connect_api_key if <signing == api-key>
    build_app(scheme: "<AppName>-macOS", export_method: "developer-id")
    notarize(package: lane_context[SharedValues::PKG_OUTPUT_PATH] || lane_context[SharedValues::IPA_OUTPUT_PATH])
    # notarize (the fastlane plugin action) already staples on success; if it isn't installed, fall back to:
    #   sh("xcrun notarytool submit '#{lane_context[SharedValues::IPA_OUTPUT_PATH]}' --wait ...")
    #   sh("xcrun stapler staple '#{lane_context[SharedValues::IPA_OUTPUT_PATH]}'")
  end
end
```

`<first-available-simulator-name>` — the first device under the newest installed iOS runtime in
`xcrun simctl list devices available`, resolved at scaffold time (a literal name, never left for the user).
**Fastlane lanes and signing are never executed by this skill or its verification.** `notarize` is the community
`fastlane-plugin-notarize`; the raw `xcrun notarytool submit --wait` + `xcrun stapler staple` fallback in the comment
needs no plugin and is what the workflows skills use in CI.

### `sonar-project.properties` (sonar)

```properties
# >>> sonar: cloud
sonar.organization=<sonar-organization>
# <<< sonar: cloud
sonar.projectKey=<sonar-project-key>
sonar.host.url=<sonar-host-url>
sonar.sourceEncoding=UTF-8
sonar.sources=Packages/Core/Sources,Targets/iOS/Sources,Targets/macOS/Sources,Targets/watchOS/Sources,Targets/visionOS/Sources
sonar.tests=Packages/Core/Tests,Targets/iOS/Tests,Targets/iOS/UITests,Targets/macOS/Tests,Targets/macOS/UITests,Targets/watchOS/Tests,Targets/watchOS/UITests,Targets/visionOS/Tests,Targets/visionOS/UITests
sonar.swift.file.suffixes=.swift
sonar.coverageReportPaths=sonarqube-generic-coverage.xml
# SwiftLint's `sonarqube` reporter output (generic external-issues JSON), produced by the workflows skill's lint job.
sonar.externalIssuesReportPaths=swiftlint-sonar.json
# The UI layer is not part of the coverage bar (Packages/Core is, at 100%).
sonar.coverage.exclusions=Targets/**
```

List only the `Targets/<Platform>/…` segments for platforms actually selected. `sonar.coverageReportPaths` names
the SonarQube generic-format XML the workflows skill produces from `llvm-cov`, and
`sonar.externalIssuesReportPaths` the `swiftlint lint --reporter sonarqube > swiftlint-sonar.json` report its Linux
`lint` job uploads for the `sonar` job (the same path its scan passes as `-Dsonar.externalIssuesReportPaths`); this
skill never generates either. Omit `sonar.organization` for a self-hosted server without organizations.
