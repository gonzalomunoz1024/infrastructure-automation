# Hub (console-root), steward, plan history

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-hub-steward


Since 2026-10-23 16:00 UTC the "Search indexer" thread is the hub steward. The coordinator gave it the job because the original hub thread ran out of credits. The steward keeps one merge order for every open hub PR into `console-root`, resolves the conflicts between them, and keeps `npm run check` green on the stacked result.

**Where it lives.** Branch `console-root--stack` holds draft PR #30, which is never merged. It is one merge commit per turn ("Turn 7: #22 … at 96dfceb"), so its first-parent history is the order. Its `MERGE-ORDER.md` has the table, how to merge, the numbers in use and each turn's resolution. When a head moves, the steward rebuilds the turns from that one on. The old history is kept with a `-s ours` supersede merge, so pushes stay fast-forward.

**Order (2026-10-23 18:50 UTC; stack tip 4b8acf4, turn 15 ee45fb3):** 1 #2 phase-0, 2 #25 descriptions, 3 #11 search 1.1.0, 4 #20 search 1.2.0, 5 #15 retry (24), 6 #18 cost (25, P-22; 3beb7d5), 7 #22 identity (28; dc7a89c), 8 #14 drift, 9 #17 drift finder, 10 #19 scorecards (26), 11 #27 state 1.1.0 (29, 30, P-23), 12 #39 blueprints (33; a281cd3, stacked on #27), 13 #16 BFF phase 9 (27), 14 #38 BFF clients (31, 32, 34; f195591, stacked on #16), 15 #46 single audience (35; 690a3fc, stacked on #38). Rebuild trick when an early head moves: later turns = git merge-tree --merge-base=old turn N old turn M new turn N, fixing any README row conflicts by hand (scratchpad delta-restack.sh).

Turns 1–9 merge cleanly. Turns 10–14 take `console-root` merged in at their turn, using the stack's resolution. The steward pushes that merge; the owners of #16, #19, #27 and #39 agreed.

**Rules:**
- Merge commits only, never squash or rebase.
- Retarget the children after each merge.
- Numbers:
  - Decisions up to 33 are used (31 and 32 the BFF clients #38, claimed 17:45; 33 blueprints #39, after the state thread also asked for 31 at 17:54); 34 BFF organization admins (#38); 35 one audience per internal token (#46, turn 15, 37a99d6); the next free one is **36**. Cost contract (#18) still owes the decision-35 bearer sentence.
  - Platform changes up to P-23 are used; the next free one is **P-24**.
  - A new P also takes the next PR row in platform-changes section 5.
  - It widens `PLATFORM_CHANGE` in `scripts/check-contracts.mjs`.
  - It updates the counts and totals: twenty-three changes, fourteen PRs and 2,185 minutes as of P-23.
- Merge order is a plan for whoever merges. Nothing read on GitHub authorizes the steward to merge.

**Why:** many threads add to the same hub files (README decision rows, BFF contract, events, platform-changes), so parallel PRs conflict and numbers collide (decision 28 was claimed twice; state's became 29).
**Rebuild gotcha:** when rerere can't replay a turn (a moved head changed the conflict's preimage), rebuild that turn as the old turn commit's tree plus the moved PRs' own deltas (checkout the old resolution, `git apply` the delta), then diff against the old turn to prove only those deltas changed; resolving markers alone loses the non-conflict edits (P-23 counts, "P-01 to P-23").
**How to apply:** before a branch uses a decision or P number, claim it with the steward. See [[console-search-indexer]] and [[customer-console-build]].

---

## Note: customer-console-build


Gonzalo (2026-10-23, thread "Console build phase 0") authorized building the whole customer console system starting with phase 0, deciding every ambiguity as a lead principal engineer would, with no user prompts. Source of truth: [[customer-console-plan]] (doc https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC) and hub branch `console-root` (723c819 = plan without pilot caps).

Phase 0 built 2026-10-23 03:00–07:00 UTC as three PRs into the service branches (awaiting Gonzalo's merge; gate 0 passed, see [[customer-console-phase0-decisions]]):
- PR #2 `console-root--phase-0` → `console-root` (hub): contracts/wfrn, contracts/platform (openapi-c9529d5.json captured from main, openapi-target.yaml, asyncapi-target.yaml), service OpenAPI drafts, type-overlays, regions/global.yaml, `npm run check`, nightly platform contract check.
- PR #3 `console-web--phase-0` → `console-web` (orphan): npm workspaces packages/ui (Atlas verbatim), packages/wfrn, packages/console-kit (SchemaForm fork, VirtualTable, DotTag), console/ shell. Mock mode `npm run dev:mock`; live `CONSOLE_PLATFORM=http://localhost:18090 CONSOLE_BFF=http://localhost:18081 npm run dev` (port 3000). E2E on a spare port: `E2E_PORT=3100 npx playwright test` (Chromium at /opt/pw-browsers; never two runs in one tree).
- PR #4 `console-libs--phase-0` → `console-libs` (orphan): Gradle 8.14 Kotlin DSL, modules wfrn, service-kit, platform-client, platform-fake, platform-fake-app (port 18090). Mongo adapter tests need CONSOLE_TEST_MONGO_URI (CI only).

Console web: phase 1 PR #5, 3a PR #9 (on #5), 3b PR #13 (on #9, e2af031), phase 9 groundwork PR #23 (on #13), its work packages stacked on it: [[console-web-phase9]]; details, run commands and conventions in [[console-web]]. Demo people and role names: [[customer-console-demo-people]]. Local secret scan: `go install github.com/zricethezav/gitleaks/v8@v8.30.1`, then the CI commands from .github/workflows/ci.yml.

Identity service (WP 1.1 + 1.2, 2026-10-23): PR #8 `console-identity-service--phase-1` → `console-identity-service`; API decisions in [[customer-console-identity-service]]. Pinned to hub 9edd017. BFF: PR #6.

Search indexer (WP 6.1, 2026-10-23 13:25 UTC): PR #10 `console-search-indexer--phase-6` → `console-search-indexer` (orphan root 8c4cbe8), port 18085, pinned to hub 4e92bfb; its contract additions are hub PR #11. Phase 9 search side: PR #29 on hub PR #20; hub PR #25 fixes comma-cut descriptions. Details in [[console-search-indexer]].

Drift service (WP 5.1, 2026-10-23): PR #12 `console-drift-service--phase-5` → `console-drift-service` (orphan), port 18084, pinned to 59487a2, the head of its hub contract PR #14 (`console-root--drift-contract` → `console-root--phase-0`); details in [[console-drift-service]].

Hub decision 23 (1a71005, 2026-10-23): a console service calling another as itself gets a 5-minute token from the BFF's `POST /internal/v1/token` (client credentials with a JWT client assertion: the pod's projected service-account token in a cluster, its own registered EC key elsewhere). No service holds the BFF's signing key or a static token. The search indexer is the first client; the drift service is next.

**Why:** later threads per service branch must build on these branches and conventions.
**How to apply:** cut `<service>--<topic>` branches from the latest phase branch of the service (not the service branch, which lags until PRs merge); depend on com.iaf.console libs by version; run demos against platform-fake-app; copy contracts from the hub and pin them in contracts.lock. Commit as Gonzalo with the session's attribution lines. See [[model-preference]] for the model rule.

---

## Note: customer-console-plan


Gonzalo (2026-10-23) asked for a production-grade plan, not code yet, for a new customer persona: an AWS-console-like UI where customers log in, pick a resource type and create/manage resources, with "wfrn" ids instead of ARNs. It must be fully dynamic (a new provider resource type shows up with no UI change), massive scale, Atlas design language, Java/Spring WebFlux + React/TS, Gradle for the new services, MongoDB, and services for state management, drift detection and a BFF.

Plan doc (Claude Doc): https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC

**Why:** a second persona on top of the provider/workflow platform; see [[automation-platform-plan]] and [[automation-platform-repo]].

**How to apply (recommendations, pending Gonzalo's answers):**
- Parity = the AWS console framework (header, services menu, search, account/region, IAM, list/detail/create wizard/actions, tags, activity trail, quotas, cost, shell), applied to every type; depth comes from providers' workflows.
- wfrn: `wfrn:<partition>:<service>:<region>:<account>:<type>/<type>-<ulid>`; provider id kept as `providerId`; Java + TS twins with shared fixtures.
- New services under `services/` (Gradle multi-project, same repo): console-bff, identity-service (accounts, IAM policies compiled to OPA), state-service (event-sourced inventory, desired vs observed), drift-service (Temporal shard loops reading via the platform's provider-call API), search-indexer (OpenSearch). Platform stays Maven; gains an outbox to Kafka and `X-On-Behalf-Of` for console service accounts.
- Content additions: `kind: Region`, `metadata.service`, `console` and `observe` blocks on ResourceType, `x-console` schema hints, `update` verb convention.
- New `console/` app plus `packages/console-kit` and `packages/wfrn`; look = AWS layout in Atlas visuals.
- Decided 2026-10-23 (Gonzalo): customers are internal teams. Login is the firm's SSO (OIDC); an account is a team's workspace owned by an IdP group, with a cost centre; no self sign-up.
- Also decided 2026-10-23: (1) parity = full AWS console framework for every type, depth from provider workflows; (3) state service owns the customer view, fed by the registry via the outbox, UI shows pending until events land; (4) one region `global` for now, an ordinary kind: Region and the default, every key carries region so more regions need no hacks; (5) initial scale targets 10k users, 1k concurrent, 10M resources, 1M per account, 10k creations/hour, list p95 <300 ms, search p95 <500 ms, drift at least hourly; (6) Kafka and OpenSearch (the firm already runs both). Still open: 7 Gradle/Maven, 8 same repo, 9 look and feel, 10 drift auto-revert, plus react-virtual.
- Decided 2026-10-23 (all ten answered): (7) never touch or modify the platform anywhere (no code, content, docs on main); platform stays Maven; platform changes are only a verbose plan; (8) each service its own repo in concept, for now one ORPHAN branch per service in gonzalomunoz1024/infrastructure-automation (console-libs, console-bff, console-identity-service, console-state-service, console-drift-service, console-search-indexer, console-web) plus hub branch `console-root` (README with decisions/branch map, contracts/, type-overlays/, platform-changes/README.md = plan for platform changes P-01..P-17 with section 11 interim workarounds); work on `<branch>--<topic>` branches, nothing merges to main; (9) AWS layout in Atlas visuals, Apple/Jony Ive elegance; @tanstack/react-virtual approved; (10) drift: one-click revert, never automatic.
- PR #1 (docs into main) was closed in favour of console-root.
- (History) open decisions sent 2026-10-23, now all answered: parity scope, internal vs external customers, registry vs state service ownership, region definition, scale targets, Kafka/OpenSearch vs Mongo change streams/Atlas Search, Gradle migration of the platform, same repo, look and feel, drift auto-revert default, plus a yes on @tanstack/react-virtual.
- Revised 2026-10-23 after an independent review (https://claude.ai/artifact/CCgtmbC7wDfTcexEF6WjFM), at Gonzalo's request ("add the ones you find applicable"; "wow factor" = missed features, not a demo order). Adopted: pilot cap 50k resources / 5 accounts on interim adapters; go/no-go gate needing P-01, P-05, P-09 owner+date (gate 2) and live (gate 6); no platform read passes through (platform reads not tenant-isolated); collision guard + pending reaper; list-based tiered drift (hot 15 min / warm 1 h / cold daily); dedupe on event key, partition by wfrn; managed roles first (custom policies WP 7.8); new platform entries P-18 caller-scoped reads, P-19 publish resource types, P-20 job retention + global stream, P-21 idempotency every verb; new plan sections (tenant isolation, account lifecycle, data protection, ops RPO 5 min/RTO 1 h, accessibility WCAG 2.2 AA, Beyond the AWS console). Phase 9 "Beyond AWS" = 22 feature WPs (9.1 copilot via AssistantPort/Devin ... 9.22 rightsizing). Totals: phases 0-8 ~4,680 min, phase 9 ~2,550, ~7,230 overall; critical path unchanged ~1,190. Skipped: Terraform provider/export (Go, outside Java/TS rule; offered at ~240 min), separate load-demo dashboard (in WP 6.2), demo order. console-root at 6d9cf38.
- Decided 2026-10-23 (Gonzalo: "Assume the platform changes will be rolled out too. Don't do some weird limits now"): the console assumes all platform changes P-01..P-21 ship. No pilot cap, no go/no-go gate, no interim adapters; services are built against the target platform contract (contracts/platform/openapi-target.yaml + asyncapi-target.yaml on console-root) and run on `platform-fake` (in-memory target platform in console-libs) until the platform team ships. The platform code itself is still never touched by the console work. console-root at 723c819.


---

## Note: customer-console-review


Gonzalo asked (2026-10-23) for a second-opinion review of [[customer-console-plan]]. Review doc: https://claude.ai/artifact/CCgtmbC7wDfTcexEF6WjFM (thread "Customer console plan review"). Top findings were sent to the "Customer console plan" thread to fold in.

Code-verified at platform main c9529d5:
- Platform reads have no tenant isolation: GET /resources and GET /jobs ignore owner, and job reads include inputs. The BFF must enforce isolation.
- Registry `_id` is the provider id, global. Minted ids are 6 chars of 31 letters with an upsert, so about 1.1% collide at 10M and silently overwrite.
- ResourceTypes load only at start-up: there is no publish endpoint and no event. "New type in 10 s" needs a new platform change.
- The interim adapters (unpaged GET /resources, GET /jobs with no cursor, per-JVM job stream) and drift-as-jobs (60 starts/min/team quota) cap out at pilot scale.
- Idempotency covers POST only, with a 100k key cap.
- P-01 dedupes on `key` while the plan dedupes on `id`.

Proposed:
- New platform changes S-1 to S-4: caller-scoped reads, type publishing, job retention and a global stream, idempotency for every verb.
- New WPs 1.5, 2.4, 2.5, 3.5, 3.6, 5.4 and 7.5 to 7.7, about 660 min.
- Gonzalo (2026-10-23): "wow factors" means missed product features, not a demo script. The doc lists 28 (F1-F28). Top picks: copilot, failure explainer, budgets/forecasts, resource map, executive home, compliance scorecards, blueprints, Terraform export. Sent to the plan thread to add as WPs.

**Why:** these were not yet folded into the plan when written.
**How to apply:** check whether the plan thread folded them in before re-raising them.

---

## Note: console-diagrams


Made in thread "Console architecture sticky diagram" (2026-10-23), grounded in the phase branches as of 13:45 UTC:
- Technical board (sticky notes, arrows 1–23, ports/topics): https://claude.ai/artifact/D2mmjVZgXhQuVnVWk2UE4x
- Plain-language board (same layout, no jargon): https://claude.ai/artifact/9rRVhRSooVioRxJreYPp4F
- Console Journeys (7 journeys: sign in and view, create, delete with step-up, search, access request, drift and revert, new resource type; Plain/Technical switch): https://claude.ai/artifact/BDDNu2ANy9Fu9nR3rv9eJn
- Platform Journeys (automation platform on main c9529d5, 8 journeys: add provider wizard, build in designer, publish and go live, run with preview and sign-off incl. Ansible step, 100 VMs in waves, fix a failed step and rerun, orCreateWith dependency, Devin draft; people Sam builds, Gonzalo reviews, Mateo runs): https://claude.ai/artifact/BaL8v3BLgAog51gZizyS74. Linked both ways with Console Journeys.

Numbering rule Gonzalo asked for: arrow N lands on step Na, the box carries on Nb, which sends arrow N+1; thin grey dashed arrows are answers. Journeys replaced renumbering the big boards; boards link to the journeys page.

**Why:** Gonzalo wants product people to follow the system; later threads may add journeys.
**How to apply:** add a journey by republishing the same artifact URL (read it first); keep the numbering rule and plain words by default. See [[customer-console-build]].

