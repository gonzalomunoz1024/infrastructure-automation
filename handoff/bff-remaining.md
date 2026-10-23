# Console BFF: the work not started yet

Written 2026-10-23 19:05 UTC by the BFF's builder. Four items are left for the BFF (`console-bff`, Spring WebFlux, Java 21, Gradle). The sources are the hub decisions on `console-root--stack` (draft PR #30) and the BFF's own `README.md` and `contracts/additions.md`.

## Where the BFF stands

- **Branch stack:** `console-bff` → `console-bff--phase-1` (#6) → `console-bff--phase-4` (#26) → `console-bff--cost` (#34) → `console-bff--phase-9` (#51, draft) → `console-bff--org` (#52, draft, decision 34).
- **Hub decisions:** 31, 32 and 34 are on hub #38, and 35 (single audience) is on hub #46.
- **In progress, pushed as WIP:**
  - `console-bff--assistant` (copilot, WP 9.1)
  - `console-bff--clients` (device-code bearer tokens, 31.1)
  - `console-bff--notifications` (decision 32)
  - `console-sdk` (31.2, an orphan branch)
  - security fixes on `console-bff--phase-1`, with WIP on `console-bff--security-wip` (the review is `handoff/security-review` or `/mnt/project-files/security-review/bff.md`)
- **Base new work on the right branch:**
  - ChatOps goes on `console-bff--clients` (it needs the bearer and chat-link identity).
  - Time travel, transfer and blueprints go on `console-bff--phase-9`, or on `console-bff--org` once #52 merges.
- **Conventions:**
  - Hexagonal slices `com.iaf.console.bff.<slice>.{domain, ports/outbound, application, adapters/{inbound,outbound}}`.
  - ArchUnit: reads in `ports.outbound` take `AccountScope` first or are `@GlobalRead`; only `..demo..` may touch `com.iaf.console.fake..`.
  - Compiler flags `-Xlint:all -Werror`.
  - Tests: `CONSOLE_BFF_TEST_PORT=<free port> ./gradlew test`, with the demo app on that port.
  - Every new route gets a demo stand-in and an endpoint test.
  - Run gitleaks before pushing.
- **Libs:** a locally built console-libs lacks `PlatformPort.rehomeResource` (libs 6861ed5), so the transfer's re-home is verified only in CI. CI publishes libs from `console.libs.ref`, so move that ref to a libs commit that includes 6861ed5.

## 1. ChatOps: Slack and Teams through IAM (decision 31.3, WP 9.17)


- **Linking a chat identity to a person.** In chat, `/console link` answers a link to
  `<web>/#/chat-link?token=…`: a token the BFF signs (aud `console-chat-link`, 10 minutes)
  naming the platform, workspace (Slack team or Teams tenant) and chat user. The signed-in person
  opens it and confirms: `POST /console/v1/chat-links {token}` (session, CSRF) -> 201 ChatLink
  `{id, platform: slack|teams, workspaceId, chatUserId, chatUserName, linkedAt}`; one link per
  chat user (409 `chat-link.taken` when another person holds it). `GET /console/v1/chat-links`
  lists the person's; `DELETE /console/v1/chat-links/{id}` unlinks (204).
- **Endpoints for the chat platforms** (no session, no CSRF; each verified, else 401):
  `POST /chatops/slack/commands` and `POST /chatops/slack/interactions` check Slack's signature
  (`v0=` HMAC-SHA256 of `v0:<X-Slack-Request-Timestamp>:<raw body>` with the signing secret, the
  timestamp within 5 minutes); `POST /chatops/teams/messages` checks the Bot Framework JWT (keys
  from `https://login.botframework.com/v1/.well-known/openidconfiguration`, `aud` the bot's app
  id). Settings: `console.chatops.slack.signing-secret`, `bot-token`;
  `console.chatops.teams.app-id`, `app-password`, `openid-configuration`. Empty settings turn a
  platform off (its endpoints answer 404). Slack's 3-second rule: a command is acknowledged at
  once and answered through its `response_url`.
- **Commands** (Slack `/console <command>`, Teams the same words to the bot): `find <text>`
  (global search), `show <wfrn>` (state, drift, last job, cost), `jobs` (the person's recent
  jobs), `run <verb> <wfrn>` (the verb's preview with Confirm and Cancel buttons; Confirm runs it),
  `approve` (the person's pending approvals with Approve and Reject buttons), `account <id>
  [region]` (the account and region the chat acts in, kept per link), `link`, `unlink`, `help`.
  Every command runs as the linked person: a Session built from the link (memberships read from
  the identity service, cached 1 minute) through the same application services and IAM as the
  console. A chat call never satisfies step-up: a destructive verb answers with a link to the
  console page that runs it. An unlinked chat user gets only `link` and `help`. Every chat action
  is in the BFF's audit record with `channel: chatops`.
- **Alerts** reach a linked person as the `slack` or `teams` channel of decision 32; an account
  admin may also subscribe a channel: `/console subscribe <kind…>` in it (stored per account and
  channel; `unsubscribe`, `subscriptions`).


Glue to add: the Slack and Teams `NotificationChannel` of decision 32 (on `console-bff--notifications`) sends through ChatOps' chat delivery, and its chat-links lookup reads ChatOps' chat links.

## 2. Time travel, revert plan, map, activity trail, blast radius and moving a resource (decisions 29 and 30)


The state service's contract on `console-root--state-phase-9` (e790a27) is the source; the BFF
decides each read as the resource's own `<service>:get` (or the list's `<service>:list`) and passes
the state service's body through, as the phase 3 and 4 resource pages do.

| BFF | State service | Notes |
| --- | --- | --- |
| `GET /console/v1/r/{type}/{wfrn}?at=<date-time>` | `GET /v1/wfrn/{wfrn}?at=` | Time travel (WP 9.8): the resource as it was; read-only (`can` empty) |
| `GET /console/v1/r/{type}/{wfrn}/revert-plan?to=<date-time>` | `GET /v1/wfrn/{wfrn}/revert-plan` | What bringing it back takes; the console shows it and runs the plan's update only when the person confirms (the ordinary `PATCH` with `confirm`) |
| `GET /console/v1/r/{type}/{wfrn}/activity:verify` | `GET /v1/wfrn/{wfrn}/events:verify` | The resource's hash chain check |
| `POST /console/v1/r:blast-radius {wfrns}` (at most 100) | `POST /v1/resources:blast-radius` | A bulk delete's blast radius, each dependent counted once |
| `GET /console/v1/map` | `GET /v1/graph` | The resource map (WP 9.4), the session's account; `accountIds` across the person's accounts where `<service>:list` allows |
| `GET /console/v1/activity`, `/activity/digests`, `/activity/keys` | `GET /v1/activity…` | The account's activity trail (WP 7.1), its sealed periods and keys |
| `GET /console/v1/activity/evidence?from=&to=` | `GET /v1/activity/evidence` | The audit evidence pack (WP 7.1): `account-admin` and `auditor` only (403 otherwise) |
| `POST /console/v1/r/{type}/{wfrn}:transfer {toAccountId, reason?}` | `POST /v1/wfrn/{wfrn}:transfer` | Decision 30, below |

**Moving a resource (decision 30).** The person must be allowed `<service>:transfer` on the wfrn
in the source account and `<service>:create` on the type in the target account (each decided in
its own account; a refusal is the 403 of that account). Then: the target's budget check, as a
create runs it (`POST /v1/budgets:check {accountId: toAccountId, type, service, tags, monthly}`,
`monthly` from `Costs#monthlyCost` or 0; a deny is 409 `budget.exceeded`; the cost service down lets
it go on); the state service's `:transfer`; then the platform's re-home
(`PlatformPort.rehomeResource(registryId, new RehomeRequest(from, wfrn, accountId), options)` with
`from` the answer's `transferredFrom`; repeating it is safe; 409 `resource.rehomed` means it moved
since). A destructive confirm (`confirm: true`) and a fresh sign-in are needed. The answer is the
new resource (`201`, its wfrn in the target account). A transferred wfrn answers 410
`resource.transferred` with `transferredTo`.


Contract: hub #38, `contracts/openapi/console-bff.yaml` (the Activity tag, `transferResource`, `getRevertPlan`, `blastRadius`, `getResourceMap`). The state service's contract 1.1.0 is on hub #27.

## 3. Blueprints executor (hub decision 33, hub PR #39; state PR #45)


The BFF fronts, with authorization and the person's token: `GET /v1/blueprints`, `/v1/blueprints/{name}`; `POST /v1/blueprints/{name}:plan` (answer lists `authorize` pairs: check `blueprints:launch` on the stack pattern plus `<service>:create` per type before launch); `POST /v1/stacks` (account token only; a service token is a 400); `GET /v1/stacks`, `/v1/stacks/{wfrn}`, `/v1/stacks/{wfrn}/events`; `:teardown` (`blueprints:teardown`), `:resume`, `steps/{step}:report-failure`.
Executor: consume `state.stacks` (12 partitions, keyed by stack wfrn); for each `ready[]` entry start it once per `idempotencyKey` (`stack-<ulid>:<step>:<action>:<attempt>`). Create = today's create plus mint body `stack: {wfrn, step, attempt}`; the mint is the claim; 409 `stack.stepNotReady` = drop; repeat mint returns the same child. Delete = entry's `wfrn`, the type's delete workflow with `input`, `:request-delete` with jobId. Cannot start (IAM deny, start refused) → `POST steps/{step}:report-failure {attempt, action, code, message}`. Children run as `launchedBy` (the teardown actor for deletes). A sweeper re-announces ready entries after 5 min.

## 4. The BFF's side of the WP 6.2 load tests

Add scenarios to the search indexer's Gatling harness, stacked on its PR #35. Read that PR first for the harness layout, its feeders and its run command.

- **Targets** (plan, decision 3): list p95 < 300 ms, search < 500 ms, with 1k concurrent users and 10M resources (1M per account).
- **What to cover:** the BFF's own paths, run against the demo profile pointed at the real state service and indexer:
  - `/console/v1/r/{type}` lists, `/console/v1/search`, `/search:counts` and `/suggest`
  - `/console/v1/types` (ETag/304)
  - `/console/v1/insights` (cached per person and period, `console.insights.cache-ttl`)
  - the decision cache (`Access`, 60 s, keyed with the region after security fix 6)
- **Caches:** measure cold and warm runs separately. Report the hit ratio from the BFF's metrics, and the p95 and p99 per route.
- **Sessions:** sign in through the demo IdP with `login_hint=<user>`, then use the cookie and the `X-Csrf-Token`. Use a pool of demo people, so the per-person caches are realistic.
- **Pass rule:** fail the simulation on the plan's p95 targets, using Gatling assertions.
