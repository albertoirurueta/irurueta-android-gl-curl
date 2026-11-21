Notes specific to this template:

- **Signing material never lands in the repository**: the keystore goes to `$RUNNER_TEMP` and the passwords
  reach Gradle only as `-P` properties from step-level `env:` (never interpolated into the script text), and
  the `.p8` key likewise lives in `$RUNNER_TEMP`. Unlike the `expo`/`local` and `ionic` templates, nothing is
  appended to `android/gradle.properties`, so a project-specific `signingConfigs.release` that reads its own
  property names keeps working locally while CI's injected values win.
- **Upload keystore vs. app signing key**: with Play App Signing (the default for new apps) the secret is the
  *upload* key; Google re-signs with the app signing key. The very first AAB of a brand-new app must be uploaded
  by hand in the Play Console before `r0adkll/upload-google-play` can publish to any track — flag this in Step 8.
- **`versionCode` must grow** on every upload Play accepts; `iru-react-native-bump-version` increments it (and
  `CURRENT_PROJECT_VERSION`) on each release value, so re-running this workflow for the same release fails at
  the upload step by design.
- **`<team-id>`** (Apple Developer Team ID) has no run-time source — ask for it, as
  `iru-setup-swift-github-workflows` does. `ExportOptions.plist` is written inline (unlike the expo variants,
  which expect a committed `ios/ExportOptions.plist`); `method: app-store` is the long-standing value newer
  Xcode releases still accept as an alias of `app-store-connect`.
- **iOS signing alternative**: a team that manages its distribution certificate by hand (a `.p12`) or with
  `fastlane match` swaps the API-key archive flags for a keychain import or `bundle exec fastlane match appstore
  --readonly`, exactly as described in `iru-setup-swift-github-workflows`' `release.yml` notes — the template
  Gemfile is already there, so adding `gem 'fastlane'` is the only prerequisite. If the repository already has a
  `fastlane/Fastfile`, prefer driving archive/export/upload through its lanes and say so in Step 8.
- **Runner cost**: the `ios` job runs on `<macos-runner>` (~10× Linux per GitHub's current pricing) once per
  published release; the `android` job compiles every ABI's native code, so expect 15–25 Linux minutes.
