# Errors

## Create, wrap, inspect

- Error strings are lower-case, without trailing punctuation, and don't repeat the caller's context
  (`"open config: permission denied"`, not `"Error: Failed to open config."`).
- Add context when an error crosses a boundary, with `%w` so callers can still inspect the cause:

  ```go
  if err := r.ReadFile(name); err != nil {
      return fmt.Errorf("read %s: %w", name, err)
  }
  ```

  Use `%v` instead of `%w` only when you deliberately hide the cause from callers (an implementation detail you
  don't want to become API). Don't wrap twice with the same context; don't log *and* return the same error.
- Inspect with `errors.Is` (identity, through wrapping) and `errors.As`/`errors.AsType` (type), never `==` on a
  wrapped error or a type switch on `err` (the `errorlint` linter flags both):

  ```go
  if errors.Is(err, fs.ErrNotExist) { … }
  if ve, ok := errors.AsType[*ValidationError](err); ok { … } // Go 1.26+; else errors.As(err, &ve)
  ```

- `errors.Join` combines independent failures (e.g. validating every field and reporting all problems).

## Sentinel vs typed errors

| Kind | Declare | Use when |
|---|---|---|
| Sentinel | `var ErrNotFound = errors.New("not found")` | Callers only need to know *which* failure. Compare with `errors.Is`. |
| Typed | `type ValidationError struct{ Field, Reason string }` + `func (e *ValidationError) Error() string` | Callers need data from the failure (field, limit, retry-after). Extract with `errors.As`/`AsType`. Pointer receiver; return `*ValidationError`. |
| Opaque | `fmt.Errorf("…: %w", err)` | Everything else — callers only log or propagate. |

Exported sentinels and error types are API: adding one is fine, renaming/removing one is a breaking change. Name
them `ErrX` / `XError` (`errname`). Never return a typed nil pointer as an `error` (`var e *MyErr; return e` is a
non-nil interface).

## Mapping failures to exit codes — in exactly one place

In a CLI, the domain packages return ordinary Go errors (sentinels, typed errors, wrapped I/O errors). **One place**
in the CLI package turns any error into the contract's exit code and envelope — commands never call `os.Exit`, and
nothing below `cmd/` does either (`os.Exit` skips deferred cleanup). Find it before adding a failure kind.

**In an `iru-setup-go-cli` scaffold** (`internal/cli/errors.go`, `root.go`):

- `*cli.Error{Code, ID, Message, Hint, Details, RetryAfterSeconds, Err}` is an *expected* failure; build one with
  `NewError(code, "SCREAMING_ID", "Message sentence.", "Hint that names the fix.").WithDetails(map[string]any{…})`.
- `wrapRunE` (applied to every command by `finishTree`) turns any non-`*Error` a `RunE` returns into
  `internalError(err)` — exit 1, "this is a bug".
- `(*App).exitCode` is the single mapping: a signal cause → 130/143; `nil` or `EPIPE` → 0; `*Error` → its code;
  anything else must have come from Cobra's parsing (unknown command, `Args`, flags, flag groups) → usage (2) via
  `usageError(cmd, err)`.

So a task that adds a failure **returns a `*cli.Error` from the command or the domain call it wraps** — e.g. map a
domain sentinel right where the command calls it:

```go
item, err := catalog.Find(ctx, name)
if errors.Is(err, catalog.ErrNotFound) {
    return NewError(ExitNotFound, "ITEM_NOT_FOUND", fmt.Sprintf("No item named %q.", name),
        "Run `tool list` to see the available names.")
}
if err != nil {
    return err // wrapRunE reports it as internal (exit 1)
}
```

**In a repository without that structure**, the shape that works (verified with Cobra v1.10.2) is one `classify`
function called from the entry point:

```go
// classify maps any error to a contract *Error. It is the only place that decides exit codes.
func classify(ctx context.Context, err error, started bool) *Error {
    if e, ok := errors.AsType[*Error](err); ok {
        return e
    }
    if sig, ok := errors.AsType[*signalError](context.Cause(ctx)); ok {
        return &Error{Code: sig.code, Message: "interrupted", Err: err} // 130 / 143
    }
    if errors.Is(err, context.DeadlineExceeded) {
        return &Error{Code: ExitRetryable, Message: "operation timed out", Hint: "Retry, or raise --timeout.", Err: err}
    }
    if !started { // Cobra parse/argument errors arrive before the command's hooks finish
        return usageError(err.Error(), "", err)
    }
    return &Error{Code: ExitInternal, Message: err.Error(), Err: err}
}
```

where `started` is set at the end of the root's `PersistentPreRunE` — *after* it has called
`cmd.ValidateRequiredFlags()` and `cmd.ValidateFlagGroups()` itself, because Cobra runs those two checks only after
the pre-run hooks (otherwise a mutually-exclusive-flags error is misreported as internal, exit 1 — verified).

Either way, when a task adds a failure kind:

1. Pick the **existing** contract code for the caller's reaction (`cli-contract.md` §2) — never a new number, never
   renumber. Finer detail goes in `ID` (e.g. `NAME_TOO_LONG`) and `Details`.
2. Map it once: return the `*Error` at the call site, *or* add one branch to the central mapping — not both.
3. Write the hint as the fix: the flag to add, the command to run, the env var to set (never empty for codes 2–7).
   Never put a token, password or credential-bearing URL in `Message`, `Hint` or `Details`.
4. Add a row to the exit-code tests (`testing.md`) that produces the code on purpose — in-process and/or a
   `status N exec …` line in a `.txtar` script.

Broken pipe (`syscall.EPIPE`) is not an error to report — `output-and-terminal.md`.

## Panics and recover

Don't `recover` in command code; a panic is a bug. Know, though, that an unrecovered panic makes the Go runtime exit
with status **2** — the contract's `USAGE` code (verified) — so a caller would "fix its command line" for what is
really a crash. The fix belongs in the CLI's single entry function: one deferred `recover` in `cli.Main` that prints
the panic value and `debug.Stack()` to stderr and returns `ExitInternal` (1). If the repository's entry function has
none, report it in Step 8 rather than adding it as a side effect of an unrelated task (unless the task is about
error handling). A panic in a goroutine the command started can't be recovered there — `concurrency.md` keeps work on
goroutines that return errors instead. Bubble Tea restores the terminal on a panic inside its own loop.
