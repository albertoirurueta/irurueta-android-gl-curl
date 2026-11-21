# Data fetching and caching

Read **"Rules for every profile"**, then **only the section for the profile Step 1 detected**: `14`, `15`,
`16-prev`, or `16-cc`. The sections deliberately repeat little; they contradict each other on purpose, because the
framework's defaults do. Applying another profile's section is the most common way to write code that compiles
and then fails `next build` or silently changes what is cached. Pages Router data functions are in
`pages-router.md` — none of this applies to `pages/`.

## Rules for every profile

- **Fetch on the server, where the data is used.** Async Server Components (pages, layouts, leaf components,
  `generateMetadata`) call a `server-only` data module directly. Don't prop-drill fetched data through layers just
  to avoid a second call: identical `GET` `fetch` calls (same URL and options) are memoized within one render, and
  `React.cache(fn)` gives the same per-render dedup for ORM/SDK calls (pass primitives — arguments compare with
  `Object.is`). Memoization does not apply in Route Handlers or to `fetch` with an `AbortController` signal.
- **Never fetch your own Route Handlers from a Server Component** (`fetch("http://localhost/api/...")`). It adds a
  hop and fails `next build` when the route is prerendered. Call the data function the handler calls.
- **Parallel by default**: start independent reads together (`Promise.all`, or `Promise.allSettled` when parts are
  optional); keep only genuine dependencies sequential.
- **Stream slow parts**: await only what the shell needs; put each slow part in its own async component inside
  `<Suspense fallback={...}>` (or rely on `loading.tsx` for the whole segment). Suspense close to the slow data,
  not around the whole page.
- **Upstream calls**: base URL from a non-`NEXT_PUBLIC_` env var in one `server-only` module; validate/encode
  every dynamic value put into a URL; `signal: AbortSignal.timeout(ms)`; throw on non-ok (→ `error.tsx`), translate
  an upstream 404 to `notFound()`.
- **Client Components** read server data via props or `use(promise)` (promise created in a Server Component, not
  awaited, under `<Suspense>` + an error boundary). Never create the promise during a client render. Client-side
  libraries (SWR, TanStack Query) only for genuinely live data, and only if the project already uses one.
- **Per-user data never goes into a shared cache** (`force-cache` on a credentialed fetch, `unstable_cache`, or
  `'use cache'` without the user id in the key). The user id, never a token or email, is the key.
- **`next dev` lies about caching.** It renders on demand and never caches pages. The authoritative answer is the
  `next build` route table (`○` static, `●` SSG, `ƒ` dynamic, `◐` partial prerender) — the group skill reads it.
  State the mode you expect for each route you touched.

---

## Profile `14` (Next 13.4–14.x, App Router)

- **`fetch` is cached by default** (as `force-cache`). Opt out per call with `{ cache: "no-store" }` or
  `{ next: { revalidate: 0 } }`; time-based with `{ next: { revalidate: n } }`; tags with `{ next: { tags: [...] } }`.
- `GET` Route Handlers are cached unless they read the request or use a dynamic function.
- Request APIs are **synchronous**: `cookies()`, `headers()`, `params`, `searchParams` are plain values — no
  `await`. Reading any of them makes the route dynamic (`ƒ`).
- Non-`fetch` data: `unstable_cache(fn, keyParts, { tags, revalidate })` (14.0+). `unstable_noStore()` marks a
  component dynamic (there is no `connection()`).
- Route segment config: `export const dynamic`, `revalidate` (a literal number), `fetchCache` — as in the
  previous-model table under `16-prev`.
- Invalidation: `revalidatePath(path, type?)` and **one-argument** `revalidateTag(tag)` — no `updateTag`/`refresh`.
- Client router cache keeps dynamic pages ~30 s — a page can look fresh in 14 without being refetched.
- No Partial Prerendering, no `'use cache'`, no `cacheLife`/`cacheTag`.

## Profile `15` (Next 15.x)

- **`fetch` is not cached by default.** A route with no request-time API is still prerendered and its plain
  `fetch` runs once at build time (frozen into the page); with a request-time API it runs per request. Opt in with
  `{ cache: "force-cache" }` or `{ next: { revalidate: n, tags: [...] } }`. A codebase upgraded from 14 that
  relied on implicit caching needs explicit opt-ins — don't "fix" slowness any other way.
- `GET` Route Handlers are dynamic by default; opt in with `export const dynamic = "force-static"`.
- Request APIs are **async**: `await cookies()`, `await headers()`, `await params`, `await searchParams`,
  `await draftMode()`. Sync access still works with a warning — never write it in new code.
- `await connection()` (from `next/server`) marks "render per request from here"; `unstable_noStore()` is legacy.
- Non-`fetch` data: `unstable_cache(fn, keyParts, { tags, revalidate })`. `'use cache'` exists only behind
  experimental flags — don't introduce it unless the project already enabled them.
- Invalidation: `revalidatePath(path, "page" | "layout")`, one-argument `revalidateTag(tag)`.
- Route segment config and the client router cache: as in `16-prev` below (`staleTimes.dynamic` is 0 from 15).

## Profile `16-prev` (Next 16, `cacheComponents` absent or `false`)

"Dynamic where the code needs the request, static where it can; data cached only where you say so."

- **What makes a route dynamic (`ƒ`)**: `await cookies()`/`headers()`/`searchParams`/`draftMode()`,
  `await connection()`, a `fetch` with `cache: "no-store"` or `next.revalidate: 0`, `dynamic = "force-dynamic"`,
  `revalidate = 0`, or unlisted params with `dynamicParams` true. **One `no-store` fetch deep in the tree makes
  the whole route dynamic.** Otherwise the route is prerendered (`○`/`●`) and its fetches run at build time.
- Request APIs are **async only** — synchronous access was removed in 16 (type and runtime error).
- `fetch` options: none (not cached; runs at build if prerendered, per request otherwise), `cache: "force-cache"`
  (Data Cache, matched on URL/method/headers/body, only 200s stored), `next: { revalidate: false | 0 | n }` (lowest
  value in a route wins and lowers the route's interval), `next: { tags: [...] }` (≤128 tags of ≤256 chars).
  Never combine contradictory options (`revalidate` with `no-store`).
- Non-`fetch` data: `unstable_cache(fn, keyParts, { tags, revalidate })` — the arguments are part of the key;
  `keyParts` only for captured outer variables (otherwise two closures share one entry). It cannot read
  `cookies()`/`headers()` — read outside, pass the value in.
- Route segment config (`page`, `layout`, `route`):

  | Export | Values |
  |---|---|
  | `dynamic` | `"auto"` (default), `"force-dynamic"`, `"error"` (fail the build if anything is dynamic), `"force-static"` (request APIs return empty values) |
  | `revalidate` | `false` (default), `0`, or a **literal** number of seconds (`600`, not `60 * 10`); the lowest across layouts and page wins |
  | `fetchCache` | advanced per-segment override — don't mix it with per-call `cache` options (behaviour measured contradicting the docs on 16.3.8) |
  | `dynamicParams` | `true` (default) / `false` (unlisted params → 404) |
  | `runtime` | `"nodejs"` (default); `"edge"` deprecated |

- Invalidation (**two-argument `revalidateTag`** — the one-argument form is deprecated and behaves like
  `{ expire: 0 }`):

  | Call | Where | Effect |
  |---|---|---|
  | `updateTag(tag)` | Server Actions only | expire now; the next read waits — read-your-own-writes after a mutation |
  | `revalidateTag(tag, "max")` | Actions and Route Handlers | stale-while-revalidate |
  | `revalidateTag(tag, { expire: 0 })` | Actions and Route Handlers | never serve stale |
  | `revalidatePath(path, "page" \| "layout")` | Actions and Route Handlers | route entries via soft tags |
  | `refresh()` | Server Actions only | refresh the client router only; no server cache touched |

  None of them can be called from a Client Component or from the proxy. Any of them in a Server Action clears the
  whole client router cache.
- `cacheLife`/`cacheTag`/`'use cache'` **do not compile** without `cacheComponents` ("To use "use cache", please
  enable the feature flag `cacheComponents`"). `experimental.ppr`/`dynamicIO`/`useCache` were removed in 16.
- Client router cache: `staleTimes.dynamic` 0 s, `static` 5 min (still under `experimental.staleTimes`).

## Profile `16-cc` (Next 16, `cacheComponents: true`)

"Everything that needs request data is dynamic; you choose what to cache." Routes prerender a static shell and
stream the rest behind `<Suspense>` (Partial Prerendering, `◐`).

**The prerender rule**: every component either completes during the prerender or sits under a `<Suspense>`
boundary. Otherwise `next build` fails with `Route "/x": Next.js encountered uncached or runtime data during
prerendering` (`blocking-prerender-dynamic`; re-run `next build --debug-prerender` for the file and line).

| The component | Result | Do |
|---|---|---|
| Pure computation, module imports, sync reads | in the shell | nothing |
| Calls or is a `'use cache'` function (lifetime not too short) | cached result in the shell | pair with `cacheLife`, add `cacheTag` |
| Awaits `cookies()`, `headers()`, `searchParams`, unknown `params` | build error unless under `<Suspense>` | move the access into a leaf under `<Suspense>`, as deep as possible |
| Awaits an uncached `fetch`/DB/IO | build error unless under `<Suspense>` | `<Suspense>`, or cache it with `'use cache'` |
| Renders `Math.random()`, `new Date()`, `Date.now()`, `crypto.randomUUID()` | build error | `await connection()`/`await io()` (16.3, from `next/cache`) under `<Suspense>`, or `'use cache'`, or a Client Component |
| Client hook `useSearchParams()` (and `usePathname`/`useParams` under unknown params) | build error unless under `<Suspense>` | wrap the component reading the hook |

- **Push dynamic access down.** `await params`/`await cookies()` at the top of a page or layout makes everything
  below it wait and empties the shell. Pass the promise down and await it in a leaf under `<Suspense>`.
- **`'use cache'`** at the top of an `async` function, component, or file (then every export is cached, and must
  be async). Key = build id + function id + **serialized arguments and captured variables**. Arguments must be
  serializable (no class instances, functions, `URL`); `children`/slots/actions may pass through unread.
- **`cacheLife` in every `'use cache'` scope** (never at module scope): built-in profiles `default` (stale 5 min,
  revalidate 15 min, expire never), `seconds`, `minutes`, `hours`, `days`, `weeks`, `max`, an inline
  `{ stale, revalidate, expire }`, or a custom profile defined under `cacheLife` in `next.config`. A `revalidate` of
  0 or an `expire` under 5 minutes (only `seconds` among the presets) makes the scope a dynamic hole that needs
  `<Suspense>`. A short inner scope inside an outer scope with no explicit `cacheLife` fails the prerender.
- **`cacheTag(...)`** labels the entry (computable from data, ≤128 tags). Invalidate with the same API as
  `16-prev`: `updateTag` (actions, read-your-own-writes), `revalidateTag(tag, "max" | profile | { expire: 0 })`,
  `revalidatePath`, `refresh`.
- **Never read `cookies()`/`headers()`/`searchParams`/`connection()` inside `'use cache'`** (build or runtime
  error `next-request-in-use-cache`), and never pass an un-awaited request Promise into one (build hangs ~50 s then
  times out). Read outside, pass the plain value in — it becomes part of the key. Per-browser caching of
  request-dependent work: `'use cache: private'`. Cross-instance shared cache: `'use cache: remote'` with a
  configured `cacheHandlers` store — only if the project already has one.
- **Route segment config `dynamic`, `revalidate`, `fetchCache`, `dynamicParams`, `experimental_ppr`, and
  `runtime = "edge"` fail the build** (`Route segment config "revalidate" is not compatible with
  nextConfig.cacheComponents`). Replacements: `force-dynamic` → delete; `force-static`/`revalidate = n` →
  `'use cache'` + `cacheLife`; `unstable_cache` → a `'use cache'` function; `unstable_noStore` → delete or
  `connection()`; `dynamicParams = false` → `notFound()` when the param doesn't resolve; `generateStaticParams`
  must return at least one real param.
- `export const instant = false` on a page/layout allows a blocking (`ƒ`) route and silences the validation. It is
  an opt-out, not a fix — use it only if the task says the route may block, and report it.
- `fetch` options and `unstable_cache` still work as a separate Data Cache layer, but new code uses `'use cache'`.
- `GET` Route Handlers follow the page model: prerendered when they touch no request or uncached data; cache data
  through a `'use cache'` helper (not in the handler body).
- Navigation keeps recent routes mounted hidden (`<Activity>`): state that used to reset on navigation persists.
- `notFound()`/`redirect()` below a flushed boundary return 200 — check existence before the first suspending
  `await` when the status matters.
