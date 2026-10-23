# Hub merge order

The order the open hub pull requests merge into `console-root`, kept by the hub steward (the
search thread, since 2026-10-23 16:00 UTC) and updated whenever a hub PR opens, moves or merges.
This branch, `console-root--stack`, is every PR below merged in this order with the conflicts
resolved; CI runs the hub checks on it. It shows the end result is green and holds the
resolutions. It is never merged itself.

## The order

| Turn | PR | Branch → base | What it carries | Before its turn |
|---|---|---|---|---|
| 1 | #2 Phase 0 hub | `console-root--phase-0` → `console-root` | Decisions 1 to 23, the contracts, wfrn, overlays and the checks | Ready |
| 2 | #25 Comma-cut descriptions | `console-root--contract-descriptions` → phase-0 | 34 descriptions quoted; `npm run check` fails on an entry with no value | Ready |
| 3 | #11 Search contract 1.1.0 | `console-root--search-indexer-contract` → phase-0 | Counts, seven sorts, the drift filter, the status scope | Ready |
| 4 | #20 Search contract 1.2.0 | `console-root--search-copilot-contract` → #11 | Plain English, grouped counts, typos, times | Ready |
| 5 | #15 Retrying a failed create | `console-root--state-retry` → phase-0 | Decision 24 | Ready |
| 6 | #18 Cost contract | `console-root--cost-contract` → phase-0 | Decision 25, P-22 price lists; it carries #15 | Base it on `console-root--state-retry` so its diff is the cost alone |
| 7 | #22 Identity contract | `console-root--identity-contract` → cost-contract | Decision 28: boundaries, guardrails, the simulator, quota rules | Merge the current `console-root--cost-contract` in: it branched from a cost commit that was later rebuilt on #15 |
| 8 | #14 Drift contract | `console-root--drift-contract` → phase-0 | Person-bound reverts, `revertStatus`, the 409 codes, internal operations | Ready |
| 9 | #17 Drift finder and monitoring | `console-root--drift-finder-monitoring` → #14 | Shadow infrastructure, monitoring, rightsizing, drift numbers | Ready |
| 10 | #19 Scorecards contract | `console-root--scorecards-contract` → #14 | Decision 26, the rule packs | Merge #17's branch in and base it on #17 (both add to the drift contract in the same places); quote 2 descriptions |
| 11 | #27 State contract 1.1.0 | `console-root--state-phase-9` → #15 | Decision 29: lineage, blast radius, the map, the sealed trail, time travel | Renumber its decision from 28 to 29 (identity claimed 28 first) |
| 12 | #16 The BFF's phase 9 paths | `console-root--phase-9-web` → phase-0 | Decision 27: the executive home, the copilot, the failure explainer | Merge `console-root` in after turn 11 (the BFF contract); quote 2 descriptions |

## How to merge

- **One at a time, top to bottom.** Use "Create a merge commit". Never squash or rebase: the later PRs carry the earlier ones' commits, and a squashed parent makes every child conflict.
- **Retargeting.** When a PR merges, the PRs based on its branch are moved to `console-root`. GitHub does this when the merged branch is deleted; otherwise the steward does it. Their diffs don't change.
- **Conflicts.** A PR whose "Before its turn" is not "Ready" conflicts with the PRs above it. The steward or the PR's owner pushes the resolution to that PR before its turn, the same one this branch holds.
- **Stopping part way is fine.** Once each PR's "Before its turn" step is done, stopping after any turn leaves `console-root` green. Every step of this branch passes the hub checks.

## Decision numbers

24 retrying a failed create (#15), 25 cost (#18), 26 scorecards (#19), 27 the BFF's phase 9 paths
(#16), 28 custom policies and quota rules (#22), 29 the state service's relationships and time
travel (#27). **The next free number is 30.** Claim a number here, or with the steward, before a
branch uses it.

## The conflicts this branch resolves

- **#22 after #18.** `console-root--cost-contract` was rebuilt on #15 after #22 branched from it. The resolution takes cost-contract's side in `console-bff.yaml` and `console-cost-service.yaml`; the two sides differ only in quote style. It keeps #22's identity contract, its events and its decision row.
- **#19 after #17.** Both add to `console-drift-service.yaml` in the same places: the description, the tags, the internal paths and the schemas. The resolution keeps both, the scorecards after the monitoring. The BFF's tags keep Cost, Budgets and Scorecards. The events description lists `drift.scorecards` and `cost.events`. The hub README and the contracts README list both.
- **#27 after #22.** Both numbered their decision 28; #27's becomes 29.
- **#16 last.** Its BFF paths, schemas and tags interleave with those of #17, #18, #19, #22 and #27. The resolution keeps both sides of every one.
- **Cut descriptions.** The check from #25 flags two in #19 (`EvaluationChanged`'s `previousScore` and `RemediateRequest.actor`) and two in #16 (the copilot's `type` and the explainer's `summary`). Each is quoted.

## The last check of this branch

Built from phase-0 `1a71005`, #25 `3ce66a0`, #11 `5cbf22f`, #20 `b0a0d52`, #15 `5d705e2`, #18
`4a016b8`, #22 `cc2de60`, #14 `0d6d8a5`, #17 `b7aae5f`, #19 `02b5030`, #27 `f299dd9` and #16
`9fbdc55`. Results:

- `npm run check` with OPA 1.4.2: 0 failures, 0 warnings.
- `npm run check:platform -- --from contracts/platform/openapi-c9529d5.json`: landed 1, pending 90, diverged 0.
