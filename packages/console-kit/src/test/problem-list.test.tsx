import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { ProblemList, fieldRoot, normalizeField, problemsFor, unfieldedProblems, type Problem } from '../index'

/* Ported from the ProblemList section of the platform's frontend/src/test/composites.test.tsx at c9529d5. */

const PROBLEMS: Problem[] = [
  { level: 'error', code: 'approval.missing', step: 'delete-vm', message: 'Deleting in prod needs an approval step before it', fix: { label: 'Insert an approval', kind: 'insert-approval' } },
  { level: 'warning', code: 'retry.unsafe', step: 'restart', field: 'retry', message: 'vm.restart is unsafe to retry' },
]

describe('ProblemList', () => {
  it('lists each problem with its code, step and field', () => {
    const html = renderToString(<ProblemList problems={PROBLEMS} />)
    expect(html).toContain('aria-label="Problems"')
    expect(html).toContain('Deleting in prod needs an approval step before it')
    expect(html).toContain('approval.missing')
    expect(html).toContain('delete-vm')
    expect(html).toContain('vm.restart is unsafe to retry')
    expect(html).not.toContain('Insert an approval') // no fix handler, no button
  })

  it('offers the fix when a handler is given, and the empty label when clean', () => {
    expect(renderToString(<ProblemList problems={PROBLEMS} onFix={() => {}} />)).toContain('Insert an approval')
    expect(renderToString(<ProblemList problems={[]} />)).toBe('')
    expect(renderToString(<ProblemList problems={[]} emptyLabel="No problems" />)).toContain('No problems')
  })

  it('matches problems to fields however the server spells the path', () => {
    expect(normalizeField('$.input.vmId')).toBe('vmId')
    expect(normalizeField('/body/properties/vmId')).toBe('vmId')
    expect(normalizeField('input.vmId')).toBe('vmId')
    expect(normalizeField('vmId')).toBe('vmId')
    expect(normalizeField(undefined)).toBeUndefined()
    expect(fieldRoot('image.create.os')).toBe('image')
    expect(fieldRoot('$.input.image.create')).toBe('image')
    const problems: Problem[] = [
      { level: 'error', code: 'a', field: 'input.vmId', message: 'a' },
      { level: 'error', code: 'b', field: '/vmId', message: 'b' },
      { level: 'error', code: 'c', field: 'reason', message: 'c' },
      { level: 'error', code: 'd', message: 'd' },
    ]
    expect(problemsFor(problems, 'vmId').map((p) => p.code)).toEqual(['a', 'b'])
    expect(unfieldedProblems(problems, ['vmId']).map((p) => p.code)).toEqual(['c', 'd'])
  })
})
