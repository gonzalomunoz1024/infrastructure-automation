import { useState } from 'react'
import { Button, Card, CodeBlock, Drawer, EmptyState, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Skeleton, useOverlayClose, useToast } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { Template } from '../lib/types'
import { ErrorState } from '../components/common'
import { renamedWorkflowYaml } from '../lib/api-extra'

const KEBAB = /^[a-z][a-z0-9-]{1,62}$/

export default function TemplatesPage(_: PageProps) {
  const templates = useQuery('templates', () => api.templates())
  const [using, setUsing] = useState<Template | null>(null)
  const [peek, setPeek] = useState<Template | null>(null)

  return (
    <>
      <PageHeader title="Templates" subtitle="Starting points for new workflows: pick one, name it, and it opens on the canvas as a draft." />

      {templates.error && !templates.data ? (
        <ErrorState error={templates.error} title="Could not load the templates" onRetry={() => void templates.refetch()} />
      ) : templates.loading ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" role="status" aria-label="Loading">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-40" />
          ))}
        </div>
      ) : (templates.data?.length ?? 0) === 0 ? (
        <Card flush>
          <EmptyState icon="grid" title="No templates yet" message="Templates come from the content repository; publish a workflow and mark it as one." />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {templates.data?.map((t) => (
            <Card key={t.id} title={t.title} subtitle={<span className="font-mono">{t.id}</span>} className="flex flex-col">
              <p className="text-sm leading-relaxed text-secondary">{t.description}</p>
              <div className="mt-4 flex items-center justify-end gap-2">
                <Button size="sm" variant="ghost" icon="eye" onClick={() => setPeek(t)}>
                  YAML
                </Button>
                <Button size="sm" variant="primary" iconRight="arrow-right" onClick={() => setUsing(t)}>
                  Use template
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      {using && <UseTemplateModal template={using} onClose={() => setUsing(null)} />}
      {peek && (
        <Drawer onClose={() => setPeek(null)} width="max-w-2xl" label={`Template ${peek.title}`}>
          <OverlayHeader title={peek.title} subtitle={peek.description} icon="grid" />
          <OverlayBody>
            <CodeBlock code={peek.yaml} language="yaml" lineNumbers />
          </OverlayBody>
        </Drawer>
      )}
    </>
  )
}

function UseTemplateModal({ template, onClose }: { template: Template; onClose: () => void }) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [title, setTitle] = useState('')
  const valid = KEBAB.test(name)
  const create = useMutation(async (n: string, t?: string) => api.createWorkflow(await renamedWorkflowYaml(template.yaml, n, t)), {
    invalidates: ['workflows'],
    onSuccess: (accepted) => {
      toast.success('Draft created', `${title.trim() || name} is on the canvas.`)
      navigate(pathFor('workflow', { name: accepted.id || name }))
    },
    onError: (e) => toast.error('Could not create the workflow', errorMessage(e)),
  })
  return (
    <Modal onClose={onClose} width="max-w-md" label={`Use template ${template.title}`}>
      <OverlayHeader title={template.title} subtitle="Name the new workflow; the template's content becomes its first draft." icon="grid" />
      <form
        className="flex min-h-0 flex-1 flex-col"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid && !create.loading) void create.mutate(name, title.trim() || undefined)
        }}
      >
        <OverlayBody className="space-y-4">
          <Field label="Name" required htmlFor="ut-name" hint="kebab-case, unique" error={name && !valid ? 'Lowercase letters, digits and dashes' : undefined}>
            <Input id="ut-name" mono value={name} onChange={(e) => setName(e.target.value)} placeholder="service-restart" autoComplete="off" data-autofocus />
          </Field>
          <Field label="Title" htmlFor="ut-title" hint="Shown in the catalog; the template's own title when empty">
            <Input id="ut-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={template.title} autoComplete="off" />
          </Field>
        </OverlayBody>
        <OverlayFooter>
          <CancelButton disabled={create.loading} />
          <Button type="submit" variant="primary" disabled={!valid} loading={create.loading} iconRight="arrow-right">
            Create and open
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

function CancelButton({ disabled }: { disabled?: boolean }) {
  const close = useOverlayClose()
  return (
    <Button onClick={close} disabled={disabled}>
      Cancel
    </Button>
  )
}
