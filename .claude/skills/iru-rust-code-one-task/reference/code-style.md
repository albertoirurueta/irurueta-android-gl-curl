# Rust code style

The defaults for new Rust code in this catalog. The surrounding crate wins where it consistently differs (README rule
1). rustfmt decides layout; this file decides everything rustfmt can't.

## Edition, toolchain, MSRV

- Read `edition` and `rust-version` from the package (or `[workspace.package]`), the channel from
  `rust-toolchain.toml`, and `msrv` from `clippy.toml` if present. Never use a language feature or std API newer
  than `rust-version` — clippy's `incompatible_msrv` lint (on by default) flags std APIs newer than it, but not
  syntax. Common traps: let chains (`if let Some(x) = a && x > 0`) need **1.88 and edition 2024**; `#[expect]` 1.81;
  `LazyLock`/`LazyCell` 1.80; `Option::is_none_or` 1.82; `std::io::pipe` 1.87; `std::io::IsTerminal` 1.70.
- Edition 2024 rules that bite when writing new code: `std::env::set_var`/`remove_var` are `unsafe` (and therefore
  unavailable under `unsafe_code = "forbid"` — read configuration once and pass it down; give a child process its
  environment with `Command::env`); `extern` blocks must be `unsafe extern`; `#[no_mangle]`/`#[export_name]` must be
  written `#[unsafe(no_mangle)]`; `gen` is a reserved keyword; return-position `impl Trait` captures every in-scope
  lifetime (narrow it with `+ use<'a, T>` only when a borrow error demands it); temporaries in an `if let` scrutinee
  and in a block's tail expression are dropped earlier than in 2021.
- Don't change `edition`, `rust-version`, `rust-toolchain.toml`, or `resolver` as part of a task — that's a plan-level
  decision (a blocker to report if the task can't be done without it).

## Where code goes

- **Logic in the library, `main.rs` thin.** In a binary package with `src/lib.rs`, `main.rs` only gathers process
  facts (args, which streams are terminals, environment facts), calls one `lib` entry point with injected
  writers, and converts its result into `std::process::ExitCode`. Integration tests can't import a binary-only crate,
  and code in `main.rs` is reachable only through slower end-to-end tests. If the crate is binary-only, don't create
  a `lib.rs` as a side effect — put new logic in a module with unit tests and say so in the report.
- **Modules by domain concept** (`commands/greet.rs`, `output.rs`, `config.rs`), not by kind of item (`structs.rs`,
  `traits.rs`). Follow the crate's existing `foo.rs` + `foo/` vs. `foo/mod.rs` convention.
- **Visibility**: private by default, `pub(crate)` for helpers shared across modules, `pub` only for the API the crate
  means to keep (in a CLI's lib, the items `main.rs`, tests in `tests/`, and the xtask need). Re-export a deliberate
  surface from the crate root rather than making every module public when the crate is a library others depend on.
- **Workspace**: a change belongs to the member that owns the concept; a shared helper moves to a shared member only
  when the task says so. `xtask/` is dev tooling (docs/man/completions generation), never runtime logic.

## Naming (RFC 430 / API Guidelines)

| Item | Convention | Example |
|---|---|---|
| Crates, modules, functions, methods, locals, fields | `snake_case` | `greet`, `write_json`, `no_input` |
| Types, traits, enum variants | `UpperCamelCase` (acronyms are one word) | `CliError`, `Exit::NotFound`, `HttpClient` |
| Constants, statics | `SCREAMING_SNAKE_CASE` | `SCHEMA_VERSION`, `MAX_NAME_CHARS` |
| Type parameters / lifetimes | short `UpperCamelCase` / short lowercase | `T`, `W: Write` / `'a` |

Conversions: `as_` is free and borrowed, `to_` allocates or is expensive, `into_` consumes `self`. Getters have no
`get_` prefix (`len()`, `selected()`). Test names describe behaviour (`rejects_empty_and_long_names`), not
`test_1`.

## Types and ownership

- Accept borrowed types (`&str`, `&[T]`, `&Path`, `impl AsRef<Path>`, `impl Into<String>` for constructors), return
  owned ones. Don't `.clone()` to quiet the borrow checker — shorten the borrow or restructure.
- Custom types instead of bool parameters (`Format::Json`, `Availability::IncludeLent`) and newtypes for validated
  values (`Isbn`, `BinName`) — parse once at the edge, then the type carries the guarantee.
- Derive eagerly: `Debug` on every type; `Clone, Copy, PartialEq, Eq, Hash, Default` where they make sense.
  `#[derive(Serialize)]` only on types that are part of a documented output (`rename_all = "camelCase"` to match the
  contract's JSON field names).
- Prefer iterators and combinators to index loops; `match` exhaustively on your own enums (no `_ =>` arm that would
  silently swallow a new variant — exit codes and output formats especially).
- `#[must_use]` on builder methods and pure functions whose result is easy to drop by mistake (clippy's
  `must_use_candidate` is allowed in the recommended lint set because it fires on nearly every getter).

## No panics on input

No `unwrap()`/`expect()`/indexing that can fail on user, file, environment, or network input, in library or binary
code. `expect("<invariant that makes this infallible>")` is acceptable only for true invariants and only where the
crate's lints allow it (the recommended set warns on both `unwrap_used` and `expect_used`; `clippy.toml`'s
`allow-unwrap-in-tests`/`allow-expect-in-tests` lift that inside `#[test]` functions and `#[cfg(test)]` modules — but
**not** inside helper functions of an integration-test file in `tests/` (verified); give such a file a
`#![allow(clippy::unwrap_used, reason = "...")]`). See `error-handling.md`.

## Lints

- The policy lives in `Cargo.toml` (`[lints]`, or `[workspace.lints]` + `lints.workspace = true` in each member) —
  never `#![deny(warnings)]` in source (a new toolchain lint would break every downstream build). CI denies warnings
  with `-D warnings` on the command line.
- Suppress a lint locally only when the task's code genuinely needs it, with `#[expect(lint, reason = "...")]` (warns
  when it stops firing) rather than `#[allow]`. Never add a crate-wide `allow` or touch `[lints]` to make a new
  warning disappear — that's a plan-level change.
- The lint set the scaffold writes, and that this reference's snippets were verified against (clippy 0.1.99, `cargo
  clippy --all-targets --all-features -- -D warnings` clean):

  ```toml
  [lints.rust]
  unsafe_code = "forbid"
  missing_docs = "warn"

  [lints.clippy]
  pedantic = { level = "warn", priority = -1 }
  must_use_candidate = "allow"       # fires on nearly every getter
  struct_excessive_bools = "allow"   # clap flag structs are bool-heavy by nature
  dbg_macro = "warn"
  exit = "warn"                      # std::process::exit skips destructors and buffered flushes
  print_stdout = "warn"              # write through the injected writer instead
  print_stderr = "warn"
  unwrap_used = "warn"
  expect_used = "warn"
  ```

  with `clippy.toml`: `allow-unwrap-in-tests = true`, `allow-expect-in-tests = true`, `allow-print-in-tests = true`.
  Pedantic lints that commonly fire on new code and are worth fixing rather than allowing: `doc_markdown` (put
  identifiers like `serde_json` in backticks in doc comments), `missing_errors_doc`/`missing_panics_doc` (add the
  `# Errors`/`# Panics` sections), `needless_pass_by_value`, `cast_possible_truncation` (use `u8::try_from`),
  `assert_is_empty` (write `assert_ne!(s, "")`, not `assert!(!s.is_empty())`), `uninlined_format_args`.

## Formatting and imports

- rustfmt with the crate's `rustfmt.toml` (default `max_width = 100`; `style_edition = "2024"` sorts imports with
  version sorting). Don't hand-align or add `#[rustfmt::skip]` except for a genuinely tabular literal.
- Import groups in this order, one blank line apart: `std`/`core`/`alloc`, external crates, then `crate::`/`super::`.
  One `use` per module path with braces (`use std::io::{self, Write};`). No glob imports except `use super::*;` in a
  test module and a crate's documented prelude.

## Dependencies

- **No new dependency, feature, or crate** unless the task explicitly calls for it — a blocker to report otherwise.
  When the task does, add it at the workspace level if the workspace uses `[workspace.dependencies]`, with a caret
  requirement of the current major.minor (`clap = { version = "4.6", features = ["derive"] }`), the smallest feature
  set (`default-features = false` when the defaults pull in what you don't use), and let `cargo check` update
  `Cargo.lock` (never edit `Cargo.lock` by hand).
- Prefer what the crate already uses: if it has `anyhow`, `owo-colors`, `indicatif`, `insta`, use those rather than
  introducing the equivalents named in these references.
