# console-bff

The console's backend for frontend: the one service the web app talks to. It signs people in
through the enterprise identity provider, keeps their session, tells the web app what each
resource type looks like and what the signed-in person may do with it, serves the resource pages
over the state service (list, detail, create, update, verbs, tags, lineage, drift, batches,
previews), changes them safely (blast radius, required tags, bulk in waves, retry with a fix,
the Tag Editor, resource groups, launch more like this), the Requests page (jobs and
approvals), cost and budgets (through the cost service, with budgets guarding creates),
compliance scorecards (through the drift service), streams live changes, and passes IAM calls
through to the identity service.
It never holds platform credentials in the browser: every platform call is made here, with the
account's service account and a signed on-behalf-of assertion naming the person.

Work packages of the customer-console plan served here: **1.3** (OIDC sign-in, sessions, CSRF,
step-up), **1.5** (the refusal names its layer, access requests), **3.1** (type descriptors),
**3.2** and **3.3** (the resource pages, over the state service), **3.4** (live updates), the
BFF's side of **6.1** (global search, type-ahead and the list's counts, through the search
indexer, and since decision 27 plain English and grouped counts), and phase 4: **4.1** (the
update convention), **4.2** (delete with dependents, bulk in waves, retry, skip and abort),
**4.3** (tags, the Tag Editor, resource groups), **4.4** (required tags at create), **4.5**
(blast radius) and **4.6** (launch more like this); the BFF's side of cost and budgets
(decision 25: Cost Explorer, price lists, chargeback, budgets, budget checks on creates,
`budget.alert`) and of the compliance scorecards (**9.6**).

## Running it

```
./gradlew bootRun --args='--spring.profiles.active=demo'
```

The demo profile needs nothing else: it embeds `platform-fake` on port 18090, a small OpenID
provider at `/demo-idp` with a user picker, and an in-process stand-in of the identity service at
`/demo-identity`, reached over HTTP by the same adapters that will talk to the real one (likewise
the state service, the search indexer, the cost service and the drift service's scorecards under
`/demo-state`, `/demo-search`, `/demo-cost` and `/demo-drift`). Open
<http://localhost:18081/auth/login?returnTo=/> and pick a person:

| Person | Compute account 123456789012 | Data account 210987654321 |
| --- | --- | --- |
| gonzalo | account-admin (`owners`) | account-admin (`owners`) |
| dana | operator (`members`), in `compute-approvers` | |
| mateo | operator (`members`) | |
| mia | viewer (`viewers`), in `compute-approvers` | |
| raj | | operator (`members`), in `data-approvers` |
| ana | | operator and viewer (`members`, `viewers`) |
| noah | no account: the sign-in answers 403 | |

`GET /auth/login?login_hint=dana` skips the picker, which is what the web app's Playwright runs
use. Ports: platform-fake 18090, bff 18081, state 18082, identity 18083, drift 18084, search
indexer 18085, cost 18086, web 3000 (the project's port decision of 2026-10-23; the plan's table had state and identity the
other way round).

To run against the real services, leave the demo profile off and set the settings below; the
`mongo` profile keeps sessions and preferences in MongoDB (needed for more than one replica) and
the `kafka` profile listens to the console's own events (identity changes drop cached decisions).

## Settings

Every setting has a production-safe default in `application.yaml`; set them as environment
variables (`console.oidc.issuer` is `CONSOLE_OIDC_ISSUER`).

| Setting | What it is |
| --- | --- |
| `console.oidc.issuer`, `client-id`, `client-secret`, `redirect-uri`, `scopes`, `groups-claim` | The enterprise identity provider; discovery is read from `<issuer>/.well-known/openid-configuration` |
| `console.session.secret` | Base64, at least 32 bytes; seals the login ticket and signs CSRF tokens. Generated at boot when empty, which only suits one replica |
| `console.session.ttl`, `idle-timeout`, `secure`, `same-site`, `cookie-name` | The session cookie: 12 h, 1 h idle, `Secure`, `Lax`, `console_session` |
| `console.auth.step-up.max-age`, `actions` | Which actions need a fresh sign-in (`*:delete`, within 10 minutes) |
| `console.trust.key`, `previous-key`, `issuer` | The private EC JWK that signs internal tokens and on-behalf-of assertions; published at `/.well-known/jwks.json`. Generated at boot when empty |
| `console.trust.clients.<service>.audiences`, `service-account`, `jwks`, `jwks-uri` | The console services that may get service tokens, the services each may get them for, and how each proves who it is (see Service tokens) |
| `console.trust.kubernetes.issuer`, `jwks-uri`, `jwks`, `ca-file`, `token-file` | The cluster whose projected service-account tokens prove a client; off while `issuer` is empty |
| `console.trust.service-token-lifetime`, `client-assertion-max-lifetime` | A service token lives 5 minutes; a client's own assertion may live 5 minutes at most |
| `console.identity.url`, `console.state.url`, `console.search.url` | The identity service (`http://localhost:18083` by default), the state service (`http://localhost:18082`) and the search indexer (`http://localhost:18085`); an empty URL means the in-process stand-in the demo profile mounts (`/demo-identity`, `/demo-state`, `/demo-search`) |
| `console.cost.url`, `service-name`, `timeout`, `check-timeout` | The cost service (`http://localhost:18086`; empty: `/demo-cost`), its token audience, and how long a create waits for the budget check and estimate (2 s; past it the create goes on without them) |
| `console.drift.url`, `service-name`, `timeout` | The drift service, for the scorecards and the executive home's findings stats (`http://localhost:18084`; empty: `/demo-drift`, which serves scorecards only, so the demo's executive home counts no findings) |
| `console.insights.baseline.source`, `lead-time`, `by-workflow.<workflow>` | The ticket baseline the executive home compares lead times with: where it comes from, the median ticket overall, and per workflow. With no lead time at all, hours saved is left out |
| `console.insights.cache-ttl`, `max-jobs-per-account`, `concurrency` | How long an executive home answer is kept (5 minutes), the most requests one account counts in a period (10000), and how many accounts are read at once (8) |
| `console.platform.base-url`, `internal-token`, `accounts.*`, `partition` | The platform, the console's internal service account, and per-account service accounts (otherwise read from the identity service's `platform-credentials`) |
| `console.web.origins` | The web app's origins for CORS and the CSRF origin check |
| `console.types.cache-ttl`, `console.access.cache-ttl` | How long descriptors and policy decisions are kept (also dropped on events) |
| `console.tags.required`, `console.tags.accounts.<accountId>.<key>`, `console.tags.account-ttl` | The tags every create must name (key, title, values or pattern, default, `default-from`: `account.owning-team`, `account` or `input.<field>`), an account's own default values, and how long an account's owning team is kept |
| `console.live.ring-capacity`, `heartbeat`, `relay.enabled` | The replay ring per account, the SSE heartbeat, and whether this instance follows the platform feed |
| `console.platform.fake.*` | Demo only: the embedded platform's port, step delay and seed |
| `console.kafka.*` | With the `kafka` profile: bootstrap servers, group id, topics |

## Security model

* **Sign-in** is the authorization-code flow with PKCE (S256), run by this service. The state,
  verifier, nonce, return path and (for a step-up) the session id travel in a sealed cookie
  (`console_login`, AES-256-GCM, path `/auth`, 10 minutes), so the callback may land on any
  replica. The ID token is verified against the provider's JWKS (issuer, audience, expiry, nonce).
* **The principal** is resolved by the identity service (`POST /v1/principals:resolve`): who the
  person is in the console and which accounts they belong to, with their managed roles. No account
  means 403 at sign-in.
* **The session** is an opaque random id in an `HttpOnly`, `SameSite=Lax`, `Secure` cookie; the
  server keeps the memberships, the current account and region, and `authTime`. Switching account
  or region is `PUT /console/v1/session`.
* **CSRF**: every write under `/console/` and the logout need `X-Csrf-Token`, an HMAC of the
  session id, which `GET /console/v1/session` hands out; when the browser sends an `Origin` it
  must be the web app's or this service's own. A missing token is the one 403 without a `denied`
  block.
* **Step-up**: destructive actions within the configured max age need a fresh authentication:
  the API answers 403 with `detail.stepUp` and a login path; `/auth/login?stepUp=true` re-runs the
  provider with `prompt=login&max_age=0` and refreshes the same session's `authTime`.
* **Downstream calls** carry a 60-second ES256 JWT minted here (`iss` console-bff, `aud` the
  target service, `sub`, `acct`, `region`, `groups`) that the identity and state services verify
  against `/.well-known/jwks.json`. Platform commands carry the account's service-account token and
  a P-02 on-behalf-of assertion signed with the same key.
* **Service tokens** (hub decision 23): a console service that calls another on its own gets a
  token from `POST /internal/v1/token`, never a copy of the signing key or a static token. See
  Service tokens below.
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
| `GET, PUT /console/v1/session`, `GET /console/v1/accounts`, `GET /console/v1/regions` | The session (with `decisions` on the account's IAM writes, quota and access requests), the memberships, the regions |
| `GET /console/v1/types`, `GET /console/v1/types/{key}` | The descriptors, with `ETag` and `If-None-Match`, every action with `allowed` and `denied` |
| `GET, POST /console/v1/r/{type}`, `POST …:preview`, `POST …:batch`, `POST …:batch:preview` | The account's resources of a type (`q` through the search index, `state`, `tag.<name>`, `drift`, `sort`, cursor paging), a create with its required tags, its preview, a batch in waves, the batch's preview with each row's problems and what the rows reach |
| `GET /console/v1/r/{type}:counts` | How many resources the list's filters match: `total`, `byState` for the state chips, `drift`, `cappedAt` |
| `GET /console/v1/search`, `GET /console/v1/suggest` | Global search with highlights and `didYouMean`, and type-ahead, over the types the person may list |
| `GET /console/v1/search:interpret` | Plain English read as the list's own filter (the indexer's reading: `q`, `type`, `filter`, `sort`, `explanation`, `confidence`, `unparsed`, `total`) |
| `GET /console/v1/search:counts` | Resources counted by one grouping (`groupBy`) with a search's filters, in the session's account or across the person's accounts (`scope=organization`), with `asOf` |
| `GET, PATCH /console/v1/r/{type}/{wfrn}`, `POST …:{verb}`, `POST …:{verb}:preview` | One resource with `can`, `ETag` and `If-None-Match`; an update (`If-Match`); a verb of the catalog; its preview |
| `GET …/{wfrn}/activity`, `GET …/drift`, `GET …/lineage`, `PUT …/tags` | The resource's log, its open finding, what it was built from and what depends on it, its tags |
| `GET …/{wfrn}/blast-radius`, `GET …/{wfrn}/clone` | What depends on the resource, in every account (named in the session's); its create form filled from it, for this account or another |
| `GET /console/v1/tag-policy`, `GET /console/v1/tags/resources`, `POST /console/v1/tags:apply` | The account's required tags with their defaults; the Tag Editor's search across types by tag, and tagging up to 100 resources at once |
| `GET, POST /console/v1/resource-groups`, `GET, PATCH, DELETE …/{groupId}`, `GET …/{groupId}/resources` | Resource groups: tag queries per account and region (unique names, `ETag` and `If-Match`, `Idempotency-Key` on create) and the resources each one matches |
| `GET /console/v1/jobs`, `GET /console/v1/jobs/{jobId}`, `POST …:cancel`, `…:retry`, `…:skip`, `…:abort` | The account's jobs, with `can`, a batch's waves and rows; retry (optionally with a fix: input and step), skip a paused step, abort with compensations |
| `POST /console/v1/jobs/{jobId}/approvals:decide` | Every pending approval of a request and its rows the person may decide, in one call (once per wave) |
| `GET /console/v1/approvals`, `POST …/{approvalId}:decide` | Approvals, with `canDecide` (group membership and policy) |
| `GET /console/v1/insights` | The executive home: resources managed, requests and how many were self-served, lead time against tickets, hours saved, drift found and fixed, adoption week by week |
| `GET /console/v1/jobs/{jobId}/explanation` | Why a job failed and what to do next: the cause, the fix and the step to run again from, whether the person may retry, how often it happened and what fixed it last |
| `GET /console/v1/events` | Server-sent events: `job.updated`, `resource.*`, `types.changed`, `region.changed`, `resync`; replay from `Last-Event-ID`, `wfrn` and `types` filters |
| `GET, PUT /console/v1/preferences` | Theme, density, pinned types, columns and saved views per type; versioned |
| `/console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}…` | Pass-through to the identity service for the session's account; a quota increase is asked on its quota and followed or withdrawn under `quota-requests` |
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
&audience=console-state-service&audience=console-identity-service
```

The answer is `{ access_token, token_type: "Bearer", expires_in: 300, audience }` with
`Cache-Control: no-store`: an ES256 token with `iss` console-bff, `aud` only the services asked
for (every registered one when none is), `sub` and `svc` the service id, and no `acct`, which the
other services treat as the console-internal principal. Refusals are RFC 6749 bodies,
`{ error, error_description }`: 400 `invalid_request`, `unsupported_grant_type` or
`invalid_target`, 401 `invalid_client`.

The assertion proves who asks, in one of two ways:

* **In a cluster**, the pod's projected service-account token with audience `console-bff`
  (re-read from its file on every request, since the kubelet rotates it). The BFF checks it
  against the cluster's service-account issuer (`console.trust.kubernetes.*`) and maps
  `system:serviceaccount:<namespace>:<name>` to the client registered with that
  `service-account`. No secret exists anywhere. The chart turns this on and mounts a token for
  the API server and the cluster's CA, so the BFF can read the issuer's keys.
* **Anywhere else** (a laptop, CI, a VM), a JWT the service signs with its own EC or RSA key: `iss`
  and `sub` its service id, `aud` `console-bff`, `iat`, `exp` at most 5 minutes later (a minute
  is plenty) and a `jti` used once; the BFF holds the service's public keys
  (`console.trust.clients.<service>.jwks`, or `jwks-uri`). Replays are refused per replica, so
  keep assertions short.

Only asymmetric algorithms are accepted (ES256, ES384, RS256, PS256). A new client is a new entry
under `console.trust.clients` (in the chart, `config.serviceTokens.clients`) with the audiences it
needs. `/internal` is never routed by the ingress. The demos keep their dev headers.

## Layout

One slice per concern under `com.iaf.console.bff`: `session`, `trust`, `access`, `types`,
`resources`, `tags`, `resourcegroups`, `jobs`, `live`, `search`, `cost`, `scorecards`, `iam`,
`preferences`, `platform`, `identity`, `demo`, and `common` for the error handling and the
service pass-through (`common.relay`) shared by every slice. Each slice is `domain` (no Spring),
`ports/outbound`, `application`, `adapters/{inbound,outbound}`; `ArchitectureTest` holds the
service to the kit's rules and adds one of its own: only `..demo..` packages may depend on
`platform-fake`.

## Tests

`./gradlew test` runs 294 tests in about two minutes: unit tests of the domain and one shared demo
application on port 18181 for the endpoint tests, which sign in through the demo identity
provider like a browser would. The MongoDB stores are tested when `CONSOLE_TEST_MONGO_URI` names a
database (CI starts one).

## Deploying

`deploy/helm/console-bff` is the chart; `Dockerfile` builds the image from the boot jar. The chart
expects a secret with the session secret, the trust key, the OIDC client secret, the platform's
internal token and the MongoDB URI (see `values.yaml`). Run at least two replicas with the `mongo`
profile, and the `kafka` profile where the console's Kafka is reachable. Service tokens are on by
default (`config.serviceTokens`): set `kubernetes.issuer` to the cluster's service-account issuer
(`kubectl get --raw /.well-known/openid-configuration`) and give each client's pods a projected
service-account token with audience `console-bff`.
