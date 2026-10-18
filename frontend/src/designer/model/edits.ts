/**
 * The structural edits of a workflow document, as pure functions: insert a step at a point of
 * the flow, remove, rename, duplicate and paste steps, connect a switch case, move a node,
 * change triggers and compensation. Every function clones the document and returns the copy,
 * so the store can keep the previous one for undo. Keys the canvas does not understand stay.
 */
import { COMMON_KEYS, STEP_TYPES, type JsonObject, type JsonValue, type StepBody, type StepDocument, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import { allStepLocations, findStepLocation, type Position, type StepLocation } from './graph'
import { clone, stepTypeOfBody, walkIds } from './fixes'

/** Where a new step goes. */
export type InsertPoint =
  | { kind: 'start'; flow: 'main' | 'catch' }
  | { kind: 'after'; step: string; handle: string }
  | { kind: 'before'; step: string }
  | { kind: 'end' }

export interface ListSlot {
  list: StepDocument[]
  index: number
}

function flow(doc: WorkflowDocument): NonNullable<WorkflowDocument['workflow']> {
  if (!doc.workflow) doc.workflow = {}
  return doc.workflow
}

function mainList(doc: WorkflowDocument): StepDocument[] {
  const f = flow(doc)
  if (!Array.isArray(f.do)) f.do = []
  return f.do
}

function catchList(doc: WorkflowDocument): StepDocument[] {
  const f = flow(doc)
  if (!f.catch || typeof f.catch !== 'object') f.catch = {}
  if (!Array.isArray(f.catch.do)) f.catch.do = []
  return f.catch.do
}

/**
 * The list and index an insert point names, inside `doc` (which is mutated: nested lists are
 * created on the way, a `then` jump on a switch handle becomes a nested branch). Null when the
 * point cannot take a step.
 */
export function resolveInsertPoint(doc: WorkflowDocument, point: InsertPoint): ListSlot | null {
  switch (point.kind) {
    case 'start':
      return { list: point.flow === 'catch' ? catchList(doc) : mainList(doc), index: 0 }
    case 'end': {
      const list = mainList(doc)
      return { list, index: list.length }
    }
    case 'before': {
      const loc = findStepLocation(doc, point.step)
      return loc ? { list: loc.list, index: loc.index } : null
    }
    case 'after': {
      const loc = findStepLocation(doc, point.step)
      if (!loc) return null
      return slotAfter(loc, point.handle)
    }
  }
}

function slotAfter(loc: StepLocation, handle: string): ListSlot | null {
  const body = loc.body
  const type = stepTypeOfBody(body)
  const next = { list: loc.list, index: loc.index + 1 }
  if (handle === 'undo') {
    if (!Array.isArray(body.compensate)) body.compensate = []
    return { list: body.compensate, index: 0 }
  }
  switch (type) {
    case 'switch': {
      if (!Array.isArray(body.switch)) body.switch = []
      if (handle === 'default') {
        let entry = body.switch.find((c) => c.default !== undefined)
        if (!entry) {
          entry = { default: { do: [] } }
          body.switch.push(entry)
        }
        if (typeof entry.default !== 'object' || entry.default === null) entry.default = { do: [] }
        if (!Array.isArray(entry.default.do)) {
          entry.default.do = []
          delete entry.default.then
        }
        return { list: entry.default.do, index: 0 }
      }
      const m = /^case:(\d+)$/.exec(handle)
      if (!m) return next
      const i = Number(m[1])
      const entry = body.switch[i] ?? { case: { when: '' } }
      body.switch[i] = entry
      if (!entry.case) entry.case = { when: '' }
      if (!Array.isArray(entry.case.do)) {
        entry.case.do = []
        delete entry.case.then
      }
      return { list: entry.case.do, index: 0 }
    }
    case 'fork': {
      if (!body.fork || typeof body.fork !== 'object') body.fork = { branches: [] }
      if (!Array.isArray(body.fork.branches)) body.fork.branches = []
      const m = /^branch:(\d+)$/.exec(handle)
      if (!m) return next
      const i = Number(m[1])
      while (body.fork.branches.length <= i) body.fork.branches.push([])
      return { list: body.fork.branches[i], index: 0 }
    }
    case 'for': {
      if (handle === 'loop') {
        if (!body.for || typeof body.for !== 'object') body.for = {}
        if (!Array.isArray(body.for.do)) body.for.do = []
        return { list: body.for.do, index: 0 }
      }
      return next
    }
    case 'try': {
      if (!body.try || typeof body.try !== 'object') body.try = {}
      if (handle === 'catch') {
        if (!body.try.catch || typeof body.try.catch !== 'object') body.try.catch = { as: 'error' }
        if (!Array.isArray(body.try.catch.do)) body.try.catch.do = []
        return { list: body.try.catch.do, index: 0 }
      }
      if (handle === 'try') {
        if (!Array.isArray(body.try.do)) body.try.do = []
        return { list: body.try.do, index: 0 }
      }
      return next
    }
    default:
      return next
  }
}

/** Every step id of the document, nested ones included. */
export function allIds(doc: WorkflowDocument): Set<string> {
  return new Set(walkIds(doc))
}

const RESERVED_IDS = new Set(['end', 'input', 'json', 'vars', 'job', 'env', 'error'])

/** A unique camelCase step id from a base like `vm.get` or `Approve change`. */
export function newStepId(doc: WorkflowDocument, base: string): string {
  const words = base
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  let id = words.map((w, i) => (i === 0 ? w.charAt(0).toLowerCase() + w.slice(1) : w.charAt(0).toUpperCase() + w.slice(1))).join('')
  if (!id || /^[0-9]/.test(id)) id = `step${id}`
  const taken = allIds(doc)
  if (!taken.has(id) && !RESERVED_IDS.has(id)) return id
  let n = 2
  while (taken.has(`${id}${n}`)) n++
  return `${id}${n}`
}

export const STEP_ID = /^[a-z][A-Za-z0-9]*$|^[a-z][a-z0-9]*(-[a-z0-9]+)+$/

/** Why an id is not acceptable, or null. */
export function stepIdProblem(doc: WorkflowDocument, id: string, current: string | null): string | null {
  if (!id) return 'A step needs an id'
  if (!STEP_ID.test(id)) return 'Ids are camelCase or kebab-case, like checkVmExists'
  if (RESERVED_IDS.has(id)) return `'${id}' is reserved`
  if (id !== current && allIds(doc).has(id)) return `'${id}' is already used`
  return null
}

/** The document with a step inserted at a point; the point decides the list. */
export function insertStep(doc: WorkflowDocument, point: InsertPoint, id: string, body: StepBody): WorkflowDocument {
  const next = clone(doc)
  const slot = resolveInsertPoint(next, point)
  if (!slot) return doc
  slot.list.splice(slot.index, 0, { [id]: body })
  return next
}

/** Rewrites `then: <from>` everywhere to `to`. */
function retarget(doc: WorkflowDocument, from: string, to: string): void {
  for (const loc of allStepLocations(doc)) {
    if (!Array.isArray(loc.body.switch)) continue
    for (const c of loc.body.switch) {
      if (c.case && c.case.then === from) c.case.then = to
      if (typeof c.default === 'string' && c.default === from) c.default = to
      else if (c.default && typeof c.default === 'object' && c.default.then === from) c.default.then = to
    }
  }
}

/** The document without the steps (nested steps go with their container); jumps to them move on to the next sibling. */
export function removeSteps(doc: WorkflowDocument, ids: readonly string[]): WorkflowDocument {
  const next = clone(doc)
  const gone = new Set(ids)
  const entries: { list: StepDocument[]; entry: StepDocument; id: string }[] = []
  const successor = new Map<string, string>()
  for (const loc of allStepLocations(next)) {
    if (!gone.has(loc.id)) continue
    entries.push({ list: loc.list, entry: loc.list[loc.index], id: loc.id })
    const after = loc.list[loc.index + 1]
    successor.set(loc.id, after ? Object.keys(after)[0] : 'end')
  }
  for (const e of entries) {
    const i = e.list.indexOf(e.entry)
    if (i >= 0) e.list.splice(i, 1)
  }
  for (const id of gone) {
    let to = successor.get(id) ?? 'end'
    let guard = 0
    while (gone.has(to) && guard++ < 100) to = successor.get(to) ?? 'end'
    retarget(next, id, to)
  }
  return next
}

/** Replaces `$('old')` and `$("old")` references in every string of the document. */
export function renameReferences(doc: WorkflowDocument, from: string, to: string): WorkflowDocument {
  if (!from || from === to) return doc
  const next = clone(doc)
  const pattern = new RegExp(`\\$\\(\\s*(['"])${from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\1\\s*\\)`, 'g')
  const walk = (v: JsonValue | undefined): JsonValue | undefined => {
    if (typeof v === 'string') return v.includes('$(') ? v.replace(pattern, `$('${to}')`) : v
    if (Array.isArray(v)) {
      for (let i = 0; i < v.length; i++) v[i] = walk(v[i]) as JsonValue
      return v
    }
    if (v && typeof v === 'object') {
      for (const k of Object.keys(v)) v[k] = walk(v[k])
      return v
    }
    return v
  }
  walk(next as unknown as JsonValue)
  return next
}

/** The document with a step's id changed: its key, the jumps to it and the `$('id')` references. */
export function renameStep(doc: WorkflowDocument, from: string, to: string): WorkflowDocument {
  if (from === to) return doc
  let next = clone(doc)
  const loc = findStepLocation(next, from)
  if (!loc) return doc
  const entry = loc.list[loc.index]
  const renamed: StepDocument = {}
  for (const k of Object.keys(entry)) renamed[k === from ? to : k] = entry[k]
  loc.list[loc.index] = renamed
  retarget(next, from, to)
  next = renameReferences(next, from, to)
  return next
}

/** The document with one step's body changed in place by `fn`. */
export function updateStep(doc: WorkflowDocument, id: string, fn: (body: StepBody) => void): WorkflowDocument {
  const next = clone(doc)
  const loc = findStepLocation(next, id)
  if (!loc) return doc
  fn(loc.body)
  return next
}

/** The document changed by `fn`. */
export function updateDocument(doc: WorkflowDocument, fn: (d: WorkflowDocument) => void): WorkflowDocument {
  const next = clone(doc)
  fn(next)
  return next
}

export function setStepPositions(doc: WorkflowDocument, positions: ReadonlyMap<string, Position>): WorkflowDocument {
  if (positions.size === 0) return doc
  const next = clone(doc)
  let changed = false
  for (const loc of allStepLocations(next)) {
    const p = positions.get(loc.id)
    if (!p) continue
    loc.body.position = { x: Math.round(p.x), y: Math.round(p.y) }
    changed = true
  }
  return changed ? next : doc
}

/** Renames every nested id of a body that is taken, so a clone can live next to its original. */
function freshIds(body: StepBody, taken: Set<string>, rename: Map<string, string>): void {
  const lists: StepDocument[][] = []
  if (body.fork?.branches) lists.push(...body.fork.branches)
  if (Array.isArray(body.switch))
    for (const c of body.switch) {
      if (c.case?.do) lists.push(c.case.do)
      if (typeof c.default === 'object' && c.default?.do) lists.push(c.default.do)
    }
  if (body.for?.do) lists.push(body.for.do)
  if (body.try?.do) lists.push(body.try.do)
  if (body.try?.catch?.do) lists.push(body.try.catch.do)
  if (Array.isArray(body.compensate)) lists.push(body.compensate)
  for (const list of lists) {
    for (let i = 0; i < list.length; i++) {
      const entry = list[i]
      const id = Object.keys(entry)[0]
      if (id === undefined) continue
      const child = entry[id]
      let fresh = id
      if (taken.has(fresh)) {
        let n = 2
        while (taken.has(`${id}Copy${n > 2 ? n : ''}`)) n++
        fresh = `${id}Copy${n > 2 ? n : ''}`
      }
      taken.add(fresh)
      if (fresh !== id) {
        rename.set(id, fresh)
        list[i] = { [fresh]: child }
      }
      if (child && typeof child === 'object') freshIds(child, taken, rename)
    }
  }
}

/** A copy id: `restart` → `restartCopy`, then `restartCopy2`. */
function copyId(id: string, taken: Set<string>): string {
  let fresh = `${id}Copy`
  let n = 2
  while (taken.has(fresh)) fresh = `${id}Copy${n++}`
  return fresh
}

/** Clones steps (not nested ones) right after their originals, with fresh ids and offset positions. */
export function duplicateSteps(doc: WorkflowDocument, ids: readonly string[], positionOf: (id: string) => Position | undefined): { doc: WorkflowDocument; ids: string[] } {
  const next = clone(doc)
  const taken = allIds(next)
  const wanted = new Set(ids)
  const created: string[] = []
  const locations = allStepLocations(next).filter((l) => wanted.has(l.id) && !l.parents.some((p) => wanted.has(p)))
  for (const loc of locations) {
    const entry = loc.list[loc.index]
    const fresh = copyId(loc.id, taken)
    taken.add(fresh)
    const body = clone(entry[loc.id])
    const rename = new Map<string, string>()
    freshIds(body, taken, rename)
    const p = positionOf(loc.id)
    if (p) body.position = { x: Math.round(p.x + 40), y: Math.round(p.y + 40) }
    const i = loc.list.indexOf(entry)
    loc.list.splice(i + 1, 0, { [fresh]: body })
    created.push(fresh)
  }
  return { doc: next, ids: created }
}

/** Inserts copied entries at a point, renaming ids that are taken; returns the new ids. */
export function pasteSteps(doc: WorkflowDocument, entries: readonly StepDocument[], point: InsertPoint, offset: Position): { doc: WorkflowDocument; ids: string[] } {
  const next = clone(doc)
  const slot = resolveInsertPoint(next, point)
  if (!slot) return { doc, ids: [] }
  const taken = allIds(next)
  const ids: string[] = []
  let index = slot.index
  for (const entry of entries) {
    const id = Object.keys(entry)[0]
    if (id === undefined) continue
    const body = clone(entry[id])
    if (!body || typeof body !== 'object') continue
    const fresh = taken.has(id) ? copyId(id, taken) : id
    taken.add(fresh)
    freshIds(body, taken, new Map())
    if (body.position && typeof body.position === 'object') body.position = { x: Math.round((body.position.x ?? 0) + offset.x), y: Math.round((body.position.y ?? 0) + offset.y) }
    slot.list.splice(index++, 0, { [fresh]: body })
    ids.push(fresh)
  }
  return { doc: next, ids }
}

/** Points a switch output at a later step (or `end`); a nested branch on that output is dropped. */
export function connectSwitch(doc: WorkflowDocument, switchId: string, handle: string, target: string): WorkflowDocument {
  return updateStep(doc, switchId, (body) => {
    if (!Array.isArray(body.switch)) body.switch = []
    if (handle === 'default') {
      const entry = body.switch.find((c) => c.default !== undefined)
      if (!entry) body.switch.push({ default: target })
      else if (typeof entry.default === 'object' && entry.default !== null) {
        entry.default = { ...entry.default, then: target }
        delete entry.default.do
      } else entry.default = target
      return
    }
    const m = /^case:(\d+)$/.exec(handle)
    if (!m) return
    const i = Number(m[1])
    const entry = body.switch[i] ?? { case: { when: '' } }
    body.switch[i] = entry
    if (!entry.case) entry.case = { when: '' }
    entry.case.then = target
    delete entry.case.do
  })
}

/** Adds an empty case (or the default) to a switch. */
export function addSwitchCase(doc: WorkflowDocument, switchId: string, isDefault = false): WorkflowDocument {
  return updateStep(doc, switchId, (body) => {
    if (!Array.isArray(body.switch)) body.switch = []
    if (isDefault) {
      if (!body.switch.some((c) => c.default !== undefined)) body.switch.push({ default: { do: [] } })
    } else body.switch.push({ case: { when: '', do: [] } })
  })
}

export function removeSwitchCase(doc: WorkflowDocument, switchId: string, index: number): WorkflowDocument {
  return updateStep(doc, switchId, (body) => {
    if (Array.isArray(body.switch)) body.switch.splice(index, 1)
  })
}

export function addForkBranch(doc: WorkflowDocument, forkId: string): WorkflowDocument {
  return updateStep(doc, forkId, (body) => {
    if (!body.fork || typeof body.fork !== 'object') body.fork = {}
    if (!Array.isArray(body.fork.branches)) body.fork.branches = []
    body.fork.branches.push([])
  })
}

export function removeForkBranch(doc: WorkflowDocument, forkId: string, index: number): WorkflowDocument {
  return updateStep(doc, forkId, (body) => {
    if (body.fork && Array.isArray(body.fork.branches)) body.fork.branches.splice(index, 1)
  })
}

export function setCompensate(doc: WorkflowDocument, stepId: string, steps: StepDocument[] | null): WorkflowDocument {
  return updateStep(doc, stepId, (body) => {
    if (steps === null) delete body.compensate
    else body.compensate = steps
  })
}

export type TriggerKind = 'alert' | 'event'

export function addTrigger(doc: WorkflowDocument, kind: TriggerKind): WorkflowDocument {
  return updateDocument(doc, (d) => {
    if (!Array.isArray(d.triggers)) d.triggers = []
    d.triggers.push(kind === 'alert' ? { alert: { match: { source: '', labels: {} }, input: {} } } : { event: { topic: '', input: {} } })
  })
}

export function removeTrigger(doc: WorkflowDocument, index: number): WorkflowDocument {
  return updateDocument(doc, (d) => {
    if (!Array.isArray(d.triggers)) return
    d.triggers.splice(index, 1)
    if (d.triggers.length === 0) delete d.triggers
  })
}

export function setTrigger(doc: WorkflowDocument, index: number, spec: JsonObject): WorkflowDocument {
  return updateDocument(doc, (d) => {
    if (!Array.isArray(d.triggers) || !d.triggers[index]) return
    const t = d.triggers[index]
    const key = Object.keys(t).find((k) => k === 'alert' || k === 'event')
    if (key) t[key] = spec
    else d.triggers[index] = { ...t, ...spec }
  })
}

/** Removes the workflow-level catch (the Error trigger and its chain). */
export function removeCatch(doc: WorkflowDocument): WorkflowDocument {
  return updateDocument(doc, (d) => {
    if (d.workflow) delete d.workflow.catch
  })
}

export function ensureCatch(doc: WorkflowDocument): WorkflowDocument {
  return updateDocument(doc, (d) => {
    catchList(d)
  })
}

export interface NewStepParams {
  provider?: string
  action?: string
  name?: string
  tile?: string
  workflow?: string
  version?: string
  resourceType?: string
  playbook?: string | JsonObject
  module?: JsonObject
  /** a terraform step's action: plan (the default), apply or destroy */
  terraformAction?: 'plan' | 'apply' | 'destroy'
  group?: string
}

/** The initial body of a new step of a type; `params` carries the provider, action, tile, workflow or resource type chosen. */
export function bodyFor(type: StepType, params: NewStepParams = {}): StepBody {
  const name = params.name ?? STEP_TYPES[type].title
  switch (type) {
    case 'provider':
      return { name, call: 'provider', with: { provider: params.provider ?? '', action: params.action ?? '', input: {} } }
    case 'http':
      return { name, call: 'http', with: { method: 'GET', url: '' } }
    case 'ansible':
      return { name, call: 'ansible', with: { playbook: params.playbook ?? { git: { repo: '', ref: 'main', path: '' }, commit: '' }, inventory: 'dynamic', extraVars: {} } }
    case 'terraform':
      return { name, call: 'terraform', with: { module: params.module ?? { source: '', version: '' }, action: params.terraformAction ?? 'plan', vars: {} } }
    case 'idp':
      return { name, call: 'idp', with: { tile: params.tile ?? '', input: {} } }
    case 'activity':
      return { name, call: 'activity', with: { taskQueue: '', activity: '', input: {} } }
    case 'run':
      return { name, run: { workflow: params.workflow ?? '', version: params.version ?? '^1', input: {} } }
    case 'approval':
      return { name, approval: { group: params.group ?? '', message: '', timeout: '4h' } }
    case 'switch':
      return { name, switch: [{ case: { when: '', do: [] } }] }
    case 'fork':
      return { name, fork: { branches: [[], []] } }
    case 'for':
      return { name, for: { each: 'item', in: '', do: [] } }
    case 'try':
      return { name, try: { do: [], catch: { as: 'error', do: [] } } }
    case 'wait':
      return { name, wait: { duration: 'PT5M' } }
    case 'set':
      return { name, set: { value: '' } }
    case 'raise':
      return { name, raise: { error: 'StepFailed', message: '' } }
    case 'use':
      return { name, use: { type: params.resourceType ?? '', id: '' } }
    case 'register':
      return { name, register: { type: params.resourceType ?? '', attributes: {} } }
    case 'notify':
      return { name, notify: { channel: '', message: '' } }
    case 'script':
      return { name, script: { language: 'javascript', code: 'return input', input: {} } }
  }
}

/** The step type of a body, else null. */
export { stepTypeOfBody }

/** The entries of a body that are not the step's own block: the common keys. */
export function commonKeysOf(body: StepBody): string[] {
  return Object.keys(body).filter((k) => COMMON_KEYS.includes(k))
}

/** A blank document for a new workflow. */
/**
 * A blank workflow under the names of the New workflow dialog. Its policy lets the owner team run
 * it: every workflow names at least one role or team that may (`policy.missing` otherwise).
 */
export function newDocument(meta: { name: string; title: string; noun: string; verb: string; owner: string; folder: string; persona?: string }): WorkflowDocument {
  const doc: WorkflowDocument = {
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: {
      name: meta.name,
      title: meta.title,
      noun: meta.noun,
      verb: meta.verb,
      owner: meta.owner,
      folder: meta.folder,
      version: '0.1.0',
    },
    api: {
      method: 'POST',
      path: `/api/v1/${meta.noun}s:${meta.verb}`,
      input: { type: 'object', required: [], properties: {} },
    },
    policy: { rbac: [meta.owner] },
    workflow: { do: [] },
  }
  if (meta.persona && doc.metadata) doc.metadata.persona = meta.persona
  return doc
}

/** The document with a policy that lets `owner` run it when it names no role or team; as it was otherwise. */
export function withOwnerPolicy(doc: WorkflowDocument, owner: string): WorkflowDocument {
  const rbac = doc.policy?.rbac
  if ((Array.isArray(rbac) && rbac.length > 0) || !owner.trim()) return doc
  return { ...doc, policy: { ...(doc.policy ?? {}), rbac: [owner.trim()] } }
}
