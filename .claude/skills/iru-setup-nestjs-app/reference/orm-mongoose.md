# `orm: mongoose` (Mongoose 9, MongoDB)

Verified on ESM (Vitest): typecheck, lint, unit tests + coverage 100 % lines, `test:e2e` against a real `mongo:8`
container (Testcontainers single-node replica set), and `build`. For CommonJS apply the conversion rules in
`app-templates.md` and the Jest global setup/teardown pattern from `orm-prisma.md`.

## Packages

- dependencies: `@nestjs/mongoose@12`, `mongoose@9`
- devDependencies: `testcontainers`, `@testcontainers/mongodb`

## Differences from the SQL variants

- Ids are ObjectId **strings**: `Book.id` is `string`, the service and controller take `id: string`, and the
  controller validates `:id` with `IsObjectIdPipe` from `@nestjs/mongoose` (a malformed id is a `400`, not a
  `CastError` `500`). Update the service/controller specs to use an ObjectId hex string
  (`'665f1c2b9d3e4a0012345678'`) where they used `1`/`9`, and the e2e `typeof id` assertion to `'string'`.
- The persistence document (`book.schema.ts`) is separate from the API shape (`book.entity.ts`); the repository
  maps one to the other, so `_id`/`__v` never leak.
- No migrations: schema changes are code; data migrations, when needed, are scripts the team runs explicitly.
- `DATABASE_URL` is a `mongodb://` URL (the e2e setup appends `/app?directConnection=true`).
- Transactions need a replica set — the Testcontainers module starts one; production must too.
- `env.schema.ts` gains `DATABASE_URL` exactly as in `orm-prisma.md` (use `mongodb://localhost:27017/app` in its
  spec), and `app.module.ts` imports `DatabaseModule` the same way.

### `src/books/book.entity.ts`

```ts
/** A book in the catalog, as returned by the API. */
export interface Book {
  /** Server-assigned identifier (a MongoDB ObjectId in hex form). */
  readonly id: string;
  /** Title of the book. */
  readonly title: string;
  /** Author of the book. */
  readonly author: string;
}

/** The fields a client supplies to create a {@link Book}. */
export type NewBook = Omit<Book, 'id'>;
```

### `src/books/book.schema.ts`

```ts
import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import type { HydratedDocument } from 'mongoose';

/** The `books` collection's document shape (Mongoose 9). Mapped to the API's `Book` by the repository. */
@Schema({ collection: 'books' })
export class BookDocument {
  /** Title of the book. */
  @Prop({ required: true, maxlength: 200 })
  title: string;

  /** Author of the book. */
  @Prop({ required: true, maxlength: 200 })
  author: string;
}

/** Mongoose schema compiled from {@link BookDocument}. */
export const BookSchema = SchemaFactory.createForClass(BookDocument);

/** A hydrated `books` document. */
export type HydratedBook = HydratedDocument<BookDocument>;
```

### `src/books/books.repository.ts`

```ts
import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import type { Model } from 'mongoose';
import type { Book, NewBook } from './book.entity.js';
import { BookDocument, type HydratedBook } from './book.schema.js';

/** Storage for books, backed by MongoDB through Mongoose. */
@Injectable()
export class BooksRepository {
  constructor(
    @InjectModel(BookDocument.name) private readonly books: Model<BookDocument>,
  ) {}

  /**
   * Lists books ordered by id (insertion order).
   *
   * @param limit - Maximum number of books to return.
   * @param offset - Number of books to skip.
   * @returns The requested page, possibly empty.
   */
  async findAll(limit: number, offset: number): Promise<readonly Book[]> {
    const documents = await this.books
      .find()
      .sort({ _id: 1 })
      .skip(offset)
      .limit(limit)
      .exec();
    return documents.map(toBook);
  }

  /**
   * Finds one book.
   *
   * @param id - Identifier of the book (a valid ObjectId).
   * @returns The book, or `undefined` when no book has that id.
   */
  async findById(id: string): Promise<Book | undefined> {
    const document = await this.books.findById(id).exec();
    return document ? toBook(document) : undefined;
  }

  /**
   * Stores a new book.
   *
   * @param data - The book's fields.
   * @returns The stored book with its assigned id.
   */
  async create(data: NewBook): Promise<Book> {
    return toBook(
      await this.books.create({ title: data.title, author: data.author }),
    );
  }

  /**
   * Deletes one book.
   *
   * @param id - Identifier of the book (a valid ObjectId).
   * @returns `true` when a book was deleted, `false` when none had that id.
   */
  async delete(id: string): Promise<boolean> {
    const { deletedCount } = await this.books.deleteOne({ _id: id }).exec();
    return deletedCount > 0;
  }
}

function toBook(document: HydratedBook): Book {
  return {
    id: document._id.toHexString(),
    title: document.title,
    author: document.author,
  };
}
```

### `src/books/books.repository.spec.ts`

```ts
import { getModelToken } from '@nestjs/mongoose';
import { Test } from '@nestjs/testing';
import { Types } from 'mongoose';
import { BookDocument } from './book.schema.js';
import { BooksRepository } from './books.repository.js';

describe('BooksRepository (Mongoose)', () => {
  const _id = new Types.ObjectId();
  const document = { _id, title: 'Dune', author: 'Frank Herbert' };
  const dune = {
    id: _id.toHexString(),
    title: 'Dune',
    author: 'Frank Herbert',
  };
  const query = { sort: vi.fn(), skip: vi.fn(), limit: vi.fn(), exec: vi.fn() };
  const model = {
    find: vi.fn(),
    findById: vi.fn(),
    create: vi.fn(),
    deleteOne: vi.fn(),
  };
  let repository: BooksRepository;

  beforeEach(async () => {
    vi.clearAllMocks();
    for (const step of [query.sort, query.skip, query.limit]) {
      step.mockReturnValue(query);
    }
    model.find.mockReturnValue(query);
    model.findById.mockReturnValue(query);
    model.deleteOne.mockReturnValue(query);
    const moduleRef = await Test.createTestingModule({
      providers: [
        BooksRepository,
        { provide: getModelToken(BookDocument.name), useValue: model },
      ],
    }).compile();
    repository = moduleRef.get(BooksRepository);
  });

  it('pages through books ordered by id', async () => {
    query.exec.mockResolvedValue([document]);

    await expect(repository.findAll(10, 20)).resolves.toEqual([dune]);
    expect(query.sort).toHaveBeenCalledWith({ _id: 1 });
    expect(query.skip).toHaveBeenCalledWith(20);
    expect(query.limit).toHaveBeenCalledWith(10);
  });

  it('finds a book by id', async () => {
    query.exec.mockResolvedValue(document);

    await expect(repository.findById(dune.id)).resolves.toEqual(dune);
  });

  it('returns undefined for an unknown id', async () => {
    query.exec.mockResolvedValue(null);

    await expect(repository.findById(dune.id)).resolves.toBeUndefined();
  });

  it('creates a book from explicit fields', async () => {
    model.create.mockResolvedValue(document);

    await expect(
      repository.create({ title: 'Dune', author: 'Frank Herbert' }),
    ).resolves.toEqual(dune);
    expect(model.create).toHaveBeenCalledWith({
      title: 'Dune',
      author: 'Frank Herbert',
    });
  });

  it('reports whether a book was deleted', async () => {
    query.exec
      .mockResolvedValueOnce({ deletedCount: 1 })
      .mockResolvedValueOnce({ deletedCount: 0 });

    await expect(repository.delete(dune.id)).resolves.toBe(true);
    await expect(repository.delete(dune.id)).resolves.toBe(false);
  });
});
```

### `src/books/books.module.ts`

```ts
import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { BookDocument, BookSchema } from './book.schema.js';
import { BooksController } from './books.controller.js';
import { BooksRepository } from './books.repository.js';
import { BooksService } from './books.service.js';

/** The book catalog feature: its HTTP controller, service, and repository. Exports the service only. */
@Module({
  imports: [
    MongooseModule.forFeature([
      { name: BookDocument.name, schema: BookSchema },
    ]),
  ],
  controllers: [BooksController],
  providers: [BooksService, BooksRepository],
  exports: [BooksService],
})
export class BooksModule {}
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
  async findOne(id: string): Promise<Book> {
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
  async remove(id: string): Promise<void> {
    if (!(await this.books.delete(id))) {
      throw new NotFoundException(`Book ${id} not found`);
    }
  }
}
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
  Post,
  Query,
} from '@nestjs/common';
import { IsObjectIdPipe } from '@nestjs/mongoose';
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
   * @param id - Identifier of the book; an invalid ObjectId is rejected with `400`.
   * @returns The book.
   * @throws NotFoundException when no book has that id.
   */
  @Get(':id')
  findOne(@Param('id', IsObjectIdPipe) id: string): Promise<Book> {
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
   * @param id - Identifier of the book; an invalid ObjectId is rejected with `400`.
   * @throws NotFoundException when no book has that id.
   */
  @Delete(':id')
  @HttpCode(204)
  remove(@Param('id', IsObjectIdPipe) id: string): Promise<void> {
    return this.booksService.remove(id);
  }
}
```

### `src/database/database.module.ts`

```ts
import { Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { MongooseModule } from '@nestjs/mongoose';

/** The application's MongoDB connection (Mongoose), configured from the validated `DATABASE_URL`. */
@Module({
  imports: [
    MongooseModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        uri: config.getOrThrow<string>('DATABASE_URL'),
      }),
    }),
  ],
})
export class DatabaseModule {}
```

### `src/health/health.controller.ts`

```ts
import { Controller, Get } from '@nestjs/common';
import {
  HealthCheck,
  HealthCheckService,
  MongooseHealthIndicator,
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
    private readonly database: MongooseHealthIndicator,
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
  MongooseHealthIndicator,
  type HealthCheckResult,
} from '@nestjs/terminus';
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
  const attempt = { withTimeout: vi.fn() };
  const database = { pingCheck: vi.fn() };
  let controller: HealthController;

  beforeEach(async () => {
    vi.clearAllMocks();
    health.check.mockResolvedValue(ok);
    const moduleRef = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [
        { provide: HealthCheckService, useValue: health },
        { provide: MongooseHealthIndicator, useValue: database },
      ],
    }).compile();
    controller = moduleRef.get(HealthController);
  });

  it('reports liveness without checking dependencies', async () => {
    await expect(controller.live()).resolves.toEqual(ok);
    expect(health.check).toHaveBeenCalledWith([]);
  });

  it('reports readiness by pinging the database', async () => {
    health.check.mockImplementation(
      async (checks: readonly (() => unknown)[]) => {
        await Promise.all(checks.map((check) => check()));
        return ok;
      },
    );
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
import { MongoDBContainer } from '@testcontainers/mongodb';

/**
 * Starts one throwaway MongoDB container (a single-node replica set, so transactions work) for the whole e2e run
 * and exports its URL as `DATABASE_URL` before any test file imports `AppModule`. Needs a running Docker daemon.
 *
 * @returns The teardown function that stops the container.
 */
export default async function setup(): Promise<() => Promise<void>> {
  const mongo = await new MongoDBContainer('mongo:8').start();
  process.env['DATABASE_URL'] =
    `${mongo.getConnectionString()}/app?directConnection=true`;

  return async () => {
    await mongo.stop();
  };
}
```
