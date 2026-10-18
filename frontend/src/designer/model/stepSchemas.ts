/**
 * The step-type parameter schemas, as `GET /registry/step-types` serves them (a copy of
 * StepTypeSchemas.java), used when the registry has not answered so the NDV still has a form.
 */
import type { StepType } from '@iaf/workflow-checks'

type S = Record<string, unknown>

const str = (description?: string, extra: S = {}): S => ({ type: 'string', ...(description ? { description } : {}), ...extra })
const obj = (description?: string): S => ({ type: 'object', ...(description ? { description } : {}) })
const en = (values: string[], description?: string): S => ({ type: 'string', enum: values, ...(description ? { description } : {}) })
const object = (required: string[], properties: S): S => ({ type: 'object', ...(required.length ? { required } : {}), properties })
const hostList = (description: string): S => ({ type: 'array', items: { type: 'string' }, minItems: 1, description })

export const FALLBACK_STEP_SCHEMAS: Record<StepType, S> = {
  provider: object(['provider', 'action'], {
    provider: str('The provider name from the registry'),
    action: str('The action id, like thing.get'),
    input: obj("The action's input fields; values may be expressions"),
  }),
  http: object(['url'], {
    method: en(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']),
    url: str('Absolute URL; may contain expressions'),
    headers: obj(),
    body: { description: 'Request body' },
    credential: str('A vault:<path> reference'),
  }),
  ansible: object(['playbook'], {
    playbook: {
      description: 'Where the playbook comes from: git, upload or library, pinned to a commit',
      oneOf: [
        str('A library playbook: <provider>/<playbook>'),
        object([], {
          git: object(['repo', 'ref', 'path'], { repo: str(), ref: str('Tag or branch'), path: str('Playbook path in the repo'), credential: str('A vault:<path> reference for a private repository') }),
          upload: object(['file', 'sha256'], { file: str(), sha256: str(), path: str('The playbook to run inside an uploaded archive') }),
          library: str('<provider>/<playbook>'),
          commit: str('The commit every run uses'),
        }),
      ],
    },
    // as the ansible runner reads them: dynamic or a path in the playbook's source, a list of hosts, or { hosts, group?, vars? }
    inventory: {
      oneOf: [
        str("dynamic (the configured dynamic inventory) or a path inside the playbook's source"),
        hostList('The hosts to run on'),
        {
          ...object(['hosts'], {
            hosts: { oneOf: [hostList('Host names'), str('Host names separated by commas')], description: 'The hosts of the group' },
            group: str('The group the hosts make up; all when absent'),
            vars: obj('Variables of the group'),
          }),
          additionalProperties: false,
        },
      ],
      description: "dynamic, a path in the playbook's source, a list of hosts or { hosts, group, vars }",
    },
    // a host pattern, or a list of hosts the runner joins with commas
    limit: { oneOf: [str('A host pattern like web1,web2 or db*'), hostList('Host names')], description: 'Hosts to limit the run to' },
    extraVars: obj('Playbook variables'),
    credential: str('A vault:<path> reference to the SSH key'),
    executionEnvironment: str('The execution environment image'),
    checkModeOnTest: { type: 'boolean', description: 'Run in check mode on the test environment' },
  }),
  terraform: object(['module', 'action'], {
    module: object(['source'], { source: str('Module source'), version: str('Pinned version') }),
    action: en(['plan', 'apply', 'destroy']),
    vars: obj('Module variables'),
    credential: str('A vault:<path> reference'),
  }),
  idp: object(['tile'], { tile: str('The tile id'), input: obj("The tile's input") }),
  activity: object(['taskQueue', 'activity'], { taskQueue: str("The team's task queue"), activity: str('The activity name'), input: obj() }),
  run: object(['workflow'], { workflow: str('A published workflow name'), version: str('A range like ^1, ~1.2 or 1.2.0'), input: obj("The child job's input") }),
  approval: object(['group'], {
    group: str('The approver group'),
    message: str('What the approver decides on; may contain expressions'),
    timeout: str('A duration like 4h'),
    escalation: object([], { after: str('A duration'), to: str('Group or person') }),
  }),
  switch: object(['cases'], {
    cases: { type: 'array', items: object([], { when: str('An expression; absent on the default case'), then: str('A later step id or end'), default: { type: 'boolean' }, do: { type: 'array', items: { description: 'Nested steps, run instead of a jump' } } }) },
  }),
  fork: object(['branches'], { branches: { type: 'array', items: { type: 'array', description: 'The steps of one branch' } } }),
  for: object(['each', 'in'], { each: str('The loop variable, like item'), in: str('An expression yielding a list'), do: { type: 'array', items: { description: 'The loop body' } } }),
  try: object(['do'], { do: { type: 'array' }, catch: object([], { as: str('The alias of the error, default error'), do: { type: 'array' } }) }),
  wait: object([], { duration: str('An ISO duration like PT5M, or 30m'), until: str('An expression') }),
  set: { type: 'object', description: 'Each key is a variable; each value an expression or literal', additionalProperties: { type: 'string' }, minProperties: 1 },
  raise: object(['error'], { error: str('The error name, like UpgradeFailed'), message: str() }),
  use: object(['type'], {
    type: str('A resource type'),
    id: str('The resource id; may be an expression'),
    selector: obj('Attributes to match when there is no id'),
    waitUntil: en(['ready']),
    checkAccess: { type: 'boolean', description: 'Ask policy whether the requester may use it (default true; false opts out)' },
  }),
  register: object(['type'], {
    type: str('A resource type'),
    id: str('The id; the registry mints one when absent'),
    state: str('A state of the type, default ready'),
    attributes: obj("Values of the type's schema"),
    builtFrom: { type: 'array', items: { type: 'string' } },
  }),
  notify: object(['channel', 'message'], { channel: str('A channel like #ops'), message: str('May contain expressions') }),
  script: object(['code'], {
    language: en(['javascript', 'python', 'bash', 'go']),
    code: str('javascript and python: the function body, input in scope; bash and go: a program reading JSON on stdin'),
    input: obj('What the script sees as input'),
  }),
}
