# Packaging, versions and publish properties

Read this when a task touches an MSIX manifest, capabilities, publish properties/profiles, RIDs, or anything about how
the app ships. Packaging itself (building an MSIX, signing, Store/WinGet submission) runs in `release.yml`
(`iru-setup-windows-github-workflows`) and is **unverifiable off Windows** — a task here only edits the inputs.

## Where packaging lives

| Stack | MSIX source | Built by |
|---|---|---|
| WinUI | `src/<App>/Package.appxmanifest` (single-project MSIX, `EnableMsixTooling`) + `Assets/` | MSBuild: `msbuild /restore -p:Configuration=Release -p:Platform=x64 -p:GenerateAppxPackageOnBuild=true -p:AppxBundle=Never -p:UapAppxPackageBuildMode=SideloadOnly` (Microsoft's "CI for WinUI 3") |
| WPF / WinForms (`msix: yes`) | `packaging/AppxManifest.xml` + `packaging/Assets/` | `packaging/build-msix.ps1`: `dotnet publish -r <rid> --self-contained` → layout → `makeappx pack` → `signtool sign` when signing is configured |
| WPF / WinForms (`msix: no`) | none | `dotnet publish -c Release -r win-x64 --self-contained -p:PublishSingleFile=true` → zip |

## Manifest rules

- **Identity** (`Name`, `Publisher`, `Version`): `Publisher` must equal the signing certificate's subject (`CN=…`)
  exactly; `Name` is the package identity — changing it makes a different app (users lose data and updates). Never
  change either unless the task is explicitly about identity.
- **`Version`** is four-part numeric `x.y.z.0`, derived from `Directory.Build.props` `<Version>` (`1.4.0-dev` →
  `1.4.0.0`) and **owned by `iru-dotnet-bump-version`** — don't hand-edit it in a feature task. The Store requires
  revision `0`.
- **Capabilities**: minimal. A desktop (full-trust) app already has `runFullTrust` (restricted capability, declared
  under `rescap`). Adding any other capability — `internetClient` is usually implicit for full trust; `webcam`,
  `microphone`, `location`, `broadFileSystemAccess`, `appCapabilityAccess`… — needs the task to say so and is a Store
  review item; otherwise it's a blocker to confirm.
- `Applications/Application`: `Executable="<App>.exe"`, `EntryPoint="Windows.FullTrustApplication"`; visual assets
  (`Square150x150Logo`, `Square44x44Logo`, `Wide310x150Logo`, `StoreLogo`) must point at files that exist.
- Extensions (protocol/file-type associations, startup task, app execution alias, share target) go under
  `Applications/Application/Extensions` with the right namespace prefix (`uap`, `uap3`, `desktop`) declared and listed
  in `IgnorableNamespaces`.

## Publish properties

- RIDs: `win-x64`, `win-arm64` (the catalog's default pair); `win-x86` only if the task asks.
- **Trimming / Native AOT**: **not supported for WPF and Windows Forms** — `PublishTrimmed`/`PublishAot` fail with
  `NETSDK1168` (WPF) / are unsupported (WinForms). WinUI supports both (Windows App SDK 1.6+), but only add them when
  the task asks and the app's reflection use (the MVVM Toolkit's generators are AOT-friendly; `{Binding}` isn't) has
  been considered.
- Single-file: fine for WPF/WinForms self-contained (`PublishSingleFile`, `IncludeNativeLibrariesForSelfExtract` for
  WPF's native libraries); WinUI single-file only unpackaged + self-contained.
- `ReadyToRun` (`PublishReadyToRun=true`) improves startup; it's per-RID and makes the output bigger.
- Publish profiles: `Properties/PublishProfiles/win-<arch>.pubxml` (WinUI template) — keep them in sync with the
  `RuntimeIdentifiers` list.

## Versions elsewhere

`<Version>` in `Directory.Build.props` (`x.y.z-dev` on the gitflow integration branch, `x.y.z` on stable) drives
`AssemblyVersion`/`FileVersion`/`InformationalVersion`; don't add explicit ones. Never put a version in a
`.appinstaller` committed to the repository — the release workflow generates it.
