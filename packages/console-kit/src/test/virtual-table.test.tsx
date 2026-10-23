import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { VirtualTable, sortRows, type VirtualColumn } from '../index'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

type Row = { id: string; name: string; cpu: number; owner?: string }

const ROWS: Row[] = Array.from({ length: 1000 }, (_, i) => ({ id: `vm-${i}`, name: `web-${i}`, cpu: (i % 8) + 1, owner: i % 3 === 0 ? undefined : 'team-compute' }))
const COLUMNS: VirtualColumn<Row>[] = [
  { key: 'name', header: 'Name', sortable: true, width: 'minmax(12rem, 2fr)' },
  { key: 'cpu', header: 'CPU', numeric: true, sortable: true, accessor: (r) => r.cpu },
  { key: 'owner', header: 'Owner' },
]
const RECT = { width: 800, height: 400 }

describe('VirtualTable', () => {
  it('renders only the rows on screen, as one ARIA table', () => {
    const html = text(renderToString(<VirtualTable columns={COLUMNS} rows={ROWS} rowKey={(r) => r.id} label="Virtual machines" rowHeight={40} initialRect={RECT} />))
    expect(html).toContain('role="grid"')
    expect(html).toContain('aria-label="Virtual machines"')
    expect(html).toContain('aria-rowcount="1001"')
    expect(html.match(/role="columnheader"/g)).toHaveLength(3)
    const rows = html.match(/role="row"/g)?.length ?? 0
    // the header, the ten rows that fit in 400px, and the overscan
    expect(rows).toBeGreaterThanOrEqual(11)
    expect(rows).toBeLessThanOrEqual(30)
    expect(html).toContain('web-0')
    expect(html).not.toContain('web-999')
    expect(html).toContain('aria-rowindex="2"')
    // an empty cell says so instead of showing nothing
    expect(html).toContain('not set')
    expect(html).toContain('aria-sort="none"')
  })

  it('sorts by a column, empties last, and marks the header', () => {
    expect(sortRows(ROWS, COLUMNS, { key: 'cpu', dir: 'desc' })[0].id).toBe('vm-7')
    expect(sortRows(ROWS, COLUMNS, { key: 'cpu', dir: 'asc' })[0].id).toBe('vm-0')
    const byName = sortRows(ROWS, COLUMNS, { key: 'name', dir: 'asc' })
    expect(byName.slice(0, 3).map((r) => r.name)).toEqual(['web-0', 'web-1', 'web-2'])
    const owners = sortRows(ROWS, [{ key: 'owner', header: 'Owner' }], { key: 'owner', dir: 'asc' })
    expect(owners[0].owner).toBe('team-compute')
    expect(owners[owners.length - 1].owner).toBeUndefined()
    const html = text(renderToString(<VirtualTable columns={COLUMNS} rows={ROWS} rowKey={(r) => r.id} label="Virtual machines" defaultSort={{ key: 'cpu', dir: 'desc' }} initialRect={RECT} />))
    expect(html).toContain('aria-sort="descending"')
    expect(html.indexOf('web-7')).toBeLessThan(html.indexOf('web-15'))
  })

  it('has an empty state, a loading state and a selection column', () => {
    const empty = text(renderToString(<VirtualTable columns={COLUMNS} rows={[]} rowKey={(r) => r.id} label="Virtual machines" emptyTitle="No virtual machines yet" emptyMessage="Create your first one." initialRect={RECT} />))
    expect(empty).toContain('No virtual machines yet')
    expect(empty).toContain('Create your first one.')
    const loading = text(renderToString(<VirtualTable columns={COLUMNS} rows={[]} rowKey={(r) => r.id} label="Virtual machines" loading initialRect={RECT} />))
    expect(loading).toContain('aria-busy="true"')
    expect(loading).not.toContain('No virtual machines yet')
    const selectable = text(renderToString(<VirtualTable columns={COLUMNS} rows={ROWS.slice(0, 3)} rowKey={(r) => r.id} label="Virtual machines" selectable selectedKeys={new Set(['vm-1'])} initialRect={RECT} />))
    expect(selectable).toContain('Select all rows')
    expect(selectable).toContain('Select vm-0')
    expect(selectable).toContain('aria-selected="true"')
    expect(selectable.match(/aria-selected="false"/g)).toHaveLength(2)
  })
})
