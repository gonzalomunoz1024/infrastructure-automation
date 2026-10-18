# API contract

Every platform endpoint lives under `/api/v1`. Generated workflow endpoints live under
`/api/v<n>/<noun>s…` and resource read endpoints under `/api/v1/<plural>`. The nouns `workflows`,
`jobs`, `approvals`, `providers`, `releases`, `audit`, `resources`, `resource-types`,
`credentials`, `registry`, `catalog`, `assistant`, `triggers`, `change-requests`, `templates`,
`service-accounts`, `me`, `mcp`, `openapi.json`, `playbooks` are reserved for the platform.

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
| GET    | `/service-accounts`       | `{ id, name, roles, teams, createdAt, scopes }[]` (admin only; 403 otherwise) |
| POST   | `/service-accounts`       | body `{ name, roles, teams?, scopes?, preset? }` → 201 `{ id, token, scopes, teams, note? }` (token shown once; admin only). `teams` are the teams the account acts for: without any it takes its creator's, and `note` says so (or, when the creator has none either, that the policy refuses every job it starts) |

Shared evidence links (`GET /audit/shared/{token}`) need no credential: the token is the secret.

Dev identity: header `X-User: <id>` (default `gonzalo`, admin, teams `team-compute`,
`team-platform`); the dev header is for local use only and is ignored under the `oidc` profile.
Service accounts: `Authorization: Bearer iaf_<token>`. OIDC profile: a JWT of the configured issuer
and audience (README, [Identity and OIDC](../README.md#identity-and-oidc)). A bearer token that
does not verify is always 401; it never falls back to the dev identity. Signed webhooks
(`POST /change-requests:github-webhook`) carry no credential: they come in as an identity that may
change nothing, and their controller checks the signature before acting.

A service account is held to scopes besides its roles and teams: every call needs the scope of its
endpoint, and one outside the account's scopes is a 403 that names the scope it needs. `preset`
names a set: `invoker` (`registry:read`, `jobs:start`, `jobs:read`, also what an account created
without scopes gets), `assistant` (`registry:read`, `workflows:validate`, `workflows:dry-run`: the
builder agent's token, which can change nothing) and `ci` (`registry:read`, `workflows:validate`,
`workflows:dry-run`, `workflows:write`, `providers:write`); `scopes` given with a preset must fit
in it. An unknown scope or preset is a 400. People are judged by roles and teams alone.

| Scope | Allows |
|-------|--------|
| `registry:read` | GET on the registry, catalog, OpenAPI, templates, workflows, providers, resource types, resources, change requests and releases; the credential list (`GET /credentials`: names, types and owners, never a value); generated resource reads; MCP `tools/list` and the resource tools |
| `workflows:validate` | `/workflows:validate`, `:parse`, `:render`, `:replay-check` and `/providers:validate` |
| `workflows:dry-run` | `/workflows:dry-run` |
| `workflows:write` | creating, importing, editing, deleting and publishing workflows; merging and closing change requests; assistant sessions and drafts; the MCP draft tool |
| `jobs:start` | starting jobs (`/jobs`, `/jobs:batch`, generated routes, the MCP invoke, batch and cancel tools), steering them (cancel, retry, skip, abort, rerun) and `/triggers/*` |
| `jobs:read` | jobs and their events, replay and evidence; approvals; audit trails (`/audit`, `/audit/platform`, a record, its verification and export); the job WebSocket; the MCP job status tool |
| `providers:write` | the Add provider wizard (import spec, the Ansible and Terraform imports, test connection, try, publish, draft), refresh spec, overrides, SLO and storing credentials (`POST /credentials`) |
| `admin` | everything, and makes the account an admin; an endpoint the table does not name needs it |

`/me`, `/mcp` (each tool is judged on its own), GET `/actuator/*` and GET `/audit/shared/{token}` (the
token is the credential) need no scope. Sealing, sharing and the export pack need `admin`.

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
| POST   | `/workflows:validate`                        | `{ yaml }` → `{ valid; problems: Problem[] }` (200); also reports, as `publish.ansibleLint`, `publish.routes` and `publish.contracts` errors, the change-request checks the file would fail when published (docs/config-as-code.md, Checks) |
| POST   | `/workflows:dry-run`                         | `{ yaml; input }` → `DryRunResult` (200) |
| POST   | `/workflows/{name}:publish`                  | `{ strategy?: 'canary' \| 'all'; notes? }` → `CommandAccepted` (id = change request id). A run step without a `version` is written with `^<major>` of the workflow it runs. Checks: schema, checks, ansible-lint, policy, replay, contracts (a breaking change against the published version needs a new major version; the detail lists every affected consumer), diff, routes (no other published workflow answers the same invoke, batch or preview route) and yaml; merging re-checks the routes |
| POST   | `/workflows/{name}/versions/{version}:replay-check` | → `{ passed: boolean; replayed: number; divergences: { jobId; step; reason }[] }` (200) |
| GET    | `/change-requests?status=`                   | `ChangeRequest[]` |
| GET    | `/change-requests/{id}`                      | `ChangeRequest & { diff: string; yaml: string }` |
| POST   | `/change-requests/{id}:merge`                | → `CommandAccepted` (publishes the version; starts the canary). The reviewer is not the author: 403 when the person who opened the change request merges it, unless they are an admin; the author may still close it. Same rule for workflow and provider change requests |
| POST   | `/change-requests/{id}:close`                | → `CommandAccepted` |
| POST   | `/change-requests:github-webhook`            | profile `github` only. GitHub's `pull_request` deliveries, signed with the webhook secret (`X-Hub-Signature-256: sha256=<HMAC-SHA256 of the body>`; the secret is the `vault:` reference `platform.github.webhook-secret`). A pull request merged on GitHub merges its change request, closed without merging closes it, as `github:<login>` → 200 `{ status; changeRequest }`. 401 when the signature is missing or wrong, 503 when the secret cannot be read, 409 when the platform refuses the merge (failed checks: the change request stays open). `ping` → 200 `{ status: 'pong' }`; other events and actions, and pull requests of no change request → 202 `{ status: 'ignored'; reason }`. No platform credential is needed or used |
| POST   | `/workflows:import`                          | `{ source: 'idp' \| 'ansible' \| 'terraform' \| 'openapi'; ref: string }` → `{ yaml; notes: string[] }` (200): a valid draft started from a registered tile, playbook (`<provider>/<action>` or `<repo>#<ref>:<path>`), module or provider action. The draft takes a name and a route of its own when a workflow already has the natural ones (`create-vm-from-vsphere` on `POST /api/v1/vms:create-from-vsphere`), registers what it made only when a resource type is named like its noun, and the notes say what is left to fill in; 400 names the known refs |
| GET    | `/templates`                                 | `{ id; title; description; yaml }[]` |

```ts
type DryRunResult = { passed: boolean; path: string[]; steps: { id; name; status; input?; output?; error? }[]; error? }
```

With the `github` profile a change request is a pull request on the content repository
(`platform.github.repo`): opening it pushes the branch with its one file from `platform.github.base-branch`,
opens the pull request (title, the checks and the rollout strategy in its body) and posts one commit
status per check (`iaf/<check>`); `url` is the pull request. When GitHub cannot be reached the change
request is not opened (502). Merging or closing it on the platform merges (`platform.github.merge-method`)
or closes the pull request; when GitHub refuses the merge (a required review or status missing) it is a
409 and nothing is published. A decision taken on GitHub comes back through the webhook above and is
never sent back to GitHub. Reviews of pull requests merged on GitHub are GitHub's to require (branch
protection, code owners); the platform's checks still decide whether the version is published.

Registry (read-only, used by the designer and the Devin playbook):

| GET `/registry/step-types` | `{ type; title; category; schema; description }[]` |
| GET `/registry/providers` | `{ name; title; type; actions: { id; title; effect; retry; approval; input: Schema; output: Schema; sample? }[] }[]` |
| GET `/registry/workflows?published=true` | `{ name; title; version; input: Schema; output: Schema }[]` |
| GET `/registry/workflows/search?q=` | up to 5 `WorkflowSummary` |
| GET `/registry/resource-types` | `ResourceType[]` |
| GET `/registry/playbooks` | `{ name; version; path; owner }[]` |
| GET `/registry/credentials` | `{ name; type }[]` (names only) |
| GET `/registry/dependencies` | `Dependencies`: who produces and consumes each resource type, which published workflows run which, and the cycles among runs |

```ts
type DependencyLink = { workflow; via: 'producedBy' | 'register' | 'input' | 'use' | 'run' | 'orCreateWith';
  at?: string /* step id or input field */; range?: string /* a run step's version range */ }
type Dependencies = {
  resourceTypes: { type; producers: DependencyLink[]; consumers: DependencyLink[] }[];
  workflows: { workflow; version; produces: string[]; consumes: string[]; runs: DependencyLink[]; consumers: DependencyLink[] }[];
  cycles: string[][]   // e.g. ['a', 'b', 'a']: a runs b, b runs a
}
```

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
| *      | generated `method path`             | body = input → 202 `{ jobId; status: 'queued'; links: { job } }`; 400 with `{ error, problems }` on schema failure, an `x-resource` id the registry does not know, or a `{ create }` request the producing workflow's input refuses (`<field>.create.<field>`); 403 on RBAC |
| POST   | `batchPath`                         | `{ items: object[]; defaults?: object; rollout?: { waves; maxParallel; stopIfFailedPercent } }` → 202 `{ jobId; rows; status; links }`; 400 lists invalid rows `{ error, rows: { index; problems }[] }`. A path that ends in a parameter batches on the collection, so every row brings its own id: `/vms/{vmId}:restart` → `/vms:restart:batch`, `DELETE /vms/{vmId}` → `/vms:delete:batch`; any other path adds `:batch` (`/vms` → `/vms:batch`). Earlier path parameters apply to every row. Every row is checked against the schema and the registry before anything starts |
| POST   | `{path}:preview`                    | body = input → `Preview` (200); judged like a run: the same 400s for the schema and for an `x-resource` id the registry does not know, 403 on RBAC |
| GET    | `/{plural}` and `/{plural}/{id}`    | resources of a type |
| POST   | `/mcp`                              | JSON-RPC 2.0: `initialize`, `ping`, `tools/list`, `tools/call`, `notifications/initialized` |

```ts
type Preview = { workflow; version; steps: { id; name; effect: 'create' | 'change' | 'delete' | 'read' | 'none' | 'unknown'; detail?; skipped?: true }[];
  affectedResources: { id; type; name; via }[]; cost?: { monthly; currency; detail };
  policy: { decision: 'allow' | 'deny' | 'needs-approval'; rules: { id; result; message }[]; engine: 'mock' | 'opa' } }
```

A preview is the flow judged by a dry run on the input (a batch's first row): `skipped` marks a
step the dry run does not take (effect `none`), and a step's `detail` ends with what its plan said
(`vsphere vm.delete (destructive) · plan vm.get: web-1042 (vm-1042)`). `via` is `input.<field>`,
`step <id>` for a resource a `use` step reads, or `built from <id>` for what the registry's lineage
puts below a resource the run changes or deletes. `cost.detail` names every priced create call and
who priced it (`vsphere vm.create 38.50 x 3 (price list list-prices)`; `plan <action>`, `sample`),
then `x <n> rows` for a batch (docs/config-as-code.md, Previews).

An `x-resource` input with `orCreateWith` shows both forms in `CatalogEntry.input`, the OpenAPI
request bodies and the MCP tool inputs: `anyOf` the id, or `{ create: <the producing workflow's
input> }` to have the resource built first (docs/config-as-code.md, Resource inputs).

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
  preview?: Preview; change?: { id; url; state }; children?: JobSummary[]; batch?: Batch; attention?: Attention; traceId;
  links?: { temporal? } }   // temporal: the job's execution in the Temporal UI (temporal profile with platform.temporal.ui-url set)
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
| GET    | `/jobs/{id}/replay?compareTo=`         | `{ steps: (JobStep & { diff?: { field; before; after }[] })[]; comparedTo?: string }`; without `compareTo` the job is compared with the last successful run of the same workflow before it |
| GET    | `/jobs/{id}/evidence`                  | `EvidenceRecord` (alias of `/audit/{jobId}`) |
| POST   | `/jobs`                                | `{ workflow; version?; input; channel?; traceId?; changeId? }` → `CommandAccepted` (the generic way to start a job; the catalog routes do the same with the workflow's own path) |
| POST   | `/jobs:batch`                          | `{ workflow; version?; items; defaults?; rollout?; channel? }` → `CommandAccepted` (links.rows = row count) |
| POST   | `/jobs/{id}:cancel`                    | → `CommandAccepted`; 403 for anyone who may not steer the job (below) |
| POST   | `/jobs/{id}:retry`                     | `{ step?; input? }` → `CommandAccepted` (needs-attention retry, optionally with edited input); 403 as cancel |
| POST   | `/jobs/{id}:skip`                      | `{ step }` → `CommandAccepted`; 403 as cancel |
| POST   | `/jobs/{id}:abort`                     | → `CommandAccepted` (abort and roll back); 403 as cancel |
| POST   | `/jobs/{id}:rerun`                     | `{ fromStep; input? }` → `CommandAccepted` (id = the new job) |
| POST   | `/jobs/{id}:retry-failed-rows`         | → `CommandAccepted` (batch) |
| GET    | `/approvals?status=pending`            | `Approval[]` |
| GET    | `/approvals/{id}`                      | `Approval` |
| POST   | `/approvals/{id}:approve` / `:reject`  | `{ comment? }` → `CommandAccepted` |
| POST   | `/triggers/alerts`                     | alert payload `{ source; labels; annotations }` → 202 `{ started: { workflow; jobId }[]; skipped: { workflow; reason }[] }` |
| POST   | `/triggers/events`                     | `{ topic; payload }` → 202, same body |

Steering a job (`:cancel`, `:retry`, `:skip`, `:abort`) is for its requester, a member of the
workflow's owner team, anyone the workflow's `policy.rbac` roles or approver groups name, and
admins; anyone else gets 403 with a message naming the job. `:rerun` is judged like a start
(`policy.rbac`), and approvals by their group.

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
type ProviderDetail = ProviderSummary & { endpoints: { [environment]: baseUrl }; auth?: { type; credential?: 'vault:…'; header? };
  yaml; actions: ActionInfo[]; daily: { date; calls; successRate?; p50Ms?; p95Ms? }[];
  perAction: { action; calls; successRate; p50Ms; p95Ms; topErrors: { class; count }[] }[]; changeRequests: ChangeRequest[] }
// daily: the last 14 days as 24-hour windows ending now, oldest first; `date` is the instant a window starts.
// The newest window covers the same calls as `health`. A window without calls has no rates.
// auth.credential is the vault reference's name, never a value.
type ActionInfo = { id; title; operation; effect; retry; approval; plan?; input: Schema; output: Schema; sample?: object; overridden: boolean;
  effectDetail?; recorded?: { environment; at } }
type SpecImport = { title; version; sha256; auth: { type; where? }; servers: string[];
  operations: { operationId; method; path; summary; effect; retry; input: Schema; output: Schema }[];
  commit? }   // commit: the commit a spec read from Git came from; it goes in the file's spec.commit
type GitRef = { repo; ref?; path; credential? }   // repo: https URL; ref: branch, tag or commit (default main); credential: vault:<path> of a read token
type AnsibleImport = { repo; ref; commit; playbooks: Playbook[]; chosen: string[]; requirements: string[]; skipped: string[]; notes: string[]; yaml }
type Playbook = { path; action; title; effect; roles: string[]; vars: Schema; sources: { [variable: string]: string }; secrets: string[];
  description?; notes: string[] }   // sources: where each input came from; secrets: variables kept out of vars
type TerraformImport = { source; version; commit?; notes: string[]; yaml;
  variables: { name; type; required; sensitive; secret; description?; default?; file }[];   // secret: never an input
  outputs: { name; description?; sensitive; file }[] }
type TryResult = { ok: boolean; status; durationMs; response; mismatches: { field; expected; actual }[]; verified?: boolean;
  environment; at; yaml? }   // yaml: the draft with the response saved as the action's sample, when the call went through
type ProviderReview = { ready: boolean; checks: Check[]; problems: Problem[];
  slo?: { successRate; p95Ms; observedP95Ms; calls; detail } }   // slo is a suggestion, never enforced
```

The review judges a provider file against what the platform itself saw in the wizard, kept per
provider name: the Test connection results sent with `provider`, and every Try it call. Its checks
come after the file's own checks on the change request:

| Check | Fails when |
|-------|------------|
| `connection` | the last Test connection to `dev` or to `test` (the ones the provider declares; its first environment when it has neither) is missing, failed, or used another endpoint or credential reference than the file has now. A missing `test` endpoint is a warning. openapi and http providers only |
| `tried` | an action never answered when tried against `dev` (its first environment without one). A delete whose `verify` still found the thing is a warning. openapi and http providers only |
| `errors` | an error class has no `match` rule, or (openapi, http) no `status` in it. A tried error that no class matches, or no error classes at all, is a warning |
| `preview` | a destructive action has neither a `plan` action nor an `effectDetail` line |
| `responses` | an action's last real response still disagrees with its schema after the `overrides` |
| `slo-suggested` | never: it suggests `p95Ms` as 1.5 times the tried calls' p95, rounded up to 50 ms (at least 100), and warns when the file's p95 is below what the tries took |

| Method | Path                                      | Body / Returns |
|--------|-------------------------------------------|----------------|
| GET    | `/providers`                              | `ProviderSummary[]` |
| GET    | `/providers/{name}`                       | `ProviderDetail` |
| GET    | `/providers/{name}/alerts`                | `{ id; at; action; message; severity; jobId?; channel? }[]` |
| POST   | `/providers:import-spec`                  | `{ type: 'openapi'; url? ; content? ; git?: GitRef }` → `SpecImport` (200). With `git` the file is read from the repository at the ref and `commit` says which commit that was; a `credential` that is not a `vault:` reference is a 400 (the token never goes through the API). Only https repositories are read, ssh URLs and credentials in the URL are refused. 403 when the caller may not use that credential (see Credentials) |
| POST   | `/providers:import-ansible`               | `{ git: { repo; ref?; path?; credential? }; playbooks?: string[]; name?; owner?; credential?; executionEnvironment? }` → `AnsibleImport` (200). Reads the playbooks of the repository at the ref (under `git.path`; the whole repository when it is blank) and drafts an `ansible` provider with one action per playbook, pinned to the commit read. `playbooks` picks some by path or action id (default: all); `credential` is the Vault reference of the machine credential the playbooks connect with. 400 when there is no playbook, a playbook named is not one, or a credential is not a `vault:` reference; 403 when the caller may not use `git.credential` or `credential` (see Credentials) |
| POST   | `/providers:import-terraform`             | `{ git?: { repo; ref?; path?; credential? }; registry?: { module; version?; credential? }; name?; owner?; credential? }` → `TerraformImport` (200). One of `git` (the module's folder at a ref) or `registry` (`namespace/name/provider` in the configured module registry; `version` is an exact version, default its newest release; `credential` a `vault:` registry token for a private module). Drafts a `terraform` provider with a `plan` and an `apply` action whose inputs are the module's variables. `credential` is the Vault reference of what the runner gives Terraform. 400 when the folder has no `.tf` files (the message names the module folders under it), the version or the module does not exist, or the registry serves an archive it does not describe; 403 when the caller may not use a credential named (see Credentials) |
| POST   | `/providers:test-connection`              | `{ endpoints; auth; provider? }` → `{ results: { environment; ok; durationMs; message }[] }` (200). With `provider` (the provider's name) the results are kept as review evidence. Link-local and cloud metadata addresses (169.254.0.0/16, fe80::/10, metadata.google.internal and the like) are refused here, in spec fetches and in provider calls, including through redirects and DNS. 403 when the caller may not use `auth.credential` (see Credentials) |
| POST   | `/providers:try`                          | `{ yaml; action; environment; input }` → `TryResult` (200). Every try is kept as review evidence; one that went through comes back with `yaml`, the draft whose action has the response as its `sample` and `recorded: { environment, at }`. Fields named like secrets (token, password, api key...) are saved as `vault:redacted`. 403 when the caller may not use a credential the draft names (see Credentials) |
| POST   | `/providers:validate`                     | `{ yaml }` → `{ checks: Check[]; problems: Problem[]; review: ProviderReview }` (200). `checks` and `problems` judge the file; `review` is null when the file is not a provider |
| POST   | `/providers:publish`                      | `{ yaml; strategy?: 'canary' \| 'all' }` → `CommandAccepted` (change request id). `strategy` defaults to `canary`, like a workflow version. 409 with the problems when the file has errors or the review is not ready, when the version is already published (or kept for a canary), and while another change request for the provider is open; the change request carries both sets of checks |
| POST   | `/providers:draft`                        | `{ description }` → `{ yaml; summary; assumptions[] }` (Ask Devin, `!provider-draft`) |
| POST   | `/providers/{name}:refresh-spec`          | `{ ref? ; content? }` (optional) → `CommandAccepted` (202). Reads the spec again from where the file says: the URL, Git at the file's ref (or at `ref`, which is then written to the file), or `content` for a pasted spec. The same document answers `status: 'unchanged'`. Otherwise it opens a change request for the next minor version: new `sha256` (and `commit`), the input and output schemas of the actions that call its operations, and each override dropped only when the new spec agrees with the action's real response (its last Try it answer, else a recorded sample). Checks: the file's, `spec-refresh` (what was read and what changed) and the review's `responses`. 409 when an operation an action calls is gone (problems `refresh.operationGone`) or another change request for the provider is open; 400 when `ref` or `content` do not fit the spec's source; owner team or admin, who must also be allowed to use the file's `git.credential` (403 otherwise, see Credentials) |
| POST   | `/providers/{name}/overrides`             | `{ action; response }` → `CommandAccepted` (202). "Use the real response": opens a change request for the next patch version whose file has the schema inferred from `response` merged into `overrides.<action>.response`, with the file's checks and the review's `responses`; `status: 'unchanged'` when the override would add nothing |
| PUT    | `/providers/{name}/slo`                   | `{ successRate?; p95Ms?; channel? }` → `CommandAccepted` (202). Opens a change request for the next patch version with the new SLO and alert channel (a blank `channel` removes it), with the file's checks and the review's `slo-suggested`; `status: 'unchanged'` when nothing changes |

Publish, refresh spec, overrides and the SLO all change a provider through a change request, one
open at a time per provider: a second one is a 409 that names the open change request, so merging
one can never publish over another. Merging publishes the provider: a first version, or one whose
change request says `strategy: 'all'`, becomes the current version at once; otherwise the version
is kept beside the current one and a provider release rolls it out (see Releases). A spec refresh
rolls out through a canary; overrides and SLO edits change no call and use `all`, unless a canary
is still running, in which case they build on its candidate and roll out as a canary too.
`provider.published` carries `{ name; version; previous?; strategy; by }`.

The two imports draft a provider file from what a repository holds and save nothing: the draft
goes through Validate and Publish like a file written by hand, and `notes` says what is left to do
(the credentials, the execution environment, what the import could not read). They read Git like
Import spec does (https only, a private repository names a `vault:` read token, at most 2000 files
of up to 1 MB and 32 MB in all) and pin the commit they read, so a later push changes nothing
until a new version is published. When a provider of that name exists, the draft is its next
minor version, which only its owner team may publish.

- Ansible: a playbook is a YAML list of plays, each with `hosts` or `import_playbook`, outside the
  folders of roles, variables and tests. Its action id is `<noun>.<verb>` from its path
  (`vm/restart.yml` is `vm.restart`), its title the name of its first play (else its file's), its
  effect read from its name (`decommission-vm.yml` is a delete, with `approval: required` and an
  `effectDetail`; a playbook whose plays all run in `check_mode` is a read). Its `vars` come from
  the roles' `meta/argument_specs.yml`, the play `vars` and `vars_prompt`, the roles' `defaults`
  and what the playbook uses in Jinja without giving it a value: a required string, unless
  `default(...)` or `is defined` guards it. What `group_vars` and `host_vars` set is not an input.
  Roles are found in the playbook's `roles` folder, the `roles_path` of `ansible.cfg` and the
  repository's `roles`; one from a collection or from requirements.yml is only noted.
- Terraform: every variable of the `.tf` and `.tf.json` files becomes an input of both actions
  (required ones first, a `validation` with `contains([...], var.x)` as an enum); Apply returns
  the non-sensitive outputs. A registry module is read from the Git repository the registry
  downloads it from, without a credential, else from the registry's description of it; the
  registry token only goes to the registry.

A variable that is sensitive, or named like a secret (password, token, api key...), is never an
input: `secrets` (Ansible) and `secret` (Terraform) list it, and the runner gives it from Vault.

## Releases

```ts
type Release = { id; workflow?; provider?; from?; to; strategy; status: 'canary' | 'paused' | 'promoted' | 'rolled-back';
  stageIndex; stages: number[]; stagePercent; startedAt; stageStartedAt; endedAt?;
  gates: { id; label; passed: boolean | null; value?; threshold?; detail }[]; jobs: { current; candidate }; history: { at; event; by? }[] }
```

A release rolls out a workflow version (`workflow`) or a provider version (`provider`). A provider
release has the same stages (10%, 50%, 100%) and gates, judged over the provider's calls instead
of jobs: the telemetry samples of each version since the stage started, without the `dev`
environment (`jobs` then counts calls, and the gate details say calls). Which version a call uses
is decided per job: `dev` and dry runs use the candidate at once (sandbox workflows see the new
actions right after the merge), a stable hash of the release and the job id puts the stage's share
of the other jobs on the candidate (every call of one job uses one version), and the rest, calls
outside a job and every job while the release is paused use the current version. Promotion makes
the candidate the provider's current version; a rollback leaves the current version in place. A
newer provider version ends a provider release still running (`rolled-back`, "superseded by").
The commands need edit rights on the provider's owner team. Release events (`release.started`,
`release.stage`, `release.promoted`, `release.paused`, `release.resumed`, `release.rolled-back`)
carry `{ releaseId; workflow | provider; from; to }`.

| GET `/releases?workflow=&provider=&kind=` | `Release[]`, newest first: a workflow's or a provider's releases, else every release of `kind` (`workflow`, the default, `provider` or `all`; anything else is a 400) |
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
| POST `/audit:export-pack` | `{ filter }` → zip (one JSON per record, the platform stream, signed manifest), sent in chunks with its `Content-Length`: the pack is written to a temporary file first (so a failure is an error status, never half a zip) and deleted once sent |
| POST `/audit/{jobId}:share` | → `{ url; expiresAt }`, a link valid for seven days. By the job's requester, a member of the teams it ran for (the requester's teams when it started, the workflow's owner team) or an admin; 403 otherwise. Published as `audit.shared` `{ recordId; by; expiresAt }`, never with the token |
| GET `/audit/shared/{token}` | read-only `EvidenceRecord`; needs no account in any mode (the token is the secret: a request without `Authorization` runs as an identity with no team, role or scope), 404 for an unknown token, 410 once it expired |
| POST `/audit:seal` | → `{ sealedAt; signature; keyId; sealed; checkpointed }`: closed records get their final seal, open ones (and the platform stream) a checkpoint over their head (also runs every `platform.audit.seal-every`). Admins only; 403 otherwise |

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
type ResourceType = { name; plural; owner; visibility: 'team' | 'public'; schema; states; producedBy? }
type Resource = { id; type; name?; status; attributes; owner; visibility; createdBy: { jobId; workflow }; builtFrom: string[]; createdAt }
```

| GET `/resource-types` | `ResourceType[]` |
| GET `/resource-types/{name}` | `ResourceType` |
| GET `/resources?type=&owner=&status=` | `Resource[]` |
| GET `/resources/{id}` | `Resource` |
| GET `/resources/{id}/lineage` | `{ resource; builtFrom: Resource[]; dependents: Resource[]; nodes: Resource[]; edges: { from; to; via? }[] }`. `builtFrom` and `dependents` are the direct neighbours; `nodes` and `edges` are the whole graph around the resource in both directions, each resource once, at most 32 hops away and 1000 resources in all, the resource itself first. An edge `from → to` says `to` was built from `from`, `via` the workflow that registered `to` when it is known |
| POST `/resources/{id}:retire` | → 403 unless the caller is in the resource's owner team or an admin (a resource without an owner is anyone's); 409 `resource has dependents` with `detail: Resource[]`, everything still built from it, directly or through other resources, depth-first; else `CommandAccepted` |

A `register` step creates a resource owned by the requester's first team (the type's `owner` when
they have none), with the type's `visibility`, and its attributes must match the type's schema
when the type declares one (400 otherwise). Registering an id that exists updates it: only the
resource's owner team, the job that created it (so a workflow that registers `building` and later
`ready` finishes its own work) or an admin may (403 to anyone else), and the attributes the
resource would then hold must still match the schema.

## Credentials

| GET `/credentials` | `{ name; type; path; owner?; updatedAt }[]` (never values). `owner` is the team whose members may use the credential; a credential without one (seeded from content) is any team's |
| POST `/credentials` | `{ name; type; value; owner? }` → `CommandAccepted` (stored through `CredentialsPort`). `owner` is one of the caller's teams (an admin may name any team; another team is a 403); omitted, it stays what it was for a credential that exists, else it is the caller's first team. Admins may store any credential; anyone else must be in a team, a credential another team owns can only be changed by that team or an admin, and one that a provider or a published workflow already uses can only be changed by a team that owns one of them (403 otherwise) |

A credential leaves the platform only on behalf of its owner team. Every wizard and import call
that would send a `vault:` reference outward checks first that the caller is an admin, that the
credential has no owner, or that the caller is in the owning team: `:test-connection`
(`auth.credential`, to each endpoint), `:import-spec` and `/providers/{name}:refresh-spec` (the
`git.credential` read token, to the repository), `:import-ansible` and `:import-terraform` (the
read token, the registry token, and the `credential` the draft names) and `:try` (every credential
the draft resolves: its `auth`, the read token of a spec or a playbook source kept in Git). A refusal
is a 403 that names the credential and its owner team, before the secret is resolved. A name the
store does not know is judged where it is resolved, as before (a 400, or a probe that failed), and
never says whether a credential exists for another team.

## Playbooks

```ts
type PlaybookUpload = { file: string; sha256: string; size: number; entries?: string[]; lint: 'passed' | 'skipped' }
type ResolvedRef = { repo: string; ref: string; commit: string }
```

| POST `/playbooks:upload` | multipart `file` (`.yml`, `.yaml`, `.zip`, `.tar.gz`) and optional `path` (the playbook to run inside an archive) → 201 `PlaybookUpload`; 400 naming the file and line when a text file of the upload holds what looks like a secret (never the value), or with the linter's output when `platform.playbooks.lint-command` refuses it |
| GET `/playbooks:resolve?repo=&ref=&credential=` | → `ResolvedRef`: the commit a tag (annotated tags peeled), branch, `HEAD` or full commit id names now. 403 naming the credential and its team when the caller may not use `credential` (see Credentials); the same rule judges the publishing identity when a publish pins a git playbook with a credential |

Both need a team (or admin), 403 otherwise. An upload is checked before it is kept: every
archive entry is a relative path without `..`, backslashes or control characters; links,
devices and duplicate names are refused; the entry count and the bytes it unpacks to are
capped while reading (`platform.playbooks.max-entries`, `max-unpacked-size`); `path` must be a
`.yml`/`.yaml` file in the archive; a single playbook, and the one `path` names, must parse as a
YAML list of plays. Every text file of the upload is then scanned for secrets: a private key
block; a key named like a secret (`password`, `token`, `secret`, `api_key`, `access_key`,
`credentials`, `authorization`, ...) with a literal value that is neither a `vault:<name>`
reference, an expression (`{{ ... }}`, `${...}`) nor an ansible-vault block; a long high-entropy
value after a weaker key (`key`, `auth`, `pass`, `cert`); and the shapes of AWS, GitHub and Slack
tokens anywhere. A finding is a 400 that names the file and the line, never the value. When
`platform.playbooks.lint-command` is set (for example `ansible-lint --offline {playbook}`) it
runs in the unpacked upload and a non-zero exit is a 400 with its output; the answer's `lint`
says `passed`, or `skipped` when no linter is configured. A file over
`platform.playbooks.max-upload-size` is a 413. The bytes are kept once in the artifact store under
their SHA-256, which a workflow pins as `playbook: { upload: { file, sha256, path? } }`; each
upload is a `playbook.uploaded` event.

`repo` is an https URL or `host/path` (https is implied); ssh, plain http, credentials in the
URL and link-local or cloud metadata hosts are 400. `credential` is a `vault:<path>` holding
`<user>:<token>` or a bare token; it is sent only to the repository's own host, and redirects
to another host are not followed. An unknown repository or ref is 404, a remote that cannot be
read 502.

## Assistant (natural-language builder)

| POST `/assistant/sessions` | `{ mode: 'create' \| 'edit'; workflow?; draftYaml? }` → `{ sessionId }` |
| POST `/assistant/sessions/{id}/messages` | `{ text }` → SSE events `{ type: 'status' \| 'draft' \| 'message' \| 'validation' \| 'done'; data }` |
| GET `/assistant/sessions/{id}` | `{ sessionId; status; draft; history }` |
| POST `/assistant/drafts` | `{ description }` → the final draft payload of a one-shot create session (what the `draft_workflow` MCP tool returns) |
| POST `/assistant/playbooks:sync` | → `{ playbooks: { file; macro; playbookId; action: 'created' \| 'updated' \| 'unchanged' }[] }`. Creates the Devin playbooks of `devin/*.md` that Devin does not have (by macro) and updates the ones whose title, body or schema changed; also at startup with `platform.assistant.sync-playbooks-on-startup`. Admins only |

A session belongs to the identity that started it: only that identity reads it or sends it
messages, plus an admin who is a person; anyone else gets 403 naming the session and who
started it. A service account, admin or not, only uses the sessions it started itself. A session
nothing happened on for `platform.assistant.session-retention` (default 30 days) is deleted, and
then answers 404.

A message that asks what the workflow does ("Explain what this workflow does", "describe it",
"summarise the flow") changes nothing: the answer is a `message` event describing the draft on the
canvas (what starts it, each step in order with what it calls, the approvals, the outputs) and no
`draft` event follows. Devin gets it as `MODE: explain`.

Closing the stream before its `done` (the panel closed, the page left) ends the agent's own session:
with Devin, the platform ends and archives it (`DELETE /sessions/{id}?archive=true`) so it stops
spending ACUs, and so it does when `platform.assistant.devin-timeout` passes without a draft (the
stream then ends with a message, a `status` whose `externalId` is empty, and a failed `done`). The
next message on the builder session starts a new Devin session with the current draft.

The `draft` event carries the structured output in `devin/workflow-draft.schema.json` shape
(`status`, `summary`, `workflowYaml`, `changes`, `assumptions`, `questions`, `missing`,
`validation`, `dryRun`).

With Devin (profile `devin`) the platform checks every draft Devin writes itself: the workflow
checks against the live catalog, then a dry run on sample input when they pass. The `draft` event
carries the platform's `validation: { valid; rounds; errors }` (each error `code @step: message`)
and `dryRun: { passed; path; error? }` in place of Devin's, and the `validation` event that follows
lists the problems on their steps. When Devin calls a draft ready and the platform still finds
errors, they go back on Devin's session as a `PLATFORM_CHECKS` message, for up to
`platform.assistant.max-rounds` rounds (default 3): the draft stays `working` meanwhile, and one
that still fails after the last round is `failed`, with a message that it needs a person.

Builder sessions and provider drafts publish events (aggregate `assistant-session`, id the session
or the provider draft) that carry hashes and counts, never the YAML:

| Event | Data |
|-------|------|
| `assistant.started` | `{ draftId; user; kind: 'workflow-draft' \| 'provider-draft'; agent; workflow?; externalId?; mode }` (provider drafts: `description`); `externalId` is Devin's session |
| `assistant.message` | the same ids and `{ role: 'user' \| 'assistant'; text }`, the text clipped to 2000 characters |
| `assistant.draft-updated` | the same ids and `{ status; contentHash; valid; errors }` (provider drafts: `contentHash; questions`), on every draft |
| `assistant.ready` | the same, once a session ends on a ready draft (a provider draft with YAML and no questions) |
| `assistant.playbooks-synced` | `{ agent; playbooks }`, after `POST /assistant/playbooks:sync` |

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
