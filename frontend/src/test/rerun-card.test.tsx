import { renderToString } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { RerunCard, rerunBlocker, rerunRequest } from '../components/RerunCard'

/*
 * "Re-run from a step" on the job page's Time travel tab: the body it sends to
 * POST /jobs/{id}:rerun (the backend merges the input onto the job's own), what it refuses before
 * sending, and the jobs the backend would refuse (a running job, a batch), which get a sentence
 * instead of the form.
 */

const STEPS = [
  { id: 'image.create', name: 'Build image', status: 'succeeded' },
  { id: 'clone', name: 'Clone from the image', status: 'succeeded' },
  { id: 'register', name: 'Register the VM', status: 'failed' },
]
const INPUT = { name: 'web-1044', cpu: 2 }

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the re-run request', () => {
  it('reuses the job input when the box is empty', () => {
    expect(rerunRequest('clone', '')).toEqual({ request: { fromStep: 'clone' } })
    expect(rerunRequest('clone', '  \n ')).toEqual({ request: { fromStep: 'clone' } })
  })

  it('sends the object you write, which the backend merges onto the job input', () => {
    expect(rerunRequest('clone', '{ "name": "web-1045" }')).toEqual({ request: { fromStep: 'clone', input: { name: 'web-1045' } } })
  })

  it('refuses a list, a scalar, null, broken JSON or no step before anything is sent', () => {
    expect(rerunRequest('clone', '[{ "name": "web-1045" }]')).toEqual({ error: 'Expected a JSON object' })
    expect(rerunRequest('clone', '"web-1045"')).toEqual({ error: 'Expected a JSON object' })
    expect(rerunRequest('clone', 'null')).toEqual({ error: 'Expected a JSON object' })
    const broken = rerunRequest('clone', '{ "name": ')
    expect(broken.request).toBeUndefined()
    expect(broken.error).toBeTruthy()
    expect(rerunRequest('', '')).toEqual({ error: 'Choose the step to start from' })
  })
})

describe('jobs that cannot be re-run', () => {
  it('names a batch and a job still running, the two the backend answers 409 for', () => {
    expect(rerunBlocker({ active: false, batch: true })).toContain('retry its failed rows')
    expect(rerunBlocker({ active: true, batch: true })).toContain('retry its failed rows')
    expect(rerunBlocker({ active: true, batch: false })).toBe('A job can be re-run once it has ended.')
    expect(rerunBlocker({ active: false, batch: false })).toBeNull()
  })
})

describe('RerunCard', () => {
  it('starts from the first step with the job input offered, and says how the input combines', () => {
    const html = text(renderToString(<RerunCard jobId="job-9b2e4f10" steps={STEPS} input={INPUT} />))
    expect(html).toContain('Re-run from a step')
    expect(html).toContain('the steps before the one you choose keep this run&#x27;s outputs')
    expect(html).toContain('aria-label="From step"')
    expect(html).toContain('>Re-run from image.create<')
    expect(html).toContain('Start from this job&#x27;s input')
    expect(html).toContain('Fields you give replace this job&#x27;s; leave it empty to reuse its input')
    expect(html).toContain('id="rerun-input"')
    // nothing to fix: the button is live
    const button = html.slice(html.lastIndexOf('<button', html.indexOf('>Re-run from image.create<')), html.indexOf('>Re-run from image.create<'))
    expect(button).not.toContain('disabled=""')
  })

  it('says why instead of offering the form when the job cannot be re-run', () => {
    const blocked = text(renderToString(<RerunCard jobId="job-5b10e7d2" steps={STEPS} input={INPUT} blocked={rerunBlocker({ active: true, batch: false })} />))
    expect(blocked).toContain('data-testid="rerun-unavailable"')
    expect(blocked).toContain('A job can be re-run once it has ended.')
    expect(blocked).not.toContain('Re-run from image.create')
    expect(blocked).not.toContain('id="rerun-input"')
    const empty = text(renderToString(<RerunCard jobId="job-1" steps={[]} input={{}} />))
    expect(empty).toContain('This job recorded no steps to start from.')
    expect(empty).not.toContain('aria-label="From step"')
  })

  it('goes to the backend as the step and the changed fields, and the answer names the new job', async () => {
    const calls: { url: string; method?: string; body: unknown }[] = []
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method, body: JSON.parse(String(init?.body)) })
      return new Response(JSON.stringify({ id: 'job-new-1', status: 'queued' }), { status: 202, headers: { 'content-type': 'application/json' } })
    })
    const changed = rerunRequest('clone', '{ "name": "web-1045" }')
    const same = rerunRequest('register', '')
    expect(await api.rerunJob('job-9b2e4f10', changed.request!)).toEqual({ id: 'job-new-1', status: 'queued' })
    await api.rerunJob('job-9b2e4f10', same.request!)
    expect(calls).toEqual([
      { url: '/api/v1/jobs/job-9b2e4f10:rerun', method: 'POST', body: { fromStep: 'clone', input: { name: 'web-1045' } } },
      { url: '/api/v1/jobs/job-9b2e4f10:rerun', method: 'POST', body: { fromStep: 'register' } },
    ])
  })
})
