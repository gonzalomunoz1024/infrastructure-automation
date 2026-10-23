# Devin playbook: finishing the customer console

For the lead Devin session. Read `HANDOFF.md` first; WP ids (R-1, S-1, B-2 …) refer to its
section 7.

## Ground rules for every sub-agent

Put these in every sub-agent's prompt:

1. Never touch `main` or the platform. Never merge a PR unless the lead says Gonzalo approved it.
2. Work on `<service branch>--<topic>` cut from that service's newest branch; open a PR into it.
3. Never rebase, squash or force-push a shared branch. Merge commits only.
4. Publish console-libs to mavenLocal first (`HANDOFF.md` section 9). Read section 12 (gotchas).
5. A contract change goes to a hub PR first, with a decision number from the lead (next free: 36;
   platform change: P-24). Never edit a copied contract in a service; re-pin `contracts.lock`.
6. Decide ambiguities yourself as a lead engineer, record them in the service README's Decisions
   table, and report them to the lead.
7. Done means: tests green locally, CI green on the PR, README updated, a short report to the
   lead with the PR link, head SHA and anything left.
8. One sub-agent per checkout. Never two Gradle builds or two Playwright runs in one tree.

## Lanes

| Lane | Owner | WPs | Touches |
|---|---|---|---|
| 0 Lead / hub steward | the lead session | R-1, H-1, H-2, L-1, L-2, merge train | `console-root--*`, `console-root--stack`, `console-libs` |
| 1 Security | 1 sub-agent per service, short-lived | S-1 (BFF), S-2 (cost), S-3 (drift #21), S-4 (web), S-5 (all) | each service's newest branch |
| 2 BFF | 2 sub-agents (2a, 2b) | 2a: B-1, B-2, B-4, B-5. 2b: B-3, B-6 (+ `console-sdk`), B-7, B-8, B-9, B-10, B-11 | `console-bff--*`, `console-sdk` |
| 3 Web | 2 to 3 sub-agents | 3a: W-1, W-2, W-3. 3b: W-4, W-5, W-6. 3c: W-7, W-8, W-9, W-10 | `console-web--*` (one branch per stream, merged into a phase 7 integration branch by 3a) |
| 4 End to end | 1 sub-agent | E-1, E-2 | `console-e2e` only; files bugs to other lanes |
| 5 Load and production | 1 sub-agent | P-1 to P-4 | load branches per service, `docs/runbooks/` per service |

## Where each lane starts (19:05 UTC)

- Lane 1: S-1 continues `console-bff--security-wip`, and S-4 continues `console-web--phase-5-security-wip`.
- Lane 2: 2a starts from draft #51 (`console-bff--phase-9`) and `console-bff--assistant`, then picks up `handoff/bff-remaining.md` (it lists the BFF stack and the WIP branches too). 2b starts from draft #52 (`console-bff--org`), then B-6 and B-7: check for `console-bff--clients`, `--notifications` and `console-sdk` first.
- Lane 3: start from draft #54 (`console-web--phase-7`) and its `console-web--p7-*` stream branches. Each stream agent keeps its own branch, and 3a merges the streams into phase-7. W-8 is PR #49.
- Lane 4: continue draft PR #55 (`console-e2e--journey` → `console-e2e`), and don't restart them.
- Lane 5: drift starts from draft #53 (finish the shared walk store fix for the call-budget overrun). State uses #47 and #48, search #44.

## Order

```
Step 1 (30 min)   Lane 0: R-1 inventory. Everyone waits for its table.
Step 2 (≈4 h)     Lane 1: S-1..S-4 in parallel (one agent each), then S-5.
                  Lane 4: E-1 compose stack in parallel (it is the test bed for decision 35).
                  Lane 0: L-1 publish libs; ask Gonzalo whether to start the merge train.
Step 3 (≈10 h)    Lane 2 (2a, 2b), Lane 3 (3a, 3b, 3c), Lane 5 P-1 in parallel.
                  BFF before web per feature: each web stream starts against the hub contract and
                  the mock, and switches to live once the BFF route lands.
                  Lane 4: E-2 journey; files integration bugs to lanes 2 and 3.
                  Lane 0: merge train (HANDOFF section 5) as PRs go green, re-pins (H-2).
Step 4 (≈4 h)     Lane 5: P-2, P-3, P-4. Lane 4: full journey green in CI on merged branches.
```

Dependencies to respect:

- S-1 and S-4 before anything new in BFF or web (the security fixes touch the same files).
- Decision 35 lands in BFF and cost together with a green e2e run (S-5).
- B-1 before W-8's executive-home/insights live mode; B-2 before the copilot live mode; B-4
  before W-6 and W-10 live; B-5 before W-7 blueprints live; B-6 before W-3 live; B-7 before W-1
  live.
- Hub changes (B-9, B-10, P-2 need new decisions) go through lane 0 first.
- E-2's drift step needs the drift service and platform-fake drift injection; its cost step
  needs cost S-2 done.

## Lead's loop

Every hour: refresh the branch/PR table (`gh pr list`, `gh pr checks`), restack
`console-root--stack` if a hub PR head moved (see `handoff/MERGE-ORDER-SNAPSHOT.md` and
`handoff/services/hub.md` for the rebuild trick), assign the next WP to any idle agent, and
append a dated status block to `HANDOFF.md` on `console-root--handoff`.

## Prompt template for a sub-agent

```
You own WP <id> from HANDOFF.md section 7 in github.com/gonzalomunoz1024/infrastructure-automation.
Read HANDOFF.md sections 1, 3, 9, 12, 13 and handoff/services/<service>.md first.
Branch: <new branch> from <base branch> (head <sha>). Do not touch other branches.
Goal: <WP goal>. Acceptance: <WP acceptance>.
Rules: the ground rules in handoff/DEVIN-PLAYBOOK.md.
Report: PR link, head SHA, CI state, decisions you took, anything left.
```
