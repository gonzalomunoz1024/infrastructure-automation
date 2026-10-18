import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { EngineLinks, engineLinks } from '../components/EngineLinks'

/* The job page's links into the engine's tools: "Open in Temporal" when the backend runs on Temporal with a UI. */

const TEMPORAL = 'http://localhost:8233/namespaces/default/workflows/job_01'

describe('EngineLinks', () => {
  it('opens the job in the Temporal UI in a new tab', () => {
    const html = renderToString(<EngineLinks links={{ temporal: TEMPORAL }} />)
    expect(html).toContain(`href="${TEMPORAL}"`)
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noreferrer"')
    expect(html).toContain('Open in Temporal')
  })

  it('shows nothing when the job has no links, as on the in-memory engine', () => {
    expect(renderToString(<EngineLinks />)).toBe('')
    expect(renderToString(<EngineLinks links={{}} />)).toBe('')
  })

  it('names a link it does not know after its key and drops anything but http(s)', () => {
    expect(engineLinks({ temporal: TEMPORAL, grafana: 'https://grafana.example.internal/d/x', bad: 'javascript:alert(1)', empty: undefined })).toEqual([
      { name: 'grafana', label: 'Open in grafana', href: 'https://grafana.example.internal/d/x' },
      { name: 'temporal', label: 'Open in Temporal', href: TEMPORAL },
    ])
  })
})
