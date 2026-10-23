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
| Accounts | `GET/POST /v1/accounts`, `GET/PATCH /v1/accounts/{accountId}`, `GET .../platform-credentials` (internal) |
| Principals | `POST /v1/principals:resolve` (internal: the BFF at sign-in) |
| Users, groups, roles, policies | `GET/POST .../users`, `GET/PATCH/DELETE .../users/{userId}`; the same for `groups`, `roles` and `policies` (`PUT` replaces a policy) |
| Access keys | `GET/POST .../access-keys`, `GET/DELETE .../access-keys/{keyId}`, `POST .../access-keys/{keyId}:rotate` |
| Quotas | `GET .../quotas`, `GET/PUT .../quotas/{quotaKey}`, `POST .../quotas/{quotaKey}:request-increase` |
| Authorization | `POST /v1/authorize`, `GET /internal/opa/bundles/console-authz.tar.gz` |

Access requests (`GET/POST .../access-requests`, `GET .../access-requests/{requestId}`,
`POST .../access-requests/{requestId}:decide`) and quota requests (`GET/DELETE .../quota-requests/{requestId}`)
are part of the contract since hub `e8992fb`; nothing is served beyond it (`ContractCoverageTest`
fails on an operation the contract lacks, so a new one goes to the hub first).

## How it decides

- **Principals.** The BFF calls with an internal JWT (`iss console-bff`, `aud console-identity-service`,
  ES256 against the configured JWKS, at most `max-lifetime` long). A user token carries `acct`,
  `sub` (the console user id), `groups` and `region`; a service token carries `svc` and no account.
  A user never sees another account: anything outside their accounts is a 404, never a 403.
  Service principals (the BFF, the state service) are allowed everything in their own calls.
- **Managed IAM.** Every account gets four built-in roles, `viewer`, `operator`, `account-admin`
  and `auditor` (each with one managed policy, immutable), and four built-in groups, `owners`
  (account-admin), `members` (operator; the owning IdP team joins it at sign-in), `viewers` and
  `auditors`. Built-in groups keep their names, `owners` keeps the account-admin role and at least
  one active member, and cannot be deleted. Custom groups, roles and policies are free.
- **Policies.** AWS-shaped statements: `effect`, `actions` (`<service>:<verb>`, wildcards on
  either side), optional `notActions` on allow statements, `resources` (wfrn patterns) and
  `conditions` (`region`, `account`, `service`, `type`, `requester.user`, `requester.email`,
  `requester.group`, `tag.<name>`, `context.<name>`). Actions are validated against the platform's
  resource types and catalog plus the console's own services (`iam`, `account`, `quota`, `audit`,
  `cost`, `search`, `access-request`). A deny wins; without an allow the decision says which managed
  role would have allowed it (`requestable`, `suggestedRole`), so the console can offer an access
  request.
- **One evaluator, two engines.** `authz/domain/Evaluator` decides in process; the same policy is
  written in Rego (`src/main/resources/opa/console/authz.rego`) and served as a bundle with the
  compiled accounts as data. The `opa` profile makes the service ask an OPA that polls the bundle
  instead. `OpaConformanceTest` and `BundlesTest` run every fixture case through both and fail on
  the first difference. `AuthorizeResponse.version` is the account's version; the bundle revision
  (and its ETag) is the global revision, bumped on every identity change.
- **Refusals.** A 403 carries `denied: {layer, action, resource, reason, role?, policyId?,
  statement?, requestable, suggestedRole?}` so the BFF can show why and offer the request.
- **Access requests.** A person asks for a role after a refusal (`{action, resource?, role?, reason}`;
  without `role` the managed role the denial suggests). The request goes to the members of the
  groups whose policies allow `access-request:decide` (the owners, plus any custom approver group);
  approving joins the requester to the group that carries the role. Everyone sees their own
  requests; the account's are for whoever may `access-request:list` (admins, auditors, approver
  groups: the managed viewer and operator policies leave those reads out). The same open request answers
  409 with the existing one; a pending request expires after thirty days (a sweep every fifteen
  minutes stores it). Every step publishes `AccessRequestChanged` (`created`, `approved`,
  `rejected`, `expired`) and an approval the `GroupChanged` of the group joined.

## Accounts, keys, quotas, events

- Creating an account (internal only) mints a twelve-digit id, the managed IAM, the first owner
  from `ownerEmail`, and asks the platform for the account's console service account (P-03). Its
  token is kept by the secrets adapter (`vault` profile: Vault; otherwise memory) and is never in
  MongoDB, an event or a log; `GET .../platform-credentials` hands it to internal callers.
- Access keys: `ck_` id plus a 43-character secret shown once; at most two usable keys per user;
  rotation keeps the previous secret for ten minutes; a removed user's keys are revoked.
- Quotas: `jobs-per-minute` reads through the platform's rate bucket (an override moves the
  bucket too), `access-keys` counts usable keys (default 50), `resources.<type>` defaults to 100
  (used counts arrive from the state service later). Users request increases (one open request
  per key, answered as a `QuotaRequest` with `currentLimit` and `requestedLimit`); the requester
  or an account admin withdraws one (`DELETE`, 204); the platform team decides by setting the
  limit (internal `PUT`): at or above the ask approves the request, below rejects it.
- Every change appends an `identity.changes` event (`AccountChanged`, `UserChanged`,
  `GroupChanged`, `RoleChanged`, `PolicyChanged`, `AccessKeyChanged`, `QuotaChanged`,
  `AccessRequestChanged`) in the
  platform envelope: key `<type>:<accountId>:<subjectId>:<version>`, partition key the account id,
  `data: {version, action, snapshot}`. The outbox relay publishes to Kafka with the `kafka` profile.
- Writes take `If-Match` (a version, bare or quoted; 409 when stale) and `Idempotency-Key` (a replay
  answers the first response with `Idempotent-Replay: true`; another body under the same key is 409).
- Pages are `{items, next}`, `limit` 1 to 200, 50 by default.

## Running it

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
`authz`), each with `domain` (pure), `application` (commands and queries), `ports/outbound` and
`adapters` (`web`, `memory`, `mongo`, `platform`, ...), as the service kit's ArchUnit rules enforce.
`common` holds auth, idempotency, paging, ids and the error bodies; `events` the outbox envelope;
`demo` the seed.
