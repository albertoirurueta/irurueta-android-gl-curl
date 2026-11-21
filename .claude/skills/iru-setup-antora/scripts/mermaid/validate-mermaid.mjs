#!/usr/bin/env node
// Validates every Mermaid diagram of an Antora module: a green Antora build proves nothing about them, because
// `@sntke/antora-mermaid-extension` only copies the source into the page and Mermaid renders it in the reader's browser.
//
//   static   `<` followed by a letter, `/`, `!` or `?` (parsed by the browser as an HTML tag), and single-escaped
//            `&lt;` in label text, which Mermaid drops silently (rules in blocks.mjs); no install needed
//   parse    mermaid.parse() of the text the browser hands to Mermaid (after HTML parsing and entity decoding)
//   render   (--render) mermaid.render() of each diagram in headless Chrome: catches layout crashes that parse
//            accepts, e.g. "Cannot set properties of undefined (setting 'order')" on a very large flowchart with
//            many subgraphs
//
// mermaid, jsdom and puppeteer are not dependencies of the docs toolchain (validation only); install them on demand,
// from docs/, with the command in reference/toolchain.md:
//     npm i --no-save mermaid@11 jsdom@31 puppeteer@25
// Usage (from docs/):
//     node scripts/mermaid/validate-mermaid.mjs [<modules-dir>] [--render]      (npm run validate:mermaid)
// Exit code: 0 every diagram passed, 1 failures (listed as file:line), 2 missing dependency or no diagram found.
import { createRequire } from 'node:module'
import { relative, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { collectBlocks, lintBlock } from './blocks.mjs'

const args = process.argv.slice(2)
const render = args.includes('--render')
const root = resolve(args.find((a) => !a.startsWith('--')) ?? 'modules')
const require = createRequire(pathToFileURL(resolve('package.json')))

function missing (name) {
  console.error(`validate-mermaid: ${name} is not installed. From docs/, run:\n\n    npm i --no-save mermaid@11 jsdom@31 puppeteer@25\n`)
  process.exit(2)
}

async function load (name) {
  try {
    return await import(pathToFileURL(require.resolve(name)).href)
  } catch (error) {
    if (error?.code === 'MODULE_NOT_FOUND' || error?.code === 'ERR_MODULE_NOT_FOUND') missing(name)
    throw error
  }
}

const blocks = collectBlocks(root)
if (blocks.length === 0) {
  console.error(`validate-mermaid: no [mermaid] block under ${relative(process.cwd(), root) || '.'}`)
  process.exit(2)
}
const where = (b, offset = 0) => `${relative(process.cwd(), b.file)}:${b.line + offset}`
const failures = []

for (const b of blocks) {
  for (const p of lintBlock(b.src)) failures.push(`${where(b, p.offset)}  static: ${p.message}`)
}

// Parse what the browser hands to Mermaid: the block's innerHTML after HTML parsing, entities decoded.
const { JSDOM } = await load('jsdom')
const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true })
globalThis.window = dom.window
globalThis.document = dom.window.document
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true })
const mermaid = (await load('mermaid')).default
mermaid.initialize({ startOnLoad: false })
const container = document.createElement('div')
const decoder = document.createElement('textarea')
const asBrowserSeesIt = (src) => {
  container.innerHTML = src
  decoder.innerHTML = container.innerHTML
  return decoder.value
}
const firstLines = (e) => String(e?.message ?? e).split('\n').slice(0, 4).join(' | ')
const parsed = []
for (const b of blocks) {
  try {
    await mermaid.parse(asBrowserSeesIt(b.src))
    parsed.push(b)
  } catch (error) {
    failures.push(`${where(b)}  parse: ${firstLines(error)}`)
  }
}

if (render) {
  const puppeteer = (await load('puppeteer')).default
  const browser = await puppeteer.launch({ args: ['--no-sandbox'] })
  try {
    const page = await browser.newPage()
    await page.setContent('<!doctype html><html><body></body></html>')
    await page.addScriptTag({ path: require.resolve('mermaid/dist/mermaid.min.js') })
    await page.evaluate(() => window.mermaid.initialize({ startOnLoad: false }))
    let n = 0
    for (const b of parsed) {
      const error = await page.evaluate(async (src, id) => {
        const holder = document.createElement('div')
        holder.innerHTML = src
        const text = document.createElement('textarea')
        text.innerHTML = holder.innerHTML
        try {
          await window.mermaid.render(id, text.value)
          return null
        } catch (e) {
          return String(e?.message ?? e)
        } finally {
          document.querySelectorAll(`#${id}, #d${id}`).forEach((el) => el.remove())
        }
      }, b.src, `mmd${n++}`)
      if (error) failures.push(`${where(b)}  render: ${error.split('\n').slice(0, 4).join(' | ')}`)
    }
  } finally {
    await browser.close()
  }
}

const checks = render ? 'static, parse and render' : 'static and parse'
if (failures.length) {
  console.error(`${failures.length} problem(s) in ${blocks.length} Mermaid diagram(s) (${checks} checks):\n`)
  for (const f of failures) console.error(`  ${f}`)
  process.exit(1)
}
console.log(`All ${blocks.length} Mermaid diagrams passed the ${checks} checks.`)
