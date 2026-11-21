# `orm: typeorm` (TypeORM 1.x, PostgreSQL)

Verified on CommonJS (Jest 30 + ts-jest) — the blocks below are the CommonJS files, so relative imports carry no
`.js` (add it for ESM, see the end of this file): unit tests + coverage ≥ 80 %, `test:e2e` against a real
PostgreSQL 18 container with the migrations run in the global setup, `build`, `migration:run` /
`migration:revert` / `migration:generate` (which reported "No changes in database schema", proving the hand-written
migration matches the entity), and the Docker image answering `/health/ready` with the database `up`.

## Packages

- dependencies: `@nestjs/typeorm@12`, `typeorm@1`, `pg@8`
- devDependencies: `testcontainers`, `@testcontainers/postgresql`

## Scripts to add

```json
"migration:generate": "typeorm migration:generate -d dist/database/data-source.js",
"migration:run": "typeorm migration:run -d dist/database/data-source.js",
"migration:revert": "typeorm migration:revert -d dist/database/data-source.js"
```

They read the **compiled** data source, so `npm run build` first — the same command works for ESM and CommonJS
(the TypeORM CLI cannot load TypeScript under ESM). New migration:
`npm run build && npm run migration:generate -- src/database/migrations/<Name>`, then add the class to
`src/database/migrations/index.ts`. Migrations are listed explicitly (no globs) so one array works from `dist/`,
under both test runners, and in the CLI. Never `synchronize: true`.

### `src/books/book.entity.ts`

```ts
import { Column, Entity, PrimaryGeneratedColumn } from 'typeorm';

/** A book in the catalog, persisted in the `books` table and returned as-is by the API. */
@Entity('books')
export class Book {
  /** Server-assigned identifier. */
  @PrimaryGeneratedColumn()
  id: number;

  /** Title of the book. */
  @Column({ length: 200 })
  title: string;

  /** Author of the book. */
  @Column({ length: 200 })
  author: string;
}

/** The fields a client supplies to create a {@link Book}. */
export type NewBook = Pick<Book, 'title' | 'author'>;
```

### `src/books/books.repository.ts`

```ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Book, type NewBook } from './book.entity';

/** Storage for books, backed by PostgreSQL through TypeORM. */
@Injectable()
export class BooksRepository {
  constructor(
    @InjectRepository(Book) private readonly books: Repository<Book>,
  ) {}

  /**
   * Lists books ordered by id.
   *
   * @param limit - Maximum number of books to return.
   * @param offset - Number of books to skip.
   * @returns The requested page, possibly empty.
   */
  findAll(limit: number, offset: number): Promise<readonly Book[]> {
    return this.books.find({ order: { id: 'ASC' }, take: limit, skip: offset });
  }

  /**
   * Finds one book.
   *
   * @param id - Identifier of the book.
   * @returns The book, or `undefined` when no book has that id.
   */
  async findById(id: number): Promise<Book | undefined> {
    return (await this.books.findOneBy({ id })) ?? undefined;
  }

  /**
   * Stores a new book.
   *
   * @param data - The book's fields.
   * @returns The stored book with its assigned id.
   */
  create(data: NewBook): Promise<Book> {
    return this.books.save(
      this.books.create({ title: data.title, author: data.author }),
    );
  }

  /**
   * Deletes one book.
   *
   * @param id - Identifier of the book.
   * @returns `true` when a book was deleted, `false` when none had that id.
   */
  async delete(id: number): Promise<boolean> {
    const { affected } = await this.books.delete({ id });
    return (affected ?? 0) > 0;
  }
}
```

### `src/books/books.repository.spec.ts`

```ts
import { Test } from '@nestjs/testing';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Book } from './book.entity';
import { BooksRepository } from './books.repository';

describe('BooksRepository (TypeORM)', () => {
  const dune: Book = { id: 1, title: 'Dune', author: 'Frank Herbert' };
  const books = {
    find: jest.fn(),
    findOneBy: jest.fn(),
    create: jest.fn(),
    save: jest.fn(),
    delete: jest.fn(),
  };
  let repository: BooksRepository;

  beforeEach(async () => {
    jest.clearAllMocks();
    const moduleRef = await Test.createTestingModule({
      providers: [
        BooksRepository,
        { provide: getRepositoryToken(Book), useValue: books },
      ],
    }).compile();
    repository = moduleRef.get(BooksRepository);
  });

  it('pages through books ordered by id', async () => {
    books.find.mockResolvedValue([dune]);

    await expect(repository.findAll(10, 20)).resolves.toEqual([dune]);
    expect(books.find).toHaveBeenCalledWith({
      order: { id: 'ASC' },
      take: 10,
      skip: 20,
    });
  });

  it('finds a book by id', async () => {
    books.findOneBy.mockResolvedValue(dune);

    await expect(repository.findById(1)).resolves.toEqual(dune);
  });

  it('returns undefined for an unknown id', async () => {
    books.findOneBy.mockResolvedValue(null);

    await expect(repository.findById(9)).resolves.toBeUndefined();
  });

  it('creates a book from explicit fields', async () => {
    books.create.mockImplementation((fields: Partial<Book>) => fields);
    books.save.mockResolvedValue(dune);

    await expect(
      repository.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).resolves.toEqual(dune);
    expect(books.create).toHaveBeenCalledWith({
      title: 'Dune',
      author: 'Frank Herbert',
    });
  });

  it('reports whether a book was deleted', async () => {
    books.delete
      .mockResolvedValueOnce({ affected: 1 })
      .mockResolvedValueOnce({ affected: 0 });

    await expect(repository.delete(1)).resolves.toBe(true);
    await expect(repository.delete(1)).resolves.toBe(false);
  });
});
```

### `src/books/books.module.ts`

```ts
import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { Book } from './book.entity';
import { BooksController } from './books.controller';
import { BooksRepository } from './books.repository';
import { BooksService } from './books.service';

/** The book catalog feature: its HTTP controller, service, and repository. Exports the service only. */
@Module({
  imports: [TypeOrmModule.forFeature([Book])],
  controllers: [BooksController],
  providers: [BooksService, BooksRepository],
  exports: [BooksService],
})
export class BooksModule {}
```

### `src/database/database.module.ts`

```ts
import { Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MIGRATIONS } from './migrations';

/**
 * The application's PostgreSQL connection (TypeORM), configured from the validated `DATABASE_URL`. Entities are
 * registered by each feature's `TypeOrmModule.forFeature()`; the schema comes from migrations, never `synchronize`.
 */
@Module({
  imports: [
    TypeOrmModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        type: 'postgres',
        url: config.getOrThrow<string>('DATABASE_URL'),
        autoLoadEntities: true,
        synchronize: false,
        migrations: MIGRATIONS,
      }),
    }),
  ],
})
export class DatabaseModule {}
```

### `src/database/data-source.ts`

```ts
import { DataSource } from 'typeorm';
import { Book } from '../books/book.entity';
import { MIGRATIONS } from './migrations';

/**
 * Builds the TypeORM data source used outside Nest: by the TypeORM CLI (`migration:*` scripts) and by the e2e
 * global setup. Migrations run outside the application, so this reads plain `process.env`, not `ConfigService`.
 *
 * @param url - PostgreSQL connection URL.
 * @returns An uninitialized data source.
 */
export function createDataSource(url: string | undefined): DataSource {
  return new DataSource({
    type: 'postgres',
    url,
    entities: [Book],
    migrations: MIGRATIONS,
  });
}

// The TypeORM CLI requires a default export (`typeorm migration:run -d dist/database/data-source.js`).
export default createDataSource(process.env['DATABASE_URL']);
```

### `src/database/migrations/index.ts`

```ts
import { CreateBooks1760000000000 } from './1760000000000-create-books';

/**
 * Every migration, in order. Listed explicitly (not as a file glob) so the same array works from `dist/`, under
 * the test runners, and in the TypeORM CLI. Add each new migration class here.
 */
export const MIGRATIONS = [CreateBooks1760000000000];
```

### `src/database/migrations/1760000000000-create-books.ts`

```ts
import type { MigrationInterface, QueryRunner } from 'typeorm';

/** Creates the `books` table. */
export class CreateBooks1760000000000 implements MigrationInterface {
  name = 'CreateBooks1760000000000';

  /** @param queryRunner - Runner of the migration's transaction. */
  async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `CREATE TABLE "books" ("id" SERIAL NOT NULL, "title" character varying(200) NOT NULL, "author" character varying(200) NOT NULL, CONSTRAINT "PK_books_id" PRIMARY KEY ("id"))`,
    );
  }

  /** @param queryRunner - Runner of the migration's transaction. */
  async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP TABLE "books"`);
  }
}
```

### `src/health/health.controller.ts`

```ts
import { Controller, Get } from '@nestjs/common';
import {
  HealthCheck,
  HealthCheckService,
  TypeOrmHealthIndicator,
  type HealthCheckResult,
} from '@nestjs/terminus';

/**
 * Liveness and readiness probes under `/health`, for the container `HEALTHCHECK`, Kubernetes probes, and load
 * balancers. Liveness never checks dependencies (a database outage must not restart every replica); readiness
 * checks what the instance needs to serve traffic.
 */
@Controller('health')
export class HealthController {
  constructor(
    private readonly health: HealthCheckService,
    private readonly database: TypeOrmHealthIndicator,
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
    return this.health.check([
      () => this.database.pingCheck('database').withTimeout(1500),
    ]);
  }
}
```

### `src/health/health.controller.spec.ts`

```ts
import {
  HealthCheckService,
  TypeOrmHealthIndicator,
  type HealthCheckResult,
} from '@nestjs/terminus';
import { Test } from '@nestjs/testing';
import { HealthController } from './health.controller';

describe('HealthController', () => {
  const ok: HealthCheckResult = {
    status: 'ok',
    info: {},
    error: {},
    details: {},
  };
  const health = { check: jest.fn() };
  const attempt = { withTimeout: jest.fn() };
  const database = { pingCheck: jest.fn() };
  let controller: HealthController;

  beforeEach(async () => {
    jest.clearAllMocks();
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
        { provide: TypeOrmHealthIndicator, useValue: database },
      ],
    }).compile();
    controller = moduleRef.get(HealthController);
  });

  it('reports liveness without checking dependencies', async () => {
    await expect(controller.live()).resolves.toEqual(ok);
    expect(health.check).toHaveBeenCalledWith([]);
  });

  it('reports readiness by pinging the database', async () => {
    database.pingCheck.mockReturnValue(attempt);
    attempt.withTimeout.mockResolvedValue({ database: { status: 'up' } });

    await expect(controller.ready()).resolves.toEqual(ok);
    expect(database.pingCheck).toHaveBeenCalledWith('database');
    expect(attempt.withTimeout).toHaveBeenCalledWith(1500);
  });
});
```

### `test/global-setup.ts`

```ts
import {
  PostgreSqlContainer,
  type StartedPostgreSqlContainer,
} from '@testcontainers/postgresql';
import { createDataSource } from '../src/database/data-source';

declare global {
  var postgresContainer: StartedPostgreSqlContainer | undefined;
}

/**
 * Starts one throwaway PostgreSQL container for the whole e2e run, applies the migrations to it, and exports its
 * URL as `DATABASE_URL` before any test file imports `AppModule` (test workers start after this and inherit the
 * environment). Needs a running Docker daemon. `global-teardown.ts` stops it.
 */
export default async function setup(): Promise<void> {
  const postgres = await new PostgreSqlContainer('postgres:18-alpine').start();
  globalThis.postgresContainer = postgres;
  process.env['DATABASE_URL'] = postgres.getConnectionUri();

  const dataSource = await createDataSource(
    postgres.getConnectionUri(),
  ).initialize();
  await dataSource.runMigrations();
  await dataSource.destroy();
}
```

### `test/global-teardown.ts`

```ts
/** Stops the PostgreSQL container `global-setup.ts` started. */
export default async function teardown(): Promise<void> {
  await globalThis.postgresContainer?.stop();
}
```

### `test/jest-e2e.json`

```json
{
  "moduleFileExtensions": ["js", "json", "ts"],
  "rootDir": ".",
  "testEnvironment": "node",
  "testRegex": ".e2e-spec.ts$",
  "transform": {
    "^.+\\.(t|j)s$": "ts-jest"
  },
  "globalSetup": "<rootDir>/global-setup.ts",
  "globalTeardown": "<rootDir>/global-teardown.ts",
  "testTimeout": 30000,
  "moduleNameMapper": {
    "^(\\.{1,2}/.*)\\.js$": "$1"
  }
}
```

## ESM variant

Add `.js` to every relative import above (`'./book.entity.js'`, `'./migrations/index.js'` — a directory import
needs the explicit `index.js` under ESM), and replace the Jest global setup/teardown with one Vitest
`globalSetup` file returning its teardown, wired in `vitest.config.e2e.ts` exactly like `orm-prisma.md` shows:

```ts
import { PostgreSqlContainer } from '@testcontainers/postgresql';
import { createDataSource } from '../src/database/data-source.js';

export default async function setup(): Promise<() => Promise<void>> {
  const postgres = await new PostgreSqlContainer('postgres:18-alpine').start();
  process.env['DATABASE_URL'] = postgres.getConnectionUri();

  const dataSource = await createDataSource(postgres.getConnectionUri()).initialize();
  await dataSource.runMigrations();
  await dataSource.destroy();

  return async () => {
    await postgres.stop();
  };
}
```

Under ESM, relations between entities must be typed `Relation<Other>` (from `typeorm`) or startup dies with
`ReferenceError: Cannot access ... before initialization` (see `iru-nestjs-code-one-task`'s `persistence.md`).
The ESM form of this file was not separately run end to end; it combines the verified CommonJS files with the
verified ESM conventions of the other ORM files.

## `database: mysql`

Not verified. Use `type: 'mysql'`, `mysql2` instead of `pg`, `@testcontainers/mysql` (`MySqlContainer('mysql:8.4')`)
in the global setup, `TypeOrmHealthIndicator` unchanged, and write the migration's SQL for MySQL
(`INT AUTO_INCREMENT`, `VARCHAR(200)`) — or generate it with `migration:generate` against a MySQL database.
