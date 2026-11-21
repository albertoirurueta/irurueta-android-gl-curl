# Rust workflow templates (`language: rust`)

`build.yml` and `release.yml` for a Rust command-line tool laid out like `iru-setup-rust-cli`'s scaffold: root
`Cargo.toml` with `[package]` + `[workspace] members = ["xtask"]`, committed `Cargo.lock`, `rust-toolchain.toml`
(rustfmt, clippy, llvm-tools-preview), `src/lib.rs` + thin `src/main.rs`, end-to-end tests in `tests/`, optional
`deny.toml`, and `cargo xtask docs` (man pages `target/dist/man/<bin>.1` + one per command, completions
`target/dist/completions/{<bin>.bash,_<bin>,<bin>.fish,_<bin>.ps1,<bin>.elv}`, and
`docs/modules/ROOT/pages/cli-reference.adoc` when `docs/modules/ROOT` exists; `cargo xtask docs --check` exits 1 when
the committed page is stale). The package name (`<crate-name>`) may differ from the binary name (bin `hello`, package
`hello-tool`); `xtask` is `0.0.0` with `publish = false`. Markers and
shared placeholders are explained in `shared-templates.md`; `release.yml` ends with that file's **release tail**.

Rust placeholders: `<crate-name>` (`[package] name`, usually `<bin>`), `<cargo-llvm-cov-version>`,
`<cargo-nextest-version>`, `<cargo-deny-version>`, `<cli-docs-command>` (default `cargo xtask docs`; its outputs land
in `target/dist/man/` and `target/dist/completions/` — adapt the two `Package` lines if a project's xtask writes them
elsewhere).

Every cargo call passes `--workspace --locked` where it builds or tests (the root has `[package]` **and**
`[workspace]`, so a bare `cargo test` would skip workspace members), and `xtask` is excluded from tests and coverage.

**Toolchain:** `rustup toolchain install` with no argument installs exactly what `rust-toolchain.toml` pins (rustup
1.28+ no longer auto-installs it on first use; the hosted images ship rustup 1.29). No third-party toolchain action.

## `.github/workflows/build.yml`

Jobs: `lint` (Linux), `test` (Linux — tests, coverage, smoke test), `cross-os` (macOS + Windows, gated by `cross-os-ci`,
`needs: lint`), `sonar` (Linux, `sonar` only), `docs` (Linux), `deploy-docs` (`pages: yes`).

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

env:
  CARGO_TERM_COLOR: always
  CARGO_INCREMENTAL: 0

jobs:
  lint:
    name: Lint — rustfmt, Clippy, rustdoc, cargo-deny
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      # rust-toolchain.toml pins the channel and the rustfmt/clippy/llvm-tools components.
      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Cache Cargo
        uses: Swatinem/rust-cache@v2

      - name: rustfmt
        run: cargo fmt --all --check

      - name: Clippy (warnings are errors)
        run: cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
      # >>> sonar

      - name: Clippy report for Sonar
        if: (!cancelled())
        run: cargo clippy --workspace --all-targets --all-features --locked --message-format=json > clippy-report.json || true

      - name: Upload the Clippy report
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: clippy-report
          path: clippy-report.json
          retention-days: 7
      # <<< sonar

      # missing_docs and broken intra-doc links fail here (the doc-comment gate).
      - name: rustdoc (warnings are errors)
        env:
          RUSTDOCFLAGS: -D warnings
        run: cargo doc --workspace --no-deps --locked
      # >>> deny

      - name: Install cargo-deny
        uses: taiki-e/install-action@v2
        with:
          tool: cargo-deny@<cargo-deny-version>

      - name: cargo-deny (advisories, licenses, bans, sources)
        run: cargo deny --locked check
      # <<< deny

  test:
    name: Tests, coverage, contract smoke test (Linux)
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Cache Cargo
        uses: Swatinem/rust-cache@v2

      - name: Install cargo-llvm-cov and cargo-nextest
        uses: taiki-e/install-action@v2
        with:
          tool: cargo-llvm-cov@<cargo-llvm-cov-version>,cargo-nextest@<cargo-nextest-version>

      # Unit tests and the end-to-end tests in tests/ that run the real binary (assert_cmd) — cargo-llvm-cov
      # instruments those child processes too, so src/main.rs is measured fairly. #[ignore] tests are skipped.
      - name: Tests with coverage
        run: cargo llvm-cov nextest --workspace --exclude xtask --locked --no-report

      # nextest can't run doctests.
      - name: Doctests
        run: cargo test --workspace --exclude xtask --doc --locked

      - name: Coverage report (80% line bar)
        run: |
          mkdir -p target/coverage
          cargo llvm-cov report --lcov --output-path target/coverage/lcov.info --ignore-filename-regex '(^|/)xtask/'
          cargo llvm-cov report --summary-only --ignore-filename-regex '(^|/)xtask/' --fail-under-lines 80
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

      - name: Upload the coverage report
        uses: actions/upload-artifact@v7
        with:
          name: coverage-lcov
          path: target/coverage/lcov.info
          retention-days: 7
      # <<< sonar
      # >>> container-tests

      # #[ignore = "…"] tests start containers (testcontainers); the hosted Ubuntu runner has Docker, and each runner
      # is a fresh VM, so the repository-wide .integration-test.lock used for local runs is not needed here.
      - name: Container-backed tests
        run: cargo nextest run --workspace --exclude xtask --locked --run-ignored only
      # <<< container-tests

      - name: Build the binary
        run: cargo build --locked --bin <bin>

      - name: Contract smoke test
        run: |
          version="$(cargo metadata --no-deps --format-version 1 --locked | jq -r --arg n '<crate-name>' '.packages[] | select(.name == $n) | .version')"
          bash .github/scripts/cli_smoke_test.sh target/debug/<bin> "$version"
      # >>> ci-tests: full

      - name: Contract smoke test (release build)
        run: |
          cargo build --release --locked --bin <bin>
          version="$(cargo metadata --no-deps --format-version 1 --locked | jq -r --arg n '<crate-name>' '.packages[] | select(.name == $n) | .version')"
          bash .github/scripts/cli_smoke_test.sh target/release/<bin> "$version"
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
        os: [ macos-26, windows-latest ]
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    defaults:
      run:
        shell: bash
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Cache Cargo
        uses: Swatinem/rust-cache@v2

      - name: Install cargo-nextest
        uses: taiki-e/install-action@v2
        with:
          tool: cargo-nextest@<cargo-nextest-version>

      # Paths, terminals, signals and line endings differ per OS — the end-to-end tests are the point of this leg.
      - name: Tests
        run: cargo nextest run --workspace --exclude xtask --locked

      - name: Doctests
        run: cargo test --workspace --exclude xtask --doc --locked
      # >>> ci-tests: full

      - name: Contract smoke test (release build)
        run: |
          cargo build --release --locked --bin <bin>
          exe=target/release/<bin>
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          version="$(cargo metadata --no-deps --format-version 1 --locked | jq -r --arg n '<crate-name>' '.packages[] | select(.name == $n) | .version')"
          bash .github/scripts/cli_smoke_test.sh "$exe" "$version"
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
          name: coverage-lcov
          path: target/coverage

      - name: Download the Clippy report
        uses: actions/download-artifact@v8
        with:
          name: clippy-report

      # Sonar's Rust analyzer imports LCOV coverage and Clippy JSON; check that your SonarQube Cloud plan / server
      # edition includes Rust.
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
            -Dsonar.sources=src
            -Dsonar.tests=tests
            -Dsonar.rust.lcov.reportPaths=target/coverage/lcov.info
            -Dsonar.rust.clippyReport.reportPaths=clippy-report.json
  # <<< sonar

  docs:
    name: Docs — Antora, rustdoc, CLI reference
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Cache Cargo
        uses: Swatinem/rust-cache@v2
      # >>> cli-docs

      # The man pages, completions and the Antora CLI reference are generated from the clap command tree; the
      # committed page must match it (irurueta docs: ai/cli-for-agents/documenting-clis.adoc — "regenerate and diff
      # in CI"). The xtask's --check exits 1 when it is stale.
      - name: Fail when the committed CLI reference is stale
        run: <cli-docs-command> --check
      # <<< cli-docs

      # rustdoc of the library crate under the site's api/ (the layout every catalog stack uses).
      - name: rustdoc
        env:
          RUSTDOCFLAGS: -D warnings
        run: |
          cargo doc --no-deps --locked -p <crate-name>
          lib="$(cargo metadata --no-deps --format-version 1 --locked | jq -r --arg n '<crate-name>' '.packages[] | select(.name == $n) | .targets[] | select(.kind | index("lib")) | .name' | head -n 1)"
          mkdir -p build/api
          cp -r target/doc/. build/api/
          printf '<!doctype html><meta http-equiv="refresh" content="0; url=%s/index.html">\n' "${lib//-/_}" > build/api/index.html

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

Without an `xtask` member (`xtask` marker false) drop every `--exclude xtask` (cargo only warns about an unknown
exclusion, but the flag is noise). Without a `src/lib.rs` drop the two `Doctests` steps (`cargo test --doc` fails with
"no library targets found") and the rustdoc step's `-p` lookup returns nothing — document the binary instead
(`cargo doc --no-deps --bins`).

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. Triggered by a **published GitHub Release** (`release: [released]`, not a
pre-release) or `workflow_dispatch` with an existing tag (dry run: everything builds, nothing is uploaded or
published). The tag must equal `Cargo.toml`'s version (`v1.4.0` ↔ `1.4.0`) — a `-dev` version fails the `version` job,
so cut releases with `iru-release`/`iru-rust-bump-version` first.

**Targets — native runners, no `cross`/`cargo-zigbuild`.** Every target builds on a GitHub-hosted runner of its own OS
and, except one, its own architecture:

| Target | Runner | Why |
|---|---|---|
| `x86_64-unknown-linux-musl` | `ubuntu-latest` | `rustup target add` + `musl-tools` (for `cc`-built C code in dependencies); a static binary that runs on any Linux |
| `aarch64-unknown-linux-musl` | `ubuntu-24.04-arm` | native Arm64 runner — no cross linker or QEMU needed |
| `aarch64-apple-darwin` | `macos-26` | native |
| `x86_64-apple-darwin` | `macos-26` (cross-compiled, not executed) | the Xcode SDK links both macOS architectures; the Intel images (`macos-15-intel`, `macos-26-intel`) are being phased out, so no Intel runner is needed |
| `x86_64-pc-windows-msvc` | `windows-latest` | native MSVC |
| `aarch64-pc-windows-msvc` | `windows-11-arm` | native Arm64 runner (Rust preinstalled on the image), so the smoke test runs the real binary |

`cross` (Docker + QEMU) or `cargo-zigbuild` would only be needed to build every Linux target from one x86 runner, or
to link against an older glibc; musl targets on native runners need neither. (Verified on 2026-10-16: both darwin
targets build from an Apple-silicon host with `rustup target add`; the Linux and Windows legs are not verified
locally.) Arm64 Linux/Windows standard runners are free on public repositories; on private repositories check that
your plan offers them and what they cost.

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
  CARGO_TERM_COLOR: always

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

      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Read and check the release version
        id: version
        run: |
          set -euo pipefail
          version="$(cargo metadata --no-deps --format-version 1 --locked | jq -r --arg n '<crate-name>' '.packages[] | select(.name == $n) | .version')"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Cargo.toml version '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::Cargo.toml version '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_OUTPUT"

  build:
    name: Build (${{ matrix.target }})
    needs: version
    strategy:
      fail-fast: true
      matrix:
        include:
          - target: x86_64-unknown-linux-musl
            os: ubuntu-latest
            smoke: true
          - target: aarch64-unknown-linux-musl
            os: ubuntu-24.04-arm
            smoke: true
          - target: aarch64-apple-darwin
            os: macos-26
            smoke: true
          # Cross-compiled on the Apple-silicon runner; it can't run there, so no smoke test.
          - target: x86_64-apple-darwin
            os: macos-26
            smoke: false
          - target: x86_64-pc-windows-msvc
            os: windows-latest
            smoke: true
          - target: aarch64-pc-windows-msvc
            os: windows-11-arm
            smoke: true
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    defaults:
      run:
        shell: bash
    env:
      VERSION: ${{ needs.version.outputs.version }}
      TARGET: ${{ matrix.target }}
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Install the pinned Rust toolchain and the target
        run: |
          rustup toolchain install
          rustup target add "$TARGET"

      - name: Install musl tools
        if: contains(matrix.target, 'linux-musl')
        run: |
          sudo apt-get update -qq
          sudo apt-get install -y -qq --no-install-recommends musl-tools

      - name: Build
        run: cargo build --release --locked --target "$TARGET" --bin <bin>

      - name: Contract smoke test
        if: matrix.smoke
        run: |
          exe="target/$TARGET/release/<bin>"
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          bash .github/scripts/cli_smoke_test.sh "$exe" "$VERSION"
      # >>> cli-docs

      - name: Generate the man page and completions
        run: |
          SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
          export SOURCE_DATE_EPOCH
          <cli-docs-command>
      # <<< cli-docs

      # Flat archives: <bin>[.exe], README.md, CHANGELOG.md, LICENSE*, man/, completions/.
      - name: Package
        run: |
          set -euo pipefail
          name="<bin>-$VERSION-$TARGET"
          stage="$RUNNER_TEMP/$name"
          mkdir -p "$stage" dist
          exe="<bin>"
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          cp "target/$TARGET/release/$exe" "$stage/"
          for file in README.md CHANGELOG.md LICENSE*; do
            if [[ -f "$file" ]]; then cp "$file" "$stage/"; fi
          done
          if [[ -d target/dist/man ]]; then cp -r target/dist/man "$stage/man"; fi
          if [[ -d target/dist/completions ]]; then cp -r target/dist/completions "$stage/completions"; fi
          if [[ "$RUNNER_OS" == Windows ]]; then
            pwsh -NoProfile -Command "Compress-Archive -Path '$stage/*' -DestinationPath 'dist/$name.zip'"
          else
            tar -C "$stage" -czf "dist/$name.tar.gz" .
          fi
          ls -l dist

      - name: Upload the archive
        uses: actions/upload-artifact@v7
        with:
          name: dist-${{ matrix.target }}
          path: dist/
          retention-days: 7
  # >>> channel: crates-io

  # cargo install <crate-name> --locked builds from source; the prebuilt archives stay the fast path.
  crates-io:
    name: Publish to crates.io
    needs: [ version, publish ]
    runs-on: ubuntu-latest
    # >>> crates-auth: trusted
    permissions:
      contents: read
      id-token: write
    # <<< crates-auth: trusted
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Install the pinned Rust toolchain
        run: rustup toolchain install

      - name: Package check (dry run)
        if: github.event_name != 'release'
        run: cargo publish --locked --dry-run -p <crate-name>
      # >>> crates-auth: trusted

      # crates.io trusted publishing: a short-lived token from GitHub's OIDC identity, revoked when the job ends.
      # The crate's first version is published by hand; then add this repository + release.yml as a trusted
      # publisher in the crate's settings on crates.io.
      - name: Authenticate with crates.io
        if: github.event_name == 'release'
        id: crates-io-auth
        uses: rust-lang/crates-io-auth-action@v1

      - name: Publish
        if: github.event_name == 'release'
        env:
          CARGO_REGISTRY_TOKEN: ${{ steps.crates-io-auth.outputs.token }}
        run: cargo publish --locked -p <crate-name>
      # <<< crates-auth: trusted
      # >>> crates-auth: token

      - name: Publish
        if: github.event_name == 'release'
        env:
          CARGO_REGISTRY_TOKEN: ${{ secrets.CARGO_REGISTRY_TOKEN }}
        run: cargo publish --locked -p <crate-name>
      # <<< crates-auth: token
  # <<< channel: crates-io
```

Then append the **release tail** from `shared-templates.md` (`publish`, and `homebrew` / `winget` per `channels`) with
`<win-x64-target>` = `x86_64-pc-windows-msvc` and `<win-arm64-target>` = `aarch64-pc-windows-msvc`. Without the
`aarch64-pc-windows-msvc` leg (a private repository that doesn't get `windows-11-arm`), drop it from the matrix and its
WinGet URL.
