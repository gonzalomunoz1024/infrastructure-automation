# console-state-service

The customer console's record of every resource, named by wfrn, from the first request to the
tombstone: an event-sourced inventory that holds the desired state a customer asked for and the
observed state the platform and the providers report, and serves the console's lists, details
and histories.

This repository is one branch (`console-state-service`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`, and the shared code comes from `console-libs` as
`com.iaf.console:*`.

## What it does

- **Owns the customer's view of a resource** (hub decision 3). A resource exists here from the
  moment a customer asks for it, as `pending`, before the platform has a job for it; the platform's
  events and registry then move it on. The platform remains the system of record for what a
  provider actually built; this service is the system of record for what the customer meant.
- **Keeps every change as an event** in an append-only log keyed `<Type>:<wfrn>:<version>`, so a
  resource's history, any past version (`?asOf=`), and the activity trail are reads, not
  reconstructions. A projection (one document per resource) serves lists in one query.
- **Serves the BFF** with the API of `contracts/openapi/console-state-service.yaml`: cursor-paged
  lists with filters and sorts, the resource by wfrn with an `ETag`, its events, and the commands
  (`:request-update`, `:request-delete`, `:tag`, `:record-observation`, `:record-drift`,
  `:resolve-drift`, `:mint`, `:import`) with optimistic concurrency and idempotency keys.
- **Publishes the console's own events** on the Kafka topic `state.resources` through the service
  kit's outbox, in the platform's P-01 envelope (hub decision 17), for the search indexer, the
  drift service and the BFF's live updates.
- **Follows the platform** (Kafka in production, the HTTP feed otherwise), reconciles with the
  registry, rebuilds from the platform's snapshot when its checkpoint is empty, and imports an
  account's existing resources on request.
- **Settles what the feed missed**: a reaper settles pending and busy resources whose job never
  came, ended elsewhere, or registered while the feed was down.
- **Protects secrets**: fields a type marks sensitive (or whose name says so) are sealed with a
  per-account data key under a master key, masked everywhere by default, revealed only to a caller
  who asks, and gone for good when the account's key is destroyed at closure.

## Run it

Java 21 and nothing else: the Gradle wrapper fetches Gradle.

```sh
./gradlew build                      # compiles, runs every test, builds build/libs/console-state-service.jar
scripts/demo.sh                      # the demo: in-memory stores, the fake platform in the same JVM
scripts/demo.sh journey              # with the demo running: mint, read, tag, trail, with curl
```

The demo listens on `http://localhost:18082`, the embedded fake platform on `http://localhost:18090`,
seeded with the two console accounts `123456789012` (compute) and `210987654321` (data). Dev headers
stand in for the BFF's token: `X-Console-Account`, `X-Console-User`, or `X-Console-Service` for
another console service (and `X-Console-Region`, `X-Console-Groups`, `X-Console-Roles`). The journey test
(`ResourceJourneyTest`) drives a VM through its whole life against the same setup, including the
platform job the BFF would start with the wfrn in its context.

Until `console-libs` is published to GitHub Packages (hub decision 15), build it to your local
Maven repository first: `./gradlew publishToMavenLocal -x test` on the `console-libs` branch. CI
does the same (`.github/workflows/ci.yml`, `CONSOLE_LIBS_REF`).

### Profiles

| Profile | What it changes |
| --- | --- |
| (none) | In-memory log, projection and stores; the real platform at `console.platform.base-url` over its HTTP feed; JWTs from the BFF's JWKS |
| `demo` | The fake platform inside this JVM (`console.demo.*`), dev headers on, a short reaper |
| `mongo` | MongoDB for everything durable (`CONSOLE_MONGO_URI`, `CONSOLE_MONGO_DATABASE`); the master key is then required |
| `kafka` | Platform events from Kafka (`console.ingest.kafka.*`) instead of the HTTP feed; the outbox relays `state.resources` to Kafka |

Production runs `mongo,kafka`; the Helm chart under `deploy/helm/console-state-service` wires
exactly that, with the three secrets it needs (MongoDB URI, master key, platform token) and a
rendered `application.yaml` mounted in `/config/`, which overrides the jar's defaults whatever the
profiles.

### Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `console.region` | `global` | The region this instance serves (hub decision 4) |
| `console.wfrn.partition` | `wf` | The partition of every wfrn minted here |
| `console.state.tombstone-ttl` | `90d` | How long a deleted resource answers 410 and keeps its log |
| `console.state.idempotency-ttl` | `24h` | How long an `Idempotency-Key` replays its first answer |
| `console.state.types-ttl` | `60s` | How long the platform's resource types are cached (ETag revalidated) |
| `console.reaper.*` | on, `60s`, pending `5m`, job `2h` | The reaper's interval and when a pending or busy resource is overdue |
| `console.ingest.enabled` | `true` | Whether platform events are consumed at all |
| `console.ingest.rebuild-on-empty` | `true` | Rebuild from the platform's snapshot when there is no feed checkpoint |
| `console.ingest.kafka.*` | `localhost:9092`, group `console-state-service`, topics `platform.jobs`, `platform.resources`, `identity.changes` | The Kafka consumer (`kafka` profile) |
| `console.platform.base-url`, `token`, `timeout` | `http://localhost:18090`, `CONSOLE_PLATFORM_TOKEN`, `30s` | The platform's API and the service account this service calls it with |
| `console.auth.jwks-uri`, `issuer`, `audiences` | the BFF's `/.well-known/jwks.json`, `console-bff`, `[console-state-service, console]` | Which tokens are accepted |
| `console.auth.dev-headers` | `false` | Accept the dev headers instead of a token (demo only) |
| `console.crypto.master-key` | `CONSOLE_MASTER_KEY` | 32 bytes, base64; random per process when unset outside `mongo` |
| `console.outbox.relay.*` | on, `1s` | The service kit's outbox relay |
| `console.demo.platform-port`, `step-delay`, `seed` | `18090`, `250ms`, `20261023` | The embedded fake platform (`demo` profile) |

## The API

Every call carries the BFF's internal token (ES256, `iss console-bff`, `aud console-state-service`,
`acct` the account, `sub` the user) or, for another console service, a token without `acct`, which
may name the account with `?accountId=`. A resource of another account is a 404, never a 403.
Wfrns travel percent-encoded in paths (`/v1/wfrn/wfrn%3Awf%3Acompute%3Aglobal%3A123456789012%3Avm%2Fvm-…`).

| Call | Answer |
| --- | --- |
| `GET /v1/resources` | The account's live resources, newest first; filters `type`, `state`, `name` (prefix), `tag.<name>`, `wfrn` (pattern), `updatedAfter`, `drift`; `sort` (`-updatedAt`, `updatedAt`, `-createdAt`, `createdAt`, `name`, `-name`); `limit` 1 to 200 (50), `after` the cursor of the last page |
| `POST /v1/resources:mint` | 201, a `pending` resource with a fresh wfrn (`<type>-<ulid>`), before the platform knows of it |
| `POST /v1/resources:import` | 201, an `active` resource the platform already had (`source` `backfill` or `discover`) |
| `GET /v1/wfrn/{wfrn}` | The resource with `ETag: "<version>"` (304 on `If-None-Match`); `?asOf=<version>` an older version (404 for one it never had); `?reveal=sensitive` the sensitive fields in clear, for an internal principal (`svc` without `acct`) and a person whose `roles` claim holds `account-admin`, ignored for anyone else; 410 with a `Tombstone` body after deletion |
| `GET /v1/wfrn/{wfrn}/events` | Its log in order, each entry the `state.resources` envelope (`id`, `key`, `type`, `at`, `context`, `data` with `version` and `state`), the same event under the same `id` as on the topic; `after` is a version, `limit` a page, `reveal` as on the resource |
| `POST /v1/wfrn/{wfrn}:request-update` | `active` → `updating` with the requested `changes` |
| `POST /v1/wfrn/{wfrn}:request-delete` | `active` or `failed` → `deleting` |
| `POST /v1/wfrn/{wfrn}:tag` | `set` and `removed` tags; names `[a-z0-9-]{1,32}`, values up to 256, 50 tags |
| `POST /v1/wfrn/{wfrn}:record-observation` | What a provider showed (`found`, `not_found`, `error`); `not_found` on a type whose `observe.notFound` is `deleted` tombstones it |
| `POST /v1/wfrn/{wfrn}:record-drift`, `:resolve-drift` | One open finding at a time; `accepted` writes the observed values into the attributes |
| `POST /internal/import` | For services: `{accountId, region, owner}`; with an `owner` (the team whose resources get names) the platform's backfill mints the missing wfrns first (the console-internal service account holds `resources:backfill`; a token without it, or a call without an owner, imports what already carries a wfrn), then the account's resources are taken under management |
| `POST /internal/rebuild`, `/internal/reap`, `/internal/accounts/{id}:shred`, `GET /internal/feed` | Operations: rebuild from the snapshot, run the reaper now, destroy an account's data key, the feed's status |

Commands take `expectedVersion` (409 `version.mismatch` with the current version when it moved),
`actor` (the user by default) and the headers `Idempotency-Key` (the same key and body replay the
first answer with `Idempotent-Replay: true`; another body is a 409) and `X-Console-Request-Id`
(carried into every event's context). A command that changes nothing (the same tags again, an
observation equal to the last) answers the resource without writing an event.

## How a resource moves

```
                 mint                 job.started            registered as active
  (nothing) ───────────▶ pending ───────────────▶ pending ───────────────────────▶ active
                             │                                                       │ ▲
                             │ job failed / no job within 5 min                      │ │ update's job ends
                             ▼                                                       ▼ │ (succeeded: new attributes;
                          failed ──── request-delete ────▶ deleting ◀─── request-delete ──┘  failed: back to active)
                             │                               │                       │
                             │ registered (a retry)          │ retired / job ends     │ request-update
                             ▼                               ▼                       ▼
                          active                       deleted (410 for 90 days)   updating
  import ───▶ active                                   ▲
  observed not_found on a type whose notFound is deleted ─┘
```

| Event (`type`) | When | What it changes |
| --- | --- | --- |
| `ResourceRequested` | `:mint` | Opens the resource: name, workflow, requested input, `pending` |
| `Imported` | `:import`, a registration the console never asked for | Opens (or resurrects) it as `active` with the registry's attributes |
| `ProvisioningStarted` | the platform's `job.started` with this wfrn | Records the job on a pending, updating or deleting resource |
| `ProvisioningSucceeded` | the registry registered it as active, or a succeeded job | `active`, with the registry id, provider id and attributes |
| `ProvisioningFailed` | a failed, cancelled or rolled-back job, or no job at all | `pending` → `failed`; an update or delete goes back to `active` |
| `UpdateRequested`, `DeleteRequested` | the commands | `updating`, `deleting` |
| `Deleted` | the platform retired it, a delete job succeeded, or a `not_found` observation | `deleted`, with `deletedAt` and `tombstoneUntil` |
| `Tagged`, `Observed`, `DriftDetected`, `DriftResolved` | the commands | Tags, the observed snapshot, the open finding; the state stays |

Versions are the log's positions, one per event; the projection is replaced only by a higher
version, and a read folds any events newer than the projection before answering, so a crash
between the log and the projection heals on the next read.

## Ingestion: the feed says *that*, the registry says *what*

The platform's `resource.registered` carries no attributes (hub decision 17's target contract), so
every platform event is a cue to read: a registration of a wfrn this service knows and whose
lifecycle class is `active` becomes `ProvisioningSucceeded` with the registry record (nothing while
the resource is deleting: only its delete job's end can finish a delete, hub decision 20); one it
does not know becomes `Imported` (source `backfill`); `resource.retired` becomes `Deleted`;
`job.finished` is applied through the job's outcome (a succeeded delete tombstones, a succeeded
create without a registration fails as `console.noRegistration`, a failed job fails the request).
Lifecycle classes come from the type's `console.lifecycle` map (`building` → pending, `ready` →
active, `retired` → deleted) with sensible fallbacks for types without one.

Events are de-duplicated on the platform's event `key` (never its `id`) through the service kit's
idempotent consumer; the Kafka consumer acknowledges after the effect is written, and the HTTP
follower checkpoints `platform-feed` after each event, so a replay after a crash is a no-op. The
`identity.changes` topic is read too: an account `deleted` (or a snapshot showing it `closed`)
destroys the account's data key.

The reaper (`console.reaper.*`) runs every minute over resources `pending` for more than five
minutes or `updating`/`deleting` for more than two hours: the registry first (active → succeeded,
retired → deleted), then the account's jobs for the wfrn (none → `console.noJob`, a terminal job →
its outcome, a running job → wait and warn past the job timeout).

The collision guard refuses an import or a registration that binds a registry id already bound
to another wfrn (409 `registry.collision`, counter `console_state_collisions_total`).

## Sensitive data

A field is sensitive when the type's schema marks it `x-console: {sensitive: true}` or its name
matches `password|passwd|secret|token|credential|private-key|api-key`. Such values in `input`,
`attributes`, `changes`, `observed` and drift fields are sealed before anything is stored:
AES-256-GCM under a per-account data key (`account_keys`, wrapped by the master key with the account
as associated data), serialized as `{"$enc": "<nonce+ciphertext>", "k": 1}`. The log, the
projection and the idempotency store only ever hold sealed values; Kafka, the trail and every
answer show `***`; `?reveal=sensitive` on the resource or its log decrypts for an internal principal
and for a person whose `roles` claim (the managed roles the BFF signs into its token, hub decision 21)
holds `account-admin`; a group name never decides, anyone else is answered masked, and every reveal is
logged. Destroying the
account's data key (`identity.changes` or `POST /internal/accounts/{id}:shred`) is the crypto-shred:
every copy, in every store and backup, is unreadable from then on, and a later seal for the same
account gets a new key.

## Storage

MongoDB, one database (`console_state`) per deployment; indexes are created at start by each adapter.

| Collection | Document | Indexes | Sharding (when needed) |
| --- | --- | --- | --- |
| `resource_events` | `_id` `<wfrn>#<version>`, `wfrn`, `version`, `type`, `accountId`, `region`, `atNs`, `expiresAt`, `doc` (the event) | unique `{wfrn, version}` (the optimistic append), `{accountId, region, atNs}`, TTL `expiresAt` (set on the tombstone) | hashed `wfrn`: one resource's log on one shard, writes spread |
| `resources` | `_id` wfrn, `accountId`, `region`, `type`, `name`, `state`, `live`, `version`, `registryId`, `tagsFlat` (`k=v`), `drifted`, `updatedAtNs`, `createdAtNs`, `expiresAt`, `doc` | `{accountId, region, live, -updatedAtNs, _id}`, the same with `type`, `{…, name, _id}`, `{…, -createdAtNs, _id}`, `{accountId, region, tagsFlat}`, `registryId` (live only), `{state, updatedAtNs}` (the reaper), TTL `expiresAt` | `{accountId, _id}`: an account's lists stay on one shard (1 million resources per account fits), lookups by wfrn carry the account |
| `idempotency_keys` | `_id` `<accountId>:<key>`, `bodyHash`, `status`, `body`, `expiresAt` | TTL `expiresAt` | hashed `_id` |
| `account_keys` | `_id` accountId, `version`, `wrapped`, `nonce`, `createdAt` | `_id` | not needed |
| `feed_checkpoints` | `_id` feed, `seq` (`$max` only) | `_id` | not needed |
| `outbox`, `processed_events` | the service kit's | the service kit's | hashed |

Lists are one indexed query with a `(sortValue, wfrn)` cursor; the kit's cursors are opaque and
expire with nothing. Cursor pages scan `limit + 1` documents; the page's filter work for `wfrn`
patterns and tags is done in the query (`tagsFlat`, an anchored regex on `_id`).

## Layout

```
com.iaf.console.state
├── inventory                     the one slice: resources by wfrn
│   ├── domain                    Resource, ResourceEvent, Transitions, ResourceFold, ResourceDecider,
│   │                             ResourceQuery/ResourceMatching, TagRules, Commands; no Spring, no I/O
│   ├── ports.outbound            ResourceEventStorePort, ResourceProjectionPort, IdempotencyKeyPort,
│   │                             ResourceTypesPort, AccountKeysPort, FeedCheckpointPort
│   ├── application               InventoryService (commands, platform facts), InventoryQueries,
│   │                             IdempotentCommands, SensitiveData, ResourceEventPublisher,
│   │                             PlatformEventHandler, JobOutcomes, RegistryImporter, PendingReaper
│   └── adapters.{web,mongo,memory,platform,kafka}
├── auth                          ConsolePrincipal, JwtVerifier (the BFF's JWKS), ConsoleAuthWebFilter
└── boot                          StateProperties and the profile wiring
```

ArchUnit (the kit's `ConsoleArchitectureRules`) keeps the domain free of Spring and adapters, the
application free of adapters, and every scoped read behind an `AccountScope`.

## Decisions

| # | Decision | Why |
| --- | --- | --- |
| 1 | Event-sourced log plus a projection, not a mutable row with an audit table | The trail, `asOf` reads and replays are reads; the projection is a cache that heals itself from the log |
| 2 | Pending exists before the platform's job | Hub decision 3: the customer's request is a fact the instant it is made; the wfrn is minted here and handed to the platform in the job's context |
| 3 | The feed is a cue, the registry the truth | `resource.registered` carries no attributes; reading the record on every registration keeps the two in step and costs one read per change, not per resource |
| 4 | No-op commands write no event | Hourly observations of 10 million resources would otherwise be 10 million events an hour; the log stays proportional to change |
| 5 | Sealing at the command boundary, masking by default | Secrets never reach the log or Kafka in clear; one `reveal` path to audit |
| 6 | Tombstones for 90 days, configurable | The plan's and the contract's number (hub decision 20); `console.state.tombstone-ttl` decides |
| 7 | Page limit 200, the kit's `CursorPaging.MAX_PAGE_SIZE` | Every console API pages 1 to 200 (default 50); `ResourceQuery.limitOf` follows the kit |
| 8 | The log's entries are the `state.resources` envelopes | The contract's `ResourceEvent` is the P-01 envelope since hub `025e8c7`, as are `sort`, `asOf`, `reveal`, the `Idempotent-Replay` header and the `Resource` fields `changes`, `observedStatus`, `lastError`, `source`, `deletedAt`, `tombstoneUntil`; an event gets its `id` when it is decided, so the topic and the log name it the same |
| 9 | One set of job events whatever the job (hub decision 20) | `ProvisioningStarted` keeps the state it finds; `ProvisioningSucceeded` ends a create or update in `active`; a delete job's success is recorded as `Deleted` alone, the tombstone, never as a `ProvisioningSucceeded` first; `ProvisioningFailed` leaves a create `failed` and returns an update or delete to `active`; a registration during a delete is no event |
| 10 | A forbidden backfill does not fail an import | The console-internal service account holds `resources:backfill` (console-libs `f44213a`); a deployment whose token lacks it still takes what already carries a wfrn under management |
| 11 | `reveal=sensitive` is honored for internal principals and account admins only | The token's `roles` claim decides (hub decision 21), never a group name; a token without roles has none; anyone else is answered masked, not refused |

## CI and release

`.github/workflows/ci.yml` builds and tests against MongoDB 7 (the Mongo adapter tests skip
without `CONSOLE_TEST_MONGO_URI`), scans files and new commits for secrets with gitleaks, and lints
and renders the Helm chart. The image (`Dockerfile`) is the boot jar on Temurin 21 as a non-root
user; publishing it and the chart waits for the registry decision on the hub.
