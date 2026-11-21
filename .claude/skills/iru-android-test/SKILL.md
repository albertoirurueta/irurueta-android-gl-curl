---
name: iru-android-test
description: "/iru-android-test [selector] runs an Android/Kotlin project's JUnit 4 unit tests via testDebugUnitTest; selector is a class, Class#method, wildcard, or a lib/app test path (module derived from it). Instrumented tests only on request with a device; `ui: jvm|device|all` runs UI tests. Report-only from the JUnit XML, never fixes tests."
model: haiku
---

# Android Test

Run the project's JUnit 4 unit tests through the Android Gradle Plugin (AGP), scoping the run to whatever the
user asked for, and report a compact pass/fail summary parsed from the generated JUnit XML. This skill only runs
tests and reports results — it does not fix failures or modify source/test code unless asked to as a follow-up.

## Step 0 — Resolve inputs

**Kotlin Multiplatform projects are out of scope.** If the Kotlin Multiplatform plugin (`kotlin("multiplatform")`/`org.jetbrains.kotlin.multiplatform`, directly or via a `libs.versions.toml` alias) is applied anywhere in the build — a KMP app's Android host module included — stop and hand the request to `iru-kmp-test` instead (or tell the caller to, when it isn't installed); this skill assumes a plain Android `lib`/`app` layout.

This skill takes a single positional `[selector]` argument (the same shape as `iru-java-test`'s selector), not
`key: value` args, unless the calling skill/prompt explicitly passes `key: value` lines (e.g. `module: app`) — in
that case honor them and skip the matching inference in Step 1/2.

- No argument → run the whole default module (`lib`) unscoped.
- A bare class simple name (`FooTest`), class+method (`FooTest#testSomething`), or wildcard/package pattern
  (`*ServiceTest`, `com.example.pkg.*`) → scope via Step 2's `--tests` mapping.
- A path (absolute or relative) under a module's `src/test`/`src/androidTest` tree → scope via Step 1's module
  table and Step 2's pattern.
- The prompt (not necessarily the selector itself) asking for instrumented/on-device/`connectedAndroidTest`
  tests → also run Step 4, honoring its device-attached gate.
- A `ui: jvm|device|all` line, or a selector starting with the word `ui` (`ui`, `ui OrderRowTest`) → a UI run
  (Step 4b) instead of the plain unit run; `ui` alone means `ui: all`. Any class/pattern after it scopes the run —
  a scoped UI run never clears the screenshot directory.

## Step 1 — Resolve the module and verify prerequisites

| Selector shape | Module |
|---|---|
| No selector | `:lib` (default) |
| Path containing `lib/src/test` or `lib/src/androidTest` | `:lib` |
| Path containing `app/src/test` or `app/src/androidTest` | `:app` |
| Bare class name/pattern, no path | search `*/src/test/**/<Name>*.{kt,java}` under the repo root; exactly one module matches → use it; several match → ask which, or default to `lib` and say so explicitly; none match → still default to `lib` and let Gradle's own "No tests found" (Step 3) surface the typo rather than guessing further |

Prerequisites — check these before invoking Gradle, since a missing one produces a confusing failure that looks
like a test problem but isn't:

- **`JAVA_HOME`** set and pointing at a JDK 17+ install — Gradle/AGP on this stack need 17+, and the machine's
  ambient/default `java` may be older or (on a recently-updated machine) newer than what's supported. On macOS,
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (or `-v 21`) if unset or wrong.
- **`ANDROID_HOME`** (or `ANDROID_SDK_ROOT`), or a `local.properties` with `sdk.dir=...` at the repo root —
  without one of these the build fails at configuration time with `SDK location not found`, before any test runs.
- **`./gradlew`** present and executable at the repo root — this skill never generates a wrapper; if missing,
  stop and report it rather than falling back to a bare `gradle` invocation.

## Step 2 — Build the `--tests` pattern

Map the selector to Gradle's built-in `Test` task `--tests` filter (which AGP's `testDebugUnitTest` inherits):

| What the user wants | `--tests` value |
|---|---|
| Everything in the module | *(omit `--tests`)* |
| One class | `--tests '*.FooTest'` (or the fully-qualified name if known) |
| One method | `--tests '*.FooTest.testSomething'` |
| Several classes/methods | repeat `--tests` once per pattern — Gradle ANDs multiple `--tests` flags as independent inclusions, unlike Maven Surefire's single comma-separated `-Dtest` value |
| Wildcard/package pattern | `--tests 'com.example.pkg.*'` |

## Step 3 — Run

```bash
./gradlew :<module>:testDebugUnitTest [--tests '<pattern>' ...] [--offline]
```

- Target `:lib:testDebugUnitTest` / `:app:testDebugUnitTest` specifically — not the bare `test` lifecycle task —
  so only the debug variant's unit tests build and run (the reference scaffold only turns on
  `enableUnitTestCoverage`/`enableAndroidTestCoverage` for `debug`, and building the `release` variant's tests too
  is pure overhead for this skill's purpose).
- **An unmatched `--tests` pattern FAILS the whole build**, not a silent zero: Gradle exits non-zero with
  `Execution failed for task ':lib:testDebugUnitTest'. > No tests found for given includes: [<pattern>](...)`.
  Detect this exact message and report it as **"no matching tests for `<pattern>`"** — a distinct outcome from
  both a clean run and a real compile/test failure. Do not report it as a build failure, and do not treat it as
  "0 passed, 0 failed" (that reads as a clean, intentionally-empty run, which this isn't).
- Do not add `clean` by default — an incremental re-run is what "run/re-run tests" normally means; add it only if
  the user asks for a clean run or a prior run's results look stale.
- `--offline`: only retry with this flag if a run fails on dependency resolution *and* a prior successful run
  already populated the Gradle/Robolectric caches (see Known quirks) — on a cold cache `--offline` just fails
  differently (missing artifacts) and hides the real network problem, so don't reach for it first.

## Step 4 — `connectedAndroidTest` (instrumented), only when explicitly asked

Run instrumented tests only when **both** hold:

1. the prompt explicitly asks for instrumented/on-device tests (a `src/androidTest` path in the selector alone is
   not enough — Step 1 still resolves the module for the unit-test run unless instrumented is explicitly asked
   for), and
2. `adb devices` lists at least one line, after the `List of devices attached` header, whose state is `device`
   (not `unauthorized`, `offline`, or an empty list) — no attached/booted device or emulator means skip this step
   and say so explicitly; never silently fall back to reporting only the unit-test results without noting that
   instrumented tests were requested but skipped.

```bash
adb devices
./gradlew :<module>:connectedDebugAndroidTest [--tests '<pattern>']
```

Results land under `<module>/build/outputs/androidTest-results/connected/**/*.xml` — same JUnit XML shape Step 5
parses; parse and report the same way, labeled as instrumented results.

## Step 4b — UI run (only for a `ui` selector)

The UI tests follow the catalog's UI contract: component and screen tests render on the JVM through Robolectric
and capture with Roborazzi through the module's `testing/UiScreenshots.kt` helper; flow tests run instrumented on
a device and write captures through AndroidX Test Storage (`DeviceUiScreenshots.kt`). Both read the same names.

1. **Resolve the directory and the level.** `UI_SCREENSHOTS_DIR` from the environment if set (absolute path),
   else `<repo root>/.ui-screenshots`; export it for every Gradle call below (the forked test JVMs inherit it).
   Read `.ui-quality.yml` at the repository root for `accessibility` (`enforce`/`report`/`off`/`none`; missing file or
   key = `enforce`). Under `none` (opted out entirely) or `off` (rules apply, checks not run) the accessibility check is not applicable: report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) — never "unverified" and never a failure. On a **full** UI run (no class/pattern given) delete only that screenshot directory's
   contents first (`rm -rf "$UI_SCREENSHOTS_DIR" && mkdir -p "$UI_SCREENSHOTS_DIR"`), so it holds exactly this run;
   a scoped run adds to what is there.
2. **JVM stage** (`ui: jvm` or `all`) — the same task as Step 3, scoped by any pattern given:
   ```bash
   UI_SCREENSHOTS_DIR="$UI_SCREENSHOTS_DIR" ./gradlew :<module>:testDebugUnitTest [--tests '<pattern>' ...]
   ```
   The helper forces Roborazzi to record, so no `-Proborazzi.test.record=true` is needed; a repository that opted
   into Roborazzi baselines keeps running its own `verifyRoborazziDebug` separately.
3. **Device stage** (`ui: device` or `all`) — only when `adb devices` lists a device in state `device` (Step 4's
   gate); otherwise report this stage **unverified (no device/emulator attached)** and continue with the JVM
   results. With a device:
   ```bash
   adb logcat -c
   ./gradlew :<module>:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.uiAccessibility=<level> \
     [-Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>]
   adb logcat -d -s AccessibilityValidator > <module>/build/outputs/ui-accessibility-logcat.txt
   ```
   Then copy every file under `<module>/build/outputs/connected_android_test_additional_output/**/ui-screenshots/`
   into `$UI_SCREENSHOTS_DIR`, keeping the path below `ui-screenshots/`; when that directory doesn't exist (the
   module doesn't set `useTestStorageService`), fall back to
   `adb pull /sdcard/googletest/test_outputfiles/ui-screenshots "$UI_SCREENSHOTS_DIR/.device"` and move its
   contents up one level. Never overwrite a JVM capture with a device one of the same name — report the clash.
4. **Validate and summarize** with this parser (`python3 - "$UI_SCREENSHOTS_DIR" '<module>/build/test-results/testDebugUnitTest/*.xml' '<module>/build/outputs/androidTest-results/connected/**/*.xml'`):
   ```python
   import collections, glob, os, re, sys
   import xml.etree.ElementTree as ET

   shots = sys.argv[1]            # UI_SCREENSHOTS_DIR
   results = sys.argv[2:]         # JUnit XML globs (unit and/or connected)
   seg = r"[a-z0-9]+(?:-[a-z0-9]+)*"
   name_re = re.compile(rf"^(component|screen|flow)/[^/]+/{seg}--(ltr|rtl)--(light|dark|high-contrast)(--{seg})*\.png$")
   per_kind, malformed = collections.Counter(), []
   for path in sorted(glob.glob(os.path.join(shots, "**", "*"), recursive=True)):
       if os.path.isfile(path):
           rel = os.path.relpath(path, shots).replace(os.sep, "/")
           m = name_re.match(rel)
           if m:
               per_kind[m.group(1)] += 1
           else:
               malformed.append(rel)

   a11y, failing = collections.Counter(), []
   logged = re.compile(r"^.*AccessibilityHierarchyCheckResult (ERROR|WARNING) (\w+).*$", re.M)
   thrown = re.compile(r"Reported by [\w.]*?\.(\w+Check)")
   for pattern in results:
       for path in glob.glob(pattern, recursive=True):
           root = ET.parse(path).getroot()
           out = (root.findtext("system-err") or "") + "\n" + (root.findtext("system-out") or "")
           lines = {m.group(0) for m in logged.finditer(out)}          # each distinct ATF result once
           for line in lines:
               level, check = logged.match(line).groups()
               a11y[f"{check}:{level.lower()}"] += 1
           for tc in root.iter("testcase"):
               for node in list(tc.findall("failure")) + list(tc.findall("error")):
                   text = (node.get("message") or "") + (node.text or "")
                   if "AccessibilityViewCheckException" in text or "AccessibilityCheckException" in text:
                       checks = sorted(set(thrown.findall(text)))
                       failing.append(f"{tc.get('classname')}#{tc.get('name')}: {', '.join(checks)}")
                       if not lines:                                  # device runs: results only in the failure
                           for check in checks:
                               a11y[f"{check}:error"] += 1

   print("screenshots:", dict(per_kind) or "none")
   print("malformed:", len(malformed))
   for rel in malformed[:20]:
       print("  MALFORMED", rel)
   print("accessibility results:", sum(a11y.values()), dict(a11y))
   print("tests failed by accessibility checks:", len(failing))
   for line in failing[:20]:
       print("  A11Y-FAIL", line)
   ```
   It counts captures per kind, lists names that don't match the contract, and counts Accessibility Test
   Framework results by `<CheckName>:<level>` from the Roborazzi lines on the test's stderr (`report` mode), or
   from the `AccessibilityViewCheckException` of a failing test (`enforce` mode, and Espresso/Compose checks on a
   device). In device `report` mode the results are only in the saved logcat file — count its lines naming a
   `…checks.<Name>Check` the same way. Verified against real output from a Robolectric/Roborazzi run (see Known
   quirks).

## Step 5 — Parse the JUnit XML results

Gradle/AGP write one `TEST-<classname>.xml` file per test class under
`<module>/build/test-results/testDebugUnitTest/` (unit tests) or
`<module>/build/outputs/androidTest-results/connected/**` (instrumented, Step 4). Parse every matching file with
`python3`'s standard-library `xml.etree.ElementTree` — no external dependency needed:

```python
import glob
import xml.etree.ElementTree as ET

total = passed = failed = skipped = 0
failures = []
for path in sorted(glob.glob("<module>/build/test-results/testDebugUnitTest/TEST-*.xml")):
    root = ET.parse(path).getroot()
    for tc in root.findall("testcase"):
        total += 1
        name, classname = tc.get("name"), tc.get("classname")
        fail, err, skip = tc.find("failure"), tc.find("error"), tc.find("skipped")
        if fail is not None or err is not None:
            failed += 1
            node = fail if fail is not None else err
            text_lines = (node.text or "").strip().splitlines()
            failures.append((f"{classname}#{name}", node.get("message", ""),
                              text_lines[0] if text_lines else ""))
        elif skip is not None:
            skipped += 1
        else:
            passed += 1

print(f"total={total} passed={passed} failed={failed} skipped={skipped}")
for name, message, first_line in failures:
    print(f"FAIL {name}: {message}\n  {first_line}")
```

Each `<testcase name="..." classname="...">` holds at most one of `<failure message="..." type="...">` /
`<error message="..." type="...">` (stack trace as element text) or a self-closing `<skipped/>`; a `<testcase>`
with none of those passed. This snippet was verified against hand-written sample files in
`$TMPDIR/iru-verify/android/gates/junit/` (`TEST-com.example.lib.FooTest.xml` with one passed, one `<failure>`
assertion, and one `<skipped/>`; `TEST-com.example.lib.BarTest.xml` with one `<error>` unchecked exception) — it
correctly counted `total=4 passed=1 failed=2 skipped=1` and extracted each failure's `classname#name`, its
`message` attribute, and the first stack-trace line.

If Step 3's build failed before producing any XML (compile error, or the unmatched-`--tests` case), report that
instead of trying to parse a stale or missing report directory.

## Step 6 — Report

- **All passed**: state the counts (`total`, module, and the pattern if scoped) briefly — no per-test list.
- **No matching tests** (Step 3's unmatched-`--tests` case): report plainly as `no tests matched '<pattern>' in
  :<module>`, never as `0 passed, 0 failed`.
- **Failures/errors**: list each failing test's `classname#name`, its assertion/exception message, and the first
  stack-trace line; point to `<module>/build/test-results/testDebugUnitTest/TEST-<classname>.xml` for the full
  trace instead of pasting it.
- **Instrumented run skipped** (not asked for, or no device attached): say so explicitly rather than silently
  reporting only unit-test results as if that were the whole answer.
- **UI run** (Step 4b): the JVM and device stages separately (pass/fail counts, or **unverified** with the reason),
  screenshots per kind (`component`/`screen`/`flow`), malformed names (each listed), accessibility results (total,
  per check id) and the tests failed by them, the `.ui-quality.yml` level applied, and whether the directory was
  cleared (full run) or added to (scoped run). Never paste screenshots, logcat or ATF dumps.
- **Warn explicitly**: a first run on a clean machine downloads Robolectric's `android-all` jars (can be hundreds
  of MB) and may take minutes — don't mistake a long first run for a hang; `ANDROID_HOME`/`local.properties` must
  already be correct or the build fails before any test executes; this skill never fixes failing tests or
  modifies source/test code, that is a follow-up the user (or `iru-android-code-one-task`) drives explicitly.

## Known quirks / verification notes

- **Unmatched `--tests` fails the build** (`No tests found for given includes: [...]`, exit 1) — this is Gradle's
  own long-documented `Test`-task contract (not scaffold-specific), but this skill's author did not run a live
  `./gradlew testDebugUnitTest` against a generated scaffold to reproduce the exact current wording — **unverified
  locally by this skill's author; exercised by the scaffold and workflows skills' own verification passes**.
- Gradle/AGP need **JDK 17+**; do not trust the machine's ambient/default `java` — set `JAVA_HOME` explicitly.
- `ANDROID_HOME`/`local.properties` missing is the most common "works on my machine" failure; check it before
  suspecting the test code itself.
- Robolectric downloads `android-all-<sdk>-robolectric-r0.jar` (one per configured SDK level) into the Gradle
  cache on first run — needs network, is slow; subsequent runs are cached and fast.
- Configuration cache (`org.gradle.configuration-cache=true` in the reference `gradle.properties`) can be
  incompatible with some third-party Gradle plugins/tasks; if a run fails with a configuration-cache-specific
  error unrelated to the tests themselves, retry once with `--no-configuration-cache` before treating it as a
  real test failure.
- **UI run, verified** in `$TMPDIR/iru-verify/android/ui/` (outside this repository) with AGP 9.4.1, Kotlin
  2.4.21, Gradle 9.8.1, Compose BOM 2026.09.00, Robolectric 4.17 and Roborazzi 1.76.0: Compose and Views
  Robolectric tests wrote `component/Greeting/default--{ltr,rtl}--{light,dark}.png` and
  `screen/MainActivity/typical-data--…png` through the helper, `UI_SCREENSHOTS_DIR` set on the `./gradlew` command
  line reached the forked test JVM, and Roborazzi's ATF check failed the test in `Error` mode and only logged in
  `LogOnly` mode; Step 4b's parser was run against those captures plus a hand-made malformed name and against the
  real stderr/failure text of that check. Robolectric `sdk=36` on JDK 21 failed with `IllegalAccessException …
  jdk.internal.access` (fixed with `jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")` in
  `testOptions.unitTests.all {}`); `sdk=35` needed nothing. **The device stage (`connectedDebugAndroidTest`, Test
  Storage pull, `uiAccessibility` runner argument, logcat) was compile-checked only — no emulator was run.**
- This skill's JUnit-XML parsing (Step 5) was verified against hand-written sample files under
  `$TMPDIR/iru-verify/android/gates/junit/` only, per this catalog's convention that gate-skill authors verify
  their own parsing logic against small fixtures rather than running the heavy Gradle build themselves — the live
  `./gradlew :lib:testDebugUnitTest` invocation and its real report path/shape are **unverified locally by this
  skill's author; exercised by the scaffold and workflows skills' own verification passes**. The report-path convention
  (`<module>/build/test-results/testDebugUnitTest/*.xml`) is taken directly from the reference repository's
  (https://github.com/albertoirurueta/irurueta-android-glutils) `sonar.junit.reportsPath` property in
  `lib/build.gradle.kts` (spelled `sonar.junit.reportPaths` — the key Sonar actually reads — in this catalog's
  `iru-setup-android-library`/`-app` scaffolds; the directory is the same either way).
