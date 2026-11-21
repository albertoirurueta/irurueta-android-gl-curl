---
name: iru-django-code-one-task-group
description: "Invoked as /iru-django-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one Django task bucket: one quality baseline, iru-django-code-one-task per task (parallel when allowed), then validates the bucket once (license, docstrings, Django checks, 80% coverage, Ruff/mypy, locked integration and UI stages) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(uv run *) Bash(poetry run *) Bash(pdm run *) Bash(pipenv run *) Bash(.venv/bin/*) Bash(python *) Bash(python3 *) Bash(DJANGO_SETTINGS_MODULE=* *) Bash(DJANGO_SECRET_KEY=* *) Bash(docker info *) Bash(docker ps *) Bash(command -v *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir -p build*) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (Django)

Carry out every Django task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task. It is built on the four Python gate skills — `iru-python-test`, `iru-python-coverage`,
`iru-python-code-quality`, `iru-python-docstrings` — each of which already recognizes Django (pytest-django, the
`mypy_django_plugin`, migrations excluded from docstring and coverage scopes). Each is invoked through an
`iru-gate-runner` agent so its report never lands in this context; fall back to the explicit command shown only when a
skill isn't installed in the consuming repository.

Four things shape the validation order:

1. **Migrations are part of the deliverable.** A model change without its migration, two sibling tasks that each
   generated `0005_*` in the same app, or a migration that doesn't match the models is caught by the framework checks
   (Step 3.3) before any test runs — they're cheap, and every later result is meaningless without them.
2. **Fast vs. integration is decided by what a test needs, not by its name.** Tests on the SQLite-in-memory test
   settings are fast; anything carrying the project's `integration` marker/tag, starting testcontainers, or running on
   test settings that point at a PostgreSQL/MySQL server is integration. Step 1 classifies once.
3. **The coverage gate is measured from fast tests only.** It's the number the bucket's tasks control, available
   whether or not Docker is; code whose coverage arrives only through the integration tier is a finding to surface,
   not a pass.
4. **Containers last, and one at a time.** Everything cheaper has passed before anything starts a container or touches
   a shared database, and only one such run may be in flight repository-wide (Step 3.9a).

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one integration-test run may be in flight against this repository at any moment.** Two overlapping runs can
share a database server (pytest-django's `test_<name>` database is created and dropped by whichever run gets there
first; `--reuse-db` makes them share rows), collide on a compose stack's ports, or race testcontainers' reuse — and
produce failures nothing in the code explains, or passes that only happened because the other run left the right data
behind. This holds:

1. **Across buckets/groups** — enforced by the lock in Step 3.9a, regardless of what the caller does.
2. **Within this bucket** — Step 2's per-task agents never run integration tests (`iru-django-code-one-task` forbids
   it), so Step 3.9 is the only container-backed run.
3. **Within the run itself** — never `-n auto` (pytest-xdist) or `manage.py test --parallel` for the integration
   selection, and never change the project's test configuration to speed it up.

The lock directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same path every other group
skill in this catalog uses, so a polyglot repository serializes all of them together. Run every lock command from the
repository root. `iru-python-test` acquires the same lock itself for container-backed selections; this skill takes it
first (3.9a) and passes `lock: held` so the gate doesn't wait on its own caller.

## Step 1 — Detect the project's shape once, then capture one pre-change quality baseline

Detect once — and reuse in every gate prompt below rather than letting each gate re-derive it — the same facts
`iru-django-code-one-task` Step 1 detects:

- **Runner prefix** — `uv.lock` → `uv run`; `poetry.lock` → `poetry run`; `pdm.lock` → `pdm run`; `Pipfile.lock` →
  `pipenv run`; `.venv/` → `.venv/bin/python`; else bare `python -m` (note it for Step 5).
- **Django project root** (the directory with `manage.py`), **Django version**, **settings modules** — the one
  `manage.py` defaults to, the **test settings** (pytest config `DJANGO_SETTINGS_MODULE`/`--ds`, else
  `[tool.django-stubs] django_settings_module`, else a `settings/test.py`/`settings_test.py`), and a **production
  settings module** if one exists (`config/settings/production.py`, `prod.py`, or what `wsgi.py` defaults to).
- **App layout and labels** — from `INSTALLED_APPS` and the `apps.py` files.
- **API style** — `rest_framework` (+ `drf_spectacular`) in `INSTALLED_APPS`, Ninja (`NinjaAPI(` / `django-ninja`), or
  plain views.
- **Test runner** — pytest-django (installed and configured) vs. the built-in `manage.py test` runner. With the built-in
  runner the `iru-python-test`/`iru-python-coverage` gates can't run the suite (they report "pytest-django not
  installed"); use the explicit fallbacks named in Step 3 for those gates — don't add pytest-django.
- **Fast vs. integration selectors**, recorded as two concrete selections:

  | Situation | Fast | Integration |
  |---|---|---|
  | `integration` marker declared (or `tests/integration/`), SQLite test settings | `-m "not integration"` (gate keyword `fast`) | `-m integration` (gate keyword `integration`) |
  | Built-in runner with an `integration` tag | `manage.py test --exclude-tag=integration` | `manage.py test --tag=integration` |
  | Test settings point every test at a PostgreSQL/MySQL server, no marker | only no-DB tests (`SimpleTestCase`/unmarked) | the whole DB-backed suite — needs the server |
  | Other markers the everyday run excludes (`browser`, `e2e`, `slow`) | excluded from fast too | run with integration only if the project's CI does |

  A test that can't be classified with confidence is treated as integration (it gets the lock) — say so in Step 5.
- **Whether the integration stage exists and can run** — testcontainers in the dev dependencies or integration fixtures
  that start containers → needs Docker (`docker info` now; record availability); a database server the test settings
  read from env (`POSTGRES_HOST`, `DATABASE_URL`) → needs that server reachable. No integration tests at all → Step 3.9
  is skipped and Step 5 says so.
- **UI test wiring** — pytest-playwright and axe-playwright-python in the dev dependencies, a `ui` marker in the
  pytest config, `tests/ui/ui_screenshots.py`, and `.ui-quality.yml` at the repository root (missing = `enforce`,
  `[ltr, rtl]`, `[light, dark]`). Absent wiring with a bucket that touches UI → Step 3.10 is **unverified (no UI test
  wiring)** and Step 5 says so; this skill doesn't add the dependencies.
- **Quality wiring** — Ruff / Black, mypy (django-stubs plugin) / pyright, Bandit, djLint, django-upgrade — only so the
  baseline prompt can name them; `iru-python-code-quality` detects them itself.

Then collect the files every task in the bucket is expected to touch (from the task descriptions) and capture one
pre-change baseline:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-python-code-quality\", args: \"<path1> <path2> ...\"}) in the Django project at
    <project root>. Report back only the per-tool, per-rule-id issue counts and the itemized issues (tool, rule id,
    file:line, message) for these paths — Ruff lint, Ruff format (rule id `format`), mypy/pyright, and any configured
    Bandit/djLint/django-upgrade — plus any tool that wasn't configured or failed to run.",
  run_in_background: false
})
```

Record the result as this bucket's baseline — empty if every file is new (pass only paths that exist; new files have
no baseline). Step 3.8 diffs against it so only issues this bucket introduces are flagged. Skip if
`iru-python-code-quality` isn't installed, and say so in Step 5.

Don't touch the integration lock here — a lock held through Step 2's implementation would block every other group for
the whole bucket. It's acquired in Step 3.9a, immediately before the run that needs it.

**Accessibility opt-out (`accessibility: none`).** When `.ui-quality.yml` says `none`, the user opted out of accessibility support entirely (see `reference/ui-contract.md` §8, "Opting out and existing code"): do not apply the accessibility rules, do not run or require the accessibility checks (axe, ATF/semantics audit, `performAccessibilityAudit()`, Axe.Windows, a11y lint rules, TUI asserted checks), and report them as "accessibility: opted out" in the group report - never "unverified", and never a failure. `off` differs: the rules still apply when writing code, only the automated checks are not run.

**Missing `.ui-quality.yml` in a repository that already has UI code.** The level was never chosen, so don't assume the `enforce` default — that default is only for a new UI project with no UI code yet. Before the first UI task of the bucket, resolve it once per `reference/ui-contract.md` §8 ("Opting out and existing code"): reuse the `Accessibility:` line of an `iru-explore` report in the plan's "Current code state" or this conversation (otherwise apply the detection rules of `iru-explore`'s `reference/accessibility-detection.md`), ask the user via `AskUserQuestion` with the setup's pre-selection (`present` → Enforce, `partial` → Report only with the mixed-compliance warning, `absent` → None with the half-compliance warning), and write `.ui-quality.yml` with the chosen level and the default `directions`/`themes`, so the per-task skills and the test gates read the same value. This is a decision only the user can make, so it is an allowed pause.

## Step 2 — Implement each task via `iru-django-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-django-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-django-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any relevant
      Current code state context>\"}). Report back: the detected shape, the files touched (including settings,
      URLconf, INSTALLED_APPS edits), every migration generated (name, auto-generated or hand-written data migration),
      the fast tests added/updated and their result, the integration tests written but not run, for UI work the
      Screen › Region › Component tree and the browser UI tests written but not run (their <kind>/<Id> and visual
      states), any deviation from the
      skill's reference/, any insecure or N+1 neighbouring code noticed, and whether the task stopped on a blocker.
      Fast tests only, scoped to the files you touched — no coverage, no full suite, no integration marker/tag, nothing
      that starts Docker or needs a database server, no -n/--parallel: sibling agents run concurrently and the
      integration tier is serialized by the group.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**Ordering constraints Django imposes regardless of the verdict** — run these tasks sequentially, in plan order, even
in a parallelizable bucket, and note the deviation in Step 5:

- Two tasks that **change models in the same app** — each would generate the next-numbered migration from the same
  parent, producing two leaf nodes (`makemigrations` then refuses with "Conflicting migrations detected").
- Two tasks that edit the same `urls.py`, settings module, `admin.py`, `apps.py`, `models.py`, or `services.py` —
  concurrent edits to one file clobber each other.
- A task whose code imports a model, service, or serializer another task in the bucket creates (provider first).

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a concurrent-write
race), flip it yourself with the same "group validation pending" note. Notify the user that this task's implementation
and fast tests landed. A task that stopped on a blocker is recorded and excluded from Step 3's validation, without
blocking the rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the validation scope first: the union of the files each agent reported, cross-checked against a fresh
`git status`, `git diff --name-only`, and `git diff --name-only --cached` (an agent's report can miss a migration or an
`INSTALLED_APPS` edit). Keep three lists: **production files** (excluding migrations), **test files**, and
**migrations**. Then run, in this order — cheapest first; **3.1–3.8 gate the bucket, 3.9 and 3.10 run only once they
pass**:

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every Python, template, and migration file this bucket added or modified. Report
   back only which files were missing a header vs. fixed vs. already compliant, and — if no header convention existed
   anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a skip choice for the rest of
   the run. Skip if `iru-check-license` isn't installed.

2. **Docstrings**, via `iru-gate-runner`: `Agent({description: "Update docstrings for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-docstrings\", args: \"<production files,
   comma-separated — no tests, no migrations>\"}) in this Django project. Report back only which public modules,
   classes, functions, and methods were documented or completed, in which files, and whether the verification (Ruff
   D rules, interrogate if configured, the project's Sphinx/MkDocs/pdoc build) passed."})`. Fix and re-invoke until it
   passes. Skip if `iru-python-docstrings` isn't installed. The per-task skill already writes docstrings; a lot of
   backfilling here means it was bypassed.

3. **Django framework checks** — one agent, all through the runner prefix and from the project root, each command's
   outcome reported separately. Via `iru-gate-runner`:
   ```
   Agent({
     description: "Run Django framework checks for task group",
     subagent_type: "iru-gate-runner",
     prompt: "In the Django project at <project root>, run each of these with `<run>` and report back only, per
       command, pass/fail and the error/warning lines (system check ids such as admin.E040, urls.W002,
       models.W042; migration names; schema warnings) — no full output:
       (a) `python manage.py check --settings=<test settings>`
       (b) `python manage.py makemigrations --check --dry-run --settings=<test settings>` — exit 1 with 'Migrations for
           ...' means a model change has no migration; 'Conflicting migrations detected' means two leaf nodes.
       (c) <only when drf_spectacular is installed> `mkdir -p build/api-docs && python manage.py spectacular --validate
           --fail-on-warn --settings=<test settings> --file build/api-docs/openapi.yaml`
       (d) <only when a production settings module exists> `DJANGO_SETTINGS_MODULE=<production settings>
           DJANGO_SECRET_KEY=<a random 50-char throwaway> <plus dummy values for any other env var it requires, e.g.
           DJANGO_ALLOWED_HOSTS=example.com> python manage.py check --deploy --fail-level WARNING` — INFORMATIONAL:
           report the warnings, and say 'not runnable' (with the missing variable or import error) rather than
           failing if the production settings can't be loaded with dummy values.",
     run_in_background: false
   })
   ```
   (a), (b), (c) **gate the bucket**. Fix what they report and re-run before going on:
   - **Missing migration** → the task that changed the model generates it (`makemigrations <app_label> --name …`,
     never by hand), and the migration is read for the rename/non-null pitfalls in `iru-django-code-one-task`'s
     `reference/models-and-orm.md`.
   - **Conflicting migrations** (two leaves in one app — Step 2's ordering should prevent it) → if both migrations were
     created by this bucket and are unapplied anywhere, regenerate the later task's migration on top of the earlier one
     (delete only that bucket-created file, re-run `makemigrations`); otherwise `makemigrations --merge` and say so in
     Step 5. Never edit or delete a migration that existed before this bucket.
   - **Schema warnings** → annotate the view/serializer (`@extend_schema`, `@extend_schema_field`) in the owning task.
   (d) is information for Step 5 — but a **new** deploy warning caused by a setting this bucket changed is a defect to
   fix (e.g. a hard-coded `DEBUG = True` reaching production).

   If `manage.py` itself can't run with the test settings (import error, missing env var), that's the first defect to
   fix — nothing below is meaningful until it does.

4. **Scoped fast tests**, via `iru-gate-runner`: `Agent({description: "Run fast tests for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"fast <the test files affected by
   this bucket, space-separated>\"}) in the Django project at <project root>. Fast tests only — never the integration
   selection, nothing that starts Docker or needs a database server, no -n/--parallel. If everything passes, report
   back only that and the test count. If anything fails, report back only the failing test node ids and the first
   line of each failure; treat 'selector matched nothing' as a wrong selector, not a pass."})`. **Built-in runner
   instead**: have the agent run `<run> python manage.py test <dotted labels> --settings=<test settings>
   --exclude-tag=<integration tag> --noinput` and report the same. Trace any failure to its task, fix, and re-invoke
   until green.

5. **Fast-test coverage — the 80% gate**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check fast-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-python-coverage\", args: \"<selector covering the bucket's affected tests —
       prefer the containing apps' test directories over single files, since a module is usually exercised from
       several test files> <production files this bucket changed or added, comma-separated — no migrations, no
       settings, no tests>\"}) in the Django project at <project root>. Container-free tests only — the integration
       selection must not run. Report back, per target file, its line % and branch % and uncovered line ranges, the
       total, and confirm the run used only fast tests. If coverage isn't wired, say so — never report 0%.",
     run_in_background: false
   })
   ```
   **Built-in runner instead**: `<run> coverage run --branch --source=<apps> manage.py test <labels>
   --settings=<test settings> --exclude-tag=<integration tag> --noinput`, then `coverage xml -o
   build/coverage/coverage.xml` and `coverage report -m --include=<targets>` (coverage.py must already be a dev
   dependency — otherwise the gate is **unverified**, never treated as a pass).

   **Gate: every changed/added production file at ≥ 80% line coverage** (the Cobertura line figure the gate skill
   reports as the gate value; branch % is reported alongside). For any file under 80%, add **fast** tests via the task
   that owns that code, re-run 3.4, and re-check here. **An integration test never closes the gap**: logic reachable
   only through a PostgreSQL-backed test either gets a fast test (usually by moving it into a service or QuerySet method
   that runs fine on SQLite) or is recorded in Step 5 as an accepted exception with the reasoning. Genuine exceptions,
   each named in Step 5: migrations, settings modules, `asgi.py`/`wsgi.py`/`manage.py`, a thin `apps.py`/`urls.py`/
   `admin.py` registration with no branching logic, templates, and code that only runs against a PostgreSQL-only
   feature.

6. **Full fast suite**, via `iru-gate-runner`: `Agent({description: "Run full fast test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"fast\"}) in the Django project at
   <project root> (or `<run> python manage.py test --settings=<test settings> --exclude-tag=<integration tag>
   --noinput` for the built-in runner). Never the integration selection. If everything passes, report back only that
   and the count. If anything fails, report back only the failing test node ids and the first line of each
   failure."})`. Fix regressions and re-invoke until green.

7. **Migrations replayed** — the fast suite in 3.6 already created the test database by applying every migration from
   zero on SQLite, so a broken `RunPython` or an operation SQLite can't express has surfaced there. If the test
   settings disable migrations (`MIGRATE: False`, `--no-migrations`/`--nomigrations` in `addopts`), say in Step 5
   that migrations were only checked for consistency (3.3b), not applied — the integration stage (3.9) is then the only
   place they run.

8. **Code quality** — over the same paths as Step 1's baseline plus the bucket's new files, diffed against that
   baseline. Via `iru-gate-runner`: `Agent({description: "Check for new quality issues in task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-code-quality\", args: \"<production
   and test files, space-separated — migrations included only if the project's Ruff config doesn't exclude them>\"})
   in the Django project at <project root>. Compare against this pre-change baseline: <baseline from Step 1>. Report
   back only issues newly appearing (not in the baseline), each with tool, rule id (Ruff codes such as S608/DJ001/D103,
   `format` for Ruff format, mypy error codes, pyright rule names, Bandit test ids), file, and line. Note any tool that
   failed to run (plugin crash, settings import error for the django-stubs plugin) as a tooling problem, not a code
   issue."})`.

   Any new issue is a defect this bucket introduced — trace it to its task, fix it, re-run 3.4 and 3.6, and re-check.
   Treat **Ruff `S` (Bandit) findings, `DJ001`/`DJ006`/`DJ007` (null strings, `exclude`, `"__all__"` on forms), and
   mypy errors in views/serializers** as the most serious: they describe exploitable or crashing code, not style.
   `format` issues are fixed by running the project's formatter on the bucket's files only. Leave an issue only if
   fixing it would contradict what its task explicitly specifies, and record why. Never add a `# noqa`, `# type:
   ignore`, per-file ignore, or mypy override to make a new issue disappear unless the task says so. Skip if
   `iru-python-code-quality` isn't installed.

9a. **Acquire the repository-wide integration-test lock** — immediately before 3.9, never earlier.

   Skip 3.9a–3.9b entirely (no lock, no run) when Step 1 found **no integration tests**, or when the bucket's changes
   can't affect them (e.g. only a template or a pure helper no integration test imports — state the reasoning in
   Step 5; when unsure, run it). If the integration tier **needs Docker and Docker is unavailable** (Step 1's probe, or
   `docker info` re-run now), report the stage as **unverified (Docker unavailable)** and continue to Step 4. If it
   needs a database server that isn't reachable and isn't something this skill may start, report **unverified (no
   database server)** — never start or stop a developer's compose stack.

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's task
     numbers/titles, "Django integration", the project root, the integration selection, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the repository's root `.gitignore` (add the
     line if missing) — it's transient local state and must never be committed.
   - **`HELD`** — another group (or a standalone `iru-python-test` run) is running its integration stage. Tell the user
     this bucket is waiting (naming the holder from `cat .integration-test.lock/owner`), then re-run the `mkdir` roughly
     every 30 seconds. Don't run the integration selection anyway, not even one test.

   Two bounded escapes, so a crashed run can't deadlock the pipeline:

   - **Stale lock** — if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Tell the user what the owner file says, break it (`rm -f .integration-test.lock/owner && rmdir
     .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes: stop waiting, report the stage as **unverified —
     blocked by a concurrent integration run**, continue to Step 4, and name it in Step 5.

9. **Integration tests** — only now, with everything above clean and the lock held:
   ```
   Agent({
     description: "Run Django integration tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"integration\nlock: held\"}) in the Django project at
       <project root> — this skill already holds .integration-test.lock, so do not acquire or release it. (Built-in
       runner instead: `<run> python manage.py test --settings=<test settings> --tag=<integration tag> --noinput`.)
       Run exactly this one invocation — no -n/--parallel, nothing else concurrently. It may start testcontainers
       (PostgreSQL) or use a database server; wait for it rather than backgrounding it. Report back only: whether all
       integration tests passed; for each failure the test node id and first failure line; and separately whether any
       failure was an environment problem (Docker unavailable, image pull failure, container startup timeout, database
       unreachable, a missing env variable) rather than an assertion failure. Do not dump the output.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another test-running agent — and never in the background:
   the lock means something only while this skill holds it for exactly as long as the suite runs.

   - **Environment failure** → **unverified** with the cause; don't spend the bucket debugging infrastructure. One
     self-inflicted cause worth checking: a deprecated import failing under `error::DeprecationWarning` at collection
     (e.g. `testcontainers.postgres` on testcontainers 4.x — the current path is `testcontainers.community.postgres`);
     that's a code fix, not an environment problem.
   - **Assertion failure** → trace it to its task and fix. Common Django causes the fast tier can't see: a
     PostgreSQL-only lookup or constraint, `select_for_update` semantics, a migration operation SQLite tolerated, a
     case-sensitivity or collation difference, a `transaction=True` test relying on `on_commit`. Keep the lock for a
     quick fix-and-re-run; for a long one, release it (3.9b), fix, and re-acquire before re-running. If the fix changed
     production code, re-run 3.3–3.8 afterwards. An integration failure the fast tests missed is a finding worth
     reporting even once fixed.

10. **Browser UI tests** — only for a bucket whose changes include templates, static assets (CSS/JS), template
    tags/partials, or views that render HTML (when unsure, run it), and only once 3.1–3.8 pass. It starts a live
    server and a browser, so it runs **under the integration lock**: when 3.9 ran, keep the lock through this stage
    (release in 3.9b afterwards); when 3.9 was skipped, acquire it exactly as in 3.9a first (owner text "Django UI").
    ```
    Agent({
      description: "Run Django browser UI tests for task group",
      subagent_type: "iru-gate-runner",
      prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"ui\nlock: held\"}) in the Django project at
        <project root> — this skill already holds .integration-test.lock, so do not acquire or release it. Full UI run
        (no paths), so the screenshot directory is cleared first. Report back only: pass/fail counts; each failing
        test node id with its first failure line; the screenshot count per kind and every <kind>/<Id> directory with
        its file names; malformed names; the .ui-quality.yml accessibility level applied with the violation count and
        rule ids; and whether the run was unverified (browser not installed, lock timeout) rather than failed.",
      run_in_background: false
    })
    ```
    Then, against that report:
    - **Screenshot matrix** — for every screen and component the bucket added or changed (the per-task agents'
      `Screen › Region › Component` trees and their reported `<kind>/<Id>` entries), `<shots>/<kind>/<Id>/` must hold
      a capture for each visual state the task named × each `.ui-quality.yml` direction × each theme. A missing
      combination or a malformed name is a defect: the owning task adds the capture (via `capture_ui_state`) and the
      stage re-runs.
    - **Accessibility** — `enforce`: violations failed their tests; fix the markup (labels, alt text, contrast in
      both themes, landmarks) in the owning task and re-run — never lower the level or skip the check. `report`:
      list the rule ids in Step 5, not a failure. `off`: say so.
    - **Unverified** — no Playwright browser, no UI test wiring, or the lock wait timed out: record **UI tests
      unverified (<reason>)**, never passed, and continue to Step 4.

9b. **Release the lock** as soon as 3.9's agent (and 3.10's, when this bucket has a UI stage) has reported back and
   any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   On **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a hard stop. If you
   took the lock, you release it before this skill returns.

Integration-test and UI-test coverage are never measured or reported as gate figures here; the only coverage number
this skill quotes is 3.5's fast-test figure.

## Step 4 — Finalize progress notes and notify the user

Confirm the integration lock is released (3.9b); if a failure path skipped the release, release it now — this skill
must never return while holding it.

Then replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome, quoting the
**fast-test** coverage figure and the integration result separately:

```
- [x] Task 2. **Add `reserve_copies` service and `POST /api/v1/books/{id}/reserve/`** — services.py, api/views.py,
  migration 0004_book_reserved_copies; 94% line coverage (fast tests only); check/makemigrations/spectacular clean;
  no new Ruff/mypy issues; integration test_reserve_postgres passed; UI tests passed — 24 screenshots
  (screen/BookListScreen, component/BookRow), accessibility enforce: 0 violations.
```

If the integration stage was unverified, say so with the reason (`integration tests unverified (Docker unavailable)`,
`… (no database server)`, `… (lock held by another group)`) — silence reads as a pass. The same for the UI stage
(`UI tests unverified (Playwright browser not installed)`, `… (no UI test wiring)`), and with `report` name the
violated rule ids (`accessibility report: image-alt×2`). Same for an unverified coverage
gate. Leave blocked tasks unchecked with their blocker note. Notify the user that group validation completed, with the
bucket-wide result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The detected shape** — runner prefix, Django version, test/production settings modules, app layout, API style,
  test runner, and the fast/integration selections used — and any test treated as integration because it couldn't be
  classified with confidence.
- **Per task**: files touched, migrations generated, tests added/updated (fast vs. integration), **fast-test line
  coverage**, and the code-quality outcome.
- **Framework checks** (3.3): `check`, `makemigrations --check` (and any conflict resolved, how), the spectacular
  result or "not DRF/drf-spectacular", and the informational `check --deploy` warnings or why it wasn't runnable.
  Whether migrations were applied in the fast run (3.7).
- **Security-relevant findings** (Ruff `S`, `DJ`, mass-assignment or unscoped-queryset issues — fixed or accepted) and
  any insecure or N+1 neighbouring code the per-task agents reported — the findings most worth escalating.
- **Coverage exceptions accepted**, with the reasoning, and any code exercised only by the integration tier.
- **The integration result** — passed, failed-and-fixed, **unverified** with the reason, or "no integration tests" —
  and how the lock behaved whenever it wasn't an immediate uncontended acquire (wait time, a broken stale lock and its
  owner text, a timeout), plus confirmation it was released.
- **The UI stage** (3.10) — not applicable (no UI touched), passed, failed-and-fixed, or **unverified** with the
  reason; the `Screen › Region › Component` trees the tasks reported; screenshot counts per kind and any matrix gap
  or malformed name fixed; the accessibility level applied and its violations (rule ids — fixed under `enforce`,
  listed under `report`).
- Whether a new quality issue was left in place as unavoidable and why; whether license-header generation was skipped;
  any sequential-ordering deviation applied in Step 2; a guessed runner prefix; and which task(s), if any, stopped on a
  blocker, with enough detail for the caller to surface it further up.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
