# Module templates — `go.mod`, entry point, version package, e2e test driver

The module skeleton every run writes: the `go` commands that create `go.mod`/`go.sum`, the thin `cmd/__BIN__/main.go`,
`internal/version`, and the testscript driver that runs `testdata/script/*.txtar` against the real `main` in-process.

Conditional markers (`// >>> <condition>` … `// <<< <condition>`) and the `__TOKEN__` placeholders work as described
in SKILL.md's "Reference files" section. Every file below was rendered, built, linted and tested on 2026-10-16 for
`ui: none` and `ui: tui` (Go 1.27.2) — see SKILL.md "Verification notes".

## `go` commands (Step 6.1 — run in this order at the repository root)

```bash
go mod init __MODULE__                      # writes `go <toolchain version>`; fixed by the next line
go mod edit -go=__GO_DIRECTIVE__ -toolchain=go__GO_TOOLCHAIN__
go get github.com/spf13/cobra@v__COBRA_VERSION__ \
       github.com/spf13/pflag@v__PFLAG_VERSION__ \
       golang.org/x/term@v__XTERM_VERSION__ \
       github.com/pelletier/go-toml/v2@v__GOTOML_VERSION__ \
       github.com/rogpeppe/go-internal@v__GOINTERNAL_VERSION__
# ui: tui only
go get charm.land/bubbletea/v2@v__BUBBLETEA_VERSION__ \
       charm.land/lipgloss/v2@v__LIPGLOSS_VERSION__ \
       github.com/charmbracelet/x/exp/teatest/v2@__TEATEST_VERSION__
# after every source file is written:
go mod tidy
```

- `go mod edit -go=1.27.0 -toolchain=go1.27.2`: the `go` line is the language/minimum version (`<minor>.0`, so any
  patch of that minor builds it); the `toolchain` line names the patch release CI and `GOTOOLCHAIN=auto` users build
  with — `actions/setup-go` ≥ v6 reads `toolchain` before `go` (so CI gets the patched standard library and
  `govulncheck` doesn't flag stdlib advisories fixed in later patches). `go mod tidy` keeps both lines.
- `pflag` is requested explicitly: Cobra 1.10.2 still requires pflag 1.0.9, and minimal version selection would keep
  it there otherwise.
- `go get` before the sources exist adds `// indirect` markers; `go mod tidy` after the sources exist turns them into
  direct requirements and drops anything unused (e.g. teatest when `ui: none`). `go.sum` is committed.

### `cmd/__BIN__/main.go`

```go
// Command __BIN__ runs the __BIN__ command-line tool. __DESCRIPTION_SENTENCE__
//
// The entry point is deliberately thin: every behavior lives in
// internal/cli, so it is unit-tested and covered.
package main

import (
	"context"
	"os"

	"__MODULE__/internal/cli"
)

func main() {
	os.Exit(cli.Main(context.Background(), os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}
```

### `cmd/__BIN__/main_test.go`

```go
package main

import (
	"errors"
	"flag"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/rogpeppe/go-internal/testscript"
)

var update = flag.Bool("update", false, "rewrite the expected output in testdata/script/*.txtar")

// TestMain lets the test binary act as __BIN__ itself: testscript re-executes
// it with the command name below, which runs main in a child process. The
// child is the coverage-instrumented test binary, so `go test -coverprofile`
// counts what the scripts exercise.
func TestMain(m *testing.M) {
	testscript.Main(m, map[string]func(){"__BIN__": main})
}

// TestScripts runs every end-to-end script in testdata/script.
func TestScripts(t *testing.T) {
	testscript.Run(t, testscript.Params{
		Dir:                 filepath.Join("..", "..", "testdata", "script"),
		RequireExplicitExec: true,
		UpdateScripts:       *update,
		Cmds:                map[string]func(ts *testscript.TestScript, neg bool, args []string){"status": status},
	})
}

// status runs a command and asserts its exact exit code:
//
//	status 3 exec __BIN__ greet ''
//
// testscript's own `! exec` only asserts "non-zero"; the exit-code contract
// needs the number.
func status(ts *testscript.TestScript, neg bool, args []string) {
	if neg || len(args) < 3 || args[1] != "exec" {
		ts.Fatalf("usage: status CODE exec COMMAND [ARGS...]")
	}
	want, err := strconv.Atoi(args[0])
	ts.Check(err)
	got := 0
	if err := ts.Exec(args[2], args[3:]...); err != nil {
		var exitErr *exec.ExitError
		if !errors.As(err, &exitErr) {
			ts.Fatalf("%v", err)
		}
		got = exitErr.ExitCode()
	}
	if got != want {
		ts.Fatalf("exit status %d, want %d", got, want)
	}
}
```

### `internal/version/version.go`

```go
// Package version holds the build metadata of __BIN__.
//
// Version, Commit and Date are set at link time by the release build
// (GoReleaser's ldflags):
//
//	go build -ldflags "-X __MODULE__/internal/version.Version=1.2.3" ./cmd/__BIN__
//
// The in-tree Version carries a pre-release suffix on the integration branch;
// iru-go-bump-version rewrites it.
package version

import (
	"fmt"
	"regexp"
	"runtime"
	"runtime/debug"
	"strings"
)

// Version is the semantic version of this build, without a leading "v".
var Version = "__VERSION__"

// Commit is the full git commit hash this build was made from, or "" for a
// development build.
var Commit = ""

// Date is the commit date (RFC 3339) of this build, or "" for a development
// build. It is the commit's date rather than the build time, so builds stay
// reproducible.
var Date = ""

// Info is the build metadata reported by `__BIN__ version`.
type Info struct {
	Commit   string `json:"commit"`
	Date     string `json:"date"`
	Go       string `json:"go"`
	Platform string `json:"platform"`
	Version  string `json:"version"`
}

// Get returns the build metadata of the running binary. A binary installed
// with `go install module@vX.Y.Z` has no ldflags, so its module version from
// the embedded build information is used instead of the in-tree default.
func Get() Info {
	bi, ok := debug.ReadBuildInfo()
	return Info{
		Commit:   Commit,
		Date:     Date,
		Go:       runtime.Version(),
		Platform: runtime.GOOS + "/" + runtime.GOARCH,
		Version:  resolve(Version, Commit, bi, ok),
	}
}

// String formats the metadata for people, e.g.
// "1.2.3 (commit 0123abc, 2026-10-16T09:30:00Z, go1.27.2, linux/amd64)".
func (i Info) String() string {
	commit := i.Commit
	if len(commit) > 7 {
		commit = commit[:7]
	}
	if commit == "" {
		commit = "unknown"
	}
	date := i.Date
	if date == "" {
		date = "unknown date"
	}
	return fmt.Sprintf("%s (commit %s, %s, %s, %s)", i.Version, commit, date, i.Go, i.Platform)
}

// resolve prefers the release version recorded by `go install module@vX.Y.Z`
// over the in-tree default. A release build (Commit set through ldflags) and
// a development build (a "(devel)" or pseudo-version such as
// v0.0.0-20261016093000-0123456789ab) keep current.
func resolve(current, commit string, bi *debug.BuildInfo, ok bool) string {
	if commit != "" || !ok || bi == nil || !releaseVersion.MatchString(bi.Main.Version) {
		return current
	}
	return strings.TrimPrefix(bi.Main.Version, "v")
}

var releaseVersion = regexp.MustCompile(`^v[0-9]+\.[0-9]+\.[0-9]+$`)
```

### `internal/version/version_test.go`

```go
package version

import (
	"runtime/debug"
	"strings"
	"testing"
)

func TestGetReportsRuntimeMetadata(t *testing.T) {
	info := Get()
	if info.Version == "" || info.Go == "" || !strings.Contains(info.Platform, "/") {
		t.Fatalf("incomplete info: %+v", info)
	}
}

func TestStringShortensCommitAndFillsUnknowns(t *testing.T) {
	full := Info{Commit: "0123456789abcdef", Date: "2026-10-16T09:30:00Z", Go: "go1.27.2", Platform: "linux/amd64", Version: "1.2.3"}
	if got, want := full.String(), "1.2.3 (commit 0123456, 2026-10-16T09:30:00Z, go1.27.2, linux/amd64)"; got != want {
		t.Fatalf("String() = %q, want %q", got, want)
	}
	dev := Info{Go: "go1.27.2", Platform: "linux/amd64", Version: "1.2.3"}
	if got := dev.String(); !strings.Contains(got, "commit unknown, unknown date") {
		t.Fatalf("String() = %q", got)
	}
}

func TestResolve(t *testing.T) {
	module := func(v string) *debug.BuildInfo { return &debug.BuildInfo{Main: debug.Module{Version: v}} }
	tests := []struct {
		name    string
		current string
		commit  string
		bi      *debug.BuildInfo
		ok      bool
		want    string
	}{
		{"release build keeps ldflags version", "2.0.0", "0123abc", module("v1.4.0"), true, "2.0.0"},
		{"go install uses module version", "__VERSION__", "", module("v1.4.0"), true, "1.4.0"},
		{"devel build", "__VERSION__", "", module("(devel)"), true, "__VERSION__"},
		{"pseudo-version build", "__VERSION__", "", module("v0.0.0-20261016093000-0123456789ab+dirty"), true, "__VERSION__"},
		{"no build info", "__VERSION__", "", nil, false, "__VERSION__"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := resolve(tt.current, tt.commit, tt.bi, tt.ok); got != tt.want {
				t.Fatalf("resolve() = %q, want %q", got, tt.want)
			}
		})
	}
}
```
