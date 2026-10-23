import { test } from '@playwright/test'

/*
 * The integration findings that block a step of the journey (README.md, "Findings"). A step a
 * finding blocks is marked test.fixme with the finding's link, so the rest of the journey still
 * runs. Once a re-pinned commit fixes one, run with E2E_ASSUME_FIXED=F-9 (or "all") to see the
 * blocked steps run, then take the finding out of OPEN.
 */

const README = 'https://github.com/gonzalomunoz1024/infrastructure-automation/blob/console-e2e/README.md'

export const FINDINGS = {
  'F-4': { owner: 'console-bff', summary: 'no BFF commit serves both the cost routes (console-bff--cost) and the drift routes (console-bff--drift)' },
  'F-9': { owner: 'console-web', summary: 'the console reads GET /console/v1/types as a bare list; the contract and the BFF answer { items }, so every page is blank after sign-in' },
  'F-13': { owner: 'console-bff', summary: 'a resource\u2019s ETag is the state version alone, so a conditional read answers 304 while its request moved on (running to waiting-approval)' },
  'F-14': { owner: 'console-web', summary: 'a deleted resource\u2019s page says it cannot load it instead of showing its tombstone: it reads the platform record by the wfrn\u2019s console id, which the platform does not know' },
  'F-15': { owner: 'console-web', summary: 'console-web--phase-9-cost, the only web with the cost panel, lacks F-9\u2019s fix (3950f17 on console-web--phase-5), so its pages are blank in live mode' },
  'F-12': { owner: 'console-web', summary: 'the drift tab stops following a revert when its job succeeded, before the finding closes, and shows it open until a reload' },
} as const

export type FindingId = keyof typeof FINDINGS

/** Findings no pinned commit fixes yet. */
const OPEN: ReadonlySet<FindingId> = new Set<FindingId>(['F-15'])

const assumedFixed = new Set((process.env.E2E_ASSUME_FIXED ?? '').split(',').map((s) => s.trim()).filter(Boolean))

export function linkOf(id: FindingId): string {
  return `${README}#${id.toLowerCase()}`
}

/** The open findings among these, unless the run assumes them fixed. */
export function blocking(...ids: FindingId[]): FindingId[] {
  if (assumedFixed.has('all')) return []
  return ids.filter((id) => OPEN.has(id) && !assumedFixed.has(id))
}

/**
 * Marks the running test fixme while any of these findings is open, naming each with its link.
 * Called first in a test's body.
 */
export function fixmeWhileOpen(...ids: FindingId[]): void {
  const open = blocking(...ids)
  for (const id of ids) test.info().annotations.push({ type: 'finding', description: `${id} (${FINDINGS[id].owner}): ${FINDINGS[id].summary} ${linkOf(id)}` })
  test.fixme(open.length > 0, open.map((id) => `blocked by ${id}, ${FINDINGS[id].owner}: ${FINDINGS[id].summary} (${linkOf(id)})`).join('; '))
}
