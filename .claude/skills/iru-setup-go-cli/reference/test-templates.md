# Test templates — unit tests for `internal/cli` and the end-to-end scripts

Two layers, both run by plain `go test ./...`:

- **Unit tests** (`internal/cli/*_test.go`, package `cli`): drive an `App` with in-memory streams, a fake
  environment and a fake terminal detector, so every branch of the colour, prompt, config, error-envelope and
  signal code is reachable without a terminal.
- **End-to-end scripts** (`testdata/script/*.txtar`): testscript runs the real `main` (registered in
  `cmd/__BIN__/main_test.go`, see `module-templates.md`) as a child process with an empty, controlled environment, and
  asserts stdout, stderr and the exact exit code (the custom `status CODE exec …` command) for every code the scaffold
  can emit, the no-TTY behaviour (no colour, no prompt), `NO_COLOR`/`FORCE_COLOR`, config precedence, and JSON golden
  output. Refresh golden sections after an intentional output change with
  `go test ./cmd/__BIN__ -run TestScripts -update` and review the diff.

The `ui: tui` additions (`internal/tui/model_test.go`, `internal/cli/browse_test.go`, `testdata/script/browse.txtar`)
are in `tui-templates.md`. Conditional markers and `__TOKEN__` placeholders work as described in SKILL.md.

### `internal/cli/helpers_test.go`

```go
package cli

import (
	"bytes"
	"strings"
)

// newTestApp returns an App with empty stdin, captured stdout/stderr, the
// given environment only, and no terminal on any stream.
func newTestApp(env map[string]string) (*App, *bytes.Buffer, *bytes.Buffer) {
	var out, errOut bytes.Buffer
	app := NewApp(strings.NewReader(""), &out, &errOut)
	app.Getenv = func(key string) string { return env[key] }
	app.IsTerminal = func(any) bool { return false }
	return app, &out, &errOut
}

// failingWriter fails every write with err.
type failingWriter struct{ err error }

func (w failingWriter) Write([]byte) (int, error) { return 0, w.err }

// failingReader fails every read with err.
type failingReader struct{ err error }

func (r failingReader) Read([]byte) (int, error) { return 0, r.err }
```

### `internal/cli/root_test.go`

```go
package cli

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"syscall"
	"testing"

	"github.com/spf13/cobra"

	"__MODULE__/internal/version"
)

func TestMainRunsAgainstTheProcessEnvironment(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("__ENV___CONFIG", "")
	t.Setenv("__ENV___FORMAT", "")
	var out, errOut bytes.Buffer
	if code := Main(context.Background(), []string{"greet", "Ada", "--no-color"}, strings.NewReader(""), &out, &errOut); code != 0 {
		t.Fatalf("exit code = %d, stderr %q", code, errOut.String())
	}
	if out.String() != "Hello, Ada!\n" {
		t.Fatalf("stdout = %q", out.String())
	}
}

func TestExitCodesAndStreams(t *testing.T) {
	tests := []struct {
		name   string
		args   []string
		code   ExitCode
		stdout string
		stderr string
	}{
		{"success", []string{"greet", "Ada"}, ExitOK, "Hello, Ada!\n", ""},
		{"unknown command", []string{"frobnicate"}, ExitUsage, "", `unknown command "frobnicate"`},
		{"unknown flag", []string{"greet", "--frobnicate"}, ExitUsage, "", "usage: __BIN__ greet [NAME] [flags]"},
		{"too many arguments", []string{"greet", "Ada", "Grace"}, ExitUsage, "", "hint: See `__BIN__ greet --help`."},
		{"bad format", []string{"--format", "xml", "greet", "Ada"}, ExitUsage, "", "must be one of text, json, jsonl"},
		{"quiet and verbose", []string{"-q", "-v", "greet", "Ada"}, ExitUsage, "", "none of the others can be"},
		{"missing name without a terminal", []string{"greet"}, ExitUsage, "", "greet needs a NAME"},
		{"unknown completion shell", []string{"completion", "tcsh"}, ExitUsage, "", `unknown command "tcsh"`},
		{"empty name", []string{"greet", " "}, ExitValidation, "", "NAME must not be empty."},
		{"help", []string{"--help"}, ExitOK, "Usage:", ""},
		{"bare invocation prints help", nil, ExitOK, "Available Commands:", ""},
		{"version flag", []string{"--version"}, ExitOK, "__BIN__ " + version.Get().Version + "\n", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, out, errOut := newTestApp(nil)
			if code := app.Run(context.Background(), tt.args); code != tt.code {
				t.Fatalf("exit code = %d, want %d (stderr %q)", code, tt.code, errOut.String())
			}
			if !strings.Contains(out.String(), tt.stdout) || (tt.stdout == "" && out.Len() != 0) {
				t.Fatalf("stdout = %q, want %q", out.String(), tt.stdout)
			}
			if !strings.Contains(errOut.String(), tt.stderr) || (tt.stderr == "" && errOut.Len() != 0) {
				t.Fatalf("stderr = %q, want %q", errOut.String(), tt.stderr)
			}
		})
	}
}

func TestJSONErrorEnvelopeGoesToStderr(t *testing.T) {
	app, out, errOut := newTestApp(nil)
	if code := app.Run(context.Background(), []string{"greet", "", "--json"}); code != ExitValidation {
		t.Fatalf("exit code = %d", code)
	}
	want := "{\"error\":{\"code\":\"INVALID_NAME\",\"details\":{},\"exitCode\":3," +
		"\"hint\":\"Pass a non-empty NAME, e.g. `__BIN__ greet Ada`.\",\"message\":\"NAME must not be empty.\"," +
		"\"retryable\":false},\"schemaVersion\":1}\n"
	if out.Len() != 0 || errOut.String() != want {
		t.Fatalf("stdout %q\nstderr %q\nwant   %q", out, errOut, want)
	}
}

func TestParseErrorsBeforeConfigurationKeepJSONMode(t *testing.T) {
	app, _, errOut := newTestApp(nil)
	if code := app.Run(context.Background(), []string{"--json", "frobnicate"}); code != ExitUsage {
		t.Fatalf("exit code = %d", code)
	}
	if !strings.HasPrefix(errOut.String(), `{"error":{"code":"USAGE"`) {
		t.Fatalf("stderr = %q", errOut)
	}
	app, _, errOut = newTestApp(nil)
	app.Run(context.Background(), []string{"--format", "jsonl", "frobnicate"})
	if !strings.HasPrefix(errOut.String(), `{"error":`) {
		t.Fatalf("stderr = %q", errOut)
	}
}

func TestWriteFailuresAreInternalErrorsButBrokenPipesAreNot(t *testing.T) {
	app, _, errOut := newTestApp(nil)
	app.Out = failingWriter{errors.New("disk full")}
	if code := app.Run(context.Background(), []string{"greet", "Ada"}); code != ExitInternal {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if !strings.Contains(errOut.String(), "internal error: disk full") {
		t.Fatalf("stderr = %q", errOut)
	}

	app, _, errOut = newTestApp(nil)
	app.Out = failingWriter{syscall.EPIPE}
	if code := app.Run(context.Background(), []string{"greet", "Ada"}); code != ExitOK {
		t.Fatalf("exit code = %d, want 0 on a broken pipe", code)
	}
	if errOut.Len() != 0 {
		t.Fatalf("stderr = %q, want nothing on a broken pipe", errOut)
	}
}

func TestPromptOnlyOnATerminal(t *testing.T) {
	app, out, errOut := newTestApp(nil)
	app.IsTerminal = func(any) bool { return true }
	app.In = strings.NewReader("Ada\n")
	if code := app.Run(context.Background(), []string{"greet", "--no-color"}); code != ExitOK {
		t.Fatalf("exit code = %d, stderr %q", code, errOut)
	}
	if out.String() != "Hello, Ada!\n" || errOut.String() != "Name: " {
		t.Fatalf("stdout %q, stderr %q", out, errOut)
	}

	for name, env := range map[string]map[string]string{
		"--no-input":        nil,
		"__ENV___NO_INPUT": {"__ENV___NO_INPUT": "1"},
		"CI":                {"CI": "true"},
	} {
		t.Run(name, func(t *testing.T) {
			app, _, errOut := newTestApp(env)
			app.IsTerminal = func(any) bool { return true }
			args := []string{"greet"}
			if env == nil {
				args = append(args, "--no-input")
			}
			if code := app.Run(context.Background(), args); code != ExitUsage {
				t.Fatalf("exit code = %d, want 2", code)
			}
			if strings.Contains(errOut.String(), "Name:") {
				t.Fatalf("prompted although disabled: %q", errOut)
			}
		})
	}
}

func TestPromptReadFailureIsInternal(t *testing.T) {
	app, _, _ := newTestApp(nil)
	app.IsTerminal = func(any) bool { return true }
	app.In = failingReader{errors.New("tty gone")}
	if code := app.Run(context.Background(), []string{"greet"}); code != ExitInternal {
		t.Fatalf("exit code = %d, want 1", code)
	}
	app, _, _ = newTestApp(nil)
	app.IsTerminal = func(any) bool { return true }
	app.Err = failingWriter{errors.New("stderr closed")}
	if code := app.Run(context.Background(), []string{"greet"}); code != ExitInternal {
		t.Fatalf("exit code = %d, want 1", code)
	}
}

func TestColorFollowsTheTerminalAndTheEnvironment(t *testing.T) {
	tests := []struct {
		name  string
		tty   bool
		env   map[string]string
		args  []string
		color bool
	}{
		{"terminal", true, nil, nil, true},
		{"pipe", false, nil, nil, false},
		{"NO_COLOR", true, map[string]string{"NO_COLOR": "1"}, nil, false},
		{"__ENV___NO_COLOR", true, map[string]string{"__ENV___NO_COLOR": "true"}, nil, false},
		{"TERM=dumb", true, map[string]string{"TERM": "dumb"}, nil, false},
		{"FORCE_COLOR on a pipe", false, map[string]string{"FORCE_COLOR": "1"}, nil, true},
		{"--no-color beats FORCE_COLOR", true, map[string]string{"FORCE_COLOR": "1"}, []string{"--no-color"}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, out, _ := newTestApp(tt.env)
			app.IsTerminal = func(any) bool { return tt.tty }
			app.Run(context.Background(), append([]string{"greet", "Ada"}, tt.args...))
			if got := strings.Contains(out.String(), "\x1b[1mAda\x1b[0m"); got != tt.color {
				t.Fatalf("styled = %v, want %v: %q", got, tt.color, out)
			}
		})
	}

	app, _, errOut := newTestApp(nil)
	app.IsTerminal = func(any) bool { return true }
	app.Run(context.Background(), []string{"greet", ""})
	if !strings.HasPrefix(errOut.String(), "\x1b[1;31m__BIN__:\x1b[0m") {
		t.Fatalf("stderr on a terminal is not styled: %q", errOut)
	}
}

func TestOutputFormats(t *testing.T) {
	app, out, _ := newTestApp(nil)
	app.Run(context.Background(), []string{"greet", "Ada", "--format", "jsonl"})
	want := "{\"greeting\":\"Hello, Ada!\",\"name\":\"Ada\",\"schemaVersion\":1}\n" +
		"{\"nextCursor\":null,\"schemaVersion\":1,\"total\":1,\"type\":\"summary\"}\n"
	if out.String() != want {
		t.Fatalf("jsonl = %q", out)
	}
	app, out, _ = newTestApp(nil)
	app.Run(context.Background(), []string{"greet", "<Ada & co>", "--json"})
	if out.String() != "{\"greeting\":\"Hello, <Ada & co>!\",\"name\":\"<Ada & co>\",\"schemaVersion\":1}\n" {
		t.Fatalf("json = %q", out)
	}
}

func TestDiagnosticsGoToStderr(t *testing.T) {
	app, out, errOut := newTestApp(nil)
	app.Run(context.Background(), []string{"-v", "--log-format", "json", "greet", "Ada"})
	if out.String() != "Hello, Ada!\n" {
		t.Fatalf("stdout = %q", out)
	}
	if !strings.Contains(errOut.String(), `"msg":"configuration resolved"`) || strings.Contains(errOut.String(), `"time"`) {
		t.Fatalf("stderr = %q", errOut)
	}
	app, _, errOut = newTestApp(nil)
	app.Run(context.Background(), []string{"-vv", "greet", "Ada"})
	if !strings.Contains(errOut.String(), `level=DEBUG msg="greeting built"`) {
		t.Fatalf("stderr = %q", errOut)
	}
	app, _, errOut = newTestApp(nil)
	app.Run(context.Background(), []string{"-q", "greet", "Ada"})
	if errOut.Len() != 0 {
		t.Fatalf("--quiet stderr = %q", errOut)
	}
}

func TestEveryCommandDeclaresItsSafety(t *testing.T) {
	app, _, _ := newTestApp(nil)
	walk(app.Command(), func(cmd *cobra.Command) {
		for _, key := range []string{annotationReadOnly, annotationDestructive, annotationIdempotent} {
			if cmd.Annotations[key] == "" {
				t.Errorf("%s has no %s annotation", cmd.CommandPath(), key)
			}
		}
	})
}

func TestPanicsAreInternalErrorsNotUsageErrors(t *testing.T) {
	for _, args := range [][]string{{"explode"}, {"explode", "--json"}} {
		app, out, errOut := newTestApp(nil)
		root := app.Command()
		root.AddCommand(&cobra.Command{Use: "explode", RunE: func(*cobra.Command, []string) error { panic("kaboom") }})
		finishTree(root)
		if code := app.run(context.Background(), root, args); code != ExitInternal {
			t.Fatalf("%v: exit code = %d, want 1", args, code)
		}
		if out.Len() != 0 || !strings.Contains(errOut.String(), "panic: kaboom") {
			t.Fatalf("%v: stdout %q, stderr %q", args, out, errOut)
		}
		if args[len(args)-1] == "--json" && !strings.HasPrefix(errOut.String(), `{"error":{"code":"INTERNAL"`) {
			t.Fatalf("stderr = %q", errOut)
		}
	}
}

func TestNonContractErrorsFromCommandsAreInternal(t *testing.T) {
	app, _, errOut := newTestApp(nil)
	root := app.Command()
	root.AddCommand(&cobra.Command{Use: "fail", RunE: func(*cobra.Command, []string) error { return errors.New("boom") }})
	finishTree(root)
	if code := app.run(context.Background(), root, []string{"fail"}); code != ExitInternal {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if !strings.Contains(errOut.String(), "This is a bug in __BIN__") {
		t.Fatalf("stderr = %q", errOut)
	}
}
```

### `internal/cli/signals_test.go`

```go
//go:build unix

package cli

import (
	"context"
	"os"
	"strings"
	"syscall"
	"testing"

	"github.com/spf13/cobra"
)

// startBlocking runs a command that waits for cancellation, and returns once
// it is running (signal handling is installed by then).
func startBlocking(t *testing.T, app *App, cleanup func()) <-chan ExitCode {
	t.Helper()
	root := app.Command()
	started := make(chan struct{})
	root.AddCommand(&cobra.Command{Use: "block", RunE: func(cmd *cobra.Command, _ []string) error {
		close(started)
		<-cmd.Context().Done()
		cleanup()
		return nil
	}})
	result := make(chan ExitCode, 1)
	go func() { result <- app.run(context.Background(), root, []string{"block"}) }()
	<-started
	return result
}

func TestSignalsExitWith128PlusN(t *testing.T) {
	for sig, want := range map[syscall.Signal]ExitCode{syscall.SIGINT: ExitInterrupted, syscall.SIGTERM: ExitTerminated} {
		app, out, errOut := newTestApp(nil)
		result := startBlocking(t, app, func() {})
		if err := syscall.Kill(os.Getpid(), sig); err != nil {
			t.Fatal(err)
		}
		if code := <-result; code != want {
			t.Fatalf("%v: exit code = %d, want %d", sig, code, want)
		}
		if out.Len() != 0 || !strings.HasPrefix(errOut.String(), "__BIN__: Interrupted by SIG") {
			t.Fatalf("%v: stdout %q, stderr %q", sig, out, errOut)
		}
	}
}

func TestSecondSignalExitsImmediately(t *testing.T) {
	app, _, _ := newTestApp(nil)
	exited := make(chan int, 1)
	release := make(chan struct{})
	cancelled := make(chan struct{})
	app.exit = func(code int) {
		exited <- code
		close(release)
	}
	result := startBlocking(t, app, func() {
		close(cancelled)
		<-release
	})
	if err := syscall.Kill(os.Getpid(), syscall.SIGINT); err != nil {
		t.Fatal(err)
	}
	<-cancelled
	if err := syscall.Kill(os.Getpid(), syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	if code := <-exited; code != int(ExitTerminated) {
		t.Fatalf("immediate exit code = %d, want 143", code)
	}
	if code := <-result; code != ExitInterrupted {
		t.Fatalf("exit code = %d, want 130", code)
	}
}
```

### `internal/cli/config_test.go`

```go
package cli

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func writeConfig(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestConfigPath(t *testing.T) {
	tests := []struct {
		name     string
		env      map[string]string
		want     string
		explicit bool
	}{
		{"explicit", map[string]string{"__ENV___CONFIG": "/etc/x.toml", "XDG_CONFIG_HOME": "/xdg"}, "/etc/x.toml", true},
		{"xdg", map[string]string{"XDG_CONFIG_HOME": "/xdg", "HOME": "/home/u"}, filepath.Join("/xdg", "__BIN__", "config.toml"), false},
		{"none", nil, "", false},
	}
	if runtime.GOOS == "windows" {
		tests = append(tests, struct {
			name     string
			env      map[string]string
			want     string
			explicit bool
		}{"appdata", map[string]string{"APPDATA": `C:\Users\u\AppData\Roaming`}, filepath.Join(`C:\Users\u\AppData\Roaming`, "__BIN__", "config.toml"), false})
	} else {
		tests = append(tests, struct {
			name     string
			env      map[string]string
			want     string
			explicit bool
		}{"home", map[string]string{"HOME": "/home/u"}, filepath.Join("/home/u", ".config", "__BIN__", "config.toml"), false})
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, explicit := configPath(func(k string) string { return tt.env[k] })
			if got != tt.want || explicit != tt.explicit {
				t.Fatalf("configPath() = %q, %v; want %q, %v", got, explicit, tt.want, tt.explicit)
			}
		})
	}
}

func TestConfigPrecedence(t *testing.T) {
	path := writeConfig(t, "format = \"json\"\nno-color = true\nno-input = true\n")
	tests := []struct {
		name string
		env  map[string]string
		args []string
		want string
	}{
		{"file", map[string]string{"__ENV___CONFIG": path}, nil, `{"greeting"`},
		{"env beats file", map[string]string{"__ENV___CONFIG": path, "__ENV___FORMAT": "text"}, nil, "Hello, Ada!\n"},
		{"flag beats env", map[string]string{"__ENV___CONFIG": path, "__ENV___FORMAT": "text"}, []string{"--format", "jsonl"}, `"type":"summary"`},
		{"--json beats env", map[string]string{"__ENV___FORMAT": "text"}, []string{"--json"}, `{"greeting"`},
		{"missing default file is fine", map[string]string{"XDG_CONFIG_HOME": t.TempDir()}, nil, "Hello, Ada!\n"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, out, errOut := newTestApp(tt.env)
			if code := app.Run(context.Background(), append([]string{"greet", "Ada"}, tt.args...)); code != ExitOK {
				t.Fatalf("exit code = %d, stderr %q", code, errOut)
			}
			if !strings.Contains(out.String(), tt.want) {
				t.Fatalf("stdout = %q, want %q", out, tt.want)
			}
		})
	}
}

func TestConfigSourcesAndBooleans(t *testing.T) {
	path := writeConfig(t, "no-color = true\nno-input = true\n")
	app, _, _ := newTestApp(map[string]string{"__ENV___CONFIG": path})
	app.IsTerminal = func(any) bool { return true }
	if code := app.Run(context.Background(), []string{"greet"}); code != ExitUsage {
		t.Fatalf("no-input from the file: exit code = %d, want 2", code)
	}
	if app.cfg.File != path || app.cfg.Sources["no-color"] != "file" || !app.cfg.NoColor {
		t.Fatalf("cfg = %+v", app.cfg)
	}
	app, _, _ = newTestApp(map[string]string{"__ENV___CONFIG": path, "__ENV___NO_INPUT": "0", "__ENV___NO_COLOR": "0"})
	app.Run(context.Background(), []string{"greet", "Ada", "--no-color"})
	if app.cfg.NoInput || app.cfg.Sources["no-input"] != "env" || app.cfg.Sources["no-color"] != "flag" {
		t.Fatalf("cfg = %+v", app.cfg)
	}
}

func TestConfigErrors(t *testing.T) {
	dir := t.TempDir()
	tests := []struct {
		name string
		env  map[string]string
		code ExitCode
		id   string
	}{
		{"explicit file missing", map[string]string{"__ENV___CONFIG": filepath.Join(dir, "missing.toml")}, ExitNotFound, "CONFIG_NOT_FOUND"},
		{"unreadable", map[string]string{"__ENV___CONFIG": dir}, ExitValidation, "CONFIG_UNREADABLE"},
		{"not TOML", map[string]string{"__ENV___CONFIG": writeConfig(t, "format = ")}, ExitValidation, "CONFIG_INVALID"},
		{"unknown key", map[string]string{"__ENV___CONFIG": writeConfig(t, "colour = true\n")}, ExitValidation, "CONFIG_INVALID"},
		{"bad format in file", map[string]string{"__ENV___CONFIG": writeConfig(t, "format = \"xml\"\n")}, ExitValidation, "CONFIG_INVALID"},
		{"bad format in env", map[string]string{"__ENV___FORMAT": "xml"}, ExitUsage, "USAGE"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, out, errOut := newTestApp(tt.env)
			if code := app.Run(context.Background(), []string{"greet", "Ada", "--json"}); code != tt.code {
				t.Fatalf("exit code = %d, want %d (stderr %q)", code, tt.code, errOut)
			}
			if out.Len() != 0 || !strings.Contains(errOut.String(), `"code":"`+tt.id+`"`) {
				t.Fatalf("stdout %q, stderr %q", out, errOut)
			}
		})
	}
}
```

### `internal/cli/exitcode_test.go`

```go
package cli

import "testing"

func TestExitCodeTable(t *testing.T) {
	want := map[ExitCode]string{
		0: "OK", 1: "INTERNAL", 2: "USAGE", 3: "VALIDATION", 4: "NOT_FOUND", 5: "CONFIRMATION_REQUIRED",
		6: "RETRYABLE", 7: "AUTH", 130: "INTERRUPTED", 143: "TERMINATED",
	}
	codes := ExitCodes()
	if len(codes) != len(want) {
		t.Fatalf("%d codes, want %d", len(codes), len(want))
	}
	for i, code := range codes {
		if i > 0 && codes[i-1] >= code {
			t.Fatalf("codes not ascending: %v", codes)
		}
		if code.Name() != want[code] || code.Meaning() == "" {
			t.Fatalf("code %d: name %q meaning %q", code, code.Name(), code.Meaning())
		}
	}
	if ExitCode(99).Name() != "UNKNOWN" || ExitCode(99).Meaning() != "" {
		t.Fatal("unknown code has a name or meaning")
	}
}
```

### `internal/cli/errors_test.go`

```go
package cli

import (
	"bytes"
	"errors"
	"testing"
)

func TestEnvelope(t *testing.T) {
	cause := errors.New("connection reset")
	e := NewError(ExitRetryable, "", "Upstream timed out.", "Retry in a minute.")
	e.RetryAfterSeconds = 30
	e.Err = cause
	var buf bytes.Buffer
	if err := writeJSON(&buf, e.envelope()); err != nil {
		t.Fatal(err)
	}
	want := `{"error":{"code":"RETRYABLE","details":{},"exitCode":6,"hint":"Retry in a minute.",` +
		`"message":"Upstream timed out.","retryAfterSeconds":30,"retryable":true},"schemaVersion":1}` + "\n"
	if buf.String() != want {
		t.Fatalf("envelope = %s", buf.String())
	}
	if !errors.Is(e, cause) || e.Error() != "Upstream timed out." {
		t.Fatal("Error/Unwrap mismatch")
	}
}

func TestInternalErrorWrapsTheCause(t *testing.T) {
	cause := errors.New("boom")
	e := internalError(cause)
	if e.Code != ExitInternal || !errors.Is(e, cause) || e.Hint == "" {
		t.Fatalf("internalError = %+v", e)
	}
	if e.envelope()["error"].(map[string]any)["code"] != "INTERNAL" {
		t.Fatal("default id is not the code name")
	}
}
```

### `internal/cli/output_test.go`

```go
package cli

import (
	"bytes"
	"testing"
)

func TestWriteJSONIsCanonical(t *testing.T) {
	var buf bytes.Buffer
	err := writeJSON(&buf, map[string]any{"b": 1, "a": []any{"<x>", 2.5}, "c": map[string]any{"z": nil, "y": true}})
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"a":["<x>",2.5],"b":1,"c":{"y":true,"z":null}}` + "\n"; buf.String() != want {
		t.Fatalf("got %s want %s", buf.String(), want)
	}
	if err := writeJSON(&buf, make(chan int)); err == nil {
		t.Fatal("unsupported value did not fail")
	}
	if err := writeIndentedJSON(&buf, make(chan int)); err == nil {
		t.Fatal("unsupported value did not fail")
	}
}

func TestWriteIndentedJSON(t *testing.T) {
	var buf bytes.Buffer
	if err := writeIndentedJSON(&buf, map[string]any{"b": 1, "a": 2}); err != nil {
		t.Fatal(err)
	}
	if want := "{\n  \"a\": 2,\n  \"b\": 1\n}\n"; buf.String() != want {
		t.Fatalf("got %q", buf.String())
	}
}

func TestFormatParsing(t *testing.T) {
	for _, s := range formatNames {
		if f, err := parseFormat(s); err != nil || string(f) != s {
			t.Fatalf("parseFormat(%q) = %q, %v", s, f, err)
		}
	}
	if _, err := parseFormat("yaml"); err == nil {
		t.Fatal("yaml accepted")
	}
	if FormatText.Machine() || !FormatJSON.Machine() || !FormatJSONL.Machine() {
		t.Fatal("Machine() is wrong")
	}
	value := "text"
	v := &enumValue{&value, formatNames}
	if err := v.Set("jsonl"); err != nil || v.String() != "jsonl" || v.Type() != "string" {
		t.Fatalf("enumValue = %q, %v", v.String(), err)
	}
	if err := v.Set("xml"); err == nil {
		t.Fatal("xml accepted")
	}
}

func TestEmitJSONLStopsAtTheFirstFailure(t *testing.T) {
	app, _, _ := newTestApp(nil)
	app.cfg.Format = FormatJSONL
	app.Out = failingWriter{errBoom}
	if err := app.emit("", map[string]any{}); err == nil {
		t.Fatal("emit did not report the write failure")
	}
}
```

### `internal/cli/term_test.go`

```go
package cli

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

var errBoom = errors.New("boom")

func TestColorEnabled(t *testing.T) {
	env := func(kv ...string) func(string) string {
		m := map[string]string{}
		for len(kv) >= 2 {
			m[kv[0]], kv = kv[1], kv[2:]
		}
		return func(k string) string { return m[k] }
	}
	tests := []struct {
		name                  string
		tty, noColor, machine bool
		getenv                func(string) string
		want                  bool
	}{
		{"tty", true, false, false, env(), true},
		{"pipe", false, false, false, env(), false},
		{"flag", true, true, false, env(), false},
		{"machine", true, false, true, env(), false},
		{"NO_COLOR", true, false, false, env("NO_COLOR", "1"), false},
		{"FORCE_COLOR", false, false, false, env("FORCE_COLOR", "1"), true},
		{"FORCE_COLOR=0", false, false, false, env("FORCE_COLOR", "0"), false},
		{"CLICOLOR_FORCE", false, false, false, env("CLICOLOR_FORCE", "1"), true},
		{"FORCE_COLOR loses to NO_COLOR", false, false, false, env("FORCE_COLOR", "1", "NO_COLOR", "x"), false},
		{"dumb", true, false, false, env("TERM", "dumb"), false},
	}
	for _, tt := range tests {
		if got := colorEnabled(tt.tty, tt.noColor, tt.machine, tt.getenv); got != tt.want {
			t.Errorf("%s: colorEnabled = %v, want %v", tt.name, got, tt.want)
		}
	}
}

func TestCanPromptAndTruthy(t *testing.T) {
	none := func(string) string { return "" }
	ci := func(k string) string {
		if k == "CI" {
			return "true"
		}
		return ""
	}
	if !canPrompt(true, true, false, none) || canPrompt(false, true, false, none) || canPrompt(true, false, false, none) ||
		canPrompt(true, true, true, none) || canPrompt(true, true, false, ci) {
		t.Fatal("canPrompt policy is wrong")
	}
	for v, want := range map[string]bool{"1": true, "TRUE": true, " yes ": true, "on": true, "0": false, "": false, "no": false} {
		if truthy(v) != want {
			t.Errorf("truthy(%q) != %v", v, want)
		}
	}
	if paint(false, sgrBold, "x") != "x" || paint(true, sgrBold, "x") != "\x1b[1mx\x1b[0m" {
		t.Fatal("paint is wrong")
	}
}

func TestIsTerminal(t *testing.T) {
	if isTerminal(&bytes.Buffer{}) {
		t.Fatal("a buffer is not a terminal")
	}
	f, err := os.Create(filepath.Join(t.TempDir(), "out"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	if isTerminal(f) {
		t.Fatal("a regular file is not a terminal")
	}
}
```

### `internal/cli/greet_test.go`

```go
package cli

import (
	"errors"
	"strings"
	"testing"
)

func TestGreet(t *testing.T) {
	tests := []struct {
		name, in, want string
		code           ExitCode
	}{
		{"plain", "Ada", "Hello, Ada!", ExitOK},
		{"trimmed", "  Ada \t", "Hello, Ada!", ExitOK},
		{"unicode counts characters", strings.Repeat("é", MaxNameLength), "Hello, " + strings.Repeat("é", MaxNameLength) + "!", ExitOK},
		{"empty", "", "", ExitValidation},
		{"blank", "   ", "", ExitValidation},
		{"too long", strings.Repeat("x", MaxNameLength+1), "", ExitValidation},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			g, err := Greet(tt.in)
			var e *Error
			switch {
			case tt.code == ExitOK && (err != nil || g.Greeting != tt.want || g.SchemaVersion != SchemaVersion):
				t.Fatalf("Greet(%q) = %+v, %v", tt.in, g, err)
			case tt.code != ExitOK && (!errors.As(err, &e) || e.Code != tt.code || e.Hint == ""):
				t.Fatalf("Greet(%q) error = %v, want code %d with a hint", tt.in, err, tt.code)
			}
		})
	}
}
```

### `internal/cli/schema_test.go`

```go
package cli

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestSchemaDescribesTheWholeTree(t *testing.T) {
	app, out, _ := newTestApp(nil)
	if code := app.Run(context.Background(), []string{"schema", "--json"}); code != ExitOK {
		t.Fatalf("exit code = %d", code)
	}
	var doc struct {
		Commands []struct {
			Name   string          `json:"name"`
			Safety map[string]bool `json:"safety"`
			Flags  []any           `json:"flags"`
		} `json:"commands"`
		ExitCodes []struct {
			Code int    `json:"code"`
			Name string `json:"name"`
		} `json:"exitCodes"`
		GlobalFlags []struct {
			Name string   `json:"name"`
			Enum []string `json:"enum"`
		} `json:"globalFlags"`
		SchemaVersion int `json:"schemaVersion"`
	}
	if err := json.Unmarshal(out.Bytes(), &doc); err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, c := range doc.Commands {
		names[c.Name] = true
		if !c.Safety["readOnly"] {
			t.Errorf("%s is not read-only", c.Name)
		}
	}
	for _, want := range []string{"greet", "schema", "version", "completion", "help"} {
		if !names[want] {
			t.Errorf("schema lacks %q", want)
		}
	}
	if len(doc.ExitCodes) != len(ExitCodes()) || doc.SchemaVersion != SchemaVersion {
		t.Fatalf("exitCodes %d, schemaVersion %d", len(doc.ExitCodes), doc.SchemaVersion)
	}
	for _, f := range doc.GlobalFlags {
		if f.Name == "format" && strings.Join(f.Enum, ",") != "text,json,jsonl" {
			t.Fatalf("format enum = %v", f.Enum)
		}
	}
}

func TestSchemaIsIndentedForPeople(t *testing.T) {
	app, out, _ := newTestApp(nil)
	app.Run(context.Background(), []string{"schema"})
	if !strings.HasPrefix(out.String(), "{\n  \"commands\": [") {
		t.Fatalf("schema text output = %.40q", out)
	}
	app, out, _ = newTestApp(nil)
	app.Run(context.Background(), []string{"schema", "--format", "jsonl"})
	if lines := strings.Count(out.String(), "\n"); lines != 2 {
		t.Fatalf("jsonl schema has %d lines, want document + summary", lines)
	}
}
```

### `internal/cli/version_test.go`

```go
package cli

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"__MODULE__/internal/version"
)

func TestVersionCommand(t *testing.T) {
	app, out, _ := newTestApp(nil)
	app.Run(context.Background(), []string{"version"})
	if !strings.HasPrefix(out.String(), "__BIN__ "+version.Get().Version+" (commit ") {
		t.Fatalf("text = %q", out)
	}
	app, out, _ = newTestApp(nil)
	app.Run(context.Background(), []string{"version", "--json"})
	var doc map[string]any
	if err := json.Unmarshal(out.Bytes(), &doc); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"commit", "date", "go", "platform", "schemaVersion", "version"} {
		if _, ok := doc[key]; !ok {
			t.Errorf("version --json lacks %q: %s", key, out)
		}
	}
}
```

### `testdata/script/greet.txtar`

```text
# The result goes to stdout only; nothing on stderr; no escape bytes on a pipe.
exec __BIN__ greet Ada
stdout '^Hello, Ada!$'
! stdout '\x1b'
! stderr .

# JSON: exactly one canonical document (golden), JSON Lines: record + summary.
exec __BIN__ greet Ada --json
cmp stdout greet.json
! stderr .
exec __BIN__ greet Ada --format jsonl
cmp stdout greet.jsonl
exec __BIN__ greet --json -- -dash-name
stdout '"name":"-dash-name"'

# FORCE_COLOR styles text even on a pipe; NO_COLOR, --no-color and --json win.
env FORCE_COLOR=1
exec __BIN__ greet Ada
stdout '\x1b\[1mAda\x1b\[0m'
exec __BIN__ greet Ada --no-color
! stdout '\x1b'
exec __BIN__ greet Ada --json
! stdout '\x1b'
env NO_COLOR=1
exec __BIN__ greet Ada
! stdout '\x1b'

-- greet.json --
{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}
-- greet.jsonl --
{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}
{"nextCursor":null,"schemaVersion":1,"total":1,"type":"summary"}
```

### `testdata/script/exit_codes.txtar`

```text
# Every exit code the scaffold can emit from the command line, with stdout empty
# on failure and a short message + hint on stderr.

# 0 OK
status 0 exec __BIN__ greet Ada

# 2 USAGE: unknown command, unknown flag, extra argument, invalid --format.
status 2 exec __BIN__ frobnicate
! stdout .
stderr 'unknown command "frobnicate" for "__BIN__"'
stderr 'hint: See `__BIN__ --help`.'
status 2 exec __BIN__ greet --frobnicate
stderr 'unknown flag: --frobnicate'
stderr 'usage: __BIN__ greet \[NAME\] \[flags\]'
status 2 exec __BIN__ greet Ada Grace
stderr 'accepts at most 1 arg'
status 2 exec __BIN__ --format xml greet Ada
stderr 'must be one of text, json, jsonl'

# 2 USAGE: a missing NAME without a terminal fails fast instead of prompting.
status 2 exec __BIN__ greet
! stderr 'Name:'
stderr 'greet needs a NAME'

# 3 VALIDATION, as the JSON error envelope on stderr in JSON mode (golden).
status 3 exec __BIN__ greet '' --json
! stdout .
cmp stderr empty-name.json
status 3 exec __BIN__ greet xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
stderr 'NAME is 65 characters long; the maximum is 64.'

# 4 NOT_FOUND: an explicitly named config file that doesn't exist.
env __ENV___CONFIG=$WORK/missing.toml
status 4 exec __BIN__ greet Ada --json
stderr '"code":"CONFIG_NOT_FOUND","details":\{\},"exitCode":4'
env __ENV___CONFIG=

-- empty-name.json --
{"error":{"code":"INVALID_NAME","details":{},"exitCode":3,"hint":"Pass a non-empty NAME, e.g. `__BIN__ greet Ada`.","message":"NAME must not be empty.","retryable":false},"schemaVersion":1}
```

### `testdata/script/config.txtar`

```text
# Precedence: flags > environment > config file > defaults.
env XDG_CONFIG_HOME=$WORK/config
exec __BIN__ greet Ada
cmp stdout greet.json
env __ENV___FORMAT=text
exec __BIN__ greet Ada
stdout '^Hello, Ada!$'
exec __BIN__ greet Ada --format json
cmp stdout greet.json

# -v reports where each setting came from, on stderr only; --log-format json makes it JSON.
exec __BIN__ -v --log-format json greet Ada
stdout '^Hello, Ada!$'
stderr '"msg":"configuration resolved"'
stderr '"formatSource":"env"'

# A bad environment value is a usage error (2); a bad file is a validation error (3).
env __ENV___FORMAT=xml
status 2 exec __BIN__ greet Ada
stderr '__ENV___FORMAT'
env __ENV___FORMAT=
env __ENV___CONFIG=$WORK/bad.toml
status 3 exec __BIN__ greet Ada --json
stderr '"code":"CONFIG_INVALID"'

-- config/__BIN__/config.toml --
format = "json"
-- bad.toml --
colour = true
-- greet.json --
{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}
```

### `testdata/script/help.txtar`

```text
# --help and --version are results: stdout, exit 0, nothing on stderr.
exec __BIN__ --help
stdout 'Usage:'
stdout 'greet'
! stderr .
exec __BIN__ greet --help
stdout 'Examples:'
stdout '__BIN__ greet Ada --json'
exec __BIN__ --version
stdout '^__BIN__ [0-9]+\.[0-9]+\.[0-9]+'
! stderr .
exec __BIN__ version --json
stdout '"schemaVersion":1'
stdout '"version":"[0-9]'

# A bare invocation prints the help and exits 0.
exec __BIN__
stdout 'Available Commands:'
```

### `testdata/script/completion.txtar`

```text
# Completion scripts are generated from the command tree for every supported shell.
exec __BIN__ completion bash
stdout 'bash completion V2 for __BIN__'
exec __BIN__ completion zsh
stdout '#compdef __BIN__'
exec __BIN__ completion fish
stdout 'fish completion for __BIN__'
exec __BIN__ completion powershell
stdout 'Register-ArgumentCompleter'
status 2 exec __BIN__ completion tcsh
stderr 'unknown command "tcsh"'
```

### `testdata/script/schema.txtar`

```text
# schema prints the machine-readable command tree: commands, flags, safety, exit codes.
exec __BIN__ schema --json
! stderr .
stdout '^\{"commands":\['
stdout '"name":"greet","path":"__BIN__ greet","safety":\{"destructive":false,"idempotent":true,"readOnly":true\}'
stdout '"exitCodes":\[\{"code":0,"meaning":"[^"]+","name":"OK"\}'
stdout '"name":"CONFIRMATION_REQUIRED"'
stdout '"enum":\["text","json","jsonl"\]'
exec __BIN__ schema
stdout '^  "commands": \['
```
