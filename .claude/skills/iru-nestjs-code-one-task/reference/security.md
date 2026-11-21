# Security

Read this when the task touches authentication, authorization, rate limiting, CORS, security headers, CSRF,
secrets, or ownership checks — or adds an endpoint that should not be public. Follow the mechanisms the project
already has; the rules below say what "correct" means for each.

## Authentication: default deny

- The expected setup is **one global authentication guard** (`APP_GUARD`, registered with `useExisting` so tests can
  override it) that rejects every route unless it is explicitly marked public with a metadata decorator
  (`export const Public = Reflector.createDecorator<boolean>()` / the project's `@Public()`), read with
  `reflector.getAllAndOverride(Public, [context.getHandler(), context.getClass()])`.
- A new endpoint is **protected unless the task says it is public**. Adding `@Public()` is a decision the task must
  state; if it's ambiguous, ask (Step 5) rather than guess.
- Missing or invalid credentials → throw `UnauthorizedException` (401). Authenticated but not allowed →
  `ForbiddenException` (403). A guard returning `false` always yields 403.
- JWT with `@nestjs/jwt`: secret/keys from `ConfigService` (`registerAsync`), **pin the algorithm** on both sides
  (`signOptions.algorithm`, `verifyOptions.algorithms`), short-lived access tokens; `verifyAsync` failures map to 401.
  Passport strategies (`@nestjs/passport`) are fine where the project already uses them.
- Expose the authenticated user through a custom parameter decorator (`@CurrentUser()`), never by reading a field on
  a singleton.

## Authorization

- **Function level**: roles/permissions metadata (`Reflector.createDecorator<string[]>()`) checked by a guard
  registered **after** authentication. Review that the guard denies when the user lacks the role — a roles guard that
  allows when no metadata is present is only safe behind the default-deny authentication guard.
- **Object level (BOLA)** — the most common API vulnerability: a valid token proves who the caller is, not which
  rows they may touch. Check ownership **in the service, where the object is loaded**, and take the owner from the
  token (`user.sub`), **never from the request body or query**. Return 403 (or 404 to hide existence) for someone
  else's object; never skip the check because ids are UUIDs.
- **Mass assignment**: `ValidationPipe` with `whitelist` + `forbidNonWhitelisted`, and DTOs that simply don't
  declare server-controlled fields (`ownerId`, `role`, `isAdmin`, `createdAt`). Map DTO → entity field by field.
- **Data exposure**: never return password hashes, tokens, internal ids or flags — map to a response type or use the
  project's serialization (`@Exclude()` + `ClassSerializerInterceptor`).

## No per-request state on singletons

Providers are singletons shared by concurrent requests. Storing the current user, tenant, locale, or request id in a
field of a service, guard, or interceptor leaks one caller's data into another's request. Pass it as an argument,
or read it from `AsyncLocalStorage` / `nestjs-cls` (or `@nestjs/observe`) if the project has one. `Scope.REQUEST` is
the heavyweight alternative (`modules-and-di.md`).

## Rate limiting (`@nestjs/throttler`)

- `ThrottlerModule.forRootAsync({ … })` once, plus `{ provide: APP_GUARD, useClass: ThrottlerGuard }` (not
  `useGlobalGuards(new ThrottlerGuard())`, which can't inject its options/storage).
- Tighter limits on credential and expensive endpoints (`@Throttle({ default: { limit: 5, ttl: seconds(60) } })` on
  login, password reset, OTP); `@SkipThrottle()` only for health checks and similar.
- Behind a proxy, the tracker is the proxy unless the app trusts it: Express `app.set('trust proxy', 1)` (exact hop
  count), Fastify `trustProxy`. Multi-instance deployments need shared storage (Redis).

## Headers, CORS, CSRF

- Security headers: Nest 12.1+ `app.useSecurityHeaders({ … })` (Helmet 8 defaults, Express and Fastify), or the
  `helmet` package already in use. Register before `init()`/`listen()`, inside the shared `configureApp` so e2e
  tests see the same headers.
- CORS: an explicit allow-list of exact origins from configuration (`CORS_ORIGINS`), explicit `methods` (Fastify's
  default omits `PUT`/`PATCH`/`DELETE`), never `origin: '*'` with `credentials: true`. CORS is a browser control,
  not an access control.
- CSRF matters only for cookie-authenticated browser clients: Nest 12.1+ `app.enableCsrfProtection({
  trustedOrigins })` (Fetch-Metadata/Origin check on state-changing methods), or a token scheme (`csrf-csrf`,
  `@fastify/csrf-protection`). `GET` handlers must never change state.

## Secrets, crypto, injection

- Secrets only from configuration/the environment, validated at startup, never logged or echoed in errors.
- Passwords: `argon2` (argon2id) or `bcrypt`, or `scrypt` from `node:crypto` with `timingSafeEqual` — never a fast
  hash, never `===` on secrets. Encryption: `aes-256-gcm` with a random IV per message.
- Queries: ORM parameters or tagged/bound parameters only — never string-concatenated SQL or a Mongo filter built
  from an unvalidated object (`{ $where … }`, operator injection through `@Body()` objects).
- Outbound requests to user-supplied URLs need an allow-list (SSRF).
- Uploads: validate size and type with `ParseFilePipe` validators; never trust the client filename.
