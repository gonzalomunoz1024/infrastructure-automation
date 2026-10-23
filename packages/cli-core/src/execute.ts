import type { ConsoleClient, DynamicCallInput, DynamicConsoleClient, ServerSentEvent } from '@iaf/console-sdk';
import { isFlag, parseArgs, suggest, UsageError, type FlagDef, type FlagValue, type ParsedArgs } from './args.js';
import { formatData, OUTPUT_FORMATS, toYaml, type OutputFormat } from './format.js';
import { GROUPS } from './generated/commands.js';
import { commandHelp, groupHelp, topHelp, usage, type HelpContext, type HelpEntry } from './help.js';
import type { CommandSpec, FlagSpec, GroupSpec } from './spec.js';

export interface ExecuteOptions {
  /** The format when no `--output` is given (`json`). */
  output?: OutputFormat;
  /** Reads `--body-file`; without it (a browser) the flag is refused. */
  readFile?: (path: string) => Promise<string>;
  /** Takes the output as it is produced (an event stream never ends); `stdout` is then empty. */
  onOutput?: (chunk: string) => void;
  /** How help names the program (`console`). */
  program?: string;
  /** More commands for the top help: the CLI's `login`, `logout`, `whoami`, `configure`. */
  commands?: readonly HelpEntry[];
  /** More global flags for the top help: the CLI's `--endpoint`. */
  globalFlags?: readonly HelpEntry[];
  /** What a 401 or a step-up refusal asks the person to do. */
  signInHint?: string;
}

export interface ExecuteResult {
  /** 0 done, 1 the call failed, 2 the command line is wrong. */
  exitCode: number;
  stdout: string;
  stderr: string;
}

/** The flags every command takes. `--account` and `--region` give way to a command's own. */
export const GLOBAL_FLAGS: readonly FlagDef[] = [
  { name: 'output', short: 'o', type: 'string', enum: OUTPUT_FORMATS },
  { name: 'account', type: 'string' },
  { name: 'region', type: 'string' },
  { name: 'all', type: 'boolean' },
  { name: 'body', type: 'string' },
  { name: 'body-file', type: 'string' },
  { name: 'idempotency-key', type: 'string' },
  { name: 'help', short: 'h', type: 'boolean' },
];

export { GROUPS };

/** Finds a command: `findCommand('resources', 'list')`. */
export function findCommand(group: string, name: string): { group: GroupSpec; command: CommandSpec } | undefined {
  const g = GROUPS.find((x) => x.name === group);
  const c = g?.commands.find((x) => x.name === name);
  return g && c ? { group: g, command: c } : undefined;
}

/**
 * Runs one command line against the BFF and answers what it printed: `execute(['jobs', 'list',
 * '--status', 'failed'], client)`. Never throws: a refusal is exit code 1, a wrong command line 2.
 */
export async function execute(argv: readonly string[], client: ConsoleClient | DynamicConsoleClient, options: ExecuteOptions = {}): Promise<ExecuteResult> {
  const program = options.program ?? 'console';
  const ctx: HelpContext = { program, commands: options.commands ?? [], globalFlags: options.globalFlags ?? [] };
  const stdout: string[] = [];
  const write = (chunk: string) => (options.onOutput ? options.onOutput(chunk) : stdout.push(chunk));
  const done = (exitCode: number, stderr = ''): ExecuteResult => ({ exitCode, stdout: stdout.join(''), stderr });

  let label = program;
  try {
    const target = locate(argv);
    if (target.help) {
      write(target.command ? commandHelp(target.group!, target.command, ctx) : target.group ? groupHelp(target.group, ctx) : topHelp(GROUPS, ctx));
      return done(0);
    }
    const { group, command } = target;
    if (!group || !command) {
      write(group ? groupHelp(group, ctx) : topHelp(GROUPS, ctx));
      return done(0);
    }
    label = `${program} ${group.name}${command.groupDefault ? '' : ` ${command.name}`}`;
    const parsed = parseArgs(target.rest, flagDefs(command));
    const call = await buildCall(command, parsed, options, program, group);
    await run(client as DynamicConsoleClient, command, call, write);
    return done(0);
  } catch (e) {
    if (e instanceof UsageError) {
      return done(2, `${label}: ${e.message}\nRun \`${label} --help\` for usage.\n`);
    }
    const lines = errorLines(e, { program, signInHint: options.signInHint ?? `run \`${program} login\``, endpoint: (client as { endpoint?: string }).endpoint });
    return done(1, `${lines.map((line, i) => (i === 0 ? `${label}: ${line}` : `  ${line}`)).join('\n')}\n`);
  }
}

// ---------------------------------------------------------------------------------------------
// Finding the command

interface Target {
  group?: GroupSpec;
  command?: CommandSpec;
  /** The command line less the group's and the command's words. */
  rest: string[];
  help: boolean;
}

const VALUE_FLAGS = new Set(GLOBAL_FLAGS.filter((d) => d.type !== 'boolean').flatMap((d) => [`--${d.name}`, ...(d.short ? [`-${d.short}`] : [])]));
const BOOLEAN_FLAGS = new Set(GLOBAL_FLAGS.filter((d) => d.type === 'boolean').flatMap((d) => [`--${d.name}`, `--no-${d.name}`, ...(d.short ? [`-${d.short}`] : [])]));

function locate(argv: readonly string[]): Target {
  const args = [...argv];
  let help = args.some((t) => t === '--help' || t === '-h');
  if (args[0] === 'help') {
    help = true;
    args.shift();
  }
  // the group's word and the command's, past the global flags given before them
  let group: GroupSpec | undefined;
  let groupAt = -1;
  let commandAt = -1;
  let candidate: string | undefined;
  for (let i = 0; i < args.length; i++) {
    const token = args[i]!;
    if (token === '--') break;
    if (isFlag(token)) {
      const name = token.split('=')[0]!;
      if (VALUE_FLAGS.has(name)) {
        if (!token.includes('=')) i++;
        continue;
      }
      if (BOOLEAN_FLAGS.has(name)) continue;
      if (!group) throw new UsageError(`${name} is not a global flag: give it after the command`);
      break; // a flag of the group's own command
    }
    if (!group) {
      group = GROUPS.find((g) => g.name === token);
      if (!group) throw new UsageError(`unknown group ${token}${suggest(token, GROUPS.map((g) => g.name), (s) => s)}`);
      groupAt = i;
      continue;
    }
    candidate = token;
    if (group.commands.some((c) => c.name === token)) commandAt = i;
    break;
  }
  const rest = args.filter((_, i) => i !== groupAt && i !== commandAt);
  if (!group) return { rest, help };

  let command = commandAt >= 0 ? group.commands.find((c) => c.name === args[commandAt]) : undefined;
  if (!command && !help) {
    // a word that is no command is an argument of the group's own command, or a mistake
    command = group.commands.find((c) => c.groupDefault);
    if (!command && candidate !== undefined) {
      throw new UsageError(`${group.name} has no command ${candidate}${suggest(candidate, group.commands.map((c) => c.name), (s) => s)}`);
    }
  }
  return { group, command, rest, help };
}

function flagDefs(command: CommandSpec): FlagDef[] {
  const own = command.flags.map(
    (f): FlagDef => ({
      name: f.flag,
      type: f.type === 'integer' || f.type === 'number' || f.type === 'boolean' ? f.type : 'string',
      array: f.array,
      enum: f.enum,
      namePattern: f.namePattern ? new RegExp(f.namePattern) : undefined,
    }),
  );
  const names = new Set(own.map((d) => d.name));
  return [...GLOBAL_FLAGS.filter((g) => !names.has(g.name)), ...own];
}

// ---------------------------------------------------------------------------------------------
// Building the call

interface Call {
  input: DynamicCallInput;
  output: OutputFormat;
  all: boolean;
}

async function buildCall(command: CommandSpec, parsed: ParsedArgs, options: ExecuteOptions, program: string, group: GroupSpec): Promise<Call> {
  const flags = parsed.flags;
  const own = new Set(command.flags.map((f) => f.flag));
  const global = (name: string): FlagValue | undefined => (own.has(name) ? undefined : flags.get(name));
  const positionals = [...parsed.positionals];

  const path: Record<string, string> = {};
  for (const name of command.positionals) {
    const spec = command.flags.find((f) => f.in === 'path' && f.name === name);
    const fromFlag = spec ? flags.get(spec.flag) : undefined;
    if (fromFlag !== undefined) path[name] = String(fromFlag);
    else if (positionals.length > 0) path[name] = checkEnum(positionals.shift()!, spec);
    else throw new UsageError(`needs <${name}>${spec ? ` (or --${spec.flag})` : ''}: ${usage(program, group, command)}`);
  }
  if (positionals.length > 0) {
    throw new UsageError(`unexpected argument ${positionals[0]}: ${usage(program, group, command)}`);
  }

  const query: Record<string, FlagValue> = {};
  const headers: Record<string, string> = {};
  for (const f of command.flags) {
    if (f.in === 'path') continue;
    if (f.namePattern) {
      const pattern = new RegExp(f.namePattern);
      for (const [name, value] of flags) if (!own.has(name) && pattern.test(name)) query[name] = value;
      continue;
    }
    const value = flags.get(f.flag);
    if (value === undefined) {
      if (f.required) throw new UsageError(`needs --${f.flag}: ${usage(program, group, command)}`);
      continue;
    }
    if (f.in === 'query') query[f.name] = value;
    else headers[f.name] = String(value);
  }

  const bodyText = global('body');
  const bodyFile = global('body-file');
  let body: unknown;
  if (bodyText !== undefined && bodyFile !== undefined) throw new UsageError('give --body or --body-file, not both');
  if (bodyText !== undefined || bodyFile !== undefined) {
    if (!command.body) throw new UsageError('this command takes no body');
    let text: string;
    if (bodyFile !== undefined) {
      if (!options.readFile) throw new UsageError('--body-file cannot be read here: give the body with --body');
      try {
        text = await options.readFile(String(bodyFile));
      } catch (e) {
        throw new UsageError(`cannot read --body-file ${String(bodyFile)}: ${(e as Error).message}`);
      }
    } else {
      text = String(bodyText);
    }
    try {
      body = JSON.parse(text);
    } catch (e) {
      throw new UsageError(`${bodyFile !== undefined ? '--body-file' : '--body'} is not JSON: ${(e as Error).message}`);
    }
  } else if (command.body?.required) {
    throw new UsageError(`needs a body: --body <json> or --body-file <path>`);
  }

  const all = global('all') === true;
  if (all && !command.paged) throw new UsageError('--all is for lists that answer pages; this command does not');
  const idempotencyKey = global('idempotency-key');
  if (idempotencyKey !== undefined && command.method === 'GET') throw new UsageError('--idempotency-key is for writes');

  const input: DynamicCallInput = { path, query, headers, body };
  const account = global('account');
  const region = global('region');
  if (account !== undefined) input.account = String(account);
  if (region !== undefined) input.region = String(region);
  if (idempotencyKey !== undefined) input.idempotencyKey = String(idempotencyKey);
  return { input, output: (global('output') as OutputFormat | undefined) ?? options.output ?? 'json', all };
}

function checkEnum(value: string, spec: FlagSpec | undefined): string {
  if (spec?.enum && !spec.enum.includes(value)) {
    throw new UsageError(`<${spec.name}> is one of ${spec.enum.join(', ')}, not ${value}${suggest(value, spec.enum, (s) => s)}`);
  }
  return value;
}

// ---------------------------------------------------------------------------------------------
// Running it

async function run(api: DynamicConsoleClient, command: CommandSpec, call: Call, write: (s: string) => void): Promise<void> {
  if (command.response === 'events') {
    for await (const event of api.events(command.operationId, call.input)) write(formatEvent(event, call.output));
    return;
  }
  let data: unknown;
  let status = 200;
  if (call.all) {
    const items: unknown[] = [];
    let last: Record<string, unknown> = {};
    for await (const page of api.pages(command.operationId, call.input)) {
      last = (page ?? {}) as Record<string, unknown>;
      if (Array.isArray(last.items)) items.push(...last.items);
    }
    data = { ...last, items, next: null };
  } else {
    const res = await api.send(command.operationId, call.input);
    data = res.data;
    status = res.status;
  }
  if (typeof data === 'string' && command.response !== 'json') write(data.endsWith('\n') ? data : `${data}\n`);
  else if (data === undefined) {
    if (call.output === 'table') write(`${status} ${REASONS[status] ?? ''}`.trimEnd() + '\n');
  } else write(formatData(data, call.output));
}

const REASONS: Record<number, string> = { 200: 'OK', 201: 'Created', 202: 'Accepted', 204: 'No Content', 304: 'Not Modified' };

function formatEvent(event: ServerSentEvent, output: OutputFormat): string {
  let data: unknown = event.data;
  try {
    data = JSON.parse(event.data);
  } catch {
    // not JSON: the text as it came
  }
  const record = { event: event.event, ...(event.id !== undefined ? { id: event.id } : {}), data };
  if (output === 'yaml') return `---\n${toYaml(record)}`;
  if (output === 'table') return `${[event.event, event.id ?? '', typeof data === 'string' ? data : JSON.stringify(data)].join('  ')}\n`;
  return `${JSON.stringify(record)}\n`;
}

// ---------------------------------------------------------------------------------------------
// Saying what went wrong

interface ApiErrorLike {
  name: string;
  status: number;
  error: string;
  message: string;
  problems?: readonly { field?: string; message: string }[];
  denied?: {
    layer: string;
    action: string;
    resource?: string;
    reason: string;
    role?: string;
    policyId?: string;
    statement?: string;
    scope?: string;
    requestable: boolean;
    suggestedRole?: string;
  };
  detail?: Record<string, unknown>;
  correlationId?: string;
  retryAfter?: number;
  stepUp?: boolean;
  cause?: unknown;
}

function isApiError(e: unknown): e is ApiErrorLike {
  return typeof e === 'object' && e !== null && (e as { name?: unknown }).name === 'ConsoleApiError' && typeof (e as { status?: unknown }).status === 'number';
}

function isOAuthError(e: unknown): e is { error: string; description?: string; message: string } {
  return typeof e === 'object' && e !== null && (e as { name?: unknown }).name === 'OAuthError';
}

/** What went wrong, a line for each thing the person can act on. */
export function errorLines(e: unknown, hints: { program: string; signInHint: string; endpoint?: string }): string[] {
  const { program, signInHint } = hints;
  if (isApiError(e)) {
    const reason = e.denied?.reason ?? (typeof e.detail?.message === 'string' ? e.detail.message : undefined);
    const lines = [`${e.status} ${e.error}${reason ? `: ${reason}` : ''}`];
    for (const p of e.problems ?? []) lines.push(p.field ? `${p.field}: ${p.message}` : p.message);
    const d = e.denied;
    if (d) {
      const why = [
        `${d.action}${d.resource ? ` on ${d.resource}` : ''}`,
        d.role && `role ${d.role}`,
        d.policyId && `policy ${d.policyId}`,
        d.statement && `statement ${d.statement}`,
        d.scope && `scope ${d.scope}`,
      ].filter(Boolean);
      lines.push(`denied by ${d.layer}: ${why.join('; ')}`);
      if (d.requestable) {
        const ask = JSON.stringify({ action: d.action, ...(d.resource ? { resource: d.resource } : {}), reason: '…' });
        lines.push(`you may ask for access${d.suggestedRole ? ` (the ${d.suggestedRole} role allows it)` : ''}: ${program} access-requests create --body '${ask}'`);
      }
    }
    if (e.stepUp || e.detail?.stepUp === true) lines.push(`this needs a fresh sign-in: ${signInHint} again`);
    else if (e.status === 401) {
      const cause = isOAuthError(e.cause) ? ` (${e.cause.error})` : '';
      lines.push(`the sign-in has ended or is missing${cause}: ${signInHint}`);
    }
    if (e.retryAfter !== undefined && (e.status === 429 || e.status === 503)) lines.push(`try again in ${e.retryAfter} s`);
    if (e.correlationId) lines.push(`correlation id ${e.correlationId}`);
    return lines;
  }
  if (isOAuthError(e)) {
    return [e.message, ['invalid_grant', 'invalid_token', 'access_denied', 'expired_token'].includes(e.error) ? `the sign-in has ended: ${signInHint}` : ''].filter(Boolean);
  }
  if (e instanceof Error) {
    const cause = (e as { cause?: { code?: string; message?: string } }).cause;
    if (e.name === 'TypeError' && cause && (cause.code || cause.message)) {
      return [`could not reach ${hints.endpoint ?? 'the console'} (${cause.code ?? cause.message})`];
    }
    if (e.name === 'AbortError') return ['stopped'];
    return [e.message];
  }
  return [String(e)];
}
