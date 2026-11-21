---
name: iru-kmp-generate-all-tests
description: "/iru-kmp-generate-all-tests [scope] finds classes of a Kotlin Multiplatform module (default shared; or :shared, androidApp, a class list) with no tests or under 80% line coverage per iru-kmp-coverage, and generates or extends tests in the matching source set, plus UI tests where wired. Args: scope:, ios: yes|no. Stops if the project is not KMP Gradle."
model: sonnet
allowed-tools: Read Edit Write Bash(./gradlew *) Bash(gradle *) Bash(uname *) Bash(xcode-select *) Bash(git status *) Bash(git diff *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent AskUserQuestion
---

# Kotlin Multiplatform Generate All Tests

Bring one KMP module's unit test coverage up to a minimum bar (80% line coverage, JVM-measured) in one pass: find
every class with no test at all or with measured coverage below the bar, then write or extend tests for each until it
clears it. This skill is Kotlin Multiplatform/Gradle only — it never runs against, or falls back to, another stack. It
only adds/extends test code; it does not add license headers, KDoc, or run a code-quality pass (`iru-check-license`,
`iru-kmp-dokka`, and `iru-kmp-code-quality`'s jobs), and it only touches production code if a class is genuinely
untestable as written (Step 5) — which it reports rather than silently redesigning the class. The conventions follow
`iru-kmp-code-one-task/reference/testing.md` and `mandatory-rules.md` when that skill is installed alongside — read
them first if present.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines if given (`scope: <module-or-class-list>`, `ios: yes|no`), and accept a bare
positional `[scope]` as shorthand for `scope:`. `ios` defaults to `no`.

## Step 1 — Confirm this is a Kotlin Multiplatform project, and pick the module

```bash
ls gradlew settings.gradle.kts 2>/dev/null
grep -lE 'org\.jetbrains\.kotlin\.multiplatform|libs\.plugins\.kotlin\.multiplatform|kotlin\("multiplatform"\)' */build.gradle.kts 2>/dev/null
```

- **No module applies the Kotlin Multiplatform plugin, or no Gradle wrapper**: tell the user plainly and stop — this
  skill doesn't fall back to Android-only or Swift-only test generation (use `iru-android-generate-all-tests` /
  `iru-swift-generate-all-tests` for those).
- **Found**: default to `shared` (or `composeApp` when that's the only KMP module); `scope` may name `androidApp`
  instead (then the Android app's JVM unit tests are the target, following its JUnit 4 + MockK conventions as in
  `iru-android-generate-all-tests`). More than one KMP module and nothing in `scope` to choose → ask
  (`AskUserQuestion`).
- If `scope` names classes, resolve each one's module and source set from its file path.

Then list the module's tasks once (`./gradlew :<module>:tasks --all`) to learn the Android host-test task
(`testAndroidHostTest` under `com.android.kotlin.multiplatform.library`, `testDebugUnitTest` under `androidTarget()`)
and whether an Android host-test source set is configured at all. **No JVM test target** (no Android host tests and no
`jvm()` target) means nothing can be measured: tell the user that coverage needs the host-test source set
(`withHostTestBuilder {}`) and stop rather than writing tests blind. Also note `uname -s`/`xcode-select -p` — iOS
tests can only be compiled and run on macOS with Xcode.

## Step 2 — Discover every candidate class and the existing test convention

Use the `Explore` agent — a module of real size has too many files to read one by one, and this step needs a
structural map.

- Every production class/object/top-level-function file under `<module>/src/<sourceSet>/kotlin/` for `commonMain`,
  `androidMain`, and the iOS source sets (`iosMain`, `appleMain`, `nativeMain`), recording each one's source set.
  Exclude `build/` (generated `Res` accessors, SQLDelight/Room/KSP output), `expect` declarations with no body (their
  contract is tested through the `actual`s, from `commonTest`), and classes with no testable logic — a marker
  interface, an enum whose only behavior is its entries, a plain data class with only generated members, `@Preview`
  composables, `MainViewController()`/`MainActivity`-style wiring, a DI module that only declares bindings. Note those
  as "excluded, no testable logic" for the report.
- The existing test source sets (`commonTest`, the Android host-test set, `iosTest`) and their conventions: mirrored
  package layout, `<Class>Test` naming, `kotlin.test` vs JUnit annotations, Turbine, fakes (where they live — a
  `fakes/` package?), Mokkery, `Dispatchers.setMain` setup, MockK/Robolectric use in the host-test set.
- For every candidate, its matching test file by that convention (in the source set Step 5 would use), or "none".

Build a plain list: class → source set → source file → matching test file (or "none").

## Step 3 — Measure real coverage for every measurable candidate in one pass

Delegate to `iru-kmp-coverage` via `iru-gate-runner`, so a large Kover report never lands in this context and the
JVM suite runs once:

```
Agent({
  description: "Measure coverage for every candidate class",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-kmp-coverage\", args: \"<Class1,Class2,...>\\nmodule: <:module>\\nbar: 80\"}) — the
    class list is its positional scope. Report back, per target class: its source set and exact line coverage
    percentage; ONLY for classes below 80%, the uncovered line numbers too; and list iosMain/appleMain/nativeMain
    classes separately as not measurable. Keep it compact — no per-line detail for classes at or above 80%.",
  run_in_background: false
})
```

Split very large candidate lists (more than ~50 classes) into a few batches of the same call. A class absent from the
report counts as 0%.

## Step 4 — Classify

- **Missing tests**: no matching test file, regardless of percentage.
- **Below bar**: a test exists but measurable line coverage is under 80%.
- **Already sufficient**: ≥ 80% — skip, count for the report.
- **Not measurable** (iOS source sets): skip unless `ios: yes`; with `ios: yes`, a class with no `iosTest` test goes
  into "missing tests" (behavior-only — its result is checked by running the tests, not by coverage).

## Step 5 — Generate or extend tests, one class at a time

Dispatch one sub-agent per class **concurrently**, in batches of a handful:

```
Agent({
  description: "Write/extend tests for <ClassName>",
  prompt: "Read <source-file-path> in full — the class to cover, in source set <sourceSet> of module <module>.
    <If a test exists at <test-file-path>: read it; these lines are uncovered: <lines> — extend it to exercise them.>
    <If none: create <test path in the matching test source set — commonTest for commonMain, <androidHostTest|androidUnitTest>
    for androidMain, iosTest for iosMain — mirroring the package>.>

    Follow the project's existing test style exactly; where it has nothing to learn from:
    - commonTest / iosTest: kotlin.test only (`kotlin.test.Test`, `assertEquals`, `assertFailsWith`, `assertNotNull`
      instead of `!!`, `@BeforeTest`/`@AfterTest`) — never `org.junit.*`, never MockK (JVM-only; it won't compile for
      iOS). Collaborators are hand-written fakes of the class's interfaces (reuse existing fakes before writing new
      ones; put new ones where the project keeps them), or real in-memory implementations (Ktor `MockEngine` when
      ktor-client-mock is on the test classpath). Use Mokkery (`mock<T>()`, `every`/`everySuspend`, `verify`) only if
      the module already applies the `dev.mokkery` plugin.
    - Coroutines: `= runTest { ... }`, inject `StandardTestDispatcher(testScheduler)` into dispatcher parameters,
      `advanceUntilIdle()`; for classes using `viewModelScope`/`Dispatchers.Main`, `Dispatchers.setMain(...)` in
      `@BeforeTest` and `Dispatchers.resetMain()` in `@AfterTest`. Never `runBlocking`, never real delays.
    - Flows: Turbine (`flow.test { awaitItem(); ... cancelAndIgnoreRemainingEvents() }`) if it's on the test
      classpath, else collect into a list from `backgroundScope`.
    - Android host-test set (androidMain code): kotlin.test or the module's JUnit 4 style; MockK/Robolectric only if
      the module already uses them there.
    - iosTest: may call platform.* APIs; clean up any simulator state (e.g. Keychain items) in `@AfterTest`.
    - Names: `function_condition_expected` unless the module uses backtick names.

    Cover real behavior, including every documented `@throws`/error result (one `assertFailsWith` case per invalid
    input), boundaries, and branches — not calls that merely execute lines. Never put a test needing a platform API
    in commonTest. Do not modify the class under test unless it's genuinely untestable (e.g. it constructs a
    platform-bound or network dependency internally with no seam); then make the minimal change — extract a common
    interface, inject it through the constructor — and say so explicitly. Report back: the test file touched (new or
    extended), its source set, what was added, and whether the class needed a testability change.",
  run_in_background: false
})
```

Collect every report before moving on. After the batch, compile-check the touched test source sets once (Android:
`./gradlew :<module>:compileAndroidHostTest` or `compileDebugUnitTestKotlinAndroid`; iOS, macOS only:
`:<module>:compileTestKotlinIosSimulatorArm64`) — a `commonTest` file that compiles for JVM but uses a JVM-only API
fails only on the iOS compile. Fix compile errors in the generated tests before measuring.

## Step 5a — UI tests for existing screens and components

Only when the module renders UI (`@Composable` functions in `commonMain` for `ui: compose`, in `androidApp` or
SwiftUI views in `iosApp` for `ui: native`) **and** the screenshot helper the UI contract needs is already wired — a
`commonTest` `uitest/UiScreenshots.kt` helper with the Robolectric `UiTest` base for `ui: compose`, or
`androidApp/src/test/…/uitest/UiScreenshots.kt` / `iosApp/iosAppTests/UIScreenshots.swift` for `ui: native` (see
`iru-kmp-code-one-task/reference/ui-implementation.md` when installed). The generated tests capture every visual
state × the `.ui-quality.yml` directions × themes into `.ui-screenshots/` with the `--android`/`--ios` platform
segment, plus the accessibility check. Without the wiring, list the
screens/components that lack UI tests in the report as "needs the UI test wiring (Robolectric `UiTest` base +
screenshot helper)" — never add Robolectric, a dependency or a test target here.

1. Inventory every screen (navigation destination / stateless `…Screen`) and component (other public stateless
   composables or SwiftUI views taking state + callbacks) and find the ones with no test that captures them
   (`grep -rl "captureUiScreenshot(.*\"<Id>\"" ` in the test trees, `"<Id>"` in `UIScreenshots.capture` calls).
2. Per missing one, dispatch a Step 5-style agent: read the component, its state class and its KDoc'd visual
   states, then write one test class (in `commonTest` extending `UiTest` for shared Compose UI; `androidApp/src/test`
   with Robolectric + `createComposeRule()` for native Android; `iosApp/iosAppTests` for SwiftUI) that asserts each
   event fires once (left-to-right, light), captures every applicable visual state with `minimal-data`,
   `typical-data`, `many-items`/`long-content` and right-to-left text fixtures across the `.ui-quality.yml` matrix
   (`<kind>` `screen`/`component`, `<Id>` = the type name exactly), and calls the accessibility check. One
   `setContent` per test, state driven through a `mutableStateOf`, `waitForIdle()` before each capture.
3. Run them via `iru-gate-runner`: `Skill({skill: "iru-kmp-test", args: "ui <the new UI test classes>\nmodule:
   <:module>"})` — report screenshots per kind and platform, malformed names, accessibility violations (count + rule
   ids) and the `.ui-quality.yml` level; iOS parts are unverified when the host can't run them. Fix failures in the
   generated tests; an `enforce` accessibility failure in production UI is reported (with the rule id and
   component), not fixed here.

## Step 6 — Re-measure and iterate on shortfalls

Re-run Step 3's delegated pass scoped to the classes touched in Step 5. For any class still below 80%: dispatch one
more Step 5-style agent focused on the remaining uncovered lines, then re-measure. Cap this at two extension attempts
per class; a class still below the bar is a residual gap to report with its uncovered lines and the likely reason
(an Android framework path the host tests can't exercise without Robolectric, a composable whose UI test can't run on
the JVM target in this project, a defensive branch unreachable from a unit test).

## Step 7 — Run the full suite once to confirm no regressions

Delegate to `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner", prompt:
"Invoke Skill({skill: \"iru-kmp-test\", args: \"module: <:module>\\ntarget: <android, or all when ios: yes or any
commonTest file was added/changed and this is macOS with Xcode>\"}) (fall back to `./gradlew <module>:testAndroidHostTest`
/ `testDebugUnitTest` if unavailable). Let the skill take .integration-test.lock itself for any iOS stage — do not pass
lock: held. If everything passed, report back only that; otherwise only the failing test names (with target), the
failure reason, and the first stack-trace line, and any stage reported unverified with its reason."})`. `commonTest`
tests also run on iOS — a generated test that passes on the JVM and fails on iOS (formatting, `Dispatchers.Main`,
JVM-only behavior) is a bug in the generated test to fix. Fix regressions this run introduced and re-invoke until
green; a pre-existing failure unrelated to any class touched is out of scope — note it. An iOS stage that couldn't run
(not macOS, no simulator runtime, lock timeout) is reported "unverified", never "passed".

## Step 8 — Report

- Module scoped, total candidates (Step 2), and those excluded as having no testable logic, by name.
- The test conventions followed (assertion style, fakes vs Mokkery, Turbine, host-test set name).
- How many were already at or above 80% and left untouched; how many iOS-only classes were "not measurable" (and
  whether `ios: yes` added behavior tests for them).
- Per class in "missing tests"/"below bar": source set, before/after line coverage, new vs extended test file.
- Any class that needed a minimal testability change, named explicitly.
- Any class still below 80% after two attempts, with its uncovered lines and why.
- The full-suite result per target from Step 7, including any unverified iOS stage and its reason.
- UI (Step 5a): screens/components that got UI tests, captures per kind and platform, accessibility violations and
  the level applied, unverified parts, and UI left uncovered because the module lacks the UI test wiring.

Do not run a code-quality, license-header, or KDoc pass. Do not generate on-device Android tests
(`androidInstrumentedTest`/`androidDeviceTest`) or `iosApp` XCTest tests unless the invocation explicitly asks for
them — the default scope is unit tests in the KMP module's own test source sets, plus Step 5a's UI tests where the
module is wired for them (`iosApp` `ImageRenderer` tests for `ui: native` count as those).

## Known quirks / verification notes

- **Verified locally** (Kotlin 2.4.21, AGP 9.4.1 `com.android.kotlin.multiplatform.library` with
  `withHostTestBuilder {}`, Gradle 9.8.1, kotlinx-coroutines 1.11.0, Turbine 1.2.1, macOS + Xcode, in
  `$TMPDIR/iru-verify/kmp/iru-kmp-code-one-task/`): `commonTest` tests using `kotlin.test`, `runTest` with an injected
  `StandardTestDispatcher(testScheduler)`, and Turbine ran green under both `:shared:testAndroidHostTest` and
  `:shared:iosSimulatorArm64Test`; the latter booted a simulator by itself; `--tests` filtering worked on both;
  backtick test names with spaces ran on both targets.
- **Not verified locally by this skill's author**: Kover measurement and its report shape (owned and verified by
  `iru-kmp-coverage`), Mokkery, Robolectric in the KMP host-test set, `androidTarget()` + `com.android.library` task
  names on AGP 9, and the Explore-agent discovery pass against a real scaffold.
- An unmatched `--tests` filter on the Kotlin/Native test task exits 0 with no result XML — when Step 7 scopes
  iOS tests, a missing XML means "matched nothing", not "passed" (`iru-kmp-test` reports it that way).
