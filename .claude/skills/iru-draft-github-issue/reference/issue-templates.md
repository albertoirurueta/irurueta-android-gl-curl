# Issue body templates

Templates for `iru-draft-github-issue`. A draft file is Markdown whose first line is `# <title>` and whose remainder
is the issue body, so `iru-review-github-issue` and `iru-split-github-issue` can read it with `draft:<path>`.
Omit an optional section entirely rather than leaving it empty or filled with a placeholder (`TBD`, `TODO`, `?`,
"etc." are all review findings). Write the body in the user's language; the section names below may be translated.

## Functional template

```markdown
# <imperative one-line title>

## Context
<Why this is needed: the problem or opportunity, who is affected, what exists today (name real files/modules found
by exploration), and why now.>

## Functional requirements
- FR1. <Observable behaviour, testable, no vague wording.>
- FR2. ...

## Acceptance criteria
- AC1 (FR1). <Verifiable condition: given/when/then or a command whose output can be checked.>
- AC2 (FR2). ...

## Technical details
<Optional. Only when the task is highly technical: files, classes, methods, config keys to change. Every artifact
named must exist (the review verifies it).>

## Out of scope
- <What this issue deliberately does not cover.>

## Dependencies and related issues
- Related: #<n> <title> (<why>)
- Blocked by: none

## Task details
- Due date: <absolute date>            <!-- omit the line when none -->
- Importance: trivial | minor | important | blocking | not specified
- Estimated difficulty: <very easy | easy | medium | hard | very hard | extreme | impossible> - <one-line rationale>

## References
- <URLs, attached files (inline <details> block for small text files, links to `issue-attachments` release assets
  for the rest)>
```

Rules: every functional requirement is covered by at least one acceptance criterion and every criterion names the
requirement it verifies (`ACn (FRm)`); requirements describe behaviour, implementation choices go in Technical
details.

### Worked example (functional)

```markdown
# Add CSV export to the reports page

## Context
Analysts copy report tables into spreadsheets by hand, which loses formatting and takes minutes per report. The
reports page (`src/reports/ReportTable.tsx`) renders the data but offers no export.

## Functional requirements
- FR1. A report page offers an "Export CSV" action that downloads the currently filtered rows.
- FR2. Fields containing the separator, quotes or line breaks are quoted per RFC 4180.

## Acceptance criteria
- AC1 (FR1). With a filter applied, clicking "Export CSV" downloads a file whose rows equal the visible rows.
- AC2 (FR2). A cell containing `a,"b"` appears in the file as `"a,""b"""`.

## Out of scope
- Excel (.xlsx) export; scheduled exports.

## Dependencies and related issues
- Blocked by: none

## Task details
- Importance: important
- Estimated difficulty: easy - one component plus a small serializer, no new dependencies

## References
- RFC 4180: https://www.rfc-editor.org/rfc/rfc4180
```

## Documentation template

```markdown
# <imperative one-line title, e.g. "Document the export module">

## Context and audience
<Why this documentation is needed, who reads it (role, prior knowledge) and what they should be able to do after
reading it.>

## Documentation type
Technical | Non-technical (learning guide)

## Page structure
<Technical: one entry per page that applies, from this list - Architecture, Data model, Use cases, Configuration
properties, Metrics, Algorithms - each with a one-line description of its content.>
<Non-technical: a learning guide - ordered pages from basics to advanced, and under each page the sections, one
per concept to be learned, with the learning outcome of each.>

## Diagrams
<For every flow, architecture, data model or state machine in the page structure: the page, the diagram kind
(Mermaid `flowchart`, `sequenceDiagram`, `erDiagram`, `stateDiagram-v2`, `classDiagram`; or an SVG file when Mermaid
cannot express it) and what it shows. If a page needs none, say why in one phrase.>

## Bibliography and official documentation
- <Title - URL (book, library docs, specification, class or API reference for every technology documented)>

## Acceptance criteria
- AC1. <Verifiable: every listed page exists, the docs build has no warnings, every link resolves, every diagram
  renders.>

## Out of scope
- <What is not documented here.>

## Dependencies and related issues
- Related: #<n> <title> (<why>)
- Blocked by: none

## Task details
- Due date: <absolute date>            <!-- omit the line when none -->
- Importance: trivial | minor | important | blocking | not specified
- Estimated difficulty: <level> - <one-line rationale>

## References
- <Optional: URLs and attached files; omit the section when there are none.>
```

Rules: the page list must match the declared type (technical page names in a learning guide, or the reverse, are a
review finding); at least one bibliography entry per documented technology or concept; each criterion verifiable.

### Worked example (documentation, technical)

```markdown
# Document the export module

## Context and audience
New backend developers cannot tell how exports are scheduled or configured. They must be able to add a new export
format after reading the pages.

## Documentation type
Technical

## Page structure
- Architecture: components (scheduler, queue, writers) and how a request flows through them.
- Data model: export job and export format entities, fields and relations.
- Use cases: export on demand, scheduled export, failed export retry.
- Configuration properties: table of `export.*` keys, type, default, effect.
- Metrics: counters and timers published, name, unit, meaning.
- Algorithms: row batching and back-pressure.

## Diagrams
- Architecture: Mermaid `sequenceDiagram` of request, queue and writer.
- Data model: Mermaid `erDiagram` of ExportJob and ExportFormat.

## Bibliography and official documentation
- Spring Boot reference - https://docs.spring.io/spring-boot/reference/
- RFC 4180 (CSV) - https://www.rfc-editor.org/rfc/rfc4180

## Acceptance criteria
- AC1. The six pages exist and are listed in `nav.adoc`.
- AC2. The Antora build finishes without warnings and both diagrams render.
- AC3. Every configuration key under `export.` in the code appears in the properties table.

## Out of scope
- User-facing guides for the reports page.

## Dependencies and related issues
- Blocked by: none

## Task details
- Importance: minor
- Estimated difficulty: medium - six pages, needs reading the scheduler and writers code
```

For a non-technical guide replace `Page structure` with, for example: "Page 1 What is an export (sections: Why
export, Formats); Page 2 Running your first export (sections: Choosing filters, Downloading, Reading the result)".
