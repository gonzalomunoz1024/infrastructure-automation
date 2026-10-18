import { expect, test } from '@playwright/test'
import { ASSISTANT_DRAFT_DEFINITION, ASSISTANT_DRAFT_YAML, ASSISTANT_PROBLEMS, ASSISTANT_SESSION, PLATFORM_STREAM, WORKFLOWS, mockApi, reply, sse } from './fixtures'

/*
 * The Assistant page against the mocked backend: the first message creates a session, the stream
 * arrives the way the backend writes it (the type as the SSE event name, the payload alone as the
 * data), the validation lists the problems on their steps, the draft opens in the designer, and a
 * session named in the address is resumed from what the backend kept.
 */

const EXAMPLE = 'Restart a VM after checking no backup is running, with an approval for production.'

const STREAM = sse([
  { id: 1, event: 'status', data: { status: 'Reading the registry' } },
  { id: 2, event: 'message', data: { role: 'assistant', text: 'Found 3 providers and 9 workflows.' } },
  { id: 3, event: 'status', data: { status: 'Drafting' } },
  { id: 4, event: 'draft', data: { status: 'ready', summary: 'Restarts a VM after a backup check.', workflowYaml: ASSISTANT_DRAFT_YAML, assumptions: ['The VM id is the vSphere id'], validation: { valid: false, rounds: 1, errors: ['step.unknownProvider @restart: No provider called vcenter'] }, dryRun: { passed: false, path: ['check'], error: 'No provider called vcenter' } } },
  { id: 5, event: 'validation', data: { valid: false, problems: ASSISTANT_PROBLEMS } },
  { id: 6, event: 'message', data: { role: 'assistant', text: 'The draft checks the backups, then restarts; the restart names a provider that does not exist.' } },
  { id: 7, event: 'done', data: { status: 'done' } },
])

const VM_RESTART_DETAIL = { ...WORKFLOWS[0], yaml: 'kind: Workflow\n', definition: { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'vm-restart' }, workflow: { do: [] } }, versions: [], problems: [], changeRequests: [] }

test.describe('the assistant page', () => {
  test('the first message creates a session the address carries, the validation lists the problems on their steps, and the draft opens in the designer', async ({ page }) => {
    const api = await mockApi(page, {
      'POST /assistant/sessions': { sessionId: 'as-81' },
      'POST /assistant/sessions/{id}/messages': STREAM,
      'POST /workflows:parse': { definition: ASSISTANT_DRAFT_DEFINITION, problems: [] },
      'GET /registry/providers': [],
      'GET /audit/platform': { ...PLATFORM_STREAM, entries: [], total: 0 },
      'POST /workflows': { id: 'vm-restart-safe', status: 'created' },
    })
    await page.goto('/#/assistant')
    await expect(page.getByText('What should it do?')).toBeVisible()
    await expect(page.getByText('No draft yet')).toBeVisible()
    await page.getByRole('button', { name: EXAMPLE }).click()
    const box = page.getByRole('textbox', { name: 'Message' })
    await expect(box).toHaveValue(EXAMPLE)
    await page.getByRole('button', { name: 'Send' }).click()

    // a create session, then the message on it; the address names the session from then on
    const conversation = page.getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('The draft checks the backups, then restarts')
    expect(api.callsTo('POST', '/assistant/sessions').map((c) => c.body)).toEqual([{ mode: 'create' }])
    expect(api.callsTo('POST', '/assistant/sessions/as-81/messages').map((c) => c.body)).toEqual([{ text: EXAMPLE }])
    await expect(page).toHaveURL(/#\/assistant\?session=as-81$/)
    await expect(page.getByTitle('Assistant session')).toHaveText('as-81')
    await expect(conversation.getByRole('listitem')).toHaveCount(3)
    await expect(conversation).toContainText('Found 3 providers and 9 workflows.')

    // the draft, and the validation event's problems on their steps, named as the parse names them
    await expect(page.getByText('Restarts a VM after a backup check.')).toBeVisible()
    await expect(page.getByText('The VM id is the vSphere id')).toBeVisible()
    const issues = page.getByTestId('issues-list')
    await expect(issues.getByRole('region', { name: 'Restart the VM' })).toContainText('No provider called vcenter; vsphere is the closest')
    await expect(issues).toContainText('A change step runs without an approval')
    await expect(page.getByText('dry run did not pass')).toBeVisible()

    // the draft becomes a workflow and opens on the canvas
    await page.getByRole('button', { name: 'Open in designer' }).click()
    await expect(page).toHaveURL(/#\/workflows\/vm-restart-safe$/)
    expect(api.callsTo('POST', '/workflows')[0].body).toEqual({ yaml: ASSISTANT_DRAFT_YAML })
  })

  test('a session named in the address goes on from what the backend kept, in its mode and in the same session', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /assistant/sessions/{id}': ASSISTANT_SESSION,
      'GET /workflows/{name}': VM_RESTART_DETAIL,
      'GET /audit/platform': { ...PLATFORM_STREAM, entries: [], total: 0 },
      'POST /assistant/sessions/{id}/messages': sse([
        { id: 1, event: 'draft', data: { ...ASSISTANT_SESSION.draft, summary: 'Restarts the VM after a backup check, on vsphere.', validation: { valid: true, rounds: 1, errors: [] }, dryRun: { passed: true, path: ['check', 'restart'] } } },
        { id: 2, event: 'validation', data: { valid: true, problems: [] } },
        { id: 3, event: 'done', data: { status: 'done' } },
      ]),
      'PUT /workflows/{name}/draft': { name: 'vm-restart', version: '1.3.0', problems: [] },
    })
    await page.goto('/#/assistant?session=as-90')
    const conversation = page.getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('Check the backups before restarting.')
    await expect(conversation).toContainText('I added a backup check before the restart.')
    // (twice in development, where React mounts every effect twice)
    expect(api.callsTo('GET', '/assistant/sessions/as-90').length).toBeGreaterThan(0)
    // an edit session: the page edits the session's workflow
    await expect(page.getByRole('link', { name: 'VM restart' })).toBeVisible()
    await expect(page.getByTitle('Assistant session')).toHaveText('as-90')
    await expect(page.getByText('Restarts the VM after a backup check, on vcenter.')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Validation errors' })).toContainText('step.unknownProvider @restart: No provider called vcenter')

    // the next message goes on in the same session
    await page.getByRole('textbox', { name: 'Message' }).fill('Use vsphere for the restart.')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('Restarts the VM after a backup check, on vsphere.')).toBeVisible()
    expect(api.callsTo('POST', '/assistant/sessions')).toHaveLength(0)
    expect(api.callsTo('POST', '/assistant/sessions/as-90/messages').map((c) => c.body)).toEqual([{ text: 'Use vsphere for the restart.' }])
    await expect(page.getByText('No problems reported.')).toBeVisible()

    // saved into the workflow it edits
    await page.getByRole('button', { name: 'Save as draft' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Draft saved' })).toBeVisible()
    expect(api.callsTo('PUT', '/workflows/vm-restart/draft')[0].body).toEqual({ yaml: ASSISTANT_DRAFT_YAML })
  })

  test('a session the backend does not know says so, and a new session starts clean', async ({ page }) => {
    await mockApi(page, {
      'GET /assistant/sessions/{id}': reply(404, { error: 'No assistant session as-404' }),
    })
    await page.goto('/#/assistant?session=as-404')
    await expect(page.getByText('Not found')).toBeVisible()
    await page.getByRole('button', { name: 'New session' }).click()
    await expect(page).toHaveURL(/#\/assistant$/)
    await expect(page.getByText('What should it do?')).toBeVisible()
  })

  test('a message the assistant could not finish ends with the reason', async ({ page }) => {
    await mockApi(page, {
      'POST /assistant/sessions': { sessionId: 'as-82' },
      'POST /assistant/sessions/{id}/messages': sse([
        { id: 1, event: 'status', data: { status: 'Reading the registry' } },
        { id: 2, event: 'message', data: { role: 'assistant', text: 'The assistant could not finish the draft.' } },
        { id: 3, event: 'done', data: { status: 'failed', error: 'The agent did not answer within 10 minutes' } },
      ]),
      'GET /audit/platform': { ...PLATFORM_STREAM, entries: [], total: 0 },
    })
    await page.goto('/#/assistant')
    await page.getByRole('textbox', { name: 'Message' }).fill('Patch a CVE on the web servers.')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'The agent did not answer within 10 minutes' })).toBeVisible()
    await expect(page.getByText('No draft yet')).toBeVisible()
  })
})
