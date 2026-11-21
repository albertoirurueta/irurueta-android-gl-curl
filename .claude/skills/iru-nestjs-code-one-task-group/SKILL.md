---
name: iru-nestjs-code-one-task-group
description: "Invoked as /iru-nestjs-code-one-task-group <bucket text> (group tasks, Parallelizable verdict, code state) via Skill. Implements one NestJS task bucket: one baseline, iru-nestjs-code-one-task per task (parallel when allowed), then validates once (license, TSDoc, unit tests, 80% coverage, lint/tsc, nest build, locked e2e stage); finalizes checkboxes."
model: sonnet
allowed-tools: Read Edit Write Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(node *) Bash(docker *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git rev-parse *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *) Bash(date *) Bash(mkdir .integration-test.lock*) Bash(rmdir .integration-test.lock*) Bash(rm -f .integration-test.lock/owner) Skill Agent
---

# Implement one task-group bucket (NestJS)

Carry out every NestJS task in one plan-group bucket end to end: one quality baseline up front, implement each task
(in parallel where safe), then validate everything the bucket touched in a single consolidated pass instead of once
per task.

It is the NestJS counterpart to `iru-typescript-code-one-task-group`, built on the same gate skills —
`iru-typescript-test`, `iru-typescript-coverage`, `iru-typescript-code-quality`, `iru-typescript-tsdoc` — each of
which already recognizes NestJS (`@nestjs/core`) and knows its unit/e2e split. Each is invoked through an
`iru-gate-runner` agent so its report never lands in this context; fall back to the explicit command shown only when
the skill isn't installed in the consuming repository.

Three differences from the plain TypeScript group skill shape the validation order:

1. **This skill owns the e2e stage — and its serialization.** `iru-nestjs-code-one-task` runs only the unit specs
   it touched. E2E specs boot the whole `AppModule`, and often a database through Testcontainers or a shared
   `compose.yaml`, so they run here, once per bucket, behind the repository-wide lock (Step 0, Step 3.9a).
2. **The coverage gate is measured from unit tests alone**, with Nest's pure wiring (`*.module.ts`, `main.ts`) and
   generated code excluded. E2E tests reach code unit tests miss — that's welcome and reported — but never satisfy
   the gate, so the verdict stays reproducible on a machine without Docker.
3. **Type-checking is its own gate.** Vitest (Oxc/esbuild), `ts-jest` in isolated mode, `@swc/jest`, and the SWC
   builder all strip types without checking them, and `nest build` with the default `tsconfig.build.json` never
   compiles spec files. `tsc --noEmit` (inside `iru-typescript-code-quality`) is the only check that sees a type
   error in a spec, so it is never skipped.

It uses a medium model on purpose: the plan carries the hard reasoning; this skill drives execution of it.

Agent dispatch: call the catalog agents as `subagent_type: "<agent-name>"` (for example `"iru-gate-runner"`) when installed under `.claude/agents/`, or as `"iru-core:<agent-name>"` when installed as a plugin — use whichever name appears in your available agent types. Every `subagent_type` below is written in the first form.

## Step 0 — Read this before running anything e2e

**Only one e2e run may be in flight against this repository at any moment.** A Nest e2e suite started while another
is mid-run can share its database or container stack (rows, collections, migrations applied twice), collide on a
fixed port or a reused Testcontainers container, and produce failures nothing in the code explains — or passes
that only happened because the other run left the right data behind. Both look like real results, which is why
this is a hard rule, not a performance note. To keep it simple and safe, the e2e stage **always** takes the lock,
whether or not it needs Docker.

This applies at three levels:

1. **Across buckets/groups** — enforced by the lock in Step 3.9a, regardless of what the caller does.
2. **Within this bucket** — Step 2's per-task agents never run e2e specs (the per-task skill forbids it), so the
   only e2e run is Step 3.9's single invocation.
3. **Within the run itself** — don't add parallelism flags to the e2e command and don't change the project's
   e2e config (`fileParallelism`, `--runInBand`, `maxWorkers`) to "speed it up".

The lock directory lives at the **repository root** (`git rev-parse --show-toplevel`) — the same path every other
group skill in this catalog uses (`iru-java-springboot-code-one-task-group`, `iru-quarkus-code-one-task-group`,
`iru-php-code-one-task-group`, `iru-nextjs-code-one-task-group`, …), so a polyglot repository serializes all of them
together. Run every lock command from the repository root.

## Step 1 — Detect the flavor once, then capture one pre-change quality baseline

Detect once, and reuse for every step below (the same facts `iru-nestjs-code-one-task` and the gate skills detect,
so the prompts can name exact invocations):

- **Nest app root(s)** — the `package.json` with `@nestjs/core`; in monorepo mode (`nest-cli.json` with
  `"monorepo": true`), the `projects` the bucket's tasks touch (`apps/<name>`, `libs/<name>`).
- **Module format and test runner** — `"type": "module"` + Vitest (`vitest.config.ts`, `vitest.config.e2e.ts`), or
  CommonJS + Jest (`jest.config.*`, `test/jest-e2e.json`). Read the `test`, `test:e2e`, `test:cov`, `lint`, and
  `build` scripts for the exact commands and config paths.
- **Package manager** — `packageManager`, else the lockfile (`package-lock.json` → npm, `pnpm-lock.yaml` → pnpm,
  `yarn.lock` → yarn).
- **Prisma** — `prisma` in `devDependencies` and a `schema.prisma` (default `prisma/schema.prisma`, or the path the
  Prisma config file names). Note the generator `output` directory — the client is generated, uncommitted code that
  must be excluded from coverage and quality scopes.
- **Builder** — `nest-cli.json` `compilerOptions.builder` (`tsc` default in standard mode, `swc`, or `rspack`, the
  monorepo default) and whether `typeCheck` is on.
- **Architecture-boundary check** — a configured `dependency-cruiser` (`.dependency-cruiser.*`), an
  `eslint-plugin-boundaries`/Nx boundaries rule, or a `lint:arch`-style script. Most Nest projects have none.
- **The e2e stage** — whether it is wired at all (a `test:e2e` script or an e2e config with at least one
  `*.e2e-spec.ts`), and **whether it needs Docker**: `testcontainers`/`@testcontainers/*` in `devDependencies`, or
  e2e specs/setup files referencing `testcontainers`, `docker compose`, or a `compose.yaml`
  (`grep -rlE "testcontainers|compose" test/ apps/*/test/ 2>/dev/null`). `mongodb-memory-server` and in-memory
  SQLite need no Docker. If Docker is needed, run `docker info` now and record whether it's available.

Then collect the files every task in the bucket is expected to touch, and capture a single pre-change baseline by
delegating to `iru-gate-runner`:

```
Agent({
  description: "Capture pre-change quality baseline for task group",
  subagent_type: "iru-gate-runner",
  prompt: "Invoke Skill({skill: \"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}) in the NestJS
    project at <app root>. Report back only the per-rule-id issue counts (and the issues) for these files, grouped by
    tool (Oxlint or ESLint, tsc, Prettier).",
  run_in_background: false
})
```

Record the result as this bucket's baseline — empty if every file is new. Step 3.7 compares against it so only
issues this bucket introduces are flagged. Skip if `iru-typescript-code-quality` isn't installed, and say so in
Step 5.

Don't touch the e2e lock here — a lock held through Step 2's implementation work would block every other group for
the whole bucket. It is acquired in Step 3.9a, immediately before the run that needs it.

## Step 2 — Implement each task via `iru-nestjs-code-one-task`

- **Bucket marked `Parallelizable: yes`**: issue all the `Agent` calls together, in the same response:
  ```
  Agent({
    description: "Implement <task N> via iru-nestjs-code-one-task",
    subagent_type: "iru-isolated-skill-executor",
    prompt: "Invoke Skill({skill: \"iru-nestjs-code-one-task\", args: \"<the task's full text, including its exact
      implementation_plan.md checkbox line(s) for itself and its sub-tasks, its sub-tasks' own text, and any
      relevant Current code state context>\"}). Report back: the detected flavor, the files touched (including
      module and global-setup files), the unit specs added/updated and their result, the e2e specs written but not
      run, whether prisma generate was run or a migration added, any deviation from the skill's reference/, and
      whether the task stopped on a blocker. Unit specs only — do not run the e2e config, coverage, the full suite,
      nest build, or anything that starts Docker or a database: sibling agents run concurrently and the group's e2e
      stage is serialized behind a repository-wide lock.",
    run_in_background: false
  })
  ```
- **Bucket marked `Parallelizable: no`**: invoke them one at a time in the plan's order, waiting for each.

**One ordering constraint Nest imposes regardless of the verdict**: two tasks that both edit the same module file
(`books.module.ts`, `app.module.ts`) or the same global-setup file, or where one task's provider/DTO is injected or
imported by another's, must run sequentially (provider first) — parallel edits to one `@Module({...})` literal
clobber each other. Check for that before fanning out; if the group is marked parallelizable, run just those tasks
sequentially and note the deviation in Step 5.

As each agent reports back, re-read `implementation_plan.md` and confirm its box is checked; if not (a concurrent-
write race), flip it yourself with the same "group validation pending" note. Notify the user that this task's
implementation and unit specs landed. A task that stopped on a blocker is recorded and excluded from Step 3's
validation, without blocking the rest of the bucket.

## Step 3 — Validate the whole bucket once

Derive the validation scope first: the union of the files each agent reported, cross-checked against a fresh
`git status`, `git diff --name-only`, and `git diff --name-only --cached` (a task's report can miss a module file the
generator updated). Drop generated files (the Prisma client output, `dist/`) from it. Then run, in this order —
cheapest first; **3.1–3.8 gate the bucket, 3.9 runs only once they pass**:

0. **Prisma client** — only if Step 1 detected Prisma: run `npx prisma generate` (per the package manager) once
   now, and again after any later fix that changes `schema.prisma`, so every compile/test step below sees a client
   matching the schema. A failure here is a schema defect to fix before anything else.

1. **License headers**, via `iru-gate-runner`: `Agent({description: "Add license headers for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-check-license\", args:
   \"<file1,file2,...>\"}) scoped to every file this bucket's tasks added or modified. Report back only which files
   were missing a header vs. fixed vs. already compliant, and — if no header convention existed anywhere in the
   repo — whether the user chose to skip or generate one."})`. Respect a skip choice for the rest of the run. Skip if
   `iru-check-license` isn't installed.

2. **TSDoc**, via `iru-gate-runner`: `Agent({description: "Update TSDoc for task group", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-tsdoc\", args: \"<source files only — no
   *.spec.ts / *.e2e-spec.ts>\"}) in this NestJS project. Report back only which exported symbols (controllers, route
   handlers, services, DTOs and their fields, modules, guards, pipes, interceptors, filters) were documented or
   completed, and whether the doc-build verification (Compodoc or TypeDoc, whichever the project configured) passed
   or the run was audit-only because neither is configured."})`. Fix and re-invoke until any configured doc build
   passes. Skip if `iru-typescript-tsdoc` isn't installed.

3. **Architecture boundaries** — only if Step 1 found a configured check: run it via `iru-gate-runner` (the
   project's own script, e.g. `npm run lint:arch`, or `npx depcruise <src dirs> --config <config>`), reporting back
   only violations (rule, from, to). Fix the code, not the rule; relax a rule only if it's genuinely wrong for the
   project, and record that for Step 5. If none is configured, note it and continue — don't add one here.

4. **Scoped unit tests**, via `iru-gate-runner`: `Agent({description: "Run unit tests for task group",
   subagent_type: "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"<the unit
   *.spec.ts files affected by this bucket, space-separated>\"}) in this NestJS project (unit suite only — no e2e
   selector). If that skill isn't installed, run `npx vitest run <files>` (ESM/Vitest) or the project's Jest command
   plus the files (`node --experimental-vm-modules ./node_modules/jest/bin/jest.js <files>` on the CommonJS
   scaffold). If everything passes, report back only that. If anything fails, report back only the failing test
   names, the failure reason, and the assertion message for each."})`. Trace any failure to its task, fix, and
   re-invoke until green.

5. **Unit-test coverage — the 80% gate**, via `iru-gate-runner`:
   ```
   Agent({
     description: "Check unit-test coverage for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-typescript-coverage\", args: \"<selector covering the bucket's unit specs,
       plus every source file this bucket changed or added>\"}) in this NestJS project. Measure the UNIT suite only —
       the default vitest.config.ts / jest.config, never the e2e config, nothing that starts Docker or a database.
       Exclude pure wiring and generated code: **/*.module.ts, src/main.ts (apps/*/src/main.ts in a monorepo),
       <the Prisma generator output dir, if any>, and migration files. If the skill isn't installed, run
       `npx vitest run --coverage --coverage.reporter=json-summary --coverage.reporter=lcov
       --coverage.include='src/**/*.ts' --coverage.exclude='**/*.module.ts' --coverage.exclude='src/main.ts'` (plus
       the other exclusions) or, for Jest, the project's Jest command with `--coverage
       --coverageReporters=json-summary --collectCoverageFrom='src/**/*.ts'
       --collectCoverageFrom='!**/*.module.ts' --collectCoverageFrom='!src/main.ts'`, and read
       coverage/coverage-summary.json. Report back, per target file, its line and branch coverage percentage and its
       uncovered line ranges, and confirm the run was unit-only.",
     run_in_background: false
   })
   ```
   **Gate: every changed/added source file at ≥ 80% line coverage, and ≥ 80% branch coverage** — with one
   Nest-specific allowance. Under `emitDecoratorMetadata` the compiler emits `typeof X === "undefined" ? Object : X`
   for every decorated constructor/handler parameter, and V8 counts the unreachable arm as an uncovered branch on
   the decorator/constructor line (verified on the Nest 12 scaffold: 50% branches on fully tested controllers, the
   uncovered "line" being the constructor or the `@Body()` handler). Branch shortfalls whose uncovered lines are
   *only* such decorator/constructor lines are artifacts — accept them and say so in Step 5; never write tests
   chasing them. Any other shortfall: add **unit** tests via the task that owns the file, re-run 3.4, re-check here.
   **An e2e test never closes the gap** — a file whose logic is reachable only end to end has no fast feedback loop;
   either unit-test it (a shared `configureApp`-style helper takes a two-line stub-app test) or record it in Step 5
   as an accepted exception with the reasoning.

6. **Full unit suite**, via `iru-gate-runner`: `Agent({description: "Run full unit test suite", subagent_type:
   "iru-gate-runner", prompt: "Invoke Skill({skill: \"iru-typescript-test\"}) with no selector in this NestJS
   project (unit suite only), or run the project's `test` script (`npm test` / `pnpm test` / `yarn test`) if the
   skill isn't installed. Never the e2e suite. If everything passes, report back only that; otherwise only the
   failing test names, reasons, and assertion messages."})`. In a monorepo the root config covers every app and lib.
   Fix regressions and re-invoke until green.

7. **Code quality — lint, type-check, format**, diffed against Step 1's baseline, via `iru-gate-runner`:
   `Agent({description: "Check for new quality issues in task group", subagent_type: "iru-gate-runner", prompt:
   "Invoke Skill({skill: \"iru-typescript-code-quality\", args: \"<file1,file2,...>\"}) in this NestJS project —
   Oxlint (`oxlint --type-aware`, the Nest 12 scaffold's linter) or ESLint, whichever the `lint` script runs, plus
   `tsc --noEmit -p tsconfig.json` (which, unlike `nest build`, includes spec and e2e files) and Prettier. Exclude
   generated code (<Prisma output dir>, dist/). Compare against this pre-change baseline: <baseline from Step 1>.
   Report back only newly appearing issues, each with tool, rule id, file, and line."})`. Any new issue is a defect
   this bucket introduced — trace it to its task, fix it, re-run 3.4 and 3.6, and re-check. Treat **every `tsc`
   error as blocking**, including those in spec files: the test runners never reported them. Leave an issue in place
   only if fixing it would contradict what its task explicitly specifies, and record why. Skip if
   `iru-typescript-code-quality` isn't installed — and then run `npx tsc --noEmit -p tsconfig.json` through
   `iru-gate-runner` anyway, because nothing else type-checks.

8. **Build — once**, via `iru-gate-runner`: `Agent({description: "Build NestJS project", subagent_type:
   "iru-gate-runner", prompt: "Run the project's build (`npm run build`, i.e. `nest build`; in monorepo mode
   `npx nest build <project>` for each touched app, or `npx nest build --all` when a touched lib is shared). Report
   back only success, or each error with file, line, and message — no build log."})`. This catches what the
   type-check doesn't: `tsconfig.build.json` includes/excludes, CLI plugins (Swagger/GraphQL metadata), asset
   copying, the SWC/Rspack builder's own failures, path aliases. Fix and re-run until it succeeds.

9a. **Acquire the repository-wide e2e lock** — immediately before 3.9, never earlier.

   Skip 3.9a–3.9b entirely (no lock, no run) when:

   - **No e2e suite is wired** (Step 1): say so in Step 5 rather than reporting a stage that never existed. If this
     bucket's tasks *wrote* e2e specs but the project has no e2e config, that's a finding to report.
   - **The suite needs Docker and Docker is unavailable** (Step 1's probe, or `docker info` re-run now): report the
     e2e stage as **unverified (Docker unavailable)** and continue to Step 4 — the coverage verdict from 3.5 stands
     on its own.

   From the repository root:

   ```bash
   mkdir .integration-test.lock 2>/dev/null && echo ACQUIRED || echo HELD
   ```

   - **`ACQUIRED`** — immediately write `.integration-test.lock/owner` (via the `Write` tool) with this bucket's
     identifying text (task numbers/titles), "NestJS e2e", the app root/project, and
     `date -u +'%Y-%m-%dT%H:%M:%SZ'`. Ensure `.integration-test.lock/` is in the repository's root `.gitignore`
     (add the line if missing) — it's transient local state and must never be committed.
   - **`HELD`** — another group is running its integration/e2e stage. Tell the user this bucket is waiting (naming
     the holder from `cat .integration-test.lock/owner` if readable), then re-run the `mkdir` roughly every 30
     seconds. Don't run the e2e suite anyway, and don't "just check" one e2e file — it boots the same app and
     database.

   Two bounded escapes, so a crashed run can't deadlock the pipeline:

   - **Stale lock** — if `find .integration-test.lock -maxdepth 0 -mmin +60` returns the directory, its holder has
     run for over an hour and almost certainly died. Tell the user what the owner file says, break it
     (`rm -f .integration-test.lock/owner && rmdir .integration-test.lock`), acquire fresh, and say so in Step 5.
   - **Wait timeout** — still held (and not stale) after ~45 minutes of polling: stop waiting, report the stage as
     **unverified — blocked by a concurrent integration run**, continue to Step 4, and name it in Step 5.

9. **E2E tests** — only now, with everything above clean and the lock held:
   ```
   Agent({
     description: "Run NestJS e2e tests for task group",
     subagent_type: "iru-gate-runner",
     prompt: "Invoke Skill({skill: \"iru-typescript-test\", args: \"e2e\"}) in this NestJS project at <app root> to run
       the whole e2e suite (the config the project's test:e2e script names). If the skill isn't installed, run the
       project's `test:e2e` script. Run exactly this one command — do not add parallelism flags, do not change the
       e2e config, and do not run any e2e file separately alongside it. It boots the whole application and may start
       Testcontainers or connect to a database; wait for it rather than backgrounding it. Report back only: whether
       all e2e tests passed; for each failure the test name, reason, and assertion message; and separately whether
       any failure was an environment problem (Docker unavailable, image pull failure, port in use, container or
       database startup timeout, a missing env variable for the database) rather than an assertion failure. Do not
       dump the runner output.",
     run_in_background: false
   })
   ```
   Run this agent on its own — never in the same response as another test-running agent. `run_in_background: false`
   is required: the lock means something only while this skill holds it for exactly as long as the suite runs.

   - **Environment failure** — report it as unverified with the cause, not as a code defect. One self-inflicted
     cause worth checking: a developer's own database/compose stack already bound to the port
     (`docker compose ps`). Report it; **never** stop a stack this skill didn't start.
   - **Assertion failure** — trace it to its task and fix. Common Nest causes a unit test can't see: global setup
     missing from the e2e bootstrap (`main.ts`-only pipes/prefix — the fix is a shared `configureApp` or `APP_*`
     providers, not duplicating code in the spec), a DTO imported with `import type` (validation silently off), a
     provider not exported/imported, a global enhancer registered twice. Keep the lock across a quick
     fix-and-re-run; for a long debugging session release it (3.9b), fix, and re-acquire before re-running. If the
     fix touched production code, re-run 3.4–3.8 afterwards.

9b. **Release the lock** as soon as 3.9's agent has reported back and any fix-and-re-run cycle is over:

   ```bash
   rm -f .integration-test.lock/owner && rmdir .integration-test.lock
   ```

   Release it on **every** exit path — pass, failure, environment failure, a blocked task, a user interrupt, or a
   hard stop. If you took the lock, you own releasing it before this skill returns.

## Step 4 — Finalize progress notes and notify the user

Confirm the e2e lock is released (3.9b); if a failure path skipped the release, release it now — this skill must
never return while holding it.

Then replace each task's "group validation pending" note in `implementation_plan.md` with the final outcome,
quoting the **unit-only** coverage figure and the e2e result separately:

```
- [x] Task 2. **Add `POST /books`** — BooksController/BooksService/CreateBookDto; unit specs (96% lines,
  unit-only); e2e books.e2e-spec.ts passed; no new Oxlint/tsc/Prettier issues; nest build OK.
```

If the e2e stage was unverified, say so with the reason — `e2e unverified (Docker unavailable)` or `e2e unverified
(lock held by another group)` is actionable; silence reads as a pass. Leave blocked tasks unchecked with their
blocker note. Notify the user that group validation completed, with the bucket-wide result.

## Step 5 — Report the bucket's outcome

Hand control back to `iru-code-one-task-group` with a summary covering the whole bucket:

- **The flavor detected** (ESM/Vitest or CommonJS/Jest, Express/Fastify, ORM, Prisma, builder, standard/monorepo)
  and, **per task**: files touched, unit specs added/updated, **unit-only coverage**, e2e specs added, and the
  code-quality outcome.
- **Coverage exceptions accepted** — decorator-metadata branch artifacts (name the files), and any file reachable
  only through e2e, with the reasoning.
- **Type-check and build results** (3.7, 3.8), including any `tsc` error found only in a spec file — evidence the
  runners weren't type-checking.
- **The e2e result** (3.9): passed, failed-and-fixed, **unverified** with the reason, or not wired. Never let
  "unverified" read as "passed". Name any e2e failure that unit tests missed, even once fixed — it shows where the
  unit layer is thin.
- **How the lock behaved** whenever it wasn't an immediate uncontended acquire: wait time, a broken stale lock (and
  its owner text), a timeout, and confirmation it was released.
- Whether `prisma generate` ran, whether an architecture check exists and passed (or a rule was relaxed, and why),
  whether a new quality issue was left in place as unavoidable, whether license-header generation was skipped,
  whether a sequential-ordering deviation was applied in Step 2, and which task(s), if any, stopped on a blocker.

`implementation_plan.md`'s checkboxes are already checked and finalized (Steps 2 and 4) and the user-facing
notifications have gone out — the caller doesn't need to redo that bookkeeping for this bucket.
