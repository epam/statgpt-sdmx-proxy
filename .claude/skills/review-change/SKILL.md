---
name: review-change
description: Run the independent adversarial review loop on an active OpenSpec change in openspec/changes/<name>/ — spawn a fresh critic subagent per round, verify every finding against the real code, apply accepted fixes to the change artifacts, record each round in evidence/review-round-N.md, and repeat until convergence or N rounds. Use ONLY when the user explicitly asks to review or critique a SPECIFIC change by name (for example "review change add-oecd-prewarm"). Do not trigger without a concrete target. Refines planning artifacts only; never writes source code.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
  - Agent
  - AskUserQuestion
  - Bash(openspec:*)
---

# review-change

This skill is lifecycle step 4 in `PROCESS.md`. It improves the planning artifacts of one active
OpenSpec change and nothing else.

## Hard constraint: artifacts only

You must not:

- Edit or create any file outside `openspec/changes/<name>/`.
- Modify source code, build files, configs, READMEs, or main specs under `openspec/specs/`.
- Run builds, tests, or E2E.
- Start implementation. The skill ends with refined artifacts and a hand-back to the user.

Target: `$ARGUMENTS`. The first token is the change name. An optional second token sets the
maximum number of rounds; the default is **4**.

## Phase 0: Resolve and read

1. Run `openspec list --json` and resolve the target to one active change. If the name is
   ambiguous or matches nothing, list the candidates and ask with `AskUserQuestion`.
2. Run `openspec validate <name>`. Fix structural errors in the delta specs (missing scenarios,
   malformed headers) before the first round, and note them in round 1.
3. Read every artifact: `proposal.md`, `design.md`, `tasks.md`, all `specs/**/spec.md`, and
   existing `evidence/review-round-*.md`. Read `CLAUDE.md`, `PROCESS.md`, and the main specs
   the delta touches.
4. The next round number is one more than the highest existing `review-round-<N>.md`.

## Phase 1: Review loop

Repeat for each round until an exit condition fires.

### 1a. Spawn a fresh critic

Use the `Agent` tool with `model: opus` and `subagent_type: general-purpose`. Every round gets a
new agent; never resume the previous critic. The brief is
`.claude/skills/review-change/REVIEWER_PROMPT.md` followed by:

- The change folder path. The critic reads the artifacts itself.
- From round 2 on: a short list of what changed since the previous round, and the findings
  already resolved, so the critic reviews the current artifacts and doesn't repeat itself.

For a high-scope change, you can run two or three critics in parallel with the same brief and
deduplicate their findings.

### 1b. Verify every finding

For each finding, open the cited `file:line`, spec section, or artifact passage and confirm it.
Discard what you can't reproduce against the real code. The critic can hallucinate, and your
verification is the reason the loop runs in-session.

### 1c. Triage

For each verified finding, decide one of the following:

- **Accepted:** fix it in the artifacts.
- **Rejected:** give a one-line reason.
- **Deferred:** out of scope for this change; say where it goes, such as a follow-up change.

For Critical and High findings, prefer Accepted or a justified Deferred over Rejected. If a
finding changes scope or direction, ask the user with `AskUserQuestion` instead of deciding.

### 1d. Apply and record

Apply every Accepted fix to the artifacts. Keep them coherent: a changed requirement updates
its scenarios, the design, and the tasks together. Then write
`evidence/review-round-<N>.md`:

```markdown
# Review round <N>

Date: <YYYY-MM-DD>. Critic: fresh opus subagent.

## Verified findings

1. **[Critical] <artifact> / <section> / `file:line`**: <claim>
   - Evidence: <what you checked>
   - Resolution: **Accepted**: <what changed> | **Rejected**: <why> | **Deferred**: <where>

## Discarded

<count> findings didn't hold up against the code: <one line each, or "none">.

## Exit check

<"converged: no new Critical or High findings" | "continuing" | "round cap reached">
```

Round files are append-only history; never rewrite an earlier round.

### 1e. Exit check

Stop when either condition holds:

- No new Critical or High finding survived verification this round. The change converged.
- The round cap is reached.

Run `openspec validate <name>` once more after the last round.

## Phase 2: Hand back

Tell the user:

- The change name, how many rounds ran, and which exit condition fired.
- The totals: verified findings, and how many were Accepted, Rejected, and Deferred.
- The most important changes the loop made to the artifacts.
- Deferred items and open decisions that need the user.
- The next step: owner approval (checkpoint 2 in `PROCESS.md`). After the user approves, they
  run `/openspec-apply-change <name>`.
