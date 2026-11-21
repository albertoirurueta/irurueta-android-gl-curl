# Tooling templates — golangci-lint, GoReleaser, the docs generator

`.golangci.yml` (golangci-lint v2 format: linters + formatters), `.goreleaser.yaml` (GoReleaser v2; only when
`distribution` is `internal` or `store`), and `tools/gendocs` — a `go run`-able generator that renders man pages,
Markdown pages, shell completion scripts and the Antora CLI reference page from the Cobra command tree.

Conditional markers and `__TOKEN__` placeholders work as described in SKILL.md's "Reference files" section; YAML
markers are `# >>> <condition>` / `# <<< <condition>`. Conditions used here: `distribution: internal|store`,
`channel: homebrew|scoop|winget` (each = `distribution: store` and that channel listed in `channels`) and `license`
(a license was chosen). Verified on 2026-10-16 with golangci-lint 2.15.0 (`config verify` + `run`, 0 issues) and
GoReleaser 2.18.3 (`check` + `release --snapshot --clean`) — see SKILL.md "Verification notes".

### `.golangci.yml`

```yaml
# golangci-lint v2 configuration (https://golangci-lint.run/docs/configuration/file/).
# Run: golangci-lint run ./...   Format: golangci-lint fmt ./...
version: "2"

linters:
  # standard = errcheck, govet, ineffassign, staticcheck, unused.
  default: standard
  enable:
    - godot
    - gosec
    - misspell
    - revive
  settings:
    godot:
      # Doc comments of declarations end with a period.
      scope: declarations
    revive:
      # Listing rules replaces revive's defaults, so the useful defaults are listed explicitly.
      rules:
        - name: exported
        - name: package-comments
        - name: blank-imports
        - name: context-as-argument
        - name: context-keys-type
        - name: dot-imports
        - name: empty-block
        - name: error-naming
        - name: error-return
        - name: error-strings
        - name: errorf
        - name: increment-decrement
        - name: indent-error-flow
        - name: range
        - name: receiver-naming
        - name: redefines-builtin-id
        - name: superfluous-else
        - name: time-naming
        - name: unexported-return
        - name: unreachable-code
        - name: var-declaration
        - name: var-naming
  exclusions:
    # Never hide doc-comment findings: the "comments" preset is deliberately not enabled.
    generated: lax

formatters:
  enable:
    - gofmt
    - goimports
  settings:
    goimports:
      local-prefixes:
        - __MODULE__
```

### `.goreleaser.yaml` (distribution: internal|store)

```yaml
# GoReleaser v2 configuration (https://goreleaser.com/customization/).
# Local dry run: goreleaser release --snapshot --clean   Validate: goreleaser check
version: 2

project_name: __BIN__

before:
  hooks:
    # Man pages and completion scripts shipped in every archive, generated from the command tree.
    - go run ./tools/gendocs -out build/gendocs -adoc ""

builds:
  - id: __BIN__
    main: ./cmd/__BIN__
    binary: __BIN__
    env:
      - CGO_ENABLED=0
    goos: [linux, darwin, windows]
    goarch: [amd64, arm64]
    flags:
      - -trimpath
    ldflags:
      - -s -w
      - -X __MODULE__/internal/version.Version={{ .Version }}
      - -X __MODULE__/internal/version.Commit={{ .FullCommit }}
      - -X __MODULE__/internal/version.Date={{ .CommitDate }}
    # Reproducible builds: file times come from the commit, not the clock.
    mod_timestamp: "{{ .CommitTimestamp }}"

archives:
  - id: default
    formats: [tar.gz]
    format_overrides:
      - goos: windows
        formats: [zip]
    # <bin>-<version>-<os>-<arch>.<ext>, e.g. __BIN__-1.2.3-linux-amd64.tar.gz
    name_template: "{{ .ProjectName }}-{{ .Version }}-{{ .Os }}-{{ .Arch }}"
    files:
      # >>> license
      - LICENSE*
      # <<< license
      - README.md
      - CHANGELOG.md
      - src: build/gendocs/man/*
        dst: man
        strip_parent: true
      - src: build/gendocs/completions/*
        dst: completions
        strip_parent: true

checksum:
  name_template: SHA256SUMS
  algorithm: sha256

snapshot:
  version_template: "{{ incpatch .Version }}-dev+{{ .ShortCommit }}"

# CHANGELOG.md is maintained by iru-setup-changelog / iru-release; GoReleaser doesn't generate release notes.
changelog:
  disable: true

release:
  # The release workflow runs on a GitHub Release that already exists; keep its notes, add the assets.
  mode: keep-existing
# >>> channel: homebrew

# Homebrew cask in __TAP_OWNER__/__TAP_REPO__ (`brew install --cask __TAP_OWNER__/__TAP_SHORT__/__BIN__`).
homebrew_casks:
  - name: __BIN__
    ids: [default]
    binaries: [__BIN__]
    manpages:
      - man/__BIN__.1
    completions:
      bash: completions/__BIN__.bash
      zsh: completions/___BIN__
      fish: completions/__BIN__.fish
    homepage: "__HOMEPAGE__"
    description: "__DESCRIPTION_YAML__"
    commit_author:
      name: goreleaserbot
      email: bot@goreleaser.com
    repository:
      owner: __TAP_OWNER__
      name: __TAP_REPO__
      token: "{{ .Env.HOMEBREW_TAP_GITHUB_TOKEN }}"
    hooks:
      post:
        # The binaries are not notarized: clear the quarantine flag so macOS runs them.
        install: |
          if OS.mac?
            system_command "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "#{staged_path}/__BIN__"]
          end
# <<< channel: homebrew
# >>> channel: scoop

# Scoop manifest in __SCOOP_OWNER__/__SCOOP_REPO__ (`scoop bucket add … ; scoop install __BIN__`).
scoops:
  - name: __BIN__
    ids: [default]
    homepage: "__HOMEPAGE__"
    description: "__DESCRIPTION_YAML__"
    # >>> license
    license: "__LICENSE_SPDX__"
    # <<< license
    commit_author:
      name: goreleaserbot
      email: bot@goreleaser.com
    repository:
      owner: __SCOOP_OWNER__
      name: __SCOOP_REPO__
      token: "{{ .Env.SCOOP_BUCKET_GITHUB_TOKEN }}"
# <<< channel: scoop
# >>> channel: winget

# WinGet manifest: pushed to __OWNER__/winget-pkgs (a fork) and proposed to microsoft/winget-pkgs as a pull request.
winget:
  - name: __BIN__
    ids: [default]
    publisher: "__PUBLISHER__"
    package_identifier: __WINGET_ID__
    short_description: "__DESCRIPTION_YAML__"
    homepage: "__HOMEPAGE__"
    # >>> license
    license: "__LICENSE_SPDX__"
    # <<< license
    commit_author:
      name: goreleaserbot
      email: bot@goreleaser.com
    repository:
      owner: __OWNER__
      name: winget-pkgs
      branch: "{{ .ProjectName }}-{{ .Version }}"
      token: "{{ .Env.WINGET_GITHUB_TOKEN }}"
      pull_request:
        enabled: true
        base:
          owner: microsoft
          name: winget-pkgs
          branch: master
# <<< channel: winget
```

### `tools/gendocs/main.go`

```go
// Command gendocs renders __BIN__'s reference documentation from the Cobra
// command tree, so it never drifts from the code: man pages, Markdown pages,
// shell completion scripts and the Antora CLI reference page.
//
//	go run ./tools/gendocs                       # build/gendocs/{man,markdown,completions} + the Antora page
//	go run ./tools/gendocs -out dist/docs -adoc ""  # skip the Antora page
//
// The Antora page is written only when its directory already exists (the
// site is created by iru-setup-antora). Set SOURCE_DATE_EPOCH for
// reproducible man page dates.
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"github.com/spf13/cobra/doc"
	"github.com/spf13/pflag"

	"__MODULE__/internal/cli"
)

func main() {
	out := flag.String("out", filepath.Join("build", "gendocs"), "output directory for man pages, Markdown and completions")
	adoc := flag.String("adoc", filepath.Join("docs", "modules", "ROOT", "pages", "cli-reference.adoc"),
		`Antora CLI reference page, written only when its directory exists ("" to skip)`)
	flag.Parse()
	if err := run(*out, *adoc, os.Getenv, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "gendocs:", err)
		os.Exit(1)
	}
}

// run generates every output and reports what it wrote on log.
func run(out, adocPath string, getenv func(string) string, log io.Writer) error {
	root := cli.NewApp(strings.NewReader(""), io.Discard, io.Discard).Command()
	disableAutoGenTag(root)
	date, err := sourceDate(getenv)
	if err != nil {
		return err
	}
	dirs := map[string]string{"man": filepath.Join(out, "man"), "markdown": filepath.Join(out, "markdown"), "completions": filepath.Join(out, "completions")}
	for _, dir := range dirs {
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return err
		}
	}
	header := &doc.GenManHeader{Title: strings.ToUpper(root.Name()), Section: "1", Source: root.Name() + " " + root.Version, Manual: "User Commands", Date: &date}
	if err := doc.GenManTree(root, header, dirs["man"]); err != nil {
		return err
	}
	if err := doc.GenMarkdownTree(root, dirs["markdown"]); err != nil {
		return err
	}
	if err := writeCompletions(root, dirs["completions"]); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(log, "wrote man pages, Markdown and completions under %s\n", out)
	if adocPath == "" {
		return nil
	}
	if _, err := os.Stat(filepath.Dir(adocPath)); errors.Is(err, os.ErrNotExist) {
		_, _ = fmt.Fprintf(log, "skipped %s: its directory doesn't exist (run iru-setup-antora first)\n", adocPath)
		return nil
	}
	if err := writeAtomically(adocPath, []byte(asciiDoc(root))); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(log, "wrote %s\n", adocPath)
	return nil
}

func disableAutoGenTag(cmd *cobra.Command) {
	cmd.DisableAutoGenTag = true
	for _, child := range cmd.Commands() {
		disableAutoGenTag(child)
	}
}

// sourceDate returns SOURCE_DATE_EPOCH as a time, or now when it is unset.
func sourceDate(getenv func(string) string) (time.Time, error) {
	epoch := getenv("SOURCE_DATE_EPOCH")
	if epoch == "" {
		return time.Now().UTC(), nil
	}
	seconds, err := strconv.ParseInt(epoch, 10, 64)
	if err != nil {
		return time.Time{}, fmt.Errorf("SOURCE_DATE_EPOCH: %w", err)
	}
	return time.Unix(seconds, 0).UTC(), nil
}

// writeCompletions writes one completion script per shell, named the way
// package managers install them.
func writeCompletions(root *cobra.Command, dir string) error {
	name := root.Name()
	writers := []struct {
		file string
		gen  func(io.Writer) error
	}{
		{name + ".bash", func(w io.Writer) error { return root.GenBashCompletionV2(w, true) }},
		{"_" + name, root.GenZshCompletion},
		{name + ".fish", func(w io.Writer) error { return root.GenFishCompletion(w, true) }},
		{name + ".ps1", root.GenPowerShellCompletionWithDesc},
	}
	for _, wr := range writers {
		var b strings.Builder
		if err := wr.gen(&b); err != nil {
			return err
		}
		if err := writeAtomically(filepath.Join(dir, wr.file), []byte(b.String())); err != nil {
			return err
		}
	}
	return nil
}

// writeAtomically writes data to a temporary file next to path and renames
// it into place, so readers never see a half-written file.
func writeAtomically(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	defer func() { _ = os.Remove(tmp.Name()) }()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// asciiDoc renders the Antora CLI reference page.
func asciiDoc(root *cobra.Command) string {
	var b strings.Builder
	fmt.Fprintf(&b, "= `%s` command reference\n", root.Name())
	b.WriteString("// Generated by `go run ./tools/gendocs` from the Cobra command tree. Do not edit by hand.\n\n")
	fmt.Fprintf(&b, "%s\n\n", root.Short)
	b.WriteString("Run `" + root.Name() + " schema` for the same information as JSON.\n\n")
	b.WriteString("== Exit codes\n\n[cols=\"1,2,6\",options=\"header\"]\n|===\n|Code |Name |Meaning\n\n")
	for _, code := range cli.ExitCodes() {
		fmt.Fprintf(&b, "|%d\n|`%s`\n|%s\n\n", int(code), code.Name(), cell(code.Meaning()))
	}
	b.WriteString("|===\n\n== Global options\n\n")
	flagTable(&b, root.PersistentFlags())
	for _, cmd := range root.Commands() {
		commandSection(&b, cmd)
	}
	return b.String()
}

func commandSection(b *strings.Builder, cmd *cobra.Command) {
	if !cmd.IsAvailableCommand() || cmd.Name() == "help" {
		return
	}
	fmt.Fprintf(b, "== `%s`\n\n%s\n\n", cmd.CommandPath(), cmd.Short)
	fmt.Fprintf(b, "[source,shell]\n----\n%s\n----\n\n", cmd.UseLine())
	if cmd.Long != "" {
		fmt.Fprintf(b, "%s\n\n", cmd.Long)
	}
	if cmd.Example != "" {
		fmt.Fprintf(b, ".Examples\n[source,shell]\n----\n%s\n----\n\n", cmd.Example)
	}
	if cmd.HasAvailableLocalFlags() {
		b.WriteString(".Options\n")
		flagTable(b, cmd.LocalNonPersistentFlags())
	}
	for _, child := range cmd.Commands() {
		commandSection(b, child)
	}
}

func flagTable(b *strings.Builder, flags *pflag.FlagSet) {
	b.WriteString("[cols=\"2,1,5\",options=\"header\"]\n|===\n|Option |Default |Description\n\n")
	flags.VisitAll(func(f *pflag.Flag) {
		if f.Hidden {
			return
		}
		name := "`--" + f.Name + "`"
		if f.Shorthand != "" {
			name = "`-" + f.Shorthand + "`, " + name
		}
		def := f.DefValue
		if def != "" {
			def = "`" + def + "`"
		}
		fmt.Fprintf(b, "|%s\n|%s\n|%s\n\n", name, def, cell(f.Usage))
	})
	b.WriteString("|===\n\n")
}

// cell escapes AsciiDoc table cell separators.
func cell(s string) string { return strings.ReplaceAll(s, "|", `\|`) }
```

### `tools/gendocs/main_test.go`

```go
package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func env(kv map[string]string) func(string) string { return func(k string) string { return kv[k] } }

func TestRunWritesEveryOutput(t *testing.T) {
	dir := t.TempDir()
	pages := filepath.Join(dir, "docs", "modules", "ROOT", "pages")
	if err := os.MkdirAll(pages, 0o750); err != nil {
		t.Fatal(err)
	}
	adoc := filepath.Join(pages, "cli-reference.adoc")
	var log bytes.Buffer
	if err := run(filepath.Join(dir, "out"), adoc, env(map[string]string{"SOURCE_DATE_EPOCH": "1760000000"}), &log); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{"man/__BIN__.1", "man/__BIN__-greet.1", "markdown/__BIN___greet.md",
		"completions/__BIN__.bash", "completions/___BIN__", "completions/__BIN__.fish", "completions/__BIN__.ps1"} {
		if _, err := os.Stat(filepath.Join(dir, "out", f)); err != nil {
			t.Errorf("missing %s: %v", f, err)
		}
	}
	page, err := os.ReadFile(filepath.Clean(adoc))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"== Exit codes", "|`CONFIRMATION_REQUIRED`", "== `__BIN__ greet`", "`--format`", ".Examples"} {
		if !strings.Contains(string(page), want) {
			t.Errorf("reference page lacks %q", want)
		}
	}
	if strings.Contains(string(page), "== `__BIN__ help`") {
		t.Error("reference page documents the help command")
	}
	man, err := os.ReadFile(filepath.Clean(filepath.Join(dir, "out", "man", "__BIN__.1")))
	if err != nil || !strings.Contains(string(man), "Oct 2025") {
		t.Fatalf("man page date not taken from SOURCE_DATE_EPOCH: %v", err)
	}
}

func TestRunSkipsTheAntoraPageWithoutASite(t *testing.T) {
	dir := t.TempDir()
	var log bytes.Buffer
	adoc := filepath.Join(dir, "docs", "modules", "ROOT", "pages", "cli-reference.adoc")
	if err := run(filepath.Join(dir, "out"), adoc, env(nil), &log); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(adoc); !os.IsNotExist(err) || !strings.Contains(log.String(), "skipped") {
		t.Fatalf("page written without a site (log %q)", log.String())
	}
	if err := run(filepath.Join(dir, "out"), "", env(nil), &log); err != nil {
		t.Fatal(err)
	}
}

func TestRunReportsErrors(t *testing.T) {
	dir := t.TempDir()
	if err := run(dir, "", env(map[string]string{"SOURCE_DATE_EPOCH": "yesterday"}), &bytes.Buffer{}); err == nil {
		t.Fatal("invalid SOURCE_DATE_EPOCH accepted")
	}
	file := filepath.Join(dir, "file")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := run(filepath.Join(file, "out"), "", env(nil), &bytes.Buffer{}); err == nil {
		t.Fatal("output under a regular file accepted")
	}
	if err := writeAtomically(filepath.Join(dir, "missing", "x"), nil); err == nil {
		t.Fatal("write into a missing directory accepted")
	}
}

func TestCellEscapesSeparators(t *testing.T) {
	if got := cell("a|b"); got != `a\|b` {
		t.Fatalf("cell() = %q", got)
	}
}
```
