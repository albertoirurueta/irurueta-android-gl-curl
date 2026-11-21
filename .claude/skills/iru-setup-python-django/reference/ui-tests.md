# Browser UI tier: `.ui-quality.yml`, `tests/ui/`

The catalog's UI contract (repeated in `iru-django-code-one-task/reference/ui-contract.md`, mapped onto Django in its
`ui-implementation.md`) asks every screen to be rendered in a real browser across left-to-right/right-to-left ×
light/dark, captured into `.ui-screenshots/`, and checked with axe-core at the level `.ui-quality.yml` sets. The
scaffold is API-centric, but it ships the Django admin, so it gets a small, working UI tier from day one: the shared
helper every later UI test imports, the fixtures, and one screen test of the admin login page (`AdminLoginScreen`,
states `default` and `error`). Written for every `api`/`database` choice — none of these files carry tags.

Supporting changes in the other templates (already in `project-templates.md` / `settings-templates.md`):

- dev group: `pytest-playwright>=0.10` and `axe-playwright-python>=0.1.8` (`uv add --group dev …`, Step 4);
- `[tool.pytest.ini_options] markers` registers `ui`; `[[tool.mypy.overrides]]` sets `ignore_missing_imports` for
  `axe_playwright_python` (it ships no `py.typed`; the rest of the tier type-checks under `strict`);
- `config/settings/base.py` adds `django.middleware.locale.LocaleMiddleware` (after `SessionMiddleware`): the
  `django_language` cookie (or `Accept-Language`) activates a language, and the admin's `base.html` writes
  `<html lang dir>` from `LANGUAGE_BIDI`, so the right-to-left capture is the server's real RTL rendering, not a
  flipped attribute. With no request preference it stays on `LANGUAGE_CODE` (`en-us`), so nothing else changes.

How the tier stays out of the fast tier: `tests/ui/conftest.py` **deselects** every `ui`-marked test unless the `-m`
expression names `ui`, so `uv run pytest`, `-m "not integration"` (the coverage gate, CI's `tests` job) and
`-m integration` never start a browser; `uv run pytest -m ui` runs exactly the UI tier. The UI tests never count
toward the 80% coverage floor.

Run it locally with `uv run playwright install chromium` once, then `uv run pytest -m ui` (CI:
`uv run playwright install --with-deps chromium`). It starts a live server and a browser, so the coding/test skills
serialize it behind `.integration-test.lock` like the integration tier.

## `.ui-quality.yml`

Written at the repository root — exactly the contract's shape (§8), `accessibility` from Step 0 (default `enforce`):

```yaml
# .ui-quality.yml — at the repository root; written by the iru-setup-* scaffolds, read by the coding and test skills.
accessibility: enforce      # enforce: violations fail the UI tests | report: listed, never failing | off: not run
directions: [ltr, rtl]      # screenshot matrix directions
themes: [light, dark]       # screenshot matrix themes
```

With `accessibility: report` or `off`, replace only the value (`enforce` → `report`/`off`) and keep the comments.

## `tests/ui/__init__.py`

```python
"""Tests."""
```

## `tests/ui/ui_screenshots.py`

The shared helper — same API as `iru-django-code-one-task/reference/ui-implementation.md` §6, so later UI tests
written by the coding skill import it unchanged.

```python
"""Shared helper for the browser UI tier: screenshot matrix and accessibility.

Every UI test resolves its settings through this one module and never builds a
screenshot path itself:

* ``ui_quality()`` reads ``.ui-quality.yml`` at the repository root (missing
  file or key: ``enforce``, ``[ltr, rtl]``, ``[light, dark]``).
* ``screenshots_dir()`` is ``$UI_SCREENSHOTS_DIR`` or
  ``<repository root>/.ui-screenshots``.
* ``apply_direction_and_theme()`` sets Django's language cookie and emulates the
  colour scheme, so the server renders ``<html dir="rtl">`` for real
  (``LocaleMiddleware`` + ``LANGUAGE_BIDI``).
* ``capture_ui_state()`` writes
  ``<dir>/<kind>/<ui_id>/<state>--<direction>--<theme>[--<variant>...].png``.
* ``check_accessibility()`` runs axe-core (``axe-playwright-python``) at the
  configured level and appends one JSON line per scan to
  ``<dir>/a11y-report.jsonl`` (read by the test gate).
"""

import json
import os
import re
from functools import cache
from pathlib import Path
from typing import Any, TypedDict

from axe_playwright_python.sync_playwright import Axe
from django.conf import settings
from playwright.sync_api import Page

KINDS = frozenset({"component", "screen", "flow"})
DIRECTIONS = frozenset({"ltr", "rtl"})
THEMES = frozenset({"light", "dark", "high-contrast"})
LEVELS = frozenset({"enforce", "report", "off", "none"})
RTL_LANGUAGE = "ar"  # any bidi language Django ships translations for
LIVE_SERVER_HOSTS = ("localhost", "127.0.0.1")  # cookies ignore the port
_AXE_OPTIONS = {
    "runOnly": {
        "type": "tag",
        "values": ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"],
    },
    "resultTypes": ["violations"],
}
_SEGMENT = re.compile(r"^(\d{2}-)?[a-z0-9]+(-[a-z0-9]+)*$")
_LIST = re.compile(r"^\[(.*)\]$")


class UiQuality(TypedDict):
    """The repository's UI-quality settings (``.ui-quality.yml``)."""

    accessibility: str
    directions: list[str]
    themes: list[str]


def repository_root() -> Path:
    """Return the nearest directory holding ``.git``, else the working directory."""
    start = Path.cwd().resolve()
    for directory in (start, *start.parents):
        if (directory / ".git").exists():
            return directory
    return start


@cache
def ui_quality() -> UiQuality:
    """Read ``.ui-quality.yml`` with a tiny ``key: value`` line parser.

    Returns:
        The configured settings; defaults for a missing file or key.

    Raises:
        ValueError: A value is outside the documented vocabulary.
    """
    quality = UiQuality(
        accessibility="enforce", directions=["ltr", "rtl"], themes=["light", "dark"]
    )
    path = repository_root() / ".ui-quality.yml"
    if not path.is_file():
        return quality
    for raw in path.read_text(encoding="utf-8").splitlines():
        key, _, value = raw.split("#", 1)[0].partition(":")
        key, value = key.strip(), value.strip()
        listed = _LIST.match(value)
        items = [v.strip() for v in listed.group(1).split(",")] if listed else []
        if key == "accessibility" and value:
            quality["accessibility"] = value
        elif key == "directions" and items:
            quality["directions"] = items
        elif key == "themes" and items:
            quality["themes"] = items
    if (
        quality["accessibility"] not in LEVELS
        or not set(quality["directions"]) <= DIRECTIONS
        or not set(quality["themes"]) <= THEMES
    ):
        msg = f"Unsupported value in {path}: {quality}"
        raise ValueError(msg)
    return quality


def screenshots_dir() -> Path:
    """Return ``$UI_SCREENSHOTS_DIR`` or ``<repository root>/.ui-screenshots``."""
    override = os.environ.get("UI_SCREENSHOTS_DIR")
    return Path(override) if override else repository_root() / ".ui-screenshots"


def language_for(direction: str) -> str:
    """Return the language rendered for ``direction`` (``ar`` or ``LANGUAGE_CODE``)."""
    return RTL_LANGUAGE if direction == "rtl" else settings.LANGUAGE_CODE


def apply_direction_and_theme(page: Page, direction: str, theme: str) -> None:
    """Select the direction's language and emulate the theme; navigate afterwards.

    The language travels in Django's language cookie: Chromium overrides an
    ``Accept-Language`` set with ``set_extra_http_headers`` on navigations.

    Args:
        page: The Playwright page (before ``page.goto``).
        direction: ``ltr`` or ``rtl``.
        theme: ``light``, ``dark`` or ``high-contrast``.
    """
    page.context.add_cookies(
        [
            {
                "name": settings.LANGUAGE_COOKIE_NAME,
                "value": language_for(direction),
                "domain": host,
                "path": "/",
            }
            for host in LIVE_SERVER_HOSTS
        ]
    )
    page.emulate_media(
        color_scheme="dark" if theme == "dark" else "light",
        forced_colors="active" if theme == "high-contrast" else "none",
        reduced_motion="reduce",
    )


def capture_ui_state(
    page: Page,
    kind: str,
    ui_id: str,
    state: str,
    direction: str,
    theme: str,
    *variants: str,
) -> Path:
    """Save a full-page PNG under the contract's file name and return its path.

    Args:
        page: The rendered page.
        kind: ``component``, ``screen`` or ``flow``.
        ui_id: The component/screen name as in code (a kebab-case flow name).
        state: Kebab-case visual state (``default``, ``error``, ``01-cart``).
        direction: ``ltr`` or ``rtl``; the page must render that ``dir``.
        theme: ``light``, ``dark`` or ``high-contrast``.
        *variants: Optional extra segments (``web``, ``w1280``, ``large-text``).

    Returns:
        The written file.

    Raises:
        ValueError: A segment does not match the naming contract.
        AssertionError: The page does not render ``direction``.
    """
    if kind not in KINDS or direction not in DIRECTIONS or theme not in THEMES:
        msg = f"bad screenshot key: {kind}/{direction}/{theme}"
        raise ValueError(msg)
    for segment in (state, *variants):
        if not _SEGMENT.match(segment):
            msg = f"segment must be kebab-case: {segment!r}"
            raise ValueError(msg)
    rendered = page.evaluate("document.documentElement.dir || 'ltr'")
    if rendered != direction:
        msg = f"page renders dir={rendered!r}, expected {direction!r}"
        raise AssertionError(msg)
    name = "--".join((state, direction, theme, *variants)) + ".png"
    path = screenshots_dir() / kind / ui_id / name
    path.parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=path, full_page=True, animations="disabled", caret="hide")
    return path


def check_accessibility(page: Page) -> list[str]:
    """Run axe-core (WCAG 2.x A/AA tags) at the ``.ui-quality.yml`` level.

    ``enforce`` fails the test on any violation, ``report`` only records them
    in ``a11y-report.jsonl``, ``off`` runs nothing.

    Args:
        page: The rendered page.

    Returns:
        ``"<rule id> x <node count>"`` per violation.
    """
    level = ui_quality()["accessibility"]
    if level in ("off", "none"):
        return []
    results = Axe().run(page, options=_AXE_OPTIONS)
    violations: list[dict[str, Any]] = results.response["violations"]
    found = [f"{v['id']} x {len(v['nodes'])}" for v in violations]
    report = screenshots_dir() / "a11y-report.jsonl"
    report.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "test": os.environ.get("PYTEST_CURRENT_TEST", ""),
        "url": page.url,
        "level": level,
        "violations": [
            {"id": v["id"], "impact": v["impact"], "nodes": len(v["nodes"])}
            for v in violations
        ],
    }
    with report.open("a", encoding="utf-8") as out:
        out.write(json.dumps(entry, ensure_ascii=False) + "\n")
    if level == "enforce":
        assert not found, f"accessibility violations: {', '.join(found)}"
    return found
```

## `tests/ui/conftest.py`

```python
"""Fixtures for the browser UI tier (``uv run pytest -m ui``).

pytest-playwright's sync API drives the browser from the test thread while
pytest-django's ``live_server`` serves the app from another one. Playwright
runs an asyncio event loop in the test thread, so Django's async-safety check
would raise ``SynchronousOnlyOperation`` for the ORM calls pytest-django makes
there (test database setup, flushing); ``DJANGO_ALLOW_ASYNC_UNSAFE`` turns
that check off for the UI tier only.
"""

import os
import re
from typing import Any

import pytest

from tests.ui.ui_screenshots import ui_quality

os.environ.setdefault("DJANGO_ALLOW_ASYNC_UNSAFE", "true")


def pytest_collection_modifyitems(
    config: pytest.Config, items: list[pytest.Item]
) -> None:
    """Deselect ``ui`` tests unless the ``-m`` expression names the marker.

    Keeps a plain ``pytest`` and the fast tier (``-m "not integration"``)
    browser-free, so the coverage gate never depends on Chromium.
    """
    if "ui" in re.findall(r"\w+", config.option.markexpr or ""):
        return
    ui_items = [item for item in items if item.get_closest_marker("ui")]
    if ui_items:
        items[:] = [item for item in items if not item.get_closest_marker("ui")]
        config.hook.pytest_deselected(items=ui_items)


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    """Run every test taking ``direction`` and ``theme`` across the matrix."""
    if {"direction", "theme"} <= set(metafunc.fixturenames):
        quality = ui_quality()
        metafunc.parametrize(
            ("direction", "theme"),
            [(d, t) for d in quality["directions"] for t in quality["themes"]],
        )


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args: dict[str, Any]) -> dict[str, Any]:
    """Deterministic rendering: fixed viewport, locale, time zone, no motion."""
    return {
        **browser_context_args,
        "viewport": {"width": 1280, "height": 800},
        "locale": "en-US",
        "timezone_id": "UTC",
        "reduced_motion": "reduce",
    }
```

## `tests/ui/test_admin_login.py`

```python
"""UI tier: the Django admin login screen across the ``.ui-quality.yml`` matrix.

Run with ``uv run pytest -m ui`` (needs ``uv run playwright install chromium``).
Captures land in ``.ui-screenshots/screen/AdminLoginScreen/``.
"""

import pytest
from django.urls import reverse
from playwright.sync_api import Page, expect
from pytest_django.live_server_helper import LiveServer

from tests.ui.ui_screenshots import (
    apply_direction_and_theme,
    capture_ui_state,
    check_accessibility,
)

pytestmark = [pytest.mark.ui, pytest.mark.django_db(transaction=True)]

SCREEN = "AdminLoginScreen"


def _open(page: Page, live_server: LiveServer, direction: str, theme: str) -> None:
    apply_direction_and_theme(page, direction, theme)
    page.goto(live_server.url + reverse("admin:login"))


def test_default_state(
    page: Page, live_server: LiveServer, direction: str, theme: str
) -> None:
    _open(page, live_server, direction, theme)

    expect(page.locator("#id_username")).to_be_focused()
    capture_ui_state(page, "screen", SCREEN, "default", direction, theme)
    check_accessibility(page)


def test_error_state_after_wrong_credentials(
    page: Page, live_server: LiveServer, direction: str, theme: str
) -> None:
    _open(page, live_server, direction, theme)

    page.locator("#id_username").fill("nobody")
    page.locator("#id_password").fill("wrong-password")
    page.locator("input[type=submit]").click()

    expect(page.locator(".errornote")).to_be_visible()
    capture_ui_state(page, "screen", SCREEN, "error", direction, theme)
    check_accessibility(page)
```
