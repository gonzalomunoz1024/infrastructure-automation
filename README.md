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
**3.2** and **3.3** (the resource pages, over the state service), **3.4** (live updates). Search
(4.x) is a later package and is listed as such in `RoutesMatchContractTest`.

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
use. Ports: platform-fake 18090, bff 18081, identity 18082, state 18082 as PR #7 built it (the
plan's table said 18083; `console.state.url` points wherever it runs), web 3000.

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
| `console.identity.url`, `console.state.url` | The identity and state services (`http://localhost:18082` is the state service's demo); an empty URL means the in-process stand-in the demo profile mounts (`/demo-identity`, `/demo-state`) |
| `console.platform.base-url`, `internal-token`, `accounts.*`, `partition` | The platform, the console's internal service account, and per-account service accounts (otherwise read from the identity service's `platform-credentials`) |
| `console.web.origins` | The web app's origins for CORS and the CSRF origin check |
| `console.types.cache-ttl`, `console.access.cache-ttl` | How long descriptors and policy decisions are kept (also dropped on events) |
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
| `GET, POST /console/v1/r/{type}`, `POST …:preview`, `POST …:batch` | The account's resources of a type (`q`, `state`, `tag.<name>`, `drift`, `sort`, cursor paging), a create, its preview, a batch |
| `GET, PATCH /console/v1/r/{type}/{wfrn}`, `POST …:{verb}`, `POST …:{verb}:preview` | One resource with `can`, `ETag` and `If-None-Match`; an update (`If-Match`); a verb of the catalog; its preview |
| `GET …/{wfrn}/activity`, `GET …/drift`, `GET …/lineage`, `PUT …/tags` | The resource's log, its open finding, what it was built from and what depends on it, its tags |
| `GET /console/v1/jobs`, `GET /console/v1/jobs/{jobId}`, `POST …:cancel` | The account's jobs, with `can` |
| `GET /console/v1/approvals`, `POST …/{approvalId}:decide` | Approvals, with `canDecide` (group membership and policy) |
| `GET /console/v1/events` | Server-sent events: `job.updated`, `resource.*`, `types.changed`, `region.changed`, `resync`; replay from `Last-Event-ID`, `wfrn` and `types` filters |
| `GET, PUT /console/v1/preferences` | Theme, density, pinned types, columns and saved views per type; versioned |
| `/console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}…` | Pass-through to the identity service for the session's account; a quota increase is asked on its quota and followed or withdrawn under `quota-requests` |
| `GET, POST /console/v1/access-requests`, `GET …/{requestId}`, `POST …:decide` | Request access from a refusal; everyone sees their own, admins and auditors the queue; admins and approver groups decide |
| `GET /.well-known/jwks.json` | The keys other services verify this service's tokens with |
| `/demo-idp/*`, `/demo-identity/*`, `/demo-state/*` | Demo profile only |

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

## Layout

One slice per concern under `com.iaf.console.bff`: `session`, `trust`, `access`, `types`,
`resources`, `jobs`, `live`, `iam`, `preferences`, `platform`, `identity`, `demo`. Each slice is `domain` (no Spring),
`ports/outbound`, `application`, `adapters/{inbound,outbound}`; `ArchitectureTest` holds the
service to the kit's rules and adds one of its own: only `..demo..` packages may depend on
`platform-fake`.

## Tests

`./gradlew test` runs 83 tests in about two minutes: unit tests of the domain and one shared demo
application on port 18181 for the endpoint tests, which sign in through the demo identity
provider like a browser would. The MongoDB stores are tested when `CONSOLE_TEST_MONGO_URI` names a
database (CI starts one).

## Deploying

`deploy/helm/console-bff` is the chart; `Dockerfile` builds the image from the boot jar. The chart
expects a secret with the session secret, the trust key, the OIDC client secret, the platform's
internal token and the MongoDB URI (see `values.yaml`). Run at least two replicas with the `mongo`
profile, and the `kafka` profile where the console's Kafka is reachable.
