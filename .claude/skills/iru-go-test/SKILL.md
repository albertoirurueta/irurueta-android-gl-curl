---
name: iru-go-test
description: "/iru-go-test [package patterns] runs a Go module or workspace's tests with go test -json and reports a compact pass/fail/skip summary. Optional lines: run:, skip:, tags: (integration for container tests), race: yes|no, junit: yes|no, lock: held, ui: auto|yes|no. Report-only; never fixes tests."
model: haiku
allowed-tools: Read Write Bash(go *) Bash(CGO_ENABLED=* go *) Bash(GOFLAGS=* go *) Bash(UI_SCREENSHOTS_DIR=* go *) Bash(gotestsum *) Bash(UI_SCREENSHOTS_DIR=* gotestsum *) Bash(mkdir -p *) Bash(python3 *) Bash(docker info *) Bash(command -v *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(test *) Bash(date *) Bash(mkdir -p build*) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Bash(rm -f build/test-results/*)
---

# Go Test

Run the project's Go tests, scoped to whatever the user asked for, and report a compact pass/fail summary parsed from
`go test -json`'s own event stream. This skill only runs tests and reports results — it does not fix failures, modify
source/test code, edit `go.mod`/`go.sum`, re-vendor, or start containers unless asked to as a follow-up.

## Step 0 — Parse the arguments

The argument string is optional package patterns on the first line(s) plus optional `key: value` lines from a calling
skill:

- **Package patterns** (zero or more, space- or comma-separated): `./...` (default), `./internal/cli/...`,
  `./internal/greet`, a full import path (`github.com/acme/tool/internal/greet`). A `.go` file path is mapped to its
  directory (`internal/greet/greet.go` → `./internal/greet`) — `go test` runs packages, not files.
- **`run: <regex>`** → `-run '<regex>'` (Go's test-name regex; `/` separates subtest levels, e.g.
  `TestScripts/greet`). **`skip: <regex>`** → `-skip '<regex>'`.
- **`tags: <comma list>`** → `-tags <list>`. `integration` selects the container-backed tests (Step 3).
- **`race: yes|no`** — default `yes` when the race detector can run (Step 2), else `no` with a note.
- **`junit: yes|no`** — default `no`; `yes` writes `build/test-results/go-test.xml` through gotestsum (Step 4).
- **`lock: held`** — the caller (e.g. `iru-go-code-one-task-group`) already holds `.integration-test.lock`; don't
  acquire or release it here (Step 5).
- **`ui: auto|yes|no`** — default `auto`: Step 4a applies when `go.mod` requires `charm.land/bubbletea/v2` (or the v1
  `github.com/charmbracelet/bubbletea`); `yes` forces it, `no` skips it.

Everything combines: `./internal/store/...` + `tags: integration` + `run: TestUpsert` runs only matching integration
tests in that tree.

## Step 1 — Detect the module layout (never edit `go.mod`)

Run from the repository root (`git rev-parse --show-toplevel`, else the current directory):

1. `command -v go` — missing → **stop and report "Go toolchain not installed"**. Note `go version` and `go env GOWORK
   GOFLAGS GOTOOLCHAIN` for the report; if `go.mod`'s `toolchain`/`go` line asks for a newer Go than the installed
   one and `GOTOOLCHAIN=local`, the run fails with `go: go.mod requires go >= …` — report that, don't change it.
2. **Module vs. workspace**: `go list -m -f '{{.Path}} {{.Dir}}'` prints one line per main module. With a `go.work`
   (`go env GOWORK` non-empty) whose root is not itself a module, `./...` fails with `pattern ./...: directory prefix .
   does not contain modules listed in go.work` (verified) — expand the default to one `./<dir>/...` per workspace
   module (`go list -m -f '{{.Dir}}'`, made relative) and say so. Patterns never cross a nested module's boundary
   (a sub-directory with its own `go.mod` isn't part of `./...`) — list nested modules you skipped.
3. **Vendoring**: with a `vendor/` directory and `go` ≥ 1.14 in `go.mod`, the go command uses `-mod=vendor`
   automatically. If the run fails with `go: inconsistent vendoring in …` (verified: `… is explicitly required in
   go.mod, but not marked as explicit in vendor/modules.txt`), **report it as a wiring problem** ("run `go mod vendor`")
   — never retry with `GOFLAGS=-mod=mod` silently; do so only if the user asks, and say the result doesn't reflect the
   vendored tree. A `missing go.sum entry` error is likewise reported ("run `go mod tidy`"), not fixed.
4. **Test presence**: `go list -f '{{if or .TestGoFiles .XTestGoFiles}}{{.ImportPath}}{{end}}' <patterns>` lists
   packages that have tests (add `-tags <tags>` when given — `//go:build integration` files are invisible without
   it). Empty → **report "no test files in <patterns>"** and stop; never "0 tests passed".

## Step 2 — Decide on the race detector

`-race` needs cgo on Linux and Windows (verified: `CGO_ENABLED=0 … go test -race` → `go: -race requires cgo`), but
not on macOS (verified: Go 1.27 on darwin/arm64 ran `-race` with `CGO_ENABLED=0`). Read `go env CGO_ENABLED GOOS`:

- `GOOS=darwin`, or `CGO_ENABLED=1` (the go command already sets it to `0` when no C compiler is on `PATH`) → `-race`.
- Otherwise → run without it and report "race detector off: cgo unavailable (no C compiler / `CGO_ENABLED=0`)".
- `race: no` from Step 0 always wins. Never install a C compiler.

## Step 3 — Classify fast vs. integration

The convention (the one `iru-setup-go-cli` and `iru-go-code-one-task` use): container-backed tests live in files with
a `//go:build integration` constraint, so a plain `go test ./...` never compiles them (verified: the package reports
`[no test files]` without the tag). So:

- **fast** (default) = no `integration` in `tags`. Container-free; no lock.
- **integration** = `tags` contains `integration`. This compiles the fast tests of those packages too — narrow with
  `run:` or the package pattern when only the container tests are wanted.
- A repository using another tag (`e2e`, `docker`) or a `testing.Short()` / env-var guard instead: read `Makefile`,
  `Taskfile.yml`, `magefile.go`, and `.github/workflows/*.yml` for the real invocation and mirror it; say which
  convention was found. If tests import `github.com/testcontainers/testcontainers-go` **without** any build-tag
  guard (`grep -rl --include='*_test.go' testcontainers-go .` vs. `grep -L 'go:build'`), warn "container-backed tests
  aren't tagged — they run in the fast selection" and treat the whole run as container-backed for Step 5.

## Step 4 — Build the command

```bash
mkdir -p build/test-results
go test -json -count=1 [-race] [-tags <tags>] [-run '<regex>'] [-skip '<regex>'] <patterns> \
  > build/test-results/go-test.json 2> build/test-results/go-test.stderr
```

- `-count=1` disables the test cache — a cached `(cached)` pass is not a fresh verification. `-json` implies `-v`
  for the event stream; stdout holds JSON only, while toolchain chatter (`go: downloading …`) goes to stderr.
- Don't add `-cover`/`-coverprofile` (that's `iru-go-coverage`'s job), `-shuffle`, `-failfast`, `-p`, or `-timeout`
  unless the project's CI passes them; mirror a CI `-timeout` when present (the default is 10m per test binary).
- **JUnit** (`junit: yes`, or the project's CI already uses gotestsum): when `command -v gotestsum` succeeds, run the
  same arguments through it — it writes both files from one run:

  ```bash
  gotestsum --format pkgname --jsonfile build/test-results/go-test.json --junitfile build/test-results/go-test.xml \
    -- -count=1 [-race] [-tags …] [-run …] [-skip …] <patterns>
  ```

  The JSON file has the same events as `go test -json` (verified, gotestsum 1.13.0), so Step 6 is unchanged; its
  JUnit counts parent tests (`TestScripts`) as well as their subtests, so the totals differ slightly from Step 6's
  leaf count. gotestsum missing → say "JUnit not written: gotestsum not installed" (never `go install` it here).

## Step 4a — Terminal-UI captures (UI contract)

Applies per Step 0's `ui:`. A Bubble Tea project's UI tests are ordinary in-process tests that write the stripped view
as `.txt` through the package's `ui_screenshots_test.go` helper (the coding skill's `reference/ui-implementation.md`);
this gate only prepares the directory, runs them with the rest, and checks what they left. No lock — they need no
terminal, container or device.

1. **Directory**: `UI_SCREENSHOTS_DIR` from the environment when already set (an absolute path), else
   `<repository root>/.ui-screenshots`. Prefix the Step 4 command (`go test` or `gotestsum`) with
   `UI_SCREENSHOTS_DIR="<that absolute path>"` — every test binary runs in its own package directory, so the variable
   is what makes them agree.
2. **Clear it only for a full run** — the default `./...` (or every workspace module) with no `run:`/`skip:`:
   `mkdir -p "<dir>" && find "<dir>" -mindepth 1 -delete`, so the directory holds exactly this run. A scoped run never
   clears it.
3. **Level**: read `accessibility:` from `.ui-quality.yml` at the repository root (`grep -E '^accessibility:'`);
   missing file or key → `enforce`. Under `enforce` a violation fails its test (`t.Errorf` naming the rule ids);
   under `report` the tests append `<target>\t<rule-id>: <message>` lines to `<dir>/a11y-report.txt`; under `off`
   nothing is checked.
4. **After the run**, validate and count with stdlib Python (script kept outside the repository, e.g.
   `$TMPDIR/iru-go-test/uicheck.py`, run as `python3 -I <script> "<dir>"`):

   ```python
   import collections
   import os
   import re
   import sys

   root = sys.argv[1]
   name = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*--(?:ltr|rtl)--(?:light|dark|high-contrast|no-color)"
                     r"(?:--[a-z0-9]+(?:-[a-z0-9]+)*)*\.(?:txt|png)$")
   type_id = re.compile(r"^[A-Za-z][A-Za-z0-9]*$")
   flow_id = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
   counts, bad = collections.Counter(), []
   for dirpath, _, files in os.walk(root):
       for f in files:
           rel = os.path.relpath(os.path.join(dirpath, f), root)
           parts = rel.split(os.sep)
           if parts == ["a11y-report.txt"]:
               continue
           ok = (len(parts) == 3 and parts[0] in ("component", "screen", "flow")
                 and (flow_id if parts[0] == "flow" else type_id).match(parts[1]) and name.match(parts[2]))
           if ok:
               counts[parts[0]] += 1
           else:
               bad.append(rel)
   rules = collections.Counter()
   report = os.path.join(root, "a11y-report.txt")
   if os.path.exists(report):
       with open(report, encoding="utf-8") as fh:
           for line in fh:
               rest = line.partition("\t")[2]
               if rest.strip():
                   rules[rest.split(":", 1)[0].strip()] += 1
   print("captures " + " ".join(f"{k}={counts[k]}" for k in ("component", "screen", "flow")))
   print(f"malformed={len(bad)} " + " ".join(bad[:20]))
   print("a11y-report " + (" ".join(f"{k}={v}" for k, v in sorted(rules.items())) or "none"))
   ```

   Malformed names are reported (they break the documentation skills' copy step), never renamed here. A `.png` next
   to a `.txt` is optional (VHS/`freeze` renders, never required); a missing PNG is not a finding. Terminal UIs have no
   automated accessibility tool — say the checks are the asserted TUI checks (`tui-color-only`, `tui-help-line`,
   `tui-keyboard`), not an audit.

**Accessibility level `none`/`off`.** If `.ui-quality.yml` says `accessibility: none` (opted out entirely) or `off` (rules apply, checks not run), accessibility checks are not applicable: skip them and report "accessibility: opted out" (`none`) or "accessibility checks off" (`off`) - never "unverified" and never a failure.

## Step 5 — Serialize container-backed runs behind the repository lock

Applies to an `integration`-tagged run and a fast run Step 3 classified as container-backed. Skip it otherwise —
in-process testscript CLI tests need no lock.

1. **Docker first**: `docker info >/dev/null 2>&1`. If it fails, **don't run**: report **unverified (Docker
   unavailable)** — never as passed, never as N failed tests. (testcontainers-go also finds the daemon through
   `DOCKER_HOST`, Docker contexts, `~/.testcontainers.properties` and the default socket — verified: it still reached
   `/var/run/docker.sock` with a bogus `DOCKER_HOST` — so `docker info` is the check, not an env var.)
2. **`lock: held`** → the caller holds the lock; skip to the run.
3. Otherwise acquire the lock at the **repository root** — a directory, because `mkdir` either creates it or fails,
   atomically (a lock *file* written with `Write` has a read-then-write window and must not be used instead):

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with "iru-go-test", the patterns/tags, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the root `.gitignore`; if it isn't, say
     so in the report (this skill doesn't edit `.gitignore`).
   - **`HELD`** — say the run is waiting (naming the holder from `cat .integration-test.lock/owner`), then re-run the
     `mkdir` roughly every 30 seconds. Never run the selection anyway — containers and ports are shared.
   - **Stale lock**: if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Report what the owner file says, break it (`rm -f .integration-test.lock/owner && rmdir
     .integration-test.lock`), acquire fresh, and say so.
   - **Wait timeout**: still held after ~45 minutes and not stale → report **unverified — blocked by a concurrent
     integration run**.
4. Run the Step 4 command — exactly one `go test` invocation, in the foreground, never alongside another test run.
5. **Release** as soon as it finishes, on **every** exit path (passed, failed, build failure, interruption):

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Only release a lock this run acquired — never one taken under `lock: held`.

## Step 6 — Interpret the exit code and parse the event stream

`go test` exits `0` when every package passed (including "no test files" and "no tests to run" packages) and `1` when
any test or package failed **or a package failed to build** — so the exit code alone can't tell a red test from a
compile error. An empty `go-test.json` plus an error in `go-test.stderr` (`go: -race requires cgo`, `go: inconsistent
vendoring`, `go.mod requires go >= …`, `no required module provides package`) means the run never started — report
that line as the cause.

Parse the stream (stdlib only — runs on any `python3` ≥ 3.8):

```bash
python3 - build/test-results/go-test.json <<'PY'
import json
import sys
from collections import defaultdict

tests = {}                      # (pkg, test) -> final action
out = defaultdict(list)         # (pkg, test) -> output lines
pkg_state, pkg_note, build = {}, {}, defaultdict(list)
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    raw = raw.strip()
    if not raw.startswith("{"):
        continue                # non-JSON noise is ignored
    e = json.loads(raw)
    a, pkg, test = e.get("Action"), e.get("Package"), e.get("Test")
    if a == "build-output":
        build[e["ImportPath"]].append(e["Output"].rstrip("\n"))
    elif a == "output":
        line = e["Output"].rstrip("\n")
        out[(pkg, test)].append(line)
        if test is None and "[no test files]" in line:
            pkg_note[pkg] = "no test files"
        elif test is None and line.startswith("testing: warning: no tests to run"):
            pkg_note[pkg] = "no tests to run"
        elif test is None and ("[build failed]" in line or "[setup failed]" in line):
            pkg_note[pkg] = line.split("\t")[-1].strip()
    elif a in ("pass", "fail", "skip"):
        if test is None:
            pkg_state[pkg] = a
            if e.get("FailedBuild"):
                pkg_note[pkg] = "build failed: " + e["FailedBuild"]
        else:
            tests[(pkg, test)] = a

# a parent test (TestScripts) fails when a subtest fails: count leaves only
names = set(tests)
leaves = {k: v for k, v in tests.items() if not any(o[0] == k[0] and o[1].startswith(k[1] + "/") for o in names)}
count = defaultdict(int)
for v in leaves.values():
    count[v] += 1
print(f"packages={len(pkg_state)} tests={len(leaves)} passed={count['pass']} failed={count['fail']} skipped={count['skip']}")
for pkg, note in sorted(pkg_note.items()):
    if note != "no test files":
        print(f"PACKAGE {pkg}: {note}")
nofiles = sorted(p for p, n in pkg_note.items() if n == "no test files")
if nofiles:
    print(f"no test files: {len(nofiles)} package(s) ({', '.join(p.rsplit('/', 1)[-1] for p in nofiles[:8])}{', ...' if len(nofiles) > 8 else ''})")
for (pkg, test), v in sorted(leaves.items()):
    if v != "fail":
        continue
    lines = [l.strip() for l in out[(pkg, test)]
             if l.strip() and not l.lstrip().startswith(("=== ", "--- FAIL", "--- PASS", "--- SKIP"))]
    pick = [l for l in lines if l.startswith(("FAIL: ", "panic: "))] or lines  # testscript / panic first
    print(f"FAIL {pkg}.{test} — {pick[0] if pick else '(no output)'}")
failed_pkgs = {k[0] for k, v in leaves.items() if v == "fail"}
for pkg, st in sorted(pkg_state.items()):        # package failed with no failing test: timeout, TestMain, os.Exit
    if st == "fail" and pkg not in failed_pkgs and not pkg_note.get(pkg, "").startswith(("build failed", "FAIL")):
        why = [l.strip() for k, ls in out.items() if k[0] == pkg for l in ls
               if l.startswith(("panic:", "FAIL: ", "testing:")) or "test timed out" in l or "exit status" in l]
        print(f"PACKAGE-FAIL {pkg} — {why[0] if why else 'failed outside any test (see output)'}")
for ip, lines in build.items():
    msg = [l for l in lines if not l.startswith("#")]
    print(f"BUILD {ip} — {msg[0] if msg else '(no output)'}")
PY
```

What the events mean (verified, Go 1.27.2):

- A test's terminal event is `pass`/`fail`/`skip` with `Test` set; the package's own terminal event has no `Test`.
  Subtests (`TestScripts/greet`) are separate tests; the parent fails whenever a child does, so only **leaves** are
  counted and listed.
- **Failure message**: the first non-frame `output` line of the failing test (`greet_test.go:20: Shout() = …`). For
  a testscript script the first line is a `WORK=$WORK` environment dump, so the parser prefers the `FAIL:
  testdata/script/<name>.txtar:<line>: <reason>` line; for a panic, the `panic: …` line.
- **No test files**: a package-level `skip` plus `[no test files]` output — listed in one summary line, not as an error.
- **`-run`/`-skip` matched nothing**: `testing: warning: no tests to run` and a package `pass` with `[no tests to run]`
  (exit `0`) — report "`run: <regex>` matched no test in <packages>", never "all passed".
- **Build failure**: `build-output` events (with `ImportPath`, no `Package`) carrying the compiler error, a
  `build-fail`, then the package's `fail` with `FailedBuild` and `[build failed]` output. A bad pattern or missing
  directory gives `[setup failed]` the same way. Report the first compiler line per package — it blocks every test
  in that package.
- **Package failure outside a test**: a timeout (`panic: test timed out after 10m0s`, then `running tests:` naming
  the stuck test), a `TestMain` that exits non-zero, or `os.Exit` in a test → `PACKAGE-FAIL` with that line.

**Environment failures, not test failures** — classify an integration failure by its message before reporting it:
`Cannot connect to the Docker daemon`, `rootless Docker not found`, `failed to create Docker provider`, `port not
found`/`context deadline exceeded` while waiting for a container, `connection refused` to a container address →
"integration not run/aborted: <cause>", not N failed tests.

Leave `build/test-results/` in place when `build/` is gitignored (CI and Sonar read `go-test.json`/`go-test.xml`);
otherwise delete the files after parsing (`rm -f build/test-results/go-test*`).

## Step 7 — Report results

- State the toolchain and scope (e.g. "go 1.27.2, module `github.com/acme/tool`, `./...` (5 packages), `-race`,
  `-count=1`, fast selection — `integration` tag not set") and the counts — never the JSON stream or the console log.
- For an integration run, say whether the lock was acquired, waited for, or held by the caller; a stage that didn't
  run is **unverified (<reason>)** — Docker unavailable, blocked by a concurrent run.
- "No test files" / "`run:` matched nothing" / "race detector off" / "JUnit not written" are said plainly.
- On failures, list each failing leaf test (`<package>.<Test/sub>`) with the first line of its message — nothing more
  per failure — then build failures and package-level failures separately; name wiring problems (inconsistent
  vendoring, missing `go.sum` entry, toolchain too old) as such.
- **UI captures** (Step 4a, when it applied): the directory, whether it was cleared (full run) or kept (scoped run),
  captures per kind (`component=… screen=… flow=…`), malformed names (count + the first few paths), accessibility
  violations as counts per rule id (from failing tests under `enforce`, from `a11y-report.txt` under `report`) — never
  the raw text — and the `.ui-quality.yml` level applied (`enforce` by default when the file is missing).
- Do not fix failing tests, modify source/test code, edit `go.mod`/`go.sum`, run `go mod tidy`/`go mod vendor`, or
  start containers unless the user asks for that as a next step.

## Verification notes

Verified with Go 1.27.2 (darwin/arm64), gotestsum 1.13.0, cobra 1.10.2, go-internal 1.16.0 (testscript),
testcontainers-go 0.44.0 and Docker, in `$TMPDIR/iru-verify/cli/go-gates/demo` (a throwaway Cobra CLI module outside
this repository: `cmd/democtl/main.go`, `internal/cli` with an in-process testscript `TestMain`, `internal/greet` with
one passing, one failing and one skipped test):

- `go test -json -count=1 -race ./...` exited `1`; the parser printed `packages=4 tests=5 passed=3 failed=1 skipped=1`,
  `no test files: 2 package(s) (democtl, version)` and `FAIL …/internal/greet.TestShoutBroken — greet_test.go:20:
  Shout() = "HELLO, ana!", want "HELLO, ANA!"`.
- A failing `.txtar` script was reported as `FAIL …/internal/cli.TestScripts/bad — FAIL: testdata/script/bad.txtar:2:
  no match for ``^Hi, Bob!$`` found in stdout` (its first output line is `testscript.go:609: WORK=$WORK`); a nil-map
  write as `panic: assignment to entry in nil map [recovered, repanicked]`; an undefined identifier in a test file as
  `BUILD … — internal/broken/b_test.go:5:32: undefined: undefinedThing` (events `build-output`/`build-fail`, then the
  package `fail` with `FailedBuild`); `-timeout 1s` on a sleeping test as `PACKAGE-FAIL … — panic: test timed out
  after 1s` with no leaf test counted.
- `-run TestNope` exited `0` with `testing: warning: no tests to run` and `[no tests to run]`; `./nope/...` and a
  missing directory gave `[setup failed]` with exit `1`.
- `CGO_ENABLED=0 GOOS=linux go test -race` → `go: -race requires cgo` (no JSON); on darwin the same flags ran fine.
- A `go.work` root that isn't a module: `go test ./...` → `directory prefix . does not contain modules listed in
  go.work`; `./a/... ./b/...` worked. A stale `vendor/` → `go: inconsistent vendoring`; `GOFLAGS=-mod=mod` bypassed it.
- A `//go:build integration` testcontainers-go test (alpine container, `wait.ForLog`) passed in ~19 s with `-tags
  integration`; without the tag the package reported `[no test files]`. `docker info` with a bogus `DOCKER_HOST`
  exited `1`, but testcontainers-go still reached the default socket — hence the `docker info` gate. The lock commands
  are the same as `iru-python-test`'s (verified there).
- gotestsum `--jsonfile … --junitfile … -- -count=1 ./...` wrote both files from one run; the parser gave the same
  counts from its JSON file.
- Step 4a (UI captures, added for the UI contract): the validation script was run with Python 3 against a sample tree
  (valid screen/flow captures counted, an upper-case state and a stray file reported as malformed, report-mode rule
  ids counted); the Go side — `UI_SCREENSHOTS_DIR` reaching each test binary, the helper's captures — was not run (no
  Go toolchain on the authoring machine) and is unverified. Still unverified: Windows hosts, a real "Docker unavailable" testcontainers-go error
  string (the classification strings above come from testcontainers-go's sources), and `-race` on Linux CI runners.

## Sources

- go.dev: `go help test`, `go help testflag`, `go doc cmd/test2json` (event `Action`s, `build-output`/`build-fail`,
  `FailedBuild`), Go 1.20 release notes (race detector without cgo on macOS), go.dev/ref/mod (workspaces, vendoring)
- pkg.go.dev: github.com/rogpeppe/go-internal/testscript; github.com/testcontainers/testcontainers-go
- gotestsum README (github.com/gotestyourself/gotestsum): `--jsonfile`, `--junitfile`, `--format`
- irurueta docs: ai/cli-for-agents/testing-clis.adoc (testscript for Go, black-box CLI tests asserting stdout/stderr/
  exit code, golden JSON)
