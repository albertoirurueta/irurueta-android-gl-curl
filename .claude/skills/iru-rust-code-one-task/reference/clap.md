# clap 4 (derive)

The command line is a published interface (`cli-contract.md` §4, §5, §7): flag names, value sets, defaults, help text
and exit code 2 for usage errors are part of it. Keep the clap types in one module (`src/cli.rs` in the scaffold) —
types only, no behaviour — and the behaviour in `src/commands/<name>.rs`.

If the crate uses the **builder API** (`Command::new(...)`, `Arg::new(...)`, `matches.get_one::<T>("id")`), keep
using it; the rules below about names, help, env, parsing and errors are the same, only the syntax differs. Don't
convert between the two as part of a task.

## Shape

```rust
/// Greets people — a demo of an agent-friendly CLI.
#[derive(Debug, Parser)]
#[command(name = "democli", version, about, max_term_width = 100)]
pub struct Cli {
    /// Global flags available on every command.
    #[command(flatten)]
    pub global: GlobalArgs,

    /// The command to run.
    #[command(subcommand)]
    pub command: Command,
}

/// Flags shared by every command (contract §1, §3, §4).
#[derive(Debug, Args)]
pub struct GlobalArgs {
    /// Output format.
    #[arg(long, global = true, value_enum, default_value_t = Format::Text)]
    pub format: Format,

    /// Alias for `--format json`.
    #[arg(long, global = true)]
    pub json: bool,

    /// Never colour output.
    #[arg(long, global = true)]
    pub no_color: bool,

    /// Never prompt; fail with the flag that supplies the answer instead.
    // A bare `env` on a bool flag only accepts `true`/`false`; Boolish also takes 1/0/yes/no/on/off.
    #[arg(long, global = true, env = "DEMOCLI_NO_INPUT", value_parser = clap::builder::BoolishValueParser::new())]
    pub no_input: bool,

    /// Suppress informational messages on stderr.
    #[arg(long, short, global = true)]
    pub quiet: bool,

    /// More diagnostics on stderr (repeatable).
    #[arg(long, short, global = true, action = clap::ArgAction::Count)]
    pub verbose: u8,
}

/// The subcommands.
#[derive(Debug, Subcommand)]
pub enum Command {
    /// Print a greeting. Read-only.
    ///
    /// Example: `democli greet Ada --json`
    Greet(GreetArgs),
    /// Forget every remembered greeting. Destructive: needs `--yes` (or `--dry-run`).
    ///
    /// Example: `democli reset --dry-run --json`
    Reset(ResetArgs),
    /// Print a shell completion script.
    ///
    /// Example: `democli completion zsh > _democli`
    Completion {
        /// Target shell.
        #[arg(value_enum)]
        shell: Shell,
    },
    /// Print the machine-readable command tree as JSON.
    Schema,
    /// Browse greetings interactively (needs a terminal; agents use `greet --json`).
    Browse,
}

/// Arguments of `reset`.
#[derive(Debug, Args)]
pub struct ResetArgs {
    /// Show the plan and change nothing.
    #[arg(long)]
    pub dry_run: bool,
    /// Confirm the destructive action.
    #[arg(long, short = 'y')]
    pub yes: bool,
}
```

Facts verified against clap 4.6.7:

- **Doc comments are the help text.** The first paragraph is the short help (clap drops its trailing period:
  "Print a greeting. Read-only"), the rest is `long_about` shown by `--help`. Say *when* to use the command and give
  one real `Example:` line (contract §7). `#[command(about)]` with no value takes `CARGO_PKG_DESCRIPTION` instead of
  the doc comment; `version` with no value takes `CARGO_PKG_VERSION`.
- **`env = "..."` on a `bool` flag** uses clap's `BoolValueParser`, which rejects `DEMOCLI_NO_INPUT=1` with exit 2
  ("invalid value '1' for '--no-input' [possible values: true, false]"). Add
  `value_parser = clap::builder::BoolishValueParser::new()` (accepts 1/0, yes/no, on/off, true/false). The `env`
  attribute needs the `env` cargo feature.
- **`--json` alias**: a separate `bool` folded in one place (`fn effective_format(&self) -> Format { if self.json {
  Format::Json } else { self.format } }`), not a second `--format` definition.
- `Cli::command().debug_assert()` in a unit test catches duplicate ids/shorts and invalid defaults at test time.

## Conventions for new arguments

- Names: kebab-case long flags derived from `snake_case` fields (`dry_run` → `--dry-run`); a short flag only for the
  common ones (`-q`, `-v`, `-y`); never reuse a global flag's name or short in a subcommand.
- **Closed value sets are `ValueEnum`s** (`#[derive(clap::ValueEnum)]`, each variant documented — the docs become the
  possible-values help). Numbers get ranges: `#[arg(long, default_value_t = 20, value_parser =
  clap::value_parser!(u16).range(1..=100))]`. Free text is a `String` validated in the domain layer (exit 3), not in
  a clap closure that would turn a validation failure into a usage error (exit 2).
- **"Not given" vs. a default**: when a value can also come from the environment or a config file, make the field
  `Option<T>` and apply the default after merging (below), so the layers can tell "absent" from "explicitly the
  default".
- Destructive commands get `--dry-run` and `--yes`/`-y` (and `--force` only for overriding a safety check — a
  different flag, contract §3/§5). Network/long commands get `--timeout` (`humantime`-style parsing only if the crate
  already depends on it; otherwise seconds as an integer).
- List commands get `--limit` (small default) and `--cursor`; `--fields a,b` only if the task asks.
- **Never enable prefix matching**: leave `infer_subcommands` and `infer_long_args` off (the defaults) — an agent's
  typo must fail, not run a different command. `--` already ends option parsing in clap; don't add
  `allow_hyphen_values`/`trailing_var_arg` to an operand that comes from users unless the task requires it.
- A hidden flag (`#[arg(hide = true)]`) is still returned by `get_arguments()`; filter it out of `schema` with
  `is_hide_set()` unless the task says agents should see it.

## Parsing and usage errors

`lib::run` uses `Cli::try_parse_from(args)` — never `Cli::parse()` (which writes to the real stdout/stderr and calls
`process::exit`, bypassing the injected writers and the JSON envelope):

```rust
/// `--help`/`--version` go to stdout with exit 0; every other parse error is a usage error (2).
fn report_parse_error(e: &clap::Error, args: &[OsString], io: &mut Io<'_>) -> Exit {
    if !e.use_stderr() {
        let _ = write!(io.out, "{}", e.render());
        return Exit::Ok;
    }
    let format = if wants_json(args) {
        Format::Json
    } else {
        Format::Text
    };
    if format == Format::Text {
        let _ = write!(io.err, "{}", e.render());
        return Exit::Usage;
    }
    let message = e.render().to_string();
    let first = message
        .lines()
        .next()
        .unwrap_or_default()
        .trim_start_matches("error: ")
        .to_owned();
    let err = CliError::new(Exit::Usage, first, format!("See `{BIN} --help`."));
    let _ = write_error(io.err, BIN, &err, format, false);
    Exit::Usage
}
```

`e.render()` returns a `StyledStr` whose `Display` is **plain text** (verified); use `e.render().ansi()` only when the
colour policy allows colour on that stream. clap's rendered usage error is already the contract's shape (one
`error:` line, a usage line, "For more information, try '--help'.") — don't print the full help on an error.
`wants_json` is a best-effort scan of the raw args before `--` for `--json`/`--format json|jsonl`, used only because
parsing failed.

## Adding a subcommand — the checklist

1. A documented variant in the `Command` enum (help + example) and, if it takes arguments, an `Args` struct.
2. `src/commands/<name>.rs` with `pub fn run(ctx: &mut Ctx<'_>, args: &<Name>Args) -> Result<(), RunError>`, logic in
   pure functions next to it; declare the module in `commands/mod.rs`.
3. One match arm in `lib::run`'s dispatch (exhaustive match — the compiler finds the missing arm).
4. Its safety class in the `schema` command (`readOnly`/`destructive`/`idempotent`/`interactiveOnly`).
5. Unit tests for the logic, end-to-end rows for each exit code it can emit, a golden file for its `--json` output.
6. Completions, man pages and the CLI reference regenerate from the tree — never edit generated files by hand. If
   the repository commits generated docs (`cargo xtask docs` writing `docs/modules/ROOT/pages/cli-reference.adoc`
   or a partial), say in the report that they need regenerating; the group skill does it.

## Completions, man pages, schema

- `completion <shell>`: `clap_complete::generate(shell, &mut Cli::command(), "<bin>", ctx.out)` — to the injected
  writer. `clap_complete::Shell` is already a `ValueEnum`.
- Man pages belong in the dev-time generator (the `xtask` member), not behind a runtime flag:
  `clap_mangen::generate_to(cmd, dir)` writes `<bin>.1` plus `<bin>-<sub>.1` for every visible subcommand (it
  disables the `help` subcommand itself — verified with clap_mangen 0.3.3). The xtask imports the CLI crate's `Cli`
  through the library (`<crate>::cli::Cli::command()`), which is one more reason the clap types live in the lib.
- `schema` walks `Cli::command()` (`get_subcommands()`, `get_arguments()`, `get_long()`, `is_required_set()`,
  `get_help()`), filters out globals and the auto `help` arg, and adds the exit-code table and each command's safety
  — generated from the same tree, so it can't drift.

## Configuration precedence (when the crate has config)

Flags → environment → config file → defaults (contract §6). clap already applies flag-over-env when a field has
`env = "<BIN>_<NAME>"`; the file and the defaults are merged after parsing:

```rust
/// Flags as parsed by clap: `None` means "not given on the command line or in the environment".
#[derive(Debug, Default, clap::Args)]
pub struct ConfigArgs {
    /// Maximum items to return.
    #[arg(long, env = "DEMOCLI_LIMIT")]
    pub limit: Option<u32>,
    /// Service endpoint.
    #[arg(long, env = "DEMOCLI_ENDPOINT")]
    pub endpoint: Option<String>,
}

/// The `config.toml` file; every key is optional and unknown keys are an error.
#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, rename_all = "kebab-case")]
pub struct FileConfig {
    /// Default `--limit`.
    pub limit: Option<u32>,
    /// Default `--endpoint`.
    pub endpoint: Option<String>,
}

/// Merges the layers: flags/env (clap already applied that order) → file → built-in defaults.
pub fn merge(args: ConfigArgs, file: FileConfig) -> Settings {
    Settings {
        limit: args.limit.or(file.limit).unwrap_or(20),
        endpoint: args
            .endpoint
            .or(file.endpoint)
            .unwrap_or_else(|| "https://api.example.com".to_owned()),
    }
}

/// `$XDG_CONFIG_HOME/<bin>/config.toml`, else `$HOME/.config/<bin>/config.toml` (Windows: `%APPDATA%\<bin>\`).
pub fn config_path(get: impl Fn(&str) -> Option<String>, bin: &str) -> Option<PathBuf> {
    let base = get("XDG_CONFIG_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(|| get("HOME").map(|h| Path::new(&h).join(".config")))
        .or_else(|| get("APPDATA").map(PathBuf::from))?;
    Some(base.join(bin).join("config.toml"))
}
```

A missing file is an empty config (not an error); an unreadable or invalid file is exit 3 naming the path (never its
content if it may hold a secret). Read the environment through a lookup closure so tests never call `set_var`.
`--verbose` reports on stderr which file was read. Verified with toml 1.1.8 and serde 1.0.229 (`deny_unknown_fields`
rejects a misspelt key). Adding config support to a crate that has none is a dependency decision — only when the task
asks for it.
