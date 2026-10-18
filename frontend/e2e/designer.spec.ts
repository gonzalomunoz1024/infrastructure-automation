import { expect, test, type Locator, type Page } from '@playwright/test'
import { EXPLANATION, mockDesignerApi, pasteInto, vmRestartYaml, yamlEditor, type MockApi } from './designer-fixtures'

/*
 * The designer against a mocked backend (the backend does not run here): the real
 * vm-restart workflow, the real providers, and every endpoint the designer calls.
 */

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function openVmRestart(page: Page): Promise<MockApi> {
  const api = await mockDesignerApi(page)
  await page.goto('/#/workflows/vm-restart')
  await expect(node(page, 'step:checkVmExists')).toBeVisible()
  return api
}

test.describe('workflow designer', () => {
  test('opens vm-restart on the canvas with its triggers, steps and connections', async ({ page }) => {
    await openVmRestart(page)
    for (const id of ['trigger:api', 'trigger:0', 'step:checkVmExists', 'step:noBackupRunning', 'step:approval', 'step:restart', 'step:validate', 'step:notifyDone', 'error-trigger', 'step:notifyOwner']) {
      await expect(node(page, id)).toBeVisible()
    }
    await expect(node(page, 'step:restart')).toContainText('Restart with Ansible')
    await expect(page.getByTestId('rf__edge-step:checkVmExists:main->step:noBackupRunning')).toBeAttached()
    await expect(page.getByTestId('rf__edge-step:validate:main->step:notifyDone')).toBeAttached()
    expect(await page.locator('.react-flow__edge').count()).toBeGreaterThanOrEqual(8)
    await expect(page.getByRole('tab', { name: 'Editor' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('issues-button')).toHaveAccessibleName('Issues: 0')
  })

  test('double-clicking a step opens its detail view with input, parameters and output', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:checkVmExists').dblclick()
    const ndv = page.getByTestId('ndv')
    await expect(ndv).toBeVisible()
    await expect(ndv.getByTestId('ndv-input')).toBeVisible()
    await expect(ndv.getByTestId('ndv-parameters')).toBeVisible()
    await expect(ndv.getByTestId('ndv-output')).toBeVisible()
    await expect(ndv.getByRole('textbox', { name: 'Step name' })).toHaveValue('Check VM exists')
    await expect(ndv.getByRole('tab', { name: /Parameters/ })).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('Escape')
    await expect(ndv).toBeHidden()
  })

  test('Tab opens the node creator and choosing Wait inserts a step', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:noBackupRunning').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await expect(creator).toBeVisible()
    await expect(creator.getByRole('combobox', { name: 'Search steps' })).toBeFocused()
    await page.keyboard.type('wait')
    await expect(creator.getByRole('option').first()).toContainText('Wait')
    await page.keyboard.press('Enter')
    // like n8n, the new step opens straight away
    await expect(page.getByTestId('ndv')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(node(page, 'step:wait')).toBeVisible()
    await expect(page.getByTestId('rf__edge-step:noBackupRunning:main->step:wait')).toBeAttached()
    await expect(page.getByTestId('rf__edge-step:wait:main->step:approval')).toBeAttached()
  })

  test('⌘S saves the draft with the workflow definition', async ({ page }) => {
    const api = await openVmRestart(page)
    await node(page, 'step:restart').click()
    await page.keyboard.press('Delete')
    await expect(node(page, 'step:restart')).toHaveCount(0)
    await expect(page.getByTestId('save-button')).toHaveText('Save')
    await page.keyboard.press('ControlOrMeta+s')
    await expect.poll(() => api.drafts.length).toBe(1)
    const definition = api.drafts[0].definition as { metadata: { name: string }; workflow: { do: Record<string, unknown>[] } }
    expect(definition.metadata.name).toBe('vm-restart')
    expect(definition.workflow.do.map((s) => Object.keys(s)[0])).toEqual(['checkVmExists', 'noBackupRunning', 'approval', 'validate', 'notifyDone'])
    await expect(page.getByTestId('save-button')).toHaveText('Saved')
  })

  test('Delete removes a step and ⌘Z brings it back', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:approval').click()
    await page.keyboard.press('Delete')
    await expect(node(page, 'step:approval')).toHaveCount(0)
    await expect(page.getByTestId('rf__edge-step:noBackupRunning:main->step:restart')).toBeAttached()
    await page.keyboard.press('ControlOrMeta+z')
    await expect(node(page, 'step:approval')).toBeVisible()
  })

  test('the Issues drawer lists a problem once a required field is cleared', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:notifyDone').dblclick()
    const ndv = page.getByTestId('ndv')
    const channel = ndv.getByRole('textbox', { name: /^Channel/ })
    await expect(channel).toHaveValue('#compute-ops')
    await channel.fill('')
    await expect(ndv.getByText('notify needs a channel')).toBeVisible()
    await ndv.getByRole('button', { name: 'Back to canvas' }).click()
    await expect(ndv).toBeHidden()
    const issues = page.getByTestId('issues-button')
    await expect(issues).toHaveAccessibleName('Issues: 1')
    await issues.click()
    const list = page.getByTestId('issues-list')
    await expect(list).toContainText('notify needs a channel')
    await expect(list).toContainText('Tell the channel')
  })

  test('the YAML drawer shows the workflow as YAML in Monaco', async ({ page }) => {
    await openVmRestart(page)
    await page.getByRole('button', { name: 'YAML' }).click()
    const editor = await yamlEditor(page)
    await expect(editor.locator('.view-lines')).toContainText('name: vm-restart')
    await expect(editor.getByRole('textbox', { name: 'Workflow YAML' })).toBeAttached()
    // the YAML tokenizer colours a key apart from its value
    const line = editor.locator('.view-line', { hasText: 'name: vm-restart' }).first()
    const key = line.locator('span > span', { hasText: /^name$/ })
    const value = line.locator('span > span', { hasText: /^vm-restart$/ })
    await expect(key).toHaveClass(/\bmtk\d+\b/)
    await expect(value).toHaveClass(/\bmtk\d+\b/)
    expect(await key.getAttribute('class')).not.toBe(await value.getAttribute('class'))
  })

  test('the YAML drawer marks a check problem on its step and applies a pasted file', async ({ page }) => {
    const api = await openVmRestart(page)
    // clearing the notify step's channel is a problem the checks find
    await node(page, 'step:notifyDone').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('textbox', { name: /^Channel/ }).fill('')
    await ndv.getByRole('button', { name: 'Back to canvas' }).click()
    await page.getByRole('button', { name: 'YAML' }).click()
    const editor = await yamlEditor(page)
    // F8 goes to the problem: the editor scrolls to the step and says what is wrong
    await editor.locator('.view-lines').click()
    await page.keyboard.press('F8')
    await expect(editor.locator('.marker-widget')).toContainText('notify needs a channel')
    await expect(editor.locator('.view-lines')).toContainText('notifyDone:')
    await page.keyboard.press('Escape')

    // a pasted file goes to the backend as it was pasted, and the canvas follows it
    const yaml = vmRestartYaml().replace('title: VM restart\n', 'title: VM restart, pasted\n')
    await pasteInto(page, editor, yaml)
    await page.getByRole('button', { name: 'Apply YAML' }).click()
    await expect(page.getByText('YAML applied')).toBeVisible()
    await expect(page.getByTitle('Rename workflow')).toHaveText('VM restart, pasted')
    // once, though leaving the editor for the button applies too
    expect(api.parses.filter((p) => p === yaml)).toHaveLength(1)
  })

  test('the expression editor opens from a parameter, marks what does not resolve and writes back', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:notifyDone').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('button', { name: 'Expand Message' }).click()
    const dialog = page.getByRole('dialog', { name: 'Edit Message' })
    const editor = dialog.getByTestId('expression-editor')
    await editor.locator('.monaco-editor .view-lines').waitFor({ timeout: 20_000 })
    await expect(editor.locator('.view-lines')).toContainText('Restarted {{ $vm.name }}')
    const result = dialog.getByTestId('expression-result')

    await pasteInto(page, editor, 'Restarted {{ $nope }}')
    await expect(editor.locator('.squiggly-error')).toHaveCount(1)
    await expect(result).toContainText('Unknown reference $nope')

    await pasteInto(page, editor, 'Restarted {{ $vm.name }} at last')
    await expect(editor.locator('.squiggly-error')).toHaveCount(0)
    await expect(result).toContainText('at last')
    await dialog.getByRole('button', { name: 'Done' }).click()
    await expect(dialog).toBeHidden()
    await expect(ndv.getByRole('textbox', { name: 'Message', exact: true })).toHaveValue('Restarted {{ $vm.name }} at last')
  })

  test("a credential created from an HTTP step's credential field lands in its YAML as a vault: reference", async ({ page }) => {
    const api = await openVmRestart(page)
    await node(page, 'step:notifyDone').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await expect(creator.getByRole('combobox', { name: 'Search steps' })).toBeFocused()
    await page.keyboard.type('HTTP request')
    await expect(creator.getByRole('option').first()).toContainText('HTTP request')
    await page.keyboard.press('Enter')
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('textbox', { name: 'Step name' })).toHaveValue('HTTP request')

    // like n8n's credential select, the field's options end with the entry that creates one
    const field = ndv.getByRole('button', { name: 'Credential', exact: true })
    await field.click()
    const entry = ndv.getByRole('listbox', { name: 'Credential' }).getByRole('option').last()
    await expect(entry).toHaveText('New credential…')
    await entry.click()
    const dialog = page.getByRole('dialog', { name: 'Add credential' })
    // on the first kind the field asks for (token, basic or bearer)
    await expect(dialog.getByRole('button', { name: 'Credential type' })).toHaveText('Token')
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('example/api-token')
    await dialog.getByRole('textbox', { name: 'Value', exact: true }).fill('example-token')
    await dialog.getByRole('button', { name: 'Store credential' }).click()
    await expect(dialog).toBeHidden()
    expect(api.credentials).toEqual([{ name: 'example/api-token', type: 'token', value: 'example-token', owner: 'team-compute' }])
    // the field takes the new credential at once, shown by its name
    await expect(field).toHaveText('example/api-token')

    await ndv.getByRole('button', { name: 'Back to canvas' }).click()
    await page.getByRole('button', { name: 'YAML' }).click()
    const editor = await yamlEditor(page)
    // Monaco draws only the lines in view: find the step's credential the way a reader would
    await editor.locator('.view-lines').click()
    await page.keyboard.press('ControlOrMeta+F')
    await page.keyboard.type('credential: vault:example')
    await expect(editor.locator('.view-lines')).toContainText('credential: vault:example/api-token')
    // the workflow file carries the reference, never the value
    await page.keyboard.press('ControlOrMeta+s')
    await expect.poll(() => api.drafts.length).toBe(1)
    const saved = JSON.stringify(api.drafts[0].definition)
    expect(saved).toContain('"credential":"vault:example/api-token"')
    expect(saved).not.toContain('example-token')
  })

  test('a step that pauses on its failure says how long it waits for a decision, and saves it', async ({ page }) => {
    const api = await openVmRestart(page)
    await node(page, 'step:restart').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('tab', { name: 'Settings' }).click()
    const timeout = ndv.getByRole('textbox', { name: /^Decision timeout/ })
    await expect(timeout).toHaveAttribute('placeholder', 'P7D')
    await timeout.fill('PT4H')
    await ndv.getByRole('button', { name: 'Back to canvas' }).click()
    await page.getByTestId('save-button').click()
    await expect.poll(() => api.drafts.length).toBe(1)
    const steps = (api.drafts[0].definition as { workflow: { do: Record<string, Record<string, unknown>>[] } }).workflow.do
    const restart = steps.find((s) => 'restart' in s)?.restart
    expect(restart?.attentionTimeout).toBe('PT4H')
    expect(restart && 'pauseOnFailure' in restart).toBe(false)
  })

  test('dragging a field of the input onto a parameter maps it as an expression', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:notifyDone').dblclick()
    const ndv = page.getByTestId('ndv')
    const pill = ndv.getByTestId('ndv-input').getByRole('button', { name: /^powerState: drag onto a parameter/ })
    await expect(pill).toBeVisible()
    const expression = /map (\{\{.*\}\})$/.exec((await pill.getAttribute('title')) ?? '')?.[1]
    expect(expression).toBeTruthy()
    await pill.dragTo(ndv.getByRole('textbox', { name: /^Channel/ }))
    await expect(ndv.getByRole('textbox', { name: /^Channel/ })).toHaveValue(expression ?? '')
  })

  test('Test workflow runs a dry run and marks every step on the canvas', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByTestId('test-workflow').click()
    const dialog = page.getByRole('dialog', { name: 'Test workflow' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: /Test workflow/ }).click()
    await expect.poll(() => api.dryRuns.length).toBe(1)
    await expect(page.getByText(/Test passed/)).toBeVisible()
    await expect(node(page, 'step:notifyDone').locator('[data-run]')).toHaveAttribute('data-run', 'success')
  })

  test('Executions shows the jobs of the workflow on its canvas', async ({ page }) => {
    await openVmRestart(page)
    await page.getByRole('tab', { name: 'Executions' }).click()
    const view = page.getByTestId('executions')
    await expect(view.getByRole('button', { name: /job_1042/ })).toBeVisible()
    await expect(view.getByRole('button', { name: /job_1041/ })).toBeVisible()
    await expect(view.getByText('web-1042: host unreachable').first()).toBeVisible()
    await expect(node(page, 'step:restart').locator('[data-run]')).toHaveAttribute('data-run', 'failure')
    await expect(node(page, 'step:checkVmExists').locator('[data-run]')).toHaveAttribute('data-run', 'success')
    await page.getByTestId('debug-in-editor').click()
    await expect(page.getByRole('tab', { name: 'Editor' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByText('Debugging in the editor')).toBeVisible()
  })

  test('Settings edits the workflow outside the steps without moving the page', async ({ page }) => {
    await openVmRestart(page)
    await page.getByRole('tab', { name: 'Settings' }).click()
    const view = page.getByTestId('settings-view')
    await expect(view.getByRole('heading', { name: 'Workflow' })).toBeVisible()
    await expect(view.getByTestId('input-schema')).toContainText('Required')
    await view.getByRole('navigation', { name: 'Settings sections' }).getByRole('button', { name: 'Outputs' }).click()
    await expect(view.getByRole('heading', { name: 'Outputs' })).toBeInViewport()
    // the designer header stays where it was
    await expect(page.getByRole('tab', { name: 'Settings' })).toBeInViewport()
    const title = view.getByRole('region', { name: 'Workflow' }).getByRole('textbox', { name: /^Title/ })
    await title.fill('VM reboot')
    await expect(page.getByTitle('Rename workflow')).toHaveText('VM reboot')
    await expect(page.getByTestId('save-button')).toHaveText('Save')
  })

  test('Publish opens a change request and merging it starts the canary', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByRole('button', { name: 'Publish', exact: true }).click()
    await page.getByTestId('publish-confirm').click()
    await expect.poll(() => api.publishes.length).toBe(1)
    expect(api.publishes[0].strategy).toBe('canary')
    const checks = page.getByTestId('cr-checks')
    await expect(checks.getByRole('listitem')).toHaveCount(3)
    await page.getByTestId('cr-merge').click()
    const merged = page.getByTestId('publish-merged')
    await expect(merged).toContainText('Published 1.3.0, canary at 10%')
    await expect(merged.getByRole('link', { name: /Follow the release/ })).toHaveAttribute('href', /releases\/rel_9/)
  })

  test('the assistant drafts a change and applies it to the canvas', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByRole('button', { name: 'Assistant' }).click()
    const panel = page.getByTestId('assistant-panel')
    await expect(panel).toBeVisible()
    await panel.getByRole('textbox', { name: 'Message the assistant' }).fill('Tell #compute-ops before restarting')
    await page.keyboard.press('Enter')
    await expect(panel.getByText('Announce the restart in #compute-ops first.')).toBeVisible()
    expect(api.assistant).toEqual(['Tell #compute-ops before restarting'])
    // what the draft changes on the canvas, before anything is applied
    const diff = panel.getByTestId('draft-diff')
    await expect(diff).toContainText('Changes to the canvas')
    await expect(diff).toContainText('name: Announce the restart')
    await expect(node(page, 'step:announce')).toHaveCount(0)
    await panel.getByRole('button', { name: 'Apply to canvas' }).click()
    await expect(node(page, 'step:announce')).toBeVisible()
    await expect(panel.getByRole('button', { name: 'Applied' })).toBeDisabled()
    await expect(panel.getByTestId('draft-diff')).toHaveCount(0)
  })

  test('the assistant explains the workflow in a message, with nothing to apply', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByRole('button', { name: 'Assistant' }).click()
    const panel = page.getByTestId('assistant-panel')
    await panel.getByRole('button', { name: 'Explain what this workflow does' }).click()
    await expect(panel.getByTestId('assistant-message')).toHaveText(EXPLANATION)
    await expect(panel.getByTestId('assistant-answer')).toContainText('No change to the canvas')
    // said once, as a message: the unchanged draft does not repeat it or offer to apply it
    await expect(panel.getByText(EXPLANATION)).toHaveCount(1)
    await expect(panel.getByRole('button', { name: 'Apply to canvas' })).toHaveCount(0)
    expect(api.assistant).toEqual(['Explain what this workflow does'])
  })

  test('a new workflow starts from the dialog and the first save creates it', async ({ page }) => {
    const api = await mockDesignerApi(page)
    await page.goto('/#/workflows/new')
    const dialog = page.getByRole('dialog', { name: 'New workflow' })
    await expect(dialog).toBeVisible()
    await dialog.getByLabel('Title').fill('Disk resize')
    await dialog.getByLabel('Noun').fill('disk')
    await dialog.getByLabel('Verb').fill('resize')
    await dialog.getByRole('button', { name: 'Create workflow' }).click()
    await expect(node(page, 'placeholder').getByRole('button', { name: 'Add first step' })).toBeVisible()
    await expect(node(page, 'trigger:api')).toBeVisible()
    await page.keyboard.press('ControlOrMeta+s')
    await expect.poll(() => api.created.length).toBe(1)
    expect((api.created[0].definition as { metadata: { name: string } }).metadata.name).toBe('disk-resize')
    // every workflow names who may run it: the new one lets its owner team
    expect((api.created[0].definition as { policy?: unknown }).policy).toEqual({ rbac: ['team-compute'] })
    await expect(page).toHaveURL(/#\/workflows\/disk-resize$/)
    await expect(node(page, 'trigger:api')).toBeVisible()
  })
})
