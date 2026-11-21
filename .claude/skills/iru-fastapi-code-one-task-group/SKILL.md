---
name: iru-fastapi-code-one-task-group
description: "Invoked as /iru-fastapi-code-one-task-group <bucket text> (the group's tasks, Parallelizable verdict, code-state context) via Skill. Implements one FastAPI task bucket: one quality baseline, iru-fastapi-code-one-task per task (parallel when allowed), then validates the bucket once (license, docstrings, tests, 80% coverage, Ruff/mypy, Alembic, locked integration stage) and finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(uv run *) Bash(poetry run *) Bash(pdm run *) Bash(pipenv run *) Bash(.venv/bin/*) Bash(python *) Bash(python3 *) Bash(docker *) Bash(command -v *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (FastAPI)

Carry out every FastAPI task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task.

It is built on the shared Python gate skills — `iru-python-test`, `iru-python-coverage`, `iru-python-code-quality`,
`iru-python-docstrings` — each of which already recognizes a FastAPI project, its runner prefix, and the
fast/integration split. Each is invoked through an `iru-gate-runner` agent so its report never lands in this
context; fall back to the explicit command shown only when the skill isn't installed in the consuming repository.

Three things shape the validation order:

1. **This skill owns the container-backed stage — and its serialization.** `iru-fastapi-code-one-task` runs only
   the fast test files it touched. Tests marked `integration` start PostgreSQL (or Redis, Mongo, …) through
   testcontainers, and the Alembic checks need a real database, so they run here, once per bucket, behind the
   repository-wide lock (Step 0, Step 3.9a).
2. **The coverage gate is measured from container-free tests alone.** Integration tests reach code fast tests miss —
   that's welcome and reported — but never satisfy the gate, so the verdict stays reproducible without Docker.
3. **Type-checking is its own gate.** Python never type-checks at import or test time; `mypy --strict` (or pyright)
   inside `iru-python-code-quality` is the only check that sees a missing `await`, a wrong response type, or a type
   error in a test, so it is never skipped when configured.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything container-backed

**Only one container-backed run may be in flight against this repository at any moment.** Two overlapping
integration runs can collide on a reused container, a fixed port, or a shared developer database (rows, migrations
applied twice) — tests fail for reasons nothing in the code explains, or pass because the other run left the right
data behind, and both look like real results. This holds:

1. **Across buckets/groups** — enforced by the lock in Step 3.9a, regardless of what the caller does.
2. **Within this bucket** — Step 2's per-task agents never run integration tests or Alembic against a database (the
   per-task skill forbids it), so Step 3.9 is the only container-backed run.
3. **Within the run itself** — never `-n`/pytest-xdist for the integration selection, and don't change the
   project's pytest or testcontainers configuration to "speed it up".

The lock directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same path every other
group skill and `iru-python-test` use, so a polyglot repository serializes all of them together. Run every lock
command from the repository root. **`iru-python-test` takes this lock itself** for container-backed selections: while
this skill holds it, every such invocation must carry a `lock: held` line, or the gate waits on this skill forever.

## Step 1 — Detect the shape once, classify the tests, capture one baseline

Detect once, and reuse in every gate prompt below (the same facts `iru-fastapi-code-one-task` and the gate skills
detect):

- **App root and runner prefix** — the `pyproject.toml` listing `fastapi`; `uv.lock` → `uv run --frozen`,
  `poetry.lock` → `poetry run`, `pdm.lock` → `pdm run`, `Pipfile.lock` → `pipenv run`, `.venv/` → `.venv/bin/`,
  else a bare interpreter (say so in Step 5). The app object from `[tool.fastapi] entrypoint`, else the module
  holding `FastAPI(`.
- **Layout and source package(s)** — `app/`, `src/<pkg>/`, or the Full Stack template — and the coverage `source`
  from `[tool.coverage.run]`.
- **Persistence** — SQLModel / SQLAlchemy 2.0 / other / none; **sync or async**. If async SQLAlchemy is used and
  `[tool.coverage.run] concurrency` doesn't include `"greenlet"`, coverage.py silently drops lines executed after
  awaited database calls (verified: 69% reported for a fully tested async service, 100% with `concurrency =
  ["greenlet", "thread"]`). Record it — Step 3.5 needs it.
- **Alembic** — `alembic.ini` and its `script_location`; how `env.py` obtains its URL (an environment variable it
  reads, a settings field, or `sqlalchemy.url` in the ini) and which driver it expects (`postgresql+psycopg`, or an
  async `env.py`); whether an integration test already checks "models match migrations" (`compare_metadata` /
  `alembic check` inside `tests/`).
- **Fast vs. integration selection** — an `integration` marker declared in `[tool.pytest.ini_options] markers`
  and/or a `tests/integration/` directory; testcontainers in the dev dependencies. Tests that read a real
  `DATABASE_URL`/Redis without a marker can't be classified with confidence: treat them as integration and say so
  in Step 5. No integration selection and no Alembic → Step 3.9 is skipped and Step 5 says so.
- **Quality tools wired** — Ruff (lint/format), mypy or pyright, Bandit; an import-boundary check (`import-linter`
  contracts, `tach`) if any.
- **Docker** — `docker info` now, recorded for Step 3.9.

Then collect the files every task in the bucket is expected to touch and capture a single pre-change baseline:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-python-code-quality\", args: \"<file1> <file2> ...\"}) in the FastAPI project
    at <app root>. Report back only the issues found for these files, itemized per tool and rule id (Ruff codes,
    `format`, mypy error codes or pyright rule names, Bandit test ids): tool, rule id, file:line.",
  run_in_background: false
})
```

Record the result as this bucket's baseline — empty if every file is new. Step 3.7 diffs against it so only issues
this bucket introduces are flagged. Skip if `iru-python-code-quality` isn't installed, and say so in Step 5.

Don't touch the lock here — a lock held through Step 2's implementation work would block every other group for the
whole bucket. It is acquired in Step 3.9a, immediately before the run that needs it.

## Step 2 — Implement each task via `iru-fastapi-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-fastapi-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-fastapi-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the detected shape, the files touched (including
      main.py/dependencies.py wiring), any Alembic revision added (id, autogenerated or hand-written), the fast
      tests added/updated and their result, the integration tests written but not run, any deviation from the
      skill's reference/, any insecure neighbouring code noticed, and whether the task stopped on a blocker. Fast
      tests only, scoped to the files you touched — no `-m integration`, no coverage, no full suite, no Alembic
      against a database, nothing that starts Docker: sibling agents run concurrently and the container-backed
      stage is serialized by the group.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**Ordering constraints FastAPI imposes regardless of the verdict** — run these tasks sequentially (provider first),
and note the deviation in Step 5:

- Two tasks editing the same file: `main.py` (`include_router`, exception handlers, middleware, lifespan),
  `dependencies.py`/`api/deps.py`, `settings.py`, a model module or `models/__init__.py`, `conftest.py`.
- One task's model, dependency, or service used by another's router.
- **Two tasks that each add an Alembic revision** — parallel revisions both point at the same `down_revision` and
  leave two heads.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a concurrent-
write race), flip it yourself with the same "group validation pending" note. Notify the user that this task's
implementation and fast tests landed. A task that stopped on a blocker is recorded and excluded from Step 3's
validation, without blocking the rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the scope first: the union of the files each agent reported, cross-checked against a fresh `git status`,
`git diff --name-only`, and `git diff --name-only --cached`. List production files, test files, and Alembic
revision files separately. Then run, in this order — cheapest first; **3.1–3.8 gate the bucket, 3.9 runs only once
they pass**:

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket added or modified (sources, tests, Alembic revisions).
   Report back only which files were missing a header vs. fixed vs. already compliant, and — if no header
   convention existed anywhere in the repo — whether the user chose to skip or generate one."})`. Respect a skip
   choice for the rest of the run. Skip if `iru-check-license` isn't installed.

2. **Docstrings**, via `iru-gate-runner`: `Agent({description: "Update docstrings for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-docstrings\", args: \"<production files only — no
   tests, no Alembic revisions>\"}) in this FastAPI project. Report back only which public modules, classes,
   functions, path operations, and models were documented or completed, and whether the verification (Ruff D rules,
   interrogate if configured, the API-docs build — Sphinx, MkDocs, or pdoc, whichever the project has) passed or the
   run was audit-only."})`. Fix and re-invoke until any configured docs build passes. Skip if
   `iru-python-docstrings` isn't installed. A lot of backfilling here means the per-task skill was bypassed.

3. **Scoped fast tests**, via `iru-gate-runner`: `Agent({description: "Run fast tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"fast <the fast test
   files affected by this bucket, space-separated>\"}) in this FastAPI project. If that skill isn't installed, run
   `<prefix> pytest <files> -m \"not integration\" --no-cov`. If everything passes, report back only that and the
   count. If anything fails, report back only the failing test node ids, the failure reason, and the assertion
   message; treat 0 tests collected as the selector matching nothing, not a pass."})`. Trace any failure to its
   task, fix, and re-invoke until green.

4. **Import boundaries** — only if Step 1 found a configured check (`lint-imports`, `tach check`): run it via
   `iru-gate-runner`, reporting back only violated contracts (contract, importer, imported). Fix the code, not the
   contract; record any contract change for Step 5. None configured → note it and continue; don't add one here.

5. **Fast-test coverage — the 80% gate**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check fast-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-python-coverage\", args: \"<selector covering the bucket's fast tests —
       prefer the containing test directories or the whole fast suite, since a module is usually exercised from
       several test files> <target1,target2,...: every production file this bucket changed or added>\nbar: 80\"})
       in this FastAPI project. Container-free tests only — the integration selection must be deselected. Exclude
       Alembic revisions and env.py. If the skill isn't installed, run `<prefix> pytest -m \"not integration\" --cov
       --cov-branch --cov-report=xml:build/coverage/coverage.xml --cov-fail-under=0` and read the Cobertura XML.
       Report back, per target file, its line % and branch %, its uncovered line ranges, and confirm the run was
       container-free.",
     run_in_background: false
   })
   ```
   **Gate: every changed/added production file at ≥ 80% line coverage (the Cobertura line rate).** For any file
   under it, add **fast** tests via the task that owns the file, re-run 3.3, and re-check. **An integration test never
   closes the gap** — logic reachable only through PostgreSQL has no fast feedback loop; either test it on SQLite /
   with a fake, or record it in Step 5 as an accepted exception with the reasoning. Genuine exceptions, each named
   in Step 5: Alembic revisions and `env.py`, an `if __name__ == "__main__":` launcher, generated code.

   **Async SQLAlchemy without `concurrency = ["greenlet", ...]`** (Step 1): the report under-counts lines that run
   after awaited DB calls. Ask the user once (`AskUserQuestion`) whether to add `concurrency = ["greenlet",
   "thread"]` to `[tool.coverage.run]` — recommended, it changes measurement only — and re-run. If declined, a
   shortfall whose uncovered lines all sit right after `await session.…` calls in code the tests demonstrably reach
   is a tooling artifact: accept it, name the files in Step 5, and never write tests chasing it.

6. **Full fast suite**, via `iru-gate-runner`: `Agent({description: "Run full fast test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"fast\"}) in this FastAPI project
   (or `<prefix> pytest -m \"not integration\" --no-cov` if the skill isn't installed). Never the integration
   selection. If everything passes, report back only that and the count; otherwise only the failing node ids,
   reasons, and assertion messages."})`. Fix regressions and re-invoke until green.

7. **Code quality — lint, format, types, security rules**, diffed against Step 1's baseline, via `iru-gate-runner`:
   `Agent({description: "Check for new quality issues in task group", subagent_type: "iru-gate-runner", prompt:
   "Invoke Skill({skill: \"iru-python-code-quality\", args: \"<production files, test files, and Alembic revision
   files>\"}) in this FastAPI project — Ruff lint, Ruff format (rule id `format`), mypy or pyright, Bandit if
   configured. Compare against this pre-change baseline: <baseline from Step 1>. Report back only newly appearing
   issues, each with tool, rule id, file, and line, and note any tool that failed to run as a tooling problem."})`.
   Any new issue is a defect this bucket introduced — trace it to its task, fix it, re-run 3.3 and 3.6, and
   re-check. Treat **every mypy/pyright error as blocking**, including those in tests, and Ruff `S` (Bandit),
   `ASYNC`, and `FAST` findings as the most serious: they describe exploitable or event-loop-blocking code, not style.
   Never add `# noqa`/`# type: ignore`, a per-file ignore, or a baseline entry to make a new issue disappear unless
   the task says so. Leave an issue only if fixing it would contradict what its task explicitly specifies, and
   record why. Skip if `iru-python-code-quality` isn't installed — then still run `<prefix> mypy <files>` through
   `iru-gate-runner` when mypy is configured, because nothing else type-checks.

8. **App import, OpenAPI build, Alembic heads — once**, via `iru-gate-runner`: `Agent({description: "Smoke-check
   FastAPI app and migrations", subagent_type: "iru-gate-runner", prompt: "In <app root>: (a) run `<prefix> python
   -W error::DeprecationWarning -c \"import json, <app module>; json.dumps(<app module>.<app object>.openapi())\"`
   (or the project's OpenAPI export script, e.g. `scripts/export_openapi.py`, into a throwaway path under build/),
   with only the environment variables the test settings provide; (b) if alembic.ini exists, run `<prefix> alembic
   heads` (reads revision files only, no database). Report back only: whether the app imported and the OpenAPI
   schema built (or the exception, and any `Duplicate Operation ID` warning), and how many heads Alembic printed."})`.
   This catches what the tests may not: a router never included, an invalid `response_model` (FastAPI raises at
   schema build), duplicate operation ids that break generated clients, and **more than one Alembic head** — fix
   that by re-pointing this bucket's later revision's `down_revision` at the other head (never by editing an
   applied revision); a merge revision is a plan-level decision to report. If importing the app genuinely needs a
   secret the test settings don't supply, say "skipped (app needs <var> at import)" — that import-time read is
   itself a finding.

9a. **Acquire the repository-wide lock** — immediately before 3.9, never earlier.

   Skip 3.9a–3.9b entirely (no lock, no run) when Step 1 found **no integration selection** and the bucket touched
   **no table model and no Alembic revision** — say so in Step 5. If **Docker is unavailable** (Step 1's probe, or
   `docker info` re-run now), report the stage as **unverified (Docker unavailable)** and continue to Step 4 — the
   coverage verdict from 3.5 stands on its own.

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     task numbers/titles, "FastAPI integration + Alembic", the app root, and `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure
     `.integration-test.lock/` is in the repository's root `.gitignore` (add the line if missing).
   - **`HELD`** — tell the user this bucket is waiting (naming the holder from `cat .integration-test.lock/owner`),
     then re-run the `mkdir` roughly every 30 seconds. Don't run the integration tests anyway, not even one file.
   - **Stale lock** — if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder almost
     certainly died. Tell the user what the owner file says, break it (`rm -f .integration-test.lock/owner && rmdir
     .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes: report the stage as **unverified — blocked by
     a concurrent integration run**, continue to Step 4, and name it in Step 5.

9. **Container-backed stage** — only now, with everything above clean and the lock held. Run each part as its own
   agent, one at a time, never in the background and never alongside another test-running agent:

   - **Integration tests** (when an integration selection exists):
     ```
     Agent({
       description: "Run FastAPI integration tests for task group",
       subagent_type: "iru-gate-runner",
       prompt: "Invoke Skill({skill: \"iru-python-test\", args: \"integration\nlock: held\"}) in this FastAPI project
         at <app root> — the caller already holds .integration-test.lock, so do not acquire or release it. If the
         skill isn't installed, run `<prefix> pytest -m integration --no-cov` (or the tests/integration/ directory).
         Run exactly this one invocation — no -n/xdist, nothing else concurrently. It starts containers; wait for it.
         Report back only: whether all integration tests passed; for each failure the node id, reason, and
         assertion message; and separately whether any failure was an environment problem (Docker unavailable, image
         pull failure, container startup timeout, port in use) rather than an assertion failure.",
       run_in_background: false
     })
     ```
   - **Alembic against PostgreSQL** (when the bucket touched a table model or added a revision) — skip it when the
     integration run above already includes a "models match migrations" plus downgrade/upgrade test, and say so.
     Otherwise, only if `env.py` takes its URL from something overridable (an environment variable or settings
     field read from the environment):
     ```
     Agent({
       description: "Check Alembic migrations against PostgreSQL",
       subagent_type: "iru-gate-runner",
       prompt: "In <app root>, start a throwaway database: `docker run -d --rm --name iru-fastapi-alembic-<random>
         -e POSTGRES_PASSWORD=<random> -p 127.0.0.1::5432 postgres:<the major the project's compose/CI uses, else
         18>`, read the host port with `docker port <name> 5432`, wait for `docker exec <name> pg_isready`. Point
         <the variable env.py reads> at it with the driver env.py expects (<e.g. postgresql+psycopg>), then run with
         <prefix>: `alembic upgrade head`, `alembic check`, `alembic downgrade base`, `alembic upgrade head`. Always
         `docker stop <name>` afterwards, on every path — and never touch any other container. Report back only:
         each command's success or its error line, and for `alembic check` either 'No new upgrade operations
         detected' or the operations it lists.",
       run_in_background: false
     })
     ```
     `alembic check` listing operations means models and revisions disagree — a missing or incomplete revision:
     fix it in the owning task (a new revision, or completing this bucket's own unapplied one) and re-run. A failed
     `downgrade` means this bucket's `downgrade()` doesn't reverse its `upgrade()`. If `env.py` hard-codes its URL,
     report the stage as **unverified (env.py URL not overridable)** rather than editing `env.py`.

   - **Environment failure** — report it as unverified with the cause, not as a code defect. Never stop or remove a
     container or Compose stack this skill didn't start.
   - **Assertion failure** — trace it to its task and fix. Common FastAPI causes fast tests can't see: a constraint
     SQLite doesn't enforce, naive vs. aware datetimes, JSONB/array queries, `IntegrityError` mapped to the wrong
     status, a lazy load (`MissingGreenlet`) hidden by SQLite fixtures. Keep the lock across a quick fix-and-re-run;
     for a long debugging session release it (3.9b), fix, and re-acquire before re-running. If the fix touched
     production code, re-run 3.3–3.8 afterwards.

9b. **Release the lock** as soon as 3.9's agents have reported back and any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release it on **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a
   hard stop. If you took the lock, you own releasing it before this skill returns.

Integration coverage is never measured or quoted as a gate figure here; the only coverage number this skill reports
is 3.5's container-free figure.

## Step 4 — Finalize progress notes and notify the user

Confirm the lock is released (3.9b); if a failure path skipped the release, release it now — this skill must never
return while holding it.

Then replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome, quoting
the **container-free** coverage figure, the integration result, and the Alembic result separately:

```
- [x] Task 2. **Add `POST /books`** — app/routers/books.py, app/services/books.py, revision 3f2a…; fast tests
  (94% lines, container-free); no new Ruff/mypy issues; OpenAPI builds; integration passed; alembic check clean.
```

If a stage was unverified, say so with the reason — `integration unverified (Docker unavailable)`, `alembic
unverified (env.py URL not overridable)`, `… (lock held by another group)` — silence reads as a pass. Leave blocked
tasks unchecked with their blocker note. Notify the user that group validation completed, with the bucket-wide
result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The shape detected** (runner prefix, layout, persistence stack, sync/async, Alembic, fast/integration selectors,
  quality tools wired) — and any test treated as integration because it couldn't be classified with confidence.
- **Per task**: files touched, Alembic revisions added, fast tests added/updated, **container-free coverage**,
  integration tests added, and the code-quality outcome.
- **Coverage exceptions accepted** — greenlet-concurrency artifacts (name the files, and whether the user declined
  the config fix), modules reachable only through the integration tier, with the reasoning.
- **Type-check results** (3.7), including any mypy error found only in a test file, and **security-relevant
  findings** (Ruff `S`, `ASYNC`, `FAST`; insecure neighbouring code the per-task agents reported) — the findings most
  worth escalating.
- **The smoke check** (3.8): app import, OpenAPI build, operation-id warnings, Alembic head count.
- **The container-backed result** (3.9): integration passed, failed-and-fixed, **unverified** with the reason, or
  "no integration selection"; Alembic upgrade/check/round trip likewise. Never let "unverified" read as "passed".
  Name any integration failure the fast tests missed, even once fixed — it shows where the fast tier is thin.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire: wait time, a broken stale lock (and
  its owner text), a timeout, and confirmation it was released.
- Whether an import-boundary check exists and passed (or a contract changed, and why), whether a new quality issue
  was left in place as unavoidable, whether license-header generation was skipped, whether a sequential-ordering
  deviation was applied in Step 2, and which task(s), if any, stopped on a blocker.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
