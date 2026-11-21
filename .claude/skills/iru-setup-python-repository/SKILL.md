---
name: iru-setup-python-repository
description: "Invoke as /iru-setup-python-repository, optionally with args (key: value lines: flavor fastapi|django, mode, open-source, visibility, ci, ci-tests, pages, distribution, sonar, python-version, database, orm, api, accessibility, branches, on-existing, identity keys) to skip matching questions. End-to-end bootstrap of a FastAPI or Django repository: asks the shared inputs once, then runs the flavor scaffold, iru-setup-antora, iru-setup-python-gitignore, iru-setup-python-github-workflows, iru-setup-changelog and iru-setup-readme in order. Use to bootstrap or catch up a Python web repository in one pass."
model: sonnet
---

# Setup Python Repository

Bootstrap a brand-new (or catch up an existing) Python web repository in one pass, for either of two flavors, by
orchestrating six skills: the flavor's own scaffold skill (`iru-setup-python-fastapi` or `iru-setup-python-django`),
`iru-setup-antora` (Antora documentation site), `iru-setup-python-gitignore` (root `.gitignore`),
`iru-setup-python-github-workflows` (`build`/`release`/`sync`/`security` workflows + Dependabot),
`iru-setup-changelog` (root `CHANGELOG.md`), and `iru-setup-readme` (root `README.md`). This skill does not duplicate
any of their logic — it collects the shared parameters once and passes each sub-skill only the `args` keys it
actually accepts.

Each of Steps 5–10 invokes its sub-skill via the `iru-isolated-skill-executor` agent (`subagent_type:
"iru-isolated-skill-executor"`, always naming the full `iru-`-prefixed skill inside the prompt) rather than calling
`Skill(...)` directly: every sub-skill re-derives what it needs from the files the previous steps wrote, so only a
short completion summary flows back, keeping six transcripts out of this orchestrator's context.
`AskUserQuestion`s raised inside a sub-skill still reach the user — tell the executor to let them through rather
than answering them itself.

**Plain Python libraries** (`Project type: python-library` — a package meant for PyPI, no web framework) and other
Python web frameworks (Flask, Litestar, …) are out of scope: there is no scaffold skill for them in this catalog. If
that's what the repository is (or what the user wants), say so and stop — the gate skills (`iru-python-test`,
`iru-python-coverage`, `iru-python-code-quality`, `iru-python-docstrings`) and `iru-setup-antora`,
`iru-setup-python-gitignore`, `iru-setup-changelog`, `iru-setup-readme` can still be run individually.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Resolve inputs

This skill runs stand-alone (`/iru-setup-python-repository`) or from the `iru-setup-repository` front door, so parse
`args` first, as `key: value` lines, one per line, e.g.:

```
flavor: fastapi
mode: new
open-source: yes
visibility: public
ci: yes
ci-tests: full
pages: yes
distribution: store
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_acme-loans
sonar-host-url: https://sonarcloud.io
python-version: 3.13
database: postgresql
orm: sqlmodel
integration-branch: develop
stable-branch: main
accessibility: enforce
```

Recognized keys: `flavor` (`fastapi`/`django`), `mode` (`new`/`existing`), `open-source` (`yes`/`no`), `visibility`
(`public`/`private`/`internal`), `ci` (`yes`/`no`), `ci-tests` (`unit`/`full`), `pages` (`yes`/`no`),
`distribution` (`none`/`internal`/`store`), `sonar` (`cloud`/`self-hosted`/`none`) and — only when not `none` —
`sonar-organization`, `sonar-project-key`, `sonar-host-url`; `python-version` (the runtime CI, Docker and
`.python-version` use, e.g. `3.13`), `database` (`postgresql`/`sqlite`/`none`), `orm` (`sqlmodel`/`sqlalchemy` —
`fastapi` only), `api` (`drf`/`ninja`/`none` — `django` only), `accessibility` (`enforce`/`report`/`off`/`none` — `django`
only: the level of the automated accessibility checks in the browser UI tests, written to `.ui-quality.yml`; the
FastAPI flavor renders no UI), `integration-branch`, `stable-branch`; the identity
keys `name`, `package`, `description`, `license`, `developer-name`, `developer-email`, `organization-url`; and
`on-existing` (`ask`/`keep`/`update`/`replace` — never asked; forwarded to every file-writing sub-skill **only** when
it arrived here). Every key found is resolved — skip its question below. Ignore unknown keys (e.g. `publish`, which
applies to libraries of other stacks — there is no PyPI publishing for an application), and ignore `orm` when the
flavor is `django` or `api`/`accessibility` when it is `fastapi` (say so once). If `args` is absent or doesn't look like this format,
treat everything as unset.

## Step 1 — Resolve the flavor and mode

Unless `flavor` came via `args`, resolve it in this order:

1. **An `iru-explore` report already in this conversation** (e.g. the front door ran it) — read its `Project type:`
   line: `python-fastapi` → `fastapi`, `python-django` → `django`. `python-library` → stop as described above.
   `multiple` (e.g. a FastAPI service and a Django project side by side) → tell the user this pipeline sets up one
   project per repository and ask which one to set up.
2. **The same markers `iru-explore` uses, checked directly** (no need to run a full exploration just for this):
   a root `manage.py`, or `django` in `pyproject.toml` (`[project].dependencies`, `[tool.poetry.dependencies]`) /
   `requirements*.txt` / `Pipfile` → `django`; `fastapi` in those same files (or an `app/main.py`/`src/*/main.py`
   creating `FastAPI(`) → `fastapi`. Both present → ask which one this repository is. Python present but neither
   framework (Flask, a plain package) → out of scope, stop as above. Tell the user what was detected and confirm
   with `AskUserQuestion` (detected flavor pre-selected).
3. **Nothing detected** (empty or unrelated repository) — ask with `AskUserQuestion` (header `Flavor`):
   - **FastAPI service** — "an async HTTP API: layered `app/` package, Pydantic v2 + pydantic-settings, SQLModel or
     SQLAlchemy 2.0 + Alembic, pytest + httpx; container image".
   - **Django project** — "a Django 6.1 project: `config/` settings split, apps under `apps/`, custom user model,
     Django REST Framework or Django Ninja, pytest-django + factory_boy; container image".

Map the resolved `flavor` to its scaffold skill for the rest of this run:

| `flavor` | Scaffold skill | Flavor-only keys it gets |
|---|---|---|
| `fastapi` | `iru-setup-python-fastapi` | `orm` (only when `database` ≠ `none`) |
| `django` | `iru-setup-python-django` | `api`, `accessibility` |

If `mode` wasn't supplied, don't ask it as an isolated question: if `pyproject.toml`, `requirements*.txt`,
`manage.py`, or an `app/`/`config/` package from item 2 above exists at the repository root, default to `existing`
and confirm with `AskUserQuestion` (`existing` pre-selected; `new` treats the repository as a fresh scaffold target —
every sub-skill still warns and asks before touching a file that exists); otherwise `new` without asking. Under
`mode: new`, any owned artifact already on disk is still an update for that component — its sub-skill will warn and
ask.

An existing project managed by Poetry (`poetry.lock`), PDM (`pdm.lock`) or pipenv (`Pipfile.lock`) instead of uv is
still in scope, but say up front that the scaffold's update path keeps the existing manager rather than migrating it
to uv silently — the scaffold skill asks if a migration is wanted.

### Setup mode announcement

Right after resolving `mode`, before asking anything else, survey the components this run owns and print:

- `mode: new` and nothing present → "Setting up this repository **from scratch** — no prior setup found."
- Otherwise → "**Updating an existing repository** — prior setup found:" and an inventory table, one row per
  component, marked **absent / present / partial** with the evidence (file paths):

  | Component | Evidence to check |
  |---|---|
  | Project manifest | `pyproject.toml` (+ `uv.lock`, or `poetry.lock`/`pdm.lock`/`Pipfile.lock`); `.python-version`; partial if the lock is missing, or if only `requirements*.txt` exists |
  | Flavor sources | `fastapi`: `app/main.py`, `app/routers/`, `app/services/`, `app/models/`, `app/settings.py`, `app/db.py`, `app/dependencies.py`, `alembic.ini` + `migrations/` (`env.py`, `versions/`; when `database` ≠ `none`); `django`: `manage.py`, `config/settings/` (`base.py`/`local.py`/`test.py`/`production.py`, or a single `settings.py`), `config/env.py`, `config/urls.py`, `config/asgi.py`/`wsgi.py`, `config/api.py` (Ninja), `apps/` (+ `apps/accounts/` custom user) |
  | Tests config | `tests/` (+ `tests/conftest.py`, `tests/integration/` for PostgreSQL), `[tool.pytest.ini_options]` in `pyproject.toml` (or `pytest.ini`), `integration` marker declared |
  | Quality configs | `[tool.ruff]`, `[tool.mypy]`, `[tool.coverage.*]` in `pyproject.toml` (or `ruff.toml`, `mypy.ini`, `.coveragerc`), `.pre-commit-config.yaml`, `.editorconfig` |
  | API docs | `docs/api/conf.py` + `docs/api/index.rst` (Sphinx); partial if only one; FastAPI also `scripts/export_openapi.py` |
  | Container | `Dockerfile`, `.dockerignore`, `compose.yaml` / `docker-compose.yml`, `.env.example` |
  | Antora docs | `docs/antora.yml`, `docs/antora-playbook.yml`, `docs/modules/ROOT/` (partial if only some) |
  | `.gitignore` | `.gitignore` |
  | CI workflows | each of `.github/workflows/build.yml`, `release.yml`, `sync.yml` (+ its two helper scripts under `.github/scripts/`; partial if any of them is missing), `security.yml` |
  | Dependabot | `.github/dependabot.yml` |
  | Sonar config | `sonar-project.properties` |
  | UI quality (`django` only) | `.ui-quality.yml` (+ the UI tier: `tests/ui/ui_screenshots.py`, the `ui` marker); partial if `tests/ui/` exists without `.ui-quality.yml` or vice versa |
  | Changelog | `CHANGELOG.md` |
  | README | `README.md` |
  | License | `LICENSE` / `LICENSE.md` / `LICENSE.txt` / `COPYING` (not written by this run — listed so a mismatch with the chosen license shows) |

  Then: "For each component that already exists you'll be asked whether to keep it, update it, or replace it." (If
  `on-existing` came via `args`, say which choice will be applied to every existing component instead.)

The keep/update/replace questions themselves are asked inside each sub-skill — never here — so nothing is asked twice.

## Step 2 — Collect the project's identity

**`mode: existing` with `pyproject.toml` on disk**: skip every identity question — the scaffold sub-skill reads the
identity from `[project]` (`name`, `description`, `authors`, `license`, `urls`) and the package directory, and asks
only for what it genuinely can't find (e.g. if the user picks Replace there), and `iru-setup-readme` derives it from
the files itself. Resolve `license` only if `args` supplied it, and record in Step 11 that identity came from the
existing files.

Otherwise ask directly (plain conversation, free-text) for whatever `args` didn't resolve:

- **name** — the distribution name, kebab-case (`[project].name`, e.g. `acme-loans`), defaulting to the repository
  directory's name.
- **package** — the import name, snake_case, derived from `name` by default and only confirmed (e.g. `acme_loans`).
  For `fastapi` it's used for the `pyproject.toml` metadata and the OpenAPI title — the code itself lives in the
  `app/` package; for `django` it names the project in settings/docs while code lives in `config/` and `apps/`.
  Validate it's a legal Python identifier and not a stdlib/framework module name (`app`, `django`, `fastapi`,
  `test`, …).
- **description** — one line.
- **Developer name**, **developer email**, **organization URL** (`[project.urls].Homepage`).

Then the **license** with `AskUserQuestion`, ordered per flavor (the framework's own license first):

- `fastapi`: **MIT (Recommended)** — "FastAPI's own license" / **Apache-2.0** / **No license (proprietary / all
  rights reserved)** / **Other**.
- `django`: **BSD-3-Clause (Recommended)** — "Django's own license" / **MIT** / **No license (proprietary / all
  rights reserved)** / **Other**.

For **Other**, ask for the display name and SPDX id afterwards (it becomes `[project].license`, a PEP 639 SPDX
expression).

## Step 3 — Collect the Python version and the flavor's shape

**`python-version`** is the CPython minor the project *runs on*: it becomes `.python-version`, the Docker base image
(`python:<python-version>-slim` for FastAPI, `python:<python-version>-slim-trixie` for Django), and CI's `setup-uv`/`setup-python` version. The scaffold keeps
`requires-python = ">=3.12"` as the floor (Django 6.1 requires 3.12+; the scaffold templates are verified on 3.12+).
Unless `args` supplied it:

- Check https://devguide.python.org/versions/ (or `https://endoflife.date/api/python.json`) for the branches in
  **bugfix** (full support) status — never assume. The catalog default is **3.13** (what the scaffold templates
  were verified against); offer it first while it's still in bugfix or security support, and the newest bugfix
  branch as the alternative when it's newer (e.g. 3.14), noting that every pinned dependency must ship wheels for
  it. Never accept a value below `3.12`, and for `django` never one outside the targeted Django release's supported
  range (Django's release notes / "What Python version can I use with Django?" FAQ).

Ask with `AskUserQuestion` (header `Python`), recommended option first, stating where each value came from.

Then the flavor's shape, with `AskUserQuestion`, skipping whatever `args` resolved. These are collected here, not
left to the scaffold, because `iru-setup-python-github-workflows` needs them too (database service containers and
migration checks, the DRF schema validation step) — which in turn shapes Step 4's `ci-tests` explanation:

- **database** —
  - `fastapi`: **PostgreSQL (Recommended)** — "async SQLAlchemy engine on asyncpg; fast tests on SQLite in-memory,
    the integration tier on a real PostgreSQL via testcontainers" / **SQLite** — "aiosqlite file database — fine
    for a prototype or a single-instance internal tool; no integration tier needed" / **None** — "no persistence
    layer: no ORM, no Alembic".
  - `django`: **PostgreSQL (Recommended)** — "psycopg 3; fast tests on SQLite in-memory via
    `config.settings.test`, the integration tier on PostgreSQL via testcontainers" / **SQLite** — "Django's default
    file database; no integration tier needed". Don't offer `none` — Django's auth, sessions and admin need a
    database; if `args` passed `database: none`, warn and fall back to asking.
- **orm** (`fastapi`, only when `database` ≠ `none`) — **SQLModel (Recommended)** — "the FastAPI docs' default:
  `XBase`/`X(table=True)`/`XCreate`/`XPublic`/`XUpdate` model family on SQLAlchemy 2.0" / **SQLAlchemy 2.0** —
  "plain `DeclarativeBase`/`Mapped` models with separate Pydantic schemas — more explicit, better for large domain
  models". Both use Alembic.
- **api** (`django`) — **Django REST Framework (Recommended)** — "DRF 3.18 + drf-spectacular OpenAPI schema under
  `/api/v1/`" / **Django Ninja** — "type-hinted, Pydantic-based endpoints with a built-in OpenAPI schema" /
  **None** — "server-rendered views/templates only, no REST API".

## Step 4 — Collect the shared pipeline parameters

Ask directly, each with its default so a blank reply accepts it (skip any Step 0 resolved):

| Parameter | Default |
|---|---|
| Integration branch | `develop` |
| Stable branch | `main` |

Then resolve, with `AskUserQuestion`, in this order (skip any key Step 0 resolved):

- **open-source** — Yes / No, no default. Drives the recommended defaults below.
- **visibility** — if the repository has a GitHub remote, detect it with `gh repo view --json visibility -q
  .visibility` (lowercased) and tell the user the detected value; otherwise ask **Public** / **Private**,
  recommending **Public** when open source, otherwise **Private**. Open source + `private`/`internal` is
  contradictory: warn (an open-source project's repository is normally public; a private one forfeits free Actions
  minutes and free SonarCloud) and ask which is right. `internal` is treated like `private` for every cost decision
  below.
- **ci** — "Generate GitHub Actions workflows (CI/CD) for this repository?" Public: recommend **Yes** — standard
  GitHub-hosted runners are free for public repositories. Private/internal: still offer **Yes** first, but state (per
  GitHub's current pricing — tell the user to double-check their plan) that runs consume the account's included
  minutes — 2,000/month on GitHub Free, 3,000 on Pro and Team, 50,000 on Enterprise Cloud — then bill per minute;
  every job in this pipeline runs on Linux runners, and the `full` test tier (below) is the expensive part. **No** —
  "no workflows — build, test, and release locally (run `/iru-setup-python-github-workflows` later)". With `ci: no`,
  Step 8 is skipped entirely (no `.github/workflows/*`, no `security.yml`, no `.github/dependabot.yml`, no sync
  script), `ci-tests` and `pages` are not asked, and Step 11 states that the shared security block (Dependabot,
  dependency-review, CodeQL, OSV-Scanner, gitleaks) was skipped with it.
- **distribution** — what a published release produces. Both flavors are applications, so there is no PyPI
  publishing; the scaffold always writes the multi-stage, non-root `Dockerfile` (+ `compose.yaml`) for local use:

  | Value | Meaning |
  |---|---|
  | `none` | CI builds and tests only (`uv build` still proves the package builds); no `release.yml` — deploying is the user's follow-up |
  | `internal` | on release, the image is built and pushed to GHCR as a **private** package tagged with the release version (sdist/wheel attached to the GitHub Release); GHCR storage/transfer of private packages counts against the account's Packages quota |
  | `store` | the same, but the GHCR package is **public** (free for public repositories) — the workflow pushes it private; making it public is a one-time package setting the workflows skill reports |

  Default **`store`** when open source **and** public; otherwise ask with **`internal`** recommended (a service with
  nothing to deploy rarely makes sense), offering `none` second.
- **sonar** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" Recommend
  **SonarCloud** (`cloud`) only when open source **and** public; otherwise say SonarCloud is free only for
  public/open-source projects (a private project needs a paid plan, per SonarSource's current pricing) and recommend
  **None**, offering **self-hosted SonarQube** second (free Community Build, you run the server). With `ci: no`, say
  nothing would send reports automatically — analysis only runs when someone runs the scanner manually with a token —
  and recommend `none`; if the user still picks cloud/self-hosted, only `sonar-project.properties` is written and
  Step 11 says no CI step sends reports. For cloud/self-hosted collect `sonar-organization` (suggest
  `<owner>-github`; self-hosted only if the server has organizations), `sonar-project-key` (suggest
  `<owner>_<repo>`), and `sonar-host-url` (`https://sonarcloud.io`, or ask), inferred from `git remote get-url
  origin`.

Then, **only when `ci: yes`**:

- **ci-tests** — "Which tests should CI run?" **Unit only** (`unit`; recommended for private/internal) vs **Full**
  (`full`; recommended for public), described per flavor:
  - `fastapi`: unit = "Ruff, mypy, the fast pytest tier (`-m \"not integration\"`, httpx `ASGITransport`,
    SQLite in-memory) with coverage, and the Alembic checks (`upgrade head`, `alembic check`, single head,
    downgrade/upgrade round trip) against a PostgreSQL service container — or a SQLite file — with only
    `APP_DATABASE_URL` set"; full = "also the `integration` tier against a real PostgreSQL started by
    testcontainers (and, on a public repository, the fast tier across the Python matrix) — image pulls and extra
    minutes per run". With `database: sqlite`/`none` there is no integration tier (`pytest -m integration` collects
    nothing and exits 5, so the workflows add no integration job) — say so: `full` would only add the matrix.
  - `django`: unit = "Ruff, mypy, `manage.py check`, `makemigrations --check --dry-run`, the schema check (DRF
    `spectacular --validate`, Ninja `export_openapi_schema`), the fast pytest-django tier on SQLite in-memory with
    coverage, and `check --deploy` + `migrate` + `migrate --check` with the production settings against a
    PostgreSQL service container (or a SQLite file)"; full = "also the `integration` tier against PostgreSQL via
    testcontainers, followed in the same job by the browser UI tier (`pytest -m ui`, Chromium, `.ui-screenshots/`
    uploaded as an artifact)". Same SQLite caveat — and with SQLite there is no integration job, so CI runs no UI
    tier (it stays a local `uv run pytest -m ui`).
- **pages** — the Antora docs site (Step 6) and the Sphinx API docs are always built. Public: default **yes**
  without asking (say so in Step 11). Private/internal: ask **No (Recommended)** — "CI still builds the docs and
  uploads them as a workflow artifact; nothing is published" — vs **Yes** — "requires GitHub Pro, Team, or
  Enterprise Cloud; the site will be PUBLIC on the internet unless the organization is on Enterprise Cloud with
  private Pages access control".

Then, **only for `django`** (with the other shared inputs, whatever `ci` is):

- **accessibility** — "Support accessibility and assistive technologies?" **Enforce (Recommended)** — "axe-core violations
  fail the browser UI tests" / **Report only** — "violations are listed, never failing" / **Off** — "not run; the
  accessibility coding defaults still apply". **None — opt out entirely** ("no accessibility rules, checks or docs"; written as `accessibility: none`). In `mode: existing`, pre-select from `iru-explore`'s `Accessibility:` line per `iru-setup-repository`'s `reference/accessibility-detection.md` (`present` → Enforce, `partial` → Report only with a mixed-compliance warning, `absent` → None) and never silently flip an existing `.ui-quality.yml` value. Written to `.ui-quality.yml` by the scaffold and read by the coding and
  test skills; with `ci-tests: full` on `database: postgresql` the integration job also runs the UI tier.

These values are forwarded only to the sub-skills that accept them: identity + `python-version` + `database` +
`orm`/`api` + `accessibility` (`django` only) + `open-source`/`visibility`/`ci`/`distribution`/`sonar` (+ keys) to
Step 5; the same pipeline keys +
`ci-tests`/`pages`/`integration-branch`/`stable-branch`/`python-version`/`database`/`api` to Step 8; `sonar`/`ci`/
`pages` to Step 10; `on-existing` — only when it came via `args` — to every file-writing sub-skill (Steps 5–10).

## Step 5 — Run the flavor's scaffold skill

Always invoke the scaffold skill, in both modes — it owns the keep/update/replace decision for `pyproject.toml`/
`uv.lock`, the sources, the tests and quality configs, the Sphinx API docs sources, the container files, and
`sonar-project.properties`. Format Steps 1–4's answers as `key: value` lines and **send only the keys that skill's
own Step 0 lists** — read its `SKILL.md` Step 0 before invoking and drop any key it doesn't recognize rather than
inventing one. Always include `mode`, `visibility`, and `ci`; add the flavor-only key from Step 1's table; add
`on-existing` only if it arrived via Step 0's `args`; omit identity keys in `mode: existing` with identity skipped
(Step 2), and omit any key left unresolved so the sub-skill asks it.

```
Agent({
  description: "Run setup-python-fastapi",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-python-fastapi\", args: \"name: <name>\\npackage: <package>\\n
    description: <description>\\nlicense: <license>\\ndeveloper-name: <developer-name>\\n
    developer-email: <developer-email>\\norganization-url: <organization-url>\\npython-version: <python-version>\\n
    database: <database>\\norm: <orm, only when database is not none>\\nopen-source: <open-source>\\n
    visibility: <visibility>\\nci: <ci>\\ndistribution: <distribution>\\nsonar: <sonar>\\n
    sonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\n
    mode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}). Let every AskUserQuestion it raises
    through to the user. Report back: scratch or update; per component it owns (pyproject.toml/uv.lock, sources,
    tests config, quality configs, Sphinx API docs sources, Dockerfile/.dockerignore/compose.yaml/.env.example,
    Alembic, sonar-project.properties) whether it was created, updated, replaced, kept as is (with mismatches), or
    the user stopped; the resolved Python/framework/tool versions and whether they came from a live lookup or a
    fallback; and each verification gate's result (uv sync, ruff check, ruff format --check, mypy, fast pytest,
    integration pytest, coverage vs 80%, Alembic upgrade/check/heads/downgrade, Sphinx build, OpenAPI export,
    uv build, docker build), including anything unverified and why.",
  run_in_background: false
})
```

`django` follows the same shape with the skill name changed to `iru-setup-python-django`, `api: <api>` and
`accessibility: <accessibility>` instead of `orm`, `.ui-quality.yml` and the browser UI tier (`uv run pytest -m ui`:
screenshots written, axe violations by rule id, or unverified without a browser) added to what it reports, and the Django checks (`manage.py check`, `makemigrations --check --dry-run`, `check --deploy`, the schema
export — DRF `spectacular --validate`, Ninja `export_openapi_schema`) added to the gates it reports (no Alembic) — still filtered against its own Step 0. Flavor-specific details
this orchestrator doesn't pre-ask (FastAPI auth sample (PyJWT + pwdlib), a background-task worker, Django
`django.tasks` vs an existing Celery setup, the starter apps beyond `accounts`) are asked or defaulted inside the
scaffold skill.

If the sub-skill reports that the user **stopped** before a usable project existed (or `pyproject.toml` exists but
is unusable), stop this skill too — there's no project identity to build docs or CI around. A **Keep as is** on an
existing `pyproject.toml` is fine: continue, presuming the existing project is valid enough for the rest of the
pipeline.

## Step 6 — Run `iru-setup-antora`

`Agent({description: "Run setup-antora", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke Skill({skill:
\"iru-setup-antora\"}) with no args (or, only if Step 0's args carried it, args \"on-existing: <on-existing>\") — it
derives the component name, title, and version from the repository (pyproject.toml [project], README). The Sphinx
API docs sources under docs/api/ belong to the scaffold — never move, overwrite, or delete them; the Antora site
links the built API docs under its api/ path. Let every AskUserQuestion through. Report back: scratch or update, the
action taken for an existing site (created / updated / replaced / kept as is), which files/pages were created vs
already present, the Antora version it wrote (and whether it mirrors pyproject's version, e.g. 0.1.0.dev0 →
prerelease), and whether the site build succeeded.", run_in_background: false})`.

Run it *before* Step 8: `iru-setup-python-github-workflows` checks for `docs/antora.yml`/`docs/antora-playbook.yml`
in its survey — running Antora first means its docs job has something to build instead of a gap to report. Afterwards
check that `docs/api/conf.py` and `docs/api/index.rst` still exist if Step 5 wrote them, and flag it in Step 11 if
they don't. Antora's Node toolchain (`docs/package.json`, `docs/node_modules/`) must also stay out of the Docker
build context — check the scaffold's `.dockerignore` covers `docs/` (or at least `docs/node_modules/`), and flag it
in Step 11 if it doesn't.

## Step 7 — Run `iru-setup-python-gitignore`

`Agent({description: "Run setup-python-gitignore", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-python-gitignore\"}) with no args (or, only if Step 0's args carried it, args
\"on-existing: <on-existing>\" — filter against its own Step 0 and drop anything else), deriving the flavor and
tooling from the repository. Let every AskUserQuestion through. Report back only whether .gitignore was created,
updated, replaced, or kept as is, and which conditional sections it included.", run_in_background: false})`.

After Steps 5–6 (it detects the flavor — Django's `staticfiles/`, `media/`, `db.sqlite3` — the virtualenv, the tool
caches, the Sphinx and Antora build output, and Antora's `node_modules/` from what's now on disk), and before Step 8
so `.venv/`, `build/`, `.coverage*`, and `.env` are ignored before CI-style reports or local secrets appear.

## Step 8 — Run `iru-setup-python-github-workflows`

**Skip this step entirely when `ci: no`**: don't invoke the sub-skill, write no `.github/workflows/*`,
`security.yml`, `.github/dependabot.yml`, or sync script, and leave any workflow files already on disk untouched.
Record "skipped (ci: no)" for the workflows and Dependabot components in Step 11, and that the shared security block
(Dependabot, dependency-review, CodeQL, OSV-Scanner, gitleaks) and the opt-in `pip-audit` were skipped with them —
`/iru-setup-python-github-workflows` adds it all later.

Otherwise (filtering the keys against that skill's own Step 0 exactly as in Step 5):

```
Agent({
  description: "Run setup-python-github-workflows",
  subagent_type: "iru-isolated-skill-executor",
  prompt: "Invoke Skill({skill: \"iru-setup-python-github-workflows\", args: \"flavor: <flavor>\\n
    integration-branch: <integration-branch>\\nstable-branch: <stable-branch>\\npython-version: <python-version>\\n
    database: <database>\\napi: <api, django only>\\nopen-source: <open-source>\\nvisibility: <visibility>\\n
    ci-tests: <ci-tests>\\npages: <pages>\\ndistribution: <distribution>\\nsonar: <sonar>\\n
    sonar-organization: <..., if set>\\nsonar-project-key: <..., if set>\\nsonar-host-url: <..., if set>\\n
    mode: <mode>\\non-existing: <on-existing, only if supplied via args>\"}). Let every AskUserQuestion through.
    Report back: scratch or update; per file (build.yml/release.yml/sync.yml + its scripts/security.yml/
    .github/dependabot.yml) created, updated, replaced, kept as is (with mismatches), not written (release.yml with
    distribution: none), or stopped; whether actionlint (or the YAML fallback) passed; the full required-secrets
    table and repository-settings notes; and every open gap it flagged.",
  run_in_background: false
})
```

`ci` itself is never forwarded (invoking the skill means CI is wanted), and any opt-in security keys the skill
offers (the shared block plus `pip-audit`) are left at their defaults — note that once in Step 11 rather than asking
per run. If the user chose to stop inside it, note it in Step 11; the scaffold, docs, and `.gitignore` from Steps 5–7
stand on their own.

## Step 9 — Run `iru-setup-changelog`

`Agent({description: "Run setup-changelog", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-changelog\"}) with no args (or, only if Step 0's args carried it, args \"on-existing:
<on-existing>\"), working from the repository's own git tag/GitHub Release history. Let every AskUserQuestion
through. Report back only whether CHANGELOG.md was created (and the version range reconstructed), updated (releases
appended), replaced, or kept as is.", run_in_background: false})`. Independent of Steps 5–8; it runs before Step 10
so the README's Documentation section links an existing `CHANGELOG.md`.

## Step 10 — Run `iru-setup-readme`

`Agent({description: "Run setup-readme", subagent_type: "iru-isolated-skill-executor", prompt: "Invoke
Skill({skill: \"iru-setup-readme\", args: \"sonar: <sonar>\\nci: <ci>\\npages: <pages, or no when ci: no>\\n
on-existing: <on-existing, only if supplied via args>\"}) — sonar: none suppresses Sonar badges/links, ci: no omits
CI badges, pages: no avoids a github.io docs link — deriving everything else from the repository (pyproject.toml,
the app/ or config/ + apps/ sources, the Antora docs, the Sphinx API docs, the workflows, sonar-project.properties,
CHANGELOG.md). For the installation section this is a service, not a library: 'run it locally' with uv (uv sync,
.env from .env.example, migrations — uv run alembic upgrade head for FastAPI with an ORM, uv run python manage.py
migrate for Django — then uv run fastapi dev / uv run python manage.py runserver), docker compose up (services
db + one-shot migrate + api for FastAPI, db + migrate + app for Django; SQLite shapes have no db service, FastAPI
database: none has no compose.yaml), and the GHCR container image when release.yml publishes one; link the OpenAPI
docs (/docs for FastAPI; /api/v1/docs/ + /api/v1/schema/ for DRF, /api/v1/docs + /api/v1/openapi.json for Ninja;
none for api: none). Report back only whether README.md was created,
updated, replaced, or kept as is, and which sections were included vs omitted and why.", run_in_background:
false})`.

Run it last: its badges, status table, and links are only as complete as what Steps 5–9 left on disk. In
`mode: new` many sections stay sparse (no tags, no CI runs yet) — expected; it omits what it can't confirm.

## Step 11 — Report

Summarize all six delegated skills together: repeat Step 1's **Setup mode** statement (from scratch vs updating an
existing repository); the resolved `flavor` and `mode`; the identity and license (or "taken from the existing
files"); `python-version` and where it came from (devguide/endoflife lookup, the catalog default, or `args`); the
flavor's shape (`database`, `orm` or `api`); the branches. Then a per-component table — project manifest, flavor
sources, tests config, quality configs, API docs, container files, Sonar config, `.ui-quality.yml` (`django` only,
with the `accessibility` level applied), Antora docs, `.gitignore`, each
workflow file (`build.yml`/`release.yml`/`sync.yml` + scripts/`security.yml`), `.github/dependabot.yml`,
`CHANGELOG.md`, `README.md` — with the action actually taken as each sub-skill reported it: **created / updated /
replaced / kept as is / skipped (ci: no) / not written (distribution: none)** (or stopped), plus every mismatch a
sub-skill listed for a kept file. Include Step 5's verification results (lint, format, types, fast tests, integration
tests, coverage vs 80%, Sphinx build, Django checks, the Django UI tier, docker build; anything unverified, e.g. no
Docker for testcontainers, no browser for the UI tier).

State explicitly what was wired or deliberately omitted, and why:

- **`open-source`** / **`visibility`** — as resolved, and where visibility came from (`args`, `gh repo view`, the
  user). When private/internal with `ci: yes`, repeat the included-minutes note (per GitHub's current pricing —
  double-check your plan).
- **`ci`** — with `no`: no workflows, no `security.yml`, no Dependabot, the shared security block and `pip-audit`
  skipped, `/iru-setup-python-github-workflows` adds them later.
- **`ci-tests`** — `unit` or `full`, with the flavor-specific meaning from Step 4 — omitted when `ci: no`.
- **`pages`** — `yes` (defaulted for public, or chosen) / `no` (docs uploaded as a workflow artifact only) — omitted
  when `ci: no`.
- **`distribution`** — `none`/`internal` (private GHCR image)/`store` (public GHCR image) and whether `release.yml`
  was written; that the `Dockerfile` exists either way.
- **`sonar`** — `cloud`/`self-hosted`/`none`; whether `sonar-project.properties` and the CI analysis step were
  included; for `none`, whether that's the expected default (not open source, not public, or `ci: no`) or a direct
  choice; for cloud/self-hosted with `ci: no`, that no CI step sends reports.
- The opt-in security jobs left at their defaults (Step 8) and the per-flavor license ordering (Step 2) — both
  decided on the user's behalf.

Unless Step 8 was skipped, reproduce the required-secrets table and repository-settings notes
`iru-setup-python-github-workflows` reported (workflow permissions allowing PR creation for `sync.yml`, Pages source =
GitHub Actions when `pages: yes`, GHCR package visibility for the image) verbatim — don't re-derive or expand it.

Finish with **Warn explicitly**, repeating the sub-skills' own warnings: **review every generated file before
building, committing, or relying on CI** — `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy .`,
and `uv run pytest -m "not integration"` should pass locally (plus `uv run python manage.py check` for Django); the
license must match an actual `LICENSE` file if one was chosen (`/iru-check-license` can generate one and backfill
`#` headers), and `[project].license` must agree with it; real secrets belong in `.env` (never committed — only
`.env.example` is) or CI secrets, never in settings modules, and Django's production settings must fail fast without
a `SECRET_KEY`; a `SONAR_TOKEN` secret and a matching Sonar project must exist before a CI scan succeeds; migrations
run as a one-shot job (the compose `migrate` service), never at app startup; the development version convention is
PEP 440 `X.Y.Z.dev0` in `pyproject.toml` `[project].version` (bumped by `iru-python-bump-version` through
`iru-release`); the scaffold's coverage floor is 80% (the author's docs use 90 — raise `fail_under` if wanted); and
every dependency/action version came from a run-time lookup or its recorded fallback — run `uv lock --upgrade`
(or `uv tree --outdated`) before relying on the scaffold long-term.

## Verification notes

Reconciled on 2026-10-14 against what the two scaffolds actually generate (`$TMPDIR/iru-verify/python/reconcile/`,
outside the repository): every key Step 5 forwards is one `iru-setup-python-fastapi`'s / `iru-setup-python-django`'s
Step 0 recognizes (`orm` only to FastAPI and only with a database, `api` only to Django, `database: none` never to
Django), and Step 8's keys are ones `iru-setup-python-github-workflows` parses. The survey and README facts above match
the rendered projects: FastAPI's Alembic lives in `migrations/` (not `alembic/`) with `APP_DATABASE_URL` as its only
setting, Compose services `db`/`migrate`/`api` (FastAPI) and `db`/`migrate`/`app` (Django), Django's production
settings read `DJANGO_SECRET_KEY`/`DJANGO_ALLOWED_HOSTS`/`POSTGRES_*` (or `DJANGO_SQLITE_PATH`) through
`config/env.py`, and the `sqlite`/`none` shapes have no integration tier (`pytest -m integration` exits 5, verified
on all three). The orchestration itself (the six `iru-isolated-skill-executor` hand-offs end to end) was not re-run
in that pass; each sub-skill's own Verification notes cover its output.
