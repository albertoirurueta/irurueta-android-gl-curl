# App structure

Conventions specific to an Apple app project this catalog scaffolds (`iru-setup-apple-app`) — how targets,
`Packages/Core`, the generator manifest, and per-platform concerns fit together. Everything in `code-style.md`,
`docc.md`, `testing.md`, and `swiftui.md` still applies; this file adds what's specific to the multi-target app
shape itself.

## Per-platform targets, one shared logic package

An app project scaffolded by this catalog is thin, platform-specific UI targets (one per selected platform —
`iOS`, `macOS`, `watchOS`, ...) sitting on top of a single shared local Swift package, conventionally
`Packages/Core`, that holds every bit of testable logic — networking, persistence, business rules, view models
not tied to one platform's UI framework. A task belongs in `Packages/Core` (see `iru-swift-code-one-task` Step 1)
whenever the logic it adds has no platform-specific UI import and is meaningfully testable in isolation; it
belongs in a platform target only for the `View`/`App`-entry-point code that's genuinely specific to rendering on
that one platform.

- **`Packages/Core` is a normal local Swift package** (its own `Package.swift`, `Sources/`, `Tests/`), added to
  the app's generator manifest as a local package dependency — its own `public`/`package`/`internal` access
  control and `swiftLanguageModes` follow `code-style.md`/`package-api.md` exactly like a standalone library,
  even though it's never independently published.
- **A platform target imports `Packages/Core`** and builds the SwiftUI screens (`swiftui.md`) that consume it —
  it should have very little logic of its own to unit test; what it has is better covered by XCUITest (see
  `testing.md`) than by trying to unit-test view bodies directly.
- **watch companion**: a watchOS target added alongside an iOS target is wired as the iOS app's *companion*
  (`WKCompanionAppBundleIdentifier` in the watch target's own `Info.plist`, matching the iOS target's bundle id) —
  both still depend on the same `Packages/Core` for any logic they share, rather than duplicating it.

## Reading the generator manifest for `Info.plist` keys

Per-target `Info.plist` content is declared **in the generator manifest**, not hand-edited in a separate
`Info.plist` file, for a generated project:

- **XcodeGen (`project.yml`)** — a target's `info:` key supplies its own plist entries directly (`info: {path:
  Info.plist, properties: {...}}` to merge onto a base file, or `properties:` alone for a fully generated plist);
  shared keys common to every target go under the top-level `options: → mergedInfoPlist:` or a settings preset
  referenced by each target, so a task adding one new key only touches that key once rather than duplicating it
  per platform.
- **Tuist (`Project.swift`)** — a target's `infoPlist:` parameter takes `.extendingDefault(with: [...])` to merge
  keys onto Tuist's synthesized default plist, or `.file(path:)` to point at a hand-maintained file instead. Read
  which style the project already uses before adding a key — don't switch a target from one to the other as a
  side effect of an unrelated task.
- **A hand-maintained `.xcodeproj` with no generator** keeps `Info.plist` as an ordinary tracked file per
  target — edit it directly; there is no manifest to regenerate from.

## `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`

These two build settings are what `iru-swift-bump-version` rewrites for an app (see that skill's own
description) — `MARKETING_VERSION` is the human-facing version (`1.4.0`, shown in the App Store and Settings),
`CURRENT_PROJECT_VERSION` is the build number (an integer or dotted integer sequence, incremented every build
submitted to App Store Connect, independent of the marketing version). A task that isn't specifically about
versioning should not touch either — they're this catalog's `iru-swift-bump-version` skill's job — but should
know where they live so a task that genuinely needs to read the current version (e.g. surfacing it in a Settings
screen via `Bundle.main.infoDictionary`) knows this is build-setting data, not something to duplicate as a
hand-written constant in source.

## Entitlements

An app capability that requires an entitlement (push notifications, HealthKit, App Groups, iCloud) is declared in
a `.entitlements` file per target, referenced from the generator manifest (XcodeGen: a target's `entitlements:`
key; Tuist: `Target(..., entitlements: .file(path: ...))`) rather than hand-added to the `.xcodeproj`'s build
settings directly for a generated project — a task that adds a capability needing a new entitlement edits that
`.entitlements` file and the manifest's reference to it, and notes in the report that the corresponding capability
must also be enabled on the developer's App ID in the Apple Developer portal (something this skill cannot do and
should never claim to have done).

## Asset catalogs

Images, colors, and app icons live in an `.xcassets` catalog per target (`Assets.xcassets`, plus a separate
`AppIcon.appiconset`/`AccentColor.colorset` entry as needed) — reference an asset by its catalog name via a
generated `ImageResource`/`ColorResource` (`Image(.myAsset)`, `Color(.myAccentColor)`, Xcode's asset-catalog code
generation) in preference to a bare string literal (`Image("myAsset")`), since the generated accessor is a
compile-time check that the asset actually exists. A task adding a new image/color asset adds the corresponding
`.imageset`/`.colorset` folder with its `Contents.json`, not just a loose file dropped into the catalog's
top level.

## Cross-platform shims

Logic that's identical in shape but needs a different underlying type per platform (a color, a font, an image
type) belongs as a single cross-platform typealias/extension inside `Packages/Core` (or a small dedicated
`PlatformCompat` target), gated with `#if os(...)` at the declaration site (see `swiftui.md`), so every call site
elsewhere in the app writes ordinary, platform-agnostic code — never scatter the same `#if os(iOS)` /
`#if os(macOS)` pair across every screen that needs a platform color.
