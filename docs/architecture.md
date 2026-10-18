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
compensation on failure. The same class runs:

- in process, inside `InMemoryWorkflowExecution` (the default), on a bounded scheduler;
- inside Temporal, where `InterpreterWorkflowImpl` implements `StepExecutor` with activities,
  child workflows, `Workflow.await` for approvals and pause-on-failure, and durable timers for
  waits. The definition snapshot travels in the workflow input.

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
Temporal UI or CLI ends the job as cancelled by `temporal`. The job list is still each instance's
in-memory projection, so jobs started before a backend restart are not listed again, although their
executions carry on. With `platform.temporal.ui-url` set (the Temporal web UI, `:8233` in
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
  Temporal CLI, since the new instance's in-memory projection does not know the job (a shared job
  store fills that gap).
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
opens a change request; merging it publishes the version and starts its canary.
