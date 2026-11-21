# Server and Client Components (App Router)

The App Router only. Pages Router components are all client-hydrated — see `pages-router.md`.

## The model

- Every component under `app/` is a **Server Component** unless it is in the client module graph — i.e. its file,
  or a file that imports it from client code, starts with `'use client'`.
- `'use client'` marks a **module-graph entry point**, not a single component. It must be the first statement
  (before imports). Everything that file imports is bundled for the browser. Put it only on files a Server
  Component imports; files imported only from other client files need no directive.
- A Client Component still renders once on the server for the HTML. It is "client" because its code also ships to
  the browser and hydrates.

## Which side

| Needs | Side |
|---|---|
| Data reads, DB/filesystem, secrets, server-only env vars, heavy libraries, SEO-relevant content | Server (default) |
| `useState`/`useReducer`/`useEffect`/`useLayoutEffect`, context, event handlers, browser APIs, custom hooks using them | Client |
| Native behaviour (`<details>`, `<form action={serverAction}>`, `<video controls>`) | Server is enough |

- **Push `'use client'` to the smallest interactive leaf** — a button, a form, a toggle — never onto a page, a
  layout, or the root layout "for convenience". A directive high in the tree ships the whole subtree to the
  browser and drops its server-only capabilities.
- Client Components cannot be `async`. To consume server data in one, pass it as props, or pass a **promise**
  created (not awaited) in the Server Component and read it with `use(promise)` under `<Suspense>`.
- **UI contract mapping** (`ui-implementation.md`): the page is the screen and owns data; interactive components are
  Client Components whose props are serializable UI state plus callbacks wired *inside the client tree* (or Server
  Actions); presentational markup with no interactivity stays a Server Component. Long lists are paginated on the
  server (`searchParams`/cursor) or virtualized in one Client Component — never every row rendered.
- Server Components cannot use hooks, context, or event handlers.

## Crossing the boundary

- **Code** crosses only server → client via imports. A Client Component can never import a Server Component (it
  would join the client graph and fail on `server-only`). To render server output inside a Client Component, pass
  it as `children` or another `ReactNode` prop — the server owns the JSX, the client only places it.
- **Data** crosses via props and must be serializable: primitives, plain objects/arrays, `Date`, `Map`, `Set`,
  typed arrays, Promises, JSX, and Server Actions. **Not**: ordinary functions (event handlers), class instances
  (ORM entities, domain objects), symbols. TypeScript doesn't catch a function prop — the failure appears at
  prerender time ("Event handlers cannot be passed to Client Component props").
- **Everything passed as a prop is public** — serialized into the HTML/RSC payload. Pass the fields the component
  needs (`bookId`, `title`), never a whole row. Hidden by the UI is not private. See `security.md`.
- `import type { X } from "@/lib/server-module"` in a Client Component is fine — type imports are erased.

## Providers, third-party components, compound components

- Context: a `'use client'` provider that takes `children`, rendered from a Server Component as deep as possible
  (wrap `{children}`, not `<html>`). Its value is per-browser and public.
- A third-party component that uses hooks but ships no `'use client'` fails in a Server Component
  ("useState is not a function"). Wrap it in a one-line client re-export file and import the wrapper.
- `Menu.Item`-style static subcomponents don't survive the boundary (client references) — export them as named
  exports.

## `'use server'` is not the opposite of `'use client'`

`'use server'` marks **functions** callable from the client (Server Actions), not components. See
`server-actions.md`. Client Components can't define inline actions — they import them from a `'use server'` file
or receive them as props.

## `server-only` / `client-only`

- `import "server-only"` at the top of every module with DB access, secrets, secret `process.env` reads, or
  `next/headers` usage — the build then fails if a Client Component imports it. It guards **modules**, not values:
  a Server Component can still leak a secret by passing it as a prop.
- `import "client-only"` for modules that touch `window` at import time.
- `server-only` breaks Pages Router modules — don't add it to code shared with `pages/`.

## `next/dynamic`

`next/dynamic(() => import(...), { ssr: false })` only inside a Client Component (an error in Server Components
from 15). Prefer it to `typeof window` checks for browser-only widgets.

## Symptom → fix

| Symptom | Fix |
|---|---|
| "You're importing a module that depends on `useState` into a React Server Component module" | Move the stateful part into a `'use client'` leaf. |
| "Only plain objects, and a few built-ins, can be passed to Client Components" | Map the value to a plain DTO. |
| "Event handlers cannot be passed to Client Component props" | Move the handler into the Client Component, or pass a Server Action. |
| "'server-only' cannot be imported from a Client Component module" | Read the import trace; the client file imports a server module — move the read to a Server Component and pass the result. |
| Bundle exploded / whole page became client | Move the directive down; pass server output as `children`. |
| Hydration mismatch | See "Hydration-safe rendering" in `code-style.md`. |
