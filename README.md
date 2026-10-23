# console-bff

The console's backend for frontend: the one service the web app talks to. It signs people in
through the enterprise identity provider, keeps their session, tells the web app what each
resource type looks like and what the signed-in person may do with it, serves the resource pages
over the state service (list, detail, create, update, verbs, tags, lineage, drift, batches,
previews), the Requests page (jobs and approvals), streams live changes, and passes IAM calls
through to the identity service.
It never holds platform credentials in the browser: every platform call is made here, with the
account's service account and a signed on-behalf-of assertion naming the person.

Work packages of the customer-console plan served here: **1.3** (OIDC sign-in, sessions, CSRF,
step-up), **1.5** (the refusal names its layer, access requests), **3.1** (type descriptors),
**3.2** and **3.3** (the resource pages, over the state service), **3.4** (live updates), and the
BFF's side of **6.1** (global search, type-ahead and the list's counts, through the search
indexer).

## Running it

```
./gradlew bootRun --args='--spring.profiles.active=demo'
```

The demo profile needs nothing else: it embeds `platform-fake` on port 18090, a small OpenID
provider at `/demo-idp` with a user picker, and an in-process stand-in of the identity service at
`/demo-identity`, reached over HTTP by the same adapters that will talk to the real one. Open
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
use. Ports: platform-fake 18090, bff 18081, state 18082, identity 18083, search indexer 18085,
web 3000 (the project's port decision of 2026-10-23; the plan's table had state and identity the
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
| `console.session.roles-refresh-interval` | How long a session uses the memberships `principals:resolve` answered before its next request reads them again (5 minutes); never past the earliest `accounts[].elevations[].until`, and sooner after an identity change |
| `console.auth.step-up.max-age`, `actions` | Which actions need a fresh sign-in (`*:delete`, within 10 minutes) |
| `console.trust.key`, `previous-key`, `issuer` | The private EC JWK that signs internal tokens and on-behalf-of assertions; published at `/.well-known/jwks.json`. Generated at boot when empty |
| `console.trust.clients.<service>.audiences`, `service-account`, `jwks`, `jwks-uri` | The console services that may get service tokens, the services each may get them for, and how each proves who it is (see Service tokens) |
| `console.trust.kubernetes.issuer`, `jwks-uri`, `jwks`, `ca-file`, `token-file` | The cluster whose projected service-account tokens prove a client; off while `issuer` is empty |
| `console.trust.service-token-lifetime`, `client-assertion-max-lifetime` | A service token lives 5 minutes; a client's own assertion may live 5 minutes at most |
| `console.identity.url`, `console.state.url`, `console.search.url` | The identity service (`http://localhost:18083` by default), the state service (`http://localhost:18082`) and the search indexer (`http://localhost:18085`); an empty URL means the in-process stand-in the demo profile mounts (`/demo-identity`, `/demo-state`, `/demo-search`) |
| `console.platform.base-url`, `internal-token`, `accounts.*`, `partition` | The platform, the console's internal service account, and per-account service accounts (otherwise read from the identity service's `platform-credentials`) |
| `console.web.origins` | The web app's origins for CORS and the CSRF origin check |
| `console.types.cache-ttl`, `console.access.cache-ttl` | How long descriptors and policy decisions are kept (also dropped on events) |
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
  server keeps the memberships, the current account and region, and `authTime`. Switching account
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
| `GET, PUT /console/v1/session`, `GET /console/v1/accounts`, `GET /console/v1/regions` | The session, the memberships, the regions |
| `GET /console/v1/types`, `GET /console/v1/types/{key}` | The descriptors, with `ETag` and `If-None-Match`, every action with `allowed` and `denied` |
| `GET, POST /console/v1/r/{type}`, `POST …:preview`, `POST …:batch` | The account's resources of a type (`q` through the search index, `state`, `tag.<name>`, `drift`, `sort`, cursor paging), a create, its preview, a batch |
| `GET /console/v1/r/{type}:counts` | How many resources the list's filters match: `total`, `byState` for the state chips, `drift`, `cappedAt` |
| `GET /console/v1/search`, `GET /console/v1/suggest` | Global search with highlights and type-ahead, over the types the person may list |
| `GET, PATCH /console/v1/r/{type}/{wfrn}`, `POST …:{verb}`, `POST …:{verb}:preview` | One resource with `can`, `ETag` and `If-None-Match`; an update (`If-Match`); a verb of the catalog; its preview |
| `GET …/{wfrn}/activity`, `GET …/drift`, `GET …/lineage`, `PUT …/tags` | The resource's log, its open finding, what it was built from and what depends on it, its tags |
| `GET /console/v1/jobs`, `GET /console/v1/jobs/{jobId}`, `POST …:cancel` | The account's jobs, with `can` |
| `GET /console/v1/approvals`, `POST …/{approvalId}:decide` | Approvals, with `canDecide` (group membership and policy) |
| `GET /console/v1/events` | Server-sent events: `job.updated`, `resource.*`, `types.changed`, `region.changed`, `resync`; replay from `Last-Event-ID`, `wfrn` and `types` filters |
| `GET, PUT /console/v1/preferences` | Theme, density, pinned types, columns and saved views per type; versioned |
| `/console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}…` | Pass-through to the identity service for the session's account; a quota increase is asked on its quota and followed or withdrawn under `quota-requests` |
| `GET, POST /console/v1/access-requests`, `GET …/{requestId}`, `POST …:decide` | Request access from a refusal; everyone sees their own, admins and auditors the queue; admins and approver groups decide |
| `GET /.well-known/jwks.json` | The keys other services verify this service's tokens with |
| `POST /internal/v1/token` | A service token for another console service (client credentials with a JWT client assertion); in-cluster only |
| `/demo-idp/*`, `/demo-identity/*`, `/demo-state/*`, `/demo-search/*` | Demo profile only |

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
an identity change drops the cached policy decisions of its account on every instance.

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

## Search (WP 6.1, the BFF's side)

The search indexer finds; the account's policies decide what the person sees. Every call carries
an internal token for the person in the session's account and region, so the indexer never
searches another account.

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

In the demo profile the indexer is a stand-in under `/demo-search` that reads the demo state
service's records directly: the indexer's query syntax (free words and quoted phrases, `tag:`,
`type:`, `state:`, `region:`, `drift:` terms and wfrn patterns), relevance (a name equal to a word
scores 10, a prefix 6, a contained word 4, the wfrn 2, a tag or attribute 1), HTML-escaped
highlights, every sort the console offers, and exact counts.

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
  the API server and the cluster's CA, so the BFF can read the issuer's keys.
* **Anywhere else** (a laptop, CI, a VM), a JWT the service signs with its own EC or RSA key: `iss`
  and `sub` its service id, `aud` exactly `console-bff`, `iat`, `exp` at most 5 minutes later (a minute
  is plenty) and a `jti` used once; the BFF holds the service's public keys
  (`console.trust.clients.<service>.jwks`, or `jwks-uri`). Replays are refused per replica, so
  keep assertions short.

Only asymmetric algorithms are accepted (ES256, ES384, RS256, PS256). A new client is a new entry
under `console.trust.clients` (in the chart, `config.serviceTokens.clients`) with the audiences it
needs. `/internal` is never routed by the ingress. The demos keep their dev headers.

## Layout

One slice per concern under `com.iaf.console.bff`: `session`, `trust`, `access`, `types`,
`resources`, `jobs`, `live`, `iam`, `preferences`, `platform`, `identity`, `demo`. Each slice is `domain` (no Spring),
`ports/outbound`, `application`, `adapters/{inbound,outbound}`; `ArchitectureTest` holds the
service to the kit's rules and adds one of its own: only `..demo..` packages may depend on
`platform-fake`.

## Tests

`./gradlew test` runs 108 tests in about two minutes: unit tests of the domain and one shared demo
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
