# User interfaces — Django templates

How `ui-contract.md` (read it first) maps onto server-rendered Django: templates, template tags, partials, HTMX, and
browser UI tests with pytest-playwright. `views-urls-templates.md` keeps the rules about views, URLs, escaping and
CSP; `forms-and-validation.md` the rules about forms. This file covers structure, collections, the UI tests, the
screenshots and the accessibility checks. JSON-only endpoints (DRF, Ninja) have no UI and are not covered here.

## 1. What a screen and a component are here

| Contract term | Django |
|---|---|
| **Screen** | A named URL route + its view + a page template that `{% extends %}` the base layout (`templates/base.html`). The view (or its service) owns the data; the template only renders it. |
| **Region** | A `{% block %}` of the base layout (`header`, `content`, `sidebar`, `footer`) or a landmark element (`<header>`, `<nav>`, `<main>`, `<aside>`) inside the page template. |
| **Component** | A template partial with a typed entry point: an **inclusion tag** (`@register.inclusion_tag("catalog/_book_row.html")`) or a plain `{% include "catalog/_book_row.html" with book=book only %}`; inside one template, a Django 6.0+ `{% partialdef %}` fragment. `django-components` / `django-cotton` components only where already a dependency. |
| **Event** | A link or form POST to a named URL (`{% url 'catalog:book-reserve' public_id=… %}`), or an HTMX request (`hx-get`/`hx-post` to a named URL) whose response is a partial. |
| **Navigation** | The URLconf (`urls.py` with `app_name`) is the one navigation table; templates link only through `{% url %}` / `get_absolute_url()`. Flows are documented from it. |

Name component templates after the component with a leading underscore (`_book_row.html`) in the app's namespaced
template directory, and the inclusion tag after the component (`book_row`), so `<Id>` in the screenshots (`BookRow`)
maps to one file.

## 2. Composition — includes, inclusion tags, partials, blocks

- **Inclusion tags are the typed component API.** The tag's signature *is* the component's inputs; its docstring
  lists purpose, inputs, events (the URLs/HTMX endpoints it targets) and visual states. It receives values, never
  the request, and never queries — the view prefetches (`select_related`/`prefetch_related`) before rendering:

  ```python
  @register.inclusion_tag("catalog/_book_row.html")
  def book_row(book: Book, *, selected: bool = False) -> dict[str, object]:
      """BookRow: one book in a list.

      Inputs: ``book`` (author prefetched), ``selected``. Events: link to ``catalog:book-detail``.
      States: default, selected, long-content.
      """
      return {"book": book, "selected": selected}
  ```

- **`{% include %}` always ends with `only`** (`{% include "catalog/_book_row.html" with book=book only %}`) so the
  partial sees just its inputs, not the whole page context — the template equivalent of an explicit props list.
- **Template partials (Django 6.0+, built in, no `{% load %}`)**: `{% partialdef rows inline %}…{% endpartialdef %}`
  defines and renders a named fragment in place; `{% partial rows %}` re-renders it; a view or HTMX response renders
  only the fragment with `"catalog/book_list.html#rows"` as the template name. On Django < 6.0 use a separate
  `_book_rows.html` include (or `django-template-partials` if already installed).
- **Slots** are `{% block %}`s: a layout or wrapper template declares `{% block body %}{% endblock %}` and the caller
  fills it by extending; `simple_block_tag` (5.2+) wraps caller content (`{% card %}…{% endcard %}`). Never copy a
  page template to tweak it, and keep `{% extends %}` chains to layout → page (one level of page inheritance).
- `django-components` / `django-cotton`: follow their component/slot APIs only when the project already depends on
  them; never add one for a task.

## 3. Inputs, events and two-way binding

- **State down**: the view computes display-ready values (formatted totals, flags such as `can_edit`) and passes
  them in the context; templates don't aggregate or decide permissions (`views-urls-templates.md`).
- **Events up**: an action is a link (`GET`, safe) or a form `POST` with `{% csrf_token %}` to a named URL, named for
  the intent (`book-reserve`, not `book-post`), followed by Post/Redirect/Get. With HTMX in the project, the same
  endpoint answers `hx-post`/`hx-get` with a partial: detect it with `request.headers.get("HX-Request") == "true"`
  (or `request.htmx` from `django-htmx`), render the `#fragment`, and `patch_vary_headers(response, ["HX-Request"])`.
  Stimulus controllers / Alpine (`x-data`) only where the repository already uses them; client code emits DOM
  events named for the intent, never calls the server directly from a presentational partial.
- **Forms are the two-way-bound controls**: a bound `Form` round-trips the user's values and errors
  (`{{ form.title.as_field_group }}`); nothing else is two-way. Invalid → re-render with status 422.
- **Visual states** in templates: `empty` with `{% for … %}{% empty %}…{% endfor %}` or an explicit branch, `error`
  from form errors/messages, `loading` with an `htmx-indicator`, `disabled` with the `disabled` attribute (not a CSS
  class), `selected` with `aria-current`/`aria-selected`.
- Every string through `{% translate %}`/`{% blocktranslate %}`; CSS with logical properties
  (`margin-inline-start`, `padding-inline`, `text-align: start`) so the RTL rendering needs no second stylesheet.

## 4. Collections — paginate, one item partial per row

The server-rendered row of the contract's §5 table. Never an unbounded `{% for %}` over a whole queryset.

- **Page the queryset in the view**: `ListView.paginate_by` (or `Paginator(qs, per_page)` +
  `paginator.get_page(request.GET.get("page"))`) over a queryset with a total order (`order_by("title", "pk")`);
  cursor/keyset pagination (`filter(pk__gt=last_pk)[:n]`, or the project's cursor paginator) for large or
  fast-changing tables where `COUNT(*)` and `OFFSET` hurt.
- **Fetch item data up front**: `select_related(...)`/`prefetch_related(...)` for every relation the item partial
  shows; a query budget test proves it (`testing.md`).
- **One item partial per row**, keyed by a stable id (`id="book-{{ book.pk }}"`), and a separate partial per item
  type for heterogeneous lists.
- **"Load more" / infinite scroll** returns only the rows fragment for the next page; the list keeps its URL
  (`{% querystring page=… %}`, 5.1+) so a full reload shows the same page.

```python
class BookListView(ListView[Book]):
    paginate_by = 20
    template_name = "catalog/book_list.html"

    def get_queryset(self) -> QuerySet[Book]:
        return Book.objects.visible_to(self.request.user).select_related("author").order_by("title", "pk")

    def get_template_names(self) -> list[str]:
        if self.request.headers.get("HX-Request") == "true":
            return ["catalog/book_list.html#rows"]        # 6.0+ partial: only the next page's rows
        return [self.template_name]
```

```django
{% extends "base.html" %}{% load i18n catalog_ui %}
{% block content %}
<ul id="book-list">
  {% partialdef rows inline %}
    {% for book in page_obj %}{% book_row book %}{% empty %}<li>{% translate "No books yet." %}</li>{% endfor %}
    {% if page_obj.has_next %}
      <li><a href="{% querystring page=page_obj.next_page_number %}"
             hx-get="{% querystring page=page_obj.next_page_number %}" hx-target="closest li" hx-swap="outerHTML">
        {% translate "Load more" %}</a></li>
    {% endif %}
  {% endpartialdef %}
</ul>
{% endblock %}
```

(The view adds `patch_vary_headers(response, ["HX-Request"])` in `render_to_response` when caching is in play.)
Selection and expansion live in the URL (query string) or the session, keyed by id — never only in the DOM of a row
that the next "load more" may re-render.

## 5. UI tests — pytest-playwright against `live_server`

Template-rendering unit tests stay as they are: Django's test client, `assertTemplateUsed`, `assertContains`,
`assertNumQueries` and `HX-Request` fragment tests (`views-urls-templates.md`) — fast, no browser, **no
screenshots**. On top of them, screens and components get **browser UI tests** with pytest-playwright (sync API)
against pytest-django's `live_server` (Cypress or Selenium only where the repository already uses them):

| Level | How |
|---|---|
| Component | Render the inclusion tag/partial with fixture values (`render_to_string`), wrap it in a harness template that extends the base layout, serve it with `page.route(f"{live_server.url}/__ui__/component", …)` so static files still resolve, then capture each state. |
| Screen | Arrange rows with factories, `page.goto(live_server.url + reverse(...))`; assert states, that links/forms target the right named URLs, and that HTMX swaps insert the right partial. |
| Flow | Drive the real pages through a flow (sign in → create → review → confirm) with `page.get_by_role(...)`; capture each step as `flow/<flow-name>/NN-<step>--…`. External services faked as in the fast tests. |

Layout and wiring:

- Tests in `tests/ui/test_<thing>_ui.py`, fixtures in `tests/ui/conftest.py`, the helper in
  `tests/ui/ui_screenshots.py` (§6). Every module: `pytestmark = [pytest.mark.ui, pytest.mark.django_db(transaction=True)]`
  (`live_server` serves from another thread, so the data must be committed). The `ui` marker is registered in the
  pytest `markers`, and the fast selection excludes it (`-m "not integration and not ui"`, mirroring how the
  project excludes `integration`).
- `tests/ui/conftest.py` sets `os.environ.setdefault("DJANGO_ALLOW_ASYNC_UNSAFE", "true")` at import time:
  pytest-playwright's sync API runs an event loop in the test thread, so Django's async-safety guard would otherwise
  raise `SynchronousOnlyOperation` on every ORM call the test makes. It also overrides the session
  `browser_context_args` fixture for determinism: fixed `viewport`, `locale`, `timezone_id`, `reduced_motion="reduce"`.
- **This skill writes the UI tests but never runs them** (they start a live server and a browser): the group runs
  `iru-python-test ui` behind `.integration-test.lock` (Step 6 of SKILL.md). Browsers come from
  `uv run playwright install chromium`; adding pytest-playwright/axe-playwright-python to a project that lacks them
  is a dependency change to report, not to make (SKILL.md Step 4).

### The matrix: states × directions × themes

Behaviour assertions run once (ltr, light); captures cover every `.ui-quality.yml` direction × theme:

- **Direction is rendered by the server**, never faked by flipping an attribute: `LocaleMiddleware` + an RTL language
  in `LANGUAGES` (`ar`), and the base template writes
  `<html lang="{{ LANGUAGE_CODE }}" dir="{{ LANGUAGE_BIDI|yesno:'rtl,ltr' }}">` from `{% get_current_language %}` /
  `{% get_current_language_bidi %}`. The test selects the language the way the app does: an `i18n_patterns` prefix
  (`reverse()` under `translation.override("ar")` gives `/ar/…`; the prefix wins over everything else), else Django's
  language cookie (`settings.LANGUAGE_COOKIE_NAME`) — **not** `Accept-Language` via `set_extra_http_headers`, which
  Chromium replaces on navigations with the context locale (verified). If the project has no RTL language or no
  `LocaleMiddleware`, report it — adding i18n is a plan-level change.
- **Dark**: `page.emulate_media(color_scheme="dark")` (the CSS must use `prefers-color-scheme` or a class the test
  can set). **High contrast**: `emulate_media(forced_colors="active")`, one capture per screen.

```python
pytestmark = [pytest.mark.ui, pytest.mark.django_db(transaction=True)]

FIXTURES = {"empty": [], "typical-data": [("Dune", "Frank Herbert"), ("مدن الملح", "عبد الرحمن منيف")],
            "many-items": [(f"Book {n:03}", "Author") for n in range(45)]}


@pytest.mark.parametrize("state", list(FIXTURES))
def test_book_list_screen_states(page: Page, live_server: LiveServer, state: str) -> None:
    for title, author in FIXTURES[state]:
        BookFactory.create(title=title, author__name=author)
    quality = ui_quality()
    for direction in quality["directions"]:
        with translation.override(language_for(direction)):
            url = live_server.url + reverse("catalog:book-list")
        for theme in quality["themes"]:
            apply_direction_and_theme(page, direction, theme)
            page.goto(url)
            if state == "many-items":
                expect(page.locator(".book-row")).to_have_count(20)   # one page, never the whole table
            capture_ui_state(page, "screen", "BookListScreen", state, direction, theme)
            check_accessibility(page)
```

## 6. The shared helper — `tests/ui/ui_screenshots.py`

One file; every UI test imports from it and never builds a screenshot path itself. It exposes:

- `ui_quality()` — reads `.ui-quality.yml` at the repository root (the nearest parent holding `.git`, else the
  working directory) with a tiny line parser (PyYAML only if already a dependency); missing file ⇒
  `{"accessibility": "enforce", "directions": ["ltr", "rtl"], "themes": ["light", "dark"]}`.
- `screenshots_dir()` — `UI_SCREENSHOTS_DIR` when set, else `<repo root>/.ui-screenshots`.
- `apply_direction_and_theme(page, direction, theme)` — sets the language cookie for `localhost`/`127.0.0.1` (the
  `live_server` hosts; cookies ignore the port) and `emulate_media(color_scheme=…, forced_colors=…,
  reduced_motion="reduce")`; navigate afterwards. `language_for(direction)` gives `ar` or `LANGUAGE_CODE` for URLs
  reversed under `translation.override`.
- `capture_ui_state(page, kind, ui_id, state, direction, theme, *variants)` — validates the segments (kind in
  `component|screen|flow`, kebab-case state, `ltr|rtl`, `light|dark|high-contrast`), **asserts the page really
  renders `dir=direction`**, then writes `<dir>/<kind>/<ui_id>/<state>--<direction>--<theme>[--<variant>…].png`.
- `check_accessibility(page)` — axe-core at the `.ui-quality.yml` level (§7).

```python
def capture_ui_state(page: Page, kind: str, ui_id: str, state: str, direction: str, theme: str, *variants: str) -> Path:
    """Write <dir>/<kind>/<ui_id>/<state>--<direction>--<theme>[--<variant>…].png for the current page."""
    if kind not in KINDS or direction not in DIRECTIONS or theme not in THEMES:
        raise ValueError(f"bad screenshot key: {kind}/{direction}/{theme}")
    for segment in (state, *variants):
        if not _SEGMENT.match(segment):                     # ^(\d{2}-)?[a-z0-9]+(-[a-z0-9]+)*$
            raise ValueError(f"segment must be kebab-case: {segment!r}")
    rendered = page.evaluate("document.documentElement.dir || 'ltr'")
    if rendered != direction:
        raise AssertionError(f"page renders dir={rendered!r}, expected {direction!r}")
    path = screenshots_dir() / kind / ui_id / ("--".join((state, direction, theme, *variants)) + ".png")
    path.parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(path), full_page=True, animations="disabled", caret="hide")
    return path
```

(The parameter is `ui_id`, not `id`, so Ruff's `A002` doesn't flag a shadowed builtin; positional calls read the
same.) A component harness:

```python
def show_component(page: Page, base_url: str, direction: str, html: SafeString) -> None:
    """Serve `html` inside the base layout at a fake URL on the live server's origin."""
    with translation.override(language_for(direction)):
        document = render_to_string("ui_harness.html", {"component_html": html})  # {% extends "base.html" %}
    url = f"{base_url}/__ui__/component"
    page.route(url, lambda route: route.fulfill(body=document, content_type="text/html; charset=utf-8"))
    page.goto(url)

html = render_to_string("catalog/_book_row.html", book_row(BookFactory.build(pk=1), selected=True))
show_component(page, live_server.url, direction, format_html("<ul>{}</ul>", html))
```

`render_to_string` returns a `SafeString`; wrap it with `format_html`, never an f-string (an f-string drops the safe
mark and the harness shows escaped markup — verified) and never `|safe` on anything user-supplied.

## 7. Accessibility — axe-playwright-python and `.ui-quality.yml`

`axe-playwright-python` (`from axe_playwright_python.sync_playwright import Axe`) bundles axe-core and exposes
`Axe().run(page, context=None, options=...) -> AxeResults`; `results.response` is axe's raw result dict
(`response["violations"]` → `id`, `impact`, `nodes`), `results.violations_count` the number of violated rules, and
`results.generate_snapshot()` one `rule-id (impact) : nodes` line per rule. Passing `options` replaces the default
`{"resultTypes": ["violations"]}`, so repeat it:

```python
_AXE_OPTIONS = {"runOnly": {"type": "tag", "values": ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]},
                "resultTypes": ["violations"]}


def check_accessibility(page: Page) -> list[str]:
    level = ui_quality()["accessibility"]
    if level in ("off", "none"):
        return []
    results = Axe().run(page, options=_AXE_OPTIONS)
    violations = results.response["violations"]
    found = [f"{v['id']}×{len(v['nodes'])}" for v in violations]
    report = screenshots_dir() / "a11y-report.jsonl"           # read by iru-python-test for rule-id counts
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("a", encoding="utf-8") as out:
        out.write(json.dumps({"test": os.environ.get("PYTEST_CURRENT_TEST", ""), "url": page.url, "level": level,
                              "violations": [{"id": v["id"], "impact": v["impact"], "nodes": len(v["nodes"])}
                                             for v in violations]}, ensure_ascii=False) + "\n")
    if level == "enforce":
        assert results.violations_count == 0, f"accessibility violations: {', '.join(found)}"
    return found
```

`enforce` fails the test with the rule ids in the first line; `report` records and never fails (no `warnings.warn`
— projects that turn warnings into errors would fail it); `off` skips the scan, while the coding defaults of the
contract's §8 still apply. Run it after every capture, in every theme — contrast differs between light and dark.

## Assistive technologies

Applies unless `.ui-quality.yml` says `accessibility: none` (opted out entirely - then omit these attributes and checks). Rules and levels: `reference/ui-contract.md` §8 ("Platform assistive-technology rules", "Visual comfort").

- Prefer native semantic elements (`button`, `a`, `nav`, `main`, `label`/`input`, headings in order) over `div` + `role`; add `aria-*` only where HTML has no equivalent.
- Icon-only controls get `aria-label`; images get `alt` (empty `alt=""` when decorative); dynamic updates use `aria-live`; modals trap and restore focus.
- Honour `prefers-reduced-motion` and `forced-colors`; size text in `rem`, never fixed `px` heights that clip on zoom.
- Templates: base template with `<main>`, `<nav aria-label>`, skip-link and `lang`/`dir` on `<html>`; form fields rendered with `<label for>` and `aria-describedby` pointing at `errorlist`; messages in a `role="status"`/`alert` region.

## Sources

- Django: templates and the template language <https://docs.djangoproject.com/en/6.1/ref/templates/language/>,
  built-in tags incl. `partialdef`/`partial` and `querystring` <https://docs.djangoproject.com/en/6.1/ref/templates/builtins/>,
  custom tags (inclusion tags, `simple_block_tag`) <https://docs.djangoproject.com/en/6.1/howto/custom-template-tags/>,
  pagination <https://docs.djangoproject.com/en/6.1/topics/pagination/>, translation and `get_current_language_bidi`
  <https://docs.djangoproject.com/en/6.1/topics/i18n/translation/>, `LocaleMiddleware` and language selection
  <https://docs.djangoproject.com/en/6.1/topics/i18n/translation/#how-django-discovers-language-preference>,
  async safety (`DJANGO_ALLOW_ASYNC_UNSAFE`) <https://docs.djangoproject.com/en/6.1/topics/async/#async-safety>
- irurueta docs: `web/django/template-partials-and-htmx.adoc`, `templates.adoc`, `custom-template-tags-and-filters.adoc`,
  `pagination.adoc`, `internationalization-and-translation.adoc`, `pytest-factories-and-browser-tests.adoc`
- pytest-django `live_server`: <https://pytest-django.readthedocs.io/en/latest/helpers.html#live-server>
- pytest-playwright: <https://playwright.dev/python/docs/test-runners>; Playwright Python `emulate_media`,
  `screenshot`, `route`: <https://playwright.dev/python/docs/api/class-page>
- axe-playwright-python: <https://github.com/pamelafox/axe-playwright-python>; axe-core rule tags:
  <https://github.com/dequelabs/axe-core/blob/develop/doc/API.md#axe-core-tags>
- HTMX: <https://htmx.org/docs/>; django-htmx: <https://django-htmx.readthedocs.io/>

Verified in a throwaway uv project (Python 3.13, Django 6.1.2, pytest 9.1.1, pytest-django 4.14.0,
pytest-playwright 0.10.0, playwright 1.64.0 with Chromium headless shell 156, axe-playwright-python 0.1.8 bundling
axe-core 4.12.1): a base layout writing `<html lang dir>` from `LANGUAGE_BIDI`, an inclusion-tag `BookRow`, a
paginated `ListView` with a 6.0 `partialdef` rows fragment returned for `HX-Request`, and `ui` tests capturing the
ltr/rtl × light/dark matrix (plus `high-contrast`) for a screen and a component with axe in `enforce` (an `<img>`
without `alt` and an empty `<button>` failed as `button-name×1, image-alt×1`), `report` (passed, recorded) and `off`.
