# MVC / passive view for Windows Forms

`architecture: mvc` is offered only for Windows Forms: the designer produces event-driven forms, and a controller that
handles the view's events through an interface keeps every rule out of the form and testable in Core. (WPF and WinUI
are MVVM — MVC is not a XAML pattern.) Microsoft documents no MVC/MVP pattern for WinForms; this is the catalog's
user pattern, the "passive view" flavor of MVP: the view is dumb, the controller decides.

## The four parts

| Part | Lives in | Responsibility |
|---|---|---|
| Model (`Models/<X>Model.cs`) | Core | State + rules; raises `Changed`/specific events (`EventHandler`, `EventHandler<TEventArgs>`) when state changes. No knowledge of views or controllers. |
| View interface (`Views/I<Screen>View.cs`) | Core | What the controller may read (`string NameInput { get; }`), show (`void ShowMessage(string text)`, `void SetGreetEnabled(bool enabled)`), and the events the user raises (`event EventHandler? GreetRequested;`). Only BCL types — never `Control`, `Color`, `DialogResult`. |
| Controller (`Controllers/<Screen>Controller.cs`) | Core | Constructed with the view interface, the model and services; subscribes to view and model events in its constructor (or an `Attach()`), calls services, updates the model, pushes results to the view. Implements `IDisposable` to unsubscribe. |
| Form (`<Screen>Form.cs` + `.Designer.cs`) | app project | Implements `I<Screen>View`: maps controls to the interface's properties/methods and raises the interface's events from control events. Nothing else. |

```csharp
namespace Bookshelf.Core.Views;

/// <summary>The main screen as the controller sees it.</summary>
public interface IMainView
{
    /// <summary>Occurs when the user asks for a greeting.</summary>
    event EventHandler? GreetRequested;

    /// <summary>Gets the name the user typed.</summary>
    string NameInput { get; }

    /// <summary>Shows a message to the user.</summary>
    /// <param name="message">The message to show.</param>
    void ShowMessage(string message);
}
```

```csharp
namespace Bookshelf.Core.Controllers;

/// <summary>Handles the main screen's events.</summary>
public sealed class MainController : IDisposable
{
    private readonly IMainView _view;
    private readonly GreetingModel _model;

    /// <summary>Initializes a new instance of the <see cref="MainController"/> class.</summary>
    /// <param name="view">The view to drive.</param>
    /// <param name="model">The greeting model.</param>
    public MainController(IMainView view, GreetingModel model)
    {
        ArgumentNullException.ThrowIfNull(view);
        ArgumentNullException.ThrowIfNull(model);
        _view = view;
        _model = model;
        _view.GreetRequested += OnGreetRequested;
        _model.Changed += OnModelChanged;
    }

    /// <inheritdoc/>
    public void Dispose()
    {
        _view.GreetRequested -= OnGreetRequested;
        _model.Changed -= OnModelChanged;
    }

    private void OnGreetRequested(object? sender, EventArgs e) => _model.Greet(_view.NameInput);

    private void OnModelChanged(object? sender, EventArgs e) => _view.ShowMessage(_model.Message);
}
```

Reusable pieces inside a form are `UserControl`s with plain inputs (`Items`, `State`) and intent-named
`EventHandler<TEventArgs>` events; the form forwards those events to its view-interface events and the controller
pushes state back through the interface into the control (`ui-implementation.md`) — the controller owns the state,
the control renders it.

## Rules

- The controller never news up a form or shows a dialog: opening another screen or asking a question goes through a
  Core interface (`IScreenNavigator`, `IDialogService`) implemented in the app project.
- The form never calls a service or the model; its event handlers are one line (`GreetRequested?.Invoke(this,
  EventArgs.Empty);`). Async work belongs to the controller: an async handler there is
  `private async void OnSaveRequested(object? sender, EventArgs e)` — an event handler, so `async void` is allowed —
  with a `try`/`catch` that reports through the view.
- Who creates the controller: the composition root (DI resolves the form, the form's constructor receives a
  controller factory, or `Program` wires `new MainController(form, model)`); keep it consistent with the existing
  screens. The form disposes the controller in `OnFormClosed`/`Dispose`.
- Threading: the controller runs on the UI thread (events come from controls). If a service completes on another
  thread, `await` (without `ConfigureAwait(false)` in the controller) brings it back; the form may additionally guard
  with `InvokeAsync` (`winforms.md`).

## Designer files

- `<Form>.Designer.cs` is owned by the Windows Forms designer: never hand-edit `InitializeComponent` beyond what the
  designer can round-trip — only control declarations, property assignments, `Controls.Add`, event wiring
  (`button.Click += OnGreetClick;`), `SuspendLayout`/`ResumeLayout`. No loops, conditionals, lambdas, or calls into
  your own code there; put those in the form's constructor after `InitializeComponent()`.
- Adding a control by hand is fine when it follows exactly the designer's shape (field declaration at the bottom,
  instantiation and properties in `InitializeComponent`, `Controls.Add`); unverified locally whether the designer
  reopens it cleanly (the designer runs only in Visual Studio on Windows) — say so in the report.

## Testing

Controllers are tested in Core.Tests with a **fake view** (a small class implementing `IMainView` that records
`ShowMessage` calls and exposes a `RaiseGreetRequested()` helper) and a real or fake model — see `testing.md`. The
form itself is covered only by UI automation.
