import { describe, expect, it } from 'vitest'
import { hostsText, hostsValue, inventoryFor, inventoryKind, splitHosts, withGroupKey } from './inventory'

/*
 * An ansible step's inventory and limit in the runner's shapes (docs/config-as-code.md, the
 * ansible step): dynamic, a path in the playbook's source, a list of hosts or { hosts, group?,
 * vars? }, none for ansible's implicit localhost; the limit a pattern or a list of hosts.
 */

describe('an ansible inventory', () => {
  it('is one of the shapes the runner reads', () => {
    expect(inventoryKind('dynamic')).toBe('dynamic')
    expect(inventoryKind(' dynamic ')).toBe('dynamic')
    expect(inventoryKind('inventories/prod.ini')).toBe('path')
    expect(inventoryKind('{{ $vars.inventory }}')).toBe('path')
    expect(inventoryKind(['web1', 'web2'])).toBe('hosts')
    expect(inventoryKind({ hosts: 'web1,web2', group: 'web' })).toBe('group')
    expect(inventoryKind(undefined)).toBe('none')
    expect(inventoryKind(null)).toBe('none')
  })

  it('takes hosts typed with commas, an expression whole', () => {
    expect(splitHosts('web1, web2,,  db-1 ')).toEqual(['web1', 'web2', 'db-1'])
    expect(splitHosts("web1, {{ $join($vms, ',') }}, {{ $vm.name }}")).toEqual(['web1', "{{ $join($vms, ',') }}", '{{ $vm.name }}'])
    expect(splitHosts(' , ')).toEqual([])
    expect(hostsText(['web1', 'web2'])).toBe('web1, web2')
    expect(hostsText('web1,web2')).toBe('web1,web2')
    expect(hostsText({ hosts: [] })).toBe('')
  })

  it('writes typed hosts as a list, a string where one was written, and nothing for none', () => {
    expect(hostsValue('web1, web2', undefined)).toEqual(['web1', 'web2'])
    expect(hostsValue('web1, web2', 'web1', true)).toBe('web1,web2')
    expect(hostsValue('web1, web2', 'web1')).toEqual(['web1', 'web2'])
    expect(hostsValue('  ', ['web1'])).toBeUndefined()
  })

  it("sets a group's keys, keeps the others and leaves no empty group", () => {
    expect(withGroupKey({ hosts: ['web1'], vars: { a: 1 } }, 'group', 'web')).toEqual({ hosts: ['web1'], vars: { a: 1 }, group: 'web' })
    expect(withGroupKey(['web1', 'web2'], 'group', 'web')).toEqual({ hosts: ['web1', 'web2'], group: 'web' })
    expect(withGroupKey('dynamic', 'hosts', ['web1'])).toEqual({ hosts: ['web1'] })
    expect(withGroupKey({ hosts: ['web1'] }, 'hosts', undefined)).toBeUndefined()
  })

  it('switches kind writing only what is whole: the hosts move along, a path waits for its text', () => {
    expect(inventoryFor('dynamic', ['web1'])).toEqual({ value: 'dynamic' })
    expect(inventoryFor('none', 'dynamic')).toEqual({ value: undefined })
    expect(inventoryFor('group', ['web1', 'web2'])).toEqual({ value: { hosts: ['web1', 'web2'] } })
    expect(inventoryFor('hosts', { hosts: 'web1, web2', group: 'web' })).toEqual({ value: ['web1', 'web2'] })
    expect(inventoryFor('hosts', { hosts: ['web1'] })).toEqual({ value: ['web1'] })
    expect(inventoryFor('hosts', 'dynamic')).toBe('pending')
    expect(inventoryFor('group', 'dynamic')).toBe('pending')
    expect(inventoryFor('path', 'dynamic')).toBe('pending')
  })
})
