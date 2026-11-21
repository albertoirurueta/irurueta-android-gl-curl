# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Antora documentation site with usage guides, code examples and the demo GIF, plus Dokka API docs under `/api/`.
- Gitflow development: `develop` branch, snapshot publishing, release/sync/security workflows and Dependabot.

### Changed

- CI builds run on JDK 21 (the library still targets JVM 17).
- Android Lint writes SARIF reports, uploaded to GitHub code scanning by CI.
- Updated `irurueta-android-glutils` to 1.1.13 and `irurueta-android-test-utils` to 1.3.3.

### Fixed

- Sonar coverage, test and lint report paths, and duplicate source indexing with recent SonarQube Gradle plugins.

## [1.1.6] - 2026-03-06

### Changed

- Upgraded build toolchain to Android Gradle Plugin 9.0.1, Gradle 9.4.0 and Kotlin 2.3.10.
- Updated `irurueta-android-glutils` dependency to 1.1.11 and other dependencies.

## [1.1.5] - 2025-12-28

### Changed

- Raised minimum SDK from 24 to 26.
- Updated to Android Gradle Plugin 8.13.2, Kotlin 2.3.0, `irurueta-android-glutils` 1.1.10 and other dependencies.

## [1.1.4] - 2025-09-24

### Changed

- Updated to Android Gradle Plugin 8.13.0, Kotlin 2.2.20, `irurueta-android-glutils` 1.1.9 and other dependencies.

## [1.1.3] - 2025-07-29

### Changed

- Updated `irurueta-android-glutils` to 1.1.8 and other dependencies.

## [1.1.2] - 2025-07-27

### Changed

- Updated `irurueta-android-glutils` to 1.1.6 and other dependencies; tests use `irurueta-android-test-utils`.

## [1.1.1] - 2025-07-20

### Changed

- Updated to Android Gradle Plugin 8.11.1, Kotlin 2.2.0, `irurueta-android-glutils` 1.1.5 and other dependencies.
- Instrumented tests that need a real device are skipped on emulators.

## [1.1.0] - 2025-06-19

### Changed

- Migrated to Kotlin DSL build scripts and a version catalog, with Android Gradle Plugin 8.10.1 and Kotlin 2.0.21.
- Compile and target SDK 36; minimum SDK raised from 21 to 24.
- `irurueta-android-glutils` (1.1.3) is exposed as an `api` dependency.

## [1.0.2] - 2023-03-06

### Changed

- Compile and target SDK 33.

## [1.0.1] - 2021-12-21

### Changed

- Updated `irurueta-android-glutils` to 1.0.9.

### Fixed

- Demo application fixes.

## [1.0.0] - 2021-12-18

### Added

- Initial release: `CurlTextureView` and `CurlGLSurfaceView` page curl views, and a demo application.

[Unreleased]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.6...develop
[1.1.6]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.5...1.1.6
[1.1.5]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.4...1.1.5
[1.1.4]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.3...1.1.4
[1.1.3]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.0.2...1.1.0
[1.0.2]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/albertoirurueta/irurueta-android-gl-curl/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/albertoirurueta/irurueta-android-gl-curl/releases/tag/1.0.0
