# Enhancers: guards, interceptors, pipes, filters, middleware

Read this when the task adds or changes a guard, interceptor, pipe class, exception filter, middleware, or custom
decorator — or registers any of them globally.

## The request lifecycle decides where code goes

```
middleware → guards → interceptors (before) → pipes → handler → interceptors (after) → response
                                              └─ anything thrown after routing → exception filters
```

| Need | Use |
|---|---|
| Correlation id, cookie parsing, a header on **every** request (even 404s) | Middleware |
| Allow/deny by identity, role, API key, feature flag | Guard (`CanActivate`, reads metadata via `Reflector`) |
| Time, map/envelope, cache, retry/timeout the handler, translate errors | Interceptor (`NestInterceptor`) |
| Validate/convert one argument | Pipe (`validation-and-dtos.md`) |
| Turn an exception into a specific HTTP response | Exception filter |

Consequences worth remembering:

- **A guard sees the raw request** — body parsed but not validated. Authorize on identity and metadata; never on
  `request.body` values.
- **Guards run before interceptors**, so a guard can't use anything an interceptor attaches, and an interceptor can't
  do authorization.
- Within a stage: global → controller → route (→ parameter for pipes). Filters are the exception: route →
  controller → global, first match wins, and **they don't chain** — a handled exception never reaches the next
  filter (extend `BaseExceptionFilter` or rethrow to chain).
- Errors thrown in middleware reach **only global** filters.
- A 404 (no matching route) never reaches guards, interceptors, or pipes.

## Registering: DI or not, and only once

| Binding | DI in the enhancer? |
|---|---|
| `{ provide: APP_GUARD \| APP_INTERCEPTOR \| APP_PIPE \| APP_FILTER, useClass: X }` in a module | **Yes** — the preferred global form |
| `app.useGlobalGuards(new X())` etc. in `main.ts` | **No**, and invisible to `Test.createTestingModule` |
| `@UseGuards(X)` / `@UseInterceptors(X)` / `@UseFilters(X)` with the **class** | Yes |
| Same decorators with an **instance** (`new X(opts)`) | No (use for options) |

- **Register each global enhancer from exactly one module.** An `APP_*` provider in a shared module imported in
  several places, or `APP_INTERCEPTOR` *plus* `useGlobalInterceptors()`, runs it twice per request (double logging,
  `{ "data": { "data": … } }`). Don't also add a globally registered enhancer with `@UseInterceptors` on a
  controller.
- **Make global enhancers overridable in tests**: register the class as a provider and point the token at it,
  `providers: [JwtAuthGuard, { provide: APP_GUARD, useExisting: JwtAuthGuard }]`. With `useClass` under the
  token, `overrideProvider(JwtAuthGuard)` finds nothing to replace.
- Global guards run in registration order (all `APP_GUARD`s, then `useGlobalGuards()`): authenticate before you
  authorize.
- `nest g gu|itc|pi|f|mi` generates the class **without registering it anywhere** — wiring it is part of the task
  (`cli-generators.md`).

## Guards

- `canActivate` returns `boolean | Promise<boolean> | Observable<boolean>`. Returning `false` always produces
  **403**; throw `UnauthorizedException` when the caller should get **401** (missing/invalid credentials).
- The generated guard returns `true` unconditionally — never ship the placeholder.
- Metadata: `export const Roles = Reflector.createDecorator<string[]>();` (the Nest 12 `nest g d` output), read with
  `this.reflector.getAllAndOverride(Roles, [context.getHandler(), context.getClass()])`. A roles guard that allows
  when no metadata is present is default-allow — pair it with a global, default-deny authentication guard and an
  explicit `@Public()` opt-out (`security.md`).
- A guard that fills `request.user` should expose it through a custom parameter decorator
  (`createParamDecorator`) rather than handlers reading `req` directly.
- Write guards against `ExecutionContext` (`context.getType()` when they must serve HTTP and another transport).

## Interceptors

- **Always return `next.handle()`** (or another stream) on every branch — a branch that doesn't silently skips the
  handler and may hang the request.
- `tap()` after `next.handle()` runs only on success; use `finalize()` for work that must always run. Code before
  `next.handle()` runs even when the handler later fails.
- Handlers using `@Res()` without `{ passthrough: true }` bypass response-mapping interceptors.
- Keep them platform-neutral: read only what Express and Fastify requests both expose (`method`, `url`).

## Exception filters

- Prefer throwing the right `HttpException` subclass from the service over writing a filter. A filter is for
  mapping a *domain* error type, or for a project-wide error format.
- Declare a catch-everything filter **first** — the last-declared filter is tried first.
- A catch-everything filter treats `exception` as `unknown`, keeps `HttpException` statuses, logs unknown errors,
  and never echoes their raw messages to the client.
- Reply through `HttpAdapterHost` (`httpAdapter.reply(ctx.getResponse(), body, status)`) — `response.status().json()`
  is Express-only and throws `response.status is not a function` on Fastify.

## Middleware

- Class middleware (`NestMiddleware`, `@Injectable()`) gets DI and is applied in a module's
  `configure(consumer: MiddlewareConsumer)` with `.apply(X).forRoutes(...)`; functional middleware has no DI.
- Express 5 / Fastify 5 route patterns use named wildcards: `forRoutes('{*splat}')`, not `'*'`.
- On Fastify, middleware receives raw Node `IncomingMessage`/`ServerResponse`, and `req.url` is relative to the
  mount point (use `req.originalUrl`).
- Throw (or `next(err)`) from middleware bound through `MiddlewareConsumer`; only global filters will see it.
