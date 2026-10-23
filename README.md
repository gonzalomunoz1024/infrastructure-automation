# console-bff

The console's backend for frontend: the one service the web app talks to. It signs people in
through the enterprise identity provider, keeps their session, tells the web app what each
resource type looks like and what the signed-in person may do with it, serves the resource pages
over the state service (list, detail, create, update, verbs, tags, lineage, drift, batches,
previews), changes them safely (blast radius, required tags, bulk in waves, retry with a fix,
the Tag Editor, resource groups, launch more like this), the Requests page (jobs and
approvals), cost and budgets (through the cost service, with budgets guarding creates),
compliance scorecards and the drift features (findings, the shadow-infrastructure finder, metrics
and alarms, rightsizing) through the drift service, the console copilot (which proposes what the
console opens and never acts), streams live changes, and passes IAM calls through to the identity
service.
It never holds platform credentials in the browser: every platform call is made here, with the
account's service account and a signed on-behalf-of assertion naming the person.

Work packages of the customer-console plan served here: **1.3** (OIDC sign-in, sessions, CSRF,
step-up), **1.5** (the refusal names its layer, access requests), **3.1** (type descriptors),
**3.2** and **3.3** (the resource pages, over the state service), **3.4** (live updates), the
BFF's side of **6.1** (global search, type-ahead and the list's counts, through the search
indexer, and since decision 27 plain English and grouped counts), phase 4: **4.1** (the update convention), **4.2** (delete with dependents, bulk
in waves, retry, skip and abort), **4.3** (tags, the Tag Editor, resource groups), **4.4**
(required tags at create), **4.5** (blast radius) and **4.6** (launch more like this); the BFF's
side of cost and budgets (decision 25: Cost Explorer, price lists, chargeback, budgets, budget
checks on creates, `budget.alert`), of the compliance scorecards (**9.6**), of **5.2** and **5.3**
(drift findings and the shadow-infrastructure finder) and of **9.21** and **9.22** (monitoring and
rightsizing), through the drift service; the executive home (**9.5**) and the failure explainer
(**9.2**); and the copilot (**9.1**, hub decision 27), which also reads the failures no rule of the
explainer knows.

## Running it

```
./gradlew bootRun --args='--spring.profiles.active=demo'
```

The demo profile needs nothing else: it embeds `platform-fake` on port 18090, a small OpenID
provider at `/demo-idp` with a user picker, and in-process stand-ins of the identity service, the
state service, the search indexer, the cost service and the drift service at `/demo-identity`,
`/demo-state`, `/demo-search`, `/demo-cost` and `/demo-drift`, reached over HTTP by the same
adapters that will talk to the real ones. Open
<http://localhost:18081/auth/login?returnTo=/> and pick a person:

| Person | Compute account 123456789012 | Data account 210987654321 |
| --- | --- | --- |
| gonzalo (organization admin) | account-admin (`owners`) | account-admin (`owners`) |
| dana | operator (`members`), in `compute-approvers` | |
| mateo | operator (`members`) | |
| mia | viewer (`viewers`), in `compute-approvers` | |
| raj | | operator (`members`), in `data-approvers` |
| ana | | operator and viewer (`members`, `viewers`) |
| noah | no account: the sign-in answers 403 | |

A third account, Sandbox (345678901234), has nobody in it: the organization pages' break-glass
access and closing an account are tried there.

`GET /auth/login?login_hint=dana` skips the picker, which is what the web app's Playwright runs
use. Ports: platform-fake 18090, bff 18081, state 18082, identity 18083, drift 18084, search
indexer 18085, cost 18086, web 3000 (the project's port decision of 2026-10-23; the plan's table had state and identity the
other way round).

To run against the real services, leave the demo profile off and set the settings below; the
`mongo` profile keeps sessions and preferences, and the copilot's conversations and turn counts, in
MongoDB (needed for more than one replica) and the `kafka` profile listens to the console's own
events (identity changes drop cached decisions).

## Settings

Every setting has a production-safe default in `application.yaml`; set them as environment
variables (`console.oidc.issuer` is `CONSOLE_OIDC_ISSUER`).

| Setting | What it is |
| --- | --- |
| `console.oidc.issuer`, `client-id`, `client-secret`, `redirect-uri`, `scopes`, `groups-claim` | The enterprise identity provider; discovery is read from `<issuer>/.well-known/openid-configuration` |
| `console.session.secret` | Base64, at least 32 bytes; seals the login ticket and signs CSRF tokens. Generated at boot when empty, which only suits one replica |
| `console.session.ttl`, `idle-timeout`, `secure`, `same-site`, `cookie-name` | The session cookie: 12 h, 1 h idle, `Secure`, `Lax`, `console_session` |
| `console.session.roles-refresh-interval` | How long a session uses the memberships `principals:resolve` answered before its next request reads them again (5 minutes); never past the earliest `accounts[].elevations[].until`, and sooner after an identity change |
| `console.auth.step-up.max-age`, `actions` | Which actions need a fresh sign-in (`*:delete`, within 10 minutes) |
| `console.organization.admin-groups` | The identity provider groups whose members are organization admins (decision 34), read from the sign-in's groups claim; empty by default (nobody), `console-org-admins` in the demo |
| `console.clients.verification-uri` | The web app's page where a person approves a CLI or SDK sign-in (`http://localhost:5173/#/device`); the CLI shows it with the user code |
| `console.clients.device-code-ttl`, `interval`, `grants-per-minute` | How long a device code waits for the person (10 minutes), the least time between two polls (5 s; each `slow_down` adds 5 s), and how many sign-ins one address may start a minute (10) |
| `console.clients.access-token-ttl`, `session-ttl`, `idle-timeout`, `session-check` | A CLI's access token lives 15 minutes; its API session ends 12 h after the approval or 8 h after the last refresh; a bearer call reads its API session again at most once a minute |
| `console.clients.proxy-hops` | How many proxies in front of the BFF add to `X-Forwarded-For`; the client's address is that many entries from its right. With 0 it is the connection's address, which on Kubernetes Spring Boot already takes from the header's first entry, one the client writes itself: set it to 1 behind the ingress |
| `console.trust.key`, `previous-key`, `issuer` | The private EC JWK that signs internal tokens and on-behalf-of assertions; published at `/.well-known/jwks.json`. Generated at boot when empty |
| `console.trust.clients.<service>.audiences`, `service-account`, `jwks`, `jwks-uri` | The console services that may get service tokens, the services each may get them for, and how each proves who it is (see Service tokens) |
| `console.trust.kubernetes.issuer`, `jwks-uri`, `jwks`, `ca-file`, `token-file` | The cluster whose projected service-account tokens prove a client; off while `issuer` is empty |
| `console.trust.service-token-lifetime`, `client-assertion-max-lifetime` | A service token lives 5 minutes; a client's own assertion may live 5 minutes at most |
| `console.trust.kubernetes.max-token-lifetime`, `single-use` | A cluster's service-account token may live 1 hour at most (from its `iat`, or from now without one); with `single-use` (on) each is redeemed once, by its `jti` or else its hash, until it expires |
| `console.trust.rate-limit.burst`, `per-minute` | A token bucket per client of `POST /internal/v1/token`: 10 at once, 30 a minute; over it is 429 `slow_down` with `Retry-After` |
| `console.identity.url`, `console.state.url`, `console.search.url` | The identity service (`http://localhost:18083` by default), the state service (`http://localhost:18082`) and the search indexer (`http://localhost:18085`); an empty URL means the in-process stand-in the demo profile mounts (`/demo-identity`, `/demo-state`, `/demo-search`) |
| `console.cost.url`, `service-name`, `timeout`, `check-timeout` | The cost service (`http://localhost:18086`; empty: `/demo-cost`), its token audience, and how long a create waits for the budget check and estimate (2 s; past it the create goes on without them) |
| `console.drift.url`, `service-name`, `timeout` | The drift service (`http://localhost:18084` by default), the audience of the internal tokens it gets (`console-drift-service`) and how long a call may take (5s); an empty URL means the stand-in under `/demo-drift` |
| `console.insights.baseline.source`, `lead-time`, `by-workflow.<workflow>` | The ticket baseline the executive home compares lead times with: where it comes from, the median ticket overall, and per workflow. With no lead time at all, hours saved is left out |
| `console.insights.cache-ttl`, `max-jobs-per-account`, `concurrency` | How long an executive home answer is kept (5 minutes), the most requests one account counts in a period (10000), and how many accounts are read at once (8) |
| `console.assistant.provider`, `turns-per-minute`, `conversation-ttl`, `turn-timeout`, `failure-wait` | The copilot: `devin` (the firm's Devin, the default), `demo` (rules in this process, the demo profile's) or anything else for none, when every turn fails `assistant.unavailable`; a person's turns in any minute (10, failure readings included); how long a conversation is kept from its start (24 h); when a turn not answered fails `assistant.timeout` (5 min); how long a job's explanation waits for the copilot's reading of a failure (5 s) |
| `console.assistant.devin.base-url`, `api-key`, `poll-interval`, `request-timeout`, `max-acu-limit` | Devin's API (`https://api.devin.ai`), its key (from `CONSOLE_ASSISTANT_DEVIN_API_KEY`, the chart's secret, never a file), how often a turn's session is read (3 s), one call's timeout (30 s), and the most ACUs a session may spend (0: Devin's own limit) |
| `console.platform.base-url`, `internal-token`, `accounts.*`, `partition` | The platform, the console's internal service account, and per-account service accounts (otherwise read from the identity service's `platform-credentials`) |
| `console.web.origins` | The web app's origins for CORS and the CSRF origin check |
| `console.types.cache-ttl`, `console.access.cache-ttl` | How long descriptors and policy decisions are kept (also dropped on events) |
| `console.tags.required`, `console.tags.accounts.<accountId>.<key>`, `console.tags.account-ttl` | The tags every create must name (key, title, values or pattern, default, `default-from`: `account.owning-team`, `account` or `input.<field>`), an account's own default values, and how long an account's owning team is kept |
| `console.types.demo-metrics` | Demo only: the types whose descriptor says `observe.metrics` although the platform type has no metrics read (`[vm]` in the demo profile, whose drift stand-in makes the series up) |
| `console.live.ring-capacity`, `heartbeat`, `relay.enabled` | The replay ring per account, the SSE heartbeat, and whether this instance follows the platform feed |
| `console.platform.fake.*` | Demo only: the embedded platform's port, step delay and seed |
| `console.kafka.*` | With the `kafka` profile: bootstrap servers, group id, topics |
| `console.kafka.sessions.topic`, `group-prefix` | With the `kafka` profile: the identity changes the sessions follow (`identity.changes`), each instance in a consumer group of its own (`console-bff-sessions-<random>`) |

## Security model

* **Sign-in** is the authorization-code flow with PKCE (S256), run by this service. The state,
  verifier, nonce, return path and (for a step-up) the session id travel in a sealed cookie
  (`console_login`, AES-256-GCM, path `/auth`, 10 minutes), so the callback may land on any
  replica. The ID token is verified against the provider's JWKS (issuer, audience, expiry, nonce).
* **The principal** is resolved by the identity service (`POST /v1/principals:resolve`): who the
  person is in the console and which accounts they belong to, with their managed roles. No account
  means 403 at sign-in. A session reads them again on its first request after
  `roles-refresh-interval`, after the earliest time-boxed role (elevation or break glass) ends,
  after an IAM write made through this instance, and (with the `kafka` profile) after an
  `identity.changes` event that concerns the person or the account (`ElevationChanged`,
  `UserChanged` updated or deleted, `GroupChanged`, `RoleChanged`, `PolicyChanged`,
  `AccountChanged` closed or suspended). A person no longer in the session's account, or in none,
  loses the session (401); a re-read that fails answers 503. No internal token outlives the roles
  it carries.
* **The session** is an opaque random id in an `HttpOnly`, `SameSite=Lax`, `Secure` cookie; the
  server keeps the memberships, the current account and region, and `authTime`, under the
  SHA-256 of the id (the id itself is never stored); only the sign-in inserts a session, so a
  request in flight never brings back one that was logged out. Switching account
  or region is `PUT /console/v1/session`.
* **CSRF**: every write under `/console/` and the logout need `X-Csrf-Token`, an HMAC of the
  session id, which `GET /console/v1/session` hands out; when the browser sends an `Origin` it
  must be the web app's or this service's own. A missing token is the one 403 without a `denied`
  block.
* **Step-up**: destructive actions within the configured max age need a fresh authentication:
  the API answers 403 with `detail.stepUp` and a login path; `/auth/login?stepUp=true` re-runs the
  provider with `prompt=login&max_age=0` and refreshes the same session's `authTime`.
* **Downstream calls** carry a 60-second ES256 JWT minted here (`iss` console-bff, `aud` exactly
  the target service and nothing else, hub decision 35, `sub`, `acct`, `region`, `groups`, `roles`)
  that the identity, state and search services verify against `/.well-known/jwks.json`; the BFF's
  own calls as the console-internal principal carry `sub` and `svc` `console-bff` and no `acct`.
  The demo stand-ins verify the same way. Platform commands carry the account's service-account token and
  a P-02 on-behalf-of assertion signed with the same key.
* **Service tokens** (hub decision 23): a console service that calls another on its own gets a
  token from `POST /internal/v1/token`, never a copy of the signing key or a static token. See
  Service tokens below.
* **The CLI and the SDKs** (decision 31) sign in with the device grant and call with a bearer
  token the BFF signs with its trust key (`typ: at+jwt`, `aud: console-api`), which no other
  service accepts and in whose place the BFF accepts no internal token. A bearer call reads no
  cookie, so it needs no CSRF token. See API clients below.
* **Every refusal names its layer**: a 403 carries `denied` with `layer` `console-iam` (the
  account's policies: action, resource pattern, role, policy, statement, suggested role,
  requestable) or `platform` (the service account's missing scope).

## Endpoints

The pinned contract is `src/main/resources/contracts/console-bff.yaml` (hub commit in
`contracts.lock`, checked by `ContractsLockTest`). Beyond it, this service also serves the
endpoints the web app asked for during phase 1, described in `contracts/additions.md`:

| Path | What |
| --- | --- |
| `GET /auth/login`, `GET /auth/callback`, `POST /auth/logout` | The sign-in |
| `POST /auth/device`, `POST /auth/token`, `POST /auth/revoke` | The CLI and SDK sign-in (decision 31): start a device grant, redeem it or refresh, sign out; form-encoded, with RFC 6749's error bodies |
| `GET /console/v1/device-grants/{userCode}`, `POST …:approve`, `POST …:deny` | The web app's `#/device` page: the grant a user code names, and the person's decision on it (browser only; approving needs a fresh sign-in) |
| `GET /console/v1/api-sessions`, `DELETE /console/v1/api-sessions/{apiSessionId}` | The person's open CLI and SDK sign-ins, newest first, and signing one out |
| `GET, PUT /console/v1/session`, `GET /console/v1/accounts`, `GET /console/v1/regions` | The session (with `decisions` on the account's IAM writes, quota and access requests), the memberships, the regions |
| `GET /console/v1/types`, `GET /console/v1/types/{key}` | The descriptors, with `ETag` and `If-None-Match`, every action with `allowed` and `denied` |
| `GET, POST /console/v1/r/{type}`, `POST …:preview`, `POST …:batch`, `POST …:batch:preview` | The account's resources of a type (`q` through the search index, `state`, `tag.<name>`, `drift`, `sort`, cursor paging), a create with its required tags, its preview, a batch in waves, the batch's preview with each row's problems and what the rows reach |
| `GET /console/v1/r/{type}:counts` | How many resources the list's filters match: `total`, `byState` for the state chips, `drift`, `cappedAt` |
| `GET /console/v1/search`, `GET /console/v1/suggest` | Global search with highlights and `didYouMean`, and type-ahead, over the types the person may list |
| `GET /console/v1/search:interpret` | Plain English read as the list's own filter (the indexer's reading: `q`, `type`, `filter`, `sort`, `explanation`, `confidence`, `unparsed`, `total`) |
| `GET /console/v1/search:counts` | Resources counted by one grouping (`groupBy`) with a search's filters, in the session's account or across the person's accounts (`scope=organization`), with `asOf` |
| `GET, PATCH /console/v1/r/{type}/{wfrn}`, `POST …:{verb}`, `POST …:{verb}:preview` | One resource with `can`, `ETag` and `If-None-Match`; an update (`If-Match`); a verb of the catalog; its preview |
| `GET …/{wfrn}/activity`, `GET …/lineage`, `PUT …/tags` | The resource's log, what it was built from and what depends on it, its tags |
| `GET …/{wfrn}/blast-radius`, `GET …/{wfrn}/clone` | What depends on the resource, in every account (named in the session's); its create form filled from it, for this account or another |
| `GET /console/v1/tag-policy`, `GET /console/v1/tags/resources`, `POST /console/v1/tags:apply` | The account's required tags with their defaults; the Tag Editor's search across types by tag, and tagging up to 100 resources at once |
| `GET …/{wfrn}/drift`, `GET …/{wfrn}/metrics`, `GET …/{wfrn}/alarms` | The resource's Drift and Monitoring tabs: its findings, its metric series, its alarms |
| `GET /console/v1/findings`, `GET …/summary`, `GET …/stats`, `GET …/{findingId}`, `POST …:revert`, `POST …:accept`, `POST …:ignore` | Drift findings with `can`, their counts and the executive numbers; a revert runs the type's update workflow for the person |
| `POST /console/v1/discover`, `GET …/{runId}`, `GET /console/v1/unmanaged`, `POST …:import`, `POST …:assign` | The shadow-infrastructure finder: a scan, what no account manages (with owner guesses and monthly costs), taking an object under management or handing it to another account |
| `GET /console/v1/alarms`, `PUT, DELETE …/{alarmId}` | The account's alarms, created and changed under an id the web app picks |
| `GET /console/v1/recommendations`, `GET …/summary`, `GET …/{recommendationId}`, `POST …:apply`, `POST …:dismiss` | Rightsizing: idle and oversized resources; a resize, or a confirmed delete after a fresh sign-in |
| `GET, POST /console/v1/resource-groups`, `GET, PATCH, DELETE …/{groupId}`, `GET …/{groupId}/resources` | Resource groups: tag queries per account and region (unique names, `ETag` and `If-Match`, `Idempotency-Key` on create) and the resources each one matches |
| `GET /console/v1/jobs`, `GET /console/v1/jobs/{jobId}`, `POST …:cancel`, `…:retry`, `…:skip`, `…:abort` | The account's jobs, with `can`, a batch's waves and rows; retry (optionally with a fix: input and step), skip a paused step, abort with compensations |
| `POST /console/v1/jobs/{jobId}/approvals:decide` | Every pending approval of a request and its rows the person may decide, in one call (once per wave) |
| `GET /console/v1/approvals`, `POST …/{approvalId}:decide` | Approvals, with `canDecide` (group membership and policy) |
| `GET /console/v1/insights` | The executive home: resources managed, requests and how many were self-served, lead time against tickets, hours saved, drift found and fixed, adoption week by week |
| `GET /console/v1/jobs/{jobId}/explanation` | Why a job failed and what to do next: the cause, the fix and the step to run again from, whether the person may retry, how often it happened and what fixed it last |
| `POST /console/v1/assistant/turns`, `GET /console/v1/assistant/conversations/{conversationId}` | The copilot: a turn (`ask` with a message, `explain` with `context.wfrn`) answers 202 `{ conversationId, turnId }` and goes on in the background; the conversation, polled until the turn is `answered` (text, at most five proposals, an explanation) or `failed`; 429 with `Retry-After` past 10 turns a minute |
| `GET /console/v1/events` | Server-sent events: `job.updated`, `resource.*`, `types.changed`, `region.changed`, `resync`; replay from `Last-Event-ID`, `wfrn` and `types` filters |
| `GET, PUT /console/v1/preferences` | Theme, density, pinned types, columns and saved views per type; versioned |
| `/console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}…` | Pass-through to the identity service for the session's account; a quota increase is asked on its quota and followed or withdrawn under `quota-requests` |
| `GET /console/v1/iam/guardrails`, `POST …/policy-simulations`, `GET /console/v1/iam/{quota-rules,elevation-rules}` | The guardrails over the account (read only), the policy simulator (nothing written), and the organization's quota and elevation rules |
| `GET, POST /console/v1/iam/access-reviews`, `GET …/{id}`, `GET …/{id}/items`, `POST …/{id}/items/{itemId}:decide`, `POST …/{id}:cancel` | Access reviews (WP 9.11), passed through |
| `GET, POST /console/v1/iam/elevations`, `GET …/{id}`, `POST …/{id}:decide`, `POST …/{id}:revoke` | Elevations (WP 9.12), passed through |
| `GET /console/v1/organization/accounts`, `POST …/{accountId}:close`, `POST …/{accountId}/break-glass` | Organization admins only (404 otherwise): every account, closing one, break-glass access with the admin as `approvedBy` |
| `GET, POST /console/v1/organization/guardrails`, `GET, PATCH, DELETE …/{guardrailId}` | Organization admins only: the organization's guardrails |
| `GET, POST /console/v1/access-requests`, `GET …/{requestId}`, `POST …:decide` | Request access from a refusal; everyone sees their own, admins and auditors the queue; admins and approver groups decide |
| `GET /.well-known/jwks.json` | The keys other services verify this service's tokens with |
| `POST /internal/v1/token` | A service token for another console service (client credentials with a JWT client assertion); in-cluster only |
| `/demo-idp/*`, `/demo-identity/*`, `/demo-state/*`, `/demo-search/*`, `/demo-cost/*`, `/demo-drift/*` | Demo profile only |

## Descriptors (P-06)

A descriptor is a platform resource type plus the catalog entries whose noun is the type. Where
the type's console block says nothing, the defaults apply: the service from the owner team
(`team-compute` becomes `compute`), the title from the plural, a generic icon, the list columns
`name`, `status`, every field with `x-console.column`, then `created`; the usual detail tabs; the
lifecycle classes by state name (`building` is pending, `ready` active, `retired` deleted). Every
verb is decided for the session's user against `<service>:<verb>` on
`wfrn:<partition>:<service>:<region>:<account>:<type>/*`, and the ETag covers both the content and
those decisions. Descriptors are cached per account and region and dropped when the platform
publishes content.

## Live updates

One instance follows the platform's event feed with the console's internal service account and
fans events out per account into a ring (`console.live.ring-capacity` events, one global
sequence). A browser subscribes once with its session and may send `Last-Event-ID` after a
reconnect; what the ring still holds is replayed, and when the cursor is older than the ring a
`resync` event tells the web app to reload. The `kafka` profile adds the console's own events, so
an identity change drops the cached policy decisions of its account on every instance, and a
budget's threshold crossed (`BudgetThresholdCrossed` on `cost.events`) reaches the account's pages
as `budget.alert`, only on the streams of people who may read the budget (`budgets:get`).

## Resources (WP 3.2, 3.3)

The state service owns what exists and in which state; the platform runs the workflows. The BFF
joins the two:

- **List and detail** read the state service for the session's account and region (its token
  carries `sub`, `acct`, `region`, `groups` and `roles`); the detail adds the verbs the account's
  policies allow on that wfrn (`can`, with `tag`), the open finding, the last ten jobs of the
  resource and the lineage neighbours from the platform's registry. A wfrn of another type,
  account or region answers 404, never a hint. The state service's `ETag` passes through, a
  tombstone answers 410 with `deletedAt` and `tombstoneUntil` in `detail`, and the log stays
  readable.
- **Create** asks the policies for `<service>:create` on the type's pattern, checks the form
  names the workflow's required fields, mints the name on the state service (`:mint`, with the
  request's `Idempotency-Key`, so a retry reuses it), then starts the type's create workflow with
  the name in the job's context (`X-Resource-Wfrn`); the registry binds it and the feed's
  `resource.registered` makes the resource active with the registry's attributes.
- **Update, verbs, delete** ask the policies on the wfrn, a destructive verb a fresh sign-in and
  `confirm: true`, start the workflow on the registry's record (the catalog entry's subject input
  and path parameter take the registry id) and then record the request on the state service
  (`:request-update` with the changes, `:request-delete` with the reason). The platform goes
  first so a rejected form (400) never leaves a resource stuck in `updating` or `deleting`; if
  the state service then refuses the record (a race on its state machine) the job runs anyway
  and the refusal is logged, as the feed brings the outcome back either way.
- **Batches** mint one name per row for a create (`wfrns` in the answer, one per row, bound
  through the batch's `contexts`) or take the rows' wfrns for another verb, and start the
  catalog's batch route with the rollout policy.
- **Previews** go to the catalog entry's preview route; 404 when the workflow publishes none.

In the demo profile the state service is a stand-in under `/demo-state` with the real service's
contract (mint, commands with idempotency replays, ETags, tombstones for 90 days, the log in the
console's event envelope), fed by the platform feed the way the real service is fed (`job.started`,
`job.finished`, `resource.registered`, `resource.retired`); every state event also reaches the
live stream, as the Kafka consumer delivers them in production. To run against the real state
service instead, start its demo (PR #7: port 18082, its own platform-fake on 18090) and run the
BFF with `--console.state.url=http://localhost:18082 --console.platform.fake.enabled=false` so
both talk to the same platform; this composition has not been exercised here yet, as the two
demos embed their own platform-fake today.

## Changing resources safely (phase 4)

The shapes and refusals are in `contracts/additions.md`; what the BFF does:

- **Blast radius** walks the platform registry's lineage from the resource, up to five levels,
  and counts what depends on it in every account while naming only the session's account's
  resources. A delete's preview carries it and denies with `dependents.exist`; the delete itself
  is a 409 with the dependents, and nothing runs. A batch's preview answers each row's problems
  and what the rows reach together before anything starts.
- **Retry** follows the hub's order: the platform runs the job again first (`:rerun` from the
  failed step, `:retry` for a paused one), then the state service records the retry, so a rerun
  the platform refuses records nothing. A failed create's resource goes back to `pending`. A fix
  merges its input over the job's and may name the step to run from; a create keeps its minted
  name.
- **Bulk in waves** passes the rollout to the platform's batch route (cumulative percentages, a
  failure threshold); the job's view shows each wave and row, and one call decides a wave's
  approvals, each recorded as the person's own platform decision.
- **Required tags** come from `console.tags.required`, with defaults from the account (its owning
  team, or a value configured per account), the organisation, or the create's own field. The
  form is pre-filled from `GET /console/v1/tag-policy`, but the BFF never adds a tag the request
  left out, so API and MCP callers are held to the same rule. The tags are set on the minted
  resource before its workflow starts.
- **The Tag Editor** finds the account's resources across types by tag, as the person may list
  each type, and tags up to 100 at once, one state service command per resource, each as
  `<service>:tag` on it allows; required tags may not be removed.
- **Launch more like this** fills the type's create form from the resource's attributes, or what
  it was created with, leaving secrets and read-only fields out, suggesting `<name>-copy` when it
  fits the field, and swapping the source account's default tags for the target's. Every account
  of the person's is offered, with `<service>:create` decided there.
- **The session's decisions** answer the account's IAM writes, quota and access-request actions
  for the person in one batch to the identity service, so the console greys what the person may
  not do before they press it.

## Search (WP 6.1, the BFF's side)

The search indexer finds; the account's policies decide what the person sees. Every call carries
an internal token for the person in the session's account and region, so the indexer never
searches another account; only counts across accounts are asked as the console-internal
principal, naming the accounts the person may list in.

- **Global search** (`GET /console/v1/search?q=`) and **type-ahead** (`GET /console/v1/suggest`)
  ask for `<service>:list` on every type of the catalogue and leave out the hits and suggestions
  of the types the person may not list, so a page can hold fewer hits than asked while its cursor
  goes on. A tag term is suggested when the person may list any type. With `type`, a type the
  person may not list is refused like its list.
- **The list's free text** (`GET /console/v1/r/{type}?q=`) goes through the index with the type,
  `state`, `tag.<name>`, `drift` and the console's sort (relevance when none is given), and each
  hit is read again from the state service, sixteen at a time and in the index's order, so the
  row is the state service's record; a hit the state service no longer holds is left out.
- **Counts** (`GET /console/v1/r/{type}:counts`) take the list's filters and answer the total,
  one count per state whatever the `state` filter (tombstones under `deleted`), the drifted
  ones, and `cappedAt` (`null` while the counts are exact).
- **Did you mean** on a search page is the indexer's own, passed on when it cannot spell a word
  of a hit that was left out.
- **Plain English** (`GET /console/v1/search:interpret?q=`, decision 27) is the indexer's reading
  of the text as the list's own filter, with a sentence saying what was understood and how sure it
  is. With `type` the reading stays in that list, which the person must be allowed to list; a
  reading of a type they may not list is refused like its list, and a reading of no type has its
  `total` counted again over the types they may list.
- **Grouped counts** (`GET /console/v1/search:counts?groupBy=`, decision 27) count by type,
  service, state, region, account or one tag's value, with the filters of a search, in the
  session's region: the session's account as the person, or with `scope=organization` every
  account of the person's that is not closed and where they may list what is counted, added up
  from the indexer's answers for the console-internal principal (at most 200 accounts a call).
  At most 100 groups, the largest first, and `asOf`, the index's last event.

In the demo profile the indexer is a stand-in under `/demo-search` that reads the demo state
service's records directly: the indexer's query syntax (free words and quoted phrases, `tag:`,
`type:`, `state:`, `region:`, `drift:` terms, `created:` and `updated:` terms with a day, a time
or an age, and wfrn patterns), relevance (a name equal to a word scores 10, a prefix 6, a
contained word 4, the wfrn 2, a tag or attribute 1, a word a typo away less, with `didYouMean`),
HTML-escaped highlights, every sort the console offers, exact counts by any grouping and over
several accounts for the console-internal principal, the index's status, and a reading of plain
English (states, types and their plurals a typo away, times, drift, sorts, and the account's own
tag names and values and regions).

## Cost and budgets (decision 25, the BFF's side)

The cost service prices; the account's policies decide who sees what. `cost:list` reads Cost
Explorer (`GET /console/v1/cost`), the price lists and a resource's cost
(`/r/{type}/{wfrn}/cost`, decided on the wfrn); `cost:export` the chargeback (JSON and CSV);
`budgets:get|list|create|update|delete` the budgets, decided on
`wfrn:<partition>:budgets::<account>:budget/<id>` (the id's underscore written as a hyphen), and
each budget carries `can: { update, delete }`. Cost is decided on `wfrn:<partition>:cost::<account>:*`.
With the managed roles every role reads, admins and auditors export, only admins change budgets.

- The session's own account is read as the person. `scope=organization` decides the action in
  each of the person's accounts, reads the allowed ones (at most 500) in one call with the BFF's
  service token, and names the others in `skippedAccounts` (a header,
  `X-Console-Skipped-Accounts`, on the CSV). The browser never names accounts itself.
- Before a create's preview and before a create (one row or a batch), the BFF asks the cost
  service's `budgets:check` with its service token: the account, type, service, tags, and the
  preview's monthly cost (0 for the create itself). Each rule joins the preview's policy with its
  `budgetId`; `budget.exceeded` denies, and the create is refused with a 409 `budget.exceeded`
  (`detail.budgets`). A preview the provider's plan priced nothing is priced from
  `POST /v1/cost:estimate`. Both fail open: past `console.cost.check-timeout` (2 s), or with the
  cost service down, the BFF logs it and goes on with no budget rule and no estimate.
- `Costs.monthlyCost(Session, wfrn)` gives the copilot one resource's monthly cost, empty when
  refused, unknown or unreachable.

In the demo profile the cost service is a stand-in under `/demo-cost` over the demo state
service: list prices as the platform-fake's (a vm by cpu or size, a database by tier), spend
counted day by day from each resource's creation, a budget's spend its scope's monthly run rate,
and every threshold crossed published to the live stream as `budget.alert`.

## Compliance scorecards (WP 9.6, the BFF's side)

`/console/v1/scorecards` (the account's scorecard), `/scorecards/packs`, `/scorecards/heatmap`,
`/scorecards/packs/{pack}/rules/{ruleId}/failing` and `/r/{type}/{wfrn}/compliance` pass through
to the drift service's scorecards as the person, as `scorecards:list` allows (the compliance page
also the resource's own `<service>:get`); the heat map decides it in each of the person's accounts
and reads the allowed ones with the service token, naming each row's account. A one-click fix
(`POST /scorecards/packs/{pack}/rules/{ruleId}:remediate`) needs `scorecards:remediate` on the
resource and the fix's own action (`<service>:tag`, or `<service>:<verb>` with its step-up rule),
relays the person's `X-On-Behalf-Of`, `Idempotency-Key` and request id, and answers the drift
service's 200, 202, 400 and 409 as they were. The demo's stand-in under `/demo-drift` judges one
pack, `baseline` (owner and cost-centre tags with a tags fix, a meaningful name without one).

## Drift, monitoring and rightsizing (WP 5.2, 5.3, 9.21, 9.22, the BFF's side)

The drift service finds and remembers; the BFF decides who sees and does what, and acts for the
person. Every call carries an internal token for the person in the session's account and region
(the drift numbers may also be read as the console-internal principal, which the executive home
does).

- **Lists** (findings, unmanaged objects, alarms, recommendations) ask the policies once per page
  and leave out the rows of types the person may not list, and of other accounts and regions;
  each row's `can` says what the person may do with it (`revert`, `accept`, `ignore`;
  `import`, `assign`; `apply`, `dismiss`).
- **Writes** need `<service>:update` on the wfrn (a decision on a finding, an alarm, a resize, a
  dismissal), `<service>:delete` with `confirm: true` and a fresh sign-in (a delete
  recommendation), `<service>:create` on the type's pattern (an import, an assignment) or
  `<service>:observe` (a discovery, a resource's metrics). A revert and an apply carry the
  person's `X-On-Behalf-Of` assertion to the drift service, which relays it onto the platform
  job; every write forwards `Idempotency-Key` and sends `X-Console-Request-Id`.
- **Refusals** of the drift service pass on: its 409 codes (`finding.reverting`,
  `recommendation.closed`...) as the BFF's coded 409, its 429 with `Retry-After`, its 502 with
  the platform's correlation id.

In the demo profile the drift service is a stand-in under `/demo-drift`: findings on the demo's
virtual machines and the orders database, two virtual machines no account manages, made-up
metrics with alarms over them, and two recommendations (an idle VM to delete, an oversized one to
resize). It keeps the demo state service in step (a finding flags its resource; an import mints
one) and verifies the BFF's assertions as the platform would. The details are in
`contracts/additions.md` under Drift.
## Executive home (WP 9.5, the BFF's side)

`GET /console/v1/insights?period=7d|30d|90d&scope=account|organization` (30 days and the
organization by default) covers the accounts where `insights:get` is allowed on
`wfrn:<partition>:insights:*:<account>:*`: the session's account, or every account of the person
that is not closed. Every managed role reads it; none allowed is a 403 with the session account's
Denial. The numbers, for the window from the period's start (to the minute) until now:

- **Managed**: the live resources now, by service, from the search indexer's counts across the
  accounts; at the start, those less the ones created since plus the ones created before and
  deleted since.
- **Requests**: the top-level platform jobs of each account started in the window on behalf of a
  person. Self-served means it ended `succeeded` with nobody stepping in: its evidence has no
  `decision` by a person (a failed step retried, skipped, given a new input or aborted), and it is
  not a console retry. Every rerun the console starts carries the label `retry-of` naming the
  failed job; the rerun copies the job's other labels and leaves the label out only when the job
  already has the 16 the platform takes. `failed` counts `failed` and `rolled-back`.
- **Lead time**: the nearest-rank p50 and p90 of the self-served requests' durations, against the
  configured ticket baseline (each request's workflow's own, else the overall one); hours saved
  adds, over the self-served requests, the baseline less the lead time, never below 0 for one.
- **Drift**: the drift service's findings stats per account (`GET /v1/findings/stats` with the
  BFF's service token): found, fixed, open now and the mean time to fix, weighted by the findings
  each account closed. An account the drift service does not answer for adds nothing and sets
  `drift.partial`; such an answer is not cached.
- **Adoption**: every Monday-to-Sunday week (UTC) of the window, oldest first: the people who
  started a request, the requests and the self-served ones.
- **Accounts**: name, owning team and cost centre from the identity service's account record, the
  cost centre otherwise the default of the account's required `cost-centre` tag, so the web can
  join the numbers to the cost service's spend by team.

An answer is kept for `console.insights.cache-ttl` per scope, period and account set. Reading the
jobs is bounded: at most `max-jobs-per-account` requests per account, with a warning when one
account starts more. Rolling the numbers up from the platform's feed, so the home stays cheap at
1M resources an account, is the next step once the load tests ask for it.

## Failure explainer (WP 9.2, the BFF's side)

`GET /console/v1/jobs/{jobId}/explanation` explains a `failed` or `rolled-back` job, or one
paused on a failed step (`needs-attention`), to anyone who may read the job (404 for another
account's job; 409 `job.not-failed` with the status otherwise):

- **Cause**: the error class (the paused step's while it waits, else the job's), a title in plain
  words and the provider's message; the failed step with its attempt.
- **Fix**: from the rules for the provider classes (`unavailable`, `rate_limited`, `timeout`,
  `not_found`, `conflict`) and the platform's (`approval.rejected`, `resource.notReady`,
  `resource.idCollision`, `batch.rowsFailed`, `platform.internal`), with how sure it is; a class
  no rule knows goes to the assistant's `FailureAdvisor` when one is wired in, else to a generic
  explanation naming the step (`low`). `source` says which (`rules` or `assistant`). The step to
  run again from is the paused step, or the one a retry would start from. A rule never suggests
  an input.
- **Retry**: whether this person may retry it now, the same decision as the job's Retry button,
  with the Denial when not.
- **Similar**: the jobs of the same workflow in the account that failed with the same class in
  the last 30 days (2000 at most), and the latest console retry (`retry-of`) of one of the ten
  most recent that succeeded: who ran it and when.

## The copilot (WP 9.1, hub decision 27)

The copilot reads what the person may read with their own session and never acts: what it
proposes (a filtered list, a create wizard or a verb's form filled in, a console page) the console
opens for the person to review and run as usual, through the preview and IAM.

- `POST /console/v1/assistant/turns` stores the question and an assistant turn `thinking`,
  answers 202 and goes on in the background; the console polls
  `GET /console/v1/assistant/conversations/{conversationId}`. A conversation is its person's, in
  the account and region it started in, for 24 hours: anyone else's, or an older one, is a 404.
- A person has 10 turns in any minute, across their accounts and conversations, then a 429 with
  `Retry-After`. Conversations and turn counts are kept in memory, or with the `mongo` profile in
  `assistant_conversations` and `assistant_turns`, whose TTL indexes forget them, so every
  replica counts the same turns.
- The BFF gathers what the assistant gets with the person's session: the account and region with
  the person's roles, the types the person may list or create (`<service>:list` on each type's
  pattern, the descriptors' greying) with their create forms and the verbs the person may run,
  forms without secret fields; the resources the question names; the page's context; the last ten
  turns; and for `explain` the resource's detail, its activity (who created it and when, its last
  change), its blast radius, its open drift finding and its monthly cost from
  `Costs.monthlyCost`. Then a `FastPath` bean, when one is wired, may answer first; otherwise the
  `AssistantPort` does.
- Every answer is reviewed before it is stored, against the person's own permissions: at most
  five proposals; a filter over a type the person may list, reduced to the list's filter; a create
  only of a type the person may create, its input reduced to the form and `missing` worked out
  from it; an action only with a verb the person may run on that wfrn, in the session's account
  and region; an open only of a `/console/` route. Text loses its markup, and a sentence stating a
  cost the cost service did not answer is dropped. What fails is dropped and logged.
- A turn ends `answered`, or `failed` with `assistant.unavailable` (the assistant unreachable or
  erring), `assistant.timeout` (no answer within 5 minutes) or `assistant.refused` (a refusal, or
  nothing the console can show).
- The `devin` adapter runs one Devin session per turn over the v1 API: it creates the session with
  a prompt (`assistant/devin-prompt.txt`) carrying the gathered request and the answer's JSON
  Schema (`assistant/answer-schema.json`, also its `structured_output_schema`), no organization
  secret and no knowledge, unlisted; reads it every 3 seconds until it is finished (or blocked
  after answering), and takes its structured output. A session past the turn timeout is ended.
  The key is the BFF's, in the `Authorization` header only.
- The failure explainer asks the copilot about a failure class its rules do not know (the
  `FailureAdvisor` seam): the copilot's cause and fix, the platform's own message as the detail, at
  the cost of one of the person's turns, kept an hour per job and failure.

In the demo profile the copilot is the `demo` adapter, rules in this process: a list question
("show the failed VMs tagged environment=prod that drifted") is the list's filter, "create a vm
called web-9" the create wizard with the image still missing, "restart web-01" its action, and an
explanation is written from the facts.

## Organization admins (decision 34)

A person is an organization admin when the identity provider's groups of their sign-in include one
of `console.organization.admin-groups`; the BFF decides it at sign-in and again at every step-up
(a person taken out of the group stops being one at their next fresh sign-in), never from a
request, and the session view says it as `organizationAdmin`. It grants nothing inside an
account. It opens `/console/v1/organization/…`, which the BFF serves by calling the identity
service's internal-only operations with its own service token, naming the person in
`X-On-Behalf-Of` (the same signed assertion the platform reads) and in a `console.audit` log line
for every write (`organization action=… target=… admin=… status=…`). Anyone else gets a 404, as the
identity service answers callers that are not the internal principal. Every organization write
needs a fresh sign-in, whatever `console.auth.step-up.actions` says: the step-up 403 names
`account:close`, `account:break-glass`, `guardrail:create`, `guardrail:update` or
`guardrail:delete`. Break-glass access always names the admin calling as `approvedBy` (the
identity service refuses a person approving their own). A guardrail write drops every cached
decision; closing an account or opening break-glass access drops that account's.

Refusals name the part of console IAM that refused them: `Denial.refusedBy` is `policy`,
`boundary` or `guardrail` (the identity service's `scope`, renamed because the BFF's `scope` is a
platform scope) with `guardrailId` for a guardrail. A boundary's or a guardrail's refusal is never
requestable and suggests no role, so the web offers no Request access for it. An allow that only a
role held for a while (an elevation or break-glass access) gave is `elevated: true` in the
session's `decisions` and is not cached.

## API clients (decision 31, the BFF's side)

The console CLI and the SDKs sign in with OAuth's device grant (RFC 8628); the BFF is the
authorization server of its own API, so no new client of the identity provider is needed.

1. The CLI posts `client_id=console-cli` (or `console-sdk`) to `POST /auth/device` and shows the
   person `user_code` (eight letters from `BCDFGHJKLMNPQRSTVWXZ`, `XXXX-XXXX`) and
   `verification_uri_complete`, the web app's `#/device` page with the code filled in. It keeps
   `device_code`, 32 random bytes, to itself.
2. The page reads the grant (`GET /console/v1/device-grants/{userCode}`: the client, where it asked
   from, when it expires) and the person approves or denies it in their signed-in browser.
   Approving needs a sign-in as fresh as a delete does (`console.auth.step-up.max-age`); a grant
   is decided once and never after its ten minutes.
3. Meanwhile the CLI polls `POST /auth/token` with the device code every `interval` seconds:
   `authorization_pending`, `slow_down` when sooner (which adds 5 s to that grant's interval),
   `access_denied`, `expired_token`, and once approved the tokens. A device code is redeemed once;
   brought back after that, it ends the API session it opened.

The tokens are a 15-minute access token and a refresh token, in an **API session** of 12 hours
from the approval (8 without a refresh). The access token is an ES256 JWT of the BFF's trust key,
header `typ: at+jwt` (RFC 9068): `iss` the BFF, `aud` `console-api` alone, `sub` the person's
subject, `sid` the API session (`aps_` and a ULID), `client_id`, `auth_time` (the browser sign-in
that approved the grant), `iat`, `exp`, `jti`. The trust slice signs it and checks it against the
current and the previous key; the key never leaves that slice. Every refresh answers a new refresh
token and ends the old one; a rotated refresh token presented again revokes the whole API session
(OAuth 2.1's reuse detection), and a refresh reads the person's memberships from the identity
service again. `POST /auth/revoke` signs a CLI out, and the person sees and signs out their CLIs
at `/console/v1/api-sessions`.

**A bearer call** is `Authorization: Bearer <access token>` on `/console/v1/**`. Its session is
the API session's person and memberships in the account `X-Console-Account` names and the region
`X-Console-Region` names (else the API session's own; an account the person is not in is a 403
`account.not-member`, an unknown region a 400). It reads no cookie and so needs no CSRF token; a
call with both a bearer token and the session cookie is a 400 `auth.ambiguous`. A token that is
expired, of a revoked or ended API session (read again at most once a `session-check`, at once on
the instance that revoked it), or not the console API's own (another key, audience or type, or an
internal token) is a 401 with `WWW-Authenticate: Bearer error="invalid_token"`. Signing in
(`/auth/login`), switching the browser session (`PUT /console/v1/session`) and deciding device
grants are browser-only: 403 `browser.only`. A bearer call's step-up time is its `auth_time`, so a
delete more than `max-age` after the approval is the usual 403 `step-up.required`, with
`detail.hint` "run `console login` again" instead of a login path.

**What is kept**: only the SHA-256 of a device code or a refresh token, compared in constant time;
no code or token is ever logged (the access log writes a user code as `{userCode}`). The stores
are in memory, or with the `mongo` profile the `device_grants` and `api_sessions` collections, each
with a TTL index (a grant ten minutes past its expiry, a session at its end). Ten sign-ins a minute
from one address at most (`proxy-hops` says how to read the address behind proxies).

**Limits**: a refresh resolves the person with their subject and the email, name and groups the
identity service answered at the approval, since the identity provider is not asked again until
the next `console login`; the identity service's own membership changes show at the next refresh. Two refreshes with one refresh
token at the same moment let one through and refuse the other (`invalid_grant`) without ending the
session, so the CLI should refresh one at a time: that token sent once more ends it. Automation
keys (`grant_type=client_credentials` with the identity service's access keys) come next.

## Service tokens (hub decision 23)

The BFF is the only issuer of internal JWTs, so every console service keeps verifying against
this service's JWKS alone. A service that calls another on its own (the search indexer reading
the state service and the accounts, the drift service) asks for a token of its own:

```
POST /internal/v1/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=<the assertion>
&audience=console-state-service
```

`audience` is required and names exactly one service (hub decision 35): a client calling two
services holds two tokens, and none or several is 400 `invalid_target`. The answer is
`{ access_token, token_type: "Bearer", expires_in: 300, audience }` (`audience` a string) with
`Cache-Control: no-store`: an ES256 token with `iss` console-bff, `aud` exactly that service,
`sub` and `svc` the service id, and no `acct`, which a service accepts only from the service
principals it lists as allowed to call it as themselves. Refusals are RFC 6749 bodies,
`{ error, error_description }`: 400 `invalid_request`, `unsupported_grant_type` or
`invalid_target`, 401 `invalid_client`.

The assertion proves who asks, in one of two ways:

* **In a cluster**, the pod's projected service-account token with audience `console-bff`
  (re-read from its file on every request, since the kubelet rotates it). The BFF checks it
  against the cluster's service-account issuer (`console.trust.kubernetes.*`) and maps
  `system:serviceaccount:<namespace>:<name>` to the client registered with that
  `service-account`. No secret exists anywhere. The chart turns this on and mounts a token for
  the API server and the cluster's CA, so the BFF can read the issuer's keys. The token may live an hour at
  most and, with `console.trust.kubernetes.single-use`, is redeemed once (by `jti`, else by its
  hash) until it expires, so a client needs a fresh service-account token for every service token.
* **Anywhere else** (a laptop, CI, a VM), a JWT the service signs with its own EC or RSA key: `iss`
  and `sub` its service id, `aud` exactly `console-bff`, `iat`, `exp` at most 5 minutes later (a minute
  is plenty) and a `jti` used once; the BFF holds the service's public keys
  (`console.trust.clients.<service>.jwks`, or `jwks-uri`). Used `jti`s are kept until they
  expire, in memory, or with the `mongo` profile in `service_token_assertions` (a TTL index), so
  an assertion is redeemed once across every replica.

Only asymmetric algorithms are accepted (ES256, ES384, RS256, PS256). A new client is a new entry
under `console.trust.clients` (in the chart, `config.serviceTokens.clients`) with the audiences it
needs. The search indexer and the cost service may ask for the state and identity services; the
drift service for those and the cost service too (`console-cost-service`, whose
`POST /v1/cost:estimate` prices what it finds). `/internal` is never routed by the ingress. The
demos keep their dev headers.

## Layout

One slice per concern under `com.iaf.console.bff`: `session`, `clients`, `trust`, `access`, `types`,
`resources`, `tags`, `resourcegroups`, `jobs`, `live`, `search`, `cost`, `scorecards`, `drift`,
`assistant`, `iam` (with the organization pages), `preferences`, `platform`, `identity`, `demo`, and `common` for the error handling and the
service pass-through (`common.relay`) shared by every slice. Each slice is `domain` (no Spring),
`ports/outbound`, `application`, `adapters/{inbound,outbound}`; `ArchitectureTest` holds the
service to the kit's rules and adds one of its own: only `..demo..` packages may depend on
`platform-fake`.

## Tests

`./gradlew test` runs 384 tests in about two minutes: unit tests of the domain and one shared demo
application on port 18181 for the endpoint tests, which sign in through the demo identity
provider like a browser would. The MongoDB stores are tested when `CONSOLE_TEST_MONGO_URI` names a
database (CI starts one).

## Deploying

`deploy/helm/console-bff` is the chart; `Dockerfile` builds the image from the boot jar. The chart
expects a secret with the session secret, the trust key, the OIDC client secret, the platform's
internal token and the MongoDB URI, and optionally the copilot's Devin API key
(`assistant-devin-api-key`; without it every copilot turn fails `assistant.unavailable`; see
`values.yaml`). The pods need egress to `config.assistant.devinBaseUrl`. Run at least two replicas
with the `mongo` profile, and the `kafka` profile where the console's Kafka is reachable. Service
tokens are on by default (`config.serviceTokens`): set `kubernetes.issuer` to the cluster's
service-account issuer (`kubectl get --raw /.well-known/openid-configuration`) and give each
client's pods a projected service-account token with audience `console-bff`. For the CLI sign-in,
set `CONSOLE_CLIENTS_VERIFICATION_URI` to the web app's `#/device` page and
`CONSOLE_CLIENTS_PROXY_HOPS` to the proxies in front (1 behind the ingress) in `config.extraEnv`.
