#!/usr/bin/env node
// Measures a built Antora site (default build/site) and says whether the slimming nav override is worth having:
// HTML file count and size, the size of the whole directory, and for a set of sample pages the page size, the nav
// size and the number of nav entries. Usage:
//   node scripts/site-metrics/measure.mjs <build-dir> [--json] [--out file] [--no-nav] [--sample path]...
//     --sample path   a page to measure, relative to <build-dir> (repeatable); with none given the home page, the
//                     largest pages and a spread of the others are measured
//     --out file      also write the JSON result to file
//     --no-nav        leave the nav HTML out of the JSON result
// Verdict, from the thresholds in reference/toolchain.md ("When the nav needs the slimming override"), printed and
// returned as the exit code:
//   ok         0   no threshold is exceeded
//   enabled    0   a threshold is exceeded but the site already uses the override (the nav lists carry its marker comment)
//   recommend  3   a threshold is exceeded and the override is not in use: recommend or enable it
//   (2 = a requested sample page is missing, 1 = usage error)
// Node built-ins only.
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { extractNav } from '../nav-html.mjs'

export { extractNav }

export const THRESHOLDS = { navEntries: 300, navFraction: 0.3, siteKiB: 500000 }
export const AUTO_SAMPLES = { largest: 3, spread: 4 }

export function walk (dir) {
  const html = []
  let htmlBytes = 0
  let totalBytes = 0
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop()
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name)
      if (e.isDirectory()) { stack.push(p); continue }
      if (!e.isFile()) continue
      const size = statSync(p).size
      totalBytes += size
      if (e.name.endsWith('.html')) { html.push({ file: p, size }); htmlBytes += size }
    }
  }
  return { html: html.sort((a, b) => (a.file < b.file ? -1 : 1)), htmlBytes, totalBytes }
}


export function extractGenerator (html) {
  const m = html.match(/<meta name="generator" content="([^"]*)"/)
  return m ? m[1] : null
}

export const countNavEntries = (navHtml) => (navHtml.match(/<li class="nav-item[\s"]/g) || []).length

// True when the nav was rendered by the override: nav-tree.hbs writes this marker comment inside every nav list. The
// bundle's own nav-tree also writes data-depth on every entry, so data-depth cannot tell the two apart.
export const NAV_OVERRIDE_MARKER = '<!-- nav-tree override -->'
export const usesOverride = (navHtml) => navHtml.includes(NAV_OVERRIDE_MARKER)

// Pages to measure when none are given: the home page, the largest pages and a spread of the rest.
export function autoSamples (root, html) {
  const rel = (f) => relative(root, f.file)
  const picked = new Set()
  const home = html.find((f) => rel(f) === 'index.html')
  if (home) picked.add(rel(home))
  const withNavFirst = html.filter((f) => rel(f) !== '404.html')
  ;[...withNavFirst].sort((a, b) => b.size - a.size).slice(0, AUTO_SAMPLES.largest).forEach((f) => picked.add(rel(f)))
  const step = Math.max(1, Math.floor(withNavFirst.length / (AUTO_SAMPLES.spread + 1)))
  for (let i = step; i < withNavFirst.length && picked.size < 1 + AUTO_SAMPLES.largest + AUTO_SAMPLES.spread; i += step) picked.add(rel(withNavFirst[i]))
  return [...picked].sort()
}

const median = (values) => {
  if (!values.length) return 0
  const s = [...values].sort((a, b) => a - b)
  return s[Math.floor(s.length / 2)]
}

export function measure (buildDir, samples = [], { nav = true } = {}) {
  const root = resolve(buildDir)
  const { html, htmlBytes, totalBytes } = walk(root)
  const paths = samples.length ? samples : autoSamples(root, html)
  const result = { buildDir: root, htmlFiles: html.length, totalHtmlBytes: htmlBytes, siteKiB: Math.ceil(totalBytes / 1024), generator: null, samples: {} }
  for (const path of paths) {
    const file = join(root, path)
    if (!existsSync(file)) { result.samples[path] = { missing: true }; continue }
    const page = readFileSync(file, 'utf8')
    const navHtml = extractNav(page)
    const bytes = Buffer.byteLength(page)
    const navBytes = navHtml ? Buffer.byteLength(navHtml) : 0
    result.samples[path] = {
      bytes,
      navBytes,
      navEntries: navHtml ? countNavEntries(navHtml) : 0,
      navFraction: bytes && navBytes ? Number((navBytes / bytes).toFixed(3)) : 0,
      overrideInUse: navHtml ? usesOverride(navHtml) : false,
      ...(nav ? { navHtml } : {}),
    }
    result.generator ??= extractGenerator(page)
  }
  result.verdict = verdict(result)
  return result
}

// { status, reasons } from the measured samples; pages without a nav (404, redirects) do not count.
export function verdict (result) {
  const withNav = Object.values(result.samples).filter((s) => !s.missing && s.navBytes > 0)
  const maxEntries = Math.max(0, ...withNav.map((s) => s.navEntries))
  const typicalFraction = median(withNav.map((s) => s.navFraction))
  const reasons = []
  if (maxEntries > THRESHOLDS.navEntries) reasons.push(`${maxEntries} nav entries on a page (more than ${THRESHOLDS.navEntries})`)
  if (typicalFraction > THRESHOLDS.navFraction) reasons.push(`the nav is ${(typicalFraction * 100).toFixed(0)}% of a typical page (more than ${THRESHOLDS.navFraction * 100}%)`)
  if (result.siteKiB > THRESHOLDS.siteKiB) reasons.push(`the site is ${result.siteKiB} KiB (more than ${THRESHOLDS.siteKiB} KiB)`)
  if (!reasons.length) return { status: 'ok', reasons }
  const inUse = withNav.length > 0 && withNav.every((s) => s.overrideInUse)
  return { status: inUse ? 'enabled' : 'recommend', reasons }
}

export const EXIT = { ok: 0, enabled: 0, recommend: 3 }

function main (argv) {
  const args = argv.slice(2)
  const samples = []
  let dir
  let json = false
  let out
  let nav = true
  for (let i = 0; i < args.length; i++) {
    const a = args[i]
    if (a === '--json') json = true
    else if (a === '--no-nav') nav = false
    else if (a === '--out' && args[i + 1]) out = args[++i]
    else if (a === '--sample' && args[i + 1]) samples.push(args[++i])
    else if (!a.startsWith('--') && !dir) dir = a
    else { console.error(`Unknown or incomplete argument: ${a}`); return 1 }
  }
  if (!dir || !existsSync(dir)) {
    console.error('Usage: measure.mjs <build-dir> [--json] [--out file] [--no-nav] [--sample path]...')
    return 1
  }
  const r = measure(dir, samples, { nav })
  if (out) writeFileSync(out, JSON.stringify(r, null, 2))
  if (json) console.log(JSON.stringify(r, null, 2))
  else {
    const mb = (n) => (n / 1048576).toFixed(2) + ' MB'
    console.log(`Build:      ${r.buildDir}`)
    console.log(`HTML files: ${r.htmlFiles}`)
    console.log(`HTML size:  ${mb(r.totalHtmlBytes)} (${r.totalHtmlBytes} bytes)`)
    console.log(`Site size:  ${r.siteKiB} KiB`)
    console.log(`Generator:  ${r.generator}`)
    for (const [path, s] of Object.entries(r.samples)) {
      console.log(s.missing
        ? `MISSING    ${path}`
        : `${String(s.bytes).padStart(9)} B  nav ${String(s.navBytes).padStart(8)} B (${(s.navFraction * 100).toFixed(0)}%, ${s.navEntries} entries${s.overrideInUse ? ', override' : ''})  ${path}`)
    }
    console.log(`Verdict:    ${r.verdict.status}${r.verdict.reasons.length ? ` - ${r.verdict.reasons.join('; ')}` : ''}`)
    if (r.verdict.status === 'recommend') console.log('Recommendation: enable the nav-tree override (ui-overrides/README.md)')
  }
  if (Object.values(r.samples).some((s) => s.missing)) return 2
  return EXIT[r.verdict.status]
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = main(process.argv)
