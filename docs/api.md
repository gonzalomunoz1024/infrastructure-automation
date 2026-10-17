# API contract

Every platform endpoint lives under `/api/v1`. Generated workflow endpoints live under
`/api/v1/<noun>s…` and resource read endpoints under `/api/v1/<plural>`. The nouns `workflows`,
`jobs`, `approvals`, `providers`, `releases`, `audit`, `resources`, `resource-types`,
`credentials`, `registry`, `catalog`, `assistant`, `triggers`, `change-requests`, `templates`,
`service-accounts`, `me`, `mcp`, `openapi.json` are reserved for the platform.

Shapes below are TypeScript. Dates are ISO strings. Enums are lowercase strings. Errors are
`{ error: string }` with 400 (bad input), 403 (RBAC), 404 (unknown id), 409 (state conflict),
500. Commands return 202 with `CommandAccepted` unless noted.

```ts
type CommandAccepted = { id: string; status: string; links?: Record<string, string> }
type Problem = { level: 'error' | 'warning'; code: string; step?: string; field?: string;
                 message: string; fix?: { label: string; kind: 'replace' | 'insert-approval' | 'connect'; value?: string } }
```

## Identity

| Method | Path                      | Returns |
|--------|---------------------------|---------|
| GET    | `/me`                     | `{ id, name, email, teams: string[], roles: string[], admin: boolean }` |
| GET    | `/service-accounts`       | `{ id, name, roles, createdAt }[]` |
| POST   | `/service-accounts`       | body `{ name, roles }` → `{ id, token }` (token shown once) |

Dev identity: header `X-User: <id>` (default `gonzalo`, admin, teams `team-compute`,
`team-platform`). Service accounts: `Authorization: Bearer <token>`. OIDC profile: a JWT.

## Workflows

```ts
type WorkflowSummary = { name; title; noun; verb; persona?; folder; owner; tags: string[];
  status: 'draft' | 'published'; latestVersion; publishedVersion?; stepCount; updatedAt; mcp: boolean }
type WorkflowDetail = WorkflowSummary & { yaml: string; definition: WorkflowDefinition;
  versions: WorkflowVersion[]; problems: Problem[]; changeRequests: ChangeRequest[] }
type WorkflowVersion = { version; status: 'draft' | 'candidate' | 'published' | 'retired';
  contentHash; publishedAt?; publishedBy?; releaseId?; notes? }
type WorkflowDefinition = { apiVersion; kind: 'Workflow'; metadata; api; mcp; policy; triggers; rollout;
  workflow: { do: Step[]; catch?; outputs? } }   // mirrors docs/config-as-code.md
type Step = { id; name; type: StepType; with?: object; output?: { as }; assert?; retry?; timeout?;
  pauseOnFailure?: boolean; position?: { x; y }; notes?; branches?: Step[][]; cases?; children?: Step[] }
type StepType = 'provider' | 'http' | 'ansible' | 'terraform' | 'idp' | 'activity' | 'run' | 'approval'
  | 'switch' | 'fork' | 'for' | 'try' | 'wait' | 'set' | 'raise' | 'use' | 'register' | 'notify' | 'script'
type ChangeRequest = { id; kind: 'workflow' | 'provider'; name; version; branch; title; author;
  status: 'open' | 'merged' | 'closed'; checks: Check[]; strategy: 'canary' | 'all'; createdAt; mergedAt?; url? }
type Check = { id; label; status: 'passed' | 'failed' | 'skipped'; detail? }
```

| Method | Path                                         | Body / Returns |
|--------|----------------------------------------------|----------------|
| GET    | `/workflows?folder=&status=&q=`              | `WorkflowSummary[]` |
| GET    | `/workflows/{name}`                          | `WorkflowDetail` |
| GET    | `/workflows/{name}/versions`                 | `WorkflowVersion[]` |
| GET    | `/workflows/{name}/versions/{version}`       | `{ version; yaml; definition }` |
| PUT    | `/workflows/{name}/draft`                    | `{ yaml }` → `{ name; version; problems: Problem[] }` (200) |
| POST   | `/workflows`                                 | `{ yaml }` → `CommandAccepted` (creates a draft; name from metadata) |
| DELETE | `/workflows/{name}`                          | drafts only → 204 |
| POST   | `/workflows:validate`                        | `{ yaml }` → `{ valid; problems: Problem[] }` (200) |
| POST   | `/workflows:dry-run`                         | `{ yaml; input }` → `DryRunResult` (200) |
| POST   | `/workflows/{name}:publish`                  | `{ strategy?: 'canary' \| 'all'; notes? }` → `CommandAccepted` (id = change request id) |
| POST   | `/workflows/{name}/versions/{version}:replay-check` | → `{ passed: boolean; replayed: number; divergences: { jobId; step; reason }[] }` (200) |
| GET    | `/change-requests?status=`                   | `ChangeRequest[]` |
| GET    | `/change-requests/{id}`                      | `ChangeRequest & { diff: string; yaml: string }` |
| POST   | `/change-requests/{id}:merge`                | → `CommandAccepted` (publishes the version; starts the canary) |
| POST   | `/change-requests/{id}:close`                | → `CommandAccepted` |
| POST   | `/workflows:import`                          | `{ source: 'idp' \| 'ansible' \| 'terraform' \| 'openapi'; ref: string }` → `{ yaml; notes: string[] }` (200) |
| GET    | `/templates`                                 | `{ id; title; description; yaml }[]` |

```ts
type DryRunResult = { passed: boolean; path: string[]; steps: { id; name; status; input?; output?; error? }[]; error? }
```

Registry (read-only, used by the designer and the Devin playbook):

| GET `/registry/step-types` | `{ type; title; category; schema; description }[]` |
| GET `/registry/providers` | `{ name; title; type; actions: { id; title; effect; retry; approval; input: Schema; output: Schema; sample? }[] }[]` |
| GET `/registry/workflows?published=true` | `{ name; title; version; input: Schema; output: Schema }[]` |
| GET `/registry/workflows/search?q=` | up to 5 `WorkflowSummary` |
| GET `/registry/resource-types` | `ResourceType[]` |
| GET `/registry/playbooks` | `{ name; version; path; owner }[]` |
| GET `/registry/credentials` | `{ name; type }[]` (names only) |

## Catalog (generated)

```ts
type CatalogEntry = { workflow; title; noun; verb; persona?; method; path; batchPath; previewPath?;
  input: Schema; mcpTool?: string; owner; version; description? ; batch: { max; rollout } }
```

| Method | Path                                | Returns |
|--------|-------------------------------------|---------|
| GET    | `/catalog`                          | `CatalogEntry[]` |
| GET    | `/openapi.json`                     | OpenAPI 3.1 document of every published workflow and resource type |
| *      | generated `method path`             | body = input → 202 `{ jobId; status: 'queued'; links: { job } }`; 400 with `{ error, problems }` on schema failure; 403 on RBAC |
| POST   | `{path}:batch`                      | `{ items: object[]; defaults?: object; rollout?: { waves; maxParallel; stopIfFailedPercent } }` → 202 `{ jobId; rows }`; 400 lists invalid rows `{ error, rows: { index; problems }[] }` |
| POST   | `{path}:preview`                    | body = input → `Preview` (200) |
| GET    | `/{plural}` and `/{plural}/{id}`    | resources of a type |
| POST   | `/mcp`                              | JSON-RPC 2.0: `initialize`, `ping`, `tools/list`, `tools/call`, `notifications/initialized` |

```ts
type Preview = { workflow; version; steps: { id; name; effect: 'create' | 'change' | 'delete' | 'read' | 'none' | 'unknown'; detail? }[];
  affectedResources: { id; type; name; via }[]; cost?: { monthly; currency; detail };
  policy: { decision: 'allow' | 'deny' | 'needs-approval'; rules: { id; result; message }[]; engine: 'mock' | 'opa' } }
```

MCP tools: one per workflow with `mcp.expose` (name `<noun>_<verb>`, input = `api.input`), one
`<noun>_<verb>_batch` per workflow, `list_<plural>` and `get_<plural>` per resource type,
`get_job_status`, `cancel_job`, and `draft_workflow` (the assistant).

## Jobs

```ts
type JobSummary = { id; workflow; title; version; status: JobStatus; requester; channel: 'catalog' | 'rest' | 'mcp' | 'trigger' | 'batch';
  startedAt; endedAt?; durationMs?; parentId?; kind: 'single' | 'batch'; progress?: { done; total; failed } }
type JobStatus = 'queued' | 'running' | 'waiting-approval' | 'needs-attention' | 'succeeded' | 'failed' | 'cancelled' | 'rolled-back'
type JobDetail = JobSummary & { input: object; output?: object; steps: JobStep[]; approvals: Approval[];
  preview?: Preview; change?: { id; url; state }; children?: JobSummary[]; batch?: Batch; attention?: Attention; traceId }
type JobStep = { id; name; type; status: 'pending' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'skipped' | 'compensated';
  attempt; startedAt?; endedAt?; durationMs?; input?; output?; error?: { class; message; retryable } ; items?: number }
type Attention = { step; error; options: ('retry' | 'edit-input' | 'skip' | 'abort')[]; since; timeout }
type Batch = { rows: BatchRow[]; waves: { index; percent; size; status; startedAt?; endedAt? }[]; rollout; stoppedReason? }
type BatchRow = { index; input; jobId?; status; error? }
type Approval = { id; jobId; workflow; step; group; message; requestedAt; timeout; status: 'pending' | 'approved' | 'rejected' | 'expired';
  decidedBy?; decidedAt?; comment?; preview?: Preview }
type JobEvent = { seq; at; jobId; type: 'job' | 'step' | 'approval' | 'attention' | 'batch' | 'log'; data: object }
```

| Method | Path                                   | Body / Returns |
|--------|----------------------------------------|----------------|
| GET    | `/jobs?workflow=&status=&limit=&parent=` | `JobSummary[]` |
| GET    | `/jobs/{id}`                           | `JobDetail` |
| GET    | `/jobs/{id}/events` (SSE), WS `/ws/jobs/{id}` | stream of `JobEvent`, replaying history first |
| GET    | `/jobs/{id}/replay`                    | `{ steps: (JobStep & { diff?: { field; before; after }[] })[]; comparedTo?: string }` |
| GET    | `/jobs/{id}/evidence`                  | `EvidenceRecord` |
| POST   | `/jobs/{id}:cancel`                    | → `CommandAccepted` |
| POST   | `/jobs/{id}:retry`                     | `{ step?; input? }` → `CommandAccepted` (needs-attention retry, optionally with edited input) |
| POST   | `/jobs/{id}:skip`                      | `{ step }` → `CommandAccepted` |
| POST   | `/jobs/{id}:abort`                     | → `CommandAccepted` (abort and roll back) |
| POST   | `/jobs/{id}:rerun`                     | `{ fromStep; input? }` → `CommandAccepted` (id = the new job) |
| POST   | `/jobs/{id}:retry-failed-rows`         | → `CommandAccepted` (batch) |
| GET    | `/approvals?status=pending`            | `Approval[]` |
| POST   | `/approvals/{id}:approve` / `:reject`  | `{ comment? }` → `CommandAccepted` |
| POST   | `/triggers/alerts`                     | alert payload `{ source; labels; annotations }` → `{ started: { workflow; jobId }[] }` |
| POST   | `/triggers/events`                     | `{ topic; payload }` → same |

## Providers

```ts
type Health = { status: 'healthy' | 'degraded' | 'failing' | 'unknown'; successRate; p50Ms; p95Ms; calls; errorTrend: number[]; reasons: string[] }
type ProviderSummary = { name; title; type; owner; version; environments: string[]; actionCount; health: Health; slo; alerts }
type ProviderDetail = ProviderSummary & { yaml; actions: ActionInfo[]; daily: { date; calls; successRate; p50Ms; p95Ms }[];
  perAction: { action; calls; successRate; p50Ms; p95Ms; topErrors: { class; count }[] }[]; changeRequests: ChangeRequest[] }
type ActionInfo = { id; title; operation; effect; retry; approval; plan?; input: Schema; output: Schema; sample?: object; overridden: boolean }
type SpecImport = { title; version; sha256; auth: { type; where? }; servers: string[];
  operations: { operationId; method; path; summary; effect; retry; input: Schema; output: Schema }[] }
type TryResult = { ok: boolean; status; durationMs; response; mismatches: { field; expected; actual }[]; verified?: boolean }
```

| Method | Path                                      | Body / Returns |
|--------|-------------------------------------------|----------------|
| GET    | `/providers`                              | `ProviderSummary[]` |
| GET    | `/providers/{name}`                       | `ProviderDetail` |
| GET    | `/providers/{name}/alerts`                | `{ id; at; action; message; severity; jobId? }[]` |
| POST   | `/providers:import-spec`                  | `{ type: 'openapi'; url? ; content? }` → `SpecImport` (200) |
| POST   | `/providers:test-connection`              | `{ endpoints; auth }` → `{ results: { environment; ok; durationMs; message }[] }` (200) |
| POST   | `/providers:try`                          | `{ yaml; action; environment; input }` → `TryResult` (200) |
| POST   | `/providers:validate`                     | `{ yaml }` → `{ checks: Check[]; problems: Problem[] }` (200) |
| POST   | `/providers:publish`                      | `{ yaml; strategy? }` → `CommandAccepted` (change request id) |
| POST   | `/providers:draft`                        | `{ description }` → `{ yaml; summary; assumptions[] }` (Ask Devin, `!provider-draft`) |
| POST   | `/providers/{name}/overrides`             | `{ action; response }` → `CommandAccepted` |
| PUT    | `/providers/{name}/slo`                   | `{ successRate; p95Ms; channel }` → `CommandAccepted` |

## Releases

```ts
type Release = { id; workflow; from?; to; strategy; status: 'canary' | 'paused' | 'promoted' | 'rolled-back';
  stageIndex; stages: number[]; stagePercent; startedAt; stageStartedAt; endedAt?;
  gates: { id; label; passed: boolean | null; value?; threshold?; detail }[]; jobs: { current; candidate }; history: { at; event; by? }[] }
```

| GET `/releases?workflow=` | `Release[]` |
| GET `/releases/{id}` | `Release` |
| POST `/releases/{id}:promote` `:pause` `:resume` `:rollback` `:evaluate` | → `CommandAccepted` |

## Audit

```ts
type EvidenceEntry = { seq; at; kind: 'requested' | 'preview' | 'policy' | 'change' | 'approval' | 'step' | 'changed' | 'closed' | 'decision';
  actor?; data: object; hash; previous }
type EvidenceRecord = { jobId; workflow; version; requester; channel; entries: EvidenceEntry[]; latestHash; sealed?: { at; signature; keyId };
  retention: { until }; change?: { id; url } }
```

| GET `/audit?workflow=&from=&to=&requester=` | `{ jobId; workflow; requester; channel; at; entries; status }[]` |
| GET `/audit/{jobId}` | `EvidenceRecord` |
| GET `/audit/{jobId}:verify` | `{ valid: boolean; entries; brokenAt?: number }` |
| GET `/audit/{jobId}/export?format=json\|pdf` | file |
| POST `/audit:export-pack` | `{ filter }` → zip (signed manifest) |
| POST `/audit/{jobId}:share` | → `{ url; expiresAt }` |
| GET `/audit/shared/{token}` | read-only `EvidenceRecord` |
| POST `/audit:seal` | → `{ sealedAt; signature }` (also runs hourly) |

## Resources

```ts
type ResourceType = { name; plural; owner; schema; states; producedBy? }
type Resource = { id; type; name?; status; attributes; owner; visibility; createdBy: { jobId; workflow }; builtFrom: string[]; createdAt }
```

| GET `/resource-types` | `ResourceType[]` |
| GET `/resources?type=&owner=&status=` | `Resource[]` |
| GET `/resources/{id}` | `Resource` |
| GET `/resources/{id}/lineage` | `{ resource; builtFrom: Resource[]; dependents: Resource[] }` |
| POST `/resources/{id}:retire` | → 409 with dependents list when something is built from it, else `CommandAccepted` |

## Credentials

| GET `/credentials` | `{ name; type; path; updatedAt }[]` (never values) |
| POST `/credentials` | `{ name; type; value }` → `CommandAccepted` (stored through `CredentialsPort`) |

## Assistant (natural-language builder)

| POST `/assistant/sessions` | `{ mode: 'create' \| 'edit'; workflow?; draftYaml? }` → `{ sessionId }` |
| POST `/assistant/sessions/{id}/messages` | `{ text }` → SSE events `{ type: 'status' \| 'draft' \| 'message' \| 'validation' \| 'done'; data }` |
| GET `/assistant/sessions/{id}` | `{ sessionId; status; draft; history }` |

The `draft` event carries the structured output in `devin/capability-draft.schema.json` shape
(`status`, `summary`, `workflowYaml`, `changes`, `assumptions`, `questions`, `missing`,
`validation`, `dryRun`).
