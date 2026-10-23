import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { ConsoleKitProvider, PreviewCard, SKIPPED_NOTE, viaLabel, withoutSkippedNote, type Preview } from '../index'

/* Ported from the platform's frontend/src/test/preview-card.test.tsx at c9529d5, plus the provider's links. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const PREVIEW: Preview = {
  workflow: 'create-vm',
  version: '2.1.0',
  steps: [
    { id: 'build-image', name: 'Build the image', effect: 'none', skipped: true, detail: 'only when the requester asks for a new image' },
    { id: 'create', name: 'Create the VM', effect: 'create', detail: 'vsphere vm.create · plan vm.create: web-1044' },
    { id: 'delete-old', name: 'Delete the old VM', effect: 'delete', detail: 'vsphere vm.delete (destructive) · plan vm.get: web-1042 (vm-1042)' },
  ],
  affectedResources: [
    { id: 'img-77', type: 'image', name: 'ubuntu-2404-cis1', via: 'input.image' },
    { id: 'vm-1042', type: 'vm', name: 'web-1042', via: 'step delete-old' },
    { id: 'disk-9', type: 'disk', name: 'data-9', via: 'built from vm-1042' },
  ],
  cost: { monthly: 115.5, currency: 'EUR', detail: 'vsphere vm.create 38.50 x 3 (price list list-prices)' },
  policy: { decision: 'allow', rules: [], engine: 'mock' },
}

describe('PreviewCard', () => {
  it('draws a step the dry run did not take muted, with the note', () => {
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('data-skipped="true"')
    expect(html).toContain(SKIPPED_NOTE)
    expect(html).toContain('opacity-60')
    // only the skipped step is muted
    expect(html.match(/data-skipped="true"/g)).toHaveLength(1)
    expect(html.match(/opacity-60/g)).toHaveLength(1)
  })

  it('shows every step detail, including what the plan said', () => {
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('only when the requester asks for a new image')
    expect(html).toContain('vsphere vm.create · plan vm.create: web-1044')
    expect(html).toContain('vsphere vm.delete (destructive) · plan vm.get: web-1042 (vm-1042)')
  })

  it('names the source of the cost figure', () => {
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('115.5')
    expect(html).toContain('Priced from: ')
    expect(html).toContain('vsphere vm.create 38.50 x 3 (price list list-prices)')
  })

  it('says how each resource is reached: an input, a step, or lineage', () => {
    expect(viaLabel('input.image')).toBe('via input.image')
    expect(viaLabel('step delete-old')).toBe('via step delete-old')
    expect(viaLabel('built from vm-1042')).toBe('built from vm-1042')
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('via input.image')
    expect(html).toContain('via step delete-old')
    expect(html).toContain('built from vm-1042')
    expect(html).not.toContain('via built from')
    for (const r of PREVIEW.affectedResources) expect(html).toContain(`href="#/resources/${r.id}"`)
  })

  it('links resources where the app around it says they live', () => {
    const html = text(
      renderToString(
        <ConsoleKitProvider links={{ resource: (r) => `#/console/compute/${r.type}/${r.id}` }}>
          <PreviewCard preview={PREVIEW} />
        </ConsoleKitProvider>,
      ),
    )
    expect(html).toContain('href="#/console/compute/image/img-77"')
    expect(html).not.toContain('href="#/resources/')
  })

  it('counts only the effects of steps that run, each count kept on one line with its effect', () => {
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('1 create, 1 delete')
  })

  it('names the BFF’s resources by wfrn, links them by the id the wfrn ends in, and shows their state', () => {
    const preview: Preview = {
      steps: [{ id: 'delete', name: 'Delete the VM', effect: 'delete' }],
      affectedResources: [
        { wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001', type: 'vm', name: 'gen-001', state: 'active' },
        { wfrn: 'wfrn:wf:platform:global:123456789012:volume/vol-9', type: 'volume', state: 'active' },
      ],
      cost: { monthly: -38.5, currency: 'EUR' },
      policy: { decision: 'needs-approval' },
    }
    const html = text(renderToString(<PreviewCard preview={preview} />))
    expect(html).toContain('href="#/resources/vm-gen-001"')
    expect(html).toContain('href="#/resources/vol-9"')
    expect(html).toContain('>vol-9<')
    expect(html).toContain('· active')
    // a negative figure is what the change saves
    expect(html).toContain('Monthly saving')
    expect(html).toContain('38.5')
    expect(html).not.toContain('-38.5')
    // no workflow or version: the subtitle keeps only what it has, and no rules list
    expect(html).toMatch(/>1\sdelete</)
    expect(html).not.toContain('undefined')
    expect(html).not.toContain('policy rule')
  })

  it('draws the decision as a dot and words, never coloured text alone', () => {
    const html = text(renderToString(<PreviewCard preview={{ ...PREVIEW, policy: { decision: 'deny', rules: [{ id: 'quota', result: 'deny', message: 'The account is at its VM quota' }] } }} />))
    expect(html).toContain('The account is at its VM quota')
    expect(html).toContain('1 policy rule')
    // the tone is the dot's; the word stays in the primary text colour
    expect(html).toContain('<span aria-hidden="true" class="h-1.5 w-1.5 shrink-0 rounded-full bg-critical"></span><span class="truncate">Denied</span>')
    expect(html).not.toMatch(/text-critical[^>]*>Denied/)
  })

  it('counts in words: one change, two changes', () => {
    const preview: Preview = { ...PREVIEW, steps: [{ id: 'a', name: 'A', effect: 'change' }, { id: 'b', name: 'B', effect: 'change' }, { id: 'c', name: 'C', effect: 'read' }] }
    expect(text(renderToString(<PreviewCard preview={preview} />))).toContain('2\u00a0changes, 1\u00a0read')
  })

  it('shows the note once when the backend already put it in the detail', () => {
    expect(withoutSkippedNote('set · not taken in the dry run')).toBe('set')
    expect(withoutSkippedNote('not taken in the dry run')).toBeUndefined()
    expect(withoutSkippedNote('only on demand')).toBe('only on demand')
    expect(withoutSkippedNote(undefined)).toBeUndefined()
    const preview: Preview = {
      ...PREVIEW,
      steps: [
        { id: 'large', name: 'Large spec', effect: 'none', skipped: true, detail: 'set · not taken in the dry run' },
        { id: 'small', name: 'Small spec', effect: 'none', skipped: true, detail: 'not taken in the dry run' },
      ],
    }
    const html = text(renderToString(<PreviewCard preview={preview} />))
    expect(html.match(new RegExp(SKIPPED_NOTE, 'g'))).toHaveLength(2)
    expect(html).toContain('>set<')
  })
})
