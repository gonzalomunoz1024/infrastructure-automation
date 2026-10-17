import { describe, expect, it } from 'vitest'
import { diffStats, parseUnifiedDiff } from '@iaf/ui'
import { countChanges, lineDiff, unifiedDiff } from '../components/diff'

describe('lineDiff', () => {
  it('keeps common lines and marks the rest', () => {
    const ops = lineDiff(['a', 'b', 'c', 'd'], ['a', 'x', 'c', 'd', 'e'])
    expect(ops.map((o) => `${o.type}:${o.text}`)).toEqual(['ctx:a', 'del:b', 'add:x', 'ctx:c', 'ctx:d', 'add:e'])
    expect(ops[0]).toMatchObject({ oldNo: 1, newNo: 1 })
    expect(ops[1]).toMatchObject({ oldNo: 2 })
    expect(ops[2]).toMatchObject({ newNo: 2 })
    expect(ops[5]).toMatchObject({ newNo: 5 })
  })
  it('handles empty sides', () => {
    expect(lineDiff([], ['a']).map((o) => o.type)).toEqual(['add'])
    expect(lineDiff(['a'], []).map((o) => o.type)).toEqual(['del'])
    expect(lineDiff([], [])).toEqual([])
  })
  it('finds the longest common subsequence, not the first match', () => {
    const ops = lineDiff(['x', 'a', 'b', 'c'], ['a', 'b', 'c', 'x'])
    expect(countChanges(ops)).toEqual({ additions: 1, deletions: 1 })
  })
})

describe('unifiedDiff', () => {
  const oldText = ['metadata:', '  name: vm-restart', '  version: 1.1.0', 'api:', '  method: POST', '  preview: true', 'workflow:', '  do:', '    - a: {}', '    - b: {}', '    - c: {}'].join('\n')
  const newText = ['metadata:', '  name: vm-restart', '  version: 1.2.0', 'api:', '  method: POST', '  preview: true', 'workflow:', '  do:', '    - a: {}', '    - b2: {}', '    - c: {}', '    - d: {}'].join('\n')

  it('is empty for identical texts', () => {
    expect(unifiedDiff(oldText, oldText)).toBe('')
  })

  it('writes hunks the kit parses back with the same line counts', () => {
    const text = unifiedDiff(oldText, newText, { oldPath: 'vm-restart@1.1.0', newPath: 'vm-restart@1.2.0', context: 2 })
    expect(text.startsWith('--- a/vm-restart@1.1.0\n+++ b/vm-restart@1.2.0\n@@ -1,5 +1,5 @@\n')).toBe(true)
    const files = parseUnifiedDiff(text)
    expect(files).toHaveLength(1)
    expect(files[0].oldPath).toBe('vm-restart@1.1.0')
    expect(diffStats(files)).toEqual({ additions: 3, deletions: 2 })
    const hunks = files[0].hunks
    expect(hunks.length).toBe(2)
    // every hunk header counts match the lines underneath
    for (const h of hunks) {
      const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(h.header)!
      const oldLen = m[2] === undefined ? 1 : Number(m[2])
      const newLen = m[4] === undefined ? 1 : Number(m[4])
      expect(h.lines.filter((l) => l.type !== 'add').length).toBe(oldLen)
      expect(h.lines.filter((l) => l.type !== 'del').length).toBe(newLen)
    }
  })

  it('merges nearby changes into one hunk', () => {
    const text = unifiedDiff('a\nb\nc\nd\ne\nf\n', 'a\nB\nc\nd\nE\nf\n', { context: 3 })
    expect(parseUnifiedDiff(text)[0].hunks).toHaveLength(1)
  })

  it('handles additions at the end and a new file', () => {
    expect(parseUnifiedDiff(unifiedDiff('', 'a\nb\n'))[0].hunks[0].lines.map((l) => l.type)).toEqual(['add', 'add'])
    expect(parseUnifiedDiff(unifiedDiff('a\n', 'a\nb\n'))[0].hunks[0].lines.map((l) => l.type)).toEqual(['ctx', 'add'])
  })
})
