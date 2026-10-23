import type { CommandSpec, FlagSpec, GroupSpec } from './spec.js';

/** A line of help for a command or a flag the host adds (the CLI's `login`, its `--endpoint`). */
export interface HelpEntry {
  readonly name: string;
  readonly summary: string;
}

export interface HelpContext {
  readonly program: string;
  readonly commands: readonly HelpEntry[];
  readonly globalFlags: readonly HelpEntry[];
}

export const GLOBAL_FLAG_HELP: readonly HelpEntry[] = [
  { name: '-o, --output json|table|yaml', summary: 'How to print the answer' },
  { name: '--account <id>', summary: 'The account to act in (X-Console-Account); else the session\'s' },
  { name: '--region <name>', summary: 'The region to act in (X-Console-Region); else the session\'s' },
  { name: '--all', summary: 'Every page of a list, not only the first' },
  { name: '--body <json>', summary: 'The request body, as JSON' },
  { name: '--body-file <path>', summary: 'The request body, read from a file (- reads standard input)' },
  { name: '--idempotency-key <key>', summary: 'The Idempotency-Key of a write (a new UUID by default)' },
  { name: '-h, --help', summary: 'Help for the console, a group or a command' },
];

export function topHelp(groups: readonly GroupSpec[], ctx: HelpContext): string {
  const p = ctx.program;
  const out = [
    `${p}: the customer console from the command line`,
    '',
    'Usage:',
    `  ${p} <group> <command> [arguments] [flags]`,
    `  ${p} help [<group> [<command>]]`,
    '',
    'Groups, one per tag of the console\'s API:',
    ...columns(groups.map((g) => [g.name, g.description ?? g.tag])),
  ];
  if (ctx.commands.length > 0) {
    out.push('', 'More commands:', ...columns(ctx.commands.map((c) => [c.name, c.summary])));
  }
  out.push('', 'Global flags:', ...columns([...GLOBAL_FLAG_HELP, ...ctx.globalFlags].map((f) => [f.name, f.summary])));
  out.push('', `Run \`${p} <group> --help\` for a group's commands, \`${p} <group> <command> --help\` for one.`);
  return `${out.join('\n')}\n`;
}

export function groupHelp(group: GroupSpec, ctx: HelpContext): string {
  const p = ctx.program;
  const own = group.commands.find((c) => c.groupDefault);
  const out = [`${p} ${group.name}: ${group.description ?? group.tag}`, '', 'Usage:'];
  if (own) out.push(`  ${usage(p, group, own)}`);
  if (group.commands.some((c) => !c.groupDefault)) out.push(`  ${p} ${group.name} <command> [arguments] [flags]`);
  out.push(
    '',
    'Commands:',
    ...columns(group.commands.map((c) => [c.groupDefault ? `${c.name} (the group's own)` : c.name, c.summary ?? c.operationId])),
    '',
    `Run \`${p} ${group.name} <command> --help\` for a command's arguments and flags.`,
  );
  return `${out.join('\n')}\n`;
}

export function commandHelp(group: GroupSpec, command: CommandSpec, ctx: HelpContext): string {
  const p = ctx.program;
  const out = [`${p} ${group.name} ${command.name}: ${command.summary ?? command.operationId}`, '', 'Usage:', `  ${usage(p, group, command)}`];
  out.push('', `  ${command.method} ${command.path}  (operation ${command.operationId})`);
  if (command.description) out.push('', ...wrap(command.description, 2, 96));

  const rows: string[][] = [];
  for (const f of command.flags) {
    const positional = f.in === 'path' ? `<${f.name}>, ` : '';
    const notes = [
      f.description,
      f.enum && f.enum.join('|').length > 40 ? `one of ${f.enum.join(', ')}` : undefined,
      f.required && f.in !== 'path' ? 'required' : undefined,
      f.default !== undefined ? `default ${String(f.default)}` : undefined,
      f.namePattern ? `any name matching ${f.namePattern}, as often as needed` : undefined,
      f.array ? 'may be repeated' : undefined,
    ].filter((n): n is string => Boolean(n));
    rows.push([`${positional}--${f.flag}${flagValue(f)}`, notes.join('; ')]);
  }
  if (command.body) {
    const schema = command.body.schema ? ` (${command.body.schema})` : '';
    rows.push(['--body <json>, --body-file <path>', `the request body${schema}${command.body.required ? '; required' : ''}`]);
  }
  if (command.paged) rows.push(['--all', 'every page, following `next`; else one page (--limit, --after)']);
  if (rows.length > 0) out.push('', 'Arguments and flags:', ...columns(rows));
  out.push('', `Global flags: --output, --account, --region, --idempotency-key; \`${p} --help\` lists them.`);
  return `${out.join('\n')}\n`;
}

export function usage(program: string, group: GroupSpec, command: CommandSpec): string {
  const words = [program, group.name];
  if (!command.groupDefault) words.push(command.name);
  for (const name of command.positionals) words.push(`<${name}>`);
  for (const f of command.flags) {
    if (f.in !== 'path' && f.required) words.push(`--${f.flag}${flagValue(f)}`);
  }
  if (command.body?.required) words.push('--body <json>');
  words.push('[flags]');
  return words.join(' ');
}

function flagValue(f: FlagSpec): string {
  if (f.type === 'boolean') return '';
  if (f.enum && f.enum.join('|').length <= 40) return ` ${f.enum.join('|')}`;
  return ` <${f.type === 'integer' || f.type === 'number' ? f.type : 'string'}>`;
}

function columns(rows: readonly (readonly string[])[]): string[] {
  const width = Math.min(36, Math.max(0, ...rows.map((r) => r[0]!.length)));
  const out: string[] = [];
  for (const [left, right] of rows) {
    const text = (right ?? '').replace(/\s+/g, ' ').trim();
    if (left!.length > width) {
      out.push(`  ${left}`);
      if (text) out.push(...wrap(text, width + 4, 100));
    } else {
      const lines = wrap(text, width + 4, 100);
      out.push(`  ${left!.padEnd(width)}  ${(lines[0] ?? '').trimStart()}`.trimEnd(), ...lines.slice(1));
    }
  }
  return out;
}

/** Wraps text at `width` columns, each line indented by `indent`; paragraphs stay apart. */
export function wrap(text: string, indent: number, width: number): string[] {
  const pad = ' '.repeat(indent);
  const lines: string[] = [];
  const paragraphs = text.trim().split(/\n\s*\n/);
  for (const [i, paragraph] of paragraphs.entries()) {
    if (i > 0) lines.push('');
    let line = '';
    for (const word of paragraph.trim().split(/\s+/)) {
      if (line && pad.length + line.length + 1 + word.length > width) {
        lines.push(pad + line);
        line = word;
      } else {
        line = line ? `${line} ${word}` : word;
      }
    }
    if (line) lines.push(pad + line);
  }
  return lines;
}
