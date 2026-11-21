# Code style

This catalog's general TypeScript agreements, condensed for NestJS, plus the rules that only exist because Nest
reads decorator metadata at runtime. If `.claude/skills/iru-typescript-code-one-task/reference/code-style.md` is
installed in this repository, it is the long form of the first half of this file (with examples); this file is
self-contained so the skill still works when it isn't.

Above all: **match the surrounding code.** These are the defaults for new code.

## The catalog's TypeScript agreements (condensed)

- **No `any`.** `unknown` plus a narrowing guard, a generic, or a (discriminated) union. A scaffolded
  `.oxlintrc.json` turns `typescript/no-explicit-any` **off** — that's the scaffold being permissive, not a
  license; write to the stricter bar. A pre-existing `any` outside the task's scope is not this task's problem.
- **No `!` non-null assertions or `as` casts to paper over a mismatch.** Narrow instead. `as` is fine after a guard
  has validated a wider type, and in tests for a deliberately partial double (`stub as unknown as
  INestApplication`). The **definite-assignment** `title!: string` on a DTO/entity field is a different thing and
  is allowed — see below.
- **`readonly` by default**: interface/type properties, `readonly T[]`/`ReadonlyArray<T>` return types the caller
  must not mutate, `private readonly` constructor-injected dependencies, `const` over `let`.
- **Prefer string-literal unions over `enum`** unless the surrounding code (or an ORM column/Swagger enum) already
  uses `enum`.
- **Named exports only.** No `export default` in new files. The exceptions Nest tooling imposes — a TypeORM CLI
  `data-source.ts`, a `registerAs()` config file the project already default-exports, `vitest.config.ts` — are
  documented exceptions; say so in the report when you use one.
- **Errors**: throw `Error` subclasses, never strings; keep `{ cause }` when rethrowing; never swallow (`catch {}`
  or log-and-continue). In a Nest request path, throw the `HttpException` subclass that names the outcome
  (`NotFoundException`, `ConflictException`, `ForbiddenException`, …) from the service, or a domain error that an
  existing exception filter maps — a plain `Error` becomes a `500`.
- **Async**: `async`/`await` over `.then()` chains; no floating promises (the Nest 12 scaffold's Oxlint config sets
  `typescript/no-floating-promises` to **error**); `void promise` with a comment only for genuine
  fire-and-forget; `Promise.all` for independent work.
- **Naming**: `UpperCamelCase` types/classes (no `I` prefix on interfaces), `lowerCamelCase` members, boolean
  predicates (`isActive`), `SCREAMING_SNAKE_CASE` only for true global constants **and injection tokens**
  (`export const BOOKS_REPOSITORY = Symbol('BOOKS_REPOSITORY')`).
- **No `console.log`.** Use Nest's `Logger` (`private readonly logger = new Logger(BooksService.name)`) or the
  project's logger (`nestjs-pino`, …) if it has one. No new runtime dependency unless the task asks for it.
- Template literals, named constants for meaningful values, short single-purpose methods, no dead or
  commented-out code.

## Nest specifics

### Module format: read it, then follow it

| | ESM (Nest 12 default) | CommonJS (pre-v12, or chosen at `nest new`) |
|---|---|---|
| Signal | `"type": "module"` in `package.json` | no `"type"` field |
| Relative imports | **must end in `.js`**: `import { BooksService } from './books.service.js'` | extensionless: `'./books.service'` |
| `main.ts` | top-level `await bootstrap();` | `void bootstrap();` |

Both compile with `"module": "nodenext"`. In an ESM project, an extensionless relative import is a compile error
(`TS2307`) — the `.js` is correct even though the source file is `.ts`; **never "fix" it**. Copying a sample from
the docs (which use ESM spellings) into a CommonJS project means dropping the `.js`. `__dirname`/`require` don't
exist in ESM: use `import.meta.dirname` / `createRequire(import.meta.url)`. Path aliases (`@app/common` in a
monorepo) carry no extension.

### Decorator metadata is load-bearing

Nest resolves constructor dependencies from the `design:paramtypes` metadata TypeScript emits because
`experimentalDecorators` and `emitDecoratorMetadata` are on. Consequences:

- **Inject classes (or abstract classes) by type; anything else needs `@Inject(token)`.** An interface or type
  alias is erased: inject it through a `Symbol` token (`@Inject(BOOKS_REPOSITORY) private readonly repo:
  BooksRepository`) or make the contract an abstract class, which is its own runtime token.
- **Optional dependencies use `?`, not `| undefined`** — a union emits `Object` and the dependency is silently
  `undefined`: `@Optional() private readonly cache?: CacheService`.
- **Never disable `emitDecoratorMetadata`**, and if a build or test path uses SWC/esbuild, it must emit decorator
  metadata (see `testing.md`). A constructor dependency that is `undefined` at runtime means a transformer
  dropped the metadata.

### DTOs are classes, imported as values

- A request DTO **must be a class** — `ValidationPipe` reads the parameter's metatype; an interface or type alias
  becomes `Object` and nothing is validated.
- **Never `import type` a DTO class** (or any class used as an injection token). Verified on a Nest 12 ESM
  scaffold: changing `import { CreateBookDto }` to `import type { CreateBookDto }` in a controller **still compiles,
  still lints clean, and the unit tests still pass** — but the e2e test posting an invalid body got `201` instead of
  `400`, because validation silently stopped. Only an e2e test catches it.
- The reverse: a *type-only* symbol referenced in a decorated signature (`ConfigType`, an interface) must use
  `import type` / inline `type`, or the build fails with `TS1272` under `isolatedModules`. Mixed form:
  `import { CreateCatDto, type CreateCatShape } from './dto/create-cat.dto.js';`
- DTO/entity fields: the scaffold sets `strictPropertyInitialization: false`, so plain `title: string` compiles;
  where the project has it on, use `title!: string`. Mark client-optional fields `title?: string` **and**
  `@IsOptional()` so type and rule agree.

### Layering inside a feature

`src/<feature>/` holds `<feature>.module.ts`, `<feature>.controller.ts`, `<feature>.service.ts`, an optional
`<feature>.repository.ts`, `<feature>.entity.ts` (or `entities/`), and `dto/create-<feature>.dto.ts`; unit specs sit
next to the file as `*.spec.ts`. Dependencies point one way:

- **Controller**: HTTP only — route, read `@Param`/`@Query`/`@Body`, pick the status code (`@HttpCode(204)`), call
  one service method. No business rules, no ORM calls.
- **Service**: application logic; never sees `req`/`res`. Takes primitives/DTOs, returns domain data or throws.
- **Repository** (or the ORM repository/model injected into the service, if that's the project's style): storage
  only.

Map DTOs into entities **field by field** rather than spreading them. With `transform: true` the DTO is a class
instance, so `{ ...current, ...dto }` trips Oxlint's `typescript(no-misused-spread)` (verified) and copies
whatever properties reached the object — explicit fields are also your mass-assignment defence.

### TSDoc

Every exported class and public method gets TSDoc with `@param`/`@returns`/`@throws` (the `@throws` lines name the
`HttpException`s the method can raise — the tests in `testing.md` are written against them). The group skill's
`iru-typescript-tsdoc` gate audits controllers, route handlers, services, DTOs (and their fields), modules, guards,
and pipes.
