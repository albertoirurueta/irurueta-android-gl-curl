# The UI contract in a .NET terminal UI (Terminal.Gui v2)

`ui-contract.md` is the platform-neutral rule set; this file maps it onto the optional full-screen terminal UI of a
.NET command-line tool (`iru-setup-dotnet-cli` `ui: tui` — Terminal.Gui 2.x, instance-based `Application.Create()`
API). Read it only when the task adds or changes the TUI (`src/<Tool>/Tui/`, a `browse`-style command, a
`Terminal.Gui` view); the rest of the CLI follows `cli.md` and `cli-contract.md`. Everything below was compiled with
warnings as errors and run against Terminal.Gui 2.5.0, MSTest 4.5.1 and .NET SDK 10.0.402 on macOS on 2026-10-16.

## Screens, components, inputs and events

| Contract term | Here |
|---|---|
| Screen | one full-screen `Window` run by `app.Run(window)` from a TTY-gated command (`BrowseCommand`); built by a `<Name>View.Create(state, …)` factory in `src/<Tool>/Tui/` so tests can render it without the command |
| Region | a subview laid out with `Pos`/`Dim` (`X = Pos.Right(list) + 2`, `Width = Dim.Percent(40)`, `Height = Dim.Fill()`) — never absolute columns that break on resize |
| Component | a `View` composed from Terminal.Gui views (`ListView`, `Label`, `TableView`, `FrameView`…) by a factory method, or a small `View` subclass when it needs its own drawing; it renders the state it is given |
| UI state | a Core type (`Browse/BrowseState`): the items, the selection, derived text — no Terminal.Gui type in Core; unit-tested in `tests/<Tool>.Core.Tests` |
| Event | the view forwards a Terminal.Gui event (`ListView.ValueChanged`, a key binding, `Accepted`) to one intent on the state (`state.Select(index)`) or a .NET event named after the intent (`event EventHandler<string>? OpenRequested`) |

- **Composition, not inheritance**: build screens by `window.Add(list, detail)` of smaller views; subclass `View`
  only for custom drawing (`OnDrawingContent`). Keep a factory under ~150 lines; split regions into their own
  factories.
- **Presentational views** only read state and forward intents — no file/network access, no `CliStreams` writes, no
  exit codes. The command owns the TTY gate, the cancellation timer and `app.Run`; Core owns the logic.
- **Every TUI function also exists as a non-interactive command** (`cli-contract.md`): the TUI is a convenience for
  people, never the only way to get data.
- Doc comments on every view factory name its inputs, events and visual states.
- No hard-coded user-visible strings scattered through views when the tool localizes (resources); key hints in the
  window title or a status bar (`Esc to quit`).

## Collections — `ListView` / `TableView` over a data source

`ListView` draws only the rows that fit its viewport, so it is the virtualized container here; the collection's whole
data lives in the state (an `IReadOnlyList` of items, selection as an index or id kept in the state, never read back
from the view later).

```csharp
internal static class BrowseView
{
    /// <summary>The browse screen. Inputs: a <see cref="BrowseState"/>. States: default, many-items, long-content.</summary>
    public static Window Create(BrowseState state, string toolName)
    {
        ArgumentNullException.ThrowIfNull(state);
        var window = new Window { Title = $"{toolName} browse (↑/↓ select, Esc quit)" };   // key help on screen
        var list = new ListView { X = 0, Y = 0, Width = Dim.Percent(40), Height = Dim.Fill() };
        var greeting = new Label { X = Pos.Right(list) + 2, Y = 1, Text = state.SelectedGreeting };
        list.SetSource(new ObservableCollection<string>(state.Names));
        list.ValueChanged += (_, args) =>
        {
            state.Select(args.NewValue ?? 0);          // intent → state; the view never keeps its own copy
            greeting.Text = state.SelectedGreeting;
        };
        window.Add(list, greeting);
        return window;                                 // disposing the window disposes its subviews
    }
}
```

- Small/medium lists: `SetSource(ObservableCollection<T>)` (a `ListWrapper<T>`); an update mutates that collection
  (`Add`/`RemoveAt`/`Move` — it raises `CollectionChanged`, which the list redraws) instead of building a new source.
- Very large or computed data: implement `IListDataSource` (`Count`, `MaxItemLength`, `Render(...)` for one visible
  row, `IsMarked`/`SetMark`, `ToList`, `CollectionChanged`, `SuspendCollectionChangedEvent`) so only visible rows are
  materialized, or page it in the state (`LoadMore` when the selection nears the end).
- Tables: `TableView` over an `ITableSource` (`Rows`, `Columns`, `ColumnNames`, `this[row, col]`).
- Many tabs: `TabView` with tab content created when the tab is selected.

## Direction, colour and keyboard

- Terminals don't mirror layout: a `rtl` capture is right-to-left **content** (Arabic/Hebrew items) in the normal
  layout, checking width, truncation and that nothing overlaps.
- Colour is never the only carrier of meaning (selection also shows the cursor/marker); the tool's `NO_COLOR`/
  `--no-color`/`TERM=dumb` policy (`ColorPolicy`) applies to the TUI too.
- Every action has a key; `Esc` quits; focus order follows reading order; the key help is on screen (the scaffold
  puts it in the window title: `greetly browse (↑/↓ select, Esc quit)`).

### Accessibility — what can actually be asserted

There is no automated accessibility checker for terminal UIs. `.ui-quality.yml`'s `accessibility` level applies to
these checks, each a rule id the tests compute from the rendered text (the same ids the Rust and Go TUIs use):

| Rule id | Check |
|---|---|
| `tui-color-only` | The selection and every state (error, disabled, loading) are readable in the text buffer — a marker, a word, or the detail pane naming the selected item — not only colour. |
| `tui-help-line` | Every screen shows its key help. |
| `tui-keyboard` | Every action the screen offers is reachable by a key, asserted by injecting the keys. |

`AccessibilityCheck.Apply(target, violations)` (`tests/<Tool>.Cli.Tests/AccessibilityCheck.cs`) applies the level:
**enforce** → `Assert.Fail("[a11y] <target>: …")`; **report** → appends `<target>\t<rule-id>: <message>` lines to
`<screenshots dir>/a11y-report.txt` (the test gate counts them); **off** → nothing. The coding defaults apply under
every level. Say in reports that these are asserted checks, not an automated audit.

## UI tests and text captures (`tests/<Tool>.Cli.Tests/Tui/`)

Render the real views in-process through Terminal.Gui's ANSI driver at a fixed size, drive them with injected keys,
and read the screen buffer — no pseudo-terminal, any host, no lock. Mark the class `[TestCategory("UI")]` (so
`iru-dotnet-test`'s `ui` selector finds it and ordinary runs can exclude it) and `[DoNotParallelize]`.

```csharp
using Terminal.Gui.App;
using Terminal.Gui.Drivers;
using Terminal.Gui.Input;
using Terminal.Gui.Testing;

private static string Render(BrowseState state, Action<IApplication>? act = null)
{
    using var app = Application.Create();                 // Application.Create(timeProvider) for a fixed clock
    app.Init(DriverRegistry.Names.ANSI);
    app.Driver!.SetScreenSize(60, 12);                     // fixed size → deterministic layout
    using var window = BrowseView.Create(state, "greetly");   // title carries the key help
    var token = app.Begin(window);
    app.LayoutAndDraw(true);
    act?.Invoke(app);                                      // e.g. app.InjectKey(Key.CursorDown)
    app.LayoutAndDraw(true);
    var screen = app.Driver.ToString() ?? string.Empty;   // the rendered buffer as text
    app.End(token!);
    return screen;
}
```

- **States × directions**: `[DynamicData]` over every visual state × `UiScreenshots.Directions` (from
  `.ui-quality.yml`); fixtures per state — `default` (sample names), `many-items` (hundreds of rows: proves only the
  visible window renders), `long-content` (names longer than the column: assert on the visible start, truncation is
  expected), and Arabic/Hebrew names for `rtl`.
- **Behaviour** once: inject keys (`Key.CursorDown`, `Key.Enter`, `Key.Esc`) and assert the state and the rendered
  text; assert that the action is reachable by keyboard alone.
- **Accessibility**: after each capture, `AccessibilityCheck.Apply($"BrowseView {state} {direction}", violations)`
  with the `tui-color-only`/`tui-help-line` violations computed from the screen text (above).
- **Captures**: `UiScreenshots.SaveText(screen, "screen", "BrowseView", state, direction)` writes
  `<UI_SCREENSHOTS_DIR or <repo root>/.ui-screenshots>/screen/BrowseView/<state>--<direction>--no-color.txt` — the
  theme segment is `no-color` because the text buffer carries no colour (`app.Driver.ToAnsi()` returns the coloured
  ANSI form if a renderer such as `freeze` is available to turn it into a PNG; optional, never required).
- A component tested alone: build it into a bare `Window` in the same `Render` helper and capture it under
  `component/<ViewName>/`.

The shared helper (`tests/<Tool>.Cli.Tests/UiScreenshots.cs`, scaffolded by `iru-setup-dotnet-cli`; add it if the
repository lacks it) resolves the root and validates the name:

```csharp
public static string SaveText(string screen, string kind, string id, string state, string direction)
{
    // kind: component|screen|flow; id: PascalCase type name or kebab-case flow; state: kebab-case; direction: ltr|rtl
    var directory = Path.Combine(RootDirectory, kind, id);   // RootDirectory: UI_SCREENSHOTS_DIR, else <repo>/.ui-screenshots
    Directory.CreateDirectory(directory);
    var path = Path.Combine(directory, $"{state}--{direction}--no-color.txt");
    File.WriteAllText(path, screen);
    return path;
}
```

`Directions` reads `directions: [...]` from `.ui-quality.yml` at the repository root (nearest `.git` or `*.slnx`
above the test binaries), defaulting to `[ltr, rtl]`; `QualitySetting("accessibility")` gives the level (default
`enforce`). All three levels were exercised: `enforce` failed the tests with `[a11y]` messages when the key help was
removed, `report` wrote `a11y-report.txt` and passed, the default passed with the help in place.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Terminal UI only: assert and implement what a terminal can offer - no colour-only information, `NO_COLOR` honoured, keyboard-only operation, a visible help line, and screen-reader-friendly linear output (no spinner spam when not a TTY). Plain CLIs have no UI rules here.

## Sources

- Terminal.Gui v2 documentation: <https://tui-cs.github.io/Terminal.Gui/> and repository
  <https://github.com/gui-cs/Terminal.Gui> (instance-based `Application.Create()`, `DriverRegistry`, the
  `Terminal.Gui.Testing` input-injection API, `ListView`/`IListDataSource`, `TableView`/`ITableSource`) — API names
  checked against the 2.5.0 package's XML documentation
- irurueta docs: ai/cli-for-agents/tuis-vs-clis.adoc and testing-clis.adoc —
  <https://www.irurueta.com/ai/cli-for-agents/index.html>
- NO_COLOR: <https://no-color.org/>
