# KDoc (and Swift doc comments)

**All public and internal API is documented** — every class, interface, object, function, property, constructor
property, and `expect` declaration in the KMP module(s) and `androidApp`. `iru-kmp-dokka` audits this later in the
task group against the same bar and runs the Dokka build; writing it now is cheaper than fixing it after validation.

## What every declaration needs

- **A summary sentence** that stands alone (Dokka shows it in listings) and says what the thing *is* or *does* beyond
  its name.
- **The contract**: accepted ranges, what `null` means, whether a list may be empty, main-safety (`suspend`
  functions: "main-safe; switches to the IO dispatcher internally"), thread confinement, whether a `Flow` is hot or
  cold and whether it completes.
- **`@param`** for every parameter and type parameter; **`@property`** for primary-constructor properties (in the
  class's KDoc); **`@return`** unless `Unit`; **`@throws`** for every exception a caller can handle, matching the
  `@Throws` annotation on Swift-facing functions exactly; then `@see`, `@sample`, `@since`, `@suppress`.
- **`[Identifier]` links** to related declarations — resolved across source sets that Dokka documents.
- Markdown in the body; no HTML escaping.

## Multiplatform specifics

- **Document the `expect` declaration.** It is the contract every `actual` honors, and it's what callers in
  `commonMain` read. Dokka merges `expect`/`actual` into one page with per-platform tabs.
- An **`actual`** needs its own KDoc only when its platform behavior differs in a way callers must know (e.g.
  "On iOS, values are stored in the Keychain with `kSecAttrAccessibleAfterFirstUnlock`") — otherwise leave it
  undocumented rather than duplicating the `expect` doc.
- **Interfaces with per-platform implementations**: the interface KDoc states what every implementation must
  guarantee; each implementation documents only its platform-specific mechanism and limits.
- **Swift-facing API**: say in the KDoc how Swift should call it when it isn't obvious (e.g. "From Swift, observe
  via `watchState(onEach:)` and cancel the returned handle"). The Objective-C header carries KDoc into Xcode's quick
  help, so this documentation is what iOS developers actually see.
- **Composables**: document parameters (including `modifier` — "applied to the root layout"), the state they
  expect, and the events they emit; previews are `private` and need no KDoc.
- `module.md`/`package.md` includes wired through Dokka's `includes.from(...)` hold module/package docs — extend them
  only if the module already has them or the task asks; `iru-kmp-dokka` backfills more broadly.

## Overrides and private members

- An override that doesn't change the contract inherits its doc; write fresh KDoc when it narrows/strengthens the
  contract or throws something new.
- Private members: a one-line KDoc when the *why* isn't obvious from the name.

## Dokka pitfalls

- A `[Link]` to a declaration in a source set Dokka doesn't document (e.g. an `androidMain`-only type from
  `commonMain` docs) produces an unresolved-link warning — link from the right source set's doc, or use a code span.
- Don't start with "This function…"/"Returns a…" filler.
- `@Deprecated(message, replaceWith = ReplaceWith(...))` carries deprecation — no separate `@deprecated` tag.

## Swift doc comments in `iosApp`

Public and internal Swift types and members carry `///` comments in DocC style: a summary line, then
`- Parameters:`/`- Parameter name:`, `- Returns:`, `- Throws:` as applicable. Keep them short — the iOS host app is a
thin shell over the shared module, and most API documentation belongs in KDoc.
