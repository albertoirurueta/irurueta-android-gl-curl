# Security

Treat every argument, environment variable, config value, file and network response as untrusted — for an
agent-driven CLI the arguments were chosen by a model that may have been steered by text it read (irurueta docs:
ai/cli-for-agents/security.adoc, `cli-contract.md` §5–§6). Every snippet below compiles under the recommended lint set
and has a passing test (Verification notes).

## No `unsafe`, no environment mutation

- `[lints.rust] unsafe_code = "forbid"` in the scaffold: a task never adds `unsafe`, `#[allow(unsafe_code)]`, or a
  dependency whose only purpose is to do something unsafe on its behalf. If a task truly needs FFI, that's a blocker
  to report.
- `std::env::set_var`/`remove_var` are `unsafe` in edition 2024 (data race with any thread reading the environment)
  and therefore unavailable: read the environment once at start-up into a struct; give a child process its
  environment with `Command::env`.

## Running processes: argv only

```rust
/// Runs `git log` for a user-supplied revision without a shell.
///
/// # Errors
///
/// Returns [`io::ErrorKind::InvalidInput`] for an operand that looks like an option, or the spawn
/// error.
pub fn git_log(git: &Path, repo: &Path, user_rev: &str) -> io::Result<Output> {
    if user_rev.starts_with('-') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "revision must not start with '-'",
        ));
    }
    Command::new(git) // a resolved absolute path, not a PATH lookup of user text
        .arg("-C")
        .arg(repo)
        .args(["log", "--oneline", "-n", "5"])
        .arg(user_rev) // one argument, never split, never interpreted by a shell
        .arg("--") // everything after is a path, not an option or revision
        .env_clear()
        .env("PATH", "/usr/bin:/bin")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .output()
}
```

- `std::process::Command` never invokes a shell; `Command::new("sh").arg("-c").arg(format!(...))` (or `cmd /C`)
  with any user-influenced text reintroduces injection — never do it. No shell means no globbing, pipes, `~` or
  `$VAR` expansion: compose those in Rust.
- The **program** is fixed by the code (a constant, a configured absolute path resolved once), never chosen by an
  argument; user values are **operands**, each one `.arg()`, after a `--` when the child supports it, and rejected
  when they start with `-` where an option isn't expected.
- Give the child a **minimal environment** (`env_clear()` + what it needs) when it doesn't need ours — our tokens and
  `LD_PRELOAD`-style variables stay out. On Windows keep `SystemRoot`.
- `stdin(Stdio::null())` unless the child needs input; cap what you read from its stdout (`.take(MAX)` on the pipe)
  and apply `--timeout` (kill the child; exit 6). Windows `.bat`/`.cmd` files run through `cmd.exe` and re-parse
  arguments — don't spawn them with user operands.
- Secrets go to children through the environment or stdin, never through arguments (visible in the process table).

## Paths from arguments and config

```rust
/// Resolves `user_path` against `root` and requires the result to stay inside `root`.
///
/// Both sides are canonicalized, so `..` segments and symlinks pointing out of the root are
/// caught. Only works for paths that exist; for a file about to be created, resolve its parent.
///
/// # Errors
///
/// [`PathError::Unresolvable`] if either path can't be canonicalized, [`PathError::OutsideRoot`]
/// if the resolved path escapes `root`.
pub fn resolve_within(root: &Path, user_path: &str) -> Result<PathBuf, PathError> {
    let unresolvable = |source| PathError::Unresolvable {
        path: user_path.to_owned(),
        source,
    };
    let root = root.canonicalize().map_err(unresolvable)?;
    let candidate = root.join(user_path).canonicalize().map_err(unresolvable)?;
    if candidate.starts_with(&root) {
        Ok(candidate)
    } else {
        Err(PathError::OutsideRoot(user_path.to_owned()))
    }
}
```

Verified: `docs/../..`, an absolute `/etc` (`Path::join` with an absolute path *replaces* the root), and a symlink to
`/` are all rejected; a missing path is `Unresolvable`. `Path::starts_with` compares components, so `/root2` doesn't
pass for `/root`. Map `OutsideRoot` to exit 3 (validation) or 2 (usage) as the command's contract says, and never echo
file contents in the error. Write files atomically (`tempfile::NamedTempFile::new_in(dir)` + `persist`) and only
where the command's purpose says (contract §6: caches in `$XDG_CACHE_HOME/<tool>`, never the working directory by
accident).

## Untrusted input

- Validate operands **before** any side effect or network call, with positive rules: length limits counted in
  characters (`chars().count()`), full-match allowlists for names (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}` — written as a
  character check, a regex only if the crate already has `regex`), numeric ranges in clap's `value_parser`.
- Bound what you read: `reader.take(MAX_BYTES)` on stdin, files and HTTP bodies; fail with exit 3 (or signal
  truncation) instead of buffering an unbounded input.
- Deserialize into typed structs with `#[serde(deny_unknown_fields)]` for config the user writes; never into
  `serde_json::Value` and then index blindly (`v["a"]["b"]` silently yields `Null`).
- Text from outside (file names, API responses, other tools' output) printed to a terminal can carry escape sequences
  that rewrite what a person sees: strip control characters before printing it in text mode
  (`text.chars().filter(|c| !c.is_control() || *c == '\n' || *c == '\t')` — removes ESC, BEL, etc.). In JSON mode
  serde escapes control characters already.

## Secrets

```rust
/// An API token. `Debug` never shows it, so `{:?}` in a log or panic can't leak it.
#[derive(Clone, PartialEq, Eq)]
pub struct Token(String);

impl fmt::Debug for Token {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Token(***)")
    }
}

/// Writes `token` to `path`, readable by the owner only on Unix, replacing any previous file.
///
/// # Errors
///
/// Returns the I/O error.
pub fn write_token_file(path: &Path, token: &Token) -> io::Result<()> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(token.expose().as_bytes())?;
    file.sync_all()
}
```

- Tokens come from `<BIN>_TOKEN`, `--token-stdin`, or a `0600` token file / the OS credential store — never a
  `--token <value>` flag (process table, shell history) and never an echoing prompt.
- A newtype with a redacting `Debug` (and no `Display`, no `Serialize`) keeps them out of `{:?}`, logs, panics, the
  JSON envelope, and `--verbose` output. Use `secrecy` only if the crate already depends on it.
- Never put a secret in a URL query string, an error message, a hint, a test fixture, or a doc example. Auth
  failures exit 7 with a hint naming the login command and the token variable — not the token.

## Dependencies and supply chain

No new crate unless the task says so (`code-style.md`); the group's quality gate runs `cargo deny check`
(advisories, licenses, bans, sources) when `deny.toml` exists, and `Cargo.lock` is committed for binaries. Never
add a `git =` or path dependency outside the workspace, or a `[patch]` section, as part of a task.
