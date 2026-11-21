# WPF (`ui: wpf`)

WPF on .NET 10: `net10.0-windows`, `<UseWPF>true</UseWPF>`, `<OutputType>WinExe</OutputType>`. XAML compiles to BAML
plus a generated `*.g.cs` partial (`InitializeComponent`, named fields) — a markup typo fails the build (the markup
compile pass runs even on Linux/macOS with `EnableWindowsTargeting`), but **binding paths are resolved at run time**:
a misspelled `{Binding Nmae}` builds fine and silently shows nothing. WPF has **no `x:Bind`** — that's WinUI.

## Application and windows

- `App.xaml` declares resources and **no `StartupUri`** — the host-built `MainWindow` is shown from `App.xaml.cs`
  (`architecture.md`). `ShutdownMode` stays the default (`OnLastWindowClose`) unless the task says otherwise.
- A window's constructor takes its view model by injection and sets `DataContext = viewModel;` after
  `InitializeComponent()`. Child views (`UserControl`s) inherit the `DataContext`, or get theirs from an implicit
  `DataTemplate` (`<DataTemplate DataType="{x:Type vm:BookDetailsViewModel}">`) in a `ContentControl`.
- Dialogs: owned and modal (`dialog.Owner = Application.Current.MainWindow; dialog.ShowDialog()`), opened by an
  app-project `IDialogService` implementation, never by a view model. `MessageBox`, `OpenFileDialog`,
  `SaveFileDialog`, `OpenFolderDialog` (.NET 8+) likewise live only in the app project.

## Binding

```xml
<Window x:Class="Bookshelf.MainWindow"
        xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        xmlns:d="http://schemas.microsoft.com/expression/blend/2008"
        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
        xmlns:vm="clr-namespace:Bookshelf.Core.ViewModels;assembly=Bookshelf.Core"
        mc:Ignorable="d"
        d:DataContext="{d:DesignInstance Type=vm:MainViewModel}"
        Title="Bookshelf" Width="480" Height="320">
    <StackPanel Margin="12">
        <TextBox AutomationProperties.AutomationId="NameInput"
                 Text="{Binding Name, UpdateSourceTrigger=PropertyChanged}" />
        <Button AutomationProperties.AutomationId="GreetButton" Content="Greet" Command="{Binding GreetCommand}" />
        <TextBlock AutomationProperties.AutomationId="Message" Text="{Binding Message}" />
    </StackPanel>
</Window>
```

- `d:DataContext` with `d:DesignInstance` gives the designer and the XAML language service the type — keep it on new
  views; it is ignored at run time.
- `UpdateSourceTrigger=PropertyChanged` on text inputs that drive `CanExecute` (the default for `TextBox.Text` is
  `LostFocus`). `Mode=TwoWay` is already the default for editable properties; `OneWay` for read-only display.
- Commands bind to the generated `…Command` properties; `CommandParameter` for item actions. An event with no
  `Command` property (e.g. `Loaded`, `SelectionChanged`) → XAML Behaviors' `InvokeCommandAction`
  (`Microsoft.Xaml.Behaviors.Wpf`, only if the project references it — otherwise a one-line code-behind handler that
  calls the command).
- Converters (`IValueConverter`) live in the app project (they're view concerns), are stateless, declared once as
  resources; formatting that is a business rule belongs in the view model instead.
- Validation shows through `INotifyDataErrorInfo` (the toolkit's `ObservableValidator`) and
  `Validation.ErrorTemplate`; `ValidatesOnNotifyDataErrors` is on by default for `{Binding}`.
- Lists: `ItemsSource="{Binding Books}"` over an `ObservableCollection<T>` of immutable item records with stable
  ids; one item `UserControl`/`DataTemplate`; keep virtualization on **with recycling** —
  `VirtualizingPanel.IsVirtualizing="True"`, `VirtualizationMode="Recycling"`, `ScrollUnit="Pixel"` — and never wrap
  a `ListBox` in a `ScrollViewer` or an auto-sized `StackPanel`; never an `ItemsControl` with a plain `StackPanel`
  for data-driven lists (it builds every item). Full pattern, keyed diffing and paging: `ui-implementation.md`.
- After renaming a view-model property, `grep -rn "Binding.*<OldName>" --include=*.xaml` — nothing else will find it.

## Components (`UserControl`s)

Reusable pieces are `UserControl`s whose inputs are dependency properties (ideally one `State` record from Core) and
whose events are `ICommand` dependency properties; they bind to their own properties through `ElementName` on a named
root and **never set their own `DataContext`**. Screens (windows/pages) own the view model. Shape, RTL
(`FlowDirection`), theme switching for tests and the FlaUI capture/Axe.Windows pattern: `ui-implementation.md`.

## Resources, styles, theme

- Shared brushes/styles in `ResourceDictionary` files merged in `App.xaml`; `StaticResource` by default,
  `DynamicResource` only for values that change at run time (theme colors).
- Implicit styles (`TargetType` without `x:Key`) for app-wide look; `BasedOn="{StaticResource {x:Type Button}}"` to
  extend; `ControlTemplate` only when restyling isn't enough.
- **Fluent theme** (.NET 9+): `ThemeMode="System"` (or `Light`/`Dark`) on `<Application>` in XAML. Setting
  `ThemeMode` **from code** is experimental and fails the build with `WPF0001` under `TreatWarningsAsErrors`
  (verified) — don't, unless the task asks and the suppression is scoped and justified. The one sanctioned case is
  the UI-test theme hook in `App.OnStartup` (`--ui-theme=light|dark`, `#pragma warning disable WPF0001` around the
  single assignment — verified to build), scaffolded by `iru-setup-windows-app`.

## Threading

- One UI thread; every `DependencyObject` has thread affinity (`DispatcherObject.VerifyAccess`).
- From code-behind/UI services: `await` (without `ConfigureAwait(false)`) returns to the UI thread; for an
  event from a background thread use `Dispatcher.InvokeAsync(…)` (prefer it to the blocking `Invoke`).
- Background-thread changes to a bound `ObservableCollection<T>` throw unless
  `BindingOperations.EnableCollectionSynchronization(collection, lockObject)` was called — prefer marshaling.
- `DispatcherTimer` for UI-paced timers; never `Thread.Sleep` on the UI thread.

## Startup, manifest, exceptions

- `app.manifest`: `PerMonitorV2` DPI awareness, `requestedExecutionLevel level="asInvoker"`, `supportedOS` for
  Windows 10/11 — change only if the task says so (`security.md`).
- Global handlers in `App.xaml.cs`: `DispatcherUnhandledException` (log, inform, decide `Handled`),
  `TaskScheduler.UnobservedTaskException`, `AppDomain.CurrentDomain.UnhandledException` — log through the host's
  `ILogger`.
- `AssemblyInfo.cs` with `[assembly: ThemeInfo(...)]` is template-generated; leave it.

## Publishing constraints

Native AOT and trimming are not supported for WPF (`NETSDK1168`); single-file + self-contained is fine
(`packaging.md`).
