# The console's services, one section each

Every service is an orphan branch in `gonzalomunoz1024/infrastructure-automation` (decision 8), built with Gradle, Java 21, Spring WebFlux, CQRS plus ports and adapters, MongoDB, and the shared `com.iaf.console:*` libraries from `console-libs` (published to mavenLocal: see `../services/libs.md` for the build-logic gotcha). Each pins the hub's contracts in `contracts.lock`. Each has a `demo` profile (in memory, platform-fake on 18090, dev headers) and production profiles (`mongo`, `kafka`, `temporal`, `opensearch` where relevant), a Helm chart under `deploy/helm/`, and ArchUnit tests that keep the hexagon honest.

For each service: the verbatim README of its newest branch is in `services/README-<branch>.md` (its own decision table, API list, run commands), and the build sessions' notes are in `../services/<name>.md`. Open PRs and heads are in `../../HANDOFF.md` sections 3 and 5 and `../BRANCH-HEADS.md`.

```
browser ── console-web (3000) ──► console-bff (18081) ──┬─► identity (18083)   IAM, roles, policies, elevation, access reviews
                                   only public API,      ├─► state (18082)      the customer view of every resource, trail, stacks
                                   only token issuer     ├─► search-indexer (18085) OpenSearch reads
                                                         ├─► drift (18084)      drift, finder, scorecards, rightsizing
                                                         ├─► cost (18086)       spend, budgets, chargeback
                                                         └─► platform API (target contract; platform-fake-app 18090)
Kafka topics: state.resources, state.activity, drift.events, cost.events, identity.events, platform.events (P-01)
```

## console-libs (shared code)

- **Owns:** the Gradle convention plugins (`console.spring-service`, `console.java-library` in `build-logic`), the wfrn library (Java, with fixtures shared with the TypeScript one), `service-kit` (problem+json errors, paging cursors, internal-JWT verification, dev headers, observability), the typed platform client for the target contract, and **platform-fake** (an in-memory platform implementing `openapi-target.yaml` and `asyncapi-target.yaml`; `platform-fake-app` serves it on 18090 with drift injection for tests).
- **Branch:** `console-libs--phase-0` (newest head in BRANCH-HEADS.md). Every service CI clones it.
- **Why it exists:** the platform changes P-01..P-23 have not shipped; every service codes against the target API now and swaps platform-fake for the real platform later with no code change (Gonzalo: assume they all ship, no interim limits).

## console-bff (backend for frontend)

- **Owns:** sign-in (OIDC + PKCE against the firm's SSO, `console_session` cookie 12 h / 1 h idle, CSRF token plus Origin check, step-up within 10 minutes for destructive verbs), the only API the browser calls (`/console/v1/...`), descriptors (`/types`, turning each `ResourceType` plus its workflows into a console service with buttons), resource pages over the state service, jobs and approvals, SSE `/console/v1/events`, preferences, IAM and access-request pass-through, cost and drift routes, previews (policy, cost, budget check), batches, blueprints execution (decision 33), the copilot `AssistantPort` (Devin first, decision 27), executive home.
- **Token model:** the only issuer of internal ES256 JWTs (decision 23, `POST /internal/v1/token`), one audience per token and no-account tokens only for allow-listed services (decision 35). See `../services/bff.md` and `../security-review/`.
- **Calls the platform** on behalf of the user with `X-On-Behalf-Of` (P-02); never passes a platform read straight to the browser.
- **Branches:** phase-1 #6 → phase-4 #26 → cost #34, drift routes #42, phase 9 on `console-bff--phase-9`. Remaining work: `../bff-remaining.md`.

## console-identity-service (IAM)

- **Owns:** users, groups (from the identity provider), accounts (team workspaces with cost centres), managed roles (`viewer`, `operator`, `account-admin`, `auditor`), customer-managed policies in an AWS-IAM-like JSON language, evaluated by OPA (Rego), permission boundaries and organization guardrails (decision 28), quotas and quota requests, access requests, time-boxed elevation and break glass, access reviews, organization admins (decision 34).
- **API:** `/v1/authorize` (decision plus a denial body naming the layer, statement and whether access can be requested), CRUD for each id prefix (`user_`, `grp_`, `role_`, `pol_`, `ck_`, `qreq_`, `areq_` + ULID).
- **Events:** `identity.events`. Port 18083. Newest branch `console-identity-service--phase-7` (#24, contract hub #22).

## console-state-service (the customer view)

- **Owns (decision 3):** the authoritative customer view of every resource. An append-only log `resource_events` keyed `<Type>:<wfrn>:<version>` plus a projection `resources`; no-op commands write no event; deleted resources answer 410 tombstones for 90 days (forever after a transfer).
- **Fed by:** the platform's event feed (P-01: "that something happened") and registry ("what it is now"); unknown registrations become `Imported`, retired ones `Deleted` (hub decision 20). A change and its job can arrive in either order.
- **Also:** the sealed activity trail (hash chain, hourly signed digests per account, evidence packs, decision 29), time travel (`at=`), tags, lineage, transfers between accounts (decision 30, P-23; repeatable), retries (decision 24), blueprints and stacks (decision 33).
- **Events:** `state.resources`, `state.activity`. Pages 1 to 200, default 50. Port 18082. Newest branch `console-state-service--blueprints` (#45), load tests #47/#48.

## console-search-indexer

- **Owns:** the OpenSearch index of resources (consumes `state.resources`, backfills from the state service), `/v1/search` (terms like `type:`, `state:`, `tag:k=v`, `drift:`, wfrn patterns, seven sorts, cursors, did-you-mean), `/v1/suggest`, `/v1/counts` (group-by), and `GET /v1/search:interpret` (a deterministic plain-English reader for the copilot).
- **Why separate:** list p95 under 300 ms and search under 500 ms at 10 M resources (decision 5); state's Mongo projection answers exact reads, OpenSearch answers search.
- Port 18085. Newest branch `console-search-indexer--load` (#44). Its session was the hub steward.

## console-drift-service (drift, finder, scorecards, rightsizing)

- **Owns:** a watch list of every live resource (synced from state), 64 shards per region with one Temporal loop per shard and tier (hot 15 min, warm 1 h, cold 24 h), paged `:observe-list` reads through the platform (P-09) mapped by each type's `observe` block (P-06), findings (one open per resource; revert, accept or ignore a field; **never automatic**, decision 10), the unmanaged-infrastructure finder ("Assign to team"), compliance scorecards from rule packs (decision 26), rightsizing.
- **Events:** `drift.events`. Port 18084. Branches: phase-5 #12 → `finder-and-monitoring` #32, scorecards #21, scan-load draft #53 (the 10 M run not done). See HANDOFF.md for the latest heads.

## console-cost-service

- **Owns:** spend per resource, account and cost centre from platform price lists (P-22), money in micros (long), days closed by a day book, budgets with one alert per threshold per month (`cost.events`), `POST /v1/budgets:check` (deny `budget.exceeded`, warn `budget.forecast-over`, used by the BFF's preview, fail open after 2 s), forecasts, chargeback published once per account-month with a sha256 through `FinanceExportPort`.
- **IAM:** `cost:list`, `cost:export`, `budgets:*` (decision 25). Port 18086. Branch `console-cost-service--phase-7` (#28).

## console-web (the UI)

- **Owns:** the AWS-like console: header with services menu, global search, account and region switcher, user menu; per-service landing pages, virtualised resource lists with property filters and saved views, detail pages with tabs (details, activity, drift, lineage, tags, cost, permissions), the schema-driven create wizard, Actions menus from workflow verbs, job and approval views, IAM pages, cost explorer, drift and scorecards, phase 9 extras (copilot, map, executive home, blueprints, time travel).
- **Stack:** React 19, Vite, TypeScript, Tailwind v4, npm workspaces: `packages/ui` (Atlas verbatim), `packages/console-kit`, `console/`. Mock mode (`npm run dev:mock`) or live against the BFF. Playwright journeys with axe checks. See `design-language.md` and `../services/web.md`.
- **Branches:** stacked phase-0 #3 → phase-1 #5 → 3a #9 → 3b #13 → phase-4 #37 → phase-5 #41 → phase-7 and the rest of phase 9 on draft #54; the phase 9 PRs #23, #31, #36, #40, #43 sit beside phase 4.

## console-sdk

- Generated clients and a CLI over the BFF's public API (phase 9), so teams can script the console. README in `services/README-console-sdk.md`.

## console-e2e (whole-system run)

- One docker compose with Mongo, Kafka, OpenSearch, OPA, Temporal, platform-fake-app and every service in real profiles with real service tokens; `services.lock` pins each branch; `./e2e.sh`; a Playwright journey (sign in, create a VM, search, cost, inject and revert drift, delete). It only runs in GitHub Actions (no Docker in the build containers). Draft PR #55 found F-1..F-15. Rule: never change service code from e2e.

## The hub (console-root)

- No code. Contracts (`contracts/console/*`, `contracts/platform/openapi-target.yaml`, `asyncapi-target.yaml`, the wfrn spec), decisions 1 to 35, type overlays, rule packs, blueprints, and the platform-changes plan P-01..P-23. Checked by `npm run check`. Open hub PRs are merged in order on `console-root--stack` (draft #30, `MERGE-ORDER.md`). See `hub/` and `../services/hub.md`.
