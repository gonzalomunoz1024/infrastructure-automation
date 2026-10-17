import { useMemo, useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Textarea, cx, useOverlayClose, useToast, type SelectOption } from '@iaf/ui'
import { api, errorMessage } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Credential } from '../lib/types'

/*
 * A credential is written once and referenced by name ever after: the picker lists the names the
 * store holds, the modal adds one. Neither ever shows a value again.
 */

/** The types the credential store accepts. */
export const CREDENTIAL_TYPES: SelectOption[] = [
  { value: 'token', label: 'Token', hint: 'A bearer token' },
  { value: 'api-key', label: 'API key', hint: 'Sent in a named header' },
  { value: 'basic', label: 'Username and password', hint: 'user:password' },
  { value: 'password', label: 'Password' },
  { value: 'ssh-key', label: 'SSH key', hint: 'A private key, PEM' },
  { value: 'file', label: 'File', hint: 'A certificate, a kubeconfig' },
]

const MULTILINE = new Set(['ssh-key', 'file'])
const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9_./-]*$/

/** The reference a provider file carries: `vault:<name>`, which the store reports as the path. */
export function credentialRef(c: Pick<Credential, 'name' | 'path'>): string {
  if (c.path?.startsWith('vault:')) return c.path
  return c.name.startsWith('vault:') ? c.name : `vault:${c.name}`
}

export interface CredentialPickerProps {
  value: string
  onChange: (ref: string) => void
  ariaLabel?: string
  required?: boolean
  disabled?: boolean
  id?: string
  className?: string
}

export function CredentialPicker({ value, onChange, ariaLabel = 'Credential', required, disabled, id, className }: CredentialPickerProps) {
  const list = useQuery('credentials', () => api.credentials())
  const [adding, setAdding] = useState(false)

  const options = useMemo<SelectOption[]>(() => {
    const opts: SelectOption[] = []
    if (!required) opts.push({ value: '', label: 'None' })
    for (const c of list.data ?? []) opts.push({ value: credentialRef(c), label: c.name, hint: `${c.type} · ${c.path}` })
    if (value && !opts.some((o) => o.value === value)) opts.push({ value, label: value, hint: 'not in the store yet' })
    return opts
  }, [list.data, value, required])

  return (
    <div className={cx('flex items-center gap-2', className)}>
      <Select ariaLabel={ariaLabel} value={value} onChange={onChange} options={options} mono disabled={disabled} placeholder={list.loading ? 'Loading…' : 'Pick a credential'} className="min-w-0 flex-1" />
      <Button size="sm" icon="plus" disabled={disabled} onClick={() => setAdding(true)} id={id ? `${id}-new` : undefined}>
        New
      </Button>
      {adding && <AddCredentialModal onClose={() => setAdding(false)} onCreated={(ref) => onChange(ref)} />}
    </div>
  )
}

export function AddCredentialModal({ onClose, onCreated, raised }: { onClose: () => void; onCreated?: (ref: string, name: string) => void; raised?: boolean }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Add credential" raised={raised}>
      <AddCredentialForm onCreated={onCreated} />
    </Modal>
  )
}

function AddCredentialForm({ onCreated }: { onCreated?: (ref: string, name: string) => void }) {
  const close = useOverlayClose()
  const toast = useToast()
  const [name, setName] = useState('')
  const [type, setType] = useState('token')
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bare = name.startsWith('vault:') ? name.slice('vault:'.length) : name
  const nameOk = NAME_RE.test(bare)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!nameOk || !value) return
    setBusy(true)
    setError(null)
    try {
      const accepted = await api.createCredential({ name: bare, type, value })
      setValue('')
      invalidate('credentials')
      // the store answers with the reference a provider file uses, vault:<name>
      const ref = accepted?.id?.startsWith('vault:') ? accepted.id : credentialRef({ name: bare, path: '' })
      toast.success('Credential stored', `${bare} is in the store. Its value is never shown again.`)
      onCreated?.(ref, bare)
      close()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="flex min-h-0 flex-1 flex-col">
      <OverlayHeader title="Add a credential" subtitle="Stored once in the credential store; providers refer to it by name." icon="key" />
      <OverlayBody className="space-y-4">
        <Field label="Name" htmlFor="cred-name" required hint="Its path in the store, like vsphere/token" error={name && !nameOk ? 'Letters, digits, slashes, dots, dashes and underscores' : undefined}>
          <Input id="cred-name" mono value={name} onChange={(e) => setName(e.target.value.trim())} invalid={!!name && !nameOk} autoFocus placeholder="team/system-token" />
        </Field>
        <Field label="Type" htmlFor="cred-type">
          <Select ariaLabel="Credential type" value={type} onChange={setType} options={CREDENTIAL_TYPES} />
        </Field>
        <Field label="Value" htmlFor="cred-value" required hint="Written once; nobody, including you, can read it back from here">
          {MULTILINE.has(type) ? (
            <Textarea id="cred-value" mono rows={6} value={value} onChange={(e) => setValue(e.target.value)} placeholder="-----BEGIN …" autoComplete="off" />
          ) : (
            <Input id="cred-value" type="password" mono value={value} onChange={(e) => setValue(e.target.value)} autoComplete="new-password" placeholder={type === 'basic' ? 'user:password' : '••••••••'} />
          )}
        </Field>
        {error && (
          <p className="text-caption text-critical" role="alert">
            {error}
          </p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button type="button" variant="ghost" onClick={close} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" icon="lock" loading={busy} disabled={!nameOk || !value}>
          Store credential
        </Button>
      </OverlayFooter>
    </form>
  )
}
