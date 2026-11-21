# Validation and DTOs

Read this when the task adds or changes a DTO, a route parameter, a query, a pipe, or anything that reads request
input. Validate at the boundary with a pipe — never in the handler body, and never trust a TypeScript type alone:
types don't exist at runtime.

## Which style the project uses

Look before writing:

- **class-validator** — `class-validator`/`class-transformer` in `package.json`, DTO classes with `@IsString()`
  etc., a `ValidationPipe` bound globally. The long-standing default; required by the Swagger CLI plugin and
  mapped types.
- **Standard Schema** (Nest 12+) — Zod/Valibot/ArkType schemas passed as `@Body({ schema })`,
  `@Query({ schema })`, `@Param('id', { schema })`, with `StandardSchemaValidationPipe` bound globally.
  Parameter types come from the schema (`z.infer<typeof createReviewSchema>`), so interfaces/type aliases are fine
  here.

Follow whichever the surrounding feature uses. Both pipes can be registered side by side (each only acts on the
parameters it understands), so a project migrating gradually may have both — don't mix them within one DTO.

## The global pipe is registered once, and tests must get it too

The expected global setup is
`new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true, transform: true })` — strip undeclared
properties, reject them (mass-assignment defence), and turn bodies into DTO instances and query strings into the
declared types. It lives in **one** of two places:

- an `APP_PIPE` provider in one module (`{ provide: APP_PIPE, useFactory: () => new ValidationPipe({...}) }`) —
  part of the module graph, so `Test.createTestingModule({ imports: [AppModule] })` gets it automatically; or
- a shared `configureApp(app)` function (any name — `setupApp`, `hardenApp`) that `main.ts` **and** every e2e test
  call. `app.useGlobalPipes()` written inline in `main.ts` alone is the classic trap: `main.ts` never runs in tests,
  so the e2e suite validates nothing.

If the task needs global validation and the project has neither, add it the way the project's existing global
setup is done; if there is no global setup at all, prefer `APP_PIPE` and say so in the report. Don't add a second
registration of the same pipe — validation runs twice and the error format of the first one wins.

## class-validator DTO rules

- One DTO class per input shape: `dto/create-book.dto.ts`, `dto/update-book.dto.ts`,
  `dto/list-books.query.ts`. Derive variants with `PartialType`/`PickType`/`OmitType`/`IntersectionType` (from
  `@nestjs/mapped-types`, or `@nestjs/swagger` when the project documents with Swagger — use the one already
  imported) rather than copying fields.
- **Every field carries a validator**; with `whitelist` an undecorated field is silently stripped. Optional fields
  get `@IsOptional()` and `?`.
- **Nested objects need both** `@ValidateNested()` and `@Type(() => AuthorDto)`; arrays of nested DTOs add
  `{ each: true }`. A top-level array body loses its element type — use `ParseArrayPipe({ items: CreateBookDto })`
  or wrap it in a class.
- **Query and path values are strings.** Use `@Type(() => Number)` + `@IsInt()` in a query DTO (with `transform`),
  or a parse pipe on the parameter: `@Param('id', ParseIntPipe) id: number`, `ParseUUIDPipe`, `ParseBoolPipe`,
  `ParseEnumPipe`, `new DefaultValuePipe(1)` before `ParseIntPipe` for optional values. Never `+id` / `Number(id)`
  in the handler — `abc` becomes `NaN` instead of a `400` (the scaffold's `nest g resource` controller does exactly
  this; replace it).
- Bound list endpoints: `limit` gets `@Max(100)` (or the project's cap) and a default.
- Response shapes are not request DTOs. Don't return entities raw when they carry fields the client must not see —
  map to a response type, or use the project's serialization approach (`ClassSerializerInterceptor` +
  `@Exclude()`, or explicit mapping).

## Pipes

- Built-in parse pipes cover most needs. A custom pipe implements `PipeTransform<In, Out>`, is `@Injectable()`,
  and throws `BadRequestException` (a plain `Error` becomes a `500`).
- Pass the **class** (`@Param('id', ParseSlugPipe)`) to get DI, an instance (`new ParseIntPipe({ optional: true })`)
  to pass options.
- Pipes on WebSocket gateways / microservice handlers need an `exceptionFactory` that returns `WsException` /
  `RpcException`.

## Pitfalls that look like passing code

| Symptom | Cause |
|---|---|
| Body never validated | DTO is an interface/type alias, or the class was imported with `import type`. |
| Validation works in prod, not in e2e (or vice versa) | Global pipe only in `main.ts`; use `APP_PIPE` or the shared `configureApp`. |
| Nested rules ignored | Missing `@ValidateNested()` **or** `@Type()`. |
| `dto instanceof CreateBookDto` is false | `transform` not enabled. |
| Query numbers arrive as strings | No `@Type(() => Number)` / parse pipe. |
| Unknown fields reach the service | No `whitelist`. |
| Method-level `@UsePipes(new ValidationPipe({...}))` options seem ignored | The global pipe already ran and threw first. |
