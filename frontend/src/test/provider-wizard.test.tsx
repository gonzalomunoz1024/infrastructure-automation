import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { parse } from 'yaml'
import { emitYaml } from '../components/yaml-emit'
import {
  ErrorClasses,
  blankAction,
  blankError,
  buildProviderDocument,
  describeProvider,
  draftNames,
  errorDocument,
  errorIssues,
  initialState,
  matchesOperation,
  metadataOf,
  resourceFields,
  sandboxEnvironment,
  stepIssues,
  type WizardState,
} from '../pages/ProviderWizardPage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

function sdk(worker: Partial<WizardState['sdk']>): WizardState {
  const s = initialState()
  return { ...s, type: 'sdk', title: 'DNS records', name: 'dns-records', owner: 'team-platform', auth: { ...s.auth, type: 'none' }, sdk: { ...s.sdk, ...worker } }
}

describe('the provider wizard for an sdk provider', () => {
  it('leaves the worker block out when the defaults will do', () => {
    const spec = buildProviderDocument(sdk({})).spec as Record<string, unknown>

    expect(spec.worker).toBeUndefined()
    expect(spec.taskQueue).toBeUndefined()
    expect(stepIssues(sdk({}), 1)).toEqual([])
  })

  it('writes the task queue and the heartbeat timeout under worker', () => {
    const spec = buildProviderDocument(sdk({ taskQueue: ' dns-workers ', heartbeatTimeout: '30s' })).spec as Record<string, unknown>

    expect(spec.worker).toEqual({ taskQueue: 'dns-workers', heartbeatTimeout: '30s' })
  })

  it('says what is wrong with a task queue or a heartbeat timeout it cannot use', () => {
    expect(stepIssues(sdk({ taskQueue: 'dns workers' }), 1)).toEqual(['The task queue is letters, digits, dots, dashes and underscores.'])
    expect(stepIssues(sdk({ heartbeatTimeout: 'half a minute' }), 1)).toEqual(['The heartbeat timeout is a duration like 30s or 2m.'])
    expect(stepIssues(sdk({ heartbeatTimeout: 'PT30S' }), 1)).toEqual([])
  })
})

/* The Actions step's search and error classes, Ask Devin's draft, and where Try it tries. */

function http(patch: Partial<WizardState> = {}): WizardState {
  const s = initialState()
  return {
    ...s,
    type: 'http',
    title: 'Backup service',
    name: 'backup',
    owner: 'team-platform',
    environments: [{ key: 1, name: 'dev', url: 'https://backup-dev.example.com/api' }],
    auth: { ...s.auth, type: 'none' },
    actions: [blankAction({ id: 'jobs.create', path: '/jobs', method: 'POST', effect: 'create', retry: 'unsafe' })],
    ...patch,
  }
}

describe('the error classes', () => {
  it('are written to spec.errors as match, class and retry, in the order given', () => {
    const s = http({
      errors: [
        blankError({ status: '404', class: 'not_found' }),
        blankError({ status: ' 409 ', body: ' already exists ', class: 'conflict' }),
        blankError({ status: '429', class: 'rate_limited', retry: true }),
        // a row left blank is not an error class
        blankError(),
      ],
    })
    const spec = buildProviderDocument(s).spec as Record<string, unknown>
    expect(spec.errors).toEqual([
      { match: { status: 404 }, class: 'not_found', retry: false },
      { match: { status: 409, body: 'already exists' }, class: 'conflict', retry: false },
      { match: { status: 429 }, class: 'rate_limited', retry: true },
    ])
    expect(stepIssues(s, 2)).toEqual([])

    // the file: after the actions, before the SLO, as docs/config-as-code.md lists them
    const yaml = emitYaml(buildProviderDocument(s))
    expect((parse(yaml) as { spec: { errors: unknown } }).spec.errors).toEqual(spec.errors)
    expect(yaml.indexOf('  errors:')).toBeGreaterThan(yaml.indexOf('  actions:'))
    expect(yaml.indexOf('  errors:')).toBeLessThan(yaml.indexOf('  slo:'))
  })

  it('leave spec.errors out when there are none', () => {
    expect((buildProviderDocument(http()).spec as Record<string, unknown>).errors).toBeUndefined()
    expect((buildProviderDocument(http({ errors: [blankError()] })).spec as Record<string, unknown>).errors).toBeUndefined()
    expect(errorDocument(blankError({ status: ' ' }))).toBeUndefined()
  })

  it('match by the body text alone for a provider not called over HTTP', () => {
    const s = { ...sdk({}), errors: [blankError({ body: 'quota exceeded', class: 'rate_limited', retry: true })] }
    expect((buildProviderDocument(s).spec as Record<string, unknown>).errors).toEqual([{ match: { body: 'quota exceeded' }, class: 'rate_limited', retry: true }])
    expect(errorIssues('sdk', s.errors)).toEqual([])
  })

  it('say what an error class is missing before the Actions step goes on', () => {
    expect(errorIssues('http', [blankError({ body: 'quota', class: 'rate_limited' })])).toEqual(['rate_limited: the status it matches; calls are matched by status first, then by body text.'])
    expect(errorIssues('sdk', [blankError({ class: 'rate_limited' })])).toEqual(['rate_limited: the status or the body text it matches.'])
    expect(errorIssues('openapi', [blankError({ status: '4o4', class: 'not_found' })])).toEqual(['not_found: the status is an HTTP status, like 429.'])
    expect(errorIssues('openapi', [blankError({ status: '404', class: 'Not Found' })])).toEqual(['Not Found: a name in lowercase words joined by underscores, like rate_limited.'])
    expect(errorIssues('openapi', [blankError({ status: '404' })])).toEqual(['Error class 1: a name in lowercase words joined by underscores, like rate_limited.'])
    expect(stepIssues(http({ errors: [blankError({ status: '429' })] }), 2)).toContain('Error class 1: a name in lowercase words joined by underscores, like rate_limited.')
  })

  it('say when a class can never match because an earlier one takes its calls', () => {
    const broad = blankError({ status: '429', class: 'rate_limited', retry: true })
    const narrow = blankError({ status: '429', body: 'quota', class: 'quota_exceeded' })
    expect(errorIssues('http', [broad, narrow])).toEqual(['quota_exceeded: rate_limited above it matches every call it would, and the first match wins.'])
    // the narrower one first: each still matches something
    expect(errorIssues('http', [narrow, broad])).toEqual([])
    expect(errorIssues('http', [narrow, blankError({ status: '429', body: 'daily quota', class: 'daily_quota' })])).toEqual(['daily_quota: quota_exceeded above it matches every call it would, and the first match wins.'])
    expect(errorIssues('http', [broad, blankError({ status: '503', class: 'unavailable' })])).toEqual([])
  })

  it('start from the classes the backend falls back to', () => {
    let state = http()
    const update = (patch: Partial<WizardState>) => {
      state = { ...state, ...patch }
    }
    const empty = text(renderToString(<ErrorClasses state={state} update={update} />))
    expect(empty).toContain('None yet.')
    expect(empty).toContain('Start from the usual ones')
    expect(empty).toContain('a call none matches is named by its status alone')

    state = http({ errors: [blankError({ status: '429', class: 'rate_limited', retry: true }), blankError({ status: '409', body: 'already exists', class: 'conflict' })] })
    const two = text(renderToString(<ErrorClasses state={state} update={update} />))
    expect(two).toContain('aria-label="Error classes"')
    expect(two).toContain('value="rate_limited"')
    expect(two).toContain('value="already exists"')
    // 409 with a body text is not the usual 409: the usual ones are still offered
    expect(two).toContain('Add the usual ones')
    expect(two).toContain('title="404 not_found · 409 conflict · 503 unavailable, retried"')

    state = http({ errors: [404, 409, 429, 503].map((status) => blankError({ status: String(status), class: 'x' })) })
    expect(text(renderToString(<ErrorClasses state={state} update={update} />))).not.toContain('the usual ones')
  })
})

describe('the operations search', () => {
  const op = { operation: 'deleteVm', id: 'vm.delete', method: 'DELETE', path: '/vms/{id}', title: 'Delete a virtual machine' }

  it('matches every word in the id, method, path or summary, whatever the case', () => {
    expect(matchesOperation(op, '')).toBe(true)
    expect(matchesOperation(op, '  ')).toBe(true)
    expect(matchesOperation(op, 'deletevm')).toBe(true)
    expect(matchesOperation(op, 'DELETE /vms')).toBe(true)
    expect(matchesOperation(op, 'virtual machine')).toBe(true)
    expect(matchesOperation(op, 'vm.delete')).toBe(true)
    expect(matchesOperation(op, 'delete snapshot')).toBe(false)
  })
})

describe('Try it', () => {
  it('tries in the environment the review counts: dev, else the first one', () => {
    expect(sandboxEnvironment(['dev', 'test', 'prod'])).toBe('dev')
    expect(sandboxEnvironment(['prod', 'dev'])).toBe('dev')
    expect(sandboxEnvironment(['test', 'prod'])).toBe('test')
    expect(sandboxEnvironment([])).toBe('dev')
  })

  it('finds the input fields that name a registry resource', () => {
    expect(resourceFields({ type: 'object', properties: { id: { type: 'string', 'x-resource': { type: 'vm' } }, force: { type: 'boolean' } } })).toEqual(['id'])
    expect(resourceFields({ type: 'object', properties: { name: { type: 'string' } } })).toEqual([])
    expect(resourceFields(undefined)).toEqual([])
  })
})

describe("Ask Devin's draft", () => {
  const draft = ['apiVersion: iaf.platform/v1', 'kind: Provider', 'metadata:', '  name: backup', '  title: "Backup service"', "  owner: 'team-platform'  # the folder", '  version: 1.0.0', 'spec:', '  type: http', '  actions:', '    - id: jobs.create', '      title: Start a backup'].join('\n')

  it('names the provider from its metadata block, and only from it', () => {
    expect(metadataOf(draft)).toEqual({ name: 'backup', title: 'Backup service', owner: 'team-platform', version: '1.0.0' })
    expect(draftNames(draft)).toEqual({ name: 'backup', nameTouched: true, title: 'Backup service', owner: 'team-platform', ownerTouched: true, version: '1.0.0' })
    expect(metadataOf('kind: Provider\nspec:\n  name: nope\n')).toEqual({})
    // a name or a version the wizard could not use is left to the wizard
    expect(draftNames('metadata:\n  name: Backup Service\n  version: one\n')).toEqual({})
  })

  it('starts from the wizard so far, in words', () => {
    expect(describeProvider({ ...initialState(), owner: 'team-platform' })).toBe('An OpenAPI provider, owned by team-platform, version 1.0.0.\nAuthentication: bearer.\nActions: suggest the useful ones.')
    const s = http({ errors: [blankError({ status: '429', class: 'rate_limited', retry: true }), blankError({ status: '409', body: 'already exists', class: 'conflict' })] })
    const words = describeProvider(s)
    expect(words).toContain('An HTTP provider called "Backup service" (name backup), owned by team-platform, version 1.0.0.')
    expect(words).toContain('Error classes: HTTP 429 is rate_limited, retried; HTTP 409 with "already exists" is conflict.')
    expect(describeProvider({ ...initialState(), type: 'terraform', title: 'Load balancer', name: 'lb' })).toContain('A Terraform provider called "Load balancer" (name lb), version 1.0.0.')
  })
})
