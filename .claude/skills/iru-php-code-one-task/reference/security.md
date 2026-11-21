# Security

Rules for any task that touches request input, rendered output, SQL, files, outgoing HTTP, authentication or
authorization, or secrets. The flavor files name the platform API for each rule (`laravel.md`, `wordpress.md`,
`prestashop.md`); this file is the platform-neutral principle and the plain-PHP form. Every item here is something
a later gate or reviewer flags (PHPStan/Psalm taint analysis, `WordPress.Security.*`/`WordPress.DB.*` sniffs, Pest
`arch()->preset()->security()`), and every one is a test case too (`testing.md`).

## Input: validate with an allow-list, on the server

- **Validate, don't "sanitize into shape".** Check type, length, format, and range against an explicit allow-list
  and reject what doesn't match — `filter_var($v, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]])`,
  `in_array($v, $allowed, true)`, an enum's `tryFrom()`, the framework's validator. Browser validation is a
  convenience, not a control.
- **Sanitizing is for when validation can't be specific** (free text): normalize, don't mangle. Store input as
  typed; never rely on stripping characters as an injection defense.
- **Never pass a whole request array onward** (`$_POST`, `$request->all()`, `Tools::getAllValues()`) into a model,
  query, or option. Pick the named fields you validated.
- **Cast numeric IDs** (`(int)`, `absint()`) at the boundary, and still check the referenced row belongs to the
  current user/shop/tenant (broken object-level authorization is the commonest real bug).
- Don't `unserialize()` untrusted data (object injection) — use `json_decode(…, flags: JSON_THROW_ON_ERROR)`;
  if unavoidable, `['allowed_classes' => false]`. Never `eval`, `assert` with strings, `extract($_REQUEST)`,
  `preg_replace` with `/e`, or variable-variables/`call_user_func` on user-controlled names.

## Output: escape late, for the context

- **Escape at the point of output**, for the context it lands in — HTML text, HTML attribute, URL, JavaScript,
  CSS. Plain PHP: `htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')`, `rawurlencode()` for URL parts,
  `json_encode($v, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_THROW_ON_ERROR)` for data
  handed to JavaScript.
- Templates escape by default (Blade `{{ }}`, Twig autoescape, PrestaShop front-office Smarty); the raw forms
  (`{!! !!}`, `|raw`, Smarty `nofilter`, back-office Smarty without `|escape`) are only for HTML the application itself produced and already sanitized with an HTML
  allow-list (`wp_kses_post()`, HTML Purifier) — never for user input.
- Validate URLs you output for scheme (`http`/`https` only) — `javascript:` survives HTML escaping.

## SQL: prepared statements, allow-listed identifiers

- **Every value goes through a bound parameter**: PDO `prepare()`/`execute()` (keep `PDO::ERRMODE_EXCEPTION`,
  set `PDO::ATTR_EMULATE_PREPARES => false`), Eloquent/query builder bindings, `$wpdb->prepare()`,
  PrestaShop `(int)`/`pSQL()`.
- **Identifiers can't be bound** — column names for sorting, table names, `ASC`/`DESC` come from an allow-list
  (`match ($sort) { 'title' => 'title', 'year' => 'published_year', default => 'id' }`), never from input.
- **`LIKE`**: bind the whole pattern and escape the wildcards in the user's part. Portable form: escape with a
  non-backslash character and declare it — `strtr($q, ['!' => '!!', '%' => '!%', '_' => '!_'])` with
  `LIKE :q ESCAPE '!'` (backslash defaults differ: SQLite has no default escape character, and MySQL's own string
  literal consumes a backslash). Framework helpers: `$wpdb->esc_like()` (WordPress, for MySQL).
- `whereRaw`/`DB::raw`/`orderByRaw` and string-built `DbQuery::where()` clauses are injection points: only with
  bindings or allow-listed fragments.

```php
<?php

declare(strict_types=1);

$sort = match ($_GET['sort'] ?? '') {
    'title' => 'title',
    'year' => 'published_year',
    default => 'id',
};
$stmt = $pdo->prepare("SELECT id, title FROM books WHERE title LIKE :q ESCAPE '!' ORDER BY {$sort}");
$stmt->execute(['q' => '%' . strtr((string) ($_GET['q'] ?? ''), ['!' => '!!', '%' => '!%', '_' => '!_']) . '%']);
```

## Authentication, sessions, CSRF

- **Passwords**: `password_hash($p, PASSWORD_DEFAULT)` / `password_verify()` / `password_needs_rehash()` (Laravel
  `Hash::make()`; WordPress `wp_hash_password()`). Never `md5`/`sha1`/home-made salts. Compare secrets and tokens
  with `hash_equals()`, never `===`.
- **Random values** for tokens/IDs: `random_bytes()`/`bin2hex(random_bytes(32))`, `random_int()` — never
  `rand()`, `mt_rand()`, `uniqid()`.
- **Every state-changing request is CSRF-protected** — POST/PUT/PATCH/DELETE only (never change state on GET),
  with the framework's token (Laravel CSRF middleware, WordPress nonces, PrestaShop admin tokens).
- **Authorization is checked on the server for every action** — the policy, the capability, the employee
  permission. Authentication ("who") is not authorization ("may they"); a CSRF token/nonce is neither.
- Regenerate the session ID at login (`session_regenerate_id(true)`), rate-limit login, reset, and public APIs.

## Files and uploads

- Check the upload error code, **detect type from content** (`finfo_file()`), not from the client's name/MIME;
  enforce a size limit; allow-list extensions.
- **Store under a generated name, outside the web root** (or on a private disk); never use the client's filename
  in a path. Re-encode images if they will be served.
- **Paths from input**: resolve with `realpath()` and confirm the result starts with the allowed base directory
  (path traversal); `basename()` alone isn't enough for nested paths.
- Never `include`/`require` a path built from input.

## Outgoing HTTP and SSRF

- **Every outgoing call has a timeout** (connect + total) and a bounded retry.
- **Never fetch a URL taken from input without an allow-list** of hosts (and scheme), checked after parsing;
  block private/link-local ranges if arbitrary hosts are a requirement. Disable or re-check redirects.
- TLS verification stays on (`verify`/`sslverify` true).

## Secrets, errors, logging

- **Secrets come from the environment or a secrets store** (Laravel `config()` reading `env()` in `config/` only;
  WordPress constants in `wp-config.php`; PrestaShop `Configuration` for module settings) — never in code, never
  in the repository, never in a test fixture that looks real.
- Show errors to developers, not visitors (`display_errors=Off`, `APP_DEBUG=false`, `WP_DEBUG_DISPLAY` off in
  production). Exception messages shown to users carry no SQL, paths, or stack traces.
- **Don't log secrets or personal data** (passwords, tokens, full card numbers, session IDs).
