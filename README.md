# console-web

The customer console of the infrastructure automation platform: one place where the firm's
internal teams see the resources the platform manages for them, and from phase 3 change them.
It is laid out like the AWS console and drawn with Atlas, the platform's design system.

This branch is shaped like the repository it will become: an npm workspace at the root, its own
CI, no platform files. The hub branch `console-root` holds the plan, the decisions and the
contracts this build pins.

## What is here (phase 0 and phase 1)

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
  pin, the platform's health and the theme toggle
- the console home, a landing page per service, a list per resource type, a resource page with
  its tabs, the requests list and one request, identity and access, settings, sign-in
- a development-only platform feed over server-sent events
- light and dark themes, visible focus, WCAG AA text, reduced motion honoured, phone widths

Nothing writes to the platform yet: every button that does so is shown and says which phase
brings it.

## Workspaces

| Package                | Path                   | What it is                                                                                                                                      |
| ---------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `@iaf/console`         | `console/`             | The web app: React 19, Vite 8, TypeScript 5.9, Tailwind 4, Vitest, Playwright                                                                   |
| `@iaf/console-kit`     | `packages/console-kit` | The console's composites: forks of the platform's SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline and ProblemList behind a provider, plus VirtualTable, WfrnText, LifecycleBadge, StatusBadge, PropertyFilterBar and CursorPager |
| `@iaf/ui`              | `packages/ui`          | Atlas, the platform's design system, copied verbatim from the platform at `c9529d5`                                                             |
| `@iaf/wfrn`            | `packages/wfrn`        | The TypeScript twin of the wfrn resource name, judged by the hub's fixtures                                                                      |

## Running it

Node 22 and npm 10.

```sh
npm ci
```

Against the platform (the `platform-fake-app` of console-libs listens on 18090; the Vite dev
server proxies `/api` and `/actuator` to `CONSOLE_PLATFORM`, which defaults to that address):

```sh
CONSOLE_PLATFORM=http://localhost:18090 npm run dev
```

With no platform at all, in mock mode: the same endpoints are answered in the browser from the
samples captured from the platform-fake (`console/src/mock/samples`), with the planned hints
added to the vm type so both the hinted and the default paths show:

```sh
npm run dev:mock
```

Both serve `http://localhost:3000`.

The session, the sign-in and identity and access come from the BFF (the `console-bff` service,
listening on 18081; its `demo` profile embeds the platform-fake and a demo identity provider).
The dev server proxies `/auth`, `/console` and `/demo-idp` to `CONSOLE_BFF`, which defaults to
`http://localhost:18081`:

```sh
CONSOLE_BFF=http://localhost:18081 CONSOLE_PLATFORM=http://localhost:18090 npm run dev
```

Live, the sign-in page has one button, "Continue with company SSO", which sends the browser to
`/auth/login?returnTo=/#/...`; the BFF runs the OIDC code flow and comes back with the cookie.
In mock mode the sign-in page stands in for the identity provider and lists the people the
identity service's demo seed knows; the mock keeps the "cookie" in this browser's localStorage.
The session user travels to the platform-fake as `X-User`, which is how it names the caller
until the BFF fronts the platform's reads in phase 3. The mock's user ids are the short names;
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
npm run e2e               # the shell and the identity journeys in Playwright, in mock mode
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
   button, which phase 1 makes pressable to open a refusal); dark tertiary text becomes `#98989d`. The
   dark accent stays Atlas's `#0a84ff`, on which white text reads at 3.65:1, and so does the dark
   critical red: notes for P-14, since the console only overrides and never edits Atlas.
6. **Status colour is in the dot.** `LifecycleBadge` (resource states) and `StatusBadge` (jobs
   and steps), both in console-kit, show a coloured dot next to a label in the primary text
   colour, instead of Atlas's `StateTag`, whose coloured text does not reach AA on its tints; the
   kit's `StepTimeline` uses `StatusBadge` for the same reason. A note for P-14.
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
    `access-request:decide`), who see it in the bell and on the access requests page and decide
    it there, never their own; an approval puts the requester in the managed group of the role
    (else the first group holding it), and their next session or a refresh of their roles picks
    it up. A second ask for the same role answers 409 with the pending request as the body, and
    the dialog shows that one. A request pending for more than 30 days is served `expired`.
    Reading the account's requests takes `access-request:list`; the identity service answers
    anyone else with their own only, so the console knows the queue is readable from the roles
    that read it (account-admin, auditor) or from an answer showing more than the person's own.
    Quotas are asked of the platform team the same way: the access keys page lists the
    account's quotas (`jobs-per-minute` from the platform's bucket, `access-keys`, and
    `resources.<service>` per service) and lets a member ask for a higher limit
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
## Layout of the code

```
console/
  e2e/            the Playwright journey and its screenshots
  src/app/        the shell: App (the session gate), AppShell, Header, UserMenuButton,
                  ServiceSidebar, Breadcrumbs, Footer, Search, PlatformFeed, Refusal (the
                  refusal dialog and ActionButton), access (the pending requests query), the
                  router, the console context
  src/lib/        api (fetch, traceparent, X-User, X-Csrf-Token, ETag cache, paging), bff (the
                  BFF client, sign-in, sign-out, switching), session, permissions, sse, queries,
                  descriptors, accounts, regions, recent, store
  src/mock/       the mock server (platform and BFF), the directory, the dataset and the browser
                  installer, with the samples
  src/pages/      one file per page; src/pages/iam/ the identity and access pages
packages/
  console-kit/    the composites and their tests
  ui/             Atlas, untouched
  wfrn/           the wfrn twin, SPEC.md and fixtures.json copied from the hub
scripts/          verify-contracts.mjs
```
