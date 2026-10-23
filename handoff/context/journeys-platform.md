# Platform journeys

Eight journeys through the automation platform on main (c9529d5), transcribed from the Platform Journeys diagram page. People: Sam builds (junior engineer, team-compute), Gonzalo reviews, Mateo runs. Numbering as in journeys-console.md.

## Add a provider with the wizard

**In plain words:** Sam connects the VM API to the platform without writing code: store the token, read the API description, test it, try each action for real, then a teammate reviews and merges.

**Technically:** Add provider wizard: credential into Vault, `providers:import-spec`, `:test-connection` and `:try` kept as review evidence, `providers:publish` opens a change request, a reviewer on the owner team merges, and the releases slice routes calls.

### Steps in plain words

- **Start** (Sam's screens): Sam opens Providers, presses Add provider, picks OpenAPI, names it Acme Cloud for team-compute, and types in the API token once → 1 to Safe
- **1a** (Safe): Locks the token away. From now on everything uses only its name
- **1b** (Sam's screens): Sam gives the address of the API description → 2 to Provider setup
- **2a** (Provider setup): Reads the description: every operation becomes an action, and each environment gets its address
- **2b** (Sam's screens): Sam presses Test connection → 3 to Provider setup
- **3a** (Provider setup): Gets the token from the safe for this call only → 4 to Safe
- **4a** (Safe): Hands it over
- **4b** (Provider setup): Knocks on the API in dev and in prod → 5 to Outside system
- **5a** (Outside system): Answers both
- **5b** (Provider setup): Writes the result down as evidence for the reviewer → 6 to Sam's screens
- **6a** (Sam's screens): Sam sees green, renames the actions (vm.create, vm.get, vm.delete) and says which errors are worth a retry
- **6b** (Sam's screens): Try it: Sam creates a test VM in dev, and confirms first because it changes something → 7 to Provider setup
- **7a** (Provider setup): Makes that one real call → 8 to Outside system
- **8a** (Outside system): Creates the test VM and answers
- **8b** (Provider setup): Checks the answer matches the description, blanks out anything secret, and keeps it as the sample that test runs will use → 9 to Sam's screens
- **9a** (Sam's screens): Sam tries the delete too, so dev is left as it was, then opens Review and presses Publish → 10 to Provider setup
- **10a** (Provider setup): Runs every check again and makes sure the review is complete
- **10b** (Provider setup): Opens a change request with the file and all the evidence → 11 to Review box
- **11a** (Review box): Puts the file on its own branch to wait for review
- **11b** (Review box): Lets the owner team know someone must review → 12 to Gonzalo's screens
- **12a** (Gonzalo's screens): Gonzalo reads the file and the evidence: spec pinned, connection tested, every action tried. Sam cannot merge their own
- **12b** (Gonzalo's screens): Gonzalo merges → 13 to Review box
- **13a** (Review box): Merges the branch into main
- **13b** (Review box): Hands the file to Provider setup → 14 to Provider setup
- **14a** (Provider setup): Acme Cloud is live: workflows can call it, and its health is measured from every call
- **14b** (Provider setup): Tells the rollout which version is current → 15 to Gradual rollout
- **15a** (Gradual rollout): The first version takes every call. Later versions get 10%, then 50%, then all calls while they stay healthy

### Steps, technical

- **Start** (frontend (author)): Wizard Type and Connect steps; `POST /api/v1/credentials {name, type, value, owner: team-compute}` → 1 to Vault
- **1a** (Vault): Stored through `CredentialsPort`; the provider names `vault:acme-cloud/token`; the value is never returned
- **1b** (frontend (author)): `POST /providers:import-spec {type: openapi, url}` (also paste, file or Git) → 2 to providers slice
- **2a** (providers slice): `SpecImport`: operations, `sha256` pin, base URL per environment; effect and retry from the HTTP method; a delete needs approval
- **2b** (frontend (author)): `POST /providers:test-connection {endpoints, auth, provider, owner}` → 3 to providers slice
- **3a** (providers slice): Resolves `vault:acme-cloud/token`; link-local and cloud metadata addresses are refused → 4 to Vault
- **4a** (Vault): Value resolved in memory, never logged
- **4b** (providers slice): One probe per environment with the bearer token → 5 to provider API
- **5a** (provider API): 200 from dev and prod
- **5b** (providers slice): Kept as review evidence for the owner team (`ok`, `durationMs`) → 6 to frontend (author)
- **6a** (frontend (author)): Actions step: ids, `previews`, error classes `not_found`, `conflict`, `rate_limited`, `unavailable`
- **6b** (frontend (author)): `POST /providers:try {yaml, action: vm.create, environment: dev, input}` → 7 to providers slice
- **7a** (providers slice): Same path as a run's call, but no telemetry sample → 8 to provider API
- **8a** (provider API): 201 with the VM
- **8b** (providers slice): Schema check; secrets become `vault:redacted`; draft gets `sample` and `recorded: {environment, at}` → 9 to frontend (author)
- **9a** (frontend (author)): Review step: `POST /providers:validate` (checks + `ProviderReview`), then `POST /providers:publish {yaml, strategy: canary}` → 10 to providers slice
- **10a** (providers slice): 409 when the file has problems, the review is not ready, or another change request for the provider is open
- **10b** (providers slice): File `providers/team-compute/acme-cloud.yaml` → 11 to Git change requests
- **11a** (Git change requests): Branch `change/acme-cloud-0.1.0`; a pull request under the `github` profile
- **11b** (Git change requests): `review.requested` into team-compute's inbox → 12 to frontend (reviewer)
- **12a** (frontend (reviewer)): Change request page: Diff and YAML tabs, checks, review evidence; the author gets 403 on merge
- **12b** (frontend (reviewer)): `POST /change-requests/{id}:merge`, or the PR merged on GitHub comes back by webhook → 13 to Git change requests
- **13a** (Git change requests): Change request saved merged
- **13b** (Git change requests): Providers slice publishes before `change-request.merged` is sent → 14 to providers slice
- **14a** (providers slice): Provider published with its SLO; health and alerts from call telemetry
- **14b** (providers slice): Release for the provider version → 15 to releases slice
- **15a** (releases slice): First version and `strategy: all` take every call; later ones canary 10/50/100 with gates on call telemetry; sandbox gets the candidate at once

## Build a workflow in the designer

**In plain words:** Sam builds Create VM on the canvas: the form fields, two steps that call the VM API, the token stored right from the step, then a check and a test run that touch nothing.

**Technically:** Blank workflow on the @xyflow/react canvas; `api.input`, two `call: http` steps, a credential saved from the step's field, the draft saved as YAML, `workflows:validate` and `workflows:dry-run`.

### Steps in plain words

- **Start** (Sam's screens): Sam presses New workflow, Blank: title Create VM, noun vm, verb create, team-compute → 1 to Workflow desk
- **1a** (Workflow desk): Starts a draft. The noun and verb already give it an address: create a VM
- **1b** (Sam's screens): The canvas opens with the API trigger. Sam adds the fields a person will fill in: name and size
- **1c** (Sam's screens): Adds an HTTP request step that creates the VM, dragging the fields into its body
- **1d** (Sam's screens): In the step's credential field Sam picks New credential and types the API token → 2 to Safe
- **2a** (Safe): Locks the token away. The step keeps only its name
- **2b** (Sam's screens): Sam adds a second step that reads the VM back using the id the first step returned, and says what the finished job answers with → 3 to Workflow desk
- **3a** (Workflow desk): Saves the draft. The canvas is only a view of this file
- **3b** (Sam's screens): Sam presses Validate → 4 to Workflow desk
- **4a** (Workflow desk): Runs the same checks a publish will: no issues
- **4b** (Sam's screens): Sam presses Test workflow with values a person would type → 5 to Workflow desk
- **5a** (Workflow desk): Walks every step without calling anything and shows what each would send
- **5b** (Sam's screens): Sam reads the YAML drawer, the file the change request will hold, and is ready to publish

### Steps, technical

- **Start** (frontend (author)): `POST /api/v1/workflows` with `metadata {name: create-vm, noun: vm, verb: create, owner: team-compute}` → 1 to workflows slice
- **1a** (workflows slice): Draft 0.1.0; route `POST /api/v1/vms` inferred from the verb
- **1b** (frontend (author)): Settings, Input: `api.input` JSON Schema, which becomes the run form, the API body and the MCP arguments
- **1c** (frontend (author)): `call: http` POST, body `{{ $input.name }}`; live checks run in the browser as Sam edits
- **1d** (frontend (author)): `POST /credentials` from the dialog over the canvas → 2 to Vault
- **2a** (Vault): `vault:acme-cloud/api-token`, sent as a bearer header on each call
- **2b** (frontend (author)): `GET .../vms/{{ $json.id }}`; Settings, Outputs over `$('Get VM').json` → 3 to workflows slice
- **3a** (workflows slice): `PUT /workflows/create-vm/draft {definition}` → `{problems}`; YAML via `POST /workflows:render`
- **3b** (frontend (author)): `POST /workflows:validate {definition}` → 4 to workflows slice
- **4a** (workflows slice): Schema, `WorkflowChecks`, `publish.routes`, `mcp.toolName`, `run.cycle`
- **4b** (frontend (author)): `POST /workflows:dry-run {yaml, input}` → 5 to workflows slice
- **5a** (workflows slice): Interpreter dry run on recorded samples → `DryRunResult` per step
- **5b** (frontend (author)): YAML drawer; next: Publish a workflow and go live

## Publish a workflow and go live

**In plain words:** Sam publishes Create VM. The platform checks it, asks the company rules, and puts it up for review. Once Gonzalo merges, it gets a form, an API and an AI tool, and goes out gradually.

**Technically:** `workflows/{name}:publish` runs the publish checks and the `publish` policy phase, writes a `change/<name>-<version>` branch, a reviewer merges, the catalog generates REST, `:batch`, `:preview`, OpenAPI and MCP, and the release router canaries the version.

### Steps in plain words

- **Start** (Sam's screens): Sam presses Publish in the designer, choosing a gradual rollout → 1 to Workflow desk
- **1a** (Workflow desk): Checks the file, that any playbook is pinned to an exact version, that recent real runs would still take the same path, and that nothing else uses the same address
- **1b** (Workflow desk): Asks the company rules whether this version may be published → 2 to Company rules
- **2a** (Company rules): Says yes, or no with the reason. If it cannot be reached, the answer is no
- **2b** (Workflow desk): Writes the file on its own branch and opens a change request → 3 to Review box
- **3a** (Review box): Holds the file, what changed, the check results and a drawing of the workflow
- **3b** (Review box): Lets the owner team know a review is waiting → 4 to Gonzalo's screens
- **4a** (Gonzalo's screens): Gonzalo sees the workflow drawn on a read-only canvas, the changes and the checks
- **4b** (Gonzalo's screens): Gonzalo merges. Sam could not merge their own → 5 to Workflow desk
- **5a** (Workflow desk): Checks once more that nothing published meanwhile took the same address or AI-tool name, then publishes 0.1.0
- **5b** (Workflow desk): Announces the new version → 6 to Shop window
- **6a** (Shop window): Makes a run form, an API endpoint and an AI tool from the fields Sam defined
- **6b** (Shop window): For each run, asks the rollout which version should take it → 7 to Gradual rollout
- **7a** (Gradual rollout): The first version takes every run. A later version gets 10%, then 50%, then all, and rolls back if its runs start failing

### Steps, technical

- **Start** (frontend (author)): `POST /workflows/create-vm:publish {strategy: canary}` → 1 to workflows slice
- **1a** (workflows slice): Checks: schema, playbooks, replay (jobs of the last 7 days dry-run on the new version), contracts, diff, routes, yaml
- **1b** (workflows slice): `PolicyPort` phase `publish` → `data.platform.publish` → 2 to OPA policy engine
- **2a** (OPA policy engine): `{allow, rules[]}`; no answer in 5 s fails closed: 503 and the version stays a draft
- **2b** (workflows slice): `workflows/team-compute/create-vm.yaml` on `change/create-vm-0.1.0` → 3 to Git change requests
- **3a** (Git change requests): GitHub PR under profile `github`; `preview.mermaid` flowchart
- **3b** (Git change requests): `review.requested` to team-compute → 4 to frontend (reviewer)
- **4a** (frontend (reviewer)): Change request page: candidate on the designer canvas, Diff and YAML tabs
- **4b** (frontend (reviewer)): `POST /change-requests/{id}:merge`; 403 for the author unless admin → 5 to workflows slice
- **5a** (workflows slice): 409 `checks failed` on `mcp.toolName` or `run.cycle`; otherwise the version is current
- **5b** (workflows slice): `change-request.merged` on the event bus → 6 to catalog and gateway
- **6a** (catalog and gateway): `POST /api/v1/vms`, `/vms:batch`, `/vms:preview`, OpenAPI 3.1 at `/openapi.json`, MCP tool `vm_create`
- **6b** (catalog and gateway): Release router; `X-Workflow-Version` pins and bypasses the split → 7 to releases slice
- **7a** (releases slice): `rollout.stages [10, 50, 100]`; `ReleaseGates` on job outcomes; promote, pause, rollback

## Run with a preview and a sign-off

**In plain words:** Mateo restarts a production VM. First a preview shows what would change, then the run pauses for an approver, then the team's Ansible playbook does the restart, and Mateo watches it live.

**Technically:** Generated `:preview` (Previews dry run + `invoke` policy), the start through the gateway, an `InterpreterWorkflow` on Temporal, a provider call, an `approval` step awaited by signal, a `call: ansible` step on the runner, job events over SSE and into the audit chain.

### Steps in plain words

- **Start** (Mateo's screens): Mateo opens VM restart, picks vm-0042 from the list of VMs, gives a reason, chooses prod, and presses Preview → 1 to Shop window
- **1a** (Shop window): Checks the form and that Mateo's team may run this workflow → 2 to Job desk
- **2a** (Job desk): Rehearses the run without touching anything: which steps would run and what each would change
- **2b** (Job desk): Asks the company rules → 3 to Company rules
- **3a** (Company rules): Yes, but production needs a sign-off
- **3b** (Job desk): Sends back the blast radius and the decision → 4 to Mateo's screens
- **4a** (Mateo's screens): Mateo sees what will change and that someone must sign off, and presses Run → 5 to Shop window
- **5a** (Shop window): Counts the run against the team's allowance and checks again → 6 to Job desk
- **6a** (Job desk): Records the job with its preview and starts it → 7 to Engine
- **7a** (Engine): Takes the job. Even if a server restarts, it carries on where it was
- **7b** (Engine): Step 1: checks the VM exists → 8 to Outside system
- **8a** (Outside system): Answers with the VM
- **8b** (Engine): Step 2: waits for a sign-off → 9 to Approver's screens
- **9a** (Approver's screens): An approver sees the request with the same preview Mateo saw
- **9b** (Approver's screens): Approves → 10 to Engine
- **10a** (Engine): Carries on
- **10b** (Engine): Step 3: runs the team's restart playbook → 11 to Script runner
- **11a** (Script runner): Runs the playbook against that one VM, only once
- **11b** (Engine): Done. Every step goes into the job's tamper-proof record → 12 to Mateo's screens
- **12a** (Mateo's screens): Mateo watches each step turn green as it happens

### Steps, technical

- **Start** (catalog and job page): `POST /api/v1/vms/vm-0042:restart:preview` → 1 to catalog and gateway
- **1a** (catalog and gateway): Schema, `x-resource` known in the registry, `policy.rbac` → 2 to runs slice
- **2a** (runs slice): `Previews`: interpreter dry run on samples, provider `plan` actions, lineage, `PriceListPort`
- **2b** (runs slice): `PolicyPort` phase `invoke`, `context.channel: preview` → 3 to OPA policy engine
- **3a** (OPA policy engine): `needs-approval`, rule `prod.change-window`
- **3b** (runs slice): `Preview` with `policy` → 4 to catalog and job page
- **4a** (catalog and job page): `POST /api/v1/vms/vm-0042:restart` with an `Idempotency-Key` → 5 to catalog and gateway
- **5a** (catalog and gateway): `JobQuotas` (60 a minute), idempotency, `invoke` again → 6 to runs slice
- **6a** (runs slice): `StartJob`: `job_…` queued, plan kept; version from the release router → 7 to Temporal + interpreter
- **7a** (Temporal + interpreter): `InterpreterWorkflow`, workflow id = job id, definition snapshot in the input
- **7b** (Temporal + interpreter): `call: provider` vsphere `vm.get` via `ProviderCallPort`, token from Vault → 8 to provider API
- **8a** (provider API): 200
- **8b** (Temporal + interpreter): `approval` step, `Workflow.await`; job `waiting-approval` → 9 to frontend (approver)
- **9a** (frontend (approver)): Inbox notification, Slack or email; `Approval.preview`
- **9b** (frontend (approver)): `POST /approvals/{id}:approve` → 10 to Temporal + interpreter
- **10a** (Temporal + interpreter): Signal `approve`
- **10b** (Temporal + interpreter): `call: ansible`, playbook pinned to commit `4e1d9a2` → 11 to Ansible runner
- **11a** (Ansible runner): `ansible-runner` in `ee-platform:2.17`, `limit: vm-0042`, SSH key from Vault, `retry.maxAttempts: 1`
- **11b** (Temporal + interpreter): `job.finished`; `AuditListener` hash chain per job, secrets redacted, copied to the SIEM → 12 to catalog and job page
- **12a** (catalog and job page): `GET /api/v1/jobs/{id}/events` (SSE)

## Restart 100 VMs in waves

**In plain words:** Mateo restarts 100 VMs in one request. Every VM is checked first, then they go out in waves: 5 first, then more. When too many fail, the rest stop, and Mateo retries just the ones left.

**Technically:** Generated `:batch` route: every row validated and judged by `invoke`, one parent job, `WaveScheduler` waves with `maxParallel`, child jobs, `stopIfFailedPercent`, then `:retry-failed-rows`.

### Steps in plain words

- **Start** (Mateo's screens): Mateo sends 100 VMs to restart: 5% first, then 25%, then the rest, 10 at a time, stop if more than 5% fail → 1 to Shop window
- **1a** (Shop window): Checks every VM before anything starts
- **1b** (Shop window): Asks the company rules about each VM → 2 to Company rules
- **2a** (Company rules): Judges each VM on its own. A single no refuses the whole request
- **2b** (Shop window): Counts the whole batch as one run against the allowance → 3 to Job desk
- **3a** (Job desk): Makes one parent job with 100 rows
- **3b** (Job desk): Plans the waves: 5 VMs, then up to 25, then the rest → 4 to Engine
- **4a** (Engine): Wave 1: 5 restarts, each its own child job → 5 to Outside system
- **5a** (Outside system): Restarts them
- **5b** (Engine): Wave 1 done, nothing failed → 6 to Job desk
- **6a** (Job desk): Opens wave 2 → 7 to Engine
- **7a** (Engine): Wave 2: 2 of 20 fail, which is over the 5% limit → 8 to Job desk
- **8a** (Job desk): Stops. The rest are skipped and nothing else is touched → 9 to Mateo's screens
- **9a** (Mateo's screens): Mateo sees one parent job: which VMs succeeded, which failed and which were skipped
- **9b** (Mateo's screens): After fixing the cause, Mateo retries only the failed and skipped VMs → 10 to Job desk
- **10a** (Job desk): Runs just those in a new wave

### Steps, technical

- **Start** (catalog and job page): `POST /api/v1/vms:restart:batch {items[100], rollout: {waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5}}` → 1 to catalog and gateway
- **1a** (catalog and gateway): Schema and registry per row; 400 lists the bad rows
- **1b** (catalog and gateway): `invoke` per row, on its own input and environment → 2 to OPA policy engine
- **2a** (OPA policy engine): 403 naming the rows; the batch keeps the strictest decision; one approval if any row needs it
- **2b** (catalog and gateway): One quota token for the batch → 3 to runs slice
- **3a** (runs slice): `kind: batch`, `Batch.rows`
- **3b** (runs slice): `WaveScheduler` → 4 to Temporal + interpreter
- **4a** (Temporal + interpreter): Child workflows, ids = child job ids, `maxParallel` 10 → 5 to provider API
- **5a** (provider API): Provider calls
- **5b** (Temporal + interpreter): `batch` event, wave `succeeded` → 6 to runs slice
- **6a** (runs slice): Next wave, 20 rows → 7 to Temporal + interpreter
- **7a** (Temporal + interpreter): Failed percent 10 > `stopIfFailedPercent` 5 → 8 to runs slice
- **8a** (runs slice): `stoppedReason`; remaining rows `skipped` → 9 to catalog and job page
- **9a** (catalog and job page): Job page: `progress {done, total, failed}`, waves, rows
- **9b** (catalog and job page): `POST /jobs/{id}:retry-failed-rows` → 10 to runs slice
- **10a** (runs slice): New wave of failed, cancelled and skipped rows

## Fix a failed step and run it again

**In plain words:** A step fails. Instead of giving up, the job pauses and asks Mateo what to do. Mateo fixes the input and retries just that step. Later, Mateo reruns from one step and compares with the last good run.

**Technically:** A failure outside every `try` puts the job in `needs-attention`; `:retry` with edited input goes to the engine as the `attention` signal; `:rerun {fromStep}` starts a new job; `/replay?compareTo=` diffs the runs.

### Steps in plain words

- **Start** (Mateo's screens): Mateo runs Create VM with size xlarge → 1 to Job desk
- **1a** (Job desk): Starts the job → 2 to Engine
- **2a** (Engine): Step Create VM calls the API → 3 to Outside system
- **3a** (Outside system): Refuses: no room for xlarge
- **3b** (Engine): The step failed and nothing was set up to catch it, so the job pauses and waits for a person instead of failing → 4 to Mateo's screens
- **4a** (Mateo's screens): Mateo sees the paused step, the error and four choices: retry, change the input, skip, or stop and undo
- **4b** (Mateo's screens): Mateo changes the size to large and retries that step → 5 to Job desk
- **5a** (Job desk): Checks Mateo may steer this job → 6 to Engine
- **6a** (Engine): Runs the step again with the new input → 7 to Outside system
- **7a** (Outside system): Creates the VM
- **7b** (Engine): Finishes the rest of the job → 8 to Mateo's screens
- **8a** (Mateo's screens): Later Mateo wants the last step redone, so picks Re-run from a step → 9 to Job desk
- **9a** (Job desk): Checks the input and the rules again, then starts a new job from that step
- **9b** (Job desk): Compares the new run with the last good one, field by field → 10 to Mateo's screens
- **10a** (Mateo's screens): Mateo sees exactly what differs between the two runs

### Steps, technical

- **Start** (catalog and job page): `POST /api/v1/vms` → 1 to runs slice
- **1a** (runs slice): `StartJob` → 2 to Temporal + interpreter
- **2a** (Temporal + interpreter): `call: provider` `vm.create` → 3 to provider API
- **3a** (provider API): 409 `conflict`
- **3b** (Temporal + interpreter): `needs-attention`; `Attention.options: retry, edit-input, skip, abort`; `attentionTimeout` → 4 to catalog and job page
- **4a** (catalog and job page): Job page attention card; Abort rolls finished steps back
- **4b** (catalog and job page): `POST /jobs/{id}:retry {step, input: {size: large}}` → 5 to runs slice
- **5a** (runs slice): 403 for anyone who may not, naming who started it → 6 to Temporal + interpreter
- **6a** (Temporal + interpreter): Signal `attention` with the decision → 7 to provider API
- **7a** (provider API): 201
- **7b** (Temporal + interpreter): `job.finished` succeeded → 8 to catalog and job page
- **8a** (catalog and job page): `POST /jobs/{id}:rerun {fromStep}` → 9 to runs slice
- **9a** (runs slice): Source input merged and checked like a start; `invoke` policy; preview kept if the input is unchanged
- **9b** (runs slice): `GET /jobs/{id}/replay?compareTo=` → 10 to catalog and job page
- **10a** (catalog and job page): Steps with `diff {field, before, after}`

## One workflow builds what another needs

**In plain words:** Mateo asks for a VM from a RHEL 9 image that does not exist yet. Create VM first runs Build VM image, gets the new image's id, checks Mateo may use it, then builds the VM and notes what it was built from.

**Technically:** An `x-resource` input with `orCreateWith: build-vm-image` takes `{create: …}`; the interpreter runs the producer as child step `image.create`, `ChildResults` carries the registration, a `use` step waits for `ready` and asks `resource-access`, and `register` records `builtFrom`.

### Steps in plain words

- **Start** (Mateo's screens): Mateo asks for VM web-01 and, instead of picking an image, asks for a new RHEL 9 one → 1 to Shop window
- **1a** (Shop window): Sees the image can be an id or a request to build one, and checks the request against Build VM image's own form → 2 to Engine
- **2a** (Engine): Before its first step, Create VM runs Build VM image as a child job → 3 to Outside system
- **3a** (Outside system): Builds the image
- **3b** (Engine): The child registers what it made → 4 to Resource list
- **4a** (Resource list): Records image img-77, owned by team-compute
- **4b** (Engine): Back in Create VM, "the image" now means img-77
- **4c** (Engine): Waits until the image is ready → 5 to Resource list
- **5a** (Resource list): img-77 is ready
- **5b** (Engine): Asks whether Mateo may use this image → 6 to Company rules
- **6a** (Company rules): Yes
- **6b** (Engine): Creates the VM from the image → 7 to Outside system
- **7a** (Outside system): Creates web-01
- **7b** (Engine): Registers the VM, noting it was built from img-77 → 8 to Resource list
- **8a** (Resource list): Records web-01 and the link. The image cannot be retired while web-01 depends on it

### Steps, technical

- **Start** (catalog and job page): `POST /api/v1/vms {name: web-01, image: {create: {os: rhel-9}}}` → 1 to catalog and gateway
- **1a** (catalog and gateway): `x-resource {type: image, orCreateWith: build-vm-image}`; problems under `image.create.*` → 2 to Temporal + interpreter
- **2a** (Temporal + interpreter): Child step `image.create`, same trace → 3 to provider API
- **3a** (provider API): Provider calls of the child
- **3b** (Temporal + interpreter): `register` step in the child → 4 to resources registry
- **4a** (resources registry): Resource `image`, `status`, `owner`
- **4b** (Temporal + interpreter): `ChildResults` carries the registration; `$input.image` = `img-77`
- **4c** (Temporal + interpreter): `use` with `waitUntil: ready` → 5 to resources registry
- **5a** (resources registry): `resource.*` events, or polling with backoff on Temporal
- **5b** (Temporal + interpreter): `PolicyPort` phase `resource-access` → 6 to OPA policy engine
- **6a** (OPA policy engine): `allow`
- **6b** (Temporal + interpreter): `vm.create` → 7 to provider API
- **7a** (provider API): 201
- **7b** (Temporal + interpreter): `register` with `builtFrom: [img-77]` → 8 to resources registry
- **8a** (resources registry): `GET /resources/{id}/lineage`; `:retire` answers 409 `resource has dependents`

## Describe it and get a draft

**In plain words:** Sam describes a workflow in plain English. The AI helper reads what already exists, drafts it from those pieces, and checks it with the platform. Sam reviews it on the canvas and submits it for the usual review.

**Technically:** `/assistant/sessions` streams over SSE; `DevinAssistant` runs playbook `!workflow-draft` with a token limited to `registry:read`, `workflows:validate`, `workflows:dry-run`; the platform re-checks the structured draft; Submit opens a change request.

### Steps in plain words

- **Start** (Sam's screens): Sam types: "Restart a VM, but only if no backup is running, with a sign-off in prod" → 1 to Builder chat
- **1a** (Builder chat): Hands the request to the AI helper with Sam's team and a pass that can only read and check, never publish → 2 to AI helper
- **2a** (AI helper): Reads the request
- **2b** (AI helper): Looks up the building blocks the platform already has → 3 to Workflow desk
- **3a** (Workflow desk): Lists the providers, published workflows, playbooks and credential names, never values
- **3b** (AI helper): Drafts the workflow from existing pieces only, adding the sign-off and a notice if it fails → 4 to Workflow desk
- **4a** (Workflow desk): Checks the draft. The AI fixes what is wrong and asks again, up to 5 times
- **4b** (AI helper): Hands back the draft, a summary and anything it could not fill in → 5 to Builder chat
- **5a** (Builder chat): Checks the draft again itself rather than trusting it → 6 to Sam's screens
- **6a** (Sam's screens): Sam sees the draft on the canvas with any issues, and asks for a change in the chat if needed
- **6b** (Sam's screens): Sam submits it → 7 to Workflow desk
- **7a** (Workflow desk): Opens a change request, with the same checks and review as any other workflow

### Steps, technical

- **Start** (frontend (author)): `POST /assistant/sessions {mode: create}`, then `POST /assistant/sessions/{id}/messages {text}` → 1 to assistant slice
- **1a** (assistant slice): `DevinAssistant`: v3 API session, playbook `!workflow-draft`, structured output schema, `PLATFORM_TOKEN` with the assistant scopes → 2 to Devin
- **2a** (Devin): DESCRIPTION, MODE, CONTEXT blocks
- **2b** (Devin): `GET /api/v1/registry/*` → 3 to workflows slice
- **3a** (workflows slice): step-types, providers, workflows, resource-types, playbooks, credentials, workflows/search
- **3b** (Devin): `run` > provider action > `http`; `approval` for prod; `workflow.catch` with notify → 4 to workflows slice
- **4a** (workflows slice): `POST /workflows:validate`, then `:dry-run`
- **4b** (Devin): Structured output: `workflowYaml`, `summary`, `missing` → 5 to assistant slice
- **5a** (assistant slice): Platform's checks of the draft; SSE `draft`, `validation`, `done` → 6 to frontend (author)
- **6a** (frontend (author)): Follow-ups go on the same session; `?session=` survives a reload
- **6b** (frontend (author)): Submit as a change request → 7 to workflows slice
- **7a** (workflows slice): `POST /workflows/{name}:publish`; the AI never publishes, merges or runs anything

