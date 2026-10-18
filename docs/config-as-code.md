# Config as code

Four kinds of file, all `apiVersion: iaf.platform/v1`. The designer, the natural-language
builder and a text editor all produce the same files; the canvas is a view of the YAML.

## kind: Workflow

```yaml
apiVersion: iaf.platform/v1
kind: Workflow
metadata:
  name: vm-restart            # unique, kebab-case; the file is workflows/<team>/<name>.yaml
  title: VM restart
  noun: vm                    # with verb, drives the generated path and the MCP tool name
  verb: restart
  persona: ops
  owner: team-compute
  folder: Compute
  tags: [vsphere, ops]
  version: 1.2.0              # semantic version of this file
api:
  method: POST                # inferred from the verb when omitted: create POST, read GET,
  path: /api/v1/vms/{vmId}:restart   # update PUT, delete DELETE, anything else POST <noun>s:<verb>
  input:                      # JSON Schema; also the MCP tool input schema and the catalog form
    type: object
    required: [vmId]
    properties:
      vmId: { type: string, title: VM id, x-resource: { type: vm } }   # picked from the registry
      reason: { type: string, examples: [kernel patch, memory leak] }  # examples feed the form, Try it and the demo history
      image: { type: string, x-resource: { type: image, orCreateWith: build-vm-image } }
  response:
    status: 202               # always 202 with { jobId }
    body:                     # optional JSON Schema of the job's output (the keys of workflow.outputs);
      type: object            # OpenAPI publishes it as <tool>_output and the job read as <tool>_job
      properties: { powerState: { type: string } }
  preview: true               # publish a :preview operation (blast radius before a run)
  batch:                      # bulk runs (always available; this block sets the owner's defaults)
    max: 500
    rollout: { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 }
mcp:
  expose: true
  description: Restart a virtual machine after safety checks
policy:
  approval: { required: true, group: compute-approvers, timeout: 4h }
  rbac: [ops, sre]            # roles or teams that may invoke (admins always may; empty: anyone)
  change: { required: true }  # a ServiceNow change is opened for every job
triggers:                     # optional extra ways in besides the API and MCP
  - alert: { match: { source: grafana, labels: { severity: critical } } }
  - alert: { match: { source: platform, labels: { provider: vsphere, class: timeout } } }   # the platform's own provider alerts
  - event: { topic: vm.created }
rollout:
  strategy: canary            # canary | all
  stages: [10, 50, 100]
workflow:
  do:
    - checkVmExists:
        call: provider
        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.vmId }}" } }
        output: { as: vm }    # also reachable as $('checkVmExists').json
    - approval:
        approval: { group: compute-approvers, message: "Restart {{ $vm.name }}?", timeout: 4h }
    - noBackupRunning:
        call: provider
        with: { provider: backup, action: jobs.list, input: { vm: "{{ $input.vmId }}", state: running } }
        assert: "{{ $json.items.length == 0 }}"
    - restart:
        call: ansible
        with:
          playbook: { git: { repo: github.com/acme/ops, ref: v3.1.0, path: vm/restart.yml }, commit: 4e1d9a2 }
          inventory: dynamic
          limit: "{{ $input.vmId }}"
          extraVars: { vm_id: "{{ $input.vmId }}" }
          credential: vault:ssh/ops-automation
          executionEnvironment: ee-platform:2.17
          checkModeOnTest: true
        retry: { maxAttempts: 1 }    # playbooks are not always safe to run twice
        timeout: 30m
    - validate:
        call: provider
        with: { provider: vsphere, action: vm.waitForPowerState, input: { id: "{{ $input.vmId }}", state: poweredOn } }
        timeout: 10m
        pauseOnFailure: true   # default; false fails fast
  catch:                      # error branch for the whole flow (also allowed per step with try)
    do:
      - notifyOwner:
          notify: { channel: "#compute-alerts", message: "VM restart {{ $job.id }} failed: {{ $error.message }}" }
  outputs:
    powerState: "{{ $('validate').json.powerState }}"
```

### The generated operation

`api.path` lives under `/api/v<n>/` (`/api/v1/`, `/api/v2/`, …), so a second version of an
operation can sit next to the first; the platform's own nouns (`jobs`, `audit`, `notifications`,
…) are reserved under every version. The operation always answers 202 with the job id.

`api.response.body` is the JSON Schema of what the job holds in `output` once it succeeds: an
object with the keys of `workflow.outputs`. The checks refuse a body that is not an object schema
(`api.responseBody`) and warn about a property that `workflow.outputs` never sets
(`api.responseOutput`). OpenAPI publishes it as `<noun>_<verb>_output`, and the job read with that
output as `<noun>_<verb>_job`.

`api.deprecated` keeps an operation answering while its successor takes over:

```yaml
api:
  path: /api/v1/vms/{vmId}:restart
  deprecated:
    since: 2026-11-01                          # YYYY-MM-DD or an ISO date-time
    sunset: 2027-05-01                         # after since
    replacement: /api/v2/vms/{vmId}:restart    # optional: a path under /api/v<n>/ or a URL
    message: Use the v2 restart                # optional
```

Every answer then carries `Deprecation`, `Sunset` and, with a replacement,
`Link: <...>; rel="successor-version"`; the catalog entry and the OpenAPI operations are marked
deprecated, and the MCP tool's description starts with `Deprecated:`. The checks report
`deprecated.since`, `deprecated.sunset` and `deprecated.replacement`. Callers can pin a version
with `X-Workflow-Version` (docs/api.md, Calls that start jobs).

An alert trigger with `source: platform` matches the platform's own provider alerts: their labels
are `alertname: provider.alert`, `provider`, `action`, `severity` and `class` (the failed call's
error class, or `health` for a health band), and the trigger's `input` reads the alert as
`$alert`, like an external one.

### Step shapes (the `do` list)

Each item is `- <stepId>: { <one step key>, ...common keys }`. Step ids are camelCase and unique.
The canvas shows `name` (defaults to the id) and keeps `position` for layout.

| Step key                 | Fields                                                                         |
|--------------------------|--------------------------------------------------------------------------------|
| `call: provider`         | `with: { provider, action, input }`                                            |
| `call: http`             | `with: { method, url, headers, body, credential }`                             |
| `call: ansible`          | `with: { playbook: { git | upload | library }, inventory, limit, extraVars, credential, executionEnvironment }` |
| `call: terraform`        | `with: { module: { source, version | ref, credential? }, action: plan | apply | destroy, workspace?, vars, credential }` |
| `call: idp`              | `with: { tile, input }` (mock until the IDP tiles API is wired)                |
| `call: activity`         | `with: { taskQueue, activity, input }` (team-owned worker, any SDK language)   |
| `run: { workflow, version, input, onFailure }` | runs a published workflow as a child job and returns `{ jobId, status, outputs, resources }` (`resources`: the `{ id, type }` its register steps recorded). `version` is a range ("^1"); left out, publish writes `^<major>` of the workflow's current version (a run without one takes the current major). `onFailure: continue` makes a failed child the step's output (`status`, `error: { step, message, class }`) and goes on; the default `fail` fails the step with the child's error class, on both engines. The child is checked as a start through its own route is: its schema's defaults are filled in, its schema is applied (`invalid_input`, naming the field), the requester must be allowed to invoke it (`policy.rbac`) and the policy engine judges it with `parentId` in the context; a child the requester may not invoke, or that the policy denies, fails the step as `forbidden` before the child starts |
| `approval: { group, message, timeout, escalation }` | human sign-off                                         |
| `switch: [ { case: { when, then \| do } }, { default: then \| { do } } ]` | `then` jumps to a later step id or `end`; `do` runs a nested branch and continues after the switch |
| `fork: { branches: [ [steps], [steps] ] }` | parallel branches, joined                                     |
| `for: { each, in, do }`  | loop                                                                           |
| `try: { do, catch: { as, do } }` | scoped error handling: inside `catch` the failure is `$error` (with `as`, `$<as>`): `{ step, message, class, retryable }`; the name holds only while the catch runs |
| `wait: PT5M`             | ISO duration or `{ until: expression }`: the condition is looked at every 5 s in process; on Temporal after 5 s, then twice as long each time up to 5 minutes; at most the step's `timeout` (24h when unset) |
| `set: { name: expression }` | variables, reachable as `$vars.name`                                        |
| `raise: { error, message }` | fail the job                                                                |
| `use: { type, id, waitUntil: ready, checkAccess: false }` | resolve a registry resource; policy judges whether the requester may use it unless `checkAccess: false` opts the step out (the demo policy: a `public` resource or one without an owner team is any team member's, a `team` resource its owner team's, an admin's always); with `waitUntil: ready` a resource still building holds the step until the registry reports it ready, for at most the step's `timeout` (10m when unset): in process the registry's events wake the step; on Temporal the step looks again after 5 s, backing off to one look every 5 minutes, and soon again once the status moves |
| `register: { type, attributes, builtFrom }` | record what the job created                               |
| `notify: { channel, message }` | message to a channel                                                     |
| `script: { language, code, input }` | sandboxed small logic in `javascript`, `python`, `bash` or `go`     |

Common keys: `name`, `with`, `output: { as }`, `assert`, `retry: { maxAttempts, backoff }`,
`timeout`, `pauseOnFailure` (default true), `attentionTimeout` (how long a step paused on its
failure waits for a person's decision before it aborts, an ISO-8601 duration like `PT1H` or `4h`;
seven days when unset, on both engines), `compensate: [steps]` (run when a later step fails),
`position: { x, y }`, `notes`.

A failed step is retried as `retry` says when its error is retryable, then pauses for a person's
decision (`pauseOnFailure`) unless a `try` around it catches the failure; each `fork` branch counts
only the `try` blocks it is inside. Bad input, such as a `wait` duration that is no duration, fails
the step as `invalid_input` at once, without retries. An error outside any step's own handling (in
a container, or while the `outputs` are evaluated) fails that step, or `outputs`, like any other:
compensation and the workflow's `catch` run.

Cancel stops, abort rolls back, on both engines. Cancelling a job (`POST /jobs/{id}:cancel`) stops
the step that runs (see the runners below), starts no other, and runs neither compensation nor the
workflow's `catch`: the job ends `cancelled`, and what its steps did stays as it is. Aborting a
step that waits for a decision (`POST /jobs/{id}:abort`, or its `attentionTimeout` running out)
fails it for good: the completed steps' `compensate` steps and the workflow's `catch` run as for any
failure, and the job ends `rolled_back`, or `failed` when no completed step had compensation. A
cancel that arrives while compensation or the `catch` runs stops them too.

### Playbook sources and pins

`playbook` comes from one of three places:

- `git: { repo, ref, path, credential? }` with `commit`: an https repository (`host/path`
  implies https), a tag or branch, the playbook inside it, and an optional `vault:<path>` for a
  private repository. When `commit` is left out, publishing resolves `ref` (an annotated tag is
  peeled to its commit) and writes the commit into the version before the change request's
  checks run, so the diff shows the pin; a ref that does not resolve fails the `ansible-lint`
  check with the reason. `GET /api/v1/playbooks:resolve` answers the same commit beforehand.
- `upload: { file, sha256, path? }`: a file kept by `POST /api/v1/playbooks:upload` in the
  artifact store under its SHA-256; `path` names the playbook to run inside a `.zip` or `.tar.gz`.
  An upload is unpacked and checked before it is kept: every text file is scanned for secrets (a
  private key block, a `password`/`token`/`secret`/`api_key` with a literal value instead of a
  `vault:` reference or an expression, AWS, GitHub and Slack token shapes) and refused naming the
  file and line, never the value; then `platform.playbooks.lint-command` (blank by default, which
  skips the lint and the answer says so) runs a linter such as `ansible-lint --offline {playbook}`
  in the unpacked upload and refuses it on a non-zero exit with the linter's output. The pin
  checks at publish therefore skip uploads: they were checked when uploaded.
- `library: <provider>/<playbook>` (or the plain string): an action of an ansible provider,
  pinned by the provider's `source.commit`.

### How automation steps run

With the default profile, `call: ansible`, `call: terraform`, `call: http` and `script` answer
from the samples recorded in the provider files (a JavaScript script runs in the platform's own
interpreter). With the `live` profile they run for real, each in a working directory of its own
that is removed afterwards; a dry run still answers from the samples and never touches a system.
Every executable, image and limit is a `platform.runners.*` setting; with
`platform.runners.container-runtime: docker` (or `podman`) every step runs in a container with no
new privileges and every kernel privilege dropped, and secrets reach it by variable name, never on
a command line. A step's `timeout` (10 minutes when it names none) ends the process and fails the
step as `timeout` (retryable), as it fails a provider call that outlives it. Cancelling the job
stops it too, on both engines: in process within a second; on Temporal the job ends at once and
the process within about `platform.temporal.heartbeat-interval` (15s), when the call hears of the
cancel on its heartbeat. Values read from the vault are masked in every log line and error message.

- `call: ansible` runs `ansible-runner` (or `ansible-playbook` with the JSON callback when
  ansible-runner is not installed) on the pinned playbook. `inventory` is `dynamic` (the
  inventory configured in `platform.runners.ansible.dynamic-inventory`), a path inside the
  playbook's source, a list of hosts, or `{ hosts, group?, vars? }`; `limit` is a pattern or a
  list. `credential` is an SSH private key in the vault, written to a `0600` file for the run
  only. The roles and collections of a `requirements.yml` (also `roles/requirements.yml` and
  `collections/requirements.yml`) are installed once per content with `ansible-galaxy`. `executionEnvironment` (or the provider's) is the image in a
  container. The output is `{ ok, changed, failed, unreachable, skipped, hosts, stats }` plus the
  playbook's `set_stats` data; a failed host fails the step (`failed`), an unreachable one with a
  retryable `unreachable`. `checkModeOnTest: true` adds `--check` in the `test` environment.
- `call: terraform` checks the module out at the commit `module.ref`, the source's `?ref=` or
  `module.version` (a tag, tried as given and with a `v`) names, and uses it as the root module.
  Sources are git over https (`git::https://host/repo//dir`, `github.com/org/repo//dir`); registry
  modules, local paths, archives and ssh are refused with the reason. State lives in a workspace,
  `workspace` or one per job and step, on the backend of `platform.runners.terraform.backend`
  (local disk under the work directory when blank). The plan is saved, kept in the artifact
  store as the binary and as `terraform show -json`, and `apply` and `destroy` apply exactly that
  plan. `credential` is a vault entry holding the provider's environment variables, as a JSON
  object or `NAME=value` lines (variables that would steer terraform itself, like `TF_CLI_ARGS`
  or `PATH`, are refused). The output is `{ add, change, destroy, plan: { sha256, size, json },
  workspace, commit, outputs }` with sensitive outputs as `"(sensitive)"`; check mode stops
  after the plan. A saved plan holds the values it was planned with in clear, so the artifact
  store must be as private as the state.
- `script` runs one process: `javascript` (node) and `python` code is a function body with
  `input` in scope that returns the output, and what it prints goes to the job log; `bash` and
  `go` (`go run`, standard library only) are programs that read the input as JSON on stdin and
  print their output as JSON. In a container a script has no network, a read-only root and the
  memory, CPU and process limits of `platform.runners.script`.
- `call: http` sends one request. The URL may not be a cloud metadata or link-local address.
  `credential: vault:<path>` becomes `Authorization: Bearer <secret>`, and a header whose value
  is `vault:<path>` (or `<scheme> vault:<path>`, like `Basic vault:svc/basic`) gets the secret in
  its place; a request carrying a secret follows no redirect. The output is `{ status, body }`,
  and a status outside 2xx fails the step with the class it implies (`rate_limited` and 5xx are
  retryable).

### Expressions

Inside `{{ }}`:

| Expression                         | Meaning                                                       |
|------------------------------------|---------------------------------------------------------------|
| `$input.vmId`                      | the job input                                                 |
| `$json.vm.id`                      | the previous step's output                                    |
| `$('Check VM exists').json.vm.id`  | a named step's output (name or id)                            |
| `$vm.name`                         | a step output saved with `output: { as: vm }`                 |
| `$vars.x`, `$job.id`, `$job.requester`, `$env.name`, `$error.message` | context                     |
| `$json.items.length`, `a == b`, `a != b`, `a > b`, `!a`, `a && b`, `a \|\| b`, `a ?? b` | operators |
| `"text"`, `12`, `true`, `null`     | literals                                                      |

A string with only one expression yields the typed value (object, number, boolean). A string with
text around expressions yields a string.

### Resource inputs

An input with `x-resource: { type }` takes the id of a resource in the registry. A start checks
that the registry knows the id, that it is of that type and that it is not retired (400 with
`input.unknownResource`, `input.wrongResourceType` or `input.retiredResource`); a resource that is
still building passes, and the `use` step waits for it.

With `orCreateWith: <workflow>` the input also takes a request to build the resource first:

```json
{ "name": "web-01", "image": { "create": { "os": "rhel-9" } } }
```

The start checks `create` against the published `api.input` of that workflow, with its defaults
(problems under `image.create.<field>`; `input.unknownProducer` when it has no published version).
The job then runs that workflow as a child job, in a step named `image.create` before its first
step, nested under the job and in the same trace. From then on `$input.image` is the id of the
`image` the child registered; a child that registers none fails the job (`no_resource`). The job
keeps its input as asked, and a rerun replays the recorded build instead of building again. The
catalog, the OpenAPI document and the MCP tools show both forms: the input becomes `anyOf` the id
or `{ create: <that workflow's input> }`. Only one level is offered: the producer's own
`orCreateWith` inputs take ids inside `create`.

### Checks

The editor, `POST /workflows:validate`, `npm run check:workflows` (CI) and the publish run the same
checks; `packages/workflow-checks` and `WorkflowChecks.java` are twins with shared fixtures. Each
finding is a `Problem` (docs/api.md). Among them:

| Code                      | Level   | Finding                                                                 |
|---------------------------|---------|-------------------------------------------------------------------------|
| `yaml.invalid`            | error   | the file does not parse, or a mapping writes the same key twice (`Duplicate field 'examples'`): a duplicate would silently keep one value, so both twins and the designer refuse it |
| `contract.missingInput`   | error   | a run step leaves out a field the workflow's published `api.input` requires (and has no default for) |
| `contract.unknownInput`   | warning | a run step passes a field that input does not declare; the fix names the closest one |
| `contract.type`           | error   | a run step passes a literal of the wrong type, or one outside the field's `enum` |
| `run.onFailure`           | error   | `onFailure` is neither `fail` nor `continue`                            |
| `expr.unknownField`       | warning | an expression reads a field the step it names does not return (`$vm.vmId`); the fix is the closest field (`id`). Judged where the step declares its output (provider action, run, use, register) |
| `step.unreachable`        | warning | a step never runs: every case of the switch before it jumps past it     |
| `policy.approvalRequired` | warning | a destructive step (delete action, HTTP DELETE, `terraform destroy`) with no approval before it; the fix inserts one |
| `publish.ansibleLint`, `publish.routes`, `publish.contracts` | error | only from `:validate`: the change-request checks the file would fail at publish (an unpinned playbook, a route another published workflow answers, a breaking change without a new major version) |

### Versions and contracts

`metadata.version` is semantic. At publish, the new version is compared with the published one:
an input removed, retyped, pointed at another resource type, narrowed (fewer `enum` values) or
newly required, an output removed, a resource type no longer registered or an attribute no longer
set is a breaking change. A breaking change without a new major version fails the change request's
`contracts` check, which lists every consumer it reaches: run steps whose range accepts the new
version, `orCreateWith` inputs, and workflows that use or take the types it registers. With a new
major version the check passes and still lists them, with the run steps that stay on the old major.

### Previews

With `api.preview` a workflow publishes a `:preview` operation, and every start of a top-level job
keeps a preview with the job (its change and its approvals show it). A preview starts from the
flow: what each step would do to the world (the provider action's `effect`; terraform `plan`,
`apply` and `destroy` read, create and delete; ansible changes; a run step carries its child's
steps). A dry run of the flow on the input and the recorded samples then judges it:

- a step the dry run does not take (a switch case, a jump, an empty loop, a catch) is `skipped`,
  with effect `none`; when the dry run fails, only what it chose to skip is, and the rest stands;
- the build an input's `{ create }` request asks for shows as the `<input>.create` step and its
  child's steps;
- a provider action's `plan` is asked with the step's input and refines the step (kind: Provider,
  Plans); the answer of a `terraform` apply or destroy is its plan;
- the touched resources are the `x-resource` inputs of every row and the ids the `use` steps read,
  and, below each one a change or delete step names in its parameters, what the registry's lineage
  says was built from it (`via: built from <id>`, three generations down);
- the monthly cost adds up every create call the dry run makes (each item of a loop): the plan's
  `monthlyCost`, else the first price list that prices the call (kind: PriceList), else the
  recorded sample's `monthlyCost`. A batch is walked on its first row and costs that times the rows.

## kind: Provider

```yaml
apiVersion: iaf.platform/v1
kind: Provider
metadata:
  name: vsphere
  title: VMware vSphere
  owner: team-compute
  version: 1.0.0
spec:
  type: openapi               # openapi | http | ansible | terraform | idp | sdk
  spec: { url: https://vcenter.example.com/openapi.json, sha256: "9f2c…" }   # pinned at publish
  endpoints: { dev: https://vcenter-dev.example.com, test: https://vcenter-test.example.com, prod: https://vcenter.example.com }
  auth: { type: bearer, credential: vault:vsphere/token }   # bearer | basic | header | none
  actions:
    - id: vm.get
      title: Get VM
      operation: getVm        # operationId in the spec (openapi); or { method, path } (http)
      effect: read            # read | create | change | delete
      retry: safe             # safe | unsafe
      slo: { successRate: 99.9, p95Ms: 300 }   # optional: this action's own SLO; a field left out takes the provider's value
      sample: { id: vm-1, name: web, powerState: poweredOn }   # what dry runs answer; Try it saves the real response here
      recorded: { environment: dev, at: "2026-10-17T10:00:00Z" } # where and when that response came from
    - id: vm.delete
      title: Delete VM
      operation: deleteVm
      effect: delete
      retry: unsafe
      approval: required      # delete defaults to required
      plan: vm.get            # preview via another action
      effectDetail: Deletes the VM and its disks   # or one plain line the preview shows instead of a plan
      verify: { action: vm.get, expect: notFound }   # after a destructive try, prove it worked
  overrides:                  # fixes from real responses, through a change request; the vendor spec is never edited
    vm.get:
      response: { properties: { powerState: { type: string } } }
  errors:
    - { match: { status: 404 }, class: not_found, retry: false }
    - { match: { status: 429 }, class: rate_limited, retry: true }
  slo: { successRate: 99.5, p95Ms: 800 }
  alerts: { channel: "#compute-alerts" }
```

Before a new version is published, the wizard's review checks the file against what the platform
saw: the connection to dev and test passed with these endpoints and this credential, every action
answered when tried against dev, every error class has a match rule, every delete has a `plan`
or an `effectDetail`, and no real response still disagrees with its schema after the
`overrides`. It also suggests an SLO from the tried calls' p95 (docs/api.md, Providers). A
provider with an action that creates, changes or deletes something is held to two more gates: its
`errors` classified (openapi and http providers) and `alerts.channel` set; a provider that only
reads gets a warning for each instead.

Health is judged over the last 24 hours against `spec.slo` (default 99% success, p95 1000 ms;
`PUT /providers/{name}/slo` changes it). An action may carry an `slo` of its own, which wins over
the provider's for its calls: the provider's band is then the worst band of its parts, the calls
of the actions without one judged together against the provider's SLO and each action with one
judged against its own. A part with fewer than 5 calls is unknown and does not count; when no part
has enough calls the provider is judged as a whole against its own SLO. The success rate and the
latencies shown beside the band are always those of all the calls.

Endpoints are https URLs. The one exception is plain http to a loopback host (`localhost`,
`127.x.x.x`, `::1`) for a provider under development on the same machine: the file checks let it
through with a warning (`endpoints.https`) instead of an error.

A call fills each `{name}` in the operation's path from the input field of that name, and the
value fills that one segment: a `/` in it is sent encoded (`%2F`), and a value with a `.` or `..`
segment fails the call with `invalid_input` before anything is sent. The declared query parameters
come next, and the rest of the input is the JSON body (query parameters for a method without one).

An error rule matches a failed call by its `status`, narrowed by a `body` text when several classes
share a status (`match: { status: 400, body: quota }`). The rules are tried in order and the first
that matches names the class; a call no rule matches falls back to its status (401 and 403
`unauthorized`, 404 `not_found`, 409 `conflict`, 429 `rate_limited`, 408, 502, 503 and 504
`unavailable`, any other 5xx `server_error`, any other 4xx `client_error`). A rule without a status
never matches, and the review refuses it.

The spec can also live in a Git repository. The file then names the repository, the ref and the
path, and pins the commit it was read from; calls look operations up in that commit, never in
whatever the ref points at today:

```yaml
  spec:
    git: { repo: https://github.com/acme/vcenter-api, ref: v8.0.2, path: openapi/vcenter.yaml, credential: vault:github/read-token }
    commit: 3f2a9c1d0e4b5a6c7d8e9f0a1b2c3d4e5f6a7b8c   # filled by Import spec and Refresh spec
    sha256: "9f2c…"
```

Only https repositories are read; a private one names a Vault reference to a read token. Refresh
spec (`POST /providers/{name}:refresh-spec`) reads the spec again (from the URL, from Git at the
file's ref or at a new one) and opens a change request for the next minor version with the new
pin and schemas. A newer spec keeps an override unless it now agrees with the action's real response.

A new version of a provider reaches production through the same canary as a workflow version:
merging keeps it beside the current version, sandbox use (`dev` and dry runs) gets it at once,
and the release gives it 10%, 50%, then 100% of the jobs that call the provider while the gates
hold on its calls. The first version, and a publish with `strategy: all`, take every call at once
(docs/api.md, Releases).

Type-specific `spec` blocks:

- `ansible`: `source: { git: { repo, ref, path, credential }, commit }` or `upload: { file, sha256 }` or
  `library`; `executionEnvironment` is the image that runs the playbooks; `actions[].playbook`,
  `actions[].vars` become inputs; `auth.credential` is the machine credential the playbooks
  connect with.
- `terraform`: `module: { source, version, commit }` (`commit` pins a module read from Git);
  actions `plan`, `apply`, `destroy` with `vars`; `auth.credential` is what the runner gives
  Terraform (the cloud credentials).
- `idp`: `tile: { id }`; mock catalog until the IDP tiles API is wired.
- `sdk`: `worker: { taskQueue, heartbeatTimeout }`, both optional (the queue defaults to
  `provider-<name>`; a call is lost after `heartbeatTimeout` without a heartbeat); actions are
  activities a team-owned worker registers, and the provider SDKs in `sdk/` write the whole file
  from the code. The contract is "Custom-code providers" in `docs/architecture.md`.
- `http`: `endpoints` plus per action `{ method, path, example }`; schemas are inferred from the example.

Plans: `plan: <action>` names the action a preview asks what the step would do, with the step's
input: for real when that action's `effect` is `read`, otherwise on its recorded sample. Its answer
may say it with `effect` and `detail`, or count it with `add`, `change` and `destroy` (any destroy
reads as a delete, else any add as a create, else any change as a change, else as nothing), and
may price it with `monthlyCost` and `currency`. A plain read answer names what a change or a
delete acts on (`name (id)`), and a `not_found` leaves it nothing to do (effect `none`); a create
keeps its effect. A failed plan is noted in the step's detail and changes nothing.

An `ansible` or `terraform` file can be drafted from a repository instead of written by hand
(docs/api.md, Providers): `POST /providers:import-ansible` reads a repository at a ref, pins its
commit and offers one action per playbook, its `vars` taken from the roles' argument specs and
defaults, the play vars and prompts, and what the playbook uses without a value;
`POST /providers:import-terraform` reads a module's variables and outputs from Git or from the
module registry into a `plan` and an `apply` action. A variable that holds a secret is never an
input: the runner gives it from Vault.

```yaml
spec:
  type: ansible
  source:
    git: { repo: https://github.com/acme/ops-playbooks, ref: v3.1.0, credential: vault:github/read-token }
    commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d   # filled by the import; a push changes nothing until the next version
  executionEnvironment: registry.example.com/ee-platform:2.17
  auth: { type: none, credential: vault:ssh/automation }
  actions:
    - id: vm.restart                  # from vm/restart.yml
      title: Restart a VM             # the first play's name
      playbook: vm/restart.yml
      effect: change
      retry: unsafe
      vars:
        type: object
        required: [vm_id]
        properties:
          vm_id: { type: string, description: The VM to act on }   # the role's argument spec
          graceful: { type: boolean, default: true }                # play vars
```

## kind: ResourceType

```yaml
apiVersion: iaf.platform/v1
kind: ResourceType
metadata: { name: image, plural: images, owner: team-platform }
schema:
  required: [templateId, os]
  properties:
    templateId: { type: string }
    os: { type: string }
states: [building, ready, deprecated, retired]
producedBy: build-vm-image     # the workflow whose register step creates this type
visibility: public             # what new resources of the type get: public (every team's) or team (the default: the owner team's)
```

The registry generates `GET /api/v1/images`, `GET /api/v1/images/{id}` and the MCP tools
`list_images`, `get_image` for every type. A resource belongs to the team that registered it;
only that team, the job that created it or an admin may update it through a later `register`,
and only the team or an admin may retire it. `visibility` is what a `use` step's access check
reads: a `public` resource is any team's, a `team` resource its owner team's.

## kind: PriceList

```yaml
apiVersion: iaf.platform/v1
kind: PriceList
metadata:
  name: list-prices           # lists are read by name; the first that prices a call wins
  owner: team-platform
spec:
  currency: USD               # the default
  prices:
    - provider: vsphere
      action: vm.create
      by: cpu                 # a field of the action's input, dotted for a nested one
      monthly: { 2: 38.5, 4: 77, 8: 154 }
    - provider: vsphere
      action: vm.snapshot
      monthly: 4.2            # flat
```

What a preview charges per month for a create call when the action's plan answers no
`monthlyCost` itself (Previews). A value of `by` the list does not name has no price in it. The file
may sit in any content folder (the sample is `providers/team-platform/list-prices.yaml`); the
platform reads every `kind: PriceList` file at startup.
