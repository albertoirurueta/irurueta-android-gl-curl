# Routing and rendering (App Router)

## Files of a segment

A folder is a URL segment; only `page` or `route` (and metadata files) make it public — everything else
colocated in `app/` is private. Nesting inside one segment, outside in: `layout` > `template` > `error` >
`loading` > `not-found`/`forbidden`/`unauthorized` > `page` (or the child segment).

| File | Rules |
|---|---|
| `layout.tsx` | Server by default; props `children` (+ parallel slots) and `params` (`LayoutProps<"/route">` in 15.5+). Persists across navigation — not re-rendered, so **never an auth boundary**. Cannot read `searchParams` or the pathname (use client hooks in a leaf). Root layout renders `<html>` and `<body>`, no hand-written `<head>` tags (use metadata). |
| `template.tsx` | Like a layout but remounts per child segment/param value. |
| `loading.tsx` | `<Suspense>` around the page and nested segments. Makes the response stream — a later `notFound()`/`redirect()` keeps status 200. Doesn't cover its own segment's layout. |
| `error.tsx` | **Must start with `'use client'`.** Props `error` (`Error & { digest?: string }`) and `reset` (≤16.2; 16.2 added `unstable_retry`, 16.3 stable `retry` — use what the installed version has). Doesn't catch its own segment's layout errors; only `global-error.tsx` (own `<html><body>`) covers the root layout. |
| `not-found.tsx` | Rendered by `notFound()` from the nearest segment above; the root one also serves unmatched URLs. |
| `forbidden.tsx` / `unauthorized.tsx` | Need `experimental.authInterrupts` (15.1+); otherwise don't use `forbidden()`/`unauthorized()`. |
| `default.tsx` | Fallback for a parallel `@slot`. **16: required for every slot** (missing one → 404s). |
| `route.ts` | See `route-handlers.md`; never beside `page.tsx`. |

## Dynamic segments and groups

| Folder | Matches | `params` value |
|---|---|---|
| `[id]` | one segment | `{ id: string }` |
| `[...slug]` | one or more | `{ slug: string[] }` |
| `[[...slug]]` | zero or more | `{ slug?: string[] }` (`undefined` on the bare path) |

- `(group)` folders organize and give per-group layouts without a URL segment (the route literal for
  `PageProps` omits them); `_folder` is private; `@slot` is a parallel route; `(.)`/`(..)`/`(...)` intercept.
- Params are always strings: validate and narrow once (schema/regex), `notFound()` on bad input, never
  interpolate raw params into paths, queries, or URLs. In pages (16.3) params arrive percent-encoded — decode once.
- Reading params: `const { id } = await params` (15+), plain `params.id` (14); `use(params)` in a Client Component
  page; `useParams()` anywhere in the client tree.
- Build errors to avoid: two dynamic siblings (`[id]` and `[slug]`), `page.tsx` beside `[[...slug]]`, `route.ts`
  beside `page.tsx`, the same URL in `app/` and `pages/`, two groups resolving to one path.

## `generateStaticParams`

- Exported from a page, layout, or route; returns `[{ id: "1" }]` (catch-all `{ slug: ["a", "b"] }`). Values must be
  strings. Runs at build time. A Client Component page can't export it — keep `page.tsx` a thin Server Component
  that exports it and passes `params` down.
- **`14`, `15`, `16-prev`**: unlisted params render on first request and are cached (`dynamicParams = true`,
  default) or 404 (`export const dynamicParams = false`). Returning `[]` builds nothing ahead.
- **`16-cc`**: must return **at least one** param; `dynamicParams` is a build error; unlisted params get the
  static shell plus streamed content — reject unknown ones with `notFound()`. Read `params` inside `<Suspense>`.

## Rendering strategy by profile

Pick the most static strategy that is correct; never put per-user output into a prerendered/ISR/cached result.
The `next build` table is the proof: `○` Static, `●` SSG (`generateStaticParams`/`getStaticProps`, children
indented), `ƒ` Dynamic, `◐` Partial Prerender (`16-cc` only). `Revalidate`/`Expire` columns appear for ISR and
`cacheLife` lifetimes.

| Intent | `14` | `15` / `16-prev` | `16-cc` |
|---|---|---|---|
| Static (`○`/`●`) | default; `fetch` cached by default | default as long as nothing reads the request; `fetch` runs at build | default when nothing reads the request or uncached data and no unstable values |
| ISR (time) | `export const revalidate = n` or `next.revalidate` | same, plus `unstable_cache({ revalidate })` | `'use cache'` + `cacheLife(...)` (shows as `Revalidate`/`Expire`) |
| ISR (on demand) | `revalidatePath`/`revalidateTag(tag)` | same; 16: `revalidateTag(tag, "max")`, `updateTag` | `cacheTag` + the same calls |
| SSR (`ƒ`) | request APIs, `no-store`, `force-dynamic` | `await` request APIs, `await connection()`, `force-dynamic` — the **whole route** | not a strategy of its own: request data under `<Suspense>` gives `◐`; outside it the build fails (`instant = false` → `ƒ`) |
| Streaming | `loading.tsx`/`<Suspense>` in a dynamic route | same | always: shell + holes |
| PPR (`◐`) | experimental preview only | 15 canary flags only; **removed in 16-prev** | the default model |

Route-mode changes the group skill will flag, so make them only when the task asks: a page or a **layout** that
starts reading `cookies()`/`headers()`/`searchParams` (a layout read makes every route below it dynamic in the
previous model), a new `no-store` fetch, a `force-dynamic`/`revalidate` export, or (under `16-cc`) moving a
request read outside `<Suspense>`.

## Route segment config

| Export | `14`/`15`/`16-prev` | `16-cc` |
|---|---|---|
| `dynamic`, `revalidate`, `fetchCache`, `dynamicParams` | valid (`revalidate` must be a literal) | **build error** |
| `runtime` | `"nodejs"` default, `"edge"` deprecated | `"edge"` build error |
| `maxDuration` | valid | valid |
| `instant` | build error | `false` = allow a blocking route (opt-out of validation) |
| `experimental_ppr` | 15 canary only, error in 16 | build error |

Full caching semantics are in `data-fetching-and-caching.md`.

## Navigation

- `<Link href>` from `next/link` for internal navigation (no child `<a>`, no `legacyBehavior`); plain `<a>` for
  Route Handlers and files. `prefetch` defaults are right — don't set it without a reason.
- `useRouter()` from **`next/navigation`** (Client Components only): `push`, `replace`, `refresh()`, `back`,
  `prefetch`. `usePathname`, `useSearchParams` (wrap the reader in `<Suspense>` under `16-cc`), `useParams`,
  `useSelectedLayoutSegment(s)`. `next/router` is Pages Router only.
- `redirect(path)` (307; 303 after a Server Action without JS), `permanentRedirect(path)` (308), `notFound()` —
  all throw (`never`): call them outside `try/catch` (or `unstable_rethrow` first), never `return redirect()`. In a
  Client Component only during render; in event handlers use `router.push`. Static redirect pairs go in
  `next.config` `redirects`.

## Static export (`output: "export"`)

When `next.config` sets `output: "export"`, the build writes plain files to `out/` and these become **build errors
or silently missing features** — don't write them:

- Request APIs (`cookies()`, `headers()`, `searchParams` in pages), Server Actions, `getServerSideProps`,
  Route Handlers other than request-free `GET` with `dynamic = "force-static"` (that includes `sitemap.ts`/
  `robots.ts`), proxy/middleware, ISR/`revalidate`, Draft Mode, intercepting routes, the i18n config.
- Dynamic segments need `generateStaticParams` (and `dynamicParams = false`); unlisted paths simply don't exist.
- `next.config` `redirects`/`rewrites`/`headers` are ignored; the default image loader emits URLs that 404 — use
  `images: { unoptimized: true }` or a custom loader.
- Every route in the build table must be `○` or `●`.
