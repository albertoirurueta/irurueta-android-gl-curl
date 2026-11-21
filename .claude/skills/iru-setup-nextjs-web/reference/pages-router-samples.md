# Pages Router sample code (Step 7, `router: pages`)

The same book-list feature as `app-router-samples.md`, written for the Pages Router as
`iru-nextjs-code-one-task/reference/pages-router.md` describes it: every file under `src/pages/` is a route, data
comes from `getStaticProps`/`getStaticPaths` (results serialized into `__NEXT_DATA__`, so DTOs only), API routes
validate their own input, and there are no Server Components, Server Actions, or `server-only` modules (that
package breaks Pages Router modules). Delete the scaffold's `src/pages/api/hello.ts` placeholder and its
`src/pages/index.tsx` starter (replaced below). Keep `_app.tsx`/`_document.tsx` as generated.

Same `File:` / `when:` convention and tags as `app-router-samples.md` (`server`, `static`, `e2e`;
`cache-components` doesn't exist in the Pages Router).

Route table this produces (verified):

| Route | `server` | `static` |
|---|---|---|
| `/` | `○` | `○` |
| `/books` | `●` (ISR, `revalidate: 60`) | `●` |
| `/books/[id]` | `●` (`/books/1`, `/books/2`; others `fallback: "blocking"`) | `●` (`fallback: false`) |
| `/api/books/[id]`, `/api/health` | `ƒ` | — |

File: `src/lib/books.ts` — when: `pages`

```ts
/** A book as exposed to pages: a DTO with explicit fields, never the storage record. */
export interface Book {
  /** Stable identifier, used in URLs. */
  readonly id: string;
  /** The book's title. */
  readonly title: string;
  /** The author or authors, as displayed. */
  readonly author: string;
}

/**
 * In-memory sample store standing in for a database. Replace this module's internals with your data access and
 * keep its exported functions and DTO shape. Only call it from data functions and API routes — never from
 * component code, which also runs in the browser.
 */
const records: readonly Book[] = [
  { id: "1", title: "The Pragmatic Programmer", author: "David Thomas and Andrew Hunt" },
  { id: "2", title: "Refactoring", author: "Martin Fowler" },
];

/**
 * Lists every book, oldest first.
 *
 * @returns the books as DTOs
 */
export async function listBooks(): Promise<readonly Book[]> {
  return records.map((record) => ({ id: record.id, title: record.title, author: record.author }));
}

/**
 * Looks one book up by id.
 *
 * @param id - the book id, already validated by the caller
 * @returns the book, or `undefined` when no book has this id
 */
export async function getBook(id: string): Promise<Book | undefined> {
  return (await listBooks()).find((book) => book.id === id);
}
```

File: `src/lib/books.test.ts` — when: `pages`

```ts
// @vitest-environment node
import { expect, it } from "vitest";

import { getBook, listBooks } from "./books";

it("lists the seeded books as DTOs", async () => {
  await expect(listBooks()).resolves.toHaveLength(2);
});

it("finds a book by id and returns undefined for an unknown id", async () => {
  await expect(getBook("2")).resolves.toMatchObject({ title: "Refactoring" });
  await expect(getBook("999")).resolves.toBeUndefined();
});
```

File: `src/components/like-button.tsx` — when: `pages`

```tsx
import { useState } from "react";

/** Props of {@link LikeButton}. */
export interface LikeButtonProps {
  /** What is being liked; part of the button's accessible name. */
  readonly label: string;
}

/** A toggle kept entirely in the browser. */
export function LikeButton({ label }: LikeButtonProps) {
  const [liked, setLiked] = useState(false);

  return (
    <button
      type="button"
      aria-pressed={liked}
      aria-label={`Like ${label}`}
      onClick={() => setLiked((current) => !current)}
    >
      {liked ? "♥" : "♡"}
    </button>
  );
}
```

File: `src/components/like-button.test.tsx` — when: `pages`

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";

import { LikeButton } from "./like-button";

it("toggles its pressed state on click", async () => {
  const user = userEvent.setup();
  render(<LikeButton label="Refactoring" />);
  const button = screen.getByRole("button", { name: "Like Refactoring" });

  await user.click(button);
  expect(button).toHaveAttribute("aria-pressed", "true");
});
```

File: `src/pages/index.tsx` — when: `pages`

```tsx
import Head from "next/head";
import Link from "next/link";

/** The home page — static (`○`). */
export default function Home() {
  return (
    <>
      <Head>
        <title>Welcome</title>
      </Head>
      <main>
        <h1>Welcome</h1>
        <p>This app was scaffolded with a sample book list that exercises every layer of the stack.</p>
        <Link href="/books">Browse the books</Link>
      </main>
    </>
  );
}
```

Tests never go under `src/pages/` — every file there with a page extension becomes a route. They live in a
mirrored `src/__tests__/pages/` tree instead (still matched by the Vitest `include` glob).

File: `src/__tests__/pages/index.test.tsx` — when: `pages`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import Home from "@/pages/index";

it("links to the book list", () => {
  render(<Home />);
  expect(screen.getByRole("heading", { level: 1, name: "Welcome" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Browse the books" })).toHaveAttribute("href", "/books");
});
```

File: `src/pages/books/index.tsx` — when: `pages`, `server`

```tsx
import type { GetStaticProps, InferGetStaticPropsType } from "next";
import Head from "next/head";
import Link from "next/link";

import { LikeButton } from "@/components/like-button";
import { type Book, listBooks } from "@/lib/books";

/** Props of the book list page — serialized into the HTML, so DTOs only. */
export interface BooksPageProps {
  /** Every book on the shelf. */
  readonly books: readonly Book[];
}

/**
 * Loads the book list at build time and regenerates it at most once a minute (ISR).
 *
 * @returns the page props
 */
export const getStaticProps = (async () => ({
  props: { books: await listBooks() },
  revalidate: 60,
})) satisfies GetStaticProps<BooksPageProps>;

/** The book list. */
export default function BooksPage({ books }: InferGetStaticPropsType<typeof getStaticProps>) {
  return (
    <>
      <Head>
        <title>Books</title>
      </Head>
      <main>
        <h1>Books</h1>
        <ul>
          {books.map((book) => (
            <li key={book.id}>
              <Link href={`/books/${book.id}`}>{book.title}</Link> by {book.author}{" "}
              <LikeButton label={book.title} />
            </li>
          ))}
        </ul>
      </main>
    </>
  );
}
```

File: `src/pages/books/index.tsx` — when: `pages`, `static`

```tsx
import type { GetStaticProps, InferGetStaticPropsType } from "next";
import Head from "next/head";
import Link from "next/link";

import { LikeButton } from "@/components/like-button";
import { type Book, listBooks } from "@/lib/books";

/** Props of the book list page — serialized into the HTML, so DTOs only. */
export interface BooksPageProps {
  /** Every book on the shelf. */
  readonly books: readonly Book[];
}

/**
 * Loads the book list once, at build time (`output: 'export'` has no ISR).
 *
 * @returns the page props
 */
export const getStaticProps = (async () => ({
  props: { books: await listBooks() },
})) satisfies GetStaticProps<BooksPageProps>;

/** The book list. */
export default function BooksPage({ books }: InferGetStaticPropsType<typeof getStaticProps>) {
  return (
    <>
      <Head>
        <title>Books</title>
      </Head>
      <main>
        <h1>Books</h1>
        <ul>
          {books.map((book) => (
            <li key={book.id}>
              <Link href={`/books/${book.id}`}>{book.title}</Link> by {book.author}{" "}
              <LikeButton label={book.title} />
            </li>
          ))}
        </ul>
      </main>
    </>
  );
}
```

File: `src/__tests__/pages/books/index.test.tsx` — when: `pages`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";

vi.mock("@/lib/books", () => ({
  listBooks: async () => [{ id: "1", title: "Refactoring", author: "Martin Fowler" }],
}));

import BooksPage, { getStaticProps } from "@/pages/books/index";

it("loads the books as props", async () => {
  await expect(getStaticProps()).resolves.toMatchObject({
    props: { books: [{ id: "1", title: "Refactoring", author: "Martin Fowler" }] },
  });
});

it("renders every book with a link and a like button", () => {
  render(<BooksPage books={[{ id: "1", title: "Refactoring", author: "Martin Fowler" }]} />);
  expect(screen.getByRole("link", { name: "Refactoring" })).toHaveAttribute("href", "/books/1");
  expect(screen.getByRole("button", { name: "Like Refactoring" })).toBeInTheDocument();
});
```

File: `src/pages/books/[id].tsx` — when: `pages`, `server`

```tsx
import type { GetStaticPaths, GetStaticProps, InferGetStaticPropsType } from "next";
import Head from "next/head";
import Link from "next/link";

import { type Book, getBook, listBooks } from "@/lib/books";

/** The params of a book page. */
export interface BookParams extends Record<string, string> {
  /** The book id from the URL. */
  id: string;
}

/** Props of a book page. */
export interface BookPageProps {
  /** The book shown. */
  readonly book: Book;
}

/**
 * Prerenders one page per known book; other ids render on first request and are then cached.
 *
 * @returns the paths to prerender
 */
export const getStaticPaths = (async () => ({
  paths: (await listBooks()).map((book) => ({ params: { id: book.id } })),
  fallback: "blocking",
})) satisfies GetStaticPaths<BookParams>;

/**
 * Loads one book; an unknown id renders the 404 page.
 *
 * @param context - the static-props context carrying the `id` param
 * @returns the page props, or `notFound`
 */
export const getStaticProps = (async ({ params }) => {
  const book = params ? await getBook(params.id) : undefined;
  return book ? { props: { book } } : { notFound: true };
}) satisfies GetStaticProps<BookPageProps, BookParams>;

/** One book. */
export default function BookPage({ book }: InferGetStaticPropsType<typeof getStaticProps>) {
  return (
    <>
      <Head>
        <title>{book.title}</title>
      </Head>
      <main>
        <h1>{book.title}</h1>
        <p>by {book.author}</p>
        <Link href="/books">All books</Link>
      </main>
    </>
  );
}
```

File: `src/pages/books/[id].tsx` — when: `pages`, `static`

```tsx
import type { GetStaticPaths, GetStaticProps, InferGetStaticPropsType } from "next";
import Head from "next/head";
import Link from "next/link";

import { type Book, getBook, listBooks } from "@/lib/books";

/** The params of a book page. */
export interface BookParams extends Record<string, string> {
  /** The book id from the URL. */
  id: string;
}

/** Props of a book page. */
export interface BookPageProps {
  /** The book shown. */
  readonly book: Book;
}

/**
 * Prerenders one page per known book; under `output: 'export'` any other id simply doesn't exist.
 *
 * @returns the paths to prerender
 */
export const getStaticPaths = (async () => ({
  paths: (await listBooks()).map((book) => ({ params: { id: book.id } })),
  fallback: false,
})) satisfies GetStaticPaths<BookParams>;

/**
 * Loads one book; an unknown id renders the 404 page.
 *
 * @param context - the static-props context carrying the `id` param
 * @returns the page props, or `notFound`
 */
export const getStaticProps = (async ({ params }) => {
  const book = params ? await getBook(params.id) : undefined;
  return book ? { props: { book } } : { notFound: true };
}) satisfies GetStaticProps<BookPageProps, BookParams>;

/** One book. */
export default function BookPage({ book }: InferGetStaticPropsType<typeof getStaticProps>) {
  return (
    <>
      <Head>
        <title>{book.title}</title>
      </Head>
      <main>
        <h1>{book.title}</h1>
        <p>by {book.author}</p>
        <Link href="/books">All books</Link>
      </main>
    </>
  );
}
```

File: `src/__tests__/pages/books/[id].test.tsx` — when: `pages`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/books", () => {
  const books = [{ id: "1", title: "Refactoring", author: "Martin Fowler" }];
  return {
    listBooks: async () => books,
    getBook: async (id: string) => books.find((book) => book.id === id),
  };
});

import BookPage, { getStaticPaths, getStaticProps } from "@/pages/books/[id]";

describe("book page", () => {
  it("prerenders every known id", async () => {
    await expect(getStaticPaths()).resolves.toMatchObject({ paths: [{ params: { id: "1" } }] });
  });

  it("loads a known book and 404s an unknown or missing id", async () => {
    await expect(getStaticProps({ params: { id: "1" } })).resolves.toMatchObject({
      props: { book: { title: "Refactoring" } },
    });
    await expect(getStaticProps({ params: { id: "999" } })).resolves.toEqual({ notFound: true });
    await expect(getStaticProps({})).resolves.toEqual({ notFound: true });
  });

  it("renders the book", () => {
    render(<BookPage book={{ id: "1", title: "Refactoring", author: "Martin Fowler" }} />);
    expect(screen.getByRole("heading", { level: 1, name: "Refactoring" })).toBeInTheDocument();
  });
});
```

File: `src/pages/404.tsx` — when: `pages`

```tsx
import Link from "next/link";

/** Rendered for every unmatched URL and for `notFound: true`. */
export default function NotFound() {
  return (
    <main>
      <h1>Page not found</h1>
      <p>The page you asked for does not exist.</p>
      <Link href="/">Back home</Link>
    </main>
  );
}
```

File: `src/__tests__/pages/404.test.tsx` — when: `pages`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import NotFound from "@/pages/404";

it("offers a way back home", () => {
  render(<NotFound />);
  expect(screen.getByRole("link", { name: "Back home" })).toHaveAttribute("href", "/");
});
```

File: `src/pages/api/books/[id].ts` — when: `pages`, `server`

```ts
import type { NextApiRequest, NextApiResponse } from "next";

import { type Book, getBook } from "@/lib/books";

const ID_PATTERN = /^\d{1,10}$/;

/** Error body of the books API. */
export interface ApiError {
  /** A message safe to show to the caller. */
  readonly error: string;
}

/**
 * `GET /api/books/:id` — one book as JSON; 405 for other methods, 400 for a malformed id, 404 for an unknown one.
 * Add authentication here before exposing anything that isn't public.
 *
 * @param req - the incoming request
 * @param res - the response
 */
export default async function handler(req: NextApiRequest, res: NextApiResponse<Book | ApiError>) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    res.status(405).json({ error: "Method not allowed" });
    return;
  }
  const id = req.query.id;
  if (typeof id !== "string" || !ID_PATTERN.test(id)) {
    res.status(400).json({ error: "Invalid id" });
    return;
  }
  const book = await getBook(id);
  if (!book) {
    res.status(404).json({ error: "Not found" });
    return;
  }
  res.status(200).json(book);
}
```

File: `src/__tests__/pages/api/books/[id].test.ts` — when: `pages`, `server`

```ts
// @vitest-environment node
import type { NextApiRequest, NextApiResponse } from "next";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/books", () => ({
  getBook: async (id: string) => (id === "1" ? { id: "1", title: "Refactoring", author: "Martin Fowler" } : undefined),
}));

import handler from "@/pages/api/books/[id]";

const call = async (method: string, id: string | string[]) => {
  const res = { status: vi.fn(), json: vi.fn(), setHeader: vi.fn() };
  res.status.mockReturnValue(res);
  await handler({ method, query: { id } } as unknown as NextApiRequest, res as unknown as NextApiResponse);
  return res;
};

describe("/api/books/[id]", () => {
  it("rejects other methods with 405", async () => {
    const res = await call("POST", "1");
    expect(res.status).toHaveBeenCalledWith(405);
    expect(res.setHeader).toHaveBeenCalledWith("Allow", "GET");
  });

  it("returns 400 for a malformed id and 404 for an unknown one", async () => {
    expect((await call("GET", ["1", "2"])).status).toHaveBeenCalledWith(400);
    expect((await call("GET", "999")).status).toHaveBeenCalledWith(404);
  });

  it("returns the book", async () => {
    const res = await call("GET", "1");
    expect(res.status).toHaveBeenCalledWith(200);
    expect(res.json).toHaveBeenCalledWith({ id: "1", title: "Refactoring", author: "Martin Fowler" });
  });
});
```

File: `src/pages/api/health.ts` — when: `pages`, `server`

```ts
import type { NextApiRequest, NextApiResponse } from "next";

/** Body of the health probe. */
export interface Health {
  /** Always `"ok"` while the server answers. */
  readonly status: "ok";
}

/**
 * `GET /api/health` — liveness probe for the container `HEALTHCHECK` and load balancers; never cached.
 *
 * @param _req - the incoming request (unused)
 * @param res - the response
 */
export default function handler(_req: NextApiRequest, res: NextApiResponse<Health>) {
  res.setHeader("Cache-Control", "no-store");
  res.status(200).json({ status: "ok" });
}
```

File: `src/__tests__/pages/api/health.test.ts` — when: `pages`, `server`

```ts
// @vitest-environment node
import type { NextApiRequest, NextApiResponse } from "next";
import { expect, it, vi } from "vitest";

import handler from "@/pages/api/health";

it("reports ok without caching", () => {
  const res = { status: vi.fn(), json: vi.fn(), setHeader: vi.fn() };
  res.status.mockReturnValue(res);
  handler({} as NextApiRequest, res as unknown as NextApiResponse);
  expect(res.setHeader).toHaveBeenCalledWith("Cache-Control", "no-store");
  expect(res.json).toHaveBeenCalledWith({ status: "ok" });
});
```

File: `e2e/books.spec.ts` — when: `pages`, `e2e`, `server`

```ts
import { expect, test } from "@playwright/test";

test("home links to the book list", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Browse the books" }).click();
  await page.waitForURL("**/books");
  await expect(page.getByRole("heading", { level: 1, name: "Books" })).toBeVisible();
});

test("unknown books render the 404 page", async ({ page }) => {
  const response = await page.goto("/books/999");
  expect(response?.status()).toBe(404);
  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
});

test("API routes answer with the documented status codes", async ({ request }) => {
  expect((await request.get("/api/health")).status()).toBe(200);
  expect((await request.get("/api/books/1")).status()).toBe(200);
  expect((await request.get("/api/books/abc")).status()).toBe(400);
});
```

File: `e2e/books.spec.ts` — when: `pages`, `e2e`, `static`

```ts
import { expect, test } from "@playwright/test";

test("home links to the book list", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Browse the books" }).click();
  await page.waitForURL("**/books/");
  await expect(page.getByRole("heading", { level: 1, name: "Books" })).toBeVisible();
});

test("every known book has a prerendered page", async ({ page }) => {
  await page.goto("/books/2/");
  await expect(page.getByRole("heading", { level: 1, name: "Refactoring" })).toBeVisible();
});
```
