import { describe, expect, it } from 'vitest'
// the hub's AsyncAPI, copied at the commit contracts.lock pins
import asyncapi from '../../../contracts/platform/asyncapi-target.yaml?raw'
import { PLATFORM_EVENT_TYPES, openPlatformStream } from './events'

describe('the platform event types', () => {
  it('are exactly the types the AsyncAPI contract names', () => {
    const fromConsts = [...asyncapi.matchAll(/type: \{ const: ([a-z-]+\.[a-z-]+) \}/g)].map((m) => m[1])
    const fromNames = [...asyncapi.matchAll(/^ {6}name: ([a-z-]+\.[a-z-]+)$/gm)].map((m) => m[1])
    const contract = [...new Set([...fromConsts, ...fromNames])].sort()
    expect(contract.length).toBeGreaterThan(0)
    expect([...PLATFORM_EVENT_TYPES].sort()).toEqual(contract)
  })

  it('are what the platform stream listens to', () => {
    const listened: string[] = []
    const source = {
      readyState: 0,
      onopen: null,
      onmessage: null,
      onerror: null,
      addEventListener: (name: string) => listened.push(name),
      close: () => {},
    } as unknown as EventSource
    const stream = openPlatformStream('/api/v1/events/stream', () => {}, { factory: () => source, events: ['custom'] })
    expect(listened).toEqual([...PLATFORM_EVENT_TYPES, 'custom'])
    stream.close()
  })
})
