import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { CursorPager, LifecycleBadge, WfrnText, displayWfrn, lifecycleOf, nextCursorFromLink, parseDuration, countdown, shortJobId, summarize, titleCase } from '../index'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const WFRN = 'wfrn:wf:compute:global:123456789012:vm/vm-web-01'

describe('WfrnText', () => {
  it('elides the middle of a long wfrn, keeping the service and the type and id', () => {
    expect(displayWfrn(WFRN, 0)).toBe(WFRN)
    expect(displayWfrn(WFRN, 60)).toBe(WFRN)
    expect(displayWfrn(WFRN, 30)).toBe('wfrn:wf:compute:…:vm/vm-web-01')
    expect(displayWfrn(WFRN, 20)).toBe('wfrn:…:vm/vm-web-01')
    expect(displayWfrn(WFRN, 10).length).toBeLessThanOrEqual(10)
    expect(displayWfrn('not a wfrn but a long enough string', 20).length).toBeLessThanOrEqual(20)
  })

  it('renders the whole name for screen readers and a copy button', () => {
    const html = text(renderToString(<WfrnText wfrn={WFRN} max={30} />))
    expect(html).toContain(`data-wfrn="${WFRN}"`)
    expect(html).toContain('wfrn:wf:compute:…:vm/vm-web-01')
    expect(html).toContain(`<span class="sr-only">${WFRN}</span>`)
    expect(html).toContain('aria-label="Copy wfrn"')
    expect(text(renderToString(<WfrnText wfrn={WFRN} copy={false} href="#/x" />))).toContain('href="#/x"')
    expect(text(renderToString(<WfrnText wfrn={WFRN} copy={false} />))).not.toContain('Copy wfrn')
  })
})

describe('LifecycleBadge', () => {
  it('folds state words onto the six classes, the type map first', () => {
    expect(lifecycleOf('ready')).toBe('active')
    expect(lifecycleOf('Building')).toBe('pending')
    expect(lifecycleOf('retired')).toBe('deleted')
    expect(lifecycleOf('deprecated')).toBe('active')
    expect(lifecycleOf('failed')).toBe('failed')
    expect(lifecycleOf('patching')).toBe('updating')
    expect(lifecycleOf('terminating')).toBe('deleting')
    expect(lifecycleOf('weird')).toBeNull()
    expect(lifecycleOf(undefined)).toBeNull()
    expect(lifecycleOf('ready', { ready: 'updating' })).toBe('updating')
    expect(lifecycleOf('odd', { odd: 'not-a-class' })).toBeNull()
  })

  it('shows the type word on the class tint', () => {
    const html = text(renderToString(<LifecycleBadge status="ready" />))
    expect(html).toContain('data-lifecycle="active"')
    expect(html).toContain('>ready<')
    expect(html).toContain('bg-healthy-tint')
    expect(text(renderToString(<LifecycleBadge status="retired" />))).toContain('data-lifecycle="deleted"')
    expect(text(renderToString(<LifecycleBadge status="weird" />))).toContain('data-lifecycle="unknown"')
    expect(text(renderToString(<LifecycleBadge status="building" lifecycle="failed" />))).toContain('data-lifecycle="failed"')
  })
})

describe('CursorPager', () => {
  it('reads the next cursor from a Link header', () => {
    expect(nextCursorFromLink('</api/v1/vms?limit=50&after=abc123>; rel="next"')).toBe('abc123')
    expect(nextCursorFromLink('</api/v1/vms?after=p1>; rel="prev", </api/v1/vms?limit=50&after=p3>; rel=next')).toBe('p3')
    expect(nextCursorFromLink('</api/v1/vms?after=p1>; rel="prev"')).toBeNull()
    expect(nextCursorFromLink(null)).toBeNull()
    expect(nextCursorFromLink('')).toBeNull()
  })

  it('says what is on the page and offers the way forward and back', () => {
    const html = text(renderToString(<CursorPager count={50} hasNext hasPrevious={false} onNext={() => {}} onPrevious={() => {}} pageSize={50} onPageSizeChange={() => {}} noun="virtual machine" page={1} />))
    expect(html).toContain('Showing 50 virtual machines, more available')
    expect(html).toContain('page 1')
    expect(html).toContain('aria-label="Next page"')
    expect(html).toMatch(/aria-label="Previous page"[^>]*disabled=""/)
    expect(html).toContain('aria-label="Rows per page"')
    expect(text(renderToString(<CursorPager count={0} hasNext={false} hasPrevious onNext={() => {}} onPrevious={() => {}} pageSize={50} noun="virtual machine" />))).toContain('No virtual machines on this page')
  })
})

describe('common helpers', () => {
  it('reads durations, counts down, shortens ids and summarises values', () => {
    expect(parseDuration('4h')).toBe(4 * 3_600_000)
    expect(parseDuration('2h30m')).toBe(2.5 * 3_600_000)
    expect(parseDuration('PT4H')).toBe(4 * 3_600_000)
    expect(parseDuration('90')).toBe(90_000)
    expect(parseDuration('sometimes')).toBeNull()
    expect(countdown(Date.UTC(2026, 0, 1, 3, 20), Date.UTC(2026, 0, 1))).toEqual({ text: 'in 3h 20m', expired: false })
    expect(countdown(Date.UTC(2026, 0, 1), Date.UTC(2026, 0, 1, 0, 5)).text).toBe('expired 5m ago')
    expect(shortJobId('job_0123456789abcdef')).toBe('job_…abcdef')
    expect(shortJobId('job-1')).toBe('job-1')
    expect(summarize({ a: 1, b: { c: 2 } })).toBe('a: 1 · b: {"c":2}')
    expect(summarize(null)).toBe('not set')
    expect(titleCase('needs-attention')).toBe('Needs attention')
  })
})
