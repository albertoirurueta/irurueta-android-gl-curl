# XML documentation comments

`GenerateDocumentationFile` is on in `Directory.Build.props`, so a public or protected member without a `///` comment
is warning `CS1591` — an error under `TreatWarningsAsErrors` — and StyleCop's `SA16xx` documentation rules add
`SA1600` (elements must be documented) and friends. DocFX (`docfx.json` at the root, metadata from the Core project)
renders the comments into the API reference the Antora site publishes under `api/`. The audit of what a task missed
belongs to `iru-dotnet-docfx` in the group's validation; this file is about writing them right the first time.

## What to document

- Every public/protected type, constructor, method, property, event, field, constant, and enum member — in the app
  project too (windows, pages, forms, converters are public types).
- `<summary>` first: one sentence saying what the member is for, third person, present tense, ending with a period
  ("Gets or sets the book title." / "Loads the books matching the current filter.").
- `<param name="…">` for every parameter, `<typeparam>` for every type parameter, `<returns>` unless `void`/`Task`,
  `<exception cref="ArgumentNullException">` (etc.) for every exception the guard clauses throw.
- Properties: "Gets …" / "Gets or sets …" (StyleCop `SA1623` checks the wording against the accessors).
- Constructors: "Initializes a new instance of the <see cref="MainViewModel"/> class." (`SA1642`).
- `<inheritdoc/>` on interface implementations and overrides (the service implementation inherits from
  `IGreetingService`'s comment) — don't restate.
- `<see cref="…"/>`/`<paramref name="…"/>` for code references, `<c>` for literals, `<remarks>` for what a caller
  needs to know that the signature doesn't say (threading, ownership, side effects).

## Source-generated members

- `[ObservableProperty]` on a **partial property**: document the property you wrote — the generated implementation
  part is emitted with `/// <inheritdoc/>` and needs nothing more.
- `[RelayCommand] private Task SaveAsync()`: the generated public `SaveCommand` gets its own generated summary
  ("Gets an `IAsyncRelayCommand` instance wrapping `SaveAsync`"), so `CS1591` never fires for it. The private method
  needs no comment under StyleCop's defaults (`documentPrivateElements: false`); add a one-line `<summary>` anyway
  when what the command does isn't obvious from its name — DocFX links the generated summary to it.
- WinForms `*.Designer.cs`, WPF/WinUI `*.g.cs`/`*.g.i.cs`: generated, never documented by hand.

## Test projects

Test classes carry no documentation requirement; the catalog's `.editorconfig` relaxes `CS1591`/`SA1600` under
`tests/**`. Leave any existing comment in test code as it is.

## Namespaces

C# has no namespace doc mechanism; DocFX supports a `NamespaceDoc` class convention. Follow it only if the repository
already uses it (`grep -r "class NamespaceDoc"`) — never introduce it as a side effect.
