# Rust development reference

Rust code agreements for implementing a task in a Cargo package or workspace — an agent-friendly command-line tool
scaffolded by `iru-setup-rust-cli` (`src/lib.rs` + thin `src/main.rs`, clap, optional Ratatui UI, `tests/cli.rs`,
`xtask/`), or any other Rust library, service, or CLI. These files exist so `iru-rust-code-one-task` can load
**only** what the task in front of it needs, instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the project shape first (`iru-rust-code-one-task` Step 0 — workspace vs. single crate, lib/bin targets,
edition/MSRV, clap/ratatui/tokio, the test crates, and whether the project follows the CLI contract), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Edition 2024 rules, module layout (logic in the lib, `main.rs` thin), naming, ownership, the lint policy, dependencies. |
| `testing.md` | The task writes or updates tests — nearly every task, since the tests are part of implementing it. Unit tests, injected writers, `assert_cmd` end-to-end tests with a clean environment, golden JSON, exit-code tables, snapshot crates. |
| `error-handling.md` | The task adds or changes an error type, a fallible function, a `?` chain, a `panic!`/`expect`, or the mapping from failures to exit codes. |
| `rustdoc.md` | The task adds or changes a `pub` item (type, function, field, variant, module, crate docs) — including clap fields, whose doc comments are the `--help` text. |
| `cli-contract.md` | The project is a CLI that follows the contract (Step 0 detection) and the task touches a command, flag, output, exit code, prompt, signal, config, or `schema`/`completion`. Verbatim copy of the catalog-wide contract. |
| `clap.md` | The task adds or changes a subcommand, argument, flag, value enum, env-backed flag, help text, completions, man page/CLI reference generation, or config precedence. |
| `output-and-terminal.md` | The task writes to stdout/stderr, prints JSON/JSONL or the error envelope, decides colour, prompts, shows progress, handles SIGINT/SIGTERM, or could hit a broken pipe. |
| `ratatui.md` | `ratatui` is a dependency and the task touches the terminal UI (state, key handling, rendering, the event loop, its TTY gate). |
| `ui-contract.md` | `ratatui` is a dependency and the task adds or changes a screen, component, list or other UI. Verbatim copy of the catalog-wide UI contract (decomposition, composition, inputs/events, collections, UI tests, screenshots, accessibility). |
| `ui-implementation.md` | Together with `ui-contract.md`: the contract on Ratatui — `Widget`/`StatefulWidget` components, `Action` events, lists/tables that render only the visible window, `TestBackend` component/screen/flow tests, the `src/tui/ui_screenshots.rs` text-capture helper, the asserted accessibility checks and `.ui-quality.yml`. |
| `security.md` | The task spawns a process, resolves a path from an argument or config, parses untrusted input (files, stdin, network), handles a token/secret, writes files, or is tempted to use `unsafe`/`env::set_var`. |
| `async.md` | `tokio` is **already** a dependency and the task touches async code, cancellation, timeouts, or async tests. Never a reason to add tokio. |

A task that only changes logic inside one existing function, with no signature change and no output change, needs
`code-style.md` plus `testing.md`, and nothing else. Don't read the rest speculatively. For a Rust crate that is not a
CLI (a library, a service), `cli-contract.md`, `clap.md`, `output-and-terminal.md`, `ratatui.md` and the two `ui-*.md` files are
simply never routed. Read `cli-contract.md` before `clap.md`/`output-and-terminal.md` when both apply — they implement it.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the crate consistently does
   something else (a different error crate, `anyhow` in the lib, a builder-API clap tree, `insta` instead of golden
   files), follow it and report the divergence, rather than converting the file as a side effect of an unrelated
   task. The CLI contract is the exception for projects that already follow it: there it is non-negotiable (exit
   codes in one enum, stdout/stderr separation, the envelope) — a change that breaks it is a bug, not a style choice.
2. **A task implements what the task says.** These files tell you *how* to write what was asked for. They never
   license a new dependency, a new crate, an abstraction, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond `cargo check --all-targets` of what it touched and formatting its own
   files, it never runs tests, clippy, coverage, `cargo deny`, `cargo doc`, or license-header checks — the calling
   `iru-rust-code-one-task-group` does all of that once for the whole task group. Write code that will pass those
   gates (clippy with the project's `[lints]`, rustfmt, `missing_docs`, 80% line coverage per changed file);
   don't run them here.

## Sources

Compiled from the following, cross-checked against each other and against a real toolchain (Rust 1.99.1, clippy
0.1.99, rustfmt 2024 style edition, cargo-nextest 0.9.148, cargo-llvm-cov 0.9.1) in a throwaway crate outside this
repository — see the skill's Verification notes. Crate versions resolved live from crates.io on 2026-10-16: clap
4.6.7, clap_complete 4.6.11, clap_mangen 0.3.3, ratatui 0.30.2 (crossterm 0.29 re-exported as
`ratatui::crossterm`), anstream 1.0.0, anstyle 1.0.14, owo-colors 4.4.0, dialoguer 0.12.0, signal-hook 0.4.5,
ctrlc 3.5.2, thiserror 2.0.21, anyhow 1.0.104, serde 1.0.229, serde_json 1.0.154, assert_cmd 2.2.2, predicates
3.1.4, snapbox 1.2.2, trycmd 1.2.1, insta 1.49.0, tempfile 3.27.0, tokio 1.53.3. Fallback when crates.io can't be
reached: use whatever the project's `Cargo.lock` already pins — this skill never adds or bumps a dependency on its
own.

- irurueta docs: programming-languages/rust/syntax-comments-and-style.adoc (naming, Rust Style Guide, rustfmt)
- irurueta docs: programming-languages/rust/modules-crates-and-visibility.adoc (structuring a library, thin
  `main.rs`, `pub(crate)`, re-exports)
- irurueta docs: programming-languages/rust/idioms-patterns-and-api-guidelines.adoc (borrowed arguments, newtypes,
  custom types instead of bools, anti-patterns, the API Guidelines checklist)
- irurueta docs: programming-languages/rust/error-handling.adoc (error enums, `thiserror` for libraries / `anyhow`
  for applications, `?` and `From`, errors in `main` and `ExitCode`)
- irurueta docs: programming-languages/rust/testing-and-benchmarking.adoc (unit/integration/doc tests, insta,
  proptest, cargo-nextest, cargo-llvm-cov)
- irurueta docs: programming-languages/rust/documentation-with-rustdoc.adoc (doc comments, conventional sections,
  intra-doc links, doctests, rustdoc lints)
- irurueta docs: programming-languages/rust/tooling-clippy-rustfmt-and-rust-analyzer.adoc (lint groups, `[lints]`,
  `#[expect]` with reasons, no `#![deny(warnings)]`, rustfmt, cargo-deny)
- irurueta docs: programming-languages/rust/cargo-and-workspaces.adoc (features, workspaces, `[workspace.lints]`,
  `.cargo/config.toml` aliases, xtask)
- irurueta docs: programming-languages/rust/toolchain-rustup-and-editions.adoc (edition 2024, `rust-version`, MSRV,
  `rust-toolchain.toml`, targets)
- irurueta docs: programming-languages/rust/io-files-processes-and-environment.adoc (`Read`/`Write`, stdout locking,
  `Command` without a shell, `env::set_var` unsafe in 2024, `ExitCode`)
- irurueta docs: programming-languages/rust/serialization-with-serde.adoc (derive attributes, `rename_all`,
  `deny_unknown_fields`, versioned payloads)
- irurueta docs: ai/cli-for-agents/frameworks-go-and-rust.adoc (clap derive, `ExitCode`, clap_complete,
  clap_mangen, Ratatui `ratatui::run`)
- irurueta docs: ai/cli-for-agents/testing-clis.adoc (golden JSON, no-TTY tests, exit-code tables, `--help`
  snapshots, trycmd/insta for Rust)
- irurueta docs: ai/cli-for-agents/tuis-vs-clis.adoc (TUI as a view over the core, TTY gate, every TUI action has a
  `--json` command, terminal restoration)
- irurueta docs: ai/cli-for-agents/security.adoc (argv discipline, `--`, allowlists, absolute paths and minimal
  environment, output sanitisation)
- irurueta docs: ai/cli-for-agents/streams-and-exit-codes.adoc, tty-colour-and-interactivity.adoc,
  machine-readable-output.adoc, safety-and-idempotency.adoc, configuration-and-auth.adoc,
  self-describing-help.adoc — https://www.irurueta.com/ai/cli-for-agents/index.html
- The Rust Programming Language, The Cargo Book, The rustdoc book, The Rust Reference, Rust API Guidelines
  (<https://rust-lang.github.io/api-guidelines/>), Clippy lint list (<https://rust-lang.github.io/rust-clippy/>)
- clap (<https://docs.rs/clap/4.6.7>), clap_complete, clap_mangen, ratatui (<https://docs.rs/ratatui/0.30.2>,
  <https://ratatui.rs>), anstream/anstyle, signal-hook, dialoguer, assert_cmd, predicates, insta, trycmd, tokio
  (<https://docs.rs/tokio>), cargo-llvm-cov (<https://github.com/taiki-e/cargo-llvm-cov>), NO_COLOR
  (<https://no-color.org>), clig.dev
