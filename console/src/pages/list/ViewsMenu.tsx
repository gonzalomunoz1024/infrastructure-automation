import { useState, type FormEvent } from 'react'
import { Button, ConfirmDialog, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, plural, useToast } from '@iaf/ui'
import { OPERATOR_LABEL, type FilterToken } from '@iaf/console-kit'
import { MenuButton, MenuHeading, MenuItem, MenuSeparator } from '../../app/MenuButton'
import type { ConsoleType } from '../../lib/descriptors'
import { stateToView, viewToState, type ListState } from '../../lib/listState'
import { newViewId, usePreferenceWriter, withView, withoutView } from '../../lib/preferences'
import { effectiveState, sameList } from '../../lib/resourceList'
import type { SavedView } from '../../lib/types'

/*
 * The saved views of a list: the whole list ("All virtual machines"), each view the person named,
 * and saving the list on screen as a new view, saving changes to the one applied, or deleting it.
 * A view keeps the filters, the sort and the page size; applying one writes them into the page's
 * query, so a view is also a link.
 */

export const MAX_VIEWS = 50

export interface ViewsMenuProps {
  type: ConsoleType
  typeKey: string
  views: SavedView[]
  /** the list as the page's query has it */
  state: ListState
  apply: (state: ListState) => void
  /** labels a filter token in the person's words */
  describeToken: (token: FilterToken) => string
}

export function ViewsMenu({ type, typeKey, views, state, apply, describeToken }: ViewsMenuProps) {
  const write = usePreferenceWriter()
  const toast = useToast()
  const [dialog, setDialog] = useState<'save' | 'delete' | null>(null)
  const active = state.view ? views.find((v) => v.id === state.view) : undefined
  const changed = !!active && !sameList(viewToState(active), state, type)
  const all = `All ${type.title.toLowerCase()}`

  const saveChanges = () => {
    if (!active) return
    write(withView(typeKey, stateToView(effectiveState(state, type), active.name, active.id, active.createdAt)))
    toast.success('View saved', `“${active.name}” now shows this list.`)
  }

  return (
    <>
      <MenuButton
        label="Saved views"
        icon="layers"
        width="w-72"
        content={
          <>
            <MenuHeading>Saved views</MenuHeading>
            <MenuItem
              checked={!active}
              onSelect={() => apply({ tokens: [] })}
            >
              {all}
            </MenuItem>
            {views.map((v) => (
              <MenuItem
                key={v.id}
                checked={active?.id === v.id}
                hint={v.filters.length ? plural(v.filters.length, 'filter') : undefined}
                onSelect={() => apply(viewToState(v))}
              >
                {v.name}
              </MenuItem>
            ))}
            <MenuSeparator />
            <MenuItem
              icon="plus"
              onSelect={() => setDialog('save')}
            >
              Save as a new view…
            </MenuItem>
            {active && changed && (
              <MenuItem
                icon="check"
                onSelect={() => saveChanges()}
              >
                Save changes to “{active.name}”
              </MenuItem>
            )}
            {active && (
              <MenuItem
                icon="trash"
                danger
                onSelect={() => setDialog('delete')}
              >
                Delete “{active.name}”…
              </MenuItem>
            )}
          </>
        }
      >
        <span className="text-secondary">View:</span> {active ? active.name : all}
        {changed && <span className="text-tertiary"> · changed</span>}
      </MenuButton>

      {dialog === 'save' && (
        <SaveViewDialog
          type={type}
          views={views}
          summary={summaryOf(effectiveState(state, type), type, describeToken)}
          onClose={() => setDialog(null)}
          onSave={(name) => {
            const view = stateToView(effectiveState(state, type), name, newViewId(), new Date().toISOString())
            write(withView(typeKey, view))
            apply({ ...state, view: view.id })
            setDialog(null)
            toast.success('View saved', `“${name}” is in the Saved views menu of ${type.title.toLowerCase()}.`)
          }}
        />
      )}
      {dialog === 'delete' && active && (
        <ConfirmDialog
          title={`Delete the view “${active.name}”?`}
          message="Only the saved filters, sort and page size go; the resources stay as they are."
          confirmLabel="Delete view"
          tone="danger"
          icon="trash"
          onClose={() => setDialog(null)}
          onConfirm={() => {
            write(withoutView(typeKey, active.id))
            apply({ ...state, view: undefined })
            toast.success('View deleted', `“${active.name}” is no longer in the Saved views menu.`)
          }}
        />
      )}
    </>
  )
}

/** What a view will keep, in words: "Status = active, search “web” · newest first · 100 per page". */
function summaryOf(state: ListState, type: ConsoleType, describeToken: (token: FilterToken) => string): string {
  const filters = state.tokens.length ? state.tokens.map(describeToken).join(', ') : 'no filters'
  const column = type.columns.find((c) => c.key === state.sort?.key)?.label ?? state.sort?.key ?? 'updated'
  const order = state.sort?.dir === 'desc' ? `${column}, descending` : `${column}, ascending`
  return `${filters} · sorted by ${order} · ${state.pageSize} per page`
}

export function describeTokenWith(labelOf: (property: string) => string) {
  return (t: FilterToken): string => (t.property === null ? `search “${t.value}”` : `${labelOf(t.property)} ${t.operator === '=' ? 'is' : OPERATOR_LABEL[t.operator]} ${t.value}`)
}

function SaveViewDialog({ type, views, summary, onClose, onSave }: { type: ConsoleType; views: SavedView[]; summary: string; onClose: () => void; onSave: (name: string) => void }) {
  const [name, setName] = useState('')
  const trimmed = name.trim()
  const clash = views.some((v) => v.name.toLowerCase() === trimmed.toLowerCase())
  const full = views.length >= MAX_VIEWS
  const error = clash ? `A view called “${trimmed}” exists. Pick another name, or apply that view and save your changes to it.` : full ? `You keep ${MAX_VIEWS} views of ${type.title.toLowerCase()}, the most there can be. Delete one first.` : undefined
  const valid = trimmed.length > 0 && trimmed.length <= 80 && !clash && !full

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (valid) onSave(trimmed)
  }

  return (
    <Modal onClose={onClose} label="Save this view" width="max-w-md">
      <form onSubmit={submit}>
        <OverlayHeader title="Save this view" subtitle={`This list of ${type.title.toLowerCase()} under a name of yours. Saved views follow you to every browser you sign in on.`} icon="layers" />
        <OverlayBody>
          <Field label="Name" htmlFor="save-view-name" required error={error} hint="Up to 80 characters, for example “Failed in prod”.">
            <Input id="save-view-name" data-autofocus required maxLength={80} value={name} invalid={!!error} onChange={(e) => setName(e.target.value)} />
          </Field>
          <p className="mt-3 text-caption text-secondary">
            <span className="font-medium text-primary">It keeps: </span>
            {summary}
          </p>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" disabled={!valid}>
            Save view
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
