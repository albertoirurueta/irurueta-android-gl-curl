---
name: iru-database-code-one-task
description: "Invoked as /iru-database-code-one-task <task description> via Skill. Generates the queries a database-tagged plan task calls for, grounded in iru-explore findings; executes only read-only queries through a connected database MCP, never writes. Records everything in database-report.md. Used per task by iru-database-code-one-task-group."
model: sonnet
---

# Implement one plan task (Database)

Carry out a single database task from an `implementation_plan.md`-style task list: generate the query/queries it
calls for, execute the ones that are safe to execute, and leave a clear record of both. Unlike
`iru-java-code-one-task`/`iru-dotnet-code-one-task`, this skill's deliverable is usually not application source code —
it's the query text itself (and, where possible, real results from running it), grounded in the actual schema
rather than invented table/column names.

## Step 1 — Ground the task in the real schema and ticket context

- Reuse this conversation's own earlier `iru-explore` findings if they exist (its "Tech stack" section, and, if a
  ticket was fetched, the restated task and the specific files/classes it named) instead of re-running it. If no
  exploration has happened yet in this conversation, run it now — `Skill({skill: "iru-explore"})` (or
  `Skill({skill: "iru-explore", args: "<ticket-id>"})` if a ticket id is already known from context) — to get
  oriented before generating anything.
- From that context, plus a direct look at the actual schema-defining artifacts (migration files, ORM model/
  entity classes, `.sql` schema dumps, seed scripts, or a `docker-compose`/connection-string config naming the
  engine), determine: which database engine is in play (PostgreSQL, MySQL, DB2, SQL Server, MongoDB, Couchbase,
  etc.), and the real table/collection names, column/field names, and relationships the task's queries need to
  reference. Read the actual current definitions rather than trusting the task's own wording alone — a task
  description may use a shorthand or slightly different name than the schema's real identifier.
- If the task names a table/collection/field that doesn't exist anywhere in the real schema, and the mismatch
  isn't a trivial one (singular/plural, an obvious rename), treat this as a blocker per Step 6 rather than
  inventing one.

## Step 2 — Generate the requested queries

- Write the actual query text the task calls for, in the query language matching the detected engine (that
  engine's own SQL dialect for relational databases — e.g. DB2- or PostgreSQL-specific syntax where it differs
  from generic SQL; MongoDB query/aggregation-pipeline syntax; N1QL for Couchbase), grounded in the real names
  from Step 1.
- Classify each query as either:
  - **Read-only/idempotent** — retrieves data with no side effect if run repeatedly (`SELECT`, `find`, an
    aggregation pipeline with no `$out`/`$merge` stage, etc.).
  - **Write** — could create, modify, or delete data or schema (`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`CREATE`/
    `ALTER`/`DROP`, `insertOne`/`updateMany`/`deleteMany`, an aggregation with `$out`/`$merge`, etc.).
- This classification is what Step 3 uses to decide whether to even attempt execution — get it right rather than
  guessing; if a query's effect is genuinely ambiguous, classify it as a write query (the safer default).

## Step 3 — Execute read-only queries via a connected database MCP, if one exists

- Search for a connected database MCP matching the detected engine (`ToolSearch` with queries like the engine's
  own name — "postgres", "postgresql", "db2", "mongodb", "couchbase" — falling back to a generic "database"/
  "sql" search if the engine name itself doesn't turn up a match).
- **No matching MCP connected**: every query from Step 2 stays generated-only. Note this plainly in the report
  (Step 4) rather than silently proceeding as if execution had been attempted.
- **A matching MCP is connected**:
  - For each **read-only/idempotent** query, execute it via the MCP tool and capture whatever it returns (rows/
    documents, or an error).
  - For each **write** query, do not attempt execution at all — these exist to be reviewed and run by a person
    (or a separate, explicitly-authorized process) through the database's own change-management path. Note in
    the report that it was generated only, and why: a write query would be rejected by the MCP's own read-only
    enforcement, and this skill does not attempt to route around that.
  - If executing a query believed to be read-only still fails (a permissions error, a genuine schema mismatch, a
    connection issue), record the failure as returned — don't retry blindly or silently reclassify the query.

## Step 4 — Write or update `database-report.md`

- If `database-report.md` already exists at the repository root (e.g. from an earlier task in the same bucket/
  run), read it and append this task's section rather than overwriting the file — `iru-database-code-one-task-group`
  calls this skill once per task, and every task's queries belong in the same report.
- For each query generated this task, add an entry naming: the task it came from, the query text (in a fenced
  code block tagged with the right language), whether it's read-only or write, and:
  - if executed: the data it returned (or the error), and
  - if not executed: why (no matching MCP connected, or it's a write query).
- Save the file.

## Step 5 — Display to the user and flag the report for review

- Show the user every query generated for this task — and, for any that were executed, the data returned —
  directly in the conversation, not just written to the file.
- State plainly that `database-report.md` was created/updated, that the user should verify its contents
  (especially any real data an executed query returned) before relying on it, and that whether the report
  should be committed is left to a later step in the run — this skill never runs `git add`/`git commit` itself.

## Step 6 — When to interrupt the user

Same bar as any other one-task skill — keep interruptions rare:

- Stop and use `AskUserQuestion` (or plain text if no real choice is being offered) only when the task is
  genuinely ambiguous in a way that changes what query gets generated (e.g. which of two similarly-named
  tables/collections it means, or a filter/aggregation the task doesn't spell out clearly enough to write), or
  when it names something that doesn't exist in the real schema per Step 1.
- Do not stop merely because a query is complex, or because it was classified as a write query that won't be
  executed — generate it and report that clearly instead (Step 7).

## Step 7 — Report the outcome

Hand control back to the caller with a short summary: how many queries were generated for this task, how many
were read-only vs. write, how many were actually executed (and against which MCP/engine) vs. skipped and why,
confirmation that `database-report.md` was updated, and whether this task stopped on a blocker per Step 6.
