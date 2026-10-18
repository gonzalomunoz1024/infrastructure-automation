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
| `audit`     | evidence records, hash chain, sealing, export, share links           |
| `catalog`   | the generated surface: REST routes, `:batch`, `:preview`, OpenAPI, MCP tools, resource read endpoints |
| `resources` | resource types, registry, lineage, retire                             |
| `assistant` | natural-language builder sessions (Devin), the platform's checks of its drafts, SSE stream, `assistant.*` events |
| `identity`  | who is calling: dev identity, OIDC, service accounts and their scopes (`ServiceScopeWebFilter`), RBAC checks |
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
| `AssistantPort`         | `DevinAssistant` (profile `devin`: the v3 organization API; sessions run the playbooks synced from `devin/` with a required structured-output schema, follow-ups go on the same session, which is polled) | `MockAssistant` (deterministic drafts) |
| `ChangeRequestPort`     | `GitHubChangeRequests` (profile `github`: pull requests through `GitHubPullRequests`, the GitHub REST API; wraps whichever store keeps the change requests, so it combines with a database profile; merges and closes on GitHub come back through `POST /change-requests:github-webhook`) | `InMemoryChangeRequests` (branch + checks, merged in the UI) |
| `ReviewEvidencePort`    | a persistent store (planned)           | `InMemoryReviewEvidence` (Test connection and Try it results per provider, bounded) |
| `GitSourcePort`         | `JGitSource` (https fetch into a throw-away bare repository, files read from the pinned commit) | same |
| `ModuleRegistryPort`    | `HttpModuleRegistry` (the Terraform module registry protocol at `platform.providers.terraform-registry-url`: versions, download location, inputs) | same |

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
opens a change request; merging it publishes the version and starts its canary. A provider
version rolls out the same way: the providers slice keeps the candidate beside the current version
(`ProviderStorePort.saveVersion`), the releases slice routes each job's calls (`ReleaseQueries.routeProvider`,
asked by `ProviderQueries.provider` when the lookup carries the job's `ProviderQueries.Use` in its
Reactor context) and judges the gates on the calls' telemetry, and promotion makes the candidate current.
