# Platform changes for the customer console

This document lists every change the existing platform (`backend/`, `frontend/`, `packages/`,
the content folders and `deploy/`) needs so the customer console can be built on top of it. It is
a plan, not a description of code that exists. Nothing here has been implemented yet.

The console itself (an AWS-console-like UI for the firm's internal teams, with `wfrn` resource
names, a state service, a drift service, a search indexer and a backend-for-frontend, all built
with Gradle on MongoDB) is described in the plan document
[Customer console plan](https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC). This README only
covers what changes on the platform side so that plan can work, and why.

- Status: proposal, 2026-10-23. Written against `main` at `c9529d5`.
- Audience: whoever implements the platform side, and whoever reviews it.
- Decided so far (2026-10-23): full AWS console framework parity for every type, internal
  teams as customers, the state service owns the console view, and one `global` region built for
  many, and Kafka with OpenSearch. Open decisions are taken here at the plan's recommended option and called out in section 9.

Contents

1. [What does not change](#1-what-does-not-change)
2. [The changes at a glance](#2-the-changes-at-a-glance)
3. [Rules every change follows](#3-rules-every-change-follows)
4. [The changes in detail](#4-the-changes-in-detail)
   - [P-01 A durable platform event feed](#p-01-a-durable-platform-event-feed)
   - [P-02 Acting on behalf of a console user](#p-02-acting-on-behalf-of-a-console-user)
   - [P-03 Console service accounts](#p-03-console-service-accounts)
   - [P-04 Job context: account, region, wfrn](#p-04-job-context-account-region-wfrn)
   - [P-05 The registry learns wfrns, accounts and paging](#p-05-the-registry-learns-wfrns-accounts-and-paging)
   - [P-06 ResourceType: service, console hints, observe](#p-06-resourcetype-service-console-hints-observe)
   - [P-07 Regions](#p-07-regions)
   - [P-08 Verbs, the subject input and the update convention](#p-08-verbs-the-subject-input-and-the-update-convention)
   - [P-09 Observe and discover](#p-09-observe-and-discover)
   - [P-10 Quotas per account](#p-10-quotas-per-account)
   - [P-11 Policy input carries the console context](#p-11-policy-input-carries-the-console-context)
   - [P-12 Audit evidence carries the console context](#p-12-audit-evidence-carries-the-console-context)
   - [P-13 Reserved nouns, OpenAPI and MCP](#p-13-reserved-nouns-openapi-and-mcp)
   - [P-14 Shared front-end packages](#p-14-shared-front-end-packages)
   - [P-15 Sample content](#p-15-sample-content)
   - [P-16 Deployment](#p-16-deployment)
   - [P-17 Documentation](#p-17-documentation)
5. [Order of work and pull requests](#5-order-of-work-and-pull-requests)
6. [Compatibility: what existing callers see](#6-compatibility-what-existing-callers-see)
7. [Testing](#7-testing)
8. [Risks on the platform side](#8-risks-on-the-platform-side)
9. [Decisions this document depends on](#9-decisions-this-document-depends-on)
10. [Appendix: examples](#10-appendix-examples)

---

## 1. What does not change

The console is a new client of the platform, not a rewrite of it. These stay exactly as they are:

- **The platform stays generic.** Nothing in `backend/` or `frontend/` learns what a VM, a
  database or an image is. Every console page is drawn from content (`kind: ResourceType`,
  `kind: Workflow`, `kind: Provider`), and every change below is expressed in terms of those kinds.
- **Every change to infrastructure is a workflow run.** The console never calls a provider to
  create, change or delete anything. It asks the platform to run a published workflow, and the run
  goes through the same interpreter, Temporal, approvals, previews, canary routing, policy checks,
  quotas and audit evidence as a run started from the provider UI, REST or MCP.
- **The provider and workflow UI** (`frontend/`), the designer, the Add provider wizard, change
  requests and releases keep working with no visible change.
- **The build.** The platform stays on Maven. The new console services build with Gradle under
  `services/`. The two builds share contracts (OpenAPI, AsyncAPI, JSON Schema, test fixtures),
  never Java jars, with one exception discussed in P-05 (the `wfrn` library).
- **The data rules.** MongoDB under the `mongo` profile, one document per record with the record
  under `doc`, no multi-document transactions, indexes created by each adapter at start
  (docs/architecture.md, Persistence). The changes below keep to those rules.
- **The demo profile.** Every change has an in-memory or demo twin, so `scripts/dev.sh` still runs
  the whole platform with no Kafka, no OpenSearch and no real provider.
- **Existing API shapes.** Every change is additive: new optional fields, new optional headers,
  new endpoints, new optional YAML blocks. Section 6 lists exactly what an existing caller could
  notice (nothing, unless they opt in).

## 2. The changes at a glance

Seventeen changes. Sizes are agent working time in minutes, as in the plan's roadmap; they include
tests and doc updates but not review time. "Needed by" is the console roadmap phase that cannot
start without the change.

| ID | Change | Platform slices and folders touched | Needed by | Size (min) |
| --- | --- | --- | --- | --- |
| P-01 | Durable event feed: `platform_events` outbox, relay to Kafka, HTTP feed for demo | `common/events`, new `events` slice, `config`, Helm | Phase 0 | 150 |
| P-02 | `X-On-Behalf-Of`: a signed assertion naming the console user | `identity`, `common/domain/Identity`, `runs`, `audit` | Phase 1 | 120 |
| P-03 | Console service accounts: `console` preset, account binding, rotate, delete | `identity` | Phase 1 | 75 |
| P-04 | Job context: `accountId`, `region`, `wfrn`, `consoleRequestId` on jobs and events | `runs`, `catalog`, `gateway` | Phase 2 | 120 |
| P-05 | Registry: `wfrn`, `accountId`, `region`, `providerId` fields; paging; by-wfrn reads | `resources`, `runs` (register step) | Phase 2 | 105 |
| P-06 | ResourceType: `metadata.service`, `console`, `observe`, `x-console` | `resources`, `packages/workflow-checks`, `scripts/check-workflows.mjs` | Phase 0 | 120 |
| P-07 | `kind: Region` and region-aware provider endpoints | `providers`, new content folder `regions/`, `observability` | Phase 0 | 105 |
| P-08 | Verb conventions: subject input, `update` verb, catalog fields, checks | `catalog`, `workflows`, `packages/workflow-checks` | Phase 3 | 90 |
| P-09 | Observe and discover endpoints for drift detection | `resources`, `providers` (call port), `observability` | Phase 5 | 135 |
| P-10 | Quotas per account, readable and settable over the API | `gateway` | Phase 7 | 45 |
| P-11 | Policy input carries the console context | `common/ports/PolicyPort`, `runs`, `resources` | Phase 1 | 30 |
| P-12 | Audit evidence carries the console context; filter by account | `audit` | Phase 7 | 60 |
| P-13 | Reserved nouns, OpenAPI and MCP updates | `catalog` | Phase 0 | 30 |
| P-14 | Shared front-end packages: `packages/console-kit`, `packages/wfrn` | `frontend/`, `packages/` | Phase 0 | 120 |
| P-15 | Sample content: regions, observe blocks, console hints, `update-vm` | `regions/`, `resource-types/`, `providers/`, `workflows/` | Phase 0 to 4 | 60 |
| P-16 | Deployment: Helm values, Kafka, network policy, dashboards, alerts | `deploy/helm`, `deploy/grafana` | Phase 0 | 60 |
| P-17 | Documentation updates | `docs/` | with each change | 45 |

Total: about 1,570 agent minutes. Sections 5 and 6 cover how they split into pull requests and
what each one risks for existing callers.

## 3. Rules every change follows

These are the platform's existing conventions, repeated here because each change below is judged
against them in review.

1. **Hexagonal slices with CQRS.** New behaviour lands in a slice's `domain/` (pure static rule
   classes with plain JUnit tests), `application/` (`<Slice>CommandHandler`,
   `<Slice>QueryHandler`), `ports/` and `adapters/`. Commands return
   `Mono<CommandAccepted>`; queries read projections only.
2. **Profiles, not flags in code.** A production adapter carries `@Profile(...) @Primary`; its
   demo twin is a plain `@Component`; an in-memory store that must not exist beside its MongoDB
   twin carries `@Profile("!mongo")`. No `@ConditionalOnMissingBean`.
3. **Both twins.** Anything the editor checks also runs in `packages/workflow-checks` (TypeScript)
   and in the Java checks, judged by the shared fixtures and `expected.json`. A new check code
   appears in both or in neither.
4. **Additive API.** New fields are optional, new headers are optional, nothing changes shape for a
   caller that does not send the new things. Errors keep the `{ error, problems? }` shape and
   status codes of docs/api.md.
5. **Temporal determinism.** Any change to the interpreter's command sequence is gated with
   `Workflow.getVersion` under its own change id and lands with a recorded history and a replay
   test (docs/architecture.md, Changing the interpreter workflow). The changes below are designed
   so that none of them alters the command sequence: the new data travels in the workflow's
   arguments and in activity inputs, which Temporal does not compare on replay. Section 7 still
   requires a replay test for each change that touches `InterpreterWorkflowImpl`'s inputs, to prove
   it.
6. **Secrets.** Credentials stay `vault:` references; no new endpoint returns a secret, and every
   new log line and event goes through the existing redaction.
7. **Demo data agrees with itself.** New demo data follows the `common.demo` fixtures
   (`DemoReleases`, `DemoResources`), derived from content, never ids in code.
8. **Docs move with code.** docs/api.md, docs/architecture.md and docs/config-as-code.md are
   updated in the same pull request as the behaviour they describe.

---

## 4. The changes in detail

Each change has the same parts: why the console needs it, what changes, data and events,
security, compatibility, tests, and when it is done.

### P-01 A durable platform event feed

**Why.** The state service must learn, reliably and in order, when a job starts, when it
registers a resource, when it succeeds or fails, and when content (a workflow, a provider, a
resource type) is published. Today the platform's `EventBus` (`common/events/EventBus.java`) is
in process: synchronous listeners (projections, the audit log) plus a Reactor sink
(`Sinks.many().multicast().onBackpressureBuffer(4096, false)`) for WebSocket and SSE clients. An
event emitted while a consumer is down, or more than 4,096 events behind, is lost to that
consumer. The console cannot be built on that: a missed `job.finished` leaves a resource
"creating" forever.

**What changes.**

1. **An outbox collection, `platform_events`.** A new synchronous `EventListener`,
   `EventOutboxListener` in a new `events` slice, registers with the `EventBus` and writes every
   event whose type is in the published catalog (below) to an `EventOutboxPort`:
   - `MongoEventOutbox` (`@Profile("mongo") @Primary`): collection `platform_events`.
   - `InMemoryEventOutbox` (`@Profile("!mongo")`): a bounded ring of the last 50,000 events, enough
     for the demo.

   Each stored event gets a **sequence number** from the existing `store_sequences` counter (the
   same counter the inbox uses), so consumers have one total order per platform installation, and
   an **idempotency key** derived from the event itself (below), so a re-emitted event is
   recognised as the same event.

2. **A deterministic event id for the feed.** `DomainEvent.id` today is `Ids.next("evt")`, random.
   The feed adds `key`, computed by a pure `EventKeys` rule class:
   - job events: `<type>:<jobId>:<job event seq>` (job events already have a `seq` in
     `runs_events`, unique with `jobId`);
   - resource events: `<type>:<resourceId>:<updatedAt epoch nanos>`;
   - content events: `<type>:<name>:<version>`.

   The outbox has a unique index on `key`, so writing the same event twice is a no-op, and a
   consumer de-duplicates on `key`.

3. **Atomicity without transactions.** The platform does not use multi-document transactions
   (docs/architecture.md, Persistence). The outbox write happens in the synchronous listener,
   right after the command handler's own write, so the window in which a crash loses an event is
   the time between two writes. Two mechanisms close it:
   - **The sweeper.** `OutboxSweeper` (a scheduled task, every 30 seconds, one replica at a time
     through a lease document in `platform_events_state`) reads the jobs and resources changed in
     the last 15 minutes (`runs_jobs.updatedNs`, `resources.updatedAt`) and re-emits any terminal
     event the outbox does not hold by `key`. Because keys are deterministic, a re-emit of an event
     that did land is refused by the unique index.
   - **Consumers reconcile.** The state service also runs a nightly comparison against
     `GET /api/v1/resources` and the job index (plan, Risks). The sweeper makes that a safety net,
     not the main path.

   Alternative considered: turning on MongoDB transactions for the outbox write only. Production
   runs a replica set, so it would work, but it would be the platform's first transaction and
   every store would need a session-aware path. The sweeper keeps the platform's rule intact. If
   review prefers transactions, the outbox port is the only place that changes.

4. **A relay to Kafka.** `KafkaEventRelay` (`@Profile("kafka")`) tails `platform_events` with a
   MongoDB change stream (resume token stored in `platform_events_state`, so a restart continues
   where it stopped), and publishes to Kafka with `kafka-clients` (not Spring Kafka, to keep the
   reactive stack small; the producer runs on `boundedElastic`). Producer settings:
   `enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection=5`. One relay is
   active at a time (same lease pattern as the sweeper); others stand by.

   Topics and partition keys:

   | Topic | Events | Partition key | Retention |
   | --- | --- | --- | --- |
   | `platform.jobs` | `job.*`, `batch.wave` | `accountId` when the job has one (P-04), else `jobId` | 14 days |
   | `platform.resources` | `resource.registered`, `resource.retired` | `accountId`, else `resourceId` | 14 days |
   | `platform.content` | `workflow.published`, `workflow.deleted`, `provider.published`, `provider.updated`, `resource-type.changed` (new), `region.changed` (new), `release.*` | content name | 30 days, compacted by name |
   | `platform.audit` | `audit.sealed`, `audit.shared` | record id | 30 days |

   Ordering: per partition only, which is per account for jobs and resources. That is what the
   state service needs (events of one account in order); cross-account order does not matter.

5. **An HTTP feed for everything without Kafka.** `GET /api/v1/events?after=<seq>&limit=<n>&types=<prefix,...>`
   answers events from the outbox in sequence order, `{ items: FeedEvent[], next: <seq> }`, and
   `GET /api/v1/events/stream?after=<seq>` streams them as SSE (`id:` is the sequence, so the
   browser-style `Last-Event-ID` resume works). The console services' demo adapters read this
   instead of Kafka, which keeps the "run everything locally with no infrastructure" promise. It
   needs the new scope `events:read` (P-03).

6. **The envelope** every consumer sees, on Kafka (as the message value, JSON) and over HTTP:

   ```json
   {
     "specVersion": "1.0",
     "id": "evt_01JBX9...",
     "key": "job.finished:job_7Q2M...:42",
     "seq": 1882231,
     "type": "job.finished",
     "at": "2026-10-23T02:11:09.412Z",
     "aggregate": "job",
     "aggregateId": "job_7Q2M...",
     "actor": "console-123456789012",
     "onBehalfOf": { "id": "dana", "name": "Dana Li", "email": "dana@example.com" },
     "context": { "accountId": "123456789012", "region": "global",
                  "wfrn": "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya",
                  "consoleRequestId": "req_01JBX9..." },
     "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
     "data": { "status": "succeeded", "workflow": "create-vm", "version": "2.0.1", "outputs": { "vmId": "vm-1042" } }
   }
   ```

   `onBehalfOf` and `context` are present only when the job was started by the console (P-02,
   P-04). `data` is what the event carries in process today, run through the redaction.

7. **The event catalog.** A new file, `docs/customer-console/events.asyncapi.yaml` (AsyncAPI 3),
   names every published event type and its `data` schema. It is the contract the Gradle services
   generate their consumers from. Only types in the catalog leave the platform; internal events
   (`job.log`, `provider.call`, telemetry) stay in process. Adding a field to `data` is
   compatible; removing or renaming one needs a new type name (`job.finished.v2`) and both
   published for one release.

   The first catalog: `job.started`, `job.step`, `job.attention`, `job.attention-resolved`,
   `job.approval-requested`, `job.approval-decided`, `job.approval-escalated`, `job.cancelled`,
   `job.finished`, `batch.wave`, `resource.registered`, `resource.retired`,
   `workflow.published`, `workflow.deleted`, `provider.published`, `provider.updated`,
   `resource-type.changed`, `region.changed`, `release.started`, `release.promoted`,
   `release.rolled-back`.

8. **Two new content events.** Resource types are rewritten from the content dir at start and
   change through change requests; nothing announces it today. `ResourceCommandHandler` (and the
   content loader) publish `resource-type.changed` with the type's name and a hash of its file
   whenever a type's stored definition changes, and the regions loader (P-07) publishes
   `region.changed`. The BFF uses these to push new type descriptors to open consoles within
   seconds.

**Data.** `platform_events`: `_id` = sequence (a long), `key` (unique), `type`, `at`, `atNs`,
`accountId` (sparse index), `doc` (the envelope). TTL: 7 days by default
(`platform.events.retention`); consumers that fall further behind rebuild from the APIs. Expected
volume at the initial targets Gonzalo accepted (10,000 creations an hour at peak): about 40 events
per job (steps dominate), so about 110 events a second at peak. Sustained for a week, that is at
most about 67 million documents, about 34 GB at 500 bytes each, which the TTL bounds; real traffic
is far below a sustained peak.

**Security.** The feed carries redacted data only. `GET /events` and the stream need `events:read`;
a person's token never gets it (scopes are for service accounts). A console service account sees
only events whose `context.accountId` is its own account (P-03), unless it also holds `admin`; the
state service and the indexer run with an internal service account that sees all accounts.

**Compatibility.** No existing endpoint or event changes. The `EventBus` keeps calling its current
listeners in the same order; the outbox listener is added last, so a slow outbox write never
delays a projection.

**Tests.** `EventKeys` unit tests. `MongoEventOutbox` on a real MongoDB (with
`PLATFORM_TEST_MONGO_URI`): duplicate keys refused, sequence strictly increasing across two
writers. `OutboxSweeper`: a job whose `job.finished` write was skipped is re-emitted exactly once.
`KafkaEventRelay` with Testcontainers Kafka: kill the relay mid-stream, restart, no gap and no
duplicate after consumer de-duplication. Contract test: every event type in the catalog validates
against its AsyncAPI schema, generated from a recorded demo run.

**Done when** a demo run of `create-vm` produces its events, in order, on `GET /api/v1/events`,
and on Kafka under the `kafka` profile, and a relay restart loses nothing.

### P-02 Acting on behalf of a console user

**Why.** The console user signs in to the console, not to the platform. The BFF calls the
platform with the account's service account (P-03), but the job's `requester`, approvals, audit
evidence and the "who did this" line in the provider UI must name the person, not the service
account. A plain header saying "this is Dana" would let anything that holds a service account
token claim to be anyone, so the header carries a signed assertion.

**What changes.**

1. **The header.** `X-On-Behalf-Of: <compact JWS>`, signed by the BFF with an asymmetric key
   (ES256), with claims:

   | Claim | Meaning |
   | --- | --- |
   | `iss` | the BFF's issuer id, `platform.identity.console.issuer` |
   | `aud` | the platform's audience, `platform.identity.console.audience` |
   | `sub` | the user's id at the identity provider (the same `sub` the platform's OIDC mapping uses) |
   | `name`, `email` | for display, the requester line and evidence |
   | `groups` | the user's groups at sign-in (informational; not used for platform RBAC) |
   | `acct` | the console account id the request is for |
   | `region` | the console region the request is for |
   | `jti` | a unique id; the platform refuses a `jti` it has seen within the token's lifetime |
   | `iat`, `exp` | at most 60 seconds apart |

   The platform verifies it with the existing `JwtTokenVerifier` machinery (JWKS cache, asymmetric
   algorithms only) against the BFF's JWKS at `platform.identity.console.jwks-uri`, a separate
   trust anchor from the user OIDC issuer.

2. **Who may send it.** `IdentityWebFilter` accepts the header only when the bearer token is a
   service account holding the new scope `console:act` and bound to a console account (P-03), and
   only when the assertion's `acct` equals that binding. Any other caller sending the header gets
   400 `on-behalf-of not allowed for this caller`. A bad signature, a wrong audience, an expired or
   replayed assertion is 401, the same as a bearer token that does not verify; it never falls back
   to the service account alone.

3. **The identity it produces.** `common/domain/Identity` gains one optional component,
   `Delegation delegation`:

   ```java
   public record Delegation(String userId, String name, String email, String accountId, String region, String assertionId) {}
   ```

   A secondary constructor keeps every existing `new Identity(...)` call compiling. The **effective
   permissions** stay the service account's: its roles, its teams (the account's owning team,
   P-03) and its scopes. That is deliberate: the console's IAM already decided whether Dana may
   do this; the platform judges whether the account may. Both must allow.

   What changes is **who is named**:
   - `Job.requester` becomes the user (id, name, email from the assertion) with `kind: user`, and
     the job gains `actor`, the service account, so nothing is lost. `JobSummary` gains
     `actor?` and `onBehalfOf?` fields.
   - `$job.requester` in expressions is the user's id, so `owner: "{{ $input.owner ?? $job.requester }}"`
     in `create-vm` records the person, as it would from the provider UI.
   - Approvals record the requester as the user; the approval group rules are unchanged. A user
     can never approve their own request; that existing rule now compares the delegated user.
   - Evidence (P-12) records both.

4. **Replay protection.** A new `AssertionReplayStore` port: `MongoAssertionReplayStore`
   (`assertion_jtis`, unique `jti`, TTL index at `exp` + 60 s) and an in-memory twin.

**Security.**
- The BFF's signing key lives in Vault, rotated every 30 days, with the JWKS publishing the
  current and previous key.
- The platform refuses assertions with `exp` more than 60 seconds after `iat`, and allows 30
  seconds of clock skew.
- The header is logged by name only, never its value.
- Nothing about the delegation widens what the service account can do; at worst a stolen service
  account token plus a forged header fails signature verification and is a 401.

**Compatibility.** Callers that do not send the header see nothing new. `Identity` keeps its JSON
shape when `delegation` is null (the field is left out).

**Tests.** `IdentityWebFilter` cases: no header; header from a non-console account (400); good
assertion (identity with delegation); wrong `acct` (400); bad signature, wrong audience, expired,
replayed (401). A job started with a delegation shows the user as requester, the service account
as actor, and `$job.requester` resolves to the user. Approval self-approval refusal with a
delegated requester.

**Done when** a job started through the BFF shows "Dana Li via console" in the provider UI's job
page and in its evidence, and a forged header is refused.

### P-03 Console service accounts

**Why.** Each console account needs its own platform identity so that the platform's existing
team rules (resource ownership, credential ownership, provider visibility, quotas) isolate one
account from another without new code in those rules. Customers are the firm's internal teams,
and each console account is owned by one identity-provider group, so the natural binding is: the
account's service account acts for exactly that group's team.

**What changes.**

1. **A `console` scope preset.** `Scopes` gains `console:act` (send `X-On-Behalf-Of`),
   `resources:observe` (P-09) and `events:read` (P-01). The new preset `console` is
   `registry:read`, `jobs:start`, `jobs:read`, `resources:observe`, `console:act`. A second new
   preset, `console-internal`, for the state service, the drift service and the indexer:
   `registry:read`, `jobs:read`, `resources:observe`, `events:read`.
2. **Account binding.** `ServiceAccount` gains `consoleAccount` (the 12-digit account id, null for
   every other account). `POST /service-accounts` accepts `consoleAccount` with the `console`
   preset, admin only, and requires `teams` to be exactly one team: the account's owning team.
   The identity service creates one per console account through this endpoint, with its own
   admin service account.
3. **Rotation and removal.** Two new endpoints, admin only:
   - `POST /service-accounts/{id}:rotate` → 201 `{ id, token }`: a new token; the old one keeps
     working for `platform.identity.rotation-grace` (default 10 minutes) so the BFF can roll over
     without a failed request. Both hashes are kept on the account during the grace period.
   - `DELETE /service-accounts/{id}` → 204: the token stops working at once. Jobs it started keep
     running; their evidence keeps naming it.
4. **Team naming.** Nothing new: the account's owning team is the group name from the identity
   provider, the same string the platform already reads from the `groups` claim for people. So a
   VM a team created from the console and one it created from the provider UI have the same owner,
   and both UIs show the same thing.

**Security.** A console service account cannot hold `admin`, `workflows:write` or
`providers:write`; `POST /service-accounts` refuses those combinations with 400. Its token is
stored by the identity service in Vault, never in MongoDB.

**Compatibility.** Existing service accounts and presets are unchanged.

**Tests.** Preset contents; the forbidden combinations; rotation grace (old token works for the
grace period, then 401); delete; a console account's job registers a resource owned by its team.

**Done when** the identity service can create, rotate and delete a console account's platform
identity, and a resource created through it is owned by the account's team.

### P-04 Job context: account, region, wfrn

**Why.** The state service must match every job to the console account, region and resource it
is about, and the provider call must go to the right region's endpoint (P-07). Today a job knows
its `workflow`, `version`, `requester`, `channel`, `environment` (from `input.environment`, default
`dev`, in `JobFactory.environmentOf`) and `traceId`, but nothing about accounts, regions or
resource names. The context must not be part of `input`, so no workflow's `api.input` schema has
to change.

**What changes.**

1. **A `JobContext` record** in `runs/domain`:

   ```java
   public record JobContext(String accountId, String region, String wfrn, String consoleRequestId, Map<String, String> labels) {}
   ```

   `StartJob` and `StartBatch` gain it (null for every existing caller), and `Job` stores it.
2. **Where it comes from.**
   - `accountId` and `region`: from the delegation (P-02), never from a header the caller sets
     freely.
   - `wfrn`: header `X-Resource-Wfrn` on a single-job start, accepted only with a delegation, and
     only when the wfrn's account and region equal the delegation's (else 400
     `wfrn.contextMismatch`).
   - `consoleRequestId`: header `X-Console-Request-Id`, accepted only with a delegation.
   - `labels`: up to 16 `X-Job-Label-<name>: <value>` headers, names `[a-z0-9-]{1,32}`, values up
     to 128 characters, for anything the console wants to find jobs by later (for example the
     console tab that started it).
   - Batch: the batch body gains an optional `contexts: { wfrn?, labels? }[]` aligned with `items`
     (same length, else 400). The account and region are the batch's.
   - Child jobs (`run` steps, `orCreateWith` builds) inherit `accountId`, `region`,
     `consoleRequestId` and labels, but not `wfrn`: a child that builds an image gets its own name
     (P-05).
   - Reruns, retries and time-travel re-runs keep the original context.
3. **Where it shows.**
   - `JobSummary` and `JobDetail` gain `context?`.
   - `GET /jobs` gains filters `account`, `region`, `wfrn`, `consoleRequestId`, `label.<name>`,
     backed by new indexes on `runs_jobs` (`accountId + startedNs`, `wfrn`, `consoleRequestId`).
     A console service account only ever sees its own account's jobs, whatever filter it sends.
   - Every `job.*` event's envelope carries `context` (P-01).
   - Expressions: `$job.accountId`, `$job.region`, `$job.wfrn`, `$job.labels.<name>`, null when
     absent, so a workflow author can, for example, tag a provider object with the wfrn.
4. **The interpreter.** The context travels in the interpreter workflow's arguments (the job
   snapshot it already receives) and in the activity inputs of `call` and `register` steps. It
   adds no activity, timer or child workflow, so it needs no `Workflow.getVersion` gate. An
   execution started before the change has no context and replays as before; a replay test
   proves it (section 7).
5. **Region and environment.** The job keeps `environment` exactly as today (from the input). The
   new `region` is independent: a console request in a region with `environment: prod` calls the
   `prod` endpoint of that region (P-07). A job with no context runs in the default region,
   `global`, which with today's content is exactly today's behaviour.

**Security.** Context is only accepted together with a verified delegation, and its account and
region always come from the signed assertion, so a caller cannot file a job under another account.

**Compatibility.** Existing callers send none of the headers and see no `context` field.

**Tests.** `JobFactory` with and without context; batch `contexts` alignment; child inheritance
without `wfrn`; filters on `GET /jobs`; a console account cannot list another account's jobs;
`$job.wfrn` in an expression; replay of a pre-change history.

**Done when** a console-started job carries its account, region and wfrn on the job page, in
`GET /jobs?wfrn=`, and on every one of its events.

### P-05 The registry learns wfrns, accounts and paging

**Why.** The state service is the system of record for the console view, but the platform's
registry is where a running workflow's `register` step writes, where `use` steps and
`x-resource` inputs look resources up, and where lineage lives. The two must agree on names.
Today `Resource.id` is either the id a `register` step gives (the provider's own id, such as
`vm-1042` in `create-vm`) or one `ResourceIds.mint` makes (`<type>-<6 chars>`). Neither is unique
across providers, regions or accounts, and `GET /resources` returns the whole collection as one
array.

**What changes.**

1. **New fields on `Resource`**, all optional:

   | Field | Meaning |
   | --- | --- |
   | `wfrn` | the resource's name, when it was registered by a job with an account context |
   | `accountId` | the console account, copied from the job context |
   | `region` | the region, copied from the job context |
   | `providerId` | the id the `register` step gave, kept separately once `wfrn` exists |

   `id` stays what it is today for every resource, so `use` steps, `x-resource` inputs, lineage
   and every existing workflow keep working with no change.

2. **How a registration gets its wfrn.** In `ResourceCommandHandler.register`, when the job has an
   account context:
   - if the job's `context.wfrn` names this resource's type and the job has not bound it yet, the
     resource takes that wfrn (the console minted it before the job started, so the console shows
     the name from the first second);
   - otherwise (an image built inline, a second resource a workflow registers) the platform mints
     one, using the type's `metadata.service` (P-06), the job's region and account, and a fresh
     ULID;
   - re-registering an existing resource (the `building` then `ready` pattern) keeps its wfrn.

   The `resource.registered` event carries the wfrn, so the state service learns about inline
   resources too.

3. **The wfrn library.** The platform needs the same parser and formatter as the console
   services. The Gradle build publishes `services/libs/wfrn` as `com.iaf:wfrn` to the firm's Maven
   repository (GitHub Packages until a firm repository is chosen), and `backend/pom.xml` depends
   on it. Until publishing is set up, the backend keeps a vendored copy in `common/domain/Wfrn.java`
   that runs the same shared fixture file (`packages/wfrn/fixtures/wfrn.json`) in its tests, so the
   three implementations cannot drift apart. The library is the one Java artifact the two builds
   share; it has no dependencies.

4. **Reads.**
   - `GET /resources/by-wfrn/{wfrn}` → `Resource`, or 404. (A deleted resource is `retired` and
     still answers; tombstones are the state service's job.)
   - `GET /resources?wfrn=` (exact) and `GET /resources?account=` filters.
   - **Paging, opt in.** `GET /resources` and the generated `GET /{plural}` accept `limit`
     (1 to 500) and `after` (an opaque cursor). With `limit`, the body is still an array, ordered
     by `_id`, and the response carries `Link: <...&after=...>; rel="next"` when there is more.
     Without `limit` the answer is exactly today's, so nothing breaks. The MCP `list_<plural>` tools
     gain optional `limit` and `after` the same way.
   - A console service account only sees resources of its own account (and `public` ones, under
     the existing visibility rule).

5. **Indexes** on `resources`: `{ wfrn: 1 }` unique sparse, `{ accountId: 1, type: 1, _id: 1 }`,
   plus the existing `type` and `builtFrom`.

6. **Backfill.** Resources registered before the change have no wfrn. A one-off admin command,
   `POST /resources:backfill-wfrn` with `{ accountId, region, owner }`, mints wfrns for an
   existing team's resources so the console can adopt them (the "import" path of the plan), and
   emits `resource.registered` for each. It is idempotent: a resource that has a wfrn is skipped.

**Compatibility.** `id`, `GET /resources` without `limit`, `use`, `x-resource`, lineage and
`retire` are unchanged. New fields appear only on resources registered by console jobs or
backfilled.

**Tests.** Registration binds the job's wfrn once; inline registrations get minted wfrns;
re-registration keeps the wfrn; unique index refuses a duplicate wfrn; paging walks 1,250
resources in pages of 500 with no duplicates or gaps; the shared wfrn fixtures pass in the Java
copy.

**Done when** a VM created from the console has the same wfrn in the platform registry, in the
state service and in the console URL, and `GET /resources?limit=` pages.

### P-06 ResourceType: service, console hints, observe

**Why.** The console draws every page from the resource type. It needs to know which console
service a type belongs to, how to show it (optional hints), and how to read its real state from
the provider for drift detection. None of that exists on `kind: ResourceType` today
(`ResourceTypeDefinition`: `name`, `plural`, `owner`, `schema`, `states`, `producedBy`,
`description`, `visibility`).

**What changes.**

1. **`metadata.service`.** Lowercase `[a-z0-9-]{2,32}`. The console's service grouping and the
   `service` segment of the wfrn. Default: the owner team without a `team-` prefix
   (`team-compute` → `compute`), so every existing type has one without a file change.
2. **`console:` block**, all optional:

   ```yaml
   console:
     title: Virtual machines           # default: plural, capitalised
     icon: server                      # a name from the Atlas icon set; default: a generic box
     list:
       columns: [name, status, cpu, memoryGb, environment, created]
       defaultSort: -created
     detail:
       tabs: [configuration, drift, activity, relationships, tags, monitoring]
     lifecycle: { building: pending, ready: active, deprecated: active, retired: deleted }
   ```

   `lifecycle` maps the type's `states` to the console's lifecycle classes (`pending`, `active`,
   `updating`, `deleting`, `deleted`, `failed`). Default: by name (`building` → pending, `ready` →
   active, `retired` → deleted), unknown names neutral.
3. **`observe:` block**, optional, the contract drift detection reads (P-09):

   ```yaml
   observe:
     provider: vsphere
     action: vm.get                    # must be an action with effect: read
     input: { id: "{{ $resource.providerId ?? $resource.id }}" }
     map:                              # observed field -> expression over the action's response
       cpu: "{{ $json.cpu }}"
       memoryGb: "{{ $json.memoryGb }}"
       powerState: "{{ $json.powerState }}"
     ignore: [owner]                   # schema fields never compared
     interval: 15m                     # at least 1m
     notFound: missing                 # what a not_found answer means: missing (default) or deleted
     discover:                         # optional: list what the provider has
       action: vm.list
       input: { environment: "{{ $region.environment }}" }
       items: "{{ $json.items }}"
       id: "{{ $item.id }}"
   ```

   Expressions use the existing `ExpressionEngine`, with two new roots: `$resource` (the registry
   record: `id`, `providerId`, `wfrn`, `attributes`, `status`) and `$region` (P-07).
4. **`x-console` keywords inside `schema`**, optional: `format` (`bytes`, `gibibytes`, `duration`,
   `percent`, `cost`, `date`, `wfrn`, `code`), `column` (boolean), `searchable` (boolean),
   `sensitive` (boolean: masked, never indexed, never in events), `mutable` (boolean: editable
   through the `update` workflow, P-08), `group` (string), `help` (URL), `widget` (`picker`,
   `slider`, `code`, `key-value`). The platform ignores them at run time (they are hints for the
   console), but checks them.
5. **The parser.** `ResourceTypeDefinition` gains `service`, `console` (kept as `JsonNode`, judged
   by the checks), and `observe` (a typed `ObserveSpec` record, since the platform executes it).
   `fromJson` fills the defaults. `GET /resource-types` and `/resource-types/{name}` return the
   new fields.
6. **A schema for the file.** `packages/workflow-checks/schemas/resource-type.schema.json`, the
   counterpart of `workflow.schema.json`, judged first by both twins. Today the TypeScript twin
   has `parseResourceType` but no schema.
7. **New checks, in both twins**, with codes:

   | Code | Level | When |
   | --- | --- | --- |
   | `resourceType.service.invalid` | error | `metadata.service` is not `[a-z0-9-]{2,32}` |
   | `console.column.unknownField` | error | a list column is not a schema field or a built-in column (`name`, `status`, `wfrn`, `owner`, `created`, `updated`) |
   | `console.tab.unknown` | error | a detail tab is not one of the known tabs |
   | `console.lifecycle.unknownState` | error | a lifecycle key is not one of the type's `states` |
   | `console.lifecycle.unknownClass` | error | a lifecycle value is not a console class |
   | `observe.provider.unknown` | error | the provider is not published |
   | `observe.action.unknown` | error | the provider has no such action |
   | `observe.action.notRead` | error | the action's `effect` is not `read` |
   | `observe.map.unknownField` | error | a mapped field is not in the type's schema |
   | `observe.interval.tooShort` | error | interval under 1 minute |
   | `observe.discover.action.notRead` | error | the discover action's effect is not `read` |
   | `xConsole.format.unknown` | error | an unknown `format` |
   | `xConsole.mutable.noUpdateWorkflow` | warning | a field is `mutable` but no published workflow has `noun` = type and `verb: update` |
   | `xConsole.sensitive.searchable` | error | a field is both `sensitive` and `searchable` |
   | `observe.missing` | warning | a type with a `producedBy` workflow has no `observe` block (drift cannot work for it) |

   `scripts/check-workflows.mjs` runs them over `resource-types/` in CI, like the workflow checks.
8. **Change event.** `resource-type.changed` (P-01) when a stored type changes.

**Compatibility.** Every block is optional. Existing type files keep validating; the only new
finding on today's samples is the `observe.missing` warning, fixed by P-15.

**Tests.** Shared fixtures for each check code, in both twins, plus `expected.json` entries;
`ResourceTypeDefinition` round trip with and without the new blocks; defaults for `service` and
`lifecycle`.

**Done when** both twins judge a type file with the new blocks identically, and the five sample
types pass with no warnings.

### P-07 Regions

**Why.** The console has a region switcher and the wfrn has a region segment. Gonzalo decided
(2026-10-23) that there is **one region for now, `global`**, but that everything must be built so
more regions can be added later with no hacks: no special cases for `global` in code, no data
migration, no renamed resources. The platform today has environments (`dev`, `test`, `prod` in each
provider's `endpoints`) and no regions. Environments stay what they are: an input of the workflow
the customer chooses. A region is a separate, new dimension.

**The rule that keeps it hack-free.** `global` is an ordinary region, defined by an ordinary
`kind: Region` file, stored, listed, validated and routed like any other. Nothing in code compares
a region name to the string `global`. The only default is in content: a provider that declares no
regions serves the installation's **default region**, which `platform.regions.default` names
(`global`). Adding `eu-west-1` later is a Region file plus `spec.regions` entries in the providers
that serve it; existing resources keep their `global` wfrns, which stay valid forever because
`global` stays a real region.

**What changes.**

1. **`kind: Region`** files in a new content folder, `regions/`. The one that ships now:

   ```yaml
   apiVersion: iaf.platform/v1
   kind: Region
   metadata:
     name: global
     title: Global
   spec:
     partition: wf
     status: available              # available | opt-in | retired
     environments: [dev, test, prod]
   ```

   A loader reads them at start like resource types, stores them through a `RegionStorePort`
   (`MongoRegionStore`, collection `regions`, with an in-memory twin), publishes `region.changed`
   (P-01), and serves `GET /api/v1/regions` and `GET /api/v1/regions/{name}`. At least one Region
   must exist, and `platform.regions.default` must name one, or the platform refuses to start with
   a message naming the property.

2. **Provider endpoints per region.** `kind: Provider` `spec` gains an optional `regions` map
   beside today's `endpoints`. Today's `endpoints` mean "this provider in the default region", so
   every existing provider file is already correct and needs no change:

   ```yaml
   spec:
     endpoints:                     # unchanged: the provider in the default region (global)
       dev: https://vcenter-dev.example.com
       prod: https://vcenter.example.com
     regions:                       # optional, for regions other than the default; empty today
       eu-west-1:                   # a later example, not shipped now
         prod: https://vcenter.euw1.example.com
   ```

   `HttpProviderCall.endpoint(p, environment)` becomes `endpoint(p, region, environment)`, and the
   lookup is one pure rule class, `RegionEndpoints`:
   - the region is the job's region (P-04), else the default region;
   - for the default region: `regions[default]` when the provider declares it, else `endpoints`;
   - for any other region: `regions[region]`, and nothing else;
   - then the environment, exactly as today;
   - nothing found is `misconfigured` ("vsphere has no prod endpoint in eu-west-1"), never a
     silent fall back to another region.

   Every job, console or not, now runs in a region; a job with no context runs in the default
   region, which with today's content is exactly today's behaviour.

3. **What a region offers.** A type is available in a region when the provider of its
   `producedBy` workflow's create call has an endpoint there. `GET /api/v1/regions/{name}/availability`
   → `{ types: string[], workflows: string[] }` lets the BFF hide services a region does not have.
   With one region, everything is available in `global`.

4. **Region everywhere data is keyed.** The region is stored, not implied, wherever a later second
   region would need it: job context (P-04), registry fields (P-05), event envelopes and partition
   keys (P-01), telemetry samples (`CallSample.region`), observe rate-limit buckets (P-09), audit
   evidence (P-12), and every new index that starts with `accountId` also carries `region` next.
   So a second region is added by content and deployment, with no backfill.

5. **Checks, both twins.** `region.name.invalid` (`[a-z0-9-]{2,32}`), `provider.region.unknown`
   (a key of `spec.regions` is not a Region file), `provider.region.environment.unknown` (an
   environment the Region does not list), `provider.region.duplicateDefault` (`spec.regions`
   names the default region and `endpoints` is also set: one or the other).

6. **Per-region reporting.** Test connection and Try it in the provider wizard take an optional
   region (default: the default region); the provider admin view's health and SLO tables gain a
   region filter, which shows only `global` until there are more.

7. **The console side**, for reference: the region switcher is always built and always reads
   `GET /regions`; with one region it shows "Global" without a menu. URLs and wfrns always carry
   the region.

**Compatibility.** No provider file changes. Jobs behave exactly as today, because the default
region's endpoints are today's endpoints.

**Tests.** `RegionEndpoints` as a table (default region with and without `spec.regions`, another
region present and missing, environment missing); start-up refusal without a default region; the
checks in both twins; a test that adds a second Region file and a provider `spec.regions` entry in
a test content folder and runs a job in each region, proving multi-region works today even though
only `global` ships.

**Done when** every job and registry entry carries `region: global`, `GET /regions` lists `global`,
and the two-region test passes.

### P-08 Verbs, the subject input and the update convention

**Why.** The console maps workflows to buttons by `metadata.noun` and `metadata.verb`: the
`create` verb (the type's `producedBy`) is the Create wizard, every other verb is an action on a
resource. For an action, the console must know which input field takes the resource the user is
looking at (in `vm-restart` it is `vmId`; in `delete-vm` it is also `vmId`, but the platform
cannot assume names). Editing a resource needs an agreed `update` verb. And the console needs to
know which actions are destructive or need an approval, to show the right confirmation.

**What changes.**

1. **The subject input.** For a workflow whose `verb` is not `create`, the subject input is the
   one `api.input` property with `x-resource.type` equal to the workflow's `noun`. Optional
   explicit form: `api.subject: vmId`, for a workflow with two inputs of the same type (for
   example "copy volume from A to B").
2. **The update convention.** A workflow with `verb: update`:
   - has a subject input;
   - every other input is either a field of the type's schema marked `x-console: { mutable: true }`
     (the new desired value; absent means "unchanged") or an operational input (`reason`,
     `environment`), marked `x-console: { operational: true }`;
   - should register the resource again at the end with its new attributes, so observed state
     catches up.
3. **New catalog fields.** `CatalogEntry` gains:

   | Field | Value |
   | --- | --- |
   | `subjectInput` | the subject input's name, or null for `create` and for workflows with none |
   | `destructive` | `verb` is `delete`, or the workflow is tagged `destructive` |
   | `approval` | `policy.approval.required` |
   | `mutableFields` | for `update`: the inputs that change the resource |
   | `bulk` | the workflow has `api.batch` |
   | `effect` | the strongest effect among its provider calls (`create`, `change`, `delete`, `read`), from the same analysis previews use |

   The BFF composes each type's Actions menu from `GET /catalog?noun=<type>`.
4. **New checks, both twins:**

   | Code | Level | When |
   | --- | --- | --- |
   | `console.subjectInput.missing` | warning | a non-create workflow whose noun is a resource type has no subject input (it will not appear on that type's pages) |
   | `console.subjectInput.ambiguous` | error | two inputs of the noun's type and no `api.subject` |
   | `console.subject.unknown` | error | `api.subject` names no input |
   | `console.update.unknownField` | error | an `update` input is neither a mutable schema field nor operational |
   | `console.verb.duplicate` | error | two published workflows with the same noun and verb (today only enforced for MCP-exposed ones, through `mcp.toolName`) |
   | `console.create.notProducer` | warning | a `create` workflow for a type whose `producedBy` names another workflow |

**Compatibility.** Additive catalog fields; checks only add findings, and of the samples only the
warning on `patch-cve`-style workflows that act on several types may appear (to be confirmed
when the checks run over `workflows/`).

**Tests.** Catalog fields for every sample workflow; each check in both twins with fixtures.

**Done when** `GET /catalog?noun=vm` gives the console everything it needs to draw the VM
Actions menu with the right confirmations, and an `update-vm` sample passes the checks.

### P-09 Observe and discover

**Why.** Drift detection compares desired state with the provider's truth. Reading the truth means
calling the provider's read action with the right credentials, endpoint, rate limits, overrides,
error classes, telemetry and redaction, which the platform already does for workflow steps. The
drift service must not re-implement any of that or hold provider credentials. The only provider
call outside a job today is `POST /providers:try`, which is for the wizard (it takes a draft
`yaml` and records review evidence), so a new, narrow, read-only path is needed.

**Why not a job per read.** A read has no side effect, and at the initial targets (10 million
resources, every one checked at least hourly) there would be about 2,800 reads a second on average.
As
jobs, that would flood `runs_jobs`, Temporal and the evidence store with runs nobody needs to see.
The drift service's own Temporal loop already gives scheduling, retries and backoff; the
platform only needs to make the call.

**What changes.**

1. **`POST /api/v1/resources/{id}:observe`** (also `POST /api/v1/resources/by-wfrn/{wfrn}:observe`):
   - looks up the resource and its type's `observe` block (404 when the type has none:
     `observe.notSupported`);
   - resolves `observe.input` with `$resource` and `$region`;
   - calls the provider action through `ProviderCallPort` with `channel: observe`, the resource's
     region and environment (`attributes.environment` when the type has that field, else the
     region's first environment);
   - maps the response through `observe.map`;
   - answers `200 { status: 'found' | 'not_found' | 'error', observed?: object, error?: { class, message, retryable },
     provider, action, providerVersion, region, environment, at, durationMs }`.

   It never answers the raw response (only the mapped fields), and every mapped value goes
   through the redaction, and through `sensitive` masking (P-06).
2. **`POST /api/v1/resources:observe`**, a batch of up to 100 ids or wfrns, answering one result
   per id in order. Calls run with bounded concurrency (`platform.observe.concurrency`, default 8
   per provider) so one batch cannot starve a provider.
3. **`POST /api/v1/resource-types/{name}:discover`** with `{ region, environment, cursor? }`:
   runs `observe.discover`, maps every item, and answers
   `{ items: { providerId, observed, managed: boolean, resource? }[], next? }`, where `managed`
   says the registry already has a resource with that `providerId` in that region. It never
   registers anything; importing is the state service's command, which then calls the backfill
   path of P-05.
4. **Rate limits.** `kind: Provider` gains optional `spec.limits: { readsPerSecond, burst }`. Observe
   and discover calls draw from a per-provider, per-region token bucket (`MongoQuotaStore`'s
   bucket logic reused, keyed `observe:<provider>:<region>`); an empty bucket is 429 with
   `Retry-After`, which the drift service honours. Job calls never draw from it: a busy drift loop
   cannot slow a workflow. Default when a provider sets nothing: 5 reads a second.
5. **Telemetry.** Observe calls record `CallSample`s with `channel: observe`. The provider admin
   view shows them as their own series, and they count towards provider health (they are real
   calls that can fail), but they never trigger the job-failure alerts.
6. **Scope and access.** `resources:observe` (P-03). A console service account can observe only its
   own account's resources; the console-internal account can observe all.
7. **Demo.** `RecordedProviderCall` answers observe calls from the recorded samples, through the
   same `RecordedWorld` the demo uses for runs. To make drift visible in the demo, a demo-only
   endpoint `POST /api/v1/demo/world:mutate` with `{ provider, action, match, set }` changes what
   the recorded world answers for one object (for example a VM's `memoryGb`). It exists only
   without the `live` profile.

**Security.** Read actions only (`effect: read` is checked again at call time, not only by the
checks). No input from the caller reaches the provider except the resource id: the input comes
from the type file, so the endpoint cannot be used as a general proxy.

**Compatibility.** New endpoints only.

**Tests.** Observe found, not found, error; mapping and redaction; sensitive masking; batch order
and concurrency; the token bucket; discover with `managed` flags; a non-read action refused at
call time; the demo mutate path makes the next observe answer differ.

**Done when** the drift service can observe 100 VMs in one call in the demo, and a
`world:mutate` of one VM's memory shows up in the next observe.

### P-10 Quotas per account

**Why.** The console has a Service Quotas page. Quotas exist already: `JobQuotas` keeps one token
bucket per team (the caller's first team), 60 a minute with a burst of 60, with per-team overrides
in `platform.gateway.quotas`. Because a console account's service account acts for its owning team
(P-03), quotas are already per team. What is missing is reading and changing them over the API,
and a per-account bucket when one team owns several accounts.

**What changes.**

1. **Bucket key.** When the caller has a delegation, the bucket key is `account:<accountId>`
   instead of the team, so `payments-dev` cannot use up `payments-prod`'s starts. Without a
   delegation, the key stays the first team.
2. **`GET /api/v1/quotas/{key}`** → `{ key, limitPerMinute, burst, available, refillsAt }`; a
   console account may read its own; admins any.
3. **`PUT /api/v1/quotas/{key}`** with `{ limitPerMinute, burst }`, admin only, stored in a new
   `quota_overrides` collection that takes precedence over the configuration. The identity
   service calls it when a quota increase request is approved.

**Compatibility.** Existing callers keep their team buckets.

**Tests.** Key selection; overrides take effect without a restart; a console account cannot read
another's quota.

### P-11 Policy input carries the console context

**Why.** The firm's OPA engine judges every run (`PolicyPort`, phases `INVOKE`, `PUBLISH`,
`RESOURCE_ACCESS`, `STEP`). Policies may want to treat console requests differently (for example
"production deletes from the console need an approval even when the workflow does not ask for
one").

**What changes.** `PolicyInput.context` gains a `console` object when the identity has a
delegation: `{ accountId, region, wfrn, user: { id, email }, assertionId }`. `MockPolicy` ignores
it. The policy engine contract in docs/architecture.md documents the new field. Nothing else
changes; deciding remains the engine's job.

**Tests.** The OPA adapter sends the field; a sample Rego rule in the docs is exercised by the
existing OPA contract test.

### P-12 Audit evidence carries the console context

**Why.** The console's activity trail (the CloudTrail equivalent) combines the BFF's own call
records with the platform's job evidence, and an account admin must be able to export the
evidence of everything done in their account.

**What changes.**
1. Evidence records written for a job with a delegation or context gain `onBehalfOf`
   (`{ id, name, email }`), `actor` (the service account), `accountId`, `region` and `wfrn`. They
   are part of the hashed data of new records only; records sealed before the change verify as
   before.
2. `GET /audit` gains filters `account`, `wfrn`; the export pack takes `account`. A console
   account sees only its own account's records; the existing admin-only rules for sealing and
   sharing stay.
3. The BFF's own audit records (every console call, including reads and denials) can be appended
   to the platform's hash chain through a new `POST /api/v1/audit/external` (scope `admin`, used
   by the BFF's internal account), so the firm has one chain to verify. This is optional: decision
   for phase 7.

**Tests.** New fields in the hash; old records still verify; filters; account isolation.

### P-13 Reserved nouns, OpenAPI and MCP

**What changes.**
1. `ReservedNouns.java` gains `events`, `regions`, `quotas`, `demo` (P-01, P-07, P-10, P-09). Both
   check twins use the same list, so a workflow whose path starts with one of them is
   `api.reservedPath`. None of the sample workflows uses them today (their paths are under
   `/vms`, `/databases`, `/images`, `/volumes`, `/environments` and similar).
2. The OpenAPI document gains the new endpoints, the `context`, `actor` and `onBehalfOf` fields on
   jobs, the new catalog fields, and the optional paging on resource reads, with the `Link`
   header documented.
3. MCP: `list_<plural>` gains optional `limit` and `after`; `get_<plural>` accepts a wfrn as well as
   an id. No new tools.

### P-14 Shared front-end packages

**Why.** The console and the provider UI must render the same schema the same way, so the
components move into packages both apps import, rather than being copied.

**What changes.**
1. **`packages/console-kit`** (new, like `packages/ui`): `SchemaForm`, `ResourcePicker`,
   `LineageGraph`, `JsonEditor`, `PreviewCard`, `StepTimeline` and `ProblemList` move here from
   `frontend/src/components`, with their tests. `frontend/` imports them from `@iaf/console-kit`.
   No behaviour change; the existing Playwright journeys must pass unchanged.
2. `SchemaForm` gains support for the `x-console` keywords (format, group, widget, help, mutable,
   sensitive). The provider UI turns them on too, so a workflow author sees in the designer's
   test form what customers will see.
3. **`packages/wfrn`** (new): the TypeScript twin of the wfrn library, with the shared fixtures
   the Java copies also run (P-05).
4. `packages/ui` (Atlas) stays as it is. If decision 10 of the plan (the `@tanstack/react-virtual`
   exception) is accepted, the virtualised table lives in `console-kit`, not in Atlas, so Atlas
   keeps its "no third-party components" rule.

**Tests.** Unit tests move with the components; the frontend's journeys pass; a fixture test for
`SchemaForm` with every `x-console` keyword.

### P-15 Sample content

**What changes.**
1. `regions/global.yaml`, the only region for now (P-07).
2. `providers/team-compute/vsphere.yaml`: `spec.limits`, and a
   new `vm.update` action (`effect: change`, input `id`, `cpu`, `memoryGb`) with a sample.
   No provider gains `spec.regions`: their `endpoints` already serve `global`.
3. `resource-types/vm.yaml` and `database.yaml`: `metadata.service`, `observe` blocks (from
   `vm.get`, and the MongoDB provider's read action), and `x-console` hints on a few fields;
   `vm.yaml` gets the full `console` block as the polished example. The other three types stay
   without hints on purpose, to prove the defaults.
4. `workflows/team-compute/update-vm.yaml`: `verb: update`, subject `vmId`, mutable `cpu` and
   `memoryGb`, a preview, an approval for `prod`, a `vm.update` call, a wait for power state and a
   final `register` with the new attributes.
5. `common.demo` fixtures: two demo console accounts (owned by `team-compute` and `team-data`) with
   a handful of resources each, derived from the content, so the console has something to show in
   the demo.

### P-16 Deployment

**What changes** in `deploy/helm`:
- `events.relay.enabled`, `kafka.bootstrapServers`, `kafka.securityProtocol`, `kafka.saslSecret`
  (the `kafka` profile turns on when `events.relay.enabled` is true).
- `identity.console.issuer`, `identity.console.audience`, `identity.console.jwksUri`.
- `observe.concurrency`, `observe.defaultReadsPerSecond`.
- `regions` content is part of the content repository, so no chart change for it.
- NetworkPolicy: ingress to the platform API from the console namespace (BFF, state, drift,
  indexer) in addition to today's sources.
- Grafana (`deploy/grafana`): panels for outbox lag (newest `platform_events` sequence minus the
  relay's last published), relay errors, observe calls per provider and region, observe 429s;
  alert when outbox lag exceeds 60 seconds for 5 minutes.
- `docker-compose.yml`: an optional `kafka` service (Redpanda, single node) under a compose
  profile, so `docker compose --profile kafka up` runs the relay locally.

### P-17 Documentation

Each change updates, in its own pull request: docs/api.md (endpoints, headers, fields, scopes),
docs/architecture.md (the `events` slice, the outbox and relay, delegation, regions, the new
ports and collections in the Persistence table), docs/config-as-code.md (`kind: Region`, the
ResourceType blocks, `x-console`, the verb conventions, provider `spec.regions` and
`spec.limits`), docs/getting-started.md (running with Kafka), and this README (status per
change).

---

## 5. Order of work and pull requests

Nine pull requests. Each is reviewable alone, keeps CI green and changes nothing for existing
callers. The lanes show what can run in parallel.

| PR | Contains | Depends on | Lane | Size (min) | Unblocks console phase |
| --- | --- | --- | --- | --- | --- |
| 1 | P-06, P-07, P-13, P-15 (content model, checks in both twins, regions, reserved nouns) | none | A | 285 | 0 |
| 2 | P-01 (event feed, outbox, sweeper, relay, HTTP feed, AsyncAPI) | none | B | 150 | 0 |
| 3 | P-14 (console-kit and wfrn packages) | none | C | 120 | 0 |
| 4 | P-03, P-02, P-11 (console service accounts, delegation, policy context) | none | D | 225 | 1 |
| 5 | P-04, P-05 (job context, registry wfrns, paging, backfill) | 1, 2, 4 | B | 225 | 2 |
| 6 | P-08 (verbs, subject input, update convention, catalog fields) | 1 | A | 90 | 3 |
| 7 | P-09 (observe, discover, limits, demo mutate) | 1, 5 | A | 135 | 5 |
| 8 | P-10, P-12 (quotas per account, audit context) | 4, 5 | D | 105 | 7 |
| 9 | P-16 (Helm, Grafana, compose) | 2, 4, 7 | C | 60 | 8 |

P-17 rides with each PR. The critical path is PR 4, PR 5, PR 7, PR 9: about 645 minutes. With
four lanes the whole set is about 1,570 minutes of work done in roughly 700 minutes of elapsed
working time.

## 6. Compatibility: what existing callers see

| Caller | Sees after all changes |
| --- | --- |
| Provider UI and designer | The same pages. New optional fields on jobs (`context`, `actor`, `onBehalfOf`) appear on console-started jobs only; the provider admin view gains a region filter and an "observe" series |
| REST clients of generated routes | Nothing new unless they send the new headers, which they cannot without a console service account |
| `GET /resources` and `/{plural}` | The same array without `limit`; with `limit`, the same array plus a `Link` header |
| MCP clients | Two optional arguments on `list_<plural>`; `get_<plural>` also takes a wfrn |
| Service accounts | Unchanged; new presets and scopes exist beside the old ones |
| Content authors | New optional YAML blocks; one new warning (`observe.missing`) on types without `observe`; new reserved path nouns (`events`, `regions`, `quotas`, `demo`) |
| Policy engine | One optional `console` object in the input of console-started runs |
| Running Temporal executions | Replay unchanged: no command-sequence change (proved by replay tests) |
| Operators | A new collection set (`platform_events`, `platform_events_state`, `assertion_jtis`, `regions`, `quota_overrides`), new indexes created at start, and, only if enabled, a Kafka connection |

## 7. Testing

- **Unit:** every new rule class (`EventKeys`, endpoint selection, subject input resolution,
  delegation validation, quota key selection, observe mapping) with plain JUnit, no Spring.
- **Both twins:** every new check code has a fixture under the shared fixtures and an
  `expected.json` entry; CI fails if the Java and TypeScript results differ.
- **MongoDB:** every new adapter runs against a real MongoDB when `PLATFORM_TEST_MONGO_URI` is set
  (a fresh database per test), including unique and TTL indexes.
- **Temporal replay:** for PR 5, record a `create-vm` history before the change
  (`HistoryRecorder`, `backend/src/test/resources/temporal/before-job-context/`) and replay it after,
  proving the context adds no command.
- **Kafka:** Testcontainers for the relay (restart with no gap, idempotent producer).
- **Contracts:** the OpenAPI document and the AsyncAPI catalog are generated from the code and
  compared in CI with the committed files; the Gradle services generate their clients from the
  same files, so a breaking change fails both builds.
- **End to end:** a new Playwright journey in the provider UI that opens a console-started job and
  sees "via console"; the console's own journeys live with the console.
- **Security:** tests for every 400 and 401 path of P-02, for account isolation in P-04, P-05,
  P-09, P-10 and P-12 (a console account reading another account's job, resource, observe result,
  quota and evidence must get 404 or an empty list).

## 8. Risks on the platform side

| Risk | Mitigation |
| --- | --- |
| The outbox write adds latency to every command | It is one insert, last among the synchronous listeners; measured in the PR on the demo run; if it exceeds 5 ms p95, it moves to an asynchronous writer with the sweeper as the guarantee |
| Events lost in the window between the state write and the outbox write | Deterministic keys plus the sweeper; nightly reconciliation in the state service |
| Delegation becomes a way around RBAC | Effective permissions stay the service account's; the assertion only names the person; signed, short-lived, single-use |
| Observe endpoints used as a provider proxy | Read actions only, inputs from the type file only, per-provider rate limits, account isolation |
| Region support changes provider calls for existing jobs | Jobs without a region use exactly today's lookup; tested as a table |
| Checks add findings to existing content | Only one warning on today's samples, fixed in the same PR |
| Two builds drift apart | Contracts generated from code and compared in CI; the wfrn fixtures shared by three implementations |

## 9. Decisions this document depends on

Decisions from the plan that shape the platform changes:

| # | Decision | Status | What it means here |
| --- | --- | --- | --- |
| 1 | What parity covers | Decided 2026-10-23: the full AWS console framework for every resource type (account and region switcher, IAM, global search, list, detail, create wizard, actions menu, tags, activity trail, quotas, cost), with each service's depth coming from the provider's workflows | Every platform change above is generic over types; nothing is built for one type. P-08 (verbs) and P-09 (observe) are what give a type its depth |
| 2 | Who the customers are | Decided 2026-10-23: the firm's internal teams | Login is the firm's SSO; a console account is owned by an identity-provider group, which is the team its service account acts for (P-03) |
| 3 | Who owns customer resources | Decided 2026-10-23: the state service owns the console view, fed by the platform registry through the event feed; the console shows a pending state until the events land | P-01 and P-05 as written. The registry stays the platform's record for runs, `use` steps and lineage |
| 4 | What a region is | Decided 2026-10-23: one region, `global`, for now, built so more can be added with no hacks | P-07 as written: `global` is an ordinary Region file and the default region; every key carries the region |
| 6 | Events and search infrastructure | Decided 2026-10-23: Kafka and OpenSearch, which the firm already runs | P-01 as written: the relay publishes to the firm's Kafka; the HTTP feed stays for the demo profile and local runs. OpenSearch is used by the console's indexer only, not by the platform |
| 7 | Build tools | Open; assumed the platform stays on Maven | Moving the platform to Gradle removes the vendored wfrn copy and the cross-build publishing in P-05 |

## 10. Appendix: examples

### A console create, as the platform sees it

```http
POST /api/v1/vms HTTP/1.1
Authorization: Bearer iaf_<console account token>
X-On-Behalf-Of: eyJhbGciOiJFUzI1NiIsImtpZCI6ImJmZi0yMDI2LTEwIn0.<claims>.<signature>
X-Resource-Wfrn: wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya
X-Console-Request-Id: req_01JBX9W3T6
Idempotency-Key: <the X-Console-Request-Id value>
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
Content-Type: application/json

{ "name": "web-07", "image": "img-ubuntu-2404", "size": "medium", "environment": "dev" }
```

```json
HTTP/1.1 202 Accepted
{ "jobId": "job_7Q2M4K9D3C", "status": "queued", "links": { "job": "/api/v1/jobs/job_7Q2M4K9D3C" } }
```

The job's summary afterwards:

```json
{
  "id": "job_7Q2M4K9D3C", "workflow": "create-vm", "version": "2.0.1", "status": "succeeded",
  "requester": "dana", "channel": "rest",
  "actor": "console-123456789012",
  "onBehalfOf": { "id": "dana", "name": "Dana Li", "email": "dana@example.com" },
  "context": { "accountId": "123456789012", "region": "global",
               "wfrn": "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya",
               "consoleRequestId": "req_01JBX9W3T6", "labels": {} }
}
```

And the registry entry its `register` step wrote:

```json
{
  "id": "vm-1042", "type": "vm", "name": "web-07", "status": "ready",
  "wfrn": "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya",
  "accountId": "123456789012", "region": "global", "providerId": "vm-1042",
  "owner": "team-compute", "visibility": "team",
  "attributes": { "name": "web-07", "image": "img-ubuntu-2404", "cpu": 4, "memoryGb": 16, "environment": "dev", "owner": "dana" },
  "createdBy": { "jobId": "job_7Q2M4K9D3C", "workflow": "create-vm", "version": "2.0.1" },
  "builtFrom": ["img-ubuntu-2404"]
}
```

### An observe call

```http
POST /api/v1/resources/by-wfrn/wfrn:wf:compute:global:123456789012:vm%2Fvm-01jbx7q2m4k9d3c8f5r6t1w0ya:observe
Authorization: Bearer iaf_<console-internal token>
```

```json
{
  "status": "found",
  "observed": { "cpu": 4, "memoryGb": 32, "powerState": "poweredOn" },
  "provider": "vsphere", "action": "vm.get", "providerVersion": "1.4.0",
  "region": "global", "environment": "dev",
  "at": "2026-10-23T02:30:00.120Z", "durationMs": 412
}
```

Here the drift service sees `memoryGb` 32 where the desired state says 16, and records a finding.

### The `update-vm` sample (P-15)

```yaml
apiVersion: iaf.platform/v1
kind: Workflow
metadata:
  name: update-vm
  title: Update VM
  noun: vm
  verb: update
  persona: developer
  owner: team-compute
  folder: Compute
  tags: [vsphere]
  version: 1.0.0
  description: Change a virtual machine's CPU and memory, then record the new size.
api:
  method: PATCH
  path: /api/v1/vms/{vmId}
  input:
    type: object
    required: [vmId]
    properties:
      vmId: { type: string, title: VM, x-resource: { type: vm } }
      cpu: { type: integer, title: CPUs, enum: [2, 4, 8], x-console: { mutable: true } }
      memoryGb: { type: integer, title: Memory (GiB), enum: [8, 16, 32], x-console: { mutable: true, format: gibibytes } }
      reason: { type: string, title: Reason, x-console: { operational: true } }
  response: { status: 202 }
  preview: true
policy:
  rbac: [developer, ops, sre]
workflow:
  do:
    - useVm:
        name: Use VM
        use: { type: vm, id: "{{ $input.vmId }}", checkAccess: true }
        output: { as: vm }
    - resize:
        name: Resize in vSphere
        call: provider
        with:
          provider: vsphere
          action: vm.update
          input:
            id: "{{ $vm.attributes.providerId ?? $input.vmId }}"
            cpu: "{{ $input.cpu ?? $vm.attributes.cpu }}"
            memoryGb: "{{ $input.memoryGb ?? $vm.attributes.memoryGb }}"
        retry: { maxAttempts: 2, backoff: exponential }
    - waitReady:
        name: Wait for power on
        call: provider
        with:
          provider: vsphere
          action: vm.waitForPowerState
          input: { id: "{{ $input.vmId }}", state: poweredOn, timeout: 600 }
    - record:
        name: Record new size
        register:
          type: vm
          id: "{{ $input.vmId }}"
          attributes:
            cpu: "{{ $input.cpu ?? $vm.attributes.cpu }}"
            memoryGb: "{{ $input.memoryGb ?? $vm.attributes.memoryGb }}"
```

(The exact shape is settled in PR 6, when the checks run against it.)
