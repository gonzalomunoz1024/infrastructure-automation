# console-drift-service

The customer console's drift detection: it compares what a customer asked for (the desired state
the state service holds for every resource) with what the provider actually has (read through the
platform's observe operations), records each difference as a finding, and offers the three
decisions a person takes on it: **revert** in one click, **accept** the provider's values, or
**ignore** the fields. Nothing is ever put back on its own: a revert is always a person's call.
It also scores every resource it reads against the compliance rule packs and keeps each account's
scorecard, its trend and the one-click fixes (see [Compliance scorecards](#compliance-scorecards)).

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
- **Finds what the console does not manage** (the shadow-infrastructure finder, WP 5.3): a
  discovery run lists each type's provider objects through the platform's `:discover` operation and
  keeps the ones no account manages. Each one gets a guess at its owning account, with the reasons
  in plain words, and an estimated monthly cost. It can be assigned to another team's account, which
  then lists it too, and imported into the state service by either account.
- **Counts drift for the executive home** (WP 9.5): the findings opened and closed in a window, by
  resolution and severity, with the mean time to close, per account or over every account.

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
platform's delegation checks on a revert) and `X-Console-Roles` (the managed roles the demo's
writes are checked against: `operator` decides findings, only `account-admin` deletes), or
`X-Console-Service` for another console service (and `X-Console-Region`). The fake's `POST /api/v1/demo/world:mutate` changes an
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
| `console.identity.base-url`, `timeout` | `http://localhost:18083`, `10s` | The identity service: each account's platform credentials, and the active accounts the owner guesses and assignments name, kept for `console.drift.sync.accounts-refresh` (the same timeout rule) |
| `console.cost.base-url`, `timeout` | empty, `5s` | The cost service's `POST /v1/cost:estimate` (audience `console-cost-service`); empty uses the price list below |
| `console.cost.currency`, `basis`, `prices.<type>` | `USD`, `list-prices`, vm, database and volume | The price list for a box without the cost service: per type a `monthly` base, a rate `per` unit of numeric attributes, and an attribute (`by`) whose value picks one of `choices` |
| `console.service-tokens.mode` | `bff` | How this service names itself to the state and identity services: `bff` (hub decision 23) or `dev-headers` (demos) |
| `console.service-tokens.token-url`, `client-id` | `http://localhost:18081/internal/v1/token`, `console-drift-service` | The BFF's token endpoint and this service's client id there |
| `console.service-tokens.assertion-file` | `CONSOLE_SERVICE_ASSERTION_FILE` | The projected service-account token (audience `console-bff`) that authenticates the token request, in a cluster |
| `console.service-tokens.signing-key-file`, `key-id` | `CONSOLE_SERVICE_KEY_FILE`, `CONSOLE_SERVICE_KEY_ID` | Elsewhere: a PKCS#8 EC key (P-256 or P-384) registered for the client with the BFF, which signs a one-minute assertion per request |
| `console.service-tokens.refresh-before`, `timeout` | `60s`, `10s` | A token is asked again this long before it ends; how long the BFF may take |
| `console.platform.base-url`, `token`, `timeout`, `account-tokens` | `http://localhost:18090`, `CONSOLE_PLATFORM_TOKEN`, `30s`, `{}` | The platform's API; per-account tokens for a dev box without the identity service |
| `console.auth.jwks-uri`, `issuer`, `audiences` | the BFF's `/.well-known/jwks.json`, `console-bff`, `[console-drift-service]` | Which tokens are accepted: a token's `aud` must be exactly one of `audiences` (hub decision 35) |
| `console.auth.service-callers` | `[console-bff]` | The console services (`svc`, else `sub`) whose tokens may name no account and act for any; any other token without `acct` is a 401 |
| `console.auth.authorization` | `identity` (`roles` in the demo) | How a person's writes are checked here too: `identity` asks the identity service (`POST /v1/authorize`, refused when it does not answer), `roles` reads the token's managed roles |
| `console.auth.jwks-timeout` | `10s` | How long a fetch of the BFF's keys may take; a failed or slow fetch is not kept, so the next request fetches again |
| `console.auth.dev-headers` | `false` | Accept the dev headers instead of a token (demo only) |
| `console.temporal.target`, `namespace`, `task-queue`, `iterations` | `localhost:7233`, `default`, `console-drift`, `96` | The Temporal frontend and how many turns a loop takes before it continues as new |
| `console.kafka.bootstrap-servers`, `group-id`, `topics` | `localhost:9092`, `console-drift-service`, `[state.resources]` | The state events consumer (`kafka` profile) |
| `console.outbox.relay.*` | on, `1s` | The service kit's outbox relay |
| `console.scorecards.engine` | `embedded` | The policy engine rules run on: `embedded` (the packs' declarative `when`/`check`) or `opa` |
| `console.scorecards.opa-url`, `opa-timeout`, `push-policies` | `http://localhost:8181`, `5s`, `true` | The firm's OPA, and whether this service loads the packs' Rego into it at start (`PUT /v1/policies/console-scorecards/<pack>/<rule>`) |
| `console.scorecards.packs-dir` | none | A directory of `*.yaml` rule packs besides the three built in |
| `console.scorecards.snapshot-interval` | `10m` | How often accounts without today's snapshot are recounted and snapshotted |

## The API

Every call carries the BFF's internal token (ES256, `iss console-bff`, `aud console-drift-service`,
`acct` the account, `sub` the user) or, for another console service, a token without `acct`, which
may name the account with `?accountId=`. A finding of another account is a 404, never a 403.
Pages are 1 to 200 items, 50 by default, with the kit's opaque cursors (`after`, `next`).

| Call | Answer |
| --- | --- |
| `GET /v1/findings` | The account's findings, newest first; filters `wfrn` (a pattern), `status` (`open` by default, `closed`, `all`), `severity`, `type`, `region`, `openedAfter` |
| `GET /v1/findings/summary` | Open findings by type and severity, and when the account was last scanned |
| `GET /v1/findings/stats` | Findings opened and closed between `since` (30 days ago by default) and `until` (now), the closed ones by resolution, the opened ones by severity, open now, and the mean minutes to close; another console service may leave out `accountId` for every account |
| `GET /v1/findings/{findingId}` | One finding: its fields (`path`, `expected`, `observed`), version, resolution, `revertJobId` and `revertStatus` |
| `POST /v1/findings/{id}:revert` | 202 `{findingId, jobId}`; the type's update workflow runs with the desired values, on behalf of the person |
| `POST /v1/findings/{id}:accept` | 200, the finding closed as `accepted`; the observed values are the desired ones on the state service |
| `POST /v1/findings/{id}:ignore` | 200, closed as `ignored`; the fields stay silent until the desired state changes, or until `until` |
| `POST /v1/discover` | 202, a discovery run of the `type` in the `region`; 409 `discover.running` with the running run's `runId` in `detail` while one runs (a run still running after two hours lost its walk: it is marked failed and a new one starts) |
| `GET /v1/discover/{runId}` | The run: types read, objects seen, unmanaged found |
| `GET /v1/unmanaged` | Provider objects no account manages, with `ownerGuess`, `monthlyCost` and `assignment`; filters `type`, `region`, `owner` (`any`, `mine`: guessed to be or assigned to the account, `unknown`: no guess) |
| `POST /v1/unmanaged:import` | 201, the object imported into the state service (`source: discover`) and watched from then on; every account's copy leaves the lists. Only the account it was handed to may import it, else the guessed owner, else (nobody guessed) an account admin: anyone else is a 409 `unmanaged.notYours` |
| `POST /v1/unmanaged:assign` | 200, the object handed to another active account (`accountId`, `note` up to 500 characters), which lists it too until either imports it; assigning again moves it, and assigning it back to the account that found it takes the assignment off. 400 `unmanaged.unknownAccount` for this account or one the identity service does not list |
| `POST /v1/webhooks/{provider}` | 202; `X-Webhook-Signature: sha256=<hex>` (HMAC-SHA256 of the raw body); the resources it names are read now. A signature received in the last 10 minutes schedules nothing, so a replayed notification cannot spend the observe budget |
| `GET /internal/watches`, `/internal/watches/{wfrn}`, `POST /internal/watches/{wfrn}:check` | For services and operators: the watch list, one watch, read one now |
| `POST /internal/scan`, `/internal/sync`, `/internal/follow` | Operations: one scan pass over every shard now (`?tier=` for one tier), sync the watch list now, look at the running revert jobs now |
| `GET /v1/scorecards/packs` | The rule packs every resource is scored against, with each rule's severity, types and one-click fix |
| `GET /v1/scorecards/account` | The account's score (0 to 100, or null with nothing scored), passing and failing counts by severity, per pack and per rule, and the trend of `days` daily snapshots (30 by default, up to 400) |
| `GET /v1/scorecards/heatmap?accountIds=` | Services only (a person gets 404): the scores of 1 to 500 accounts by pack, in the order asked; the BFF names the accounts the person may see |
| `GET /v1/scorecards/packs/{pack}/rules/{ruleId}/failing` | The account's resources that fail a rule, longest failing first; filter `type` |
| `GET /v1/scorecards/resources?wfrn=` | One resource's result for every rule that applies to it |
| `POST /v1/scorecards/packs/{pack}/rules/{ruleId}:remediate` | The rule's one-click fix for one failing resource, on behalf of the person: 200 with the tags set, or 202 with the job started |
| `POST /internal/scorecards:rescore`, `:snapshot` | Operations: score every watched resource again now (after a pack changed; `?accountId=` for one account), recount and snapshot every account now |

The decisions take `{reason}` (and `until` on an ignore) and the headers `Idempotency-Key` (the same
key and body replay the first answer with `Idempotent-Replay: true`; another body is a 409) and
`X-Console-Request-Id` (carried into the job, the state calls and the events). A decision on a
closed finding is a 409 `finding.closed` naming its resolution; others are `finding.reverting` (a
revert job is running), `finding.notRevertable` (no update workflow, or a drifted field it cannot
set), `finding.unwatched` (the resource left the inventory) and `revert.needsPerson` (the call
carries no person to act for).

A fix (`:remediate`) takes `{wfrn, input, reason}`, the same headers and `X-On-Behalf-Of`. It never
runs on its own. A `tags` fix sets the tags through the state service in the person's name; a
`workflow` fix starts the type's verb (or the named workflow) on the platform on behalf of the
person, labelled `scorecard-rule: <pack>.<rule>`. Its 409s are `evaluation.passing` (the resource
passes the rule now), `remediation.none` (the rule has no fix), `remediation.unavailable` (the
type has no such verb), `remediation.needsPerson` and `resource.inFlight`; a missing or wrong
input is a 400 naming it.

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

## Compliance scorecards

Every managed resource is scored against the rule packs (WP 9.6, hub decision 26). A pack is a
`kind: RulePack` file of the hub (`rule-packs/*.yaml`, copied into `src/main/resources/rule-packs`
and pinned in `contracts.lock`); each rule carries its policy twice, as Rego for OPA and as the
declarative `when`/`check` the embedded engine runs, and examples both must agree on (the hub's
check runs them through OPA, this service's tests through both engines). The built-in packs are
`required-tags` (owner, cost centre, data class, environment), `compute-baseline` (production VMs
with at least 2 vCPUs and 4 GiB, powered on) and `data-baseline` (a supported engine version,
the production tier, credentials in the vault).

```
 reconciler (every read) ─┐                                  ┌─▶ scorecard_evaluations (one per resource)
                          ├─▶ evaluator ─▶ policy engine ────┤─▶ scorecard_failures   (one per failing rule)
 inventory sync (changes)─┘   (skips an unchanged input)     ├─▶ scorecard_counters   ($inc by deltas)
                                                             └─▶ outbox ─▶ drift.scorecards
 snapshot loop (daily per account): recount from the evaluations ─▶ counters replaced, scorecard_snapshots
```

- **The input** is `{resource: {wfrn, accountId, region, type, service, name, state, environment,
  production, attributes, observed, effective, tags}}`, where `effective` is the desired attributes
  with the last provider read laid over them. Its digest, with the packs' digest, is kept on the
  evaluation, so an unchanged read never asks the engine.
- **One evaluation per resource and rule**, stored as one document per resource and written only
  over the version before it; a lost race scores again on what is stored. A rule that does not
  apply is not scored; one the engine gave no verdict for is an `error` and not counted.
- **The score** weighs rules by severity (critical 10, warning 3, info 1): 100 × the weighted
  passing evaluations over all weighted evaluations, rounded; null when nothing counts. Per-account
  counters move by the deltas of each change, so a scorecard is a read of a few hundred counters at
  any account size; the daily recount repairs any drift in them and records the day's snapshot.
- **The engine** is a port: `embedded` until the firm's OPA is wired in, `opa` then. A failed call
  leaves the stored evaluation and scores as they were.

| Event (`drift.scorecards`, partitioned by account) | When |
| --- | --- |
| `EvaluationChanged` | A resource's results changed: the rules whose status moved, from and to; aggregate `evaluation`, key `EvaluationChanged:<wfrn>:<version>` |
| `ScoreSnapshotted` | An account's daily snapshot: score, per-pack scores, failing count and the previous score; aggregate `scorecard`, key `ScoreSnapshotted:<accountId>:<day>` |

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

## Monitoring and rightsizing

Two more parts of the service read the same resources' metrics (WP 9.21 and WP 9.22): alarms on a
series, and recommendations to shrink what is oversized or delete what is idle. Each is a slice of
its own beside `drift` (`monitoring`, `rightsizing`) and uses the drift slice's watch list, resource
types, state service, platform and job starter through their ports; the drift slice knows nothing
of them.

Series come from the type's metrics read (P-09 `observe.metrics`, `POST /api/v1/resources/{id}:metrics`),
called with the account's own platform credential and paced by the same observe budget as the scans:
when the budget is spent, or the platform answers 429, the caller hears 429 with the wait, and the
budget stays empty until then. A resource of another account is a 404, never a 403. A type without a
metrics read is a 404 `metrics.notSupported`, and so is an imported object, which has no registry
record for the platform to read it by. Any other platform failure is a 502.

| Call | Answer |
| --- | --- |
| `GET /v1/metrics` | A resource's series (`wfrn`, `from`, `to`, `step`): without `from` the last 24 hours, without `step` a point every 5 minutes up to a day and every hour beyond; `step` is an ISO-8601 duration of at least a minute, and one read holds at most 92 days and 5000 points. Another console service may name `accountId`; without it, the wfrn's own account |
| `GET /v1/alarms` | The account's alarms, firing first (`alarm`, `insufficient`, `ok`), then by id; filters `wfrn`, `state` |
| `GET /v1/alarms/{alarmId}` | One alarm: its rule, `state`, `stateSince`, `lastValue`, `lastEvaluatedAt`, `createdBy` |
| `PUT /v1/alarms/{alarmId}` | `{wfrn, name, metric, comparison, threshold, windowMinutes}`: 201 created (`insufficient` until its first evaluation) or 200 changed (its state stays until the next one). The caller names the id, so a retried create is the same alarm; `comparison` is `above` or `below`, `metric` one of the type's series, `windowMinutes` 1 to 1440 (10 by default) |
| `DELETE /v1/alarms/{alarmId}` | 204 |
| `POST /internal/alarms:evaluate` | For operators: evaluate every alarm now; answers what the pass did |
| `GET /v1/recommendations` | The account's recommendations, the largest monthly saving first; filters `type`, `kind` (`idle`, `oversized`), `status` (`open` by default, `applied`, `dismissed`, `superseded`), `wfrn` |
| `GET /v1/recommendations/summary` | Open recommendations by kind, what they would save a month, and when the last pass read the account |
| `GET /v1/recommendations/{id}` | One recommendation: the size now (`current`) and the one `proposed`, the utilisation behind it, its `monthlyCost` and `monthlySaving`, and once decided, who decided, why, and the `jobId` |
| `POST /v1/recommendations/{id}:apply` | 202 `{recommendationId, jobId}`: the state service records the change, then the type's update workflow (with the proposed size) or its delete workflow starts on behalf of the person, labelled `drift-recommendation`; the recommendation is `applied` once the job is started |
| `POST /v1/recommendations/{id}:dismiss` | 200, `dismissed` for good, or until `until` |
| `POST /internal/utilisation` | For operators: read every resource's utilisation now (`?accountId=` for one account), whether or not its turn has come; answers what the pass did |

The decisions take the drift API's headers: `Idempotency-Key` (the same key and body replay the
first answer with `Idempotent-Replay: true`), `X-Console-Request-Id`, and on `:apply`
`X-On-Behalf-Of`. An apply answers 409 `recommendation.closed` when the recommendation is applied,
dismissed or superseded (and supersedes it when the resource changed size or left the inventory
since the pass), `recommendation.changing` when a change is in flight on the resource,
`recommendation.notApplicable` when the type has no update or delete workflow or the update does not
set the size field, and `revert.needsPerson` when the call carries no person to act for. A dismissal
of a closed one is a 409 `recommendation.closed` too.

### Alarms

An alarm compares the average of one series over its window with its threshold: `alarm` when it
crosses it, `ok` when not, and `insufficient` when the provider gave no points (a resource that left
the inventory gives none). `stateSince` moves only when the state changes, so it says how long an
alarm has been firing. Every minute the evaluation takes the alarms a resource at a time, so each
resource is read once, over the longest window its alarms need, a point a minute; a read that fails
(a spent budget, a platform error) leaves its alarms as they were, and the next minute tries again.
Alarms carry a revision: an evaluation that read an alarm before a person changed it leaves the
change standing.

### Recommendations

Once a day, each watched resource whose type has a metrics read has the last 14 days of its CPU and
memory read, a point an hour, and only since it last changed size; a judgement needs a week of
points (168). On the 95th percentiles:

- **idle**: CPU under 5% and memory under 10%. Applying it deletes the resource through the type's
  delete workflow, with the person's reason, or one that names the recommendation.
- **oversized**: CPU under 20% or memory under 30%. That dimension shrinks to the smallest allowed
  value at which its 95th percentile, scaled to the smaller size, stays under 60%. The allowed values
  are the update workflow's input `enum`, else the type's, else whole halvings down to 1 CPU and 2 GB.

Each recommendation is priced through the service's cost estimates: an idle one saves what the
resource costs, an oversized one the difference to the proposed size, and a resource without a price
shows no cost or saving. A resource has at most one current recommendation (open or dismissed). A later
pass refreshes an open one with the new numbers, replaces it when the kind changes, and supersedes it
when the resource changed size, left the inventory or no longer qualifies. A dismissal holds until its
date, then opens again if the resource still qualifies. A resource with a change in flight is skipped.
Nothing is applied on its own: like a revert, that is always a person's call.

### Loops

| Loop | In this process | Under `temporal` |
| --- | --- | --- |
| `alarms` | Every `alarms.interval` (a minute) | The workflow `console-drift-<region>-alarms` |
| `utilisation` | First after `rightsizing.initial-delay` (a minute), then every `check-interval` (an hour), reading the resources whose turn has come | The workflow `console-drift-<region>-utilisation` |

Because the utilisation loop wakes often and reads only what is due, a restart does not read every
resource again, and a pass cut short (a tick is cut after 14 minutes, as a Temporal activity is) goes
on at the next wake.

### Settings

| Property | Default | Meaning |
| --- | --- | --- |
| `console.monitoring.metrics.mode` | `platform` | Where series come from: the type's metrics read, or `demo` (made-up series, set by the demo profile only) |
| `console.monitoring.metrics.max-range`, `max-steps` | `92d`, `5000` | How far one `GET /v1/metrics` may reach, and the most points it may hold |
| `console.monitoring.metrics.demo.types`, `idle`, `oversized`, `busy` | `[vm]`, `[batch-01, shop-01]`, `[web-02]`, `[cache-01]` | The demo's types with series, and the resources (by name) that are idle, oversized or busy |
| `console.monitoring.alarms.enabled`, `interval`, `concurrency` | on, `1m`, `4` | The alarm evaluation: how often, and how many resources one evaluation reads at once |
| `console.monitoring.rightsizing.enabled`, `interval`, `check-interval`, `initial-delay` | on, `24h`, `1h`, `1m` | The utilisation pass: each resource is read once an `interval`; the loop wakes every `check-interval`, the first time after `initial-delay` |
| `console.monitoring.rightsizing.window`, `step`, `min-history` | `14d`, `1h`, `7d` | How far back a read reaches, a point per `step`, and the history a judgement needs |
| `console.monitoring.rightsizing.idle-cpu`, `idle-memory`, `oversized-cpu`, `oversized-memory`, `target-p95` | `5`, `10`, `20`, `30`, `60` | The lines on the 95th percentiles, in percent |
| `console.monitoring.rightsizing.cpu-series`, `memory-series`, `cpu-field`, `memory-field` | `cpuPercent`, `memoryPercent`, `cpu`, `memoryGb` | The series read, and the size fields a resize sets |
| `console.monitoring.rightsizing.min-cpu`, `min-memory`, `concurrency` | `1`, `2`, `4` | How far halving may go for a size without allowed values; how many resources one pass reads at once |

### Collections

Under the `mongo` profile, in the same database as the findings; in memory otherwise.

| Collection | Document | Indexes |
| --- | --- | --- |
| `drift_alarms` | `_id` `<accountId>:<alarmId>`, `accountId`, `alarmId`, `wfrn`, `stateRank`, `rev`, `doc` | `{accountId, stateRank, alarmId}`, `{accountId, wfrn}`, `{wfrn, accountId, alarmId}` |
| `drift_recommendations` | `_id` recommendationId, `accountId`, `wfrn`, `type`, `kind`, `status`, `active` (open or dismissed), `savingSort`, `rev`, `doc` | unique `wfrn` where active (`one_current_recommendation_per_resource`), `{accountId, status, savingSort desc, _id desc}`, `{active, accountId}` |
| `drift_utilisation` | `_id` wfrn, `accountId`, `readAt`, `doc`: the last read's percentiles and samples, and the size it was read at and since when | `{accountId, readAt desc}` |

### Where the code is

```
com.iaf.console.drift
├── monitoring                    metrics and alarms (WP 9.21)
│   ├── domain                    Alarm, AlarmInput, Comparison, MetricsWindow, ResourceMetrics, MetricsTarget
│   ├── ports.outbound            AlarmStorePort, MetricsPort
│   ├── application               MetricsQueries, MetricsTargets, AlarmService, AlarmEvaluator
│   └── adapters.{web,mongo,memory,platform,demo}
├── rightsizing                   utilisation and recommendations (WP 9.22)
│   ├── domain                    Recommendation, RightsizingRules, SizeOptions, Utilisation, ResourceUtilisation
│   ├── ports.outbound            RecommendationStorePort, UtilisationStorePort
│   ├── application               UtilisationPass, RecommendationService (decisions), RecommendationQueries
│   └── adapters.{web,mongo,memory}
└── boot                          MonitoringProperties, MonitoringConfiguration
```

### Why it works this way

| Decision | Why |
| --- | --- |
| The lines (idle under 5% CPU and 10% memory, oversized under 20% or 30%, a target of 60%, a week of history) are settings | The contract names idle and oversized but not where the lines are; a week of hourly points covers a weekly cycle |
| A resize picks from what the update workflow accepts, then the type's values, then whole halvings | A proposal the workflow would refuse is no proposal, and halving is what a person would try first |
| Applied once the job is started, with no follower | The contract: the next pass judges the new size, and a failed job leaves the resource to qualify again |
| Each resource read once a day, by a loop that wakes hourly | A restart does not read every resource again, and a pass cut short goes on at the next wake; the reads take their turn in the account's share of the observe budget and wait when it is spent |
| A dismissal holds until its date, a change of size or a change of kind | Set aside means set aside, but an idle VM that turns oversized is a new question |
| The summary adds savings in one currency | Money in different currencies cannot be added; it counts the currency most open recommendations are priced in |
| Alarms are evaluated per resource, over the longest window | One read per resource a minute, whatever the number of its alarms |

### In the demo

The fake platform's types have no metrics read, so the demo profile sets `metrics.mode: demo`: CPU,
memory and disk series for the VMs, the same numbers on every read, a daily wave around a level the
VM's name picks. `batch-01` and `shop-01` are idle, `web-02` oversized, `cache-01` busy and every
other VM ordinary. Alarms are evaluated every 30 seconds, and the first utilisation pass runs 20
seconds after the start, so three recommendations show within a minute. Nothing of it runs in
production.

## Storage

MongoDB, one database (`console_drift`) per deployment; indexes are created at start by each adapter.

| Collection | Document | Indexes |
| --- | --- | --- |
| `drift_findings` | `_id` findingId, `wfrn`, `accountId`, `region`, `type`, `status`, `severity`, `version`, `revertStatus`, `openedAt`, `closedAt`, `resolution`, `doc` | unique `wfrn` where open (`one_open_finding_per_resource`), `{accountId, status, openedAt desc}`, `{wfrn, status}`, `{status, revertStatus}`, and for the window counts `{accountId, openedAt}`, `{accountId, closedAt}`, `openedAt`, `closedAt` |
| `drift_watches` | `_id` wfrn, `accountId`, `type`, `shard`, `tier`, `nextCheckAt`, `lastCheckedAt`, `providerId`, `registryId`, `rev`, `doc` | `{shard, tier, nextCheckAt}`, `{shard, nextCheckAt}`, `{accountId, type, _id}`, `{accountId, lastCheckedAt desc}`, `providerId`, `registryId` |
| `drift_discovery_runs`, `drift_unmanaged` | a discovery run; an unmanaged provider object, one copy per account that lists it, with `guessAccountId` and `assignedTo` for the owner filter | `{accountId, type, region, status}`; `{accountId, type, region, _id}`, `{accountId, _id}`, `{providerId, type, region}` |
| `idempotency_keys` | `_id` `<accountId>:<key>`, `bodyHash`, `status`, `body`, `expiresAt` | TTL `expiresAt` |
| `scorecard_evaluations` | `_id` wfrn, `accountId`, `type`, `version`, `failing`, `doc` (every rule's result) | `{accountId, _id}` |
| `scorecard_failures` | `_id` `<wfrn>|<pack>/<rule>`, `accountId`, `wfrn`, `rule`, `type`, `since`, `doc` | `{accountId, rule, since, _id}`, `{accountId, rule, type, since, _id}`, `wfrn` |
| `scorecard_counters` | `_id` `<accountId>|<pack>/<rule>`, `accountId`, `rule`, `passed`, `failed` | `accountId` |
| `scorecard_snapshots` | `_id` `<accountId>|<day>`, `accountId`, `day`, `at`, `doc` | `{accountId, day}`, TTL 400 days on `at` |
| `outbox`, `processed_events` | the service kit's | the service kit's |

## Layout

```
com.iaf.console.drift
├── drift                         findings and the watch list
│   ├── domain                    Finding, Watch, DriftRules, TierRules, Observation, ObserveBudget, Shards,
│   │                             UnmanagedObject, OwnerGuesses, FindingStats, Money; no Spring, no I/O
│   ├── ports.outbound            FindingStorePort, WatchStorePort, StateServicePort, PlatformPort, ResourceTypesPort,
│   │                             WorkflowStarterPort, OnBehalfOfPort, DiscoveryStorePort, IdempotencyKeyPort,
│   │                             AccountDirectoryPort, CostEstimatesPort
│   ├── application               ShardScanner, Reconciler, InventorySync, DriftService (decisions), RevertFollower,
│   │                             DiscoveryService, WebhookService, FindingQueries, FindingEvents, StateEvents
│   └── adapters.{web,mongo,memory,state,platform,identity,cost,kafka,temporal,loop}
├── scorecards                    compliance scorecards (WP 9.6)
│   ├── domain                    RulePack, Rule, Condition, ResourceEvaluation, Scores, Templates, Views; no Spring, no I/O
│   ├── ports.outbound            PolicyEnginePort, EvaluationStorePort, ScoreStorePort
│   ├── application               ScorecardEvaluator (the drift slice's ResourceListener), ScorecardQueries,
│   │                             ScorecardSnapshots, RemediationService, ScorecardEvents
│   └── adapters.{web,mongo,memory,engine,content,loop}
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
| 12 | Owner guesses in steps of confidence, and only when one account is named | A tag naming an account's team, id or name is `high`; the account's or team's name as whole words in the object's name is `medium`; sharing a folder, network, cluster or pool with one account's managed resources only, or being of a type only one account manages in the region, is `low`. A step that names two accounts says nothing rather than guess wrong. The guesses read what the type's `observe.map` gives, and the managed neighbours come from the same `:discover` pages, so a guess costs no extra read |
| 13 | An assignment is a second copy, not a pointer | The handed account lists the object with its own reads and pages, scoped like every other read; an import by either account removes every copy, and a run skips an object any account has imported |
| 14 | Costs from the cost service, a price list without it | The cost service owns prices (WP 7.3); the drift service only asks, and an unpriced type or a failed estimate leaves `monthlyCost` out rather than fail a run |
| 15 | Window counts by the store | Mongo answers opened and closed per window from indexed top-level `openedAt`, `closedAt` and `resolution`, so the executive home never pages through findings |
| 16 | Writes are checked here as well as at the BFF | Security review: a token that reaches this service another way must not do more than the person may. Each write asks the identity service for the BFF's own action on the resource (`<service>:update` for a decision, an alarm or a resize, `<service>:delete` for an idle resource's delete, `<service>:create` on the type for an import or an assignment); the demos read the token's managed roles. A person is always the actor of what they do; only an allowed console service may name someone else |
| 17 | Finding an object is no claim on it | Discovery sees the whole region, so an import is for the account an object was handed to, else its guessed owner; an object nobody is guessed for waits for an account admin or for Assign to team |
| 18 | Scorecards in the drift service, fed by its reads | Every provider read and every inventory change already passes through here; scoring them where they land costs no extra read and keeps a resource's score as fresh as its drift |
| 19 | A policy-engine port with OPA and an embedded engine, the packs carrying both | The platform's policy check is a mock today; the embedded engine runs the same rules until the firm's OPA is wired in, and the examples keep the two in agreement |
| 20 | One document per resource, a failures collection, counters by delta and a daily recount | A scorecard reads counters instead of 1M evaluations; a rule's failing list pages on an index; a missed delta lives one day at most |
| 21 | A fix is never automatic and always in a person's name | As revert (decision 4): `remediation.needsPerson` without one; tags through the state service as the person, workflows through the platform's delegation |

### Contract additions (raised with the hub)

Hub PR #14 (`console-root--drift-contract`) writes these into the drift contract; all are backward compatible:
`revertStatus` on a finding; the `X-On-Behalf-Of` header relayed on `:revert`; the 409 codes
`revert.needsPerson`, `finding.reverting`, `finding.notRevertable` and `finding.unwatched`; the job
label `drift-finding` that ties a revert job to its finding; the `/internal/*` operations; and on
an import of a discovered object, `registryId` set to the provider id, since the state contract
requires one and a discovered object has no registry record.

Hub PR #17 (`console-root--drift-finder-monitoring`, stacked on PR #14) adds the finder's
`ownerGuess`, `monthlyCost`, `assignment` and `owner` filter, `POST /v1/unmanaged:assign`,
`GET /v1/findings/stats`, the monitoring and rightsizing operations, and the BFF routes over them.

Hub PR #19 (`console-root--scorecards-contract`, stacked on #17, the commit `contracts.lock` pins)
adds the scorecards: the `RulePack` schema and the three packs, the `/v1/scorecards/*` and
`/internal/scorecards:*` paths, the `drift.scorecards` channel with `EvaluationChanged` and
`ScoreSnapshotted`, the job label `scorecard-rule`, and the BFF's `/console/v1/scorecards*` routes.

### The demo's world

The fake platform answers single reads (`vm.get`, `cluster.get`) with a fixed sample and pages
with its recorded world. At start the demo writes each recorded object's attributes into the fake's
world (`DemoWorld`), so every read agrees with the registry and only real changes show as drift.
It also gives the three VMs vSphere has that no account manages (`vm-1042`, `vm-1043`, `vm-2001`)
their CPUs and memory, so the finder prices them from the demo price list; since every VM the demo
manages is the compute account's, that account is their `low` owner guess. The demo's accounts are
the identity service's demo pair, `123456789012` Compute (team-compute) and `210987654321` Data
(team-data).

## CI and release

`.github/workflows/ci.yml` builds `console-libs` into the local Maven repository, then builds and
tests against MongoDB 7 (the Mongo adapter tests skip without `CONSOLE_TEST_MONGO_URI`), scans files
and new commits for secrets with gitleaks, and lints and renders the Helm chart. The image
(`Dockerfile`) is the boot jar on Temurin 21 as a non-root user; publishing it and the chart waits
for the registry decision on the hub.
