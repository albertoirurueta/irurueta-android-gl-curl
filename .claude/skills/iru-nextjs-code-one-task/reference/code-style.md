# Code style

This catalog's TypeScript agreements (the same ones `iru-typescript-code-one-task` applies to any npm/TypeScript
project, condensed here so this skill stays self-contained) plus what is specific to Next.js. Everything below is
a default for new code: **match the surrounding code**, and report a divergence instead of restyling a file the
task didn't ask you to restyle.

## TypeScript agreements (inherited)

- **Strict typing, no `any`.** Use `unknown` plus a type guard, a generic, or a union. No non-null assertions
  (`!`) in place of narrowing; no `as` to force unrelated shapes together (only after a validating guard). Prefer
  string-literal unions over `enum` unless the project already uses enums.
- **Discriminated unions over optional-field grab-bags** — `{ status: "idle" } | { status: "ok"; data } |
  { status: "error"; error }`. This is also the shape for Server Action results (`server-actions.md`).
- **`readonly` by default**: `readonly` props/fields, `readonly T[]`/`ReadonlyArray<T>` for inputs and outputs the
  caller must not mutate, `const` over `let`, return new objects instead of mutating.
- **Named exports for every module you create** — except where Next.js requires a default export (below).
- **ESM only**; group imports external → aliased internal → relative, unless the lint config orders them.
- **Naming**: `UpperCamelCase` types/components (no `I` prefix), `lowerCamelCase` functions/values, `use*` hooks,
  predicates read as booleans (`isOwner`), `SCREAMING_SNAKE_CASE` only for true global constants.
- **Errors**: throw `Error` subclasses with `.name` set and `{ cause }` when re-throwing; never swallow in a bare
  `catch`; validate at the boundary (props, action input, request body). No `console.log` left behind.
- **Async**: `async`/`await`; `Promise.all` for independent work; no floating promises (`void` + comment when a
  promise is deliberately not awaited); `AbortSignal` for cancellable work.
- **No new runtime dependency** unless the task asks for it — that's a blocker to report, not a decision to make.
  No dead or commented-out code, no `TODO`s for work the task covers, no magic numbers/strings.
- **TSDoc on every exported symbol** you add or change (`@param`, `@returns`, `@throws`). For a default-exported
  `page`/`layout`/`route` export, a one-line summary is enough — `iru-typescript-tsdoc` accepts that.

React conventions (inherited from the catalog's React agreements, apply to Client Components):

- Function components only; props interface `<Component>Props` with `readonly` fields, destructured in the
  signature; stable keys from data, never the index for dynamic lists.
- Rules of hooks and exhaustive deps — fix the dependency, never suppress the lint rule. `useCallback`/`useMemo`
  only with a reason (or not at all when the React Compiler is enabled — `reactCompiler: true` in `next.config`).
- State colocated as low as possible; derive instead of duplicating; `useReducer` once transitions depend on each
  other. Server data is not component state — in the App Router it comes from Server Components.
- Every interactive element has an accessible name — `testing.md` queries by role.

## The default-export exception

Next.js resolves these by file name and requires a **default export**: `page`, `layout`, `template`, `loading`,
`error`, `global-error`, `not-found`, `forbidden`, `unauthorized`, `default` (App Router); every file under
`pages/` plus `_app`/`_document`/`_error` (Pages Router); metadata image files (`opengraph-image`, `icon`, ...)
and `sitemap.ts`/`robots.ts`/`manifest.ts`. Use a default export there and only there; everything else —
components, helpers, actions, data modules — uses named exports. Route Handlers (`route.ts`) use **named**
method exports (`GET`, `POST`), `proxy.ts` exports `proxy` (16) and `middleware.ts` exports `middleware` (14/15).

## Project layout

- Detect, don't assume: `app/` or `src/app/` (a root `app/` wins over `src/app/`); `pages/` or `src/pages/`.
  `proxy.ts`/`middleware.ts`, `instrumentation.ts`, `instrumentation-client.ts` sit next to `app/` (inside `src/`
  when it exists). `public/`, `next.config.*`, `tsconfig.json`, `.env*` always at the project root.
- Follow the project's existing organisation (shared `components/`/`lib/` outside `app/`, or feature folders
  colocated inside `app/`). Inside `app/` only `page`/`route` (and metadata files) are public; anything else
  colocated there is private. Use `_folder` for a private folder, `(group)` for a route group.
- **Never put non-route files under `pages/`** — every file with a page extension there becomes a route.
- Server-only modules (data access, secrets) start with `import "server-only"` — see `security.md`.
- Use the alias the project's `tsconfig.json` `paths` already defines (`@/*` → `./*` or `./src/*`); never add a
  new alias for one task.

## Typed route props

- **15.5+ / 16**: use the generated global helpers — `PageProps<"/blog/[slug]">`, `LayoutProps<"/[locale]">`,
  `RouteContext<"/api/books/[id]">` — no import needed. The route literal is the URL pattern **without** route
  groups or `src/app`. They come from `next typegen` (or `next dev`/`next build`); a `TS2304: Cannot find name
  'PageProps'` on a clean checkout is a missing typegen run, not a bug in your code.
- **15.0–15.4**: hand-type `{ params: Promise<{ slug: string }>; searchParams: Promise<...> }`.
- **14**: `{ params: { slug: string }; searchParams: { [key: string]: string | string[] | undefined } }` — plain
  objects, synchronous.
- `typedRoutes` enabled (`typedRoutes: true`, or `experimental.typedRoutes` before 15.5): `href`/`router.push`
  take literal routes; cast runtime strings with `as Route` (`import type { Route } from "next"`).
- Never edit `next-env.d.ts`; keep the scaffold's `plugins: [{ name: "next" }]` and `.next/types` includes.

## `next.config`

- Exactly one `next.config.{ts,mjs,js}` (14 has no `.ts` support). Type it with `import type { NextConfig } from
  "next"`. Stable keys at the top level, experimental ones under `experimental`. Unknown keys only *warn* — read
  the build output after changing the config.
- A task that needs a config change states it; don't touch `cacheComponents`, `output`, `images`,
  `experimental.serverActions`, or headers as a side effect.
- **16**: Turbopack is the default for dev and build; a custom `webpack()` function without a `turbopack` key fails
  the build. `next lint` and the `eslint` config key are gone — lint is the ESLint CLI with `eslint.config.mjs`
  (`eslint-config-next/core-web-vitals` + `/typescript`) or Biome. Respect the rules it enforces:
  `@next/next/no-html-link-for-pages` (use `<Link>`), `@next/next/no-img-element` (use `next/image`).

## Styling — detect, then follow

- Tailwind v4 (`tailwindcss` ^4, `@import "tailwindcss"` in global CSS, theme in `@theme`, no
  `tailwind.config.js`) vs v3 (`tailwind.config.js`, `@tailwind` directives) — never mix. CSS Modules
  (`name.module.css` beside `name.tsx`, pure class selectors). Global CSS is imported only in the root layout (App)
  or `pages/_app` (Pages). Sass needs `sass` and `@use`. Runtime CSS-in-JS (Emotion, MUI, styled-components) only
  in Client Components with the library's registry — don't introduce it.
- Don't add a second styling approach for one task.

## `next/image`, `next/font`, `next/script`

- `next/image`: `alt` always (`""` if decorative); `width`/`height` for remote or `public/` paths (static imports
  infer them); `fill` needs a positioned parent and `sizes`; remote hosts must be in `images.remotePatterns`. LCP
  image: `preload` (16) / `priority` (≤15), one per page. `loader`/`onLoad`/`onError` only in Client Components.
- `next/font`: call at module scope once (a shared `fonts.ts`), prefer variable fonts with `variable: "--font-x"`
  applied on `<html>`.
- `next/script`: `beforeInteractive` only in the root layout, inline scripts need an `id`, event callbacks only in
  Client Components, pass `nonce` under a nonce CSP.

## Hydration-safe rendering (Client Components)

Server HTML and the first client render must match. Never branch on `typeof window`, read `localStorage`/
`window`/`navigator`, or render `Date.now()`/`Math.random()`/`new Date()`/locale-dependent formatting during render.
Fix in this order: make it deterministic (props from the server, `useId`, explicit locale and `timeZone` in
`Intl`) → move it to a Server Component → CSS media queries → `useSyncExternalStore` with a server snapshot →
render in an effect / `next/dynamic` with `ssr: false` (Client Components only). `suppressHydrationWarning` only
for genuinely unavoidable text (a clock) or `<html>` attributes set by a theme script — never for structure.
Invalid nesting (`<div>` in `<p>`, `<a>` in `<a>`) is also a mismatch.
