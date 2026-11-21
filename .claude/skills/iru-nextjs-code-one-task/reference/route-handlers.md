# Route Handlers and the proxy

## Route Handler conventions (App Router)

- `app/**/route.ts` exports **named** functions `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`;
  unexported methods get 405. A `route.ts` and a `page.tsx` cannot share a folder ("Conflicting route and page") —
  put endpoints under `app/api/...` or file-like segments (`app/rss.xml/route.ts`).
- Handlers sit outside the layout tree: a layout's auth check never protects them. **Every handler is public** —
  authenticate and authorize inside it and return `401` (no session) / `403` (not allowed) before touching data.
  Never redirect an API caller to a login page.
- Signature by profile:

  | Profile | Dynamic params |
  |---|---|
  | `14` | `export async function GET(request: Request, { params }: { params: { id: string } })` |
  | `15` (≤15.4) | `ctx: { params: Promise<{ id: string }> }` then `const { id } = await ctx.params` |
  | `15.5+`, `16-*` | `ctx: RouteContext<"/api/todos/[id]">` (global, from `next typegen`), `await ctx.params` |

  Params arrive decoded in Route Handlers (pages receive them percent-encoded). They are always strings —
  validate and narrow them once.
- Request: `request.nextUrl.searchParams`, `request.cookies` (with `NextRequest`), or `await cookies()`/
  `await headers()` from `next/headers` (15+; sync in 14). `headers()` is read-only — set headers on the response.
- Response: `Response.json(body, { status, headers })` / `NextResponse.json`. Create → `201` + `Location`.
  Per-user responses: `Cache-Control: private, no-store`. `NextResponse.next()` is proxy-only.
- Body: read once (`json()`/`text()`/`formData()`); `request.json()` in a `try` → `400` on malformed JSON;
  validate with a schema → `422` with field errors. There is no body-size limit in the App Router — check
  `Content-Length` or cap the stream for uploads.
- Errors: map expected cases to status codes (`404`, `409`, upstream failure → `502`), a small JSON error shape, no
  stack traces.
- Segment config allowed: `dynamicParams`, `runtime` (`"nodejs"` default; `"edge"` deprecated, rejected under
  `16-cc`), `maxDuration`; plus `dynamic`/`revalidate` in the previous model only.

## Caching by profile

- **`14`**: `GET` handlers are cached unless they read the request or a dynamic function — upgrading to 15 silently
  makes them dynamic.
- **`15`, `16-prev`**: `GET` runs per request. Opt in to static with `export const dynamic = "force-static"` (plus
  `export const revalidate = n` for ISR). Inner `fetch` uncached unless opted in.
- **`16-cc`**: no per-file switch (`dynamic`/`revalidate` fail the build). A `GET` handler is prerendered (`○` in the
  build table) when it touches no request data, uncached I/O, or unstable values; to cache data, call a helper
  marked `'use cache'` + `cacheLife` + `cacheTag` (the directive is not allowed in the handler body). Reading
  `request`, `cookies()`, `headers()`, or `await connection()` makes it dynamic.
- Non-`GET` methods are never cached. No `fetch` memoization in handlers — two identical fetches are two requests.
- `updateTag`/`refresh` cannot be called from a handler; use `revalidateTag(tag, "max")`/`revalidatePath` (16) or
  one-argument `revalidateTag` (14/15).
- `output: "export"`: only `GET` handlers that read nothing from the request, with `dynamic = "force-static"`.

## What handlers are for

Browser code that needs a JSON endpoint, external clients, webhooks, file/stream responses. **Not** for Server
Components (call the data function directly) and not for mutations from your own forms (Server Actions). If an
action and a handler share a mutation, extract the shared function and call it from both.

## Webhooks

- Verify the signature on the **raw** body: `const payload = await request.text()` → verify → then parse. Never
  `request.json()` first.
- Shared-secret webhooks: secret in a header (never the query string), constant-time comparison with a length
  check, `POST` only.
- Respond `2xx` quickly; do slow work in `after()` (15.1+) or a queue; be idempotent on the event id; acknowledge
  unknown event types with 200; never log the payload or signature.
- Revalidation endpoints: `POST` + header secret → `revalidateTag(tag, "max")` (16) / `revalidateTag(tag)` (14/15).

## CORS and streaming

- Same-origin needs no CORS. Cross-origin: an explicit `OPTIONS` export, echo the `Origin` only if allow-listed,
  `Vary: Origin`, never `*` with credentials, same headers on the real response.
- Streaming: `new Response(readableStream, { headers: { "Content-Type": "text/event-stream; charset=utf-8",
  "Cache-Control": "no-cache, no-transform" } })`; pass `request.signal` to upstream fetches.

## BFF endpoints

Upstream base URL from one `server-only` module and a non-`NEXT_PUBLIC_` env var; allow-list paths and methods;
build outgoing headers from scratch (never forward the browser's `Cookie`/`Authorization`); attach the server-held
token; `redirect: "manual"`, `signal: AbortSignal.timeout(...)`, failure → 502; rebuild the response (never relay
upstream `Set-Cookie`). `rewrites` are pure pass-through — never for anything that needs auth.

## Pages Router API routes

`pages/api/**` — see `pages-router.md`. Never define the same path in `pages/api` and an App Router handler.

## Proxy (16) / middleware (14, 15)

- **16**: `proxy.ts` next to `app/` (root or `src/`), `export function proxy(request: NextRequest)` (or default
  export) + `export const config`. Node.js runtime only. Having both `proxy.ts` and `middleware.ts` fails the build;
  keep `middleware.ts` only where the project still depends on the Edge runtime. **14/15**: `middleware.ts`,
  `export function middleware`, Edge runtime by default (`runtime: "nodejs"` stable from 15.5). One file per
  project; split logic into imported modules.
- A constant `matcher` (analysed at build time) that excludes `api`, `_next/static`, `_next/image`, `favicon.ico`,
  extension-less metadata routes (`icon`, `apple-icon`), files with an extension, and prefetch requests when the
  logic doesn't need them.
- Good uses: locale redirects, A/B rewrites, **optimistic** auth redirects (cookie presence/signature only), per-request
  headers such as a CSP nonce. **Never** the only authorization layer (proxy-bypass CVEs exist; a matcher that
  skips a path also skips actions posted to it), no database calls, no slow remote calls (it runs on every request,
  including prefetches), no business logic, no shared module state with the app.
- Build URLs from `request.nextUrl.clone()` (keeps `basePath`). `NextResponse.next({ request: { headers } })`
  forwards modified request headers to the app (overwrite trusted headers, never merge);
  `NextResponse.redirect(url)` is 307. Static redirect pairs belong in `redirects` in `next.config`, not the proxy.
- `request.ip`/`request.geo` were removed in 15.
- Not available with `output: "export"`.
