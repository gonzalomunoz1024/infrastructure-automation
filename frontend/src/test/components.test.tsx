import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import {
  AppHeader,
  Badge,
  BarChart,
  Button,
  Card,
  CardRow,
  Checkbox,
  CodeBlock,
  CommandPalette,
  ConfirmDialog,
  DiffView,
  Drawer,
  EmptyState,
  Field,
  Icon,
  ICON_NAMES,
  IconButton,
  Input,
  JsonView,
  Kbd,
  LineChart,
  Modal,
  PageHeader,
  ProgressBar,
  Segmented,
  Select,
  Sidebar,
  Skeleton,
  SkeletonRows,
  Sparkline,
  Stat,
  StateTag,
  StatusDot,
  Stepper,
  Switch,
  Table,
  Tabs,
  Tag,
  Textarea,
  ThemeToggle,
  ToastProvider,
  Tooltip,
  UserMenu,
  jobState,
  niceTicks,
  parseUnifiedDiff,
  stateTone,
} from '@iaf/ui'

const noop = () => {}

describe('primitives', () => {
  it('Button renders variants, sizes and the loading state', () => {
    const primary = renderToString(<Button variant="primary">Run</Button>)
    expect(primary).toContain('bg-accent')
    expect(primary).toContain('Run')
    expect(primary).toContain('type="button"')
    const danger = renderToString(<Button variant="danger" size="sm" loading>Delete</Button>)
    expect(danger).toContain('bg-critical')
    expect(danger).toContain('aria-busy="true"')
    expect(danger).toContain('disabled')
    expect(renderToString(<Button variant="warning-outline">Fix</Button>)).toContain('border-warning')
  })

  it('IconButton carries its accessible name', () => {
    const html = renderToString(
      <IconButton label="Close">
        <Icon name="close" />
      </IconButton>,
    )
    expect(html).toContain('aria-label="Close"')
  })

  it('Icon renders every glyph as a 24-viewBox stroke svg', () => {
    expect(ICON_NAMES.length).toBeGreaterThan(50)
    for (const name of ICON_NAMES) {
      const html = renderToString(<Icon name={name} size={16} />)
      expect(html, name).toContain('viewBox="0 0 24 24"')
      expect(html, name).toContain('stroke="currentColor"')
    }
  })

  it('Tag, StateTag and Badge', () => {
    expect(renderToString(<Tag tone="healthy">ok</Tag>)).toContain('bg-healthy-tint')
    expect(renderToString(<StateTag status="waiting-approval" />)).toContain('bg-state-waiting-tint')
    expect(renderToString(<StateTag status="running" />)).toContain('animate-pulse-subtle')
    expect(jobState('needs-attention')).toBe('waiting')
    expect(jobState('rolled-back')).toBe('failed')
    expect(stateTone('succeeded')).toBe('healthy')
    expect(renderToString(<Badge count={120} />)).toContain('99+')
    expect(renderToString(<Badge count={0} />)).toBe('')
  })

  it('StatusDot, Kbd, Tooltip, Skeleton', () => {
    expect(renderToString(<StatusDot kind="critical" label="Down" />)).toContain('Down')
    expect(renderToString(<StatusDot kind="healthy" />)).toContain('--color-healthy')
    expect(renderToString(<Kbd>⌘K</Kbd>)).toContain('<kbd')
    expect(renderToString(<Tooltip label="Hint"><button type="button">x</button></Tooltip>)).toContain('role="tooltip"')
    expect(renderToString(<Skeleton className="h-4" />)).toContain('skeleton')
    expect(renderToString(<SkeletonRows n={2} />).match(/skeleton/g)?.length).toBe(2)
  })

  it('EmptyState shows title, message and an action', () => {
    const html = renderToString(<EmptyState icon="ring" title="Nothing yet" message="Come back later" action={<Button>Add</Button>} />)
    expect(html).toContain('Nothing yet')
    expect(html).toContain('Come back later')
    expect(html).toContain('Add')
  })
})

describe('forms and selection', () => {
  it('Field, Input, Textarea, Checkbox, Switch', () => {
    const html = renderToString(
      <Field label="Name" required error="Required" htmlFor="n">
        <Input id="n" value="" onChange={noop} invalid />
      </Field>,
    )
    expect(html).toContain('for="n"')
    expect(html).toContain('aria-invalid="true"')
    expect(html).toContain('Required')
    expect(renderToString(<Textarea value="a" onChange={noop} mono />)).toContain('font-mono')
    expect(renderToString(<Checkbox checked onChange={noop} label="Yes" />)).toContain('checked')
    const sw = renderToString(<Switch checked={false} onChange={noop} label="Enabled" />)
    expect(sw).toContain('role="switch"')
    expect(sw).toContain('aria-checked="false"')
  })

  it('Select renders the current label and listbox affordance', () => {
    const html = renderToString(<Select value="b" onChange={noop} ariaLabel="Pick" options={[{ value: 'a', label: 'Alpha' }, { value: 'b', label: 'Beta' }]} />)
    expect(html).toContain('Beta')
    expect(html).toContain('aria-haspopup="listbox"')
  })

  it('Segmented and Tabs mark the selection', () => {
    const seg = renderToString(<Segmented ariaLabel="Mode" value="b" onChange={noop} options={[{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }]} />)
    expect(seg).toContain('role="radiogroup"')
    expect(seg).toContain('aria-checked="true"')
    const tabs = renderToString(<Tabs ariaLabel="Views" value="x" onChange={noop} tabs={[{ id: 'x', label: 'X', count: 3 }, { id: 'y', label: 'Y' }]} />)
    expect(tabs).toContain('aria-selected="true"')
    expect(tabs).toContain('>3<')
  })
})

describe('data display', () => {
  const rows = [
    { id: '1', name: 'b', n: 2 },
    { id: '2', name: 'a', n: 1 },
  ]
  it('Table sorts, aligns numerics and renders cells', () => {
    const html = renderToString(
      <Table
        columns={[
          { key: 'name', header: 'Name', sortable: true },
          { key: 'n', header: 'Count', numeric: true },
        ]}
        rows={rows}
        rowKey={(r) => r.id}
        defaultSort={{ key: 'name', dir: 'asc' }}
      />,
    )
    expect(html.indexOf('>a<')).toBeLessThan(html.indexOf('>b<'))
    expect(html).toContain('aria-sort="ascending"')
    expect(html).toContain('tabular-nums')
  })
  it('Table shows the empty state and the loading rows', () => {
    expect(renderToString(<Table columns={[{ key: 'x', header: 'X' }]} rows={[]} rowKey={() => ''} emptyTitle="No rows" />)).toContain('No rows')
    expect(renderToString(<Table columns={[{ key: 'x', header: 'X' }]} rows={[]} rowKey={() => ''} loading />)).toContain('skeleton')
  })
  it('Card, CardRow, PageHeader, Stat', () => {
    expect(renderToString(<Card title="T" subtitle="S"><CardRow label="L">v</CardRow></Card>)).toContain('shadow-card')
    const ph = renderToString(<PageHeader title="Jobs" subtitle="All" actions={<Button>New</Button>} back={{ href: '#/x', label: 'Back' }} />)
    expect(ph).toContain('<h1')
    expect(ph).toContain('Back')
    const stat = renderToString(<Stat label="Calls" value="1,204" delta={{ value: -3.2, suffix: '%', betterWhen: 'down' }} sparkline={[1, 2, 3]} />)
    expect(stat).toContain('text-healthy')
    expect(stat).toContain('<svg')
  })
  it('ProgressBar and Stepper expose their state', () => {
    const pb = renderToString(<ProgressBar value={42} label="Rollout" showValue />)
    expect(pb).toContain('aria-valuenow="42"')
    expect(pb).toContain('42%')
    expect(renderToString(<ProgressBar indeterminate />)).toContain('animate-progress')
    const st = renderToString(<Stepper steps={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }]} current={1} />)
    expect(st).toContain('aria-current="step"')
    expect(st).toContain('bg-accent')
  })
})

describe('charts', () => {
  it('Sparkline, LineChart and BarChart render svg with the accent only', () => {
    const sp = renderToString(<Sparkline values={[1, 3, 2]} />)
    expect(sp).toContain('text-accent')
    const line = renderToString(<LineChart series={[{ id: 'a', label: 'A', values: [1, 2] }, { id: 'b', label: 'B', values: [2, 1] }]} labels={['x', 'y']} />)
    expect(line).toContain('<svg')
    expect(line).toContain('A') // legend for two series
    expect(line).not.toContain('text-healthy')
    const bar = renderToString(<BarChart values={[3, 5]} labels={['a', 'b']} showValues />)
    expect(bar).toContain('fill-accent')
    expect(renderToString(<BarChart values={[]} />)).toContain('No data')
  })
  it('niceTicks reach past the largest value, so no point is drawn off the plot', () => {
    expect(niceTicks(0, 98.4, 4)).toEqual([0, 25, 50, 75, 100])
    expect(niceTicks(0, 60, 4)).toEqual([0, 20, 40, 60])
    const t = niceTicks(-3, 7, 4)
    expect(t[0]).toBeLessThanOrEqual(-3)
    expect(t[t.length - 1]).toBeGreaterThanOrEqual(7)
  })
  it('LineChart leaves a gap for a missing value instead of drawing zero', () => {
    const line = renderToString(<LineChart values={[98, Number.NaN, 97]} labels={['a', 'b', 'c']} />)
    expect((line.match(/ M/g) ?? []).length + (line.match(/d="M/g) ?? []).length).toBe(2)
  })
})

describe('code and diffs', () => {
  const diff = ['--- a/x.yaml', '+++ b/x.yaml', '@@ -1,2 +1,2 @@', ' same', '-old', '+new'].join('\n')
  it('parseUnifiedDiff reads files, hunks and numbering', () => {
    const files = parseUnifiedDiff(diff)
    expect(files).toHaveLength(1)
    expect(files[0].newPath).toBe('x.yaml')
    expect(files[0].hunks[0].lines.map((l) => l.type)).toEqual(['ctx', 'del', 'add'])
    expect(files[0].hunks[0].lines[2].newNo).toBe(2)
    expect(parseUnifiedDiff('+a\n-b\n c')[0].hunks[0].lines.map((l) => l.type)).toEqual(['add', 'del', 'ctx'])
  })
  it('DiffView tints additions accent and deletions critical in both layouts', () => {
    const unified = renderToString(<DiffView diff={diff} />)
    expect(unified).toContain('bg-accent-tint')
    expect(unified).toContain('bg-critical-tint')
    expect(unified).toContain('+1')
    expect(renderToString(<DiffView diff={diff} defaultMode="split" />)).toContain('grid-cols-2')
  })
  it('CodeBlock and JsonView', () => {
    expect(renderToString(<CodeBlock code={'a\nb'} lineNumbers language="yaml" />)).toContain('>2<')
    const json = renderToString(<JsonView value={{ a: 1, b: { c: 'x' } }} defaultExpanded={2} />)
    expect(json).toContain('a:')
    expect(json).toContain('&quot;x&quot;') // quotes are HTML-escaped by the server renderer
  })
})

describe('shell pieces', () => {
  it('Sidebar marks the active item and shows group labels', () => {
    const html = renderToString(
      <Sidebar
        collapsed={false}
        groups={[{ label: 'Run', items: [{ id: 'jobs', label: 'Jobs', icon: 'job', href: '#/jobs', badge: 2 }] }]}
        activeId="jobs"
      />,
    )
    expect(html).toContain('aria-current="page"')
    expect(html).toContain('Run')
    expect(html).toContain('>2<')
  })
  it('AppHeader renders the search trigger with the shortcut hint', () => {
    const html = renderToString(<AppHeader onSearch={noop} left={<span>Jobs</span>} />)
    expect(html).toContain('aria-label="Search"')
    expect(html).toContain('<kbd')
  })
  it('UserMenu shows the user and the offline state', () => {
    expect(renderToString(<UserMenu user={{ id: 'g', name: 'Gonzalo Muñoz', roles: ['ops'] }} />)).toContain('GM')
    expect(renderToString(<UserMenu status="offline" />)).toContain('Backend unreachable')
  })
  it('ThemeToggle and ToastProvider', () => {
    expect(renderToString(<ThemeToggle mode="dark" onCycle={noop} />)).toContain('Switch to light mode')
    expect(renderToString(<ToastProvider><p>child</p></ToastProvider>)).toContain('child')
  })
})

describe('overlays', () => {
  it('Modal, Drawer and ConfirmDialog are dialogs', () => {
    expect(renderToString(<Modal onClose={noop} label="Thing"><p>body</p></Modal>)).toContain('role="dialog"')
    expect(renderToString(<Drawer onClose={noop}><p>body</p></Drawer>)).toContain('animate-slide-in-right')
    const confirm = renderToString(<ConfirmDialog title="Retire?" tone="danger" confirmLabel="Retire" onConfirm={noop} onClose={noop} requireText="vm-1" />)
    expect(confirm).toContain('bg-critical')
    expect(confirm).toContain('vm-1')
  })
  it('CommandPalette lists items by group', () => {
    const html = renderToString(
      <CommandPalette onClose={noop} items={[{ id: 'a', group: 'Pages', label: 'Jobs', run: noop }, { id: 'b', group: 'Actions', label: 'Toggle theme', run: noop }]} groups={['Pages', 'Actions']} />,
    )
    expect(html).toContain('Pages')
    expect(html).toContain('Toggle theme')
    expect(html).toContain('role="option"')
  })
})
