import { describe, expect, it } from 'vitest'
import type { Problem } from '@iaf/workflow-checks'
import { PUBLISH_GROUP, documentTarget, groupsOf, isPublishProblem } from './IssuesDrawer'

const PROBLEMS: Problem[] = [
  { level: 'warning', code: 'expr.unknownField', step: 'restart', field: 'input.vm', message: "vm has no field 'vmId'" },
  { level: 'error', code: 'metadata.owner', field: 'metadata.owner', message: 'owner is required' },
  { level: 'error', code: 'publish.playbooks', step: 'restart', field: 'playbook', message: 'The playbooks check of the change request fails: pin the playbook to a commit' },
  { level: 'error', code: 'publish.routes', field: 'api.path', message: 'POST /vms/{vmId}:restart is already answered by vm-reboot' },
  { level: 'error', code: 'publish.contracts', field: 'metadata.version', message: '1.3.0 breaks the contract of 1.2.0', fix: { label: 'Publish as 2.0.0', kind: 'replace', value: '2.0.0' } },
]

describe('the issues list', () => {
  it('groups the publish checks under "Before publishing", after the steps, whatever step they name', () => {
    const groups = groupsOf(PROBLEMS, (id) => (id === 'restart' ? 'Restart with Ansible' : id))
    expect(groups.map((g) => g.title)).toEqual(['Workflow', 'Restart with Ansible', PUBLISH_GROUP])
    const publish = groups[2]
    expect(publish.publish).toBe(true)
    expect(publish.problems.map((p) => p.code)).toEqual(['publish.playbooks', 'publish.routes', 'publish.contracts'])
    expect(groups[1].problems.map((p) => p.code)).toEqual(['expr.unknownField'])
    expect(PROBLEMS.filter(isPublishProblem)).toHaveLength(3)
  })

  it('leaves the group out when nothing came from the publish checks', () => {
    const groups = groupsOf(PROBLEMS.slice(0, 2), (id) => id)
    expect(groups.map((g) => g.title)).toEqual(['Workflow', 'restart'])
  })

  it('opens document problems where they are edited', () => {
    expect(documentTarget({ level: 'error', code: 'api.reservedPath', field: 'api.path', message: '' })).toBe('settings')
    expect(documentTarget({ level: 'error', code: 'trigger.match', field: 'triggers[1].alert.match', message: '' })).toBe('trigger')
    expect(documentTarget({ level: 'error', code: 'publish.contracts', field: 'metadata.version', message: '' })).toBe('settings')
    expect(documentTarget({ level: 'error', code: 'workflow.empty', message: '' })).toBeNull()
  })
})
