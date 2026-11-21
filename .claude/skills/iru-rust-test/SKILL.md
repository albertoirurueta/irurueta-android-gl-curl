---
name: iru-rust-test
description: "/iru-rust-test [filter ...] - runs a Rust crate or workspace's tests with cargo-nextest (or cargo test) plus doctests, always --locked, and reports a compact pass/fail/skip summary. Optional key: value lines: package, target, features, ignored, lock, ui. Report-only; #[ignore] tests run only on request."
model: haiku
allowed-tools: Read Write Bash(cargo *) Bash(CARGO_TERM_COLOR=* *) Bash(UI_SCREENSHOTS_DIR=* *) Bash(python3 *) Bash(rustup *) Bash(docker info *) Bash(command -v *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(test *) Bash(date *) Bash(mkdir -p *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Bash(rm -f target/nextest/*) Bash(rm -f target/iru-rust-test/*)
---

# Rust Test

Run the project's tests with cargo-nextest (or `cargo test`), scoped to whatever the user asked for, and report a
compact pass/fail summary parsed from nextest's JUnit XML or libtest's console output. This skill only runs tests and
reports results — it does not fix failures, modify source/test code, update `Cargo.lock`, install tools, or start
containers unless asked to as a follow-up.

## Step 0 — Parse the arguments

The argument string is zero or more positional filters plus optional `key: value` lines from a calling skill:

- **Filters** (positional, zero or more): test-name substrings (`greet`, `tests::parses_year`, `cli::rejects_empty`) —
  a test runs when its name contains any of them; or one nextest **filterset** prefixed `expr:` (`expr:test(/^cli::/)
  & package(democli)`, `expr:rdeps(democore)`) — nextest only.
- **`package: <name>`** — one workspace member (`-p <name>`). Default: every member (`--workspace`).
- **`target: lib|bins|tests|doc|<stem>`** — `lib` → `--lib` (unit tests in `src/lib.rs` and its modules), `bins` →
  `--bins` (unit tests inside `src/main.rs`/`src/bin/*`), `tests` → `--tests` (every test target incl. `tests/*.rs`),
  `doc` → doctests only (Step 4), `<stem>` → `--test <stem>` (`cli` = `tests/cli.rs`). Default: everything, doctests
  included.
- **`features: all|<comma list>`** — `all` → `--all-features`; a list → `--features a,b`. Default: the default
  features (the CI convention in this catalog's workflows is `--all-features` — say which one ran).
- **`ignored: exclude|include|only`** — default `exclude`. `include` → nextest `--run-ignored all` / libtest
  `--include-ignored`; `only` → `--run-ignored only` / `--ignored`. In this catalog `#[ignore = "…"]` marks
  container/network-backed tests (testcontainers-rs, a real database); any selection that runs them goes through
  Step 3.
- **`lock: held`** — the caller already holds `.integration-test.lock`; don't acquire or release it here.
- **`ui: auto|yes|no`** — default `auto`: Step 2a applies when any workspace member depends on `ratatui` (`cargo
  metadata` without `--no-deps`, or `grep -n '^ratatui' */Cargo.toml Cargo.toml`); `yes` forces it, `no` skips it.

## Step 1 — Survey the workspace and pick the runner

```bash
cargo metadata --format-version 1 --no-deps | python3 -c 'import json,sys; m=json.load(sys.stdin); ids=set(m["workspace_members"]); [print(p["name"], p["version"], [(t["name"], t["kind"][0], t["doctest"]) for t in p["targets"]]) for p in m["packages"] if p["id"] in ids]'
```

- Run from the **workspace root** (`workspace_root` in the metadata). A root manifest with both `[package]` and
  `[workspace]` has `default-members` = the root package only — plain `cargo test` there silently skips the other
  members (verified: an `xtask` member was skipped), so always pass `--workspace` unless `package:` was given.
  `--workspace` together with `-p` runs everything (verified) — pass one or the other, never both.
- An unknown `package:` name makes cargo exit `101` with `package ID specification … did not match` — report it as a
  bad selector.
- **`Cargo.lock`**: when it exists, pass `--locked` on every run (CI does, and a gate must not rewrite the lockfile).
  If cargo fails with `cannot update the lock file … because --locked was passed to prevent this`, stop and report
  "`Cargo.lock` is out of date with `Cargo.toml` — run `cargo update -w`" (verified message); ask before running it.
  With **no** `Cargo.lock` (a library that doesn't commit one) `--locked` fails with `cannot create the lock file`
  (verified) — drop `--locked` for that run and say the dependency versions were resolved fresh.
- **Runner**: `cargo nextest --version` succeeds → **nextest**; otherwise **`cargo test`** — say "cargo-nextest not
  installed; using `cargo test`" (never install it). An `expr:` filterset with `cargo test` can't be honored: report
  that and ask for name substrings instead.
- `rust-toolchain.toml` pins the toolchain; rustup installs it on first use — a download failure is an environment
  problem, not a test result.

## Step 2 — Build the command

Write logs under `target/iru-rust-test/` (`target/` is gitignored) — `mkdir -p target/iru-rust-test`.

**nextest** — JUnit comes from a profile. If `.config/nextest.toml` already defines a profile with a `junit` table
(this catalog's scaffold ships `[profile.ci.junit] path = "junit.xml"`), use it (`--profile ci`, report at
`target/nextest/ci/junit.xml`) so the run matches CI. Otherwise inject one through a tool config file, which nextest
layers under the repository's own config (the project's `[profile.default]` settings — test groups, timeouts, slow
thresholds — still apply, verified):

```bash
mkdir -p "$TMPDIR/iru-rust-test"
cat > "$TMPDIR/iru-rust-test/nextest.toml" <<'EOF'
[profile.iru-rust-test]
fail-fast = false
[profile.iru-rust-test.junit]
path = "junit.xml"
EOF
rm -f target/nextest/iru-rust-test/junit.xml     # never parse a stale report from an earlier run
cargo nextest run --locked --color never <--workspace | -p NAME> [--lib|--bins|--tests|--test STEM] \
  [--all-features|--features a,b] [--run-ignored all|only] \
  --tool-config-file "iru-rust-test:$TMPDIR/iru-rust-test/nextest.toml" --profile iru-rust-test \
  [<filter> ...] [-E '<filterset>'] > target/iru-rust-test/nextest.log 2>&1
```

(`--tool-config-file` needs an absolute path; with the project's `ci` profile pass `--no-fail-fast` instead, and
delete `target/nextest/ci/junit.xml` first.) Several positional filters are OR-ed (verified). Add `--no-tests=warn`
only when `package:`/`target:` legitimately selects a target with no tests (an `xtask` binary); never to hide an
unmatched filter.

**cargo test** (fallback):

```bash
cargo test --locked --color never --no-fail-fast <--workspace | -p NAME> [--lib|--bins|--tests|--test STEM] \
  [--all-features|--features a,b] -- [<filter> ...] [--include-ignored|--ignored] > target/iru-rust-test/cargo-test.log 2>&1
```

Filters go **after** `--` (libtest accepts several, OR-ed — verified). Without `--lib/--bins/--tests/--test`, `cargo
test` also runs the doctests in the same invocation; with them, it doesn't (Step 4 then runs them separately unless
`target:` excluded them). libtest's JSON output (`-Z unstable-options --format json`) is nightly-only — it fails on
stable with `the option Z is only accepted on the nightly compiler` (verified), so the console output is parsed.

## Step 2a — Terminal-UI captures (UI contract)

Applies per Step 0's `ui:`. A Ratatui project's UI tests are ordinary unit tests that write the rendered buffer as
`.txt` through the crate's `src/tui/ui_screenshots.rs` helper (the coding skill's `reference/ui-implementation.md`);
this gate only prepares the directory, runs them with the rest, and checks what they left. No lock — they need no
terminal, container or device.

1. **Directory**: `UI_SCREENSHOTS_DIR` from the environment when already set (an absolute path), else
   `<repository root>/.ui-screenshots` (`git rev-parse --show-toplevel`). Prefix the Step 2/Step 4 commands with
   `UI_SCREENSHOTS_DIR="<that absolute path>"` so every test binary resolves the same place.
2. **Clear it only for a full run** — no positional filters, no `expr:`, no `package:`/`target:` narrowing:
   `mkdir -p "<dir>" && find "<dir>" -mindepth 1 -delete`, so the directory holds exactly this run. A scoped run never
   clears it (it adds to or overwrites the captures it re-renders).
3. **Level**: read `accessibility:` from `.ui-quality.yml` at the repository root (`grep -E '^accessibility:'`);
   missing file or key → `enforce`. Under `enforce` a violation fails its test (it shows up as a normal failure whose
   message names the rule ids); under `report` the tests append `<target>\t<rule-id>: <message>` lines to
   `<dir>/a11y-report.txt`; under `off` nothing is checked (reported "accessibility checks off"), and under `none` — the
   project opted out entirely — nothing is checked either (reported "accessibility: opted out"); neither is ever
   "unverified" or a failure.
4. **After the run**, validate and count with stdlib Python (script kept outside the repository, e.g.
   `$TMPDIR/iru-rust-test/uicheck.py`, run as `python3 -I <script> "<dir>"`):

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
   `tui-keyboard`, `tui-no-color`), not an audit.

## Step 3 — Serialize container-backed selections behind the repository lock

Applies when `ignored:` is `include` or `only`. Skip it for the default `exclude`.

1. **Docker first**: `docker info >/dev/null 2>&1`. If it fails, don't run the ignored tests: report them as
   **unverified (Docker unavailable)** — never as passed or failed — and, for `include`, still run and report the
   non-ignored tests (`ignored: exclude`). A test that needs an already-running service rather than Docker is still
   container-backed; if its host is unreachable report "test service unreachable at `<host>`".
2. **`lock: held`** → the caller holds the lock; go straight to the run.
3. Otherwise acquire it at the **repository root** (`git rev-parse --show-toplevel`, else the current directory) — a
   directory, because `mkdir` either creates it or fails atomically (never a lock *file* written with `Write`):

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with "iru-rust-test", the selection, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. If `.integration-test.lock/` isn't in the root `.gitignore`, say so in the report
     (this skill doesn't edit `.gitignore`).
   - **`HELD`** — say the run is waiting (naming the holder from `cat .integration-test.lock/owner`) and retry the
     `mkdir` roughly every 30 seconds. Never run the selection anyway — containers and ports are shared.
   - **Stale**: `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory → report the owner file,
     break it (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and say so.
   - **Timeout**: still held after ~45 minutes and not stale → **unverified — blocked by a concurrent integration run**.
4. Run the selection — one invocation, in the foreground, never alongside another test run.
5. **Release** on **every** exit path (passed, failed, build error, interruption), only if this run acquired it:
   `rm -f .integration-test.lock/owner && rmdir .integration-test.lock`.

## Step 4 — Doctests

Run unless `target:` is `lib`/`bins`/`tests`/`<stem>`, or the `cargo test` fallback already ran them (no target flag):

```bash
cargo test --locked --color never --doc <--workspace | -p NAME> [features] -- [<filter> ...] > target/iru-rust-test/doctest.log 2>&1
```

- nextest can't run doctests (`cargo nextest run --doc` → `unexpected argument '--doc'`, exit `2` — verified).
- Doctests exist only for **library** targets: `cargo test --doc` on a bin-only package fails with `no library targets
  found in package` (exit `101`), and examples in `src/main.rs` doc comments are never compiled or run (verified).
  Skip such packages and say "no doctests (bin-only crate — doc examples in `main.rs` aren't tested)"; this catalog's
  CLI scaffold keeps its logic in `lib.rs` for exactly this reason.
- With `--workspace`, packages without a library are skipped silently by cargo.
- `target: doc` runs only this step.

## Step 5 — Interpret exit codes and parse the results

| Runner | Exit | Meaning |
|---|---|---|
| nextest | `0` | all selected tests passed |
| nextest | `100` | at least one test failed |
| nextest | `4` | **no tests to run** — the filters/target matched nothing (`error: no tests to run`); JUnit has `tests="0"` |
| nextest | `101` | **build failed** (`could not compile`) or a bad `-p` — no new JUnit written |
| nextest | `2` | usage error (unknown flag, bad filterset) |
| cargo test | `0` | passed — **including when a filter matched nothing** (every `test result:` line shows `0 passed … N filtered out`) |
| cargo test | `101` | a test failed **or** the build failed — tell them apart from the log (Step 5 parser: `BUILD` lines) |

Parse with stdlib Python (`python3` ≥ 3.8):

```bash
python3 - junit target/nextest/iru-rust-test/junit.xml target/iru-rust-test/nextest.log <<'PY'   # nextest
# or: python3 - libtest target/iru-rust-test/cargo-test.log <<'PY'                                 # cargo test / doctests
import re
import sys
import xml.etree.ElementTree as ET


def first_cause(text):
    """libtest panic output: 'thread ... panicked at file:line:col:' then the message line(s)."""
    lines = [ln.strip() for ln in (text or "").splitlines() if ln.strip()]
    for i, ln in enumerate(lines):
        if " panicked at " in ln:
            where = ln.split(" panicked at ", 1)[1].rstrip(":")
            return f"{lines[i + 1] if i + 1 < len(lines) else ''} ({where})"
    return lines[0] if lines else ""


def junit(path, summary):
    root = ET.parse(path).getroot()
    cases = list(root.iter("testcase"))
    fails = [(c, c.find("failure")) for c in cases if c.find("failure") is not None]
    errs = [(c, c.find("error")) for c in cases if c.find("error") is not None]
    flaky = [c for c in cases if c.find("flakyFailure") is not None or c.find("rerunFailure") is not None]
    m = re.search(r"(\d+) tests? run: (\d+) passed(?:, (\d+) failed)?.*?(\d+) skipped", summary or "")
    skipped = m.group(4) if m else "?"
    print(f"tests={len(cases)} passed={len(cases) - len(fails) - len(errs)} failures={len(fails)} "
          f"errors={len(errs)} skipped={skipped} flaky={len(flaky)}")
    for kind, items in (("FAILURE", fails), ("ERROR", errs)):
        for c, el in items:
            print(f"{kind} {c.get('classname')} {c.get('name')} — {first_cause(el.text or el.get('message'))}")


def libtest(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    unit, totals = None, {"passed": 0, "failed": 0, "ignored": 0, "filtered": 0}
    failed, blocks = [], {}
    for ln in text.splitlines():
        if m := re.match(r"^\s+(?:Running (?:unittests )?|Doc-tests )(\S+)", ln):
            unit = m.group(1)
        elif m := re.match(r"^test (.+?) \.\.\. FAILED$", ln):
            failed.append((unit, m.group(1)))
        elif m := re.match(r"^test result: \w+\. (\d+) passed; (\d+) failed; (\d+) ignored; \d+ measured; (\d+) filtered out", ln):
            for k, v in zip(("passed", "failed", "ignored", "filtered"), m.groups()):
                totals[k] += int(v)
    for m in re.finditer(r"(?ms)^---- (.+?) stdout ----\n(.*?)(?=^---- |^failures:$|\Z)", text):
        blocks[m.group(1)] = m.group(2)
    print("passed={passed} failed={failed} ignored={ignored} filtered_out={filtered}".format(**totals))
    for unit, name in failed:
        print(f"FAILURE {unit} {name} — {first_cause(blocks.get(name, ''))}")
    for ln in text.splitlines():
        if ln.startswith("error[") or ln.startswith("error: could not compile"):
            print(f"BUILD {ln}")


if sys.argv[1] == "junit":
    junit(sys.argv[2], open(sys.argv[3]).read() if len(sys.argv) > 3 else "")
else:
    libtest(sys.argv[2])
PY
```

Notes on the shapes (verified):

- nextest JUnit: one `<testsuite>` per test binary (`democli` = the lib's unit tests, `democli::cli` = `tests/cli.rs`,
  `democli::bin/democli` = unit tests in the binary); `<testcase name="tests::classify_fails" classname="democli">`.
  The `<failure message>` attribute only says `thread '…' panicked at src/lib.rs:44:9`; the assertion text is in the
  element body — the parser prints `assertion `left == right` failed: zero is not positive (src/lib.rs:44:9)`.
  **Ignored tests that didn't run are absent from the JUnit file** (not `<skipped/>`), so the skipped count comes from
  nextest's `Summary [...] 4 tests run: 3 passed, 1 failed, 1 skipped` console line. Retried tests carry
  `<flakyFailure>`/`<rerunFailure>` children — report flaky tests separately from hard failures.
- libtest: `test tests::classify_fails ... FAILED`, a `---- <name> stdout ----` block with the panic, and one
  `test result: …; N filtered out` line per test binary. Ignored tests print `... ignored, <reason>`.
- A filter that matched nothing: nextest exit `4`; `cargo test` exit `0` with every `passed`/`failed` count `0` —
  report "filter `<x>` matched nothing" either way, never "all passed".
- **Environment failures, not test failures** — classify by the cause line: testcontainers-rs without a Docker socket
  panics with `Client(Init(SocketNotFoundError("…/docker.sock")))` (verified, testcontainers 0.27), `Connection
  refused (os error 61/111)` from a database driver, `failed to resolve address` → "integration not run: <cause>".

Leave `target/nextest/` and `target/iru-rust-test/` in place (`target/` is gitignored; CI and Sonar may read the
JUnit file).

## Step 6 — Report results

- State the runner and selection ("cargo-nextest 0.9.148, injected `iru-rust-test` profile, `--workspace`, default
  features, ignored tests excluded (1)"), the counts, and the doctest result separately ("doctests: 3 passed" /
  "no doctests — bin-only crate") — never the JUnit XML or the full console log.
- A selection that matched nothing, a build failure (first `error[E…]` line and file), a stale `Cargo.lock`, or a bad
  `package:` is said plainly — not as 0 passed.
- On failures, list each failing test (`<binary> <test path>`) with its first cause line — nothing more per failure.
- For `ignored: include|only`: whether the lock was acquired, waited for, or held by the caller; an ignored selection
  that couldn't run is **unverified (<reason>)**.
- **UI captures** (Step 2a, when it applied): the directory, whether it was cleared (full run) or kept (scoped run),
  captures per kind (`component=… screen=… flow=…`), malformed names (count + the first few paths), accessibility
  violations as counts per rule id (from failing tests under `enforce`, from `a11y-report.txt` under `report`) — never
  the raw text — and the `.ui-quality.yml` level applied (`enforce` by default when the file is missing).
- Do not fix failing tests, modify source/test code, update `Cargo.lock`, install nextest, or start containers unless
  the user asks for that as a next step.

## Verification notes

Verified on 2026-10-16 with Rust 1.99.1 (stable, aarch64-apple-darwin), cargo-nextest 0.9.148 and testcontainers-rs
0.27.3 against a throwaway workspace in `$TMPDIR/iru-verify/cli/rust-gates/` (outside this repository): a `democli`
package (lib + bin, `tests/cli.rs` with assert_cmd 2.2 driving the binary, one failing unit test, one `#[ignore = "needs
docker"]` test, one doctest) plus an `xtask` member.

- `cargo metadata --no-deps` listed `default-members` = the root package only; `cargo test` without `--workspace`
  skipped `xtask`; `--workspace -p xtask` still ran every member.
- The injected tool-config profile wrote `target/nextest/iru-rust-test/junit.xml`; the run exited `100`; the parser
  printed `tests=4 passed=3 failures=1 errors=0 skipped=1 flaky=0` and `FAILURE democli tests::classify_fails —
  assertion `left == right` failed: zero is not positive (src/lib.rs:44:9)`. The ignored test was absent from the XML.
- nextest: unmatched filter → exit `4` (`error: no tests to run`, JUnit `tests="0"`); `-p xtask` (no tests) → `4`, `0`
  with `--no-tests=pass`/`warn`; `--run-ignored only` ran just the ignored test; `-E 'test(/greet/)'`, `--test cli`,
  `--lib`, `--bins`, `--tests`, `--all-features` and two OR-ed positional filters all selected as documented; a
  compile error → `101`; `-p nope` → `101`; `--doc` → `2` (unsupported).
- `cargo test --workspace --no-fail-fast` exited `101`; the libtest parser printed `passed=4 failed=1 ignored=1` (unit,
  integration and doctests summed) and the same failure line; an unmatched filter exited `0` with all-zero counts; a
  compile error produced `BUILD error: could not compile ...` lines. `-Z unstable-options --format json` is
  rejected on stable. `--color never` kept ANSI escapes out of both logs even with `CARGO_TERM_COLOR=always`.
- `cargo test --doc` ran the doctest; on a bin-only crate it exited `101` (`no library targets found`), and a failing
  example in `main.rs` was never run by `cargo test`.
- testcontainers-rs (`GenericImage` redis, blocking runner, `#[ignore]`): passed in ~2 s with Docker; with
  `DOCKER_HOST=unix:///nonexistent/docker.sock` it panicked with `Client(Init(SocketNotFoundError(...)))`; the lock's
  `mkdir` protocol is the one verified for `iru-python-test`.
- Step 2a (UI captures, added for the UI contract): the validation script was run with Python 3 against a sample
  tree (valid screen/flow captures counted, an upper-case state and a stray file reported as malformed, report-mode
  rule ids counted); the cargo side — `UI_SCREENSHOTS_DIR` reaching the test binaries, the helper's captures — was not
  run (no Rust toolchain on the authoring machine) and is unverified.
- Not verified: Windows paths/`.exe` test binaries, nextest archive/partitioned runs, `cargo test` with
  `harness = false` targets (custom output isn't parseable — report their exit code only), and a project whose own
  `ci` profile sets `junit.store-success-output`.

## Sources

- irurueta docs: programming-languages/rust/testing-and-benchmarking.adoc (unit/integration/doc tests, `cargo test`
  selection flags, `#[ignore = "reason"]`, `--ignored`/`--include-ignored`, combined doctests, logic in `lib.rs`)
- irurueta docs: programming-languages/rust/cargo-and-workspaces.adoc (workspaces, `default-members`, `--workspace`,
  `-p`, `Cargo.lock` and `--locked`)
- irurueta docs: ai/cli-for-agents/testing-clis.adoc (end-to-end CLI tests that run the real binary)
- cargo-nextest documentation (nexte.st: JUnit support, tool config files, filtersets, `--no-tests`, exit codes)
