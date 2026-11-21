# Issue quality checklist

Single source of truth for `iru-review-github-issue`. Every item has an id, the check, how to verify it, and one
failing and one passing example sentence. Each violated item becomes one finding of class `gap`, `pending`,
`inconsistency` or `doubt` (see "Finding classes" at the end).

Applicability: **Common** items apply to every issue; **Development** items apply to `Kind: functional`;
**Documentation** items apply to `Kind: documentation`; **Size signals** apply to both and never block a `ready`
verdict on their own (they only produce a `Suggestions:` hint to run `iru-split-github-issue`).

## Common

### C1 — Context explains why
- **Check**: a Context section exists and says why the work matters (the problem, who is affected), not only what to do.
- **Verify**: read the Context section; it must contain a motivation, not just a restatement of the title.
- **Fails**: "Context: add CSV export."
- **Passes**: "Context: finance re-keys the monthly report by hand because the dashboard cannot export; this costs about two hours a month and causes typos."

### C2 — Functional requirements present and testable
- **Check**: requirements exist (functional issues) and each is observable and testable; no vague adjectives.
- **Verify**: scan each requirement for "fast", "nice", "better", "user-friendly", "robust" without a measurable bound.
- **Fails**: "The export should be fast."
- **Passes**: "Exporting 10 000 rows completes in under 5 seconds on the reference environment."

### C3 — Acceptance criteria present and verifiable
- **Check**: acceptance criteria exist and each can be answered yes/no by someone who did not write the issue.
- **Verify**: for each criterion ask "how would I demonstrate this?"; if there is no answer it is not verifiable.
- **Fails**: "Export works well."
- **Passes**: "Given a table with 3 columns, the exported file has a header row with the 3 column names and one line per row."

### C4 — Traceability between requirements and criteria
- **Check**: every requirement has at least one criterion and every criterion maps to a requirement.
- **Verify**: build the requirement-to-criterion matrix; report orphans on either side.
- **Fails**: requirement "Support semicolon separators" with no criterion mentioning separators.
- **Passes**: criterion "AC3 (R2): a file exported with separator `;` uses `;` between every pair of fields."

### C5 — No placeholders
- **Check**: no `TBD`, `TODO`, `FIXME`, `???`, lone `?`, "etc.", "and so on", "to be defined", "XXX".
- **Verify**: grep the body for those tokens; each hit is a `pending` finding.
- **Fails**: "Supported formats: CSV, JSON, etc."
- **Passes**: "Supported formats: CSV and JSON. Any other format is out of scope."

### C6 — No contradictions
- **Check**: scope, numbers, names, defaults and behaviours agree across sections and with comments on the issue.
- **Verify**: collect every number, identifier and default mentioned more than once and compare them; read comments for later corrections.
- **Fails**: Requirements say "maximum 100 rows", a criterion says "1 000 rows are exported".
- **Passes**: both sections say "maximum 1 000 rows".

### C7 — Out of scope stated
- **Check**: an explicit out-of-scope statement exists (even "nothing excluded" with a reason).
- **Verify**: look for an "Out of scope" section or equivalent sentence.
- **Fails**: no mention of what is excluded for a feature that obviously has adjacent features.
- **Passes**: "Out of scope: importing files, scheduled exports, XLSX."

### C8 — Dependencies and related issues referenced
- **Check**: blocking/related issues, PRs or external prerequisites are listed, or "none" is stated; referenced issues resolve.
- **Verify**: for each `#N`, run `gh issue view <n> --json number,title,state`; confirm it exists and matches the description. Look for references the text implies but does not link.
- **Fails**: "Depends on the auth rework." (no link)
- **Passes**: "Blocked by #41 (auth rework, open). Related: #38."

## Development issues (Kind: functional)

### D1 — Technical details only when warranted and verified
- **Check**: technical details (classes, files, lines, endpoints, config keys) appear only when the task is highly technical, and every named artifact exists.
- **Verify**: for each path/class/method, `Read` or grep the repository; confirm it exists and behaves as described. Missing artifacts are `inconsistency`; unnecessary prescriptive detail is a `doubt`.
- **Fails**: "Change `ExportService.write()` at line 120" when no `ExportService` exists.
- **Passes**: "Extend `src/export/CsvWriter.ts` (`writeRow`, currently ignores quoting) to quote fields containing the separator."

### D2 — Requirements stay behavioural
- **Check**: requirements state behaviour, not implementation, unless D1 applies.
- **Verify**: scan for implementation verbs ("use library X", "refactor class Y") outside the technical-details section.
- **Fails**: "Use Jackson to serialize the export."
- **Passes**: "The export output is valid JSON according to RFC 8259."

## Documentation issues (Kind: documentation)

### DOC1 — Page structure given and matching the type
- **Check**: the issue states the documentation type (technical or non-technical) and the page structure. Technical: architecture, data model, use cases, configuration properties, metrics, algorithms (those that apply). Non-technical: a learning guide with pages and sections per concept, ordered from basics to advanced.
- **Verify**: locate the "Page structure" section; compare it with the declared type; flag a technical page list used for a learning guide and vice versa.
- **Fails**: "Document the exporter."
- **Passes**: "Technical: pages Architecture, Data model, Use cases (export, schedule), Configuration properties (table), Metrics, Algorithms (row batching)."

### DOC2 — Bibliography and official documentation
- **Check**: at least one bibliography / official-documentation link per documented technology or concept.
- **Verify**: collect every URL; fetch each with `WebFetch`; unreachable or redirected-to-unrelated pages are `inconsistency`; missing bibliography is a `gap`.
- **Fails**: "See the Spring docs."
- **Passes**: "Bibliography: https://docs.spring.io/spring-boot/reference/ (Spring Boot reference), RFC 4180 (CSV)."

### DOC3 — Diagrams where they aid understanding
- **Check**: wherever a flow, architecture, data model or state machine is described, the issue asks for a Mermaid or SVG diagram and names what it shows.
- **Verify**: list every flow/architecture/model mentioned in the page structure; each needs a diagram entry or an explicit "no diagram needed because...".
- **Fails**: "Describe the export flow in prose."
- **Passes**: "Mermaid `sequenceDiagram` of request, queue and writer in the Architecture page."

### DOC4 — Acceptance criteria for documentation
- **Check**: criteria are verifiable (pages exist, build passes, every link resolves, every diagram renders).
- **Verify**: same test as C3.
- **Fails**: "Docs are complete."
- **Passes**: "The Antora build has no warnings and every listed page exists in `nav.adoc`."

## Size signals

Never blocking; report as `Suggestions:` ("consider /iru-split-github-issue") when any fires.

### S1 — Many requirements or criteria
- **Check**: more than about 7 acceptance criteria or functional requirements.
- **Verify**: count them.
- **Example**: 12 criteria across three unrelated capabilities fire this signal; 4 criteria do not.

### S2 — Multiple independently deliverable capabilities
- **Check**: the issue contains more than one capability that could ship and be tested separately.
- **Verify**: ask whether half of the issue could be released without the other half.

### S3 — Multiple modules or areas touched
- **Check**: more than one module, package, service or documentation area is touched.
- **Verify**: group the referenced files and requirements by module.

### S4 — Estimated difficulty hard or above
- **Check**: the issue's own difficulty estimate (or your assessment from the code) is hard, very hard, extreme or impossible.
- **Verify**: read the Task details section if present; otherwise estimate from D1 findings.

## Finding classes

- **gap**: required information is absent (C1, C2, C3, C4, C7, DOC1-DOC4 missing).
- **pending**: the author deferred something (C5 placeholders, unresolved questions in comments).
- **inconsistency**: two statements, or a statement and the codebase/URL, disagree (C6, D1, DOC2 unreachable).
- **doubt**: the text is present but ambiguous or questionable and a human must decide (vague wording, over-prescriptive details).

Every finding carries the exact question to put to the user, phrased so the answer can be pasted into the issue.
