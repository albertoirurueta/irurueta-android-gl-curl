# Views, URLs, templates, and middleware

Read this when the task adds or changes a server-rendered view, URL pattern, template, template tag/filter,
middleware, or htmx fragment. JSON APIs go through DRF or Ninja (`drf.md`, `ninja.md`), not template views or
hand-built `JsonResponse`s. When the task adds or changes a screen, component or list, also read `ui-contract.md` and
`ui-implementation.md` — how to decompose a page into components, build lists, and write the browser UI tests.

## Choosing a view

- **Generic CBVs** (`ListView`, `DetailView`, `CreateView`, `UpdateView`, `DeleteView`, `FormView`) when the page fits
  the shape and the differences are a queryset, template, form, or hook. **FBVs** for one-off logic, webhooks, and
  heavy branching. Plain `View` for verb separation. Use the simplest clear view; keep inheritance shallow.
- **Thin**: forms validate, QuerySets query, services hold business rules. A view parses input, calls one thing, and
  renders or redirects. No business logic, no aggregation, no ORM loops.
- Explicit context; never `locals()`.

## Class-based views

(`UpdateView[Review, ReviewForm]` needs `django_stubs_ext.monkeypatch()` in the settings to run — drop the
subscript otherwise; see `code-style.md`.)

```python
class ReviewUpdateView(LoginRequiredMixin, SuccessMessageMixin, UpdateView[Review, ReviewForm]):
    form_class = ReviewForm
    template_name = "reviews/review_form.html"
    success_message = "Review updated."

    def get_queryset(self) -> QuerySet[Review]:
        # Row-level scoping: other users' reviews are a 404, never editable.
        return Review.objects.filter(reviewer=self.request.user).select_related("book")

    def get_success_url(self) -> str:
        return reverse("reviews:review-detail", kwargs={"pk": self.object.pk})
```

- **Access mixins go leftmost** (`LoginRequiredMixin`, `PermissionRequiredMixin`, `UserPassesTestMixin`) — a mixin on
  the right of the base view never runs, and access mixins fail open. Combine checks into one `test_func()`.
- **Row restrictions in `get_queryset()`** (call `super()` then filter by the user); ownership in `form_valid()`:
  `form.instance.reviewer = self.request.user` **before** `return super().form_valid(form)`; extra form arguments via
  `get_form_kwargs()`.
- `fields = [...]` **or** `form_class` (with explicit `Meta.fields`), never `"__all__"`, never both.
- `success_url = reverse_lazy(...)` at class level, or `get_success_url()`; `get_context_data()` always starts with
  `context = super().get_context_data(**kwargs)`.
- Per-request state in `setup()`/locals, never mutable class attributes; `as_view(**kwargs)` is evaluated once at
  import. `http_method_names` as an allow-list where it matters. `DeleteView` logic goes in `form_valid()`.
- `paginate_by` needs an ordered queryset with a total key (`order_by("-published", "-pk")`). Every list page is
  paginated (or cursor-paged) — never an unbounded loop over a whole queryset (`ui-implementation.md` §4).
- Decorate a CBV with `@method_decorator([...], name="dispatch")` or wrap `as_view()` in the URLconf.

## Function-based views

```python
@login_required
@require_POST
def book_reserve(request: HttpRequest, public_id: uuid.UUID) -> HttpResponse:
    book = get_object_or_404(Book.objects.visible_to(request.user), public_id=public_id)
    form = ReserveForm(request.POST)
    if not form.is_valid():
        return render(request, "catalog/book_detail.html", {"book": book, "form": form}, status=422)
    try:
        services.reserve_copies(book_id=book.pk, quantity=form.cleaned_data["quantity"])
    except services.OutOfStockError:
        form.add_error("quantity", _("Not enough copies in stock."))
        return render(request, "catalog/book_detail.html", {"book": book, "form": form}, status=422)
    messages.success(request, _("Reserved."))
    return redirect(book)                       # Post/Redirect/Get
```

- `render()` (never `render_to_response`), `get_object_or_404(<scoped queryset>, …)`, `redirect("app:name", …)` or
  `redirect(obj)`. Avoid `get_list_or_404()` for legitimately empty lists.
- **State changes only on POST** (`@require_POST`, `@require_http_methods([...])`, `@require_safe` for read-only) with
  CSRF, then redirect. Never `if request.POST:`, never raw `request.POST["x"]` — bind a form.
- Decorator order: gatekeepers (`require_*`, `login_required`) outermost; `cache_control(private=True)` /
  `never_cache` for user-dependent pages, never `public=True`.
- Custom decorators must handle async views (`inspect.iscoroutinefunction`) and use `functools.wraps`.
- With `LoginRequiredMiddleware` (5.1+) every view is private by default; mark genuinely public ones
  `@login_not_required` (`method_decorator(login_not_required, name="dispatch")` on CBVs) — signup, landing,
  health checks, webhooks.

## Async views

Only for I/O fan-out (several upstream HTTP calls with `asyncio.gather`), streaming/SSE, or long-polling under ASGI;
a sync project stays sync. In `async def`: async ORM (`aget`, `acreate`, `async for`), `await request.auser()`, no
lazy relation access (`SynchronousOnlyOperation`), no blocking libraries, no `transaction.atomic()` — wrap the
transactional unit in a sync function and `await sync_to_async(fn)(...)`. Forms that touch the ORM:
`await sync_to_async(form.is_valid)()`. A CBV is all-async or all-sync; sync mixins break async dispatch.

## URLs

```python
app_name = "catalog"

urlpatterns = [
    path("books/", views.BookListView.as_view(), name="book-list"),
    path("books/new/", views.BookCreateView.as_view(), name="book-create"),     # literal before slug routes
    path("books/<uuid:public_id>/", views.BookDetailView.as_view(), name="book-detail"),
    path("books/<uuid:public_id>/reserve/", views.book_reserve, name="book-reserve"),
]
```

- `path()` with converters; `re_path()` only when needed; no leading `/` (`urls.W002`). `app_name` per app, included
  with `include("apps.catalog.urls")`; reverse with `"catalog:book-detail"`.
- **Name every pattern** you link to; `reverse()` / `{% url %}` / `get_absolute_url()` everywhere, `reverse_lazy()` in
  class attributes and module constants — never hard-coded paths.
- Literal routes before generic slug/converter routes. Pass `View.as_view()`, not the class.
- Error handlers (`handler404(request, exception)`, `handler500(request)`) live in the root URLconf; never echo
  `str(exception)`.

## Templates

- App templates in `<app>/templates/<app>/…` (namespaced); project-wide ones in the root `templates/`.
  `{% extends %}` first, `{% block %}` for layout, `{% include "…" with x=y only %}`, an inclusion tag, or 6.0
  `{% partialdef %}` for fragments — components with explicit inputs, composed rather than copied
  (`ui-implementation.md` §2); `{% load %}` in every template that needs it (not inherited).
- The base layout writes `<html lang="{{ LANGUAGE_CODE }}" dir="{{ LANGUAGE_BIDI|yesno:'rtl,ltr' }}">` and CSS uses
  logical properties (`margin-inline-start`, `text-align: start`), so right-to-left languages render correctly.
- **"Stupid" templates**: no queries in loops (`book.reviews.count` per row is N+1 — annotate in the view), no
  sorting/aggregation (`|length` on a QuerySet evaluates it), no business rules (expose a model property or a view
  boolean). Set `alters_data = True` on model methods that mutate.
- Auto-escaping stays on: never `|safe`/`{% autoescape off %}`/`mark_safe` on user data; build HTML in Python with
  `format_html()`. JSON for JavaScript via `{{ data|json_script:"data-id" }}`.
- `{% url 'catalog:book-detail' public_id=book.public_id %}` (quoted name, keyword arguments); `{% static %}` for
  assets, never `/static/...`; `{% csrf_token %}` in every POST form; `{% querystring page=… %}` (5.1+) to keep filters
  across pages.
- i18n: `{% load i18n %}`, `{% translate %}`, `{% blocktranslate count n=… %}`; whole sentences with named
  placeholders.
- CSP (6.0+): no inline `<script>`/`onclick`; `nonce="{{ csp_nonce }}"` / 6.1 `{% csp_nonce_attr %}` when the
  project enables nonces.

### Template tags and filters

Code in `<app>/templatetags/<app>_extras.py` with a module-level `register = template.Library()`. Smallest helper
that works: `@register.filter` (one value in/out, fail soft), `@register.simple_tag` (output auto-escaped),
`@register.inclusion_tag("app/_fragment.html")`, `@register.simple_block_tag` (5.2). HTML via `format_html()`;
`is_safe=True` only when no HTML-significant characters can be introduced. No queries in tags rendered inside loops.

### htmx and partials (6.0+)

One view, two renderings: `template = "catalog/book_list.html" + ("#table" if request.htmx else "")` with
`django-htmx` installed, **and `Vary: HX-Request`** (`patch_vary_headers(response, ["HX-Request"])`) so caches don't
mix bodies. CSRF on every htmx request (`<body hx-headers='{"x-csrftoken": "{{ csrf_token }}"}'>`). Fragment
endpoints are real endpoints: authentication, scoped `get_object_or_404`, no state change via `hx-get`. Invalid form
re-render with status 422.

## Middleware

Only when many views need the behavior (otherwise a decorator or mixin). A factory taking `get_response`; one-time
setup in `__init__`; **no per-request state on `self`** (one instance serves concurrent requests) — use
`request.<attr>`. Declare `sync_capable`/`async_capable` (or `@sync_and_async_middleware`); check
`response.streaming` before touching content; use `request.get_host()`. Order matters: `SecurityMiddleware` first,
WhiteNoise right after it, `SessionMiddleware` before `AuthenticationMiddleware`, `CorsMiddleware` before
`CommonMiddleware`. Adding middleware is a settings change — only when the task asks.

## Responses

`JsonResponse(dict)` only for tiny internal endpoints (a model or QuerySet raises `TypeError` — serialize first);
`FileResponse(f, as_attachment=True, filename=…)` for downloads (no `with` block; never a path built from input);
`StreamingHttpResponse` for large bodies (sync iterator under WSGI, async under ASGI); CSV with `csv.writer(response)`
and formula-injection-safe cells (prefix `=`, `+`, `-`, `@` values). Slow exports belong in a background task.

## Tests for this area

Test client for routing/middleware/templates (`assertTemplateUsed`, `assertContains`, `assertRedirects(...,
fetch_redirect_response=False)`), `RequestFactory` for view logic in isolation (set `request.user` yourself),
`assertNumQueries` around `client.get()` for every list page, `Client(enforce_csrf_checks=True)` to prove a
token-less POST is 403, `HX-Request: true` header tests for fragments (body is the fragment, `Vary` is set). These stay browser-free and
take no screenshots; screens and components additionally get browser UI tests (`ui-implementation.md` §5).
