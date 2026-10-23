import { describe, expect, it } from 'vitest'
import type { ConsoleType } from './descriptors'
import { approvalHeadline, mobileViewOf, verbOfWorkflow } from './mobile'

/* The phone view's words (WP 9.18): what an approval is about, and which section the link opens. */

const vmType = { name: 'vm', title: 'Virtual machines', service: 'compute' } as ConsoleType

describe('the phone view', () => {
  it('says what an approval is about in a few words', () => {
    expect(approvalHeadline({ workflow: 'vm-restart', wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-gen-004' }, [vmType])).toBe('Restart virtual machine vm-gen-004')
    expect(approvalHeadline({ workflow: 'create-vm', wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-x' }, [vmType])).toBe('Create virtual machine vm-x')
    expect(approvalHeadline({ wfrn: 'wfrn:wf:data:global:123456789012:database/db-1' }, [vmType])).toBe('A change to db-1')
    expect(approvalHeadline({ message: 'Waits for compute-approvers' }, [])).toBe('Waits for compute-approvers')
    expect(verbOfWorkflow('mongodb-upgrade', { name: 'database' })).toBe('Mongodb upgrade')
  })

  it('opens the section the link names, the approvals by default', () => {
    expect(mobileViewOf('drift')).toBe('drift')
    expect(mobileViewOf('requests')).toBe('requests')
    expect(mobileViewOf(undefined)).toBe('approvals')
    expect(mobileViewOf('elsewhere')).toBe('approvals')
  })
})
