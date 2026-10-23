# console-drift-service (drift, finder, monitoring, rightsizing, scorecards)

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-drift-service


# console-drift-service (thread "Drift service", 2026-10-23)

- Branch `console-drift-service--phase-5` → PR #12 into orphan `console-drift-service`. Contracts are pinned in `contracts.lock` to 59487a2, the head of hub PR #14 (`console-root--drift-contract`, stacked on `console-root--phase-0`), which holds the drift contract's additions. Re-pin when #14 merges or the hub moves. Shared code is `com.iaf.console:*` 0.1.0-SNAPSHOT from mavenLocal (see [[console-ci-libs-publish]]).
- Port 18084. Demo: `scripts/demo.sh`, then `scripts/demo.sh journey` (fake platform in the JVM on 18090, state stand-in). Production profiles: `mongo,kafka,temporal`. Chart: `deploy/helm/console-drift-service`.
- Design: a watch list of every live resource, synced from the state service and `state.resources`. There are 64 shards per region, with one loop per shard and tier (Temporal ids `console-drift-<region>-scan-<shard>-<tier>`). Reads use paged `:observe-list` per type, region and environment, batches below 20 due, and `:discover` pages for imported resources. Tiers: hot 15m, warm 1h (or the type's interval), cold 24h.
- Findings: one open per resource. Events go on `drift.findings` in the P-01 envelope, key `<type>:<wfrn>:<findingId>:<version>`. A revert is never automatic. It runs on behalf of a person (the BFF's `X-On-Behalf-Of`, else 409 `revert.needsPerson`) and closes only when the next read agrees.
- Concurrency: finding versions never go back, and watches carry `rev` with compare-and-set saves. Without the revisions, a stale scan reopened a finding right after an accept.
- Hub decision 23 is built in: service tokens come from the BFF's `/internal/v1/token`, for audiences console-state-service and console-identity-service. The assertion is the projected SA token of `<ns>:console-drift-service`, or a signed EC key. There are no static tokens. The demo uses `console.service-tokens.mode: dev-headers`.
- Gotchas:
  - The fake platform's single reads (`vm.get`, `cluster.get`) answer fixed samples, so the demo aligns the world at start (`DemoWorld`).
  - gitleaks flags literal PEM armor in code. Build PEM strings from parts in tests.
  - A `.timeout()` in a WebClient filter only bounds the wait for headers; a body that stalls needs Reactor Netty's `responseTimeout` on the connector (fixed in 88fa1eb, with a JWKS fetch timeout and a 14-minute loop tick limit).
  - `get.helm.sh` is blocked here. Build Helm with `go install helm.sh/helm/v3/cmd/helm@v3.16.3`.
- Contract additions are hub PR #14 (opened by this thread on 2026-10-23 because the hub thread ran out of credits): revertStatus, the new 409 codes, the `drift-finding` job label, `/internal/*` with Watch/ScanReport/SyncReport, and registryId=providerId on a discover import. Each service thread now opens its own small hub PR (indexer #11, state #15).
- Docker in this container can die between turns: `rm -f /var/run/docker.pid; nohup dockerd &`, then `docker start drift-mongo`.

**Why:** the BFF and web drift screens build on this API. Later threads need the rules without re-reading the code.
**How to apply:** code against PR #12's README. Wider status is in [[customer-console-build]]; the finder, stats, monitoring and rightsizing are in [[console-drift-finder]].

---

## Note: console-drift-finder


# Drift finder, numbers, monitoring, rightsizing (thread "Drift service and drift UI", 2026-10-23)

- Hub PR #17 `console-root--drift-finder-monitoring`, stacked on #14. Merge order: #14 is turn 8 and #17 turn 9 (hub steward's MERGE-ORDER.md on `console-root--stack`, draft PR #30). Head b7aae5f holds the drift contract additions (unmanaged `ownerGuess`, `monthlyCost`, `assignment`, `owner` filter, `POST /v1/unmanaged:assign`, `GET /v1/findings/stats`, metrics, alarms, recommendations) and the BFF routes. Scorecards #19 restacks onto #17, so keep #17's description, tags, paths and schemas in place.
- Drift service PR #32: `console-drift-service--finder-and-monitoring` into `console-drift-service--phase-5` (PR #12), with monitoring and rightsizing (WP 9.21, 9.22) merged in; 156 tests, CI green 2026-10-23 18:25 UTC. A second discover while one runs is 409 `discover.running` with `detail.runId`; a run still running after 2 h is marked failed as abandoned.
- BFF: PR #42 `console-bff--drift`, stacked on `console-bff--phase-4` (#26), CI green. Drift problem fields are turned into JSON pointers (`/accountId`). Slice `com.iaf.console.bff.drift` (DriftPort, HttpDriftPort). Settings: `console.drift.url` (empty means the demo stand-in `/demo-drift`), `service-name`, `timeout`. The executive home (BFF thread, `console-bff--phase-9`) maps stats: found = opened, fixed = closed.total, open, meanTimeToFixMs = meanMinutesToClose × 60000.
- Owner guess rules, in order:
  - high: an owner, team or account tag (top level or in tags/labels) naming a team, id or name.
  - medium: the account or team name as whole words in the object's name (3 or more characters).
  - low: a place (folder, network, cluster, pool and similar) shared with exactly one account's managed resources; else the only account managing the type in the region.
  - A step that names two accounts falls through to the next.
  - Guesses read only what the type's observe.map gives. The fake's vm map has only cpu, memoryGb and powerState, so the demo shows only the type-wide low guess.
- Assign makes a second copy in the target account. An import by either account deletes every copy, and a run skips objects any account watches.
- Costs come from the cost service's `POST /v1/cost:estimate` (audience console-cost-service, so the BFF's console-drift-service client must list it), or from `console.cost.prices` when `console.cost.base-url` is empty. Demo vm: 5 + 14.60 per CPU + 3.65 per GiB.
- Cross-service journey script: /mnt/project-files/console-drift/journey-bff-drift.sh. Run the drift demo with `--console.demo.platform-port=18091 --console.auth.dev-headers=false --console.auth.jwks-uri=http://localhost:18081/.well-known/jwks.json` beside the BFF demo with `--console.drift.url=http://localhost:18084`. The two demos mint different wfrns, so use the drift fake's (18091) wfrns for metrics and alarms.
- Gotchas:
  - Never copy a new jar over one a running JVM uses: its event-loop threads die (NoClassDefFoundError) and requests hang.
  - The journey tests share one demo context, so each demo VM field belongs to one test (cache-01 cpu is the stats test's; FindingApiTest uses cache-01 memoryGb).
  - gitleaks flags `header("Idempotency-Key", "<literal>")`; use a variable.
  - Publishing console-libs needs the plugin portal: the init script needs `gradlePluginPortal()` in pluginManagement, because the mirror-only one fails on kotlin-dsl.

**Why:** later threads (web drift UI, executive home, merge order) need these names and rules without re-reading the code.
**How to apply:** code against PR #32's README and hub #17. The base service is in [[console-drift-service]].

---

## Note: console-scorecards


Built 2026-10-23 for Gonzalo's "build the entire system" mandate.

- Hub PR #19 `console-root--scorecards-contract` → `console-root--drift-contract` (head 02b5030): `kind: RulePack` (contracts/schemas/rule-pack.schema.json, JS reference contracts/rule-packs/reference.mjs), packs in `rule-packs/` (required-tags, compute-baseline, data-baseline; 30 examples), `npm run check` runs examples through the reference AND OPA v1.4.2 (CI installs, sha256 2c0ccdbb…). Hub decision 26. Drift yaml `/v1/scorecards/*` + `/internal/scorecards:{rescore,snapshot}`; asyncapi `drift.scorecards` (EvaluationChanged, ScoreSnapshotted, partition accountId); BFF `/console/v1/scorecards*` + `/console/v1/r/{type}/{wfrn}/compliance`, IAM `scorecards:list`, `scorecards:remediate`.
- Drift PR #21 `console-drift-service--scorecards` → `console-drift-service--phase-5`: slice `com.iaf.console.drift.scorecards`; `ResourceListener` hook in Reconciler (after each read) and InventorySync (change/remove); PolicyEnginePort `embedded` (default) / `opa` (`console.scorecards.engine`, pushes Rego via Policy API); Mongo collections scorecard_evaluations (CAS on version), scorecard_failures, scorecard_counters ($inc deltas, daily recount), scorecard_snapshots (TTL 400d). Remediation never automatic, person required (tags via state `:tag`, workflow via platform on-behalf-of, job label `scorecard-rule`).
- Score = round(100·Σw·passed/Σw·total), critical 10 / warning 3 / info 1; null if nothing scored.
- OPA Rego gotcha: `not f(input.x)` is false when input.x is undefined; packs use helper rules.

**Why:** WP 9.6 of the plan's phase 9; the drift service already reads every resource, so scoring lives there.
**How to apply:** new rules go in hub `rule-packs/` with examples (both halves must agree), then re-copy into the drift service and re-pin contracts.lock.

