# Common templates — repository-level build files

Repository-level build files, the solution, central package management, analyzer configuration, the coverage run
settings and the DocFX configuration. Written for every `ui` value.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`, `<!-- >>> <condition> -->` … in XML) and the
`__Token__` placeholders work as described in SKILL.md's "Reference files" section. A heading suffixed `(condition)`
is written only when the condition holds. Every file below was rendered and built on 2026-10-16 for `ui: none` and
`ui: tui` — see SKILL.md "Verification notes".

### `.editorconfig`

```ini
root = true

[*]
charset = utf-8
indent_style = space
insert_final_newline = true
trim_trailing_whitespace = true

[*.{csproj,props,targets,slnx,xml,runsettings,json,yml,yaml}]
indent_size = 2

[*.{sh,bash,zsh,fish,ps1}]
indent_size = 4

[*.cs]
indent_size = 4

# Language style (enforced in the build through EnforceCodeStyleInBuild + TreatWarningsAsErrors).
csharp_style_namespace_declarations = file_scoped:warning
csharp_using_directive_placement = outside_namespace:warning
csharp_prefer_braces = true:warning
csharp_style_var_for_built_in_types = true:suggestion
csharp_style_var_when_type_is_apparent = true:suggestion
dotnet_sort_system_directives_first = true
dotnet_style_qualification_for_field = false:warning
dotnet_style_qualification_for_property = false:warning
dotnet_style_qualification_for_method = false:warning
dotnet_style_qualification_for_event = false:warning
dotnet_diagnostic.IDE0005.severity = warning

# Naming: private instance fields are _camelCase; constants and static readonly fields PascalCase.
dotnet_naming_rule.constants_pascal.symbols = constants
dotnet_naming_rule.constants_pascal.style = pascal
dotnet_naming_rule.constants_pascal.severity = warning
dotnet_naming_symbols.constants.applicable_kinds = field
dotnet_naming_symbols.constants.required_modifiers = const
dotnet_naming_style.pascal.capitalization = pascal_case

dotnet_naming_rule.static_readonly_pascal.symbols = static_readonly_fields
dotnet_naming_rule.static_readonly_pascal.style = pascal
dotnet_naming_rule.static_readonly_pascal.severity = warning
dotnet_naming_symbols.static_readonly_fields.applicable_kinds = field
dotnet_naming_symbols.static_readonly_fields.required_modifiers = static, readonly

dotnet_naming_rule.private_fields_underscore.symbols = private_fields
dotnet_naming_rule.private_fields_underscore.style = underscore_camel
dotnet_naming_rule.private_fields_underscore.severity = warning
dotnet_naming_symbols.private_fields.applicable_kinds = field
dotnet_naming_symbols.private_fields.applicable_accessibilities = private
dotnet_naming_style.underscore_camel.capitalization = camel_case
dotnet_naming_style.underscore_camel.required_prefix = _

# StyleCop house choices.
# SA1101 (prefix local calls with this.) and SA1309 (no leading underscore) contradict the _camelCase field convention.
dotnet_diagnostic.SA1101.severity = none
dotnet_diagnostic.SA1309.severity = none
# File headers (SA1633-SA1641) are owned by the license-header tooling (iru-check-license), not by StyleCop.
dotnet_diagnostic.SA1633.severity = none
dotnet_diagnostic.SA1634.severity = none
dotnet_diagnostic.SA1635.severity = none
dotnet_diagnostic.SA1636.severity = none
dotnet_diagnostic.SA1637.severity = none
dotnet_diagnostic.SA1638.severity = none
dotnet_diagnostic.SA1639.severity = none
dotnet_diagnostic.SA1640.severity = none
dotnet_diagnostic.SA1641.severity = none

[tests/**.cs]
# Test classes and methods are self-describing; underscores separate the scenario parts of a test name.
dotnet_diagnostic.CS1591.severity = none
dotnet_diagnostic.SA1600.severity = none
dotnet_diagnostic.CA1707.severity = none
```

### `__ToolName__.slnx`

```xml
<Solution>
  <Folder Name="/Solution Items/">
    <File Path=".editorconfig" />
    <File Path="Directory.Build.props" />
    <File Path="Directory.Packages.props" />
    <File Path="global.json" />
    <File Path="stylecop.json" />
  </Folder>
  <Folder Name="/src/">
    <Project Path="src/__ToolName__.Core/__ToolName__.Core.csproj" />
    <Project Path="src/__ToolName__/__ToolName__.csproj" />
  </Folder>
  <Folder Name="/tests/">
    <Project Path="tests/__ToolName__.Core.Tests/__ToolName__.Core.Tests.csproj" />
    <Project Path="tests/__ToolName__.Cli.Tests/__ToolName__.Cli.Tests.csproj" />
  </Folder>
</Solution>
```

### `Directory.Build.props`

```xml
<Project>
  <!-- Shared by every project in the repository. The tool version lives here (iru-dotnet-bump-version edits it). -->
  <PropertyGroup>
    <Version>__Version__</Version>
    <Product>__ToolName__</Product>
    <Description>__Description__</Description>
    <Company>__Company__</Company>
    <Authors>__Company__</Authors>
    <Copyright>Copyright (c) __Year__ __Company__</Copyright>
    <!-- >>> license -->
    <PackageLicenseExpression>__PackageLicenseExpression__</PackageLicenseExpression>
    <!-- <<< license -->
    <!-- >>> repository-url -->
    <PackageProjectUrl>__RepositoryUrl__</PackageProjectUrl>
    <RepositoryUrl>__RepositoryUrl__</RepositoryUrl>
    <!-- <<< repository-url -->
    <PackageTags>cli;command-line</PackageTags>
    <!-- The version option prints exactly the Version above: no "+commit" suffix from SourceLink. -->
    <IncludeSourceRevisionInInformationalVersion>false</IncludeSourceRevisionInInformationalVersion>
  </PropertyGroup>

  <PropertyGroup>
    <LangVersion>latest</LangVersion>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <AnalysisLevel>latest-recommended</AnalysisLevel>
    <EnforceCodeStyleInBuild>true</EnforceCodeStyleInBuild>
    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    <!-- Culture-invariant everywhere (the published tool sets it too), so messages and formatting are the same in tests. -->
    <InvariantGlobalization>true</InvariantGlobalization>
    <SatelliteResourceLanguages>en</SatelliteResourceLanguages>
    <Deterministic>true</Deterministic>
    <ContinuousIntegrationBuild Condition="'$(CI)' == 'true'">true</ContinuousIntegrationBuild>
    <!-- One SARIF analyzer log per project (obj/ exists after restore); a command-line ErrorLog still wins. -->
    <ErrorLog Condition="'$(ErrorLog)' == ''">$(MSBuildProjectDirectory)/obj/$(MSBuildProjectName).sarif,version=2.1</ErrorLog>
  </PropertyGroup>

  <ItemGroup>
    <AdditionalFiles Include="$(MSBuildThisFileDirectory)stylecop.json" Link="stylecop.json" />
  </ItemGroup>
</Project>
```

### `Directory.Packages.props`

```xml
<Project>
  <!-- Central package management: every PackageReference in the repository takes its version from here. -->
  <PropertyGroup>
    <ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally>
  </PropertyGroup>

  <ItemGroup>
    <GlobalPackageReference Include="StyleCop.Analyzers" Version="__StyleCopVersion__" />
  </ItemGroup>

  <ItemGroup>
    <PackageVersion Include="System.CommandLine" Version="__SystemCommandLineVersion__" />
    <PackageVersion Include="Spectre.Console" Version="__SpectreConsoleVersion__" />
    <!-- >>> ui: tui -->
    <PackageVersion Include="Terminal.Gui" Version="__TerminalGuiVersion__" />
    <!-- <<< ui: tui -->
    <PackageVersion Include="MSTest" Version="__MSTestVersion__" />
    <PackageVersion Include="coverlet.collector" Version="__CoverletVersion__" />
  </ItemGroup>
</Project>
```

### `.ui-quality.yml` (ui: tui)

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: __Accessibility__      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

The catalog's UI contract settings, exactly the contract's shape. For a terminal UI the `accessibility` level applies
to the asserted terminal checks (`tui-color-only`, `tui-help-line`, `tui-keyboard`) — no automated audit exists for
terminals —, `directions` drives the text captures (a `rtl` capture is right-to-left content in the normal layout),
and `themes` is kept for the shared shape (text captures are `no-color`).

### `docfx.json`

```json
{
  "$schema": "https://raw.githubusercontent.com/dotnet/docfx/main/schemas/docfx.schema.json",
  "metadata": [
    {
      "src": [
        {
          "src": "src/__ToolName__.Core",
          "files": [ "**/*.csproj" ]
        }
      ],
      "dest": "docfx/api"
    }
  ],
  "build": {
    "content": [
      {
        "src": "docfx",
        "files": [ "**/*.{md,yml}" ]
      }
    ],
    "output": "build/docfx",
    "template": [ "default", "modern" ],
    "globalMetadata": {
      "_appTitle": "__ToolName__",
      "_appName": "__ToolName__",
      "_enableSearch": true
    }
  }
}
```

### `docfx/index.md`

```markdown
# __ToolName__ API

API reference of `__ToolName__.Core`, the library behind the `__BinName__` command-line tool (exit codes, output
envelope, terminal policy, schema and completion generation). Generated by DocFX from the XML documentation comments.
The command-line reference itself is generated from `__BinName__ schema`.
```

### `docfx/toc.yml`

```yaml
- name: API
  href: api/
```

### `global.json`

```json
{
  "sdk": {
    "version": "__DotnetSdkVersion__",
    "rollForward": "latestFeature"
  }
}
```

### `stylecop.json`

```json
{
  "$schema": "https://raw.githubusercontent.com/DotNetAnalyzers/StyleCopAnalyzers/master/StyleCop.Analyzers/StyleCop.Analyzers/Settings/stylecop.schema.json",
  "settings": {
    "documentationRules": {
      "companyName": "__Company__",
      "xmlHeader": false,
      "documentExposedElements": true,
      "documentInterfaces": true,
      "documentInternalElements": false,
      "documentPrivateElements": false,
      "documentPrivateFields": false
    },
    "orderingRules": {
      "usingDirectivesPlacement": "outsideNamespace",
      "systemUsingDirectivesFirst": true
    },
    "layoutRules": {
      "newlineAtEndOfFile": "require"
    }
  }
}
```

### `tests/coverlet.runsettings`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Coverlet settings for the tests (passed to dotnet test with the settings option plus the "XPlat Code Coverage"
     collector). The 80% bar applies to __ToolName__.Core; the thin __BinName__ shell is exercised by the Cli tests
     but excluded from the measured total ("not measured"). -->
<RunSettings>
  <DataCollectionRunSettings>
    <DataCollectors>
      <DataCollector friendlyName="XPlat code coverage">
        <Configuration>
          <Format>cobertura,opencover</Format>
          <Include>[__ToolName__.Core]*</Include>
          <Exclude>[*.Tests]*</Exclude>
          <ExcludeByAttribute>Obsolete,GeneratedCodeAttribute,CompilerGeneratedAttribute,ExcludeFromCodeCoverageAttribute</ExcludeByAttribute>
          <ExcludeByFile>**/*.g.cs,**/*.g.i.cs</ExcludeByFile>
          <SkipAutoProps>true</SkipAutoProps>
        </Configuration>
      </DataCollector>
    </DataCollectors>
  </DataCollectionRunSettings>
</RunSettings>
```
