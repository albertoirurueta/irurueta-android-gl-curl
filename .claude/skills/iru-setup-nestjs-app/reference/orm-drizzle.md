# `orm: drizzle` (Drizzle ORM 0.45 + `@nestjs/drizzle`, PostgreSQL)

Verified on ESM (Vitest): typecheck, lint, unit tests + coverage 100 % lines, `test:e2e` against a real
PostgreSQL 18 container with the drizzle-kit migrations applied by the global setup, `build`. For CommonJS apply
the conversion rules in `app-templates.md` and the Jest global setup/teardown pattern from `orm-prisma.md`.

## Packages

- dependencies: `drizzle-orm@0.45` (pin the stable line — `1.0.0` is still an `rc` tag), `@nestjs/drizzle`
  (pre-1.0; its peer range covers Nest 12), `pg@8`
- devDependencies: `drizzle-kit@0.31`, `@types/pg@8`, `testcontainers`, `@testcontainers/postgresql`

## Scripts and migrations

`"db:generate": "drizzle-kit generate"` (diffs `src/database/schema.ts` against `drizzle/` and writes the next SQL
migration) and `"db:migrate": "drizzle-kit migrate"` (applies them to `DATABASE_URL`). Generate the first one at
scaffold time with `npx drizzle-kit generate --name init` (no database needed) and commit `drizzle/` (SQL + `meta/`).
Add `drizzle` to `.prettierignore` (generated JSON). `env.schema.ts` gains `DATABASE_URL` and `app.module.ts`
imports `DatabaseModule` exactly as in `orm-prisma.md`.

Terminus has no Drizzle indicator, so `database.health.ts` runs `select 1` through `HealthIndicatorService`.

### `drizzle.config.ts`

```ts
import { defineConfig } from 'drizzle-kit';

// drizzle-kit configuration: `npm run db:generate` diffs src/database/schema.ts against drizzle/ and writes a
// new SQL migration; `npm run db:migrate` applies pending ones to DATABASE_URL.
export default defineConfig({
  dialect: 'postgresql',
  schema: './src/database/schema.ts',
  out: './drizzle',
  dbCredentials: { url: process.env['DATABASE_URL'] ?? '' },
});
```

### `src/database/schema.ts`

```ts
import { pgTable, serial, varchar } from 'drizzle-orm/pg-core';

/** The `books` table. Change it, then run `npm run db:generate` to write the matching migration. */
export const books = pgTable('books', {
  id: serial('id').primaryKey(),
  title: varchar('title', { length: 200 }).notNull(),
  author: varchar('author', { length: 200 }).notNull(),
});
```

### `src/database/database.ts`

```ts
import type { NodePgDatabase } from 'drizzle-orm/node-postgres';
import type * as schema from './schema.js';

/** The application's Drizzle database type, injected with `@InjectDrizzle()`. */
export type Database = NodePgDatabase<typeof schema>;
```

### `src/database/database.module.ts`

```ts
import { Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { DrizzleModule } from '@nestjs/drizzle';
import { drizzle } from 'drizzle-orm/node-postgres';
import * as schema from './schema.js';

/**
 * The application's PostgreSQL connection (Drizzle over node-postgres), configured from the validated
 * `DATABASE_URL`. Inject it with `@InjectDrizzle()` as a {@link Database}. The schema comes from drizzle-kit
 * migrations in `drizzle/`.
 */
@Module({
  imports: [
    DrizzleModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        drizzle,
        connection: config.getOrThrow<string>('DATABASE_URL'),
        schema,
        autoCloseConnection: true,
      }),
    }),
  ],
})
export class DatabaseModule {}
```

### `src/books/books.repository.ts`

```ts
import { Injectable } from '@nestjs/common';
import { InjectDrizzle } from '@nestjs/drizzle';
import { asc, eq } from 'drizzle-orm';
import type { Database } from '../database/database.js';
import { books } from '../database/schema.js';
import type { Book, NewBook } from './book.entity.js';

/** Storage for books, backed by PostgreSQL through Drizzle. */
@Injectable()
export class BooksRepository {
  constructor(@InjectDrizzle() private readonly db: Database) {}

  /**
   * Lists books ordered by id.
   *
   * @param limit - Maximum number of books to return.
   * @param offset - Number of books to skip.
   * @returns The requested page, possibly empty.
   */
  findAll(limit: number, offset: number): Promise<readonly Book[]> {
    return this.db
      .select()
      .from(books)
      .orderBy(asc(books.id))
      .limit(limit)
      .offset(offset);
  }

  /**
   * Finds one book.
   *
   * @param id - Identifier of the book.
   * @returns The book, or `undefined` when no book has that id.
   */
  async findById(id: number): Promise<Book | undefined> {
    const [book] = await this.db.select().from(books).where(eq(books.id, id));
    return book;
  }

  /**
   * Stores a new book.
   *
   * @param data - The book's fields.
   * @returns The stored book with its assigned id.
   */
  async create(data: NewBook): Promise<Book> {
    const [book] = await this.db
      .insert(books)
      .values({ title: data.title, author: data.author })
      .returning();
    if (!book) {
      throw new Error('INSERT ... RETURNING returned no row');
    }
    return book;
  }

  /**
   * Deletes one book.
   *
   * @param id - Identifier of the book.
   * @returns `true` when a book was deleted, `false` when none had that id.
   */
  async delete(id: number): Promise<boolean> {
    const deleted = await this.db
      .delete(books)
      .where(eq(books.id, id))
      .returning({ id: books.id });
    return deleted.length > 0;
  }
}
```

### `src/books/books.repository.spec.ts`

```ts
import { getDrizzleToken } from '@nestjs/drizzle';
import { Test } from '@nestjs/testing';
import { BooksRepository } from './books.repository.js';

describe('BooksRepository (Drizzle)', () => {
  const dune = { id: 1, title: 'Dune', author: 'Frank Herbert' };
  // Stand-ins for Drizzle's query builders: intermediate steps return the chain, the last step of each query
  // resolves to `rows`.
  let rows: unknown[] = [];
  const chain = {
    from: vi.fn(),
    orderBy: vi.fn(),
    limit: vi.fn(),
    offset: vi.fn(),
    where: vi.fn(),
    values: vi.fn(),
    returning: vi.fn(),
  };
  const db = { select: vi.fn(), insert: vi.fn(), delete: vi.fn() };
  let repository: BooksRepository;

  beforeEach(async () => {
    vi.clearAllMocks();
    rows = [];
    for (const step of [chain.from, chain.orderBy, chain.limit, chain.values]) {
      step.mockReturnValue(chain);
    }
    for (const last of [chain.offset, chain.where, chain.returning]) {
      last.mockImplementation(() => Promise.resolve(rows));
    }
    for (const start of [db.select, db.insert, db.delete]) {
      start.mockReturnValue(chain);
    }
    const moduleRef = await Test.createTestingModule({
      providers: [
        BooksRepository,
        { provide: getDrizzleToken(), useValue: db },
      ],
    }).compile();
    repository = moduleRef.get(BooksRepository);
  });

  it('pages through books', async () => {
    rows = [dune];

    await expect(repository.findAll(10, 20)).resolves.toEqual([dune]);
    expect(chain.limit).toHaveBeenCalledWith(10);
    expect(chain.offset).toHaveBeenCalledWith(20);
  });

  it('finds a book by id, or undefined', async () => {
    rows = [dune];
    await expect(repository.findById(1)).resolves.toEqual(dune);

    rows = [];
    await expect(repository.findById(9)).resolves.toBeUndefined();
  });

  it('creates a book from explicit fields', async () => {
    rows = [dune];

    await expect(
      repository.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).resolves.toEqual(dune);
    expect(chain.values).toHaveBeenCalledWith({
      title: 'Dune',
      author: 'Frank Herbert',
    });
  });

  it('fails loudly when the insert returns no row', async () => {
    rows = [];

    await expect(
      repository.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).rejects.toThrow(Error);
  });

  it('reports whether a book was deleted', async () => {
    chain.where.mockReturnValue(chain);
    rows = [{ id: 1 }];
    await expect(repository.delete(1)).resolves.toBe(true);

    rows = [];
    await expect(repository.delete(1)).resolves.toBe(false);
  });
});
```

### `src/health/database.health.ts`

```ts
import { Injectable } from '@nestjs/common';
import { InjectDrizzle } from '@nestjs/drizzle';
import {
  HealthIndicatorService,
  type HealthIndicatorResult,
} from '@nestjs/terminus';
import { sql } from 'drizzle-orm';
import type { Database } from '../database/database.js';

/** Terminus indicator that runs `SELECT 1` through Drizzle (Terminus has no built-in Drizzle indicator). */
@Injectable()
export class DatabaseHealthIndicator {
  constructor(
    private readonly indicators: HealthIndicatorService,
    @InjectDrizzle() private readonly db: Database,
  ) {}

  /**
   * Pings the database.
   *
   * @param key - Key of the result in the health report.
   * @returns `up` when the query answers within 1.5 s, `down` otherwise.
   */
  isHealthy<Key extends string>(
    key: Key,
  ): PromiseLike<HealthIndicatorResult<Key>> {
    return this.indicators
      .check(key)
      .attempt(async () => {
        await this.db.execute(sql`select 1`);
      })
      .withTimeout(1500);
  }
}
```

### `src/health/database.health.spec.ts`

```ts
import { getDrizzleToken } from '@nestjs/drizzle';
import { HealthIndicatorService } from '@nestjs/terminus';
import { Test } from '@nestjs/testing';
import { DatabaseHealthIndicator } from './database.health.js';

describe('DatabaseHealthIndicator', () => {
  const db = { execute: vi.fn() };
  let indicator: DatabaseHealthIndicator;

  beforeEach(async () => {
    vi.clearAllMocks();
    const moduleRef = await Test.createTestingModule({
      providers: [
        DatabaseHealthIndicator,
        HealthIndicatorService,
        { provide: getDrizzleToken(), useValue: db },
      ],
    }).compile();
    indicator = moduleRef.get(DatabaseHealthIndicator);
  });

  it('is up when the database answers', async () => {
    db.execute.mockResolvedValue({ rows: [] });

    await expect(indicator.isHealthy('database')).resolves.toMatchObject({
      database: { status: 'up' },
    });
  });

  it('is down when the query fails', async () => {
    db.execute.mockRejectedValue(new Error('connection refused'));

    await expect(indicator.isHealthy('database')).resolves.toMatchObject({
      database: { status: 'down' },
    });
  });
});
```

### `src/health/health.controller.ts`

```ts
import { Controller, Get } from '@nestjs/common';
import {
  HealthCheck,
  HealthCheckService,
  type HealthCheckResult,
} from '@nestjs/terminus';
import { DatabaseHealthIndicator } from './database.health.js';

/**
 * Liveness and readiness probes under `/health`, for the container `HEALTHCHECK`, Kubernetes probes, and load
 * balancers. Liveness never checks dependencies (a database outage must not restart every replica); readiness
 * checks what the instance needs to serve traffic.
 */
@Controller('health')
export class HealthController {
  constructor(
    private readonly health: HealthCheckService,
    private readonly database: DatabaseHealthIndicator,
  ) {}

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
   * @returns The Terminus result; `503` when the database does not answer within 1.5 s.
   */
  @Get('ready')
  @HealthCheck()
  ready(): Promise<HealthCheckResult> {
    return this.health.check([() => this.database.isHealthy('database')]);
  }
}
```

### `src/health/health.controller.spec.ts`

```ts
import { HealthCheckService, type HealthCheckResult } from '@nestjs/terminus';
import { Test } from '@nestjs/testing';
import { DatabaseHealthIndicator } from './database.health.js';
import { HealthController } from './health.controller.js';

describe('HealthController', () => {
  const ok: HealthCheckResult = {
    status: 'ok',
    info: {},
    error: {},
    details: {},
  };
  const health = { check: vi.fn() };
  const database = { isHealthy: vi.fn() };
  let controller: HealthController;

  beforeEach(async () => {
    vi.clearAllMocks();
    health.check.mockImplementation(
      async (checks: readonly (() => unknown)[]) => {
        await Promise.all(checks.map((check) => check()));
        return ok;
      },
    );
    const moduleRef = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [
        { provide: HealthCheckService, useValue: health },
        { provide: DatabaseHealthIndicator, useValue: database },
      ],
    }).compile();
    controller = moduleRef.get(HealthController);
  });

  it('reports liveness without checking dependencies', async () => {
    await expect(controller.live()).resolves.toEqual(ok);
    expect(health.check).toHaveBeenCalledWith([]);
  });

  it('reports readiness by pinging the database', async () => {
    database.isHealthy.mockResolvedValue({ database: { status: 'up' } });

    await expect(controller.ready()).resolves.toEqual(ok);
    expect(database.isHealthy).toHaveBeenCalledWith('database');
  });
});
```

### `src/health/health.module.ts`

```ts
import { Module } from '@nestjs/common';
import { TerminusModule } from '@nestjs/terminus';
import { DatabaseHealthIndicator } from './database.health.js';
import { HealthController } from './health.controller.js';

/** Health checks (`@nestjs/terminus`): the `/health/live` and `/health/ready` endpoints. */
@Module({
  imports: [TerminusModule.forRoot()],
  controllers: [HealthController],
  providers: [DatabaseHealthIndicator],
})
export class HealthModule {}
```

### `test/global-setup.ts`

```ts
import { PostgreSqlContainer } from '@testcontainers/postgresql';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';

/**
 * Starts one throwaway PostgreSQL container for the whole e2e run, applies the drizzle-kit migrations in
 * `drizzle/` to it, and exports its URL as `DATABASE_URL` before any test file imports `AppModule` (test workers
 * start after this and inherit the environment). Needs a running Docker daemon.
 *
 * @returns The teardown function that stops the container.
 */
export default async function setup(): Promise<() => Promise<void>> {
  const postgres = await new PostgreSqlContainer('postgres:18-alpine').start();
  process.env['DATABASE_URL'] = postgres.getConnectionUri();

  const db = drizzle(postgres.getConnectionUri());
  await migrate(db, { migrationsFolder: './drizzle' });
  await db.$client.end();

  return async () => {
    await postgres.stop();
  };
}
```
