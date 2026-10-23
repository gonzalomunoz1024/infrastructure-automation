# console-web

The customer console of the infrastructure automation platform: one place where the firm's
internal teams see the resources the platform manages for them, and from phase 3 change them.
It is laid out like the AWS console and drawn with Atlas, the platform's design system.

This branch is shaped like the repository it will become: an npm workspace at the root, its own
CI, no platform files. The hub branch `console-root` holds the plan, the decisions and the
contracts this build pins.

## What is here (phase 0)

The shell and the read-only pages:

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
| `@iaf/console-kit`     | `packages/console-kit` | The console's composites: forks of the platform's SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline and ProblemList behind a provider, plus VirtualTable, WfrnText, LifecycleBadge, PropertyFilterBar and CursorPager |
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

Both serve `http://localhost:3000`. The sign-in page offers the development identities the
platform-fake knows (`gonzalo`, `mateo`, `ana`); the one chosen travels as the `X-User` header,
as the platform's own frontend does until OIDC arrives in phase 1.

## Checks

```sh
npm run lint              # eslint over every workspace
npm run typecheck         # tsc -b in every workspace
npm test                  # vitest in every workspace
npm run build             # vite build
npm run e2e               # the shell journey in Playwright, in mock mode
npm run contracts:verify  # the copied contracts still match contracts.lock
```

Playwright uses the Chromium under `PLAYWRIGHT_BROWSERS_PATH` (the development container ships
one at `/opt/pw-browsers`, which the config defaults to); only CI installs a browser. The journey
writes the screenshots in `console/e2e/screenshots`, which are committed.

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
the sha256 of each copy (`packages/wfrn/SPEC.md`, `packages/wfrn/fixtures.json`).
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
5. **Two Atlas tokens move for WCAG AA.** Light tertiary text becomes `#6b6b70` (5.3:1 on white)
   and the light accent `#0066cc` (5.6:1, and white on it 5.6:1); dark tertiary text becomes
   `#98989d`. The dark accent stays Atlas's `#0a84ff`, on which white text reads at 3.65:1: a
   note for P-14, since the console only overrides and never edits Atlas.
6. **Status colour is in the dot.** `LifecycleBadge` and `JobStatusBadge` show a coloured dot
   next to a label in the primary text colour, instead of Atlas's `StateTag`, whose coloured text
   does not reach AA on tinted backgrounds.
7. **wfrns are derived until the platform mints them.** A resource that carries a `wfrn` (P-05)
   is shown as named; one that does not gets a wfrn derived from the partition, region and
   account in the header plus its id, marked as derived (dimmed in lists, tagged on its page).
8. **Accounts and identities are placeholders.** Two demo accounts stand in until the platform
   serves accounts (phase 2); the dev identities are the platform-fake's, sent as `X-User`. The
   firm's single sign-on button exists and says that OIDC arrives in phase 1.
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
12. **Writes wait for phase 3.** The create, change and delete buttons are drawn, disabled for
    assistive technology, and say which phase brings them, so nobody wonders whether they are
    broken.

## Layout of the code

```
console/
  e2e/            the Playwright journey and its screenshots
  src/app/        the shell: App, AppShell, Header, ServiceSidebar, Breadcrumbs, Footer,
                  Search, PlatformFeed, the router, the console context
  src/lib/        api (fetch, traceparent, X-User, ETag cache, paging), sse, queries,
                  descriptors, identity, accounts, regions, recent, store
  src/mock/       the mock server, its dataset and the browser installer, with the samples
  src/pages/      one file per page
packages/
  console-kit/    the composites and their tests
  ui/             Atlas, untouched
  wfrn/           the wfrn twin, SPEC.md and fixtures.json copied from the hub
scripts/          verify-contracts.mjs
```
