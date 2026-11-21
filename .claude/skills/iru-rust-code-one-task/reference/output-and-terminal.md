# Output and the terminal

How a contract CLI (`cli-contract.md` §1–§4) writes, colours, prompts, and stops. Every snippet here compiles and is
exercised by tests in the verification crate (see the skill's Verification notes).

## Inject the streams; never `println!`

`println!`/`print!`/`eprintln!` write to the real process streams (untestable) and **panic** on a broken pipe
("failed printing to stdout"); `std::process::exit` skips flushes. The recommended lint set warns on `print_stdout`,
`print_stderr` and `exit`. Instead:

- `main.rs` captures the process facts once and hands `run` the writers:

  ```rust
  /// The outside world as seen by [`run`]: streams, terminal facts, environment facts.
  pub struct Io<'a> {
      /// Result stream.
      pub out: &'a mut dyn Write,
      /// Diagnostics stream.
      pub err: &'a mut dyn Write,
      /// Which standard streams are terminals.
      pub ttys: Ttys,
      /// Environment facts the policies read.
      pub env: EnvFacts,
      /// How to ask yes/no questions.
      pub confirm: &'a mut dyn Confirm,
  }
  ```

  and `main` wraps the locked stdout/stderr in `anstream::AutoStream::new(io::stdout().lock(), ColorChoice::Always)`
  — the code decides colour itself; `AutoStream` only translates ANSI for legacy Windows consoles (`error-handling.md`
  shows `main`).
- `run` derives a per-command context (`Ctx { out, err, format, colour_out, colour_err, may_prompt, quiet, ttys,
  confirm }`) and every command writes with `writeln!(ctx.out, …)?` / `write_json(ctx.out, …)?`.
- Write many lines through the same locked handle (`io::stdout().lock()`), not one `io::stdout()` call per line;
  `run` flushes `out` once at the end and treats a flush error like a write error.

## stdout is the result, stderr everything else

- Results (text or JSON) → `ctx.out`. Progress, warnings, hints, prompts, logs, and errors → `ctx.err`.
- `--quiet` silences informational stderr (never errors); `--verbose` adds diagnostics on stderr; neither changes
  stdout. A command must not print "Done" or a banner on stdout.
- Write files atomically: `tempfile::NamedTempFile::new_in(target_dir)?` → write → `persist(target)` (same
  filesystem, so the rename is atomic).

## JSON: deterministic, sorted, compact

```rust
/// Writes `value` as one compact JSON line with object keys sorted.
///
/// Going through [`serde_json::Value`] sorts keys (its map is a `BTreeMap` as long as no
/// crate in the build enables `serde_json`'s `preserve_order` feature), so struct field order
/// never leaks into the output.
///
/// # Errors
///
/// Returns the writer's error (including [`io::ErrorKind::BrokenPipe`]) or a serialization error
/// converted to [`io::Error`].
pub fn write_json<T: Serialize>(out: &mut dyn Write, value: &T) -> io::Result<()> {
    let sorted: Value = serde_json::to_value(value).map_err(io::Error::other)?;
    serde_json::to_writer(&mut *out, &sorted).map_err(io::Error::other)?;
    out.write_all(b"\n")
}
```

- Serializing a struct directly writes fields in **declaration order**; the `Value` round trip sorts them. If a new
  dependency is added, check `cargo tree -e features -i serde_json` for `preserve_order` — feature unification
  would silently switch every map to insertion order and break golden tests.
- Output types: `#[derive(Serialize)] #[serde(rename_all = "camelCase")]` with a `schema_version: u32` field (→
  `schemaVersion`), or `json!({...})` for small ad hoc documents. No timestamps, absolute paths, or colour unless the
  task asks. Lists add `total`, `nextCursor` (or `null`) and `truncated`.
- **jsonl**: one `write_json` per record, then a summary line
  `{"nextCursor":null,"schemaVersion":1,"total":N,"type":"summary"}`.
- Adding a field is fine; renaming/removing/retyping one bumps `SCHEMA_VERSION` — a plan-level decision to report.

## Exit codes and the error envelope

The enum lives in one file and is never renumbered:

```rust
/// Exit status of a `democli` invocation, one variant per caller reaction.
///
/// The numbers are part of the public contract: never renumber a variant, and map a new
/// failure kind onto an existing reaction instead of adding a code.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Exit {
    /// Success, including an idempotent no-op and a `--dry-run` plan.
    Ok = 0,
    /// Unexpected failure (a bug).
    Internal = 1,
    /// Bad flag, argument, or unknown command.
    Usage = 2,
    /// Well-formed input that was rejected.
    Validation = 3,
    /// The named resource doesn't exist.
    NotFound = 4,
    /// A destructive action needs confirmation and none was given.
    ConfirmationRequired = 5,
    /// Transient failure; retry with the same arguments.
    Retryable = 6,
    /// Missing or invalid credentials.
    Auth = 7,
    /// Interrupted by SIGINT (128 + 2).
    Interrupted = 130,
    /// Terminated by SIGTERM (128 + 15).
    Terminated = 143,
}
```

with `const fn code(self) -> u8 { self as u8 }`, `const fn name(self) -> &'static str` (`"NOT_FOUND"`, …) and
`impl From<Exit> for ExitCode`. The envelope and the text form are written in one function:

```rust
pub fn write_error(
    err_out: &mut dyn Write,
    bin: &str,
    err: &CliError,
    format: Format,
    colour: bool,
) -> io::Result<()> {
    if format.is_machine() {
        let envelope = json!({
            "error": {
                "code": err.exit.name(),
                "details": err.details,
                "exitCode": err.exit.code(),
                "hint": err.hint,
                "message": err.message,
                "retryable": err.exit == crate::exit::Exit::Retryable,
            },
            "schemaVersion": SCHEMA_VERSION,
        });
        return write_json(err_out, &envelope);
    }
    let style = if colour {
        Style::new().fg_color(Some(AnsiColor::Red.into())).bold()
    } else {
        Style::new()
    };
    writeln!(err_out, "{bin}: {style}error:{style:#} {}", err.message)?;
    if !err.hint.is_empty() {
        writeln!(err_out, "hint: {}", err.hint)?;
    }
    Ok(())
}
```

In JSON mode a failure prints nothing on stdout and exactly one envelope on stderr. The text form keeps the `error:`
prefix so meaning never depends on colour.

## Colour

One pure policy function, decided per stream, unit-tested with plain values:

```rust
/// Decides whether to colour one stream (contract §3).
///
/// `--no-color` and `NO_COLOR` always win; machine mode and `--quiet` never colour; otherwise
/// `FORCE_COLOR` forces colour on, and `TERM=dumb` or a non-terminal stream turns it off.
pub fn colour_enabled(
    stream_is_tty: bool,
    env: &EnvFacts,
    no_color_flag: bool,
    machine_or_quiet: bool,
) -> bool {
    if no_color_flag || env.no_color || machine_or_quiet {
        return false;
    }
    if env.force_color {
        return true;
    }
    !env.term_dumb && stream_is_tty
}
```

- `Ttys::detect()` uses `std::io::IsTerminal` (`stdin().is_terminal()`, …) once in `main`; `EnvFacts::from_lookup`
  reads `NO_COLOR` (set and non-empty), `FORCE_COLOR`/`CLICOLOR_FORCE` (non-empty, not `0`), `TERM=dumb`, `CI`
  through a closure, so tests pass a map.
- Style with `anstyle` (`Style::new().fg_color(Some(AnsiColor::Red.into())).bold()`, rendered with `{style}` …
  `{style:#}` for the reset) only when the policy said yes; with colour off, write a plain `Style::new()` (renders as
  nothing). `owo-colors` is fine if the crate already uses it — but call its plain methods behind the policy bool;
  its `if_supports_color` reads the real environment and TTY itself, which tests can't control.
- clap's own help/errors: `StyledStr`'s `Display` is plain; `.ansi()` adds styles (`clap.md`).

## Prompts

Only when `may_prompt` is true — stdin **and** stderr are terminals, and neither `--no-input`, `<BIN>_NO_INPUT`, nor
`CI` is set:

```rust
pub fn may_prompt(ttys: Ttys, env: &EnvFacts, no_input: bool) -> bool {
    ttys.stdin && ttys.stderr && !no_input && !env.ci
}

/// Asks a yes/no question. The real implementation prompts on stderr; tests pass a fake.
pub trait Confirm {
    /// Asks `question`, defaulting to "no".
    ///
    /// # Errors
    ///
    /// Returns the terminal's I/O error (including an interrupted prompt).
    fn confirm(&mut self, question: &str) -> std::io::Result<bool>;
}

/// [`Confirm`] backed by `dialoguer` on stderr — only ever called when [`may_prompt`] is true.
#[derive(Debug, Default)]
pub struct TerminalConfirm;

impl Confirm for TerminalConfirm {
    fn confirm(&mut self, question: &str) -> std::io::Result<bool> {
        dialoguer::Confirm::new()
            .with_prompt(question)
            .default(false)
            .interact_on(&dialoguer::console::Term::stderr())
            .map_err(std::io::Error::from)
    }
}
```

The command never calls `dialoguer` directly; it asks `ctx.confirm` only when `ctx.may_prompt`, and otherwise fails
fast — exit 5 with the exact `confirmCommand` in `details` for a confirmation, exit 2 naming the flag for a missing
value. The adapter is the only untested code (it needs a real TTY); keep it this small and in the same file as the
tested policies. Secrets are never prompted with echo; prefer `--token-stdin` (`security.md`). `dialoguer` with
`default-features = false` is enough for `Confirm`/`Input`/`Select` (its default features add the editor and
password prompts).

## Progress

Spinners/bars only when stderr is a terminal and not `--quiet`/machine mode: with `indicatif` (only if already a
dependency) use `ProgressDrawTarget::hidden()` otherwise; at most an occasional plain line on stderr at `--verbose`.

## Signals (SIGINT/SIGTERM → 130/143)

Long-running work polls a flag and cleans up; a second signal during cleanup exits immediately:

```rust
/// A cheap, clonable handle telling long-running work that a stop signal arrived.
#[derive(Debug, Clone, Default)]
pub struct Interrupt {
    last_signal: Arc<AtomicUsize>,
}

impl Interrupt {
    /// Installs the handlers: the first SIGINT/SIGTERM is recorded for [`Interrupt::pending`];
    /// a second one while cleanup runs exits immediately with 128 + N.
    ///
    /// # Errors
    ///
    /// Returns the OS error if a handler can't be registered.
    pub fn install() -> io::Result<Self> {
        let last_signal = Arc::new(AtomicUsize::new(0));
        let seen = Arc::new(AtomicBool::new(false));
        for (signal, exit) in [(SIGINT, Exit::Interrupted), (SIGTERM, Exit::Terminated)] {
            // Order matters: the conditional shutdown must be registered before the flag that arms it.
            flag::register_conditional_shutdown(signal, i32::from(exit.code()), Arc::clone(&seen))?;
            flag::register(signal, Arc::clone(&seen))?;
            flag::register_usize(signal, Arc::clone(&last_signal), signal_number(signal))?;
        }
        Ok(Self { last_signal })
    }

    /// The exit status to stop with, if a stop signal has arrived.
    pub fn pending(&self) -> Option<Exit> {
        match self.last_signal.load(Ordering::Relaxed) {
            0 => None,
            n if n == signal_number(SIGTERM) => Some(Exit::Terminated),
            _ => Some(Exit::Interrupted),
        }
    }
}

fn signal_number(signal: i32) -> usize {
    usize::try_from(signal).unwrap_or(usize::MAX)
}
```

(`use signal_hook::{consts::{SIGINT, SIGTERM}, flag};`.) Verified on macOS: SIGTERM → 143, SIGINT → 130 after a
300 ms cleanup, a second SIGINT during cleanup → 130 after 50 ms; it also compiles for `x86_64-pc-windows-msvc`.
Install it only in commands that run long enough to need it (installing changes Ctrl-C from "die now" to "please
stop"), print one line on stderr, remove temp files, return the `Exit`. `ctrlc` (with its `termination` feature) is
fine if the crate already uses it, but it can't tell SIGINT from SIGTERM — use `signal-hook` when the 130/143
distinction matters. In a tokio program use `tokio::signal` (`async.md`). In a raw-mode TUI, Ctrl-C arrives as a key
event, not a signal (`ratatui.md`).

## Broken pipe

Rust ignores SIGPIPE, so `tool … | head -n1` turns into `ErrorKind::BrokenPipe` from a write. Commands propagate it
with `?` (as `RunError::Io`) and `run` maps it in one place to a quiet `Exit::Ok` — nothing on stderr, no panic:

```rust
fn finish(result: Result<(), RunError>, ctx: &mut Ctx<'_>) -> Exit {
    let err = match result {
        Ok(()) => return Exit::Ok,
        Err(RunError::Io(e)) if is_broken_pipe(&e) => return Exit::Ok, // the reader left: stop quietly
        Err(RunError::Io(e)) => CliError::internal(&e),
        Err(RunError::Cli(e)) => e,
    };
    // Best effort: if stderr itself is gone there is nobody left to tell.
    let _ = write_error(ctx.err, BIN, &err, ctx.format, ctx.colour_err);
    err.exit
}
```

Test it with a `Write` impl whose `write` returns `Err(io::ErrorKind::BrokenPipe.into())`.

## Bounded output

List commands take `--limit` (small default) and `--cursor`, and always signal truncation (`truncated: true` plus a
`nextCursor`); long text fields take `--max-chars` with a marker. Output that can flood an agent's context is a bug.
