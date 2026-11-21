# NestJS development reference

Code agreements for implementing a task in a NestJS application — this catalog's general TypeScript agreements
condensed and restated for Nest, plus one file per Nest concern (modules and DI, validation, enhancers,
configuration, persistence, security, testing, the CLI generators). These files exist so
`iru-nestjs-code-one-task` can load **only** what the task in front of it actually needs, instead of carrying
every rule in context on every task.

## Read this much, and no more

Detect the project's flavor first (`iru-nestjs-code-one-task` Step 1 — module format, test runner, HTTP platform,
ORM, Nest major, standard vs. monorepo), then read:

| Read | When |
|---|---|
| `code-style.md` | **Always.** Strict typing, no `any`, `readonly`, named exports, error handling, async — plus the Nest-specific rules: `.js` relative-import suffix under ESM, decorator metadata, DTO classes imported as values, controller → service → repository. |
| `testing.md` | **Always** — writing the tests is part of implementing the task. Unit `*.spec.ts` with `Test.createTestingModule`, overrides, e2e with supertest + a shared `configureApp`, database tests, SWC. |
| `modules-and-di.md` | The task adds or changes a module, a provider, an injection token, an `exports`/`imports` entry, a scope, or a circular dependency. |
| `validation-and-dtos.md` | The task adds or changes a DTO, a route parameter, a query, a pipe, or anything that reads request input. |
| `enhancers.md` | The task adds or changes a guard, interceptor, pipe class, exception filter, middleware, or custom decorator — or registers one globally. |
| `configuration.md` | The task reads an environment variable or setting, adds one, or touches `ConfigModule`. |
| `persistence.md` | The task touches an entity/schema/model, a repository, a query, a transaction, or a migration (TypeORM, Prisma, Mongoose, Drizzle, MikroORM). |
| `security.md` | The task touches authentication, authorization, rate limiting, CORS, security headers, CSRF, secrets, or ownership checks — or adds an endpoint that should not be public. |
| `cli-generators.md` | The task creates a new module, controller, service, resource, guard, interceptor, pipe, filter, middleware, or library. |
| `hexagonal.md` | Only when the repository clearly follows ports and adapters (Step 1 tells you how to recognize it). |

A task that only changes logic inside one existing service method needs `code-style.md` plus `testing.md`, and
nothing else. Don't read the rest speculatively.

## The three rules that override everything else

1. **Match the surrounding code.** Every rule here is the default for *new* code. If the module or file you're
   editing consistently does something else — CommonJS imports, Jest instead of Vitest, `useGlobalPipes` in
   `main.ts`, a request-scoped provider — follow it and note the divergence in the report rather than converting
   it as a side effect of an unrelated task. The one exception is a rule whose violation is a silent runtime
   defect (a DTO imported with `import type`, per-request state on a singleton, `synchronize: true` outside a
   throwaway database): don't copy those, and say so in the report.
2. **A task implements what the task says.** These references tell you *how* to write what was asked for. They
   never license adding a guard, a global interceptor, a CQRS layer, a new ORM, or a "while I'm here" refactor
   the plan didn't ask for.
3. **This skill doesn't validate the group.** It writes unit tests and runs only the spec files it touched; it
   never runs e2e tests, coverage, lint/type-check gates, license headers, a TSDoc audit, or `nest build` — the
   calling `iru-nestjs-code-one-task-group` does all of that once for the whole task group. Write code that will
   pass those gates; don't run them here.

## Sources

Compiled from the following, with this catalog's own stated preferences taking precedence where they are
stricter (notably on `any`, `readonly`, and named exports). Every Nest behavior called out as "verified" was
reproduced on a fresh `@nestjs/cli` 12 scaffold (ESM/Vitest and CommonJS/Jest) on Node 24.

- irurueta docs: `backend/nestjs/getting-started-and-cli.adoc`, `typescript-for-nestjs.adoc`,
  `project-structure-and-workspaces.adoc`, `modules.adoc`, `providers-and-dependency-injection.adoc`,
  `injection-scopes.adoc`, `module-ref-circular-dependencies-and-lazy-loading.adoc`, `pipes-and-validation.adoc`,
  `request-lifecycle.adoc`, `guards.adoc`, `interceptors.adoc`, `exception-filters-and-error-handling.adoc`,
  `middleware.adoc`, `configuration.adoc`, `data-overview-and-typeorm.adoc`,
  `prisma-drizzle-mikroorm-and-sequelize.adoc`, `mongodb-with-mongoose.adoc`, `security-best-practices.adoc`,
  `security-headers-cors-and-csrf.adoc`, `rate-limiting-and-encryption.adoc`, `authentication.adoc`,
  `testing.adoc`, `fastify-and-http-platforms.adoc`, `whats-changed-and-migration.adoc`
- irurueta docs: `backend/nodejs/` (ESM/CommonJS interop, `AsyncLocalStorage`)
- This catalog's TypeScript agreements: `.claude/skills/iru-typescript-code-one-task/reference/code-style.md`
  (condensed into `code-style.md` here)
- NestJS documentation: <https://docs.nestjs.com/> — fundamentals/testing, techniques & data (`/data/*`),
  security, CLI usage, recipes/swc, migration guide
- class-validator: <https://github.com/typestack/class-validator>; Standard Schema: <https://standardschema.dev/>
- Testcontainers for Node.js: <https://node.testcontainers.org/>; supertest: <https://github.com/ladjs/supertest>
- Vitest: <https://vitest.dev/guide/>; Jest: <https://jestjs.io/docs/getting-started>
