# Customer console: the hub

This branch, `console-root`, is the hub of the customer console work: an AWS-console-like UI for
the firm's internal teams, plus the backend services behind it, built on top of the existing
infrastructure automation platform. It has no code of its own. It holds what every service
shares: the decisions, the branch map, the contracts between services, and the plan for the
platform changes the console needs.

- Plan: [Customer console plan](https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC) (the full
  design: personas, wfrn, the dynamic console, services, scale, security, roadmap, risks).
- Platform changes: [platform-changes/README.md](platform-changes/README.md), a plan only.
- Status: phase 0 in progress, 2026-10-23. All ten decisions are answered, and the phase 0
  decisions below are taken. On this branch the platform's target contracts
  (`contracts/platform/`), the console's own event catalog and the five service drafts
  (`contracts/`), the wfrn specification, fixtures and JavaScript reference, the schemas, the type
  overlays and the global region, the contract checks (`npm run check`) and the nightly platform
  contract check have landed. `console-libs` and `console-web` are being set up in parallel.
  Revised the same day after an independent review against the platform at `c9529d5`: P-18 to
  P-21, and a phase 9 of features beyond the AWS console. The console is built against the
  platform as it will be after all twenty-two changes (P-22, readable price lists, was added
  with the cost service, decision 25).

## Building against the target platform

Decided 2026-10-23: the console assumes every change in `platform-changes/` (P-01 to P-22) is
rolled out, and every service is built against the platform's API as it will be after them. There
are no interim adapters and no pilot limits. Until the platform team ships the changes, the
services run against `platform-fake`, an in-memory implementation of the target contract
(`platform-changes/README.md`, section 11).

## The rules of this repository layout

1. **The platform is not touched.** No branch of the console work changes the platform's `main`,
   its code, content, build or docs (decided 2026-10-23). What the platform must change is written
   up in `platform-changes/` for the platform team, and the console is built as if every change
   has shipped (that README, section 11).
2. **One branch per service, each shaped like its own repository.** Each service will become its
   own repository. Until then, each lives on its own branch of this repository, created as an
   orphan branch: its own history, its own build at the branch root, its own CI workflow, and no
   platform files. Moving a service to its own repository later is
   `git push git@github.com:<org>/<repo>.git <branch>:main`, with its history intact.
3. **This branch is the hub.** Anything that more than one service needs to agree on lives here:
   decisions, contracts, the wfrn fixtures, the type overlays, the platform-changes plan. A
   service never copies a contract by hand; it pins a commit of this branch (below).
4. **Reviews.** Work happens on short-lived branches cut from a service branch, named
   `<service branch>--<topic>` (for example `console-state-service--cursor-paging`), merged into
   the service branch by pull request. Changes to the hub go the same way into `console-root`.
   Nothing is ever merged into `main`.

## Branches

| Branch | Future repository | What it holds | Build | Depends on |
| --- | --- | --- | --- | --- |
| `console-root` | `console-hub` | This README, decisions, `contracts/`, `type-overlays/`, `platform-changes/` | none (docs and schemas; CI validates the schemas) | none |
| `console-libs` | `console-libs` | Gradle convention plugins (`build-logic`), the Java `wfrn` library, `service-kit` (account scope, errors, outbox, idempotent consumer, MongoDB and OpenTelemetry helpers), the platform client typed against `contracts/platform/openapi-target.yaml`, `platform-fake` and `platform-fake-app`; published to GitHub Packages as `com.iaf.console:*` | Gradle | `console-root` contracts |
| `console-bff` | `console-bff` | Sign-in, sessions, type descriptors, page APIs, commands, SSE | Gradle, Spring WebFlux | `console-libs` |
| `console-identity-service` | `console-identity-service` | Accounts, users, groups, roles, policies, access keys, quotas, OPA bundles | Gradle, Spring WebFlux | `console-libs` |
| `console-state-service` | `console-state-service` | The event-sourced resource inventory by wfrn, desired against observed | Gradle, Spring WebFlux | `console-libs` |
| `console-drift-service` | `console-drift-service` | Drift loops on Temporal, comparison rules, revert, accept, discovery | Gradle, Spring WebFlux, Temporal SDK | `console-libs` |
| `console-search-indexer` | `console-search-indexer` | Kafka to OpenSearch indexing, the search API | Gradle, Spring WebFlux | `console-libs` |
| `console-cost-service` | `console-cost-service` | The cost ledger priced from the platform's price lists, the Cost Explorer's queries, budgets with forecasts, alerts and the create block, monthly chargeback to finance (decision 25) | Gradle, Spring WebFlux | `console-libs` |
| `console-web` | `console-web` | The console UI: React 19, Vite, TypeScript, Tailwind v4, Atlas; the TypeScript `wfrn` package | npm | `console-root` contracts |

Every service branch has the same shape: the platform's hexagonal, CQRS layout
(`com.iaf.console.<service>.<slice>.{adapters,application,domain,ports}`), a demo profile that runs
in memory with no Kafka, OpenSearch or real platform, production adapters behind Spring profiles,
its own MongoDB database, its own Helm chart under `deploy/`, and its own
`.github/workflows/ci.yml`.

## How services share contracts

- `contracts/` on this branch is the source of truth: one OpenAPI file per service API
  (`contracts/openapi/<service>.yaml`), the AsyncAPI file for the console's own Kafka topics
  (`contracts/asyncapi/console-events.yaml`), the platform's target API and events after the
  planned changes (`contracts/platform/openapi-target.yaml`, `contracts/platform/asyncapi-target.yaml`),
  the platform's API as it is today for reference (`contracts/platform/openapi-c9529d5.json`,
  read-only), and the wfrn fixtures (`contracts/wfrn/fixtures.json`) that the Java
  and TypeScript wfrn libraries both run.
- `console-libs` builds its platform client and its fake against a pinned commit of this branch,
  recorded in `console-libs/contracts.lock` (the commit and the sha256 of every contract file it
  reads; `contracts/README.md` shows the file), and publishes them with a version. Every service
  pins the same way. Services depend on a published version, never on a branch.
- A breaking change to a contract is a new version of that API (`/v2`) or a new event type, and
  both versions are served for one release.
- **Nightly platform contract check.** A nightly CI job on this branch fetches `/openapi.json`
  from the platform's `main`, compares it with the target contract for every operation the
  console uses (listed in `contracts/platform/used-operations.txt`), reports which planned
  changes have landed, and fails when something the console uses moves away from the target.
- Consumers de-duplicate platform events on the event's `key`, never its `id`, and console topics
  are partitioned by wfrn.

## Type overlays

The console's per-type hints and drift mappings (`console`, `observe`, `x-console`) live on the
platform's `kind: ResourceType` (P-06), owned by provider teams and published by change request
(P-19). For the sample types, the hub drafts them in `type-overlays/<type>.yaml`, in exactly
P-06's shape, as the input to the platform's sample content (P-15) and to `platform-fake`. A type
with no hints still gets a full console from its schema and workflows; hints only polish it.

## Decisions

| # | Decision | Answer |
| --- | --- | --- |
| 1 | What parity covers | The full AWS console framework for every resource type (account and region switcher, IAM, global search, list, detail, create wizard, actions menu, tags, activity trail, quotas, cost); each service's depth comes from the provider's workflows |
| 2 | Who the customers are | The firm's internal teams; the firm's SSO through OIDC; an account is owned by an identity-provider group and charged to its cost centre |
| 3 | Who owns customer resources | The state service owns the customer view, fed by the platform; the UI shows a pending state until the platform's events land |
| 4 | Regions | One region, `global`, for now, modelled as an ordinary region so more can be added with no hacks |
| 5 | Scale targets (initial) | 10,000 users, 1,000 concurrent, 10 million resources, 1 million per account, 10,000 creations an hour, list p95 under 300 ms, search p95 under 500 ms, every resource checked for drift at least hourly |
| 6 | Events and search | Kafka and OpenSearch, which the firm already runs |
| 7 | The platform | Not touched anywhere; stays on Maven; changes only described in `platform-changes/` |
| 8 | Repositories | Each service its own repository in concept; for now one orphan branch per service here, with this branch as the hub |
| 9 | Look and feel | AWS's layout and interactions, drawn in Atlas visuals with Apple-grade restraint: think Jony Ive, elegant, quiet, precise. The one Atlas exception is `@tanstack/react-virtual` for row virtualisation |
| 10 | Drift remediation | Detect drift and show it with a one-click revert (plus accept and ignore field); never revert automatically |

## Phase 0 decisions

Taken 2026-10-23 while the foundations were built, on top of the ten decisions above.

| # | Decision | Answer |
| --- | --- | --- |
| 11 | How `console-web` gets Atlas and the shared components | It vendors Atlas: `packages/ui` is copied verbatim from the platform at `c9529d5`, and `packages/console-kit` holds the console's forks of SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline and ProblemList, with `x-console` support added. `packages/wfrn` is the TypeScript wfrn library. The web app switches to the published P-14 packages when they exist |
| 12 | What `platform-fake` is | A library in `console-libs` plus a runnable `platform-fake-app`, an in-memory implementation of the target contracts seeded from the `c9529d5` sample content pinned in `contracts/platform/sample-content/` plus P-15's additions (the `global` region, the overlays' `console` and `observe` blocks, `update-vm`) |
| 13 | How the platform client is written | Hand-written reactive code in `console-libs`, typed against `contracts/platform/openapi-target.yaml`, with an abstract contract test suite that runs against the fake in CI and against the real platform when `CONSOLE_TEST_PLATFORM_URL` is set. Code generation is revisited for the service-to-service clients in phase 1 |
| 14 | How a service pins the contracts | By hub commit, in a `contracts.lock` at the service root holding the commit and the sha256 of every contract file it reads (`contracts/README.md`) |
| 15 | How shared code is published | `console-libs` publishes `com.iaf.console:*` to GitHub Packages from merges to `console-libs`; services depend on a published version |
| 16 | The wfrn pattern rules | As settled in `contracts/wfrn/SPEC.md`: `*` matches any run of characters and `?` exactly one, across `:` and `/`, as in IAM; a pattern is judged with a name's rules, except that a segment with a wildcard is checked only for its alphabet and length, a pattern may stop early at a segment that ends in `*`, and the id may be absent when the type segment ends in `*`. The fixtures in `contracts/wfrn/fixtures.json` are the test every wfrn library passes |
| 17 | The shape of the console's own events | The platform's P-01 envelope, the one the service kit's `EventEnvelope`, outbox and Kafka publisher write and every consumer already reads for the platform's feed: `aggregate` and `aggregateId` name the subject, `context` carries `accountId`, `region` and `wfrn`, and the console's fields ride in `data` (`data.version` on every event, `data.state` on resources, `data.action` and `data.snapshot` on identity changes). Type names stay PascalCase (`UserChanged`) so they never collide with the platform's dotted `resource.*` types in a merged stream (`contracts/asyncapi/console-events.yaml`) |
| 18 | Where a console account's platform credential lives | The identity service answers `GET /v1/accounts/{accountId}/platform-credentials` to the console's own services: the token itself in the demo and dev profiles (`source: local`, minted on platform-fake), or the Vault path `console/accounts/{accountId}/platform-service-account` in production (`source: vault`), never in events or the account record. OPA fetches its bundle from `GET /internal/opa/bundles/console-authz.tar.gz` with an ETag |
| 19 | What the BFF answers a refused person | Every 403 but a missing CSRF token carries a `Denial`: the layer that decided (console IAM or the platform), the `<service>:<verb>`, the resource, the reason, the role, policy and statement or scope behind it, whether access may be requested and which role would grant it; the same block greys an action in the descriptors (`ConsoleAction.allowed`, `denied`). A step-up refusal is a 403 with `detail.stepUp`. IAM is passed through at `/console/v1/iam/*` for the session's account with the identity service's schemas unchanged; access requests live at `/console/v1/access-requests`; `/.well-known/jwks.json` publishes the BFF's keys (`contracts/openapi/console-bff.yaml`) |
| 20 | Page sizes, tombstones and job events across the services | Every console API pages 1 to 200 (the plan's cap; the service kit's `CursorPaging` enforces it), 50 by default. A deleted resource's name is a tombstone for 90 days, as the wfrn spec and the plan say. The three job events keep one type each whatever the job: `ProvisioningStarted` keeps the state, `ProvisioningSucceeded` ends a create or update in `active`, a succeeded delete is recorded as `Deleted` alone, `ProvisioningFailed` leaves a create `failed` and returns an update or delete to `active`. The platform's backfill may be run by the `console-internal` preset (`resources:backfill`), so the state service imports the registry without a person |
| 21 | The managed roles and how a service knows the caller's | Four managed roles per account, `viewer`, `operator`, `account-admin` and `auditor` (shown as Viewer, Operator, Admin, Auditor), born with the groups `viewers`, `members` (the owning identity-provider team joins it at sign-in), `owners` (account-admin) and `auditors`. The internal JWT the BFF signs (verified against its JWKS) carries `sub`, `acct`, `region`, `groups`, `roles` (from the membership the identity service serves) and `svc`; every service decides admin-only things (the state service's `reveal=sensitive`, deciding access requests) on `roles` containing `account-admin`, never on a group name |
| 22 | Quota requests are decided explicitly; who sees the access-request queue | `POST …/quota-requests/{requestId}:decide` (internal principal, i.e. the platform team) approves with a granted limit (the ask by default) and a comment, or rejects; an internal `PUT` of a limit at or above an open ask approves it as moot, a lower limit leaves it open. Everyone reads their own access requests; the account's queue needs `access-request:get`/`list`, granted to the managed `account-admin` and `auditor` policies and left out of `viewer` and `operator`. A pending access request expires after 30 days |
| 23 | How a console service calls another as itself | The BFF stays the only issuer of internal JWTs, so every verifier keeps its one JWKS. A service that calls another on its own account (the search indexer reading the state service and the accounts; the drift service next) asks the BFF for a service token at `POST /internal/v1/token`: OAuth 2.0 client credentials (RFC 6749 §4.4) with a JWT client assertion (RFC 7523). In a cluster the assertion is the pod's projected Kubernetes service-account token with audience `console-bff`, verified against the cluster's service-account issuer, so no secret exists; anywhere else (a laptop, CI, a VM) it is a JWT the service signs with its own EC key (`iss` and `sub` its service id, `aud` `console-bff`, a fresh `jti`, at most 5 minutes long), verified against the public keys registered for it. The BFF registers each client with its service account or keys and the audiences it may ask for (the indexer: `console-state-service`, `console-identity-service`), and answers `{ access_token, token_type: Bearer, expires_in }`: a 5-minute ES256 token with `iss` `console-bff`, `aud` only the services asked for (never the shared `console`), `sub` and `svc` the service id and no `acct`, which verifiers already treat as the console-internal principal. The BFF's signing key never leaves it, and there are no static tokens. The endpoint is in-cluster only (the ingress routes `/auth`, `/console` and the JWKS, never `/internal`). The demos keep the dev headers (`contracts/openapi/console-bff.yaml`, `issueServiceToken`) |
| 25 | Where cost, budgets and chargeback live (WP 7.3, 9.3, 9.9) | A service of its own, `console-cost-service` (port 18086, orphan branch), not the BFF: it follows `state.resources` like the indexer, keeps a ledger in its own MongoDB and closes days on a schedule, which is work a backend for frontend should not carry, and at 10 million resources the reports must read rollups, never resources. Prices come from the platform's `kind: PriceList` content, readable through P-22, and each type names its price in an optional `cost` block (P-22; the hub's overlays bind `vm` by `cpu` and `database` by `tier`); until P-22 ships the service reads a bundled copy of the pinned `list-prices.yaml` and overlays. A resource is charged while `active`, `updating` or `deleting`, priced from its last observation when there is one, accrued by the second at `monthly / days in the month`. Budgets are monthly, per account or one tag within it, alert once per threshold (80 and 100 by default) on `cost.events`, and with `blockCreates` turn the create preview's policy into a deny rule `budget.exceeded`. A closed month's chargeback is final, published to finance with a sha256. Accounts gain `costCentre` (identity service). The BFF serves `/console/v1/cost`, `/console/v1/budgets` and `/console/v1/r/{type}/{wfrn}/cost` with IAM actions `cost:list`, `cost:export`, `budgets:list`, `budgets:create`, `budgets:update` and `budgets:delete` (granted to `viewer` and up for the reads, `account-admin` for budget writes, `account-admin` and `auditor` for the export), and fills a preview's missing `cost` from `POST /v1/cost:estimate` (`contracts/openapi/console-cost-service.yaml`) |

## Build order

The same phases as the plan's roadmap, without the work that would have changed the platform
(that work is now the platform team's, in `platform-changes/`). Each line names the branch it
lands on.

| Phase | Work | Branches |
| --- | --- | --- |
| 0 Foundations | Contracts (including the platform's target contract) and wfrn fixtures; convention plugins, wfrn, service-kit, `platform-fake`; the console shell in Atlas; the nightly platform contract check | `console-root`, `console-libs`, `console-web` |
| 1 Sign in and IAM | Accounts, four managed roles per account, OPA bundles, platform service accounts per account; OIDC sign-in, sessions; Request access | `console-identity-service`, `console-bff`, `console-web` |
| 2 Inventory | The state service, fed by the platform's event feed on Kafka with snapshot rebuilds; import of the registry; pending reaper and collision guard; per-account keys for sensitive fields | `console-state-service` |
| 3 Dynamic console | Type descriptors from today's resource types, catalog and overlays; list, detail, create, actions; view as code, command palette, plain-language errors, accessibility checks | `console-bff`, `console-web`, `console-root` (overlays) |
| 4 Edit and delete | Update convention, bulk actions, tags, required tags, blast radius, clone | `console-bff`, `console-web`, `console-state-service` |
| 5 Drift | List reads through the platform's observe API, hot, warm and cold tiers, per-account rate shares, one-click revert | `console-drift-service` |
| 6 Search and scale | Indexer and search; load tests at the initial targets | `console-search-indexer` |
| 7 Parity extras | Activity trail and evidence pack, quotas and quota requests, cost, CLI, notifications, account lifecycle, custom policies | `console-bff`, `console-identity-service`, `console-web`; cost on `console-cost-service` |
| 8 Production | Threat model, pen test, SLO alerts, runbooks, backups and restore drill, cost estimate | every branch |
| 9 Beyond AWS | Copilot, failure explainer, budgets, resource map, executive home, scorecards, blueprints, time travel and the other features in the plan's "Beyond the AWS console" section; off the critical path | mostly `console-bff`, `console-web`; blueprints on `console-state-service` and `console-root` (`kind: Blueprint`); budgets and chargeback on `console-cost-service` |
