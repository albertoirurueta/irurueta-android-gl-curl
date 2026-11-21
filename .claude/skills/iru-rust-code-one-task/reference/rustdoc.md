# rustdoc

`missing_docs = "warn"` (scaffold lint set) plus CI's `-D warnings` make an undocumented `pub` item a build failure;
the group skill also runs `cargo doc --no-deps` with `RUSTDOCFLAGS="-D warnings"` and `cargo test --doc` through
`iru-rust-rustdoc`. Write the docs with the code — the group only backfills what was missed.

## What to document

- Every `pub` item reachable from outside its crate: types, functions, methods, traits, constants, **struct fields
  and enum variants** (each needs its own `///`), and modules (`//!` at the top of the module file).
- The crate root (`//!` in `lib.rs`): what the crate is, how `main.rs` uses it (`rustdoc::missing_crate_level_docs`).
- `pub(crate)` and private items don't need docs, but a short `///` on a non-obvious helper is welcome.
- **clap fields and variants**: the doc comment *is* the `--help` text (`clap.md`): user-facing, imperative, says when
  to use it, with an `Example:` line on commands. Implementation notes for maintainers go in a `//` comment above the
  attribute, not in the `///` (it would show up in `--help`).
- `#[derive(Serialize)]` output types: document each field's meaning — the docs are where the JSON schema lives.

## Shape

```rust
/// Builds the greeting for `name`.
///
/// # Errors
///
/// Returns [`GreetError::Empty`] for a blank name and [`GreetError::TooLong`] for one longer than
/// [`MAX_NAME_CHARS`] characters.
///
/// # Examples
///
/// ```
/// let g = democli::commands::greet::greeting("Ada")?;
/// assert_eq!(g.greeting, "Hello, Ada!");
/// # Ok::<(), democli::commands::greet::GreetError>(())
/// ```
pub fn greeting(name: &str) -> Result<Greeting, GreetError> {
```

- **Summary line**: one sentence, third person present ("Builds…", "Returns…"), ending with a period; it's what
  module listings and search show.
- **Sections**, in this order when they apply: `# Errors` (every function returning `Result` — which variants and
  when; clippy pedantic's `missing_errors_doc`), `# Panics` (`missing_panics_doc`), `# Safety` (any `unsafe fn` —
  but the scaffold forbids `unsafe`), `# Examples`.
- **Intra-doc links** in backticks inside brackets: ``[`GreetError::Empty`]``, ``[`crate::output::write_json`]``,
  ``[`std::io::IsTerminal`]``. Broken links fail `cargo doc` under `-D warnings`; a link from a public item to a private
  one triggers `rustdoc::private_intra_doc_links`.
- Identifiers in prose go in backticks (`serde_json`, `NO_COLOR`, `--format`) — clippy's `doc_markdown` flags bare
  `snake_case` words (verified).
- Bare URLs go in angle brackets `<https://…>` (`rustdoc::bare_urls`).

## Doctests

- Examples on a **library** target are compiled and run by `cargo test --doc` (merged into one binary per crate in
  edition 2024). Doc examples on items in a binary-only crate or in `main.rs` are never compiled — another reason the
  logic lives in `lib.rs`.
- Use `?` with a hidden `# Ok::<(), ErrorType>(())` last line; hide setup with `# `-prefixed lines.
- Hermetic: no network, no real terminal, no user config, no writes outside a temp dir. Use ```` ```no_run ```` for an
  example that would touch the outside world, ```` ```compile_fail ```` to document what the API forbids,
  ```` ```text ````/```` ```toml ````/```` ```console ```` for non-Rust blocks. Avoid ```` ```ignore ````.
- Give an example where it teaches something (the main entry points, a non-obvious API); a one-line getter doesn't
  need one.

## Don'ts

- No `#![allow(missing_docs)]` or `#[allow(missing_docs)]` to get a task through — document the item.
- No restating the signature ("Takes a `&str` and returns a `Result`"); say what it means and what can go wrong.
- No secrets, internal hostnames, or real customer data in examples.
