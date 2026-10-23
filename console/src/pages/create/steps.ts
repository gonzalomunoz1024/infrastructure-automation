import { fieldRoot, groupFields, xConsoleOf, type Problem, type Schema, type XConsole } from '@iaf/console-kit'

/*
 * The wizard's steps, read off the create workflow's input schema: the ungrouped fields make
 * the first step, every `x-console.group` its own step in schema order, then (when asked) tags,
 * and the review. A type whose author added groups gets a longer wizard with no console change.
 */

export type WizardStepKind = 'fields' | 'tags' | 'review'

/** The hints a create's field takes from the type's field of the same name (P-06): its form section, its help link, its masking. */
const SHARED_HINTS = ['group', 'help', 'sensitive'] as const

/**
 * The create's input with the type's own hints where the input has none: a provider groups its
 * type's fields once (`x-console.group`), and every create of the type is laid out the same way.
 * A hint on the input itself always wins.
 */
export function withTypeHints(input: Schema, typeSchema: Schema | undefined): Schema {
  const theirs = typeSchema?.properties ?? {}
  let changed = false
  const properties = Object.fromEntries(
    Object.entries(input.properties ?? {}).map(([name, prop]) => {
      const hint = xConsoleOf(theirs[name])
      const mine = xConsoleOf(prop)
      const add: XConsole = {}
      for (const k of SHARED_HINTS) if (mine[k] === undefined && hint[k] !== undefined) add[k] = hint[k] as never
      if (Object.keys(add).length === 0) return [name, prop]
      changed = true
      const own = prop['x-console'] && typeof prop['x-console'] === 'object' ? prop['x-console'] : {}
      return [name, { ...prop, 'x-console': { ...own, ...add } }]
    }),
  )
  return changed ? { ...input, properties } : input
}

export interface WizardStep {
  id: string
  label: string
  description?: string
  kind: WizardStepKind
  /** the schema properties the step edits */
  fields: string[]
  optional?: boolean
}

export function slugOf(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'step'
}

export interface StepsOptions {
  /** a Tags step before the review */
  tags?: boolean
  /** how many tags the account requires: the Tags step is then not optional, and says how many */
  requiredTags?: number
}

/** Whether a problem sits on the create's tags (`/tags/<key>`, as the tag policy names them), not on its input. */
export function isTagProblem(p: Problem): boolean {
  return /^\/tags(\/|$)/.test(p.field ?? '')
}

export function stepsFor(schema: Schema | undefined, noun: string, opts: StepsOptions = {}): WizardStep[] {
  const grouped = groupFields(schema)
  const ungrouped = grouped.filter((g) => g.group === null).flatMap((g) => g.names)
  const steps: WizardStep[] = []
  if (ungrouped.length) steps.push({ id: 'details', label: `${noun.charAt(0).toUpperCase()}${noun.slice(1)} details`, kind: 'fields', fields: ungrouped })
  for (const g of grouped) if (g.group !== null) steps.push({ id: slugOf(g.group), label: g.group, kind: 'fields', fields: g.names })
  if (steps.length === 0) steps.push({ id: 'details', label: `${noun.charAt(0).toUpperCase()}${noun.slice(1)} details`, kind: 'fields', fields: [] })
  const required = opts.requiredTags ?? 0
  if (opts.tags) steps.push(required > 0 ? { id: 'tags', label: 'Tags', description: `${required} required`, kind: 'tags', fields: [] } : { id: 'tags', label: 'Tags', description: 'Optional', kind: 'tags', fields: [], optional: true })
  steps.push({ id: 'review', label: 'Review and create', kind: 'review', fields: [] })
  return steps
}

/** The schema of one step: its properties, and the required ones among them. */
export function stepSchema(schema: Schema | undefined, step: WizardStep): Schema {
  const props = schema?.properties ?? {}
  return { type: 'object', properties: Object.fromEntries(step.fields.filter((f) => f in props).map((f) => [f, props[f]])), required: (schema?.required ?? []).filter((r) => step.fields.includes(r)) }
}

/** The index of the first step that edits a field a problem names (the Tags step for `/tags/<key>`); -1 when none does. */
export function stepOfProblems(steps: WizardStep[], problems: Problem[]): number {
  for (const p of problems) {
    if (isTagProblem(p)) {
      const i = steps.findIndex((s) => s.kind === 'tags')
      if (i >= 0) return i
      continue
    }
    const root = fieldRoot(p.field)
    if (!root) continue
    const i = steps.findIndex((s) => s.fields.includes(root))
    if (i >= 0) return i
  }
  return -1
}
