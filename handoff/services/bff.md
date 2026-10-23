# console-bff

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: customer-console-bff


Built 2026-10-23 (thread "Console BFF") as PR #6 `console-bff--phase-1` → `console-bff` (orphan root branch like console-web/console-libs): https://github.com/gonzalomunoz1024/infrastructure-automation/pull/6. Spring WebFlux, Java 21, Gradle via `console.spring-service` from console-libs (mavenLocal; CI clones `console.libs.ref` and publishes build-logic first). Contracts pinned in `contracts.lock` to console-root--phase-0 b40ccc9; `contracts/additions.md` lists what the BFF answers beyond the pinned text.

Serves: OIDC+PKCE sign-in (`console_session` cookie 12h/1h idle, CSRF `X-Csrf-Token` + Origin check, step-up `/auth/login?stepUp=true` for `*:delete` within 10 min), session/accounts/regions, descriptors `/types` (ETag/304), resource pages `/console/v1/r/{type}…` over the state service (list, detail with `can`+ETag+410 tombstone, activity, drift, lineage, tags, create, update, verbs, batches, previews; destructive verbs need `confirm: true` + step-up), jobs/approvals, SSE `/console/v1/events`, preferences, IAM and access-request pass-through to identity, JWKS, and search through the search indexer (`/console/v1/search`, `/suggest`, `/r/{type}:counts`, list `q`). Internal JWTs: ES256, iss `console-bff`, aud [target, "console"], claims sub/acct/region/groups/roles/svc, 60 s.

Run: `./gradlew bootRun --args='--spring.profiles.active=demo'` (18081; embeds platform-fake 18090, demo IdP `/demo-idp`, `login_hint=<user>` skips the picker, stand-ins `/demo-identity`, `/demo-state`, `/demo-search`). `console.identity.url` (default http://localhost:18083), `console.state.url` (18082) and `console.search.url` (18085) swap in the real services. Profiles `mongo` (sessions, preferences), `kafka` (console events). Helm chart `deploy/helm/console-bff`, Dockerfile. Tests: 98 (`./gradlew test`, demo app on 18181; Mongo test needs CONSOLE_TEST_MONGO_URI). Search pushed 2026-10-23 13:06 UTC (f718409). Phase 4 (WP 4.1–4.6, session decisions): PR #26 `console-bff--phase-4` → `console-bff--phase-1`, 164 tests, contracts pinned to console-root--state-retry c6c22ab; see [[customer-console-bff-phase4]].

**Why:** later threads (web, identity, state, drift) integrate against these endpoints and token claims.
**How to apply:** build on `console-bff` after PR #6 merges; see [[customer-console-demo-people]], [[customer-console-bff-decisions]], [[customer-console-build]].

---

## Note: customer-console-bff-decisions


Decisions taken as lead engineer in the Console BFF thread, 2026-10-23, when wiring `/console/v1/r/*` to the state service (PR #7 contract):

- Update, verbs and delete start the platform workflow FIRST, then record `:request-update` / `:request-delete` on the state service. A rejected form (400) therefore never strands a resource in `updating`/`deleting`; a state refusal after the job started is logged and the platform feed reconciles the outcome.
- Create mints the wfrn on the state service first (`:mint`, with the request's Idempotency-Key), then starts the workflow with the wfrn in the job context (`X-Resource-Wfrn`), because the registry must bind the name.
- The after-start state commands send no `expectedVersion`: the feed's `job.started` already bumps the version, so the BFF enforces `If-Match` itself against the version it read.
- `tag` is listed in the detail's `can`; the finding's `can` (revert/accept/ignore) follows the update permission.
- Demo profile: in-process stand-in `/demo-state` with the real contract (mint, commands with idempotency replays, ETags, 90-day tombstones, event-envelope log), fed by the platform feed; every state event also reaches the SSE stream.
- Untested: running the BFF demo against the real state-service demo (both embed their own platform-fake). Flags: `--console.state.url=http://localhost:18082 --console.platform.fake.enabled=false`.
- Ports (coordinator, 2026-10-23 12:35 UTC): state 18082, identity 18083; the BFF defaults `console.state.url` and `console.identity.url` to them.
- Search (2026-10-23 13:06 UTC): the BFF filters indexer hits and suggestions by `<service>:list` per type (a page may hold fewer hits than `limit`); list `q` goes through the indexer, each hit re-read from the state service (16 at a time), hits it no longer holds dropped; the console's six sorts pass straight to the indexer (asked the indexer thread to accept them); every page writes `next: null`.
- Step-up refusal: 403 `error: "step-up.required"`, `message`, `detail.login` a path; the hash-routed console appends its own `returnTo`.
- Service tokens (hub decision 23, 1a71005; BFF 405d97c on PR #6): the BFF is the only internal-JWT issuer. Other services call `POST /internal/v1/token` (client credentials + RFC 7523 assertion: projected k8s service-account token, audience `console-bff`, or a short self-signed JWT with a registered key, jti once). Token: 5 min, `aud` only what was asked, `sub`=`svc`=service id, no `acct`. Clients in `console.trust.clients` (indexer, drift: state + identity). Never share the BFF key or static tokens. Spring gotcha: map keys in test properties must be dotted (`clients.x.y`), a bracketed key does not merge with application.yaml's entry.

**Why:** later threads must not undo the ordering or re-add `expectedVersion`, and every demo must agree on the ports.
**How to apply:** keep this ordering when adding verbs; use these ports when composing the demos. See [[customer-console-bff]], [[console-state-service]].

---

## Note: customer-console-bff-phase4


Branch `console-bff--phase-4` is stacked on `console-bff--phase-1` (PR #6) and is PR #26: https://github.com/gonzalomunoz1024/infrastructure-automation/pull/26. It was built 2026-10-23 in thread "Console BFF". The shapes are in `contracts/additions.md`, section "Changing resources safely (phase 4)".

Decisions:
- **Required tags** (`console.tags.required`: owner, cost-centre, data-class, environment) are never filled in by the BFF. Defaults only pre-fill the form through `GET /console/v1/tag-policy`, so API and MCP callers must name them too. A missing tag is 400 `tags.required` and a bad value 400 `tags.invalid`, and nothing is minted. The tags are set with the state service's `:tag` between mint and start, because MintRequest has no tags.
- **Retry** follows hub decision 24: rerun on the platform first, then `:request-retry` naming the rerun. A fix body `{input?, fromStep?}` merges input over the job's. A create's name cannot change (`retry.name-fixed`), and a batch takes no fix.
- **Bulk approvals**: `POST /console/v1/jobs/{id}/approvals:decide` decides every pending approval of a request and its rows that the person may decide. Each one is the person's own platform decision, and `left` names the others.
- **Tag Editor**: one apply covers up to 100 resources. Each resource is checked as `<service>:tag` and gets its own state command, and failures come back per row.
- **Launch more like this**: `GET /r/{type}/{wfrn}/clone?account=` returns a create draft. Secrets and read-only fields are left out, the name becomes `<name>-copy`, the account's default tags are swapped for the target's, and `<service>:create` is decided for each account.
- **Session decisions**: `GET` and `PUT /console/v1/session` carry `decisions`, keyed `<kind>:<verb>`. Each is decided on `wfrn:wf:iam::<acct>:<kind>/*`: IAM writes, `quota:request-increase`, and access-request create, list and decide. The BFF demo's approver groups now read the queue, as the identity service's demo seeds it.
- **Platform-fake pause race**: a job's pause is visible a moment before it takes a decision, which gives 409 `job.notPaused`. The BFF asks again on approve, reject, retry, skip and abort (`PausedJobs`), and rebuilds the seeded fake up to 3 times. The real fix is in thread "Platform fake pause race" (console-libs); drop the workaround once it lands.
- The cost and drift threads stack on this branch. `console-cost-service` is registered as a service-token client for the state and identity services.

**Why:** the web, cost, drift and copilot threads build on these shapes and rules.
**How to apply:** keep these rules when extending phase 4. See [[customer-console-bff]], [[customer-console-bff-decisions]], [[console-retry-flow]].

---

## Note: customer-console-bff-phase9


Branch `console-bff--phase-9` (worktree /home/claude/bff9) is stacked on `console-bff--cost` (PR #34), which it merged. It serves hub decision 27 (PR #16, console-root--phase-9-web): `GET /console/v1/insights`, `GET /console/v1/jobs/{jobId}/explanation`, `/search:interpret`, `/search:counts`, `/assistant/turns` and `/assistant/conversations/{id}`. The contract pin stays at the cost branch's. The decision 27 routes are listed in `contracts/additions.md` until the pin moves past #16. The hub stack branch `console-root--stack` (PR #30, the steward is the "Search indexer" thread) is the only commit with every decision, and it also carries the drift thread's BFF routes.

Rules that later work must keep:
- **retry-of label.** Every rerun the console starts carries the platform job label `retry-of: <failed job id>` (`JobRetries.RETRY_OF_LABEL`).
  - The executive home never counts a request that carries it, or that has a person's `decision` evidence entry, as self-served.
  - The explainer uses it to find the retry that fixed a failure.
- **Insights.** The window's start is cut to the minute, because the indexer's time terms are to the minute. Its end is never truncated: truncating it once dropped requests started in the current second.
  - If the drift service fails for one account, the answer gets `drift.partial: true` and is not cached.
  - The drift stats use the cost branch's `DriftProperties` (`console.drift.*`). The demo has no findings stats, so it shows zero findings.
- **Wave 2 design.** Decisions 31 (API clients: device-code bearer tokens, CLI and SDKs on a new `console-sdk` branch, the console shell, ChatOps) and 32 (notifications, feed, recent, favourites, weekly digest). Claimed with the steward, hub branch `console-root--bff-clients` stacked on #16. Decision 34 (organization admins via `console.organization.admin-groups`, org pages as the internal principal with 404 for others, IAM pass-through for guardrails, simulations, access reviews, elevations; `Denial.refusedBy`/`guardrailId`, since `scope` already names a platform scope; and the BFF paths for decisions 29 and 30) is on the same PR #38 (f195591, turn 14). Next free decision: 35.
- **Transfer route** (decision 30, wave 2):
  - Before the state service's `:transfer`, run the create's budget check on the target account: `POST /v1/budgets:check {accountId: toAccountId, type, service, tags, monthly}`, with `monthly` from `Costs#monthlyCost` or 0. A deny answers 409 `budget.exceeded`. If the cost service is down, let the move go ahead.
  - After the transfer, call `PlatformPort.rehomeResource(registryId, new RehomeRequest(from, wfrn, accountId), options)`, with `from` = the state service's `transferredFrom`. This needs libs 6861ed5 (pinned to hub e790a27).
  - A record already at `wfrn` answers 200, so a retry is safe. A record at neither answers 409 `resource.rehomed`.
- **Libs.** Local builds can't publish newer console-libs (the auto-mode classifier denied it), so new libs APIs are verified only in CI. The `PausedJobs` workaround stays until local libs include 4260aab.

**Why:** the web phase 9 thread, the cost thread and the wave 2 work build on these shapes.
**How to apply:** see [[customer-console-bff-phase4]], [[customer-console-bff]], [[console-retry-flow]], [[console-cost-service]].

---

## Note: console-service-tokens


Hub decision 23 (console-root--phase-0 1a71005, 2026-10-23): the BFF is the only issuer of internal JWTs. A service calling another on its own asks the BFF's `POST /internal/v1/token` (`issueServiceToken` in console-bff.yaml): form-encoded `grant_type=client_credentials`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `client_assertion`, optional `client_id`, `audience` once per callee. Answer: `{access_token, token_type: Bearer, expires_in: 300, audience}`; claims iss console-bff, aud only what was asked, sub = svc = the client id, no acct. Errors are RFC 6749 bodies (400 invalid_request/unsupported_grant_type/invalid_target, 401 invalid_client). The ingress never routes `/internal`.

Assertion: in a cluster the pod's projected service-account token (audience console-bff, expirationSeconds 3600, re-read per request); elsewhere a JWT signed with the service's own EC key (iss = sub = client id, aud console-bff, ≤ 5 min, fresh jti; ES256/ES384/RS256/PS256). The BFF registers clients under `console.trust.clients.<id>` (audiences, service-account `<ns>:<name>`, jwks or jwks-uri); console-search-indexer and console-drift-service are registered for console-state-service and console-identity-service.

Reference client: the search indexer, package `com.iaf.console.search.index.adapters.tokens` on PR #10 (ServiceTokens, ClientAssertion, ServiceCredentials as a WebClient filter): one token per callee, reused for 80% of its life, shared in-flight request, old token served while the BFF is down until 10 s before expiry, one retry after a callee's 401. Each BFF request must have a time limit (5 s, `console.auth.token-timeout`): callers share the in-flight request, so one that never ends stalls every call. Settings `console.auth.token-url`, `token-timeout`, `client-assertion-file`, `client-key`.

E2E recipe (verified 2026-10-23 14:07 UTC): state demo with `--console.auth.dev-headers=false`; BFF demo jar with `--console.platform.fake.enabled=false --console.state.url=http://localhost:18082 "--console.trust.clients.console-search-indexer.jwks=<public JWK>"`; indexer demo with `CONSOLE_AUTH_CLIENT_KEY=<private JWK with kid>`.

**Decision 35 (hub PR #46, 2026-10-23 18:30 UTC, from the BFF security review; supersedes the above where they differ):** every internal JWT has exactly one `aud`, the callee; the shared `console` audience is retired. `/internal/v1/token` takes exactly one `audience` (none or several is `invalid_target`; answer `audience` is a string). Every service refuses (401) a token whose `aud` isn't exactly its own id and accepts a no-`acct` token only from its own allow list of service principals (`console-bff` plus its decision 23 clients). `roles` never older than 5 min or past an elevation's `until`. No transition period.

**Why:** the drift service and any later service must call others the same way; no shared keys or static tokens.
**How to apply:** copy the indexer's client; see [[console-search-indexer]], [[customer-console-bff]], [[customer-console-build]].

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

