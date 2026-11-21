Notes specific to both `react-native` `toolchain: expo` variants:

- **Runner cost**: the `local` variant's `ios` job runs on `macos-latest`, whose minutes cost roughly 10×
  Linux per GitHub's current pricing — free on a public repository, but on a private/internal one each iOS
  archive draws heavily on the included-minutes budget (see Step 1's cost context). The `eas` variant runs only
  a short `ubuntu-latest` job; the builds themselves happen on Expo's infrastructure, metered by the EAS plan
  instead of Actions minutes. Mention this in Step 8 when `visibility` isn't `public`.

- `ExportOptions.plist` is a project-specific file this skill doesn't generate (it encodes the team id and
  export method) — note in Step 8 that the user must add it under `ios/` before this workflow can export an
  `.ipa`.
- **iOS signing model (`local` variant, `distribution` ≠ `none`)**: nothing imports a `.p12` certificate or a
  `.mobileprovision` into the runner's keychain — the archive relies on `-allowProvisioningUpdates` +
  `-authenticationKeyPath/-authenticationKeyID/-authenticationKeyIssuerID` + `CODE_SIGN_STYLE=Automatic`, which
  lets `xcodebuild` create/fetch the cloud-managed distribution certificate and profile itself (Xcode 13+). It
  needs an App Store Connect API key with the **App Manager** or **Admin** role, and `-authenticationKeyPath`
  must be an **absolute** path (hence `$PWD/../AuthKey.p8`, evaluated inside `working-directory: ios`). If the
  team instead manages its distribution certificate manually (a `.p12` it owns), replace the `Decode App Store
  Connect API key` step with a keychain import (e.g. `apple-actions/import-codesign-certs`) plus a profile
  install, set `CODE_SIGN_STYLE=Manual` with the matching `PROVISIONING_PROFILE_SPECIFIER`, and note the
  extra secrets in Step 7's table — flag which model applies in Step 8 rather than assuming. With
  `distribution: none` the unsigned archive (`CODE_SIGNING_ALLOWED=NO`) is all that runs.
- `<ios-workspace>`/`<ios-scheme>`: `expo prebuild` names the workspace and scheme after the app (Step 1's
  survey) — the `App.xcworkspace`/`-scheme App` pair only exists in Capacitor projects (the `ionic` flavor).
- Neither variant submits to a store automatically the way `eas submit` does — they upload a signed artifact
  only. If the user wants an automated Play Console/App Store Connect upload from the `local` path too, that's
  additional scope beyond this skill's remit; note it as a follow-up rather than improvising an untested
  upload step.
