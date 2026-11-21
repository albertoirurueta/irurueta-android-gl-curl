---
name: iru-knowledge-process-inbox
description: "/iru-knowledge-process-inbox - files every meeting transcription (timestamped or not, diarized or not) and attachment in knowledge/inbox into knowledge/: a meeting.md per meeting, people files per speaker, an agreement file of numbered checkboxes and one pending file per item, then runs iru-knowledge-compact to dedupe pending tasks and check off agreements whose linked issue/ticket is closed (it asks before deleting or editing). Requires iru-knowledge-setup first. Use when new meeting transcriptions or documents land in knowledge/inbox."
model: sonnet
---

# Knowledge Process Inbox

File every meeting transcription (and its supporting documents) currently sitting in `knowledge/inbox` into
`knowledge/meetings`, `knowledge/people`, `knowledge/agreements`, and `knowledge/pending`.

Requires the `knowledge/` structure created by `iru-knowledge-setup` to already exist.

Two properties of a transcription vary independently and neither is guaranteed:

- **Timestamped or not** — lines may or may not carry a time marker (`[00:01:23]`, `10:03:15`, etc.).
- **Diarized or not** — lines may or may not be attributed to a named speaker (`Jane Doe:`, `[Speaker 2]`,
  `John (10:03):`).

Detect both per transcription rather than assuming one shape for every file — they change what's achievable in
Steps 5 and 7, not what's required to run the rest of this skill.

Throughout this skill, `<timestamp>` means the same **filesystem-safe ISO 8601** value:
`YYYY-MM-DDTHH-MM-SSZ` — colons replaced with hyphens so the string is valid in a directory/file name on every
OS. The same `<timestamp>` for one meeting is reused across its meeting directory, its agreement file, and every
pending file it produces.

## Step 1 — List the inbox

List every file in `knowledge/inbox`. If it's empty, report that there's nothing to process and stop here.

## Step 2 — Group files into meetings

Usually every file in `knowledge/inbox` belongs to one meeting's worth of material (one transcription plus its
attachments) — default to treating the whole inbox as a single group.

Split into more than one group only when there's clear evidence of more than one meeting in the inbox at once:
more than one file that reads as a standalone transcription (dialogue/speaker structure or a clear meeting-log
shape, not just a long document) — each anchors its own group. When that happens, assign every remaining
non-transcription file to whichever transcription's group it plausibly belongs to, using filename
prefixes/dates, explicit references inside the file's own content, or a file timestamp close to that
transcription's.

If the grouping is genuinely ambiguous (a shared attachment that could belong to either transcription, or a
file with no signal pointing either way), ask the user via `AskUserQuestion` to confirm the grouping before
proceeding — don't guess silently.

Process each group through Steps 3-10 below, one at a time.

## Step 3 — Read every file in the group

Read the transcription file in full. Attempt to read every other file in the group too, using whatever tool
fits its type (`Read` handles text, PDF, and image files directly) — this is "additional context if possible",
not a requirement. If a file can't be read (unsupported or corrupt binary), note that in the final report but
still copy it into the meeting directory in Step 6 — being filed alongside the meeting and being readable for
context are separate concerns.

## Step 4 — Determine the meeting timestamp

In priority order:

1. An explicit date/time already present in the transcription's own content (a header line, or timestamps
   within the dialogue).
2. A date encoded in a filename in the group.
3. The transcription file's filesystem timestamp (creation or last-modified).
4. If none of the above yields a usable date/time, ask the user for the meeting's date/time.

Format the result as the filesystem-safe ISO 8601 `<timestamp>` defined above.

## Step 5 — Detect diarization and timestamps

Per the definitions above, note for this group's transcription whether it's diarized and whether it's
timestamped. This only changes whether Step 7 (people files) runs — Steps 6 and 8 proceed the same regardless.

## Step 6 — Create the meeting directory and meeting.md

Create `knowledge/meetings/meeting-<timestamp>/`. Write `meeting.md` inside it with exactly two sections:

```markdown
## Summary

<summary of the transcription's discussion, incorporating relevant context from every other file in the
group that could be read in Step 3>

## Full transcription

<the transcription's original content, copied verbatim — including whatever timestamps/speaker labels it
has or lacks; don't reformat it>
```

Copy every other file in the group into the same meeting directory unchanged (byte-for-byte), regardless of
whether Step 3 could read it.

## Step 7 — Update people files (diarized transcriptions only)

Skip this step entirely if Step 5 found no diarization — there's no reliable way to attribute contributions to
a named person otherwise.

For each distinct speaker identified:

- Derive a filename `knowledge/people/<name>.md` — lowercase, hyphen-joined first and last name (e.g. "Jane
  Doe" → `jane-doe.md`). If only a first name or handle is available, use that alone.
- **File doesn't exist yet**: create it with a summary of the person's role (as inferred from context) and
  what they talked about in this meeting.
- **File already exists**: read it first, then update it — merge in what this meeting adds (new context on
  their role, this meeting's contribution) rather than overwriting or duplicating what's already recorded
  there.

## Step 8 — Extract agreements

Read through the transcription (and readable supporting files) for explicit agreements, decisions, or action
items reached during the meeting.

- **None found**: skip Step 9 for this group and note that in the final report.
- **Found**: create `knowledge/agreements/agreement-<timestamp>.md` (same `<timestamp>` as the meeting
  directory) listing every agreement as a numbered, unchecked markdown checkbox, numbered sequentially from 1
  within this file:

  ```markdown
  1. [ ] <agreement text>
  2. [ ] <agreement text>
  ```

  Word each item as a concrete, standalone statement of what was agreed or what needs doing — its own pending
  file (Step 9) may end up being the only place this task is tracked from, so it must stay understandable on
  its own.

## Step 9 — Create pending files for each agreement

Skip if Step 8 produced no agreements.

For every numbered item in the agreement file, create `knowledge/pending/pending-<timestamp>-<n>.md`, where
`<timestamp>` is this meeting's timestamp and `<n>` is that item's number in the agreement file. Together,
`<timestamp>-<n>` is that task's id, referenced later by `iru-knowledge-complete`. Content:

```markdown
# Task <timestamp>-<n>

<the agreement item's text, verbatim>

- Agreement: knowledge/agreements/agreement-<timestamp>.md, item <n>
- Meeting: knowledge/meetings/meeting-<timestamp>/
```

Every agreement extracted in Step 8 gets a pending file — there's no such thing as an agreement that's already
complete at the moment it's extracted from a meeting that just happened. Don't skip an item here because an
earlier meeting already produced a task that looks like it, either: this meeting genuinely re-raised it, and
collapsing the two is Step 11's job, made with the whole knowledge base in view rather than from this one
group.

## Step 10 — Clear this group's files from the inbox

Once this group's files are copied into its meeting directory (Step 6) and its `meeting.md` contains the full
transcription verbatim, delete this group's original files from `knowledge/inbox` — they're now filed, and a
re-run of this skill shouldn't reprocess them into a second, duplicate meeting entry. Only do this once Steps
6-9 for this group have actually completed — leave a group's inbox files in place if any step for it had to
stop on an unresolved question, so nothing is lost.

## Step 11 — Compact the knowledge base

Run this **once for the whole run**, after every group from Step 2 has been through Steps 3-10 — not once per
group. Compaction is only meaningful with all of this run's new agreements and pending tasks already on disk,
since what it's looking for spans meetings.

Skip it, and say so in Step 12, if this run created no agreement or pending files at all (every group hit
Step 8's "none found" case, or the inbox turned out to be empty) — there is nothing new for it to reconcile.
Otherwise run:

```
Skill({skill: "iru-knowledge-compact"})
```

What it does for this run, and what to keep in mind while it runs:

- **Duplicates.** A meeting very often re-raises an action item an earlier meeting already agreed — Step 9
  deliberately files that as its own pending task rather than judging it in isolation. `iru-knowledge-compact`
  is what then groups those tasks, keeps the one that already carries a tracker issue (or the earliest, if none
  do), merges the newer task's extra context into it, and removes the rest. This is the main reason this step
  exists, and why it runs over the whole knowledge base rather than just this run's files.
- **Completed agreements.** For any pending task or open agreement item carrying a `- GitHub issue:`/`- Jira
  ticket:` line (added by `iru-knowledge-to-issue`/`iru-knowledge-to-jira`), it reads the issue's current
  state and checks off the ones already closed as completed. A meeting frequently restates work that shipped
  weeks ago; this is what stops that from re-entering the base as an open item.
- **It asks before changing anything.** `iru-knowledge-compact` presents its full plan of deletions/edits for
  confirmation. Pass that plan to the user as it comes and let them answer — do not approve it on their behalf,
  and do not pre-empt it by deleting or checking off anything here yourself.
- **It only ever removes.** It never creates or rewords an agreement, and it never touches `knowledge/meetings`
  or `knowledge/people` — the meeting records this skill just wrote are safe from it.

Two cases to handle rather than treat as errors:

- **`iru-knowledge-compact` isn't installed** in this repository's `.claude/skills/` (this catalog's skills are
  adopted individually, so it may not have been copied in): skip this step and note it in Step 12, pointing the
  user at the skill as an optional add-on. Never hand-roll its dedup/completion logic here.
- **It stops early or reports inconsistencies** (no tracker reachable, an issue lookup that failed, an item it
  declined to touch): that's its own reporting to make. Carry its outcome into Step 12 unchanged; this skill's
  filing work in Steps 3-10 is already complete and correct either way, so a compaction that couldn't run is
  never a reason to undo any of it.

## Step 12 — Report

Per meeting group processed: the meeting directory created, whether it was diarized (and how many people files
were created vs. updated, with names), how many agreements were found (or none), and how many pending files
were created. List any inbox files left behind because processing stopped on an unresolved question, and why.

Then report Step 11's compaction outcome as its own section — duplicates collapsed, agreements closed out from
their issue status, anything it left for a human decision — or why it was skipped. Keep this separate from the
filing summary above, so it stays clear which pending files this run created and which ones compaction then
removed.

Remind the user that `iru-knowledge-complete` is what later marks an agreement item done and removes its
pending file, for work whose completion no tracker issue can prove.
