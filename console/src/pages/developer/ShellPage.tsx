import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Button, Icon, Kbd, PageHeader, cx } from '@iaf/ui'
import { useConsole } from '../../app/console'
import { Link, pathFor, resourcePath, type PageProps } from '../../app/router'
import { completeCommand, execute, type ShellOutput } from '../../lib/shell/core'
import { consoleShellContext } from '../../lib/shell/console'

/*
 * The console shell (WP 7.4, like CloudShell): a terminal in the page that runs the console CLI's
 * commands with the page's own session, so it is signed in already and nothing runs on a server.
 * The command core is lib/shell (the CLI's, until the generated one replaces it); this page only
 * draws it. Keyboard first: Enter runs, Up and Down walk the history, Tab completes a command's
 * name, Ctrl+C stops following a job, Ctrl+L clears. The output is a log region, so a screen
 * reader hears each result as it lands; tables are real tables, each in its own scrolling region,
 * so a phone scrolls a wide table and never the page. A wfrn and a job id link to their pages.
 */

interface Entry {
  id: number
  command: string
  outputs: ShellOutput[]
  state: 'running' | 'ok' | 'failed'
}

const HISTORY_MAX = 100
const EXAMPLES = ['help', 'whoami', 'ls vm --filter environment=prod', 'jobs']

function historyKey(userId: string): string {
  return `console-shell-history:${userId}`
}

function readHistory(userId: string): string[] {
  try {
    const raw = localStorage.getItem(historyKey(userId))
    const parsed = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string').slice(-HISTORY_MAX) : []
  } catch {
    return []
  }
}

function writeHistory(userId: string, history: string[]) {
  try {
    localStorage.setItem(historyKey(userId), JSON.stringify(history.slice(-HISTORY_MAX)))
  } catch {
    /* storage unavailable: the history lives for this page */
  }
}

export default function ShellPage(_props: PageProps) {
  const { me, account, region } = useConsole()
  const [entries, setEntries] = useState<Entry[]>([])
  const [line, setLine] = useState('')
  const [history, setHistory] = useState<string[]>(() => readHistory(me.id))
  // where Up and Down are in the history; null is the line being typed
  const [cursor, setCursor] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [running, setRunning] = useState<AbortController | null>(null)
  const [announce, setAnnounce] = useState('')
  const nextId = useRef(1)
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => document.getElementById('shell-input')?.focus(), [])
  useEffect(() => endRef.current?.scrollIntoView({ block: 'end' }), [entries])
  // leaving the page stops whatever it follows
  useEffect(() => () => running?.abort(), [running])

  const run = useCallback(
    async (command: string) => {
      const text = command.trim()
      setLine('')
      setCursor(null)
      setDraft('')
      if (!text) return
      const nextHistory = [...history.filter((h, i) => !(i === history.length - 1 && h === text)), text].slice(-HISTORY_MAX)
      setHistory(nextHistory)
      writeHistory(me.id, nextHistory)
      const id = nextId.current++
      const stop = new AbortController()
      setRunning(stop)
      setAnnounce(`Running ${text}`)
      setEntries((list) => [...list, { id, command: text, outputs: [], state: 'running' }])
      const emit = (out: ShellOutput) => {
        if (out.kind === 'clear') setEntries([])
        else setEntries((list) => list.map((e) => (e.id === id ? { ...e, outputs: [...e.outputs, out] } : e)))
      }
      const ok = await execute(text, consoleShellContext({ emit, signal: stop.signal }))
      setEntries((list) => list.map((e) => (e.id === id ? { ...e, state: ok ? 'ok' : 'failed' } : e)))
      setRunning(null)
      setAnnounce(ok ? `${text}: done` : `${text}: failed`)
      document.getElementById('shell-input')?.focus()
    },
    [history, me.id],
  )

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (running) return
    void run(line)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.ctrlKey && (e.key === 'c' || e.key === 'C') && running) {
      e.preventDefault()
      running.abort()
      return
    }
    if (e.ctrlKey && (e.key === 'l' || e.key === 'L')) {
      e.preventDefault()
      setEntries([])
      setAnnounce('Cleared')
      return
    }
    if (e.key === 'ArrowUp' && history.length) {
      e.preventDefault()
      const at = cursor === null ? history.length - 1 : Math.max(0, cursor - 1)
      if (cursor === null) setDraft(line)
      setCursor(at)
      setLine(history[at])
      return
    }
    if (e.key === 'ArrowDown' && cursor !== null) {
      e.preventDefault()
      const at = cursor + 1
      if (at >= history.length) {
        setCursor(null)
        setLine(draft)
      } else {
        setCursor(at)
        setLine(history[at])
      }
      return
    }
    if (e.key === 'Tab' && !e.shiftKey) {
      // completes a command's name when it can; otherwise Tab moves on, as everywhere else
      const done = completeCommand(line)
      if (done) {
        e.preventDefault()
        setLine(done)
      }
    }
  }

  return (
    <>
      <PageHeader title="Console shell" subtitle={`The console CLI in your browser, signed in as ${me.name} in ${account.name}, ${region.title ?? region.name}. Commands run through the console with your access.`} />
      <section aria-label="Shell" className="overflow-hidden rounded-lg border border-stroke-light bg-surface shadow-card">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-stroke-light bg-surface-secondary px-4 py-2">
          <p className="flex min-w-0 items-center gap-2 text-caption text-secondary">
            <Icon name="terminal" size={14} className="shrink-0" />
            <span className="truncate font-mono">
              {me.id}@{account.name.toLowerCase()}
            </span>
          </p>
          <div className="flex items-center gap-1">
            <Button size="sm" variant="ghost" onClick={() => setEntries([])} disabled={!entries.length}>
              Clear
            </Button>
          </div>
        </div>
        <div className="max-h-[60vh] min-h-64 overflow-y-auto px-4 py-3 font-mono text-caption">
          {entries.length === 0 && (
            <div className="space-y-2 font-sans text-sm text-secondary">
              <p>Type a command and press Enter. Type help for the list.</p>
              <div className="flex flex-wrap gap-1.5">
                {EXAMPLES.map((x) => (
                  <button key={x} type="button" onClick={() => void run(x)} className="rounded-md bg-surface-secondary px-2 py-1 font-mono text-caption text-primary transition-colors hover:bg-stroke-light">
                    {x}
                  </button>
                ))}
              </div>
            </div>
          )}
          <div role="log" aria-label="Shell output" className="space-y-3">
            {entries.map((e) => (
              <div key={e.id} data-shell-entry={e.state} data-shell-command={e.command}>
                <p className="break-all text-primary">
                  <span aria-hidden="true" className="select-none text-accent">
                    ${' '}
                  </span>
                  <span className="sr-only">Command: </span>
                  {e.command}
                </p>
                <div className="mt-1 space-y-2">
                  {e.outputs.map((o, i) => (
                    <OutputBlock key={i} out={o} />
                  ))}
                  {e.state === 'running' && <p className="text-tertiary">Running. Ctrl+C stops following a job.</p>}
                </div>
              </div>
            ))}
          </div>
          <div ref={endRef} />
        </div>
        <form onSubmit={submit} className="flex items-center gap-2 border-t border-stroke-light px-4 py-2">
          <label htmlFor="shell-input" className="shrink-0 font-mono text-sm text-accent">
            <span aria-hidden="true">$</span>
            <span className="sr-only">Command</span>
          </label>
          <input
            id="shell-input"
            value={line}
            onChange={(e) => {
              setLine(e.target.value)
              setCursor(null)
            }}
            onKeyDown={onKeyDown}
            readOnly={!!running}
            aria-busy={!!running}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            enterKeyHint="go"
            aria-describedby="shell-keys"
            className="min-w-0 flex-1 bg-transparent py-1.5 font-mono text-sm text-primary outline-none placeholder:text-tertiary"
            placeholder={running ? 'Running…' : 'ls vm --filter environment=prod'}
          />
          <Button type="submit" size="sm" variant="primary" disabled={!!running}>
            Run
          </Button>
        </form>
      </section>
      <p id="shell-keys" className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-caption text-secondary">
        <span>
          <Kbd className="text-secondary">↑</Kbd> <Kbd className="text-secondary">↓</Kbd> history
        </span>
        <span>
          <Kbd className="text-secondary">Tab</Kbd> completes a command
        </span>
        <span>
          <Kbd className="text-secondary">Ctrl</Kbd>+<Kbd className="text-secondary">C</Kbd> stops following
        </span>
        <span>
          <Kbd className="text-secondary">Ctrl</Kbd>+<Kbd className="text-secondary">L</Kbd> clears
        </span>
      </p>
      <p className="sr-only" role="status" aria-live="polite">
        {announce}
      </p>
      <p className="mt-4 text-caption text-secondary">
        The same commands run in a terminal with the CLI.{' '}
        <Link to={pathFor('portal')} className="text-accent underline underline-offset-2">
          Get the CLI
        </Link>
      </p>
    </>
  )
}

// the words keep the primary colour (healthy and warning text miss AA on the surface); a rule at the left carries the tone
const TONE: Record<string, string> = { plain: 'text-primary', muted: 'text-secondary', success: 'border-l-2 border-healthy pl-2 text-primary', error: 'border-l-2 border-critical pl-2 text-critical', warning: 'border-l-2 border-warning pl-2 text-primary' }

function OutputBlock({ out }: { out: ShellOutput }) {
  if (out.kind === 'clear') return null
  if (out.kind === 'text') return <p className={cx('whitespace-pre-wrap break-words', TONE[out.tone ?? 'plain'])}>{linkify(out.text)}</p>
  if (out.kind === 'json')
    return (
      <pre tabIndex={0} aria-label="JSON output" className="max-h-80 overflow-auto rounded-md bg-surface-secondary p-2 text-primary outline-none focus-visible:ring-2 focus-visible:ring-accent">
        {JSON.stringify(out.value, null, 2)}
      </pre>
    )
  return (
    <div role="region" aria-label={out.caption} tabIndex={0} className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-accent">
      <table className="min-w-full border-collapse text-left">
        <caption className="sr-only">{out.caption}</caption>
        <thead>
          <tr>
            {out.columns.map((c) => (
              <th key={c} scope="col" className="whitespace-nowrap py-0.5 pr-6 font-semibold text-secondary">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {out.rows.map((r, i) => (
            <tr key={i}>
              {r.map((cell, j) => (
                <td key={j} className="whitespace-nowrap py-0.5 pr-6 align-top text-primary">
                  {linkify(cell)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const LINKABLE = /(wfrn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:\d{0,12}:[a-z0-9-]+\/[A-Za-z0-9._/-]+|\bjob_[a-z0-9]+\b)/g

/** A wfrn links to its resource and a job id to its request; everything else stays text. */
function linkify(text: string): ReactNode {
  const parts = text.split(LINKABLE)
  if (parts.length === 1) return text
  return parts.map((p, i) => {
    if (i % 2 === 0) return p
    if (p.startsWith('job_'))
      return (
        <Link key={i} to={pathFor('request', { id: p })} className="text-accent underline-offset-2 hover:underline">
          {p}
        </Link>
      )
    const m = /^wfrn:[^:]*:([^:]*):[^:]*:[^:]*:([^/]+)\//.exec(p)
    return m ? (
      <Link key={i} to={resourcePath(m[1], m[2], p)} className="text-accent underline-offset-2 hover:underline">
        {p}
      </Link>
    ) : (
      p
    )
  })
}
