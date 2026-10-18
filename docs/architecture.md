# Architecture

The platform lets a team design an infrastructure workflow on an n8n-style canvas, store it as
config as code in Git, and run it on Temporal. Publishing a workflow generates its REST endpoint,
its OpenAPI entry and its MCP tool. Providers (the systems a workflow talks to) are config too: a
provider file says where an API lives, how to sign in and which operations to offer. The platform
holds only the workflow, the provider registration and the run; the real APIs stay in their own
systems.

Everything a team builds (VM restart, MongoDB upgrade, Delete VM) is sample content under
`workflows/`. Nothing in `backend/` or `frontend/` knows what a VM is.

```
frontend/   React 19 · Vite · TypeScript · Tailwind v4 · @xyflow/react (canvas only)
packages/
  ui/              the Atlas design system (tokens, ui/ kit, hooks)      -> used by frontend
  workflow-checks/ the editor checks (also run in CI)                    -> used by frontend + CI
backend/    Spring Boot 3.5 · Java 21 · WebFlux · Maven · Lombok · Temporal Java SDK · JGit
workflows/  sample content: Workflow, Provider and ResourceType files (kind: ...)
devin/      the Devin playbooks (workflow draft, provider draft) and their structured-output schemas
scripts/    dev.sh (backend + frontend), check-workflows.mjs (CI checks over workflows/)
```

## Backend: vertical-slice hexagonal with CQRS

Package root `com.iaf.platform`. One package per slice, each with the same four folders:

```
<slice>/
  adapters/inbound/    RestController<Slice>Adapter (implements the inbound ports), WebSocket handlers
  adapters/outbound/   one adapter per outbound port: InMemory*, Git*, Temporal*, Http*, Vault*, Opa*
  application/         <Slice>CommandHandler, <Slice>QueryHandler (@Service, @RequiredArgsConstructor)
  domain/              records, enums, pure rule classes (static, no Spring)
  ports/inbound/       <Slice>Commands, <Slice>Queries (interfaces returning Mono/Flux)
  ports/outbound/      one interface per external system or store
```

| Slice       | Owns                                                                  |
|-------------|-----------------------------------------------------------------------|
| `workflows` | definitions, drafts, validation, versions, publish, change requests, registry, templates, import |
| `providers` | provider files, OpenAPI import, Ansible and Terraform imports, test connection, try action, review, overrides, checks, health, alerts |
| `runs`      | jobs, the interpreter, bulk runs, approvals, time travel, previews, job events |
| `releases`  | canary releases, gates, version routing                               |
| `audit`     | evidence records, hash chain, sealing and checkpoints, export, share links, the platform stream, SIEM export |
| `catalog`   | the generated surface: REST routes, `:batch`, `:preview`, OpenAPI, Swagger UI, MCP tools, resource read endpoints, version pins, deprecation headers |
| `gateway`   | guards on the requests that start jobs: idempotency keys, per-team quotas |
| `notifications` | the in-app inbox and the delivery adapters (chat webhooks, email) behind one port |
| `observability` | job and step spans, traced provider calls, per-workflow metrics          |
| `resources` | resource types, registry, lineage, retire                             |
| `assistant` | natural-language builder sessions (Devin), the platform's checks of its drafts, SSE stream, `assistant.*` events |
| `identity`  | who is calling: dev identity, OIDC, service accounts and their scopes (`ServiceScopeWebFilter`), RBAC checks |
| `common`    | cross-slice domain: ids, expressions, JSON schema validation, events, clock, redaction, trace ids |
| `config`    | Clock, Jackson, CORS, WebSocket mapping, GlobalExceptionHandler, demo loader |

### CQRS rules

- `ports/inbound/<Slice>Commands` and `<Slice>Queries` are the inbound ports. The REST adapter
  implements both: `POST` maps to a command, `GET` to a query.
- Commands are records (`StartJob`, `PublishWorkflowVersion`, `ApproveStep`, `RegisterProvider`,
  `PromoteCanary`). A command handler validates, changes state through outbound ports, appends a
  `DomainEvent` to the `EventBus`, and returns `Mono<CommandAccepted>` (id + status). Nothing waits
  on a long operation in the request thread.
- Query handlers read projections only (`ProjectionStore` and the slice's own read models). A
  projection is updated by an event listener, never computed inside a command handler.
- Domain rules are pure static classes with plain JUnit tests: `ReleaseGates`,
  `ProviderHealthRule`, `WaveScheduler`, `DependencyRules`, `WorkflowChecks`, `HashChain`,
  `ExpressionEngine`, `WorkflowInterpreter`.

### Outbound ports

| Port                    | Production adapter                     | Default (demo) adapter          |
|-------------------------|----------------------------------------|---------------------------------|
| `WorkflowStorePort`     | `GitWorkflowStore` (JGit, branch per change request) | `InMemoryWorkflowStore` seeded from `workflows/` |
| `WorkflowExecutionPort` | `TemporalWorkflowExecution` (profile `temporal`) | `InMemoryWorkflowExecution` (same interpreter, in-process) |
| `ProviderCallPort`      | `HttpProviderCall` (WebClient)         | `RecordedProviderCall` (samples recorded in Try it) |
| `AutomationRunnerPort`  | `AnsibleRunnerAutomation`, `TerraformCliAutomation`, `ProcessScriptRunner`, `HttpStepRunner` (profile `live`, on the host or in docker/podman) | `DemoAutomationRunner` (recorded samples) |
| `ArtifactStorePort`     | `S3ArtifactStore` (profile `s3`, any S3-compatible store) | `FilesystemArtifactStore` (content-addressed by SHA-256) |
| `GitRemotePort`         | `JGitRemotes` (ls-remote, a mirror per repository, the files of each commit cached once) | same                            |
| `CredentialsPort`       | `VaultCredentials` (KV v2 over HTTP)   | `InMemoryCredentials`           |
| `PolicyPort`            | `OpaPolicy` (data API over HTTP)       | `MockPolicy` (TODO: wire the firm's OPA engine) |
| `TelemetryPort`         | `MicrometerTelemetry` (+ OTel exporter) | same, in-memory registry        |
| `ProjectionStore`       | `JdbcProjectionStore` (profile `postgres`) | `InMemoryProjectionStore`   |
| `EvidenceStore`         | `JdbcEvidenceStore` (profile `postgres`, append-only); `FileSystemEvidenceStore` (profile `worm`, read-only files with retention sidecars); `S3ObjectLockEvidenceStore` (profile `worm-s3`, Object Lock in COMPLIANCE mode) | `InMemoryEvidenceStore` |
| `AssistantPort`         | `DevinAssistant` (profile `devin`: the v3 organization API; sessions run the playbooks synced from `devin/` with a required structured-output schema, follow-ups go on the same session, which is polled) | `MockAssistant` (deterministic drafts) |
| `ChangeRequestPort`     | `GitHubChangeRequests` (profile `github`: pull requests through `GitHubPullRequests`, the GitHub REST API; wraps whichever store keeps the change requests, so it combines with a database profile; merges and closes on GitHub come back through `POST /change-requests:github-webhook`) | `InMemoryChangeRequests` (branch + checks, merged in the UI) |
| `PriceListPort`         | `ContentPriceLists` (every `kind: PriceList` file of the content repo) | same |
| `ReviewEvidencePort`    | a persistent store (planned)           | `InMemoryReviewEvidence` (Test connection and Try it results per provider, bounded) |
| `GitSourcePort`         | `JGitSource` (https fetch into a throw-away bare repository, files read from the pinned commit) | same |
| `ModuleRegistryPort`    | `HttpModuleRegistry` (the Terraform module registry protocol at `platform.providers.terraform-registry-url`: versions, download location, inputs) | same |

Every adapter is a `@Component`; the demo one carries `@ConditionalOnMissingBean` of the port or a
profile guard so that the production adapter replaces it when its profile is active.

The demo data follows one fixture, `common.demo.DemoReleases`: the release stories (versions,
stages, outcomes, who decided). `DemoWorkflowHistory` keeps the versions those releases replaced
and the change requests that published them, `DemoRunsSeeder` routes each seeded job to the
version a story says was live and adds the canary traffic, `DemoResources` derives the registry
from the resource types and workflows, and `DemoReleaseSeed` records the releases last and judges
their gates with `ReleaseGates` over the seeded jobs. So every page in demo mode agrees with the
others, the way the Atlas topology fixture does, and nothing scenario-specific lives in the
platform code.

### Persistence

Without a profile every store is in memory and a restart starts over. The `postgres` profile
(`docker compose up`, `scripts/dev.sh --postgres`) keeps the platform's state in PostgreSQL 16.
`PersistenceConfig` builds a connection pool from `spring.datasource.*` (`SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; a password never goes in a file),
Flyway applies `db/migration/common` and the database's own folder (`db/migration/postgresql`)
on start, and a JDBC adapter (`@Profile("postgres") @Primary`, over the small `Jdbc` helper in
`common/adapters/jdbc`) replaces the in-memory adapter of each store:

| Port (slice)                     | JDBC adapter          | Tables |
|----------------------------------|-----------------------|--------|
| `WorkflowStorePort` (workflows)  | `JdbcWorkflowStore`   | `workflow_versions` |
| `ChangeRequestPort` (workflows)  | `JdbcChangeRequests`  | `change_requests` |
| `ProviderStorePort` (providers)  | `JdbcProviderStore`   | `providers`, `provider_versions` |
| `ReleaseStore` (releases)        | `JdbcReleaseStore`    | `releases` (workflow and provider releases) |
| `ResourceStorePort` (resources)  | `JdbcResourceStore`   | `resource_types`, `resources` |
| `EvidenceStore` (audit)          | `JdbcEvidenceStore`   | `evidence_records`, `evidence_entries`, `evidence_share_links` |
| `ServiceAccountStore` (identity) | `JdbcServiceAccounts` | `service_accounts` |
| `SessionStore` (assistant)       | `JdbcSessionStore`    | `assistant_sessions` |
| `TelemetryPort` (common)         | `JdbcTelemetry`       | `telemetry_samples` (each call with the provider version that served it) |
| `ProjectionStore` (runs)         | `JdbcProjectionStore` | `runs_jobs`, `runs_approvals`, `runs_events`, `runs_definitions` |

A row keeps the record as JSON text in a `doc` column, next to real columns for what is queried
(workflow, status, requester, times). Times are also kept as epoch nanoseconds, so the order is
exact. The SQL is portable between PostgreSQL 16 and H2 in PostgreSQL mode, which the tests use.

- Workflow versions, providers and resource types come from the content dir on start. A missing
  workflow version or provider is added, and one still exactly as seeded takes its file's new
  content; one changed through the platform is never reset. Resource types are rewritten from
  their files at every start. Reads come from a copy of the table that is reloaded when
  `store_revisions` shows a write from any instance.
- A provider's current version is its `providers` row; every version saved since the database
  was made, the current one among them, is also a `provider_versions` row, so a canary candidate
  (`ProviderStorePort.saveVersion`) is kept beside the current version, routed to by its release,
  and survives a restart until it is promoted and saved as current. The versions read newest
  first, as in memory, and a kept version is never written back to the content repo until it is current.
- Audit evidence is append-only twice over. `JdbcEvidenceStore` has no path that updates or
  deletes an entry, and it refuses entries for a sealed record. The PostgreSQL migration adds
  triggers that refuse `UPDATE`, `DELETE` and `TRUNCATE` of entries, entries for a sealed record,
  deleting a header, shrinking one and any change to a sealed one. Appends to a record take a lock
  on its header row, so concurrent writers cannot fork the hash chain. A share link keeps only
  the SHA-256 of its token, and a service account only the hash of its token.
- The runs index: `JobProjection` still folds job events in process, but it keeps the jobs (with
  their steps), approvals and event history in `ProjectionStore`. The definition a job ran is
  stored once per distinct definition. Event numbering carries on after a restart, so
  `/api/v1/jobs/{id}/events` replays the stored history and then follows the live tail. The live
  fan-out (SSE, WebSocket) stays in memory.
- Provider-call samples are queued, and a background thread writes them in batches, so recording
  never waits for the database (a read writes the queue first). Each row keeps the provider
  version that served the call, so a provider canary's gates judge the candidate's calls against
  the current version's after a restart too. Samples older than
  `platform.persistence.telemetry-retention` (30 days) are deleted every hour and never served.
- The demo history (`platform.demo.seed-history`) is written once per database (`store_seeds`).
  A later start finds it claimed and turns the switch off before any seeder runs.

Run one backend per database: the runs index is folded from the events this instance sees. Jobs
the in-memory engine was running when the backend stopped keep the status they had.

### Reactive boundaries

Handlers return `Mono`/`Flux`. Blocking clients (Temporal, JGit, process runners) are wrapped in
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` or `Mono.fromFuture` on async
stubs, so a Netty thread never blocks. Live job progress is a `Flux<JobEvent>` from the
`EventBus`, served over `/ws/jobs/{id}` (reactive WebSocket) and `/api/v1/jobs/{id}/events` (SSE).
The store ports are synchronous, and the JDBC stores of the `postgres` profile block. So under
that profile `BlockingStoresWebFilter` subscribes each request's whole chain on `boundedElastic`.
The demo adapters that simulate latency (`RecordedProviderCall`, `DemoAutomationRunner`) also wait
there, so the job events that follow are stored from there. `Jdbc` counts any statement that still
runs on a non-blocking thread, and the wiring test expects none.

### Conventions

- `record` for every domain and DTO type. Enums serialise lowercase through `@JsonValue`.
- Jackson: `non_null`, ISO dates. One injectable `Clock`. Ids are strings (`job_…`, `rel_…`).
- `GlobalExceptionHandler`: `IllegalArgumentException` → 400, `NotFoundException` → 404,
  `ForbiddenException` → 403, `ConflictException` → 409, `TooManyRequestsException` → 429 with
  `Retry-After`, `ResponseStatusException` → its status, anything else → 500,
  body `{ "error": "…" }`.
- Tests: Mockito for ports + `StepVerifier` for handlers; plain JUnit for domain rules;
  `WebTestClient` for adapters; `TestWorkflowEnvironment` for the Temporal workflow. No Spring
  context unless the test is about wiring.
- Logging via Lombok `@Slf4j`. No field injection. No static mutable state outside fixtures.

### Evidence, notifications, guards and telemetry

| Port (slice)                       | Adapters |
|------------------------------------|----------|
| `EvidenceStore` (audit)            | `InMemoryEvidenceStore`; over `WormEvidenceStore` (one write-once object per entry, seal and checkpoint, created conditionally so two instances never take the same seq, the record rebuilt from its objects): `FileSystemEvidenceStore` (profile `worm`, read-only files with retention sidecars) and `S3ObjectLockEvidenceStore` (profile `worm-s3`, Object Lock in COMPLIANCE mode) |
| `SiemPort` (audit)                 | `SplunkHecSiem` and `SyslogSiem` (profile `siem`, each on when its destination is set) |
| `NotificationPort` (notifications) | `InAppNotifications` (the inbox, over `InboxStore`), `SlackWebhookNotifications` (profile `slack`), `EmailNotifications` (profile `mail`) |
| `ChangeTicketPort` (runs)          | `MockServiceNowChangeTickets`; `ServiceNowChangeTickets` (profiles `servicenow` and `live`, the Table API on `change_request`) |
| `IdempotencyStore` (gateway)       | `InMemoryIdempotencyStore` (per instance) |

- `AuditListener` folds job events into one hash chain per job, release decisions into
  `release:<id>` and every other recorded command into the platform stream. Each entry is
  redacted (`common/redaction`: secret-looking fields and the values resolved from Vault during
  the run) before it is chained, then queued for the SIEM by `SiemExporter` (bounded, batched,
  retried with backoff). The scheduled seal seals closed records and checkpoints open ones.
- `IdempotencyWebFilter` (after the identity filter) and `JobQuotas` guard every request that
  starts jobs; MCP `tools/call` goes through the same `IdempotencyHandler` and quotas.
- `NotificationListener` turns notify steps, provider alerts, approval requests and escalations
  into notifications; `NotificationHandler` sends each to every `NotificationPort`, and a failing
  adapter never stops the others. `ProviderAlertTriggers` (runs) starts the alert triggers that
  match a provider alert.
- Tracing is Micrometer's Observation API on the OpenTelemetry bridge (`common/tracing`).
  `GeneratedRouteWebFilter` and `McpServer` open `iaf.gateway` and `iaf.mcp` spans under the
  request's (which continues an incoming `traceparent`); `JobTracing` (observability) turns job
  events into `iaf.job` and `iaf.step` spans on the trace the job's `traceId` names
  (`RequestedTraceIds` makes it the root of that trace when nothing started it from a span);
  `TracedProviderCalls` proxies every `ProviderCallPort` bean so each call is an
  `iaf.provider.call` span under its step. `JobMetrics` counts and times jobs per workflow. Step
  spans come from the in-memory engine's events.

## The interpreter

`WorkflowInterpreter` (runs/domain) is a pure walker over a `WorkflowDefinition`. It takes a
`StepExecutor` (the only thing that touches the world) and a `JobContext` (input, step outputs,
identity). It resolves expressions, walks `do` in order, handles `switch`, `fork`, `try/catch`,
`for`, `wait`, `set`, `raise`, `run` (child workflow), `approval`, `use`/`register`, and
compensation on failure. Before the first step it builds what the input asked for: an
`orCreateWith` input holding `{ create }` runs its producer as a child job (step `<input>.create`)
and then reads as the id of the resource the child registered; every engine returns a child's
result through `ChildResults`, which carries those registrations. The same class runs:

- in process, inside `InMemoryWorkflowExecution` (the default), on a bounded scheduler;
- inside Temporal, where `InterpreterWorkflowImpl` implements `StepExecutor` with activities,
  child workflows, `Workflow.await` for approvals and pause-on-failure, and durable timers for
  waits. The definition snapshot travels in the workflow input.

A `use` step that waits for a resource to be ready goes through `PortStepExecutor.Engine.awaitResource`:
in process the registry's `resource.*` events on the EventBus wake it (`ResourceQueries.awaitState`),
bounded by the step's timeout and ended by a cancel; on Temporal it maps to `Workflow.await` over a
signal a registry listener sends to the waiting workflow.

A preview (`Previews`, runs/application) runs the same interpreter as a dry run on the input and
the recorded samples, in process whatever the engine, and judges the static walk of `PreviewRules`
with it: the steps it does not take are skipped, provider `plan` actions are asked through
`ProviderCallPort`, the registry's lineage adds what a change touches, and `PriceListPort` prices
the create calls.

Expressions are n8n-style: `{{ $input.vmId }}`, `{{ $json.vm.id }}` (previous step's output),
`{{ $('Check VM exists').json.vm.name }}`, `{{ $job.id }}`, `{{ $env.name }}`. See
`docs/config-as-code.md`.

### Running on Temporal

The `temporal` profile (`docker compose up`, `scripts/dev.sh --temporal`) sets
`platform.execution.mode: temporal` and swaps the engine: `TemporalWorkflowExecution` starts each
job as an `InterpreterWorkflow` execution whose workflow id is the job id, on
`platform.temporal.task-queue` at `platform.temporal.target` (namespace
`platform.temporal.namespace`). The worker runs inside the backend process
(`InterpreterWorkerLifecycle` starts it once the application is ready, retrying in the background
while the server is down, and drains it on shutdown), because its activities are the slice's own
beans: a step's calls go through the same `PortStepExecutor` as in memory, once, with the step's
timeout (the interpreter does the retrying), and every event goes through `JobEvents`, so the
projection, the audit log and the streams cannot tell the engines apart. Approvals, attention
decisions and cancels are the signals `approve`, `attention` and `cancel`; `status` and `waitingOn`
are queries. Waits, timeouts, retry backoffs and `use … waitUntil: ready` polls are timers; `run`
steps and batch rows are child workflows whose ids are the child job ids. A cancel from the
Temporal UI or CLI ends the job as cancelled by `temporal`. Without the `postgres` profile the job
list is each instance's in-memory projection, so jobs started before a backend restart are not
listed again, although their executions carry on. With it, the jobs, their steps and their event
history are read back from PostgreSQL. With `platform.temporal.ui-url` set (the Temporal web UI, `:8233` in
`docker-compose.yml`), each job's view carries `links.temporal`, its execution there
(`<ui-url>/namespaces/<namespace>/workflows/<job id>`, the latest run), and the job page shows
"Open in Temporal".

The platform's own step calls heartbeat while they run: a provider call, an automation run or a
script heartbeats every `platform.temporal.heartbeat-interval` (15s) and fails as `worker-lost`
after `platform.temporal.heartbeat-timeout` (60s; `0` turns it off) without one, so a backend that
dies mid-call fails the step, which its retry policy can run again on another instance, instead of
waiting out the step's timeout.

`platform.roles` (`api, worker` by default) splits that work. An instance with the `api` role
serves the REST API, the streams, MCP and the generated routes, and starts, signals and queries
executions; one with the `worker` role runs the interpreter's worker. Without the `api` role an
instance answers nothing but the actuator (health, metrics), so its probes and scrapes keep
working. One role alone needs the `temporal` profile, and the context refuses to start without
it. Since the worker that runs a job's steps is the instance that records them, API instances see
that progress only through a shared job store (the Postgres adapters); with the in-memory
projection every instance keeps both roles.

### Custom-code providers

A provider of type `sdk` is code a team owns, served by the team's own worker on its own task
queue (`spec.worker.taskQueue`, default `provider-<name>`). The platform never runs that code:
under the `temporal` profile the interpreter schedules each call of an action as one activity on
that queue and the worker answers it. The provider SDKs (`sdk/java`, `sdk/go`, `sdk/python`)
implement the worker's side of the contract, derive the action schemas from the handlers' types
and write the provider file, so the code and the file cannot drift.

- The activity type is the action id (`address.reserve`); a `call: activity` step names its own.
- The one argument is a JSON object: `input` (the step's resolved input), `idempotencyKey`,
  `job { id, workflow, version, traceId }`, `step`, `environment` and `attempt` (from 1). The
  key is `<job id>:<step id>:<$job.seq of the step's first attempt>`: the same for every retry
  of the step and when a restarted worker picks the call up again, new for a new run of the step.
  The result is the action's output, a JSON object.
- The activity runs once. The step's `timeout` (10m by default) is both its schedule-to-close and
  its start-to-close timeout, so a call that no worker picks up fails the step instead of waiting
  in the queue for good; its heartbeat timeout is the provider's `worker.heartbeatTimeout` (none
  when absent), and Temporal does not retry it: the step's `retry` policy does, with the same key.
- A failure is an `ApplicationFailure` whose type is the error class (`spec.errors[].class`);
  non-retryable means no retry fixes it, and the step fails at once. Running out of the step's
  timeout fails the step as `timeout` (saying so when no worker on the queue picked the call up)
  and a heartbeat timeout as `worker-lost`, both retryable. The SDKs report
  a call that breaks the contract as `invalid_request`, `invalid_input`, `invalid_output` or
  `unknown_action` (never retried) and any other exception as the retryable class `error`.
- Cancelling the job cancels the activity; the worker learns of it through its heartbeats.

The in-memory demo answers an `sdk` action from its `sample`, as it does for every provider; the
`live` profile without Temporal fails it with a message saying it needs the `temporal` profile.
Each worker is a deployment of its own, which the team that owns it scales and releases (the
Helm chart's `providerWorkers`, the example workers in `docker-compose.yml`); a stopping worker
stops polling and lets the calls in flight finish (`WORKER_DRAIN_SECONDS`, 30 by default).

### Verified against a real Temporal server

Besides the test server of the backend's suite, these ran against a Temporal dev server
(`temporal server start-dev`, Server 1.29.1, UI 2.42.1), with the backend jar on the `demo` and
`temporal` profiles and the example workers of the three SDKs on their own queues:

- The `vm-restart` sample through its generated route: two provider steps, the approval decided
  through `POST /api/v1/approvals/{id}:approve` (a signal), an automation step, a provider step
  and a notification, to `succeeded`; `links.temporal` opened the execution in the UI. A cancel
  through `POST /jobs/{id}:cancel` ended a job waiting for its approval, which expired.
- Restarts: a backend stopped while a team's worker ran a call, and started again only after the
  call had finished, replayed the history and finished the job by itself. A job waiting for an
  approval outlived a restart too; its approval went in as the `approve` signal through the
  Temporal CLI, since the new instance's in-memory projection does not know the job (the
  `postgres` profile fills that gap).
- `reserve-ip-address` through `POST /api/v1/addresses` on the Go (`ip-pool`) and Python
  (`dns-records`) example workers, alone and as a three-row batch whose rows ran as child
  workflows; a scratch workflow added the Java example (`cmdb`) as a third step.
- Failures: a worker killed mid-call failed its step as `worker-lost` after the heartbeat timeout,
  and the step's retry ran it again on a fresh worker with the same idempotency key; a call to a
  queue with no worker failed its step as `timeout` after the step's timeout, naming the queue.

## Frontend

See `docs/design-language.md`. The shell (every page but the canvas) is built from
`packages/ui`, the Atlas design system. The canvas (`frontend/src/designer`) replicates n8n's
editor on `@xyflow/react`. Routing is a small hash router in `frontend/src/app/router.ts`; no
router library. The API client is `frontend/src/lib/api.ts`, typed from `docs/api.md`.

## Where things are in Git

A workflow is `workflows/<team>/<name>.yaml`, a provider `providers/<team>/<name>.yaml`, a
resource type `resource-types/<name>.yaml`, all under the content repo (`workflows/` in this
monorepo for the samples). Publishing writes the file on a branch `change/<name>-<version>` and
opens a change request; merging it publishes the version and starts its canary. A provider
version rolls out the same way: the providers slice keeps the candidate beside the current version
(`ProviderStorePort.saveVersion`), the releases slice routes each job's calls (`ReleaseQueries.routeProvider`,
asked by `ProviderQueries.provider` when the lookup carries the job's `ProviderQueries.Use` in its
Reactor context) and judges the gates on the calls' telemetry, and promotion makes the candidate current.
