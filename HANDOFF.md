# Customer console: handoff plan

Written 2026-10-23 at about 19:00 UTC for an agent (Devin, with sub-agents) that takes over the
customer console build. You have **no access** to the sessions, threads, chat or memory that
built it. Everything you need is in this repository, mostly in this file and in `handoff/`.

Read in this order:

1. This file, top to bottom (about 40 minutes).
2. `handoff/DEVIN-PLAYBOOK.md`: how to split the work across sub-agents and in what order.
3. `handoff/HUB-README-STACK-SNAPSHOT.md`: the hub README with all 35 decisions (the copy on
   `console-root` itself only has decisions 1 to 10, because no hub PR has merged yet).
4. `handoff/services/<service>.md` for the service a sub-agent owns.
5. `handoff/PLAN-SNAPSHOT.md`: the full product plan (a Markdown copy of the plan document,
   which lives at a claude.ai URL you cannot open).
6. `handoff/security-review/`: the security review of 2026-10-23, per service.

**How much of this was verified.** Branch heads, open PRs and their bases were read from GitHub at
about 18:55 UTC. CI results, test counts and "what is left" come from the build sessions' own
status reports in the same hour; they were not re-run for this handoff. Anything marked
**(unverified)** must be checked before you rely on it. Work that the build sessions had in
progress but had **not pushed** is lost to you unless it later appears on GitHub; section 6 says
which items are at risk.

## Final status at handoff (about 19:55 UTC)

All build sessions stopped at about 19:50 UTC, when the usage limit was reached. They were told to push everything, WIP included. `handoff/BRANCH-HEADS.md` lists every branch head at that moment. This block supersedes the 19:05 update below wherever they differ. Its contents are from the owners' final reports; CI was not re-run.

**Fixed since 19:05**
- **Full-system run findings.** The e2e run (draft PR #55) numbers its integration bugs F-1 to F-15.
  - F-1, F-2, F-7 and F-11: fixed in console-libs at **cd6f535** on PR #4's branch, CI green.
    - The fake runs on a JRE.
    - The fake app publishes the feed to Kafka under its `kafka` profile, so the e2e bridge can go.
    - The fake serves P-22 price lists, and types carry price bindings.
    - Fake-created VMs report their own attributes, so new VMs no longer show drift.
    - One gap remains: publishing a price list emits no event, because the event contract has no price-list content kind.
  - F-9: the blank console after sign-in (`/types` read as a bare list instead of `{items}`). Fixed on web #41 and phase 7.
  - F-12 and F-14: a revert is followed until it closes, and a deleted resource shows its tombstone. Done in PR #56 (`console-web--resource-fixes`), merged into `console-web--phase-5`.
  - F-15: web cost #49 was blank against the live BFF. Fixed at b327e4d.
  - F-10: the cost service answers internal reads of one resource (#28).
  - F-13: the stale resource ETag (it hid a changed `lastJob`). Fixed on BFF phase 4 and carried into `console-bff--cost` (#34 at c4dc41a). **Not yet carried into #51 (phase 9) or #52 (org admins):** merge phase-4 into them.
- **State service.** All 9 security findings are fixed on #33 (0d6164a), including the transfer fix.
  - The state service's `:transfer` now accepts only the BFF calling with its service token, naming the person who asked (see the commit "the state service's transfer is called with the BFF's service token" on this branch).
  - B-4's transfer route must call it that way.
  - #33, #45 (a85521f) and #48 are green and mergeable.
  - #47: the PR run is green, but one push build died during setup, before any of the PR's code ran. Click "Re-run failed jobs" once.
- **BFF.** Security fixes are on PR #6, merged into phase 4 (#26): 197 tests green.
- **Cost.** #28 is green at 3664b57. #34 is green at c4dc41a: cost, scorecards and drift routes, plus the phase-4 security fixes.
- **Drift scan.** Each type's list is now walked once across all accounts, and discover walks are capped to the cold tier. At 1M resources: 4.9 platform calls/s, nothing past its interval (was 74%). Draft #53; the 10M runs at 5/s and 6.7/s were still running.
- **Drift scorecards.** Security fixes are on PR #21: remediation actor and assertion, write checks, OPA package check, bearer token, push off by default. A follow-up is WIP on `console-drift-service--scorecards-wip`.

**Unfinished at handoff (start here)**
| Item | Branch / PR | State |
|---|---|---|
| BFF CLI sign-in (decision 31) | `console-bff--clients` 5c66f1f | WIP |
| BFF notifications (decision 32) | `console-bff--notifications` a55ca11 | WIP |
| SDKs and CLI | `console-sdk` 338a4e0 | TypeScript and Java SDKs and CLI; 192 tests green locally; CLI sign-in waits on `--clients` |
| BFF time travel, revert plan, map, activity, transfer (B-4) | none pushed | A worker was mid-way at the stop; assume lost. Spec in `handoff/bff-remaining.md` |
| BFF blueprints routes and executor (B-5) | none pushed | As above |
| BFF ChatOps (B-8) | `console-bff--chatops` 3c36e69 | WIP, unreviewed, no PR |
| BFF load tests | none | No code written |
| BFF unverified security items (shared jti store, cluster-token cap, rate limit) | `console-bff--security-next` 27a2ffe | Verify, then merge into phase-1 and upward |
| Carry BFF phase-4 fixes into #51, #52 and #42 | - | #42 merge was in progress (one conflict resolved, building) |
| Web phase 7 | draft #54 `console-web--phase-7` 154347f | Policy editor, simulator, boundaries, guardrails, activity trail, time travel, lineage and resource map merged (514 unit tests). Still to merge: recent, favourites, Service Quotas and quota increases; blueprints, change risk, scorecards and event subscriptions; resource transfer and mobile approvals; team dashboards and service health (stream branches `console-web--p7-*`) |
| Web security fixes for #41 | `console-web--phase-5-security` / `-security-wip` ea33f69 | Journey suite was running |
| e2e full journey | draft #55 `console-e2e--journey` cbf72e5 | Pinned to every owner's fix, all steps enabled; first full CI run not confirmed green |
| Cost: one audience per service token | #28 | Not checked by its owner |
| Drift 10M scan runs | draft #53 cfddfee | Running at the stop |

---

## Update 19:05 UTC: most at-risk work is now on GitHub

The build sessions pushed their work in progress just after this file was first written. Read
this block before sections 4 to 7. Wherever those sections say a branch is "not on GitHub" or "at
risk", start from the branch listed here instead. **(Heads checked with `git ls-remote` at 19:05
UTC; nothing below is CI-verified.)**

| Area | Now on GitHub | Head | Start here for |
|---|---|---|---|
| BFF phase 9 | draft PR #51 `console-bff--phase-9` → `console-bff--cost` | 9f4768e | B-1 (finish, take out of draft, CI green) |
| BFF organization admins | draft PR #52 `console-bff--org` → `console-bff--phase-9` | 2dfa817 | B-3 |
| BFF copilot | branch `console-bff--assistant` (no PR) | 3dc6f40 | B-2 |
| BFF security fixes | branch `console-bff--security-wip` (no PR) | a36c65b | S-1 |
| BFF API clients, notifications, SDK | announced as `console-bff--clients`, `console-bff--notifications`, `console-sdk`, but **not on GitHub at 19:05** | - | B-6, B-7 (check again; rebuild if absent) |
| BFF unstarted work | specs in `handoff/bff-remaining.md` (written by the BFF session, 3296a6a) (ChatOps, time travel and transfer, blueprints executor, BFF load tests) | - | B-4, B-5, B-8, P-1 (BFF part) |
| Drift 10M scan | draft PR #53 `console-drift-service--scan-load` → `--finder-and-monitoring` | 7e8c95d | P-1 (drift). The scan overran the platform-call budget; a shared walk store fix was in progress |
| Web phase 7 and the rest of 9 | draft PR #54 `console-web--phase-7` → `console-web--phase-5` | 7207de4 | W-1 to W-7, W-9, W-10 |
| Web phase 7 streams | `console-web--p7-access` (cdbc2ab), `--p7-cli` (b3e39bd), and WIP branches `--p7-blueprints-wip` (340db1a), `--p7-dashboards-wip` (facb1c8), `--p7-mobile-wip` (70046d9), `--p7-notify-wip` (e0d95ce), `--p7-personal-wip` (2b3ac71), `--p7-policies-wip` (dc70827), `--p7-state-wip` (0e3b80f) | see left | Each W stream continues on its own branch and is merged into `console-web--phase-7` |
| Web security | branch `console-web--phase-5-security-wip` (no PR) | 38eede3 | S-4 |
| Web cost | PR #49 `console-web--phase-9-cost` → `--phase-9` | 1bb59ec | W-8 |
| End to end | draft PR #55 `console-e2e--journey` (39259f4) → `console-e2e` (dc39076) | - | E-1, E-2 (continue, do not restart) |
| State, search | state #45, #47, #48 and search #44 as in section 4 | - | merge train, P-1 |

Mapping streams to WPs (by branch name, unverified): notify = W-1, personal = W-2, cli = W-3,
policies = W-4, access = W-5, state = W-6, blueprints = W-7, mobile and dashboards = W-9.

---

## Contents

1. Product goal and the owner's standing rules
2. Who built what (the build sessions, for reading commit messages and PR comments)
3. Architecture: services, ports, topics, storage, tokens
4. Repository layout, branches and PRs
5. Merge order
6. State of each service
7. Remaining work packages
8. Open security findings
9. How to build, test and run each service and demo
10. The whole-system end-to-end run
11. Load tests and the 10M environment
12. Gotchas (read before your first build)
13. Conventions for every change
14. Things not verified for this handoff

---

## 1. Product goal and the owner's standing rules

### 1.1 The product

The owner is **Gonzalo** (GitHub `gonzalomunoz1024`). The repository holds two products:

- **The automation platform** (branch `main`). Infrastructure providers register an OpenAPI
  spec or Ansible playbooks, build CRUD **workflows** in an n8n-style designer, and the platform
  generates REST, OpenAPI and MCP endpoints and runs the steps on Temporal (Java 21, Spring
  WebFlux, Maven, MongoDB, React). It is finished and **must not be touched**. Background in
  `handoff/services/platform-background.md`.
- **The customer console** (everything else, this handoff). A console for the firm's internal
  teams that mirrors the AWS Management Console one to one: sign in through the firm's SSO,
  pick an account and region, pick a service (a platform `kind: ResourceType`), list, view,
  create, edit, delete and act on resources, with IAM, search, tags, activity trail, quotas,
  cost and drift detection. It is fully dynamic: a new provider resource type appears as a new
  console service with no UI release. Resources are named by **wfrn** instead of ARN:
  `wfrn:<partition>:<service>:<region>:<account>:<type>/<type>-<ulid>` (spec:
  `contracts/wfrn/SPEC.md`). Phase 9 adds features beyond AWS (copilot, failure explainer,
  budgets, resource map, executive home, scorecards, blueprints, time travel and more).

The ultimate goal for the whole system: a junior infrastructure engineer can use it easily and
onboard a provider.

### 1.2 Standing rules (decided by Gonzalo; do not reopen)

| Rule | Detail |
|---|---|
| Never touch the platform | No commit to `main`, its code, content, build or docs. Anything the console needs from the platform is written up in `platform-changes/README.md` (P-01 to P-23) for the platform team. The platform stays on Maven. |
| Assume every platform change ships | Build against the platform's **target** API (`contracts/platform/openapi-target.yaml`, `asyncapi-target.yaml`). No pilot caps, no go/no-go gates, no interim adapters or workaround limits. Until the platform ships, every service runs against `platform-fake` (console-libs). |
| One orphan branch per service | Each service is conceptually its own repository; for now each is an orphan branch in this repo with its own history, build, CI and Helm chart. `console-root` is the hub. Work happens on `<branch>--<topic>` branches merged by PR. Nothing ever merges into `main`. |
| Stack | Java 21, Spring Boot WebFlux (reactive), **Gradle** (Kotlin DSL) for console services, MongoDB, hexagonal architecture plus CQRS (`com.iaf.console.<service>.<slice>.{adapters,application,domain,ports}`), Kafka for events, OpenSearch for search, Temporal for drift loops, OPA for policy. Web: React 19, Vite, TypeScript, Tailwind v4. |
| Look and feel | AWS console layout and interactions, drawn in the **Atlas** design system (Apple HIG inspired; vendored verbatim in `console-web/packages/ui`, never edited). Apple / Jony Ive restraint. The one dependency exception is `@tanstack/react-virtual`. WCAG 2.2 AA with axe checks in every journey. |
| Drift | Detect and show drift with one-click revert, accept and ignore field. **Never revert automatically.** A revert always runs on behalf of a person. |
| Customers | Internal teams. Login is the firm's SSO (OIDC). An account is a team workspace owned by an IdP group and charged to a cost centre. No self sign-up. |
| Regions | One region, `global`, modelled as an ordinary `kind: Region` so more can be added with no hacks. Every key carries the region. |
| Scale targets | 10,000 users, 1,000 concurrent, 10M resources, 1M per account, 10,000 creations an hour, list p95 < 300 ms, search p95 < 500 ms, every resource drift-checked at least hourly. |
| Events and search | Kafka and OpenSearch (the firm runs both). |
| Terminology | "workflow", never "capability". |
| Decide as a lead engineer | Gonzalo authorized building the entire system without asking him. Decide ambiguities yourself, record each decision in a README (service decisions) or the hub README (cross-service decisions, numbered), and move on. |
| Not in scope | Terraform export (needs Go). Schedules and change windows (declined). Network access. |
| Merging | Build sessions never merged a PR. The owner merges. If Gonzalo gives you merge rights, follow section 5 exactly. |

Thirty-five numbered cross-service decisions are in `handoff/HUB-README-STACK-SNAPSHOT.md`. The
ones every sub-agent must know are summarised in section 3.

---

## 2. Who built what

Commit messages, PR descriptions and PR comments refer to the build sessions by name. Each owned
some branches:

| Session ("thread") | Owned |
|---|---|
| Customer console plan / plan review | The plan document (now `handoff/PLAN-SNAPSHOT.md`) |
| Console build phase 0 | Hub PR #2, console-web #3, console-libs #4 (ran out of credits; libs passed to "Platform fake pause race") |
| Console BFF | console-bff PRs #6, #26, #34 (with the cost session), #42 (with drift), branches `--phase-9`, `--org`, `--search-p9`; hub #16, #38, #46 |
| Identity service | identity PRs #8, #24; hub #22; ran the cross-service security review |
| State service | state PRs #7, #33, #45, #48; hub #15, #27, #39; search PR #47 |
| Drift service and drift UI | drift PRs #12, #32; hub #14, #17; BFF #42 |
| Search indexer | indexer PRs #10, #29, #35, #44; hub #11, #20, #25; **hub steward** (owns `console-root--stack`, PR #30) |
| Console web phase 1 | console-web #5, #9, #37, #41 and an unpushed phase 7 branch |
| Resource detail and create wizard / Resource pages, cost and copilot | console-web #13, #23, #31, #36, #40, #43 |
| Cost, scorecards and end-to-end run | cost #28, hub #18, #19, drift #21, BFF #34; the `console-e2e` branch (not pushed yet) |
| Platform fake pause race | console-libs fixes on `console-libs--phase-0` (pause race 4260aab, re-home 6861ed5) |

---

## 3. Architecture

### 3.1 Services

| Service | Branch (root) | Port | Mongo DB | What it does |
|---|---|---|---|---|
| console-web | `console-web` | 3000 (vite) | none | The console UI. Mock mode with no backend, live mode through the BFF |
| console-bff | `console-bff` | 18081 | sessions, preferences | OIDC + PKCE sign-in, sessions, CSRF, step-up; type descriptors; the one API the web calls (`/console/v1/*`); SSE; **only issuer of internal JWTs** (ES256, JWKS at `/.well-known/jwks.json`); service tokens at `/internal/v1/token` |
| console-identity-service | `console-identity-service` | 18083 | identity | Organizations, accounts, users, groups, managed and custom roles, policies (compiled to OPA bundles), `POST /v1/authorize`, access keys, quotas, access requests, guardrails, boundaries, simulator, access reviews, elevation, break glass, account closure, IdP group sync |
| console-state-service | `console-state-service` | 18082 | state | Event-sourced resource inventory by wfrn (`resource_events` log + `resources` projection), desired vs observed, tombstones, sealed activity trail with hourly signed digests, lineage, blast radius, resource map, time travel, account transfers, blueprints/stacks, per-account envelope keys for sensitive fields |
| console-drift-service | `console-drift-service` | 18084 | drift | Drift loops on Temporal (64 shards per region, tiers hot 15 min / warm 1 h / cold 24 h), findings, person-bound revert/accept/ignore, discovery of unmanaged resources with owner guesses, monitoring metrics and alarms, rightsizing, **compliance scorecards** (rule packs, OPA or embedded engine) |
| console-search-indexer | `console-search-indexer` | 18085 | none (OpenSearch alias `console-resources`) | Kafka (`state.resources`, `drift.findings`) to OpenSearch; `/v1/search`, `/v1/suggest`, `/v1/counts`, `/v1/search:interpret` (plain English for the copilot) |
| console-cost-service | `console-cost-service` | 18086 | console_cost | Cost ledger from price lists, Cost Explorer queries, budgets with forecasts and alerts (`cost.events`), create block `budget.exceeded`, monthly chargeback with sha256 to finance |
| console-libs | `console-libs` | 18090 (platform-fake-app) | none | Gradle convention plugins (`build-logic`), Java `wfrn`, `service-kit` (account scope, errors, outbox, idempotent consumer, Mongo, OTel, `EventEnvelope`, `CursorPaging`), hand-written reactive platform client, `platform-fake` and `platform-fake-app` |
| console-sdk | does not exist yet | - | - | Decision 31: TypeScript and Java SDKs and the `console` CLI generated from `contracts/openapi/console-bff.yaml` |
| console-e2e | not pushed yet | - | - | Docker compose of the whole system plus a Playwright journey (section 10) |

Platform-fake demo accounts: `123456789012` "Compute" (team-compute) and `210987654321` "Data"
(team-data). Demo people (same in BFF demo IdP, identity seed and web mock; `login_hint=<user>`
skips the picker): gonzalo (account-admin in both), dana (operator + compute-approvers), mateo
(operator, Compute), mia (viewer + compute-approvers), raj (operator + data-approvers, Data),
ana (operator + viewer, Data), noah (no account, gets 403).

### 3.2 Topics (Kafka) and events

All console events use the platform's P-01 envelope as `service-kit`'s `EventEnvelope` writes it:
`aggregate`/`aggregateId` name the subject, `context.accountId/region/wfrn`, console fields in
`data` (`data.version` always). Type names are PascalCase (`UserChanged`). Consumers de-duplicate
on the event **key**, never the id; topics are partitioned by wfrn (scorecards by accountId).
Contract: `contracts/asyncapi/console-events.yaml` (version 1.1.0 on the stack).

| Topic | Producer | Consumers |
|---|---|---|
| platform feed (P-01) | platform (fake) | state, BFF (SSE) |
| `state.resources` | state | indexer, drift, cost, BFF |
| `state.stacks` | state (blueprints) | BFF (executes `ready` steps) |
| `identity.changes` | identity | BFF (session refresh), state (shred on `AccountChanged closed`) |
| `drift.findings` | drift | indexer, BFF |
| `drift.scorecards` | drift | BFF |
| `cost.events` | cost | BFF (`budget.alert` SSE, notifications) |

### 3.3 Tokens and authorization (decisions 21, 23, 35)

- The BFF is the **only** issuer of internal JWTs: ES256, `iss console-bff`, claims `sub`, `acct`,
  `region`, `groups`, `roles` (managed role names), `svc`, optional `name`/`email`. Every service
  verifies against the BFF's JWKS.
- **Decision 35 (supersedes the earlier shared `console` audience):** every internal JWT carries
  exactly one `aud`, the callee's service id (`console-state-service`, `console-identity-service`,
  `console-drift-service`, `console-search-indexer`, `console-cost-service`). Every service
  rejects (401) any other `aud`. A token **without `acct`** (the console-internal principal) is
  accepted only when `svc == sub` and that service is on the callee's allow list (`console-bff`
  plus the decision 23 clients registered for that audience). `roles` are never older than
  5 minutes or past an elevation's `until`. No transition period: all services switch together.
- **Decision 23 service tokens:** a service calling another as itself asks
  `POST /internal/v1/token` on the BFF (client credentials, RFC 7523 JWT client assertion; in a
  cluster the pod's projected service-account token with audience `console-bff`, elsewhere a JWT
  signed with the service's own registered EC key). Answer: a 5-minute token, one `aud`,
  `sub = svc = client id`, no `acct`. Reference client: the search indexer,
  `com.iaf.console.search.index.adapters.tokens` (ServiceTokens, ClientAssertion,
  ServiceCredentials). The ingress never routes `/internal`. No static tokens, no shared keys.
- Admin-only checks test `roles` for `account-admin`, never a group name.
- Demo profiles use dev headers (`X-Console-Account`, `-User`, `-Service`, `-Region`, `-Groups`,
  `-Roles` on state; `X-User/X-Account/X-Groups/X-Roles` on identity), same rules as tokens,
  only in the demo profile.
- Every 403 carries a `Denial` (layer, action, resource, reason, role/policy/statement,
  requestable, suggestedRole; decision 34 adds `refusedBy`, `guardrailId`). Step-up refusal is
  403 `step-up.required` with `detail.login`.

### 3.4 Other cross-service rules (from the decisions)

- Pages 1 to 200, default 50. Tombstones answer 410 for 90 days (transfer tombstones forever).
- Job events: `ProvisioningSucceeded` ends create/update in `active`; a succeeded delete is one
  `Deleted`; `ProvisioningFailed` leaves a create `failed`, returns update/delete to `active`.
- Platform gotcha: a failed step does **not** fail a job; it pauses as `needs-attention`.
- BFF ordering: create mints the wfrn on state first (`:mint` with the Idempotency-Key), then
  starts the workflow with `X-Resource-Wfrn`. Update/verbs/delete start the platform job first,
  then record `:request-update`/`:request-delete`. Retry (decision 24): rerun the job
  (`:rerun` from `lastError.failedStep`), then `:request-retry` naming the rerun.
- Required tags `owner`, `cost-centre`, `data-class`, `environment` on every create (400
  `tags.required` otherwise); never auto-filled by the BFF.
- Transfer (decision 30): state `:transfer` writes `Transferred` then `Imported`; BFF then
  re-homes on the platform (P-23 `:rehome {from, wfrn, accountId}`, repeatable moves).
- Blueprints (decision 33): state decides, BFF executes each `ready` step once per
  idempotency key; mint with `stack {wfrn, step, attempt}` is the claim.
- Copilot (decision 27): behind an `AssistantPort` in the BFF, Devin API first; it never acts,
  it proposes (a filter, a prefilled wizard, a page); turns are async (202 then poll).

### 3.5 Diagrams

The build produced diagram pages at claude.ai URLs you cannot open. Section 3 above and the hub
README are the text equivalent.

---

## 4. Repository layout, branches and PRs

Repository: `github.com/gonzalomunoz1024/infrastructure-automation`. Clone shallow per branch;
the repo has many orphan histories.

### 4.1 Hub (`console-root`, base 723c819)

`console-root` itself only holds the plan-era README, `contracts/`, `type-overlays/`,
`platform-changes/`. **All the real hub content is in open PRs.** The branch
`console-root--stack` (draft PR #30, never merged) is all 15 hub PRs merged in order with the
conflicts resolved, and is the only place every decision exists together. Treat
`console-root--stack` as the hub's truth until the PRs merge. It contains: `contracts/openapi/*.yaml`
(one per service), `contracts/asyncapi/console-events.yaml`, `contracts/platform/*`,
`contracts/wfrn/*`, `contracts/schemas/*`, `type-overlays/`, `rule-packs/`, `blueprints/`,
`scripts/check-contracts.mjs` (`npm run check`, uses OPA 1.4.2), and `platform-changes/README.md`.

Hub PRs (all open, base in brackets): #2 phase 0 [console-root], #25 descriptions [phase-0],
#11 search 1.1.0 [phase-0], #20 search 1.2.0 [#11], #15 retry, decision 24 [phase-0], #18 cost,
decision 25 + P-22 [#15], #22 identity, decision 28 [#18], #14 drift [phase-0], #17 drift
finder [#14], #19 scorecards, decision 26 [#17], #27 state 1.1.0, decisions 29/30 + P-23 [#15],
#39 blueprints, decision 33 [#27], #16 BFF phase 9, decision 27 [phase-0], #38 API clients,
notifications, org admins, decisions 31/32/34 [#16], #46 single audience, decision 35 [#38],
#30 the stack (draft).

Numbers: **next free decision is 36, next free platform change is P-24.** A new P also adds a
PR row in `platform-changes/README.md` section 5, widens `PLATFORM_CHANGE` in
`scripts/check-contracts.mjs`, and updates the counts (23 changes, 14 PRs, about 2,185 minutes).

### 4.2 Service branches and PR stacks

Every service PR is stacked: each topic branch is based on the previous one. Head SHAs as of
18:55 UTC.

| Service | Stack (PR: head branch → base) | Head |
|---|---|---|
| console-libs | #4 `console-libs--phase-0` → `console-libs` | 6861ed5 |
| console-web | #3 `--phase-0` → `console-web`; #5 `--phase-1` → `console-web`; #9 `--phase-3a` → phase-1; #13 `--phase-3b` → 3a; #37 `--phase-4` → 3b; #41 `--phase-5` → phase-4; #23 `--phase-9` → 3b; #31 `--phase-9-explainer` → phase-9; #36 `--phase-9-retry` → explainer (merges phase 4 in); #40 `--phase-9-home` → phase-9; #43 `--phase-9-copilot` → phase-9; #49 `--phase-9-cost` → phase-9 | #41 8b7980c, #43 563fd5b, #36 03676ef |
| console-bff | #6 `--phase-1` → `console-bff`; #26 `--phase-4` → phase-1; #34 `--cost` → phase-4; #42 `--drift` → phase-4. Pushed without a PR: `console-bff--org` (2dfa817), `console-bff--search-p9` (16d0d05). **Not on GitHub:** `console-bff--phase-9` | #26 7e3b5d4, #34 69c0853, #42 1096484 |
| console-identity-service | #8 `--phase-1` → root; #24 `--phase-7` → phase-1 | #24 317bc40 |
| console-state-service | #7 `--phase-2` → root; #33 `--phase-9` → phase-2; #45 `--blueprints` → phase-9; #48 `--load` → phase-9 | #33 61ef5d1, #45 6ce9ba1, #48 90ee637 |
| console-drift-service | #12 `--phase-5` → root; #32 `--finder-and-monitoring` → phase-5; #21 `--scorecards` → phase-5 | #32 1f6e4ce, #21 ad9cbcc |
| console-search-indexer | #10 `--phase-6` → root; #29 `--phase-9` → phase-6; #35 `--transfers` → phase-9; #44 `--load` → transfers; #47 `--state-load` → load | #35 200dba4, #44 cc4701f, #47 3133b09 |
| console-cost-service | #28 `--phase-7` → `console-cost-service` | 8ca4409 |

The newest branch of each service is where new work starts: cut `<service>--<topic>` from it.

---

## 5. Merge order

Nothing has merged. Merge only with Gonzalo's go-ahead. Rules: "Create a merge commit" only, never
squash or rebase (children carry parents' commits; `.gitleaksignore` pins commits); merge one PR
at a time, bottom of each stack first; after each merge retarget its children to the service root
(GitHub does it when the merged branch is deleted); re-run CI on the child before merging it.

1. **Hub first**, turns 1 to 15 exactly as `handoff/MERGE-ORDER-SNAPSHOT.md` (read it; turns 10
   to 14 need `console-root` merged in first with the stack's recorded resolution, which you can
   copy from the turn commits on `console-root--stack`): #2, #25, #11, #20, #15, #18, #22, #14,
   #17, #19, #27, #39, #16, #38, #46. Then close #30.
2. **console-libs** #4, then publish libs (WP L-1) so services can stop building libs in CI.
3. Services in any order, each bottom-up:
   - identity #8, #24
   - state #7, #33, #45, #48
   - indexer #10, #29, #35, #44, #47 (#47 also needs state #48's seeder; merge after it)
   - drift #12, #32, #21 (#21 is beside #32 on phase-5; after #32 merges, merge `console-drift-service` into #21 and re-run CI)
   - cost #28
   - BFF #6, #26, then #34 and #42 (both on phase-4; the second needs the first merged in), then the phase 9, org and search-p9 work once they have PRs
   - web #3, #5, #9, #13, #37, #41, then #23, #31, #36, #40, #43, #49 (the phase 9 PRs were cut from phase-3b; merge `console-web` into each before its merge)
4. After each service merges, re-pin its `contracts.lock` to the merged `console-root` commit
   (WP H-2).

---

## 6. State of each service

CI states are as reported by the owners at 18:25 to 18:53 UTC **(unverified; run
`gh pr checks <n>` for each)**.

### console-libs
- Done: all phase 0 modules; pause race fix (4260aab: a job pauses before its approval is listed);
  P-23 re-home in fake and client, repeatable (6861ed5), pinned to hub e790a27.
- Open: PR #4, CI green. Not published to GitHub Packages (decision 15); every service CI builds
  it from source.

### console-bff
- Done and pushed: phase 1 (#6, 98 tests), phase 4 (#26, 164 tests), cost/budgets/scorecards
  (#34, green), drift routes (#42, green at 1096484), organization admins and phase 7/9 IAM
  pass-through (`console-bff--org`, 322 tests, no PR), search interpret and grouped counts
  (`console-bff--search-p9`, no PR).
- In progress when the handoff was written, **not on GitHub (at risk)**: `console-bff--phase-9`
  (executive home `/insights`, failure explainer `/jobs/{id}/explanation`, the decision 27 paths;
  it was stacked on `console-bff--cost`), the copilot `AssistantPort` slice, device-code bearer
  tokens (decision 31), notifications (decision 32), the security fixes (decision 35 single
  audience minting, session role refresh, per-row batch checks, lows).
- Not started: ChatOps, time-travel and transfer routes (decisions 29/30 BFF side), blueprints
  executor (decision 33), BFF load scenarios (WP 6.2), SDK/CLI (`console-sdk`).
- Known workaround to remove: `PausedJobs` retry on 409 `job.notPaused`, once the BFF builds
  against libs ≥ 4260aab.

### console-identity-service
- Done: WP 1.1, 1.2 (#8, green at 6b1bd68), WP 7.6, 7.7, 7.8, 9.11, 9.12, blueprints actions,
  decision 25 cost actions, all security findings fixed (#24, green at 73b371e / 317bc40).
- Open: nothing but merging. Contract on hub #22.

### console-state-service
- Done: WP 2.1 to 2.5 (#7), phase 9 (lineage, blast radius, map, sealed trail, time travel,
  transfers; #33, green at 61ef5d1 with 8 of 9 security fixes; the ninth, transfer, stays a
  person's command by design), blueprints (#45, green), load seeder (#48, CI running).
- Open: the 10M load run (needs a load environment), merging.

### console-drift-service
- Done: WP 5.1, 5.3, 5.4 (#12, #32), monitoring and rightsizing (9.21, 9.22, in #32), security
  fixes in #32 (162 tests, green at 1f6e4ce), scorecards (#21).
- In progress: a scan simulation at 1M then 10M resources against the ~6 platform calls/second
  budget **(not pushed; at risk)**; scorecards security fixes on #21 (three findings, owned by the
  cost/e2e session; status unknown).
- To do: merge the BFF's phase-4 decision 35 fix into BFF #42 once it lands.

### console-search-indexer
- Done: WP 6.1 (#10), phase 9 search (#29), transfers (#35), WP 6.2 search side with Gatling and a
  Grafana dashboard (#44), state Gatling scenarios (#47), all security fixes. CI green on #35,
  #44 (18:49 UTC).
- Open: a full run at 10M (local run: 1,000 people, 2M docs, 0 failures, p95 16 ms).

### console-cost-service
- Done: ledger, Cost Explorer, budgets, chargeback (#28, green at ed3806e).
- In progress **(at risk)**: 5 security findings plus decision 35 on #28; merging the BFF phase-4
  security fixes into the cost BFF branch #34.

### console-web
- Done and pushed: phases 0, 1, 3a, 3b, 4 (#37 green), 5 (#41 green), phase 9 groundwork (#23),
  failure explainer (#31), retry with fix (#36 green), executive home (#40 green), copilot (#43
  green), cost, budgets and chargeback (#49 `console-web--phase-9-cost` on phase-9, opened
  18:57 UTC, CI not yet finished).
- In progress, **not on GitHub (at risk)**: a phase 7 branch stacked on #41 with seven streams
  (7.2/7.5/9.13 notifications and digests; 7.2/7.6 recent, favourites, Service Quotas and
  increases; 7.4/9.16 device sign-in, CLI sessions, console shell, developer portal; 7.8 policy
  editor, simulator, boundaries, guardrails; 9.11/9.12/7.7 access reviews, elevation, break
  glass, closure; 7.1/9.8/9.4 activity trail, evidence, time travel, lineage, resource map;
  9.7/9.10/9.6/9.14 blueprints, change risk, scorecards, event subscriptions); then 9.18, 9.19,
  9.20. And the security fix: every platform `/api/v1` call moved behind the BFF.
- **Known integration bug** (found by the e2e session): console-web reads
  `GET /console/v1/types` as a bare array; the BFF and the hub contract answer `{items: [...]}`,
  so the live console renders blank after sign-in on every web branch. Mock mode hides it.

### console-e2e
- A compose stack (Mongo, Kafka KRaft, OpenSearch, OPA, Temporal, Dex as dev SSO, platform-fake
  plus a feed bridge, all six services, console-web live) was healthy locally in the e2e
  session, with real BFF-issued tokens and sign-in through Dex. **Nothing is pushed.**

---

## 7. Remaining work packages

Each WP lists: goal, branch, depends on, files, acceptance, commands. WP ids are new
(handoff-local), with the plan's WP numbers in brackets. "Lane" maps to the sub-agent split in
`handoff/DEVIN-PLAYBOOK.md`. ETAs are agent minutes, rough.

### Phase R: recover and verify (do first, 30 min, lane 0)

**R-1 Inventory what exists.** Start from the 19:05 UTC update table at the top. `git ls-remote --heads origin`; compare with section 4. For each
branch marked "at risk" in section 6, check whether it appeared after this handoff (the build
sessions may have pushed later). Record the result in a new section of `HANDOFF.md` on your own
branch. Run `gh pr list --state all` and `gh pr checks` per PR. Acceptance: a table of every
branch, PR, head and CI state, and the list of at-risk items that are truly missing (they become
rebuild WPs below).

### Phase S: security and decision 35, in lockstep (lane 1, 240 min total)

Decision 35 must land in every service and the BFF at the same time: once the BFF mints a single
audience, any service still requiring the old pair fails, and once services require exactly their
own audience, any BFF still minting `[target, "console"]` fails (a JWT library may accept a list
containing the expected audience; check each verifier). Verify in the e2e stack.

**S-1 BFF security [review items 1 to 8].** Branch `console-bff--security` from
`console-bff--phase-4` (then merge into `--cost`, `--drift`, `--org`, `--search-p9`, phase 9).
Files: `trust/application/Trust.java` (mint `aud` = target only, remove `CONSOLE_AUDIENCE`),
`/internal/v1/token` (exactly one `audience`, else `invalid_target`), `Sessions.refreshAccounts`
(call it on `identity.changes`/`ElevationChanged` and at least every 5 min; cap at earliest
`until`), `resources/application/Resources.java` batch and `previewBatch` (authorize per row on
its wfrn; refuse or name denied rows), `IamController`/`IamProxy` (reject `.`, `..`, `%2e`,
`%2f`, `\`), `Scorecards.java` (fail closed, check region), `Access.java`/`HttpAuthorization.java`
(region in cache key), `Sessions.java`/`MongoSessionStore.java` (conditional updates, store
sha256 of session id), `AssertionReplays.java`/`ServiceTokens.java` (shared jti store in Mongo,
cap SA token lifetime, per-client rate limit). Acceptance: new tests for each; a viewer denied
`compute:restart` on `vm/prod-*` cannot restart it through `:batch`; a revoked elevation stops
working downstream within 5 min; all BFF tests green. Details:
`handoff/security-review/bff.md`.

**S-2 Cost security [review items 1 to 5].** On `console-cost-service--phase-7` (#28): accept only
`aud console-cost-service`, fail closed on empty issuer/audiences, internal only for allow-listed
`svc == sub` (bff, plus drift for estimates), CSV formula escaping for tab/CR/leading spaces,
actuator on a management port, `svc=console-bff` required for chargeback/budget writes.
`handoff/security-review/cost-service.md`.

**S-3 Scorecards security.** On drift #21: the three findings in
`handoff/security-review/drift-service.md` (scorecards part), plus decision 35 as #32 already has.

**S-4 Web security [review item 1 and lows].** On the newest web branch: move every `/api/v1`
read (`lib/api.ts`, `ResourceDetailPage`, `HomePage`, `RequestsPage`, `resource/tabs.tsx`,
`PlatformFeed`, `app/console.tsx`) to BFF routes (`/console/v1/r/...`, `/console/v1/jobs`,
`/console/v1/events`), stop sending `X-User`, remove `CONSOLE_PLATFORM` from live mode, keep the
mock in step; step-up redirect rejects `//` and `/\`; https-only help links; clear recent visits
on sign-out. Fix the `/types` `{items}` bug here too. Acceptance: `grep -r "/api/v1" console/src`
finds only the mock; all journeys pass; the e2e stack shows types after sign-in.
`handoff/security-review/console-web.md`.

**S-5 Verify every service against decision 35.** State (#33), drift (#32), indexer (#35),
identity (#24) report done; cost after S-2. Write one contract test per service: a token with
`aud` = another service is 401; a no-`acct` token from a non-listed `svc` is 401. Acceptance:
tests green on every service.

### Phase H: hub (lane 0, ongoing)

**H-1 Keep the stack.** Any new hub change goes on a `console-root--<topic>` branch, takes the
next decision number (36+) or P number (P-24+), and is added as a new turn on
`console-root--stack` with `npm run check` green. Update `MERGE-ORDER.md` there.

**H-2 Re-pin contracts.** After merges, each service's `contracts.lock` (commit + sha256 of each
copied file, verified by `ContractsLockTest` or `npm run contracts:verify`) moves to the merged
`console-root` commit. Copy contract files from the hub, never edit them in a service.

### Phase L: libs (lane 0, 60 min)

**L-1 Publish console-libs.** Decision 15: publish `com.iaf.console:*` to GitHub Packages on merge
to `console-libs` (workflow in `console-libs/.github/workflows`). Then change each service's CI to
consume the published version instead of building `CONSOLE_LIBS_REF` from source. Until then keep
the gotcha in section 12.

**L-2 Drop workarounds.** Remove the BFF's `PausedJobs` retry loop once it builds on libs ≥ 4260aab.

### Phase B: BFF features (lane 2, about 900 min)

**B-1 Phase 9 BFF [9.2, 9.5, decision 27].** If `console-bff--phase-9` is missing, rebuild it from
`console-bff--cost`: `GET /console/v1/insights` (managed now and at period start, requests and
share self-served, lead time vs configured baseline, hours saved, drift found/fixed, weekly
adoption, spend from cost `groupBy=costCentre&scope=organization`; window start cut to the minute,
end never truncated; `drift.partial: true` when drift fails for an account, not cached; never
count a request carrying label `retry-of` or a person's `decision` evidence as self-served),
`GET /console/v1/jobs/{jobId}/explanation` (rules over platform error classes, copilot for the
rest; fix `input` is a merge for the phase 4 retry), every rerun carries job label
`retry-of: <failed job id>` (`JobRetries.RETRY_OF_LABEL`). Merge `console-bff--search-p9`
(`/search:interpret`, `/search:counts`). Open a PR. Acceptance: matches
`contracts/openapi/console-bff.yaml` on the stack (decision 27 paths); web #40 and #31 work in
live mode.

**B-2 Copilot [9.1].** `AssistantPort` with a Devin adapter (profile `devin`) and a deterministic
demo adapter; `POST /console/v1/assistant/turns` (202), `GET
/console/v1/assistant/conversations/{id}`; reads with the person's own session; answers in words
and proposals only (filtered list, prefilled wizard via `?prefill=` base64url JSON, page). Uses
`/search:interpret`, `Costs#monthlyCost`. Acceptance: web #43 works live; the copilot can never
start a job.

**B-3 Organization admins [decision 34].** Open a PR for `console-bff--org` (on phase-4; it may
need cost merged in). Acceptance: non-admins get 404 on `/console/v1/organization/*`; writes need
a fresh sign-in.

**B-4 Time travel, transfer and trail routes [decisions 29, 30, 34].** `?at=`, `revert-plan`,
`activity:verify`, `POST /console/v1/r:blast-radius` (≤ 100), `/console/v1/map`,
`/console/v1/activity` with digests and keys, evidence pack for account-admin and auditor;
`POST /console/v1/r/{type}/{wfrn}:transfer` (authorize `<service>:transfer` at source and
`<service>:create` at target, `confirm`, fresh sign-in, target budget check fail-open, state
`:transfer`, then `PlatformPort.rehomeResource(registryId, RehomeRequest(from, wfrn, accountId))`,
201 with the new resource); whole-account move via state `/internal/accounts/{id}:transfer` in
pages, re-homing each pair. Details in `handoff/services/state.md` and `bff.md`.

**B-5 Blueprints executor [9.7, decision 33].** Consume `state.stacks`; for each `ready` step
execute once per key `stack-<ulid>:<step>:<action>:<attempt>`: create = mint with `stack {wfrn,
step, attempt}` then start the job as the launching person (409 `stack.stepNotReady` = drop);
delete = the type's delete workflow + `:request-delete`; cannot start =
`steps/{step}:report-failure`. Authorize `blueprints:*` and per-child `<service>:create/delete`
before calling state. BFF routes for launch/teardown/resume/list/get. Acceptance: the
`blueprints/web-app.yaml` sample launches and tears down in the demo.

**B-6 API clients [7.4, 9.15, decision 31].** Device grant (`POST /auth/device`, `/auth/token`),
15-minute `at+jwt` bearer tokens `aud console-api`, rotating refresh tokens (reuse ends the API
session), API sessions list/revoke, `X-Console-Account`/`-Region` per call, step-up from the
approving sign-in, access-key exchange. Then create the orphan branch `console-sdk`
(TypeScript and Java SDKs and the `console` CLI generated from `console-bff.yaml`, pinned via
`contracts.lock`). Acceptance: `console login` then `console vm list` works against the demo.

**B-7 Notifications, recent, favourites, digest [7.2, 7.5, 9.13, decision 32].** Feed kept 90
days, `notification` SSE events, email and Slack/Teams through an outbox (exactly once per event,
person, channel by first insert of the key), per-kind preferences with defaults, recent (20 per
account), favourites (100), weekly digest Monday 06:00 UTC once across replicas.

**B-8 ChatOps [9.17, decision 31].** Slack/Teams identity link through a short BFF-signed token
confirmed in the console; verify Slack signing secret / Bot Framework JWT; run as the linked
person through IAM; destructive verbs answer a console link (chat never satisfies step-up).

**B-9 Event subscriptions [9.14].** No owner yet: an account's resource events to a webhook,
Kafka topic or Slack. Needs a hub decision (36). Suggest it lives in the BFF, following
`state.resources`, with signed webhooks and per-subscription retries.

**B-10 Change risk score [9.10].** No backend owner yet: score each request from blast radius,
prod tags and history; high risk needs an approver. Needs a hub decision. Compute in the BFF
preview; web shows it.

**B-11 Service health [9.20].** BFF route over the platform's SLO telemetry (target contract);
web page.

### Phase W: web (lane 3, about 1,100 min)

All on `console-web`, stacked on `--phase-5` (#41) unless noted. Keep the conventions in
`handoff/services/web.md` (slots, `?prefill=`, mock ledger, decision number ranges: web README
decisions from 70 for phase 7, 50 to 69 phase 9). Every page: mock and live mode, a Playwright
journey with `expectNoSeriousA11y` and `expectNoHorizontalOverflow`.

- **W-1** Rebuild or recover the phase 7 branch: notifications, settings, chat links, digests
  (7.2, 7.5, 9.13).
- **W-2** Recent, favourites, Service Quotas, quota increase requests (7.2, 7.6).
- **W-3** Device sign-in approval page, CLI sessions, console shell (runs the CLI's command core
  in the page), developer portal and IDP tiles mock (7.4, 9.16).
- **W-4** IAM: policy editor (AWS-shaped JSON with validation), simulator, boundaries,
  guardrails (organization admins) (7.8).
- **W-5** Access reviews, just-in-time elevation, break glass, account closure (9.11, 9.12, 7.7).
- **W-6** Activity trail with digests and evidence download, time travel (`?at=`, revert to this
  as a plan the person confirms), lineage and the resource map (zoomable graph, coloured by
  health, drift or cost) (7.1, 9.8, 9.4).
- **W-7** Blueprints launch/teardown/resume, change risk on previews, scorecards and org heat map,
  event subscriptions (9.7, 9.10, 9.6, 9.14).
- **W-8** Cost Explorer, budgets, chargeback (7.3, 9.3, 9.9): built as PR #49. Remaining: drive
  #49 green, make the mock event stream emit `budget.alert`, show the budget name in its
  breadcrumb, then switch to live against BFF #34.
- **W-9** Mobile approvals (installable responsive view), team dashboards, service health (9.18,
  9.19, 9.20).
- **W-10** Account transfer action (decision 30/34) on the resource page and whole-account move for
  org admins.

### Phase E: end to end (lane 4, about 240 min)

**E-1** Create the orphan branch `console-e2e` (work on `console-e2e--journey`, PR into it):
`docker-compose.yml` with Mongo (replica set), Kafka KRaft, OpenSearch, OPA, Temporal, Dex (dev
OIDC), platform-fake-app, identity, state, BFF, indexer, drift, cost, console-web in live mode;
real profiles (`mongo,kafka,temporal` as each service lists) and real BFF service tokens (fresh EC
keys per run registered under `console.trust.clients.<id>`; no dev headers); `services.lock`
pinning each service branch and commit; `./e2e.sh` one command; `.github/workflows/e2e.yml`.
Service code is never changed from e2e: file bugs against the service's branch.
**E-2** Playwright journey: sign in as gonzalo through Dex, create a VM with required tags, see it
`active`, find it in search, see its cost, inject drift on platform-fake and revert it, delete
with step-up, see the tombstone. Acceptance: the CI job is green from a clean runner.

### Phase P: load and production (lane 5, about 600 min plus environment time)

- **P-1** 10M load runs (section 11): state (#47/#48), indexer (#44), drift scan simulation, BFF
  cache scenarios (new Gatling scenarios in the BFF for descriptors, decisions and session
  refresh). Acceptance: scale targets in section 1.2 met; results in each service README.
- **P-2 [8.1]** A second region as a cell: add a `kind: Region` overlay, region routing in the BFF,
  failover drill. Needs a hub decision.
- **P-3 [8.2]** Threat model per service (STRIDE) building on `handoff/security-review/`, SLO
  alerts (Prometheus rules in each chart), runbooks per service in `docs/runbooks/`, journeys in
  pictures (Playwright screenshots).
- **P-4 [8.3]** Backups and a restore drill to RPO 5 min / RTO 1 h (Mongo PITR, OpenSearch
  snapshot, projection rebuild runbook for state and indexer), owners, monthly cost estimate.

### Not to do

Anything on `main` or the platform; Terraform export; schedules or change windows; automatic
drift revert; pilot caps or interim adapters.

---

## 8. Open security findings

From the review of 2026-10-23 (`handoff/security-review/`). Status per the owners at 18:50 UTC.

| Service | Finding | Status |
|---|---|---|
| All | Shared `console` audience; no-`acct` tokens treated as internal | Decision 35 (hub #46). Fixed in identity, state, drift, indexer. **Open in BFF (S-1) and cost (S-2)** |
| BFF | Roles frozen 12 h; `:batch` checked only on the type wildcard; 5 lows | **Open** (S-1) |
| Drift | Writes not authorized in the service | Fixed on #32 |
| State | Confused deputy through registry-id fallback, and 7 more | Fixed on #33; transfer stays a person's command by design |
| Web | Reads platform `/api/v1` directly with client-set `X-User`; 4 lows | **Open** (S-4) |
| Cost | 1 high, 1 medium, 3 lows | **Open** (S-2) |
| Scorecards (drift #21) | 3 items | **Open** (S-3) |
| Search indexer | 2 medium, 3 low | Fixed on #35 |
| Identity | 1 high, 2 medium | Fixed on #24 |

---

## 9. How to build, test and run

Every Java service: JDK 21, Gradle wrapper, `./gradlew build` (tests included). Adapter tests
that need infrastructure run only when the env var is set: `CONSOLE_TEST_MONGO_URI`
(replica set), `CONSOLE_TEST_KAFKA_BOOTSTRAP`, `CONSOLE_TEST_OPENSEARCH_URL`, `CONSOLE_OPA_BIN`,
`CONSOLE_TEST_PLATFORM_URL` (platform client contract suite against a real platform).

**First, publish console-libs locally** (until L-1):

```bash
git clone --depth 1 -b console-libs--phase-0 https://github.com/gonzalomunoz1024/infrastructure-automation libs
cd libs && ./gradlew :build-logic:publishToMavenLocal publishToMavenLocal -x test --no-daemon
```

| Service | Demo run | Notes |
|---|---|---|
| platform-fake-app | `./gradlew :platform-fake-app:bootRun` in libs (port 18090) | Demo accounts as in 3.1 |
| BFF | `./gradlew bootRun --args='--spring.profiles.active=demo'` (18081) | Embeds platform-fake 18090, demo IdP `/demo-idp`, stand-ins `/demo-identity`, `/demo-state`, `/demo-search`, `/demo-drift`. Swap in real services with `--console.identity.url=http://localhost:18083 --console.state.url=http://localhost:18082 --console.search.url=http://localhost:18085 --console.drift.url=http://localhost:18084 --console.platform.fake.enabled=false`. Profiles `mongo`, `kafka`. Tests: demo app on 18181 |
| identity | `bootRun` with profile `demo` (or `demo,fake`) (18083) | Dev headers; OPA tests need `CONSOLE_OPA_BIN` |
| state | `bootRun` with profile `demo` (18082) | Embeds platform-fake; production `mongo,kafka`; `CONSOLE_MASTER_KEY`, optional `CONSOLE_TRAIL_SIGNING_KEY` |
| drift | `scripts/demo.sh`, then `scripts/demo.sh journey` (18084) | Production `mongo,kafka,temporal`. Beside the BFF: `--console.demo.platform-port=18091 --console.auth.dev-headers=false --console.auth.jwks-uri=http://localhost:18081/.well-known/jwks.json` |
| indexer | `bootRun` demo (18085) | `kafka` profile reads topics, else polls state; `scripts/opensearch.sh` runs OpenSearch from jars without Docker |
| cost | `bootRun` demo (18086) | `console.cost.base-url` empty means bundled prices |
| web | `npm ci && npm run dev:mock` (3000, no backend) or `CONSOLE_BFF=http://localhost:18081 npm run dev` | Typecheck `cd console && npx tsc -b`; journeys `cd console && E2E_PORT=3100 npx playwright test` |
| hub | `npm ci && npm run check` | Needs OPA 1.4.2 on PATH; `npm run check:platform -- --from contracts/platform/openapi-c9529d5.json` |

Real-token recipe (verified 2026-10-23): state demo with `--console.auth.dev-headers=false`; BFF
demo with `--console.platform.fake.enabled=false --console.state.url=http://localhost:18082
"--console.trust.clients.console-search-indexer.jwks=<public JWK>"`; indexer demo with
`CONSOLE_AUTH_CLIENT_KEY=<private JWK with kid>`. A cross-service drift journey script is
`console-drift/journey-bff-drift.sh` in the project files (copy in `handoff/scripts/`).

---

## 10. The whole-system end-to-end run

See WP E-1 and E-2. Until it exists, the closest is running the demos side by side with real
tokens (section 9). In cloud sandboxes without Docker the compose stack only runs in GitHub
Actions.

---

## 11. Load tests and the 10M environment

Harnesses exist; full runs need an environment with real MongoDB (replica set), Kafka and
OpenSearch sized for 10M resources (suggest: Mongo 3-node M50-class, OpenSearch 3 data nodes
with 64 GB, Kafka 3 brokers, services at their chart replica counts, e.g. indexer 3).

- Indexer (#44): `src/gatling` (Estate, SeedEstate, SearchSimulation), `./gradlew seedEstate`,
  `./gradlew gatlingRun -Pload.*`; Grafana dashboard `deploy/grafana`; CI "Load smoke". Budget
  30/s burst 60 per account per replica (429 above).
- State (#47 scenarios on the indexer harness, #48 seeder): lists, filters, detail, events,
  trail, 10k mints an hour, signing its own BFF-style tokens.
- Drift: scan simulation at 1M and 10M against about 6 platform calls a second (to be pushed).
- BFF: not written (P-1).

---

## 12. Gotchas

- **console-libs `build-logic`**: the root `publishToMavenLocal` does not publish the included
  build; without `:build-logic:publishToMavenLocal` you get "Plugin [id: 'console.spring-service']
  was not found". Each service CI checks out `CONSOLE_LIBS_REF` and runs both. The init script
  needs `gradlePluginPortal()` in `pluginManagement` (a mirror-only setup fails on kotlin-dsl).
- **gitleaks** (CI secret scan) flags literal PEM armor, long hex/base64 next to key/token/digest
  words, and `header("Idempotency-Key", "<literal>")`. Build PEM strings from parts and compute
  fixtures at run time. Local: `go install github.com/zricethezav/gitleaks/v8@v8.30.1`.
- **Never rebase or squash**: `.gitleaksignore` pins commits, and stacks carry parents' commits.
- **Helm**: `get.helm.sh` may be blocked; `go install helm.sh/helm/v3/cmd/helm@v3.16.4`.
- **Docker** may be missing or die in sandboxes: `rm -f /var/run/docker.pid; nohup dockerd &`.
  Without Docker, MongoDB can come from conda-forge (`mongodb-8.3.7` package) as a single-node
  replica set; `mongo-java-server` works as a stand-in for some tests.
- **WebClient timeouts**: `.timeout()` in a filter only bounds headers; use Reactor Netty
  `responseTimeout` on the connector. Every call to the BFF token endpoint needs a time limit
  (5 s) because callers share the in-flight request.
- **Never copy a new jar over one a running JVM uses** (event-loop threads die, requests hang).
- **Spring**: `@SpringBootTest(classes=…)` ignores nested `@TestConfiguration`, use `@Import`; map
  keys in test properties must be dotted (`clients.x.y`); an option given twice is joined with a
  comma, so test helpers replace defaults.
- **YAML descriptions with commas** in flow maps are cut into null keys; quote them. `npm run
  check` fails on null entries.
- **OPA Rego**: `not f(input.x)` is false when `input.x` is undefined; use helper rules.
- **OpenSearch in a sandbox**: disk watermark blocks index creation; set
  `cluster.routing.allocation.disk.threshold_enabled=false`.
- **Playwright**: never two runs in one checkout; use a spare `E2E_PORT`; Chromium from the
  environment, do not download browsers if one is provided.
- **Prettier**: no config in console-web; defaults add semicolons. Use `--no-semi --single-quote
  --print-width 200 --trailing-comma all`, and only on new files.
- **SSE**: the platform names each event after its type (`event: job.started`); consumers must
  subscribe to all 24 types in `asyncapi-target.yaml`.
- **Atlas**: never edit `packages/ui`; override in console CSS or fork into `console-kit`. Use
  `StatusBadge` (not Atlas `Tag` tones) for anything axe checks.
- **Test isolation**: give each Mongo test its own database; collect bus events in a
  `CopyOnWriteArrayList`.
- **Long runs**: CI jobs that start the platform fake plus Mongo can take 10+ minutes; give
  commands generous timeouts and never run two Gradle builds in one tree.

---

## 13. Conventions for every change

- Branch `<service branch>--<topic>` from the newest branch of that service; PR into it.
- Commit as Gonzalo: `git -c user.name="Gonzalo Munoz" -c user.email="munozgonzalo1024@gmail.com"`,
  plus your own attribution trailer. No model names in code or commits.
- A contract change goes to a hub PR first (with a decision number from the steward), then the
  service copies the file and re-pins `contracts.lock`.
- Each service README has a "Decisions" table for service-local choices; add rows.
- Tests: unit plus journey tests on the demo profile; adapter tests gated by env vars; ArchUnit
  rules forbid cross-account port reads and layer violations.
- Keep demo people, accounts and names identical across BFF demo, identity seed and web mock.
- Before a push, run the service's CI commands locally (build, tests, gitleaks).

---

## 14. Things not verified for this handoff

- CI status of every PR (taken from owners' reports, 18:25 to 18:53 UTC).
- Whether the at-risk work in section 6 was pushed after 19:00 UTC.
- Whether BFF #34 and #42 conflict with each other (both stack on phase-4).
- The exact content of the drift scorecards findings (see the file) and whether #21 fixed them.
- The e2e compose details (only described by its owner; nothing pushed).
- Test counts quoted per service.
