# Security

Rules for any task that touches request input, rendered output, raw SQL, uploads, redirects, CORS/CSP/CSRF, outgoing
email or HTTP, or secrets — **always** read for a new view or API endpoint. Every item here is something a later gate
(Ruff `S`/Bandit, `DJ` rules, `check --deploy`) or a reviewer flags, and every one is a test case too (`testing.md`).
Authentication and authorization rules are in `auth.md`.

## Input

- **Everything goes through a form, serializer, or Ninja schema with explicit fields** — never `fields = "__all__"`/
  `exclude`, never the raw `request.POST`/`request.data`/`request.GET` dict passed into `create(**…)`, `filter(**…)`,
  `update(**…)`, or `order_by(…)`. Server-side fields (`owner`, `is_staff`, `price`, `featured`) are set in code.
- **Allow-list identifiers**: sort keys, filter field names, and `values()` columns map through a dict
  (`{"title": "title", "newest": "-created_at"}`), never straight from the query string.
- Ids from the URL are looked up through a **scoped** queryset (`auth.md`); converters (`<int:pk>`, `<uuid:public_id>`)
  reject garbage before the view runs.
- Never `pickle`/`eval`/`exec`/`yaml.load` (use `yaml.safe_load`) on untrusted data; JSON only.

## Output

- Templates auto-escape — keep it on. No `|safe`, `{% autoescape off %}`, or `mark_safe()` on anything containing
  user data. Build HTML in Python with `format_html()`/`format_html_join()`. Data for JavaScript via `json_script`.
- URLs you render from user data are checked for scheme (`http`/`https` only).
- `JsonResponse` only of explicit dicts/lists — never a model's `__dict__` (leaks hashes, flags, foreign keys).
- CSV exports neutralize formula cells (values starting `=`, `+`, `-`, `@`). Error pages never echo exceptions.

## SQL

The ORM first; `F()`/`Q()`/expressions next; raw SQL (`Manager.raw()`, `RawSQL`, `cursor.execute`) only with
`params` — **never f-strings, `%`-formatting, or `.format()` into SQL** (Ruff `S608`). Database functions (`Func`
templates) take expressions or `Value(...)`, never interpolated input. Since 6.1 aliases are quoted; still never pass
user input as an alias.

## CSRF and state changes

- State changes only on POST/PUT/PATCH/DELETE, with CSRF: `{% csrf_token %}` in every POST form, `X-CSRFToken` from
  JavaScript (htmx: `hx-headers` on `<body>`), DRF `SessionAuthentication` enforces it for logged-in browser clients.
- **Never `@csrf_exempt` to fix a 403.** The only legitimate exemption is a machine endpoint with its own
  authentication — a webhook that verifies an HMAC signature (`hmac.compare_digest`) before doing anything.
- CORS (`django-cors-headers`) is an explicit origin allow-list scoped to the API prefix, never `*` with credentials,
  and doesn't replace CSRF. Changing CORS/CSP/security settings is a settings change — only when the task asks.

## Redirects

`next`/`return_to` values go through `url_has_allowed_host_and_scheme()`; `RedirectView`/`redirect()` targets come from
`reverse()` of a key, never a raw user URL (open redirect).

## Uploads and files

`FileField`/`ImageField` with an `upload_to` **callable** generating server-side names (`covers/<pk>/<uuid4 hex>.png`) —
never the client filename; `FileExtensionValidator` allow-list **plus** a content check (Pillow `verify()` and format
check for images) and a size check; iterate `chunks()`, never `read()` whole; private files served by a
permission-checking view (`FileResponse(..., as_attachment=True)` + `X-Content-Type-Options: nosniff`) or signed
storage URLs — never from `MEDIA_URL` for private data. Never build a filesystem path from request input (path
traversal). Deleting a row doesn't delete its file — `on_commit` cleanup if the task needs it.

## Outgoing email and HTTP

- Email addresses built with `email.headerregistry.Address(display_name=..., addr_spec=...)`, never f-strings; visitors
  never control recipients, subject, or body of an email they can trigger; rate-limit anything an anonymous user can
  trigger. Send after commit (`on_commit`) or from a task.
- Outgoing HTTP always has a `timeout`; URLs fetched on a user's behalf are validated against an allow-list (SSRF);
  never forward the user's cookies/tokens.

## Secrets and errors

Secrets come from the environment (`settings.md`), never literals in code, tests, fixtures, or migrations (Ruff
`S105`/`S106` flag hard-coded passwords — test passwords use `secrets.token_urlsafe(16)` or factory defaults).
Sensitive values never reach logs or error reports (`@sensitive_variables`, `@sensitive_post_parameters`). No
`DEBUG = True` or `ALLOWED_HOSTS = ["*"]` outside local settings.

## Headers and CSP

`SecurityMiddleware`, `XFrameOptionsMiddleware`, and (6.0+) `ContentSecurityPolicyMiddleware` stay in place. New
templates contain no inline `<script>`/`style=`/`onclick` under a CSP — static files, or nonces where the project
uses them. Per-view CSP changes via `@csp_override` only when the task asks. Never full-page-cache a response that
carries a CSP nonce or a CSRF token.

## Tests for this area

Hostile input is rejected or escaped (`<script>` in a title renders escaped — `assertContains(response, "&lt;script&gt;")`);
a token-less POST with `Client(enforce_csrf_checks=True)` gets 403; `owner`/`is_staff` in a payload are ignored; an
unknown sort key falls back to the default instead of erroring; another user's object is 404; an open-redirect `next`
is ignored; an upload with a bad extension or a disguised file is rejected.
