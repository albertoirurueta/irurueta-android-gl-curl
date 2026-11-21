# Testing

Writing the tests is part of the task. This skill writes them and compile-checks them (`cargo check
--all-targets`); `iru-rust-code-one-task-group` runs them, measures coverage (80% line coverage per changed source
file, cargo-llvm-cov), and runs clippy over them. Tests are code: the crate's lints apply to them too.

## Where each test goes

| What changed | Test | Location |
|---|---|---|
| A function/type in the library (parsing, validation, policy, rendering to a buffer, a command's logic) | unit test | `#[cfg(test)] mod tests { use super::*; … }` at the bottom of the same file |
| Behaviour a caller sees through the public API of a library crate | integration test | `tests/<concern>.rs` (shared helpers in `tests/common/mod.rs`, never `tests/common.rs`) |
| A CLI command's observable contract: stdout, stderr, exit code, no-TTY behaviour, env vars | end-to-end test running the real binary | `tests/cli.rs` (or the file the crate already uses) |
| A `pub` item's documented example | doctest | the `# Examples` block (`rustdoc.md`) |
| A TUI's state/rendering | component/screen/flow UI tests with `TestBackend`, each rendered state captured as `.txt` into `.ui-screenshots/` across directions × themes, plus the accessibility checks | the TUI module (`ratatui.md`, `ui-implementation.md` §4–§6) |

Put most tests at the cheapest level that can see the behaviour: a command's logic tested with in-memory writers runs
in microseconds; end-to-end tests pin the contract (one per exit code, golden JSON, no-TTY, colour) and catch wiring
mistakes in `main.rs`.

## Unit tests with injected writers

Commands write through `&mut dyn Write` handles in a context struct (`output-and-terminal.md`), so a test passes
`Vec<u8>` buffers and asserts the exact bytes. Keep one small test helper in the crate (`#[cfg(test)] pub(crate) mod
test_support`) instead of rebuilding the context in every test:

```rust
#[cfg(test)]
pub(crate) mod test_support {
    use std::cell::Cell;

    use crate::commands::Ctx;
    use crate::output::Format;
    use crate::term::{Confirm, Ttys};

    /// A scripted [`Confirm`] that counts the questions asked.
    pub struct FakeConfirm {
        pub answer: bool,
        pub asked: Cell<usize>,
    }

    impl Confirm for FakeConfirm {
        fn confirm(&mut self, _question: &str) -> std::io::Result<bool> {
            self.asked.set(self.asked.get() + 1);
            Ok(self.answer)
        }
    }

    /// In-memory streams plus the knobs a command test needs.
    pub struct TestIo {
        pub out: Vec<u8>,
        pub err: Vec<u8>,
        pub format: Format,
        pub ttys: Ttys,
        pub may_prompt: bool,
        pub confirm: FakeConfirm,
    }

    impl TestIo {
        pub fn new(format: Format) -> Self {
            Self {
                out: Vec::new(),
                err: Vec::new(),
                format,
                ttys: Ttys::default(),
                may_prompt: false,
                confirm: FakeConfirm { answer: false, asked: Cell::new(0) },
            }
        }

        pub fn ctx(&mut self) -> Ctx<'_> {
            Ctx {
                out: &mut self.out,
                err: &mut self.err,
                format: self.format,
                colour_out: false,
                colour_err: false,
                may_prompt: self.may_prompt,
                quiet: false,
                ttys: self.ttys,
                confirm: &mut self.confirm,
            }
        }

        pub fn stdout(&self) -> String {
            String::from_utf8_lossy(&self.out).into_owned()
        }
        // …stderr(), questions_asked(), and builder knobs like interactive(answer) / ttys(t)
    }
}
```

A command test then reads like the contract:

```rust
#[test]
fn prints_each_format() {
    for (format, expected) in [
        (Format::Text, "Hello, Ada!\n"),
        (Format::Json, "{\"greeting\":\"Hello, Ada!\",\"name\":\"Ada\",\"schemaVersion\":1}\n"),
    ] {
        let mut io = TestIo::new(format);
        run(&mut io.ctx(), &GreetArgs { name: "Ada".into() }).unwrap();
        assert_eq!(io.stdout(), expected);
        assert_eq!(io.stderr(), "");
    }
}

#[test]
fn without_a_tty_needs_confirmation_and_never_prompts() {
    let mut io = TestIo::new(Format::Json);
    let Err(RunError::Cli(err)) = run(&mut io.ctx(), &ResetArgs { dry_run: false, yes: false }) else {
        panic!("expected exit 5")
    };
    assert_eq!(err.exit, Exit::ConfirmationRequired);
    assert_eq!(io.questions_asked(), 0);
}
```

Also unit-test every policy as a pure function of plain values — colour (`colour_enabled(is_tty, &env, flag,
machine)`), prompting (`may_prompt(ttys, &env, no_input)`), the exit-code table (every variant → number → name), the
broken-pipe path (a `Write` impl that returns `ErrorKind::BrokenPipe`), and `Cli::command().debug_assert()` (catches
conflicting/duplicate clap arguments at test time instead of at a user's first run).

Habits:

- Test names state the behaviour; one behaviour per test; `assert_eq!` with the expected value second-to-none
  readable (exact strings for short output, parsed JSON for long).
- A test may return `Result<(), E>` and use `?`; `#[should_panic(expected = "...")]` only for documented panics.
- **Never touch process-global state**: no `std::env::set_var` (unsafe in edition 2024, racy across parallel tests) —
  pass an `EnvFacts`-style snapshot built from a closure/map instead; no fixed ports or file names — `tempfile`;
  no `std::env::set_current_dir`.
- Edge cases from the doc comments' `# Errors`/`# Panics` sections each get a test; validation limits get both
  sides of the boundary (64 accepted, 65 rejected; characters vs. bytes).
- `proptest`/`rstest`/`serial_test` only if already a dev-dependency.

## End-to-end tests of the binary

`assert_cmd` runs the compiled binary; `env!("CARGO_BIN_EXE_<bin>")` (set by Cargo for integration tests of a
package with that `[[bin]]`) is the path. Build every command from one helper that makes the environment
deterministic:

```rust
// `allow-unwrap-in-tests` covers `#[test]` fns only, not this file's helper functions.
#![allow(clippy::unwrap_used, reason = "test helpers fail the test on purpose")]

use assert_cmd::Command;
use predicates::prelude::*;
use serde_json::Value;

/// The binary with a clean, deterministic environment: no inherited config, colour or CI vars,
/// HOME/XDG dirs in a temp dir, and stdin closed (never a TTY in tests).
fn democli(home: &tempfile::TempDir) -> Command {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_democli"));
    cmd.env_clear()
        .env("HOME", home.path())
        .env("XDG_CONFIG_HOME", home.path().join("config"))
        .env("PATH", std::env::var_os("PATH").unwrap_or_default())
        .write_stdin("");
    // Keep coverage of the child working under `cargo llvm-cov` (env_clear drops it otherwise).
    if let Some(profile) = std::env::var_os("LLVM_PROFILE_FILE") {
        cmd.env("LLVM_PROFILE_FILE", profile);
    }
    #[cfg(windows)]
    for key in ["SystemRoot", "TEMP", "TMP"] {
        if let Some(v) = std::env::var_os(key) {
            cmd.env(key, v);
        }
    }
    cmd
}

fn home() -> tempfile::TempDir {
    tempfile::tempdir().unwrap()
}
```

Why each line matters:

- `env_clear()` keeps a developer's `NO_COLOR`, `CI`, `<BIN>_TOKEN`, `<BIN>_CONFIG` or real config from making a
  test pass or fail. `HOME`/`XDG_CONFIG_HOME` point into a temp dir so the tool never reads the user's config.
- **Forward `LLVM_PROFILE_FILE`** — verified: without it, `cargo llvm-cov` loses every line the spawned binary
  executes (`main.rs` reported 0% and stray `default_*.profraw` files appear in the package root); with it,
  `main.rs` and the end-to-end paths are measured.
- `write_stdin("")` gives the child a closed pipe: stdin is not a TTY and a stray prompt reads EOF instead of hanging.
  For a "never prompts" test also set `.timeout(Duration::from_secs(10))`.
- On Windows a child without `SystemRoot` can fail in surprising ways (sockets, crypto); keep it.

The tests that every contract CLI has (`cli-contract.md` and irurueta docs: ai/cli-for-agents/testing-clis.adoc):

```rust
#[test]
fn greet_json_matches_golden_bytes() {
    let golden = std::fs::read_to_string("tests/golden/greet-ada.json").unwrap();
    democli(&home())
        .args(["greet", "Ada", "--json"])
        .assert()
        .success()
        .stdout(golden)
        .stderr("");
}

#[test]
fn every_emitted_exit_code_has_a_test() {
    let h = home();
    let cases: [(&[&str], i32); 6] = [
        (&["greet"], 0),
        (&["greet", "--no-such-flag"], 2),
        (&["browse"], 2),
        (&["greet", " ", "--json"], 3),
        (&["reset", "--json"], 5),
        (&["reset", "--dry-run", "--json"], 0),
    ];
    for (args, code) in cases {
        democli(&h).args(args).assert().code(code);
    }
}

#[test]
fn json_failures_write_one_envelope_on_stderr_and_nothing_on_stdout() {
    let out = democli(&home())
        .args(["reset", "--json"])
        .assert()
        .code(5)
        .stdout("")
        .get_output()
        .clone();
    let v: Value = serde_json::from_slice(&out.stderr).unwrap();
    assert_eq!(v["schemaVersion"], 1);
    assert_eq!(v["error"]["code"], "CONFIRMATION_REQUIRED");
    assert_eq!(v["error"]["exitCode"], 5);
    assert!(v["error"]["hint"].as_str().unwrap().contains("--yes"));
}

#[test]
fn colour_only_when_forced_and_no_color_always_wins() {
    let h = home();
    let esc = predicate::str::contains("\u{1b}[");
    democli(&h).args(["greet", " "]).assert().code(3).stderr(esc.clone().not());
    democli(&h)
        .args(["greet", " "])
        .env("FORCE_COLOR", "1")
        .assert()
        .code(3)
        .stderr(esc.clone());
    democli(&h)
        .args(["greet", " "])
        .env("FORCE_COLOR", "1")
        .env("NO_COLOR", "1")
        .assert()
        .code(3)
        .stderr(esc.not());
}
```

When a task adds a command or an error path, add its row to the exit-code table test, a golden file for its `--json`
output, and — for anything that can ask a question — a closed-stdin test proving it fails fast with exit 2 or 5 and a
hint naming the bypass flag. Assert `stderr("")` on success paths and `stdout("")` on JSON failures: an extra log
line on stdout is a contract break. Env-backed flags get an e2e test with the variable set (`DEMOCLI_NO_INPUT=1`) —
a bool flag with `env` rejects `1` unless it uses `BoolishValueParser` (`clap.md`, verified).

### Golden files and snapshots

- **Golden files** (`tests/golden/<command>-<case>.json`, trailing newline included): compare bytes for JSON whose
  determinism is part of the contract; compare parsed `serde_json::Value`s when only the shape matters. Replace only
  what genuinely varies (absolute temp paths, versions) before comparing. A changed golden file is a reviewed
  interface change: update it in the same task, and say so in the report (it may need a `schemaVersion` bump if a
  field was renamed/removed/retyped).
- **insta** (only if already a dev-dependency): `insta::assert_json_snapshot!(value)` / `assert_snapshot!(text)`; a
  new snapshot is written as `*.snap.new` — read it, and if correct rename it to `.snap` (or `cargo insta accept` if
  cargo-insta is installed). Never accept a changed snapshot you didn't read. Snapshot tests fail in CI when
  `CI` is set and a snapshot is missing.
- **trycmd / snapbox** (only if already used): `tests/cmd/*.trycmd` or `.toml` cases with `$ democli …`, expected
  output, and `? <code>`; `[..]`/`...` elide variable parts. `TRYCMD=overwrite` rewrites expectations — review the
  diff like a golden file.
- **TUI captures** (`.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>.txt`) are evidence, not
  assertions: they are written through `src/tui/ui_screenshots.rs` on every run, gitignored and cleared by
  `iru-rust-test` at the start of a full run — never committed and never compared (`ui-implementation.md` §5).
- **`--help` snapshots** (if the crate keeps them, e.g. `tests/help/*.txt` or insta): regenerate with `NO_COLOR=1`
  and a fixed width (`#[command(max_term_width = 100)]` or `COLUMNS`), and review the diff — a lost flag shows up
  there.

## Container-backed and network tests

A test that needs Docker (testcontainers-rs), a real database, or the network is marked
`#[ignore = "requires docker (testcontainers)"]` — the catalog's marker, which `iru-rust-test` excludes by default
and the group skill runs last behind the repository-wide lock. Keep such tests few; the logic they exercise must also
be covered by container-free tests, because only those count toward the 80% gate. Plain CLI end-to-end tests need
no marker and no lock.

## Doctests

Examples in `pub` doc comments are compiled and run by `cargo test --doc` (the group runs them; nextest can't).
Keep them hermetic — no network, no real config, no terminal; `no_run` for an example that would perform I/O, and the
hidden `# Ok::<(), E>(())` line to use `?`. Doctests don't count toward coverage on stable.
