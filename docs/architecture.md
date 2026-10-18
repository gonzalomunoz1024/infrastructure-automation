# Architecture

The platform lets a team design an infrastructure workflow on an n8n-style canvas, store it as
config as code in Git, and run it on Temporal. Publishing a workflow generates its REST endpoint,
its OpenAPI entry and its MCP tool. Providers (the systems a workflow talks to) are config too: a
provider file says where an API lives, how to sign in and which operations to offer. The platform
holds only the workflow, the provider registration and the run; the real APIs stay in their own
systems.

Everything a team builds (VM restart, MongoDB upgrade, Delete VM) is sample content under
`workflows/`. Nothing in `backend/` or `frontend/` knows what a VM is.

```
frontend/   React 19 · Vite · TypeScript · Tailwind v4 · @xyflow/react (canvas only)
packages/
  ui/              the Atlas design system (tokens, ui/ kit, hooks)      -> used by frontend
  workflow-checks/ the editor checks (also run in CI)                    -> used by frontend + CI
backend/    Spring Boot 3.5 · Java 21 · WebFlux · Maven · Lombok · Temporal Java SDK · JGit
workflows/  sample content: Workflow, Provider and ResourceType files (kind: ...)
devin/      the Devin playbooks (workflow draft, provider draft) and their structured-output schemas
scripts/    dev.sh (backend + frontend), check-workflows.mjs (CI checks over workflows/),
            scan-playbooks/ (CI: the upload scan rule over the playbooks the repo keeps)
```

## Backend: vertical-slice hexagonal with CQRS

Package root `com.iaf.platform`. One package per slice, each with the same four folders:

```
<slice>/
  adapters/inbound/    RestController<Slice>Adapter (implements the inbound ports), WebSocket handlers
  adapters/outbound/   one adapter per outbound port: InMemory*, Git*, Temporal*, Http*, Vault*, Opa*
  application/         <Slice>CommandHandler, <Slice>QueryHandler (@Service, @RequiredArgsConstructor)
  domain/              records, enums, pure rule classes (static, no Spring)
  ports/inbound/       <Slice>Commands, <Slice>Queries (interfaces returning Mono/Flux)
  ports/outbound/      one interface per external system or store
```

| Slice       | Owns                                                                  |
|-------------|-----------------------------------------------------------------------|
| `workflows` | definitions, drafts, validation, versions, publish, change requests, registry, templates, import |
| `providers` | provider files, OpenAPI import, Ansible and Terraform imports, test connection, try action, review, overrides, checks, health, alerts |
| `runs`      | jobs, the interpreter, bulk runs, approvals, time travel, previews, job events |
| `releases`  | canary releases, gates, version routing                               |
| `audit`     | evidence records, hash chain, sealing and checkpoints, export, share links, the platform stream, SIEM export |
| `catalog`   | the generated surface: REST routes, `:batch`, `:preview`, OpenAPI, Swagger UI, MCP tools, resource read endpoints, version pins, deprecation headers |
| `gateway`   | guards on the requests that start jobs: idempotency keys, per-team quotas |
| `notifications` | the in-app inbox and the delivery adapters (chat webhooks, email) behind one port |
| `archive`   | ended jobs' log lines and previews in the artifact store (`jobs/<id>/logs.ndjson`, `jobs/<id>/plan.json`), archived when a job ends and at start for any missing; `GET /jobs/{id}/logs` and `/plan`; reads jobs through the runs slice's `RunsQueries` |
| `observability` | job and step spans, traced provider calls, per-workflow metrics          |
| `resources` | resource types, registry, lineage, retire                             |
| `assistant` | natural-language builder sessions (Devin), the platform's checks of its drafts, SSE stream, `assistant.*` events |
| `identity`  | who is calling: dev identity, OIDC, service accounts and their scopes (`ServiceScopeWebFilter`), RBAC checks (`AccessRules`: who may invoke, edit, approve, and use a credential: its owner team, anyone when it has no owner, admins always; who sees a provider: the same rule, for scoping the provider list) |
| `common`    | cross-slice domain: ids, expressions, JSON schema validation, events, clock, redaction, trace ids |
| `config`    | Clock, Jackson, CORS, WebSocket mapping, GlobalExceptionHandler, demo loader |

### CQRS rules

- `ports/inbound/<Slice>Commands` and `<Slice>Queries` are the inbound ports. The REST adapter
  implements both: `POST` maps to a command, `GET` to a query.
- Commands are records (`StartJob`, `PublishWorkflowVersion`, `ApproveStep`, `RegisterProvider`,
  `PromoteCanary`). A command handler validates, changes state through outbound ports, appends a
  `DomainEvent` to the `EventBus`, and returns `Mono<CommandAccepted>` (id + status). Nothing waits
  on a long operation in the request thread.
- Query handlers read projections only (`ProjectionStore` and the slice's own read models). A
  projection is updated by an event listener, never computed inside a command handler.
- Domain rules are pure static classes with plain JUnit tests: `ReleaseGates`,
  `ProviderHealthRule`, `WaveScheduler`, `DependencyRules`, `WorkflowChecks`, `HashChain`,
  `ExpressionEngine`, `WorkflowInterpreter`, `AccessRules`.

### Outbound ports

| Port                    | Production adapter                     | Default (demo) adapter          |
|-------------------------|----------------------------------------|---------------------------------|
| `WorkflowStorePort`     | `GitWorkflowStore` (JGit, branch per change request) | `InMemoryWorkflowStore` seeded from `workflows/` |
| `WorkflowExecutionPort` | `TemporalWorkflowExecution` (profile `temporal`) | `InMemoryWorkflowExecution` (same interpreter, in-process) |
| `ProviderCallPort`      | `HttpProviderCall` (WebClient)         | `RecordedProviderCall` (samples recorded in Try it) |
| `AutomationRunnerPort`  | `AnsibleRunnerAutomation`, `TerraformCliAutomation`, `ProcessScriptRunner`, `HttpStepRunner` (profile `live`, on the host or in docker/podman) | `DemoAutomationRunner` (recorded samples) |
| `ArtifactStorePort`     | `S3ArtifactStore` (profile `s3`, any S3-compatible store) | `FilesystemArtifactStore` (content-addressed by SHA-256); both also keep named objects, replaced whole when written again, for the job archives |
| `GitRemotePort`         | `JGitRemotes` (ls-remote, a mirror per repository, the files of each commit cached once) | same                            |
| `CredentialsPort`       | `VaultCredentials` (KV v2 over HTTP; a credential's type, author and owner team live in its custom metadata, written before the value) | `InMemoryCredentials` (seeded from the content's providers, without an owner) |
| `PolicyPort`            | `OpaPolicy` (data API over HTTP)       | `MockPolicy` (TODO: wire the firm's OPA engine; its resource-access rule: a `public` resource or one without an owner team is any team member's, a `team` resource its owner team's, an admin's always) |
| `TelemetryPort`         | `JdbcTelemetry` (profile `postgres`, the samples in `telemetry_samples`) | `InMemoryTelemetry` (the samples of the last 30 days, bounded); both also record the `provider.call` timer and the `provider.errors` counter |
| `ProjectionStore`       | `JdbcProjectionStore` (profile `postgres`) | `InMemoryProjectionStore`   |
| `EvidenceStore`         | `JdbcEvidenceStore` (profile `postgres` without a `worm` profile, append-only); `FileSystemEvidenceStore` (profile `worm`, read-only files with retention sidecars); `S3ObjectLockEvidenceStore` (profile `worm-s3`, Object Lock in COMPLIANCE mode) | `InMemoryEvidenceStore` |
| `AssistantPort`         | `DevinAssistant` (profile `devin`: the v3 organization API; sessions run the playbooks synced from `devin/` with a required structured-output schema, follow-ups go on the same session, which is polled) | `MockAssistant` (deterministic drafts) |
| `ChangeRequestPort`     | `GitHubChangeRequests` (profile `github`: pull requests through `GitHubPullRequests`, the GitHub REST API; wraps whichever store keeps the change requests, so it combines with a database profile; merges and closes on GitHub come back through `POST /change-requests:github-webhook`) | `InMemoryChangeRequests` (branch + checks, merged in the UI) |
| `PriceListPort`         | `ContentPriceLists` (every `kind: PriceList` file of the content repo) | same |
| `ReviewEvidencePort`    | `JdbcReviewEvidence` (profile `postgres`, the same bounds) | `InMemoryReviewEvidence` (Test connection and Try it results per provider, bounded, with a count of the older ones dropped) |
| `ProviderAlertStatePort` | `JdbcProviderAlertState` (profile `postgres`) | `InMemoryProviderAlertState` (when the alert evaluator last ran, so a restart only judges the calls that failed since) |
| `GitSourcePort`         | `JGitSource` (https fetch into a throw-away bare repository, files read from the pinned commit) | same |
| `ModuleRegistryPort`    | `HttpModuleRegistry` (the Terraform module registry protocol at `platform.providers.terraform-registry-url`: versions, download location, inputs) | same |

Every adapter is a `@Component`. A production adapter carries its profile and `@Primary`
(`@Profile("opa") @Primary` on `OpaPolicy`), so it wins over its demo twin, a plain component,
when the profile is on; a twin that must not exist beside it carries the opposite guard (the
in-memory stores are `@Profile("!postgres")`). The Git workflow store is switched by the property
`platform.content.git`, not by a profile. No adapter uses `@ConditionalOnMissingBean`.

A credential belongs to a team: `CredentialInfo.owner`, chosen when it is stored (one of the
caller's teams, any team for an admin) and kept by both adapters; one seeded from content has
none. The provider wizard and the imports send a `vault:` reference outward (to an endpoint, a
repository, the module registry) only after `AccessRules.requireUseCredential` has judged the
caller against the owner the store reports, through `CredentialUse` in the providers slice: admins
pass, so does anyone for a credential without an owner. The check runs before the secret is
resolved, and a name the store does not know is left to fail where it is resolved, so a refusal
never reveals a credential another team owns. The same rule, as `identity.application.CredentialAccess`,
guards the playbooks slice: `GET /playbooks:resolve` with a credential, and the pinning of git
playbooks at publish (`PlaybookPinning`), which judges the publishing identity and refuses the
publish with 403 rather than sending another team's credential to a repository.

The demo data follows one fixture, `common.demo.DemoReleases`: the release stories (versions,
stages, outcomes, who decided). `DemoWorkflowHistory` keeps the versions those releases replaced
and the change requests that published them, `DemoRunsSeeder` routes each seeded job to the
version a story says was live and adds the canary traffic, `DemoResources` derives the registry
from the resource types and workflows, and `DemoReleaseSeed` records the releases last and judges
their gates with `ReleaseGates` over the seeded jobs. So every page in demo mode agrees with the
others, the way the Atlas topology fixture does. The release stories are the only platform code
that names the sample content (`DemoReleases` tells the stories of mongodb-upgrade, create-vm and
vm-restart, `DemoRunsSeeder` puts its live states on mongodb-upgrade, vm-restart and patch-cve);
the other demo seeders read the sample content rather than naming it: `DemoTelemetrySeeder` gives
every provider the registry has two weeks of background calls, drawn from its declared SLO and a
deterministic hash of its name, and hands the failing hour, the slow p95 and the success rate under
the SLO to whichever providers can show them whatever the draw; every sample carries the provider's
version, so a provider release's gates have a baseline. A provider added to the content gets its
traffic without a code change. All of them are demo-only: each seeds only with
`platform.demo.seed-history`, which the backend turns on by default for the demo and the Helm chart
turns off (`demo.seedHistory`).

### Identity

`IdentityWebFilter` resolves the caller of every request before anything else runs and puts it
in the Reactor context (`CurrentIdentity`): a service account token (`iaf_…`, looked up by its
SHA-256), an OIDC JWT under the `oidc` profile, the dev `X-User` header, the default user. Under
`oidc`, `JwtTokenVerifier` checks the signature against the issuer's JWKS (asymmetric algorithms
only, the RSA, RSA-PSS and EC families, the key chosen by the token's algorithm and key id), the
issuer, the audience, `sub` and `exp`, and maps the claims: `name`, `email`, the teams claim
(`platform.identity.oidc-teams-claim`, default `groups`), the roles claim (`oidc-roles-claim`,
`roles`), and admin when the roles hold `oidc-admin-role` (`platform-admin`; no other role makes
an admin). The issuer, the audience and the JWKS URI are required: a blank one fails the start
with a message that names the property, so no token of the issuer for another audience ever
verifies. The JWKS is cached and refreshed ahead of its expiry on a schedule, rate limited and
outage tolerant, and a token is verified on `boundedElastic` (`TokenVerifier` answers a `Mono`),
since a key the cache lacks is fetched over HTTP. Two kinds of request come in without a
credential, each as an identity that holds nothing: a signed webhook (its controller checks the
signature) and the read of a shared evidence link, `GET /api/v1/audit/shared/{token}`, whose
token is the secret (the audit slice keeps only its SHA-256 and refuses an expired one).

### Data

PostgreSQL (the `postgres` profile, below) holds the registry (workflow versions while the Git
store is off, change requests, providers and their versions, releases, resource types and
resources), the audit evidence (unless the `worm` or `worm-s3` profile keeps it), the runs index
(jobs, approvals and their events), the gateway's quotas and idempotency keys, the inbox, and the
other stores the table below lists. Git is the source of truth for workflows and providers: every
start seeds them from the content repository in `platform.content.dir`, and with
`platform.content.git` on that repository is the workflow store itself (`GitWorkflowStore`, below,
which commits every draft and publish) and a provider saved through the platform is written back
to its file there, uncommitted. Object storage, through `ArtifactStorePort` (a bucket under the
`s3` profile, else files under `platform.artifacts.dir`), holds playbook uploads and Terraform's
saved plans by SHA-256, and each ended job's archive: its log lines (`jobs/<id>/logs.ndjson`) and
the plan it was started with (`jobs/<id>/plan.json`). WORM evidence goes to object storage under
its own profile, `worm-s3`: S3 Object Lock objects in `platform.audit.worm.s3.bucket` (or, under
`worm`, read-only files in `platform.audit.worm.dir`).

### Persistence

Without a profile every store is in memory and a restart starts over. The `postgres` profile
(`docker compose up`, `scripts/dev.sh --postgres`) keeps the platform's state in PostgreSQL 16.
`PersistenceConfig` builds a connection pool from `spring.datasource.*` (`SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; a password never goes in a file),
Flyway applies `db/migration/common` and the database's own folder (`db/migration/postgresql`)
on start, and a JDBC adapter (`@Profile("postgres") @Primary`, over the small `Jdbc` helper in
`common/adapters/jdbc`) replaces the in-memory adapter of each store:

| Port (slice)                     | JDBC adapter          | Tables |
|----------------------------------|-----------------------|--------|
| `WorkflowStorePort` (workflows)  | `JdbcWorkflowStore`   | `workflow_versions` |
| `ChangeRequestPort` (workflows)  | `JdbcChangeRequests`  | `change_requests` |
| `ProviderStorePort` (providers)  | `JdbcProviderStore`   | `providers`, `provider_versions` |
| `ReviewEvidencePort` (providers) | `JdbcReviewEvidence`  | `review_evidence` |
| `ProviderAlertStatePort` (providers) | `JdbcProviderAlertState` | `provider_alert_state` (one row: when the alert evaluator last ran) |
| `ReleaseStore` (releases)        | `JdbcReleaseStore`    | `releases` (workflow and provider releases) |
| `ResourceStorePort` (resources)  | `JdbcResourceStore`   | `resource_types`, `resources` |
| `EvidenceStore` (audit)          | `JdbcEvidenceStore`   | `evidence_records`, `evidence_entries`, `evidence_share_links` |
| `ServiceAccountStore` (identity) | `JdbcServiceAccounts` | `service_accounts` |
| `SessionStore` (assistant)       | `JdbcSessionStore`    | `assistant_sessions` (rows not updated within `platform.assistant.session-retention` are deleted every hour by `SessionRetention`) |
| `TelemetryPort` (common)         | `JdbcTelemetry`       | `telemetry_samples` (each call with the provider version that served it) |
| `ProjectionStore` (runs)         | `JdbcProjectionStore` | `runs_jobs`, `runs_approvals`, `runs_events`, `runs_definitions` |
| `IdempotencyStore` (gateway)     | `JdbcIdempotencyStore` | `idempotency_keys` (claim = insert or take over a stale row; replay from the stored status, headers and body; a release deletes an unanswered row; expired rows swept every ten minutes) |
| `QuotaStore` (gateway)           | `JdbcQuotaStore`      | `quota_buckets` (one row per team bucket, `SELECT ... FOR UPDATE` on every take, so every replica draws from the same bucket) |
| `InboxStore` (notifications)     | `JdbcInboxStore`      | `inbox_notifications`, `inbox_audience`, `inbox_reads` (the newest `platform.notifications.keep`, with who read each one) |

Under that profile the in-memory twins of these stores are not created at all
(`@Profile("!postgres")`), so nothing seeds the content twice and the JDBC adapters are the only
implementations in the context (the evidence store follows its own rule: `worm` or `worm-s3`
wins when active, else `postgres` keeps it in the database, else it stays in memory). A row keeps the record as JSON text in a `doc` column, next to real columns for what is queried
(workflow, status, requester, times). Times are also kept as epoch nanoseconds, so the order is
exact. The SQL is portable between PostgreSQL 16 and H2 in PostgreSQL mode, which the tests use.

- Workflow versions, providers and resource types come from the content dir on start. A missing
  workflow version or provider is added, and one still exactly as seeded takes its file's new
  content; one changed through the platform is never reset. Resource types are rewritten from
  their files at every start. Reads come from a copy of the table that is reloaded when
  `store_revisions` shows a write from any instance.
- A provider's current version is its `providers` row; every version saved since the database
  was made, the current one among them, is also a `provider_versions` row, so a canary candidate
  (`ProviderStorePort.saveVersion`) is kept beside the current version, routed to by its release,
  and survives a restart until it is promoted and saved as current. The versions read newest
  first, as in memory, and a kept version is never written back to the content repo until it is current.
- Review evidence (what the wizard's Test connection and Try it calls proved, per provider name)
  is one `review_evidence` row per result, with the in-memory bounds: the last 50 connection
  tests and the last 500 tries of a provider, a response over 256 KiB kept without its body, and
  one count row per provider and kind (`dropped-connection`, `dropped-try`) for what the bounds
  deleted. A row holds endpoints, credential references and responses, never a credential value.
- The provider alert evaluator keeps when it last ran in `provider_alert_state`, so the first
  evaluation after a restart raises alerts only for the calls that failed since, not for every
  failed call of the last 24 hours again.
- Audit evidence is append-only twice over. `JdbcEvidenceStore` has no path that updates or
  deletes an entry, and it refuses entries for a sealed record. The PostgreSQL migration adds
  triggers that refuse `UPDATE`, `DELETE` and `TRUNCATE` of entries, entries for a sealed record,
  deleting a header, shrinking one and any change to a sealed one. Appends to a record take a lock
  on its header row, so concurrent writers cannot fork the hash chain. A share link keeps only
  the SHA-256 of its token, and a service account only the hash of its token.
- The runs index: `JobProjection` still folds job events in process, but it keeps the jobs (with
  their steps), approvals and event history in `ProjectionStore`. The definition a job ran is
  stored once per distinct definition. Event numbering carries on after a restart, so
  `/api/v1/jobs/{id}/events` replays the stored history and then follows the live tail. The live
  fan-out (SSE, WebSocket) stays in memory.
- Provider-call samples are queued, and a background thread writes them in batches, so recording
  never waits for the database (a read writes the queue first). Each row keeps the provider
  version that served the call, so a provider canary's gates judge the candidate's calls against
  the current version's after a restart too. Samples older than
  `platform.persistence.telemetry-retention` (30 days) are deleted every hour and never served.
- The demo history (`platform.demo.seed-history`) is written once per database (`store_seeds`).
  A later start finds it claimed and turns the switch off before any seeder runs.

Run one backend per database: the runs index is folded from the events this instance sees. Jobs
the in-memory engine was running when the backend stopped keep the status they had.

### The Git workflow store

With `platform.content.git` on, `GitWorkflowStore` is the workflow store under every profile (it is
`@Primary`, and neither `JdbcWorkflowStore` nor `DemoWorkflowHistory` is created): the content repo
in `platform.content.dir`, through JGit. A draft or a candidate is its file on the branch
`change/<name>-<version>`. Publishing commits the file on the checked-out branch (main), as a merge
of that branch when it exists, and deletes the branch; a retired version is read back from main's
history (the last 500 commits). A write is an in-core commit whose branch update fails if the
branch moved meanwhile. Reads never touch Git: they come from an index built from the branches
when the store starts and updated by each write it makes. The same switch has the provider stores
write a provider saved through the platform back to its file in that working tree
(`providers/<owner>/<name>.yaml`), uncommitted.

The store pushes nothing and fetches nothing. Its commits stay in that clone, and what reaches the
remote another way (a change request merged on GitHub under the `github` profile, a push) is read
only by a clone made after it. So it cannot be shared between replicas: each backend instance has
its own clone and index, a version published through one is unknown to the others (the other API
pods, and the workers, which resolve the workflow of every `run` step), and an instance whose clone
is not kept loses its commits when it is replaced. A deployment with the Git store needs a single
writer that is also the only reader: one backend instance whose clone outlives it. The Helm chart
switches the store on with a content repository (`content.repository`, with
`content.gitStore.enabled` true by default) and then runs exactly that, refusing other values: one
backend pod (`api.replicas: 1` and `worker.enabled: false`, so the API pod runs the interpreter's
worker too), its clone on a volume (`content.gitStore.existingClaim`; cloned once with its history,
then kept), replaced with `Recreate` so that two pods never hold it at once. More replicas need
`content.gitStore.enabled: false`: the workflows then live in the database (`workflow_versions`),
seeded from a fresh shallow clone at every start, and change requests can still be pull requests on
the repository (`github`). Sharing the Git store between replicas would take a push after each
commit, and a fetch and a reindex whenever the remote moves.

### Reactive boundaries

Handlers return `Mono`/`Flux`. Blocking clients (Temporal, JGit, process runners) are wrapped in
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` or `Mono.fromFuture` on async
stubs, so a Netty thread never blocks. Live job progress is a `Flux<JobEvent>` from the
`EventBus`, served over `/ws/jobs/{id}` (reactive WebSocket) and `/api/v1/jobs/{id}/events` (SSE).
The store ports are synchronous, and the JDBC stores of the `postgres` profile block. So under
that profile `BlockingStoresWebFilter` subscribes each request's whole chain on `boundedElastic`,
first in the filter chain (`RoleWebFilter` comes next, then the identity, scope, idempotency and
generated-route filters). The evidence stores of the `worm` and `worm-s3` profiles block on files
and the S3 client under any profile, so the audit handlers read and write them on `boundedElastic`
themselves.
The demo adapters that simulate latency (`RecordedProviderCall`, `DemoAutomationRunner`) also wait
there, so the job events that follow are stored from there. `Jdbc` counts any statement that still
runs on a non-blocking thread, and the wiring test expects none.

### Conventions

- `record` for every domain and DTO type. Enums serialise lowercase through `@JsonValue`.
- Jackson: `non_null`, ISO dates. One injectable `Clock`. Ids are strings (`job_…`, `rel_…`).
- `GlobalExceptionHandler`: `IllegalArgumentException` → 400, `NotFoundException` → 404,
  `ForbiddenException` → 403, `ConflictException` → 409, `TooManyRequestsException` → 429 with
  `Retry-After`, `ResponseStatusException` → its status, anything else → 500,
  body `{ "error": "…" }`.
- Tests: Mockito for ports + `StepVerifier` for handlers; plain JUnit for domain rules;
  `WebTestClient` for adapters; `TestWorkflowEnvironment` for the Temporal workflow. No Spring
  context unless the test is about wiring.
- Logging via Lombok `@Slf4j`. No field injection. No static mutable state outside fixtures.

### Evidence, notifications, guards and telemetry

| Port (slice)                       | Adapters |
|------------------------------------|----------|
| `EvidenceStore` (audit)            | `InMemoryEvidenceStore`; over `WormEvidenceStore` (one write-once object per entry, seal and checkpoint, created conditionally so two instances never take the same seq, the record rebuilt from its objects; a `sealed/<record>.json` index object per sealed record, so a sealing run never reads one; a share link is kept under the SHA-256 of its token, never the token; the cache holds the open records, the 256 most recently used sealed ones and 1024 share links, and drops a link once it expired): `FileSystemEvidenceStore` (profile `worm`, read-only files with retention sidecars) and `S3ObjectLockEvidenceStore` (profile `worm-s3`, Object Lock in COMPLIANCE mode) |
| `SiemPort` (audit)                 | `SplunkHecSiem` and `SyslogSiem` (profile `siem`, each on when its destination is set; neither is `@Primary`, every configured SIEM gets every batch) |
| `NotificationPort` (notifications) | `InAppNotifications` (the inbox, over `InboxStore`), `SlackWebhookNotifications` (profile `slack`), `EmailNotifications` (profile `mail`) |
| `ChangeTicketPort` (runs)          | `MockServiceNowChangeTickets`; `ServiceNowChangeTickets` (profiles `servicenow` and `live`, the Table API on `change_request`) |
| `IdempotencyStore`, `QuotaStore` (gateway) | `InMemoryIdempotencyStore` and `InMemoryQuotaStore` (per instance, so several API replicas need sticky routing); `JdbcIdempotencyStore` and `JdbcQuotaStore` under `postgres`, shared by every replica |

- `AuditListener` folds job events into one hash chain per job, release decisions into
  `release:<id>` and every other recorded command into the platform stream. Each entry is
  redacted (`common/redaction`: secret-looking fields and the values resolved from Vault during
  the run) before it is chained, then queued for the SIEM by `SiemExporter` (bounded, batched,
  retried with backoff). A store that fails an append is tried once more; a second failure loses
  that entry, counts it in `audit.append.failed` (tag `store`; `audit_append_failed_total` on
  `/actuator/prometheus`) and logs an error naming the record. The job goes on: evidence never
  stops a job, so alert on that counter. A sealed record refusing a late entry
  (`SealedRecordException`) is a refusal, logged as a warning and not counted. The scheduled seal
  seals closed records and checkpoints open ones; it reads only the records still taking
  signatures (`EvidenceStore.unsealed()`: their head, entry count, whether they are closed and
  their last checkpoint, never their entries), so a run costs the open records, not the history.
  The JDBC store answers it from the headers (`sealed_at IS NULL`), the WORM stores skip every
  record named in their `sealed/` index (one write-once object per sealed record, written with the
  seal; a record sealed before the index existed is indexed the first time a run finds it). On
  demand (`POST /audit:seal`) only an admin seals. A record is shared by the job's requester, a
  member of the teams it ran for (`RecordOwners`: the requester's teams as the runs index kept
  them, the workflow's owner team) or an admin, and each link is published as `audit.shared`
  (record, who, expiry; never the token). An evidence pack is written to a temporary file as it
  is built (each record serialised straight into the zip, its sha256 taken on the way), streamed in
  64 KiB chunks and deleted once sent, failed or abandoned; a pack whose client left before its
  body was sent is swept from `java.io.tmpdir/iaf-evidence-packs` after an hour. Seals, checkpoints
  and packs are signed by `AuditSigner` with `platform.audit.signing-key`: a `vault:` reference
  resolved through `CredentialsPort` when it signs (each sealing run, verification and pack takes
  the key once, so one never mixes two keys; a rotated key signs from the next run on, and seals
  signed before read as `unverifiable`), a literal key (accepted with a startup warning) or, blank,
  a random key per run.
- `IdempotencyWebFilter` (after the identity filter) and `JobQuotas` guard every request that
  starts jobs; MCP `tools/call` goes through the same `IdempotencyHandler` and quotas.
- `NotificationListener` turns notify steps, provider alerts, approval requests and escalations
  into notifications; `NotificationHandler` sends each to every `NotificationPort`, and a failing
  adapter never stops the others. `ProviderAlertTriggers` (runs) starts the alert triggers that
  match a provider alert.
- Tracing is Micrometer's Observation API on the OpenTelemetry bridge (`common/tracing`).
  `GeneratedRouteWebFilter` and `McpServer` open `iaf.gateway` and `iaf.mcp` spans under the
  request's (which continues an incoming `traceparent`); `JobTracing` (observability) turns job
  events into `iaf.job` and `iaf.step` spans on the trace the job's `traceId` names
  (`RequestedTraceIds` makes it the root of that trace when nothing started it from a span);
  `TracedProviderCalls` proxies every `ProviderCallPort` bean so each call is an
  `iaf.provider.call` span under its step. `JobMetrics` counts and times jobs per workflow. Step
  spans come from the in-memory engine's events.

### Policy engine contract

`PolicyPort` is asked at three points. The demo's `MockPolicy` answers in process; under the `opa`
profile `OpaPolicy` asks the firm's OPA engine through its data API, one document per phase:
`POST {platform.policy.opa-url}/<phase>` with the body `{ "input": ... }`. With the default URL,
`http://localhost:8181/v1/data/platform`, the documents are `data.platform.invoke`,
`data.platform.publish` and `data.platform["resource-access"]`.

```json
{
  "input": {
    "phase": "invoke",
    "who": { "id": "mateo", "name": "Mateo", "email": "mateo@example.com", "teams": ["team-compute"],
             "roles": ["ops"], "admin": false, "kind": "user" },
    "workflow": "vm-restart",
    "version": "1.2.0",
    "input": { "vmId": "vm-0042", "reason": "stuck after a kernel update", "environment": "prod" },
    "context": { "channel": "api", "environment": "prod" }
  }
}
```

`who.kind` is `user`, `service` (a service account) or `system`. Per phase:

| Phase | Asked | `workflow`, `version` | `input` | `context` | A denial |
|-------|-------|-----------------------|---------|-----------|----------|
| `invoke` | before a job starts, by the runs slice for every start (the UI, `POST /jobs`, triggers, child jobs, a re-run) and every preview, and before that by the catalog for a generated route or MCP tool | the workflow and the version that will run | the job's input with the schema's defaults; a batch: `{ defaults, items }` from the catalog, its first row from the runs slice | runs slice: `channel` (`preview` for a preview, `rest` for a re-run), `environment` (the input's), `parentId` (a child job), `changeRequired` and `approvalRequired` (the workflow's `policy` block); catalog: `channel`, `traceId`, `rows` (a batch), `pinnedVersion` (a run pinned to a version) | 403 with the rules' messages; the generated routes also answer `rules` and `engine`, MCP its policy-denied error |
| `publish` | when a change request is opened for a version | the workflow and the proposed version | the definition, as JSON | `strategy` (`canary` or `all`), `owner` (the owner team), `previous` (the version it replaces; null for a first) | the change request's `policy` check fails |
| `resource-access` | when a `use` step resolves a registry resource and checks access | null | the resource: `id`, `type`, `status`, `owner`, `visibility`, `attributes` | `{ "phase": "resource-access" }` | the step fails, naming the resource and the reasons |

One start can ask `invoke` more than once (the catalog, the preview, the start), so an answer
must depend on the input alone. `PolicyPort.Phase` also names `step`, which nothing asks today.

The answer is the data API's, the rules of the phase's document:

```json
{ "result": { "allow": true, "needsApproval": true,
              "rules": [ { "id": "prod.change-window", "result": "needs-approval", "message": "Production outside the change window" } ] } }
```

- `allow: true` allows; with `needsApproval` (or `needs_approval`) `true` the decision is
  `needs-approval`; `allow` false or missing denies.
- `rules[]` (`id`, `result`, `message`) are kept with the decision: `id` defaults to `rule`,
  `result` to `pass` when allowed and to `deny` otherwise.
- No `result` (no document for the phase) denies with the rule `opa.undefined`. An error, a
  non-2xx answer or no answer within 5 seconds denies with `opa.unreachable`: the platform fails
  closed.

`deny` refuses. `allow` and `needs-approval` let the request through, and the decision
(`decision`, `rules`, `engine`) is kept on the job and its preview as `policy`, and so in the
`job.started` evidence. A `needs-approval` answer adds no approval of its own: a job waits for the
approvals its workflow has (`approval` steps, `policy.approval`).

## The interpreter

`WorkflowInterpreter` (runs/domain) is a pure walker over a `WorkflowDefinition`. It takes a
`StepExecutor` (the only thing that touches the world) and a `JobContext` (input, step outputs,
identity). It resolves expressions, walks `do` in order, handles `switch`, `fork`, `try/catch`,
`for`, `wait`, `set`, `raise`, `run` (child workflow), `approval`, `use`/`register`, and
compensation on failure. Before the first step it builds what the input asked for: an
`orCreateWith` input holding `{ create }` runs its producer as a child job (step `<input>.create`)
and then reads as the id of the resource the child registered; every engine returns a child's
result through `ChildResults`, which carries those registrations. The same class runs:

- in process, inside `InMemoryWorkflowExecution` (the default), on a bounded scheduler;
- inside Temporal, where `InterpreterWorkflowImpl` implements `StepExecutor` with activities,
  child workflows, `Workflow.await` for approvals and pause-on-failure, and durable timers for
  waits. The definition snapshot travels in the workflow input.

A `use` step asks the policy engine whether the requester may use the resource (phase
`RESOURCE_ACCESS`, the resource's owner and visibility in the input) unless it says
`checkAccess: false`; the YAML parsers of both twins apply that default. One that waits for a
resource to be ready goes through `PortStepExecutor.Engine.awaitResource`:
in process the registry's `resource.*` events on the EventBus wake it (`ResourceQueries.awaitState`),
bounded by the step's timeout and ended by a cancel; on Temporal it maps to `Workflow.await` over a
signal a registry listener sends to the waiting workflow.

The registry judges its own writes (`resources.domain.ResourceRules`): a resource is its owner
team's, so only that team, the job that created it or an admin may update it through a `register`
step, and only the team or an admin may retire it. Retiring and lineage walk
`ResourceStorePort.builtFrom` (`ResourceGraph`: depth-first in both directions, each resource once,
at most 32 hops and 1000 resources) instead of reading the whole registry; the JDBC store finds a
resource's dependents with a LIKE on the document text and judges the candidates' `builtFrom`.

A preview (`Previews`, runs/application) runs the same interpreter as a dry run on the input and
the recorded samples, in process whatever the engine, and judges the static walk of `PreviewRules`
with it: the steps it does not take are skipped, provider `plan` actions are asked through
`ProviderCallPort`, the registry's lineage adds what a change touches, and `PriceListPort` prices
the create calls.

Expressions are n8n-style: `{{ $input.vmId }}`, `{{ $json.vm.id }}` (previous step's output),
`{{ $('Check VM exists').json.vm.name }}`, `{{ $job.id }}`, `{{ $env.name }}`. See
`docs/config-as-code.md`.

### Running on Temporal

The `temporal` profile (`docker compose up`, `scripts/dev.sh --temporal`) sets
`platform.execution.mode: temporal` and swaps the engine: `TemporalWorkflowExecution` starts each
job as an `InterpreterWorkflow` execution whose workflow id is the job id, on
`platform.temporal.task-queue` at `platform.temporal.target` (namespace
`platform.temporal.namespace`). The worker runs inside the backend process
(`InterpreterWorkerLifecycle` starts it once the application is ready, retrying in the background
while the server is down, and drains it on shutdown), because its activities are the slice's own
beans: a step's calls go through the same `PortStepExecutor` as in memory, once, with the step's
timeout (the interpreter does the retrying), and every event goes through `JobEvents`, so the
projection, the audit log and the streams cannot tell the engines apart. Approvals, attention
decisions and cancels are the signals `approve`, `attention` and `cancel`; `status` and `waitingOn`
are queries. Waits, timeouts, retry backoffs and `use … waitUntil: ready` polls are timers; `run`
steps and batch rows are child workflows whose ids are the child job ids. A cancel from the
Temporal UI or CLI ends the job as cancelled by `temporal`. Without the `postgres` profile the job
list is each instance's in-memory projection, so jobs started before a backend restart are not
listed again, although their executions carry on. With it, the jobs, their steps and their event
history are read back from PostgreSQL. With `platform.temporal.ui-url` set (the Temporal web UI, `:8233` in
`docker-compose.yml`), each job's view carries `links.temporal`, its execution there
(`<ui-url>/namespaces/<namespace>/workflows/<job id>`, the latest run), and the job page shows
"Open in Temporal".

The platform's own step calls heartbeat while they run: a provider call, an automation run or a
script heartbeats every `platform.temporal.heartbeat-interval` (15s) and fails as `worker-lost`
after `platform.temporal.heartbeat-timeout` (60s; `0` turns it off) without one, so a backend that
dies mid-call fails the step, which its retry policy can run again on another instance, instead of
waiting out the step's timeout.

`platform.roles` (`api, worker` by default) splits that work. An instance with the `api` role
serves the REST API, the streams, MCP and the generated routes, and starts, signals and queries
executions; one with the `worker` role runs the interpreter's worker. Without the `api` role an
instance answers nothing but the actuator (health, metrics), so its probes and scrapes keep
working. One role alone needs the `temporal` profile, and the context refuses to start without
it. Since the worker that runs a job's steps is the instance that records them, API instances see
that progress only through a shared job store (the Postgres adapters); with the in-memory
projection every instance keeps both roles.

### Changing the interpreter workflow

A worker replays an execution's history through the code it runs now whenever it picks the
execution up again (after a restart, an eviction from its cache, a signal), and the replay fails
as non-deterministic if that code issues other commands than the history recorded. The walker
(`WorkflowInterpreter`) runs inside `InterpreterWorkflowImpl`, so the rule covers both and what they
call: every change to the walker's command sequence (which activities, timers and child workflows
it starts, in which order, with which activity type or task queue) is gated with
`Workflow.getVersion` under a change id of its own, and lands with a history recorded before the
change and a test that replays it. A new execution takes the new branch; one recorded before the
change, whose history has no marker for that id, replays the old one. The change ids are the
constants on `InterpreterWorkflowImpl` (`team-workers` was the first). A change that leaves the
commands as they were (a log line, a query's answer) needs no gate, and a gate's old branch goes
only once no execution recorded before it can still be open (the id stays:
`Workflow.getVersion(id, 1, 1)`).

The history fixtures come from the test server. The test-only `HistoryRecorder`
(runs/adapters/outbound/temporal, next to the replay tests) runs one scenario per test with the
workflow code as it is and writes each execution's history as JSON into the absolute directory its
property names; without the property nothing runs, so the suite never rewrites a fixture:

```bash
cd backend && mvn -B -o test -Dtest=HistoryRecorder -Diaf.record.histories=/abs/dir -Dsurefire.failIfNoSpecifiedTests=false
```

Before the change, add a scenario that drives the code it alters if none does, record, and keep
the files under `backend/src/test/resources/temporal/before-<change id>/`. Then make the change
behind its gate and replay them in a test beside the change's own (`TemporalTestEngine.replay`, as
`TeamWorkerStepsTest` replays `team-steps-before-team-workers.json`): the old histories still
replay, and the new scenario takes the new branch.

### Custom-code providers

A provider of type `sdk` is code a team owns, served by the team's own worker on its own task
queue (`spec.worker.taskQueue`, default `provider-<name>`). The platform never runs that code:
under the `temporal` profile the interpreter schedules each call of an action as one activity on
that queue and the worker answers it. The provider SDKs (`sdk/java`, `sdk/go`, `sdk/python`)
implement the worker's side of the contract, derive the action schemas from the handlers' types
and write the provider file, so the code and the file cannot drift.

- The activity type is the action id (`address.reserve`); a `call: activity` step names its own.
- The one argument is a JSON object: `input` (the step's resolved input), `idempotencyKey`,
  `job { id, workflow, version, traceId }`, `step`, `environment` and `attempt` (from 1). The
  key is `<job id>:<step id>:<$job.seq of the step's first attempt>`: the same for every retry
  of the step and when a restarted worker picks the call up again, new for a new run of the step.
  The result is the action's output, a JSON object.
- The activity runs once. The step's `timeout` (10m by default) is both its schedule-to-close and
  its start-to-close timeout, so a call that no worker picks up fails the step instead of waiting
  in the queue for good; its heartbeat timeout is the provider's `worker.heartbeatTimeout` (none
  when absent), and Temporal does not retry it: the step's `retry` policy does, with the same key.
- A failure is an `ApplicationFailure` whose type is the error class (`spec.errors[].class`);
  non-retryable means no retry fixes it, and the step fails at once. Running out of the step's
  timeout fails the step as `timeout` (saying so when no worker on the queue picked the call up)
  and a heartbeat timeout as `worker-lost`, both retryable. The SDKs report
  a call that breaks the contract as `invalid_request`, `invalid_input`, `invalid_output` or
  `unknown_action` (never retried) and any other exception as the retryable class `error`.
- Cancelling the job cancels the activity; the worker learns of it through its heartbeats.

The in-memory demo answers an `sdk` action from its `sample`, as it does for every provider; the
`live` profile without Temporal fails it with a message saying it needs the `temporal` profile.
Each worker is a deployment of its own, which the team that owns it scales and releases (the
Helm chart's `providerWorkers`, the example workers in `docker-compose.yml`); a stopping worker
stops polling and lets the calls in flight finish (`WORKER_DRAIN_SECONDS`, 30 by default). A
worker reaches Temporal Cloud or a TLS frontend with `IAF_TEMPORAL_TLS_CA` (the frontend's CA),
`IAF_TEMPORAL_TLS_CERT` and `IAF_TEMPORAL_TLS_KEY` (its client certificate, for mutual TLS) and
`IAF_TEMPORAL_API_KEY` (a bearer token over TLS), the same in the three SDKs; the chart sets them
from `temporal.tls` and `temporal.apiKeySecret`. The SDKs hand the call's W3C `traceparent` (in
`job`, next to `traceId`, or at the top of the payload) to the handler and continue the platform's
trace from it: a span `run <provider>.<action>`, child of the step span, in Java and Python when
the OpenTelemetry API is on the worker's classpath (an optional dependency), in Go through the
`SpanStarter` the program passes to `WithTracing`.

### Verified against a real Temporal server

Besides the test server of the backend's suite, these ran against a Temporal dev server
(`temporal server start-dev`, Server 1.29.1, UI 2.42.1), with the backend jar on the `demo` and
`temporal` profiles and the example workers of the three SDKs on their own queues:

- The `vm-restart` sample through its generated route: two provider steps, the approval decided
  through `POST /api/v1/approvals/{id}:approve` (a signal), an automation step, a provider step
  and a notification, to `succeeded`; `links.temporal` opened the execution in the UI. A cancel
  through `POST /jobs/{id}:cancel` ended a job waiting for its approval, which expired.
- Restarts: a backend stopped while a team's worker ran a call, and started again only after the
  call had finished, replayed the history and finished the job by itself. A job waiting for an
  approval outlived a restart too; its approval went in as the `approve` signal through the
  Temporal CLI, since the new instance's in-memory projection does not know the job (the
  `postgres` profile fills that gap).
- `reserve-ip-address` through `POST /api/v1/addresses` on the Go (`ip-pool`) and Python
  (`dns-records`) example workers, alone and as a three-row batch whose rows ran as child
  workflows; a scratch workflow added the Java example (`cmdb`) as a third step.
- Failures: a worker killed mid-call failed its step as `worker-lost` after the heartbeat timeout,
  and the step's retry ran it again on a fresh worker with the same idempotency key; a call to a
  queue with no worker failed its step as `timeout` after the step's timeout, naming the queue.

## Frontend

See `docs/design-language.md`. The shell (every page but the canvas) is built from
`packages/ui`, the Atlas design system. The canvas (`frontend/src/designer`) replicates n8n's
editor on `@xyflow/react`. Routing is a small hash router in `frontend/src/app/router.ts`; no
router library. The API client is `frontend/src/lib/api.ts`, typed from `docs/api.md`.

The shell renders what the API judges and never decides for itself: the catalog and the run page
show an operation's deprecation (since, sunset, successor) as the catalog reports it; a run can be
pinned to an exact version (`X-Workflow-Version`, bypassing the canary split) and every
submission carries one `Idempotency-Key` per form, so a resend after a 429 (shown with the
backend's `Retry-After` wait) or a lost answer replays the same job; the header bell is the inbox
(`/notifications`); the audit page has a tab on the platform stream; the releases, release and
provider pages show provider releases judged over calls. Every provider edit (the SLO, "Use the
real response", a spec refresh, the wizard's publish) is a change request the page names and
links. The Add provider wizard reads a spec from a URL, a pasted text, a file or Git (pinned to
the commit read), imports an Ansible repository or a Terraform module through the backend's
drafts, and edits an existing provider at `/providers/<name>/edit` on three steps (Connect, Try
it, Review) that collect the review evidence the reviewer sees; the browser still parses no YAML,
so an edit keeps the file as it is and bumps its version by a line edit of the metadata block. The
assistant page shows the validation's problems as the designer's Issues list, the session's own
events from the platform stream, and submits a ready draft as a change request. The settings page
creates service accounts with the presets and the grouped scopes of `docs/api.md`.

## Where things are in Git

A workflow is `workflows/<team>/<name>.yaml`, a provider `providers/<team>/<name>.yaml`, a
resource type `resource-types/<name>.yaml`, all under the content repo (`workflows/` in this
monorepo for the samples). Publishing writes the file on a branch `change/<name>-<version>` and
opens a change request; merging it publishes the version and starts its canary. A provider
version rolls out the same way: the providers slice keeps the candidate beside the current version
(`ProviderStorePort.saveVersion`), the releases slice routes each job's calls (`ReleaseQueries.routeProvider`,
asked by `ProviderQueries.provider` when the lookup carries the job's `ProviderQueries.Use` in its
Reactor context) and judges the gates on the calls' telemetry, and promotion makes the candidate current.
