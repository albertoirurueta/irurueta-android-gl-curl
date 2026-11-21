# Terminal UI templates (`ui: tui` only)

The `browse` command and the Ratatui picker. State and key handling are pure (`tui/app.rs`), drawing is a function of
the state (`tui/ui.rs`), and the event loop takes the terminal and the event source as parameters (`tui/mod.rs`) —
so all of it is unit-tested with `TestBackend` and scripted events. Only `browse::run` (raw mode on the real
terminal) is left to the PTY smoke check in SKILL.md Step 7.

The picker follows the catalog's UI contract (`iru-rust-code-one-task` `reference/ui-contract.md`): the list builds
items only for the rows that fit (`App::visible_window`), and `tui/ui.rs`'s tests render every state (`default`,
`empty`, `many-items`, `long-content`) in each `.ui-quality.yml` direction (`rtl` = Arabic/Hebrew names in the normal
layout), write each frame as a text capture through `src/tui/ui_screenshots.rs` to
`.ui-screenshots/screen/App/<state>--<direction>--no-color.txt`, and run the asserted accessibility checks at the
file's level. The picker has no colour palette (a `> ` marker and reverse video only), so `no-color` is its only
theme — light/dark captures would be identical.

**UI additions unverified**: the `.ui-quality.yml`, `ui_screenshots.rs`, `visible_window` and matrix-test parts were
added after the 2026-10-16 verification without a Rust toolchain on the authoring machine; the first scaffold run's
Step 7 is their first compile — fix anything it finds here.

Conditional markers (`// >>> <condition>` … `// <<< <condition>` in Rust, `# >>> …` in TOML) and the `__Token__`
placeholders work as described in SKILL.md's "Reference files" section; the marker lines themselves are never
written. A heading suffixed `(condition)` is written only when the condition holds. Every file below was rendered
and verified on 2026-10-16 for `ui: none` and `ui: tui` (Rust 1.99.1; MSRV toolchains 1.85.0 for `ui: none`, 1.88.0 for `ui: tui`) — see SKILL.md
"Verification notes".

### `src/commands/browse.rs` (ui: tui)

```rust
//! `browse [NAMES]...` — a full-screen picker for people. Agents and scripts use `greet NAME --json`.

use std::time::Duration;

use clap::Args;
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEvent, KeyModifiers};

use crate::commands::greet::{greeting, write_greeting};
use crate::error::CliError;
use crate::exit::Exit;
use crate::tui::{App, Outcome, run_app};
use crate::{BIN_NAME, Context, Session};

/// Arguments of `browse`.
#[derive(Debug, Args)]
pub struct BrowseArgs {
    /// Names to choose from.
    #[arg(value_name = "NAMES", default_values = ["Ada", "Grace", "Linus", "Margaret"])]
    pub names: Vec<String>,
}

/// Checks everything `browse` needs before taking over the terminal.
///
/// # Errors
///
/// Exit 2 when stdin/stdout aren't terminals or prompts are disabled; exit 3 for an invalid name.
pub fn preflight(args: &BrowseArgs, ctx: &Context<'_>, session: &Session) -> Result<App, CliError> {
    if !(ctx.terminal.stdin && ctx.terminal.stdout) || session.settings.no_input {
        return Err(CliError::usage(
            "browse needs an interactive terminal (stdin and stdout) and input enabled.",
            format!("Use `{BIN_NAME} greet NAME --json` instead."),
        ));
    }
    for name in &args.names {
        greeting(name)?;
    }
    Ok(App::new(args.names.clone()))
}

/// Runs `browse`: the picker on the real terminal, then the chosen greeting on stdout.
///
/// # Errors
///
/// See [`preflight`]; plus exit 130 on Ctrl-C and the I/O error of the terminal or the write.
pub fn run(args: &BrowseArgs, ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    let mut app = preflight(args, ctx, session)?;
    // SIGTERM/SIGINT must not kill the process while the terminal is in raw mode: defer them, poll for events
    // with a timeout, and turn a recorded signal into the same exit path as Ctrl-C.
    let _deferred = ctx.signals.defer();
    let signals = ctx.signals.clone();
    let next_event = move || {
        loop {
            if signals.received().is_some() {
                return Ok(Event::Key(KeyEvent::new(
                    KeyCode::Char('c'),
                    KeyModifiers::CONTROL,
                )));
            }
            if event::poll(Duration::from_millis(100))? {
                return event::read();
            }
        }
    };
    // `ratatui::run` enters raw mode and the alternate screen and restores both afterwards — also on a panic,
    // through the hook `ratatui::init` installs.
    let outcome = ratatui::run(|terminal| run_app(terminal, &mut app, next_event))?;
    finish(outcome, ctx, session)
}

/// Turns the picker's outcome into output and an exit code.
///
/// # Errors
///
/// Exit 130 for Ctrl-C, or the I/O error of the write.
pub fn finish(outcome: Outcome, ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    match outcome {
        Outcome::Selected(name) => write_greeting(ctx, session, &greeting(&name)?),
        Outcome::Quit => Ok(()),
        Outcome::Interrupted => Err(CliError::new(Exit::Interrupted, "Interrupted.")),
    }
}
```

### `.ui-quality.yml` (ui: tui)

`__Accessibility__` = the `accessibility` input (`enforce` by default). Exactly the contract's shape:

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: __Accessibility__      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

### `src/tui/mod.rs` (ui: tui)

```rust
//! The full-screen picker behind `browse`: state ([`app`]), drawing ([`ui`]) and the event loop.
//!
//! The loop takes the terminal and the event source as parameters, so tests drive it with ratatui's
//! `TestBackend` and a scripted list of key events — no real terminal needed.

pub mod app;
pub mod ui;
#[cfg(test)]
pub(crate) mod ui_screenshots;

use std::io;

use ratatui::Terminal;
use ratatui::backend::Backend;
use ratatui::crossterm::event::Event;

pub use app::{App, Outcome};

/// Draws `app` and feeds it events until it produces an [`Outcome`].
///
/// # Errors
///
/// Returns the error of drawing or of reading the next event.
pub fn run_app<B: Backend>(
    terminal: &mut Terminal<B>,
    app: &mut App,
    mut next_event: impl FnMut() -> io::Result<Event>,
) -> io::Result<Outcome> {
    loop {
        terminal
            .draw(|frame| ui::render(frame, app))
            .map_err(|e| io::Error::other(e.to_string()))?;
        if let Event::Key(key) = next_event()?
            && let Some(outcome) = app.handle_key(key)
        {
            return Ok(outcome);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::io;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;
    use ratatui::crossterm::event::{Event, KeyCode, KeyEvent};

    use super::{App, Outcome, run_app};

    fn script(events: Vec<Event>) -> impl FnMut() -> io::Result<Event> {
        let mut queue: VecDeque<Event> = events.into();
        move || {
            queue
                .pop_front()
                .ok_or_else(|| io::Error::other("no more events"))
        }
    }

    #[test]
    fn moving_down_and_pressing_enter_selects_the_second_name() {
        let mut terminal = Terminal::new(TestBackend::new(40, 10)).expect("terminal");
        let mut app = App::new(vec!["Ada".into(), "Grace".into()]);
        let events = vec![
            Event::FocusGained,
            Event::Key(KeyEvent::from(KeyCode::Down)),
            Event::Key(KeyEvent::from(KeyCode::Enter)),
        ];
        let outcome = run_app(&mut terminal, &mut app, script(events)).expect("run");
        assert_eq!(outcome, Outcome::Selected("Grace".into()));
    }

    #[test]
    fn event_errors_propagate() {
        let mut terminal = Terminal::new(TestBackend::new(40, 10)).expect("terminal");
        let mut app = App::new(vec!["Ada".into()]);
        assert!(run_app(&mut terminal, &mut app, script(Vec::new())).is_err());
    }
}
```

### `src/tui/app.rs` (ui: tui)

```rust
//! Picker state and key handling — pure logic, no terminal.

use std::ops::Range;

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::widgets::ListState;

/// How the picker ended.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The user chose this name (Enter).
    Selected(String),
    /// The user left without choosing (`q` or Esc).
    Quit,
    /// The user pressed Ctrl-C (the terminal is in raw mode, so it arrives as a key, not as SIGINT).
    Interrupted,
}

/// The picker's state: the names and the highlighted row.
#[derive(Clone, Debug)]
pub struct App {
    names: Vec<String>,
    state: ListState,
}

impl App {
    /// A picker over `names`, highlighting the first one.
    #[must_use]
    pub fn new(names: Vec<String>) -> Self {
        let selected = if names.is_empty() { None } else { Some(0) };
        Self {
            names,
            state: ListState::default().with_selected(selected),
        }
    }

    /// The names shown.
    #[must_use]
    pub fn names(&self) -> &[String] {
        &self.names
    }

    /// The highlighted name, if any.
    #[must_use]
    pub fn selected(&self) -> Option<&str> {
        self.state
            .selected()
            .and_then(|i| self.names.get(i))
            .map(String::as_str)
    }

    /// The index of the highlighted name, if any.
    #[must_use]
    pub fn selected_index(&self) -> Option<usize> {
        self.state.selected()
    }

    /// The names that fit in `rows` visible rows, scrolled so the highlighted name stays visible — rendering builds
    /// list items only for this range, never for every name.
    pub fn visible_window(&mut self, rows: usize) -> Range<usize> {
        if rows == 0 {
            return 0..0;
        }
        let selected = self.state.selected().unwrap_or(0);
        let offset = self.state.offset();
        let offset = if selected < offset {
            selected
        } else if selected >= offset + rows {
            selected + 1 - rows
        } else {
            offset
        };
        *self.state.offset_mut() = offset;
        offset..(offset + rows).min(self.names.len())
    }

    /// Applies one key press; returns the outcome when the picker should close.
    pub fn handle_key(&mut self, key: KeyEvent) -> Option<Outcome> {
        if key.kind != KeyEventKind::Press {
            return None;
        }
        match key.code {
            KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                Some(Outcome::Interrupted)
            }
            KeyCode::Char('q') | KeyCode::Esc => Some(Outcome::Quit),
            KeyCode::Enter => self
                .selected()
                .map(|name| Outcome::Selected(name.to_owned())),
            KeyCode::Down | KeyCode::Char('j') => {
                self.move_by(1);
                None
            }
            KeyCode::Up | KeyCode::Char('k') => {
                self.move_by(-1);
                None
            }
            _ => None,
        }
    }

    fn move_by(&mut self, delta: isize) {
        let Some(last) = self.names.len().checked_sub(1) else {
            return;
        };
        let current = self.state.selected().unwrap_or(0);
        let next = current.saturating_add_signed(delta).min(last);
        self.state.select(Some(next));
    }
}

#[cfg(test)]
mod tests {
    use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};

    use super::{App, Outcome};

    fn press(code: KeyCode) -> KeyEvent {
        KeyEvent::from(code)
    }

    #[test]
    fn navigation_stays_in_bounds() {
        let mut app = App::new(vec!["Ada".into(), "Grace".into()]);
        assert_eq!(app.selected(), Some("Ada"));
        assert_eq!(app.handle_key(press(KeyCode::Up)), None);
        assert_eq!(app.selected(), Some("Ada"));
        app.handle_key(press(KeyCode::Char('j')));
        app.handle_key(press(KeyCode::Down));
        assert_eq!(app.selected(), Some("Grace"));
        app.handle_key(press(KeyCode::Char('k')));
        assert_eq!(app.selected(), Some("Ada"));
        assert_eq!(app.handle_key(press(KeyCode::Char('x'))), None);
        assert_eq!(app.names().len(), 2);
    }

    #[test]
    fn enter_quit_and_ctrl_c_close_the_picker() {
        let mut app = App::new(vec!["Ada".into()]);
        assert_eq!(
            app.handle_key(press(KeyCode::Enter)),
            Some(Outcome::Selected("Ada".into()))
        );
        assert_eq!(
            app.handle_key(press(KeyCode::Char('q'))),
            Some(Outcome::Quit)
        );
        assert_eq!(app.handle_key(press(KeyCode::Esc)), Some(Outcome::Quit));
        let ctrl_c = KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL);
        assert_eq!(app.handle_key(ctrl_c), Some(Outcome::Interrupted));
        let release =
            KeyEvent::new_with_kind(KeyCode::Enter, KeyModifiers::NONE, KeyEventKind::Release);
        assert_eq!(app.handle_key(release), None);
    }

    #[test]
    fn an_empty_list_selects_nothing() {
        let mut app = App::new(Vec::new());
        app.handle_key(press(KeyCode::Down));
        assert_eq!(app.selected(), None);
        assert_eq!(app.handle_key(press(KeyCode::Enter)), None);
    }

    #[test]
    fn the_visible_window_follows_the_selection() {
        let mut app = App::new((0..10).map(|i| format!("Name {i}")).collect());
        assert_eq!(app.visible_window(3), 0..3);
        for _ in 0..5 {
            app.handle_key(press(KeyCode::Down));
        }
        assert_eq!(app.selected_index(), Some(5));
        assert_eq!(app.visible_window(3), 3..6);
        for _ in 0..4 {
            app.handle_key(press(KeyCode::Up));
        }
        assert_eq!(app.visible_window(3), 1..4);
        assert_eq!(app.visible_window(3), 1..4);
        assert_eq!(app.visible_window(0), 0..0);
        assert_eq!(App::new(Vec::new()).visible_window(3), 0..0);
    }
}
```

### `src/tui/ui.rs` (ui: tui)

```rust
//! Drawing the picker (screen `App`): a bordered list and a one-line key help.
//!
//! States: `default`, `empty`, `many-items`, `long-content`. The selection is marked with `> ` as well as reverse
//! video, so it stays visible without colour.

use ratatui::Frame;
use ratatui::layout::{Constraint, Layout};
use ratatui::style::{Modifier, Style};
use ratatui::widgets::{Block, List, ListState, Paragraph};

use super::App;

/// The key help shown on the last line.
pub(crate) const HELP: &str = "Up/Down move  Enter select  q quit";

/// Renders `app` into the whole frame, turning only the names that fit into list items.
pub fn render(frame: &mut Frame, app: &mut App) {
    let [list_area, help_area] =
        Layout::vertical([Constraint::Min(1), Constraint::Length(1)]).areas(frame.area());
    let window = app.visible_window(usize::from(list_area.height.saturating_sub(2)));
    let selected = app.selected_index().map(|i| i.saturating_sub(window.start));
    let list = List::new(app.names()[window].iter().map(String::as_str))
        .block(Block::bordered().title(" Pick a name "))
        .highlight_style(Style::new().add_modifier(Modifier::REVERSED))
        .highlight_symbol("> ");
    let mut state = ListState::default().with_selected(selected);
    frame.render_stateful_widget(list, list_area, &mut state);
    frame.render_widget(Paragraph::new(HELP), help_area);
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::{HELP, render};
    use crate::tui::App;
    use crate::tui::ui_screenshots::{capture, check_accessibility, tui_violations, ui_quality};

    /// The names for one state of the UI contract's matrix; `rtl` uses right-to-left scripts.
    fn names(state: &str, direction: &str) -> Vec<String> {
        let base = if direction == "rtl" {
            ["عائشة", "מרים", "ليلى", "דוד"]
        } else {
            ["Ada", "Grace", "Linus", "Margaret"]
        };
        match state {
            "empty" => Vec::new(),
            "many-items" => (1..=500_usize)
                .map(|i| format!("{} {i}", base[i % base.len()]))
                .collect(),
            "long-content" => vec![base[0].repeat(20), base[1].to_owned()],
            _ => Vec::from(base.map(str::to_owned)),
        }
    }

    #[test]
    fn captures_every_state_in_each_direction() {
        let quality = ui_quality();
        for direction in &quality.directions {
            for state in ["default", "empty", "many-items", "long-content"] {
                let mut app = App::new(names(state, direction));
                let mut terminal = Terminal::new(TestBackend::new(40, 8)).expect("terminal");
                terminal
                    .draw(|frame| render(frame, &mut app))
                    .expect("draw");
                // No colour palette (a marker and reverse video only): `no-color` is the picker's one theme.
                let text = capture(
                    "screen",
                    "App",
                    state,
                    direction,
                    "no-color",
                    terminal.backend().buffer(),
                );
                let selection = (state != "empty").then_some("> ");
                check_accessibility(
                    &format!("screen/App/{state}--{direction}--no-color"),
                    &tui_violations(&text, selection, HELP),
                );
                if state == "many-items" {
                    assert!(!text.contains(" 500"), "only the visible rows are rendered");
                }
            }
        }
    }

    #[test]
    fn draws_the_list_with_the_selection_marked() {
        let mut terminal = Terminal::new(TestBackend::new(30, 6)).expect("terminal");
        let mut app = App::new(vec!["Ada".into(), "Grace".into()]);
        terminal
            .draw(|frame| render(frame, &mut app))
            .expect("draw");
        let text: String = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect();
        assert!(text.contains("Pick a name"));
        assert!(text.contains("> Ada"));
        assert!(text.contains("  Grace"));
        assert!(text.contains("Enter select"));
    }
}
```

### `src/tui/ui_screenshots.rs` (ui: tui)

The one capture helper every UI test uses (test-only: declared `#[cfg(test)]` in `src/tui/mod.rs`).

```rust
//! Test-only support for the UI contract: text captures of rendered buffers and the accessibility level.
//!
//! Captures go to `UI_SCREENSHOTS_DIR` (else `<repository root>/.ui-screenshots`) as
//! `<kind>/<Id>/<state>--<direction>--<theme>.txt`; `.ui-quality.yml` at the repository root sets the
//! accessibility level (`enforce`, `report`, `off`, `none`) and the directions and themes the tests render.

use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::{env, fs};

use ratatui::buffer::Buffer;
use ratatui::text::Span;

/// The `.ui-quality.yml` settings; a missing file or key means the contract's defaults.
pub(crate) struct UiQuality {
    /// `enforce`, `report` or `off`.
    pub(crate) accessibility: String,
    /// The directions to capture (`ltr`, `rtl`).
    pub(crate) directions: Vec<String>,
    /// The themes to capture where the UI has a palette for them (`light`, `dark`).
    pub(crate) themes: Vec<String>,
}

fn repo_root() -> PathBuf {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    manifest
        .ancestors()
        .find(|dir| dir.join(".git").exists())
        .unwrap_or(manifest)
        .to_path_buf()
}

/// `UI_SCREENSHOTS_DIR` when set, else `<repository root>/.ui-screenshots`.
pub(crate) fn screenshots_dir() -> PathBuf {
    env::var_os("UI_SCREENSHOTS_DIR")
        .map_or_else(|| repo_root().join(".ui-screenshots"), PathBuf::from)
}

/// Reads `.ui-quality.yml` at the repository root.
pub(crate) fn ui_quality() -> UiQuality {
    parse_ui_quality(&fs::read_to_string(repo_root().join(".ui-quality.yml")).unwrap_or_default())
}

fn parse_ui_quality(text: &str) -> UiQuality {
    let value = |key: &str| {
        text.lines().find_map(|line| {
            let line = line.split('#').next().unwrap_or_default();
            let (name, value) = line.split_once(':')?;
            (name.trim() == key).then(|| value.trim().to_owned())
        })
    };
    let list = |key: &str, default: [&str; 2]| {
        value(key).map_or_else(
            || Vec::from(default.map(str::to_owned)),
            |items| {
                items
                    .trim_matches(['[', ']'])
                    .split(',')
                    .map(|item| item.trim().to_owned())
                    .filter(|item| !item.is_empty())
                    .collect()
            },
        )
    };
    UiQuality {
        accessibility: value("accessibility").unwrap_or_else(|| "enforce".to_owned()),
        directions: list("directions", ["ltr", "rtl"]),
        themes: list("themes", ["light", "dark"]),
    }
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

/// Writes `<dir>/<kind>/<id>/<state>--<direction>--<theme>.txt` and returns the captured text.
pub(crate) fn capture(
    kind: &str,
    id: &str,
    state: &str,
    direction: &str,
    theme: &str,
    buffer: &Buffer,
) -> String {
    let dir = screenshots_dir().join(kind).join(id);
    fs::create_dir_all(&dir).expect("create the screenshot directory");
    let text = buffer_text(buffer);
    fs::write(dir.join(format!("{state}--{direction}--{theme}.txt")), &text)
        .expect("write the screenshot");
    text
}

/// The asserted terminal-UI accessibility checks on a styles-free capture, as `rule-id: message`.
pub(crate) fn tui_violations(text: &str, selection: Option<&str>, help: &str) -> Vec<String> {
    let mut violations = Vec::new();
    if selection.is_some_and(|marker| !text.contains(marker)) {
        violations.push("tui-color-only: the selection has no marker visible without colour".to_owned());
    }
    if !text.contains(help) {
        violations.push("tui-help-line: the key help is not on screen".to_owned());
    }
    violations
}

/// Applies the `.ui-quality.yml` level to the violations found for `target` (`<kind>/<Id>/<file stem>`).
pub(crate) fn check_accessibility(target: &str, violations: &[String]) {
    apply_level(&ui_quality().accessibility, target, violations, &screenshots_dir());
}

fn apply_level(level: &str, target: &str, violations: &[String], dir: &Path) {
    if violations.is_empty() || level == "off" || level == "none" {
        return;
    }
    if level == "report" {
        fs::create_dir_all(dir).expect("create the screenshot directory");
        let mut report = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join("a11y-report.txt"))
            .expect("open the accessibility report");
        for violation in violations {
            writeln!(report, "{target}\t{violation}").expect("write the accessibility report");
        }
        return;
    }
    panic!("accessibility violations in {target}: {violations:?}");
}

mod tests {
    use std::fs;
    use std::path::Path;

    use ratatui::buffer::Buffer;

    use super::{apply_level, buffer_text, parse_ui_quality, tui_violations};

    #[test]
    fn parses_the_quality_file_and_falls_back_to_the_defaults() {
        let quality = parse_ui_quality("accessibility: report  # note\ndirections: [ltr]\n");
        assert_eq!(quality.accessibility, "report");
        assert_eq!(quality.directions, ["ltr"]);
        assert_eq!(quality.themes, ["light", "dark"]);
        assert_eq!(parse_ui_quality("").accessibility, "enforce");
    }

    #[test]
    fn buffer_text_counts_wide_characters_once() {
        let buffer = Buffer::with_lines(["日本", "ab"]);
        assert_eq!(buffer_text(&buffer), "日本\nab  \n");
    }

    #[test]
    fn finds_a_colour_only_selection_and_a_missing_help_line() {
        assert!(tui_violations("> Ada\nhelp", Some("> "), "help").is_empty());
        assert_eq!(tui_violations("  Ada", Some("> "), "help").len(), 2);
        assert!(tui_violations("help", None, "help").is_empty());
    }

    #[test]
    fn report_appends_and_off_ignores() {
        let dir = tempfile::tempdir().expect("temp dir");
        let violations = vec!["tui-help-line: missing".to_owned()];
        apply_level("off", "screen/App/x", &violations, dir.path());
        assert!(!dir.path().join("a11y-report.txt").exists());
        apply_level("report", "screen/App/x", &violations, dir.path());
        let report = fs::read_to_string(dir.path().join("a11y-report.txt")).expect("report");
        assert_eq!(report, "screen/App/x\ttui-help-line: missing\n");
        apply_level("enforce", "screen/App/x", &[], dir.path());
    }

    #[test]
    #[should_panic(expected = "accessibility violations")]
    fn enforce_fails_the_test() {
        apply_level("enforce", "screen/App/x", &["tui-help-line: missing".to_owned()], Path::new("."));
    }
}
```
