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
workflow), `opa` (the policy engine), `vault` (KV v2
credentials), `devin` (the natural-language builder), `oidc` (JWT identities), `live` (real HTTP
provider calls, and Ansible, Terraform, script and http steps run for real, see
[How automation steps run](docs/config-as-code.md#how-automation-steps-run)), `postgres` (the
stores in PostgreSQL, migrated by Flyway on start; the connection is `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`), `s3` (the artifact store on S3:
playbook uploads, saved plans and each ended job's log lines and plan, see
[Data](docs/architecture.md#data)), `github`
(change requests as pull requests on the content repo). The Git workflow store (JGit over the
content repo, a branch per change request) is not a profile but the property
`platform.content.git: true` (`PLATFORM_CONTENT_GIT=true`); it commits to its own clone only, so
it runs on one backend instance (see
[The Git workflow store](docs/architecture.md#the-git-workflow-store)).
`docker-compose.yml` starts a Temporal dev server, PostgreSQL and the stack on them; adding
`-f docker-compose.temporal-postgres.yml` runs Temporal self-hosted on its own PostgreSQL
(`temporalio/auto-setup`) instead of the dev server's SQLite file.

## Identity and OIDC

Locally every request runs as the person the `X-User` header names, or as
`platform.identity.default-user` (`gonzalo`) without one; service accounts call with
`Authorization: Bearer iaf_<token>` in every mode. Under the `oidc` profile, with
`platform.identity.mode: oidc`, a person calls with the identity provider's JWT, a request
without one is a 401 and the dev header is ignored. The properties (under `platform.identity.`,
or as environment variables such as `PLATFORM_IDENTITY_OIDC_AUDIENCE`):

| Property | Default | What it is |
|----------|---------|------------|
| `oidc-issuer` | required | the `iss` the tokens carry |
| `oidc-audience` | required | the `aud` the tokens carry for the platform; a token for another audience is refused |
| `oidc-jwks-uri` | required | the issuer's JWKS endpoint (https; plain http only for a local provider) |
| `oidc-teams-claim` | `groups` | the claim that lists the caller's teams |
| `oidc-roles-claim` | `roles` | the claim that lists the caller's roles |
| `oidc-admin-role` | `platform-admin` | the role in that claim that makes the caller a platform admin |

The three required properties are checked at startup: a blank one stops the API with a message
that names it (`platform.identity.oidc-audience is required under the oidc profile: ...`). A token
must be signed by a key of the JWKS with an asymmetric algorithm (RS256/384/512, PS256/384/512,
ES256/384/512; never an HMAC), carry `sub` and `exp`, and match the issuer and the audience; the
teams and roles claims may be lists or space- or comma-separated strings, and `name` falls back to
`sub`. The keys are cached for ten minutes, refreshed a minute before they expire, fetched at most
twice in 30 seconds when tokens name a key the cache lacks, and kept for an hour while the endpoint
is down; tokens are verified on a worker thread, never on the request's event loop.

## Natural-language builder on Devin

The builder panel and the "Ask Devin" step of the Add provider wizard run on a deterministic demo
agent until the `devin` profile is on; then every message becomes a Devin session in your
organization, run by the playbooks in `devin/` against their structured-output schemas, and the
platform still checks every draft itself ([the assistant endpoints](docs/api.md#assistant-natural-language-builder)).
The properties, under `platform.assistant.` (or environment variables such as
`PLATFORM_ASSISTANT_DEVINORG`):

| Property | Default | What it is |
|----------|---------|------------|
| `mode` | `mock` | descriptive only: the `devin` profile is what switches the adapter |
| `devin-url` | `https://api.devin.ai/v3` | the Devin API; every call goes to `<devin-url>/organizations/<devin-org>` |
| `devin-org` | required | the organization id (`org-...`) |
| `devin-api-key` | required | the service user's API key, as a `vault:` reference such as `vault:devin/api-key`, resolved through the credentials store at each call (the key itself still works, with a warning at startup) |
| `devin-playbook-id` | blank | the `!workflow-draft` playbook (`devin/nl-builder-playbook.md`); blank uses the id the last sync found or created |
| `devin-provider-playbook-id` | blank | the `!provider-draft` playbook (`devin/provider-draft-playbook.md`), the same way |
| `platform-token-secret-id` | blank | the id of the Devin organization secret that holds `PLATFORM_TOKEN`, attached to every session |
| `devin-repos` | `[]` | repositories attached to every session as context, as Devin names them (for example `acme/platform-content`) |
| `devin-max-acu-limit` | `10` | the ACU ceiling of each session; `0` sends none |
| `devin-poll-every` | `PT15S` | how often a session and its new messages are read, kept within 10 to 30 seconds |
| `devin-timeout` | `PT30M` | a session with no draft by then is ended and archived, and the stream fails |
| `devin-dir` | blank | the playbooks and schemas; blank means `<platform.content.dir>/devin` |
| `sync-playbooks-on-startup` | `false` | create or update the playbooks from `devin/*.md` when the platform starts |
| `platform-api-url` | blank | the platform API the agent calls back (the `PLATFORM_API` of the prompt); blank means `http://localhost:<server.port>` |
| `max-rounds` | `3` | times the platform sends its own check results back to the agent before it settles a draft |
| `session-retention` | `P30D` | builder sessions not updated for this long are deleted (checked every hour) |

Setting it up:

1. Create a Devin service user for the platform and an API key for it. Running sessions needs
   `UseDevinSessions`; store the key in the credentials store (`POST /api/v1/credentials`, or
   Vault under the `vault` profile) and set `devin-api-key` to its reference.
2. Create a platform service account with the `assistant` preset (`POST /api/v1/service-accounts`
   with `preset: assistant`: `registry:read`, `workflows:validate`, `workflows:dry-run`, nothing
   that changes anything). Store its token in Devin as an organization secret named
   `PLATFORM_TOKEN` and put that secret's id in `platform-token-secret-id`; the playbooks call the
   platform with it at `platform-api-url`, which Devin's machines must reach.
3. Create the playbooks. `POST /api/v1/assistant/playbooks:sync` (admins) creates the ones Devin
   lacks and updates the changed ones, but managing playbooks needs a key allowed to do so, which
   the service user's should not be: run the sync once with a broader admin key, or create the
   two playbooks by hand from `devin/*.md` (the title and macro of the front matter, the markdown
   after it as the body; every session sends the schema itself) and put their ids in
   `devin-playbook-id` and `devin-provider-playbook-id`.
4. List the repositories the agent may read in `devin-repos`. They are attached as context; they
   stay read-only because the playbooks forbid branches, commits and pull requests, not through
   an API flag, so give the service user no write access to them.

A session the builder stops waiting for (the panel closed, or `devin-timeout` passed) is ended
and archived so it stops spending ACUs, and the next message starts a new one with the current
draft.

## Deployment

`backend/Dockerfile` and `frontend/Dockerfile` (nginx serving the build and proxying `/api` and
`/ws` to the backend) build images that run as a non-root user, and so does each provider SDK
example. The Helm chart in `deploy/helm/platform` runs the API, the interpreter's workers
(`platform.roles`), the web app and one deployment per provider worker against an external
Temporal and PostgreSQL. It references secrets by the names of existing Secrets or through Vault
agent annotations and never holds a value; `values.yaml` describes every setting and
`ci/full-values.yaml` shows them all in use. `content.repository` is cloned into the backend
pods, and with it the workflows live in the repository (the Git workflow store,
`content.gitStore`): the chart then runs one backend pod whose clone a volume keeps
(`content.gitStore.existingClaim`, `ci/git-store-values.yaml`) and refuses more;
`content.gitStore.enabled: false` keeps the workflows in PostgreSQL, seeded from the repository, for
any number of replicas. `audit.signingKeySecret` names the Secret holding
the key that signs evidence seals (without it every pod signs with a random key per run, and a
seal cannot be verified after a restart), `audit.siem` turns on the SIEM export and
`audit.worm.mode` write-once evidence (`files` on an existing volume claim, `s3` with Object
Lock); each adds its profile. For Temporal Cloud or a server that requires mTLS,
`temporal.tls.secretName` mounts the CA, certificate and key (`platform.temporal.tls.ca`, `.cert`,
`.key`), `temporal.apiKeySecret` sets `platform.temporal.api-key`, and `temporal.payloadKeySecret`
the key that encrypts what the interpreter writes into Temporal's histories
(`platform.temporal.payload-key`). The provider workers get the same certificate files and API
key as the SDKs' `IAF_TEMPORAL_TLS_CA`, `IAF_TEMPORAL_TLS_CERT`, `IAF_TEMPORAL_TLS_KEY` and
`IAF_TEMPORAL_API_KEY`.

Evidence, notifications and change records have profiles of their own: `worm` (write-once
evidence files under `platform.audit.worm.dir`), `worm-s3` (evidence in S3 under Object Lock in
COMPLIANCE mode), `siem` (every evidence entry also to Splunk HEC and syslog), `slack`
(Slack-compatible incoming webhooks), `mail` (email) and `servicenow` (change records over the
ServiceNow Table API; `live` turns it on too). Their tokens and passwords are `vault:` references,
and so is `platform.audit.signing-key`, the HMAC key that signs seals and evidence packs (blank: a
random key per run, so seals cannot be verified after a restart; a literal key still works, with a
warning at startup); the S3 store takes its credentials from the AWS SDK's default chain. Tracing is always on: set
`MANAGEMENT_OTLP_TRACING_ENDPOINT` (for example `http://localhost:4318/v1/traces`) to ship the
spans to an OTLP collector, and scrape `/actuator/prometheus` for the per-workflow job metrics;
`deploy/grafana/platform-dashboard.json` is a Grafana dashboard over them (starts, finishes by
status, duration percentiles, failed steps by workflow and error class, failed jobs by version and
channel, and the provider calls' outcome, success rate and p95), which
`metrics.grafanaDashboard.enabled` ships as a ConfigMap with the `grafana_dashboard` label for
Grafana's sidecar.
With the `postgres` profile the idempotency keys, the per-team job quotas and the in-app inbox
live in the database too (`idempotency_keys`, `quota_buckets`, `inbox_*`), so every API replica
sees the same keys and draws from the same buckets; without it they are in memory, per instance,
so run one API replica or route each caller to the same replica.

Signing in with SSO goes through an auth proxy: the web app acquires no token itself. Put a proxy
such as oauth2-proxy in front of the web app and the API, which signs people in at the identity
provider and adds `Authorization: Bearer <ID token>` to every request it lets through
(oauth2-proxy's `--set-authorization-header=true`, passed on by ingress-nginx's `auth-url`,
`auth-signin` and `auth-response-headers` annotations; `values.yaml` shows them under `ingress`).
The API verifies the token under the `oidc` profile (`platform.identity.oidc-issuer`,
`oidc-audience` and `oidc-jwks-uri`, all required): its audience is the client id the proxy signs
in with, and the token must carry the teams claim. Service accounts
and MCP clients call with their own `iaf_` tokens and no browser session, so route them to the API
on a host the proxy does not guard; the API checks those tokens itself.

## Checks

```bash
cd backend && mvn -B -q test        # backend unit, handler and adapter tests (JDBC adapters on H2)
cd backend && PLATFORM_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/platform?user=platform' mvn -B -q test
                                    # the same, with the JDBC adapters also on PostgreSQL (CI does this)
npm run lint && npm run typecheck && npm test   # ESLint, then the kit, checks package and frontend
npm run check:workflows             # the config-as-code checks over the sample content (CI runs this too)
java -cp backend/target/classes scripts/scan-playbooks/ScanPlaybooks.java
                                    # after a backend compile: the upload scan rule over the repo's playbooks (CI)
npm run build
(cd sdk/java && mvn -B -q test); (cd sdk/go && go test ./...); (cd sdk/python && pytest)   # the provider SDKs
python3 scripts/api-flows/flows1.py   # ... flows4.py: the API end to end on a running demo stack (scripts/api-flows/README.md)
```
