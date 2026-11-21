// Mermaid block extraction and static checks for AsciiDoc sources. Node built-ins only, so it is unit-tested by
// `npm run test:nav` without the on-demand mermaid/jsdom/puppeteer install that validate-mermaid.mjs needs.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

// Every `.adoc` file under `dir` (pages, partials, examples: a partial included into a page ships its diagrams too).
export function adocFiles (dir) {
  const out = []
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) out.push(...adocFiles(path))
    else if (name.endsWith('.adoc')) out.push(path)
  }
  return out
}

// An AsciiDoc Mermaid block is `[mermaid]` (optionally with attributes, e.g. `[mermaid,format=svg]`) followed by a
// `....` or `----` delimited block; the closing delimiter must match the opening one. Returns
// [{ line, src }] where `line` is the 1-based line of the first source line.
export function extractBlocks (text) {
  const lines = text.split('\n')
  const blocks = []
  for (let i = 0; i < lines.length; i++) {
    if (!/^\[mermaid(,[^\]]*)?\]$/.test(lines[i].trim())) continue
    let j = i + 1
    while (j < lines.length && lines[j].trim() === '') j++
    const delimiter = lines[j]?.trim()
    if (delimiter !== '....' && delimiter !== '----') continue
    const body = []
    let k = j + 1
    while (k < lines.length && lines[k].trim() !== delimiter) body.push(lines[k++])
    blocks.push({ line: j + 2, src: body.join('\n') })
    i = k
  }
  return blocks
}

// Static checks that need no Mermaid install. `@sntke/antora-mermaid-extension` writes the block into the page as raw
// HTML and Mermaid reads the element's innerHTML, so a `<` followed by a letter, `/`, `!` or `?` is parsed by the
// browser as a tag (`<<interface>>`, `<timestamp>`): write it as `&lt;`. Arrows (`<|--`, `<-->`, `<<->>`) are safe.
// The formatting tags Mermaid labels support (`<br>`, `<b>`, ...) are intended HTML and pass.
// In label text, `&lt;x` reaches Mermaid as `<x`, which its HTML labels then drop as an unknown tag, whatever the label
// form (quoted, `[..]`, `(..)`, `{..}`, `|..|`, `-- .. -->`, class members, `: ..` transitions): text that must show
// `<x>` is written `&amp;lt;x&amp;gt;`. Exceptions, verified by rendering in headless Chrome:
//   - stereotypes (`&lt;&lt;interface&gt;&gt;`, `&lt;&lt;choice&gt;&gt;`) are Mermaid syntax and stay single-escaped;
//   - sequenceDiagram renders its text without HTML, so `&lt;key&gt;` is right there (double-escaped fails to parse);
//   - stateDiagram drops `&lt;key&gt;` and garbles `&amp;lt;key&amp;gt;` (the entity's `;` splits the statement), so
//     the label is reworded.
// Returns [{ offset, message }] with `offset` the 0-based line inside the block.
const LABEL_TAGS = /^<\/?(br|b|i|em|strong|u|sub|sup|small)\s*\/?>/i

// The diagram keyword (`flowchart`, `sequenceDiagram`, ...): the first line outside front matter and `%%` comments.
export function diagramType (src) {
  let frontMatter = false
  for (const raw of src.split('\n')) {
    const line = raw.trim()
    if (line === '---') { frontMatter = !frontMatter; continue }
    if (frontMatter || line === '' || line.startsWith('%%')) continue
    return line.split(/\s/)[0]
  }
  return ''
}

export function lintBlock (src) {
  const type = diagramType(src)
  const sequence = type === 'sequenceDiagram'
  const state = type.startsWith('stateDiagram')
  const escaped = state ? /&(?:amp;)?lt;([A-Za-z/!?][^\s&]*)/g : /&lt;([A-Za-z/!?][^\s&]*)/g
  const problems = []
  src.split('\n').forEach((line, offset) => {
    for (const m of line.matchAll(/<[A-Za-z/!?]/g)) {
      if (LABEL_TAGS.test(line.slice(m.index))) continue
      const tag = line.slice(m.index).match(/^<[^\s>]*>?/)[0]
      problems.push({ offset, message: `"${tag}" is parsed as an HTML tag by the browser; write "&lt;" instead` })
      break
    }
    if (sequence || line.trim().startsWith('%%')) return
    for (const m of line.matchAll(escaped)) {
      if (line.slice(0, m.index).endsWith('&lt;')) continue // second `<` of a `&lt;&lt;stereotype&gt;&gt;`
      if (LABEL_TAGS.test(`<${m[1]}>`)) continue
      problems.push({
        offset,
        message: state
          ? `"${m[0]}" in a stateDiagram label is dropped or garbled whichever way it is escaped; reword the label`
          : `label text "&lt;${m[1]}" is dropped by Mermaid as an HTML tag; write "&amp;lt;" to show "<"`
      })
      break
    }
  })
  return problems
}

// Collects every block under `root`: [{ file, line, src }].
export function collectBlocks (root) {
  const all = []
  for (const file of adocFiles(root)) {
    for (const block of extractBlocks(readFileSync(file, 'utf8'))) all.push({ file, ...block })
  }
  return all
}
