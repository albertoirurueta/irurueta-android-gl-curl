Notes specific to this template:

- **The watch app rides inside the iOS archive** (`embed: true` in `project.yml`): `xcodebuild archive -scheme
  <AppName>-iOS` builds `<AppName>-watchOS` as a dependency and places it under the iOS app's `Watch/` folder —
  verified locally (Step 13). Only a *standalone* watchOS app (`watchos-standalone`) gets its own `app-store` leg.
- **visionOS archives with `generic/platform=visionOS`** and uploads as its own App Store Connect platform build
  (same bundle id as iOS, one app record) — its archive needs no simulator runtime.
- **The App Store Connect API key is required for every `distribution` ≠ `none` pipeline, whichever `signing`
  mode** — `match` only supplies certificates and profiles; it never authenticates an upload or a notarization.
  Plain path: Admin or App Manager role for `-allowProvisioningUpdates` (cloud-managed certificates); notarization
  works with the Developer role.
- **macOS defaults to Developer ID + notarization**, not a Mac App Store `.pkg` (an `app-store-connect` export is never
  notarized — App Review handles that). For a Mac App Store release, add `<AppName>-macOS` (`platform: macOS`) to the
  `app-store` matrix instead and drop `macos-developer-id` — the same archive/export-with-upload steps apply. State
  which path was generated in the report.
- `teamID` is filled in at run time from the `APPLE_TEAM_ID` secret (`plutil -replace`), so no Team ID is committed or
  asked for while generating.
- `workflow_dispatch` takes a required `tag` so a dry run archives the same tagged commit a real Release would;
  `RELEASE_TAG` resolves to whichever is present. The `version` job runs on Linux, so a tag/version mismatch fails
  before any macOS minute is spent.
