---
name: iru-swift-test
description: "/iru-swift-test [ui] [selector] - runs a Swift project's tests: swift test for a SwiftPM package, xcodebuild test plus xcresulttool for an Xcode app; selector is a test, suite or Suite.method name. Optional key: value lines: scheme, destination, kind, workspace, project, lock. A leading ui runs UI tests. Report-only; zero matches is a warning."
model: haiku
---

# Swift Test

Run the project's test suite through whichever runner its layout calls for — SwiftPM's `swift test` for a
package, `xcodebuild test` for an Xcode app/workspace project — scoping the run to whatever the user asked for,
and report a compact pass/fail summary. This skill only runs tests and reports results — it does not fix
failures or modify source/test code unless asked to as a follow-up.

## Step 0 — Resolve inputs

This skill takes a single positional `[selector]` argument, not `key: value` args, unless the calling
skill/prompt explicitly passes `key: value` lines — in that case honor them and skip the matching inference in
Steps 1–2:

- `kind: package|app` — override the Step 1 auto-detection (use when a repository has both a `Package.swift` and
  an `.xcodeproj`/`.xcworkspace` and the caller knows which one to exercise).
- `scheme: <name>` — the Xcode scheme to test (app path only).
- `destination: <xcodebuild destination string>` — e.g. `platform=macOS`, `platform=iOS
  Simulator,name=iPhone 18 Pro,OS=27.0` (app path only).
- `workspace: <path>.xcworkspace` / `project: <path>.xcodeproj` — disambiguate when more than one exists.
- `lock: held` — the caller (`iru-swift-code-one-task-group`) already holds the repository's `.simulator-test.lock`;
  a `ui` run then doesn't take it (see "UI run").

A leading **`ui`** word in the selector (`ui`, `ui OrderRowTests`, `ui MyAppUITests-iOS/OrdersScreenUITests`) asks
for a **UI run** per the catalog's UI contract — the component-capture tests and XCUITest screen/flow tests, their
screenshots and accessibility audits — handled by the "UI run" section after Step 5; whatever follows `ui` is an
ordinary selector scoping it.

`[selector]` shapes, mapped in Step 2/Step 4:

- No argument → run everything.
- A bare test/method name (`testFoo`, `fooReturnsBar`) → filter to it.
- A suite/class name (`FooTests`) → filter to everything in it.
- `Suite.method` / `Suite/method` (`FooTests.testBar`, `FooTests/testBar`) → filter to one test in one suite.
- A target-qualified form (`FooPackageTests.FooTests/testBar`) → filter to one test in one suite in one target.

## Step 1 — Detect package vs. app, and verify prerequisites

```bash
find . -maxdepth 2 \( -name "Package.swift" -o -name "project.yml" -o -name "Project.swift" \
  -o -name "*.xcodeproj" -o -name "*.xcworkspace" \) -not -path "*/.build/*"
```

- **Only `Package.swift` found** (no `project.yml`/`Project.swift`/`*.xcodeproj`/`*.xcworkspace`) → **package**. Go
  to Step 2.
- **Any of `project.yml`, `Project.swift`, `*.xcodeproj`, `*.xcworkspace` found** → **app**. Go to Step 3. This
  holds even when a `Package.swift` also exists alongside them (e.g. local SwiftPM modules pulled into an app
  target) — the presence of a project/workspace means `xcodebuild` is the right runner, unless `kind: package`
  overrides it.
- **`project.yml` (XcodeGen) or `Project.swift` (Tuist) present but no generated `.xcodeproj`/`.xcworkspace`
  yet** → the project must be generated first (`xcodegen generate` / `tuist generate`). If the tool isn't on
  `PATH`, stop and report that generation is needed before tests can run — this skill does not install XcodeGen
  or Tuist itself. `iru-setup-apple-app` writes the `project.yml`/`Project.swift` manifest but never runs the
  generator (the `.xcodeproj` is gitignored and regenerated on demand), so the user is expected to have run
  `xcodegen generate`/`tuist generate` once after that scaffold before invoking this skill.
- **Nothing found** → stop and report "no Swift package or Xcode project found" rather than guessing.

Prerequisites, checked before invoking either runner since a missing one produces a confusing failure that looks
like a test problem but isn't:

- **Package path**: `swift --version` succeeds (Xcode's bundled toolchain, or a standalone `swift.org` toolchain,
  must be on `PATH`).
- **App path**: `xcodebuild -version` succeeds, and a full Xcode install is selected (`xcode-select -p` points at
  `/Applications/Xcode.app/Contents/Developer`, not just the Command Line Tools — `xcodebuild test` fails outright
  under CLT-only). Simulator destinations additionally need `xcrun simctl` to work; `xcrun simctl list devices
  available` can be slow, so give it a generous timeout (or pipe to `head`) rather than treating a slow response
  as a hang.

## Step 2 — Package path: build the `--filter`/`--skip` arguments

`swift test --filter '<regex>'` runs both XCTest and Swift Testing tests in one invocation and matches the regex
as a substring against each test's qualified identifier (`<Module>.<Class>/<method>` for XCTest,
`<Module>.<function>()`-shaped for Swift Testing) — one filter syntax covers both frameworks, per
`swift test --help`: `Run test cases that match a regular expression, for example, <test-target>.<test-case> or
<test-target>.<test-case>/<test>`.

| What the user wants | Flag |
|---|---|
| Everything | *(omit `--filter`)* |
| One test/method name | `--filter '<name>'` |
| One suite/class | `--filter '<Suite>'` |
| One method in one suite | `--filter '<Suite>[./]<method>'` (either separator works as a regex substring match) |
| One target/module | `--filter '<Target>\.'` |
| Several disjoint names | repeat `--filter '<pattern>'` once per pattern — **verified: repeated `--filter`
  flags are OR'd together** (each pattern is matched independently across both frameworks), not AND'd; e.g.
  `--filter testPasses --filter 'example\(\)'` ran one XCTest test and one Swift Testing test, one from each
  filter, not their intersection |
| Excluding a pattern | `--skip '<regex>'` (same regex semantics, inverted) |

Escape regex metacharacters in the selector when it came from free text (e.g. `()` in a Swift Testing function
name) rather than passing it through raw.

## Step 3 — App path: resolve scheme/destination and build `-only-testing:`

**Scheme**: use `scheme:` if given. Otherwise run `xcodebuild -list` (add `-project <path>`/`-workspace <path>`
only if `project:`/`workspace:` was given or more than one exists at the repository root) and:

- **Per-platform schemes** (`iru-setup-apple-app`'s layout — `Targets/<Platform>/` directories and schemes
  `<AppName>-iOS`, `<AppName>-macOS`, `<AppName>-watchOS`, `<AppName>-visionOS`, each testing `CoreTests` +
  `<AppName>Tests-<Platform>` + `<AppName>UITests-<Platform>`): ignore XcodeGen's `Core` package scheme and pick by
  scope — a selector or file under `Targets/<Platform>/` (or a `<AppName>Tests-<Platform>`/`<AppName>UITests-<Platform>`
  target) → that platform's scheme only; a `Packages/Core`-only selector (`CoreTests/…`) → the first platform scheme
  in the order iOS, macOS, watchOS, visionOS (every scheme runs `CoreTests`); no selector (the whole suite) → **every**
  platform scheme, one `xcodebuild test` per scheme with its own destination and result bundle, reported per scheme.
  `-only-testing:` then names `<AppName>Tests-<Platform>`/`<AppName>UITests-<Platform>`/`CoreTests`.
- **Otherwise**: pick the sole scheme it reports; if several are listed, prefer one that doesn't contain
  "Tests"/"UITests" in its name and state that choice explicitly in the report, or ask which one if the
  prompt/caller can answer.

**Destination**: use `destination:` if given. Otherwise infer the platform — from the scheme suffix
(`-iOS`/`-watchOS`/`-visionOS`/`-macOS`) for per-platform schemes, else from the project (macOS-only tooling →
`platform=macOS`; an iOS/iPadOS target → an iOS Simulator destination). A macOS scheme gets `platform=macOS`. For a
simulator platform, resolve the **newest installed runtime** that the selected Xcode's simulator SDK supports and its
first matching device, and **always add `OS=<runtime version>`** — several runtimes ship the same device names
(`iPhone 17` exists under iOS 26.4 and 27.0), and without `OS=` `xcodebuild` silently picks one of them (verified:
no warning is printed), so the run's OS version is not the one you think:

```bash
# resolve_destination <iOS|watchOS|visionOS> <device-name prefix: iPhone | iPad | Apple Watch | Apple Vision Pro>
resolve_destination() {
  local sdk
  case "$1" in
    iOS) sdk=iphonesimulator ;;
    watchOS) sdk=watchsimulator ;;
    visionOS) sdk=xrsimulator ;;
  esac
  SDK_VERSION="$(xcrun --sdk "$sdk" --show-sdk-version)" PLATFORM="$1" PREFIX="$2" python3 -c '
import json, os, subprocess
def ver(v): return tuple(int(x) for x in v.split(".")[:2])
platform, prefix, sdk = os.environ["PLATFORM"], os.environ["PREFIX"], ver(os.environ["SDK_VERSION"])
names = {platform, "xrOS"} if platform == "visionOS" else {platform}
runtimes = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "runtimes", "available", "-j"]))["runtimes"]
devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
for rt in sorted((r for r in runtimes if r["platform"] in names and ver(r["version"]) <= sdk),
                 key=lambda r: ver(r["version"]), reverse=True):
    for d in devices.get(rt["identifier"], []):
        if d["name"].startswith(prefix):
            print("platform=%s Simulator,name=%s,OS=%s" % (platform, d["name"], rt["version"]))
            raise SystemExit
'
}
resolve_destination iOS iPhone   # → platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0
```

Device prefixes: `iPhone` for an iOS scheme (`iPad` when the target's `TARGETED_DEVICE_FAMILY` is `"2"` — iPad
only), `Apple Watch` for watchOS, `Apple Vision Pro` for visionOS. `xcrun simctl list` can be slow on the first call
— give it a generous timeout rather than treating a slow response as a hang. Default to `platform=macOS` when the
project's target platform can't be determined and the scheme builds for macOS at all — it needs no simulator boot
and is the fastest destination to verify against.

**Empty result = no usable runtime.** For **visionOS** (its simulator runtime is a separate Xcode component that is
often not installed) don't fail and don't download anything: confirm the scheme compiles with `xcodebuild
build-for-testing -scheme <AppName>-visionOS -destination 'generic/platform=visionOS Simulator'` and report that
scheme **"unverified (no visionOS simulator runtime installed)"** — never as passed, never as 0 tests. For iOS/watchOS
an empty result is a real environment problem: report "no <platform> simulator runtime installed" for that scheme
(also unverified, named as such), not a test failure.

**`-only-testing:` selector**, from `[selector]` (the test target/bundle name — usually `<Product>Tests` — must
be known or discoverable via `xcodebuild -list`'s "Targets" section or the project file):

| What the user wants | Flag |
|---|---|
| Everything | *(omit `-only-testing:`)* |
| One suite/class | `-only-testing:<TestTarget>/<Suite>` |
| One method in one suite | `-only-testing:<TestTarget>/<Suite>/<test>` |
| Whole test target/bundle | `-only-testing:<TestTarget>` |
| Several disjoint scopes | repeat `-only-testing:<...>` once per scope (standard `xcodebuild` behavior — each
  flag adds to the set of tests run) |
| Excluding a scope while running the rest | `-skip-testing:<TestTarget>/<Suite>/<test>` |

## Step 4 — Run

**Package:**

```bash
swift test [--filter '<regex>' ...] [--skip '<regex>' ...]
```

Do not add `--xunit-output`: verified on this toolchain (Swift 6.4) that it is **not a reliable structured-output
path** for either framework — see Known quirks. Parse the console output directly instead (Step 5).

**App**, always with a fresh `-resultBundlePath` (an existing path at that location makes `xcodebuild` fail
outright — delete it first):

```bash
rm -rf <path>.xcresult
xcodebuild test -scheme '<scheme>' -destination '<destination>' \
  [-only-testing:<...> ...] [-skip-testing:<...> ...] \
  -resultBundlePath <path>.xcresult
```

Use a scratch path under a temp directory (e.g. `$TMPDIR/iru-swift-test-<timestamp>.xcresult`), not a path
inside the repository, so repeated runs never collide with a committed file and nothing test-run-specific gets
left behind for the user to clean up.

## Step 5 — Parse the results

**Package** — parse the console output (there is no structured reporter for `swift test`):

- XCTest lines: `Test Case '-[<Target>.<Suite> <test>]' passed (<N> seconds).` /
  `... failed (<N> seconds).`, with the failure's message on the **preceding** line:
  `<file>:<line>: error: -[<Target>.<Suite> <test>] : <message>`. Per-suite/overall totals:
  `Executed <N> tests, with <F> failures (<U> unexpected) in <T> (<T>) seconds` (one line per suite and one for
  `'All tests'`/`'Selected tests'`).
- Swift Testing lines: `✔ Test <name>() passed after <N> seconds.` / `✘ Test <name>() failed after <N> seconds
  with <M> issue(s).`, with the failure detail on a preceding line: `✘ Test <name>() recorded an issue at
  <file>:<line>: <message>`. Overall total: `✔ Test run with <N> tests in <M> suites passed after <T> seconds.` /
  `✘ Test run with <N> tests in <M> suites failed after <T> seconds with <I> issue(s).`
- **Zero-match case**: an unmatched `--filter` prints only `Building for debugging...` / `Build complete!` and a
  single `warning: No matching test cases were run` — **no** `Test Case`/`Executed`/`Test run with` lines appear
  at all, and the process **exits 0**. Detect this by the absence of any `Executed <N> tests` / `Test run with`
  line (or by matching that exact warning text) and report it as **"selector `<x>` matched no tests"**, never as
  "0 passed, 0 failed" or a clean pass — verified: `swift test --filter 'NoSuchTestXYZ'` produced exactly this
  output and exit code 0.
- The overall run's exit code is non-zero (`1`) whenever either framework reports a failure, and `0` when both
  pass (or when nothing ran at all — see above) — verified with a mixed XCTest+Swift Testing suite containing one
  passing and one failing test in each framework.

**App** — parse the `.xcresult` bundle with `xcresulttool` (Xcode 16+; this machine's Xcode 27 /
`xcresulttool` version 25115 confirmed the shapes below):

```bash
xcrun xcresulttool get test-results summary --path <path>.xcresult
```

returns (verified shape):

```json
{
  "passedTests": 2, "failedTests": 2, "skippedTests": 0, "expectedFailures": 0,
  "totalTestCount": 4, "result": "Failed",
  "testFailures": [
    { "testName": "exampleFailing()", "targetName": "DemoTests",
      "testIdentifierString": "exampleFailing()",
      "failureText": "Expectation failed: 1 + 1 == 3\n1 + 1 → 2" },
    { "testName": "testFails()", "targetName": "DemoTests",
      "testIdentifierString": "DemoXCTests/testFails()",
      "failureText": "XCTAssertEqual failed: (\"2\") is not equal to (\"3\")" }
  ]
}
```

Use `passedTests`/`failedTests`/`skippedTests`/`totalTestCount` for the headline counts, `result` for
Passed/Failed, and each `testFailures[]` entry's `testName` (or `testIdentifierString` when it disambiguates a
same-named method in different suites) plus the **first line** of `failureText` for the failing-test list — never
the full `failureText` if it's long.

For a full pass/fail name list beyond just failures (e.g. to confirm which tests ran when scoping), use:

```bash
xcrun xcresulttool get test-results tests --path <path>.xcresult
```

which returns a `testNodes` tree (`Test Plan` → `Unit test bundle` → `Test Suite` → `Test Case`, each with
`name`, `nodeIdentifier`, and `result: "Passed"|"Failed"`); walk it recursively (e.g. with `python3 -m json.tool`
piped into a small recursive-descent script, or `jq '.. | objects | select(.nodeType == "Test Case") |
{name, result}'`) and collect every `Test Case` node instead of re-deriving names from the summary alone.

- **Zero-match case**: an unmatched `-only-testing:` selector makes `xcodebuild test` print `** TEST SUCCEEDED
  **` and exit **0**, with every suite reporting `Executed 0 tests, with 0 failures`; the `.xcresult` bundle's
  `test-results summary` then reports `"totalTestCount": 0` and `"result": "unknown"` (not `"Passed"`) —
  verified with `-only-testing:DemoTests/DemoXCTests/testNoSuchTest`. Detect via `totalTestCount == 0` (or
  `result == "unknown"`) and report it as **"selector `<x>` matched no tests"**, never as a passing run.
- On Xcode < 16 (not available on this machine to verify), `get test-results summary`/`tests` don't exist yet —
  fall back to the deprecated `xcrun xcresulttool get --format json --path <path>.xcresult` object dump and parse
  its `issues.testFailureSummaries`/`metrics` fields instead; treat this fallback path as unverified until
  exercised against an actual pre-16 toolchain.

## UI run (`ui` selector)

Follows the UI contract (`iru-swift-code-one-task`'s `reference/ui-contract.md` §6–§8 and `ui-implementation.md`):
component tests render with `ImageRenderer`/native capture in the unit-test target (or a package's test target),
screen and flow tests are XCUITests, and every capture goes through the shared `UIScreenshots.swift` helper, which
writes `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` under `UI_SCREENSHOTS_DIR` **and** attaches it to
the test as `<kind>~<Id>~<file>`.

1. **Settings.** Read `.ui-quality.yml` at the repository root (`accessibility: enforce|report|off|none`, `directions`,
   `themes`; a missing file means `enforce`, `[ltr, rtl]`, `[light, dark]`) — the tests read it themselves; this skill
   reports the level applied. Resolve `UI_SCREENSHOTS_DIR`: the environment's value if set (absolute), else
   `<repository root>/.ui-screenshots`. On a **full** UI run (nothing after `ui`) clear it first — `rm -rf
   "$UI_SCREENSHOTS_DIR" && mkdir -p "$UI_SCREENSHOTS_DIR"` — so it holds exactly this run; a scoped run never
   clears it. Never commit it (`.ui-screenshots/` is in `.gitignore`; add it if missing).
2. **Lock** (app path only): unless `lock: held`, take `.simulator-test.lock` exactly as
   `iru-swift-code-one-task-group` Step 3.3 does (`mkdir .simulator-test.lock`, an `owner` file with this run and
   `date -u`, poll every ~30 s while held, break a lock older than 60 minutes, give up after ~45 minutes as
   **unverified — blocked by a concurrent simulator run**) and release it (`rm -f .simulator-test.lock/owner && rmdir
   .simulator-test.lock`) on every exit path. A macOS app takes it too — XCUITest drives the real desktop. A package
   needs no lock.
3. **Run.**
   - **Package** (a Swift package with SwiftUI components): `UI_SCREENSHOTS_DIR="$UI_SCREENSHOTS_DIR" swift test
     [--filter '<selector>']` — runs on the macOS host; the helper writes the files directly.
   - **App**: per in-scope scheme (Step 3), `TEST_RUNNER_UI_SCREENSHOTS_DIR="$UI_SCREENSHOTS_DIR" xcodebuild test
     -scheme '<scheme>' -destination '<destination>' [-only-testing:<…>] -skip-testing:CoreTests -resultBundlePath
     <path>.xcresult` (a full run: the scheme's `<AppName>Tests-<P>` and `<AppName>UITests-<P>` targets; `xcodebuild`
     strips the `TEST_RUNNER_` prefix and hands `UI_SCREENSHOTS_DIR` to the unit-test host and the UI-test runner).
     Then recover every capture the test process couldn't write itself (a sandboxed macOS host app, a device) from
     the result bundle:

     ```bash
     EXPORT="$(mktemp -d)"
     xcrun xcresulttool export attachments --path <path>.xcresult --output-path "$EXPORT"
     python3 -I - "$EXPORT" "$UI_SCREENSHOTS_DIR" <<'PY'
     import collections, json, os, re, shutil, sys
     export, out = sys.argv[1], sys.argv[2]
     valid = re.compile(r'^(component|screen|flow)/[^/]+/[a-z0-9]+(?:-[a-z0-9]+)*--(?:ltr|rtl)--'
                        r'(?:light|dark|high-contrast)(?:--[a-z0-9]+(?:-[a-z0-9]+)*)*\.png$')
     copied, malformed, a11y = 0, [], collections.Counter()
     for test in json.load(open(os.path.join(export, 'manifest.json'))):
         for a in test['attachments']:
             name = re.sub(r'_\d+_[0-9A-Fa-f-]{36}(\.\w+)?$', '', a['suggestedHumanReadableName'])
             name = re.sub(r'\.(png|txt)$', '', name)
             if name.startswith('a11y~'):                 # a11y~<Screen>~<rule>
                 a11y[name.split('~')[-1]] += 1
                 continue
             if not re.match(r'^(component|screen|flow)~', name):
                 continue                                 # failure screenshots, logs
             rel = name.replace('~', '/') + '.png'
             if not valid.match(rel):
                 malformed.append(rel); continue
             os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
             shutil.copyfile(os.path.join(export, a['exportedFileName']), os.path.join(out, rel)); copied += 1
     print(json.dumps({'collected': copied, 'malformed': malformed, 'a11y': dict(a11y)}))
     PY
     rm -rf "$EXPORT"
     ```

     `export attachments` (Xcode 16+) writes each attachment as `<UUID>.<ext>` plus a `manifest.json` whose
     `suggestedHumanReadableName` is the attachment name with `_<n>_<UUID>` appended — verified with Xcode 27.1 on an
     iOS 27 simulator run, where the copies were byte-identical to the files the helper wrote directly.
   - **No simulator runtime** for a scheme (typically visionOS), **no macOS host / Command Line Tools only**, or a
     macOS UI test that can't get the Automation/Accessibility permission or a logged-in desktop session → that
     scheme's UI run is **unverified** with the reason — never skipped silently, never counted as passed.
4. **Validate** every file under `$UI_SCREENSHOTS_DIR` against the same pattern (`find "$UI_SCREENSHOTS_DIR" -type f`):
   anything outside `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png` is reported as malformed.
5. **Accessibility**: issues come from the `a11y~<Screen>~<rule>` attachments the audit tests add (rule = the
   `XCUIAccessibilityAuditType`: `contrast`, `hit-region`, `dynamic-type`, `text-clipped`, …) and, at `enforce`, from
   the failing audit tests in the summary. Report counts and rule ids — never the raw issue descriptions.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 6 — Report

- **All passed**: state the counts (package: totals from the last `Executed`/`Test run with` lines; app:
  `passedTests`/`totalTestCount`) briefly — no per-test list.
- **Selector matched no tests** (package or app zero-match case above): report plainly as `selector '<x>' matched
  no tests`, never as `0 passed, 0 failed` or a clean pass — this is the one case both runners actively hide
  behind a success exit code.
- **Failures**: list each failing test's name (qualified with its suite/target when that disambiguates), the
  first line of its assertion/expectation message, and — for the app path — point to the `.xcresult` bundle path
  for the full detail instead of dumping it.
- Do not attempt to fix failing tests or modify source/test code unless the user asks for that as a next step.
- **UI run**: test pass/fail as above, then the screenshot count per kind (`component`/`screen`/`flow`), malformed
  names, accessibility issues (count + rule ids, and whether they failed the run) with the `.ui-quality.yml` level
  applied, whether `$UI_SCREENSHOTS_DIR` was cleared (full run) or kept (scoped), how many captures came from the
  `.xcresult`, and every scheme reported unverified with its reason.
- **Unverified schemes**: a per-platform scheme whose runtime is missing (typically visionOS) is listed as
  "unverified (no visionOS simulator runtime installed)" with its `build-for-testing` result — the other schemes'
  counts still stand.
- **Warn explicitly**: an app-path run needs a full Xcode install (not just Command Line Tools) and, for a
  simulator destination, a matching simulator runtime already downloaded — a missing runtime fails at destination
  resolution, before any test runs, and looks unrelated to the test code itself.

## Known quirks / verification notes

All of the following were verified locally in `$TMPDIR/iru-verify/swift/test/` (Xcode 27.0, Swift 6.4,
`xcresulttool` version 25115) against a `swift package init --type library` scaffold with one Swift Testing
`@Test` function and one XCTest `XCTestCase` in the same test target, each with one passing and one failing test:

- **`swift test --xunit-output <path>.xml` does not do what its name/help text implies on this toolchain.** With
  both a Swift Testing test and an XCTest test present, it wrote only `<path>-swift-testing.xml` (the Swift
  Testing framework's own xUnit report) and never wrote the requested `<path>.xml`. With XCTest tests only (the
  Swift Testing test file removed), it **still** wrote only `<path>-swift-testing.xml`, this time containing an
  empty 0-test `<testsuite>` — the XCTest results were not captured in any xUnit file at all. Do not rely on
  `--xunit-output` for XCTest results in a package; parse the console output (Step 5) instead. The `-swift-testing`
  suffix and this behavior may be specific to Swift 6.4's SwiftPM; re-verify if targeting an older/newer toolchain.
- **An unmatched `swift test --filter` pattern is a silent, exit-0 pass** — `warning: No matching test cases were
  run` with no per-test output at all. This is the exact trap the task calling for this skill described; Step 5
  above is the detection.
- **An unmatched `xcodebuild -only-testing:` selector is the same trap on the app path** — `** TEST SUCCEEDED **`,
  exit 0, `totalTestCount: 0` in the xcresult summary.
- **Repeated `--filter` flags on `swift test` are OR'd, not AND'd** — verified with `--filter testPasses --filter
  'example\(\)'`, which ran one test matched by each pattern independently (one from XCTest, one from Swift
  Testing), not their intersection.
- **`-resultBundlePath` requires a path that does not already exist** — a second `xcodebuild test` run reusing the
  same `.xcresult` path fails immediately; always `rm -rf` it first or use a fresh (e.g. timestamped) path.
- **A macOS desktop app (`iru-setup-macos-app` scaffold: `CODE_SIGN_STYLE: Automatic`, empty `DEVELOPMENT_TEAM`)
  tests with plain `xcodebuild test` — no signing overrides needed.** Verified 2026-10-16 (Xcode 27.1) on copies of
  the `swiftui-mvvm` and `appkit-mvc` scaffolds (App Sandbox on), each with a fresh `-derivedDataPath`:
  `xcodebuild test -project MyMacApp.xcodeproj -scheme MyMacApp -destination 'platform=macOS'
  -skip-testing:MyMacAppUITests` → `** TEST SUCCEEDED **`, exit 0 (also with `-enableCodeCoverage YES`); Xcode signs
  with "Sign to Run Locally". Adding `CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=` (what the
  scaffold contract's "All tests" row passes) gave the identical result and identity, so this skill doesn't add them.
  Not verified: a project whose `DEVELOPMENT_TEAM` names a real team with Automatic signing — that needs the team's
  development certificate in the keychain; on a machine without it, pass those three overrides (only for a
  `platform=macOS` destination; they ad-hoc sign, so a test that depends on a real team identity/entitlement, e.g.
  iCloud or a keychain access group, would not run as in production).
- **Per-platform schemes and `OS=` destinations (`iru-setup-apple-app` scaffold)** — verified 2026-10-16 with Xcode
  27.1 (iOS 18.5/26.4/27.0 and watchOS 11.5/26.5/27.0 runtimes, no visionOS runtime) on copies of the scaffold
  (`$TMPDIR/iru-verify/apple/iru-setup-swift-github-workflows/`): `xcodebuild -list` printed `Core`, `MyApp-iOS`,
  `MyApp-visionOS`, `MyApp-watchOS`; `resolve_destination` returned `platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0`,
  `platform=iOS Simulator,name=iPad Pro 13-inch (M5),OS=27.0` (`iPad`), `platform=watchOS Simulator,name=Apple Watch
  Series 12 (46mm),OS=27.0`, and nothing for visionOS; `MyApp-iOS` 11/11 and `MyApp-watchOS` 11/11 (each incl. its
  UI test), UIKit `MyApp-iOS` with `-skip-testing:MyAppUITests-iOS` 17/17, `MyApp-visionOS` `build-for-testing`
  against `generic/platform=visionOS Simulator` succeeded. `-destination 'platform=visionOS Simulator,name=Apple
  Vision Pro'` without a runtime fails with "Unable to find a device matching the provided destination specifier".
- **One `xcodebuild test` run in this environment crashed while finalizing diagnostics** (`Fatal error: Unable to
  read directory contents for .../TestResults.xcresult/Staging/.../<n>.logarchive/68`, exit 133) during log
  staging under a `$TMPDIR`-nested path, yet the `.xcresult` bundle was still fully written and
  `xcresulttool get test-results summary` read it back correctly; a second, otherwise-identical run right
  afterwards completed cleanly with no crash. Treat an `xcodebuild test` non-zero/crash exit as inconclusive until
  checking whether the `.xcresult` bundle exists and parses — don't assume the tests themselves failed from the
  process exit code alone on the app path.
- **`xcodebuild -list` with no `-project`/`-workspace` auto-resolves** the sole project/workspace (or, for a
  SwiftPM package opened directly, the package itself) found in the working directory and prints its scheme(s) —
  confirmed against the package scaffold (`Information about workspace "Demo": Schemes: Demo`). Pass
  `-project`/`-workspace` explicitly only when more than one exists.
- **`get test-results summary`/`get test-results tests` need Xcode 16+** (Apple introduced them as the successor
  to the deprecated `xcresulttool get --format json --path ...` object dump); only Xcode 27 was available on this
  machine, so the pre-16 legacy fallback noted in Step 5 is unverified.
- **UI run (verified 2026-10-16, Xcode 27.1, Swift 6.4)**: in a throwaway SwiftPM package
  (`$TMPDIR/iru-verify/swift/iru-swift-code-one-task/Probe`), the `UIScreenshots.swift` helper wrote the contract
  files under `swift test` on macOS and under `xcodebuild test -scheme Probe -destination 'platform=iOS
  Simulator,name=iPhone 17,OS=27.0'` with `TEST_RUNNER_UI_SCREENSHOTS_DIR` set (the simulator process writes host
  paths); `xcrun xcresulttool export attachments --path R.xcresult --output-path <dir>` exported both the
  `XCTAttachment` and the Swift Testing `Attachment.record` captures, and the script above rebuilt the same tree
  byte-for-byte (`collected=5 malformed=0`). Not run here: an XCUITest target (no XcodeGen installed), a macOS UI
  test, a device — their attachment path is the same `.xcresult` export, so it is unverified rather than assumed.
- **`swiftlint`/`xcodegen`/`tuist`/`swiftformat` install checks are out of scope for this skill** (it doesn't
  invoke any of them), but the app-path project-generation step in Step 1 depends on `xcodegen`/`tuist` being
  installed when only `project.yml`/`Project.swift` exists without a generated `.xcodeproj`/`.xcworkspace` — this
  machine has neither installed (no `brew`), so that specific branch is documented but unverified locally.
