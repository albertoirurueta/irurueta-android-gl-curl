Notes specific to this template:

- Use `xcodebuild -workspace ios/App/App.xcworkspace -scheme App archive` exactly as this skill's design names it —
  the `working-directory: ios/App` above plus a relative `-workspace App.xcworkspace` is equivalent and
  keeps every other relative path (`build/...`, `ExportOptions.plist`) short; resolve to whichever form
  matches how the rest of this repository's iOS steps are written, if this is an update (Step 2). Capacitor
  really does name the workspace/scheme `App` (`ios/App/App.xcworkspace`), unlike `expo prebuild`.
  If the target repository already has a `fastlane/Fastfile`, prefer driving the archive/export/upload
  through `bundle exec fastlane <lane>` instead of raw `xcodebuild` calls — note that as the preferred path in
  Step 8's report rather than silently overriding an existing Fastlane setup with the raw-`xcodebuild` steps
  above.
- **iOS signing**: identical model to the `react-native`/`local` variant — `-allowProvisioningUpdates` + the
  API-key trio + `CODE_SIGN_STYLE=Automatic` on both `archive` and `-exportArchive`, an App Manager/Admin-role
  API key, an absolute `-authenticationKeyPath` (`$PWD/../../AuthKey.p8` from `working-directory: ios/App`),
  and the manually-managed-certificate alternative described there if the team doesn't use cloud-managed
  signing. `distribution: none` only runs the unsigned archive.
- The `android` job rebuilds from the `native-projects` artifact rather than re-running `npx cap sync` itself
  (which would need the web bundle built again on that runner); the price is the `chmod +x android/gradlew`
  step, because the artifact zip doesn't preserve the wrapper's executable bit. If a repository would rather
  keep each native job self-contained, replace the download with `<install-command>` + `<package-manager-run>
  build` + `npx cap sync android` in-job and drop the `chmod` — either shape is fine, just don't drop the
  `chmod` while keeping the artifact download.
- `<android-package-name>`: the Android application id from `android/app/build.gradle` (Capacitor's default
  project layout) — resolved in Step 1's survey when `distribution: store`.
- **Runner cost**: the `ios` job runs on `macos-latest` (roughly 10× Linux minute cost per GitHub's current
  pricing) on every published release. Free on a public repository; on a private/internal one, flag the
  included-minutes impact in Step 8 (Step 1's cost context), and mention that a team that rarely ships iOS
  can drop the `ios` job here and archive locally in Xcode instead.
