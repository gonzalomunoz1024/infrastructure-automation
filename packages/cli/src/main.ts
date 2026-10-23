// console: the customer console from the command line. `login`, `logout`, `whoami` and
// `configure` are the CLI's own; every other command is the command core's, one per operation of
// the BFF's contract, run with the credentials `console login` keeps.

import { readFile } from 'node:fs/promises';
import os from 'node:os';
import {
  createConsoleClient,
  OAuthError,
  pollDeviceAuthorization,
  refreshingCredentials,
  revokeToken,
  startDeviceAuthorization,
  type ConsoleClient,
  type TokenSet,
} from '@iaf/console-sdk';
import {
  errorLines,
  execute,
  formatData,
  GLOBAL_FLAGS,
  isFlag,
  OUTPUT_FORMATS,
  parseArgs,
  UsageError,
  type FlagDef,
  type HelpEntry,
  type OutputFormat,
  type ParsedArgs,
} from '@iaf/console-cli-core';
import { canOpenBrowser, openBrowser } from './browser.js';
import { CONFIG_KEYS, configDir, Files, type Config, type ConfigKey } from './files.js';

export const VERSION = '0.1.0';
export const CLIENT_ID = 'console-cli';
/** The BFF's own port on a developer's machine. */
export const DEFAULT_ENDPOINT = 'http://localhost:18081';

export interface Io {
  env: Record<string, string | undefined>;
  stdout: { write(chunk: string): unknown; isTTY?: boolean };
  stderr: { write(chunk: string): unknown; isTTY?: boolean };
  /** Standard input, for `--body-file -`. */
  readStdin?: () => Promise<string>;
  fetch?: typeof fetch;
  openBrowser?: (url: string) => Promise<boolean>;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  now?: () => number;
  home?: string;
  platform?: NodeJS.Platform;
}

const OWN_COMMANDS: readonly HelpEntry[] = [
  { name: 'login', summary: 'Sign in with a code you approve in the browser (device authorization)' },
  { name: 'logout', summary: 'Sign out: revoke the API session and forget its tokens' },
  { name: 'whoami', summary: 'Who is signed in, in which account and region' },
  { name: 'configure', summary: 'configure set|get|unset account|region|output|endpoint, configure list' },
];
const OWN_FLAGS: readonly HelpEntry[] = [{ name: '--endpoint <url>', summary: `The console's address (else configured, else ${DEFAULT_ENDPOINT})` }];

const OWN_HELP: Record<string, string> = {
  login: `Usage: console login [--no-browser]

Signs in with the OAuth 2.0 device authorization grant: prints a code and the page to enter it
at, opens that page in the browser when it can, and waits while you approve the sign-in there.
The tokens are kept in credentials.json in the configuration directory, mode 0600.

  --no-browser   Only print the page and the code
`,
  logout: `Usage: console logout

Revokes the API session at the console (POST /auth/revoke) and forgets its tokens.
`,
  whoami: `Usage: console whoami

Who is signed in to the endpoint, in which account and region, and when the API session ends.
`,
  configure: `Usage:
  console configure set <key> <value>
  console configure get <key>
  console configure unset <key>
  console configure list

Keys:
  endpoint   The console's address, such as https://console.example.com
  account    The account to act in, sent as X-Console-Account (else the sign-in's)
  region     The region to act in, sent as X-Console-Region (else the sign-in's)
  output     json, table or yaml at a terminal (piped output is JSON unless -o says otherwise)

Kept in config.json in the configuration directory, mode 0600. CONSOLE_ENDPOINT,
CONSOLE_ACCOUNT, CONSOLE_REGION and CONSOLE_OUTPUT override it, and flags override those.
`,
};

/** Runs one command line; answers the exit code. */
export async function main(argv: readonly string[], io: Io): Promise<number> {
  const err = (line: string) => io.stderr.write(`${line}\n`);
  let reached: string | undefined;
  try {
    const { endpoint: endpointFlag, rest } = takeEndpoint(argv);
    const files = new Files(configDir(io.env, io.home ?? os.homedir()));
    const config = await files.readConfig();
    const endpoint = normalizeEndpoint(endpointFlag ?? io.env.CONSOLE_ENDPOINT ?? config.endpoint ?? DEFAULT_ENDPOINT, '--endpoint');
    reached = endpoint;
    const envOutput = io.env.CONSOLE_OUTPUT;
    if (envOutput !== undefined && !OUTPUT_FORMATS.includes(envOutput as OutputFormat)) {
      throw new UsageError(`CONSOLE_OUTPUT is one of ${OUTPUT_FORMATS.join(', ')}, not ${envOutput}`);
    }
    // piped output is JSON; at a terminal, the configured format or a table
    const output = (envOutput ?? (io.stdout.isTTY ? (config.output ?? 'table') : 'json')) as OutputFormat;
    const ctx: Ctx = { io, files, config, endpoint, output, err };

    const at = rest.findIndex((t, i) => !isFlag(t) && !isValueOf(rest, i));
    const word = at < 0 ? undefined : rest[at];
    if (word === undefined && (rest.includes('--version') || rest.includes('-v'))) {
      io.stdout.write(`console ${VERSION}\n`);
      return 0;
    }
    if (word === 'version') {
      io.stdout.write(`console ${VERSION}\n`);
      return 0;
    }
    if (word === 'help' && rest[1] && rest[1] in OWN_HELP) {
      io.stdout.write(OWN_HELP[rest[1]]!);
      return 0;
    }
    if (word && word in OWN_HELP) {
      const args = rest.filter((_, i) => i !== at);
      if (args.includes('--help') || args.includes('-h')) {
        io.stdout.write(OWN_HELP[word]!);
        return 0;
      }
      switch (word) {
        case 'login':
          return await login(ctx, parseArgs(args, [...GLOBAL_FLAGS, { name: 'browser', type: 'boolean' }]));
        case 'logout':
          return await logout(ctx, parseArgs(args, GLOBAL_FLAGS));
        case 'whoami':
          return await whoami(ctx, parseArgs(args, GLOBAL_FLAGS));
        case 'configure':
          return await configure(ctx, parseArgs(args, GLOBAL_FLAGS));
      }
    }

    const result = await execute(rest, client(ctx), {
      output,
      readFile: (p) => (p === '-' ? (io.readStdin ?? (() => Promise.reject(new Error('no standard input'))))() : readFile(p, 'utf8')),
      onOutput: (chunk) => io.stdout.write(chunk),
      program: 'console',
      commands: OWN_COMMANDS,
      globalFlags: OWN_FLAGS,
      signInHint: 'run `console login`',
    });
    if (result.stdout) io.stdout.write(result.stdout);
    if (result.stderr) io.stderr.write(result.stderr);
    return result.exitCode;
  } catch (e) {
    if (e instanceof UsageError) {
      err(`console: ${e.message}`);
      return 2;
    }
    const lines = errorLines(e, { program: 'console', signInHint: 'run `console login`', endpoint: reached });
    lines.forEach((line, i) => err(i === 0 ? `console: ${line}` : `  ${line}`));
    return 1;
  }
}

interface Ctx {
  io: Io;
  files: Files;
  config: Config;
  endpoint: string;
  output: OutputFormat;
  err: (line: string) => void;
}

function userAgent(io: Io): string {
  return `console-cli/${VERSION} (${io.platform ?? process.platform}; node ${process.versions.node})`;
}

function client(ctx: Ctx, flags?: ParsedArgs['flags']): ConsoleClient {
  const { io, files, endpoint, config } = ctx;
  return createConsoleClient({
    endpoint,
    credentials: refreshingCredentials({
      endpoint,
      store: files.store(endpoint),
      clientId: CLIENT_ID,
      fetch: io.fetch,
      userAgent: userAgent(io),
      now: io.now,
    }),
    account: (flags?.get('account') as string | undefined) ?? io.env.CONSOLE_ACCOUNT ?? config.account,
    region: (flags?.get('region') as string | undefined) ?? io.env.CONSOLE_REGION ?? config.region,
    userAgent: userAgent(io),
    fetch: io.fetch,
    sleep: io.sleep,
  });
}

function outputOf(ctx: Ctx, args: ParsedArgs): OutputFormat {
  return (args.flags.get('output') as OutputFormat | undefined) ?? ctx.output;
}

function noPositionals(args: ParsedArgs, command: string): void {
  if (args.positionals.length > 0) throw new UsageError(`${command} takes no arguments: ${args.positionals.join(' ')}`);
}

// ---------------------------------------------------------------------------------------------
// console login

async function login(ctx: Ctx, args: ParsedArgs): Promise<number> {
  noPositionals(args, 'login');
  const { io, endpoint, files } = ctx;
  const common = { endpoint, clientId: CLIENT_ID, fetch: io.fetch, userAgent: userAgent(io), now: io.now };
  let authorization;
  try {
    authorization = await startDeviceAuthorization(common);
  } catch (e) {
    if (e instanceof OAuthError && e.error === 'slow_down') {
      ctx.err('console: too many sign-ins from this address: wait a minute and try again');
      return 1;
    }
    throw e;
  }
  const minutes = Math.max(1, Math.round(authorization.expiresIn / 60));
  ctx.err(`To sign in to ${endpoint}, open ${authorization.verificationUri}`);
  ctx.err(`and enter the code ${authorization.userCode}`);
  const page = authorization.verificationUriComplete ?? authorization.verificationUri;
  const interactive = Boolean(io.stderr.isTTY);
  if (args.flags.get('browser') !== false && canOpenBrowser(io.env, io.platform ?? process.platform, interactive)) {
    if (await (io.openBrowser ?? openBrowser)(page)) ctx.err(`(opened ${page} in your browser)`);
  } else if (authorization.verificationUriComplete) {
    ctx.err(`or open ${authorization.verificationUriComplete}`);
  }
  ctx.err(`Waiting for you to approve the sign-in; the code expires in ${minutes} minute${minutes === 1 ? '' : 's'}.`);

  let tokens: TokenSet;
  try {
    tokens = await pollDeviceAuthorization({ ...common, authorization, sleep: io.sleep });
  } catch (e) {
    if (e instanceof OAuthError && e.error === 'access_denied') {
      ctx.err('console: the sign-in was denied');
      return 1;
    }
    if (e instanceof OAuthError && e.error === 'expired_token') {
      ctx.err('console: the code expired before the sign-in was approved: run `console login` again');
      return 1;
    }
    throw e;
  }

  // keep the new tokens, then end the API session they replace
  let previous: TokenSet | undefined;
  await files.locked(async () => {
    previous = await files.store(endpoint).load();
    await files.saveSession(endpoint, tokens);
  });
  if (previous?.refreshToken && previous.refreshToken !== tokens.refreshToken) {
    await revokeToken({ ...common, token: previous.refreshToken }).catch(() => undefined);
  }

  const output = outputOf(ctx, args);
  const who = await describeSession(ctx, args).catch(() => undefined);
  if (output === 'table') {
    const user = (who?.user as { email?: string } | undefined)?.email;
    const account = who?.account as { accountId?: string; name?: string } | undefined;
    const where = account ? ` in ${account.name ?? ''} (${account.accountId ?? ''})${who?.region ? `, ${String(who.region)}` : ''}` : '';
    io.stdout.write(`Signed in to ${endpoint}${user ? ` as ${user}` : ''}${where}.\n`);
  } else {
    io.stdout.write(formatData({ signedIn: true, ...(who ?? { endpoint, clientId: CLIENT_ID }) }, output));
  }
  return 0;
}

// ---------------------------------------------------------------------------------------------
// console logout

async function logout(ctx: Ctx, args: ParsedArgs): Promise<number> {
  noPositionals(args, 'logout');
  const { io, endpoint, files } = ctx;
  const tokens = await files.store(endpoint).load();
  const output = outputOf(ctx, args);
  if (!tokens) {
    if (output === 'table') io.stdout.write(`Not signed in to ${endpoint}.\n`);
    else io.stdout.write(formatData({ endpoint, signedOut: true, revoked: false }, output));
    return 0;
  }
  let revoked = false;
  const token = tokens.refreshToken ?? tokens.accessToken;
  try {
    await revokeToken({
      endpoint,
      clientId: tokens.clientId ?? CLIENT_ID,
      token,
      tokenTypeHint: tokens.refreshToken ? 'refresh_token' : 'access_token',
      fetch: io.fetch,
      userAgent: userAgent(io),
    });
    revoked = true;
  } catch (e) {
    const until = tokens.refreshExpiresAt ? `; it ends by itself at ${new Date(tokens.refreshExpiresAt).toISOString()}` : '';
    ctx.err(`console: could not revoke the API session (${(e as Error).message})${until}`);
  }
  await files.locked(() => files.removeSession(endpoint));
  if (output === 'table') io.stdout.write(`Signed out of ${endpoint}.\n`);
  else io.stdout.write(formatData({ endpoint, signedOut: true, revoked }, output));
  return 0;
}

// ---------------------------------------------------------------------------------------------
// console whoami

async function whoami(ctx: Ctx, args: ParsedArgs): Promise<number> {
  noPositionals(args, 'whoami');
  if (!(await ctx.files.store(ctx.endpoint).load())) {
    ctx.err(`console: not signed in to ${ctx.endpoint}: run \`console login\``);
    return 1;
  }
  ctx.io.stdout.write(formatData(await describeSession(ctx, args), outputOf(ctx, args)));
  return 0;
}

/** The BFF's session for these credentials, and what the access token says of the API session. */
async function describeSession(ctx: Ctx, args: ParsedArgs): Promise<Record<string, unknown>> {
  const session = await client(ctx, args.flags).call('getSession');
  const tokens = await ctx.files.store(ctx.endpoint).load();
  const claims = tokens ? jwtClaims(tokens.accessToken) : undefined;
  const seconds = (v: unknown) => (typeof v === 'number' ? new Date(v * 1000).toISOString() : undefined);
  return withoutUndefined({
    endpoint: ctx.endpoint,
    user: session.user,
    account: session.account,
    region: session.region,
    apiSession: typeof claims?.sid === 'string' ? claims.sid : undefined,
    clientId: tokens?.clientId ?? (typeof claims?.client_id === 'string' ? claims.client_id : undefined),
    signedInAt: seconds(claims?.auth_time),
    sessionExpiresAt: session.expiresAt,
    refreshExpiresAt: tokens?.refreshExpiresAt ? new Date(tokens.refreshExpiresAt).toISOString() : undefined,
  });
}

/** The claims of a JWT, read without checking it: only to show them. */
function jwtClaims(token: string): Record<string, unknown> | undefined {
  const parts = token.split('.');
  if (parts.length !== 3) return undefined;
  try {
    const claims = JSON.parse(Buffer.from(parts[1]!, 'base64url').toString('utf8')) as unknown;
    return typeof claims === 'object' && claims !== null ? (claims as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function withoutUndefined(record: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(record).filter(([, v]) => v !== undefined));
}

// ---------------------------------------------------------------------------------------------
// console configure

async function configure(ctx: Ctx, args: ParsedArgs): Promise<number> {
  const [action, key, value, ...extra] = args.positionals;
  const output = outputOf(ctx, args);
  const config = { ...ctx.config };
  const needKey = (): ConfigKey => {
    if (!key || !(CONFIG_KEYS as readonly string[]).includes(key)) {
      throw new UsageError(`configure ${action} takes a key: ${CONFIG_KEYS.join(', ')}${key ? `, not ${key}` : ''}`);
    }
    return key as ConfigKey;
  };
  switch (action) {
    case 'set': {
      const k = needKey();
      if (value === undefined || extra.length > 0) throw new UsageError(`configure set ${k} takes one value`);
      config[k] = checkConfigValue(k, value);
      await ctx.files.writeConfig(config);
      return 0;
    }
    case 'get': {
      const k = needKey();
      if (value !== undefined) throw new UsageError('configure get takes only a key');
      if (config[k] !== undefined) ctx.io.stdout.write(`${config[k]}\n`);
      return config[k] !== undefined ? 0 : 1;
    }
    case 'unset': {
      const k = needKey();
      if (value !== undefined) throw new UsageError('configure unset takes only a key');
      delete config[k];
      await ctx.files.writeConfig(config);
      return 0;
    }
    case 'list': {
      if (key !== undefined) throw new UsageError('configure list takes no arguments');
      ctx.io.stdout.write(formatData({ ...config, configDir: ctx.files.dir }, output));
      return 0;
    }
    default:
      throw new UsageError(`configure takes set, get, unset or list${action ? `, not ${action}` : ''}`);
  }
}

function checkConfigValue(key: ConfigKey, value: string): string {
  switch (key) {
    case 'account':
      if (!/^[0-9]{12}$/.test(value)) throw new UsageError(`an account is twelve digits, not ${value}`);
      return value;
    case 'region':
      if (!/^[a-z0-9-]{2,32}$/.test(value)) throw new UsageError(`a region is 2 to 32 lower-case letters, digits and dashes, not ${value}`);
      return value;
    case 'output':
      if (!OUTPUT_FORMATS.includes(value as OutputFormat)) throw new UsageError(`output is one of ${OUTPUT_FORMATS.join(', ')}, not ${value}`);
      return value;
    case 'endpoint':
      return normalizeEndpoint(value, 'the endpoint');
  }
}

// ---------------------------------------------------------------------------------------------

/** Takes `--endpoint <url>` (or `=<url>`) out of the command line, wherever it is before `--`. */
function takeEndpoint(argv: readonly string[]): { endpoint?: string; rest: string[] } {
  const rest: string[] = [];
  let endpoint: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]!;
    if (token === '--') {
      rest.push(...argv.slice(i));
      break;
    }
    if (token === '--endpoint' || token.startsWith('--endpoint=')) {
      if (endpoint !== undefined) throw new UsageError('--endpoint is given twice');
      const value = token.includes('=') ? token.slice('--endpoint='.length) : argv[++i];
      if (value === undefined) throw new UsageError('--endpoint needs a value');
      endpoint = value;
      continue;
    }
    rest.push(token);
  }
  return { endpoint, rest };
}

function normalizeEndpoint(value: string, what: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new UsageError(`${what} is an http(s) address such as https://console.example.com, not ${value}`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new UsageError(`${what} is an http(s) address, not ${value}`);
  if (url.username || url.password) throw new UsageError(`${what} must not carry a user name or password`);
  if (url.search || url.hash) throw new UsageError(`${what} has no query or fragment`);
  return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
}

/** The token at `i` is the value of the global value flag before it (`-o json`). */
function isValueOf(argv: readonly string[], i: number): boolean {
  if (i === 0) return false;
  const previous = argv[i - 1]!;
  return GLOBAL_FLAGS.some((d: FlagDef) => d.type !== 'boolean' && (previous === `--${d.name}` || (d.short !== undefined && previous === `-${d.short}`)));
}
