# Cargo and tool configuration templates

The workspace manifest (the tool's package plus the `xtask` member), the toolchain pin, formatter, cargo-deny and
the `cargo xtask` alias. Written for every `ui` value.

Conditional markers (`// >>> <condition>` … `// <<< <condition>` in Rust, `# >>> …` in TOML) and the `__Token__`
placeholders work as described in SKILL.md's "Reference files" section; the marker lines themselves are never
written. A heading suffixed `(condition)` is written only when the condition holds. Every file below was rendered
and verified on 2026-10-16 for `ui: none` and `ui: tui` (Rust 1.99.1; MSRV toolchains 1.85.0 for `ui: none`, 1.88.0 for `ui: tui`) — see SKILL.md
"Verification notes".

### `Cargo.toml`

`[workspace.lints]` holds the lint policy so `xtask` shares it; CI adds `-D warnings` on the command line (never `#![deny(warnings)]` in source). The `print_stdout`/`print_stderr` lints keep every write going through the injected writers of `Context` — the stdout-purity rule enforced by the compiler. With no license (`no-license`), `publish = false` keeps the crate off crates.io by accident.

```toml
[package]
name = "__package__"
version = "__Version__"
edition = "2024"
rust-version = "__Msrv__"
description = "__Description__"
# >>> license
license = "__License__"
# <<< license
# >>> no-license
publish = false
# <<< no-license
authors = ["__Authors__"]
# >>> repository
repository = "__RepositoryUrl__"
# <<< repository
keywords = ["cli"]
categories = ["command-line-utilities"]

[[bin]]
name = "__bin__"
path = "src/main.rs"

[dependencies]
anstyle = "__AnstyleVersion__"
clap = { version = "__ClapVersion__", features = ["derive", "env", "wrap_help"] }
clap_complete = "__ClapCompleteVersion__"
# >>> ui: tui
ratatui = { version = "__RatatuiVersion__", default-features = false, features = ["crossterm"] }
# <<< ui: tui
serde = { version = "__SerdeVersion__", features = ["derive"] }
serde_json = "__SerdeJsonVersion__"
signal-hook = "__SignalHookVersion__"
thiserror = "__ThiserrorVersion__"
toml = "__TomlVersion__"

[dev-dependencies]
assert_cmd = "__AssertCmdVersion__"
predicates = "__PredicatesVersion__"
snapbox = "__SnapboxVersion__"
tempfile = "__TempfileVersion__"

[workspace]
members = ["xtask"]
resolver = "3"

[workspace.lints.rust]
missing_docs = "warn"
unsafe_code = "forbid"

[workspace.lints.clippy]
pedantic = { level = "warn", priority = -1 }
dbg_macro = "warn"
print_stderr = "warn"
print_stdout = "warn"
# clap flag structs and the schema's safety record are naturally sets of booleans.
struct_excessive_bools = "allow"

[lints]
workspace = true

# Release binaries: smaller and faster at the cost of a slower release build.
[profile.release]
codegen-units = 1
lto = "fat"
strip = "symbols"
# `panic` stays "unwind" (the default) on purpose: `__crate__::main` catches a panic and exits 1 (INTERNAL) with
# a clean message, and the TUI's panic hook restores the terminal. "abort" would end with SIGABRT (134) instead.
panic = "unwind"
```

### `rust-toolchain.toml`

```toml
# The toolchain contributors and CI use (rustup reads this file). The minimum supported version (MSRV) is
# `rust-version` in Cargo.toml — CI checks it separately.
[toolchain]
channel = "stable"
components = ["rustfmt", "clippy", "llvm-tools-preview"]
profile = "minimal"
```

### `rustfmt.toml`

```toml
# Stable options only; everything else is the official Rust style.
style_edition = "2024"
max_width = 100
use_field_init_shorthand = true
```

### `deny.toml`

Licenses cover the verified dependency graph of both `ui` variants (`cargo deny check` → advisories, bans, licenses, sources ok). `multiple-versions = "warn"`: `ui: tui` pulls `signal-hook` 0.3 through crossterm next to the tool's 0.4, and two `syn` majors — warnings, not failures.

```toml
# cargo-deny: `cargo deny check` (advisories, licenses, bans, sources). https://embarkstudios.github.io/cargo-deny/
[graph]
all-features = true

[advisories]
# RustSec advisory database; unmaintained/unsound notices fail too. Ignore an advisory only with a reason:
# ignore = [{ id = "RUSTSEC-0000-0000", reason = "not reachable: …" }]
ignore = []

[licenses]
# Licenses dependencies may use (an `A OR B` expression passes when either is allowed).
allow = [
    "Apache-2.0",
    "Apache-2.0 WITH LLVM-exception",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "BSL-1.0",
    "ISC",
    "MIT",
    "Unicode-3.0",
    "Unicode-DFS-2016",
    "Zlib",
]
confidence-threshold = 0.9
# The list is deliberately broader than today's graph; don't warn about entries nothing uses yet.
unused-allowed-license = "allow"

[licenses.private]
# Unpublished workspace members (xtask, and the tool itself when `publish = false`) need no license.
ignore = true

[bans]
# Two versions of one crate compile twice and grow the binary — worth knowing, not worth failing over.
multiple-versions = "warn"
wildcards = "deny"
allow-wildcard-paths = true

[sources]
unknown-registry = "deny"
unknown-git = "deny"
allow-registry = ["https://github.com/rust-lang/crates.io-index"]
```

### `.cargo/config.toml`

```toml
[alias]
# `cargo xtask docs` — see xtask/src/main.rs.
xtask = "run --quiet --package xtask --"
```

### `xtask/Cargo.toml`

```toml
[package]
name = "xtask"
version = "0.0.0"
edition = "2024"
rust-version = "__Msrv__"
publish = false
description = "Development tasks for __bin__: `cargo xtask docs` generates man pages, completions and the CLI reference."

[dependencies]
__package__ = { path = ".." }
clap = { version = "__ClapVersion__", features = ["derive"] }
clap_complete = "__ClapCompleteVersion__"
clap_mangen = "__ClapMangenVersion__"
serde_json = "__SerdeJsonVersion__"

[lints]
workspace = true
```
