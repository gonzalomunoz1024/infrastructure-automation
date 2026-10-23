import { get } from './api'
import { BFF_BASE } from './bff'
import type { Denial } from './types'

/*
 * The failure explainer (WP 9.2): why a job failed, or why it waits on a failed step, in plain
 * words, with the fix to try (hub decision 27, `GET /console/v1/jobs/{jobId}/explanation`). Rules
 * over the platform's error classes answer first; the copilot answers what they cannot. A fix's
 * `input` is a merge over the job's input that the console sends, with `fromStep`, as the body of
 * the phase 4 retry (the platform's `:rerun`), so the original input and its secrets never travel
 * back. Asking for an explanation retries nothing.
 */

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

export type FixConfidence = 'high' | 'medium' | 'low'

/** What the person can send to try again: a merge over the job's input, and the step to run from. */
export interface RetryFix {
  input?: Record<string, unknown>
  fromStep?: string
}

export interface FailureFix extends RetryFix {
  /** the fix to try, in one or two sentences */
  summary: string
  confidence: FixConfidence
}

export interface JobExplanation {
  jobId: string
  status: 'failed' | 'needs-attention'
  cause: {
    /** the platform's error class */
    class?: string
    /** what went wrong, in plain words */
    title: string
    detail?: string
  }
  failedStep?: { id: string; name?: string; attempt?: number; message?: string }
  fix?: FailureFix
  /** whether this person may run it again (judged like the first request) */
  retry?: { allowed: boolean; denied?: Denial }
  /** jobs of the same workflow that failed with the same class in the account, 30 days */
  similar?: { count: number; lastFixed?: { jobId?: string; by?: string; at?: string } }
  /** rules over the error classes, or the copilot for what they cannot say */
  source: 'rules' | 'assistant'
  links?: { title: string; url: string }[]
}

/** Inputs no fix may change, whoever wrote it: where the request runs. The BFF refuses them too, with the subject and sensitive fields. */
const NEVER_FIXED = new Set(['accountId', 'region', 'wfrn'])

/**
 * The retry body a fix asks for, or undefined when it asks for nothing beyond running again. Only
 * the rules' fixes may change the input: a copilot's reading is written by a model that has read
 * provider text, so its fix can at most run a step again, and nothing a prompt slipped into a
 * failure message can change a value the person might not notice.
 */
export function retryBodyOf(fix: RetryFix | undefined, source: JobExplanation['source'] = 'rules'): RetryFix | undefined {
  if (!fix) return undefined
  const kept = source === 'rules' && fix.input ? Object.entries(fix.input).filter(([k]) => !NEVER_FIXED.has(k)) : []
  const input = kept.length ? Object.fromEntries(kept) : undefined
  if (!input && !fix.fromStep) return undefined
  return { ...(input ? { input } : {}), ...(fix.fromStep ? { fromStep: fix.fromStep } : {}) }
}

/** The hosts an explanation may link out to (runbooks, provider docs): `VITE_CONSOLE_DOCS_HOSTS`, comma-separated; none unless configured. */
export const DOCS_HOSTS: readonly string[] = String(import.meta.env.VITE_CONSOLE_DOCS_HOSTS ?? '')
  .split(',')
  .map((h) => h.trim().toLowerCase())
  .filter(Boolean)

/** A link the console may follow: its own route (`#/...`), or an https page on a docs host, opened in a new tab; anything else is dropped. */
export function linkOf(url: string, hosts: readonly string[] = DOCS_HOSTS): { href: string; external: boolean } | null {
  if (url.startsWith('#/')) return { href: url, external: false }
  if (!/^https:\/\/[^\s]+$/i.test(url)) return null
  try {
    return hosts.includes(new URL(url).hostname.toLowerCase()) ? { href: url, external: true } : null
  } catch {
    return null
  }
}

export const explainApi = {
  job: (jobId: string) => get<JobExplanation>(b(`/jobs/${enc(jobId)}/explanation`)),
}
