# CLI templates — `internal/cli`

The command tree and the agent-friendly contract, one concern per file: `root.go` (the `App`, `Main`, the Cobra root
and its global flags, the one place exit codes are chosen), `exitcode.go` (the exit-code table), `errors.go` (the
typed error and its JSON envelope), `output.go` (`--format`, canonical JSON), `term.go` (colour and prompt policy),
`config.go` (flags → env → config file → defaults), `signals.go` (SIGINT/SIGTERM → 130/143), and the sample commands
`greet.go`, `schema.go`, `version.go` (+ `browse.go` from `tui-templates.md` for `ui: tui`). Their unit tests are in
`test-templates.md`.

Conditional markers and `__TOKEN__` placeholders work as described in SKILL.md's "Reference files" section.
`__ENV__` is the environment-variable prefix (`bin-name` upper-cased, `-` → `_`). Verified on 2026-10-16 for
`ui: none` and `ui: tui` — see SKILL.md "Verification notes".

### `internal/cli/doc.go`

```go
// Package cli implements the __BIN__ command tree and the command-line
// contract it follows for people, scripts and AI agents alike: results on
// stdout and everything else on stderr, --format text|json|jsonl with a JSON
// error envelope, one exit code per caller reaction (see ExitCode), color and
// prompts only on a terminal, configuration precedence (flags, environment,
// config file, defaults) and SIGINT/SIGTERM handling.
//
// cmd/__BIN__ only calls Main; tests drive an App with in-memory streams.
package cli
```

### `internal/cli/exitcode.go`

```go
package cli

// ExitCode is a process exit status of __BIN__. The numbers are a public
// interface that scripts and agents rely on: never renumber them. A new kind
// of failure maps to the existing code whose caller reaction fits; finer
// detail goes in the error envelope's code and details fields.
type ExitCode int

// The exit codes, one per caller reaction.
const (
	// ExitOK means success, including an idempotent no-op and a --dry-run plan.
	ExitOK ExitCode = 0
	// ExitInternal means an unexpected failure (a bug).
	ExitInternal ExitCode = 1
	// ExitUsage means a bad flag, argument or unknown command.
	ExitUsage ExitCode = 2
	// ExitValidation means well-formed input that was rejected.
	ExitValidation ExitCode = 3
	// ExitNotFound means the named resource does not exist.
	ExitNotFound ExitCode = 4
	// ExitConfirmationRequired means a destructive action needs confirmation.
	ExitConfirmationRequired ExitCode = 5
	// ExitRetryable means a transient failure worth retrying unchanged.
	ExitRetryable ExitCode = 6
	// ExitAuth means missing or invalid credentials.
	ExitAuth ExitCode = 7
	// ExitInterrupted is returned after SIGINT (128+2), once cleanup is done.
	ExitInterrupted ExitCode = 130
	// ExitTerminated is returned after SIGTERM (128+15), once cleanup is done.
	ExitTerminated ExitCode = 143
)

var exitCodeTable = []struct {
	code    ExitCode
	name    string
	meaning string
}{
	{ExitOK, "OK", "Success, including an idempotent no-op and a --dry-run plan. Continue."},
	{ExitInternal, "INTERNAL", "Unexpected failure (a bug). Report it; don't retry blindly."},
	{ExitUsage, "USAGE", "Bad flag, argument or unknown command. Fix the command line using the message."},
	{ExitValidation, "VALIDATION", "Well-formed input that was rejected. Read the problems, fix the data."},
	{ExitNotFound, "NOT_FOUND", "The named resource doesn't exist. Look it up."},
	{ExitConfirmationRequired, "CONFIRMATION_REQUIRED", "A destructive action needs confirmation. Review the plan, then re-run with --yes."},
	{ExitRetryable, "RETRYABLE", "Transient failure (timeout, rate limit). Wait, then retry with the same arguments."},
	{ExitAuth, "AUTH", "Missing or invalid credentials. Log in or set the token variable named in the hint."},
	{ExitInterrupted, "INTERRUPTED", "Interrupted by SIGINT; cleanup finished."},
	{ExitTerminated, "TERMINATED", "Terminated by SIGTERM; cleanup finished."},
}

// ExitCodes returns every exit code __BIN__ defines, in ascending order.
func ExitCodes() []ExitCode {
	codes := make([]ExitCode, 0, len(exitCodeTable))
	for _, row := range exitCodeTable {
		codes = append(codes, row.code)
	}
	return codes
}

// Name returns the stable SCREAMING_SNAKE name of c, or "UNKNOWN".
func (c ExitCode) Name() string {
	for _, row := range exitCodeTable {
		if row.code == c {
			return row.name
		}
	}
	return "UNKNOWN"
}

// Meaning returns the reaction c asks of the caller, or "" for an unknown code.
func (c ExitCode) Meaning() string {
	for _, row := range exitCodeTable {
		if row.code == c {
			return row.meaning
		}
	}
	return ""
}
```

### `internal/cli/errors.go`

```go
package cli

import "fmt"

// Error is an expected failure: it carries the exit code and every field of
// the JSON error envelope. Commands return *Error for each failure they
// anticipate; any other error a command returns is reported as an internal
// error (exit code 1).
type Error struct {
	// Code is the process exit code.
	Code ExitCode
	// ID is the stable SCREAMING_SNAKE identifier in the envelope's "code"
	// field. Empty means Code.Name().
	ID string
	// Message says what went wrong, in one sentence.
	Message string
	// Hint says how to fix it. It is never empty for codes 2 to 7.
	Hint string
	// Details carries machine-readable context. It never holds secrets.
	Details map[string]any
	// RetryAfterSeconds is reported for ExitRetryable when positive.
	RetryAfterSeconds int
	// Err is the underlying cause, if any.
	Err error
}

// NewError returns an *Error with the given exit code, identifier, message
// and hint.
func NewError(code ExitCode, id, message, hint string) *Error {
	return &Error{Code: code, ID: id, Message: message, Hint: hint}
}

// WithDetails sets the envelope's details and returns e.
func (e *Error) WithDetails(details map[string]any) *Error {
	e.Details = details
	return e
}

// Error returns the message.
func (e *Error) Error() string { return e.Message }

// Unwrap returns the underlying cause.
func (e *Error) Unwrap() error { return e.Err }

// envelope is the JSON document written to stderr in machine mode.
func (e *Error) envelope() map[string]any {
	id := e.ID
	if id == "" {
		id = e.Code.Name()
	}
	details := e.Details
	if details == nil {
		details = map[string]any{}
	}
	body := map[string]any{
		"code":      id,
		"details":   details,
		"exitCode":  int(e.Code),
		"hint":      e.Hint,
		"message":   e.Message,
		"retryable": e.Code == ExitRetryable,
	}
	if e.Code == ExitRetryable && e.RetryAfterSeconds > 0 {
		body["retryAfterSeconds"] = e.RetryAfterSeconds
	}
	return map[string]any{"error": body, "schemaVersion": SchemaVersion}
}

// internalError reports an unexpected error as a bug (exit code 1).
func internalError(err error) *Error {
	return &Error{
		Code:    ExitInternal,
		Message: fmt.Sprintf("internal error: %v", err),
		Hint:    "This is a bug in __BIN__; please report it with the command you ran.",
		Err:     err,
	}
}
```

### `internal/cli/output.go`

```go
package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"slices"
	"strings"
)

// SchemaVersion is the version of every JSON document __BIN__ writes. Adding
// a field keeps it; renaming, removing or retyping one increments it.
const SchemaVersion = 1

// Format selects how results are written to stdout.
type Format string

// The output formats accepted by --format.
const (
	FormatText  Format = "text"
	FormatJSON  Format = "json"
	FormatJSONL Format = "jsonl"
)

var (
	formatNames    = []string{string(FormatText), string(FormatJSON), string(FormatJSONL)}
	logFormatNames = []string{"text", "json"}
)

// Machine reports whether f is a machine-readable format.
func (f Format) Machine() bool { return f == FormatJSON || f == FormatJSONL }

// parseFormat converts s to a Format.
func parseFormat(s string) (Format, error) {
	if !slices.Contains(formatNames, s) {
		return "", fmt.Errorf("%q is not one of %s", s, strings.Join(formatNames, ", "))
	}
	return Format(s), nil
}

// enumValue is a pflag.Value that accepts only the allowed strings, so an
// invalid --format is a flag error (exit code 2) raised before any command runs.
type enumValue struct {
	value   *string
	allowed []string
}

func (v *enumValue) String() string { return *v.value }

func (v *enumValue) Set(s string) error {
	if !slices.Contains(v.allowed, s) {
		return fmt.Errorf("must be one of %s", strings.Join(v.allowed, ", "))
	}
	*v.value = s
	return nil
}

func (v *enumValue) Type() string { return "string" }

// writeJSON writes v as one canonical JSON document followed by a newline:
// object keys sorted, compact separators and no HTML escaping, so the same
// value always produces the same bytes (golden tests rely on it).
func writeJSON(w io.Writer, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var generic any
	if err := dec.Decode(&generic); err != nil {
		return err
	}
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	return enc.Encode(generic)
}

// writeIndentedJSON writes v like writeJSON, indented for people.
func writeIndentedJSON(w io.Writer, v any) error {
	var compact, indented bytes.Buffer
	if err := writeJSON(&compact, v); err != nil {
		return err
	}
	if err := json.Indent(&indented, compact.Bytes(), "", "  "); err != nil {
		return err
	}
	_, err := indented.WriteTo(w)
	return err
}

// emit writes one result to stdout: text for people, the record as one JSON
// document, or the record plus a summary line in JSON Lines mode.
func (a *App) emit(text string, record any) error {
	switch a.format() {
	case FormatJSON:
		return writeJSON(a.Out, record)
	case FormatJSONL:
		if err := writeJSON(a.Out, record); err != nil {
			return err
		}
		return writeJSON(a.Out, map[string]any{"nextCursor": nil, "schemaVersion": SchemaVersion, "total": 1, "type": "summary"})
	default:
		_, err := fmt.Fprintln(a.Out, text)
		return err
	}
}

// reportError writes e to stderr: the JSON envelope in machine mode, otherwise
// "__BIN__: message" plus usage and hint lines.
func (a *App) reportError(e *Error) {
	if a.format().Machine() {
		_ = writeJSON(a.Err, e.envelope())
		return
	}
	prefix := paint(a.stderrColor(), sgrBoldRed, "__BIN__:")
	_, _ = fmt.Fprintf(a.Err, "%s %s\n", prefix, e.Message)
	if usage, ok := e.Details["usage"].(string); ok {
		_, _ = fmt.Fprintf(a.Err, "usage: %s\n", usage)
	}
	if e.Hint != "" {
		_, _ = fmt.Fprintf(a.Err, "hint: %s\n", e.Hint)
	}
}
```

### `internal/cli/term.go`

```go
package cli

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"strings"

	"golang.org/x/term"
)

// SGR sequences used for styling.
const (
	sgrBold    = "1"
	sgrBoldRed = "1;31"
)

// isTerminal reports whether stream is a file attached to a terminal.
func isTerminal(stream any) bool {
	f, ok := stream.(interface{ Fd() uintptr })
	return ok && term.IsTerminal(int(f.Fd())) //nolint:gosec // G115: file descriptors fit in an int.
}

// truthy reports whether an environment value means "on".
func truthy(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

// forced reports whether FORCE_COLOR / CLICOLOR_FORCE asks for color.
func forced(v string) bool {
	return v != "" && v != "0" && !strings.EqualFold(v, "false")
}

// colorEnabled decides styling for one stream. Off when NO_COLOR is set,
// --no-color (or __ENV___NO_COLOR) is given, or the output is machine-readable;
// otherwise on when FORCE_COLOR/CLICOLOR_FORCE asks for it, off for TERM=dumb,
// and on only when the stream is a terminal.
func colorEnabled(tty, noColor, machine bool, getenv func(string) string) bool {
	if noColor || machine || getenv("NO_COLOR") != "" {
		return false
	}
	if forced(getenv("FORCE_COLOR")) || forced(getenv("CLICOLOR_FORCE")) {
		return true
	}
	if getenv("TERM") == "dumb" {
		return false
	}
	return tty
}

// canPrompt reports whether asking a question is allowed: stdin and stderr
// are terminals, and neither --no-input (or __ENV___NO_INPUT, folded into
// noInput by the configuration) nor CI is set.
func canPrompt(stdinTTY, stderrTTY, noInput bool, getenv func(string) string) bool {
	if noInput || truthy(getenv("CI")) {
		return false
	}
	return stdinTTY && stderrTTY
}

// paint wraps s in an SGR sequence when enabled.
func paint(enabled bool, sgr, s string) string {
	if !enabled {
		return s
	}
	return "\x1b[" + sgr + "m" + s + "\x1b[0m"
}

func (a *App) stdoutColor() bool {
	return colorEnabled(a.IsTerminal(a.Out), a.noColor(), a.format().Machine(), a.Getenv)
}

func (a *App) stderrColor() bool {
	return colorEnabled(a.IsTerminal(a.Err), a.noColor() || a.flags.quiet, a.format().Machine(), a.Getenv)
}

func (a *App) canPrompt() bool {
	return canPrompt(a.IsTerminal(a.In), a.IsTerminal(a.Err), a.cfg.NoInput, a.Getenv)
}

// prompt asks one question on stderr and reads one line from stdin. Call it
// only after canPrompt; never use it for secrets (it echoes).
func (a *App) prompt(question string) (string, error) {
	if _, err := fmt.Fprint(a.Err, question); err != nil {
		return "", err
	}
	line, err := bufio.NewReader(a.In).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	return strings.TrimSpace(line), nil
}
```

### `internal/cli/config.go`

```go
package cli

import (
	"bytes"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"

	"github.com/pelletier/go-toml/v2"
	"github.com/spf13/pflag"
)

// Environment variables __BIN__ reads. Every setting has a flag, an
// environment variable and a config-file key with the same meaning.
const (
	envConfig  = "__ENV___CONFIG"
	envFormat  = "__ENV___FORMAT"
	envNoColor = "__ENV___NO_COLOR"
	envNoInput = "__ENV___NO_INPUT"
)

// Config holds the settings every command shares, after precedence is
// applied: flags, then environment variables, then the user config file,
// then built-in defaults.
type Config struct {
	Format  Format
	NoColor bool
	NoInput bool
	// File is the config file that was read, or "" when none was.
	File string
	// Sources names the layer that supplied each setting:
	// "default", "file", "env" or "flag".
	Sources map[string]string
}

// fileConfig is the TOML shape of config.toml; pointers tell "unset" apart
// from a zero value.
type fileConfig struct {
	Format  *string `toml:"format"`
	NoColor *bool   `toml:"no-color"`
	NoInput *bool   `toml:"no-input"`
}

// configPath returns the config file to read and whether it was named
// explicitly (through __ENV___CONFIG). The default is
// $XDG_CONFIG_HOME/__BIN__/config.toml, else ~/.config/__BIN__/config.toml
// (%APPDATA%\__BIN__\config.toml on Windows).
func configPath(getenv func(string) string) (string, bool) {
	if p := getenv(envConfig); p != "" {
		return p, true
	}
	if dir := getenv("XDG_CONFIG_HOME"); dir != "" {
		return filepath.Join(dir, "__BIN__", "config.toml"), false
	}
	if runtime.GOOS == "windows" {
		if dir := getenv("APPDATA"); dir != "" {
			return filepath.Join(dir, "__BIN__", "config.toml"), false
		}
	}
	if home := getenv("HOME"); home != "" {
		return filepath.Join(home, ".config", "__BIN__", "config.toml"), false
	}
	return "", false
}

// readConfigFile reads and strictly decodes path. A missing default file is
// not an error; a missing explicit one is (exit code 4).
func readConfigFile(path string, explicit bool) (fileConfig, bool, error) {
	var fc fileConfig
	data, err := os.ReadFile(filepath.Clean(path))
	switch {
	case errors.Is(err, fs.ErrNotExist) && !explicit:
		return fc, false, nil
	case errors.Is(err, fs.ErrNotExist):
		return fc, false, NewError(ExitNotFound, "CONFIG_NOT_FOUND",
			fmt.Sprintf("Config file %s does not exist.", path),
			fmt.Sprintf("Create it, or unset %s to use the default location.", envConfig))
	case err != nil:
		return fc, false, NewError(ExitValidation, "CONFIG_UNREADABLE",
			fmt.Sprintf("Config file %s cannot be read: %v.", path, err),
			"Point "+envConfig+" at a readable TOML file.")
	}
	dec := toml.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&fc); err != nil {
		return fc, false, invalidConfig(path, err)
	}
	return fc, true, nil
}

func invalidConfig(path string, err error) *Error {
	return NewError(ExitValidation, "CONFIG_INVALID",
		fmt.Sprintf("Config file %s is invalid: %v", path, err),
		"Valid keys are format (text, json or jsonl), no-color and no-input (booleans).")
}

// loadConfig resolves the shared settings for one invocation.
func (a *App) loadConfig(flags *pflag.FlagSet) (Config, error) {
	cfg := Config{Format: FormatText, Sources: map[string]string{"format": "default", "no-color": "default", "no-input": "default"}}
	if path, explicit := configPath(a.Getenv); path != "" {
		fc, found, err := readConfigFile(path, explicit)
		if err != nil {
			return Config{}, err
		}
		if found {
			cfg.File = path
			if fc.Format != nil {
				f, err := parseFormat(*fc.Format)
				if err != nil {
					return Config{}, invalidConfig(path, err)
				}
				cfg.Format, cfg.Sources["format"] = f, "file"
			}
			if fc.NoColor != nil {
				cfg.NoColor, cfg.Sources["no-color"] = *fc.NoColor, "file"
			}
			if fc.NoInput != nil {
				cfg.NoInput, cfg.Sources["no-input"] = *fc.NoInput, "file"
			}
		}
	}
	if v := a.Getenv(envFormat); v != "" {
		f, err := parseFormat(v)
		if err != nil {
			return Config{}, NewError(ExitUsage, "USAGE", fmt.Sprintf("%s: %v.", envFormat, err),
				"Set "+envFormat+" to text, json or jsonl, or unset it.")
		}
		cfg.Format, cfg.Sources["format"] = f, "env"
	}
	if v := a.Getenv(envNoColor); v != "" {
		cfg.NoColor, cfg.Sources["no-color"] = truthy(v), "env"
	}
	if v := a.Getenv(envNoInput); v != "" {
		cfg.NoInput, cfg.Sources["no-input"] = truthy(v), "env"
	}
	if flags.Changed("format") {
		cfg.Format, cfg.Sources["format"] = Format(a.flags.format), "flag"
	}
	if flags.Changed("json") && a.flags.json {
		cfg.Format, cfg.Sources["format"] = FormatJSON, "flag"
	}
	if flags.Changed("no-color") {
		cfg.NoColor, cfg.Sources["no-color"] = a.flags.noColor, "flag"
	}
	if flags.Changed("no-input") {
		cfg.NoInput, cfg.Sources["no-input"] = a.flags.noInput, "flag"
	}
	return cfg, nil
}
```

### `internal/cli/signals.go`

```go
package cli

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

// interrupt is the cancellation cause recorded when a signal arrives.
type interrupt struct{ sig os.Signal }

func (i interrupt) Error() string { return "received " + i.name() }

// name returns the conventional signal name.
func (i interrupt) name() string {
	if i.sig == syscall.SIGTERM {
		return "SIGTERM"
	}
	return "SIGINT"
}

// code maps the signal to 143 for SIGTERM and 130 otherwise (SIGINT).
func (i interrupt) code() ExitCode {
	if i.sig == syscall.SIGTERM {
		return ExitTerminated
	}
	return ExitInterrupted
}

// notifySignals returns a context canceled, with an interrupt cause, by the
// first SIGINT or SIGTERM, so commands stop and clean up; a second signal
// during cleanup exits immediately. (signal.NotifyContext does the first half
// but doesn't expose which signal arrived, and the exit code depends on it.)
// The returned stop function must be called once the command has returned.
func (a *App) notifySignals(parent context.Context) (context.Context, func()) {
	ctx, cancel := context.WithCancelCause(parent)
	signals := make(chan os.Signal, 2)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	done := make(chan struct{})
	go func() {
		select {
		case sig := <-signals:
			cancel(interrupt{sig})
			select {
			case sig := <-signals:
				a.exit(int(interrupt{sig}.code()))
			case <-done:
			}
		case <-done:
		}
	}()
	return ctx, func() {
		signal.Stop(signals)
		close(done)
		cancel(nil)
	}
}
```

### `internal/cli/root.go`

```go
package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/spf13/cobra"

	// >>> ui: tui
	"__MODULE__/internal/tui"
	// <<< ui: tui
	"__MODULE__/internal/version"
)

// Command annotations recording each command's safety; `__BIN__ schema`
// reports them and every command must set them.
const (
	annotationReadOnly    = "readOnly"
	annotationDestructive = "destructive"
	annotationIdempotent  = "idempotent"
)

// globalFlags holds the values of the persistent flags.
type globalFlags struct {
	format    string
	json      bool
	noColor   bool
	noInput   bool
	quiet     bool
	verbose   int
	logFormat string
}

// App is one invocation of __BIN__: its streams, its environment and the
// state resolved while it runs. Main builds one for the process; tests build
// one with in-memory streams and a fake environment.
type App struct {
	In  io.Reader
	Out io.Writer
	Err io.Writer
	// Getenv reads an environment variable.
	Getenv func(string) string
	// IsTerminal reports whether a stream is attached to a terminal.
	IsTerminal func(stream any) bool

	exit func(code int)
	// >>> ui: tui

	runTUI func(ctx context.Context, in io.Reader, out io.Writer, names []string) (string, error)
	// <<< ui: tui

	flags globalFlags
	args  []string
	cfg   Config
	log   *slog.Logger
}

// NewApp returns an App bound to the given streams and the process
// environment.
func NewApp(stdin io.Reader, stdout, stderr io.Writer) *App {
	return &App{
		In:         stdin,
		Out:        stdout,
		Err:        stderr,
		Getenv:     os.Getenv,
		IsTerminal: isTerminal,
		exit:       os.Exit,
		// >>> ui: tui
		runTUI: tui.Run,
		// <<< ui: tui
		log: slog.New(slog.DiscardHandler),
	}
}

// Main runs __BIN__ with args (without the program name) and returns the
// process exit code.
func Main(ctx context.Context, args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	return int(NewApp(stdin, stdout, stderr).Run(ctx, args))
}

// Run executes one invocation and returns its exit code.
func (a *App) Run(ctx context.Context, args []string) ExitCode {
	return a.run(ctx, a.Command(), args)
}

func (a *App) run(ctx context.Context, root *cobra.Command, args []string) (code ExitCode) {
	// An unrecovered panic would exit with status 2, which means USAGE in this
	// contract; report it as the bug it is instead (exit code 1).
	defer func() {
		if r := recover(); r != nil {
			a.reportError(internalError(fmt.Errorf("panic: %v", r)))
			code = ExitInternal
		}
	}()
	// With SIGPIPE ignored, writing to a closed pipe returns EPIPE (handled as
	// a clean stop below) instead of killing the process.
	signal.Ignore(syscall.SIGPIPE)
	ctx, stop := a.notifySignals(ctx)
	defer stop()
	a.args = args
	root.SetArgs(args)
	cmd, err := root.ExecuteContextC(ctx)
	return a.exitCode(ctx, cmd, err)
}

// exitCode maps the outcome of a run to an exit code and reports the failure.
// It is the only place exit codes are chosen.
func (a *App) exitCode(ctx context.Context, cmd *cobra.Command, err error) ExitCode {
	var sig interrupt
	if errors.As(context.Cause(ctx), &sig) {
		a.reportError(NewError(sig.code(), "", fmt.Sprintf("Interrupted by %s.", sig.name()), ""))
		return sig.code()
	}
	if err == nil || errors.Is(err, syscall.EPIPE) {
		return ExitOK
	}
	var e *Error
	if !errors.As(err, &e) {
		// Commands' own errors are always *Error (see wrapRunE), so anything
		// else comes from Cobra's parsing: unknown command, wrong arguments.
		e = usageError(cmd, err)
	}
	a.reportError(e)
	return e.Code
}

// usageError turns a parse error into exit code 2 with the command's usage
// line and a pointer to its help.
func usageError(cmd *cobra.Command, err error) *Error {
	return NewError(ExitUsage, "USAGE", err.Error(), fmt.Sprintf("See `%s --help`.", cmd.CommandPath())).
		WithDetails(map[string]any{"usage": cmd.UseLine()})
}

// format returns the output format: the resolved configuration or, when a
// failure happened before configuration was resolved, the parsed flags or
// (for errors Cobra reports before parsing flags, such as an unknown command)
// the raw arguments.
func (a *App) format() Format {
	if a.cfg.Format != "" {
		return a.cfg.Format
	}
	if a.flags.json {
		return FormatJSON
	}
	if f, err := parseFormat(a.flags.format); err == nil && f != FormatText {
		return f
	}
	if f, err := parseFormat(formatFromArgs(a.args)); err == nil {
		return f
	}
	return FormatText
}

// formatFromArgs finds the last --json or --format value before "--".
func formatFromArgs(args []string) string {
	format := ""
	for i, arg := range args {
		switch {
		case arg == "--":
			return format
		case arg == "--json":
			format = string(FormatJSON)
		case strings.HasPrefix(arg, "--format="):
			format = strings.TrimPrefix(arg, "--format=")
		case arg == "--format" && i+1 < len(args):
			format = args[i+1]
		}
	}
	return format
}

func (a *App) noColor() bool { return a.cfg.NoColor || a.flags.noColor }

// Command builds the complete command tree for this App.
func (a *App) Command() *cobra.Command {
	root := &cobra.Command{
		Use:   "__BIN__",
		Short: "__DESCRIPTION_SENTENCE__",
		Long: "__DESCRIPTION_SENTENCE__\n\n" +
			"Results go to stdout and everything else to stderr. Every command accepts --format text|json|jsonl " +
			"(--json for short) and fails with a documented exit code; run `__BIN__ schema` for the machine-readable " +
			"command tree.",
		Version:       version.Get().Version,
		SilenceUsage:  true,
		SilenceErrors: true,
		PersistentPreRunE: func(cmd *cobra.Command, _ []string) error {
			return a.setup(cmd)
		},
	}
	root.SetIn(a.In)
	root.SetOut(a.Out)
	root.SetErr(a.Err)
	root.SetVersionTemplate("{{.Name}} {{.Version}}\n")
	root.SetFlagErrorFunc(func(cmd *cobra.Command, err error) error { return usageError(cmd, err) })

	a.flags.format, a.flags.logFormat = string(FormatText), "text"
	flags := root.PersistentFlags()
	flags.Var(&enumValue{&a.flags.format, formatNames}, "format", "output format: text, json or jsonl")
	flags.BoolVar(&a.flags.json, "json", false, "shorthand for --format json")
	flags.BoolVar(&a.flags.noColor, "no-color", false, "disable color (also NO_COLOR or "+envNoColor+")")
	flags.BoolVar(&a.flags.noInput, "no-input", false, "never prompt; fail when a value is missing (also "+envNoInput+" or CI)")
	flags.BoolVarP(&a.flags.quiet, "quiet", "q", false, "suppress informational messages on stderr (errors are still printed)")
	flags.CountVarP(&a.flags.verbose, "verbose", "v", "print diagnostics on stderr; repeat for more detail")
	flags.Var(&enumValue{&a.flags.logFormat, logFormatNames}, "log-format", "diagnostics format on stderr: text or json")
	root.MarkFlagsMutuallyExclusive("quiet", "verbose")
	for name, values := range map[string][]string{"format": formatNames, "log-format": logFormatNames} {
		_ = root.RegisterFlagCompletionFunc(name, cobra.FixedCompletions(values, cobra.ShellCompDirectiveNoFileComp))
	}

	root.AddCommand(newGreetCommand(a), newSchemaCommand(a), newVersionCommand(a))
	// >>> ui: tui
	root.AddCommand(newBrowseCommand(a))
	// <<< ui: tui
	finishTree(root)
	return root
}

// setup resolves configuration and logging before any command runs.
func (a *App) setup(cmd *cobra.Command) error {
	cfg, err := a.loadConfig(cmd.Flags())
	a.cfg = cfg
	a.log = newLogger(a.Err, a.flags.logFormat, a.flags.quiet, a.flags.verbose)
	if err != nil {
		return err
	}
	a.log.Info("configuration resolved", "file", cfg.File, "format", cfg.Format,
		"formatSource", cfg.Sources["format"], "noInputSource", cfg.Sources["no-input"])
	return nil
}

// newLogger returns the stderr diagnostics logger: warnings by default,
// errors only with --quiet, info with -v and debug with -vv. Timestamps are
// left out so output is reproducible.
func newLogger(w io.Writer, format string, quiet bool, verbose int) *slog.Logger {
	level := slog.LevelWarn
	switch {
	case quiet:
		level = slog.LevelError
	case verbose == 1:
		level = slog.LevelInfo
	case verbose > 1:
		level = slog.LevelDebug
	}
	opts := &slog.HandlerOptions{Level: level, ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
		if len(groups) == 0 && attr.Key == slog.TimeKey {
			return slog.Attr{}
		}
		return attr
	}}
	if format == "json" {
		return slog.New(slog.NewJSONHandler(w, opts))
	}
	return slog.New(slog.NewTextHandler(w, opts))
}

// safety returns the annotations recording a command's safety.
func safety(readOnly, destructive, idempotent bool) map[string]string {
	return map[string]string{
		annotationReadOnly:    fmt.Sprint(readOnly),
		annotationDestructive: fmt.Sprint(destructive),
		annotationIdempotent:  fmt.Sprint(idempotent),
	}
}

// finishTree adds Cobra's help and completion commands up front (so schema
// and the docs generator see the whole tree), marks those built-ins
// read-only, makes a command group reject unknown subcommands with exit code
// 2, and wraps every RunE so that a non-*Error failure becomes an internal
// error. Call it again after adding commands to a finished tree.
func finishTree(root *cobra.Command) {
	root.InitDefaultHelpCmd()
	root.InitDefaultCompletionCmd()
	walk(root, func(cmd *cobra.Command) {
		cmd.InitDefaultHelpFlag()
		if cmd.Annotations[annotationReadOnly] == "" {
			if cmd.Annotations == nil {
				cmd.Annotations = map[string]string{}
			}
			for k, v := range safety(true, false, true) {
				cmd.Annotations[k] = v
			}
		}
		if cmd != root && cmd.HasSubCommands() && !cmd.Runnable() {
			// Cobra shows a group's help for any argument and exits 0; reject
			// unknown subcommands instead (the root already does).
			cmd.Args = cobra.NoArgs
			cmd.RunE = func(cmd *cobra.Command, _ []string) error { return cmd.Help() }
		}
		wrapRunE(cmd)
	})
	root.InitDefaultVersionFlag()
}

func wrapRunE(cmd *cobra.Command) {
	run := cmd.RunE
	if run == nil {
		return
	}
	cmd.RunE = func(cmd *cobra.Command, args []string) error {
		err := run(cmd, args)
		var e *Error
		if err == nil || errors.As(err, &e) || errors.Is(err, syscall.EPIPE) {
			return err
		}
		return internalError(err)
	}
}

// walk calls fn for cmd and every command below it.
func walk(cmd *cobra.Command, fn func(*cobra.Command)) {
	fn(cmd)
	for _, child := range cmd.Commands() {
		walk(child, fn)
	}
}
```

### `internal/cli/greet.go`

```go
package cli

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/spf13/cobra"
)

// MaxNameLength is the longest NAME greet accepts, in characters.
const MaxNameLength = 64

// Greeting is the result of greet; its JSON form is greet's --json output.
type Greeting struct {
	Greeting      string `json:"greeting"`
	Name          string `json:"name"`
	SchemaVersion int    `json:"schemaVersion"`
}

// Greet validates name and returns the greeting for it. An empty or overlong
// name is a validation error (exit code 3).
func Greet(name string) (Greeting, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Greeting{}, NewError(ExitValidation, "INVALID_NAME", "NAME must not be empty.",
			"Pass a non-empty NAME, e.g. `__BIN__ greet Ada`.")
	}
	if n := utf8.RuneCountInString(name); n > MaxNameLength {
		return Greeting{}, NewError(ExitValidation, "INVALID_NAME",
			fmt.Sprintf("NAME is %d characters long; the maximum is %d.", n, MaxNameLength),
			"Pass a shorter NAME.").WithDetails(map[string]any{"length": n, "maxLength": MaxNameLength})
	}
	return Greeting{Greeting: "Hello, " + name + "!", Name: name, SchemaVersion: SchemaVersion}, nil
}

func newGreetCommand(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "greet [NAME]",
		Short: "Print a greeting for NAME (read-only sample command)",
		Long: "Use greet to check that __BIN__ works and to see its output contract: the greeting on stdout, " +
			"--json for a machine-readable document, exit code 3 for an empty or overlong NAME. Without NAME it " +
			"asks for one on a terminal and fails with exit code 2 otherwise.",
		Example:     "  __BIN__ greet Ada\n  __BIN__ greet Ada --json\n  __BIN__ greet --format jsonl -- \"-dash-name\"",
		Args:        cobra.MaximumNArgs(1),
		Annotations: safety(true, false, true),
		RunE: func(_ *cobra.Command, args []string) error {
			name, err := a.nameArgument(args)
			if err != nil {
				return err
			}
			greeting, err := Greet(name)
			if err != nil {
				return err
			}
			a.log.Debug("greeting built", "name", greeting.Name)
			text := "Hello, " + paint(a.stdoutColor(), sgrBold, greeting.Name) + "!"
			return a.emit(text, greeting)
		},
	}
}

// nameArgument returns NAME from the arguments or, on a terminal, from a
// prompt; without either it fails fast with exit code 2 and names the fix.
func (a *App) nameArgument(args []string) (string, error) {
	if len(args) == 1 {
		return args[0], nil
	}
	if !a.canPrompt() {
		return "", NewError(ExitUsage, "MISSING_ARGUMENT", "greet needs a NAME.",
			"Pass it as an argument, e.g. `__BIN__ greet Ada` (prompting is off without a terminal, with --no-input, "+
				envNoInput+" or CI).")
	}
	return a.prompt("Name: ")
}
```

### `internal/cli/schema.go`

```go
package cli

import (
	"strconv"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"__MODULE__/internal/version"
)

func newSchemaCommand(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "schema",
		Short: "Print the machine-readable command tree as JSON",
		Long: "Use schema to discover __BIN__ programmatically: every command with its arguments, flags (types and " +
			"defaults), safety (readOnly/destructive/idempotent), the output schemaVersion and the exit codes. It is " +
			"the shape an MCP wrapper maps to tool definitions. Always JSON; indented unless --json/--format is given.",
		Example:     "  __BIN__ schema --json | jq '.commands[].name'",
		Args:        cobra.NoArgs,
		Annotations: safety(true, false, true),
		RunE: func(cmd *cobra.Command, _ []string) error {
			doc := describeTree(cmd.Root())
			if a.format().Machine() {
				return a.emit("", doc)
			}
			return writeIndentedJSON(a.Out, doc)
		},
	}
}

// describeTree returns the schema document for root.
func describeTree(root *cobra.Command) map[string]any {
	exitCodes := make([]map[string]any, 0, len(exitCodeTable))
	for _, code := range ExitCodes() {
		exitCodes = append(exitCodes, map[string]any{"code": int(code), "meaning": code.Meaning(), "name": code.Name()})
	}
	return map[string]any{
		"commands":            describeChildren(root),
		"exitCodes":           exitCodes,
		"globalFlags":         describeFlags(root.PersistentFlags()),
		"name":                root.Name(),
		"outputSchemaVersion": SchemaVersion,
		"schemaVersion":       SchemaVersion,
		"version":             version.Get().Version,
	}
}

func describeChildren(cmd *cobra.Command) []map[string]any {
	children := make([]map[string]any, 0)
	for _, child := range cmd.Commands() {
		if child.Hidden || child.Deprecated != "" {
			continue
		}
		children = append(children, describeCommand(child))
	}
	return children
}

func describeCommand(cmd *cobra.Command) map[string]any {
	boolOf := func(key string) bool {
		v, _ := strconv.ParseBool(cmd.Annotations[key])
		return v
	}
	return map[string]any{
		"commands":    describeChildren(cmd),
		"description": cmd.Long,
		"example":     cmd.Example,
		"flags":       describeFlags(cmd.LocalNonPersistentFlags()),
		"name":        cmd.Name(),
		"path":        cmd.CommandPath(),
		"safety": map[string]bool{
			annotationDestructive: boolOf(annotationDestructive),
			annotationIdempotent:  boolOf(annotationIdempotent),
			annotationReadOnly:    boolOf(annotationReadOnly),
		},
		"summary": cmd.Short,
		"usage":   cmd.UseLine(),
	}
}

func describeFlags(flags *pflag.FlagSet) []map[string]any {
	out := make([]map[string]any, 0)
	flags.VisitAll(func(f *pflag.Flag) {
		if f.Hidden {
			return
		}
		flag := map[string]any{
			"default":   f.DefValue,
			"name":      f.Name,
			"shorthand": f.Shorthand,
			"type":      f.Value.Type(),
			"usage":     f.Usage,
		}
		if v, ok := f.Value.(*enumValue); ok {
			flag["enum"] = v.allowed
		}
		out = append(out, flag)
	})
	return out
}
```

### `internal/cli/version.go`

```go
package cli

import (
	"github.com/spf13/cobra"

	"__MODULE__/internal/version"
)

// versionRecord is the --json form of the version command.
type versionRecord struct {
	version.Info
	SchemaVersion int `json:"schemaVersion"`
}

func newVersionCommand(a *App) *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the version and build metadata",
		Long: "Use version for build metadata (commit, commit date, Go version, platform); `__BIN__ --version` " +
			"prints only the version.",
		Example:     "  __BIN__ version --json",
		Args:        cobra.NoArgs,
		Annotations: safety(true, false, true),
		RunE: func(*cobra.Command, []string) error {
			info := version.Get()
			return a.emit("__BIN__ "+info.String(), versionRecord{Info: info, SchemaVersion: SchemaVersion})
		},
	}
}
```
