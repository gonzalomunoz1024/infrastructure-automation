import { Button, Icon, IconButton, Input } from '@iaf/ui'
import { newTagRow, type TagRow } from '../../lib/tags'

/*
 * Free tag rows: a name and a value per row, a button to remove a row and one to add another.
 * The create's Tags step, the Tags drawer and the apply dialog share it; each says what a row may
 * hold through `errors`, keyed by the row's id, and may lock a row (a required tag) with the
 * reason in words in place of its Remove button.
 */

export interface TagRowsProps {
  rows: TagRow[]
  onChange: (rows: TagRow[]) => void
  errors?: Record<string, { key?: string; value?: string }>
  /** ids of the form's controls start with it */
  idPrefix: string
  /** rows whose name is fixed and which cannot be removed, by row id, with the reason */
  locked?: Record<string, string>
  /** what a row's value is, when not a tag value ("Value to set") */
  valueLabel?: string
  addLabel?: string
  disabled?: boolean
}

export function TagRows({ rows, onChange, errors = {}, idPrefix, locked = {}, valueLabel = 'Value', addLabel = 'Add a tag', disabled }: TagRowsProps) {
  const set = (id: string, patch: Partial<TagRow>) => onChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  const add = () => {
    const row = newTagRow()
    onChange([...rows, row])
    requestAnimationFrame(() => document.getElementById(`${idPrefix}-${row.id}-key`)?.focus())
  }
  const remove = (i: number) => {
    const next = rows.filter((_, j) => j !== i)
    onChange(next)
    // the focus goes to the row that took its place, else to the button that adds one
    requestAnimationFrame(() => (document.getElementById(next[i] ? `${idPrefix}-${next[i].id}-key` : `${idPrefix}-add`) as HTMLElement | null)?.focus())
  }
  return (
    <div className="space-y-3">
      {rows.length > 0 && (
        <div aria-hidden="true" className="hidden grid-cols-[minmax(0,1fr)_minmax(0,1fr)_2.25rem] gap-2 text-caption font-medium text-secondary sm:grid">
          <span>Name</span>
          <span>{valueLabel}</span>
        </div>
      )}
      {rows.map((row, i) => {
        const e = errors[row.id] ?? {}
        const lock = locked[row.id]
        const keyId = `${idPrefix}-${row.id}-key`
        const valueId = `${idPrefix}-${row.id}-value`
        return (
          <div key={row.id} className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_2.25rem]" data-tag-row={row.key || undefined}>
            <div className="min-w-0">
              <Input
                id={keyId}
                size="sm"
                mono
                value={row.key}
                readOnly={!!lock}
                disabled={disabled}
                invalid={!!e.key}
                aria-label={`Tag name, row ${i + 1}`}
                aria-describedby={e.key ? `${keyId}-error` : lock ? `${keyId}-lock` : undefined}
                onChange={(ev) => set(row.id, { key: ev.target.value })}
                placeholder="name"
                autoComplete="off"
                spellCheck={false}
              />
              {e.key && (
                <p id={`${keyId}-error`} className="mt-1 text-caption text-critical">
                  {e.key}
                </p>
              )}
              {lock && !e.key && (
                <p id={`${keyId}-lock`} className="mt-1 text-caption text-tertiary">
                  {lock}
                </p>
              )}
            </div>
            <div className="min-w-0">
              <Input id={valueId} size="sm" value={row.value} disabled={disabled} invalid={!!e.value} aria-label={`${valueLabel === 'Value' ? 'Tag value' : valueLabel}, row ${i + 1}${row.key ? ` (${row.key})` : ''}`} aria-describedby={e.value ? `${valueId}-error` : undefined} onChange={(ev) => set(row.id, { value: ev.target.value })} placeholder="value" autoComplete="off" />
              {e.value && (
                <p id={`${valueId}-error`} className="mt-1 text-caption text-critical">
                  {e.value}
                </p>
              )}
            </div>
            <div className="flex items-start justify-end sm:justify-center">{lock ? null : (
                <IconButton size="sm" label={`Remove tag row ${i + 1}${row.key ? ` (${row.key})` : ''}`} onClick={() => remove(i)} disabled={disabled}>
                  <Icon name="close" size={14} />
                </IconButton>
              )}</div>
          </div>
        )
      })}
      <Button id={`${idPrefix}-add`} size="sm" variant="ghost" icon="plus" onClick={add} disabled={disabled}>
        {addLabel}
      </Button>
    </div>
  )
}
