# The admin

Read this when the task touches `admin.py`, a `ModelAdmin`, an inline, an admin action, or an admin template. The
admin is for trusted staff curation — never the end-user UI. Customer-facing features get real views.

## Registering and the change list

```python
@admin.register(Book)
class BookAdmin(admin.ModelAdmin[Book]):
    list_display = ("title", "author", "status", "review_count")
    list_select_related = ("author",)                   # name the fields; never True (deprecated in 6.1)
    list_filter = ("status",)
    search_fields = ("^title",)                         # ^ prefix → indexed startswith
    autocomplete_fields = ("author",)                   # AuthorAdmin needs search_fields
    readonly_fields = ("created_at",)
    show_full_result_count = False
    ordering = ("title",)

    def get_queryset(self, request: HttpRequest) -> QuerySet[Book]:
        return super().get_queryset(request).annotate(review_count=Count("reviews"))

    @admin.display(description="Reviews", ordering="review_count")
    def review_count(self, obj: Book) -> int:
        count: int = getattr(obj, "review_count", 0)    # annotated in get_queryset; typed without an ignore
        return count

    @admin.action(description="Publish selected books", permissions=["change"])
    def publish(self, request: HttpRequest, queryset: QuerySet[Book]) -> None:
        count = services.publish_books(book_ids=list(queryset.values_list("pk", flat=True)))
        self.message_user(request, f"Published {count} books.")
```

- `@admin.register(Model)` in the app's `admin.py`; `admin.site.unregister(User)` first when replacing a built-in
  registration. `admin.ModelAdmin[Book]` type-checks, but **only runs if the settings call
  `django_stubs_ext.monkeypatch()`** — otherwise it raises `TypeError: type 'ModelAdmin' is not subscriptable` at
  import (verified). Follow `code-style.md`'s rule for runtime-generic Django classes.
- **N+1 in the change list** is the common admin defect: `list_select_related` with named fields, or annotate in
  `get_queryset()` (and set `ordering`); `__str__` methods that follow relations need the join too.
- HTML in `list_display` only via `format_html()`, never `mark_safe()` on model data. `@admin.display` for callables.
- `get_*` hooks return a **new** list (`[*super().get_readonly_fields(request, obj), "x"]`), never mutate super's.
- `save_model()`/`delete_model()` still call `super()` and are not validation hooks — validate in the form's
  `clean()`; inline-created objects go through `save_formset()`; bulk delete through `delete_queryset()`.
- **Actions** declare `permissions=[...]` and call the same service the rest of the app uses; `queryset.update()`
  skips `save()` and signals (and isn't recorded in `LogEntry`). Object-level checks call
  `has_change_permission(request, obj)` themselves.
- Row scoping for staff with partial access: `get_queryset()` and `formfield_for_foreignkey()`; object-level
  `has_view_permission` is not a visibility filter. Superusers bypass everything — test with an ordinary staff user.

## Custom admin pages and templates

Extra views via `get_urls()` returning `custom + super().get_urls()` (custom first), each wrapped in
`self.admin_site.admin_view(view)` **and** an explicit permission check inside. Template overrides under
`templates/admin/<app>/<model>/change_form.html`, overriding one `{% block %}` and keeping `{{ block.super }}`; no
inline `<script>` under CSP — static files through `Media`.

## Security notes

The admin URL comes from settings/env when the project moved it (`path(settings.ADMIN_URL, admin.site.urls)`); never
hard-code a new `/admin/` path. `is_staff` only opens the door — permissions and groups decide. Don't expose admindocs.

## Tests for this area

`TestCase`/pytest with an ordinary staff user given the needed permissions (`force_login`): the change list renders
with a fixed query count (`django_assert_num_queries` around `client.get(reverse("admin:catalog_book_changelist"))`),
an action changes what it should for permitted users and is refused/hidden otherwise, anonymous users are redirected
to the admin login. `manage.py check` (run by the group) catches misconfiguration such as `admin.E040`
(autocomplete without `search_fields`).
