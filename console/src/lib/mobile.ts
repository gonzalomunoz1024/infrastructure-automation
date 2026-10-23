import { page, type ListParams } from './api'
import { idOfWfrn, partsOfWfrn, singularOf, type ConsoleType } from './descriptors'
import type { BffApproval } from './types'

/*
 * The phone view (WP 9.18): the approvals waiting for the person (`GET /console/v1/approvals`,
 * decided through the request's `approvals:decide` as the request page decides them), the
 * account's drift and the person's own requests. The words it shows live here, so the page and
 * its tests agree.
 */

export const mobileApi = {
  /** the approvals waiting for the person, and the ones on requests they asked for */
  approvals: (params?: { status?: 'pending' | 'approved' | 'rejected' | 'expired' } & ListParams) => page<BffApproval>('/console/v1/approvals', params),
}

/** The type a wfrn names, among the console's types. */
export function typeOfWfrn(types: readonly ConsoleType[], wfrn: string | undefined): ConsoleType | undefined {
  const name = wfrn ? partsOfWfrn(wfrn)?.type : undefined
  return name ? types.find((t) => t.name === name) : undefined
}

/** What an approval is about, in a few words: "Restart virtual machine vm-gen-004". */
export function approvalHeadline(a: Pick<BffApproval, 'workflow' | 'wfrn' | 'message'>, types: readonly ConsoleType[]): string {
  const t = typeOfWfrn(types, a.wfrn)
  const id = a.wfrn ? idOfWfrn(a.wfrn) : null
  const verb = verbOfWorkflow(a.workflow, t)
  if (verb && t) return `${verb} ${singularOf(t.title).toLowerCase()}${id ? ` ${id}` : ''}`
  if (id) return `A change to ${id}`
  return a.message ?? 'A change waiting for approval'
}

/** "restart-vm" → "Restart", "vm-create" → "Create": the workflow's verb, without the type's name. */
export function verbOfWorkflow(workflow: string | undefined, type?: Pick<ConsoleType, 'name'>): string | null {
  if (!workflow) return null
  const words = workflow
    .split(/[-_.]+/)
    .filter((w) => w && w !== type?.name && !/^v\d+$/.test(w))
  if (!words.length) return null
  const text = words.join(' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** The sections of the phone view, in the order of its tab bar. */
export const MOBILE_VIEWS = [
  { id: 'approvals', label: 'Approvals', icon: 'approval' },
  { id: 'drift', label: 'Drift', icon: 'pulse' },
  { id: 'requests', label: 'Requests', icon: 'job' },
] as const

export type MobileView = (typeof MOBILE_VIEWS)[number]['id']

export function mobileViewOf(value: string | undefined): MobileView {
  return MOBILE_VIEWS.some((v) => v.id === value) ? (value as MobileView) : 'approvals'
}
