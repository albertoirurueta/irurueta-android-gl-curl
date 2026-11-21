# UI contract on Next.js

How `ui-contract.md` maps onto a Next.js project (App Router first; Pages Router notes at the end). Read the
contract first; this file only says *how*. Everything in `server-and-client-components.md` still decides which side
a component lives on — the contract never moves `'use client'` up the tree.

## Screens, components, composition

| Contract term | Next.js |
|---|---|
| Screen | A route's `page.tsx` (Server Component): reads data through the data layer, maps rows to DTOs, renders regions, passes props down. `loading.tsx`/`error.tsx`/`not-found.tsx` are its loading/error/empty states. |
| Region | A layout (`layout.tsx`), a parallel-route slot, or a Server Component section of the page. |
| Presentational component | A **Server Component** when it has no interactivity (cards, headings, static lists of a page); a **Client Component** (`'use client'`, smallest leaf) when it needs state, effects, event handlers or browser APIs. |
| Event | Inside the client tree: typed callbacks (`onSelect(orderId)`) between Client Components. Crossing to the server: a Server Action passed as a prop or imported from a `'use server'` module — never an ordinary function prop from a Server Component. |
| Navigation | File-based routes plus `<Link>`/`useRouter()` in one place per flow; components request navigation through a callback or a `<Link href>` prop the page computes. |

- Interactive components are **Client Components fed by Server Components**: the page passes serializable UI state
  (plain DTOs, ids, formatted strings, a promise for `use()` under `<Suspense>`) — never whole rows, class instances
  or functions. Server output reaches a Client Component as `children`/`ReactNode` slots (composition, not imports).
- Two-way binding only for form fields: controlled inputs (`value` + `onValueChange`) or uncontrolled fields posted
  to a Server Action with `useActionState`.
- Derived values (formatted dates, totals, flags) are computed on the server or in the data layer, not in JSX.
- No hard-coded user-visible strings when the project localizes (its i18n library or dictionary per locale);
  `<html lang dir>` comes from the locale in the root layout.

## Collections

Choose by where the list lives:

- **Server pagination (default for page-level lists)** — the page reads `searchParams` (`?page=`/`?cursor=`; a
  Promise on 15+), fetches one page from the data layer, renders one item component per row (a Server Component
  item is fine) and a `<Link>` to the next/previous page (or a "Load more" Client Component calling a Server Action
  that returns the next page's DTOs). Stable `key={item.id}`. The route's rendering mode follows from reading
  `searchParams` — state it in the report.
- **Client virtualization (long interactive lists, infinite scroll, pickers)** — one `'use client'` collection
  component receiving the first page as props, `@tanstack/react-virtual` (or the virtualizer the project already
  uses — a new dependency only if the task asked for it), one memoized item component, keys from ids, and more pages
  through a Server Action or a Route Handler:

```tsx
'use client'
export function OrderList({ items, hasMore, loadMore }: OrderListProps): JSX.Element {
  const [rows, setRows] = useState(items)           // seeded by the page; selection/scroll keyed by id
  const [pending, startTransition] = useTransition()
  const scrollRef = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({ count: rows.length, getScrollElement: () => scrollRef.current,
    estimateSize: () => 48, getItemKey: (index) => rows[index].id, overscan: 8 })
  const lastIndex = virtualizer.getVirtualItems().at(-1)?.index ?? -1
  useEffect(() => {
    if (hasMore && !pending && lastIndex >= rows.length - 5)
      startTransition(async () => {
        const next = await loadMore(rows.at(-1)?.id)  // Server Action: authenticates, returns DTOs
        setRows((current) => [...current, ...next])
      })
  }, [hasMore, pending, lastIndex, rows, loadMore])
  return (
    <div ref={scrollRef} className={styles.viewport} aria-busy={pending}>
      <ul role="list" style={{ position: 'relative', blockSize: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((row) => (
          <li key={row.key} data-index={row.index} ref={virtualizer.measureElement}
              aria-setsize={rows.length} aria-posinset={row.index + 1}
              style={{ position: 'absolute', insetInlineStart: 0, inlineSize: '100%',
                       transform: `translateY(${row.start}px)` }}>
            <OrderRow row={rows[row.index]} />
          </li>
        ))}
      </ul>
    </div>
  )
}
```

  The page renders `<OrderList items={firstPage} hasMore={…} loadMore={loadMoreOrders} />` with `loadMoreOrders` a
  Server Action. Horizontal lists: `horizontal: true` plus `isRtl` from the document direction.
- Never render every row of an unbounded query on the server or the client.

## UI tests

| Level | What it renders | How |
|---|---|---|
| Behaviour | Client Components, hooks, synchronous Server Components, leaf async pages called as functions | Vitest/Jest + Testing Library in jsdom (`testing.md`) — never screenshots |
| Component | one Client Component (or a synchronous, `server-only`-free presentational Server Component) per visual state | Playwright `mount()` against a **standalone Vite gallery** |
| Screen | one page of the **production build** (`next build && next start`) with upstreams faked | Playwright `page.goto()` |
| Flow (e2e) | a user goal across pages, the production build, upstreams faked | Playwright navigation |

**Component tests.** `@playwright/experimental-ct-react` is frozen at 1.62; from `@playwright/test` 1.62 a component
test is a plain project whose `baseURL` is a gallery page, driven by the built-in `mount(storyId, props?)` fixture
(returns the `#root` locator, with `update(props)`/`unmount()`). Next.js has no Vite dev server, so the gallery runs
on its own:

```text
playwright/vite.config.ts        # root '..', @vitejs/plugin-react (+ @tailwindcss/vite for Tailwind v4), port 3100,
                                 # aliases: @/ → src/, next/link + next/navigation → gallery/next-stubs.tsx,
                                 # next/image → gallery/next-image.tsx
playwright/gallery/index.html    # <main id="root"></main> + <script type="module" src="./main.tsx">
playwright/gallery/main.tsx      # import.meta.glob('../../src/**/*.story.tsx'), createRoot reused, flushSync render,
                                 # imports src/app/globals.css
playwright/gallery/next-stubs.tsx # Link (prevents navigation, records body[data-last-navigation]), useRouter,
                                 # usePathname, useSearchParams, useParams; Image → <img>
src/**/<Component>.story.tsx     # one export per visual state; events recorded in a hidden form
e2e/support/ui-screenshots.ts    # the shared helper — reference/ui-screenshots.ts verbatim (under Playwright's testDir)
e2e/components/*.spec.ts         # component specs; e2e/*.spec.ts screen and flow specs
```

```ts
// playwright.config.ts — add next to the existing e2e project (which gets testIgnore: 'components/**')
{ name: 'components', testDir: './e2e/components',
  use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:3100/playwright/gallery/index.html',
         serviceWorkers: 'block', reuseContext: true } },
// webServer becomes an array: the gallery, then the existing production server entry
{ command: 'npx vite --config playwright/vite.config.ts', url: 'http://localhost:3100/playwright/gallery/index.html',
  reuseExistingServer: !process.env.CI },
```

A story renders the Client Component with fixture DTOs and records its callbacks into a hidden form
(`<input data-testid="selected" readOnly value={selected} />`); a Server Action prop is replaced in the story by a
local async function returning fixture data. Components importing `server-only`, `next/headers`, `next/cache` or a
data module can't be storied — test them as screens. Story ids are the path under `src/` without `.story.tsx`, plus
the export (`components/OrderList/ManyItems`, or the suffix `OrderList/ManyItems`); type props with
`mount<typeof ManyItems>(…)`.

**Screen and flow tests** run against the production build the existing e2e project already starts. Upstream
services the server fetches are faked by pointing their env-configured URLs at a fake server in `webServer.env`
(browser-side `page.route()` can't intercept server fetches). `cacheComponents`/PPR pages: wait for the streamed
content (`expect(…).toBeVisible()`) before capturing.

**The matrix, screenshots and accessibility** — the shared helper (create `e2e/support/ui-screenshots.ts` from this
skill's `reference/ui-screenshots.ts` if missing; adjust the folder to the config's `testDir`). It resolves
`UI_SCREENSHOTS_DIR` else `<repo root>/.ui-screenshots`, reads `.ui-quality.yml`, and for every direction × theme:
emulates `colorScheme`/`forcedColors`/`reducedMotion: 'reduce'`, sets `dir` on `<html>` through a disposable init
script (applied before hydration on each navigation), calls your render callback, and writes
`<kind>/<Id>/<state>--<dir>--<theme>.png`; with `accessibility: { testInfo }` it runs axe (WCAG 2.x A/AA) per cell
at the configured level (`enforce` fails softly after capturing everything, `report` annotates, `off` skips).

```ts
test('OrdersPage empty state', async ({ page }, testInfo) => {
  await captureUiMatrix(page, { kind: 'screen', id: 'OrdersPage', state: 'empty' }, async () => {
    await page.goto('/orders?fixture=empty')
    await expect(page.getByRole('heading', { level: 1, name: 'Orders' })).toBeVisible()
    return page
  }, { accessibility: { testInfo } })
})
```

`<Id>` is the component/page type name (`OrderList`, `OrdersPage` — name page components after the screen);
flows use `kind: 'flow'`, a kebab-case id and `NN-<step>` states. Dark mode follows `prefers-color-scheme`
(Tailwind's default `dark:` variant) so `emulateMedia` switches it; a class-based dark mode needs an `onVariant`
hook that toggles the class. Use CSS logical properties (Tailwind `ms-*`/`me-*`/`ps-*`/`pe-*`/`start-*`/`end-*`/
`text-start`, never `ml-*`/`left-*`/`text-left`) and `rtl:` variants only for mirroring icons.

**Determinism.** Fixture DTOs only; a fixed clock passed as a prop or faked upstream; `next/font` self-hosts fonts
(the helper awaits `document.fonts.ready`); no live third-party scripts in tests.

**Lint.** `eslint-config-next` already includes `eslint-plugin-jsx-a11y`'s core rules; keep them on.

## Pages Router

The same mapping with `pages/<route>.tsx` as the screen (data from `getServerSideProps`/`getStaticProps`); every
component is client-hydrated, so all of them can be storied; server pagination uses query parameters in
`getServerSideProps`.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Prefer native semantic elements (`button`, `a`, `nav`, `main`, `label`/`input`, headings in order) over `div` + `role`; add `aria-*` only where HTML has no equivalent.
- Icon-only controls get `aria-label`; images get `alt` (empty `alt=""` when decorative); dynamic updates use `aria-live`; modals trap and restore focus.
- Honour `prefers-reduced-motion` and `forced-colors`; size text in `rem`, never fixed `px` heights that clip on zoom.
- Per-route: set a unique `<title>` and one `<h1>`; announce client-side route changes (focus the new heading or use a live region); Server Components render the semantic markup, Client Components add ARIA state.
- Lint with `eslint-plugin-jsx-a11y` (bundled in `eslint-config-next`).

## Sources

- Playwright component testing (stories and gallery, `mount` fixture): <https://playwright.dev/docs/test-components>
- Playwright accessibility testing with axe: <https://playwright.dev/docs/accessibility-testing>; `page.emulateMedia`,
  `addInitScript`: <https://playwright.dev/docs/api/class-page>
- Next.js Server and Client Components, composition: <https://nextjs.org/docs/app/getting-started/server-and-client-components>
- Next.js testing with Playwright: <https://nextjs.org/docs/app/guides/testing/playwright>
- Next.js `searchParams` on pages: <https://nextjs.org/docs/app/api-reference/file-conventions/page>
- TanStack Virtual: <https://tanstack.com/virtual/latest/docs/framework/react/react-virtual>
- Tailwind CSS logical-property utilities and `rtl:`/`dark:` variants: <https://tailwindcss.com/docs/hover-focus-and-other-states>
- eslint-plugin-jsx-a11y: <https://github.com/jsx-eslint/eslint-plugin-jsx-a11y>
- MDN CSS logical properties: <https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_logical_properties_and_values>
