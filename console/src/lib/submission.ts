import { useCallback, useRef } from 'react'
import { idempotencyKey } from './bff'

/*
 * One Idempotency-Key per form submission (P-21): sending the same body again (a retry after a
 * lost answer, a second click) reuses the key, so the platform answers with the first result
 * and starts exactly one job; changing what is sent mints a new key. The key is taken when the
 * form is sent, never while rendering.
 */
export function useSubmissionKey(): (body: unknown) => string {
  const last = useRef<{ json: string; key: string } | null>(null)
  return useCallback((body: unknown) => {
    const json = JSON.stringify(body ?? null)
    if (last.current && last.current.json === json) return last.current.key
    const key = idempotencyKey()
    last.current = { json, key }
    return key
  }, [])
}

/** The body without the empty values a form leaves behind ('' , null, []), nested objects kept. */
export function cleanInput(value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(value)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v) && v.length === 0) continue
    out[k] = v
  }
  return out
}
