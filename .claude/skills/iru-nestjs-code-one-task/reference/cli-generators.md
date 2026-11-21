# CLI generators

Read this when the task creates a new module, controller, service, resource, guard, interceptor, pipe, filter,
middleware, decorator, or library. Generating with the project's own CLI keeps file names, folders, spec files,
and module registration consistent with the rest of the codebase; hand-writing them is fine too, as long as the
result looks the same.

## Match the project's conventions first

Before generating, look at how existing features are laid out and read `nest-cli.json`:

- `generateOptions` (`"spec": false`, `"flat": true`, `"specFileSuffix"`) is applied automatically — don't fight it
  with flags.
- Folder per feature (`src/books/books.service.ts`, the default) vs. flat files (`--flat`) vs. a deeper layout
  (`src/modules/books/…`, `src/books/application/…`): pass the path as part of the name
  (`nest g s modules/books/books`) and `--flat` when the project puts several elements in one folder.
- `"monorepo": true` → always pass `--project <app-or-lib>` (`-p`), or the element lands in the default project.
- Always preview first with `-d`/`--dry-run`: it prints every `CREATE`/`UPDATE`, including **which module file gets
  updated** — the generator registers the element in the *closest* module walking up from the target folder, which
  is not always the one the task means. Use `--skip-import` and register it yourself when it would pick the wrong one.

Run it through the project's local CLI (`npx nest g …`), never a globally installed or `@latest` one — a different
schematics major generates different code (Nest 12 emits `.js` relative imports in ESM projects and
`Reflector.createDecorator()` decorators).

## Schematics

| Command | Creates | Registered? |
|---|---|---|
| `nest g mo books` | `books/books.module.ts` | Imported into the closest module |
| `nest g co books` / `nest g s books` | controller / service + spec | Added to the closest module's `controllers` / `providers` |
| `nest g pr clock` / `nest g cl money` / `nest g itf shape` | plain provider / class / interface | provider: yes; others: no |
| `nest g gu auth/jwt-auth --flat` | `jwt-auth.guard.ts` + spec | **No** |
| `nest g itc common/timing --flat` / `nest g pi …` / `nest g f …` / `nest g mi …` | interceptor / pipe / filter / middleware + spec | **No** |
| `nest g d roles` | `Reflector.createDecorator<string[]>()` decorator | n/a |
| `nest g res books --type rest --crud` | module, controller, service, `dto/create-*.dto.ts`, `dto/update-*.dto.ts`, `entities/*.entity.ts`, specs; adds `@nestjs/mapped-types` | Module imported into `AppModule` |
| `nest g lib common` / `nest g app worker` | library / application — **converts a standard project to monorepo mode** | — |

`nest g res` without `--type`/`--crud` prompts — always pass both. `--crud false` gives only the module, controller,
service, and specs. `nest g lib`/`nest g app` restructure the whole repository; never run them unless the task
explicitly asks for a monorepo conversion.

## After generating: the output is a skeleton, not an implementation

Treat everything the generator wrote as placeholder text to be replaced in this same task:

- **`nest g res`** — the service returns strings like `'This action adds a new book'`; the DTO and entity are
  empty classes; the controller coerces ids with `+id` (`NaN` for `abc`). Replace with the real repository/ORM
  calls, fields + validators (`validation-and-dtos.md`), `ParseIntPipe`/`ParseUUIDPipe`, typed return values,
  `@HttpCode(204)` on delete if that's the contract. Delete generated pieces the task doesn't need (e.g. the
  `entities/` folder when the project keeps entities elsewhere, `update` routes it didn't ask for) rather than
  leaving dead endpoints.
- **Guards** are generated returning `true` (allow everything); **interceptors** with `Observable<any>` and a bare
  `next.handle()`; pipes return the value unchanged; filters are empty. Write the real logic, replace `any` with
  real types (`Observable<unknown>`), and **register** each one where the task says — `@UseGuards(...)` on the
  controller/route, or once as an `APP_*` provider with `useExisting` (`enhancers.md`); middleware in the module's
  `configure()`.
- **Generated specs** contain only `it('should be defined')`. Replace them with tests of the actual behavior
  (`testing.md`), with the doubles the class now needs — the placeholder spec of a service that gained a repository
  dependency fails `compile()` as soon as you add the constructor parameter.
- Generated code may not match the project's Prettier/lint config (long import lines, `any`); the files you touched
  get formatted as part of Step 3.
- The `UPDATE src/app.module.ts` line: confirm the import landed in the module the task intended and that the
  feature module `exports` what other modules will consume.
- `nest g res` installs `@nestjs/mapped-types` if absent — if the project uses `@nestjs/swagger`'s mapped types
  instead, switch the import and drop the new dependency.
