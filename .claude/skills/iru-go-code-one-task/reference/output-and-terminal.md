# Output, terminal, signals

Go mechanics for `cli-contract.md` §1, §3 and §4. Read that file for the *what*; this one is the *how*.

## Streams are injected, never global

`cli.Main(ctx, args, stdin, stdout, stderr) int` receives the streams; commands write through `cmd.OutOrStdout()` /
`cmd.ErrOrStderr()` and read `cmd.InOrStdin()`. No `fmt.Println`, `os.Stdout`, `log.Printf` to the default logger, or
`os.Exit` anywhere below `cmd/<bin>/main.go`. This is what makes every rule below testable in-process with
`bytes.Buffer`s.

- stdout: the result only. stderr: everything else (progress, diagnostics, warnings, hints, prompts, errors).
- `--quiet` suppresses informational stderr (never errors); `-v`/`-vv` adds diagnostics; neither changes stdout.
  Diagnostics go through one small logger on stderr — `log/slog` with `slog.NewTextHandler(errOut, …)` or
  `slog.NewJSONHandler` for `--log-format json`, level from `-v` count — created in the root, passed down, never
  `slog.SetDefault` in library code.

## JSON — deterministic by construction

In an `iru-setup-go-cli` scaffold, use what's there: `a.emit(text, record)` writes a result in the selected format
(text, one JSON document, or a JSON Lines record plus the summary line), `writeJSON` canonicalises any value by
re-decoding it into a generic value (`UseNumber`) before encoding — so object keys come out sorted whatever the struct
field order — and `a.reportError` writes the envelope. A repository without such a helper needs the rules below.

```go
// writeJSON writes v as one compact JSON document followed by a newline.
func writeJSON(w io.Writer, v any) error {
    enc := json.NewEncoder(w)
    enc.SetEscapeHTML(false) // keep <, >, & readable; output is still deterministic
    if err := enc.Encode(v); err != nil {
        return fmt.Errorf("write json: %w", err)
    }
    return nil
}
```

- `encoding/json` **sorts map keys** but writes **struct fields in declaration order**. The contract wants sorted
  keys, so payload and envelope structs declare fields in alphabetical order of their JSON names — and a comment on the
  struct says so (verified: `{"greeting":…,"name":…,"schemaVersion":1}` comes out byte-identical run to run).
- Every document carries `SchemaVersion int \`json:"schemaVersion"\``; lists add `Total`, `NextCursor *string`
  (`null` when absent — a pointer, not `omitempty`), `Truncated`. Slices that may be empty are initialised
  (`[]T{}`), so they marshal as `[]`, not `null`.
- No `omitempty` on contract fields whose absence would change meaning; no `time.Now()`, absolute paths, or map
  iteration order leaking into output. Sort slices by a documented key before writing.
- `jsonl`: one `enc.Encode` per record, then the summary line
  `{"nextCursor":null,"schemaVersion":1,"total":N,"type":"summary"}`.
- Stay on `encoding/json` (v1) unless the module already uses `encoding/json/v2`; don't mix them in one payload.

## The error envelope (stderr)

```go
// errorBody is the "error" member of the envelope (fields in key order).
type errorBody struct {
    Code      string         `json:"code"`
    Details   map[string]any `json:"details"`
    ExitCode  int            `json:"exitCode"`
    Hint      string         `json:"hint"`
    Message   string         `json:"message"`
    Retryable bool           `json:"retryable"`
}

type envelope struct {
    Error         errorBody `json:"error"`
    SchemaVersion int       `json:"schemaVersion"`
}
```

Written once by `cli.Main` in machine mode (`Details` defaults to `{}`, `Retryable` is `code == ExitRetryable`); in
text mode it prints `greeter: <message>` and `hint: <hint>`. Commands never write either themselves — they return a
`*cli.Error` (`errors.md`).

## TTY detection — per stream

```go
// isTerminal reports whether s is an *os.File attached to a terminal.
// Buffers and pipes injected by tests are never terminals.
func isTerminal(s any) bool {
    f, ok := s.(*os.File)
    return ok && term.IsTerminal(int(f.Fd())) // golang.org/x/term
}
```

Decide colour for **stdout** from stdout, colour/progress for **stderr** from stderr, and prompting from **stdin and
stderr** — never one global "is interactive".

## Colour policy and Lip Gloss v2

The scaffold's `term.go` has `colorEnabled(tty, noColor, machine, getenv)`, `canPrompt(stdinTTY, stderrTTY, noInput,
getenv)`, `a.stdoutColor()`/`a.stderrColor()`/`a.canPrompt()`, and `paint(enabled, sgr, s)` for plain SGR styling —
reuse them; the terminal check is the injectable `a.IsTerminal`. The generic version:

```go
func colorEnabled(w io.Writer, getenv func(string) string, noColorFlag, machine bool) bool {
    switch {
    case noColorFlag, machine, getenv("NO_COLOR") != "", getenv("TERM") == "dumb":
        return false
    case getenv("FORCE_COLOR") != "" || getenv("CLICOLOR_FORCE") != "":
        return true
    default:
        return isTerminal(w)
    }
}
```

- `getenv` is injected (`os.Getenv` in production, a map in tests) so tests stay parallel and a developer's
  environment can't change results.
- **Lip Gloss v2 (`charm.land/lipgloss/v2`) has no per-stream `Renderer`** (removed in v2; see its
  `UPGRADE_GUIDE_V2.md`): `Style.Render` always emits ANSI, and downsampling happens in the *writer*
  (`lipgloss.Println` writes through a package-level `colorprofile.Writer` bound to **os.Stdout** — never use it, it
  ignores the injected stream and the `--no-color` flag). So: decide with `colorEnabled` per stream, then either
  render plain text when colour is off (the scaffold does — simplest, and machine mode never touches styles), or wrap
  the stream once in `&colorprofile.Writer{Forward: w, Profile: p}` (`github.com/charmbracelet/colorprofile`) with
  `p = colorprofile.NoTTY` when off and `colorprofile.Detect(w, env)` when on, so styled strings are stripped or
  downsampled for that stream (verified: `NoTTY` strips every escape, `Detect` on a non-file writer returns `NoTTY`).
  `colorprofile` is already in `go.sum` through Lip Gloss, but importing it directly makes it a direct `require` —
  a `go.mod` change the task must allow; the plain-text route needs none.
- Never convey meaning by colour alone: keep `error:`/`warning:` words. Never style JSON.
- Test: `FORCE_COLOR=1` produces `\x1b[`; `FORCE_COLOR=1`+`NO_COLOR=1`, `TERM=dumb`, and a non-TTY buffer produce
  no `\x1b` at all (verified).

## Prompts

```go
func canPrompt(in io.Reader, errOut io.Writer, getenv func(string) string, noInput bool) bool {
    if noInput || getenv("GREETER_NO_INPUT") != "" || getenv("CI") == "true" {
        return false
    }
    return isTerminal(in) && isTerminal(errOut)
}
```

When `canPrompt` is false, fail fast: a missing value → usage error (2) naming the flag; a confirmation → exit 5
with `details.confirmCommand`. Prompts write to stderr and read stdin; secrets are read with `term.ReadPassword`
(no echo) or, better, a `--token-stdin` flag. A prompt's default answer for destructive actions is No.

## Signals — 130 / 143

`signal.NotifyContext` cancels a context but its cause is an unexported type, so it can't tell SIGINT (130) from
SIGTERM (143). The scaffold's `cli.WithSignals` records a typed cause instead:

```go
func WithSignals(parent context.Context) (ctx context.Context, stop func()) {
    ctx, cancel := context.WithCancelCause(parent)
    ch := make(chan os.Signal, 2)
    signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
    done := make(chan struct{})
    go func() {
        select {
        case s := <-ch:
            cancel(&signalError{sig: s, code: codeFor(s)}) // 130 or 143
        case <-done:
            return
        }
        select {
        case s := <-ch: // second Ctrl-C during cleanup exits immediately
            os.Exit(int(codeFor(s)))
        case <-done:
        }
    }()
    return ctx, func() { signal.Stop(ch); close(done); cancel(nil) }
}
```

`main` calls it, passes `ctx` to `cli.Main`, and `classify` maps the cause (`errors.md`). The scaffold does the same inside
`(*App).run` with `a.notifySignals` (`internal/cli/signals.go`) and maps the cause in `exitCode`. Verified on macOS:
`kill -TERM` → exit 143, `kill -INT` → exit 130. Code that blocks must select on `ctx.Done()` and pass `ctx` to every
I/O call (`concurrency.md`) or Ctrl-C won't stop it. `syscall.SIGTERM` compiles on Windows too (it's never delivered
there; Ctrl-C arrives as `os.Interrupt`).

## Broken pipe

- Go's default for **fd 1/2**: a write to a closed pipe raises SIGPIPE and the process dies silently (status 141, no
  stderr — verified with `tool | head -n1`). That already satisfies "exit quietly"; don't `signal.Ignore(SIGPIPE)`
  unless the tool has cleanup to do.
- The scaffold calls `signal.Ignore(syscall.SIGPIPE)` in `run` (so deferred cleanup still runs) and treats the resulting
  `EPIPE` as exit 0.
- If SIGPIPE is ignored/notified, or the write goes to another descriptor, it returns `syscall.EPIPE`: treat
  `errors.Is(err, syscall.EPIPE)` as a clean stop — no message, exit 0 (the scaffold's `run` does) — and don't wrap it
  in a way that loses `Is` (use `%w`).

## Files

Write files atomically: `os.CreateTemp(filepath.Dir(name), "."+base+".tmp-*")` → write → `Close` (checked) →
`os.Rename`; remove the temp file on every error path. Never write to the working directory unless that's the
command's purpose; caches/state go under `os.UserCacheDir()`/XDG (`cli-contract.md` §6).
