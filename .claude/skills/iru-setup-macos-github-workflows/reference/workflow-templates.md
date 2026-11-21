# macOS desktop workflow templates

**Conditional markers**: a line `# >>> <condition>` opens a block that is written only when the condition holds, and
the matching `# <<< <condition>` line closes it; marker lines are never written, blocks nest, and a comma-separated
condition must hold entirely. Markers also appear inside `run: |` scripts (same rule — the marker line is dropped).

| Condition | Holds when |
|---|---|
| `gitflow` / `main-only` | `branching` |
| `macos-ci: stable` | `macos-ci: stable` (the macOS job only on stable-branch pushes and `workflow_dispatch`) |
| `ci-tests: unit` / `ci-tests: full` | `ci-tests` |
| `pages: yes` / `pages: no` | `pages` |
| `sonar` | `sonar` ≠ `none` (the scaffold's `sonar-project.properties` carries the coordinates) |
| `distribution: internal` / `distribution: store` | `distribution` (`release.yml` exists only when it is not `none`) |
| `security-*` | each enabled `security-*` key (`security-codeql` is the macOS Swift job) |

**Placeholders**: `<AppName>` (`project.yml` `name:` — target, scheme, module, `.app`), `<app-name>` (display name,
`INFOPLIST_KEY_CFBundleDisplayName`), `<integration-branch>`, `<stable-branch>`, `<runner>`, `<xcode-version>`,
`<linux-swift-tag>` (the `swift` Docker image tag for the Linux lint job, e.g. `6.4-noble`), `<swiftlint-version>`,
`<repo>` (repository name, for DocC's `--hosting-base-path <repo>/api`).

Every command below follows `iru-setup-macos-app`'s scaffold contract (its Step 8 table): `xcodegen generate` →
`<AppName>.xcodeproj`, one scheme `<AppName>`, ad-hoc signing for tests (`CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual
DEVELOPMENT_TEAM=`; `CODE_SIGNING_ALLOWED=NO` kills the UI-test runner on arm64), a fresh `-derivedDataPath`, the Core
coverage bar from `swift test` + `llvm-cov` (the app scheme's `xccov` report does not see the statically linked Core).

## `.github/workflows/build.yml`

Always written. Jobs: `lint` (Linux container), `macos` (`<runner>`, `needs: lint`; the job-level `if:` only for
`macos-ci: stable`), `sonar` (Linux, `sonar` only), `docs` (Linux), `site` (Linux — merges the Antora site and the DocC
archive), `deploy-docs` (`pages: yes`).

```yaml
name: Build

on:
  push:
    # >>> gitflow
    branches: [ <integration-branch>, <stable-branch> ]
    # <<< gitflow
    # >>> main-only
    branches: [ <stable-branch> ]
    # <<< main-only
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

# A newer push to the same ref cancels the run in flight: macOS minutes are expensive.
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  # Linux, every push and pull request — macOS minutes cost ~10x Linux minutes on private repositories.
  lint:
    name: Lint — SwiftLint, swift format
    runs-on: ubuntu-latest
    container: swift:<linux-swift-tag>
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      # The dynamically linked Linux binary uses the container toolchain's SourceKit (libsourcekitdInProc.so).
      - name: Install SwiftLint <swiftlint-version>
        run: |
          set -euo pipefail
          if ! command -v curl >/dev/null || ! command -v unzip >/dev/null; then
            apt-get update -qq
            apt-get install -y -qq --no-install-recommends ca-certificates curl unzip >/dev/null
          fi
          case "$(uname -m)" in
            aarch64|arm64) arch=arm64 ;;
            *) arch=amd64 ;;
          esac
          curl -fsSL -o /tmp/swiftlint.zip \
            "https://github.com/realm/SwiftLint/releases/download/<swiftlint-version>/swiftlint_linux_${arch}.zip"
          unzip -q -o /tmp/swiftlint.zip swiftlint -d /usr/local/bin
          swiftlint version

      - name: SwiftLint (strict)
        run: swiftlint lint --strict --reporter github-actions-logging
      # >>> sonar

      # Generic external-issues JSON for the sonar job (SwiftLint's sonarqube reporter).
      - name: SwiftLint report for Sonar
        if: (!cancelled())
        run: swiftlint lint --quiet --reporter sonarqube > swiftlint-sonar.json || true

      - name: Upload the SwiftLint report
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: swiftlint-report
          path: swiftlint-sonar.json
          retention-days: 7
      # <<< sonar

      - name: swift format lint
        run: swift format lint --strict --recursive App Packages/Core/Sources Packages/Core/Tests

  # macOS minutes cost ~10x Linux minutes on private repositories — see iru-setup-macos-github-workflows' macos-ci.
  macos:
    name: macOS — Core coverage, app tests, DocC
    needs: lint
    # >>> macos-ci: stable
    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< macos-ci: stable
    runs-on: <runner>
    timeout-minutes: 60
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      # The bar: Packages/Core holds every model/view-model/controller and is measured with swift test + llvm-cov.
      # The app scheme's xccov report does not attribute the statically linked Core sources.
      - name: Core tests with coverage (80% line bar)
        run: |
          set -euo pipefail
          swift test --package-path Packages/Core --enable-code-coverage
          BIN="$(swift build --package-path Packages/Core --show-bin-path)"
          TEST_BINARY="$BIN/CoreTests.xctest/Contents/MacOS/CoreTests"
          PROFDATA="$BIN/codecov/default.profdata"
          IGNORE='(Tests/|DerivedSources|\.build/)'
          xcrun llvm-cov report "$TEST_BINARY" -instr-profile "$PROFDATA" --ignore-filename-regex="$IGNORE"
          xcrun llvm-cov export "$TEST_BINARY" -instr-profile "$PROFDATA" --ignore-filename-regex="$IGNORE" \
            -format=lcov > coverage.lcov
          xcrun llvm-cov export "$TEST_BINARY" -instr-profile "$PROFDATA" --ignore-filename-regex="$IGNORE" \
            -summary-only > coverage-summary.json
          python3 - <<'PY'
          import json
          percent = json.load(open("coverage-summary.json"))["data"][0]["totals"]["lines"]["percent"]
          print(f"Core line coverage: {percent:.2f}% (bar: 80%)")
          if percent < 80:
              raise SystemExit(f"::error::Core line coverage {percent:.2f}% is below the 80% bar")
          PY
      # >>> sonar

      - name: Convert coverage to SonarQube generic XML
        run: python3 .github/scripts/lcov_to_sonar_generic.py coverage.lcov > sonarqube-generic-coverage.xml

      - name: Upload the coverage report
        uses: actions/upload-artifact@v7
        with:
          name: core-coverage
          path: |
            coverage.lcov
            sonarqube-generic-coverage.xml
          retention-days: 7
      # <<< sonar

      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          xcodegen generate
      # >>> ci-tests: full

      # XCUITest drives the real desktop session and needs Automation Mode without an authentication prompt.
      # GitHub's hosted macOS images enable it (runner-images configure-machine.sh); a self-hosted runner needs
      # `sudo automationmodetool enable-automationmode-without-authentication` once, run by an administrator.
      - name: Check Automation Mode (UI tests)
        run: |
          status="$(automationmodetool)"
          echo "$status"
          if [[ "$status" != *"DOES NOT REQUIRE"* ]]; then
            echo "::error::Automation Mode requires authentication on this runner; UI tests would time out."
            exit 1
          fi

      # CoreTests + <AppName>Tests + <AppName>UITests. Ad-hoc signing: CODE_SIGNING_ALLOWED=NO would kill the
      # UI-test runner on arm64 before it connects.
      - name: App tests (unit + UI)
        env:
          # UI contract: the test processes write captures here (xcodebuild strips the TEST_RUNNER_ prefix).
          TEST_RUNNER_UI_SCREENSHOTS_DIR: ${{ github.workspace }}/.ui-screenshots
        run: |
          xcodebuild test -project <AppName>.xcodeproj -scheme <AppName> -destination 'platform=macOS' \
            -derivedDataPath build/dd -resultBundlePath build/<AppName>.xcresult \
            CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= -quiet
      # <<< ci-tests: full
      # >>> ci-tests: unit

      # CoreTests + <AppName>Tests; the XCUITest bundle stays local (ci-tests: unit).
      - name: App tests (unit)
        env:
          TEST_RUNNER_UI_SCREENSHOTS_DIR: ${{ github.workspace }}/.ui-screenshots
        run: |
          xcodebuild test -project <AppName>.xcodeproj -scheme <AppName> -destination 'platform=macOS' \
            -skip-testing:<AppName>UITests \
            -derivedDataPath build/dd -resultBundlePath build/<AppName>.xcresult \
            CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= -quiet
      # <<< ci-tests: unit

      - name: Test summary
        if: (!cancelled()) && hashFiles('build/<AppName>.xcresult/Info.plist') != ''
        run: xcrun xcresulttool get test-results summary --path build/<AppName>.xcresult --compact

      - name: Upload the xcresult bundle
        if: (!cancelled()) && hashFiles('build/<AppName>.xcresult/Info.plist') != ''
        uses: actions/upload-artifact@v7
        with:
          name: <AppName>-xcresult
          path: build/<AppName>.xcresult
          retention-days: 14

      # UI contract: the sandboxed app can't write the workspace, so the captures its tests attached to the
      # .xcresult (<kind>~<Id>~<file>) are rebuilt into .ui-screenshots/<kind>/<Id>/<file>.png here.
      - name: Collect UI screenshots from the xcresult
        if: (!cancelled()) && hashFiles('build/<AppName>.xcresult/Info.plist') != ''
        run: |
          set -eo pipefail
          export_dir="$RUNNER_TEMP/attachments"
          xcrun xcresulttool export attachments --path build/<AppName>.xcresult --output-path "$export_dir" > /dev/null
          python3 - "$export_dir" .ui-screenshots <<'PY'
          import json, os, re, shutil, sys
          export, out = sys.argv[1], sys.argv[2]
          for test in json.load(open(os.path.join(export, "manifest.json"))):
              for a in test["attachments"]:
                  name = re.sub(r"_\d+_[0-9A-Fa-f-]{36}(\.\w+)?$", "", a["suggestedHumanReadableName"])
                  name = re.sub(r"\.png$", "", name)
                  if re.match(r"^(component|screen|flow)~", name):
                      path = os.path.join(out, name.replace("~", "/") + ".png")
                      os.makedirs(os.path.dirname(path), exist_ok=True)
                      shutil.copyfile(os.path.join(export, a["exportedFileName"]), path)
          PY

      - name: Upload UI screenshots
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-screenshots
          path: .ui-screenshots/
          retention-days: 7
          if-no-files-found: ignore

      # --warnings-as-errors is the documentation gate; the archive is merged under the Antora site's api/ by the
      # site job, so DocC bakes /<repo>/api into its links.
      - name: DocC (Packages/Core)
        run: |
          mkdir -p build/docc
          swift package --package-path Packages/Core --allow-writing-to-directory build/docc \
            generate-documentation --target Core --warnings-as-errors \
            --transform-for-static-hosting --hosting-base-path <repo>/api --output-path build/docc

      - name: Upload the DocC archive
        uses: actions/upload-artifact@v7
        with:
          name: docc-api
          path: build/docc
          retention-days: 7
  # >>> sonar

  # Linux: the scan itself needs no Xcode, so it does not spend macOS minutes. Skipped on pull requests from forks
  # (no SONAR_TOKEN) and whenever the macos job did not run (macos-ci: stable on a pull request).
  sonar:
    name: SonarQube analysis
    needs: [ lint, macos ]
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Download the coverage report
        uses: actions/download-artifact@v8
        with:
          name: core-coverage

      - name: Download the SwiftLint report
        uses: actions/download-artifact@v8
        with:
          name: swiftlint-report

      # sonar-project.properties (iru-setup-macos-app) carries the coordinates and
      # sonar.coverageReportPaths=sonarqube-generic-coverage.xml.
      - name: Run SonarQube analysis
        uses: SonarSource/sonarqube-scan-action@v8
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        with:
          args: >
            -Dsonar.externalIssuesReportPaths=swiftlint-sonar.json
  # <<< sonar

  docs:
    name: Docs — Antora
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up Node
        uses: actions/setup-node@v7
        with:
          node-version: 24
          cache: npm
          cache-dependency-path: docs/package-lock.json

      - name: Install docs toolchain
        working-directory: docs
        run: npm ci

      - name: Show Antora version
        working-directory: docs
        run: npx antora -v

      - name: Build Antora docs
        working-directory: docs
        run: npx antora antora-playbook.yml

      # Search index. The DocC archive is merged later (site job), so only the Antora pages are indexed; to make the
      # API pages searchable, run `npx pagefind --site site` in the site job after the merge instead.
      - name: Index the site for search
        working-directory: docs
        run: npx pagefind --site build/site

      - name: Upload the Antora site
        uses: actions/upload-artifact@v7
        with:
          name: antora-site
          path: docs/build/site
          retention-days: 7

  # Merges the DocC archive (macos job) under the Antora site's api/ — the layout every catalog stack uses.
  site:
    name: Docs — merge Antora + DocC
    needs: [ docs, macos ]
    # >>> pages: yes
    if: github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>'
    # <<< pages: yes
    runs-on: ubuntu-latest
    steps:
      - name: Download the Antora site
        uses: actions/download-artifact@v8
        with:
          name: antora-site
          path: site

      - name: Download the DocC archive
        uses: actions/download-artifact@v8
        with:
          name: docc-api
          path: site/api

      - name: Disable Jekyll
        run: touch site/.nojekyll

      # Optional size guard (GitHub Pages rejects a site over 1 GB): uncomment; it must run after the last step that
      # writes into the site directory (here, after the DocC archive is merged).
      # - name: Check site size
      #   run: |
      #     size=$(du -sk site | cut -f1)
      #     echo "site is ${size} KiB"
      #     if [ "$size" -gt 960000 ]; then echo "::error::site over 960,000 KiB"; exit 1; fi
      #     if [ "$size" -gt 900000 ]; then echo "::warning::site over 900,000 KiB"; fi
      # >>> pages: yes

      - name: Upload Pages artifact
        uses: actions/upload-pages-artifact@v5
        with:
          path: site
      # <<< pages: yes
      # >>> pages: no

      - name: Upload docs artifact
        uses: actions/upload-artifact@v7
        with:
          name: docs-site
          path: site
          retention-days: 14
      # <<< pages: no
  # >>> pages: yes

  deploy-docs:
    name: Deploy docs to GitHub Pages
    needs: site
    if: github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>'
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v5
  # <<< pages: yes
```

## `.github/scripts/lcov_to_sonar_generic.py`

Written only when `sonar` ≠ `none`. The converter `iru-setup-swift-github-workflows` vendors for its library flavor,
unchanged: run from the repository root, it turns `llvm-cov`'s absolute `SF:` paths into
`Packages/Core/Sources/…` paths Sonar can match.

```python
#!/usr/bin/env python3
"""Converts an lcov coverage report (xcrun llvm-cov export -format=lcov) into
SonarQube's Generic Test Coverage XML format (sonar.coverageReportPaths).

Usage: python3 lcov_to_sonar_generic.py coverage.lcov > sonarqube-generic-coverage.xml

File paths are rewritten relative to the current working directory, since
llvm-cov's SF: lines are always absolute and Sonar expects project-relative
paths.
"""
import os
import sys
import xml.sax.saxutils as sx


def main():
    lcov_path = sys.argv[1] if len(sys.argv) > 1 else "coverage.lcov"
    root = os.getcwd()
    files = []
    current = None
    with open(lcov_path) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("SF:"):
                current = {"path": line[3:], "lines": []}
            elif line.startswith("DA:") and current is not None:
                lineno, hits = line[3:].split(",", 1)
                current["lines"].append((int(lineno), int(hits) > 0))
            elif line == "end_of_record" and current is not None:
                files.append(current)
                current = None

    out = ['<coverage version="1">']
    for f in files:
        rel = os.path.relpath(f["path"], root)
        out.append(f'  <file path="{sx.escape(rel)}">')
        for lineno, covered in f["lines"]:
            out.append(f'    <lineToCover lineNumber="{lineno}" covered="{"true" if covered else "false"}"/>')
        out.append("  </file>")
    out.append("</coverage>")
    print("\n".join(out))


if __name__ == "__main__":
    main()
```

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. Triggered by a **published GitHub Release** (`release: [released]`, not a
pre-release) or `workflow_dispatch` with an existing tag (dry run). The tag must equal `project.yml`'s
`MARKETING_VERSION` (`v1.4.0` ↔ `1.4.0`) and be a plain `x.y.z` — App Store Connect and notarization take no suffix —
so cut the release with `iru-release`/`iru-swift-bump-version` first.

```yaml
name: Release

on:
  release:
    types: [released]
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag to build (e.g. v1.2.0) — a manual run has no release event to read it from"
        required: true
        type: string
      # >>> distribution: internal
      attach:
        description: "Also attach the notarized DMG to that tag's GitHub Release (a published release always does)"
        required: false
        type: boolean
        default: false
      # <<< distribution: internal

permissions:
  contents: read

env:
  RELEASE_TAG: ${{ github.event.release.tag_name || inputs.tag }}

jobs:
  # >>> distribution: internal
  developer-id:
    name: Developer ID — sign, notarize, staple, DMG
    runs-on: <runner>
    timeout-minutes: 90
    permissions:
      contents: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      - name: Read and check the release version
        id: version
        run: |
          set -euo pipefail
          version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"?([^"[:space:]]+)"?.*$/\1/p' project.yml | head -n 1)"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::project.yml MARKETING_VERSION '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::project.yml MARKETING_VERSION '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_OUTPUT"

      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          xcodegen generate

      # Developer ID certificates can't be created by -allowProvisioningUpdates (only the Account Holder can), so the
      # exported .p12 is imported into a temporary keychain the action adds to the search list.
      - name: Import the Developer ID Application certificate
        uses: apple-actions/import-codesign-certs@v7
        with:
          p12-file-base64: ${{ secrets.MACOS_CERTIFICATE_P12_BASE64 }}
          p12-password: ${{ secrets.MACOS_CERTIFICATE_PASSWORD }}

      # The raw .p8 content (AuthKey_<id>.p8) — written with umask 077 and removed in the last step.
      - name: Write the App Store Connect API key
        env:
          ASC_PRIVATE_KEY: ${{ secrets.ASC_PRIVATE_KEY }}
        run: |
          umask 077
          printf '%s\n' "$ASC_PRIVATE_KEY" > "$RUNNER_TEMP/AuthKey.p8"
          echo "ASC_KEY_PATH=$RUNNER_TEMP/AuthKey.p8" >> "$GITHUB_ENV"

      # Manual Developer ID signing for the archive: no "Apple Development" certificate or provisioning profile is
      # needed on the runner (the App Sandbox and hardened runtime need no profile).
      - name: Archive
        env:
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          xcodebuild archive -project <AppName>.xcodeproj -scheme <AppName> \
            -destination 'generic/platform=macOS' -archivePath build/<AppName>.xcarchive \
            CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="Developer ID Application" \
            DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -quiet

      # ExportOptions-developer-id.plist is committed with an empty teamID unless iru-setup-macos-app knew it; a
      # temporary copy gets the team from the secret.
      - name: Export with Developer ID
        env:
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
        run: |
          set -euo pipefail
          cp ExportOptions-developer-id.plist "$RUNNER_TEMP/ExportOptions.plist"
          plutil -replace teamID -string "$APPLE_TEAM_ID" "$RUNNER_TEMP/ExportOptions.plist"
          xcodebuild -exportArchive -archivePath build/<AppName>.xcarchive \
            -exportOptionsPlist "$RUNNER_TEMP/ExportOptions.plist" -exportPath build/export \
            -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" \
            -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
          codesign --verify --deep --strict --verbose=2 build/export/<AppName>.app

      # notarytool takes a zip, dmg or pkg — never a bare .app — and stapler can't staple a zip: notarize a ditto zip
      # of the app, staple the app, build the DMG from the stapled app, then sign, notarize and staple the DMG too.
      - name: Notarize and staple the app, then sign, notarize and staple the DMG
        env:
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
          VERSION: ${{ steps.version.outputs.version }}
        run: |
          set -euo pipefail
          notarize() {
            local result id status
            result="$(xcrun notarytool submit "$1" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" \
              --issuer "$ASC_ISSUER_ID" --wait --timeout 30m --output-format json)"
            echo "$result"
            id="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])' <<< "$result")"
            status="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("status", ""))' <<< "$result")"
            if [[ "$status" != "Accepted" ]]; then
              xcrun notarytool log "$id" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" || true
              echo "::error::Notarization of $1 ended with status '$status'."
              return 1
            fi
          }

          ditto -c -k --keepParent build/export/<AppName>.app build/<AppName>-notarize.zip
          notarize build/<AppName>-notarize.zip
          xcrun stapler staple build/export/<AppName>.app
          xcrun stapler validate build/export/<AppName>.app
          rm build/<AppName>-notarize.zip

          DMG_SIGN_IDENTITY="$(security find-identity -v -p codesigning \
            | awk '/"Developer ID Application/ { print $2; exit }')"
          if [[ -z "$DMG_SIGN_IDENTITY" ]]; then
            echo "::error::No Developer ID Application identity in the keychain; the DMG can't be signed."
            exit 1
          fi
          export DMG_SIGN_IDENTITY
          scripts/make-dmg.sh build/export/<AppName>.app "build/<AppName>-$VERSION.dmg" "<app-name>"
          notarize "build/<AppName>-$VERSION.dmg"
          xcrun stapler staple "build/<AppName>-$VERSION.dmg"
          xcrun stapler validate "build/<AppName>-$VERSION.dmg"
          spctl --assess --type open --context context:primary-signature --verbose=2 "build/<AppName>-$VERSION.dmg"

      - name: Upload the DMG as a workflow artifact
        uses: actions/upload-artifact@v7
        with:
          name: <AppName>-dmg
          path: build/<AppName>-${{ steps.version.outputs.version }}.dmg
          retention-days: 30

      - name: Attach the DMG to the GitHub Release
        if: github.event_name == 'release' || inputs.attach
        env:
          GH_TOKEN: ${{ github.token }}
          VERSION: ${{ steps.version.outputs.version }}
        run: gh release upload "$RELEASE_TAG" "build/<AppName>-$VERSION.dmg" --clobber

      - name: Remove the App Store Connect API key
        if: always()
        run: rm -f "$RUNNER_TEMP/AuthKey.p8"
  # <<< distribution: internal
  # >>> distribution: store

  app-store:
    name: Mac App Store — archive and upload to App Store Connect
    runs-on: <runner>
    timeout-minutes: 90
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      - name: Read and check the release version
        run: |
          set -euo pipefail
          version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"?([^"[:space:]]+)"?.*$/\1/p' project.yml | head -n 1)"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::project.yml MARKETING_VERSION '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::project.yml MARKETING_VERSION '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi

      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          xcodegen generate

      - name: Write the App Store Connect API key
        env:
          ASC_PRIVATE_KEY: ${{ secrets.ASC_PRIVATE_KEY }}
        run: |
          umask 077
          printf '%s\n' "$ASC_PRIVATE_KEY" > "$RUNNER_TEMP/AuthKey.p8"
          echo "ASC_KEY_PATH=$RUNNER_TEMP/AuthKey.p8" >> "$GITHUB_ENV"

      # Automatic signing with the API key (Admin or App Manager role): Xcode creates/uses cloud-managed
      # certificates and the Mac App Store profile, so the runner never holds a distribution private key.
      - name: Archive
        env:
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
        run: |
          xcodebuild archive -project <AppName>.xcodeproj -scheme <AppName> \
            -destination 'generic/platform=macOS' -archivePath build/<AppName>.xcarchive \
            DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -allowProvisioningUpdates \
            -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" \
            -authenticationKeyIssuerID "$ASC_ISSUER_ID" -quiet

      # ExportOptions-app-store.plist has destination=upload: this step signs for App Store Connect and uploads the
      # build (TestFlight / App Review). Submitting it for review stays a manual step in App Store Connect.
      - name: Export and upload to App Store Connect
        env:
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
        run: |
          set -euo pipefail
          cp ExportOptions-app-store.plist "$RUNNER_TEMP/ExportOptions.plist"
          plutil -replace teamID -string "$APPLE_TEAM_ID" "$RUNNER_TEMP/ExportOptions.plist"
          xcodebuild -exportArchive -archivePath build/<AppName>.xcarchive \
            -exportOptionsPlist "$RUNNER_TEMP/ExportOptions.plist" -exportPath build/export \
            -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" \
            -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"

      - name: Remove the App Store Connect API key
        if: always()
        run: rm -f "$RUNNER_TEMP/AuthKey.p8"
  # <<< distribution: store
```

## `.github/workflows/sync.yml`

`branching: gitflow` only, always together with `sync_versions.py`.

```yaml
name: Sync

# After a release is published from <stable-branch>, open a pull request that merges it back into
# <integration-branch> and moves MARKETING_VERSION to the next patch (x.y.z -> x.y.(z+1)) with
# CURRENT_PROJECT_VERSION + 1 — the macOS app carries no -SNAPSHOT/-dev marker (iru-swift-bump-version Step 5).
on:
  release:
    types: [released]

permissions:
  contents: write
  pull-requests: write

jobs:
  sync:
    name: Open sync PR from the released branch into <integration-branch>
    runs-on: ubuntu-latest
    if: github.event.release.target_commitish == '<stable-branch>'
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Compute versions and branch names
        id: versions
        env:
          TAG: ${{ github.event.release.tag_name }}
        run: |
          set -euo pipefail
          RELEASE_VERSION="${TAG#v}"
          if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Release tag '$TAG' is not in vX.Y.Z / X.Y.Z form; cannot compute the next version."
            exit 1
          fi
          IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
          NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))"
          {
            echo "release_version=$RELEASE_VERSION"
            echo "next_version=$NEXT_VERSION"
            echo "sync_branch=sync_${RELEASE_VERSION}"
          } >> "$GITHUB_OUTPUT"

      - name: Skip if this release was already synced
        id: guard
        run: |
          if git ls-remote --exit-code --heads origin "${{ steps.versions.outputs.sync_branch }}" >/dev/null 2>&1; then
            echo "::notice::${{ steps.versions.outputs.sync_branch }} already exists on origin; skipping."
            echo "exists=true" >> "$GITHUB_OUTPUT"
          else
            echo "exists=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Configure git identity
        if: steps.guard.outputs.exists == 'false'
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

      - name: Create sync branch from <integration-branch>
        if: steps.guard.outputs.exists == 'false'
        run: |
          git fetch origin <integration-branch> <stable-branch>
          git checkout -b "${{ steps.versions.outputs.sync_branch }}" "origin/<integration-branch>"

      - name: Merge the released branch into the sync branch
        id: merge
        if: steps.guard.outputs.exists == 'false'
        env:
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
        run: |
          if git merge --no-ff --no-edit \
              -m "Merge <stable-branch> (${RELEASE_VERSION}) into <integration-branch>" \
              "origin/<stable-branch>"; then
            echo "conflict=false" >> "$GITHUB_OUTPUT"
          else
            git merge --abort
            echo "::warning::Merging <stable-branch> (${RELEASE_VERSION}) into <integration-branch> conflicts; opening the sync pull request from the release commit for manual resolution, without bumping the development version."
            git checkout -B "${SYNC_BRANCH}" "origin/<stable-branch>"
            echo "conflict=true" >> "$GITHUB_OUTPUT"
          fi

      - name: Set up Python
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        uses: actions/setup-python@v7
        with:
          python-version: '3.x'

      - name: Bump the development version
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        env:
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_VERSION: ${{ steps.versions.outputs.next_version }}
        run: python3 .github/scripts/sync_versions.py

      # git add -u stages every tracked file the script changed; a pathspec list would abort the whole add when one
      # path (README.md, docs/antora.yml) doesn't exist.
      - name: Commit the version bump
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        run: |
          git add -u
          git diff --cached --quiet || git commit -m "Sync ${{ steps.versions.outputs.next_version }}"

      - name: Push sync branch
        if: steps.guard.outputs.exists == 'false'
        run: git push origin "${{ steps.versions.outputs.sync_branch }}"

      - name: Ensure the sync label exists
        if: steps.guard.outputs.exists == 'false'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh label create sync --description "Sync pull request from a release branch back into <integration-branch>" --color 0e8a16 \
            || gh label list --search sync >/dev/null

      - name: Open pull request into <integration-branch>
        if: steps.guard.outputs.exists == 'false'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_VERSION: ${{ steps.versions.outputs.next_version }}
          CONFLICT: ${{ steps.merge.outputs.conflict }}
        run: |
          if [ "$CONFLICT" = "true" ]; then
            TITLE="Sync ${RELEASE_VERSION} (merge conflicts to resolve)"
            BODY="Merges \`<stable-branch>\` (\`${RELEASE_VERSION}\`) back into \`<integration-branch>\`. The automatic merge conflicted (usually on the version line), so this branch points at the release commit and the development version was **not** bumped. Resolve the conflicts keeping \`<integration-branch>\`'s development version and taking the release's changes otherwise."
          else
            TITLE="Sync ${NEXT_VERSION}"
            BODY="Merges \`<stable-branch>\` back into \`<integration-branch>\` after releasing \`${RELEASE_VERSION}\`, and moves \`MARKETING_VERSION\` in \`project.yml\` to \`${NEXT_VERSION}\` (\`CURRENT_PROJECT_VERSION\` + 1)."
          fi
          gh pr create \
            --base <integration-branch> \
            --head "${SYNC_BRANCH}" \
            --title "${TITLE}" \
            --body "${BODY}" \
            --label sync
```

## `.github/scripts/sync_versions.py`

`branching: gitflow` only.

```python
#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_VERSION from the environment and updates:
- project.yml: MARKETING_VERSION = NEXT_VERSION and CURRENT_PROJECT_VERSION + 1 — the same quote-tolerant,
  first-occurrence regexes iru-swift-bump-version uses on XcodeGen manifests. A macOS app has no -SNAPSHOT/-dev
  marker (App Store Connect and notarization take x.y.z only), so the next patch is the development version and
  the build number keeps increasing monotonically.
- README.md "Current development version" / "Latest release" status-table rows, when present.
- docs/antora.yml's version (the latest release), when present.

CHANGELOG.md is intentionally untouched: its release section was written before the tag was published.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def update_project_yml(next_version):
    path = REPO_ROOT / "project.yml"
    text = path.read_text()
    text, n_mv = re.subn(
        r'(MARKETING_VERSION:[ \t]*)("?)[^"\s]+\2',
        lambda m: f"{m.group(1)}{m.group(2)}{next_version}{m.group(2)}",
        text,
        count=1,
    )
    if n_mv == 0:
        sys.exit(f"{path}: MARKETING_VERSION not found")

    def bump(m):
        return f"{m.group(1)}{m.group(2)}{int(m.group(3)) + 1}{m.group(2)}"

    text, n_cpv = re.subn(r'(CURRENT_PROJECT_VERSION:[ \t]*)("?)(\d+)\2', bump, text, count=1)
    if n_cpv == 0:
        sys.exit(f"{path}: CURRENT_PROJECT_VERSION not found")
    path.write_text(text)


def update_readme(release_version, next_version):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    # Adjust these row labels if this repository's README table words them differently.
    updated = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_version}\g<2>", text)
    updated = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", updated)
    if updated != text:
        path.write_text(updated)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = path.read_text()
    updated, count = re.subn(r"(?m)^version:.*$", f"version: {release_version}", text, count=1)
    if count:
        path.write_text(updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_version = os.environ["NEXT_VERSION"]
    for name, value in (("RELEASE_VERSION", release_version), ("NEXT_VERSION", next_version)):
        if not VERSION_RE.match(value):
            sys.exit(f"{name} '{value}' is not x.y.z")
    update_project_yml(next_version)
    update_readme(release_version, next_version)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
```

## `.github/workflows/security.yml`

Always written; each job gated by its `security-*` key. CodeQL `swift` needs macOS (there is no Linux Swift
extractor) — it is the only macOS job here; with `macos-ci: stable` it follows the same schedule as `build.yml`'s
macOS job plus the weekly run.

```yaml
name: Security

on:
  push:
    # >>> gitflow
    branches: [ <integration-branch>, <stable-branch> ]
    # <<< gitflow
    # >>> main-only
    branches: [ <stable-branch> ]
    # <<< main-only
  pull_request:
  schedule:
    - cron: '0 6 * * 1' # weekly, Monday 06:00 UTC
  workflow_dispatch:

permissions:
  contents: read

jobs:
  # >>> security-dependency-review
  dependency-review:
    name: Dependency review
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'
    permissions:
      contents: read
      pull-requests: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Dependency review
        uses: actions/dependency-review-action@v5.0.0

  # <<< security-dependency-review
  # >>> security-codeql
  # Swift — macOS only, ~10x minutes on private repositories. The .xcodeproj is generated (gitignored), so it is
  # generated before the manual build CodeQL observes.
  codeql:
    name: CodeQL (swift)
    # >>> macos-ci: stable
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< macos-ci: stable
    runs-on: <runner>
    timeout-minutes: 60
    permissions:
      contents: read
      security-events: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'
      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          xcodegen generate
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: swift
          build-mode: manual
      - name: Build the app
        run: |
          xcodebuild build -project <AppName>.xcodeproj -scheme <AppName> -destination 'generic/platform=macOS' \
            -derivedDataPath build/dd-codeql CODE_SIGNING_ALLOWED=NO -quiet
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:swift

  # <<< security-codeql
  # >>> security-osv
  osv-scanner:
    name: OSV-Scanner
    permissions:
      contents: read
      security-events: write
    uses: google/osv-scanner-action/.github/workflows/osv-scanner-reusable.yml@v2.7.0
    with:
      scan-args: |-
        --recursive
        ./

  # <<< security-osv
  # >>> security-gitleaks
  gitleaks:
    name: gitleaks
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0
      - name: Run gitleaks
        uses: gitleaks/gitleaks-action@v3
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  # <<< security-gitleaks
```

## `.github/dependabot.yml`

Always written (no opt-out). The only SwiftPM manifest is the local package (`Packages/Core/Package.swift` +
its committed `Package.resolved`); the app target's dependencies come from `project.yml`, which Dependabot doesn't
read.

```yaml
version: 2
updates:
  - package-ecosystem: swift
    directory: /Packages/Core
    schedule:
      interval: weekly
    groups:
      swift-dependencies:
        patterns:
          - "*"

  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
    groups:
      github-actions-dependencies:
        patterns:
          - "*"
```
