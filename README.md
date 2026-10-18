# Infrastructure Automation

A platform where a team designs an infrastructure workflow on an n8n-style canvas, stores it as
config as code in Git, and runs it on Temporal. Publishing a workflow generates its REST
endpoint, its OpenAPI entry and its MCP tool. The systems a workflow talks to (a vCenter, an Ops
Manager, a backup service, an Ansible library, a Terraform module) are registered as *providers*
through a wizard, without code.

Everything a team builds (VM restart, MongoDB upgrade, Delete VM, Patch a CVE) is sample content
under `workflows/`. Nothing in `backend/` or `frontend/` knows what a VM is.

## Quick start

```bash
# Java 21, Maven 3.9, Node 22
scripts/dev.sh                 # backend on :8080 (demo profile), frontend on :3000
scripts/dev.sh --temporal      # the same, with the interpreter running on a local Temporal dev server
```

Open <http://localhost:3000>. The demo profile keeps everything in memory, answers provider calls
from the samples recorded in each provider file, and seeds two weeks of job history so that
provider health, canary releases and audit evidence have data. Switch users in Settings
(`gonzalo` is an admin and approver, `junior` can only run).

The generated surface:

| Surface | Where |
|---|---|
| Catalog of published workflows | `GET /api/v1/catalog` |
| A workflow's endpoint | its `api.path`, e.g. `POST /api/v1/vms/{vmId}:restart` |
| Bulk runs | `POST <path>:batch` with `{ items, defaults, rollout }` |
| Blast-radius preview | `POST <path>:preview` |
| OpenAPI 3.1 | `GET /openapi.json` |
| MCP server (Streamable HTTP, JSON-RPC 2.0) | `POST /api/v1/mcp` |
| Job events | `GET /api/v1/jobs/{id}/events` (SSE), `ws://…/ws/jobs/{id}` |

## What is in the repo

```
backend/            Spring Boot 3.5 · Java 21 · WebFlux · CQRS · ports and adapters · Temporal SDK · JGit
frontend/           React 19 · Vite · TypeScript · Tailwind v4 · the n8n-style designer on @xyflow/react
packages/ui         the Atlas design system (tokens, kit, hooks) used by every page but the canvas
packages/workflow-checks   the editor's checks in TypeScript; the same rules run in Java and in CI
workflows/ providers/ resource-types/   sample content as config as code (kind: Workflow | Provider | ResourceType)
sdk/                provider SDKs (Java, Go, Python) for custom-code providers on a team's own worker
devin/              the natural-language builder playbooks and their structured-output schemas
docs/               architecture, config as code, API, design language, contributing
```

## Documentation

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
provider calls). `docker-compose.yml` starts a Temporal dev server and the stack on it.

## Checks

```bash
cd backend && mvn -B -q test        # backend unit, handler and adapter tests
npm run typecheck && npm test       # kit, checks package and frontend
npm run check:workflows             # the config-as-code checks over the sample content (CI runs this too)
npm run build
(cd sdk/java && mvn -B -q test); (cd sdk/go && go test ./...); (cd sdk/python && pytest)   # the provider SDKs
```
