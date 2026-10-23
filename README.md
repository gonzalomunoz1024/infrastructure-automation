# Customer console: the hub

This branch, `console-root`, is the hub of the customer console work: an AWS-console-like UI for
the firm's internal teams, plus the backend services behind it, built on top of the existing
infrastructure automation platform. It has no code of its own. It holds what every service
shares: the decisions, the branch map, the contracts between services, and the plan for the
platform changes the console needs.

- Plan: [Customer console plan](https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC) (the full
  design: personas, wfrn, the dynamic console, services, scale, security, roadmap, risks).
- Platform changes: [platform-changes/README.md](platform-changes/README.md), a plan only.
- Status: planning, 2026-10-23. All ten decisions are answered. No service branch has code yet.
  Revised the same day after an independent review against the platform at `c9529d5`: pilot cap,
  go/no-go gate, isolation rules, P-18 to P-21, and a phase 9 of features beyond the AWS console.

## The pilot cap and the go/no-go gate

Until the platform team ships three of the planned changes, the console runs on interim adapters
to today's platform API, and those carry a pilot only:

- **Pilot cap: at most 50,000 resources and 5 accounts in total.** The identity service refuses a
  sixth account and the state service refuses imports past the cap, with a message naming the
  changes that lift it. The reasons are in `platform-changes/README.md`, section 11.
- **Go/no-go.** The console's scale gate (plan, gate 6) cannot pass until P-01 (event feed), P-05
  (registry wfrns, collision-safe keys, paging) and P-09 (observe and list reads) are live on the
  platform. Gate 2 needs each of them to have an owner on the platform team and a target date:

  | Change | Owner (platform team) | Target date | Status |
  | --- | --- | --- | --- |
  | P-01 Event feed | to be named | to be set | planned |
  | P-05 Registry wfrns and paging | to be named | to be set | planned |
  | P-09 Observe and list reads | to be named | to be set | planned |

- **Isolation, whatever the adapter.** Platform reads are not isolated by tenant today (P-18). No
  service passes a platform read through to the browser: every platform response is filtered by
  the account's owner team and every record's account is re-checked before it leaves the service.
  A cross-account read test runs against a real platform in CI.

## The rules of this repository layout

1. **The platform is not touched.** No branch of the console work changes the platform's `main`,
   its code, content, build or docs (decided 2026-10-23). What the platform must change is written
   up in `platform-changes/` for the platform team, and the console works around each missing
   change until it lands (that README, section 11).
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
| `console-libs` | `console-libs` | Gradle convention plugins (`build-logic`), the Java `wfrn` library, `service-kit` (account scope, errors, outbox, idempotent consumer, MongoDB and OpenTelemetry helpers), clients generated from `contracts/`; published to GitHub Packages as `com.iaf.console:*` | Gradle | `console-root` contracts |
| `console-bff` | `console-bff` | Sign-in, sessions, type descriptors, page APIs, commands, SSE | Gradle, Spring WebFlux | `console-libs` |
| `console-identity-service` | `console-identity-service` | Accounts, users, groups, roles, policies, access keys, quotas, OPA bundles | Gradle, Spring WebFlux | `console-libs` |
| `console-state-service` | `console-state-service` | The event-sourced resource inventory by wfrn, desired against observed | Gradle, Spring WebFlux | `console-libs` |
| `console-drift-service` | `console-drift-service` | Drift loops on Temporal, comparison rules, revert, accept, discovery | Gradle, Spring WebFlux, Temporal SDK | `console-libs` |
| `console-search-indexer` | `console-search-indexer` | Kafka to OpenSearch indexing, the search API | Gradle, Spring WebFlux | `console-libs` |
| `console-web` | `console-web` | The console UI: React 19, Vite, TypeScript, Tailwind v4, Atlas; the TypeScript `wfrn` package | npm | `console-root` contracts |

Every service branch has the same shape: the platform's hexagonal, CQRS layout
(`com.iaf.console.<service>.<slice>.{adapters,application,domain,ports}`), a demo profile that runs
in memory with no Kafka, OpenSearch or real platform, production adapters behind Spring profiles,
its own MongoDB database, its own Helm chart under `deploy/`, and its own
`.github/workflows/ci.yml`.

## How services share contracts

- `contracts/` on this branch is the source of truth: one OpenAPI file per service API
  (`contracts/openapi/<service>.yaml`), the AsyncAPI file for the console's own Kafka topics
  (`contracts/asyncapi/console-events.yaml`), the platform's API as the console uses it today
  (`contracts/platform/openapi-c9529d5.json`, captured from the platform's `/openapi.json` at
  that commit, read-only), and the wfrn fixtures (`contracts/wfrn/fixtures.json`) that the Java
  and TypeScript wfrn libraries both run.
- `console-libs` generates its clients from a pinned commit of this branch, recorded in
  `console-libs/contracts.lock`, and publishes them with a version. Services depend on a
  published version, never on a branch.
- A breaking change to a contract is a new version of that API (`/v2`) or a new event type, and
  both versions are served for one release.
- **Nightly platform contract check.** The platform keeps changing while the console pins
  `c9529d5`. A nightly CI job on this branch fetches `/openapi.json` from the platform's `main`,
  compares it with `contracts/platform/openapi-c9529d5.json` for every operation the console uses
  (listed in `contracts/platform/used-operations.txt`), and fails on a breaking change, so the
  console team hears about it before an adapter breaks.
- Consumers de-duplicate platform events on the event's `key`, never its `id`, and console topics
  are partitioned by wfrn.

## Type overlays (until the platform's P-06 lands)

The console's per-type hints and drift mappings (`console`, `observe`, `x-console`) belong on the
platform's `kind: ResourceType`, but the platform is not changed. Until it is, they live here in
`type-overlays/<type>.yaml`, in exactly the shape P-06 gives them, so moving them into the
platform later is a copy. A type with no overlay still gets a full console from its schema and
workflows; overlays only polish it.

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

## Build order

The same phases as the plan's roadmap, without the work that would have changed the platform
(that work is now the platform team's, in `platform-changes/`). Each line names the branch it
lands on.

| Phase | Work | Branches |
| --- | --- | --- |
| 0 Foundations | Contracts and wfrn fixtures; convention plugins, wfrn, service-kit; the console shell in Atlas; interim adapters with isolation filters; the nightly platform contract check | `console-root`, `console-libs`, `console-web` |
| 1 Sign in and IAM | Accounts, four managed roles per account, OPA bundles, platform service accounts per account; OIDC sign-in, sessions; Request access | `console-identity-service`, `console-bff`, `console-web` |
| 2 Inventory | The state service, fed by today's platform job stream and registry reads (interim adapters), under the pilot cap; pending reaper and collision guard; per-account keys for sensitive fields | `console-state-service` |
| 3 Dynamic console | Type descriptors from today's resource types, catalog and overlays; list, detail, create, actions; view as code, command palette, plain-language errors, accessibility checks | `console-bff`, `console-web`, `console-root` (overlays) |
| 4 Edit and delete | Update convention, bulk actions, tags, required tags, blast radius, clone | `console-bff`, `console-web`, `console-state-service` |
| 5 Drift | Pilot only until P-09: read-only `observe-<type>` workflows run as jobs, daily, under half the team's job quota. Then list reads and hot, warm and cold tiers | `console-drift-service` |
| 6 Search and scale | Indexer and search; load tests at the initial targets, which cannot start until P-01, P-05 and P-09 are live | `console-search-indexer` |
| 7 Parity extras | Activity trail and evidence pack, quotas and quota requests, cost, CLI, notifications, account lifecycle, custom policies | `console-bff`, `console-identity-service`, `console-web` |
| 8 Production | Threat model, pen test, SLO alerts, runbooks, backups and restore drill, cost estimate | every branch |
| 9 Beyond AWS | Copilot, failure explainer, budgets, resource map, executive home, scorecards, blueprints, time travel and the other features in the plan's "Beyond the AWS console" section; off the critical path | mostly `console-bff`, `console-web`; blueprints on `console-state-service` and `console-root` (`kind: Blueprint`) |
