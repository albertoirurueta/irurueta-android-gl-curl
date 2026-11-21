---
name: iru-kmp-test
description: "/iru-kmp-test [scope] runs a Kotlin Multiplatform project's Android host/JVM, Kotlin/Native iOS simulator and iosApp XCTest tests; scope is a class, Class#method, pattern, test file path, module or source set. Optional lines: target:, module:, destination:, lock: held; `ui` runs UI tests. iOS stages are unverified without macOS. Report-only."
model: haiku
allowed-tools: Read Write Bash(./gradlew *) Bash(python3 *) Bash(xcodebuild *) Bash(xcrun *) Bash(xcode-select *) Bash(xcodegen *) Bash(uname *) Bash(command -v *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(rm -rf *iru-kmp-test-*) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Bash(/usr/libexec/java_home *)
---

# KMP Test

Run a Kotlin Multiplatform project's tests on each target it actually has, scoped to what the user asked for, and
report a compact per-target pass/fail summary parsed from JUnit XML (Gradle) and `.xcresult` (Xcode). This skill
only runs tests and reports results — it never fixes failures or modifies source/test code.

## Step 0 — Resolve inputs

A single positional `<scope>` plus optional `key: value` lines; explicit keys win over inference:

- `target: android|ios|all` — default `all`. `android` runs the JVM stages only (Android host tests, `jvmTest`,
  the Android app's unit tests); `ios` runs the iOS stages only (Kotlin/Native simulator tests and the iosApp XCTest
  bundle). A fast caller-side check (e.g. a coding skill's per-task compile-and-test loop) passes `target: android`;
  group validation leaves the default.
- `module: <gradle path>` — restrict to one module (`:shared`, `:androidApp`, `:composeApp`).
- `destination: <xcodebuild destination>` — override the simulator chosen in Step 5.
- `lock: held` — the caller (e.g. `iru-kmp-code-one-task-group`) already holds `.integration-test.lock`; don't
  acquire or release it here (Step 4).
- `ui: yes` (or a leading `ui` word in `<scope>`, e.g. `ui GreetingScreenTest`) — a **UI run** (Step 5a). The rest
  of the scope and the `target:` key keep their meaning; `ui` with no further scope is a **full UI run**.

`<scope>` shapes:

| Scope | Effect |
|---|---|
| none | every test task of every in-scope target |
| `FooTest`, `FooTest#bar`, `*ServiceTest`, `com.example.pkg.*` | Gradle `--tests` filter (Step 3) on every Gradle test task that runs; for the XCTest stage, see Step 5 |
| path under `src/commonTest/` | the owning module, every target |
| path under `src/androidHostTest/`, `src/androidUnitTest/`, `src/test/` (Android app), `src/jvmTest/` | Android/JVM only |
| path under `src/iosTest/`, `src/appleTest/`, `src/nativeTest/`, `src/ios*Test/` | Kotlin/Native iOS only |
| path under `iosApp/` (e.g. `iosApp/iosAppTests/FooTests.swift`) | the XCTest stage only |
| `:shared`, `shared`, `androidApp` | that module, every target it has |
| `commonTest`, `androidHostTest`, `iosTest`, … | the same mapping as the matching path |

A path's class name (file stem) becomes the `--tests '*.<Stem>'` filter.

## Step 1 — Detect the layout and verify prerequisites

```bash
grep -nE 'include\(' settings.gradle.kts settings.gradle 2>/dev/null
grep -lE 'kotlin\("multiplatform"\)|kotlinMultiplatform|org\.jetbrains\.kotlin\.multiplatform' */build.gradle.kts */build.gradle 2>/dev/null
grep -lE 'com\.android\.application|androidApplication' */build.gradle.kts */build.gradle 2>/dev/null
ls -d iosApp/*.xcodeproj iosApp/*.xcworkspace iosApp/project.yml 2>/dev/null
```

- **KMP modules**: every included module applying the Kotlin Multiplatform plugin (`shared/` in this catalog's
  layout; `composeApp/` in the JetBrains wizard's single-module layout, which is *both* the KMP module and the
  Android application). **Android app modules**: those applying `com.android.application` (`androidApp/`, or
  `composeApp/` again). **iOS host app**: `iosApp/` with an `.xcodeproj`/`.xcworkspace` or an XcodeGen
  `project.yml`.
- No KMP module at all → stop: this isn't a KMP project (point to `iru-android-test`/`iru-java-test`).

Prerequisites — check before invoking Gradle; a missing one fails in a way that looks like a test problem:

- **`JAVA_HOME`** on a JDK the build accepts (17 or 21 for current AGP/Kotlin; the machine default may be newer than
  supported). On macOS `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` when unset or wrong.
- **`ANDROID_HOME`/`ANDROID_SDK_ROOT`, or `local.properties` `sdk.dir`** — otherwise configuration fails with
  `SDK location not found` before any test runs (the iOS tasks too, since every module configures).
- **`./gradlew`** at the repository root — never fall back to a bare `gradle`.
- **iOS stages only** — all of: `uname -s` is `Darwin`; `xcodebuild -version` succeeds and `xcode-select -p` points
  into an `Xcode.app` (Command Line Tools alone can't run simulators); `xcrun simctl list runtimes` lists an iOS
  runtime. Any missing → the iOS stages are **unverified (<what's missing>)** — never "passed", never "0 tests" —
  and the Android stages still run and report normally.

## Step 2 — Discover the test tasks

Never hardcode one plugin's names — list them per module (one invocation per module; configuration is the slow
part, so reuse the output for the whole run):

```bash
./gradlew :<module>:tasks --all > "$TMPDIR/iru-kmp-test-tasks-<module>.txt"
grep -E '^(testAndroidHostTest|testDebugUnitTest|jvmTest|iosSimulatorArm64Test|iosX64Test|allTests)( |$)' "$TMPDIR/iru-kmp-test-tasks-<module>.txt"
```

| Stage | Task (first that exists) |
|---|---|
| Android host tests of a KMP module | `testAndroidHostTest` (AGP KMP library plugin with `withHostTest {}` — the name may follow a custom host-test compilation name, so also accept any `test<Name>HostTest`), else `testDebugUnitTest` (`androidTarget()` + `com.android.library`) |
| JVM target | `jvmTest` (only when a `jvm()` target exists) |
| Android app module | `testDebugUnitTest` |
| Kotlin/Native iOS | `iosSimulatorArm64Test` on Apple silicon (`uname -m` = `arm64`), `iosX64Test` on Intel; device targets (`iosArm64`) have no runnable test task |

Use the explicit per-target tasks, not `allTests`: `allTests` is an aggregate (no `--tests` filter of its own) and
hides which target a failure came from. A KMP module whose Android host tests aren't enabled (no `withHostTest {}`
→ no `testAndroidHostTest`) has its `commonTest` exercised only on the other targets — say so in the report rather
than implying Android coverage of shared tests.

## Step 3 — Run the Gradle stages

**Android/JVM stages** (any host, no lock):

```bash
./gradlew :shared:testAndroidHostTest [--tests '<pattern>'] :androidApp:testDebugUnitTest [--tests '<pattern>'] --continue
```

- **`--tests` binds to the task immediately before it** — verified: `./gradlew :shared:testAndroidHostTest
  :shared:iosSimulatorArm64Test --tests '*.GreeterTest.greets'` filtered only the iOS task and ran the whole Android
  suite. Repeat `--tests '<pattern>'` after **every** test task it should filter.
- Pattern mapping is Gradle's: one class `'*.FooTest'`, one method `'*.FooTest.bar'` (from `FooTest#bar`), a package
  `'com.example.pkg.*'`; several patterns → repeat `--tests` (each adds an inclusion). Kotlin backtick test names
  with spaces work as-is inside the quotes.
- `--continue` so a failing Android stage doesn't stop the next one from running.
- **Unmatched filter on a JVM task fails the build**: `No tests found for given includes: [<pattern>](--tests
  filter)`, exit 1 (verified on `testAndroidHostTest`). Report it as **"no matching tests for `<pattern>` in
  `:<module>:<task>`"** — not a build failure and not "0 passed". When a class-name scope exists in only some
  modules' test trees, add the filter only to the tasks of the modules that contain it (`find */src/*Test* -name
  '<Stem>.kt'`), so the other modules don't fail on it.
- Don't add `clean`; retry once with `--no-configuration-cache` if a configuration-cache error unrelated to the
  tests appears; `--offline` only after a prior online run populated the caches.

**Kotlin/Native iOS stage** — only when Step 1's iOS prerequisites hold, and only behind the lock (Step 4): the
task links a `test.kexe` and runs it on an iOS Simulator via `xcrun simctl spawn --standalone <device-udid>` (booting
a device standalone), so two concurrent runs contend for the same simulator.

```bash
./gradlew :shared:iosSimulatorArm64Test [--tests '<pattern>'] --continue
```

- **An unmatched `--tests` filter on the Kotlin/Native task exits 0** (`BUILD SUCCESSFUL`) and leaves **no**
  `TEST-*.xml` in `shared/build/test-results/iosSimulatorArm64Test/` (verified) — the "matched nothing" signal is
  the missing XML, never the exit code. Report **"no matching tests for `<pattern>` on iosSimulatorArm64"**.
- The first run downloads the Kotlin/Native toolchain into `~/.konan` (hundreds of MB) and links the test binary —
  minutes, not a hang. A run that sits on `iosSimulatorArm64Test` with no output for many minutes while its
  `test.kexe` process uses no CPU is a hang inside the test binary (verified: a test touching
  `UIDevice.currentDevice` hung under `simctl spawn --standalone`); stop it after ~10 minutes, release the lock,
  and report that test task as **timed out** naming the process — not as passed or failed.

## Step 4 — Serialize simulator stages behind the repository lock

Applies to the Kotlin/Native simulator task and the XCTest stage; skip it for Android/JVM-only runs and when the iOS
stages are unverified (nothing to run).

1. **`lock: held`** → the caller holds the lock; go straight to the runs.
2. Otherwise acquire it at the repository root (`git rev-parse --show-toplevel`, else the current directory) — a
   directory, because `mkdir` creates it or fails atomically:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with "iru-kmp-test", the scope, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Check `.integration-test.lock/` is in the root `.gitignore`; if not, say so in
     the report (this skill doesn't edit `.gitignore`).
   - **`HELD`** — say the run is waiting (naming the holder from `cat .integration-test.lock/owner`), re-run the
     `mkdir` roughly every 30 seconds, and never start a simulator run anyway.
   - **Stale**: `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory → its holder almost
     certainly died; report the owner file, break it (`rm -f .integration-test.lock/owner && rmdir
     .integration-test.lock`), acquire fresh, and say so.
   - **Wait timeout**: still held after ~45 minutes and not stale → the iOS stages are **unverified — blocked by a
     concurrent simulator/integration run**; the Android results still stand.
3. Run the Kotlin/Native task, then the XCTest stage (Step 5), one at a time, in the foreground.
4. **Release** as soon as the last simulator stage ends, on **every** exit path (passed, failed, timed out,
   interrupted): `rm -f .integration-test.lock/owner && rmdir .integration-test.lock`. Never release a lock taken
   under `lock: held`.

## Step 5 — iosApp XCTest stage

Runs when the iOS stages are in scope, an iOS test target exists (`iosApp/iosAppTests/` or a `*Tests` target in
`project.yml`/the scheme), and the scope isn't a Gradle-only class pattern that has no Swift counterpart.

- **Project**: use `iosApp/iosApp.xcodeproj` (or the `.xcworkspace` when CocoaPods made one). Only a
  `project.yml` → run `xcodegen generate --spec iosApp/project.yml` when `xcodegen` is on `PATH`; otherwise report
  the XCTest stage **unverified (project not generated, xcodegen not installed)** — this skill installs nothing.
- **Scheme**: `iosApp` by default; otherwise the sole non-test scheme from `xcodebuild -list -project <project>`.
- **Destination**: `destination:` if given, else the first available iPhone simulator:

  ```bash
  xcrun simctl list devices available -j | python3 -c "
  import json, sys
  for runtime, devices in json.load(sys.stdin)['devices'].items():
      if 'iOS' in runtime:
          for d in devices:
              if d['name'].startswith('iPhone'):
                  print(d['name']); sys.exit(0)
  "
  ```

- **Run** (fresh result bundle outside the repository; the project's pre-build phase runs `./gradlew
  :shared:embedAndSignAppleFrameworkForXcode`, so the Gradle/JDK prerequisites of Step 1 apply here too):

  ```bash
  rm -rf "$TMPDIR/iru-kmp-test-ios.xcresult"
  xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -destination 'platform=iOS Simulator,name=<device>' \
    [-only-testing:iosAppTests/<Suite>[/<test>]] \
    -resultBundlePath "$TMPDIR/iru-kmp-test-ios.xcresult"
  ```

- **Parse** exactly as `iru-swift-test`'s Step 5 (app path) does: `xcrun xcresulttool get test-results summary
  --path <bundle>` for `passedTests`/`failedTests`/`skippedTests`/`totalTestCount`/`testFailures[]`, and
  `totalTestCount == 0` (`result: "unknown"`) for an unmatched `-only-testing:` — which `xcodebuild` reports as `**
  TEST SUCCEEDED **`, exit 0. A crash exit with a readable bundle is inconclusive, not a failure — read the bundle.

## Step 5a — UI runs: screenshots and accessibility

Only for `ui: yes`. KMP UI tests are ordinary test classes in the stages above — `commonTest` Compose
Multiplatform tests run in the Android host task (under Robolectric) **and** in the iOS simulator task, `ui: native`
Compose tests in `androidApp`'s `testDebugUnitTest`, SwiftUI `ImageRenderer` tests in the XCTest bundle — so a UI run
is Steps 3–5 with these additions:

1. **Resolve the capture directory once**: `UI_SCREENSHOTS_DIR` from the environment if set (absolute), else
   `<repo root>/.ui-screenshots`. Export it for every command below; the scaffold's build scripts forward it to the
   JVM tests (`UI_SCREENSHOTS_DIR`), to the simulator (`SIMCTL_CHILD_UI_SCREENSHOTS_DIR` — `simctl` passes only
   `SIMCTL_CHILD_`-prefixed variables, verified), and `xcodebuild test` gets it as
   `TEST_RUNNER_UI_SCREENSHOTS_DIR=<dir>` (verified). A module whose build script doesn't forward it writes to its
   helper's default — say so if captures appear elsewhere.
2. **Full UI run only** (no scope beyond `ui`): `rm -rf "$UI_SCREENSHOTS_DIR"` before the first stage, so the
   directory holds exactly this run. A scoped run never clears.
3. **Force the test tasks to execute**: put `--rerun` after **every** Gradle test task (like `--tests`, it binds to
   the task before it — verified: `./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --rerun`
   re-ran only the iOS task and the Android captures were missing, restored from the build cache). Captures are
   side effects Gradle doesn't track, so an `UP-TO-DATE`/`FROM-CACHE` task writes none.
4. **Device flow tests** (`androidApp/src/androidTest`, a `connected*AndroidTest` task in `tasks --all`): run only
   when `adb devices` lists a device in `device` state — `./gradlew :androidApp:connectedDebugAndroidTest`, then move
   `androidApp/build/outputs/connected_android_test_additional_output/**/ui-screenshots/<kind>/<Id>/*.png` into the
   directory. No device → **unverified (no emulator/device attached)**. An XCUITest target in `iosApp` runs in Step
   5's `xcodebuild test` (same lock).
5. **Collect `.xcresult` attachments** after the XCTest stage: `xcrun xcresulttool export attachments --path
   "$TMPDIR/iru-kmp-test-ios.xcresult" --output-path "$TMPDIR/iru-kmp-test-attachments"` writes the files plus a
   `manifest.json`; copy every attachment whose `suggestedHumanReadableName` (minus any numeric suffix Xcode adds) is
   a contract path or file name ending in `--ios.png` into `<dir>/<kind>/<Id>/`.
6. **Validate and count** with the embedded parser:

   ```bash
   python3 - "$UI_SCREENSHOTS_DIR" <<'PY'
   import collections, os, re, sys
   root = sys.argv[1]
   seg = r"[a-z0-9]+(?:-[a-z0-9]+)*"
   name = re.compile(rf"^(component|screen|flow)/([A-Za-z][A-Za-z0-9]*|{seg})/((?:\d\d-)?{seg})--(ltr|rtl)--(light|dark|high-contrast|no-color)((?:--{seg})*)\.(png|txt)$")
   counts, platforms, bad = collections.Counter(), collections.Counter(), []
   for dirpath, _, files in os.walk(root):
       for f in files:
           rel = os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, "/")
           m = name.match(rel)
           if not m:
               bad.append(rel); continue
           counts[m.group(1)] += 1
           variants = m.group(6).split("--")[1:]
           platforms[variants[0] if variants and variants[0] in ("android", "ios") else "(none)"] += 1
   print("per kind:", dict(counts)); print("per platform:", dict(platforms))
   print(f"malformed ({len(bad)}):", *bad[:20], sep="\n  ")
   PY
   ```

   A KMP capture without an `android`/`ios` first variant is worth flagging: the same `<Id>` renders on both
   platforms, so unsuffixed names collide.
7. **Accessibility**: read `accessibility` from `<repo root>/.ui-quality.yml` (missing file or key → `enforce`) and
   report the level applied. Under `none` (opted out entirely) or `off` (rules apply, checks not run) the accessibility check is not applicable: report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) — never "unverified" and never a failure. Count violations from the results, never by dumping them: the scaffold's
   `checkUiAccessibility()` fails with `Accessibility violations {rule=count, …}` under `enforce` and prints
   `UI accessibility (report): {rule=count, …}` under `report` (in the JUnit XML's `<failure message>` /
   `<system-out>`); XCUITest `performAccessibilityAudit()` failures appear in the `.xcresult` `testFailures`
   (summarize by audit type); device ATF (`AccessibilityViewCheckException`) in the device run's XML. Report the
   total and the rule ids with counts; under `report` they are listed, never a failure.

## Step 6 — Parse the JUnit XML

Each Gradle test task writes one `TEST-*.xml` per class to `<module>/build/test-results/<task>/`. Kotlin/Native
results prefix the class name with the task (`iosSimulatorArm64Test.com.example.FooTest`) and suffix each test name
with the target (`greets[iosSimulatorArm64]`) — strip both so the same test reads identically across targets:

```bash
python3 - shared/build/test-results/testAndroidHostTest shared/build/test-results/iosSimulatorArm64Test \
  androidApp/build/test-results/testDebugUnitTest <<'PY'
import glob, os, re, sys
import xml.etree.ElementTree as ET

# argv: one or more "<module>/build/test-results/<task>" directories
for d in sys.argv[1:]:
    files = sorted(glob.glob(os.path.join(d, "TEST-*.xml")))
    task = os.path.basename(d.rstrip("/"))
    if not files:
        print(f"{d}: NO RESULTS (no TEST-*.xml - no matching tests, task skipped, or it never ran)")
        continue
    total = passed = failed = skipped = 0
    failures = []
    for path in files:
        for tc in ET.parse(path).getroot().iter("testcase"):
            total += 1
            # Kotlin/Native prefixes classname with "<task>." and suffixes name with "[<target>]"
            cls = re.sub(r"^" + re.escape(task) + r"\.", "", tc.get("classname", ""))
            name = re.sub(r"\[[A-Za-z0-9]+\]$", "", tc.get("name", ""))
            fail = tc.find("failure")
            if fail is None:
                fail = tc.find("error")
            if fail is not None:
                failed += 1
                lines = (fail.text or "").strip().splitlines()
                failures.append((f"{cls}#{name}", fail.get("message", ""), lines[0] if lines else ""))
            elif tc.find("skipped") is not None:
                skipped += 1
            else:
                passed += 1
    print(f"{d}: total={total} passed={passed} failed={failed} skipped={skipped}")
    for n, msg, first in failures:
        print(f"  FAIL {n}: {msg}\n    {first}")
PY
```

Pass only the directories of tasks that ran in this invocation. A Gradle test task that was `UP-TO-DATE` keeps its
previous XML, which still describes the current inputs — that's fine; but if the build failed before a task ran
(compile error), report the compile error instead of parsing a stale directory.

## Step 7 — Report

Per target, one line: Android host (`:<module>:<task>`), JVM, Android app, iOS simulator (Kotlin/Native), iosApp
XCTest — each **passed** (counts), **failed** (counts plus each failing `Class#test`, the assertion message, the first
stack line, and the XML/`.xcresult` path for the full trace), **no matching tests for `<pattern>`**, **timed out**,
**not applicable** (the project has no such target/test bundle), or **unverified (<reason>)** — non-macOS host, no
Xcode/simulator runtime, project not generated, or blocked by the lock. A test failing on one target only (e.g. iOS
but not Android) is worth calling out explicitly — it usually means an `actual` implementation differs. Mention the
lock only when it was anything other than an immediate acquire. For a UI run add: the capture directory and whether
it was cleared (full run) or not (scoped); screenshots per kind and per platform (`android`/`ios`); malformed names
(path list); accessibility violations (total + rule ids with counts) and the `.ui-quality.yml` level applied
(`enforce`/`report`/`off`/`none`); device flow tests run or **unverified (<reason>)**. Never paste raw Gradle/xcodebuild output, and never
fix tests or source code — that's a follow-up the user (or `iru-kmp-code-one-task`) drives.

## Known quirks / verification notes

Verified in `$TMPDIR/iru-verify/kmp/iru-kmp-test/` (October 2026, macOS on Apple silicon, Xcode 27.0, JDK 21, Gradle
9.8.1, Kotlin 2.4.21, AGP 9.4.1 with `com.android.kotlin.multiplatform.library` + `withHostTest {}`, a
`com.android.application` `androidApp`, `iosArm64` + `iosSimulatorArm64` targets):

- `./gradlew :shared:tasks --all` listed `testAndroidHostTest`, `iosSimulatorArm64Test`, `allTests` (no
  `iosArm64Test`, no `testDebugUnitTest` on the KMP module); `:androidApp` listed `testDebugUnitTest`.
- `commonTest` tests ran under both `testAndroidHostTest` and `iosSimulatorArm64Test`; XML landed in
  `shared/build/test-results/testAndroidHostTest/TEST-com.example.shared.GreeterTest.xml` and
  `shared/build/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.example.shared.GreeterTest.xml`
  (`classname="iosSimulatorArm64Test.com.example.shared.GreeterTest"`, `name="greets[iosSimulatorArm64]"`), the
  app's in `androidApp/build/test-results/testDebugUnitTest/`.
- With one failing and one `@Ignore`d common test, `:shared:allTests --continue` ran both targets and the Step 6
  parser reported `total=5 passed=3 failed=1 skipped=1` for each, with `java.lang.AssertionError: expected:<3> but
  was:<1>` (Android) and `kotlin.AssertionError: Expected <3>, actual <1>.` (iOS).
- Unmatched `--tests '*.NoSuchTest'`: `testAndroidHostTest` failed the build with `No tests found for given
  includes`; `iosSimulatorArm64Test` exited 0 and emptied its results directory (only `binary/` left).
- `--tests` placed once after two tasks filtered only the second; repeating it after each task filtered both.
- A Kotlin/Native test calling `UIDevice.currentDevice.systemName()` hung indefinitely under the task's
  `simctl spawn --standalone` run (0% CPU for 4+ minutes); the same tests without UIKit finished in seconds.
- UI runs (October 2026, Compose Multiplatform 1.12.2, Robolectric 4.17 `sdk=35`, Xcode 27.0, iPhone 18 Pro
  simulator): with `UI_SCREENSHOTS_DIR`/`UI_REPO_ROOT` set on `Test` tasks and `SIMCTL_CHILD_UI_SCREENSHOTS_DIR`/
  `SIMCTL_CHILD_UI_REPO_ROOT` on `KotlinNativeSimulatorTest` tasks, one `commonTest` `runComposeUiTest` class wrote
  `screen/GreetingScreen/<state>--<dir>--<theme>--android.png` from `testAndroidHostTest` and `…--ios.png` from
  `iosSimulatorArm64Test` (12 + 12 files); `xcodebuild test` with `TEST_RUNNER_UI_SCREENSHOTS_DIR` delivered the
  variable to an `ImageRenderer` XCTest, which wrote its PNGs there. Without per-task `--rerun`, a re-run after
  deleting the directory restored the Android task from the build cache and wrote no Android captures.
- **Unverified locally**: the `xcresulttool export attachments` collection (no XCUITest target in the sample),
  device flow tests (no emulator attached), the iosApp XCTest stage's `xcresulttool` parsing and zero-match
  behaviour (`xcodebuild test` itself ran in the UI verification above; the parsing commands are `iru-swift-test`'s,
  verified there on Xcode 27), the `androidTarget()` + `com.android.library` variant's
  `testDebugUnitTest` naming (taken from AGP's documented variant tasks), the `iosX64Test` Intel path, and the
  stale-lock/wait branches of Step 4 (the protocol is the catalog's shared one, as in `iru-python-test`).
