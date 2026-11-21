---
name: iru-knowledge-setup
description: "/iru-knowledge-setup - creates the knowledge/ directory with inbox, meetings, people, agreements and pending subdirectories, each with a .gitkeep. Idempotent: only creates what is missing, never overwrites or deletes. Use once per repository before iru-knowledge-process-inbox or iru-knowledge-complete, to bootstrap the knowledge base folders."
model: haiku
---

# Knowledge Setup

Bootstrap the `knowledge/` directory structure at the repository root that `iru-knowledge-process-inbox` and
`iru-knowledge-complete` depend on.

## Step 1 — Check current state

Check whether `knowledge/` already exists at the repository root, and which of its five subdirectories
(`inbox`, `meetings`, `people`, `agreements`, `pending`) already exist. This skill is idempotent — never delete
or modify any existing content; only fill in what's missing.

## Step 2 — Create the directory structure

For each of `inbox`, `meetings`, `people`, `agreements`, `pending` under `knowledge/` that doesn't already
exist, create it. In every one of the five directories — both pre-existing and newly created — ensure a
`.gitkeep` file exists: git doesn't track empty directories, so `.gitkeep` is what lets these directories survive
a commit before real content lands in them. Skip adding `.gitkeep` to a directory that already contains other
files — it no longer needs one.

## Step 3 — Report

List each of the five directories with its status (created / already existed) and whether a `.gitkeep` was
added. Confirm the knowledge base is ready for `iru-knowledge-process-inbox`.
