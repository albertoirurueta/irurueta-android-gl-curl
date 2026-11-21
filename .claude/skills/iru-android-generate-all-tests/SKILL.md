---
name: iru-android-generate-all-tests
description: "/iru-android-generate-all-tests [scope] finds classes of a Kotlin/Android Gradle module (default lib, or a comma-separated class list) lacking JVM unit tests or below 80% line coverage per iru-android-coverage, and generates or extends JUnit 4 + MockK + Robolectric tests, plus Roborazzi UI tests for UI modules. Stops if the repository is not a Kotlin/Android Gradle project."
model: sonnet
allowed-tools: Read Edit Write Bash(./gradlew *) Bash(gradle *) Bash(git status *) Bash(git diff *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent AskUserQuestion
---

# Android Generate All Tests

Bring one Gradle module's JVM unit test coverage up to a minimum bar (80% line coverage) in one pass: find every
class with no test class at all or with measured coverage below the bar, then write or extend tests for each
until it clears it. This skill is Kotlin/Android/Gradle only — it never runs against, or falls back to, any other
language or build tool. It only adds/extends test code; it does not add license headers, KDoc, or run a
code-quality pass (those are `iru-check-license`, `iru-android-dokka`, and `iru-android-code-quality`'s jobs), and
it only touches production source code if a class is genuinely untestable as written (see Step 5) — which it
reports rather than silently "fixing" by redesigning the class. The test conventions this skill defaults to when
a project has nothing to learn from — JUnit 4 + MockK + Robolectric, the `irurueta-android-test-utils` private-
member helpers, `@RunWith(RobolectricTestRunner::class)` only where Android framework types are actually touched
— are grounded in real Kotlin/Android test code observed in
[irurueta-android-glutils](https://github.com/albertoirurueta/irurueta-android-glutils) (genericized in the
examples below — no real package/class names from that project).

**Kotlin Multiplatform projects are out of scope.** If the Kotlin Multiplatform plugin (`kotlin("multiplatform")`/`org.jetbrains.kotlin.multiplatform`, directly or via a `libs.versions.toml` alias) is applied anywhere in the build — a KMP app's Android host module included — stop and hand the request to `iru-kmp-generate-all-tests` instead (or tell the caller to, when it isn't installed); this skill assumes a plain Android `lib`/`app` layout.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 1 — Confirm this is a Kotlin/Android Gradle project

```bash
find . -maxdepth 3 -iname "build.gradle.kts" -not -path "*/build/*"
grep -rl "com\.android\.library\|com\.android\.application" --include="build.gradle.kts" . 2>/dev/null | grep -v /build/
```

- **None found**: this skill applies only to Kotlin/Android Gradle projects. Tell the user plainly and stop — do
  not attempt any other language's equivalent or partial exploration.
- **Found**: continue. If more than one module applies an Android plugin (typically `lib` and `app`) and the
  scope argument (Step 0-equivalent — parsed here directly since the only input is the positional `[scope]`)
  doesn't name one, default to `lib` (the library module conventionally holding the bulk of testable logic in
  this catalog's Android scaffold) rather than asking, unless the repository genuinely has no `lib` module, in
  which case ask (`AskUserQuestion`) which module to scope this run to.
- If the scope argument names specific classes (comma-separated) instead of a module, resolve the module each one
  lives in from its file path.

## Step 2 — Discover every candidate class and the existing test convention

Use the `Explore` agent for this — a module of any real size has too many source files to read directly one by
one, and this step needs a structural map, not a design review.

- Identify every **main-source** Kotlin class/object under `<module>/src/main/java` (or `src/main/kotlin`,
  whichever this project actually uses), excluding `build/` (including `build/generated`), `BuildConfig`, and
  resource-only files. Use judgment on classes with no real logic to exercise (a pure marker interface, an enum
  whose only behavior is its constant list, a plain data class with only generated accessors, a Compose
  `@Preview`-only function, a `Main`/`Application` class that only wires up bootstrapping with no branching logic
  of its own) — note these as "excluded, no testable logic" in the final report rather than silently dropping
  them or forcing trivial tests onto them.
- Identify the **existing JVM test source root** (`<module>/src/test/java`, matching the same package layout as
  `src/main/java`) and the naming convention it already uses to map a source class to its test class (`Foo` →
  `FooTest`, mirroring the package 1:1). Also note, if present, the SDK-branch-specific test-class convention this
  catalog's Android reference repository uses: a class with a conditional branch on `Build.VERSION.SDK_INT` gets
  one test class per branch, suffixed with the `Build.VERSION_CODES` constant it exercises (e.g. `FooTest` for the
  default/newest-API path, `FooQTest` for the pre-API-30 branch under `@Config(sdk = [Build.VERSION_CODES.Q])`) —
  follow this same split convention when a class under test has such a branch and the project already uses it
  elsewhere; otherwise write one test class per production class as usual.
- Note whether `<module>/src/test/resources/<package>/robolectric.properties` exists (setting a default `sdk=`)
  — if so, plain `@RunWith(RobolectricTestRunner::class)` with no explicit `@Config(sdk = [...])` already targets
  that SDK; only add an explicit `@Config(sdk = [...])` when a test specifically needs to pin a *different* API
  level than the project default (as in the SDK-branch convention above).
- For every candidate class, resolve whether a matching test class file already exists by that convention, and
  its path if so.

Build a plain list: class name (with package) → source file path → matching test file path (or "none").

## Step 3 — Measure real coverage for every candidate class in one pass

Delegate this to `iru-android-coverage` via the `iru-gate-runner` agent, so a large JaCoCo XML/HTML report never
lands directly in this conversation's context, and so the whole suite is only executed once regardless of how
many classes are in scope:

```
Agent({
  description: "Measure coverage for every candidate class",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-android-coverage\", args: \"<TargetClass1,TargetClass2,...>\\nmodule: <module>\"})
    — the class list is `iru-android-coverage`'s positional `[scope]` (its first line; omit it for a whole-module
    report) and `module:` is its own `key: value` line (it accepts no `scope:` key) — so the whole unit-test suite
    runs once and every class is measured in the same pass. Report back, per target
    class: its exact line coverage percentage; and, ONLY for classes below 80%, the specific uncovered line
    numbers too (from the per-class JaCoCo XML). For classes at or above 80%, report just the percentage — do not
    include per-line detail for those, to keep the report compact.",
  run_in_background: false
})
```

If the candidate list from Step 2 is very large (rough guide: more than ~50 classes), split it into a few batches
of target-class arguments passed in the same `iru-android-coverage` call structure above so no single
`iru-gate-runner` report becomes unwieldy — this still only requires one full-module-suite run per batch, not one
per class.

If a class has no matching test file (Step 2) it will simply show 0% (or be entirely absent from the coverage
report) — treat "absent from the report" the same as "0%, no coverage," per `iru-android-coverage`'s own
reporting convention.

## Step 4 — Classify

From Step 3's results, split every candidate class into:

- **Missing tests**: no matching test file (Step 2), regardless of reported percentage.
- **Below bar**: a matching test file exists, but line coverage is under 80%.
- **Already sufficient**: line coverage is 80% or above — skip these entirely, count them for the final report.

## Step 5 — Generate or extend tests, one class at a time

For every class in the "missing tests" or "below bar" buckets, write (or extend) its unit tests. Since each
class's test file is independent of every other's, dispatch one sub-agent per class **concurrently** (in batches
of a manageable size — a handful at a time rather than dozens at once — if the bucket is large):

```
Agent({
  description: "Write/extend tests for <ClassName>",
  prompt: "Read <source-file-path> in full — this is the class to cover. <If a test file already exists at
    <test-file-path>: read it too; these lines are currently uncovered: <line numbers from Step 3> — extend this
    file with tests that exercise them.> <If no test file exists: create one at <path implied by this project's
    naming/package convention from Step 2>, under <module>/src/test/java.>

    Follow this project's existing JUnit 4 + MockK + Robolectric style exactly (fall back to the conventions below
    only where the project has nothing to learn from):
    - Plain JUnit 4 (`org.junit.Test`, no `@RunWith`) for a class with no dependency on any `android.*` type.
    - `@RunWith(RobolectricTestRunner::class)` for a class that touches an Android framework type (`Context`,
      a `View`, `SurfaceTexture`, a system service, etc.) — never add Robolectric to a test that doesn't need it.
    - `mockk<T>(relaxed = true)` for a lightweight stand-in collaborator whose every call doesn't need a specific
      `every {}`, or `@MockK` fields with `@get:Rule val mockkRule = MockKRule(this)` when several mocks need
      individual `every {}`/`verify {}` stubbing — match whichever style this project's other tests already use.
      Use `mockkStatic`/`mockkConstructor` sparingly, only when a static/constructed dependency genuinely can't be
      injected. Always call `unmockkAll()` (and `clearAllMocks()` if the project already does) in an `@After`
      method, never only at the end of the test body, so one test's mocks never leak into the next.
    - `ApplicationProvider.getApplicationContext<Context>()` (from `androidx.test.core.app.ApplicationProvider`,
      the `androidx-test-core-ktx` dependency) for a real Robolectric-backed `Context` instead of mocking one,
      whenever the class under test just needs *a* context rather than specific stubbed behavior from it.
    - The `com.irurueta.android.testutils` helpers (`getPrivateProperty`, `setPrivateProperty`, `callPrivateFunc`,
      from the `irurueta-test-utils` / `com.irurueta:irurueta-android-test-utils` dependency) to assert on or seed
      a private property/function instead of widening visibility just for testability.
    - `every { ... }.returns(...)` / `verify { ... }` for MockK stubbing/verification, matching whichever of
      `.returns(x)` vs. `} returns x` infix style this project's existing tests already use.

    Cover the class's actual behavior, including edge cases (invalid-argument handling implied by its own
    validation logic — e.g. an `IllegalArgumentException`-throwing constructor gets a
    `@Test(expected = IllegalArgumentException::class)` case per invalid input — boundary conditions, branches).
    Not superficial calls that merely execute lines without asserting real behavior. Do not modify the class
    under test unless it is genuinely untestable as written (e.g. a hard dependency on a static/non-injectable
    collaborator with no seam to substitute it) — if so, make the minimal change needed to add a seam (e.g.
    extracting an interface, injecting a dependency via a constructor) and say so explicitly rather than
    reporting it as a plain test addition. Report back: the test file touched (new or extended), what was added,
    and whether the class under test needed a minimal testability change.",
  run_in_background: false
})
```

Collect every sub-agent's report before moving on.

## Step 5b — UI tests for existing screens and components (UI modules only)

When the module renders UI (a `@Composable`, an `Activity`/`Fragment`, a custom `View`, an XML layout or a
`RecyclerView` adapter), also bring its UI tests up to the catalog's UI contract — the rules and the Android
mapping are `iru-android-code-one-task`'s `reference/ui-contract.md` and `reference/ui-implementation.md` (read
them from `.claude/skills/iru-android-code-one-task/reference/` when installed; otherwise follow the summary
here). From Step 2's map, list every screen (navigation destination / `Activity` / `Fragment` / screen-level
composable) and every reusable component that has no Robolectric UI test capturing it, then:

- Make sure the module has the JVM screenshot helper `src/test/java/<package>/testing/UiScreenshots.kt` and the
  test dependencies it needs (`io.github.takahirom.roborazzi:roborazzi`, `roborazzi-compose` for Compose,
  `roborazzi-accessibility-check`, `androidx.compose.ui:ui-test-junit4` + `debugImplementation` `ui-test-manifest`
  for Compose, and `testOptions.unitTests.isIncludeAndroidResources = true`). Adding these test-scope
  dependencies to the module's build file and version catalog is the one build change this step may make — say so
  in Step 8.
- Dispatch one Step 5-style agent per screen/component (same batching) to write a Robolectric test with
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and a fixed `@Config(qualifiers = …)` device that renders each visual
  state the component actually has (`default`, `loading`, `empty`, `error`, `disabled`, `selected`,
  `long-content`, `many-items`, …) from minimal/typical/many-items and right-to-left fixture data, asserts its
  events fire with the right payload (once, left-to-right, light), captures every state × the `.ui-quality.yml`
  directions × themes as `<kind>/<Id>/<state>--<direction>--<theme>.png` through the helper, and runs the
  accessibility check at the configured level.
- Instrumented flow tests (`src/androidTest`) stay opt-in, as for every other instrumented test here.

These UI tests are ordinary `src/test` tests, so Step 6's coverage re-measurement and Step 7's suite run cover
them; then run them once more through `iru-gate-runner` with `Skill({skill: "iru-android-test", args: "module:
<module>\nui: jvm"})` to collect the screenshot counts, malformed names and accessibility results for Step 8.
Fix a component's genuine accessibility violation only when the fix is local and obvious (a missing content
description or label); otherwise report it — under `enforce` it keeps that test red, so name it explicitly.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 6 — Re-measure and iterate on shortfalls

Re-run Step 3's delegated `iru-android-coverage` pass, scoped to just the classes touched in Step 5, to confirm
each now meets 80%. For any class still below the bar:

- Identify the specific lines still uncovered (already returned by this re-measurement).
- Dispatch one more Step 5-style agent for just that class, focused on the remaining uncovered lines.
- Re-measure once more.

Cap this at two extension attempts per class. A class still below 80% after that is a genuine residual gap —
report it as such (Step 8), with the exact lines still uncovered, rather than looping indefinitely. Common
legitimate reasons: a defensive branch that's difficult or unsafe to trigger from a JVM unit test (a caught
`OutOfMemoryError`, a hardware-callback path only ever invoked by the real camera/GL pipeline), or a branch that
only differs under a specific `Build.VERSION.SDK_INT` this Robolectric version doesn't shadow well. Name these
explicitly if that's what remains uncovered.

## Step 7 — Run the full unit-test suite once to confirm no regressions

Delegate to `iru-gate-runner`: `Agent({description: "Run full unit test suite", subagent_type: "iru-gate-runner",
prompt: "Invoke Skill({skill: \"iru-android-test\"}) scoped to the same module (fall back to
`./gradlew :<module>:testDebugUnitTest` directly if unavailable). If everything passed, report back only that all
tests passed. If anything failed, report back only the failing test names, the failure reason, and the stack
trace for each."})`. Fix any regression this run's new/extended tests introduced (a genuine bug in a generated
test, not a pre-existing failure), then re-invoke until it reports all tests passed. A pre-existing failure
unrelated to any class this run touched is out of scope — note it in Step 8 rather than fixing it.
Instrumented (`connectedAndroidTest`) coverage is out of scope for this run entirely — this skill only targets
the JVM unit-test suite, per its own description.

## Step 8 — Report

Summarize:

- Total candidate classes found (Step 2), and how many were excluded as having no testable logic, with names.
- The module scoped, and the test convention(s) this run followed (including whether the SDK-branch split
  convention applied to any class).
- How many were already at or above 80% and left untouched.
- For each class in the "missing tests"/"below bar" buckets: before/after line coverage, and whether its test
  file was newly created or extended.
- Any class that required a minimal testability change to the class under test (Step 5), named explicitly.
- Any class still below 80% after Step 6's two attempts, with the exact lines still uncovered and, where
  apparent, why (e.g. an untestable defensive branch or hardware-callback path).
- The full-suite result from Step 7.
- For a UI module (Step 5b): the screens/components that got UI tests, the screenshot count per kind, any
  malformed names, the accessibility results and the `.ui-quality.yml` level applied, and any test-scope
  dependency or `testOptions` change made to the build.

Do not run a code-quality, license-header, or KDoc pass — those are `iru-android-code-quality`, `iru-check-license`,
and `iru-android-dokka`'s jobs respectively, not this skill's. Do not generate `src/androidTest` instrumented
tests (Espresso/Compose UI test rules) unless the invocation explicitly asked for them — the JVM UI tests of
Step 5b are not instrumented tests and are generated by default — this skill's default
scope is JVM unit tests only, since instrumented tests need a device/emulator this skill never launches.

## Known quirks / verification notes

- **JUnit 5 variant**: if the module applies `de.mannodermaus.android-junit` (the plugin id the scaffolds use — the
  older `de.mannodermaus.android-junit5` id was retired when the project was renamed) alongside plain
  JUnit 4, tests use `org.junit.jupiter.api.Test` and `@Nested`/`@ParameterizedTest` are available, but MockK and
  Robolectric usage stay the same (MockK is test-framework agnostic; Robolectric's JUnit 5 extension is
  `@ExtendWith(RobolectricExtension::class)` in place of `@RunWith(RobolectricTestRunner::class)`) — detect which
  is wired from the module's `testImplementation`/`testRuntimeOnly` dependencies before choosing which form to
  write, rather than assuming JUnit 4 unconditionally.
- **Instrumented (`androidTest`) tests are opt-in only.** Even when this skill is asked to also cover
  instrumented tests, it still never runs `connectedAndroidTest` itself (no emulator is launched here) — it only
  writes the test source; running/verifying it is `iru-android-test`'s job when a device is actually attached.
- **This skill's own author did not run Gradle to verify these test skeletons compile** — the reference-repository
  test files quoted for style (constructor/property/mock patterns) were read and matched by eye, not executed
  locally; the scaffold's own `:lib:testDebugUnitTest` run is exercised by `iru-setup-android-library`'s verification against the
  real scaffold this catalog generates, not by this skill's authoring pass.
