# MVVM with CommunityToolkit.Mvvm 8.4

The catalog's MVVM view models use CommunityToolkit.Mvvm (8.4.x; 8.4.1+ generators need Roslyn 5 / C# 14, which .NET
10's `LangVersion latest` provides — no `preview` needed). The toolkit is a set of base classes plus source generators;
it is **not** a framework — no view-model locator (that was MvvmLight), no navigation or dialog service
(`architecture.md` covers those user patterns).

## View model shape

```csharp
using Bookshelf.Core.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bookshelf.Core.ViewModels;

/// <summary>Drives the main window.</summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly IGreetingService _greetingService;

    /// <summary>Initializes a new instance of the <see cref="MainViewModel"/> class.</summary>
    /// <param name="greetingService">Builds the greeting.</param>
    /// <exception cref="ArgumentNullException"><paramref name="greetingService"/> is <see langword="null"/>.</exception>
    public MainViewModel(IGreetingService greetingService)
    {
        ArgumentNullException.ThrowIfNull(greetingService);
        _greetingService = greetingService;
    }

    /// <summary>Gets or sets the name typed by the user.</summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(GreetCommand))]
    public partial string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets the greeting shown to the user.</summary>
    [ObservableProperty]
    public partial string Message { get; set; } = string.Empty;

    [RelayCommand(CanExecute = nameof(CanGreet))]
    private void Greet() => Message = _greetingService.Greet(Name);

    private bool CanGreet() => !string.IsNullOrWhiteSpace(Name);
}
```

- The class is `partial` (generators add members) and `sealed`; it derives from `ObservableObject`, or
  `ObservableValidator` when it validates, or `ObservableRecipient` only when it must activate/deactivate messenger
  registrations with the view's lifetime.
- **Observable properties are partial properties** (`[ObservableProperty] public partial T Name { get; set; }`) — the
  8.4 form. The old field form (`[ObservableProperty] private string name;`) is what analyzer **MVVMTK0042** asks to
  convert; don't write new fields that way, and convert an existing one only if the task touches it. (Microsoft Learn's
  `ObservableProperty` page still shows fields; the toolkit's release notes are the source for partial properties.)
- Initialize with a property initializer (`= string.Empty;`), never leave a non-nullable reference property null.
- `[NotifyPropertyChangedFor(nameof(Summary))]` when a computed property depends on this one;
  `[NotifyCanExecuteChangedFor(nameof(SaveCommand))]` when a command's `CanExecute` does.
- React to changes in the generated partial hooks — `partial void OnNameChanged(string value)` (also `OnNameChanging`,
  and the `(oldValue, newValue)` overloads) — not by subscribing to your own `PropertyChanged`.
- `[property: JsonIgnore]`-style forwarding puts an attribute on the generated member when needed.
- Never call `OnPropertyChanged("Name")` with a string; when raising by hand (a computed property with no attribute
  path), use `OnPropertyChanged(nameof(Summary))`.

## Commands

- `[RelayCommand] private void Greet()` → generated `GreetCommand` (`IRelayCommand`); `private async Task SaveAsync()`
  → `SaveCommand` (`IAsyncRelayCommand`, the `Async` suffix is dropped). Commands are **methods**, never `async void`.
- `CanExecute = nameof(CanSave)` points at a `bool` method or property; pair it with `[NotifyCanExecuteChangedFor]` on
  every property it reads, or call `SaveCommand.NotifyCanExecuteChanged()` after changing non-observable state.
- Async with cancellation: `private async Task SaveAsync(CancellationToken cancellationToken)` +
  `[RelayCommand(IncludeCancelCommand = true)]` generates `SaveCancelCommand`; pass the token to every awaited call.
  A cancelled run makes `ExecuteAsync` throw `TaskCanceledException` (verified) — the view never sees it through
  `ICommand.Execute`, but a test does.
- `AllowConcurrentExecutions = false` is the default: while running, `CanExecute` is `false` and `IsRunning` is
  `true` — bind a progress indicator to `SaveCommand.IsRunning` instead of a hand-written `IsBusy` flag.
- A command parameter: `private void Open(Book book)` → `IRelayCommand<Book>`; the view passes `CommandParameter`.
- Exceptions in an async command surface from `ExecuteAsync`/`ExecutionTask`; catch expected failures in the method
  and turn them into state (`ErrorMessage`) the view shows.

## Validation (`ObservableValidator`)

```csharp
/// <summary>Gets or sets the book title.</summary>
[ObservableProperty]
[NotifyDataErrorInfo]
[Required]
[MinLength(2)]
[NotifyCanExecuteChangedFor(nameof(SaveCommand))]
public partial string Title { get; set; } = string.Empty;
```

`[NotifyDataErrorInfo]` validates on every set and raises `INotifyDataErrorInfo.ErrorsChanged` (WPF shows it through
`Validation.ErrorTemplate`, WinUI through your own error text); call `ValidateAllProperties()` before saving and check
`HasErrors`. DataAnnotations attributes or `[CustomValidation(typeof(…), nameof(…))]` for rules; keep rule logic in
Core, testable without a view.

## Messaging

- `WeakReferenceMessenger` (the default; recipients can be collected without unregistering) — inject `IMessenger`
  (`services.AddSingleton<IMessenger>(WeakReferenceMessenger.Default)`) instead of reaching for `.Default` inside a
  view model, so tests pass a fresh `new WeakReferenceMessenger()`.
- Messages are small immutable types, one per file (`public sealed record BookSavedMessage(Book Book);`, or
  `ValueChangedMessage<T>` / `RequestMessage<T>` / `AsyncRequestMessage<T>` from the toolkit).
- `messenger.Register<TRecipient, TMessage>(recipient, static (r, m) => r.Handle(m))` — `static` lambdas avoid
  capturing; `StrongReferenceMessenger` only for measured hot paths, and then always `UnregisterAll(this)` when the
  screen closes.
- Prefer a shared service over messages when the relationship is a plain dependency; messages are for broadcast
  between screens that don't know each other.

## Collections and threading

- `ObservableCollection<T>` of immutable item records with a stable `Id` for lists the view shows; apply a new
  snapshot with a **keyed diff** (the `SyncById` helper in `ui-implementation.md`: remove, move, insert, replace only
  what changed) — not `Clear()` + `Add`, which resets scroll position, selection and every recycled container. Keep
  selection as an id property (`SelectedId`), never as a reference to an item view. Never mutate it from a background
  thread: WPF throws unless the app enabled `BindingOperations.EnableCollectionSynchronization`, WinUI always
  throws.
- The view model never touches `Dispatcher`/`DispatcherQueue`. Commands run on the UI thread; `await` returns there
  as long as the view model's own awaits don't use `ConfigureAwait(false)` (`code-style.md`). If a service raises an
  event on a background thread, marshal in the app project (a UI service), not in Core.

## What the toolkit doesn't give you

No navigation, no dialogs, no view-model-first window opening, no view location. Expose those as Core interfaces
implemented in the app project (`architecture.md`). `Ioc.Default` exists, but the catalog uses the Generic Host's
`IServiceProvider` and constructor injection instead.
