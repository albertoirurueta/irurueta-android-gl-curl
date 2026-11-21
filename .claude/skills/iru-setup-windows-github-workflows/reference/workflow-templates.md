# Windows desktop workflow templates

**Conditional markers**: a line `# >>> <condition>` opens a block that is written only when the condition holds, and
the matching `# <<< <condition>` line closes it; marker lines are never written, blocks nest, and a comma-separated
condition must hold entirely. Markers also appear inside `run: |` scripts (same rule — the marker line is dropped).

| Condition | Holds when |
|---|---|
| `gitflow` / `main-only` | `branching` |
| `ui: wpf\|winforms` / `ui: winui` | the app project sets `UseWPF`/`UseWindowsForms` / `UseWinUI` |
| `msix` / `no-msix` | MSIX packaging exists — always for `ui: winui`; `packaging/AppxManifest.xml` + `packaging/build-msix.ps1` for WPF/WinForms / it doesn't |
| `flaui` | `tests/<AppName>.UITests/` exists |
| `ci-tests: full` | `ci-tests: full` (always written together with `flaui` — no UI-test project means `unit`) |
| `pages: yes` / `pages: no` | `pages` |
| `sonar` / `sonar: cloud` | `sonar` ≠ `none` / `sonar: cloud` (`self-hosted` has no organization) |
| `distribution: internal` / `distribution: store` | `distribution` (`release.yml` exists only when it is not `none`) |
| `signing: artifact-signing` / `signing: certificate` / `signed` | `signing`; `signed` = `signing` ≠ `none` |
| `sign-step` | `signed` **and** something to sign: `ui: wpf\|winforms` (the zip's exe) or `distribution: internal` (the MSIX) |
| `gh-release` | `ui: wpf\|winforms` (zips) or `distribution: internal` — something to attach to the GitHub Release |
| `winget` | `winget: yes` (implies `distribution: internal`, `signed`, public) |
| `security-*` | each enabled `security-*` key |

**Placeholders**: `<AppName>` (the `WinExe` project / solution name), `<integration-branch>`, `<stable-branch>`,
`<java-version>` (SonarScanner's JDK, default `21`), `<docfx-version>`, `<sonarscanner-version>`,
`<sonar-project-key>`, `<sonar-organization>`, `<sonar-host-url>`, `<winget-package-id>`. Remove the `win-arm64`
matrix leg (and its appinstaller/WinGet URL) when the scaffold's `rids` holds only `win-x64`.

## `.github/workflows/build.yml`

Always written. Jobs: `core` (Linux), `windows` (Windows), `sonar` (Linux for WPF/WinForms, Windows for WinUI; only
`sonar`), `docs` (Linux), `deploy-docs` (`pages: yes`).

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

env:
  DOTNET_NOLOGO: true
  DOTNET_CLI_TELEMETRY_OPTOUT: true
  NUGET_PACKAGES: ${{ github.workspace }}/.nuget/packages

jobs:
  # Everything that runs on Linux runs here — Windows minutes cost ~1.7x Linux minutes on private repositories.
  core:
    name: Core — build, format, tests, coverage (Linux)
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
      # >>> ui: wpf|winforms

      # EnableWindowsTargeting (Directory.Build.props) compiles WPF/WinForms (incl. XAML/designer) on Linux.
      - name: Restore
        run: dotnet restore <AppName>.slnx

      - name: Build (warnings and analyzer findings are errors)
        run: dotnet build <AppName>.slnx -c Release --no-restore

      - name: Format check (IDE rules the build doesn't enforce)
        run: dotnet format <AppName>.slnx --verify-no-changes --no-restore
      # <<< ui: wpf|winforms
      # >>> ui: winui

      # The WinUI app only builds on Windows (its XAML compiler is a Windows executable) — the windows job builds
      # it; here only the UI-free Core and its tests are built.
      - name: Restore
        run: dotnet restore tests/<AppName>.Core.Tests

      - name: Build Core and its tests (warnings and analyzer findings are errors)
        run: dotnet build tests/<AppName>.Core.Tests -c Release --no-restore

      # One call per project: formatting a project does not check the projects it references.
      - name: Format check (IDE rules the build doesn't enforce)
        run: |
          dotnet format src/<AppName>.Core/<AppName>.Core.csproj --verify-no-changes --no-restore
          dotnet format tests/<AppName>.Core.Tests/<AppName>.Core.Tests.csproj --verify-no-changes --no-restore
      # <<< ui: winui

      - name: Core tests with coverage
        run: >-
          dotnet test tests/<AppName>.Core.Tests -c Release --no-build
          --settings tests/coverlet.runsettings --collect "XPlat Code Coverage"
          --results-directory TestResults --logger "trx;LogFileName=core-tests.trx"

      # The UI assembly is excluded by tests/coverlet.runsettings ("not measured"), so this is Core's line rate.
      - name: Enforce 80% line coverage on Core
        run: |
          python3 - <<'PY'
          import glob, sys, xml.etree.ElementTree as ET
          reports = glob.glob("TestResults/*/coverage.cobertura.xml")
          if not reports:
              sys.exit("No coverage.cobertura.xml under TestResults/")
          rate = float(ET.parse(reports[0]).getroot().get("line-rate"))
          print(f"Core line coverage: {rate:.1%}")
          if rate < 0.80:
              sys.exit(f"Core line coverage {rate:.1%} is below the 80% bar")
          PY

      - name: Upload test results and coverage
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: core-test-results
          path: TestResults/
          retention-days: 14

  windows:
    # >>> ci-tests: full, flaui
    name: Windows — build, tests, desktop UI tests
    # <<< ci-tests: full, flaui
    # >>> ci-tests: unit
    name: Windows — build and tests
    # <<< ci-tests: unit
    runs-on: windows-latest
    timeout-minutes: 45
    defaults:
      run:
        shell: pwsh
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

      - name: Build the solution
        run: dotnet build <AppName>.slnx -c Release

      # On Windows the FlaUI project is a test project; its UI tests are excluded here.
      - name: Tests (no desktop UI tests)
        run: dotnet test <AppName>.slnx -c Release --no-build --filter "TestCategory!=UI"
      # >>> ci-tests: full, flaui
      # >>> ui: winui

      # A packaged WinUI app starts only with package identity: build an MSIX signed with a throwaway self-signed
      # certificate whose subject is the manifest's Publisher, trust it, install it, and hand the AUMID to FlaUI.
      - name: Set up MSBuild
        uses: microsoft/setup-msbuild@v3

      - name: Package and install the app for UI tests
        run: |
          [xml] $manifest = Get-Content -Raw src/<AppName>/Package.appxmanifest
          $identity = $manifest.Package.Identity
          $cert = New-SelfSignedCertificate -Type Custom -Subject $identity.Publisher -KeyUsage DigitalSignature `
            -FriendlyName 'CI UI-test signing' -CertStoreLocation 'Cert:\CurrentUser\My' `
            -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')
          $cer = Join-Path $env:RUNNER_TEMP 'uitest.cer'
          Export-Certificate -Cert $cert -FilePath $cer | Out-Null
          Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
          $appx = Join-Path $env:RUNNER_TEMP 'uitest-appx\'
          msbuild src/<AppName>/<AppName>.csproj /restore /p:Configuration=Release /p:Platform=x64 `
            /p:RuntimeIdentifier=win-x64 /p:SelfContained=true /p:WindowsAppSDKSelfContained=true `
            /p:GenerateAppxPackageOnBuild=true /p:AppxBundle=Never /p:UapAppxPackageBuildMode=SideloadOnly `
            /p:AppxPackageSigningEnabled=true /p:PackageCertificateThumbprint=$($cert.Thumbprint) "/p:AppxPackageDir=$appx"
          if ($LASTEXITCODE -ne 0) { throw 'MSIX build failed.' }
          $msix = Get-ChildItem $appx -Recurse -Filter *.msix | Select-Object -First 1
          Add-AppxPackage -Path $msix.FullName
          $family = (Get-AppxPackage -Name $identity.Name).PackageFamilyName
          "APP_AUMID=$family!App" >> $env:GITHUB_ENV
      # <<< ui: winui

      # Hosted Windows runners have an interactive desktop session, which FlaUI needs. Each runner is a fresh VM,
      # so the repository-wide .integration-test.lock used for local runs is not needed here.
      - name: Desktop UI tests (FlaUI)
        env:
          # >>> ui: wpf|winforms
          APP_EXE: ${{ github.workspace }}\src\<AppName>\bin\Release\net10.0-windows\<AppName>.exe
          # <<< ui: wpf|winforms
          # The UI tests capture every state × direction × theme here (the catalog's UI contract).
          UI_SCREENSHOTS_DIR: ${{ github.workspace }}\.ui-screenshots
        run: >-
          dotnet test tests/<AppName>.UITests -c Release --no-build --filter "TestCategory=UI"
          --results-directory TestResults --logger "trx;LogFileName=ui-tests.trx"

      - name: Upload UI test results
        if: (!cancelled())
        uses: actions/upload-artifact@v7
        with:
          name: ui-test-results
          path: TestResults/
          retention-days: 14

      - name: Upload UI screenshots
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-screenshots
          path: .ui-screenshots/
          retention-days: 7
          if-no-files-found: ignore
      # <<< ci-tests: full, flaui
  # >>> sonar

  # Skipped on pull requests from forks — they never receive SONAR_TOKEN.
  sonar:
    name: SonarQube analysis
    # >>> ui: wpf|winforms
    runs-on: ubuntu-latest
    # <<< ui: wpf|winforms
    # >>> ui: winui
    # The WinUI app project builds only on Windows, and SonarScanner for .NET analyzes what the build compiles.
    runs-on: windows-latest
    # <<< ui: winui
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    defaults:
      run:
        shell: bash
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

      # begin -> build -> test (OpenCover + TRX) -> end. The UI project and tests are excluded from coverage:
      # XAML/forms code is "not measured", never 0%.
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
            /d:sonar.coverage.exclusions="src/<AppName>/**,tests/**"
          dotnet build <AppName>.slnx -c Release --no-incremental
          dotnet test tests/<AppName>.Core.Tests -c Release --no-build \
            --settings tests/coverlet.runsettings --collect "XPlat Code Coverage" \
            --results-directory TestResults --logger "trx;LogFileName=core-tests.trx"
          "$scanner" end /d:sonar.token="$SONAR_TOKEN"
  # <<< sonar

  docs:
    name: Docs — Antora + DocFX
    runs-on: ubuntu-latest
    steps:
      - name: Check out code
        uses: actions/checkout@v7

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      # docfx.json reads only the UI-free Core project (net10.0), so this runs on Linux for every ui.
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
> `/o:` line joins the `begin` command (cloud) or disappears (self-hosted) — never leave a marker line in the output,
> it would end the continuation.

## `.github/workflows/release.yml`

Written only when `distribution` ≠ `none`. Triggered like the other stacks' release workflows: a **published GitHub
Release** (`release: [released]`, i.e. not a pre-release) on the stable branch, or `workflow_dispatch` with an existing
tag (dry run). The tag must equal `Directory.Build.props` `<Version>` (`v1.4.0` ↔ `1.4.0`) — a `-dev` version fails
the job, so cut releases with `iru-release`/`iru-dotnet-bump-version` first.

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
  DOTNET_NOLOGO: true
  DOTNET_CLI_TELEMETRY_OPTOUT: true

jobs:
  package:
    name: Package (${{ matrix.rid }})
    runs-on: windows-latest
    timeout-minutes: 60
    # >>> signing: artifact-signing
    # The Azure federated credential trusts this environment (subject repo:<owner>/<repo>:environment:release).
    environment: release
    permissions:
      contents: read
      id-token: write
    # <<< signing: artifact-signing
    strategy:
      fail-fast: true
      matrix:
        include:
          - rid: win-x64
            arch: x64
            platform: x64
          - rid: win-arm64
            arch: arm64
            platform: ARM64
    defaults:
      run:
        shell: pwsh
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json

      - name: Read and check the release version
        id: version
        run: |
          $version = (Select-Xml -Path Directory.Build.props -XPath '/Project/PropertyGroup/Version')[0].Node.InnerText.Trim()
          $tagVersion = $env:RELEASE_TAG -replace '^v', ''
          if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Directory.Build.props <Version> '$version' is not a release version x.y.z." }
          if ($version -ne $tagVersion) { throw "Directory.Build.props <Version> '$version' does not match the tag '$env:RELEASE_TAG'." }
          "version=$version" >> $env:GITHUB_OUTPUT
          "msix_version=$version.0" >> $env:GITHUB_OUTPUT
      # >>> ui: wpf|winforms

      # Self-contained single-file exe for the zip. Never PublishTrimmed/PublishAot: WPF (NETSDK1168) and
      # Windows Forms (NETSDK1175) don't support them.
      - name: Publish the single-file app
        run: >-
          dotnet publish src/<AppName> -c Release -r ${{ matrix.rid }} --self-contained
          -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
          -o artifacts/zip-staging/${{ matrix.rid }}
      # >>> msix

      # Unsigned on purpose (no WINDOWS_CERTIFICATE_PATH here): signing is one later step for every file.
      - name: Build the MSIX
        run: ./packaging/build-msix.ps1 -RuntimeIdentifier ${{ matrix.rid }} -Version ${{ steps.version.outputs.version }}
      # <<< msix
      # <<< ui: wpf|winforms
      # >>> ui: winui

      - name: Set up MSBuild
        uses: microsoft/setup-msbuild@v3

      # iru-dotnet-bump-version already wrote x.y.z.0 on the release branch; stamping it again is a no-op guard.
      - name: Stamp the MSIX identity version
        run: |
          $path = (Resolve-Path src/<AppName>/Package.appxmanifest).Path
          [xml] $manifest = Get-Content -Raw $path
          $manifest.Package.Identity.Version = '${{ steps.version.outputs.msix_version }}'
          $manifest.Save($path)

      # Microsoft's CI-for-WinUI recipe: MSBuild (not dotnet build) with single-project MSIX packaging, unsigned.
      - name: Build the MSIX
        run: |
          $appx = Join-Path $env:GITHUB_WORKSPACE 'artifacts\appx\'
          msbuild src/<AppName>/<AppName>.csproj /restore /p:Configuration=Release `
            /p:Platform=${{ matrix.platform }} /p:RuntimeIdentifier=${{ matrix.rid }} /p:SelfContained=true `
          # >>> distribution: internal
            /p:WindowsAppSDKSelfContained=true `
          # <<< distribution: internal
            /p:GenerateAppxPackageOnBuild=true /p:AppxBundle=Never /p:UapAppxPackageBuildMode=SideloadOnly `
            /p:AppxPackageSigningEnabled=false "/p:AppxPackageDir=$appx"
          if ($LASTEXITCODE -ne 0) { throw 'MSIX build failed.' }
          New-Item -ItemType Directory -Force -Path artifacts/msix | Out-Null
          $msix = Get-ChildItem $appx -Recurse -Filter *.msix | Select-Object -First 1
          Copy-Item $msix.FullName "artifacts/msix/<AppName>_${{ steps.version.outputs.msix_version }}_${{ matrix.arch }}.msix"
      # <<< ui: winui
      # >>> sign-step

      - name: List the files to sign
        id: tosign
        run: |
          $files = @()
          # >>> ui: wpf|winforms
          $files += (Resolve-Path "artifacts/zip-staging/${{ matrix.rid }}/<AppName>.exe").Path
          # <<< ui: wpf|winforms
          # >>> distribution: internal, msix
          $files += (Resolve-Path "artifacts/msix/<AppName>_${{ steps.version.outputs.msix_version }}_${{ matrix.arch }}.msix").Path
          # <<< distribution: internal, msix
          "files=$($files -join ',')" >> $env:GITHUB_OUTPUT
      # >>> signing: artifact-signing

      # OpenID Connect: no client secret is stored. The MSIX Publisher must equal the certificate profile's subject.
      - name: Azure login (OIDC)
        uses: azure/login@v3
        with:
          client-id: ${{ secrets.AZURE_CLIENT_ID }}
          tenant-id: ${{ secrets.AZURE_TENANT_ID }}
          subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}

      - name: Sign with Azure Artifact Signing
        uses: azure/artifact-signing-action@v2
        with:
          endpoint: ${{ vars.ARTIFACT_SIGNING_ENDPOINT }}
          signing-account-name: ${{ vars.ARTIFACT_SIGNING_ACCOUNT }}
          certificate-profile-name: ${{ vars.ARTIFACT_SIGNING_CERTIFICATE_PROFILE }}
          files: ${{ steps.tosign.outputs.files }}
          file-digest: SHA256
          timestamp-rfc3161: http://timestamp.acs.microsoft.com
          timestamp-digest: SHA256
      # <<< signing: artifact-signing
      # >>> signing: certificate

      # The base64 PFX is decoded to a temp file only for this step. The MSIX Publisher must equal its subject.
      - name: Sign with the code-signing certificate
        env:
          WINDOWS_CERTIFICATE_PFX_BASE64: ${{ secrets.WINDOWS_CERTIFICATE_PFX_BASE64 }}
          WINDOWS_CERTIFICATE_PASSWORD: ${{ secrets.WINDOWS_CERTIFICATE_PASSWORD }}
          FILES: ${{ steps.tosign.outputs.files }}
        run: |
          $pfx = Join-Path $env:RUNNER_TEMP 'signing.pfx'
          [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_CERTIFICATE_PFX_BASE64))
          try {
            $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
            $signtool = Get-ChildItem $kits -Directory -Filter '10.*' | Sort-Object { [version]$_.Name } -Descending |
              ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
            if (-not $signtool) { throw 'signtool.exe not found in the Windows SDK.' }
            foreach ($file in $env:FILES -split ',') {
              & $signtool sign /fd SHA256 /f $pfx /p $env:WINDOWS_CERTIFICATE_PASSWORD /tr http://timestamp.digicert.com /td SHA256 $file
              if ($LASTEXITCODE -ne 0) { throw "signtool failed for $file." }
            }
          }
          finally {
            Remove-Item -Force $pfx -ErrorAction SilentlyContinue
          }
      # <<< signing: certificate
      # <<< sign-step
      # >>> gh-release

      - name: Collect the release files
        run: |
          New-Item -ItemType Directory -Force -Path artifacts/release | Out-Null
          # >>> ui: wpf|winforms
          Compress-Archive -Path "artifacts/zip-staging/${{ matrix.rid }}/<AppName>.exe" `
            -DestinationPath "artifacts/release/<AppName>-${{ steps.version.outputs.version }}-${{ matrix.rid }}.zip"
          # <<< ui: wpf|winforms
          # >>> distribution: internal, msix, signed
          Copy-Item "artifacts/msix/<AppName>_${{ steps.version.outputs.msix_version }}_${{ matrix.arch }}.msix" artifacts/release/
          # <<< distribution: internal, msix, signed
          Get-ChildItem artifacts/release

      - name: Upload the release files
        uses: actions/upload-artifact@v7
        with:
          name: release-${{ matrix.rid }}
          path: artifacts/release/
          retention-days: 14
      # <<< gh-release
      # >>> distribution: store, msix

      # The Store re-signs submitted packages, so these stay unsigned.
      - name: Upload the Store package
        uses: actions/upload-artifact@v7
        with:
          name: store-${{ matrix.rid }}
          path: artifacts/msix/*.msix
          retention-days: 14
      # <<< distribution: store, msix
  # >>> gh-release

  github-release:
    name: Attach files to the GitHub Release
    needs: package
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Download the packaged files
        uses: actions/download-artifact@v8
        with:
          pattern: release-*
          path: artifacts/release
          merge-multiple: true
      # >>> distribution: internal, msix, signed

      # One .appinstaller per architecture (App Installer 2021 schema). Its own Uri is the stable
      # releases/latest/download/ URL, so installed apps keep updating from the newest release.
      - name: Generate the App Installer files
        run: python3 .github/scripts/make_appinstaller.py --app-name <AppName> --dir artifacts/release
      # <<< distribution: internal, msix, signed

      - name: Upload to the GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release upload "$RELEASE_TAG" artifacts/release/* --clobber
  # <<< gh-release
  # >>> winget

  # The first submission to microsoft/winget-pkgs is manual (wingetcreate new); this job only updates it.
  winget:
    name: Submit the update to WinGet
    needs: github-release
    runs-on: windows-latest
    defaults:
      run:
        shell: pwsh
    steps:
      - name: Update the winget-pkgs manifest with wingetcreate
        env:
          WINGET_TOKEN: ${{ secrets.WINGET_TOKEN }}
        run: |
          Invoke-WebRequest -Uri https://aka.ms/wingetcreate/latest -OutFile wingetcreate.exe
          $version = $env:RELEASE_TAG -replace '^v', ''
          $base = "https://github.com/$env:GITHUB_REPOSITORY/releases/download/$env:RELEASE_TAG"
          # The installer type must match the first (manual) submission: the signed MSIX when there is one,
          # otherwise the zip (a portable app nested in a zip).
          # >>> msix
          $urls = @("$base/<AppName>_$version.0_x64.msix|x64", "$base/<AppName>_$version.0_arm64.msix|arm64")
          # <<< msix
          # >>> ui: wpf|winforms, no-msix
          $urls = @("$base/<AppName>-$version-win-x64.zip|x64", "$base/<AppName>-$version-win-arm64.zip|arm64")
          # <<< ui: wpf|winforms, no-msix
          ./wingetcreate.exe update <winget-package-id> --version $version --urls $urls --submit --token $env:WINGET_TOKEN
          if ($LASTEXITCODE -ne 0) { throw 'wingetcreate update failed.' }
  # <<< winget
  # >>> distribution: store

  store:
    name: Submit to the Microsoft Store
    needs: package
    runs-on: windows-latest
    defaults:
      run:
        shell: pwsh
    steps:
      - name: Check out code
        uses: actions/checkout@v7
        with:
          ref: ${{ env.RELEASE_TAG }}

      - name: Download the Store packages
        uses: actions/download-artifact@v8
        with:
          pattern: store-*
          path: artifacts/msix
          merge-multiple: true

      # One .msixbundle carrying every architecture, so the submission has a single package to pick up.
      - name: Bundle the per-architecture packages
        run: |
          $version = ($env:RELEASE_TAG -replace '^v', '') + '.0'
          $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
          $makeappx = Get-ChildItem $kits -Directory -Filter '10.*' | Sort-Object { [version]$_.Name } -Descending |
            ForEach-Object { Join-Path $_.FullName 'x64\makeappx.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
          if (-not $makeappx) { throw 'makeappx.exe not found in the Windows SDK.' }
          New-Item -ItemType Directory -Force -Path artifacts/store | Out-Null
          & $makeappx bundle /d artifacts/msix /p "artifacts/store/<AppName>_$version.msixbundle" /bv $version /o
          if ($LASTEXITCODE -ne 0) { throw 'makeappx bundle failed.' }

      - name: Keep the bundle as a workflow artifact (manual Partner Center upload fallback)
        uses: actions/upload-artifact@v7
        with:
          name: store-msixbundle
          path: artifacts/store/
          retention-days: 30

      - name: Set up the Microsoft Store Developer CLI
        uses: microsoft/microsoft-store-apppublisher@v1

      - name: Configure the Microsoft Store Developer CLI
        env:
          PARTNER_CENTER_TENANT_ID: ${{ secrets.PARTNER_CENTER_TENANT_ID }}
          PARTNER_CENTER_SELLER_ID: ${{ secrets.PARTNER_CENTER_SELLER_ID }}
          PARTNER_CENTER_CLIENT_ID: ${{ secrets.PARTNER_CENTER_CLIENT_ID }}
          PARTNER_CENTER_CLIENT_SECRET: ${{ secrets.PARTNER_CENTER_CLIENT_SECRET }}
        run: >-
          msstore reconfigure --tenantId $env:PARTNER_CENTER_TENANT_ID --sellerId $env:PARTNER_CENTER_SELLER_ID
          --clientId $env:PARTNER_CENTER_CLIENT_ID --clientSecret $env:PARTNER_CENTER_CLIENT_SECRET

      # Creates a submission with the bundle and commits it for certification (add --noCommit to keep a draft).
      - name: Publish the submission
        env:
          STORE_PRODUCT_ID: ${{ secrets.STORE_PRODUCT_ID }}
        run: msstore publish src/<AppName> --inputDirectory artifacts/store --appId $env:STORE_PRODUCT_ID
  # <<< distribution: store
```

## `.github/scripts/make_appinstaller.py`

Written with `release.yml` when `distribution: internal`, `msix` and `signed` hold. Renders the `.appinstaller`
template below once per `<AppName>_<x.y.z.0>_<arch>.msix` in the release folder, reading `Name`/`Publisher` from the
committed MSIX manifest (`packaging/AppxManifest.xml` for WPF/WinForms, `src/<AppName>/Package.appxmanifest` for
WinUI) so it never drifts from the signed package. Element order follows the 2021 schema
(`MainPackage` → `UpdateSettings`); `ProcessorArchitecture` is mandatory on a `MainPackage`.

```python
#!/usr/bin/env python3
"""Writes one App Installer file (2021 schema) per MSIX in a release folder, for the Release workflow.

Each <AppName>-<arch>.appinstaller points at the versioned MSIX of this release and at itself through the stable
https://github.com/<owner>/<repo>/releases/latest/download/ URL, so an installed app keeps updating from whichever
release is newest. Environment: RELEASE_TAG (e.g. v1.4.0) and GITHUB_REPOSITORY (owner/repo) — both set on GitHub
Actions.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_NS = "{http://schemas.microsoft.com/appx/manifest/foundation/windows10}"

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<AppInstaller
    xmlns="http://schemas.microsoft.com/appx/appinstaller/2021"
    Version={version}
    Uri={self_uri}>
  <MainPackage
      Name={name}
      Publisher={publisher}
      Version={version}
      ProcessorArchitecture={arch}
      Uri={package_uri} />
  <UpdateSettings>
    <OnLaunch HoursBetweenUpdateChecks="12" ShowPrompt="true" UpdateBlocksActivation="false" />
    <AutomaticBackgroundTask />
  </UpdateSettings>
</AppInstaller>
"""


def read_identity(app_name):
    for candidate in (REPO_ROOT / "packaging" / "AppxManifest.xml",
                      REPO_ROOT / "src" / app_name / "Package.appxmanifest"):
        if candidate.exists():
            identity = ET.parse(candidate).getroot().find(f"{MANIFEST_NS}Identity")
            if identity is not None:
                return identity.get("Name"), identity.get("Publisher")
    sys.exit("No MSIX manifest with an <Identity> found (packaging/AppxManifest.xml or src/<AppName>/Package.appxmanifest)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-name", required=True)
    parser.add_argument("--dir", required=True, type=Path)
    args = parser.parse_args()

    tag = os.environ["RELEASE_TAG"]
    repository = os.environ["GITHUB_REPOSITORY"]
    name, publisher = read_identity(args.app_name)
    pattern = re.compile(rf"^{re.escape(args.app_name)}_(\d+\.\d+\.\d+\.\d+)_(x64|arm64|x86)\.msix$")

    written = 0
    for package in sorted(args.dir.glob("*.msix")):
        match = pattern.match(package.name)
        if not match:
            continue
        version, arch = match.groups()
        target = args.dir / f"{args.app_name}-{arch}.appinstaller"
        target.write_text(TEMPLATE.format(
            version=quoteattr(version),
            self_uri=quoteattr(f"https://github.com/{repository}/releases/latest/download/{target.name}"),
            name=quoteattr(name),
            publisher=quoteattr(publisher),
            arch=quoteattr(arch),
            package_uri=quoteattr(f"https://github.com/{repository}/releases/download/{tag}/{package.name}"),
        ), encoding="utf-8")
        print(f"Wrote {target}")
        written += 1
    if written == 0:
        sys.exit(f"No {args.app_name}_<x.y.z.0>_<arch>.msix in {args.dir}")


if __name__ == "__main__":
    main()
```

Rendered result for `Bookshelf_1.4.0.0_x64.msix` in `example-org/bookshelf` (the shape to expect):

```xml
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller
    xmlns="http://schemas.microsoft.com/appx/appinstaller/2021"
    Version="1.4.0.0"
    Uri="https://github.com/example-org/bookshelf/releases/latest/download/Bookshelf-x64.appinstaller">
  <MainPackage
      Name="JaneDoe.Bookshelf"
      Publisher="CN=Jane Doe"
      Version="1.4.0.0"
      ProcessorArchitecture="x64"
      Uri="https://github.com/example-org/bookshelf/releases/download/v1.4.0/Bookshelf_1.4.0.0_x64.msix" />
  <UpdateSettings>
    <OnLaunch HoursBetweenUpdateChecks="12" ShowPrompt="true" UpdateBlocksActivation="false" />
    <AutomaticBackgroundTask />
  </UpdateSettings>
</AppInstaller>
```

## `.github/workflows/sync.yml`

`branching: gitflow` only, always together with `sync_versions.py`. Opens the next development cycle with a **minor**
bump and `-dev` (`1.4.0` → `1.5.0-dev`) — `iru-dotnet-bump-version`'s Step 5 convention, which `iru-release` follows.

```yaml
name: Sync

# After a release is published from <stable-branch>, open a pull request that merges it back into
# <integration-branch> and moves <Version> to the next -dev version (minor bump). The sync branch is named after the
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
          IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
          if [ -z "${MAJOR:-}" ] || [ -z "${MINOR:-}" ] || [ -z "${PATCH:-}" ]; then
            echo "::error::Release tag '$RELEASE_VERSION' is not in X.Y.Z form; cannot compute the next -dev version."
            exit 1
          fi
          NEXT_MINOR=$((MINOR + 1))
          {
            echo "release_version=$RELEASE_VERSION"
            echo "next_dev=${MAJOR}.${NEXT_MINOR}.0-dev"
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

      - name: Bump to the next development version
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        env:
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_DEV: ${{ steps.versions.outputs.next_dev }}
        run: python3 .github/scripts/sync_versions.py

      - name: Commit the version bump
        if: steps.guard.outputs.exists == 'false' && steps.merge.outputs.conflict == 'false'
        run: |
          # The script only edits tracked files and the merge left the tree clean, so -u stages exactly its edits.
          # (A pathspec list would abort the whole add as soon as one path, e.g. *.appxmanifest, matches nothing.)
          git add -u
          git diff --cached --quiet || git commit -m "Sync ${{ steps.versions.outputs.next_dev }}"

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
          CONFLICT: ${{ steps.merge.outputs.conflict }}
          RELEASE_VERSION: ${{ steps.versions.outputs.release_version }}
          NEXT_DEV: ${{ steps.versions.outputs.next_dev }}
          SYNC_BRANCH: ${{ steps.versions.outputs.sync_branch }}
        run: |
          if [ "$CONFLICT" = "true" ]; then
            TITLE="Sync ${RELEASE_VERSION} (merge conflicts to resolve)"
            BODY="Merges \`<stable-branch>\` (\`${RELEASE_VERSION}\`) back into \`<integration-branch>\`. The automatic merge conflicted (usually on the version line), so this branch points at the release commit and the development version was **not** bumped. Resolve the conflicts keeping \`<integration-branch>\`'s development version and taking the release's changes otherwise."
          else
            TITLE="Sync ${NEXT_DEV}"
            BODY="Merges \`<stable-branch>\` back into \`<integration-branch>\` after releasing \`${RELEASE_VERSION}\`, and moves \`<Version>\` in \`Directory.Build.props\` to \`${NEXT_DEV}\` (MSIX identity \`x.y.z.0\`)."
          fi
          gh pr create \
            --base <integration-branch> \
            --head "$SYNC_BRANCH" \
            --title "$TITLE" \
            --body "$BODY" \
            --label sync
```

## `.github/scripts/sync_versions.py`

`branching: gitflow` only.

```python
#!/usr/bin/env python3
"""Rewrites version references after a release, for the Sync workflow.

Reads RELEASE_VERSION (e.g. 1.4.0) and NEXT_DEV (e.g. 1.5.0-dev) from the environment and updates:
- Directory.Build.props: <Version> = NEXT_DEV (or <VersionPrefix> 1.5.0 + <VersionSuffix> dev when split that way).
- Every MSIX manifest outside build output (Package.appxmanifest, AppxManifest.xml): <Identity Version> = 1.5.0.0 —
  always four-part numeric with revision 0, suffix stripped (iru-dotnet-bump-version's convention).
- README.md status-table rows and docs/antora.yml's version (the latest release).

CHANGELOG.md is intentionally untouched: its release section was written before the tag was published.
"""
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {"bin", "obj", "AppPackages", "BundleArtifacts", "artifacts", "node_modules", ".git"}


def read(path):
    # Bytes in, bytes out: a UTF-8 BOM survives as U+FEFF and CRLF line endings are never translated, so an edit
    # changes only the version text — never the whole file (Visual Studio writes manifests/props with BOM + CRLF).
    return path.read_bytes().decode("utf-8")


def write(path, text):
    path.write_bytes(text.encode("utf-8"))


def update_directory_build_props(next_dev):
    path = REPO_ROOT / "Directory.Build.props"
    text = read(path)
    updated, count = re.subn(r"(<Version>)[^<]*(</Version>)", rf"\g<1>{next_dev}\g<2>", text, count=1)
    if count == 0:
        prefix, _, suffix = next_dev.partition("-")
        updated, count = re.subn(r"(<VersionPrefix>)[^<]*(</VersionPrefix>)", rf"\g<1>{prefix}\g<2>", text, count=1)
        if count == 0:
            sys.exit(f"{path}: neither <Version> nor <VersionPrefix> found")
        updated, suffix_count = re.subn(r"(<VersionSuffix>)[^<]*(</VersionSuffix>)", rf"\g<1>{suffix}\g<2>", updated, count=1)
        if suffix_count == 0:
            sys.exit(f"{path}: <VersionPrefix> without <VersionSuffix>; add an empty <VersionSuffix></VersionSuffix>")
    write(path, updated)


def msix_manifests():
    for path in REPO_ROOT.rglob("*"):
        if path.name not in ("Package.appxmanifest", "AppxManifest.xml"):
            continue
        if any(part in SKIP_DIRS for part in path.relative_to(REPO_ROOT).parts[:-1]):
            continue
        yield path


def update_msix_identity(next_dev):
    msix_version = next_dev.split("-")[0] + ".0"
    for path in msix_manifests():
        text = read(path)
        # \bVersion only matches the Identity element's own attribute (never MinVersion/MaxVersionTested).
        updated, count = re.subn(r'(<Identity\b[^>]*?\bVersion=")[^"]*(")', rf"\g<1>{msix_version}\g<2>", text,
                                 count=1, flags=re.DOTALL)
        if count:
            write(path, updated)


def update_readme(release_version, next_dev):
    path = REPO_ROOT / "README.md"
    if not path.exists():
        return
    text = read(path)
    # Adjust these row labels if this repository's README table words them differently (iru-setup-readme's
    # defaults are "Current development version" / "Latest release").
    text = re.sub(r"(\| Current development version \| `)[^`]*(` \|)", rf"\g<1>{next_dev}\g<2>", text)
    text = re.sub(r"(\| Latest release[^|]*\| `)[^`]*(` \|)", rf"\g<1>{release_version}\g<2>", text)
    write(path, text)


def update_antora_component_version(release_version):
    path = REPO_ROOT / "docs" / "antora.yml"
    if not path.exists():
        return
    text = read(path)
    updated, count = re.subn(r"(?m)^version:[^\r\n]*", f"version: {release_version}", text, count=1)
    if count:
        write(path, updated)


def main():
    release_version = os.environ["RELEASE_VERSION"]
    next_dev = os.environ["NEXT_DEV"]
    if not re.fullmatch(r"\d+\.\d+\.\d+-dev", next_dev):
        sys.exit(f"NEXT_DEV '{next_dev}' is not x.y.z-dev")
    update_directory_build_props(next_dev)
    update_msix_identity(next_dev)
    update_readme(release_version, next_dev)
    update_antora_component_version(release_version)


if __name__ == "__main__":
    main()
```

## `.github/workflows/security.yml`

Always written; each job gated by its `security-*` key. CodeQL `csharp` runs on Linux in both cases:
`build-mode: manual` for WPF/WinForms (a real `dotnet build` of the whole solution through `EnableWindowsTargeting`
— the most precise mode, and it sees source-generated code), `build-mode: none` for WinUI (the app can't build on
Linux, and a Windows runner just for CodeQL would cost ~1.7× — buildless extraction still covers every `.cs` file;
code generated from XAML at build time is not analyzed).

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
  codeql:
    name: CodeQL (csharp)
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - name: Check out code
        uses: actions/checkout@v7
      - name: Set up .NET (global.json)
        uses: actions/setup-dotnet@v6
        with:
          global-json-file: global.json
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: csharp
          # >>> ui: wpf|winforms
          build-mode: manual
          # <<< ui: wpf|winforms
          # >>> ui: winui
          build-mode: none
          # <<< ui: winui
      # >>> ui: wpf|winforms
      - name: Build the solution
        run: dotnet build <AppName>.slnx -c Release --no-incremental -p:UseSharedCompilation=false
      # <<< ui: wpf|winforms
      - name: Perform CodeQL analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:csharp

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

Always written (no opt-out). `nuget` reads `Directory.Packages.props` (central package management) and every
project; `global.json`'s SDK pin is not covered (bump it with the SDK lookup in `iru-setup-windows-app`).

```yaml
version: 2
updates:
  - package-ecosystem: nuget
    directory: /
    schedule:
      interval: weekly
    groups:
      nuget-dependencies:
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
