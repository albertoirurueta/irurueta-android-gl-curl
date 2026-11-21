# Tests

Every task's implementation comes with the tests that prove it. This skill writes unit and e2e tests, runs **only
the unit spec files it touched**, and never runs e2e, coverage, or the full suite — the calling
`iru-nestjs-code-one-task-group` does that once for the whole group.

## Follow the project's runner

| | ESM project (Nest 12 default) | CommonJS project |
|---|---|---|
| Runner | Vitest (`vitest.config.ts` unit, `vitest.config.e2e.ts` e2e) | Jest 30 + `ts-jest` (`jest.config.ts`, `test/jest-e2e.json`) |
| Globals | `globals: true` → `describe`/`it`/`expect`/`vi` ambient (check the config; otherwise `import { vi } from 'vitest'`) | ambient `describe`/`it`/`expect`/`jest` |
| Doubles | `vi.fn()`, `vi.spyOn()`, `vi.clearAllMocks()` | `jest.fn()`, `jest.spyOn()`, `jest.clearAllMocks()` |
| Imports | relative with `.js` | extensionless |

Don't use one runner's API under the other. Verified when porting the same specs from the ESM to the CommonJS
scaffold: `vi.clearAllMocks()` and Vitest's `toHaveBeenCalledOnce()` both break under Jest (use
`toHaveBeenCalledTimes(1)`, which works in both). **`nest build` does not type-check spec files**
(`tsconfig.build.json` excludes `**/*spec.ts`) and neither scaffold's runner type-checks (verified: a spec with a
deliberate `TS2322` passes under Vitest and under `ts-jest`, which transpiles only because the scaffold's
`tsconfig.json` sets `isolatedModules`), so a type error in a spec only surfaces in the group's `tsc --noEmit`
gate — write specs that compile.

File placement: unit specs `*.spec.ts` **next to the source file**; e2e specs `test/<feature>.e2e-spec.ts` (or
`apps/<app>/test/` in a monorepo). The two configs' globs keep them apart — a file named wrongly is either never run
or runs in the wrong suite.

## Unit tests

- **Pure classes** (mappers, domain logic, a function taking a clock): instantiate directly, no Nest.
- **Providers and controllers**: build them through Nest so the decorators, constructor metadata, and wiring are
  exercised — and a missing provider fails `compile()` with the same message the app would give:

```ts
describe('BooksService', () => {
  let service: BooksService;
  const repository = { insert: vi.fn(), findById: vi.fn(), save: vi.fn(), delete: vi.fn(), findAll: vi.fn() };

  beforeEach(async () => {
    vi.clearAllMocks();
    const moduleRef = await Test.createTestingModule({
      providers: [BooksService, { provide: BooksRepository, useValue: repository }],
    }).compile();
    service = moduleRef.get(BooksService);
  });

  it('throws NotFoundException for a missing book', () => {
    repository.findById.mockReturnValue(undefined);
    expect(() => service.findOne(9)).toThrow(NotFoundException);
  });
});
```

- Build the module in `beforeEach` (fresh instances) and clear mocks.
- Doubles are custom providers under the token the class injects: the class itself, a `Symbol` token,
  `getRepositoryToken(Book)` (TypeORM), `getModelToken(Book.name)` / `getConnectionToken()` (Mongoose),
  `PrismaService`, `getDrizzleToken()`, `ConfigService`. `useValue` for a few `vi.fn()`s, `useClass` for a reusable
  hand-written fake, `useFactory` when the double needs other providers. Keep doubles minimal — `useMocker()` is fine
  for "every other dependency", but a factory returning `{}` hides typos until runtime.
- Request-scoped providers: `await moduleRef.resolve(X)` (`get()` throws).
- **Cover every `@throws` in the TSDoc** — each `HttpException` the method can raise gets a test for its condition.
  Plus boundaries (empty list, page 1, max page size) and the branches the task added.
- Guards/interceptors/pipes/filters are unit-tested by calling `canActivate`/`intercept`/`transform`/`catch` with
  a stub `ExecutionContext`/`CallHandler` (`{ handle: () => of(value) }`)/`ArgumentsHost`.
- A shared bootstrap helper (`configureApp(app)`) is worth a two-line unit test with a stub app asserting what it
  registers — otherwise it only shows up as 0% in the unit coverage gate (verified), since only e2e tests call it.

## Overrides

When a test imports a real module, replace only what touches the outside world:
`.overrideProvider(token).useValue(…)`, `.overrideGuard(Guard)`, `.overrideInterceptor(…)`, `.overridePipe(…)`,
`.overrideFilter(…)`, `.overrideModule(Real).useModule(Fake)`. A global enhancer registered as
`{ provide: APP_GUARD, useClass: X }` **can't** be overridden — the project must register it with `useExisting`
(`enhancers.md`). Enhancers bound with `app.useGlobal*()` in `main.ts` don't exist in tests at all.

## E2E tests

```ts
describe('Books (e2e)', () => {
  let app: INestApplication<App>;

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(MAILER).useValue({ send: vi.fn() })
      .compile();
    app = moduleRef.createNestApplication();
    configureApp(app); // the same function main.ts calls — or nothing, if globals are APP_* providers
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('rejects unknown properties with 400', () =>
    request(app.getHttpServer()).post('/books').send({ title: 'x', pages: 1, admin: true }).expect(400));
});
```

- `import request from 'supertest'` (default import — `import * as request` is a type error and a runtime
  `TypeError` under ESM). The scaffold types the app as `INestApplication<App>` with `App` from
  `'supertest/types.js'` (ESM) / `'supertest/types'` (CJS).
- **`main.ts` never runs in tests.** Global prefix, pipes, versioning, CORS, Helmet, shutdown hooks — whatever
  `main.ts` sets up — must come from a shared `configureApp(app)` or from `APP_*` providers, or the e2e suite tests a
  different application than production (verified: without it, the invalid-body test gets `201`).
- `app.init()`, never `app.listen()` — supertest binds an ephemeral port itself. Always `app.close()` in `afterAll`,
  or pools and timers keep the runner alive.
- Assert status codes and the response contract, including the failure paths (`400` invalid body, `400` bad
  param, `401`/`403`, `404`, `409`).
- **Fastify**: `createNestApplication<NestFastifyApplication>(new FastifyAdapter())`, then after `init()` await
  `app.getHttpAdapter().getInstance().ready()`, and use `app.inject({ method, url, payload })` instead of supertest.
- Data isolation: tests that write data either rebuild per test, truncate in `beforeEach`, or use unique keys —
  never depend on order.

## Tests that need a database

- Unit tests mock the persistence layer; correctness of a query is proven against the **real engine**:
  Testcontainers (`@testcontainers/postgresql`, `@testcontainers/mongodb`, …) or `mongodb-memory-server`
  (`MongoMemoryReplSet` when transactions are involved). In-memory SQLite has dialect gaps (no `jsonb`, no row
  locks, different errors) — acceptable only when the project already relies on it.
- These are e2e/integration specs (`*.e2e-spec.ts` or whatever the e2e config includes), never `*.spec.ts`: the unit
  suite and its coverage gate must run without Docker.
- Long `beforeAll` timeout (`120_000`) for image pulls; one container per file (or a `globalSetup`), not per test;
  close the Nest module **before** stopping the container. `synchronize: true` is acceptable only inside such a
  throwaway container — production schema still comes from migrations (better: run the migrations in setup).

## SWC and decorator metadata

Nest injection depends on `emitDecoratorMetadata`. The Nest 12 ESM scaffold's Vitest 4 / Vite 8 (Oxc) transform
emits it, so no plugin is needed there. Older Vitest (esbuild) does **not**: if injected dependencies are
`undefined` in tests only, the project needs `unplugin-swc` (`swc.vite({ module: { type: 'es6' } })`) — a
configuration change to raise as a blocker, not to make silently. Jest with `@swc/jest` needs
`jsc.transform.legacyDecorator` and `decoratorMetadata`. SWC never type-checks.

## What to run here

Only the unit spec files this task created or changed, through the project's runner:

- Vitest: `npx vitest run src/books/books.service.spec.ts src/books/books.controller.spec.ts`
- Jest (CommonJS scaffold): the command the `test` script uses plus the paths, e.g.
  `node --experimental-vm-modules ./node_modules/jest/bin/jest.js src/books/books.service.spec.ts`

Never the e2e config, never `--coverage`, never the whole suite.
