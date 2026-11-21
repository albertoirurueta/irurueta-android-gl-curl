import { strict as assert } from 'node:assert'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { collectBlocks, diagramType, extractBlocks, lintBlock } from './blocks.mjs'

test('extracts literal and listing blocks with their first source line', () => {
  const text = ['= Page', '', '[mermaid]', '....', 'flowchart LR', '  A --> B', '....', '', '[mermaid,format=svg]', '', '----', 'graph TD', '----'].join('\n')
  assert.deepEqual(extractBlocks(text), [
    { line: 5, src: 'flowchart LR\n  A --> B' },
    { line: 12, src: 'graph TD' }
  ])
})

test('ignores a [mermaid] attribute without a delimited block and other block styles', () => {
  assert.deepEqual(extractBlocks('[mermaid]\ntext\n\n[source,mermaid]\n----\nA\n----'), [])
})

test('flags tag-like text but not Mermaid arrows', () => {
  assert.deepEqual(lintBlock('classDiagram\n  A <|-- B\n  C <--> D\n  E <<->> F'), [])
  assert.deepEqual(lintBlock('flowchart LR\n  A["one<br>two<br/>three <b>bold</b>"]'), [])
  const problems = lintBlock('flowchart LR\n  A["write <timestamp>"]\n  B["&amp;lt;ok&amp;gt;"]\n  class X <<interface>>')
  assert.deepEqual(problems.map((p) => p.offset), [1, 3])
})

test('flags a single-escaped placeholder inside a label, accepts the double-escaped one', () => {
  assert.deepEqual(lintBlock('flowchart LR\n  A["meeting-&lt;timestamp&gt;"]').map((p) => p.offset), [1])
  assert.deepEqual(lintBlock('flowchart LR\n  A["meeting-&amp;lt;timestamp&amp;gt;"]'), [])
  assert.deepEqual(lintBlock('classDiagram\n  class Shape {\n    &lt;&lt;interface&gt;&gt;\n  }'), [])
})

test('flags a single-escaped placeholder in every unquoted label form', () => {
  const lines = [
    'flowchart LR',
    '  A[iru-&lt;key&gt;-bump] --> B',
    '  A(run &lt;key&gt;) --> B',
    '  A{is &lt;key&gt; set} --> B',
    '  A([run &lt;key&gt;]) --> B',
    '  A -->|run &lt;key&gt; now| B',
    '  A -- run &lt;key&gt; --> B',
    '  A[iru-&amp;lt;key&amp;gt;-bump] -->|run &amp;lt;key&amp;gt;| B',
    '  %% a comment about &lt;key&gt;'
  ]
  assert.deepEqual(lintBlock(lines.join('\n')).map((p) => p.offset), [1, 2, 3, 4, 5, 6])
  assert.deepEqual(lintBlock('classDiagram\n  class Foo {\n    +List&lt;String&gt; items\n  }').map((p) => p.offset), [2])
  assert.deepEqual(lintBlock('classDiagram\n  Foo --> Bar : uses &lt;key&gt;').map((p) => p.offset), [1])
  assert.deepEqual(lintBlock('classDiagram\n  class Shape &lt;&lt;interface&gt;&gt;'), [])
})

test('accepts single-escaped text in sequence diagrams', () => {
  assert.deepEqual(lintBlock('sequenceDiagram\n  participant A as svc &lt;key&gt;\n  A->>B: get &lt;key&gt;'), [])
  assert.deepEqual(lintBlock('sequenceDiagram\n  A->>B: get <key>').map((p) => p.offset), [1])
})

test('asks to reword a placeholder in a stateDiagram label, either escaping', () => {
  const problems = lintBlock('stateDiagram-v2\n  s1 --> s2: on &lt;key&gt;\n  s2 --> s3: on &amp;lt;key&amp;gt;\n  state c &lt;&lt;choice&gt;&gt;')
  assert.deepEqual(problems.map((p) => p.offset), [1, 2])
  assert.match(problems[0].message, /reword/)
})

test('detects the diagram type past front matter and comments', () => {
  assert.equal(diagramType('---\ntitle: x\n---\n%% note\n\nsequenceDiagram\n  A->>B: hi'), 'sequenceDiagram')
  assert.equal(diagramType('flowchart LR'), 'flowchart')
  assert.equal(diagramType(''), '')
})

test('collects blocks from pages and partials', () => {
  const dir = mkdtempSync(join(tmpdir(), 'mermaid-blocks-'))
  mkdirSync(join(dir, 'ROOT', 'partials'), { recursive: true })
  writeFileSync(join(dir, 'ROOT', 'partials', 'p.adoc'), '[mermaid]\n....\ngraph LR\n....\n')
  writeFileSync(join(dir, 'ROOT', 'skip.txt'), '[mermaid]\n....\ngraph LR\n....\n')
  const blocks = collectBlocks(dir)
  assert.equal(blocks.length, 1)
  assert.match(blocks[0].file, /p\.adoc$/)
})
