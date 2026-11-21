# iOS and iPadOS apps

Conventions specific to the **iOS/iPadOS target** of an app (the `Targets/iOS/` target `iru-setup-apple-app` scaffolds —
iPhone and iPad share one target — or any XcodeGen/Tuist/hand-maintained project with an iOS app target). Everything
in `code-style.md`, `docc.md`, `testing.md` and `architecture.md` still applies; `swiftui.md` applies to SwiftUI views,
`uikit.md` to UIKit controllers and views. This file adds what only the phone and tablet have: the suspension model,
background execution, notifications and permission prompts, the privacy manifest, widgets/App Intents, iPad
multitasking, pointer and keyboard, size classes, Dynamic Type and the Keychain. The macOS counterpart is `macos.md`;
visionOS-only scenes are in `visionos.md`.

Every API named below was type-checked against the iOS 27.1 simulator SDK (Xcode 27.1, Swift 6 language mode) with a
deployment target of iOS 18 — see the "Verification" note at the end.

## Availability: read the deployment target first

Read `IPHONEOS_DEPLOYMENT_TARGET` (XcodeGen `deploymentTarget`/`settings`, Tuist `deploymentTargets`, or the
`.xcodeproj`) before using any API below. Minimums worth knowing:

| API | Minimum iOS/iPadOS |
|---|---|
| `WindowGroup(id:for:)`, `openWindow(id:value:)` | 16 |
| `hoverEffect(_:isEnabled:)`, `contentShape(.hoverEffect, _:)` | 17 |
| `.sidebarAdaptable`, `TabViewCustomization`, `UITab` | 18 |
| `UIMainMenuSystem`, iPad menu bar, `windowScene(_:didUpdateEffectiveGeometry:)`, `BGContinuedProcessingTask` | 26 |
| `BackgroundTask.processingTask(_:)` (SwiftUI), `UISceneClosureConfirmation`, `UISceneAccessory` | 27 |

Anything newer than the deployment target goes behind `if #available(iOS NN, *)` with a working fallback (a scene
modifier can't be — keep the older registration path until the minimum moves); never raise the deployment target as a
side effect of a task.

## App and scene lifecycle

- **SwiftUI**: `@main struct <AppName>App: App`; react to `@Environment(\.scenePhase)` — `.active`, `.inactive`
  (visible, not receiving events: pause timers/animations), `.background` (save **now**: a suspended process gets no
  warning before termination). Read in the `App`, it aggregates all scenes; read in a view, it is that view's scene.
- App-level delegate behavior (remote-notification token, `BGTaskScheduler` registration, the notification-center
  delegate) goes in **one** `@UIApplicationDelegateAdaptor(AppDelegate.self)` in the `App` type — prefer SwiftUI APIs
  (`scenePhase`, `.onOpenURL`, `.backgroundTask`) whenever one exists.
- **UIKit**: the scene-based lifecycle is mandatory with the 27 SDK (an app without a `UIWindowSceneDelegate` fails to
  launch) — `uikit.md` has the app-delegate/scene-delegate split.
- **The suspension model**: after the last scene backgrounds, code runs for a few seconds, then the process is
  *suspended* (no timers, no network callbacks, `Task`s make no progress) and may be *terminated* without notice.
  A background launch (a due task, a background push, a finished download) starts the app **without showing a
  scene** — anything a handler needs (the store, services) must be reachable from the composition root without a
  window.
- UI state that should survive termination: `@SceneStorage` (per scene; identifiers only, never data or secrets) or
  UIKit `stateRestorationActivity(for:)`. Data lives in the store.

## Background execution

Pick the API by *when* the work must happen — a wrong pick is the common mistake:

| Need | API |
|---|---|
| Finish something already running when the user leaves | `UIApplication.shared.beginBackgroundTask(withName:expirationHandler:)` + `endBackgroundTask(_:)` — **always** ended, also from the expiration handler |
| Fresh content a few times a day (~30 s) | `BGAppRefreshTaskRequest`, handled with SwiftUI `.backgroundTask(.appRefresh("<id>")) { … }` or `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)` |
| Heavy, deferrable maintenance (idle, charging) | `BGProcessingTaskRequest` (`requiresExternalPower`, `requiresNetworkConnectivity`); SwiftUI `.processingTask(_:)` only from iOS 27 |
| Long transfers | Background `URLSession` (`URLSessionConfiguration.background(withIdentifier:)`, `sessionSendsLaunchEvents = true`), relaunch handled with `.backgroundTask(.urlSession("<id>"))` |
| Server says something changed | Background push (`content-available: 1`), `remote-notification` mode |

Rules:

- Every task identifier is listed in the `Info.plist` array `BGTaskSchedulerPermittedIdentifiers` (prefixed with the
  bundle id), and the matching `UIBackgroundModes` entry (`fetch`, `processing`, `remote-notification`) is set —
  through the generator manifest's `info` properties (`app-structure.md`). Long-running modes (`audio`, `location`,
  `voip`) only when the feature genuinely does that work; App Review checks.
- Register `BGTaskScheduler` handlers **before launch finishes** (`application(_:didFinishLaunchingWithOptions:)`),
  and use either the SwiftUI modifier or `register` for one identifier, never both.
- A request is a hint (`earliestBeginDate` is the earliest, not a schedule); tasks are one-shot — submit the next one
  from the handler. Call `setTaskCompleted(success:)` and set `expirationHandler` to cancel the Swift `Task`; with the
  SwiftUI modifier, honour cancellation instead (`try Task.checkCancellation()`).
- The scheduler/registration code lives in the app target (it imports `BackgroundTasks`/UIKit); the *work* is a Core
  service method with a protocol seam, tested without the scheduler. `submit` throws on the Simulator
  (`.unavailable`) — handle the error, don't crash. Never use the deprecated `setMinimumBackgroundFetchInterval(_:)`.

## Notifications

- Ask with `UNUserNotificationCenter.current().requestAuthorization(options:)` (`async throws -> Bool`) **in
  context** — when the user first asks for a reminder — never at launch. `.provisional` (quiet delivery, no prompt) is
  a good launch-time default; check `notificationSettings().authorizationStatus` before scheduling and offer a
  Settings link (`UIApplication.openSettingsURLString`) when denied. No `Info.plist` key is needed.
- Set `UNUserNotificationCenter.current().delegate` before launch finishes (the center holds it **weakly** — the app
  delegate keeps it). Implement the async `willPresent` (return `[.banner, .list, .sound]` to show in the foreground)
  and `didReceive` as `nonisolated` methods on a `@MainActor` delegate, extracting `Sendable` values before hopping to
  the main actor. A tap that cold-launched the app is routed to navigation state the first scene reads.
- Local scheduling (`UNMutableNotificationContent`, `UNTimeIntervalNotificationTrigger`/`UNCalendarNotificationTrigger`,
  `UNNotificationRequest`, categories with actions) is a Core service (`UserNotifications` is UI-free) behind a protocol.
- Remote notifications need the Push Notifications capability (`aps-environment` entitlement) — say in the report it
  must be enabled on the App ID; `registerForRemoteNotifications()` + `didRegisterForRemoteNotificationsWithDeviceToken`
  (send the token every time it arrives) + `didFailToRegisterForRemoteNotificationsWithError`.
- Live Activities (ActivityKit, iOS/iPadOS only) need `NSSupportsLiveActivities` and live in the widget extension.

## Permission prompts and usage-description keys

Accessing a protected resource without its `Info.plist` usage-description string **crashes** the app. For every
permission a task adds:

- Add the key with a specific, user-facing reason (localized via `InfoPlist.xcstrings` if the project localizes) via
  the generator manifest's `info` properties: `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`,
  `NSPhotoLibraryUsageDescription`/`NSPhotoLibraryAddUsageDescription`, `NSLocationWhenInUseUsageDescription`
  (`NSLocationAlwaysAndWhenInUseUsageDescription` only for genuine background location), `NSContactsUsageDescription`,
  `NSCalendarsFullAccessUsageDescription`/`NSCalendarsWriteOnlyAccessUsageDescription`, `NSFaceIDUsageDescription`,
  `NSBluetoothAlwaysUsageDescription`, `NSMotionUsageDescription`, `NSLocalNetworkUsageDescription`,
  `NSSpeechRecognitionUsageDescription`, `NSHealthShareUsageDescription`/`NSHealthUpdateUsageDescription`,
  `NSUserTrackingUsageDescription` (App Tracking Transparency).
- Request at the moment of use, after the UI explains the value; handle denied/restricted with a degraded path and a
  Settings link — never re-prompt in a loop or block the app. Prefer prompt-free pickers (`PhotosPicker`,
  `fileImporter`, `CLLocationButton`) when they cover the need.
- The authorization call lives behind a Core protocol (`CameraAuthorizing`) so view models are tested with a fake;
  only the concrete implementation imports the framework.
- Tracking (linking data with other companies' for ads) needs `ATTrackingManager.requestTrackingAuthorization()` —
  a plan-level decision, never added inside a feature task.

## Privacy manifest (`PrivacyInfo.xcprivacy`)

The app target (and every third-party SDK) ships a `PrivacyInfo.xcprivacy` declaring `NSPrivacyTracking`,
`NSPrivacyTrackingDomains`, `NSPrivacyCollectedDataTypes` and `NSPrivacyAccessedAPITypes`. A task that:

- **calls a required-reason API** — `UserDefaults` (`NSPrivacyAccessedAPICategoryUserDefaults`, e.g. `CA92.1` app-only,
  `1C8F.1` App Group), file timestamps (`…FileTimestamp`, e.g. `C617.1`), system boot time (`…SystemBootTime`), disk
  space (`…DiskSpace`), active keyboards (`…ActiveKeyboards`) — adds the category with a **true** approved reason code
  if it isn't already declared; an upload without it is rejected;
- **collects a new data type** (location, contact info, identifiers, …) adds an `NSPrivacyCollectedDataTypes` entry
  (linked/tracking/purposes);
- adds a third-party SDK checks it ships its own manifest.

Edit the existing manifest (resource of the app target in the generator manifest); create one only if the target has
none, and say so in the report.

## Widgets, Live Activities and App Intents (pointers)

- A widget/control is a separate **extension target** (WidgetKit, its own process and sandbox): its entry point is a
  `@main WidgetBundle`, its data comes from the **App Group** container (`com.apple.security.application-groups`
  on every sharing target), and it never calls into the app. A task adding the first widget adds a target to the
  generator manifest (`app-structure.md`) — a plan-level change; flag it if the plan didn't ask.
- Reload only the changed kind (`WidgetCenter.shared.reloadTimelines(ofKind:)`) on data change, not on a timer.
- App Intents (`AppIntent`, `AppEntity`, `AppShortcutsProvider`) are the one declaration that feeds Siri, Shortcuts,
  Spotlight, the Action button, widget buttons and controls. Intents shared with an extension sit in a folder with
  membership in both targets and go through the shared store; app-only intents (`openAppWhenRun`) go through the
  composition root's services. Their logic is a Core service, so intents stay thin.
- Deep links: universal links (`applinks:` associated domain) over custom URL schemes; handle with `.onOpenURL` /
  `scene(_:openURLContexts:)` and validate every parameter — a URL is untrusted input.

## iPadOS: windows, multitasking, pointer and keyboard

- **Design for any width**: iPad windows resize freely (iPadOS 26+) and `UIRequiresFullScreen` is deprecated — never
  add it, never assume full screen or a fixed orientation. Multiple windows need `UIApplicationSupportsMultipleScenes`
  = `YES` in the scene manifest; a per-item window is `WindowGroup(id:for:)` with an **identifier** value, opened with
  `openWindow(id:value:)` when `@Environment(\.supportsMultipleWindows)` is true. Each scene needs its own
  `.environment(store)` injection (scenes don't inherit).
- **Size classes, not devices**: switch structure on `@Environment(\.horizontalSizeClass)` (`.compact`/`.regular`) and
  finer layout on the proposed size (`ViewThatFits`, `GeometryReader` sparingly); never on
  `UIDevice.current.userInterfaceIdiom` or screen size. `NavigationSplitView` and `TabView` +
  `.tabViewStyle(.sidebarAdaptable)` (with `customizationID`s and `TabViewCustomization` in `@AppStorage`) already
  adapt. UIKit: `windowScene.sizeRestrictions`, and on 26+ `windowScene(_:didUpdateEffectiveGeometry:)` skipping work
  while `effectiveGeometry.isInteractivelyResizing`.
- **Menu bar and keyboard**: iPadOS 26+ shows a menu bar built from the scene's `.commands { CommandMenu/CommandGroup }`
  — the same declaration as the Mac; every frequent action gets `.keyboardShortcut(_:modifiers:)` (they appear in the
  ⌘-hold overlay on iPhone and iPad too). Don't steal standard shortcuts. UIKit: `UIKeyCommand` + `UIMainMenuSystem`
  (26+). Menu symbols are hidden by default in 27 — use `.labelStyle(.titleAndIcon)` only when the image identifies an
  object.
- **Pointer**: custom tappable views get `.hoverEffect(.highlight)`/`.lift` with `.contentShape(.hoverEffect, …)`;
  UIKit uses `UIPointerInteraction` (delegate held strongly by its owner). `pointerStyle(_:)` is macOS/visionOS only.
- Unsaved state on window close: iPadOS 27 `UISceneClosureConfirmation` behind `#available(iOS 27, *)` — or better,
  save as the user edits.

## Dynamic Type and accessibility

- Text uses text styles (`.font(.body)`, UIKit `preferredFont(forTextStyle:)` + `adjustsFontForContentSizeCategory`);
  sizes that should scale with text (icon frames, padding) use `@ScaledMetric(relativeTo:)` / `UIFontMetrics`. Never a
  fixed point size for text.
- At accessibility sizes, switch horizontal rows to vertical: `dynamicTypeSize.isAccessibilitySize` with `AnyLayout`
  (keeps identity) or `ViewThatFits`. Cap only views that cannot grow (`.dynamicTypeSize(...DynamicTypeSize.accessibility2)`
  + `.accessibilityShowsLargeContentViewer()`).
- Every control has a meaningful label (`Button("Add", systemImage: "plus")`, `.accessibilityLabel` for icon-only
  content), decorative images are `.accessibilityHidden(true)`, related elements are combined
  (`.accessibilityElement(children: .combine)`), custom controls declare traits/actions. Respect
  `accessibilityReduceMotion`, `accessibilityReduceTransparency`, `colorSchemeContrast`,
  `accessibilityDifferentiateWithoutColor`; semantic colors for Dark Mode.
- `accessibilityIdentifier` is for UI tests only — never a substitute for a label (`testing.md`).
- The UI tests check all of this automatically with `performAccessibilityAudit()` at the `.ui-quality.yml` level, and
  capture the largest text size (`large-text` variant) where cheap (`ui-implementation.md`).

## Secrets: the Keychain

- Tokens, passwords and keys go in the **Keychain** (`SecItemAdd`/`SecItemCopyMatching`/`SecItemUpdate`/`SecItemDelete`
  with `kSecClassGenericPassword`, handling `errSecSuccess`/`errSecItemNotFound`/`errSecDuplicateItem`) — never in
  `UserDefaults`/`@AppStorage`, files, `Info.plist`, build settings or source.
- Wrap it in one small `Sendable` Core type (`KeychainStore`, Security is UI-free) with typed throws and a protocol seam
  (`SecretStoring`) so tests use an in-memory fake; never hit the real Keychain from unit tests.
- Choose the most restrictive accessibility class the readers allow (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`;
  `…AfterFirstUnlockThisDeviceOnly` when background tasks/widgets need it while locked); never the deprecated
  `kSecAttrAccessibleAlways*`. Set `kSecUseDataProtectionKeychain = true` in code shared with macOS. Sharing with
  extensions uses an App Group/`keychain-access-groups` access group — an entitlement change to report.
- Biometrics (`LAContext`, `NSFaceIDUsageDescription`) protect data through `SecAccessControl`, not only a UI overlay.

## The main thread

UIKit and SwiftUI views, view controllers, view models and Core controllers are `@MainActor`; the watchdog kills an
app that blocks launch or the main thread for seconds. No synchronous network or large file I/O, `Thread.sleep`,
`DispatchSemaphore.wait()` or `DispatchQueue.main.sync` on it — do the work in an `actor`/`nonisolated async` Core
function and publish the result back. Work tied to a screen is `.task { }` (cancelled with the view) or a stored `Task`
cancelled in `viewWillDisappear(_:)`. Errors reach the user (`.alert`, `UIAlertController`), never vanish in `try?`.

## UI-test hooks

UI tests (`testing.md`) query by identifier: give every control a test touches an `.accessibilityIdentifier(_:)`
(SwiftUI) or `accessibilityIdentifier` (UIKit). The app honours a `--uitesting` launch argument
(`ProcessInfo.processInfo.arguments.contains("--uitesting")`) to start from an in-memory store, skip onboarding and
permission prompts, and disable animations — the branch lives in the composition root, never in Core logic. A
`--fixture <data-set>` argument (`minimal-data`, `typical-data`, `many-items`, …) selects fake repositories there for
the UI contract's screen tests and captures (`ui-implementation.md`).

## What must not be done

- Block the main thread, or do save/sync work only in a place a suspended process never reaches (save on
  `.background`/`sceneDidEnterBackground`).
- Request a permission without its usage-description key, at launch, or without a denied path.
- Call a required-reason API without a privacy-manifest entry, or add tracking/ATT inside a feature task.
- Secrets in `UserDefaults`/files/source; `kSecAttrAccessibleAlways`.
- Branch on device/idiom/screen size instead of size classes; set `UIRequiresFullScreen`; fixed font sizes.
- Background work through polling timers, the deprecated background-fetch API, unended `beginBackgroundTask`
  identifiers, or identifiers missing from `BGTaskSchedulerPermittedIdentifiers`.
- Import UIKit/SwiftUI/`BackgroundTasks` or call `UIApplication` from `Packages/Core` (`architecture.md`).
- Use Mac-only idioms on the iOS target (`NSWorkspace`, `pointerStyle`, `Settings` scene, `MenuBarExtra`) — guard
  shared code with `#if os(...)` per `swiftui.md`.

## Verification

`scenePhase` handling, `@UIApplicationDelegateAdaptor`, `@SceneStorage`, `.onOpenURL`, `.backgroundTask(.appRefresh)`/
`.backgroundTask(.urlSession)`, `BGTaskScheduler.register(forTaskWithIdentifier:using:launchHandler:)`/`submit`,
`BGAppRefreshTaskRequest`/`BGProcessingTaskRequest`, `beginBackgroundTask`/`endBackgroundTask`, a background
`URLSession` with a `Sendable` delegate, `UNUserNotificationCenter` authorization/settings/scheduling and a `@MainActor`
delegate with `nonisolated` async callbacks, remote-notification delegate methods, a typed-throws `KeychainStore` over
`SecItem*`, `WidgetCenter.reloadTimelines(ofKind:)`, an `AppIntent` + `AppShortcutsProvider`, `WindowGroup(id:for:)` +
`openWindow(id:value:)` + `supportsMultipleWindows`, `.commands` with `.keyboardShortcut`, `horizontalSizeClass`,
`dynamicTypeSize.isAccessibilitySize` with `AnyLayout`, `@ScaledMetric`, `hoverEffect`/`contentShape(.hoverEffect,_:)`
`accessibilityReduceMotion`/`ReduceTransparency`/`DifferentiateWithoutColor`/`colorSchemeContrast`, `TabView` with
`.sidebarAdaptable` + `customizationID` + `TabViewCustomization` in `@AppStorage`, `ViewThatFits`,
`accessibilityShowsLargeContentViewer`, `PhotosPicker`, `LocationButton`/`CLLocationButton`, `openSettingsURLString`,
`UNCalendarNotificationTrigger`, `SecAccessControlCreateWithFlags`, `LAContext`, `ATTrackingManager` and
`scene(_:openURLContexts:)` were type-checked with `xcrun -sdk iphonesimulator swiftc -typecheck -target
arm64-apple-ios18.0-simulator -swift-version 6` and `-ios26.0` against the iOS 27.1 SDK (Xcode 27.1);
`isInteractivelyResizing` is rejected below iOS 26, `UISceneClosureConfirmation` below 27, and
`.backgroundTask(.processingTask(_:))` compiles only in an iOS 27-available context. Usage-description and
privacy-manifest keys are `Info.plist`/property-list strings, not SDK symbols: `NSFaceIDUsageDescription`,
`NSLocation*UsageDescription`, `NSLocalNetworkUsageDescription`, `NSSpeechRecognitionUsageDescription`,
`NSHealth*UsageDescription` and `NSUserTrackingUsageDescription` appear in the SDK headers; the others are taken from
Apple's Information Property List reference. Runtime behavior (scheduling, suspension, prompts) was not exercised.
