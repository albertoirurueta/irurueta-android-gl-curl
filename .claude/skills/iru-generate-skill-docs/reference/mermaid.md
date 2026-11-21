# Mermaid diagrams in Antora pages

Shared, byte-identical, by every catalog skill that writes or checks Antora pages (`iru-setup-antora`,
`iru-update-antora`, `iru-build-docs`, `iru-update-docs`, `iru-generate-skill-docs`,
`iru-update-java-springboot-documentation`); change one, copy it to all.

## Why a green build proves nothing

`@sntke/antora-mermaid-extension` does not parse Mermaid. It copies the `[mermaid]` block into the page as raw HTML
(a `div.mermaid`), and Mermaid parses, lays out and draws it in the reader's browser. A diagram with an error builds
without a warning and shows Mermaid's error graphic ("Syntax error in text", a bomb icon) only on the published page.
Every diagram written or changed must therefore be validated on its own.

## Writing rules

- **Tag-like text.** The browser parses the block as HTML, Mermaid reads the element's `innerHTML` and decodes its
  entities, and labels are then rendered as HTML again (`htmlLabels`). So:
  - Mermaid **syntax** that contains `<` followed by a letter, `/`, `!` or `?` (class-diagram stereotypes such as
    `<<interface>>`, `<<enumeration>>`) is written single-escaped: `&lt;&lt;interface&gt;&gt;`. Raw, the browser turns it
    into an `<interface>` element whose closing tag lands after the diagram's last line, and the diagram fails.
  - **Label text** that must show `<…>` (a placeholder such as `<timestamp>` or `<key>`) is written double-escaped:
    `"iru-&amp;lt;key&amp;gt;-bump-version"`. Single-escaped (`&lt;key&gt;`) it reaches Mermaid as `<key>` and the label
    drops it silently (`iru--bump-version`), with no error anywhere. This holds for every label form, quoted or not:
    `["…"]`, `[…]`, `(…)`, `{…}`, `|…|` and `-- … -->` edge labels, class members (`+List&amp;lt;String&amp;gt;`),
    class and ER relationship labels. Rewording the label without angle brackets is also fine.
  - Two diagram types differ (verified by rendering). **`sequenceDiagram`** renders its text without HTML, so there
    `&lt;key&gt;` is right and the double-escaped form fails to parse. **`stateDiagram`** drops `&lt;key&gt;` and
    garbles `&amp;lt;key&amp;gt;` (the entity's `;` ends the statement), so reword the label instead.
  - Arrows (`-->`, `-.->`, `==>`, `<-->`, `<|--`, `<<->>`) and the label formatting tags Mermaid supports (`<br>`,
    `<b>`, `<i>`, `<sub>`, `<sup>`) stay as they are. Do not use Mermaid's `#60;`/`#62;` codes: they work in `["…"]`
    and `{"…"}` nodes but throw `Invalid character` in stadium (`(["…"])`) and subroutine (`[["…"]]`) shapes.
- **Quotes in labels.** Mermaid has no backslash escape: `G["Report \"x\""]` ends the string at the first inner quote
  and the whole diagram fails. Reword the label, or use single quotes inside the double-quoted label (not `#quot;`,
  for the same reason as `#60;`).
- **Line breaks.** `\n` inside a quoted label produces a line break.
- **Size.** Keep each flowchart small enough for the dagre layout: as a rule of thumb, at most about 60 nodes,
  150 edges and 10 subgraphs per diagram. A very large flowchart with many subgraphs can parse and still crash while
  laying out ("Cannot set properties of undefined (setting 'order')"); split it into an overview diagram (one node per
  group, edges aggregated between groups) plus one diagram per group instead.
- **Delimiters.** Write `[mermaid]` followed by a `....` (literal) block; `----` also works. The validator finds
  only these two forms.

## Validating

From `docs/`, after the build:

```bash
npm i --no-save mermaid@11 jsdom@31 puppeteer@25
node scripts/mermaid/validate-mermaid.mjs modules --render     # npm run validate:mermaid when the script is wired
```

- **static**: raw tag-like text, and single-escaped `&lt;` in label text of any form (any escaping in a `stateDiagram`
  label); no install needed.
- **parse**: `mermaid.parse()` of the text the browser hands to Mermaid (after HTML parsing and entity decoding); a
  plain `mermaid.parse()` of the source misses the tag problem above.
- **render** (`--render`): `mermaid.render()` of each diagram in headless Chrome; the only check that catches layout
  crashes.

Exit 0 means every diagram passed; 1 lists each failure as `file:line` with the check that failed; 2 means a missing
dependency or no diagram found. Use the repository's own `docs/scripts/mermaid/` when present; otherwise run the copy
under `iru-setup-antora`'s, `iru-update-antora`'s or `iru-build-docs`' `scripts/mermaid/` with `docs/` as the working
directory (it resolves the three packages from `docs/node_modules`).

When puppeteer cannot run (no Chrome download allowed, no sandbox), run without `--render`, say so in the report, and
open the changed pages of the built site in a browser to confirm every diagram drew as an SVG rather than the error
graphic.
