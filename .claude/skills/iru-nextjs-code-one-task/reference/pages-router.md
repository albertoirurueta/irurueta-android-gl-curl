# Pages Router

Read this file **only** when the code the task touches lives under `pages/` (or `src/pages/`). In a project with
both routers, files under `app/` follow the App Router files instead — a route exists in one router or the other,
never both. Cache Components, `'use cache'`, Server Components, and Server Actions don't exist here.

## Conventions

- Every file with a page extension under `pages/` is a route: `pages/index.tsx` → `/`,
  `pages/books/[id].tsx` → `/books/:id`, `[...slug].tsx`, `[[...slug]].tsx`, `pages/api/**` → API routes.
  **Keep components, helpers, and tests out of `pages/`** (unless `pageExtensions` is configured to allow it).
- Reserved: `_app.tsx` (persistent layout, providers, the **only** place for global CSS imports), `_document.tsx`
  (server-only HTML shell with `Html`/`Head`/`Main`/`NextScript` from `next/document`; no handlers, no data
  functions), `404.tsx`, `500.tsx`, `_error.tsx`. Avoid `App.getInitialProps` — it disables static optimization
  site-wide.
- Per-page title/meta with `next/head` (`<title>` takes one string — use a template literal). Per-page layouts via
  `Page.getLayout`.
- Every page component hydrates on the client. Data functions run on the server; their results are serialized
  into `__NEXT_DATA__` — **public**, JSON-serializable only (no `Date`/`Map`/class instances): return DTOs, never
  secrets or whole rows.
- `params` and `query` in data functions are **synchronous** in every version, including 16. Their values are
  `string | string[] | undefined` — normalize and validate.

## Data functions

| Function | Build table | Use |
|---|---|---|
| none | `○` | Automatic Static Optimization; `router.query` is empty until `router.isReady` |
| `getStaticProps` | `●` | build-time data; `revalidate: n` for ISR; returns `{ props }`, `{ notFound: true }`, or `{ redirect: { destination, permanent } }` |
| `getStaticPaths` | — | required for a dynamic SSG page: `{ paths, fallback }` — `false` (unlisted → 404), `"blocking"` (SSR first, then cached), `true` (fallback render: guard `router.isFallback`) |
| `getServerSideProps` | `ƒ` | per-request data; context `req`, `res`, `query`, `params`, `resolvedUrl`; `res.setHeader("Cache-Control", ...)` only for anonymous output |
| `getInitialProps` | `ƒ` | legacy — don't add |

Type them with `satisfies` so the page props are inferred:

```tsx
import type { GetStaticPaths, GetStaticProps, InferGetStaticPropsType } from "next";

export const getStaticPaths = (async () => ({
  paths: [{ params: { id: "1" } }],
  fallback: "blocking",
})) satisfies GetStaticPaths;

export const getStaticProps = (async ({ params }) => {
  const book = params === undefined ? undefined : await getBook(params.id);
  if (book === undefined) return { notFound: true, revalidate: 60 };
  return { props: { book: toBookView(book) }, revalidate: 60 };
}) satisfies GetStaticProps<{ book: BookView }, { id: string }>;

export default function BookPage({ book }: InferGetStaticPropsType<typeof getStaticProps>) {
  return <h1>{book.title}</h1>;
}
```

Same pattern with `GetServerSideProps<P>` + `InferGetServerSidePropsType<typeof getServerSideProps>`. Data functions
may import server-only code directly (it is tree-shaken from the client bundle), but don't add `import
"server-only"` to modules shared with `pages/` — it breaks the Pages Router build.

## API routes

```ts
import type { NextApiRequest, NextApiResponse } from "next";

export default async function handler(req: NextApiRequest, res: NextApiResponse<TodoView | ApiError>) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "method not allowed" });
  }
  // authenticate + authorize here; req.body is `any` — validate it with a schema
}
```

- Public endpoints: authenticate, authorize, validate inside. `req.body` is parsed (1 MB default `sizeLimit`); a
  webhook needing the raw body sets `export const config = { api: { bodyParser: false } }` and reads the stream.
- On-demand ISR: `await res.revalidate("/books/1")` in a `POST` API route protected by a header secret (eager
  regeneration; wrap in `try` → 500 keeps the last good page). Pass the real path.
- Not available with `output: "export"`.

## Routing hooks

- `useRouter()` from **`next/router`**: `pathname` (the file path pattern), `query`, `asPath`, `isReady`,
  `isFallback`, `push(url, as?, { shallow, scroll })`, `replace`, `events` (subscribe in an effect, unsubscribe in
  cleanup). `next/router` throws "NextRouter was not mounted" under `app/`; a component shared by both routers uses
  `next/compat/router` (returns `null` in `app/`) or `next/navigation` hooks (typed nullable once `pages/` exists).
- `<Link>` from `next/link` with no child `<a>`.

## Testing

`getServerSideProps`/`getStaticProps` are plain async functions — call them with a minimal typed context and
assert the returned `{ props }`/`{ notFound }`/`{ redirect }`, then render the page with those props. API routes:
call the handler with a fake `req` and a chainable `res` mock and assert status/JSON (including 405 + `Allow`).
Components using `next/router` need it mocked. Details in `testing.md`.
