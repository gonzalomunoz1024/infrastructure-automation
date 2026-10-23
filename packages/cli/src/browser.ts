import { spawn } from 'node:child_process';

/** Whether to try a browser: a person at a terminal of a machine with a display. */
export function canOpenBrowser(env: Record<string, string | undefined>, platform: NodeJS.Platform, interactive: boolean): boolean {
  if (!interactive || env.CI || env.SSH_CONNECTION || env.SSH_TTY) return false;
  if (env.BROWSER === 'none') return false;
  if (platform === 'darwin' || platform === 'win32') return true;
  return Boolean(env.DISPLAY || env.WAYLAND_DISPLAY);
}

/** Opens an http(s) URL in the default browser, without a shell; answers whether it started. */
export function openBrowser(url: string, platform: NodeJS.Platform = process.platform): Promise<boolean> {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return Promise.resolve(false);
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return Promise.resolve(false);
  const href = parsed.toString();
  const [command, args] =
    platform === 'darwin'
      ? ['open', [href]]
      : platform === 'win32'
        ? ['rundll32', ['url.dll,FileProtocolHandler', href]]
        : ['xdg-open', [href]];
  return new Promise((resolve) => {
    try {
      const child = spawn(command, args, { stdio: 'ignore', detached: true });
      child.once('error', () => resolve(false));
      child.once('spawn', () => {
        child.unref();
        resolve(true);
      });
    } catch {
      resolve(false);
    }
  });
}
