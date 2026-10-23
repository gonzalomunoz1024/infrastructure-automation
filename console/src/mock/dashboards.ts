import type { Problem } from '@iaf/console-kit'
import { observeOf, partsOfWfrn } from '../lib/descriptors'
import { HEIGHTS, MAX_NAME, MAX_TITLE, MAX_WIDGETS, WIDTHS, type Dashboard, type DashboardList, type GroupShare, type Permit, type ShareAccess, type Sharing, type Widget } from '../lib/dashboards'
import { emptyConsoleState, idFrom, journaled, typeOf, wfrnOfResource, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { authorize, groupsOf, personOf, rolesOf, type Decision } from './directory'

/*
 * Team dashboards as the BFF would keep them (WP 9.19; no hub contract yet, the routes are the
 * console's proposal in lib/dashboards.ts). A dashboard belongs to one account and its owner,
 * who shares it with everyone in the account and with groups of it, each to view or to edit.
 * Console IAM decides the verbs (`dashboards:list|get|create|update|share|delete`, which the
 * platform's role never decides), and the sharing decides who reaches which dashboard: an edit
 * needs `dashboards:update` and the owner's or an edit share's reach; sharing is the owner's
 * (or an admin's) with `dashboards:share`; the owner removes their own with `dashboards:update`,
 * anyone else's takes `dashboards:delete`. A dashboard nobody shared with a person is a 404 to
 * them. Every change sends If-Match with the version it read; writes are journaled under the
 * `dashboard.` kinds, so a reload replays them.
 */

export interface StoredDashboard {
  dashboardId: string
  accountId: string
  name: string
  description?: string
  ownerId: string
  sharing: { account: Sharing['account']; groups: { group: string; access: ShareAccess }[] }
  widgets: Widget[]
  version: number
  createdAt: string
  updatedAt: string
  updatedById?: string
}

export interface DashboardsState {
  dashboards: Record<string, StoredDashboard>
}

const COMPUTE = '123456789012'
const WIDGET_ID = /^[A-Za-z0-9_-]{1,64}$/
const METRIC_NAME = /^[A-Za-z][A-Za-z0-9]{0,63}$/
const TAG_KEY = /^[a-z0-9][a-z0-9-]{0,31}$/

/** The vm wfrn of a demo resource, as the state service names it. */
function wfrnOf(data: MockData, resourceId: string): string {
  const r = data.resources.find((x) => x.id === resourceId)
  const rt = r ? typeOf(data, r.type) : undefined
  return r && rt ? wfrnOfResource(data, { region: data.regionName }, rt, r) : `wfrn:${data.partition}:compute:${data.regionName}:${COMPUTE}:vm/${resourceId}`
}

/** The two dashboards the demo starts with: team-compute's fleet board, shared, and Mateo's own. */
function seeded(data: MockData): StoredDashboard[] {
  return [
    {
      dashboardId: 'dash-compute-fleet',
      accountId: COMPUTE,
      name: 'Compute fleet',
      description: 'The machines team-compute runs, at a glance',
      ownerId: 'gonzalo',
      sharing: { account: 'view', groups: [{ group: 'members', access: 'edit' }] },
      widgets: [
        { widgetId: 'w-web01-cpu', kind: 'metric', title: 'web-01 CPU', type: 'vm', wfrn: wfrnOf(data, 'vm-demo01'), metric: 'cpuPercent', width: 6, height: 2 },
        { widgetId: 'w-vm-states', kind: 'count', title: 'Virtual machines by state', type: 'vm', width: 3, height: 2 },
        { widgetId: 'w-alarms', kind: 'alarms', title: 'Alarms', width: 3, height: 2 },
        { widgetId: 'w-requests', kind: 'requests', title: 'Open requests', width: 4, height: 1 },
      ],
      version: 3,
      createdAt: '2026-10-10T09:00:00.000Z',
      updatedAt: '2026-10-20T14:30:00.000Z',
      updatedById: 'dana',
    },
    {
      dashboardId: 'dash-batch-nights',
      accountId: COMPUTE,
      name: 'Batch nights',
      description: 'How the night batch machines are doing',
      ownerId: 'mateo',
      sharing: { account: 'none', groups: [] },
      widgets: [
        { widgetId: 'w-batch-memory', kind: 'metric', title: 'batch-014 memory', type: 'vm', wfrn: wfrnOf(data, 'vm-gen-004'), metric: 'memoryPercent', width: 8, height: 2 },
        { widgetId: 'w-batch-requests', kind: 'requests', title: 'Open requests', width: 4, height: 2 },
      ],
      version: 1,
      createdAt: '2026-10-18T07:50:00.000Z',
      updatedAt: '2026-10-18T07:50:00.000Z',
    },
  ]
}

/** The state of these routes, made with the seeded dashboards on first use. */
export function dashboardsStateOf(data: MockData): DashboardsState {
  const st = (data.console ??= emptyConsoleState())
  if (st.dashboards) return st.dashboards
  const state: DashboardsState = { dashboards: {} }
  for (const d of seeded(data)) state.dashboards[d.dashboardId] = d
  st.dashboards = state
  return state
}

/* ----------------------------------------------------------------------------
   Who reaches a dashboard, and what they may do with it
   ------------------------------------------------------------------------- */

interface Who {
  userId: string
  accountId: string
}

const RANK: Record<ShareAccess, number> = { view: 1, edit: 2 }

/** How a person reaches a dashboard: their own, through a share to edit or to view, or not at all. */
export function accessOf(data: MockData, d: StoredDashboard, who: Who): Dashboard['access'] | null {
  if (d.accountId !== who.accountId) return null
  if (d.ownerId === who.userId) return 'owner'
  const mine = new Set(groupsOf(data.directory, who.accountId, who.userId).map((g) => g.name))
  let best: ShareAccess | null = d.sharing.account === 'none' ? null : d.sharing.account
  for (const g of d.sharing.groups) if (mine.has(g.group) && (!best || RANK[g.access] > RANK[best])) best = g.access
  return best
}

function dashboardWfrn(data: MockData, accountId: string, id: string): string {
  return `wfrn:${data.partition}:dashboards:${data.regionName}:${accountId}:dashboard/${id}`
}

const nameOf = (data: MockData, userId: string) => personOf(data.directory, userId)?.name ?? userId

function refusedPermit(d: Decision): Permit {
  return { allowed: false, denied: d.denied }
}

/** The BFF's `can` on a dashboard for this person. */
export function permitsOf(data: MockData, d: StoredDashboard, who: Who, access: Dashboard['access']): Dashboard['can'] {
  const can = (action: string) => authorize(data.directory, who, action, dashboardWfrn(data, d.accountId, d.dashboardId))
  const owner = nameOf(data, d.ownerId)
  const admin = rolesOf(data.directory, who.accountId, who.userId).includes('account-admin')
  const update = can('dashboards:update')
  const edit: Permit = !update.allowed ? refusedPermit(update) : access === 'view' ? { allowed: false, why: `${owner} shared it with you to view. Ask them to share it with you to edit.` } : { allowed: true }
  const shareDecision = can('dashboards:share')
  const share: Permit = !shareDecision.allowed ? refusedPermit(shareDecision) : access !== 'owner' && !admin ? { allowed: false, why: `Only its owner, ${owner}, chooses who sees it.` } : { allowed: true }
  const removal = access === 'owner' ? update : can('dashboards:delete')
  const remove: Permit = removal.allowed ? { allowed: true } : refusedPermit(removal)
  return { edit, share, delete: remove }
}

function titleOfGroup(data: MockData, accountId: string, name: string): string {
  const g = (data.directory.groups[accountId] ?? []).find((x) => x.name === name)
  return g?.idpGroups?.[0] ?? name
}

/** A stored dashboard as this person reads it: names for people and groups, how they reach it and what they may do. */
export function viewOf(data: MockData, d: StoredDashboard, who: Who): Dashboard | null {
  const access = accessOf(data, d, who)
  if (!access) return null
  const groups: GroupShare[] = d.sharing.groups.map((g) => ({ group: g.group, title: titleOfGroup(data, d.accountId, g.group), access: g.access }))
  return {
    dashboardId: d.dashboardId,
    accountId: d.accountId,
    name: d.name,
    ...(d.description ? { description: d.description } : {}),
    owner: { userId: d.ownerId, name: nameOf(data, d.ownerId) },
    sharing: { account: d.sharing.account, groups },
    widgets: d.widgets.map((w) => ({ ...w })),
    version: d.version,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    ...(d.updatedById ? { updatedBy: { userId: d.updatedById, name: nameOf(data, d.updatedById) } } : {}),
    access,
    can: permitsOf(data, d, who, access),
  }
}

/* ----------------------------------------------------------------------------
   Validation
   ------------------------------------------------------------------------- */

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

function problem(field: string, code: string, message: string): Problem {
  return { level: 'error', code, field, message }
}

/** What is wrong with a dashboard's name, description and widgets, as the BFF would list it. */
export function dashboardProblems(data: MockData, body: Record<string, unknown>): Problem[] {
  const out: Problem[] = []
  const name = typeof body.name === 'string' ? body.name.trim() : ''
  if (!name) out.push(problem('/name', 'required', 'Give the dashboard a name'))
  else if (name.length > MAX_NAME) out.push(problem('/name', 'maxLength', `A name is up to ${MAX_NAME} characters`))
  if (body.description !== undefined && (typeof body.description !== 'string' || body.description.length > 500)) out.push(problem('/description', 'maxLength', 'A description is up to 500 characters'))
  const widgets = body.widgets === undefined ? [] : body.widgets
  if (!Array.isArray(widgets)) return [...out, problem('/widgets', 'type', 'widgets is a list')]
  if (widgets.length > MAX_WIDGETS) out.push(problem('/widgets', 'maxItems', `A dashboard holds up to ${MAX_WIDGETS} widgets`))
  const seen = new Set<string>()
  widgets.forEach((raw: unknown, i) => {
    const at = `/widgets/${i}`
    if (!isRecord(raw)) {
      out.push(problem(at, 'type', 'A widget is an object'))
      return
    }
    const id = raw.widgetId
    if (typeof id !== 'string' || !WIDGET_ID.test(id)) out.push(problem(`${at}/widgetId`, 'pattern', 'A widget id is 1 to 64 letters, digits, dashes and underscores'))
    else if (seen.has(id)) out.push(problem(`${at}/widgetId`, 'unique', `Two widgets have the id ${id}`))
    else seen.add(id)
    const title = typeof raw.title === 'string' ? raw.title.trim() : ''
    if (!title) out.push(problem(`${at}/title`, 'required', 'Give the widget a title'))
    else if (title.length > MAX_TITLE) out.push(problem(`${at}/title`, 'maxLength', `A title is up to ${MAX_TITLE} characters`))
    if (!(WIDTHS as readonly unknown[]).includes(raw.width)) out.push(problem(`${at}/width`, 'enum', 'width is 3, 4, 6, 8 or 12 twelfths'))
    if (!(HEIGHTS as readonly unknown[]).includes(raw.height)) out.push(problem(`${at}/height`, 'enum', 'height is 1 to 4 rows'))
    switch (raw.kind) {
      case 'metric': {
        const rt = typeof raw.type === 'string' ? typeOf(data, raw.type) : undefined
        if (!rt) out.push(problem(`${at}/type`, 'type.unknown', 'Choose a type the platform has'))
        else if (!observeOf(rt).metrics) out.push(problem(`${at}/type`, 'metrics.notSupported', `${rt.plural} have no metrics read`))
        const parts = typeof raw.wfrn === 'string' ? partsOfWfrn(raw.wfrn) : null
        if (!parts) out.push(problem(`${at}/wfrn`, 'format', 'Choose a resource'))
        else if (rt && parts.type !== rt.name) out.push(problem(`${at}/wfrn`, 'type.mismatch', `The resource is not one of the ${rt.plural}`))
        if (typeof raw.metric !== 'string' || !METRIC_NAME.test(raw.metric)) out.push(problem(`${at}/metric`, 'pattern', 'Choose a series'))
        break
      }
      case 'count': {
        if (typeof raw.type !== 'string' || !typeOf(data, raw.type)) out.push(problem(`${at}/type`, 'type.unknown', 'Choose a type the platform has'))
        if (raw.tag !== undefined) {
          const tag = isRecord(raw.tag) ? raw.tag : {}
          if (typeof tag.key !== 'string' || !TAG_KEY.test(tag.key)) out.push(problem(`${at}/tag/key`, 'pattern', 'A tag name is 1 to 32 lowercase letters, digits or hyphens'))
          if (typeof tag.value !== 'string' || !tag.value.trim() || tag.value.length > 256) out.push(problem(`${at}/tag/value`, 'required', 'Give the tag’s value, up to 256 characters'))
        }
        break
      }
      case 'drift':
      case 'requests':
      case 'alarms':
        break
      default:
        out.push(problem(`${at}/kind`, 'enum', 'kind is metric, count, drift, requests or alarms'))
    }
  })
  return out
}

/** What is wrong with a sharing body: who in the account, and which of its groups. */
export function sharingProblems(data: MockData, accountId: string, body: Record<string, unknown>): Problem[] {
  const out: Problem[] = []
  if (!['none', 'view', 'edit'].includes(body.account as string)) out.push(problem('/account', 'enum', 'account is none, view or edit'))
  const groups = body.groups === undefined ? [] : body.groups
  if (!Array.isArray(groups)) return [...out, problem('/groups', 'type', 'groups is a list')]
  if (groups.length > 20) out.push(problem('/groups', 'maxItems', 'Share with up to 20 groups'))
  const known = new Set((data.directory.groups[accountId] ?? []).map((g) => g.name))
  const seen = new Set<string>()
  groups.forEach((g: unknown, i) => {
    const at = `/groups/${i}`
    const rec = isRecord(g) ? g : {}
    const name = rec.group
    if (typeof name !== 'string' || !known.has(name)) out.push(problem(`${at}/group`, 'group.unknown', `No group ${String(name ?? '')} in this account`))
    else if (seen.has(name)) out.push(problem(`${at}/group`, 'unique', `${name} is named twice`))
    else seen.add(name)
    if (rec.access !== 'view' && rec.access !== 'edit') out.push(problem(`${at}/access`, 'enum', 'access is view or edit'))
  })
  return out
}

function cleanWidgets(raw: unknown): Widget[] {
  if (!Array.isArray(raw)) return []
  return raw.map((w) => {
    const r = w as Widget & Record<string, unknown>
    const base = { widgetId: r.widgetId, title: String(r.title).trim(), width: r.width, height: r.height }
    if (r.kind === 'metric') return { ...base, kind: 'metric', type: r.type, wfrn: r.wfrn, metric: r.metric } as Widget
    if (r.kind === 'count') {
      const tag = r.tag as { key: string; value: string } | undefined
      return { ...base, kind: 'count', type: r.type, ...(tag ? { tag: { key: tag.key, value: tag.value.trim() } } : {}) } as Widget
    }
    return { ...base, kind: r.kind } as Widget
  })
}

/* ----------------------------------------------------------------------------
   Commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

/** Applies a journaled `dashboard.*` command, when it runs and when the journal replays it. */
export function applyDashboardCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = dashboardsStateOf(data)
  const body = isRecord(cmd.body) ? cmd.body : {}
  const at = new Date(cmd.at).toISOString()
  const id = cmd.ids.resourceId ?? ''
  const who = { userId: cmd.userId, accountId: cmd.accountId }
  const answer = (status: number, d: StoredDashboard): RouteAnswer => ({ status, body: viewOf(data, d, who), headers: { ETag: `"${d.version}"` } })
  switch (cmd.kind) {
    case 'dashboard.create': {
      const d: StoredDashboard = {
        dashboardId: id,
        accountId: cmd.accountId,
        name: String(body.name).trim(),
        ...(typeof body.description === 'string' && body.description.trim() ? { description: body.description.trim() } : {}),
        ownerId: cmd.userId,
        sharing: { account: 'none', groups: [] },
        widgets: cleanWidgets(body.widgets),
        version: 1,
        createdAt: at,
        updatedAt: at,
      }
      st.dashboards[id] = d
      return answer(201, d)
    }
    case 'dashboard.update': {
      const d = st.dashboards[id]
      if (!d) return { status: 404, body: { error: 'dashboard.not-found' } }
      d.name = String(body.name).trim()
      if (typeof body.description === 'string' && body.description.trim()) d.description = body.description.trim()
      else delete d.description
      d.widgets = cleanWidgets(body.widgets)
      d.version += 1
      d.updatedAt = at
      d.updatedById = cmd.userId
      return answer(200, d)
    }
    case 'dashboard.share': {
      const d = st.dashboards[id]
      if (!d) return { status: 404, body: { error: 'dashboard.not-found' } }
      const groups = Array.isArray(body.groups) ? (body.groups as { group: string; access: ShareAccess }[]).map((g) => ({ group: g.group, access: g.access })) : []
      d.sharing = { account: (body.account as Sharing['account']) ?? 'none', groups }
      d.version += 1
      d.updatedAt = at
      d.updatedById = cmd.userId
      return answer(200, d)
    }
    case 'dashboard.delete': {
      if (!st.dashboards[id]) return { status: 404, body: { error: 'dashboard.not-found' } }
      delete st.dashboards[id]
      return { status: 204 }
    }
    default:
      return { status: 404, body: { error: `No command ${cmd.kind}` } }
  }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

/** The version an If-Match names (`"3"`, `W/"3"`, `3`), or null for none. */
function versionOf(ifMatch: string | undefined): number | null {
  if (!ifMatch || ifMatch.trim() === '*') return null
  const n = Number(ifMatch.trim().replace(/^W\//, '').replace(/"/g, ''))
  return Number.isFinite(n) ? n : null
}

function invalid(problems: Problem[]): RouteAnswer {
  return { status: 400, body: { error: 'validation.failed', detail: `${problems.length === 1 ? 'One field needs' : `${problems.length} fields need`} attention`, problems } }
}

const notAllowed = (method: string, path: string): RouteAnswer => ({ status: 405, body: { error: `${method} is not allowed on ${path}` } })

/** Answers the dashboards paths, or null for any other path. */
export function dashboardRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (path !== '/console/v1/dashboards' && !path.startsWith('/console/v1/dashboards/')) return null
  const s = ctx.session
  const who = { userId: s.userId, accountId: s.accountId }
  const st = dashboardsStateOf(data)
  const can = (action: string, id = '*') => authorize(data.directory, who, action, dashboardWfrn(data, s.accountId, id))
  const refused = (d: Decision): RouteAnswer => ({ status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(`${ctx.now}:${path}`, 8)}` } })
  const command = (kind: `dashboard.${string}`, id: string, body: unknown) => journaled(data, ctx, { at: ctx.now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type: 'dashboard', body, ids: { resourceId: id } })

  if (path === '/console/v1/dashboards') {
    if (method === 'GET') {
      const d = can('dashboards:list')
      if (!d.allowed) return refused(d)
      const scope = params.get('scope') ?? 'all'
      if (!['all', 'mine', 'shared'].includes(scope)) return invalid([problem('/scope', 'enum', 'scope is all, mine or shared')])
      const items = Object.values(st.dashboards)
        .map((x) => viewOf(data, x, who))
        .filter((x): x is Dashboard => !!x && (scope === 'all' || (scope === 'mine') === (x.access === 'owner')))
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.dashboardId.localeCompare(b.dashboardId))
      const create = can('dashboards:create')
      const list: DashboardList = { items, next: null, can: { create: create.allowed ? { allowed: true } : refusedPermit(create) } }
      return { status: 200, body: list }
    }
    if (method !== 'POST') return notAllowed(method, path)
    const d = can('dashboards:create')
    if (!d.allowed) return refused(d)
    const problems = dashboardProblems(data, ctx.body ?? {})
    if (problems.length) return invalid(problems)
    const id = `dash-${idFrom(`${s.userId}:${s.accountId}:${ctx.now}:${String(ctx.body.name)}:${ctx.headers['idempotency-key'] ?? ''}`, 10)}`
    const answer = command('dashboard.create', id, { name: ctx.body.name, description: ctx.body.description, widgets: ctx.body.widgets ?? [] })
    return answer.status < 400 ? { ...answer, headers: { ...answer.headers, Location: `/console/v1/dashboards/${(answer.body as Dashboard).dashboardId}` } } : answer
  }

  const m = /^\/console\/v1\/dashboards\/([^/]+)(\/sharing)?$/.exec(path)
  if (!m) return { status: 404, body: { error: 'dashboard.not-found', detail: `Nothing at ${path}` } }
  const id = decodeURIComponent(m[1])
  const stored = st.dashboards[id]
  const view = stored ? viewOf(data, stored, who) : null
  // a dashboard nobody shared with this person is not there for them
  if (!stored || !view) return { status: 404, body: { error: 'dashboard.not-found', detail: `No dashboard ${id} that you can see in this account` } }
  const changed = (): RouteAnswer | null => {
    const want = versionOf(ctx.headers['if-match'])
    if (want === null) return { status: 428, body: { error: 'if-match.required', detail: 'Send the version you read as If-Match, so a change made meanwhile is not lost' } }
    if (want !== stored.version) {
      const by = stored.updatedById ? nameOf(data, stored.updatedById) : 'Someone'
      return { status: 409, body: { error: 'dashboard.changed', detail: `${by} changed this dashboard after you read it: it is at version ${stored.version}`, version: stored.version, updatedBy: stored.updatedById } }
    }
    return null
  }

  if (m[2]) {
    if (method !== 'PUT') return notAllowed(method, path)
    if (!view.can.share.allowed) return view.can.share.denied ? refused(view.can.share) : { status: 403, body: { error: 'dashboard.notOwner', detail: view.can.share.why } }
    const problems = sharingProblems(data, s.accountId, ctx.body ?? {})
    if (problems.length) return invalid(problems)
    const stale = changed()
    if (stale) return stale
    return command('dashboard.share', id, { account: ctx.body.account, groups: ctx.body.groups ?? [] })
  }
  if (method === 'GET') {
    const d = can('dashboards:get', id)
    if (!d.allowed) return refused(d)
    if (ctx.headers['if-none-match'] === `"${stored.version}"`) return { status: 304, headers: { ETag: `"${stored.version}"` } }
    return { status: 200, body: view, headers: { ETag: `"${stored.version}"` } }
  }
  if (method === 'PUT') {
    if (!view.can.edit.allowed) return view.can.edit.denied ? refused(view.can.edit) : { status: 403, body: { error: 'dashboard.viewOnly', detail: view.can.edit.why } }
    const problems = dashboardProblems(data, ctx.body ?? {})
    if (problems.length) return invalid(problems)
    const stale = changed()
    if (stale) return stale
    return command('dashboard.update', id, { name: ctx.body.name, description: ctx.body.description, widgets: ctx.body.widgets ?? [] })
  }
  if (method === 'DELETE') {
    if (!view.can.delete.allowed) return refused(view.can.delete)
    const stale = changed()
    if (stale) return stale
    return command('dashboard.delete', id, null)
  }
  return notAllowed(method, path)
}
