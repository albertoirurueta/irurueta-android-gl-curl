# The UI contract on Bubble Tea v2

How `ui-contract.md` (read it first) maps onto a Bubble Tea v2 terminal UI (`charm.land/bubbletea/v2`, Lip Gloss v2
`charm.land/lipgloss/v2`, Bubbles v2 `charm.land/bubbles/v2`). `bubbletea.md` keeps the rules about the TTY gate,
pure `Update`, `tea.Cmd` side effects and starting the program; this file covers structure, collections, UI tests,
the text captures and the accessibility checks. It applies only to a task that adds or changes a screen, component,
list or other part of the terminal UI — plain commands are governed by `cli-contract.md` alone.

## 1. What a screen and a component are here

| Contract term | Bubble Tea |
|---|---|
| **Screen** | A full-screen model. The root model (the one `tea.NewProgram` runs, `View() tea.View`) owns the current screen and routes messages to it; with one screen the root *is* the screen (the scaffold's `tui.Model`). |
| **Region** | A block of the screen's view joined with `lipgloss.JoinVertical`/`JoinHorizontal`, sized from the last `tea.WindowSizeMsg` (`lipgloss.Width`/`Height` to measure). |
| **Component** | A sub-model with `Init() tea.Cmd`, `Update(tea.Msg) (T, tea.Cmd)` returning its **concrete type** and `View() string` — the Bubbles convention (`list.Model`, `table.Model`, `textinput.Model`) — or, for a stateless piece, a pure render function `func renderOrderRow(s OrderRowState, selected bool, th Theme) string`. |
| **Event** | A typed `tea.Msg` (`OpenOrderMsg{ID}`) the child returns through a `tea.Cmd`. |
| **Navigation** | One `screen` field in the root model and one place in the root `Update` that switches it. |

Composition is nesting: the parent holds child models as fields, forwards the messages each child needs
(`m.orders, cmd = m.orders.Update(msg)`), combines commands with `tea.Batch`, and joins the children's `View()`
strings. Go has no inheritance; embedding a child model to "extend" it is the same mistake — wrap it in a field and
forward.

```go
// OrderList shows the orders and emits OpenOrderMsg when one is chosen.
//
// Inputs: the rows (SetRows) and the size (SetSize). Events: OpenOrderMsg, LoadMoreMsg.
// States: default, loading, empty, error, many-items, long-content.
type OrderList struct {
	list    list.Model
	loading bool
}

// Update handles keys for the list and turns enter into an OpenOrderMsg.
func (o OrderList) Update(msg tea.Msg) (OrderList, tea.Cmd) {
	if k, ok := msg.(tea.KeyPressMsg); ok && k.String() == "enter" {
		if row, ok := o.list.SelectedItem().(orderItem); ok {
			return o, func() tea.Msg { return OpenOrderMsg{ID: row.ID} }
		}
	}
	var cmd tea.Cmd
	o.list, cmd = o.list.Update(msg)
	return o, cmd
}
```

Row states carry display-ready values (`AmountText` formatted by the model) and a stable `ID`. A presentational
component never reads files, the clock, the environment or the network — the screen does that in a `tea.Cmd` and
passes the result down.

## 2. Inputs and events — unidirectional

- **State down**: the parent sets a child's inputs (`SetRows`, `SetSize`, a struct field) — the child never reaches
  into its parent.
- **Events up**: only the focused component sees raw `tea.KeyPressMsg`s (match with `key.String()` or, when Bubbles
  is a dependency, `key.Matches(msg, keys.Open)` from `charm.land/bubbles/v2/key`). It turns them into intent
  messages carrying ids — `OpenOrderMsg{ID}`, `RetryMsg{}`, `LoadMoreMsg{}`, `QueryChangedMsg{Text}` — which the parent
  handles in its own `Update`. A raw key never travels further than the component that owns it.
- **Two-way binding** only for text fields: the value lives in a `textinput.Model` the screen owns; after forwarding
  a key, the screen compares `Value()` and emits `QueryChangedMsg`.

## 3. Collections — Bubbles render only what fits

| Need | Component | Item component |
|---|---|---|
| Long or filterable list | `list.New(items, delegate, width, height)` — paginated: only the current page is rendered | a `list.ItemDelegate` (`Render(w, m, index, item)`, `Height()`, `Spacing()`, `Update`) calling the row's pure render function |
| Rows with columns | `table.New(table.WithColumns(...), table.WithRows(...), table.WithHeight(h))` — renders the rows inside its height | the `table.Row` values the model builds |
| Long text | `viewport.New(...)` + `SetContent` | — |

```go
type orderItem struct{ OrderRowState }

func (i orderItem) FilterValue() string { return i.Title }

type orderDelegate struct{ theme Theme }

func (d orderDelegate) Height() int                         { return 1 }
func (d orderDelegate) Spacing() int                        { return 0 }
func (d orderDelegate) Update(tea.Msg, *list.Model) tea.Cmd { return nil }
func (d orderDelegate) Render(w io.Writer, m list.Model, index int, item list.Item) {
	if row, ok := item.(orderItem); ok {
		fmt.Fprint(w, renderOrderRow(row.OrderRowState, index == m.Index(), d.theme))
	}
}
```

- **Stable ids**: after `SetItems` (it returns a `tea.Cmd` — keep it), restore the selection by id: find the index
  of the previously selected `ID` and `Select(i)`. Never key anything by position.
- **Paging**: when `Index()` comes within a few rows of the end and no page is loading, return a `tea.Cmd` that loads
  the next page (the same core function the `list --json` command uses) and append on `pageLoadedMsg`.
- **Fixed size**: the delegate's `Height()`/`Spacing()` are the contract's fixed item size; call `SetSize` on every
  `tea.WindowSizeMsg`.
- A hand-written list (no Bubbles dependency, e.g. the scaffold's picker) keeps a scroll `offset` in the model and
  renders only `names[offset:offset+rows]` — never a loop over the whole data set inside `View`.
- Adding `charm.land/bubbles/v2` is a new module dependency: only when the task allows it (`SKILL.md` Step 3).

## 4. UI tests

All in-process `_test.go` files in the TUI package — no terminal, no PTY, no lock.

| Level | How |
|---|---|
| Component | Call the pure render function or the child's `View()` with fixture state; strip styles (`ansi.Strip` from `github.com/charmbracelet/x/ansi`, already in the module graph through Lip Gloss) and assert the text. Feed messages to the child's `Update` and run the returned command: `msg := cmd(); open, ok := msg.(OpenOrderMsg)` — assert the type and the id; a disabled affordance returns a nil command. |
| Screen | The screen model with fake loaders injected (functions returning `tea.Cmd`), a `tea.WindowSizeMsg{Width: 60, Height: 12}` first, then keys; assert `View().Content` and the messages emitted. |
| Flow | teatest v2 (`github.com/charmbracelet/x/exp/teatest/v2`): `NewTestModel(t, m, teatest.WithInitialTermSize(80, 24))`, `Send` keys, `WaitFor` the next step's text, `FinalModel`; capture each step from the model's view between sends (`flow/<flow-name>/01-<step>…`). `teatest.RequireEqualOutput` golden files are the opt-in baseline comparison (teatest owns `-update`). |

- **Data**: `minimal-data`, `typical-data`, `many-items` (thousands — also assert an item far below the page is *not*
  in the view), `long-content` (wider than the window), missing optional values, large numbers, and right-to-left
  text (Arabic or Hebrew).
- **Directions**: terminals don't mirror layouts, so `rtl` means the RTL fixture rendered in the normal layout —
  assert truncation keeps the marker and widths are measured with `lipgloss.Width`/`ansi.StringWidth`, never `len`.
- **Themes**: a TUI that adapts to the terminal background requests it in `Init` (`return tea.RequestBackgroundColor`),
  stores `msg.IsDark()` from `tea.BackgroundColorMsg`, and builds its styles with `lipgloss.LightDark(isDark)(light,
  dark)` in a `Theme` value; tests build `NewTheme(false)` / `NewTheme(true)` directly and capture each
  `.ui-quality.yml` theme, plus **`no-color`** (the styles-free text — what Bubble Tea's colour-profile detection
  leaves under `NO_COLOR`). The text of a light and a dark capture is the same; colour evidence comes from a PNG
  (below) or from asserting the theme's styles. A TUI that doesn't adapt captures `no-color` only, and the report says
  light/dark are "not applicable (single palette)".
- **Variants**: one narrow capture per screen (`--w40`) stands in for large text; contrast and font size belong to
  the terminal.
- **Determinism**: an explicit `tea.WindowSizeMsg`, fixed data, an injected clock, no real timers (send tick
  messages yourself).
- Behaviour assertions run once; captures cover states × directions × themes.

## 5. The capture helper — `internal/tui/ui_screenshots_test.go`

One test file in the TUI package (with several TUI packages, move it to an `internal/uitest` package imported only
by tests). It resolves the directory once and writes the contract's file name — the `.txt` *is* the terminal
screenshot:

```go
// screenshotsDir is UI_SCREENSHOTS_DIR, else <repository root>/.ui-screenshots.
func screenshotsDir(t *testing.T) string {
	t.Helper()
	if dir := os.Getenv("UI_SCREENSHOTS_DIR"); dir != "" {
		return dir
	}
	return filepath.Join(repoRoot(t), ".ui-screenshots")
}

// captureView writes the styles-free view to <dir>/<kind>/<id>/<state>--<direction>--<theme>.txt and returns it.
func captureView(t *testing.T, kind, id, state, direction, theme, view string) string {
	t.Helper()
	text := ansi.Strip(view)
	dir := filepath.Join(screenshotsDir(t), kind, id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatalf("create screenshot directory: %v", err)
	}
	name := filepath.Join(dir, state+"--"+direction+"--"+theme+".txt")
	if err := os.WriteFile(name, []byte(text), 0o600); err != nil {
		t.Fatalf("write screenshot: %v", err)
	}
	return text
}
```

plus `repoRoot` (walks up from the package directory to the nearest `.git`, else the `go.mod` directory),
`loadUIQuality` (reads `.ui-quality.yml` with `strings.Cut` — no YAML module; a missing file or key = `enforce`,
`[ltr, rtl]`, `[light, dark]`) and `checkAccessibility` (§6). The scaffold's version of this file is the reference
(`iru-setup-go-cli` `tui-templates.md`); copy it rather than writing another. A PNG is optional and never produced in
CI: when VHS (`Screenshot` in a `.tape`) or `freeze` is installed locally, render one next to the `.txt` with the same
name.

## 6. Accessibility — what can actually be asserted

There is no automated accessibility checker for terminal UIs (no axe, no ATF). `.ui-quality.yml`'s `accessibility`
applies to these checks, each a rule id the tests compute from the styles-free text and the key map:

| Rule id | Check |
|---|---|
| `tui-color-only` | The selection and every state (error, disabled, loading) are readable in the stripped text — a marker, symbol or word, not only colour, bold or reverse video. |
| `tui-help-line` | Every screen shows its key help (`help.Model` from Bubbles, or a fixed line). |
| `tui-keyboard` | Every intent message the screen can emit has a key binding, asserted by a table test over the key map. |

`NO_COLOR` itself is honoured by Bubble Tea's colour-profile detection — don't add a second switch in the model
(`bubbletea.md`); `tui-color-only` is what keeps such a terminal usable. `checkAccessibility(t, target, violations)`
applies the level: **enforce** → `t.Errorf` listing the violations; **report** → appends `<target>\t<rule-id>:
<message>` lines to `<screenshots dir>/a11y-report.txt` (the test gate counts them); **off** → nothing. The coding
defaults still apply under `off`. Say in the report that these are asserted checks, not an automated audit.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Terminal UI only (Bubble Tea): no colour-only information (pair with symbols/text), honour `NO_COLOR`, keyboard-only operation with a help line, no animation when non-TTY; these are asserted checks, not an audit. Plain CLIs have no UI rules here.

## Sources

- Bubble Tea v2: <https://pkg.go.dev/charm.land/bubbletea/v2> (`Model`, `View`/`NewView` and its `Content` field,
  `KeyPressMsg`, `WindowSizeMsg`, `RequestBackgroundColor`, `BackgroundColorMsg.IsDark`), <https://github.com/charmbracelet/bubbletea>
- Bubbles v2: <https://pkg.go.dev/charm.land/bubbles/v2/list> (`New`, `Item`, `ItemDelegate`, `SetItems`, `SetSize`,
  `Index`, `SelectedItem`), <https://pkg.go.dev/charm.land/bubbles/v2/table>, <https://pkg.go.dev/charm.land/bubbles/v2/viewport>,
  <https://pkg.go.dev/charm.land/bubbles/v2/key>
- Lip Gloss v2: <https://pkg.go.dev/charm.land/lipgloss/v2> (`LightDark`, `HasDarkBackground`, `Width`, `JoinVertical`)
- `github.com/charmbracelet/x/ansi` (`Strip`, `StringWidth`): <https://pkg.go.dev/github.com/charmbracelet/x/ansi>
- teatest v2: <https://pkg.go.dev/github.com/charmbracelet/x/exp/teatest/v2>
- VHS: <https://github.com/charmbracelet/vhs>; freeze: <https://github.com/charmbracelet/freeze>; NO_COLOR: <https://no-color.org>
- irurueta docs: ai/cli-for-agents/tuis-vs-clis.adoc and tty-colour-and-interactivity.adoc —
  <https://www.irurueta.com/ai/cli-for-agents/index.html>
