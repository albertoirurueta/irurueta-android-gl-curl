# visionOS apps

Conventions specific to a **visionOS target** (native — "Apple Vision" in the target's supported destinations or a
`visionOS` platform in the generator manifest), SwiftUI-only. Everything in `code-style.md`, `docc.md`, `testing.md`,
`architecture.md` and `swiftui.md` still applies (MVVM/MV, logic in `Packages/Core`); this file adds windows, volumes,
immersive spaces, ornaments, hover effects and the basics of `RealityView`. Background tasks, notifications, the
Keychain, permission keys and the privacy manifest follow `ios.md`.

Every snippet shape below was type-checked against the visionOS 27.0 simulator SDK (`xrsimulator`, Xcode 27.1, Swift
6 language mode) with deployment targets visionOS 1.0, 2.0 and 26 — see "Verification".

## SwiftUI only

New visionOS UI is SwiftUI: scenes in the `@main App`, views, `RealityView`. UIKit exists on visionOS but only for the
interop cases in `uikit.md` (a representable, an existing iPad codebase running as a compatible app); never start a new
visionOS screen in UIKit, and never add an MVC passive-view controller for a visionOS screen.

## Scenes: windows, volumes, immersive spaces

| Scene | Use it for |
|---|---|
| `WindowGroup { … }` | A 2D window (glass pane) in the Shared Space — the main UI; `.defaultSize(width:height:)` suggests its first size, the user resizes and moves it. |
| `WindowGroup(id:) { … }.windowStyle(.volumetric).defaultSize(width:height:depth:in: .meters)` | A **volume**: bounded 3D content people can walk around, still in the Shared Space next to other apps. |
| `ImmersiveSpace(id:) { … }.immersionStyle(selection:in:)` | Content placed anywhere around the person in a **Full Space** (other apps hide); `.mixed` (room visible), `.progressive` (Digital Crown sets how much is replaced), `.full` (no passthrough). |

Rules:

- Scene ids are constants (`enum SceneID { static let globe = "globe" }`), shared by the scene and every
  `openWindow`/`openImmersiveSpace` call.
- Inject shared stores into **every** scene with `.environment(_:)` — scenes don't inherit. Several simultaneous scenes
  need `UIApplicationSupportsMultipleScenes` = `YES`.
- **Start with the least immersive scene** that does the job (window → volume → mixed space → full) and let the user
  step up; never open an immersive space unprompted at launch.
- In a file that imports RealityKit, `Scene` is ambiguous (`SwiftUI.Scene` vs `RealityKit.Scene`): write
  `var body: some SwiftUI.Scene`, or keep the `App` file free of `import RealityKit`.

## Opening and dismissing immersive spaces

Use the async environment actions, and handle **every** result — only one immersive space can be open across all apps,
so opening can fail:

```swift
@Environment(\.openImmersiveSpace) private var openImmersiveSpace
@Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace

private func toggle() async {
  if isOpen {
    await dismissImmersiveSpace()
    isOpen = false
    return
  }
  switch await openImmersiveSpace(id: SceneID.space) {
  case .opened: isOpen = true
  case .userCancelled, .error: isOpen = false
  @unknown default: isOpen = false
  }
}
```

The user can leave with the Digital Crown at any time; state that must track the space exactly is set from the space's
own view (`onAppear`/`onDisappear`), not only from the button. Windows are opened with `openWindow(id:)`; the app can't
move or resize a window after it appears.

## Ornaments, glass and hover effects

- An **ornament** is a small control bar attached outside a window: `.ornament(attachmentAnchor: .scene(.bottom)) {
  Bar().padding(12).glassBackgroundEffect() }` — a few frequent actions only; the rest stays in the toolbar/content.
  Apply `.environment(store)` after `.ornament` so the ornament sees it.
- Custom glass is `glassBackgroundEffect()`; `glassEffect(_:in:)` is **unavailable** on visionOS — guard shared code.
- **Gaze privacy**: the app never learns where people look, only where they tap. Custom tappable views get
  `.hoverEffect(.highlight)` (or `.lift`) with `.contentShape(.hoverEffect, .rect(cornerRadius:))` so the system draws
  the highlight; no gaze-driven tooltips or prefetching. Keep targets ≥ 60 pt apart (or 16 pt margins).

## RealityKit basics

- `Model3D(named:bundle:) { model in model.resizable().scaledToFit() } placeholder: { ProgressView() }` for a static
  model shown like an image (`.frame(depth:)` reserves depth).
- `RealityView { content in … } update: { content in … }` for code-built or interactive content: create entities once in
  `make`, push SwiftUI state into them in `update`. `RealityView { content, attachments in … } update: { _, _ in }
  attachments: { Attachment(id:) { SwiftUIView() } }` embeds SwiftUI next to entities.
- An entity that reacts to input needs `InputTargetComponent` **and** `CollisionComponent` (plus
  `HoverEffectComponent` for the highlight); gestures target it with `SpatialTapGesture().targetedToAnyEntity()` (the
  value carries `.entity`). Forward the hit to the view model — no feature logic in the closure.
- ECS components/systems, ARKit providers (`ARKitSession`; Full Space only, with `NSWorldSensingUsageDescription`/
  `NSHandsTrackingUsageDescription`) and Reality Composer Pro assets are beyond this file — follow the project's
  existing code and Apple's documentation.

## What's unavailable, and multiplatform guards

Native visionOS has no `UIScreen` (no `UIScreen.main`), no `UIDevice.orientation`, no ActivityKit/Live Activities, and
no `glassEffect`; camera capture is restricted (the compiler rejects `AVCaptureDevice.default(for:)` below visionOS 2.1)
— offer the photo library instead. A shared target guards visionOS-only code (`ornament`, volumes, `ImmersiveSpace`,
`RealityView` attachments, `Model3D`) with `#if os(visionOS)` and iOS-only code with `#if os(iOS)`, at the narrowest
scope (`swiftui.md`). Size layouts from the window/proposed size, never the screen.

## Testing

View models and Core logic are tested exactly as on other platforms (Swift Testing, `testing.md`). The visionOS
simulator runs windows, volumes and immersive spaces and XCUITest; ARKit data and real hand input need a device —
report those paths "not verified on simulator" rather than writing tests that can't run. Windows and their
components follow the UI contract like any SwiftUI target (`ui-implementation.md`: `ImageRenderer` component
captures, XCUITest window captures with `XCUIDevice.shared.appearance`, `performAccessibilityAudit()`), with the
`visionos` variant; immersive-space content (RealityKit entities) isn't captured by either — report it uncovered.

## What must not be done

- UIKit for a new visionOS screen, or an immersive space opened without handling `.error`/`.userCancelled`.
- Opening a Full Space or `.full` immersion unprompted; surprising the user with sudden immersion.
- Logic in `RealityView` closures or gesture handlers instead of a view model/Core.
- `UIScreen`, device orientation, ActivityKit or `glassEffect` in code compiled for visionOS.

## Verification

`WindowGroup` with `.ornament(attachmentAnchor:)`, `.windowStyle(.volumetric)` + `defaultSize(width:height:depth:in:)`,
`ImmersiveSpace` + `immersionStyle(selection:in:)` (`.mixed`, `.progressive`, `.full`), `openImmersiveSpace` result
handling, `dismissImmersiveSpace`, `glassBackgroundEffect()`, `hoverEffect`/`contentShape(.hoverEffect,_:)`,
`RealityView` (make/update and the attachments form), `ModelEntity`, `InputTargetComponent`, `CollisionComponent`,
`HoverEffectComponent`, `SpatialTapGesture().targetedToAnyEntity()` and `Model3D` were type-checked with `xcrun -sdk
xrsimulator swiftc -typecheck -target arm64-apple-xros2.0-simulator -swift-version 6` (also `xros1.0` and `xros26.0`)
against the visionOS 27.0 SDK of Xcode 27.1. The `Scene` ambiguity with RealityKit imported, and the unavailability of
`UIScreen`, `UIDevice.orientation`, `glassEffect` and the `ActivityKit` module, are compiler results from the same SDK.
