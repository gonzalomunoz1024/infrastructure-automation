import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { JobStep, JobSummary } from '../lib/types'
import { StepTimeline, buildOf, childJobIdOf, continuedAfterFailure } from '../components/StepTimeline'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const BUILD: JobStep = { id: 'image.create', name: 'Build image', type: 'run', status: 'succeeded', attempt: 1, durationMs: 240_000, output: { jobId: 'job-child-9', status: 'succeeded', outputs: { image: 'img-99' } } }
const CONTINUED: JobStep = {
  id: 'warm-cache',
  name: 'Warm the cache',
  type: 'run',
  status: 'succeeded',
  attempt: 1,
  durationMs: 4000,
  output: { jobId: 'job-child-10', status: 'failed', outputs: {}, error: { step: 'fetch', message: 'origin answered 503', class: 'upstream' } },
}
const PLAIN: JobStep = { id: 'notify', name: 'Tell the channel', type: 'run', status: 'succeeded', attempt: 1, output: { jobId: 'job-child-11', status: 'succeeded', outputs: {} } }
const CHILD: JobSummary = { id: 'job-child-9', workflow: 'build-vm-image', title: 'Build ubuntu-2404', version: '1.0.0', status: 'succeeded', requester: 'gonzalo', channel: 'rest', startedAt: '2026-10-17T09:00:00Z', kind: 'single' }

describe('StepTimeline', () => {
  it('recognises the build of an input and the child job it ran', () => {
    expect(buildOf(BUILD)).toEqual({ input: 'image', type: 'image' })
    expect(buildOf({ id: 'db.create', name: 'Build database' })).toEqual({ input: 'db', type: 'database' })
    expect(buildOf({ id: 'create', name: 'Create' })).toBeNull()
    expect(buildOf({ id: 'restart', name: 'Restart' })).toBeNull()
    expect(childJobIdOf(BUILD)).toBe('job-child-9')
    expect(childJobIdOf({ output: { ok: true } })).toBeUndefined()
  })

  it('knows a run step whose child failed while the workflow continued', () => {
    expect(continuedAfterFailure(CONTINUED)).toEqual({ status: 'failed', message: 'origin answered 503', errorClass: 'upstream' })
    expect(continuedAfterFailure(PLAIN)).toBeNull()
    expect(continuedAfterFailure({ ...CONTINUED, status: 'failed' })).toBeNull()
    expect(continuedAfterFailure({ ...CONTINUED, type: 'provider' })).toBeNull()
  })

  it('shows a build step as "Built <type> for <input>" with a link to the child job', () => {
    const html = text(renderToString(<StepTimeline steps={[BUILD]} children={[CHILD]} />))
    expect(html).toContain('Built image for image')
    expect(html).toContain('The image was built by job')
    expect(html).toContain('href="#/jobs/job-child-9"')
    expect(html).not.toContain('>Build image<')
  })

  it('links the child job from the step output when the job list does not carry it', () => {
    const html = text(renderToString(<StepTimeline steps={[BUILD]} />))
    expect(html).toContain('href="#/jobs/job-child-9"')
  })

  it('says a continued run step failed, and keeps the state the step has', () => {
    const html = text(renderToString(<StepTimeline steps={[CONTINUED, PLAIN]} />))
    expect(html).toContain('failed, the workflow continued')
    expect(html).toContain('origin answered 503')
    expect(html.match(/failed, the workflow continued/g)).toHaveLength(1)
  })
})
