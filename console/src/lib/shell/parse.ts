/*
 * The command line as the console CLI reads it: words split on spaces, with '…' and "…" quoting
 * and a backslash escaping the next character; `--name value`, `--name=value` and `-o value`
 * flags, repeated ones kept in order; a leading `iaf` (the CLI's own name) dropped, so a line
 * copied from "View as code" or the developer portal runs as it is.
 */

export interface ParsedLine {
  /** the command, lowercased: 'ls', 'get', 'run'… ('' for an empty line) */
  command: string
  /** the words after the command that are not flags */
  args: string[]
  /** every flag's values in order; a switch (`--confirm`) holds 'true' */
  flags: Record<string, string[]>
}

export class ParseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ParseError'
  }
}

/** Splits a line into words, honouring quotes and backslashes. An unclosed quote is an error. */
export function tokenize(line: string): string[] {
  const words: string[] = []
  let word = ''
  let inWord = false
  let quote: '"' | "'" | null = null
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (quote) {
      if (c === quote) quote = null
      else if (c === '\\' && quote === '"' && i + 1 < line.length) word += line[++i]
      else word += c
      continue
    }
    if (c === '"' || c === "'") {
      quote = c
      inWord = true
    } else if (c === '\\' && i + 1 < line.length) {
      word += line[++i]
      inWord = true
    } else if (/\s/.test(c)) {
      if (inWord) words.push(word)
      word = ''
      inWord = false
    } else {
      word += c
      inWord = true
    }
  }
  if (quote) throw new ParseError(`A ${quote === '"' ? 'double' : 'single'} quote is not closed.`)
  if (inWord) words.push(word)
  return words
}

/** Short flags the CLI knows, and what they stand for. */
const SHORT: Record<string, string> = { o: 'output', f: 'filter', i: 'input', h: 'help', w: 'wait' }

/**
 * Reads a line. `switches` names the flags that take no value (`--confirm`, `--follow`); any
 * other flag takes the next word, or what follows its `=`.
 */
export function parseLine(line: string, switches: ReadonlySet<string> = new Set()): ParsedLine {
  const words = tokenize(line.trim())
  if (words[0] === 'iaf') words.shift()
  const command = (words.shift() ?? '').toLowerCase()
  const args: string[] = []
  const flags: Record<string, string[]> = {}
  const add = (name: string, value: string) => (flags[name] ??= []).push(value)
  for (let i = 0; i < words.length; i++) {
    const w = words[i]
    if (w === '--') {
      args.push(...words.slice(i + 1))
      break
    }
    const long = /^--([a-z][a-z0-9-]*)(?:=(.*))?$/i.exec(w)
    const short = /^-([a-z])$/i.exec(w)
    if (!long && !short) {
      args.push(w)
      continue
    }
    const name = long ? long[1].toLowerCase() : (SHORT[short![1].toLowerCase()] ?? short![1].toLowerCase())
    const inline = long?.[2]
    if (inline !== undefined) add(name, inline)
    else if (switches.has(name)) add(name, 'true')
    else if (i + 1 < words.length) add(name, words[++i])
    else throw new ParseError(`--${name} needs a value.`)
  }
  return { command, args, flags }
}

/** 'environment=prod' → ['environment', 'prod']; null when there is no `=` or no name. */
export function splitPair(text: string): [string, string] | null {
  const at = text.indexOf('=')
  if (at <= 0) return null
  return [text.slice(0, at).trim(), text.slice(at + 1)]
}

/** The last value of a flag, or undefined. */
export function flagOf(p: ParsedLine, name: string): string | undefined {
  const v = p.flags[name]
  return v?.[v.length - 1]
}

/** Whether a switch was given (`--confirm`, `--confirm=true`); `--confirm=false` is off. */
export function switchOf(p: ParsedLine, name: string): boolean {
  const v = flagOf(p, name)
  return v !== undefined && v !== 'false' && v !== '0'
}
