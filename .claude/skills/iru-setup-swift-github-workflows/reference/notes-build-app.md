Notes specific to this template:

- Only the matrix entries for the selected platforms are written. `ios` and `ipados` share the one `<AppName>-iOS`
  leg (`<ios-device-prefix>` = `iPad` when only `ipados` was selected).
- **watchOS always gets its own leg.** The companion watch app is embedded in the iOS app's *build product*, but the
  `<AppName>-iOS` scheme's test action runs only `CoreTests` + `<AppName>Tests-iOS` + `<AppName>UITests-iOS`; the watch
  targets' tests run only under `<AppName>-watchOS` (verified: 11/11 on a watchOS 27.0 simulator, see Step 13).
- **Why build-for-testing + test-without-building**: the build against `generic/platform=<P> Simulator` is the compile
  check for every platform (including visionOS when no runtime exists); the test step then reuses the same
  `build/dd` products on the resolved simulator. `OS=` is required because the same device name exists under several
  runtimes (`iPhone 17` on iOS 26.4 and 27.0), and the runtime is capped at the selected Xcode's simulator SDK
  (`xcrun --sdk <iphonesimulator|watchsimulator|xrsimulator> --show-sdk-version`) so an older Xcode never picks a
  runtime it can't drive.
- **`ci-tests` maps onto `-skip-testing:<AppName>UITests-<P>`** in every leg (`unit`); `full` runs the UI tests too
  (on macOS after the Automation Mode check).
- **UI screenshots (UI contract)**: each leg's test step passes `TEST_RUNNER_UI_SCREENSHOTS_DIR`, the leg then copies
  the captures the test processes attached to the `.xcresult` (`<kind>~<Id>~<file>` — the only route out of the
  sandboxed macOS host app) into `.ui-screenshots/` with `xcrun xcresulttool export attachments`, and uploads the
  directory as a 7-day `ui-screenshots-<scheme>` artifact (`if-no-files-found: ignore` — a `ci-tests: unit` leg still
  carries the unit-test component captures). No extra job, runner or minutes: it runs inside the existing leg.
  Verified locally: the step's YAML parses and its script rebuilt the contract tree from a real Xcode 27.1 `.xcresult`. Each UI-test leg boots a simulator and launches the app — macOS
  minutes billed at ~10× Linux on a private repository.
- **The coverage bar is Core's**, measured by `swift test` + `llvm-cov` in the `core` job — exactly
  `iru-setup-macos-github-workflows`' step. The app legs don't pass `-enableCodeCoverage`: the scheme's `xccov` report
  can't see the statically linked `Packages/Core`, and the UI layer is "not measured" by design. Sonar therefore gets
  Core's lcov (via the vendored `lcov_to_sonar_generic.py`, now written for the app flavor too), not an `.xcresult`.
- `lint` needs no Xcode: `container: swift:<linux-swift-tag>` provides `swift format` and the SourceKit library the
  dynamically linked Linux SwiftLint binary loads. `core` and every `apps` leg `needs: lint`, so a lint failure never
  spends macOS minutes.
- `site` needs `core` (the DocC archive): with `pages: no` it uploads the merged `docs-site` artifact on every run;
  with `pages: yes` it and `deploy-docs` run only on a push to the stable branch.
