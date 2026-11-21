# WinUI 3 templates — `ui: winui` (architecture `mvvm` only)

The `src/__AppName__` WinUI 3 / Windows App SDK 2.x project: single-project MSIX (`EnableMsixTooling`,
`Package.appxmanifest` — WinUI is always packaged by this scaffold), Generic Host composition root in
`App.xaml.cs`, a `MainWindow` with Mica and compiled `x:Bind` bindings to `Core`'s `MainViewModel` (`x:Bind` is
strongly typed, resolves against the window's code-behind and defaults to `OneTime` — every binding that must
update says `Mode=OneWay`/`TwoWay`), per-architecture publish profiles, and the MSIX visual assets generated at
scaffold time (see `packaging-templates.md`).

Grounded in Microsoft's current WinUI CLI template (`Microsoft.WindowsAppSDK.WinUI.CSharp.Templates`
`winui.mvvmapp`, Windows App SDK 2.x) with these deliberate departures: no x86 platform (the `rids` default is
`win-x64;win-arm64`), no `mp:PhoneIdentity`/`systemAIModels` capability, `PublishTrimmed` left off (trim warnings
would break the warnings-as-errors build; opt in deliberately — WinUI supports trimming/Native AOT, WPF/WinForms do
not), and the `Microsoft.Windows.SDK.BuildTools.WinApp` `dotnet run` helper not added. Markers and `__Token__`
placeholders as described in SKILL.md.

**Unverified locally**: a WinUI build needs Windows (the XAML compiler and MSIX tooling are Windows executables) —
see SKILL.md "Known quirks" for exactly how it fails on macOS/Linux.

### `src/__AppName__/__AppName__.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows10.0.26100.0</TargetFramework>
    <TargetPlatformMinVersion>10.0.17763.0</TargetPlatformMinVersion>
    <RootNamespace>__RootNamespace__</RootNamespace>
    <AssemblyName>__AppName__</AssemblyName>
    <ApplicationManifest>app.manifest</ApplicationManifest>
    <ApplicationIcon>Assets\AppIcon.ico</ApplicationIcon>
    <Platforms>x64;ARM64</Platforms>
    <RuntimeIdentifiers>__Rids__</RuntimeIdentifiers>
    <RuntimeIdentifier Condition="'$(RuntimeIdentifier)' == ''">win-$([System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString().ToLowerInvariant())</RuntimeIdentifier>
    <PublishProfile Condition="Exists('Properties\PublishProfiles\win-$(Platform).pubxml')">win-$(Platform).pubxml</PublishProfile>
    <UseWinUI>true</UseWinUI>
    <EnableMsixTooling>true</EnableMsixTooling>
  </PropertyGroup>

  <ItemGroup>
    <Content Include="Assets\*.png" />
    <Content Include="Assets\AppIcon.ico" />
    <Manifest Include="$(ApplicationManifest)" />
  </ItemGroup>

  <!-- Lets the single-project MSIX tooling light up before the Windows App SDK package is restored. -->
  <ItemGroup Condition="'$(DisableMsixProjectCapabilityAddedByProject)' != 'true' and '$(EnableMsixTooling)' == 'true'">
    <ProjectCapability Include="Msix" />
  </ItemGroup>

  <PropertyGroup Condition="'$(DisableHasPackageAndPublishMenuAddedByProject)' != 'true' and '$(EnableMsixTooling)' == 'true'">
    <HasPackageAndPublishMenu>true</HasPackageAndPublishMenu>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.WindowsAppSDK" />
    <PackageReference Include="Microsoft.Windows.SDK.BuildTools" />
    <PackageReference Include="Microsoft.Extensions.Hosting" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\__AppName__.Core\__AppName__.Core.csproj" />
  </ItemGroup>

  <PropertyGroup>
    <PublishReadyToRun Condition="'$(Configuration)' != 'Debug'">true</PublishReadyToRun>
  </PropertyGroup>
</Project>
```

### `src/__AppName__/Package.appxmanifest`

```xml
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">

  <!-- Name/Publisher must match the Partner Center reservation (Store) or the signing certificate subject. Version is
       the four-part numeric form of Directory.Build.props' Version (revision 0 — the Store requires it). -->
  <Identity
    Name="__PackageName__"
    Publisher="__Publisher__"
    Version="__MsixVersion__" />

  <Properties>
    <DisplayName>__DisplayName__</DisplayName>
    <PublisherDisplayName>__PublisherDisplayName__</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>

  <Dependencies>
    <TargetDeviceFamily Name="Windows.Universal" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>

  <Resources>
    <Resource Language="x-generate" />
  </Resources>

  <Applications>
    <!-- $targetnametoken$ / $targetentrypoint$ are filled in by the MSIX build tooling; keep them literal. -->
    <Application Id="App"
      Executable="$targetnametoken$.exe"
      EntryPoint="$targetentrypoint$">
      <uap:VisualElements
        DisplayName="__DisplayName__"
        Description="__DisplayName__"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\Square150x150Logo.png"
        Square44x44Logo="Assets\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" />
        <uap:SplashScreen Image="Assets\SplashScreen.png" />
      </uap:VisualElements>
    </Application>
  </Applications>

  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
```

### `src/__AppName__/App.xaml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<Application
    x:Class="__RootNamespace__.App"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Application.Resources>
        <ResourceDictionary>
            <ResourceDictionary.MergedDictionaries>
                <XamlControlsResources xmlns="using:Microsoft.UI.Xaml.Controls" />
            </ResourceDictionary.MergedDictionaries>
        </ResourceDictionary>
    </Application.Resources>
</Application>
```

### `src/__AppName__/App.xaml.cs`

```csharp
using __RootNamespace__.Core.Services;
using __RootNamespace__.Core.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.UI.Xaml;

namespace __RootNamespace__;

/// <summary>
/// Composition root: builds the Generic Host (dependency injection, configuration, logging) and activates the main
/// window resolved from it.
/// </summary>
public partial class App : Application
{
    private readonly IHost _host;

    /// <summary>
    /// Initializes a new instance of the <see cref="App"/> class and registers the app's services.
    /// </summary>
    public App()
    {
        InitializeComponent();
        var builder = Host.CreateApplicationBuilder(
            new HostApplicationBuilderSettings { ContentRootPath = AppContext.BaseDirectory });
        builder.Services.AddSingleton<IGreetingService, GreetingService>();
        builder.Services.AddTransient<MainViewModel>();
        builder.Services.AddSingleton<MainWindow>();
        _host = builder.Build();
    }

    /// <inheritdoc/>
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _host.Start();
        var window = _host.Services.GetRequiredService<MainWindow>();
        window.Closed += OnMainWindowClosed;
        if (window.Content is FrameworkElement root)
        {
            // The UI tests pass --ui-direction / --ui-theme (UiTestOptions); without them the system settings apply.
            root.FlowDirection = UiTestOptions.IsRightToLeft ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            root.RequestedTheme = UiTestOptions.Theme switch
            {
                "light" => ElementTheme.Light,
                "dark" => ElementTheme.Dark,
                _ => ElementTheme.Default,
            };
        }

        window.Activate();
    }

    private void OnMainWindowClosed(object sender, WindowEventArgs args)
    {
        using (_host)
        {
            _host.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        }
    }
}
```

### `src/__AppName__/MainWindow.xaml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<Window
    x:Class="__RootNamespace__.MainWindow"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
    Title="__DisplayName__">
    <Window.SystemBackdrop>
        <MicaBackdrop />
    </Window.SystemBackdrop>

    <StackPanel Margin="24" Spacing="8" MaxWidth="420" HorizontalAlignment="Left">
        <TextBox AutomationProperties.AutomationId="nameTextBox"
                 Header="Name"
                 Text="{x:Bind ViewModel.Name, Mode=TwoWay, UpdateSourceTrigger=PropertyChanged}" />
        <Button AutomationProperties.AutomationId="greetButton"
                Content="Greet"
                Command="{x:Bind ViewModel.GreetCommand}"
                Style="{StaticResource AccentButtonStyle}" />
        <TextBlock AutomationProperties.AutomationId="messageLabel"
                   Text="{x:Bind ViewModel.Message, Mode=OneWay}" />
    </StackPanel>
</Window>
```

### `src/__AppName__/MainWindow.xaml.cs`

```csharp
using __RootNamespace__.Core.ViewModels;
using Microsoft.UI.Xaml;

namespace __RootNamespace__;

/// <summary>
/// Main window. Exposes <see cref="ViewModel"/> for the compiled <c>x:Bind</c> bindings; all behavior lives in
/// <see cref="MainViewModel"/>.
/// </summary>
public sealed partial class MainWindow : Window
{
    /// <summary>
    /// Initializes a new instance of the <see cref="MainWindow"/> class.
    /// </summary>
    /// <param name="viewModel">The view model the window binds to.</param>
    public MainWindow(MainViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        ViewModel = viewModel;
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(480, 320));
    }

    /// <summary>
    /// Gets the view model the window binds to.
    /// </summary>
    public MainViewModel ViewModel { get; }
}
```

### `src/__AppName__/Properties/launchSettings.json`

```json
{
  "profiles": {
    "__AppName__ (Package)": {
      "commandName": "MsixPackage"
    },
    "__AppName__ (Unpackaged)": {
      "commandName": "Project"
    }
  }
}
```

### `src/__AppName__/Properties/PublishProfiles/win-x64.pubxml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<Project ToolsVersion="4.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
  <PropertyGroup>
    <PublishProtocol>FileSystem</PublishProtocol>
    <Platform>x64</Platform>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
    <PublishDir>bin\$(Configuration)\$(TargetFramework)\$(RuntimeIdentifier)\publish\</PublishDir>
    <SelfContained>true</SelfContained>
    <PublishSingleFile>false</PublishSingleFile>
  </PropertyGroup>
</Project>
```

### `src/__AppName__/Properties/PublishProfiles/win-arm64.pubxml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<Project ToolsVersion="4.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
  <PropertyGroup>
    <PublishProtocol>FileSystem</PublishProtocol>
    <Platform>ARM64</Platform>
    <RuntimeIdentifier>win-arm64</RuntimeIdentifier>
    <PublishDir>bin\$(Configuration)\$(TargetFramework)\$(RuntimeIdentifier)\publish\</PublishDir>
    <SelfContained>true</SelfContained>
    <PublishSingleFile>false</PublishSingleFile>
  </PropertyGroup>
</Project>
```
