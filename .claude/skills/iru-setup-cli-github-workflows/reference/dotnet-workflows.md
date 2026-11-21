# .NET workflow templates (`language: dotnet`)

`build.yml` and `release.yml` for a .NET 10 command-line tool laid out like `iru-setup-dotnet-cli`'s scaffold:
`<Tool>.slnx`, `global.json`, `Directory.Build.props` (`<Version>0.1.0-dev</Version>`, `TreatWarningsAsErrors`,
`GenerateDocumentationFile`, analyzers), `Directory.Packages.props`, `src/<Tool>` (`OutputType Exe`, `AssemblyName
<bin>`, `PublishAot`, `InvariantGlobalization`, System.CommandLine, and for the `nuget` channel `PackAsTool` +
`ToolCommandName` + `ToolPackageRuntimeIdentifiers`, including `any` — a framework-dependent fallback package), `src/<Tool>.Core` (logic), `tests/<Tool>.Core.Tests` (MSTest +
coverlet — the 80% bar), `tests/<Tool>.Cli.Tests` (in-process command tree + published-binary tests), `docfx.json` →
`build/docfx/`. Markers and shared placeholders are explained in `shared-templates.md`; `release.yml` ends with that
file's **release tail** (`publish`, and `homebrew` / `winget` per `channels`).

.NET placeholders: `<Tool>` (solution / project name), `<package-id>` (the tool's NuGet `PackageId`, default
`<Tool>`), `<nuget-user>` (the nuget.org account that owns the package — trusted publishing), `<docfx-version>`,
`<sonarscanner-version>`, `<java-version>` (SonarScanner's JDK, default `21`), `<coverage-settings>` (`--settings
tests/coverlet.runsettings --collect "XPlat Code Coverage"` when that file exists — the scaffold writes it with Cobertura
+ OpenCover formats — else `--collect "XPlat Code Coverage;Format=cobertura,opencover"`), `<cli-docs-command>` (default
`dotnet run --project src/<Tool> -c Release -- gen-docs --output-dir .` — the scaffold's hidden `gen-docs` command:
`docs/modules/ROOT/pages/cli-reference.adoc`, `artifacts/man/<bin>.1`, `artifacts/completions/*`).

**Native AOT per RID on native runners.** Native AOT compiles for the OS it runs on (it needs that platform's linker:
clang on Linux, Xcode on macOS, MSVC on Windows), so every RID builds on its own runner; macOS is the one exception —
`osx-x64` cross-compiles on the Apple-silicon runner (verified locally). Shipped RIDs: `linux-x64`
(`ubuntu-latest`), `linux-arm64` (`ubuntu-24.04-arm`), `osx-arm64` + `osx-x64` (`macos-26`), `win-x64`
(`windows-latest`), `win-arm64` (`windows-11-arm`). Linux AOT binaries link glibc dynamically (Ubuntu 24.04's glibc
2.39 is the floor) — say so in the report; build in an older container if older distributions matter.

**The `nuget` channel — a RID-specific Native AOT .NET tool** (a .NET 10 SDK feature): each matrix leg also runs
`dotnet pack -r <rid>` → `<package-id>.<rid>.<version>.nupkg` holding that RID's AOT binary, and one Linux job packs
the RID-less pointer package `<package-id>.<version>.nupkg` that lists them (`ToolPackageRuntimeIdentifiers`) —
plus, when that list contains `any`, the framework-dependent `<package-id>.any.<version>.nupkg` fallback.
`dotnet tool install -g <package-id>` then downloads only the matching native package (verified end to end from a local
feed on osx-arm64). Requires the .NET 10 SDK on the user's machine.

## `.github/workflows/build.yml`

Jobs: `core` (Linux — build, format, tests, coverage, smoke test), `cross-os` (macOS + Windows, gated, `needs: core`),
`sonar` (Linux, `sonar` only), `docs` (Linux), `deploy-docs` (`pages: yes`).

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
  DOTNET_NOLOGO: true
  DOTNET_CLI_TELEMETRY_OPTOUT: true
  NUGET_PACKAGES: ${{ github.workspace }}/.nuget/packages

jobs:
  core:
    name: Build, format, tests, coverage, contract smoke test (Linux)
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      - name: Cache NuGet packages
        uses: actions/cache@v6
        with:
          path: ${{ github.workspace }}/.nuget/packages
          key: ${{ runner.os }}-nuget-${{ hashFiles('Directory.Packages.props', '**/*.csproj', 'global.json') }}
          restore-keys: |
            ${{ runner.os }}-nuget-

      - name: Restore
        run: dotnet restore <Tool>.slnx

      - name: Build (warnings, analyzer findings and missing XML docs are errors)
        run: dotnet build <Tool>.slnx -c Release --no-restore

      - name: Format check (IDE rules the build doesn't enforce)
        run: dotnet format <Tool>.slnx --verify-no-changes --no-restore

      - name: Core tests with coverage
        run: >-
          dotnet test tests/<Tool>.Core.Tests -c Release --no-build
          <coverage-settings>
          --results-directory TestResults --logger "trx;LogFileName=core-tests.trx"

      # The bar measures <Tool>.Core; the thin command-line project is "not measured".
      - name: Enforce 80% line coverage on Core
        run: |
          python3 - <<'PY'
          import glob, sys, xml.etree.ElementTree as ET
          reports = glob.glob("TestResults/*/coverage.cobertura.xml")
          if not reports:
              sys.exit("No coverage.cobertura.xml under TestResults/")
          rate = float(ET.parse(reports[0]).getroot().get("line-rate"))
          print(f"Core line coverage: {rate:.1%} (bar: 80%)")
          if rate < 0.80:
              sys.exit(f"::error::Core line coverage {rate:.1%} is below the 80% bar")
          PY

      # The command tree in-process (exit codes, JSON, stderr) and the published-binary tests.
      - name: CLI end-to-end tests
        run: dotnet test tests/<Tool>.Cli.Tests -c Release --no-build --results-directory TestResults --logger "trx;LogFileName=cli-tests.trx"
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

      - name: Upload test results and coverage
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: test-results
          path: TestResults/
          retention-days: 14

      # The JIT apphost from the build — same contract, seconds instead of a Native AOT compile.
      - name: Contract smoke test
        run: |
          version="$(sed -nE 's/.*<Version>([^<]+)<\/Version>.*/\1/p' Directory.Build.props | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$(find src/<Tool>/bin/Release -maxdepth 2 -type f -name <bin> | head -n 1)" "$version"
      # >>> ci-tests: full

      # Native AOT is what ships: trimming/AOT warnings are errors, and the binary must keep the contract.
      - name: Contract smoke test (Native AOT, linux-x64)
        run: |
          sudo apt-get update -qq
          sudo apt-get install -y -qq --no-install-recommends clang zlib1g-dev
          dotnet publish src/<Tool> -c Release -r linux-x64 -o artifacts/aot
          version="$(sed -nE 's/.*<Version>([^<]+)<\/Version>.*/\1/p' Directory.Build.props | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh artifacts/aot/<bin> "$version"
      # <<< ci-tests: full

  # macOS minutes cost ~10x and Windows ~1.7x Linux minutes on private repositories — see cross-os-ci.
  cross-os:
    name: Tests (${{ matrix.os }})
    needs: core
    # >>> cross-os-ci: stable
    if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref == 'refs/heads/<stable-branch>')
    # <<< cross-os-ci: stable
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: macos-26
            rid: osx-arm64
          - os: windows-latest
            rid: win-x64
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    defaults:
      run:
        shell: bash
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      - name: Cache NuGet packages
        uses: actions/cache@v6
        with:
          path: ${{ github.workspace }}/.nuget/packages
          key: ${{ runner.os }}-nuget-${{ hashFiles('Directory.Packages.props', '**/*.csproj', 'global.json') }}
          restore-keys: |
            ${{ runner.os }}-nuget-

      # Paths, terminals, signals and console encodings differ per OS — the CLI tests are the point of this leg.
      - name: Tests
        run: dotnet test <Tool>.slnx -c Release
      # >>> ci-tests: full

      - name: Contract smoke test (Native AOT)
        env:
          RID: ${{ matrix.rid }}
        run: |
          dotnet publish src/<Tool> -c Release -r "$RID" -o artifacts/aot
          exe=artifacts/aot/<bin>
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          version="$(sed -nE 's/.*<Version>([^<]+)<\/Version>.*/\1/p' Directory.Build.props | head -n 1)"
          bash .github/scripts/cli_smoke_test.sh "$exe" "$version"
      # <<< ci-tests: full
  # >>> sonar

  # Skipped on pull requests from forks — they never receive SONAR_TOKEN.
  sonar:
    name: SonarQube analysis
    needs: core
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0

      - name: Set up JDK <java-version> (SonarScanner)
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '<java-version>'

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      - name: Install SonarScanner for .NET
        run: dotnet tool install dotnet-sonarscanner --version <sonarscanner-version> --tool-path "$RUNNER_TEMP/sonar-scanner"

      # begin -> build -> Core tests (OpenCover + TRX) -> end. The command-line project and tests are excluded from
      # coverage ("not measured", never 0%).
      - name: Analyze
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: |
          set -euo pipefail
          scanner="$RUNNER_TEMP/sonar-scanner/dotnet-sonarscanner"
          "$scanner" begin /k:"<sonar-project-key>" \
          # >>> sonar: cloud
            /o:"<sonar-organization>" \
          # <<< sonar: cloud
            /d:sonar.host.url="<sonar-host-url>" /d:sonar.token="$SONAR_TOKEN" \
            /d:sonar.cs.opencover.reportsPaths="TestResults/*/coverage.opencover.xml" \
            /d:sonar.cs.vstest.reportsPaths="TestResults/*.trx" \
            /d:sonar.coverage.exclusions="src/<Tool>/**,tests/**"
          dotnet build <Tool>.slnx -c Release --no-incremental
          dotnet test tests/<Tool>.Core.Tests -c Release --no-build \
            --collect "XPlat Code Coverage;Format=cobertura,opencover" \
            --results-directory TestResults --logger "trx;LogFileName=core-tests.trx"
          "$scanner" end /d:sonar.token="$SONAR_TOKEN"
  # <<< sonar

  docs:
    name: Docs — Antora, DocFX, CLI reference
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json
      # >>> cli-docs

      # The CLI reference is generated from the command tree (the `schema` output); committed pages must match it
      # (irurueta docs: ai/cli-for-agents/documenting-clis.adoc — "regenerate and diff in CI").
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

      # docfx.json reads the UI-free Core project (net10.0).
      - name: Generate DocFX API docs
        run: |
          dotnet tool install docfx --version <docfx-version> --tool-path "$RUNNER_TEMP/docfx"
          "$RUNNER_TEMP/docfx/docfx" docfx.json --warningsAsErrors

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

      - name: Merge DocFX API docs into the Antora site
        run: |
          touch ./docs/build/site/.nojekyll
          mkdir -p ./docs/build/site/api
          cp -r ./build/docfx/. ./docs/build/site/api/

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

> The `Analyze` step's `# >>> sonar: cloud` markers sit inside a backslash-continued command: after rendering, the
> `/o:` line joins the `begin` command (cloud) or disappears (self-hosted) — never leave a marker line in the output.

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. Triggers, dry run and tag check as in the other languages; the version is
`Directory.Build.props` `<Version>` (cut releases with `iru-release`/`iru-dotnet-bump-version`).

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
  DOTNET_NOLOGO: true
  DOTNET_CLI_TELEMETRY_OPTOUT: true

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
          version="$(sed -nE 's/.*<Version>([^<]+)<\/Version>.*/\1/p' Directory.Build.props | head -n 1)"
          tag_version="${RELEASE_TAG#v}"
          if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Directory.Build.props <Version> '$version' is not a release version x.y.z."
            exit 1
          fi
          if [[ "$version" != "$tag_version" ]]; then
            echo "::error::Directory.Build.props <Version> '$version' does not match the tag '$RELEASE_TAG'."
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_OUTPUT"

  build:
    name: Build (${{ matrix.rid }})
    needs: version
    strategy:
      fail-fast: true
      matrix:
        include:
          - rid: linux-x64
            os: ubuntu-latest
            smoke: true
          - rid: linux-arm64
            os: ubuntu-24.04-arm
            smoke: true
          - rid: osx-arm64
            os: macos-26
            smoke: true
          # Cross-compiled on the Apple-silicon runner (Native AOT supports osx-x64 from osx-arm64); not executed.
          - rid: osx-x64
            os: macos-26
            smoke: false
          - rid: win-x64
            os: windows-latest
            smoke: true
          - rid: win-arm64
            os: windows-11-arm
            smoke: true
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    defaults:
      run:
        shell: bash
    env:
      VERSION: ${{ needs.version.outputs.version }}
      RID: ${{ matrix.rid }}
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      # Native AOT on Linux links with clang and needs zlib's headers.
      - name: Install the Native AOT prerequisites
        if: runner.os == 'Linux'
        run: |
          sudo apt-get update -qq
          sudo apt-get install -y -qq --no-install-recommends clang zlib1g-dev

      - name: Publish (Native AOT)
        run: dotnet publish src/<Tool> -c Release -r "$RID" -o "artifacts/publish/$RID"

      - name: Contract smoke test
        if: matrix.smoke
        run: |
          exe="artifacts/publish/$RID/<bin>"
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          bash .github/scripts/cli_smoke_test.sh "$exe" "$VERSION"
      # >>> cli-docs

      - name: Generate the man page and completions
        run: <cli-docs-command>
      # <<< cli-docs

      # Flat archives: only the native executable (no .pdb/.dSYM/.dbg), README.md, CHANGELOG.md, LICENSE*, and the
      # generated man/ and completions/ when the generator writes them under artifacts/.
      - name: Package
        run: |
          set -euo pipefail
          name="<bin>-$VERSION-$RID"
          stage="$RUNNER_TEMP/$name"
          mkdir -p "$stage" dist
          exe="<bin>"
          if [[ "$RUNNER_OS" == Windows ]]; then exe="$exe.exe"; fi
          cp "artifacts/publish/$RID/$exe" "$stage/"
          for file in README.md CHANGELOG.md LICENSE*; do
            if [[ -f "$file" ]]; then cp "$file" "$stage/"; fi
          done
          if [[ -d artifacts/man ]]; then cp -r artifacts/man "$stage/man"; fi
          if [[ -d artifacts/completions ]]; then cp -r artifacts/completions "$stage/completions"; fi
          if [[ "$RUNNER_OS" == Windows ]]; then
            pwsh -NoProfile -Command "Compress-Archive -Path '$stage/*' -DestinationPath 'dist/$name.zip'"
          else
            tar -C "$stage" -czf "dist/$name.tar.gz" .
          fi
          ls -l dist

      - name: Upload the archive
        uses: actions/upload-artifact@v7
        with:
          name: dist-${{ matrix.rid }}
          path: dist/
          retention-days: 7
      # >>> channel: nuget

      # The RID-specific Native AOT tool package <package-id>.<rid>.<version>.nupkg.
      - name: Pack the RID-specific tool package
        run: dotnet pack src/<Tool> -c Release -r "$RID" -o nupkg

      - name: Upload the tool package
        uses: actions/upload-artifact@v7
        with:
          name: nupkg-${{ matrix.rid }}
          path: nupkg/*.nupkg
          retention-days: 7
      # <<< channel: nuget
  # >>> channel: nuget

  # The RID-less pointer package plus every RID-specific package, pushed together after the GitHub Release has its
  # archives. A dry run packs and lists them without pushing.
  nuget:
    name: Publish the .NET tool to NuGet
    needs: [ version, publish ]
    runs-on: ubuntu-latest
    # >>> nuget-auth: trusted
    permissions:
      contents: read
      id-token: write
    # <<< nuget-auth: trusted
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      - name: Download the RID-specific packages
        uses: actions/download-artifact@v8
        with:
          pattern: nupkg-*
          path: nupkg
          merge-multiple: true

      - name: Pack the pointer package
        run: |
          dotnet pack src/<Tool> -c Release -o nupkg
          ls -l nupkg
      # >>> nuget-auth: trusted

      # NuGet trusted publishing: a short-lived API key for GitHub's OIDC identity. Add a trusted publishing policy for
      # this repository and release.yml on nuget.org first (the package's first version may be pushed by hand).
      - name: NuGet login (trusted publishing)
        if: github.event_name == 'release'
        id: nuget-login
        uses: NuGet/login@v1
        with:
          user: <nuget-user>

      - name: Push to nuget.org
        if: github.event_name == 'release'
        env:
          NUGET_API_KEY: ${{ steps.nuget-login.outputs.NUGET_API_KEY }}
        run: dotnet nuget push "nupkg/*.nupkg" --api-key "$NUGET_API_KEY" --source https://api.nuget.org/v3/index.json --skip-duplicate
      # <<< nuget-auth: trusted
      # >>> nuget-auth: api-key

      - name: Push to nuget.org
        if: github.event_name == 'release'
        env:
          NUGET_API_KEY: ${{ secrets.NUGET_API_KEY }}
        run: dotnet nuget push "nupkg/*.nupkg" --api-key "$NUGET_API_KEY" --source https://api.nuget.org/v3/index.json --skip-duplicate
      # <<< nuget-auth: api-key
  # <<< channel: nuget
```

Then append the **release tail** from `shared-templates.md` with `<win-x64-target>` = `win-x64` and
`<win-arm64-target>` = `win-arm64`. The Homebrew formula maps `osx-arm64`/`osx-x64`/`linux-arm64`/`linux-x64`
archives to its `on_macos`/`on_linux` × `on_arm`/`on_intel` blocks.
