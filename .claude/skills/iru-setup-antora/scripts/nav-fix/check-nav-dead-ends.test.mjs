// Tests of check-nav-dead-ends.mjs. Run with `npm run test:nav` (`node --test "scripts/nav-fix/*.test.mjs"`).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { analyzeNav, check, extractNav, sampleFiles, targetFile, listHtml } from './check-nav-dead-ends.mjs'

const SCRIPT = new URL('./check-nav-dead-ends.mjs', import.meta.url).pathname

const nav = (inner) => `<nav class="nav-menu"><h3 class="title">T</h3>\n<ul class="nav-list">${inner}</ul></nav>`
const page = (navHtml) => `<!DOCTYPE html><html><body><div class="nav-container">${navHtml}</div><article>x</article></body></html>`
const link = (href, text) => `<li class="nav-item" data-depth="1"><a class="nav-link" href="${href}">${text}</a></li>`
const group = (title, children) => `<li class="nav-item" data-depth="1"><button class="nav-item-toggle"></button><span class="nav-text">${title}</span>${children}</li>`

function site (files) {
  const root = mkdtempSync(join(tmpdir(), 'nav-check-'))
  for (const [name, body] of Object.entries(files)) {
    mkdirSync(dirname(join(root, name)), { recursive: true })
    writeFileSync(join(root, name), body)
  }
  return root
}

test('extractNav: returns the nav-menu element, or null', () => {
  assert.equal(extractNav('<p>no nav</p>'), null)
  assert.equal(extractNav('<nav class="nav-menu"><nav>inner</nav></nav><nav class="x"></nav>'), '<nav class="nav-menu"><nav>inner</nav></nav>')
  assert.equal(extractNav('<nav class="nav-menu"><ul>'), null, 'an unclosed nav is not returned')
})

test('analyzeNav: a text-only group with a child list is not a dead end', () => {
  const html = nav(group('Group', `<ul class="nav-list">${link('a.html', 'A')}</ul>`) + link('b.html', 'B'))
  assert.deepEqual(analyzeNav(html), { deadEnds: [], links: ['a.html', 'b.html'] })
})

test('analyzeNav: a text-only group without children is a dead end, a deeper one too', () => {
  const html = nav(group('Empty <code>One</code>', '') + group('Outer', `<ul class="nav-list">${group('Inner', '')}</ul>`))
  assert.deepEqual(analyzeNav(html).deadEnds, ['Empty One', 'Inner'])
})

test('analyzeNav: only a child list directly under the item counts, and comments and void tags are ignored', () => {
  const html = nav(`<li class="nav-item"><!-- <ul class="nav-list"> --><span class="nav-text">Group</span><img src="x.png"><div><ul class="nav-list"></ul></div></li>`)
  assert.deepEqual(analyzeNav(html).deadEnds, ['Group'])
  assert.deepEqual(analyzeNav(nav(link('a.html', 'A') + '<li class="other"><span class="nav-text">x</span></li>')).deadEnds, [])
})

test('targetFile: relative, absolute, fragment, directory and external links', () => {
  const root = '/site'
  const from = '/site/c/1.0/page.html'
  assert.equal(targetFile(root, from, 'other.html'), '/site/c/1.0/other.html')
  assert.equal(targetFile(root, from, '../2.0/x.html#frag'), '/site/c/2.0/x.html')
  assert.equal(targetFile(root, from, '/c/y.html'), '/site/c/y.html')
  assert.equal(targetFile(root, from, '../'), '/site/c/index.html')
  assert.equal(targetFile(root, from, '#top'), from)
  assert.equal(targetFile(root, from, 'https://example.com/x.html'), null)
  assert.equal(targetFile(root, from, 'mailto:a@b.c'), null)
  assert.equal(targetFile(root, from, '//cdn.example.com/x'), null)
  assert.equal(targetFile(root, from, 'my%20page.html'), '/site/c/1.0/my page.html')
  assert.equal(targetFile(root, from, 'caf%C3%A9.html#x'), '/site/c/1.0/café.html')
  assert.equal(targetFile(root, from, 'bad%E0.html'), '/site/c/1.0/bad%E0.html')
})

test('check: a healthy site passes', () => {
  const root = site({
    'index.html': '<html>redirect</html>',
    'c/1.0/index.html': page(nav(link('a.html', 'A') + group('G', `<ul class="nav-list">${link('index.html', 'Home')}</ul>`))),
    'c/1.0/a.html': page(nav(link('index.html', 'Home'))),
    '404.html': '<html>not found</html>',
  })
  try {
    const r = check(root)
    assert.deepEqual([r.pages, r.checked, r.withNav, r.deadEnds, r.brokenLinks], [4, 4, 2, [], []])
  } finally { rmSync(root, { recursive: true }) }
})

test('check: reports dead ends and broken links once per page and target', () => {
  const root = site({
    'c/1.0/index.html': page(nav(group('Empty', '') + link('missing.html', 'Gone') + link('missing.html#a', 'Gone again') + link('https://x.org', 'Ext'))),
  })
  try {
    const r = check(root)
    assert.deepEqual(r.deadEnds, [{ page: 'c/1.0/index.html', title: 'Empty' }])
    assert.deepEqual(r.brokenLinks, [{ page: 'c/1.0/index.html', href: 'missing.html' }])
  } finally { rmSync(root, { recursive: true }) }
})

test('check: --sample takes one page per directory, preferring index.html', () => {
  const files = { 'a/1/index.html': page(nav(link('index.html', 'x'))), 'a/1/z.html': page(nav(link('z.html', 'z'))), 'b/index.html': page(nav(link('index.html', 'x'))), 'deep/a/b/c/p.html': page(nav(link('p.html', 'p'))), 'deep/a/b/d/q.html': page(nav(link('q.html', 'q'))) }
  const root = site(files)
  try {
    const picked = sampleFiles(root, listHtml(root)).map((f) => f.slice(root.length + 1))
    assert.deepEqual(picked, ['a/1/index.html', 'b/index.html', 'deep/a/b/c/p.html'])
    const r = check(root, { sample: true })
    assert.deepEqual([r.pages, r.checked], [5, 3])
  } finally { rmSync(root, { recursive: true }) }
})

test('cli: exit codes 0, 1 and 2', () => {
  const run = (...args) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' })
  const good = site({ 'index.html': page(nav(link('index.html', 'Home'))) })
  const bad = site({ 'index.html': page(nav(group('Empty', ''))) })
  const none = site({ 'index.html': '<html>no nav</html>' })
  try {
    assert.equal(run(good).status, 0)
    assert.equal(run(good, '--sample').status, 0)
    const failed = run(bad)
    assert.equal(failed.status, 1)
    assert.match(failed.stderr, /DEAD END .*"Empty"/)
    assert.equal(run(none).status, 2)
    assert.equal(run().status, 2)
    assert.equal(run(join(good, 'missing')).status, 2)
    assert.equal(run(good, '--bogus').status, 2)
  } finally { for (const d of [good, bad, none]) rmSync(d, { recursive: true }) }
})
