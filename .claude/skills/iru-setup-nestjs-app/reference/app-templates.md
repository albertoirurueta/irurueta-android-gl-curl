# App templates (no ORM, ESM, Express)

The base application `iru-setup-nestjs-app` writes on top of the Nest CLI scaffold, verified end-to-end on a
`@nestjs/cli` 12.0.8 scaffold (Nest 12.1.3, Node 24.20, npm 11.19): `lint`, `format:check`, `typecheck`, `test`,
`coverage` (100 % lines), `test:e2e`, `build`, `docs` (Compodoc 2.0.0), `docker build` + a container reporting
`healthy`. Every block below is the verified file **after** `npx prettier --write .` with the scaffold's own
`.prettierrc` (`singleQuote`, `trailingComma: all`, default 80-column width) — copy it verbatim, replacing only the
sample-feature names if the user asked for a different first feature.

The sample feature (`books`) follows `iru-nestjs-code-one-task`'s references on purpose: feature folder,
controller → service → repository, a class DTO imported as a value, `ParseIntPipe`, a capped page query, TSDoc with
`@throws`, `NotFoundException` from the service, unit specs next to the source through `Test.createTestingModule`,
e2e specs in `test/` sharing `configureApp()` with `main.ts`, and `APP_PIPE` for the global `ValidationPipe`.

An ORM file (`orm-*.md`) replaces `books.repository.ts` (+ spec), the readiness check in `health.controller.ts`
(+ spec), `env.schema.ts` (+ spec, adds `DATABASE_URL`), adds a `DatabaseModule` to `app.module.ts`, and adds an
e2e global setup. Everything else here stays as is.

## CommonJS conversion rules

The templates are written for ESM (`"type": "module"`, Vitest). For `module-system: cjs` (Jest 30 + ts-jest),
apply exactly these mechanical rules — this is how the CommonJS variants were produced and verified:

1. Drop the `.js` suffix from every **relative** import (`'./books.service.js'` → `'./books.service'`); package
   imports are untouched. `'supertest/types.js'` → `'supertest/types'`.
2. `vi.fn` / `vi.spyOn` / `vi.clearAllMocks` → `jest.fn` / `jest.spyOn` / `jest.clearAllMocks` (Prettier may
   have split `vi` and `.fn()` across lines — replace the identifier, not just `vi.fn`).
3. `src/main.ts` ends with `void bootstrap();` instead of `await bootstrap();`.
4. Keep `toHaveBeenCalledTimes(1)` (works in both runners); never use Vitest-only matchers.
5. Use `jest.config.ts` / `test/jest-e2e.json` below instead of the two Vitest configs.

## Configuration files (ESM / Vitest)

### `vitest.config.ts`

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Resolves the path aliases declared in tsconfig.json, including the ones
  // added by `nest g library`. Requires Vite 8, which ships this option.
  resolve: { tsconfigPaths: true },
  test: {
    globals: true,
    root: './',
    include: ['src/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      // Wiring-only and generated files: no unit test imports them, so they would only drag the number down.
      exclude: [
        'src/**/*.spec.ts',
        'src/**/*.module.ts',
        'src/main.ts',
        'src/generated/**',
        'src/database/migrations/**',
        'src/database/data-source.ts',
      ],
      reporter: ['text', 'lcov', 'json-summary'],
      reportsDirectory: 'coverage',
      // No branch threshold: the decorator metadata TypeScript emits for every injected constructor parameter
      // compiles to a `typeof X === "undefined" ? Object : X` branch that no test can take.
      thresholds: { lines: 80, statements: 80, functions: 80 },
    },
  },
});
```

### `vitest.config.e2e.ts`

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: { tsconfigPaths: true },
  test: {
    globals: true,
    root: './',
    include: ['test/**/*.e2e-spec.ts'],
    // Container start-up (first image pull) and app bootstrap are slower than unit tests.
    hookTimeout: 120_000,
    testTimeout: 30_000,
  },
});
```

### `.oxlintrc.json`

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "rules": {
    "typescript/no-explicit-any": "error",
    "typescript/no-floating-promises": "error"
  },
  "env": {
    "node": true
  },
  "ignorePatterns": ["src/generated/**"]
}
```

### `.prettierignore`

```text
dist
coverage
docs
.github
.claude
src/generated
CHANGELOG.md
README.md
package-lock.json
pnpm-lock.yaml
```

## Configuration files (CommonJS / Jest)

The scaffold's `jest.config.ts` gains `roots`, the `.js` → source `moduleNameMapper` (needed as soon as generated
code such as the Prisma client imports `./x.js`; harmless otherwise), the coverage exclusions, reporters, and the
80 % threshold. The CommonJS `package.json` scripts call Jest the way the scaffold does
(`node --experimental-vm-modules ./node_modules/jest/bin/jest.js`, needed to load the ESM-only Nest 12 packages;
Node 24.9+).

### `jest.config.ts`

```ts
import type { Config } from 'jest';
import { pathsToModuleNameMapper } from 'ts-jest';
import ts from 'typescript';

// Path aliases (e.g. the ones added by `nest g library`) live in tsconfig.json,
// so they are read from there instead of being duplicated here.
const { config: tsconfig } = ts.readConfigFile(
  './tsconfig.json',
  ts.sys.readFile,
);
const paths = tsconfig?.compilerOptions?.paths ?? {};

const config: Config = {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  roots: ['<rootDir>/src'],
  testRegex: '.*\\.spec\\.ts$',
  transform: {
    '^.+\\.(t|j)s$': 'ts-jest',
  },
  moduleNameMapper: {
    // Generated code (the Prisma client) imports siblings as `./x.js`; resolve those to the `.ts` sources.
    '^(\\.{1,2}/.*)\\.js$': '$1',
    ...pathsToModuleNameMapper(paths, { prefix: '<rootDir>/' }),
  },
  // Wiring-only and generated files: no unit test imports them, so they would only drag the number down.
  collectCoverageFrom: [
    'src/**/*.ts',
    '!src/**/*.spec.ts',
    '!src/**/*.module.ts',
    '!src/main.ts',
    '!src/generated/**',
    '!src/database/migrations/**',
    '!src/database/data-source.ts',
  ],
  coverageDirectory: './coverage',
  coverageReporters: ['text', 'lcov', 'json-summary'],
  // No branch threshold: the decorator metadata TypeScript emits for injected constructor parameters adds
  // branches no test can take.
  coverageThreshold: { global: { lines: 80, statements: 80, functions: 80 } },
  testEnvironment: 'node',
};

export default config;
```

### `test/jest-e2e.json`

Without a database (an ORM file adds `globalSetup`/`globalTeardown`):

```json
{
  "moduleFileExtensions": [
    "js",
    "json",
    "ts"
  ],
  "rootDir": ".",
  "testEnvironment": "node",
  "testRegex": ".e2e-spec.ts$",
  "transform": {
    "^.+\\.(t|j)s$": "ts-jest"
  },
  "testTimeout": 30000,
  "moduleNameMapper": {
    "^(\\.{1,2}/.*)\\.js$": "$1"
  }
}
```

## Source files (ESM, no ORM, Express)

Verified exactly as shown (after `prettier --write .`). For CommonJS apply the conversion rules above.

### `src/config/env.schema.ts`

```ts
import { z } from 'zod';

/**
 * Environment variables the application reads, validated once at bootstrap by `ConfigModule`.
 *
 * Every value arrives as a string, so numbers are coerced. A variable without a default is required: the
 * application refuses to start with a `Config validation error` naming it instead of failing on first use.
 */
export const envSchema = z.object({
  NODE_ENV: z
    .enum(['development', 'production', 'test'])
    .default('development'),
  PORT: z.coerce.number().int().min(0).max(65_535).default(3000),
});

/** The validated, typed environment produced by {@link envSchema}. */
export type Env = z.output<typeof envSchema>;
```

### `src/config/env.schema.spec.ts`

```ts
import { envSchema } from './env.schema.js';

describe('envSchema', () => {
  it('applies the defaults when nothing is set', () => {
    expect(envSchema.parse({})).toEqual({
      NODE_ENV: 'development',
      PORT: 3000,
    });
  });

  it('coerces PORT from its string form', () => {
    expect(envSchema.parse({ PORT: '8080' }).PORT).toBe(8080);
  });

  it('rejects a PORT that is not a number', () => {
    expect(envSchema.safeParse({ PORT: 'abc' }).success).toBe(false);
  });
});
```

### `src/app.module.ts`

```ts
import { Module, ValidationPipe } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { APP_PIPE } from '@nestjs/core';
import { BooksModule } from './books/books.module.js';
import { envSchema } from './config/env.schema.js';
import { HealthModule } from './health/health.module.js';

/**
 * Root module: validated configuration, the global validation pipe, health checks, and the feature modules.
 *
 * The `ValidationPipe` is registered here as an `APP_PIPE` provider rather than with `app.useGlobalPipes()` in
 * `main.ts`, so every test that imports `AppModule` validates requests exactly like production does.
 */
@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, validationSchema: envSchema }),
    HealthModule,
    BooksModule,
  ],
  providers: [
    {
      provide: APP_PIPE,
      useFactory: (): ValidationPipe =>
        new ValidationPipe({
          whitelist: true,
          forbidNonWhitelisted: true,
          transform: true,
        }),
    },
  ],
})
export class AppModule {}
```

### `src/app.setup.ts`

```ts
import type { INestApplication } from '@nestjs/common';

/**
 * Applies the HTTP-level setup that cannot live in the module graph: security headers and shutdown hooks.
 *
 * `main.ts` and every e2e test call this same function, so the tests exercise the application production runs
 * (`main.ts` itself never runs under a test runner).
 *
 * @param app - The application created by `NestFactory.create()` or `createNestApplication()`.
 * @returns The same application, for chaining.
 */
export function configureApp<T extends INestApplication>(app: T): T {
  app.useSecurityHeaders();
  app.enableShutdownHooks();
  return app;
}
```

### `src/app.setup.spec.ts`

```ts
import type { INestApplication } from '@nestjs/common';
import { configureApp } from './app.setup.js';

describe('configureApp', () => {
  it('enables security headers and shutdown hooks', () => {
    const app = { useSecurityHeaders: vi.fn(), enableShutdownHooks: vi.fn() };

    const result = configureApp(app as unknown as INestApplication);

    expect(result).toBe(app);
    expect(app.useSecurityHeaders).toHaveBeenCalledTimes(1);
    expect(app.enableShutdownHooks).toHaveBeenCalledTimes(1);
  });
});
```

### `src/main.ts`

```ts
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';
import { configureApp } from './app.setup.js';

/** Creates the application, applies the shared setup, and listens on the validated `PORT`. */
async function bootstrap(): Promise<void> {
  const app = configureApp(await NestFactory.create(AppModule));
  const config = app.get(ConfigService);
  await app.listen(config.getOrThrow<number>('PORT'), '0.0.0.0');
}
await bootstrap();
```

### `src/health/health.module.ts`

```ts
import { Module } from '@nestjs/common';
import { TerminusModule } from '@nestjs/terminus';
import { HealthController } from './health.controller.js';

/** Health checks (`@nestjs/terminus`): the `/health/live` and `/health/ready` endpoints. */
@Module({
  imports: [TerminusModule.forRoot()],
  controllers: [HealthController],
})
export class HealthModule {}
```

### `src/health/health.controller.ts`

```ts
import { Controller, Get } from '@nestjs/common';
import {
  HealthCheck,
  HealthCheckService,
  type HealthCheckResult,
} from '@nestjs/terminus';

/**
 * Liveness and readiness probes under `/health`, for the container `HEALTHCHECK`, Kubernetes probes, and load
 * balancers. Liveness never checks dependencies (a database outage must not restart every replica); readiness
 * checks what the instance needs to serve traffic.
 */
@Controller('health')
export class HealthController {
  constructor(private readonly health: HealthCheckService) {}

  /**
   * `GET /health/live` - the process is up and its event loop answers.
   *
   * @returns The Terminus result (`200` with `status: "ok"`).
   */
  @Get('live')
  @HealthCheck()
  live(): Promise<HealthCheckResult> {
    return this.health.check([]);
  }

  /**
   * `GET /health/ready` - the instance may receive traffic.
   *
   * @returns The Terminus result; `503` when a dependency check fails.
   */
  @Get('ready')
  @HealthCheck()
  ready(): Promise<HealthCheckResult> {
    return this.health.check([]);
  }
}
```

### `src/health/health.controller.spec.ts`

```ts
import { HealthCheckService, type HealthCheckResult } from '@nestjs/terminus';
import { Test } from '@nestjs/testing';
import { HealthController } from './health.controller.js';

describe('HealthController', () => {
  const ok: HealthCheckResult = {
    status: 'ok',
    info: {},
    error: {},
    details: {},
  };
  const health = { check: vi.fn() };
  let controller: HealthController;

  beforeEach(async () => {
    vi.clearAllMocks();
    health.check.mockResolvedValue(ok);
    const moduleRef = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [{ provide: HealthCheckService, useValue: health }],
    }).compile();
    controller = moduleRef.get(HealthController);
  });

  it('reports liveness without checking dependencies', async () => {
    await expect(controller.live()).resolves.toEqual(ok);
    expect(health.check).toHaveBeenCalledWith([]);
  });

  it('reports readiness', async () => {
    await expect(controller.ready()).resolves.toEqual(ok);
    expect(health.check).toHaveBeenCalledTimes(1);
  });
});
```

### `src/books/book.entity.ts`

```ts
/** A book in the catalog, as stored and as returned by the API. */
export interface Book {
  /** Server-assigned identifier. */
  readonly id: number;
  /** Title of the book. */
  readonly title: string;
  /** Author of the book. */
  readonly author: string;
}

/** The fields a client supplies to create a {@link Book}. */
export type NewBook = Omit<Book, 'id'>;
```

### `src/books/dto/create-book.dto.ts`

```ts
import { IsNotEmpty, IsString, MaxLength } from 'class-validator';

/** Request body of `POST /books`. Undeclared properties are rejected by the global `ValidationPipe`. */
export class CreateBookDto {
  /** Title of the book, 1-200 characters. */
  @IsString()
  @IsNotEmpty()
  @MaxLength(200)
  title: string;

  /** Author of the book, 1-200 characters. */
  @IsString()
  @IsNotEmpty()
  @MaxLength(200)
  author: string;
}
```

### `src/books/dto/list-books.query.ts`

```ts
import { Type } from 'class-transformer';
import { IsInt, IsOptional, Max, Min } from 'class-validator';

/** Query string of `GET /books`: an offset page, capped at 100 items. */
export class ListBooksQuery {
  /** Maximum number of books to return (1-100, default 20). */
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  limit: number = 20;

  /** Number of books to skip (default 0). */
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  offset: number = 0;
}
```

### `src/books/dto/list-books.query.spec.ts`

```ts
import 'reflect-metadata';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { ListBooksQuery } from './list-books.query.js';

describe('ListBooksQuery', () => {
  it('defaults to the first page of 20', async () => {
    const query = plainToInstance(ListBooksQuery, {});

    expect(query).toMatchObject({ limit: 20, offset: 0 });
    await expect(validate(query)).resolves.toHaveLength(0);
  });

  it('converts query-string numbers', async () => {
    const query = plainToInstance(ListBooksQuery, { limit: '5', offset: '10' });

    expect(query).toMatchObject({ limit: 5, offset: 10 });
    await expect(validate(query)).resolves.toHaveLength(0);
  });

  it('rejects a page size above 100', async () => {
    const errors = await validate(
      plainToInstance(ListBooksQuery, { limit: '1000' }),
    );

    expect(errors.map((error) => error.property)).toEqual(['limit']);
  });
});
```

### `src/books/books.repository.ts`

```ts
import { Injectable } from '@nestjs/common';
import type { Book, NewBook } from './book.entity.js';

/**
 * Storage for books. This in-memory implementation is the placeholder persistence of the scaffold: replace its
 * body with database calls when the application gets one, keeping the method contracts.
 */
@Injectable()
export class BooksRepository {
  private readonly books = new Map<number, Book>();
  private nextId = 1;

  /**
   * Lists books ordered by id.
   *
   * @param limit - Maximum number of books to return.
   * @param offset - Number of books to skip.
   * @returns The requested page, possibly empty.
   */
  findAll(limit: number, offset: number): Promise<readonly Book[]> {
    const ordered = [...this.books.values()].sort((a, b) => a.id - b.id);
    return Promise.resolve(ordered.slice(offset, offset + limit));
  }

  /**
   * Finds one book.
   *
   * @param id - Identifier of the book.
   * @returns The book, or `undefined` when no book has that id.
   */
  findById(id: number): Promise<Book | undefined> {
    return Promise.resolve(this.books.get(id));
  }

  /**
   * Stores a new book.
   *
   * @param data - The book's fields.
   * @returns The stored book with its assigned id.
   */
  create(data: NewBook): Promise<Book> {
    const book: Book = {
      id: this.nextId++,
      title: data.title,
      author: data.author,
    };
    this.books.set(book.id, book);
    return Promise.resolve(book);
  }

  /**
   * Deletes one book.
   *
   * @param id - Identifier of the book.
   * @returns `true` when a book was deleted, `false` when none had that id.
   */
  delete(id: number): Promise<boolean> {
    return Promise.resolve(this.books.delete(id));
  }
}
```

### `src/books/books.repository.spec.ts`

```ts
import { BooksRepository } from './books.repository.js';

describe('BooksRepository (in-memory)', () => {
  let repository: BooksRepository;

  beforeEach(() => {
    repository = new BooksRepository();
  });

  it('assigns increasing ids and finds stored books', async () => {
    const first = await repository.create({
      title: 'Dune',
      author: 'Frank Herbert',
    });
    const second = await repository.create({
      title: 'Emma',
      author: 'Jane Austen',
    });

    expect(second.id).toBe(first.id + 1);
    await expect(repository.findById(first.id)).resolves.toEqual(first);
  });

  it('returns undefined for an unknown id', async () => {
    await expect(repository.findById(42)).resolves.toBeUndefined();
  });

  it('pages through books ordered by id', async () => {
    for (const title of ['A', 'B', 'C']) {
      await repository.create({ title, author: 'Anon' });
    }

    const page = await repository.findAll(2, 1);

    expect(page.map((book) => book.title)).toEqual(['B', 'C']);
  });

  it('deletes a book once', async () => {
    const book = await repository.create({
      title: 'Dune',
      author: 'Frank Herbert',
    });

    await expect(repository.delete(book.id)).resolves.toBe(true);
    await expect(repository.delete(book.id)).resolves.toBe(false);
  });
});
```

### `src/books/books.service.ts`

```ts
import { Injectable, NotFoundException } from '@nestjs/common';
import type { Book } from './book.entity.js';
import { BooksRepository } from './books.repository.js';
import { CreateBookDto } from './dto/create-book.dto.js';

/** Application logic for the book catalog. Knows nothing about HTTP requests. */
@Injectable()
export class BooksService {
  constructor(private readonly books: BooksRepository) {}

  /**
   * Lists one page of books.
   *
   * @param limit - Maximum number of books to return.
   * @param offset - Number of books to skip.
   * @returns The requested page, possibly empty.
   */
  findAll(limit: number, offset: number): Promise<readonly Book[]> {
    return this.books.findAll(limit, offset);
  }

  /**
   * Returns one book.
   *
   * @param id - Identifier of the book.
   * @returns The book.
   * @throws NotFoundException when no book has that id.
   */
  async findOne(id: number): Promise<Book> {
    const book = await this.books.findById(id);
    if (!book) {
      throw new NotFoundException(`Book ${id} not found`);
    }
    return book;
  }

  /**
   * Creates a book from a validated request body.
   *
   * @param dto - The validated request body.
   * @returns The created book.
   */
  create(dto: CreateBookDto): Promise<Book> {
    return this.books.create({ title: dto.title, author: dto.author });
  }

  /**
   * Deletes a book.
   *
   * @param id - Identifier of the book.
   * @throws NotFoundException when no book has that id.
   */
  async remove(id: number): Promise<void> {
    if (!(await this.books.delete(id))) {
      throw new NotFoundException(`Book ${id} not found`);
    }
  }
}
```

### `src/books/books.service.spec.ts`

```ts
import { NotFoundException } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import type { Book } from './book.entity.js';
import { BooksRepository } from './books.repository.js';
import { BooksService } from './books.service.js';

describe('BooksService', () => {
  const dune: Book = { id: 1, title: 'Dune', author: 'Frank Herbert' };
  const repository = {
    findAll: vi.fn(),
    findById: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
  };
  let service: BooksService;

  beforeEach(async () => {
    vi.clearAllMocks();
    const moduleRef = await Test.createTestingModule({
      providers: [
        BooksService,
        { provide: BooksRepository, useValue: repository },
      ],
    }).compile();
    service = moduleRef.get(BooksService);
  });

  it('lists one page of books', async () => {
    repository.findAll.mockResolvedValue([dune]);

    await expect(service.findAll(20, 0)).resolves.toEqual([dune]);
    expect(repository.findAll).toHaveBeenCalledWith(20, 0);
  });

  it('returns an existing book', async () => {
    repository.findById.mockResolvedValue(dune);

    await expect(service.findOne(1)).resolves.toEqual(dune);
  });

  it('throws NotFoundException for a missing book', async () => {
    repository.findById.mockResolvedValue(undefined);

    await expect(service.findOne(9)).rejects.toThrow(NotFoundException);
  });

  it('creates a book from the DTO fields only', async () => {
    repository.create.mockResolvedValue(dune);

    await expect(
      service.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).resolves.toEqual(dune);
    expect(repository.create).toHaveBeenCalledWith({
      title: 'Dune',
      author: 'Frank Herbert',
    });
  });

  it('deletes an existing book', async () => {
    repository.delete.mockResolvedValue(true);

    await expect(service.remove(1)).resolves.toBeUndefined();
  });

  it('throws NotFoundException when deleting a missing book', async () => {
    repository.delete.mockResolvedValue(false);

    await expect(service.remove(9)).rejects.toThrow(NotFoundException);
  });
});
```

### `src/books/books.controller.ts`

```ts
import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  Param,
  ParseIntPipe,
  Post,
  Query,
} from '@nestjs/common';
import type { Book } from './book.entity.js';
import { BooksService } from './books.service.js';
import { CreateBookDto } from './dto/create-book.dto.js';
import { ListBooksQuery } from './dto/list-books.query.js';

/** HTTP endpoints of the book catalog under `/books`. Routing and status codes only; logic lives in the service. */
@Controller('books')
export class BooksController {
  constructor(private readonly booksService: BooksService) {}

  /**
   * `GET /books` - lists one page of books.
   *
   * @param query - The validated `limit`/`offset` query string.
   * @returns The requested page, possibly empty.
   */
  @Get()
  findAll(@Query() query: ListBooksQuery): Promise<readonly Book[]> {
    return this.booksService.findAll(query.limit, query.offset);
  }

  /**
   * `GET /books/:id` - returns one book.
   *
   * @param id - Identifier of the book; a non-integer is rejected with `400`.
   * @returns The book.
   * @throws NotFoundException when no book has that id.
   */
  @Get(':id')
  findOne(@Param('id', ParseIntPipe) id: number): Promise<Book> {
    return this.booksService.findOne(id);
  }

  /**
   * `POST /books` - creates a book.
   *
   * @param dto - The validated request body.
   * @returns The created book (`201`).
   */
  @Post()
  create(@Body() dto: CreateBookDto): Promise<Book> {
    return this.booksService.create(dto);
  }

  /**
   * `DELETE /books/:id` - deletes a book (`204`).
   *
   * @param id - Identifier of the book; a non-integer is rejected with `400`.
   * @throws NotFoundException when no book has that id.
   */
  @Delete(':id')
  @HttpCode(204)
  remove(@Param('id', ParseIntPipe) id: number): Promise<void> {
    return this.booksService.remove(id);
  }
}
```

### `src/books/books.controller.spec.ts`

```ts
import { Test } from '@nestjs/testing';
import type { Book } from './book.entity.js';
import { BooksController } from './books.controller.js';
import { BooksService } from './books.service.js';

describe('BooksController', () => {
  const dune: Book = { id: 1, title: 'Dune', author: 'Frank Herbert' };
  const service = {
    findAll: vi.fn(),
    findOne: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
  };
  let controller: BooksController;

  beforeEach(async () => {
    vi.clearAllMocks();
    const moduleRef = await Test.createTestingModule({
      controllers: [BooksController],
      providers: [{ provide: BooksService, useValue: service }],
    }).compile();
    controller = moduleRef.get(BooksController);
  });

  it('passes the page parameters to the service', async () => {
    service.findAll.mockResolvedValue([dune]);

    await expect(controller.findAll({ limit: 5, offset: 10 })).resolves.toEqual(
      [dune],
    );
    expect(service.findAll).toHaveBeenCalledWith(5, 10);
  });

  it('returns one book', async () => {
    service.findOne.mockResolvedValue(dune);

    await expect(controller.findOne(1)).resolves.toEqual(dune);
  });

  it('creates a book', async () => {
    service.create.mockResolvedValue(dune);

    await expect(
      controller.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).resolves.toEqual(dune);
  });

  it('deletes a book', async () => {
    service.remove.mockResolvedValue(undefined);

    await controller.remove(1);

    expect(service.remove).toHaveBeenCalledWith(1);
  });
});
```

### `src/books/books.module.ts`

```ts
import { Module } from '@nestjs/common';
import { BooksController } from './books.controller.js';
import { BooksRepository } from './books.repository.js';
import { BooksService } from './books.service.js';

/** The book catalog feature: its HTTP controller, service, and repository. Exports the service only. */
@Module({
  controllers: [BooksController],
  providers: [BooksService, BooksRepository],
  exports: [BooksService],
})
export class BooksModule {}
```

### `test/app.factory.ts`

```ts
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import type { App } from 'supertest/types.js';
import { AppModule } from '../src/app.module.js';
import { configureApp } from '../src/app.setup.js';

/**
 * Boots the whole `AppModule` with the same `configureApp()` setup as `main.ts`, without listening on a port
 * (supertest binds an ephemeral one itself). Close the returned application in `afterAll`.
 *
 * @returns The initialized application.
 */
export async function createTestApp(): Promise<INestApplication<App>> {
  const moduleRef = await Test.createTestingModule({
    imports: [AppModule],
  }).compile();
  const app = configureApp(
    moduleRef.createNestApplication<INestApplication<App>>(),
  );
  await app.init();
  return app;
}
```

### `test/health.e2e-spec.ts`

```ts
import type { INestApplication } from '@nestjs/common';
import request from 'supertest';
import type { App } from 'supertest/types.js';
import { createTestApp } from './app.factory.js';

describe('Health (e2e)', () => {
  let app: INestApplication<App>;

  beforeAll(async () => {
    app = await createTestApp();
  });

  afterAll(async () => {
    await app.close();
  });

  it('GET /health/live answers 200 with the security headers set', async () => {
    const response = await request(app.getHttpServer())
      .get('/health/live')
      .expect(200);

    expect(response.body).toMatchObject({ status: 'ok' });
    expect(response.headers['x-content-type-options']).toBe('nosniff');
  });

  it('GET /health/ready answers 200', () =>
    request(app.getHttpServer()).get('/health/ready').expect(200));
});
```

### `test/books.e2e-spec.ts`

```ts
import type { INestApplication } from '@nestjs/common';
import request from 'supertest';
import type { App } from 'supertest/types.js';
import { createTestApp } from './app.factory.js';

describe('Books (e2e)', () => {
  let app: INestApplication<App>;

  beforeAll(async () => {
    app = await createTestApp();
  });

  afterAll(async () => {
    await app.close();
  });

  it('creates, reads, lists, and deletes a book', async () => {
    const created = await request(app.getHttpServer())
      .post('/books')
      .send({ title: 'Dune', author: 'Frank Herbert' })
      .expect(201);
    const id: unknown = created.body.id;
    expect(typeof id).toBe('number');

    await request(app.getHttpServer())
      .get(`/books/${String(id)}`)
      .expect(200)
      .expect({ id, title: 'Dune', author: 'Frank Herbert' });
    const list = await request(app.getHttpServer())
      .get('/books?limit=10')
      .expect(200);
    expect(list.body).toContainEqual({
      id,
      title: 'Dune',
      author: 'Frank Herbert',
    });

    await request(app.getHttpServer())
      .delete(`/books/${String(id)}`)
      .expect(204);
    await request(app.getHttpServer())
      .get(`/books/${String(id)}`)
      .expect(404);
  });

  it('rejects a body with a missing field with 400', () =>
    request(app.getHttpServer())
      .post('/books')
      .send({ title: 'Dune' })
      .expect(400));

  it('rejects unknown properties with 400', () =>
    request(app.getHttpServer())
      .post('/books')
      .send({ title: 'Dune', author: 'Frank Herbert', admin: true })
      .expect(400));

  it('rejects a non-integer id with 400', () =>
    request(app.getHttpServer()).get('/books/abc').expect(400));

  it('rejects a page size above the cap with 400', () =>
    request(app.getHttpServer()).get('/books?limit=1000').expect(400));
});
```

## `http: fastify` deltas

Replace `@nestjs/platform-express` (and `@types/express`) with `@nestjs/platform-fastify`. Two files change; the
e2e specs type the app as `NestFastifyApplication` (import it from `@nestjs/platform-fastify` and drop the
`supertest/types` import) and keep using supertest — it works once the Fastify instance is `ready()`, which the
factory awaits (verified: all e2e specs pass, including the security-header assertion). `listen(..., '0.0.0.0')`
matters on Fastify: its default host is loopback, unreachable from outside a container.

### `src/main.ts (Fastify)`

```ts
import { ConfigService } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import {
  FastifyAdapter,
  type NestFastifyApplication,
} from '@nestjs/platform-fastify';
import { AppModule } from './app.module.js';
import { configureApp } from './app.setup.js';

/** Creates the application on Fastify, applies the shared setup, and listens on the validated `PORT`. */
async function bootstrap(): Promise<void> {
  const app = configureApp(
    await NestFactory.create<NestFastifyApplication>(
      AppModule,
      new FastifyAdapter(),
    ),
  );
  const config = app.get(ConfigService);
  // Fastify listens on loopback only unless told otherwise: unreachable from outside a container.
  await app.listen(config.getOrThrow<number>('PORT'), '0.0.0.0');
}
await bootstrap();
```

### `test/app.factory.ts (Fastify)`

```ts
import {
  FastifyAdapter,
  type NestFastifyApplication,
} from '@nestjs/platform-fastify';
import { Test } from '@nestjs/testing';
import { AppModule } from '../src/app.module.js';
import { configureApp } from '../src/app.setup.js';

/**
 * Boots the whole `AppModule` on Fastify with the same `configureApp()` setup as `main.ts`, without listening on
 * a port. Close the returned application in `afterAll`.
 *
 * @returns The initialized application, ready for supertest (`app.getHttpServer()`) or `app.inject()`.
 */
export async function createTestApp(): Promise<NestFastifyApplication> {
  const moduleRef = await Test.createTestingModule({
    imports: [AppModule],
  }).compile();
  const app = configureApp(
    moduleRef.createNestApplication<NestFastifyApplication>(
      new FastifyAdapter(),
    ),
  );
  await app.init();
  await app.getHttpAdapter().getInstance().ready();
  return app;
}
```
