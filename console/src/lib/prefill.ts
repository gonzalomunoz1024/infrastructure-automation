import { sensitiveFields, type Schema } from '@iaf/console-kit'

/*
 * A form opened with values already in it: the create wizard (`?prefill=`) and an action's drawer
 * (`?action=<verb>&prefill=`) read their first values from the link, so the copilot's proposals
 * and Clone open a form filled for the person to check, never submitted. The input travels as
 * base64url JSON. Only the schema's own fields are taken, and never a sensitive one: a secret
 * does not belong in a URL.
 */

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(raw: string): string {
  const b64 = raw.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

/** The query value for an input: `encodePrefill({ size: 'large' })` → 'eyJzaXplIjoibGFyZ2UifQ'. */
export function encodePrefill(input: Record<string, unknown>): string {
  return toBase64Url(JSON.stringify(input))
}

/** The input a link carries, or undefined when there is none or it does not read as an object. */
export function decodePrefill(raw: string | undefined): Record<string, unknown> | undefined {
  if (!raw) return undefined
  try {
    const v: unknown = JSON.parse(fromBase64Url(raw))
    return isRecord(v) ? v : undefined
  } catch {
    return undefined
  }
}

/** What a form may take from a link: the schema's own fields, none of them sensitive. */
export function prefillFor(schema: Schema, raw: string | undefined): Record<string, unknown> {
  const input = decodePrefill(raw)
  if (!input) return {}
  const known = new Set(Object.keys(schema.properties ?? {}))
  const secret = new Set(sensitiveFields(schema))
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(input)) if (known.has(k) && !secret.has(k) && v !== undefined) out[k] = v
  return out
}
