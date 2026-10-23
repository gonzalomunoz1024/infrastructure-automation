/*
 * The platform shapes the console reads. The ones the kit shares are re-exported from it; the
 * rest are the console's own views of the platform's answers.
 */

export type { CatalogEntry, ConsoleHints, JobStatus, JobStep, JobSummary, Lineage, LifecycleClass, Problem, Resource, ResourceType, Schema, XConsole } from '@iaf/console-kit'
import type { JobStep, JobSummary } from '@iaf/console-kit'

/** GET /api/v1/me */
export interface Me {
  id: string
  name: string
  email?: string
  roles?: string[]
  teams?: string[]
  admin?: boolean
}

/** A region as the console keeps it (P-07): the file's metadata and spec flattened. */
export interface Region {
  name: string
  title: string
  partition: string
  status?: string
  environments?: string[]
}

export interface JobDetail extends JobSummary {
  steps?: JobStep[]
  input?: Record<string, unknown>
  outputs?: Record<string, unknown>
  children?: JobSummary[]
  error?: { class?: string; message?: string; step?: string }
}

/** The event envelope of the platform feed (platform-changes, section 11). */
export interface PlatformEvent {
  specVersion?: string
  dataVersion?: string
  id: string
  key?: string
  seq: number
  type: string
  at: string
  aggregate?: string
  aggregateId?: string
  actor?: string
  onBehalfOf?: string
  context?: { accountId?: string; region?: string; wfrn?: string; consoleRequestId?: string }
  traceparent?: string
  data?: unknown
}

/** One page of a list (P-05): the rows and the cursor of the next page, if any. */
export interface Page<T> {
  items: T[]
  next: string | null
}

export interface Health {
  status: string
  components?: Record<string, { status: string }>
}
