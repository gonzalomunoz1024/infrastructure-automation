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
devin/      the natural-language builder playbook and its structured-output schema
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
| `providers` | provider files, OpenAPI import, test connection, try action, overrides, checks, health, alerts |
| `runs`      | jobs, the interpreter, bulk runs, approvals, time travel, previews, job events |
| `releases`  | canary releases, gates, version routing                               |
| `audit`     | evidence records, hash chain, sealing, export, share links           |
| `catalog`   | the generated surface: REST routes, `:batch`, `:preview`, OpenAPI, MCP tools, resource read endpoints |
| `resources` | resource types, registry, lineage, retire                             |
| `assistant` | natural-language builder sessions (Devin), SSE stream                 |
| `identity`  | who is calling: dev identity, OIDC, service accounts, RBAC checks     |
| `common`    | cross-slice domain: ids, expressions, JSON schema validation, events, clock |
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
| `ProviderCallPort`      | `HttpProviderCall` (WebClient), `AnsibleRunner`, `TerraformRunner` | `RecordedProviderCall` (samples recorded in Try it) |
| `CredentialsPort`       | `VaultCredentials` (KV v2 over HTTP)   | `InMemoryCredentials`           |
| `PolicyPort`            | `OpaPolicy` (data API over HTTP)       | `MockPolicy` (TODO: wire the firm's OPA engine) |
| `TelemetryPort`         | `MicrometerTelemetry` (+ OTel exporter) | same, in-memory registry        |
| `ProjectionStore`       | `JdbcProjectionStore` (profile `postgres`) | `InMemoryProjectionStore`   |
| `EvidenceStore`         | `JdbcEvidenceStore` (profile `postgres`, append-only); write-once object store (planned) | `InMemoryEvidenceStore` |
| `AssistantPort`         | `DevinAssistant` (v3 API)              | `MockAssistant` (deterministic drafts) |
| `ChangeRequestPort`     | `GitHubChangeRequests` (planned)       | `InMemoryChangeRequests` (branch + checks, merged in the UI) |
| `PriceListPort`         | `ContentPriceLists` (every `kind: PriceList` file of the content repo) | same |

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
| `ProviderStorePort` (providers)  | `JdbcProviderStore`   | `providers` |
| `ReleaseStore` (releases)        | `JdbcReleaseStore`    | `releases` |
| `ResourceStorePort` (resources)  | `JdbcResourceStore`   | `resource_types`, `resources` |
| `EvidenceStore` (audit)          | `JdbcEvidenceStore`   | `evidence_records`, `evidence_entries`, `evidence_share_links` |
| `ServiceAccountStore` (identity) | `JdbcServiceAccounts` | `service_accounts` |
| `SessionStore` (assistant)       | `JdbcSessionStore`    | `assistant_sessions` |
| `TelemetryPort` (common)         | `JdbcTelemetry`       | `telemetry_samples` |
| `ProjectionStore` (runs)         | `JdbcProjectionStore` | `runs_jobs`, `runs_approvals`, `runs_events`, `runs_definitions` |

A row keeps the record as JSON text in a `doc` column, next to real columns for what is queried
(workflow, status, requester, times). Times are also kept as epoch nanoseconds, so the order is
exact. The SQL is portable between PostgreSQL 16 and H2 in PostgreSQL mode, which the tests use.

- Workflow versions, providers and resource types come from the content dir on start. A missing
  workflow version or provider is added, and one still exactly as seeded takes its file's new
  content; one changed through the platform is never reset. Resource types are rewritten from
  their files at every start. Reads come from a copy of the table that is reloaded when
  `store_revisions` shows a write from any instance.
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
  never waits for the database (a read writes the queue first). Samples older than
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
  `ForbiddenException` → 403, `ResponseStatusException` → its status, anything else → 500,
  body `{ "error": "…" }`.
- Tests: Mockito for ports + `StepVerifier` for handlers; plain JUnit for domain rules;
  `WebTestClient` for adapters; `TestWorkflowEnvironment` for the Temporal workflow. No Spring
  context unless the test is about wiring.
- Logging via Lombok `@Slf4j`. No field injection. No static mutable state outside fixtures.

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
history are read back from PostgreSQL.

## Frontend

See `docs/design-language.md`. The shell (every page but the canvas) is built from
`packages/ui`, the Atlas design system. The canvas (`frontend/src/designer`) replicates n8n's
editor on `@xyflow/react`. Routing is a small hash router in `frontend/src/app/router.ts`; no
router library. The API client is `frontend/src/lib/api.ts`, typed from `docs/api.md`.

## Where things are in Git

A workflow is `workflows/<team>/<name>.yaml`, a provider `providers/<team>/<name>.yaml`, a
resource type `resource-types/<name>.yaml`, all under the content repo (`workflows/` in this
monorepo for the samples). Publishing writes the file on a branch `change/<name>-<version>` and
opens a change request; merging it publishes the version and starts its canary.
