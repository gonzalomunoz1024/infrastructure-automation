import { useState } from 'react'
import { Button, CodeBlock, Field, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, Textarea, useOverlayClose, useToast } from '@iaf/ui'
import { api, errorMessage } from '../../lib/api'
import type { ProviderDraft } from '../../lib/types'

/** Ask Devin: a description in words becomes a provider file to review, which `onUse` takes. */
export function DraftModal({ initial, onUse, onClose }: { initial: string; onUse: (d: ProviderDraft) => void; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-3xl" label="Ask Devin to draft the provider">
      <DraftForm initial={initial} onUse={onUse} />
    </Modal>
  )
}

function DraftForm({ initial, onUse }: { initial: string; onUse: (d: ProviderDraft) => void }) {
  const close = useOverlayClose()
  const toast = useToast()
  const [text, setText] = useState(initial)
  const [draft, setDraft] = useState<ProviderDraft | null>(null)
  const [busy, setBusy] = useState(false)

  const ask = async () => {
    setBusy(true)
    try {
      setDraft(await api.draftProvider(text.trim()))
    } catch (e) {
      toast.error('Devin did not answer', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <OverlayHeader title="Ask Devin to draft it" subtitle="Describe the system in words; the draft comes back as a provider document for you to review." icon="sparkles" />
      <OverlayBody className="space-y-4">
        <Field label="Description" htmlFor="draft-text">
          <Textarea id="draft-text" rows={6} value={text} onChange={(e) => setText(e.target.value)} disabled={busy} />
        </Field>
        {draft && (
          <div className="animate-fade-in space-y-3">
            <p className="text-sm text-primary">{draft.summary}</p>
            {draft.assumptions.length > 0 && (
              <ul className="space-y-1 text-caption text-secondary">
                {draft.assumptions.map((a, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                    {a}
                  </li>
                ))}
              </ul>
            )}
            <CodeBlock code={draft.yaml} language="yaml" lineNumbers maxHeight="max-h-80" />
          </div>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" onClick={close} disabled={busy}>
          Cancel
        </Button>
        {draft ? (
          <>
            <Button icon="refresh" loading={busy} onClick={() => void ask()}>
              Ask again
            </Button>
            <Button variant="primary" icon="check" onClick={() => onUse(draft)}>
              Use this draft
            </Button>
          </>
        ) : (
          <Button variant="primary" icon="sparkles" loading={busy} disabled={!text.trim()} onClick={() => void ask()}>
            Ask Devin
          </Button>
        )}
      </OverlayFooter>
    </>
  )
}
