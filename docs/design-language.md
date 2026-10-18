# Design language

Two rules.

1. **The canvas is n8n.** Node shapes, ports, edges, the node detail view (NDV), the node creator,
   keyboard shortcuts and interactions follow n8n's editor one to one, so anyone who has used n8n
   is at home. Layout and interaction patterns are copied; n8n's code, icons, logo, name and brand
   colours are not.
2. **Everything else is Atlas.** The shell (sidebar, header, catalog, jobs, approvals, providers,
   admin, the Add provider wizard) is built from the Atlas design system in `packages/ui`, so the
   platform looks like a sibling of Atlas rather than a different product.

## packages/ui (Atlas, verbatim)

- `index.css`: `@theme inline` over runtime variables. Surfaces `#ffffff / #f5f5f7 / #fbfbfd`,
  text `#1d1d1f / #6e6e73 / #86868b`, hairlines `#d2d2d7 / #e8e8ed`. One accent, Apple blue
  `#007aff` (`#0a84ff` dark) with a 10% tint. States: healthy `#34c759`, warning `#ff9500`,
  critical `#ff3b30`, never reused as series colours. Dark mode is `.dark` on `<html>`.
- Type: `-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', 'Helvetica Neue'`,
  `SF Mono` for identifiers. HIG scale utilities: `text-caption2` 11, `text-caption` 12,
  `text-footnote` 13, `text-subheadline` 15, `text-body` 17, `text-title3` 20, `text-title2` 22,
  `text-title1` 28, `text-large-title` 34.
- Radii 8 / 12 / 16 / 20 (`rounded-sm` … `rounded-xl`). Shadows `shadow-sm|md|lg|card`.
  One frosted surface `.material-panel`, one scrim `.material-scrim`.
- Motion: `--ease-out-quint` and `--ease-spring`; 150 / 250 / 400 ms; `animate-scale-in`
  (modals), `animate-slide-in-right` (drawers), exits before unmount; `animate-pulse-once` for
  insight moments; `prefers-reduced-motion` turns it all off.
- Components: `Button` (primary, secondary, ghost, capsule, warning-outline; `active:scale-[0.97]`),
  `IconButton`, `Modal`, `Drawer`, `PopoverPanel`, `useOverlayClose`, `Select`, `StatusDot`,
  `EmptyState`, `Skeleton`, `Icon` (inline SVG, line style), `Toast`, `Tabs`, `Segmented`,
  `Field`/`Input`/`Textarea`, `Table`, `Card`, `Tag`, `Kbd`, `CommandPalette`, `AppHeader`,
  `Sidebar`; hooks `useTheme`, `useDismiss`, `useHotkey`; `cx`.
- Conventions: no component, icon or motion library, with two exceptions: React Flow, confined to
  `frontend/src/designer`, and the Monaco code editor, confined to
  `frontend/src/components/code-editor`, whose themes take their colours from `atlasColors`. Colour through semantic utilities (`bg-surface`, `text-secondary`,
  `border-stroke-light`), never raw hex in components. Every list has an empty state. The server
  judges facts (health bands, thresholds, lint); the UI renders them.

## Where the two meet

The canvas is one bounded region inside the Atlas shell. The shell owns the chrome; the canvas
owns everything inside its frame and keeps n8n's geometry: 100 × 100 px node body with 16 px
radius (trigger nodes with a 36 px left radius), 8 px ports, bezier edges with the "+" handle at
the midpoint, a "+" add handle after the last node, the dotted grid, the NDV as a wide modal with
three panes (Input · Parameters/Settings · Output, each pane with Schema/Table/JSON views),
the node creator as a right-hand panel opened by "+" or Tab, sticky notes, minimap, zoom controls,
tidy up. n8n's palette is remapped onto Atlas tokens: node border `--color-border`, selected node
and ports the accent, canvas `--color-surface-secondary` with the Atlas dot grid, success
`--color-healthy`, problems `--color-critical` / `--color-warning`.

Keyboard: Tab opens the node creator, ⌘K the command palette, ⌘S saves, ⌘Enter test-runs,
Delete removes the selection, ⌘C/⌘V copies and pastes node JSON, ⌘A selects all, Esc closes.
