# WinUI 3 / Windows App SDK (`ui: winui`)

WinUI 3 on the Windows App SDK 2.x (SemVer since 2.0; NuGet version = SDK version): the app project targets
`net10.0-windows10.0.19041.0` (`TargetPlatformMinVersion 10.0.17763.0`), sets `<UseWinUI>true</UseWinUI>`,
`<EnableMsixTooling>true</EnableMsixTooling>`, `<Platforms>x64;ARM64</Platforms>` and `RuntimeIdentifiers`. Its XAML
compiler is a Windows executable: **the project builds only on Windows** (on macOS it fails with
`XamlCompiler output file "…/output.json" was not created` — verified). Everything in Core still builds and tests
anywhere, which is why the logic goes there.

## `x:Bind` (compiled bindings)

```xml
<Window x:Class="Bookshelf.MainWindow"
        xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <StackPanel Margin="12" Spacing="8">
        <TextBox AutomationProperties.AutomationId="NameInput"
                 Text="{x:Bind ViewModel.Name, Mode=TwoWay, UpdateSourceTrigger=PropertyChanged}" />
        <Button AutomationProperties.AutomationId="GreetButton" Content="Greet" Command="{x:Bind ViewModel.GreetCommand}" />
        <TextBlock AutomationProperties.AutomationId="Message" Text="{x:Bind ViewModel.Message, Mode=OneWay}" />
    </StackPanel>
</Window>
```

- `x:Bind` resolves against the **page/window class**, not `DataContext`: expose
  `public MainViewModel ViewModel { get; }` on the code-behind, set from the constructor (DI-injected) before
  `InitializeComponent()` or right after it.
- **Default mode is `OneTime`** — anything that changes needs `Mode=OneWay` (display) or `Mode=TwoWay` (input). A
  missing `Mode=OneWay` is the most common WinUI bug: it builds, shows the initial value, and never updates.
- Compiled and strongly typed: a renamed view-model member fails the build (on Windows) — a safety net WPF lacks.
- In a `DataTemplate`, set `x:DataType="models:Book"` so `x:Bind` knows the item type.
- Function bindings (`{x:Bind local:Format.Date(ViewModel.Due), Mode=OneWay}`) instead of converters where it reads
  better; `x:Load` for deferred UI.
- Components are `UserControl`s with dependency-property inputs (`x:Bind State.Title, Mode=OneWay` inside) and
  `ICommand`/event outputs; lists are `ListView`/`GridView`/`ItemsView` (virtualizing) with an `x:DataType` item
  template over a keyed `ObservableCollection` — never an `ItemsRepeater` inside a `StackPanel`. RTL
  (`FlowDirection` on the root, `MirroredWhenRightToLeft` icons), `RequestedTheme` for test captures and the
  incremental-loading pattern: `ui-implementation.md`.
- `{Binding}` still exists but is reflection-based; use it only where `x:Bind` can't (rare).

## Threading

- The UI thread has a `DispatcherQueue` (not WPF's `Dispatcher`): `DispatcherQueue.TryEnqueue(() => …)` from a UI
  service when an event arrives on another thread. Changes to bound properties or collections **must** happen on the
  UI thread — WinUI, unlike WPF, never marshals property-change notifications for you.

## Navigation and windowing

- `NavigationView` + `Frame` for multi-page apps; `Frame.Navigate(typeof(BooksPage), parameter)` from an app-project
  `INavigationService` (a user pattern — Core sees only the interface). Pages resolve their view model from DI in the
  constructor or `OnNavigatedTo`.
- `AppWindow` (`Microsoft.UI.Windowing`) for size, position, title bar customization (`ExtendsContentIntoTitleBar`,
  `SetTitleBar`), presenters. There is no `Window.Width` like WPF.
- Backdrop: `SystemBackdrop = new MicaBackdrop()` on the window (or `DesktopAcrylicBackdrop`); fall back silently where
  unsupported.
- Dialogs: `ContentDialog` needs `XamlRoot` set (`dialog.XamlRoot = window.Content.XamlRoot`) — inside the dialog
  service, never in a view model.
- File pickers (`Windows.Storage.Pickers`) in a desktop app need the window handle:
  `WinRT.Interop.InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(window))` — again app-project
  only, behind `IFilePickerService`.

## Packaged vs unpackaged, the manifest

- The catalog's WinUI scaffold is **packaged** (single-project MSIX): identity, capabilities and visual assets come
  from `Package.appxmanifest`. Package identity unlocks app notifications, `ApplicationData`, share target, startup
  tasks, etc.
- Change `Package.appxmanifest` only when the task says so (`packaging.md`): capabilities minimal (`runFullTrust` is
  already there for a desktop app; anything else — `broadFileSystemAccess`, `webcam`, `location` — is a blocker to
  confirm); `Identity Version` is owned by `iru-dotnet-bump-version`; asset paths must exist.
- App lifecycle: single instancing and activation through `Microsoft.Windows.AppLifecycle.AppInstance`
  (`FindOrRegisterForKey`, `RedirectActivationToAsync`) in `Program`/`App` — only when a task asks.

## Testing

View models are in Core and tested like any other (`testing.md`). The WinUI-specific "Unit Test App (WinUI)" project
is for code that needs the WinUI runtime — the catalog avoids needing it by keeping logic in Core. UI automation
(FlaUI) targets the packaged app by AUMID (`Application.LaunchStoreApp(aumid, "--ui-direction=rtl --ui-theme=dark")`)
or the built exe; Windows only. Screens and components get FlaUI UI tests with screenshots and the Axe.Windows check
per `ui-implementation.md`.

## Publishing

WinUI supports trimming and Native AOT (`PublishAot`, since Windows App SDK 1.6); single-file publishing works only
unpackaged + self-contained. MSIX builds go through MSBuild with `GenerateAppxPackageOnBuild` (`packaging.md`).
