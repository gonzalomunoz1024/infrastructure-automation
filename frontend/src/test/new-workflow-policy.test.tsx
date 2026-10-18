import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { check, checkYaml, EMPTY_CONTEXT, type Problem, type WorkflowDocument } from '@iaf/workflow-checks'
import { emitYaml } from '../components/yaml-emit'
import { fromTemplate } from '../designer/DesignerRoute'
import { applyFix, fixOf } from '../designer/model/fixes'
import { newDocument, withOwnerPolicy } from '../designer/model/edits'
import { EMPTY_REGISTRY } from '../designer/model/registry'
import { DesignerContext, DesignerStore } from '../designer/model/store'
import { SettingsView } from '../designer/views/SettingsView'
import { blankWorkflowDocument } from '../pages/WorkflowsPage'

/*
 * Every workflow names at least one role or team that may run it: a file without `policy.rbac` is
 * `policy.missing`, an error in both twins (docs/config-as-code.md, Checks). The two skeletons a
 * new workflow starts from let its owner team run it, a template without roles gets the same, the
 * Settings tab says the field is required and the problem offers to fill it.
 */

const META = { name: 'service-restart', title: 'Restart a service', noun: 'service', verb: 'restart', owner: 'team-platform', folder: 'Platform' }
const codes = (problems: Problem[]) => problems.map((p) => p.code)

describe('a new workflow says who may run it', () => {
  it("the designer's blank document lets the owner team run it and passes the schema", () => {
    const doc = newDocument(META)
    expect(doc.policy).toEqual({ rbac: ['team-platform'] })
    const problems = codes(check(doc, EMPTY_CONTEXT))
    expect(problems).not.toContain('policy.missing')
    expect(problems).not.toContain('schema.invalid')
  })

  it("the Blank tile's first draft does too, and writes no empty key", () => {
    const yaml = emitYaml(blankWorkflowDocument({ ...META, noun: '', verb: ' ', folder: '' }))
    expect(yaml).toContain('policy:\n  rbac:\n    - team-platform\n')
    expect(yaml).not.toMatch(/noun|verb|folder|null/)
    const problems = codes(checkYaml(yaml, EMPTY_CONTEXT))
    expect(problems).not.toContain('policy.missing')
    expect(problems).not.toContain('schema.invalid')
  })

  it('a template keeps the roles it names; one that names none lets the owner team run it', () => {
    const template: WorkflowDocument = { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'tpl', title: 'Template' }, policy: { approval: { required: true, group: 'approvers' } }, workflow: { do: [] } }
    expect(fromTemplate(template, META).policy).toEqual({ approval: { required: true, group: 'approvers' }, rbac: ['team-platform'] })
    expect(fromTemplate({ ...template, policy: { rbac: ['ops', 'sre'] } }, META).policy).toEqual({ rbac: ['ops', 'sre'] })
    expect(withOwnerPolicy(template, ' ')).toBe(template)
  })

  it('policy.missing offers to let the owner team run it, which makes it go away', () => {
    const doc: WorkflowDocument = { ...newDocument(META), policy: undefined }
    const missing = check(doc, EMPTY_CONTEXT).find((p) => p.code === 'policy.missing') as Problem
    expect(missing.field).toBe('policy')
    expect(fixOf(missing)).toEqual({ label: 'Let the owner team run it', kind: 'owner-policy' })
    const fixed = applyFix(doc, missing)
    expect(fixed?.policy).toEqual({ rbac: ['team-platform'] })
    expect(codes(check(fixed, EMPTY_CONTEXT))).not.toContain('policy.missing')
    // a policy with an empty rbac is the same problem, fixed the same way
    const empty = check({ ...doc, policy: { rbac: [] } }, EMPTY_CONTEXT).find((p) => p.code === 'policy.missing') as Problem
    expect(empty.field).toBe('policy.rbac')
    expect(applyFix({ ...doc, policy: { rbac: [], change: { required: true } } }, empty)?.policy).toEqual({ rbac: ['team-platform'], change: { required: true } })
    // without an owner there is nobody to name
    expect(applyFix({ ...doc, metadata: { ...doc.metadata, owner: '' } }, missing)).toBeNull()
  })

  it('the Settings tab says at least one role or team is required, and shows the problem under the field', () => {
    const render = (doc: WorkflowDocument) => {
      const store = new DesignerStore({ name: META.name, doc, registry: EMPTY_REGISTRY })
      store.checkNow()
      const html = renderToString(
        <DesignerContext.Provider value={store}>
          <SettingsView />
        </DesignerContext.Provider>,
      )
      const start = html.indexOf('for="list-policy.rbac"')
      return html.slice(start - 200, html.indexOf('Changes need a change record'))
    }
    const without = render({ ...newDocument(META), policy: undefined })
    expect(without).toContain('Roles or teams that may run it')
    expect(without).toContain('(required)')
    expect(without).toContain('At least one role or team: a workflow that names none does not pass the checks (policy.missing). Admins may always run it')
    expect(without).toContain('Every workflow says who may run it')
    expect(without).toContain('Let the owner team run it')
    const named = render(newDocument(META))
    expect(named).toContain('value="team-platform"')
    expect(named).not.toContain('Every workflow says who may run it')
  })
})
