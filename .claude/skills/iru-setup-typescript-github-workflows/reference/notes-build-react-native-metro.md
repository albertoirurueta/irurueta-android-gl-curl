Notes specific to this template:

- **Why four jobs instead of one**: the JS checks, the Android build and the iOS build share nothing but
  `node_modules`, and each native build takes several minutes; separate jobs run in parallel, fail
  independently, and let the macOS job be gated on its own. The Sonar scan stays in `build` (it only needs
  `coverage/lcov.info`); `sonar-project.properties` already excludes `android/**`/`ios/**`, so native code is not
  sent to Sonar.
- **No `.integration-test.lock` in CI**: the lock serializes emulator/simulator/Metro use on a developer machine
  (`iru-typescript-test`'s `e2e` selector); each GitHub-hosted job has its own VM and its own emulator, so the
  workflow neither takes nor needs it.
- **Detox instead of Maestro**: when Step 1 found `.detoxrc.js` and no `.maestro/`, replace the two Maestro steps
  with `npx detox build --configuration android.emu.release` before the emulator step and `npx detox test
  --configuration android.emu.release --headless` as its `script:` — and say so in Step 8; the Maestro shape is
  the default because `iru-setup-react-native-app` scaffolds `.maestro/smoke.yaml`.
- **UI screenshots in CI**: `Upload UI screenshots` (`if: always()`, 7 days, `if-no-files-found: ignore`) uploads
  whatever the `ui-flow`-tagged Maestro flows captured on the emulator (light theme, `--android` variant). The
  component/screen gallery captures need a debug build with Metro, so they are produced locally by
  `iru-typescript-test`'s `e2e` selector, not here — no extra job or emulator minutes are spent on them.
- **iOS e2e is not generated**: a Maestro/Detox run on an iOS simulator would add a second, much longer macOS
  job per run (simulator boot + Release build); keep it local (`iru-typescript-test e2e`) unless the user asks.
- **`<emulator-api-level>`**: `35` by default — the same default as `iru-setup-android-github-workflows`'s
  `api-level`, independent of the template's `compileSdkVersion`/`targetSdkVersion`.
