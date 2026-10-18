import { describe, expect, it } from 'vitest'
import { contentTabs } from '../pages/ChangeRequestPage'

/* What a change request shows below its checks: a workflow's candidate on the canvas first. */

describe('the content tabs of a change request', () => {
  it('open a workflow on the canvas, with the diff and the file beside it', () => {
    expect(contentTabs('workflow')).toEqual(['canvas', 'diff', 'yaml'])
  })

  it('give a provider no canvas: a provider file is not a workflow', () => {
    expect(contentTabs('provider')).toEqual(['diff', 'yaml'])
  })
})
