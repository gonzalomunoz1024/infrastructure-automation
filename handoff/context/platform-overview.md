# The automation platform on `main`, explained

`main` at c9529d5 is finished, and the console work must never change it. This page explains it for someone who has to build on its API. The depth is in the verbatim copies of its docs in `platform/` (start with `main-README.md`, then `main-docs-architecture.md`, `main-docs-config-as-code.md` and `main-docs-api.md`). The two walkthroughs (`main-docs-onboard-a-vm-provider.md`, `main-docs-create-a-vm-in-the-designer.md`) and `journeys-platform.md` show it in use.

## 1. What it is

A team designs an infrastructure workflow on an n8n-style canvas, stores it as config as code in Git, and runs it on Temporal. Publishing a workflow generates its REST endpoint, its OpenAPI entry and its MCP tool. The systems a workflow talks to are registered as providers through a no-code wizard. Everything a team builds (VM restart, MongoDB upgrade, Delete VM, Patch a CVE) is sample content under `workflows/`: nothing in `backend/` or `frontend/` knows what a VM is.

Layout: `backend/` (Spring Boot 3.5, Java 21, WebFlux, CQRS, ports and adapters, Temporal SDK, JGit, **Maven**), `frontend/` (React 19, Vite, TypeScript, Tailwind v4, the designer on `@xyflow/react`), `packages/ui` (Atlas), `packages/workflow-checks` (the editor's checks in TypeScript plus the Workflow JSON Schema; the same rules run in Java and in CI), content at the root (`workflows/`, `providers/`, `resource-types/`, `kind: PriceList`), `sdk/` (Java, Go, Python SDKs for custom-code providers on a team's own worker), `devin/` (NL builder playbooks), `docs/`.

Run it: `scripts/dev.sh` (backend :8080 demo profile, frontend :3000), `--temporal` (interpreter on a local Temporal dev server), `--mongo` (state in MongoDB). Demo users: `gonzalo` (admin, approver), `junior` (can only run), `ana`, `mateo`. The demo profile keeps everything in memory, answers provider calls from samples recorded in each provider file, and seeds two weeks of self-consistent history.

## 2. Config as code

Four kinds, all `apiVersion: iaf.platform/v1`:

- `kind: Workflow` (`workflows/<team>/<name>.yaml`): `metadata` (name, title, **noun**, **verb**, persona, owner team, version), `api` (method and path inferred from the verb, a JSON-schema `input` that is also the catalog form and the MCP tool input, 202 with `{jobId}`, optional `preview`, `batch` defaults), `mcp`, **`policy`** (required: approval group, RBAC, change ticket), `triggers` (alerts, events), `rollout` (canary stages), and `workflow.do`, the steps.
- `kind: Provider`: how to reach a system (OpenAPI spec pinned by sha256, endpoints per environment, auth as `vault:<path>` references, action names, retry classes, overrides for spec mismatches, recorded samples).
- `kind: ResourceType`: a resource's JSON schema; registry records carry lineage.
- `kind: PriceList`: list prices (the console's cost service needs P-22 to read them over the API).

Step shapes: `call: provider | http | ansible | terraform | idp | activity`, `run` (a published workflow as a child job, with a version range), `approval`, `switch` (`then` jumps, `do` branches and rejoins), `fork`, `for`, `try`/`catch`, `wait`, `set`, `raise`, `use` (resolve a registry resource, policy-checked, can wait until ready), `register` (record what the job created, with `builtFrom`), `notify`, `script` (sandboxed JS/Python/bash/Go). Common keys: `retry`, `timeout`, `pauseOnFailure` (default true), `attentionTimeout`, `compensate`. Expressions are `{{ $input.x }}`, `$('stepId').json`, `$vars`.

**Failure semantics the console depends on:** a failed step is retried when retryable, then **pauses the job as needs-attention** for a person's retry, skip or abort. Cancel stops (no compensation); abort rolls back (`rolled_back`). `:rerun` starts a new job from a step of an ended job.

## 3. The designer (primary mode of use)

An n8n look-alike: canvas, node creator, node panels with input/output, drag-to-map fields between steps, live checks in the editor, a Test workflow run that touches nothing (dry runs answer from recorded samples), view YAML, and Publish (a change request). Gonzalo decided (2026-10-18) that the designer is the primary mode: every provider action (credentials, a new provider) must be doable from the designer alone; a provider made in the designer publishes as its own change request, linked by `requires`, merged first.

## 4. Providers and the wizard

The platform makes REST calls itself from a provider's OpenAPI registration; SDK code is only for custom logic. The five-step **Add provider** wizard: type (OpenAPI/HTTP, Ansible, Terraform, IDP tile), credential (stored in Vault through `CredentialsPort`; only the `vault:` name is ever used again; a credential belongs to an owner team), connect (`providers:import-spec`, `:test-connection` per environment, SSRF guard on metadata addresses), actions (rename, retry classes, `:try` each action for real, samples kept with secrets blanked), review (evidence: spec pinned, connection tested, every action tried) and **Publish** as a change request an owner-team member must merge (an author cannot merge their own). Then the releases slice routes calls, with canaries at 10%, 50%, 100% behind gates. Network access is out of scope; developers pick their own test resources.

## 5. Ansible steps

A step can run an Ansible playbook instead of an API: from a Git repository pinned by SHA, an upload pinned by SHA-256, or a shared library. Inventory, limit, extra vars, credential (vault reference), execution environment. Retries are off by default.

## 6. Chaining and resources

Workflows depend on each other and pass outputs cleanly. `run` calls a child workflow; an input marked `x-resource: { type: image, orCreateWith: build-vm-image }` lets Create VM first run Build VM image when the image does not exist, wait until it is ready (`use` with `waitUntil: ready`), check the requester may use it, and `register` the VM with `builtFrom` the image. The registry keeps that lineage.

## 7. Engine

One generic Java **interpreter** runs any workflow, in process (demo) or as one Temporal workflow (`temporal` profile) with each step an activity. Production adapters sit behind Spring profiles (`temporal`, `opa`, `vault`, `devin`, `live`, `oidc`, `postgres`/`mongo`, `s3`); demo adapters are plain components. MongoDB is the platform's database (Temporal keeps its own).

## 8. Approved features (Gonzalo, 2026-10-17)

- **Blast-radius preview** before a run (`POST <path>:preview`); providers may publish a "plan" operation.
- **Time-travel debugging**: replay a job, pause on failure, re-run from a step, replay new versions against recent jobs before publishing.
- **Alerts as a trigger type.**
- **Canary releases** for new workflow and provider versions.
- **Provider admin view**: health, performance, timings, SLO alerts, readiness.
- **Audit evidence** for each job with a hash chain, shown in the admin view.
- **Bulk runs**: one request runs a workflow per target (e.g. 100 VMs) in waves, stops automatically on errors, one parent job.
- **Designer**: drag-to-map and live checks.
- Declined: schedules and change windows.
- OPA: the firm has an OPA policy engine; the platform calls a mock until it is wired in (TODO).
- IDP tiles: a step type exists as a mock placeholder.

## 9. The Devin natural-language builder

The firm has full access to the Devin API. A person describes a workflow in plain English; the platform starts a Devin session with the playbook `devin/nl-builder-playbook.md` (`!workflow-draft`), Devin reads the existing providers, types and workflows, drafts YAML from those pieces against a structured-output schema, validates it with the platform's checks, and the person reviews it on the canvas and submits the usual change request. `devin/provider-draft-playbook.md` does the same for providers. The demo profile has a deterministic mock agent. The console's copilot (decision 27) uses the same Devin API behind its own `AssistantPort`.

## 10. Generated surface

Catalog `GET /api/v1/catalog`; each workflow at its `api.path` (e.g. `POST /api/v1/vms/{vmId}:restart`); bulk `POST <path>:batch`; preview `POST <path>:preview`; OpenAPI 3.1 at `/openapi.json`; MCP server (Streamable HTTP, JSON-RPC 2.0) at `POST /api/v1/mcp`; job events as SSE (`/api/v1/jobs/{id}/events`, each SSE event named after its type) and WebSocket.

## 11. What the console needs from it (and why it may not just use it)

The independent review of the console plan (code-verified at c9529d5) found that platform reads are not tenant-isolated (`GET /resources` and `GET /jobs` ignore the owner and job reads include inputs), registry ids can collide at 10M, resource types load only at start-up, idempotency covers POST only, and there is no durable event feed. Hence P-01 to P-23 (`hub/platform-changes-README.md`) and the rule that the console never passes a platform read straight through to a browser.

## 12. Terminology and rules

- "Workflow", never "capability". Kinds: `Workflow`, `Provider`, `ResourceType`, `PriceList`.
- The platform only lets providers add their own workflows; VM restart, Mongo upgrade, CVE remediation are content, never platform logic.
- Credentials only as `vault:<path>`; the API never returns secret values.
- Platform conventions for anyone reading its code: Java compiles with `-Werror`; commits as Gonzalo; gitleaks in CI; merges with `--no-ff` and no rebases.
