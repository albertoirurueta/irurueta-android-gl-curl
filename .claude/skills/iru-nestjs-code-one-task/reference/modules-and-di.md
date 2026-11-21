# Modules and dependency injection

Read this when the task adds or changes a module, provider, token, `imports`/`exports` entry, scope, or a circular
dependency.

## One feature, one module, one host per provider

- **Every provider has exactly one host module** — the one whose `providers` lists it. Other modules get it by
  importing that module, which must list it in `exports`. Listing the same class in two modules' `providers` creates
  **two instances** of a "singleton", which is a bug (two caches, two counters, two connection pools).
- **`exports` is the module's public surface.** Export the service other features call; keep its repository,
  mappers, and helpers private. A module may only export its own providers, their tokens, and modules it imports.
- **Import what you use, explicitly.** `@Global()` is for the cross-cutting few — configuration, logging, a
  database client module the project already made global — never a shortcut to avoid writing `imports`.
- **Dynamic modules (`forRoot`/`forRootAsync`/`register`) are configured once**, usually in `AppModule` (or the
  project's `CoreModule`), and re-exported or made global. A second `forRoot()` call creates a separately configured
  instance. Use `forRootAsync({ imports: [ConfigModule], inject: [ConfigService], useFactory })` whenever an option
  comes from configuration — never `process.env` read at import time.
- A new feature module is added to the `imports` of the module that owns it (normally `AppModule`; with
  `RouterModule`, also to the route tree). `nest g mo` does the first part for you (`cli-generators.md`).

## Tokens and provider forms

| Need | Form |
|---|---|
| A class with injectable dependencies | `providers: [BooksService]` |
| Depend on a contract, not a class | `export const BOOKS_REPOSITORY = Symbol('BOOKS_REPOSITORY')` + `{ provide: BOOKS_REPOSITORY, useClass: TypeOrmBooksRepository }` + `@Inject(BOOKS_REPOSITORY)` — or an `abstract class BooksRepository` as its own token |
| A ready value (client instance, constant) | `{ provide: TOKEN, useValue: value }` |
| Built from other providers / async setup | `{ provide: TOKEN, useFactory: (cfg: ConfigService) => …, inject: [ConfigService] }` (may be `async`) |
| An alias (also: making a global enhancer overridable) | `{ provide: TOKEN, useExisting: OtherClass }` |

Prefer a `Symbol` over a string token (strings collide silently). Keep tokens next to the contract they name and
export both.

## Scopes: singleton unless proven otherwise

- **Default scope is singleton and it is the right one** for nearly every provider: one instance, created at
  bootstrap, shared by every concurrent request.
- **Never keep per-request state in a singleton's fields** (current user, tenant, request id, a "last result"
  cache keyed by nothing) — concurrent requests overwrite each other. Pass the value as a method argument, or read it
  from `AsyncLocalStorage` (`node:async_hooks`) / `nestjs-cls` if the project already uses one.
- **`Scope.REQUEST` bubbles**: every provider that injects a request-scoped one — and the controller above it —
  becomes request-scoped too, re-created per request. Use it only when the task genuinely needs it and the project
  already accepts the cost; resolve scoped instances with `moduleRef.resolve()`, never `get()`.
- Don't make a database client, logger, or config service request-scoped: it drags the whole graph with it.

## Lifecycle hooks

Acquire resources in `onModuleInit()` (or an `async` factory), release them in `onModuleDestroy()` /
`beforeApplicationShutdown()`. Signals only reach these hooks when `main.ts` (or the shared `configureApp`) calls
`app.enableShutdownHooks()`. In Nest 12 lifecycle hooks of related providers run in hierarchy order, and
`@Optional()` markers are not inherited by a subclass that redeclares its constructor — redeclare them.

## Circular dependencies

First try to break the cycle — move the shared logic into a third provider or module, or replace the call with an
event. If it must stay: `forwardRef(() => OtherModule)` in **both** modules' `imports`, and for providers
`@Inject(forwardRef(() => BService)) private readonly b: Relation<BService>` on both sides, where
`export type Relation<T> = T;` wraps the type. Under ESM the wrapper is required: without it the emitted metadata
evaluates the class before initialization and the app dies with `ReferenceError: Cannot access 'BService' before
initialization` — `forwardRef` alone does not help.

## Diagnosing `Nest can't resolve dependencies of X (?)`

The index names the constructor parameter; the module named is where Nest looked. In order of likelihood: the
provider isn't in that module's `providers`; the host module doesn't `export` it; the host module isn't imported;
the parameter is an interface or a type-only import ("undefined at runtime" in the message — inject a class or use
`@Inject(token)`); a provider class placed in `imports`; two copies of `@nestjs/core` in a monorepo (missing token
shows as `ModuleRef`). A unit test's `compile()` fails with the same message, which is useful — don't paper over it
with `useMocker` returning `{}`.

## Monorepo mode

`nest-cli.json` with `"monorepo": true` and a `projects` map means apps live under `apps/<name>/` and shared code
under `libs/<name>/`, imported through `@app/<name>` path aliases (no `.js` suffix). Put code in the app or lib the
task names; something two apps need goes in a lib, not copy-pasted. New libs come from `nest g library <name>`.
