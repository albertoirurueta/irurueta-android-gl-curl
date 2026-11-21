# KDoc

**All code is fully documented.** On every public and `internal` class, object, interface, function, and
property, without exception. `iru-android-dokka` audits this one gate later, in the task group, against the same
bar this file describes — so writing it now is cheaper than fixing it after validation.

## What every declaration needs

- **A summary sentence.** The first sentence/paragraph (up to the first blank line) is what Dokka shows in a
  summary listing, so it must stand alone. Say what the thing *is* or *does*, not what its name already says:
  `/** The camera id. */` on `cameraId` is noise; `/** Identifier of the camera sensor whose orientation is
  being resolved. */` is documentation.
- **The contract a caller must satisfy** — accepted ranges, whether a nullable parameter's `null` is meaningful,
  whether a returned list is empty rather than `null`, whether the function is safe to call from a background
  thread, whether it blocks.
- **`@param`** for every function/constructor parameter, including every type parameter (`@param T`). State
  whether `null` is a meaningful input when the type is nullable.
- **`@property`** for every primary-constructor `val`/`var` that's also documented on the class's own KDoc block
  (Kotlin's convention: constructor-property docs go in the class's KDoc, tagged `@property <name>`, not as a
  separate block on the parameter itself).
- **`@return`**, unless the function returns `Unit`. Say what "empty" means when the type is a collection, and
  what `null` means when the type is nullable.
- **`@throws`** for every exception a caller can reasonably handle, with the condition that triggers it — this is
  not decoration: the `@throws` list is what `testing.md`'s tests are written against, so an undocumented
  precondition tends to become an untested one.
- **`@sample`** for a function whose usage isn't obvious from its signature alone — points at a fully-compiled
  function elsewhere in the source tree (commonly under a `samples`/test source set) whose body Dokka inlines
  verbatim as the example, which keeps the example from silently rotting out of sync with the real API the way a
  hand-written code block in a comment can. Only worth adding where a plain description leaves the caller
  guessing at call order or setup (see `GLTextureView.setEGLContextClientVersion`'s worked example in the
  reference sample, written as a fenced code block there since the sample predates `@sample` sources — prefer
  `@sample` for new code when a real compiled sample function exists to point at).
- **`@see`** to cross-reference a related declaration the reader would want next — `GLTextureView.debugFlags`
  points at `DEBUG_CHECK_GL_ERROR`/`DEBUG_LOG_GL_CALLS`, its two legal flag values, exactly this way.

Order the block tags `@param`, `@property`, `@return`, `@throws`, then the rest (`@see`, `@sample`, `@since`,
`@suppress`).

## Types

A class's or object's KDoc says what it's responsible for and, when it isn't obvious, how it's meant to be used —
constructed how, called in what order, safe to share across threads or not. `OrientationHelper` in the reference
sample states its purpose and links to `OpenGlToCameraHelper` with `@see` in its very first paragraph — that's
the level of orientation a type-level doc owes the next reader. For an interface, the KDoc *is* the contract
every implementation must honor: state what an implementation must guarantee (ordering, idempotency, whether it
may return `null`), exactly as `GLTextureView.EGLConfigChooser`/`EGLContextFactory` document what a client
implementing them must do.

For a non-`final` function a subclass may override, document what the override must preserve — the invariant, the
permitted return values, whether it must call `super`.

## Overrides and inherited docs

An override that changes nothing about the contract needs no fresh KDoc — Dokka inherits it from the supertype.
Write fresh KDoc whenever the override narrows the contract, strengthens a guarantee, throws something the
supertype doesn't, or has a performance characteristic worth knowing.

## Private members

Document a private declaration whenever the *why* isn't obvious from the name — see how thoroughly the reference
sample documents even `private` fields inside `GLThread` (`hasSurface`, `waitingForSurface`,
`shouldReleaseEglContext`, …): each one-line KDoc explains what the flag *means*, because the surrounding
state-machine logic is otherwise opaque. A `private` helper with a self-describing name and three lines of body
needs nothing.

## Mechanics that trip up a Dokka build

- KDoc supports Markdown inside the comment body — use it for lists, `code spans`, and fenced blocks; don't
  hand-escape `<`/`>`/`&` the way Javadoc requires.
- **`[Identifier]`** for a link — `[TextureView]`, `[setRenderer]`, `[GLWrapper.wrap]` — resolves to the linked
  declaration in the generated site; a link to something outside the project's own Dokka source sets produces a
  broken-link warning `iru-android-dokka` will flag.
- Don't start a summary with "This function…" or "Returns a…" filler that pushes the real content past the first
  sentence.
- **`@Deprecated`** (the annotation, with its `message` and optional `replaceWith = ReplaceWith(...)`) carries the
  deprecation notice itself in Kotlin — KDoc doesn't need a separate `@deprecated` tag the way Javadoc does; see
  `library-api.md` for the annotation's own conventions.
- **Module- and package-level docs** live in separate `module.md`/`package.md` files wired into
  `dokkaSourceSets { includes.from(...) }` in the module's `build.gradle.kts` — not inside a Kotlin source file.
  Add or extend one only if the module/package already has one, or the task explicitly asks for module/package
  documentation; `iru-android-dokka` is what audits and backfills these more broadly.
