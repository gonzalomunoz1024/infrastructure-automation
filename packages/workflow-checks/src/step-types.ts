/** The building blocks a workflow is made of, as StepType.java lists them; `yamlKey` is how the YAML spells the step. */

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
  /** Container steps whose children are other steps. */
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
  provider: info('provider', 'call', 'Providers', 'Provider action', 'Call an action of a registered provider with its input'),
  http: info('http', 'call', 'Code', 'HTTP request', 'Call a URL with a method, headers, a body and a Vault credential'),
  ansible: info('ansible', 'call', 'Ansible', 'Ansible playbook', 'Run a playbook from git, an upload or the library against an inventory'),
  terraform: info('terraform', 'call', 'Terraform', 'Terraform module', 'Plan, apply or destroy a pinned module with variables'),
  idp: info('idp', 'call', 'IDP tiles', 'IDP tile', 'Request something through a tile of the developer portal'),
  activity: info('activity', 'call', 'Code', 'Activity', 'Run an activity a team-owned worker registers on a task queue'),
  run: info('run', 'run', 'Flow', 'Run workflow', 'Run a published workflow as a child job'),
  approval: info('approval', 'approval', 'Human', 'Approval', 'Wait for a group of people to sign off'),
  switch: info('switch', 'switch', 'Flow', 'Switch', 'Branch on an expression: jump to a later step or run a nested branch'),
  fork: info('fork', 'fork', 'Flow', 'Fork', 'Run branches in parallel and join them'),
  for: info('for', 'for', 'Flow', 'Loop', 'Run a branch for each item of a list'),
  try: info('try', 'try', 'Flow', 'Try and catch', 'Run a branch and handle its failure in catch'),
  wait: info('wait', 'wait', 'Flow', 'Wait', 'Pause for a duration or until an expression holds'),
  set: info('set', 'set', 'Code', 'Set variables', 'Compute values reachable as $vars.name'),
  raise: info('raise', 'raise', 'Flow', 'Raise error', 'Fail the job with an error name and message'),
  use: info('use', 'use', 'Resources', 'Use resource', 'Resolve a registry resource and check access to it'),
  register: info('register', 'register', 'Resources', 'Register resource', 'Record what the job created in the registry'),
  notify: info('notify', 'notify', 'Human', 'Notify', 'Send a message to a channel'),
  script: info('script', 'script', 'Code', 'Script', 'Run a small sandboxed JavaScript function'),
};

export const STEP_TYPE_KEYS: readonly StepType[] = Object.keys(STEP_TYPES) as StepType[];

export function isStepType(key: string): key is StepType {
  return Object.hasOwn(STEP_TYPES, key);
}

/** The step types whose YAML key is `call`. */
export const CALL_KINDS: readonly StepType[] = STEP_TYPE_KEYS.filter((k) => STEP_TYPES[k].isCall);
