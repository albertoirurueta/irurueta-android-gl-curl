# Cobra (github.com/spf13/cobra v1.10.x)

How commands are written in a Cobra CLI scaffolded by `iru-setup-go-cli` (and how to read one that wasn't). The
contract they implement is `cli-contract.md`; output and terminal rules are `output-and-terminal.md`.

## The root command — set once, don't undo

**In an `iru-setup-go-cli` scaffold** the tree is built by `(*App).Command()` in `internal/cli/root.go`: the root sets
`SilenceUsage`/`SilenceErrors`, `PersistentPreRunE: a.setup` (configuration + logger), a flag-error func returning
`usageError(cmd, err)`, the global flags, then `finishTree(root)` — which adds Cobra's `help`/`completion` commands up
front, gives every command default read-only annotations, makes a command group reject unknown subcommands, and wraps
every `RunE` (`wrapRunE`) so a non-`*Error` becomes an internal error. A new command is a `newXCommand(a *App)`
constructor added to `root.AddCommand(...)` there, using `Annotations: safety(readOnly, destructive, idempotent)` and
writing its result with `a.emit(text, record)`; don't bypass `finishTree`.

In a repository without that structure, the minimum that implements the contract (verified with Cobra v1.10.2):

```go
root := &cobra.Command{
    Use:           "greeter",
    Short:         "Greet people from scripts, agents and terminals.",
    Version:       version.Version,
    SilenceUsage:  true, // no usage dump on runtime errors (noise in an agent's context)
    SilenceErrors: true, // cli.Main prints errors itself, once, on stderr
    PersistentPreRunE: func(cmd *cobra.Command, _ []string) error {
        // Cobra validates required flags and flag groups only AFTER the pre-run hooks
        // (verified in cobra v1.10.2's execute order), so check them here while their
        // errors still classify as usage errors (exit 2), not internal ones.
        if err := cmd.ValidateRequiredFlags(); err != nil {
            return err
        }
        if err := cmd.ValidateFlagGroups(); err != nil {
            return err
        }
        *started = true // from here on, a plain error is an internal error (exit 1)
        return nil
    },
}
root.SetVersionTemplate("{{.Version}}\n") // `--version` on stdout, exit 0 (the scaffold prints "{{.Name}} {{.Version}}")
root.SetFlagErrorFunc(func(cmd *cobra.Command, err error) error {
    return usageError(err.Error(), "See `"+cmd.CommandPath()+" --help`.", err)
})
```

- **Never** set `SilenceUsage`/`SilenceErrors` back to false on a subcommand, and never print errors inside `RunE` —
  return them; `cli.Main` classifies (`errors.md`) and prints exactly once.
- **Don't define `PersistentPreRunE` on a subcommand**: Cobra runs only the nearest one, so the root's hook (the
  scaffold's `a.setup`, or the `started` flag above) would be skipped. If a subcommand needs setup, use `PreRunE`, or set
  `cobra.EnableTraverseRunHooks = true` once in the root package (then every ancestor's hook runs).
- `cobra.EnablePrefixMatching` defaults to `false` — keep it so (`gre` must not resolve to `greet`; the contract
  forbids ambiguous prefixes). Cobra still *suggests* `greet` in the error ("Did you mean this?"), which is fine.
  pflag has no flag-abbreviation matching. Don't add command `Aliases` that are prefixes of other commands.
- Root without `Run`: `greeter` alone prints help and exits 0; `greeter nope` is an unknown-command error (exit 2).

## A subcommand

The generic shape (in the scaffold, `o *options` is `a *App`, and the text/JSON branch is one `a.emit(text, record)` call):

```go
func newGreetCommand(o *options) *cobra.Command {
    return &cobra.Command{
        Use:         "greet [NAME]",
        Short:       "Print a greeting; use it to check the tool works.", // says WHEN to use it
        Example:     "  greeter greet Ada\n  greeter greet --json -- Ada",   // at least one real example
        Args:        cobra.MaximumNArgs(1),
        Annotations: map[string]string{"readOnly": "true", "idempotent": "true"},
        RunE: func(cmd *cobra.Command, args []string) error {
            name := "World"
            if len(args) == 1 {
                name = args[0]
            }
            g, err := Greet(name) // logic in a plain function, unit-tested on its own
            if err != nil {
                return err
            }
            if f, _ := o.outputFormat(); f != FormatText {
                return writeJSON(cmd.OutOrStdout(), g)
            }
            _, err = fmt.Fprintln(cmd.OutOrStdout(), g.Greeting)
            return err
        },
    }
}
```

- `RunE`, never `Run` (a `Run` can't return an error, so it would have to print and exit itself).
- One constructor function per command (`newXCommand(a *App) *cobra.Command` in the scaffold) registered with
  `root.AddCommand(...)` where the tree is built — no package-level `var xCmd = &cobra.Command{…}` + `init()` (global state breaks parallel
  tests; the `cobra-cli` generator's style is the thing to *not* copy into a scaffolded repository).
- Write through `cmd.OutOrStdout()`/`cmd.ErrOrStderr()` and read `cmd.InOrStdin()`; get the context from
  `cmd.Context()` (set by `ExecuteContext` — it carries the signal cancellation).
- `Annotations` feed `<bin> schema` (`readOnly`, `destructive`, `idempotent`, plus an interactive-only marker for a
  TUI command) — set them on every new command (`safety(…)` in the scaffold); a mutating command must override the
  read-only default and say whether it is idempotent.
- Keep `Short` to one line starting with what it does; put detail in `Long`; `Example` lines are indented two spaces
  and runnable as written.

## Arguments

Validate positionals with `Args` so mistakes are usage errors before `RunE` runs: `cobra.NoArgs`,
`cobra.ExactArgs(n)`, `cobra.MinimumNArgs(n)`, `cobra.MaximumNArgs(n)`, `cobra.RangeArgs(a, b)`,
`cobra.MatchAll(cobra.ExactArgs(1), cobra.OnlyValidArgs)` with `ValidArgs`. Content validation (empty, too long,
bad characters) is a **validation** error (exit 3) raised in `RunE`/the domain function, not in `Args`. Help text and
docs show `--` before free-text operands (`greet --json -- Ada`): pflag stops flag parsing at `--`, so a NAME of
`--help` is data (verified).

## Flags

- Global flags (`--format`, `--json`, `--no-color`, `--no-input`, `--quiet/-q`, `--verbose/-v` count,
  `--log-format`) are `root.PersistentFlags()` bound to the shared `options` struct. Don't redefine them on a
  subcommand. Cobra's auto `--version` gets no `-v` shorthand because `-v` is taken — intended.
- Command flags on `cmd.Flags()`, bound to local variables or an options struct captured by the closure. Flag names
  are kebab-case; every flag has a usage string ending in a period.
- Typed flags (`IntVar`, `DurationVar`, `StringSliceVar`, `CountVarP`) instead of parsing strings yourself; for an
  enum, a `string` flag validated in `PersistentPreRunE`/`RunE` returning a usage error that lists the allowed values
  (and `cmd.RegisterFlagCompletionFunc` for completion).
- Each setting that the contract says has an env var (`<BIN>_*`) and a config key reads them in one place (the
  scaffold's `config.go`), with flags winning — not ad hoc `os.Getenv` in commands.
- `--yes/-y` and `--force` are different flags; `--dry-run` on every mutating command (`cli-contract.md` §5).

### Flag groups and required flags

```go
cmd.MarkFlagsOneRequired("id", "all")          // at least one
cmd.MarkFlagsMutuallyExclusive("id", "all")     // not both
cmd.MarkFlagsMutuallyExclusive("yes", "dry-run")
cmd.MarkFlagsRequiredTogether("user", "password")
_ = cmd.MarkFlagRequired("component")           // prefer a positional or a group when it reads better
```

These errors surface after the pre-run hooks, outside `RunE`: in the scaffold they reach `exitCode` as non-`*Error`s
and become usage errors (2); with the `started` pattern the root hook must validate them early (above).
A test row per group belongs in the exit-code table.

## Completion and docs generated from the tree

- Keep Cobra's built-in `completion` command (bash, zsh, fish, powershell); don't set
  `CompletionOptions.DisableDefaultCmd`. Add `ValidArgsFunction`/`RegisterFlagCompletionFunc` for dynamic values;
  completion functions must not hit the network or prompt.
- `cobra/doc` renders the reference from the same tree (`tools/gendocs` in the scaffold, run with
  `go run ./tools/gendocs`): set `root.DisableAutoGenTag = true` for reproducible output, and **create the output directories
  first** — `doc.GenManTree`/`GenMarkdownTree` don't (verified: `open …/man/tool-delete.1: no such file or
  directory`).

  ```go
  root.DisableAutoGenTag = true
  for _, sub := range []string{"man", "md"} {
      if err := os.MkdirAll(filepath.Join(dir, sub), 0o750); err != nil {
          return fmt.Errorf("mkdir: %w", err)
      }
  }
  if err := doc.GenManTree(root, &doc.GenManHeader{Title: "GREETER", Section: "1"}, filepath.Join(dir, "man")); err != nil {
      return fmt.Errorf("man: %w", err)
  }
  ```

  A task that adds or changes a command re-runs the generator (the command in the repository's README/Makefile/
  `go:generate` line) only if the generated reference is committed; otherwise CI regenerates it — say which in the
  report.
- `greeter schema` walks the tree too (`c.Commands()` is sorted by name; `NonInheritedFlags().VisitAll`); a new command
  appears there automatically — add a test assertion that it does, with its annotations.
