# Hub merge order

The order the open hub pull requests merge into `console-root`, kept by the hub steward (the
search thread, since 2026-10-23 16:00 UTC) and updated whenever a hub PR opens, moves or merges.
This branch, `console-root--stack`, is every PR below merged into `console-root` in this order,
one merge commit per turn ("Turn 7: #22 … at 72485db"), with the conflicts resolved; CI runs the
hub checks on it. It shows the end result is green and holds each turn's resolution. It is never
merged itself. When a head moves, the steward rebuilds the turns from that one on.

## The order

| Turn | PR | Branch → base | What it carries | Before its turn |
|---|---|---|---|---|
| 1 | #2 Phase 0 hub | `console-root--phase-0` → `console-root` | Decisions 1 to 23, the contracts, wfrn, overlays and the checks | Ready |
| 2 | #25 Comma-cut descriptions | `console-root--contract-descriptions` → phase-0 | 34 descriptions quoted; `npm run check` fails on an entry with no value | Ready |
| 3 | #11 Search contract 1.1.0 | `console-root--search-indexer-contract` → phase-0 | Counts, seven sorts, the drift filter, the status scope | Ready |
| 4 | #20 Search contract 1.2.0 | `console-root--search-copilot-contract` → #11 | Plain English, grouped counts, typos, times | Ready |
| 5 | #15 Retrying a failed create | `console-root--state-retry` → phase-0 | Decision 24 | Ready |
| 6 | #18 Cost contract | `console-root--cost-contract` → #15 | Decision 25, P-22 price lists, the BFF's cost refusals (the create's 409 `budget.exceeded`, the price lists' 403, skipped accounts on the CSV) | Ready |
| 7 | #22 Identity contract | `console-root--identity-contract` → #18 | Decision 28: boundaries, guardrails, the simulator, quota rules, access reviews, account closure, the group sync | Ready |
| 8 | #14 Drift contract | `console-root--drift-contract` → phase-0 | Person-bound reverts, `revertStatus`, the 409 codes, internal operations | Ready |
| 9 | #17 Drift finder and monitoring | `console-root--drift-finder-monitoring` → #14 | Shadow infrastructure, monitoring, rightsizing, drift numbers | Ready |
| 10 | #19 Scorecards contract | `console-root--scorecards-contract` → #17 | Decision 26, the rule packs | Merge `console-root` in once turn 9 is merged: turn 10 below |
| 11 | #27 State contract 1.1.0 | `console-root--state-phase-9` → #15 | Decisions 29 (lineage, blast radius, the map, the sealed trail, time travel) and 30 (moving a resource to another account), P-23 (moving a registry record to another account) | Merge `console-root` in once turn 10 is merged: turn 11 below |
| 12 | #39 Blueprints and stacks | `console-root--state-blueprints` → #27 | Decision 33: `kind: Blueprint`, two samples, their checks, the stack routes, `state.stacks` (events 1.1.0) | Merge `console-root` in once turn 11 is merged: turn 12 below |
| 13 | #16 The BFF's phase 9 paths | `console-root--phase-9-web` → phase-0 | Decision 27: the executive home, the copilot, the failure explainer | Merge `console-root` in once turn 12 is merged: turn 13 below (the web thread asked the steward to push it) |
| 14 | #38 API clients and notifications | `console-root--bff-clients` → #16 | Decisions 31 (the CLI and SDKs' device sign-in, the shell, ChatOps), 32 (notifications, recent and favourites, digests) and 34 (organization admins), and the BFF's paths for decisions 29 and 30 | Merge `console-root` in once turn 13 is merged: turn 14 below |

Turns 1 to 9 merge with no conflict at all, checked by merging the heads in this order with no
recorded resolutions. Turns 10 to 14 conflict with what merged before them, because they grew
beside it; their resolutions are below and in this branch's turn commits.

## How to merge

- **One at a time, top to bottom.** Use "Create a merge commit". Never squash or rebase: the later PRs carry the earlier ones' commits, and a squashed parent makes every child conflict.
- **Retargeting.** When a PR merges, the PRs based on its branch are moved to `console-root`. GitHub does this when the merged branch is deleted; otherwise the steward does it. Their diffs don't change.
- **Conflicts.** A PR whose "Before its turn" is not "Ready" has `console-root` merged into it once the turns before it are merged (a merge commit, never a rebase), with this branch's resolution for that turn. The steward or the PR's owner pushes it, then CI runs on the PR before it merges.
- **Stopping part way is fine.** Stopping after any turn leaves `console-root` green: every turn of this branch passes the hub checks.

## Numbers in use

- **Decisions:** 24 retrying a failed create (#15), 25 cost (#18), 26 scorecards (#19), 27 the
  BFF's phase 9 paths (#16), 28 custom policies and quota rules (#22, and its later identity
  commits: access reviews, just-in-time elevation, account closure), 29 the state service's
  relationships and time travel (#27), 30 moving a resource to another account (#27, agreed
  with the identity thread), 31 the API clients (the CLI and SDKs' device-code sign-in, the
  console shell, ChatOps identity and its Slack and Teams endpoints) and 32 notifications (the
  in-console feed, Slack, Teams and email per person, recent and favourites, the weekly digest),
  both the BFF thread's (#38), 33 blueprints and stacks (#39), 34 the BFF's organization admins
  (#38). **The next free decision is 35.**
- **Platform changes:** P-22 price lists over the API and a type's price binding (#18), P-23 moving a
  registry record to another account (#27). **The next free platform change is P-24.** A new one
  also takes the next pull request row in `platform-changes/README.md` section 5 (P-22 is 13,
  P-23 is 14) and widens `PLATFORM_CHANGE` in `scripts/check-contracts.mjs`.
- Claim a number with the steward, or here, before a branch uses it.

## The resolutions

- **Turn 10, #19 after turns 1 to 9.** It meets #25's check, #18's cost contract and #22's identity
  contract in the hub README, the contracts README, the events contract, the BFF's tags and
  `scripts/check-contracts.mjs`. The resolution keeps both sides: decision rows 24, 25, 26 and 28
  in order, `drift.scorecards` and `cost.events` both listed, the BFF's tags Cost, Budgets and
  Scorecards, the check script's header combined and `checkEntriesHaveValues()` run after the rule
  packs. (#19's own overlap with #17 in the drift contract is already resolved on #19's branch.)
- **Turn 11, #27.** The hub README takes row 29 and row 30 after row 28 and counts twenty-three
  changes, P-01 to P-23. `platform-changes/README.md` keeps P-22 then P-23 in the extensions, the
  contents, the table, the sections and the pull request rows (13, then 14); the totals become
  about 2,185 agent minutes in sections 2 and 5 (each side alone says 2,125), twenty-three
  changes, fourteen pull requests, and section 11's list names both. `PLATFORM_CHANGE` becomes
  `2[0-3]`. The contracts README's line for `openapi-target.yaml` says P-01 to P-23.
- **Turn 12, #39.** The events contract's description names `state.stacks` beside the drift,
  identity and cost topics (version 1.1.0, #39's). `scripts/check-contracts.mjs` keeps both new
  sections, 7 the rule packs and 8 the blueprints: its header names both, section 3 builds both
  validators and returns `vPack`, `sampleTypes`, `vBlueprint` and `mergedTypes`, and the run ends
  `checkFixtures`, `checkRulePacks`, `checkBlueprints`. The hub README's row 33 follows row 30.
- **Turn 13, #16.** Its row 27 goes between rows 26 and 28. Its BFF paths, schemas and tags
  interleave with those of #17, #18, #19, #22 and #27: the resolution keeps both sides of every
  one (tags Cost, Budgets, Scorecards, then Insights and Assistant).
- **Turn 14, #38.** Its paths follow the stack's last path, `UserCodePath` follows
  `RecommendationIdPath`, its schemas go at the end, rows 31 and 32 between 30 and 33, and
  `LiveEvent.type` reads "…job.updated, budget.alert (…BudgetThresholdCrossed), or notification
  (…)". Its later commit puts `OrganizationAccountId` before `WfrnPath`, the organization paths
  after its last path, `organizationAdmin` after the session's `expiresAt`, and row 34 after row 33.
  The result is exactly #38's own change on top of turn 13.
- **Already resolved on the PRs' own branches:** #22 merged the rebuilt cost contract (4a016b8;
  #18's later e42d270 touches only the BFF's cost paths and merges cleanly after it),
  #19 merged #17 (4d9c555), #27 renumbered its decision from 28 to 29, and #19 and #16 quoted
  their comma-cut descriptions.

## The last check of this branch

Built from phase-0 `1a71005`, #25 `3ce66a0`, #11 `5cbf22f`, #20 `b0a0d52`, #15 `5d705e2`, #18
`e42d270`, #22 `72485db`, #14 `0d6d8a5`, #17 `b7aae5f`, #19 `4d9c555`, #27 `e790a27`, #16
`1bda04e`, #39 `a281cd3`, #38 `f195591`. Results, at every turn:

- `npm run check` with OPA 1.4.2: 0 failures, 0 warnings.
- After turn 14, `npm run check:platform -- --from contracts/platform/openapi-c9529d5.json`:
  landed 1, pending 91, diverged 0.
