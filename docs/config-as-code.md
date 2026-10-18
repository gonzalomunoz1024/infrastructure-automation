# Config as code

Three kinds of file, all `apiVersion: iaf.platform/v1`. The designer, the natural-language
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
      vmId: { type: string, title: VM id }
      reason: { type: string }
      image: { type: string, x-resource: { type: image, orCreateWith: build-vm-image } }
  response: { status: 202 }   # always 202 with { jobId }
  preview: true               # publish a :preview operation (blast radius before a run)
  batch:                      # bulk runs (always available; this block sets the owner's defaults)
    max: 500
    rollout: { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 }
mcp:
  expose: true
  description: Restart a virtual machine after safety checks
policy:
  approval: { required: true, group: compute-approvers, timeout: 4h }
  rbac: [ops, sre]            # roles that may invoke
  change: { required: true }  # a ServiceNow change is opened for every job
triggers:                     # optional extra ways in besides the API and MCP
  - alert: { match: { source: grafana, labels: { severity: critical } } }
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

### Step shapes (the `do` list)

Each item is `- <stepId>: { <one step key>, ...common keys }`. Step ids are camelCase and unique.
The canvas shows `name` (defaults to the id) and keeps `position` for layout.

| Step key                 | Fields                                                                         |
|--------------------------|--------------------------------------------------------------------------------|
| `call: provider`         | `with: { provider, action, input }`                                            |
| `call: http`             | `with: { method, url, headers, body, credential }`                             |
| `call: ansible`          | `with: { playbook: { git | upload | library }, inventory, limit, extraVars, credential, executionEnvironment }` |
| `call: terraform`        | `with: { module: { source, version }, action: plan | apply | destroy, vars, credential }` |
| `call: idp`              | `with: { tile, input }` (mock until the IDP tiles API is wired)                |
| `call: activity`         | `with: { taskQueue, activity, input }` (team-owned worker, any SDK language)   |
| `run: { workflow, version, input }` | runs a published workflow as a child job; `version` is a range ("^1") |
| `approval: { group, message, timeout, escalation }` | human sign-off                                         |
| `switch: [ { case: { when, then \| do } }, { default: then \| { do } } ]` | `then` jumps to a later step id or `end`; `do` runs a nested branch and continues after the switch |
| `fork: { branches: [ [steps], [steps] ] }` | parallel branches, joined                                     |
| `for: { each, in, do }`  | loop                                                                           |
| `try: { do, catch: { as, do } }` | scoped error handling                                                  |
| `wait: PT5M`             | ISO duration or `{ until: expression }`                                        |
| `set: { name: expression }` | variables, reachable as `$vars.name`                                        |
| `raise: { error, message }` | fail the job                                                                |
| `use: { type, id, waitUntil: ready, checkAccess: true }` | resolve a registry resource               |
| `register: { type, attributes, builtFrom }` | record what the job created                               |
| `notify: { channel, message }` | message to a channel                                                     |
| `script: { language, code, input }` | sandboxed small logic                                               |

Common keys: `name`, `with`, `output: { as }`, `assert`, `retry: { maxAttempts, backoff }`,
`timeout`, `pauseOnFailure` (default true), `compensate: [steps]` (run when a later step fails),
`position: { x, y }`, `notes`.

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
    - id: vm.delete
      title: Delete VM
      operation: deleteVm
      effect: delete
      retry: unsafe
      approval: required      # delete defaults to required
      plan: vm.get            # preview via another action
      verify: { action: vm.get, expect: notFound }   # after a destructive try, prove it worked
  overrides:                  # fixes from real responses; the vendor spec is never edited
    vm.get:
      response: { properties: { powerState: { type: string } } }
  errors:
    - { match: { status: 404 }, class: not_found, retry: false }
    - { match: { status: 429 }, class: rate_limited, retry: true }
  slo: { successRate: 99.5, p95Ms: 800 }
  alerts: { channel: "#compute-alerts" }
```

Type-specific `spec` blocks:

- `ansible`: `source: { git: { repo, ref, path }, commit }` or `upload: { file, sha256 }` or
  `library`; `actions[].playbook`, `actions[].vars` become inputs.
- `terraform`: `module: { source, version }`; actions `plan`, `apply`, `destroy` with `vars`.
- `idp`: `tile: { id }`; mock catalog until the IDP tiles API is wired.
- `sdk`: `worker: { taskQueue, heartbeatTimeout }`, both optional (the queue defaults to
  `provider-<name>`; a call is lost after `heartbeatTimeout` without a heartbeat); actions are
  activities a team-owned worker registers, and the provider SDKs in `sdk/` write the whole file
  from the code. The contract is "Custom-code providers" in `docs/architecture.md`.
- `http`: `endpoints` plus per action `{ method, path, example }`; schemas are inferred from the example.

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
```

The registry generates `GET /api/v1/images`, `GET /api/v1/images/{id}` and the MCP tools
`list_images`, `get_image` for every type.
