# Code style

This catalog's general Kotlin/Android code agreements. They apply to both the `lib` (library) module and the
`app` (sample/application) module of an Android project this catalog scaffolds.

Above all: **match the surrounding code.** These are the defaults for new code. If the file you are editing
consistently does something else, follow it and note the divergence in the report — don't restyle a file as a
side effect of an unrelated task.

## Kotlin official code style

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) ("Kotlin official
style", 4-space indentation, no tabs, one top-level declaration's worth of blank-line spacing between members,
`{` on the same line as the declaration). Formatting itself is a `ktlint`/Spotless concern when the project has
opted into it (`reference/README.md`'s Sources); this file is about the substantive conventions a formatter can't
enforce.

## `val` over `var`

Declare every property and local with `val` unless it is genuinely reassigned. `val` is Kotlin's equivalent of
Java's `final` default: it documents that a reference doesn't change, and it turns accidental reassignment into a
compile error.

```kotlin
val orientation = OrientationHelper.getCameraDisplayOrientation(context, cameraId)
val listeners = mutableListOf<SurfaceTextureListener>()   // the *reference* is val; the list itself may still mutate
```

A `var` is warranted for a property whose value genuinely changes over the object's lifetime (a cached computed
value, a piece of mutable UI/render state) — see `GLTextureView.renderMode`, `GLTextureView.debugFlags` in the
reference sample, both `var` because they're meant to be reassigned after construction. Don't default to `var`
"in case it's needed later."

## Null-safety: no `!!`

The non-null assertion operator (`!!`) converts a recoverable `null` into an unhandled `NullPointerException` at
the single worst place to encounter one — kernel exceptions have no message telling the caller which expression
was null. Use, in order of preference:

- **A safe call chain** (`?.`) when absence should just propagate: `view.surfaceTextureListener?.let { ... }`.
- **The Elvis operator** (`?:`) to substitute a default or `return`/`throw` early: `val ctx = context
  ?: return`, or `val target = target ?: throw IllegalStateException("renderer has already been set")`.
- **A smart-cast guard**: `if (renderer == null) return` followed by unconditional use of `renderer` below (only
  works on a `val`, or a `var` the compiler can prove isn't reassigned by another thread).
- **`requireNotNull(x) { "message" }`** / **`checkNotNull(x) { "message" }`** at a boundary where a `null` is a
  genuine precondition/invariant violation the caller should see as `IllegalArgumentException`/
  `IllegalStateException` with a clear message — this is the idiomatic Kotlin equivalent of Java's
  `Objects.requireNonNull`.

`!!` is acceptable only where the compiler cannot see an invariant a human has already verified a few lines
above (rare, and worth a one-line comment explaining why). A pre-existing `!!` outside the task's scope is not
this task's problem to fix.

## Visibility: `internal` by default in a library module

Kotlin's `internal` is visible module-wide but invisible to another Gradle module (and to `lib`'s consumers) —
use it, not `public`, for anything the `lib` module's own callers use across files but that isn't part of the
published API:

- **`private`**: visible only within the file (top-level) or the class. The default for anything a single
  file/class owns.
- **`internal`**: the default for anything shared across files within the `lib` module that consumers of the
  library must never see — a helper class, a mutable implementation detail, a constant used by two internal
  classes. Widening a declaration to `public` is an API decision with binary-compatibility consequences (see
  `library-api.md`); don't make it casually while implementing an unrelated task.
- **`public`**: only for the module's actual public API surface — what `iru-android-dokka` audits and what
  `library-api.md` governs.

In the `app` module nothing is published, so this hierarchy collapses: default (package-`internal` in Kotlin's
sense, i.e. `public` in the absence of an explicit modifier, but treated as private-to-the-app in practice) or
explicit `private` is the norm, and there is rarely a reason to write `public` at all.

## Data classes, sealed classes, sealed interfaces

- **`data class`** for anything that is purely a carrier of values — value objects, results, UI state holders.
  It gets `equals`/`hashCode`/`copy`/`toString`/`componentN` for free; don't hand-write those.
- **A `data class`'s constructor properties are its contract.** Validate in an `init` block so a constructed
  instance is always valid, throwing `IllegalArgumentException` naming the offending parameter — the same
  fail-fast discipline as a Java compact record constructor.
- **`sealed class`/`sealed interface`** for a closed hierarchy the caller is meant to exhaustively `when` over —
  a UI state (`Loading`/`Content`/`Error`), a parsed result, a rendering mode. The compiler enforces
  exhaustiveness on a `when` used as an expression, which is the whole point: adding a new case anywhere in the
  `when` chain becomes a compile error until every branch is updated.
- **`object`** for a true singleton with no per-instance state — a stateless utility holder like
  `OrientationHelper` in the reference sample, or a sealed hierarchy's parameterless case.

## Extension functions

An extension function is appropriate for a small, self-contained operation on a type you don't own (an Android
framework type, a type from another module) that reads naturally as `receiver.operation()`. It is *not* a place
to smuggle business logic that belongs on the type you do own, and it should not need access to anything private
— if it does, it belongs as a member instead. Keep an extension's name and behavior obvious from the call site;
an extension that silently does something surprising (blocking I/O behind what looks like a getter) is worse than
a member function doing the same thing, because nothing at the call site hints that it isn't free.

## Coroutines and `Flow`, briefly

- **`suspend fun`** for anything that does asynchronous work — never a callback-based API for new code unless the
  surrounding file is entirely callback-based already.
- **Never `GlobalScope`.** Launch from a scope tied to a lifecycle (`viewModelScope`, `lifecycleScope`) or one
  injected into the class under test, so cancellation actually propagates and tests can control it.
- **Inject the `CoroutineDispatcher`** (or a `CoroutineContext`) into a class that launches coroutines rather than
  hardcoding `Dispatchers.IO`/`Dispatchers.Main` — this is what lets `testing.md`'s tests run it on a test
  dispatcher instead of a real thread pool.
- **`Flow`** for a stream of values over time (sensor readings, repeated queries); a single asynchronous result is
  a plain `suspend fun`, not a `Flow` that emits once. Prefer `StateFlow`/`SharedFlow` over a hand-rolled
  observer/listener list for anything with more than one subscriber.
- Structured concurrency: a coroutine launched inside a function should be `await`ed/joined before the function
  returns, or explicitly scoped to something with a well-defined lifetime — never fired and silently forgotten.

## Expression bodies and named arguments

- **Expression body** (`fun square(x: Int) = x * x`) for a function whose entire body is one expression —
  it's shorter and it makes a `return`-vs-not-return decision non-existent. Switch to a block body the moment the
  function needs more than the one expression.
- **Named arguments** at a call site with more than two parameters, two or more of the same type in a row (easy
  to transpose silently), or any `Boolean` parameter (`setEGLConfigChooser(needDepth = true)`, never a bare
  `true`) — a positional `Boolean` argument is unreadable at the call site and a classic source of swapped-order
  bugs.

## Member ordering

Kotlin idiom departs from this catalog's Java convention in one place: the **companion object goes last**, after
the instance methods, not first among "static" members — see `GLTextureView`'s companion object, placed after
every instance method and before the nested `interface`/inner class declarations. Otherwise, order a class body
top to bottom:

1. **Properties** — constructor (primary-constructor) properties are declared in the header; body-declared
   properties come first in the body, `public` before `internal` before `private`, with `const val`
   companion-object constants living inside the companion object itself, not the instance body.
2. **`init` blocks**, immediately after the properties they initialize.
3. **Secondary constructors**, if any.
4. **Functions** — `public` before `internal` before `private`, overrides grouped with the function they
   override rather than scattered by visibility.
5. **Companion object**, last in the primary body.
6. **Nested/inner types** (`class`, `interface`, `enum class`, `sealed` cases as nested classes) — after the
   companion object, in the same public-before-private order.

Two rules cut across the sequence, matching the reference sample:

- **Overloads stay together**, at the position their most visible member would occupy — `GLTextureView`'s several
  `setEGLConfigChooser` overloads sit as one contiguous group.
- **A private helper does not follow the function it serves.** `OrientationHelper`'s private helpers, where it
  has any, belong with the other private members, not immediately after their sole caller.

Applying this to an existing file: insert new members in their correct band, don't reorder a file that already
violates the convention (say so in the report instead), and keep a moved member's relocation visible in the diff
rather than folding it into an unrelated change.

## Naming

- Classes, objects, interfaces: `UpperCamelCase` nouns. No `I` prefix on an interface.
- Functions: `lowerCamelCase` verbs; `is`/`has` for a `Boolean`-returning function or property.
- Properties and parameters: `lowerCamelCase`, meaningful names, no single letters except a lambda parameter or
  loop index whose scope is one line.
- Constants (`const val`, top-level or in a companion object): `SCREAMING_SNAKE_CASE`, e.g.
  `RENDER_MODE_CONTINUOUSLY`.
- Type parameters: a single capital letter (`T`, `K`, `V`, `R`) unless several would be indistinguishable.
- Tests: follow the existing convention in the same package (see `testing.md`) — don't introduce a second one.

## Exceptions and error handling

- **`require(condition) { "message" }`** for an invalid argument — it throws `IllegalArgumentException` with the
  lazily-evaluated message, and it reads better at the call site than a hand-written `if`/`throw`.
- **`check(condition) { "message" }`** when the object is in the wrong state for the call, not the argument —
  throws `IllegalStateException`. `GLTextureView.checkRenderThreadState()` in the reference sample is the
  hand-written equivalent; prefer `check()` for new code.
- **Fail fast, at the boundary.** Validate in an `init` block or at function entry, not deep inside a call chain.
- **Don't catch and swallow.** An empty `catch (e: Exception) {}` — or one that only logs — turns a failure into
  silent wrong behavior. Handle it meaningfully, translate it (preserving the original as the cause), or let it
  propagate. `catch (_: InterruptedException) { }` is the one well-understood exception: re-interrupting the
  thread or falling through to an already-planned exit is a deliberate, documented choice, not swallowing.
- **Never catch `Throwable`**, and don't catch an exception you only intend to rethrow unchanged.
- **`android.util.Log`, never `println`/`System.out`** — and never log a secret, token, or credential.

## Collections

- Return `List`/`Map`/`Set` (the read-only interfaces), never `MutableList`/`MutableMap`/`MutableSet`, from a
  function whose result the caller shouldn't mutate. Kotlin's `List` not being deeply immutable (the underlying
  object can still be a `MutableList` upcast) is a known limitation — don't rely on it for security, only for
  signaling intent; return `List.copyOf`-style defensive copies where a caller genuinely must not observe later
  mutation.
- `listOf`/`mapOf`/`setOf`/`emptyList()` for fixed collections, not `arrayListOf` unless Java interop specifically
  needs an `ArrayList`.
- Program to the interface (`List`, `Map`, `Set`) in properties, parameters, and return types; the concrete
  implementation is an internal choice.

## Other conventions

- **Constructor injection over field mutation** — a dependency arrives through the (primary) constructor and is
  stored in a `private val`/`internal val`, making the class testable without a container.
- **No new runtime dependency** unless the task explicitly calls for it. If one seems unavoidable, that's a
  blocker to report, not a decision to make silently.
- **Don't leave dead code, commented-out code, or `TODO`s** behind for work the task itself covers.
- **`@Suppress` needs a reason.** If a warning is suppressed (`@Suppress("DEPRECATION")` as in the reference
  sample's `GLTextureView`), the suppression should be narrowly scoped (the declaration, not the file) and the
  reason should be obvious from context or a short comment — not a blanket file-level suppression to silence
  unrelated noise.
- **Use the standard library** before writing a helper: `Comparator`, `sequences`, `Regex`, `String.format`, the
  `kotlin.time` types. Don't add a utility object that duplicates one of them.
