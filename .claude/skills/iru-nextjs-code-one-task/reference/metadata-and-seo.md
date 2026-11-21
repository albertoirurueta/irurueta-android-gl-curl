# Metadata and SEO (App Router)

Pages Router pages use `next/head` instead — see `pages-router.md`. In the App Router never use `next/head`,
`document.title`, or a client effect for tags.

## Static and generated metadata

- `export const metadata: Metadata = { ... }` (static) or `export async function generateMetadata(...)`
  (data-dependent) from a `layout.tsx` or `page.tsx` — **Server Components only** (exporting from a
  `'use client'` file fails the build), never both in one segment.
- Root layout: `metadataBase: new URL(siteUrl)` (else OG URLs resolve to `localhost` with a build warning),
  `title: { default: "Site", template: "%s | Site" }` (the template applies to **child** segments only;
  `title.absolute` ignores it), a default description, `openGraph.siteName`.
- `generateMetadata({ params, searchParams }, parent)`: `await params` in 15+ (plain object in 14). For a missing
  entity return `{ title: "Not found", robots: { index: false } }` (or call `notFound()`).
- Merging is **shallow per top-level key**: a page's `openGraph` replaces the layout's entire `openGraph` — spread a
  shared object when you need both.
- Share the lookup between the page and `generateMetadata`: identical `GET` fetches are memoized; wrap ORM/SDK
  calls in `React.cache` (or `'use cache'` under `16-cc`).
- `viewport`/`themeColor`/`colorScheme` belong in `export const viewport: Viewport` or `generateViewport`, not in
  `metadata`.

## Profile notes

- **15.2+**: metadata of dynamic pages streams after the shell; HTML-limited bots get it blocking.
- **`16-cc`**: a `generateMetadata` (or `generateViewport`) that reads runtime or uncached data on an otherwise
  static page **fails the build**. Fix with `'use cache'` inside it (return serializable values — no `URL`
  instances) or, if the page is genuinely dynamic, a request-reading component under `<Suspense>` on the page.
  `generateViewport` is never streamed: runtime data there blocks the page.
- **16**: `params` in metadata image files (`opengraph-image`, `icon`) and the `sitemap` `id` are Promises.

## File conventions

`app/favicon.ico` (root only), `icon.*`/`apple-icon.*`, `opengraph-image.tsx`/`twitter-image.tsx` (exports `alt`,
`size`, `contentType`; returns `ImageResponse` from `next/og`, flexbox only, never private data), `sitemap.ts`
(`MetadataRoute.Sitemap`; `generateSitemaps` beyond 50,000 URLs), `robots.ts` (`MetadataRoute.Robots`, root only),
`manifest.ts` (root only). File-based metadata overrides the object. Dynamic image files under a dynamic segment
need their own `generateStaticParams` or they render per request. Exclude these routes from the proxy matcher.

## Structured data and indexing

- JSON-LD as a plain `<script type="application/ld+json" dangerouslySetInnerHTML={{ __html:
  JSON.stringify(data).replace(/</g, "\\u003c") }} />` in the page (not `next/script`), built from the same server
  data as the visible content.
- Canonical URLs via `alternates.canonical`; per-locale `alternates.languages` reciprocal, with `x-default`.
- `robots.txt` blocks crawling, not indexing — use `robots: { index: false }` for pages that must not be indexed.
- `notFound()` below a flushed `<Suspense>` boundary returns 200 — rely on the `noindex` it adds, or check existence
  before streaming when the status matters.
