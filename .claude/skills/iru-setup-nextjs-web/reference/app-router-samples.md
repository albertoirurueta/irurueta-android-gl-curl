# App Router sample code (Step 7)

The sample feature — a small book list — exists to prove every layer of the toolchain on the first commit and to
give `iru-nextjs-code-one-task` real code to imitate. Each file follows the coding references in
`iru-nextjs-code-one-task/reference/` (server/client split, DTOs out of a `server-only` data layer, Server Action
order authenticate → validate → write → invalidate, Route Handlers returning 401/400/404, async Server Components
tested by calling them, Playwright against the production build).

Each template is introduced by a `File:` line naming its path and, after `when:`, the variant tags it belongs to.
Write a file only when **every** tag listed applies to this run (`!tag` means the tag must not apply); a `File:`
line with no `when:` applies to every App Router run. Tags:

| Tag | Applies when |
|---|---|
| `server` | `output: server` |
| `static` | `output: static` (`output: 'export'`) |
| `prev` | `cache-components: no` (the previous caching model) |
| `cc` | `cache-components: yes` (only ever with `server`) |
| `e2e` | `e2e: playwright` |

`<App title>` is the resolved `app-title` (Step 2). Prettier (Step 5) reformats these files to the project's own
`.prettierrc.json` before `format:check` runs, so line breaks here are not significant.

Rendering modes this produces in the `next build` route table (verified — Step 9 compares against it):

| Route | `server` + `prev` | `server` + `cc` | `static` |
|---|---|---|---|
| `/` | `○` | `○` | `○` |
| `/books` | `○` (regenerated on demand by `revalidatePath`) | `○` with Revalidate `1h` / Expire `1d` (`cacheLife("hours")`; `updateTag` invalidates) | `○` |
| `/books/[id]` | `●` (`/books/1`, `/books/2`; others rendered on first request) | `◐` `/books/[id]` shell, `○` `/books/1`, `/books/2` (1h/1d) | `●` (`dynamicParams = false`) |
| `/api/books/[id]` | `ƒ` | `ƒ` | — |
| `/api/health` | `ƒ` | `ƒ` | — |
| `/books.json` | — | — | `○` |

## Data layer and session

File: `src/lib/books.ts` — when: `!cc`

```ts
import "server-only";

/** A book as exposed to pages and Client Components: a DTO with explicit fields, never the storage record. */
export interface Book {
  /** Stable identifier, used in URLs. */
  readonly id: string;
  /** The book's title. */
  readonly title: string;
  /** The author or authors, as displayed. */
  readonly author: string;
}

/** The validated fields a caller supplies to create a book. */
export interface NewBook {
  /** The book's title, trimmed and non-empty. */
  readonly title: string;
  /** The author or authors, trimmed and non-empty. */
  readonly author: string;
}

interface BookRecord extends Book {
  readonly createdBy: string;
}

/**
 * In-memory sample store standing in for a database. Replace this module's internals with your data access (ORM,
 * SDK, upstream API) and keep its exported functions and DTO shape.
 */
const records: BookRecord[] = [
  { id: "1", title: "The Pragmatic Programmer", author: "David Thomas and Andrew Hunt", createdBy: "seed" },
  { id: "2", title: "Refactoring", author: "Martin Fowler", createdBy: "seed" },
];

function toBook(record: BookRecord): Book {
  return { id: record.id, title: record.title, author: record.author };
}

/**
 * Lists every book, oldest first.
 *
 * @returns the books as DTOs
 */
export async function listBooks(): Promise<readonly Book[]> {
  return records.map(toBook);
}

/**
 * Looks one book up by id.
 *
 * @param id - the book id, already validated by the caller
 * @returns the book, or `undefined` when no book has this id
 */
export async function getBook(id: string): Promise<Book | undefined> {
  const record = records.find((candidate) => candidate.id === id);
  return record ? toBook(record) : undefined;
}

/**
 * Stores a new book on behalf of a user.
 *
 * @param input - the validated title and author
 * @param userId - the authenticated user creating the book
 * @returns the stored book
 */
export async function addBook(input: NewBook, userId: string): Promise<Book> {
  const record: BookRecord = {
    id: String(records.length + 1),
    title: input.title,
    author: input.author,
    createdBy: userId,
  };
  records.push(record);
  return toBook(record);
}
```

File: `src/lib/books.ts` — when: `cc`

```ts
import "server-only";

import { cacheLife, cacheTag } from "next/cache";

/** A book as exposed to pages and Client Components: a DTO with explicit fields, never the storage record. */
export interface Book {
  /** Stable identifier, used in URLs. */
  readonly id: string;
  /** The book's title. */
  readonly title: string;
  /** The author or authors, as displayed. */
  readonly author: string;
}

/** The validated fields a caller supplies to create a book. */
export interface NewBook {
  /** The book's title, trimmed and non-empty. */
  readonly title: string;
  /** The author or authors, trimmed and non-empty. */
  readonly author: string;
}

/** Cache tag on every book read; Server Actions invalidate it with `updateTag` after a write. */
export const BOOKS_TAG = "books";

interface BookRecord extends Book {
  readonly createdBy: string;
}

/**
 * In-memory sample store standing in for a database. Replace this module's internals with your data access (ORM,
 * SDK, upstream API) and keep its exported functions and DTO shape.
 */
const records: BookRecord[] = [
  { id: "1", title: "The Pragmatic Programmer", author: "David Thomas and Andrew Hunt", createdBy: "seed" },
  { id: "2", title: "Refactoring", author: "Martin Fowler", createdBy: "seed" },
];

function toBook(record: BookRecord): Book {
  return { id: record.id, title: record.title, author: record.author };
}

/**
 * Lists every book, oldest first. Cached (`'use cache'`) and tagged with {@link BOOKS_TAG}.
 *
 * @returns the books as DTOs
 */
export async function listBooks(): Promise<readonly Book[]> {
  "use cache";
  cacheLife("hours");
  cacheTag(BOOKS_TAG);
  return records.map(toBook);
}

/**
 * Looks one book up by id. Cached (`'use cache'`) and tagged with {@link BOOKS_TAG}.
 *
 * @param id - the book id, already validated by the caller
 * @returns the book, or `undefined` when no book has this id
 */
export async function getBook(id: string): Promise<Book | undefined> {
  "use cache";
  cacheLife("hours");
  cacheTag(BOOKS_TAG);
  const record = records.find((candidate) => candidate.id === id);
  return record ? toBook(record) : undefined;
}

/**
 * Stores a new book on behalf of a user. Not cached: callers invalidate {@link BOOKS_TAG} afterwards.
 *
 * @param input - the validated title and author
 * @param userId - the authenticated user creating the book
 * @returns the stored book
 */
export async function addBook(input: NewBook, userId: string): Promise<Book> {
  const record: BookRecord = {
    id: String(records.length + 1),
    title: input.title,
    author: input.author,
    createdBy: userId,
  };
  records.push(record);
  return toBook(record);
}
```

File: `src/lib/books.test.ts`

```ts
// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

// Only the cache-components variant imports these; mocking an unused module is harmless.
vi.mock("next/cache", () => ({ cacheLife: vi.fn(), cacheTag: vi.fn() }));

import { addBook, getBook, listBooks } from "./books";

describe("books data layer", () => {
  it("lists the seeded books as DTOs without storage fields", async () => {
    const books = await listBooks();
    expect(books.length).toBeGreaterThanOrEqual(2);
    expect(books[0]).toEqual({
      id: "1",
      title: "The Pragmatic Programmer",
      author: "David Thomas and Andrew Hunt",
    });
  });

  it("finds a book by id and returns undefined for an unknown id", async () => {
    await expect(getBook("2")).resolves.toMatchObject({ title: "Refactoring" });
    await expect(getBook("999")).resolves.toBeUndefined();
  });

  it("adds a book and exposes only DTO fields", async () => {
    const book = await addBook({ title: "Domain-Driven Design", author: "Eric Evans" }, "user-1");
    expect(book).toEqual({ id: expect.any(String), title: "Domain-Driven Design", author: "Eric Evans" });
    await expect(getBook(book.id)).resolves.toEqual(book);
  });
});
```

File: `src/lib/session.ts` — when: `server`

```ts
import "server-only";

import { cache } from "react";

/** The authenticated caller. */
export interface Session {
  /** The authenticated user's id — the only identity value Server Actions and handlers trust. */
  readonly userId: string;
}

/**
 * Resolves the current caller's session, memoized per request.
 *
 * Scaffold stub: every caller is the demo user so the sample flow works end to end. Replace the body with the
 * project's authentication (a signed session cookie read through `cookies()`, Auth.js, Clerk, ...) and return
 * `null` for anonymous callers — every Server Action and Route Handler already handles that case.
 *
 * @returns the session, or `null` when the caller is anonymous
 */
export const getSession = cache(async (): Promise<Session | null> => ({ userId: "demo-user" }));
```

File: `src/lib/session.test.ts` — when: `server`

```ts
// @vitest-environment node
import { expect, it } from "vitest";

import { getSession } from "./session";

it("returns the demo session until real authentication replaces the stub", async () => {
  await expect(getSession()).resolves.toEqual({ userId: "demo-user" });
});
```

## Server Action and its form

File: `src/app/books/actions.ts` — when: `server`, `prev`

```ts
"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";

import { addBook } from "@/lib/books";
import { getSession } from "@/lib/session";

/** Result of {@link createBook}, rendered by the add-book form. */
export type CreateBookState =
  | {
      /** Nothing submitted yet. */
      readonly status: "idle";
    }
  | {
      /** The book was stored. */
      readonly status: "ok";
      /** The stored title, echoed back to the form. */
      readonly title: string;
    }
  | {
      /** The submission was rejected. */
      readonly status: "error";
      /** A message safe to show to the user. */
      readonly error: string;
      /** Validation messages per field, when the input was invalid. */
      readonly fieldErrors?: Readonly<Partial<Record<"title" | "author", readonly string[]>>>;
    };

const NewBookSchema = z.object({
  title: z.string().trim().min(1, { error: "Title is required" }).max(200, { error: "Title is too long" }),
  author: z.string().trim().min(1, { error: "Author is required" }).max(200, { error: "Author is too long" }),
});

/**
 * Server Action creating a book from the add-book form. It is a public POST endpoint: it authenticates, validates
 * every field, writes through the data layer, and only then invalidates `/books`.
 *
 * @param _previous - the form's previous state (required by `useActionState`, unused)
 * @param formData - the submitted form
 * @returns `ok` with the stored title, or `error` with a message and per-field errors
 */
export async function createBook(_previous: CreateBookState, formData: FormData): Promise<CreateBookState> {
  const session = await getSession();
  if (!session) {
    return { status: "error", error: "Sign in required" };
  }
  const parsed = NewBookSchema.safeParse({ title: formData.get("title"), author: formData.get("author") });
  if (!parsed.success) {
    return {
      status: "error",
      error: "Check the highlighted fields",
      fieldErrors: z.flattenError(parsed.error).fieldErrors,
    };
  }
  const book = await addBook(parsed.data, session.userId);
  revalidatePath("/books");
  return { status: "ok", title: book.title };
}
```

File: `src/app/books/actions.ts` — when: `server`, `cc`

```ts
"use server";

import { updateTag } from "next/cache";
import { z } from "zod";

import { addBook, BOOKS_TAG } from "@/lib/books";
import { getSession } from "@/lib/session";

/** Result of {@link createBook}, rendered by the add-book form. */
export type CreateBookState =
  | {
      /** Nothing submitted yet. */
      readonly status: "idle";
    }
  | {
      /** The book was stored. */
      readonly status: "ok";
      /** The stored title, echoed back to the form. */
      readonly title: string;
    }
  | {
      /** The submission was rejected. */
      readonly status: "error";
      /** A message safe to show to the user. */
      readonly error: string;
      /** Validation messages per field, when the input was invalid. */
      readonly fieldErrors?: Readonly<Partial<Record<"title" | "author", readonly string[]>>>;
    };

const NewBookSchema = z.object({
  title: z.string().trim().min(1, { error: "Title is required" }).max(200, { error: "Title is too long" }),
  author: z.string().trim().min(1, { error: "Author is required" }).max(200, { error: "Author is too long" }),
});

/**
 * Server Action creating a book from the add-book form. It is a public POST endpoint: it authenticates, validates
 * every field, writes through the data layer, and only then expires the books cache tag (read-your-own-writes).
 *
 * @param _previous - the form's previous state (required by `useActionState`, unused)
 * @param formData - the submitted form
 * @returns `ok` with the stored title, or `error` with a message and per-field errors
 */
export async function createBook(_previous: CreateBookState, formData: FormData): Promise<CreateBookState> {
  const session = await getSession();
  if (!session) {
    return { status: "error", error: "Sign in required" };
  }
  const parsed = NewBookSchema.safeParse({ title: formData.get("title"), author: formData.get("author") });
  if (!parsed.success) {
    return {
      status: "error",
      error: "Check the highlighted fields",
      fieldErrors: z.flattenError(parsed.error).fieldErrors,
    };
  }
  const book = await addBook(parsed.data, session.userId);
  updateTag(BOOKS_TAG);
  return { status: "ok", title: book.title };
}
```

File: `src/app/books/actions.test.ts` — when: `server`

```ts
// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";

const getSession = vi.fn();
const addBook = vi.fn();
vi.mock("@/lib/session", () => ({ getSession: () => getSession() }));
vi.mock("@/lib/books", () => ({
  BOOKS_TAG: "books",
  addBook: (...args: unknown[]) => addBook(...args),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn(), updateTag: vi.fn() }));

import { revalidatePath, updateTag } from "next/cache";

import { createBook } from "./actions";

const form = (fields: Record<string, string>): FormData => {
  const formData = new FormData();
  for (const [name, value] of Object.entries(fields)) {
    formData.set(name, value);
  }
  return formData;
};

describe("createBook", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("rejects anonymous callers without writing or invalidating", async () => {
    getSession.mockResolvedValue(null);
    await expect(createBook({ status: "idle" }, form({ title: "T", author: "A" }))).resolves.toEqual({
      status: "error",
      error: "Sign in required",
    });
    expect(addBook).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
    expect(updateTag).not.toHaveBeenCalled();
  });

  it("returns field errors for invalid input without writing", async () => {
    getSession.mockResolvedValue({ userId: "u1" });
    const state = await createBook({ status: "idle" }, form({ title: "   " }));
    expect(state).toMatchObject({
      status: "error",
      fieldErrors: { title: ["Title is required"], author: [expect.any(String)] },
    });
    expect(addBook).not.toHaveBeenCalled();
  });

  it("stores the trimmed book for the session user and invalidates the list", async () => {
    getSession.mockResolvedValue({ userId: "u1" });
    addBook.mockResolvedValue({ id: "3", title: "Clean Code", author: "Robert C. Martin" });
    await expect(
      createBook({ status: "idle" }, form({ title: " Clean Code ", author: "Robert C. Martin" })),
    ).resolves.toEqual({ status: "ok", title: "Clean Code" });
    expect(addBook).toHaveBeenCalledWith({ title: "Clean Code", author: "Robert C. Martin" }, "u1");
    expect(vi.mocked(revalidatePath).mock.calls.length + vi.mocked(updateTag).mock.calls.length).toBe(1);
  });
});
```

File: `src/app/books/add-book-form.tsx` — when: `server`

```tsx
"use client";

import { useActionState } from "react";

import { createBook, type CreateBookState } from "./actions";

const initialState: CreateBookState = { status: "idle" };

/** Form creating a book through the `createBook` Server Action; it also works before JavaScript loads. */
export function AddBookForm() {
  const [state, formAction, pending] = useActionState(createBook, initialState);
  const fieldErrors = state.status === "error" ? state.fieldErrors : undefined;

  return (
    <form action={formAction} aria-label="Add a book">
      <div>
        <label htmlFor="title">Title</label>
        <input id="title" name="title" aria-describedby={fieldErrors?.title ? "title-error" : undefined} />
        {fieldErrors?.title ? <p id="title-error">{fieldErrors.title[0]}</p> : null}
      </div>
      <div>
        <label htmlFor="author">Author</label>
        <input id="author" name="author" aria-describedby={fieldErrors?.author ? "author-error" : undefined} />
        {fieldErrors?.author ? <p id="author-error">{fieldErrors.author[0]}</p> : null}
      </div>
      <button type="submit" disabled={pending}>
        {pending ? "Adding…" : "Add book"}
      </button>
      <p aria-live="polite">
        {state.status === "ok" ? `Added “${state.title}”` : state.status === "error" ? state.error : ""}
      </p>
    </form>
  );
}
```

File: `src/app/books/add-book-form.test.tsx` — when: `server`

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createBook = vi.fn();
vi.mock("./actions", () => ({
  createBook: (...args: unknown[]) => createBook(...args),
}));

import { AddBookForm } from "./add-book-form";

describe("AddBookForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("submits the fields and announces the stored book", async () => {
    createBook.mockResolvedValue({ status: "ok", title: "Clean Code" });
    const user = userEvent.setup();
    render(<AddBookForm />);

    await user.type(screen.getByLabelText("Title"), "Clean Code");
    await user.type(screen.getByLabelText("Author"), "Robert C. Martin");
    await user.click(screen.getByRole("button", { name: "Add book" }));

    expect(await screen.findByText("Added “Clean Code”")).toBeInTheDocument();
    const submitted = createBook.mock.calls[0]?.[1] as FormData;
    expect(submitted.get("title")).toBe("Clean Code");
  });

  it("shows the field errors returned by the action", async () => {
    createBook.mockResolvedValue({
      status: "error",
      error: "Check the highlighted fields",
      fieldErrors: { title: ["Title is required"] },
    });
    const user = userEvent.setup();
    render(<AddBookForm />);

    await user.click(screen.getByRole("button", { name: "Add book" }));

    expect(await screen.findByText("Title is required")).toBeInTheDocument();
    expect(screen.getByLabelText("Title")).toHaveAttribute("aria-describedby", "title-error");
    expect(screen.getByText("Check the highlighted fields")).toBeInTheDocument();
  });
});
```

## Client Component leaf

File: `src/components/like-button.tsx`

```tsx
"use client";

import { useState } from "react";

/** Props of {@link LikeButton}. */
export interface LikeButtonProps {
  /** What is being liked; part of the button's accessible name. */
  readonly label: string;
}

/** A toggle kept entirely in the browser — the sample Client Component leaf inside a Server Component page. */
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

File: `src/components/like-button.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";

import { LikeButton } from "./like-button";

it("toggles its pressed state on click", async () => {
  const user = userEvent.setup();
  render(<LikeButton label="Refactoring" />);
  const button = screen.getByRole("button", { name: "Like Refactoring" });

  expect(button).toHaveAttribute("aria-pressed", "false");
  await user.click(button);
  expect(button).toHaveAttribute("aria-pressed", "true");
  await user.click(button);
  expect(button).toHaveAttribute("aria-pressed", "false");
});
```

## Pages, metadata, and special files

File: `src/app/page.tsx`

```tsx
import Link from "next/link";

/** The home page — static (`○`). */
export default function Home() {
  return (
    <main>
      <h1>Welcome</h1>
      <p>This app was scaffolded with a sample book list that exercises every layer of the stack.</p>
      <Link href="/books">Browse the books</Link>
    </main>
  );
}
```

File: `src/app/page.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import Home from "./page";

it("links to the book list", () => {
  render(<Home />);
  expect(screen.getByRole("heading", { level: 1, name: "Welcome" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Browse the books" })).toHaveAttribute("href", "/books");
});
```

File: `src/app/books/page.tsx` — when: `server`

```tsx
import type { Metadata } from "next";
import Link from "next/link";

import { LikeButton } from "@/components/like-button";
import { listBooks } from "@/lib/books";

import { AddBookForm } from "./add-book-form";

/** Metadata of the book list. */
export const metadata: Metadata = {
  title: "Books",
  description: "Every book on the shelf.",
};

/** The book list: an async Server Component reading the data layer directly. */
export default async function BooksPage() {
  const books = await listBooks();

  return (
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
      <AddBookForm />
    </main>
  );
}
```

File: `src/app/books/page.tsx` — when: `static`

```tsx
import type { Metadata } from "next";
import Link from "next/link";

import { LikeButton } from "@/components/like-button";
import { listBooks } from "@/lib/books";

/** Metadata of the book list. */
export const metadata: Metadata = {
  title: "Books",
  description: "Every book on the shelf.",
};

/** The book list: an async Server Component, rendered to static HTML at build time. */
export default async function BooksPage() {
  const books = await listBooks();

  return (
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
  );
}
```

File: `src/app/books/page.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";

vi.mock("@/lib/books", () => ({
  listBooks: async () => [{ id: "1", title: "Refactoring", author: "Martin Fowler" }],
}));
// The server variant renders the add-book form; keep its Server Action out of this test.
vi.mock("./actions", () => ({ createBook: vi.fn() }));

import BooksPage, { metadata } from "./page";

it("renders every book with a link and a like button", async () => {
  render(await BooksPage());

  expect(screen.getByRole("heading", { level: 1, name: "Books" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Refactoring" })).toHaveAttribute("href", "/books/1");
  expect(screen.getByRole("button", { name: "Like Refactoring" })).toBeInTheDocument();
  expect(metadata.title).toBe("Books");
});
```

File: `src/app/books/[id]/page.tsx` — when: `server`

```tsx
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { getBook, listBooks } from "@/lib/books";

/** The params of a book page. */
export interface BookParams {
  /** The book id from the URL. */
  readonly id: string;
}

/**
 * Prerenders one page per known book at build time.
 *
 * @returns the `id` params of every book that exists at build time
 */
export async function generateStaticParams(): Promise<BookParams[]> {
  return (await listBooks()).map((book) => ({ id: book.id }));
}

/**
 * Title and description of a book page; unknown ids are marked `noindex`.
 *
 * @param props - the route props
 * @returns the page metadata
 */
export async function generateMetadata({ params }: PageProps<"/books/[id]">): Promise<Metadata> {
  const { id } = await params;
  const book = await getBook(id);
  return book
    ? { title: book.title, description: `${book.title} by ${book.author}` }
    : { title: "Book not found", robots: { index: false } };
}

/** One book; unknown ids render the nearest `not-found`. */
export default async function BookPage({ params }: PageProps<"/books/[id]">) {
  const { id } = await params;
  const book = await getBook(id);
  if (!book) {
    notFound();
  }

  return (
    <main>
      <h1>{book.title}</h1>
      <p>by {book.author}</p>
      <Link href="/books">All books</Link>
    </main>
  );
}
```

File: `src/app/books/[id]/page.tsx` — when: `static`

```tsx
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { getBook, listBooks } from "@/lib/books";

/** Only the ids returned by `generateStaticParams` exist in the export; anything else is the host's 404. */
export const dynamicParams = false;

/** The params of a book page. */
export interface BookParams {
  /** The book id from the URL. */
  readonly id: string;
}

/**
 * Prerenders one page per known book at build time.
 *
 * @returns the `id` params of every book that exists at build time
 */
export async function generateStaticParams(): Promise<BookParams[]> {
  return (await listBooks()).map((book) => ({ id: book.id }));
}

/**
 * Title and description of a book page; unknown ids are marked `noindex`.
 *
 * @param props - the route props
 * @returns the page metadata
 */
export async function generateMetadata({ params }: PageProps<"/books/[id]">): Promise<Metadata> {
  const { id } = await params;
  const book = await getBook(id);
  return book
    ? { title: book.title, description: `${book.title} by ${book.author}` }
    : { title: "Book not found", robots: { index: false } };
}

/** One book, rendered to static HTML at build time. */
export default async function BookPage({ params }: PageProps<"/books/[id]">) {
  const { id } = await params;
  const book = await getBook(id);
  if (!book) {
    notFound();
  }

  return (
    <main>
      <h1>{book.title}</h1>
      <p>by {book.author}</p>
      <Link href="/books">All books</Link>
    </main>
  );
}
```

File: `src/app/books/[id]/page.test.tsx`

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

import BookPage, { generateMetadata, generateStaticParams } from "./page";

const props = (id: string): PageProps<"/books/[id]"> => ({
  params: Promise.resolve({ id }),
  searchParams: Promise.resolve({}),
});

describe("book page", () => {
  it("renders a known book", async () => {
    render(await BookPage(props("1")));
    expect(screen.getByRole("heading", { level: 1, name: "Refactoring" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All books" })).toHaveAttribute("href", "/books");
  });

  it("calls notFound() for an unknown id", async () => {
    await expect(BookPage(props("999"))).rejects.toMatchObject({
      digest: expect.stringMatching(/^NEXT_HTTP_ERROR_FALLBACK;404/),
    });
  });

  it("prerenders every known id", async () => {
    await expect(generateStaticParams()).resolves.toEqual([{ id: "1" }]);
  });

  it("builds metadata for known and unknown ids", async () => {
    await expect(generateMetadata(props("1"))).resolves.toMatchObject({ title: "Refactoring" });
    await expect(generateMetadata(props("999"))).resolves.toMatchObject({ robots: { index: false } });
  });
});
```

File: `src/app/books/loading.tsx`

```tsx
/** Streaming fallback of the books segment. */
export default function Loading() {
  return <p role="status">Loading books…</p>;
}
```

File: `src/app/books/loading.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import Loading from "./loading";

it("announces that books are loading", () => {
  render(<Loading />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading books…");
});
```

File: `src/app/books/error.tsx`

```tsx
"use client";

/** Props Next.js passes to an `error.tsx` boundary. */
export interface BooksErrorProps {
  /** The error thrown while rendering; production builds expose only `digest`. */
  readonly error: Error & {
    /** Server-generated hash matching the server-side log entry. */
    readonly digest?: string;
  };
  /** Re-fetches and re-renders the segment. */
  readonly retry: () => void;
}

/** Error boundary of the books segment. */
export default function BooksError({ error, retry }: BooksErrorProps) {
  return (
    <main>
      <h1>Something went wrong</h1>
      <p>{error.digest ? `Reference: ${error.digest}` : "The books could not be loaded."}</p>
      <button type="button" onClick={() => retry()}>
        Try again
      </button>
    </main>
  );
}
```

File: `src/app/books/error.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";

import BooksError from "./error";

it("shows the digest and retries on demand", async () => {
  const retry = vi.fn();
  const user = userEvent.setup();
  render(<BooksError error={Object.assign(new Error("boom"), { digest: "abc123" })} retry={retry} />);

  expect(screen.getByText("Reference: abc123")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(retry).toHaveBeenCalledOnce();
});

it("falls back to a generic message without a digest", () => {
  render(<BooksError error={new Error("boom")} retry={vi.fn()} />);
  expect(screen.getByText("The books could not be loaded.")).toBeInTheDocument();
});
```

File: `src/app/not-found.tsx`

```tsx
import Link from "next/link";

/** Rendered by `notFound()` and for every unmatched URL. */
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

File: `src/app/not-found.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import NotFound from "./not-found";

it("offers a way back home", () => {
  render(<NotFound />);
  expect(screen.getByRole("heading", { level: 1, name: "Page not found" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Back home" })).toHaveAttribute("href", "/");
});
```

## Route Handlers

File: `src/app/api/books/[id]/route.ts` — when: `server`

```ts
import { getBook } from "@/lib/books";
import { getSession } from "@/lib/session";

const ID_PATTERN = /^\d{1,10}$/;

/**
 * `GET /api/books/:id` — one book as JSON. A public endpoint: 401 for anonymous callers, 400 for a malformed id,
 * 404 for an unknown one.
 *
 * @param _request - the incoming request (unused)
 * @param ctx - the route context carrying the `id` param
 * @returns the JSON response
 */
export async function GET(_request: Request, ctx: RouteContext<"/api/books/[id]">): Promise<Response> {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "Sign in required" }, { status: 401 });
  }
  const { id } = await ctx.params;
  if (!ID_PATTERN.test(id)) {
    return Response.json({ error: "Invalid id" }, { status: 400 });
  }
  const book = await getBook(id);
  if (!book) {
    return Response.json({ error: "Not found" }, { status: 404 });
  }
  return Response.json(book, { headers: { "Cache-Control": "private, no-store" } });
}
```

File: `src/app/api/books/[id]/route.test.ts` — when: `server`

```ts
// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";

const getSession = vi.fn();
vi.mock("@/lib/session", () => ({ getSession: () => getSession() }));
vi.mock("@/lib/books", () => ({
  getBook: async (id: string) => (id === "1" ? { id: "1", title: "Refactoring", author: "Martin Fowler" } : undefined),
}));

import { GET } from "./route";

const call = (id: string): Promise<Response> =>
  GET(new Request(`http://localhost/api/books/${id}`), {
    params: Promise.resolve({ id }),
  } as RouteContext<"/api/books/[id]">);

describe("GET /api/books/[id]", () => {
  beforeEach(() => {
    getSession.mockResolvedValue({ userId: "u1" });
  });

  it("returns 401 for anonymous callers", async () => {
    getSession.mockResolvedValue(null);
    expect((await call("1")).status).toBe(401);
  });

  it("returns 400 for a malformed id", async () => {
    expect((await call("abc")).status).toBe(400);
  });

  it("returns 404 for an unknown id", async () => {
    expect((await call("999")).status).toBe(404);
  });

  it("returns the book as a private, uncached response", async () => {
    const response = await call("1");
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("private, no-store");
    await expect(response.json()).resolves.toEqual({ id: "1", title: "Refactoring", author: "Martin Fowler" });
  });
});
```

File: `src/app/api/health/route.ts` — when: `server`

```ts
import { connection } from "next/server";

/**
 * `GET /api/health` — liveness probe for the container `HEALTHCHECK` and load balancers. Always rendered per
 * request (`connection()`), never cached.
 *
 * @returns `{ "status": "ok" }`
 */
export async function GET(): Promise<Response> {
  await connection();
  return Response.json({ status: "ok" }, { headers: { "Cache-Control": "no-store" } });
}
```

File: `src/app/api/health/route.test.ts` — when: `server`

```ts
// @vitest-environment node
import { expect, it, vi } from "vitest";

// The real connection() throws outside a request scope.
vi.mock("next/server", () => ({ connection: vi.fn(async () => undefined) }));

import { GET } from "./route";

it("reports ok without caching", async () => {
  const response = await GET();
  expect(response.status).toBe(200);
  expect(response.headers.get("cache-control")).toBe("no-store");
  await expect(response.json()).resolves.toEqual({ status: "ok" });
});
```

File: `src/app/books.json/route.ts` — when: `static`

```ts
import { listBooks } from "@/lib/books";

/** Required by `output: 'export'`: the handler runs once at build time and is written to `out/books.json`. */
export const dynamic = "force-static";

/**
 * `GET /books.json` — the book list as a static JSON file.
 *
 * @returns the JSON response
 */
export async function GET(): Promise<Response> {
  return Response.json(await listBooks());
}
```

File: `src/app/books.json/route.test.ts` — when: `static`

```ts
// @vitest-environment node
import { expect, it, vi } from "vitest";

vi.mock("@/lib/books", () => ({
  listBooks: async () => [{ id: "1", title: "Refactoring", author: "Martin Fowler" }],
}));

import { GET } from "./route";

it("serves the book list as JSON", async () => {
  const response = await GET();
  await expect(response.json()).resolves.toEqual([{ id: "1", title: "Refactoring", author: "Martin Fowler" }]);
});
```

## Playwright specs

File: `e2e/books.spec.ts` — when: `e2e`, `server`

```ts
import { expect, test } from "@playwright/test";

test("home links to the book list", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Browse the books" }).click();
  await page.waitForURL("**/books");
  await expect(page.getByRole("heading", { level: 1, name: "Books" })).toBeVisible();
});

test("adds a book through the Server Action", async ({ page }) => {
  const title = `E2E book ${Date.now()}`;
  await page.goto("/books");
  await page.getByLabel("Title").fill(title);
  await page.getByLabel("Author").fill("Playwright");
  await page.getByRole("button", { name: "Add book" }).click();

  await expect(page.getByText(`Added “${title}”`)).toBeVisible();
  await expect(page.getByRole("link", { name: title })).toBeVisible();
});

test("validates the form on the server", async ({ page }) => {
  await page.goto("/books");
  await page.getByRole("button", { name: "Add book" }).click();
  await expect(page.getByText("Title is required")).toBeVisible();
});

test("renders a book page and the not-found page", async ({ page }) => {
  await page.goto("/books/1");
  await expect(page.getByRole("heading", { level: 1, name: "The Pragmatic Programmer" })).toBeVisible();
  await page.goto("/books/999");
  await expect(page.getByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
});

test("API routes answer with the documented status codes", async ({ request }) => {
  expect((await request.get("/api/health")).status()).toBe(200);
  expect((await request.get("/api/books/1")).status()).toBe(200);
  expect((await request.get("/api/books/abc")).status()).toBe(400);
  expect((await request.get("/api/books/999")).status()).toBe(404);
});
```

File: `e2e/books.spec.ts` — when: `e2e`, `static`

```ts
import { expect, test } from "@playwright/test";

test("home links to the book list", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Browse the books" }).click();
  await page.waitForURL("**/books/");
  await expect(page.getByRole("heading", { level: 1, name: "Books" })).toBeVisible();
});

test("the like button works after hydration", async ({ page }) => {
  await page.goto("/books/");
  const like = page.getByRole("button", { name: "Like Refactoring" });
  await like.click();
  await expect(like).toHaveAttribute("aria-pressed", "true");
});

test("every known book has a prerendered page", async ({ page }) => {
  await page.goto("/books/1/");
  await expect(page.getByRole("heading", { level: 1, name: "The Pragmatic Programmer" })).toBeVisible();
});

test("the static JSON route is exported", async ({ request }) => {
  const response = await request.get("/books.json");
  expect(response.status()).toBe(200);
  expect(await response.json()).toHaveLength(2);
});
```
