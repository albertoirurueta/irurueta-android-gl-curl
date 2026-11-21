# Persistence

Read this when the task touches an entity, schema, model, repository, query, transaction, or migration. Read the
**shared rules** and then only the section for the ORM Step 1 detected.

| ORM | Detected by |
|---|---|
| TypeORM | `@nestjs/typeorm` + `typeorm` |
| Prisma | `prisma` / `@prisma/client` + `prisma/schema.prisma` |
| Mongoose | `@nestjs/mongoose` + `mongoose` |
| Drizzle | `drizzle-orm` (+ `@nestjs/drizzle`, pre-1.0) |
| MikroORM | `@mikro-orm/nestjs` |

## Shared rules

- **Never `synchronize: true`** (TypeORM/MikroORM schema sync, `prisma db push` in a deploy path) outside a
  throwaway test database. Schema changes ship as a **migration** in the same task as the entity change, using the
  project's existing migration tool and directory, with a working `down()`/rollback where the tool supports one.
  Review generated SQL: a rename can come out as drop + add (data loss).
- **Migrations run outside Nest** — no DI, no `ConfigService`; plain SQL / schema-API calls reading
  `process.env`. Don't make the app run them at startup unless the project already does.
- **Inside a transaction, every query goes through the transaction handle** (`manager` / `queryRunner.manager`,
  Prisma `tx`, Mongoose `{ session }`, Drizzle `tx`, MikroORM's forked `em`). An injected repository or
  `this.prisma` runs on another pooled connection, outside the transaction, and is not rolled back. Never swallow
  the error after a rollback — rethrow.
- **No unbounded lists**: paginate with a deterministic `ORDER BY` (offset, or keyset for deep/live data), and
  cap the page size.
- **Don't return ORM objects raw** when they carry fields the client must not see (password hashes, internal
  flags, foreign keys) — map to a response type.
- **Connection settings come from `ConfigService`** through the module's `forRootAsync` (`configuration.md`).
- **Circular entity imports under ESM** crash at startup (`ReferenceError`): type single-valued relations as
  `Relation<Author>` (TypeORM) / `Rel<Author>` (MikroORM).
- **Not-found is an outcome, not a `null` leak**: the service turns a missing row into `NotFoundException` (or a
  domain error), and documents it in `@throws`.

## TypeORM

- Feature modules register entities with `TypeOrmModule.forFeature([Book])`; inject with
  `@InjectRepository(Book) private readonly books: Repository<Book>`. With `autoLoadEntities: true`, an entity not
  registered through some `forFeature()` is unknown to the data source even if a relation points at it.
- Transactions: `this.dataSource.transaction(async (manager) => { … })` for a self-contained unit of work, or a
  `QueryRunner` (`connect` → `startTransaction` → work on `queryRunner.manager` → `commitTransaction`, `catch` →
  `rollbackTransaction` + rethrow, `finally` → `release()`). A runner that isn't released leaks a connection.
- A repository method that must take part in a caller's transaction accepts an optional `EntityManager`.
- Migrations: `MigrationInterface` classes with `up`/`down`; generated via the TypeORM CLI against a separate
  `data-source.ts` (in an ESM project the CLI needs compiled JS — follow the project's existing script).
- TypeORM is 1.x since 2026; re-check 0.3-era snippets before copying them.

## Prisma (7)

- `PrismaService extends PrismaClient`, constructed with a **driver adapter** (`@prisma/adapter-pg`,
  `-better-sqlite3`, `-mariadb`, `-mssql`) from `config.getOrThrow('DATABASE_URL')`; `$connect()` in
  `onModuleInit`, `$disconnect()` in `onModuleDestroy`. `$on('beforeExit')` is gone in 7.
- The generated client (`provider = "prisma-client"`, `output` inside `src/`) is **not committed**: after changing
  `schema.prisma`, run `npx prisma generate` (and `npx prisma migrate dev --name <change>` to create the migration
  when the task changes the schema and a database is available; otherwise write the migration SQL by hand in
  `prisma/migrations/` and say so). Import the client with the `.js` suffix under ESM
  (`./generated/prisma/client.js`); a CommonJS project needs `moduleFormat = "cjs"` in the generator block.
- Transactions: interactive `this.prisma.$transaction(async (tx) => { … })` with **every** query on `tx`; the array
  form for independent queries. `nestjs-cls` + its Prisma adapter when the project already propagates transactions.
- Generated types (`Prisma.BookWhereInput`) are compile-time only — request input is still validated by DTOs.
- Don't upgrade or install Prisma unpinned: `npm install prisma` resolves to a Prisma 8 release candidate; keep
  `prisma` and `@prisma/client` on the project's major.

## Mongoose (9)

- Schema classes: `@Schema()` + `@Prop()`, `SchemaFactory.createForClass(Book)`, document type
  `HydratedDocument<Book>` (not `extends Document`). Register with
  `MongooseModule.forFeature([{ name: Book.name, schema: BookSchema }])`, inject with
  `@InjectModel(Book.name) private readonly books: Model<Book>`.
- Hooks are plain/async functions **without `next`** in Mongoose 9.
- `.lean().exec()` for reads you only serialize; `.exec()` for real Promises.
- Validate `:id` as an ObjectId at the boundary (a parse pipe / `isValidObjectId`) so a malformed id is a `400`, not
  a `CastError` `500`.
- Transactions need a **replica set**: `this.connection.transaction(async (session) => …)` (or
  `session.withTransaction`) with `{ session }` passed to **every** operation, no `Promise.all` inside, and a callback
  that is safe to retry (no e-mails inside). Inject the connection with `@InjectConnection()`.

## Drizzle

- Inject the database with `@InjectDrizzle()`; the schema is plain TypeScript. Register through
  `DrizzleModule.forRootAsync` (so e2e apps each get their own connection).
- `db.transaction((tx) => …)` with every statement on `tx`. With a **synchronous driver** (`better-sqlite3`) the
  callback must **not** be `async` (statements end in `.run()/.get()/.all()`) — an async callback throws after the
  first statement and the write is not rolled back.
- Migrations with `drizzle-kit generate` / `migrate`. Pin the Drizzle major the project uses (v1 is an `rc` tag).

## MikroORM

- Repositories via `MikroOrmModule.forFeature([Book])` + `@InjectRepository(Book)`; writes go through the
  `EntityManager` (unit of work, `em.flush()`); `em.transactional(async (em) => …)` uses the forked `em`.
- Each HTTP request runs in a `RequestContext` (default middleware). Cron jobs, queue consumers, and event handlers
  have none: decorate them with `@CreateRequestContext()` (below `@Cron()`), or the call throws.
- Don't combine with `ClassSerializerInterceptor`.
