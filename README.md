# console-drift-service

The customer console's drift detection: it compares what a customer asked for (the desired state
the state service holds for every resource) with what the provider actually has (read through the
platform's observe operations), records each difference as a finding, and offers the three
decisions a person takes on it: **revert** in one click, **accept** the provider's values, or
**ignore** the fields. Nothing is ever put back on its own: a revert is always a person's call.

This repository is one branch (`console-drift-service`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`, and the shared code comes from `console-libs` as
`com.iaf.console:*`.

## What it does

- **Keeps a watch list** of every live resource the state service knows (the inventory sync, plus
  the `state.resources` topic under `kafka`): its desired attributes, where its truth is read, its
  tier and when it is next due.
- **Reads providers in pages, not one by one.** A shard loop (64 shards per region) takes the due
  resources of a shard and reads them per type, region and environment with the platform's
  account-filtered `:observe-list` pages (P-09), or with batch `resources:observe` calls when only
  a few are due; imported resources (`source: discover`) are matched in the type's `:discover`
  pages by provider id. A paced budget per provider, region and account keeps the service under
  the platform's observe rate instead of collecting 429s.
- **Tiers the work**: hot every 15 minutes (changed in the last 24 hours, in production, drifted in
  the last 7 days, or a finding open), warm on the type's `observe.interval` (hourly by default),
  cold daily (untouched for 30 days and never drifted). A provider webhook makes a resource hot
  and reads it at once.
- **Records drift** as one finding per resource at a time: opened, updated when the drifted fields
  change, closed as `vanished` when the provider is back in line or the resource is deleted. Each
  change goes to the state service (`:record-observation`, `:record-drift`, `:resolve-drift`) and
  onto the `drift.findings` topic in the platform's P-01 envelope.
- **Reverts in one click**: the type's update workflow runs through the account's platform service
  account, on behalf of the person who clicked (the BFF's `X-On-Behalf-Of`), with the desired values
  of the drifted fields; the finding closes as `reverted` when the job succeeds and the next read
  agrees; a failed job shows on the finding (`revertStatus: failed`), which stays open.
- **Accepts and ignores**: an accept makes the observed values the desired ones on the state service;
  an ignore silences the fields until the desired state changes, or until a date.
- **Finds what the console does not manage**: a discovery run lists each type's provider objects
  through the platform's `:discover` operation, keeps the ones no wfrn holds, and imports a chosen
  one into the state service.

## Run it

Java 21 and nothing else: the Gradle wrapper fetches Gradle.

```sh
./gradlew build                      # compiles, runs every test, builds build/libs/console-drift-service.jar
scripts/demo.sh                      # the demo: in-memory stores, the fake platform and a state stand-in in the same JVM
scripts/demo.sh journey              # with the demo running: drift two VMs in vSphere, revert one, accept the other
```

The demo listens on `http://localhost:18084`, the embedded fake platform on `http://localhost:18090`,
seeded with the two console accounts `123456789012` (compute) and `210987654321` (data). Dev headers
stand in for the BFF's token: `X-Console-Account`, `X-Console-User`, `X-Console-Groups` (what the
platform's delegation checks on a revert), or `X-Console-Service` for another console service (and
`X-Console-Region`, `X-Console-Roles`). The fake's `POST /api/v1/demo/world:mutate` changes an
object at the provider behind the console's back; the next read of its tier opens the finding.
To run against the real state service instead of the stand-in, start it on `18082` against the
same fake and run `CONSOLE_STATE_URL=http://localhost:18082 scripts/demo.sh`.

The journey tests (`DriftJourneyTest`, `FindingApiTest`) drive the same setup end to end: a change
at the provider, revert, accept, ignore, discovery and import, webhooks, and account isolation.

Until `console-libs` is published to GitHub Packages (hub decision 15), build it to your local
Maven repository first: `./gradlew :build-logic:publishToMavenLocal publishToMavenLocal -x test`
on the `console-libs` branch. CI does the same (`.github/workflows/ci.yml`, `CONSOLE_LIBS_REF`).

### Profiles

| Profile | What it changes |
| --- | --- |
| (none) | In-memory stores and outbox; the shard, sync and revert-follow loops inside this process; the real platform, state service and identity service over HTTP, called with service tokens from the BFF; JWTs from the BFF's JWKS |
| `demo` | The fake platform inside this JVM (`console.demo.*`), the state stand-in (`console.state.mode: embedded`), dev headers on (also for this service's own calls), fast tiers (10 s / 30 s / 60 s) and loops |
| `mongo` | MongoDB for findings, watches, discovery, idempotency keys and the outbox (`CONSOLE_MONGO_URI`, `CONSOLE_MONGO_DATABASE`) |
| `kafka` | The outbox relays `drift.findings` to Kafka; the `state.resources` topic reaches the watch list at once (`console.kafka.*`) |
| `temporal` | The loops run as Temporal workflows on the task queue `console-drift` (`console.temporal.*`), one per region, shard and tier, so a scan survives a restart and two replicas never run the same shard |

Production runs `mongo,kafka,temporal`; the Helm chart under `deploy/helm/console-drift-service`
wires that, with the two secrets it needs (MongoDB URI, platform token), a projected service-account
token for the BFF's service tokens, and a rendered `application-cluster.yaml`.

### Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `console.region` | `global` | The region this instance serves (hub decision 4) |
| `console.drift.shards` | `64` | Shards per region; a resource's shard is its wfrn's hash modulo this |
| `console.drift.tiers.hot`, `warm`, `cold` | `15m`, `1h`, `24h` | How often each tier is read (warm defers to the type's `observe.interval`) |
| `console.drift.tiers.recent-change`, `drifted-for`, `cold-after` | `24h`, `7d`, `30d` | What makes a resource hot or cold |
| `console.drift.scan.page-size`, `batch-size` | `500`, `100` | The platform's largest observe-list page and observe batch (P-09) |
| `console.drift.scan.list-threshold` | `20` | Fewer due resources than this in a group are read in a batch instead of pages |
| `console.drift.scan.max-per-scan` | `5000` | Due resources one scan turn takes from a shard |
| `console.drift.scan.observation-refresh` | `24h` | An unchanged observation is sent to the state service again after this |
| `console.drift.scan.in-flight-retry`, `error-retry` | `1m`, `5m` | When a resource with a change in flight, or whose read failed, is read again |
| `console.drift.budget.observe-per-minute`, `burst`, `account-share` | `300`, `50`, `0.25` | The observe budget per provider and region, and one account's share of it |
| `console.drift.loop.*`, `sync.*`, `follower.*` | on, `30s`; `60s`; `5s` | The scan loop's turn, the inventory sync's interval, the revert follower's interval (in this process, a tick still running after 14 minutes is cut, as a Temporal activity is) |
| `console.drift.sync.accounts` | `[]` | Accounts to watch in addition to the ones the identity service lists |
| `console.drift.idempotency-ttl`, `types-ttl` | `24h`, `60s` | How long an `Idempotency-Key` replays; how long resource types are cached |
| `console.drift.webhooks.<provider>.secret` | none | The HMAC secret of a provider's change notifications |
| `console.state.mode`, `base-url`, `timeout` | `http`, `http://localhost:18082`, `30s` | The state service; an answer must start within the timeout, and its body may not stall longer between reads |
| `console.identity.base-url`, `timeout` | `http://localhost:18083`, `10s` | The identity service: each account's platform credentials (the same timeout rule) |
| `console.service-tokens.mode` | `bff` | How this service names itself to the state and identity services: `bff` (hub decision 23) or `dev-headers` (demos) |
| `console.service-tokens.token-url`, `client-id` | `http://localhost:18081/internal/v1/token`, `console-drift-service` | The BFF's token endpoint and this service's client id there |
| `console.service-tokens.assertion-file` | `CONSOLE_SERVICE_ASSERTION_FILE` | The projected service-account token (audience `console-bff`) that authenticates the token request, in a cluster |
| `console.service-tokens.signing-key-file`, `key-id` | `CONSOLE_SERVICE_KEY_FILE`, `CONSOLE_SERVICE_KEY_ID` | Elsewhere: a PKCS#8 EC key (P-256 or P-384) registered for the client with the BFF, which signs a one-minute assertion per request |
| `console.service-tokens.refresh-before`, `timeout` | `60s`, `10s` | A token is asked again this long before it ends; how long the BFF may take |
| `console.platform.base-url`, `token`, `timeout`, `account-tokens` | `http://localhost:18090`, `CONSOLE_PLATFORM_TOKEN`, `30s`, `{}` | The platform's API; per-account tokens for a dev box without the identity service |
| `console.auth.jwks-uri`, `issuer`, `audiences` | the BFF's `/.well-known/jwks.json`, `console-bff`, `[console-drift-service, console]` | Which tokens are accepted |
| `console.auth.jwks-timeout` | `10s` | How long a fetch of the BFF's keys may take; a failed or slow fetch is not kept, so the next request fetches again |
| `console.auth.dev-headers` | `false` | Accept the dev headers instead of a token (demo only) |
| `console.temporal.target`, `namespace`, `task-queue`, `iterations` | `localhost:7233`, `default`, `console-drift`, `96` | The Temporal frontend and how many turns a loop takes before it continues as new |
| `console.kafka.bootstrap-servers`, `group-id`, `topics` | `localhost:9092`, `console-drift-service`, `[state.resources]` | The state events consumer (`kafka` profile) |
| `console.outbox.relay.*` | on, `1s` | The service kit's outbox relay |

## The API

Every call carries the BFF's internal token (ES256, `iss console-bff`, `aud console-drift-service`,
`acct` the account, `sub` the user) or, for another console service, a token without `acct`, which
may name the account with `?accountId=`. A finding of another account is a 404, never a 403.
Pages are 1 to 200 items, 50 by default, with the kit's opaque cursors (`after`, `next`).

| Call | Answer |
| --- | --- |
| `GET /v1/findings` | The account's findings, newest first; filters `wfrn` (a pattern), `status` (`open` by default, `closed`, `all`), `severity`, `type`, `region`, `openedAfter` |
| `GET /v1/findings/summary` | Open findings by type and severity, and when the account was last scanned |
| `GET /v1/findings/{findingId}` | One finding: its fields (`path`, `expected`, `observed`), version, resolution, `revertJobId` and `revertStatus` |
| `POST /v1/findings/{id}:revert` | 202 `{findingId, jobId}`; the type's update workflow runs with the desired values, on behalf of the person |
| `POST /v1/findings/{id}:accept` | 200, the finding closed as `accepted`; the observed values are the desired ones on the state service |
| `POST /v1/findings/{id}:ignore` | 200, closed as `ignored`; the fields stay silent until the desired state changes, or until `until` |
| `POST /v1/discover` | 202, a discovery run over the account's discoverable types, or the `type` and `region` given |
| `GET /v1/discover/{runId}` | The run: types read, objects seen, unmanaged found |
| `GET /v1/unmanaged` | Provider objects no wfrn holds; filters `type`, `region` |
| `POST /v1/unmanaged:import` | 201, the object imported into the state service (`source: discover`) and watched from then on |
| `POST /v1/webhooks/{provider}` | 202; `X-Webhook-Signature: sha256=<hex>` (HMAC-SHA256 of the raw body); the resources it names are read now |
| `GET /internal/watches`, `/internal/watches/{wfrn}`, `POST /internal/watches/{wfrn}:check` | For services and operators: the watch list, one watch, read one now |
| `POST /internal/scan`, `/internal/sync`, `/internal/follow` | Operations: one scan pass over every shard now (`?tier=` for one tier), sync the watch list now, look at the running revert jobs now |

The decisions take `{reason}` (and `until` on an ignore) and the headers `Idempotency-Key` (the same
key and body replay the first answer with `Idempotent-Replay: true`; another body is a 409) and
`X-Console-Request-Id` (carried into the job, the state calls and the events). A decision on a
closed finding is a 409 `finding.closed` naming its resolution; others are `finding.reverting` (a
revert job is running), `finding.notRevertable` (no update workflow, or a drifted field it cannot
set), `finding.unwatched` (the resource left the inventory) and `revert.needsPerson` (the call
carries no person to act for).

## How drift is found

```
 state service ──(list, state.resources)──▶ inventory sync ──▶ watch list (desired, tier, next due)
                                                                    │
                                     shard loop, every turn: the due watches of one shard
                                                                    │
                     per type, region, environment: :observe-list pages / resources:observe batches
                     (imported resources: the type's :discover pages, matched by provider id)
                                                                    │
                                                              reconciler ──▶ state: observation, drift, resolution
                                                                    │
                                                    finding store ──▶ outbox ──▶ drift.findings
```

The reconciler compares both sides normalised (numbers, booleans and numeric strings by value,
trimmed strings, lists of scalars in any order, objects in any key order), leaves out the type's
`ignore` fields and the fields a provider reports but no workflow sets, and lists silenced fields
without counting them. Severity follows from what drifted: a missing resource or anything in
production is `critical`, a field the type marks mutable (`x-console: {mutable: true}`, one an update
can put back) is `warning`, anything else `info`. A resource with a change in flight (`pending`, `updating`,
`deleting`) is not compared; it is read again shortly.

| Event (`drift.findings`) | When |
| --- | --- |
| `FindingOpened` | A read differs from the desired state and no finding is open |
| `FindingUpdated` | Other fields drifted, or a revert started or failed |
| `FindingClosed` | Back in line (`vanished`), reverted, accepted, ignored, or the resource was deleted |

Events use the P-01 envelope with the aggregate `finding` and the key
`<type>:<wfrn>:<findingId>:<version>`, published through the service kit's outbox.

## Concurrency

Scans, the inventory sync, webhooks and people's decisions all write to the same two records, so
every write is conditional:

- **One open finding per resource**: a partial unique index on `wfrn` where the finding is open.
- **Findings never go back**: a write with an older version, or one that would reopen a closed
  finding, is refused and answered with the stored finding; the caller then neither tells the state
  service nor publishes.
- **Watches carry a revision**: a save only lands on the revision it read. A scan that read a watch
  before an accept, an ignore or a new desired state decides again on the watch as it stands, so a
  stale copy never opens a finding against old values nor undoes a decision.

## Storage

MongoDB, one database (`console_drift`) per deployment; indexes are created at start by each adapter.

| Collection | Document | Indexes |
| --- | --- | --- |
| `drift_findings` | `_id` findingId, `wfrn`, `accountId`, `region`, `type`, `status`, `severity`, `version`, `revertStatus`, `openedAt`, `doc` | unique `wfrn` where open (`one_open_finding_per_resource`), `{accountId, status, openedAt desc}`, `{wfrn, status}`, `{status, revertStatus}` |
| `drift_watches` | `_id` wfrn, `accountId`, `type`, `shard`, `tier`, `nextCheckAt`, `lastCheckedAt`, `providerId`, `registryId`, `rev`, `doc` | `{shard, tier, nextCheckAt}`, `{shard, nextCheckAt}`, `{accountId, type, _id}`, `{accountId, lastCheckedAt desc}`, `providerId`, `registryId` |
| `drift_discovery_runs`, `drift_unmanaged` | a discovery run; an unmanaged provider object | `{accountId, type, region, status}`; `{accountId, type, region, _id}` |
| `idempotency_keys` | `_id` `<accountId>:<key>`, `bodyHash`, `status`, `body`, `expiresAt` | TTL `expiresAt` |
| `outbox`, `processed_events` | the service kit's | the service kit's |

## Layout

```
com.iaf.console.drift
├── drift                         the one slice: findings and the watch list
│   ├── domain                    Finding, Watch, DriftRules, TierRules, Observation, ObserveBudget, Shards; no Spring, no I/O
│   ├── ports.outbound            FindingStorePort, WatchStorePort, StateServicePort, PlatformPort, ResourceTypesPort,
│   │                             WorkflowStarterPort, OnBehalfOfPort, DiscoveryStorePort, IdempotencyKeyPort
│   ├── application               ShardScanner, Reconciler, InventorySync, DriftService (decisions), RevertFollower,
│   │                             DiscoveryService, WebhookService, FindingQueries, FindingEvents, StateEvents
│   └── adapters.{web,mongo,memory,state,platform,kafka,temporal,loop}
├── auth                          ConsolePrincipal, JwtVerifier (the BFF's JWKS), ConsoleAuthWebFilter
└── boot                          DriftProperties, the profile wiring, the demo's world alignment
```

ArchUnit (the kit's `ConsoleArchitectureRules`) keeps the domain free of Spring and adapters, the
application free of adapters, and every scoped read behind an `AccountScope`.

## Decisions

| # | Decision | Why |
| --- | --- | --- |
| 1 | A watch list with the desired state, fed by the state service | A scan compares a page of provider objects without a state read per resource; 10 million resources in hourly tiers stay a few thousand page reads |
| 2 | Paged reads by type, region and environment; batches below 20 due | The platform's `:observe-list` answers 500 objects per call (P-09); a handful of due resources is cheaper as one batch |
| 3 | Shards as the unit of work, one loop per shard and tier | Work spreads over replicas through Temporal workflow ids; a second start of the same loop finds the first |
| 4 | Revert never automatic, always on behalf of a person | Plan decision: show and offer, never fix on its own; the platform's delegation checks the person's groups on the job (`revert.needsPerson` without one) |
| 5 | A revert closes only when the next read agrees | A succeeded job is not proof; the read is |
| 6 | Silences are bound to the desired state's digest | A new desired state brings the field back into view without anyone having to remember the silence |
| 7 | Conditional writes everywhere (finding versions, watch revisions) | Several writers; a stale writer must lose rather than undo a person's decision |
| 8 | Authorization is the BFF's | As for the state service: the BFF checks roles before it calls; this service scopes every read and write to the token's account |
| 9 | Imported resources are read through `:discover` | The fake and the platform's `:observe-list` answer managed objects only; an imported object has a provider id and no registry record |
| 10 | Unknown webhook providers answer 401 | The contract allows 202, 400, 401; a provider without a secret cannot be verified, which is the 401's meaning |
| 11 | Service tokens from the BFF for this service's own calls | Hub decision 23: the state and identity services verify only the BFF's JWKS, so this service asks `POST /internal/v1/token` (client credentials with a JWT client assertion) for a five-minute token per audience, `console-state-service` and `console-identity-service`. The assertion is the pod's projected service-account token, or a JWT signed with the service's own registered key; there is no static token. Tokens are kept until a minute before they end, and a target's 401 gets one fresh token and one retry |

### Contract additions (raised with the hub)

Hub PR #14 (`console-root--drift-contract`, the commit `contracts.lock` pins) writes these into the drift contract; all are backward compatible:
`revertStatus` on a finding; the `X-On-Behalf-Of` header relayed on `:revert`; the 409 codes
`revert.needsPerson`, `finding.reverting`, `finding.notRevertable` and `finding.unwatched`; the job
label `drift-finding` that ties a revert job to its finding; the `/internal/*` operations; and on
an import of a discovered object, `registryId` set to the provider id, since the state contract
requires one and a discovered object has no registry record.

### The demo's world

The fake platform answers single reads (`vm.get`, `cluster.get`) with a fixed sample and pages
with its recorded world. At start the demo writes each recorded object's attributes into the fake's
world (`DemoWorld`), so every read agrees with the registry and only real changes show as drift.

## CI and release

`.github/workflows/ci.yml` builds `console-libs` into the local Maven repository, then builds and
tests against MongoDB 7 (the Mongo adapter tests skip without `CONSOLE_TEST_MONGO_URI`), scans files
and new commits for secrets with gitleaks, and lints and renders the Helm chart. The image
(`Dockerfile`) is the boot jar on Temurin 21 as a non-root user; publishing it and the chart waits
for the registry decision on the hub.
