// Unit tests of ui-overrides/helpers/nav-item-state.js and of the ui-overrides/partials/nav-tree.hbs override
// that uses it. Run with `npm run test:nav` (`node --test "scripts/nav-fix/*.test.mjs"`, from docs/).
// `handlebars` comes with Antora (it is a dependency of @antora/ui-loader), so nothing else is installed.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const navItemState = require('../../ui-overrides/helpers/nav-item-state.js')
const { containsCurrent } = navItemState
const Handlebars = require('handlebars')

// depth 0: group, depth 1: section, depth 2: subsection, depth 3: page
const fixture = {
  content: 'Group',
  url: '/group/index.html',
  items: [
    {
      content: 'Section',
      url: '/group/section/index.html',
      items: [
        {
          content: 'Subsection',
          url: '/group/section/sub/index.html',
          items: [{ content: 'Page', url: '/group/section/sub/page.html' }],
        },
      ],
    },
    { content: 'Leaf' },
  ],
}

// A text-only group heading: children and no url (e.g. `* Getting started` followed by `** xref:...`).
const textGroup = {
  content: 'Getting started',
  items: [
    { content: 'Install', url: '/install.html' },
    { content: 'Nested group', items: [{ content: 'Deep page', url: '/deep.html' }] },
  ],
}

test('containsCurrent: the entry itself, a deep descendant, absent', () => {
  assert.equal(containsCurrent(fixture, '/group/index.html'), true)
  assert.equal(containsCurrent(fixture, '/group/section/sub/page.html'), true)
  assert.equal(containsCurrent(fixture.items[0], '/group/section/sub/page.html'), true)
  assert.equal(containsCurrent(fixture, '/other/page.html'), false)
})

test('containsCurrent: entries without items or url, and invalid input', () => {
  assert.equal(containsCurrent({ content: 'Leaf', url: '/leaf.html' }, '/leaf.html'), true)
  assert.equal(containsCurrent(fixture.items[1], '/x.html'), false)
  assert.equal(containsCurrent({ items: [{ url: '/a.html' }] }, '/a.html'), true)
  assert.equal(containsCurrent({ items: [{ url: '/a.html' }] }, undefined), false)
  assert.equal(containsCurrent(undefined, '/a.html'), false)
  assert.equal(containsCurrent('text', '/a.html'), false)
  assert.equal(containsCurrent({ items: 'not an array' }, '/a.html'), false)
})

test('navItemState: depth rules with the default expand depth (1)', () => {
  const page = '/other.html'
  const at = (level, open) => navItemState(fixture, level, open, page)
  assert.deepEqual([at(undefined).depth, at(undefined).active, at(undefined).expand], [0, true, true])
  assert.deepEqual([at(1).active, at(1).expand], [false, true])
  assert.deepEqual([at(2).active, at(2).expand, at(2).toggle], [false, false, false])
  assert.deepEqual([at(2, true).active, at(2, true).expand], [true, true])
  assert.equal(at(3).childLevel, 4)
})

test('navItemState: entry on the path of the current page is open at any depth', () => {
  const s = navItemState(fixture.items[0], 5, false, '/group/section/sub/page.html')
  assert.deepEqual([s.onPath, s.active, s.expand, s.toggle, s.current], [true, true, true, true, false])
  assert.equal(navItemState(fixture, 2, false, '/group/index.html').current, true)
})

test('navItemState: an entry without children never gets a toggle', () => {
  const s = navItemState({ content: 'Leaf', url: '/leaf.html' }, 1, false, '/leaf.html')
  assert.deepEqual([s.expand, s.toggle, s.current], [true, false, true])
  assert.equal(navItemState({ content: 'Empty', items: [] }, 4, false, '/x').toggle, false)
})

test('navItemState: a text-only group heading always emits its children, collapsed off the current path', () => {
  const off = navItemState(textGroup, 4, false, '/elsewhere.html')
  assert.deepEqual([off.expand, off.toggle, off.active, off.onPath], [true, true, false, false])
  const on = navItemState(textGroup, 4, false, '/install.html')
  assert.deepEqual([on.expand, on.toggle, on.active, on.onPath], [true, true, true, true])
})

test('navItemState: nested text-only groups are all emitted, only the one on the path is open', () => {
  const nested = textGroup.items[1]
  const off = navItemState(nested, 6, false, '/install.html')
  assert.deepEqual([off.expand, off.toggle, off.active], [true, true, false])
  const on = navItemState(nested, 6, false, '/deep.html')
  assert.deepEqual([on.expand, on.toggle, on.active], [true, true, true])
})

test('navItemState: a linked entry deeper than the expand depth is pruned off the path', () => {
  const linked = { content: 'Linked', url: '/linked.html', items: [{ content: 'Child', url: '/child.html' }] }
  const s = navItemState(linked, 4, false, '/elsewhere.html')
  assert.deepEqual([s.expand, s.toggle], [false, false])
})

test('navItemState: nav_expand_depth knob (number, numeric string, invalid, negative)', () => {
  const at = (level, expandDepth) => navItemState(fixture, level, false, '/other.html', undefined, expandDepth).expand
  assert.equal(at(2, 2), true)
  assert.equal(at(3, 2), false)
  assert.equal(at(2, '2'), true)
  assert.equal(at(2, 0), false)
  assert.equal(at(0, 0), true)
  assert.equal(at(1, 0), false)
  assert.equal(at(2, 'abc'), false, 'invalid values fall back to the default depth 1')
  assert.equal(at(1, -3), true, 'negative values fall back to the default depth 1')
  assert.equal(at(1, 1.5), true, 'non-integers fall back to the default depth 1')
})

test('navItemState: which children open (nav_open_groups, comma-separated, trimmed)', () => {
  const open = (item, groups) => navItemState(item, 1, false, '/x', groups).openChildren
  assert.equal(open({ items: [] }, undefined), true, 'an untitled list wrapper always opens its children')
  assert.equal(open({ content: 'Guides', items: [] }, undefined), false, 'no group is open by default')
  assert.equal(open({ content: 'Open Source Projects', items: [] }, 'Open Source Projects, Samples'), true)
  assert.equal(open({ content: 'Samples', items: [] }, 'Open Source Projects, Samples'), true)
  assert.equal(open({ content: 'Guides', items: [] }, 'Open Source Projects, Samples'), false)
  assert.equal(open({ content: 'Guides', items: [] }, ' , '), false, 'blank entries are ignored')
  assert.equal(open({ content: 'Guides', items: [] }, 42), false, 'a non-string value selects the default')
})

// Rendering of the partial with the helpers it uses (eq and relativize stubbed as in the UI bundle). The partial reads
// the playbook's `site.keys` camelCased, as Antora hands them to the templates.
function render (navigation, pageUrl, keys = {}) {
  const hb = Handlebars.create()
  hb.registerHelper('eq', (a, b) => a === b)
  hb.registerHelper('relativize', (to) => to)
  hb.registerHelper('nav-item-state', navItemState)
  hb.registerPartial('nav-tree', readFileSync(new URL('../../ui-overrides/partials/nav-tree.hbs', import.meta.url), 'utf8'))
  return hb.compile('{{> nav-tree navigation=this}}')(navigation, { data: { root: { page: { url: pageUrl }, site: { keys } } } })
}

const page = (name) => ({ content: name, url: `/${name}.html`, urlType: 'internal' })
const site = [
  {
    // untitled list wrapper (depth 0)
    items: [
      {
        content: 'Guides & References',
        items: [
          {
            content: 'Backend',
            url: '/backend.html',
            urlType: 'internal',
            items: [{ content: 'Spring', url: '/spring.html', urlType: 'internal', items: [page('spring-a'), page('spring-b')] }],
          },
          {
            content: 'Web',
            url: '/web.html',
            urlType: 'internal',
            items: [{ content: 'React', url: '/react.html', urlType: 'internal', items: [page('react-a')] }],
          },
        ],
      },
      { content: 'Open Source Projects', items: [{ content: 'Java Projects', items: [page('algebra')] }] },
    ],
  },
]

test('nav-tree: lists groups and sections, emits only the current branch below them', () => {
  const html = render(site, '/spring-b.html')
  for (const name of ['Guides & References', 'Backend', 'Web', 'Spring', 'spring-a', 'spring-b']) {
    assert.ok(html.includes(`>${name}<`), `${name} should be rendered`)
  }
  assert.ok(!html.includes('React'), 'subsections of other sections are not emitted')
  assert.ok(!html.includes('react-a'))
  assert.match(html, /nav-item is-current-page is-active" data-depth="4">\s*<a class="nav-link" href="\/spring-b.html">spring-b</)
  assert.match(html, /nav-item is-active" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<a class="nav-link" href="\/backend.html">Backend</)
  assert.match(html, /nav-item" data-depth="2">\s*<a class="nav-link" href="\/web.html">Web</, 'Web is closed and has no toggle')
})

test('nav-tree: the top-level groups are open', () => {
  const html = render(site, '/none.html')
  assert.match(html, /nav-item is-active" data-depth="1">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Guides & References</)
})

test('nav-tree: no group is hard-coded: a text-only group emits its children, collapsed, with a toggle', () => {
  const html = render(site, '/spring-b.html')
  assert.match(html, /nav-item" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Java Projects</)
  assert.ok(html.includes('>algebra<'), 'children of a text-only group are always emitted')
})

test('nav-tree: nav_open_groups opens the listed groups, others stay collapsed', () => {
  const closed = render(site, '/spring-b.html')
  assert.match(closed, /<li class="nav-item" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Java Projects</)
  assert.match(closed, /<li class="nav-item" data-depth="3">\s*<a class="nav-link" href="\/algebra.html">algebra</)
  const opened = render(site, '/spring-b.html', { navOpenGroups: 'Samples, Open Source Projects' })
  // Open Source Projects opens its children: Java Projects is rendered open and so is its child list
  assert.match(opened, /nav-item is-active" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Java Projects</)
  // only the children of a listed group open: Java Projects is not itself listed, so its children stay closed
  assert.match(opened, /<li class="nav-item" data-depth="3">\s*<a class="nav-link" href="\/algebra.html">algebra</)
  // an unrelated group is unaffected
  assert.match(opened, /nav-item is-active" data-depth="1">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Guides & References</)
})

test('nav-tree: nav_expand_depth lists deeper sections of other branches', () => {
  assert.ok(!render(site, '/spring-b.html').includes('react-a'))
  const html = render(site, '/spring-b.html', { navExpandDepth: 3 })
  assert.ok(html.includes('>React<'), 'with depth 3 the subsections of other sections are emitted')
  assert.ok(html.includes('>react-a<'))
  assert.ok(!render(site, '/none.html', { navExpandDepth: 0 }).includes('>Spring<'), 'depth 0 prunes the linked sections below the groups')
})

test('nav-tree: a text-only group with children never renders as a dead end', () => {
  const nav = [{ items: [{ content: 'Getting started', items: [page('install'), { content: 'Inner', items: [page('deep')] }] }] }]
  for (const current of ['/install.html', '/deep.html', '/elsewhere.html']) {
    const html = render(nav, current)
    for (const title of ['Getting started', 'Inner']) {
      assert.match(html, new RegExp(`<span class="nav-text">${title}</span>\\s*<ul class="nav-list">`), `${title} has a child list on ${current}`)
    }
  }
  const collapsed = render(nav, '/elsewhere.html')
  assert.match(collapsed, /nav-item" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Inner/, 'Inner is collapsed off the path')
  const onPath = render(nav, '/deep.html')
  assert.match(onPath, /nav-item is-active" data-depth="2">\s*<button class="nav-item-toggle"><\/button>\s*<span class="nav-text">Inner/)
})

test('nav-tree: an empty navigation renders nothing', () => {
  assert.equal(render([], '/x.html').trim(), '')
})
