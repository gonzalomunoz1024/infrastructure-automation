import type { CodeSample } from '@iaf/console-kit'
import { get } from './api'
import { codeSamplesFor, type CodeContext } from './code'
import type { ConsoleAction, ConsoleType } from './descriptors'
import type { RequiredTag } from './tags'
import type { CommandInput, Schema } from './types'

/*
 * The developer portal (WP 9.16): the console API as its OpenAPI document lists it, sample calls
 * for a type built from its workflows' schemas, and the tiles a firm's internal developer portal
 * embeds (samples until the firm's own examples arrive), any of which a person keeps as a
 * shortcut. The page draws what this module works out.
 */

/* ----------------------------------------------------------------------------
   The console API's description
   ------------------------------------------------------------------------- */

export const OPENAPI_PATH = '/console/v1/openapi.json'
/** The full contract, with every schema, on the hub. */
export const CONTRACT_URL = 'https://github.com/gonzalomunoz1024/infrastructure-automation/blob/console-root/contracts/openapi/console-bff.yaml'

export interface OpenApiDocument {
  openapi?: string
  info?: { title?: string; version?: string; description?: string }
  tags?: { name: string; description?: string }[]
  paths?: Record<string, Record<string, { tags?: string[]; summary?: string; operationId?: string } | unknown>>
}

export interface ApiOperation {
  method: string
  path: string
  summary: string
}

export interface ApiGroup {
  tag: string
  operations: ApiOperation[]
}

export const fetchApiDocument = () => get<OpenApiDocument>(OPENAPI_PATH)

const METHODS = ['get', 'put', 'post', 'patch', 'delete']

/** The document's operations by tag, in the document's tag order, then the order the paths come in. */
export function apiGroupsOf(doc: OpenApiDocument): ApiGroup[] {
  const groups = new Map<string, ApiOperation[]>()
  for (const t of doc.tags ?? []) groups.set(t.name, [])
  for (const [path, item] of Object.entries(doc.paths ?? {})) {
    for (const method of METHODS) {
      const op = (item as Record<string, unknown>)[method] as { tags?: string[]; summary?: string; operationId?: string } | undefined
      if (!op || typeof op !== 'object') continue
      const tag = op.tags?.[0] ?? 'Other'
      if (!groups.has(tag)) groups.set(tag, [])
      groups.get(tag)!.push({ method: method.toUpperCase(), path, summary: op.summary ?? op.operationId ?? '' })
    }
  }
  return [...groups].filter(([, ops]) => ops.length).map(([tag, operations]) => ({ tag, operations }))
}

/* ----------------------------------------------------------------------------
   Sample calls from a type's schemas
   ------------------------------------------------------------------------- */

/** One value a field could take, from what its schema says: an example, the default, the first choice, else a placeholder. */
export function exampleValue(name: string, s: Schema): unknown {
  const examples = s.examples as unknown[] | undefined
  if (Array.isArray(examples) && examples.length) return examples[0]
  if (s.default !== undefined) return s.default
  if (s.enum?.length) return s.enum[0]
  if (s['x-resource']) return `<wfrn of a ${s['x-resource'].type}>`
  const type = Array.isArray(s.type) ? s.type[0] : s.type
  if (type === 'integer' || type === 'number') return s.minimum ?? 1
  if (type === 'boolean') return false
  if (type === 'array') return []
  if (type === 'object') return {}
  return `<${name}>`
}

/** An input for a workflow: every required field, and the optional ones that have an example or a default; the subject left out. */
export function exampleInput(schema: Schema, subject?: string): Record<string, unknown> {
  const required = new Set(schema.required ?? [])
  const out: Record<string, unknown> = {}
  for (const [name, s] of Object.entries(schema.properties ?? {})) {
    if (name === subject || s['x-console']?.sensitive) continue
    const hinted = Array.isArray(s.examples) || s.default !== undefined || !!s.enum?.length
    if (required.has(name) || hinted) out[name] = exampleValue(name, s)
  }
  return out
}

/** The required tags a create names itself: the policy's default, its first value, or a placeholder. */
export function exampleTags(required: readonly RequiredTag[], input: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(
    required.map((t) => {
      const field = t.defaultFrom?.startsWith('input.') ? input[t.defaultFrom.slice('input.'.length)] : undefined
      return [t.key, typeof field === 'string' ? field : (t.default ?? t.values?.[0] ?? `<${t.key}>`)]
    }),
  )
}

/** The verb a sample runs on an existing resource: the first one that is neither create, update nor destructive. */
export function sampleVerbOf(type: ConsoleType): ConsoleAction | undefined {
  return type.actions.find((a) => a.verb !== 'create' && a.verb !== 'update' && !a.destructive) ?? type.actions.find((a) => a.verb !== 'create')
}

function subjectOf(action: ConsoleAction, type: ConsoleType): string | undefined {
  return action.subject ?? Object.entries(action.input.properties ?? {}).find(([, p]) => p?.['x-resource']?.type === type.name)?.[0]
}

function shellQuoted(v: unknown): string {
  const s = typeof v === 'string' ? v : JSON.stringify(v)
  return /^[\w./:@-]+$/.test(s) ? s : `"${s.replace(/(["\\])/g, '\\$1')}"`
}

/** A wfrn to show in samples: one of the type's own, in the person's account and region. */
export function sampleWfrn(type: Pick<ConsoleType, 'service' | 'name'>, ctx: CodeContext & { partition?: string }): string {
  return `wfrn:${ctx.partition ?? 'wf'}:${type.service}:${ctx.region}:${ctx.accountId}:${type.name}/<id>`
}

/**
 * The samples for a type: the console shell (and the CLI, which reads the same lines), the
 * create through every channel View as code knows, and the TypeScript and Java SDKs.
 */
export function portalSamplesFor(type: ConsoleType, ctx: CodeContext & { partition?: string }, required: readonly RequiredTag[]): CodeSample[] {
  const wfrn = sampleWfrn(type, ctx)
  const verb = sampleVerbOf(type)
  const verbInput = verb ? exampleInput(verb.input, subjectOf(verb, type)) : {}
  const shell = [
    `# the ${type.title.toLowerCase()} in production`,
    `ls ${type.name} --filter environment=prod`,
    `get ${wfrn}`,
    ...(verb ? [`run ${verb.verb} ${wfrn}${Object.entries(verbInput).map(([k, v]) => ` --input ${k}=${shellQuoted(v)}`).join('')}${verb.destructive ? ' --confirm' : ''} --wait`] : []),
    'jobs --limit 5',
  ].join('\n')
  const out: CodeSample[] = [{ id: 'shell', label: 'Console shell', language: 'shell', code: `${shell}\n`, note: 'Paste these into the console shell, or after iaf in a terminal.' }]
  if (type.create) {
    const input = exampleInput(type.create.input)
    const body: CommandInput = { input, ...(required.length ? { tags: exampleTags(required, input) } : {}) }
    const created = codeSamplesFor({ kind: 'create', type, action: type.create, body, context: ctx })
    out.push(...created.filter((s) => s.id === 'cli' || s.id === 'rest' || s.id === 'json'))
    const ts = [
      "import { ConsoleClient } from '@iaf/console-sdk'",
      '',
      '// signs in as you the way iaf login does: approve the code in the console',
      'const console = await ConsoleClient.deviceSignIn()',
      `const accepted = await console.resources.create('${type.name}', ${JSON.stringify(body, null, 2).replace(/\n/g, '\n  ')})`,
      'const job = await console.jobs.wait(accepted.jobId)',
      'console.log(job.status)',
    ].join('\n')
    const java = [
      'var console = ConsoleClient.deviceSignIn();',
      `var accepted = console.resources().create("${type.name}", CommandInput.of(Map.of(${Object.entries(input)
        .map(([k, v]) => `"${k}", ${typeof v === 'string' ? `"${v}"` : JSON.stringify(v)}`)
        .join(', ')})));`,
      'var job = console.jobs().await(accepted.jobId());',
    ].join('\n')
    out.push({ id: 'ts', label: 'TypeScript SDK', language: 'typescript', code: `${ts}\n`, note: 'The SDK is generated from the console API; each call names its own Idempotency-Key unless you pass one.' })
    out.push({ id: 'java', label: 'Java SDK', language: 'java', code: `${java}\n` })
  }
  return out
}

/* ----------------------------------------------------------------------------
   Portal tiles
   ------------------------------------------------------------------------- */

export type TileKind = 'resources' | 'drift' | 'cost' | 'workflow'

export interface PortalTile {
  id: string
  kind: TileKind
  title: string
  description: string
  /** what a shortcut to it opens */
  to: string
  /** a workflow tile's type and verb, so its permission is checked */
  workflow?: { service: string; type: string; verb: string }
}

/**
 * Whether a link may be drawn: a console route (`/…`, never `//host`), or an https URL. A tile from
 * the firm's portal arrives as data, so its target is checked before it becomes an href.
 */
export function isSafeLink(to: string): boolean {
  if (/^\/(?![/\\])/.test(to)) return true
  try {
    return new URL(to).protocol === 'https:'
  } catch {
    return false
  }
}

/** Where the tiles' shortcuts are kept: in this browser, for the person (a convenience, not a record). */
export function shortcutsKey(userId: string): string {
  return `console-portal-shortcuts:${userId}`
}

export function readShortcuts(userId: string, storage: Pick<Storage, 'getItem'> = localStorage): string[] {
  try {
    const parsed = JSON.parse(storage.getItem(shortcutsKey(userId)) ?? '[]') as unknown
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

export function writeShortcuts(userId: string, ids: readonly string[], storage: Pick<Storage, 'setItem'> = localStorage) {
  try {
    storage.setItem(shortcutsKey(userId), JSON.stringify(ids))
  } catch {
    /* storage unavailable: the shortcuts live for this page */
  }
}

/** Adds the tile when it is not kept, removes it when it is. */
export function toggleShortcut(ids: readonly string[], id: string): string[] {
  return ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]
}
