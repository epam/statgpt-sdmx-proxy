---
name: run-gates
description: Run the gate ladder from PROCESS.md on an implemented OpenSpec change — build and unit tests, scenario coverage, independent diff review, then E2E once at the end for registry-facing changes — and record every result in the change's evidence/gates.md. Use ONLY when the user explicitly asks to run gates on a SPECIFIC change by name, or after /openspec-apply-change finishes and the user says to run the gates. Never commits or pushes.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
  - Bash
  - PowerShell
  - Agent
  - AskUserQuestion
  - Skill
---

# run-gates

This skill is lifecycle step 7 in `PROCESS.md`. It runs the gates in order, stops at the first
red gate, and records results in `openspec/changes/<name>/evidence/gates.md`.

Target: `$ARGUMENTS`, the change name. Resolve it with `openspec list --json`; if it's ambiguous,
ask with `AskUserQuestion`.

Before you start, read `tasks.md`. If any non-Gates task is unchecked, stop and tell the user;
the gates run on a finished implementation.

## Gate 1: Build and unit tests

Run `./gradlew clean build`. Delegate the run to a fresh sonnet subagent when the output is
long; it returns the result, test counts, and the failing tests with their first error lines.

- Green: record the command, the test counts, and the date.
- Red: stop the ladder. Report the failures. A failure that predates the change needs evidence
  that it also fails without the change (for example, the same test on a clean checkout of the
  base commit, run by the user). Record it as unrelated only with that evidence.

## Gate 2: Scenario coverage

List every scenario in the change's delta specs (`specs/**/spec.md`). For each, find the test
that exercises it: a unit test class and method, or an E2E suite and config entry. Build a table
in `evidence/gates.md`:

| Requirement | Scenario | Test | Status |
|---|---|---|---|

A scenario with no test either gets one (go back to apply for it) or is recorded as manually
verified, with the exact command and its observed output. An uncovered scenario keeps the gate
red.

## Gate 3: Independent diff review

Spawn a fresh opus subagent (`subagent_type: general-purpose`, `model: opus`) that didn't take
part in implementation. Brief it with the change folder path, `CLAUDE.md`, and the instruction
to run `git diff` and `git status` itself (read-only) and review the diff for:

- Correctness bugs and regressions against the delta specs and `design.md`.
- Deviations from `tasks.md`: missing work, or scope that no task asked for.
- `CLAUDE.md` rules: code style, the config README schema tables, line endings.

Verify each returned finding against the code yourself. Write `evidence/diff-review.md` with
each verified finding and its resolution (Fixed, Rejected with reason, or Deferred with where).
Fixes go back through apply; after fixes, rerun gate 1 before you continue.

## Gate 4: E2E, once, last

Run this gate only when the proposal marks the change registry-facing. Otherwise record
"skipped: not registry-facing" and finish.

Ask the user before you start: E2E takes 30 to 60 minutes and calls live registries. Then
invoke the `e2e-report` skill, scoped to the registry suites the proposal names. Record the
summary in `evidence/gates.md`: suites, passed and failed counts, and failures attributed to
the change versus the registry (rate limits, outages).

If a failure is attributed to the change, the defect cycle in `PROCESS.md` takes over. The
re-test runs the failing suite first.

## Finish

Check the Gates tasks in `tasks.md` that passed. Report to the user:

- Each gate: green, red, or skipped, with the numbers.
- What remains, if a gate is red.
- When all gates are green, the next step: `/openspec-archive-change <name>`.

Never commit, branch, stash, or push.
