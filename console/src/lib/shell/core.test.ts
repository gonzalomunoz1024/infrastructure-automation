import { describe, expect, it } from 'vitest'
import { completeCommand, execute, inputOf, listFiltersOf, typeOfWfrn, type ShellClient, type ShellJob, type ShellOutput } from './core'
import { ParseError, parseLine, tokenize } from './parse'
import { tableText, toText } from './render'

/* The console CLI's command core, run against a fake client as the console shell and the CLI would run it. */

const WFRN = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'

function fakeClient(over: Partial<ShellClient> = {}): ShellClient & { calls: unknown[][] } {
  const calls: unknown[][] = []
  let polls = 0
  const job = (status: string): ShellJob => ({ jobId: 'job_1', workflow: 'vm-restart', status, wfrn: WFRN, steps: [{ id: 's1', name: 'apply', status: status === 'succeeded' ? 'succeeded' : 'running' }] })
  return {
    calls,
    session: async () => ({ user: { userId: 'dana', name: 'Dana Reyes', email: 'dana@example.test', groups: ['members'] }, account: { accountId: '123456789012', name: 'Compute', roles: ['operator'] }, region: 'global' }),
    types: async () => [
      {
        key: 'compute/vm',
        type: 'vm',
        service: 'compute',
        plural: 'vms',
        title: 'Virtual machines',
        actions: [
          { verb: 'list', allowed: true },
          { verb: 'restart', allowed: true },
          { verb: 'delete', destructive: true, allowed: true },
          { verb: 'patch', allowed: false, denied: { reason: 'Your role does not allow compute:patch', action: 'compute:patch' } },
        ],
      },
    ],
    list: async (type, params) => {
      calls.push(['list', type, params])
      return { items: [{ wfrn: WFRN, type: 'vm', name: 'web-01', state: 'active', drift: false, tags: { environment: 'prod' }, attributes: { size: 'm' } }], next: 'c50' }
    },
    get: async (type, wfrn) => {
      calls.push(['get', type, wfrn])
      return { wfrn, type, name: 'web-01', state: 'active', version: 3, drift: false, tags: { environment: 'prod' }, attributes: { size: 'm' } }
    },
    run: async (type, wfrn, verb, body, key) => {
      calls.push(['run', type, wfrn, verb, body, key])
      return { jobId: 'job_1', wfrn }
    },
    jobs: async () => ({ items: [job('running')], next: null }),
    job: async () => job(++polls >= 3 ? 'succeeded' : 'running'),
    ...over,
  }
}

async function run(line: string, client = fakeClient()) {
  const out: ShellOutput[] = []
  const ok = await execute(line, { client, emit: (o) => out.push(o), sleep: async () => {}, newKey: () => 'key-1', describeError: (e) => `failed: ${String(e)}` })
  return { ok, out, text: out.map(toText).join('\n'), client }
}

describe('the command line', () => {
  it('splits words with quotes and escapes, and drops the CLI’s own name', () => {
    expect(tokenize(`run restart w --input reason="stuck after an update" --input "note=say \\"hi\\"" a\\ b 'x y'`)).toEqual(['run', 'restart', 'w', '--input', 'reason=stuck after an update', '--input', 'note=say "hi"', 'a b', 'x y'])
    expect(parseLine('iaf ls vm -f environment=prod --limit=5').flags).toEqual({ filter: ['environment=prod'], limit: ['5'] })
    expect(parseLine('job job_1 --follow', new Set(['follow'])).flags).toEqual({ follow: ['true'] })
    expect(() => tokenize('get "unclosed')).toThrow(ParseError)
    expect(() => parseLine('ls vm --limit')).toThrow('--limit needs a value.')
  })

  it('reads filters as the list’s parameters and inputs as values', () => {
    expect(listFiltersOf(['environment=prod', 'state=active', 'q=web', 'tag.owner=team-a'])).toEqual({ 'tag.environment': 'prod', state: 'active', q: 'web', 'tag.owner': 'team-a' })
    expect(() => listFiltersOf(['prod'])).toThrow('A filter is name=value')
    expect(inputOf(['reason=a=b', 'count:=3', 'flags:=["x"]'])).toEqual({ reason: 'a=b', count: 3, flags: ['x'] })
    expect(typeOfWfrn(WFRN)).toBe('vm')
    expect(typeOfWfrn('vm-demo01')).toBeNull()
  })

  it('completes a command it can name alone', () => {
    expect(completeCommand('wh')).toBe('whoami ')
    expect(completeCommand('j')).toBeNull()
    expect(completeCommand('ls v')).toBeNull()
  })
})

describe('the commands', () => {
  it('lists with server-side filters and says how to read on', async () => {
    const { ok, out, text, client } = await run('ls vm --filter environment=prod')
    expect(ok).toBe(true)
    expect(client.calls[0]).toEqual(['list', 'vm', { 'tag.environment': 'prod', limit: 50, after: undefined }])
    expect(out[0]).toMatchObject({ kind: 'table', caption: 'Virtual machines where environment=prod', columns: ['NAME', 'STATE', 'ENVIRONMENT', 'DRIFT', 'WFRN'] })
    expect(text).toContain('1 shown. More with: ls vm --filter environment=prod --after c50')
  })

  it('reads one resource by its wfrn, as a table or as JSON', async () => {
    const table = await run(`get ${WFRN}`)
    expect(table.client.calls[0]).toEqual(['get', 'vm', WFRN])
    expect(table.text).toMatch(/^FIELD\s+VALUE\nName\s+web-01/)
    expect(table.text).toContain('Tag environment  prod')
    const json = await run(`get ${WFRN} -o json`)
    expect(json.out[0]).toMatchObject({ kind: 'json', value: { name: 'web-01' } })
  })

  it('runs a verb with its input and one key, then follows the job to its end', async () => {
    const { ok, text, client } = await run(`run restart ${WFRN} --input reason="stuck" --wait`)
    expect(ok).toBe(true)
    expect(client.calls[0]).toEqual(['run', 'vm', WFRN, 'restart', { input: { reason: 'stuck' } }, 'key-1'])
    expect(text).toContain('Started restart as job job_1.')
    expect(text).toContain('job_1 is running: apply.')
    expect(text).toContain('job_1 is succeeded.')
  })

  it('refuses before calling: a denied verb with its reason, a destructive one without --confirm', async () => {
    const denied = await run(`run patch ${WFRN}`)
    expect(denied.text).toContain('Not allowed: Your role does not allow compute:patch.')
    expect(denied.client.calls).toEqual([])
    const destructive = await run(`run delete ${WFRN}`)
    expect(destructive.ok).toBe(false)
    expect(destructive.text).toContain('delete cannot be undone. Add --confirm to run it.')
    const confirmed = await run(`run delete ${WFRN} --confirm`)
    expect(confirmed.client.calls[0]).toEqual(['run', 'vm', WFRN, 'delete', { input: {}, confirm: true }, 'key-1'])
  })

  it('says who it acts as, what failed, and what it does not know', async () => {
    expect((await run('whoami')).text).toContain('Account  Compute (123456789012)')
    expect((await run('frobnicate')).text).toBe('There is no command frobnicate. Type help for the list.')
    expect((await run('ls')).text).toContain('Usage: ls <type>')
    const failing = await run('jobs', fakeClient({ jobs: async () => Promise.reject(new Error('boom')) }))
    expect(failing.ok).toBe(false)
    expect(failing.text).toBe('failed: Error: boom')
    expect((await run('help run')).text).toContain('run <verb> <wfrn>')
    expect((await run('ls --help')).text).toContain('ls vm --filter environment=prod')
  })

  it('stops following when asked, and the job goes on', async () => {
    const stop = new AbortController()
    stop.abort()
    const out: ShellOutput[] = []
    const ok = await execute('job job_1 --follow', { client: fakeClient(), emit: (o) => out.push(o), signal: stop.signal, sleep: async () => {} })
    expect(ok).toBe(false)
    expect(out.map(toText).join('\n')).toContain('Stopped following job_1.')
  })
})

describe('the terminal’s text', () => {
  it('aligns columns two spaces apart, without padding the last', () => {
    expect(tableText(['A', 'LONGER'], [['xyz', '1'], ['q', '22']])).toBe('A    LONGER\nxyz  1\nq    22')
  })
})
