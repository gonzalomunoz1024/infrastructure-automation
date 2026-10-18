# Getting started

A walkthrough for an engineer who has never used the platform: run it, run a workflow, design and
publish one, and onboard a provider. Every step below works on the demo profile with the sample
content in this repository; nothing needs a real vCenter, Vault or Temporal.

## 1. Run it

```bash
scripts/dev.sh        # backend on http://localhost:8080, frontend on http://localhost:3000
```

Open <http://localhost:3000>. You are `gonzalo`, an admin. The demo keeps everything in memory
and seeds two weeks of history (jobs, releases, change requests, resources) that agrees with
itself. To see the platform as a less privileged person, open **Settings** and change **Act as**:
`ana` edits the data team's workflows, `mateo` runs the compute team's, `junior` can only run.

The pages, left to right in the sidebar:

| Section | Page | What it is for |
|---|---|---|
| Build | Workflows, Dependencies, Templates, Assistant | design, version and publish workflows |
| Run | Catalog, Jobs, Approvals | run published workflows, follow jobs, decide approvals |
| Operate | Providers, Resources, Releases, Audit | the systems workflows call, the registry they read and write, canaries, evidence |
| Admin | Credentials, Settings | `vault:` references, identity, API links |

## 2. Run a workflow from the catalog

1. Open **Catalog**. Every published workflow has a generated run form; pick *VM restart*.
2. Fill the form (it is the workflow's `api.input` schema; a field that names a resource offers
   a picker over the registry).
3. Press **Preview**. The blast-radius preview walks the workflow with your
   input and lists each step's effect (read, change, create, delete), the resources it touches,
   the cost when a price list knows it and the policy decision. Nothing runs yet.
4. Press **Run**. The job opens: steps appear as they run, with their input and output one click
   away. A step that needs an approval waits in **Approvals**; a step that fails pauses the run in
   *Needs attention*, where you choose Retry, Edit input, Skip or Abort (abort rolls back the
   completed steps that declare a `compensate` block).
5. Switch the segmented control from **One** to **Many** to run the same workflow on a list of
   targets: paste CSV, TSV or JSON rows or upload a file. Rows are validated first, then run in
   waves (5%, 25%, 100% by default) that stop when too many rows fail, as one parent job.

The same run is one HTTP call: `POST /api/v1/vms/{vmId}:restart` (the catalog page shows the
exact path and the Swagger link), and one MCP tool, `vm_restart`, for an agent.

## 3. Design a workflow

1. **Workflows → New workflow**. Choose *From template* for a guided start, *Blank* for an empty
   canvas, *Import* to turn an OpenAPI operation, an Ansible playbook, a Terraform module or an
   IDP tile into a draft, or *Describe it* to let the assistant draft one from a sentence.
2. The designer is the n8n-style canvas: add a step with the plus on the edge, pick a provider
   action, an approval, a wait, a switch, a loop, a fork, or a *run* step that calls another
   published workflow. The panel on the right is the step's parameters; expressions use the
   `{{ $('step').json.field }}` syntax and show their value from the last test run.
3. **Issues** lists what the checks found as you edit (unknown action, missing approval on a
   destructive step, a literal secret, an expression that does not parse); most problems carry a
   one-click fix. **Validate** runs the full backend checks; **YAML** shows the file the canvas
   produces, which is what lands in Git.
4. **Test workflow** runs the draft as a dry run: provider calls answer from the samples recorded
   in each provider file, so nothing real changes, and every step shows what it would send.
5. **Publish** opens a change request: the diff of the YAML, the checks (schema, checks,
   playbooks, policy, replay of recent jobs, contracts with dependants, routes), and the release
   strategy (canary by default). The author cannot merge their own change; a reviewer with edit
   rights on the owner team opens it under **Workflows → change requests** and presses *Merge and
   publish*. From then on the version is in the catalog, has its endpoint and its MCP tool, and the
   canary release moves traffic to it stage by stage under **Releases**, rolling back on its own
   when a gate fails.

Prefer a file? Write the YAML by hand under `workflows/<team>/` following
[config as code](config-as-code.md) and run `npm run check:workflows`; the designer and the file
are the same thing.

## 4. Onboard a provider

A provider is a system workflows may call. Nobody writes code for one; the platform makes the
calls from the provider's own description.

1. **Providers → Add provider**. Choose the type: *OpenAPI* (import a spec and every operation
   can become an action), *HTTP* (a few endpoints described by example), *Ansible* (playbooks
   from a Git repository, pinned to a commit), *Terraform* (a module with plan, apply and destroy),
   *IDP tile* or *SDK worker* (a team's own code on its own task queue, see `sdk/`).
2. **Connect**: paste the spec or its URL, give the base URL per environment, pick the
   credential as a `vault:` reference (never a value; **Credentials** lists what your team may
   use), and run *Test connection*: every environment is probed and the result is kept as review
   evidence.
3. **Actions**: tick the operations workflows may call. Name, effect (read, change, create,
   delete) and retry are inferred from the HTTP method; a delete needs an approval by default.
4. **Try it**: call an action for real against dev with a generated form. The response is checked
   against the schema; a mismatch becomes an *overrides* block (the vendor spec is never edited)
   and the response is saved as the sample the dry runs answer from. A destructive try is verified
   gone afterwards.
5. **Review**: the automated review (spec pinned, credentials work, every action tried, errors
   classified, owner and alert channel set, SLO suggested). Publishing opens a change request a
   teammate merges; the provider version rolls out as a canary over its calls.

The provider page then shows its health (success rate, p50, p95, error trend) computed from the
calls workflows make, its SLO, and the alerts it raised. Degraded or failing providers are judged
from that telemetry, and an alert can itself trigger a workflow.

## 5. Where to go next

- [Config as code](config-as-code.md): every field of the `Workflow`, `Provider` and
  `ResourceType` files, the step kinds and the expression language.
- [API](api.md): every endpoint, the generated ones, MCP, service accounts and scopes.
- [Architecture](architecture.md): the slices, the interpreter, the engines and the profiles
  for production (PostgreSQL, Temporal, Vault, OPA, OIDC).
- [Contributing](contributing.md): build, tests and conventions.
