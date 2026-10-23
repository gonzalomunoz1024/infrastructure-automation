# console-libs (Gradle conventions, wfrn, service-kit, platform client, platform-fake)

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-ci-libs-publish


Until console-libs is published to GitHub Packages (hub decision 15), each service's CI checks out
`console-libs--phase-0` (env `CONSOLE_LIBS_REF`) and publishes it to the local Maven repository
before building. The root `publishToMavenLocal` does NOT publish the included build `build-logic`,
which holds the `console.spring-service` / `console.java-library` convention plugin markers, so the
build fails with "Plugin [id: 'console.spring-service'] was not found". Run

`./gradlew :build-logic:publishToMavenLocal publishToMavenLocal -x test --no-daemon`

in the libs checkout (the state service's `.github/workflows/ci.yml` does). console-libs f44213a
(2026-10-23) lets the console-internal token run the backfill. console-libs 4260aab (2026-10-23)
fixed the pause race at its root: a job now pauses before its approval is listed or its
waiting-approval / needs-attention status shows, so deciding the instant a pause is visible never
gets job.notPaused (only a second decision on the same pause still does). Consumers can drop
"wait for status, swallow 409" workarounds after re-pulling. console-libs c4548f4 (2026-10-23) adds
P-23 re-home (PlatformPort.rehomeResource, Resource.previousWfrns); 6861ed5 makes it repeatable
(decision 2026-10-23: teams reorganize more than once): RehomeRequest(from, wfrn, accountId), 409
resource.rehomed only when the record is at neither from nor wfrn. Pinned to hub e790a27 (hub PR #27,
branch console-root--state-phase-9, owned by the State service thread).

**Why:** the first CI run of PR #7 failed on this and every service branch will hit it.
**How to apply:** copy the CI step from [[console-state-service]]'s workflow for new services.

---

## Note: customer-console-phase0-decisions


Decisions Claude made as lead for phase 0 (2026-10-23), recorded in the hub README "Phase 0 decisions" 11–16 on `console-root`:
- Atlas (`@iaf/ui`) is vendored verbatim from platform main c9529d5 until platform change P-14 publishes it.
- The platform client is hand-written and contract-tested against the hub's OpenAPI (generation revisited in phase 1 for service-to-service clients).
- platform-fake is both a library a service's demo profile embeds and a runnable app (port 18090, demo accounts 123456789012 team-compute and 210987654321 team-data).
- Contracts are pinned per repo by hub commit in `contracts.lock` (commit + sha256 per copied file; `npm run contracts:verify` / `ContractsLockTest`).
- wfrn patterns: a wildcard segment is checked only for alphabet and max length (lone `*` valid), prefix patterns like `wfrn:wf:*` allowed, `matches()` is false for an invalid name.
- Console events (`contracts/asyncapi/console-events.yaml`, decided 2026-10-23 07:15 with the identity thread): every message is the platform's P-01 envelope as the service kit's `EventEnvelope` writes it (`aggregate`/`aggregateId` name the subject, `context.accountId`/`region`/`wfrn`, console fields in `data`: `data.version` on every event, `data.state` on resources, `data.action` + `data.snapshot` on identity). Type names stay PascalCase (`UserChanged`) to never collide with the platform's `resource.*`. Keys `<type>:<accountId>:<subjectId>:<version>` on identity.changes.
- Identity service internal operations (hub draft): `GET /v1/accounts/{accountId}/platform-credentials` (source local → token; source vault → Vault path console/accounts/{accountId}/platform-service-account) and `GET /internal/opa/bundles/console-authz.tar.gz` (ETag/If-None-Match, console-internal JWT or the OPA bundle token).
- The platform's SSE streams name each event after its type (`event: job.started`); every consumer must subscribe to the 24 types of asyncapi-target.yaml (console: `console/src/lib/events.ts`, kept equal to the contract by a test).
- Decisions 20–21 (hub b40ccc9, 2026-10-23 09:00 UTC): every console API caps page size at 200 (default 50); tombstones 410 for 90 days; `ProvisioningSucceeded` ends a create/update in `active`, a succeeded delete is one `Deleted` event (never ProvisioningSucceeded first), `ProvisioningFailed` leaves a create `failed` and returns an update/delete to `active`. Managed roles per account `viewer`, `operator`, `account-admin`, `auditor` (shown Viewer/Operator/Admin/Auditor) with groups `viewers`, `members` (operator), `owners` (account-admin), `auditors`. The internal JWT is signed by the BFF (ES256, its JWKS), claims iss, aud, iat, exp, sub, acct, region, groups, `roles` (managed role names), svc, optional name/email; admin-only checks (state `reveal=sensitive`, deciding access requests) test `roles` for `account-admin`, never a group name.

Gate 0 passed 2026-10-23 06:56 UTC: console shell live against platform-fake (sign-in, VM list, detail by the fake's wfrn, POST /api/v1/vms → job.started…job.finished live in the Platform feed, request page, new VM listed, Idempotency-Key replay returns the same job). Proof: /mnt/project-files/infrastructure-automation/console-phase-0/ (live-*.png, gate0-report.json). Bugs it caught: the feed listened only for unnamed SSE messages (mock mode had hidden it; the mock now names events too); MongoOutboxTest shared one database across tests (order-dependent, each test now gets its own).

**Why:** these are not derivable from the code alone and later service threads must follow them.
**How to apply:** keep them unless Gonzalo overrides; see [[customer-console-build]] for branches and PRs.

