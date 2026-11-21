# Testing

Standard library `testing` first. Use testify/go-cmp/is only when the package already does (README rule 1); never add
an assertion library for one task. Tests sit next to the code (`foo_test.go`), in the same package for white-box
tests or `package foo_test` for black-box tests of the exported API — follow the neighbouring files.

## Table tests

```go
func TestExitCodes(t *testing.T) {
    t.Parallel()
    tests := []struct {
        name string
        args []string
        want ExitCode
    }{
        {"default greeting", []string{"greet"}, ExitOK},
        {"unknown command", []string{"nope"}, ExitUsage},
        {"prefix is not a command", []string{"gre"}, ExitUsage},
        {"exclusive flags", []string{"greet", "--json", "--format", "json"}, ExitUsage},
        {"empty name", []string{"greet", "--", " "}, ExitValidation},
        {"browse without tty", []string{"browse"}, ExitUsage},
    }
    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            t.Parallel()
            code, _, stderr := runCLI(t, nil, tt.args...)
            if ExitCode(code) != tt.want {
                t.Fatalf("exit = %d, want %d (stderr %q)", code, tt.want, stderr)
            }
        })
    }
}
```

- Failure messages say `got … want …` with the input; `t.Fatalf` when continuing makes no sense, `t.Errorf` otherwise.
- Helpers call `t.Helper()` first and take `t` as the first parameter (`thelper` linter).
- Since Go 1.22 each loop iteration has its own `tt`; don't add `tt := tt`.
- `go-cmp`'s `cmp.Diff` only if the module already depends on it; otherwise compare fields or marshalled JSON.

## `t.Parallel` caveats

- Mark a test and its subtests parallel when they share no mutable state (`tparallel` flags half-parallel trees).
- **Never with `t.Setenv` or `t.Chdir`** — both panic in a parallel test (they change process-wide state). Prefer
  injecting an env lookup (`getenv func(string) string`) or a working directory into the code under test, so the test
  stays parallel; use `t.Setenv` only for code that reads `os.Getenv` directly.
- No package-level mutable state (globals, Cobra command singletons, `flag.CommandLine`) touched by parallel tests:
  build a fresh command tree per test (the scaffold's `newRootCommand(o, &started)` does).
- Parallel tests + `-race` is how the group skill finds data races — write tests that would expose one.

## Scaffolding helpers

- `t.TempDir()` for files (auto-removed), `t.Context()` (Go 1.24+, cancelled when the test ends) instead of
  `context.Background()`, `t.Cleanup` for teardown, `t.Chdir` (Go 1.24+) instead of `os.Chdir`.
- `testing/fstest.MapFS` for code that takes an `fs.FS`; `httptest.NewServer` for HTTP clients; `io.Writer`
  buffers for output. Never reach the real network, the real home directory, or real credentials.
- `testing.Short()` to skip slow tests under `go test -short`.

## Golden files with an `-update` flag

```go
var update = flag.Bool("update", false, "rewrite golden files in testdata/")

func TestGreetJSONGolden(t *testing.T) {
    t.Parallel()
    code, stdout, stderr := runCLI(t, nil, "greet", "--json", "--", "Ada")
    if code != 0 || stderr != "" {
        t.Fatalf("exit %d, stderr %q", code, stderr)
    }
    golden := filepath.Join("testdata", "greet-ada.golden.json")
    if *update {
        if err := os.WriteFile(golden, []byte(stdout), 0o600); err != nil {
            t.Fatal(err)
        }
    }
    want, err := os.ReadFile(golden)
    if err != nil {
        t.Fatalf("read golden (run with -update to create it): %v", err)
    }
    if stdout != string(want) {
        t.Errorf("stdout mismatch\n got: %s\nwant: %s", stdout, want)
    }
}
```

Create/refresh with `go test ./internal/cli -run TestGreetJSONGolden -update`, **read the diff**, and commit the
golden file in the same change — a changed golden file is an interface change (`schemaVersion` policy,
`cli-contract.md` §4). Compare bytes for JSON (determinism is part of the contract); `testdata/` is ignored by the
go tool, so it's the right home.

## CLI commands in-process (Cobra)

Run the real command tree with injected streams, args and environment — milliseconds, and it counts toward coverage.
In an `iru-setup-go-cli` scaffold the helper already exists (`internal/cli/helpers_test.go`):
`app, out, errOut := newTestApp(map[string]string{"NO_COLOR": "1"})` gives an `App` with empty stdin, captured
stdout/stderr, only the given environment, and `IsTerminal` returning false; set `app.IsTerminal = func(any) bool {
return true }` to exercise the TTY branches (colour, prompts, the TUI gate), then `code := app.Run(t.Context(), args)`.
Elsewhere, the equivalent is a small helper like:

```go
func runCLI(t *testing.T, env map[string]string, args ...string) (code int, stdout, stderr string) {
    t.Helper()
    var out, errOut bytes.Buffer
    o := &options{in: strings.NewReader(""), out: &out, errOut: &errOut,
        getenv: func(k string) string { return env[k] }} // a developer's NO_COLOR/CI can't leak in
    code = run(t.Context(), o, args)
    return code, out.String(), errOut.String()
}
```

When a repository tests a bare `*cobra.Command` instead: `cmd.SetArgs(args)`, `cmd.SetOut(&out)`, `cmd.SetErr(&errOut)`,
`cmd.SetIn(r)`, `err := cmd.ExecuteContext(t.Context())` — and the command must write through `cmd.OutOrStdout()`/
`cmd.ErrOrStderr()`, never `os.Stdout`/`fmt.Println`, or the capture sees nothing.

What every CLI change asserts (`cli-contract.md`): the exit code; stdout holds only the result (empty on failure);
stderr empty on success in machine mode; the JSON envelope on failure (`code`, `exitCode`, non-empty `hint`,
`schemaVersion`); no `\x1b` bytes when not a TTY / `NO_COLOR` set / `TERM=dumb`; no prompt without a TTY (exit 2 or
5 naming the flag); deterministic output (run twice, compare bytes).

## End-to-end with testscript

`github.com/rogpeppe/go-internal/testscript` runs `testdata/script/*.txtar` scripts against the real `main`, each in a
fresh temp directory with a minimal environment. `testscript.Main` (go-internal v1.14+; the older `RunMain` with
`func() int` commands is deprecated) re-executes the test binary as the named command, so no `go build` is needed:

```go
// cmd/<bin>/main_test.go
func TestMain(m *testing.M) {
    testscript.Main(m, map[string]func(){"greeter": main})
}

func TestScripts(t *testing.T) {
    t.Parallel()
    testscript.Run(t, testscript.Params{Dir: "../../testdata/script"})
}
```

```
# testdata/script/contract.txtar
exec greeter --version
stdout '^0\.1\.0-dev$'
! stderr .

! exec greeter nope
! stdout .
stderr '^greeter: unknown command'

exec greeter greet --json -- Ada
cmp stdout want-greet.json

! exec greeter greet --json -- ''
! stdout .
stderr '"exitCode":3'

env NO_COLOR=1
exec greeter greet Ada
! stdout '\x1b'

-- want-greet.json --
{"greeting":"Hello, Ada!","name":"Ada","schemaVersion":1}
```

- `! exec` asserts *some* non-zero status. The scaffold registers a custom `status` command (`Params.Cmds`) that pins
  the exact code — `status 3 exec greeter greet ''` — implemented with `ts.Exec` and `*exec.ExitError.ExitCode()`
  (verified with go-internal v1.16.0: a wrong code fails as `FAIL: codes.txtar:2: exit status 3, want 4`). Without it,
  pin the code through the envelope's `exitCode` (as above) or an in-process table test.
- The scaffold also sets `RequireExplicitExec: true` (every program call is spelled `exec …`) and
  `UpdateScripts: *update`, so `go test ./cmd/<bin> -run TestScripts -update` rewrites the expected `-- file --`
  sections of the scripts in place — review that diff like a golden-file diff. Stdin is empty and not a TTY, so scripts are the natural "never prompts" check.
- Find the repository's `TestMain` (`grep -rn "testscript.Main" --include=*_test.go`) and its `Dir`; add a new
  `.txtar` file per command or behaviour rather than growing one script.
- Verified: with go-internal v1.16.0, statements executed by the re-executed command count in
  `go test -coverprofile -coverpkg=./...` — but in-process tests are still the primary coverage source (faster,
  and they pin exact codes).
- testscript needs no `.integration-test.lock`: no containers, no shared ports.

## Bubble Tea (teatest and pure Update tests)

Test `Update` directly first — it's a pure function (`bubbletea.md`):

```go
func TestUpdateMovesAndPicks(t *testing.T) {
    t.Parallel()
    var m tea.Model = New([]string{"Ada", "Grace"})
    m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyDown})
    m, _ = m.Update(tea.KeyPressMsg{Code: tea.KeyEnter})
    if got := m.(Model).Chosen(); got != "Grace" {
        t.Errorf("chosen = %q, want Grace", got)
    }
}
```

A quit is asserted by running the returned command: `_, cmd := m.Update(tea.KeyPressMsg{Code: 'q', Text: "q"})`,
then `cmd().(tea.QuitMsg)`. Then one or two full-program tests with teatest v2
(`github.com/charmbracelet/x/exp/teatest/v2`, which imports `charm.land/bubbletea/v2`; pseudo-versioned — resolve
`@latest` live):

```go
tm := teatest.NewTestModel(t, New([]string{"Ada", "Grace"}), teatest.WithInitialTermSize(40, 10))
teatest.WaitFor(t, tm.Output(), func(b []byte) bool { return bytes.Contains(b, []byte("Grace")) },
    teatest.WithDuration(3*time.Second))
tm.Send(tea.KeyPressMsg{Code: tea.KeyDown})
tm.Send(tea.KeyPressMsg{Code: tea.KeyEnter})
final := tm.FinalModel(t, teatest.WithFinalTimeout(3*time.Second))
```

For a screen or component the task added or changed, the UI tests of `ui-implementation.md` §4–§6 come on top:
every visual state with minimal/typical/many-items/long-content and right-to-left fixtures, each view captured
through `internal/tui/ui_screenshots_test.go` as `.ui-screenshots/<kind>/<Id>/<state>--<direction>--<theme>.txt`
(styles stripped; evidence, gitignored, cleared by `iru-go-test` at the start of a full run — never compared), and the
asserted accessibility checks at the `.ui-quality.yml` level.

`teatest.RequireEqualOutput(t, out)` compares against `testdata/<TestName>.golden` and is refreshed with
`go test -update` — teatest registers that flag itself, so a package using teatest must not declare its own
`-update` flag (duplicate flag panic); reuse teatest's or keep golden helpers in another package.

## Container-backed tests

Tests that need Docker (testcontainers-go, a real database) carry the build constraint `//go:build integration` in
their first line, so `go test ./...` never starts containers; the group skill runs them with `tags: integration`
behind the repository-wide lock. Never write one without the tag, never run it from this skill.

## Fuzzing and benchmarks

A parser/validator taking untrusted input is a good `FuzzXxx(f *testing.F)` target (`f.Add` seeds, the corpus in
`testdata/fuzz/`); write one only when the task asks or adds such a parser. `go test` runs the seed corpus as normal
tests. Benchmarks (`BenchmarkXxx(b *testing.B)` with `for b.Loop()`, Go 1.24+) only when the task is about performance.
