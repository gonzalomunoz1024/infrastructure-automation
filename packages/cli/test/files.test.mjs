import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, utimes, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';
import { configDir, Files } from '../dist/files.js';

describe('the CLI\'s files', () => {
  let root;
  before(async () => {
    root = await mkdtemp(path.join(os.tmpdir(), 'console-files-'));
  });
  after(() => rm(root, { recursive: true, force: true }));

  test('the directory: CONSOLE_CONFIG_DIR, else XDG_CONFIG_HOME/console, else ~/.config/console', () => {
    assert.equal(configDir({ CONSOLE_CONFIG_DIR: '/x/y' }, '/home/a'), '/x/y');
    assert.equal(configDir({ XDG_CONFIG_HOME: '/xdg' }, '/home/a'), path.join('/xdg', 'console'));
    assert.equal(configDir({}, '/home/a'), path.join('/home/a', '.config', 'console'));
  });

  test('config and credentials are written with mode 0600 in a 0700 directory', async () => {
    const files = new Files(path.join(root, 'modes', 'console'));
    await files.writeConfig({ region: 'eu-west-1' });
    await files.saveSession('https://a.example.com', { accessToken: 'at', tokenType: 'Bearer', refreshToken: 'rt', expiresAt: Date.parse('2026-10-23T12:15:00Z') });
    assert.equal((await stat(files.dir)).mode & 0o777, 0o700);
    assert.equal((await stat(files.configFile)).mode & 0o777, 0o600);
    assert.equal((await stat(files.credentialsFile)).mode & 0o777, 0o600);
    const stored = JSON.parse(await readFile(files.credentialsFile, 'utf8'));
    assert.equal(stored.sessions['https://a.example.com'].expiresAt, '2026-10-23T12:15:00.000Z');
  });

  test('each endpoint keeps its own tokens', async () => {
    const files = new Files(path.join(root, 'endpoints'));
    await files.saveSession('https://a.example.com', { accessToken: 'a', tokenType: 'Bearer' });
    await files.saveSession('https://b.example.com', { accessToken: 'b', tokenType: 'Bearer', refreshToken: 'rb' });
    assert.equal((await files.store('https://a.example.com').load()).accessToken, 'a');
    assert.equal((await files.store('https://b.example.com').load()).refreshToken, 'rb');
    await files.removeSession('https://a.example.com');
    assert.equal(await files.store('https://a.example.com').load(), undefined);
    assert.equal((await files.store('https://b.example.com').load()).accessToken, 'b');
  });

  test('the lock lets one holder in at a time', async () => {
    const files = new Files(path.join(root, 'lock'));
    let inside = 0;
    let most = 0;
    await Promise.all(
      Array.from({ length: 5 }, () =>
        files.locked(async () => {
          inside++;
          most = Math.max(most, inside);
          await new Promise((r) => setTimeout(r, 20));
          inside--;
        }),
      ),
    );
    assert.equal(most, 1);
  });

  test('a lock untouched for 30 s is a crashed command\'s and is taken over; a fresh one is waited for', async () => {
    const files = new Files(path.join(root, 'stale'));
    await files.writeConfig({});
    const lock = `${files.credentialsFile}.lock`;
    await writeFile(lock, '99999');
    const old = new Date(Date.now() - 60_000);
    await utimes(lock, old, old);
    assert.equal(await files.locked(async () => 'ran'), 'ran');
    await writeFile(lock, '99999');
    await assert.rejects(files.locked(async () => 'ran', 200), /another console command holds/);
  });

  test('a file that is not JSON is named', async () => {
    const files = new Files(path.join(root, 'broken'));
    await files.writeConfig({});
    await writeFile(files.configFile, '{oops');
    await assert.rejects(files.readConfig(), /config\.json is not JSON: fix it or remove it/);
  });
});
