# Project-level templates

The XcodeGen manifest, entitlements, asset catalog, export options, the DMG script and the quality/Sonar configs —
everything outside `App/Sources`, `App/Tests`, `App/UITests` and `Packages/Core`.

Conditional markers (`# >>> sandbox: yes` … `# <<< sandbox: yes`, `<!-- >>> sandbox: yes -->`, …) and placeholders
work as described in SKILL.md's "Reference files" section. Every file below was rendered and exercised for
`swiftui+mvvm`, `appkit+mvc` and `appkit+mvvm` on 2026-10-16 (see SKILL.md's Known quirks).

### `project.yml`

```yaml
name: <AppName>
options:
  deploymentTarget:
    macOS: "<min-macos>"
  xcodeVersion: "<xcode-version>"
  createIntermediateGroups: true
  developmentLanguage: en
configs:
  Debug: debug
  Release: release
settings:
  base:
    SWIFT_VERSION: "6.0"
    SWIFT_STRICT_CONCURRENCY: complete
    MACOSX_DEPLOYMENT_TARGET: "<min-macos>"
    # Written unquoted on purpose: iru-swift-bump-version and the gitflow sync_versions.py rewrite these two keys
    # by regex. MARKETING_VERSION is always a plain x.y.z (App Store Connect and notarization take no suffix).
    MARKETING_VERSION: <marketing-version>
    CURRENT_PROJECT_VERSION: <build-number>
    DEVELOPMENT_TEAM: "<team-id>"
    CODE_SIGN_STYLE: Automatic
    ENABLE_USER_SCRIPT_SANDBOXING: YES
    DEAD_CODE_STRIPPING: YES
packages:
  Core:
    path: Packages/Core
targets:
  <AppName>:
    type: application
    platform: macOS
    sources:
      - path: App/Sources
      - path: App/Resources
    dependencies:
      - package: Core
    settings:
      base:
        PRODUCT_NAME: <AppName>
        PRODUCT_MODULE_NAME: <AppName>
        PRODUCT_BUNDLE_IDENTIFIER: <bundle-id>
        # No committed Info.plist: Xcode generates it from these INFOPLIST_KEY_* settings.
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_CFBundleDisplayName: "<app-name>"
        INFOPLIST_KEY_LSApplicationCategoryType: <category>
        INFOPLIST_KEY_NSHumanReadableCopyright: "Copyright © <year> <developer-name>"
        INFOPLIST_KEY_NSPrincipalClass: NSApplication
        # >>> observation-tracking-opt-in
        # AppKit Observation tracking is on by default from macOS 26; below that it is opt-in through
        # NSObservationTrackingEnabled. Xcode has no INFOPLIST_KEY_ setting for it (an INFOPLIST_KEY_ line is
        # silently dropped), so a minimal committed plist is merged with the generated one.
        INFOPLIST_FILE: App/Info.plist
        # <<< observation-tracking-opt-in
        # >>> distribution: store
        INFOPLIST_KEY_ITSAppUsesNonExemptEncryption: NO
        # <<< distribution: store
        ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon
        ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME: AccentColor
        CODE_SIGN_ENTITLEMENTS: App/Resources/<AppName>.entitlements
        # Notarization rejects executables without the hardened runtime.
        ENABLE_HARDENED_RUNTIME: YES
        # >>> sandbox: yes
        # Mirrors com.apple.security.app-sandbox in the entitlements file (the Mac App Store requires it).
        ENABLE_APP_SANDBOX: YES
        # <<< sandbox: yes
  <AppName>Tests:
    type: bundle.unit-test
    platform: macOS
    sources:
      - path: App/Tests
    dependencies:
      - target: <AppName>
      - package: Core
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: <bundle-id>.tests
        GENERATE_INFOPLIST_FILE: YES
  <AppName>UITests:
    type: bundle.ui-testing
    platform: macOS
    sources:
      - path: App/UITests
      # The UI contract's screenshot helper lives in the unit-test target and is compiled here too (one file).
      - path: App/Tests/Support/UIScreenshots.swift
    dependencies:
      - target: <AppName>
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: <bundle-id>.uitests
        GENERATE_INFOPLIST_FILE: YES
        TEST_TARGET_NAME: <AppName>
schemes:
  <AppName>:
    build:
      targets:
        <AppName>: all
    run:
      config: Debug
    test:
      config: Debug
      gatherCoverageData: true
      targets:
        - package: Core/CoreTests
        - <AppName>Tests
        - <AppName>UITests
    profile:
      config: Release
    analyze:
      config: Debug
    archive:
      config: Release
```

`<team-id>` may be empty: every local command in SKILL.md's contract runs with `CODE_SIGNING_ALLOWED=NO` or ad-hoc
signing. A Developer ID export or an App Store upload needs it (or `DEVELOPMENT_TEAM=<id>` on the `xcodebuild`
command line, which is what CI does).

### `App/Info.plist` (observation-tracking-opt-in)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Merged with the Info.plist Xcode generates from project.yml's INFOPLIST_KEY_* settings. Only written for
         ui: appkit + architecture: mvvm with min-macos below 26 (AppKit Observation tracking is opt-in there). -->
    <key>NSObservationTrackingEnabled</key>
    <true/>
</dict>
</plist>
```

It lives in `App/`, outside the `App/Sources`/`App/Resources` source paths, so XcodeGen never adds it to Copy
Bundle Resources.

### `App/Resources/<AppName>.entitlements`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- >>> sandbox: yes -->
    <!-- App Sandbox: required for the Mac App Store, kept for Developer ID builds too. Add the narrowest extra
         entitlement per feature, e.g. com.apple.security.network.client or
         com.apple.security.files.user-selected.read-write (NSOpenPanel/NSSavePanel + security-scoped bookmarks). -->
    <key>com.apple.security.app-sandbox</key>
    <true/>
    <!-- <<< sandbox: yes -->
</dict>
</plist>
```

With `sandbox: no` the file is still written (an empty `<dict/>`-equivalent) so `CODE_SIGN_ENTITLEMENTS` never
dangles; hardened-runtime exceptions (`com.apple.security.cs.*`) go here too — add only the ones a feature needs.

### `App/Resources/Assets.xcassets/Contents.json`

```json
{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

### `App/Resources/Assets.xcassets/AccentColor.colorset/Contents.json`

```json
{
  "colors" : [
    {
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

### `App/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json`

```json
{
  "images" : [
    { "idiom" : "mac", "scale" : "1x", "size" : "16x16" },
    { "idiom" : "mac", "scale" : "2x", "size" : "16x16" },
    { "idiom" : "mac", "scale" : "1x", "size" : "32x32" },
    { "idiom" : "mac", "scale" : "2x", "size" : "32x32" },
    { "idiom" : "mac", "scale" : "1x", "size" : "128x128" },
    { "idiom" : "mac", "scale" : "2x", "size" : "128x128" },
    { "idiom" : "mac", "scale" : "1x", "size" : "256x256" },
    { "idiom" : "mac", "scale" : "2x", "size" : "256x256" },
    { "idiom" : "mac", "scale" : "1x", "size" : "512x512" },
    { "idiom" : "mac", "scale" : "2x", "size" : "512x512" }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
```

An empty placeholder icon set (it builds without warnings); drop the PNGs in, or replace it with an Icon Composer
`.icon` file, before the first release.

### `ExportOptions-copy.plist` (distribution: none)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- "Copy App": exports the archived .app as built, without re-signing. For local/manual QA builds only;
         Gatekeeper blocks an un-notarized app downloaded from the internet. -->
    <key>method</key>
    <string>mac-application</string>
    <key>destination</key>
    <string>export</string>
</dict>
</plist>
```

### `ExportOptions-developer-id.plist` (distribution: internal)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Direct distribution: Developer ID Application signature, hardened runtime, secure timestamp.
         Notarize and staple the DMG made from this export (scripts/make-dmg.sh, then notarytool + stapler). -->
    <key>method</key>
    <string>developer-id</string>
    <key>teamID</key>
    <string><team-id></string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>destination</key>
    <string>export</string>
</dict>
</plist>
```

### `ExportOptions-app-store.plist` (distribution: store)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Mac App Store: sign for App Store Connect and upload in the same -exportArchive step
         (-allowProvisioningUpdates + an App Store Connect API key on CI). -->
    <key>method</key>
    <string>app-store-connect</string>
    <key>teamID</key>
    <string><team-id></string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>destination</key>
    <string>upload</string>
</dict>
</plist>
```

When `<team-id>` is empty, write the `teamID` key with an empty string and say in the report that it must be
filled in (or passed as `DEVELOPMENT_TEAM` on CI) before the export works.

### `scripts/make-dmg.sh`

```bash
#!/bin/bash
# Packages an exported <AppName>.app into a read-only, zlib-compressed (UDZO) disk image with a drag-to-install
# /Applications link. Uses only hdiutil/ditto/codesign from macOS -- no third-party create-dmg.
#
# Usage: scripts/make-dmg.sh <path/to/App.app> <output.dmg> [volume-name]
# Env:   DMG_SIGN_IDENTITY  optional, e.g. "Developer ID Application: Example Corp (ABCDE12345)" -- signs the image
#                           (required before notarizing it); unset = unsigned image (local/QA builds).
#        DMG_IDENTIFIER     optional code-signing identifier for the image (default: <bundle-id>.dmg).
# Notarize and staple the signed image afterwards:
#   xcrun notarytool submit <output.dmg> --key <AuthKey.p8> --key-id <id> --issuer <issuer> --wait
#   xcrun stapler staple <output.dmg>
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 <path/to/App.app> <output.dmg> [volume-name]" >&2
  exit 64
fi

app_path="${1%/}"
dmg_path="$2"
app_name="$(basename "$app_path" .app)"
volume_name="${3:-$app_name}"

if [[ ! -d "$app_path" || "$app_path" != *.app ]]; then
  echo "error: '$app_path' is not an .app bundle" >&2
  exit 66
fi

staging="$(mktemp -d "${TMPDIR:-/tmp}/make-dmg.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

ditto "$app_path" "$staging/$app_name.app"
ln -s /Applications "$staging/Applications"

mkdir -p "$(dirname "$dmg_path")"
hdiutil create -volname "$volume_name" -srcfolder "$staging" -format UDZO -fs APFS -ov "$dmg_path"

if [[ -n "${DMG_SIGN_IDENTITY:-}" ]]; then
  codesign --sign "$DMG_SIGN_IDENTITY" --timestamp \
    --identifier "${DMG_IDENTIFIER:-<bundle-id>.dmg}" "$dmg_path"
  codesign --verify --verbose=2 "$dmg_path"
fi

hdiutil verify "$dmg_path"
echo "Created $dmg_path"
```

Write it with mode `0755` (`chmod +x scripts/make-dmg.sh`).

### `.swiftlint.yml`

```yaml
included:
  - App
  - Packages/Core/Sources
  - Packages/Core/Tests
excluded:
  - "**/.build"
  - DerivedData
  - build
disabled_rules:
  # swift-format (.swift-format) owns formatting and adds trailing commas to multi-line collection literals.
  - trailing_comma
opt_in_rules:
  - closure_spacing
  - empty_count
  - explicit_init
  - fatal_error_message
  - first_where
  - force_unwrapping
  - implicitly_unwrapped_optional
  - missing_docs
  - overridden_super_call
  - prohibited_super_call
  - redundant_nil_coalescing
  - sorted_imports
analyzer_rules:
  - unused_import
# The documentation bar iru-swift-docc reads: every public/open declaration of Packages/Core needs a /// comment.
missing_docs:
  warning: [open, public]
line_length:
  warning: 120
  error: 160
identifier_name:
  excluded: [id]
```

Run as `swiftlint lint --strict` from the repository root (warnings fail too). `swiftlint analyze` (for
`unused_import`) needs a compiler log and is optional.

### `.swift-format`

```json
{
  "version": 1,
  "lineLength": 120,
  "indentation": { "spaces": 4 },
  "tabWidth": 4,
  "respectsExistingLineBreaks": true,
  "lineBreakBeforeControlFlowKeywords": false,
  "lineBreakBeforeEachArgument": false,
  "rules": {
    "AllPublicDeclarationsHaveDocumentation": true,
    "NeverForceUnwrap": true,
    "NeverUseForceTry": true,
    "OrderedImports": true
  }
}
```

Run as `swift format lint --strict --recursive App Packages/Core/Sources Packages/Core/Tests`. Without this file the
toolchain formatter defaults to 2-space indentation and fails every template.

### `.ui-quality.yml`

The UI contract's quality settings (`iru-swift-code-one-task`'s `reference/ui-contract.md` §8), read by the
`UIScreenshots.swift` helper in the UI tests and by `iru-swift-test`/`iru-swift-code-one-task-group`. Committed —
only `.ui-screenshots/` is gitignored. `<accessibility>` is Step 0/2's `accessibility` (default `enforce`).

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: <accessibility>      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

### `sonar-project.properties` (sonar)

```properties
# >>> sonar: cloud
sonar.organization=<sonar-organization>
# <<< sonar: cloud
sonar.projectKey=<sonar-project-key>
sonar.host.url=<sonar-host-url>
sonar.projectName=<app-name>
sonar.sourceEncoding=UTF-8
sonar.sources=App/Sources,Packages/Core/Sources
sonar.tests=App/Tests,App/UITests,Packages/Core/Tests
sonar.swift.file.suffixes=.swift
# SonarQube generic coverage XML converted from the xcresult/llvm-cov output by CI (iru-setup-macos-github-workflows).
sonar.coverageReportPaths=sonarqube-generic-coverage.xml
sonar.swift.swiftLint.reportPaths=swiftlint.json
# The UI layer is not part of the coverage bar (SKILL.md: "Coverage bar").
sonar.coverage.exclusions=App/Sources/**
```

`sonar.organization` only for `sonar: cloud` (or a self-hosted server with organizations enabled).
