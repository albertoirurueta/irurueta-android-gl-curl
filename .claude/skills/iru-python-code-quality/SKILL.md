---
name: iru-python-code-quality
description: "/iru-python-code-quality [path ...] runs a Python project's wired Ruff lint/format (or Black), mypy or pyright, Bandit, and for Django djLint and django-upgrade, and reports issues per tool and rule id; paths narrow the run. Unconfigured tools are skipped, not passed; a supplied baseline reports only the diff. Report-only."
model: haiku
allowed-tools: Read Bash(uv *) Bash(poetry *) Bash(pdm *) Bash(pipenv *) Bash(.venv/bin/*) Bash(python *) Bash(python3 *) Bash(ruff *) Bash(mypy *) Bash(pyright *) Bash(bandit *) Bash(djlint *) Bash(django-upgrade *) Bash(command -v *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(head *) Bash(test *) Bash(git ls-files *) Bash(mkdir -p build*) Bash(rm -f build/iru-python-cq-*)
---

# Python Code Quality

Run every static-analysis and style tool this Python project actually has wired — Ruff (lint and format), mypy or
pyright, and, when configured, Bandit and djLint — and report every issue, grouped by tool and by rule id. This skill
only runs checks and reports results — it never fixes issues, applies formatting, regenerates a baseline, or modifies
tool configuration unless asked to as a follow-up.

## Step 0 — Resolve inputs and the runner

`[scope]` (optional) is one or more files or directories (`app/services/books.py`, `apps/catalog/`). Expand shell
globs with `find` first (the tools take paths) and drop paths that don't exist — say so rather than letting a tool
fail on them. No scope → each tool checks what its own configuration lists. If the calling prompt passes a baseline
(per-tool/per-rule-id counts, or an itemized `tool rule file:line` list), keep it for Step 4.

Detect the environment runner exactly as `iru-python-test` Step 1 does (`uv.lock` → `uv run --frozen <tool>`,
`poetry.lock` → `poetry run <tool>`, `pdm.lock` → `pdm run <tool>`, `Pipfile.lock` → `pipenv run <tool>`,
`.venv/` → `.venv/bin/<tool>`, else the tool on `PATH` with a warning that its version may not match the project's
CI). Run the project's pinned tools, never a globally installed one when the project pins its own: a different Ruff
or mypy version reports different rules. Below, `<prefix>` is that resolved form. Also read the CI workflow
(`.github/workflows/*.yml`), `.pre-commit-config.yaml`, `Makefile`/`noxfile.py`/`tox.ini` lint targets — the
project's real invocation (an explicit `mypy src tests`, a `--config-file`, `ruff check --select …`) is the one to
mirror.

## Step 1 — Verify which tools are wired

A tool counts as wired when it is installed in the project environment (`<prefix> <tool> --version` succeeds, or it
is in a dependency group/lockfile) **and** configured or invoked by the project:

| Tool | Configured by | Notes |
|---|---|---|
| **Ruff lint** | `[tool.ruff]`/`[tool.ruff.lint]` in `pyproject.toml`, `ruff.toml`, `.ruff.toml`, or a CI/pre-commit `ruff check` | Installed but unconfigured → Ruff's default rule set (`E4`, `E7`, `E9`, `F`) — run it and say "Ruff defaults (no project config)". Note the `select` list and `per-file-ignores`; `S` = Bandit-derived, `D` = pydocstyle, `DJ` = flake8-django, `FAST` = FastAPI, `ASYNC`, `PT`, `B`, `UP`, … |
| **Ruff format** | `[tool.ruff.format]`, or a CI/pre-commit `ruff format` / `ruff-format` hook, or Ruff configured and no other formatter | If the project formats with **Black** (`[tool.black]`, `black` hook/CI step), check with Black instead and note it. |
| **mypy** | `[tool.mypy]`, `mypy.ini`, `.mypy.ini`, `setup.cfg` `[mypy]`, or a CI/pre-commit `mypy` | Note `strict`, `files`, and `plugins` (`pydantic.mypy`, `mypy_django_plugin.main` with `[tool.django-stubs] django_settings_module`) — the Django plugin imports the settings module, so a broken settings import is a tooling failure, not a code issue. |
| **pyright** (or basedpyright) | `[tool.pyright]`, `pyrightconfig.json`, or a CI step | The PyPI `pyright` wrapper downloads its Node runtime on first use — if that's not possible offline, report "pyright not runnable here". If both mypy and pyright are configured, run both. |
| **Bandit** | `[tool.bandit]` (needs `-c pyproject.toml`), `.bandit`, `bandit.yaml`, or a CI/pre-commit `bandit` | Only when configured — Ruff's `S` rules already cover Bandit's checks (`S608` ≙ `B608`); say "security lint via Ruff `S` rules" when that's the setup. |
| **djLint** | `[tool.djlint]`, `.djlintrc`, or a CI/pre-commit `djlint` hook (Django projects with templates) | `--lint` only reports; `--check` reports formatting without writing. Never `--reformat`. |
| **django-upgrade** | pre-commit `django-upgrade` hook (read its `--target-version` arg) | Only ever with `--check` — without it, it rewrites files in place. |

A tool that isn't wired is **skipped and named in the report** ("pyright: not configured, skipped") — never omitted
silently, never reported as "0 issues". If nothing is wired at all, report "no Python quality tooling wired" and
stop; don't reach for global installs. Recommend (don't fail on) missing expected tools: Ruff and a type checker for
every project, `DJ` rules + django-stubs for Django, `FAST` rules + `pydantic.mypy` for FastAPI.

## Step 2 — Run each wired tool, scoped to `[scope]` when given

Write every machine-readable report under `build/`, prefixed `iru-python-cq-`, and delete them afterwards (`rm -f
build/iru-python-cq-*`; remove `build/` only if this run created it). Exit codes are information, not failures —
always go on and read the report; only a "tool broke" exit (listed per tool) means the report can't be trusted.

1. **Ruff lint** — `<prefix> ruff check --output-format json --exit-zero --force-exclude [paths] > build/iru-python-cq-ruff.json`
   - `--exit-zero` keeps the exit `0` with issues; exit `2` means Ruff itself failed (bad config, unknown rule) —
     report that. `--force-exclude` makes explicitly passed paths honor the config's `exclude`/`extend-exclude`
     (e.g. Django `migrations/`) — without it a scoped run lints excluded files too (verified).
   - Shape: a JSON array of `{"code": "S608", "message": "...", "filename": "/abs/app/x.py", "location": {"row": 5, "column": 9}, "url": ..., "fix": ...}`.
     A file Ruff can't parse appears with code `invalid-syntax` — report it, it blocks every other check on that file.
2. **Ruff format** — `<prefix> ruff format --check --output-format json --force-exclude [paths] > build/iru-python-cq-ruff-format.json`
   - Exit `1` = files would be reformatted, `2` = error (e.g. a syntax error). Each entry has code `unformatted` and
     the first changed line; this skill buckets it as rule id **`format`** (one issue per file).
   - Older Ruff releases without `--output-format` for `format` print `Would reformat: <path>` lines — capture
     stdout+stderr (`> build/iru-python-cq-ruff-format.txt 2>&1`) and parse as `ruff-format-text`.
   - **Black** instead: `<prefix> black --check [paths] > build/iru-python-cq-black.txt 2>&1` (lines `would reformat
     <path>` on stderr; exit `1` = would reformat, `123` = internal error).
3. **mypy** — `<prefix> mypy --output json [paths or the configured files] > build/iru-python-cq-mypy.json`
   - With no scope and no `files` in the config, mypy needs a target — use what the project's CI passes, else `.`.
     Paths on the command line replace `files` but keep every other setting (strictness, plugins, per-module
     overrides).
   - Shape (mypy ≥ 1.11): one JSON object **per line** — `{"file": "app/x.py", "line": 6, "column": 12, "message": "...", "hint": null, "code": "return-value", "severity": "error"}`.
     `severity: "note"` lines are context for the preceding error, not issues. On older mypy, drop `--output json` and
     add `--show-error-codes --no-error-summary --no-pretty` (text `file:line: error: message  [code]`).
   - Exit `1` = type errors, `2` = mypy itself failed (crash, config error, `Duplicate module named …`, a plugin that
     can't import Django settings) — report the first line of stderr as a tooling failure.
   - mypy follows imports and reports errors in imported modules outside the scope (verified: a scoped Django run
     also reported `config/settings/base.py`) — the parser's scope filter drops them.
4. **pyright** — `<prefix> pyright --outputjson [paths] > build/iru-python-cq-pyright.json`
   - Shape: `{"generalDiagnostics": [{"file": "/abs/app/x.py", "severity": "error", "message": "...", "range": {"start": {"line": 5, "character": 12}}, "rule": "reportReturnType"}], "summary": {...}}`
     — `line` is **0-based** (the parser adds 1). Exit `1` = errors; `2`/`3` = fatal/config error.
5. **Bandit** (only when configured) — `<prefix> bandit -q -f json -o build/iru-python-cq-bandit.json [-c pyproject.toml] -r <paths or the configured targets>`
   - Shape: `{"results": [{"test_id": "B608", "filename": "app/x.py", "line_number": 5, "issue_severity": "MEDIUM", "issue_confidence": "LOW", "issue_text": "..."}], "errors": [...], "metrics": {...}}`.
     Report `errors` (files Bandit couldn't parse) separately. Exit `1` = issues found.
6. **djLint** (only when configured) — `<prefix> djlint <template dirs> --lint > build/iru-python-cq-djlint.txt 2>&1`
   (text only: a file-path header line, then `H013 3:0 Img tag should have an alt attribute. …` lines; exit `1` =
   issues). Use the configured `profile`; add `--profile=django` only if the config sets none.
7. **django-upgrade** (only when configured) — `<prefix> django-upgrade --target-version <hook's value, else the
   installed Django major.minor> --check <*.py paths> > build/iru-python-cq-django-upgrade.txt 2>&1` (expand scope or
   `git ls-files '*.py'` to file paths — it takes files, not directories). Lines `Would rewrite <path>`, exit `1` when
   any file would change; parse as `django-upgrade` (rule id `django-upgrade`, one issue per file).

Never run `ruff check --fix`, `ruff format` without `--check`, `black` without `--check`, `djlint --reformat`,
`mypy --install-types`, or django-upgrade without `--check`.

## Step 3 — Parse every report into tool → rule id → issues

Pass `scope=<comma-separated paths>` (omit for an unscoped run) and one `tool=report` pair per tool that ran
(`ruff`, `ruff-format`, `ruff-format-text`, `black`, `mypy`, `pyright`, `bandit`, `djlint`, `django-upgrade`):

```bash
<prefix> python - scope=app/services,app/routers ruff=build/iru-python-cq-ruff.json ruff-format=build/iru-python-cq-ruff-format.json mypy=build/iru-python-cq-mypy.json <<'PY'
import json
import os
import re
import sys
from collections import Counter, defaultdict

cwd = os.path.realpath(os.getcwd())


def rel(path):
    p = os.path.realpath(path) if os.path.isabs(path) else path
    return os.path.relpath(p, cwd) if os.path.isabs(p) else p


def ruff(text):  # ruff check / ruff format --check, --output-format json
    for d in json.loads(text or "[]"):
        code = d.get("code") or "invalid-syntax"
        yield rel(d["filename"]), d["location"]["row"], "format" if code == "unformatted" else code, d["message"]


def mypy(text):  # mypy --output json: one JSON object per line; text fallback for mypy < 1.11
    for line in text.splitlines():
        if line.startswith("{"):
            d = json.loads(line)
            if d["severity"] != "note":
                yield rel(d["file"]), d["line"], d.get("code") or "misc", d["message"]
        elif m := re.match(r"^(.+?):(\d+)(?::\d+)?: error: (.*?)(?:  \[([\w-]+)\])?$", line):
            yield rel(m[1]), int(m[2]), m[4] or "misc", m[3]


def pyright(text):  # pyright --outputjson: 0-based lines
    for d in json.loads(text)["generalDiagnostics"]:
        if d["severity"] in ("error", "warning"):
            yield rel(d["file"]), d["range"]["start"]["line"] + 1, d.get("rule") or d["severity"], d["message"].splitlines()[0]


def bandit(text):  # bandit -f json
    for d in json.loads(text)["results"]:
        yield rel(d["filename"]), d["line_number"], d["test_id"], f"[{d['issue_severity']}] {d['issue_text']}"


def ruff_format_text(text):  # older ruff / black --check: "Would reformat: <path>" / "would reformat <path>"
    for line in text.splitlines():
        if m := re.match(r"^(?:Would reformat:|would reformat) (.+)$", line.strip()):
            yield rel(m[1]), 0, "format", "File would be reformatted"


def django_upgrade(text):  # django-upgrade --check: "Would rewrite <path>"
    for line in text.splitlines():
        if m := re.match(r"^Would rewrite (.+)$", line.strip()):
            yield rel(m[1]), 0, "django-upgrade", "Would rewrite (deprecated Django API)"


def djlint(text):  # djlint --lint (text only): a file header line, then "<CODE> <line>:<col> <message>"
    current = None
    for line in re.sub(r"\x1b\[[0-9;]*m", "", text).splitlines():
        if m := re.match(r"^([A-Z]\d{3}) (\d+):\d+ (.*)$", line):
            yield current, int(m[2]), m[1], m[3]
        elif line.strip() and os.path.isfile(line.strip()):
            current = rel(line.strip())


PARSERS = {"ruff": ruff, "ruff-format": ruff, "ruff-format-text": ruff_format_text, "black": ruff_format_text,
           "mypy": mypy, "pyright": pyright, "bandit": bandit, "djlint": djlint, "django-upgrade": django_upgrade}
args = dict(a.split("=", 1) for a in sys.argv[1:])
scope = [s.rstrip("/") for s in args.pop("scope", "").split(",") if s]
for tool, path in args.items():
    issues = [i for i in PARSERS[tool](open(path).read())
              if not scope or any(i[0] == s or i[0].startswith(s + "/") for s in scope)]
    by_rule = Counter(i[2] for i in issues)
    print(f"{tool}: {len(issues)} issue(s)")
    files = defaultdict(list)
    for f, line, rule, msg in issues:
        files[rule].append(f"{f}:{line} — {msg}")
    for rule, n in by_rule.most_common():
        print(f"  {rule}: {n}")
        for item in files[rule][:20]:
            print(f"    {item}")
PY
```

Paths come out project-relative (Ruff and pyright write absolute ones; macOS `/var` vs `/private/var` is resolved).
The per-rule list is capped at 20 entries — say "(+N more)" when a bucket is larger, and give the full list only on
request.

## Step 4 — Classify, diff against a baseline if given, and report

Bucket every issue by **tool, then rule id** — Ruff code (`F401`, `S608`, `DJ001`, `FAST002`, `D103`), `format`,
mypy error code (`arg-type`, `union-attr`, `no-untyped-def`; `misc` when mypy gives none), pyright rule name, Bandit
test id (`B608`), djLint code (`H013`), `django-upgrade`. Never merge buckets across tools even when they describe the same finding
(Ruff `S608` and Bandit `B608` stay separate). Within Ruff, call out families a caller hands off elsewhere:
**`D*`** (pydocstyle — "docstring", fixed by `iru-python-docstrings`), **`S*`** (security — list first), **`DJ*`** /
**`FAST*`** (framework rules), and `invalid-syntax` (blocks everything else on that file).

**Report**: an overall total across all tools first, then per tool: whether it ran or was skipped (and why), the
config and version in force (e.g. "Ruff 0.17.0, `pyproject.toml`, select E,F,W,I,N,UP,B,S,…", "mypy 2.4, strict,
plugins `mypy_django_plugin.main`"), its total, and one line per rule-id bucket with its count — plus the `file:line
— message` list when the scope is smaller than the whole project, or on request. Tool failures (Ruff exit `2`, mypy
exit `2`, a plugin that can't import settings, pyright unable to start) get their own line with the first error
line, never "0 issues". Never paste the raw JSON reports or diffs.

**Baseline diff** — when the prompt supplies a baseline (a prior run's per-tool/per-rule-id counts or itemized list,
the contract `iru-fastapi-code-one-task-group`/`iru-django-code-one-task-group` use for this skill): report only
issues that are new since the baseline — a rule id whose count increased, a rule id that didn't exist before, or a
new `file` + rule entry when the baseline was itemized (match on tool + rule id + file, not on line numbers or
message text, which shift). A rule id whose count fell or stayed the same is not a regression and is omitted from the
diff (it still appears in the plain counts). If a tool wasn't run in the baseline (unwired then, newly wired since),
treat all of its current issues as new and say so. This is separate from any suppression the project itself
maintains (`# noqa`, `# type: ignore[code]`, `per-file-ignores`, mypy `[[tool.mypy.overrides]]`), which the tools
already applied.

Do not fix any issue, add `# noqa`/`# type: ignore`, extend `per-file-ignores`/`ignore`, run a formatter in write
mode, or modify any configuration — those are follow-ups the user (or a coding skill) drives explicitly.

## Verification notes

Verified with uv 0.12.8, CPython 3.13, Ruff 0.17.0, mypy 2.4.0 (+ django-stubs with Django 6.1.2), pyright (PyPI
wrapper, via `uv run --with pyright`), Bandit and djLint (via `uv run --with`), Black, in
`$TMPDIR/iru-verify/python/gates/{lib,dj}` (outside this repository), against a deliberately bad module
(unused import, unsorted imports, `shell=True`, `%`-built SQL, missing annotations/docstrings, a wrong return type,
bad formatting):

- `ruff check --output-format json --exit-zero` exited `0` and the parser bucketed `D100, I001, F401, D103, S602,
  UP031, S608` (+ `PT015`, `B011` in tests); without `--exit-zero` it exits `1`. A file with a syntax error came back
  as code `invalid-syntax`. `--force-exclude` made an explicitly passed, `extend-exclude`d file report "No Python files
  found"; without it the file was linted.
- `ruff format --check --output-format json` exited `1` with one `unformatted` entry (bucketed as `format`); a syntax
  error exited `2`; `--output-format concise` printed `app/bad.py:3:1: unformatted: File would be reformatted`.
- `mypy --output json` printed one JSON object per line (`no-untyped-def`, `return-value`) and exited `1`; the text
  fallback parser produced identical buckets. A scoped Django run (`mypy_django_plugin.main`, settings
  `config.settings.test`) also reported `var-annotated` in `config/settings/base.py`, which the scope filter dropped.
- `pyright --outputjson` reported `reportReturnType` with a 0-based `line` 5 for source line 6; it downloaded its
  runtime on first use. `bandit -q -f json -o … -r app` reported `B404`, `B602`, `B608` and exited `1`.
  `djlint templates --lint --profile=django` printed `H005`/`H007`/`H016`/`H030`/`H013` under the file header and
  exited `1`; `black --check` printed `would reformat <file>` on stderr; `django-upgrade --target-version 6.1 --check`
  printed `Would rewrite old.py` for a `django.conf.urls.url` usage, exited `1`, and left the file unchanged.
- Still unverified: the Poetry, PDM and Pipenv prefixes, basedpyright, Ruff releases old enough to lack `format
  --output-format`, and mypy < 1.11's text-only output on a real project.

## Sources

- irurueta docs: programming-languages/python/debugging-and-tooling.adoc (Ruff as the default linter/formatter,
  Black/flake8 alternatives)
- irurueta docs: programming-languages/python/type-hints.adoc (mypy/pyright as the only enforcement of hints)
- irurueta docs: programming-languages/python/docstrings.adoc (PEP 257 conventions behind Ruff's `D` rules)
- irurueta docs: backend/fastapi/testing.adoc (pip-audit + bandit in CI)
- irurueta docs: web/django/whats-changed-and-upgrading.adoc (django-upgrade `--target-version`/`--check`, pre-commit
  hook ahead of Black/Ruff)
