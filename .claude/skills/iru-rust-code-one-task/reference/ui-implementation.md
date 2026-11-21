# The UI contract on Ratatui

How `ui-contract.md` (read it first) maps onto a Ratatui 0.30 terminal UI (crossterm backend re-exported as
`ratatui::crossterm`). `ratatui.md` keeps the rules about the TTY gate, the event loop, Ctrl-C and terminal
restoration; this file covers structure, collections, UI tests, the text captures and the accessibility checks. It
applies only to a task that adds or changes a screen, component, list or other part of the terminal UI — the plain
CLI commands are governed by `cli-contract.md` alone.

## 1. What a screen and a component are here

| Contract term | Ratatui |
|---|---|
| **Screen** | One full-screen view: a struct holding that screen's UI state, with `handle_key(&mut self, KeyEvent) -> Option<Action>` and a `render(&self, frame: &mut Frame, area: Rect)`. The scaffold's picker (`tui::App` + `tui::ui::render`) is one screen. |
| **Region** | A `Rect` from `Layout::vertical/horizontal([...]).areas(area)` inside the screen's `render`. |
| **Component** | A type implementing `Widget` (stateless; implement it for a borrowed struct so it renders by reference) or `StatefulWidget` (`type State` = scroll/selection the widget updates while drawing). Built per frame from the UI state, never cached. |
| **Event** | A variant of the app's `Action` enum, returned from key handling. |
| **Navigation** | One `enum Screen` in the app model and one `App::update(action)` that switches it — never a screen constructing another screen. |

Composition is nesting: a parent's `render` splits its area into regions and renders child widgets into them; a
component that needs content passed in takes it as a field (`Block` + inner widget, a `Vec<Line>`), never by
wrapping or "extending" another component's type. Rust has no inheritance — don't recreate it with a `BaseScreen`
trait object hierarchy; a small `Screen` trait (`handle_key`, `render`) is fine when the app has several screens.

```rust
/// One order in the order list.
///
/// Inputs: [`OrderRowState`] and whether it is selected. Events: none of its own — the list emits
/// `Action::Open(id)`. States: `default`, `selected`, `long-content` (the title is truncated, the amount kept).
pub struct OrderRow<'a> {
    pub state: &'a OrderRowState,
    pub selected: bool,
    pub theme: &'a Theme,
}

impl Widget for OrderRow<'_> {
    fn render(self, area: Rect, buf: &mut Buffer) {
        let [title_area, amount_area] =
            Layout::horizontal([Constraint::Fill(1), Constraint::Length(10)]).areas(area);
        let marker = if self.selected { "> " } else { "  " }; // visible without colour (§7)
        let style = if self.selected { self.theme.selected } else { self.theme.text };
        Line::from(vec![Span::raw(marker), Span::styled(self.state.title.as_str(), style)])
            .render(title_area, buf);
        Line::from(self.state.amount_text.as_str())
            .alignment(Alignment::Right)
            .render(amount_area, buf);
    }
}
```

`OrderRowState` carries display-ready values (`amount_text` is formatted by the model, not by the widget) and the
item's stable id. A presentational widget never reads files, the clock, the environment or a channel — the screen
(or `App`) owns that and passes state down.

## 2. Inputs and events — unidirectional

- **State down**: the model holds the data; each frame builds widgets from borrowed state.
- **Events up**: the screen's `handle_key` is the only code that sees a raw `KeyEvent`. It maps keys to a typed
  `Action` named after the intent and carrying ids, never indexes into a recycled window:

  ```rust
  /// What the user asked for. The event loop applies it with [`App::update`].
  #[derive(Debug, Clone, PartialEq, Eq)]
  pub enum Action {
      Open(OrderId),
      Retry,
      LoadMore,
      QueryChanged(String),
      Quit,
  }
  ```

  `App::update(&mut self, action)` mutates the state (and starts work through a channel, `ratatui.md`); rendering
  reads the result. A child screen returns `Option<Action>` and the parent decides — events never mutate a parent
  through a shared reference.
- **Two-way binding** only for a text field: its value lives in the model; the field's own `handle_key` edits a
  `TextFieldState` and the screen emits `Action::QueryChanged(text)`. Use an input crate (`tui-input`,
  `tui-textarea`) only if it is already a dependency.

## 3. Collections — only the visible window

`List`+`ListState` and `Table`+`TableState` scroll and highlight, but `List::new(items)` still takes every item you
give it: building a `ListItem` per row for the whole data set every frame is the eager loop the contract forbids. The
collection state holds the whole list (with ids), the selection by id and the scroll offset; `render` turns only the
rows that fit into items:

```rust
/// The order list's state: all rows, the selection (by id, so it survives reloads) and the scroll offset.
#[derive(Debug, Default)]
pub struct OrderListState {
    rows: Vec<OrderRowState>,
    selected: Option<OrderId>,
    offset: usize,
    loading_more: bool,
}

impl OrderListState {
    /// Replaces the rows (a reload or the next page) and keeps the selection when its id still exists.
    pub fn replace(&mut self, rows: Vec<OrderRowState>) {
        if !self.selected.is_some_and(|id| rows.iter().any(|r| r.id == id)) {
            self.selected = rows.first().map(|r| r.id);
        }
        self.rows = rows;
        self.offset = self.offset.min(self.rows.len().saturating_sub(1));
    }

    fn selected_index(&self) -> Option<usize> {
        self.selected.and_then(|id| self.rows.iter().position(|r| r.id == id))
    }

    /// The rows that fit in `height` lines, scrolled so the selection stays visible.
    pub fn window(&mut self, height: usize) -> Range<usize> {
        let selected = self.selected_index().unwrap_or(0);
        if selected < self.offset {
            self.offset = selected;
        } else if height > 0 && selected >= self.offset + height {
            self.offset = selected + 1 - height;
        }
        self.offset..(self.offset + height).min(self.rows.len())
    }
}

/// Renders only the visible rows; the highlight index is relative to the window.
pub fn render_order_list(frame: &mut Frame, area: Rect, list: &mut OrderListState, theme: &Theme) {
    let window = list.window(usize::from(area.height));
    let selected = list.selected_index().map(|i| i - window.start);
    let items = list.rows[window.clone()].iter().map(|row| ListItem::new(row.title.as_str()));
    let widget = List::new(items).highlight_symbol("> ").highlight_style(theme.selected);
    let mut state = ListState::default().with_selected(selected);
    frame.render_stateful_widget(widget, area, &mut state);
    let mut bar = ScrollbarState::new(list.rows.len()).position(window.start);
    frame.render_stateful_widget(Scrollbar::new(ScrollbarOrientation::VerticalRight), area, &mut bar);
}
```

- **Rows with several columns** → `Table::new(rows, widths)` + `TableState`, the same window slicing, `Row::new`
  built only for the window; fixed `Constraint::Length` widths (the contract's "fixed item size").
- **Item types**: one widget per type; a heterogeneous list maps each row state enum variant to its widget.
- **Paging**: when the selection comes within a few rows of the end and `!loading_more`, `handle_key` returns
  `Action::LoadMore`; the page arrives through the loop's channel and `replace`/`extend` keeps ids stable.
- **Many tabs**: `Tabs` shows a window of titles around the active one; only the active tab's content is built.
- Scrolling, selection and expansion live in the collection state keyed by id — never in a widget.

## 4. UI tests

All of them are ordinary unit tests in the TUI module (`#[cfg(test)] mod tests`) — no terminal, no PTY, no lock.

| Level | How |
|---|---|
| Component | Render the widget straight into a buffer: `let mut buf = Buffer::empty(Rect::new(0, 0, 30, 1)); OrderRow { … }.render(buf.area, &mut buf);` then assert `buffer_text(&buf)` and styles (`buf[(x, y)].style()`, `.modifier`). |
| Screen | `Terminal::new(TestBackend::new(w, h))` + `terminal.draw(\|f\| screen.render(f, f.area()))` with fixture state; feed `KeyEvent`s to `handle_key` and assert the returned `Action` and its payload; a disabled affordance returns `None`. |
| Flow | The scaffold's `run_app(terminal, app, scripted_events)` over `TestBackend`: drive a whole flow with a scripted event list, capture each step (`flow/<flow-name>/01-<step>…`), assert the `Outcome`. The real-terminal run stays a PTY smoke check outside CI (`ratatui.md`). |

- Exact layout assertions: `assert_buffer` with an expected `Buffer::with_lines` + `set_style` (`ratatui.md`).
  `insta::assert_snapshot!(buffer_text(...))` is the opt-in baseline comparison — only when `insta` is already a
  dev-dependency; the `.ui-screenshots/` capture is written either way.
- **Data**: `minimal-data`, `typical-data`, `many-items` (thousands of rows — also asserts that an item far below the
  window is *not* in the buffer), `long-content` (a title longer than the width), missing optional values, large
  numbers, and right-to-left text (Arabic or Hebrew).
- **Directions**: terminals don't mirror layouts, so `rtl` means the RTL fixture rendered in the normal layout —
  assert nothing panics, truncation keeps the marker and the amount, and widths are counted by display width
  (`Line::width()`), never `len()`. Ratatui does not reorder bidirectional text; how the terminal shows it varies.
- **Themes**: a TUI whose `Theme` has light and dark palettes (chosen by a flag/config — crossterm cannot query the
  background colour; a detection crate such as `terminal-colorsaurus` is a new dependency the task must allow)
  captures each `.ui-quality.yml` theme it has, plus **`no-color`** (`Theme::no_color()`: modifiers only, used when
  `NO_COLOR` is set or `--no-color`). A TUI with no colour palette at all captures `no-color` only, and the report
  says light/dark are "not applicable (no colour palette)".
- **Variants**: one narrow capture per screen (`--w40`) is the terminal stand-in for large text; high contrast and
  font scaling belong to the terminal, not the app.
- **Determinism**: fixed `TestBackend` size, fixed data, an injected clock, spinner ticks set explicitly.
- Behaviour assertions run once; captures cover states × directions × themes.

## 5. The capture helper — `src/tui/ui_screenshots.rs`

One test-only file in the TUI module (`#[cfg(test)] pub(crate) mod ui_screenshots;` in `src/tui/mod.rs`); every UI
test uses it. It writes the rendered buffer as text with the contract's file name — the `.txt` *is* the terminal
screenshot:

```rust
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::{env, fs};

use ratatui::buffer::Buffer;
use ratatui::text::Span;

fn repo_root() -> PathBuf {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    manifest.ancestors().find(|d| d.join(".git").exists()).unwrap_or(manifest).to_path_buf()
}

/// `UI_SCREENSHOTS_DIR` when set, else `<repository root>/.ui-screenshots`.
pub(crate) fn screenshots_dir() -> PathBuf {
    env::var_os("UI_SCREENSHOTS_DIR").map_or_else(|| repo_root().join(".ui-screenshots"), PathBuf::from)
}

/// The buffer's text without styles, one line per row (wide characters counted once).
pub(crate) fn buffer_text(buffer: &Buffer) -> String {
    let mut text = String::new();
    for row in buffer.content.chunks(usize::from(buffer.area.width).max(1)) {
        let mut skip = 0;
        for cell in row {
            if skip == 0 {
                text.push_str(cell.symbol());
            }
            skip = skip.max(Span::raw(cell.symbol()).width()).saturating_sub(1);
        }
        text.push('\n');
    }
    text
}

/// Writes `<dir>/<kind>/<id>/<state>--<direction>--<theme>.txt` and returns the text.
pub(crate) fn capture(kind: &str, id: &str, state: &str, direction: &str, theme: &str, buffer: &Buffer) -> String {
    let dir = screenshots_dir().join(kind).join(id);
    fs::create_dir_all(&dir).expect("create the screenshot directory");
    let text = buffer_text(buffer);
    fs::write(dir.join(format!("{state}--{direction}--{theme}.txt")), &text).expect("write the screenshot");
    text
}
```

plus `ui_quality()` (reads `.ui-quality.yml` at the repository root with a few line splits — no YAML crate; a missing
file or key = `enforce`, `[ltr, rtl]`, `[light, dark]`) and `check_accessibility(target, &violations)` (§6). The
scaffold's version of this file is the reference (`iru-setup-rust-cli` `tui-templates.md`); copy it rather than
writing another. A PNG is optional and never produced in CI: when VHS (`Screenshot` in a `.tape` that runs the
binary) or `freeze` is installed locally, render one next to the `.txt` with the same name.

## 6. Accessibility — what can actually be asserted

There is no automated accessibility checker for terminal UIs (no axe, no ATF). `.ui-quality.yml`'s `accessibility`
applies to these checks, each a rule id the tests compute from the styles-free text and the key map:

| Rule id | Check |
|---|---|
| `tui-color-only` | The selection and every state (error, disabled, loading) are readable in `buffer_text` — a marker, symbol or word, not only colour or `REVERSED`. |
| `tui-help-line` | Every screen shows its key help (or a `?` hint that opens it). |
| `tui-keyboard` | Every `Action` the screen offers has a key binding, asserted by a test that walks the key map. |
| `tui-no-color` | Under `Theme::no_color()` no cell has a foreground/background colour (`cell.fg == Color::Reset`). |

`check_accessibility(target, &violations)` applies the level: **enforce** → the test panics listing the
violations; **report** → appends `<target>\t<rule-id>: <message>` lines to `<screenshots dir>/a11y-report.txt`
(the test gate counts them); **off** → nothing. The coding defaults still apply under `off`: keyboard-only
operation, a visible help line, no colour-only information, `NO_COLOR` honoured. Say in the report that these are
asserted checks, not an automated audit.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Terminal UI only (Ratatui): no colour-only information (pair with symbols/text), honour `NO_COLOR`, keyboard-only operation with a help line, no animation when `--no-input`/non-TTY; these are asserted checks, not an audit. Plain CLIs have no UI rules here.

## Sources

- Ratatui 0.30: <https://docs.rs/ratatui/0.30.2> (`Widget`, `StatefulWidget`, `List`/`ListState`, `Table`/
  `TableState`, `Scrollbar`, `Tabs`, `Buffer`, `backend::TestBackend`), <https://ratatui.rs/concepts/widgets/>,
  <https://ratatui.rs/concepts/application-patterns/the-elm-architecture/>,
  <https://ratatui.rs/concepts/application-patterns/component-architecture/>, <https://ratatui.rs/recipes/testing/snapshots/>
- `ratatui-core` `TestBackend` source (`buffer_view`, the wide-character skip this helper mirrors):
  <https://docs.rs/ratatui-core/latest/src/ratatui_core/backend/test.rs.html>
- insta: <https://insta.rs>; VHS: <https://github.com/charmbracelet/vhs>; freeze: <https://github.com/charmbracelet/freeze>
- NO_COLOR: <https://no-color.org>; irurueta docs: ai/cli-for-agents/tuis-vs-clis.adoc and
  tty-colour-and-interactivity.adoc — <https://www.irurueta.com/ai/cli-for-agents/index.html>
