# Code style

This catalog's Kotlin code agreements for a Kotlin Multiplatform app — they apply to every Kotlin source set of the
shared module(s) and to `androidApp`. Swift in `iosApp` follows `ios-interop.md`'s "Swift in iosApp" section.

Above all: **match the surrounding code.** These are the defaults for new code; `mandatory-rules.md` is the only
thing that overrides a file's own consistent convention.

## Kotlin official code style

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) — `kotlin.code.style=official`
in `gradle.properties`, 4-space indentation, `{` on the declaration line, trailing commas in multi-line parameter and
argument lists. Formatting is enforced by ktlint (`org.jlleitschuh.gradle.ktlint`) when the scaffold wired it; this
file covers what a formatter can't.

## File naming in a multiplatform module

- A file holding one class is named after it: `SessionRepository.kt`.
- A file of top-level declarations is named for what it holds: `Strings.kt`, `PlatformLogging.kt`.
- **`actual` files carry the platform suffix**: the `expect` in `commonMain/.../Platform.kt`, its actuals in
  `androidMain/.../Platform.android.kt` and `iosMain/.../Platform.ios.kt` (the convention the Kotlin docs and the
  JetBrains wizard use). Two files with the same name and package in different source sets of the same compilation
  produce a duplicate JVM class name (`PlatformKt`) — the suffix avoids it.
- Same package across source sets: an `actual` lives in exactly the package of its `expect`.

## Visibility and explicit API mode

- **`private`** for anything a single file/class owns; **`internal`** for anything shared across files of one module
  that other modules (including `androidApp` and, through the framework, Swift) must not see; **`public`** only for
  what another module or Swift actually calls.
- In the shared module of an **app**, "public" means "visible to `androidApp` and to Swift". Everything public in a
  framework-exported module ends up in the Objective-C header (`Shared.h`) and bloats it — prefer `internal` for
  anything `iosApp`/`androidApp` doesn't call. `internal` declarations are not exported to Swift.
- **Explicit API mode** (`kotlin { explicitApi() }` in the module's build script) makes the compiler require an
  explicit visibility modifier and an explicit return type on every public declaration. If the module has it on,
  write both on every new public declaration — never turn the mode off or downgrade it to `explicitApiWarning()` to
  get a task through. If it's off, still write explicit return types on public functions whose type isn't obvious
  from a single-expression body.

## `val` over `var`; immutable state

`val` unless genuinely reassigned. State exposed to UI is an immutable `data class` held in a
`private val _state = MutableStateFlow(...)` and exposed as `val state: StateFlow<UiState> = _state.asStateFlow()`.
Update with `_state.update { it.copy(...) }` (atomic) rather than read-modify-write on `.value`.

## Null safety

No `!!` (see `mandatory-rules.md`). Prefer `?.`/`?:`/smart casts; `requireNotNull`/`checkNotNull` with a message at
boundaries. Values arriving from Swift through a nullable Objective-C type, or from JSON, are validated where they
enter, not deep inside.

## Data classes, sealed types, value classes

- **`data class`** for value carriers (UI state, DTOs, domain values). Validate invariants in `init` with `require`.
- **`sealed interface`/`sealed class`** for closed hierarchies the caller handles exhaustively (`UiState.Loading /
  Content / Error`, results). In Kotlin a `when` expression over them is checked for exhaustiveness. In Swift it isn't
  (without SKIE) — see `ios-interop.md` before making a sealed type part of the Swift-facing API.
- **`@JvmInline value class`** for a typed wrapper around one value (`UserId(val raw: String)`). `@JvmInline` is
  required on the JVM and accepted in common code. Note value classes are exported to Swift as their underlying type —
  the type safety exists only on the Kotlin side.
- **`enum class`** for a fixed set of constants without per-case data. Exported to Swift as a class with static
  instances, not a Swift `enum` — no exhaustive `switch` without SKIE.

## Naming

- Classes/objects/interfaces: `UpperCamelCase` nouns, no `I` prefix. Implementations of a common interface per
  platform: `AndroidSecureStore`, `IosSecureStore` (platform prefix), or `KeychainSecureStore`/`KeystoreSecureStore`
  (mechanism prefix) — match the module.
- Functions: `lowerCamelCase` verbs; `is`/`has` for booleans.
- Constants: `SCREAMING_SNAKE_CASE`.
- Composables: `UpperCamelCase` nouns (`ProfileScreen`, `AvatarImage`); see `compose-multiplatform.md`.
- Names Swift will see: avoid names that clash with Objective-C/Swift conventions (`description`, `hash`, `init*`,
  `new*`, `copy*` prefixes get mangled or have ARC meaning in Objective-C) — rename, or use `@ObjCName` per
  `ios-interop.md`.

## Member ordering

Properties (public → internal → private), `init` blocks, secondary constructors, functions (public → internal →
private, overrides with the function they override), companion object last in the body, nested types after it.
Overloads stay together. Insert new members in their band; don't reorder an existing file.

## Functions and arguments

- Expression bodies for single-expression functions.
- **Named arguments** for more than two parameters, adjacent same-typed parameters, and every `Boolean` argument.
- **Default arguments are a Kotlin-only convenience.** Objective-C export drops them: Swift must pass every argument
  (unless the project uses SKIE's default-argument support or Swift export). For a Swift-facing API with an optional
  parameter, consider an overload or a parameter object — see `ios-interop.md`.

## Errors

- `require(...)` for invalid arguments, `check(...)` for invalid state, both with a message.
- Domain/expected failures are values (`Result<T>` or a sealed result type), not exceptions, especially on APIs Swift
  consumes — a sealed result is far easier to handle in Swift than an `NSError`.
- Never catch and swallow; never catch `Throwable`; never swallow `CancellationException`.

## Logging

There is no `android.util.Log` or `println`-as-logging in common code. Use the project's logging abstraction — a
common `Logger` interface with platform implementations (`android.util.Log` / `NSLog`/`os_log`), or a KMP logging
library the project already depends on (e.g. Kermit, Napier). Don't add one. Never log a secret, token, or personal
data (`security.md`).

## Collections and stdlib

Read-only interfaces in signatures; `listOf`/`mapOf`/`buildList`; `any`/`firstOrNull`/`associateBy`/`sumOf` over
manual loops that only scan or accumulate. Use the multiplatform stdlib (`kotlin.text`, `kotlin.math`, `kotlin.time`,
`kotlin.random`, `kotlin.uuid`) before reaching for a platform API.

## Other conventions

- Constructor injection: dependencies arrive in the primary constructor as `private val`.
- No dead code, commented-out code, or `TODO`s for work the task covers.
- `@Suppress` is narrowly scoped with a reason; never suppress `EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING`
  per-file — that's a build-level decision (`-Xexpect-actual-classes`) the project makes once.
- `@OptIn` for an experimental API (e.g. `ExperimentalUuidApi`, `ExperimentalTime`, `ExperimentalObjCName`) at the
  narrowest scope that compiles, or once in the module's `languageSettings.optIn(...)` if the project already does
  that — don't add a module-wide opt-in for one call site.
