import { roleLabel, ADMIN_ROLE } from '../lib/session'
import { NOTIFICATION_CHANNELS, defaultChannels, isChannel, isNotificationKind, type ChatLink, type ChatPlatform, type Digest, type DigestSummary, type LiveEvent, type Notification, type NotificationChannel, type NotificationKind, type NotificationSettings } from '../lib/notifications'
import { emptyConsoleState, idFrom, iso, journaled, typeOf, wfrnOfResource, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { DIRECTORY_PARTITION, accessRequestsOf, accountOf, evaluateRoles, personOf, rolesOf, type Decision } from './directory'

/*
 * The BFF's notifications, notification settings, chat links and weekly digests (hub
 * console-bff.yaml, tags Notifications, ChatOps and Digests; hub decision 32), answered from the
 * dataset. The BFF follows the events it relays and notifies the people each concerns, once per
 * event and person; so does this module, following the console's own jobs (a top-level job that
 * succeeds, fails or pauses notifies its requester; an approval it opens the approver group's
 * members, and its answer the requester) and the account's access requests (the approvers when
 * asked, the requester when decided). It runs at the end of every settle of the clock, so a job
 * that ends on the clock notifies at the moment it ended, and a reload's replay notifies the same.
 * Each person also starts with a few seeded notifications, a chat link or two and two weekly
 * digests per account. Reads, settings and chat links are journaled under the `notification.`
 * and `chat-link.` kinds; a test send is not (it only reaches the outbox).
 */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
/** the feed keeps 90 days */
export const KEPT_MS = 90 * DAY
/** a notification about something older than this when it is first delivered arrives read: the person was around then */
const READ_IF_OLDER_MS = 3 * DAY
export const CHAT_LINK_AUDIENCE = 'console-chat-link'

/* ----------------------------------------------------------------------------
   State
   ------------------------------------------------------------------------- */

export interface StoredNotification extends Notification {
  userId: string
  seq: number
}

/** What a person chose, beside what the identity provider and the chat links say. */
export interface StoredSettings {
  version: number
  email: boolean
  slack: boolean
  teams: boolean
  rules: NotificationSettings['rules']
}

export interface StoredChatLink extends ChatLink {
  userId: string
}

export interface NotificationsState {
  items: StoredNotification[]
  /** `<userId>|<event key>`: what was delivered already, once per event and person */
  delivered: Record<string, true>
  seq: number
  settings: Record<string, StoredSettings>
  links: StoredChatLink[]
  outbox: { userId: string; channel: NotificationChannel; at: number; test: boolean }[]
  /** when the seeds were laid, the anchor of their times and of the digests' weeks */
  seededAt: number
}

/* ----------------------------------------------------------------------------
   Seeds
   ------------------------------------------------------------------------- */

const COMPUTE = '123456789012'
const DATA = '210987654321'

/** The Monday at 00:00 UTC of the week holding `at`. */
export function mondayOf(at: number): number {
  const d = new Date(at)
  const day = (d.getUTCDay() + 6) % 7
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - day)
}

const dateOf = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** The weeks the seeded digests cover: the last finished week, and the one before. The newest is written the Monday after it ends, at 06:00 UTC. */
export function digestWeeksOf(now: number): { weekStart: string; weekEnd: string; generatedAt: number }[] {
  let monday = mondayOf(now)
  // before Monday 06:00 this week's digest is not written yet
  if (now < monday + 6 * HOUR) monday -= 7 * DAY
  return [1, 2].map((n) => {
    const start = monday - n * 7 * DAY
    return { weekStart: dateOf(start), weekEnd: dateOf(start + 6 * DAY), generatedAt: start + 7 * DAY + 6 * HOUR }
  })
}

interface SeedNotification {
  id: string
  userId: string
  kind: NotificationKind
  severity: Notification['severity']
  title: string
  body?: string
  accountId: string
  resourceId?: string
  jobId?: string
  link?: string
  tab?: string
  ago: number
  read?: boolean
}

const SEEDS: readonly SeedNotification[] = [
  { id: 'ntf_dana_drift', userId: 'dana', kind: 'drift.found', severity: 'warning', title: 'api-01 drifted from what was asked for', body: 'Its memory is 16 GB where 8 GB was asked for, changed outside the console. Revert it, accept it or ignore it on its Drift tab.', accountId: COMPUTE, resourceId: 'vm-demo02', tab: 'drift', ago: 40 * MINUTE },
  { id: 'ntf_dana_alarm', userId: 'dana', kind: 'drift.found', severity: 'info', title: 'web-01 runs more CPUs than it asked for', body: 'The provider reports what the request set, plus a change made by hand. Its Drift tab says what changed.', accountId: COMPUTE, resourceId: 'vm-demo01', tab: 'drift', ago: 26 * HOUR },
  { id: 'ntf_gonzalo_quota', userId: 'gonzalo', kind: 'quota.near-limit', severity: 'warning', title: 'Compute used 80% of its virtual machine quota', body: 'Ask for more on the quotas of Identity and access before a create is refused.', accountId: COMPUTE, link: '/iam/quotas/resources.vm', ago: 5 * HOUR },
  { id: 'ntf_gonzalo_attention', userId: 'gonzalo', kind: 'job.needs-attention', severity: 'warning', title: 'VM restart paused on a failed step', body: 'The apply step failed. Retry it, skip it or abort the request on its page.', accountId: COMPUTE, jobId: 'job_demo_attention', ago: 2 * HOUR },
  { id: 'ntf_gonzalo_done', userId: 'gonzalo', kind: 'job.succeeded', severity: 'info', title: 'VM restart succeeded', accountId: COMPUTE, jobId: 'job_ib5qngxpbv', ago: 30 * HOUR, read: true },
  { id: 'ntf_mateo_done', userId: 'mateo', kind: 'job.succeeded', severity: 'info', title: 'VM restart succeeded', accountId: COMPUTE, jobId: 'job_8vhxiwrwvq', ago: 20 * HOUR },
  { id: 'ntf_raj_approval', userId: 'raj', kind: 'approval.requested', severity: 'warning', title: 'Ana Costa asks for an approval: MongoDB upgrade', body: 'It waits for data-approvers. Approve or reject it on the request’s page.', accountId: DATA, jobId: 'job_demo_approval', ago: 3 * HOUR },
  { id: 'ntf_ana_failed', userId: 'ana', kind: 'job.failed', severity: 'critical', title: 'Create VM failed', body: 'A step could not find what it needed. Its page says which, and runs it again with changes.', accountId: DATA, jobId: 'job_83amhygy2c', ago: 4 * HOUR },
]

/** The chat identities the demo starts with: Dana in Slack, Gonzalo in Teams, Mateo in Slack. */
const SEEDED_LINKS: readonly StoredChatLink[] = [
  { id: 'clk_dana_slack', userId: 'dana', platform: 'slack', workspaceId: 'T0FIRM01', chatUserId: 'U0DANA42', chatUserName: 'dana.reyes', linkedAt: '2026-10-02T09:12:00.000Z' },
  { id: 'clk_gonzalo_teams', userId: 'gonzalo', platform: 'teams', workspaceId: 'firm.onmicrosoft.com', chatUserId: '29:gonzalo-munoz', chatUserName: 'Gonzalo Munoz', linkedAt: '2026-09-28T14:40:00.000Z' },
  { id: 'clk_mateo_slack', userId: 'mateo', platform: 'slack', workspaceId: 'T0FIRM01', chatUserId: 'U0MATEO7', chatUserName: 'mateo.silva', linkedAt: '2026-10-05T08:00:00.000Z' },
]

function emptyState(now: number): NotificationsState {
  return { items: [], delivered: {}, seq: 0, settings: {}, links: SEEDED_LINKS.map((l) => ({ ...l })), outbox: [], seededAt: now }
}

/** The members of an account, by the groups that hold them. */
function membersOf(data: MockData, accountId: string): string[] {
  return data.directory.people.map((p) => p.id).filter((id) => rolesOf(data.directory, accountId, id).length > 0)
}

/** The state of these routes, made with the seeds on first use. */
export function notificationsStateOf(data: MockData, now: number): NotificationsState {
  const st = (data.console ??= emptyConsoleState())
  if (st.notifications) return st.notifications
  const state = emptyState(now)
  st.notifications = state
  for (const s of SEEDS) {
    let wfrn: string | undefined
    let link = s.link ?? (s.jobId ? `/jobs/${s.jobId}` : '/')
    if (s.resourceId) {
      const r = data.resources.find((x) => x.id === s.resourceId)
      const rt = r ? typeOf(data, r.type) : undefined
      if (r && rt) {
        wfrn = wfrnOfResource(data, { region: data.regionName }, rt, r)
        link = `/r/${rt.name}/${encodeURIComponent(wfrn)}${s.tab ? `?tab=${s.tab}` : ''}`
      }
    }
    const at = now - s.ago
    push(state, s.userId, `seed:${s.id}`, { id: s.id, kind: s.kind, severity: s.severity, title: s.title, body: s.body, accountId: s.accountId, region: data.regionName, wfrn, jobId: s.jobId, link, createdAt: iso(at), readAt: s.read ? iso(at + 10 * MINUTE) : undefined })
  }
  // the newest digest of each account, to every member who has it on (all, by default)
  const newest = digestWeeksOf(now)[0]
  for (const a of data.directory.accounts) {
    for (const userId of membersOf(data, a.accountId)) {
      push(state, userId, `digest:${a.accountId}:${newest.weekStart}`, { id: `ntf_${idFrom(`${userId}:digest:${a.accountId}:${newest.weekStart}`, 10)}`, kind: 'digest.ready', severity: 'info', title: `The weekly digest of ${a.name} is ready`, body: `What changed in ${a.name} in the week of ${newest.weekStart}: requests, drift, cost and access.`, accountId: a.accountId, link: `/digests/${newest.weekStart}`, createdAt: iso(Math.min(now, newest.generatedAt)) })
    }
  }
  return state
}

function push(state: NotificationsState, userId: string, key: string, n: Notification): StoredNotification | null {
  const k = `${userId}|${key}`
  if (state.delivered[k]) return null
  state.delivered[k] = true
  const item: StoredNotification = { ...n, userId, seq: ++state.seq }
  state.items.push(item)
  return item
}

/* ----------------------------------------------------------------------------
   Settings
   ------------------------------------------------------------------------- */

function linkedOf(state: NotificationsState, userId: string): { slack: boolean; teams: boolean } {
  const mine = state.links.filter((l) => l.userId === userId)
  return { slack: mine.some((l) => l.platform === 'slack'), teams: mine.some((l) => l.platform === 'teams') }
}

function storedSettingsOf(state: NotificationsState, userId: string): StoredSettings {
  const linked = linkedOf(state, userId)
  return (state.settings[userId] ??= { version: 1, email: true, slack: linked.slack, teams: linked.teams, rules: {} })
}

/** The settings document as the BFF answers it. */
export function settingsOf(data: MockData, state: NotificationsState, userId: string): NotificationSettings {
  const s = storedSettingsOf(state, userId)
  const linked = linkedOf(state, userId)
  return {
    version: s.version,
    channels: {
      email: { address: personOf(data.directory, userId)?.email, enabled: s.email },
      slack: { linked: linked.slack, enabled: linked.slack && s.slack },
      teams: { linked: linked.teams, enabled: linked.teams && s.teams },
    },
    rules: { ...s.rules },
  }
}

/** The channels a kind goes to for this person now. */
export function channelsFor(state: NotificationsState, userId: string, kind: NotificationKind): NotificationChannel[] {
  const s = storedSettingsOf(state, userId)
  return s.rules[kind] ?? defaultChannels(kind, linkedOf(state, userId))
}

/* ----------------------------------------------------------------------------
   Following what happens
   ------------------------------------------------------------------------- */

/**
 * Delivers what the console's jobs and the accounts' access requests did since the last look,
 * once per event and person, at the moment it happened, to the feed of everyone whose rule for
 * the kind keeps the console. Called at the end of every settle of the clock (./commands).
 */
export function followNotifications(data: MockData, now: number) {
  const state = notificationsStateOf(data, now)
  const dir = data.directory
  const nameOf = (userId: string) => personOf(dir, userId)?.name ?? userId
  const deliver = (userId: string, key: string, n: Omit<Notification, 'id'>) => {
    if (!personOf(dir, userId)) return
    const k = `${userId}|${key}`
    if (state.delivered[k]) return
    if (!channelsFor(state, userId, n.kind).includes('console')) {
      state.delivered[k] = true
      return
    }
    const at = Date.parse(n.createdAt)
    push(state, userId, key, { ...n, id: `ntf_${idFrom(k, 12)}`, readAt: now - at > READ_IF_OLDER_MS ? n.createdAt : undefined })
  }

  for (const [jobId, extra] of Object.entries(data.console?.jobs ?? {})) {
    if (extra.parentId) continue
    const job = data.jobs.find((j) => j.id === jobId)
    if (!job) continue
    const accountId = job.accountId ?? dir.accounts[0].accountId
    const what = job.title
    const base = { accountId, region: job.region, wfrn: job.wfrn, jobId, link: `/jobs/${jobId}` }
    for (const a of extra.approvals) {
      if (a.requestedAt) {
        const group = (dir.groups[accountId] ?? []).find((g) => g.name === a.group)
        for (const member of group?.members ?? []) {
          if (member === job.requester) continue
          deliver(member, `approval:${a.approvalId}:requested`, { ...base, kind: 'approval.requested', severity: 'warning', title: `${nameOf(job.requester)} asks for an approval: ${what}`, body: `It waits for ${a.group}. Approve or reject it on the request’s page.`, createdAt: a.requestedAt })
        }
      }
      if (a.decidedAt && (a.status === 'approved' || a.status === 'rejected')) {
        const by = a.decidedBy?.startsWith('approver:') ? `the ${a.group} approver` : nameOf(a.decidedBy ?? '')
        deliver(job.requester, `approval:${a.approvalId}:decided`, { ...base, kind: 'approval.decided', severity: a.status === 'rejected' ? 'warning' : 'info', title: `${what} was ${a.status}`, body: `Decided by ${by}${a.comment ? `: “${a.comment}”` : '.'}`, createdAt: a.decidedAt })
      }
    }
    if (extra.attention) {
      deliver(job.requester, `job:${jobId}:attention:${extra.attention.since}`, { ...base, kind: 'job.needs-attention', severity: 'warning', title: `${what} paused on a failed step`, body: `The ${extra.attention.step} step failed. Retry it, skip it or abort the request on its page.`, createdAt: extra.attention.since })
    }
    if (job.endedAt && job.status === 'succeeded') {
      deliver(job.requester, `job:${jobId}:succeeded`, { ...base, kind: 'job.succeeded', severity: 'info', title: `${what} succeeded`, createdAt: job.endedAt })
    } else if (job.endedAt && job.status === 'failed') {
      deliver(job.requester, `job:${jobId}:failed`, { ...base, kind: 'job.failed', severity: 'critical', title: `${what} failed`, body: job.failedStep ? `The ${job.failedStep} step failed. Its page says why, and runs it again with changes.` : 'Its page says why, and runs it again with changes.', createdAt: job.endedAt })
    }
  }

  for (const account of dir.accounts) {
    for (const r of accessRequestsOf(dir, account.accountId, now)) {
      const asked = `${roleLabel(r.role)} in ${account.name}`
      const base = { accountId: account.accountId, link: `/access-requests/${r.requestId}` }
      if (r.status !== 'expired') {
        const admins = membersOf(data, account.accountId).filter((id) => rolesOf(dir, account.accountId, id).includes(ADMIN_ROLE))
        for (const userId of new Set([...admins, ...(r.approvers ?? [])])) {
          if (userId === r.requester.userId) continue
          deliver(userId, `areq:${r.requestId}:created`, { ...base, kind: 'access-request.created', severity: 'info', title: `${r.requester.name ?? nameOf(r.requester.userId)} asks for ${asked}`, body: r.reason, createdAt: r.requestedAt })
        }
      }
      if ((r.status === 'approved' || r.status === 'rejected') && r.decidedAt) {
        deliver(r.requester.userId, `areq:${r.requestId}:decided`, { ...base, kind: 'access-request.decided', severity: r.status === 'rejected' ? 'warning' : 'info', title: `Your access request for ${asked} was ${r.status}`, body: r.decidedBy ? `Decided by ${nameOf(r.decidedBy)}${r.comment ? `: “${r.comment}”` : '.'}` : r.comment, createdAt: r.decidedAt })
      }
    }
  }
}

/** A person's feed, newest first, within the 90 days it is kept. */
export function feedOf(state: NotificationsState, userId: string, now: number): StoredNotification[] {
  return state.items.filter((n) => n.userId === userId && now - Date.parse(n.createdAt) <= KEPT_MS).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.seq - a.seq)
}

const publicOf = ({ userId: _u, seq: _s, ...n }: StoredNotification): Notification => n

/** The live stream's `notification` events for a person after a sequence number, and the last one. */
export function notificationEventsAfter(data: MockData, userId: string, afterSeq: number, now: number): { events: { id: string; event: LiveEvent<Notification> }[]; last: number } {
  const state = notificationsStateOf(data, now)
  const mine = state.items.filter((n) => n.userId === userId && n.seq > afterSeq).sort((a, b) => a.seq - b.seq)
  return { events: mine.map((n) => ({ id: `n${n.seq}`, event: { type: 'notification', at: n.createdAt, key: n.id, wfrn: n.wfrn, data: publicOf(n) } })), last: mine.length ? mine[mine.length - 1].seq : afterSeq }
}

/** The highest sequence delivered so far: a stream opened now starts after it. */
export function lastNotificationSeq(data: MockData, now: number): number {
  return notificationsStateOf(data, now).seq
}

/* ----------------------------------------------------------------------------
   Chat link tokens
   ------------------------------------------------------------------------- */

export interface ChatLinkClaims {
  platform: ChatPlatform
  workspaceId: string
  chatUserId: string
  chatUserName?: string
  /** seconds since the epoch */
  exp: number
  aud?: string
}

const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64url = (s: string) => {
  const b = s.replace(/-/g, '+').replace(/_/g, '/')
  return atob(b + '='.repeat((4 - (b.length % 4)) % 4))
}

/** The mock's stand-in for the BFF's signed token: a JWT whose signature is the fixed word `mock-signature`. */
export function mockChatLinkToken(claims: Omit<ChatLinkClaims, 'aud'> & { aud?: string }): string {
  return `${b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${b64url(JSON.stringify({ aud: CHAT_LINK_AUDIENCE, ...claims }))}.mock-signature`
}

function claimsOf(token: string, now: number): ChatLinkClaims | RouteAnswer {
  const parts = token.split('.')
  const bad: RouteAnswer = { status: 400, body: { error: 'chat-link.invalid', detail: 'This link is not one the console made. Ask the chat app for a new one with /console link.' } }
  if (parts.length !== 3 || parts[2] !== 'mock-signature') return bad
  let c: Partial<ChatLinkClaims>
  try {
    c = JSON.parse(unb64url(parts[1])) as Partial<ChatLinkClaims>
  } catch {
    return bad
  }
  if (c.aud !== CHAT_LINK_AUDIENCE || (c.platform !== 'slack' && c.platform !== 'teams') || typeof c.workspaceId !== 'string' || typeof c.chatUserId !== 'string' || typeof c.exp !== 'number') return bad
  if (c.exp * 1000 < now) return { status: 400, body: { error: 'chat-link.expired', detail: 'A link is good for ten minutes. Ask the chat app for a new one with /console link.' } }
  return c as ChatLinkClaims
}

/* ----------------------------------------------------------------------------
   Digests
   ------------------------------------------------------------------------- */

const DIGEST_TEXT: Record<string, ((week: string) => Omit<Digest, 'accountId' | 'weekStart' | 'weekEnd' | 'generatedAt' | 'source'>)[]> = {
  [COMPUTE]: [
    () => ({
      headline: 'A busy, healthy week: 38 requests, two of them failed and were fixed, and one machine drifted.',
      sections: [
        { kind: 'changes', title: 'Changes', text: 'The team ran 38 requests, 31 of them from the console. 36 succeeded on their first run; two restarts failed on the apply step and went through once retried. Four production changes waited for compute-approvers, for 22 minutes on average.', facts: { requests: 38, fromTheConsole: 31, succeeded: 36, failedThenFixed: 2, approvals: 4, averageApprovalMinutes: 22 } },
        { kind: 'drift', title: 'Drift', text: 'One new finding: api-01’s memory was doubled by hand, outside the console. It is still open. Two older findings were reverted.', facts: { newFindings: 1, open: 1, reverted: 2 }, highlights: [{ text: 'api-01 runs 16 GB where 8 GB was asked for', link: '/r/vm/wfrn%3Awf%3Acompute%3Aglobal%3A123456789012%3Avm%2Fvm-demo02?tab=drift' }] },
        { kind: 'cost', title: 'Cost', text: 'The account cost $4,212 this week, 6% more than the week before, mostly from the two new batch machines. Rightsizing finds $310 a month in idle machines.', facts: { spend: 4212, currency: 'USD', changePercent: 6, idleSavingsPerMonth: 310 } },
        { kind: 'access', title: 'Access', text: 'No one joined or left. One access request, for Admin, expired without an answer after 30 days; nothing risky was granted.', facts: { joined: 0, left: 0, requestsExpired: 1, adminsNow: 1 }, highlights: [{ text: 'Mateo Silva’s request for Admin expired', link: '/access-requests/areq_002' }] },
      ],
    }),
    () => ({
      headline: 'A quiet week: 21 requests, all succeeded, and no new drift.',
      sections: [
        { kind: 'changes', title: 'Changes', text: '21 requests ran, every one succeeded. Two production restarts waited for compute-approvers.', facts: { requests: 21, succeeded: 21, approvals: 2 } },
        { kind: 'drift', title: 'Drift', text: 'No new findings. Two were open at the end of the week.', facts: { newFindings: 0, open: 2 } },
        { kind: 'cost', title: 'Cost', text: 'The account cost $3,974, 1% less than the week before.', facts: { spend: 3974, currency: 'USD', changePercent: -1 } },
        { kind: 'access', title: 'Access', text: 'No changes to who holds what.', facts: { joined: 0, left: 0 } },
      ],
    }),
  ],
  [DATA]: [
    () => ({
      headline: 'One upgrade waits for data-approvers, and a create failed on a missing image.',
      sections: [
        { kind: 'changes', title: 'Changes', text: 'Nine requests ran. A MongoDB upgrade of orders-db waits for data-approvers, and one create failed because the image it named was gone.', facts: { requests: 9, succeeded: 7, failed: 1, waiting: 1 }, highlights: [{ text: 'The MongoDB upgrade waits for an approval', link: '/jobs/job_demo_approval' }] },
        { kind: 'drift', title: 'Drift', text: 'No drift on the account’s databases.', facts: { newFindings: 0, open: 0 } },
        { kind: 'cost', title: 'Cost', text: 'The account cost $2,180, 3% more than the week before.', facts: { spend: 2180, currency: 'USD', changePercent: 3 } },
        { kind: 'access', title: 'Access', text: 'No changes to who holds what.', facts: { joined: 0, left: 0 } },
      ],
    }),
    () => ({
      headline: 'A quiet week: four requests, all succeeded.',
      sections: [
        { kind: 'changes', title: 'Changes', text: 'Four requests ran and succeeded.', facts: { requests: 4, succeeded: 4 } },
        { kind: 'drift', title: 'Drift', text: 'No drift.', facts: { newFindings: 0 } },
        { kind: 'cost', title: 'Cost', text: 'The account cost $2,116, the same as the week before.', facts: { spend: 2116, currency: 'USD', changePercent: 0 } },
        { kind: 'access', title: 'Access', text: 'No changes to who holds what.', facts: { joined: 0, left: 0 } },
      ],
    }),
  ],
}

/** An account's digests, newest first: the newest by the copilot, the older one by the template. */
export function digestsOf(state: NotificationsState, accountId: string): Digest[] {
  const texts = DIGEST_TEXT[accountId] ?? []
  return digestWeeksOf(state.seededAt)
    .slice(0, texts.length)
    .map((w, i) => ({ accountId, weekStart: w.weekStart, weekEnd: w.weekEnd, generatedAt: iso(w.generatedAt), source: i === 0 ? ('assistant' as const) : ('rules' as const), ...texts[i](w.weekStart) }))
}

/* ----------------------------------------------------------------------------
   Commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

/** Applies a journaled `notification.*` or `chat-link.*` command, when it runs and when the journal replays it. */
export function applyNotificationCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  followNotifications(data, cmd.at)
  const state = notificationsStateOf(data, cmd.at)
  const body = (cmd.body && typeof cmd.body === 'object' ? cmd.body : {}) as Record<string, unknown>
  const unread = () => feedOf(state, cmd.userId, cmd.at).filter((n) => !n.readAt).length
  switch (cmd.kind) {
    case 'notification.read': {
      const ids = new Set(Array.isArray(body.ids) ? body.ids.filter((x): x is string => typeof x === 'string') : [])
      for (const n of state.items) {
        if (n.userId !== cmd.userId || n.readAt) continue
        if (body.all === true ? Date.parse(n.createdAt) <= cmd.at : ids.has(n.id)) n.readAt = iso(cmd.at)
      }
      return { status: 200, body: { unread: unread() } }
    }
    case 'notification.settings': {
      const s = storedSettingsOf(state, cmd.userId)
      const channels = (body.channels ?? {}) as Record<string, { enabled?: unknown }>
      s.email = channels.email?.enabled !== false
      s.slack = channels.slack?.enabled === true
      s.teams = channels.teams?.enabled === true
      s.rules = (body.rules ?? {}) as NotificationSettings['rules']
      s.version++
      return { status: 200, body: settingsOf(data, state, cmd.userId), headers: { ETag: String(s.version) } }
    }
    case 'chat-link.create': {
      const claims = body.claims as ChatLinkClaims
      const existing = state.links.find((l) => l.platform === claims.platform && l.workspaceId === claims.workspaceId && l.chatUserId === claims.chatUserId)
      if (existing) {
        if (existing.userId !== cmd.userId) return { status: 409, body: { error: 'chat-link.taken', detail: 'This chat identity is linked to someone else in the console.' } }
        return { status: 201, body: publicLink(existing) }
      }
      const s = storedSettingsOf(state, cmd.userId)
      const link: StoredChatLink = { id: `clk_${idFrom(`${cmd.userId}:${claims.platform}:${claims.workspaceId}:${claims.chatUserId}`, 10)}`, userId: cmd.userId, platform: claims.platform, workspaceId: claims.workspaceId, chatUserId: claims.chatUserId, chatUserName: claims.chatUserName, linkedAt: iso(cmd.at) }
      state.links.push(link)
      // a newly linked chat is turned on: the defaults send it the loud kinds
      s[claims.platform] = true
      s.version++
      return { status: 201, body: publicLink(link) }
    }
    case 'chat-link.delete': {
      const link = state.links.find((l) => l.id === body.linkId && l.userId === cmd.userId)
      if (!link) return { status: 404, body: { error: 'chat-link.not-found' } }
      state.links = state.links.filter((l) => l !== link)
      const s = storedSettingsOf(state, cmd.userId)
      if (!linkedOf(state, cmd.userId)[link.platform]) s[link.platform] = false
      s.version++
      return { status: 204 }
    }
    default:
      return { status: 404, body: { error: `No command ${cmd.kind}` } }
  }
}

const publicLink = ({ userId: _u, ...l }: StoredChatLink): ChatLink => l

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

function pageOf<T>(items: T[], params: URLSearchParams): { items: T[]; next: string | null } {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null }
}

const notAllowed = (method: string, path: string): RouteAnswer => ({ status: 405, body: { error: `${method} is not allowed on ${path}` } })

/** The version an If-Match names: `3`, `"3"` or `W/"3"`. */
function versionOf(header: string | undefined): number | null {
  const m = header ? /(\d+)/.exec(header) : null
  return m ? Number(m[1]) : null
}

/**
 * A decision of the console's own IAM alone: the digests are the BFF's, so the platform's role
 * for the account never decides them (as with resource groups and the identity service's own).
 */
function consoleDecision(data: MockData, accountId: string, userId: string, action: string, resource: string): Decision {
  const roles = rolesOf(data.directory, accountId, userId)
  const verdict = roles.length ? evaluateRoles(data.directory, accountId, roles, action, resource) : { effect: null }
  if (verdict.effect === 'allow') return { allowed: true }
  return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: roles.length ? `no policy allows ${action} on ${resource}` : 'not a member of this account', role: roles[0], requestable: false } }
}

/** Answers the notifications, notification settings, chat links and digests paths, or null for any other path. */
export function notificationRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!/^\/console\/v1\/(notifications|notification-settings|chat-links|digests)(\/|:|$)/.test(path)) return null
  const s = ctx.session
  const now = ctx.now
  followNotifications(data, now)
  const state = notificationsStateOf(data, now)
  const command = (kind: `notification.${string}` | `chat-link.${string}`, body: Record<string, unknown>) => journaled(data, ctx, { at: now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type: 'notification', body, ids: {} })
  const refused = (d: Decision): RouteAnswer => ({ status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(`${now}:${path}`, 8)}` } })

  if (path === '/console/v1/notifications') {
    if (method !== 'GET') return notAllowed(method, path)
    const kind = params.get('kind')
    if (kind && !isNotificationKind(kind)) return { status: 400, body: { error: 'kind.unknown', detail: `No notification kind ${kind}` } }
    const accountId = params.get('accountId')
    const unreadOnly = params.get('unread') === 'true'
    const feed = feedOf(state, s.userId, now)
    const matching = feed.filter((n) => (!unreadOnly || !n.readAt) && (!kind || n.kind === kind) && (!accountId || n.accountId === accountId)).map(publicOf)
    return { status: 200, body: { ...pageOf(matching, params), unread: feed.filter((n) => !n.readAt).length } }
  }
  if (path === '/console/v1/notifications:read') {
    if (method !== 'POST') return notAllowed(method, path)
    const b = ctx.body
    const all = b.all === true
    const ids = Array.isArray(b.ids) ? b.ids : undefined
    if (!all && !ids) return { status: 400, body: { error: 'read.what', detail: 'Name the notifications to mark read in ids, or send all: true' } }
    if (ids && (ids.length > 200 || ids.some((x) => typeof x !== 'string'))) return { status: 400, body: { error: 'read.ids', detail: 'ids holds at most 200 notification ids' } }
    return command('notification.read', all ? { all: true } : { ids })
  }

  if (path === '/console/v1/notification-settings') {
    if (method === 'GET') {
      const doc = settingsOf(data, state, s.userId)
      return { status: 200, body: doc, headers: { ETag: String(doc.version) } }
    }
    if (method !== 'PUT') return notAllowed(method, path)
    const wanted = versionOf(ctx.headers['if-match'])
    if (wanted === null) return { status: 400, body: { error: 'version.required', detail: 'Send If-Match with the version you read' } }
    const current = settingsOf(data, state, s.userId)
    if (wanted !== current.version) return { status: 409, body: { error: 'version.moved', detail: 'Your notification settings changed since you read them', current } }
    const b = ctx.body
    const channels = (b.channels && typeof b.channels === 'object' ? b.channels : null) as Record<string, { enabled?: unknown } | undefined> | null
    if (!channels || typeof channels.email?.enabled !== 'boolean' || typeof channels.slack?.enabled !== 'boolean' || typeof channels.teams?.enabled !== 'boolean') return { status: 400, body: { error: 'channels.required', detail: 'channels names email, slack and teams, each with enabled' } }
    for (const p of ['slack', 'teams'] as const) {
      if (channels[p]?.enabled === true && !current.channels[p].linked) return { status: 400, body: { error: 'channel.not-linked', detail: `${p === 'slack' ? 'Slack' : 'Teams'} is not linked: link it with /console link first` } }
    }
    const rules = (b.rules && typeof b.rules === 'object' && !Array.isArray(b.rules) ? b.rules : {}) as Record<string, unknown>
    for (const [k, v] of Object.entries(rules)) {
      if (!isNotificationKind(k)) return { status: 400, body: { error: 'rules.kind', detail: `No notification kind ${k}` } }
      if (!Array.isArray(v) || v.some((c) => !isChannel(c)) || new Set(v).size !== v.length) return { status: 400, body: { error: 'rules.channels', detail: `The channels of ${k} are each one of ${NOTIFICATION_CHANNELS.join(', ')}, once` } }
    }
    return command('notification.settings', { channels: { email: { enabled: channels.email?.enabled }, slack: { enabled: channels.slack?.enabled }, teams: { enabled: channels.teams?.enabled } }, rules })
  }
  if (path === '/console/v1/notification-settings:test') {
    if (method !== 'POST') return notAllowed(method, path)
    const channel = ctx.body.channel
    if (!isChannel(channel)) return { status: 400, body: { error: 'channel.unknown', detail: `channel is one of ${NOTIFICATION_CHANNELS.join(', ')}` } }
    const doc = settingsOf(data, state, s.userId)
    if (channel === 'slack' || channel === 'teams') {
      if (!doc.channels[channel].linked) return { status: 409, body: { error: 'channel.not-linked', detail: `${channel === 'slack' ? 'Slack' : 'Teams'} is not linked` } }
      if (!doc.channels[channel].enabled) return { status: 409, body: { error: 'channel.disabled', detail: `${channel === 'slack' ? 'Slack' : 'Teams'} is turned off in your settings` } }
    }
    if (channel === 'email' && !doc.channels.email.enabled) return { status: 409, body: { error: 'channel.disabled', detail: 'Email is turned off in your settings' } }
    state.outbox.push({ userId: s.userId, channel, at: now, test: true })
    return { status: 202 }
  }

  if (path === '/console/v1/chat-links') {
    if (method === 'GET') return { status: 200, body: { items: state.links.filter((l) => l.userId === s.userId).sort((a, b) => a.linkedAt.localeCompare(b.linkedAt)).map(publicLink) } }
    if (method !== 'POST') return notAllowed(method, path)
    const token = ctx.body.token
    if (typeof token !== 'string' || !token) return { status: 400, body: { error: 'token.required', detail: 'The token the chat app gave you' } }
    const claims = claimsOf(token, now)
    if ('status' in claims) return claims
    return command('chat-link.create', { claims })
  }
  const m = /^\/console\/v1\/chat-links\/([^/]+)$/.exec(path)
  if (m) {
    if (method !== 'DELETE') return notAllowed(method, path)
    const linkId = decodeURIComponent(m[1])
    if (!state.links.some((l) => l.id === linkId && l.userId === s.userId)) return { status: 404, body: { error: 'chat-link.not-found', detail: `You have no chat link ${linkId}` } }
    return command('chat-link.delete', { linkId })
  }

  /* digests: digests:get on the account's digests, which every managed role reads */
  const d = /^\/console\/v1\/digests(?:\/([^/]+))?$/.exec(path)
  if (d) {
    if (method !== 'GET') return notAllowed(method, path)
    const week = d[1] ? decodeURIComponent(d[1]) : undefined
    const decision = consoleDecision(data, s.accountId, s.userId, 'digests:get', `wfrn:${DIRECTORY_PARTITION}:digests::${s.accountId}:${week ? `digest/${week}` : '*'}`)
    if (!decision.allowed) return refused(decision)
    const all = digestsOf(state, s.accountId)
    if (!week) return { status: 200, body: pageOf(all.map((x): DigestSummary => ({ weekStart: x.weekStart, headline: x.headline, generatedAt: x.generatedAt })), params) }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(week)) return { status: 400, body: { error: 'weekStart.format', detail: 'weekStart is a date, such as 2026-10-12' } }
    const one = all.find((x) => x.weekStart === week)
    return one ? { status: 200, body: one } : { status: 404, body: { error: 'digest.not-found', detail: `No digest of ${accountOf(data.directory, s.accountId)?.name ?? s.accountId} for the week of ${week}` } }
  }
  return null
}
