/**
 * The building blocks a workflow is made of, as StepType.java lists them; `yamlKey` is how the
 * YAML spells the step. Titles and descriptions are StepTypeSchemas.java's, the ones
 * GET /registry/step-types serves, so the designer reads the same words when it falls back to
 * this table; test/step-types.test.ts reads both Java files and keeps the two the same.
 */

export type StepType =
  | 'provider'
  | 'http'
  | 'ansible'
  | 'terraform'
  | 'idp'
  | 'activity'
  | 'run'
  | 'approval'
  | 'switch'
  | 'fork'
  | 'for'
  | 'try'
  | 'wait'
  | 'set'
  | 'raise'
  | 'use'
  | 'register'
  | 'notify'
  | 'script';

export type StepCategory = 'Providers' | 'Code' | 'Ansible' | 'Terraform' | 'IDP tiles' | 'Flow' | 'Human' | 'Resources';

export interface StepTypeInfo {
  /** The type, as the API spells it. */
  key: StepType;
  /** The YAML key: `call` for every integration step, otherwise the type itself. */
  yamlKey: string;
  category: StepCategory;
  title: string;
  description: string;
  isCall: boolean;
  /**
   * Container steps whose children are other steps: fork, for and try, as StepType.isContainer
   * says. A switch holds steps only in the `do` of its cases, so code that walks nested steps
   * adds it (the checks, the canvas and the interpreter do).
   */
  isContainer: boolean;
  /** Steps that touch the world (become activities in Temporal). */
  isActivity: boolean;
}

function info(key: StepType, yamlKey: string, category: StepCategory, title: string, description: string): StepTypeInfo {
  const isCall = yamlKey === 'call';
  return {
    key,
    yamlKey,
    category,
    title,
    description,
    isCall,
    isContainer: key === 'fork' || key === 'for' || key === 'try',
    isActivity: isCall || key === 'notify' || key === 'script' || key === 'register' || key === 'use',
  };
}

export const STEP_TYPES: Readonly<Record<StepType, StepTypeInfo>> = {
  provider: info('provider', 'call', 'Providers', 'Provider action', "Call one action of a registered provider; the output is the action's response."),
  http: info('http', 'call', 'Code', 'HTTP request', 'Send a raw HTTP request when no provider action fits; the credential is a vault: reference.'),
  ansible: info('ansible', 'call', 'Ansible', 'Ansible playbook', 'Run a playbook from git, an upload or the playbook library in an execution environment; pin it to a commit.'),
  terraform: info('terraform', 'call', 'Terraform', 'Terraform module', 'Plan, apply or destroy a pinned Terraform module with the given variables.'),
  idp: info('idp', 'call', 'IDP tiles', 'IDP tile', 'Run a tile of the developer portal with an input (mock until the IDP tiles API is wired).'),
  activity: info('activity', 'call', 'Code', 'Activity', 'Run an activity a team-owned worker registers on its task queue.'),
  run: info('run', 'run', 'Flow', 'Run workflow', 'Run a published workflow as a child job; version is a range like ^1.'),
  approval: info('approval', 'approval', 'Human', 'Approval', 'Pause until someone of the group signs off, with a timeout and an optional escalation.'),
  switch: info('switch', 'switch', 'Flow', 'Switch', 'Branch on expressions: each case jumps to a later step (then) or runs a nested branch (do).'),
  fork: info('fork', 'fork', 'Flow', 'Fork', 'Run branches in parallel and continue when all of them are done.'),
  for: info('for', 'for', 'Flow', 'For each', 'Run the body once per item of a list; the item is reachable as $<each>.'),
  try: info('try', 'try', 'Flow', 'Try / catch', 'Run steps and, when one fails, the catch steps with the error as $<as>.'),
  wait: info('wait', 'wait', 'Flow', 'Wait', 'Pause for a duration (PT5M) or until an expression is true.'),
  set: info('set', 'set', 'Code', 'Set variables', 'Set variables reachable later as $vars.<name>.'),
  raise: info('raise', 'raise', 'Flow', 'Raise error', 'Fail the job with a named error.'),
  use: info('use', 'use', 'Resources', 'Use resource', 'Resolve a registry resource by id, optionally waiting until it is ready and checking access.'),
  register: info('register', 'register', 'Resources', 'Register resource', 'Record what the job created in the registry, with what it was built from.'),
  notify: info('notify', 'notify', 'Human', 'Notify', 'Send a message to a channel.'),
  script: info('script', 'script', 'Code', 'Script', 'Run a small sandboxed script (JavaScript, Python, Bash or Go) over an input and return its result.'),
};

export const STEP_TYPE_KEYS: readonly StepType[] = Object.keys(STEP_TYPES) as StepType[];

export function isStepType(key: string): key is StepType {
  return Object.hasOwn(STEP_TYPES, key);
}

/** The step types whose YAML key is `call`. */
export const CALL_KINDS: readonly StepType[] = STEP_TYPE_KEYS.filter((k) => STEP_TYPES[k].isCall);
