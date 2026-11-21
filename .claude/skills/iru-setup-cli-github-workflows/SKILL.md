---
name: iru-setup-cli-github-workflows
description: "Invoked as /iru-setup-cli-github-workflows with optional key: value args, usually by iru-setup-cli-repository. Creates or updates the build, release, sync and security GitHub Actions workflows plus Dependabot for a Rust, Go, Swift or .NET command-line tool, with a CLI-contract smoke test, an OS matrix and per-target releases."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(python3 *) Bash(ruby *) Bash(actionlint *) Bash(shellcheck *) Bash(bash *) Bash(jq *) Bash(cargo *) Bash(rustup *) Bash(go *) Bash(goreleaser *) Bash(golangci-lint *) Bash(swift *) Bash(xcrun *) Bash(dotnet *) Bash(brew style *) Bash(mkdir *) Bash(ls *) Bash(find *) Bash(grep *) Bash(sed -n *)
---

# Setup CLI GitHub Workflows

Scaffold (or update) the CI/CD pipeline of a command-line tool around two rules. **Everything that can run on Linux runs
on `ubuntu-latest`** — lint, tests, coverage, the contract smoke test, the docs, Sonar, CodeQL (except Swift's) — and
the macOS (~10× Linux minutes on a private repository) and Windows (~1.7×) legs run the same tests only as often as
`cross-os-ci` says, `needs:` the lint job so a lint failure never spends them. And **the binary is tested the way an
agent uses it**: every CI leg ends with a smoke test of the built executable against the catalog's CLI contract
(streams, exit codes, JSON, no colour without a terminal), and every release target is smoke-tested on its own runner
before it is packaged.

Stand-alone, this skill never asks `ci`: invoking it means CI is wanted. It is used by `iru-setup-cli-repository` and also runs on its own.

Files (templates in `reference/`; markers and placeholders are explained in `reference/shared-templates.md`):

| File | Written when | Template |
|---|---|---|
| `.github/workflows/build.yml` | always | `reference/<language>-workflows.md` |
| `.github/scripts/cli_smoke_test.sh` | always | `shared-templates.md` |
| `.github/workflows/release.yml` | `distribution` ≠ `none` | `<language>-workflows.md` + (Rust/Swift/.NET) the shared **release tail** |
| `.github/scripts/homebrew_formula.py` | `homebrew` in `channels` (Rust/Swift/.NET; Go's GoReleaser writes its cask) | `shared-templates.md` |
| `.github/scripts/lcov_to_sonar_generic.py` | Swift with `sonar` ≠ `none` | copied from `iru-setup-macos-github-workflows` |
| `.github/workflows/sync.yml` + `.github/scripts/sync_versions.py` | `branching: gitflow` | `shared-templates.md` |
| `.github/workflows/security.yml` | always (jobs gated by `security-*`) | `shared-templates.md` |
| `.github/dependabot.yml` | always | `shared-templates.md` |

`distribution` for a command-line tool (the same wording as `iru-setup-cli-repository` and `iru-setup-repository`):

- `none` — CI only, no `release.yml`.
- `internal` — `release.yml` builds native binaries per target, packages flat `<bin>-<version>-<target>.tar.gz` (Unix) /
  `.zip` (Windows) archives, a `SHA256SUMS` file and GitHub build-provenance attestations, and uploads them to the
  GitHub Release. Default when not open source.
- `store` — `internal` plus the package channels in `channels`. Defaults: Rust `crates-io,homebrew`; Go
  `homebrew,scoop,winget`; Swift `homebrew`; .NET `nuget,winget`. Also accepted: Rust `winget`, .NET `homebrew`.
  Default `store` when open source.

## Step 0 — Resolve inputs

Parse `args` (`key: value` lines) first, e.g. (what `iru-setup-cli-repository` passes):

```
language: rust
bin-name: logsift
branching: gitflow
integration-branch: develop
stable-branch: main
cross-os-ci: all
ci-tests: full
open-source: yes
visibility: public
pages: yes
distribution: store
channels: crates-io,homebrew
homebrew-tap: example-org/homebrew-tap
sonar: cloud
sonar-organization: example-org-github
sonar-project-key: example-org_logsift
sonar-host-url: https://sonarcloud.io
mode: new
```

| `args` key | Meaning | Default / resolution |
|---|---|---|
| `language` | `rust` / `go` / `swift` / `dotnet` | detected (Step 1); several candidates or none → ask |
| `bin-name` | the command name | detected: Rust `[[bin]] name` (else the package name), Go `cmd/<name>/`, Swift the `.executable` product, .NET `AssemblyName`/`ToolCommandName` |
| `branching` | `gitflow` / `main-only` | `gitflow` when an integration branch exists or the version ends in `-dev`, else `main-only`; confirm |
| `integration-branch` / `stable-branch` | branch names | `develop` / `main` (confirm against `git branch -a`) |
| `cross-os-ci` | `all` / `stable` — when the macOS/Windows test legs (and Swift's macOS CodeQL) run | see Standalone resolution |
| `ci-tests` | `unit` / `full` — `unit`: unit + end-to-end CLI tests on every leg and the debug-build smoke test on Linux; `full`: also the release-mode build + smoke test on every leg and any container-backed tests (Rust `#[ignore = "…"]`, Go `//go:build integration` — the scaffolds have none by default) | public `full`; private/internal ask, `unit` recommended |
| `pages` | `yes` / `no` | public `yes` without asking; private/internal ask, `no` recommended |
| `distribution` | `none` / `internal` / `store` | `store` when `open-source: yes`, else ask with `internal` recommended |
| `channels` | comma list (see above) | the language's defaults; only with `store`; an unknown channel for the language → say so and re-ask |
| `homebrew-tap` | `owner/repo` of the tap | `<owner>/homebrew-tap` (Go: the scaffold's `.goreleaser.yaml` decides — read it and report a mismatch) |
| `winget-package-id` | WinGet `PackageIdentifier` | `<Owner>.<BinName>` in PascalCase; must equal the first manual submission (Rust/.NET; Go reads it from `.goreleaser.yaml`) |
| `crates-auth` | `trusted` / `token` | `trusted` (crates.io trusted publishing; the crate's first version is published by hand) |
| `nuget-auth` / `nuget-user` | `trusted` / `api-key`; nuget.org account | `trusted`; `nuget-user` asked when `trusted` |
| `notarize` | `yes` / `no` (Swift) | `yes` when `distribution` ≠ `none` — the steps skip at run time without the Apple secrets |
| `attestations` | `yes` / `no` | `yes` for public; `no` for private/internal unless the account is on GitHub Enterprise Cloud (artifact attestations need it there) — say which |
| `sonar` (+ coordinates) | `cloud` / `self-hosted` / `none` | see Standalone resolution; the coordinates go on the scanner's command line (no file written) |
| `open-source` | `yes` / `no` | `gh repo view --json isPrivate` if unresolved |
| `visibility` | `public` / `private` / `internal` (`internal` behaves like `private`) | see Standalone resolution |
| `mode` | `new` / `existing` | informational only — every existing file is still asked about |
| `on-existing` | `ask` / `keep` / `update` / `replace` | `ask` |
| `security-dependency-review` / `security-osv` / `security-gitleaks` | gate one `security.yml` job each | `yes` |
| `security-codeql` | the CodeQL job | `yes`; **Swift on a private/internal repository: `no`** (macOS ~10×) unless the user opts in — say which |
| `ui` | `none` / `tui` | detected from disk when absent (`ui: tui` marker in `reference/shared-templates.md`): with a terminal UI the Linux test job uploads `.ui-screenshots/` (the UI tests' text captures) as a 7-day `ui-screenshots` artifact (`if: always()`, `if-no-files-found: ignore`) — no new job |
| `ci`, `description`, `license`, `developer-*`, `organization-url`, `owner`, identity keys | shared vocabulary | accepted; `description`/`license` feed the Homebrew formula (else read from the manifest / `LICENSE`), the rest is ignored and named in the report |

### Standalone resolution of `visibility`, `cross-os-ci`, `ci-tests`, `pages`, `sonar`, `distribution`, `channels`

In this order, skipping any `args` supplied (`AskUserQuestion`). Quote cost facts "per GitHub's current pricing"
(verified October 2026) and tell the user to double-check their plan: standard GitHub-hosted runners are **free for
public repositories**; private/internal ones consume the account's included minutes — **2,000/month on GitHub Free,
3,000 on Pro and Team, 50,000 on Enterprise Cloud** — then are billed per minute (**Linux 2-core $0.006, Windows $0.010
(~1.7×), macOS $0.062 (~10×)**). Arm64 Linux/Windows runners (`ubuntu-24.04-arm`, `windows-11-arm`, used by the
release) are free on public repositories; on private ones check availability and price for the plan. A cross-OS leg
takes ~5–15 minutes; a release builds 6 targets (Rust/.NET: 2 macOS jobs, 2 Windows jobs; Swift: 1 macOS job; Go: Linux
only).

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercase), else ask Public/Private (Public
   recommended when open source). Open source + private is contradictory — warn and ask.
2. **`cross-os-ci`** — **Every push and pull request** (`all`, recommended for public — a CLI's paths, terminals,
   signals and line endings differ per OS, and the legs are free there) / **Stable branch and manual runs only**
   (`stable`, recommended for private/internal — lint, tests, coverage, smoke test and docs still run on Linux on
   every push and PR). Say plainly that with `stable` an OS-specific break is only caught when it reaches the stable
   branch or someone runs the workflow by hand. Swift has no Windows leg.
3. **`ci-tests`** — **Unit + end-to-end** (`unit`) / **Full** (`full`) as in the table above.
4. **`pages`** — public: `yes`. Private/internal: **No (Recommended)** ("the Antora site, API docs and CLI reference are
   still built and uploaded as the `docs-site` artifact") / **Yes** ("requires Pro/Team/Enterprise Cloud; the site is
   PUBLIC unless Enterprise Cloud private Pages").
5. **`sonar`** — "Send CI analysis and coverage reports to SonarCloud / SonarQube?" `cloud` recommended only for public
   + open source; otherwise `none` recommended, `self-hosted` offered (SonarCloud is free only for public/open-source
   projects; Community Build is free but self-run). Rust analysis in SonarQube is newer than the other languages — check
   the plan includes it. Collect `sonar-organization` (cloud only) / `sonar-project-key` / `sonar-host-url` — suggest
   `<owner>-github` / `<owner>_<repo>` / `https://sonarcloud.io` from `git remote get-url origin`.
6. **`distribution`** then **`channels`** (multi-select, the language's defaults pre-selected) — as described above.

**Invalid combinations** (report and re-ask, never silently change): `homebrew`, `scoop` or `winget` on a private
repository (the package managers download the release assets anonymously — they can't read a private repository's
releases); `crates-io` or `nuget` on a closed-source tool (both publish publicly — confirm explicitly); `crates-io` when
`Cargo.toml` sets `publish = false` (the Rust scaffold writes that without a license — `cargo publish` refuses); `winget` for
Swift (no Windows binary); a channel without `distribution: store`.

## Step 1 — Survey the repository

- **Language** — the first that matches: `Cargo.toml` with a `[[bin]]` or `src/main.rs` and a `clap`/`ratatui`
  dependency → `rust`; `go.mod` + `cmd/<name>/main.go` (or a root `package main`) importing Cobra (or urfave/cli, kong,
  bubbletea) → `go`; `Package.swift` with an `.executableTarget` depending on `ArgumentParser` → `swift`; a `*.csproj`
  with `<OutputType>Exe</OutputType>`, no `UseWPF`/`UseWinUI`/`UseWindowsForms`, and `System.CommandLine`/
  `Spectre.Console.Cli`/`PackAsTool` → `dotnet`. A Windows desktop app belongs to `iru-setup-windows-github-workflows`, a
  macOS app to `iru-setup-macos-github-workflows` — say so and stop.
- **Scaffold contract** the templates assume (each language reference lists its own in full): Rust — root
  `[package]` + `[workspace]` with an `xtask` member, committed `Cargo.lock`, `rust-toolchain.toml`, `src/lib.rs`,
  `deny.toml`, `cargo xtask docs` writing `target/dist/man/`, `target/dist/completions/` and the CLI reference
  (`cargo xtask docs --check` is the staleness gate); Go —
  `internal/version/version.go`, `tools/gendocs`, `.golangci.yml` v2, `.goreleaser.yaml` (with `distribution` ≠
  `none`); Swift — `Sources/<Bin>/`, `Sources/<Bin>Kit/Version.swift`, `scripts/generate-docs.sh`, `swift-docc-plugin`;
  .NET — `<Tool>.slnx`, `global.json`, `Directory.Build.props` `<Version>`, `src/<Tool>`, `src/<Tool>.Core`,
  `tests/<Tool>.Core.Tests`, `tests/<Tool>.Cli.Tests`, `docfx.json`, `PackAsTool` + `ToolPackageRuntimeIdentifiers` for
  `nuget`. Resolve the markers from disk: `xtask`, `deny`, `docc`, `container-tests`, `ui: tui` (a Ratatui / Bubble Tea /
  Terminal.Gui dependency, unless `args` gave `ui`), and `<cli-docs-command>` (Rust
  `cargo xtask docs`; Go `go run ./tools/gendocs` (or `./internal/tools/gendocs`); Swift `scripts/generate-docs.sh
  build/gendocs`; .NET `dotnet run --project src/<Tool> -c Release -- gen-docs --output-dir .` (the scaffold's hidden
  command); none found → drop the `cli-docs` blocks and report the gap). If the layout differs (no lib/Core/Kit unit, a
  `.sln`, no xtask, version elsewhere, a `workspace.package` version), warn, ask whether to stop or continue, and when
  continuing read the real names and adapt every command — listing each adaptation in the report.
- **Go release config** — with `distribution` ≠ `none`, `.goreleaser.yaml` must exist (else stop and point at
  `iru-setup-go-cli` with that `distribution`); read its `checksum.name_template` (`SHA256SUMS` expected), archive
  `name_template`, `release.mode` (`keep-existing` expected — the workflow runs on an existing Release), and per channel
  the `{{ .Env.* }}` token names (`HOMEBREW_TAP_GITHUB_TOKEN`, `SCOOP_BUCKET_GITHUB_TOKEN`, `WINGET_GITHUB_TOKEN`
  expected); a different name → adapt the workflow's `env:` and report it. A channel in `channels` that the file
  doesn't configure → recommend re-running the scaffold with that `channels`.
- `docs/antora.yml`/`docs/antora-playbook.yml` — missing → recommend running `iru-setup-antora` first (the docs job
  fails without them).
- `docs/package-lock.json` — the docs job runs `npm ci` with the setup-node npm cache keyed on it, so it fails at
  `actions/setup-node` without a committed lockfile. `docs/` present without one (a site from the old per-package
  install) → recommend `/iru-update-antora` first; no `docs/` → `iru-setup-antora`, which commits it.
- `<repo>`/`<owner>` from `git remote get-url origin`; branch names (`git branch -a`).
- Existing files: every file in the table above; also sibling-named files (`ci.yml`, `rust.yml`, `go.yml`, `release.yaml`,
  `codeql.yml`, `goreleaser.yml`, `.github/workflows/*cargo-dist*`) that would duplicate this pipeline.

### Announce the mode, then warn and ask per existing file

- None exist: "**Creating the CI/CD pipeline from scratch**." Every file is *created*.
- Any exists: "**Updating an existing CI/CD pipeline** — found: <paths>." Unless `on-existing` is `keep`/`update`/
  `replace` (apply it to every file, still printing the warning), ask with `AskUserQuestion`, one question per file
  (`sync.yml` + `sync_versions.py` count as one, `release.yml` + `homebrew_formula.py` as one; batch up to 4), naming
  each concrete mismatch (e.g. "`build.yml` runs the tests only on `macos-latest` — on Linux they cost ~1/10", "it runs
  the Windows leg on every push but you chose `cross-os-ci: stable`", "`build.yml` deploys to Pages but you chose
  `pages: no`", "`release.yml` uses `cross` for every target where native runners exist", "an existing `codeql.yml`
  would run CodeQL twice", "a cargo-dist workflow already builds the release"):
  - **Update (Recommended)** — merge using the templates as reference; keep every step/job/entry this skill doesn't own
    (notifications, extra targets, other Dependabot ecosystems); you review a diff first. Customizations survive, but a
    customized part this skill owns may be rewritten.
  - **Keep as is** — untouched; it may disagree with this run's choices — the report lists each mismatch. Recommended
    only for a `.github/dependabot.yml` that already has the language's ecosystem and `github-actions`.
  - **Replace** — regenerate from the template; every customization is lost (git history only). Never the default.
  - **Stop** (first question only) — change nothing; report the existing files and end.

With `distribution: none`, an existing `release.yml` is offered for removal (ask; never delete silently).

## Step 2 — Look up versions at run time

Resolve every `uses:` major with `git ls-remote --tags https://github.com/<owner>/<repo>` (the REST `/tags` listing can
miss floating majors; `/releases/latest` returns `codeql-bundle-*` for CodeQL), tools from their registries (GitHub
releases, `https://proxy.golang.org/<module>/@latest`, the NuGet flat container), and the Swift toolchain from
`https://www.swift.org/api/v1/install/releases.json` + Docker Hub `library/swift` tags. Fall back to this table,
confirmed on 2026-10-16:

| Action / tool | Version | Note |
|---|---|---|
| `actions/checkout` / `actions/setup-go` / `actions/setup-dotnet` / `actions/setup-node` / `actions/setup-java` | `v7` / `v7` / `v6` / `v7` / `v6` | floating majors |
| `actions/cache` / `actions/upload-artifact` / `actions/download-artifact` | `v6` / `v7` / `v8` | floating |
| `actions/upload-pages-artifact` / `actions/deploy-pages` | `v5` / `v5` | floating |
| `actions/attest-build-provenance` | `v4` | floating (`subject-checksums` input) |
| `Swatinem/rust-cache` / `taiki-e/install-action` | `v2` / `v2` | floating |
| `golangci/golangci-lint-action` / `goreleaser/goreleaser-action` | `v9` / `v7` | floating; GoReleaser itself `'~> v2'` (2.18.3) |
| `rust-lang/crates-io-auth-action` | `v1` | **a branch, not a tag** (latest tag `v1.0.5`) — the crates.io docs use `@v1` |
| `NuGet/login` | `v1` | floating (`user` input → `NUGET_API_KEY` output) |
| `maxim-lobanov/setup-xcode` / `apple-actions/import-codesign-certs` | `v1` / `v7` | floating |
| `SonarSource/sonarqube-scan-action` | `v8` | floating |
| `github/codeql-action` (`init`/`analyze`) / `gitleaks/gitleaks-action` | `v4` / `v3` | floating |
| `actions/dependency-review-action` / `google/osv-scanner-action` reusable workflow | **`v5.0.0`** / **`v2.7.0`** | exact tags |
| cargo-llvm-cov / cargo-nextest / cargo-deny | `0.9.1` / `0.9.148` / `0.20.2` | GitHub releases |
| golangci-lint / govulncheck (`golang.org/x/vuln`) | `2.15.0` / `1.9.0` | |
| SwiftLint / `swift` image / Static Linux SDK | `0.65.1` / `6.4.1-noble` / `0.1.0` (checksum `cef5592d…a7c`, Swift 6.4.1) | the SDK version and checksum belong to that exact toolchain release |
| docfx / dotnet-sonarscanner | `2.81.0` / `12.0.0` | NuGet |
| wingetcreate | `https://aka.ms/wingetcreate/latest` at run time (1.12.13.0) | |

**Runners** (`actions/runner-images` README, 2026-10-16): `ubuntu-latest` (24.04), `ubuntu-24.04-arm`, `macos-26`
(arm64; `macos-26-intel`/`macos-15-intel` are the remaining Intel images — not used: x86_64 macOS binaries are
cross-compiled on arm64), `windows-latest` (2025 + VS 2026), `windows-11-arm` (Rust, Go, .NET, jq, bash preinstalled).
For Swift pick `<macos-runner>`/`<xcode-version>` like `iru-setup-macos-github-workflows`' Step 2: the
`swift-tools-version` must be supported by the image's Xcode — `macos-26` (Xcode 26.6 default) for the scaffold's
`6.0`; `xcode-27` (preview, Xcode 27.x) for 6.4-only features (actionlint 1.7.12 needs `xcode-27` declared in
`actionlint.yaml`).

## Step 3 — Render the templates

Read `reference/shared-templates.md` and `reference/<language>-workflows.md`, resolve the markers, substitute the
placeholders, and append the shared release tail to the Rust/Swift/.NET `release.yml` (Swift: its `publish` job
`needs: [ version, build-macos, build-linux ]`). Notes that matter when adapting them:

- **Smoke test** — `cli_smoke_test.sh <binary> [expected-version]` runs with stdin from `/dev/null` and both streams
  captured, so neither is a TTY. Its `greet`/`schema` checks follow the scaffold's sample commands; when a project
  removed `greet`, replace those two checks with an equivalent read-only command and keep the rest.
- **Coverage bars** — Rust: `cargo llvm-cov nextest --no-report` then `report --fail-under-lines 80` with
  `--ignore-filename-regex '(^|/)xtask/'` (end-to-end tests' child processes are instrumented); Go: an inline parser
  over `build/coverage/cover.out` merging `-coverpkg` duplicates and excluding `cmd/` and `tools/`; Swift: the
  `--show-codecov-path` JSON filtered to `Sources/<Bin>Kit/`; .NET: Cobertura `line-rate` of `<Tool>.Core`. The gates
  match `iru-<lang>-coverage`.
- **Docs** — Rust uses `cargo xtask docs --check`; the other languages run the generator with `SOURCE_DATE_EPOCH` =
  the last commit time, then `git diff --exit-code -- docs/` fails the job when a committed generated page is stale (a
  generated-but-untracked page passes). API docs land under
  the site's `api/`: rustdoc (with an `index.html` redirect to the crate), `go-doc.txt` (Go has no static API site —
  public modules link `https://pkg.go.dev/<module>`), DocC (`--hosting-base-path <repo>/api`), DocFX.
- **Release** — the `version` job checks the tag (minus `v`) equals the manifest version and that it has no `-dev`
  suffix. Rust targets build on native runners (musl via `musl-tools`; no `cross`/`cargo-zigbuild`: nothing here needs
  them), `x86_64-apple-darwin` and .NET `osx-x64` cross-compile on the arm64 macOS runner. Archives are flat (see
  `shared-templates.md` — WinGet's nested-installer path must not change per version). A dry run (`workflow_dispatch`)
  builds, smoke-tests and keeps everything as artifacts, renders the formula, runs `cargo publish --dry-run`, packs
  the NuGet packages — and uploads/pushes/attests nothing.
- **sync.yml** — `NEXT_DEV = x.(y+1).0-dev`, the minor bump of `iru-rust-bump-version`/`iru-go-bump-version`/
  `iru-swift-bump-version`/`iru-dotnet-bump-version`'s Step 5 that `iru-release` uses; Rust also rewrites the package's
  `Cargo.lock` entry so `--locked` builds keep working. The commit stages with `git add -u`. The branch is
  `sync_<released version>` (release `1.0.1` → `sync_1.0.1`). If the merge conflicts, the PR is opened from the release
  commit ("Sync 1.0.1 (merge conflicts to resolve)") without bumping; a conflicting sync never bumps, so it cannot lower
  the integration branch's development version.
- **security.yml** — CodeQL per language as tabled in `shared-templates.md`; OSV-Scanner reads the lock files (for
  .NET without `packages.lock.json`, NuGet's restore audit is the effective gate — say so).

## Step 4 — Write the files

Apply Step 1's per-file decision (create / Update after a diff / Keep as is / Replace). `sync.yml` and
`sync_versions.py` are written together (gitflow only); `release.yml` only when `distribution` ≠ `none`, with
`homebrew_formula.py` only for the `homebrew` channel (not Go); `lcov_to_sonar_generic.py` only for Swift with `sonar`;
`cli_smoke_test.sh`, `security.yml` and `.github/dependabot.yml` always. `chmod +x` the scripts. If an existing
`.github/dependabot.yml` has other ecosystems (`npm` for `docs/`, `docker`, …), keep them and only add/update the
language's ecosystem and `github-actions`.

## Step 5 — Verify

In a throwaway copy (or `$TMPDIR`), never in a way that commits anything:

1. `actionlint` with `shellcheck` on `PATH` over every written workflow (with an `actionlint.yaml` declaring
   `self-hosted-runner: labels: [xcode-27]` when Swift uses that preview label); if `actionlint` isn't installed, fall
   back to `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>` (PyYAML turns `on:` into `True` — a false flag).
2. `python3 -m py_compile .github/scripts/*.py`, `bash -n .github/scripts/cli_smoke_test.sh`.
3. With the toolchain installed, run through `iru-gate-runner` (they're long) the Linux job's commands — lint, tests +
   coverage bar, `cli_smoke_test.sh` on the debug binary — and the host target's release build + smoke test (Rust
   `cargo build --release --locked --target <host>`, Go `goreleaser check` + `goreleaser release --snapshot --clean`,
   Swift the universal build, .NET `dotnet publish -r <host-rid>`), plus `CLI_LANGUAGE=<language> RELEASE_VERSION=…
   NEXT_DEV=… python3 .github/scripts/sync_versions.py` on a copy (the branch name `sync_<release version>` and the conflict fallback are not runnable locally). A failing smoke check is a real contract break in
   the project — report it, don't weaken the script.

Everything that needs GitHub's runners, other operating systems, secrets, the notary service, a tap repository,
crates.io, nuget.org or winget-pkgs stays **unverified locally** — say so.

## Step 6 — Report

Summarize: the mode (from scratch / updating), and per file the action taken — **created / updated / replaced / kept
as is** (or Stop) — with each mismatch of a kept file, and every adaptation for a non-standard layout. State what was
wired and why: `language` and `bin-name` (and where they came from); the job/runner layout (Linux: lint, tests,
coverage, smoke test, docs, Sonar, CodeQL except Swift; macOS/Windows: the `cross-os` legs and the release targets —
restating the ~10×/~1.7× cost for private repositories); `cross-os-ci` and `ci-tests` (what `full` added);
`branching` (no `sync.yml` for `main-only` — say so plainly); `visibility` (and its source); `pages`; `sonar`;
`distribution`, the release targets and runners, `channels` (and that the first crates.io/NuGet version and the first
WinGet submission are manual), attestations; the security jobs included/omitted (Swift CodeQL on private); every open
gap (no CLI-docs generator found, no Antora site, a smoke check adapted). Then the required-secrets table — only the
rows that apply:

| Secret / variable | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | Sonar scan auth | `sonar` ≠ `none` |
| `HOMEBREW_TAP_TOKEN` | fine-grained PAT (or GitHub App token) with **Contents: read and write** on `<homebrew-tap>` — pushes the formula (Rust/Swift/.NET) or GoReleaser's cask (Go, as `HOMEBREW_TAP_GITHUB_TOKEN`) | `homebrew` channel |
| `SCOOP_BUCKET_TOKEN` | the same kind of token for `<owner>/scoop-bucket` (GoReleaser's `SCOOP_BUCKET_GITHUB_TOKEN`) | Go `scoop` channel |
| `WINGET_TOKEN` | classic PAT with `public_repo` (forks `microsoft/winget-pkgs` and opens the PR) — `wingetcreate --submit` (Rust/.NET) or GoReleaser's `WINGET_GITHUB_TOKEN` (Go, which pushes to the `<owner>/winget-pkgs` fork) | `winget` channel |
| `CARGO_REGISTRY_TOKEN` | crates.io API token scoped to `publish-update` for the crate | `crates-io` + `crates-auth: token` (none with trusted publishing — configure the trusted publisher on crates.io: repository, `release.yml`) |
| `NUGET_API_KEY` | nuget.org API key scoped to the package id(s), including the `<package-id>.<rid>` packages | `nuget` + `nuget-auth: api-key` (none with trusted publishing — add a policy on nuget.org for the repository and `release.yml`; `nuget-user` is the owning account) |
| `MACOS_CERTIFICATE_P12_BASE64` / `MACOS_CERTIFICATE_PASSWORD` | Developer ID Application certificate + key (`.p12`, base64) and its password | Swift with `notarize: yes` (optional — skipped without them) |
| `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_PRIVATE_KEY` | App Store Connect API key (raw `.p8` content) for `notarytool` (Developer role is enough) | Swift with `notarize: yes` (optional) |
| `GITHUB_TOKEN` | release uploads, GoReleaser's GitHub Release, `sync.yml`'s `gh` calls, attestations, CodeQL/gitleaks | automatic |

Repository settings to call out: Settings → Actions → General → "Workflow permissions" must allow GitHub Actions to
create pull requests (gitflow `sync.yml`); with `pages: yes`, Settings → Pages → Source = **GitHub Actions** (and a
Pro/Team/Enterprise Cloud plan for a private repository — the site is public unless Enterprise Cloud private Pages);
the tap/bucket repositories must exist (`<owner>/homebrew-tap`, `<owner>/scoop-bucket`) and, for Go's WinGet, the
`<owner>/winget-pkgs` fork; optionally scope the release secrets to a `release` environment with required reviewers.

Tell `iru-setup-readme` / the orchestrator: workflows are named **Build** (`build.yml`, the CI badge), **Release**,
**Sync**, **Security**; release assets are `<bin>-<version>-<target>.tar.gz|zip` + `SHA256SUMS` (Go:
`<bin>-<version>-<os>-<arch>`); install one-liners per channel (`brew install <tap-owner>/<tap-short>/<bin>` — Go:
`brew install --cask …`; `cargo install <crate> --locked`; `scoop bucket add … && scoop install <bin>`;
`winget install <winget-package-id>`; `dotnet tool install -g <package-id>`); verification: `sha256sum --check
SHA256SUMS --ignore-missing` and `gh attestation verify <file> -R <owner>/<repo>`.

## Verification notes

Verified on 2026-10-16 in `$TMPDIR/iru-verify/cli/workflows/` (outside this repository), with minimal contract-shaped
sample tools per language (the scaffolds were being written concurrently — Go and Swift scaffold facts were then read
from `iru-setup-go-cli`/`iru-setup-swift-cli` and the templates aligned):

- **actionlint 1.7.12 + shellcheck** (a planted SC2086 was reported) — exit 0 on eight rendered variants, two per
  language: (a) gitflow, `cross-os-ci: all`, `ci-tests: full` + container tests, Pages, Sonar cloud, `store` with every
  channel the language supports, trusted publishing, attestations, notarization, every security job; (b) main-only,
  `cross-os-ci: stable`, `unit`, `pages: no`, no Sonar, `internal`, token/API-key auth, no CodeQL (Swift: CodeQL on the
  stable schedule). Ruby YAML parse of every workflow and `dependabot.yml`; scripts `py_compile`/`bash -n` clean.
- **Smoke test** passed against real Rust (clap), Go, Swift (swift-argument-parser — it caught the library's default
  usage exit 64 until remapped to 2, as the scaffold does) and .NET binaries (JIT apphost, Native AOT `osx-arm64`, and
  the AOT tool installed from a local NuGet feed), and failed 11 checks on a deliberately non-compliant fake.
- **Rust** (1.99.1, cargo-llvm-cov 0.9.1, nextest 0.9.148, cargo-deny 0.20.2), against `iru-setup-rust-cli`'s own
  rendered project (bin `hello`, package `hello-tool`): the rendered `lint` (incl. cargo-deny), `test` (54 tests,
  97.3% lines, debug + release smoke), `docs` (`cargo xtask docs --check` failed on a hand-edited committed page)
  jobs run step by step; release `build` for both darwin targets (archives carry `target/dist` man pages and five
  completion scripts), `publish`, the formula (`brew style` clean); earlier, on a minimal sample: release `build` for `aarch64-apple-darwin` (smoke-tested) and `x86_64-apple-darwin`
  (cross-built on arm64), packaging (flat tar with `man/`, `completions/`), `publish`'s `SHA256SUMS`, the Homebrew
  formula render (`brew style`: no offenses); the version guard rejects `-dev` and a tag mismatch.
- **Go** (1.27.2, golangci-lint 2.15.0, GoReleaser 2.18.3, govulncheck 1.9.0): `lint` (golangci-lint correctly failed on
  the sample's unchecked `Fprintln`s), `test` (race, coverage parser, `-tags integration`, both smoke tests), `docs`
  (`go doc` dump), `goreleaser check` + `release --snapshot --clean` with a config shaped like the scaffold's (archive
  names, `SHA256SUMS`) and the smoke test on the snapshot binary; version guard (BSD/GNU-portable `sed`).
- **Swift** (6.4, Xcode 27.1): the `test` job on macOS (codecov JSON bar, lcov → generic XML, debug + release smoke),
  the universal `--arch arm64 --arch x86_64` build (lipo: both slices) + smoke, `generate-manual` output path.
- **.NET** (SDK 10.0.402): the `core` job (restore, warnings-as-errors build, `dotnet format`, coverage with the inline
  `Format=cobertura,opencover` collector, 80% bar, CLI tests, smoke); release `build` for `osx-arm64` and cross-compiled
  `osx-x64` Native AOT + packaging + RID-specific `dotnet pack -r`, then the `nuget` job's pointer package.
- **sync_versions.py** on all four manifests (`0.1.0-dev` → `0.2.0-dev`; Rust's `Cargo.lock` entry rewritten and
  `cargo metadata --locked` accepts it; a `.build/` copy of `Version.swift` untouched; README rows, `antora.yml`); a
  non-`-dev` `NEXT_DEV` exits 1.
- Looked up live: action tags (`git ls-remote`), runner labels and preinstalled tools (`actions/runner-images`), CodeQL
  Rust GA with `build-mode: none` (github/docs), `crates-io-auth-action` and `NuGet/login` inputs/outputs, the Static
  Linux SDK URL (HTTP 200, 285 MB) and checksum (swift.org API), Sonar's `sonar.rust.lcov.reportPaths` /
  `sonar.rust.clippyReport.reportPaths` / `sonar.go.coverage.reportPaths`.
- The `ui: tui` block (the `.ui-screenshots/` upload, added for the catalog's UI contract) was checked by rendering
  each language's `build.yml` with the markers dropped and parsing it with PyYAML 6.0.3 (the step keeps `if:
  always()`, `retention-days: 7`, `if-no-files-found: ignore`); actionlint wasn't installed for that pass, and the
  upload itself only runs on GitHub.
- **Unverified locally, never a blocker**: every job on GitHub's runners (Linux, `ubuntu-24.04-arm`, Windows,
  `windows-11-arm`, macOS images, the `swift` containers); the Static Linux SDK build (the local Docker VM's disk was
  full pulling `swift:6.4.1-noble`; nothing was pruned); musl and MSVC builds; Linux/Windows Native AOT; Developer ID
  signing and notarization; attestations; `gh release upload`; GoReleaser's real release and its tap/bucket/winget
  pushes; the Homebrew tap push and `brew install`; crates.io/NuGet trusted publishing and pushes; `wingetcreate
  update`; Sonar ingestion; CodeQL extraction; OSV-Scanner/gitleaks/dependency-review results; the Antora half of the
  docs job (identical to the sibling workflows skills). Every scaffold's generator command and output paths (Rust
  `cargo xtask docs [--check]` → `target/dist/`, .NET `gen-docs` → `artifacts/`, Go `tools/gendocs`, Swift
  `scripts/generate-docs.sh`) were read from the scaffold skills.

**Warn explicitly:**

- Review every generated workflow before relying on it; run `release.yml` by `workflow_dispatch` with an existing tag
  (a dry run) before the first real release.
- The first crates.io and NuGet versions, the first WinGet submission and the tap/bucket repositories are manual
  one-time steps; every secret above must exist before the release jobs pass. This skill creates none of them.
- On a private repository the cross-OS legs and the macOS/Windows release builds consume minutes at ~10×/~1.7× —
  revisit `cross-os-ci`/`ci-tests` if the budget runs out.

## Sources

- irurueta docs: ai/cli-for-agents/packaging-and-distribution.adoc — channels, one tag → every channel, snapshot builds
  on PRs, checksums + attestations (`gh attestation verify`), Homebrew formula tests with a read-only command, WinGet,
  `dotnet tool`, GoReleaser.
- irurueta docs: ai/cli-for-agents/testing-clis.adoc — exit-code tests, no-TTY/`NO_COLOR` checks, contract tests against
  the packaged artifact; ai/cli-for-agents/documenting-clis.adoc — generate the man page/reference/completions from the
  command tree, regenerate and diff in CI, reproducible output.
- irurueta docs: ai/cli-for-agents/ (streams-and-exit-codes, machine-readable-output, tty-colour-and-interactivity) —
  the contract the smoke test encodes; programming-languages/rust/ pages for Cargo conventions.
- Go: go.dev (coverage, `go mod tidy -diff`), golangci-lint v2, govulncheck, GoReleaser docs (`homebrew_casks`,
  `scoops`, `winget`, `release.mode`), pkg.go.dev.
- crates.io trusted publishing (`https://crates.io/docs/trusted-publishing`, `rust-lang/crates-io-auth-action`),
  NuGet trusted publishing (`NuGet/login`), .NET 10 RID-specific/Native AOT tools, Native AOT prerequisites; swift.org
  Static Linux SDK; Apple "Customizing the notarization workflow"; GitHub artifact attestations; `actions/runner-images`.
- Sibling skills: `iru-setup-windows-github-workflows` (shape, sync script, WinGet, Windows cost), `iru-setup-macos-
  github-workflows` (macOS cost gating, notarytool, CodeQL Swift, `lcov_to_sonar_generic.py`),
  `iru-setup-python-github-workflows`, `iru-setup-swift-github-workflows`.
