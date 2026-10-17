/** n8n's "Rename node" dialog (F2): the display name, references to it follow. */
import { useState } from 'react'
import { Button, Field, Input, Modal, OverlayFooter, OverlayHeader, useOverlayClose } from '@iaf/ui'
import { findStepNode } from '../model/graph'
import { useDesignerStore } from '../model/store'

function Body({ stepId }: { stepId: string }) {
  const store = useDesignerStore()
  const close = useOverlayClose()
  const node = findStepNode(store.getState().graph, stepId)
  const current = node?.data.step.name ?? stepId
  const [name, setName] = useState(current)
  const submit = () => {
    const next = name.trim()
    if (next && next !== current) store.setStepName(stepId, next)
    close()
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <OverlayHeader title="Rename step" subtitle={<span className="font-mono">{stepId}</span>} icon="edit" />
      <div className="px-5 pb-2 pt-1">
        <Field label="Name" hint="Expressions that reference the old name are updated too" htmlFor="rename-step">
          <Input id="rename-step" value={name} onChange={(e) => setName(e.target.value)} data-autofocus onFocus={(e) => e.currentTarget.select()} />
        </Field>
      </div>
      <OverlayFooter>
        <Button variant="secondary" onClick={close}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" disabled={!name.trim()}>
          Rename
        </Button>
      </OverlayFooter>
    </form>
  )
}

export function RenameDialog({ stepId, onClose }: { stepId: string; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-md" label="Rename step">
      <Body stepId={stepId} />
    </Modal>
  )
}
