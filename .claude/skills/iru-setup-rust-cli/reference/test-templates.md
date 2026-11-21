# Test templates

In-process tests of `run` with injected terminal facts and environment (`src/tests.rs`, declared at the end of
`src/lib.rs`), end-to-end tests of the real binary (`tests/cli.rs`, assert_cmd + predicates + snapbox golden files),
and the constant golden file. `tests/golden/schema.json` is **not** a template: Step 6 generates it with
`SNAPSHOTS=overwrite cargo test --test cli` and the user reviews it like any golden change.

Conditional markers (`// >>> <condition>` … `// <<< <condition>` in Rust, `# >>> …` in TOML) and the `__Token__`
placeholders work as described in SKILL.md's "Reference files" section; the marker lines themselves are never
written. A heading suffixed `(condition)` is written only when the condition holds. Every file below was rendered
and verified on 2026-10-16 for `ui: none` and `ui: tui` (Rust 1.99.1; MSRV toolchains 1.85.0 for `ui: none`, 1.88.0 for `ui: tui`) — see SKILL.md
"Verification notes".

### `src/tests.rs`

The only test that installs signal handlers is `a_deferred_signal_is_recorded_and_wins_with_130_or_143`: handlers are process-wide, so a second test raising a signal would end a `cargo test` run (nextest runs each test in its own process and doesn't care).

```rust
//! In-process tests of [`crate::run`]: terminal facts and the environment are injected, so TTY-only paths
//! (colour, prompts, the TUI gate) are covered without a real terminal.

use std::io::{self, Cursor, Write};

use crate::exit::Exit;
use crate::signals::Signals;
use crate::term::{Env, Terminal};
use crate::{Context, guarded, run};

const TTY: Terminal = Terminal {
    stdin: true,
    stdout: true,
    stderr: true,
};

struct Outcome {
    exit: Exit,
    stdout: String,
    stderr: String,
}

fn run_with(args: &[&str], terminal: Terminal, env: &[(&str, &str)], stdin: &str) -> Outcome {
    let (mut out, mut err) = (Vec::new(), Vec::new());
    let mut input = Cursor::new(stdin.as_bytes().to_vec());
    let mut ctx = Context {
        stdout: &mut out,
        stderr: &mut err,
        stdin: &mut input,
        terminal,
        env: Env::from_pairs(env.iter().copied()),
        signals: Signals::default(),
    };
    let exit = run(
        std::iter::once("__bin__").chain(args.iter().copied()),
        &mut ctx,
    );
    Outcome {
        exit,
        stdout: String::from_utf8(out).expect("utf8"),
        stderr: String::from_utf8(err).expect("utf8"),
    }
}

fn piped(args: &[&str]) -> Outcome {
    run_with(args, Terminal::default(), &[], "")
}

#[test]
fn greet_prints_text_json_and_jsonl() {
    let text = piped(&["greet", "Ada"]);
    assert_eq!(
        (text.exit, text.stdout.as_str(), text.stderr.as_str()),
        (Exit::Ok, "Hello, Ada!\n", "")
    );
    let json = piped(&["greet", "Ada", "--json"]);
    assert_eq!(
        json.stdout,
        "{\"greeting\":\"Hello, Ada!\",\"name\":\"Ada\",\"schemaVersion\":1}\n"
    );
    let jsonl = piped(&["--format", "jsonl", "greet"]);
    assert_eq!(jsonl.stdout.lines().count(), 2);
    assert!(jsonl.stdout.contains("\"name\":\"World\""));
}

#[test]
fn colour_is_used_on_a_terminal_unless_disabled() {
    assert!(
        run_with(&["greet", "Ada"], TTY, &[], "")
            .stdout
            .contains('\u{1b}')
    );
    assert!(
        !run_with(&["greet", "Ada", "--no-color"], TTY, &[], "")
            .stdout
            .contains('\u{1b}')
    );
    assert!(
        !run_with(&["greet", "Ada"], TTY, &[("NO_COLOR", "1")], "")
            .stdout
            .contains('\u{1b}')
    );
    assert!(
        run_with(&["greet", ""], TTY, &[], "")
            .stderr
            .contains('\u{1b}')
    );
    assert!(
        run_with(&["--help"], TTY, &[], "")
            .stdout
            .contains('\u{1b}')
    );
    assert!(run_with(&["nope"], TTY, &[], "").stderr.contains('\u{1b}'));
}

#[test]
fn ask_prompts_only_on_a_terminal() {
    let asked = run_with(&["greet", "--ask"], TTY, &[], "Grace\n");
    assert_eq!(asked.exit, Exit::Ok);
    assert_eq!(asked.stdout, "Hello, \u{1b}[1mGrace\u{1b}[0m!\n");
    assert_eq!(asked.stderr, "Name? ");
    for (terminal, env, flag) in [
        (Terminal::default(), vec![], None),
        (TTY, vec![("CI", "true")], None),
        (TTY, vec![], Some("--no-input")),
    ] {
        let mut args = vec!["greet", "--ask"];
        args.extend(flag);
        let refused = run_with(&args, terminal, &env, "Grace\n");
        assert_eq!(refused.exit, Exit::Usage);
        assert!(
            refused.stderr.contains("Pass the name as an argument"),
            "{}",
            refused.stderr
        );
    }
}

#[test]
fn verbose_and_quiet_shape_stderr_only() {
    let verbose = piped(&["-v", "--log-format", "json", "greet", "Ada"]);
    assert_eq!(verbose.stdout, "Hello, Ada!\n");
    assert_eq!(
        verbose.stderr,
        "{\"level\":\"debug\",\"message\":\"greeting \\\"Ada\\\"\"}\n"
    );
    let quiet = piped(&["-q", "greet", "Ada"]);
    assert_eq!(quiet.stderr, "");
}

#[test]
fn config_file_supplies_defaults_and_its_errors_are_reported() {
    let dir = tempfile::tempdir().expect("tempdir");
    let config = dir.path().join("config.toml");
    std::fs::write(&config, "format = \"json\"\n").expect("write");
    let path = config.to_str().expect("utf8");
    let from_file = piped(&["--config", path, "-v", "greet", "Ada"]);
    assert!(from_file.stdout.starts_with('{'));
    assert!(from_file.stderr.contains("read config file"));
    let missing = piped(&["--config", "/definitely/missing.toml", "greet"]);
    assert_eq!(missing.exit, Exit::NotFound);
}

#[test]
fn version_and_schema_commands() {
    assert!(
        piped(&["version"])
            .stdout
            .starts_with(&format!("__bin__ {} (", env!("CARGO_PKG_VERSION")))
    );
    assert!(
        piped(&["version", "--json"])
            .stdout
            .contains("\"schemaVersion\":1")
    );
    assert_eq!(
        piped(&["version", "--format", "jsonl"])
            .stdout
            .lines()
            .count(),
        2
    );
    assert!(piped(&["schema"]).stdout.contains("\"globalFlags\""));
    let one = piped(&["schema", "greet", "--json"]);
    assert!(one.stdout.starts_with("{\"args\""));
    let lines = piped(&["schema", "--format", "jsonl"]).stdout;
    assert!(
        lines
            .lines()
            .last()
            .is_some_and(|l| l.contains("\"type\":\"summary\""))
    );
    assert_eq!(
        piped(&["schema", "greet", "--format", "jsonl"])
            .stdout
            .lines()
            .count(),
        2
    );
    let unknown = piped(&["schema", "nope", "--json"]);
    assert_eq!(unknown.exit, Exit::NotFound);
    assert!(unknown.stderr.contains("\"code\":\"NOT_FOUND\""));
}

#[test]
fn usage_errors_exit_2_with_text_or_an_envelope() {
    let text = piped(&["nope"]);
    assert_eq!(text.exit, Exit::Usage);
    assert!(text.stderr.contains("unrecognized subcommand"));
    for args in [
        &["--json", "nope"][..],
        &["--format", "json", "nope"],
        &["--format=jsonl", "greet", "--bogus"],
    ] {
        let machine = piped(args);
        assert_eq!(machine.exit, Exit::Usage);
        let envelope: serde_json::Value = serde_json::from_str(&machine.stderr).expect("envelope");
        assert_eq!(envelope["error"]["code"], "USAGE");
    }
    let env_json = run_with(
        &["nope"],
        Terminal::default(),
        &[("__BIN_ENV___FORMAT", "json")],
        "",
    );
    assert!(env_json.stderr.starts_with('{'));
    let back_to_text = piped(&[
        "--format",
        "json",
        "--format",
        "text",
        "--format=text",
        "nope",
    ]);
    assert!(back_to_text.stderr.starts_with("error"));
    assert!(piped(&["--", "--json"]).stderr.starts_with("error"));
    assert_eq!(piped(&[]).exit, Exit::Usage);
}

#[test]
fn help_and_version_are_results_on_stdout() {
    let help = piped(&["greet", "--help"]);
    assert_eq!(help.exit, Exit::Ok);
    assert!(help.stdout.contains("Examples:"));
    assert_eq!(
        piped(&["--version"]).stdout,
        format!("__bin__ {}\n", env!("CARGO_PKG_VERSION"))
    );
}

#[test]
fn completion_prints_a_script() {
    let script = piped(&["completion", "bash"]).stdout;
    assert!(script.contains("complete -F") && script.contains("__bin__"));
}

struct BrokenPipe;

impl Write for BrokenPipe {
    fn write(&mut self, _: &[u8]) -> io::Result<usize> {
        Err(io::Error::from(io::ErrorKind::BrokenPipe))
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[test]
fn a_closed_stdout_pipe_ends_quietly() {
    for args in [&["__bin__", "schema"][..], &["__bin__", "--help"]] {
        let (mut out, mut err) = (BrokenPipe, Vec::new());
        let mut input = Cursor::new(Vec::new());
        let mut ctx = Context {
            stdout: &mut out,
            stderr: &mut err,
            stdin: &mut input,
            terminal: Terminal::default(),
            env: Env::default(),
            signals: Signals::default(),
        };
        assert_eq!(run(args.iter().copied(), &mut ctx), Exit::Ok);
        assert_eq!(err, b"");
    }
}

#[test]
fn panics_become_messages_instead_of_exit_101() {
    let message = guarded(|| panic!("boom")).expect_err("panicked");
    assert!(message.contains("boom"));
    assert_eq!(guarded(|| Exit::Ok), Ok(Exit::Ok));
}

#[cfg(unix)]
#[test]
fn a_deferred_signal_is_recorded_and_wins_with_130_or_143() {
    // The only test that installs handlers: they are process-wide. Deferred, the signal is recorded, not fatal.
    let signals = Signals::install().expect("install");
    let guard = signals.defer();
    signal_hook::low_level::raise(signal_hook::consts::SIGTERM).expect("raise");
    let (mut out, mut err) = (Vec::new(), Vec::new());
    let mut input = Cursor::new(Vec::new());
    let mut ctx = Context {
        stdout: &mut out,
        stderr: &mut err,
        stdin: &mut input,
        terminal: Terminal::default(),
        env: Env::default(),
        signals: signals.clone(),
    };
    assert_eq!(run(["__bin__", "greet"], &mut ctx), Exit::Terminated);
    assert_eq!(
        String::from_utf8(err).expect("utf8"),
        "__bin__: terminated\n"
    );
    // Keep the handlers deferred until the end of the process: a later signal would end the test run.
    std::mem::forget(guard);
}
// >>> ui: tui

#[test]
fn browse_refuses_without_a_terminal_and_validates_names() {
    let refused = piped(&["browse"]);
    assert_eq!(refused.exit, Exit::Usage);
    assert!(refused.stderr.contains("greet NAME --json"));
    assert_eq!(
        run_with(&["browse", "--no-input"], TTY, &[], "").exit,
        Exit::Usage
    );
    assert_eq!(
        run_with(&["browse", ""], TTY, &[], "").exit,
        Exit::Validation
    );
}

#[test]
fn browse_outcomes_become_output_and_exit_codes() {
    use crate::commands::browse::{BrowseArgs, finish, preflight};
    use crate::config::{ConfigFile, Settings};
    use crate::tui::Outcome;

    let (mut out, mut err) = (Vec::new(), Vec::new());
    let mut input = Cursor::new(Vec::new());
    let mut ctx = Context {
        stdout: &mut out,
        stderr: &mut err,
        stdin: &mut input,
        terminal: TTY,
        env: Env::default(),
        signals: Signals::default(),
    };
    let settings = Settings::resolve(
        &crate::cli::GlobalArgs::default(),
        &ConfigFile::default(),
        None,
    );
    let session = crate::Session::new(settings, &ctx);
    let app = preflight(
        &BrowseArgs {
            names: vec!["Ada".into()],
        },
        &ctx,
        &session,
    )
    .expect("preflight");
    assert_eq!(app.selected(), Some("Ada"));
    finish(Outcome::Selected("Ada".into()), &mut ctx, &session).expect("selected");
    finish(Outcome::Quit, &mut ctx, &session).expect("quit");
    let interrupted = finish(Outcome::Interrupted, &mut ctx, &session).expect_err("ctrl-c");
    assert_eq!(interrupted.exit(), Exit::Interrupted);
    assert!(String::from_utf8(out).expect("utf8").contains("Ada"));
}
// <<< ui: tui
```

### `tests/cli.rs`

`tool()` removes only the variables that change behaviour — never `env_clear()`, which would also drop `LLVM_PROFILE_FILE` and lose the binary's coverage under cargo-llvm-cov.

```rust
//! End-to-end tests: run the real `__bin__` binary with no terminal attached and assert stdout, stderr and
//! the exit code — the contract scripts and agents rely on.
//!
//! Golden files live in `tests/golden/`; after a deliberate output change, refresh them with
//! `SNAPSHOTS=overwrite cargo test --test cli` and review the diff (bump `schemaVersion` if it is breaking).

use assert_cmd::Command;
use predicates::prelude::{PredicateBooleanExt, predicate};
use predicates::str::contains;
use serde_json::Value;
use snapbox::{assert_data_eq, file};

/// The binary with a clean, non-interactive environment (no inherited colour or format settings).
fn tool() -> Command {
    let mut cmd = Command::cargo_bin("__bin__").expect("binary");
    for var in ["NO_COLOR", "FORCE_COLOR", "CLICOLOR_FORCE", "TERM", "CI"] {
        cmd.env_remove(var);
    }
    for var in ["__BIN_ENV___FORMAT", "__BIN_ENV___NO_INPUT", "__BIN_ENV___CONFIG"] {
        cmd.env_remove(var);
    }
    // No user config file is read: point the config directory at an empty location.
    cmd.env("XDG_CONFIG_HOME", env!("CARGO_TARGET_TMPDIR"));
    cmd
}

fn stderr_envelope(output: &std::process::Output) -> Value {
    serde_json::from_slice(&output.stderr).expect("stderr is one JSON envelope")
}

#[test]
fn help_and_version_go_to_stdout_with_exit_0() {
    tool()
        .arg("--help")
        .assert()
        .success()
        .stdout(contains("Usage: __bin__"))
        .stderr("");
    tool()
        .args(["greet", "--help"])
        .assert()
        .success()
        .stdout(contains("Examples:"));
    let version = format!("__bin__ {}\n", env!("CARGO_PKG_VERSION"));
    tool()
        .arg("--version")
        .assert()
        .success()
        .stdout(version)
        .stderr("");
}

#[test]
fn greet_prints_only_the_result_on_stdout() {
    tool()
        .args(["greet", "Ada"])
        .assert()
        .success()
        .stdout("Hello, Ada!\n")
        .stderr("");
    tool()
        .arg("greet")
        .assert()
        .success()
        .stdout("Hello, World!\n");
}

#[test]
fn greet_json_matches_the_golden_file() {
    let output = tool()
        .args(["greet", "Ada", "--json"])
        .output()
        .expect("run");
    assert_eq!(output.status.code(), Some(0));
    assert_data_eq!(output.stdout, file!["golden/greet-ada.json"].raw());
}

#[test]
fn jsonl_ends_with_a_summary_line() {
    tool()
        .args(["greet", "Ada", "--format", "jsonl"])
        .assert()
        .success()
        .stdout(predicate::str::ends_with(
            "{\"nextCursor\":null,\"schemaVersion\":1,\"total\":1,\"type\":\"summary\"}\n",
        ));
}

#[test]
fn no_escape_bytes_without_a_terminal_even_when_forced_off() {
    for env in [None, Some(("NO_COLOR", "1")), Some(("TERM", "dumb"))] {
        let mut cmd = tool();
        if let Some((key, value)) = env {
            cmd.env(key, value);
        }
        let output = cmd.args(["greet", ""]).output().expect("run");
        assert!(!output.stdout.contains(&0x1b) && !output.stderr.contains(&0x1b));
    }
    // FORCE_COLOR turns colour on for people, but NO_COLOR still wins.
    let forced = tool()
        .env("FORCE_COLOR", "1")
        .args(["greet", "Ada"])
        .output()
        .expect("run");
    assert!(forced.stdout.contains(&0x1b));
    let both = tool()
        .env("FORCE_COLOR", "1")
        .env("NO_COLOR", "1")
        .args(["greet", "Ada"])
        .output()
        .expect("run");
    assert!(!both.stdout.contains(&0x1b));
}

#[test]
fn an_invalid_name_exits_3_with_an_envelope_in_json_mode() {
    tool()
        .args(["greet", ""])
        .assert()
        .code(3)
        .stdout("")
        .stderr(contains("__bin__: error: The name is empty.").and(contains("hint: ")));
    let output = tool().args(["greet", "", "--json"]).output().expect("run");
    assert_eq!(output.status.code(), Some(3));
    assert_eq!(output.stdout, b"");
    let envelope = stderr_envelope(&output);
    assert_eq!(envelope["error"]["code"], "VALIDATION");
    assert_eq!(envelope["error"]["exitCode"], 3);
    assert_eq!(envelope["error"]["retryable"], false);
    assert_eq!(envelope["schemaVersion"], 1);
}

#[test]
fn usage_errors_exit_2() {
    tool()
        .arg("nope")
        .assert()
        .code(2)
        .stdout("")
        .stderr(contains("unrecognized subcommand"));
    tool().assert().code(2).stdout("");
    let output = tool()
        .args(["--json", "greet", "--bogus"])
        .output()
        .expect("run");
    assert_eq!(output.status.code(), Some(2));
    assert_eq!(stderr_envelope(&output)["error"]["code"], "USAGE");
    // No prefix matching: an abbreviated command is an error, not a guess.
    tool().arg("gre").assert().code(2);
}

#[test]
fn prompts_fail_fast_without_a_terminal() {
    tool()
        .args(["greet", "--ask"])
        .assert()
        .code(2)
        .stdout("")
        .stderr(contains("Pass the name"));
    tool()
        .env("__BIN_ENV___NO_INPUT", "1")
        .args(["greet", "--ask"])
        .assert()
        .code(2);
}

#[test]
fn unknown_schema_command_exits_4() {
    let output = tool()
        .args(["schema", "nope", "--json"])
        .output()
        .expect("run");
    assert_eq!(output.status.code(), Some(4));
    assert_eq!(stderr_envelope(&output)["error"]["code"], "NOT_FOUND");
}

#[test]
fn schema_matches_the_golden_file() {
    let output = tool().args(["schema", "--json"]).output().expect("run");
    assert_eq!(output.status.code(), Some(0));
    let mut tree: Value = serde_json::from_slice(&output.stdout).expect("json");
    tree["version"] = "[VERSION]".into(); // the version changes on every release; the shape must not
    let pretty = serde_json::to_string_pretty(&tree).expect("json") + "\n";
    assert_data_eq!(pretty, file!["golden/schema.json"].raw());
}

#[test]
fn environment_and_config_file_supply_settings() {
    // Boolean variables accept the usual spellings (clap's BoolishValueParser), never exit 2.
    for value in ["1", "true", "yes", "0", "false", "off"] {
        tool()
            .env("__BIN_ENV___NO_INPUT", value)
            .args(["greet", "Ada"])
            .assert()
            .success();
    }
    tool()
        .env("__BIN_ENV___FORMAT", "json")
        .args(["greet", "Ada"])
        .assert()
        .success()
        .stdout(contains("\"schemaVersion\":1"));
    let config = std::path::Path::new(env!("CARGO_TARGET_TMPDIR")).join("e2e-config.toml");
    std::fs::write(&config, "format = \"json\"\n").expect("write config");
    tool()
        .arg("--config")
        .arg(&config)
        .args(["greet", "Ada"])
        .assert()
        .success()
        .stdout(contains("\"greeting\""));
    // A flag beats the config file.
    tool()
        .arg("--config")
        .arg(&config)
        .args(["--format", "text", "greet", "Ada"])
        .assert()
        .stdout("Hello, Ada!\n");
    tool()
        .args(["--config", "/missing/config.toml", "greet"])
        .assert()
        .code(4);
}

#[test]
fn completion_and_version_commands_work() {
    for shell in ["bash", "zsh", "fish", "powershell", "elvish"] {
        tool()
            .args(["completion", shell])
            .assert()
            .success()
            .stdout(contains("__bin__"));
    }
    tool()
        .args(["version", "--json"])
        .assert()
        .success()
        .stdout(contains(format!(
            "\"version\":\"{}\"",
            env!("CARGO_PKG_VERSION")
        )));
}
// >>> ui: tui

#[test]
fn browse_refuses_to_start_without_a_terminal() {
    tool()
        .arg("browse")
        .assert()
        .code(2)
        .stdout("")
        .stderr(contains("__bin__ greet NAME --json"));
}
// <<< ui: tui
```

### `tests/golden/greet-ada.json`

```json
{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}
```
