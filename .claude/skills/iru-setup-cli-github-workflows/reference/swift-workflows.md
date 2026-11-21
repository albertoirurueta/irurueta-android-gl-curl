# Swift workflow templates (`language: swift`)

`build.yml` and `release.yml` for a Swift command-line tool laid out like `iru-setup-swift-cli`'s scaffold: one root
`Package.swift` with an `.executableTarget` `<Bin>` (swift-argument-parser, thin), a library target `<Bin>Kit` (logic
and `Version.swift`: `public let toolVersion = "0.1.0-dev"`), `Tests/<Bin>KitTests` and `Tests/<Bin>CLITests` (runs the
built binary), `.swiftlint.yml`, `.swift-format`, and — when `docc` holds — the `swift-docc-plugin` dependency.
Markers and shared placeholders are explained in `shared-templates.md`; `release.yml` ends with that file's
**release tail** (`publish`, and `homebrew` with the `homebrew` channel).

Swift placeholders: `<Bin>` (the executable target), `<linux-swift-tag>` (the `swift` Docker image tag for the Linux
jobs, e.g. `6.4.1-noble`), `<swift-version>` (its toolchain version, e.g. `6.4.1` — the Static Linux SDK must match it
exactly), `<static-sdk-version>` / `<static-sdk-checksum>` (from `https://www.swift.org/api/v1/install/releases.json`,
the `Static SDK` platform of that release), `<macos-runner>` / `<xcode-version>` (Step 2), `<swiftlint-version>`,
`<repo>` (DocC `--hosting-base-path <repo>/api`), `<cli-docs-command>` (default `scripts/generate-docs.sh build/gendocs`
— the scaffold's generator: swift-argument-parser's `generate-manual` plugin for the man page, `<bin> completion
bash|zsh|fish`, `<bin> schema > schema.json`, and `<bin> schema --asciidoc` →
`docs/modules/ROOT/partials/cli-reference.adoc`; the output directory argument keeps it out of the release `dist/`).

**Linux first.** Lint, tests, coverage and the smoke test run in a `swift:<linux-swift-tag>` container on
`ubuntu-latest`; macOS (~10× Linux minutes on a private repository) runs only the `cross-os` leg and CodeQL, gated by
`cross-os-ci`. **No Windows leg and no Windows binary**: Swift on Windows needs a separate toolchain install per job
and swift-argument-parser's terminal/signal handling there is not part of the scaffold contract; a Windows user is
pointed at the Linux binary under WSL — say so in the report and recommend Rust, Go or .NET when Windows is a primary
target.

**Coverage bar on `<Bin>Kit`:** `swift test --enable-code-coverage`, then `swift test --show-codecov-path` (the
llvm-cov JSON export SwiftPM writes) filtered to `Sources/<Bin>Kit/`; the executable target is thin and "not
measured". For Sonar the lcov export is converted to SonarQube's generic coverage XML by the same
`lcov_to_sonar_generic.py` `iru-setup-macos-github-workflows` vendors (written only with `sonar`).

## `.github/workflows/build.yml`

Jobs: `lint` (Linux container), `test` (Linux container), `cross-os` (macOS, gated, `needs: lint`), `sonar` (Linux,
`sonar` only), `api-docs` (Linux container — the CLI-reference generator and its staleness check, DocC with `docc`), `docs` (Linux), `site` (Linux — merges Antora + DocC),
`deploy-docs` (`pages: yes`).

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

# A newer push to the same ref cancels the run in flight: macOS minutes are expensive on private repositories.
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
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
          apt-get update -qq
          apt-get install -y -qq --no-install-recommends ca-certificates curl unzip >/dev/null
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

      - name: swift format lint
        run: swift format lint --strict --recursive Sources Tests Package.swift

  test:
    name: Tests, coverage, contract smoke test (Linux)
    runs-on: ubuntu-latest
    container: swift:<linux-swift-tag>
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Install jq
        run: apt-get update -qq && apt-get install -y -qq --no-install-recommends jq >/dev/null

      # Swift Testing + XCTest, including <Bin>CLITests (runs the built binary: exit codes, JSON, no-TTY output).
      - name: Tests with coverage
        run: swift test --enable-code-coverage

      - name: Coverage bar (<Bin>Kit, 80% of lines)
        run: |
          python3 - "$(swift test --show-codecov-path)" <<'PY'
          import json, sys
          files = json.load(open(sys.argv[1]))["data"][0]["files"]
          kit = [f for f in files if "/Sources/<Bin>Kit/" in f["filename"]]
          count = sum(f["summary"]["lines"]["count"] for f in kit)
          covered = sum(f["summary"]["lines"]["covered"] for f in kit)
          percent = 100.0 * covered / count if count else 100.0
          print(f"<Bin>Kit line coverage: {percent:.2f}% of {count} lines (bar: 80%)")
          if percent < 80:
              sys.exit(f"::error::<Bin>Kit line coverage {percent:.2f}% is below the 80% bar")
          PY
      # >>> sonar

      # lcov from every test bundle (Linux: plain executables; macOS: .xctest bundles), converted to Sonar's generic
      # coverage XML with paths relative to the repository root.
      - name: Coverage report for Sonar
        run: |
          set -euo pipefail
          BIN="$(swift build --show-bin-path)"
          objects=()
          for t in "$BIN"/*.xctest; do
            if [[ -d "$t" ]]; then objects+=(-object "$t/Contents/MacOS/$(basename "$t" .xctest)"); else objects+=(-object "$t"); fi
          done
          llvm-cov export -format=lcov -instr-profile "$BIN/codecov/default.profdata" "${objects[@]}" \
            --ignore-filename-regex='(\.build/|Tests/)' > coverage.lcov
          python3 .github/scripts/lcov_to_sonar_generic.py coverage.lcov > sonarqube-generic-coverage.xml

      - name: Upload the coverage report
        uses: actions/upload-artifact@v7
        with:
          name: coverage
          path: sonarqube-generic-coverage.xml
          retention-days: 7
      # <<< sonar

      - name: Contract smoke test
        run: |
          swift build --product <bin>
          version="$(sed -nE 's/.*toolVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' Sources/<Bin>Kit/Version.swift | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$(swift build --show-bin-path)/<bin>" "$version"
      # >>> ci-tests: full

      - name: Contract smoke test (release build)
        run: |
          swift build -c release --product <bin>
          version="$(sed -nE 's/.*toolVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' Sources/<Bin>Kit/Version.swift | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$(swift build -c release --show-bin-path)/<bin>" "$version"
      # <<< ci-tests: full

  # macOS minutes cost ~10x Linux minutes on private repositories — see cross-os-ci.
  cross-os:
    name: Tests (macOS)
    needs: lint
    # >>> cross-os-ci: stable
    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< cross-os-ci: stable
    runs-on: <macos-runner>
    timeout-minutes: 45
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      - name: Tests
        run: swift test
      # >>> ci-tests: full

      # The universal binary the release ships (arm64 + x86_64); the runner executes its arm64 slice.
      - name: Contract smoke test (universal release build)
        run: |
          swift build -c release --arch arm64 --arch x86_64 --product <bin>
          version="$(sed -nE 's/.*toolVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' Sources/<Bin>Kit/Version.swift | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/<bin>" "$version"
      # <<< ci-tests: full
  # >>> sonar

  # Skipped on pull requests from forks — they never receive SONAR_TOKEN.
  sonar:
    name: SonarQube analysis
    needs: [ lint, test ]
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
          name: coverage

      - name: Run SonarQube analysis
        uses: SonarSource/sonarqube-scan-action@v8
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        with:
          args: >
            -Dsonar.projectKey=<sonar-project-key>
            # >>> sonar: cloud
            -Dsonar.organization=<sonar-organization>
            # <<< sonar: cloud
            -Dsonar.host.url=<sonar-host-url>
            -Dsonar.sources=Sources
            -Dsonar.tests=Tests
            -Dsonar.coverage.exclusions=Sources/<Bin>/**
            -Dsonar.coverageReportPaths=sonarqube-generic-coverage.xml
  # <<< sonar

  # Needs the Swift toolchain: the generated CLI reference (and its staleness check) and DocC of <Bin>Kit
  # (--warnings-as-errors is the documentation gate, links baked for /<repo>/api).
  api-docs:
    name: Docs — CLI reference, DocC (<Bin>Kit)
    runs-on: ubuntu-latest
    container: swift:<linux-swift-tag>
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      # >>> cli-docs

      # The CLI reference is generated from the command tree; committed pages must match it
      # (irurueta docs: ai/cli-for-agents/documenting-clis.adoc — "regenerate and diff in CI").
      - name: Generate the CLI reference from the command tree
        run: <cli-docs-command>

      - name: Fail when the committed CLI reference is stale
        run: |
          if ! git diff --exit-code -- docs/; then
            echo "::error::The generated CLI reference differs from the committed one — run '<cli-docs-command>' and commit the result."
            exit 1
          fi
      # <<< cli-docs
      # >>> docc

      - name: DocC
        run: |
          mkdir -p build/docc
          swift package --allow-writing-to-directory build/docc generate-documentation --target <Bin>Kit \
            --warnings-as-errors --transform-for-static-hosting --hosting-base-path <repo>/api --output-path build/docc

      - name: Upload the DocC archive
        uses: actions/upload-artifact@v7
        with:
          name: docc-api
          path: build/docc
          retention-days: 7
      # <<< docc

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

  # Merges the DocC archive under the Antora site's api/ — the layout every catalog stack uses.
  site:
    name: Docs — merge Antora + DocC
    needs: [ docs, api-docs ]
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
      # >>> docc

      - name: Download the DocC archive
        uses: actions/download-artifact@v8
        with:
          name: docc-api
          path: site/api
      # <<< docc

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

`.github/scripts/lcov_to_sonar_generic.py` (with `sonar`): copy it verbatim from
`iru-setup-macos-github-workflows`' `reference/workflow-templates.md` (same converter, run from the repository root).

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. Triggers, dry run and tag check as in the other languages; the version is
`Sources/<Bin>Kit/Version.swift`'s `toolVersion` (cut releases with `iru-release`/`iru-swift-bump-version`).

| Archive target | Built on | How |
|---|---|---|
| `universal-apple-darwin` | `<macos-runner>` | `swift build -c release --arch arm64 --arch x86_64` — one universal Mach-O; optionally Developer ID–signed and notarized |
| `x86_64-swift-linux-musl` / `aarch64-swift-linux-musl` | `ubuntu-latest`, `swift:<linux-swift-tag>` container | the **Static Linux SDK** (`swift sdk install <url> --checksum <sha256>`, exactly matching the container's toolchain version) → fully static binaries that run on any Linux distribution; both architectures cross-compile from the one x86-64 runner, and the x86-64 binary is smoke-tested there |

**Notarization (optional, `notarize`):** with the Developer ID and App Store Connect secrets present, the macOS job
signs the binary with the hardened runtime and a secure timestamp, submits a `ditto` zip to `notarytool --wait`, and
fails unless the status is `Accepted`. A bare Mach-O **can't be stapled** (only apps, disk images and installer
packages can), so Gatekeeper checks the ticket online on first run; Homebrew and `curl` downloads carry no
quarantine attribute anyway. Without the secrets the steps skip and the binary ships ad-hoc–signed (what the Swift
linker produces on Apple silicon).

```yaml
name: Release

on:
  release:
    types: [released]
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag to build (e.g. v1.2.0) — a dry run: nothing is uploaded or published"
        required: true
        type: string

permissions:
  contents: read

env:
  RELEASE_TAG: ${{ github.event.release.tag_name || inputs.tag }}

jobs:
  version:
    name: Check the release version
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.version.outputs.version }}
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Read and check the release version
        id: version
        run: |
          set -euo pipefail
          version="$(sed -nE 's/.*toolVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' Sources/<Bin>Kit/Version.swift | head -n 1)"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Version.swift toolVersion '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::Version.swift toolVersion '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_OUTPUT"

  build-macos:
    name: Build (universal-apple-darwin)
    needs: version
    runs-on: <macos-runner>
    timeout-minutes: 60
    env:
      VERSION: ${{ needs.version.outputs.version }}
      # >>> notarize
      HAS_NOTARIZATION_SECRETS: ${{ secrets.MACOS_CERTIFICATE_P12_BASE64 != '' && secrets.ASC_PRIVATE_KEY != '' }}
      # <<< notarize
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'

      - name: Build the universal binary
        run: |
          swift build -c release --arch arm64 --arch x86_64 --product <bin>
          echo "BIN_DIR=$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)" >> "$GITHUB_ENV"

      - name: Contract smoke test
        run: |
          lipo -info "$BIN_DIR/<bin>"
          bash .github/scripts/cli_smoke_test.sh "$BIN_DIR/<bin>" "$VERSION"

      # >>> cli-docs
      # Man page (swift-argument-parser's generate-manual plugin) and completions, from the command tree.
      - name: Generate the man page and completions
        run: <cli-docs-command>
      # <<< cli-docs
      # >>> notarize

      - name: Import the Developer ID Application certificate
        if: env.HAS_NOTARIZATION_SECRETS == 'true'
        uses: apple-actions/import-codesign-certs@v7
        with:
          p12-file-base64: ${{ secrets.MACOS_CERTIFICATE_P12_BASE64 }}
          p12-password: ${{ secrets.MACOS_CERTIFICATE_PASSWORD }}

      # Hardened runtime + secure timestamp are notarization requirements; a bare binary can't be stapled.
      - name: Sign and notarize
        if: env.HAS_NOTARIZATION_SECRETS == 'true'
        env:
          ASC_PRIVATE_KEY: ${{ secrets.ASC_PRIVATE_KEY }}
          ASC_KEY_ID: ${{ secrets.ASC_KEY_ID }}
          ASC_ISSUER_ID: ${{ secrets.ASC_ISSUER_ID }}
        run: |
          set -euo pipefail
          identity="$(security find-identity -v -p codesigning | awk '/"Developer ID Application/ { print $2; exit }')"
          if [[ -z "$identity" ]]; then
            echo "::error::No Developer ID Application identity in the keychain."
            exit 1
          fi
          codesign --force --options runtime --timestamp --sign "$identity" "$BIN_DIR/<bin>"
          codesign --verify --strict --verbose=2 "$BIN_DIR/<bin>"
          umask 077
          printf '%s\n' "$ASC_PRIVATE_KEY" > "$RUNNER_TEMP/AuthKey.p8"
          trap 'rm -f "$RUNNER_TEMP/AuthKey.p8"' EXIT
          ditto -c -k --keepParent "$BIN_DIR/<bin>" "$RUNNER_TEMP/<bin>-notarize.zip"
          result="$(xcrun notarytool submit "$RUNNER_TEMP/<bin>-notarize.zip" --key "$RUNNER_TEMP/AuthKey.p8" \
            --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" --wait --timeout 30m --output-format json)"
          echo "$result"
          status="$(jq -r '.status // ""' <<< "$result")"
          if [[ "$status" != "Accepted" ]]; then
            xcrun notarytool log "$(jq -r '.id' <<< "$result")" --key "$RUNNER_TEMP/AuthKey.p8" \
              --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" || true
            echo "::error::Notarization ended with status '$status'."
            exit 1
          fi

      - name: Notarization skipped
        if: env.HAS_NOTARIZATION_SECRETS != 'true'
        run: echo "::warning::No Developer ID / App Store Connect secrets — the macOS binary ships unsigned by Developer ID and un-notarized."
      # <<< notarize

      - name: Package
        run: |
          set -euo pipefail
          name="<bin>-$VERSION-universal-apple-darwin"
          stage="$RUNNER_TEMP/$name"
          mkdir -p "$stage" dist
          cp "$BIN_DIR/<bin>" "$stage/"
          if [[ -d build/gendocs/man ]]; then cp -r build/gendocs/man "$stage/man"; fi
          if [[ -d build/gendocs/completions ]]; then cp -r build/gendocs/completions "$stage/completions"; fi
          for file in README.md CHANGELOG.md LICENSE*; do
            if [[ -f "$file" ]]; then cp "$file" "$stage/"; fi
          done
          tar -C "$stage" -czf "dist/$name.tar.gz" .

      - name: Upload the archive
        uses: actions/upload-artifact@v7
        with:
          name: dist-universal-apple-darwin
          path: dist/
          retention-days: 7

  build-linux:
    name: Build (static Linux, x86_64 + aarch64)
    needs: version
    runs-on: ubuntu-latest
    container: swift:<linux-swift-tag>
    env:
      VERSION: ${{ needs.version.outputs.version }}
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Install jq
        run: apt-get update -qq && apt-get install -y -qq --no-install-recommends jq >/dev/null

      # The SDK must match the container's toolchain version exactly (swift-<swift-version>-RELEASE).
      - name: Install the Static Linux SDK
        run: |
          swift sdk install \
            "https://download.swift.org/swift-<swift-version>-release/static-sdk/swift-<swift-version>-RELEASE/swift-<swift-version>-RELEASE_static-linux-<static-sdk-version>.artifactbundle.tar.gz" \
            --checksum <static-sdk-checksum>
          swift sdk list

      # >>> cli-docs
      - name: Generate the man page and completions
        run: <cli-docs-command>
      # <<< cli-docs

      - name: Build and package
        run: |
          set -euo pipefail
          mkdir -p dist
          for arch in x86_64 aarch64; do
            sdk="${arch}-swift-linux-musl"
            swift build -c release --swift-sdk "$sdk" --product <bin>
            out="$(swift build -c release --swift-sdk "$sdk" --show-bin-path)"
            if [[ "$arch" == x86_64 ]]; then
              bash .github/scripts/cli_smoke_test.sh "$out/<bin>" "$VERSION"
            fi
            name="<bin>-$VERSION-$sdk"
            stage="$RUNNER_TEMP/$name"
            mkdir -p "$stage"
            cp "$out/<bin>" "$stage/"
            if [[ -d build/gendocs/man ]]; then cp -r build/gendocs/man "$stage/man"; fi
            if [[ -d build/gendocs/completions ]]; then cp -r build/gendocs/completions "$stage/completions"; fi
            for file in README.md CHANGELOG.md LICENSE*; do
              if [[ -f "$file" ]]; then cp "$file" "$stage/"; fi
            done
            tar -C "$stage" -czf "dist/$name.tar.gz" .
          done
          ls -l dist

      - name: Upload the archives
        uses: actions/upload-artifact@v7
        with:
          name: dist-swift-linux-musl
          path: dist/
          retention-days: 7
```

Then append the **release tail** from `shared-templates.md` with one change: its `publish` job's
`needs: [ version, build ]` becomes `needs: [ version, build-macos, build-linux ]`. The Swift tool has no `winget`
channel (no Windows binary).
