let counter = 0

/** Monotonic, process-local id: "toast_1", "field_2". For keys and aria wiring, not for storage. */
export function nextId(prefix = 'id'): string {
  counter += 1
  return `${prefix}_${counter.toString(36)}`
}

/** Random id, UUID when the platform has one. */
export function uid(): string {
  const c = typeof globalThis.crypto !== 'undefined' ? globalThis.crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** "job_01hxk2…c9f2" — the prefix and the tail of a platform id. */
export function shortId(id: string, tail = 6): string {
  if (!id) return ''
  const sep = id.indexOf('_')
  const prefix = sep > 0 ? id.slice(0, sep + 1) : ''
  const rest = sep > 0 ? id.slice(sep + 1) : id
  if (rest.length <= tail + 2) return id
  return `${prefix}…${rest.slice(-tail)}`
}
