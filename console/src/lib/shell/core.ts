import { ParseError, flagOf, parseLine, splitPair, switchOf, type ParsedLine } from './parse'

/*
 * The console CLI's command core, as the console shell runs it in the page (WP 7.4). Hub decision
 * 31 has the CLI generated on the `console-sdk` branch with the console shell "running the CLI's
 * command core with the page's own session"; until that package exists this is the core, written
 * to the interface it would have, so swapping it is one import in lib/shell/console.ts:
 *
 *   execute(line, { client, emit, describeError, sleep, signal, newKey }) → Promise<boolean>
 *
 * A line is parsed (./parse), run against a `ShellClient` (the BFF's calls the CLI makes, here the
 * page's own session; the CLI's is its bearer token), and every result is emitted as an output
 * block: text, a table, or JSON. Nothing here knows about React or the browser, so the CLI can
 * print the same blocks with `toText` (./render).
 */

/* ----------------------------------------------------------------------------
   What the core emits, and what it calls
   ------------------------------------------------------------------------- */

export type ShellTone = 'plain' | 'muted' | 'success' | 'error' | 'warning'

export type ShellOutput =
  | { kind: 'text'; text: string; tone?: ShellTone }
  | { kind: 'table'; columns: string[]; rows: string[][]; caption: string }
  | { kind: 'json'; value: unknown }
  /** the page clears its screen; a terminal prints nothing */
  | { kind: 'clear' }

export interface ShellSession {
  user: { userId: string; name?: string; email?: string; groups?: string[] }
  account: { accountId: string; name?: string; roles?: string[] }
  region: string
}

export interface ShellAction {
  verb: string
  title?: string
  destructive?: boolean
  approval?: boolean
  allowed?: boolean
  denied?: { reason: string; action: string; layer?: string }
}

export interface ShellType {
  key: string
  type: string
  service: string
  plural?: string
  title?: string
  actions: ShellAction[]
}

export interface ShellResource {
  wfrn: string
  type: string
  name?: string
  state: string
  version?: number
  drift?: boolean
  tags: Record<string, string>
  attributes: Record<string, unknown>
  createdAt?: string
  updatedAt?: string
}

export interface ShellJob {
  jobId: string
  workflow: string
  status: string
  title?: string
  wfrn?: string
  startedAt?: string
  endedAt?: string
  requester?: string
  steps?: { id: string; name: string; status: string }[]
  approvals?: { group?: string; status: string }[]
  attention?: { step: string; options: string[] }
}

export interface ShellPage<T> {
  items: T[]
  next: string | null
}

/** The BFF calls the CLI makes. */
export interface ShellClient {
  session: () => Promise<ShellSession>
  types: () => Promise<ShellType[]>
  list: (type: string, params: Record<string, string | number | undefined>) => Promise<ShellPage<ShellResource>>
  get: (type: string, wfrn: string) => Promise<ShellResource>
  run: (type: string, wfrn: string, verb: string, body: { input: Record<string, unknown>; confirm?: boolean; reason?: string }, idempotencyKey: string) => Promise<{ jobId: string; wfrn: string; approval?: { group?: string; status?: string } }>
  jobs: (params: Record<string, string | number | undefined>) => Promise<ShellPage<ShellJob>>
  job: (jobId: string) => Promise<ShellJob>
}

export interface ShellContext {
  client: ShellClient
  emit: (out: ShellOutput) => void
  /** a failed call in words; the CLI prints the error's message */
  describeError?: (e: unknown) => string
  /** waits between polls of `job --follow` */
  sleep?: (ms: number) => Promise<void>
  /** stops `job --follow` (Ctrl+C) */
  signal?: AbortSignal
  /** one Idempotency-Key per `run` */
  newKey?: () => string
  /** how often `job --follow` reads the job */
  pollMs?: number
}

/* ----------------------------------------------------------------------------
   The commands
   ------------------------------------------------------------------------- */

interface CommandDef {
  name: string
  usage: string
  summary: string
  /** flags that take no value */
  switches?: string[]
  examples?: string[]
  run: (p: ParsedLine, ctx: Required<Pick<ShellContext, 'client' | 'emit'>> & ShellContext) => Promise<void>
}

/** Raised for a mistake on the line: printed as it is, with the command's usage. */
export class UsageError extends Error {
  constructor(
    message: string,
    readonly usage?: string,
  ) {
    super(message)
    this.name = 'UsageError'
  }
}

const OPEN_STATUSES = new Set(['queued', 'running', 'waiting-approval', 'needs-attention'])

/** A job that has ended: anything that is not queued, running, waiting or paused. */
export function jobEnded(status: string): boolean {
  return !OPEN_STATUSES.has(status)
}

/** 'waiting-approval' → 'waiting approval'. */
export function statusWords(status: string): string {
  return status.replace(/-/g, ' ')
}

/** The type a wfrn names: 'wfrn:wf:compute:global:123456789012:vm/vm-01' → 'vm'. */
export function typeOfWfrn(wfrn: string): string | null {
  const m = /^wfrn:[^:]*:[^:]*:[^:]*:[^:]*:([^/]+)\/.+$/.exec(wfrn)
  return m ? m[1] : null
}

/** The filters `ls` sends: q, state and drift as themselves, anything else as a tag (`environment=prod` → `tag.environment=prod`). */
export function listFiltersOf(pairs: readonly string[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const text of pairs) {
    const pair = splitPair(text)
    if (!pair || !pair[0]) throw new UsageError(`A filter is name=value, like environment=prod; "${text}" is not.`)
    const [name, value] = pair
    const key = name === 'q' || name === 'state' || name === 'drift' ? name : name.startsWith('tag.') ? name : `tag.${name}`
    out[key] = value
  }
  return out
}

/** `--input k=v` pairs as the run's input: true, false and plain numbers read as themselves, a JSON value after `k:=`. */
export function inputOf(pairs: readonly string[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const text of pairs) {
    const json = /^([^=:]+):=(.*)$/.exec(text)
    if (json) {
      try {
        out[json[1].trim()] = JSON.parse(json[2])
      } catch {
        throw new UsageError(`The value of ${json[1].trim()} after := is not JSON.`)
      }
      continue
    }
    const pair = splitPair(text)
    if (!pair || !pair[0]) throw new UsageError(`An input is name=value, like reason=stuck; "${text}" is not.`)
    out[pair[0]] = pair[1]
  }
  return out
}

function findType(types: readonly ShellType[], name: string): ShellType | undefined {
  const n = name.toLowerCase()
  return types.find((t) => t.type === n || t.key === n || t.plural?.toLowerCase() === n)
}

function cell(v: unknown): string {
  if (v === undefined || v === null || v === '') return '-'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function outputOf(p: ParsedLine): 'table' | 'json' {
  const o = flagOf(p, 'output') ?? 'table'
  if (o !== 'table' && o !== 'json') throw new UsageError(`--output is table or json, not ${o}.`)
  return o
}

function limitOf(p: ParsedLine, fallback: number): number {
  const raw = flagOf(p, 'limit')
  if (raw === undefined) return fallback
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1 || n > 200) throw new UsageError('--limit is a whole number from 1 to 200.')
  return n
}

function jobTable(jobs: readonly ShellJob[], caption: string): ShellOutput {
  return { kind: 'table', caption, columns: ['JOB', 'WORKFLOW', 'STATUS', 'STARTED', 'RESOURCE'], rows: jobs.map((j) => [j.jobId, j.workflow, statusWords(j.status), cell(j.startedAt), cell(j.wfrn)]) }
}

function jobLine(j: ShellJob): string {
  const pending = j.approvals?.find((a) => a.status === 'pending')
  if (j.status === 'waiting-approval' && pending?.group) return `${j.jobId} is waiting for approval by ${pending.group}.`
  if (j.status === 'needs-attention' && j.attention) return `${j.jobId} paused at step ${j.attention.step}. Retry, skip or abort it on its page in the console.`
  const step = j.steps?.find((s) => s.status === 'running')
  return `${j.jobId} is ${statusWords(j.status)}${step && j.status === 'running' ? `: ${step.name}` : ''}.`
}

async function follow(jobId: string, ctx: ShellContext & { client: ShellClient; emit: (o: ShellOutput) => void }): Promise<ShellJob> {
  const sleep = ctx.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  let last = ''
  for (;;) {
    if (ctx.signal?.aborted) {
      ctx.emit({ kind: 'text', text: `Stopped following ${jobId}. It goes on; read it again with: job ${jobId}`, tone: 'muted' })
      throw new FollowStopped()
    }
    const j = await ctx.client.job(jobId)
    const line = jobLine(j)
    if (line !== last) {
      ctx.emit({ kind: 'text', text: line, tone: j.status === 'succeeded' ? 'success' : j.status === 'failed' || j.status === 'rolled-back' ? 'error' : j.status === 'needs-attention' ? 'warning' : 'plain' })
      last = line
    }
    if (jobEnded(j.status) || j.status === 'needs-attention') return j
    await sleep(ctx.pollMs ?? 2_000)
  }
}

/** Ctrl+C while following: the job goes on, the command ends. */
export class FollowStopped extends Error {
  constructor() {
    super('Stopped following')
    this.name = 'FollowStopped'
  }
}

export const COMMANDS: CommandDef[] = [
  {
    name: 'help',
    usage: 'help [command]',
    summary: 'What each command does, or how to use one',
    run: async (p, ctx) => {
      const name = p.args[0]
      if (name) {
        const c = COMMANDS.find((x) => x.name === name)
        if (!c) throw new UsageError(`There is no command ${name}. Type help for the list.`)
        ctx.emit({ kind: 'text', text: `${c.usage}\n  ${c.summary}${c.examples?.length ? `\n\nExamples:\n${c.examples.map((e) => `  ${e}`).join('\n')}` : ''}` })
        return
      }
      ctx.emit({ kind: 'table', caption: 'Commands', columns: ['COMMAND', 'WHAT IT DOES'], rows: COMMANDS.map((c) => [c.usage, c.summary]) })
      ctx.emit({ kind: 'text', text: 'Add --output json to ls, get, types, jobs or job for JSON. Up and Down walk your history.', tone: 'muted' })
    },
  },
  {
    name: 'whoami',
    usage: 'whoami',
    summary: 'Who the shell acts as, in which account and region',
    run: async (p, ctx) => {
      const s = await ctx.client.session()
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: s })
      ctx.emit({
        kind: 'table',
        caption: 'Signed in as',
        columns: ['FIELD', 'VALUE'],
        rows: [
          ['Name', cell(s.user.name)],
          ['Email', cell(s.user.email)],
          ['Account', s.account.name ? `${s.account.name} (${s.account.accountId})` : s.account.accountId],
          ['Region', s.region],
          ['Roles', cell((s.account.roles ?? []).join(', '))],
          ['Groups', cell((s.user.groups ?? []).join(', '))],
        ],
      })
    },
  },
  {
    name: 'types',
    usage: 'types',
    summary: 'The resource types, and the workflows you may run on each',
    run: async (p, ctx) => {
      const types = await ctx.client.types()
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: types })
      ctx.emit({
        kind: 'table',
        caption: 'Resource types',
        columns: ['TYPE', 'TITLE', 'YOU MAY', 'NOT ALLOWED'],
        rows: types.map((t) => [t.type, cell(t.title), cell(t.actions.filter((a) => a.allowed !== false).map((a) => a.verb).join(' ')), cell(t.actions.filter((a) => a.allowed === false).map((a) => a.verb).join(' '))]),
      })
    },
  },
  {
    name: 'ls',
    usage: 'ls <type> [--filter name=value]… [--limit n] [--after cursor]',
    summary: 'List the resources of a type, filtered on the server',
    examples: ['ls vm --filter environment=prod', 'ls vm --filter state=active --filter q=web', 'ls mongodb-cluster -o json'],
    run: async (p, ctx) => {
      const name = p.args[0]
      if (!name) throw new UsageError('Name a type, like ls vm. Type types for the list.')
      const types = await ctx.client.types()
      const t = findType(types, name)
      if (!t) throw new UsageError(`There is no type ${name}. Type types for the list.`)
      const filters = listFiltersOf(p.flags.filter ?? [])
      const page = await ctx.client.list(t.type, { ...filters, limit: limitOf(p, 50), after: flagOf(p, 'after') })
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: page })
      if (!page.items.length) return ctx.emit({ kind: 'text', text: `No ${t.plural ?? t.type} match.`, tone: 'muted' })
      ctx.emit({ kind: 'table', caption: `${t.title ?? t.type}${Object.keys(filters).length ? ` where ${Object.entries(filters).map(([k, v]) => `${k.replace(/^tag\./, '')}=${v}`).join(', ')}` : ''}`, columns: ['NAME', 'STATE', 'ENVIRONMENT', 'DRIFT', 'WFRN'], rows: page.items.map((r) => [cell(r.name), r.state, cell(r.tags.environment), r.drift ? 'yes' : 'no', r.wfrn]) })
      ctx.emit({ kind: 'text', text: `${page.items.length} shown.${page.next ? ` More with: ls ${t.type}${(p.flags.filter ?? []).map((f) => ` --filter ${f}`).join('')} --after ${page.next}` : ''}`, tone: 'muted' })
    },
  },
  {
    name: 'get',
    usage: 'get <wfrn>',
    summary: 'One resource: its state, tags and configuration',
    examples: ['get wfrn:wf:compute:global:123456789012:vm/vm-demo01', 'get <wfrn> -o json'],
    run: async (p, ctx) => {
      const wfrn = p.args[0]
      if (!wfrn) throw new UsageError('Name the resource by its wfrn. ls prints them.')
      const type = typeOfWfrn(wfrn)
      if (!type) throw new UsageError(`${wfrn} is not a wfrn. One reads wfrn:<partition>:<service>:<region>:<account>:<type>/<id>.`)
      const r = await ctx.client.get(type, wfrn)
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: r })
      ctx.emit({
        kind: 'table',
        caption: r.name ?? wfrn,
        columns: ['FIELD', 'VALUE'],
        rows: [
          ['Name', cell(r.name)],
          ['State', r.state],
          ['Drift', r.drift === undefined ? 'not checked yet' : r.drift ? 'yes' : 'no'],
          ['Version', cell(r.version)],
          ['wfrn', r.wfrn],
          ...Object.entries(r.tags).map(([k, v]) => [`Tag ${k}`, v]),
          ...Object.entries(r.attributes).map(([k, v]) => [k, cell(v)]),
        ],
      })
    },
  },
  {
    name: 'run',
    usage: 'run <verb> <wfrn> [--input name=value]… [--confirm] [--wait]',
    summary: 'Run a workflow on a resource; --wait follows its job to the end',
    switches: ['confirm', 'wait'],
    examples: ['run restart <wfrn> --input reason="stuck after a kernel update"', 'run delete <wfrn> --confirm --input reason=retired'],
    run: async (p, ctx) => {
      const [verb, wfrn] = p.args
      if (!verb || !wfrn) throw new UsageError('Name the workflow and the resource, like run restart <wfrn>.')
      const typeName = typeOfWfrn(wfrn)
      if (!typeName) throw new UsageError(`${wfrn} is not a wfrn.`)
      const t = findType(await ctx.client.types(), typeName)
      const action = t?.actions.find((a) => a.verb === verb)
      if (!t || !action) throw new UsageError(`${t?.title ?? typeName} has no workflow ${verb}.${t ? ` It has: ${t.actions.map((a) => a.verb).join(', ')}.` : ''}`)
      if (action.allowed === false) {
        ctx.emit({ kind: 'text', tone: 'error', text: `Not allowed: ${action.denied?.reason ?? `your role does not allow ${t.service}:${verb}`}.\nAsk for access from the resource's page in the console.` })
        return
      }
      const confirm = switchOf(p, 'confirm')
      if (action.destructive && !confirm) throw new UsageError(`${verb} cannot be undone. Add --confirm to run it.`)
      const input = inputOf(p.flags.input ?? [])
      const accepted = await ctx.client.run(t.type, wfrn, verb, { input, ...(confirm ? { confirm: true } : {}) }, ctx.newKey?.() ?? `${Date.now()}`)
      const waiting = accepted.approval?.status === 'pending' ? ` It waits for approval by ${accepted.approval.group ?? 'an approver'}.` : ''
      ctx.emit({ kind: 'text', tone: 'success', text: `Started ${verb} as job ${accepted.jobId}.${waiting}` })
      if (switchOf(p, 'wait')) {
        await follow(accepted.jobId, ctx)
        return
      }
      ctx.emit({ kind: 'text', tone: 'muted', text: `Follow it with: job ${accepted.jobId} --follow` })
    },
  },
  {
    name: 'jobs',
    usage: 'jobs [--status s] [--limit n]',
    summary: 'The account’s requests, newest first',
    run: async (p, ctx) => {
      const page = await ctx.client.jobs({ status: flagOf(p, 'status'), limit: limitOf(p, 20) })
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: page })
      if (!page.items.length) return ctx.emit({ kind: 'text', text: 'No requests match.', tone: 'muted' })
      ctx.emit(jobTable(page.items, 'Requests'))
    },
  },
  {
    name: 'job',
    usage: 'job <job id> [--follow]',
    summary: 'One request and its steps; --follow reads it until it ends',
    switches: ['follow'],
    examples: ['job job_abc123 --follow'],
    run: async (p, ctx) => {
      const id = p.args[0]
      if (!id) throw new UsageError('Name the job, like job job_abc123. jobs lists them.')
      if (switchOf(p, 'follow')) {
        const j = await follow(id, ctx)
        if (j.steps?.length) ctx.emit({ kind: 'table', caption: `Steps of ${id}`, columns: ['STEP', 'STATUS'], rows: j.steps.map((s) => [s.name, statusWords(s.status)]) })
        return
      }
      const j = await ctx.client.job(id)
      if (outputOf(p) === 'json') return ctx.emit({ kind: 'json', value: j })
      ctx.emit({
        kind: 'table',
        caption: j.title ?? id,
        columns: ['FIELD', 'VALUE'],
        rows: [
          ['Job', j.jobId],
          ['Workflow', j.workflow],
          ['Status', statusWords(j.status)],
          ['Resource', cell(j.wfrn)],
          ['Started', cell(j.startedAt)],
          ['Ended', cell(j.endedAt)],
        ],
      })
      if (j.steps?.length) ctx.emit({ kind: 'table', caption: `Steps of ${id}`, columns: ['STEP', 'STATUS'], rows: j.steps.map((s) => [s.name, statusWords(s.status)]) })
      if (!jobEnded(j.status)) ctx.emit({ kind: 'text', tone: 'muted', text: jobLine(j) })
    },
  },
  {
    name: 'clear',
    usage: 'clear',
    summary: 'Clear the screen (Ctrl+L)',
    run: async (_p, ctx) => ctx.emit({ kind: 'clear' }),
  },
]

const SWITCHES = new Set(['help', ...COMMANDS.flatMap((c) => c.switches ?? [])])

/** The command names, for completion. */
export const COMMAND_NAMES = COMMANDS.map((c) => c.name)

/**
 * Runs one line. Every result and every failure is emitted; the answer says whether the
 * command succeeded, as the CLI's exit code would.
 */
export async function execute(line: string, context: ShellContext): Promise<boolean> {
  const ctx = context
  let parsed: ParsedLine
  try {
    parsed = parseLine(line, SWITCHES)
  } catch (e) {
    ctx.emit({ kind: 'text', tone: 'error', text: e instanceof ParseError ? e.message : String(e) })
    return false
  }
  if (!parsed.command) return true
  const def = COMMANDS.find((c) => c.name === parsed.command)
  if (!def) {
    ctx.emit({ kind: 'text', tone: 'error', text: `There is no command ${parsed.command}. Type help for the list.` })
    return false
  }
  if (switchOf(parsed, 'help')) return execute(`help ${def.name}`, ctx)
  try {
    await def.run(parsed, ctx)
    return true
  } catch (e) {
    if (e instanceof FollowStopped) return false
    if (e instanceof UsageError) {
      ctx.emit({ kind: 'text', tone: 'error', text: `${e.message}\nUsage: ${def.usage}` })
      return false
    }
    ctx.emit({ kind: 'text', tone: 'error', text: ctx.describeError ? ctx.describeError(e) : e instanceof Error ? e.message : String(e) })
    return false
  }
}

/** Completes the first word: the one command it starts, or null when none or several do. */
export function completeCommand(line: string): string | null {
  if (/\s/.test(line.trimStart())) return null
  const word = line.trim().toLowerCase()
  if (!word) return null
  const hits = COMMAND_NAMES.filter((n) => n.startsWith(word))
  return hits.length === 1 ? `${hits[0]} ` : null
}
