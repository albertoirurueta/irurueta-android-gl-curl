# Windows Forms on .NET 10 (`ui: winforms`)

`net10.0-windows`, `<UseWindowsForms>true</UseWindowsForms>`, `<OutputType>WinExe</OutputType>`. Compiles on
Linux/macOS with `EnableWindowsTargeting` (verified, including `Application.SetColorMode`, `Control.InvokeAsync` and
`Button.Command` binding); runs and is designed only on Windows (the out-of-process designer is Visual Studio on
Windows). Architecture is either MVC (`mvc-winforms.md`) or MVVM (this file's binding section + `mvvm-toolkit.md`).

## Startup

```csharp
namespace Bookshelf;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();          // high DPI, visual styles, default font from the csproj
        Application.SetColorMode(SystemColorMode.System);
        using IHost host = AppHost.Build();              // the Generic Host composition root
        Application.Run(host.Services.GetRequiredService<MainForm>());
    }
}
```

- `ApplicationConfiguration.Initialize()` is source-generated from csproj properties
  (`<ApplicationHighDpiMode>PerMonitorV2</ApplicationHighDpiMode>`, `ApplicationVisualStyles`,
  `ApplicationDefaultFont`) — change those properties, not the call.
- Dark mode: `Application.SetColorMode(SystemColorMode.System|Dark|Classic)` — stable in .NET 10 (no experimental
  diagnostic, verified). Custom-drawn controls must use `SystemColors`, not hard-coded colors, to follow it.
- Right-to-left: `RightToLeft = RightToLeft.Yes` + `RightToLeftLayout = true` on the form mirrors it; the UI tests
  drive both (and the color mode) through the `--ui-direction`/`--ui-theme` launch arguments
  (`ui-implementation.md`).

## The designer contract

- A form is `MainForm.cs` (your code) + `MainForm.Designer.cs` (designer-owned) + optional `MainForm.resx`. Edit
  `InitializeComponent` only in shapes the designer round-trips: field declarations, `new`, property assignments,
  `Controls.Add`, event subscriptions to named methods, `SuspendLayout`/`ResumeLayout`/`PerformLayout`. No lambdas,
  loops, conditionals, or calls into your own code there — put those after `InitializeComponent()` in the
  constructor.
- Name controls meaningfully (`greetButton`, `nameTextBox`); set `AccessibleName` and, for UI tests, `Name` (FlaUI
  finds WinForms controls by `AutomationId`, which WinForms maps from `Control.Name`).
- A `UserControl` for a reusable panel — inputs are properties marked `[Browsable(false)]` +
  `[DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]` (without it the WinForms analyzer fails
  the build with `WFO1000` — verified), events are `EventHandler<TEventArgs>` named after the intent
  (`OpenRequested`); the controller/presenter or view model owns the state. A form never reaches into another form's
  controls.
- Data-driven lists: `ListView`/`DataGridView` with `VirtualMode = true` and `RetrieveVirtualItem`/`CellValueNeeded`
  building only the visible rows — never a `FlowLayoutPanel` filled with one `UserControl` per item for unbounded
  data (`ui-implementation.md`).

## Layout and DPI

- Layout with `TableLayoutPanel`/`FlowLayoutPanel` and `Dock`/`Anchor`, `AutoSize` + `AutoSizeMode.GrowAndShrink`,
  `Padding`/`Margin` — never absolute pixel `Location`s for anything that should resize or scale.
- `PerMonitorV2` is the default high-DPI mode; keep `AutoScaleMode = AutoScaleMode.Dpi` (or `Font`) consistent with
  the existing forms; test-sized fonts and images must scale (use `DeviceDpi`/`LogicalToDeviceUnits` for custom
  drawing).

## Binding (architecture `mvvm`)

```csharp
_bindingSource.DataSource = viewModel;                                     // a BindingSource dropped in the designer
nameTextBox.DataBindings.Add(nameof(TextBox.Text), _bindingSource, nameof(MainViewModel.Name),
    false, DataSourceUpdateMode.OnPropertyChanged);
messageLabel.DataBindings.Add(nameof(Label.Text), _bindingSource, nameof(MainViewModel.Message));
greetButton.DataBindings.Add(nameof(Button.Command), _bindingSource, nameof(MainViewModel.GreetCommand), true);
```

- `BindingSource` between the controls and the view model (designer-friendly, handles `INotifyPropertyChanged`).
- `Button.Command`/`ToolStripItem.Command` (.NET 7+) bind to the toolkit's commands; `CanExecute` drives `Enabled`.
- `nameof` everywhere — a string property name is a silent run-time failure.

## Threading and async

- Event handlers may be `async void` (they're event handlers) with a `try`/`catch` that reports the error.
- From a background continuation: `await control.InvokeAsync(() => …)` (.NET 9+, stable in .NET 10) instead of
  `Invoke`/`BeginInvoke`; `Form.ShowAsync()`/`ShowDialogAsync()` (.NET 10) for non-blocking dialogs where the task
  wants them.
- Never block the UI thread (`.Result`, `Thread.Sleep`); long work goes through the controller/view model as
  `async`/`Task.Run`.

## Clipboard and serialization

.NET 10 removed `BinaryFormatter` from clipboard/drag-drop paths: use `Clipboard.SetDataAsJson`/`TryGetData<T>` for
custom types and plain `SetText`/`GetText` for text — never re-enable `BinaryFormatter`.

## Publishing

Trimming and Native AOT are unsupported for Windows Forms; self-contained single-file is fine (`packaging.md`).
