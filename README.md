# Infrastructure Automation

A platform where a team designs an infrastructure workflow on an n8n-style canvas, stores it as
config as code in Git, and runs it on Temporal. Publishing a workflow generates its REST
endpoint, its OpenAPI entry and its MCP tool. The systems a workflow talks to (a vCenter, an Ops
Manager, a backup service, an Ansible library, a Terraform module) are registered as *providers*
through a wizard, without code: from an OpenAPI spec (a URL, a file or a Git repository), a few
HTTP endpoints, or imported from an Ansible repository or a Terraform module.

Everything a team builds (VM restart, MongoDB upgrade, Delete VM, Patch a CVE) is sample content
under `workflows/`. Nothing in `backend/` or `frontend/` knows what a VM is.

## Quick start

```bash
# Java 21, Maven 3.9, Node 22
scripts/dev.sh                 # backend on :8080 (demo profile), frontend on :3000
scripts/dev.sh --temporal      # the same, with the interpreter running on a local Temporal dev server
scripts/dev.sh --postgres      # the same, with the platform's state in PostgreSQL (docker compose's, or SPRING_DATASOURCE_URL)
```

Open <http://localhost:3000>. The demo profile keeps everything in memory, answers provider calls
from the samples recorded in each provider file, and seeds two weeks of history that agrees with
itself: the jobs, the releases that put the current versions out (one rolled back by its gates,
one promoted by hand, one canary still running), the change requests that published them and the
resources the jobs worked on. Provider health, canary gates and audit evidence are computed from
that history, not written into it. Switch users in Settings (`gonzalo` is an admin and approver,
`junior` can only run).

With `--postgres` the stores live in PostgreSQL instead of memory: published workflows, change
requests, providers with their canary candidates and releases, the wizard's review evidence, jobs
with their steps and events, provider-call samples, audit evidence and the rest survive a restart,
and the demo history is written once per database.

The generated surface:

| Surface | Where |
|---|---|
| Catalog of published workflows | `GET /api/v1/catalog` |
| A workflow's endpoint | its `api.path`, e.g. `POST /api/v1/vms/{vmId}:restart` |
| Bulk runs | `POST <path>:batch` with `{ items, defaults, rollout }` |
| Blast-radius preview | `POST <path>:preview` |
| OpenAPI 3.1 | `GET /openapi.json` |
| Swagger UI over it | <http://localhost:8080/swagger-ui> |
| MCP server (Streamable HTTP, JSON-RPC 2.0) | `POST /api/v1/mcp` |
| Job events | `GET /api/v1/jobs/{id}/events` (SSE), `ws://…/ws/jobs/{id}` |

## What is in the repo

```
backend/            Spring Boot 3.5 · Java 21 · WebFlux · CQRS · ports and adapters · Temporal SDK · JGit
frontend/           React 19 · Vite · TypeScript · Tailwind v4 · the n8n-style designer on @xyflow/react
packages/ui         the Atlas design system (tokens, kit, hooks) used by every page but the canvas
packages/workflow-checks   the editor's checks in TypeScript; the same rules run in Java and in CI
workflows/ providers/ resource-types/   sample content as config as code (kind: Workflow | Provider | ResourceType | PriceList)
sdk/                provider SDKs (Java, Go, Python) for custom-code providers on a team's own worker
devin/              the natural-language builder playbooks and their structured-output schemas
docs/               architecture, config as code, API, design language, contributing
```

## Documentation

- [Getting started](docs/getting-started.md): a first run, a first workflow, a first provider.
- [Architecture](docs/architecture.md): slices, CQRS rules, ports and adapters, the interpreter.
- [Config as code](docs/config-as-code.md): the `Workflow`, `Provider` and `ResourceType` files,
  step kinds and expressions.
- [API](docs/api.md): every platform endpoint and the generated ones.
- [Design language](docs/design-language.md): the n8n canvas and the Atlas shell.
- [Contributing](docs/contributing.md): build, conventions, commits.

## A tour in pictures

<!-- screenshots: filled in by the end-to-end run, see docs/screenshots -->

## Production adapters

The demo adapters are plain Spring components. Each production adapter sits behind a profile and
replaces its demo twin when the profile is on: `temporal` (the interpreter as a Temporal
workflow), `git` (JGit over the content repo), `opa` (the policy engine), `vault` (KV v2
credentials), `devin` (the natural-language builder), `oidc` (JWT identities), `live` (real HTTP
provider calls, and Ansible, Terraform, script and http steps run for real, see
[How automation steps run](docs/config-as-code.md#how-automation-steps-run)), `postgres` (the
stores in PostgreSQL, migrated by Flyway on start; the connection is `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`), `s3` (the artifact store on S3), `github`
(change requests as pull requests on the content repo).
`docker-compose.yml` starts a Temporal dev server, PostgreSQL and the stack on them.

## Deployment

`backend/Dockerfile` and `frontend/Dockerfile` (nginx serving the build and proxying `/api` and
`/ws` to the backend) build images that run as a non-root user, and so does each provider SDK
example. The Helm chart in `deploy/helm/platform` runs the API, the interpreter's workers
(`platform.roles`), the web app and one deployment per provider worker against an external
Temporal and PostgreSQL. It references secrets by the names of existing Secrets or through Vault
agent annotations and never holds a value; `values.yaml` describes every setting and
`ci/full-values.yaml` shows them all in use.

Evidence, notifications and change records have profiles of their own: `worm` (write-once
evidence files under `platform.audit.worm.dir`), `worm-s3` (evidence in S3 under Object Lock in
COMPLIANCE mode), `siem` (every evidence entry also to Splunk HEC and syslog), `slack`
(Slack-compatible incoming webhooks), `mail` (email) and `servicenow` (change records over the
ServiceNow Table API; `live` turns it on too). Their tokens and passwords are `vault:` references;
the S3 store takes its credentials from the AWS SDK's default chain. Tracing is always on: set
`MANAGEMENT_OTLP_TRACING_ENDPOINT` (for example `http://localhost:4318/v1/traces`) to ship the
spans to an OTLP collector, and scrape `/actuator/prometheus` for the per-workflow job metrics.
The API keeps idempotency keys and the per-team job quotas in memory, per instance: run one API
replica, or route each caller to the same replica, until a shared store backs them.

## Checks

```bash
cd backend && mvn -B -q test        # backend unit, handler and adapter tests (JDBC adapters on H2)
cd backend && PLATFORM_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/platform?user=platform' mvn -B -q test
                                    # the same, with the JDBC adapters also on PostgreSQL (CI does this)
npm run lint && npm run typecheck && npm test   # ESLint, then the kit, checks package and frontend
npm run check:workflows             # the config-as-code checks over the sample content (CI runs this too)
npm run build
(cd sdk/java && mvn -B -q test); (cd sdk/go && go test ./...); (cd sdk/python && pytest)   # the provider SDKs
python3 scripts/api-flows/flows1.py   # ... flows4.py: the API end to end on a running demo stack (scripts/api-flows/README.md)
```
