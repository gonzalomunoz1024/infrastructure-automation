import { describe, expect, it } from 'vitest'
import type { Problem } from '@iaf/workflow-checks'
import { expressionMarkers, lineOfProblem, pathSegments, problemMarkers, syntaxPosition } from '../components/code-editor/markers'

/* The YAML drawer's markers: where a check problem sits in the file, found by its keys. */

// the `yaml` package's style: a sequence indented under its key
const INDENTED = [
  'apiVersion: iaf.platform/v1', //  1
  'kind: Workflow', //  2
  'metadata:', //  3
  '  name: vm-restart', //  4
  '  owner: team-compute', //  5
  'api:', //  6
  '  method: POST', //  7
  '  input:', //  8
  '    type: object', //  9
  '    required:', // 10
  '      - vmId', // 11
  'workflow:', // 12
  '  do:', // 13
  '    - lookup:', // 14
  '        name: Look up the VM', // 15
  '        call: provider', // 16
  '        with:', // 17
  '          provider: vsphere', // 18
  '          action: vm.get', // 19
  '          input:', // 20
  '            id: "{{ $input.vmId }}"', // 21
  '    - route:', // 22
  '        switch:', // 23
  '          - when: "{{ $json.on }}"', // 24
  '            then: end', // 25
  '          - when: "{{ $json.off }}"', // 26
  '            then: restart', // 27
  '    - restart:', // 28
  '        call: provider', // 29
  '        with:', // 30
  '          provider: vsphere', // 31
].join('\n')

// Jackson's style, the backend's: a sequence at its key's own column
const UNINDENTED = [
  'workflow:', // 1
  '  do:', // 2
  '  - lookup:', // 3
  '      call: provider', // 4
  '      with:', // 5
  '        provider: vsphere', // 6
  '  - restart:', // 7
  '      with:', // 8
  '        provider: vsphere', // 9
  'triggers:', // 10
  '- type: alert', // 11
  '  input:', // 12
  '    vmId: "{{ $event.vm }}"', // 13
].join('\n')

const problem = (p: Partial<Problem>): Problem => ({ level: 'error', code: 'step.x', message: 'wrong', ...p })

describe('pathSegments', () => {
  it('splits a dotted field into keys and list positions', () => {
    expect(pathSegments('with.input.id')).toEqual(['with', 'input', 'id'])
    expect(pathSegments('switch[1].when')).toEqual(['switch', 1, 'when'])
    expect(pathSegments('triggers[0].input')).toEqual(['triggers', 0, 'input'])
  })
})

describe('lineOfProblem', () => {
  it('finds a step, then its field key by key', () => {
    expect(lineOfProblem(INDENTED, { step: 'lookup' })).toBe(14)
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'with.input.id' })).toBe(21)
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'call' })).toBe(16)
    expect(lineOfProblem(INDENTED, { step: 'restart', field: 'with.provider' })).toBe(31)
  })

  it('looks for a step’s parameter under its with when it is not beside it', () => {
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'provider' })).toBe(18)
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'input.id' })).toBe(21)
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'name' })).toBe(15)
  })

  it('counts list items, whose first key shares the dash line', () => {
    expect(lineOfProblem(INDENTED, { step: 'route', field: 'switch[0].when' })).toBe(24)
    expect(lineOfProblem(INDENTED, { step: 'route', field: 'switch[1].then' })).toBe(27)
    expect(lineOfProblem(INDENTED, { step: 'route', field: 'switch[1]' })).toBe(26)
  })

  it('finds a field of the document from the top', () => {
    expect(lineOfProblem(INDENTED, { field: 'metadata.owner' })).toBe(5)
    expect(lineOfProblem(INDENTED, { field: 'api.input.required' })).toBe(10)
  })

  it('reads a sequence written at its key’s own column', () => {
    expect(lineOfProblem(UNINDENTED, { step: 'restart', field: 'with.provider' })).toBe(9)
    expect(lineOfProblem(UNINDENTED, { step: 'lookup', field: 'with.provider' })).toBe(6)
    expect(lineOfProblem(UNINDENTED, { field: 'triggers[0].input.vmId' })).toBe(13)
  })

  it('stops at the deepest key it found, and falls back to the first line', () => {
    expect(lineOfProblem(INDENTED, { step: 'lookup', field: 'with.nothing' })).toBe(17)
    expect(lineOfProblem(INDENTED, { step: 'gone', field: 'with' })).toBe(1)
    expect(lineOfProblem(INDENTED, {})).toBe(1)
  })
})

describe('syntaxPosition', () => {
  it('reads the line and column a parser names', () => {
    expect(syntaxPosition("mapping values are not allowed here\n in 'reader', line 4, column 9:")).toEqual({ line: 4, column: 9 })
    expect(syntaxPosition('Unexpected character at [Source: (StringReader); line: 3, column: 5]')).toEqual({ line: 3, column: 5 })
    expect(syntaxPosition('mapping values are not allowed here (line 7)')).toEqual({ line: 7, column: 1 })
    expect(syntaxPosition('not a mapping')).toBeNull()
  })
})

describe('problemMarkers', () => {
  it('marks each problem from its key to the end of its line, with its field and level', () => {
    const [step, doc] = problemMarkers(INDENTED, [
      problem({ level: 'warning', code: 'expr.unknownInput', step: 'lookup', field: 'with.input.id', message: "The input has no field 'vmId'" }),
      problem({ code: 'metadata.owner', field: 'metadata.owner', message: 'owner is a team' }),
    ])
    expect(step).toEqual({ startLineNumber: 21, startColumn: 13, endLineNumber: 21, endColumn: 36, message: "with.input.id: The input has no field 'vmId'", severity: 'warning', code: 'expr.unknownInput' })
    expect(doc).toMatchObject({ startLineNumber: 5, startColumn: 3, severity: 'error', message: 'metadata.owner: owner is a team' })
  })

  it('starts a step’s marker after the dash, and a syntax error at its column', () => {
    const [step, syntax] = problemMarkers(INDENTED, [problem({ step: 'restart' }), problem({ code: 'yaml.invalid', message: 'YAML syntax: bad indentation of a mapping entry (line 18, column 11)' })])
    expect(step).toMatchObject({ startLineNumber: 28, startColumn: 7, endColumn: 15 })
    expect(syntax).toMatchObject({ startLineNumber: 18, startColumn: 11, endColumn: 28, message: 'YAML syntax: bad indentation of a mapping entry (line 18, column 11)' })
  })

  it('keeps a line past the end of the text on the last line', () => {
    const [m] = problemMarkers('kind: Workflow\n', [problem({ code: 'yaml.invalid', message: 'end of the stream (line 9, column 1)' })])
    expect(m.startLineNumber).toBe(2)
    expect(m.endColumn).toBe(2)
  })
})

describe('expressionMarkers', () => {
  it('marks the expressions that do not resolve, across lines', () => {
    const text = 'Restart {{ $input.vmId }}\nfor {{ $nope.x }}'
    const markers = expressionMarkers(text, (e) => (e.includes('$nope') ? 'No such variable $nope' : null))
    expect(markers).toEqual([{ startLineNumber: 2, startColumn: 5, endLineNumber: 2, endColumn: 18, message: 'No such variable $nope', severity: 'error', code: 'expr' }])
    expect(expressionMarkers('plain text', () => 'never asked')).toEqual([])
  })
})
