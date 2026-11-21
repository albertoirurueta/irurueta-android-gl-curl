# Architecture: Core vs app, MVVM vs MVC

## The two projects

| Project | Target | Holds | Never holds |
|---|---|---|---|
| `src/<App>.Core` | plain `net10.0` | `Models/` (records, domain types), `Services/` (interfaces **and** pure implementations — parsing, rules, HTTP/file access through abstractions), `ViewModels/` (MVVM) **or** `Controllers/` + `Views/I<X>View.cs` (MVC), messages, validation | Anything from `System.Windows*`, `Microsoft.UI*`, `Windows.*`, `System.Windows.Forms`; `Dispatcher`/`SynchronizationContext` capture; `MessageBox`; file/folder pickers; `Application.Current` |
| `src/<App>` | `net10.0-windows` (WPF/WinForms) or `net10.0-windows10.0.19041.0` (WinUI) | XAML + code-behind / forms + designer files, UI-touching service implementations (dialogs, pickers, navigation, clipboard, notifications, theme), converters, DI composition root, `app.manifest`, `Package.appxmanifest`, assets | Business rules, state machines, formatting rules, anything a test should cover |

Why: Core runs and is covered on Linux/macOS/Windows — the 80% line-coverage bar is measured there, and the UI layer
is reported "not measured", not 0%. Code in the app project is only exercised by UI automation, which runs on Windows
only and never counts toward coverage.

### Deciding where the code goes

1. A path in the task wins.
2. Otherwise: data/rules/state/commands → Core. Something that needs a window handle, a control, the UI thread, a
   Windows-only API (`Windows.Storage.Pickers`, registry, `ProtectedData`) → an **interface in Core**
   (`IDialogService`, `IFilePickerService`, `INavigationService`, `ISecretStore`) + an **implementation in the app
   project**, registered in DI. Pure-.NET Windows-agnostic I/O (`System.IO`, `HttpClient`, `System.Text.Json`) may be
   implemented in Core directly.
3. Ambiguous → Core, and say so in the report.

Those service interfaces are **user patterns** — neither WPF, WinUI, WinForms nor the MVVM Toolkit ships an official
dialog or navigation service. Keep them small and screen-agnostic (`Task<bool> ConfirmAsync(string title, string
message)`, `Task<string?> PickOpenFileAsync(IReadOnlyList<string> extensions)`), returning data, never UI types.

## Composition root: Generic Host

The app builds a `Microsoft.Extensions.Hosting` host at startup and resolves the first window/form from it:

- WPF: `App.xaml` has **no `StartupUri`**; `App.xaml.cs` builds the host in its constructor (or `OnStartup`), registers
  services, view models and windows, `await host.StartAsync()` in `OnStartup`, resolves and shows `MainWindow`, and
  `await host.StopAsync()` + `Dispose()` in `OnExit` (Microsoft Learn: "Use the .NET Generic Host in a WPF app").
- WinForms: `Program.Main` (`[STAThread]`) calls `ApplicationConfiguration.Initialize()`, builds the host, resolves
  `MainForm`, `Application.Run(form)`.
- WinUI: `App.xaml.cs` builds the host in the constructor and resolves `MainWindow` in `OnLaunched`.

Lifetimes for a desktop app: stateless services and repositories `AddSingleton`; view models, windows, forms and pages
`AddTransient` (one per opened screen) unless the task says a screen keeps its state for the app's lifetime. A new
type a task adds is registered in the same composition root as its siblings; constructor injection everywhere — no
service locator (`Ioc.Default`, `App.Current.Services`) in Core.

## MVVM (`ViewModels/` in Core) — WPF, WinUI, and WinForms with `architecture: mvvm`

- **View model** (`<Screen>ViewModel : ObservableObject`, `sealed partial`): exposes state as observable properties
  and actions as commands (`mvvm-toolkit.md`); takes services through its constructor; knows nothing about the view
  type, controls, or threads. Collections the view lists are `ObservableCollection<T>` exposed as such (or a
  read-only wrapper).
- **View** (XAML window/page/user control, or a form): binds to the view model — WPF `{Binding}` with `DataContext`
  set in code-behind or by an implicit `DataTemplate`; WinUI `x:Bind ViewModel.X` through a typed `ViewModel`
  property; WinForms `BindingSource` + `Button.Command` (`winforms.md`).
- **Code-behind may contain**: `InitializeComponent()`, assigning `DataContext`/`ViewModel` (constructor-injected),
  and purely visual concerns (focus, scrolling, animation, `PasswordBox.Password` hand-off) that forward to a command.
  It may **not** contain business logic, service calls, or state the view model doesn't know about. Prefer XAML
  Behaviors' `InvokeCommandAction` (WPF) over an event handler that only calls a command.
- **Communication between view models**: a shared service, or `WeakReferenceMessenger` messages
  (`mvvm-toolkit.md`) — never one view model holding another view's reference.

## MVC / passive view (`Controllers/` + `Views/I<X>View.cs` in Core) — WinForms only

Offered only for WinForms (MVC is not a XAML pattern; WPF and WinUI are MVVM by Microsoft's guidance). Rules in
`mvc-winforms.md`: the **view interface** and the **controller** live in Core; the **form** implements the interface
and contains no logic; the **model** raises change events; the controller subscribes to view and model events and
pushes results back through the interface.

## Screens and components

A window/page/form is a **screen** — it owns its view model (or controller). Reusable UI is a **component** — a
`UserControl` that renders the state it is given and raises events, with no services or navigation. UI-state records
(`OrderRowState`) and collection state live in Core with the view models; `ui-contract.md` and `ui-implementation.md`
cover decomposition, collections, UI tests and captures.

## What never changes between the two

- The Core/app split and the "UI types never in Core" rule.
- Services behind interfaces, constructed by DI.
- Tests: Core.Tests covers models, services, view models/controllers; the UI layer is covered (if at all) by FlaUI
  tests in `tests/<App>.UITests`, outside the coverage bar.
