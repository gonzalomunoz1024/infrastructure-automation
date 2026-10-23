# console-web

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-web


Branches (stacked; cut new work from the newest): `console-web--phase-0` (PR #3) → `console-web--phase-1` (PR #5: BFF sign-in, roles, refusals, IAM pages; aligned 2026-10-23 with the identity service at c66dc25) → `console-web--phase-3a` (PR #9, base phase-1; WP 3.2 list, 3.6 palette, 3.8 WCAG 2.2 AA; phase 1 merged in) → `console-web--phase-3b` (PR #13, base phase-3a; thread B: WP 3.3/3.5/3.7) → `console-web--phase-4` (PR #37, base phase-3b) → `console-web--phase-5` (base phase-4); thread B's phase 9 PRs (#23, #31, #36) sit beside 4 and merge after it. Merge order #3, #5, #9, #13, #37, then phase 5; retarget each PR to `console-web` after the one below merges. Phases 4 and 5: [[console-web-phase45]]. A push to phase-3a means thread B rebases #13: tell it.

Run: `npm run dev:mock` (no backend) or `CONSOLE_PLATFORM=http://localhost:18090 CONSOLE_BFF=http://localhost:18081 npm run dev`. Typecheck `npx tsc -b` inside `console/` (the root has no tsconfig). Journeys: `cd console && E2E_PORT=3100 npx playwright test` (builds the mock app; Chromium at /opt/pw-browsers; never two runs in one checkout). Helpers `signInAs`, `tabTo`, `expectNoSeriousA11y`, `expectNoHorizontalOverflow` in console/e2e/support.ts.

Mock identity (console/src/mock/directory.ts) seeds and decides as the identity service: managed policies with `notActions`, roles by name, first deny wins, default deny suggests the weakest managed role; approver groups hold the custom `approver` role (access-request get/list/decide). Non-readers listing access requests get their own (200) and a 403 for someone else's, so the console learns queue readability with one probe, `requester=~someone-else` (`readsQueue` in app/access.ts); the BFF has no "may I" endpoint. Approvers never include the requester; their own `:decide` is a 403. Quotas are `resources.<type>` per platform resource type (live resources, tombstones left out), titled by the type. Mock storage key `console-mock-directory-v2`. Mock preferences check every BFF rule (operators, sort, pageSize 1–200, type keys).

3a decisions (README decisions 19-24):
- lists filter and sort on the server only: q, state, drift, tag.<key>; sorts name/created/updated; counts from `GET /console/v1/r/{type}:counts` ("1,000+" at cappedAt)
- list state in the URL query (f, sort, size, view); saved views and columns in BFF preferences (`v_` ids, 409 → reread and reapply), local copy keyed by owner
- `MenuButton` (app/MenuButton.tsx) for every dropdown; `Hint` (app/Hint.tsx) instead of Atlas Tooltip on LaterButton/ActionButton; a refused button's hint is the denial headline, never the long reason (phone overflow)
- dialogs use `data-autofocus`, never React `autoFocus` (Atlas overlay restores focus to the opener)
- a list's highlight follows the pointer only on mousemove, so a list opening under a resting pointer keeps the keyboard's highlight
- index.css: light token overrides on `:root:not(.dark)`; `.dark .bg-accent.text-white` → #0066cc, `.dark .bg-critical.text-white` → #d70015
- never edit packages/ui (Atlas verbatim); override in console CSS or fork into console-kit

Thread B conventions: [[console-web-phase3b]]. Demo people: [[customer-console-demo-people]].

**Why:** a later thread on console-web needs the branch stack, the conventions and the a11y rules without re-asking.
**How to apply:** build on the newest branch, keep these rules, run the full journeys before pushing. See [[customer-console-build]].

---

## Note: console-web-phase3b


Built 2026-10-23 (thread "Resource detail and create wizard") on branch `console-web--phase-3b`: one commit rebased onto `console-web--phase-3a` at 1eaae81 (PR #9, which carries phase 1's identity alignment). PR #13 (base `console-web--phase-3a`) is stacked the way #9 is on phase 1; retarget to `console-web` after the stack merges. Overall status: [[customer-console-build]]. Contracts and mock rules: [[console-web-phase3b-contracts]].

Where things are:
- Create wizard `#/console/:service/:type/create` (pages/CreatePage.tsx, pages/create/steps.ts): steps from the create workflow's input schema, one per `x-console.group`; review shows the BFF preview (cost, policy) and view as code; one Idempotency-Key per body (lib/submission.ts).
- Resource page (pages/ResourceDetailPage.tsx, pages/resource/*): polls 2.5 s while a job is open, `?action=<verb>` opens the drawer, `?tab=`; 410 = tombstone.
- View as code (lib/code.ts, kit CodeView), plain language (lib/plain.ts), tours (lib/tour.ts).
- Actions menu is 3a's `MenuButton` + `ActionMenuItem`; `ConfirmDialog` is console-kit's fork (Atlas stays verbatim).
- Phase labels: 4 = editing, tags, clone, bulk, retry; 5 = drift revert/accept/ignore; 7 = resource policies.

**Why:** later console phases (4 edits/tags/clone/bulk, 5 drift actions) extend these pages and the mock.
**How to apply:** add verbs through ActionMenuItem/ActionDrawer and LaterButton→live; Select names "label: value"; README decisions 25–32 record phase 3b; demo sign-ins are Dana Reyes / Gonzalo Munoz; run `E2E_PORT=3100 npx playwright test e2e/resources.spec.ts`.

---

## Note: console-web-phase3b-contracts


Shapes the console's resource pages rely on (branch `console-web--phase-3b`, [[console-web-phase3b]]):
- Drift: `drift` boolean on a row; the detail adds `finding` (open/closed) and `observedAt`; no `observedAt` reads "Not checked yet". `lastJob` is the BFF JobSummary (`jobId`, no verb); the banner names it by workflow.
- Step-up (BFF PR #6): 403 `error: "step-up.required"`, `detail: {stepUp, action, login}`; the console adds `returnTo` (its own hash route).
- Phase 4 on `console-bff--phase-4`: 409 `dependents.exist` with `detail.dependents` plus a preview deny rule; `POST /console/v1/jobs/{jobId}:retry` → 202, `can.retry`, 409 `job.not-failed`.
- Quotas are per resource type (`resources.<type>`, default 100; the platform team raises them). The preview's `quota.resources` rule and the Quotas card count with one function, `usedByTypeOf` in mock/commands.ts; deleted resources don't count (PR #13 at e2af031).
- Admin role is `ADMIN_ROLE` = 'account-admin'. An operator's delete is a default deny (no statement) that suggests account-admin.
- Mock (console/src/mock/commands.ts): approval 6 s, run 8 s, a name with `fail` fails, journal `console-mock-journal`. List and resource page share one row via `liveRowOf`; drift from `driftOf` (vm-demo02 memoryGb x2, generated ids ending in 7 cpu+2).

**Why:** the BFF, state and drift threads build against these; a change on either side breaks the pages.
**How to apply:** change a shape on both sides together, and keep list and detail rows from the one row function.

---

## Note: console-web-phase45


Phase 4 (`console-web--phase-4`, PR #37, base phase-3b): request page on the BFF job (pages/request/*), bulk drawer (pages/list/BulkDrawer.tsx, bulk.ts), Edit configuration and blast radius, tags, Tag Editor, resource groups, required tags, create similar. README decisions 33 to 41. Phase 5 (`console-web--phase-5`, base phase-4): drift decisions, Drift page, Unmanaged resources, Monitoring tab, Alarms, Rightsizing; decisions 42 to 49. Decisions 50+ belong to thread B (phase 9).

Seams other threads use (keep them stable):
- `FailureCard({ job, className })` in pages/request/FailureCard.tsx; thread B's PR #36 adds `onRetry` and `busy` props and renders its explainer inside it.
- `bff.jobs.retry(jobId, body?: RetryFix, key?)`: a paused job answers its own jobId and goes on; an ended one answers a new jobId (the page navigates there). Key per pause: `keyFor({ retry, pause: attention.since ?? endedAt, fix })`.
- mock/jobs.ts exports `stepsOf` and `retryTargetOf` (thread B's mock/explain.ts imports both). A rerun's link back is mock-only: `data.console.jobs[newJobId].retryOf`.

Mock rules (console/src/mock/jobs.ts follows the BFF's Jobs.can and JobRetries): cancelled jobs are not retryable; cancel works on paused jobs under `jobs:cancel`; a create named with `fail` fails its first run; any other verb whose input `reason` contains `fail` fails until a retry's fix changes it. A failed step pauses the job (needs-attention), never fails it (hub decision 24). Prod rows of a bulk run wait for approval; the platform's approver approves after 6 s if nobody does. Updates run only as PATCH; `POST …:update` is a 404. Every create must send the account's required tags (`tags: { owner, 'cost-centre', 'data-class', environment }`), or it gets a 400.

Gotchas:
- Drift reverts: a paused revert job has not ended, so revertStatus stays `running` (the drift service's RevertFollower reads it the same way); only an aborted, cancelled or failed job gives `failed`.
- Atlas `Tag` tones put the word in the colour (healthy is 2:1 on its tint): use console-kit `StatusBadge` with a status word for anything axe sees.
- The request page has `[data-job-status]` (a live region) for journeys to wait on.
- Ports: web4 3400, web5 3500, worker worktrees 3410 to 3440.

**Why:** the next thread on console-web, or the one that merges these PRs, needs the seams and rules without re-deriving them.
**How to apply:** build phase 6+ on `console-web--phase-5`; keep the seams; send required tags in any new create test. See [[console-web]], [[console-retry-flow]].

---

## Note: console-web-phase9


Thread "Resource pages, cost and copilot" owns phase 9 web; the "Console web phase 1" thread owns phases 4 and 5 web (bulk, retry/skip/abort, Tag Editor, groups, Clone, drift view). README decisions: phases 4 and 5 use 33 to 49; phase 9 uses 50 to 69 (home 50–54, cost 55–59, copilot 60–64, explainer 65–69).

- Groundwork: PR #23, `console-web--phase-9` (18a4ed5) on #13. Work packages are stacked PRs on it: `console-web--phase-9-home` (PR #40, executive home, decisions 50–54, console-kit `ChartFigure`), `-cost`, `-copilot` (worktrees /home/claude/wt-*, E2E ports 3101–3103). The explainer (9.2) waits for phase 4's `jobs.retry` (BFF `console-bff--phase-4`).
- Hub decision numbers: 24 retry (#15), 25 cost service (#18, `console-cost-service`, port 18086), 26 scorecards (#19), 27 the BFF's own phase 9 paths: insights, copilot, job explanation (#16, `console-root--phase-9-web`). Search indexer 1.2.0 interpret and grouped counts: hub PR #20 (a tag value "" means "has the tag").
- Slots, so parallel work never shares a file: console home `HomeCostWidget` and `HomeInsightsWidget`; resource page `costFacts()` and `ExplainButton`; copilot state `app/copilot/context.tsx` (`CopilotRoot` in App.tsx, `useCopilot()`, seeds numbered so each starts one turn).
- `?prefill=` (lib/prefill.ts, base64url JSON) fills the create wizard and an action drawer (`?action=<verb>&prefill=`); only the schema's own, non-sensitive fields. Used by copilot proposals; phase 4's Clone uses its own `?from=` / `?draft=` instead.
- Mock cost ledger `console/src/mock/cost.ts`: computed from resources at `priceOf` list prices, charged while active/updating/deleting, accrued per second at monthly / days in month; every money number (Cost Explorer, budgets, exec home spend) comes from it. Cost centres CC-4100 Compute, CC-4200 Data. `cost`, `budgets`, `insights` are console services (no platform scope check); budget writes admin-only, export admin or auditor.
- Failure explainer (9.2): PR #31 (opened 2026-10-23, on #23), branch `console-web--phase-9-explainer`, card `pages/explain/FailureExplainer.tsx`, mock `mock/explain.ts`. Retry with fix is live in PR #36 (branch `console-web--phase-9-retry`, base #31, opened 2026-10-23): it merges phase 4 web (a622722) and must merge after phase 4's PR and #31. Phase 4's `pages/request/FailureCard.tsx` hosts the card for a failed or paused step (props `onRetry`, `busy`; RequestPage passes its own `retry`); rejections, batches and rollbacks keep FailureCard's words. The card shows its own retry button only when the fix changes the input or the step, otherwise it points to the page's Retry. The mock reads phase 4's `stepsOf` and `retryTargetOf` (keep them exported); a paused step carries class `provider`; reruns record `retryOf`. Warning confirm buttons (Atlas `warning-outline`) get `#c93400` in light mode via console/src/index.css (README decision 5).
- Formatting gotcha: the repo has no prettier config; running `npx prettier` with defaults adds semicolons. Use `--no-semi --single-quote --print-width 200 --trailing-comma all`, and only on new files.

**Why:** later threads extend these pages and must keep the slots, the shared ledger and the decision numbering.
**How to apply:** add a phase 9 surface in its own files behind a slot; take money numbers from the ledger; pick the next free hub decision number by checking open hub PRs first.

---

## Note: customer-console-demo-people


Managed roles (hub decision 21, 2026-10-23): `viewer`, `operator`, `account-admin` (shown as Admin), `auditor`. Managed groups: `viewers`, `members` (operator), `owners` (account-admin), `auditors`. Approver groups are custom groups with a custom `approver` role (e.g. `compute-approvers`, `data-approvers`). Downstream services decide admin by `roles` containing `account-admin`; user tokens carry `roles` beside `groups`.

Demo accounts: 123456789012 "Compute" and 210987654321 "Data", one region `global`. Demo people (console user ids `user_<subject>`; `login_hint=<user>` skips the picker):
- gonzalo: account-admin (`owners`) in both accounts.
- dana: operator (`members`) + `compute-approvers` (Compute).
- mateo: operator (`members`) (Compute).
- mia: viewer (`viewers`) + `compute-approvers` (Compute).
- raj: operator (`members`) + `data-approvers` (Data).
- ana: operator and viewer (`members`, `viewers`) (Data).
- noah: no account → 403.
Full names (same in the BFF demo, identity DemoSeed and web mock since 2026-10-23): Gonzalo Munoz, Dana Reyes, Mateo Silva, Mia Chen, Raj Patel, Ana Costa, Noah Berg.
Demo bundle token for the identity service: `demo-bundle-token` (demo-only value, not a secret).

**Why:** the BFF, identity service and web mock must agree on these names or the demos and tests diverge.
**How to apply:** change them in all three places at once; see [[customer-console-bff]], [[console-state-service]].

