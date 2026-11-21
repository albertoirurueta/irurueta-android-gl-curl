# Implementation plan: Update dependencies

## Task summary

Bump two dependency versions declared in the Gradle version catalog:
- `iruruetaTestUtils` from 1.3.2 to 1.3.3
- `glutils` from 1.1.11 to 1.1.13

Approach chosen: edit only `gradle/libs.versions.toml` (single source of truth; `lib/build.gradle.kts` consumes
them via `libs.glutils` and `libs.irurueta.test.utils`), verify build and tests, and record the change in the
changelog and what's-new docs under the Unreleased section.

Source: GitHub issue #3
Base branch: develop
Open clarifications: none

## Acceptance criteria coverage

| # | Criterion | Covered by tasks |
|---|---|---|
| 1 | "iruruetaTestUtils: should be 1.3.3" | Task 1.1 |
| 2 | "glutils: shoul be 1.1.13" | Task 1.2 |
| 3 | derived: project still builds and unit tests pass with the new versions | Task 2 |
| 4 | derived: changelog and docs mention the update | Task 3 |

## Current code state

- Project type: android-library (`lib/` library module, `app/` Compose sample), Gradle Kotlin DSL with wrapper,
  versions centralized in `gradle/libs.versions.toml`.
- `gradle/libs.versions.toml`: `glutils = "1.1.11"`, `iruruetaTestUtils = "1.3.2"`.
- `lib/build.gradle.kts`: `api(libs.glutils)` (line ~111), `testImplementation(libs.irurueta.test.utils)` and
  `androidTestImplementation(libs.irurueta.test.utils)`.
- Docs: `CHANGELOG.md` and `docs/modules/ROOT/pages/whats-new.adoc` both have an Unreleased (1.1.7-SNAPSHOT)
  section; 1.1.6 entries mention glutils 1.1.11.
- No UI changes. No `.archive/` precedents.

## Implementation steps

### Group 1 (Parallelizable: yes — single task)

- [x] Task 1. Bump versions in the version catalog _(android)_ — gradle/libs.versions.toml only; no new tests (config change); full `./gradlew test` green; no new Lint issues (:lib 0).
  - [x] Task 1.1. In `gradle/libs.versions.toml` set `iruruetaTestUtils = "1.3.3"`
  - [x] Task 1.2. In the same file set `glutils = "1.1.13"`

### Group 2 (Parallelizable: no — validates the result of Group 1)

- [x] Task 2. Verify the build with new versions _(android)_ — no source changes needed; lib 406/406 tests pass (forced rerun), assembleDebug OK; no new Lint issues.
  - [x] Task 2.1. Delegate to `iru-gate-runner` (`iru-android-test`) to run `lib` unit tests; fix any API breakage
    in `lib/src` caused by glutils 1.1.13 if it appears
  - [x] Task 2.2. Confirm the `app` module and `lib` compile (`./gradlew assembleDebug`) via `iru-gate-runner`

### Group 3 (Parallelizable: yes — docs only, independent of code)

- [x] Task 3. Document the update
  - [x] Task 3.1. Add under `### Changed` in `[Unreleased]` of `CHANGELOG.md`: "Updated `irurueta-android-glutils`
    to 1.1.13 and `irurueta-android-test-utils` to 1.3.3."
  - [x] Task 3.2. Add a matching `* ⬆️` bullet to the "Unreleased (1.1.7-SNAPSHOT)" section of
    `docs/modules/ROOT/pages/whats-new.adoc`
    - Done: docs only (CHANGELOG.md, whats-new.adoc); no tests/coverage/quality applicable.
