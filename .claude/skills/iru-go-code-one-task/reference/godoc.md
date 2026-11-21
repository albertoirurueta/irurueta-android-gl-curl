# Doc comments (go doc)

Every exported identifier the task adds or changes gets a doc comment; the group's `iru-go-godoc` gate audits and
backfills, and golangci-lint `revive` (`exported`, `package-comments`) + `godot` enforce it. Writing them now is
cheaper than a backfill pass.

## Rules (go.dev/doc/comment)

- A doc comment is the `//` comment block **immediately above** the declaration, no blank line in between.
- It is a complete sentence that **starts with the identifier's name** and ends with a period:
  `// Greet validates name and returns the greeting payload.` — revive's `exported` rule checks the name prefix,
  `godot` the period.
- Say what it does and what the caller must know (units, nil handling, which errors it returns and how to match
  them: "returns an error wrapping fs.ErrNotExist when…"), not how it's implemented.
- Grouped `const`/`var` blocks: one comment above the block describes the group (the scaffold's exit codes), plus
  per-item comments where an item needs its own explanation.
- Methods implementing a well-known interface may say so briefly: `// Init implements tea.Model.`
- Deprecations: a paragraph starting `Deprecated: ` (tools and `staticcheck` SA1019 pick it up).
- Links: `[fmt.Errorf]`, `[Model.Update]`, `[RFC 8259]` with a link definition; lists with `-` and a blank line;
  code blocks indented by a tab.

## Packages

- Every package has exactly one package comment, `// Package cli implements …`, conventionally in `doc.go` when it's
  longer than a line, otherwise above `package` in the main file. `package main` uses `// Command greeter …`.
- `internal/` packages need package comments too (revive's `package-comments` doesn't exempt them), even though they
  aren't published.

## Unexported code and tests

Unexported identifiers don't need doc comments; comment *why* where the code isn't obvious (`// Cobra validates
flag groups only after the pre-run hooks, so…`). Test functions don't need doc comments; a test helper's comment is
welcome but not required.

## Check locally (only when asked — the group runs the gate)

`go doc ./internal/cli` and `go doc ./internal/cli.Greet` render what a reader sees; `go vet` doesn't check doc
comments. Don't run golangci-lint from the per-task skill.
