# TUI templates — `ui: tui` only

A Bubble Tea v2 (`charm.land/bubbletea/v2`) full-screen list behind the `browse` subcommand, styled with Lip Gloss v2
(`charm.land/lipgloss/v2`). The model's `Update`/`View` are pure and unit-tested; the full program is tested with
teatest v2 (`github.com/charmbracelet/x/exp/teatest/v2`). `browse` starts only when stdin and stdout are terminals and
the output format is text; otherwise it exits 2 and points at `greet NAME --json` — the TUI is an addition for
people, never the only way to do something. Every file below is written only for `ui: tui`.

Conditional markers and `__TOKEN__` placeholders work as described in SKILL.md's "Reference files" section. Verified
on 2026-10-16 with Bubble Tea v2.1.0, Lip Gloss v2.0.6 and teatest v2 (pseudo-version
`v2.0.0-20261012121918-43427fdbad12`) — see SKILL.md "Verification notes".

The list follows the catalog's UI contract (`iru-go-code-one-task` `reference/ui-contract.md`): it renders only the
names that fit the window (`Model.window`, sized from `tea.WindowSizeMsg`), and the tests render every state
(`default`, `empty`, `many-items`, `long-content`) in each `.ui-quality.yml` direction (`rtl` = Arabic/Hebrew names in
the normal layout), write the styles-free view through `internal/tui/ui_screenshots_test.go` to
`.ui-screenshots/screen/Model/<state>--<direction>--no-color.txt`, and run the asserted accessibility checks at the
file's level. The list has one palette that doesn't adapt to the terminal background, so `no-color` is its only
theme. The helper strips styles with `github.com/charmbracelet/x/ansi` (already required through Lip Gloss; `go mod
tidy` makes it a direct requirement).

**UI additions unverified**: the `.ui-quality.yml`, the window, `ui_screenshots_test.go` and the matrix test were
added after the 2026-10-16 verification without a Go toolchain on the authoring machine; the first scaffold run's
Step 7 is their first compile — fix anything it finds here.

### `.ui-quality.yml` (ui: tui)

`__ACCESSIBILITY__` = the `accessibility` input (`enforce` by default). Exactly the contract's shape:

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: __ACCESSIBILITY__      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

### `internal/tui/model.go` (ui: tui)

```go
// Package tui is the full-screen terminal interface of `__BIN__ browse`: a
// list of names to pick one to greet. The model is pure (Update and View
// have no side effects), so it is tested without a terminal; Run starts the
// Bubble Tea program on the streams it is given.
package tui

import (
	"context"
	"io"
	"strings"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
)

var (
	titleStyle    = lipgloss.NewStyle().Bold(true)
	selectedStyle = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("6"))
	helpStyle     = lipgloss.NewStyle().Faint(true)
)

const (
	// helpText is the key help on the last line of the view.
	helpText = "up/down or k/j to move, enter to greet, q to quit"
	// chromeRows counts the view lines that aren't names: the title, two blank lines and the help.
	chromeRows = 4
	// defaultRows is how many names show before the first tea.WindowSizeMsg arrives.
	defaultRows = 10
)

// Model is the state of the browse list (screen Model; states: default, empty,
// many-items, long-content). The cursor is marked with "> " as well as color,
// so it stays visible without color.
type Model struct {
	names       []string
	cursor      int
	offset      int
	height      int
	chosen      string
	interrupted bool
}

// New returns a model listing names with the cursor on the first one.
func New(names []string) Model {
	return Model{names: names}
}

// DemoNames returns the sample names browse lists.
func DemoNames() []string {
	return []string{"Ada", "Grace", "Linus", "Margaret"}
}

// Init starts the program with no command.
func (m Model) Init() tea.Cmd { return nil }

// Update moves the cursor (up/down, k/j), chooses the name under it (enter),
// quits without choosing (q, esc) or interrupts (ctrl+c), and records the
// window height so only the names that fit are rendered.
func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.height = msg.Height
	case tea.KeyPressMsg:
		switch msg.String() {
		case "ctrl+c":
			m.interrupted = true
			return m, tea.Interrupt
		case "q", "esc":
			return m, tea.Quit
		case "up", "k":
			if m.cursor > 0 {
				m.cursor--
			}
		case "down", "j":
			if m.cursor < len(m.names)-1 {
				m.cursor++
			}
		case "enter":
			if len(m.names) > 0 {
				m.chosen = m.names[m.cursor]
			}
			return m, tea.Quit
		}
	}
	m.offset, _ = m.window()
	return m, nil
}

// window returns the range [start, end) of names that fit, scrolled so the
// cursor stays visible.
func (m Model) window() (int, int) {
	rows := defaultRows
	if m.height > 0 {
		rows = max(1, m.height-chromeRows)
	}
	start := min(m.offset, m.cursor)
	if m.cursor >= start+rows {
		start = m.cursor - rows + 1
	}
	return start, min(len(m.names), start+rows)
}

// View renders the visible part of the list in the alternate screen.
func (m Model) View() tea.View {
	var b strings.Builder
	b.WriteString(titleStyle.Render("Who should we greet?") + "\n\n")
	start, end := m.window()
	for i, name := range m.names[start:end] {
		if start+i == m.cursor {
			b.WriteString(selectedStyle.Render("> "+name) + "\n")
			continue
		}
		b.WriteString("  " + name + "\n")
	}
	b.WriteString("\n" + helpStyle.Render(helpText) + "\n")
	v := tea.NewView(b.String())
	v.AltScreen = true
	return v
}

// Chosen returns the name picked with enter, or "" when the user quit.
func (m Model) Chosen() string { return m.chosen }

// Interrupted reports whether the user pressed ctrl+c.
func (m Model) Interrupted() bool { return m.interrupted }

// Run shows the list on in/out until the user chooses a name or quits, and
// returns the chosen name ("" when none). Bubble Tea restores the terminal
// before Run returns; ctrl+c returns tea.ErrInterrupted.
func Run(ctx context.Context, in io.Reader, out io.Writer, names []string) (string, error) {
	final, err := tea.NewProgram(New(names), tea.WithContext(ctx), tea.WithInput(in), tea.WithOutput(out)).Run()
	if err != nil {
		return "", err
	}
	return final.(Model).Chosen(), nil
}
```

### `internal/tui/model_test.go` (ui: tui)

```go
package tui

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/x/ansi"
	"github.com/charmbracelet/x/exp/teatest/v2"
)

func press(m tea.Model, keys ...tea.KeyPressMsg) (Model, tea.Cmd) {
	var cmd tea.Cmd
	for _, k := range keys {
		m, cmd = m.Update(k)
	}
	return m.(Model), cmd
}

var (
	down  = tea.KeyPressMsg{Code: tea.KeyDown}
	up    = tea.KeyPressMsg{Code: tea.KeyUp}
	enter = tea.KeyPressMsg{Code: tea.KeyEnter}
)

func TestCursorStaysInBounds(t *testing.T) {
	m, _ := press(New(DemoNames()), up, down, down, down, down, down)
	if m.cursor != len(DemoNames())-1 {
		t.Fatalf("cursor = %d, want last", m.cursor)
	}
	m, _ = press(m, tea.KeyPressMsg{Code: 'k', Text: "k"}, up, up, up, up)
	if m.cursor != 0 {
		t.Fatalf("cursor = %d, want 0", m.cursor)
	}
}

func TestEnterChoosesAndQuits(t *testing.T) {
	m, cmd := press(New(DemoNames()), tea.KeyPressMsg{Code: 'j', Text: "j"}, enter)
	if m.Chosen() != "Grace" {
		t.Fatalf("Chosen() = %q, want Grace", m.Chosen())
	}
	if _, ok := cmd().(tea.QuitMsg); !ok {
		t.Fatal("enter did not quit")
	}
}

func TestQuitAndInterrupt(t *testing.T) {
	m, cmd := press(New(DemoNames()), tea.KeyPressMsg{Code: 'q', Text: "q"})
	if m.Chosen() != "" || m.Interrupted() {
		t.Fatalf("q chose %q / interrupted %v", m.Chosen(), m.Interrupted())
	}
	if _, ok := cmd().(tea.QuitMsg); !ok {
		t.Fatal("q did not quit")
	}
	m, cmd = press(New(DemoNames()), tea.KeyPressMsg{Code: 'c', Mod: tea.ModCtrl})
	if !m.Interrupted() {
		t.Fatal("ctrl+c did not interrupt")
	}
	if _, ok := cmd().(tea.InterruptMsg); !ok {
		t.Fatal("ctrl+c did not send InterruptMsg")
	}
}

func TestEmptyListAndOtherMessages(t *testing.T) {
	m, _ := press(New(nil), down, enter)
	if m.Chosen() != "" {
		t.Fatalf("Chosen() = %q on an empty list", m.Chosen())
	}
	next, cmd := New(DemoNames()).Update(tea.WindowSizeMsg{Width: 80, Height: 24})
	if cmd != nil || next.(Model).cursor != 0 {
		t.Fatal("a non-key message changed the model")
	}
	if New(nil).Init() != nil {
		t.Fatal("Init returned a command")
	}
}

func TestViewMarksTheCursor(t *testing.T) {
	m, _ := press(New(DemoNames()), down)
	v := m.View()
	if !v.AltScreen {
		t.Fatal("view is not in the alternate screen")
	}
	if !strings.Contains(v.Content, "> Grace") || !strings.Contains(v.Content, "  Ada") {
		t.Fatalf("unexpected view:\n%s", v.Content)
	}
}

func TestViewShowsOnlyTheVisibleWindow(t *testing.T) {
	names := make([]string, 20)
	for i := range names {
		names[i] = fmt.Sprintf("Name %02d", i)
	}
	next, _ := New(names).Update(tea.WindowSizeMsg{Width: 40, Height: chromeRows + 3})
	m, _ := press(next, down, down, down, down, down)
	if start, end := m.window(); start != 3 || end != 6 {
		t.Fatalf("window = [%d, %d), want [3, 6)", start, end)
	}
	view := ansi.Strip(m.View().Content)
	if strings.Contains(view, "Name 02") || !strings.Contains(view, "> Name 05") {
		t.Fatalf("unexpected window:\n%s", view)
	}
	m, _ = press(m, up, up, up, up)
	if start, _ := m.window(); start != 1 {
		t.Fatalf("start = %d after moving up, want 1", start)
	}
	if start, end := New(names).window(); start != 0 || end != defaultRows {
		t.Fatalf("window before any size = [%d, %d), want [0, %d)", start, end, defaultRows)
	}
}

// fixtureNames returns the names for one state of the UI contract's matrix;
// rtl uses right-to-left scripts.
func fixtureNames(state, direction string) []string {
	base := []string{"Ada", "Grace", "Linus", "Margaret"}
	if direction == "rtl" {
		base = []string{"عائشة", "מרים", "ليلى", "דוד"}
	}
	switch state {
	case "empty":
		return nil
	case "many-items":
		names := make([]string, 500)
		for i := range names {
			names[i] = fmt.Sprintf("%s %d", base[i%len(base)], i+1)
		}
		return names
	case "long-content":
		return []string{strings.Repeat(base[0], 20), base[1]}
	}
	return base
}

func TestViewCapturesEveryStateInEachDirection(t *testing.T) {
	quality := loadUIQuality(t)
	for _, direction := range quality.Directions {
		for _, state := range []string{"default", "empty", "many-items", "long-content"} {
			names := fixtureNames(state, direction)
			next, _ := New(names).Update(tea.WindowSizeMsg{Width: 60, Height: 12})
			// One palette that doesn't adapt to the background: no-color is the only theme captured.
			text := captureView(t, "screen", "Model", state, direction, "no-color", next.(Model).View().Content)
			selection := ""
			if len(names) > 0 {
				selection = "> "
			}
			target := "screen/Model/" + state + "--" + direction + "--no-color"
			checkAccessibility(t, target, tuiViolations(text, selection, helpText))
			if state == "many-items" && strings.Contains(text, " 500") {
				t.Errorf("%s rendered names outside the window", target)
			}
		}
	}
}

func TestProgramWithTeatest(t *testing.T) {
	tm := teatest.NewTestModel(t, New(DemoNames()), teatest.WithInitialTermSize(80, 24))
	teatest.WaitFor(t, tm.Output(), func(b []byte) bool { return bytes.Contains(b, []byte("Ada")) },
		teatest.WithDuration(5*time.Second))
	tm.Send(down)
	tm.Send(down)
	tm.Send(enter)
	final := tm.FinalModel(t, teatest.WithFinalTimeout(5*time.Second)).(Model)
	if final.Chosen() != "Linus" {
		t.Fatalf("Chosen() = %q, want Linus", final.Chosen())
	}
}

func TestRunReturnsTheChoice(t *testing.T) {
	name, err := Run(context.Background(), strings.NewReader("j\r"), io.Discard, DemoNames())
	if err != nil || name != "Grace" {
		t.Fatalf("Run() = %q, %v; want Grace", name, err)
	}
}

func TestRunStopsWhenTheContextIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	reader, writer := io.Pipe()
	defer func() { _ = writer.Close() }()
	if _, err := Run(ctx, reader, io.Discard, DemoNames()); !errors.Is(err, tea.ErrProgramKilled) {
		t.Fatalf("Run() error = %v, want ErrProgramKilled", err)
	}
}
```

### `internal/tui/ui_screenshots_test.go` (ui: tui)

The one capture helper every UI test in the package uses.

```go
package tui

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/charmbracelet/x/ansi"
)

// uiQuality is the .ui-quality.yml at the repository root (the UI contract);
// a missing file or key means the defaults.
type uiQuality struct {
	Accessibility string
	Directions    []string
	Themes        []string
}

// errorReporter is the part of *testing.T that applyLevel needs, so the
// enforce path can be tested.
type errorReporter interface {
	Helper()
	Errorf(format string, args ...any)
	Fatalf(format string, args ...any)
}

// repoRoot walks up from the package directory to the nearest directory
// holding .git, else the nearest go.mod directory.
func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("working directory: %v", err)
	}
	module := ""
	for d := dir; ; d = filepath.Dir(d) {
		if _, err := os.Stat(filepath.Join(d, ".git")); err == nil {
			return d
		}
		if _, err := os.Stat(filepath.Join(d, "go.mod")); err == nil && module == "" {
			module = d
		}
		if filepath.Dir(d) == d {
			if module == "" {
				return dir
			}
			return module
		}
	}
}

// screenshotsDir is UI_SCREENSHOTS_DIR, else <repository root>/.ui-screenshots.
func screenshotsDir(t *testing.T) string {
	t.Helper()
	if dir := os.Getenv("UI_SCREENSHOTS_DIR"); dir != "" {
		return dir
	}
	return filepath.Join(repoRoot(t), ".ui-screenshots")
}

// loadUIQuality reads .ui-quality.yml at the repository root.
func loadUIQuality(t *testing.T) uiQuality {
	t.Helper()
	// A missing or unreadable file means the defaults.
	data, _ := os.ReadFile(filepath.Clean(filepath.Join(repoRoot(t), ".ui-quality.yml")))
	return parseUIQuality(string(data))
}

// parseUIQuality reads the three keys of .ui-quality.yml without a YAML module.
func parseUIQuality(text string) uiQuality {
	q := uiQuality{Accessibility: "enforce", Directions: []string{"ltr", "rtl"}, Themes: []string{"light", "dark"}}
	for _, line := range strings.Split(text, "\n") {
		line, _, _ = strings.Cut(line, "#")
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		value = strings.TrimSpace(value)
		switch strings.TrimSpace(key) {
		case "accessibility":
			q.Accessibility = value
		case "directions":
			q.Directions = yamlList(value)
		case "themes":
			q.Themes = yamlList(value)
		}
	}
	return q
}

// yamlList splits a flow-style list such as [ltr, rtl].
func yamlList(value string) []string {
	var items []string
	for _, item := range strings.Split(strings.Trim(value, "[]"), ",") {
		if item = strings.TrimSpace(item); item != "" {
			items = append(items, item)
		}
	}
	return items
}

// captureView writes the styles-free view to
// <dir>/<kind>/<id>/<state>--<direction>--<theme>.txt and returns that text.
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

// tuiViolations runs the asserted terminal-UI accessibility checks on a
// styles-free capture and returns "rule-id: message" for each failure.
func tuiViolations(text, selection, help string) []string {
	var violations []string
	if selection != "" && !strings.Contains(text, selection) {
		violations = append(violations, "tui-color-only: the selection has no marker visible without color")
	}
	if !strings.Contains(text, help) {
		violations = append(violations, "tui-help-line: the key help is not on screen")
	}
	return violations
}

// checkAccessibility applies the .ui-quality.yml level to the violations
// found for target (<kind>/<Id>/<file stem>).
func checkAccessibility(t *testing.T, target string, violations []string) {
	t.Helper()
	applyLevel(t, loadUIQuality(t).Accessibility, target, violations, screenshotsDir(t))
}

// applyLevel fails the test (enforce), appends to <dir>/a11y-report.txt
// (report) or does nothing (off, or none when the project opted out).
func applyLevel(r errorReporter, level, target string, violations []string, dir string) {
	r.Helper()
	if len(violations) == 0 || level == "off" || level == "none" {
		return
	}
	if level != "report" {
		r.Errorf("accessibility violations in %s: %s", target, strings.Join(violations, "; "))
		return
	}
	if err := os.MkdirAll(dir, 0o750); err != nil {
		r.Fatalf("create screenshot directory: %v", err)
		return
	}
	report := filepath.Clean(filepath.Join(dir, "a11y-report.txt"))
	f, err := os.OpenFile(report, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		r.Fatalf("open accessibility report: %v", err)
		return
	}
	defer func() { _ = f.Close() }()
	for _, v := range violations {
		if _, err := fmt.Fprintf(f, "%s\t%s\n", target, v); err != nil {
			r.Fatalf("write accessibility report: %v", err)
			return
		}
	}
}

// recorder collects what applyLevel reports instead of failing the test.
type recorder struct {
	*testing.T
	messages []string
}

func (r *recorder) Errorf(format string, args ...any) {
	r.messages = append(r.messages, fmt.Sprintf(format, args...))
}

func (r *recorder) Fatalf(format string, args ...any) { r.Errorf(format, args...) }

func TestParseUIQuality(t *testing.T) {
	q := parseUIQuality("accessibility: report  # note\ndirections: [ltr]\n")
	if q.Accessibility != "report" || len(q.Directions) != 1 || q.Directions[0] != "ltr" || len(q.Themes) != 2 {
		t.Fatalf("parseUIQuality = %+v", q)
	}
	if parseUIQuality("").Accessibility != "enforce" {
		t.Fatal("missing file is not enforce")
	}
}

func TestTUIViolations(t *testing.T) {
	if got := tuiViolations("> Ada\nhelp", "> ", "help"); len(got) != 0 {
		t.Fatalf("violations = %v, want none", got)
	}
	if got := tuiViolations("  Ada", "> ", "help"); len(got) != 2 {
		t.Fatalf("violations = %v, want two", got)
	}
}

func TestApplyLevel(t *testing.T) {
	dir := t.TempDir()
	violations := []string{"tui-help-line: missing"}
	r := &recorder{T: t}
	applyLevel(r, "off", "screen/Model/x", violations, dir)
	applyLevel(r, "enforce", "screen/Model/x", nil, dir)
	if len(r.messages) != 0 {
		t.Fatalf("off/no violations reported %v", r.messages)
	}
	applyLevel(r, "enforce", "screen/Model/x", violations, dir)
	if len(r.messages) != 1 {
		t.Fatalf("enforce reported %v, want one failure", r.messages)
	}
	applyLevel(r, "report", "screen/Model/x", violations, dir)
	data, err := os.ReadFile(filepath.Clean(filepath.Join(dir, "a11y-report.txt")))
	if err != nil || string(data) != "screen/Model/x\ttui-help-line: missing\n" {
		t.Fatalf("report = %q, %v", data, err)
	}
}
```

### `internal/cli/browse.go` (ui: tui)

```go
package cli

import (
	"errors"

	tea "charm.land/bubbletea/v2"
	"github.com/spf13/cobra"

	"__MODULE__/internal/tui"
)

func newBrowseCommand(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "browse",
		Short: "Pick a name from a full-screen list and greet it (interactive)",
		Long: "Use browse at a terminal to pick a name interactively. It needs a terminal on stdin and stdout and " +
			"exits with code 2 otherwise; scripts and agents use `__BIN__ greet NAME --json` instead, which does the " +
			"same without a terminal.",
		Example:     "  __BIN__ browse",
		Args:        cobra.NoArgs,
		Annotations: safety(true, false, true),
		RunE: func(cmd *cobra.Command, _ []string) error {
			if !a.IsTerminal(a.In) || !a.IsTerminal(a.Out) || a.format().Machine() {
				return NewError(ExitUsage, "TTY_REQUIRED",
					"browse needs an interactive terminal on stdin and stdout and text output.",
					"Use `__BIN__ greet NAME --json` instead.")
			}
			name, err := a.runTUI(cmd.Context(), a.In, a.Out, tui.DemoNames())
			switch {
			case errors.Is(err, tea.ErrInterrupted):
				return NewError(ExitInterrupted, "", "Interrupted (ctrl+c).", "")
			case err != nil:
				return err
			case name == "":
				return nil
			}
			greeting, err := Greet(name)
			if err != nil {
				return err
			}
			return a.emit("Hello, "+paint(a.stdoutColor(), sgrBold, greeting.Name)+"!", greeting)
		},
	}
}
```

### `internal/cli/browse_test.go` (ui: tui)

```go
package cli

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"

	tea "charm.land/bubbletea/v2"
)

func TestBrowseRefusesWithoutATerminal(t *testing.T) {
	app, out, errOut := newTestApp(nil)
	if code := app.Run(context.Background(), []string{"browse"}); code != ExitUsage {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if out.Len() != 0 || !strings.Contains(errOut.String(), "greet NAME --json") {
		t.Fatalf("stdout %q, stderr %q", out, errOut)
	}
}

func TestBrowseGreetsTheChosenName(t *testing.T) {
	tests := []struct {
		name   string
		chosen string
		err    error
		code   ExitCode
		stdout string
	}{
		{"chosen", "Grace", nil, ExitOK, "Hello, Grace!\n"},
		{"quit", "", nil, ExitOK, ""},
		{"ctrl+c", "", tea.ErrInterrupted, ExitInterrupted, ""},
		{"program error", "", errors.New("boom"), ExitInternal, ""},
		{"invalid name", strings.Repeat("x", MaxNameLength+1), nil, ExitValidation, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, out, _ := newTestApp(nil)
			app.IsTerminal = func(any) bool { return true }
			app.runTUI = func(context.Context, io.Reader, io.Writer, []string) (string, error) { return tt.chosen, tt.err }
			if code := app.Run(context.Background(), []string{"browse", "--no-color"}); code != tt.code {
				t.Fatalf("exit code = %d, want %d", code, tt.code)
			}
			if out.String() != tt.stdout {
				t.Fatalf("stdout = %q, want %q", out, tt.stdout)
			}
		})
	}
}
```

### `testdata/script/browse.txtar` (ui: tui)

```text
# browse is TTY-gated: without a terminal it exits 2 and points at the
# non-interactive equivalent; stdout stays empty.
status 2 exec __BIN__ browse
! stdout .
stderr 'browse needs an interactive terminal'
stderr 'greet NAME --json'

# JSON mode reports the same as an envelope.
status 2 exec __BIN__ browse --json
stderr '"code":"TTY_REQUIRED"'
```
