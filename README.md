# console-identity-service

The identity service of the customer console: accounts, users and their memberships, groups,
roles, policies, access keys and quotas, and the authorization decisions every other console
service asks for (`POST /v1/authorize`), compiled into an OPA bundle.

This repository is one branch (`console-identity-service`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`; the shared Java code comes from `console-libs`
as `com.iaf.console:*`.

## What it serves

Everything in `contracts/openapi/console-identity-service.yaml` (the copy under
`src/main/resources/contracts`, pinned in `contracts.lock`; `ContractsLockTest` fails when the copy
drifts and `ContractCoverageTest` when an operation is not served):

| Area | Operations |
|---|---|
| Accounts | `GET/POST /v1/accounts`, `GET/PATCH /v1/accounts/{accountId}`, `POST /v1/accounts/{accountId}:close` (internal), `GET .../platform-credentials` (internal) |
| Principals | `POST /v1/principals:resolve` (internal: the BFF at sign-in), `POST /internal/v1/group-sync:run` (internal) |
| Users, groups, roles, policies | `GET/POST .../users`, `GET/PATCH/DELETE .../users/{userId}`; the same for `groups`, `roles` and `policies` (`PUT` replaces a policy) |
| Access keys | `GET/POST .../access-keys`, `GET/DELETE .../access-keys/{keyId}`, `POST .../access-keys/{keyId}:rotate` |
| Quotas | `GET .../quotas`, `GET/PUT .../quotas/{quotaKey}`, `POST .../quotas/{quotaKey}:request-increase`, `GET/DELETE .../quota-requests/{requestId}`, `POST .../quota-requests/{requestId}:decide` (internal), `GET /v1/quota-rules` |
| Guardrails | `GET/POST /v1/guardrails`, `GET/PATCH/DELETE /v1/guardrails/{guardrailId}` (internal), `GET .../guardrails` (the ones that apply to the account) |
| Access reviews | `GET/POST .../access-reviews`, `GET .../access-reviews/{reviewId}`, `GET .../access-reviews/{reviewId}/items`, `POST .../access-reviews/{reviewId}/items/{itemId}:decide`, `POST .../access-reviews/{reviewId}:cancel` |
| Elevations | `GET /v1/elevation-rules`, `GET/POST .../elevations`, `GET .../elevations/{elevationId}`, `POST .../elevations/{elevationId}:decide`, `POST .../elevations/{elevationId}:revoke`, `POST .../break-glass` (internal) |
| Authorization | `POST /v1/authorize`, `POST .../policy-simulations`, `GET /internal/opa/bundles/console-authz.tar.gz` |

Access requests (`GET/POST .../access-requests`, `GET .../access-requests/{requestId}`,
`POST .../access-requests/{requestId}:decide`) are served as the contract defines them too. The
pin is hub `fce1187` (hub PR #22, decision 28, which merges the cost contract of #18); nothing is
served beyond it (`ContractCoverageTest` fails on an operation the contract lacks, so a new one goes
to the hub first).

## How it decides

- **Principals.** The BFF signs the internal JWT (`iss console-bff`, `aud console-identity-service`,
  ES256 with a `kid`, verified against its JWKS, at most `max-lifetime` long). A user token carries
  `acct`, `sub` (the console user id), `region`, `groups` (group names) and `roles` (the managed
  role names, from the membership `principals:resolve` serves); a service token carries `svc` and
  no account. The caller keeps both lists, but this service decides from the live membership and
  policies, not from the token: the managed `account-admin` policy allows everything, so whoever
  holds `account-admin` passes every admin check here, leaving `owners` counts at once even while
  an older token still lists the role, and no check tests a group name. `AccountMembership.roles` lists managed role names
  only (a custom role shows through its group). The demo's dev headers are `X-User`, `X-Account`,
  `X-Groups` and `X-Roles`.
  A user never sees another account: anything outside their accounts is a 404, never a 403.
  Service principals (the BFF, the state service) are allowed everything in their own calls.
- **Managed IAM.** Every account gets four built-in roles, `viewer`, `operator`, `account-admin`
  and `auditor` (each with one managed policy, immutable), and four built-in groups, `owners`
  (account-admin), `members` (operator; the owning IdP team joins it at sign-in), `viewers` and
  `auditors`. Built-in groups keep their names, `owners` keeps the account-admin role and at least
  one active member, and cannot be deleted. Custom groups, roles and policies are free. Every
  managed role reads cost and budgets; `cost:export` (the chargeback) is account-admin's and
  auditor's, and budget writes are account-admin's alone (hub decision 25). Managed policies follow
  the release: at start `ManagedPolicyUpgrade` replaces, in place, each managed policy of an
  account that is not closed which differs from `ManagedIam`'s, with a `PolicyChanged` `updated`
  by `svc:console-identity-service`, so a change to a managed role reaches older accounts.
- **Policies.** AWS-shaped statements: `effect`, `actions` (`<service>:<verb>`, wildcards on
  either side), optional `notActions` on allow statements, `resources` (wfrn patterns) and
  `conditions` (`region`, `account`, `service`, `type`, `requester.user`, `requester.email`,
  `requester.group`, `tag.<name>`, `context.<name>`). Actions are validated against the platform's
  resource types and catalog plus the console's own services (`iam`, `account`, `quota`, `audit`,
  `cost`, `budgets`, `search`, `access-request`, `access-review`, `elevation`). A deny wins; without an allow the decision says which managed
  role would have allowed it (`requestable`, `suggestedRole`), so the console can offer an access
  request.
- **Permission boundaries.** A role may name one of the account's policies as its `boundary`:
  its allows count only where the boundary allows too (no deny of the boundary matches, an allow
  does), so an admin can let a team write its own roles without letting it grant more than the
  boundary. Another role's allow still counts. A refusal for this reason has `scope: boundary`,
  names the boundary as `policyId` and is not requestable. `boundary: ""` removes it; the
  `account-admin` role takes none (it is how an account undoes a boundary that went too far), and
  a policy that bounds a role cannot be deleted.
- **Guardrails.** The organization's statements over every role of the accounts they target
  (`accounts: ["*"]`, now and later, or account ids), as AWS's service control policies are. Only
  the internal principal writes them (the BFF, for an organization admin; anyone else gets a 404).
  A matching deny refuses whatever a role allows; once any guardrail of an account allows
  something, an action none of them allows is refused ("outside the organization's guardrails").
  They are read before the account's policies, so the reason names them; the refusal has
  `scope: guardrail` (and `guardrailId` for a deny) and is not requestable. Every change is a
  `GuardrailChanged` in each account it touches (`deleted` where it no longer applies), which also
  moves those accounts' versions. An account's people who may `iam:list` see the ones that apply
  there.
- **Policy simulator.** `POST .../policy-simulations` answers checks for a person, a role (by id
  or name) or a set of groups (by id or name) exactly as `/v1/authorize` would, over the current
  data with up to ten unsaved policies laid over it and, for a role, a draft boundary in place of
  its own. Nothing is written. Unsaved documents show as `simulated:policy-<n>` and
  `simulated:boundary` in the results. It needs `iam:get` on the person or the roles, `iam:list`
  for groups.
- **One evaluator, two engines.** `authz/domain/Evaluator` decides in process; the same policy is
  written in Rego (`src/main/resources/opa/console/authz.rego`) and served as a bundle with the
  compiled accounts as data. The `opa` profile makes the service ask an OPA that polls the bundle
  instead. `OpaConformanceTest` and `BundlesTest` run every fixture case through both and fail on
  the first difference. `AuthorizeResponse.version` is the account's version; the bundle revision
  (and its ETag) is the global revision, bumped on every identity change.
- **Refusals.** A 403 carries `denied: {layer, action, resource, reason, role?, policyId?,
  statement?, requestable, suggestedRole?, scope?, guardrailId?}` so the BFF can show why, and
  what refused it (a policy, a role's boundary or a guardrail), and offer the request.
- **Access requests.** A person asks for a role after a refusal (`{action, resource?, role?, reason}`;
  without `role` the managed role the denial suggests). The request goes to the members of the
  groups whose policies allow `access-request:decide` (the owners, plus any custom approver group);
  approving joins the requester to the group that carries the role. Nobody decides their own
  request: the requester is left out of its approvers, and their `:decide` is a 403 whose denial
  says so. Everyone sees their own
  requests; the account's are for whoever may `access-request:list` (admins, auditors, approver
  groups: the managed viewer and operator policies leave those reads out). The same open request answers
  409 with the existing one; a pending request expires after thirty days (a sweep every fifteen
  minutes stores it). Every step publishes `AccessRequestChanged` (`created`, `approved`,
  `rejected`, `expired`) and an approval the `GroupChanged` of the group joined.
- **Access reviews.** An account admin starts a campaign (`POST .../access-reviews`,
  `{name?, dueInDays?}`, due in 1 to 90 days, 14 by default): one item per person and group of the
  account's active memberships (suspended people are left out), with the role names the group
  carries. Admins keep or revoke each item (`POST .../access-reviews/{reviewId}/items/{itemId}:decide`,
  `{decision, comment?}`); a revoke takes the group off the person the way the users API does, so
  the owners guardrail holds and a `UserChanged` follows (the last active owner's owners group is a
  409), and a person who no longer holds the group is recorded as revoked without a change. A
  decided item is final, and nobody decides an item about themselves: their `:decide` is a 403
  whose denial says so. The campaign closes when every item is decided; `:cancel` stops it and
  leaves its pending items pending. Past the due date it takes neither (a 409), so nothing dodges
  the expiry. An account has one open campaign at a time (a second start is
  a 409 naming it in `detail.reviewId`). A sweep every fifteen minutes closes the campaigns past
  their due date, each pending item expiring as a revoke decided by `system:access-review` (the
  last active owner's owners item is kept, with a comment), and starts a `scheduled` campaign, by
  `system:access-review`, on every active account with none open whose last campaign started a
  cadence ago (or, with none yet, that was created a cadence ago). The settings are under
  `console.identity.access-reviews` (`sweep-every` 15 minutes, `scheduled.enabled` true,
  `scheduled.cadence` 90 days, `scheduled.due-in-days` 14). The reviews are the console service
  `access-review` (`get`, `list`, `create`, `decide`, `cancel`): the managed account-admin policy
  allows them all, the auditor policy the reads, and the viewer and operator policies leave
  `access-review:*` out (accounts created before then are brought up to date at start, as managed
  policies are). Every change publishes `AccessReviewChanged`
  (`created`; `updated` on each decision, with the item, and on cancel; `expired` when the due date
  closes it) with the campaign and its counts. The code is the `accessreviews` slice.
- **Just-in-time elevation.** A person asks to hold one of the account's roles for a while
  (`POST .../elevations` with `role`, `reason` and `durationMinutes`, 5 to 480, 60 by default;
  `admin` reads as `account-admin`). The first auto-approval rule that covers it
  (`console.identity.elevations.auto-approval`, served at `GET /v1/elevation-rules`; by default an
  operator may be account-admin for up to two hours) approves it at once (`decidedBy`
  `rule:<name>`); otherwise it waits, `pending`, for someone who may `elevation:decide` (its
  `approvers` lists who may now, never the requester, who can never decide their own) and expires
  undecided after `pending-for` (24 hours). One open ask or elevation per person and role (409
  with it); a role the person holds through a group is a 400. While active the role decides as a
  role from a group does, inside the guardrails and the role's boundary, after the person's own
  roles, so a decision says `elevated` only when the time-boxed role alone allows it. It stops
  deciding at `until` by itself (the compiled data carries `grants` with their `until`, and both
  the evaluator and the Rego compare it with now); a sweep (`sweep-every` one minute) then stores
  it `expired`. The person or an admin (`elevation:revoke`) ends it early (`revoked`; a pending ask
  is `cancelled`), and a person who leaves the account loses theirs. Sign-in
  (`POST /v1/principals:resolve`) adds the role to the account's `roles` and lists it in
  `elevations` with its `until`, so the BFF refreshes a session on `ElevationChanged` and never
  lets a token outlive it. The managed viewer and operator policies may ask (`elevation:create`)
  and read their own; auditors read them all; account admins decide and revoke.
- **Break-glass support access.** The BFF (`POST .../break-glass`, internal only) opens time-boxed
  access for support or an emergency: a `reason`, a named `approvedBy` who is not the person, an
  optional `ticket`, at most `break-glass-max-minutes` (240), `account-admin` by default. It is
  the same record, of kind `break-glass`, active at once, and it works for people outside the
  account: the account then appears in their sign-in with no groups. It ends like an elevation,
  and closing the account ends every open one. Every change of either publishes
  `ElevationChanged` (`created`, `approved`, `rejected`, `cancelled`, `revoked`, `expired`) on the
  account's own trail. The code is the `elevations` slice.

## Accounts, keys, quotas, events

- Creating an account (internal only) mints a twelve-digit id, the managed IAM, the first owner
  from `ownerEmail`, and asks the platform for the account's console service account (P-03). Its
  token is kept by the secrets adapter (`vault` profile: Vault; otherwise memory) and is never in
  MongoDB, an event or a log; `GET .../platform-credentials` hands it to internal callers.
- Account lifecycle. A membership knows which of its groups the person holds through their identity
  provider (`Group.idpGroups`; `members` maps the owning team): `User.idpGroups` lists them, and
  sign-in (`principals:resolve` with `groups`) and the group sync add the mapped groups and take
  back the ones whose provider group the person left, in every account they are in. An admin's or
  an access review's change wins over the provider: a mapped group taken away is listed in
  `excludedGroups` and stays out until an admin gives it back, and a group an admin gives is never
  removed by a sync. No sync takes `owners` from the account's last active owner; it skips and
  reports it. The group sync runs nightly (`console.identity.lifecycle.group-sync`, 02:00 UTC by
  default) and on `POST /internal/v1/group-sync:run` (internal), which answers the report per
  account (added, removed, skipped, missing provider groups). It reads each provider group once per
  run from the directory (in memory, seeded with the demo's teams; SCIM 2.0 with the `scim` profile,
  `CONSOLE_SCIM_URL` and `CONSOLE_SCIM_TOKEN`, the token never logged), matches people by email,
  gives no one a membership, and marks an account `owningTeamMissing` while its owning team is not
  in the directory. `POST /v1/accounts/{accountId}:close` (internal, `{reason}`, 202) closes an
  account for good: every call in it is refused from then on (`account closed`) and sign-in leaves
  it out; its usable access keys are revoked, its pending access requests expire, its open elevations and break-glass access end, and its open quota
  requests are withdrawn, each with its event, then `AccountChanged closed`. A user's `PATCH` of an
  account takes `account:update` (account admins), so only internal callers change a suspended or
  closed account; a `PATCH` never sets `closed` and no longer changes a closed account's status
  (`suspended` stays reversible), closing twice is a 409, no key is minted in an account that is not
  active, and the platform service account stays as it is (the platform cannot disable one). An
  account may carry a `costCentre` (at most 64 characters, `""` clears it): the cost service groups
  chargeback by it, and by `owningTeam` when there is none.
- Access keys: `ck_` id plus a 43-character secret shown once; at most two usable keys per user;
  rotation keeps the previous secret for ten minutes; a removed user's keys are revoked.
- Quotas: `jobs-per-minute` reads through the platform's rate bucket (an override moves the
  bucket too), `access-keys` counts usable keys (default 50), `resources.<type>` defaults to 100
  and is listed once per platform resource type, by the type's name (`resources.vm`,
  `resources.database`; used counts arrive from the state service later). Users request increases
  above the current limit (one open request per key, answered as a `QuotaRequest` with
  `currentLimit` and `requestedLimit`; an ask at or below the limit is a 400, `not-an-increase`).
  The first auto-approval rule (`console.identity.quotas.auto-approval`, served at
  `GET /v1/quota-rules`) that covers an ask approves it as it is made, setting the limit as `PUT`
  does, with `decidedBy` `rule:<name>`; a rule covers key patterns (`resources.*`), approves at
  most `max-factor` times the current limit and never above `max-limit`, and once it has approved
  an account's key no rule approves that key again for the `cooldown`. The defaults double
  `resources.*` (up to 1,000) and `access-keys` (up to 100) once every 30 days and raise
  `jobs-per-minute` by half (up to 600) once a week. Any other ask is routed to the approver
  (`routedTo: platform-team`) and stays open. The requester, an account admin (`quota:set`) or
  the internal principal withdraws an open one (`DELETE`, 204). The
  platform team decides with the internal `:decide` (`{decision, limit?, comment?}`): approving
  sets the limit granted (the ask by default, possibly less or more) exactly as `PUT` does,
  keeping an override's burst while it still covers the limit, and records `grantedLimit`;
  rejecting leaves the limit and records the comment. An internal `PUT` at or above an open ask
  approves it (it is moot); a lower limit leaves it open.
- Every change appends an `identity.changes` event (`AccountChanged`, `UserChanged`,
  `GroupChanged`, `RoleChanged`, `PolicyChanged`, `AccessKeyChanged`, `QuotaChanged`,
  `AccessRequestChanged`, `GuardrailChanged`, `AccessReviewChanged`, `ElevationChanged`) in the
  platform envelope: key `<type>:<accountId>:<subjectId>:<version>`, partition key the account id,
  `data: {version, action, snapshot}`. The outbox relay publishes to Kafka with the `kafka` profile.
  The outbox takes an event whose key it has seen for a replay, so every change moves its
  subject's version. A `QuotaChanged` has its own counter per account and key (a limit set and a
  request opened, decided or withdrawn each move it); its snapshot is the quota as served plus,
  when the change is about one, the `request`, and its action is `approved` or `rejected` when a
  request was decided (by `:decide`, or a moot `PUT`) and `updated` otherwise.
- Writes take `If-Match` (a version, bare or quoted; 409 when stale) and `Idempotency-Key` (a replay
  answers the first response with `Idempotent-Replay: true`; another body under the same key is 409).
- Pages are `{items, next}`, `limit` 1 to 200, 50 by default.

## Running it

The service listens on 18083 (`CONSOLE_IDENTITY_PORT` overrides it). The state service has 18082,
the BFF 18081 and platform-fake-app 18090.

```bash
# the libraries, once (until console-libs is on GitHub Packages)
cd ../console-libs && ./gradlew publishToMavenLocal :build-logic:publishToMavenLocal -x test

./gradlew build                                          # tests included; OPA tests need CONSOLE_OPA_BIN or opa on the PATH
./gradlew bootRun --args='--spring.profiles.active=demo'  # platform-fake-app on 18090, the BFF's JWKS on 18081, dev headers on
./gradlew bootRun --args='--spring.profiles.active=demo,fake'  # one JVM: the fake platform embedded, no other process needed
```

Profiles: `mongo` (MongoDB, `CONSOLE_MONGO_URI`), `kafka` (`CONSOLE_KAFKA_BOOTSTRAP`), `opa`
(`CONSOLE_OPA_URL`), `vault`, `demo` (dev headers, the demo seed, platform-fake on 18090), `fake`
(platform-fake in this process). Without `mongo` everything is in memory, so a restart starts empty.

The demo seed creates the accounts `123456789012` (Compute, team-compute) and `210987654321` (Data,
team-data) and the people the console and the BFF mocks know, with ids `user_<name>` linked to
the demo identity provider (`http://localhost:18081/demo-idp`): gonzalo owns both accounts;
dana and mateo are operators and mia a viewer of Compute; raj is an operator and ana an operator
and viewer of Data. Each account has a custom `approver` role (policy `approver`: read and decide
access requests) held by `compute-approvers` (dana, mia) and `data-approvers` (raj). noah exists
in the identity provider and has no account.

## Tests

`./gradlew build` runs them all: unit and fixture tests (`authz/fixtures.json` is the shared truth of
the evaluator, the Rego policy and the bundle), the architecture rules from the service kit, the
contract lock and coverage, and the HTTP flows against an in-process platform-fake on the in-memory
stores. With `CONSOLE_TEST_MONGO_URI` set (CI runs mongo:7) the same flows run again on MongoDB and
the Mongo adapters get their own tests; with `CONSOLE_OPA_BIN` (CI installs OPA) the conformance
tests run the real engine. CI also runs `opa check --strict` and gitleaks.

## Layout

One slice per aggregate (`accounts`, `principals`, `iam`, `accesskeys`, `quotas`, `accessrequests`,
`accessreviews`, `elevations`, `authz`), each with `domain` (pure), `application` (commands and queries), `ports/outbound` and
`adapters` (`web`, `memory`, `mongo`, `platform`, ...), as the service kit's ArchUnit rules enforce.
`common` holds auth, idempotency, paging, ids and the error bodies; `events` the outbox envelope;
`demo` the seed.
