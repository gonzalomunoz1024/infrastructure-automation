/**
 * What the node creator offers, built from the registry: the categories n8n's creator shows
 * ("What happens next?"), each a list of items that either add a step (or a trigger) or open a
 * deeper list, plus a fuzzy search over every leaf. Product names come from the registry only.
 */
import { fuzzyMatch, type Tone } from '@iaf/ui'
import { STEP_TYPES, type JsonObject, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import type { RegistryAction, RegistryProvider } from '../../lib/types'
import type { NewStepParams, TriggerKind } from '../model/edits'
import { providerLabel, type Registry } from '../model/registry'
import { providerGlyph, triggerGlyph, typeGlyph, type Glyph } from '../canvas/glyphs'

export type CreatorAction = { kind: 'step'; type: StepType; params?: NewStepParams } | { kind: 'trigger'; trigger: TriggerKind }

export interface CreatorTag {
  label: string
  tone: Tone
}

export interface CreatorItem {
  /** unique across the catalog */
  id: string
  title: string
  description?: string
  glyph: Glyph
  tags?: CreatorTag[]
  /** extra words the search matches */
  keywords?: string
  /** a leaf adds something */
  action?: CreatorAction
  /** a branch opens another view */
  view?: string
  /** why it cannot be added */
  disabled?: string
}

export interface CreatorSection {
  title?: string
  items: CreatorItem[]
}

export interface CreatorView {
  id: string
  title: string
  subtitle?: string
  sections: CreatorSection[]
}

export type Catalog = Map<string, CreatorView>

export const ROOT_VIEW = 'root'

const EFFECT_TONE: Record<string, Tone> = { read: 'neutral', create: 'healthy', change: 'warning', delete: 'critical', none: 'neutral' }

export function effectTags(a: Pick<RegistryAction, 'effect' | 'approval'>): CreatorTag[] {
  const tags: CreatorTag[] = []
  if (a.effect && a.effect !== 'unknown') tags.push({ label: a.effect, tone: EFFECT_TONE[a.effect] ?? 'neutral' })
  if (a.approval === 'required' || a.approval === true) tags.push({ label: 'approval', tone: 'warning' })
  return tags
}

/** A provider (or a playbook of one) the workflow has not published yet, tagged as such: one of its drafts ("draft"), or one in review. */
export function unpublishedTags(p: Pick<RegistryProvider, 'unpublished'>): CreatorTag[] {
  return p.unpublished ? [{ label: p.unpublished, tone: 'accent' }] : []
}

function stepItem(type: StepType, extra: Partial<CreatorItem> = {}, params?: NewStepParams): CreatorItem {
  const info = STEP_TYPES[type]
  return { id: `type:${type}`, title: info.title, description: info.description, glyph: typeGlyph(type), action: { kind: 'step', type, params }, keywords: `${type} ${info.category}`, ...extra }
}

function branch(id: string, view: string, title: string, description: string, glyph: Glyph): CreatorItem {
  return { id, title, description, glyph, view }
}

function providersOf(registry: Registry | null, types: readonly string[]): RegistryProvider[] {
  return (registry?.providers ?? []).filter((p) => types.includes(p.type)).sort((a, b) => a.title.localeCompare(b.title))
}

const CALL_PROVIDER_TYPES = ['openapi', 'http', 'sdk']

/** The views of the creator for a registry and a document. */
export function buildCatalog(registry: Registry | null, doc: WorkflowDocument): Catalog {
  const views: Catalog = new Map()
  const add = (v: CreatorView) => views.set(v.id, v)

  // providers and their actions
  const callProviders = providersOf(registry, CALL_PROVIDER_TYPES)
  const workflows = (registry?.workflows ?? []).filter((w) => w.name !== doc.metadata?.name).sort((a, b) => a.title.localeCompare(b.title))
  const runItems: CreatorItem[] = workflows.map((w) => ({
    id: `run:${w.name}`,
    title: w.title,
    description: `${w.name} ${w.version}`,
    glyph: typeGlyph('run'),
    keywords: `run workflow child ${w.name}`,
    action: { kind: 'step', type: 'run', params: { workflow: w.name, version: `^${w.version.split('.')[0] || '1'}`, name: `Run ${w.title}` } },
  }))
  add({
    id: 'providers',
    title: 'Providers',
    subtitle: 'Call an action of a registered provider',
    sections: [
      {
        items:
          callProviders.length > 0
            ? callProviders.map((p) => ({ id: `provider:${p.name}`, title: p.title, description: `${p.actions.length} ${p.actions.length === 1 ? 'action' : 'actions'}${p.owner ? ` · ${p.owner}` : ''}`, glyph: providerGlyph(p.type), tags: unpublishedTags(p), view: `provider:${p.name}`, keywords: p.name }))
            : [stepItem('provider', { description: 'No provider is registered yet; pick the provider in the step' })],
      },
      ...(runItems.length > 0 ? [{ title: 'Run workflow', items: runItems }] : []),
    ],
  })
  for (const p of callProviders) {
    add({
      id: `provider:${p.name}`,
      title: providerLabel(p),
      subtitle: p.name,
      sections: [
        {
          title: 'Actions',
          items: p.actions.map((a) => ({
            id: `action:${p.name}:${a.id}`,
            title: a.title || a.id,
            description: `${p.title} · ${a.id}`,
            glyph: providerGlyph(p.type),
            tags: [...effectTags(a), ...unpublishedTags(p)],
            keywords: `${p.name} ${a.id} ${a.effect}`,
            action: { kind: 'step', type: 'provider', params: { provider: p.name, action: a.id, name: a.title || a.id } },
          })),
        },
      ],
    })
  }

  // flow
  add({
    id: 'flow',
    title: 'Flow',
    subtitle: 'Branch, loop, wait, run another workflow',
    sections: [
      {
        items: [
          stepItem('switch', { keywords: 'if branch condition route' }),
          stepItem('fork', { keywords: 'parallel branches merge join' }),
          stepItem('for', { keywords: 'loop each iterate items' }),
          stepItem('try', { keywords: 'error handling catch' }),
          stepItem('wait', { keywords: 'delay sleep pause until' }),
          stepItem('raise', { keywords: 'fail stop error' }),
          runItems.length > 0 ? branch('flow:run', 'workflows', 'Run workflow', STEP_TYPES.run.description, typeGlyph('run')) : stepItem('run'),
        ],
      },
    ],
  })
  if (runItems.length > 0) add({ id: 'workflows', title: 'Run workflow', subtitle: 'Published workflows, run as a child job', sections: [{ items: runItems }] })

  // people
  add({
    id: 'human',
    title: 'Human',
    subtitle: 'Ask for a sign-off, tell someone',
    sections: [{ items: [stepItem('approval', { keywords: 'approve sign off human in the loop' }), stepItem('notify', { keywords: 'message slack chat channel email' })] }],
  })

  // resources
  const types = registry?.resourceTypes ?? []
  add({
    id: 'resources',
    title: 'Resources',
    subtitle: 'Use what the registry knows, record what the job made',
    sections:
      types.length > 0
        ? [
            { title: 'Use', items: types.map((t) => stepItem('use', { id: `use:${t.name}`, title: `Use ${t.name}`, description: `Resolve a ${t.name} and check access to it`, keywords: `use resource ${t.name} ${t.plural}` }, { resourceType: t.name, name: `Use ${t.name}` })) },
            { title: 'Register', items: types.map((t) => stepItem('register', { id: `register:${t.name}`, title: `Register ${t.name}`, description: `Record a ${t.name} the job created`, keywords: `register resource ${t.name} ${t.plural}` }, { resourceType: t.name, name: `Register ${t.name}` })) },
          ]
        : [{ items: [stepItem('use'), stepItem('register')] }],
  })

  // code
  add({
    id: 'code',
    title: 'Code',
    subtitle: 'HTTP requests, scripts, variables, team activities',
    sections: [{ items: [stepItem('http', { keywords: 'api rest url request' }), stepItem('script', { keywords: 'javascript code function transform' }), stepItem('set', { keywords: 'variables vars edit fields' }), stepItem('activity', { keywords: 'temporal worker task queue' })] }],
  })

  // ansible
  const playbooks = registry?.playbooks ?? []
  add({
    id: 'ansible',
    title: 'Ansible',
    subtitle: 'Run a playbook against an inventory',
    sections: [
      ...(playbooks.length > 0
        ? [
            {
              title: 'Library',
              items: playbooks.map((pb) => ({
                id: `playbook:${pb.name}`,
                title: pb.name,
                description: [pb.path, pb.version, pb.owner].filter(Boolean).join(' · '),
                glyph: typeGlyph('ansible'),
                tags: unpublishedTags(pb),
                keywords: `ansible playbook library ${pb.path}`,
                action: { kind: 'step' as const, type: 'ansible' as const, params: { playbook: pb.name, name: pb.name.split('/').pop() ?? pb.name } },
              })),
            },
          ]
        : []),
      {
        title: 'Other sources',
        items: [
          stepItem('ansible', { id: 'ansible:git', title: 'Playbook from git', description: 'A repository, a ref and a path, pinned to a commit', keywords: 'ansible git repository' }, { playbook: { git: { repo: '', ref: 'main', path: '' }, commit: '' }, name: 'Ansible playbook' }),
          stepItem('ansible', { id: 'ansible:upload', title: 'Upload a playbook', description: 'A file you upload, pinned by its sha256', keywords: 'ansible upload file' }, { playbook: { upload: { file: '', sha256: '' } }, name: 'Ansible playbook' }),
        ],
      },
    ],
  })

  // terraform: a provider's actions run its own module (spec.module), so the step passes the terraform.module check as inserted
  const tf = providersOf(registry, ['terraform'])
  add({
    id: 'terraform',
    title: 'Terraform',
    subtitle: 'Plan, apply or destroy a pinned module',
    sections: [
      ...tf.map((p) => ({
        title: providerLabel(p),
        items: p.actions.map((a): CreatorItem => {
          const terraformAction = a.id === 'apply' || a.id === 'destroy' ? a.id : 'plan'
          const module: JsonObject = p.module ? { ...p.module } : { source: '', version: '' }
          return {
            id: `terraform:${p.name}:${a.id}`,
            title: a.title || a.id,
            description: p.module ? `${p.title} · ${a.id} · ${[p.module.source, p.module.version].filter(Boolean).join(' @ ')}` : `${p.title} · ${a.id}`,
            glyph: providerGlyph('terraform'),
            tags: [...effectTags(a), ...unpublishedTags(p)],
            keywords: `terraform module ${p.name} ${a.id}`,
            action: { kind: 'step', type: 'terraform', params: { name: `${p.title}: ${a.title || a.id}`, module, terraformAction } },
          }
        }),
      })),
      { title: tf.length > 0 ? 'Any module' : undefined, items: [stepItem('terraform', { id: 'terraform:any', title: 'Terraform module', keywords: 'terraform module plan apply' })] },
    ],
  })

  // IDP tiles
  const idp = providersOf(registry, ['idp'])
  add({
    id: 'idp',
    title: 'IDP tiles',
    subtitle: 'Request through a tile of the developer portal',
    sections:
      idp.length > 0
        ? idp.map((p) => ({
            title: providerLabel(p),
            items: p.actions.map((a) => ({
              id: `tile:${p.name}:${a.id}`,
              title: a.title || a.id,
              description: `${p.title} · ${a.id}`,
              glyph: providerGlyph('idp'),
              tags: [...effectTags(a), ...unpublishedTags(p)],
              keywords: `idp tile portal ${a.id}`,
              action: { kind: 'step' as const, type: 'idp' as const, params: { tile: a.id, name: a.title || a.id } },
            })),
          }))
        : [{ items: [stepItem('idp')] }],
  })

  // triggers
  add({
    id: 'triggers',
    title: 'Add another trigger',
    subtitle: 'Start this workflow from an alert or an event too',
    sections: [
      {
        items: [
          { id: 'trigger:api', title: 'API trigger', description: 'Every workflow has one: its endpoint and its MCP tool', glyph: triggerGlyph('api'), disabled: 'Already there', keywords: 'api endpoint rest mcp webhook' },
          { id: 'trigger:alert', title: 'Alert trigger', description: 'Start a job when a matching alert fires', glyph: triggerGlyph('alert'), action: { kind: 'trigger', trigger: 'alert' }, keywords: 'alert alertmanager grafana monitoring' },
          { id: 'trigger:event', title: 'Event trigger', description: 'Start a job when an event arrives on a topic', glyph: triggerGlyph('event'), action: { kind: 'trigger', trigger: 'event' }, keywords: 'event topic kafka message' },
        ],
      },
    ],
  })

  add({
    id: ROOT_VIEW,
    title: 'What happens next?',
    sections: [
      {
        items: [
          branch('root:providers', 'providers', 'Providers', 'Call an action of a registered provider, or another workflow', { kind: 'provider', tone: 'accent' }),
          branch('root:flow', 'flow', 'Flow', 'Branch, loop, wait, run another workflow', { kind: 'switch', tone: 'warning' }),
          branch('root:human', 'human', 'Human', 'Ask for an approval, notify a channel', { kind: 'approval', tone: 'healthy' }),
          branch('root:resources', 'resources', 'Resources', 'Use or register resources of the registry', { kind: 'resource', tone: 'accent' }),
          branch('root:code', 'code', 'Code', 'HTTP requests, scripts, variables', { kind: 'script', tone: 'neutral' }),
          branch('root:ansible', 'ansible', 'Ansible', 'Run a playbook from the library, git or an upload', { kind: 'ansible', tone: 'critical' }),
          branch('root:terraform', 'terraform', 'Terraform', 'Plan, apply or destroy a module', { kind: 'terraform', tone: 'accent' }),
          branch('root:idp', 'idp', 'IDP tiles', 'Request through the developer portal', { kind: 'grid', tone: 'healthy' }),
        ],
      },
      { items: [branch('root:triggers', 'triggers', 'Add another trigger', 'Start this workflow from an alert or an event', { kind: 'bolt', tone: 'warning' })] },
    ],
  })
  return views
}

/** Every leaf of the catalog once, with the title of the view it lives in. */
export function leavesOf(catalog: Catalog): { item: CreatorItem; group: string }[] {
  const seen = new Set<string>()
  const out: { item: CreatorItem; group: string }[] = []
  for (const view of catalog.values()) {
    if (view.id === ROOT_VIEW) continue
    for (const section of view.sections) {
      for (const item of section.items) {
        if (!item.action || seen.has(item.id)) continue
        seen.add(item.id)
        out.push({ item, group: view.title })
      }
    }
  }
  return out
}

/** The search results: the best leaves, grouped by where they live, best group first. */
export function searchCatalog(catalog: Catalog, query: string, limit = 60): CreatorSection[] {
  const q = query.trim()
  if (!q) return []
  const scored: { item: CreatorItem; group: string; score: number }[] = []
  for (const { item, group } of leavesOf(catalog)) {
    const title = fuzzyMatch(q, item.title)
    const rest = fuzzyMatch(q, `${item.title} ${item.description ?? ''} ${item.keywords ?? ''} ${group}`)
    const best = title ? title.score + 5 : rest ? rest.score : null
    if (best === null) continue
    scored.push({ item, group, score: best })
  }
  scored.sort((a, b) => b.score - a.score || a.item.title.localeCompare(b.item.title))
  const groups = new Map<string, CreatorItem[]>()
  for (const s of scored.slice(0, limit)) {
    const list = groups.get(s.group) ?? []
    list.push(s.item)
    groups.set(s.group, list)
  }
  return [...groups.entries()].map(([title, items]) => ({ title, items }))
}
