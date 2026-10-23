import { pathFor } from '../app/router'
import { singularOf, type ConsoleAction, type ConsoleService, type ConsoleType } from './descriptors'
import { verbsOf } from './verbs'

/*
 * A getting-started tour per service (WP 3.7), generated from what the service's providers
 * publish: which type to create first (one whose create workflow needs no other resource), what
 * each create asks for, what can be done to a resource afterwards, and where to follow the
 * requests. Nothing here is written by hand for a service, so a new provider gets a tour too.
 */

export type TourKind = 'create' | 'operate' | 'follow' | 'access'

export interface TourStep {
  id: string
  kind: TourKind
  title: string
  detail: string
  href?: string
  type?: ConsoleType
}

/** The resource types a create workflow's input names through `x-resource`. */
export function dependenciesOf(action: ConsoleAction | null): string[] {
  if (!action) return []
  const out = new Set<string>()
  for (const prop of Object.values(action.input.properties ?? {})) {
    const t = prop?.['x-resource']?.type
    if (t) out.add(t)
  }
  return [...out]
}

/** Types in the order to create them: what a type's create needs comes before it; ties keep the alphabetical order. */
export function orderTypes(types: ConsoleType[]): ConsoleType[] {
  const byName = new Map(types.map((t) => [t.name, t]))
  const placed = new Set<string>()
  const out: ConsoleType[] = []
  const visit = (t: ConsoleType, trail: Set<string>) => {
    if (placed.has(t.name) || trail.has(t.name)) return
    trail.add(t.name)
    for (const dep of dependenciesOf(t.create)) {
      const d = byName.get(dep)
      if (d && d !== t) visit(d, trail)
    }
    trail.delete(t.name)
    if (!placed.has(t.name)) {
      placed.add(t.name)
      out.push(t)
    }
  }
  for (const t of [...types].sort((a, b) => a.title.localeCompare(b.title))) visit(t, new Set())
  return out
}

function list(words: string[]): string {
  if (words.length <= 1) return words[0] ?? ''
  if (words.length === 2) return `${words[0]} and ${words[1]}`
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}

/** A field's title inside a sentence: "Size" reads "size", but "VM name" keeps its acronym. */
function fieldTitle(action: ConsoleAction, name: string): string {
  const title = action.input.properties?.[name]?.title ?? name
  const first = title.split(/\s+/)[0] ?? ''
  return first.length > 1 && first === first.toUpperCase() ? title : title.charAt(0).toLowerCase() + title.slice(1)
}

/** The tour of one service: create steps in dependency order, how to operate, where to follow up. */
export function tourFor(service: ConsoleService, allTypes: ConsoleType[]): TourStep[] {
  const steps: TourStep[] = []
  const byName = new Map(allTypes.map((t) => [t.name, t]))
  for (const t of orderTypes(service.types)) {
    const noun = singularOf(t.title).toLowerCase()
    const article = /^[aeiou]/i.test(noun) ? 'an' : 'a'
    if (t.create) {
      const required = (t.create.input.required ?? []).map((n) => fieldTitle(t.create!, n))
      const deps = dependenciesOf(t.create).map((d) => {
        const dt = byName.get(d)
        if (!dt) return `${/^[aeiou]/i.test(d) ? 'an' : 'a'} ${d}`
        const other = dt.service !== service.id ? ` from ${dt.service.charAt(0).toUpperCase()}${dt.service.slice(1)}` : ''
        return `${/^[aeiou]/i.test(dt.title) ? 'an' : 'a'} ${singularOf(dt.title).toLowerCase()}${other}`
      })
      const parts = [`The ${t.create.title} workflow asks for ${required.length ? list(required) : 'nothing but a name'}`]
      if (deps.length) parts.push(`it needs ${list(deps)} first`)
      steps.push({ id: `create-${t.name}`, kind: 'create', title: `Create ${article} ${noun}`, detail: `${parts.join('; ')}.`, href: pathFor('create', { service: t.service, type: t.name }), type: t })
    } else {
      steps.push({ id: `browse-${t.name}`, kind: 'create', title: `Browse ${t.title.toLowerCase()}`, detail: `No workflow creates ${t.title.toLowerCase()} from the console yet; ${t.owner} registers them.`, href: pathFor('type', { service: t.service, type: t.name }), type: t })
    }
    const verbs = verbsOf(t)
    if (verbs.length) {
      const safe = verbs.filter((a) => !a.destructive).map((a) => a.title)
      const destructive = verbs.filter((a) => a.destructive).map((a) => a.title)
      const parts = [`From ${article} ${noun}'s page, the Actions menu offers ${list(verbs.map((a) => a.title))}`]
      if (destructive.length) parts.push(`${list(destructive)} ${destructive.length === 1 ? 'asks' : 'ask'} you to type the name and ${destructive.length === 1 ? 'waits' : 'wait'} for an approval`)
      else if (safe.length) parts.push('each shows what it will touch before it runs')
      steps.push({ id: `operate-${t.name}`, kind: 'operate', title: `Operate ${t.title.toLowerCase()}`, detail: `${parts.join('; ')}.`, href: pathFor('type', { service: t.service, type: t.name }), type: t })
    }
  }
  steps.push({ id: 'follow', kind: 'follow', title: 'Follow your requests', detail: 'Every create and change is a request with steps you can watch, cancel while it runs, and read back later.', href: pathFor('requests') })
  steps.push({ id: 'access', kind: 'access', title: 'See who can do what', detail: 'Identity and access lists the roles in this account; a locked action tells you which statement decided and lets you ask for access.', href: pathFor('iam') })
  return steps
}
