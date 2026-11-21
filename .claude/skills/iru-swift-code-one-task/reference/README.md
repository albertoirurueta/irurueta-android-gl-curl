# Swift development reference

General Swift 6 code agreements for implementing a task in a Swift Package or an Apple app project this catalog
scaffolds (`iru-setup-swift-library`, `iru-setup-apple-app`, `iru-setup-macos-app`, `iru-setup-swift-cli`). These files exist so
`iru-swift-code-one-task` can load **only** what the task in front of it actually needs, instead of carrying every rule in context on every
task.

## Read this much, and no more

Detect the project shape and target first (`iru-swift-code-one-task` Step 1 — package vs. app, and which target),
then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Swift 6 strict concurrency, value types, access control, error handling, naming, member ordering. |
| `docc.md` | The task adds or changes a type, or any `public`/`package`/`internal` member. |
| `testing.md` | The task writes or updates tests — which is nearly every task, since writing the tests is part of implementing it. |
| `swiftui.md` | The task adds or changes a SwiftUI `View` or view model. |
| `package-api.md` | The task changes a `public` declaration in a library package's target — semver-stable API, availability, `Package.swift` hygiene. |
| `app-structure.md` | The task adds a new app target, touches the generator manifest, or wires shared logic between `Packages/Core` and a platform target. |
| `architecture.md` | The task adds or changes a view model, an MVC controller or its view protocol, a service protocol/test double, or decides whether logic goes in `Packages/Core` or the app target — every app task (macOS, iOS/iPadOS, visionOS) beyond a one-function change. Also how to detect the app target/platform (`App/` vs `Targets/<Platform>/`), the UI framework, and MVVM vs MVC from the layout. |
| `macos.md` | The task touches a macOS app target: scenes/windows, menus/commands/shortcuts, Settings, `MenuBarExtra`, file access (panels, App Sandbox, security-scoped bookmarks), entitlements/hardened runtime, Apple-silicon/architecture settings, or main-thread work. |
| `appkit.md` | The task adds or changes AppKit code: window/view controllers, target-action, delegates/data sources, `NSMenu`, Auto Layout in code, AppKit MVVM via Observation tracking, or SwiftUI/AppKit interop. |
| `ios.md` | The task touches an iOS/iPadOS app target: app/scene lifecycle and `scenePhase`, background tasks (`BGTaskScheduler`, background `URLSession`), notifications, permission prompts and `Info.plist` usage-description keys, the privacy manifest, widgets/App Intents, iPad windows/multitasking/menu bar/pointer/keyboard shortcuts, size classes, Dynamic Type/accessibility, Keychain secrets, or main-thread work. |
| `uikit.md` | The task adds or changes UIKit code: app/scene delegates, view controllers, Auto Layout in code, navigation/tab/split containers, diffable collection views and cell registrations, `UIAction`/target-action, the trait system, UIKit MVVM via Observation tracking (`updateProperties()`/`UIObservationTrackingEnabled`), or `UIHostingController`/`UIHostingConfiguration`/`UIViewRepresentable` interop. |
| `cli.md` | The package is a command-line tool (an `.executableTarget` depending on `ArgumentParser`) and the task adds or changes a command, flag, output, error/exit code, prompt, signal/cleanup, child process or the CLI tests — where code goes (`<Bin>Kit` vs the thin commands), swift-argument-parser patterns, implementing the contract, testing the executable, Linux portability. |
| `cli-contract.md` | Together with `cli.md`, whenever a CLI task changes anything a caller observes: stdout/stderr, exit codes, `--format`/JSON, colour, prompts, `--dry-run`/`--yes`, `schema`/help text. |
| `visionos.md` | The task touches a visionOS target or destination: windows, volumes, immersive spaces, ornaments, hover effects, `RealityView`/`Model3D`, or visionOS-unavailable APIs. |
| `ui-contract.md` | The task adds or changes a screen, component, list or other UI — the catalog-wide UI contract (copied verbatim into every coding skill that builds UI): decomposition, composition over inheritance, inputs/events, virtualized collections, UI tests, the `.ui-screenshots/` layout, `.ui-quality.yml` accessibility levels. |
| `ui-implementation.md` | Always together with `ui-contract.md` — the contract on SwiftUI/UIKit/AppKit: what a screen and a component are, value-type state + closures, `List`/`Lazy*`/diffable `UICollectionView`/`NSTableView` collections, `ImageRenderer` component captures and XCUITest screen/flow captures through `UIScreenshots.swift`, RTL/dark switches, `performAccessibilityAudit()`. |

A task that only changes logic inside one existing function, with no signature change and no UI involved, needs
`code-style.md` plus `testing.md`, and nothing else. Don't read the rest speculatively. Read at most one of
`package-api.md`/`app-structure.md` for a given task — a change belongs to the library's published surface or to
the app's own target wiring, rarely both. Read at most one of `swiftui.md`/`appkit.md` for a given screen unless it
hosts one framework inside the other — and likewise at most one of `uikit.md`/`appkit.md`. Read at most the platform
file(s) of the target(s) the task touches: `macos.md`, `ios.md`, `visionos.md`. A command-line tool task reads
`cli.md` (+ `cli-contract.md` when observable behaviour changes) and never the app files; it reads `package-api.md`
only if the Kit is also published as a library product. A task that adds or changes a screen, component or list
reads `ui-contract.md` + `ui-implementation.md` on top of its framework file (`swiftui.md`/`uikit.md`/`appkit.md`);
a command-line tool never does (no UI).

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the file or target you're
   editing consistently does something else, follow it and note the divergence in the report rather than
   converting the file to this document's preference as a side effect of an unrelated task. Reordering or
   restyling untouched code turns a two-line change into an unreviewable diff.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding an abstraction, a protocol, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond a compile-check of the target it touched, it never runs the full test
   suite, coverage, SwiftLint/`swift format`, license headers, or a DocC audit — the calling
   `iru-swift-code-one-task-group` does all of that once for the whole task group. Write code that will pass
   those gates; don't run them here.

## Sources

UI guidance (`ui-implementation.md`; `ui-contract.md` is the catalog's shared, platform-agnostic contract, copied
verbatim) cites its own Apple documentation list; its screenshot helper was run with Swift 6.4 / Xcode 27.1 under
`swift test` on macOS and `xcodebuild test` on an iOS 27 simulator (captures written directly and recovered from the
`.xcresult` with `xcrun xcresulttool export attachments`), and its collection and XCUITest snippets were type-checked
for iOS 18, macOS 15, watchOS 11 and visionOS 2, in a throwaway directory outside this repository.

Command-line tool guidance (`cli.md`; `cli-contract.md` is the catalog's shared, language-agnostic contract, copied
verbatim) is grounded in irurueta docs: ai/cli-for-agents/ (frameworks-swift-and-dotnet.adoc,
streams-and-exit-codes.adoc, tty-colour-and-interactivity.adoc, machine-readable-output.adoc,
safety-and-idempotency.adoc, self-describing-help.adoc, testing-clis.adoc, security.adoc, configuration-and-auth.adoc —
https://www.irurueta.com/ai/cli-for-agents/index.html), irurueta docs: apps/apple/testing-with-swift-testing.adoc, the
swift-argument-parser documentation and sources, and swift.org's Static Linux SDK guide; its snippets were
type-checked with Swift 6.4 and swift-argument-parser 1.8.2 inside an `iru-setup-swift-cli` scaffold.

Compiled from the following, cross-checked against each other and against a real Swift 6.4 toolchain (Xcode 27),
with this catalog's own stated preferences taking precedence where they are stricter (notably on avoiding
`@unchecked Sendable`, and on the public-to-private declaration order, which the Swift API Design Guidelines
leave unspecified).

- Swift API Design Guidelines: <https://www.swift.org/documentation/api-design-guidelines/>
- The Swift Programming Language — Concurrency: <https://docs.swift.org/swift-book/documentation/the-swift-programming-language/concurrency/>
- The Swift Programming Language — Error Handling: <https://docs.swift.org/swift-book/documentation/the-swift-programming-language/errorhandling/>
- Swift 6 migration guide (strict concurrency, `Sendable`, data-race safety): <https://www.swift.org/migration/documentation/migrationguide/>
- Swift evolution, typed throws (SE-0413): <https://github.com/swiftlang/swift-evolution/blob/main/proposals/0413-typed-throws.md>
- Swift Testing documentation: <https://developer.apple.com/documentation/testing>
- Swift Testing on swift.org: <https://swift.org/documentation/testing/>
- XCTest documentation: <https://developer.apple.com/documentation/xctest>
- DocC documentation: <https://www.swift.org/documentation/docc/>, authoring reference: <https://www.swift.org/documentation/docc/writing-symbol-documentation-in-your-source-files>
- SwiftUI documentation: <https://developer.apple.com/documentation/swiftui>, `@Observable`: <https://developer.apple.com/documentation/observation>
- Swift Package Manager documentation: <https://www.swift.org/documentation/package-manager/>, `PackageDescription` reference: <https://developer.apple.com/documentation/packagedescription>
- `apple/swift-format` (the toolchain's bundled formatter/linter): <https://github.com/swiftlang/swift-format>

macOS desktop guidance (`macos.md`, `appkit.md`, `architecture.md`, and the macOS sections of `testing.md`) is grounded
in the author's docs repository, <https://github.com/albertoirurueta/docs>, and the Apple documentation those pages
cite; every API it names was type-checked with `xcrun swiftc -typecheck -target arm64-apple-macos15 -swift-version 6`
against the macOS 27 SDK (Xcode 27.1) in a throwaway directory outside this repository. Where the SDK disagreed with a
page, the SDK won: `NSControl.Events.valueChanged`/`.primaryActionTriggered`/`.menuActionTriggered`/`.trackingRepeated`
are annotated macOS 27+ (the tracking events and `addTarget(_:action:for:)` are macOS 11+), so they need an
`if #available(macOS 27, *)` guard below 27; and an `@objc` responder-chain command protocol adopted by an
`NSViewController` must be `@MainActor` under Swift 6.

- irurueta docs: apps/apple/macos.adoc (scenes, windows, commands, Settings, `MenuBarExtra`, AppKit in macOS 27,
  App Sandbox, Apple silicon only)
- irurueta docs: apps/apple/app-architecture.adoc (MV with Observation vs MVVM, protocol-typed services and test
  doubles, feature modules)
- irurueta docs: apps/apple/uikit-and-appkit-interop.adoc (`NSWindow`/`NSWindowController`, `NSMenu`,
  `NSHostingController`/`NSHostingView`/`NSHostingMenu`, `NSViewRepresentable`)
- irurueta docs: apps/apple/macos-distribution-outside-the-app-store.adoc (hardened runtime, runtime exceptions,
  Developer ID constraints)
- irurueta docs: apps/apple/signing-capabilities-and-configuration.adoc (entitlements, App Sandbox capability)
- irurueta docs: apps/apple/ui-tests-test-plans-and-coverage.adoc (XCUITest, `launchArguments`, accessibility
  identifiers, waiting)
- irurueta docs: apps/apple/testing-with-swift-testing.adoc (Swift Testing, `@MainActor` suites, test doubles)
- Human Interface Guidelines — Designing for macOS: <https://developer.apple.com/design/human-interface-guidelines/designing-for-macos>;
  The menu bar: <https://developer.apple.com/design/human-interface-guidelines/the-menu-bar>; Settings:
  <https://developer.apple.com/design/human-interface-guidelines/settings>
- SwiftUI Windows: <https://developer.apple.com/documentation/swiftui/windows>; `Window`:
  <https://developer.apple.com/documentation/swiftui/window>; `UtilityWindow`:
  <https://developer.apple.com/documentation/swiftui/utilitywindow>; `OpenWindowAction`:
  <https://developer.apple.com/documentation/swiftui/openwindowaction>; `DismissWindowAction`:
  <https://developer.apple.com/documentation/swiftui/dismisswindowaction>; `WindowPlacement`:
  <https://developer.apple.com/documentation/swiftui/windowplacement>; Customizing window styles and
  state-restoration behavior in macOS:
  <https://developer.apple.com/documentation/swiftui/customizing-window-styles-and-state-restoration-behavior-in-macos>
- Menus and commands: `CommandGroup` <https://developer.apple.com/documentation/swiftui/commandgroup>, `CommandMenu`
  <https://developer.apple.com/documentation/swiftui/commandmenu>, Building and customizing the menu bar with SwiftUI
  <https://developer.apple.com/documentation/swiftui/building-and-customizing-the-menu-bar-with-swiftui>,
  `FocusedValue` <https://developer.apple.com/documentation/swiftui/focusedvalue>
- `Settings` <https://developer.apple.com/documentation/swiftui/settings>, `SettingsLink`
  <https://developer.apple.com/documentation/swiftui/settingslink>, `OpenSettingsAction`
  <https://developer.apple.com/documentation/swiftui/opensettingsaction>, `MenuBarExtra`
  <https://developer.apple.com/documentation/swiftui/menubarextra>
- AppKit: `NSWindowController` <https://developer.apple.com/documentation/appkit/nswindowcontroller>, `NSWindow`
  <https://developer.apple.com/documentation/appkit/nswindow>, `NSMenu` <https://developer.apple.com/documentation/appkit/nsmenu>,
  `NSMenuItemValidation` <https://developer.apple.com/documentation/appkit/nsmenuitemvalidation>, `NSControl.Events`
  <https://developer.apple.com/documentation/appkit/nscontrol/events>, Updating views automatically with observation
  tracking in AppKit <https://developer.apple.com/documentation/appkit/updating-views-automatically-with-observation-tracking-in-appkit>,
  `NSObservationTrackingEnabled` <https://developer.apple.com/documentation/bundleresources/information-property-list/nsobservationtrackingenabled>,
  WWDC26 Modernize your AppKit app <https://developer.apple.com/videos/play/wwdc2026/289/>
- Interop: `NSHostingController` <https://developer.apple.com/documentation/swiftui/nshostingcontroller>,
  `NSHostingView` <https://developer.apple.com/documentation/swiftui/nshostingview>, `NSHostingMenu`
  <https://developer.apple.com/documentation/swiftui/nshostingmenu>, `NSViewRepresentable`
  <https://developer.apple.com/documentation/swiftui/nsviewrepresentable>, AppKit integration
  <https://developer.apple.com/documentation/swiftui/appkit-integration>
- Architecture: Managing model data in your app <https://developer.apple.com/documentation/swiftui/managing-model-data-in-your-app>,
  Organizing your code with local packages <https://developer.apple.com/documentation/xcode/organizing-your-code-with-local-packages>
- Sandbox and files: Configuring the macOS App Sandbox <https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox>,
  Accessing files from the macOS App Sandbox <https://developer.apple.com/documentation/security/accessing-files-from-the-macos-app-sandbox>,
  `fileImporter` <https://developer.apple.com/documentation/swiftui/view/fileimporter(ispresented:allowedcontenttypes:oncompletion:)>,
  `startAccessingSecurityScopedResource()` <https://developer.apple.com/documentation/foundation/url/startaccessingsecurityscopedresource()>,
  Supported capabilities (macOS) <https://developer.apple.com/help/account/reference/supported-capabilities-macos>
- Hardened runtime: Configuring the hardened runtime <https://developer.apple.com/documentation/xcode/configuring-the-hardened-runtime>,
  Hardened Runtime <https://developer.apple.com/documentation/security/hardened-runtime>
- Apple silicon: About the Rosetta translation environment <https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment>,
  Building a universal macOS binary <https://developer.apple.com/documentation/apple-silicon/building-a-universal-macos-binary>,
  macOS 27 release notes <https://developer.apple.com/documentation/macos-release-notes/macos-27-release-notes>
- UI testing: `XCUIApplication` <https://developer.apple.com/documentation/xcuiautomation/xcuiapplication>,
  `launchArguments` <https://developer.apple.com/documentation/xcuiautomation/xcuiapplication/launcharguments>,
  `XCUIElementQuery` <https://developer.apple.com/documentation/xcuiautomation/xcuielementquery>

iOS/iPadOS, UIKit and visionOS guidance (`ios.md`, `uikit.md`, `visionos.md`, the iOS/UIKit parts of `architecture.md`,
and the simulator section of `testing.md`) is grounded in the same docs repository and the Apple documentation those
pages cite; every API it names was type-checked with `xcrun -sdk iphonesimulator swiftc -typecheck -target
arm64-apple-ios18.0-simulator -swift-version 6` (and `-ios26.0` for iOS 26/27 APIs) against the iOS 27.1 simulator SDK,
and with `xcrun -sdk xrsimulator swiftc -typecheck -target arm64-apple-xros2.0-simulator` (also `xros1.0`/`xros26.0`)
against the visionOS 27.0 simulator SDK (Xcode 27.1), in a throwaway directory outside this repository. Where the SDK and
a page differ, the SDK won: UIKit automatic Observation tracking also works on iOS 18 behind the `Info.plist` key
`UIObservationTrackingEnabled` (present in the iOS 18.5 runtime's `UIKitCore`, absent from 26.x where it is on by
default) — only in the layout-family methods, since `updateProperties()` is iOS 26+; `isInteractivelyResizing` and
`updateProperties()` are rejected below iOS 26; `UIMainMenuSystem` and `UISceneClosureConfirmation` set from a free
function need `@MainActor` (Swift 6 isolation diagnostics); a representable's target-action `Coordinator` must be
`@MainActor` to read UIKit state; and in a file that imports RealityKit, `some Scene` is ambiguous and must be written
`some SwiftUI.Scene`.

- irurueta docs: apps/apple/uikit-and-appkit-interop.adoc (view controllers, `updateProperties()` observation
  tracking, Auto Layout, `UINavigationController`, diffable data sources, the trait system, `UIHostingController`/
  `UIHostingConfiguration`, `UIViewRepresentable`/`UIViewControllerRepresentable`, `UITraitBridgedEnvironmentKey`)
- irurueta docs: apps/apple/app-structure-and-lifecycle.adoc (scene phases, `UIApplicationDelegateAdaptor`, UIKit app
  and scene delegates, the scene lifecycle mandatory with the 27 SDK, `@SceneStorage`/state restoration)
- irurueta docs: apps/apple/background-execution.adoc (suspension model, `beginBackgroundTask`, `BGTaskScheduler`,
  `.backgroundTask`, background modes and permitted identifiers)
- irurueta docs: apps/apple/notifications-and-live-activities.adoc (authorization, provisional delivery,
  `UNUserNotificationCenterDelegate`, APNs registration, Live Activities requirements)
- irurueta docs: apps/apple/security-and-privacy.adoc (Keychain Services and accessibility classes, access groups,
  LocalAuthentication, privacy manifests and required-reason APIs, App Tracking Transparency)
- irurueta docs: apps/apple/ipados.adoc (multiple scenes, window sizes and size classes, `UISceneSizeRestrictions`,
  effective geometry, `UISceneClosureConfirmation`, the iPad menu bar and `UIMainMenuSystem`, `.sidebarAdaptable`,
  pointer input)
- irurueta docs: apps/apple/visionos.adoc (Shared/Full Space, windows and ornaments, volumes, immersive spaces and
  immersion styles, hover effects, `RealityView`/`Model3D`, bringing iPad apps to visionOS)
- irurueta docs: apps/apple/widgets-and-controls.adoc (widget extension, App Group data, reload budget)
- irurueta docs: apps/apple/app-intents-and-system-integration.adoc (intents/entities/shortcuts, target membership,
  deep links, app extensions and App Groups)
- irurueta docs: apps/apple/accessibility.adoc (labels/traits, `accessibilityIdentifier` for UI tests only, Dynamic
  Type, system display settings, accessibility audits in UI tests)
- UIKit: About app development with UIKit <https://developer.apple.com/documentation/uikit/about-app-development-with-uikit>,
  `updateProperties()` <https://developer.apple.com/documentation/uikit/uiviewcontroller/updateproperties()>,
  `NSLayoutAnchor` <https://developer.apple.com/documentation/uikit/nslayoutanchor>, `UINavigationController`
  <https://developer.apple.com/documentation/uikit/uinavigationcontroller>, `UICollectionViewDiffableDataSource`
  <https://developer.apple.com/documentation/uikit/uicollectionviewdiffabledatasource-9tqpa>,
  `UICollectionView.CellRegistration` <https://developer.apple.com/documentation/uikit/uicollectionview/cellregistration>,
  `UIMutableTraits` <https://developer.apple.com/documentation/uikit/uimutabletraits-13ja5>, Automatic trait tracking
  <https://developer.apple.com/documentation/uikit/automatic-trait-tracking>, Elevating your iPad app with a tab bar
  and sidebar <https://developer.apple.com/documentation/uikit/elevating-your-ipad-app-with-a-tab-bar-and-sidebar>,
  WWDC26 Modernize your UIKit app <https://developer.apple.com/videos/play/wwdc2026/278/>
- UIKit interop: Using SwiftUI with UIKit <https://developer.apple.com/documentation/uikit/using-swiftui-with-uikit>,
  `UIHostingController` <https://developer.apple.com/documentation/swiftui/uihostingcontroller>,
  `UIHostingConfiguration` <https://developer.apple.com/documentation/swiftui/uihostingconfiguration>,
  `UIViewRepresentable` <https://developer.apple.com/documentation/swiftui/uiviewrepresentable>,
  `UITraitBridgedEnvironmentKey` <https://developer.apple.com/documentation/uikit/uitraitbridgedenvironmentkey>
- Lifecycle: Managing your app's life cycle <https://developer.apple.com/documentation/uikit/managing-your-app-s-life-cycle>,
  Transitioning to the UIKit scene-based life cycle
  <https://developer.apple.com/documentation/uikit/transitioning-to-the-uikit-scene-based-life-cycle>, Specifying the
  scenes your app supports <https://developer.apple.com/documentation/uikit/specifying-the-scenes-your-app-supports>,
  `ScenePhase` <https://developer.apple.com/documentation/swiftui/scenephase>, `UIApplicationDelegateAdaptor`
  <https://developer.apple.com/documentation/swiftui/uiapplicationdelegateadaptor>, `SceneStorage`
  <https://developer.apple.com/documentation/swiftui/scenestorage>
- Background: `beginBackgroundTask(withName:expirationHandler:)`
  <https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:)>,
  Configuring background execution modes <https://developer.apple.com/documentation/xcode/configuring-background-execution-modes>,
  `BGTaskScheduler` <https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler>,
  `BGAppRefreshTaskRequest` <https://developer.apple.com/documentation/backgroundtasks/bgapprefreshtaskrequest>,
  `BGProcessingTaskRequest` <https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest>,
  `backgroundTask(_:action:)` <https://developer.apple.com/documentation/swiftui/scene/backgroundtask(_:action:)>
- Notifications: Asking permission to use notifications
  <https://developer.apple.com/documentation/usernotifications/asking-permission-to-use-notifications>,
  `UNUserNotificationCenterDelegate` <https://developer.apple.com/documentation/usernotifications/unusernotificationcenterdelegate>,
  Registering your app with APNs <https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns>
- Security and privacy: Keychain Services <https://developer.apple.com/documentation/security/keychain-services>,
  Restricting keychain item accessibility <https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility>,
  Privacy manifest files <https://developer.apple.com/documentation/bundleresources/privacy-manifest-files>, Describing
  use of required reason API <https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api>,
  App Tracking Transparency <https://developer.apple.com/documentation/apptrackingtransparency>
- iPadOS: `UIApplicationSupportsMultipleScenes`
  <https://developer.apple.com/documentation/bundleresources/information-property-list/uiapplicationscenemanifest/uiapplicationsupportsmultiplescenes>,
  `horizontalSizeClass` <https://developer.apple.com/documentation/swiftui/environmentvalues/horizontalsizeclass>,
  Multitasking on iPad, Mac, and Apple Vision Pro <https://developer.apple.com/documentation/uikit/multitasking-on-ipad-mac-and-apple-vision-pro>,
  `UIMainMenuSystem` <https://developer.apple.com/documentation/uikit/uimainmenusystem>, `UISceneClosureConfirmation`
  <https://developer.apple.com/documentation/uikit/uisceneclosureconfirmation>, `hoverEffect(_:isEnabled:)`
  <https://developer.apple.com/documentation/swiftui/view/hovereffect(_:isenabled:)>, `UIPointerInteraction`
  <https://developer.apple.com/documentation/uikit/uipointerinteraction>
- Widgets and intents: Creating a widget extension <https://developer.apple.com/documentation/widgetkit/creating-a-widget-extension>,
  Keeping a widget up to date <https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date>,
  Getting started with the App Intents framework
  <https://developer.apple.com/documentation/appintents/getting-started-with-the-app-intents-framework>, Configuring app
  groups <https://developer.apple.com/documentation/xcode/configuring-app-groups>
- Accessibility: `ScaledMetric` <https://developer.apple.com/documentation/swiftui/scaledmetric>, `isAccessibilitySize`
  <https://developer.apple.com/documentation/swiftui/dynamictypesize/isaccessibilitysize>, `accessibilityIdentifier(_:)`
  <https://developer.apple.com/documentation/swiftui/view/accessibilityidentifier(_:)>, `performAccessibilityAudit(for:_:)`
  <https://developer.apple.com/documentation/xcuiautomation/xcuiapplication/performaccessibilityaudit(for:_:)>
- visionOS: Presenting windows and spaces <https://developer.apple.com/documentation/visionos/presenting-windows-and-spaces>,
  Immersive spaces <https://developer.apple.com/documentation/swiftui/immersive-spaces>, `ImmersiveSpace`
  <https://developer.apple.com/documentation/swiftui/immersivespace>, `OpenImmersiveSpaceAction`
  <https://developer.apple.com/documentation/swiftui/openimmersivespaceaction>, `ImmersionStyle`
  <https://developer.apple.com/documentation/swiftui/immersionstyle>, Positioning and sizing windows
  <https://developer.apple.com/documentation/visionos/positioning-and-sizing-windows>, Ornaments (HIG)
  <https://developer.apple.com/design/human-interface-guidelines/ornaments>, Eyes (HIG)
  <https://developer.apple.com/design/human-interface-guidelines/eyes>, `HoverEffectComponent`
  <https://developer.apple.com/documentation/realitykit/hovereffectcomponent>, `Model3D`
  <https://developer.apple.com/documentation/realitykit/model3d>, Adding 3D content to your app
  <https://developer.apple.com/documentation/visionos/adding-3d-content-to-your-app>, Bringing your existing apps to
  visionOS <https://developer.apple.com/documentation/visionos/bringing-your-app-to-visionos>
