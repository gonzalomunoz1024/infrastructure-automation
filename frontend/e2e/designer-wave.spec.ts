import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockDesignerApi, type MockApi } from './designer-fixtures'

/*
 * The designer against the mocked backend: the Validate action and its "Before publishing"
 * group, the fixes the checks offer on the conformance fixtures, the Playbook section of an
 * ansible step (pin to a commit, upload an archive), and a job opened from its page.
 */

const FIXTURES = fileURLToPath(new URL('../../packages/workflow-checks/fixtures/', import.meta.url))
const fixture = (name: string) => readFileSync(join(FIXTURES, name), 'utf8')

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function openVmRestart(page: Page): Promise<MockApi> {
  const api = await mockDesignerApi(page)
  await page.goto('/#/workflows/vm-restart')
  await expect(node(page, 'step:checkVmExists')).toBeVisible()
  return api
}

/** Replaces the workflow through the YAML drawer, the way a file is pasted in. */
async function loadYaml(page: Page, yaml: string): Promise<void> {
  await page.getByRole('button', { name: 'YAML' }).click()
  const editor = page.getByTestId('yaml-editor')
  await expect(editor).toHaveValue(/name: /)
  await editor.fill(yaml)
  await page.getByRole('button', { name: 'Apply YAML' }).click()
  await expect(page.getByText('YAML applied')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(editor).toBeHidden()
}

test.describe('validate', () => {
  test('Validate asks the backend and lists the publish checks under "Before publishing"', async ({ page }) => {
    const api = await openVmRestart(page)
    await page.getByTestId('validate-button').click()
    await expect.poll(() => api.validations.length).toBe(1)
    expect((api.validations[0].definition as { metadata: { name: string } }).metadata.name).toBe('vm-restart')

    const list = page.getByTestId('issues-list')
    await expect(list).toBeVisible()
    const publish = page.getByTestId('issues-publish')
    await expect(publish.getByRole('heading', { name: 'Before publishing' })).toBeVisible()
    await expect(publish.locator('[data-code="publish.ansibleLint"]')).toContainText('The playbook is not pinned to a commit')
    await expect(publish.locator('[data-code="publish.ansibleLint"]')).toContainText('Restart with Ansible')
    await expect(publish.locator('[data-code="publish.routes"]')).toContainText('already answered by vm-reboot 1.0.0')
    await expect(publish.locator('[data-code="publish.contracts"]')).toContainText('Affected consumers: web-environment')
    await expect(page.getByTestId('issues-button')).toHaveAccessibleName('Issues: 3')

    // the fix the contracts check offers writes the version; the backend's verdict is then stale
    await publish.getByRole('button', { name: 'Publish as 2.0.0' }).click()
    await expect(page.getByText('Fixed')).toBeVisible()
    await expect(page.getByTestId('issues-publish')).toHaveCount(0)
    await page.keyboard.press('Escape')
    await page.getByRole('tab', { name: 'Settings' }).click()
    await expect(page.getByTestId('settings-view')).toContainText('2.0.0')
  })
})

test.describe('fixes', () => {
  test('a destructive step without an approval offers to insert one', async ({ page }) => {
    await openVmRestart(page)
    await loadYaml(page, fixture('delete-without-approval.yaml'))
    await expect(node(page, 'step:delete')).toBeVisible()
    const issues = page.getByTestId('issues-button')
    await expect(issues).toHaveAccessibleName('Issues: 1')
    await issues.click()
    const problem = page.getByTestId('issues-list').locator('[data-code="policy.approvalRequired"]')
    await expect(problem).toContainText('Action vm.delete is destructive')
    await problem.getByRole('button', { name: 'Insert an approval before this step' }).click()
    await expect(page.getByText('Fixed')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(node(page, 'step:approveDelete')).toBeVisible()
    await expect(page.getByTestId('rf__edge-step:approveDelete:main->step:delete')).toBeAttached()
  })

  test('an unknown field offers a did-you-mean that edits the expression', async ({ page }) => {
    await openVmRestart(page)
    await loadYaml(page, fixture('unknown-field.yaml'))
    const issues = page.getByTestId('issues-button')
    await expect(issues).toHaveAccessibleName('Issues: 2')
    await issues.click()
    const list = page.getByTestId('issues-list')
    await expect(list).toContainText("$vm has no field 'nmae'")
    await list.getByRole('button', { name: 'Did you mean name?' }).click()
    await expect(page.getByText('Fixed')).toBeVisible()
    await expect(list).not.toContainText("$vm has no field 'nmae'")
    await expect(list).toContainText("The previous step's output has no field 'attributesTemplateId'")
    await page.keyboard.press('Escape')
    await node(page, 'step:tell').dblclick()
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('textbox', { name: /^Message/ })).toHaveValue('{{ $vm.name }} runs {{ $json.attributesTemplateId }} ({{ $vm.powerState }}, {{ $json.attributes.os }})')
    // the second did-you-mean is offered right under the field too
    await ndv.getByRole('button', { name: 'Did you mean attributes.templateId?' }).click()
    await expect(ndv.getByRole('textbox', { name: /^Message/ })).toHaveValue('{{ $vm.name }} runs {{ $json.attributes.templateId }} ({{ $vm.powerState }}, {{ $json.attributes.os }})')
  })

  test('a step that never runs offers to be removed', async ({ page }) => {
    await openVmRestart(page)
    await loadYaml(page, fixture('unreachable-step.yaml'))
    await expect(node(page, 'step:forgotten')).toBeVisible()
    await page.getByTestId('issues-button').click()
    const problem = page.getByTestId('issues-list').locator('[data-code="step.unreachable"]')
    await expect(problem).toContainText("'forgotten' never runs: every case of pick jumps past it")
    await problem.getByRole('button', { name: "Remove 'forgotten'" }).click()
    await expect(page.getByText('Fixed')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(node(page, 'step:forgotten')).toHaveCount(0)
    await expect(page.getByTestId('issues-button')).toHaveAccessibleName('Issues: 0')
  })
})

test.describe('the playbook of an ansible step', () => {
  test('pins the git source to the commit the ref names, and shows what went wrong', async ({ page }) => {
    const api = await openVmRestart(page)
    await node(page, 'step:restart').dblclick()
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('heading', { name: 'Playbook' })).toBeVisible()
    await expect(ndv.getByRole('radio', { name: 'Git repository' })).toHaveAttribute('aria-checked', 'true')
    await expect(ndv.getByTestId('playbook-pinned')).toHaveText('pinned 4e1d9a2')

    // a public repository: the pin sends no credential, and never the machine credential of the hosts
    await expect(ndv.getByRole('button', { name: 'Machine credential', exact: true })).toContainText('ssh/ops-automation')
    await expect(ndv.getByRole('button', { name: 'Git credential', exact: true })).not.toContainText('/')
    await ndv.getByTestId('pin-playbook').click()
    await expect.poll(() => api.resolves.length).toBe(1)
    const sent = new URLSearchParams(api.resolves[0])
    expect(sent.get('repo')).toBe('github.com/acme/ops-playbooks')
    expect(sent.get('ref')).toBe('v3.1.0')
    expect(sent.get('credential')).toBeNull()
    await expect(ndv.getByRole('textbox', { name: /^Commit/ })).toHaveValue('9fceb02d0ae598e95dc970b74767f19372d61af8')
    await expect(ndv.getByTestId('playbook-pinned')).toHaveText('pinned 9fceb02')
    await expect(ndv.getByTestId('playbook-note')).toContainText('Pinned to 9fceb02')

    // a private repository: the git credential picked for it is what the pin sends
    await ndv.getByRole('button', { name: 'Git credential', exact: true }).click()
    await page.getByRole('option', { name: /github\/read-token/ }).click()
    await expect(ndv.getByRole('button', { name: 'Git credential', exact: true })).toContainText('github/read-token')
    await ndv.getByTestId('pin-playbook').click()
    await expect.poll(() => api.resolves.length).toBe(2)
    expect(new URLSearchParams(api.resolves[1]).get('credential')).toBe('vault:github/read-token')
    await expect(ndv.getByRole('button', { name: 'Machine credential', exact: true })).toContainText('ssh/ops-automation')

    // a ref the repository does not have: the backend's 400 message, as it is
    await ndv.getByRole('textbox', { name: /^Ref/ }).fill('gone')
    await ndv.getByTestId('pin-playbook').click()
    await expect(ndv.getByRole('alert').filter({ hasText: 'has no ref gone' })).toBeVisible()
  })

  test('uploads a playbook archive and writes the stored reference into the step', async ({ page }) => {
    const api = await openVmRestart(page)
    await node(page, 'step:restart').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('radio', { name: 'Upload' }).click()
    const picker = ndv.getByTestId('playbook-archive')
    await picker.setInputFiles({ name: 'restart.tar.gz', mimeType: 'application/gzip', buffer: Buffer.from('not really an archive') })
    await expect.poll(() => api.uploads.length).toBe(1)
    expect(api.uploads[0]).toContain('filename="restart.tar.gz"')
    await expect(ndv.getByRole('textbox', { name: /^File/ })).toHaveValue('uploads/restart-3f1c.tar.gz')
    await expect(ndv.getByRole('textbox', { name: /^SHA-256/ })).toHaveValue('3f1c7a2b'.repeat(8))
    await expect(ndv.getByTestId('playbook-note')).toContainText('Uploaded restart.tar.gz')
    await expect(ndv.getByTestId('playbook-note')).toContainText('2 files')

    // too large (413) and not an archive (400): the backend's message, as it is
    await picker.setInputFiles({ name: 'too-large.tar.gz', mimeType: 'application/gzip', buffer: Buffer.from('x') })
    await expect(ndv.getByRole('alert').filter({ hasText: 'larger than 8 MB (platform.playbooks.max-upload-size)' })).toBeVisible()
    await picker.setInputFiles({ name: 'not-an-archive.txt', mimeType: 'text/plain', buffer: Buffer.from('x') })
    await expect(ndv.getByRole('alert').filter({ hasText: 'not a tar.gz, zip or YAML playbook' })).toBeVisible()
    // the step keeps the archive that was stored
    await expect(ndv.getByRole('textbox', { name: /^File/ })).toHaveValue('uploads/restart-3f1c.tar.gz')
  })

  test('a library playbook comes from the registry', async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:restart').dblclick()
    const ndv = page.getByTestId('ndv')
    await ndv.getByRole('radio', { name: 'Library' }).click()
    await expect(ndv.getByTestId('playbook-pinned')).toHaveCount(0)
    await ndv.getByRole('button', { name: /^Playbook/ }).click()
    await expect(page.getByRole('option').first()).toContainText('ops-playbooks/')
  })
})

test.describe('a job opened from its page', () => {
  test('?job= opens that run in Executions with its step results', async ({ page }) => {
    await mockDesignerApi(page)
    await page.goto('/#/workflows/vm-restart?job=job_1042')
    await expect(page.getByRole('tab', { name: 'Executions' })).toHaveAttribute('aria-selected', 'true')
    const view = page.getByTestId('executions')
    await expect(view.getByRole('button', { name: /job_1042/ })).toHaveAttribute('aria-current', 'true')
    await expect(view.getByText('Restart with Ansible: web-1042: host unreachable')).toBeVisible()
    await expect(node(page, 'step:restart').locator('[data-run]')).toHaveAttribute('data-run', 'failure')
    await expect(node(page, 'step:approval').locator('[data-run]')).toHaveAttribute('data-run', 'success')

    // the step's own results: the error of this run, its input and output
    await node(page, 'step:restart').dblclick()
    const drawer = page.getByRole('dialog', { name: 'Step Restart with Ansible' })
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('alert')).toContainText('AnsibleFailed')
    await expect(drawer.getByRole('alert')).toContainText('web-1042: host unreachable')
    await expect(drawer).toContainText('web-1042')
    await expect(drawer.getByRole('link', { name: /Open in the job page/ })).toHaveAttribute('href', /#\/jobs\/job_1042\?step=restart$/)
  })
})
