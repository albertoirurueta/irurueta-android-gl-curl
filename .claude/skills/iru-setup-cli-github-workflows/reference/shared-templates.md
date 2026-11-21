# Shared templates — every language

The files every CLI pipeline shares, whatever `language` is: the conditional-marker rules and placeholders all four
language references use, the contract smoke-test script, the release "tail" jobs (checksums, attestations, upload,
Homebrew, WinGet — appended to the Rust, Swift and .NET `release.yml`; Go's GoReleaser does all of that itself),
`sync.yml` + `sync_versions.py`, `security.yml`, `.github/dependabot.yml`, and the Homebrew formula renderer.

## Conditional markers

A line `# >>> <condition>` opens a block that is written only when the condition holds, and the matching
`# <<< <condition>` line closes it; marker lines are never written, blocks nest, and a comma-separated condition must
hold entirely. Markers also appear inside `run: |` scripts and folded `args: >` scalars (same rule — the marker line is
dropped before the YAML is parsed, so it never ends a continuation).

| Condition | Holds when |
|---|---|
| `gitflow` / `main-only` | `branching` |
| `lang: rust` / `lang: go` / `lang: swift` / `lang: dotnet` | `language` (only the shared templates below need it) |
| `cross-os-ci: stable` | `cross-os-ci: stable` — the macOS/Windows legs (and Swift's macOS CodeQL) run only on stable-branch pushes, `workflow_dispatch` (and CodeQL's weekly schedule) |
| `ci-tests: full` | `ci-tests: full` — adds the release-mode build + contract smoke test on every CI leg |
| `container-tests` | `ci-tests: full` **and** the repository has container-backed tests (Rust: an `#[ignore = "…"]` test under `tests/` or `src/`; Go: a `//go:build integration` file). The Swift and .NET scaffolds have none — never written for them |
| `ui: tui` | the tool has a terminal UI — `ui: tui` from `args`, else from disk: Rust `ratatui` in a `Cargo.toml`, Go `charm.land/bubbletea/v2` (or `github.com/charmbracelet/bubbletea`) in `go.mod`, .NET a `Terminal.Gui` `PackageReference`. Never for Swift (no TUI library offered). Adds the `.ui-screenshots/` upload after the Linux test job's tests (the TUI tests are ordinary unit tests there — no new job, runner or minutes) |
| `pages: yes` / `pages: no` | `pages` |
| `sonar` / `sonar: cloud` | `sonar` ≠ `none` / `sonar: cloud` (`self-hosted` has no organization) |
| `cli-docs` | Step 1 found the CLI-reference generator (`<cli-docs-command>`) |
| `deny` | Rust: `deny.toml` exists |
| `xtask` | Rust: the workspace has an `xtask` member |
| `docc` | Swift: `Package.swift` depends on `swift-docc-plugin` |
| `release` | `distribution` ≠ `none` (`release.yml` is written only then) |
| `channel: <name>` | `<name>` is in `channels` (only with `distribution: store`) |
| `crates-auth: trusted` / `crates-auth: token` | Rust `crates-io` channel: trusted publishing (`rust-lang/crates-io-auth-action`) / a `CARGO_REGISTRY_TOKEN` secret |
| `nuget-auth: trusted` / `nuget-auth: api-key` | .NET `nuget` channel: trusted publishing (`NuGet/login`) / a `NUGET_API_KEY` secret |
| `attest` | `attestations: yes` (default for public repositories — GitHub artifact attestations on private/internal repositories need GitHub Enterprise Cloud) |
| `notarize` | Swift: `notarize: yes` (the steps still skip themselves at run time when the Apple secrets are absent) |
| `security-dependency-review` / `security-codeql` / `security-osv` / `security-gitleaks` | each enabled `security-*` key |

**Placeholders** (all languages): `<bin>` (the command name — binary, archive prefix, formula), `<integration-branch>`,
`<stable-branch>`, `<homebrew-tap>` (`owner/repo`), `<description>`, `<homepage>` (the Pages URL
`https://<owner>.github.io/<repo>/` with `pages: yes`, else `https://github.com/<owner>/<repo>`), `<license>` (SPDX id),
`<winget-package-id>`, `<win-x64-target>` / `<win-arm64-target>` (the Windows archive targets: Rust
`x86_64-pc-windows-msvc`/`aarch64-pc-windows-msvc`, .NET `win-x64`/`win-arm64`), `<cli-docs-command>`,
`<sonar-project-key>`, `<sonar-organization>`, `<sonar-host-url>`, and the tool versions of Step 2. Language-specific
placeholders are listed in each language reference. YAML strings that carry user text (`<description>`) are emitted
inside double-quoted YAML scalars — escape `"` and `\`.

**Release archives** (Rust, Swift, .NET — GoReleaser follows the same naming through the scaffold's
`.goreleaser.yaml`): `<bin>-<version>-<target>.tar.gz` (macOS/Linux) and `<bin>-<version>-<target>.zip` (Windows),
**flat** — the binary at the archive root plus `README.md`, `CHANGELOG.md`, `LICENSE*`, and the generated `man/` and
`completions/` directories when the generator produced them. Flat on purpose: a WinGet portable-in-zip manifest records
the nested binary's path, and a versioned top-level directory would change that path every release (`wingetcreate
update` copies it from the previous manifest). `SHA256SUMS` (`sha256sum` format) covers every archive.

## `.github/scripts/cli_smoke_test.sh`

Always written. Run by `build.yml` on the Linux binary (and, with `ci-tests: full`, the release-mode binary of every
leg) and by the Rust/Swift/.NET `release.yml` on every natively runnable target before it is packaged. It encodes the
scaffold contract's sample commands (`greet`, `schema`); the report tells the user to adapt those two checks when the
sample command is removed, and to keep the rest. `jq` is preinstalled on every GitHub-hosted image (Ubuntu, macOS,
Windows incl. Arm64); the Swift jobs that run in a `swift` container install it.

```bash
#!/usr/bin/env bash
# Contract smoke test for a built command-line binary (cli-contract.md §1–§4, §7).
#
# Usage: .github/scripts/cli_smoke_test.sh <path-to-binary> [expected-version]
#
# Runs the binary the way an agent or a script does — stdin from /dev/null, stdout and stderr captured to files (so
# neither is a TTY) — and fails on the first broken promise: --version/--help exit 0 on stdout, an unknown command
# exits 2 with nothing on stdout, `greet --json` prints one JSON document with schemaVersion 1, a validation failure
# exits 3 with the JSON error envelope on stderr, `schema` prints JSON, and no output carries an ESC byte when
# NO_COLOR is set or the streams are not terminals. Adjust the sample-command checks when the scaffold's `greet`
# command is replaced; keep the rest.
set -uo pipefail

bin="${1:?usage: cli_smoke_test.sh <path-to-binary> [expected-version]}"
expected_version="${2:-}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  echo "::error::smoke test: $*"
  failures=$((failures + 1))
}

# run <name> <expected-exit> [env assignments...] -- <args...>
run() {
  local name="$1" expected="$2" status
  shift 2
  local envs=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do envs+=("$1"); shift; done
  shift
  env ${envs[@]+"${envs[@]}"} "$bin" "$@" </dev/null >"$work/$name.out" 2>"$work/$name.err"
  status=$?
  if [[ "$status" -ne "$expected" ]]; then
    fail "'$*' exited $status, expected $expected (stderr: $(head -c 300 "$work/$name.err"))"
  fi
}

no_escape_bytes() {
  local file
  for file in "$@"; do
    if LC_ALL=C grep -q $'\x1b' "$file"; then
      fail "$(basename "$file") contains ESC bytes although the stream is not a TTY / NO_COLOR is set"
    fi
  done
}

run version 0 -- --version
[[ -s "$work/version.out" ]] || fail "--version printed nothing on stdout"
if [[ -n "$expected_version" ]] && ! grep -qF -- "$expected_version" "$work/version.out"; then
  fail "--version printed '$(head -n1 "$work/version.out")', expected it to contain $expected_version"
fi

run help 0 -- --help
[[ -s "$work/help.out" ]] || fail "--help printed nothing on stdout"

run unknown 2 -- definitely-not-a-command
[[ -s "$work/unknown.out" ]] && fail "a usage error wrote to stdout (it belongs on stderr)"
[[ -s "$work/unknown.err" ]] || fail "a usage error printed no message on stderr"

run greet_json 0 -- greet Smoke --json
jq -e '.schemaVersion == 1' "$work/greet_json.out" >/dev/null || fail "'greet Smoke --json' is not one JSON document with schemaVersion 1"
[[ "$(jq -s 'length' "$work/greet_json.out" 2>/dev/null)" == "1" ]] || fail "'greet Smoke --json' printed more than one JSON document"

run greet_invalid 3 -- greet "" --json
[[ -s "$work/greet_invalid.out" ]] && fail "a validation error wrote to stdout"
jq -e '.error.exitCode == 3 and (.error.code | type == "string") and (.error.hint | length > 0)' \
  "$work/greet_invalid.err" >/dev/null || fail "the validation error is not the JSON error envelope on stderr"

run schema 0 -- schema
jq -e 'type == "object"' "$work/schema.out" >/dev/null || fail "'schema' did not print a JSON object"

run greet_text 0 NO_COLOR=1 -- greet Smoke
run help_nocolor 0 NO_COLOR=1 -- --help
run greet_piped 0 -- greet Smoke
no_escape_bytes "$work"/*.out "$work"/*.err

if [[ "$failures" -gt 0 ]]; then
  echo "smoke test: $failures check(s) failed for $bin"
  exit 1
fi
echo "smoke test: every contract check passed for $bin"
```

## Release tail — appended to `release.yml` (Rust, Swift, .NET)

Appended, unchanged apart from markers and placeholders, after the language's `version` and `build` jobs (which upload
one `dist-<target>` artifact per target). Indentation is already at the `jobs:` level. Nothing leaves the workflow on a
`workflow_dispatch` dry run: the archives, `SHA256SUMS` and the rendered Homebrew formula are kept as workflow
artifacts, and the attestation, upload, tap push, crates.io/NuGet push and WinGet submission run only for a `release`
event.

```yaml
  publish:
    name: Checksums, attestations, GitHub Release
    needs: [ version, build ]
    runs-on: ubuntu-latest
    permissions:
      contents: write
      # >>> attest
      id-token: write
      attestations: write
      # <<< attest
    steps:
      - name: Download the archives
        uses: actions/download-artifact@v8
        with:
          pattern: dist-*
          path: dist
          merge-multiple: true

      - name: Write SHA256SUMS
        working-directory: dist
        run: |
          shopt -s nullglob
          files=( *.tar.gz *.zip )
          sha256sum -- "${files[@]}" > SHA256SUMS
          cat SHA256SUMS
      # >>> attest

      # Build provenance for every archive (verify with: gh attestation verify <file> -R <owner>/<repo>).
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
          path: dist/
          retention-days: 30

      - name: Upload to the GitHub Release
        if: github.event_name == 'release'
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release upload "$RELEASE_TAG" dist/* --clobber --repo "$GITHUB_REPOSITORY"
  # >>> channel: homebrew

  # Renders Formula/<bin>.rb from SHA256SUMS and pushes it to <homebrew-tap>; a dry run only renders it.
  homebrew:
    name: Update the Homebrew tap
    needs: [ version, publish ]
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Download the release files
        uses: actions/download-artifact@v8
        with:
          name: release-files
          path: dist

      - name: Check out the tap
        if: github.event_name == 'release'
        uses: actions/checkout@v7
        with:
          repository: <homebrew-tap>
          token: ${{ secrets.HOMEBREW_TAP_TOKEN }}
          path: tap

      - name: Render the formula
        env:
          VERSION: ${{ needs.version.outputs.version }}
          FORMULA_DESCRIPTION: "<description>"
        run: |
          python3 .github/scripts/homebrew_formula.py --name <bin> --version "$VERSION" --tag "$RELEASE_TAG" \
            --sums dist/SHA256SUMS --repository "$GITHUB_REPOSITORY" --description "$FORMULA_DESCRIPTION" \
            --homepage "<homepage>" --license "<license>" --output tap/Formula/<bin>.rb
          cat tap/Formula/<bin>.rb

      - name: Push the formula
        if: github.event_name == 'release'
        working-directory: tap
        env:
          VERSION: ${{ needs.version.outputs.version }}
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add Formula/<bin>.rb
          git diff --cached --quiet || git commit -m "<bin> $VERSION"
          git push
  # <<< channel: homebrew
  # >>> channel: winget

  # The first submission to microsoft/winget-pkgs is manual (wingetcreate new, installer type "zip" with a nested
  # "portable" <bin>.exe); this job only submits updates, which copy that manifest's shape.
  winget:
    name: Submit the update to WinGet
    needs: [ version, publish ]
    if: github.event_name == 'release'
    runs-on: windows-latest
    defaults:
      run:
        shell: pwsh
    steps:
      - name: Update the winget-pkgs manifest with wingetcreate
        env:
          WINGET_TOKEN: ${{ secrets.WINGET_TOKEN }}
          VERSION: ${{ needs.version.outputs.version }}
        run: |
          Invoke-WebRequest -Uri https://aka.ms/wingetcreate/latest -OutFile wingetcreate.exe
          $base = "https://github.com/$env:GITHUB_REPOSITORY/releases/download/$env:RELEASE_TAG"
          $urls = @("$base/<bin>-$env:VERSION-<win-x64-target>.zip|x64", "$base/<bin>-$env:VERSION-<win-arm64-target>.zip|arm64")
          ./wingetcreate.exe update <winget-package-id> --version $env:VERSION --urls $urls --submit --token $env:WINGET_TOKEN
          if ($LASTEXITCODE -ne 0) { throw 'wingetcreate update failed.' }
  # <<< channel: winget
```

## `.github/scripts/homebrew_formula.py`

Written with `release.yml` when `channel: homebrew` holds (Rust, Swift, .NET; Go's GoReleaser writes its own cask).
A formula (not a cask) because it covers macOS **and** Linux from one file; the test block runs `--version` and the
contract's read-only `schema` command, which never prompts or touches the network (irurueta docs:
ai/cli-for-agents/packaging-and-distribution.adoc — "tests that need no user input").

```python
#!/usr/bin/env python3
"""Writes the Homebrew formula of a command-line tool from a release's SHA256SUMS, for the Release workflow.

Usage: homebrew_formula.py --name <bin> --version <x.y.z> --tag <release tag> --sums <SHA256SUMS>
                           --repository <owner/repo> --description <text> --homepage <url> --license <SPDX> --output <Formula/<bin>.rb>

Every `<bin>-<version>-<target>.tar.gz` line in SHA256SUMS becomes a url/sha256 pair: the target names the OS
(`apple-darwin`/`osx` → macOS, `linux` → Linux) and the CPU (`aarch64`/`arm64` → on_arm, `x86_64`/`x64` → on_intel,
`universal` → both). Windows archives are ignored. The archives are flat (the binary at the root, plus optional
`man/` and `completions/` directories), so the formula installs them without a version-dependent path.
"""
import argparse
import re
import sys
from pathlib import Path


def class_name(name):
    # Homebrew's Formulary.class_s: "my-tool" -> "MyTool", "tool2" -> "Tool2".
    return "".join(part[:1].upper() + part[1:] for part in re.split(r"[-_.]", name) if part)


def classify(target):
    os_name = "macos" if ("apple-darwin" in target or target.startswith("osx")) else (
        "linux" if "linux" in target else None)
    if "universal" in target:
        cpus = ["arm", "intel"]
    elif "aarch64" in target or "arm64" in target:
        cpus = ["arm"]
    elif "x86_64" in target or "x64" in target:
        cpus = ["intel"]
    else:
        cpus = []
    return os_name, cpus


def main():
    parser = argparse.ArgumentParser()
    for option in ("--name", "--version", "--tag", "--sums", "--repository", "--description", "--homepage", "--license",
                   "--output"):
        parser.add_argument(option, required=True)
    args = parser.parse_args()

    pattern = re.compile(rf"^([0-9a-f]{{64}})\s+\*?({re.escape(args.name)}-{re.escape(args.version)}-(.+)\.tar\.gz)$")
    archives = {}
    for line in Path(args.sums).read_text().splitlines():
        match = pattern.match(line.strip())
        if not match:
            continue
        digest, filename, target = match.groups()
        os_name, cpus = classify(target)
        for cpu in cpus:
            if os_name:
                archives[(os_name, cpu)] = (filename, digest)
    if not archives:
        sys.exit(f"No {args.name}-{args.version}-<target>.tar.gz entries in {args.sums}")

    base = f"https://github.com/{args.repository}/releases/download/{args.tag}"
    lines = [
        f"class {class_name(args.name)} < Formula",
        f"  desc {json_str(args.description)}",
        f"  homepage {json_str(args.homepage)}",
        f"  version {json_str(args.version)}",
        f"  license {json_str(args.license)}",
        "",
    ]
    for os_name in ("macos", "linux"):
        if not any(key[0] == os_name for key in archives):
            continue
        lines.append(f"  on_{os_name} do")
        for cpu in ("arm", "intel"):
            if (os_name, cpu) not in archives:
                continue
            filename, digest = archives[(os_name, cpu)]
            lines += [
                f"    on_{cpu} do",
                f"      url {json_str(f'{base}/{filename}')}",
                f"      sha256 {json_str(digest)}",
                "    end",
            ]
        lines += ["  end", ""]
    lines += [
        "  def install",
        f"    bin.install {json_str(args.name)}",
        '    man1.install Dir["man/*.1"]',
        '    bash_completion.install Dir["completions/*.bash"]',
        '    zsh_completion.install Dir["completions/_*"].reject { |f| f.end_with?(".ps1") }',
        '    fish_completion.install Dir["completions/*.fish"]',
        "  end",
        "",
        "  test do",
        f'    assert_match version.to_s, shell_output("#{{bin}}/{args.name} --version")',
        f'    system bin/{json_str(args.name)}, "schema"',
        "  end",
        "end",
        "",
    ]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines))
    print(f"Wrote {output}")


def json_str(value):
    # A double-quoted Ruby string literal; escapes backslashes, quotes and interpolation.
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("#{", "\\#{") + '"'


if __name__ == "__main__":
    main()
```

Rendered for a Rust release (`logsift 1.2.0`, four Unix targets) the formula has an `on_macos`/`on_linux` block each
with `on_arm`/`on_intel` `url` + `sha256` pairs, `bin.install "logsift"`, the man page and completion installs, and the
test block; a Swift `universal-apple-darwin` archive fills both macOS CPU blocks with the same URL.

## `.github/workflows/sync.yml`

`branching: gitflow` only, always together with `sync_versions.py`. Opens the next development cycle with a **minor**
bump and the `-dev` suffix (`1.4.0` → `1.5.0-dev`) — the convention of this catalog's `iru-rust-bump-version`,
`iru-go-bump-version`, `iru-swift-bump-version` (CLI `Version.swift`) and `iru-dotnet-bump-version`, which `iru-release`
follows. `<version-file>` is `Cargo.toml` (and `Cargo.lock`), `internal/version/version.go`,
`Sources/<Bin>Kit/Version.swift` or `Directory.Build.props`.

```yaml
name: Sync

# After a release is published from <stable-branch>, open a pull request that merges it back into
# <integration-branch> and moves the version to the next minor -dev version. The sync branch is named after the
# released version (sync_<release_version>); if the merge conflicts, the PR is opened from the release commit for manual
# resolution and the development version is not bumped.
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
            echo "::error::Release tag '$TAG' is not in vX.Y.Z / X.Y.Z form; cannot compute the next -dev version."
            exit 1
          fi
          IFS='.' read -r MAJOR MINOR _ <<< "$RELEASE_VERSION"
          NEXT_MINOR=$((MINOR + 1))
          {
            echo "release_version=$RELEASE_VERSION"
            echo "next_dev=${MAJOR}.${NEXT_MINOR}.0-dev"
            echo "sync_branch=sync_${RELEASE_VERSION}"
          } >> "$GITHUB_OUTPUT"

      - name: Skip if this release was already synced
        id: guard
        env:
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
        run: |
          if git ls-remote --exit-code --heads origin "$SYNC_BRANCH" >/dev/null 2>&1; then
            echo "::notice::$SYNC_BRANCH already exists on origin; skipping."
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
        env:
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
        run: |
          git fetch origin <integration-branch> <stable-branch>
          git checkout -b "$SYNC_BRANCH" "origin/<integration-branch>"

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

      - name: Bump to the next development version
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        env:
          CLI_LANGUAGE: <language>
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_DEV: ${{ steps.versions.outputs.next_dev }}
        run: python3 .github/scripts/sync_versions.py

      # The script only edits tracked files and the merge left the tree clean, so -u stages exactly its edits (a
      # pathspec list would abort the whole add as soon as one path, e.g. README.md, doesn't exist).
      - name: Commit the version bump
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        env:
          NEXT_DEV: ${{ steps.versions.outputs.next_dev }}
        run: |
          git add -u
          git diff --cached --quiet || git commit -m "Sync $NEXT_DEV"

      - name: Push sync branch
        if: steps.guard.outputs.exists == 'false'
        env:
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
        run: git push origin "$SYNC_BRANCH"

      - name: Ensure the sync label exists
        if: steps.guard.outputs.exists == 'false'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh label create sync --description "Sync pull request from a release branch back into <integration-branch>" --color 0e8a16 \
            || gh label list --search sync >/dev/null

      - name: Open pull request into <integration-branch>
        if: steps.guard.outputs.exists == 'false'
        env:
          GH_TOKEN: ${{ github.token }}
          CONFLICT: ${{ steps.merge.outputs.conflict }}
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_DEV: ${{ steps.versions.outputs.next_dev }}
        run: |
          if [ "$CONFLICT" = "true" ]; then
            TITLE="Sync $RELEASE_VERSION (merge conflicts to resolve)"
            BODY="Merges \`<stable-branch>\` (\`$RELEASE_VERSION\`) back into \`<integration-branch>\`. The automatic merge conflicted (usually on the version line), so this branch points at the release commit and the development version was **not** bumped. Resolve the conflicts keeping \`<integration-branch>\`'s development version and taking the release's changes otherwise."
          else
            TITLE="Sync $NEXT_DEV"
            BODY="Merges \`<stable-branch>\` back into \`<integration-branch>\` after releasing \`$RELEASE_VERSION\`, and moves the version in \`<version-file>\` to \`$NEXT_DEV\`."
          fi
          gh pr create \
            --base <integration-branch> \
            --head "$SYNC_BRANCH" \
            --title "$TITLE" \
            --body "$BODY" \
            --label sync
```

The Ubuntu image's system `python3` runs the script (stdlib only) — no `setup-python` step.

## `.github/scripts/sync_versions.py`

`branching: gitflow` only.

```python
#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow of a command-line tool.

Reads CLI_LANGUAGE (rust, go, swift or dotnet), RELEASE_VERSION (e.g. 1.4.0) and NEXT_DEV (e.g. 1.5.0-dev) from the
environment and updates the one place the tool's version lives (the scaffold contract of iru-setup-<lang>-cli):
- rust:   Cargo.toml [package] version, and the same package's entry in Cargo.lock (so `--locked` builds keep working).
- go:     internal/version/version.go `Version = "..."` (the release build overrides it with -ldflags -X).
- swift:  Sources/<Bin>Kit/Version.swift `toolVersion = "..."` (the only Version.swift declaring toolVersion).
- dotnet: Directory.Build.props <Version> (or <VersionPrefix> + <VersionSuffix>).
Then README.md "Current development version" / "Latest release" status-table rows and docs/antora.yml's version (the
latest release), when present.

CHANGELOG.md is intentionally untouched: its release section was written before the tag was published.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".build", ".git", "bin", "obj", "target", "dist", "node_modules", "artifacts"}


def read(path):
    # Bytes in, bytes out: a UTF-8 BOM and CRLF line endings survive, so an edit changes only the version text.
    return path.read_bytes().decode("utf-8")


def write(path, text):
    path.write_bytes(text.encode("utf-8"))


def replace_once(path, pattern, replacement, what, flags=0):
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count == 0:
        sys.exit(f"{path}: {what} not found")
    write(path, updated)


def update_rust(next_dev):
    manifest = REPO_ROOT / "Cargo.toml"
    text = read(manifest)
    package = re.search(r"(?ms)^\[package\][^\[]*", text)
    if not package:
        sys.exit(f"{manifest}: no [package] table (a virtual workspace root) — point this script at the binary crate")
    name = re.search(r'(?m)^name\s*=\s*"([^"]+)"', package.group(0))
    if not name:
        sys.exit(f"{manifest}: [package] has no name")
    section, count = re.subn(r'(?m)^(version\s*=\s*")[^"]*(")', rf"\g<1>{next_dev}\g<2>", package.group(0), count=1)
    if count == 0:
        sys.exit(f"{manifest}: [package] version not found (version.workspace = true is not supported here)")
    write(manifest, text[: package.start()] + section + text[package.end():])
    lock = REPO_ROOT / "Cargo.lock"
    if lock.exists():
        replace_once(
            lock,
            rf'(\[\[package\]\]\r?\nname = "{re.escape(name.group(1))}"\r?\nversion = ")[^"]*(")',
            rf"\g<1>{next_dev}\g<2>",
            f"the {name.group(1)} entry",
        )


def update_go(next_dev):
    path = REPO_ROOT / "internal" / "version" / "version.go"
    replace_once(path, r'(\bVersion\s*=\s*")[^"]*(")', rf"\g<1>{next_dev}\g<2>", "Version = \"...\"")


def update_swift(next_dev):
    candidates = []
    for path in (REPO_ROOT / "Sources").rglob("Version.swift"):
        if any(part in SKIP_DIRS for part in path.relative_to(REPO_ROOT).parts):
            continue
        if re.search(r'\btoolVersion\s*=\s*"', read(path)):
            candidates.append(path)
    if len(candidates) != 1:
        sys.exit(f"expected exactly one Sources/**/Version.swift declaring toolVersion, found {len(candidates)}")
    replace_once(candidates[0], r'(\btoolVersion\s*=\s*")[^"]*(")', rf"\g<1>{next_dev}\g<2>", "toolVersion")


def update_dotnet(next_dev):
    path = REPO_ROOT / "Directory.Build.props"
    text = read(path)
    updated, count = re.subn(r"(<Version>)[^<]*(</Version>)", rf"\g<1>{next_dev}\g<2>", text, count=1)
    if count == 0:
        prefix, _, suffix = next_dev.partition("-")
        updated, count = re.subn(r"(<VersionPrefix>)[^<]*(</VersionPrefix>)", rf"\g<1>{prefix}\g<2>", text, count=1)
        if count == 0:
            sys.exit(f"{path}: neither <Version> nor <VersionPrefix> found")
        updated, count = re.subn(r"(<VersionSuffix>)[^<]*(</VersionSuffix>)", rf"\g<1>{suffix}\g<2>", updated, count=1)
        if count == 0:
            sys.exit(f"{path}: <VersionPrefix> without <VersionSuffix>; add an empty <VersionSuffix></VersionSuffix>")
    write(path, updated)


def update_readme(release_version, next_dev):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = read(path)
    # Adjust these row labels if this repository's README table words them differently (iru-setup-readme's
    # defaults are "Current development version" / "Latest release").
    updated = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_dev}\g<2>", text)
    updated = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", updated)
    if updated != text:
        write(path, updated)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = read(path)
    updated, count = re.subn(r"(?m)^version:[^\r\n]*", f"version: {release_version}", text, count=1)
    if count:
        write(path, updated)


UPDATERS = {"rust": update_rust, "go": update_go, "swift": update_swift, "dotnet": update_dotnet}


def main():
    language = os.environ["CLI_LANGUAGE"]
    release_version = os.environ["RELEASE_VERSION"]
    next_dev = os.environ["NEXT_DEV"]
    if language not in UPDATERS:
        sys.exit(f"CLI_LANGUAGE '{language}' is not one of {', '.join(UPDATERS)}")
    if not re.fullmatch(r"\d+\.\d+\.\d+", release_version):
        sys.exit(f"RELEASE_VERSION '{release_version}' is not x.y.z")
    if not re.fullmatch(r"\d+\.\d+\.\d+-dev", next_dev):
        sys.exit(f"NEXT_DEV '{next_dev}' is not x.y.z-dev")
    UPDATERS[language](next_dev)
    update_readme(release_version, next_dev)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
```

## `.github/workflows/security.yml`

Always written; each job gated by its `security-*` key. The shared block of every `iru-setup-*-github-workflows` skill —
dependency review, CodeQL, OSV-Scanner, gitleaks — with the CodeQL job matched to the language:

| `language` | CodeQL language / build mode | Runner |
|---|---|---|
| `rust` | `rust`, `build-mode: none` — the only mode CodeQL supports for Rust (rust-analyzer runs build scripts and expands macros; needs `rustup`/`cargo`, preinstalled on the image) | `ubuntu-latest` |
| `go` | `go`, `build-mode: manual` + `go build ./...` (precise: exactly the packages that build) | `ubuntu-latest` |
| `swift` | `swift`, `build-mode: manual` + `swift build` — **Swift extraction needs macOS** (no Linux extractor) | `<macos-runner>` (~10× Linux minutes on a private repository: `security-codeql` defaults to `no` there; with `cross-os-ci: stable` it runs only on the weekly schedule, stable-branch pushes and `workflow_dispatch`) |
| `dotnet` | `csharp`, `build-mode: manual` + `dotnet build <Tool>.slnx` (sees source-generated code; a Native AOT project builds normally — AOT only happens on publish) | `ubuntu-latest` |

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
  codeql:
    # >>> lang: rust
    name: CodeQL (rust)
    runs-on: ubuntu-latest
    # <<< lang: rust
    # >>> lang: go
    name: CodeQL (go)
    runs-on: ubuntu-latest
    # <<< lang: go
    # >>> lang: dotnet
    name: CodeQL (csharp)
    runs-on: ubuntu-latest
    # <<< lang: dotnet
    # >>> lang: swift
    # Swift — macOS only (~10x Linux minutes on private repositories).
    name: CodeQL (swift)
    # >>> cross-os-ci: stable
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< cross-os-ci: stable
    runs-on: <macos-runner>
    timeout-minutes: 60
    # <<< lang: swift
    permissions:
      contents: read
      security-events: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      # >>> lang: go
      - name: Set up Go (go.mod)
        uses: actions/setup-go@v7
        with:
          go-version-file: go.mod
      # <<< lang: go
      # >>> lang: dotnet
      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json
      # <<< lang: dotnet
      # >>> lang: swift
      - name: Select Xcode <xcode-version>
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '<xcode-version>'
      # <<< lang: swift
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          # >>> lang: rust
          languages: rust
          build-mode: none
          # <<< lang: rust
          # >>> lang: go
          languages: go
          build-mode: manual
          # <<< lang: go
          # >>> lang: swift
          languages: swift
          build-mode: manual
          # <<< lang: swift
          # >>> lang: dotnet
          languages: csharp
          build-mode: manual
          # <<< lang: dotnet
      # >>> lang: go
      - name: Build
        run: go build ./...
      # <<< lang: go
      # >>> lang: swift
      - name: Build
        run: swift build
      # <<< lang: swift
      # >>> lang: dotnet
      - name: Build
        run: dotnet build <Tool>.slnx -c Release --no-incremental -p:UseSharedCompilation=false
      # <<< lang: dotnet
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          # >>> lang: rust
          category: /language:rust
          # <<< lang: rust
          # >>> lang: go
          category: /language:go
          # <<< lang: go
          # >>> lang: swift
          category: /language:swift
          # <<< lang: swift
          # >>> lang: dotnet
          category: /language:csharp
          # <<< lang: dotnet

  # <<< security-codeql
  # >>> security-osv
  # Reads Cargo.lock / go.mod / Package.resolved / packages.lock.json. A .NET project with central package management
  # and no lock files gives OSV nothing to read: NuGet's own restore audit (NU1901–NU1904, errors under
  # TreatWarningsAsErrors) is the effective gate there.
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

Always written (no opt-out). One entry for the language's ecosystem at the repository root (the Cargo workspace incl.
`xtask`, `go.mod`, `Package.swift` + `Package.resolved`, or every project through `Directory.Packages.props`) plus
`github-actions`. Not covered: `rust-toolchain.toml`, `go.mod`'s `go` directive/toolchain line (Dependabot's `gomod`
leaves it alone), `global.json`'s SDK pin and the Swift container tag in the workflows — bump those with the live
lookups of Step 2.

```yaml
version: 2
updates:
  # >>> lang: rust
  - package-ecosystem: cargo
    directory: /
    schedule:
      interval: weekly
    groups:
      cargo-dependencies:
        patterns:
          - "*"
  # <<< lang: rust
  # >>> lang: go
  - package-ecosystem: gomod
    directory: /
    schedule:
      interval: weekly
    groups:
      go-dependencies:
        patterns:
          - "*"
  # <<< lang: go
  # >>> lang: swift
  - package-ecosystem: swift
    directory: /
    schedule:
      interval: weekly
    groups:
      swift-dependencies:
        patterns:
          - "*"
  # <<< lang: swift
  # >>> lang: dotnet
  - package-ecosystem: nuget
    directory: /
    schedule:
      interval: weekly
    groups:
      nuget-dependencies:
        patterns:
          - "*"
  # <<< lang: dotnet

  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
    groups:
      github-actions-dependencies:
        patterns:
          - "*"
```
