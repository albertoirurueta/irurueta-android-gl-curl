# Go development reference

Code agreements for implementing a task in a Go module — a command-line tool scaffolded by `iru-setup-go-cli` (Cobra,
optional Bubble Tea terminal UI) or any other Go library, service or CLI. These files exist so `iru-go-code-one-task`
can load **only** what the task in front of it needs instead of carrying every rule in context on every task.

## Read this much, and no more

Detect the project shape first (`iru-go-code-one-task` Step 0 — module, Go version, CLI framework, TUI, test
harness), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Effective Go / Code Review Comments conventions, package naming and `internal/`, small interfaces declared by the consumer, `context.Context` first, `gofmt`/`goimports`, what the linters enforce. |
| `errors.md` | **Always** for a task that returns, wraps, compares or maps errors — nearly every task. `%w` wrapping, `errors.Is`/`errors.As`/`errors.AsType`, sentinel vs typed errors, mapping to exit codes in exactly one place. |
| `testing.md` | The task writes or updates tests — nearly every task. Table tests, `t.Parallel` caveats, `t.TempDir`/`t.Setenv`/`t.Context`, golden files with `-update`, testscript e2e, Cobra command tests, teatest. |
| `cli-contract.md` | The repository is a CLI and the task adds or changes a command, flag, output, exit code, prompt, or error message. The language-agnostic contract (streams, exit codes 0–7/130/143, TTY/colour, JSON + error envelope, safety, config, self-description). |
| `cobra.md` | The task adds or changes a Cobra command, flag, argument validator, completion, flag group, or the generated CLI reference/man pages. |
| `output-and-terminal.md` | The task writes to stdout/stderr, adds a `--format`/JSON payload, styles output with Lip Gloss, prompts, detects a TTY, or handles signals/broken pipes. |
| `bubbletea.md` | The task adds or changes the Bubble Tea model (`internal/tui/`) or the command that starts it. |
| `ui-contract.md` | The module has a Bubble Tea TUI and the task adds or changes a screen, component, list or other UI. Verbatim copy of the catalog-wide UI contract (decomposition, composition, inputs/events, collections, UI tests, screenshots, accessibility). |
| `ui-implementation.md` | Together with `ui-contract.md`: the contract on Bubble Tea v2 — sub-models composed by the parent, typed intent messages, Bubbles `list`/`table`/`viewport` or a windowed slice, component/screen/flow tests incl. teatest, the `internal/tui/ui_screenshots_test.go` text-capture helper, the asserted accessibility checks and `.ui-quality.yml`. |
| `godoc.md` | The task adds or changes an exported identifier or a package. |
| `security.md` | The task runs a child process, opens/writes a path that came from an argument/env/config, reads a secret/token, does network I/O, or a `gosec` finding is involved. |
| `concurrency.md` | The task starts goroutines, uses channels/`sync`/`errgroup`, adds a timeout/cancellation, or touches signal handling. |

A task that only changes logic inside one existing function, with no new exported identifier and no new output,
needs `code-style.md` + `errors.md` + `testing.md` and nothing else. Don't read the rest speculatively. A non-CLI Go
module (no `cmd/`, no Cobra) never needs `cli-contract.md`, `cobra.md`, `output-and-terminal.md`, `bubbletea.md` or the two `ui-*.md` files.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the package you're editing
   consistently does something else (another CLI framework, testify instead of the standard library, a different
   exit-code table), follow it and note the divergence in the report rather than converting the file to this
   document's preference as a side effect of an unrelated task. The one exception: never *renumber* existing exit
   codes or rename JSON fields to match `cli-contract.md` — that breaks every script that calls the tool; report it.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They never
   license adding an abstraction, an interface, a dependency, or a "while I'm here" refactor the plan didn't ask for.
3. **This skill doesn't validate.** Beyond `go build ./...` + `go vet` on the touched packages (and `gofmt`/
   `goimports` on touched files), it never runs tests, coverage, `golangci-lint`, `govulncheck`, license headers, or a
   doc audit — the calling `iru-go-code-one-task-group` does all of that once for the whole task group. Write code
   that will pass those gates; don't run them here.

## Sources

Go guidance follows the official Go documentation and the libraries' own docs; the author's docs site has no Go
language pages. Every API named in these files was compiled, and the snippets that matter exercised, with Go 1.27.2
and the module versions below in a throwaway module outside this repository (see the skill's Verification notes);
where a page disagreed with the code, the code won.

- Effective Go: <https://go.dev/doc/effective_go>
- Go Code Review Comments: <https://go.dev/wiki/CodeReviewComments>; Go Test Comments: <https://go.dev/wiki/TestComments>
- Package names: <https://go.dev/blog/package-names>; Organizing a Go module (`internal/`, `cmd/`): <https://go.dev/doc/modules/layout>
- Go Doc Comments: <https://go.dev/doc/comment>
- Working with errors in Go 1.13: <https://go.dev/blog/go1.13-errors>; package `errors` (incl. `AsType`): <https://pkg.go.dev/errors>
- Package `testing`: <https://pkg.go.dev/testing>; Go fuzzing: <https://go.dev/doc/security/fuzz/>; coverage for integration tests (`GOCOVERDIR`): <https://go.dev/doc/build-cover>
- Data race detector: <https://go.dev/doc/articles/race_detector>; Go memory model: <https://go.dev/ref/mem>
- Package `context`: <https://pkg.go.dev/context>; `os/signal` (`NotifyContext`, SIGPIPE behaviour): <https://pkg.go.dev/os/signal>
- `os.Root` and traversal-resistant file APIs: <https://go.dev/blog/osroot>; `os/exec`: <https://pkg.go.dev/os/exec>
- Go security best practices and `govulncheck`: <https://go.dev/doc/security/best-practices>, <https://go.dev/doc/security/vuln/>
- `golang.org/x/term`: <https://pkg.go.dev/golang.org/x/term>; `golang.org/x/sync/errgroup`: <https://pkg.go.dev/golang.org/x/sync/errgroup>
- Cobra (v1.10.2): <https://cobra.dev/>, <https://pkg.go.dev/github.com/spf13/cobra>; `cobra/doc`: <https://pkg.go.dev/github.com/spf13/cobra/doc>
- Bubble Tea v2 (`charm.land/bubbletea/v2` v2.1.0): <https://github.com/charmbracelet/bubbletea>, <https://pkg.go.dev/charm.land/bubbletea/v2>;
  Lip Gloss v2 (`charm.land/lipgloss/v2` v2.0.6, `UPGRADE_GUIDE_V2.md`): <https://github.com/charmbracelet/lipgloss>;
  teatest v2: <https://pkg.go.dev/github.com/charmbracelet/x/exp/teatest/v2>
- testscript (`github.com/rogpeppe/go-internal` v1.16.0): <https://pkg.go.dev/github.com/rogpeppe/go-internal/testscript>
- golangci-lint v2 configuration and linters: <https://golangci-lint.run/docs/configuration/file/>, <https://golangci-lint.run/docs/linters/>; revive rules: <https://revive.run/r>; gosec rules: <https://github.com/securego/gosec#available-rules>
- NO_COLOR: <https://no-color.org/>; Command Line Interface Guidelines: <https://clig.dev/>
- irurueta docs: ai/cli-for-agents/frameworks-go-and-rust.adoc (Cobra `SilenceUsage`/`SilenceErrors`, `RunE`, typed exit
  errors, `cobra/doc`, Bubble Tea v2 TTY gating)
- irurueta docs: ai/cli-for-agents/testing-clis.adoc (golden `--json` tests, never-prompts-without-a-TTY, table-driven
  exit codes, testscript `TestMain`)
- irurueta docs: ai/cli-for-agents/tuis-vs-clis.adoc (the TUI is a view over the core; every TUI action has a `--json`
  command; exit 2 without a terminal)
- irurueta docs: ai/cli-for-agents/security.adoc (argv discipline, `--` before operands, allowlists, path roots)
- irurueta docs: ai/cli-for-agents/streams-and-exit-codes.adoc, tty-colour-and-interactivity.adoc,
  machine-readable-output.adoc, safety-and-idempotency.adoc, self-describing-help.adoc (summarised in `cli-contract.md`)
