import { useMemo, useState } from 'react'
import { Button, Icon, Input, Select, cx, type SelectOption } from '@iaf/ui'
import { pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import { TextLink } from './common'

export interface ResourcePickerProps {
  /** the resource type name (x-resource.type) */
  type: string
  value: string
  onChange: (value: string) => void
  /** the workflow that creates one, when the schema says `orCreateWith` */
  createWith?: string
  ariaLabel: string
  required?: boolean
  disabled?: boolean
  id?: string
  className?: string
}

/**
 * A select fed by the type's generated read endpoint (`GET /api/v1/<plural>`): name, id and
 * state per option, a link to the workflow that creates one, and a way to type an id by hand.
 */
export function ResourcePicker({ type, value, onChange, createWith, ariaLabel, required, disabled, id, className }: ResourcePickerProps) {
  const types = useQuery('resource-types', () => api.resourceTypes())
  const plural = types.data?.find((t) => t.name === type)?.plural
  const settled = !!types.data || !!types.error
  const list = useQuery(['resources-of', type, plural ?? ''], () => (plural ? api.resourcesOf(plural) : api.resources({ type })), { enabled: settled })
  const [manual, setManual] = useState(false)

  const options = useMemo<SelectOption[]>(() => {
    const opts: SelectOption[] = []
    if (!required) opts.push({ value: '', label: 'Not set' })
    for (const r of list.data ?? []) opts.push({ value: r.id, label: r.name ? r.name : r.id, hint: r.name ? `${r.id} · ${r.status}` : r.status })
    if (value && !opts.some((o) => o.value === value)) opts.push({ value, label: value, hint: 'typed' })
    return opts
  }, [list.data, required, value])

  const empty = !list.loading && (list.data?.length ?? 0) === 0
  const createHref = createWith ? pathFor('catalog-run', { workflow: createWith }) : null

  return (
    <div className={cx('space-y-1.5', className)}>
      <div className="flex items-center gap-2">
        {manual ? (
          <Input id={id} mono value={value} onChange={(e) => onChange(e.target.value)} placeholder={`${type} id`} disabled={disabled} aria-label={ariaLabel} autoComplete="off" />
        ) : (
          <Select
            value={value}
            onChange={onChange}
            options={options}
            ariaLabel={ariaLabel}
            placeholder={list.loading ? 'Loading…' : list.error ? 'Could not load' : `Pick a ${type}`}
            disabled={disabled}
            className="min-w-0 flex-1"
          />
        )}
        <Button size="sm" variant="capsule" icon={manual ? 'list' : 'edit'} onClick={() => setManual((m) => !m)} disabled={disabled} title={manual ? 'Pick from the registry' : 'Type an id'}>
          {manual ? 'Pick' : 'Type'}
        </Button>
      </div>
      <p className="flex flex-wrap items-center gap-x-1.5 text-caption2 text-tertiary">
        <Icon name="resource" size={11} />
        {empty ? `No ${plural ?? `${type}s`} registered yet` : `${plural ?? `${type}s`} from the registry`}
        {createHref && (
          <>
            <span>·</span>
            <TextLink to={createHref}>create one with {createWith}</TextLink>
          </>
        )}
      </p>
    </div>
  )
}
