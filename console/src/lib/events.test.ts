import { describe, expect, it } from 'vitest'
import { LIVE_EVENT_TYPES, openLiveStream } from './events'

describe('the live event types', () => {
  it('name the job, registry, state, finding and personal events once each', () => {
    expect(new Set(LIVE_EVENT_TYPES).size).toBe(LIVE_EVENT_TYPES.length)
    for (const t of ['job.updated', 'resource.registered', 'types.changed', 'ProvisioningSucceeded', 'FindingOpened', 'notification']) expect(LIVE_EVENT_TYPES).toContain(t)
    // the platform's own job events never reach the browser: the BFF folds them into job.updated
    expect(LIVE_EVENT_TYPES).not.toContain('job.started')
  })

  it('are what the live stream listens to', () => {
    const listened: string[] = []
    let opened = ''
    const source = {
      readyState: 0,
      onopen: null,
      onmessage: null,
      onerror: null,
      addEventListener: (name: string) => listened.push(name),
      close: () => {},
    } as unknown as EventSource
    const stream = openLiveStream('/console/v1/events', () => {}, {
      factory: (url) => {
        opened = url
        return source
      },
      events: ['custom'],
    })
    expect(opened).toBe('/console/v1/events')
    expect(listened).toEqual([...LIVE_EVENT_TYPES, 'custom'])
    stream.close()
  })
})
