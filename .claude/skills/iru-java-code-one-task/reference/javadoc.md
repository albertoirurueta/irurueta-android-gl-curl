# Javadoc

**All code is fully documented.** On every type, method, constructor, and field — public, protected,
package-private, and private alike, in test sources as much as in production sources (`mandatory-rules.md`). The
`iru-java-javadoc` skill audits this one gate later, in the task group, and a Maven build configured with
`-Xdoclint:all` fails on a missing `@param` — so writing it now is cheaper than fixing it after validation.

## What every declaration needs

- **A summary sentence.** The first sentence is what appears in the summary table, so it must stand alone and
  end with a period. Say what the thing *is* or *does*, not what its name already says: `/** The order id. */`
  on `getOrderId()` is noise; `/** Identifier assigned by the carrier when the shipment is booked. */` is
  documentation.
- **The contract a caller must satisfy** — accepted ranges, whether `null` is allowed, whether a returned
  collection is modifiable, whether the method is thread-safe, whether it may block.
- **`@param` for every parameter**, including every type parameter (`@param <T>`) and — on a record's own
  Javadoc — every record component. State whether `null` is permitted.
- **`@return`**, unless the method returns `void`. Say what "empty" or "absent" means when the type is a
  collection or an `Optional`.
- **`@throws` for every exception a caller can reasonably handle**, with the condition that triggers it. This is
  not decoration: the `@throws` list is what the tests in `testing.md` are written against, so an undocumented
  precondition tends to become an untested one.

Order the block tags `@param`, `@return`, `@throws`, then the rest (`@see`, `@since`, `@deprecated`).

## Types

A class's Javadoc says what the type is responsible for and, when it isn't obvious, how it's meant to be used —
constructed how, called in what order, safe to share between threads or not. For an interface, the Javadoc *is*
the contract every implementation must honour: state what an implementation must guarantee (ordering,
idempotency, whether it may return `null`, what it does when nothing is found). Those interfaces are the
codebase's real documentation.

For a non-final method that subclasses may override (see `code-style.md`), document what an override must
preserve — the invariant, the permitted return values, whether it must call `super`. A subclass author cannot
honour a contract that was never written down.

## Overrides and inherited docs

An override that changes nothing about the contract may use `{@inheritDoc}` — or omit the Javadoc entirely and
let the tool inherit it. Write fresh Javadoc whenever the override narrows the contract, strengthens a
guarantee, throws something the supertype doesn't, or has a performance characteristic worth knowing.

## Private members and test sources

Private members get Javadoc too, with the same `@param`/`@return`/`@throws` tags. Make it earn its place: say
*why* the member exists, why an invariant holds, or why an obvious-looking simplification is wrong — not a
restatement of what the next line does.

In test sources, the class Javadoc names the type under test, a test method's Javadoc states the scenario and
the expected outcome in one sentence, and fixtures, constants, and helpers are documented like any other member.

## Mechanics that trip up a build

- `{@code ...}` for identifiers, literals, `null`, `true`/`false`. Bare `<`, `>`, and `&` in prose must be
  escaped or wrapped in `{@code}`.
- `{@link Type#member}` for a cross-reference the reader might want to follow; `{@code}` when you're just naming
  something. A `{@link}` to a type outside the project's Javadoc link set produces a warning.
- Don't start a summary with "This method…" or "Returns a…"-style filler that pushes the real content past the
  first sentence.
- `@since` on newly public API only if the project already uses it; don't introduce the tag in one file.
- `@deprecated` (the tag) and `@Deprecated` (the annotation) go together, and the tag must say what to use
  instead.
- Package-level docs live in `package-info.java`. Add one only if the package already has them or the task asks.
