# Mandatory rules

The rules in this file are **not preferences** — they apply to every line of production and test code this skill
writes or rewrites, and they take precedence over anything softer elsewhere in `reference/` (including "match the
surrounding code"). Each exists because a later gate (`iru-kmp-code-one-task-group`'s per-target tests, coverage,
detekt/ktlint/Lint, Dokka, or the iOS stage) fails on it, or because breaking it compiles fine on one target and
breaks the other one later.

Scope, so the rules don't turn a task into a restyle: they bind **the code you write or rewrite for this task**. If a
file you're adding to already breaks one, write your new code to the rule anyway, leave the old code alone, and name
the pre-existing violation in the report so it can become its own task.

## Placement

### `commonMain` first — and nothing platform-specific in it

- New logic goes in `commonMain` unless it genuinely needs a platform API. "It's only used on Android today" is not a
  reason to put platform-neutral logic in `androidMain`.
- `commonMain` never imports `android.*`, `androidx.*` Android-only artifacts, `java.*`/`javax.*`, `platform.*`
  (Apple frameworks via cinterop), or `kotlinx.cinterop.*`. It compiles to Kotlin metadata, so the compiler stops
  most of these — but a dependency added to `commonMain.dependencies` that only publishes a JVM variant fails late
  (at iOS link time on a Mac, or only in CI). Add a dependency to `commonMain` only when it publishes every target
  the module declares (check its Maven Central `.module` file or docs for `iosArm64`/`iosSimulatorArm64` variants).
- `java.util.UUID`, `java.time.*`, `System.currentTimeMillis()`, `String.format`, `synchronized`, `@Volatile` from
  `kotlin.jvm`, `Thread` — none of these exist in common code. Use `kotlin.uuid.Uuid` (opt-in `ExperimentalUuidApi`
  where still experimental), `kotlin.time` / kotlinx-datetime, string templates, `kotlinx.atomicfu` or a `Mutex`,
  and coroutines respectively.
- A platform API is reached through an interface defined in `commonMain` and implemented per platform (injected), or
  through `expect`/`actual` — see `project-structure.md` for which.

### Tests go in the source set that matches what they test

- Platform-neutral behavior → `commonTest` (it then runs on **every** target: Android host tests and iOS simulator
  tests). This is the default home of a test.
- An `actual` or other `androidMain` code → the Android host-test source set (`androidHostTest` with the
  `com.android.kotlin.multiplatform.library` plugin, `androidUnitTest` with the older `androidTarget()` +
  `com.android.library` setup — detect which exists; `androidInstrumentedTest`/`androidDeviceTest` only when the task
  explicitly asks for on-device tests).
- An `actual` or other `iosMain` code → `iosTest`.
- `androidApp` code → `androidApp/src/test/` (JVM unit tests); `iosApp` Swift → the `iosAppTests` XCTest target.
- Never put a test that needs a platform API in `commonTest` (it won't compile for the other target), and never
  duplicate a `commonTest` test into a platform test source set "to be sure" — it already runs there.

## Code

### No `!!`

Never in production code. Express absence in the type (`T?`) and handle it with `?.`, `?:`, a smart-cast guard,
`requireNotNull(x) { "message" }` (argument) or `checkNotNull(x) { "message" }` (state). In tests use
`assertNotNull(x)` from `kotlin.test` (it returns the value smart-cast) instead of `!!`. On Kotlin/Native a
`NullPointerException` thrown across the Swift boundary terminates the app — there is no recovering from it in Swift.

### No platform types in a `commonMain` public API

A `public` declaration in `commonMain` exposes only common types: Kotlin stdlib, kotlinx libraries, and your own
common types. Platform types appear only in `androidMain`/`iosMain` declarations, and a common API that needs one
takes an abstraction (an interface, a common data class) instead.

### Exceptions crossing into Swift are declared

Any `public` function in a framework-exported module that Swift calls and that can throw something a Swift caller
should handle is annotated `@Throws(SomeException::class, …)`. Without it, a Kotlin exception reaching Swift
terminates the app (a non-`suspend` function propagates nothing; a `suspend` function without `@Throws` propagates
only `CancellationException`). A `@Throws` on a `suspend` function replaces that implicit set, so it must also list
`CancellationException::class` — otherwise a cancelled call becomes fatal in Swift, and the compiler does not warn
(verified: the generated header then documents only the listed type as converted, "Other uncaught Kotlin exceptions
are fatal"). Better still for expected failures: return a result type instead of
throwing. See `ios-interop.md`.

### Coroutines

- No `GlobalScope`, no `runBlocking` in production code (it doesn't exist on every target and blocks the iOS main
  thread where it does). Launch from a lifecycle-bound scope (`viewModelScope`) or an injected `CoroutineScope`.
- Inject the `CoroutineDispatcher` into anything that switches context, so tests substitute a `TestDispatcher`.
- Never catch `CancellationException` and continue — rethrow it (or use `ensureActive()`), or cancellation silently
  stops working.

### `val` over `var`; read-only collection types

Properties and locals are `val` unless reassigned. Public APIs expose `List`/`Set`/`Map`/`StateFlow`, never
`MutableList`/`MutableStateFlow` — keep the mutable backing property `private`.

### KDoc on every public and internal declaration

Class, interface, object, function, property, constructor property, `expect` declaration — with `@param`,
`@property`, `@return`, and `@throws` (which must match the `@Throws` annotation for Swift-facing APIs). Document the
`expect` declaration; an `actual` needs its own KDoc only when its behavior differs per platform in a way callers must
know. See `kdoc.md`. Public Swift types in `iosApp` get `///` documentation comments.

### Named constants, never magic numbers

A literal whose meaning isn't self-evident is a `const val` (in a companion object or at file top level) in
`SCREAMING_SNAKE_CASE`. Timeouts, retry counts, page sizes, URLs, keys: all named.

### No secrets in source

No API key, token, password, signing credential, or private endpoint credential in any source set, `BuildConfig`
field, `gradle.properties` committed to the repo, `Info.plist`, `Config.xcconfig`, or Compose resource. See
`security.md`.

### Never edit generated or build output

Don't hand-edit anything under `build/` (the generated `Res` class, SQLDelight/Room/KSP output, the exported
`Shared.framework` and its Objective-C header), the generated `iosApp/*.xcodeproj` when `iosApp/project.yml` exists
(edit `project.yml` and regenerate), or Xcode's `DerivedData`. Change the source that generates it.

### No new dependency or target unless the task says so

Adding a library, a Gradle plugin (SKIE, KMP-NativeCoroutines, Mokkery, Koin, SQLDelight, Room, ktor engine…), or a
new Kotlin target is a blocker to report, not a decision to make silently — each has build-wide and iOS-binary-size
consequences.

## Tests

- **`kotlin.test`** (`kotlin.test.Test`, `assertEquals`, `assertFailsWith`, `assertNotNull`…) in `commonTest` —
  never JUnit-only APIs (`org.junit.*`) there; those don't exist on iOS.
- **`runTest`** (kotlinx-coroutines-test) for every test that calls a `suspend` function — never `runBlocking`.
- **Fakes over mocks** in `commonTest`: MockK is JVM-only and doesn't compile for iOS. Hand-written fakes of
  common interfaces are the default; Mokkery only if the project already applies it. MockK stays allowed in
  `androidHostTest`/`androidUnitTest` and `androidApp/src/test` if the project already uses it there.
- **One test class per production class**, named `<Class>Test`, in the same package, in the source set the rules
  above choose. Test names follow the module's existing convention — `function_condition_expected` by default, or backtick
  names (``fun `greet rejects blank names`()``) where the module already uses them (verified to compile and run in
  `commonTest` on both the Android host-test and the iOS simulator targets; avoid them in on-device Android test
  source sets, where the Kotlin docs note the Android runtime's limited support for such names).
- **No `Thread.sleep`/`delay` to wait** for asynchronous work — virtual time (`runTest`, `advanceUntilIdle()`) or
  Turbine's `awaitItem()`.
