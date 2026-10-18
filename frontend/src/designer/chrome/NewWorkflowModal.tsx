/**
 * "New workflow": what the API and the catalog call it (title, noun and verb, so `vm` +
 * `restart` gives `POST /api/v1/vms:restart` and the MCP tool `vm_restart`), who owns it and the
 * folder it lives in. The workflow exists once it is first saved.
 */
import { useMemo, useState } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Skeleton, useOverlayClose } from '@iaf/ui'
import { RESERVED_NOUNS } from '@iaf/workflow-checks'
import { api, useCurrentUser } from '../../lib/api'
import { useQuery } from '../../lib/queries'

export interface NewWorkflowMeta {
  name: string
  title: string
  noun: string
  verb: string
  owner: string
  folder: string
}

const WORD = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/

function kebab(s: string): string {
  return s
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function Body({ initial, onCreate, templateTitle }: { initial: Partial<NewWorkflowMeta>; onCreate: (meta: NewWorkflowMeta) => void; templateTitle?: string }) {
  const close = useOverlayClose()
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  const [title, setTitle] = useState(initial.title ?? '')
  const [noun, setNoun] = useState(initial.noun ?? '')
  const [verb, setVerb] = useState(initial.verb ?? '')
  const [owner, setOwner] = useState(initial.owner ?? '')
  const [folder, setFolder] = useState(initial.folder ?? '')
  const [touched, setTouched] = useState(false)
  const teams = me.data?.teams ?? []
  const ownerValue = owner || teams[0] || ''
  const name = initial.name ?? (noun && verb ? `${kebab(noun)}-${kebab(verb)}` : '')

  const errors = useMemo(() => {
    const e: Partial<Record<'title' | 'noun' | 'verb' | 'owner', string>> = {}
    if (!title.trim()) e.title = 'Give the workflow a title'
    if (!WORD.test(noun)) e.noun = 'Lowercase letters, digits and dashes, like vm'
    else if (RESERVED_NOUNS.includes(`${noun}s`) || RESERVED_NOUNS.includes(noun)) e.noun = `/api/v1/${noun}s belongs to the platform; pick another noun`
    if (!WORD.test(verb)) e.verb = 'Lowercase letters, digits and dashes, like restart'
    if (!ownerValue) e.owner = 'Pick the team that owns it'
    return e
  }, [title, noun, verb, ownerValue])
  const valid = Object.keys(errors).length === 0

  const submit = () => {
    setTouched(true)
    if (!valid) return
    onCreate({ name, title: title.trim(), noun, verb, owner: ownerValue, folder: folder.trim() })
  }

  return (
    <form
      className="flex min-h-0 flex-col"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <OverlayHeader title="New workflow" subtitle={templateTitle ? `From the template “${templateTitle}”` : 'It starts with an API trigger; add the first step on the canvas.'} icon="workflow" />
      <OverlayBody className="space-y-4">
        <Field label="Title" required error={touched ? errors.title : undefined} htmlFor="new-title">
          <Input id="new-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="VM restart" data-autofocus />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Noun" required hint={!touched || !errors.noun ? 'What it acts on' : undefined} error={touched ? errors.noun : undefined} htmlFor="new-noun">
            <Input id="new-noun" mono value={noun} onChange={(e) => setNoun(e.target.value.toLowerCase())} placeholder="vm" />
          </Field>
          <Field label="Verb" required hint={!touched || !errors.verb ? 'What it does' : undefined} error={touched ? errors.verb : undefined} htmlFor="new-verb">
            <Input id="new-verb" mono value={verb} onChange={(e) => setVerb(e.target.value.toLowerCase())} placeholder="restart" />
          </Field>
        </div>
        <div className="rounded-md bg-surface-secondary px-3 py-2.5 text-caption text-secondary">
          {name ? (
            <>
              Saved as <span className="font-mono text-primary">{name}</span>, called with <span className="font-mono text-primary">POST /api/v1/{noun}s:{verb}</span>
            </>
          ) : (
            'The noun and the verb name the file, the endpoint and the MCP tool.'
          )}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Owner" required error={touched ? errors.owner : undefined}>
            {me.loading && !me.data ? (
              <Skeleton className="h-9" />
            ) : (
              <Select ariaLabel="Owner" value={ownerValue} onChange={setOwner} options={teams.map((t) => ({ value: t, label: t }))} placeholder="Your teams" />
            )}
          </Field>
          <Field label="Folder" htmlFor="new-folder" hint="Groups it in the list">
            <Input id="new-folder" value={folder} onChange={(e) => setFolder(e.target.value)} placeholder="Compute" />
          </Field>
        </div>
      </OverlayBody>
      <OverlayFooter>
        <Button variant="secondary" onClick={close}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" disabled={touched && !valid}>
          Create workflow
        </Button>
      </OverlayFooter>
    </form>
  )
}

export function NewWorkflowModal({ initial = {}, templateTitle, onCreate, onClose }: { initial?: Partial<NewWorkflowMeta>; templateTitle?: string; onCreate: (meta: NewWorkflowMeta) => void; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="New workflow">
      <Body initial={initial} templateTitle={templateTitle} onCreate={onCreate} />
    </Modal>
  )
}
