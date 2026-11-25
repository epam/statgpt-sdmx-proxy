# Development process

This file describes how changes move through the repository: features, bug fixes, and defects
found in E2E runs or in production. The specs under `openspec/specs/` are the contract.
`CLAUDE.md` holds the repo rules and code style; this file holds the loop.

## The change lifecycle

Every non-trivial change is an OpenSpec change in `openspec/changes/<name>/`. A trivial change
skips the loop. Trivial means a typo, a log message, a dependency bump with no behavior change,
or a test-only fix.

1. **Explore (optional).** Use `/openspec-explore` to think a problem through before you commit
   to an approach. Explore mode reads code and writes nothing outside the change folder.
2. **Propose.** Use `/openspec-propose <description>`. The author researches the code, asks you
   about scope and approach, and writes four artifacts:
   - `proposal.md`: why, what changes, out of scope, affected registries.
   - `specs/<capability>/spec.md`: the delta (ADDED, MODIFIED, or REMOVED requirements, each
     with scenarios).
   - `design.md`: how, in the section layout from `openspec/config.yaml`.
   - `tasks.md`: dependency-ordered tasks, ending with the Gates group.
3. **Owner read (checkpoint 1).** You read the artifacts and talk them through until you
   understand how the change gets built. This gate comes before the independent review
   on purpose. It catches a change that solves the wrong problem, which a reviewer rarely
   flags.
4. **Independent review.** Use `/review-change <name>`. A fresh critic subagent reviews the
   change folder. The orchestrator verifies each finding against the code, applies the accepted
   fixes to the artifacts, and records the round in `evidence/review-round-<N>.md`. The loop
   repeats until no new Critical or High finding survives verification, or until the iteration
   cap.
5. **Owner approval (checkpoint 2).** You read the refined artifacts and the review rounds.
   Nothing is implemented without your explicit go. If you ask for changes, the loop returns to
   step 2 for that part (`/openspec-update-change`), then step 4.
6. **Apply.** Use `/openspec-apply-change <name>`. Implementation follows `tasks.md` in order,
   with unit tests alongside each task group. If a task no longer fits the code, implementation
   stops and the artifacts are updated first.
7. **Gates.** Use `/run-gates <name>`. The gate ladder runs in order. Each result is recorded in
   `evidence/gates.md`.
8. **Archive.** Use `/openspec-archive-change <name>` after every gate is green. Archiving
   merges the delta specs into `openspec/specs/` and moves the folder to
   `openspec/changes/archive/YYYY-MM-DD-<name>/`.

The user runs every git write operation: commit, branch, push, merge. A session never commits.

## The gate ladder

Gates run in this order. A cheap gate runs before an expensive one, and a failing gate stops the
ladder.

1. **Build and unit tests.** `./gradlew clean build`. All modules compile and every unit test
   passes. A failing test that predates the change is recorded in `evidence/gates.md` with the
   reason it's unrelated, never silently skipped.
2. **Scenario coverage.** Every scenario in the change's delta specs maps to at least one test:
   a unit test or an E2E suite entry. The mapping is a table in `evidence/gates.md`. A scenario
   with no test either gets one or is recorded as manually verified with the command and its
   output.
3. **Independent diff review.** A fresh reviewer subagent (not the implementer) reviews the
   diff against the change artifacts and `CLAUDE.md`. Findings land in
   `evidence/diff-review.md` with a resolution for each. You can also run `/code-review`
   yourself.
4. **E2E, once, last.** Registry-facing changes only: a change that alters a request sent to a
   registry or the handling of a registry response. Run it with the `e2e-report` skill, scoped
   to the affected registry suites when the change is registry-specific. E2E takes 30 to 60
   minutes and hits live registries with rate limits. It never runs during implementation or
   between review rounds. Record the report summary in `evidence/gates.md`.

If E2E fails, the defect cycle runs inside the same change. The re-test runs the failing suite
first, scoped with `--tests`, and the full ladder again only if the fix touched shared code.

## The defect cycle

Use this loop whenever behavior fails a gate: a unit test, an E2E row, or a defect found in
production.

0. **Instrument.** Reproduce the failure as a repeatable probe with captured evidence: a
   fixture-driven unit test (preferred), a captured registry response, or a proxy log. The
   probe must be able to fail. Run one negative control first, a deliberately wrong
   expectation, before you trust any verdict from the probe.
1. **Diagnose.** Localize the failing leg: translator, adapter, conversion, fixture, cache,
   limit emulation, or the registry itself. Establish the root cause from evidence, not
   plausibility. The output is a finding record: symptom, evidence, root cause, proposed fix.
2. **Spec gate.** Does the fix change behavior that a spec describes, or expose behavior that no
   spec states?
   - No: go to step 4.
   - Yes: stop. The spec changes first (step 3). Never fix first and spec later.
3. **Spec change.** Open an OpenSpec change (or reopen the active change's tasks) and run steps
   2 to 5 of the change lifecycle on it.
4. **Implement.** Fix the code and turn the probe into a permanent regression test.
5. **Re-test.** The same probe first; it must now pass. Then the gate ladder from gate 1. E2E
   runs only at the end, per the gate ladder.
6. **Loop.** New failure evidence opens the next iteration at step 0, inside the same change.

Rules for the cycle:

- **Probe fails first.** No verdict comes from an unproven instrument.
- **Unspecified behavior gets a spec line first.** A defect isn't closed by a fix and a test
  while the behavior it exposed is still absent from the specs.
- **One change per finding cluster.** A diagnosis can decompose into several spec deltas in one
  change.
- **Registry and sdmx-core defects are reported, not fixed upstream.** The proxy works around
  them with a fixture or an override in `services/sdmxsource/`, and the workaround says so.

## Roles

One session runs the loop. The main session is the orchestrator: it owns the loop state, makes
the spec-gate decision, verifies review findings, and routes work to subagents. It doesn't author
large artifacts, implement large task groups, or review its own work.

| Role | Model | Work |
|---|---|---|
| Orchestrator | the session model | Loop state, spec gate, finding verification, briefs, reports to you |
| Author | opus subagent | Artifact authoring for large changes, artifact updates after review |
| Critic | opus subagent | Independent artifact review (lifecycle step 4) |
| Diff reviewer | opus subagent | Independent diff review (gate 3) |
| Implementer | sonnet subagent | Task groups from `tasks.md`, with unit tests |
| Runner | sonnet subagent | Gate runs, probes, log and capture forensics |

For a small change, the orchestrator can author and implement in-session. Review always runs on
a separate subagent.

### One fresh agent per pass

A pass is one authoring round, review round, implementation round, or owner-change-request
round. Each pass runs on a fresh subagent, briefed from the change folder plus the specific
findings being addressed. It never resumes an earlier agent's transcript.

- Within one pass, the orchestrator can message the same agent for clarification. Across passes,
  it never does.
- A review pass is a fresh agent every time. A reviewer that re-reviews a revision it ruled on
  before is weaker than a fresh one.
- The brief lives in the change folder. If a fresh agent can't run the pass from the folder, the
  folder is defective; fix that first.

## Artifacts

- **Change folder:** `proposal.md`, `specs/`, `design.md`, `tasks.md`, and `evidence/`.
- **Evidence:** `review-round-<N>.md`, `diff-review.md`, `gates.md`, finding records,
  registry captures, negative-control records. No finding lives only in chat scrollback.
- **Main specs:** `openspec/specs/<capability>/spec.md`. Changed only by archiving a change,
  never by hand.
- **History:** `openspec/changes/archive/`. Entries dated before 2026-11-25 were migrated from
  `docs/designs/`; they keep their three-digit design number in the folder name and have no
  delta specs.
