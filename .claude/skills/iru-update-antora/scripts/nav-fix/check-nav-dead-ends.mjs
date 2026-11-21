#!/usr/bin/env node
// Checks the navigation of a built Antora site (default build/site) for two defects:
//   dead end   a `li.nav-item` whose `.nav-text` (a text-only group heading) has no child `ul.nav-list`: the reader
//              sees a heading with nothing under it, because the nav override pruned its children
//   broken     a nav link whose target page is missing from the build directory
// Usage:
//   node scripts/nav-fix/check-nav-dead-ends.mjs <build-dir> [--sample]
//     --sample   check one page per directory (at depth 3 at most, plus the site root) instead of every page: a
//                nav section is a directory of the site, so every section is covered at a fraction of the cost
// Exit code: 0 clean, 1 defects found, 2 usage error or no page with a nav menu under <build-dir>.
// Node built-ins only; run it from docs/ after `npx antora antora-playbook.yml` (npm run test:nav:site).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { extractNav } from '../nav-html.mjs'

export { extractNav }

const SAMPLE_DEPTH = 3
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr'])


function classesOf (attrs) {
  const m = attrs.match(/\bclass\s*=\s*(?:"([^"]*)"|'([^']*)')/)
  return new Set(((m && (m[1] ?? m[2])) || '').split(/\s+/).filter(Boolean))
}

// Walks the tags of a nav fragment and returns { deadEnds: [title], links: [href] }. A dead end is a li.nav-item with
// a direct child `.nav-text` and no direct child `ul.nav-list`.
export function analyzeNav (navHtml) {
  const deadEnds = []
  const links = []
  const stack = []
  const tag = /<!--[\s\S]*?-->|<(\/?)([a-zA-Z][\w-]*)([^>]*)>/g
  let m
  while ((m = tag.exec(navHtml))) {
    if (m[0].startsWith('<!--')) continue
    const [, closing, rawName, attrs] = m
    const name = rawName.toLowerCase()
    if (closing) {
      const top = stack.pop()
      if (top && top.name === 'li' && top.isItem && top.navText != null && !top.childList) deadEnds.push(top.navText)
      if (top && top.isNavText) top.owner.navText = navHtml.slice(top.contentStart, m.index).replace(/<[^>]*>/g, '').trim()
      continue
    }
    const classes = classesOf(attrs)
    const parent = stack[stack.length - 1]
    if (name === 'a' && classes.has('nav-link')) {
      const href = attrs.match(/\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')/)
      if (href) links.push((href[1] ?? href[2]).trim())
    }
    if (VOID_TAGS.has(name) || /\/\s*$/.test(attrs)) continue
    const entry = { name, isItem: name === 'li' && classes.has('nav-item'), navText: null, childList: false }
    if (name === 'ul' && classes.has('nav-list') && parent && parent.isItem) parent.childList = true
    if (classes.has('nav-text') && parent && parent.isItem) {
      entry.isNavText = true
      entry.owner = parent
      entry.contentStart = m.index + m[0].length
      parent.navText = ''
    }
    stack.push(entry)
  }
  return { deadEnds, links }
}

export function listHtml (root) {
  const files = []
  const stack = [root]
  while (stack.length) {
    const dir = stack.pop()
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      if (e.isDirectory()) stack.push(p)
      else if (e.isFile() && e.name.endsWith('.html')) files.push(p)
    }
  }
  return files.sort()
}

// One page per directory up to SAMPLE_DEPTH levels below the root (index.html preferred), so every nav section of the
// site is exercised.
export function sampleFiles (root, files) {
  const byDir = new Map()
  for (const file of files) {
    const rel = relative(root, dirname(file))
    const parts = rel === '' ? [] : rel.split(sep)
    const key = parts.slice(0, SAMPLE_DEPTH).join('/')
    const current = byDir.get(key)
    if (!current || (file.endsWith(`${sep}index.html`) && !current.endsWith(`${sep}index.html`))) byDir.set(key, file)
  }
  return [...byDir.values()].sort()
}

// The file a nav href points to from the page `pageFile`, or null for an external or non-page link.
export function targetFile (root, pageFile, href) {
  if (/^([a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) return null
  let path = href.split('#')[0].split('?')[0]
  // hrefs are percent-encoded (spaces, non-ASCII page names); the files on disk are not
  try { path = decodeURIComponent(path) } catch { /* malformed escape: keep it literal */ }
  if (path === '') return pageFile
  let target = path.startsWith('/') ? join(root, path) : resolve(dirname(pageFile), path)
  if (path.endsWith('/')) target = join(target, 'index.html')
  return target
}

function isFile (path, cache) {
  if (!cache.has(path)) {
    let ok = false
    if (existsSync(path)) {
      const stats = statSync(path)
      ok = stats.isFile() || (stats.isDirectory() && existsSync(join(path, 'index.html')))
    }
    cache.set(path, ok)
  }
  return cache.get(path)
}

export function check (buildDir, { sample = false } = {}) {
  const root = resolve(buildDir)
  const all = listHtml(root)
  const files = sample ? sampleFiles(root, all) : all
  const result = { root, pages: all.length, checked: 0, withNav: 0, deadEnds: [], brokenLinks: [] }
  const cache = new Map()
  const reported = new Set()
  for (const file of files) {
    result.checked++
    const nav = extractNav(readFileSync(file, 'utf8'))
    if (!nav) continue
    result.withNav++
    const page = relative(root, file)
    const { deadEnds, links } = analyzeNav(nav)
    for (const title of deadEnds) result.deadEnds.push({ page, title })
    for (const href of links) {
      const target = targetFile(root, file, href)
      if (target === null || isFile(target, cache)) continue
      const key = `${relative(root, target)} ${page}`
      if (!reported.has(key)) { reported.add(key); result.brokenLinks.push({ page, href }) }
    }
  }
  return result
}

function main (argv) {
  const args = argv.slice(2)
  let dir
  let sample = false
  for (const a of args) {
    if (a === '--sample') sample = true
    else if (!a.startsWith('--') && !dir) dir = a
    else { console.error(`Unknown argument: ${a}`); return 2 }
  }
  if (!dir || !existsSync(dir)) {
    console.error('Usage: check-nav-dead-ends.mjs <build-dir> [--sample]  (build-dir must exist, e.g. build/site)')
    return 2
  }
  const r = check(dir, { sample })
  if (r.withNav === 0) {
    console.error(`No page with a nav menu found under ${r.root}: nothing to check`)
    return 2
  }
  console.log(`Checked ${r.checked} of ${r.pages} pages (${r.withNav} with a nav menu)${sample ? ' [--sample]' : ''}`)
  for (const d of r.deadEnds) console.error(`DEAD END  ${d.page}: nav group "${d.title}" has no child list`)
  for (const b of r.brokenLinks) console.error(`BROKEN    ${b.page}: nav link ${b.href} does not exist in the build`)
  if (r.deadEnds.length || r.brokenLinks.length) {
    console.error(`FAILED: ${r.deadEnds.length} dead end(s), ${r.brokenLinks.length} broken nav link(s)`)
    return 1
  }
  console.log('OK: no nav dead ends, no broken nav links')
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = main(process.argv)
