# macOS desktop apps

Conventions specific to a **macOS-only desktop app** (the layout `iru-setup-macos-app` scaffolds, or any XcodeGen/
Tuist/hand-maintained project whose only platform is macOS) and to the macOS target of a multiplatform app. Everything
in `code-style.md`, `docc.md`, `testing.md` and `architecture.md` still applies; `swiftui.md` applies to SwiftUI
views, `appkit.md` to AppKit controllers and views. This file adds what only the Mac has: scenes and windows, the menu
bar, Settings, menu bar extras, the App Sandbox, the hardened runtime and the Apple-silicon baseline.

Every API named below was type-checked against the macOS 27 SDK (Xcode 27.1, Swift 6 language mode) with a
deployment target of macOS 15 — see the "Verification" note at the end.

## Availability: read the deployment target first

Read `MACOSX_DEPLOYMENT_TARGET` (XcodeGen `project.yml` `settings`/`deploymentTarget`, Tuist `deploymentTargets`,
or the `.xcodeproj` build settings) before using any API below. Minimum macOS versions of the scene APIs:

| API | Minimum macOS |
|---|---|
| `Window`, `MenuBarExtra`, `openWindow`/`dismissWindow` | 13 |
| `SettingsLink`, `openSettings` | 14 |
| `NSHostingMenu` | 14.4 |
| `UtilityWindow`, `defaultWindowPlacement(_:)`, `windowIdealPlacement(_:)`, `windowLevel(_:)`, `WindowDragGesture`, `restorationBehavior(_:)`, `defaultLaunchBehavior(_:)` | 15 |
| AppKit Observation tracking on by default (opt-in with `NSObservationTrackingEnabled` on 15) | 26 |
| `NSControl.Events` `.valueChanged`/`.primaryActionTriggered`/`.menuActionTriggered`/`.trackingRepeated` | 27 (see `appkit.md`) |

Anything newer than the deployment target goes behind `if #available(macOS NN, *)` with a working fallback — never
raise the deployment target as a side effect of a task; that is a plan-level decision.

## Scenes: pick the right type

The `@main struct <AppName>App: App` declares every window the app can show. Choose the scene type by how many
windows it creates:

| Scene | Use it for |
|---|---|
| `WindowGroup { … }` | The main window; any number of independent windows (File > New Window). |
| `WindowGroup(id:for:) { $value in … }` | One window per value (a document/item window). The value type is `Codable & Hashable` and should be an **identifier** (`Item.ID`), never the model itself — the window restores the value across launches. |
| `Window("Title", id:)` | Exactly one window (a library browser, an activity log); listed in the Window menu; reopening brings it forward. |
| `UtilityWindow("Title", id:)` | A floating inspector panel that follows the focused main window's focused values and hides when the app is inactive. |
| `Settings { … }` | The app's settings window; gives the standard *App* > *Settings…* item and ⌘, automatically. |
| `MenuBarExtra("Title", systemImage:) { … }` | An item in the system menu bar; `.menuBarExtraStyle(.menu)` (menu items only) or `.window` (arbitrary SwiftUI content). |

Rules:

- **Window ids are constants**, not string literals scattered across call sites: one `enum WindowID { static let
  inspector = "inspector" }` (or similar) in the app target, used by the scene and by every `openWindow(id:)`.
- **Inject shared services/stores into every scene** with `.environment(_:)` — scenes don't inherit from each
  other, so a store injected only on the main `WindowGroup` is missing in `Settings` and `MenuBarExtra` and a
  non-optional `@Environment(Type.self)` read crashes there.
- **`@NSApplicationDelegateAdaptor`** is the only place for app-level AppKit delegate behavior in a SwiftUI app
  (e.g. `applicationShouldTerminateAfterLastWindowClosed(_:)`, a Dock menu). Don't create a second `NSApplication`
  delegate or set `NSApp.delegate` by hand.
- A menu-bar-only utility (its only scene is a `MenuBarExtra`) sets `LSUIElement` = `YES` in the target's
  `Info.plist` (through the generator manifest — `app-structure.md`); a regular app with an optional extra keeps its
  Dock icon. Insert/remove an extra with `MenuBarExtra(…, isInserted: $binding)`, not by `if` around the scene.
- After `openWindow` from a menu bar extra, call `NSApplication.shared.activate()` so the window doesn't open behind
  the frontmost app.

## Opening, closing, sizing and restoring windows

- Open with `@Environment(\.openWindow)` → `openWindow(id:)` / `openWindow(id:value:)`; close with
  `@Environment(\.dismissWindow)` → `dismissWindow()` (the current window) or `dismissWindow(id:)`. Never reach for
  `NSApp.windows` to find a SwiftUI window by title.
- **Size**: `.defaultSize(width:height:)` is the size of a *new* window only; `.windowResizability(.contentSize)`
  (content min/max frames bound the window) or `.contentMinSize` (content sets only the minimum) on the scene; the
  content declares its limits with `.frame(minWidth:idealWidth:maxWidth:minHeight:maxHeight:)`. Don't hard-code a
  fixed window size the user can't change unless the task asks for it.
- **Placement** (macOS 15+): `.defaultWindowPlacement { content, context in … }` returning a `WindowPlacement` built
  from `context.defaultDisplay.visibleRect` (clear of the menu bar and Dock); `.windowIdealPlacement` for the zoom
  (Option-click green button) frame.
- **Style/level**: `.windowStyle(.hiddenTitleBar)`, `.windowToolbarStyle(.unified)`/`.unifiedCompact`,
  `.windowLevel(.floating)` — `.floating` only for small reference panels, never for document windows. A window
  without a title bar keeps a `navigationTitle` (VoiceOver and the Window menu still read it) and gets
  `WindowDragGesture()` so it stays draggable.
- **Restoration**: macOS restores windows, their presented value and `@SceneStorage` by default. Opt a scene out
  with `.restorationBehavior(.disabled)` when reopening it at launch is noise (a selection-dependent inspector);
  `.defaultLaunchBehavior(.suppressed)` keeps a scene closed at launch unless restored. Restore **UI state**
  (`@SceneStorage` selection, tab) — never data; data comes from the model/store.

## Menus, commands and keyboard shortcuts

The menu bar is the Mac's primary command surface: every user action is reachable from it, frequent ones have a
shortcut. In SwiftUI:

- Put commands in a dedicated `struct <Feature>Commands: Commands` attached with `.commands { … }` on the main
  `WindowGroup` — not inline in the `App` body.
- **Standard placements first**: `CommandGroup(replacing: .newItem)`, `CommandGroup(after: .importExport)`,
  `CommandGroup(before: .windowList)`, `CommandGroup(replacing: .help)` (`CommandGroupPlacement` names every
  standard group: `.newItem`, `.saveItem`, `.importExport`, `.printItem`, `.pasteboard`, `.textEditing`, `.sidebar`,
  `.toolbar`, `.windowList`, `.help`, …). A new top-level menu is `CommandMenu("Name") { … }` only for a genuinely
  app-specific group. Reuse the system's `SidebarCommands()`/`InspectorCommands()` instead of re-implementing them.
- **Shortcuts**: `.keyboardShortcut("e", modifiers: [.command, .shift])`. Don't steal a standard shortcut (⌘Q, ⌘W,
  ⌘, , ⌘C/⌘V/⌘X/⌘Z, ⌘M, ⌘H) for something else; check the existing `Commands` types for collisions before adding one.
- **Commands act on the focused window through focused values**: declare `extension FocusedValues { @Entry var
  selectedItem: Item? }`, publish with `.focusedSceneValue(\.selectedItem, item)` (enabled whatever has focus in the
  window) or `.focusedValue(\.selectedItem, item)` (only while focus is inside that view), read with
  `@FocusedValue(\.selectedItem)` in the `Commands` type.
- **Disable, don't hide**: a command that can't run now stays visible with `.disabled(selectedItem == nil)`.
- macOS 27 hides symbol images in menus unless a `Label` uses `.labelStyle(.titleAndIcon)` — don't rely on icons
  to convey a menu item's meaning.

An AppKit app builds the same menu bar as an `NSMenu` in code — see `appkit.md`.

## Settings

- One `Settings { SettingsView() }` scene. Multiple panes are a `TabView { Tab("General", systemImage:
  "gearshape") { Form { … }.formStyle(.grouped) } … }` with a fixed `.frame(width:height:)` — the Mac settings
  convention. Never a custom "Preferences" window or menu item.
- Values are `@AppStorage("key")` (simple preferences) in the view; logic that reads a preference lives in Core
  behind a protocol or receives the value as a parameter — Core never imports SwiftUI to read `@AppStorage`.
- Open settings from inside the app with `SettingsLink { Text("Settings…") }` (a button) or
  `@Environment(\.openSettings)` → `openSettings()` (from another action). Never send the private
  `showSettingsWindow:` selector from SwiftUI code (an AppKit-only app's own menu item is the one place it appears —
  see `appkit.md`).

## Files, the App Sandbox and security-scoped bookmarks

The scaffold sandboxes every build (`ENABLE_APP_SANDBOX`/`com.apple.security.app-sandbox` = `true` in
`<AppName>.entitlements`) — required for the Mac App Store and kept for Developer ID builds too. Code must work
**sandboxed**:

- The app can read and write only its container plus what the user picks. Locations come from
  `FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create:
  true)` (or `.cachesDirectory`, `URL.temporaryDirectory`) — which resolve inside the container — never from a
  hard-coded `~/Documents`, `/Users/…`, `NSHomeDirectory() + "/Desktop"` or `/tmp` path.
- **User-picked files**: SwiftUI `.fileImporter(isPresented:allowedContentTypes:onCompletion:)` /
  `.fileExporter(…)`, or AppKit `NSOpenPanel`/`NSSavePanel` with `allowedContentTypes` (`UTType`, never the
  deprecated `allowedFileTypes`) presented with `begin { response in … }` or `beginSheetModal(for:)` — not
  `runModal()` from async code. Access needs `com.apple.security.files.user-selected.read-only` or
  `.read-write` in the entitlements file; it lasts for the session only.
- **Remembering a user-picked file or folder across launches** needs a security-scoped bookmark: create it with
  `url.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)` while
  access is active; later `URL(resolvingBookmarkData:options: .withSecurityScope, relativeTo: nil,
  bookmarkDataIsStale: &isStale)`, then `guard url.startAccessingSecurityScopedResource() else { throw … }`,
  `defer { url.stopAccessingSecurityScopedResource() }`, re-create the bookmark when `isStale`, use the file. Every
  `start` is balanced by a `stop` (the kernel limits open sandbox extensions). Wrap the protocol in one small Core
  type (Foundation only), with a protocol seam so tests use a fake.
- Network access needs `com.apple.security.network.client` (outgoing) / `.network.server` (listening); camera,
  microphone, location, contacts… need their sandbox entitlement **and** the `Info.plist` usage-description string.
  Add only what the task needs, edit the `.entitlements` file + manifest reference (`app-structure.md`), and say in
  the report that a restricted capability must also be enabled on the App ID — never claim to have done that.
- Never disable the sandbox, add `com.apple.security.temporary-exception.*` entitlements, or shell out
  (`Process`/`NSTask`) to reach outside the container to make a task work — report it as a blocker instead.

## Hardened runtime and signing constraints

`ENABLE_HARDENED_RUNTIME = YES` is required for notarization (Developer ID). Under it: no writable-and-executable
memory, no loading libraries signed by another team, no `DYLD_*` variables, no debugger attach in Release, and
camera/microphone/location/contacts/calendars/photos/Apple Events need a resource-access entitlement. So:

- Don't add runtime-exception entitlements (`com.apple.security.cs.allow-jit`,
  `…allow-unsigned-executable-memory`, `…allow-dyld-environment-variables`, `…disable-library-validation`,
  `…disable-executable-page-protection`) unless the task explicitly calls for one; name it in the report.
- Don't load plug-ins/bundles from outside the app bundle, download and execute code, or embed an unsigned helper
  tool — helpers and frameworks go inside the bundle and are signed with the app.
- Don't touch signing settings (`CODE_SIGN_IDENTITY`, `DEVELOPMENT_TEAM`, `ExportOptions-*.plist`,
  `com.apple.security.get-task-allow`) in a feature task — that is the workflows/release skills' job.
- Some capabilities (Sign in with Apple, In-App Purchase, Game Center, WeatherKit) exist only for Mac App Store
  builds, not Developer ID. A task adding one to a repository whose distribution is Developer ID is a blocker to
  report.

## Apple silicon only

macOS 26 *Tahoe* is the last release for Intel Macs; macOS 27 runs only on Apple silicon, and Rosetta stops running
general Intel-only apps after macOS 27.

- While the deployment target is **below 27**, keep the Release build universal: leave `ARCHS` at the standard
  architectures and don't add `EXCLUDED_ARCHS = x86_64`/`ARCHS = arm64` as part of a feature task.
- Don't add an Intel-only binary dependency (`.framework`/`.xcframework`/`.dylib` without an `arm64` slice), and
  don't write `#if arch(x86_64)`-only code paths for new features.
- Once the deployment target is 27+, `x86_64` can go — a plan-level decision, not a side effect.

## The main thread

AppKit and SwiftUI views, view controllers, view models and Core controllers are `@MainActor`. Never block it:

- No synchronous network calls, no large synchronous file reads/writes, no `Thread.sleep`, no
  `DispatchSemaphore.wait()`/`DispatchQueue.main.sync`, no busy-waiting on the main actor. Do the work in an
  `actor` or a `nonisolated async` function in Core, `await` it from the `@MainActor` caller, and publish the result
  back to `@MainActor` state.
- Long-running work started from a window is tied to that window: SwiftUI `.task { … }` (cancelled when the view
  goes away) or a stored `Task` cancelled in `viewWillDisappear()`/`windowWillClose(_:)` for AppKit.
- Errors reach the user (`.alert`, `NSAlert(error:)` as a sheet with `beginSheetModal(for:)`), never vanish in a
  `try?`.

## UI-test hooks

UI tests (`testing.md`) query by identifier: give every control a test touches an `.accessibilityIdentifier(_:)`
(SwiftUI) or `setAccessibilityIdentifier(_:)` (AppKit). The app honors a `--uitesting` launch argument
(`ProcessInfo.processInfo.arguments.contains("--uitesting")`) to start from an in-memory/empty store and skip
first-run sheets — add that branch in the composition root, never inside Core logic. A `--fixture <data-set>`
argument selects fake repositories there for the UI contract's screen tests and captures; right-to-left and dark
captures relaunch with `-AppleLanguages (ar) -AppleTextDirection YES -NSForceRightToLeftWritingDirection YES` and
`-AppleInterfaceStyle Dark` (`ui-implementation.md`).

## What must not be done

- Block the main thread (see above), or use `runModal()` for panels/alerts from async code.
- Assume an unsandboxed file system: hard-coded home/Desktop/Documents paths, reading a user file without a panel or
  a resolved bookmark, unbalanced `startAccessingSecurityScopedResource()`.
- Disable the sandbox/hardened runtime, or add temporary-exception/runtime-exception entitlements unasked.
- Hide menu commands instead of disabling them, steal standard shortcuts, or add a custom Preferences window.
- Put AppKit/SwiftUI imports, `@AppStorage`, `NSOpenPanel` or `NSWorkspace` calls into `Packages/Core`
  (`architecture.md`).
- Store a model value (instead of its identifier) as a `WindowGroup(for:)` presented value.
- Use iOS-only idioms on the Mac target (`UIApplication`, `UIKit` imports, `.navigationBarTitleDisplayMode`) —
  guard shared code with `#if os(macOS)` per `swiftui.md`.

## Verification

The scene, command, focused-value, Settings/`SettingsLink`/`openSettings`, `MenuBarExtra` (`.menu`/`.window`,
`isInserted:`), `UtilityWindow`, placement/level/style/restoration modifiers, `@NSApplicationDelegateAdaptor`,
`fileImporter`, `NSOpenPanel`/`NSSavePanel` (`allowedContentTypes`, `begin`, `beginSheetModal`), security-scoped
bookmark, `NSHostingMenu`, `NSAlert(error:)`, `WindowDragGesture`, `toolbar(removing:)` and `@SceneStorage` code
shapes above were compiled with `xcrun swiftc -typecheck -target arm64-apple-macos15 -swift-version 6` against the
macOS 27 SDK of Xcode 27.1, in a throwaway directory outside this repository. Runtime behavior (window restoration,
sandbox denials, Rosetta) was not exercised; it follows the cited documentation.
