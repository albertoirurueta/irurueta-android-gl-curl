---
name: iru-swift-generate-all-tests
description: "/iru-swift-generate-all-tests [scope] - for a Swift package or Xcode project only, finds every type with no test or under 80% line coverage (via iru-swift-coverage) and generates or extends tests, plus UI tests for existing screens and components. Optional scope: <target-or-type-list>."
model: sonnet
allowed-tools: Read Edit Write Bash(swift *) Bash(xcodebuild *) Bash(xcrun *) Bash(git status *) Bash(git diff *) Bash(find *) Bash(grep *) Bash(ls *) Skill Agent AskUserQuestion
---

# Swift Generate All Tests

Bring an entire Swift codebase's unit test coverage up to a minimum bar (80% line coverage) in one pass: find
every type with no test at all or with measured coverage below the bar, then write or extend tests for each
until it clears it. This skill is Swift (Swift Package Manager or Xcode project) only — it never runs against,
or falls back to, any other language. It only adds/extends test code; it does not add license headers, DocC
comments, or run a code-quality pass (those are `iru-check-license`, `iru-swift-docc`, and `iru-swift-code-quality`'s
jobs), and it only touches production source code if a type is genuinely untestable as written (see Step 5) —
which it reports rather than silently "fixing" by redesigning the type.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

Parse `args` as `key: value` lines if given (currently just `scope: <target-or-type-list>`), and accept a bare
positional `[scope]` argument as shorthand for the same thing. Skip any question below that this already answers.

## Step 1 — Confirm this is a Swift project, and which test framework(s) are in use

```bash
find . -maxdepth 2 -iname "Package.swift" -not -path "*/.build/*"
find . -maxdepth 3 \( -iname "*.xcodeproj" -o -iname "*.xcworkspace" \) -not -path "*/.build/*"
```

- **Neither found**: this skill applies only to Swift packages or Xcode projects. Tell the user plainly and stop
  — do not attempt any other language's equivalent or partial exploration.
- **`Package.swift` found**: a Swift Package Manager project. This is the primary shape this skill targets.
- **Only an `.xcodeproj`/`.xcworkspace` found** (no `Package.swift`, or one only for a sub-package): an Xcode
  app project. Continue, but note that test execution in Steps 3/6/7 goes through `xcodebuild test` (via
  `iru-swift-test`/`iru-swift-coverage`) instead of `swift test`.
- If both exist (an Xcode app with a local Swift package, or a package with a demo/host app), and the scope
  argument (Step 0) doesn't name one, ask (`AskUserQuestion`) which one this run targets rather than guessing —
  they usually hold very different kinds of code (app/UI glue vs. library logic).

Detect which test framework(s) the project already uses by inspecting existing test files (grep for
`import Testing`/`@Test`/`@Suite` vs. `import XCTest`/`XCTestCase`):

- **Swift Testing present, or no tests exist yet on a `swift-tools-version` of 6.0+** (Swift Testing ships in
  the toolchain, no package dependency needed): this is the default framework for every unit test Step 5 writes.
  A brand-new `swift package init --type library` scaffold already generates a Swift Testing test file, so
  "no tests yet" on a modern toolchain still means Swift Testing, not XCTest.
- **Only XCTest present** (an older codebase, or a `swift-tools-version` below 6.0): follow the project's own
  convention and keep writing XCTest for consistency, but flag to the user in the final report that migrating
  to Swift Testing is available once the toolchain supports it.
- **Both present**: use whichever framework the majority of existing unit tests already use for parity; always
  use XCTest specifically for anything Step 5 classifies as a UI test (`XCUITest`, needing a simulator/device
  and `XCUIApplication`) or a performance test (`measure`), regardless of which framework unit tests use —
  Swift Testing has no UI-automation or performance-measurement equivalent as of this toolchain.

## Step 2 — Discover every candidate type and the existing test convention

Use the `Explore` agent for this — a project of any real size has too many source files to read directly one by
one, and this step needs a structural map, not a design review.

- Identify every **production type** (`struct`, `class`, `enum`, `actor`) under the scoped target's source root
  (`Sources/<Target>/` for a package, or the app/module's own source group for an Xcode project), excluding:
  - `.build/`, `DerivedData/`, and any other build output.
  - Generated code (e.g. `*.generated.swift`, Xcode's asset-catalog/String-Catalog generated accessors, any
    `*/generated/` tree).
  - SwiftUI preview-only content (`#Preview` blocks, a `_Previews` struct with no logic beyond rendering).
  - Pure `App`/`@main` entry-point types and `SceneDelegate`/`AppDelegate` types that only wire up bootstrapping
    with no branching logic of their own.
  - Plain marker protocols, an `enum` whose only behavior is its case list, or a `struct`/`class` with only
    generated/synthesized members (e.g. a `Codable` DTO with no custom `init`/computed logic). Use judgment the
    same way as the Java/Android skills — note these as "excluded, no testable logic" in the final report rather
    than silently dropping them or forcing trivial tests onto them.
  - Existing test files themselves (anything under a `Tests/` or `*Tests/`/`*UITests/` directory).
- Identify the **existing test source root** and naming convention already in use — do not assume one, detect
  it from what's on disk:
  - A Swift package convention: `Tests/<Target>Tests/` mirroring `Sources/<Target>/`, with `<Type>Tests.swift`
    (a `struct <Type>Tests` tagged `@Suite` under Swift Testing, or a `final class <Type>Tests: XCTestCase`
    under XCTest) importing the target under test with `@testable import <Target>`.
  - An Xcode app convention: a `<AppName>Tests` target for unit tests and a `<AppName>UITests` target for UI
    tests, each its own top-level group in the project, same `<Type>Tests.swift` file-naming pattern.
  - If **no test source root exists at all** (no `Tests/` directory, no `*Tests` target in the Xcode project,
    and no test dependencies), stop and tell the user: this skill extends an existing test setup's coverage, it
    doesn't scaffold one from nothing — ask (`AskUserQuestion`) whether tests actually live somewhere
    unconventional before concluding there really are none.
- For every candidate type, resolve whether a matching test file already exists by that convention, and its
  path if so. Note separately which candidates are UI-surface types (a `View`/`ViewController` primarily driving
  user interaction) that this project already covers, if at all, with `XCUITest` rather than a unit test — they
  are not coverage candidates (the UI layer is "not measured"); instead they feed Step 5b's UI-test inventory.
  `measure` cases are only written when the scope argument or the project's own convention explicitly calls for them.
- **UI inventory** (for Step 5b), when the scoped target renders UI (SwiftUI `View`s, `UIViewController`/`UIView`,
  `NSViewController`/`NSView` subclasses — never for a command-line tool): every screen and component, whether it
  already has UI tests per the catalog's UI contract (a component-capture test calling `UIScreenshots.render`/
  `capture`, or an XCUITest that captures it), and whether the project has a `UIScreenshots.swift` helper and a
  UI-test target.

Build a plain list: type name (with module) → source file path → matching test file path (or "none").

## Step 3 — Measure real coverage for every candidate type in one pass

Delegate this to `iru-swift-coverage` via the `iru-gate-runner` agent, so a large `.profdata`/`llvm-cov`/`xccov`
report never lands directly in this conversation's context, and so the whole suite is only executed once
regardless of how many types are in scope:

```
Agent({
  description: "Measure coverage for every candidate type",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-swift-coverage\", args: \"<Type1,Type2,...>\\ntarget: <Target>\"}) —
    the comma-separated type list is `iru-swift-coverage`'s positional `[scope]` argument and the target goes
    in its `target:` key (there is no `scope:` key; an unrecognized key would fall back to a whole-package
    report, so the per-type classification would run against unfiltered totals) — so the whole test suite runs once (via `swift test --enable-code-coverage` or `xcodebuild test -enableCodeCoverage
    YES`, whichever this project uses) and every type is measured in the same pass. Report back, per target
    type: its exact line coverage percentage; and, ONLY for types below 80%, the specific uncovered line ranges
    too (from the `llvm-cov`/`xccov` per-file report). For types at or above 80%, report just the percentage —
    do not include per-line detail for those, to keep the report compact.",
  run_in_background: false
})
```

If the candidate list from Step 2 is very large (rough guide: more than ~50 types), split it into a few batches
of target-type arguments passed in the same `iru-swift-coverage` call structure above so no single
`iru-gate-runner` report becomes unwieldy — this still only requires one full-suite run per batch, not one per
type.

If a type has no matching test file (Step 2) it will simply show 0% (or be entirely absent from the coverage
report) — treat "absent from the report" the same as "0%, no coverage," per `iru-swift-coverage`'s own reporting
convention.

## Step 4 — Classify

From Step 3's results, split every candidate type into:

- **Missing tests**: no matching test file (Step 2), regardless of reported percentage.
- **Below bar**: a matching test file exists, but line coverage is under 80%.
- **Already sufficient**: line coverage is 80% or above — skip these entirely, count them for the final report.

## Step 5 — Generate or extend tests, one type at a time

For every type in the "missing tests" or "below bar" buckets, write (or extend) its unit tests. Since each
type's test file is independent of every other's, dispatch one sub-agent per type **concurrently** (in batches
of a manageable size — a handful at a time rather than dozens at once — if the bucket is large):

```
Agent({
  description: "Write/extend tests for <TypeName>",
  prompt: "Read <source-file-path> in full — this is the type to cover. <If a test file already exists at
    <test-file-path>: read it too; these lines are currently uncovered: <line ranges from Step 3> — extend this
    file with tests that exercise them.> <If no test file exists: create one at <path implied by this project's
    naming/directory convention from Step 2>.>

    Default to Swift Testing for a unit test (fall back to plain XCTest only where the project's own convention,
    detected in Step 1, is XCTest-only):
    - `import Testing` and `@testable import <Target>`; group a type's tests in a `struct <TypeName>Tests`
      tagged `@Suite(\"<display name>\")`, one `@Test(\"<case description>\")` function per behavior.
    - Parameterize repeated cases with `@Test(arguments:)` instead of copy-pasted near-identical test functions —
      a single `[(Int, Int, Int)]`-shaped array of tuples works directly as the argument collection, with the
      test function taking one parameter per tuple element (verified: the compiler destructures the tuple into
      the function's parameter list without an explicit `for (a, b, expected) in` unpack). For two independent
      parameter axes needing a full cartesian product instead of paired tuples, pass two separate `arguments:`
      collections.
    - `#expect(<condition>)` for a non-fatal assertion (execution continues on failure, so multiple independent
      expectations can be checked in one test); `#expect(throws: SomeError.self) { try ... }` (or
      `#expect(throws: SomeError.specificCase) { ... }` for an `Equatable` error case) to assert a call throws —
      never wrap it in a manual `do`/`catch`. `#require(<expression>)` (or `try #require(try? ...)`) when the
      rest of the test cannot meaningfully continue without that value/condition holding — it throws and stops
      the test immediately on failure, unlike `#expect`.
    - `withKnownIssue(\"<tracking note, e.g. an issue id>\") { ... }` to wrap an expectation that's currently
      known to fail (a tracked bug) without failing the suite — it still reports the issue, and starts failing
      the build loudly once the wrapped code starts passing unexpectedly, so it self-clears when the bug is
      fixed. Only use it for a genuinely tracked, temporary gap — never as a way to silence a flaky new test.
    - `.tags(.<tagName>)` on a `@Test`/`@Suite` to group related tests (e.g. a shared `.networking` or `.slow`
      tag defined as a `Tag` extension) when this project's existing tests already use tags for that purpose;
      don't introduce a new tag taxonomy unprompted.
    - `await confirmation(\"<expectation>\", expectedCount: <n>) { confirm in ... }` to assert a callback-based
      or delegate-style event fires an expected number of times within the test (the Swift Testing equivalent of
      XCTest's `XCTestExpectation`) — call `confirm()` inside the callback under test, not `confirm` itself.
    - An `async` test function (`@Test func ... async { ... }`, or `async throws` when it also calls a throwing
      async API) for anything awaiting an `async` API — Swift Testing runs each test in its own task, so no
      `XCTestExpectation`/`waitForExpectations` dance is needed for a plain `await`.
    - Testing an `actor`: call its methods with `await` from the test function (mark the test function `async`);
      never reach into an actor's isolated state directly — if a test needs to assert on internal state,
      expose it via an `async` accessor method on the actor itself, the same seam-adding rule as production code.
    - Testing a `@MainActor`-isolated type: mark the test function itself `@MainActor` (or the whole `@Suite`
      `@MainActor` if every test in it touches the same main-actor type) so calls into it don't need individual
      `await MainActor.run { ... }` wrapping; a plain (non-isolated) test function calling into a `@MainActor`
      type still needs each call `await`-ed and may need `await MainActor.run { ... }` for a synchronous
      accessor.
    - `Sendable` constraints: a type crossing an `async`/actor boundary in the test (passed into `Task {}`, into
      an actor's method, or held by a `@Test(arguments:)` parameter used inside an `async` test body) must
      itself be `Sendable` (or be captured by value where the compiler can prove isolation) — if the strict
      concurrency checker (Swift 6 language mode, the project's default) rejects a fixture value at the
      isolation boundary, make the fixture type conform to `Sendable` (a `struct`/`enum` fixture usually already
      does implicitly) rather than suppressing the diagnostic with `@unchecked Sendable` unless the project's own
      production code already does the same for a comparable case.

    Use XCTest instead, in its own `XCTestCase` subclass, ONLY for:
    - A UI test (`XCUITest`): `final class <Feature>UITests: XCTestCase`, `XCUIApplication().launch()` in
      `setUpWithError()`, interact via `app.buttons[\"...\"].tap()`/`app.textFields[\"...\"].typeText(...)`,
      assert with `XCTAssert*` against the accessibility hierarchy (`exists`, `waitForExistence(timeout:)`).
      Only write these when the scope argument or the project's own convention explicitly calls for UI-test
      coverage — this skill's default scope is unit tests (see Step 2's note on UI-surface types).
    - A performance test (`measure { ... }` inside a `func test<Name>Performance()`): `measure` is an XCTest-only
      API with no Swift Testing equivalent as of this toolchain. It must live in its own `XCTestCase` subclass —
      a `@Test` function cannot be declared inside an `XCTestCase` subclass at all (Swift Testing tests are free
      functions/struct methods, never members of an `XCTestCase` type), so never try to add a `measure` block to
      a Swift Testing `@Suite`, or a `@Test` function alongside `measure` in the same `XCTestCase` class — Swift
      Testing and XCTest tests coexist fine in the same test target and even the same file, just never in the
      same `XCTestCase` class/type.

    Cover the type's actual behavior, including edge cases (invalid-input handling implied by its own
    validation logic — e.g. a throwing initializer/method gets one `#expect(throws:)` case per invalid input —
    boundary conditions, branches). Not superficial calls that merely execute lines without asserting real
    behavior. Match this project's existing test style exactly (naming, `@Suite`/`@Test` display-name phrasing,
    fixture/helper conventions already in use). Do not modify the type under test unless it is genuinely
    untestable as written (e.g. a hard dependency on a non-injectable singleton or global with no seam to
    substitute it) — if so, make the minimal change needed to add a seam (e.g. extracting a protocol, injecting
    a dependency via an initializer) and say so explicitly rather than reporting it as a plain test addition.
    Report back: the test file touched (new or extended), what was added, and whether the type under test needed
    a minimal testability change.",
  run_in_background: false
})
```

Collect every sub-agent's report before moving on.

## Step 5b — Generate UI tests for screens and components that have none

Only when Step 2's UI inventory found screens/components without UI tests. Follow `iru-swift-code-one-task`'s
`reference/ui-contract.md` §6–§8 and `reference/ui-implementation.md` (read them from that installed skill; if it
isn't installed, say so and skip this step):

- Add the shared `Support/UIScreenshots.swift` helper to the unit-test target (and the UI-test target's sources) when
  the project lacks it — copied from `iru-setup-apple-app`/`iru-setup-macos-app`'s test templates when installed,
  else from `ui-implementation.md`.
- Per **component** with no UI test: one Swift Testing suite in the app's unit-test target with a parameterized
  `@Test(arguments: UIQuality.directions, UIQuality.themes)` per visual state the component supports (read its
  `#Preview`s and state type), rendering fixture states — including `long-content` with Arabic/Hebrew text — with
  `UIScreenshots.render` (SwiftUI-native leaves) or `UIScreenshots.capture` (UIKit/AppKit views), plus, for UIKit/
  AppKit, event assertions by sending actions.
- Per **screen** with no UI test, when a UI-test target exists: an XCUITest that launches with `--uitesting` (and a
  `--fixture` the composition root already understands — never invent fixture plumbing in production code here;
  report it as a gap instead), asserts the screen's states once, captures each `.ui-quality.yml` direction × theme
  with `XCUIScreen.main.screenshot()`, and one `performAccessibilityAudit()` test at the configured level. No UI-test
  target → report the screens as uncovered rather than creating a target.
- One sub-agent per screen/component, the same way as Step 5. Then run them all through `iru-gate-runner`:
  `Invoke Skill({skill: "iru-swift-test", args: "ui"})` — a full UI run, which clears and rebuilds
  `.ui-screenshots/`, recovers captures from the `.xcresult`, and takes `.simulator-test.lock` itself. Fix failures in
  the generated tests (not in production code); report accessibility issues at the configured level instead of
  silencing them. No simulator runtime/macOS desktop session → the generated UI tests are reported **unverified**.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 6 — Re-measure and iterate on shortfalls

Re-run Step 3's delegated `iru-swift-coverage` pass, scoped to just the types touched in Step 5, to confirm each
now meets 80%. For any type still below the bar:

- Identify the specific lines still uncovered (already returned by this re-measurement).
- Dispatch one more Step 5-style agent for just that type, focused on the remaining uncovered lines.
- Re-measure once more.

Cap this at two extension attempts per type. A type still below 80% after that is a genuine residual gap —
report it as such (Step 8), with the exact lines still uncovered, rather than looping indefinitely. Common
legitimate reasons: a defensive branch that's difficult or unsafe to trigger from a unit test (a caught
`fatalError`-adjacent guard, a platform/hardware-specific branch this environment can't emulate), or a
data-race-safety branch only reachable under real concurrent scheduling that a deterministic test can't force.
Name these explicitly if that's what remains uncovered.

## Step 7 — Run the full suite once to confirm no regressions

Delegate to `iru-gate-runner`: `Agent({description: "Run full test suite", subagent_type: "iru-gate-runner",
prompt: "Invoke Skill({skill: \"iru-swift-test\"}) (fall back to `swift test` or `xcodebuild test -scheme
<scheme> -destination 'platform=iOS Simulator,name=<first available iPhone>'` directly if unavailable, matching
whichever this project uses per Step 1). If everything passed, report back only that all tests passed. If
anything failed, report back only the failing test names, the failure reason, and relevant output for each."})`.
Fix any regression this run's new/extended tests introduced (a genuine bug in a generated test, not a
pre-existing failure), then re-invoke until it reports all tests passed. A pre-existing failure unrelated to any
type this run touched is out of scope — note it in Step 8 rather than fixing it. UI suites already ran in Step 5b's
`ui` run; don't re-run them here unless a fix touched them.

## Step 8 — Report

Summarize:

- Total candidate types found (Step 2), and how many were excluded as having no testable logic, with names.
- Which test framework(s) were detected/used (Step 1), and the test-file naming/source-root convention followed.
- How many were already at or above 80% and left untouched.
- For each type in the "missing tests"/"below bar" buckets: before/after line coverage, and whether its test
  file was newly created or extended.
- Any type that required a minimal testability change to the type under test (Step 5), named explicitly.
- Any type still below 80% after Step 6's two attempts, with the exact lines still uncovered and, where
  apparent, why (e.g. an untestable defensive branch or concurrency-scheduling-dependent path).
- The full-suite result from Step 7.
- Step 5b: the screens/components that got UI tests (and which were left uncovered and why), the screenshot count
  per kind from the `ui` run, malformed names, accessibility issues (count + rule ids) at the `.ui-quality.yml`
  level, and anything reported unverified.

Do not run a code-quality, license-header, or DocC pass — those are `iru-swift-code-quality`, `iru-check-license`,
and `iru-swift-docc`'s jobs respectively, not this skill's. Do not generate `measure` cases beyond what Step 5
already scoped in; UI tests are limited to Step 5b's contract tests, and their simulator runs go through
`iru-swift-test`'s `ui` selector, never launched by hand.

## Known quirks / verification notes

- **Verified locally** (Swift 6.4, `swift-tools-version: 6.4`, macOS, via `swift package init --type library`
  in a scratch directory): a `struct`, an `actor`, and a throwing method all compiled and ran correctly under
  Swift Testing with `@Suite`, `@Test(arguments:)` using an array-of-tuples parameterization destructured
  directly into named function parameters, `#expect(throws: SomeError.someCase)`, `try #require(...)`, an
  `async` test awaiting an actor's methods, and `withKnownIssue { #expect(...) }` (which reported the wrapped
  failure as a "known issue" and still passed the suite, as designed) — `swift test` ran all cases green with
  no strict-concurrency (Swift 6 language mode) diagnostics for this shape of code. `swift build` and
  `swift test` both succeeded with zero warnings from the language-mode/concurrency checker.
- **Verified locally**: `measure { ... }` inside its own `final class ...: XCTestCase` in a dedicated test
  target ran correctly under `swift test` (not just `xcodebuild test`) and printed the standard
  `com.apple.XCTPerformanceMetric_WallClockTime` measurement line — SwiftPM does not require Xcode/`xcodebuild`
  to exercise `measure`, contrary to the assumption that performance tests are Xcode-only.
- **Verified locally**: a `@Test` function (Swift Testing) and an `XCTestCase` subclass containing a `measure`
  block coexist without conflict in the same test target and even the same source file — `swift test` (and a
  filtered `swift test --filter <Type>`) ran both correctly. The constraint is narrower than "don't mix
  frameworks in a target": it is specifically that a `measure` block, and more generally XCTest instance
  methods, cannot be declared as members of a Swift Testing `@Suite`, and a `@Test` free function/struct method
  cannot be declared as a member of an `XCTestCase` subclass — the two live in structurally different test
  types, not just different files.
- **Verified locally**: `swift test --enable-code-coverage` produced a `.profdata` at
  `.build/out/Products/Debug/codecov/default.profdata`, and `swift test --show-codecov-path` printed the
  matching per-module JSON coverage summary path (`.build/out/Products/Debug/codecov/<ModuleName>.json`) —
  `iru-swift-coverage` should read coverage from that JSON (via `llvm-cov export`/`xcrun llvm-cov`) rather than
  parsing raw `.profdata`.
- **Verified locally**: `swift format lint --recursive <paths>` (the toolchain-bundled formatter, no `brew`
  install needed) ran successfully against the scratch package's default 4-space-indented source and flagged
  the SwiftPM template's own 2-space indentation as non-conformant — i.e. this formatter's default style
  expectation does not match `swift package init`'s own scaffold indentation, so don't assume a freshly
  generated package is already `swift format`-clean.
- **Not verified locally** (this catalog's authoring pass had no Xcode `.xcodeproj`/`.xcworkspace` app target
  available, and no `brew`-installed `xcbeautify`/`xcodegen`/`tuist`): `xcodebuild test -enableCodeCoverage YES`
  and its `xcrun xccov view --report` output shape, `XCUIApplication`-driven `XCUITest` execution against a
  simulator, and any Xcode-project-specific (as opposed to SwiftPM-package) coverage/report path. These follow
  standard Apple documentation and the shape mirrored from `iru-android-generate-all-tests`'s equivalent
  instrumented-test caveat, but were not exercised end-to-end in this authoring pass — `iru-swift-coverage` and
  `iru-swift-test`'s own verification passes are the ones expected to confirm the `xcodebuild` path directly
  against a real Xcode app scaffold.
- **`swift-tools-version` below 6.0**: Swift Testing is unavailable (it ships with the Swift 6 toolchain/SwiftPM
  integration); Step 1's XCTest-only fallback applies, and parameterization must use a manual loop over a fixture
  array with `XCTAssert*` calls instead of `@Test(arguments:)`.
