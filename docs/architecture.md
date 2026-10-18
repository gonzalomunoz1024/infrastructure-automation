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
| `audit`     | evidence records, hash chain, sealing and checkpoints, export, share links, the platform stream, SIEM export |
| `catalog`   | the generated surface: REST routes, `:batch`, `:preview`, OpenAPI, Swagger UI, MCP tools, resource read endpoints, version pins, deprecation headers |
| `gateway`   | guards on the requests that start jobs: idempotency keys, per-team quotas |
| `notifications` | the in-app inbox and the delivery adapters (chat webhooks, email) behind one port |
| `observability` | job and step spans, traced provider calls, per-workflow metrics          |
| `resources` | resource types, registry, lineage, retire                             |
| `assistant` | natural-language builder sessions (Devin), SSE stream                 |
| `identity`  | who is calling: dev identity, OIDC, service accounts, RBAC checks     |
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
| `ProviderCallPort`      | `HttpProviderCall` (WebClient), `AnsibleRunner`, `TerraformRunner` | `RecordedProviderCall` (samples recorded in Try it) |
| `CredentialsPort`       | `VaultCredentials` (KV v2 over HTTP)   | `InMemoryCredentials`           |
| `PolicyPort`            | `OpaPolicy` (data API over HTTP)       | `MockPolicy` (TODO: wire the firm's OPA engine) |
| `TelemetryPort`         | `MicrometerTelemetry` (+ OTel exporter) | same, in-memory registry        |
| `ProjectionStore`       | `PostgresProjectionStore` (planned)    | `InMemoryProjectionStore`       |
| `EvidenceStore`         | write-once object store (planned)      | `InMemoryEvidenceStore`         |
| `AssistantPort`         | `DevinAssistant` (v3 API)              | `MockAssistant` (deterministic drafts) |
| `ChangeRequestPort`     | `GitHubChangeRequests` (planned)       | `InMemoryChangeRequests` (branch + checks, merged in the UI) |

Every adapter is a `@Component`; the demo one carries `@ConditionalOnMissingBean` of the port or a
profile guard so that the production adapter replaces it when its profile is active.

### Reactive boundaries

Handlers return `Mono`/`Flux`. Blocking clients (Temporal, JGit, process runners) are wrapped in
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` or `Mono.fromFuture` on async
stubs, so a Netty thread never blocks. Live job progress is a `Flux<JobEvent>` from the
`EventBus`, served over `/ws/jobs/{id}` (reactive WebSocket) and `/api/v1/jobs/{id}/events` (SSE).

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
compensation on failure. The same class runs:

- in process, inside `InMemoryWorkflowExecution` (the default), on a bounded scheduler;
- inside Temporal, where `InterpreterWorkflowImpl` implements `StepExecutor` with activities,
  child workflows, `Workflow.await` for approvals and pause-on-failure, and `Workflow.sleep` for
  waits. The definition snapshot travels in the workflow input.

Expressions are n8n-style: `{{ $input.vmId }}`, `{{ $json.vm.id }}` (previous step's output),
`{{ $('Check VM exists').json.vm.name }}`, `{{ $job.id }}`, `{{ $env.name }}`. See
`docs/config-as-code.md`.

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
