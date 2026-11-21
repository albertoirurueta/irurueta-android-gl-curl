---
name: iru-database-code-one-task-group
description: "Invoked as /iru-database-code-one-task-group <bucket text> via Skill. Runs iru-database-code-one-task once per task of a database-tagged bucket, always sequentially, then checks off completed tasks in implementation_plan.md and reports the outcome. No baseline or bucket validation."
model: sonnet
allowed-tools: Read Edit Write Bash(git status *) Bash(git diff *) Bash(git log *) Bash(find *) Bash(grep *) Bash(ls *) Skill
---

# Implement one task-group bucket (Database)

Carry out every database task in one plan-group bucket end to end, by delegating each task to
`iru-database-code-one-task` one at a time and keeping `implementation_plan.md` current once the bucket finishes.
This is deliberately simpler than `iru-java-code-one-task-group`/`iru-dotnet-code-one-task-group`: it has no pre-change
quality baseline and no bucket-wide test/coverage/quality validation pass, since there is no single standardized
toolchain across database platforms the way Maven/dotnet provide for Java/.NET — `iru-database-code-one-task` is
trusted to handle each task's own correctness. It uses a medium model on purpose: the plan already carries the
hard reasoning; this skill drives execution of it.

## Step 1 — Implement each task via `iru-database-code-one-task`, one at a time

For every task in the bucket, in the plan's order, invoke:

```
Skill({skill: "iru-database-code-one-task", args: "<the task's full text, including its sub-tasks and any relevant
  Current code state context>"})
```

Wait for it to report back before starting the next task. This skill is always sequential — unlike
`iru-java-code-one-task-group`/`iru-dotnet-code-one-task-group`, it never dispatches tasks to parallel agents, even when
the bucket is marked `Parallelizable: yes` — database changes commonly have a real ordering dependency (a table
must exist before a later migration alters it, seed data depends on schema already being in place) even when a
plan doesn't call that out explicitly.

Record, for each task: how many queries it generated, how many were executed (and against which engine/MCP) vs.
generated-only, and whether it reported a blocker instead of finishing. If a task reports a blocker, don't stop
the loop — move on to the next task in the bucket so the rest of the group's tasks still get attempted, then
surface the blocker in Step 2/3.

## Step 2 — Update `implementation_plan.md` and notify the user

Once every task in the bucket has been attempted (Step 1):

- For each task that completed cleanly, check its own checkbox (`[ ]` → `[x]`) and the checkbox of every
  sub-task that was part of the completed work, and add a short note naming what it did, e.g. `- [x] Task 2.
  **Query active orders by customer** — 2 queries generated (1 executed against PostgreSQL), see
  `database-report.md`.`
- For each task recorded as blocked, leave its checkbox unchecked and add a short blocker note under it (what
  was tried, what failed, why) instead of a completion note.
- Save the file, then notify the user with a short summary of the bucket's outcome: which tasks completed, and
  which (if any) are blocked and why. If any task updated `database-report.md`, repeat that skill's own reminder
  here too: the user should verify the report's contents, and whether to commit it is a decision for a later
  step in the run.

## Step 3 — Report the bucket's outcome

Hand control back to the caller (`iru-code-one-task-group`) with a summary covering the whole bucket: per task, the
queries generated/executed and whether it finished or blocked, plus confirmation that `database-report.md` was
updated if any task in the bucket touched it. This skill has already handled `implementation_plan.md`'s
checkboxes and the user notification itself (Step 2) — the caller does not need to redo that bookkeeping for
this bucket.
