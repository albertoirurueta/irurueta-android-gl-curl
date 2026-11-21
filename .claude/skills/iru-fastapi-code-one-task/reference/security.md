# Security

Read this when the task touches authentication, authorization, passwords, tokens, API keys, CORS, proxies,
secrets, ownership checks — or adds an endpoint that should not be public. Follow the mechanisms the project
already has; the rules below say what "correct" means for each.

## The chain: extract → authenticate → authorize

`fastapi.security` classes (`OAuth2PasswordBearer`, `HTTPBearer`, `APIKeyHeader`, `HTTPBasic`, `OpenIdConnect`)
only **extract** credentials and declare the OpenAPI scheme — `Depends(HTTPBearer())` accepts any string. Validation
is your dependency chain:

```python
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")


def get_current_user_id(
    token: Annotated[str, Depends(oauth2_scheme)], settings: SettingsDep
) -> int:
    try:
        return int(decode_subject(token, settings))
    except (jwt.InvalidTokenError, ValueError):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        ) from None


CurrentUserId = Annotated[int, Depends(get_current_user_id)]
```

- **401 + `WWW-Authenticate`** for missing/invalid credentials (security classes do this themselves for missing
  ones since 0.122.0 — tests expecting 403 there are outdated); **403** for an authenticated caller lacking
  permission. One generic message for every authentication failure (no "unknown user" vs. "wrong password").
- **A new endpoint is protected unless the task says it's public.** If the task doesn't say and the router isn't
  already guarded, ask (Step 5) rather than guess. Protect whole routers with `dependencies=[...]` where possible
  so a forgotten parameter can't open a route; global dependencies don't cover `/docs`, `/openapi.json`, or mounts.
- Expose the principal through an alias (`CurrentUser`, `CurrentUserId`); never stash it on a module global or a
  dependency instance.

## JWTs (PyJWT) and passwords (pwdlib)

- **PyJWT** (`import jwt`), not python-jose; **pwdlib** with Argon2 (`PasswordHash.recommended()`), not passlib. If
  the project still uses the old libraries, follow it within the module and report it — migrating is its own task.
- Signing key: a required `SecretStr` setting (no default, ≥ 256 bits — `openssl rand -hex 32`), read with
  `.get_secret_value()` only where you sign/verify.
- Encode `{"sub": str(user_id), "iat": now, "exp": now + ttl}` with `now = datetime.now(UTC)`; `sub` must be a
  **string** (PyJWT ≥ 2.10 raises `InvalidSubjectError` otherwise). No secrets or PII in claims — JWTs are signed,
  not encrypted.
- Decode with a pinned **`algorithms=[settings.jwt_algorithm]` allow-list** (never from the token header) and
  `options={"require": ["exp", "sub"]}`; pass `audience=`/`issuer=` when tokens carry them. Catch
  `jwt.InvalidTokenError` once and map to 401. External IdPs: `PyJWKClient` (a blocking fetch — `def` dependency),
  `PyJWKClientConnectionError` → 503, require `exp`/`iss`/`aud`/`sub`.
- Argon2 hashing is CPU- and memory-heavy: call `hash`/`verify` from `def` code (threadpool) or
  `run_in_threadpool`, never directly in `async def`. Verify against a dummy hash for unknown users to equalize
  timing. Login endpoints (`OAuth2PasswordRequestForm`, form-encoded) need rate limiting.
- Short access tokens (5–30 min); revocation needs a `jti` store or a per-user "valid after" checked against `iat`.
  Tokens never in URLs (except a seconds-lived WebSocket ticket — `streaming-files-and-websockets.md`).

## Authorization

- **Scopes**: `OAuth2PasswordBearer(tokenUrl=..., scopes={...})` documents them; enforcement is
  `Security(get_current_user, scopes=["books:write"])` + a `SecurityScopes` parameter that checks every required
  scope is in the token's space-delimited `scope` claim → 403 with `WWW-Authenticate: Bearer scope="..."`. Plain
  `Depends(get_current_user)` enforces no scopes. `/token` grants the intersection of requested and allowed scopes.
- **Permissions/roles**: endpoints ask for a permission, never a role string; a parameterized dependency
  (`RequirePermission("book:write")`) maps roles → permissions in one place. No `if user.role == "admin"`
  scattered in handlers.
- **Object level (IDOR/BOLA)** — the most common real bug: load the object **with** the owner condition
  (`WHERE id = :id AND owner_id = :uid`, or a load-then-check in the service) and take the owner from the token,
  **never from the body or query**. Someone else's object is a 404 (hides existence) unless existence is public
  (then 403). One ownership dependency (`OwnedBook = Annotated[Book, Depends(get_owned_book)]`) reused by GET,
  PATCH, PUT, DELETE — and a test for each.
- **Mass assignment**: input models with `extra="forbid"` that don't declare server-controlled fields
  (`validation-and-schemas.md`).
- **Data exposure**: response models are allow-lists; hashes, tokens, internal flags never leave
  `...InDB`/table models.

## API keys and Basic auth

- `APIKeyHeader(name="X-API-Key")` (never the query); keys generated with `secrets.token_urlsafe(32)`, stored
  hashed, compared with `secrets.compare_digest` on **bytes**. Basic auth only for internal tools over HTTPS:
  compare username and password both (no short-circuit), credentials from settings, fail closed when unset.
- Never `==` on secrets, never secrets in OpenAPI descriptions/examples/defaults.

## CORS, proxies, docs

- CORS (`CORSMiddleware`) configured **once**, with an explicit origin list from settings; never
  `allow_origins=["*"]` together with `allow_credentials=True` (Starlette then reflects any origin). Bearer tokens
  need `Authorization` in `allow_headers`, not `allow_credentials`. CORS is a browser control, not access control,
  and doesn't apply to WebSockets.
- Middleware order (outermost first): `TrustedHostMiddleware`, `HTTPSRedirectMiddleware`, CORS, custom, GZip — the
  last added is the outermost. Request-context middleware (request IDs, tracing) is pure ASGI, not
  `BaseHTTPMiddleware`.
- Trust `X-Forwarded-*` only from the proxy (`--forwarded-allow-ips`); use `request.client.host`, never the raw
  header, for rate limiting and audit.
- Private APIs: `FastAPI(openapi_url=None)` or docs behind auth via settings — hiding docs or
  `include_in_schema=False` is not access control.

## Injection, files, outbound calls

- SQL only through bound parameters (`persistence.md`); `yaml.safe_load`; no `pickle`/`eval` on input; Jinja2 with
  autoescape, never `|safe` on user input.
- Paths from input: resolve and check `target.is_relative_to(BASE)`; generate stored filenames
  (`streaming-files-and-websockets.md`).
- Outbound requests to user-supplied URLs need a host allow-list (SSRF) and a timeout.
- Ruff's `S` rules (Bandit) run in the group's quality gate: `S105`/`S106` hard-coded passwords, `S608` SQL built
  from strings, `S113` requests without timeout, `S311` `random` for tokens (use `secrets`). Don't silence one
  with `# noqa` unless the task says so and you explain why.
