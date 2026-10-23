# console-e2e

The customer console's full-system integration run: every console service on its production
profiles, the platform fake and the infrastructure they need in one docker compose stack
(`docker-compose.yml`), and a Playwright journey through console-web in live mode (`journey/`).

## Run it

Needs Docker with compose, Java 21 and Node 22.

```
./e2e.sh                 # check out services.lock, build, bring the stack up, run the journey, take it down
./e2e.sh --keep          # leave the stack up; then: cd journey && npx playwright test
./e2e.sh --no-build      # reuse the console-e2e/*:local images
./e2e.sh -- -g "7 ·"     # anything after -- goes to playwright test
```

- `scripts/checkout.sh` makes a detached worktree per commit of `services.lock` under `.work/src/`.
- `scripts/build.sh` publishes console-libs into a private Maven repository (`.work/m2`), builds the
  boot jars and console-web's production build, and the `console-e2e/<image>:local` images (the
  service's own Dockerfile, else `images/<image>/Dockerfile`).
- `scripts/up.sh` makes fresh dev keys and env files (`scripts/keys.mjs`, `scripts/env.mjs`; nothing
  secret is committed), starts the infrastructure and platform-fake, reads the platform token from
  its boot log, then starts the console and waits for every health check.
- The report, the JUnit results and the traces land in `artifacts/`; when anything fails, every
  container's log lands in `artifacts/logs/`. CI (`.github/workflows/e2e.yml`, on push and PR to
  `console-e2e` and on demand) keeps them as the run's artifacts, and runs gitleaks.

## The journey

Gonzalo signs in through Dex (the dev SSO) and the BFF (1), creates a VM in the create wizard (2),
waits until it is active (3), finds it in global search (4), sees what it costs (5); someone resizes
it behind the console's back and the drift scan finds it (6); he reverts it from the drift tab (7);
he deletes it, Dana approves, and it leaves the inventory and the search (8).

Each step in the console's pages has a twin through the BFF route the page calls. While a finding
blocks a page, its test is `test.fixme` with the finding's id (`journey/support/findings.ts`) and
the BFF's twin takes the step, so the rest of the journey still runs. `E2E_ASSUME_FIXED=F-12` (or
`all`) runs the steps a finding blocks.

## Pins

`services.lock` holds the branch, the commit and the reason of each image.

### Pins held back

Since the security reviews (hub decision 35) the identity service (73b371e) and the drift service
(5a01b4c) take a token without an account only from the services listed in their configuration.
The e2e harness calls both as its own registered client, `console-e2e` (seeding the account in the
identity service, asking the drift service for a check now). Their tips need `console-e2e` listed in
`console.identity.auth.internal-services` and `console.auth.service-callers` in
`config/identity` and `config/drift`; until that is decided, both are pinned at the commit before.

## Findings

Open:

- **F-4 (console-bff).** The cost routes (`console-bff--cost`) and the drift routes
  (`console-bff--drift`) live on sibling branches; no BFF commit has both. Step 5 is fixme.
- **F-12 (console-web).** `pages/resource/DriftTab.tsx:40-41` follows a revert only while
  `revertStatus` is `running`; between the job's success and the finding's close the tab stops
  polling and shows the finding open until a reload. Fix: keep following while `succeeded` and open.
- **F-13 (console-bff).** A resource's ETag is the state version alone
  (`resources/application/Resources.java:209-226`), while the body carries `lastJob` from the
  platform: a revalidation answers 304 and the page keeps `lastJob: running` while the delete
  waits for its approver. Fix: an ETag over the composed view, or no 304 while a job is open.
- **F-14 (console-web).** `pages/ResourceDetailPage.tsx:52` reads the platform record by the wfrn's
  console id, which the platform does not know; on the BFF's 410 for a deleted VM the page says
  "Could not load this virtual machine" instead of the tombstone. Fix: the tombstone on 410.
- **F-15 (console-web).** `console-web--phase-9-cost`, the only web with the resource cost panel,
  does not have F-9's fix (3950f17 on `console-web--phase-5`): `console/src/lib/bff.ts:57` still
  reads `/console/v1/types` as a bare list, so its pages are blank in live mode.

Worked around in this repository:

- **F-1 (console-libs, platform-fake).** `PlatformFake.java:167` calls `RandomGenerator.getDefault()`
  (module `jdk.random`, missing from the Temurin JRE images); the fake's image uses the JDK.
- **F-2 (console-libs, platform-fake-app).** The fake serves the P-01 feed over HTTP only;
  `images/platform-feed-bridge` publishes it to `platform.jobs` and `platform.resources`.
- **F-3 (console-bff).** The only dev sign-in is the demo IdP; the stack signs in through Dex.
- **F-7 (console-libs, platform-fake).** No P-22 price lists; the cost service runs on its bundled prices.
- **F-8.** console-identity-service, console-libs and console-web have no Dockerfile; `images/` has them.
- **F-11 (console-libs, platform-fake).** `vm.get` answers its 4 CPU / 16 GiB sample whatever
  `vm.create` made; the journey creates a medium VM, which matches it.

Fixed by their owners: F-5 (no cost panel; `console-web--phase-9-cost` has one), F-9 (the console
read `/console/v1/types` as a bare list; fixed in 3950f17), F-10 (the cost service's internal
cost read answered 500; fixed in 3664b57).
