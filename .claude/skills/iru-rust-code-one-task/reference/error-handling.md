# Error handling

## Which tool

| Layer | Use | Why |
|---|---|---|
| Domain/library modules (`src/commands/*`, `src/config.rs`, a library crate) | a `thiserror` enum per module or concern | callers (and tests) match on variants; each variant maps to one exit code |
| The CLI boundary (`lib::run`) | one `CliError` struct carrying the `Exit` reaction, message, hint and details | the envelope and the exit code need all four, in one place |
| A non-contract binary, `xtask`, test helpers | `anyhow::Result` + `.context(...)` if the crate already uses `anyhow` | only the message matters there |

Never put `anyhow::Error` in a library's public API (it erases the type callers need to match), and never let a
contract CLI's command return `anyhow::Error` — the exit code would have to be recovered by downcasting. If the crate
already does that, follow it and keep the downcast-to-exit mapping in one function.

## Designing an error enum

```rust
/// Why a name was rejected.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum GreetError {
    /// The name is empty or only whitespace.
    #[error("the name is empty")]
    Empty,
    /// The name is longer than [`MAX_NAME_CHARS`].
    #[error("the name is longer than {max} characters")]
    TooLong {
        /// The limit that was exceeded.
        max: usize,
    },
}
```

- One variant per failure cause the caller might react to differently; `Display` is lowercase, no trailing
  punctuation, and does **not** repeat the wrapped error's message (the reporter prints the `source()` chain).
- Wrap causes with `#[from]` (generates `From` + `source()`) when there's exactly one natural conversion, `#[source]`
  plus context fields otherwise (`BadYear { line: usize, #[source] source: ParseIntError }`), `#[error(transparent)]`
  to forward an inner error unchanged.
- Derive `PartialEq` when the variants allow it (no `io::Error` inside) so tests can `assert_eq!(f(x), Err(E::Empty))`.
- "Not found" that callers routinely treat as normal is `Ok(None)` (`Result<Option<T>, E>`); "not found" that ends
  the command is a variant mapped to exit 4. Never turn a real failure into `None`.
- Every variant and field gets a `///` doc comment (`missing_docs`); a public fallible function documents its
  variants under `# Errors` (`rustdoc.md`).

## Mapping failures to the contract (CLI projects)

Each domain error converts to `CliError` in exactly one `From` impl, choosing the reaction and writing the hint:

```rust
impl From<GreetError> for CliError {
    fn from(err: GreetError) -> Self {
        CliError::new(
            Exit::Validation,
            err.to_string(),
            format!("Pass a NAME of 1-{MAX_NAME_CHARS} characters."),
        )
    }
}
```

```rust
/// A failure that ends the command, carrying everything the error envelope needs.
#[derive(Debug, thiserror::Error)]
#[error("{message}")]
pub struct CliError {
    /// Which caller reaction (and process status) this failure maps to.
    pub exit: Exit,
    /// One-sentence description of what went wrong.
    pub message: String,
    /// How to fix it; never empty for exit codes 2–7.
    pub hint: String,
    /// Structured, secret-free details (for example a `confirmCommand`).
    pub details: serde_json::Map<String, serde_json::Value>,
}

/// What a command can fail with: a contract-level [`CliError`], or an I/O error while writing
/// output (a broken pipe is a clean stop, anything else is internal).
#[derive(Debug, thiserror::Error)]
pub enum RunError {
    /// A failure with a contract exit code.
    #[error(transparent)]
    Cli(#[from] CliError),
    /// Writing to stdout/stderr failed.
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
```

Commands return `Result<(), RunError>`, so `?` works on both `writeln!(ctx.out, ...)` and domain calls
(`greeting(name).map_err(CliError::from)?`). `lib::run` turns the result into an `Exit` in one place: broken pipe →
quiet `Exit::Ok`; other I/O → `Exit::Internal`; `CliError` → its own `exit`, after writing the envelope or the
`bin: error: …`/`hint: …` lines (`output-and-terminal.md`).

Which reaction a cause maps to (contract §2 — never invent a new number):

| Cause | Exit |
|---|---|
| clap parse error, unknown subcommand, conflicting/missing flag, a value that would have been prompted for under `--no-input` | 2 `Usage` |
| well-formed input rejected by validation (empty name, bad range, `check` found problems), malformed config file content | 3 `Validation` |
| a named file/record/resource doesn't exist (`io::ErrorKind::NotFound` on a path the user named, HTTP 404) | 4 `NotFound` |
| destructive action without `--yes` and no interactive "yes" | 5 `ConfirmationRequired` |
| `io::ErrorKind::TimedOut`, a `--timeout` elapsing, connection reset/refused to a remote, HTTP 429/5xx | 6 `Retryable` |
| missing/invalid credentials, HTTP 401/403 | 7 `Auth` |
| anything unexpected (a bug, an I/O error on our own temp file) | 1 `Internal` |

The hint names the fix: the flag that supplies the missing value, the list command for a not-found name, the exact
confirm command, the login command and the token environment variable. It never contains a secret.

## Panics

- Panic only on a broken invariant of the program itself (an index the code just checked, a regex literal), with
  `expect("why this cannot fail")` — and only where the crate's lints allow it. Never on input.
- A panic in a contract CLI is exit code 101 with a backtrace on stderr — a bug by definition. Don't
  `catch_unwind` to hide it.
- A full-screen TUI must restore the terminal before a panic message prints: `ratatui::init`/`ratatui::run` install
  that hook (`ratatui.md`).

## `main`

`fn main() -> ExitCode`, never `fn main() -> Result<(), E>` in a CLI (it prints the `Debug` form and always exits 1)
and never `std::process::exit` in normal flow (it skips destructors — buffered writers aren't flushed, temp files
aren't removed; clippy's `exit` lint in the recommended set flags it):

```rust
fn main() -> ExitCode {
    let ttys = Ttys::detect();
    // `run` decides colour itself; AutoStream only adapts ANSI for legacy Windows consoles.
    let mut out = AutoStream::new(io::stdout().lock(), ColorChoice::Always);
    let mut err = AutoStream::new(io::stderr().lock(), ColorChoice::Always);
    let mut io = democli::Io {
        out: &mut out,
        err: &mut err,
        ttys,
        env: EnvFacts::from_process(),
        confirm: &mut TerminalConfirm,
    };
    democli::run(std::env::args_os(), &mut io).into()
}
```

`Exit` converts with `impl From<Exit> for ExitCode { fn from(e: Exit) -> Self { ExitCode::from(e.code()) } }`
(`cli-contract.md` §2; the enum itself is shown in `output-and-terminal.md`).
