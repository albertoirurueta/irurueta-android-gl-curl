# Authentication, users, passwords, and authorization

Read this when the task touches the user model, login/logout/sign-up/password flows, password hashing, permissions,
groups, or object-level access.

## The user model

- Always reference it indirectly: `settings.AUTH_USER_MODEL` in model fields and signal senders (import time),
  `get_user_model()` at runtime. Never import `django.contrib.auth.models.User` in project code.
- The project should already have a custom user (`accounts.User(AbstractUser)`, `AUTH_USER_MODEL = "accounts.User"`).
  **If it doesn't and the task needs one, stop and report** — switching `AUTH_USER_MODEL` after the first migration
  is a manual, data-touching migration project (`InconsistentMigrationHistory` otherwise), not a task-sized change.
  Non-auth user data goes in a profile model with a `OneToOneField` in that case.
- Adding fields to an existing custom user is an ordinary model change (`models-and-orm.md`). Email login
  (`username = None`, `USERNAME_FIELD = "email"`) needs a custom `BaseUserManager` with `create_user`/
  `create_superuser`, a case-insensitive `UniqueConstraint(Lower("email"), …)`, `get_by_natural_key` using `__iexact`
  (and its async twin), and `REQUIRED_FIELDS` without the username field.
- Users are created with `create_user()` / `set_password()` — never assign `user.password`. `create_user()` doesn't
  run password validators: call `validate_password(raw, user)` yourself in sign-up/reset flows that bypass the forms.

## Login, logout, sign-up, password flows

- Built-in views first: `path("accounts/", include("django.contrib.auth.urls"))` with the project's own
  `registration/*.html` templates; `LOGIN_URL`, `LOGIN_REDIRECT_URL`, `LOGOUT_REDIRECT_URL` set.
- `authenticate()` then `login(request, user)` (it rotates the session key). **Logout is POST-only** (GET is 405 since
  5.0) — a form with `{% csrf_token %}`, never a link.
- Sign-up forms subclass `BaseUserCreationForm`/`UserCreationForm` with explicit `fields` — never `is_staff`,
  `is_superuser`, `groups`, or `user_permissions`.
- A custom password-change view calls `update_session_auth_hash(request, user)`. Reset links depend on the host —
  `ALLOWED_HOSTS` is never `["*"]`.
- Every `next`/redirect target is validated with `url_has_allowed_host_and_scheme(url, allowed_hosts={request.get_host()},
  require_https=request.is_secure())`.
- Failed logins are logged via `user_login_failed` (username only, never the password). Social login, MFA, email
  verification: django-allauth if the project has it — never hand-rolled.
- Async: `aauthenticate`, `alogin`, `alogout`, `await request.auser()`.

## Passwords

Keep the project's `PASSWORD_HASHERS` (PBKDF2 default, or Argon2 first). **MD5 only in test settings.** Never remove a
hasher that stored hashes still use. Never log, email, or put passwords/hashes in URLs or custom columns.

## Authorization

- **Authentication isn't authorization.** `LoginRequiredMixin`/`@login_required`/`LoginRequiredMiddleware` say *who*;
  permissions and queryset scoping say *what*.
- Model permissions: `user.has_perm("catalog.publish_book")`, custom ones in `Meta.permissions = [("publish_book",
  "Can publish book")]` (needs a migration), bundled into `Group`s. A typo is silently `False` for normal users and
  silently `True` for superusers — tests use ordinary users.
- Views: `@permission_required("catalog.publish_book", raise_exception=True)` (without `raise_exception`, logged-in
  users are bounced to the login page) or `PermissionRequiredMixin` — **leftmost** in the bases, after
  `LoginRequiredMixin`. Other rules in one `UserPassesTestMixin.test_func()`.
- **Queryset scoping is the main defense against IDOR**: one `visible_to(user)`/`owned_by(user)` QuerySet method used by
  every list, detail, API, export, admin, and task path; `get_object_or_404(Book.objects.visible_to(user), pk=pk)` →
  other users' rows are **404**, not 403. Scope related data too. UUIDs are not authorization; hiding a button is not
  access control.
- Never trust an owner/user id from the request: `form.instance.owner = request.user`,
  `serializer.save(owner=request.user)`.
- Object-level permissions (django-guardian, django-rules) only if the project already uses them.
- The permission cache is per user instance — re-fetch the user after granting permissions in a test.

## Tests for this area

Cover every actor: anonymous (302 to login / 401 / 403), authenticated without the permission (403 or 404), with it,
owner, stranger. Assert the **effect** (`refresh_from_db()` shows nothing changed on denial) plus a positive control.
`force_login()` everywhere except tests of the login flow itself (`client.login()` / posting the form); passwords via
`secrets.token_urlsafe(16)`; `assertRedirects(..., fetch_redirect_response=False)`; GET logout → 405; an open-redirect
`next=https://evil.example` is ignored.
