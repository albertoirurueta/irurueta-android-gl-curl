# Server Actions

A Server Action is a **public POST endpoint** (the page URL plus a `Next-Action` header), reachable by anyone who
can send HTTP — not a private function call. Encrypted action IDs, tree-shaking of unused actions, and the
Origin/Host check are defence in depth, never authorization. Read `security.md` alongside this file.

## Defining them

- **Default: a module-level `'use server'` file** next to the feature (`app/todos/actions.ts`, or
  `lib/actions/<domain>.ts` if the project centralizes them). A `'use server'` file may export **only async
  functions** — `export const X = 5` fails the build; `export type` is fine (erased).
- An inline `'use server'` function inside a Server Component is for one-off actions that close over component
  values. Client Components can't define actions: they import them from a `'use server'` file or receive them as
  props.
- Arguments and return values must be serializable. Take **ids**, not objects.
- Actions are for **mutations**. Reads belong in Server Components (or a Route Handler for client fetching). The
  client runs actions one at a time — `Promise.all` over actions does not parallelize.
- **14**: same model; the form-state hook is `useFormState` from `react-dom` instead of `useActionState`.

## The body of every action, in order

1. **Authenticate**: read the session server-side through the project's data access layer
   (`verifySession()`/`getUser()`/`auth()`). Never take the user's identity from an argument or a hidden field.
2. **Validate** every input with a schema — `FormData` values, `.bind()` arguments, and hidden inputs are all
   untrusted (bound arguments are plain text in the HTML). Use the project's validation library (Zod if present):
   `schema.safeParse({ title: formData.get("title") })` or `Object.fromEntries(formData)` through a non-strict
   object schema that lists the fields (hydrated submissions add `$ACTION_*` keys). `z.coerce.number()` for
   numbers. Zod 4: `error` option and `z.flattenError(err).fieldErrors`. Never spread raw form data into a write.
3. **Authorize**: re-read the target row by id and check the caller may change it (owner/role). Shape is not
   permission. Look up prices, roles, and totals server-side — ignore client-supplied values.
4. **Mutate** through the data layer.
5. **Invalidate** (below), then **redirect** if needed — `redirect()` last.
6. **Return** a small, UI-shaped result.

## Return shapes and errors

- Expected failures are **returned**, not thrown — a discriminated union the form renders:
  ```ts
  export type SaveState =
    | { readonly status: "idle" }
    | { readonly status: "ok" }
    | { readonly status: "error"; readonly error: string; readonly fieldErrors?: Readonly<Record<string, string[]>> };
  ```
  Echo submitted values back when the form must refill without JS.
- Unexpected errors throw; production clients get only an opaque `digest` and the nearest `error.tsx` renders.
- Return values are public: never a raw row, never internal error messages.

## Forms and hooks (Client Components)

- `const [state, formAction, pending] = useActionState(action, initialState)` — the action signature becomes
  `(previousState, formData)`. Export the state type from the actions file (`export type` is allowed there).
- `useFormStatus()` (from `react-dom`) only works in a **child** of the `<form>` — a separate submit-button
  component.
- Calling an action from an event handler: inside `startTransition(async () => { ... })`; `useTransition` for the
  pending flag.
- `useOptimistic(serverState, reducer)`: call the setter inside the transition; it reverts when the transition
  ends — show an error on failure.
- Progressive enhancement: `<form action={serverAction}>` rendered from a Server Component works without JS;
  `.bind(null, id)` keeps that. `next/form` `<Form action="/search">` is a client-navigating GET form for search,
  never for mutations.

## Invalidation after the mutation

| Profile | Calls |
|---|---|
| `14`, `15` | `revalidatePath(path, "page" \| "layout")`, `revalidateTag(tag)` (one argument) |
| `16-prev`, `16-cc` | `updateTag(tag)` (read-your-own-writes, actions only), `revalidateTag(tag, "max")` (stale-while-revalidate), `revalidatePath(path, type?)`, `refresh()` (client router only). One-argument `revalidateTag` is deprecated. |

- Invalidate the tags the **read side** actually uses (`cacheTag` under `16-cc`, `next.tags`/`unstable_cache` tags
  in the previous model) — check the reader before choosing a tag.
- Any invalidation call or a cookie change in an action clears the whole client router cache and re-renders the
  current page.
- Never mutate or revalidate during render or on a `GET`.

## `redirect()` ordering

`redirect()` throws `NEXT_REDIRECT`: invalidate **before** it, nothing after it runs, and never call it inside a
`try` whose `catch` swallows it — call it after the `try`, or re-throw with `unstable_rethrow(err)` first in the
`catch`. Never build the destination from unchecked input (open redirects): allow-list locales, validate ids,
same-origin paths only for `?from=`.

## Configuration (only when the task asks)

- `experimental.serverActions.bodySizeLimit` (default 1 MB of raw multipart body) — keep large uploads in a Route
  Handler or direct-to-storage instead of raising it.
- `experimental.serverActions.allowedOrigins` for a reverse proxy with a different public host.
- Multi-instance or rolling deployments: the same `NEXT_SERVER_ACTIONS_ENCRYPTION_KEY` (base64, 16/24/32 bytes) at
  build time from a secret store — otherwise "Failed to find Server Action".
- Closure variables of inline actions are encrypted; bound arguments and hidden inputs are not — never pass secrets
  that way.
- `output: "export"` projects cannot have Server Actions at all.
