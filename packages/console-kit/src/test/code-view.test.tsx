import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { CodeView, cliCommand, cliFlags, curlCommand, shellQuote, toYaml } from '../index'

/* View as code: the kit's helpers turn a value into YAML, a request into curl, and words into a shell-safe line. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

describe('toYaml', () => {
  it('writes plain words unquoted and quotes what YAML would misread', () => {
    expect(toYaml({ name: 'web-01', size: 'small', on: 'yes', zip: '01234', empty: '', none: null, count: 3, ok: true })).toBe(
      ['name: web-01', 'size: small', '"on": "yes"', 'zip: "01234"', "empty: ''", 'none: null', 'count: 3', 'ok: true'].join('\n'),
    )
    expect(toYaml({ wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo01' })).toBe('wfrn: "wfrn:wf:compute:global:123456789012:vm/vm-demo01"')
  })

  it('nests by two spaces, lists with dashes, and writes empty collections inline', () => {
    expect(toYaml({ spec: { input: { image: { create: { os: 'rhel-9' } } } }, disks: [], tags: {} })).toBe(
      ['spec:', '  input:', '    image:', '      create:', '        os: rhel-9', 'disks: []', 'tags: {}'].join('\n'),
    )
    expect(toYaml({ items: [{ id: 'a', count: 1, labels: { team: 'web' } }, 'plain', [1, 2]] })).toBe(
      ['items:', '  - id: a', '    count: 1', '    labels:', '      team: web', '  - plain', '  -', '    - 1', '    - 2'].join('\n'),
    )
    expect(toYaml([{ id: 'a', count: 1 }])).toBe(['- id: a', '  count: 1'].join('\n'))
  })

  it('writes multi-line text as a block', () => {
    expect(toYaml({ note: 'first line\nsecond line' })).toBe(['note: |', '  first line', '  second line'].join('\n'))
  })

  it('leaves out what is undefined', () => {
    expect(toYaml({ a: 1, b: undefined })).toBe('a: 1')
  })
})

describe('shell lines', () => {
  it('quotes only what the shell would split or expand', () => {
    expect(shellQuote('web-01')).toBe('web-01')
    expect(shellQuote('wfrn:wf:compute:global:123456789012:vm/vm-demo01')).toBe('wfrn:wf:compute:global:123456789012:vm/vm-demo01')
    expect(shellQuote('new web tier')).toBe("'new web tier'")
    expect(shellQuote("it's")).toBe("'it'\\''s'")
    expect(shellQuote('$HOME')).toBe("'$HOME'")
    expect(shellQuote('')).toBe("''")
  })

  it('writes a curl command one option per line, the body as a quoted heredoc', () => {
    expect(curlCommand({ method: 'GET', url: 'https://console.example.test/console/v1/r/vm' })).toBe(
      ["curl -X GET https://console.example.test/console/v1/r/vm", "  -H 'Accept: application/json'"].join(' \\\n'),
    )
    const post = curlCommand({ method: 'POST', url: 'https://x.test/r/vm', headers: { 'Idempotency-Key': 'k-1' }, body: { input: { name: 'web-09' } } })
    expect(post).toBe(
      [
        'curl -X POST https://x.test/r/vm \\',
        "  -H 'Accept: application/json' \\",
        "  -H 'Content-Type: application/json' \\",
        "  -H 'Idempotency-Key: k-1' \\",
        "  --data @- <<'JSON'",
        '{',
        '  "input": {',
        '    "name": "web-09"',
        '  }',
        '}',
        'JSON',
      ].join('\n'),
    )
  })

  it('turns fields into flags: kebab-case names, booleans as switches, lists joined, objects as JSON', () => {
    expect(cliFlags({ keepSnapshotDays: 7, publicIp: true, backups: false, zones: ['a', 'b'], image: { create: { os: 'rhel-9' } }, note: '', none: null })).toEqual([
      '--keep-snapshot-days 7',
      '--public-ip',
      '--no-backups',
      '--zones a,b',
      `--image '{"create":{"os":"rhel-9"}}'`,
    ])
  })

  it('keeps a short command on one line and wraps a long one a flag per line', () => {
    expect(cliCommand(['iaf', 'compute', 'get-vm'], ['--region global'])).toBe('iaf compute get-vm --region global')
    expect(cliCommand(['iaf', 'compute', 'get-vm'])).toBe('iaf compute get-vm')
    const long = cliCommand(['iaf', 'compute', 'create-vm'], ['--account 123456789012', '--region global', "--reason 'a reason long enough to push the line past one hundred characters'"])
    expect(long).toBe(['iaf compute create-vm', '--account 123456789012', '--region global', "--reason 'a reason long enough to push the line past one hundred characters'"].join(' \\\n  '))
  })
})

describe('CodeView', () => {
  const samples = [
    { id: 'cli', label: 'CLI', language: 'shell', code: 'iaf compute get-vm vm-demo01', note: 'iaf is the generated console CLI.' },
    { id: 'yaml', label: 'YAML', language: 'yaml', code: 'name: web-01' },
  ]

  it('shows the first sample under a segmented control, with its note', () => {
    const html = text(renderToString(<CodeView samples={samples} ariaLabel="Restart as code" />))
    expect(html).toContain('data-testid="code-view"')
    expect(html).toContain('aria-label="Restart as code"')
    expect(html).toContain('iaf compute get-vm vm-demo01')
    expect(html).toContain('iaf is the generated console CLI.')
    expect(html).not.toContain('name: web-01')
  })

  it('lets a keyboard reach the scroll area, named by the sample', () => {
    const html = text(renderToString(<CodeView samples={samples} />))
    expect(html).toMatch(/<pre tabindex="0" role="region" aria-label="CLI code"/)
  })

  it('opens on the chosen sample, and drops the control for a single one', () => {
    expect(text(renderToString(<CodeView samples={samples} defaultId="yaml" />))).toContain('name: web-01')
    expect(text(renderToString(<CodeView samples={samples} value="yaml" />))).toContain('name: web-01')
    const one = text(renderToString(<CodeView samples={[samples[1]]} ariaLabel="Only YAML" />))
    expect(one).not.toContain('aria-label="Only YAML"')
    expect(renderToString(<CodeView samples={[]} />)).toBe('')
  })
})
