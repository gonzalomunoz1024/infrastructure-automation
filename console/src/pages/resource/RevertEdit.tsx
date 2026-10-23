import { Button, Drawer, EmptyState, OverlayBody, OverlayFooter, OverlayHeader, SkeletonRows, useOverlayClose } from '@iaf/ui'
import { titleCase, type SchemaValue } from '@iaf/console-kit'
import { ExplainedError } from '../../app/commands'
import { singularOf } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { revertReasonWords, stateApi, type RevertPlan } from '../../lib/state'
import { EditDrawer, editSchemaOf, type EditDrawerProps } from './EditDrawer'

/*
 * "Revert to this" (WP 9.8): the state service's revert plan for the time picked, opened as Edit
 * configuration with the values of then, so the change is reviewed like any edit (what changes,
 * the preview, policy and approvals, the request as code) and runs through the type's own update
 * workflow only when the person sends it. Fields the update workflow does not set, and tags, are
 * named in the note rather than sent.
 */

const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })

/** The plan's changes the update workflow's form can carry, and the rest. */
export function revertValues(plan: RevertPlan, mutable: string[]): { initial: SchemaValue; left: string[] } {
  const initial: SchemaValue = {}
  const left: string[] = []
  for (const [k, v] of Object.entries(plan.changes ?? {})) {
    if (mutable.includes(k) && v !== null) initial[k] = v as SchemaValue[string]
    else left.push(k)
  }
  return { initial, left }
}

function Close() {
  const close = useOverlayClose()
  return <Button onClick={close}>Close</Button>
}

export function RevertEdit({ to, ...props }: EditDrawerProps & { to: string }) {
  const { type, wfrn, name, onClose } = props
  const noun = singularOf(type.title).toLowerCase()
  const plan = useQuery(['revert-plan', type.name, wfrn, to], () => stateApi.revertPlan(type.name, wfrn, to))
  const when = Number.isNaN(Date.parse(to)) ? to : fmt(to)

  if (!plan.data || !plan.data.possible || !type.update) {
    return (
      <Drawer onClose={onClose} width="max-w-xl" label={`Revert ${name}`}>
        <OverlayHeader title="Revert to an earlier time" icon="history" subtitle={`${name}, as it was on ${when}`} />
        <OverlayBody>
          {plan.error ? <ExplainedError error={plan.error} what={`Plan bringing this ${noun} back`} onRetry={() => void plan.refetch()} compact /> : plan.loading ? <SkeletonRows n={3} /> : <EmptyState compact icon="history" title="Nothing to bring back" message={revertReasonWords(plan.data?.reason, noun)} />}
        </OverlayBody>
        <OverlayFooter>
          <Close />
        </OverlayFooter>
      </Drawer>
    )
  }
  const schema = editSchemaOf(type.update, type)
  const mutable = Object.keys(schema.properties ?? {})
  const { initial, left } = revertValues(plan.data, mutable)
  const tags = plan.data.tags
  const tagWords = tags && (Object.keys(tags.set).length || tags.removed.length) ? [...Object.keys(tags.set), ...tags.removed] : []
  const reason = `Back to how it was on ${when}`
  const note = (
    <div className="mb-5 rounded-lg border border-accent/30 bg-accent-tint/40 p-3 text-sm text-primary" data-testid="revert-note">
      <p>
        <span className="font-semibold">Reverting to {when}.</span> The form starts from the values {name} had then (version {plan.data.to?.version}); review what changes below before you send it.
      </p>
      {left.length > 0 && <p className="mt-1 text-secondary">{`${type.update.workflow} does not change ${left.map(titleCase).join(', ')}, so ${left.length === 1 ? 'it stays' : 'they stay'} as now.`}</p>}
      {tagWords.length > 0 && <p className="mt-1 text-secondary">{`The tags ${tagWords.join(', ')} were different then; change them with Manage tags.`}</p>}
    </div>
  )
  return <EditDrawer {...props} initial={{ ...initial, ...('reason' in (schema.properties ?? {}) ? { reason } : {}) }} note={note} />
}
