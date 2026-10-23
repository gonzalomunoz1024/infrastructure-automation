# console-state-service

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-state-service


# console-state-service (thread "State service", 2026-10-23)

- Branch `console-state-service--phase-2` → PR #7 into `console-state-service`; `contracts.lock`
  pins hub c6c22ab, the head of hub PR #15 (`console-root--state-retry` → `console-root--phase-0`);
  `com.iaf.console:*` 0.1.0-SNAPSHOT from mavenLocal (see [[console-ci-libs-publish]]).
- Port 18082; demo profile embeds platform-fake on 18090; dev headers `X-Console-Account`,
  `-User`, `-Service`, `-Region`, `-Groups`, `-Roles`. Production profiles `mongo,kafka`; Helm chart
  `deploy/helm/console-state-service`.
- Design: log `resource_events` keyed `<Type>:<wfrn>:<version>` + projection `resources`; no-op
  commands write no event; tombstone 410 for 90d; pages 1–200, default 50.
- Ingestion: the feed says that, the registry says what; unknown registration ⇒ Imported; retired ⇒
  Deleted; registration during a delete is no event (hub decision 20). A change and its job in either
  order: a request naming an ended `jobId` settles at once (a delete whose job already deleted it
  answers the tombstone, 200); job.started on an idle resource is no event. Reaper: pending >5m,
  updating/deleting >2h by their own job. `/internal/import` needs `owner` (P-05).
- Retrying a failed create: [[console-retry-flow]].
- Sensitive data: per-account DEK under `CONSOLE_MASTER_KEY`, masked `***`; `?reveal=sensitive` only
  for internal principals (svc, no acct) and JWT `roles` holding `account-admin` (hub decision 21).
- Events: Kafka `state.resources`, P-01 envelope (aggregate `resource`, aggregateId wfrn); the
  `/events` endpoint returns the same envelopes and ids. A succeeded delete job is `Deleted` alone.
- Auth: BFF ES256 JWT via JWKS `http://localhost:18081/.well-known/jwks.json` (iss console-bff).
- Phase 9 (2026-10-23): branch `console-state-service--phase-9` → PR #33 into phase-2, pinned to hub
  1022c5d (hub PR #27, decisions 29 and 30). Reads: lineage, blast radius (GET, and POST for a bulk
  action), `/v1/graph`, `/v1/activity` (+ digests, keys, evidence for account-admin, auditor or
  internal), `events:verify`, `revert-plan`, `at` on a resource and the list. `builtFrom` = registry
  ids, resolved when read; other accounts are counted, never named. Trail: per-resource hash chain,
  hourly ES256 digests per account and region; key `CONSOLE_TRAIL_SIGNING_KEY` (optional, else per
  pod, public halves kept). Closure: AccountChanged `closed` shreds the DEK. Moves:
  [[console-transfer]]. Store contract tests run the same checks on memory and MongoDB (CI).
- Security (61ef5d1 on phase-9, 2026-10-23, hub decision 35): aud exactly console-state-service;
  no-acct tokens only from `console.auth.internal-services` (bff, indexer, drift, cost) with sub=svc;
  :import / record-observation / record-drift / resolve-drift internal only (403); body actor only
  from internal callers; deep sealing (nested JSON pointers); shred marker, key cache 60 s.
- Blueprints (WP 9.7, decision 33): PR #45 on top of #33: [[console-blueprints]].

**Why:** later threads (BFF, indexer, drift) need the ports, topic and rules without re-reading the
code. **How to apply:** code against PR #7's README; wider status in [[customer-console-build]].

---

## Note: console-transfer


# Moving a resource to another account (agreed 2026-10-23)

- Agreed by the identity, state and BFF threads; built in state PR #33, contract on hub PR #27
  (decision 30), platform side planned as P-23. A resource moves only within one organization;
  accounts never change organization and ids are never reused.
- State service `POST /v1/wfrn/{wfrn}:transfer {toAccountId, reason}`: writes `Transferred` on the
  old wfrn first (tombstone with `transferredTo`, no `tombstoneUntil`, never expires), then
  `Imported` (`source: transfer`, `transferredFrom`) for a new wfrn in the other account: same
  registryId, attributes sealed again under the new account's key, tags, builtFrom. The new
  `createdAt` is the move's instant. Idempotent; the reaper finishes a move cut short (last hour,
  once a minute old). Only an idle, active resource moves.
- Codes: old wfrn 410 `resource.transferred` with `transferredTo`; 409 `resource.busy`,
  `resource.notActive`, `account.shredded`; 400 for the same account.
- BFF: authorize both ends with identity `POST /v1/authorize` (`<service>:delete` on the source,
  `<service>:create` on `wfrn:<p>:<service>:<region>:<toAccount>:<type>/*`; no new verb), call
  `:transfer`, then platform `POST /api/v1/resources/{registryId}:rehome {from, wfrn, accountId}`
  (P-23, `rehomeResource`, scope `resources:rehome` in the console preset; `from` = the old wfrn,
  so a record can move again; 409 `resource.rehomed` when it moved since, `wfrn.taken`). Whole account:
  `POST /internal/accounts/{accountId}:transfer` with the service token; re-home each `moved`
  pair, repeat while `more`.
- Until the platform re-homes, the state service applies feed events naming the old wfrn to the
  new one (up to 8 hops). The platform fake and client have `rehomeResource(from, wfrn, accountId)`
  (console-libs--phase-0 6861ed5, pinned to hub e790a27): at wfrn 200 no change, at from moves and
  adds from to previousWfrns, at neither 409 `resource.rehomed`.
- Consumers: the indexer treats `Transferred` as a tombstone; drift closes old findings as vanished
  and watches the new wfrn; cost ends accrual at `Transferred` and starts it at the `Imported`,
  which is not a creation.

**Why:** the move spans four services and a platform change; later threads must not re-derive it.
**How to apply:** follow these steps and codes. Rationale: hub README rows 29 and 30, and
platform-changes/README.md P-23. Service details: [[console-state-service]].

---

## Note: console-blueprints


Built 2026-10-23. Hub PR #39 (console-root--state-blueprints, a281cd3): `kind: Blueprint` schema,
`blueprints/web-app.yaml` + `dev-sandbox.yaml`, check-contracts section 8, state OpenAPI 1.2.0
(10 routes), console-events 1.1.0 (`state.stacks`). State PR #45 (console-state-service--blueprints,
stacked on #33): package `stack`, Mongo `stacks` (partial unique liveName) + `stack_events`.

Rules: state decides, BFF executes each `ready[]` once per idempotencyKey
`stack-<ulid>:<step>:<action>:<attempt>`; create = mint with `stack {wfrn, step, attempt}` (the
claim; 409 stack.stepNotReady = drop); delete = type's delete workflow + :request-delete;
cannot start = `steps/{step}:report-failure`. Child changes advance the stack; sweeper re-announces
after 5 min, fails a never-recorded child after 10 min. No rollback; teardown reverse, retain by
`deletionPolicy` or `retain` list; failed teardown stays `deleting` with `failure`. Only `vm` has a
delete workflow in the pinned catalog. Identity actions blueprints:get/list/launch/teardown/resume
(identity PR #24; teardown admin only). Settings `console.stacks.*`.

**Why:** BFF executor and web pages are built against this. **How to apply:** see PR #45 README
"Blueprints and stacks"; [[console-state-service]], [[customer-console-bff]].

---

## Note: console-retry-flow


Hub decision 24 (hub PR #15, state service 51c4b42): a failed resource is retried by an explicit
command, never by a job's start.

1. The BFF reruns the failed job: `POST /api/v1/jobs/{id}:rerun` with `fromStep` =
   `lastError.failedStep` (the resource's `lastError.jobId` is the job to rerun). The rerun is a new
   job that keeps the wfrn context and merges any new input over the old, so the BFF never needs the
   original input (it only sees secrets masked).
2. Then it records `POST /v1/wfrn/{wfrn}:request-retry` at the state service with `jobId` = the rerun
   (body optional; `workflow` defaults to the create workflow). `RetryRequested`: failed → pending;
   409 `resource.notFailed` in any other state.

Rerun first (as for updates and deletes) so a refused rerun records nothing. Recording first also
works; then the reaper fails it as `console.noJob` if no job comes. A retry naming a job already on
record changes nothing; a rerun that already ended settles the retry at once. The failed job's late
events never end the retry.

Platform gotcha: a failed step does not fail the job. The job pauses as `needs-attention` and waits
for retry, skip or abort, so only an aborted, cancelled or rolled-back job ends `failed`.
`:retry` resumes a paused step; `:rerun` starts a new job from a step of an ended one.

**Why:** the BFF thread builds the retry button and the drift/indexer threads read `RetryRequested`.
**How to apply:** follow this order in the BFF; details in PR #7's README. See [[console-state-service]].

