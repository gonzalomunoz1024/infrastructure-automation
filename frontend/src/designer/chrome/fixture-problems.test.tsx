/**
 * The conformance fixtures of @iaf/workflow-checks as the designer shows them: every problem of
 * packages/workflow-checks/fixtures/expected.json renders in the Issues list with its message,
 * and every fix it offers (a did-you-mean, an approval before a destructive step, the removal
 * of a step that never runs, a connection) applies and makes the problem go away.
 */
import { parse as parseYaml } from 'yaml'
import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  buildContext,
  check,
  checkYaml,
  contextSchemas,
  parseDocument,
  parseProvider,
  parseResourceType,
  resourceTypeName,
  type CheckContext,
  type ParsedWorkflow,
  type Problem,
  type ProviderDocument,
  type ResourceTypeDocument,
  type WorkflowDocument,
} from '@iaf/workflow-checks'
import { IssuesList, groupsOf } from './IssuesDrawer'
import { applyFix, fixOf } from '../model/fixes'
import { documentToGraph, type Graph } from '../model/graph'
import { DesignerStore } from '../model/store'

const FIXTURES = import.meta.glob('../../../../packages/workflow-checks/fixtures/*.yaml', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const EXPECTED = import.meta.glob('../../../../packages/workflow-checks/fixtures/expected.json', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const CONTENT = import.meta.glob(['../../../../providers/*/*.yaml', '../../../../workflows/*/*.yaml', '../../../../resource-types/*.yaml'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>

const expected = JSON.parse(Object.values(EXPECTED)[0] ?? '{}') as Record<string, string[]>

function fixture(name: string): string {
  const key = Object.keys(FIXTURES).find((k) => k.endsWith(`/${name}`))
  if (!key) throw new Error(`no fixture ${name}`)
  return FIXTURES[key]
}

/** The repository's own content as the catalog, the way the package's own fixture test builds it. */
function repoContext(): CheckContext {
  const providers: ProviderDocument[] = []
  const workflows: ParsedWorkflow[] = []
  const types: ResourceTypeDocument[] = []
  for (const text of Object.values(CONTENT)) {
    const doc = parseYaml(text, { uniqueKeys: false }) as { kind?: string } | null
    switch (doc?.kind) {
      case 'Provider':
        providers.push(parseProvider(doc))
        break
      case 'Workflow':
        workflows.push(parseDocument(doc))
        break
      case 'ResourceType':
        types.push(parseResourceType(doc))
        break
      default:
        break
    }
  }
  return buildContext(
    providers,
    workflows.map((w) => w.metadata.name),
    types.map((t) => resourceTypeName(t)),
    contextSchemas(workflows, types),
  )
}

const ctx = repoContext()
const EMPTY_DOC: WorkflowDocument = { kind: 'Workflow', metadata: { name: 'x' }, workflow: { do: [] } }

function docOf(yaml: string): WorkflowDocument | null {
  try {
    const doc = parseYaml(yaml, { uniqueKeys: false }) as unknown
    return doc && typeof doc === 'object' ? (doc as WorkflowDocument) : null
  } catch {
    return null
  }
}

function graphOf(doc: WorkflowDocument | null): Graph {
  try {
    return documentToGraph(doc ?? EMPTY_DOC)
  } catch {
    return documentToGraph(EMPTY_DOC)
  }
}

/** What React writes for a text: the characters HTML escapes. */
const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;')
const key = (p: Problem) => `${p.code} @${p.step ?? ''} ${p.field ?? ''}: ${p.message}`
const nameOf = (id: string) => id
const noop = () => {}

describe('the conformance fixtures in the designer', () => {
  const rendered = new Set<string>()
  const files = Object.entries(expected)
  it('reads the fixtures', () => {
    expect(files.length).toBeGreaterThan(0)
    expect(ctx.knowsCatalog).toBe(true)
  })

  for (const [file, codes] of files) {
    it(`${file}: lists every problem with its message, and its fix applies`, () => {
      const yaml = fixture(file)
      const problems = checkYaml(yaml, ctx)
      expect(problems.map((p) => `${p.level} ${p.code}`).sort(), file).toEqual([...codes].sort())
      for (const p of problems) rendered.add(p.code)

      const doc = docOf(yaml)
      const html = renderToString(<IssuesList groups={groupsOf(problems, nameOf)} graph={graphOf(doc)} providers={[]} nameOf={nameOf} onOpen={noop} onFix={noop} />)
      for (const p of problems) {
        expect(html, `${file}: ${p.code}`).toContain(esc(p.message))
        expect(html, `${file}: ${p.code}`).toContain(`data-code="${p.code}"`)
        const fix = fixOf(p)
        if (fix) expect(html, `${file}: the fix of ${p.code}`).toContain(esc(fix.label))
      }

      if (!doc) return
      for (const p of problems) {
        const fix = fixOf(p)
        if (!fix) continue
        const label = `${file}: ${p.code} "${fix.label}"`
        if (fix.kind === 'connect') {
          const store = new DesignerStore({ name: null, doc, registry: null })
          expect(store.applyFix(p), label).toBe(true)
          expect(store.getState().creator?.point, label).toEqual({ kind: 'before', step: p.step })
          continue
        }
        let next: WorkflowDocument | null
        if (fix.kind === 'remove-step') {
          const store = new DesignerStore({ name: null, doc, registry: null })
          expect(store.applyFix(p), label).toBe(true)
          next = store.getState().doc
        } else {
          next = applyFix(doc, p)
        }
        expect(next, label).not.toBeNull()
        expect(check(next, ctx).map(key), `${label} still reported after the fix`).not.toContain(key(p))
      }
    })
  }

  it('shows every code of expected.json', () => {
    const all = new Set(Object.values(expected).flat().map((c) => c.split(' ')[1]))
    expect([...all].filter((c) => !rendered.has(c))).toEqual([])
  })
})

describe('the fixes, in detail', () => {
  const problemsOf = (file: string) => ({ doc: docOf(fixture(file)) as WorkflowDocument, problems: checkYaml(fixture(file), ctx) })

  it('a did-you-mean for a field replaces that name inside the expression, not the whole message', () => {
    const { doc, problems } = problemsOf('unknown-field.yaml')
    const [nmae, template] = problems
    const message = (d: WorkflowDocument | null) => d?.workflow?.do?.[2]?.tell?.notify?.message
    const first = applyFix(doc, nmae)
    expect(message(first)).toBe('{{ $vm.name }} runs {{ $json.attributesTemplateId }} ({{ $vm.powerState }}, {{ $json.attributes.os }})')
    const second = applyFix(first as WorkflowDocument, template)
    expect(message(second)).toBe('{{ $vm.name }} runs {{ $json.attributes.templateId }} ({{ $vm.powerState }}, {{ $json.attributes.os }})')
    expect(check(second, ctx)).toEqual([])
  })

  it('a did-you-mean for a step reference keeps the rest of the expression', () => {
    const { doc, problems } = problemsOf('unknown-step-reference.yaml')
    const unknown = problems.find((p) => p.code === 'expr.unknownStep') as Problem
    expect(unknown.fix?.value).toBe('Check VM')
    const next = applyFix(doc, unknown)
    expect(next?.workflow?.do?.[1]?.tell?.notify?.message).toBe("{{ $('Check VM').json.name }} and {{ $('Later').json.x }}")
  })

  it('a did-you-mean for an input key renames the key and keeps its value', () => {
    const doc: WorkflowDocument = { ...EMPTY_DOC, workflow: { do: [{ getVm: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { di: '{{ $input.vmId }}' } } } }] } }
    const problems = check(doc, ctx)
    const unknown = problems.find((p) => p.code === 'step.unknownInput') as Problem
    expect(unknown.fix?.value).toBe('id')
    const next = applyFix(doc, unknown)
    expect(next?.workflow?.do?.[0]?.getVm?.with?.input).toEqual({ id: '{{ $input.vmId }}' })
    expect(check(next, ctx).filter((p) => p.code === 'step.unknownInput' || p.code === 'step.missingInput')).toEqual([])
  })

  it('a misspelt input of a run step goes away when the right one is already there', () => {
    const { doc, problems } = problemsOf('run-contract.yaml')
    const unknown = problems.find((p) => p.code === 'contract.unknownInput') as Problem
    const next = applyFix(doc, unknown)
    expect(next?.workflow?.do?.[0]?.first?.run?.input).toEqual({ image: 'img-1', size: 'huge', environment: 3 })
  })

  it('a did-you-mean for an input field or a variable keeps the root of the reference', () => {
    const { doc, problems } = problemsOf('unknown-input-and-var.yaml')
    const input = problems.find((p) => p.code === 'expr.unknownInput') as Problem
    expect(input.fix?.value).toBe('vmId')
    expect(applyFix(doc, input)?.workflow?.do?.[0]?.getVm?.with?.input).toEqual({ id: '{{ $input.vmId }}' })
    const vars: Problem = { level: 'warning', code: 'expr.unknownVar', step: 'tell', field: 'message', message: 'No earlier set step defines $vars.nope', fix: { label: 'Did you mean note?', kind: 'replace', value: 'note' } }
    expect(applyFix(doc, vars)?.workflow?.do?.[1]?.tell?.notify?.message).toBe('{{ $vars.note }} {{ $error.message }}')
  })

  it('an unknown alias is replaced wherever it is referenced in the field', () => {
    const doc: WorkflowDocument = {
      ...EMPTY_DOC,
      workflow: {
        do: [{ getVm: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '1' } }, output: { as: 'vm' } } }, { tell: { notify: { channel: '#ops', message: '{{ $vmm.name }} ({{ $vmm.powerState }})' } } }],
      },
    }
    const problems = check(doc, ctx).filter((p) => p.code === 'expr.unknownReference')
    expect(problems).toHaveLength(2)
    const next = applyFix(doc, problems[0]) as WorkflowDocument
    expect(next.workflow?.do?.[1]?.tell?.notify?.message).toBe('{{ $vm.name }} ({{ $vmm.powerState }})')
    const again = applyFix(next, check(next, ctx).find((p) => p.code === 'expr.unknownReference') as Problem)
    expect(again?.workflow?.do?.[1]?.tell?.notify?.message).toBe('{{ $vm.name }} ({{ $vm.powerState }})')
  })

  it('an unknown type or producer of an api input fixes its x-resource', () => {
    const doc: WorkflowDocument = {
      ...EMPTY_DOC,
      metadata: { name: 'vm-make', title: 'Make', noun: 'vm', verb: 'make', owner: 'team', version: '1.0.0' },
      api: { input: { type: 'object', properties: { image: { type: 'string', 'x-resource': { type: 'imge', orCreateWith: 'build-images' } } } } },
      workflow: { do: [{ a: { set: { x: 1 } } }] },
    }
    const problems = check(doc, ctx)
    const type = problems.find((p) => p.code === 'resource.unknownType') as Problem
    const producer = problems.find((p) => p.code === 'run.unknownWorkflow') as Problem
    const next = applyFix(applyFix(doc, type) as WorkflowDocument, producer)
    expect(next?.api?.input?.properties?.image?.['x-resource']).toEqual({ type: 'image', orCreateWith: 'build-vm-image' })
    expect(check(next, ctx).some((p) => p.code === 'resource.unknownType' || p.code === 'run.unknownWorkflow')).toBe(false)
  })

  it('a step that never runs offers to be removed, and the store removes it', () => {
    const { doc, problems } = problemsOf('unreachable-step.yaml')
    expect(problems[0]?.fix).toBeUndefined()
    expect(fixOf(problems[0])).toEqual({ label: "Remove 'forgotten'", kind: 'remove-step' })
    const store = new DesignerStore({ name: null, doc, registry: null })
    expect(store.applyFix(problems[0])).toBe(true)
    expect(store.getState().doc.workflow?.do?.map((s) => Object.keys(s)[0])).toEqual(['pick', 'large', 'small'])
    expect(store.getState().canUndo).toBe(true)
  })

  it('a fix that finds nothing to change leaves the document alone', () => {
    const { doc } = problemsOf('unknown-field.yaml')
    const stale: Problem = { level: 'warning', code: 'expr.unknownField', step: 'tell', field: 'message', message: "$vm has no field 'gone'", fix: { label: 'Did you mean name?', kind: 'replace', value: 'name' } }
    expect(applyFix(doc, stale)).toBeNull()
    expect(applyFix(doc, { ...stale, step: 'nope' })).toBeNull()
  })
})
