import { useMemo, useState } from 'react'
import { Button, Drawer, OverlayBody, OverlayFooter, OverlayHeader, SkeletonRows, useDebounce, useOverlayClose } from '@iaf/ui'
import { ConfirmDialog, PreviewCard, SchemaForm, defaultsFor, displayWfrn, fieldNames, fieldRoot, isComplete, schemaType, validateAgainst, type Problem, type Schema, type SchemaValue } from '@iaf/console-kit'
import { CodeDisclosure } from '../../app/CodeDisclosure'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { bff } from '../../lib/bff'
import { codeSamplesFor, subjectFor } from '../../lib/code'
import type { ConsoleAction, ConsoleType } from '../../lib/descriptors'
import { explain } from '../../lib/plain'
import { useQuery } from '../../lib/queries'
import { cleanInput, useSubmissionKey } from '../../lib/submission'
import type { CommandAccepted, CommandInput } from '../../lib/types'

/*
 * One workflow run on one resource, from the detail page's Actions (or a `?action=<verb>` link):
 * the workflow's input as a form (the resource itself filled in, fields the resource already
 * answers prefilled), a live preview of what it will touch and cost, the same request as code,
 * and for a destructive verb a confirmation that asks for the resource's name. A failure says
 * what went wrong in words, beside the fields it names.
 */

export interface ActionDrawerProps {
  type: ConsoleType
  action: ConsoleAction
  wfrn: string
  /** the resource's name, typed to confirm a destructive verb */
  name: string
  /** what the resource already is: inputs of the same name start from these */
  attributes: Record<string, unknown>
  onClose: () => void
  onAccepted: (accepted: CommandAccepted, action: ConsoleAction) => void
}

/** The form's first values: the workflow's defaults, then what the resource already holds for the same inputs. */
export function startValue(schema: Schema, subject: string | undefined, attributes: Record<string, unknown>): SchemaValue {
  const out = defaultsFor(schema)
  for (const [name, prop] of Object.entries(schema.properties ?? {})) {
    if (name === subject || prop['x-resource']) continue
    const v = attributes[name]
    if (v === undefined || v === null || v === '') continue
    if (prop.enum && !prop.enum.some((e) => e === v)) continue
    const t = schemaType(prop)
    const fits = (t === 'string' && typeof v === 'string') || ((t === 'integer' || t === 'number') && typeof v === 'number') || (t === 'boolean' && typeof v === 'boolean')
    if (fits) out[name] = v
  }
  if (subject) delete out[subject]
  return out
}

function CancelButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}

export function ActionDrawer({ type, action, wfrn, name, attributes, onClose, onAccepted }: ActionDrawerProps) {
  const { decide, account, region } = useConsole()
  const subject = subjectFor(action, type)
  // the form without the subject: the page already knows which resource this runs on
  const schema = useMemo<Schema>(() => ({ ...action.input, required: (action.input.required ?? []).filter((r) => r !== subject) }), [action.input, subject])
  const omit = useMemo(() => (subject ? [subject] : []), [subject])
  const [value, setValue] = useState<SchemaValue>(() => startValue(action.input, subject, attributes))
  const [problems, setProblems] = useState<Problem[]>([])
  const [confirming, setConfirming] = useState(false)
  const keyFor = useSubmissionKey()
  const cmd = useCommand()

  const input = useMemo(() => cleanInput(value), [value])
  const body: CommandInput = { input }
  const complete = isComplete(schema, value)
  const debounced = useDebounce(JSON.stringify(input), 350)
  const preview = useQuery(['action-preview', type.name, wfrn, action.verb, debounced], () => bff.resources.preview(type.name, wfrn, action.verb, { input: JSON.parse(debounced) as Record<string, unknown> }), { enabled: complete })
  const decision = decide(type, action.verb)
  const policy = complete ? preview.data?.policy.decision : undefined
  const samples = codeSamplesFor({ kind: 'verb', type, action, wfrn, body: action.destructive ? { ...body, confirm: true } : body, context: { accountId: account.id, region: region.name } })

  const send = async (confirm: boolean) => {
    const payload: CommandInput = confirm ? { ...body, confirm: true } : body
    const key = keyFor(payload)
    const accepted = await cmd.run(action.title, () => bff.resources.run(type.name, wfrn, action.verb, payload, key), {
      quiet: true,
      invalidates: ['resources', 'jobs', `console-activity/${type.name}`],
      onProblems: setProblems,
    })
    if (accepted) onAccepted(accepted, action)
  }

  const submit = () => {
    const found = validateAgainst(schema, value).filter((p) => !omit.includes(fieldRoot(p.field) ?? ''))
    setProblems(found)
    if (found.length) {
      document.getElementById(`action-${action.verb}-${fieldRoot(found[0].field)}`)?.focus()
      return
    }
    if (action.destructive) setConfirming(true)
    else void send(false)
  }

  return (
    <>
      <Drawer onClose={onClose} width="max-w-xl" label={`${action.title}: ${name}`}>
        <OverlayHeader
          title={action.title}
          icon={action.destructive ? 'trash' : 'play'}
          subtitle={
            <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
              <span className="font-medium text-primary">{name}</span>
              <span className="truncate font-mono text-caption text-tertiary" title={wfrn}>
                {displayWfrn(wfrn, 36)}
              </span>
            </span>
          }
        />
        <OverlayBody>
          <form
            id={`action-${action.verb}-form`}
            onSubmit={(e) => {
              e.preventDefault()
              submit()
            }}
            noValidate
          >
            {action.description && <p className="mb-5 text-sm text-secondary">{action.description}</p>}
            <SchemaForm schema={schema} omit={omit} value={value} onChange={setValue} problems={problems} idPrefix={`action-${action.verb}`} />
          </form>

          <section aria-label="What will happen" className="mt-6">
            {!complete ? (
              <p className="rounded-lg border border-dashed border-stroke-light px-3 py-2.5 text-caption text-tertiary">Fill in the required fields to see what this will do, what it touches and what it costs.</p>
            ) : preview.loading ? (
              <SkeletonRows n={2} rowClassName="h-16" />
            ) : preview.error ? (
              <FailureNote failure={explain(preview.error, 'Preview this run')} compact onRetry={() => void preview.refetch()} />
            ) : preview.data ? (
              <PreviewCard preview={preview.data} compact title="What will happen" />
            ) : null}
            {policy === 'deny' && <p className="mt-2 text-caption text-critical">Policy refuses this run as it stands, so it cannot be sent. Change the fields above, or ask the account’s admins.</p>}
          </section>

          <CodeDisclosure samples={samples} ariaLabel={`${action.title} as code`} className="mt-6" />

          <FailureNote failure={cmd.failure} shownFields={fieldNames(schema)} onDismiss={cmd.clear} className="mt-5" />
        </OverlayBody>
        <OverlayFooter>
          <CancelButton />
          <ActionButton decision={decision} what={action.title} verb={action.verb} variant={action.destructive ? 'danger' : 'primary'} icon={action.destructive ? 'trash' : 'play'} loading={cmd.busy} disabled={policy === 'deny'} onClick={submit}>
            {action.title}
          </ActionButton>
        </OverlayFooter>
      </Drawer>
      {confirming && (
        <ConfirmDialog
          title={`${action.title}: ${name}?`}
          tone="danger"
          icon="trash"
          requireText={name}
          confirmLabel={action.title}
          cancelLabel="Keep it"
          message={
            <>
              {action.workflow} runs on <span className="font-medium text-primary">{name}</span>
              {policy === 'needs-approval' ? ' once an approver says yes' : ''}. The console cannot undo it; its record and activity stay readable afterwards.
            </>
          }
          onConfirm={() => send(true)}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
  )
}
