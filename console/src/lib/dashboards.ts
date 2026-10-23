import { get, qs, request, type ListParams } from './api'
import { idempotencyKey } from './bff'
import type { Decision, Denial } from './permissions'

/*
 * Team dashboards through the BFF (WP 9.19). The hub has no contract for them yet, so these
 * routes are the console's proposal, in the BFF's conventions: the session's account, a `can`
 * on every dashboard, 403 refusals with the Denial, a version as the ETag that every change
 * sends back as If-Match, and an Idempotency-Key on the create.
 *
 *   GET    /console/v1/dashboards?scope=all|mine|shared
 *   POST   /console/v1/dashboards                  (Idempotency-Key)
 *   GET    /console/v1/dashboards/{dashboardId}
 *   PUT    /console/v1/dashboards/{dashboardId}    (If-Match)
 *   DELETE /console/v1/dashboards/{dashboardId}    (If-Match)
 *   PUT    /console/v1/dashboards/{dashboardId}/sharing (If-Match)
 *
 * A widget reads its numbers from routes the console already has: a resource's metrics, a
 * type's counts, the findings summary, the requests and the alarms. The layout rules (the
 * width and height steps, moving one place at a time) live here, so the page and the tests agree.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/* ----------------------------------------------------------------------------
   The shapes
   ------------------------------------------------------------------------- */

/** How wide a widget is, in twelfths of the board: a quarter, a third, a half, two thirds or all of it. */
export type WidgetWidth = 3 | 4 | 6 | 8 | 12
/** How tall a widget is, in rows of the board. */
export type WidgetHeight = 1 | 2 | 3 | 4

export const WIDTHS: readonly WidgetWidth[] = [3, 4, 6, 8, 12]
export const HEIGHTS: readonly WidgetHeight[] = [1, 2, 3, 4]
/** The most widgets one dashboard holds. */
export const MAX_WIDGETS = 30
export const MAX_NAME = 100
export const MAX_TITLE = 80

export type WidgetKind = 'metric' | 'count' | 'drift' | 'requests' | 'alarms'

interface WidgetBase {
  widgetId: string
  title: string
  width: WidgetWidth
  height: WidgetHeight
}

/** One series of one resource, from `GET /console/v1/r/{type}/{wfrn}/metrics`, over the board's time range. */
export interface MetricWidget extends WidgetBase {
  kind: 'metric'
  type: string
  wfrn: string
  /** the series, such as `cpuPercent` */
  metric: string
}

/** A type's resources counted by lifecycle state, from `GET /console/v1/r/{type}:counts`, optionally only those with a tag. */
export interface CountWidget extends WidgetBase {
  kind: 'count'
  type: string
  tag?: { key: string; value: string }
}

/** The account's open drift findings, from `GET /console/v1/findings/summary`. */
export interface DriftWidget extends WidgetBase {
  kind: 'drift'
}

/** The requests still open, from `GET /console/v1/jobs`. */
export interface RequestsWidget extends WidgetBase {
  kind: 'requests'
}

/** The account's alarms by state, from `GET /console/v1/alarms`. */
export interface AlarmsWidget extends WidgetBase {
  kind: 'alarms'
}

export type Widget = MetricWidget | CountWidget | DriftWidget | RequestsWidget | AlarmsWidget

/** Who else sees a dashboard: everyone in the account, and groups of it, each to view or to edit. */
export type ShareAccess = 'view' | 'edit'

export interface GroupShare {
  /** the group's name in the account */
  group: string
  /** how the BFF names it for people: the team for a group the identity provider fills */
  title?: string
  access: ShareAccess
}

export interface Sharing {
  account: 'none' | ShareAccess
  groups: GroupShare[]
}

/** What a person may do: allowed, refused by policy (the Denial), or not offered to them (why, in words). */
export interface Permit extends Decision {
  why?: string
}

export interface PersonRef {
  userId: string
  name: string
}

export interface Dashboard {
  dashboardId: string
  accountId: string
  name: string
  description?: string
  owner: PersonRef
  sharing: Sharing
  widgets: Widget[]
  version: number
  createdAt: string
  updatedAt: string
  updatedBy?: PersonRef
  /** how this person reaches it: their own, or shared with them to edit or to view */
  access: 'owner' | ShareAccess
  can: { edit: Permit; share: Permit; delete: Permit }
}

export interface DashboardList {
  items: Dashboard[]
  next: string | null
  /** whether this person may create one */
  can: { create: Permit }
}

export type DashboardScope = 'all' | 'mine' | 'shared'

export interface DashboardInput {
  name: string
  description?: string
  widgets: Widget[]
}

/* ----------------------------------------------------------------------------
   The client
   ------------------------------------------------------------------------- */

const ifMatch = (version: number) => ({ 'If-Match': `"${version}"` })

export const dashboardsApi = {
  list: (params?: { scope?: DashboardScope } & ListParams) => get<DashboardList>(`${b('/dashboards')}${qs(params)}`),
  get: (id: string) => get<Dashboard>(b(`/dashboards/${enc(id)}`)),
  /** the key is the form submission's, so a retry of the same body answers the first dashboard */
  create: (body: DashboardInput, key?: string) => request<Dashboard>('POST', b('/dashboards'), body, { headers: { 'Idempotency-Key': key ?? idempotencyKey() } }),
  /** replaces the name, description and widgets of the version read; 409 when someone saved first */
  update: (id: string, body: DashboardInput, version: number) => request<Dashboard>('PUT', b(`/dashboards/${enc(id)}`), body, { headers: ifMatch(version) }),
  remove: (id: string, version: number) => request<void>('DELETE', b(`/dashboards/${enc(id)}`), undefined, { headers: ifMatch(version) }),
  share: (id: string, body: Sharing, version: number) => request<Dashboard>('PUT', b(`/dashboards/${enc(id)}/sharing`), body, { headers: ifMatch(version) }),
}

/** The query key prefixes a change to dashboards touches. */
export const DASHBOARD_QUERIES = ['dashboards', 'dashboard']

/* ----------------------------------------------------------------------------
   The layout: moving and resizing a step at a time
   ------------------------------------------------------------------------- */

/** The widgets with the one at `from` moved to `to` (clamped to the board). */
export function moveWidget(widgets: readonly Widget[], from: number, to: number): Widget[] {
  const out = [...widgets]
  if (from < 0 || from >= out.length) return out
  const target = Math.max(0, Math.min(out.length - 1, to))
  const [w] = out.splice(from, 1)
  out.splice(target, 0, w)
  return out
}

/** Where a key sends the widget at `index`: arrows a place earlier or later, Home first, End last; null for any other key. */
export function moveTarget(key: string, index: number, count: number): number | null {
  if (key === 'ArrowLeft' || key === 'ArrowUp') return Math.max(0, index - 1)
  if (key === 'ArrowRight' || key === 'ArrowDown') return Math.min(count - 1, index + 1)
  if (key === 'Home') return 0
  if (key === 'End') return count - 1
  return null
}

/** The next width or height step in a direction, staying at the end of the steps. */
export function stepSize<T extends number>(steps: readonly T[], current: T, direction: 1 | -1): T {
  const i = steps.indexOf(current)
  const at = i < 0 ? 0 : i
  return steps[Math.max(0, Math.min(steps.length - 1, at + direction))]
}

export function resizeWidget(widgets: readonly Widget[], index: number, change: { width?: 1 | -1; height?: 1 | -1 }): Widget[] {
  return widgets.map((w, i) => (i !== index ? w : { ...w, width: change.width ? stepSize(WIDTHS, w.width, change.width) : w.width, height: change.height ? stepSize(HEIGHTS, w.height, change.height) : w.height }))
}

const WIDTH_WORDS: Record<WidgetWidth, string> = { 3: 'a quarter of the width', 4: 'a third of the width', 6: 'half the width', 8: 'two thirds of the width', 12: 'the full width' }

export function widthWords(w: WidgetWidth): string {
  return WIDTH_WORDS[w] ?? `${w} twelfths of the width`
}

export function heightWords(h: WidgetHeight): string {
  return h === 1 ? '1 row' : `${h} rows`
}

/** What a widget's size is, in words: "half the width, 2 rows tall". */
export function sizeWords(w: Pick<Widget, 'width' | 'height'>): string {
  return `${widthWords(w.width)}, ${heightWords(w.height)} tall`
}

/** The size a new widget of a kind starts at. */
export function defaultSize(kind: WidgetKind): { width: WidgetWidth; height: WidgetHeight } {
  return kind === 'metric' ? { width: 6, height: 2 } : { width: 4, height: 2 }
}

/** A fresh widget id the BFF accepts (`^[A-Za-z0-9_-]{1,64}$`). */
export function newWidgetId(): string {
  return `w-${idempotencyKey().replace(/[^A-Za-z0-9]/g, '').slice(0, 20)}`
}

/* ----------------------------------------------------------------------------
   Words
   ------------------------------------------------------------------------- */

export const KIND_WORDS: Record<WidgetKind, { label: string; hint: string }> = {
  metric: { label: 'Metric chart', hint: 'One series of one resource, such as CPU used, over the board’s time range' },
  count: { label: 'Resource count', hint: 'A type’s resources by state, all of them or those with a tag' },
  drift: { label: 'Drift findings', hint: 'The open findings of the account, by severity' },
  requests: { label: 'Open requests', hint: 'The requests still running, waiting for approval or paused' },
  alarms: { label: 'Alarms', hint: 'The account’s alarms by state, the firing ones named' },
}

/** Who a dashboard is shared with, in words: "everyone in Compute to view and team-compute to edit". */
export function sharingWords(s: Sharing, accountName: string): string {
  const parts: string[] = []
  if (s.account !== 'none') parts.push(`everyone in ${accountName} to ${s.account}`)
  for (const g of s.groups) parts.push(`${g.title ?? g.group} to ${g.access}`)
  if (!parts.length) return 'Only its owner'
  const text = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** How this person reaches a dashboard, in words. */
export function accessWords(d: Pick<Dashboard, 'access' | 'owner'>): string {
  if (d.access === 'owner') return 'Yours'
  return `Shared with you by ${d.owner.name} to ${d.access}`
}

/** The refusal a permit carries, if it was refused by policy. */
export function denialOfPermit(p: Permit | undefined): Denial | undefined {
  return p && !p.allowed ? p.denied : undefined
}

/** The open request statuses: what the Open requests widget counts. */
export const OPEN_REQUEST_STATUSES = ['queued', 'pending', 'running', 'waiting-approval', 'needs-attention'] as const

export function isOpenRequest(status: string): boolean {
  return (OPEN_REQUEST_STATUSES as readonly string[]).includes(status)
}
