// The CLI's files, in ~/.config/console/ (or $XDG_CONFIG_HOME/console, or $CONSOLE_CONFIG_DIR):
//
//   config.json       endpoint, account, region, output (`console configure set …`)
//   credentials.json  the tokens of each endpoint signed in to (`console login`)
//
// Both are written whole, to a temporary file renamed over the old one, with mode 0600 in a 0700
// directory. A refresh holds credentials.json.lock: the refresh token rotates, and the BFF ends
// the whole API session when a rotated one comes back, so two commands must never both use it.

import { randomBytes } from 'node:crypto';
import { chmod, mkdir, open, readFile, rename, stat, unlink, utimes, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { TokenSet, TokenStore } from '@iaf/console-sdk';

export interface Config {
  endpoint?: string;
  account?: string;
  region?: string;
  output?: string;
}

export const CONFIG_KEYS = ['account', 'region', 'output', 'endpoint'] as const;
export type ConfigKey = (typeof CONFIG_KEYS)[number];

/** One endpoint's tokens as credentials.json keeps them (times as ISO strings). */
interface StoredSession {
  clientId?: string;
  tokenType: string;
  accessToken: string;
  refreshToken?: string;
  expiresAt?: string;
  refreshExpiresAt?: string;
  scope?: string;
  savedAt: string;
}

interface CredentialsFile {
  version: 1;
  sessions: Record<string, StoredSession>;
}

export function configDir(env: Record<string, string | undefined>, home: string): string {
  if (env.CONSOLE_CONFIG_DIR) return env.CONSOLE_CONFIG_DIR;
  return path.join(env.XDG_CONFIG_HOME || path.join(home, '.config'), 'console');
}

export class Files {
  readonly configFile: string;
  readonly credentialsFile: string;

  constructor(readonly dir: string) {
    this.configFile = path.join(dir, 'config.json');
    this.credentialsFile = path.join(dir, 'credentials.json');
  }

  async readConfig(): Promise<Config> {
    return ((await readJson(this.configFile)) as Config | undefined) ?? {};
  }

  async writeConfig(config: Config): Promise<void> {
    await this.writePrivate(this.configFile, config);
  }

  /** The tokens of one endpoint, kept in credentials.json, for the SDK's refreshing credentials. */
  store(endpoint: string): TokenStore {
    return {
      load: async () => fromStored((await this.readCredentials()).sessions[endpoint]),
      save: (tokens) => this.saveSession(endpoint, tokens),
      exclusive: (task) => this.locked(task),
    };
  }

  async saveSession(endpoint: string, tokens: TokenSet): Promise<void> {
    const file = await this.readCredentials();
    file.sessions[endpoint] = toStored(tokens);
    await this.writePrivate(this.credentialsFile, file);
  }

  async removeSession(endpoint: string): Promise<void> {
    const file = await this.readCredentials();
    if (!(endpoint in file.sessions)) return;
    delete file.sessions[endpoint];
    await this.writePrivate(this.credentialsFile, file);
  }

  async readCredentials(): Promise<CredentialsFile> {
    const file = (await readJson(this.credentialsFile)) as CredentialsFile | undefined;
    return file && typeof file.sessions === 'object' && file.sessions !== null ? file : { version: 1, sessions: {} };
  }

  /**
   * Runs `task` holding credentials.json.lock. The holder touches the lock every 5 s, so a lock
   * untouched for 30 s is a crashed command's and is taken over.
   */
  async locked<T>(task: () => Promise<T>, timeoutMs = 15_000): Promise<T> {
    await this.ensureDir();
    const lock = `${this.credentialsFile}.lock`;
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try {
        const handle = await open(lock, 'wx', 0o600);
        await handle.writeFile(String(process.pid));
        await handle.close();
        break;
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e;
        const age = await stat(lock).then(
          (s) => Date.now() - s.mtimeMs,
          () => 0,
        );
        if (age > 30_000) {
          await unlink(lock).catch(() => undefined);
          continue;
        }
        if (Date.now() > deadline) throw new Error(`another console command holds ${lock}; remove it if none is running`);
        await new Promise((resolve) => setTimeout(resolve, 25 + Math.random() * 50));
      }
    }
    const heartbeat = setInterval(() => {
      const now = new Date();
      utimes(lock, now, now).catch(() => undefined);
    }, 5_000);
    heartbeat.unref();
    try {
      return await task();
    } finally {
      clearInterval(heartbeat);
      await unlink(lock).catch(() => undefined);
    }
  }

  private async ensureDir(): Promise<void> {
    await mkdir(this.dir, { recursive: true, mode: 0o700 });
  }

  private async writePrivate(file: string, value: unknown): Promise<void> {
    await this.ensureDir();
    const temporary = `${file}.${process.pid}.${randomBytes(4).toString('hex')}.tmp`;
    try {
      await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
      await chmod(temporary, 0o600);
      await rename(temporary, file);
    } catch (e) {
      await unlink(temporary).catch(() => undefined);
      throw e;
    }
  }
}

async function readJson(file: string): Promise<unknown> {
  let text: string;
  try {
    text = await readFile(file, 'utf8');
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
    throw e;
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${file} is not JSON: fix it or remove it`);
  }
}

function toStored(t: TokenSet): StoredSession {
  return {
    clientId: t.clientId,
    tokenType: t.tokenType,
    accessToken: t.accessToken,
    refreshToken: t.refreshToken,
    expiresAt: t.expiresAt === undefined ? undefined : new Date(t.expiresAt).toISOString(),
    refreshExpiresAt: t.refreshExpiresAt === undefined ? undefined : new Date(t.refreshExpiresAt).toISOString(),
    scope: t.scope,
    savedAt: new Date().toISOString(),
  };
}

function fromStored(s: StoredSession | undefined): TokenSet | undefined {
  if (!s || typeof s.accessToken !== 'string') return undefined;
  return {
    clientId: s.clientId,
    tokenType: s.tokenType ?? 'Bearer',
    accessToken: s.accessToken,
    refreshToken: s.refreshToken,
    expiresAt: s.expiresAt ? Date.parse(s.expiresAt) : undefined,
    refreshExpiresAt: s.refreshExpiresAt ? Date.parse(s.refreshExpiresAt) : undefined,
    scope: s.scope,
  };
}
