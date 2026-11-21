# Tests

This skill **writes** tests and does not **run** them — `iru-nextjs-code-one-task-group` runs the unit suite,
the 80% coverage gate (unit tests only), `next build`, and the Playwright suite once for the whole group. Write
tests that pass, that hold the coverage gate up, and that don't depend on a browser unless they are e2e tests.

## Follow the project

Read an existing test near the code first and match its runner, location (beside the source vs `__tests__/`),
naming, globals-vs-imports style, and mocking habits. Step 1 already detected the runner:

- **Vitest**: `vitest.config.{mts,ts}` with `@vitejs/plugin-react`, `environment: "jsdom"`, a setup file. Import
  `describe`/`test`/`expect`/`vi` from `"vitest"` unless `globals: true`.
- **Jest via `next/jest`**: `jest.config.ts` wrapping its config with `nextJest({ dir: "./" })` (SWC transform,
  CSS/image/font mocks, `.env` loading). Globals are ambient. Files exercising `Request`/`Response` need the
  `/** @jest-environment node */` docblock first.
- **Playwright** (`playwright.config.ts`, `@playwright/test`) for end-to-end tests, under the config's `testDir`.

Only if the project has **no** unit runner and the task asks for tests, use this setup (verified on Next 16.4,
Vitest 5.0, Vite 8):

```ts
// vitest.config.mts — .mts keeps it ESM regardless of package.json "type"
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  resolve: { tsconfigPaths: true }, // Vite 8; older Vite: the vite-tsconfig-paths plugin
  test: { environment: "jsdom", include: ["src/**/*.test.{ts,tsx}"], setupFiles: ["./vitest.setup.ts"] },
});
```

```ts
// vitest.setup.ts
import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

vi.mock("server-only", () => ({})); // the real package throws outside a React Server environment
afterEach(() => cleanup());
```

Adding a runner adds dev dependencies — that is a decision the task must have asked for; otherwise report it as a
blocker. Known trap: Vitest 5 rejects the `@types/node@^20` that `create-next-app` scaffolds (`ERESOLVE`) — it needs
`@types/node` 22 or ≥24. Vitest doesn't load `.env.test`; call `loadEnvConfig(process.cwd())` from `@next/env` in
the setup file if tests need it.

## What to test with what

| Code | How |
|---|---|
| Utilities, data-layer functions, hooks | plain unit tests (mock the DB/SDK client, not your own logic) |
| Client Components, synchronous Server Components | React Testing Library in jsdom; query by role/label; `userEvent.setup()` |
| Async Server Component that is a **leaf** (no async children, no Suspense to exercise) | `render(await Page(props))` |
| Async Server Component with async children, streaming, or `'use cache'` behaviour | Playwright — or extract its logic into a function and unit-test that |
| Server Actions | call the function directly with `FormData` and mocks |
| Route Handlers | call the exported `GET`/`POST` directly |
| proxy / middleware | call the function; matcher via `next/experimental/testing/server` |
| Pages Router data functions and API routes | call them as functions (`pages-router.md`) |
| Full flows (form → action → revalidated page), redirects, rendering mode | Playwright against the production build |

**Keep async Server Components thin.** Put branching logic in a function or the data layer and unit-test it
there; a page that only awaits params, calls one data function, and renders is the case the group skill accepts as
"only reachable through e2e". A page with real branching that has no test is a coverage gap you created.

## Mocks you will need

```ts
// Client Components using routing hooks — otherwise "invariant expected app router to be mounted"
const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/todos",
  useSearchParams: () => new URLSearchParams("q=milk"),
}));

// Request APIs — real ones throw "cookies was called outside a request scope". 15+: async; 14: return the object.
const get = vi.fn();
vi.mock("next/headers", () => ({ cookies: vi.fn(async () => ({ get })), headers: vi.fn(async () => new Headers()) }));

// Cache APIs — real ones throw "Invariant: static generation store missing". Mock only what the module imports.
vi.mock("next/cache", () => ({ revalidatePath: vi.fn(), revalidateTag: vi.fn(), updateTag: vi.fn(), refresh: vi.fn() }));
```

- `vi.mock` is hoisted above imports: a factory referencing an outer `const fn = vi.fn()` must call it lazily
  (`getSession: () => getSession()`) or use `vi.hoisted`.
- A Client Component that imports a Server Action: mock the actions module (`vi.mock("./actions", ...)`).
- `beforeEach(() => vi.clearAllMocks())`. Jest: the same with `jest.mock`/`jest.fn`.
- Mock the session/auth helper, not `cookies()`, when testing code that sits above the data layer.

## Async Server Components

`render(<AsyncPage />)` renders an empty container with an "async Client Component" warning — never do that.
Call the component as a function and render the result:

```tsx
const ui = await TodosPage({ params: Promise.resolve({ id: "1" }), searchParams: Promise.resolve({}) }); // 14: plain objects
render(ui);
expect(screen.getByRole("heading", { level: 1, name: "To-dos" })).toBeInTheDocument();
```

Only the outermost component resolves: nested async children render nothing, Suspense and `'use cache'` are
invisible, request APIs must be mocked.

## Server Actions — always include the negative cases

```ts
const getSession = vi.fn();
vi.mock("@/lib/session", () => ({ getSession: () => getSession() }));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
import { revalidatePath } from "next/cache";
import { addTodo } from "./actions";

const form = (title: string): FormData => { const fd = new FormData(); fd.set("title", title); return fd; };

test("rejects anonymous callers without side effects", async () => {
  getSession.mockResolvedValue(null);
  expect(await addTodo({ status: "idle" }, form("x"))).toEqual({ status: "error", error: "Sign in required" });
  expect(revalidatePath).not.toHaveBeenCalled();
});
```

Cover: anonymous caller, wrong owner/role, invalid input (each asserting **no** write and **no** invalidation),
then the happy path asserting the write and the exact tag/path invalidated. `redirect()`/`notFound()` throw: mock
`next/navigation` and assert the call, or assert the real error's `digest` — verified on 16.4:
`NEXT_REDIRECT;replace;/login;307;` and `NEXT_HTTP_ERROR_FALLBACK;404` (14: `NEXT_NOT_FOUND`):

```ts
await expect(createTodo(fd)).rejects.toMatchObject({ digest: expect.stringMatching(/^NEXT_REDIRECT;/) });
```

A test expecting a redirect that gets a returned error object instead has found a `redirect()` swallowed by a
`try/catch`.

## Route Handlers

```ts
import { GET } from "./route";

const ctx = (id: string) => ({ params: Promise.resolve({ id }) }) as RouteContext<"/api/todos/[id]">; // 14: { params: { id } }

test("401 for anonymous callers", async () => {
  getSession.mockResolvedValue(null);
  expect((await GET(new Request("http://localhost/api/todos/1"), ctx("1"))).status).toBe(401);
});
```

Handlers reading `request.cookies`/`request.nextUrl` need `new NextRequest(url, { headers, method, body })` from
`next/server`. Test the status mapping (400/401/403/404/422/502) as well as the success body.

## proxy / middleware

```ts
import { NextRequest } from "next/server";
import { getRedirectUrl, unstable_doesMiddlewareMatch } from "next/experimental/testing/server";
import { config, proxy } from "@/proxy"; // 14/15: { config, middleware } from "@/middleware"

expect(unstable_doesMiddlewareMatch({ config, url: "/api/x" })).toBe(false);
expect(getRedirectUrl(proxy(new NextRequest("http://localhost/books")))).toBe("http://localhost/en/books");
```

The docs name `unstable_doesProxyMatch`, but 16.3.8 and 16.4.0 only export `unstable_doesMiddlewareMatch` — check the
installed module's exports. Available from 15.1.

## Playwright

- Tests in the config's `testDir` (commonly `e2e/`), named like their siblings. The `webServer` should run the
  **production** build (`npm run build && npm run start -- --port <port>`), not `next dev`.
- Locate by role and name (`getByRole("heading", { name })`), never bare `getByRole("alert")` (Next injects a route
  announcer with that role). Wait with `waitForURL` after navigation. API checks without a browser:
  `async ({ request }) => { expect((await request.get("/api/todos/1")).status()).toBe(401); }`.
- One e2e per user-visible flow the task adds (form submit → result, redirect, auth gate), not one per unit case.
- **UI contract**: Client Components also get Playwright component tests through stories and a standalone Vite
  gallery, and pages get screen tests against the production build — both capturing the `.ui-screenshots/` matrix
  (left-to-right/right-to-left × light/dark) with an axe check at the `.ui-quality.yml` level through the shared
  `support/ui-screenshots.ts` helper. jsdom tests stay behaviour-only. See `ui-implementation.md`.
- MSW in the test process cannot intercept the Next server's own fetches — make upstream URLs configurable via env
  and point `webServer.env` at a fake server.
- `16-cc` with `partialPrefetching`: `instant()` from `@next/playwright` asserts what is in the shell — only if the
  project already uses it.

## Environment and snapshots

`NODE_ENV=test` loads `.env.test` and never `.env.local`. Module-level `process.env` reads are captured at import
— set them first or use `vi.stubEnv` + `vi.resetModules()`. Snapshots only for small components, never whole pages.
