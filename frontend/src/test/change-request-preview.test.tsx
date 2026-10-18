import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DiagramSource, diagramSourceOf } from '../pages/ChangeRequestPage'

/*
 * A workflow change request's flowchart (`preview.mermaid`, docs/api.md): GitHub draws it in the
 * pull request, so the page shows its source behind a "Diagram source" disclosure, with a copy
 * button, under the canvas that stays the view.
 */

const MERMAID = 'flowchart LR\n  t0(["POST /api/v1/vms/{vmId}:restart"])\n  s1["Restart the VM<br/>provider: vsphere vm.restart"]\n  t0 --> s1\n'

describe("a change request's diagram source", () => {
  it("is a workflow change request's preview; a provider's has none", () => {
    expect(diagramSourceOf({ kind: 'workflow', preview: { mermaid: MERMAID } })).toBe(MERMAID)
    expect(diagramSourceOf({ kind: 'workflow' })).toBeNull()
    expect(diagramSourceOf({ kind: 'workflow', preview: { mermaid: '  ' } })).toBeNull()
    expect(diagramSourceOf({ kind: 'provider', preview: { mermaid: MERMAID } })).toBeNull()
  })

  it('is monospace text behind a closed disclosure, with a copy button and the way to the pull request', () => {
    const html = renderToString(<DiagramSource mermaid={MERMAID} url="https://github.com/acme/infrastructure/pull/202" />)
    expect(html).toMatch(/^<details[^>]*data-testid="diagram-source"/)
    expect(html).not.toMatch(/<details[^>]*\bopen\b/)
    expect(html).toContain('Diagram source')
    expect(html).toContain('<pre class="')
    expect(html).toContain('font-mono')
    expect(html).toContain('s1[&quot;Restart the VM&lt;br/&gt;provider: vsphere vm.restart&quot;]')
    expect(html).toContain('aria-label="Copy"')
    expect(html).toContain('href="https://github.com/acme/infrastructure/pull/202"')
    // no renderer: the source is text, never drawn
    expect(html).not.toContain('<svg class="mermaid')
    expect(renderToString(<DiagramSource mermaid={MERMAID} />)).not.toContain('href=')
  })
})
