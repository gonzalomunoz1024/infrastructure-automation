# Glossary

| Term | Meaning |
|---|---|
| **Platform** | The automation platform on branch `main` (c9529d5). Finished. Never modified by console work. |
| **Console** | The customer console: the AWS-like UI (`console-web`) plus its backend services, on the `console-*` orphan branches. |
| **Provider** | A system the platform automates (a VM API, Ops Manager, an Ansible repository, a Terraform module), registered as `kind: Provider` through the wizard or YAML. |
| **Workflow** | A sequence of steps on the platform (`kind: Workflow`, YAML in Git) that does one thing to a resource: create, restart, update, delete. Never call it a "capability". |
| **ResourceType** | `kind: ResourceType`: a kind of resource (vm, database, image, volume, environment) with a JSON schema. In the console, each one is a "service" page. |
| **Registry** | The platform's record of every resource a workflow created or registered, with lineage (`builtFrom`). |
| **Noun / verb** | A workflow's `noun` is the resource type it acts on; its `verb` is the action (`create`, `update`, `restart`, `delete`). The console turns verbs into buttons. |
| **Change request (CR)** | The platform's review step: a workflow or provider change goes on a Git branch and waits for an owner-team reviewer to merge. |
| **Job** | One run of a workflow on the platform. A failed step pauses the job as `needs-attention` (retry, skip, abort) rather than failing it. `:rerun` starts a new job from a step of an ended one. |
| **Preview / plan** | A dry run of a workflow (blast radius, policy, cost) before it runs. Providers may publish a "plan" operation. |
| **Bulk run / batch** | One request that runs a workflow per target in waves, stopping on errors, shown as one parent job (`POST <path>:batch`). |
| **Canary** | A new workflow or provider version taking a share of calls (10%, 50%, 100%) behind health gates. |
| **wfrn** | "Workflow resource name", the console's ARN: `wfrn:<partition>:<service>:<region>:<account>:<type>/<type>-<ulid>`. Spec: `hub/contracts-wfrn-SPEC.md`. Java and TypeScript libraries share fixtures. |
| **Account** | A console account: a team's workspace, owned by an identity-provider group, charged to a cost centre. Demo: `123456789012` Compute, `210987654321` Data. |
| **Region** | A `kind: Region`; only `global` exists, but every key carries the region so more need no hacks. |
| **Organization** | All accounts of the firm. Organization admins (decision 34) manage guardrails, closures and break glass. |
| **Managed roles** | `viewer`, `operator`, `account-admin`, `auditor` (shown Viewer, Operator, Admin, Auditor), with groups `viewers`, `members`, `owners`, `auditors`. |
| **Guardrail / boundary** | Decision 28: organization-wide statements like AWS SCPs; a role's permission boundary like AWS's. |
| **Elevation / break glass** | Time-boxed roles (decision 28): a person asks with a reason, a rule or admin approves; break glass is the emergency version. |
| **Step-up** | Signing in again (within 10 minutes) before a destructive action; refusal is 403 `step-up.required`. |
| **Denial** | The body of every 403: which layer refused (console IAM or the platform), the action, the reason, the statement, whether access can be requested. |
| **BFF** | Backend for frontend, `console-bff`: the only API the web calls, the only issuer of internal tokens. |
| **Internal JWT / service token** | ES256 tokens the BFF signs for calls to other services (decisions 21, 23, 35). One `aud` per token. A token without `acct` is the "console-internal principal". |
| **Hub** | Branch `console-root`: the shared contracts, decisions, type overlays, rule packs, blueprints and the platform-changes plan. No code. |
| **Hub stack / steward / turn** | `console-root--stack` (draft PR #30) is every open hub PR merged in order; each merge is a "turn" (turn 1 = #2 … turn 15 = #46). The "steward" was the build session that kept it (the search indexer session). |
| **Decision N** | A numbered cross-service decision in the hub README. 1 to 10 are Gonzalo's answers to the plan's open questions; 11 to 35 were taken during the build. Next free: 36. See `decisions.md`. |
| **README decision N** | A service-local decision in a service's own README (for example console-web decisions 1 to 80). Not the same numbering as hub decisions. |
| **P-NN** | A planned platform change (P-01 to P-23), written up in `hub/platform-changes-README.md` for the platform team. The console assumes all of them ship. Next free: P-24. |
| **Target contract** | `contracts/platform/openapi-target.yaml` and `asyncapi-target.yaml`: the platform's API as it will be after P-01 to P-23. |
| **platform-fake** | An in-memory implementation of the target contract in `console-libs` (library plus `platform-fake-app` on port 18090). Every console demo and test runs on it. |
| **contracts.lock** | Each service's pin of the hub commit and the sha256 of every contract file it copied. |
| **Type overlay** | `type-overlays/<type>.yaml`: the console hints (`console`, `observe`, `x-console`) the hub drafts for the sample types, in P-06's shape. |
| **x-console** | JSON-schema hints for console forms (grouping into wizard steps, widgets, labels). |
| **observe block** | How drift reads a type at the provider and maps fields (P-06, P-09). |
| **Drift / finding** | A difference between what the console recorded (desired) and what the provider reports (observed). One open finding per resource; revert, accept or ignore field, never automatic. |
| **Tiers (hot/warm/cold)** | Drift check intervals: 15 min, 1 h, 24 h. |
| **Unmanaged / shadow infrastructure** | Objects at a provider that no account manages; the finder guesses an owner and offers "Assign to team". |
| **Rule pack / scorecard** | `kind: RulePack` compliance rules (Rego plus a declarative twin) the drift service scores resources against (decision 26). |
| **Blueprint / stack** | `kind: Blueprint`: a multi-resource template; launching it creates a stack run by the state service and executed by the BFF (decision 33). |
| **Tombstone** | What a deleted resource's wfrn answers: 410 for 90 days (forever after a transfer). |
| **Transfer / re-home** | Moving a resource to another account (decision 30): state writes `Transferred` then `Imported`; the platform registry is re-homed by P-23. |
| **Sealed trail / digest / evidence pack** | The state service's hash-chained activity log, hourly signed digests per account, and the downloadable pack an auditor verifies offline (decision 29). |
| **Time travel** | Reading a resource or list `at` an instant; "revert to this" makes a plan the person confirms. |
| **Copilot / AssistantPort** | The console's assistant behind a port in the BFF (Devin API first); proposes, never acts (decision 27). |
| **Executive home / insights** | The console home for leaders: managed resources, self-served share, lead time, hours saved, drift, spend (decision 27). |
| **Phase / WP / gate** | The plan's roadmap: phases 0 to 9, each made of work packages (WP 0.1 … 9.22) and ending at a gate shown in the running console (`console-plan.md`). Handoff-local WP ids (R-1, S-1, B-2 …) are in `HANDOFF.md` section 7. |
| **Lane** | A parallel stream of work in the plan (A to F) or in the Devin playbook (0 to 5). |
| **F-NN** | Integration bugs found by the whole-system e2e run (draft PR #55), F-1 to F-15. |
| **Thread / session** | One of the parallel Claude build sessions. Commit messages and PR comments name them ("the State service thread"). They no longer exist. |
| **Atlas** | The Apple-inspired design system from Gonzalo's service-dashboard app, vendored verbatim as `packages/ui`. |
| **console-kit** | The console's own components (forks of SchemaForm, VirtualTable, StatusBadge, charts) beside Atlas. |
| **Mock mode / live mode** | console-web runs with an in-browser mock of the BFF (`npm run dev:mock`) or against the real BFF. |
| **Demo profile** | Every Java service's in-memory profile with no Kafka, OpenSearch or real platform. |
| **Dev headers** | Demo-only headers (`X-Console-Account`, `X-User` …) that stand in for tokens. |
| **IDP tiles** | The firm's internal developer portal tiles; a mock placeholder until Gonzalo shares examples. |
