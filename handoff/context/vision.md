# Product vision

Written for the handoff from the owner's instructions as recorded during the build (2026-10-17 to 2026-10-23). The owner is Gonzalo (GitHub `gonzalomunoz1024`), an infrastructure engineering lead at a large enterprise ("the firm").

## The problem

The firm's infrastructure is run by many teams on many systems: vCenter for VMs, MongoDB Ops Manager for databases, backup services, image builders, Ansible libraries, Terraform modules. Every routine change (restart a VM, upgrade a MongoDB cluster, patch a CVE, create a VM from a new image) is either a ticket that waits for a person, or a script only its author understands. There is no single place to see what exists, who owns it, what it costs, or whether it still matches what was asked for.

## The ultimate goal

In the owner's words: **a junior infrastructure engineer can easily use the tool and onboard a provider.** Every design choice is judged against that sentence. If a junior engineer would need to read code, guess a field, or ask a senior to understand an error, the design is wrong.

## Two products, two personas, one system

### 1. The automation platform (branch `main`, finished)

**Persona: the provider team** (infrastructure engineers who own a system, e.g. the VM team). Demo people: Sam builds (a junior engineer in team-compute), Gonzalo reviews, Mateo runs.

- A provider team registers its system as a **provider** through a no-code wizard: an OpenAPI spec (URL, file or Git), a few HTTP endpoints, an Ansible repository or a Terraform module. The platform makes the REST calls itself from the registration; SDK code is only for custom logic.
- The team then builds **workflows** (CRUD and operations on its resources: create VM, restart VM, upgrade MongoDB) on a canvas that looks and feels exactly like n8n.
- A workflow is stored as **config as code**: YAML in Git, published through a change request a teammate reviews.
- Publishing generates the workflow's REST endpoint, its OpenAPI entry and its MCP tool (so AI agents can call it). Steps run on **Temporal** through one generic Java interpreter workflow.
- A natural-language builder lets someone describe a workflow in plain English; Devin drafts it from existing pieces, and it goes through the same review.
- The platform is generic: nothing in its code knows what a VM is. Every scenario is content a provider team builds.

Why it was built this way: n8n itself was rejected because its engine and editor are coupled and its license is unclear for this use, so the platform owns a React Flow editor that copies n8n's look and an interpreter on Temporal for durability. Git is the source of truth so every change is reviewed, versioned and auditable.

### 2. The customer console (orphan branches `console-*`, being finished by you)

**Persona: the consumer team** (the firm's internal teams who need infrastructure: application teams, data teams). They do not want to know about providers or workflows; they want what AWS gives its customers.

- A console that mirrors the **AWS Management Console** one to one in layout and interactions: sign in with the firm's SSO, an account and region switcher, a services menu, global search, per-service landing pages, resource lists with filters and saved views, a detail page with tabs, a create wizard, an Actions menu, tags, an activity trail, IAM, quotas, cost and billing.
- **Fully dynamic**: every platform `kind: ResourceType` becomes a console service and every published workflow whose noun is that type becomes a button. A new provider resource type appears in the console the moment it is published, with no UI release.
- Resources are named by **wfrn** (the platform's answer to the ARN): `wfrn:<partition>:<service>:<region>:<account>:<type>/<type>-<ulid>`.
- **Massive scale** from day one: 10,000 users, 10 million resources.
- Backend services of its own (BFF, identity, state, drift, search, cost) so the console has its own memory, permissions and drift detection, while the platform stays untouched and does the real work.
- **Phase 9, "Beyond AWS"**: things AWS does not do well, chosen by Gonzalo as the "wow factor": a copilot that turns plain English into a filter or a prefilled wizard (on Devin), a failure explainer with "Retry with fix", budgets and forecasts, a resource map, an executive home (lead time, self-service share, hours saved, spend by team), compliance scorecards, blueprints (multi-resource stacks), time travel, chargeback, access reviews, just-in-time elevation, ChatOps, SDKs and a CLI, mobile approvals, rightsizing.

Why a separate console instead of extending the platform UI: the platform UI is for people who build automation; the console is for people who consume infrastructure. Different mental model, different scale, different permission model (AWS-style accounts and IAM instead of platform teams), and Gonzalo ruled that the platform must not be modified for it. The console therefore sits on the platform's API as a customer would, and everything it needs from the platform is written as a change request for the platform team (`hub/platform-changes-README.md`).

## How the two fit together

```
consumer team ──> console-web ──> console-bff ──> identity / state / drift / search / cost services
                                       │                         │
                                       └──── platform API ◄──────┘  (target contract; platform-fake until P-01..P-23 ship)
                                                   │
provider team ──> platform UI (designer, wizard) ──┘──> Temporal ──> providers (vCenter, Ops Manager, Ansible, ...)
```

A provider team publishes a `ResourceType` and its workflows on the platform. The console reads the type and the catalog, draws the service, and runs the workflows on behalf of the consumer through the platform's API (`X-On-Behalf-Of`, P-02). The platform's event feed (P-01) tells the state service what happened; the drift service reads providers through the platform's observe API (P-09) and offers a one-click revert, never an automatic one.

## The owner's standing preferences

- Decide as a lead principal engineer; do not wait for him. Record decisions.
- Terminology: "workflow", never "capability".
- Look: n8n for the designer, Atlas (Apple-inspired) for everything else, AWS's layout for the console, Jony Ive restraint.
- Backend: Java 21, Spring WebFlux, CQRS, hexagonal, MongoDB. Platform on Maven, console services on Gradle.
- He likes journeys in pictures (screenshots of each step) and asks for ETAs in agent minutes.
- Declined: schedules and change windows. Out of scope: network access, Terraform export.
- The firm's "IDP tiles" tool stays a mock placeholder until he shares examples. The firm's OPA policy engine is called through a mock until wired in.
