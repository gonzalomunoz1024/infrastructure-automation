import { get, page, request, type ListParams } from './api'
import type { ApiClientId, ApiSession, DeviceGrant } from './types'

/*
 * The CLI's web side (WP 7.4, hub console-bff.yaml tags "Device sign-in" and "API sessions"):
 * the `#/device` page reads a device grant by the code the CLI shows and approves or denies it,
 * and the CLI sign-ins page lists the person's API sessions and revokes one. Approving needs a
 * fresh sign-in (the BFF's step-up 403, which the API client follows live). The words the pages
 * use for a grant and a session live here too, so both pages say the same thing the same way.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

export const cli = {
  deviceGrants: {
    get: (userCode: string) => get<DeviceGrant>(b(`/device-grants/${enc(userCode)}`)),
    /** browser sessions only; past the step-up age it is the step-up 403 */
    approve: (userCode: string) => request<DeviceGrant>('POST', b(`/device-grants/${enc(userCode)}:approve`)),
    deny: (userCode: string) => request<DeviceGrant>('POST', b(`/device-grants/${enc(userCode)}:deny`)),
  },
  apiSessions: {
    /** newest first, the open ones only */
    list: (params?: ListParams) => page<ApiSession>(b('/api-sessions'), params),
    /** its refresh token stops at once, its access token within 15 minutes */
    revoke: (apiSessionId: string) => request<void>('DELETE', b(`/api-sessions/${enc(apiSessionId)}`)),
  },
}

/* ----------------------------------------------------------------------------
   The code the CLI shows
   ------------------------------------------------------------------------- */

/** The letters a user code is made of (RFC 8628 §6.1: no vowels, so no words). */
export const USER_CODE_LETTERS = 'BCDFGHJKLMNPQRSTVWXZ'

/** 'wdjb mjht', 'WDJBMJHT', 'wdjb-mjht' → 'WDJB-MJHT'; null when it is not eight letters. */
export function normalizeUserCode(input: string): string | null {
  const letters = input.toUpperCase().replace(/[^A-Z]/g, '')
  if (letters.length !== 8 || input.replace(/[\s-]/g, '').length !== 8) return null
  return `${letters.slice(0, 4)}-${letters.slice(4)}`
}

/** Whether every letter is one a code can hold; a vowel is a misread. */
export function codeLettersValid(code: string): boolean {
  return code.replace('-', '').split('').every((c) => USER_CODE_LETTERS.includes(c))
}

/* ----------------------------------------------------------------------------
   Words
   ------------------------------------------------------------------------- */

const CLIENTS: Record<ApiClientId, { title: string; short: string }> = {
  'console-cli': { title: 'The console CLI', short: 'CLI' },
  'console-sdk': { title: 'A console SDK', short: 'SDK' },
}

/** 'console-cli' → 'The console CLI'. */
export function clientTitle(clientId: string): string {
  return CLIENTS[clientId as ApiClientId]?.title ?? clientId
}

/** 'console-cli' → 'CLI'. */
export function clientShort(clientId: string): string {
  return CLIENTS[clientId as ApiClientId]?.short ?? clientId
}

const SYSTEMS: [RegExp, string][] = [
  [/darwin|mac ?os/i, 'macOS'],
  [/win32|windows/i, 'Windows'],
  [/linux/i, 'Linux'],
]

/**
 * What a user agent says about the machine, in words: 'iaf/0.7.0 (darwin; arm64) node/22.11.0'
 * → 'macOS on arm64, iaf 0.7.0'. Unknown parts are left out; nothing at all is 'An unknown machine'.
 */
export function machineOf(userAgent: string | undefined): string {
  if (!userAgent) return 'An unknown machine'
  const system = SYSTEMS.find(([re]) => re.test(userAgent))?.[1]
  const arch = /\b(arm64|aarch64|x64|x86_64|amd64)\b/i.exec(userAgent)?.[1]
  const client = /^([a-z][\w.-]*)\/(\d[\w.-]*)/i.exec(userAgent)
  const where = system ? (arch ? `${system} on ${arch}` : system) : arch ? `A machine on ${arch}` : null
  const what = client ? `${client[1]} ${client[2]}` : null
  if (where && what) return `${where}, ${what}`
  return where ?? what ?? 'An unknown machine'
}

/** What the person signs in: the console's one scope, said as what it lets the client do. */
export const SCOPE_WORDS = 'Everything you may do in the console, in the accounts you belong to. The CLI never gets more than you have, and a refusal reads the same as in the browser.'

/** Minutes and seconds left until `at`, or null once it passed. */
export function timeLeft(at: string, now = Date.now()): string | null {
  const ms = Date.parse(at) - now
  if (Number.isNaN(ms) || ms <= 0) return null
  const s = Math.ceil(ms / 1000)
  const m = Math.floor(s / 60)
  if (m >= 60) {
    const h = Math.floor(m / 60)
    return `${h} hour${h === 1 ? '' : 's'}${m % 60 ? ` ${m % 60} min` : ''}`
  }
  return m > 0 ? `${m} min ${s % 60} s` : `${s} s`
}

/** The status word of a grant as the page says it. */
export function grantStatusWords(g: Pick<DeviceGrant, 'status'>): string {
  switch (g.status) {
    case 'approved':
      return 'This sign-in was approved. The CLI is signed in and can be closed here.'
    case 'denied':
      return 'This sign-in was denied. The CLI was told no and signed nobody in.'
    case 'expired':
      return 'This code expired. Run the sign-in again in your terminal for a new one.'
    default:
      return 'Waiting for you to decide.'
  }
}
