---
name: iru-java-test
description: "/iru-java-test [selector] runs a Java or Kotlin project's JUnit tests via Maven Surefire or the Gradle test task; selector is a class, Class#method, wildcard/package pattern or tag expression, optionally module-prefixed. Covers Spring Boot and Quarkus (missing Docker for Dev Services is an environment failure). Report-only."
model: haiku
allowed-tools: Read Bash(mvn *) Bash(./mvnw *) Bash(./gradlew *) Bash(gradle *) Bash(find *) Bash(grep *) Bash(ls *) Bash(python3 *) Bash(QUARKUS_DEVSERVICES_ENABLED=false *)
---

# Java Test

Run the project's JUnit tests through Maven Surefire or the Gradle `test` task, scoping the run to whatever the user
asked for. Works the same for Java and Kotlin sources (`src/test/java`, `src/test/kotlin`, or both). This skill only
runs tests and reports results — it does not fix failures unless asked to as a follow-up.

## Step 0 — Detect the build tool

From the repository root (or the module directory the user pointed at):

- `pom.xml` present → **Maven**. Use `mvn` exactly as below (an `./mvnw` wrapper may be used instead when the
  project ships one and `mvn` isn't installed).
- `settings.gradle(.kts)` or `build.gradle(.kts)` present (and no `pom.xml`) → **Gradle**. Prefer the wrapper
  (`./gradlew`, executable at the repository root) over a system `gradle`, so the project's pinned Gradle version is
  used; fall back to `gradle` only when no wrapper exists, and say so in the report.
- Both present → use whichever one the CI workflow (`.github/workflows/*.yml`) invokes; if that doesn't settle it,
  ask the user once.
- An Android Gradle plugin (`com.android.application`/`com.android.library`) applied → this is an Android project:
  use `iru-android-test` instead.
- The Kotlin Multiplatform plugin (`kotlin("multiplatform")`/`org.jetbrains.kotlin.multiplatform`) applied → this
  is a Kotlin Multiplatform project: use `iru-kmp-test` instead.

For a **multi-module** build, scope the run to the affected module(s) when the user (or calling skill) named them —
Maven `-pl <module> -am`, Gradle `:<module>:test` — rather than running every module's tests.

## Step 1 — Determine scope from the argument

The skill may be invoked with no argument (run everything) or with a selector describing what to run. Map the
selector to the correct parameter for the detected build tool.

### Maven (Surefire)

| What the user wants | Parameter | Example |
|---|---|---|
| Everything | *(none)* | `mvn test` |
| One test class | `-Dtest` | `mvn test -Dtest=FooTest` |
| One method in a class | `-Dtest` with `#` | `mvn test -Dtest=FooTest#testSomething` |
| Several methods in one class | `-Dtest` with `+` between method names after `#` | `mvn test -Dtest=FooTest#method1+method2` |
| Several classes | `-Dtest` comma-separated | `mvn test -Dtest=FooTest,BarTest` |
| Classes matching a name pattern | `-Dtest` with `*` wildcard | `mvn test -Dtest=*ServiceTest` |
| All classes in a package | `-Dtest` with the package path and wildcard | `mvn test -Dtest=com.example.pkg.**` |
| Excluding some classes/methods | `-Dtest` with `!` prefix (combine with other `-Dtest` entries via comma) | `mvn test -Dtest=!SlowTest` |
| Tests tagged with a JUnit 5 `@Tag` | `-Dgroups` | `mvn test -Dgroups="fast"` |
| Excluding a JUnit 5 tag | `-DexcludedGroups` | `mvn test -DexcludedGroups="slow"` |
| Tag boolean expression | `-Dgroups` with `&`, `|`, `!` | `mvn test -Dgroups="fast & !flaky"` |

Notes:
- The `-Dtest` value accepts the class simple name without the `.java`/`.kt`/`.class` extension.
- `#method` selection and `-Dgroups`/`-DexcludedGroups` tag filtering require JUnit 4.x, JUnit 5, or TestNG (not
  plain JUnit 3). Check the project's `pom.xml` for its test framework/dependency versions if unsure which
  syntax applies; JUnit 5 `@Tag` values map directly to `-Dgroups`/`-DexcludedGroups`, TestNG groups likewise.
- If the selector the user gave doesn't match any known test (typo in class/method name), Surefire fails the
  build with "No tests matching pattern...". Add `-DfailIfNoSpecifiedTests=false` only if the user explicitly
  wants a missing-selector to be a no-op rather than a failure — don't add it by default, since silently running
  zero tests is usually not what's wanted.

### Gradle (`test` task)

| What the user wants | Parameter | Example |
|---|---|---|
| Everything | *(none)* | `./gradlew test` |
| One test class (simple name) | `--tests` | `./gradlew test --tests 'FooTest'` |
| One test class (fully qualified) | `--tests` | `./gradlew test --tests 'com.example.pkg.FooTest'` |
| One method in a class | `--tests` with `.` (not `#`) | `./gradlew test --tests 'FooTest.testSomething'` |
| Several classes/methods | repeat `--tests` | `./gradlew test --tests 'FooTest' --tests 'BarTest'` |
| Classes matching a name pattern | `--tests` with `*` wildcard | `./gradlew test --tests '*ServiceTest'` |
| All classes in a package | `--tests` with the package and `*` | `./gradlew test --tests 'com.example.pkg.*'` |
| One module only (multi-module) | prefix the task path | `./gradlew :service:test --tests 'FooTest'` |
| Several modules, keep going past a failing one | list each task + `--continue` | `./gradlew :a:test :b:test --continue` |

Notes:
- Translate a Maven-style selector the caller passed: `FooTest#method` → `--tests 'FooTest.method'`;
  `FooTest#m1+m2` → `--tests 'FooTest.m1' --tests 'FooTest.m2'`; `FooTest,BarTest` → one `--tests` per entry;
  `com.example.pkg.**` → `--tests 'com.example.pkg.*'`. A pattern starting with an uppercase letter is matched as a
  simple class name; a lowercase start is matched as a fully qualified name.
- `--tests` attaches to the task named right before it on the command line — always put it immediately after the
  `test` task it filters (e.g. `:service:test --tests 'FooTest'`).
- Exclusions (`!SlowTest`) and JUnit 5 tag expressions have **no** built-in command-line flag in Gradle — they live
  in the build script (`useJUnitPlatform { includeTags(...) }`, `filter { excludeTestsMatching(...) }`). Check the
  build script for a project property wired to them (e.g. `-PincludeTags=fast`); if none exists, tell the user the
  tag/exclusion filter can't be applied from the command line without editing the build, and ask whether to run
  the closest class/package selector instead. Don't edit the build script yourself.
- A selector matching no test fails the task with "No tests found for given includes" — same stance as Maven:
  don't add `--no-fail-on-no-test`/`filter.isFailOnNoMatchingTests = false` unless the user asks.
- Gradle skips an up-to-date `test` task whose inputs haven't changed. To force a re-run of an unchanged scope
  (e.g. checking a flaky test), add `--rerun` (Gradle 7.6+) or run `cleanTest` first — only when a re-run is
  actually wanted.

### Kotlin test sources

Kotlin tests (`src/test/kotlin`) use the same selectors — the test class name is the Kotlin class name, which
needn't match the file name. Kotlin backtick method names (`` fun `should parse input`() ``) compile to a JVM method
name containing spaces:

- Always single-quote the whole selector so the shell passes the spaces through:
  Gradle `--tests 'FooTest.should parse input'`; Maven `-Dtest='FooTest#should parse input'`.
- If a name with spaces (or characters such as `,`, `#`, `+` that the selector syntax itself uses) doesn't
  resolve, fall back to a wildcard on a distinctive prefix — `--tests 'FooTest.should parse*'` /
  `-Dtest='FooTest#should*'` — and say in the report that the selector was widened.

### Quarkus

- `@QuarkusTest` (and `@QuarkusComponentTest`) classes are ordinary Surefire/`test`-task tests — the selectors above
  apply unchanged. The first `@QuarkusTest` boots the application once, and a different `@TestProfile` restarts it,
  so even a single-method run takes noticeably longer than a plain unit test.
- A `@QuarkusTest` may start **Dev Services** containers (database, Kafka, Redis, …) when no URL is configured for
  that service, which needs a running Docker/Podman daemon. If the run fails with a container/Docker error
  (e.g. "Could not find a valid Docker environment", "Docker is not running", a Dev Services start-up failure) —
  report it as an **environment failure** (the tests didn't get to run), not as test failures, and don't try to
  work around it by disabling Dev Services unless the user (or the calling skill) asks. When asked: Maven takes
  `-Dquarkus.devservices.enabled=false`; Gradle needs the environment variable instead
  (`QUARKUS_DEVSERVICES_ENABLED=false ./gradlew test …`), because Gradle's `-D` doesn't reach the test JVM. A test
  that genuinely needs a container then fails fast at startup (e.g. "Datasource '<default>' was deactivated") —
  report it as needing the container stage, not as a defect.
- `@QuarkusIntegrationTest` classes (`*IT`) test the packaged artifact and are **not** part of this skill's default
  run: Maven runs them in `verify` (Failsafe); Gradle in the Quarkus plugin's `quarkusIntTest` task (or a custom
  `integrationTest` task). Only run them — `mvn verify -Dit.test=<FooIT>` / `./gradlew quarkusIntTest --tests
  '<FooIT>'` — when the user explicitly asks for integration tests.

The same stance applies to Spring Boot (Java or Kotlin): Testcontainers-backed `*IT` classes run under Failsafe
(`mvn verify`) or a dedicated Gradle `integrationTest` task, never as part of this skill's default unit run.

## Step 2 — Build and run the command

Compose the full invocation from the working directory at the repository root:

```bash
mvn test -Dtest=<selector>
# or, for Gradle:
./gradlew test --tests '<pattern>'
```

Combine multiple parameters with spaces if both a class/method selector and a tag filter are needed, e.g.:

```bash
mvn test -Dtest=FooTest -Dgroups="fast"
```

For a multi-module Gradle build scoped to more than one module, list each module's `test` task with its own
`--tests` filters and add `--continue` so a failure in one module doesn't hide another module's results:

```bash
./gradlew :domain:test --tests 'FooTest' :application:test --tests 'BarServiceTest' --continue
```

Run it with the Bash tool. Do not add unrelated flags (e.g. `-o`, `-q`, `--offline`, skip-checks, `-x <task>`)
unless the user asks for them or a prior step in this conversation already established they're needed. Never run
`./gradlew build`/`check` or `mvn verify` for a unit-test request — they also run integration tests and other gates.

## Step 3 — Report results

Summarize the test output concisely:
- Total tests run, failures, errors, skipped. Surefire prints a `Tests run: X, Failures: Y, Errors: Z, Skipped: W`
  line per class and a totals line at the end. Gradle's console only lists failing tests by default — read the
  totals from the JUnit XML results instead:
  ```bash
  python3 - <<'PY'
  import glob, xml.etree.ElementTree as ET
  t = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
  for f in glob.glob("**/build/test-results/test/*.xml", recursive=True):
      s = ET.parse(f).getroot()
      for k in t:
          t[k] += int(s.get(k, 0))
  print(t)
  PY
  ```
  (`build/test-results/test/TEST-<class>.xml`, one per test class and module, rewritten on every run so it never
  holds results from an earlier, differently scoped run; JUnit 5 `testcase` names carry a `()` suffix, e.g.
  `should parse input()`; the HTML summary is `build/reports/tests/test/index.html`.)
- If everything passed, say so briefly — don't paste the full log.
- If there are failures/errors, show the failing test names and the relevant assertion/exception output (from
  `target/surefire-reports/*.txt` for Maven, or the `<failure>`/`<error>` element of the matching
  `build/test-results/test/TEST-*.xml` for Gradle, if the console output was truncated), but don't dump unrelated
  passing-test noise.
- Report an environment failure (Docker/Dev Services/Testcontainers unavailable, see Step 1's Quarkus notes)
  separately from genuine test failures, naming which test classes couldn't run because of it.
- Do not attempt to fix failing tests or modify source/test code unless the user asks for that as a next step.

## Verification notes

- Maven + Kotlin (`$TMPDIR/iru-verify/java/iru-java-test/kt/`, `kotlin-maven-plugin` 2.2.0, Surefire 3.5.3):
  `-Dtest='FooTest#should parse input'` ran exactly that backtick-named test; `-Dtest='FooTest#should*'` ran both
  `should …` tests.
- Gradle 8 (`gradle:8-jdk21` Docker image, `$TMPDIR/iru-verify/java/iru-java-test-gradle/`, Kotlin DSL two-level
  build with Kotlin tests in `svc/src/test/kotlin`): `:svc:test --tests 'FooTest.should parse input'` → 1 test;
  `'FooTest.should*'` → 2; `'FooTest'` → 3; `'com.ex.*'`, `'*Test'`, and two repeated `--tests` → all 4; a bare
  `test --tests 'FooTest'` from the root ran the subproject's task; `--tests 'Nope'` failed with "No tests found for
  given includes"; the Step 3 python totals read `build/test-results/test/*.xml` correctly.
- Not verified: Quarkus (`@QuarkusTest`, Dev Services, `quarkusIntTest`) behavior, which follows the Quarkus docs.
