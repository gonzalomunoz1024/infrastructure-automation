# API contract

Every platform endpoint lives under `/api/v1`. Generated workflow endpoints live under
`/api/v1/<noun>s…` and resource read endpoints under `/api/v1/<plural>`. The nouns `workflows`,
`jobs`, `approvals`, `providers`, `releases`, `audit`, `resources`, `resource-types`,
`credentials`, `registry`, `catalog`, `assistant`, `triggers`, `change-requests`, `templates`,
`service-accounts`, `me`, `mcp`, `openapi.json` are reserved for the platform.

Shapes below are TypeScript. Dates are ISO strings. Enums are lowercase strings. Errors are
`{ error: string }` with 400 (bad input), 401 (a bearer token that does not verify), 403 (RBAC),
404 (unknown id), 409 (state conflict), 500. Input that fails a workflow's schema or names a
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
| GET    | `/service-accounts`       | `{ id, name, roles, teams, createdAt, scopes }[]` |
| POST   | `/service-accounts`       | body `{ name, roles, teams?, scopes?, preset? }` → 201 `{ id, token, scopes }` (token shown once; admin only) |

Dev identity: header `X-User: <id>` (default `gonzalo`, admin, teams `team-compute`,
`team-platform`); the dev header is for local use only and is ignored under the `oidc` profile.
Service accounts: `Authorization: Bearer iaf_<token>`. OIDC profile: a JWT. A bearer token that
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
| `registry:read` | GET on the registry, catalog, OpenAPI, templates, workflows, providers, resource types, resources, change requests and releases; generated resource reads; MCP `tools/list` and the resource tools |
| `workflows:validate` | `/workflows:validate`, `:parse`, `:render`, `:replay-check` and `/providers:validate` |
| `workflows:dry-run` | `/workflows:dry-run` |
| `workflows:write` | creating, importing, editing, deleting and publishing workflows; merging and closing change requests; assistant sessions and drafts; the MCP draft tool |
| `jobs:start` | starting jobs (`/jobs`, `/jobs:batch`, generated routes, the MCP invoke, batch and cancel tools), steering them (cancel, retry, skip, abort, rerun) and `/triggers/*` |
| `jobs:read` | jobs and their events, replay and evidence; approvals; audit trails; the job WebSocket; the MCP job status tool |
| `providers:write` | the Add provider wizard (import spec, the Ansible and Terraform imports, test connection, try, publish, draft), refresh spec, overrides, SLO and `/credentials` |
| `admin` | everything, and makes the account an admin; an endpoint the table does not name needs it |

`/me`, `/mcp` (each tool is judged on its own) and GET `/actuator/*` need no scope.

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
| POST   | `/change-requests/{id}:merge`                | → `CommandAccepted` (publishes the version; starts the canary). The reviewer is not the author: 403 when the person who opened the change request merges it, unless they are an admin; the author may still close it. Same rule for workflow and provider change requests |
| POST   | `/change-requests/{id}:close`                | → `CommandAccepted` |
| POST   | `/change-requests:github-webhook`            | profile `github` only. GitHub's `pull_request` deliveries, signed with the webhook secret (`X-Hub-Signature-256: sha256=<HMAC-SHA256 of the body>`; the secret is the `vault:` reference `platform.github.webhook-secret`). A pull request merged on GitHub merges its change request, closed without merging closes it, as `github:<login>` → 200 `{ status; changeRequest }`. 401 when the signature is missing or wrong, 503 when the secret cannot be read, 409 when the platform refuses the merge (failed checks: the change request stays open). `ping` → 200 `{ status: 'pong' }`; other events and actions, and pull requests of no change request → 202 `{ status: 'ignored'; reason }`. No platform credential is needed or used |
| POST   | `/workflows:import`                          | `{ source: 'idp' \| 'ansible' \| 'terraform' \| 'openapi'; ref: string }` → `{ yaml; notes: string[] }` (200) |
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

## Catalog (generated)

```ts
type CatalogEntry = { workflow; title; noun; verb; persona?; method; path; batchPath; previewPath?;
  input: Schema; mcpTool?: string; owner; version; description? ; batch: { max; rollout } }
```

| Method | Path                                | Returns |
|--------|-------------------------------------|---------|
| GET    | `/catalog`                          | `CatalogEntry[]` |
| GET    | `/openapi.json` (also `/api/v1/openapi.json`) | OpenAPI 3.1 document of every published workflow and resource type |
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

## Providers

```ts
type Health = { status: 'healthy' | 'degraded' | 'failing' | 'unknown'; successRate; p50Ms; p95Ms; calls; errorTrend: number[]; reasons: string[] }
type ProviderSummary = { name; title; type; owner; version; environments: string[]; actionCount; health: Health; slo; alerts }
type ProviderDetail = ProviderSummary & { yaml; actions: ActionInfo[]; daily: { date; calls; successRate; p50Ms; p95Ms }[];
  perAction: { action; calls; successRate; p50Ms; p95Ms; topErrors: { class; count }[] }[]; changeRequests: ChangeRequest[] }
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
| POST   | `/providers:import-spec`                  | `{ type: 'openapi'; url? ; content? ; git?: GitRef }` → `SpecImport` (200). With `git` the file is read from the repository at the ref and `commit` says which commit that was; a `credential` that is not a `vault:` reference is a 400 (the token never goes through the API). Only https repositories are read, ssh URLs and credentials in the URL are refused |
| POST   | `/providers:import-ansible`               | `{ git: { repo; ref?; path?; credential? }; playbooks?: string[]; name?; owner?; credential?; executionEnvironment? }` → `AnsibleImport` (200). Reads the playbooks of the repository at the ref (under `git.path`; the whole repository when it is blank) and drafts an `ansible` provider with one action per playbook, pinned to the commit read. `playbooks` picks some by path or action id (default: all); `credential` is the Vault reference of the machine credential the playbooks connect with. 400 when there is no playbook, a playbook named is not one, or a credential is not a `vault:` reference |
| POST   | `/providers:import-terraform`             | `{ git?: { repo; ref?; path?; credential? }; registry?: { module; version?; credential? }; name?; owner?; credential? }` → `TerraformImport` (200). One of `git` (the module's folder at a ref) or `registry` (`namespace/name/provider` in the configured module registry; `version` is an exact version, default its newest release; `credential` a `vault:` registry token for a private module). Drafts a `terraform` provider with a `plan` and an `apply` action whose inputs are the module's variables. `credential` is the Vault reference of what the runner gives Terraform. 400 when the folder has no `.tf` files (the message names the module folders under it), the version or the module does not exist, or the registry serves an archive it does not describe |
| POST   | `/providers:test-connection`              | `{ endpoints; auth; provider? }` → `{ results: { environment; ok; durationMs; message }[] }` (200). With `provider` (the provider's name) the results are kept as review evidence. Link-local and cloud metadata addresses (169.254.0.0/16, fe80::/10, metadata.google.internal and the like) are refused here, in spec fetches and in provider calls, including through redirects and DNS |
| POST   | `/providers:try`                          | `{ yaml; action; environment; input }` → `TryResult` (200). Every try is kept as review evidence; one that went through comes back with `yaml`, the draft whose action has the response as its `sample` and `recorded: { environment, at }`. Fields named like secrets (token, password, api key...) are saved as `vault:redacted` |
| POST   | `/providers:validate`                     | `{ yaml }` → `{ checks: Check[]; problems: Problem[]; review: ProviderReview }` (200). `checks` and `problems` judge the file; `review` is null when the file is not a provider |
| POST   | `/providers:publish`                      | `{ yaml; strategy?: 'canary' \| 'all' }` → `CommandAccepted` (change request id). `strategy` defaults to `canary`, like a workflow version. 409 with the problems when the file has errors or the review is not ready, when the version is already published (or kept for a canary), and while another change request for the provider is open; the change request carries both sets of checks |
| POST   | `/providers:draft`                        | `{ description }` → `{ yaml; summary; assumptions[] }` (Ask Devin, `!provider-draft`) |
| POST   | `/providers/{name}:refresh-spec`          | `{ ref? ; content? }` (optional) → `CommandAccepted` (202). Reads the spec again from where the file says: the URL, Git at the file's ref (or at `ref`, which is then written to the file), or `content` for a pasted spec. The same document answers `status: 'unchanged'`. Otherwise it opens a change request for the next minor version: new `sha256` (and `commit`), the input and output schemas of the actions that call its operations, and each override dropped only when the new spec agrees with the action's real response (its last Try it answer, else a recorded sample). Checks: the file's, `spec-refresh` (what was read and what changed) and the review's `responses`. 409 when an operation an action calls is gone (problems `refresh.operationGone`) or another change request for the provider is open; 400 when `ref` or `content` do not fit the spec's source; owner team or admin |
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
type EvidenceRecord = { jobId; workflow; version; requester; channel; entries: EvidenceEntry[]; latestHash; sealed?: { at; signature; keyId };
  retention: { until }; change?: { id; url } }
```

| GET `/audit?workflow=&from=&to=&requester=` | `{ jobId; workflow; requester; channel; at; entries; status }[]` |
| GET `/audit/{jobId}` | `EvidenceRecord` |
| GET `/audit/{jobId}:verify` | `{ valid: boolean; entries; brokenAt?: number; seal?: 'verified' \| 'mismatch' \| 'unverifiable' }` |
| GET `/audit/{jobId}/export?format=json\|pdf` | file |
| POST `/audit:export-pack` | `{ filter }` → zip (signed manifest) |
| POST `/audit/{jobId}:share` | → `{ url; expiresAt }` |
| GET `/audit/shared/{token}` | read-only `EvidenceRecord` |
| POST `/audit:seal` | → `{ sealedAt; signature; keyId; sealed }` (also runs every `platform.audit.seal-every`) |

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
| POST `/assistant/playbooks:sync` | → `{ playbooks: { file; macro; playbookId; action: 'created' \| 'updated' \| 'unchanged' }[] }`. Creates the Devin playbooks of `devin/*.md` that Devin does not have (by macro) and updates the ones whose title, body or schema changed; also at startup with `platform.assistant.sync-playbooks-on-startup`. Admins only |

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
