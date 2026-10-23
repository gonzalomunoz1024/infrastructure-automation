# Design language

Three sources, one rule each, all from Gonzalo:

1. **The platform's workflow designer is n8n.** Node shapes, ports, edges, the node detail view, the node creator, shortcuts and interactions copy n8n's editor one to one; n8n's code, icons, logo, name and colours are not copied (its palette is remapped onto Atlas tokens).
2. **Everything else is Atlas**, the Apple-inspired design system from Gonzalo's own service-dashboard app, vendored verbatim as `packages/ui` (platform at c9529d5, copied unchanged into console-web).
3. **The console's layout and interactions are the AWS Management Console**, drawn in Atlas visuals "with Apple-grade restraint: think Jony Ive, elegant, quiet, precise" (decision 9).

Full platform rules: `platform/main-docs-design-language.md`. Full console rules: the "Design" decisions in `services/README-console-web--phase-7.md` (decisions 1 to 80 there are console-web's own, not hub decisions).

## Atlas in one screen

- Surfaces `#ffffff / #f5f5f7 / #fbfbfd`; text `#1d1d1f / #6e6e73 / #86868b`; hairlines `#d2d2d7 / #e8e8ed`.
- One accent, Apple blue `#007aff` (`#0a84ff` dark) with a 10% tint.
- States healthy `#34c759`, warning `#ff9500`, critical `#ff3b30`, never reused as chart series colours.
- Dark mode is `.dark` on `<html>`.
- SF system font stack, SF Mono for identifiers (wfrns, ids); HIG type scale from `text-caption2` (11) to `text-large-title` (34).
- Radii 8/12/16/20, a few soft shadows, one frosted `.material-panel`, one scrim.
- Motion: quint and spring easing, 150/250/400 ms, scale-in modals, slide-in drawers, exits before unmount, all off under `prefers-reduced-motion`.
- Components: Button, IconButton, Modal, Drawer, PopoverPanel, Select, StatusDot, EmptyState, Skeleton, Icon (inline line SVG), Toast, Tabs, Segmented, Field/Input/Textarea, Table, Card, Tag, Kbd, CommandPalette, AppHeader, Sidebar; hooks useTheme, useDismiss, useHotkey.
- Conventions: colour only through semantic utilities (`bg-surface`, `text-secondary`), never a hex in a component; every list has an empty state; the server judges facts (health, thresholds), the UI only renders them.
- No component, icon or motion libraries. The platform's two exceptions are React Flow (designer only) and Monaco (code editor only).

## What "Apple / Jony Ive restraint" means in practice

Quiet chrome, one accent colour, lots of white space, hairlines rather than boxes, plain sentences rather than codes ("This VM is being restarted", not `RESTARTING`), one primary action per view, motion only to explain a change, nothing decorative. Errors say what happened, why, and what to do next: a refusal (403) shows which layer refused, the statement, and a "Request access" button. A junior engineer must never need to read code to understand a screen (the ultimate goal).

## The AWS console layout the console copies

- **Header:** services menu, global search (⌘K palette), account switcher, region switcher (`global` only, but real), notifications, help, user menu.
- **Service landing page** per resource type (each `ResourceType`).
- **List:** property filter bar with tokens, saved views, column preferences, cursor paging, virtualised rows, bulk select and Actions.
- **Detail:** summary header plus tabs (Details, Activity, Drift, Lineage, Tags, Cost, Permissions) and an Actions menu built from the type's workflow verbs.
- **Create wizard:** steps from the type's JSON schema and its `x-console` hints, review page with the preview (policy, cost, budget, blast radius).
- **Also:** destructive confirmations by typing the name; IAM, quotas, billing/cost explorer and the activity trail.
- **Dynamic:** nothing is hand-coded per type; a newly published type appears with no UI release.

## Console-specific rules (console-web README decisions)

- Atlas is vendored verbatim and never edited. The console only overrides tokens in `console/src/index.css`. Where Atlas falls short, the component is forked into `@iaf/console-kit`.
  - Example: `ConfirmDialog` ties its type-to-confirm label to the input.
- The platform's composites are forked into console-kit behind a provider: SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline, ProblemList.
- The kit adds VirtualTable, WfrnText, LifecycleBadge, StatusBadge, PropertyFilterBar, CursorPager, DotTag, CodeView.
- **The one dependency exception:** `@tanstack/react-virtual`, only inside console-kit's `VirtualTable`, for lists of thousands of rows. Gonzalo allowed it. It never enters `packages/ui`.
- **WCAG 2.2 AA is the floor**, checked by axe in every Playwright journey (zero serious or critical findings): skip link, full keyboard use, focus returns to the opener, visible focus, reduced motion, phone widths with no horizontal scroll, accessible names that contain the visible text (2.5.3), hoverable and dismissable hints (1.4.13).
- **Four Atlas tokens move for AA:**
  - Light secondary text becomes `#636366`.
  - Light tertiary text becomes `#6b6b70`.
  - Danger becomes `#d70015`.
  - Dark tertiary text becomes `#98989d`.
  - Accent and red fills under white text use `#0066cc` and `#d70015` in both themes.
  - Light values sit on `:root:not(.dark)` so they don't outrank Atlas's `.dark` block.
- **Status colour lives in the dot, not the text.** LifecycleBadge (resource states), StatusBadge (jobs and steps) and DotTag show a coloured dot next to a label in primary text, instead of Atlas's coloured-text `StateTag`, which misses AA on its tints.
- **Charts:** state colours never become series colours.
