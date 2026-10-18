# API contract

Every platform endpoint lives under `/api/v1`. Generated workflow endpoints live under
`/api/v<n>/<noun>s…` and resource read endpoints under `/api/v1/<plural>`. The nouns `workflows`,
`jobs`, `approvals`, `providers`, `releases`, `audit`, `resources`, `resource-types`,
`credentials`, `registry`, `catalog`, `assistant`, `triggers`, `change-requests`, `templates`,
`service-accounts`, `me`, `mcp`, `openapi.json` are reserved for the platform.

Shapes below are TypeScript. Dates are ISO strings. Enums are lowercase strings. Errors are
`{ error: string }` with 400 (bad input), 401 (a bearer token that does not verify), 403 (RBAC),
404 (unknown id), 409 (state conflict), 429 (over the team's job-start quota, with `Retry-After`
and `retryAfter` in seconds), 500. Input that fails a workflow's schema or names a
resource the registry does not know adds `problems: Problem[]` (one job) or
`rows: { index; problems }[]` (a batch). Commands return 202 with `CommandAccepted` unless noted.

```ts
type CommandAccepted = { id: string; status: string; links?: Record<string, string> }
type Problem = { level: 'error' | 'warning'; code: string; step?: string; field?: string;
                 message: string; fix?: { label: string; kind: 'replace' | 'insert-approval' | 'connect'; value?: string } }
```

## Identity

| Method | Path                      | Returns |
|--------|---------------------------|---------|
| GET    | `/me`                     | `{ id, name, email, teams: string[], roles: string[], admin: boolean }` |
| GET    | `/service-accounts`       | `{ id, name, roles, teams, createdAt }[]` |
| POST   | `/service-accounts`       | body `{ name, roles, teams? }` → 201 `{ id, token }` (token shown once; admin only) |

Dev identity: header `X-User: <id>` (default `gonzalo`, admin, teams `team-compute`,
`team-platform`); the dev header is for local use only and is ignored under the `oidc` profile.
Service accounts: `Authorization: Bearer iaf_<token>`. OIDC profile: a JWT. A bearer token that
does not verify is always 401; it never falls back to the dev identity.

## Workflows

```ts
type WorkflowSummary = { name; title; noun; verb; persona?; folder; owner; tags: string[];
  status: 'draft' | 'published'; latestVersion; publishedVersion?; stepCount; updatedAt; mcp: boolean }
type WorkflowDetail = WorkflowSummary & { yaml: string; definition: WorkflowDefinition;
  versions: WorkflowVersion[]; problems: Problem[]; changeRequests: ChangeRequest[] }
type WorkflowVersion = { version; status: 'draft' | 'candidate' | 'published' | 'retired';
  contentHash; publishedAt?; publishedBy?; releaseId?; notes? }
type WorkflowDefinition = { apiVersion; kind: 'Workflow'; metadata; api; mcp; policy; triggers; rollout;
  workflow: { do: StepEntry[]; catch?: { do: StepEntry[] }; outputs? } }   // the YAML document as JSON (docs/config-as-code.md)
type StepEntry = { [stepId: string]: { name?; call?: StepType; with?: object; output?: { as }; assert?; retry?; timeout?;
  pauseOnFailure?: boolean; position?: { x; y }; notes?; compensate?; /* or one of */ run?; approval?; switch?; fork?; for?;
  try?; wait?; set?; raise?; use?; register?; notify?; script? } }   // exactly one step key per entry; `@iaf/workflow-checks` parses it
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
| PUT    | `/workflows/{name}/draft`                    | `{ yaml }` or `{ definition }` → `{ name; version; problems: Problem[] }` (200; creates the workflow when the name is new) |
| POST   | `/workflows`                                 | `{ yaml }` or `{ definition }` → `CommandAccepted` (creates a draft; name from metadata; 409 when it exists) |
| POST   | `/workflows:render`                          | `{ definition }` → `{ yaml }` (200): the canonical YAML of a document; the browser never renders YAML itself |
| POST   | `/workflows:parse`                           | `{ yaml }` → `{ definition; problems: Problem[] }` (200): the YAML document as JSON, same shape as `definition` everywhere |
| DELETE | `/workflows/{name}`                          | drafts only → 204 |
| POST   | `/workflows:validate`                        | `{ yaml }` → `{ valid; problems: Problem[] }` (200) |
| POST   | `/workflows:dry-run`                         | `{ yaml; input }` → `DryRunResult` (200) |
| POST   | `/workflows/{name}:publish`                  | `{ strategy?: 'canary' \| 'all'; notes? }` → `CommandAccepted` (id = change request id). Checks: schema, checks, ansible-lint, policy, replay, diff, routes (no other published workflow answers the same invoke, batch or preview route) and yaml; merging re-checks the routes |
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
| GET    | `/openapi.json` (also `/api/v1/openapi.json`) | OpenAPI 3.1 document of every published workflow and resource type |
| GET    | `/swagger-ui` (outside `/api/v1`)   | Swagger UI over that document: redirects to `/swagger-ui/index.html`, assets from the swagger-ui webjar under `/webjars/swagger-ui/`; the page and its assets load without a caller, a bearer token typed into the page goes with every request it makes |
| *      | generated `method path`             | body = input → 202 `{ jobId; status: 'queued'; links: { job } }`; 400 with `{ error, problems }` on schema failure or an `x-resource` id the registry does not know; 403 on RBAC |
| POST   | `batchPath`                         | `{ items: object[]; defaults?: object; rollout?: { waves; maxParallel; stopIfFailedPercent } }` → 202 `{ jobId; rows; status; links }`; 400 lists invalid rows `{ error, rows: { index; problems }[] }`. A path that ends in a parameter batches on the collection, so every row brings its own id: `/vms/{vmId}:restart` → `/vms:restart:batch`, `DELETE /vms/{vmId}` → `/vms:delete:batch`; any other path adds `:batch` (`/vms` → `/vms:batch`). Earlier path parameters apply to every row. Every row is checked against the schema and the registry before anything starts |
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

In the OpenAPI document every generated operation's 202 links to `get_job` (`id` from
`$response.body#/jobId`), and its input schema is `<noun>_<verb>_input`. A workflow that declares
`api.response.body` also gets `<noun>_<verb>_output` (the body as written: what the job's `output`
holds once it succeeds) and `<noun>_<verb>_job` (`JobDetail` with that output), which the 202 names.

### Calls that start jobs

What the generated routes (with their `:batch` and `:preview`), `POST /jobs`, `POST /jobs:batch`
and MCP `tools/call` read besides the input. The OpenAPI operations document the headers and the
409 and 429 answers.

| Header (MCP argument)                 | Effect |
|---------------------------------------|--------|
| `Idempotency-Key` (`idempotencyKey`)  | Up to 255 characters, scoped to the caller. The first request with a key runs and, when it succeeds, its answer is kept for `platform.gateway.idempotency.ttl` (24h); the same key with the same request (method, path, sorted query, pinned version, canonical JSON body) gets that answer again with `Idempotent-Replay: true` (MCP: `_meta.idempotentReplay`). The same key with another request, or while the first still runs, is 409; a failed request frees its key |
| `X-Workflow-Version` or `?version=` (`version`) | Runs exactly that version, published or retired, without the release router's canary split; input, RBAC, policy and preview are judged against it. A draft is 409, an unknown version 404, a header and query that disagree 400. On a GET whose own input has a `version` field, `?version=` stays input. `POST /jobs` pins with its body's `version` |
| `traceparent`                         | W3C trace context: the request's span continues it and the job's `traceId` is its trace id. A `traceId` in the body of `POST /jobs` or `/jobs:batch` must be a W3C trace id (32 hex characters, not all zeros), 400 otherwise |

Every call that starts jobs takes a token from its caller's team first (the caller's first team,
or a bucket of their own when they have none): `platform.gateway.quotas` sets 60 a minute with a
burst of 60, and per-team overrides. A batch takes one token whatever its size. An empty bucket is
429 with `Retry-After` and `{ error, retryAfter }` naming the team, the limit and when to retry
(MCP: a JSON-RPC error with `data.tooManyRequests` and `data.retryAfter`). Jobs the platform
starts itself (triggers, `run` steps, reruns) are not charged. Idempotency keys and buckets live in
each instance's memory.

Generated paths may live under any API version (`/api/v1/`, `/api/v2/`, …); the platform's
nouns, and `notifications`, are reserved under every one. A workflow with `api.deprecated` still
answers, and every answer of its operations carries `Deprecation: @<epoch seconds of since>`
(RFC 9745), `Sunset: <HTTP date>` (RFC 8594) and, with a replacement,
`Link: <replacement>; rel="successor-version"` (path parameters filled from the call). Its
`CatalogEntry` carries `deprecated: { since; sunset; replacement?; message? }`, its OpenAPI
operations are `deprecated: true`, and its MCP tool descriptions start with `Deprecated:`.

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
  decidedBy?; decidedAt?; comment?; preview?: Preview; escalatedTo? }   // escalatedTo: the escalation group, which may decide too
type JobEvent = { seq; at; jobId; type: 'job' | 'step' | 'approval' | 'attention' | 'batch' | 'log'; data: object }
```

| Method | Path                                   | Body / Returns |
|--------|----------------------------------------|----------------|
| GET    | `/jobs?workflow=&status=&limit=&parent=` | `JobSummary[]` |
| GET    | `/jobs/{id}`                           | `JobDetail` |
| GET    | `/jobs/{id}/events` (SSE), WS `/ws/jobs/{id}` | stream of `JobEvent`, replaying history first |
| GET    | `/jobs/{id}/replay`                    | `{ steps: (JobStep & { diff?: { field; before; after }[] })[]; comparedTo?: string }` |
| GET    | `/jobs/{id}/evidence`                  | `EvidenceRecord` (alias of `/audit/{jobId}`) |
| POST   | `/jobs`                                | `{ workflow; version?; input; channel?; traceId?; changeId? }` → `CommandAccepted` (the generic way to start a job; the catalog routes do the same with the workflow's own path) |
| POST   | `/jobs:batch`                          | `{ workflow; version?; items; defaults?; rollout?; channel? }` → `CommandAccepted` (links.rows = row count) |
| POST   | `/jobs/{id}:cancel`                    | → `CommandAccepted` |
| POST   | `/jobs/{id}:retry`                     | `{ step?; input? }` → `CommandAccepted` (needs-attention retry, optionally with edited input) |
| POST   | `/jobs/{id}:skip`                      | `{ step }` → `CommandAccepted` |
| POST   | `/jobs/{id}:abort`                     | → `CommandAccepted` (abort and roll back) |
| POST   | `/jobs/{id}:rerun`                     | `{ fromStep; input? }` → `CommandAccepted` (id = the new job) |
| POST   | `/jobs/{id}:retry-failed-rows`         | → `CommandAccepted` (batch) |
| GET    | `/approvals?status=pending`            | `Approval[]` |
| GET    | `/approvals/{id}`                      | `Approval` |
| POST   | `/approvals/{id}:approve` / `:reject`  | `{ comment? }` → `CommandAccepted` |
| POST   | `/triggers/alerts`                     | alert payload `{ source; labels; annotations }` → 202 `{ started: { workflow; jobId }[]; skipped: { workflow; reason }[] }` |
| POST   | `/triggers/events`                     | `{ topic; payload }` → 202, same body |

A job's `traceId` is a W3C trace id (32 hex characters): the trace of the request that started it,
or a new one. Its spans are `iaf.job` with an `iaf.step` per step attempt and an
`iaf.provider.call` per provider call (in-memory engine), under the span of the request that
started it (`iaf.gateway` on a generated route, `iaf.mcp` on an MCP call). `change.evidenceUrl`
is the link back to the job's evidence that its change record carries
(`platform.change.evidence-url`). The platform's own provider alerts are matched
against the published alert triggers like an external alert, as `{ source: 'platform', labels:
{ alertname: 'provider.alert', provider, action, severity, class }, annotations: { summary,
message, jobId, channel, owner } }` (`class` is the call's error class, `health` for a health
band). An alert raised by a job that such an alert started starts nothing, and the same provider,
action and class starts nothing more for ten minutes.

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
| GET    | `/providers/{name}/alerts`                | `{ id; at; action; message; severity; jobId?; channel? }[]` |
| POST   | `/providers:import-spec`                  | `{ type: 'openapi'; url? ; content? }` → `SpecImport` (200) |
| POST   | `/providers:test-connection`              | `{ endpoints; auth }` → `{ results: { environment; ok; durationMs; message }[] }` (200). Link-local and cloud metadata addresses (169.254.0.0/16, fe80::/10, metadata.google.internal and the like) are refused here, in spec fetches and in provider calls, including through redirects and DNS |
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
type EvidenceRecord = { jobId; workflow; version; requester; channel; entries: EvidenceEntry[]; latestHash; sealed?: Seal;
  retention: { until }; change?: { id; url }; checkpoints?: Seal[] }
type Seal = { at; signature; keyId; seq?; hash? }   // the HMAC of `hash`, the hash of entry `seq`
type Verification = { valid: boolean; entries; brokenAt?: number; seal?: 'verified' | 'mismatch' | 'unverifiable';
  seals?: { kind: 'checkpoint' | 'seal'; at; seq?; keyId; status: 'verified' | 'mismatch' | 'unverifiable' }[] }
```

| GET `/audit?workflow=&from=&to=&requester=` | `{ jobId; workflow; requester; channel; at; entries; status }[]` |
| GET `/audit/platform?from=&to=&aggregate=&actor=` | `{ entries: EvidenceEntry[]; total; latestHash; verification: Verification }`: the platform stream, oldest first; a filter never hides a break in the chain |
| GET `/audit/{jobId}` | `EvidenceRecord` |
| GET `/audit/{jobId}:verify` | `Verification` (every checkpoint, then the seal) |
| GET `/audit/{jobId}/export?format=json\|pdf` | file |
| POST `/audit:export-pack` | `{ filter }` → zip (one JSON per record, the platform stream, signed manifest) |
| POST `/audit/{jobId}:share` | → `{ url; expiresAt }` |
| GET `/audit/shared/{token}` | read-only `EvidenceRecord` |
| POST `/audit:seal` | → `{ sealedAt; signature; keyId; sealed; checkpointed }`: closed records get their final seal, open ones (and the platform stream) a checkpoint over their head (also runs every `platform.audit.seal-every`) |

A job's record holds `requested` (with `publish`: the version's `contentHash` and where it was
published on the platform stream, `platformSeq`, `platformHash`, `publishedAt`, `publishedBy`,
`changeRequestId`), `preview`, `policy`, `change` (opened, then closed with its state),
`approval` (requested, decided, escalated), `step`, `changed` (each create, change or delete call:
provider, action, effect, target), `decision` (on a job that needed attention, with the edited
input) and `closed` (with the retention date). Commands that are not about a job (drafts, change
requests, publishes, provider changes, credentials by name and type, service accounts, release
decisions, builder sessions, retired resources) go on the platform stream, a chain of its own
whose entries carry `event`, `aggregate`, `aggregateId` and the SHA-256 of any file instead of the
file. Every entry is redacted before it is chained: secret-looking fields and values resolved from
Vault during the run become `***`, `vault:` references stay; job views are redacted the same way.

Evidence is kept in memory by default, as write-once files under `platform.audit.worm.dir`
(profile `worm`, each with a read-only retention sidecar) or as S3 objects under Object Lock in
COMPLIANCE mode for `retention-years` (profile `worm-s3`, `platform.audit.worm.s3.*`; startup fails
on a bucket without Object Lock). With profile `siem`, every entry also goes to Splunk HEC
(`platform.siem.hec.url`, the token a `vault:` reference) and to RFC 5424 syslog over UDP or TCP
(`platform.siem.syslog.host`), from a bounded queue that retries with backoff.

## Notifications

```ts
type Notification = { id; at; kind: 'notify' | 'provider.alert' | 'approval.requested' | 'approval.escalated';
  severity: 'info' | 'warning' | 'critical'; title; message; link?; channel?; jobId?; workflow?; provider?;
  users: string[]; teams: string[]; read: boolean }   // read is the caller's own
```

| GET `/notifications?unread=` | `{ items: Notification[]; unread: number }`: what was addressed to the caller or one of their teams, newest first; `unread=true` keeps the unread, `false` the read |
| POST `/notifications/{id}:read` | → `Notification` (404 when the caller cannot see it) |

Notify steps, provider alerts, approval requests (to the approver group) and escalations (to the
escalation group) go to every delivery adapter: the in-app inbox always, a Slack-compatible
incoming webhook per channel (profile `slack`, the URL a `vault:` reference) and email (profile
`mail`). `platform.notifications.channels` and `teams` say who a channel or an approver group
reaches; events older than `fresh-for` (seeded or replayed history) notify nobody. `notifications`
is a reserved noun.

## Resources

```ts
type ResourceType = { name; plural; owner; schema; states; producedBy? }
type Resource = { id; type; name?; status; attributes; owner; visibility; createdBy: { jobId; workflow }; builtFrom: string[]; createdAt }
```

| GET `/resource-types` | `ResourceType[]` |
| GET `/resource-types/{name}` | `ResourceType` |
| GET `/resources?type=&owner=&status=` | `Resource[]` |
| GET `/resources/{id}` | `Resource` |
| GET `/resources/{id}/lineage` | `{ resource; builtFrom: Resource[]; dependents: Resource[] }` |
| POST `/resources/{id}:retire` | → 409 with dependents list when something is built from it, else `CommandAccepted` |

## Credentials

| GET `/credentials` | `{ name; type; path; updatedAt }[]` (never values) |
| POST `/credentials` | `{ name; type; value }` → `CommandAccepted` (stored through `CredentialsPort`). Admins may store any credential; anyone else must be in a team, and a credential that a provider or a published workflow already uses can only be changed by a team that owns one of them (403 otherwise) |

## Assistant (natural-language builder)

| POST `/assistant/sessions` | `{ mode: 'create' \| 'edit'; workflow?; draftYaml? }` → `{ sessionId }` |
| POST `/assistant/sessions/{id}/messages` | `{ text }` → SSE events `{ type: 'status' \| 'draft' \| 'message' \| 'validation' \| 'done'; data }` |
| GET `/assistant/sessions/{id}` | `{ sessionId; status; draft; history }` |
| POST `/assistant/drafts` | `{ description }` → the final draft payload of a one-shot create session (what the `draft_workflow` MCP tool returns) |

The `draft` event carries the structured output in `devin/workflow-draft.schema.json` shape
(`status`, `summary`, `workflowYaml`, `changes`, `assumptions`, `questions`, `missing`,
`validation`, `dryRun`).

## Telemetry (outside `/api/v1`)

| GET `/actuator/health`, `/actuator/info` | liveness and build info |
| GET `/actuator/prometheus` | Prometheus scrape; `/actuator/metrics` lists the same meters |

Per workflow, for the jobs this instance runs (history seeded into the past counts for nothing):

| Meter (Prometheus) | Tags |
|--------------------|------|
| `workflow_jobs_started_total` | `workflow`, `version`, `channel`, `kind` (`single` or `batch`) |
| `workflow_jobs_finished_total` | the same and `status` |
| `workflow_jobs_duration_seconds` (histogram) | `workflow`, `version`; single runs only |
| `workflow_steps_failed_total` | `workflow`, `class` (the step's error class), one per failed attempt |

Every span is also a timer: `iaf_gateway_seconds`, `iaf_mcp_seconds`, `iaf_job_seconds`,
`iaf_step_seconds`, `iaf_provider_call_seconds`, with an `error_class` tag (`none` when it
succeeded) and an `…_active_seconds` long-task timer for what is still running. Tracing samples
every request and job (`management.tracing.sampling.probability: 1.0`); spans leave the process
only when `management.otlp.tracing.endpoint` (`MANAGEMENT_OTLP_TRACING_ENDPOINT`) names an OTLP
collector, which the demo leaves unset.
