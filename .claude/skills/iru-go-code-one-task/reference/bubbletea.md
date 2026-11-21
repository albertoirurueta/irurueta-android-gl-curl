# Bubble Tea v2 (charm.land/bubbletea/v2)

The full-screen UI behind a TTY-only subcommand (`greeter browse`). Verified against `charm.land/bubbletea/v2` v2.1.0
and `charm.land/lipgloss/v2` v2.0.6.

## Import paths — v2 moved

- `tea "charm.land/bubbletea/v2"` and `"charm.land/lipgloss/v2"` (vanity domain; the same repositories as
  `github.com/charmbracelet/...`). Never mix in v1 (`github.com/charmbracelet/bubbletea`) — its `Model.View()` returns
  `string` and `tea.KeyMsg` is a struct; v1 snippets from blogs don't compile against v2.
- Bubbles components for v2 live under `charm.land/bubbles/v2` — adding one is a new dependency (task must allow it).

## The model

A one-screen model is below. When the task adds or changes a screen, component or list, also read
`ui-contract.md` and `ui-implementation.md` — sub-models with `Init`/`Update`/`View` composed by the parent, typed
intent messages instead of raw keys, Bubbles `list`/`table`/`viewport` (or a windowed slice) for collections, the
UI-test matrix, the `.ui-screenshots/` text captures and the accessibility checks. The loop below ranges over every
name only because the picker is a handful of fixed names; a data-driven list renders just the visible window
(`ui-implementation.md` §3).

```go
// Model is a selectable list of names.
type Model struct {
    names  []string
    cursor int
    chosen string
}

func (m Model) Init() tea.Cmd { return nil }

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    key, ok := msg.(tea.KeyPressMsg)
    if !ok {
        return m, nil
    }
    switch key.String() {
    case "q", "esc", "ctrl+c":
        return m, tea.Quit
    case "up", "k":
        m.cursor = max(0, m.cursor-1)
    case "down", "j":
        m.cursor = min(len(m.names)-1, m.cursor+1)
    case "enter":
        if len(m.names) > 0 {
            m.chosen = m.names[m.cursor]
        }
        return m, tea.Quit
    }
    return m, nil
}

func (m Model) View() tea.View {
    var b strings.Builder
    for i, n := range m.names {
        marker := " "
        if i == m.cursor {
            marker = ">"
        }
        fmt.Fprintf(&b, "%s %s\n", marker, n)
    }
    b.WriteString("\nup/down to move, enter to pick, q to quit\n")
    v := tea.NewView(b.String())
    v.AltScreen = true // v2: alt screen, mouse mode, cursor, colours are fields of the View
    return v
}
```

- `View()` returns `tea.View` (`tea.NewView(s)`), not a string. Terminal modes are declarative fields on the view
  (`AltScreen`, `MouseMode`, `Cursor`, `BackgroundColor`) rather than v1's program options/commands.
- Keys arrive as `tea.KeyPressMsg` (and `tea.KeyReleaseMsg` where supported); match on `key.String()` (`"ctrl+c"`,
  `"enter"`, `"up"`) or `key.Code` (`tea.KeyEnter`, `tea.KeyDown`, a rune). Window size arrives as
  `tea.WindowSizeMsg`.
- Value receivers; `Update` returns the modified copy.

## Keep `Update` pure

`Update` decides; it never performs I/O, sleeps, reads files, or calls the network. Side effects go in a `tea.Cmd`
(a `func() tea.Msg`) that returns a message with the result, which `Update` then handles:

```go
type loadedMsg struct{ items []Item; err error }

func load(ctx context.Context, svc Lister) tea.Cmd {
    return func() tea.Msg {
        items, err := svc.List(ctx) // the same core function the `list --json` command calls
        return loadedMsg{items, err}
    }
}
```

That keeps the model unit-testable by feeding messages to `Update` (`testing.md`), and keeps the TUI a *view* over the
same core the non-interactive commands use (`cli-contract.md` §3: every TUI action has a `--json` equivalent).
Destructive actions in the TUI go through the same plan/confirm/`--dry-run` code path as the CLI.

## Starting it — TTY-gated, context-bound, streams injected

In an `iru-setup-go-cli` scaffold this already exists: `tui.Run(ctx, in, out, names)` in `internal/tui` builds the
program with `tea.WithContext`/`WithInput`/`WithOutput` and returns the chosen value, and the `browse` command calls it
through the injectable `a.runTUI` only when `a.IsTerminal(a.In) && a.IsTerminal(a.Out)` and the format isn't machine
— tests swap `a.runTUI` and `a.IsTerminal` to cover both branches without a terminal. Extend that; don't start a second
program elsewhere. The generic shape:

```go
RunE: func(cmd *cobra.Command, _ []string) error {
    if !isTerminal(o.in) || !isTerminal(o.out) {
        return usageError("browse needs a terminal", "Use `greeter greet --json -- NAME` instead.", nil) // exit 2
    }
    p := tea.NewProgram(tui.New(names),
        tea.WithContext(cmd.Context()), // SIGINT/SIGTERM cancel the program; terminal is restored
        tea.WithInput(o.in), tea.WithOutput(o.out))
    if _, err := p.Run(); err != nil {
        return fmt.Errorf("run browser: %w", err)
    }
    return nil
},
```

- Check **stdin and stdout** are TTYs *before* `tea.NewProgram`; never fall back to `tea.WithoutRenderer()` to "make
  it work" for an agent — exit 2 with a pointer to the `--json` command instead.
- Mark the command as interactive-only in its annotations (use the key the repository's `schema` command already
  reads) so agents know not to call it.
- `p.Run()` returns the final model — type-assert it (`final.(tui.Model).Chosen()`) to act on the user's choice
  *after* the terminal is restored; print results to stdout only then.
- A context cancelled by a signal makes `Run` return an error wrapping `tea.ErrProgramKilled`/the context error;
  `classify` turns the context cause into 130/143 (`errors.md`).
- Styling inside the TUI uses Lip Gloss styles in `View`; Bubble Tea downsamples colours for the terminal it owns.
  `NO_COLOR` is honoured by its colour-profile detection; don't add another colour switch inside the model — but
  never carry information by colour alone: the cursor/selection and every state also need a marker or word
  (`ui-implementation.md` §6).

## Testing

Pure `Update` tests first, plus the contract's state × direction × theme captures and accessibility checks
(`ui-implementation.md` §4–§6, through `internal/tui/ui_screenshots_test.go`); one or two `teatest` v2 program tests (`testing.md` — `teatest.NewTestModel`,
`WaitFor`, `Send`, `FinalModel`; it owns the `-update` flag). Coverage of `internal/tui` comes from these; the
TTY-gated `RunE` branch that starts the program is covered only up to the gate (the "not a TTY" path).
