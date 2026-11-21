// Tests of measure.mjs. Run with `npm run test:nav`.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { AUTO_SAMPLES, THRESHOLDS, autoSamples, countNavEntries, extractGenerator, extractNav, measure, usesOverride, verdict, walk } from './measure.mjs'

const SCRIPT = new URL('./measure.mjs', import.meta.url).pathname
const items = (n, attrs = '') => Array.from({ length: n }, (_, i) => `<li class="nav-item"${attrs}><a class="nav-link" href="p${i}.html">P${i}</a></li>`).join('')
const page = (n, { override = false, body = 'x' } = {}) =>
  `<html><head><meta name="generator" content="Antora 3.2.2"></head><body><nav class="nav-menu"><ul class="nav-list">${override ? '<!-- nav-tree override -->' : ''}${items(n, ' data-depth="1"')}</ul></nav><article>${body}</article></body></html>`

function site (files) {
  const root = mkdtempSync(join(tmpdir(), 'measure-'))
  for (const [name, body] of Object.entries(files)) {
    mkdirSync(dirname(join(root, name)), { recursive: true })
    writeFileSync(join(root, name), body)
  }
  return root
}
const cleanup = (root) => rmSync(root, { recursive: true })

test('extractNav, extractGenerator, countNavEntries and usesOverride', () => {
  const html = page(3, { override: true })
  assert.equal(countNavEntries(extractNav(html)), 3)
  assert.equal(usesOverride(extractNav(html)), true)
  assert.equal(usesOverride(extractNav(page(3))), false)
  assert.equal(extractGenerator(html), 'Antora 3.2.2')
  assert.equal(extractGenerator('<p/>'), null)
  assert.equal(extractNav('<p/>'), null)
  assert.equal(extractNav('<nav class="nav-menu"><ul>'), null)
})

test('walk: counts HTML files and sizes of everything', () => {
  const root = site({ 'a.html': '12345', 'b/c.html': '123', 'b/x.css': '12' })
  try {
    const w = walk(root)
    assert.deepEqual([w.html.length, w.htmlBytes, w.totalBytes], [2, 8, 10])
  } finally { cleanup(root) }
})

test('autoSamples: home, largest pages and a spread, without 404 and without duplicates', () => {
  const files = { 'index.html': page(1), '404.html': page(1, { body: 'y'.repeat(9999) }) }
  for (let i = 0; i < 20; i++) files[`c/p${i}.html`] = page(1, { body: 'z'.repeat(i * 10) })
  const root = site(files)
  try {
    const picked = autoSamples(root, walk(root).html)
    assert.ok(picked.includes('index.html'))
    assert.ok(picked.includes('c/p19.html'), 'the largest page is sampled')
    assert.ok(!picked.includes('404.html'))
    assert.equal(new Set(picked).size, picked.length)
    assert.ok(picked.length <= 1 + AUTO_SAMPLES.largest + AUTO_SAMPLES.spread)
  } finally { cleanup(root) }
})

test('measure: small site is ok, a missing sample is flagged', () => {
  const root = site({ 'index.html': page(5, { body: 'x'.repeat(5000) }) })
  try {
    const r = measure(root)
    assert.equal(r.verdict.status, 'ok')
    assert.equal(r.samples['index.html'].navEntries, 5)
    assert.equal(r.generator, 'Antora 3.2.2')
    assert.ok(r.samples['index.html'].navHtml)
    const m = measure(root, ['index.html', 'nope.html'], { nav: false })
    assert.deepEqual(m.samples['nope.html'], { missing: true })
    assert.equal('navHtml' in m.samples['index.html'], false)
  } finally { cleanup(root) }
})

test('verdict: more than 300 nav entries recommends the override, or reports it enabled', () => {
  const big = site({ 'index.html': page(THRESHOLDS.navEntries + 1, { body: 'x'.repeat(500000) }) })
  const bigOverride = site({ 'index.html': page(THRESHOLDS.navEntries + 1, { override: true, body: 'x'.repeat(500000) }) })
  try {
    const r = measure(big)
    assert.equal(r.verdict.status, 'recommend')
    assert.match(r.verdict.reasons[0], /301 nav entries/)
    assert.equal(measure(bigOverride).verdict.status, 'enabled')
  } finally { cleanup(big); cleanup(bigOverride) }
})

test('verdict: nav over 30% of a typical page, and site over 500,000 KiB', () => {
  const heavyNav = site({ 'index.html': page(20, { body: '' }) })
  try {
    const r = measure(heavyNav)
    assert.equal(r.verdict.status, 'recommend')
    assert.match(r.verdict.reasons[0], /of a typical page/)
  } finally { cleanup(heavyNav) }
  const huge = verdict({ siteKiB: THRESHOLDS.siteKiB + 1, samples: { 'a.html': { bytes: 100000, navBytes: 10, navEntries: 5, navFraction: 0.0001, overrideInUse: false } } })
  assert.equal(huge.status, 'recommend')
  assert.match(huge.reasons[0], /KiB/)
  assert.equal(verdict({ siteKiB: 1, samples: { '404.html': { navBytes: 0, navEntries: 0, navFraction: 0 } } }).status, 'ok')
})

test('cli: exit codes and output', () => {
  const run = (...args) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' })
  const small = site({ 'index.html': page(3, { body: 'x'.repeat(5000) }) })
  const big = site({ 'index.html': page(THRESHOLDS.navEntries + 1, { body: 'x'.repeat(500000) }) })
  try {
    const ok = run(small)
    assert.equal(ok.status, 0)
    assert.match(ok.stdout, /Verdict:\s+ok/)
    const rec = run(big)
    assert.equal(rec.status, 3)
    assert.match(rec.stdout, /Recommendation: enable the nav-tree override/)
    assert.equal(JSON.parse(run(small, '--json', '--no-nav').stdout).verdict.status, 'ok')
    assert.equal(run(small, '--sample', 'nope.html').status, 2)
    assert.equal(run(small, '--sample', 'index.html').status, 0)
    assert.equal(run().status, 1)
    assert.equal(run(small, '--bogus').status, 1)
    assert.equal(run(join(small, 'missing')).status, 1)
  } finally { cleanup(small); cleanup(big) }
})
