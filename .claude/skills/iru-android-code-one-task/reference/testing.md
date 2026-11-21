# Tests

Every task's implementation comes with the tests that prove it. This skill **writes** them and does not **run**
them beyond `iru-android-code-one-task`'s own Step 5 compile-check — the calling
`iru-android-code-one-task-group` runs the suite, coverage, and quality checks once for the whole task group.
Write tests that will pass and that will hold the 80%-line-coverage gate up; don't invoke the full test task
here.

## Follow the package you're in

Before writing a test, read an existing test in the same package. Match its structure, its naming convention,
its use of MockK and Robolectric. Introducing a second convention in a project — a different naming scheme, a
different mocking library, `Robolectric` where the surrounding tests are plain JVM tests — is a cost paid by
every later reader, and it isn't what the task asked for.

Defaults for a project that doesn't yet have a convention: JUnit 4 (`org.junit.Test`/`org.junit.Assert`), MockK
for mocking, Robolectric for anything that touches the Android framework and can't run on a plain JVM.

## JUnit 4

This catalog's Android scaffold targets JUnit 4 (`org.junit:junit`), not JUnit 5/Jupiter — Robolectric's and
AndroidX Test's own JUnit 4 integration (`@RunWith`, `AndroidJUnitRunner`) is the reason, and it's what the
reference sample uses throughout.

- **`@Test fun name_condition_expectedResult()`** — an underscore-separated, sentence-like method name
  (`setRenderer_whenAlreadySet_throwsIllegalStateException`, `debugFlags_returnsExpectedValue`, matching the
  reference sample) reads as the assertion without opening the method body.
- **`@Before`/`@After`** for per-test setup/teardown — fresh fixtures every test, never shared mutable state
  across tests, never a reliance on test execution order.
- **`org.junit.Assert.*`** (`assertEquals`, `assertTrue`, `assertNull`, `assertSame`, …) — `assertEquals(expected,
  actual)` over asserting a boolean comparison, so a failure message shows the actual diff.
- **`assertThrows` isn't in JUnit 4's `Assert`** the way it is in JUnit 5 — either use JUnit 4's
  `@Test(expected = IllegalStateException::class)` for a simple "this call throws" assertion, or wrap the call in
  a `try { ...; fail("expected X") } catch (e: X) { /* assert on e's message/fields here */ }` when the test
  needs to inspect the thrown exception's state, not just its type.

## MockK

- **`@MockK` fields plus `@get:Rule val mockkRule = MockKRule(this)`** initializes every `@MockK`-annotated
  property automatically — the pattern the reference sample's `GLTextureViewTest` uses for its EGL/renderer
  collaborators. Prefer this over manual `mockk<T>()` calls when a test class needs several mocks.
- **`mockk<T>(relaxed = true)`** for a collaborator whose calls you don't care to stub individually (everything
  returns a sensible default — `0`, `false`, an empty collection, another relaxed mock) — reach for a relaxed
  mock to avoid `io.mockk.MockKException: no answer found` noise on incidental calls, but stub explicitly
  (`every { ... } returns ...`) whenever the *value* returned is part of what the test is asserting.
- **`every { collaborator.method(...) } returns value`** to stub, **`verify { collaborator.method(...) }`** to
  assert an interaction happened — verify only when the interaction itself is the behavior being tested (a
  listener was invoked, a lock was acquired), not as a substitute for asserting on a result.
- **`mockkStatic(SomeClass::class)`** / **`mockkObject(SomeObject)`** to mock a static Java method or a Kotlin
  `object`'s members (needed for framework statics like `Build.VERSION.SDK_INT` gates, or for mocking
  `OrientationHelper`-style utility objects from a caller's test) — always paired with `unmockkStatic`/
  `unmockkObject`, or with the blanket `unmockkAll()` below, so the static mock doesn't leak into the next test
  class in the same JVM.
- **`clearAllMocks()` and `unmockkAll()` in `@After`** — the reference sample's `GLTextureViewTest.afterTest()`
  does both, and both matter: `clearAllMocks()` resets stubbed answers/call history between tests in the same
  class, `unmockkAll()` tears down any `mockkStatic`/`mockkObject` registrations so they don't bleed into a
  different test class run in the same process.
- Mock what you don't own or can't afford: an Android framework collaborator (`CameraManager`,
  `WindowManager`), a slow or hardware-backed dependency, a clock. Use the real thing for a data class, a plain
  Kotlin value, or a cheap in-memory collaborator — mocking a value object is pure ceremony.
- **Don't mock the class under test**, and don't `spyk` on it to stub away the exact behavior the task just
  changed.

## Robolectric

Use Robolectric for a unit test that needs a real (simulated) Android framework — a `View`, a `Context`, system
services — without an emulator/device, i.e. anything the reference sample's `GLTextureViewTest` and
`OrientationHelperTest` need.

- **`@RunWith(RobolectricTestRunner::class)`** at the class level — every test in the class runs against the
  simulated framework.
- **`@Config(sdk = [Build.VERSION_CODES.Q])`** on an individual `@Test` (or at the class level) to pin the
  simulated SDK level for a test whose behavior is version-specific — the reference sample's
  `OrientationHelperQTest` pins every test to `Build.VERSION_CODES.Q` because the code path it covers
  (`context.display.rotation`) only exists from Android R onward and needs the pre-R branch exercised under an
  explicit older SDK. Leave `@Config` off when the module's default `sdk` (declared once in
  `src/test/resources/<package>/robolectric.properties`, e.g. `sdk=35`) already matches what the test needs.
- **`ApplicationProvider.getApplicationContext<Context>()`** for the `Context` a constructor/function needs —
  never `mockk<Context>()` for a real framework object Robolectric can simulate faithfully; a mocked `Context`
  tends to under-simulate exactly the behavior worth testing.
- Robolectric tests are slower than plain JVM tests — reach for it only when the code under test genuinely
  touches the framework (a `View`, `Context.getSystemService`, `Build.VERSION`), not for logic that's really
  framework-independent and could run as a plain JUnit test instead.

## `androidTest` (instrumented tests)

A task that adds a custom `View`/`Composable` occasionally also needs (or already has) an `androidTest` — real
device/emulator instrumented tests under `src/androidTest/java`, run with `connectedAndroidTest`, not part of
this skill's own compile-check or `iru-android-code-one-task-group`'s unit-coverage gate:

- **Espresso** (`androidx.test.espresso`) for a Views-based screen — `onView(withId(...)).perform(click())`,
  matchers, idling resources for asynchronous UI.
- **`createComposeRule()`/`createAndroidComposeRule<Activity>()`** for a Compose screen — see `compose.md`.
- **`AndroidJUnitRunner`** as the `testInstrumentationRunner`, `androidx.test.ext:junit`/`junit-ktx` for
  `@RunWith(AndroidJUnit4::class)`.
- Write the `androidTest` when the task explicitly asks for instrumented coverage, adds or changes a user flow
  (see "UI tests" below), or when a behavior genuinely can't be verified any other way (real touch/gesture
  handling, real `SurfaceTexture` lifecycle). Don't write one
  merely because a unit test would be harder — Robolectric usually covers the same ground faster.

## UI tests

A task that adds or changes a screen, component or list also writes UI tests per `ui-contract.md` §6–§8, mapped
in `ui-implementation.md`: component and screen tests on Robolectric in `src/test` (every visual state, minimal/
typical/many-items and right-to-left fixture data, a Roborazzi capture of each state across the `.ui-quality.yml`
directions × themes through the module's `testing/UiScreenshots.kt` helper, and the Accessibility Test Framework
check at the configured level), plus an instrumented flow test only when the task adds or changes a flow. These
JVM UI tests count toward the unit-test coverage gate like any other `src/test` test.

## `com.irurueta:irurueta-android-test-utils`

When the project depends on this catalog's real, published test-utility artifact
(`com.irurueta:irurueta-android-test-utils`, package `com.irurueta.android.testutils`) — check
`lib/build.gradle.kts`'s `testImplementation` block before assuming it's present — use its reflection helpers to
assert on or set a `private`/`internal` member from a test without widening that member's visibility just to make
it testable:

- **`instance.getPrivateProperty("name")`** to read a private/internal property's current value (the reference
  sample's `GLTextureViewTest` uses this throughout to assert constructor defaults: `view.getPrivateProperty
  ("glThread")`).
- **`instance.setPrivateProperty("name", value)`** to seed a private/internal property before exercising a
  function that reads it.
- **`instance.callPrivateFunc("name", vararg args)`** to invoke a private/internal function directly, for the
  rare case the behavior genuinely can't be reached through the public API (last resort — see "Don't test private
  members directly" below first).

Without this dependency, don't widen a member's visibility just to test it — exercise it through the public
behavior that uses it instead.

## What to cover

- **The behavior the task added or changed**, through the public/internal API — the happy path plus the
  meaningful variations.
- **Every `@throws` in the KDoc.** Each documented exception gets a test asserting it's thrown for the documented
  condition — this is the single most useful habit here, and it's where most real defects surface.
- **Boundaries**: empty/single-element collections, zero, negative, the first and last valid value, `null` where
  a nullable type makes it a meaningful input.
- **Not the trivia.** A `data class`'s generated `equals`/`copy`, a constant, or a one-line delegate to an
  already-tested function needs no dedicated test. Coverage is a gate, not a goal.

## Writing the test

- **One behavior per test method**, named per the "JUnit 4" section above.
- **Arrange, act, assert**, in that order and visibly separated — see the reference sample's
  `setRenderer_whenNonExisting_setsExpectedValue`: default-value check, then the call under test, then the
  assertions, cleanly separated by a comment.
- **Assert the specific thing** — `assertEquals`/`assertSame` over a hand-rolled boolean condition.
- **No `Thread.sleep`.** Await a condition (`glThreadManager`-style locks/conditions in production code should
  expose something a test can wait on synchronously), inject a fake `Clock`/dispatcher, or make the seam
  synchronous — a sleep is either flaky or slow, usually both.
- **Don't test private members directly** by exporting them or widening visibility. Exercise them through the
  public/internal behavior that uses them; when that's genuinely impossible, either use the test-utils reflection
  helpers above, or treat it as a finding to report — not a refactor to perform mid-task.
- Test classes get the same member ordering as any other Kotlin class (`code-style.md`) — `@MockK` fields, then
  `@Before`/`@After`, then `@Test` functions in a sensible reading order. Test functions don't need KDoc unless
  the *why* of the scenario is non-obvious.

## Coverage target

`iru-android-code-one-task-group` enforces **≥ 80% line coverage** for the bucket via `iru-android-coverage`
(unit tests only; instrumented coverage is reported for information when available, never required). Write tests
that actually exercise the branches the task added, not tests aimed at the number — a test that asserts nothing
meaningful just to move a percentage costs maintenance and proves nothing.

## What this skill does not do

Beyond `iru-android-code-one-task`'s own Step 5 compile-check, no running the suite, no coverage report, no
lint/detekt/ktlint run, no license headers, no KDoc audit — all of those belong to
`iru-android-code-one-task-group`, once per task group. If a test can't be made to pass without something outside
the task's scope, say so in the report; don't delete the assertion to make the file green.
