# C# code style (.NET 10 / C# 14, desktop)

The defaults for new code in a Windows desktop solution. The repository's `.editorconfig`, `stylecop.json` and
`Directory.Build.props` win wherever they say something more specific — read them first; with
`TreatWarningsAsErrors` they are not suggestions, they are the build.

## File shape

- **File-scoped namespaces** (`namespace Bookshelf.Core.Services;`), matching the folder path under the project's
  root namespace. One public type per file, file named after the type.
- **`using` directives outside the namespace**, `System*` first. StyleCop's default (`SA1200`) wants them *inside*;
  the catalog's `stylecop.json` sets `"orderingRules": { "usingDirectivesPlacement": "outsideNamespace" }` — if the
  repository's doesn't and `SA1200` fires, follow what the existing files do and report it rather than flipping the
  repository's setting. `ImplicitUsings` is on: don't add `using System;`/`System.Linq`/`System.Threading.Tasks`.
- **Nullable reference types on** (`<Nullable>enable</Nullable>`). No `!` (null-forgiving) in production code except
  where an analyzer can't see an invariant you can name in a comment; never `= null!` on a field outside tests.
- Four-space indent, Allman braces, braces on every `if`/`for` body, one statement per line, line length ≤ 120.

## Members and ordering

StyleCop's ordering rules (`SA1201` element order, `SA1202` access order, `SA1204` static before instance) — inside a
type: constants → fields → constructors → finalizer → delegates → events → enums → interfaces → properties →
indexers → methods → structs → classes; within each kind, `public` → `internal` → `protected internal` →
`protected` → `private protected` → `private`, static before instance. Source-generated members (`[ObservableProperty]`
partial properties, `[RelayCommand]` methods) follow the same order by their declared kind.

- `sealed` on every class not designed for inheritance; `readonly` on every field not reassigned; `static` on lambdas
  that capture nothing.
- Private fields `_camelCase` (the irurueta docs convention; the catalog's `.editorconfig` disables `SA1309`/`SA1101`
  for it) — match the repository if it uses `this.` + `camelCase` instead.
- Primary constructors are fine for small services; a view model that validates arguments uses an explicit
  constructor so the guard clauses read first.
- `record` for immutable data (`public sealed record Book(string Isbn, string Title);`), collection expressions
  (`[]`), target-typed `new()`, `is null`/`is not null`, `switch` expressions, string interpolation.

## Guard clauses and errors

- Validate public arguments at the top with the throw helpers: `ArgumentNullException.ThrowIfNull(service);`,
  `ArgumentException.ThrowIfNullOrWhiteSpace(name);`, `ArgumentOutOfRangeException.ThrowIfNegative(count);`. Document
  each with `<exception cref="…">` (`xml-docs.md`).
- Don't catch `Exception` in Core except at a boundary that translates it into a result the UI shows; never swallow
  silently. In the app project, an unhandled-exception handler (`DispatcherUnhandledException`,
  `Application.ThreadException`, `UnhandledException` in WinUI) logs and informs — it doesn't hide bugs.
- Culture: `CA1304`/`CA1305`/`CA1311` are on under `AnalysisLevel latest-recommended` — pass
  `CultureInfo.InvariantCulture` for protocol/storage strings and `CultureInfo.CurrentCulture` for anything shown to
  the user; use `StringComparison` overloads.

## Async

- **Async all the way**: an I/O-bound operation is `Task`/`ValueTask`-returning and named `…Async`, with a
  `CancellationToken cancellationToken = default` last parameter on public APIs that can take time. Never `.Result`,
  `.Wait()`, or `GetAwaiter().GetResult()` — on a UI thread that deadlocks.
- **`ConfigureAwait(false)` in Core and library code only** (it has no UI context to return to, and may be called from
  one). **Never in the app project's UI code** (code-behind, WPF/WinUI/WinForms event handlers, UI service
  implementations): the continuation must resume on the UI thread to touch controls. A view model in Core that sets
  bound properties after an `await` is called from a UI-thread command, so it **does not** use `ConfigureAwait(false)`
  on awaits whose continuation raises `PropertyChanged` (WPF marshals scalar property changes, but WinUI and
  `ObservableCollection<T>` changes do not) — put the `ConfigureAwait(false)` inside the service it calls instead.
- **No `async void`** except event handlers (`private async void OnLoaded(object sender, RoutedEventArgs e)`), which
  wrap their body in `try`/`catch` that reports the error. Commands are `[RelayCommand] private async Task SaveAsync()`
  (the toolkit generates an `AsyncRelayCommand`), never an `async void` method.
- CPU-bound work off the UI thread with `await Task.Run(…, cancellationToken)` from the view model/controller, not
  inside a service that pretends to be async.

## Naming

PascalCase types/methods/properties/events/constants, `I` prefix for interfaces, `T` prefix for type parameters,
`camelCase` parameters/locals, `Async` suffix, boolean members as predicates (`IsBusy`, `CanSave`). View models
`<Screen>ViewModel`, controllers `<Screen>Controller`, view interfaces `I<Screen>View`, windows `<Name>Window`, forms
`<Name>Form`, WinUI pages `<Name>Page`, services `I<Thing>Service`/`<Thing>Service`. XAML element names
(`x:Name`) PascalCase and only when code-behind or `ElementName` needs them; automation ids
(`AutomationProperties.AutomationId`) on every control a UI test or screen reader targets.

## Analyzer interplay (seen in verification)

- `SA1200` (using placement) — see "File shape".
- `CA1707` (no underscores in member names) rejects `Method_Scenario_Expected` test names under
  `latest-recommended`; the catalog's `.editorconfig` turns it off for `tests/**`. Don't rename existing tests to dodge it.
- `SA0001` fires when a project disables `GenerateDocumentationFile` while StyleCop is active — test projects keep the
  file on (or the repository silences `SA0001` there).
- Never silence an analyzer with a blanket `<NoWarn>`; use `[SuppressMessage(…, Justification = "…")]` on the member
  or a scoped `#pragma warning disable/restore` with a reason, and only when the rule is genuinely wrong for that line.
