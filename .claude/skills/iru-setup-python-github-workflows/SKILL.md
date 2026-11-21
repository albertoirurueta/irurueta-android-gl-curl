---
name: iru-setup-python-github-workflows
description: "Invoked as /iru-setup-python-github-workflows with optional key: value args (flavor, branches, ci-tests, pages, distribution, sonar, security-*, on-existing), usually by iru-setup-python-repository. Creates or updates build, release, sync and security workflows plus Dependabot for a FastAPI or Django repository."
model: sonnet
allowed-tools: Read Write Edit AskUserQuestion Bash(gh *) Bash(git *) Bash(curl *) Bash(docker *) Bash(actionlint *) Bash(python3 *) Bash(uv *) Bash(uvx *) Bash(npm *) Bash(npx *) Bash(mkdir *) Bash(ls *) Bash(cat *) Bash(grep *) Bash(find *) Bash(sed -n *) Bash(chmod *)
---

# Setup Python GitHub Workflows

Scaffold (or update) the CI/CD and security pipeline for a FastAPI or Django repository:

| File | Trigger | Does |
|---|---|---|
| `.github/workflows/build.yml` | push to `<integration-branch>`/`<stable-branch>`, pull requests to them, `workflow_dispatch` | lint/format/types (+ Django checks), fast tests with coverage, migration checks against PostgreSQL, integration (`ci-tests: full`), image build check, optional Sonar, docs (Pages from `<stable-branch>` or artifact) |
| `.github/workflows/release.yml` | GitHub Release published — **not written with `distribution: none`** | tag/version check, `uv build` sdist + wheel attached to the release, container image to GHCR (private for `internal`, public for `store`) |
| `.github/workflows/sync.yml` + `.github/scripts/python_version.py` + `.github/scripts/sync_versions.py` | GitHub Release published from `<stable-branch>` | PR merging the release back into `<integration-branch>` with the next `X.(Y+1).0.dev0` version and a refreshed `uv.lock` |
| `.github/workflows/security.yml` | pushes, pull requests, weekly | dependency review, CodeQL (`python` + `actions`), OSV-Scanner, gitleaks, pip-audit |
| `.github/dependabot.yml` | — | weekly grouped updates for uv (or pip), the actions, the `Dockerfile`/`compose.yaml` images, the docs toolchain |

Used by `iru-setup-python-repository`; also runs standalone, where it resolves `visibility` itself and asks `sonar`/`ci-tests`/`pages`/`distribution` with visibility-aware cost notes (it never asks `ci`).

It fits the catalog's release flow: `iru-release` cuts `release_x.y.z` from `<integration-branch>`, bumps the version
with `iru-python-bump-version` (`X.Y.Z.dev0` → `X.Y.Z`, `uv.lock` refreshed), and opens a PR into `<stable-branch>`;
merging it runs `build.yml` there (docs to Pages); publishing the GitHub Release (tag `X.Y.Z`, no `v` prefix by this
catalog's convention — a `v` is tolerated) runs `release.yml` and `sync.yml` (next `X.(Y+1).0.dev0` back on
`<integration-branch>`).

There is deliberately **no PyPI publishing**: both flavors are deployable services, not libraries, so the
distribution channel is a container image (`distribution`), and the `publish` key of the shared vocabulary is
ignored. The sdist/wheel attached to the release is a reproducible build artifact (what `uv build` produced from the
tagged commit), not something to `pip install` from an index. A plain Python library is out of this skill's scope
(the docs' route for one is PyPI Trusted Publishing — irurueta docs: programming-languages/python/virtual-environments-and-packaging.adoc).

Every action reference uses the major (or, where the publisher has no floating major tag, the exact release) current
when this skill was written, checked with `git ls-remote --tags`: `actions/checkout@v7`, `astral-sh/setup-uv@v10.3.0`
(setup-uv stopped moving major tags after `v7` — every later release is immutable, so pin the exact one; `@v10` does
not resolve), `actions/setup-node@v7`, `actions/upload-artifact@v7`, `actions/download-artifact@v8`,
`actions/configure-pages@v6`, `actions/upload-pages-artifact@v5`, `actions/deploy-pages@v5`,
`SonarSource/sonarqube-scan-action@v8`, `docker/setup-buildx-action@v4`, `docker/login-action@v4`,
`docker/metadata-action@v6`, `docker/build-push-action@v7`, `github/codeql-action/*@v4`,
`actions/dependency-review-action@v5`, `google/osv-scanner-action/...@v2.7.0` (that repository publishes **no** `v2`
ref), `gitleaks/gitleaks-action@v3`. This is the catalog-wide convention every `iru-setup-*-github-workflows` skill
follows; the author's docs recommend pinning third-party actions to a full commit SHA in a real project instead
(irurueta docs: backend/flask/cloud-platforms-and-monitoring.adoc) — offer that as a hardening option in Step 7, and
never mix the two styles in one file. When updating an old pipeline re-check them — `gh api repos/<owner>/<repo>/tags` (not `releases/latest`,
which returns `codeql-bundle-*` for CodeQL) or `git ls-remote --tags https://github.com/<owner>/<repo>`; Dependabot's
`github-actions` entry keeps them current afterwards.

**Templates.** The workflow, script and Dependabot templates of Step 3 are files under this skill's `templates/` directory, not inline text. Read only the template files that apply to the choices resolved in Step 1 (the index at the top of Step 3 says when each applies); never read the others.

## Step 1 — Resolve inputs and survey the repository

Parse `args` first (`key: value` lines); every key found is resolved and its survey/question below is skipped:

```
flavor: fastapi
integration-branch: develop
stable-branch: main
python-version: 3.13
database: postgresql
api: drf
open-source: yes
visibility: public
ci-tests: full
pages: yes
distribution: store
sonar: cloud
sonar-organization: example-github
sonar-project-key: example_bookshelf-api
sonar-host-url: https://sonarcloud.io
mode: new
on-existing: ask
```

Ignore any other key (`ci` and `publish` included — invoking this skill means CI is wanted, and these services never
publish to PyPI). `mode` is informational; `on-existing` pre-answers Step 2. The `security-*` keys (`security-dependency-review`/`security-codeql`/`security-osv`/`security-gitleaks`/`security-pip-audit`, each `yes`/`no`) default to `yes`.

Then survey what isn't resolved:

- **Flavor** — the same markers `iru-explore` uses (first match wins): a root `manage.py` and `django` among
  `pyproject.toml` `[project].dependencies` (or `requirements*.txt`) → `django`; `fastapi` among them → `fastapi`.
  Anything else (a plain package) is outside this skill's templates: say so and ask (`AskUserQuestion`) **Stop** /
  **Continue anyway** (every flavor-specific step becomes an open gap).
- **Runner** — the templates assume uv with a committed `uv.lock` (the scaffold contract). `poetry.lock`/`pdm.lock`/
  `Pipfile.lock`/`requirements*.txt` only → say so and ask **Stop** / **Continue anyway** (every `uv sync --locked`
  becomes an open gap; this skill never migrates a project to uv). Read `[tool.uv] required-version` if present
  (setup-uv honours it). `[build-system]` missing (a uv *virtual* project, `tool.uv.package = false`) → `uv build`
  can't run: the release `dist` job is dropped and reported.
- **`<image-name>`** — `[project].name`, lowercased (the GHCR image name); confirm when the repository name differs.
- **Python versions** — `<python-version>` = `python-version` from `args`, else `.python-version` (major.minor), else
  `3.13`. `<python-floor>` = the lowest minor `requires-python` allows (`>=3.12` → `3.12`). `<python-matrix>` = every
  CPython minor from `<python-floor>` up to the newest one in bugfix support **that every locked dependency ships
  wheels for** — `curl -s https://endoflife.date/api/python.json` lists the cycles (`3.12`…`3.15` when this skill was
  written, 3.15 released days earlier: offer it only after a local `uv sync --locked --python 3.15` succeeds), quoted
  (`'3.12', '3.13', '3.14'`). Offline → ask, offering floor..`3.14`.
- **Contract tooling** — confirm in `pyproject.toml`: `[tool.ruff]`, `[tool.mypy]` (or `[tool.pyright]` — then the
  type step runs `uv run pyright`), `[tool.pytest.ini_options]` with the `integration` marker, `[tool.coverage.xml]
  output = "build/coverage/coverage.xml"`, the `dev` and `docs` dependency groups. A missing tool is never invented in
  the workflow: drop the step that would call it and list the gap in Step 7 (no `integration` marker → no integration
  job; no `docs` group/`docs/api/conf.py` → no Sphinx step).
- **FastAPI** — `alembic.ini` + its `script_location` (the scaffold's is `%(here)s/migrations` — revisions under
  `migrations/versions/`, never `alembic/`; Alembic in use → `migrations` job); the database URL setting: read
  `app/settings.py` (`env_prefix`, the URL field) → `<db-url-env>` (scaffold: `APP_DATABASE_URL` on a URL-only
  `DatabaseSettings`, so the migration job needs no other `APP_*` variable, even with `auth: yes`) and the driver the
  engine expects (`postgresql+asyncpg://`, scaffold default; `sqlite+aiosqlite://` for `database: sqlite`;
  `postgresql+psycopg://` for a sync engine); `scripts/export_openapi.py` (→ the OpenAPI step, writes
  `build/api-docs/openapi.json`).
- **Django** — `config/settings/{base,local,test,production}.py` + `config/env.py` (the scaffold contract; adapt the
  module paths for an existing project's layout); `DJANGO_SETTINGS_MODULE` for tests from `[tool.pytest.ini_options]`
  (`config.settings.test`). `<django-prod-env>` is every variable `production.py` requires, with a CI dummy. For the
  scaffold that is exactly (no `DATABASE_URL` — the scaffold never reads one): `DJANGO_ALLOWED_HOSTS` (and
  `DJANGO_SECRET_KEY`, generated per run by its own step, ≥ 50 characters); `database: postgresql` →
  `POSTGRES_HOST`, `POSTGRES_PASSWORD` (required by `config/env.py`'s `postgresql_database()`) plus `POSTGRES_PORT`,
  `POSTGRES_DB`, `POSTGRES_USER`, set explicitly to the service container's `5432`/`app`/`postgres` because their
  defaults are `5432`/`<package>`/`<package>`; `database: sqlite` → `DJANGO_SQLITE_PATH` (optional — defaults to
  `db.sqlite3` at the root — set to `build/ci.sqlite3` so CI never touches a developer-style file). Every other
  `DJANGO_*` variable (`DJANGO_CSRF_TRUSTED_ORIGINS`, `DJANGO_HTTPS`, HSTS, e-mail, …) has a default that passes
  `check --deploy --fail-level WARNING` — don't set them. For a non-scaffold project, find the required ones with
  `grep -n "os.environ\[\|env(\|env\.\(str\|bool\|list\|db\|int\)(" config/settings/*.py config/env.py` (a
  read with no default) and ask for anything you can't give a harmless dummy. `api` from `args`, else
  `drf_spectacular` in `INSTALLED_APPS` → `drf`, `ninja` → `ninja` (its `NinjaAPI` instance path → `<ninja-api>`;
  scaffold: `config.api.api`), else `none`.
- **`database`** — from `args`, else FastAPI: `asyncpg` in `[project].dependencies` → `postgresql`, `aiosqlite` there
  (not only in the `dev` group, where the PostgreSQL shape keeps it for the fast tests) → `sqlite`, no `alembic.ini`
  and no SQL driver → `none`; Django: `psycopg` in `[project].dependencies` → `postgresql`, else `sqlite` (Django has
  no `none`). PostgreSQL image `postgres:18` (the docs' compose and CI image — irurueta docs:
  web/django/pytest-factories-and-browser-tests.adoc).
- **Integration tier** — present only with `database: postgresql` (`tests/integration/` exists). The `sqlite`/`none`
  shapes keep the `integration` marker declared but have no such tests: `pytest -m integration` collects nothing and
  exits **5**, so those variants never get the `integration` job (see the template-selection table).
- **Container** — a root `Dockerfile` (+ `compose.yaml`/`docker-compose.yml`); without one, `distribution`
  `internal`/`store` has nothing to push: recommend running the scaffold skill first, or ask **Stop** / **Continue
  with `distribution: none`**.
- **Docs** — `docs/antora.yml` + `docs/antora-playbook.yml` + `docs/package-lock.json` (`iru-setup-antora`); missing
  → recommend running it first. Sphinx only when `docs/api/conf.py` exists — never add one here (the scaffold owns it).
- **Sonar** — `sonar-project.properties`; note whether it sets `sonar.python.ruff.reportPaths` (gates the Ruff report
  step).
- **Branches** — confirm against `git branch -a` (or ask); `sync.yml` needs both.
- **README/Antora wording** for `sync_versions.py` — `grep -n -i "current development version\|latest release"
  README.md`; note deviations from the default row labels.

**Cost-aware choices** (only those still unresolved, in this order, `AskUserQuestion`; prices per GitHub's/
SonarSource's current pricing — tell the user to double-check their plan):

1. **`visibility`** — `gh repo view --json visibility -q .visibility` (lowercased) when there's a GitHub remote;
   otherwise ask **Public** / **Private** (Public recommended when open source). Open source + private is
   contradictory — say so. `internal` = `private`. Public repositories run standard runners free; private ones
   consume included minutes (2,000/month Free, 3,000 Pro/Team, 50,000 Enterprise Cloud), then ~$0.006/min Linux.
   Everything here is Linux; the Python matrix multiplies the test job, and the PostgreSQL service / testcontainers
   jobs are the expensive part.
2. **`sonar`** — "Configure SonarCloud / SonarQube and send CI analysis and coverage reports to it?" — **SonarCloud**
   only for a public open-source repository; otherwise **None** recommended, **self-hosted** second.
3. **`ci-tests`** — **Unit only** (`unit`; recommended for private) — "fast suites only (pytest on SQLite in-memory,
   `-m "not integration"`), one Python version: no testcontainers; the migration checks still run".
   **Full** (`full`; recommended for public) — "also the integration job (the `integration` marker against a real
   PostgreSQL started by testcontainers — `database: postgresql` only) and, on a public repository, the fast tests
   across `<python-matrix>` — complete verification, more minutes". With `database: sqlite`/`none` say `full` only
   adds the matrix (public) — there is no integration tier.
4. **`pages`** — public: `yes` without asking. Private: **No (Recommended)** — "docs still built, uploaded as an
   artifact" — vs **Yes** — "needs Pro/Team/Enterprise Cloud; the site is public unless Enterprise Cloud private
   Pages".
5. **`distribution`** — **none** (no `release.yml`; the GitHub Release/tag is the release) / **internal** (sdist +
   wheel on the release, image to GHCR as a **private** package — free for public repositories, storage/transfer
   quotas for private ones) / **store** (same, image made **public** — anyone can `docker pull` it; only sensible
   for an open-source project). Default `store` for open source, else `internal` when a `Dockerfile` exists, else
   `none`.

**Accessibility opt-out.** Read the repository's `.ui-quality.yml` `accessibility` level (UI contract §8). When it is
`none` (opted out entirely), emit no accessibility report artifact, upload or step: do not collect `a11y-report.txt`
or other accessibility reports into the `ui-screenshots` artifacts, and drop any accessibility-only job or step;
the UI test run and the screenshot upload are unaffected. With `enforce`, `report` or `off` the workflows are
generated exactly as below (`off` runs no automated checks, so no accessibility report exists to upload either).

## Step 2 — Announce scratch vs update, decide per existing file

Check `build.yml`, `release.yml`, `sync.yml` + its two scripts (one unit — partial if not all exist),
`security.yml`, `.github/dependabot.yml`. None → "creating the CI/CD pipeline **from scratch**". Any → "**updating an
existing CI/CD setup**", list each as present/absent/partial, read each existing one, and name every concrete
contradiction with this run's choices ("`build.yml` has an integration job but `ci-tests: unit`", "it deploys to
Pages but `pages: no`", "it has a Sonar job but `sonar: none`", "there's a `release.yml` but `distribution: none`",
"it installs with `pip install -r requirements.txt` but the project has a `uv.lock`", "it runs `python -m build`
where this skill uses `uv build`"). Unless `on-existing` decides, ask per unit (`AskUserQuestion`, ≤ 4 per call):

- **Update (Recommended)** — merge: add/correct what this skill owns (jobs, gating, action versions, commands), keep
  every other step/job/entry; diff shown before writing.
- **Keep as is** — untouched; mismatches listed in Step 7.
- **Replace** — regenerate from the template; customizations lost (git history keeps them).
- **Stop** (first question only) — end without changing anything.

With `distribution: none`, an existing `release.yml` is offered for removal (ask; never delete silently).

## Step 3 — Templates

**Templates.** Every template below is a file under `templates/<variant>/<path of the generated file>`, holding the exact bytes of that generated file's template. Read only the files that apply to the choices resolved in Step 1, each in full, before filling placeholders:

| Generated file | Template | Applies when |
|---|---|---|
| `.github/workflows/build.yml` | `templates/common/.github/workflows/build.yml` | always (both flavors; gates inside the template and the gating summary decide what stays) |
| `.github/workflows/build.yml` | `templates/common/.github/workflows/build.yml.integration-job` | `ci-tests: full` with `database: postgresql` (job appended under `jobs:` of the build template; never for `sqlite`/`none`) |
| `.github/workflows/release.yml` | `templates/common/.github/workflows/release.yml` | `distribution` is `internal` or `store` (not written with `distribution: none`) |
| `.github/workflows/sync.yml` | `templates/common/.github/workflows/sync.yml` | always (written together with the two scripts below) |
| `.github/scripts/python_version.py` | `templates/common/.github/scripts/python_version.py` | whenever `sync.yml` or `release.yml` is written |
| `.github/scripts/sync_versions.py` | `templates/common/.github/scripts/sync_versions.py` | written together with `sync.yml` |
| `.github/workflows/security.yml` | `templates/common/.github/workflows/security.yml` | at least one `security-*` flag is not `no` |
| `.github/dependabot.yml` | `templates/common/.github/dependabot.yml` | always (no opt-out) |

Resolve every `<placeholder>` from Step 1. Comments marked `# fastapi`, `# django`, `# drf`, `# ninja`,
`# ci-tests: full`, `# ui`, `# pages: yes|no`, `# sonar`, `# ruff-report`, `# sphinx`, `# openapi`, `# image`, `# postgresql`,
`# database: sqlite`, `# matrix` are gates: keep what applies and **drop the rest together with these guidance comments** — a step that
can never run is dead YAML.

### `build.yml`

Template: `templates/common/.github/workflows/build.yml` — read it in full before filling placeholders

**`ci-tests: full` with `database: postgresql`** — append this job under `jobs:` (never for `sqlite`/`none`: those
shapes have no integration tests, and `pytest -m integration` would exit 5, "no tests collected"). It never counts towards the coverage gate (that's `tests`,
container-free tests only — the same rule the coding skills' group stage applies). Testcontainers finds the runner's
Docker engine itself (ubuntu-latest ships it; Ryuk cleans up), so there is no `services:` block and no `DOCKER_HOST`
tweak; the repository-wide `.integration-test.lock` the coding skills use is a local-machine concern and isn't needed
on a fresh runner. The `# ui` steps (Django only, and only when the project has the scaffold's browser UI tier —
`tests/ui/` plus the `ui` marker in `[tool.pytest.ini_options]`) reuse this job rather than adding one: the runner is
already up and synced, so the UI tier costs only the Chromium download and a few browser minutes. Its conftest
deselects `ui` tests from every other selection, so the `tests` job never needs a browser and the coverage gate keeps
counting fast tests only:

Template: `templates/common/.github/workflows/build.yml.integration-job` — read it in full before filling placeholders

Gating summary for `build.yml` — template selection per scaffold variant (`database` × `api`; the rows are what
`iru-setup-python-fastapi`/`-django` actually generate):

| Variant | `quality` extras | `migrations` job | `integration` job (`ci-tests: full`) | `docs` API steps |
|---|---|---|---|---|
| FastAPI, `postgresql` | — | PostgreSQL service; `APP_DATABASE_URL: postgresql+asyncpg://postgres:postgres@localhost:5432/app`; the four Alembic steps | yes | Sphinx + `export_openapi.py` → `build/api-docs/openapi.json` |
| FastAPI, `sqlite` | — | no `services:`; `APP_DATABASE_URL: sqlite+aiosqlite:///./build/ci.sqlite3`; "Create the SQLite directory" + the four Alembic steps (the scaffold's `env.py` already sets `render_as_batch` on SQLite) | **no** (exit 5) | same |
| FastAPI, `none` | — | **no job** (no Alembic) | **no** (exit 5) | same |
| Django, `postgresql`, `drf` | `manage.py check`, `makemigrations --check --dry-run`, `spectacular --validate --fail-on-warn` | PostgreSQL service; the `DJANGO_*` + `POSTGRES_*` lines; the four Django steps | yes, + the `# ui` steps when `tests/ui/` exists | Sphinx + `spectacular … --file build/api-docs/openapi.yaml` |
| Django, `postgresql`, `ninja` | `check`, `makemigrations --check`, Ninja `export_openapi_schema --api config.api.api` | same | yes, + the `# ui` steps when `tests/ui/` exists | Sphinx + `export_openapi_schema … --output build/api-docs/openapi.json` |
| Django, `sqlite`, any `api` | as the row for its `api` (`none` → only `check` + `makemigrations --check`) | no `services:`; replace the four `POSTGRES_*` lines with `DJANGO_SQLITE_PATH: build/ci.sqlite3`; "Create the SQLite directory" + the four Django steps | **no** (exit 5) | Sphinx (+ the `api`'s export; none for `api: none`) |

Name the job honestly ("Migration checks (SQLite)" without the service). Then:

- **UI tier** (`# ui`) — kept only for Django with `tests/ui/` and a registered `ui` marker, inside the `integration`
  job; when kept, add `build/test-results/pytest-ui.xml` to the integration report's `path` (as a `|` list). FastAPI
  and every variant without an `integration` job (SQLite, `ci-tests: unit`) get no UI steps and no new job — report
  that the UI tier then runs only locally (`uv run pytest -m ui`).
- **Flavor** — drop every `# django`/`# drf`/`# ninja` step and the `DJANGO_SETTINGS_MODULE` lines for FastAPI; drop
  the four Alembic steps and the `<db-url-env>` line for Django; "Create the SQLite directory" only for `sqlite`.
- **`api`** (Django) — `drf` keeps "Validate the OpenAPI schema" + "Export the OpenAPI schema (drf-spectacular)";
  `ninja` keeps both "Export the OpenAPI schema (Django Ninja)" steps (`<ninja-api>` = `config.api.api` for the
  scaffold); `none` keeps neither — no schema step at all.
- **Type checker** — `[tool.pyright]` instead of mypy → `uv run pyright`; neither → drop the step (open gap).
- **Matrix** — `ci-tests: full` **and** `visibility: public` → `python: [ <python-matrix> ]`; otherwise the single
  `'<python-version>'` entry. The reports are uploaded from the `<python-version>` entry only.
- **`sonar: none`** — drop the `sonar` job and both Ruff-report steps; `SONAR_HOST_URL` only for self-hosted. With
  `sonar` ≠ `none` the Ruff-report steps (and their download in the `sonar` job) **stay**: both scaffolds'
  `sonar-project.properties` set `sonar.python.ruff.reportPaths=build/reports/ruff.json`, so the `quality` job must
  write that file. Only a non-scaffold properties file without that key drops them — add the key when updating
  Sonar config is in scope, else drop the steps and report it.
- **`image`** — only with `distribution: internal | store` and a root `Dockerfile` (both scaffolds always write one;
  its last stage is the runtime image, so no `target:` is needed).
- **Docs** — Sphinx step only with `docs/api/conf.py`; FastAPI OpenAPI only with `scripts/export_openapi.py`; DRF/Ninja
  export per `api`; no API step at all → drop the uv setup and the `api/` merge line. Keep exactly one of "Upload
  Pages artifact"/"Upload documentation" and drop `deploy-docs` with `pages: no`; drop the whole `docs`/`deploy-docs`
  pair when there is no Antora site and no API docs (report it).
- **`ci-tests: full`** — append `integration` only with `database: postgresql` (an existing project whose
  `tests/integration/` holds tests on another backend counts too); otherwise say in Step 7 that `full` added only
  the matrix. If a non-scaffold project's integration job must stay generic, run it as
  `uv run pytest -m integration … || [ $? -eq 5 ]` and say why — never let exit 5 fail the build silently or
  pretend a tier ran.

### `release.yml` (not written with `distribution: none`)

Template: `templates/common/.github/workflows/release.yml` — read it in full before filling placeholders

Notes:

- **`internal` vs `store`** differ only in the GHCR package's visibility, which a workflow can't set (the
  `GITHUB_TOKEN` has no permission to change package settings): the first push creates the package as *private*;
  for `store`, Step 7 tells the user to make it public (package → Package settings → Change visibility) and to link
  it to the repository if `metadata-action`'s `org.opencontainers.image.source` label didn't.
- **No `[build-system]`** (uv virtual project) → drop the `dist` job, move "Check the version matches the tag" into
  `image` as its first step after the checkout, and drop `needs: dist`. VCS-derived dynamic versions (hatch-vcs,
  setuptools-scm) → drop the version check (`python_version.py` prints `version: dynamic …` and no location lines,
  which would fail the `grep`) and give the `dist` checkout `fetch-depth: 0` so the backend sees the tag.
- `uv build --no-sources` builds the way an index consumer would (ignoring `[tool.uv.sources]` overrides), as uv's
  docs recommend before publishing anything. The released commit was already tested by `build.yml` on
  `<stable-branch>`, so no tests run here.
- Configuration stays out of the image: database URLs, `SECRET_KEY`/`DJANGO_SECRET_KEY`, `APP_*` settings are runtime
  environment variables; migrations run as a one-shot job/compose service per release, never at container start
  (irurueta docs: backend/fastapi/deployment.adoc, web/django/deployment.adoc). Deploying the image is the user's own
  pipeline — list it as a follow-up in Step 7. No image is pushed per `<integration-branch>` push (a registry full of
  `.dev0` tags costs storage on private repositories); add one only if a deployment pipeline consumes it.
- `build-push-action` attaches its default minimal provenance attestation to a pushed image; add `provenance: mode=max`
  + `sbom: true` only if the consumer verifies attestations.

### `sync.yml`

Template: `templates/common/.github/workflows/sync.yml` — read it in full before filling placeholders

The sync branch is `sync_<released version>` (release `1.0.1` → `sync_1.0.1`), so a patch release still gets its own PR. If merging `<stable-branch>` conflicts, the job aborts the merge, points the branch at the release commit and opens the PR titled "merge conflicts to resolve" without bumping, so a conflicting (patch) sync never lowers `<integration-branch>`'s development version.

The bump is always minor (`1.3.0` → `1.4.0.dev0`), matching `iru-python-bump-version`'s Step 5 and `iru-release`'s
default "next upcoming version"; flag it in Step 7 if the team releases patches from `<integration-branch>` too. Tags
that aren't plain `X.Y.Z` (`1.4.0rc1`) stop the job with an error instead of guessing. `CHANGELOG.md` isn't touched —
its release section arrives through the merge. `uv lock` keeps every other pin (it only re-resolves what changed — the
project's own version), so the sync PR's lockfile diff is one line. A VCS-derived dynamic version makes the bump step
a no-op (`version: dynamic …`) — then also drop "Refresh uv.lock". The runner's own `python3` (3.12 on
ubuntu-latest) runs both scripts; they need nothing beyond the standard library.

### `.github/scripts/python_version.py`

The exact helper embedded in `iru-python-bump-version`'s Step 1 (keep the two identical — the release workflow's
version check and `sync.yml` both rely on its `--list` output format `<file>:<line>: <value>    [<location>]`, its
`version: dynamic` short-circuit, and its refusal to write when locations disagree). Copy it from that skill's
current `SKILL.md` (the heredoc between `cat > "$TMPDIR/iru-python-bump-version/py_version.py" <<'PY'` and `PY`)
rather than retyping it; the copy below is the version this skill was verified with:

Template: `templates/common/.github/scripts/python_version.py` — read it in full before filling placeholders

### `.github/scripts/sync_versions.py`

Template: `templates/common/.github/scripts/sync_versions.py` — read it in full before filling placeholders

### `security.yml`

The shared security block every workflows skill in this catalog embeds — dependency review, CodeQL, OSV-Scanner,
gitleaks, identical in shape to the other stacks — with CodeQL's `python` extractor (no build needed:
`build-mode: none`) next to `actions`, plus this stack's addition, **pip-audit** over the exported lock (the Python
Packaging Advisory Database / OSV, the auditor the author's docs put in CI — irurueta docs:
backend/flask/security-considerations.adoc, backend/fastapi/property-based-and-load-testing.adoc). Bandit's rules
already run in `build.yml` as Ruff's `S` rules (the scaffold selects them); a separate `bandit` step is only added
when the project configures `[tool.bandit]` itself. Drop a job's whole entry when its `security-*` flag is `no`; if all
are `no`, don't write the file (say so in Step 7).

Template: `templates/common/.github/workflows/security.yml` — read it in full before filling placeholders

`security-codeql: no` drops `codeql`; `security-pip-audit: no` drops `pip-audit`. OSV-Scanner reads `uv.lock` (and
`docs/package-lock.json`) directly — no setup. pip-audit and OSV-Scanner overlap on purpose: pip-audit fails the
workflow on any advisory (a red check on the PR), OSV-Scanner reports into Code scanning. Without a `uv.lock` (pip
projects) the export step becomes `cp requirements.txt build/requirements-audit.txt` and `--disable-pip` needs
hashed requirements — otherwise drop it (pip-audit then resolves in a throwaway venv). `pip-audit@2.10.1` is pinned
(the version verified here); re-check it with `uvx pip-audit --version` when updating the pipeline, since Dependabot
doesn't see versions inside `run:` lines.

### `.github/dependabot.yml` (always written — no opt-out)

Template: `templates/common/.github/dependabot.yml` — read it in full before filling placeholders

Dependabot's `uv` ecosystem updates `pyproject.toml` constraints and `uv.lock` together and respects
`requires-python`. Base-image bumps across a Python minor (`3.13-slim` → `3.14-slim`) arrive as normal PRs — merge them
only together with `python-version`/`.python-version`. When updating an existing `dependabot.yml`, keep entries for
ecosystems this skill doesn't manage.

## Step 4 — Fill and gate

Substitute every placeholder from Step 1, apply every gate above, and strip the guidance comments. Never invent a
value: an unresolved fact (no `integration` marker, no Antora playbook, no Sonar project key, no `Dockerfile` for
`internal`/`store`, a production-settings variable without an obvious dummy) is either asked or reported as an open gap
in Step 7. The Django `migrations` env block in the template **is** `<django-prod-env>` for the scaffold's
PostgreSQL shape; for `database: sqlite` it becomes

```yaml
      DJANGO_SETTINGS_MODULE: config.settings.production
      DJANGO_ALLOWED_HOSTS: ci.example.com
      DJANGO_SQLITE_PATH: build/ci.sqlite3
```

and for a non-scaffold project it is whatever Step 1's survey found — one `NAME: value` line per required variable,
never `DATABASE_URL` unless the settings really read it, and never `DJANGO_SECRET_KEY` (generated by its own step).
Also:

- **`sonar-project.properties`** missing with `sonar` ≠ `none` → ask for the organization/project key (and host URL
  for self-hosted) and write the flavor scaffold's own template (`iru-setup-python-fastapi`/`-django` own it); both
  read `sonar.python.version`, `sonar.python.coverage.reportPaths=build/coverage/coverage.xml`,
  `sonar.python.xunit.reportPath=build/test-results/pytest*.xml` (and optionally
  `sonar.python.ruff.reportPaths=build/reports/ruff.json`).
- `.github/scripts/python_version.py` is written whenever `sync.yml` or `release.yml` is (both use it), together with
  `sync_versions.py` for `sync.yml`; `chmod +x` both scripts.

## Step 5 — Validate

- Run `actionlint` on every generated workflow — `actionlint` if installed, else
  `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest -color` — and fix every finding (shellcheck
  findings in `run:` blocks included). Parse `.github/dependabot.yml` with
  `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/dependabot.yml` (PyYAML reads the
  workflows' `on:` key as `True` — a YAML 1.1 quirk, not an error).
- Run the commands the workflows contain locally first, through `iru-gate-runner` agents (compact results):
  `uv sync --locked`, `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy .` (`iru-python-code-quality`),
  the fast tests with coverage (`iru-python-test` / `iru-python-coverage`), Django's `manage.py check` +
  `makemigrations --check --dry-run` (+ `spectacular --validate --fail-on-warn` for DRF, `export_openapi_schema` for
  Ninja — `mkdir -p` its output directory first), the Sphinx build
  (`iru-python-docstrings`), `uv build`, the pip-audit export + audit, and `python3 .github/scripts/python_version.py
  --list` (every location must agree and follow the `.dev0` convention). With Docker available, also the `migrations`
  job's commands, with exactly the job's `env:` values, against `docker run --rm -d -e POSTGRES_USER=postgres -e
  POSTGRES_PASSWORD=postgres -e POSTGRES_DB=app -p 5432:5432 postgres:18` (SQLite variants need no container) and,
  with `ci-tests: full` + `database: postgresql`, `iru-python-test integration` (and, with the `# ui` steps,
  `iru-python-test ui` after `uv run playwright install chromium`); Docker unavailable → those jobs are
  **unverified**, not failed; no browser → the UI steps are **unverified**. A workflow whose commands were never run is a guess.
- Never trigger `release.yml` or `sync.yml` for real as a test. Recommend a throwaway pre-release on a fork, or
  review of the first real release run.

## Step 6 — Write the files

Write (or, per Step 2, update/replace — and skip kept units) the workflows, the two scripts (only together with
`sync.yml`/`release.yml` as above), `security.yml`, and `.github/dependabot.yml`. With `distribution: none`, write no
`release.yml`.

## Step 7 — Report: secrets, settings, choices, warnings

Required secrets (Settings → Secrets and variables → Actions) — list only the rows that apply:

| Secret | Purpose | Needed when |
|---|---|---|
| `SONAR_TOKEN` | SonarCloud/SonarQube token for the scan | `sonar` is `cloud` or `self-hosted` (cloud on a private repository also needs a paid SonarCloud plan) |

`GITHUB_TOKEN` needs no setup and covers the release uploads, GHCR, Pages, Sync and every `security.yml` job (gitleaks
needs `GITLEAKS_LICENSE` only for organization accounts on its paid tier — mention it). No database password,
`DJANGO_SECRET_KEY` or `APP_*` secret is needed: CI uses throwaway service containers and a per-run generated key.
Repository settings to check:

- **Settings → Actions → General → Workflow permissions** must allow GitHub Actions to create pull requests, or
  `sync.yml` fails on `gh pr create` despite its `permissions:` block.
- **Pages** (`pages: yes`): Source = **GitHub Actions**. Private repositories need Pro/Team/Enterprise Cloud, and the
  site is public unless Enterprise Cloud private Pages.
- **Code scanning** must be enabled for the CodeQL and OSV-Scanner SARIF uploads; **Dependency graph** for dependency
  review (on by default for public repositories).
- **GHCR** (`internal`/`store`): the first push creates the package as *private*; for `store` change its visibility to
  public, and link it to the repository if needed.

Then summarize: scratch vs update and, per unit, **created / updated / replaced / kept as is** (with mismatches) or
stopped; flavor, `<image-name>`, primary Python and matrix (and where they came from); `visibility` (and its source)
with the minutes note for private repositories; `ci-tests` (and for `full`, whether the matrix applies); the
`migrations` job's shape (Alembic / Django, PostgreSQL / SQLite) or why it was omitted (FastAPI `database: none`);
whether the `integration` job was added (`ci-tests: full` + `database: postgresql`) or why not, and for Django
whether it runs the browser UI tier and uploads `.ui-screenshots/` (the `ui-screenshots-integration` artifact) or why
not (no `tests/ui/`, no integration job — then the UI tier is local-only); `pages`; `sonar` (+ keys) or
why omitted; `distribution` (or why `release.yml` wasn't written); which security jobs were included; and every open
gap (missing contract tooling, no Antora setup, no `Dockerfile`, README wording `sync_versions.py` won't match, patch
releases the minor-bump default doesn't handle, a production-settings variable that needed a guessed dummy, the
SHA-pinning hardening option).

Warn explicitly: **review every workflow before pushing it.** Branch names, versions, image coordinates and the
production-settings dummies were inferred from this repository and may need correcting; `release.yml` publishes to
GitHub Releases and GHCR, and `sync.yml` pushes branches and opens pull requests, so verify both against the first real
release. Nothing in these files contains a credential, and it must stay that way.

## Sources

- irurueta docs: web/django/pytest-factories-and-browser-tests.adoc — the GitHub Actions job (postgres:18 service with
  `pg_isready`, generated masked `DJANGO_SECRET_KEY`, `manage.py check`, `makemigrations --check --dry-run`, pytest
  with coverage XML).
- irurueta docs: web/django/settings-and-configuration.adoc, web/django/deployment.adoc — `check --deploy
  --fail-level WARNING` against the production settings, `migrate --check`, no default `SECRET_KEY`, migrations once
  per release.
- irurueta docs: web/django/django-rest-framework.adoc — `spectacular --validate --fail-on-warn` in CI.
- irurueta docs: backend/pyramid/sqlalchemy-transactions-and-migrations.adoc, backend/flask/database-migrations.adoc —
  `alembic check`, single head, upgrade/downgrade round trip in CI; migrations once per release as a one-shot job;
  backend/fastapi/sql-databases-with-sqlmodel.adoc — Alembic with SQLModel's metadata.
- irurueta docs: backend/fastapi/testing.adoc, backend/fastapi/deployment.adoc — pytest with a coverage threshold,
  warnings as errors, a DB-backed tier against a real PostgreSQL, pinned FastAPI.
- irurueta docs: backend/docker/ci-cd-with-github-actions.adoc, backend/flask/docker-and-kubernetes.adoc — image job
  after tests, Buildx + `type=gha` cache, `docker/metadata-action`, push only outside pull requests;
  backend/fastapi/docker-and-kubernetes.adoc, web/django/docker-and-kubernetes.adoc — the multi-stage uv image the
  release builds;
  backend/flask/cloud-platforms-and-monitoring.adoc — pin third-party actions to a full commit SHA.
- irurueta docs: backend/flask/security-considerations.adoc, backend/fastapi/property-based-and-load-testing.adoc —
  `pip-audit` (and Bandit) on every commit; Dependabot/Renovate; base-image scanning.
- irurueta docs: programming-languages/python/virtual-environments-and-packaging.adoc — uv, committed `uv.lock`,
  `uv sync --locked`; Trusted Publishing as the route for libraries (not used here).
- `iru-setup-php-github-workflows` (pipeline shape, security block, sync scripts), `iru-setup-typescript-github-workflows`
  (NestJS/Next.js image build check and GHCR release), `iru-setup-android-github-workflows` (Antora + API docs under
  `api/`).

## Verification notes

Verified in `$TMPDIR/iru-verify/python/workflows/` (uv 0.12.8, CPython 3.13.15, Docker 29.8):

- **actionlint 1.7.12** (`rhysd/actionlint`, Docker, shellcheck active — a planted unquoted variable was reported) clean
  on every workflow rendered from these templates (a script extracts the YAML blocks above and applies the gates) for
  four variants — `fastapi` (PostgreSQL + Alembic `migrations`, `ci-tests: full` with the 3.12–3.14 matrix and the
  integration job, Sonar cloud with the Ruff report, Sphinx + OpenAPI, Pages, `store` release); `django` + DRF
  (PostgreSQL deploy/migrate checks, `unit`, no Sonar, `pages: no`, `internal` release); `django` + Ninja (SQLite
  `migrations` without a service, `full` on a private repository — no matrix, and since the reconciliation pass no integration job — self-hosted Sonar, no
  `release.yml`); `fastapi` without a database or `[build-system]` (no `migrations` job, image-only release with the
  version check moved into it) — `sync.yml` and `security.yml` included. Every `.github/dependabot.yml` variant
  validated against the SchemaStore Dependabot 2.0 schema (`check-jsonschema`), which lists the `uv` and
  `docker-compose` ecosystems.
- **Build-job command sequence**, run as written on a minimal FastAPI project shaped like the scaffold contract
  (layered `app/`, SQLModel, async engine, Alembic async template, `docs/api` Sphinx, `scripts/export_openapi.py`):
  `uv sync --locked`, `ruff check .`, `ruff format --check .`, `mypy .` (strict + `pydantic.mypy`), the fast pytest
  command (coverage XML at `build/coverage/coverage.xml`, JUnit at `build/test-results/pytest.xml`, the 80%
  `fail_under` enforced by pytest-cov), the integration command (a testcontainers `postgres:18` test passed), the
  Alembic steps against a `postgres:18` container (`upgrade head`, `check` → "No new upgrade operations detected",
  one `(head)`, `downgrade base` + `upgrade head`; an unmigrated model made `alembic check` fail as intended), the
  Sphinx build with `-W` under `UV_LOCKED=1`, the OpenAPI export, `uv lock --check`, `uv build --no-sources`, and the
  Ruff JSON report. The async Alembic template needs `sqlalchemy[asyncio]` (greenlet) in the dependencies — without it
  every Alembic step fails with "the greenlet library is required".
- **Django sequence** on a minimal split-settings project (`config/settings/{base,local,test,production}.py`, DRF +
  drf-spectacular, Django 6.1, psycopg 3): `manage.py check`, `makemigrations --check --dry-run` (and exit 1 after an
  unmigrated field), `spectacular --validate --fail-on-warn`, the fast pytest-django command, the secret-key one-liner,
  then with the production settings against `postgres:18`: `check --deploy --fail-level WARNING`, `migrate
  --noinput`, `migrate --check` (re-run against the real scaffolds, see the reconciliation bullet below). Django
  Ninja's schema command is
  `export_openapi_schema` (not `export_ninja_api_schema`) and, like drf-spectacular's `--file`, needs its output
  directory to exist — hence the `mkdir -p` in every export step.
- **Reconciliation with the scaffolds** (2026-10-14, `$TMPDIR/iru-verify/python/reconcile/`): the templates were
  re-rendered for five variants — FastAPI `postgresql` (`full`, public matrix, Sonar cloud, Pages, `store`), FastAPI
  `sqlite` (`full` → no integration job, SQLite `migrations`), Django DRF `postgresql` (`full`, Sonar cloud), Django
  Ninja `postgresql` (`unit`, self-hosted Sonar, `pages: no`, no `release.yml`), Django `api: none` + `sqlite`
  (`full` → no integration job) — and actionlint 1.7.12 was clean on every `build`/`release`/`sync`/`security.yml`.
  The jobs' commands ran, with exactly the jobs' `env:` values, on projects rendered by `iru-setup-python-django`
  (DRF, Ninja, none-sqlite) and `iru-setup-python-fastapi` (postgresql, sqlite, none): Django `check --deploy
  --fail-level WARNING` ("no issues"), `migrate --noinput`, `migrate --check` green on `postgres:18` with
  `DJANGO_ALLOWED_HOSTS` + `POSTGRES_HOST/PORT/DB/USER/PASSWORD` and a `secrets.token_urlsafe(64)` key, and on SQLite
  with `DJANGO_SQLITE_PATH=build/ci.sqlite3`; Ninja `export_openapi_schema --api config.api.api` and DRF
  `spectacular --validate --fail-on-warn` green; FastAPI's four Alembic steps green on `postgres:18`
  (`postgresql+asyncpg://…`) and on `sqlite+aiosqlite:///./build/ci.sqlite3`; `pytest -m integration` exits 5 on
  FastAPI `sqlite`/`none` and Django `sqlite` — hence no integration job there. Fixed by that pass: the Django job
  used to set a `DATABASE_URL` the scaffold never reads (and would have connected as `<package>` to a database named
  `<package>`), and the integration job used to be added for SQLite/no-database shapes.
- **pip-audit 2.10.1** over `uv export --frozen --all-groups --no-emit-project`: clean on the fixture's 94 hashed pins
  with `--disable-pip --strict`; exit 1 on a known-vulnerable pin. **OSV-Scanner** (v2 image) read `uv.lock` directly
  ("Scanned /src/uv.lock file and found 95 packages").
- **Version helper**: byte-identical to `iru-python-bump-version`'s (extracted from its `SKILL.md`, same SHA-1) and
  runs on macOS's system `python3` 3.9 as well as 3.13. On fixtures it listed `[project].version`, `__version__`, a
  settings `VERSION`, and a `FastAPI(version=)` literal; wrote `0.2.0.dev0` (then `uv lock` → `uv sync --locked`
  passed); short-circuited on hatch-vcs with `version: dynamic …`; refused to write when locations disagreed. The
  release tag check (`--list` | location lines | minus `FastAPI(version=)` | `grep -v -F ": $VERSION "`) passed for
  the matching version and failed for another. `uv lock --check` failed (exit 1) after a version change without
  `uv lock`, which is what release.yml's "Check the lockfile" relies on.
- **Sync**: `sync_versions.py` rewrote the README rows and `docs/antora.yml`'s `version:` on a fixture; the
  next-version computation (`1.3.0` / `v1.3.0` → `1.4.0.dev0`, branch `sync_1.3.0`; `1.3` and `1.3.0rc1` rejected) was run
  in bash.
- **Action refs** checked with `git ls-remote --tags` on the day of writing: `astral-sh/setup-uv` has no tag after
  `v7` that floats (latest `v10.3.0`), hence the exact pin; its `python-version`/`enable-cache` inputs were read from
  that tag's `action.yml`.

Not verified locally: a run on GitHub itself (service containers, setup-uv caching, GHCR push, Pages deploy, SARIF
uploads, `gh release upload`, the sync PR), the Antora steps (identical to the other workflows skills, verified
there), the container image build check (the `Dockerfile` belongs to the scaffold skills and is verified there),
Dependabot's actual `uv`/`docker-compose` update runs, and SonarCloud ingestion of the Cobertura/JUnit/Ruff reports.

The Django `# ui` steps (2026-10-16, `$TMPDIR/iru-verify/python/iru-setup-python-django/`): the rendered `integration`
job with the Chromium install, `pytest -m ui`, the two-path report upload and the `ui-screenshots-integration` upload
passed actionlint (Docker image) and a YAML parse; the commands themselves ran locally against the rendered Django
scaffold (`pytest -m ui` 8 passed, 8 PNGs under `.ui-screenshots/screen/AdminLoginScreen/`). Not run on a GitHub
runner — `playwright install --with-deps chromium` on `ubuntu-latest` is unverified here.
