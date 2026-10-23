# console-web

The customer console of the infrastructure automation platform: one place where the firm's
internal teams see the resources the platform manages for them, and from phase 3 change them.
It is laid out like the AWS console and drawn with Atlas, the platform's design system.

This branch is shaped like the repository it will become: an npm workspace at the root, its own
CI, no platform files. The hub branch `console-root` holds the plan, the decisions and the
contracts this build pins.

## What is here (phases 0, 1 and 3a)

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
  notifications bell carrying the access requests an admin has to decide
- identity and access: users, groups, the four managed roles with their policies statement by
  statement, access keys (the secret shown once, rotation, revocation, quotas) and access requests
- a refusal view on every denied action, naming the layer that decided (the account's console
  IAM policies, or the platform's own role for the account), the statement, and your role, with
  Request access sending an approval to the account's admins
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
BFF's demo profile knows; the mock keeps the "cookie" in this browser's localStorage. The session
user travels to the platform-fake as `X-User`, which is how it names the caller until the BFF
fronts the platform's reads in phase 3.

| Person  | Compute (123456789012)                          | Data (210987654321)             |
| ------- | ----------------------------------------------- | ------------------------------- |
| gonzalo | admin (groups team-compute, team-platform, admin) | admin (group admin)             |
| dana    | operator (team-compute, developer)              |                                 |
| mateo   | operator (team-compute, ops)                    |                                 |
| mia     | viewer (team-platform, compute-approvers, platform-approvers) |                   |
| raj     |                                                 | operator (team-data, data-approvers) |
| ana     |                                                 | operator (team-data, developer, dba) |

The managed roles: `viewer` reads; `operator` reads and makes every change that is not
destructive; `admin` does everything, including deletions and identity and access; `auditor`
reads, the audit trail included. `Data`'s platform service account holds only `compute:read`,
so creating a virtual machine there is refused by the platform layer, not by console IAM.

The gate-1 story, in mock mode: sign in as Mia (viewer), open Virtual machines, press the locked
Create: the refusal names console IAM and that no statement of `viewer` allows `compute:create`,
and offers Request access. Send it; sign in as Gonzalo: the bell shows one request to decide;
approve it. Sign in as Mia again: Mia now holds `operator` and Create is no longer locked. Sign in
as Dana and open a machine: Delete is refused by statement `DenyDestructive` of policy
`managed-operator`. Sign in as Raj in Data: Create is refused by the platform, scope
`compute:write` missing on `svc-data`.

## Checks

```sh
npm run lint              # eslint over every workspace
npm run typecheck         # tsc -b in every workspace
npm test                  # vitest in every workspace
npm run build             # vite build
npm run e2e               # the shell, identity, list and keyboard journeys in Playwright, in mock mode
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
   button, which phase 1 makes pressable to open a refusal); dark tertiary text becomes `#98989d`.
   The light values are set on `:root:not(.dark)`: on a plain `:root` they outranked Atlas's
   `.dark` block (same specificity, later in the cascade) and the dark appearance drew `#636366`
   on black. In the dark, Atlas's accent `#0a84ff` and red `#ff453a` read well as text but carry
   white text at 3.65:1 and 3.4:1, and no one colour does both; so the fills under white text
   (`.bg-accent.text-white`, `.bg-critical.text-white`: primary and danger buttons, the accent and
   critical badges, a done step, the sidebar's count) take `#0066cc` and `#d70015` in the dark too
   (5.6:1 and 5.4:1, 4.8:1 and 4.6:1 under the buttons' hover brightness). Notes for P-14, since
   the console only overrides and never edits Atlas: an on-accent fill token would replace them.
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
    with explicit deny first, then any allow, else deny; the platform layer checks the account's
    service-account scopes after console IAM allowed.
16. **Identity and access writes are greyed for everyone but admins before any call.**
    `adminOnly()` is the console's own rule for phase 1 (the managed roles are fixed, only
    `admin` writes IAM); it greys the button with a requestable denial that suggests `admin`.
    The server's answer, with the statement that decided, replaces it the moment a call is
    refused. Every IAM write carries an `Idempotency-Key` and, where the identity service
    versions the resource, `If-Match`.
17. **Access requests are the approval path.** Request access in a refusal posts to
    `/console/v1/access-requests` (reason of ten characters or more, the role suggested by the
    refusal); the account's admins see it in the bell and on the access requests page and decide
    it there; an approval puts the requester in the group that grants the role, and their next
    session or a refresh of their roles picks it up. A duplicate pending request answers 409 and
    the dialog links to the open one.
18. **The mock BFF is the same pure handler, with a session store.** `console/src/mock/server.ts`
    speaks the BFF's contract next to the platform's: `/auth/login` with `login_hint` (the demo
    identity provider), `/auth/logout`, the session, accounts, regions, the types with decisions,
    identity and access, and access requests, over an in-memory directory
    (`console/src/mock/directory.ts`) that mirrors the BFF's demo people, the managed policies
    and the two accounts' platform scopes. The browser installer keeps the session in
    localStorage, so a reload stays signed in and a sign-out clears it.
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
    a type, 50 filters a view); a write that meets another tab's newer version (409) reads it,
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

## Layout of the code

```
console/
  e2e/            the Playwright journeys (shell, identity, list, keyboard), their helpers and
                  screenshots
  src/app/        the shell: App (the session gate), AppShell, Header, UserMenuButton,
                  ServiceSidebar, Breadcrumbs, Footer, Search (the palette), PlatformFeed,
                  Refusal (the refusal dialog and ActionButton), MenuButton, Hint, access (the
                  pending requests query), the router, the console context
  src/lib/        api (fetch, traceparent, X-User, X-Csrf-Token, ETag cache, paging), bff (the
                  BFF client, sign-in, sign-out, switching), session, permissions, sse, queries,
                  descriptors, accounts, regions, recent, store, resourceList (the list's
                  filters, sorts, columns and counts), listState (the list in the query),
                  preferences (saved views and columns), keyed, csv
  src/mock/       the mock server (platform and BFF), the directory, the dataset and the browser
                  installer, with the samples
  src/pages/      one file per page; src/pages/iam/ the identity and access pages,
                  src/pages/list/ the list's saved views menu and column chooser
packages/
  console-kit/    the composites and their tests
  ui/             Atlas, untouched
  wfrn/           the wfrn twin, SPEC.md and fixtures.json copied from the hub
scripts/          verify-contracts.mjs
```
