# Console journeys

Seven journeys through the customer console, transcribed from the Console Journeys diagram page (built 2026-10-23 from the phase branches). Each step carries the number of the arrow that set it off: arrow 4 lands on step 4a, the part carries on with 4b, which sends arrow 5. "→ N to X" means the step sends arrow N to part X. Demo people: Dana (operator, Compute), Mia (viewer), Gonzalo (admin).

## Sign in and see your resources

**In plain words:** Dana signs in with the usual work login, opens Virtual machines, and opens one of them.

**Technically:** OIDC sign-in run by the BFF, then the list and detail pages read through the BFF from the state service, the search index and the platform.

### Steps in plain words

- **Start** (Console screens): Dana opens the console and presses Sign in → 1 to Front door
- **1a** (Front door): Sends Dana to the company login page, remembering where to come back to → 2 to Company sign-in
- **2a** (Company sign-in): Dana signs in as usual
- **2b** (Company sign-in): Sends Dana back with a one-time code → 3 to Front door
- **3a** (Front door): Checks the code and the login are genuine
- **3b** (Front door): Asks Permissions who Dana is → 4 to Permissions
- **4a** (Permissions): Finds Dana's team accounts and Dana's role in each
- **4b** (Front door): Starts Dana's session for the day and opens the console → 5 to Console screens
- **5a** (Console screens): The console opens on Dana's account
- **5b** (Console screens): Dana opens Virtual machines → 6 to Front door
- **6a** (Front door): Works out which buttons Dana may press → 7 to Permissions
- **7a** (Permissions): Answers yes or no for each action
- **7b** (Front door): Asks Resource records for the account's machines → 8 to Resource records
- **8a** (Resource records): Returns a page of machines, newest first
- **8b** (Front door): Asks Search how many there are in each status → 9 to Search
- **9a** (Search): Counts machines by status and drift
- **9b** (Front door): Sends the list, the counts and the allowed buttons → 10 to Console screens
- **10a** (Console screens): Dana sees the list; a locked button says why it is locked
- **10b** (Console screens): Dana opens one machine → 11 to Front door
- **11a** (Front door): Reads the machine's record → 12 to Resource records
- **12a** (Resource records): Returns its settings, status and history; passwords stay hidden
- **12b** (Front door): Asks the platform for recent jobs and what the machine was built from → 13 to Automation platform
- **13a** (Automation platform): Returns the jobs and the related resources
- **13b** (Front door): Sends the whole page back → 14 to Console screens
- **14a** (Console screens): Dana sees the machine: settings, activity, relationships

### Steps, technical

- **Start** (console-web): Dana opens the console; Sign in goes to `/auth/login?returnTo=/` → 1 to console BFF
- **1a** (console BFF): Seals PKCE verifier, state and nonce in the `console_login` cookie; redirects to the IdP → 2 to Enterprise SSO
- **2a** (Enterprise SSO): The IdP authenticates Dana
- **2b** (Enterprise SSO): Redirects to `/auth/callback` with a code; the ID token carries `groups` → 3 to console BFF
- **3a** (console BFF): Swaps the code with PKCE; verifies the ID token (issuer, audience, nonce) on the JWKS
- **3b** (console BFF): Calls `POST /v1/principals:resolve` → 4 to identity service
- **4a** (identity service): Resolves the user, memberships and managed roles; no account means 403
- **4b** (console BFF): Sets `console_session` (12 h, 1 h idle); redirects to `returnTo` → 5 to console-web
- **5a** (console-web): `GET /console/v1/session`: account, region, CSRF token
- **5b** (console-web): Asks `GET /console/v1/types` and `GET /console/v1/r/vm` → 6 to console BFF
- **6a** (console BFF): Builds descriptors from the platform's types and catalog, and decides each action → 7 to identity service
- **7a** (identity service): `POST /v1/authorize` per `<service>:<verb>` on the type's wfrn pattern
- **7b** (console BFF): `GET /v1/resources?type=vm` with an internal JWT (`acct`, `roles`) → 8 to state service
- **8a** (state service): One indexed query on the `resources` projection; cursor `next`
- **8b** (console BFF): `GET /v1/counts` for the status chips → 9 to search indexer
- **9a** (search indexer): Counts on alias `console-resources`, routed by account
- **9b** (console BFF): Rows, `next`, counts, descriptors with `allowed` and `denied` → 10 to console-web
- **10a** (console-web): VirtualTable renders; denied actions are drawn locked and open the refusal
- **10b** (console-web): `GET /console/v1/r/vm/{wfrn}` → 11 to console BFF
- **11a** (console BFF): `GET /v1/wfrn/{wfrn}` with `If-None-Match` → 12 to state service
- **12a** (state service): Resource with its `ETag`; sensitive fields masked `***`; 410 once deleted
- **12b** (console BFF): Last ten jobs and the lineage from the registry → 13 to platform
- **13a** (platform): `GET /api/v1/jobs`, `GET /api/v1/resources/{id}/lineage`
- **13b** (console BFF): Detail plus `can`, the open finding, jobs and lineage → 14 to console-web
- **14a** (console-web): The resource page and its tabs render

## Create a resource

**In plain words:** Dana creates a virtual machine. It is written down at once, the platform builds it, and every part hears that it is ready.

**Technically:** Authorize, mint the wfrn on the state service, start the workflow on the platform, then the platform feed and the state service's own events carry the result to search, drift and the browser.

### Steps in plain words

- **Start** (Console screens): Dana fills in the create form and presses Create → 1 to Front door
- **1a** (Front door): Checks Dana is signed in and the request is genuine
- **1b** (Front door): Asks Permissions: may Dana create machines here? → 2 to Permissions
- **2a** (Permissions): Checks Dana's role against the account's rules
- **2b** (Permissions): Answers yes
- **2c** (Front door): Checks the form, then asks Resource records to reserve a name → 3 to Resource records
- **3a** (Resource records): Writes the machine down as "pending" under a new name
- **3b** (Front door): Asks the platform to build it, on Dana's behalf → 4 to Automation platform
- **4a** (Automation platform): Checks the request and who it is for
- **4b** (Automation platform): Runs the provider's steps
- **4c** (Automation platform): The provider builds the machine
- **4d** (Automation platform): Registers the machine under its name
- **4e** (Automation platform): Posts "job finished, machine registered" → 5 to News board
- **5a** (News board): Work news → 6 to Resource records
- **6a** (Resource records): Reads the news
- **6b** (Resource records): Checks with the platform what was really built → 7 to Automation platform
- **7a** (Automation platform): Returns the machine's real settings
- **7b** (Resource records): Marks the machine "active" with its settings
- **7c** (Resource records): Posts "machine is ready" → 8 to News board
- **8a** (News board): Resource news → 9 to Search → 10 to Change watcher → 11 to Front door
- **9a** (Search): Adds the machine to search
- **10a** (Change watcher): Starts watching the machine for outside changes
- **11a** (Front door): Hears the news for Dana's account → 12 to Console screens
- **12a** (Console screens): Dana sees the machine turn "active" without refreshing

### Steps, technical

- **Start** (console-web): Wizard built from the workflow's input schema; `POST /console/v1/r/vm` → 1 to console BFF
- **1a** (console BFF): Session cookie, `X-Csrf-Token` and Origin check
- **1b** (console BFF): `POST /v1/authorize` for `compute:create` on `wfrn:…:vm/*` → 2 to identity service
- **2a** (identity service): Evaluates the policies (Java evaluator, same rules as the OPA bundle)
- **2b** (identity service): Allow; a deny would name the statement and the role that allows it
- **2c** (console BFF): Required inputs present; `POST /v1/resources:mint` with the `Idempotency-Key` → 3 to state service
- **3a** (state service): `ResourceRequested`: `pending`, wfrn `…:vm/vm-<ulid>`, appended to the log
- **3b** (console BFF): Starts the create workflow with the account's service account, `X-On-Behalf-Of`, `X-Resource-Wfrn` → 4 to platform
- **4a** (platform): Gateway checks the service account and the on-behalf-of assertion (P-02)
- **4b** (platform): One generic Temporal workflow runs the steps
- **4c** (platform): Provider REST calls or an Ansible playbook
- **4d** (platform): Registry records it with the wfrn (P-05)
- **4e** (platform): Outbox `platform_events`, relayed to Kafka (P-01) → 5 to Kafka
- **5a** (Kafka): `platform.jobs`, `platform.resources` → 6 to state service
- **6a** (state service): Consumes `job.finished`, `resource.registered` once per `key`
- **6b** (state service): The feed is only a cue: read the registry record by wfrn → 7 to platform
- **7a** (platform): Registry read by wfrn: `GET /api/v1/resources/by-wfrn`
- **7b** (state service): Appends `ProvisioningSucceeded`: `active`
- **7c** (state service): Outbox to `state.resources`, secrets masked → 8 to Kafka
- **8a** (Kafka): `state.resources` → 9 to search indexer → 10 to drift service → 11 to console BFF
- **9a** (search indexer): Upserts one document per wfrn (`external_gte`)
- **10a** (drift service): Adds a watch with the desired values, tier and next check
- **11a** (console BFF): Kafka consumer into the per-account replay ring → 12 to console-web
- **12a** (console-web): SSE `/console/v1/events`; the request banner settles

## Delete a resource

**In plain words:** Deleting asks Dana to sign in again first. The record is kept readable for 90 days so the name cannot be reused by mistake.

**Technically:** Step-up for `*:delete` within 10 minutes, platform first, then `:request-delete`; the delete job's success becomes the tombstone.

### Steps in plain words

- **Start** (Console screens): Dana chooses Delete and types the machine's name to confirm → 1 to Front door
- **1a** (Front door): Asks Permissions: may Dana delete this machine? → 2 to Permissions
- **2a** (Permissions): Answers yes (an operator would get a no)
- **2b** (Front door): Dana signed in more than 10 minutes ago, so asks Dana to sign in again → 3 to Console screens
- **3a** (Console screens): Explains why and sends Dana to sign in → 4 to Company sign-in
- **4a** (Company sign-in): Dana signs in again → 5 to Front door
- **5a** (Front door): Updates Dana's sign-in time, same session → 6 to Console screens
- **6a** (Console screens): Dana presses Delete again → 7 to Front door
- **7a** (Front door): Asks the platform to delete it, on Dana's behalf → 8 to Automation platform
- **8a** (Automation platform): Accepts the delete job
- **8b** (Front door): Notes the delete on the record → 9 to Resource records
- **9a** (Resource records): Marks the machine "deleting"
- **8c** (Automation platform): Removes the machine at the provider
- **8d** (Automation platform): Posts "job finished" → 10 to News board
- **10a** (News board): Work news → 11 to Resource records
- **11a** (Resource records): Records the machine as deleted
- **11b** (Resource records): Keeps the record readable for 90 days so the name is not reused → 12 to News board
- **12a** (News board): Resource news → 13 to Front door
- **13a** (Front door): Hears the news for Dana's account → 14 to Console screens
- **14a** (Console screens): Dana sees the machine as deleted; its history stays readable

### Steps, technical

- **Start** (console-web): Actions → Delete with typed-name confirm; `POST …/{wfrn}:delete` with `confirm: true` → 1 to console BFF
- **1a** (console BFF): `POST /v1/authorize` for `compute:delete` on the wfrn → 2 to identity service
- **2a** (identity service): Allow; operators hit `DenyDestructive` in `managed-operator`
- **2b** (console BFF): Step-up: `authTime` older than 10 min → 403 with `detail.stepUp` → 3 to console-web
- **3a** (console-web): Follows `/auth/login?stepUp=true` → 4 to Enterprise SSO
- **4a** (Enterprise SSO): `prompt=login&max_age=0` → 5 to console BFF
- **5a** (console BFF): The callback refreshes the session's `authTime` → 6 to console-web
- **6a** (console-web): Retries the same request → 7 to console BFF
- **7a** (console BFF): Platform first, so a refused form never leaves the record stuck in `deleting` → 8 to platform
- **8a** (platform): Delete workflow started on the registry record
- **8b** (console BFF): `POST /v1/wfrn/{wfrn}:request-delete` with the reason → 9 to state service
- **9a** (state service): `DeleteRequested`: `deleting`
- **8c** (platform): The workflow runs the provider's delete steps
- **8d** (platform): `job.finished` relayed to `platform.jobs` → 10 to Kafka
- **10a** (Kafka): `platform.jobs` → 11 to state service
- **11a** (state service): A succeeded delete job is `Deleted` alone (hub decision 20)
- **11b** (state service): Tombstone: 410 with `tombstoneUntil`; outbox to `state.resources` → 12 to Kafka
- **12a** (Kafka): `state.resources` → 13 to console BFF
- **13a** (console BFF): Kafka consumer into the replay ring → 14 to console-web
- **14a** (console-web): SSE update; the page answers 410 and the log stays readable

## Find anything with search

**In plain words:** Dana searches from anywhere, or filters a long list. Search only ever looks inside Dana's own accounts and types Dana may see.

**Technically:** Global search filtered by `<service>:list` decisions; list free text goes through the index and every hit is re-read from the state service.

### Steps in plain words

- **Start** (Console screens): Dana presses Cmd+K and types "web-01" → 1 to Front door
- **1a** (Front door): Asks Permissions which kinds of resources Dana may list → 2 to Permissions
- **2a** (Permissions): Answers for each kind
- **2b** (Front door): Asks Search, inside Dana's account only → 3 to Search
- **3a** (Search): Looks through names, tags and settings, best match first
- **3b** (Front door): Leaves out kinds Dana may not see → 4 to Console screens
- **4a** (Console screens): Dana sees the matches with the words highlighted
- **4b** (Console screens): Later, Dana types in the filter box of a long list → 5 to Front door
- **5a** (Front door): Asks Search for matching machines → 6 to Search
- **6a** (Search): Returns the matching names in order
- **6b** (Front door): Reads each match again so every row is up to date → 7 to Resource records
- **7a** (Resource records): Returns the current records
- **7b** (Front door): Sends the rows back → 8 to Console screens
- **8a** (Console screens): Dana sees an up-to-date filtered list

### Steps, technical

- **Start** (console-web): Palette: `GET /console/v1/search?q=web-01` → 1 to console BFF
- **1a** (console BFF): `<service>:list` for every type (decisions cached, dropped on identity changes) → 2 to identity service
- **2a** (identity service): `POST /v1/authorize` per type
- **2b** (console BFF): `GET /v1/search?q=` with Dana's JWT (`acct`) → 3 to search indexer
- **3a** (search indexer): OpenSearch on `console-resources`, routed by account; 20 queries/s per account
- **3b** (console BFF): Drops hits of types without `list`; the cursor carries on → 4 to console-web
- **4a** (console-web): Hits with HTML-escaped highlights
- **4b** (console-web): `GET /console/v1/r/vm?q=…&state=active` → 5 to console BFF
- **5a** (console BFF): Through the index with type, state, tags and sort → 6 to search indexer
- **6a** (search indexer): Wfrns in the index's order
- **6b** (console BFF): Re-reads each hit from the state service, sixteen at a time → 7 to state service
- **7a** (state service): `GET /v1/wfrn/{wfrn}` per hit
- **7b** (console BFF): A hit the state service no longer holds is left out → 8 to console-web
- **8a** (console-web): Rows, counts and cursor

## Ask for access and get approved

**In plain words:** Mia can only view, so Create is locked. Mia asks for access from the locked button, Gonzalo approves, and Create unlocks.

**Technically:** The refusal names its layer and suggested role; the access request lives in the identity service; approval joins the group and `identity.changes` drops cached decisions.

### Steps in plain words

- **Start** (Mia's screens): Create is locked; Mia opens it and reads why, then presses Request access → 1 to Front door
- **1a** (Front door): Passes the request on for Mia's account → 2 to Permissions
- **2a** (Permissions): Records the request and suggests the operator role
- **2b** (Front door): Confirms it was sent → 3 to Mia's screens
- **3a** (Mia's screens): Mia sees "Request sent to the account's admins"
- **Start** (Gonzalo's screens): Later, Gonzalo opens the notifications bell → 4 to Front door
- **4a** (Front door): Asks Permissions for requests Gonzalo may decide → 5 to Permissions
- **5a** (Permissions): Lists the pending requests
- **5b** (Front door): Sends the list → 6 to Gonzalo's screens
- **6a** (Gonzalo's screens): Gonzalo approves Mia's request → 7 to Front door
- **7a** (Front door): Passes the decision on → 8 to Permissions
- **8a** (Permissions): Adds Mia to the group that holds the operator role
- **8b** (Permissions): Posts the news → 9 to News board
- **9a** (News board): Permission news → 10 to Front door
- **10a** (Front door): Forgets the old answers for this account → 11 to Mia's screens
- **11a** (Mia's screens): Next time Mia loads the page, Create works

### Steps, technical

- **Start** (console-web (Mia)): Refusal view: console IAM, no `viewer` statement allows `compute:create`; `POST /console/v1/access-requests` → 1 to console BFF
- **1a** (console BFF): Pass-through with Mia's internal JWT → 2 to identity service
- **2a** (identity service): `AccessRequest` pending, role = `suggestedRole`, expires in 30 days
- **2b** (console BFF): 201 with the request → 3 to console-web (Mia)
- **3a** (console-web (Mia)): Request shown under My requests
- **Start** (console-web (Gonzalo)): `GET /console/v1/access-requests` → 4 to console BFF
- **4a** (console BFF): Pass-through; needs `access-request:list` → 5 to identity service
- **5a** (identity service): Admins, auditors and approver groups see the queue
- **5b** (console BFF): Each request with `canDecide` → 6 to console-web (Gonzalo)
- **6a** (console-web (Gonzalo)): `POST /console/v1/access-requests/{id}:decide` → 7 to console BFF
- **7a** (console BFF): Pass-through → 8 to identity service
- **8a** (identity service): Mia joins `members`
- **8b** (identity service): `AccessRequestChanged` (approved) and `GroupChanged` → 9 to Kafka
- **9a** (Kafka): `identity.changes` → 10 to console BFF
- **10a** (console BFF): Drops the account's cached decisions on every instance → 11 to console-web (Mia)
- **11a** (console-web (Mia)): The next `GET /console/v1/types` decides `compute:create` allowed

## Spot an outside change and put it back

**In plain words:** Someone changed a machine directly at the provider. The watcher notices, Dana sees what differs, and one click puts it back. Nothing is put back on its own.

**Technically:** Temporal loop, `:observe-list`, finding, `drift.findings`; revert runs the update workflow on behalf of the person and closes only when the next read agrees. The screen-to-revert link (arrows 7 and 8) arrives in phase 5.

### Steps in plain words

- **Start** (Change watcher): The watcher's hourly check for this machine comes round → 1 to Automation platform
- **1a** (Automation platform): Reads the machine's real settings from the provider
- **1b** (Change watcher): The memory was changed to 16 GB; it should be 8 GB → 2 to Resource records
- **2a** (Resource records): Flags the drift on the machine's record
- **1c** (Change watcher): Posts drift news → 3 to News board
- **3a** (News board): Drift news → 4 to Search → 5 to Front door
- **4a** (Search): Marks the machine as drifted so filters find it
- **5a** (Front door): Hears the news for the account → 6 to Console screens
- **6a** (Console screens): Dana sees a drift warning: memory 8 GB expected, 16 GB found
- **6b** (Console screens): Dana clicks Revert → 7 to Front door
- **7a** (Front door): Passes it on, on Dana's behalf → 8 to Change watcher
- **8a** (Change watcher): Asks the platform to set the memory back to 8 GB → 9 to Automation platform
- **9a** (Automation platform): Puts the memory back at the provider
- **9b** (Change watcher): Waits for the job, then checks again: a finished job is not proof → 10 to Automation platform
- **10a** (Automation platform): Reads the real settings again
- **10b** (Change watcher): It matches now: the warning is closed as reverted → 11 to Resource records
- **11a** (Resource records): Clears the drift from the record
- **10c** (Change watcher): Posts "resolved" → 12 to News board
- **12a** (News board): Drift news → 13 to Front door
- **13a** (Front door): Hears the news → 14 to Console screens
- **14a** (Console screens): Dana sees the machine back in line

### Steps, technical

- **Start** (drift service): Temporal `DriftLoopWorkflow` turn for its shard and tier (warm: 1 h) → 1 to platform
- **1a** (platform): `:observe-list` page of up to 500 through the read action (P-09)
- **1b** (drift service): Reconciler: `memoryGb` expected 8, observed 16 → finding opened → 2 to state service
- **2a** (state service): `:record-observation`, `:record-drift` → `DriftDetected`
- **1c** (drift service): Outbox → `drift.findings` (`FindingOpened`) → 3 to Kafka
- **3a** (Kafka): `drift.findings` → 4 to search indexer → 5 to console BFF
- **4a** (search indexer): Sets the document's drift flag
- **5a** (console BFF): Kafka consumer into the replay ring → 6 to console-web
- **6a** (console-web): SSE update; the drift tab shows expected and observed
- **6b** (console-web): Revert on the finding (screen arrives in phase 5) → 7 to console BFF
- **7a** (console BFF): `POST /v1/findings/{id}:revert` with `X-On-Behalf-Of` (phase 5) → 8 to drift service
- **8a** (drift service): The type's update workflow with the desired values, as the account, on Dana's behalf → 9 to platform
- **9a** (platform): Update job runs, labelled `drift-finding`
- **9b** (drift service): Revert follower; the finding stays open until a read agrees → 10 to platform
- **10a** (platform): `resources:observe`
- **10b** (drift service): `FindingClosed` (reverted) → 11 to state service
- **11a** (state service): `:resolve-drift` → `DriftResolved`
- **10c** (drift service): Outbox → `drift.findings` → 12 to Kafka
- **12a** (Kafka): `drift.findings` → 13 to console BFF
- **13a** (console BFF): Kafka consumer into the replay ring → 14 to console-web
- **14a** (console-web): SSE update; the warning is gone

## A new kind of resource appears

**In plain words:** A provider team publishes a new kind of resource. The console shows it with a list, a create form and actions, without anyone changing the console.

**Technically:** A `kind: ResourceType` published by change request (P-19) drops the BFF's descriptor cache; descriptors are built from the type's schema and the catalog's verbs.

### Steps in plain words

- **Start** (Provider team): The database team publishes a "Database" resource type with its workflows → 1 to Automation platform
- **1a** (Automation platform): Stores the new type and its workflows
- **1b** (Automation platform): Posts "new content published" → 2 to Front door
- **2a** (Front door): Forgets its old list of resource types
- **2b** (Front door): Tells open screens that the types changed → 3 to Console screens
- **3a** (Console screens): The console reloads its menu → 4 to Front door
- **4a** (Front door): Asks the platform for the new type's details → 5 to Automation platform
- **5a** (Automation platform): Returns its fields, workflows and display hints
- **5b** (Front door): Builds the pages from it and works out who may press what → 6 to Console screens
- **6a** (Console screens): Databases appear in the menu with a list, a create form and actions

### Steps, technical

- **Start** (provider team): `kind: ResourceType` with `console` hints, merged by change request (P-19) → 1 to platform
- **1a** (platform): Stored in MongoDB; `content.published`
- **1b** (platform): On `platform.content` and the HTTP feed → 2 to console BFF
- **2a** (console BFF): Drops cached descriptors for every account
- **2b** (console BFF): SSE `types.changed` → 3 to console-web
- **3a** (console-web): Refetches `GET /console/v1/types` → 4 to console BFF
- **4a** (console BFF): Reads the resource type and the catalog → 5 to platform
- **5a** (platform): `GET /api/v1/resource-types/{name}`, `GET /api/v1/catalog`
- **5b** (console BFF): Descriptor: list columns, create form, verbs; defaults where hints are missing → 6 to console-web
- **6a** (console-web): The same pages as every type: no console code changed

