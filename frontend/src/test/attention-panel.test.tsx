import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { Attention } from '../lib/types'
import { AttentionPanel } from '../components/AttentionPanel'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const never = async () => {}

const ATTENTION: Attention = {
  step: 'wait-on',
  error: { class: 'timeout', message: 'Wait for power on did not finish within its timeout', retryable: true },
  options: ['retry', 'edit-input', 'skip', 'abort'],
  since: new Date(Date.now() - 5 * 60_000).toISOString(),
  timeout: 'PT4H',
}

describe('AttentionPanel', () => {
  it('shows the failure the backend reports as its class, message and whether a retry may help', () => {
    const html = text(renderToString(<AttentionPanel attention={ATTENTION} onRetry={never} onRetryWithInput={never} onSkip={never} onAbort={never} />))
    expect(html).toContain('timeout')
    expect(html).toContain('Wait for power on did not finish within its timeout')
    expect(html).toContain('retryable')
    expect(html).not.toContain('[object Object]')
  })

  it('renders without an error when the engine reported none', () => {
    const html = text(renderToString(<AttentionPanel attention={{ ...ATTENTION, error: undefined }} onRetry={never} onRetryWithInput={never} onSkip={never} onAbort={never} />))
    expect(html).toContain('Needs attention')
    expect(html).not.toContain('data-testid="attention-error"')
  })
})
