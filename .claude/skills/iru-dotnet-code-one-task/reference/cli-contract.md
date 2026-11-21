# CLI contract — streams, exit codes, TTY, machine output, safety

The behavioural contract every command-line tool in this catalog follows, whatever its language. It serves three
callers from one implementation: a **person** at a terminal, a **script or CI job**, and an **AI agent** driving the
tool through a shell or MCP wrapper. Each rule below is a claim a test can verify (`testing.md` shows how per
language). When the repository's own code consistently does something else, follow the code and report the
divergence — never half-apply a rule.

## 1. Streams

- **stdout carries the result, and only the result**: the text a person asked for, or the JSON document in machine
  mode. `--help` and `--version` are results too: stdout, exit 0.
- **stderr carries everything else**: progress, spinners, log lines, warnings, hints, prompts, and error messages
  (in JSON mode the error itself is a JSON envelope — §4 — still on stderr).
- Redirecting stdout to a file must leave exactly the data in it. Never print banners, "Done!" lines, or colour
  resets on stdout in machine mode.
- `--quiet` suppresses informational stderr (never errors); `--verbose` (repeatable) adds diagnostics on stderr;
  `--log-format json` makes stderr diagnostics JSON. None of them changes stdout.
- **Broken pipe** (`tool … | head -n1`): stop writing and exit quietly — no panic/stack trace on stderr. (Rust:
  handle `ErrorKind::BrokenPipe` from writes; Go: treat `syscall.EPIPE` as a clean stop; .NET/Swift: catch the
  write `IOException`/`EPIPE` and exit.)
- Output is complete or clearly incomplete: write files atomically (temp file + rename); in `jsonl` mode every line
  is a complete record and a final summary line marks a clean finish.

## 2. Exit codes — one code per caller reaction

| Code | Name | Meaning → the caller's reaction |
|---:|---|---|
| 0 | `OK` | Success, including an idempotent no-op and a `--dry-run` plan. Continue. |
| 1 | `INTERNAL` | Unexpected failure (a bug). Report it; don't retry blindly. |
| 2 | `USAGE` | Bad flag/argument/unknown command. Fix the command line using the message. (Every parsing library used here — clap, Cobra (wired as below), swift-argument-parser, System.CommandLine — already uses 2 or can be told to.) |
| 3 | `VALIDATION` | Well-formed input that was rejected (or a `check`/`validate` command found problems). Read the problems list, fix the data. |
| 4 | `NOT_FOUND` | The named resource doesn't exist. Look it up (list command). |
| 5 | `CONFIRMATION_REQUIRED` | A destructive action needs confirmation and none was given. Not a failure — a question. The envelope carries the exact confirm command. |
| 6 | `RETRYABLE` | Transient failure (timeout, rate limit, 5xx). Wait, then retry with the same arguments (envelope: `retryable: true`, optional `retryAfterSeconds`). |
| 7 | `AUTH` | Missing/invalid credentials. The hint names the login command and the token environment variable. |
| 126/127 | (shell) | Not executable / not found — never emitted by the tool. |
| 128+N | (signal) | Interrupted: **130** after SIGINT, **143** after SIGTERM, emitted by the tool itself after cleanup. |

- Keep the numbers in one place in the code (an enum/const block named after this table) and **never renumber
  them** — scripts depend on them. A new failure kind maps to an existing reaction; finer detail goes in the
  envelope's `code`/`details`.
- The same failure gives the same code in every subcommand; a test produces each code the tool can emit.
- `sysexits.h` (64–78) is vocabulary only — don't emit those numbers.
- Signals: install SIGINT/SIGTERM handling, stop work, remove temp files, print one line to stderr, exit 130/143. A
  second Ctrl-C during cleanup exits immediately. A pending network call must not block Ctrl-C. A full-screen TUI
  restores the terminal before exiting.

## 3. TTY, colour and interactivity

Decide **per stream**:

| Stream is a TTY | Controls |
|---|---|
| stdout | colour/styling of results, tables sized to the terminal width |
| stderr | colour on errors/warnings, progress bars and spinners |
| stdin (and stderr) | prompts and confirmations |

- **Colour off** when any of: the stream isn't a TTY; `NO_COLOR` is set and non-empty; `TERM=dumb`; `--no-color`;
  machine mode (`--json`/`jsonl`) or `--quiet` on that stream. `FORCE_COLOR`/`CLICOLOR_FORCE` may force it on for
  humans; `--no-color` and `NO_COLOR` still win. Never convey meaning by colour alone — keep `error:`/`warning:`
  prefixes.
- **Progress/spinners** only when stderr is a TTY; otherwise at most an occasional plain line at `--verbose`.
- **Prompt only** when stdin and stderr are TTYs and neither `--no-input` nor `<TOOL>_NO_INPUT=1` nor `CI=true` is
  set. Otherwise **fail fast**: say what would have been asked, name the flag that supplies it, and exit 2 (a
  missing value) or 5 (a confirmation). Never read a secret from a prompt that echoes; prefer `--token-stdin`.
- `--yes`/`-y` answers ordinary confirmations; `--force` overrides a safety check. They are different flags and
  neither implies the other.
- A **TUI** (full-screen interface) is its own subcommand (e.g. `tool browse`/`tool ui`), starts only when stdin and
  stdout are TTYs (else exit 2 with a pointer to the non-interactive equivalent), and every function it offers has a
  non-interactive command with `--json`. The TUI is an addition for people, never the only way to do something.

## 4. Machine-readable output

- Every command that produces data accepts `--format text|json|jsonl` with `--json` as an alias for
  `--format json`. Text is the default for humans.
- **json**: exactly one document on stdout, an object carrying `schemaVersion` (integer, starts at 1) plus the
  payload; lists add `total`, `nextCursor` (or `null`) and `truncated`.
- **jsonl**: one complete object per line, ending with a summary line
  `{"schemaVersion":1,"type":"summary","total":N,"nextCursor":null}`.
- **Deterministic**: sorted object keys, stable array ordering (documented sort key), compact separators, no
  timestamps/absolute paths/colour unless asked for. Same input → same bytes (golden tests rely on it).
- **Evolution is additive**: new fields may appear; renaming/removing/retyping a field bumps `schemaVersion`.
- **Error envelope** — in JSON mode a failure writes exactly one document to **stderr**:

  ```json
  {"error":{"code":"NOT_FOUND","details":{},"exitCode":4,"hint":"Run `tool list` to see the available names.","message":"No item named 'x'.","retryable":false},"schemaVersion":1}
  ```

  `code` is a stable SCREAMING_SNAKE identifier, `exitCode` matches the process status, `hint` says how to fix it
  (never empty for codes 2–7), `retryable` is `true` only for code 6. Text mode prints `tool: <message>` and
  `hint: <hint>` lines on stderr instead. Secrets never appear in either.
- **Bounded output**: list commands take `--limit` (small default, e.g. 20) and `--cursor`; long text fields take
  `--max-chars`. Truncation is always signalled (`truncated: true`, a `nextCursor`, or a marker) — output that can
  flood an agent's context is a bug.
- Optional `--fields a,b,c` projection on list commands (unknown field → exit 2); the envelope fields stay.

## 5. Safety and idempotency

- **Read-only by default.** Reads are reads; writes are explicit commands (`tool apply`, not `tool show --write`).
- Every mutating command has **`--dry-run`**: exit 0, change nothing, print a structured plan (counts by kind of
  change, affected items, a plan id) using the same code path as the real run up to the write.
- A destructive command without confirmation: on a TTY ask (default No); otherwise exit **5** with an envelope whose
  `details` carry the plan and the exact `confirmCommand`. `--yes` confirms deliberately.
- Create-or-update operations are idempotent (re-running with the same input is a no-op exiting 0); operations that
  can't be accept `--idempotency-key`.
- **Validate operands locally** before any remote call or side effect; treat every argument as untrusted (it may come
  from a model). `--` ends option parsing; no subcommand or long flag is resolved by ambiguous prefix (turn off
  prefix/abbreviation matching where the library offers it).
- **Never build shell strings.** Run child processes with an argv array, an absolute or resolved program path, `--`
  before user-supplied operands, and a minimal environment when the child doesn't need the caller's.
- Paths from arguments are resolved and checked against the intended root before being read or written (no `..`
  escape, no following symlinks out of the root when that matters).
- Network/long operations take `--timeout` with a sensible default; a timeout exits 6.

## 6. Configuration and auth

- **Precedence**: flags → environment (`<TOOL>_*`) → project file (found by walking up from the working directory) →
  user config (XDG: `$XDG_CONFIG_HOME/<tool>/config.toml`, default `~/.config/<tool>/`; macOS
  `~/Library/Application Support/<tool>/` is acceptable when the tool already uses it; Windows `%APPDATA%\<tool>\`) →
  system config → built-in defaults. Every setting has a flag, an env var and a config key with the same name and
  meaning. `--verbose` logs on stderr which files were read and which layer supplied each value.
- Caches go to `$XDG_CACHE_HOME/<tool>`, state to `$XDG_STATE_HOME/<tool>`; never write to the working directory
  unless the command's purpose is to write there.
- **Credentials**: an OS credential store or a `0600` token file, plus `<TOOL>_TOKEN` for CI/headless use (record
  the env-var-vs-file trade-off in the docs); `login --token-stdin` for headless login. Tokens are never printed,
  logged, put in URLs, or echoed in error envelopes; an auth failure exits 7 and names the fix.

## 7. Self-description

- `--help` on every command: one line saying **when** to use it, the arguments, and at least one real example.
  Help goes to stdout and exits 0; a usage error prints a short usage line plus "see `tool <cmd> --help`" on stderr
  and exits 2 (never the whole help text on a runtime error).
- `--version` prints the version (stdout, exit 0); `tool version --json` (or `--version --json` where the library
  allows) adds build metadata (commit, target, build date only when reproducible builds allow).
- `tool completion <shell>` prints a generated completion script (bash, zsh, fish, PowerShell) — generated from the
  command tree, never hand-written.
- `tool schema` (or the library's native dump, e.g. swift-argument-parser's `--experimental-dump-help`) prints the
  machine-readable command tree: commands, arguments/flags with types and defaults, output `schemaVersion`, exit
  codes, and each command's safety (`readOnly`/`destructive`/`idempotent`) — the same shape an MCP wrapper maps to
  tool schemas.
- Man pages and the CLI reference in the docs site are **generated from the same command tree** (clap_mangen,
  cobra/doc, swift-argument-parser's `generate-manual` plugin, or the tool's own `schema` output rendered to
  AsciiDoc) — never maintained by hand next to the code.

## 8. Startup and footprint

- Fast startup is a feature (agents call tools in loops): no network, no config writes, no update checks on the
  default path; lazy-initialise expensive subsystems. Measure startup (`hyperfine '<tool> --version'`) when a task
  changes initialisation.
- No telemetry or update check without explicit opt-in, and never in machine mode.

## References

- Command Line Interface Guidelines (clig.dev) — Output, Errors, Interactivity, Arguments and flags
- irurueta docs: ai/cli-for-agents/ (streams-and-exit-codes, tty-colour-and-interactivity, machine-readable-output,
  agent-ergonomics, safety-and-idempotency, configuration-and-auth, self-describing-help, security, testing-clis,
  documenting-clis, agent-readiness-checklist) — https://www.irurueta.com/ai/cli-for-agents/index.html
- POSIX Utility Conventions; NO_COLOR (no-color.org); XDG Base Directory Specification; JSON Lines
