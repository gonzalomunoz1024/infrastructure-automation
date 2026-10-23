import { cliCommand, cliFlags, curlCommand, sensitiveFields, toYaml, type CodeSample } from '@iaf/console-kit'
import { BFF_BASE } from './bff'
import type { ConsoleAction, ConsoleType } from './descriptors'
import type { CommandInput } from './types'

/*
 * View as code (WP 3.5): every wizard, action and record in the console can be taken away as
 * a CLI line, a REST call (the BFF's, and the platform's own), a workflow run manifest for a
 * Git repository, or plain JSON and YAML. The texts are built here, from the type, the action
 * and what the person typed; the kit's CodeView shows them.
 */

export interface CodeContext {
  accountId: string
  region: string
  /** the console's origin, e.g. https://console.example.test; the current one when left out */
  origin?: string
}

export type CodeRequest =
  | { kind: 'create'; type: ConsoleType; action: ConsoleAction | null; body: CommandInput; context: CodeContext }
  | { kind: 'update'; type: ConsoleType; wfrn: string; body: CommandInput; context: CodeContext; /** the type's update workflow, when the catalog has one */ action?: ConsoleAction | null; /** the version the person read, sent as If-Match */ version?: number }
  | { kind: 'verb'; type: ConsoleType; action: ConsoleAction; wfrn: string; body: CommandInput; context: CodeContext }
  | { kind: 'record'; type: ConsoleType; wfrn: string; record: unknown; context: CodeContext }

export const CLI = 'iaf'
const CSRF_NOTE = 'The BFF takes the session cookie and X-Csrf-Token from a signed-in browser; from a terminal, use an access key as a Bearer token instead.'
const KEY_PLACEHOLDER = '<one fresh key per submission>'

function originOf(ctx: CodeContext): string {
  if (ctx.origin) return ctx.origin
  if (typeof window !== 'undefined' && window.location?.origin && window.location.origin !== 'null') return window.location.origin
  return 'https://console.example.test'
}

/** `create-vm`, `restart-vm`, `get-vm`: the CLI's verb for a type. */
export function cliVerb(verb: string, type: ConsoleType): string {
  return `${verb}-${type.name}`
}

function globalFlags(ctx: CodeContext): string[] {
  return [`--account ${ctx.accountId}`, `--region ${ctx.region}`]
}

function commandFlags(body: CommandInput): string[] {
  const flags = cliFlags(body.input)
  if (body.reason) flags.push(...cliFlags({ reason: body.reason }))
  for (const [k, v] of Object.entries(body.labels ?? {})) flags.push(...cliFlags({ label: `${k}=${v}` }))
  if (body.confirm) flags.push('--confirm')
  return flags
}

/** The platform's own path for an action, with the subject filled in ("/api/v1/vms/{vmId}:restart" → "/api/v1/vms/vm-demo01:restart"). */
export function platformPath(action: ConsoleAction, subjectId?: string): string {
  return action.path.replace(/\{[^}]+\}/g, subjectId ?? '<id>')
}

function idOf(wfrn: string): string {
  const i = wfrn.lastIndexOf('/')
  return i >= 0 ? wfrn.slice(i + 1) : wfrn
}

/** A `kind: WorkflowRun` manifest: the request as a file a Git repository (and the CLI's `apply`) can carry. */
export function workflowRunYaml(req: Exclude<CodeRequest, { kind: 'record' }>): string {
  const action = req.kind === 'update' ? (req.action ?? null) : req.action
  const workflow = action?.workflow ?? (req.kind === 'update' ? `update-${req.type.name}` : `create-${req.type.name}`)
  const doc: Record<string, unknown> = {
    apiVersion: 'iaf.platform/v1',
    kind: 'WorkflowRun',
    metadata: {
      workflow,
      version: action?.version,
      account: req.context.accountId,
      region: req.context.region,
      subject: req.kind === 'create' ? undefined : req.wfrn,
      reason: req.body.reason || undefined,
      labels: req.body.labels && Object.keys(req.body.labels).length ? req.body.labels : undefined,
    },
    spec: {
      input: req.body.input,
      confirm: req.body.confirm || undefined,
    },
  }
  return `${toYaml(doc)}\n`
}

/** What stands in for a sensitive value: the code is for copying and sharing, the value stays in the form. */
export const SENSITIVE_PLACEHOLDER = '<sensitive: fill in>'

/** The request with every sensitive input (the type's, or the workflow's own) replaced by a placeholder. */
function masked(req: CodeRequest): CodeRequest {
  if (req.kind === 'record') return req
  const action = req.kind === 'update' ? (req.action ?? null) : req.action
  const hidden = new Set([...req.type.sensitive, ...sensitiveFields(action?.input)])
  const input = req.body.input
  if (!Object.keys(input).some((k) => hidden.has(k))) return req
  const out = Object.fromEntries(Object.entries(input).map(([k, v]) => [k, hidden.has(k) && v !== undefined && v !== '' ? SENSITIVE_PLACEHOLDER : v]))
  return { ...req, body: { ...req.body, input: out } } as CodeRequest
}

/** The samples for one request or record: CLI, REST (BFF), REST (platform), workflow YAML, JSON. */
export function codeSamplesFor(request: CodeRequest): CodeSample[] {
  const req = masked(request)
  const origin = originOf(req.context)
  const bff = `${origin}${BFF_BASE}`
  const enc = encodeURIComponent
  const type = req.type

  if (req.kind === 'record') {
    const id = idOf(req.wfrn)
    return [
      { id: 'json', label: 'JSON', language: 'json', code: `${JSON.stringify(req.record, null, 2)}\n`, note: 'The record as the platform holds it.' },
      { id: 'yaml', label: 'YAML', language: 'yaml', code: `${toYaml(req.record)}\n` },
      { id: 'cli', label: 'CLI', language: 'shell', code: cliCommand([CLI, type.service, cliVerb('get', type), req.wfrn], globalFlags(req.context)), note: `${CLI} is the generated console CLI; add --output yaml for YAML.` },
      {
        id: 'rest',
        label: 'REST',
        language: 'shell',
        code: `# through the console (what this page reads)\n${curlCommand({ method: 'GET', url: `${bff}/r/${enc(type.name)}/${enc(req.wfrn)}`, headers: { Cookie: 'console_session=<your session>' } })}\n\n# straight from the platform\n${curlCommand({ method: 'GET', url: `${origin}/api/v1/${enc(type.plural)}/${enc(id)}`, headers: { 'X-User': '<you>' } })}`,
        note: CSRF_NOTE,
      },
    ]
  }

  const body = req.body
  const subjectId = req.kind === 'create' ? undefined : idOf(req.wfrn)
  const verb = req.kind === 'create' ? 'create' : req.kind === 'update' ? 'update' : req.action.verb
  const words = [CLI, type.service, cliVerb(verb, type), ...(req.kind === 'create' ? [] : [req.wfrn])]
  const cli = cliCommand(words, [...globalFlags(req.context), ...commandFlags(body)])

  const bffUrl = req.kind === 'create' ? `${bff}/r/${enc(type.name)}` : req.kind === 'update' ? `${bff}/r/${enc(type.name)}/${enc(req.wfrn)}` : `${bff}/r/${enc(type.name)}/${enc(req.wfrn)}:${req.action.verb}`
  const bffMethod = req.kind === 'update' ? 'PATCH' : 'POST'
  // an update names the version it was made against, so a change made in between is refused rather than overwritten
  const ifMatch: Record<string, string> = req.kind === 'update' && req.version !== undefined ? { 'If-Match': String(req.version) } : {}
  const bffCall = curlCommand({ method: bffMethod, url: bffUrl, headers: { 'Idempotency-Key': KEY_PLACEHOLDER, ...ifMatch, 'X-Csrf-Token': '<the session’s csrfToken>', Cookie: 'console_session=<your session>' }, body })

  const action = req.kind === 'update' ? (req.action ?? null) : req.action
  const subject = action ? (action.subject ?? subjectFor(action, type)) : undefined
  // the reason the console carries beside an update's input is the workflow's own operational input on the platform (P-08)
  const reason = req.kind === 'update' && body.reason && action?.input.properties?.reason ? { reason: body.reason } : {}
  const platformCall = action
    ? curlCommand({
        method: action.method,
        url: `${origin}${platformPath(action, subjectId)}`,
        headers: { 'X-User': '<you>', 'X-On-Behalf-Of': `account:${req.context.accountId}`, 'Idempotency-Key': KEY_PLACEHOLDER },
        body: req.kind !== 'create' ? { ...body.input, ...reason, ...(subject ? { [subject]: subjectId } : {}) } : body.input,
      })
    : `# the platform runs the type's update workflow for a PATCH through the console; it has no direct path of its own`

  return [
    { id: 'cli', label: 'CLI', language: 'shell', code: cli, note: `${CLI} is the generated console CLI. It returns the request id to follow; add --wait to block until the job ends.` },
    { id: 'rest', label: 'REST', language: 'shell', code: `# through the console\n${bffCall}\n\n# straight from the platform\n${platformCall}`, note: CSRF_NOTE },
    { id: 'workflow', label: 'Workflow YAML', language: 'yaml', code: workflowRunYaml(req), note: `A WorkflowRun manifest: commit it and ${CLI} apply -f runs it, so the change is reviewed like code.` },
    { id: 'json', label: 'JSON', language: 'json', code: `${JSON.stringify(body, null, 2)}\n`, note: 'The body the console sends.' },
  ]
}

/** The input that names the resource a verb acts on: the one x-resource input of the type (P-08). */
export function subjectFor(action: ConsoleAction, type: Pick<ConsoleType, 'name'>): string | undefined {
  return Object.entries(action.input.properties ?? {}).find(([, p]) => p?.['x-resource']?.type === type.name)?.[0]
}
