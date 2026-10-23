# console-search-indexer

The customer console's search (work package 6.1, and the search side of 9.1, 9.4 and 9.5). It
follows the console's own events on Kafka (`state.resources`, `drift.findings`), keeps one
OpenSearch document per resource, and answers the search, type-ahead and list counts the BFF uses
for the header's global search and the filter bar over large lists, the copilot's reading of plain
English as a list's filter, and the counts by type, tag or account behind the scorecards and the
executive home, plus the index's status and rebuilds.

This repository is one branch (`console-search-indexer`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock` (hub `b0a0d52`), and the shared code comes from
`console-libs` as `com.iaf.console:*`.

## Run it

Java 21 is all the demo needs. Start the state service's demo first (branch
`console-state-service`, `scripts/demo.sh`: the state service on 18082 and its fake platform on
18090), then:

```sh
scripts/demo.sh            # this service on 18085, following the state service every 2 s
scripts/demo.sh journey    # search, page, suggest, count, status, a rebuild
```

The demo keeps the index in memory. To use OpenSearch instead, run one and name it:

```sh
scripts/opensearch.sh      # OpenSearch 2.19 on 127.0.0.1:9200 without Docker (not as root)
CONSOLE_SEARCH_ENGINE=opensearch CONSOLE_OPENSEARCH_URL=http://localhost:9200 scripts/demo.sh
```

With Docker, `deploy/compose/docker-compose.yaml` starts OpenSearch, Kafka and MongoDB, the same
images CI uses. In a cluster, `deploy/helm/console-search-indexer` runs the `mongo` and `kafka`
profiles; `Dockerfile` builds the image from `build/libs/console-search-indexer.jar`.

## The API

The hub's `contracts/openapi/console-search-indexer.yaml` 1.2.0, pinned under
`src/test/resources/contracts/`; `contracts/additions.md` names anything served beyond it (nothing now).

| Call | What it answers |
|---|---|
| `GET /v1/search?q=…` | One page of hits: `q` is free text plus `type:`, `state:`, `region:`, `tag:k=v`, `drift:`, `created:` and `updated:` terms, wfrn patterns and quoted phrases; `type`, `region`, `state`, `tag.<name>`, `drift` parameters narrow further; `sort` is `score` (default), `name`, `-name`, `created`, `-created`, `updated` or `-updated`; `limit` 1 to 200 (50), `after` the cursor of the last page; `didYouMean` when a typo was forgiven |
| `GET /v1/suggest?q=…` | The type-ahead: resources by name, wfrns by id, types and tags, at most `limit` (8). Resources are found at once; the types and the account's 1,000 most used tag values, with their counts, are counted at most every 30 seconds on OpenSearch |
| `GET /v1/counts` | A list's numbers: `total`, `byState` (the chips), `drift`, `cappedAt` (null: exact); with `groupBy` (`type`, `service`, `state`, `region`, `account` or `tag:<name>`) the same numbers per key in `groups`, the largest 100; the console's own services may repeat `accountId` for up to 200 accounts and get them added up |
| `GET /v1/search:interpret?q=…` | Plain English read as a list's filter: the type, the list's own filters (`filter.q`, `state`, `drift`, `region`, `tags`), the sort, the whole reading as one query (`q`), a sentence to show the person, how sure it is (`high`, `medium`, `low`), the words it left out (`unparsed`) and how many resources it lists (`total`); `type` keeps the reading in the list the person is on |
| `GET /v1/index/status` | The caller's account's documents (the whole index for the console's own services), the newest event and how long ago it was, whether a rebuild runs |
| `POST /v1/index:rebuild` | The console's own services: refill the index (one account or all) from the state service, idempotent on `Idempotency-Key`; 409 while one runs |
| `GET /v1/index/rebuilds/{runId}` | A rebuild's progress |
| `GET /internal/sources` | Where the events come from (Kafka or the poller) and how they flow |

**Who calls.** The BFF's internal JWT (ES256 from its JWKS, issuer `console-bff`, audience
`console-search-indexer` and nothing else, hub decision 35): a person's token (`acct`) reads its own
account only, and another account's id answers 404, never 403. A console service's token (`svc`, no
`acct`) is let in only from the services `console.auth.services` lists (`console-bff`, which names
the account with `accountId` anywhere) or `console.auth.counting-services` lists
(`console-drift-service`, `console-cost-service`, which may only count across accounts with
`/v1/counts`); any other, and a token with neither `acct` nor `svc`, is a 401. The demo profile also
takes the dev headers `X-Console-Account`, `X-Console-User`, `X-Console-Roles`, `X-Console-Region`
and `X-Console-Service`, under the same rules. When the BFF's keys cannot be read, a token gets 503
with `Retry-After`, not 401.

**Budget.** Each account may run 30 queries a second with bursts of 60 on each replica
(`console.search.budget.*`), 90 a second on the chart's three; beyond it, 429 with `Retry-After`.
A count over several accounts spends one query of each account's budget. The load test sized it:
at the target, the largest account's people ask about 20 a second, so even two replicas leave it
three times that (see Load below).

**Times.** `created:` and `updated:` take a day (`2026-10-01`, the whole UTC day), a time with its
offset (`2026-10-01T12:00Z`, only compared) or an age (`30m`, `24h`, `7d`, `2w`), compared with `>`,
`>=`, `<`, `<=` or as a range `a..b` that keeps both ends. An age alone means since then
(`created:7d`), and two terms on the same field narrow together.

**Typos.** A name or tag word four letters or longer still matches when it is one edit off (two
from seven letters), the first letter typed right, as OpenSearch's `fuzziness: AUTO:4,7` counts;
a forgiven match weighs less than an exact one. On the first page by relevance, `didYouMean` is
the query with each forgiven word replaced by the word the hits show, read only from the page's
own highlights, so it never names another account's words.

**Plain English.** The reader is deterministic: it knows the account's types (by name, plural or
title, and one typo away), states by their everyday names ("broken", "running", "being deleted"),
drift ("changed outside the console", "in sync"), tags (`env=prod`, "tagged team web", "team
payments" when the account has that tag), regions, times ("created this week", "older than 90
days", "not updated in 30 days", "between oct 1 and oct 7") and sorts ("newest first", "recently
updated"). Any other word is kept as free text while the list still shows something; when such
words empty a list that would show something without them, they are left out. A union ("vms or databases"), a negation ("not failed") or a second
value of the same filter is left out and said in `unparsed`, which makes the reading `low`, the
copilot's cue to ask its model. `total` is counted the way the console applies the answer, with
`filter.q` in the search box and the rest as the list's filters.

## How the index is kept

- **One document per resource**, its id the wfrn, routed by account (an account lives on one shard,
  so its counts and sorts are exact), written with the resource's version as an external version
  (`external_gte`): a stale write is a no-op, and a finding may rewrite the document at the version
  it holds.
- **The events fold in order.** An event at the version held plus one applies; an older one is
  ignored; a gap reads the resource again from the state service. `Deleted` always applies, and the
  document becomes a tombstone for 90 days: hidden from lists and suggestions, found by
  `state:deleted` or its exact wfrn. A resource moved to another account (`Transferred`, hub
  decision 30) always applies too: its old wfrn becomes a tombstone with no end, as the state
  service keeps a moved resource's name for good, and the new wfrn arrives as an ordinary
  `Imported` in the other account. A finding opened, updated or closed marks the document's drift;
  a finding on a resource not indexed yet reads the resource from the state service. Every event is
  applied once (the service kit's `IdempotentConsumer`, keyed on the envelope's `key`).
- **Never indexed.** A field the type marks sensitive, a field named like a secret, a value masked
  as `***`. Only the fields the type marks searchable are indexed at all. The types come from the
  platform (`GET /api/v1/resource-types`, cached for a minute).
- **The events' sources.** Under the `kafka` profile a reactor-kafka consumer reads both topics
  (group `console-search-indexer`); a record that is not an envelope is logged and skipped, never
  blocking the partition. Without Kafka (the demo, a laptop) the service polls the state service's
  lists every interval, per account, from a watermark. A rebuild walks the state service's lists
  (live, then tombstones) in batches of 200 and then removes what it did not see; under `kafka`, an
  empty index at startup (a new cluster, a lost index) is rebuilt on its own.
- **OpenSearch.** The service reads and writes the alias `console-resources`, whose write index is
  `console-resources-v1`, created with `opensearch/console-resources.json` when the alias is missing;
  a new mapping is a new index, a rebuild into it and an alias swap. Every write sends
  `require_alias`, so a deleted index is never recreated with a guessed mapping. The mapping is
  strict: names get exact, word and prefix (edge n-gram) fields and a lowercase sort key, wfrns a
  keyword and a `wildcard` field for patterns, tags a keyword list for filters and the searchable
  values one `text` field; the tags and fields themselves are stored, not indexed. Highlights come
  back HTML-escaped around the `<em>`.

## Settings

Everything is under `console.*` with defaults that run the demo (`application.yaml`,
`application-demo.yaml`, `application-mongo.yaml`). The usual ones:

| Variable | Setting | Default |
|---|---|---|
| `CONSOLE_SEARCH_ENGINE` | `console.search.engine` | `opensearch` (`memory` in the demo) |
| `CONSOLE_OPENSEARCH_URL`, `_USERNAME`, `_PASSWORD` | `console.search.opensearch.*` | `http://localhost:9200` |
| `CONSOLE_KAFKA_BOOTSTRAP` | `console.ingest.kafka.bootstrap-servers` | `localhost:9092` |
| `CONSOLE_MONGO_URI` | `spring.data.mongodb.uri` (profile `mongo`: rebuild runs, checkpoints) | `mongodb://localhost:27017` |
| `CONSOLE_STATE_URL` | `console.state.url` | `http://localhost:18082` |
| `CONSOLE_AUTH_TOKEN_URL` | `console.auth.token-url`, where the BFF issues this service its tokens | `http://localhost:18081/internal/v1/token` |
| `CONSOLE_AUTH_CLIENT_ASSERTION_FILE` or `CONSOLE_AUTH_CLIENT_KEY` | how this service proves itself to the BFF: the projected service-account token's file (a cluster), or its own private EC JWK with a `kid`, registered with the BFF (anywhere else) | none (the demo uses dev headers) |
| `CONSOLE_IDENTITY_URL` | `console.identity.url`, the accounts a full rebuild walks | empty: `console.rebuild.accounts` |
| `CONSOLE_PLATFORM_URL`, `CONSOLE_PLATFORM_TOKEN` | `console.platform.*`, the resource types | `http://localhost:18090` |

### Calling the state and identity services

The service reads the state service (whole resources, rebuilds) and the identity service (the
accounts a full rebuild walks) as itself, with service tokens the BFF issues it (hub decision 23,
`issueServiceToken` in `contracts/openapi/console-bff.yaml`). It asks `POST /internal/v1/token`
with OAuth 2.0 client credentials and a JWT client assertion, one token per callee
(`audience=console-state-service`, `audience=console-identity-service`), keeps each token for 80%
of its five minutes, and asks once more when a callee answers 401. A request to the BFF is given
up on after `console.auth.token-timeout` (5 s) and tried twice more; while the BFF cannot answer,
the current token serves until shortly before it ends. The assertion is the pod's
projected service-account token (audience `console-bff`, the chart mounts it and the BFF knows
`system:serviceaccount:<namespace>:console-search-indexer`) or, outside a cluster, a one-minute JWT
signed with `CONSOLE_AUTH_CLIENT_KEY`, whose public half the BFF registers for the client
(`--console.trust.clients.console-search-indexer.jwks=…` on the BFF). No secret holds a token, and
the BFF's signing key never leaves it.

## Operate it

- **Health** at `/actuator/health` (liveness and readiness probes, OpenSearch included), metrics
  at `/actuator/prometheus`, in the cluster on their own port (18185, `service.managementPort`),
  which the chart's NetworkPolicy (on by default) opens to Prometheus while the API's port stays
  for the console's own pods: `console_search_documents`, `console_search_lag_seconds`,
  `console_search_events_total{type,outcome}`, `console_search_queries_total{kind,outcome}`,
  `console_search_duration{what}`, `console_search_rebuilds_total{outcome}`,
  `console_search_refetches_total`.
- **Rebuild** one account or all: `POST /v1/index:rebuild` with `{"accountId": "…"}` or `{}` as a
  console service; follow it at `/v1/index/rebuilds/{runId}`.
- **Housekeeping** removes tombstones past their 90 days every hour.
- **Timeouts**: a callee silent for its timeout is given up on, so none holds up a poll for good:
  `console.search.opensearch.timeout` (10 s; a delete by query, answered only once it is done, has
  10 minutes), `console.state.timeout` and `console.identity.timeout` (30 s), and the BFF's token
  endpoint above.

## Load

The search side of WP 6.2: the plan's targets are a search's p95 under 500 ms and a list's under
300 ms over 10 million resources, a million of them in one account, with 1,000 people at the
console at once. The test lives in `src/gatling` and needs no other service:

- **`Estate`** is a synthetic estate the same every time for the same `load.size`,
  `load.accounts` and `load.seed`: resource `i` is a pure function of them, so the seeder writes
  it and the simulation finds it again with nothing passed between them. The largest account holds
  `load.largestShare` of it (a tenth by default: a million of ten million) and the others share
  the rest by a Zipf law. Five types (virtual machines, volumes, databases, images,
  environments), names like `payments-api-7k2qd`, the tags `env`, `team`, `cost-centre` and a
  unique `cmdb`, the states in a live estate's proportions, 3% drifted.
- **`SeedEstate`** (`./gradlew seedEstate`) writes it through the service's own OpenSearch adapter,
  so the documents, routing and versions are the ones the service writes, with refreshes and
  replicas off while it loads. `-Pload.mode=trickle` then keeps changing it at `load.trickle`
  writes a second, so the searches meet refreshes.
- **`SearchSimulation`** (`./gradlew gatlingRun`) is the people: each opens lists (the counts for
  the header and chips; the rows come from the state service), types in the header's search (a
  suggestion per keystroke, then the search), uses the filter bar (a page newest first, sometimes
  the next), asks the copilot, looks a resource up by its CMDB tag or its wfrn, or opens the
  executive home (the BFF counting 20 accounts at once), thinking 5 to 15 seconds between actions.
  One person in ten works in the largest account; the others pick an account by the square root of
  its size. Every name, tag and wfrn lookup checks that the resource is the first hit, gate 6's
  "search finds any resource by name, tag or wfrn". The assertions are the targets: p95 under
  500 ms for searches, 300 ms for a list's counts and each suggestion, 1 s for the executive
  home's 20 accounts, and under 1% failed, a 429 included.

```sh
# the estate: ten million resources in 2,000 accounts, the largest a million
./gradlew seedEstate -Pload.size=10000000 -Pload.accounts=2000 -Pload.opensearch=http://opensearch:9200
# the service, ingestion off so the estate stays as seeded, dev headers on (a load environment only)
java -jar build/libs/console-search-indexer.jar --console.ingest.enabled=false --console.auth.dev-headers=true
# 1,000 people for 15 minutes, while 10 writes a second change the estate
./gradlew seedEstate -Pload.size=10000000 -Pload.accounts=2000 -Pload.mode=trickle -Pload.trickle=10 -Pload.trickleFor=PT20M &
./gradlew gatlingRun -Pload.size=10000000 -Pload.accounts=2000 -Pload.users=1000 -Pload.ramp=PT2M -Pload.duration=PT15M \
  -Pload.baseUrl=http://console-search-indexer:18085
```

The report is in `build/reports/gatling/`; `deploy/grafana/console-search-indexer.json` is the
dashboard for the same run from the service's side (p95 by kind of query and at the HTTP edge,
the share within the targets, queries by outcome with the 429s, the index's lag). Every
`load.*` property and its default is at the top of `Estate.fromProperties()` and the simulation.
CI's **Load smoke** job runs the same harness on 100,000 resources with 30 people for a minute.

**What it found** (2026-10-23, one 4-vCPU machine running OpenSearch 2.19 with a 4 GB heap, one
replica of the service and Gatling together; 2 million resources in 200 accounts, the largest a
million, so 17% of the people work in it; 1,000 people for 5 minutes after a 2-minute ramp):

| Run | Requests | Failed | p95 overall | Suggest p95 | Search by name p95 | List counts p95 | Copilot p95 | OpenSearch CPU |
|---|---|---|---|---|---|---|---|---|
| 1. Budget 20 a second, one replica | 84,841 | 6.9%, all 429 | 141 ms | 182 ms | 69 ms | 44 ms | 118 ms | about 370% |
| 2. Budget as three replicas give it | 74,458 | 0 | 1,905 ms | 1,764 ms | 1,642 ms | 1,718 ms | 4,924 ms | saturated |
| 3. Suggestions' counts kept 30 s | 85,071 | 0 | 16 ms | 12 ms | 22 ms | 5 ms | 24 ms | about 110% |

Every name, tag and wfrn lookup found its resource first in every run. Two changes came out of it:

- **Suggestions no longer count the account on every keystroke.** Suggestions are more than half of
  all requests, and each one aggregated the types and tags of every live document of the account:
  on the million-resource account that was most of the cluster's work, and run 2, with nothing
  turned away, saturated it (a few hub checks shared the machine during run 2, but not its
  OpenSearch). The types and the 1,000 most used tag values are now counted once per
  account (and type) every 30 seconds; the resources themselves are still found at once.
- **The budget is 30 queries a second per replica, bursts of 60.** A person asks about one query
  every 5 seconds, four a second while typing, so the largest account's people ask about 20 a
  second at the target (11% of 1,000 people) and 30 in these runs (17%), more than one replica's
  old 20; the chart's three replicas now give an account
  90 a second and the two the disruption budget keeps, 60. Run 1's 429s were all that account.

This is one machine standing in for a cluster, so it shows the queries' cost and the design's
headroom, not the production numbers: those come from the same run against the load environment at
10 million resources, with the budget as the deployment sets it and the trickle on. Run locally,
`-Pload.size=2000000 -Pload.accounts=200 -Pload.largestShare=0.5` builds the same estate, and the
service needs `--console.search.budget.per-second=90 --console.search.budget.burst=180` to stand for
three replicas.

## Build and test

```sh
./gradlew build
```

The unit, application and in-memory API tests run anywhere. The adapter tests run against real
servers when they are named, and skip otherwise; CI starts all three as service containers:

```sh
CONSOLE_TEST_OPENSEARCH_URL=http://localhost:9200 CONSOLE_TEST_KAFKA_BOOTSTRAP=localhost:9092 \
CONSOLE_TEST_MONGO_URI=mongodb://localhost:27017 ./gradlew build
```

`SearchIndexContractTest` holds the in-memory twin and OpenSearch to the same behaviour (versions,
scope, filters, scoring order, the seven sorts and their cursors, tombstones, counts and their
groups, times, typos, suggestions, highlights, the account's vocabulary, sweeps); `PlainEnglishTest`
pins how each kind of word is read; `SearchJourneyTest` runs the API as the BFF meets it on both
engines, against a stub state service and the fake platform; `RoutesMatchContractTest` holds the handlers to the pinned
contract and `contracts/additions.md`; `ArchitectureTest` holds the slices to the hexagonal rules.

## Layout

`com.iaf.console.search.index` is the slice: `domain` (documents, the event folds, the query
language, typo tolerance, the plain-English reader, pure Java), `ports.outbound` (the index, the state service, the types, the accounts, the
stores), `application` (indexing, the poller, rebuilds, queries, housekeeping) and `adapters`
(`opensearch`, `memory`, `kafka`, `state`, `platform`, `identity`, `mongo`, `web`, and `tokens`,
the BFF's service tokens for the state and identity calls). `com.iaf.console.search.auth` checks
the callers; `com.iaf.console.search.boot` wires it all. `src/gatling` is the load test (Load,
above).
