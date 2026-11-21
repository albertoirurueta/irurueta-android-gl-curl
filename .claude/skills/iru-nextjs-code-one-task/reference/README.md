# Next.js development reference

Rules for implementing one task in an existing Next.js project — App Router or Pages Router, Next 14, 15, or 16,
with or without Cache Components. These files exist so `iru-nextjs-code-one-task` loads **only** what the task in
front of it needs, instead of carrying every Next.js rule in context on every task.

Every file is written against the **version/model profile** Step 1 of the skill detected — `14`, `15`,
`16-prev` (16 without `cacheComponents`), or `16-cc` (16 with `cacheComponents: true`) — and marks
version-specific rules with those tags. A rule tagged for another profile does not apply to this project, however
good it looks.

## Read this much, and no more

| Read | When |
|---|---|
| `code-style.md` | **Always.** Inherits the catalog's TypeScript agreements (strict typing, no `any`, `readonly`, discriminated unions, named exports, errors, async) and adds what is Next-specific: file conventions and the default-export exception, project layout, aliases, typed route props, `next.config`, styling, `next/image`/`next/font`/`next/script`. |
| `server-and-client-components.md` | **App Router, always.** Which side of the boundary the code lives on decides everything else. Skip for a Pages-Router-only task. |
| `data-fetching-and-caching.md` | The task reads data in a page/layout/component, adds or changes caching, revalidation, `fetch` options, `'use cache'`, `cacheLife`/`cacheTag`, or anything that changes a route's static/dynamic behaviour. Read **only the section for the detected profile** plus the shared rules at the top. |
| `server-actions.md` | The task adds or changes a Server Action, a form that posts to one, `useActionState`/`useFormStatus`/`useOptimistic`, or post-mutation revalidation. |
| `route-handlers.md` | The task adds or changes an App Router `route.ts`, a webhook, a BFF endpoint, or `proxy.ts`/`middleware.ts`. |
| `routing-and-rendering.md` | The task adds a route, segment, layout, `loading`/`error`/`not-found` file, dynamic segment, `generateStaticParams`, route segment config, navigation, or touches a project with `output: 'export'`. |
| `metadata-and-seo.md` | The task adds or changes `metadata`/`generateMetadata`/`viewport`, OG images, `sitemap.ts`/`robots.ts`, JSON-LD. |
| `pages-router.md` | The code the task touches lives under `pages/` (Pages Router). Replaces `server-and-client-components.md` for those files. |
| `testing.md` | The task writes or updates tests — nearly every task. |
| `security.md` | The task touches a Server Action, Route Handler, proxy, session/auth, environment variables, props passed to a Client Component from server data, headers/CSP, or anything user-supplied. In practice: most tasks that are not pure UI. |
| `ui-contract.md` + `ui-implementation.md` | The task adds or changes a page, layout, component, list or other UI. The contract is the catalog-wide rule set (verbatim in every UI coding skill); `ui-implementation.md` maps it onto Server/Client Components, server pagination vs. a client virtualizer, the component gallery and Playwright screenshot/accessibility tests. `ui-screenshots.ts` is the helper to copy when the project has none. |

A one-line change inside an existing Client Component is `code-style.md` + `server-and-client-components.md` +
`testing.md`. Don't read the rest speculatively.

If the project ships `AGENTS.md`/`CLAUDE.md` with the `create-next-app` block pointing at
`node_modules/next/dist/docs/`, those bundled docs match the installed version exactly: when a rule here and the
bundled page for the API you are using disagree, the bundled page wins for that API — say so in the report.

## The three rules that override everything else

1. **Apply the detected profile's rules, and only those.** The same code means different things across 14, 15,
   16 and Cache Components (`fetch` cached by default in 14 and not in 15+; `params` sync in 14 and a Promise in
   15+; `export const revalidate` valid in the previous model and a build error under Cache Components). Mixing
   idioms from two profiles produces code that type-checks and then fails `next build` or silently changes
   caching. Never "modernize" toward a newer profile the project hasn't adopted, and never flip
   `cacheComponents` as a side effect of a task.
2. **The server/client boundary is a security boundary.** Every Server Action and Route Handler is a public
   endpoint: authenticate, authorize, and validate inside it. Everything passed to a Client Component — props,
   action return values, `NEXT_PUBLIC_*` variables — is public. See `security.md`.
3. **A task implements what the task says — including the route's rendering mode.** Don't turn a static route
   dynamic (a stray `cookies()` at the top of a page, a `no-store` fetch) or a dynamic route static
   (`force-static`, `'use cache'` over per-user data) unless the task asks for it. State the rendering mode you
   expect for every route you touched (`○`/`●`/`ƒ`/`◐`) in the report: the group skill compares it against the
   `next build` route table.

## Sources

Compiled from the user's own reference docs (written against Next.js 16.3.8 / React 19.3) and cross-checked
against a `create-next-app` 16.4.0 scaffold in both caching models:

- irurueta docs: `web/nextjs/caching.adoc`, `web/nextjs/partial-prerendering-and-cache-components.adoc`,
  `web/nextjs/data-fetching.adoc`, `web/nextjs/server-and-client-components.adoc`,
  `web/nextjs/server-actions-and-mutations.adoc`, `web/nextjs/route-handlers-and-backend-for-frontend.adoc`,
  `web/nextjs/redirects-rewrites-and-proxy.adoc`, `web/nextjs/routing-fundamentals.adoc`,
  `web/nextjs/layouts-templates-and-special-files.adoc`, `web/nextjs/dynamic-routes-and-route-groups.adoc`,
  `web/nextjs/static-site-generation.adoc`, `web/nextjs/incremental-static-regeneration.adoc`,
  `web/nextjs/metadata-and-seo.adoc`, `web/nextjs/pages-router.adoc`, `web/nextjs/testing.adoc`,
  `web/nextjs/data-security-and-csp.adoc`, `web/nextjs/authentication-and-authorization.adoc`,
  `web/nextjs/configuration-typescript-and-environment-variables.adoc`, `web/nextjs/project-structure.adoc`,
  `web/nextjs/styling.adoc`, `web/nextjs/hydration.adoc`, `web/nextjs/whats-changed-and-upgrading.adoc`.
- This catalog's TypeScript agreements: `iru-typescript-code-one-task/reference/code-style.md` and `react.md`.
- Next.js docs: <https://nextjs.org/docs/app/getting-started/caching>,
  <https://nextjs.org/docs/app/guides/caching-without-cache-components>,
  <https://nextjs.org/docs/app/guides/migrating-to-cache-components>,
  <https://nextjs.org/docs/app/api-reference/directives/use-cache>,
  <https://nextjs.org/docs/app/api-reference/file-conventions/route-segment-config>,
  <https://nextjs.org/docs/app/guides/upgrading/version-15>, <https://nextjs.org/docs/app/guides/upgrading/version-16>,
  <https://nextjs.org/docs/app/guides/data-security>, <https://nextjs.org/docs/app/guides/testing>.
- UI contract sources (Playwright component testing and accessibility testing, TanStack Virtual, axe-core): listed in
  `ui-implementation.md`.
