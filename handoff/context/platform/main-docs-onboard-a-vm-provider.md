# Onboard a VM provider, end to end

What a junior engineer does to let workflows create virtual machines: add the VM API as a
provider, try it, get it reviewed, build a *Create VM* workflow on it and run it. Every picture
is the real console on a running backend; the only stand-in is the VM API itself.

The VM API here is `scripts/api-flows/fake-vm-api.cjs`, a small server that publishes an
OpenAPI document (list, create, get and delete VMs, with a bearer token) and keeps its VMs in
memory. The backend ran with the `demo,live` profiles, so the try calls and the job called it
over HTTP; nothing in the platform knows about VMs.

The people: `junior` (team-compute) does the work, and `gonzalo`, an admin, reviews and merges.
Nobody merges their own change request unless they are an admin, so any teammate on the
owner team could review instead.

## 1. Add the provider

The VM API publishes an OpenAPI document, so onboarding it is a form, not code.

![The junior engineer opens Providers: the systems workflows may call. Add provider starts the wizard.](screenshots/journey/01-providers.png)

*The junior engineer opens Providers: the systems workflows may call. Add provider starts the wizard.*

![Type: the VM API publishes an OpenAPI document, so the provider is OpenAPI. The name is what workflows call it by; the owner is the junior engineer’s team.](screenshots/journey/02-wizard-type.png)

*Type: the VM API publishes an OpenAPI document, so the provider is OpenAPI. The name is what workflows call it by; the owner is the junior engineer’s team.*

![Connect: the API token is stored once as a credential. The provider names it as vault:acme-cloud/token; nobody can read the value back.](screenshots/journey/03-credential.png)

*Connect: the API token is stored once as a credential. The provider names it as vault:acme-cloud/token; nobody can read the value back.*

![Connect: the spec is imported from its URL, each environment’s base URL comes from it, and Test connection probes dev and prod with the token. The result is kept as review evidence.](screenshots/journey/04-connect.png)

*Connect: the spec is imported from its URL, each environment’s base URL comes from it, and Test connection probes dev and prod with the token. The result is kept as review evidence.*

![Actions: each operation becomes an action. Effect and retry come from the HTTP method, and a delete needs an approval. The ids are renamed vm.create, vm.get and so on, and vm.get previews vm.delete.](screenshots/journey/05-actions.png)

*Actions: each operation becomes an action. Effect and retry come from the HTTP method, and a delete needs an approval. The ids are renamed vm.create, vm.get and so on, and vm.get previews vm.delete.*

![Actions: error classes say which failures are retried. Start from the usual ones adds not_found, conflict, rate_limited and unavailable.](screenshots/journey/06-errors.png)

*Actions: error classes say which failures are retried. Start from the usual ones adds not_found, conflict, rate_limited and unavailable.*

## 2. Try every action against dev

Each action is called for real before anyone reviews it. The answers become the samples the dry runs use.

![Try it: one real call in dev, with a form generated from the operation. A create changes something, so the wizard asks first.](screenshots/journey/07-try-confirm.png)

*Try it: one real call in dev, with a form generated from the operation. A create changes something, so the wizard asks first.*

![Try it: the API created the VM and its answer matches the schema. The answer is kept as review evidence and recorded as the sample that dry runs answer with.](screenshots/journey/08-try-create.png)

*Try it: the API created the VM and its answer matches the schema. The answer is kept as review evidence and recorded as the sample that dry runs answer with.*

![Try it: the delete removes the test VM again, so dev is left as it was.](screenshots/journey/09-try-delete.png)

*Try it: the delete removes the test VM again, so dev is left as it was.*

## 3. Review and merge

Publishing opens a change request. Someone else on the owner team reads it and merges it.

![Review: the provider file the wizard wrote, and the checks the change request will run again. Plain http is allowed only because the API is on this machine.](screenshots/journey/10-review-checks.png)

*Review: the provider file the wizard wrote, and the checks the change request will run again. Plain http is allowed only because the API is on this machine.*

![Review: the automated review of what happened in the wizard: spec pinned, connection tested, every action tried in dev, errors classified, an owner and an alert channel.](screenshots/journey/11-review-evidence.png)

*Review: the automated review of what happened in the wizard: spec pinned, connection tested, every action tried in dev, errors classified, an owner and an alert channel.*

![The change request: the new provider file, the checks and the review evidence the wizard kept. The author cannot merge it.](screenshots/journey/12-provider-cr.png)

*The change request: the new provider file, the checks and the review evidence the wizard kept. The author cannot merge it.*

![A reviewer from the owner team opens the same change request, reads the file and the evidence, and merges.](screenshots/journey/13-provider-cr-review.png)

*A reviewer from the owner team opens the same change request, reads the file and the evidence, and merges.*

![Acme Cloud is published: its actions, the SLO it is held to, and the health the platform computes from every call workflows make.](screenshots/journey/14-provider-page.png)

*Acme Cloud is published: its actions, the SLO it is held to, and the health the platform computes from every call workflows make.*

## 4. Build the Create VM workflow

The provider’s create operation becomes a draft workflow in one step; the designer is where it is shaped and tested.

![Workflows, New workflow, Import: the provider’s create operation becomes a draft workflow, with its form taken from the operation, a create step and a step that registers the VM.](screenshots/journey/15-workflow-import.png)

*Workflows, New workflow, Import: the provider’s create operation becomes a draft workflow, with its form taken from the operation, a create step and a step that registers the VM.*

![The designer: the draft on the n8n-style canvas. The API trigger is the generated endpoint; the steps call vm.create and register what it made.](screenshots/journey/16-designer.png)

*The designer: the draft on the n8n-style canvas. The API trigger is the generated endpoint; the steps call vm.create and register what it made.*

![A step’s parameters: the provider action and its input, mapped from the workflow’s input with {{ }} expressions. Fields can be dragged in from the left.](screenshots/journey/17-designer-step.png)

*A step’s parameters: the provider action and its input, mapped from the workflow’s input with {{ }} expressions. Fields can be dragged in from the left.*

![Test workflow: a dry run with the samples recorded in the wizard. Nothing is created; every step shows what it would send and get back.](screenshots/journey/18-designer-test.png)

*Test workflow: a dry run with the samples recorded in the wizard. Nothing is created; every step shows what it would send and get back.*

## 5. Publish the workflow

The workflow goes through the same review as the provider.

![Publish opens a change request with the workflow’s checks: schema, policy, a replay of recent jobs, the contracts with other workflows and the routes. The author waits for a teammate.](screenshots/journey/19-workflow-cr.png)

*Publish opens a change request with the workflow’s checks: schema, policy, a replay of recent jobs, the contracts with other workflows and the routes. The author waits for a teammate.*

![The reviewer merges it. Version 0.1.0 is published: it has its endpoint, its MCP tool and a run form in the catalog.](screenshots/journey/20-workflow-merged.png)

*The reviewer merges it. Version 0.1.0 is published: it has its endpoint, its MCP tool and a run form in the catalog.*

## 6. Run it and find the VM

Anyone allowed to run it now has a form in the catalog, an API endpoint and an MCP tool.

![The catalog’s run form, generated from the workflow’s input. Preview shows the blast radius before anything runs: the steps, what each one changes and the policy decision.](screenshots/journey/21-run-preview.png)

*The catalog’s run form, generated from the workflow’s input. Preview shows the blast radius before anything runs: the steps, what each one changes and the policy decision.*

![The job: both steps succeeded. Create a VM called the Acme Cloud API for real, and Register put the VM it made in the registry. Each step opens to show its input and output.](screenshots/journey/22-job.png)

*The job: both steps succeeded. Create a VM called the Acme Cloud API for real, and Register put the VM it made in the registry. Each step opens to show its input and output.*

![Resources: the new VM is in the registry, owned by the junior engineer’s team, where other workflows can find it.](screenshots/journey/23-resources.png)

*Resources: the new VM is in the registry, owned by the junior engineer’s team, where other workflows can find it.*

![The VM’s page: its attributes, the workflow and job that created it, its audit record and its lineage. A delete or another workflow picks it from here.](screenshots/journey/24-resource.png)

*The VM’s page: its attributes, the workflow and job that created it, its audit record and its lineage. A delete or another workflow picks it from here.*

## Replay it

The pictures come from `scripts/api-flows/journey-vm-provider.cjs`, which clicks through the
console with Playwright. To run it yourself:

```bash
FAKE_VM_TOKEN=journey-demo-token node scripts/api-flows/fake-vm-api.cjs 18090 &
(cd backend && mvn -q package -DskipTests)
java -jar backend/target/platform-*.jar --server.port=8080 --platform.content.dir=$PWD \
  --spring.profiles.active=demo,live &
(cd frontend && npx vite --port 3000) &
NODE_PATH=$PWD/node_modules node scripts/api-flows/journey-vm-provider.cjs \
  http://localhost:3000 http://localhost:8080/api/v1 http://localhost:18090
```

It writes the screenshots and `captions.json` to `docs/screenshots/journey/`. Start from a
fresh backend: the provider and the workflow can only be added once.
