# Security

A CLI that agents drive executes text a model chose — possibly steered by a web page or an issue it read. Treat every
argument, environment variable, config value, and file name as untrusted (`cli-contract.md` §5). `gosec` (via
golangci-lint) and `govulncheck` run in the group's quality gate; write code they have nothing to say about, and when a
finding is a deliberate, safe construct, suppress it narrowly with the reason.

## Child processes — argv only

```go
// RunGit runs git with an argv array, a timeout and "--" before operands.
func RunGit(ctx context.Context, repo, path string) ([]byte, error) {
    git, err := exec.LookPath("git") // or an absolute path from trusted config
    if err != nil {
        return nil, fmt.Errorf("find git: %w", err)
    }
    ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
    defer cancel()
    cmd := exec.CommandContext(ctx, git, "-C", repo, "log", "--oneline", "--", path) //nolint:gosec // G204: argv array, resolved program, operands after "--".
    cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=" + os.Getenv("HOME")} // minimal environment
    cmd.WaitDelay = 5 * time.Second // don't hang on a child that ignores the kill
    out, err := cmd.Output()
    if err != nil {
        return nil, fmt.Errorf("git log: %w", err)
    }
    return out, nil
}
```

- **Never** `exec.Command("sh", "-c", s)`, `bash -c`, `cmd /c`, or `fmt.Sprintf` into a command line. No shell
  means no globbing, pipes, `~` or `$VAR` expansion — compose those in Go.
- `exec.CommandContext` (never plain `exec.Command` in code that has a context — the `noctx` linter flags it), a
  timeout, `WaitDelay`, and `--` before every user-supplied operand. One argument is one element: `"--component",
  name`, never `"--component " + name`.
- The **program** is fixed by code or trusted config, never by an argument. A user value never chooses a subcommand
  or flag name of the child (allowlist them in code). Reject values starting with `-` where a name is expected.
- gosec **G204** ("Subprocess launched with variable") fires on *any* non-constant argv, including the safe form above
  (verified with golangci-lint 2.15.0). Suppress only that line, only with `//nolint:gosec // G204: <why it's safe>`,
  and only when every rule above holds.
- Go's `os/exec` refuses to run a program found via a relative `PATH` entry (`exec.ErrDot`) — don't disable that.
- Cap what you read from a child (`io.LimitReader`) when its output is fed back to an agent.

## Paths from arguments — stay inside the root

```go
// ReadInRoot reads a user-supplied relative path that must stay inside root.
func ReadInRoot(root, userPath string) ([]byte, error) {
    if !filepath.IsLocal(userPath) { // rejects "", absolute paths, "..", and reserved Windows names
        return nil, &ValidationError{Field: "path", Reason: "must be a relative path inside the workspace"}
    }
    r, err := os.OpenRoot(root) // Go 1.24+: also blocks symlinks that escape the root
    if err != nil {
        return nil, fmt.Errorf("open root: %w", err)
    }
    defer func() { _ = r.Close() }()
    b, err := r.ReadFile(userPath)
    if err != nil {
        return nil, fmt.Errorf("read %s: %w", userPath, err)
    }
    return b, nil
}
```

Verified: `../etc/passwd`, `/etc/passwd`, `a/../../x` and a symlink to `/etc` inside the root all fail. Prefer
`os.Root`/`os.OpenInRoot` (and `Root.Create`, `Root.MkdirAll`, `Root.Remove`) over `filepath.Clean` + `strings.HasPrefix`
checks, which miss symlinks; when the `go` directive is below 1.24, use `filepath.IsLocal` (1.20+) plus
`filepath.EvalSymlinks` and a separator-aware prefix check, and say so. gosec G304 ("file inclusion via variable")
is answered by this, not by a blanket `nolint`.

## Files you create

- Permissions: `0o600` for files that may hold user data or tokens, `0o750`/`0o700` for directories (gosec G301/G302/G306
  flag looser modes); config and cache under `os.UserConfigDir()`/`os.UserCacheDir()` (or XDG), never the CWD.
- Atomic replace (temp file in the same directory + `Rename`), so an interrupted write never leaves half a file.
- `os.CreateTemp`/`os.MkdirTemp`, never predictable names in `/tmp`.

## Secrets

- Tokens come from `<BIN>_TOKEN`, a `0600` token file, an OS credential store, or `--token-stdin` — never a flag value
  (visible in `ps` and shell history) and never a prompt that echoes (`term.ReadPassword` if a prompt is needed).
- Never print, log, wrap into an error message, put in a URL, or include in the JSON envelope a token or a URL with
  credentials; redact in `%v` by giving the holding type a `String()`/`LogValue()` that masks it.
- No secrets in tests or `testdata/`; use obviously fake values.

## Network

- Every request carries the context (`http.NewRequestWithContext`; `noctx` flags `http.Get`) and a timeout; a
  timeout maps to exit 6 (`errors.md`). Close response bodies (`bodyclose` if enabled).
- Never disable TLS verification (`InsecureSkipVerify`, gosec G402) outside a test server.
- Validate operands locally before any remote call (`cli-contract.md` §5).

## Other gosec findings worth knowing

G101 (hard-coded credentials — usually a constant named `token`/`password`; rename or move it), G104/errcheck
(unchecked errors), G115 (integer overflow on conversion — clamp or check the range before converting), G401/G501
(weak crypto — use `crypto/sha256`+), G404 (`math/rand` for security — use `crypto/rand`), G110 (decompression bomb —
`io.CopyN` with a limit). Fix the code; a suppression needs a specific rule and a reason (`nolintlint` enforces both).

## Dependencies

Don't add modules casually: each is code that runs with the user's privileges. `govulncheck ./...` runs in the
group's quality gate; if it reports a reachable vulnerability in a module this task added or upgraded, that's this
task's to fix (upgrade to the fixed version) — report it, don't suppress it.
