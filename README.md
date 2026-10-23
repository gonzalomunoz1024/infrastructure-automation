# console-web

The customer console of the infrastructure automation platform: one place where the firm's
internal teams see the resources the platform manages for them, and from phase 3 change them.
It is laid out like the AWS console and drawn with Atlas, the platform's design system.

This branch is shaped like the repository it will become: an npm workspace at the root, its own
CI, no platform files. The hub branch `console-root` holds the plan, the decisions and the
contracts this build pins.

## What is here (phases 0, 1, 3, 4, 5 and 7)

Phases 7 and 9 keep access right over time, and close the account's life:

- access reviews: an admin starts a campaign over everyone's group memberships with a due date,
  and the account's admins keep or remove each access with a reason, one at a time or a selection
  at a time, by keyboard too (arrows move, K keeps, R removes); nobody reviews their own; the
  progress shows on each campaign, and the rule of the due date is said wherever it shows:
  anything not reviewed by then loses its access; the bell lists the open review to whoever may
  work through it
- elevations: a person asks to hold a role for 15 minutes to 8 hours with a reason, approved at
  once by a rule or by an admin who is not them (the bell lists the asks to decide); a held role
  shows in a banner with the time left, ends by itself or by hand, and marks the actions only it
  allows as elevated; a refusal that a rule covers offers Ask for this role for an hour
- the organization's accounts, for its admins: emergency (break-glass) access with its warning
  and the line the account's trail records, and closing an account by typing its name, refused
  while the account still holds resources, with what it holds

Phase 7 brings the CLI's web side and the developer portal:

- `#/device` approves a CLI or SDK sign-in: the code from the terminal (in the link, or typed),
  what asks, from which machine and address, for what and until when, then Approve or Deny;
  approving asks for a fresh sign-in first, as a delete does
- CLI sign-ins (the user menu, and Identity and access beside Access keys): the CLIs and SDKs the
  person signed in, newest first, each signed out after asking once
- the console shell (Developer tools): a terminal in the page that runs the CLI's commands with
  the page's own session (`help`, `whoami`, `types`, `ls`, `get`, `run`, `jobs`, `job --follow`),
  with history on Up and Down, Tab completing a command, and every result in a log region
- the developer portal: the console API by tag from its OpenAPI document, how to get the CLI and
  SDKs, sample calls for a type built from its workflows' schemas, and sample portal tiles, any of
  them kept as a shortcut

Phase 7 tells people what happened:

- the bell carries the person's notifications beside the access requests to decide: requests that
  succeed, fail or pause, approvals waiting and decided, access requests, drift, quotas, budgets
  and the weekly digest, new ones arriving on the live stream, each leading to its resource or
  request and marked read on the way; the Notifications page filters them by unread and kind
- notification settings: email, Slack and Teams on or off, the channels for each kind (the
  defaults filled in), saved against the version read, and a test send per channel
- linked chats: `#/chat-link?token=` links the Slack or Teams identity `/console link` names after
  one confirmation; the linked ones are listed and unlinked there
- weekly digests: each week of the account in words, its changes, drift, cost and access, with
  the numbers they were written from and links to what they name

Phase 7 writes the account's own policies (WP 7.8):

- Policies under Identity and access: the managed and custom policies, where each is used, its
  statements in words and its JSON; the editor writes one visually (service and its actions from
  the console's types and workflows, wfrn patterns with a helper that builds them, conditions on
  tags, the region, the person and their groups) or as JSON, both views over one document, each
  problem shown at its field and its JSON line; a save is a new version against the one read
- a policy is attached to a custom role, or a role is created with it and granted by groups; its
  delete is refused while a role holds it or is bounded by it, naming each role
- the policy simulator: a person, a role or a set of groups, unsaved policies and a draft
  boundary laid over the account, actions on wfrns or patterns (a picked resource brings its tags);
  each answer says allowed or denied, the statement that decided and where it sits (a guardrail,
  the role's permission boundary, a policy), drawn as the refusal draws its layers; the editor
  opens it in a drawer over the unsaved document
- permission boundaries on custom roles, shown on each role and, for a person, over every role
  they hold; the organization's guardrails over the account, listed for everyone who reads IAM and
  written by an organization admin

The state service's views show what happened, what was and what is built from what:

- the Activity trail lists every change to the account's resources (what, who, through which
  request), filtered by person, resource type, event type, outcome and period; Verify the chain
  checks the period's evidence pack in the browser and says in words whether the chain holds or
  where it breaks, and Download evidence pack saves it for the auditors (admins and auditors)
- a resource's Activity tab replays it to any point of its history (a slider, a day, Yesterday,
  A week ago), what was asked for and what the provider reported then beside what is asked for
  now, with what changed since; Revert to this opens Edit configuration filled with then, to
  review before anything runs; Inventory as of a date lists the account's resources at the end of
  a day
- the Relationships tab shows what made the resource (its workflow, its request, its image) and
  what was built from it, each a link; the Resource map draws the account (and, if chosen, other
  accounts the person can read) as columns of what is built from what, coloured by health, drift
  or cost, zoomed and moved through with the keyboard, with a list view holding the same

Phase 7 remembers each person's places and raises quotas:

- the console home's Recently visited and Favourites come from the BFF: the lists and resources
  the person opened in the account, newest first, the resources they starred (a star on every
  resource page and list row) and the resource types they pinned (a pin on each list), so they
  follow the person to any browser and never show to the next person on the device
- Service Quotas: every quota of the account with its use, its limit and the rule that raises it,
  a request for more that a rule approves at once (the dialog says so before it is sent) or sends
  to the platform team, the account's requests with their status, and the platform team deciding
  them there, never their own; a create the quota refuses links straight to the request

Phase 5 decides on drift and finds what nobody manages:

- a finding on the resource's Drift tab is reverted in one click: the type's update workflow runs
  with what was asked for, the card says Reverting with its request and follows it, and the
  finding closes when the request succeeded (a revert whose request paused stays Reverting until someone steers it there; one that failed says so and is offered again); Accept
  makes the provider's values the desired ones after asking once, and Ignore asks for how long
  (until what was asked for changes, or 1, 7 or 30 days) and why; the findings closed in the last
  30 days are listed below, with who decided, when and why
- the account's Drift page counts the open findings by severity and type, says when the last scan
  ran and what the last 30 days found and closed, and lists every finding across the watched
  types, filtered by type, severity and status, each with its three decisions; selected rows are
  reverted together, one call each, counted as they go
- Unmanaged resources runs a discovery for each type whose provider has a discovery read, lists
  what no account manages with what the provider reports, a guess at the owning account with its
  reasons and what it costs a month, and imports an object (a new wfrn, then its page) or assigns
  it to another team's account, where it shows too until either imports it

Phase 5 watches the resources once they run:

- a Monitoring tab on each resource whose type keeps metrics: a chart per series over 1 hour to
  7 days with its latest, average and highest in words, a crosshair the keyboard moves and a table
  view, the alarms drawn on the charts as labelled lines and listed below them, created, edited and
  deleted in a small dialog
- the Alarms page: the account's alarms, firing first, filtered by state, each leading to its
  resource's Monitoring tab
- the Rightsizing page: the idle and oversized resources found in the last 14 days of metrics, the
  largest saving first, each resized or deleted through its type's own workflow, or dismissed for
  good or for 7, 30 or 90 days

Phase 4 changes resources in place, safely:

- Edit configuration on a resource's page (the Actions menu, or Edit on its Configuration tab):
  the type's update workflow as a form, what changes against what is asked for now and what the
  provider reports, a preview, the request as code, and a stale version caught before it lands
- before a delete, what depends on the resource, in words and nearest first, and a delete refused
  while anything does
- the request page on the BFF's job: a step that failed pauses the request, which offers the ways
  on its policies allow (retry the step, retry with changes and the step to run from, skip it,
  abort), with what went wrong in words; its approvals are decided there in one call, and a
  request that ended badly runs again as a new one
- bulk actions on a list's selection, as one request in waves: the workflow's own waves, two
  waves or all at once, how many at a time and the share of failures that stops it; the drawer
  shows the waves it makes of these rows, names the rows that cannot run and leaves them out in
  one click, and the request page follows every wave and row

Phase 4 adds tags and resource groups, across every type of the account and region:

- the create wizard's Tags step: every tag the account requires, pre-filled from its default (the
  environment follows the create's own field), and the person's own tags; Create waits, and says
  why, while a required tag is blank
- the Tags drawer on a resource's page: every tag as a name and a value, the required ones kept
- the Tag Editor: the resources of the types chosen that carry the tags named, in one table, and a
  dialog that sets and removes tags on up to 100 of them, saying which did not take it and why
- resource groups: a saved tag query and the resources it matches, created, changed against the
  version read, and deleted by typing the group's name
- create similar: the wizard filled from a resource, in its account or handed to another
- required tags: what each tag takes, its default and where that comes from

Phase 3b makes the console change resources, with the request in view at every step:

- a create wizard for every type a workflow creates, generated from the workflow's input schema:
  a step per `x-console` group, checked a step at a time, then a review that shows what was asked
  for, what the run will touch, what it costs a month and what policy says, before Create
- a resource page that follows its request: a banner while it runs or waits for its approver, the
  state as it settles, and once deleted the record kept readable until its tombstone ends; tabs for
  the configuration (with the fields that drifted marked), drift, activity, relationships, tags,
  permissions and the JSON
- an Actions menu with the type's workflows: each opens a drawer that previews the run (its steps,
  the resources it touches, the cost, policy's decision), takes a reason, and for a destructive one
  asks for the resource's name; a workflow waits while another request changes the resource, and a
  delete is held back by what was built from it
- view as code on every wizard, drawer and record: the `iaf` CLI, the REST calls through the
  console and the platform, a `WorkflowRun` manifest and JSON, with sensitive inputs masked
- every failed call said in words with what to do next, empty states that say what a type is for,
  and a getting-started tour on each service's page, written from what its providers publish
- in mock mode, writes run on a clock (6 s to approve, 8 s to apply) and survive a reload

Phase 3a makes the lists work at the size the console is built for, and the whole console usable
with the keyboard alone:

- a service's landing page counts each type's resources by lifecycle state and drift (the BFF's
  counts, "1,000+" past their cap), and each count opens the list filtered to it
- the list filters by status, drift, tags and free text, and sorts by name, created or updated,
  all on the server; it pages by cursor (25 to 200 rows) and keeps all of it in its link
- saved views (the filters, sort and page size under a name, up to 50 a type) and the column
  choice live in the person's preferences at the BFF, so they follow the person to any browser
- rows are selected one by one or a page at a time, and the page or the selection exports as CSV
  with the columns on screen
- the palette (Cmd+K or Ctrl+K, or /) finds services, resource types, resources from the search
  index, a pasted wfrn, the actions (create any type; on a resource's page, its type's actions),
  the pages and the settings (appearance, account, region, sign out); an action runs the same
  decision as its button, so a refused one opens the refusal
- WCAG 2.2 AA: a skip link first on every page, menus and panels by keyboard, dialogs that give
  the focus back to what opened them, notes that stay on screen, the dark appearance at AA, and
  axe in every journey, in both appearances and at phone width
- each page loads as its own chunk

Phase 1 adds who you are to the console and what you may do:

- sign-in through the firm's identity provider (OIDC), run by the BFF behind the
  `console_session` cookie; the console reads the session, never the cookie
- the account picker with the role you hold in each account, the region picker, and the
  notifications bell carrying the access requests that name you among their approvers
- identity and access: users, groups, the four managed roles (and any custom role, such as the
  approvers') with their policies statement by statement, access keys (the secret shown once,
  rotation, revocation, two usable a person), quotas with requests for more, and access requests
- a refusal view on every denied action, naming the layer that decided (the account's console
  IAM policies, or the platform's own role for the account), the statement when one denied, and
  your role, with Request access sending the ask to the account's approvers
- every button that would run a denied action is drawn locked and opens the refusal instead

Phase 0 built the shell and the read-only pages:

- the top bar with the Services menu, the unified search (Cmd+K or Ctrl+K), notifications,
  help, the account picker, the region picker and the user menu
- a collapsible sidebar per service, breadcrumbs, and a status bar with the build, the contracts
  pin, the console's health (the BFF's) and the theme toggle
- the console home, a landing page per service, a list per resource type, a resource page with
  its tabs, the requests list and one request, identity and access, settings, sign-in
- a development-only live feed of the account over server-sent events (the BFF's `/console/v1/events`)
- light and dark themes, visible focus, WCAG AA text, reduced motion honoured, phone widths

What the console does not do yet is still shown, and says which phase brings it: custom roles and
policies in phase 7.

## Workspaces

| Package                | Path                   | What it is                                                                                                                                      |
| ---------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `@iaf/console`         | `console/`             | The web app: React 19, Vite 8, TypeScript 5.9, Tailwind 4, Vitest, Playwright                                                                   |
| `@iaf/console-kit`     | `packages/console-kit` | The console's composites: forks of the platform's SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline and ProblemList behind a provider, plus VirtualTable, WfrnText, LifecycleBadge, StatusBadge, PropertyFilterBar, CursorPager, DotTag, CodeView and a labelled ConfirmDialog |
| `@iaf/ui`              | `packages/ui`          | Atlas, the platform's design system, copied verbatim from the platform at `c9529d5`                                                             |
| `@iaf/wfrn`            | `packages/wfrn`        | The TypeScript twin of the wfrn resource name, judged by the hub's fixtures                                                                      |

## Running it

Node 22 and npm 10.

```sh
npm ci
```

Against the BFF (the `console-bff` service, listening on 18081; its `demo` profile embeds the
platform-fake and a demo identity provider). The BFF is the only backend the browser talks to:
the Vite dev server proxies `/console`, `/auth`, `/demo-idp` and `/actuator/health` to
`CONSOLE_BFF`, which defaults to `http://localhost:18081`, and nothing to the platform. The BFF
reads the platform on the person's behalf; the edge must never route the platform's `/api/v1`
from browsers.

```sh
CONSOLE_BFF=http://localhost:18081 npm run dev
```

With no backend at all, in mock mode: the BFF's endpoints are answered in the browser, built from
the samples captured from the platform-fake (`console/src/mock/samples`), with the planned hints
added to the vm type so both the hinted and the default paths show:

```sh
npm run dev:mock
```

Both serve `http://localhost:3000`.

Live, the sign-in page has one button, "Continue with company SSO", which sends the browser to
`/auth/login?returnTo=/#/...`; the BFF runs the OIDC code flow and comes back with the cookie.
In mock mode the sign-in page stands in for the identity provider and lists the people the
identity service's demo seed knows; the mock keeps the "cookie" in this browser's localStorage.
The browser never names the person itself: the session cookie does, on every call. The mock's user ids are the short names;
the BFF's and the identity service's are `user_<name>`, which the pages never parse.

| Person               | Compute (123456789012)                          | Data (210987654321)        |
| -------------------- | ----------------------------------------------- | -------------------------- |
| gonzalo, Gonzalo Munoz | account-admin (owners)                        | account-admin (owners)     |
| dana, Dana Reyes     | operator, approver (members, compute-approvers) |                            |
| mateo, Mateo Silva   | operator (members)                              |                            |
| mia, Mia Chen        | viewer, approver (viewers, compute-approvers)   |                            |
| raj, Raj Patel       |                                                 | operator, approver (members, data-approvers) |
| ana, Ana Costa       |                                                 | operator, viewer (members, viewers) |
| noah, Noah Berg      | no account: known to the identity provider, refused by the console at sign-in | |

Every account is born with the identity service's four managed groups and roles: `owners`
(account-admin, which can be neither deleted nor left without a member, and keeps its role),
`members` (operator; the owning team joins it), `viewers` (viewer) and `auditors` (auditor). The
managed policies: `viewer` reads, asks for access and for more quota; `operator` makes every
change that is not a delete, IAM, a quota or the account, and reads IAM; `account-admin` does
everything; `auditor` reads, the audit trail included, and cannot ask for access. The custom
`approver` role (policy `approver`, statement `approve`) reads and decides the account's access
requests; the approvers groups carry it. The pages show account-admin as Admin. `Data`'s platform
service account holds only `compute:read`, so creating a virtual machine there is refused by the
platform layer, not by console IAM; IAM, access requests, quotas and the account are the identity
service's alone, and the platform layer never decides them.

The gate-1 story, in mock mode: sign in as Mia (viewer), open Virtual machines, press the locked
Create: the refusal names console IAM and that no statement of `viewer` allows `compute:create`,
and offers Request access, which goes to the approvers other than Mia: Dana and Gonzalo. Send
it; sign in as Gonzalo: the bell shows one request to decide; approve it. Sign in as Mia again:
Mia now holds `operator` (through `members`) and Create is no longer locked. Sign in as Dana and
open a machine: Delete is refused because no statement allows it (the operator's `changes` allow
leaves `*:delete` out), and the role's page shows that carve-out. Sign in as Raj in Data: Create
is refused by the platform, scope `compute:write` missing on `svc-data`.

## Checks

```sh
npm run lint              # eslint over every workspace
npm run typecheck         # tsc -b in every workspace
npm test                  # vitest in every workspace
npm run build             # vite build
npm run e2e               # the shell, identity, list, keyboard and resources journeys in Playwright, in mock mode
npm run contracts:verify  # the copied contracts still match contracts.lock
```

Playwright uses the Chromium under `PLAYWRIGHT_BROWSERS_PATH` (the development container ships
one at `/opt/pw-browsers`, which the config defaults to); only CI installs a browser. The journeys
run against the built app in mock mode (`npm run build:mock`, served by `vite preview`), the way
people get it; `E2E_DEV=1 npm run e2e` drives the dev server instead, with HMR, for debugging.
They run with reduced motion, as a person with that setting sees the console, write the
screenshots in `console/e2e/screenshots`, which are committed, and run axe on the settled page
everywhere they go (zero serious or critical findings, the WCAG 2.2 AA floor). `E2E_PORT=3100`
serves the app on another port, so two checkouts can run their suites at once; never two runs in
one checkout, which share `test-results/`.

## Design rules

- The layout is the AWS console's: a fixed top bar (brand, Services menu, one search field in
  the centre, notifications, help, account, region, user at the right), a collapsible sidebar
  per service, content with breadcrumbs, a status bar at the bottom.
- The visuals are Atlas and nothing else: its tokens, components and motion. Colours are only
  ever Atlas semantic utilities; no component carries a hex value.
- Apple-grade restraint: one accent, generous whitespace, few words, motion only where it
  explains something (a panel opening, a drawer sliding in), and none at all under
  `prefers-reduced-motion`.
- Every list has an empty state that says what the thing is for.
- Status colour lives in a dot, never in the text, so every label reads at AA on both themes.
- Plain English, no em dashes, "workflows" never "capabilities", "wfrn" always lowercase.

### The one third-party UI exception

`@tanstack/react-virtual`, in `@iaf/console-kit`'s `VirtualTable`, for lists of thousands of
rows. It is the only UI dependency outside Atlas and it never enters `packages/ui`.

## The contracts pin

`contracts.lock` names the commit of the `console-root` hub the contracts were copied from and
the sha256 of each copy (`packages/wfrn/SPEC.md`, `packages/wfrn/fixtures.json`, and
`contracts/platform/asyncapi-target.yaml`, the platform's event catalogue, which
`console/src/lib/events.ts` lists the types of: the stream names each server-sent event after its
type, so the feed must subscribe to every one).
`npm run contracts:verify` checks the copies still match; moving the pin is a commit of its own.

## Decisions

1. **Atlas is vendored, verbatim, at `c9529d5`.** The platform does not publish it as a package
   yet (platform change P-14 does); copying it unchanged keeps the diff to that package empty.
   The console's only changes to Atlas are two token overrides in `console/src/index.css`.
   Where Atlas falls short, the console forks the component into console-kit instead: the kit's
   `ConfirmDialog` ties the type-to-confirm label to its input, which Atlas's does not (a screen
   reader announced an unnamed field). A note for P-14.
2. **The platform's composites are forked into `@iaf/console-kit`, behind a provider.** The
   platform's SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline and
   ProblemList read the platform's API client directly; the forks read a `ConsoleKitProvider`
   (data functions and link builders), so the console wires them to its own client and routes.
   When P-14 publishes them, the provider is the seam.
3. **Descriptors are composed in the browser for now.** `console/src/lib/descriptors.ts` builds
   one `ConsoleType` per resource type from `GET /api/v1/resource-types` and `/catalog` with the
   plan's defaults (service from the owner, title from the plural, columns from the required
   scalars, lifecycle classes from the state names, actions from the catalog nouns) and honours
   the `service`, `console` and `x-console` hints of P-06 when a type carries them. Phase 3 moves
   this composition to the BFF; the shapes stay.
4. **A hand-rolled hash router**, like the platform's: `#/console/:service/:type/:wfrn` with the
   wfrn as one percent-encoded segment, typed routes, `pathFor`, and an optional
   `#/account/:accountId/` prefix. No router library.
5. **Four Atlas tokens move for WCAG AA.** Light secondary text becomes `#636366` (6.0:1 on
   white, 5.2:1 on a toast's translucent face over a dialog's backdrop, where Atlas's `#6e6e73`
   read at 4.4:1), light tertiary text `#6b6b70` (5.3:1 on white; secondary stays the darker of
   the two), the light accent `#0066cc` (5.6:1, and white on it 5.6:1) and the light critical red
   `#d70015` (5.4:1 both ways; Atlas's `#ff3b30` reads at 3.3:1 under the white text of a danger
   button, which phase 1 makes pressable to open a refusal); dark tertiary text becomes `#98989d`.
   The light values are set on `:root:not(.dark)`: on a plain `:root` they outranked Atlas's
   `.dark` block (same specificity, later in the cascade) and the dark appearance drew `#636366`
   on black. In the dark, Atlas's accent `#0a84ff` and red `#ff453a` read well as text but carry
   white text at 3.65:1 and 3.4:1, and no one colour does both; so the fills under white text
   (`.bg-accent.text-white`, `.bg-critical.text-white`: primary and danger buttons, the accent and
   critical badges, a done step, the sidebar's count) take `#0066cc` and `#d70015` in the dark too
   (5.6:1 and 5.4:1, 4.8:1 and 4.6:1 under the buttons' hover brightness). The warning outline
   button, the yes of a warning confirmation, writes its label in Atlas's amber `#ff9500` (2.2:1
   on white); in the light it takes `#c93400`, Apple's accessible orange (5.3:1 on white, 4.9:1 on
   its hover tint). Notes for P-14, since the console only overrides and never edits Atlas: an
   on-accent fill token would replace the fills, and an accessible warning text token the label.
6. **Status colour is in the dot.** `LifecycleBadge` (resource states) and `StatusBadge` (jobs
   and steps), both in console-kit, show a coloured dot next to a label in the primary text
   colour, instead of Atlas's `StateTag`, whose coloured text does not reach AA on its tints; the
   kit's `StepTimeline` uses `StatusBadge` for the same reason, and `DotTag` does the same for any
   other word with a tone (a preview's effects and policy decision, drift, roles). A note for P-14.
7. **wfrns are derived until the platform mints them.** A resource that carries a `wfrn` (P-05)
   is shown as named; one that does not gets a wfrn derived from the partition, region and
   account in the header plus its id, marked as derived (dimmed in lists, tagged on its page).
8. **The session is the one source of identity, account and region.** `console/src/lib/session.ts`
   keeps what `GET /console/v1/session` answered (user, account with its roles and groups, region,
   CSRF token, expiry) in memory behind `useSyncExternalStore`; the gate in `App.tsx` reads it
   once on load, sends a signed-out browser to the door with `next`, and signs out at expiry.
   Switching account or region is `PUT /console/v1/session`, after which every query is read
   again in the new scope. The API client adds `X-Csrf-Token` to every write and treats a 401
   from a BFF path as a sign-out; a 401 from the platform is just an error.
9. **Mock mode is a fetch interceptor in the browser.** `VITE_CONSOLE_MOCK=1` installs a pure
   request handler (`console/src/mock/server.ts`, unit tested) over the captured samples, with
   ETags, cursor paging and a Link header, regions, lineage, synthesised job steps, the events
   log and an EventSource that plays the feed. Playwright runs against it with no server beyond
   Vite.
10. **The descriptor cache survives the platform.** Resource types, the catalog and the regions
    are kept in localStorage with their ETags; a 304, a 5xx or an unreachable platform answer
    from the cache, so the shell always draws its navigation.
11. **Virtual tables are grids.** `VirtualTable` uses the ARIA grid pattern (keyboard rows, an
    active descendant, sortable column headers) over `@tanstack/react-virtual`, with its empty and
    loading states outside the grid so the markup stays valid.
12. **Writes wait for phase 3; refusals do not.** An `ActionButton` takes the BFF's decision for
    its verb: denied, it is drawn locked and opens the refusal; allowed but not built yet, it is
    disabled for assistive technology and says which phase brings it; allowed and built, it acts.
    The decisions come from `GET /console/v1/types`, which carries `allowed` and a `Denial` on
    every action for the signed-in person, account and region, merged into the composed types
    by `service/type` and verb (`console/src/lib/permissions.ts`). A verb the BFF has not
    decided counts as allowed: the call itself refuses, and the refusal view shows that answer.
13. **Accessible names contain the visible text (WCAG 2.5.3).** The header's search field and
    menu triggers name themselves with visually hidden text instead of `aria-label`, so the name
    holds the visible words at every width. Atlas's `UserMenu` names its collapsed trigger
    "Account: name" over visible initials, and its `Select` names its trigger with `aria-label`
    alone, which hides the chosen value; so the header has its own `UserMenuButton` over Atlas
    primitives, and every `Select` the console renders is named "label: value". Both are notes
    for P-14. The journey's axe check (zero serious or critical findings on the home, a list, a
    resource and a request) keeps this true.
14. **Descriptor hints are read as P-06 writes them.** `defaultSort: -created` sorts newest
    first and a key no column has falls back to the name; `detail.tabs` names tabs by id
    (`configuration`, `json`) and the console shows their labels, an empty list keeping the
    defaults. A field's label is the schema's `title`, else its name split into words
    (`memoryGb` is "Memory GB"). On a resource page the ungrouped fields read together, first,
    before the named groups; in a form they keep their place.
15. **A refusal names the layer that decided.** Every 403 but the CSRF one, and every denied
    action of a type, carries a `Denial`: `layer` (`console-iam` or `platform`), the action, the
    resource, the role evaluated, the policy and the statement that decided, the missing platform
    scope and service account, whether access can be requested and which managed role would
    allow it. `RefusalDialog` draws the two layers in the order they decide, marks the one that
    refused, lists the facts, and holds the Request access form. The identity service decides
    with the first matching deny, else the first matching allow (an allow's `notActions` carved
    out of it), else a default deny that names the role and the weakest managed role that would
    allow it; the platform layer checks the account's service-account scopes after console IAM
    allowed.
16. **Identity and access writes are greyed for everyone but admins before any call.**
    `adminOnly()` is the console's own rule for phase 1 (only account-admin writes IAM); it
    greys the button with a requestable denial that suggests `account-admin`.
    The server's answer, with the statement that decided, replaces it the moment a call is
    refused. Every IAM write carries an `Idempotency-Key` and, where the identity service
    versions the resource, `If-Match`.
17. **Access requests are the approval path.** Request access in a refusal posts to
    `/console/v1/access-requests` (a reason of 1 to 2,000 characters, the role suggested by the
    refusal); the request names its approvers (the members of the groups whose policies allow
    `access-request:decide`, the requester left out), who see it in the bell and on the access
    requests page and decide it there; nobody decides their own, a 403 whose denial says so. An
    approval puts the requester in the managed group of the role
    (else the first group holding it), and their next session or a refresh of their roles picks
    it up. A second ask for the same role answers 409 with the pending request as the body, and
    the dialog shows that one. A request pending for more than 30 days is served `expired`.
    Reading the account's requests takes `access-request:list`; the identity service answers
    anyone else's list with their own only and refuses them someone else's, so the console asks
    once for the requests of a requester nobody is: a 200 says the person reads the queue, a 403
    that they see their own (account-admin and auditor read it without asking).
    Quotas are asked of the platform team the same way: the access keys page lists the
    account's quotas (`jobs-per-minute` from the platform's bucket, `access-keys`, and
    `resources.<type>` for every platform resource type by its name, such as `resources.vm`,
    counting the account's live resources of the type) and lets a member ask for a higher limit
    (`:request-increase`, a reason of 10 to 1,000 characters, one open request per quota). The
    requester or an admin withdraws it while it waits; the platform team decides, never the
    account's own admins.
18. **The mock BFF is the same pure handler, with a session store.** `console/src/mock/server.ts`
    speaks the BFF's contract next to the platform's: `/auth/login` with `login_hint` (the demo
    identity provider), `/auth/logout`, the session, accounts, regions, the types with decisions,
    identity and access, quotas and access requests, over an in-memory directory
    (`console/src/mock/directory.ts`) that seeds and decides as the identity service does: its
    demo people and approvers, the managed groups, roles and policies (statements in order,
    `notActions` carved out of an allow, the first deny wins, else the first allow, else a
    default deny naming the weakest managed role that would allow it), access keys, quotas and
    access requests, and the two accounts' platform scopes. One choice is stricter than the
    identity service: an approver never decides their own request. The browser installer keeps
    the session and the directory in localStorage, so a reload stays signed in and keeps what
    was changed, and a sign-out clears the session.
19. **The list asks the server for everything it shows.** The filter bar's tokens become the
    BFF's list parameters: free text `q`, Status `state`, Drift `drift`, `Tag: <key>` `tag.<key>`,
    each with `=` and one value per property (the newest wins). A token the BFF cannot answer is
    dropped, never applied in the browser to one page, which would misstate the rest. Only name,
    created and updated sort (the state service's orders). The count is
    `GET /console/v1/r/{type}:counts`: `total` and `drift` take every filter, `byState` every
    filter but the state, and `cappedAt` is set once a count reaches the cap ("1,000+"), null
    while exact; without it the list says how many rows are on the page. Deleted resources
    (tombstones) stay out of the list and the total unless the filter asks for Status = deleted,
    which the landing's Deleted count links to.
20. **The page's query is the list's state.** `f` (the tokens), `sort`, `size` and `view`, with
    the defaults left out and written with replace, so Back leaves the list instead of undoing a
    filter. The cursor trail and the selection are keyed by that state (`useKeyedState`), so a new
    filter starts on page 1 with nothing selected, and no effect has to reset them.
21. **Saved views and columns are the person's preferences.** `GET` and `PUT
    /console/v1/preferences` keep `savedViews` and `columns` per type key behind the version the
    BFF hands out (ids `v_…`, names of 1 to 80 characters unique in a type ignoring case, 50 views
    a type, 50 filters a view with the filter bar's operators, a sort on one column, pages of 1 to
    200); a write that meets another tab's newer version (409) reads it,
    applies the change again and retries. The local copy names its owner, so a browser shared by
    two people never shows one person's views to the other. The mock checks the same rules.
22. **Dropdowns are WAI-ARIA menu buttons.** `MenuButton`: Enter, Space or Down opens a menu on
    its first item and Up on its last; the arrows, Home and End move; Escape closes it on its
    button, even when the focus was lost inside; Tab moves on. A panel (the column chooser) keeps
    the browser's Tab order and closes when the focus leaves. Panels slide back inside the
    viewport, so none hangs off a phone. The notes of the later-phase and refused buttons are
    `Hint`s instead of Atlas's CSS tooltip, which is always in the layout and widened the page
    under a button at a phone's edge: shown only on hover or focus, under `<body>`, kept on
    screen, dismissed with Escape, and hoverable (WCAG 1.4.13).
23. **Dialogs focus their first field with `data-autofocus`.** Atlas's overlay remembers what had
    the focus when it opened and gives it back when it closes; React's `autoFocus` moves the
    focus into the dialog first, so the dialog gave the focus back to itself and the person
    landed on the page's body.
24. **Each page is a chunk of its own.** The shell loads the sign-in page eagerly and every other
    page with `React.lazy`, under a quiet loading state, so the first load carries the shell and
    one page. "Skip to content", the first stop of the Tab order, focuses `#main`.

25. **The create wizard is generated from the create workflow's schema.** The ungrouped fields
    make the first step, each `x-console.group` a step of its own in schema order, then the review
    (`console/src/pages/create/steps.ts`); the type's schema lends its `group`, `help` and
    `sensitive` hints to the workflow's input where the input has none, so a provider that groups
    its type gets the same wizard. Next checks the step and moves the focus to the first field that
    needs attention, or to the next step's heading. The review asks the BFF for the preview (cost,
    policy, the resources the run touches) and shows the request as code; Create sends one
    Idempotency-Key per body (`lib/submission.ts`), so a second click or a retry starts one job.
26. **The resource page reads two sources and keeps going on one.** The platform's record and
    the BFF's view (`GET /console/v1/r/{type}/{wfrn}`) load side by side, and either alone draws
    the page. While a request is open the page polls every 2.5 seconds and follows it to its end.
    Drift is read as the contract gives it: `drift` (a boolean), the open `finding` and
    `observedAt`; a resource no scan has read yet says "Not checked yet" rather than "In sync". A
    410 is a tombstone (under `detail` from the BFF, at the top level from the state service): the
    page says when it was deleted and until when it stays readable, and nothing runs on it.
27. **Every workflow on a resource runs from one menu and one drawer.** The Actions menu is a
    `MenuButton` (decision 22) of `ActionMenuItem`s, which follow `ActionButton`'s rules: denied
    shows the lock and opens the refusal, a later phase says which, and while another request is
    changing the resource (the BFF answers 409 unless it is active or failed) an item says it waits.
    `?action=<verb>` opens the drawer from a link. The type's verbs leave out the workflow that
    creates it (`lib/verbs.ts`: an image's build is its create, not something done to an image).
    Destructive verbs sit beside the menu as buttons and confirm by name; a delete with live
    dependents is refused by name (409 `dependents.exist`, agreed with the BFF for phase 4).
28. **View as code shows the request, never a secret.** `lib/code.ts` writes the CLI as `iaf
    <service> <verb>-<type> --field value`, the REST calls through the console and to the
    platform, and the manifest as `apiVersion: iaf.platform/v1`, `kind: WorkflowRun`, the form
    to keep in Git; a type's `sensitive` fields and the workflow's own are replaced by
    `<sensitive: fill in>` before any of it is written. The kit's `CodeView` scrolls in a region
    that takes the keyboard's focus, so the code reads without a mouse.
29. **Failures are said in words, with what to do next.** `lib/plain.ts` turns a status and the
    BFF's error code into a title and a next step (a 409 says something changed first and to
    reload, a 429 how long to wait, a 504 that the request may still be running, so check the
    requests before sending it again); problems go back to the fields they name; a refusal is
    never explained here, the refusal dialog names the layer that decided.
30. **Step-up keeps the person's place.** A 403 `step-up.required` (or any 403 whose detail says
    `stepUp`) sends the browser to the BFF's `login` with `returnTo` set to the console's own hash
    route, which the BFF cannot know (a Referer carries no fragment); the BFF accepts only paths
    starting with `/`.
31. **Getting-started tours are generated, not written.** `lib/tour.ts` orders a service's types
    so a type comes after what its create needs (`x-resource` inputs), says what each create asks
    for and what can be done afterwards, and ends with following requests and access. A new
    provider's service gets a tour with no console change; the person can dismiss it per service.
32. **The mock runs requests on a clock, from the same rows as the list.** `console/src/mock/
    commands.ts` answers the BFF's creates, verbs, previews, tags, activity, drift, lineage and
    jobs: an approval lands after 6 seconds, the apply 8 seconds later, a name with `fail` fails,
    and every write goes into a journal the browser keeps and replays after a reload. Its rows are
    the list's rows (`mock/resources.ts`) with the console's own requests laid over them, so the
    list and the resource page show the same wfrn, account, state and drift. A resource younger
    than the hourly drift scan has not been read; a deleted one stays readable for 30 days. The
    review's quota rule and the Quotas card count `resources.<type>` with one function
    (`usedByTypeOf`), so they always agree, and a deleted resource frees its place at once.
33. **A request is read from the BFF's job alone, and steered by its `can`.** The request page
    reads `GET /console/v1/jobs/{jobId}` and nothing from the platform: the job's `can` decides
    each choice it shows (cancel, retry, skip, abort), its `attention` names the paused step and
    the ways on the platform offers, its `approvals` are decided in one call
    (`approvals:decide`, what was left said with why), and a bulk run's `batch` lists its waves
    and rows. A paused job retried goes on in place; an ended one runs again as a new request,
    which the page opens. Retry with changes sends only the fields changed (the BFF merges them
    over the job's input) and the step only when it differs from where the retry starts anyway; a
    create keeps its name, and a bulk run takes no changes. Each pause gets its own
    Idempotency-Key, so a second retry of a step is not the first one replayed. What went wrong
    is one card (`FailureCard`), so a fuller explanation can take its place.
34. **A bulk run is one request in waves, judged row by row before it is sent.** The list's
    selection runs any verb with a batch route (create aside). The rollout is the workflow's own
    unless the person changes it: kept, the request names none and the BFF applies its own;
    changed, it is sent whole, its waves always ending at 100%, so the waves the drawer shows
    ("3, then 10, then 37 rows") are the ones that run. `:batch:preview` judges every row: the
    ones that cannot run as they are are named with why and left out in one click; what depends
    on the rows is shown for a delete or a destructive verb only. An input every row answers from
    its own attributes (a VM's environment) stays each row's, never flattened to one value; a
    workflow with a reason of its own takes it in its form. A destructive verb asks for the verb
    and the count typed out (`delete 12`).
35. **The mock steers jobs by the BFF's own rules.** `console/src/mock/jobs.ts` follows the BFF's
    `Jobs.can` and `JobRetries`: cancel is open to any job still running (paused ones too) under
    `jobs:cancel`, a retry asks the target workflow's own action, failed, rolled-back and paused
    jobs run again but a cancelled one does not, and a batch reruns only its failed rows. A step
    fails on cue: a create named with `fail` fails its first run, and any other verb whose
    `reason` contains `fail` fails until a retry's change removes it. A wave whose prod rows wait
    is approved by a person (one decision a wave) or, after 6 seconds, by the platform's own
    approver; a rerun keeps the job it retries in the mock's own state (`retryOf`).
36. **A delete shows what depends on the resource first, and is refused while anything does.** The
    preview of a delete or a destructive verb carries its blast radius (what was built from it, up
    to three steps away; `byAccount` counts what lives in other accounts and never names it), and
    `BlastRadiusCard` says it in words above the preview: how many would be left without what they
    were built from, nearest first, each a link, other accounts as counts, a note past 500. A live
    dependent (judged by its own type's lifecycle) denies a delete by the `dependents.exist` rule;
    the drawer says to delete or move them first, a 409 `dependents.exist` at send time shows the
    radius it carries, and the confirmation repeats it in one line.
37. **Edit configuration sends only what changed, against the version the person read.** The
    type's update workflow (P-08) is the form: its input without the subject, its `mutable` fields
    and `operational` inputs, starting from the desired attributes. "What changes" lists only the
    changed fields, now and after, with what the provider reports beside now when it differs; the
    PATCH carries them as `input` (absent means unchanged), the reason beside it, and `If-Match`.
    A 409 naming a newer version reads "Someone changed this first", and Load the latest carries
    the edits onto it; a type with mutable fields but no update workflow says "Editing is not
    offered by this provider".

38. **A create names every required tag itself.** The account's tag policy
    (`GET /console/v1/tag-policy`) lists the tags each new resource must carry, with a list of
    values or a pattern, and a default from the account, the organization, or the create's own
    field (`input.<field>`). The wizard asks for each on a Tags step before the review and sends
    `tags` on the preview and the create; the BFF never adds one a request left out, so the
    console does not either. A value a tag may not take keeps the step; a blank one reaches the
    review, which says what is missing, and Create stays disabled until it is set (the preview
    says the same as a deny rule). A tag that copies a field follows it until the person sets the
    tag. `lib/tags.ts` holds the BFF's rules word for word, so the forms and the mock agree.
39. **The required tags stay on a resource; the Tag Editor searches by type and tags.** The Tags
    drawer replaces a resource's tags with one PUT, so a required tag it carries keeps its row
    (no Remove button, and the reason in words), its value checked as it is typed; a resource
    older than the policy is offered the missing ones, never made to take them. A new tag's name
    is 1 to 32 lowercase letters, digits or hyphens, as the state service takes it. The Tag
    Editor's query (`type`, `tag.<name>`) lives in the address; the BFF searches no free text
    there, so a name filter narrows the loaded page and says so. Its dialog changes up to 100
    resources with one `tags:apply`, each its own change, cannot tick a required tag for
    removal, and ends on how many took the change and why each other did not.
40. **Resource groups are greyed by role, edited by version, deleted by name.** A viewer reads, an
    operator also creates and changes, only an admin deletes (`resource-groups:<verb>`), and the
    pages grey a write for the others before any call, as decision 16 does for identity; the
    BFF's Denial replaces it on a 403. An edit sends `If-Match` with the version it read; a 409
    says the group moved on and reads it again. The delete asks for the group's name at
    `?action=delete`, so a fresh sign-in comes back to the dialog. As the identity service does,
    the mock lets console IAM alone decide them: the platform's role for an account never does.
41. **Create similar opens the wizard from the BFF's draft.** `?from=<wfrn>` fetches the clone
    draft (its fields, the name as `<name>-copy`, its tags, notes on secrets, names and
    references) and opens the wizard filled from it under a note linking to the source. Another
    account comes from the draft's `targets`, locked where the person may not create the type:
    the draft for it is fetched in this session, kept in sessionStorage under a one-time token,
    and read once by that account's wizard (`#/account/<id>/.../create?draft=<token>`), which
    switches the session to it first.
42. **A revert is one click; accept and ignore ask once.** A revert only puts back what was asked
    for, through the type's own update workflow and its approvals, so it needs no confirmation:
    the card says Reverting, links the request and follows it, and the finding closes when the
    request succeeded, never when it was merely sent. Accept changes the desired state, and Ignore
    silences the drift service, so each asks once in a small dialog, with an optional reason that
    the history keeps. Each decision is gated twice: the type's `update` permission (refused, it
    shows its lock and the refusal) and the finding's `can` (not offered, it is greyed with the
    reason in words beside it, such as a field the update workflow does not set).
43. **Drift codes are read by their prefix.** The drift service's 409 codes open `error` with a
    stable prefix (`finding.closed`, `finding.reverting`, `finding.notRevertable`,
    `finding.unwatched`, `revert.needsPerson`), and `lib/plain.ts` matches the table's code when
    it stands alone or opens the error, so a code with words after it is still explained. A
    refused decision reads the findings again, since the refusal means they changed.
44. **Reverting many is many single reverts.** Selected rows are reverted
    one call each, with their own Idempotency-Keys, counted as they go; what could not start is
    named with its reason and stays selected for another try. There is no batch revert in the
    contract, and a revert's request is the update workflow's own, so each one is followed and
    approved like any other change. Findings carry no name, so the page shows the resource's id
    from its wfrn and links to its Drift tab.
45. **The unmanaged list is what the runs found, never a scan of its own.** Find unmanaged starts
    a discovery run per type (one per type and region at a time; a second follows the running
    one) and shows it going; the list holds what the account's finished runs found and what
    another account assigned to it. Import asks for the name to manage the object under, then
    links to its page; Assign to team offers the guessed account first, or an account id typed.
    Both need the type's `create` permission and the object's `can`, so an account whose platform
    role may not create the type can still find and read what it holds. In mock mode
    (`console/src/mock/drift.ts`) the findings are the scan's with the decisions laid over them,
    a revert closes when its update job succeeded (the provider refuses the first revert of
    `vm-gen-037`: its request pauses on the failed step, as hub decision 24 has every failed step
    do, so the revert stays running until someone retries, skips or aborts it there, and an
    aborted one leaves the finding open, offered again), each resource carries a short seeded history,
    and a discovery run takes 4 seconds and finds twelve machines and four clusters across the two
    accounts, with owner guesses of each confidence.
46. **A metric series is a function of the resource and the time, so every reader agrees.** The
    mock (`mock/monitoring.ts`) works each point out of the resource, its size and the moment (a
    working day in UTC, quieter weekends, disks filling slowly), so a chart, an alarm and the
    rightsizing pass read the same numbers. A read keeps the contract's defaults (24 hours, a point
    every 5 minutes up to a day, else hourly), answers at most 500 points, needs
    `<service>:observe`, and past 120 reads a minute answers 429 `observe.budget` with a wait.
47. **Alarms are judged when they are read, by the average the chart shows.** An alarm watches one
    series of one resource, above or below a threshold, averaged over 1 to 1440 minutes; its state
    (OK, In alarm, Not enough data) and since when are worked out from the series at each read, as
    the drift service's minute-by-minute evaluation would leave them, so they never go stale. The
    console picks the id (`PUT /console/v1/alarms/{id}`), so a retried create replaces rather than
    doubles; writing one needs `<service>:update` on the resource, reading them `get`.
48. **A recommendation runs through the type's own workflow, on behalf of the person.** The daily
    pass (05:00 UTC) reads 14 days of p95 CPU and memory: both under 5% is idle (delete it), both
    under 30% oversized (the smallest size that keeps p95 under 60%, never a larger one). Apply
    starts the update workflow with that size, or the delete workflow with `confirm: true` once the
    name is typed; the recommendation is `applied` with its job id at the 202, so the page links
    the request at once. A dismissal keeps its reason, for good or until its date.
49. **Charts are drawn by hand and say in words what they show.** `pages/resource/MetricChart.tsx`
    draws SVG with the theme's tokens: one accent line over a faint area, four value ticks, a few
    time ticks, thresholds as dashed labelled lines. Each chart is titled with its unit, sums itself
    up in a line (latest, average, highest) and turns into a table; its plot is a slider whose
    arrows, Page keys, Home and End move the crosshair and whose value text reads the point. No
    chart library: the scales and ticks are `lib/chart.ts`, tested on their own.
85. **The simulator answers from the code that decides.** `mock/directory.ts` has one `decide`:
    the guardrails first (any deny, then the allow-list), then the roles' denies, then the first
    allow that its role's boundary also allows, then a bounded or a default deny. `authorize` and
    `POST /console/v1/iam/policy-simulations` both call it, the simulation with its unsaved
    policies held by a stand-in role and its draft boundary in place of the role's own, so what
    the simulator says is what the next request gets. Nothing is written, and the answer carries
    the authorization version it was decided at.
86. **A tag condition reads the resource the request is about.** A statement's `tag.<name>`
    condition matches the resource's tags, which the request's context carries (`context.tags`);
    the mock's single-resource checks pass them, and the simulator sends a picked resource's tags
    with each check. A check on a type, such as the Actions menu's, has no resource and so no tags:
    the menu offers what the type allows, and the request on the VM is refused by the statement
    that names its tag, as the restart-only-prod journey shows.
87. **One document, two views, one list of problems.** The editor keeps a single policy document;
    the JSON view is its text, read on each keystroke by a reader that keeps the line of every
    value by its field path (`statements[1].actions[0]`), so the identity service's 400 problems
    and the console's own checks (the same rules, ported) show at the field in the visual view and
    at the line in the JSON one. While the JSON does not read, the visual view shows the last
    document that did and says so; a save waits until it reads. Saves send `If-Match` with the
    version read; a 409 offers to load the latest or keep yours.
88. **Boundaries belong to roles; guardrails to the organization.** The identity contract bounds
    roles only, so a person's page shows the boundary over each role they hold and links the
    simulator rather than offering a boundary of their own. The account's guardrails
    (`GET /console/v1/iam/guardrails`) are read by anyone who may list IAM; the organization's
    (`/console/v1/org/guardrails`) are written by an organization admin only, through the BFF as
    its internal principal, and a refusal past a boundary or a guardrail cannot be requested.
89. **A custom policy decides nothing until a role carries it, and stays while one does.** A new
    policy is offered for a role at once (attach, or create a role with it granted by groups); its
    delete is refused with a 409 naming the roles that carry or are bounded by it, which the page
    lists with links. Managed roles keep their policies, and Admin takes no boundary, so an
    account always has someone who can change everything.

90. **A review decides who keeps a group, and the due date decides what nobody did.** A campaign
    is the identity service's: everyone's memberships when it starts (who), one item per person
    and group with the roles it carries (what), and a due date of 1 to 90 days. The contract has no
    list of reviewers, so the reviewers are whoever may `access-review:decide` (the account's
    admins, and anyone holding Admin for a while); the page and the start dialog say so, and that
    nobody reviews their own. Every page that shows the due date says what it does: what is not
    reviewed by then expires and the person loses the group, the last owner excepted.
91. **Deciding many is many single decisions, by keyboard as well.** There is no batch decision,
    so a selection is one `:decide` per item with one reason, counted as it goes, with what
    failed named. Removing needs a reason, keeping does not. In the item list the arrows move
    between people and K and R keep and remove the one in focus, each through the same dialog.
92. **A held role is visible while it lasts.** The session lists the roles held for a while
    (`account.elevations`, the identity service's `HeldElevation`); a banner above every page names
    each with its time left, loud and red for break-glass, and reads the session again when the
    earliest ends. The BFF marks an action that only a held role allows (`elevated` on the
    action's decision); the button carries an "elevated" mark and the menu item says so.
93. **The refusal offers an hour only when a rule grants it at once.** When a console IAM refusal
    suggests a role the person lacks and an auto-approval rule (`GET .../elevation-rules`) covers
    that role for the roles they hold through groups for 60 minutes, the refusal offers Ask for
    this role for an hour beside Request access; once active the roles are read again, so closing
    the dialog finds the action unlocked. Without such a rule it links to the elevations page,
    where an admin decides. Boundary and guardrail refusals never offer it.
94. **Closing an account and break-glass are the organization admins', and say what they do.**
    The accounts page lists every account to organization admins (`session.organizationAdmin`;
    404 for anyone else, which the page explains). Break-glass asks for a person, role, at most
    4 hours, a ticket, a reason and an explicit approval, warns that it bypasses the account's
    approvers, and shows the line the account's trail keeps. Closing lists what it ends, asks for
    the account's name typed and a reason, and is refused while the account holds resources
    (409 `account.hasResources` with the count of each type, the plan's rule for WP 7.7), since
    closing leaves resources where they are. In mock mode (`mock/access.ts`) Gonzalo is the
    organization admin and the rule is the identity service's `operators-admin-two-hours`.
80. **A device sign-in is approved by a person who sees what asks, and never by a link.** `#/device`
    reads the grant by its code (`?code=` from the CLI's `verification_uri_complete`, or typed and
    normalised to `XXXX-XXXX`) and shows the client, the machine in words from its user agent, the
    address, when it asked, the time left and the one scope in plain words, with a warning to
    approve only a sign-in one started. Approve and Deny are buttons the person presses: the page
    never approves from its address. Approving is the BFF's step-up 403 past ten minutes; live the
    API client follows it to the BFF's own `/auth/` sign-in and back to the code (the page builds no
    redirect), in mock mode the page signs in again in place; either way Approve is pressed again.
    Denying needs no fresh sign-in. A signed-out link keeps its query through the sign-in, so the
    code survives it.
81. **CLI sign-ins sit beside access keys and belong to the person.** `GET /console/v1/api-sessions`
    lists the open ones newest first (client, machine, address, account, signed in, last used,
    ends); everyone signs their own out after one confirmation (`DELETE`), whatever their role, so
    nothing is greyed by role. The mock seeds each person a laptop CLI and a CI job's SDK, opens a
    session when a grant is approved, and journals approvals, denials and revocations (`cli.`).
82. **The console shell runs the CLI's command core, through the BFF only.** No `console-sdk`
    branch exists yet, so `lib/shell/core.ts` is the core, written to the interface the generated
    one would have (`execute(line, { client, emit, describeError, sleep, signal, newKey })`, output
    as text, table or JSON blocks, `toText` for a terminal); `lib/shell/console.ts` adapts it to the
    BFF client with the page's session, so swapping in `@iaf/console-cli-core` is one import. It
    makes no platform call. `ls <type> --filter k=v` sends `q`, `state` and `drift` as themselves
    and anything else as `tag.<k>`; `run` checks the type's decision first and refuses with its
    reason, asks `--confirm` for a destructive verb, sends one Idempotency-Key, and `--wait` or
    `job <id> --follow` reads the job every 2 seconds until it ends or pauses (Ctrl+C stops
    following, the job goes on). A leading `iaf` is dropped, so a sample line runs as it is.
83. **The shell is a keyboard page that a screen reader can follow.** Enter runs, Up and Down walk
    a history kept per person in this browser (100 lines), Tab completes a command only when one
    name fits and otherwise moves on, Ctrl+L clears. Each result lands in a `log` region; tables
    are real tables in their own focusable scrolling region, so a phone scrolls a wide table and
    never the page; tones are a rule at the left, never colour on the words alone. A wfrn links to
    its resource and a job id to its request, built from the console's own routes.
84. **The developer portal reads the API's own description, and its tiles are samples.** The
    operations come from `GET /console/v1/openapi.json` grouped by tag (the mock serves an outline;
    the full contract is linked), and sample calls are built from the chosen type's workflow schemas
    (examples, defaults, first choices, the required tags from the tag policy) for the shell, the
    CLI, REST and the TypeScript and Java SDKs. The IDP tiles (team resources, drift, a sample cost,
    and create workflows) are marked as samples until the firm's examples arrive; a tile is kept as
    a shortcut in this browser for the person, a workflow tile is greyed with the refusal where the
    person may not run it, and a tile's target becomes a link only when it is a console route or
    https.
70. **Notifications follow what already happened, once per event and person.** The bell keeps the
    access requests to decide (phase 1) and adds the person's newest notifications with the unread
    count (`GET /console/v1/notifications`), marks one read when it is followed and all with one
    `POST /console/v1/notifications:read`, and opens the BFF's live stream (`GET
    /console/v1/events`, listening for `notification`) once per person, refetching every reader on
    an event and announcing it politely; an access request already listed to decide is not repeated.
    The Notifications page filters by unread and kind in the address. A notification's `link` is
    read into the console's own route (`/r/<type>/<wfrn>`, `/jobs/<id>`, `/access-requests/<id>`,
    `/digests/<week>`, with only a `tab` kept from its query), scoped to its account when it is
    another one: nothing from the data becomes an href as it came. In mock mode
    (`console/src/mock/notifications.ts`) the notifier runs at the end of every settle of the
    clock, so a console job that succeeds, fails or pauses notifies its requester at the moment it
    did, an approval it opens notifies the approver group's members and its answer the requester,
    and an access request notifies its approvers and its answer the requester; something older
    than three days arrives read.
71. **Notification settings save the differences from the defaults, against the version read.**
    The page shows every kind with its channels, the defaults filled in (the console for every
    kind; email and the linked chats too for failures, pauses, approvals, access requests, budget
    alerts and the digest), and saves only the kinds that differ, so an untouched kind follows the
    default as it moves. One PUT with `If-Match`; a 409 reads them again and says so. A channel
    that is off or not linked greys its boxes and its test, with the reason in the box's name. The
    page lives at `#/settings/notifications`, found from the user menu, the Settings page, the bell
    and the palette.
72. **A chat is linked by confirming its token once.** `#/chat-link?token=` reads what the token
    names (the platform, the workspace, the chat user) without trusting it, asks once, and posts
    it; the BFF decides. An expired, foreign or taken token is refused in words with what to do.
    Linking turns that channel on; unlinking the last link of a platform turns it off. The linked
    chats are listed on the same page and each unlinked after one question.
73. **The weekly digest is read as words with its numbers beside them.** The Digests page lists the
    session account's weeks, newest first; a week reads as four parts (changes, drift, cost,
    access), each its text, its highlights as links and the facts it was written from, and says
    whether the copilot or the template wrote it. Every managed role reads them (`digests:get`,
    decided by the console's IAM alone; the platform's role never decides them).
74. **Notifications, settings and chat links are the person's own.** Like preferences, no IAM
    action guards them: a viewer such as Mia reads and marks their notifications, chooses their
    channels and links their chats, so the pages refuse nothing on role. Only the digests, which are
    the account's, are read through IAM, and a refusal there shows the denial.

95. **The trail is verified in the browser, from the evidence pack alone.** Verify the chain reads
    `GET /console/v1/activity/evidence` for the period and checks it with `lib/trail.ts`: every
    event's sha-256 over its canonical JSON (sorted keys, no whitespace, `hash` left out), every
    link to the event before it per resource, every seal's count, root and link to the seal
    before, and every ES256 signature against the published keys through Web Crypto. The answer is
    one sentence: the chain holds over the span, or it breaks at a named resource and version, and
    why ("changed after it was recorded", "a version is missing", "it does not link"). The pack is
    for the account's admins and auditors (`activity:evidence`); everyone who may list activity
    reads the trail. A URL inside the pack (a job's evidence) is never made a link.
96. **Time travel replays the resource's own events, and a revert is an edit.** The Activity tab
    reads the resource's state events once and replays them (`lib/timeTravel.ts`) to any instant,
    so moving the slider costs no request; the table sets what was asked for and what the provider
    reported then beside what is asked for now. Revert to this asks the BFF's revert plan, then
    opens the ordinary Edit configuration drawer filled with the plan's values and a note naming the
    moment, so the change is reviewed, previewed and approved like any other edit; a plan that
    cannot run says why (not yet built, deleted then, busy now, nothing to change). Inventory as of
    a date lists each type with `?at=` at the end of the chosen UTC day.
97. **Lineage is the state service's, through the BFF.** The Relationships tab reads
    `/console/v1/r/{type}/{wfrn}/lineage` and lays it out in columns by hops: built from to the
    left, built from it to the right. What made it (the workflow, its version and the request)
    comes from the resource's first events. Resources in accounts the person cannot read are
    counted and never named; parents the console does not manage are listed by reference. The
    browser talks only to the BFF: no platform read remains on the resource page's tabs.
98. **The map is laid out by depth and moved through by its links.** `lib/graph.ts` puts each
    resource in the column of its longest chain of parents, stacks a column in blocks of 16, and
    maps the arrow keys to the links (left to what it was built from, right to what was built from
    it, up and down within a column); the page draws SVG by hand, zooms with buttons, + and - and
    Ctrl with the wheel, pans by dragging, and says each focused resource aloud with what it was
    built from. Cost is shown by the dot's size as well as the legend, so no colour stands alone.
    The list view holds the same resources and links as a table. A big map starts at a size its
    names can be read at; Fit shows all of it.
99. **The mock's trail is a real chain with one break on purpose.** `mock/state.ts` derives every
    resource's events from the dataset, the console's own jobs and its drift history, chains them
    with sha-256 (`lib/sha256.ts`, synchronous so the mock stays a pure function), seals each UTC
    day and signs the seals with two demo P-256 keys (`mock/es256.ts`, rotated on 1 October). One
    event, the first change requested on cache-043 (`vm-gen-033`), is altered after hashing, so a
    week verifies and the month breaks there. batch-01 (`vm-demo04`) carries the history the
    journeys replay: sized up to 8 CPUs three days ago and back down to 2 this morning. Lineage
    and the map come from the same rows, with each machine's monthly price from the cost mock.

75. **Visits and favourites live at the BFF, never in the browser.** A type's list and a resource's
    page post their visit (`POST /console/v1/recent`, the key a type key or a wfrn, the title as
    shown); the home's Recently visited and the palette's Recent group read `GET
    /console/v1/recent`, so a shared device shows the next person nothing. The local copy
    (`lib/recent.ts`) is no longer written; Settings still clears what an older build left. Every
    link is built from the key with the router's own paths (a type key's service and type, a
    wfrn's parts), never taken from the data as a URL. The two home panels are components of their
    own (`pages/personal`), so the home's layout can place them anywhere.
76. **A favourite is a star; a favourite service is a pin.** A resource is starred on its page and in
    every list row (`PUT`/`DELETE /console/v1/favourites/{wfrn}` with its type and name, 100 an
    account, 409 `favourites.full` said in words); a type is pinned on its list into the
    preferences' `pinnedTypes` (type keys, 50 kept). Both are toggle buttons whose name stays the
    same (`aria-pressed` says the state), drawn in the console since Atlas has no star or pin glyph,
    and a star in a list row keeps its click and keys from the row. The star changes at once and goes
    back if the call fails. The mock keeps both in the directory, per person and account.
77. **The quota rules are read in the browser exactly as the identity service reads them.** `GET
    /console/v1/iam/quota-rules` lists them in order; the first rule covering a key (`*` any run of
    characters) approves an ask of at most `maxFactor` times the limit and never above `maxLimit`,
    unless a rule raised the key within its `cooldown`, which the console reads from the account's
    approved requests (`decidedBy` `rule:<name>`). So the request dialog says before sending whether
    the ask is approved at once or goes to the platform team, and the quota's row says which rule
    stands behind it and until when it is cooling down. The mock's directory applies the same rules
    (`lib/quotas.ts`, shared), with the identity service's defaults.
78. **The platform team decides through the BFF, never the requester.** The identity service keeps
    `:decide` to its internal principal; the BFF calls it for a member of the platform team (the
    organization admins; in the demo, Gonzalo) on `/console/v1/iam/quota-requests/{id}:decide`, and
    serves each request with `can` (`decide`, `withdraw`) and `refusals` holding the reason. The page
    shows Approve and Reject only where `decide` is offered, and otherwise the reason in words
    ("Nobody decides their own quota request"). Approving grants the ask or another limit with a
    note; rejecting asks why. Asking stays open to every member the identity service's policies
    allow, the managed viewer included, so a viewer reads the page, may ask, and decides nothing.
79. **A quota refusal leads to the request.** `/service-quotas?request=<quota key>` opens the request
    dialog, so a create whose preview the `quota.resources` rule denies links to it under the review,
    and the access keys page's Quotas card uses the same dialog and links to Service Quotas. The
    ask for more than the rules give at once is the one that waits, so the earlier journeys ask for
    150 access keys, past the access keys rule's 100.

## Layout of the code

```
console/
  e2e/            the Playwright journeys (shell, identity, list, keyboard, resources, changes,
                  tags, bulk, drift, monitoring, access, cli, notifications, policies, state,
                  personal), their helpers and screenshots; cli covers the device sign-in, CLI sign-ins, the
                  console shell and the developer portal; policies the policy editor, simulator,
                  boundaries and guardrails
  src/app/        the shell: App (the session gate), AppShell, Header, UserMenuButton,
                  ServiceSidebar, Breadcrumbs, Footer, Search (the palette), PlatformFeed,
                  NotificationsBell and notifications (the feed, the live stream, marking read),
                  Refusal (the refusal dialog, ActionButton and ActionMenuItem), MenuButton, Hint,
                  elevation (the refusal's offer of an hour, the held roles banner, the elevated
                  mark), accessTimeBell (reviews and elevations in the bell),
                  access (the pending requests query), commands (running a change and explaining
                  its failure), CodeDisclosure, GettingStarted, the router, the console context
  src/lib/        api (fetch to the BFF only, traceparent, X-Csrf-Token, ETag cache, paging, the
                  step-up door), bff (the BFF client, sign-in, sign-out, switching), kit (the
                  BFF's answers in the kit's shapes), events (the live event types), session, permissions, sse, queries,
                  descriptors, accounts, regions, recent, store, resourceList (the list's
                  filters, sorts, columns and counts), listState (the list in the query),
                  preferences (saved views and columns), keyed, csv, code (view as code), plain
                  (failures in words), tour, verbs, submission (Idempotency-Keys), poll, tags
                  (the tag policy, Tag Editor, resource group and clone client, and their rules),
                  rollout (a bulk run's waves), drift (the drift and unmanaged routes, and their
                  words), monitoring (metrics, alarms and rightsizing, and their words), chart
                  (scales, ticks, time labels and the keyboard's steps), accessReviews,
                  elevations and organization (access over time and the accounts, and their
                  words), cli (device grants and API sessions, and their words), shell/ (the
                  CLI's command core, its parser, the terminal's text and the console adapter),
                  portal (the API by tag, sample calls, tiles and shortcuts), notifications (the
                  notifications, settings, chat links and digests client, kinds and channels in
                  words, the defaults, and a notification's link read into a route), policies
                  (the policy, role, guardrail and simulator client, the glob rule, the action
                  catalog, validation and the JSON reader that keeps lines), state (the trail,
                  time travel, lineage and map reads, and their words), trail (the chain's
                  checks), sha256, timeTravel (replay and diff), graph (the map's layout and
                  keyboard), personal (recent visits, favourites and pinned types), quotas
                  (Service Quotas, the rules and their words)
  src/mock/       the mock server (the BFF), types (the BFF's type descriptors), live (the live
                  feed per account), the directory, the dataset, the rows
                  (resources) and the writes on a clock (commands), jobs (steering, approvals and
                  bulk runs in waves), the tag policy, Tag Editor, resource groups and clone drafts
                  (tags), drift (findings, decisions, discovery and unmanaged objects),
                  monitoring (series, alarms and the rightsizing pass), access (review campaigns,
                  elevations, break-glass and closing accounts), cli (device grants, API
                  sessions and the console API's outline), notifications (the notifier,
                  settings, chat links and digests), policies (custom policies, roles and their
                  boundaries, guardrails and the simulator, over the directory's `decide`), state
                  (the chained and sealed trail, time travel, lineage and the map) with es256
                  (the demo keys), quotas (the rules, the account's requests and the platform
                  team's decision), personal (recent visits and favourites), and the browser
                  installer, with the samples
  src/pages/      one file per page; src/pages/iam/ the identity and access pages (access
                  reviews, elevations and CLI sign-ins among them), src/pages/iam/policies/ the
                  Policies, policy editor, Policy simulator and Guardrails pages and the role
                  dialogs (create, attach, boundary), src/pages/organization/ the accounts page,
                  src/pages/list/ the list's saved views menu, column chooser and bulk drawer,
                  src/pages/create/ the wizard's steps, its Tags step and the clone's hand-over,
                  src/pages/resource/ the resource page's tabs (DriftTab and the decisions it
                  shares with the Drift page in DriftDecisions), request banner, action drawer,
                  edit drawer, blast radius and Tags drawer, src/pages/request/ the request
                  page's parts (the paused step, what went wrong, approvals, a bulk run's waves
                  and rows, retry with changes), src/pages/tags/ the Tag Editor, its apply
                  dialog, the tag rows and the Required tags page, src/pages/groups/ the
                  resource groups, src/pages/operations/ the Drift, Unmanaged resources, Alarms
                  and Rightsizing pages and the alarm dialog, src/pages/developer/ the device
                  sign-in, the console shell and the developer portal, src/pages/notify/ the
                  Notifications, Notification settings, Linked chats and Weekly digests pages,
                  src/pages/state/ the Activity trail, Inventory as of a date and Resource map;
                  the resource page's Monitoring tab and its MetricChart, TimeTravel, RevertEdit
                  and LineageView sit with its other tabs; src/pages/personal/ the home's
                  Recently visited and Favourites panels, the star and the pin, src/pages/quotas/
                  the Service Quotas page, its request and decision dialogs and the create's
                  quota note
packages/
  console-kit/    the composites and their tests
  ui/             Atlas, untouched
  wfrn/           the wfrn twin, SPEC.md and fixtures.json copied from the hub
scripts/          verify-contracts.mjs
```

## Phase 9 decisions

Beyond the AWS console: the executive home (WP 9.5), cost and budgets (WP 7.3, 9.3 and 9.9's
pages), the copilot (WP 9.1) and the failure explainer (WP 9.2). Numbered from 50, so the
decisions of phases 4 and 5 keep 33 to 49. The shapes are the hub's: decision 27 for the BFF's
own paths (insights, the copilot, the failure explanation), decision 25's cost service for cost,
budgets and chargeback, and the search indexer's 1.2.0 for plain-English search and grouped
counts.

What every phase 9 page stands on: the routes and the Cost and budgets sidebar; the copilot's
header button and its panel's state (`app/copilot/context.tsx`), which a page opens seeded, such
as a resource's Explain; forms opened filled from a link (`lib/prefill.ts`: `?prefill=` on a
create or an action carries base64url JSON, and the form takes only the schema's own fields and
never a sensitive one, so a secret never rides in a URL, and nothing is sent until the person
sends it); and the mock's cost ledger (`mock/cost.ts`), computed from the resources at the list
prices the create review quotes, charged while a resource is active, updating or deleting, so the
Cost Explorer, budgets and the executive home's spend agree to the cent. Accounts carry a cost
centre (`CC-4100` Compute, `CC-4200` Data); `cost:list` is every role's, budget writes are an
admin's and the chargeback export an admin's or an auditor's, as the cost service decides.

### The executive home (50 to 54)

50. **The numbers are counted from the rows the other pages show.** `mock/insights.ts` computes
    `GET /console/v1/insights` from the dataset every route serves, so each number agrees with a
    page: resources managed are the list's rows in the covered accounts in any state but deleted
    (`liveRowOf`), at the period's start those created before it plus each tombstone created
    before it and deleted after (its `updatedAt`), and by service the same rows by their service.
    Requests are the platform's jobs started in the period, a batch once and never its children,
    from every channel (the console's own, the catalog, REST, MCP, triggers), since each one
    stood in for a ticket; a job's account is its own, its wfrn's, else its workflow owner's, the
    rule the sample resources follow. Self-served is a request that ended `succeeded`, as the
    mock has no step retried, skipped or aborted by hand; failed is `failed` or `rolled-back`;
    one still running or waiting for approval counts in the total only. Adoption counts the same
    requests week by week, and a week's active people are its distinct requesters.
51. **Lead time and hours saved are measured against a ticket baseline per queue and kind.** Lead
    time is how long a self-served request took from being asked to done, its approval included
    (the job's `durationMs`, else `endedAt` less `startedAt`), as the nearest-rank median and
    90th percentile. `TICKET_BASELINE` stands in for the BFF's `console.insights.baseline`, a
    "ticket export, September 2026": the median ticket time by queue (the team that owns the
    workflow) and kind (the request's verb: create, delete, anything else a change),
    team-compute's creates 3 days, changes 1 day and deletes 2 days, team-platform's 2 days each,
    and no queue for the data team. `baselineP50Ms` is the median of those medians over the
    self-served requests the export covers, and `hoursSaved` adds up, over the same requests, the
    baseline less the lead time (never below 0 for one), in hours to a tenth. Without a covered
    request both are absent: the page shows the lead time alone with its 90th percentile, and
    hours saved says it needs a baseline and why (Raj Patel, in Data only, sees this).
52. **Drift fixed counts only findings that closed, so the mock's stays at 0.** Found is the
    findings opened in the period on the covered resources and open is every finding open now,
    both from `findingsOf`, the findings the resource pages show (opened by the first hourly scan
    after a create, at most a day ago). Fixed would count the findings closed in the period by a
    revert, an accept or the resource changing back, with the mean time to fix over those; the
    mock serves no revert, accept or rescan, so nothing closes and the tile says so ("0 of 19",
    "none fixed yet") rather than guess. Deleting a drifted resource takes its finding off the
    open count but is not a fix, since nothing was brought back in line. Where more are fixed
    than found (fixes of findings found before the period), the tile reads "3 fixed" with the
    found count beside it, since "3 of 2" would not read.
53. **Periods are whole UTC days, half open, and IAM decides account by account.** A period of 7,
    30 or 90 days ends at the next midnight UTC, which it excludes, and starts that many days
    before, so today is always in it and nothing after `asOf` counts; adoption's weeks start on
    Monday (UTC), and the first and last count only their days inside the period. The BFF checks
    `insights:get` on each account (viewers and up have it through `*:get`; `insights` is a
    console service, so the platform's role never decides it): `scope=account` needs it in the
    session's account and is refused with a 403 otherwise, and `scope=organization` covers each
    membership that allows it, the page naming the ones left out, and is refused only when none
    does. A `period` or `scope` outside the contract's values is a 400 naming the field, and any
    method but GET a 405. Grouped counts (`/search:counts`) are not served: the breakdown by
    service comes with the numbers, and the page asks for nothing it does not show.
54. **The view lives in the link, and the last numbers stay until the next arrive.** The period
    and the scope are the page's query (`#/insights?period=30d&scope=organization`), so a link
    shares the view, and each Segmented is named for what it shows ("Period: 30 days", "Scope:
    All my accounts"). Spend by team is the cost service's report by cost centre over the
    numbers' own days, on the path the hub fixed
    (`/console/v1/cost?scope=organization&groupBy=costCentre`, which needs `cost:list`), asked
    with `scope=account` when the page is narrowed so that spend and numbers cover the same
    accounts; each centre's accounts and owning teams come from the report's accounts, and under
    it go the total, the month's forecast, the resources with no list price and the accounts
    skipped, every amount an estimate from list prices. While a new view loads, the last numbers
    stay, dimmed and `aria-busy`, and a live region says what is shown; the skeleton is for a
    first load only, a failure offers a retry, a refusal its See why, and a refused or failed
    spend changes only its card and tile. Every chart keeps its numbers as a table under a
    disclosure (console-kit's `ChartFigure`), and active people get a chart of their own, since
    they count people, not requests. The home's card asks the same question as
    `#/insights?period=30d&scope=account`, so the page opens from the cache, and on a 403 the
    card is not drawn at all.

### Cost and budgets (55 to 59)

55. **The Cost Explorer is a link.** Everything it shows lives in its URL: the range (a preset, or
    custom days), the granularity, the grouping, the service, type and tag filters, the scope, one
    resource's wfrn and chart or table (`#/cost?range=3m&groupBy=tag:environment`), so a view is
    shared by pasting it and the CSV is the table on screen. Ranges are UTC days, as the cost
    service closes them: month to date and the last 30 days go by day, three and twelve months by
    month, and a custom range by day up to 62 days and by month beyond, until the person picks
    otherwise. A grouping draws its seven largest by name and folds the rest into Other, as the
    cost service's `top` does, and every figure says it is an estimate from list prices, never an
    invoice.
56. **What no price covers counts as nothing, and says so.** The ledger charges only what a price
    list prices, and only while a resource is active, updating or deleting; the explorer counts the
    rest as Not priced and names their types, and a resource's page says "Not priced" with the
    reason behind its info button. A group's points are the steps of its rounded running total, so
    the days add up to the total to the cent, and the explorer, budgets, the chargeback and the
    executive home read one ledger (`mock/cost.ts`) and never disagree. The forecast is the month
    so far plus the run rate for the days left, its likely range taken from the last 14 closed
    days.
57. **Budgets read the same ledger and block in the create review.** A budget's spent and forecast
    are the explorer's numbers for its scope, the account or one tag's value. A budget that blocks
    creates and is over adds a `deny` rule `budget.exceeded` to a create's preview, naming itself
    and carrying its `budgetId`, and one the new resource would push over adds a `warn`; a create
    sent anyway answers 409 `budget.exceeded` with the budgets in `detail.budgets`. Creating is an
    admin's (`adminOnly`, as the BFF gives no `can.create`), while editing and deleting follow each
    budget's `can`; a change carries `If-Match` (400 without it), and a 409 `version.conflict` says
    someone changed it first and loads their version into the drawer. Alerts arrive as
    `budget.alert` events and refresh the budgets.
58. **A closed month's chargeback is a file you can check.** The chargeback needs `cost:export` and
    shows a month by cost centre, account, service and, on request, a tag's value, with the
    subtotals finance reads. A month is final once it has closed: published once with the SHA-256
    of its CSV and never restated, so a rate changed later never rewrites what finance received;
    the current month is provisional and grows. Download saves the cost service's CSV byte for byte
    (UTF-8, CRLF, no byte order mark) and the browser hashes it with `crypto.subtle` before saying
    it matches; an organization export names the accounts it left out (`skippedAccounts`,
    `X-Console-Skipped-Accounts`).
59. **A group keeps its colour, from one validated palette.** The series take an eight-slot
    categorical palette in a fixed order (`packages/console-kit/src/palette.ts`), checked for
    colour-blind separation of neighbouring slots and for contrast against both surfaces, with dark
    steps of its own; three light slots fall under 3:1 on white, one more reason a chart of two
    series or more names them in a legend and every chart has a table view. The folded rest is
    Other in the tertiary ink, and a filter that drops a group never repaints the others.
    `StackedBarChart` is one keyboard stop whose arrows, Home and End step a column at a time, each
    saying its values. Where cost is a side note (a resource's "Monthly cost", the home's widget) a
    refusal leaves it out rather than showing a lock; the explorer, budgets and chargeback show the
    refusal with its "See why".

### The copilot (60 to 64)

60. **The copilot sits beside the page and never acts.** Its panel is a non-modal complementary
    region beside the page, and the page makes room for it (half the screen at most), so the page
    stays in reach and nothing focused there hides underneath; narrower than 768 px it covers the
    screen, keeps Tab, and closes once something it proposes opens. It answers in words and
    proposals, each a card the person opens: a filter opens the list with the list's own `?f=`
    tokens, a create opens the wizard and an action the resource's drawer, both filled through
    `?prefill=`, and nothing runs from the panel. Opening it puts the focus in its box, Escape gives
    the focus back to what opened it (the header's button when that is gone), and a polite status
    line says when it thinks, answers or fails.
61. **A reading the copilot is not sure of waits for the person.** The person's words also go to
    `search:interpret`, and a filter proposal whose reading came back `confidence: low` says it is
    not sure, shows the filter chip by chip as the list will show it, names the words left out, and
    applies only when its button is pressed. No filter applies itself at any confidence: a sure
    reading is one click too, so the list changes only because the person chose it. What the list
    cannot hold (a tag with any value, another region) is named on the card rather than dropped in
    silence.
62. **One conversation per person and account, read only while the panel is open.** It lives outside
    React (`app/copilot/thread.ts`), so it survives closing the panel and changing page; New
    conversation forgets it. An answer is read at once, then after 1 s, backing off by half a second
    to every 3 s, and the reading stops when the panel closes and carries on when it opens. A 429
    says when to ask again and enables Try again then; a failed turn and an unreachable copilot
    offer Try again, which sends the same question; a refusal does not; a conversation the copilot
    no longer has (24 hours) starts a new one.
63. **The mock reads plain English as the search indexer does, and counts with the list.**
    `mock/assistant.ts` is deterministic: types by name, plural and title, a typo forgiven; states
    and their everyday words (`stopped` is `deleted`, and the answer says so); drift, times, sorts,
    the account's own tags and the query syntax. A union, a negation or a second state is left out
    rather than guessed, and so is `tag:<name>` with any value, which the list cannot hold. The
    mock's list and its counts keep `created:` and `updated:` terms themselves, so the copilot's "10
    match" is the count the list then shows, and a test holds every reading to it.
64. **The mock's copilot is as honest as the real one has to be.** It reads with the person's own
    access: a list, a create, an action or an explanation their roles refuse is answered with what
    refused it, the cost section needs `cost:get`, dependents are the ones they can read, and a
    sensitive field is never shown. A turn thinks for 1.2 s on the server's clock; "fail the
    copilot" fails a conversation's first turn so that Try again can be seen; ten questions a minute
    per person, then 429 with `Retry-After`; a conversation is the person's own and ends after 24
    hours (404).

### The failure explainer (65 to 69)

65. **A failed request explains itself where it failed.** The request page's "What went wrong"
    card (`console/src/pages/explain/FailureExplainer.tsx`, which phase 4's `FailureCard` hosts for
    a failed or paused step; a rejection, a bulk run and a rollback keep that card's own words) reads
    `GET /console/v1/jobs/{jobId}/explanation` for a failed job and one waiting on a failed step:
    the cause in plain words, the step that stopped and what it said, a suggested fix, and how
    often the same failure happened in the account in the last 30 days, with the rerun that last
    fixed one. The resource page's banner names the cause in a line and links "See the fix", so
    nobody reads an error class to learn what to do. Without an explanation (the BFF down, an old
    job) the card shows the platform's own words, as before.
66. **Rules first, the copilot for the rest, and the card says which.** The BFF's rules read the
    platform's error classes (`not_found`, `conflict`, `rate_limited`, `unavailable`, `timeout`,
    `unreachable`, `server_error`, `unauthorized`, and the mock's `provider`), reading the failed
    step as the request page draws it (a paused step carries its class too); what they cannot
    read, such as a provider's own validation error (`client_error`), the copilot answers.
    The card's corner says "Explained by the rules" or "Explained by the copilot", because a
    reader weighs a rule's answer and a model's differently. The mock (`mock/explain.ts`) follows
    the same rules over the same jobs the pages show.
67. **A fix shows exactly what the retry changes, and how sure it is.** A fix carries a merge over
    the job's input and the step to run from; the card lists each changed field and its new value,
    says the steps before `fromStep` are not repeated, and rates the fix high, medium or low in a
    dot (`DotTag`). The confirmation repeats that the retry is judged like the first request. A
    fix never proposes a create's name (the retry keeps the minted one) nor a resource that is not
    live in the account; when nothing live can stand in, the fix says so and links to the create.
68. **The fix travels as a merge, never as the whole input.** "Retry with fix" sends only
    `{ input, fromStep }` to the phase 4 retry, which merges it over the original on the server
    (the platform's `:rerun`), so the original input, secrets included, never comes back to the
    browser to be edited and resent. Asking for an explanation retries nothing. The button runs the
    request page's own retry (one key per pause; an ended request's rerun opens as the new request)
    and is an `ActionButton` on the explanation's own `retry` decision, which the mock judges as
    the page's retry does: a viewer sees it locked with the refusal behind it. The card offers it
    only for a fix that changes the input or the step; a fix that only runs the failed step again
    points to the page's Retry instead, so the page never shows the same retry twice.
    Only the rules' fixes change the input; a copilot's fix, written by a model that has read the
    provider's text, can at most run a step again, so a prompt slipped into a failure message
    cannot change a value the person might not notice. No fix changes where the request runs
    (`accountId`, `region`, `wfrn`); the BFF enforces the same and also refuses the subject and
    sensitive fields, limiting a fix to the failed step's own non-sensitive inputs.
69. **An explanation's links are followed only when safe.** A link is a console route (`#/...`) or
    an https page on a docs host (`VITE_CONSOLE_DOCS_HOSTS`, comma-separated, none by default),
    opened in a new tab; anything else (another host, `javascript:`, `data:`, plain http) is
    dropped (`lib/explain.ts`, `linkOf`), because the copilot and a provider's runbooks write
    them, not the console, and a phishing link planted in a failure message must not get through.
