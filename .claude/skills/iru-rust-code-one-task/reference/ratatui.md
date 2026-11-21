# Ratatui terminal UI

A full-screen UI is a **view for people** over the same core the CLI commands use (irurueta docs:
ai/cli-for-agents/tuis-vs-clis.adoc, `cli-contract.md` §3). Verified with ratatui 0.30.2 (crossterm 0.29 backend).

## Rules that don't bend

1. **Its own subcommand** (`browse`, `ui`), never the default action, and it starts only when stdin **and** stdout are
   terminals and no machine format was asked for — otherwise exit 2 with a hint naming the non-interactive command:

   ```rust
   pub fn ensure_interactive(ctx: &Ctx<'_>) -> Result<(), CliError> {
       if ctx.ttys.stdin && ctx.ttys.stdout && !ctx.format.is_machine() {
           return Ok(());
       }
       Err(CliError::new(
           Exit::Usage,
           "browse needs an interactive terminal",
           "Use `democli greet NAME --json` instead.",
       ))
   }
   ```

2. **Every action it offers has a non-interactive command with `--json`.** A task that adds a TUI action without the
   equivalent command is incomplete — add both or report the gap. The `schema` command marks the TUI command
   `interactiveOnly`.
3. **Destructive actions go through the same plan/confirm path** as the CLI (`--dry-run` logic, the same domain
   function); the TUI is never a way around a confirmation.
4. **The terminal is always restored** — on quit, on error, on panic, on Ctrl-C.

## Structure: state, update, render, loop

Keep the state and its transitions pure; only the loop touches the terminal. When the task adds or changes a screen,
component or list, also read `ui-contract.md` and `ui-implementation.md` — screens/components as `Widget`s, key
handling that returns a typed `Action`, collections that render only the visible window, the UI-test matrix, the
`.ui-screenshots/` text captures and the accessibility checks. A one-screen picker like the one below is the minimal
shape:

```rust
/// The browser's state. Rendering reads it; [`App::handle_key`] is the only mutator.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct App {
    items: Vec<String>,
    selected: usize,
    quit: bool,
}

impl App {
    /// Applies one key press. Releases/repeats are ignored (Windows reports both press and release).
    pub fn handle_key(&mut self, key: KeyEvent) {
        if key.kind != KeyEventKind::Press {
            return;
        }
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => self.quit = true,
            KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => self.quit = true,
            KeyCode::Down | KeyCode::Char('j') => {
                self.selected = (self.selected + 1).min(self.items.len().saturating_sub(1));
            }
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            _ => {}
        }
    }

    /// Draws the whole screen from the state.
    pub fn render(&self, frame: &mut Frame<'_>) {
        let [list_area, help_area] =
            Layout::vertical([Constraint::Min(1), Constraint::Length(1)]).areas(frame.area());
        // Only the rows that fit become items (ui-implementation.md §3) — never the whole data set.
        let rows = usize::from(list_area.height.saturating_sub(2)); // inside the border
        let start = self.selected.saturating_sub(rows.saturating_sub(1)); // keep the selection visible
        let items: Vec<ListItem<'_>> = self
            .items
            .iter()
            .skip(start)
            .take(rows)
            .map(|i| ListItem::new(i.as_str()))
            .collect();
        let list = List::new(items)
            .block(Block::bordered().title("Greetings"))
            .highlight_style(Style::new().add_modifier(Modifier::REVERSED))
            .highlight_symbol("> ");
        let mut state = ListState::default().with_selected(Some(self.selected - start));
        frame.render_stateful_widget(list, list_area, &mut state);
        frame.render_widget(Paragraph::new(Line::from("j/k move, q quit")), help_area);
    }
}

/// Runs the event loop on a real terminal until the user quits.
///
/// Call it through [`ratatui::run`], which enters raw mode and the alternate screen, installs a
/// panic hook that restores the terminal, and restores it on return.
///
/// # Errors
///
/// Returns the terminal's I/O error.
pub fn run_loop(terminal: &mut DefaultTerminal, app: &mut App) -> io::Result<()> {
    while !app.should_quit() {
        terminal.draw(|frame| app.render(frame))?;
        if let Event::Key(key) = event::read()? {
            app.handle_key(key);
        }
    }
    Ok(())
}
```

and the command starts it with `ratatui::run(|terminal| run_loop(terminal, &mut app))?;` after the TTY gate.

- **Imports**: `use ratatui::crossterm::event::{…}` — the re-export guarantees the crossterm version ratatui was
  built against (0.29 for ratatui 0.30). A separate `crossterm` dependency of a different version compiles but its
  events and ratatui's backend won't agree.
- **`ratatui::run(f)`** (0.30) takes `FnOnce(&mut DefaultTerminal) -> R`: it calls `init()` (raw mode, alternate
  screen, panic hook that restores the terminal), runs `f`, then `restore()`. Use `ratatui::try_init()` +
  `ratatui::restore()` only when the code needs the terminal across function boundaries — and then restore on every
  return path. Install any other panic hook (e.g. color-eyre) **before** `init`, or it replaces the restoring one.
- **Ctrl-C in raw mode is a key event**, not SIGINT: handle `KeyCode::Char('c')` + `KeyModifiers::CONTROL` as quit
  (and, if the UI is running long work, as cancellation of that work). Exit 0 for a normal quit.
- **Windows reports press and release** for each key: filter `KeyEventKind::Press`, or every key acts twice.
- **Time-based updates** (spinners, refresh): `event::poll(Duration::from_millis(250))?` before `event::read()`, and
  a tick in the state — never a busy loop. Long work runs on a thread or task and reports back through a channel the
  loop drains; the UI never blocks on I/O.
- **Layout** from `Layout::vertical/horizontal([...]).areas(area)` with `Constraint::{Length, Min, Percentage,
  Fill}`; widgets are built per frame from state (immediate mode) — don't cache widgets in the state.
- Colours: use `Style`/`Color` from ratatui through one `Theme` value; respect `NO_COLOR` by choosing
  `Theme::no_color()` from the same colour policy (`output-and-terminal.md`). Modifiers like `REVERSED`/`BOLD` help,
  but the selection and every state also need a marker or word — no information by colour alone
  (`ui-implementation.md` §6).

## Testing with `TestBackend`

`handle_key` is tested with plain `KeyEvent`s; `render` with a `Terminal<TestBackend>`. These are the behaviour
assertions; the contract's state × direction × theme matrix, the `.ui-screenshots/` `.txt` captures (through
`src/tui/ui_screenshots.rs`) and the accessibility checks are in `ui-implementation.md` §4–§6:

```rust
#[test]
fn renders_the_list_with_the_selection_marked() {
    let mut terminal = Terminal::new(TestBackend::new(22, 5)).unwrap();
    terminal.draw(|frame| app().render(frame)).unwrap();
    // Buffers compare styles too: build the expected one and apply the highlight explicitly.
    let mut expected = Buffer::with_lines([
        "┌Greetings───────────┐",
        "│> Hello, Ada!       │",
        "│  Hello, Linus!     │",
        "└────────────────────┘",
        "j/k move, q quit      ",
    ]);
    expected.set_style(Rect::new(1, 1, 20, 1), Style::new().add_modifier(Modifier::REVERSED));
    terminal.backend().assert_buffer(&expected);
}

#[test]
fn quits_on_q_esc_and_ctrl_c_but_ignores_releases() {
    let mut app = app();
    let mut release = KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE);
    release.kind = KeyEventKind::Release;
    app.handle_key(release);
    assert!(!app.should_quit());
}
```

(`use ratatui::{Terminal, backend::TestBackend, buffer::Buffer, layout::Rect};`)

- **`assert_buffer_lines([...])` compares styles as well as text** (verified): it fails on any highlighted cell
  because the expected lines carry the default style. Build the expected `Buffer::with_lines`, apply the styles the
  widget sets with `set_style`, and call `assert_buffer`. For text-only assertions, `insta::assert_snapshot!(
  terminal.backend())` (`TestBackend` implements `Display`) — only if insta is already a dev-dependency.
- Use small fixed sizes (20–40 columns) so expected buffers stay readable; add one test at a narrow size when the
  layout has a minimum.
- Test the TTY gate with an injected `Ttys` (`refuses_without_a_terminal`), and the end-to-end refusal
  (`democli browse` with closed stdin → exit 2, stdout empty, hint on stderr).
- `run_loop` and the `ratatui::run` call are the only lines no test reaches (they need a real terminal). Keep them
  this small so the file stays above the 80% bar; the group reports them as a terminal-bound exception if it doesn't.
  (Verified separately under a pseudo-terminal at 80×24: the UI renders, `q` exits 0, and the alternate screen is
  entered and left. A pty with a 0×0 window size renders nothing — set the size when scripting such a check.)
