# API flows

End-to-end exercises of a running stack, the way a user and an integration would use it: every
flow calls the REST API as different demo users, follows jobs to their end and checks what came
back. They complement the unit and adapter tests: the backend is real, the demo providers answer
from their samples, and a flow that finds a wrong answer exits non-zero with the issue list.

```bash
scripts/dev.sh                                      # backend on :8080, frontend on :3000
python3 scripts/api-flows/flows1.py                 # workflows, drafts, validation, imports, dry runs
python3 scripts/api-flows/flows2.py                 # jobs, approvals, attention, batches, triggers, resources, releases
python3 scripts/api-flows/flows3.py                 # providers, wizard imports, assistant, actuator
python3 scripts/api-flows/flows4.py                 # dependencies, previews, create forms, replay, publish checks
node scripts/api-flows/smoke.cjs                    # every page in a browser: console errors, failed calls, error states
node scripts/api-flows/tour.cjs                     # the README's screenshots into docs/screenshots
node scripts/api-flows/fake-vm-api.cjs 18090        # a stand-in VM API with an OpenAPI spec (FAKE_VM_TOKEN)
node scripts/api-flows/journey-vm-provider.cjs      # docs/onboard-a-vm-provider.md's pictures, on the demo,live profiles
```

`IAF_BASE` points the flows at another backend (`IAF_BASE=http://localhost:18080`); the two
browser scripts take the frontend and API URLs as arguments. The flows are stateful (they create
drafts, jobs and resources), so run them on a fresh demo stack; a second run on the same stack
reports the conflicts the first run left behind.
