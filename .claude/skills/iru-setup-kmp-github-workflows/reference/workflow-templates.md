# KMP workflow templates

Conditional markers: `# >>> <condition>` … `# <<< <condition>` blocks are written only when the condition holds; the marker lines are never written. Conditions: `gitflow` / `main-only`, `ios` (`ios-ci` ≠ `none`), `ios-ci: stable`, `ci-tests: unit` / `ci-tests: full`, `pages: yes` / `pages: no`, `sonar` (≠ `none`), `distribution: store` / `distribution: internal`, and each enabled `security-*` key. Every combination listed in SKILL.md's verification notes was rendered and passed `actionlint` 1.7.12 (with shellcheck) and a YAML parse on 2026-10-15.

## `.github/workflows/build.yml`

Always written. Jobs: `android` (Linux), `ios` (macOS — only when `ios-ci` ≠ `none`; the job-level `if:` only for `ios-ci: stable`), `docs`, `deploy-docs` (`pages: yes`).

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

concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  android:
    name: Android / JVM — static analysis, tests, coverage
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write
      pull-requests: write
      security-events: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      # --continue: report every analyzer's findings in one run instead of stopping at the first failing tool.
      - name: Static analysis (detekt, ktlint, Android Lint)
        run: ./gradlew --continue detekt ktlintCheck :androidApp:lint :shared:lint

      # JVM/Android host tests only — Kotlin/Native iOS code has no coverage tool; the iOS job runs its tests.
      - name: Tests and coverage (80% line bound)
        run: ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest koverXmlReport koverVerify

      - name: Build the Android app
        run: ./gradlew :androidApp:assembleDebug

      - name: Publish test results
        if: (!cancelled())
        uses: EnricoMi/publish-unit-test-result-action@v2
        with:
          files: |
            shared/build/test-results/testAndroidHostTest/*.xml
            androidApp/build/test-results/testDebugUnitTest/*.xml

      # The UI tests' captures (.ui-screenshots/<kind>/<Id>/<state>--<dir>--<theme>--android.png) — review evidence.
      - name: Upload UI screenshots (Android)
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-screenshots-android
          path: .ui-screenshots/
          retention-days: 7
          if-no-files-found: ignore

      - name: Upload Android Lint SARIF (androidApp)
        if: (!cancelled()) && hashFiles('androidApp/build/reports/lint-results-debug.sarif') != ''
        uses: github/codeql-action/upload-sarif@v4
        with:
          sarif_file: androidApp/build/reports/lint-results-debug.sarif
          category: android-lint-androidApp

      - name: Upload Android Lint SARIF (shared)
        if: (!cancelled()) && hashFiles('shared/build/reports/lint-results-androidMain.sarif') != ''
        uses: github/codeql-action/upload-sarif@v4
        with:
          sarif_file: shared/build/reports/lint-results-androidMain.sarif
          category: android-lint-shared

      - name: Upload detekt SARIF
        if: (!cancelled()) && hashFiles('shared/build/reports/detekt/detekt.sarif') != ''
        uses: github/codeql-action/upload-sarif@v4
        with:
          sarif_file: shared/build/reports/detekt/detekt.sarif
          category: detekt-shared

      - name: Upload coverage report
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: kover-report
          path: build/reports/kover/
          retention-days: 14
      # >>> sonar

      # Skipped on pull requests from forks — they never receive SONAR_TOKEN.
      - name: Run SonarQube analysis
        if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: ./gradlew sonar
      # <<< sonar
  # >>> ios

  # macOS minutes cost ~10x Linux minutes on private repositories — see iru-setup-kmp-github-workflows' ios-ci.
  ios:
    name: iOS — Kotlin/Native tests, SwiftLint, app build
    # >>> ios-ci: stable
    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< ios-ci: stable
    runs-on: <runner>
    timeout-minutes: 60
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      # The Kotlin/Native toolchain and its dependencies (~1 GB) live in ~/.konan, outside Gradle's own cache.
      - name: Cache Kotlin/Native toolchain
        uses: actions/cache@v6
        with:
          path: ~/.konan
          key: ${{ runner.os }}-konan-${{ hashFiles('gradle/libs.versions.toml') }}
          restore-keys: |
            ${{ runner.os }}-konan-

      - name: Kotlin/Native iOS simulator tests
        run: ./gradlew :shared:iosSimulatorArm64Test

      - name: Publish iOS test results
        if: (!cancelled())
        uses: EnricoMi/publish-unit-test-result-action/macos@v2
        with:
          check_name: iOS test results
          files: shared/build/test-results/iosSimulatorArm64Test/*.xml

      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          cd iosApp && xcodegen generate

      - name: SwiftLint
        run: |
          command -v swiftlint >/dev/null || brew install swiftlint
          cd iosApp && swiftlint lint --strict

      # The newest installed iOS runtime's first iPhone — a bare `name=` destination resolves against OS:latest.
      - name: Pick an iOS simulator
        id: simulator
        run: |
          UDID=$(xcrun simctl list devices available -j | python3 -c '
          import json, re, sys
          devices = json.load(sys.stdin)["devices"]
          runtimes = sorted((k for k in devices if ".iOS-" in k), key=lambda k: [int(n) for n in re.findall(r"\d+", k.split(".iOS-")[-1])])
          for runtime in reversed(runtimes):
              phones = [d for d in devices[runtime] if d["name"].startswith("iPhone")]
              if phones:
                  print(phones[0]["udid"])
                  break
          ')
          echo "udid=$UDID" >> "$GITHUB_OUTPUT"
      # >>> ci-tests: unit

      # ci-tests: unit — proves the app compiles and links Shared.framework; the XCTest bundle stays local.
      - name: Build the iOS app
        run: |
          xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
            -destination "platform=iOS Simulator,id=${{ steps.simulator.outputs.udid }}" \
            -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO -quiet
      # <<< ci-tests: unit
      # >>> ci-tests: full

      - name: Test the iOS app
        run: |
          xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
            -destination "platform=iOS Simulator,id=${{ steps.simulator.outputs.udid }}" \
            -derivedDataPath iosApp/build/DerivedData -resultBundlePath iosApp/build/iosApp.xcresult \
            CODE_SIGNING_ALLOWED=NO -quiet

      - name: Upload the xcresult bundle
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: iosApp-xcresult
          path: iosApp/build/iosApp.xcresult
          retention-days: 14
      # <<< ci-tests: full

      # Kotlin/Native Compose UI test captures (--ios.png), plus the iosApp XCTest ones with ci-tests: full.
      - name: Upload UI screenshots (iOS)
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-screenshots-ios
          path: .ui-screenshots/
          retention-days: 7
          if-no-files-found: ignore
  # <<< ios

  docs:
    name: Docs — Antora + Dokka
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Generate Dokka API docs
        run: ./gradlew :shared:dokkaGenerate

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

      # Search index. Runs before the API docs are merged below, so only the Antora pages are indexed; move this
      # step after the merge if the API pages should be searchable.
      - name: Index the site for search
        working-directory: docs
        run: npx pagefind --site build/site

      - name: Merge Dokka API docs into the Antora site
        run: |
          touch ./docs/build/site/.nojekyll
          mkdir -p ./docs/build/site/api
          cp -r ./shared/build/dokka/html/. ./docs/build/site/api/

      # Optional size guard (GitHub Pages rejects a site over 1 GB): uncomment; keep it after the last step that
      # writes into build/site.
      # - name: Check site size
      #   working-directory: docs
      #   run: |
      #     size=$(du -sk build/site | cut -f1)
      #     echo "build/site is ${size} KiB"
      #     if [ "$size" -gt 960000 ]; then echo "::error::site over 960,000 KiB"; exit 1; fi
      #     if [ "$size" -gt 900000 ]; then echo "::warning::site over 900,000 KiB"; fi
      # >>> pages: yes

      - name: Upload Pages artifact
        if: github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>'
        uses: actions/upload-pages-artifact@v5
        with:
          path: ./docs/build/site
      # <<< pages: yes
      # >>> pages: no

      - name: Upload docs artifact
        uses: actions/upload-artifact@v7
        with:
          name: docs-site
          path: ./docs/build/site
          retention-days: 14
      # <<< pages: no
  # >>> pages: yes

  deploy-docs:
    name: Deploy docs to GitHub Pages
    needs: docs
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

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. `<play-track>` is `production` by default (the Android sibling's choice) — offer `internal` for a first rollout.

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

permissions:
  contents: read

env:
  RELEASE_TAG: ${{ github.event.release.tag_name || inputs.tag }}

jobs:
  android-release:
    name: Android — signed bundle and upload
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      # androidApp/build.gradle.kts reads these four exact env var names; SIGNING_STORE_FILE resolves relative to
      # the androidApp module, so the keystore is decoded into androidApp/release.jks (gitignored by *.jks).
      - name: Decode the release keystore
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > androidApp/release.jks

      - name: Build the signed release bundle
        env:
          SIGNING_STORE_FILE: release.jks
          SIGNING_STORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: ./gradlew :androidApp:bundleRelease

      - name: Upload the bundle as a workflow artifact
        uses: actions/upload-artifact@v7
        with:
          name: androidApp-release-aab
          path: androidApp/build/outputs/bundle/release/androidApp-release.aab
      # >>> distribution: store

      - name: Upload to Google Play
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: <application-id>
          releaseFiles: androidApp/build/outputs/bundle/release/androidApp-release.aab
          track: <play-track>
      # <<< distribution: store
      # >>> distribution: internal

      - name: Distribute via Firebase App Distribution
        uses: wzieba/Firebase-Distribution-Github-Action@v1
        with:
          appId: ${{ secrets.FIREBASE_APP_ID }}
          serviceCredentialsFileContent: ${{ secrets.FIREBASE_SERVICE_CREDENTIALS }}
          groups: internal-testers
          file: androidApp/build/outputs/bundle/release/androidApp-release.aab
      # <<< distribution: internal

  ios-release:
    name: iOS — archive, sign, upload to TestFlight
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

      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          cd iosApp && xcodegen generate

      # One App Store Connect API key authenticates automatic signing (-allowProvisioningUpdates), the export and
      # the TestFlight upload. The TestFlight action takes the raw .p8 content, so it is also exported to the env.
      - name: Write the App Store Connect API key
        env:
          API_KEY_BASE64: ${{ secrets.APP_STORE_CONNECT_API_KEY_BASE64 }}
        run: |
          echo "$API_KEY_BASE64" | base64 -d > "$RUNNER_TEMP/AuthKey.p8"
          {
            echo "APP_STORE_CONNECT_API_PRIVATE_KEY<<EOF"
            cat "$RUNNER_TEMP/AuthKey.p8"
            echo "EOF"
          } >> "$GITHUB_ENV"

      # The archive's pre-build phase runs ./gradlew :shared:embedAndSignAppleFrameworkForXcode for iphoneos/Release.
      # TEAM_ID overrides iosApp/Configuration/Config.xcconfig's value (DEVELOPMENT_TEAM = $(TEAM_ID)).
      - name: Archive the iOS app
        env:
          API_KEY_ID: ${{ secrets.APP_STORE_CONNECT_API_KEY_ID }}
          API_ISSUER_ID: ${{ secrets.APP_STORE_CONNECT_API_KEY_ISSUER_ID }}
          TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          xcodebuild archive -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
            -destination 'generic/platform=iOS' -archivePath "$RUNNER_TEMP/iosApp.xcarchive" \
            TEAM_ID="$TEAM_ID" -allowProvisioningUpdates \
            -authenticationKeyPath "$RUNNER_TEMP/AuthKey.p8" -authenticationKeyID "$API_KEY_ID" \
            -authenticationKeyIssuerID "$API_ISSUER_ID"

      - name: Export the .ipa
        env:
          API_KEY_ID: ${{ secrets.APP_STORE_CONNECT_API_KEY_ID }}
          API_ISSUER_ID: ${{ secrets.APP_STORE_CONNECT_API_KEY_ISSUER_ID }}
          TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          cat > "$RUNNER_TEMP/ExportOptions.plist" <<PLIST
          <?xml version="1.0" encoding="UTF-8"?>
          <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
          <plist version="1.0">
          <dict>
            <key>method</key>
            <string>app-store-connect</string>
            <key>teamID</key>
            <string>${TEAM_ID}</string>
            <key>signingStyle</key>
            <string>automatic</string>
          </dict>
          </plist>
          PLIST
          xcodebuild -exportArchive -archivePath "$RUNNER_TEMP/iosApp.xcarchive" \
            -exportPath "$RUNNER_TEMP/export" -exportOptionsPlist "$RUNNER_TEMP/ExportOptions.plist" \
            -allowProvisioningUpdates -authenticationKeyPath "$RUNNER_TEMP/AuthKey.p8" \
            -authenticationKeyID "$API_KEY_ID" -authenticationKeyIssuerID "$API_ISSUER_ID"

      - name: Upload to TestFlight
        uses: apple-actions/upload-testflight-build@v5
        with:
          app-path: ${{ runner.temp }}/export/iosApp.ipa
          issuer-id: ${{ secrets.APP_STORE_CONNECT_API_KEY_ISSUER_ID }}
          api-key-id: ${{ secrets.APP_STORE_CONNECT_API_KEY_ID }}
          api-private-key: ${{ env.APP_STORE_CONNECT_API_PRIVATE_KEY }}
```

## `.github/workflows/sync.yml`

`branching: gitflow` only, always together with `sync_versions.py`.

```yaml
name: Sync

# After a release is published from <stable-branch>, open a pull request that merges it back into
# <integration-branch> and bumps VERSION_NAME to the next -SNAPSHOT.
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
          IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
          if [ -z "${MAJOR:-}" ] || [ -z "${MINOR:-}" ] || [ -z "${PATCH:-}" ]; then
            echo "::error::Release tag '$RELEASE_VERSION' is not in X.Y.Z form; cannot compute the next snapshot."
            exit 1
          fi
          NEXT_PATCH=$((PATCH + 1))
          {
            echo "release_version=$RELEASE_VERSION"
            echo "next_snapshot=${MAJOR}.${MINOR}.${NEXT_PATCH}-SNAPSHOT"
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
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
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

      - name: Bump the development snapshot version
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        env:
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_SNAPSHOT: ${{ steps.versions.outputs.next_snapshot }}
        run: python3 .github/scripts/sync_versions.py

      - name: Commit the version bump
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        run: |
          # --ignore-errors keeps a missing README/docs tree from aborting the step; whatever exists is staged.
          git add --ignore-errors -- gradle.properties README.md docs/antora.yml docs/modules/ROOT/pages || true
          git diff --cached --quiet || git commit -m "Sync ${{ steps.versions.outputs.next_snapshot }}"

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
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_SNAPSHOT: ${{ steps.versions.outputs.next_snapshot }}
          CONFLICT: ${{ steps.merge.outputs.conflict }}
        run: |
          if [ "${CONFLICT}" = "true" ]; then
            TITLE="Sync ${RELEASE_VERSION} (merge conflicts to resolve)"
            BODY="Merges \`<stable-branch>\` (\`${RELEASE_VERSION}\`) back into \`<integration-branch>\`. The automatic merge conflicted (usually on the version line), so this branch points at the release commit and the development version was **not** bumped. Resolve the conflicts keeping \`<integration-branch>\`'s development version and taking the release's changes otherwise."
          else
            TITLE="Sync ${NEXT_SNAPSHOT}"
            BODY="Merges \`<stable-branch>\` back into \`<integration-branch>\` after releasing \`${RELEASE_VERSION}\`, and bumps \`VERSION_NAME\` in \`gradle.properties\` to \`${NEXT_SNAPSHOT}\`."
          fi
          gh pr create \
            --base <integration-branch> \
            --head "${{ steps.versions.outputs.sync_branch }}" \
            --title "${TITLE}" \
            --body "${BODY}" \
            --label sync
```

## `.github/scripts/sync_versions.py`

`branching: gitflow` only.

```python
#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION and NEXT_SNAPSHOT from the environment and updates:
- gradle.properties: VERSION_NAME = NEXT_SNAPSHOT. VERSION_CODE is left alone — only a release bumps it
  (iru-kmp-bump-version), and that release value already arrives through the merge that precedes this script.
- iosApp/Configuration/Config.xcconfig: never touched — MARKETING_VERSION/CURRENT_PROJECT_VERSION only ever hold
  release values (App Store Connect rejects -SNAPSHOT), set by iru-kmp-bump-version on the release branch.
- README.md status-table rows and docs/antora.yml's version (the latest release).

CHANGELOG.md is intentionally untouched: its release section was written before the tag was published.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def update_gradle_properties(next_snapshot):
    path = REPO_ROOT / "gradle.properties"
    text = path.read_text()
    updated, count = re.subn(r"(?m)^(VERSION_NAME\s*=\s*).*$", lambda m: m.group(1) + next_snapshot, text, count=1)
    if count == 0:
        sys.exit(f"{path}: VERSION_NAME not found")
    path.write_text(updated)


def update_readme(release_version, next_snapshot):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = path.read_text()
    # Adjust these row labels if this repository's README table words them differently (iru-setup-readme's
    # defaults are "Current development version" / "Latest release").
    text = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_snapshot}\g<2>", text)
    text = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", text)
    path.write_text(text)


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
    next_snapshot = os.environ["NEXT_SNAPSHOT"]
    update_gradle_properties(next_snapshot)
    update_readme(release_version, next_snapshot)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
```

## `.github/workflows/security.yml`

Always written; each job gated by its `security-*` key.

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
  # Kotlin (shared commonMain/androidMain + androidApp) through the JVM compilations only — CodeQL has no
  # Kotlin/Native extractor, so iosMain is not analyzed. build-mode: manual with caches disabled, because CodeQL must
  # observe a real kotlinc run (a cached/up-to-date task extracts nothing).
  codeql:
    name: CodeQL (java-kotlin)
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: java-kotlin
          build-mode: manual
      - name: Compile the JVM/Android targets
        run: ./gradlew --no-daemon --no-build-cache --no-configuration-cache :shared:compileAndroidMain :androidApp:compileDebugKotlin
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:java-kotlin

  # <<< security-codeql
  # >>> security-codeql-swift
  # Swift (iosApp) — macOS only (no Linux Swift extractor), ~10x minutes on private repositories. The Xcode build
  # first runs the Gradle pre-build phase, i.e. a full Kotlin/Native framework link.
  codeql-swift:
    name: CodeQL (swift)
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
      - name: Set up JDK <java-version>
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'
      - name: Generate the Xcode project
        run: |
          command -v xcodegen >/dev/null || brew install xcodegen
          cd iosApp && xcodegen generate
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: swift
          build-mode: manual
      # ARCHS=arm64: a generic simulator destination otherwise also asks Gradle for x86_64, and the Kotlin
      # framework only declares iosArm64/iosSimulatorArm64 ("Xcode Requested Architecture Not Configured in Gradle").
      - name: Build the iOS app
        run: |
          xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
            -destination 'generic/platform=iOS Simulator' ARCHS=arm64 CODE_SIGNING_ALLOWED=NO -quiet
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:swift

  # <<< security-codeql-swift
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

Always written (no opt-out).

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /
    schedule:
      interval: weekly
    groups:
      gradle-dependencies:
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
