# DocC

**All code is fully documented.** On every `public` and `package` type and member, without exception. A gate one
step later in the pipeline audits this (the doc-comment equivalent of `iru-java-javadoc`/`iru-android-dokka` for
Swift) — so writing it now is cheaper than fixing it after validation.

## What every declaration needs

- **A summary sentence**, on the line(s) immediately after `///` and before the first blank comment line — DocC
  shows this alone in a symbol's summary listing, so it must stand on its own. Say what the thing *is* or *does*,
  not what its name already says: `/// The meters.` on a `meters` property is noise; `/// The measured distance,
  in meters.` is documentation.
- **The contract a caller must satisfy** — accepted ranges, whether `nil` is meaningful, whether a returned
  collection can be empty, whether the member is safe to call concurrently or is actor-isolated.
- **`- Parameters:`** (or a single `- Parameter name:` for exactly one parameter) for every parameter, stating
  whether `nil` is a meaningful input when the type is optional.
- **`- Returns:`**, unless the member returns `Void`. Say what an empty collection or a `nil` optional means.
- **`- Throws:`** for every error case a caller can reasonably handle, with the condition that triggers it — not
  decoration: this is what `testing.md`'s tests are written against, so an undocumented precondition tends to
  become an untested one.

```swift
/// Validates a raw distance and returns a measurement.
///
/// - Parameter meters: The raw distance, in meters.
/// - Returns: A validated ``Measurement``.
/// - Throws: ``ValidationError/negativeValue(_:)`` if `meters` is negative.
public static func validate(meters: Double) throws(ValidationError) -> Measurement {
  ...
}
```

Order the fields `- Parameters:`, `- Returns:`, `- Throws:`, then any remaining callout (`- Note:`, `- Warning:`,
`- SeeAlso:`).

## Types

A type's DocC comment says what it's responsible for and, when it isn't obvious, how it's meant to be used —
constructed how, called in what order, safe to share across actors/threads or not (an `actor`'s doc comment can
state this in one sentence, since the compiler already enforces it — see `code-style.md`). For a `protocol`, the
doc comment *is* the contract every conformance must honor: state what a conforming type must guarantee
(ordering, idempotency, whether a method may return `nil`).

For an `open` member a subclass may override (see `package-api.md`), document what the override must preserve —
the invariant, the permitted return values, whether it must call `super`.

## Symbol links

Reference another documented symbol with double backticks rather than a plain code span, so DocC turns it into a
navigable link: ``Type``, ``Type/member``, ``Type/init(label:)`` for a specific initializer overload, or
` ``ModuleName/Type`` ` when disambiguating a symbol imported from another module. Use a plain single-backtick
code span (`` `meters` ``) only for a literal, a parameter name being discussed in prose, or an identifier that
genuinely isn't itself a documented symbol — a `` ``Symbol`` `` link to something outside the target's own DocC
catalog produces a broken-link warning the doc-audit gate will flag.

## Private and internal members

Document a `private`/`fileprivate` member whenever the *why* isn't obvious from the name — an invariant, or why
an obvious-looking simplification would be wrong. An `internal` member gets the same full treatment as `public`/
`package` per this file's opening rule, since it's still part of what another file in the module relies on, even
though `iru-swift-code-quality`'s doc audit is scoped to `public`/`package` by default. A `private` helper with a
self-describing name and three lines of body needs nothing.

## Markdown, callouts, and articles

- DocC comments are Markdown — lists, `` `code spans` ``, fenced code blocks, and emphasis all render; no
  Javadoc-style HTML escaping is needed for `<`/`>`/`&` inside prose.
- A code example longer than a line or two belongs in a fenced ` ```swift ` block inside the doc comment, or in a
  DocC **article** (a `.md` file inside the target's `.docc` catalog, e.g. `Sources/<Name>/<Name>.docc/
  GettingStarted.md`) when the walkthrough spans more than one symbol — add or extend an article only if the
  target already has a `.docc` catalog or the task explicitly asks for one.
- `> Note:`, `> Warning:`, `> Important:` blockquote callouts render as DocC's styled admonitions — reach for one
  when a caveat needs to stand out from the surrounding prose, not for routine parameter documentation that
  already has its own `- Parameters:` field.

## Mechanics that trip up a `docc` build

- Don't start a summary with "This function…"/"Returns a…" filler that pushes the real content past the first
  sentence — DocC (like Javadoc/KDoc) shows exactly that first sentence in a summary listing.
- `@available` (see `package-api.md`) documents a platform/version constraint on the declaration itself, not in
  prose — DocC renders it as a badge automatically; don't restate "iOS 17+" in the doc comment text once the
  attribute is present.
- A symbol referenced by a `` ``Link`` `` that DocC can't resolve (a typo, a symbol in a different, undeclared
  module) is a build warning, not a silent no-op — `swift package generate-documentation` surfaces it, and the
  catalog's workflows treat that gate as failing on warnings.
- Module- and catalog-level documentation lives in the target's own `<Name>.docc/<Name>.md` top-level article
  (the "technology root" page), not inside a Swift source file — extend it only if the target already has one or
  the task explicitly asks for module-level documentation.
