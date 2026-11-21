---
name: iru-setup-python-gitignore
description: "Invoked as /iru-setup-python-gitignore, optionally with args on-existing: ask|keep|update|replace. Creates or updates the root .gitignore of a Python project (FastAPI, Django, plain package), never overwriting an existing one without a diff and approval."
model: haiku
---

# Setup Python Gitignore

Create or refresh the root `.gitignore` for a Python project. Never overwrite an existing `.gitignore` without
showing the user exactly what would change and getting explicit approval first — a `.gitignore` can carry
hand-added, project-specific entries that must not be silently dropped.
Mirrors `iru-setup-php-gitignore` and `iru-setup-typescript-gitignore` for the Python ecosystem.

## Step 0 — Parse `args`

This skill takes a single optional key, as a `key: value` line in `args`: `on-existing` (`ask`/`keep`/`update`/
`replace`, default `ask`) — how to treat an existing `.gitignore` in scripted runs (an orchestrator forwards it only
if its own `args` received it). Ignore any other key (orchestrators may pass shared keys such as `mode` or `flavor`;
the flavor is detected in Step 3 from the files themselves, and `mode: existing` alone doesn't skip Step 5's
question). If `args` is absent or unparseable, treat `on-existing` as `ask`.

## Step 1 — Check whether `.gitignore` already exists at the repository root

- **Not found**: tell the user "Creating `.gitignore` **from scratch** — none found." Continue to Step 2. Because
  there's nothing to lose, the final write happens without needing approval — go straight from composing the content
  to writing it, then report in Step 6.
- **Found**: tell the user "**Updating existing `.gitignore`**" and warn that one already exists, so this skill will
  propose changes for them to review rather than overwrite it silently. Read its current content now — it matters
  later, both to avoid proposing duplicate entries and because Step 4 must preserve any hand-written,
  project-specific lines this skill doesn't know how to regenerate. If `on-existing: keep`, stop here: leave the file
  untouched and go straight to Step 6's report. Otherwise continue to Step 2.

## Step 2 — The universal base template

Every Python project gets this baseline, regardless of flavor (grounded in GitHub's own `Python.gitignore`, trimmed
to what a uv-managed project can actually produce):

```gitignore
# Bytecode
__pycache__/
*.py[cod]
*$py.class

# Build / packaging output (uv build → dist/; reports under build/: coverage, test results, API docs)
/build/
/dist/
*.egg-info/
.eggs/
*.egg

# Virtual environments (uv creates .venv/; never commit one)
.venv/
venv/

# Test / coverage output and caches
.pytest_cache/
.coverage
.coverage.*
coverage.xml
htmlcov/
.hypothesis/
.tox/
.nox/

# Type-checker / linter caches
.mypy_cache/
.dmypy.json
dmypy.json
.ruff_cache/
.pyre/
.pytype/

# Environment files (never commit real secrets; the example file documents the shape)
.env
.env.*
!.env.example
.envrc

# PyPI credentials — never commit
.pypirc

# Logs
*.log

# Repository-wide lock the coding skills use to serialize container-backed test runs
.integration-test.lock/

# OS noise
.DS_Store
Thumbs.db

# IDE noise
.idea/
.fleet/
.zed/
.ropeproject/
.spyderproject
.spyproject

# VS Code — ignore the directory but keep the shared, non-machine-specific config files
.vscode/*
!.vscode/settings.json
!.vscode/extensions.json
!.vscode/launch.json
!.vscode/tasks.json
```

This section is unconditional — include it verbatim regardless of flavor, with these adjustments when an existing
`.gitignore` already decides otherwise:

- **`.vscode`**: if the existing file ignores the whole directory (`/.vscode` or `.vscode/`), keep that line and leave
  out the template's `.vscode/*` + `!` block — git can't re-include files under an excluded directory, so the two
  would contradict each other.
- **Env files**: `.env.example` stays tracked (the scaffolds ship one), hence `.env.*` followed by `!.env.example`
  (order matters — the negation must come after). If the project keeps other committed templates (`.env.test`,
  `.env.sample`), add a `!` line for each rather than narrowing `.env.*`.
- **Virtual environments**: GitHub's template also ignores `env/` and `ENV/`; they're left out here because `env/`
  is a plausible source directory name. Add them only if `git ls-files -- env ENV` is empty **and** such a
  directory contains `pyvenv.cfg`.

**Never ignored — and if an existing `.gitignore` ignores one, propose removing that line** (call it out explicitly
in Step 5's question):

- **`uv.lock`** (and `poetry.lock`, `pdm.lock`, `Pipfile.lock`): the scaffold contract commits it — CI installs with
  `uv sync --locked`, the container image builds from it, Dependabot updates it, and OSV-Scanner and pip-audit read
  it. For an application (both FastAPI and Django services) the lock is what makes every environment run the tested
  dependency set (irurueta docs: programming-languages/python/virtual-environments-and-packaging.adoc). A plain
  library that deliberately doesn't commit it is respected — keep its ignore line if the user says so in Step 5.
- **`.python-version`**: uv and `astral-sh/setup-uv` read it to pick the interpreter; GitHub's template only mentions
  it in a comment. Commit it.
- **`.secrets.baseline`**: `iru-check-security`'s triaged detect-secrets baseline — committed by design.

**Note on `/build/`**: the scaffold contract writes `build/coverage/coverage.xml`, `build/test-results/pytest*.xml`,
`build/api-docs/` (Sphinx + OpenAPI), and `build/reports/` there — all regenerated. If `.gitignore` doesn't exist yet,
check `git ls-files -- build dist` before assuming; when files are already tracked there, ask via `AskUserQuestion`
whether to keep ignoring the directory (and untrack it) or narrow the entry to `/build/coverage/`,
`/build/test-results/`, `/build/api-docs/`, `/build/reports/` — never ignore a directory out from under tracked files
without asking. `coverage.xml` is listed separately for tools that write it to the root (coverage.py's default when
`[tool.coverage.xml] output` isn't set).

## Step 3 — Detect the flavor and project-specific additions

Don't guess; only add a section below when the thing it targets is actually present. Use the same markers
`iru-explore` and `iru-setup-python-repository` rely on: `pyproject.toml` (`[project].dependencies`,
`[dependency-groups]`, `[tool.*]` tables), `requirements*.txt`, `manage.py`, `config/settings/*.py` (or a
`settings.py`), `app/settings.py`, `docs/api/conf.py` / `docs/conf.py` / `mkdocs.yml`, `*.ipynb`, `package.json`, and
`docs/antora.yml`.

- **Django project** (a root `manage.py` and `django` among the dependencies): read the settings for the actual
  paths — `STATIC_ROOT` (`collectstatic` output; the scaffold uses `BASE_DIR / "staticfiles"`), `MEDIA_ROOT` (user
  uploads), and the SQLite `NAME` of a development database — and ignore exactly those, root-anchored:
  ```gitignore
  # Django
  /staticfiles/
  /media/
  db.sqlite3
  db.sqlite3-journal
  ```
  (`staticfiles/` is rebuilt by `collectstatic` in the image build; `media/` holds uploaded files, which belong in
  object storage, never in git; `db.sqlite3` is the `local` settings' throwaway database.) When `STATIC_ROOT`/
  `MEDIA_ROOT` are read from environment variables, use the default the settings fall back to; with no default, skip
  the line and say so. Never ignore a `static/` *source* directory (`STATICFILES_DIRS` or an app's `static/`) — only
  the collected output. If `celery` is a dependency, also add `celerybeat-schedule*` and `celerybeat.pid`. A legacy
  `local_settings.py` pattern (a gitignored per-developer settings file) is added only when the project's settings
  import one. Compiled translations (`*.mo`) stay tracked unless the build runs `compilemessages` — ask if
  `locale/` exists and the Dockerfile doesn't compile them.
- **Browser UI tests** (a Django project — its scaffold ships the `tests/ui/` Playwright tier — or `pytest-playwright`
  / `playwright` among the dependencies, or a `tests/ui/` directory): add
  ```gitignore
  # UI tests: per-run screenshots (state × direction × theme), regenerated by every UI run
  .ui-screenshots/
  # pytest-playwright output (traces, videos, failure screenshots)
  /test-results/
  ```
  Never ignore `.ui-quality.yml` — it is the committed UI-quality configuration. A FastAPI service without Playwright
  gets no such section.
- **FastAPI service** (`fastapi` among the dependencies): nothing beyond the base for the scaffold's PostgreSQL setup.
  When the settings default to a file-based SQLite database (`app/settings.py`'s database URL such as
  `sqlite+aiosqlite:///./app.db`), ignore that file and its journal (`/app.db`, `/app.db-journal`, plus `-wal`/`-shm`
  when WAL is enabled); an in-memory URL (`sqlite+aiosqlite://` / `:memory:`) adds nothing.
- **Sphinx outside the contract's output** (`docs/conf.py` or `docs/source/conf.py`, or a `Makefile` next to
  `docs/api/conf.py` — its default target writes `_build/` beside the sources): add the matching `docs/_build/` /
  `docs/api/_build/`. The contract's `sphinx-build … build/api-docs` output is already under `/build/`.
- **MkDocs** (`mkdocs.yml`): add `/site/` (its default `site_dir`, or whatever `site_dir` names).
- **Jupyter notebooks** (any tracked `*.ipynb`): add `.ipynb_checkpoints/`.
- **PDM** (`pdm.lock` or `[tool.pdm]`): add `.pdm-python` and `.pdm-build/` (and `__pypackages__/` for PEP 582 mode).
- **Node tooling at the root** (`package.json` at the repository root — e.g. a frontend build or a Playwright suite):
  add
  ```gitignore
  node_modules/
  npm-debug.log*
  ```
- **Antora documentation build output** (`docs/antora.yml` or `docs/antora-playbook.yml` exists — see
  `iru-setup-antora`): add
  ```gitignore
  docs/build/
  docs/node_modules/
  ```

Don't invent additional entries beyond what's actually detected — a project with no Node tooling, no Antora docs, no
Celery, or no notebooks gets no section for them. `.dockerignore` is a separate file owned by the scaffold skills; this
skill never edits it.

## Step 4 — Compose the proposed content

Concatenate Step 2's base template with whichever Step 3 sections matched, each under its own comment header for
readability (e.g. `# Django`, `# UI tests`, `# FastAPI`, `# Sphinx`, `# Node`, `# Antora`). Drop any line that duplicates one
already present (including path-equivalent forms such as `/dist` vs `/dist/` vs `dist/`, `.venv` vs `.venv/`, and
`*.pyc` vs `*.py[cod]`). If Step 1 found an existing `.gitignore`, compose two drafts:

- **Merged** (the Update draft): keep every existing line that isn't already covered by the composed template
  (hand-added project-specific ignores must survive), add only the missing template lines, and **remove** an ignore
  line for `uv.lock`/`poetry.lock`/`pdm.lock`/`.python-version`/`.secrets.baseline` (Step 2) — the one deliberate kind
  of deletion, listed in Step 5's question. Keep `!` negations after the patterns they re-include.
- **Template-only** (the Replace draft): exactly the composed template. Note which existing lines it would drop.

## Step 5 — If `.gitignore` already existed, get approval before writing

Skip this step entirely if Step 1 found no existing file — proceed straight to Step 6's write.

- Show the user the merged draft as a diff against the current file (writing the draft to a temp path and running
  `git diff --no-index <current> <draft>` gives a clean unified diff). If the merged draft is identical to the
  current file, say so, treat the outcome as "kept as is", and skip the question.
- If `on-existing` is `update` or `replace`, apply it without asking (still show the diff of what is being
  written). Otherwise ask via `AskUserQuestion` (header `.gitignore`), with these consequences in the option
  descriptions:
  - **Update (Recommended)** — "Write the merged draft: add the missing entries from this skill's template and keep
    every line you added by hand. You've seen the diff; nothing you customized is dropped." — plus, when it applies,
    "…except the `uv.lock` (or `.python-version`) ignore line, which is removed so it is committed (see rationale)".
  - **Keep as is** — "Leave `.gitignore` untouched. Your entries stay exactly as they are, but caches/virtualenv/report
    output the template would ignore (listed in the diff) may show up as untracked files."
  - **Replace** — "Write the template-only draft. Matches this house template exactly, but the hand-added lines
    listed below are lost (recoverable only from git history)." — list those lines in the question text.
  There is no partial-apply option — if the user wants only some of the proposed lines (e.g. keep ignoring `uv.lock`
  for a library), let them say so in free text and revise the draft before writing.
- Only proceed to Step 6's write on **Update** or **Replace**; on **Keep as is**, write nothing.

## Step 6 — Write `.gitignore` and report

Write the chosen draft to `.gitignore` at the repository root — or nothing, if it was kept as is. Then verify it does
what it says, with git itself (read-only):

```bash
# must print nothing (exit 1): lockfile, interpreter pin, env example, detect-secrets baseline stay tracked.
# No -v here: with -v, git also prints paths matched by a "!" negation (e.g. !.env.example) and exits 0.
git check-ignore uv.lock .python-version .env.example .secrets.baseline   # + .ui-quality.yml when present
# should list each ignored path with the line that ignores it
git check-ignore -v .venv build dist .env .pytest_cache .coverage .mypy_cache .ruff_cache __pycache__ 2>/dev/null
```

Then summarize: whether this run was from scratch or an update, the action taken (**created / updated / replaced /
kept as is**, and whether that came from the user's answer or from `on-existing`), the detected flavor, which
conditional sections from Step 3 were included versus skipped and why (e.g. "no Node section — no root
`package.json`", "Django `MEDIA_ROOT` has no default — not added"), whether a `uv.lock`/`.python-version` ignore line
was removed, the `git check-ignore` result, and — if `.gitignore` already existed — which hand-added lines were
preserved (Update) or dropped (Replace).

**Warn explicitly**:

- Review the generated `.gitignore` before committing — detection is heuristic (manifest/settings signals), not a
  guarantee that every relevant path was found.
- If the repository already had files tracked under a path this skill now ignores (a committed `.venv/`, `dist/`,
  `staticfiles/`, `media/`, or `.env`), `git rm -r --cached` it manually after reviewing the diff — writing
  `.gitignore` alone does not untrack already-committed files. A committed `.env` or `.pypirc` may contain
  credentials: rotate them, since they remain in git history.
- Commit `uv.lock` and `.python-version`; CI (`uv sync --locked`) and the container image depend on both.
- Which values were verified versus inferred: the base was checked against GitHub's `Python.gitignore` and exercised
  with `git check-ignore` on a uv project (below); the Django paths come from the scaffold's settings contract
  (`STATIC_ROOT = BASE_DIR / "staticfiles"`) and Django's deployment docs; every other Step 3 entry is inferred from
  local config signals and should be spot-checked against the project's actual generated output.

## Sources

- GitHub's `github/gitignore` `Python.gitignore` (bytecode, packaging, test, type-checker, PDM, Celery, Jupyter,
  Sphinx, MkDocs entries).
- irurueta docs: programming-languages/python/virtual-environments-and-packaging.adoc — `.venv` per project, committed
  `uv.lock`, never `pip install` into the system Python.
- irurueta docs: web/django/deployment.adoc, web/django/file-uploads-and-storage.adoc — `collectstatic` into
  `STATIC_ROOT` built into the image, media as untrusted user content in object storage.
- irurueta docs: backend/fastapi/settings-lifespan-and-background-tasks.adoc — `.env` git-ignored, settings from
  `APP_*` environment variables.

## Verification notes

Verified in `$TMPDIR/iru-verify/python/gitignore/`: the composed base + Django + Antora template written into a
scratch git repository holding a uv project (`uv.lock`, `.python-version`, `.env.example`, `.secrets.baseline`,
`.venv/`, `build/coverage/coverage.xml`, `dist/*.whl`, `.env`, `.env.local`, `__pycache__/`, `.pytest_cache/`,
`.coverage`, `.mypy_cache/`, `.ruff_cache/`, `staticfiles/`, `media/`, `db.sqlite3`, `docs/build/`,
`docs/node_modules/`): `git check-ignore` reported all 15 generated paths as ignored and `uv.lock`, `.python-version`,
`.env.example`, `.secrets.baseline`, `apps/catalog/static/app.css` and `config/settings/local.py` as not ignored (exit
1, no output); `git status --porcelain -uall` listed only those files, `pyproject.toml` and `.gitignore`. `git
check-ignore -v` on the same "keep" list printed the `!.env.example` negation and exited 0 — hence Step 6 runs that
check without `-v`.

The browser UI test section (2026-10-16, `$TMPDIR/iru-verify/python/iru-setup-python-django/gi/`): `git check-ignore -v`
reported `.ui-screenshots/screen/X/a.png` and `test-results/t.zip` as ignored by those two lines, while
`.ui-quality.yml` and `tests/ui/ui_screenshots.py` stayed tracked (exit 1, no output).
