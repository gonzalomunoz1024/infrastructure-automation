/**
 * Polls `probe` until it answers something truthy and returns it. The interval starts short and
 * doubles up to `max`, so a fast stack is not slowed down and a slow one is not hammered. On
 * timeout the error names what was awaited and the last answer or error seen.
 */
export async function until<T>(
  what: string,
  probe: () => Promise<T | undefined | null | false>,
  { timeout = 60_000, min = 200, max = 2_000 }: { timeout?: number; min?: number; max?: number } = {},
): Promise<T> {
  const deadline = Date.now() + timeout
  let wait = min
  let last: unknown
  for (;;) {
    try {
      const value = await probe()
      if (value) return value
      last = value
    } catch (e) {
      last = e instanceof Error ? e.message : e
    }
    if (Date.now() + wait > deadline) {
      throw new Error(`timed out after ${timeout} ms waiting for ${what}; last: ${typeof last === 'string' ? last : JSON.stringify(last)}`)
    }
    await new Promise((r) => setTimeout(r, wait))
    wait = Math.min(wait * 2, max)
  }
}
