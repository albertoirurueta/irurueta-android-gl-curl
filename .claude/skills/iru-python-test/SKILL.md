---
name: iru-python-test
description: "/iru-python-test [fast|integration|all|ui] [path|node-id|-k expr] - runs a Python project's pytest suite (FastAPI, Django, plain library) through the project's own environment runner, fast container-free tests by default, and reports a compact JUnit-based pass/fail/skip summary. Report-only; never edits code."
model: haiku
allowed-tools: Read Write Bash(uv *) Bash(poetry *) Bash(pdm *) Bash(pipenv *) Bash(.venv/bin/*) Bash(python *) Bash(python3 *) Bash(DJANGO_SETTINGS_MODULE=* *) Bash(UI_SCREENSHOTS_DIR=* *) Bash(docker info *) Bash(command -v *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(test *) Bash(date *) Bash(mkdir -p build*) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Bash(rm -f build/test-results/*)
---

# Python Test

Run the project's pytest suite through whichever environment runner it actually uses, scoped to whatever the user
asked for, and report a compact pass/fail summary parsed from pytest's own JUnit XML log. This skill only runs tests
and reports results — it does not fix failures, modify source/test code, install dependencies, or start databases
unless asked to as a follow-up.

## Step 0 — Parse the arguments

The argument string is a selector plus optional `key: value` lines from a calling skill:

- **Leading keyword** (optional, at most one): `fast` (default when absent), `integration`, `all`, or `ui`. `unit` is
  accepted as a synonym of `fast`. `ui` is valid only for a **Django project with pytest-django and
  pytest-playwright** whose config declares a `ui` marker; anywhere else (FastAPI, a plain package, no
  pytest-playwright) stop and report "no `ui` selection in this project" — never fall back to another selection.
  `all` never includes `ui`; a caller asks for it explicitly.
- **Path / node id** (zero or more): `tests/test_books.py`, `tests/services/`,
  `tests/test_books.py::TestCreate::test_rejects_duplicate`, `apps/catalog/tests/test_views.py::test_list`.
- **`-k "<expression>"`** (optional): pytest's keyword expression (`-k "create and not slow"`).
- **`lock: held`** — the caller (e.g. a `*-code-one-task-group` skill) already holds `.integration-test.lock`;
  don't acquire or release it here (Step 4).

Everything combines: `integration tests/integration/test_repo.py -k upsert` runs only matching integration tests. A
`ui` run with no path, node id or `-k` is a **full UI run**; with any of them it is a **scoped UI run** (Step 5b).

## Step 1 — Detect the environment runner (never `pip install` anything)

Check, in order — the first match wins:

| Signal | Runner | Prefix |
|---|---|---|
| `uv.lock` (or `[tool.uv]` in `pyproject.toml`) and `command -v uv` | **uv** | `uv run --frozen` |
| `poetry.lock` and `command -v poetry` | **Poetry** | `poetry run` |
| `pdm.lock` and `command -v pdm` | **PDM** | `pdm run` |
| `Pipfile.lock` and `command -v pipenv` | **Pipenv** | `pipenv run` |
| `.venv/bin/python` exists (`.venv\Scripts\python.exe` on Windows) | **project venv** | `.venv/bin/python -m` |
| none of the above | **plain interpreter** | `python -m` (or `python3 -m`) — warn: "no project environment found; using the interpreter on PATH, results may not match CI" |

- `uv run --frozen` syncs the environment from `uv.lock` (dev group included) without rewriting the lockfile — never
  plain `uv run` here, which can silently relock and dirty `uv.lock`. If `--frozen` fails because the lockfile is
  missing a dependency `pyproject.toml` now requires, report "`uv.lock` is out of date — run `uv lock`" (ask before
  running it).
- With the `uv`/`poetry`/`pdm`/`pipenv` prefixes call `pytest` directly (`uv run --frozen pytest …`); with
  `.venv/bin/python -m` or `python -m` call `pytest` as a module (`python -m pytest …`). Below, `<pytest>` means the
  resolved form.
- A lockfile whose tool isn't installed (e.g. `poetry.lock` but no `poetry`) → fall through to `.venv`, and say so.

**Confirm pytest is actually wired** before trusting any count: `<prefix> python -c "import pytest"` must succeed.
If it doesn't, **stop and report "no test runner wired — pytest is not installed in <environment>"** (name the
dependency group to add it to). Never report "0 tests". A project that only has `unittest`/`manage.py test` suites
still runs under pytest (pytest collects `unittest.TestCase` and, with pytest-django, Django `TestCase` classes); a
Django project without pytest-django is reported as "pytest-django not installed — Django tests need it (or run
`python manage.py test`)" instead of letting every test fail with `ImproperlyConfigured`.

## Step 2 — Read the pytest configuration and detect plugins

Read the pytest config the way pytest does — the first of `pytest.toml`/`.pytest.toml` (pytest 9), `pytest.ini`,
`.pytest.ini`, `pyproject.toml` `[tool.pytest.ini_options]` (or pytest 9's native `[tool.pytest]`), `tox.ini`
`[pytest]`, `setup.cfg` `[tool:pytest]` — for `testpaths`, `addopts` (`--strict-markers`, `--reuse-db`, `-n auto`,
`--cov`), `markers`, `asyncio_mode`, and `DJANGO_SETTINGS_MODULE`. Then detect, from the dependency groups/lockfile
or `<prefix> python -c "import importlib.util as u; print([n for n in ('pytest_django','pytest_asyncio','anyio','xdist','pytest_cov','testcontainers') if u.find_spec(n)])"`:

- **pytest-django** — settings resolve from `--ds` > the `DJANGO_SETTINGS_MODULE` environment variable > the
  config's `DJANGO_SETTINGS_MODULE` key. If none is set, look for `config/settings/test.py` (or `<project>/settings/test.py`,
  `settings_test.py`) and pass `--ds=<module>`, saying so. Don't override a configured value. `--reuse-db` in
  `addopts` is fine for a run, but after a migration change say "re-run with `--create-db` if the schema looks stale".
- **Async** — anyio's plugin is registered automatically when `anyio` is installed (`@pytest.mark.anyio` tests plus an
  `anyio_backend` fixture); pytest-asyncio needs `@pytest.mark.asyncio` unless `asyncio_mode = "auto"`. Nothing to
  pass — but an `async def` test that fails with `async def functions are not natively supported` means no async
  plugin claims it (missing marker, or the plugin isn't installed): report it as a wiring problem, not an assertion
  failure.
- **pytest-xdist** — leave the project's own `-n` setting alone; never add `-n` yourself. Never pass `-n` to the
  integration selection (Step 4) even when `addopts` has it — override with `-p no:xdist` only if the project's CI
  does the same; otherwise run as configured and say so.

## Step 3 — Classify fast vs. integration and build the command

**Integration split** — in this order:

1. An `integration` marker declared in the config's `markers` (or registered in a `conftest.py` via
   `config.addinivalue_line("markers", "integration…")`; `<pytest> --markers | grep -i integration` confirms it) →
   fast = `-m "not integration"`, integration = `-m integration`. A declared `ui` marker is always excluded from fast
   (`-m "not integration and not ui"`) and is the `ui` selection (`-m ui`). If the project also declares other heavyweight
   markers it excludes from its everyday run (e.g. Django's `browser` for Playwright tests, `e2e`, `slow`), mirror
   the CI/`addopts` exclusion for `fast` (`-m "not integration and not browser"`) and list what was excluded.
2. No marker but a `tests/integration/` (or `*/tests/integration/`) directory → fast = `--ignore=<dir>`,
   integration = `<dir>`.
3. Neither → there is no split: `fast` runs everything. If `testcontainers` is installed (or a test imports
   `testcontainers`), warn "container-backed tests aren't marked — they run in the fast selection" and treat the whole
   run as container-backed for Step 4.

**Command** (`[paths/node ids]` and `-k` from Step 0 appended; `-p no:cacheprovider` keeps the run from writing
`.pytest_cache`):

```bash
mkdir -p build/test-results
# fast (default)
<pytest> -m "not integration" [paths] [-k "<expr>"] -p no:cacheprovider --junitxml=build/test-results/pytest.xml
# integration
<pytest> -m integration [paths] [-k "<expr>"] -p no:cacheprovider --junitxml=build/test-results/pytest-integration.xml
# all = run the fast command, then the integration one (two logs, two summaries) — never one combined run, so the
# fast result stands on its own when the integration stage is unverified
# ui (Django + pytest-playwright only; <shots> = absolute UI_SCREENSHOTS_DIR, default <repo root>/.ui-screenshots)
UI_SCREENSHOTS_DIR=<shots> <pytest> -m ui [paths] [-k "<expr>"] -p no:cacheprovider --junitxml=build/test-results/pytest-ui.xml
```

For `ui`, keep the project's own pytest-playwright options (`--browser`, `--headed`, `--screenshot`, `--output`) from
`addopts`; never add `--headed`. Record the output directory (`--output`, default `test-results/` under the
rootdir) — Step 5b collects from it.

Don't add `--cov` here (that's `iru-python-coverage`'s job); if the project's `addopts` already includes `--cov`,
add `--no-cov` so a coverage `fail_under` can't turn a green test run red — say so in the report.

## Step 4 — Serialize container-backed selections behind the repository lock

Applies to the `integration` selection, the integration half of `all`, the `ui` selection (it starts a live server
and a browser), and a `fast` run Step 3 classified as container-backed. Skip it for pure fast runs.

1. **Docker first**: `docker info >/dev/null 2>&1`. If it fails, **don't run the selection**: report it as
   **unverified (Docker unavailable)** — never as passed, never as N failed tests — and for `all` still report the
   fast half. (A test that only needs an already-running database service, e.g. CI's `services: postgres`, is still
   "integration"; if its host is unreachable, report "test database unreachable at `<host>`".)
   **For `ui`, probe the browser instead** (unless the UI tests also start containers):
   `<prefix> python -c "from playwright.sync_api import sync_playwright
   with sync_playwright() as p: p.chromium.launch().close()"` (the browser the project's `--browser` names). If it
   fails with `BrowserType.launch: Executable doesn't exist` (verified), **don't run the selection**: report
   **unverified (Playwright browser not installed — run `<prefix> playwright install chromium`)**; never install it
   here.
2. **`lock: held`** → the caller holds the lock; skip to the run.
3. Otherwise acquire the lock at the **repository root** (`git rev-parse --show-toplevel`, else the current
   directory) — a directory, because `mkdir` either creates it or fails, atomically; a lock *file* written with
   `Write` has a read-then-write window and must not be used instead:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — write `.integration-test.lock/owner` (via `Write`) with "iru-python-test", the selector, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the root `.gitignore`; if it isn't,
     say so in the report (this skill doesn't edit `.gitignore`).
   - **`HELD`** — say the run is waiting (naming the holder from `cat .integration-test.lock/owner`), then re-run the
     `mkdir` roughly every 30 seconds. Never run the selection anyway, not even "one quick test" — the containers and
     databases are shared.
   - **Stale lock**: if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Report what the owner file says, break it (`rm -f .integration-test.lock/owner && rmdir
     .integration-test.lock`), acquire fresh, and say so.
   - **Wait timeout**: still held after ~45 minutes and not stale → report **unverified — blocked by a concurrent
     integration run**.
4. Run the selection — exactly one pytest invocation, in the foreground, never alongside another test run.
5. **Release** as soon as it finishes, on **every** exit path (passed, failed, environment failure, interruption):

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Only release a lock this run acquired — never one taken under `lock: held`.

## Step 5 — Interpret the exit code and parse the JUnit log

pytest's exit codes (verified, pytest 9.1): `0` all passed, `1` some tests failed, `2` interrupted (a collection
error stops the session), `3` internal error, `4` usage error (e.g. `ERROR: file or directory not found:
tests/nope.py`, an unknown option such as `--cov` without pytest-cov), `5` **no tests collected** (an unmatched `-k`,
node id, or marker selection). Exit `4`/`5` still write a JUnit file with `tests="0"` — so `0 tests` is "selector
`<x>` matched nothing" (exit 5) or "bad invocation: <first `ERROR:` line>" (exit 4), never "all passed".

Parse the log with the project's own interpreter (stdlib only; for `ui` the log is `pytest-ui.xml`):

```bash
<prefix> python - build/test-results/pytest.xml <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
t = {k: sum(int(s.get(k, 0)) for s in suites) for k in ("tests", "failures", "errors", "skipped")}
t["passed"] = t["tests"] - t["failures"] - t["errors"] - t["skipped"]
print("tests={tests} passed={passed} failures={failures} errors={errors} skipped={skipped}".format(**t))
for case in root.iter("testcase"):
    for kind in ("failure", "error"):
        el = case.find(kind)
        if el is not None:
            lines = (el.get("message") or el.text or "").strip().splitlines()
            if lines[:1] == ["collection failure"]:  # import/syntax error while collecting a module
                lines = [ln[1:].strip() for ln in (el.text or "").splitlines() if ln.startswith("E ")] or lines
            name = "::".join(p for p in (case.get("classname"), case.get("name")) if p)
            print(f"{kind.upper()} {name} — {lines[0] if lines else ''}")
PY
```

(With the `.venv/bin/python -m` / `python -m` prefixes, the interpreter is `.venv/bin/python` / `python`.) The
`message` attribute holds the assertion line (`AssertionError: fine of 3 days`); `<failure>` is an assertion failure,
`<error>` an error in a fixture/setup/teardown or a collection failure (`classname` empty, `name` = the module,
message `collection failure` — the parser pulls the real `ModuleNotFoundError …` line from the body). Deselected tests
(`-m`) don't appear in the log; skipped ones do. If no JUnit file was written at all, the run died before pytest
started (missing interpreter, broken `conftest.py` import at startup) — report that cause from the console output.

**Environment failures, not test failures** — classify a failure/error by its first line before reporting it:
`docker.errors.DockerException: Error while fetching server API version` (verified: what testcontainers raises when
the Docker socket is missing), `Could not connect to Ryuk`, `psycopg.OperationalError: connection … refused`,
`asyncpg … ConnectionRefusedError`, `django.db.utils.OperationalError: could not connect to server`,
`django.core.exceptions.ImproperlyConfigured` (settings not found) → report "integration not run/aborted:
<cause>" (or "Django settings not configured") rather than N failed tests. For `ui`:
`BrowserType.launch: Executable doesn't exist` on setup → **unverified (browser not installed)**;
`SynchronousOnlyOperation` → the UI conftest lacks `DJANGO_ALLOW_ASYNC_UNSAFE` (a wiring problem); a failure whose
first line starts `accessibility violations:` is an enforced accessibility failure — report its rule ids (Step 5b). `django.db.utils.OperationalError: no such
table` is different: a missing migration or `--reuse-db` with a stale schema — report it as such.

The JUnit files under `build/test-results/` (`pytest.xml`, `pytest-integration.xml`, `pytest-ui.xml`) are the paths CI and `sonar.python.xunit.reportPath` use; leave them in
place when `build/` is gitignored, otherwise delete them after parsing (`rm -f build/test-results/pytest*.xml`).

## Step 5b — `ui` only: screenshots and accessibility

The UI contract keeps every capture under one directory,
`<shots>/<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png`, written by the project's
`tests/ui/ui_screenshots.py` helper, which also appends one JSON line per axe scan to `<shots>/a11y-report.jsonl`.

- **Before the run** (inside the lock): on a **full** UI run clear the directory —
  `find <shots> -mindepth 1 -delete` when it exists — so it holds exactly this run; on a **scoped** run never clear
  it, and record the current line count of `<shots>/a11y-report.jsonl` (`0` when absent) so only this run's scans
  are counted.
- **After the run**, collect, validate and summarize with the project's interpreter (pytest-playwright cleans its
  output directory at session start, so whatever is there came from this run):

```bash
<prefix> python - <shots> <output dir> <a11y lines before> <<'PY'
import json, re, shutil, sys
from pathlib import Path

shots, output, a11y_from = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
NAME = re.compile(r"^(component|screen|flow)/[A-Za-z0-9][A-Za-z0-9-]*/(\d{2}-)?[a-z0-9]+(-[a-z0-9]+)*"
                  r"--(ltr|rtl)--(light|dark|high-contrast|no-color)(--[a-z0-9]+(-[a-z0-9]+)*)*\.(png|txt)$")
moved, evidence = 0, 0
for f in sorted(output.rglob("*.png")) if output.is_dir() else []:
    rel = f.relative_to(output).as_posix()
    if NAME.match(rel):                      # a contract-named capture written under the wrong root
        (shots / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.move(f, shots / rel)
        moved += 1
    else:                                    # --screenshot failure evidence (test-failed-N.png), not a state
        evidence += 1
counts, bad = {}, []
for f in sorted(shots.rglob("*")) if shots.is_dir() else []:
    if f.is_dir() or f.name == "a11y-report.jsonl":
        continue
    rel = f.relative_to(shots).as_posix()
    if NAME.match(rel):
        counts[rel.split("/")[0]] = counts.get(rel.split("/")[0], 0) + 1
    else:
        bad.append(rel)
print(f"screenshots={counts} moved_from_output={moved} failure_screenshots={evidence}")
for rel in bad:
    print(f"MALFORMED {rel}")
rules, levels, scans = {}, set(), 0
report = shots / "a11y-report.jsonl"
for line in report.read_text(encoding="utf-8").splitlines()[a11y_from:] if report.is_file() else []:
    entry = json.loads(line)
    scans += 1
    levels.add(entry["level"])
    for v in entry["violations"]:
        rules[v["id"]] = rules.get(v["id"], 0) + v["nodes"]
print(f"a11y_scans={scans} levels={sorted(levels)} violations={sum(rules.values())} rules={rules}")
PY
```

- **The level applied** comes from `.ui-quality.yml` at the repository root (`grep -E '^(accessibility|directions|themes):'`;
  missing file = `enforce`, `[ltr, rtl]`, `[light, dark]`). With `enforce`, violations already failed their tests
  (first line `accessibility violations: <rule>×<nodes>, …`); with `report` they are listed, never failures; with
  `off` or `none`, `a11y_scans=0` is expected — report "accessibility checks off" (`off`) or "accessibility: opted
  out" (`none`, the project opted out entirely), never "unverified" and never a failure. Scans present but no
  `a11y-report.jsonl` lines at all with `enforce` or `report` means the tests don't call the helper's
  `check_accessibility` — say so.
- Failure screenshots under the output directory are reported by count and path only, never copied into
  `<shots>` (they are not contract states).

## Step 6 — Report results

- State the runner and selection (e.g. "pytest via `uv run --frozen`, pytest-django `config.settings.test`, fast
  selection `-m "not integration"`, 1 integration test deselected") and the counts — never the JUnit XML or the full
  console log.
- For `all`, report fast and integration separately, plus whether the lock was acquired, waited for, or held by the
  caller. An integration stage that didn't run is **unverified (<reason>)** — Docker unavailable, blocked by a
  concurrent run, database unreachable.
- A selector that matched nothing (exit 5) or a bad invocation (exit 4) is said plainly — not as 0 passed.
- For `ui`: the full-vs-scoped run (and whether `<shots>` was cleared), the screenshot count per kind, every
  malformed name, captures moved in from the output directory, failure screenshots left there, the accessibility
  level applied with the violation count and rule ids (`image-alt×3, color-contrast×1` — never the raw axe JSON),
  and **unverified (<reason>)** when the browser was missing or the lock timed out.
- On failures, list each failing test (`classname::name`) with the first line of its message — nothing more per
  failure — and count `<error>`s separately from assertion failures. Name wiring problems (no async plugin, no
  pytest-django settings) as such.
- Do not fix failing tests, modify source/test code, install dependencies, relock, or start containers/databases
  unless the user asks for that as a next step.

## Verification notes

Verified with uv 0.12.8, CPython 3.13, pytest 9.1.1, pytest-cov 7.1.0, anyio 4.15.1, testcontainers 4.15.0, Django
6.1.2 + pytest-django 4.14.0, in `$TMPDIR/iru-verify/python/gates/{lib,dj}` (outside this repository):

- `uv run --frozen pytest -m "not integration" -p no:cacheprovider --junitxml=build/test-results/pytest.xml` on a
  suite with one pass, one failure, one skip, an `@pytest.mark.anyio` test and one deselected integration test
  exited `1`; the parser printed `tests=4 passed=2 failures=1 errors=0 skipped=1` and the failure as
  `tests.test_calc.TestFine::test_fails — AssertionError: fine of 3 days`. `.venv/bin/python -m pytest` gave the
  same result.
- An unmatched `-k` exited `5`, a missing path exited `4` (`ERROR: file or directory not found`), both with a
  `tests="0"` log; a module with a bad import exited `2` and the parser reported
  `ERROR tests.test_broken — ModuleNotFoundError: No module named 'nonexistent_mod'`. An unmarked `async def` test
  failed with `async def functions are not natively supported`.
- `-m integration` with a testcontainers `PostgresContainer("postgres:18-alpine", driver="psycopg")` test passed in
  ~10 s with Docker running; with `DOCKER_HOST` pointing at a missing socket it failed with
  `docker.errors.DockerException: Error while fetching server API version` (classified above as an environment
  failure) and `docker info` exited `1`. The lock's `mkdir` printed `ACQUIRED` then `HELD`; `rm -f …/owner && rmdir`
  released it. `testcontainers.postgres` emits a `DeprecationWarning` (use `testcontainers.community.postgres`
  imports in new code) — fatal under `filterwarnings = ["error::DeprecationWarning"]`.
- pytest-django picked up `DJANGO_SETTINGS_MODULE = "config.settings.test"` from `[tool.pytest.ini_options]`
  (SQLite in-memory); a missing migration surfaced as `OperationalError: no such table: books_book`, and `--ds=…`
  overrode the config.
- `ui` (in `$TMPDIR/iru-verify/django/iru-django-code-one-task`, Django 6.1.2, pytest-playwright 0.10.0, playwright
  1.64.0 + Chromium headless shell, axe-playwright-python 0.1.8): `UI_SCREENSHOTS_DIR=… uv run --frozen pytest -m ui
  -p no:cacheprovider --junitxml=build/test-results/pytest-ui.xml` captured the ltr/rtl × light/dark matrix through
  the helper; an enforced violation failed with `AssertionError: accessibility violations: button-name×1, image-alt×1`;
  the Step 5b script counted `screenshots={'component': 12, 'screen': 1}`, moved one contract-named PNG in from
  `test-results/`, counted one `--screenshot only-on-failure` capture as failure evidence, flagged `Default_LTR.png`
  as malformed, and summed the rule ids. With `PLAYWRIGHT_BROWSERS_PATH` pointing at an empty directory the probe and
  every test failed on setup with `BrowserType.launch: Executable doesn't exist`; pytest-playwright removed a
  pre-existing `test-results/` at session start. `-m "not integration and not ui"` deselected every UI test.
- Still unverified: the Poetry, PDM and Pipenv prefixes (not installed locally), pytest-asyncio's `asyncio_mode`
  handling, pytest-xdist under the integration selection, and Windows paths.

## Sources

- irurueta docs: programming-languages/python/testing.adoc (pytest discovery, `-k`, node ids, markers,
  `--strict-markers`)
- irurueta docs: backend/fastapi/testing.adoc (anyio marker + `anyio_backend`, `TestClient`/`AsyncClient`, SQLite
  unit tier vs. PostgreSQL integration tier)
- irurueta docs: web/django/pytest-factories-and-browser-tests.adoc (pytest-django settings resolution,
  `--reuse-db`/`--create-db`, `browser` marker excluded from everyday runs)
- irurueta docs: web/django/testing.adoc (test database behavior, run against the production engine)
- pytest-playwright CLI options (`--browser`, `--output`, `--screenshot`): https://playwright.dev/python/docs/test-runners
- axe-playwright-python: https://github.com/pamelafox/axe-playwright-python
