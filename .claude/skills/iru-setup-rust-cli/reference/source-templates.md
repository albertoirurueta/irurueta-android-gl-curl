# Source templates — the library and the thin binary

Every module of `src/` except the terminal UI (`tui-templates.md`) and the in-process tests (`test-templates.md`).
Written for every `ui` value; `ui: tui` keeps the marked blocks.

Conditional markers (`// >>> <condition>` … `// <<< <condition>` in Rust, `# >>> …` in TOML) and the `__Token__`
placeholders work as described in SKILL.md's "Reference files" section; the marker lines themselves are never
written. A heading suffixed `(condition)` is written only when the condition holds. Every file below was rendered
and verified on 2026-10-16 for `ui: none` and `ui: tui` (Rust 1.99.1; MSRV toolchains 1.85.0 for `ui: none`, 1.88.0 for `ui: tui`) — see SKILL.md
"Verification notes".

### `src/main.rs`

Thin on purpose: no logic, so nothing here needs its own tests (the end-to-end tests still run it).

```rust
//! The `__bin__` executable. All logic lives in the library crate so it can be tested.

fn main() -> std::process::ExitCode {
    __crate__::main()
}
```

### `src/lib.rs`

The entry point (`main`), the argument-to-exit-code pipeline (`run`), early flag scanning so parse errors render as text or as the JSON envelope, panic → exit 1 (`guarded`), and the post-run signal check.

```rust
//! # __bin__
//!
//! __Description__
//!
//! The binary (`src/main.rs`) only calls [`main`]; everything else lives in this library so it can be tested
//! in-process. [`run`] takes the arguments and a [`Context`] holding the three standard streams, which
//! terminals they are, and the environment — tests pass byte buffers and fixed values.
//!
//! The behaviour follows the CLI contract: results on stdout and everything else on stderr; one exit code per
//! caller reaction ([`exit::Exit`]); `--format text|json|jsonl` with a JSON error envelope on stderr; colour
//! and prompts only on terminals; SIGINT/SIGTERM exit 130/143; a closed stdout pipe ends quietly.

pub mod cli;
pub mod commands;
pub mod config;
pub mod error;
pub mod exit;
pub mod output;
pub mod signals;
pub mod term;
// >>> ui: tui
pub mod tui;
// <<< ui: tui

use std::ffi::OsString;
use std::io::{self, BufRead, Write};
use std::panic::{self, AssertUnwindSafe};
use std::sync::{Arc, Mutex, PoisonError};

use clap::error::ErrorKind;
use clap::{CommandFactory, FromArgMatches};

use crate::cli::{Cli, Command};
use crate::config::Settings;
use crate::error::CliError;
use crate::exit::Exit;
use crate::output::{Diagnostics, LogFormat};
use crate::signals::Signals;
use crate::term::{Env, Painter, Terminal, color_enabled};

/// The executable name, used in messages, completions and generated docs.
pub const BIN_NAME: &str = "__bin__";

/// The process streams and facts a command runs against.
pub struct Context<'a> {
    /// Where results go.
    pub stdout: &'a mut dyn Write,
    /// Where diagnostics, prompts and errors go.
    pub stderr: &'a mut dyn Write,
    /// Where prompt answers come from.
    pub stdin: &'a mut dyn BufRead,
    /// Which streams are terminals.
    pub terminal: Terminal,
    /// The environment variables.
    pub env: Env,
    /// Termination signals received so far.
    pub signals: Signals,
}

/// Per-run state derived from the parsed flags.
#[derive(Clone, Debug)]
pub struct Session {
    /// The effective settings.
    pub settings: Settings,
    /// The stderr writer configuration.
    pub diagnostics: Diagnostics,
    /// Colour policy for stdout.
    pub stdout_painter: Painter,
}

impl Session {
    /// Derives the colour policies of both streams from `settings` and the context.
    #[must_use]
    pub fn new(settings: Settings, ctx: &Context<'_>) -> Self {
        let machine = settings.format.is_machine();
        let stdout_color = color_enabled(ctx.terminal.stdout, &ctx.env, settings.no_color, machine);
        let stderr_color = color_enabled(
            ctx.terminal.stderr,
            &ctx.env,
            settings.no_color,
            machine || settings.quiet,
        );
        let diagnostics = Diagnostics::new(
            BIN_NAME,
            settings.quiet,
            settings.verbose,
            settings.log_format,
            Painter::new(stderr_color),
        );
        Self {
            settings,
            diagnostics,
            stdout_painter: Painter::new(stdout_color),
        }
    }
}

/// What can be known from the raw arguments before parsing — used to render parse errors correctly.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct EarlyFlags {
    no_color: bool,
    machine: bool,
}

impl EarlyFlags {
    fn scan(args: &[OsString], env: &Env) -> Self {
        let mut flags = Self {
            machine: matches!(env.get("__BIN_ENV___FORMAT"), Some("json" | "jsonl")),
            ..Self::default()
        };
        let mut previous_was_format = false;
        for arg in args.iter().skip(1).map(|a| a.to_string_lossy()) {
            if arg == "--" {
                break;
            }
            match arg.as_ref() {
                "--no-color" => flags.no_color = true,
                "--json" | "--format=json" | "--format=jsonl" => flags.machine = true,
                "--format=text" => flags.machine = false,
                "json" | "jsonl" if previous_was_format => flags.machine = true,
                "text" if previous_was_format => flags.machine = false,
                _ => {}
            }
            previous_was_format = arg == "--format";
        }
        flags
    }
}

/// Parses `args` (including the program name) and runs the command, returning its exit code.
///
/// Never panics on bad input and never writes anything but results to `ctx.stdout`.
pub fn run<I, T>(args: I, ctx: &mut Context<'_>) -> Exit
where
    I: IntoIterator<Item = T>,
    T: Into<OsString>,
{
    let args: Vec<OsString> = args.into_iter().map(Into::into).collect();
    let early = EarlyFlags::scan(&args, &ctx.env);
    let stderr_painter = Painter::new(color_enabled(
        ctx.terminal.stderr,
        &ctx.env,
        early.no_color,
        early.machine,
    ));
    let fallback = Diagnostics::new(BIN_NAME, false, 0, LogFormat::Text, stderr_painter);

    let cli = match parse(&args, ctx, early, stderr_painter) {
        Ok(cli) => cli,
        Err(exit) => return exit,
    };
    let (file, path) = match config::load(cli.global.config.as_deref(), &ctx.env) {
        Ok(loaded) => loaded,
        Err(error) => return report(&error, ctx, &fallback, early.machine),
    };
    let session = Session::new(Settings::resolve(&cli.global, &file, path), ctx);
    if let Some(path) = &session.settings.config_path {
        let _ = session
            .diagnostics
            .debug(ctx.stderr, &format!("read config file {}", path.display()));
    }

    let result = dispatch(&cli.command, ctx, &session);
    // A signal the command deferred wins over its result: one line on stderr, then 130/143.
    if let Some(signal) = ctx.signals.received() {
        let _ = writeln!(ctx.stderr, "{BIN_NAME}: {}", signal.name().to_lowercase());
        return signal;
    }
    match result {
        Ok(()) => Exit::Ok,
        Err(error) => report(
            &error,
            ctx,
            &session.diagnostics,
            session.settings.format.is_machine(),
        ),
    }
}

fn parse(
    args: &[OsString],
    ctx: &mut Context<'_>,
    early: EarlyFlags,
    stderr_painter: Painter,
) -> Result<Cli, Exit> {
    let parsed = Cli::command()
        .try_get_matches_from(args)
        .and_then(|m| Cli::from_arg_matches(&m));
    let error = match parsed {
        Ok(cli) => return Ok(cli),
        Err(error) => error,
    };
    let styled = error.render();
    if matches!(
        error.kind(),
        ErrorKind::DisplayHelp | ErrorKind::DisplayVersion
    ) {
        // Help and version are results: stdout, exit 0.
        let stdout_color = color_enabled(ctx.terminal.stdout, &ctx.env, early.no_color, false);
        let text = if stdout_color {
            styled.ansi().to_string()
        } else {
            styled.to_string()
        };
        return match ctx.stdout.write_all(text.as_bytes()) {
            Ok(()) => Err(Exit::Ok),
            Err(e) if e.kind() == io::ErrorKind::BrokenPipe => Err(Exit::Ok),
            Err(_) => Err(Exit::Internal),
        };
    }
    if early.machine {
        let plain = styled.to_string();
        let message = plain
            .lines()
            .next()
            .unwrap_or_default()
            .trim_start_matches("error: ")
            .to_owned();
        let usage = CliError::usage(
            message,
            format!("Run `{BIN_NAME} --help` or `{BIN_NAME} <command> --help`."),
        )
        .with_detail("kind", format!("{:?}", error.kind()));
        let diagnostics = Diagnostics::new(BIN_NAME, false, 0, LogFormat::Text, stderr_painter);
        return Err(report(&usage, ctx, &diagnostics, true));
    }
    let text = if stderr_painter.enabled() {
        styled.ansi().to_string()
    } else {
        styled.to_string()
    };
    let _ = ctx.stderr.write_all(text.as_bytes());
    Err(Exit::Usage)
}

fn dispatch(command: &Command, ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    match command {
        Command::Greet(args) => commands::greet::run(args, ctx, session),
        Command::Completion(args) => commands::completion::run(args, ctx),
        Command::Schema(args) => commands::schema::run(args, ctx, session),
        Command::Version => commands::version::run(ctx, session),
        // >>> ui: tui
        Command::Browse(args) => commands::browse::run(args, ctx, session),
        // <<< ui: tui
    }
}

/// Writes `error` to stderr (unless it is a closed stdout pipe) and returns its exit code.
fn report(
    error: &CliError,
    ctx: &mut Context<'_>,
    diagnostics: &Diagnostics,
    machine: bool,
) -> Exit {
    if error.is_broken_pipe() {
        return Exit::Ok;
    }
    let _ = diagnostics.error(ctx.stderr, error, machine);
    error.exit()
}

/// Runs `f`, turning a panic into `Err(message)` instead of the default exit code 101.
///
/// # Errors
///
/// Returns the panic message (with its location) when `f` panicked.
pub fn guarded(f: impl FnOnce() -> Exit) -> Result<Exit, String> {
    let message = Arc::new(Mutex::new(None::<String>));
    let sink = Arc::clone(&message);
    let previous = panic::take_hook();
    panic::set_hook(Box::new(move |info| {
        *sink.lock().unwrap_or_else(PoisonError::into_inner) = Some(info.to_string());
    }));
    let result = panic::catch_unwind(AssertUnwindSafe(f));
    panic::set_hook(previous);
    result.map_err(|_| {
        message
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take()
            .unwrap_or_default()
    })
}

/// The real entry point: process streams, environment and signal handlers, with panics mapped to exit 1.
#[must_use]
pub fn main() -> std::process::ExitCode {
    let signals = Signals::install().unwrap_or_default();
    let (stdout, stderr, stdin) = (io::stdout(), io::stderr(), io::stdin());
    let (mut out, mut err, mut input) = (stdout.lock(), stderr.lock(), stdin.lock());
    let env = Env::from_process();
    let args: Vec<OsString> = std::env::args_os().collect();
    let machine = EarlyFlags::scan(&args, &env).machine;
    let mut ctx = Context {
        stdout: &mut out,
        stderr: &mut err,
        stdin: &mut input,
        terminal: Terminal::detect(),
        env,
        signals,
    };
    let exit = match guarded(|| run(args, &mut ctx)) {
        Ok(exit) => exit,
        Err(panic) => {
            let error = CliError::internal(format!("internal error: {panic}"));
            let diagnostics =
                Diagnostics::new(BIN_NAME, false, 0, LogFormat::Text, Painter::new(false));
            report(&error, &mut ctx, &diagnostics, machine)
        }
    };
    exit.into()
}

#[cfg(test)]
mod tests;
```

### `src/cli.rs`

The clap derive tree. Doc comments are the help text, man page and CLI reference. Prefix matching (`infer_subcommands`/`infer_long_args`) stays off — clap's default — so `gre` is an error, not `greet`.

```rust
//! The command tree (clap derive). Doc comments here are the `--help` text, the man page and the CLI reference.

use std::path::PathBuf;

use clap::builder::BoolishValueParser;
use clap::{ArgAction, Args, Parser, Subcommand};

use crate::commands::{completion::CompletionArgs, greet::GreetArgs, schema::SchemaArgs};
// >>> ui: tui
use crate::commands::browse::BrowseArgs;
// <<< ui: tui
use crate::output::{Format, LogFormat};

/// Exit codes listed at the end of every `--help`.
const EXIT_CODES_HELP: &str = "\
Exit codes: 0 ok, 1 internal error, 2 usage error, 3 validation failed, 4 not found,
5 confirmation required, 6 retryable, 7 auth, 130 interrupted (SIGINT), 143 terminated (SIGTERM).
Run `__bin__ schema` for the machine-readable command tree.";

/// __Description__
///
/// Results go to stdout, everything else to stderr. Use `--json` for machine-readable output and `--no-input`
/// when nothing may prompt.
#[derive(Debug, Parser)]
#[command(
    name = "__bin__",
    bin_name = "__bin__",
    version,
    propagate_version = true
)]
#[command(
    subcommand_required = true,
    arg_required_else_help = true,
    disable_help_subcommand = true
)]
#[command(after_help = EXIT_CODES_HELP)]
pub struct Cli {
    /// Flags accepted by every command.
    #[command(flatten)]
    pub global: GlobalArgs,

    /// The command to run.
    #[command(subcommand)]
    pub command: Command,
}

/// Flags accepted by every command.
#[derive(Debug, Default, Args)]
pub struct GlobalArgs {
    /// Output format of results on stdout.
    #[arg(
        long,
        global = true,
        value_enum,
        env = "__BIN_ENV___FORMAT",
        value_name = "FORMAT"
    )]
    pub format: Option<Format>,

    /// Shorthand for `--format json`.
    #[arg(long, global = true)]
    pub json: bool,

    /// Never use colour (the `NO_COLOR` variable does the same).
    #[arg(long, global = true)]
    pub no_color: bool,

    /// Never prompt; fail with exit code 2 when a value is missing.
    /// An environment value is read leniently: `1`/`true`/`yes`/`on` enable it, `0`/`false`/`no`/`off` don't.
    #[arg(long, global = true, env = "__BIN_ENV___NO_INPUT", action = ArgAction::SetTrue)]
    #[arg(value_parser = BoolishValueParser::new())]
    pub no_input: bool,

    /// Hide informational messages on stderr (errors are still printed).
    #[arg(short, long, global = true, conflicts_with = "verbose")]
    pub quiet: bool,

    /// Print diagnostics on stderr; repeat for more detail.
    #[arg(short, long, global = true, action = ArgAction::Count)]
    pub verbose: u8,

    /// Format of diagnostics on stderr.
    #[arg(long, global = true, value_enum, default_value_t = LogFormat::Text, value_name = "FORMAT")]
    pub log_format: LogFormat,

    /// Read settings from this TOML file instead of the user config file.
    #[arg(long, global = true, env = "__BIN_ENV___CONFIG", value_name = "PATH")]
    pub config: Option<PathBuf>,
}

/// The commands.
#[derive(Debug, Subcommand)]
pub enum Command {
    /// Print a greeting. Read-only; use it to check that the tool works.
    #[command(after_help = "Examples:\n  __bin__ greet Ada\n  __bin__ greet Ada --json")]
    Greet(GreetArgs),

    /// Print a shell completion script for bash, zsh, fish, elvish or PowerShell.
    #[command(after_help = "Example:\n  __bin__ completion zsh > ~/.zfunc/___bin__")]
    Completion(CompletionArgs),

    /// Print the machine-readable command tree: commands, flags, exit codes and safety, as JSON.
    #[command(after_help = "Examples:\n  __bin__ schema\n  __bin__ schema greet")]
    Schema(SchemaArgs),

    /// Print the version and build target. Use `--json` for build metadata.
    #[command(after_help = "Example:\n  __bin__ version --json")]
    Version,
    // >>> ui: tui
    /// Pick a name in a full-screen terminal UI and print its greeting (people only; scripts use `greet --json`).
    #[command(after_help = "Example:\n  __bin__ browse Ada Grace Linus")]
    Browse(BrowseArgs),
    // <<< ui: tui
}

#[cfg(test)]
mod tests {
    use clap::CommandFactory;

    use super::Cli;

    #[test]
    fn the_command_tree_is_valid() {
        Cli::command().debug_assert();
    }
}
```

### `src/exit.rs`

The exit-code table of the CLI contract, in one place. Never renumber.

```rust
//! Process exit codes — one code per caller reaction.
//!
//! The numbers are part of the tool's public interface: scripts and agents branch on them. Never renumber a
//! variant; map a new kind of failure to the existing reaction and put the detail in the error envelope's
//! `code`/`details` instead.

use serde::Serialize;

/// The exit codes this tool can return, as listed in the CLI contract.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Exit {
    /// Success, including an idempotent no-op and a `--dry-run` plan.
    Ok,
    /// Unexpected failure (a bug). Report it; don't retry blindly.
    Internal,
    /// Bad flag, argument or unknown command. Fix the command line.
    Usage,
    /// Well-formed input that was rejected. Fix the data.
    Validation,
    /// The named resource doesn't exist.
    NotFound,
    /// A destructive action needs confirmation and none was given.
    ConfirmationRequired,
    /// Transient failure (timeout, rate limit). Retry with the same arguments.
    Retryable,
    /// Missing or invalid credentials.
    Auth,
    /// Interrupted by SIGINT (Ctrl-C).
    Interrupted,
    /// Terminated by SIGTERM.
    Terminated,
}

impl Exit {
    /// Every exit code, in numeric order.
    pub const ALL: [Self; 10] = [
        Self::Ok,
        Self::Internal,
        Self::Usage,
        Self::Validation,
        Self::NotFound,
        Self::ConfirmationRequired,
        Self::Retryable,
        Self::Auth,
        Self::Interrupted,
        Self::Terminated,
    ];

    /// The numeric process status.
    #[must_use]
    pub const fn code(self) -> u8 {
        match self {
            Self::Ok => 0,
            Self::Internal => 1,
            Self::Usage => 2,
            Self::Validation => 3,
            Self::NotFound => 4,
            Self::ConfirmationRequired => 5,
            Self::Retryable => 6,
            Self::Auth => 7,
            Self::Interrupted => 130,
            Self::Terminated => 143,
        }
    }

    /// The stable `SCREAMING_SNAKE_CASE` name used as the error envelope's `code`.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Ok => "OK",
            Self::Internal => "INTERNAL",
            Self::Usage => "USAGE",
            Self::Validation => "VALIDATION",
            Self::NotFound => "NOT_FOUND",
            Self::ConfirmationRequired => "CONFIRMATION_REQUIRED",
            Self::Retryable => "RETRYABLE",
            Self::Auth => "AUTH",
            Self::Interrupted => "INTERRUPTED",
            Self::Terminated => "TERMINATED",
        }
    }

    /// What the caller should do when it sees this code.
    #[must_use]
    pub const fn meaning(self) -> &'static str {
        match self {
            Self::Ok => "Success. Continue.",
            Self::Internal => "Unexpected failure (a bug). Report it; don't retry blindly.",
            Self::Usage => {
                "Bad flag, argument or unknown command. Fix the command line using the message."
            }
            Self::Validation => {
                "Input was well-formed but rejected. Read the problems, fix the data."
            }
            Self::NotFound => "The named resource doesn't exist. Look it up first.",
            Self::ConfirmationRequired => {
                "A destructive action needs confirmation. Ask the user, then re-run with the confirm command."
            }
            Self::Retryable => "Transient failure. Wait, then retry with the same arguments.",
            Self::Auth => "Missing or invalid credentials. Log in or set the token variable.",
            Self::Interrupted => "Interrupted by SIGINT after cleanup.",
            Self::Terminated => "Terminated by SIGTERM after cleanup.",
        }
    }

    /// Whether retrying the same command may succeed (only [`Exit::Retryable`]).
    #[must_use]
    pub const fn retryable(self) -> bool {
        matches!(self, Self::Retryable)
    }
}

impl From<Exit> for std::process::ExitCode {
    fn from(exit: Exit) -> Self {
        Self::from(exit.code())
    }
}

#[cfg(test)]
mod tests {
    use super::Exit;

    #[test]
    fn codes_match_the_contract_table() {
        let codes: Vec<u8> = Exit::ALL.iter().map(|e| e.code()).collect();
        assert_eq!(codes, [0, 1, 2, 3, 4, 5, 6, 7, 130, 143]);
    }

    #[test]
    fn names_are_unique_screaming_snake_case() {
        let mut names: Vec<&str> = Exit::ALL.iter().map(|e| e.name()).collect();
        assert!(
            names
                .iter()
                .all(|n| n.chars().all(|c| c.is_ascii_uppercase() || c == '_'))
        );
        names.dedup();
        assert_eq!(names.len(), Exit::ALL.len());
        assert_eq!(
            serde_json::to_value(Exit::NotFound).ok(),
            Some("NOT_FOUND".into())
        );
    }

    #[test]
    fn only_retryable_is_retryable_and_every_code_has_a_meaning() {
        for exit in Exit::ALL {
            assert_eq!(exit.retryable(), exit == Exit::Retryable);
            assert_ne!(exit.meaning(), "");
        }
        let _status: std::process::ExitCode = Exit::Usage.into();
    }
}
```

### `src/error.rs`

```rust
//! The error type every command returns, carrying its exit code and a hint for the caller.

use serde_json::{Map, Value, json};

use crate::exit::Exit;

/// A failure that ends the command with a specific [`Exit`] code.
///
/// Rendered on stderr either as `<bin>: <message>` plus `hint: <hint>` lines (text mode) or as one JSON error
/// envelope (`--format json|jsonl`). Never put secrets in any field.
#[derive(Debug, thiserror::Error)]
#[error("{message}")]
pub struct CliError {
    exit: Exit,
    message: String,
    hint: Option<String>,
    details: Map<String, Value>,
    #[source]
    source: Option<std::io::Error>,
}

impl CliError {
    /// Creates an error for `exit` with a message and no hint.
    #[must_use]
    pub fn new(exit: Exit, message: impl Into<String>) -> Self {
        Self {
            exit,
            message: message.into(),
            hint: None,
            details: Map::new(),
            source: None,
        }
    }

    /// A bad flag, argument or missing value (exit 2).
    #[must_use]
    pub fn usage(message: impl Into<String>, hint: impl Into<String>) -> Self {
        Self::new(Exit::Usage, message).with_hint(hint)
    }

    /// Well-formed input that was rejected (exit 3).
    #[must_use]
    pub fn validation(message: impl Into<String>, hint: impl Into<String>) -> Self {
        Self::new(Exit::Validation, message).with_hint(hint)
    }

    /// An unexpected failure — a bug (exit 1).
    #[must_use]
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(Exit::Internal, message)
            .with_hint("This is a bug. Re-run with --verbose and report it with the output.")
    }

    /// Adds or replaces the hint telling the caller how to fix the problem.
    #[must_use]
    pub fn with_hint(mut self, hint: impl Into<String>) -> Self {
        self.hint = Some(hint.into());
        self
    }

    /// Adds one machine-readable detail to the envelope's `details` object.
    #[must_use]
    pub fn with_detail(mut self, key: &str, value: impl Into<Value>) -> Self {
        self.details.insert(key.to_owned(), value.into());
        self
    }

    /// The exit code this error ends the process with.
    #[must_use]
    pub fn exit(&self) -> Exit {
        self.exit
    }

    /// The human-readable message.
    #[must_use]
    pub fn message(&self) -> &str {
        &self.message
    }

    /// How to fix the problem, if known.
    #[must_use]
    pub fn hint(&self) -> Option<&str> {
        self.hint.as_deref()
    }

    /// Whether this error is a closed pipe on stdout (`tool … | head`), which ends the command quietly.
    #[must_use]
    pub fn is_broken_pipe(&self) -> bool {
        self.source
            .as_ref()
            .is_some_and(|e| e.kind() == std::io::ErrorKind::BrokenPipe)
    }

    /// The JSON error envelope written to stderr in machine mode.
    #[must_use]
    pub fn envelope(&self) -> Value {
        json!({
            "error": {
                "code": self.exit.name(),
                "details": self.details,
                "exitCode": self.exit.code(),
                "hint": self.hint,
                "message": self.message,
                "retryable": self.exit.retryable(),
            },
            "schemaVersion": crate::output::SCHEMA_VERSION,
        })
    }
}

impl From<std::io::Error> for CliError {
    fn from(error: std::io::Error) -> Self {
        let mut err = Self::internal(format!("I/O error: {error}"));
        err.source = Some(error);
        err
    }
}

#[cfg(test)]
mod tests {
    use std::io;

    use super::CliError;
    use crate::exit::Exit;

    #[test]
    fn envelope_has_the_contract_shape() {
        let err = CliError::validation("No good.", "Fix it.").with_detail("field", "name");
        let env = err.envelope();
        assert_eq!(env["schemaVersion"], 1);
        assert_eq!(env["error"]["code"], "VALIDATION");
        assert_eq!(env["error"]["exitCode"], 3);
        assert_eq!(env["error"]["hint"], "Fix it.");
        assert_eq!(env["error"]["message"], "No good.");
        assert_eq!(env["error"]["retryable"], false);
        assert_eq!(env["error"]["details"]["field"], "name");
        assert_eq!(err.to_string(), "No good.");
    }

    #[test]
    fn io_errors_are_internal_and_broken_pipe_is_detected() {
        let pipe: CliError = io::Error::from(io::ErrorKind::BrokenPipe).into();
        assert_eq!(pipe.exit(), Exit::Internal);
        assert!(pipe.is_broken_pipe());
        assert!(std::error::Error::source(&pipe).is_some());
        let other: CliError = io::Error::other("disk on fire").into();
        assert!(!other.is_broken_pipe());
        assert!(other.message().contains("disk on fire"));
        assert!(other.hint().is_some());
        assert!(!CliError::new(Exit::Auth, "x").is_broken_pipe());
    }
}
```

### `src/output.rs`

```rust
//! Result and diagnostic output: formats, deterministic JSON, the error envelope and stderr logging.
//!
//! Results go to stdout and nothing else does; diagnostics and errors go to stderr.

use std::io::Write;

use anstyle::{AnsiColor, Style};
use clap::ValueEnum;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::error::CliError;
use crate::term::Painter;

/// The `schemaVersion` of every JSON document this tool prints. Bump it only for a breaking change.
pub const SCHEMA_VERSION: u32 = 1;

/// How results are written to stdout.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Format {
    /// Human-readable text (the default).
    #[default]
    Text,
    /// Exactly one JSON document.
    Json,
    /// One JSON object per line, ending with a summary line.
    Jsonl,
}

impl Format {
    /// Whether this is a machine format (`json` or `jsonl`).
    #[must_use]
    pub const fn is_machine(self) -> bool {
        matches!(self, Self::Json | Self::Jsonl)
    }
}

/// How diagnostics are written to stderr.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, ValueEnum)]
pub enum LogFormat {
    /// `<bin>: <level>: <message>` lines.
    #[default]
    Text,
    /// One JSON object per line.
    Json,
}

/// Serializes `value` as one compact JSON line with sorted object keys.
///
/// # Errors
///
/// Returns an internal error if the value can't be represented as JSON (a bug).
pub fn json_line(value: &impl Serialize) -> Result<String, CliError> {
    // Going through `Value` sorts object keys (serde_json's map is a BTreeMap), so output is byte-stable.
    let value = serde_json::to_value(value)
        .map_err(|e| CliError::internal(format!("JSON encoding failed: {e}")))?;
    Ok(value.to_string())
}

/// Writes `value` to `out` as one JSON line.
///
/// # Errors
///
/// Returns the encoding or I/O error.
pub fn write_json(out: &mut dyn Write, value: &impl Serialize) -> Result<(), CliError> {
    writeln!(out, "{}", json_line(value)?)?;
    Ok(())
}

/// Writes `records` as JSON Lines followed by the summary line that marks a clean finish.
///
/// # Errors
///
/// Returns the encoding or I/O error.
pub fn write_jsonl<T: Serialize>(out: &mut dyn Write, records: &[T]) -> Result<(), CliError> {
    for record in records {
        write_json(out, record)?;
    }
    write_json(out, &summary(records.len()))
}

/// The final `jsonl` line: `{"nextCursor":null,"schemaVersion":1,"total":N,"type":"summary"}`.
#[must_use]
pub fn summary(total: usize) -> Value {
    json!({"nextCursor": null, "schemaVersion": SCHEMA_VERSION, "total": total, "type": "summary"})
}

/// Writes diagnostics and errors to stderr, honouring `--quiet`, `--verbose`, `--log-format` and colour.
#[derive(Clone, Copy, Debug)]
pub struct Diagnostics {
    bin: &'static str,
    quiet: bool,
    verbose: u8,
    log_format: LogFormat,
    painter: Painter,
}

impl Diagnostics {
    /// Creates the stderr writer configuration.
    #[must_use]
    pub const fn new(
        bin: &'static str,
        quiet: bool,
        verbose: u8,
        log_format: LogFormat,
        painter: Painter,
    ) -> Self {
        Self {
            bin,
            quiet,
            verbose,
            log_format,
            painter,
        }
    }

    /// An informational line, hidden by `--quiet`.
    ///
    /// # Errors
    ///
    /// Returns the I/O error of the write.
    pub fn info(&self, err: &mut dyn Write, message: &str) -> std::io::Result<()> {
        if self.quiet {
            Ok(())
        } else {
            self.log(err, "info", message)
        }
    }

    /// A diagnostic line, shown only with `--verbose`.
    ///
    /// # Errors
    ///
    /// Returns the I/O error of the write.
    pub fn debug(&self, err: &mut dyn Write, message: &str) -> std::io::Result<()> {
        if self.verbose == 0 {
            Ok(())
        } else {
            self.log(err, "debug", message)
        }
    }

    fn log(&self, err: &mut dyn Write, level: &str, message: &str) -> std::io::Result<()> {
        match self.log_format {
            LogFormat::Json => writeln!(err, "{}", json!({"level": level, "message": message})),
            LogFormat::Text => writeln!(err, "{}: {level}: {message}", self.bin),
        }
    }

    /// Reports a failure: the JSON envelope in machine mode, `<bin>: error: …` and `hint: …` lines otherwise.
    ///
    /// # Errors
    ///
    /// Returns the I/O error of the write.
    pub fn error(
        &self,
        err: &mut dyn Write,
        error: &CliError,
        machine: bool,
    ) -> std::io::Result<()> {
        if machine {
            return writeln!(err, "{}", error.envelope());
        }
        let label = self.painter.paint(
            Style::new().bold().fg_color(Some(AnsiColor::Red.into())),
            "error:",
        );
        writeln!(err, "{}: {label} {}", self.bin, error.message())?;
        if let Some(hint) = error.hint() {
            let label = self.painter.paint(Style::new().bold(), "hint:");
            writeln!(err, "{label} {hint}")?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::{Diagnostics, Format, LogFormat, json_line, summary, write_jsonl};
    use crate::error::CliError;
    use crate::term::Painter;

    fn diag(quiet: bool, verbose: u8, log_format: LogFormat, color: bool) -> Diagnostics {
        Diagnostics::new("tool", quiet, verbose, log_format, Painter::new(color))
    }

    #[test]
    fn json_is_compact_with_sorted_keys() {
        let line = json_line(&json!({"b": 1, "a": {"d": 2, "c": 3}})).expect("json");
        assert_eq!(line, r#"{"a":{"c":3,"d":2},"b":1}"#);
    }

    #[test]
    fn jsonl_ends_with_a_summary_line() {
        let mut out = Vec::new();
        write_jsonl(&mut out, &[json!({"x": 1}), json!({"x": 2})]).expect("jsonl");
        let text = String::from_utf8(out).expect("utf8");
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 3);
        assert_eq!(
            lines[2],
            r#"{"nextCursor":null,"schemaVersion":1,"total":2,"type":"summary"}"#
        );
        assert_eq!(summary(0)["total"], 0);
    }

    #[test]
    fn machine_formats() {
        assert!(!Format::Text.is_machine());
        assert!(Format::Json.is_machine());
        assert!(Format::Jsonl.is_machine());
    }

    #[test]
    fn info_is_hidden_by_quiet_and_debug_needs_verbose() {
        let mut err = Vec::new();
        diag(true, 0, LogFormat::Text, false)
            .info(&mut err, "hidden")
            .expect("write");
        diag(false, 0, LogFormat::Text, false)
            .debug(&mut err, "hidden")
            .expect("write");
        assert_eq!(err, b"");
        diag(false, 0, LogFormat::Text, false)
            .info(&mut err, "shown")
            .expect("write");
        diag(false, 1, LogFormat::Json, false)
            .debug(&mut err, "details")
            .expect("write");
        let text = String::from_utf8(err).expect("utf8");
        assert_eq!(
            text,
            "tool: info: shown\n{\"level\":\"debug\",\"message\":\"details\"}\n"
        );
    }

    #[test]
    fn errors_render_as_text_or_envelope() {
        let error = CliError::usage("Bad.", "Do better.");
        let mut text = Vec::new();
        diag(false, 0, LogFormat::Text, false)
            .error(&mut text, &error, false)
            .expect("write");
        assert_eq!(
            String::from_utf8(text).expect("utf8"),
            "tool: error: Bad.\nhint: Do better.\n"
        );

        let mut coloured = Vec::new();
        diag(false, 0, LogFormat::Text, true)
            .error(&mut coloured, &error, false)
            .expect("write");
        assert!(coloured.contains(&0x1b));

        let mut machine = Vec::new();
        diag(false, 0, LogFormat::Text, true)
            .error(&mut machine, &error, true)
            .expect("write");
        let envelope: serde_json::Value = serde_json::from_slice(&machine).expect("json");
        assert_eq!(envelope["error"]["exitCode"], 2);
        assert!(!machine.contains(&0x1b));

        let mut no_hint = Vec::new();
        let bare = CliError::new(crate::exit::Exit::NotFound, "Gone.");
        diag(false, 0, LogFormat::Text, false)
            .error(&mut no_hint, &bare, false)
            .expect("write");
        assert_eq!(
            String::from_utf8(no_hint).expect("utf8"),
            "tool: error: Gone.\n"
        );
    }
}
```

### `src/term.rs`

```rust
//! Terminal facts and the per-stream colour and prompt policies.
//!
//! Every decision here is a pure function of [`Terminal`], [`Env`] and the parsed flags, so it is unit-tested
//! without a real terminal.

use std::collections::BTreeMap;
use std::io::{self, BufRead, IsTerminal, Write};

use anstyle::Style;

/// Which standard streams are attached to a terminal.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Terminal {
    /// stdin is a TTY.
    pub stdin: bool,
    /// stdout is a TTY.
    pub stdout: bool,
    /// stderr is a TTY.
    pub stderr: bool,
}

impl Terminal {
    /// Inspects the real process streams.
    #[must_use]
    pub fn detect() -> Self {
        Self {
            stdin: io::stdin().is_terminal(),
            stdout: io::stdout().is_terminal(),
            stderr: io::stderr().is_terminal(),
        }
    }
}

/// A snapshot of the environment variables the tool reads, so tests can inject their own.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Env {
    vars: BTreeMap<String, String>,
}

impl Env {
    /// Captures the process environment (variables that aren't valid UTF-8 are skipped).
    #[must_use]
    pub fn from_process() -> Self {
        Self::from_pairs(
            std::env::vars_os()
                .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?))),
        )
    }

    /// Builds an environment from `(name, value)` pairs.
    #[must_use]
    pub fn from_pairs<K: Into<String>, V: Into<String>>(
        pairs: impl IntoIterator<Item = (K, V)>,
    ) -> Self {
        Self {
            vars: pairs
                .into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect(),
        }
    }

    /// The value of `name`, if set.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&str> {
        self.vars.get(name).map(String::as_str)
    }

    /// Whether `name` is set to a non-empty value.
    #[must_use]
    pub fn is_non_empty(&self, name: &str) -> bool {
        self.get(name).is_some_and(|v| !v.is_empty())
    }

    /// Whether `name` is set to a value meaning "on" (non-empty and not `0`/`false`).
    #[must_use]
    pub fn is_truthy(&self, name: &str) -> bool {
        self.get(name)
            .is_some_and(|v| !v.is_empty() && v != "0" && !v.eq_ignore_ascii_case("false"))
    }
}

/// Decides whether one stream gets colour.
///
/// Off when `--no-color` or `NO_COLOR` is set (they always win), when the stream carries machine output or is
/// silenced by `--quiet`, or when `TERM=dumb`; `FORCE_COLOR`/`CLICOLOR_FORCE` force it on for people;
/// otherwise colour follows whether the stream is a TTY.
#[must_use]
pub fn color_enabled(
    stream_is_tty: bool,
    env: &Env,
    no_color_flag: bool,
    machine_or_quiet: bool,
) -> bool {
    if no_color_flag || env.is_non_empty("NO_COLOR") || machine_or_quiet {
        return false;
    }
    if env.is_truthy("FORCE_COLOR") || env.is_truthy("CLICOLOR_FORCE") {
        return true;
    }
    if env.get("TERM") == Some("dumb") {
        return false;
    }
    stream_is_tty
}

/// Decides whether the tool may ask a question.
///
/// Only when stdin and stderr are TTYs and neither `--no-input` (or `<BIN>_NO_INPUT`, folded into the flag by
/// clap) nor `CI` is set. Otherwise the command must fail fast and name the flag that supplies the value.
#[must_use]
pub fn can_prompt(terminal: Terminal, env: &Env, no_input: bool) -> bool {
    terminal.stdin && terminal.stderr && !no_input && !env.is_truthy("CI")
}

/// Writes `question` to `stderr` and reads one trimmed line from `stdin`.
///
/// # Errors
///
/// Returns the I/O error of the write or the read.
pub fn prompt(
    stderr: &mut dyn Write,
    stdin: &mut dyn BufRead,
    question: &str,
) -> io::Result<String> {
    write!(stderr, "{question} ")?;
    stderr.flush()?;
    let mut line = String::new();
    stdin.read_line(&mut line)?;
    Ok(line.trim().to_owned())
}

/// Applies styles only when colour is enabled for the stream.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Painter {
    enabled: bool,
}

impl Painter {
    /// A painter that styles text only when `enabled`.
    #[must_use]
    pub const fn new(enabled: bool) -> Self {
        Self { enabled }
    }

    /// Whether this painter emits escape sequences.
    #[must_use]
    pub const fn enabled(self) -> bool {
        self.enabled
    }

    /// Wraps `text` in `style` (or returns it unchanged when colour is off).
    #[must_use]
    pub fn paint(self, style: Style, text: &str) -> String {
        if self.enabled {
            format!("{}{text}{}", style.render(), style.render_reset())
        } else {
            text.to_owned()
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use anstyle::Style;

    use super::{Env, Painter, Terminal, can_prompt, color_enabled, prompt};

    const TTY: Terminal = Terminal {
        stdin: true,
        stdout: true,
        stderr: true,
    };

    #[test]
    fn colour_follows_the_tty_by_default() {
        let env = Env::default();
        assert!(color_enabled(true, &env, false, false));
        assert!(!color_enabled(false, &env, false, false));
    }

    #[test]
    fn no_color_flag_env_machine_mode_and_dumb_terminals_disable_colour() {
        assert!(!color_enabled(true, &Env::default(), true, false));
        assert!(!color_enabled(
            true,
            &Env::from_pairs([("NO_COLOR", "1")]),
            false,
            false
        ));
        assert!(!color_enabled(true, &Env::default(), false, true));
        assert!(!color_enabled(
            true,
            &Env::from_pairs([("TERM", "dumb")]),
            false,
            false
        ));
        // An empty NO_COLOR doesn't count.
        assert!(color_enabled(
            true,
            &Env::from_pairs([("NO_COLOR", "")]),
            false,
            false
        ));
    }

    #[test]
    fn force_color_wins_over_the_tty_but_not_over_no_color() {
        assert!(color_enabled(
            false,
            &Env::from_pairs([("FORCE_COLOR", "1")]),
            false,
            false
        ));
        assert!(color_enabled(
            false,
            &Env::from_pairs([("CLICOLOR_FORCE", "1")]),
            false,
            false
        ));
        assert!(!color_enabled(
            false,
            &Env::from_pairs([("FORCE_COLOR", "0")]),
            false,
            false
        ));
        assert!(!color_enabled(
            true,
            &Env::from_pairs([("FORCE_COLOR", "1"), ("NO_COLOR", "1")]),
            false,
            false
        ));
        assert!(!color_enabled(
            true,
            &Env::from_pairs([("FORCE_COLOR", "1")]),
            true,
            false
        ));
    }

    #[test]
    fn prompts_need_a_terminal_and_no_opt_out() {
        assert!(can_prompt(TTY, &Env::default(), false));
        assert!(!can_prompt(TTY, &Env::default(), true));
        assert!(!can_prompt(TTY, &Env::from_pairs([("CI", "true")]), false));
        assert!(can_prompt(TTY, &Env::from_pairs([("CI", "false")]), false));
        assert!(!can_prompt(
            Terminal {
                stdin: false,
                ..TTY
            },
            &Env::default(),
            false
        ));
        assert!(!can_prompt(
            Terminal {
                stderr: false,
                ..TTY
            },
            &Env::default(),
            false
        ));
    }

    #[test]
    fn prompt_writes_the_question_to_stderr_and_trims_the_answer() {
        let mut stderr = Vec::new();
        let mut stdin = Cursor::new("  Ada \n");
        let answer = prompt(&mut stderr, &mut stdin, "Name?").expect("prompt");
        assert_eq!(answer, "Ada");
        assert_eq!(stderr, b"Name? ");
    }

    #[test]
    fn painter_emits_escapes_only_when_enabled() {
        let bold = Style::new().bold();
        assert_eq!(Painter::new(false).paint(bold, "x"), "x");
        assert!(Painter::new(true).paint(bold, "x").contains('\u{1b}'));
        assert!(Painter::new(true).enabled());
    }

    #[test]
    fn env_reads_values() {
        let env = Env::from_pairs([("A", "1"), ("B", "")]);
        assert_eq!(env.get("A"), Some("1"));
        assert!(env.is_non_empty("A"));
        assert!(!env.is_non_empty("B"));
        assert!(!env.is_truthy("B"));
        assert!(!env.is_truthy("MISSING"));
        // The real environment can always be captured.
        let _ = Env::from_process();
        let _ = Terminal::detect();
    }
}
```

### `src/config.rs`

Implemented, not just documented: flags and `__BIN_ENV___*` variables come from clap's `env` feature, the TOML file from `toml`. Every key has a flag and an env var.

```rust
//! Settings resolution: flags → environment (`__BIN_ENV___*`, read by clap) → config file → built-in defaults.
//!
//! The config file is TOML at `--config`/`__BIN_ENV___CONFIG`, else `$XDG_CONFIG_HOME/__bin__/config.toml`
//! (`~/.config/__bin__/config.toml`; `%APPDATA%\__bin__\config.toml` on Windows). Every key has a flag and an
//! environment variable with the same meaning. Startup never writes configuration.

use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::cli::GlobalArgs;
use crate::error::CliError;
use crate::exit::Exit;
use crate::output::{Format, LogFormat};
use crate::term::Env;

/// The keys the config file may set.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
pub struct ConfigFile {
    /// Same as `--format`.
    pub format: Option<Format>,
    /// Same as `--no-color`.
    pub no_color: Option<bool>,
    /// Same as `--no-input`.
    pub no_input: Option<bool>,
}

/// The effective settings after every layer was applied.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Settings {
    /// Result format on stdout.
    pub format: Format,
    /// Colour disabled by flag or config.
    pub no_color: bool,
    /// Prompts disabled by flag, environment or config.
    pub no_input: bool,
    /// Informational stderr output suppressed.
    pub quiet: bool,
    /// Diagnostic verbosity (number of `-v`).
    pub verbose: u8,
    /// Diagnostic format on stderr.
    pub log_format: LogFormat,
    /// The config file that was read, if any.
    pub config_path: Option<PathBuf>,
}

impl Settings {
    /// Applies the config file under the flags/environment values clap already resolved.
    #[must_use]
    pub fn resolve(global: &GlobalArgs, file: &ConfigFile, config_path: Option<PathBuf>) -> Self {
        let format = if global.json {
            Format::Json
        } else {
            global.format.or(file.format).unwrap_or_default()
        };
        Self {
            format,
            no_color: global.no_color || file.no_color.unwrap_or(false),
            no_input: global.no_input || file.no_input.unwrap_or(false),
            quiet: global.quiet,
            verbose: global.verbose,
            log_format: global.log_format,
            config_path,
        }
    }
}

/// The user config file location, or `None` when no base directory is known.
#[must_use]
pub fn default_path(env: &Env, windows: bool) -> Option<PathBuf> {
    let base = if let Some(xdg) = env
        .get("XDG_CONFIG_HOME")
        .filter(|v| Path::new(v).is_absolute())
    {
        PathBuf::from(xdg)
    } else if windows {
        PathBuf::from(env.get("APPDATA").filter(|v| !v.is_empty())?)
    } else {
        Path::new(env.get("HOME").filter(|v| !v.is_empty())?).join(".config")
    };
    Some(base.join(crate::BIN_NAME).join("config.toml"))
}

/// Loads the config file: the explicit `--config` path (which must exist) or the default location (optional).
///
/// # Errors
///
/// Exit 4 when an explicit file is missing, exit 3 when a file isn't valid, exit 1 on another I/O error.
pub fn load(explicit: Option<&Path>, env: &Env) -> Result<(ConfigFile, Option<PathBuf>), CliError> {
    let (path, required) = match explicit {
        Some(path) => (path.to_path_buf(), true),
        None => match default_path(env, cfg!(windows)) {
            Some(path) => (path, false),
            None => return Ok((ConfigFile::default(), None)),
        },
    };
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound && !required => {
            return Ok((ConfigFile::default(), None));
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(CliError::new(
                Exit::NotFound,
                format!("Config file {} doesn't exist.", path.display()),
            )
            .with_hint("Check the --config path or the __BIN_ENV___CONFIG variable."));
        }
        Err(e) => return Err(e.into()),
    };
    let file = toml::from_str(&text).map_err(|e| {
        CliError::validation(
            format!("Config file {} is invalid: {}", path.display(), e.message()),
            "Fix the file; allowed keys are format, no-color and no-input.",
        )
    })?;
    Ok((file, Some(path)))
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::{ConfigFile, Settings, default_path, load};
    use crate::cli::GlobalArgs;
    use crate::exit::Exit;
    use crate::output::Format;
    use crate::term::Env;

    #[test]
    fn flags_win_over_the_file_and_the_file_over_defaults() {
        let file = ConfigFile {
            format: Some(Format::Jsonl),
            no_color: Some(true),
            no_input: Some(true),
        };
        let from_file = Settings::resolve(&GlobalArgs::default(), &file, None);
        assert_eq!(from_file.format, Format::Jsonl);
        assert!(from_file.no_color && from_file.no_input);

        let flags = GlobalArgs {
            format: Some(Format::Text),
            ..GlobalArgs::default()
        };
        assert_eq!(Settings::resolve(&flags, &file, None).format, Format::Text);
        let json = GlobalArgs {
            json: true,
            format: Some(Format::Text),
            ..GlobalArgs::default()
        };
        assert_eq!(Settings::resolve(&json, &file, None).format, Format::Json);
        assert_eq!(
            Settings::resolve(&GlobalArgs::default(), &ConfigFile::default(), None).format,
            Format::Text
        );
    }

    #[test]
    fn default_path_follows_xdg_then_platform_defaults() {
        let xdg = Env::from_pairs([("XDG_CONFIG_HOME", "/xdg"), ("HOME", "/home/u")]);
        assert_eq!(
            default_path(&xdg, false),
            Some(PathBuf::from("/xdg/__bin__/config.toml"))
        );
        let relative_xdg = Env::from_pairs([("XDG_CONFIG_HOME", "rel"), ("HOME", "/home/u")]);
        assert_eq!(
            default_path(&relative_xdg, false),
            Some(PathBuf::from("/home/u/.config/__bin__/config.toml"))
        );
        let windows = Env::from_pairs([("APPDATA", "C:/Users/u/AppData/Roaming")]);
        assert_eq!(
            default_path(&windows, true),
            Some(
                PathBuf::from("C:/Users/u/AppData/Roaming")
                    .join("__bin__")
                    .join("config.toml")
            )
        );
        assert_eq!(default_path(&Env::default(), false), None);
        assert_eq!(default_path(&Env::default(), true), None);
    }

    #[test]
    fn load_reads_validates_and_reports_missing_files() {
        let dir = tempfile::tempdir().expect("tempdir");
        let good = dir.path().join("good.toml");
        std::fs::write(&good, "format = \"json\"\nno-color = true\n").expect("write");
        let (file, path) = load(Some(&good), &Env::default()).expect("load");
        assert_eq!(file.format, Some(Format::Json));
        assert_eq!(path, Some(good));

        let bad = dir.path().join("bad.toml");
        std::fs::write(&bad, "colour = 1\n").expect("write");
        assert_eq!(
            load(Some(&bad), &Env::default())
                .expect_err("invalid")
                .exit(),
            Exit::Validation
        );

        let missing = dir.path().join("missing.toml");
        assert_eq!(
            load(Some(&missing), &Env::default())
                .expect_err("missing")
                .exit(),
            Exit::NotFound
        );
        assert_eq!(
            load(Some(dir.path()), &Env::default())
                .expect_err("a directory")
                .exit(),
            Exit::Internal
        );

        // The default location is optional, and no base directory means no file.
        let env = Env::from_pairs([("XDG_CONFIG_HOME", dir.path().to_str().expect("utf8"))]);
        assert_eq!(
            load(None, &env).expect("optional"),
            (ConfigFile::default(), None)
        );
        assert_eq!(
            load(None, &Env::default()).expect("none"),
            (ConfigFile::default(), None)
        );
    }
}
```

### `src/signals.rs`

```rust
//! SIGINT/SIGTERM handling.
//!
//! With nothing to clean up, the first signal ends the process at once with 130 (SIGINT) or 143 (SIGTERM) —
//! a blocked read such as a prompt can't keep Ctrl-C from working. A command with cleanup to do (temp files,
//! a half-written output) holds a [`Signals::defer`] guard: the signal is then only recorded, the command
//! stops at its next check of [`Signals::received`], cleans up and returns, and [`crate::run`] prints one line
//! and exits 130/143. A second signal while deferred exits immediately.

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use signal_hook::consts::{SIGINT, SIGTERM};
use signal_hook::flag;

use crate::exit::Exit;

/// The termination signals received so far, and whether the next one ends the process immediately.
#[derive(Clone, Debug, Default)]
pub struct Signals {
    received: Arc<AtomicUsize>,
    exit_now: Arc<AtomicBool>,
}

impl Signals {
    /// Installs the handlers for SIGINT and SIGTERM (on Windows, Ctrl-C arrives as SIGINT).
    ///
    /// # Errors
    ///
    /// Returns the error of registering a handler.
    pub fn install() -> io::Result<Self> {
        let signals = Self {
            exit_now: Arc::new(AtomicBool::new(true)),
            ..Self::default()
        };
        for signal in [SIGINT, SIGTERM] {
            let exit = if signal == SIGINT {
                Exit::Interrupted
            } else {
                Exit::Terminated
            };
            // Handlers run in registration order: exit if armed, otherwise arm for the next signal and record.
            flag::register_conditional_shutdown(
                signal,
                i32::from(exit.code()),
                Arc::clone(&signals.exit_now),
            )?;
            flag::register(signal, Arc::clone(&signals.exit_now))?;
            let number = usize::try_from(signal).unwrap_or_default();
            flag::register_usize(signal, Arc::clone(&signals.received), number)?;
        }
        Ok(signals)
    }

    /// Defers signals until the returned guard is dropped: they are recorded instead of ending the process.
    #[must_use = "signals are deferred only while the guard lives"]
    pub fn defer(&self) -> DeferGuard {
        DeferGuard {
            exit_now: Arc::clone(&self.exit_now),
            previous: self.exit_now.swap(false, Ordering::SeqCst),
        }
    }

    /// The exit code for the signal received so far, if any — commands check this at safe points.
    #[must_use]
    pub fn received(&self) -> Option<Exit> {
        exit_for(self.received.load(Ordering::SeqCst))
    }
}

/// Keeps signals deferred while alive; see [`Signals::defer`].
#[derive(Debug)]
pub struct DeferGuard {
    exit_now: Arc<AtomicBool>,
    previous: bool,
}

impl Drop for DeferGuard {
    fn drop(&mut self) {
        // A signal received meanwhile already re-armed the flag; otherwise restore the previous state.
        self.exit_now.fetch_or(self.previous, Ordering::SeqCst);
    }
}

fn exit_for(signal: usize) -> Option<Exit> {
    match i32::try_from(signal).ok()? {
        SIGINT => Some(Exit::Interrupted),
        SIGTERM => Some(Exit::Terminated),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::Ordering;

    use super::{Signals, exit_for};
    use crate::exit::Exit;

    #[test]
    fn signals_map_to_their_exit_codes() {
        assert_eq!(exit_for(0), None);
        let sigint = usize::try_from(signal_hook::consts::SIGINT).expect("int");
        let sigterm = usize::try_from(signal_hook::consts::SIGTERM).expect("int");
        assert_eq!(exit_for(sigint), Some(Exit::Interrupted));
        assert_eq!(exit_for(sigterm), Some(Exit::Terminated));
        assert_eq!(Signals::default().received(), None);
    }

    #[test]
    fn defer_disarms_and_restores() {
        let signals = Signals::default();
        signals.exit_now.store(true, Ordering::SeqCst);
        {
            let _guard = signals.defer();
            assert!(!signals.exit_now.load(Ordering::SeqCst));
        }
        assert!(signals.exit_now.load(Ordering::SeqCst));
    }
}
```

### `src/commands/mod.rs`

```rust
//! One module per command, plus the safety metadata `schema` publishes for each.

// >>> ui: tui
pub mod browse;
// <<< ui: tui
pub mod completion;
pub mod greet;
pub mod schema;
pub mod version;

use serde::Serialize;

use crate::exit::Exit;

/// What a command may do — published by `schema` and mapped to MCP tool annotations by wrappers.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Safety {
    /// Changes nothing.
    pub read_only: bool,
    /// May delete or overwrite data.
    pub destructive: bool,
    /// Running it twice with the same input has the same effect as once.
    pub idempotent: bool,
    /// Talks to systems outside this machine.
    pub open_world: bool,
    /// Needs `--yes` (or a prompt answer) before it acts.
    pub requires_confirmation: bool,
}

/// The read-only, local, idempotent profile every sample command has.
const READ_ONLY: Safety = Safety {
    read_only: true,
    destructive: false,
    idempotent: true,
    open_world: false,
    requires_confirmation: false,
};

/// Exit codes and safety of one command.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CommandInfo {
    /// Every exit code the command can return.
    pub exit_codes: &'static [Exit],
    /// What the command may do.
    pub safety: Safety,
}

/// Metadata for the command named `name` (as in the command tree); `None` for an unknown name.
///
/// Every new command must be added here — a unit test walks the command tree and fails otherwise.
#[must_use]
pub fn info(name: &str) -> Option<CommandInfo> {
    // Every command can fail on its arguments (2), on the config file (3, 4), with a bug (1) or on a signal.
    const COMMON: &[Exit] = &[
        Exit::Ok,
        Exit::Internal,
        Exit::Usage,
        Exit::Validation,
        Exit::NotFound,
        Exit::Interrupted,
        Exit::Terminated,
    ];
    let known = matches!(name, "greet" | "completion" | "schema" | "version");
    // >>> ui: tui
    let known = known || name == "browse";
    // <<< ui: tui
    known.then_some(CommandInfo {
        exit_codes: COMMON,
        safety: READ_ONLY,
    })
}

#[cfg(test)]
mod tests {
    use clap::CommandFactory;

    use super::info;
    use crate::cli::Cli;

    #[test]
    fn every_command_has_metadata() {
        for sub in Cli::command().get_subcommands() {
            assert!(
                info(sub.get_name()).is_some(),
                "add `{}` to commands::info",
                sub.get_name()
            );
        }
        assert!(info("nope").is_none());
    }
}
```

### `src/commands/greet.rs`

````rust
//! `greet [NAME]` — the sample read-only command.

use clap::Args;
use serde::Serialize;

use crate::error::CliError;
use crate::output::{Format, SCHEMA_VERSION, write_json, write_jsonl};
use crate::term::{can_prompt, prompt};
use crate::{Context, Session};

/// The longest name `greet` accepts, in characters.
pub const MAX_NAME_CHARS: usize = 64;

/// Arguments of `greet`.
#[derive(Debug, Default, Args)]
pub struct GreetArgs {
    /// Who to greet: 1 to 64 characters, no control characters [default: World].
    #[arg(value_name = "NAME", conflicts_with = "ask")]
    pub name: Option<String>,

    /// Ask for the name interactively (needs a terminal; fails with exit code 2 under --no-input, CI or a pipe).
    #[arg(long)]
    pub ask: bool,
}

/// The result of `greet`, as printed with `--json`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Greeting {
    /// The full greeting, e.g. `Hello, Ada!`.
    pub greeting: String,
    /// The validated name.
    pub name: String,
    /// The output schema version.
    pub schema_version: u32,
}

/// Validates `name` and builds its greeting.
///
/// # Errors
///
/// Exit 3 (validation) when the name is empty, longer than [`MAX_NAME_CHARS`] or contains control characters.
///
/// # Examples
///
/// ```
/// let greeting = __crate__::commands::greet::greeting("Ada").unwrap();
/// assert_eq!(greeting.greeting, "Hello, Ada!");
/// ```
pub fn greeting(name: &str) -> Result<Greeting, CliError> {
    let name = name.trim();
    let problem = if name.is_empty() {
        Some("The name is empty.".to_owned())
    } else if name.chars().count() > MAX_NAME_CHARS {
        Some(format!(
            "The name is longer than {MAX_NAME_CHARS} characters."
        ))
    } else if name.chars().any(char::is_control) {
        Some("The name contains control characters.".to_owned())
    } else {
        None
    };
    if let Some(message) = problem {
        return Err(CliError::validation(
            message,
            "Pass a name of 1-64 printable characters, e.g. `greet Ada`.",
        )
        .with_detail("field", "name")
        .with_detail("maxChars", MAX_NAME_CHARS));
    }
    Ok(Greeting {
        greeting: format!("Hello, {name}!"),
        name: name.to_owned(),
        schema_version: SCHEMA_VERSION,
    })
}

/// Runs `greet`.
///
/// # Errors
///
/// Exit 2 when `--ask` can't prompt, exit 3 for an invalid name, or the I/O error of writing the result.
pub fn run(args: &GreetArgs, ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    let name = if args.ask {
        if !can_prompt(ctx.terminal, &ctx.env, session.settings.no_input) {
            return Err(CliError::usage(
                "greet --ask needs an interactive terminal and prompts are disabled here.",
                "Pass the name as an argument instead, e.g. `greet Ada`.",
            ));
        }
        prompt(ctx.stderr, ctx.stdin, "Name?")?
    } else {
        args.name.clone().unwrap_or_else(|| "World".to_owned())
    };
    let result = greeting(&name)?;
    session
        .diagnostics
        .debug(ctx.stderr, &format!("greeting {:?}", result.name))?;
    write_greeting(ctx, session, &result)
}

/// Writes a greeting in the session's format (reusable by other commands).
///
/// # Errors
///
/// Returns the I/O error of the write.
pub fn write_greeting(
    ctx: &mut Context<'_>,
    session: &Session,
    result: &Greeting,
) -> Result<(), CliError> {
    match session.settings.format {
        Format::Json => write_json(ctx.stdout, result),
        Format::Jsonl => write_jsonl(ctx.stdout, std::slice::from_ref(result)),
        Format::Text => {
            let name = session
                .stdout_painter
                .paint(anstyle::Style::new().bold(), &result.name);
            writeln!(ctx.stdout, "Hello, {name}!")?;
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{MAX_NAME_CHARS, greeting};
    use crate::exit::Exit;

    #[test]
    fn greets_a_trimmed_name() {
        let result = greeting("  Ada ").expect("valid");
        assert_eq!(result.greeting, "Hello, Ada!");
        assert_eq!(result.name, "Ada");
        assert_eq!(result.schema_version, 1);
    }

    #[test]
    fn rejects_empty_long_and_control_names() {
        for bad in [
            String::new(),
            "   ".to_owned(),
            "x".repeat(MAX_NAME_CHARS + 1),
            "a\u{7}b".to_owned(),
        ] {
            let err = greeting(&bad).expect_err("invalid");
            assert_eq!(err.exit(), Exit::Validation);
            assert!(err.hint().is_some());
        }
        assert!(greeting(&"x".repeat(MAX_NAME_CHARS)).is_ok());
    }
}
````

### `src/commands/completion.rs`

```rust
//! `completion <SHELL>` — prints a completion script generated from the command tree.

use clap::{Args, CommandFactory};
use clap_complete::Shell;

use crate::cli::Cli;
use crate::error::CliError;
use crate::{BIN_NAME, Context};

/// Arguments of `completion`.
#[derive(Debug, Args)]
pub struct CompletionArgs {
    /// The shell to generate the script for.
    #[arg(value_enum)]
    pub shell: Shell,
}

/// Generates the completion script for `shell`.
#[must_use]
pub fn script(shell: Shell) -> Vec<u8> {
    let mut buffer = Vec::new();
    clap_complete::generate(shell, &mut Cli::command(), BIN_NAME, &mut buffer);
    buffer
}

/// Runs `completion`.
///
/// # Errors
///
/// Returns the I/O error of writing the script.
pub fn run(args: &CompletionArgs, ctx: &mut Context<'_>) -> Result<(), CliError> {
    // Generated into memory first: clap_complete panics on a failed write, we want a clean broken-pipe exit.
    ctx.stdout.write_all(&script(args.shell))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use clap_complete::Shell;

    use super::script;

    #[test]
    fn every_shell_gets_a_script_naming_the_binary() {
        for shell in [
            Shell::Bash,
            Shell::Zsh,
            Shell::Fish,
            Shell::Elvish,
            Shell::PowerShell,
        ] {
            let text = String::from_utf8(script(shell)).expect("utf8");
            assert!(text.contains("__bin__"), "{shell}");
        }
    }
}
```

### `src/commands/schema.rs`

Walks clap's `Command` into the JSON tree (commands, positional args, flags with type/enum values/default/env, per-command exit codes and safety, the exit-code table). `cargo xtask docs` renders the CLI reference from the same function.

```rust
//! `schema [COMMAND]` — the machine-readable command tree, built by walking clap's `Command`.
//!
//! The same document feeds `cargo xtask docs` (CLI reference) and MCP wrappers (tool schemas and annotations).

use clap::builder::PossibleValue;
use clap::{Arg, ArgAction, Args, CommandFactory};
use serde_json::{Map, Value, json};

use crate::cli::Cli;
use crate::commands::info;
use crate::error::CliError;
use crate::exit::Exit;
use crate::output::{Format, SCHEMA_VERSION, write_json, write_jsonl};
use crate::{BIN_NAME, Context, Session};

/// Arguments of `schema`.
#[derive(Debug, Args)]
pub struct SchemaArgs {
    /// Print only this command (e.g. `greet`) instead of the whole tree.
    #[arg(value_name = "COMMAND")]
    pub command: Option<String>,
}

/// The whole command tree of `cmd` as a JSON document.
#[must_use]
pub fn tree(cmd: &clap::Command) -> Value {
    let global_flags: Vec<Value> = cmd
        .get_arguments()
        .filter(|a| a.is_global_set())
        .map(flag)
        .collect();
    let commands: Vec<Value> = cmd.get_subcommands().map(command).collect();
    let exit_codes: Vec<Value> = Exit::ALL
        .iter()
        .map(|e| json!({"code": e.code(), "meaning": e.meaning(), "name": e.name()}))
        .collect();
    json!({
        "commands": commands,
        "exitCodes": exit_codes,
        "globalFlags": global_flags,
        "name": cmd.get_name(),
        "schemaVersion": SCHEMA_VERSION,
        "summary": cmd.get_about().map(ToString::to_string),
        "version": cmd.get_version(),
    })
}

/// One command of the tree as a JSON object.
#[must_use]
pub fn command(cmd: &clap::Command) -> Value {
    let meta = info(cmd.get_name());
    let positionals: Vec<Value> = cmd.get_positionals().map(positional).collect();
    let flags: Vec<Value> = cmd
        .get_arguments()
        .filter(|a| !a.is_positional() && !a.is_global_set() && !is_builtin(a))
        .map(flag)
        .collect();
    json!({
        "args": positionals,
        "exitCodes": meta.map(|m| m.exit_codes.iter().map(|e| e.code()).collect::<Vec<_>>()),
        "flags": flags,
        "name": cmd.get_name(),
        "output": {"schemaVersion": SCHEMA_VERSION},
        "safety": meta.map(|m| m.safety),
        "summary": cmd.get_about().map(ToString::to_string),
    })
}

fn is_builtin(arg: &Arg) -> bool {
    matches!(arg.get_id().as_str(), "help" | "version")
}

fn positional(arg: &Arg) -> Value {
    let mut value = describe(arg);
    value.insert(
        "name".into(),
        arg.get_value_names().and_then(|n| n.first()).map_or_else(
            || arg.get_id().to_string().into(),
            |n| n.to_lowercase().into(),
        ),
    );
    value.insert("required".into(), arg.is_required_set().into());
    value.into()
}

fn flag(arg: &Arg) -> Value {
    let mut value = describe(arg);
    value.insert(
        "name".into(),
        arg.get_long().map(|l| format!("--{l}")).into(),
    );
    value.insert(
        "short".into(),
        arg.get_short().map(|s| format!("-{s}")).into(),
    );
    value.insert(
        "env".into(),
        arg.get_env()
            .map(|e| e.to_string_lossy().into_owned())
            .into(),
    );
    value.into()
}

fn describe(arg: &Arg) -> Map<String, Value> {
    let (kind, values) = match arg.get_action() {
        ArgAction::SetTrue | ArgAction::SetFalse => ("boolean", Vec::new()),
        ArgAction::Count => ("count", Vec::new()),
        _ => {
            let values: Vec<String> = arg
                .get_possible_values()
                .iter()
                .filter(|v| !v.is_hide_set())
                .map(PossibleValue::get_name)
                .map(str::to_owned)
                .collect();
            (if values.is_empty() { "string" } else { "enum" }, values)
        }
    };
    let defaults: Vec<String> = arg
        .get_default_values()
        .iter()
        .map(|d| d.to_string_lossy().into_owned())
        .collect();
    let mut map = Map::new();
    map.insert("type".into(), kind.into());
    if !values.is_empty() {
        map.insert("values".into(), values.into());
    }
    map.insert("default".into(), defaults.first().cloned().into());
    map.insert(
        "description".into(),
        arg.get_help().map(ToString::to_string).into(),
    );
    map.insert(
        "repeatable".into(),
        matches!(arg.get_action(), ArgAction::Append | ArgAction::Count).into(),
    );
    map
}

/// Runs `schema`.
///
/// # Errors
///
/// Exit 4 for an unknown command name, or the I/O error of the write.
pub fn run(args: &SchemaArgs, ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    let cli = Cli::command();
    let document = match &args.command {
        None => tree(&cli),
        Some(name) => {
            let sub = cli.find_subcommand(name).ok_or_else(|| {
                CliError::new(Exit::NotFound, format!("No command named '{name}'."))
                    .with_hint(format!("Run `{BIN_NAME} schema` to list the commands."))
                    .with_detail("command", name.as_str())
            })?;
            command(sub)
        }
    };
    match session.settings.format {
        Format::Json => write_json(ctx.stdout, &document),
        Format::Jsonl => {
            let commands = document
                .get("commands")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_else(|| vec![document]);
            write_jsonl(ctx.stdout, &commands)
        }
        Format::Text => {
            let pretty = serde_json::to_string_pretty(&document)
                .map_err(|e| CliError::internal(e.to_string()))?;
            writeln!(ctx.stdout, "{pretty}")?;
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use clap::CommandFactory;

    use super::{command, tree};
    use crate::cli::Cli;

    #[test]
    fn the_tree_lists_commands_global_flags_and_exit_codes() {
        let doc = tree(&Cli::command());
        assert_eq!(doc["schemaVersion"], 1);
        assert_eq!(doc["name"], "__bin__");
        assert_eq!(doc["exitCodes"].as_array().map(Vec::len), Some(10));
        let globals = doc["globalFlags"].as_array().expect("array");
        let format = globals
            .iter()
            .find(|f| f["name"] == "--format")
            .expect("--format");
        assert_eq!(format["type"], "enum");
        assert_eq!(format["env"], "__BIN_ENV___FORMAT");
        let verbose = globals
            .iter()
            .find(|f| f["name"] == "--verbose")
            .expect("--verbose");
        assert_eq!(verbose["type"], "count");
        assert_eq!(verbose["short"], "-v");
        let names: Vec<&str> = doc["commands"]
            .as_array()
            .expect("array")
            .iter()
            .filter_map(|c| c["name"].as_str())
            .collect();
        assert!(names.contains(&"greet") && names.contains(&"schema"));
    }

    #[test]
    fn a_command_has_args_flags_safety_and_exit_codes() {
        let cli = Cli::command();
        let greet = command(cli.find_subcommand("greet").expect("greet"));
        assert_eq!(greet["args"][0]["name"], "name");
        assert_eq!(greet["args"][0]["required"], false);
        assert_eq!(greet["flags"][0]["name"], "--ask");
        assert_eq!(greet["flags"][0]["type"], "boolean");
        assert_eq!(greet["safety"]["readOnly"], true);
        assert!(
            greet["exitCodes"]
                .as_array()
                .expect("array")
                .contains(&3.into())
        );
        let completion = command(cli.find_subcommand("completion").expect("completion"));
        assert_eq!(completion["args"][0]["type"], "enum");
    }
}
```

### `src/commands/version.rs`

```rust
//! `version` — the version plus build metadata (`--version` prints the version alone).

use serde::Serialize;

use crate::error::CliError;
use crate::output::{Format, SCHEMA_VERSION, write_json, write_jsonl};
use crate::{BIN_NAME, Context, Session};

/// Version and build target, as printed with `--json`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionInfo {
    /// The executable name.
    pub name: &'static str,
    /// The crate version (semver; `-dev` on the integration branch).
    pub version: &'static str,
    /// Target operating system (`linux`, `macos`, `windows`, …).
    pub os: &'static str,
    /// Target architecture (`x86_64`, `aarch64`, …).
    pub arch: &'static str,
    /// The output schema version.
    pub schema_version: u32,
}

/// The running binary's version information.
#[must_use]
pub fn info() -> VersionInfo {
    VersionInfo {
        name: BIN_NAME,
        version: env!("CARGO_PKG_VERSION"),
        os: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        schema_version: SCHEMA_VERSION,
    }
}

/// Runs `version`.
///
/// # Errors
///
/// Returns the I/O error of the write.
pub fn run(ctx: &mut Context<'_>, session: &Session) -> Result<(), CliError> {
    let info = info();
    match session.settings.format {
        Format::Json => write_json(ctx.stdout, &info),
        Format::Jsonl => write_jsonl(ctx.stdout, &[info]),
        Format::Text => {
            writeln!(
                ctx.stdout,
                "{} {} ({}-{})",
                info.name, info.version, info.os, info.arch
            )?;
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn reports_the_crate_version() {
        let info = super::info();
        assert_eq!(info.version, env!("CARGO_PKG_VERSION"));
        assert_eq!(info.name, "__bin__");
    }
}
```
