# Configuration

Read this when the task reads or adds an environment variable or setting, or touches `ConfigModule`.

## The shape to expect (and to keep)

- `ConfigModule.forRoot({ isGlobal: true, validationSchema: envSchema })` once, in `AppModule`. Every other module
  injects `ConfigService` without importing `ConfigModule` again. Since `@nestjs/config` 12, `validationSchema`
  takes any Standard Schema (Zod is the documented default; Valibot, ArkType, Joi ≥ 18 also work — library options go
  under `validationOptions.libraryOptions`). Joi < 18 fails at startup.
- **A new variable is added to the schema in the same task**, with its coercion and default (or no default, which
  makes it required). Environment variables are strings: `z.coerce.number()`, `z.stringbool()` — `'false'` is
  truthy otherwise. The app then refuses to start with a clear `Config validation error: PORT: …` instead of
  failing on first use.
- Add the variable to the project's `.env.example` (or equivalent documentation) when one exists — never to a
  committed `.env`, and never a real secret anywhere in the repository.

## Reading values

- `config.getOrThrow<string>('DATABASE_URL')` for anything required — fail fast at bootstrap, not on the first
  request. `get()` with a default only for genuinely optional settings.
- `get<number>('PORT')` is a type *hint*, not a conversion — the schema's coercion is what makes it a number.
  `ConfigService<Env, true>` (with `Env = z.output<typeof envSchema>`) types keys and values when the project uses it.
- **Namespaces**: `registerAs('database', () => ({ … }))` loaded with `load: [databaseConfig]`, injected as
  `@Inject(databaseConfig.KEY) private readonly db: ConfigType<typeof databaseConfig>`. Import it as
  `import { type ConfigType } from '@nestjs/config'` — a plain import fails with `TS1272` under `isolatedModules` +
  `emitDecoratorMetadata`. `validationSchema` does **not** validate `load` factories; validate inside the factory.
- Feature-scoped config: `ConfigModule.forFeature(cfg)`; read those values in `onModuleInit()` or inject by `KEY`,
  not in a constructor that may run before they are registered.
- Since Nest 11, `get()` reads `load` factories first, then validated variables, then `process.env`.

## Configuring other modules

Any module option that comes from configuration uses the async form so it waits for `ConfigModule`:

```ts
TypeOrmModule.forRootAsync({
  inject: [ConfigService],
  useFactory: (config: ConfigService) => ({
    type: 'postgres',
    url: config.getOrThrow<string>('DATABASE_URL'),
    autoLoadEntities: true,
    synchronize: false,
  }),
}),
```

The same applies to `JwtModule.registerAsync`, `ThrottlerModule.forRootAsync`, `MongooseModule.forRootAsync`,
`DrizzleModule.forRootAsync`, etc. Never read `process.env.X` at module-decorator evaluation time: `forRoot()`
options are evaluated **when the file is imported**, before validation, and before a test can set the variable.

## In `main.ts` and tests

- `main.ts` reads settings via `app.get(ConfigService)` after `NestFactory.create()`.
- Tests set variables **before** importing the module under test (Vitest `test.env`, a setup file, or
  `process.env.X = …` at the top of the spec before a dynamic `import`), or bypass `ConfigModule` by providing a
  `ConfigService` double (`{ provide: ConfigService, useValue: { getOrThrow: vi.fn().mockReturnValue('…') } }`).
- `cache: true` freezes reads — fine in production, surprising in tests that change values.

## Secrets

Secrets come from the environment (or the platform's secret store) through `ConfigService`; they're never logged,
never returned in a response or error body, and never given a default in the schema.
