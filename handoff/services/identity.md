# console-identity-service

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: customer-console-identity-service


Built 2026-10-23 (thread "Identity service") as PR #8 `console-identity-service--phase-1` → orphan branch `console-identity-service` (https://github.com/gonzalomunoz1024/infrastructure-automation/pull/8). Gradle, Java 21, WebFlux, CQRS + hexagonal, MongoDB (profile `mongo`; memory otherwise), on `com.iaf.console:*` from `console-libs--phase-0` via mavenLocal. Contracts pinned to hub 9edd017 in `contracts.lock`; `ContractCoverageTest` fails on anything served beyond them. Port 18083 (state service has 18082; `CONSOLE_IDENTITY_PORT` overrides); `bootRun` with profile `demo` (platform-fake-app 18090, BFF JWKS 18081, dev headers X-User/X-Account/X-Groups/X-Roles) or `demo,fake`. OPA tests need `CONSOLE_OPA_BIN`; Mongo tests `CONSOLE_TEST_MONGO_URI`. No Docker locally: mongo-java-server 1.47.0 in a tiny Gradle app works as a stand-in (only the actuator health test fails, it lacks `hello`).

Decisions other services rely on (all in the README):
- Ids `user_`, `grp_`, `role_`, `pol_`, `ck_`, `qreq_`, `areq_` + ULID; demo people `user_<name>`, issuer `http://localhost:18081/demo-idp`. Demo: gonzalo owns 123456789012 and 210987654321; dana, mateo operators, mia viewer of Compute (dana, mia in `compute-approvers`); raj operator, ana operator+viewer of Data (raj in `data-approvers`); noah no account. Approver groups hold a custom `approver` role (access-request get/list/decide).
- Managed roles `viewer`, `operator`, `account-admin`, `auditor`; groups `owners`, `members`, `viewers`, `auditors`. resolve's AccountMembership `roles` = managed role names only. The BFF signs the JWT with `groups` and `roles`; the service carries both but decides from the live membership and policies, never from token roles or group names.
- 403 body `{error, denied: {layer: console-iam, action, resource, reason, role?, policyId?, statement?, requestable, suggestedRole?}, correlationId}`.
- Access requests: own unless the policy allows access-request:list (viewer/operator exclude it); decide is policy-based (admins plus granted groups; `approvers` lists them); role defaults to suggestedRole; 409 answers the existing one; 30-day expiry, 15-min sweep.
- Quota requests: internal `:decide` {decision, limit?, comment?} sets `grantedLimit`; PUT at/above an open ask approves it, below leaves it open; DELETE withdraws. QuotaChanged: version from a counter per account+key (shared keys were dropped as replays), snapshot = Quota + `request`, action approved/rejected/updated.
- Records hide accountId (write-only JSON), so Mongo reads put it back from the field beside `doc`.
- Platform service-account tokens live only in the secrets adapter, never in Mongo, events or logs.

**Why:** the BFF, console-web and the state service integrate against these shapes.
**How to apply:** change the API only with the hub thread (contract) and the BFF thread. See [[customer-console-build]].

---

## Note: console-identity-phase7


Thread "Identity service". Branch `console-identity-service--phase-7`, PR #24 into `console-identity-service--phase-1` (stacked on #8). All packages were pushed by 2026-10-23 18:05 UTC (head e0d18b9). Contract: hub PR #22 (`console-root--identity-contract`, decision 28, stacked on cost PR #18), pinned at a0f5dbe (head 73b371e) in `contracts.lock` and `gradle.properties`. A new API goes to #22 first, then the copies are re-pinned.

What each package serves:
- **7.8:** role `boundary`, guardrails (`/v1/guardrails`, internal only) and `policy-simulations`.
  - Decision order: account and member checks, guardrail deny, guardrail allow-list, role denies, then the first allow its boundary permits.
  - A person's group roles are tried before time-boxed roles, so `elevated` marks only what the grant alone allowed.
- **7.6:** quota rules at `GET /v1/quota-rules`; an increase no rule covers is routed to the platform team.
- **9.11:** access reviews; undecided items expire as revokes by `system:access-review`.
- **7.7:** internal `:close`, IdP group sync (`/internal/v1/group-sync:run`), `costCentre` and `owningTeamMissing`.
- **Decision 25:** managed roles get cost and budgets, auditors get `cost:export`. ManagedPolicyUpgrade replaces stale managed policies at start.
- **9.12 elevation API:**
  - `POST /v1/accounts/{id}/elevations` with `{role, reason, durationMinutes 5-480, default 60}`. A configured rule may approve it (none ships by default; see below); otherwise an admin who is not the requester approves.
  - Asks stay pending for 24 hours. `:decide` and `:revoke`.
  - Internal `POST .../break-glass` with `{userId, reason, approvedBy != userId, ticket, role, minutes <= 240}`. It works for non-members.
  - Ids start `elev_`; the event is `ElevationChanged`. Sign-in lists held roles in `accounts[].elevations`.
  - The BFF must refresh sessions on `ElevationChanged` and cap tokens at `until`. The BFF and web threads were told on 2026-10-23.

Security review (2026-10-23 18:30, findings in /mnt/project-files/security-review/, one file per service, sent to each owning thread):
- Identity accepts only aud console-identity-service (hub decision 35: one audience per token).
- A token without acct is internal only when svc == sub and it is in `console.identity.auth.internal-services` (console-bff, console-search-indexer, console-drift-service, console-cost-service).
- Access-key answers are `Cache-Control: no-store`, so the idempotency store keeps a 409 and never the secret.
- No elevation auto-approval rule ships; the demo profile and the tests keep operators-admin-two-hours.

Blueprints (decision 33, 04e560f, hub a0f5dbe): the console service `blueprints` (get, list, launch, teardown, resume). Operators can't tear down.

Gotcha: user DELETE publishes UserChanged "deleted", which ends that person's elevations. The `approvers` field is left out when nobody but the requester could decide.

**Why:** a new thread needs the stack, the pins and the API without asking Gonzalo again.
**How to apply:** build on PR #24 and put contract changes on #22 first. See [[customer-console-identity-service]] and [[customer-console-build]].

---

## Note: customer-console-demo-people


Managed roles (hub decision 21, 2026-10-23): `viewer`, `operator`, `account-admin` (shown as Admin), `auditor`. Managed groups: `viewers`, `members` (operator), `owners` (account-admin), `auditors`. Approver groups are custom groups with a custom `approver` role (e.g. `compute-approvers`, `data-approvers`). Downstream services decide admin by `roles` containing `account-admin`; user tokens carry `roles` beside `groups`.

Demo accounts: 123456789012 "Compute" and 210987654321 "Data", one region `global`. Demo people (console user ids `user_<subject>`; `login_hint=<user>` skips the picker):
- gonzalo: account-admin (`owners`) in both accounts.
- dana: operator (`members`) + `compute-approvers` (Compute).
- mateo: operator (`members`) (Compute).
- mia: viewer (`viewers`) + `compute-approvers` (Compute).
- raj: operator (`members`) + `data-approvers` (Data).
- ana: operator and viewer (`members`, `viewers`) (Data).
- noah: no account → 403.
Full names (same in the BFF demo, identity DemoSeed and web mock since 2026-10-23): Gonzalo Munoz, Dana Reyes, Mateo Silva, Mia Chen, Raj Patel, Ana Costa, Noah Berg.
Demo bundle token for the identity service: `demo-bundle-token` (demo-only value, not a secret).

**Why:** the BFF, identity service and web mock must agree on these names or the demos and tests diverge.
**How to apply:** change them in all three places at once; see [[customer-console-bff]], [[console-state-service]].

