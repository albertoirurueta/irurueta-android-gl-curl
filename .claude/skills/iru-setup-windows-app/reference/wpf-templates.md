# WPF templates — `ui: wpf` (architecture `mvvm` only)

The `src/__AppName__` WPF app project: Generic Host composition root in `App.xaml.cs` (no `StartupUri`), a
`MainWindow` bound with `{Binding}` to `Core`'s `MainViewModel` through its `DataContext`, and the Fluent theme
following the system light/dark setting. WPF has no `x:Bind`; `{Binding}` + `DataContext` is the WPF form (there
is no Microsoft Learn MVVM article specific to WPF — the pattern follows the MVVM Toolkit docs and the
"Enterprise Application Patterns Using .NET MAUI" chapters).

Markers and `__Token__` placeholders as described in SKILL.md. Rendered, compiled (`EnableWindowsTargeting`,
XAML markup compile included) and analyzer-clean on macOS on 2026-10-16; running the window is unverified locally.

### `src/__AppName__/__AppName__.csproj`

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <UseWPF>true</UseWPF>
    <RootNamespace>__RootNamespace__</RootNamespace>
    <AssemblyName>__AppName__</AssemblyName>
    <ApplicationManifest>app.manifest</ApplicationManifest>
    <ApplicationIcon>Assets\AppIcon.ico</ApplicationIcon>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.Extensions.Hosting" />
  </ItemGroup>

  <ItemGroup>
    <ProjectReference Include="..\__AppName__.Core\__AppName__.Core.csproj" />
  </ItemGroup>
</Project>
```

### `src/__AppName__/AssemblyInfo.cs`

```csharp
using System.Windows;

[assembly: ThemeInfo(
    ResourceDictionaryLocation.None, // theme-specific resource dictionaries
    ResourceDictionaryLocation.SourceAssembly)] // generic resource dictionary (Themes/Generic.xaml)
```

### `src/__AppName__/App.xaml`

```xml
<Application x:Class="__RootNamespace__.App"
             xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
             xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
             ThemeMode="System">
    <!-- No StartupUri: App.xaml.cs resolves MainWindow from the Generic Host. -->
    <Application.Resources />
</Application>
```

### `src/__AppName__/App.xaml.cs`

```csharp
using System.Windows;
using __RootNamespace__.Core.Services;
using __RootNamespace__.Core.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace __RootNamespace__;

/// <summary>
/// Composition root: builds the Generic Host (dependency injection, configuration, logging) and shows the main
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
        var builder = Host.CreateApplicationBuilder(
            new HostApplicationBuilderSettings { ContentRootPath = AppContext.BaseDirectory });
        builder.Services.AddSingleton<IGreetingService, GreetingService>();
        builder.Services.AddTransient<MainViewModel>();
        builder.Services.AddSingleton<MainWindow>();
        _host = builder.Build();
    }

    /// <inheritdoc/>
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _host.Start();
        ApplyUiTestTheme();
        var window = _host.Services.GetRequiredService<MainWindow>();
        window.FlowDirection = UiTestOptions.IsRightToLeft ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        window.Show();
    }

    /// <inheritdoc/>
    protected override void OnExit(ExitEventArgs e)
    {
        // Synchronous on purpose: after OnExit returns the dispatcher stops, so an awaited continuation would never
        // run. Hosting's StopAsync does not capture the UI context, so blocking here cannot deadlock.
        using (_host)
        {
            _host.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        }

        base.OnExit(e);
    }

    private void ApplyUiTestTheme()
    {
        // Setting ThemeMode from code is experimental (WPF0001); it runs only when the UI tests pass --ui-theme.
#pragma warning disable WPF0001
        ThemeMode = UiTestOptions.Theme switch
        {
            "light" => ThemeMode.Light,
            "dark" => ThemeMode.Dark,
            _ => ThemeMode,
        };
#pragma warning restore WPF0001
    }
}
```

### `src/__AppName__/MainWindow.xaml`

```xml
<Window x:Class="__RootNamespace__.MainWindow"
        xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        xmlns:d="http://schemas.microsoft.com/expression/blend/2008"
        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
        xmlns:vm="clr-namespace:__RootNamespace__.Core.ViewModels;assembly=__AppName__.Core"
        mc:Ignorable="d"
        d:DataContext="{d:DesignInstance Type=vm:MainViewModel}"
        Title="__DisplayName__"
        Width="420"
        SizeToContent="Height"
        WindowStartupLocation="CenterScreen">
    <StackPanel Margin="16">
        <Label Content="_Name" Target="{Binding ElementName=NameTextBox}" Padding="0,0,0,4" />
        <TextBox x:Name="NameTextBox"
                 AutomationProperties.AutomationId="nameTextBox"
                 Text="{Binding Name, UpdateSourceTrigger=PropertyChanged}" />
        <Button AutomationProperties.AutomationId="greetButton"
                Content="_Greet"
                Command="{Binding GreetCommand}"
                IsDefault="True"
                HorizontalAlignment="Left"
                Margin="0,8,0,0"
                Padding="16,4" />
        <TextBlock AutomationProperties.AutomationId="messageLabel"
                   Text="{Binding Message}"
                   Margin="0,12,0,0" />
    </StackPanel>
</Window>
```

### `src/__AppName__/MainWindow.xaml.cs`

```csharp
using System.Windows;
using __RootNamespace__.Core.ViewModels;

namespace __RootNamespace__;

/// <summary>
/// Main window. Code-behind only wires the view model; all behavior lives in <see cref="MainViewModel"/>.
/// </summary>
public partial class MainWindow : Window
{
    /// <summary>
    /// Initializes a new instance of the <see cref="MainWindow"/> class.
    /// </summary>
    /// <param name="viewModel">The view model the window binds to.</param>
    public MainWindow(MainViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        InitializeComponent();
        DataContext = viewModel;
    }
}
```
