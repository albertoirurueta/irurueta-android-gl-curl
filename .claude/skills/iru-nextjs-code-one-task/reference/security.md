# Security

Most Next.js security defects come from treating the server/client boundary as an internal call. It isn't: every
Server Action, Route Handler, and API route is a public HTTP endpoint, and everything sent to a Client Component is
public.

## Data access: one layer, DTOs out

- If the project has a data access layer (`lib/dal.ts`, `server/`, `data/`, a `verifySession`/`getUser`/`auth`
  helper), route every new read and write through it. Only that layer imports the DB client and reads secret env
  vars.
- Every such module starts with `import "server-only"` (build fails if a Client Component imports it). Not in
  modules shared with `pages/`.
- Return **DTOs**: a new object with explicit fields, never `{ ...row }`. Type Client Component props as the DTO,
  not the database row. Decide per field whether the viewer may see it.
- Session helper memoized per request with `React.cache` (`export const verifySession = cache(async () => ...)`),
  reading the cookie with `await cookies()` (15+), decrypting without throwing on a bad cookie, and **re-reading
  the user** — a role in the cookie is only a hint.

## Where checks live

| Place | What it may do |
|---|---|
| Proxy / middleware | Optimistic checks only (cookie present/signed) and redirects. Never the only gate; no DB calls. |
| Layout | Nothing security-relevant — layouts don't re-run on navigation and don't protect nested segments, actions, or handlers. |
| Page / leaf Server Component | Call the session helper; `redirect("/login")`, `notFound()`, or `forbidden()` (with `experimental.authInterrupts`). |
| Server Action | Authenticate + validate + authorize ownership, every time (`server-actions.md`). |
| Route Handler / API route | Return 401/403 — no redirects. |
| Client hooks (`useSession`) | Display only. |

- Take **ids** from the client and check ownership server-side (IDOR). Look up prices, roles, totals on the
  server.
- `redirect`/`notFound`/`forbidden`/`unauthorized` throw — call them outside `try/catch`.
- Under `16-cc`/inside `<Suspense>` the status is already 200 when a nested check runs; the data layer stays the
  authority. Never read `cookies()` inside a plain `'use cache'` scope; cache keys and tags use user **ids**, never
  tokens or emails.

## Environment variables

- Only `NEXT_PUBLIC_*` variables reach the browser — inlined as literals at **build** time. Secrets (API keys, DB
  URLs, `AUTH_SECRET`, signing keys) are never prefixed. Write `process.env.NEXT_PUBLIC_X` literally (dynamic
  lookups aren't inlined).
- An unprefixed variable read in a Client Component is `undefined` in the browser and causes a hydration error —
  read it on the server and pass the result.
- Server variables read in a prerendered route are frozen at build; read them per request after
  `await connection()` (15+), or inside `<Suspense>` under `16-cc`.
- Load order (first wins): real environment → `.env.$(NODE_ENV).local` → `.env.local` (skipped when `NODE_ENV=test`)
  → `.env.$(NODE_ENV)` → `.env`. Files live at the project root. Never commit `.env*.local`; commit only an
  `.env.example`/`.env.test` with placeholders.
- `serverRuntimeConfig`/`publicRuntimeConfig` were removed in 16; the `env` key in `next.config` inlines into the
  bundle — don't use it for secrets.

## Session cookies

Set only on the server (in an action or handler): `httpOnly: true`, `secure` in production, `sameSite: "lax"`,
`path: "/"`, an expiry; minimal signed payload (id + expiry). Same error message for unknown user and wrong
password. Never put sessions or secrets in URLs.

## XSS and redirects

- React escapes text; don't bypass it. `dangerouslySetInnerHTML` only with a maintained sanitizer; JSON in inline
  scripts with `JSON.stringify(v).replace(/</g, "\\u003c")`.
- Allow-list URL schemes (`https:`, `mailto:`) for user-supplied links. Post-login and `?from=` redirects:
  same-origin paths only. Never pass untrusted URLs to `redirect`/`router.push`.

## Headers and CSP (only when the task asks)

- Static security headers in `next.config` `headers()`: `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, `Strict-Transport-Security`;
  `poweredByHeader: false`.
- A **nonce CSP** is generated per request in `proxy.ts` (16) / `middleware.ts` (14/15): set it on the request
  headers (`x-nonce`) and the response. It requires per-request rendering — it disables static rendering and ISR
  for the affected routes and is incompatible with Partial Prerendering shells. Pass `nonce` to `<Script>`/inline
  scripts from `(await headers()).get("x-nonce")`. `'unsafe-eval'` in development only. A static CSP in
  `next.config` keeps routes static at the cost of `'unsafe-inline'`. Either way this changes the build table —
  report it.

## Other

- `images.remotePatterns` as narrow as possible; don't enable `dangerouslyAllowSVG`.
- Rate-limit expensive actions/handlers if the project has a limiter.
- `experimental.taint` (React taint APIs) is a tripwire only, never a replacement for DTOs — don't enable it unless
  the project already does.
- Multi-instance deployments need a shared `NEXT_SERVER_ACTIONS_ENCRYPTION_KEY` — never `NEXT_PUBLIC_`, never
  committed.
- Stay on the latest patch of the project's Next line; don't upgrade as part of an unrelated task.
