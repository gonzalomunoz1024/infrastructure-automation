import { get } from './api'
import { BFF_BASE } from './bff'
import type { Denial, RetryFix } from './types'

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

/** A fix is the phase 4 retry's own body (a merge over the job's input, and the step to run from), in words. */
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

/** The retry body a fix asks for, or undefined when it asks for nothing beyond running again. */
export function retryBodyOf(fix: RetryFix | undefined): RetryFix | undefined {
  if (!fix) return undefined
  const input = fix.input && Object.keys(fix.input).length ? fix.input : undefined
  if (!input && !fix.fromStep) return undefined
  return { ...(input ? { input } : {}), ...(fix.fromStep ? { fromStep: fix.fromStep } : {}) }
}

/** A link the console may follow: its own route (`#/...`), or an https page opened in a new tab; anything else is dropped. */
export function linkOf(url: string): { href: string; external: boolean } | null {
  if (url.startsWith('#/')) return { href: url, external: false }
  return /^https:\/\/[^\s]+$/i.test(url) ? { href: url, external: true } : null
}

export const explainApi = {
  job: (jobId: string) => get<JobExplanation>(b(`/jobs/${enc(jobId)}/explanation`)),
}
