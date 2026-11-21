---
name: iru-nestjs-code-one-task
description: "Invoked as /iru-nestjs-code-one-task <task description> (the task's text with its implementation_plan.md checkbox lines) via Skill. Implements one NestJS (ESM or CommonJS, any ORM, standard or monorepo) plan task and its tests, unit/e2e specs written, ticks the checkbox with a group-validation-pending note; no baseline or full gates, which iru-nestjs-code-one-task-group runs."
model: sonnet
allowed-tools: Read Edit Write Bash(npm *) Bash(npx *) Bash(pnpm *) Bash(yarn *) Bash(node *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Bash(cat *)
---

# Implement one plan task (NestJS)

Carry out a single task's implementation, tests, and checkbox update against a NestJS application — nothing else.
This is the narrowest execution unit for a Nest project in the `iru-code` pipeline: `iru-nestjs-code-one-task-group`
calls it once per task in a bucket (potentially many at once, in parallel agents), then handles license headers,
TSDoc, coverage, lint/type-check, the build, the full unit suite, and the e2e stage itself, once for the whole
bucket.

Two things make it different from `iru-typescript-code-one-task`:

1. **Nest punishes some mistakes silently.** A DTO imported with `import type`, a missing `.js` suffix copied into
   the wrong module format, a global pipe that only exists in `main.ts`, per-request state on a singleton — each
   compiles, and several pass unit tests. The reference files exist mostly to prevent those.
2. **It runs the unit specs it touched, and only those.** Nest unit specs compile a small testing module in well
   under a second, so validating one task's own work is cheap; everything heavier — e2e specs (which boot the whole
   `AppModule`, often with a database), coverage, the full suite — belongs to the group.

It uses a medium model on purpose: the plan already carries the hard reasoning, and the guidance lives in
`reference/` rather than being re-derived per task.

## Step 1 — Detect the project's flavor, then read only the reference material this task needs

Detect once, from files rather than assumptions (the same facts the group skill and the `iru-typescript-*` gates
detect, so a task never disagrees with its validator):

1. **Nest app root and major version** — the `package.json` with `@nestjs/core` in `dependencies` (the repository
   root, or a subdirectory in a polyglot repository); major from its version range.
2. **Module format** — `"type": "module"` → **ESM** (relative imports end in `.js`); absent → **CommonJS**
   (extensionless). Confirm against an existing `src/` import rather than trusting the field alone.
3. **Test runner** — `vitest` + `vitest.config.ts`/`vitest.config.e2e.ts` → Vitest; `jest` + `jest.config.*` /
   `test/jest-e2e.json` → Jest. Read the `test` and `test:e2e` scripts for the exact invocation (the CommonJS
   scaffold runs Jest as `node --experimental-vm-modules ./node_modules/jest/bin/jest.js`).
4. **HTTP platform** — `@nestjs/platform-fastify` → Fastify; otherwise Express.
5. **ORM** — `@nestjs/typeorm`, `prisma`/`@prisma/client` (+ `prisma/schema.prisma`), `@nestjs/mongoose`,
   `drizzle-orm`, `@mikro-orm/nestjs`; none → no persistence section applies.
6. **Validation style** — `class-validator` DTO classes vs. Standard Schema (`zod`/`valibot`/`arktype` with
   `@Body({ schema })`); and **where global enhancers are registered** (`APP_*` providers, a shared `configureApp`-
   style function, or inline in `main.ts`).
7. **Workspace mode** — `nest-cli.json` with `"monorepo": true` and a `projects` map → apps under `apps/`, libs
   under `libs/`, `@app/*` aliases; otherwise standard mode.
8. **Architecture** — ports and adapters only if `reference/hexagonal.md`'s recognition criteria hold; otherwise
   feature modules (controller → service → repository).
9. **Package manager** — `packageManager` field, else the lockfile (`package-lock.json` → npm, `pnpm-lock.yaml` →
   pnpm, `yarn.lock` → yarn).

Then read `reference/README.md` first — it is short and routes you to the rest — then read only what it points at
for this task:

| Read | When |
|---|---|
| `reference/code-style.md` | **Always.** |
| `reference/testing.md` | **Always** — writing the tests is part of the task. |
| `reference/modules-and-di.md` | The task adds/changes a module, provider, token, `imports`/`exports`, scope, or a circular dependency. |
| `reference/validation-and-dtos.md` | The task adds/changes a DTO, route parameter, query, pipe, or reads request input. |
| `reference/enhancers.md` | The task adds/changes a guard, interceptor, pipe class, filter, middleware, custom decorator, or a global registration. |
| `reference/configuration.md` | The task reads or adds a setting/environment variable or touches `ConfigModule`. |
| `reference/persistence.md` | The task touches an entity/schema/model, repository, query, transaction, or migration (read the shared rules plus the detected ORM's section only). |
| `reference/security.md` | The task touches authN/authZ, rate limiting, CORS/headers/CSRF, secrets, ownership — or adds an endpoint that shouldn't be public. |
| `reference/cli-generators.md` | The task creates a new module/controller/service/resource/enhancer/middleware/library. |
| `reference/hexagonal.md` | Only when item 8 detected ports and adapters. |

Don't read the optional files speculatively — for a change inside one existing service method, `code-style.md` and
`testing.md` are the whole list.

If `reference/` is missing (e.g. the skill directory was copied without it), proceed using this repository's own
`CLAUDE.md` conventions and the surrounding code's existing style, and say so in Step 7's report — don't guess at
what the reference would have said.

## Step 2 — Re-check the current code state

Read the actual current content of every file the task touches — and the feature's `*.module.ts`, plus whichever
file holds the global setup (`app.module.ts`, `main.ts`, the `configureApp`-style helper) when the task touches
validation, enhancers, or configuration. Don't trust any "current code state" notes passed in with the task; other
tasks in the same bucket may be landing concurrently.

Note what the surrounding code already does about the things `reference/` has opinions on — DTO style, where
errors become HTTP statuses, which ORM idiom, how tests build their modules. The reference's rules are the default
for new code; a feature that consistently does something else wins, and the divergence goes in Step 7's report —
except for the silent-defect rules `reference/README.md` lists, which you don't copy.

## Step 3 — Implement exactly what the task specifies

The named files, the described module/controller/service/DTO/enhancer, the behavior. Apply the reference files
from Step 1, plus this repository's own `CLAUDE.md` where it is more specific.

- **New Nest elements**: when `reference/cli-generators.md` says the project's conventions allow it, generate the
  skeleton with the project's local CLI (`npx nest g … --dry-run` first, then for real), then replace every
  placeholder — service bodies, empty DTOs/entities, `+id` coercion, permissive guards, `any` — and register every
  enhancer it generated unregistered. Hand-writing the same files is equally fine.
- **Prisma**: if the task changed `prisma/schema.prisma`, run `npx prisma generate` so the code you write compiles
  against the new client, and add the migration per `reference/persistence.md`.
- **Format the files you touched** with the project's formatter when it has one configured
  (`npx prettier --write <files>` for a `.prettierrc`, or `npx biome format --write <files>`) — only those files,
  never the whole project.

Non-negotiable, because a later gate (or production) enforces them:

- **Module format consistency** — `.js` suffix on every relative import in an ESM project, none in a CommonJS one.
- **DTO classes and injected classes are value imports**, never `import type`; type-only symbols in decorated
  signatures use `import type`.
- **Full TSDoc** on every exported class and public method, with `@param`, `@returns`, and `@throws` for every
  `HttpException`/domain error a caller can see.
- **No `any`**, `readonly` by default, named exports, no `console.log`.
- **No per-request state on singletons**, no `synchronize: true`, no new global enhancer registered twice.
- **No new runtime dependency** unless the task explicitly calls for it — a blocker to report (Step 5), not a
  decision to make quietly (`nest g res` adding `@nestjs/mapped-types` included).

Don't add anything the task didn't ask for — no speculative abstractions, no unrelated cleanup, no restyling of code
the task doesn't touch.

## Step 4 — Write the tests, and run only the unit specs you touched

Follow `reference/testing.md` and the existing specs in the same feature:

- **Unit specs** (`*.spec.ts` next to the source) for every class the task added or changed, covering the new
  behavior and every `@throws`. Replace any generator placeholder spec (`it('should be defined')`).
- **E2E specs** (`test/*.e2e-spec.ts`, or wherever the project's e2e config looks) when the task adds or changes an
  endpoint's HTTP contract — status codes, validation, auth — through the project's shared bootstrap
  (`configureApp`-style helper or `APP_*` providers). **Write them; don't run them.**

Run the unit spec files this task created or changed — nothing else — with the detected runner:

```bash
npx vitest run src/books/books.service.spec.ts src/books/books.controller.spec.ts
# CommonJS/Jest: the `test` script's command plus the paths, e.g.
node --experimental-vm-modules ./node_modules/jest/bin/jest.js src/books/books.service.spec.ts
```

(substitute `pnpm exec`/`yarn` for `npx` per the package manager). If the project uses Prisma and the generated
client is missing (fresh checkout), run `npx prisma generate` first.

**Never run**: the e2e config (`vitest.config.e2e.ts`, `test/jest-e2e.json`, `npm run test:e2e`), `--coverage`,
the unscoped suite, `nest build`, lint, or anything that starts Docker, Testcontainers, or a database. Several of
these agents run concurrently and the group's e2e stage is serialized behind a repository-wide lock; an e2e run
here would bypass it.

If a unit spec fails, fix the implementation or the test and re-run until green — don't hand back a red test. If
some behavior can only be verified end to end, write that e2e spec, leave it to the group, and say so in Step 7.

## Step 5 — When to interrupt the user

Keep interruptions rare — most tasks should complete unattended. Stop and use `AskUserQuestion` (or plain text if no
real choice is being offered) only when:

- The task is ambiguous in a way that changes correctness or scope and can't be safely inferred — in particular,
  **whether a new endpoint is public** or which role may call it, when the task doesn't say.
- The described approach is infeasible against the real code (it names a module, provider, or entity that doesn't
  exist and isn't a trivial typo), or needs a new runtime dependency or a test-infrastructure change (an SWC plugin,
  a Testcontainers setup) the task didn't ask for.
- Step 1's detection is genuinely ambiguous in a way that changes the code you'd write (two ORMs in one feature, a
  half-migrated ESM/CommonJS project).

Don't stop merely because the task is nontrivial. Don't report the task done to work around a blocker; report it as
blocked (Step 7) with what was tried, what failed, and why. A conflict between `reference/` and the surrounding code
is *not* a reason to stop: follow the surrounding code and report the divergence.

## Step 6 — Check off this task's checkbox now

If this task did not stop on a blocker, update `implementation_plan.md` at the repository root before reporting
back. Re-read the file fresh (other tasks in the same bucket may be landing concurrently), locate this task's own
checkbox line by the exact text passed in with the task, flip `[ ]` to `[x]` for it and for every sub-task covered by
the work just completed, and add a short note naming the files touched, e.g. `- [x] Task 2. **Add `POST /books`** —
BooksController/BooksService/CreateBookDto, unit specs added (passing), e2e spec added; group validation pending.`
The "group validation pending" wording is what `iru-nestjs-code-one-task-group` replaces with the final outcome.
Edit only this task's own line(s) — never surrounding lines or other tasks' checkboxes.

If this task stopped on a blocker, leave its checkbox untouched.

## Step 7 — Report the outcome

Hand control back to the caller with a short summary:

- **The flavor Step 1 detected** — ESM/CommonJS, Vitest/Jest, Express/Fastify, ORM, validation style, standard/
  monorepo (and which project), layered/hexagonal — and the reference files actually read.
- **The files touched**, including module files and any global-setup file, and any `nest g` command used.
- **The unit specs added/updated and their result**, and **the e2e specs written but not run** (the group must run
  them), and whether any of them need Docker/a database.
- **Whether `prisma generate` was run** or a migration added (and whether it was generated or hand-written).
- **Whether this task stopped on a blocker**, with enough detail for the caller to surface it.
- **Any deliberate deviation from `reference/`** (matching a feature that registers pipes in `main.ts`, a default
  export a tool requires, …), and any **silent-defect pattern found in surrounding code** and left alone because it
  was out of scope — or say so if `reference/` was missing entirely.

This skill has already checked off its own checkbox with a pending-validation note (Step 6). It never captures a
quality baseline, adds license headers, runs a TSDoc audit, coverage, lint/type-check, `nest build`, the full suite,
or any e2e test.
