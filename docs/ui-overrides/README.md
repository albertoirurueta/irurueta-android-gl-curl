# UI overrides

Generic Handlebars, JavaScript and CSS overrides for the shared `ui-bundle.zip`. Copy this directory into the
repository's `docs/ui-overrides/` and register the files as `ui.supplemental_files` in the playbook. They do not
depend on any particular site: titles, groups and depths come from the playbook.

## Files

| File | What it does |
|---|---|
| `partials/article.hbs` | Wraps the article in `data-pagefind-body` so only the page content is indexed (no nav, header or footer), and only on the latest version of a component, so a page published in several versions is found once. A hidden span exposes the component title as a Pagefind `component` filter. |
| `partials/header-content.hbs` | Adds the Pagefind search container (`#search`) to the header and drops the bundle's placeholder menu and burger. It uses `img/logo.svg` from the bundle. |
| `partials/head-styles.hbs` | Loads the site stylesheet, the Pagefind UI stylesheet and `css/pagefind-site.css`. |
| `partials/footer-scripts.hbs` | Loads the bundle scripts and the Pagefind UI script (deferred) and initialises the search box once the page is ready. |
| `css/pagefind-site.css` | Styles of the search box and its results. |
| `partials/nav-tree.hbs` | Slimmed navigation: emits only the levels a reader needs instead of the whole subtree of every section on every page. Text-only group headings always emit their children, with a toggle, collapsed unless on the path to the current page. |
| `helpers/nav-item-state.js` | Computes, once per nav entry, whether it is open, expanded and toggleable. Used by `nav-tree.hbs`. |

The search UI needs the Pagefind index, which `npx pagefind --site build/site` writes into the site (see
`reference/toolchain.md`). Search works only when the site is served over HTTP, not from `file://`.

## Playbook entries

```yaml
ui:
  bundle:
    url: ./ui-bundle.zip
    snapshot: true
  supplemental_files:
    - path: partials/article.hbs
      contents: ./ui-overrides/partials/article.hbs
    - path: partials/footer-scripts.hbs
      contents: ./ui-overrides/partials/footer-scripts.hbs
    - path: partials/head-styles.hbs
      contents: ./ui-overrides/partials/head-styles.hbs
    - path: partials/header-content.hbs
      contents: ./ui-overrides/partials/header-content.hbs
    - path: partials/nav-tree.hbs
      contents: ./ui-overrides/partials/nav-tree.hbs
    - path: helpers/nav-item-state.js
      contents: ./ui-overrides/helpers/nav-item-state.js
    - path: css/pagefind-site.css
      contents: ./ui-overrides/css/pagefind-site.css
```

Paths in `contents` are relative to the playbook file.

## Navigation knobs (`site.keys`)

Both are optional; with neither set, the override lists the top two levels and is a no-op on a small nav.

```yaml
site:
  keys:
    nav_open_groups: 'Open Source Projects, Samples'
    nav_expand_depth: 1
```

| Key | Default | Meaning |
|---|---|---|
| `nav_open_groups` | none | Comma-separated group titles whose children are rendered open. An untitled list wrapper at depth 0 always opens its children. |
| `nav_expand_depth` | `1` | Deepest level whose child list is always emitted (0 is the untitled wrapper, 1 the groups, 2 the sections). Deeper entries are emitted only on the path to the current page, or when text-only groups. |

Antora camelCases `site.keys`, so the partial reads `navOpenGroups` and `navExpandDepth`; write them in snake_case in
the playbook as shown.

## Nav authoring rules

- Every nav entry is either a link (`xref:`) or a text-only group heading with children. Never add a text-only entry
  without children.
- Never link a nav entry to a `#fragment`: the bundle's script collapses the default-open groups when it sees one.
- The override prunes the subtree of a linked entry outside the current branch, because readers reach it through the
  entry's own page; it never prunes entries that have no url.
