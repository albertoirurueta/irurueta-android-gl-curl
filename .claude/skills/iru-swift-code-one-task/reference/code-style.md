# Code style

This catalog's general Swift 6 code agreements. They apply to a Swift Package target and to an Apple app target
alike.

Above all: **match the surrounding code.** These are the defaults for new code. If the file you are editing
consistently does something else, follow it and note the divergence in the report — don't restyle a file as a
side effect of an unrelated task.

All examples in this file follow the toolchain's own `swift-format` default (2-space indentation, confirmed
against a real Swift 6.4 toolchain via `swift format dump-configuration`) — if the project ships a `.swift-format`
config with a different `indentation` value, match that file instead.

## Strict concurrency is not optional

A package built with `swiftLanguageModes: [.v6]` (or an app target with `SWIFT_STRICT_CONCURRENCY = complete`)
turns every data-race the Swift 6 concurrency model can prove into a **compile error**, not a warning. New code
must build clean under it — this was verified end to end (actor, `@MainActor` class, `Sendable` struct, typed
throws, all built with `swift build --build-tests` under `// swift-tools-version: 6.0` and
`swiftLanguageModes: [.v6]`) as part of authoring this reference.

- **`Sendable`** on every value type that crosses an actor/task boundary. A `struct`/`enum` whose stored
  properties are all `Sendable` gets an implicit conformance for free; declare it explicitly (`struct
  Measurement: Sendable`) on a package's `public` types so the promise is visible in the type's own declaration,
  not just inferred.
- **`actor`** for a reference type that owns mutable state accessed from more than one place concurrently — the
  compiler serializes every access to the actor's stored properties, so no hand-written lock is needed:

  ```swift
  public actor Counter {

    private var value = 0

    public init() {}

    @discardableResult
    public func increment() -> Int {
      value += 1
      return value
    }
  }
  ```

  Calling an actor's method from outside the actor is implicitly `async` (`await counter.increment()`) — that
  `await` is the compiler's proof the access is properly serialized, not ceremony to remove.
- **`@MainActor`** on a reference type whose state backs UI — a SwiftUI view model, anything that must only ever
  be touched from the main thread:

  ```swift
  @MainActor
  public final class CounterViewModel {

    public private(set) var displayValue = 0

    private let counter: Counter

    public init(counter: Counter) {
      self.counter = counter
    }

    public func increment() async {
      displayValue = await counter.increment()
    }
  }
  ```

  Prefer `@MainActor` on the whole type over sprinkling it on individual members — a view model is either
  entirely main-actor-confined or it isn't.
- **Structured concurrency**: launch child work with `async let` or a `TaskGroup`, both of which are
  automatically awaited/cancelled with their enclosing scope; reach for a detached `Task { }` only when the work
  must genuinely outlive the calling scope (fire callbacks from a synchronous context, kick off app-lifetime
  work), and even then prefer a `Task` tied to a `@MainActor` or actor context over `Task.detached`, since
  `Task.detached` inherits neither the caller's actor isolation nor its task-local values.
- **`nonisolated`** on an actor/`@MainActor` type's member that genuinely doesn't touch isolated state (a pure
  computation, a `Sendable` constant) — it lets a caller invoke that one member synchronously without `await`,
  and it documents that the member was deliberately excluded from the type's isolation, not merely forgotten.
- **Avoid `@unchecked Sendable`.** It disables the compiler's proof entirely and hands the invariant to a comment
  that can silently go stale. Prefer, in order: make the type immutable and genuinely `Sendable`; wrap the
  mutable state in an `actor`; use `@MainActor`. Reach for `@unchecked Sendable` only when wrapping a type this
  package doesn't own (a C/Objective-C type, a pre-Swift-6 dependency) that the surrounding code has already
  established is safe by construction, with a comment saying why — never as a quick fix for a diagnostic the task
  ran out of time to actually resolve, and never on a type this task itself introduces.
- **A closure crossing an isolation boundary must capture only `Sendable` values.** The compiler catches this at
  the call site — a real diagnostic observed while verifying this reference: `passing closure as a 'sending'
  parameter risks causing data races between code in the current isolation context and concurrent execution of
  the closure`, with a note naming the captured non-`Sendable` value. Fix it at the source (make the captured
  type `Sendable`, or move the work inside an actor) rather than working around the diagnostic.

## Value types first

Prefer `struct`/`enum` over `class` for anything that is a value, not an identity — a model, a result, a
coordinate pair, a configuration. A value type is copied on assignment (no shared mutable state to reason about
across concurrency domains), gets synthesized `Equatable`/`Hashable` conformance for free when every member is
itself conformant, and is `Sendable` by default when every stored property is. Reach for a `class` only when the
thing genuinely has identity and mutable shared state (a view controller, a cache, something that must be
observed by reference) — and when it does, isolate it (`actor`/`@MainActor`) per the strict-concurrency section
above rather than leaving it a plain, un-isolated reference type.

## `guard` for early exits

Use `guard` to state a precondition and exit immediately when it fails, keeping the function's main body at the
top level of indentation instead of nested inside an `if`:

```swift
func validate(meters: Double) throws(ValidationError) -> Measurement {
  guard meters >= 0 else {
    throw ValidationError.negativeValue(meters)
  }
  return Measurement(meters: meters)
}
```

Reserve plain `if` for a genuine two-way branch where both arms do meaningful work; a `guard` whose `else` falls
through to unrelated logic instead of `return`/`throw`/`break`/`continue` defeats the point.

## Access control: narrow by default

- **`internal`** (the implicit default — most declarations need no modifier) for anything a module's own files
  share but that isn't meant to be seen from outside the module. This is the right default for the large majority
  of a package's or app target's code.
- **`public`** only for a library package's actual, deliberately published API — what a consumer imports the
  package to call. Every `public` declaration is a promise `reference/package-api.md` governs; don't widen a
  declaration to `public` casually while implementing an unrelated task.
- **`package`** for something shared across module boundaries *within* the same package/repository (e.g. between
  `Packages/Core`'s several targets, or between an app's own local packages) but that must never leak to an
  external consumer of a published library — the access level Swift 5.9 added specifically to replace the older
  trick of marking something `public` just so a sibling module could see it.
- **`private`**/**`fileprivate`** for anything a single type or file owns. Prefer `private` (scoped to the
  declaration, including extensions in the same file) over `fileprivate` (scoped to the whole file) unless the
  member genuinely needs to be shared between a type and an extension of it declared elsewhere in the same file.
- Never widen a member's access level just to make it directly testable — see `testing.md` for `@testable
  import`, which reaches `internal` members from a test target without touching production access control.

## Error types: `enum: Error`, typed throws

- **Model a package or module's error cases as an `enum` conforming to `Error`** (and usually `Equatable`, so
  tests can assert on the exact case), one case per distinct failure a caller might need to branch on:

  ```swift
  public enum ValidationError: Error, Equatable {
    case negativeValue(Double)
  }
  ```

- **Typed throws** (`func validate(meters: Double) throws(ValidationError) -> Measurement`) when a function's
  callers benefit from knowing the exact error type at the call site without an `as?` cast — this compiles and
  works exactly as shown above on Swift 6.4/Swift 6 language mode, verified as part of authoring this reference.
  Reach for the untyped `throws` (implicitly `throws(any Error)`) when a function genuinely propagates
  heterogeneous errors from more than one source (e.g. it forwards whatever a generic `Decodable` collaborator
  throws) — forcing a typed-throws signature there just erases the caller's ability to `catch` the real
  underlying type.
- **Fail fast, at the boundary** — validate in an initializer or at function entry with `guard`, so an invalid
  value never propagates far enough to fail somewhere confusing.
- **Don't catch and swallow.** An empty `catch { }` — or one that only logs — turns a failure into silent wrong
  behavior. Either handle it meaningfully, wrap it in a more specific error (keeping the original as context), or
  let it propagate.
- **`Result<Success, Failure>`** is for a value that's handed around or stored before being examined (a cached
  outcome, a callback-based API's parameter) — not a substitute for `throws` in an already-synchronous or
  `async` call chain, where `throws`/typed throws reads more directly.

## Naming (Swift API Design Guidelines)

- **Clarity at the point of use** over brevity — `remove(at: index)` reads as a sentence; a name that only makes
  sense next to its declaration is a smell.
- Types and protocols: `UpperCamelCase`. A protocol describing *what a thing is* reads as a noun (`Collection`);
  a protocol describing *a capability* reads as an `-able`/`-ing` adjective (`Equatable`, `ProgressReporting`).
- Functions, methods, properties, cases: `lowerCamelCase`. A method with no side effects and returning a value
  reads as a noun phrase (`x.distance(to: y)`); a method with a side effect reads as an imperative verb
  (`x.sort()`, `x.append(y)`) — its mutating/non-mutating counterpart, if both exist, differs by exactly the verb
  vs. the participle (`sort()`/`sorted()`).
- **Boolean properties and methods read as assertions**: `isEmpty`, `hasSuffix(_:)`, never a bare noun.
- Omit a parameter label that would be redundant with the base name and the argument's role
  (`move(to:)`/`move(from:to:)`, not `move(toPoint:)`); keep a label whenever its absence would make the call
  site read ambiguously.
- Don't abbreviate — spell out `error` not `err`, `index` not `idx`, unless the abbreviation is already the
  domain's own vocabulary (`URL`, `id`).

## Member ordering and `// MARK:`

Order a type's body top to bottom, grouped by `// MARK:` and, within a group, by access level from most to least
visible:

1. **Nested/associated types**, if any come first structurally (a `public` `enum`/`struct` the type itself
   defines as part of its API).
2. **Stored properties** — `public`, then `package`, then `internal`, then `private`/`fileprivate`. Computed
   properties sit with the stored properties they logically relate to, not in a separate band.
3. **Initializers** — same visibility order.
4. **Methods** — same visibility order, with protocol-conformance methods grouped under their own `// MARK: -
   <ProtocolName>` rather than interleaved with the type's own API.

```swift
public struct Measurement: Sendable, Equatable {

  // MARK: Properties

  public let meters: Double

  // MARK: Initializers

  public init(meters: Double) {
    self.meters = meters
  }
}
```

Two rules cut across the sequence, matching this catalog's Java/Kotlin conventions:

- **Overloads stay together**, at the position their most visible member would occupy.
- **A private helper does not follow the method it serves.** It goes with the other private members, not
  immediately after its sole caller — the public API has to be readable without scrolling past implementation
  details.

Applying this to an existing file: insert new members in their correct group, don't reorder a file that already
violates the convention (say so in the report instead), and keep a moved member's relocation visible in the diff
rather than folding it into an unrelated change.

## Collections and optionals

- Return an empty collection, never `nil`, from a function whose result type is a collection.
- `Optional` for a genuinely absent value; don't reach for a sentinel (`-1`, `""`) instead.
- Prefer `if let`/`guard let` shorthand (`if let value { ... }`, binding to the same name) over the older `if let
  value = value { ... }` spelling.
- `map`/`compactMap`/`flatMap`/`filter`/`reduce` over a hand-written loop for a simple transformation; a loop
  with early `return`/`break`/`continue` or side effects on every iteration is clearer as a `for` loop than
  forced into a functional chain.

## Other conventions

- **Initializer injection over property mutation** — a dependency arrives through `init` and is stored `let`
  wherever the type's mutability (or actor isolation) allows it.
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report, not a decision to make silently.
- **Don't leave dead code, commented-out code, or `TODO`s** behind for work the task itself covers.
- **`final` on a `class` by default**, unless it's genuinely designed for subclassing (an open base class in a
  published library needs `open`, not just the absence of `final` — see `package-api.md`).
- **No `print`/`NSLog`** in library or production app code — use the project's own logger (commonly `os.Logger`)
  where one exists, and never log a secret, token, or credential.
- **Use the standard library** before writing a helper: `Comparable`, `Sequence`/`Collection` algorithms,
  `String` interpolation, `Codable`. Don't add a utility type that duplicates one of them.
