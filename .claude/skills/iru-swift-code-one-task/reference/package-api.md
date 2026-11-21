# Package API and semver stability

Conventions specific to a change in a library package's `public` surface — what makes a Swift package safe to
depend on from another package (including this catalog's own `iru-swift-bump-version`, which tags a release
without rewriting an in-tree version field) across a version bump without breaking existing consumers. Read this
alongside `docc.md` when the changed declaration is also documented as part of the release notes.

## `public` is a promise

Every `public` declaration in a package's target is part of that target's published API the moment a version tag
ships with it — a consumer's code can call it, and nothing in `swift build` warns before a `public` signature
changes underneath them. Before widening a declaration from `internal`/`package` to `public` (see
`code-style.md`'s access-control section), ask: does a consumer of this package actually need to call this, or is
it something the package needs across its own files/targets? Only the first case justifies `public`. Once
something is `public`, treat these as breaking changes needing a major version bump (or, at minimum, a deliberate
decision recorded in the task's report, not a silent side effect):

- Removing a `public` declaration, or narrowing its access level.
- Removing or renaming a `public` function's parameter, changing its type, or removing a parameter label a caller
  relies on.
- Changing a `public` function's or property's return/declared type to something not assignable to the old one.
- Adding a new required member to a `public` `protocol` with no default implementation — existing conformances
  won't compile against it.
- Marking a previously `open` class `final`, or a previously `open` member non-overridable — an existing
  subclass/override stops compiling.

Adding a new `public` member, a new default-valued parameter, or a new protocol extension method with a default
implementation is additive and safe.

## `open` vs. `public` for classes

`public` alone lets another module use and instantiate a class, but not subclass it or override its members
outside the declaring module; **`open`** is required for either. Default a `class` to `public final` (see
`code-style.md`) — reach for `open` only when the package deliberately designs the type for subclassing by
consumers, and document (per `docc.md`) exactly what an override must preserve. Widening `public` to `open` later
is additive; narrowing `open` back to `public`/`final` is a breaking change to a consumer with an existing
subclass.

## `@available` for platform and version constraints

Annotate a `public` declaration that depends on a platform API newer than the package's minimum deployment target
with `@available`, naming every platform the package supports:

```swift
@available(iOS 17, macOS 14, *)
public func observeChanges() -> AsyncStream<Change> {
  ...
}
```

- List every platform the package's `Package.swift` `platforms:` array declares — an omitted platform is treated
  as available at the package's minimum, which fails to compile for a consumer targeting that platform if the
  API genuinely isn't there.
- The trailing `*` covers any platform not explicitly listed (including one added to the package later) at "no
  constraint" — required syntax, not a stylistic choice.
- Prefer gating a *smaller* declaration (one function, one overload) over annotating a whole type `@available`
  when only part of the type needs the newer platform — an `@available` type forces every consumer to guard the
  entire type, not just the member that needs it.
- A call site using an `@available`-gated API needs its own `if #available(iOS 17, *) { ... } else { ... }`
  guard (or the whole caller must itself already require iOS 17+) — the compiler enforces this at the call site,
  not just at the declaration.

## `Package.swift` products and targets hygiene

- **One `.library` product per consumer-facing module** the package intends people to `import` directly; a
  target with no corresponding product is an internal implementation detail, not part of the package's API
  surface even if some of its declarations are technically `public` (mark them `package` instead — see
  `code-style.md`).
- **`type: .automatic`** (the default, omit the parameter) for a library product unless the package specifically
  needs `.static`/`.dynamic` linking — don't force one without a reason the task states.
- **Target dependencies flow one direction**: a lower-level target (pure data/logic) is depended on by a
  higher-level one (a target with UI or I/O concerns), never the reverse — adding a dependency edge that creates
  a cycle is a `Package.swift` error SwiftPM catches at resolution time, and adding one that merely points the
  wrong direction architecturally is a design smell worth flagging even though it compiles.
- **Test targets** (`.testTarget`) depend on the target they test via `dependencies: ["<Target>"]` and use
  `@testable import` (see `testing.md`) rather than depending on the target's own product name unless the tests
  are deliberately exercising the public API only, from outside the module.

## `swiftLanguageModes`

`Package.swift`'s top-level `swiftLanguageModes: [.v6]` (or a per-target override via `swiftSettings:
[.swiftLanguageMode(.v6)]`) opts a target into Swift 6's full data-race-safety checking at compile time — verified
end to end while authoring this reference (`// swift-tools-version: 6.0` plus `swiftLanguageModes: [.v6]`
compiles an actor, a `@MainActor` class, and a `Sendable` struct cleanly with `swift build --build-tests`). A
package migrating gradually can instead list `[.v5, .v6]` per target to let some targets opt in ahead of others —
new code in a target already on `.v6` follows every rule in `code-style.md`'s strict-concurrency section without
exception; a target still on `.v5` gets the same conventions applied best-effort, since it will need to compile
clean under `.v6` eventually.

## `@_spi` and `@inlinable`

- **`@_spi(GroupName)`** marks a `public` declaration visible only to a consumer that explicitly imports it via
  `@_spi(GroupName) import ModuleName` — a narrow, explicitly-unstable escape hatch for sharing something between
  this package and one specific, cooperating consumer (often another package in the same organization) without
  committing to it as fully public, stable API. Don't reach for `@_spi` as a general substitute for `package`
  access (see `code-style.md`) — `package` is the right tool when the consumer is inside the same package;
  `@_spi` is for a consumer in a genuinely separate package that the maintainers have coordinated with directly.
- **`@inlinable`** on a `public` function lets the optimizer inline it into a consumer's own compiled binary
  across module boundaries — but it also freezes the function's *implementation* as part of the API contract:
  changing an `@inlinable` function's body can silently break already-compiled consumer binaries that inlined the
  old version. Use it only where a measured performance need justifies it, never by default on ordinary `public`
  API, and never on a function whose body reaches into `@usableFromInline`/`internal` implementation details that
  aren't equally ready to be frozen.

## `Package.resolved` policy

For a **library package**, `Package.resolved` is typically **not committed** — a library should resolve to
whatever compatible versions its own consumer's `Package.resolved` picks, and committing one only pins the
library's own local development/CI environment, which can mask a real compatibility problem a consumer would
hit. For an **app project**'s own root package manifest (where the app *is* the final consumer, not a library
other packages depend on), `Package.resolved` **is committed**, so every build — local and CI — resolves the
exact same dependency graph. State which case applies in the report if the task adds or changes a package
dependency.
