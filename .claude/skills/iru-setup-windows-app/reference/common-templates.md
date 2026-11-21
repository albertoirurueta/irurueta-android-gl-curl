# Common templates — every `ui`

Repository-level build files, the UI-free `src/__AppName__.Core` project with the greeting sample, its MSTest 4 test
project, the coverage run settings and the DocFX configuration. Written for every `ui` value.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`, `<!-- >>> <condition> -->` … in XML/XAML) and the
`__Token__` placeholders work as described in SKILL.md's "Reference files" section. A heading suffixed `(condition)`
is written only when the condition holds. Every file below was rendered and built on 2026-10-16 for `ui: wpf`
(mvvm), `ui: winforms` (mvc and mvvm) and `ui: winui` (mvvm) — see SKILL.md "Known quirks".

### `global.json`

```json
{
  "sdk": {
    "version": "__DotnetSdkVersion__",
    "rollForward": "latestFeature"
  }
}
```

### `Directory.Build.props`

```xml
<Project>
  <!-- Shared by every project in the repository. The app version lives here (and, numerically, in the MSIX manifest). -->
  <PropertyGroup>
    <Version>__Version__</Version>
    <Product>__DisplayName__</Product>
    <Company>__Company__</Company>
    <Authors>__Company__</Authors>
    <Copyright>Copyright (c) __Year__ __Company__</Copyright>
  </PropertyGroup>

  <PropertyGroup>
    <LangVersion>latest</LangVersion>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <AnalysisLevel>latest-recommended</AnalysisLevel>
    <EnforceCodeStyleInBuild>true</EnforceCodeStyleInBuild>
    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    <!-- Lets WPF / Windows Forms projects restore and compile on Linux and macOS (CI, dev machines). -->
    <EnableWindowsTargeting>true</EnableWindowsTargeting>
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
    <!-- >>> architecture: mvvm -->
    <PackageVersion Include="CommunityToolkit.Mvvm" Version="__MvvmToolkitVersion__" />
    <!-- <<< architecture: mvvm -->
    <PackageVersion Include="Microsoft.Extensions.Hosting" Version="__HostingVersion__" />
    <!-- >>> ui: winui -->
    <PackageVersion Include="Microsoft.WindowsAppSDK" Version="__WindowsAppSdkVersion__" />
    <PackageVersion Include="Microsoft.Windows.SDK.BuildTools" Version="__WindowsSdkBuildToolsVersion__" />
    <!-- <<< ui: winui -->
    <PackageVersion Include="MSTest" Version="__MSTestVersion__" />
    <PackageVersion Include="coverlet.collector" Version="__CoverletVersion__" />
    <!-- >>> flaui -->
    <PackageVersion Include="FlaUI.UIA3" Version="__FlaUIVersion__" />
    <PackageVersion Include="Axe.Windows" Version="__AxeWindowsVersion__" />
    <!-- <<< flaui -->
  </ItemGroup>
</Project>
```

### `.editorconfig`

```ini
root = true

[*]
charset = utf-8
indent_style = space
insert_final_newline = true
trim_trailing_whitespace = true

[*.{csproj,props,targets,slnx,xml,xaml,appxmanifest,manifest,runsettings,pubxml,resx,json,yml,yaml}]
indent_size = 2

[*.ps1]
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

### `__AppName__.slnx`

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
    <Project Path="src/__AppName__.Core/__AppName__.Core.csproj" />
    <Project Path="src/__AppName__/__AppName__.csproj" />
  </Folder>
  <Folder Name="/tests/">
    <Project Path="tests/__AppName__.Core.Tests/__AppName__.Core.Tests.csproj" />
    <!-- >>> flaui -->
    <Project Path="tests/__AppName__.UITests/__AppName__.UITests.csproj" />
    <!-- <<< flaui -->
  </Folder>
</Solution>
```

### `src/__AppName__.Core/__AppName__.Core.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <!-- UI-framework-free logic: models, services, view models (mvvm) or controllers + view interfaces (mvc).
       Plain net10.0, so it builds, tests and is covered on Linux, macOS and Windows. -->
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__RootNamespace__.Core</RootNamespace>
    <AssemblyName>__AppName__.Core</AssemblyName>
    <IsAotCompatible>true</IsAotCompatible>
  </PropertyGroup>

  <!-- >>> architecture: mvvm -->
  <ItemGroup>
    <PackageReference Include="CommunityToolkit.Mvvm" />
  </ItemGroup>

  <!-- <<< architecture: mvvm -->
  <ItemGroup>
    <InternalsVisibleTo Include="__AppName__.Core.Tests" />
  </ItemGroup>
</Project>
```

### `src/__AppName__.Core/Services/IGreetingService.cs`

```csharp
namespace __RootNamespace__.Core.Services;

/// <summary>
/// Builds the greeting shown to the user.
/// </summary>
public interface IGreetingService
{
    /// <summary>
    /// Creates a greeting for <paramref name="name"/>.
    /// </summary>
    /// <param name="name">The name to greet; a blank or missing name produces a generic greeting.</param>
    /// <returns>The greeting message.</returns>
    string Greet(string? name);
}
```

### `src/__AppName__.Core/Services/GreetingService.cs`

```csharp
namespace __RootNamespace__.Core.Services;

/// <summary>
/// Default <see cref="IGreetingService"/>: greets the trimmed name, or the world when no name is given.
/// </summary>
public sealed class GreetingService : IGreetingService
{
    /// <inheritdoc/>
    public string Greet(string? name) =>
        string.IsNullOrWhiteSpace(name) ? "Hello, world!" : $"Hello, {name.Trim()}!";
}
```

### `src/__AppName__.Core/ViewModels/MainViewModel.cs` (architecture: mvvm)

```csharp
using __RootNamespace__.Core.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace __RootNamespace__.Core.ViewModels;

/// <summary>
/// View model of the main window: greets the name the user types.
/// </summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly IGreetingService _greetingService;

    /// <summary>
    /// Initializes a new instance of the <see cref="MainViewModel"/> class.
    /// </summary>
    /// <param name="greetingService">Builds the greeting message.</param>
    public MainViewModel(IGreetingService greetingService)
    {
        ArgumentNullException.ThrowIfNull(greetingService);
        _greetingService = greetingService;
        Name = string.Empty;
        Message = string.Empty;
    }

    /// <summary>
    /// Gets or sets the name typed by the user.
    /// </summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(GreetCommand))]
    public partial string Name { get; set; }

    /// <summary>
    /// Gets or sets the greeting shown to the user.
    /// </summary>
    [ObservableProperty]
    public partial string Message { get; set; }

    [RelayCommand(CanExecute = nameof(CanGreet))]
    private void Greet() => Message = _greetingService.Greet(Name);

    private bool CanGreet() => !string.IsNullOrWhiteSpace(Name);
}
```

### `src/__AppName__.Core/Models/GreetingModel.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Services;

namespace __RootNamespace__.Core.Models;

/// <summary>
/// Model of the greeting feature: holds the current message and raises <see cref="Changed"/> when it changes.
/// </summary>
public sealed class GreetingModel
{
    private readonly IGreetingService _greetingService;

    /// <summary>
    /// Initializes a new instance of the <see cref="GreetingModel"/> class.
    /// </summary>
    /// <param name="greetingService">Builds the greeting message.</param>
    public GreetingModel(IGreetingService greetingService)
    {
        ArgumentNullException.ThrowIfNull(greetingService);
        _greetingService = greetingService;
    }

    /// <summary>
    /// Raised after <see cref="Message"/> changes.
    /// </summary>
    public event EventHandler? Changed;

    /// <summary>
    /// Gets the current greeting message.
    /// </summary>
    public string Message { get; private set; } = string.Empty;

    /// <summary>
    /// Greets <paramref name="name"/> and notifies listeners.
    /// </summary>
    /// <param name="name">The name to greet.</param>
    public void Greet(string? name)
    {
        Message = _greetingService.Greet(name);
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
```

### `src/__AppName__.Core/Views/IMainView.cs` (architecture: mvc)

```csharp
namespace __RootNamespace__.Core.Views;

/// <summary>
/// Passive view of the main window. The form implements it; <see cref="Controllers.MainController"/> drives it, so
/// the controller is unit-tested against a fake view without any UI framework.
/// </summary>
public interface IMainView
{
    /// <summary>
    /// Raised when the user asks for a greeting.
    /// </summary>
    event EventHandler? GreetRequested;

    /// <summary>
    /// Raised when the text of the name input changes.
    /// </summary>
    event EventHandler? NameInputChanged;

    /// <summary>
    /// Gets the name currently typed by the user.
    /// </summary>
    string NameInput { get; }

    /// <summary>
    /// Shows <paramref name="message"/> to the user.
    /// </summary>
    /// <param name="message">The message to show.</param>
    void ShowMessage(string message);

    /// <summary>
    /// Enables or disables the greet action.
    /// </summary>
    /// <param name="enabled"><see langword="true"/> to enable the action.</param>
    void SetGreetEnabled(bool enabled);
}
```

### `src/__AppName__.Core/Controllers/MainController.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Models;
using __RootNamespace__.Core.Views;

namespace __RootNamespace__.Core.Controllers;

/// <summary>
/// Controller of the main window: handles the view's events, updates the model and pushes model changes back to
/// the view.
/// </summary>
public sealed class MainController : IDisposable
{
    private readonly IMainView _view;
    private readonly GreetingModel _model;
    private bool _disposed;

    /// <summary>
    /// Initializes a new instance of the <see cref="MainController"/> class and starts listening to
    /// <paramref name="view"/> and <paramref name="model"/>.
    /// </summary>
    /// <param name="view">The view to drive.</param>
    /// <param name="model">The greeting model.</param>
    public MainController(IMainView view, GreetingModel model)
    {
        ArgumentNullException.ThrowIfNull(view);
        ArgumentNullException.ThrowIfNull(model);
        _view = view;
        _model = model;
        _view.GreetRequested += OnGreetRequested;
        _view.NameInputChanged += OnNameInputChanged;
        _model.Changed += OnModelChanged;
        _view.SetGreetEnabled(CanGreet());
    }

    /// <summary>
    /// Stops listening to the view and the model.
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _view.GreetRequested -= OnGreetRequested;
        _view.NameInputChanged -= OnNameInputChanged;
        _model.Changed -= OnModelChanged;
        _disposed = true;
    }

    private bool CanGreet() => !string.IsNullOrWhiteSpace(_view.NameInput);

    private void OnGreetRequested(object? sender, EventArgs e)
    {
        if (CanGreet())
        {
            _model.Greet(_view.NameInput);
        }
    }

    private void OnNameInputChanged(object? sender, EventArgs e) => _view.SetGreetEnabled(CanGreet());

    private void OnModelChanged(object? sender, EventArgs e) => _view.ShowMessage(_model.Message);
}
```

### `.ui-quality.yml`

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: __Accessibility__      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

The catalog's UI contract settings, read by the UI tests (`UiQuality.cs`) and the coding/test skills — exactly the
contract's shape. Written for every `ui` (also with `ui-tests: none`, so UI tests added later pick it up);
`__Accessibility__` is Step 0's `accessibility` (default `enforce`).

### `src/__AppName__/UiTestOptions.cs`

```csharp
namespace __RootNamespace__;

/// <summary>
/// Launch arguments the UI tests pass to render the app in a fixed layout direction and theme
/// (<c>--ui-direction=ltr|rtl</c>, <c>--ui-theme=light|dark</c>); without them the app follows the system.
/// </summary>
internal static class UiTestOptions
{
    /// <summary>Gets a value indicating whether <c>--ui-direction=rtl</c> was passed.</summary>
    public static bool IsRightToLeft { get; } = Value("--ui-direction=") == "rtl";

    /// <summary>Gets the <c>--ui-theme=</c> value (<c>light</c> or <c>dark</c>), or <see langword="null"/>.</summary>
    public static string? Theme { get; } = Value("--ui-theme=");

    private static string? Value(string prefix) =>
        Environment.GetCommandLineArgs().Skip(1)
            .FirstOrDefault(argument => argument.StartsWith(prefix, StringComparison.Ordinal))?[prefix.Length..];
}
```

### `src/__AppName__/app.manifest`

```xml
<?xml version="1.0" encoding="utf-8"?>
<assembly manifestVersion="1.0" xmlns="urn:schemas-microsoft-com:asm.v1">
  <assemblyIdentity version="1.0.0.0" name="__AppName__.app" />

  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v2">
    <security>
      <requestedPrivileges xmlns="urn:schemas-microsoft-com:asm.v3">
        <!-- Least privilege: never requireAdministrator unless the app truly needs it. -->
        <requestedExecutionLevel level="asInvoker" uiAccess="false" />
      </requestedPrivileges>
    </security>
  </trustInfo>

  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <!-- Windows 10 and Windows 11. -->
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}" />
    </application>
  </compatibility>
  <!-- >>> ui: wpf|winui -->

  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2, PerMonitor</dpiAwareness>
    </windowsSettings>
  </application>
  <!-- <<< ui: wpf|winui -->
  <!-- >>> ui: winforms -->
  <!-- DPI awareness is set by ApplicationHighDpiMode (PerMonitorV2) in the project file, not here (WFAC010). -->
  <!-- <<< ui: winforms -->
</assembly>
```

### `tests/__AppName__.Core.Tests/__AppName__.Core.Tests.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>__RootNamespace__.Core.Tests</RootNamespace>
    <IsPackable>false</IsPackable>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="MSTest" />
    <PackageReference Include="coverlet.collector" />
  </ItemGroup>

  <ItemGroup>
    <Using Include="Microsoft.VisualStudio.TestTools.UnitTesting" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\..\src\__AppName__.Core\__AppName__.Core.csproj" />
  </ItemGroup>
</Project>
```

### `tests/__AppName__.Core.Tests/MSTestSettings.cs`

```csharp
[assembly: Parallelize(Scope = ExecutionScope.MethodLevel)]
```

### `tests/__AppName__.Core.Tests/Services/GreetingServiceTests.cs`

```csharp
using __RootNamespace__.Core.Services;

namespace __RootNamespace__.Core.Tests.Services;

[TestClass]
public sealed class GreetingServiceTests
{
    [TestMethod]
    [DataRow("Ada", "Hello, Ada!")]
    [DataRow("  Grace  ", "Hello, Grace!")]
    public void Greet_WithName_GreetsTrimmedName(string name, string expected)
    {
        var service = new GreetingService();

        Assert.AreEqual(expected, service.Greet(name));
    }

    [TestMethod]
    [DataRow(null)]
    [DataRow("")]
    [DataRow("   ")]
    public void Greet_WithoutName_GreetsTheWorld(string? name)
    {
        var service = new GreetingService();

        Assert.AreEqual("Hello, world!", service.Greet(name));
    }
}
```

### `tests/__AppName__.Core.Tests/Fakes/FakeGreetingService.cs`

```csharp
using __RootNamespace__.Core.Services;

namespace __RootNamespace__.Core.Tests.Fakes;

internal sealed class FakeGreetingService : IGreetingService
{
    public List<string?> Names { get; } = [];

    public string Greet(string? name)
    {
        Names.Add(name);
        return $"greeting for {name}";
    }
}
```

### `tests/__AppName__.Core.Tests/ViewModels/MainViewModelTests.cs` (architecture: mvvm)

```csharp
using System.ComponentModel;
using __RootNamespace__.Core.Tests.Fakes;
using __RootNamespace__.Core.ViewModels;

namespace __RootNamespace__.Core.Tests.ViewModels;

[TestClass]
public sealed class MainViewModelTests
{
    [TestMethod]
    public void Constructor_WithoutService_Throws()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => new MainViewModel(null!));
    }

    [TestMethod]
    public void Constructor_StartsEmpty_AndCannotGreet()
    {
        var viewModel = new MainViewModel(new FakeGreetingService());

        Assert.AreEqual(string.Empty, viewModel.Name);
        Assert.AreEqual(string.Empty, viewModel.Message);
        Assert.IsFalse(viewModel.GreetCommand.CanExecute(null));
    }

    [TestMethod]
    public void Name_WhenSet_RaisesPropertyChanged_AndRefreshesGreetCommand()
    {
        var viewModel = new MainViewModel(new FakeGreetingService());
        var changed = new List<string?>();
        var canExecuteChanges = 0;
        viewModel.PropertyChanged += (_, e) => changed.Add(e.PropertyName);
        viewModel.GreetCommand.CanExecuteChanged += (_, _) => canExecuteChanges++;

        viewModel.Name = "Ada";

        Assert.Contains(nameof(MainViewModel.Name), changed);
        Assert.AreEqual(1, canExecuteChanges);
        Assert.IsTrue(viewModel.GreetCommand.CanExecute(null));
    }

    [TestMethod]
    public void GreetCommand_SetsMessageFromService()
    {
        var service = new FakeGreetingService();
        var viewModel = new MainViewModel(service) { Name = "Ada" };
        PropertyChangedEventArgs? messageChange = null;
        viewModel.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainViewModel.Message))
            {
                messageChange = e;
            }
        };

        viewModel.GreetCommand.Execute(null);

        Assert.AreEqual("greeting for Ada", viewModel.Message);
        Assert.IsNotNull(messageChange);
        Assert.AreEqual("Ada", Assert.ContainsSingle(service.Names));
    }
}
```

### `tests/__AppName__.Core.Tests/Models/GreetingModelTests.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Models;
using __RootNamespace__.Core.Tests.Fakes;

namespace __RootNamespace__.Core.Tests.Models;

[TestClass]
public sealed class GreetingModelTests
{
    [TestMethod]
    public void Constructor_WithoutService_Throws()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => new GreetingModel(null!));
    }

    [TestMethod]
    public void Greet_UpdatesMessage_AndRaisesChanged()
    {
        var model = new GreetingModel(new FakeGreetingService());
        var changes = 0;
        model.Changed += (_, _) => changes++;

        model.Greet("Ada");

        Assert.AreEqual("greeting for Ada", model.Message);
        Assert.AreEqual(1, changes);
    }
}
```

### `tests/__AppName__.Core.Tests/Fakes/FakeMainView.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Views;

namespace __RootNamespace__.Core.Tests.Fakes;

internal sealed class FakeMainView : IMainView
{
    public event EventHandler? GreetRequested;

    public event EventHandler? NameInputChanged;

    public string NameInput { get; private set; } = string.Empty;

    public List<string> Messages { get; } = [];

    public bool? GreetEnabled { get; private set; }

    public bool HasListeners => GreetRequested is not null || NameInputChanged is not null;

    public void ShowMessage(string message) => Messages.Add(message);

    public void SetGreetEnabled(bool enabled) => GreetEnabled = enabled;

    public void Type(string text)
    {
        NameInput = text;
        NameInputChanged?.Invoke(this, EventArgs.Empty);
    }

    public void ClickGreet() => GreetRequested?.Invoke(this, EventArgs.Empty);
}
```

### `tests/__AppName__.Core.Tests/Controllers/MainControllerTests.cs` (architecture: mvc)

```csharp
using __RootNamespace__.Core.Controllers;
using __RootNamespace__.Core.Models;
using __RootNamespace__.Core.Tests.Fakes;

namespace __RootNamespace__.Core.Tests.Controllers;

[TestClass]
public sealed class MainControllerTests
{
    private readonly FakeMainView _view = new();
    private readonly GreetingModel _model = new(new FakeGreetingService());

    [TestMethod]
    public void Constructor_WithoutArguments_Throws()
    {
        Assert.ThrowsExactly<ArgumentNullException>(() => new MainController(null!, _model));
        Assert.ThrowsExactly<ArgumentNullException>(() => new MainController(_view, null!));
    }

    [TestMethod]
    public void Constructor_DisablesGreet_WhileNameIsEmpty()
    {
        using var controller = new MainController(_view, _model);

        Assert.IsFalse(_view.GreetEnabled);
    }

    [TestMethod]
    public void TypingAName_EnablesGreet()
    {
        using var controller = new MainController(_view, _model);

        _view.Type("Ada");

        Assert.IsTrue(_view.GreetEnabled);
    }

    [TestMethod]
    public void ClickingGreet_ShowsTheModelMessage()
    {
        using var controller = new MainController(_view, _model);
        _view.Type("Ada");

        _view.ClickGreet();

        Assert.AreEqual("greeting for Ada", Assert.ContainsSingle(_view.Messages));
    }

    [TestMethod]
    public void ClickingGreet_WithBlankName_DoesNothing()
    {
        using var controller = new MainController(_view, _model);
        _view.Type("   ");

        _view.ClickGreet();

        Assert.IsEmpty(_view.Messages);
    }

    [TestMethod]
    public void Dispose_StopsListening_AndIsIdempotent()
    {
        var controller = new MainController(_view, _model);

        controller.Dispose();
        controller.Dispose();
        _model.Greet("Ada");

        Assert.IsFalse(_view.HasListeners);
        Assert.IsEmpty(_view.Messages);
    }
}
```

### `tests/coverlet.runsettings`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Coverlet settings for the Core tests (passed to dotnet test with the settings option plus the
     "XPlat Code Coverage" collector). The UI app assembly (__AppName__) is excluded: XAML/forms code is "not measured", never counted as 0%. -->
<RunSettings>
  <DataCollectionRunSettings>
    <DataCollectors>
      <DataCollector friendlyName="XPlat code coverage">
        <Configuration>
          <Format>cobertura,opencover</Format>
          <Exclude>[__AppName__]*,[*.Tests]*,[*.UITests]*</Exclude>
          <ExcludeByAttribute>Obsolete,GeneratedCodeAttribute,CompilerGeneratedAttribute,ExcludeFromCodeCoverageAttribute</ExcludeByAttribute>
          <ExcludeByFile>**/*.g.cs,**/*.g.i.cs,**/*.Designer.cs</ExcludeByFile>
          <SkipAutoProps>true</SkipAutoProps>
        </Configuration>
      </DataCollector>
    </DataCollectors>
  </DataCollectionRunSettings>
</RunSettings>
```

### `docfx.json`

```json
{
  "$schema": "https://raw.githubusercontent.com/dotnet/docfx/main/schemas/docfx.schema.json",
  "metadata": [
    {
      "src": [
        {
          "src": "src/__AppName__.Core",
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
      "_appTitle": "__DisplayName__",
      "_appName": "__DisplayName__",
      "_enableSearch": true
    }
  }
}
```

### `docfx/index.md`

```markdown
# __DisplayName__ API

API reference of `__AppName__.Core`, the UI-free layer of __DisplayName__ (models, services and
the presentation logic the Windows UI binds to). Generated by DocFX from the XML documentation comments.
```

### `docfx/toc.yml`

```yaml
- name: API
  href: api/
```
