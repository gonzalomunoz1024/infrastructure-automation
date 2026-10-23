# Create a VM in the designer, end to end

What a junior engineer does to build and run a *Create VM* workflow using only the designer:
a blank canvas, two HTTP steps that call the VM API, a credential stored from a step's own
credential field, a test run, publish, a reviewer's merge and a run. No provider is onboarded;
compare [Onboard a VM provider, end to end](onboard-a-vm-provider.md), where the same API
becomes a provider first. Every picture is the real console on a running backend; the only
stand-in is the VM API itself.

The VM API here is `scripts/api-flows/fake-vm-api.cjs`, a small server that creates, reads and
deletes VMs behind a bearer token and keeps them in memory. The backend ran with the `demo,live`
profiles, so the job called it over HTTP. Every value in the pictures is made up: the token is
`example-token` and the VM is `web-07`.

The people: `junior` (team-compute) builds and runs the workflow, and `gonzalo`, an admin,
reviews and merges. Nobody merges their own change request unless they are an admin, so any
teammate on the owner team could review instead.

## 1. A blank workflow

Everything happens in the designer; the only page outside it is the run form at the end.

![The junior engineer opens Workflows: everything the platform can run. New workflow starts one.](screenshots/designer-journey/01-workflows.png)

*The junior engineer opens Workflows: everything the platform can run. New workflow starts one.*

![New workflow, Blank: a title, the name the file and the URL use, a noun and a verb that name its endpoint, and the owner team.](screenshots/designer-journey/02-new-blank.png)

*New workflow, Blank: a title, the name the file and the URL use, a noun and a verb that name its endpoint, and the owner team.*

![The designer opens on the n8n-style canvas: the API trigger every workflow has, and a button for the first step.](screenshots/designer-journey/03-canvas.png)

*The designer opens on the n8n-style canvas: the API trigger every workflow has, and a button for the first step.*

## 2. The input

The fields the person running it fills in are set in the designer's Settings.

![Settings, Input: the fields the person running it fills in. They become the run form, the API body and the MCP tool’s arguments.](screenshots/designer-journey/04-inputs.png)

*Settings, Input: the fields the person running it fills in. They become the run form, the API body and the MCP tool’s arguments.*

## 3. An HTTP step that creates the VM

An HTTP request step calls the VM API directly, so no provider has to be onboarded first.

![Add first step opens the step list. HTTP request calls any API: no provider is needed.](screenshots/designer-journey/05-node-creator.png)

*Add first step opens the step list. HTTP request calls any API: no provider is needed.*

![The step’s parameters: POST to the VM API, with the JSON body mapped from the input with {{ }} expressions. The input’s fields on the left can be dragged onto a parameter.](screenshots/designer-journey/06-http-step.png)

*The step’s parameters: POST to the VM API, with the JSON body mapped from the input with {{ }} expressions. The input’s fields on the left can be dragged onto a parameter.*

## 4. The credential, without leaving the canvas

The API token is stored from the step's own credential field.

![The step’s credential field lists the credentials already stored, by name and kind. None is for the VM API yet; the list ends with New credential.](screenshots/designer-journey/07-credential-field.png)

*The step’s credential field lists the credentials already stored, by name and kind. None is for the VM API yet; the list ends with New credential.*

![New credential opens over the canvas: a token for the VM API, stored once under a name. Nobody can read the value back.](screenshots/designer-journey/08-credential-dialog.png)

*New credential opens over the canvas: a token for the VM API, stored once under a name. Nobody can read the value back.*

![The field now names the credential: the step keeps only the reference vault:acme-cloud/api-token, and the token goes as a bearer header on each call.](screenshots/designer-journey/09-credential-set.png)

*The field now names the credential: the step keeps only the reference vault:acme-cloud/api-token, and the token goes as a bearer header on each call.*

## 5. Read the VM back, and the outputs

![A second HTTP step reads the VM back: GET with the id the create step answered with ($json is the previous step’s output), and the same credential, now picked from the list.](screenshots/designer-journey/10-get-step.png)

*A second HTTP step reads the VM back: GET with the id the create step answered with ($json is the previous step’s output), and the same credential, now picked from the list.*

![Settings, Outputs: what a finished job answers with, as expressions over the steps: the VM’s id, its address and its state, read back by Get VM.](screenshots/designer-journey/11-outputs.png)

*Settings, Outputs: what a finished job answers with, as expressions over the steps: the VM’s id, its address and its state, read back by Get VM.*

## 6. Check, test and publish

![The canvas with both steps. The live checks run as you edit and Validate runs the server’s: no issues.](screenshots/designer-journey/12-validated.png)

*The canvas with both steps. The live checks run as you edit and Validate runs the server’s: no issues.*

![Test workflow: a dry run with the values a person would type. Nothing is called; each step shows what it would send.](screenshots/designer-journey/13-test-run.png)

*Test workflow: a dry run with the values a person would type. Nothing is called; each step shows what it would send.*

![The YAML drawer: the file the designer has been writing all along, the input schema and then the steps. It is what the change request will hold.](screenshots/designer-journey/14-yaml.png)

*The YAML drawer: the file the designer has been writing all along, the input schema and then the steps. It is what the change request will hold.*

![Publish opens a change request with the workflow’s checks, right in the designer. The author waits for a teammate to merge it.](screenshots/designer-journey/15-publish.png)

*Publish opens a change request with the workflow’s checks, right in the designer. The author waits for a teammate to merge it.*

## 7. The reviewer merges

![A reviewer opens the change request: the workflow file, what changed and the checks. Then Merge.](screenshots/designer-journey/16-cr-review.png)

*A reviewer opens the change request: the workflow file, what changed and the checks. Then Merge.*

![Merged: version 0.1.0 of Create VM is published, with its endpoint, its MCP tool and a run form in the catalog.](screenshots/designer-journey/17-merged.png)

*Merged: version 0.1.0 of Create VM is published, with its endpoint, its MCP tool and a run form in the catalog.*

## 8. Run it

![The run form, generated from the input defined in the designer. The junior engineer asks for web-07.](screenshots/designer-journey/18-run-form.png)

*The run form, generated from the input defined in the designer. The junior engineer asks for web-07.*

![The job: both steps succeeded. Create VM called the VM API and Get VM read the VM back; the output is its id, address and state.](screenshots/designer-journey/19-job.png)

*The job: both steps succeeded. Create VM called the VM API and Get VM read the VM back; the output is its id, address and state.*

![Back in the designer, Executions shows the job on the canvas: each step green, and its input and output a click away.](screenshots/designer-journey/20-executions.png)

*Back in the designer, Executions shows the job on the canvas: each step green, and its input and output a click away.*

## What the designer does not do yet

- Get VM reads the VM once. The designer has a Wait step that waits until a condition holds,
  and a Retry setting on every step; neither is needed here, because the fake API answers with a
  VM that is already running.
- The designer has no Run button: the run starts from the catalog's run form, and Executions in
  the designer shows the job afterwards.
- Nothing in this workflow registers the VM, so it is not in Resources; the VM API's own list
  has it. The step list's Resources group is where a register step would come from.

## To run it yourself

The pictures come from `scripts/api-flows/journey-vm-designer.cjs`, which clicks through the
console with Playwright. To run it yourself:

```bash
FAKE_VM_TOKEN=example-token node scripts/api-flows/fake-vm-api.cjs 18091 &
(cd backend && mvn -q package -DskipTests)
java -jar backend/target/platform-*.jar --server.port=18080 --platform.content.dir=$PWD \
  --spring.profiles.active=demo,live &
(cd frontend && IAF_BACKEND=http://localhost:18080 npx vite --port 3400) &
NODE_PATH=$PWD/node_modules node scripts/api-flows/journey-vm-designer.cjs \
  http://localhost:3400 http://localhost:18080/api/v1 http://localhost:18091
```

It writes the screenshots and `captions.json` to `docs/screenshots/designer-journey/`. Start
from a fresh backend and a fresh VM API: the workflow can only be created once, and the VM API
refuses a second `web-07`.
