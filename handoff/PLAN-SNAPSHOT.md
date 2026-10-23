
# Customer console plan

2026-10-23 · Gonzalo


## Summary

We build a customer console that looks and behaves like the AWS Management Console, drawn entirely from the platform's config: every `kind: ResourceType` becomes a console "service" page, and every published workflow whose `noun` is that type becomes a button. A new provider resource type appears in the console the moment it is published, with no UI release.

It adds five Gradle services beside today's Maven platform, all Java 21, Spring WebFlux, CQRS and hexagonal, on MongoDB:

- **console-bff**: login, sessions, and the one API the console calls.
- **identity-service**: accounts, users, groups, roles and IAM-style policies.
- **state-service**: the inventory of every customer resource, keyed by its wfrn, with desired and observed state, event sourced.
- **drift-service**: reconciles each resource against the provider's truth and offers revert or accept.
- **search-indexer**: feeds the unified search and the filter bar across millions of resources.

The platform keeps running every change as a Temporal workflow. The console never calls a provider directly; it asks the platform to run a workflow, and the state service records what that run did.

Three bets carry the plan:

1. **Parity means the console framework, not every AWS service.** We copy the AWS console's shell and its cross-cutting features (account and region, IAM, services menu, search, list, detail, create wizard, actions, tags, activity trail, quotas, cost). How deep each service goes is up to its provider's workflows.
2. **Defaults first, hints second.** A resource type with no console hints still gets a usable list, detail page and create form. Optional `x-console` hints make it look as polished as a hand-built page.
3. **One region now, many later, with no hacks.** Only the global region ships (decided 2026-10-23), but it is an ordinary region: the wfrn, every key, topic and URL carries it, so each later region's state and drift services can run on their own without a data migration.

All ten open decisions were answered on 2026-10-23 and are recorded at the end. The platform is not touched: what it should change is a plan only, on the [console-root](https://github.com/gonzalomunoz1024/infrastructure-automation/tree/console-root) hub branch, and each service has its own branch.

**Revised 2026-10-23****.** An independent review checked the plan against the platform code at `c9529d5`. It found that platform reads are not isolated by tenant and that registry ids can collide and silently merge. The plan now adds isolation rules, a collision guard, list-based tiered drift, four new platform changes (P-18 to P-21), sections on account lifecycle, data protection, operations and accessibility, and a new phase 9 of features beyond the AWS console. **The console assumes every planned platform change (P-01 to P-21) is rolled out** (decided 2026-10-23): each service is built against the platform's target API from the start, with no interim adapters and no pilot limits.


## What the platform already gives us

The platform (main at `c9529d5`) already holds most of what a console needs to be dynamic; the gaps are tenancy, a global id, desired state and a way to read a resource's actual state.

| Already there | Where | What the console does with it |
|---|---|---|
| `kind: ResourceType` with a JSON Schema, `states`, `producedBy`, `visibility` | `resource-types/*.yaml`, `GET /resource-types` | One console service page per type; schema drives list columns, detail fields and the create form |
| Workflows with `metadata.noun` and `verb` | `workflows/**`, `GET /catalog` | `noun: vm, verb: create` is the Create button; every other verb is an entry in the Actions menu |
| `api.input` schemas with `title`, `enum`, `default`, `pattern`, `x-resource` | each workflow | The create and action forms, including resource pickers and "create new image" inline (`orCreateWith`) |
| `:preview` (steps, affected resources, cost, policy) | `POST {path}:preview` | The "Review and create" step of every wizard |
| Jobs with steps, approvals, batches, SSE and WebSocket | `/jobs`, `/ws` | The console's request tracker and the "creating..." state |
| Registry with lineage (`builtFrom`, dependents, retire) | `/resources`, `/resources/{id}/lineage` | The Relationships tab and "cannot delete, has dependents" |
| Provider read actions (`effect: read`, such as `vm.get`, `vm.list`) | `providers/**` | What drift detection and discovery call |
| OIDC, service accounts with scopes, OPA policy port, audit hash chain | `identity`, `audit` slices | Login, access keys, policy evaluation, activity trail |
| Atlas kit (`packages/ui`) and `SchemaForm`, `ResourcePicker`, `LineageGraph` | `packages/ui`, `frontend/src/components` | Reused and moved into a shared package |

**Gaps ****in today's platform, each closed by a planned platform change the console assumes****:**

- **No tenancy.** The platform knows teams, not customer accounts. Resource ids are provider ids such as `vm-1042`, which can collide across providers and regions.
- **No desired state.** The registry stores what a `register` step wrote. It has no record of what the customer asked for, so nothing can be compared.
- **No link from a type to its read action.** `vm.get` exists, but nothing says it is how to observe a `vm`.
- **No update verb convention.** Editing a resource needs an `update` workflow; none of the samples has one yet.
- **No durable event feed.** `EventBus` is in process (a Reactor sink plus listeners), so other services cannot follow jobs or registry changes reliably.
- **No paging.** `GET /resources` returns whole arrays; the console needs cursors, server-side filters and sorting.
- **No regions.** Providers have `endpoints` per environment (dev, test, prod). An AWS-style region needs its own definition.


## Personas, accounts and IAM

Customers sign in through the firm's OIDC identity provider, land in an account, and are allowed or denied by IAM-style policies whose actions come straight from the workflows.

**Personas**

| Persona | Does | Today's equivalent |
|---|---|---|
| Customer user | Creates, inspects and manages resources in accounts they can reach | none (new) |
| Account admin | Manages users, groups, roles, policies, quotas and tags in an account | platform admin, but per account |
| Organization admin | Creates accounts, sets organization-wide guardrails, sees cost across accounts | none (new) |
| Read-only auditor | Reads inventory, drift and the activity trail | `jobs:read` scope |
| Automation (CI, scripts) | Calls the same APIs with access keys | service accounts (`iaf_` tokens) |
| Provider engineer | Builds types and workflows in the existing platform UI | unchanged |

**Tenancy model**: the customers are the firm's internal teams (decided 2026-10-23). One organization (the firm), then Accounts, then Regions. An account is a team's workspace: it has a 12-digit id (as in AWS), a name, a cost centre, and one owning team, which is an existing identity-provider group (the groups claim the platform already reads). Members of that group get the account's default role; other access is granted with roles. A team may own several accounts (for example payments-dev and payments-prod). Every resource, policy, quota and event carries `accountId`. An account is the unit of isolation, chargeback to its cost centre, and quota. No self sign-up and no external identity provider: an organization admin creates accounts, or a team requests one through an approval workflow.

**Login**

- Enterprise single sign-on through the firm's OIDC identity provider, the same issuer the platform's oidc profile trusts, with its own client registration for the console. The authorization code flow with PKCE is run by the BFF. The browser holds only an `HttpOnly`, `Secure`, `SameSite=Lax` session cookie; access and refresh tokens stay in the BFF's session store.
- MFA and password policy belong to the identity provider. The console asks for a fresh login (step-up, `max_age`) before destructive actions when the policy says so.
- Sign in shows the account picker; "Switch role" assumes a role in another account for a time-limited session, and the header shows the role in colour, as AWS does.
- Access keys for scripts are service accounts scoped to one account, shown once, rotated, with last-used time.

**IAM**

- Entities: users (from the identity provider), groups, roles (with a trust policy saying who may assume them) and managed or inline policies.
- A policy is a JSON document in AWS's shape:

```json
{
  "Version": "2026-10-23",
  "Statement": [
    { "Effect": "Allow", "Action": ["compute:vm:Get", "compute:vm:List", "compute:vm:Restart"],
      "Resource": "wfrn:wf:compute:*:123456789012:vm/*",
      "Condition": { "StringEquals": { "wf:ResourceTag/env": "dev" } } }
  ]
}
```

- Actions are generated, never typed into code: `<service>:<type>:<Verb>` from each workflow's `noun` and `verb`, plus `Get`, `List` and `Tag` for every type. A new workflow adds its action to the policy editor's list automatically.
- Evaluation is AWS's: explicit deny wins, then an allow from an identity policy, a permission boundary and the organization's guardrails must all agree, default deny. The identity service compiles policies into OPA bundles; OPA (the firm's engine) makes every decision, and the BFF caches allow decisions for 60 seconds per principal and action.
- A policy simulator page answers "can this role do this action on this wfrn, and which statement decided?"

**Managed roles first.** Phase 1 ships four managed roles per account (Viewer, Operator, Admin, Auditor), compiled to OPA like any policy. The AWS-shaped policy editor, the simulator and permission boundaries follow in phase 7 (WP 7.8). The model does not change, only the order, which takes most of the risk out of phase 1. A denied action is never a dead end: it shows which layer refused it (console IAM or the platform's own RBAC on the account's service account) and offers Request access, an approval sent to the account admin. The BFF's platform admin credential, needed to create service accounts, lives in the vault, is used by one code path only, and every use is logged to the trail.

**Bridge to the platform**: the platform keeps judging teams and RBAC. The BFF calls it with one service account per account, whose teams are the account's owning team (`console-<accountId>`). Through platform change P-02, the BFF passes the real user in a signed `X-On-Behalf-Of` header, which the platform writes as the job's `requester` and into audit evidence. P-02 is one of the platform changes the console assumes are rolled out, so every job names the real person from the first release.


## wfrn: the resource name

Every resource gets one immutable name the platform mints at request time, before the provider is called, in the same six-part shape as an ARN:

```
wfrn:<partition>:<service>:<region>:<account>:<type>/<id>
wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya
```

| Segment | Rule | Example |
|---|---|---|
| `partition` | Fixed per installation; a sovereign or air-gapped install gets its own | `wf` |
| `service` | New `metadata.service` on the ResourceType, the console's service grouping; defaults to the owner team without `team-` | `compute` |
| `region` | A region id from the new `kind: Region` file. Only global exists for now (decided 2026-10-23); it stays a real region when others are added, so these names never change. Empty for account-level things such as IAM roles | `global` |
| `account` | 12-digit account id; empty only for organization-level things | `123456789012` |
| `type` | The ResourceType's `metadata.name` | `vm` |
| `id` | Type prefix plus a lowercase ULID (time ordered, 26 characters), minted by the state service | `vm-01jbx7q2m4k9d3c8f5r6t1w0ya` |

Rules:

- Lowercase `[a-z0-9-]` in every segment except the id path, which may hold `/` for nested resources (`bucket/logs/object`). At most 1,024 characters.
- The provider's own id (`vm-1042` in vSphere) is kept as `providerId` and is never part of the wfrn, so a provider re-creating its object, or two providers using the same id, never breaks a name.
- Names are never reused, even after deletion; the ULID makes collisions impossible in practice.
- Wildcards `*` and `?` are allowed only in policies and search, never in a stored name.

Resolution:

- `GET /wfrn/{wfrn}` on the state service answers the resource, or `410 Gone` with its tombstone if it was deleted.
- The BFF routes by the `region` segment to that region's state service, so the name alone finds the cell.
- One parser and formatter in two twins, `services/libs/wfrn` (Java) and `packages/wfrn` (TypeScript), judged by one shared fixture file in CI, the same way `workflow-checks` keeps its Java and TypeScript twins honest.
- Console URLs carry the wfrn: `/console/compute/vm/{wfrn}`, so a link is shareable and opens in the right account and region.


## The dynamic console

The console has no page that knows what a VM is. At sign-in it loads a list of type descriptors from the BFF and renders every service page, form, table and action from them; publishing a type or workflow pushes a new descriptor to open consoles within seconds.

**The type descriptor.** The BFF composes one per resource type, per account and region, from four sources: the ResourceType, the published workflows whose `noun` is the type, the provider's read actions, and what the caller's policies allow. Disallowed actions stay visible but greyed, with the reason, as AWS does.

```ts
type ConsoleType = {
  key: 'compute/vm'; service: 'compute'; title: 'Virtual machines'; icon?: string; etag: string
  schema: JsonSchema                      // the type's schema, with x-console hints
  lifecycle: Record<string, 'pending' | 'active' | 'updating' | 'deleting' | 'deleted' | 'failed'>
  list: { columns: Column[]; filters: Filter[]; defaultSort: Sort }
  detail: { summary: Field[]; tabs: Tab[] }
  create?: { workflow: 'create-vm'; input: JsonSchema; preview: boolean; steps?: WizardStep[] }
  update?: { workflow: string; mutable: string[] }
  actions: { verb; title; workflow; input: JsonSchema; destructive: boolean; bulk: boolean; allowed: boolean; reason?: string }[]
  observe?: { supported: boolean; interval: string }
}
```

**Defaults when a type has no hints**, so a brand-new type is usable on day one:

- List columns: name, status, wfrn (copy button), the type's `required` scalar fields, owner, created. Filters: every `enum` field, status, tags, created.
- Detail: a summary card of every scalar field; tabs for Configuration (desired against observed), Drift, Activity (jobs), Relationships (lineage), Tags, Permissions, JSON.
- Create: the `producedBy` workflow's `api.input`, one wizard step per top-level object, then "Review and create" built from `:preview` (steps, affected resources, monthly cost, policy decision).
- Actions: every other verb of the type, in an Actions menu. `delete`, any verb tagged `destructive`, and any with `approval.required` get the type-the-name confirmation.
- Status mapping: the type's `states` mapped by name (`building` to pending, `ready` to active, `retired` to deleted); unknown names show as neutral badges.

**Hints, when the owner wants polish.** Optional blocks on the ResourceType and `x-console` keywords inside the JSON Schema:

```yaml
kind: ResourceType
metadata: { name: vm, plural: vms, service: compute, owner: team-compute }
console:
  title: Virtual machines
  icon: server
  list: { columns: [name, status, cpu, memoryGb, environment, created], defaultSort: -created }
  detail: { tabs: [configuration, drift, activity, relationships, tags, monitoring] }
  lifecycle: { building: pending, ready: active, deprecated: active, retired: deleted }
observe:                           # how drift detection reads the truth
  provider: vsphere
  action: vm.get
  input: { id: "{{ $resource.providerId }}" }
  map: { cpu: "{{ $json.cpu }}", memoryGb: "{{ $json.memoryGb }}", powerState: "{{ $json.powerState }}" }
  ignore: [owner]
  interval: 1h
  discover: { action: vm.list, id: "{{ $item.id }}" }
schema:
  properties:
    memoryGb: { type: integer, x-console: { format: gibibytes, column: true, mutable: true } }
```

`x-console` keywords: `format` (bytes, gibibytes, duration, percent, cost, date, wfrn, code), `column`, `searchable`, `sensitive` (masked, never indexed), `mutable` (editable through the `update` workflow), `group` (a section on forms and detail pages), `help` (an info panel link), `widget` (picker, slider, code, key-value). Both twins of the checks validate the hints, so a typo fails CI, not the console.

**Console features that copy AWS** (framework parity, each built once and applied to every type):

| Feature | AWS equivalent | Built from |
|---|---|---|
| Header: services menu, unified search, account and region pickers, role colour, notifications bell, help | Console header | descriptors, search-indexer, identity-service |
| Console home: recently visited, favourites, health, cost widget, "Build a solution" | Console Home | BFF preferences, cost from price lists |
| Service landing page per service | EC2 dashboard | descriptor counts per lifecycle state |
| Resource list: property filter bar, saved views, column chooser, page size, bulk select, CSV export | Cloudscape table | state-service query API |
| Create wizard with review and cost | Launch instance | create workflow, `:preview` |
| Detail page with tabs and Actions menu | Instance detail | descriptor tabs |
| Edit settings | Modify instance | `update` workflow, desired state diff |
| Tag editor and resource groups across types | Tag Editor, Resource Groups | state-service tags index |
| Activity trail with every call, who, wfrn, decision | CloudTrail | BFF audit plus platform evidence |
| Drift and compliance per resource and per account | AWS Config | drift-service |
| Requests in flight with live steps | CloudFormation events | platform jobs, SSE |
| Service quotas and requests to raise them | Service Quotas | identity-service |
| Cost by account, service and tag | Cost Explorer | price lists, inventory |
| Shell with a CLI already signed in | CloudShell | later phase, the CLI generated from OpenAPI |

**Massive-scale UI rules**: server-side filtering, sorting and cursor paging only, never skip and limit; virtualised rows past 100; counts shown as "1,000+" when exact counting would be slow; route-level code splitting; descriptors cached by ETag and persisted, so a cold start renders the shell in under one second; live updates arrive as row patches over one SSE stream per tab, not refetches.

**Front-end shape**: a new app on its own branch, `console``-web` (React 19, Vite, TypeScript, Tailwind v4, a hand-rolled hash router, Atlas). It copies AWS's layout and interactions, drawn in Atlas visuals with Apple-grade restraint: think Jony Ive, so it should feel elegant, quiet and precise, with generous whitespace, one accent colour, HIG type, and motion only where it explains something (decided 2026-10-23). It imports `SchemaForm`, `ResourcePicker` and `LineageGraph` from the shared packages of platform change P-14 rather than forking them. The one third-party UI library is `@tanstack/react-virtual` for row virtualisation (approved 2026-10-23).


## Backend services

Five new services sit between the console and the platform; each is one Spring WebFlux app with its own MongoDB database, written in the platform's style (vertical slices, `adapters/application/domain/ports`, `Commands` and `Queries` ports, pure static rule classes). They talk to each other over HTTP for reads and over a durable event log for facts.

The BFF is the only door for the browser; every change goes through a platform workflow, and the state, drift and search services learn what happened from the event log. Each new service has its own MongoDB database; drift reads providers through the platform's provider-call API.


### console-bff

The only service the browser talks to. It owns:

- The OIDC login, the session store (MongoDB with a TTL index, so sessions survive a BFF restart), CSRF tokens and step-up.
- `GET /console/v1/types` and `/types/{key}`: the type descriptors, composed and cached per account, region and policy version.
- Aggregated page APIs, one call per screen: `GET /console/v1/r/{type}?filter=&sort=&cursor=` (list), `GET /console/v1/r/{wfrn}` (detail with drift summary, last job and permissions in one answer).
- Commands: `POST /console/v1/r/{type}` (create), `PATCH /console/v1/r/{wfrn}` (update), `POST /console/v1/r/{wfrn}:{verb}` (action), each with `:preview` and `:batch` forms. It checks IAM, mints the wfrn through the state service, then starts the workflow on the platform with an `Idempotency-Key` derived from the request.
- `GET /console/v1/events` (SSE): row patches, job progress, drift findings and notifications for what the tab is showing.
- User preferences: favourites, recent, saved views, column choices, theme.
- The console's own audit record of every call (principal, account, action, wfrn, decision, correlation id).


### identity-service

Accounts, organizations, users (shadowed from the identity provider), groups, roles, trust policies, managed and inline policies, permission boundaries, organization guardrails, access keys, quotas and quota requests. It compiles policies into OPA bundles and serves `POST /authorize` (batch, so a list page asks once for 50 rows). Changes are versioned, and the version is part of every cache key.


### state-service

The system of record for customer resources. Each resource is an aggregate keyed by wfrn:

```ts
type ResourceState = {
  wfrn; accountId; region; service; type; name; providerId?; tags: Record<string, string>
  desired: { spec: object; generation: number; requestedBy; requestedAt }
  observed?: { spec: object; observedGeneration: number; observedAt; source: 'job' | 'observe' | 'event' }
  lifecycle: 'pending' | 'active' | 'updating' | 'deleting' | 'deleted' | 'failed'
  drift: { status: 'in-sync' | 'drifted' | 'missing' | 'unknown'; checkedAt?; findings: number }
  operation?: { jobId; verb; startedAt }      // the change in flight, at most one
  relationships: { builtFrom: string[]; dependents: string[] }
  version: number                               // optimistic concurrency
}
```

- **Event sourced.** Every change is an event appended to `resource_events` (`ResourceRequested`, `ProvisioningStarted`, `ProvisioningSucceeded`, `ProvisioningFailed`, `UpdateRequested`, `Observed`, `DriftDetected`, `DriftResolved`, `Tagged`, `DeleteRequested`, `Deleted`, `Imported`). The `resources` collection is the current-state projection, written in the same MongoDB transaction as the event, so reads never lag their own writes.
- **Desired against observed**, in the Kubernetes style: `generation` goes up on each request, `observedGeneration` catches up when a job or an observation confirms it. The detail page's Configuration tab is that diff.
- **One change at a time** per resource: a second update while `operation` is set is 409, with a link to the running job. Bulk actions queue per resource instead.
- **Fed by the platform.** A job's outcome reaches the state service as an event; the `register` step's attributes become `observed.spec`, and `providerId` is set from the registered id.
- **Queries** with cursor paging and server-side filters by type, lifecycle, drift, tags and indexed fields; `GET /wfrn/{wfrn}`; history (`/wfrn/{wfrn}/events`), which also powers time travel on the detail page ("what did this look like on Tuesday").
- Deletion leaves a tombstone for 90 days, so names resolve with 410 and the trail stays readable.

**Pending reaper and collision guard.** Minting a wfrn and starting the workflow are two steps, so either can fail alone. A reaper checks every request that is still pending 5 minutes after it was made with no matching platform job, and every job with no outcome event after its timeout, against the platform, and moves the resource to failed or active. A job is matched to its registry record through the record's `createdBy.jobId`. The collision guard refuses to bind a registry id that is already bound to another live wfrn and raises an alert, because today's registry overwrites on a collision (P-05). The Create button is keyed off the type's `producedBy` workflow, not the verb, since not every producer is called create (an image is built).


### drift-service

Compares each resource's desired state with the provider's truth, using the type's `observe` block.

1. **Schedule.** A Temporal workflow per shard (wfrn hash modulo 64, per region) wakes on its tier's interval and continues as new, so a loop never grows without bound. Three tiers: hot (changed in the last 24 hours, tagged prod, or drifted before) every 15 minutes, warm hourly, cold daily; a change event from a provider moves a resource to hot. The type's `interval` sets the warm tier. Temporal is already in the stack, so no new scheduler is needed.
2. **Read.** It reads a page of up to 500 resources per provider list call (P-09's `observe-list`), and uses a single read only to verify a revert or follow a provider's change event. Every call goes through the platform's provider-call API, never directly, so credentials, rate limits, SLO telemetry and recorded samples all keep working. It throttles to the account's share of the provider's declared rate, so one large account cannot starve the others, and backs off on `rate_limited`.
3. **Compare.** A pure `DriftRules` class normalises both sides (types, defaults, units, order of lists), drops `ignore` fields and emits findings: field, desired, observed, severity.
4. **Record.** It sends `Observed` and `DriftDetected` or `DriftResolved` to the state service. A read that returns `not_found` marks the resource `missing`.
5. **Act.** The console offers "Revert" (run the `update` workflow with the desired spec), "Accept" (make the observed spec the desired one, an audited event) or "Ignore field". Revert is one click on the finding; there is no automatic revert (decided 2026-10-23).
6. **Discover.** The optional `discover` action lists what the provider has; anything not in the inventory shows on an "Unmanaged resources" page with an "Import" button that mints a wfrn and an `Imported` event.

Providers that can push changes later send webhooks to a drift endpoint, which triggers an immediate check of that one resource.


### search-indexer

Follows the resource event stream and keeps an OpenSearch index per region: every searchable field, tags, name, wfrn, lifecycle and drift status, with `accountId` on every document and a filter on every query. It powers the header's unified search (resources, services, docs, recent) and the property filter bar's typeahead. Fields marked `sensitive` are never indexed.


### Events and job tracking

- The platform gets a **transactional outbox**: its `EventBus` keeps working in process, and a new listener writes `job.*`, `resource.registered` and `workflow.published` events to an `outbox` collection that a relay publishes to Kafka. This is platform change P-01, one of the changes the console assumes are rolled out, so the state service consumes it from Kafka from the first release.
- Topics: `platform.jobs`, `platform.content`, `state.resources`, `drift.findings`, `identity.changes`, partitioned by wfrn (by job id for jobs) so ordering holds per resource. Keying by account would put a one-million-resource account on one hot partition.
- Every consumer is idempotent by the event's deterministic `key`, never its `id` (a re-emitted event gets a new id), so replays are safe. A consumer that falls behind the outbox's 7 days rebuilds from P-01's snapshot endpoint.
- The console's Requests page and the in-flight badge on a resource are platform jobs, matched to the resource by the `operation.jobId` the state service holds. Live steps come from the platform's job stream, relayed by the BFF over SSE.

**A create, end to end:** the user fills the wizard; the BFF checks `compute:vm:Create` on the new wfrn; the state service mints the wfrn and records `ResourceRequested` (lifecycle `pending`); the BFF starts `create-vm` on the platform with the wfrn in a hidden input; the job runs on Temporal and its `register` step records the provider's id; the outbox event reaches the state service (`ProvisioningSucceeded`, lifecycle `active`, observed spec set); the indexer updates search; the BFF pushes a row patch, and the list shows the VM as Running without a refresh. A failed job gives `ProvisioningFailed` with the step and error, and the resource shows Failed with a "Retry" that reruns the job.


## Scale, regions, observability and security

These are the initial targets Gonzalo accepted on 2026-10-23. They size the shard keys, indexes, caches and load tests, and will be revised once real usage is measured.

| Measure | Initial target |
|---|---|
| Users | 10,000 |
| Concurrent console users | 1,000 |
| Resources in total | 10 million |
| Resources in one account | 1 million |
| Resource creations | 10,000 an hour at peak |
| List page, p95 | under 300 ms |
| Search, p95 | under 500 ms |
| Drift | every resource checked at least once an hour, read in pages of 500 through provider list calls (about 6 calls a second instead of 2,800 single reads); hot tier every 15 minutes |
| New type visible in open consoles | 10 s after publish (platform change P-19, publishing resource types) |
| Availability, console and BFF | 99.95% (my proposal) |

**Data and scale**

- MongoDB sharded per region. `resources` shard key `{ accountId: 1, type: 1, _id: 1 }` with `_id` the wfrn's ULID, so one account's list of one type lives on few shards and stays in creation order. Very large accounts are split by zone ranges.
- Compound indexes for the default sorts; typed fields promoted for `x-console: { searchable }` so filters hit an index. A filter on an unindexed field goes to OpenSearch.
- Cursor paging everywhere (`after=<sort value, _id>`), page size at most 200, no exact totals past 10,000.
- `resource_events` grows fastest: TTL to cold storage (the artifact store) after 400 days, snapshots every 100 events so a rebuild does not replay a resource's whole life.
- Caches: descriptors (ETag, browser and CDN), policy decisions (60 s, keyed by policy version), the BFF's per-account type list. Redis only if the BFF's in-memory Caffeine caches stop being enough.
- Every service is stateless and scales horizontally; Kafka consumers scale by partition.

**Regions as cells**

- Global plane, one per partition: identity-service, the content (types, workflows, descriptors), the console's static assets.
- Regional cell, one per region: state-service, drift-service, search-indexer, OpenSearch, its MongoDB, its Kafka, and the platform with its Temporal namespace.
- The BFF routes by the wfrn's region, or the region picker for lists. A cell failing affects only its region.
- Phase 1 runs one region, but every record, key and topic already carries the region, so adding one is deployment, not migration.

**Observability**

- OpenTelemetry from the browser (W3C `traceparent` on every fetch) through BFF, state service, platform and Temporal; the platform already continues incoming trace context.
- RED metrics and SLOs per service and per console route; per type: create success rate, time to active, drift rate. Grafana dashboards beside the platform's (`deploy/grafana`).
- Front-end real-user monitoring: web vitals, route timing and JavaScript errors, sent to the BFF.
- Synthetic checks every 5 minutes: sign in, list, create and delete a canary resource in a test account.
- Structured JSON logs with `correlationId`, `accountId` and wfrn; no secret or `sensitive` field in a log, enforced by the existing redaction.

**Security**

- Tokens never reach the browser (BFF token handler). Strict CSP with nonces, `frame-ancestors 'none'`, CSRF token on every state-changing call, `SameSite` cookies.
- Tenant isolation in code: every outbound port that reads tenant data takes `AccountScope` as its first parameter, and an ArchUnit test fails the build if a repository method lacks it. Cross-account integration tests try to read another account's wfrn and must get 404.
- Default deny everywhere; IAM checked in the BFF, and the platform still checks RBAC and OPA policy on its side.
- Input judged against the same JSON Schema in browser and server; the platform's validation stays the final word.
- Credentials stay `vault:` references; the console can pick one but never read a value.
- Step-up login for destructive verbs; approvals keep working through the platform.
- Audit: console events chained into the platform's evidence hash chain, exportable per account.
- Supply chain: SBOM per service, dependency and container scanning in CI, signed images, the existing secret scan.
- A threat model before phase 2, and an external penetration test before general availability.

**Tenant isolation on today's platform**

- Today the platform's registry and job reads ignore the caller's account, and job reads include inputs. Platform change P-18 scopes every read to the caller, and the console relies on it.
- As defence in depth, the BFF still never passes a platform read through to the browser. Every platform response goes through an adapter that filters by the account's owner team and re-checks each record's account against the state service before it leaves the service. Job inputs are shown from the console's own copy of the request, never from a platform read.
- A cross-account read test runs against a real platform in CI, and gate 2 needs it green.

**Account lifecycle and support access**

- Closing an account with live resources is refused until they are deleted or transferred. A transfer re-imports the resource under the new account's wfrn and keeps the old wfrn as an alias that resolves, since the account is part of the name.
- Identity-provider groups are checked nightly: a renamed group is followed by its stable id, a deleted group freezes its accounts and notifies the organization admins.
- Support and break-glass access is time-boxed (at most 4 hours), needs a reason and an approver, and shows in the account's own trail.

**Data protection**

- Fields marked `sensitive` are encrypted per account with envelope keys from the firm's key service. Closing an account destroys its key, which crypto-shreds those fields in every event and backup.
- Sensitive values never go onto Kafka topics or into search, and the activity trail masks them.

**Operations and recovery**

- Targets per cell: RPO 5 minutes, RTO 1 hour, proven by a restore drill (WP 8.3).
- Backups: MongoDB continuous backup per service; OpenSearch indexes are rebuilt from the state service, never restored.
- A tested rebuild job replays a projection from its events, using P-01's snapshot endpoint for anything older than the outbox's 7 days.
- Each service has a named owning team and an on-call rota; Kafka and OpenSearch stay with the firm's teams that run them today. A monthly running-cost estimate per cell is part of WP 8.3.

**Accessibility and plain language**

- Generated pages meet WCAG 2.2 AA: keyboard paths through table, wizard and Actions menu, screen-reader names from schema titles, and visible focus. Automated axe checks run in every Playwright journey, and gate 3 needs them green.
- Provider errors are translated: each error code maps to a plain message and a next step, and the raw detail sits behind Show details.
- Every empty list says what the service is for and offers Create your first one, and each service gets a short getting-started tour generated from its schema, for the junior-engineer persona.


## Repositories and branches

Each service will be its own repository; for now each lives on its own orphan branch of the platform repository, shaped exactly like that future repository, and the platform's `main` is never touched (decided 2026-10-23). The hub branch, [console-root](https://github.com/gonzalomunoz1024/infrastructure-automation/tree/console-root), holds what the services share: decisions, contracts, the wfrn fixtures, the type overlays and the [platform-changes plan](https://github.com/gonzalomunoz1024/infrastructure-automation/blob/console-root/platform-changes/README.md).

| Branch | Future repository | Holds | Build |
|---|---|---|---|
| `console-root` | console-hub | Decisions, `contracts/` (OpenAPI per service, AsyncAPI for console events, the platform API as captured at `c9529d5`, wfrn fixtures), `type-overlays/`, `platform-changes/` | none; CI validates the schemas |
| `console-libs` | console-libs | Gradle convention plugins, Java `wfrn`, `service-kit`, clients generated from the contracts; published to GitHub Packages | Gradle |
| `console-bff` | console-bff | Sign-in, sessions, descriptors, page APIs, commands, SSE | Gradle, WebFlux |
| `console-identity-service` | console-identity-service | Accounts, IAM, OPA bundles, access keys, quotas | Gradle, WebFlux |
| `console-state-service` | console-state-service | Event-sourced inventory by wfrn | Gradle, WebFlux |
| `console-drift-service` | console-drift-service | Drift loops on Temporal, revert, accept, discovery | Gradle, WebFlux, Temporal |
| `console-search-indexer` | console-search-indexer | Kafka to OpenSearch, search API | Gradle, WebFlux |
| `console-web` | console-web | The console UI and the TypeScript `wfrn` package | npm |

Conventions:

- **Orphan branches.** Each has its own history and build at its root and no platform files, so moving it to its own repository is one `git push <new repo> <branch>:main`.
- **Reviews.** Work happens on `<branch>--<topic>` branches merged into the service branch by pull request; nothing merges into `main`.
- **Contracts.** `console-libs` generates clients from a pinned commit of `console-root` and publishes versions; services depend on versions, never on branches.
- **The platform stays as it is**** in this work****.** It stays on Maven and is never changed by the console branches. What it should change is described in the platform-changes plan, and the console is built against the platform's API as it will be after those changes (the target contract on `console-root`). Until the platform team ships them, every service runs against `platform-fake`, an in-memory implementation of that contract, and switches to the real platform by configuration.
- **Shared UI.** `console-web` imports `SchemaForm`, `ResourcePicker` and `LineageGraph` from the shared packages of platform change P-14.
- Each service has the platform's package shape (`com.iaf.console.<service>.<slice>.{adapters,application,domain,ports}`), reactive end to end (Spring Data MongoDB Reactive, WebClient, Reactor Kafka), a demo profile that runs in memory, production adapters behind Spring profiles, ArchUnit rules from the convention plugins, Testcontainers for MongoDB, Kafka and OpenSearch, and Playwright journeys in `console-web`.


## Beyond the AWS console

Parity copies AWS's framework. These are the features that make people say AWS doesn't do that. They came from the independent review's enhancements and its list of 28 missed features; each was checked against the decisions and the platform as it is, and the ones that fit are now work packages. Most need nothing the plan does not already collect (events, lineage, cost, policy results), which is why they are cheap. None uses schedules or change windows, which you declined.

**Built into phases 1 to 8** (they change how the core console behaves):

| Feature | What people see | WP |
|---|---|---|
| Request access | A greyed action says why and offers Request access, an approval to the account admin | 1.5 |
| View as code | Every wizard and action shows the CLI command, REST call and workflow YAML | 3.5 |
| Command palette | Cmd+K jumps to any service, resource, action or setting; quiet, fast, keyboard first | 3.6 |
| Plain language and tours | Errors in plain words with a next step; empty states and generated getting-started tours | 3.7 |
| Cost before Create | The wizard's review shows the monthly estimate before anything runs | 3.3 |
| Required tags | Organization guardrails require owner, cost centre, data class and environment, pre-filled | 4.4 |
| Blast radius before you click | A delete or bulk action draws the dependents and counts them across accounts first | 4.5 |
| Clone | Launch more like this copies a resource's spec into a new wizard | 4.6 |
| Tiered drift | Hot resources checked every 15 minutes, so drift turns amber while you watch | 5.4 |
| Shadow-infrastructure finder | Unmanaged resources with owner guesses and cost, each with Import and Assign to team | 5.3 |
| Notifications | Job finished, drift found, access request, quota near limit, to Slack, Teams or email | 7.5 |
| Self-service quota increases | Auto-approved under rules, otherwise routed to an approver | 7.6 |
| Audit evidence pack | One download per account and period: trail, approvals, drift fixes, hash-chain proof | 7.1 |

**Phase 9, beyond AWS** (new surfaces, each independent):

| Feature | What people see | WP |
|---|---|---|
| Console copilot | Plain English becomes a filter or a pre-filled wizard; Explain this resource summarises config, cost, history and dependents. It only fills forms and filters; every change still goes through preview and IAM | 9.1 |
| Failure explainer | A failed request shows the cause, the failing step, a suggested fix and Retry with fix | 9.2 |
| Budgets and forecasts | A monthly budget per account and tag, forecast to month end, alerts, an optional create block | 9.3 |
| Resource map | One zoomable picture of the estate across accounts, coloured by health, drift or cost | 9.4 |
| Executive home | Resources managed, requests self-served, lead time against today's tickets, hours saved, drift fixed, spend by team | 9.5 |
| Compliance scorecards | OPA rule packs scored per account, one-click remediation workflows, an org heat map | 9.6 |
| Blueprints | Golden-path stacks (VM, database, volume, DNS) launched and torn down as one request | 9.7 |
| Time travel | A slider replays a resource or an account to any date, with revert to this | 9.8 |
| Chargeback | Monthly cost by cost centre, account, service and tag, exported to finance | 9.9 |
| Change risk score | Low, medium or high per request; high needs an approver | 9.10 |
| Access reviews | Quarterly campaigns; unreviewed access expires | 9.11 |
| Just-in-time elevation | Admin for 2 hours with a reason, approved by rule or person, revoked on expiry | 9.12 |
| Weekly digest | A written summary per account: changes, drift, cost movement, risky permissions | 9.13 |
| Event subscriptions | An account's resource events to a webhook, Kafka topic or Slack, like EventBridge | 9.14 |
| SDKs | Generated clients for TypeScript and Java first, then Python and Go | 9.15 |
| Developer portal and IDP tiles | A team's resources, drift and cost inside the firm's portal; mock until the tile examples arrive | 9.16 |
| ChatOps | A Slack or Teams app to look up resources and run allowed actions with buttons | 9.17 |
| Mobile approvals | An installable responsive view for approvals, drift and job status | 9.18 |
| Team dashboards | Shareable dashboards per team, like CloudWatch dashboards | 9.19 |
| Service health | Per-service and per-provider health from the platform's SLO telemetry | 9.20 |
| Monitoring tab | CPU, memory and disk per resource from provider metrics, with simple alarms | 9.21 |
| Rightsizing | Idle and oversized resources with estimated savings and a one-click resize or delete | 9.22 |

How they are built, where it is not obvious:

- The copilot and the digest sit behind an `AssistantPort` in the BFF. The first adapter is the firm's Devin API, the same agent as the natural-language builder. A Devin session takes longer than a palette keystroke, so the copilot answers in a side panel, and a faster model behind the same port is a later swap, not a redesign.
- Blueprints are a new kind on `console-root` (`kind: Blueprint`: parameters, the resources, and how outputs feed inputs), run by the state service as one request with child requests, in dependency order, torn down in reverse.
- The monitoring tab and rightsizing need provider metrics. They read them through P-09's observe path, with an optional `observe.metrics` block on the type; a type whose provider has no metrics action simply shows no tab.
- Scorecards use the same OPA engine as IAM; until the firm's engine is wired in, rule packs run against the mock, as the platform's policy check does.

**Left out, and why:**

- Terraform provider and Export this account as Terraform. A Terraform provider has to be written in Go, outside the Java and TypeScript you set for this work. View as code (3.5), the CLI and the SDKs cover the GitOps need. If you want Terraform anyway, it is about 240 minutes as a phase 9 package.
- A separate live load-test dashboard. It is part of WP 6.2.
- A scripted demo order. You asked for features, not a demo, so the features stand on their own.

**Value metrics.** The executive home (9.5) reads numbers the plan already collects: resources managed, requests self-served, lead time from request to running against a baseline from today's ticket data, hours saved, drift found and fixed, spend by team, and adoption by week. The baseline needs a one-off export from the current ticket queue.


## Roadmap and work packages

Ten phases, each ending at a gate you can see in the running console; phases 4 to 7 run partly in parallel once the inventory exists, and phase 9 (features beyond AWS, in the section above) runs beside them, off the critical path. Estimates are agent working time in minutes per package, as in earlier threads, with parallel lanes marked; they exclude your review time and real-provider testing. No package changes the platform. Every package is built against the platform as it will be after the planned changes, and runs on `platform-fake` until the platform team ships them.

Phase 3 needs both sign-in and the inventory; after it, editing, search, the parity extras and (once editing exists) drift proceed side by side.

| WP | Phase | Package | Depends on | Lane | ETA (min) |
|---|---|---|---|---|---|
| 0.1 | 0 Foundations | Gradle root, version catalog, convention plugins, ArchUnit rules, CI job | none | A | 60 |
| 0.2 | 0 | `libs/wfrn` and `packages/wfrn` twins with shared fixtures | none | B | 45 |
| 0.3 | 0 | `service-kit`: AccountScope, errors, outbox, idempotent consumer (by event key), OTel, Mongo reactive helpers | 0.1 | A | 75 |
| 0.4 | 0 | Platform-changes plan (written), the platform's target contract (OpenAPI and AsyncAPI), and `platform-fake`, an in-memory platform that implements it for demo and tests | none | C | 90 |
| 0.5 | 0 | Type overlays on `console-root` (`console`, `observe`, `x-console`) and their checks; the `global` region | none | D | 75 |
| 0.6 | 0 | `console-web` shell in Atlas: header, services menu, region and account pickers, router; forked `SchemaForm` and pickers | 0.2 | E | 120 |
| 0.7 | 0 | Nightly platform contract check: fetch `/openapi.json` from the platform's `main`, fail on a breaking change to anything the console uses | 0.4 | C | 45 |
| 1.1 | 1 Sign in and IAM | identity-service: organizations, accounts, users, groups, four managed roles per account (Viewer, Operator, Admin, Auditor), access keys, quotas | 0.3 | A | 150 |
| 1.2 | 1 | Policy compiler to OPA bundles, `POST /authorize`, mock OPA for demo | 1.1 | A | 90 |
| 1.3 | 1 | BFF: OIDC code flow with PKCE, sessions, CSRF, step-up, switch role | 0.3 | B | 120 |
| 1.4 | 1 | Console: sign-in, account picker, IAM pages (users, groups, managed roles, access keys) | 1.1, 1.3 | E | 120 |
| 1.5 | 1 | Request access from a denied action; a refusal view that names the layer that decided (console IAM or platform) | 1.2 | B | 60 |
| 2.1 | 2 Inventory | state-service: aggregate, events, projection, optimistic concurrency, tombstones | 0.3 | A | 150 |
| 2.2 | 2 | Platform ingestion from the event feed on Kafka, with snapshot rebuilds; import of today's registry | 0.4, 2.1 | C | 90 |
| 2.3 | 2 | Query API: cursor paging, filters, sorts, `GET /wfrn/{wfrn}`, history | 2.1 | A | 90 |
| 2.4 | 2 | Pending reaper and collision guard | 2.1 | C | 45 |
| 2.5 | 2 | Per-account envelope keys for sensitive fields (crypto-shred on closure), masking in events and the trail | 2.1 | D | 60 |
| 3.1 | 3 Dynamic console | BFF descriptors: compose, defaults, hints, policy greying, ETag, publish push on content.published | 0.5, 1.2, 2.3 | B | 120 |
| 3.2 | 3 | Console: service landing, resource list with filter bar, saved views, virtualised rows | 3.1 | E | 150 |
| 3.3 | 3 | Console: detail page with tabs; create wizard with review and cost before Create; Actions menu and confirmations | 3.1 | F | 180 |
| 3.4 | 3 | Live updates: SSE row patches, job progress, Requests page | 3.2 | B | 90 |
| 3.5 | 3 | View as code on every wizard and action: CLI command, REST call, workflow YAML | 3.3 | F | 60 |
| 3.6 | 3 | Command palette (Cmd+K): services, actions, settings; resources once 6.1 lands | 3.1 | E | 60 |
| 3.7 | 3 | Plain language: error translation (code to message and next step), empty states, getting-started tours generated per service | 3.3 | F | 90 |
| 3.8 | 3 | Accessibility: WCAG 2.2 AA keyboard paths and names from schema titles; axe checks in every Playwright journey | 3.2, 3.3 | E | 45 |
| 4.1 | 4 Change and lifecycle | `update` verb convention, sample update workflows, Edit settings with desired diff | 3.3 | C | 120 |
| 4.2 | 4 | Delete with dependents, bulk actions through `:batch`, retry of failed requests | 3.3 | F | 90 |
| 4.3 | 4 | Tags, Tag Editor, Resource Groups | 3.2 | E | 120 |
| 4.4 | 4 | Required tags at create (owner, cost centre, data class, environment), pre-filled from the account | 4.3, 1.1 | E | 45 |
| 4.5 | 4 | Blast radius before delete and bulk actions: dependency graph and affected count across accounts | 4.2 | F | 60 |
| 4.6 | 4 | Clone and launch more like this, across accounts where allowed | 3.3 | F | 45 |
| 5.1 | 5 Drift | drift-service: Temporal shard loops, provider reads, `DriftRules`, throttling with per-account shares | 2.1, 0.5 | D | 150 |
| 5.2 | 5 | Drift UI: per resource, per account; one-click revert, accept, ignore field | 5.1, 4.1 | F | 120 |
| 5.3 | 5 | Discovery and import of unmanaged resources: shadow-infrastructure finder with owner guesses, cost and Assign to team | 5.1 | D | 120 |
| 5.4 | 5 | List-based observation (pages of 500) and drift tiers: hot 15 min, warm hourly, cold daily | 5.1 | D | 90 |
| 6.1 | 6 Search and scale | search-indexer, OpenSearch adapter, unified search, typeahead | 2.1 | B | 120 |
| 6.2 | 6 | Load tests to the targets (Gatling) with a live latency dashboard, shard keys and indexes tuned, caching; needs P-01, P-05 and P-09 live | 6.1, 3.4 | A | 150 |
| 7.1 | 7 Parity extras | Activity trail (CloudTrail-like) sealed with its own hash chain and linked to platform evidence by job; audit evidence pack per account and period | 3.1 | C | 120 |
| 7.2 | 7 | Console home, favourites, recent, in-console notifications, Service Quotas pages | 3.2 | E | 120 |
| 7.3 | 7 | Cost by account, service and tag from price lists | 2.3 | D | 90 |
| 7.4 | 7 | Generated CLI and console shell | 3.1 | B | 150 |
| 7.5 | 7 | Notifications to Slack, Teams and email per user preference, deep-linked to the wfrn | 3.4 | B | 60 |
| 7.6 | 7 | Self-service quota increases: auto-approved under rules, otherwise routed to an approver | 7.2 | C | 60 |
| 7.7 | 7 | Account lifecycle: closure, transfer by re-import with an alias, nightly group-change detection, break-glass support access | 1.1, 2.1 | A | 90 |
| 7.8 | 7 | Custom policies: the AWS-shaped policy editor, simulator and permission boundaries | 1.4 | E | 90 |
| 8.1 | 8 Production | Second region as a cell, region routing, failover drill | 6.2 | A | 150 |
| 8.2 | 8 | Threat model fixes, pen-test findings, SLO alerts, runbooks, journeys in pictures | all | F | 150 |
| 8.3 | 8 | Operations: backups, restore drill to RPO 5 min and RTO 1 h, projection rebuild runbook, owners, monthly cost estimate | 6.2 | C | 90 |
| 9.1 | 9 Beyond AWS | Console copilot: plain English to a filter or a pre-filled wizard, and Explain this resource | 3.6, 6.1 | B | 180 |
| 9.2 | 9 | Failure explainer: cause, failing step, suggested fix, Retry with fix | 3.7 | F | 90 |
| 9.3 | 9 | Budgets and forecasts per account and tag, alerts at 80% and 100%, optional create block | 7.3 | D | 120 |
| 9.4 | 9 | Resource map: zoomable dependency graph across types and accounts, coloured by health, drift or cost | 6.1 | E | 150 |
| 9.5 | 9 | Executive home: managed count, self-served requests, lead time against tickets, hours saved, drift fixed, spend by team | 7.3 | E | 120 |
| 9.6 | 9 | Compliance scorecards: OPA rule packs scored per account, remediation workflows, org heat map | 1.2, 4.4 | A | 180 |
| 9.7 | 9 | Blueprints: versioned multi-resource stacks launched and torn down as one request | 4.1 | C | 180 |
| 9.8 | 9 | Time travel: replay a resource or an account to any date, with revert to this | 2.3 | A | 90 |
| 9.9 | 9 | Chargeback reports by cost centre, account, service and tag, exported to finance | 7.3 | D | 90 |
| 9.10 | 9 | Change risk score per request (blast radius, prod tags, history); high risk needs an approver | 4.5 | F | 90 |
| 9.11 | 9 | Access reviews: quarterly campaigns, unreviewed access expires | 1.1 | A | 90 |
| 9.12 | 9 | Just-in-time elevation with a reason, approved by rule or person, revoked on expiry | 1.5 | A | 90 |
| 9.13 | 9 | Weekly account digest, written by the copilot, by email or Slack | 7.5, 9.1 | B | 60 |
| 9.14 | 9 | Event subscriptions: an account's resource events to a webhook, Kafka topic or Slack | 2.1 | C | 90 |
| 9.15 | 9 | SDKs generated from the console's OpenAPI: TypeScript and Java first, then Python and Go | 7.4 | B | 90 |
| 9.16 | 9 | Developer portal and IDP tiles plugin (mock until the firm's examples arrive) | 3.1 | E | 120 |
| 9.17 | 9 | ChatOps app for Slack or Teams: look up, alerts, allowed actions with buttons, through IAM | 7.5 | B | 120 |
| 9.18 | 9 | Mobile approvals: installable responsive view for approvals, drift and job status | 3.4 | E | 120 |
| 9.19 | 9 | Team dashboards, shareable, like CloudWatch dashboards | 9.5 | E | 120 |
| 9.20 | 9 | Service health page from the platform's SLO telemetry | 3.1 | F | 60 |
| 9.21 | 9 | Resource monitoring tab: CPU, memory, disk from provider metrics, simple alarms | 5.4 | D | 150 |
| 9.22 | 9 | Rightsizing and idle finder with estimated savings and one-click resize or delete | 9.21, 7.3 | D | 150 |

Totals: phases 0 to 8 are about 4,680 agent minutes (3,550 before the review); phase 9 adds about 2,550, for about 7,230 in all. The critical path is unchanged (0.1, 0.3, 2.1, 2.3, 3.1, 3.2, 3.4, 6.2, 8.1, then 8.2), about 1,190 minutes, roughly 20 hours of working time; every new package runs in a parallel lane. 2.4 and 2.5 run in phase 2 because they protect data. Phase 9 is off the critical path and can start, package by package, as soon as its dependencies exist.

Phase gates:

| Gate | Shown in the running console |
|---|---|
| 0 | The empty console shell in Atlas; a wfrn round-trips through both twins; a demo job's events reach the console from `platform``-fake`; the nightly platform contract check runs |
| 1 | You sign in through OIDC, pick an account, and a managed role denies an action with the deciding statement and the deciding layer shown; Request access sends an approval to the account admin |
| 2 | Every resource in today's registry has a wfrn and shows in the state service's API; a cross-account read test passes |
| 3 | VM, database, image, volume and environment each have list, detail and create pages with no type-specific code; adding a sixth sample type shows a sixth service with no UI change within seconds of publishing; automated accessibility checks (WCAG 2.2 AA, axe) pass on every generated page |
| 4 | Edit a VM's memory, delete with dependents refused and its blast radius shown, bulk restart of 50 VMs in waves, a create refused until its required tags are set |
| 5 | A memory change made directly in the recorded vSphere shows as drift within the hot-tier interval (15 minutes); revert fixes it in one click |
| 6 | The load test at the targets passes; search finds any resource by name, tag or wfrn |
| 7 | Activity trail, quotas, cost and the CLI work for every type; an account is closed and a resource transferred with its alias |
| 8 | Two regions, a cell failure drill, a restore drill within RPO 5 min and RTO 1 h, pen test clean |
| 9 | The copilot turns plain English into a filter and a pre-filled wizard; a blueprint launches and tears down a stack; the executive home and the resource map show live data |


## Risks

The biggest risk is the word "100%": the AWS console is hundreds of services deep, and only the framework around them can be copied in full.

| Risk | Why it matters | Mitigation |
|---|---|---|
| Platform reads are not isolated by tenant | Account A's service account can read account B's resources and job inputs | No platform read passes through; every record re-checked by account; cross-account test in CI; P-18 |
| Registry ids collide and overwrite | A resource silently merges into another one's record and lineage | Collision guard in the state service (WP 2.4); collision-safe keys in P-05 |
| "100% parity" read as every AWS service | Scope without end; no release ever ships | Parity defined as the framework list in the console section; service depth comes from providers' workflows. Decision 1 |
| Generated pages feel generic | Customers judge the console against AWS's hand-built pages | Strong defaults, `x-console` hints, one polished sample per kind of type, a hint linter, plain-language errors and tours |
| Types without `update` or `observe` | No edit, no drift for that type | The console says so plainly ("Editing is not offered by this provider"); the provider admin view lists missing verbs as a readiness score |
| Two sources of truth: platform registry and state service | Disagreement between the provider UI and the console | One-way: the platform's registry writes become events, the state service owns the customer view, a nightly reconciliation report flags any gap, the pending reaper settles stuck requests. Decision 3 |
| Drift reads overload providers | Provider outages caused by the console | List reads in pages of 500, tiers, a per-account share of each provider's rate, back off on 429 and 503, per-provider circuit breakers |
| Tenant data leak | The worst possible failure | AccountScope on every port, ArchUnit gate, cross-account tests against the real platform, isolation in search queries, pen test |
| New infrastructure (Kafka, OpenSearch) | Operating cost and skills | The firm already runs both; demo profiles run without them; named owners and a cost estimate (WP 8.3). Decision 6 |
| Two build tools | Friction in CI and for developers | Contracts only through OpenAPI and AsyncAPI, separate CI jobs, a nightly check against the platform's live contract. Decision 7 |
| Policies in AWS's language are hard to get right | Customers locked out or over-permitted | Managed roles first, custom policies later with the simulator and "last accessed" data; the refusal view names the deciding layer |
| Event ordering and duplicates | Wrong lifecycle shown | Partitions keyed by wfrn, consumers idempotent on the event key, `version` checks, events carry the job's step sequence |
| Sensitive data kept for years in events | A leak from an old event or backup | Per-account envelope keys, crypto-shred on closure, never on topics |


## Open decisions for Gonzalo

All ten were decided on 2026-10-23. The same list is on the [console-root](https://github.com/gonzalomunoz1024/infrastructure-automation/tree/console-root) hub branch.

| # | Decision | Answer or recommendation | Status |
|---|---|---|---|
| 1 | What "100% parity with the AWS console" covers | The full AWS console framework for every resource type: account and region switcher, IAM, global search, list, detail, create wizard, actions menu, tags, activity trail, quotas, cost. Each service's depth comes from the provider's workflows, not hand-built pages | Decided 2026-10-23 |
| 2 | Who the customers are | The firm's internal teams. Login is the firm's SSO through OIDC, an account belongs to an identity-provider group, and cost goes to the account's cost centre | Decided 2026-10-23 |
| 3 | Who owns customer resources | The state service owns the customer view, fed by the platform registry through the event feed; the console shows a pending state until the events land | Decided 2026-10-23 |
| 4 | What a region is | One region, `global`, for now. It is an ordinary `kind: Region` and the default region, and every key carries the region, so more can be added later with no hacks | Decided 2026-10-23 |
| 5 | Scale targets | The initial targets in the scale section | Decided 2026-10-23 |
| 6 | Events and search infrastructure | Kafka and OpenSearch, which the firm already runs; the demo profiles need neither | Decided 2026-10-23 |
| 7 | Build tools | The platform is not touched anywhere and stays on Maven; the new services use Gradle; platform changes are only described in a plan | Decided 2026-10-23 |
| 8 | Where the console lives | Each service its own repository in concept; for now one orphan branch per service in this repository, with console-root as the hub | Decided 2026-10-23 |
| 9 | Look and feel | AWS's layout and interactions drawn in Atlas visuals with Apple-grade restraint (think Jony Ive: elegant, quiet, precise), the way the designer copies n8n's geometry | Decided 2026-10-23 |
| 10 | Drift remediation default | Detect drift and show it with a one-click revert (plus accept and ignore field); never revert automatically | Decided 2026-10-23 |

The one Atlas exception, `@tanstack/react-virtual` for row virtualisation, was approved on 2026-10-23. Everything else stays hand-rolled.

