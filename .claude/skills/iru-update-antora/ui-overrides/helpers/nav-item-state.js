'use strict'

// Handlebars helper `nav-item-state` (Antora derives the helper name from this file name), used by
// partials/nav-tree.hbs as
//   {{#with (nav-item-state item ../level ../open @root.page.url @root.site.keys.navOpenGroups @root.site.keys.navExpandDepth) as |s|}}
// It computes, once per nav entry, everything the partial needs:
//   depth        level of the entry (0 = the untitled list wrapper)
//   childLevel   level passed to the child list
//   current      the entry is the current page
//   onPath       the entry is the current page or contains it (in `items`, at any depth)
//   active       the entry is rendered open (is-active): depth 0, its parent asked for open children, or onPath
//   expand       its child list is emitted: depth <= nav_expand_depth (groups and sections), parent asked for open
//                children, onPath, or the entry is a text-only group heading (has children and no url)
//   toggle       it has children and they are emitted, so it gets a toggle button
//   openChildren its children are rendered open: an untitled list wrapper, or a group whose title is listed in
//                site.keys.nav_open_groups
//
// Playbook knobs (all optional, read from `site.keys` in the playbook):
//   nav_open_groups   comma-separated group titles whose children are rendered open (default: none)
//   nav_expand_depth  deepest level whose child list is always emitted (default 1)
//
// A text-only group heading (a nav entry with no xref, e.g. `*** Getting started`) has no page of its own, so readers
// can only reach its children through the nav: it always emits them, with a toggle, collapsed unless it is on the
// path to the current page. Only entries that have a url may be pruned, because readers reach their subtree through the
// entry's own page. Never make this helper prune an entry that has no url.
//
// Reading site.keys: confirmed. The helper never touches the template context; the partial passes
// `@root.site.keys.<name>` as arguments, and `@root` is the UI model Antora hands to every template and partial, so
// the values are available at any partial depth (checked in a real Antora 3.2.2 build, at depth 0 and at depth 5).
// Antora camelCases the keys of the playbook's `site.keys` (verified: `nav_open_groups` arrives as `navOpenGroups`,
// `nav_expand_depth` as `navExpandDepth`), so the playbook says nav_open_groups / nav_expand_depth and the partial
// reads navOpenGroups / navExpandDepth. A key that is not set arrives as undefined, which selects the defaults, and
// a number in the playbook arrives as a number (a quoted one as a string; both are accepted).
// Pure: Antora loads helper files on their own, so the containment check lives here rather than in a second file.

const DEFAULT_EXPAND_DEPTH = 1

function containsCurrent (item, currentUrl) {
  if (!item || typeof item !== 'object' || currentUrl == null) return false
  if (item.url != null && item.url === currentUrl) return true
  const items = item.items
  if (!Array.isArray(items)) return false
  return items.some((child) => containsCurrent(child, currentUrl))
}

function parseOpenGroups (openGroups) {
  if (typeof openGroups !== 'string') return []
  return openGroups.split(',').map((title) => title.trim()).filter((title) => title.length > 0)
}

function parseExpandDepth (expandDepth) {
  const depth = typeof expandDepth === 'number' ? expandDepth : parseInt(expandDepth, 10)
  return Number.isInteger(depth) && depth >= 0 ? depth : DEFAULT_EXPAND_DEPTH
}

function navItemState (item, level, open, currentUrl, openGroups, expandDepth) {
  const depth = typeof level === 'number' ? level : 0
  const parentOpen = open === true
  const onPath = containsCurrent(item, currentUrl)
  const hasItems = Array.isArray(item && item.items) && item.items.length > 0
  const textOnlyGroup = hasItems && !item.url
  const expand = depth <= parseExpandDepth(expandDepth) || parentOpen || onPath || textOnlyGroup
  return {
    depth,
    childLevel: depth + 1,
    current: !!item && item.url != null && item.url === currentUrl,
    onPath,
    active: depth === 0 || parentOpen || onPath,
    expand,
    toggle: hasItems && expand,
    openChildren: !!item && (!item.content || parseOpenGroups(openGroups).includes(item.content)),
  }
}

module.exports = navItemState
module.exports.containsCurrent = containsCurrent
