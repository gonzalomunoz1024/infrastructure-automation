import { describe, expect, it } from 'vitest'
import { cancelMessage } from '../pages/JobPage'

/*
 * What the job page's cancel dialog promises. Both engines cancel without compensating (the
 * execution port: "Stop a job and the children it is waiting on; nothing is compensated"); only
 * Abort, on a run a failed step paused, runs the compensation steps.
 */

describe('the cancel dialog', () => {
  it('says the job stops and nothing is rolled back', () => {
    const message = cancelMessage({ title: 'Restart web-1042' })
    expect(message).toContain('Restart web-1042 stops: no further step starts, a wait ends at once')
    expect(message).toContain('a playbook, plan or worker call in progress is stopped')
    expect(message).toContain('Nothing is rolled back: what the finished steps changed stays changed.')
    expect(message).toContain('Only Abort, offered when a failed step pauses the run, rolls back.')
    // the old promise, which no engine keeps
    expect(message).not.toMatch(/compensated/i)
    expect(message).not.toContain('after the current step')
  })

  it('points a paused run at Abort, which is right there', () => {
    const message = cancelMessage({ title: 'Restart web-1042', attention: { step: 'restart', options: ['retry', 'edit-input', 'skip', 'abort'] } })
    expect(message).toContain('Nothing is rolled back')
    expect(message).toContain('To roll them back instead, choose Abort for restart under Needs attention.')
    expect(message).not.toContain('Only Abort')
  })

  it('does not offer Abort when the paused run does not', () => {
    const message = cancelMessage({ title: 'Restart web-1042', attention: { step: 'restart', options: ['retry', 'skip'] } })
    expect(message).not.toContain('choose Abort')
    expect(message).toContain('Only Abort, offered when a failed step pauses the run, rolls back.')
  })
})
