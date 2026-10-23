import { internal } from './tokens.js'
import { URLS } from './stack.js'

/*
 * The drift service's operator route: check one watched resource now instead of at its next
 * interval (15 minutes for a VM), with the harness's service token (audience console-drift-service).
 */
export async function checkNow(wfrn: string): Promise<unknown> {
  const { status, body } = await internal(`${URLS.drift}/internal/watches/${encodeURIComponent(wfrn)}:check`, 'console-drift-service', { method: 'POST' })
  if (status >= 300) throw new Error(`the drift service refused a check of ${wfrn}: ${status} ${JSON.stringify(body)}`)
  return body
}

export async function watch(wfrn: string): Promise<{ status: number; body: unknown }> {
  return internal(`${URLS.drift}/internal/watches/${encodeURIComponent(wfrn)}`, 'console-drift-service')
}
