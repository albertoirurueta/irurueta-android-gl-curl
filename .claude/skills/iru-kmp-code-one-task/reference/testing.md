# Tests

Every task's implementation comes with the tests that prove it. This skill **writes** them and compile-checks them
(Step 5) — it does not **run** them: `iru-kmp-code-one-task-group` runs them per target (Android host tests, iOS
simulator tests, XCTest) once for the whole task group, behind the simulator lock where needed.

## Follow the module you're in

Read an existing test in the same source set first and match it: assertion style, fake/mock approach, naming,
fixtures. `mandatory-rules.md`'s test rules (`kotlin.test` in common code, `runTest`, fakes over mocks, one test class
per class, source-set placement) override a module that does otherwise only for **new** code.

## Where a test goes

| Code under test | Test source set | Runs on |
|---|---|---|
| `commonMain` | `commonTest` | every target — Android host (JVM) and iOS simulator |
| `androidMain` | `androidHostTest` (`com.android.kotlin.multiplatform.library`) / `androidUnitTest` (`androidTarget()`) | JVM on the host |
| `iosMain` | `iosTest` | iOS simulator (macOS only) |
| `androidApp` | `androidApp/src/test/kotlin` (or `java`) | JVM on the host |
| `iosApp` Swift | `iosApp/iosAppTests` (XCTest) | iOS simulator (macOS only) |

The Android host-test source set exists only if the module configures it (`withHostTestBuilder {}` under the
Android-KMP library plugin) — if it doesn't and the task needs an `androidMain` test, that's a build change to
report, not to make silently. Coverage (Kover) is measured **only from the JVM runs** (Android host tests and
`androidApp` unit tests); Kotlin/Native has no coverage tool — so `commonMain` logic reaches the 80% bar through
`commonTest` running on the Android host target, and `iosMain` code is "not measurable". Push logic into
`commonMain` and keep `actual`s thin: it's what makes the code coverable.

## `kotlin.test`

- `import kotlin.test.Test`, `assertEquals(expected, actual)`, `assertTrue`, `assertNull`, `assertIs<T>(value)`
  (returns the smart-cast value), `assertFailsWith<IllegalArgumentException> { … }` (returns the exception for
  message assertions), `assertNotNull(x)` instead of `!!`.
- `@BeforeTest`/`@AfterTest` for setup/teardown (not JUnit's `@Before`).
- `kotlin("test")` (or `libs.kotlin.test`) in `commonTest.dependencies` brings the right per-target runner
  (JUnit on JVM, the native runner on iOS).

## Coroutines — `runTest`

```kotlin
@Test
fun refresh_whenRepositorySucceeds_emitsContent() = runTest {
    val repository = FakeProfileRepository(result = Result.success(PROFILE))
    val viewModel = ProfileViewModel(repository, ioDispatcher = StandardTestDispatcher(testScheduler))

    viewModel.onRefresh()
    advanceUntilIdle()

    assertEquals(ProfileUiState.Content(PROFILE), viewModel.state.value)
}
```

- `runTest` runs on virtual time: `delay` is skipped, `advanceUntilIdle()`/`advanceTimeBy()` drive the scheduler.
- Pass `StandardTestDispatcher(testScheduler)` (or `UnconfinedTestDispatcher(testScheduler)` for eager execution)
  into the class's injected dispatcher so its work runs on the test scheduler.
- **`ViewModel`s** use `Dispatchers.Main` via `viewModelScope`: set it in `@BeforeTest`
  (`Dispatchers.setMain(StandardTestDispatcher())`) and reset in `@AfterTest` (`Dispatchers.resetMain()`) — available
  in common code from kotlinx-coroutines-test. Without it, Android host tests fail with "Module with the Main dispatcher
  is missing".
- Never `runBlocking` (unavailable/blocking on some targets), never real `delay`s to wait.

## Flows — Turbine (if present)

```kotlin
viewModel.state.test {
    assertEquals(ProfileUiState.Loading, awaitItem())
    viewModel.onRefresh()
    assertEquals(ProfileUiState.Content(PROFILE), awaitItem())
    cancelAndIgnoreRemainingEvents()
}
```

`app.cash.turbine:turbine` is multiplatform (verified in `commonTest` on both targets). `awaitItem()`,
`awaitComplete()`, `awaitError()`, `expectNoEvents()`; end with `cancelAndIgnoreRemainingEvents()` for hot flows.
Without Turbine: collect into a list in `backgroundScope.launch { flow.toList(results) }` under `runTest`.

## Fakes over mocks

MockK is JVM-only and won't compile for iOS, so `commonTest` uses:

- **Hand-written fakes** of `commonMain` interfaces — the default. Keep them in `commonTest` (one file per fake or a
  `fakes/` package, per the module's convention), configurable through constructor parameters or public `var`s, and
  recording calls in lists the test asserts on. This is also why platform services are interfaces (`project-structure.md`).
- **In-memory real implementations** where they exist: `MockEngine` for Ktor (below), an in-memory SQLDelight driver
  (JVM `JdbcSqliteDriver(IN_MEMORY)` in the Android host-test source set / `inMemory = true` native driver in
  `iosTest`), Room's in-memory builder, a `DataStore` on a temp path.
- **Mokkery** (`dev.mokkery` plugin) only if the module already applies it: `mock<Repo>()`,
  `every { repo.find(any()) } returns x`, `everySuspend { repo.load(any()) } returns x`, `verify { … }`,
  `verifySuspend { … }`. Mokkery mocks interfaces and open/abstract classes; Kotlin classes are final, so mocking a
  concrete class needs the project's all-open setup — prefer extracting an interface only if the task already
  touches that seam.
- MockK remains fine in the Android host-test set and `androidApp` tests if the module already uses it there (e.g.
  to mock an Android framework type), never in `commonTest`.

## Ktor — `MockEngine`

```kotlin
val engine = MockEngine { request ->
    assertEquals("/v1/profile/42", request.url.encodedPath)
    respond(PROFILE_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
val api = ProfileApi(HttpClient(engine) { install(ContentNegotiation) { json(appJson) } })
```

Requires `ktor-client-mock` in `commonTest`. Assert on the request (path, method, headers, body) and on the mapped
result; cover non-2xx and malformed-body paths implied by the KDoc `@throws`/result contract.

## Android host tests (`androidMain`)

- Plain JVM tests: Android framework classes are stubs. Behavior that needs a real `Context`/framework uses
  Robolectric **only if the module already has it** on the host-test classpath (`@RunWith(RobolectricTestRunner::class)`,
  `ApplicationProvider.getApplicationContext()`); otherwise keep the Android `actual` thin and test the logic it
  delegates to in `commonTest`.
- `kotlin.test` assertions work here too; JUnit 4 annotations are acceptable in this source set if the module's
  existing host tests use them.

## iOS tests (`iosMain`)

- `iosTest` uses `kotlin.test` and may call `platform.*` APIs (Foundation, Security) — e.g. exercise a
  Keychain-backed `actual` against the simulator's keychain, cleaning up in `@AfterTest`.
- They run only on macOS with Xcode (the `iosSimulatorArm64Test` task boots a simulator itself) — the group skill
  runs them; you only compile them (`compileTestKotlinIosSimulatorArm64`).

## Compose Multiplatform UI tests (`ui: compose`)

```kotlin
@OptIn(ExperimentalTestApi::class)
class ProfileScreenTest : UiTest() {   // UiTest: the module's Robolectric-on-Android base (ui-implementation.md)
    @Test
    fun retryButton_click_invokesOnRetry() = runComposeUiTest {   // androidx.compose.ui.test.v2.runComposeUiTest
        var retried = false
        setContent { AppTheme { ProfileScreen(state = ProfileUiState.Error, onRetry = { retried = true }) } }

        onNodeWithText("Retry").performClick()

        assertTrue(retried)
    }
}
```

- `org.jetbrains.compose.ui:ui-test` in `commonTest`; query by text/content description, `testTag` as a last resort.
- **Where it runs — verified locally**: the same `runComposeUiTest` test in `commonTest` **passed** under
  `iosSimulatorArm64Test` and **failed** under `testAndroidHostTest` (`NullPointerException` at `runComposeUiTest`)
  on a module with no Robolectric. Because every `commonTest` test also runs on the Android host target, a Compose UI
  test in `commonTest` turns the Android host-test task red unless the module has an Android UI-test setup for it
  (Robolectric on the host-test classpath with Android resources enabled, per the JetBrains docs — unverified
  locally). So:
  - module already runs Compose UI tests in `commonTest` successfully on Android (the `UiTest` base class with a
    Robolectric `actual`, Robolectric + `ui-test-manifest` in `androidHostTest`, as `iru-setup-kmp-app` scaffolds
    and `ui-implementation.md` describes — verified on both targets) → follow it;
  - otherwise put Compose UI tests in **`iosTest`** (they run natively in the simulator) or a desktop `jvmTest` if
    the module has a `jvm()` target, and say so in the report. Never add Robolectric or a target to make it fit.
- UI tests also capture every visual state across the `.ui-quality.yml` directions × themes into
  `.ui-screenshots/` (platform segment `--android`/`--ios`) and call `checkUiAccessibility()` — the helper and the
  matrix pattern are in `ui-implementation.md`.
- Prefer testing state holders: a `ViewModel` test is faster, runs everywhere, and counts toward coverage; a UI test
  should assert wiring (a click calls the callback, state renders the right element), not business rules.

## Swift tests (`iosApp/iosAppTests`)

- XCTest (`import XCTest`, `@testable import iosApp`, `import Shared`), `final class XTests: XCTestCase`, one test
  class per Swift type, `XCTAssertEqual`, `async` test methods for `await`. Use Swift Testing (`@Test`, `#expect`) only
  if the target already does.
- Test Swift-side logic: mapping Kotlin state to view data, the observable model's start/stop behavior. Kotlin
  logic is tested in Kotlin — don't re-test shared behavior from Swift.
- `ui: native` SwiftUI views get `ImageRenderer` screenshot tests through `UIScreenshots.swift`
  (`ui-implementation.md`); flows and the accessibility audit need an XCUITest target.
- Fakes: Swift classes implementing the exported Kotlin protocols.

## What to cover

- The behavior the task added or changed, through the public/internal API — happy path plus meaningful variations.
- **Every `@throws`/error result in the KDoc**: one test per documented failure condition.
- Boundaries: empty collections, zero/negative, first/last valid value, `null` where meaningful.
- For each `expect`: its contract is tested once in `commonTest` (runs against every `actual`); platform-specific
  edge cases of an `actual` go in that platform's test set.
- Not trivia: a data class's generated members, a constant, a one-line delegate.

## Writing the test

- One behavior per test, arrange/act/assert visibly separated, `assertEquals` over boolean comparisons.
- Constants for test values in the test class's companion object.
- Test classes follow `code-style.md` ordering; KDoc on test functions only when the scenario's *why* is non-obvious.
- Don't widen visibility to test something; `internal` is visible to the same module's test source sets already.

## What this skill does not do

No running the tests, no coverage, no detekt/ktlint/Lint/SwiftLint, no license headers, no Dokka build, no simulator.
If a test can't be made to compile without something outside the task's scope, say so in the report — don't delete
the assertion.
