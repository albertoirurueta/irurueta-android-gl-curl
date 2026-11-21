Notes specific to this template:

- The `Export lcov coverage` step's two-line `find`-based binary lookup is deliberately awkward instead of a
  hardcoded `<Name>Tests.xctest` path, because — confirmed in `iru-swift-coverage`'s own verification and
  re-confirmed here — the `.xctest` bundle is always named after its **test target**, not the package, and a
  package can carry more than one test target; globbing avoids hardcoding a name this skill can't fully predict
  from `Package.swift` alone (a `<Name>Tests` convention holds for `iru-setup-swift-library`'s own scaffold, but
  not necessarily for an existing repository this skill is asked to update).
- `.github/scripts/lcov_to_sonar_generic.py` (referenced above, written in Step 9's file matrix alongside this
  workflow) is a small, self-contained SonarQube Generic Test Coverage XML converter this skill vendors — see
  Step 9 for its full source and Step 13 for why slather/`xccov-to-sonarqube-generic.sh` don't apply to a bare
  SwiftPM package's coverage output.
- `swiftLanguageModes`/`swift-tools-version: 6.0` (per `iru-setup-swift-library`'s own scaffold) needs no
  special CI handling — `swift build`/`swift test` resolve the toolchain requirement from the manifest itself,
  as long as the selected Xcode (`xcode-version`) bundles a Swift toolchain new enough.
- `sonar.host.url` is passed explicitly via `-D` even though `sonar-project.properties` (written by
  `iru-setup-swift-library`) already carries it, matching this catalog's convention of not relying solely on the
  properties file for the one value most likely to differ between a `cloud` and `self-hosted` setup.
