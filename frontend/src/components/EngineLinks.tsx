import { Icon } from '@iaf/ui'

/** How a job's page names each link into the engine's tools; a name it does not know reads "Open in <name>". */
const LABEL: Record<string, string> = { temporal: 'Open in Temporal' }

// the secondary md Button of @iaf/ui, as a link: these leave the app, in a new tab
const LINK_CLASS =
  'inline-flex select-none items-center justify-center gap-1.5 whitespace-nowrap rounded-md bg-surface-secondary px-4 py-2 text-sm font-medium text-primary transition-all duration-150 hover:bg-stroke-light active:scale-[0.97]'

/** The http(s) links of a job's `links`, in a stable order; anything else is dropped. */
export function engineLinks(links?: Record<string, string | undefined>): { name: string; label: string; href: string }[] {
  return Object.entries(links ?? {})
    .filter((e): e is [string, string] => typeof e[1] === 'string' && /^https?:\/\//i.test(e[1]))
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, href]) => ({ name, label: LABEL[name] ?? `Open in ${name}`, href }))
}

/** Header actions that open the job where the engine's own tools show it (the job view's `links`), e.g. its execution in the Temporal UI. */
export function EngineLinks({ links }: { links?: Record<string, string | undefined> }) {
  return (
    <>
      {engineLinks(links).map((l) => (
        <a key={l.name} href={l.href} target="_blank" rel="noreferrer" className={LINK_CLASS}>
          {l.label}
          <Icon name="external" size={15} />
        </a>
      ))}
    </>
  )
}
