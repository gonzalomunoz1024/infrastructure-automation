import { type Tone } from '@iaf/ui'
import { get, qs, request, type ListParams } from './api'
import { partsOfWfrn, type ConsoleType } from './descriptors'

/*
 * Notifications, their settings, linked chats and the weekly digests through the BFF (hub
 * console-bff.yaml, tags Notifications, ChatOps and Digests; hub decisions 31 and 32). The
 * shapes mirror the contract's schemas. The words the pages use for each kind and channel live
 * here too, with the defaults a kind takes when the person never chose, and the reading of a
 * notification's link into the console's own route, so the bell, the Notifications page and the
 * settings say the same thing the same way.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/* ----------------------------------------------------------------------------
   The contract's shapes
   ------------------------------------------------------------------------- */

export const NOTIFICATION_KINDS = ['job.succeeded', 'job.failed', 'job.needs-attention', 'approval.requested', 'approval.decided', 'access-request.created', 'access-request.decided', 'drift.found', 'quota.near-limit', 'budget.alert', 'digest.ready'] as const
export type NotificationKind = (typeof NOTIFICATION_KINDS)[number]

export const NOTIFICATION_CHANNELS = ['console', 'email', 'slack', 'teams'] as const
export type NotificationChannel = (typeof NOTIFICATION_CHANNELS)[number]
export type ChatPlatform = 'slack' | 'teams'

export type NotificationSeverity = 'info' | 'warning' | 'critical'

export interface Notification {
  id: string
  kind: NotificationKind
  severity: NotificationSeverity
  title: string
  body?: string
  accountId: string
  region?: string
  wfrn?: string
  jobId?: string
  /** the console path it opens, such as /r/<type>/<wfrn> or /jobs/<jobId> */
  link: string
  createdAt: string
  readAt?: string
}

/** `GET /console/v1/notifications`: one page, newest first, and how many are unread in all. */
export interface NotificationPage {
  items: Notification[]
  next: string | null
  unread: number
}

export interface NotificationListParams extends ListParams {
  unread?: boolean
  kind?: NotificationKind
  accountId?: string
}

export interface LinkedChannel {
  /** whether the person linked a chat identity: read only */
  linked: boolean
  enabled: boolean
}

export interface NotificationSettings {
  version: number
  channels: {
    email: { address?: string; enabled: boolean }
    slack: LinkedChannel
    teams: LinkedChannel
  }
  /** per kind, the channels it goes to; a kind left out takes its default */
  rules: Partial<Record<NotificationKind, NotificationChannel[]>>
}

export interface ChatLink {
  id: string
  platform: ChatPlatform
  /** the Slack team or the Teams tenant */
  workspaceId: string
  chatUserId: string
  chatUserName?: string
  linkedAt: string
}

export interface DigestSummary {
  weekStart: string
  headline: string
  generatedAt: string
}

export type DigestSectionKind = 'changes' | 'drift' | 'cost' | 'access'

export interface DigestSection {
  kind: DigestSectionKind
  title: string
  text: string
  /** the numbers the text was written from */
  facts?: Record<string, unknown>
  highlights?: { text: string; link?: string }[]
}

export interface Digest {
  accountId: string
  weekStart: string
  weekEnd: string
  generatedAt: string
  /** written by the copilot, or by the template without it */
  source: 'assistant' | 'rules'
  headline: string
  sections: DigestSection[]
}

/** A live stream event (`LiveEvent`); a `notification` carries a Notification in `data`. */
export interface LiveEvent<T = Record<string, unknown>> {
  type: string
  wfrn?: string
  at: string
  version?: number
  key?: string
  data?: T
}

/* ----------------------------------------------------------------------------
   The client
   ------------------------------------------------------------------------- */

export const notificationsApi = {
  list: (params?: NotificationListParams) => get<NotificationPage>(`${b('/notifications')}${qs(params)}`),
  /** `ids` (at most 200) or `all`; answers what is left unread */
  markRead: (body: { ids: string[] } | { all: true }) => request<{ unread: number }>('POST', b('/notifications:read'), body),
  settings: () => get<NotificationSettings>(b('/notification-settings')),
  /** the version read goes in If-Match; a 409 `version.moved` says it changed since */
  putSettings: (body: NotificationSettings) => request<NotificationSettings>('PUT', b('/notification-settings'), body, { headers: { 'If-Match': String(body.version) } }),
  /** 202: in the outbox; 409 when the channel is off or not linked */
  test: (channel: NotificationChannel) => request<void>('POST', b('/notification-settings:test'), { channel }),
  chatLinks: () => get<{ items: ChatLink[] }>(b('/chat-links')),
  link: (token: string) => request<ChatLink>('POST', b('/chat-links'), { token }),
  unlink: (linkId: string) => request<void>('DELETE', b(`/chat-links/${enc(linkId)}`)),
  digests: (params?: ListParams) => get<{ items: DigestSummary[]; next: string | null }>(`${b('/digests')}${qs(params)}`),
  digest: (weekStart: string) => get<Digest>(b(`/digests/${enc(weekStart)}`)),
}

/** The live stream the bell listens to: the account's events and the person's own notifications. */
export const LIVE_EVENTS_URL = b('/events')

/* ----------------------------------------------------------------------------
   Words
   ------------------------------------------------------------------------- */

export const KIND_WORDS: Record<NotificationKind, { label: string; description: string }> = {
  'job.succeeded': { label: 'Request succeeded', description: 'A request you started finished well' },
  'job.failed': { label: 'Request failed', description: 'A request you started ended in a failure' },
  'job.needs-attention': { label: 'Request paused', description: 'A step of your request failed and waits for you to retry, skip or abort it' },
  'approval.requested': { label: 'Approval waiting', description: 'A request waits for a group you approve for' },
  'approval.decided': { label: 'Approval decided', description: 'Someone approved or rejected your request' },
  'access-request.created': { label: 'Access asked for', description: 'Someone asks for access you may decide' },
  'access-request.decided': { label: 'Access decided', description: 'Your access request was approved or rejected' },
  'drift.found': { label: 'Drift found', description: 'A resource you asked for no longer matches what the provider reports' },
  'quota.near-limit': { label: 'Quota nearly used', description: 'An account you administer used 80% of a quota' },
  'budget.alert': { label: 'Budget alert', description: 'An account you administer passed a budget threshold' },
  'digest.ready': { label: 'Weekly digest', description: 'The written summary of last week, every Monday' },
}

export const CHANNEL_WORDS: Record<NotificationChannel, string> = { console: 'Console', email: 'Email', slack: 'Slack', teams: 'Teams' }

export const SEVERITY_WORDS: Record<NotificationSeverity, { text: string; tone: Tone }> = {
  info: { text: 'Info', tone: 'accent' },
  warning: { text: 'Warning', tone: 'warning' },
  critical: { text: 'Critical', tone: 'critical' },
}

export function isNotificationKind(v: unknown): v is NotificationKind {
  return typeof v === 'string' && (NOTIFICATION_KINDS as readonly string[]).includes(v)
}

export function isChannel(v: unknown): v is NotificationChannel {
  return typeof v === 'string' && (NOTIFICATION_CHANNELS as readonly string[]).includes(v)
}

/* ----------------------------------------------------------------------------
   Defaults and rules
   ------------------------------------------------------------------------- */

/** The kinds that also go by email, and by Slack or Teams once linked, when the person never chose (hub decision 32). */
export const LOUD_KINDS: readonly NotificationKind[] = ['job.failed', 'job.needs-attention', 'approval.requested', 'access-request.created', 'access-request.decided', 'budget.alert', 'digest.ready']

/** What a kind left out of the rules takes: the console always, and email with the linked chats for the loud kinds. */
export function defaultChannels(kind: NotificationKind, linked: { slack: boolean; teams: boolean }): NotificationChannel[] {
  if (!LOUD_KINDS.includes(kind)) return ['console']
  return ['console', 'email', ...(linked.slack ? (['slack'] as const) : []), ...(linked.teams ? (['teams'] as const) : [])]
}

const ordered = (channels: Iterable<NotificationChannel>): NotificationChannel[] => {
  const set = new Set(channels)
  return NOTIFICATION_CHANNELS.filter((c) => set.has(c))
}

/** Every kind's channels, with the defaults filled in. */
export function effectiveRules(settings: Pick<NotificationSettings, 'channels' | 'rules'>): Record<NotificationKind, NotificationChannel[]> {
  const linked = { slack: settings.channels.slack.linked, teams: settings.channels.teams.linked }
  return Object.fromEntries(NOTIFICATION_KINDS.map((k) => [k, ordered(settings.rules[k] ?? defaultChannels(k, linked))])) as Record<NotificationKind, NotificationChannel[]>
}

/** The rules to save: only the kinds that differ from their default, so a kind the person never changed follows the default as it moves. */
export function rulesToSave(rules: Record<NotificationKind, NotificationChannel[]>, linked: { slack: boolean; teams: boolean }): NotificationSettings['rules'] {
  const out: NotificationSettings['rules'] = {}
  for (const k of NOTIFICATION_KINDS) {
    const mine = ordered(rules[k] ?? [])
    const def = defaultChannels(k, linked)
    if (mine.join(',') !== def.join(',')) out[k] = mine
  }
  return out
}

/** Whether a channel can carry anything now, and why not in words. */
export function channelState(settings: Pick<NotificationSettings, 'channels'>, channel: NotificationChannel): { on: boolean; reason?: string } {
  if (channel === 'console') return { on: true }
  if (channel === 'email') return settings.channels.email.enabled ? { on: true } : { on: false, reason: 'Email is turned off' }
  const c = settings.channels[channel]
  if (!c.linked) return { on: false, reason: `${CHANNEL_WORDS[channel]} is not linked` }
  return c.enabled ? { on: true } : { on: false, reason: `${CHANNEL_WORDS[channel]} is turned off` }
}

/* ----------------------------------------------------------------------------
   Where a notification leads
   ------------------------------------------------------------------------- */

/**
 * The console route a notification's link names, as a hash path: `/r/<type>/<wfrn>` is the
 * resource's page (with its query, such as `?tab=drift`), `/jobs/<id>` the request,
 * `/access-requests/<id>` the access request, `/digests/<weekStart>` the digest. A notification
 * of another account gets the account prefix, so following it switches the session there first.
 * Null when the link names nothing the console has a page for. The answer is always one of
 * these same-origin hash routes, built from encoded parts: a link from the data never becomes an
 * href as it came, whatever host or scheme it named.
 */
export function routeOfLink(n: Pick<Notification, 'link' | 'wfrn' | 'jobId' | 'accountId'>, types: readonly Pick<ConsoleType, 'service' | 'name'>[], sessionAccountId: string): string | null {
  const raw = (n.link ?? '').replace(/^https?:\/\/[^/]+/, '').replace(/^\/console(?=\/)/, '').replace(/^#/, '')
  const qi = raw.indexOf('?')
  const path = qi >= 0 ? raw.slice(0, qi) : raw
  // only a tab name rides along: nothing else from the data reaches the address
  const tab = qi >= 0 ? new URLSearchParams(raw.slice(qi + 1)).get('tab') : null
  const query = tab && /^[a-z][a-z-]{0,31}$/.test(tab) ? `?tab=${tab}` : ''
  const decode = (s: string) => {
    try {
      return decodeURIComponent(s)
    } catch {
      return s
    }
  }
  let to: string | null = null
  let m = /^\/r\/([a-z0-9-]{1,64})\/(.+)$/.exec(path)
  if (m) {
    const typeName = m[1]
    const wfrn = decode(m[2])
    const parts = partsOfWfrn(wfrn)
    const service = types.find((t) => t.name === typeName)?.service ?? parts?.service
    if (service) to = `/console/${enc(service)}/${enc(typeName)}/${enc(wfrn)}${query}`
  } else if ((m = /^\/jobs\/([^/]+)$/.exec(path))) {
    to = `/requests/${enc(decode(m[1]))}${query}`
  } else if ((m = /^\/(?:iam\/)?access-requests\/([^/]+)$/.exec(path))) {
    to = `/iam/access-requests/${enc(decode(m[1]))}`
  } else if ((m = /^\/digests\/(\d{4}-\d{2}-\d{2})$/.exec(path))) {
    to = `/digests/${m[1]}`
  } else if (/^\/iam\/quotas(\/|$)/.test(path)) {
    to = '/iam'
  } else if (path === '/drift' || path === '/operations/drift') {
    to = '/operations/drift'
  }
  if (!to && n.wfrn) {
    const parts = partsOfWfrn(n.wfrn)
    const service = types.find((t) => t.name === parts?.type)?.service ?? parts?.service
    if (parts && service) to = `/console/${enc(service)}/${enc(parts.type)}/${enc(n.wfrn)}`
  }
  if (!to && n.jobId) to = `/requests/${enc(n.jobId)}`
  if (!to) return null
  return n.accountId && n.accountId !== sessionAccountId ? `/account/${n.accountId}${to}` : to
}

/* ----------------------------------------------------------------------------
   Chat link tokens and weeks
   ------------------------------------------------------------------------- */

/**
 * What a chat link token says it links, read without checking its signature (only the BFF can):
 * enough to ask "Link Slack user @dana in workspace T0FIRM?" before the person confirms. Null
 * when the token is not a JWT the console can read; the BFF still decides.
 */
export function readChatLinkToken(token: string): { platform?: ChatPlatform; workspaceId?: string; chatUserId?: string; chatUserName?: string; expiresAt?: string } | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = JSON.parse(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))) as Record<string, unknown>
    const str = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
    const platform = json.platform === 'slack' || json.platform === 'teams' ? json.platform : undefined
    return { platform, workspaceId: str(json.workspaceId), chatUserId: str(json.chatUserId), chatUserName: str(json.chatUserName), expiresAt: typeof json.exp === 'number' ? new Date(json.exp * 1000).toISOString() : undefined }
  } catch {
    return null
  }
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

/** "2026-10-12" and "2026-10-18" → "12 to 18 October 2026"; across months "28 September to 4 October 2026". */
export function weekWords(weekStart: string, weekEnd?: string): string {
  const start = new Date(`${weekStart}T00:00:00Z`)
  if (Number.isNaN(start.getTime())) return weekStart
  const end = weekEnd ? new Date(`${weekEnd}T00:00:00Z`) : new Date(start.getTime() + 6 * 86_400_000)
  const sd = start.getUTCDate()
  const ed = end.getUTCDate()
  const sm = MONTHS[start.getUTCMonth()]
  const em = MONTHS[end.getUTCMonth()]
  if (start.getUTCFullYear() !== end.getUTCFullYear()) return `${sd} ${sm} ${start.getUTCFullYear()} to ${ed} ${em} ${end.getUTCFullYear()}`
  if (sm !== em) return `${sd} ${sm} to ${ed} ${em} ${end.getUTCFullYear()}`
  return `${sd} to ${ed} ${em} ${end.getUTCFullYear()}`
}

/** A fact's key in words: `jobsSucceeded` → "Jobs succeeded". */
export function factLabel(key: string): string {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .toLowerCase()
    .trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
