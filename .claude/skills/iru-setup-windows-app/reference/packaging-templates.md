# Assets and packaging templates

Three things live here:

1. **`generate-assets.py`** — run at scaffold time (never committed) to write placeholder artwork: the app icon
   `src/__AppName__/Assets/AppIcon.ico` (every `ui`), the WinUI single-project MSIX tiles under
   `src/__AppName__/Assets/` (`ui: winui`), and the hand-packaged MSIX tiles under `packaging/Assets/` (`ui: wpf|
   winforms` with `msix: yes`). Plain standard-library Python (`zlib` + `struct`), no Pillow.
2. **`packaging/AppxManifest.xml`** (`ui: wpf|winforms`, `msix: yes`) — the MSIX manifest for a full-trust desktop
   app packaged without a Windows Application Packaging Project: `Windows.FullTrustApplication` entry point,
   `runFullTrust`, four-part `Version`.
3. **`packaging/build-msix.ps1`** (`ui: wpf|winforms`, `msix: yes`) — `dotnet publish` per RID (self-contained, never
   trimmed/AOT — unsupported for WPF/WinForms, NETSDK1168) → package layout → `makeappx pack` → `signtool sign`
   when a PFX is supplied. Azure Artifact Signing is applied by the CI workflow (`iru-setup-windows-github-workflows`)
   to the unsigned output instead.

WinUI does not use `packaging/`: its `Package.appxmanifest` is built by the Windows App SDK single-project MSIX
tooling (`msbuild /restore -p:GenerateAppxPackageOnBuild=true …` — see `winui-templates.md`).

`__Token__` placeholders as described in SKILL.md. Verified on macOS on 2026-10-16: the generator output (valid PNG
and ICO files per `file`), `build-msix.ps1` parsed and run by PowerShell 7.6 with `-LayoutOnly` (both
`win-x64` and `win-arm64` published self-contained and laid out with the manifest's `Version`/`ProcessorArchitecture`
rewritten); `makeappx`/`signtool` themselves and installing the package are unverified locally (Windows SDK only).

### Scaffold helper: `generate-assets.py` (run from a scratch directory, never written into the repository)

```python
"""Write placeholder icon/tile PNGs and an .ico for a Windows app scaffold.

Usage: python3 -I generate-assets.py <repo-root> <app-name> <ui> <msix yes|no>
Replace every generated image with real artwork before shipping (Store certification rejects placeholder tiles).
"""
import pathlib
import struct
import sys
import zlib

BACKGROUND = (0x00, 0x5A, 0x9E, 0xFF)
FOREGROUND = (0xFF, 0xFF, 0xFF, 0xFF)


def png(width, height):
    side = min(width, height)
    margin = side // 4
    x0, x1 = (width - side) // 2 + margin, (width + side) // 2 - margin
    y0, y1 = (height - side) // 2 + margin, (height + side) // 2 - margin
    back, fore = bytes(BACKGROUND), bytes(FOREGROUND)
    rows = []
    for y in range(height):
        inside = y0 <= y < y1
        row = bytearray(b"\x00")
        for x in range(width):
            row += fore if inside and x0 <= x < x1 else back
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def ico(sizes):
    images = [png(size, size) for size in sizes]
    entries, data = b"", b""
    offset = 6 + 16 * len(images)
    for size, image in zip(sizes, images):
        entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(image), offset + len(data))
        data += image
    return struct.pack("<HHH", 0, 1, len(images)) + entries + data


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    print(path)


root, app, ui, msix = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
app_assets = root / "src" / app / "Assets"
write(app_assets / "AppIcon.ico", ico([16, 24, 32, 48, 256]))

if ui == "winui":
    for name, (w, h) in {
        "SplashScreen.scale-200.png": (1240, 600),
        "LockScreenLogo.scale-200.png": (48, 48),
        "Square150x150Logo.scale-200.png": (300, 300),
        "Square44x44Logo.scale-200.png": (88, 88),
        "Square44x44Logo.targetsize-24_altform-unplated.png": (24, 24),
        "StoreLogo.png": (50, 50),
        "Wide310x150Logo.scale-200.png": (620, 300),
    }.items():
        write(app_assets / name, png(w, h))
elif msix == "yes":
    for name, (w, h) in {
        "Square150x150Logo.png": (150, 150),
        "Square44x44Logo.png": (44, 44),
        "StoreLogo.png": (50, 50),
        "Wide310x150Logo.png": (310, 150),
    }.items():
        write(root / "packaging" / "Assets" / name, png(w, h))
```

### `packaging/AppxManifest.xml` (ui: wpf|winforms, msix)

```xml
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">

  <!-- Name/Publisher must match the Partner Center reservation (Store) or the signing certificate subject.
       build-msix.ps1 rewrites Version (four-part, revision 0) and ProcessorArchitecture per runtime identifier. -->
  <Identity
    Name="__PackageName__"
    Publisher="__Publisher__"
    Version="__MsixVersion__"
    ProcessorArchitecture="x64" />

  <Properties>
    <DisplayName>__DisplayName__</DisplayName>
    <PublisherDisplayName>__PublisherDisplayName__</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>

  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>

  <Resources>
    <Resource Language="en-us" />
  </Resources>

  <Applications>
    <Application Id="App" Executable="__AppName__.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements
        DisplayName="__DisplayName__"
        Description="__DisplayName__"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" />
      </uap:VisualElements>
    </Application>
  </Applications>

  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
```

### `packaging/build-msix.ps1` (ui: wpf|winforms, msix)

```powershell
#Requires -Version 7.2
<#
.SYNOPSIS
    Publishes __AppName__ per runtime identifier and packs (and optionally signs) one MSIX per architecture.

.DESCRIPTION
    dotnet publish (self-contained; WPF/Windows Forms support neither trimming nor Native AOT) -> package layout
    (publish output + packaging/Assets + packaging/AppxManifest.xml with Version/ProcessorArchitecture rewritten)
    -> makeappx pack -> signtool sign when -CertificatePath is given. Unsigned packages are what Azure Artifact
    Signing (CI) or the Microsoft Store (which re-signs) take; an unsigned MSIX cannot be installed by users.

.EXAMPLE
    pwsh packaging/build-msix.ps1
    pwsh packaging/build-msix.ps1 -RuntimeIdentifier win-x64 -CertificatePath cert.pfx -CertificatePassword $env:PFX_PASSWORD
#>
[CmdletBinding()]
param(
    [ValidateSet('win-x64', 'win-arm64')]
    [string[]] $RuntimeIdentifier = @('win-x64', 'win-arm64'),
    [string] $Configuration = 'Release',
    # Defaults to <Version> in Directory.Build.props; any pre-release suffix is dropped (x.y.z-dev -> x.y.z.0).
    [string] $Version,
    [string] $OutputDirectory,
    [string] $CertificatePath = $env:WINDOWS_CERTIFICATE_PATH,
    [string] $CertificatePassword = $env:WINDOWS_CERTIFICATE_PASSWORD,
    [string] $TimestampUrl = 'http://timestamp.digicert.com',
    # Stop after building the layouts (no makeappx/signtool) - for inspection, or on a non-Windows host.
    [switch] $LayoutOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$appName = '__AppName__'
$repoRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $repoRoot "src/$appName/$appName.csproj"
$artifacts = Join-Path $repoRoot 'artifacts'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $artifacts 'msix' }

if (-not $Version) {
    $node = Select-Xml -Path (Join-Path $repoRoot 'Directory.Build.props') -XPath '/Project/PropertyGroup/Version'
    if (-not $node) { throw 'No <Version> in Directory.Build.props; pass -Version.' }
    $Version = $node[0].Node.InnerText.Trim()
}
$numericVersion = ($Version -split '[-+]')[0]
if ($numericVersion -notmatch '^\d+\.\d+\.\d+$') { throw "Version '$Version' is not x.y.z[-suffix]." }
$msixVersion = "$numericVersion.0"

function Find-WindowsSdkTool([string] $Name) {
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $programFilesX86 = ${env:ProgramFiles(x86)}
    $kits = if ($programFilesX86) { Join-Path $programFilesX86 'Windows Kits/10/bin' } else { $null }
    if ($kits -and (Test-Path $kits)) {
        $hostArch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq 'Arm64') { 'arm64' } else { 'x64' }
        $tool = Get-ChildItem $kits -Directory -Filter '10.*' |
            Sort-Object { [version]$_.Name } -Descending |
            ForEach-Object { Join-Path $_.FullName "$hostArch/$Name" } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($tool) { return $tool }
    }
    throw "$Name not found - install the Windows SDK (it ships on the windows-latest GitHub runner)."
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

foreach ($rid in $RuntimeIdentifier) {
    $arch = $rid.Split('-')[1]
    $publishDir = Join-Path $artifacts "publish/$rid"
    $layoutDir = Join-Path $artifacts "msix-layout/$rid"

    Write-Host "Publishing $appName $Version for $rid"
    dotnet publish $project -c $Configuration -r $rid --self-contained true -p:PublishSingleFile=false -o $publishDir
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed for $rid." }

    if (Test-Path $layoutDir) { Remove-Item -Recurse -Force $layoutDir }
    New-Item -ItemType Directory -Force -Path $layoutDir | Out-Null
    Copy-Item -Recurse -Path (Join-Path $publishDir '*') -Destination $layoutDir
    Copy-Item -Recurse -Force -Path (Join-Path $PSScriptRoot 'Assets') -Destination $layoutDir

    [xml] $manifest = Get-Content -Raw -Path (Join-Path $PSScriptRoot 'AppxManifest.xml')
    $manifest.Package.Identity.Version = $msixVersion
    $manifest.Package.Identity.ProcessorArchitecture = $arch
    $manifest.Save((Join-Path $layoutDir 'AppxManifest.xml'))

    if ($LayoutOnly) {
        Write-Host "Layout ready: $layoutDir (MSIX version $msixVersion, $arch)"
        continue
    }

    $package = Join-Path $OutputDirectory "${appName}_${msixVersion}_$arch.msix"
    & (Find-WindowsSdkTool 'makeappx.exe') pack /d $layoutDir /p $package /o
    if ($LASTEXITCODE -ne 0) { throw "makeappx failed for $rid." }

    if ($CertificatePath) {
        & (Find-WindowsSdkTool 'signtool.exe') sign /fd SHA256 /f $CertificatePath /p $CertificatePassword /tr $TimestampUrl /td SHA256 $package
        if ($LASTEXITCODE -ne 0) { throw "signtool failed for $package." }
    }
    else {
        Write-Warning "$package is unsigned: sign it (Artifact Signing / certificate) or submit it to the Store."
    }
    Write-Host "Packed $package"
}
```
