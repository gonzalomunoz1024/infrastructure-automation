import { ICON_NAMES, type IconName } from '@iaf/ui'
import { columnFields, lifecycleOf, searchableFields, sensitiveFields, titleCase, xConsoleOf, type CatalogEntry, type LifecycleClass, type Resource, type ResourceType, type Schema, type XConsole } from '@iaf/console-kit'

/*
 * One ConsoleType per resource type, composed in the browser from what the platform serves today
 * (GET /api/v1/resource-types and /catalog) with the defaults the plan fixes, and refined by the
 * `service`, `console` and `x-console` hints of P-06 when a type carries them. In phase 3 the BFF
 * serves this composition instead; the shapes stay.
 *
 *   service   metadata.service, else `service`, else the owner without its `team-` prefix
 *   title     console.title, else the plural capitalised
 *   icon      console.icon when Atlas has it, else by the type's name, else the resource glyph
 *   columns   console.list.columns, else name, status, wfrn, the x-console columns (else up to
 *             four required scalar fields), owner, created
 *   lifecycle console.lifecycle, else each state by its name (ready is active, building pending,
 *             retired deleted, failed failed); a state nobody can place stays null
 *   actions   the catalog entries whose noun is the type; create is the producedBy workflow
 */

export type ColumnKind = 'name' | 'status' | 'wfrn' | 'owner' | 'created' | 'updated' | 'field'

export interface ConsoleColumn {
  key: string
  label: string
  kind: ColumnKind
  /** the attribute read for a `field` column */
  field?: string
  x: XConsole
  schema?: Schema
}

export interface ConsoleAction {
  workflow: string
  title: string
  verb: string
  method: string
  path: string
  batchPath?: string
  previewPath?: string
  destructive: boolean
  input: Schema
  description?: string
  version?: string
}

export const DEFAULT_TABS = ['Configuration', 'Drift', 'Activity', 'Relationships', 'Tags', 'Permissions', 'JSON'] as const

export interface ConsoleType {
  name: string
  plural: string
  service: string
  title: string
  description?: string
  icon: IconName
  owner: string
  visibility?: string
  states: string[]
  lifecycle: Record<string, LifecycleClass | null>
  columns: ConsoleColumn[]
  defaultSort: string
  tabs: string[]
  actions: ConsoleAction[]
  create: ConsoleAction | null
  schema: Schema
  searchable: string[]
  sensitive: string[]
  /** the type carried a `console` block or a `service` of its own */
  hinted: boolean
}

export interface ConsoleService {
  id: string
  title: string
  icon: IconName
  types: ConsoleType[]
  /** the teams behind it */
  owners: string[]
}

const TYPE_ICONS: Record<string, IconName> = {
  vm: 'terminal',
  database: 'database',
  'mongodb-cluster': 'database',
  image: 'layers',
  volume: 'folder',
  environment: 'grid',
  address: 'link',
  network: 'link',
  secret: 'key',
  certificate: 'shield',
}

const SERVICE_ICONS: Record<string, IconName> = {
  compute: 'terminal',
  data: 'database',
  platform: 'layers',
  network: 'link',
  storage: 'folder',
  security: 'shield',
}

export function isIconName(s: unknown): s is IconName {
  return typeof s === 'string' && (ICON_NAMES as string[]).includes(s)
}

export function capitalise(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s
}

/** "Virtual machines" → "Virtual machine", "Policies" → "Policy", "Databases" → "Database". */
export function singularOf(plural: string): string {
  if (/ies$/i.test(plural)) return plural.replace(/ies$/i, 'y')
  if (/(ss|us|is)$/i.test(plural)) return plural
  if (/(ches|shes|xes|zes|sses)$/i.test(plural)) return plural.replace(/es$/i, '')
  if (/s$/i.test(plural)) return plural.slice(0, -1)
  return plural
}

/** What a type is for: its description, else what creates it and how the platform tracks it. */
export function purposeOf(t: Pick<ConsoleType, 'title' | 'description' | 'create' | 'owner' | 'states'>): string {
  if (t.description?.trim()) return t.description.trim()
  const by = t.create ? `the "${t.create.title}" workflow` : `${t.owner}'s workflows`
  const path = t.states.length > 1 ? ` from ${t.states[0]} to ${t.states[t.states.length - 1]}` : ''
  return `${t.title} are created by ${by} and tracked by the platform${path}.`
}

/** metadata.service, else `service`, else the owner without `team-`. */
export function serviceOf(rt: ResourceType): string {
  const meta = (rt as { metadata?: { service?: unknown } }).metadata?.service
  if (typeof meta === 'string' && meta.trim()) return meta.trim().toLowerCase()
  if (typeof rt.service === 'string' && rt.service.trim()) return rt.service.trim().toLowerCase()
  return (rt.owner ?? 'platform').replace(/^team-/, '').toLowerCase() || 'platform'
}

/** console.title, else the plural capitalised ("vms" → "Vms", "databases" → "Databases"). */
export function titleOf(rt: ResourceType): string {
  const hinted = rt.console?.title
  if (typeof hinted === 'string' && hinted.trim()) return hinted.trim()
  return capitalise(rt.plural.replace(/[-_]+/g, ' '))
}

export function iconOf(rt: ResourceType): IconName {
  const hinted = rt.console?.icon
  if (isIconName(hinted)) return hinted
  return TYPE_ICONS[rt.name] ?? 'resource'
}

export function serviceIcon(id: string, types: ConsoleType[]): IconName {
  return SERVICE_ICONS[id] ?? types[0]?.icon ?? 'resource'
}

/** Each state folded onto a lifecycle class: the type's own map first, then the state's name. */
export function lifecycleMapOf(rt: ResourceType): Record<string, LifecycleClass | null> {
  const out: Record<string, LifecycleClass | null> = {}
  for (const state of rt.states ?? []) out[state] = lifecycleOf(state, rt.console?.lifecycle)
  for (const state of Object.keys(rt.console?.lifecycle ?? {})) if (!(state in out)) out[state] = lifecycleOf(state, rt.console?.lifecycle)
  return out
}

function isScalar(prop: Schema | undefined): boolean {
  if (!prop) return false
  const t = Array.isArray(prop.type) ? prop.type.find((x) => x !== 'null') : prop.type
  return t === 'string' || t === 'number' || t === 'integer' || t === 'boolean' || (!t && !!prop.enum)
}

const BUILTIN_COLUMNS: Record<string, ConsoleColumn> = {
  name: { key: 'name', label: 'Name', kind: 'name', x: {} },
  status: { key: 'status', label: 'Status', kind: 'status', x: {} },
  wfrn: { key: 'wfrn', label: 'wfrn', kind: 'wfrn', x: {} },
  owner: { key: 'owner', label: 'Owner', kind: 'owner', x: {} },
  created: { key: 'created', label: 'Created', kind: 'created', x: {} },
  updated: { key: 'updated', label: 'Updated', kind: 'updated', x: {} },
}

const BUILTIN_ALIASES: Record<string, string> = { createdAt: 'created', updatedAt: 'updated', id: 'name' }

export function fieldColumn(rt: ResourceType, name: string): ConsoleColumn {
  const prop = rt.schema?.properties?.[name]
  return { key: name, label: prop?.title ?? titleCase(name), kind: 'field', field: name, x: xConsoleOf(prop), schema: prop }
}

/** The list columns: the type's own list, else the defaults. Sensitive fields never make a column. */
export function columnsOf(rt: ResourceType): ConsoleColumn[] {
  const props = rt.schema?.properties ?? {}
  const sensitive = new Set(sensitiveFields(rt.schema))
  const hinted = rt.console?.list?.columns
  if (Array.isArray(hinted) && hinted.length) {
    const out: ConsoleColumn[] = []
    for (const key of hinted) {
      const builtin = BUILTIN_COLUMNS[BUILTIN_ALIASES[key] ?? key]
      if (builtin) out.push(builtin)
      else if (props[key] && !sensitive.has(key)) out.push(fieldColumn(rt, key))
    }
    if (out.length) return out
  }
  // a field the built-in columns already show (name) never repeats as a column
  const eligible = (f: string) => !sensitive.has(f) && !(f in BUILTIN_COLUMNS)
  let fields = columnFields(rt.schema).filter(eligible)
  if (fields.length === 0) fields = (rt.schema?.required ?? []).filter((n) => isScalar(props[n]) && eligible(n)).slice(0, 4)
  return [BUILTIN_COLUMNS.name, BUILTIN_COLUMNS.status, BUILTIN_COLUMNS.wfrn, ...fields.map((f) => fieldColumn(rt, f)), BUILTIN_COLUMNS.owner, BUILTIN_COLUMNS.created]
}

export function toAction(entry: CatalogEntry): ConsoleAction {
  return {
    workflow: entry.workflow,
    title: entry.title,
    verb: entry.verb,
    method: entry.method,
    path: entry.path,
    batchPath: entry.batchPath,
    previewPath: entry.previewPath,
    destructive: entry.destructive ?? entry.verb === 'delete',
    input: entry.input ?? { type: 'object' },
    description: entry.description,
    version: entry.version,
  }
}

/** The catalog entries whose noun is the type, create first, delete last. */
export function actionsOf(rt: ResourceType, catalog: CatalogEntry[]): ConsoleAction[] {
  const rank = (a: ConsoleAction) => (a.verb === 'create' ? 0 : a.destructive ? 2 : 1)
  return catalog
    .filter((e) => e.noun === rt.name)
    .map(toAction)
    .sort((a, b) => rank(a) - rank(b) || a.title.localeCompare(b.title))
}

export function composeType(rt: ResourceType, catalog: CatalogEntry[]): ConsoleType {
  const actions = actionsOf(rt, catalog)
  const producer = rt.producedBy ? catalog.find((e) => e.workflow === rt.producedBy) : undefined
  const create = producer ? toAction(producer) : (actions.find((a) => a.verb === 'create') ?? null)
  const columns = columnsOf(rt)
  const hintedSort = rt.console?.list?.defaultSort
  const tabs = rt.console?.detail?.tabs?.filter((t) => typeof t === 'string' && t.trim())
  return {
    name: rt.name,
    plural: rt.plural,
    service: serviceOf(rt),
    title: titleOf(rt),
    description: rt.description,
    icon: iconOf(rt),
    owner: rt.owner,
    visibility: rt.visibility,
    states: rt.states ?? [],
    lifecycle: lifecycleMapOf(rt),
    columns,
    defaultSort: hintedSort && columns.some((c) => c.key === hintedSort) ? hintedSort : 'name',
    tabs: tabs?.length ? tabs : [...DEFAULT_TABS],
    actions,
    create,
    schema: rt.schema ?? { type: 'object' },
    searchable: searchableFields(rt.schema),
    sensitive: sensitiveFields(rt.schema),
    hinted: !!rt.console || typeof rt.service === 'string',
  }
}

export function composeTypes(types: ResourceType[], catalog: CatalogEntry[]): ConsoleType[] {
  return types.map((rt) => composeType(rt, catalog)).sort((a, b) => a.service.localeCompare(b.service) || a.title.localeCompare(b.title))
}

/** Types grouped by service, services by name. */
export function groupServices(types: ConsoleType[]): ConsoleService[] {
  const map = new Map<string, ConsoleType[]>()
  for (const t of types) map.set(t.service, [...(map.get(t.service) ?? []), t])
  return [...map.entries()]
    .map(([id, ts]) => ({ id, title: capitalise(id), icon: serviceIcon(id, ts), types: ts, owners: [...new Set(ts.map((t) => t.owner))].sort() }))
    .sort((a, b) => a.title.localeCompare(b.title))
}

export function findType(types: ConsoleType[], service: string, name: string): ConsoleType | undefined {
  return types.find((t) => t.service === service && t.name === name) ?? types.find((t) => t.name === name)
}

export function findService(services: ConsoleService[], id: string): ConsoleService | undefined {
  return services.find((s) => s.id === id)
}

/* ----------------------------------------------------------------------------
   wfrns: the platform's, or one derived in the browser until P-05 mints them
   ------------------------------------------------------------------------- */

export interface WfrnContext {
  partition: string
  region: string
  account: string
}

function segment(s: string, fallback = 'x'): string {
  const out = s.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '')
  return out || fallback
}

/**
 * The wfrn of a resource: the one it carries (P-05), else one derived from the console's
 * context and the resource's id, marked as derived so the page can say so.
 */
export function wfrnOf(resource: Resource, type: Pick<ConsoleType, 'name' | 'service'>, ctx: WfrnContext): { wfrn: string; derived: boolean } {
  if (typeof resource.wfrn === 'string' && resource.wfrn.startsWith('wfrn:')) return { wfrn: resource.wfrn, derived: false }
  const id = resource.id
    .split('/')
    .map((p) => segment(p))
    .join('/')
  const region = resource.region ? segment(resource.region) : segment(ctx.region)
  const account = resource.accountId && /^\d{12}$/.test(resource.accountId) ? resource.accountId : ctx.account
  return { wfrn: `wfrn:${segment(ctx.partition, 'wf')}:${segment(type.service)}:${region}:${account}:${segment(type.name)}/${id}`, derived: true }
}

export interface WfrnParts {
  partition: string
  service: string
  region: string
  account: string
  type: string
  id: string
}

/** The six parts of a wfrn, or null when the string is not shaped like one. */
export function partsOfWfrn(wfrn: string): WfrnParts | null {
  const m = /^wfrn:([^:]*):([^:]*):([^:]*):([^:]*):([^/]+)\/(.+)$/.exec(wfrn)
  return m ? { partition: m[1], service: m[2], region: m[3], account: m[4], type: m[5], id: m[6] } : null
}

/** The resource id a derived or minted wfrn points at: the part after the type. */
export function idOfWfrn(wfrn: string): string | null {
  return partsOfWfrn(wfrn)?.id ?? null
}

/** Counts per lifecycle class for a service landing's tiles. */
export function countByLifecycle(resources: Resource[], type: ConsoleType): Record<LifecycleClass | 'other', number> {
  const out: Record<LifecycleClass | 'other', number> = { pending: 0, active: 0, updating: 0, deleting: 0, deleted: 0, failed: 0, other: 0 }
  for (const r of resources) {
    const cls = type.lifecycle[r.status] ?? lifecycleOf(r.status)
    out[cls ?? 'other']++
  }
  return out
}
