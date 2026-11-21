# Go code style

Defaults for new Go code. The surrounding package wins when it consistently differs (README rule 1).

## Formatting is not a choice

- Every touched `.go` file is `gofmt`-clean, and imports are grouped by `goimports`: standard library, a blank line,
  third-party, and — when `.golangci.yml` sets `goimports.local-prefixes` — a final group for this module's own
  packages. Run `gofmt -w <files>` (and `goimports -w <files>` when installed, or `golangci-lint fmt <files>` when the
  repository uses golangci-lint v2 formatters) on the files you touched — never on the whole tree.
- No hand-aligned comments or columns; `gofmt` owns whitespace. Struct tags are aligned by `gofmt` automatically.

## Packages and layout

- Package names are short, lower-case, single words, no underscores or `mixedCaps`; the name is the last path
  element. Don't stutter: `cli.Main`, not `cli.CLIMain`; `version.Version` is the accepted exception.
- No `util`, `common`, `helpers`, `misc` packages — name a package for what it provides.
- `cmd/<bin>/main.go` stays thin: build a context, call one function, `os.Exit` with its result. Logic lives under
  `internal/` (unimportable from other modules) so it can be unit-tested and covered.
- A new package needs a reason the task states; adding a file to an existing package is the default.
- `internal/version` holds `Version`/`Commit` as `var`s (not `const`) so release builds set them with
  `-ldflags "-X <module>/internal/version.Version=1.2.3"`.

## Names

- `MixedCaps`/`mixedCaps`, never underscores. Initialisms keep one case: `ID`, `URL`, `HTTP`, `JSON`, `userID`,
  `ServeHTTP` (revive `var-naming` enforces it).
- Receivers are one or two letters, consistent across a type's methods, never `self`/`this`.
- Getters have no `Get` prefix (`Chosen()`, not `GetChosen()`); setters are `SetX`.
- Error variables are `ErrX`; error types end in `Error` (`errname` linter).
- Short names for short scopes (`i`, `b`, `w`); descriptive names for package-level identifiers.

## Interfaces

- Declare an interface **where it is consumed**, with only the methods the consumer calls (often one). Return
  concrete types from constructors ("accept interfaces, return structs").
- Don't create an interface "for mocking" with a single implementation unless a test in this task needs the seam;
  an `io.Writer`, `fs.FS`, or a function field is often a smaller seam than a new interface.
- Standard interfaces first: `io.Reader`/`io.Writer`, `fmt.Stringer`, `fs.FS`, `encoding.TextUnmarshaler`.

## Functions and control flow

- `ctx context.Context` is the **first parameter** of anything that blocks, does I/O, or calls something that might;
  never store a context in a struct (revive `context-as-argument`). Cobra commands get it from `cmd.Context()`.
- Handle errors first and return early; keep the happy path unindented. No `else` after a `return`.
- Return `error` last. Don't return an unexported type from an exported function (revive `unexported-return`).
- Zero values should be useful; prefer a struct literal or a small constructor over a builder.
- Use the standard library's newer helpers when `go.mod`'s `go` line allows them: `min`/`max`, `slices`, `maps`,
  `strings.Cut`, `errors.AsType` (Go 1.26+), `t.Context()`/`t.Chdir()` (Go 1.24+), `os.Root` (Go 1.24+), range over
  integers and functions. Never use an API newer than the `go` directive — `go vet` and the build will reject it.
- `any` instead of `interface{}`. Generics only when two or more concrete types need the same code now.
- `panic` only for programmer errors that can't happen (an impossible `switch` default in an `init`-time table);
  never for input, I/O, or user errors.

## Dependencies

- A new module dependency is a decision the task must state. If it does: `go get <module>@<version>` (resolve the
  current version live — `go list -m -versions <module>` or `https://proxy.golang.org/<module>/@latest`), then
  `go mod tidy`, and commit `go.mod` + `go.sum` together. Otherwise it is a blocker (SKILL.md Step 6).
- Major versions ≥ 2 are different import paths (`.../v2`); Bubble Tea and Lip Gloss v2 live under `charm.land/…/v2`.
- Never edit `go.sum` by hand; never add a `replace` directive except as the task says.
- `//go:generate` directives: if the task changes a generator's input, run that `go generate` line for the touched
  package only and commit the output; never hand-edit a file with a `// Code generated … DO NOT EDIT.` header.

## What the linters enforce (golangci-lint v2, the recommended set)

`standard` (errcheck, govet, ineffassign, staticcheck, unused) + revive (`exported`, `package-comments`,
`unexported-return`, `error-return`, `error-naming`, `context-as-argument`, `var-naming`), godot, gosec, errorlint,
noctx, nolintlint (explanation + specific linter required), misspell, unconvert, unparam, usestdlibvars, gocritic,
thelper, tparallel, errname; formatters gofmt + goimports. Code written to these files passes them; a
`//nolint:<linter> // <reason>` is acceptable only with a specific linter and a real reason (gosec G204 on an argv
`exec.Command` is the typical one — `security.md`).

The verified configuration (golangci-lint 2.15.0, `golangci-lint config verify` clean) — the repository's own
`.golangci.yml` wins; this is what to propose when there is none, never something to drop into a repository from a
coding task:

```yaml
version: "2"
run:
  tests: true
linters:
  default: standard            # errcheck, govet, ineffassign, staticcheck, unused
  enable: [revive, godot, gosec, errorlint, noctx, nolintlint, misspell, unconvert, unparam,
           usestdlibvars, gocritic, thelper, tparallel, errname]
  settings:
    revive:
      rules:
        - name: exported
        - name: package-comments
        - name: unexported-return
        - name: error-return
        - name: error-naming
        - name: context-as-argument
        - name: var-naming
    godot:
      scope: declarations
    nolintlint:
      require-explanation: true
      require-specific: true
  exclusions:
    generated: strict
    rules:
      - path: _test\.go
        linters: [gosec]
formatters:
  enable: [gofmt, goimports]
  settings:
    goimports:
      local-prefixes: [<module path>]
```

`contextcheck` and `wrapcheck` were tried and left out: `contextcheck` reports a false positive on every Cobra
`RunE` that uses `cmd.Context()`, and `wrapcheck` demands wrapping `fmt.Fprintln` results in every command.

- `errcheck`: every returned error is handled. Intentionally ignored: `_ = f.Close()` on a read-only file in a
  defer, `_, _ = fmt.Fprintln(w, …)` on stderr diagnostics — never silently on stdout results.
- `defer f.Close()` on a file you **wrote** loses the write error: close explicitly and check.
