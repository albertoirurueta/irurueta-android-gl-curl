# Go workflow templates (`language: go`)

`build.yml` and `release.yml` for a Go command-line tool laid out like `iru-setup-go-cli`'s scaffold: `go.mod`,
`cmd/<bin>/main.go` (thin), `internal/cli/` (Cobra), `internal/version/version.go` (`var Version = "0.1.0-dev"`,
overridden by `-ldflags -X`), testscript end-to-end tests run in-process, `.golangci.yml` (v2), `tools/gendocs`
(man pages, completions, `docs/modules/ROOT/pages/cli-reference.adoc`), and — with `distribution` ≠ `none` —
`.goreleaser.yaml` (v2: archives `<bin>-<version>-<os>-<arch>`, `checksum.name_template: SHA256SUMS`,
`release.mode: keep-existing`, `homebrew_casks`/`scoops`/`winget` per channel reading
`HOMEBREW_TAP_GITHUB_TOKEN`/`SCOOP_BUCKET_GITHUB_TOKEN`/`WINGET_GITHUB_TOKEN`). Markers and shared placeholders are
explained in `shared-templates.md`. Go's `release.yml` does **not** append the shared release tail: GoReleaser builds,
archives, checksums, uploads and updates every channel from the scaffold's `.goreleaser.yaml`.

Go placeholders: `<module>` (`go.mod` module path), `<golangci-lint-version>` (e.g. `2.15.0`), `<govulncheck-version>`
(`golang.org/x/vuln` module version, e.g. `1.9.0`), `<cli-docs-command>` (default `go run ./tools/gendocs`;
`./internal/tools/gendocs` when that's where it lives).

**API docs:** Go has no static API-site generator in its toolchain (`go doc` prints text; `pkgsite` serves pages but
has no static export). The docs job therefore writes `api/go-doc.txt` (`go doc -all` for every package — what an
agent or a reviewer greps offline) and the Antora pages link to `https://pkg.go.dev/<module>` for a public module
(pkg.go.dev renders it from the module proxy once a tag is fetched). Doc-comment completeness is enforced in `lint` by
golangci-lint (`revive` `exported`/`package-comments`, `godot`) — the scaffold's `.golangci.yml` enables them.

**Coverage:** `-coverpkg=./...` so code exercised by another package's tests (testscript runs the whole Cobra tree
in-process) counts; the 80% bar excludes `cmd/` (a one-line `main`) and `tools/` (the docs generator) — the same scope
`iru-go-coverage` gates — and merges the duplicate blocks `-coverpkg` writes once per test binary. Branch coverage is
not available in Go.

## `.github/workflows/build.yml`

Jobs: `lint` (Linux), `test` (Linux), `cross-os` (macOS + Windows, gated by `cross-os-ci`, `needs: lint`),
`goreleaser` (Linux snapshot build of every target — only with `distribution` ≠ `none`), `sonar` (Linux, `sonar`
only), `docs` (Linux), `deploy-docs` (`pages: yes`).

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

# A newer push to the same ref cancels the run in flight: the macOS/Windows legs are expensive on private repositories.
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

jobs:
  lint:
    name: Lint — golangci-lint, go vet, govulncheck, go mod tidy
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod

      # .golangci.yml is a v2 config (linters + gofmt/goimports formatters); a v1 config fails here on purpose.
      - name: golangci-lint
        uses: golangci/golangci-lint-action@v9
        with:
          version: v<golangci-lint-version>

      - name: go vet
        run: go vet ./...

      - name: govulncheck
        run: go run golang.org/x/vuln/cmd/govulncheck@v<govulncheck-version> ./...

      - name: go.mod and go.sum are tidy
        run: go mod tidy -diff

  test:
    name: Tests, coverage, contract smoke test (Linux)
    runs-on: ubuntu-latest
    env:
      # -race needs cgo; gcc is on the Ubuntu image.
      CGO_ENABLED: 1
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod

      # Unit tests and the testscript end-to-end tests (in-process, via TestMain); the integration build tag stays off.
      - name: Tests with coverage
        run: |
          mkdir -p build/coverage
          go test -race -covermode=atomic -coverpkg=./... -coverprofile=build/coverage/cover.out ./...

      - name: Coverage bar (80% of statements, cmd/ and tools/ excluded)
        run: |
          python3 - <<'PY'
          import re, sys
          module = open("go.mod").read().split("module", 1)[1].split()[0]
          excluded = re.compile(rf"^{re.escape(module)}/(cmd/|tools/|internal/tools/)")
          blocks = {}
          with open("build/coverage/cover.out") as fh:
              next(fh)  # mode: atomic
              for line in fh:
                  location, statements, count = line.rsplit(" ", 2)
                  if excluded.match(location):
                      continue
                  previous = blocks.get(location, (int(statements), 0))
                  blocks[location] = (int(statements), max(previous[1], int(count)))
          total = sum(s for s, _ in blocks.values())
          covered = sum(s for s, c in blocks.values() if c > 0)
          percent = 100.0 * covered / total if total else 100.0
          print(f"Statement coverage: {percent:.2f}% of {total} statements (bar: 80%)")
          if percent < 80:
              sys.exit(f"::error::Statement coverage {percent:.2f}% is below the 80% bar")
          PY
      # >>> ui: tui

      # The terminal-UI tests leave their text captures (the catalog's UI contract) in .ui-screenshots/ — review
      # evidence, kept 7 days; nothing is uploaded when the run produced none.
      - name: Upload UI captures
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-screenshots
          path: .ui-screenshots/
          retention-days: 7
          if-no-files-found: ignore
      # <<< ui: tui
      # >>> sonar

      - name: Upload the coverage profile
        uses: actions/upload-artifact@v7
        with:
          name: coverage-profile
          path: build/coverage/cover.out
          retention-days: 7
      # <<< sonar
      # >>> container-tests

      # //go:build integration tests start containers (testcontainers-go); the hosted Ubuntu runner has Docker, and
      # each runner is a fresh VM, so the repository-wide .integration-test.lock used for local runs isn't needed.
      - name: Container-backed tests
        run: go test -race -tags integration ./...
      # <<< container-tests

      - name: Contract smoke test
        run: |
          go build -o "$RUNNER_TEMP/<bin>" ./cmd/<bin>
          version="$(sed -nE 's/.*[^[:alnum:]_]Version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$RUNNER_TEMP/<bin>" "$version"
      # >>> ci-tests: full

      # The release build flags (static, stripped) — what GoReleaser ships.
      - name: Contract smoke test (release build)
        env:
          CGO_ENABLED: 0
        run: |
          go build -trimpath -ldflags "-s -w" -o "$RUNNER_TEMP/<bin>-release" ./cmd/<bin>
          version="$(sed -nE 's/.*[^[:alnum:]_]Version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$RUNNER_TEMP/<bin>-release" "$version"
      # <<< ci-tests: full

  # macOS minutes cost ~10x and Windows ~1.7x Linux minutes on private repositories — see cross-os-ci.
  cross-os:
    name: Tests (${{ matrix.os }})
    needs: lint
    # >>> cross-os-ci: stable
    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< cross-os-ci: stable
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: macos-26
            race: -race
          # -race needs cgo, and the Windows image has no gcc on PATH by default: plain tests there.
          - os: windows-latest
            race: ""
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    defaults:
      run:
        shell: bash
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod

      # Paths, terminals, signals and line endings differ per OS — the testscript end-to-end tests are the point.
      - name: Tests
        env:
          RACE: ${{ matrix.race }}
        run: go test ${RACE:+"$RACE"} ./...
      # >>> ci-tests: full

      - name: Contract smoke test (release build)
        env:
          CGO_ENABLED: 0
        run: |
          exe="$RUNNER_TEMP/<bin>"
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          go build -trimpath -ldflags "-s -w" -o "$exe" ./cmd/<bin>
          version="$(sed -nE 's/.*[^[:alnum:]_]Version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$exe" "$version"
      # <<< ci-tests: full
  # >>> release

  # Every target GoReleaser ships, built on every change (CGO_ENABLED=0 cross-compiles all of them on Linux), so a
  # broken .goreleaser.yaml fails before the tag rather than on it.
  goreleaser:
    name: GoReleaser snapshot (every target)
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod

      - name: goreleaser check
        uses: goreleaser/goreleaser-action@v7
        with:
          distribution: goreleaser
          version: '~> v2'
          args: check

      - name: goreleaser release --snapshot
        uses: goreleaser/goreleaser-action@v7
        with:
          distribution: goreleaser
          version: '~> v2'
          args: release --snapshot --clean

      - name: Contract smoke test (linux/amd64 snapshot binary)
        run: bash .github/scripts/cli_smoke_test.sh "$(find dist -path '*linux_amd64*' -name <bin> -type f | head -n 1)"
  # <<< release
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

      - name: Download the coverage profile
        uses: actions/download-artifact@v8
        with:
          name: coverage-profile
          path: build/coverage

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
            -Dsonar.sources=.
            -Dsonar.exclusions=**/*_test.go,**/testdata/**,build/**,dist/**,docs/**
            -Dsonar.tests=.
            -Dsonar.test.inclusions=**/*_test.go,**/testdata/**
            -Dsonar.go.coverage.reportPaths=build/coverage/cover.out
  # <<< sonar

  docs:
    name: Docs — Antora, go doc, CLI reference
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod
      # >>> cli-docs

      # Man pages, completions and the Antora CLI reference come from the Cobra command tree; committed pages must
      # match it (irurueta docs: ai/cli-for-agents/documenting-clis.adoc — "regenerate and diff in CI").
      - name: Generate the CLI reference from the command tree
        run: |
          SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
          export SOURCE_DATE_EPOCH
          <cli-docs-command>

      - name: Fail when the committed CLI reference is stale
        run: |
          if ! git diff --exit-code -- docs/; then
            echo "::error::The generated CLI reference differs from the committed one — run '<cli-docs-command>' and commit the result."
            exit 1
          fi
      # <<< cli-docs

      # Plain-text API reference (go doc -all per package); public modules are also rendered on pkg.go.dev.
      - name: go doc
        run: |
          mkdir -p build/api
          for pkg in $(go list ./...); do
            echo "==== $pkg"
            go doc -all "$pkg" || true
            echo
          done > build/api/go-doc.txt

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

      - name: Merge the API docs into the Antora site
        run: |
          touch ./docs/build/site/.nojekyll
          mkdir -p ./docs/build/site/api
          cp -r ./build/api/. ./docs/build/site/api/

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

Written only when `distribution` ≠ `none`. Same triggers as every catalog release workflow: a **published GitHub
Release** (`release: [released]`) or `workflow_dispatch` with an existing tag (dry run: `goreleaser release
--snapshot`, which builds every archive and `SHA256SUMS` but publishes nothing). The tag must equal
`internal/version/version.go`'s literal (cut releases with `iru-release`/`iru-go-bump-version` first); GoReleaser
stamps the same value into the binary with `-ldflags -X`. Because the Release already exists, the scaffold's
`release.mode: keep-existing` makes GoReleaser keep its notes and only add the assets.

`distribution: internal` = archives + `SHA256SUMS` + attestations on the GitHub Release. `store` adds the channels the
scaffold's `.goreleaser.yaml` configures — the workflow only supplies their tokens:

| Channel | `.goreleaser.yaml` section | Environment variable it reads | Secret |
|---|---|---|---|
| `homebrew` | `homebrew_casks` → `<homebrew-tap>` | `HOMEBREW_TAP_GITHUB_TOKEN` | `HOMEBREW_TAP_TOKEN` |
| `scoop` | `scoops` → `<owner>/scoop-bucket` | `SCOOP_BUCKET_GITHUB_TOKEN` | `SCOOP_BUCKET_TOKEN` |
| `winget` | `winget` → fork `<owner>/winget-pkgs`, PR to `microsoft/winget-pkgs` | `WINGET_GITHUB_TOKEN` | `WINGET_TOKEN` |

```yaml
name: Release

on:
  release:
    types: [released]
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag to build (e.g. v1.2.0) — a dry run: GoReleaser --snapshot, nothing is published"
        required: true
        type: string

permissions:
  contents: read

env:
  RELEASE_TAG: ${{ github.event.release.tag_name || inputs.tag }}

jobs:
  goreleaser:
    name: GoReleaser
    runs-on: ubuntu-latest
    permissions:
      contents: write
      # >>> attest
      id-token: write
      attestations: write
      # <<< attest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}
          fetch-depth: 0

      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod

      - name: Check the release version
        run: |
          set -euo pipefail
          version="$(sed -nE 's/.*[^[:alnum:]_]Version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n 1)"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::internal/version/version.go Version '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::internal/version/version.go Version '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi

      - name: GoReleaser — release
        if: github.event_name == 'release'
        uses: goreleaser/goreleaser-action@v7
        with:
          distribution: goreleaser
          version: '~> v2'
          args: release --clean
        env:
          GITHUB_TOKEN: ${{ github.token }}
          # >>> channel: homebrew
          HOMEBREW_TAP_GITHUB_TOKEN: ${{ secrets.HOMEBREW_TAP_TOKEN }}
          # <<< channel: homebrew
          # >>> channel: scoop
          SCOOP_BUCKET_GITHUB_TOKEN: ${{ secrets.SCOOP_BUCKET_TOKEN }}
          # <<< channel: scoop
          # >>> channel: winget
          WINGET_GITHUB_TOKEN: ${{ secrets.WINGET_TOKEN }}
          # <<< channel: winget

      - name: GoReleaser — dry run (snapshot, nothing published)
        if: github.event_name != 'release'
        uses: goreleaser/goreleaser-action@v7
        with:
          distribution: goreleaser
          version: '~> v2'
          args: release --snapshot --clean

      - name: Contract smoke test (linux/amd64 binary)
        run: bash .github/scripts/cli_smoke_test.sh "$(find dist -path '*linux_amd64*' -name <bin> -type f | head -n 1)"
      # >>> attest

      # Build provenance for every archive listed in SHA256SUMS (verify: gh attestation verify <file> -R <owner>/<repo>).
      - name: Attest build provenance
        if: github.event_name == 'release'
        uses: actions/attest-build-provenance@v4
        with:
          subject-checksums: dist/SHA256SUMS
      # <<< attest

      - name: Keep the release files as a workflow artifact
        uses: actions/upload-artifact@v7
        with:
          name: release-files
          path: |
            dist/*.tar.gz
            dist/*.zip
            dist/SHA256SUMS
          retention-days: 30
```

GoReleaser cross-compiles every target with `CGO_ENABLED=0` on one Linux runner, so the Go release spends no macOS or
Windows minutes; its macOS binaries are unsigned (Homebrew installs and `curl` downloads aren't quarantined; a browser
download needs `xattr -d com.apple.quarantine`). The `--version` smoke check skips the expected-version comparison on
the snapshot build (its version is the snapshot template), but every other contract check runs.
