import { useMemo, useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Textarea, cx, useOverlayClose, useToast, type SelectOption } from '@iaf/ui'
import { api, errorMessage, useCurrentUser } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Credential, Me } from '../lib/types'

/*
 * A credential is written once and referenced by name ever after: the picker lists the names the
 * store holds, the modal adds one. Neither ever shows a value again. A credential belongs to a
 * team: only its members (and admins) may send it anywhere from the wizard or the imports.
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
const TEAM_RE = /^[a-z][a-z0-9-]*$/

/** The choice that lets an admin type a team they are not in. */
export const OTHER_TEAM = '__other__'

/** The teams a new credential can belong to: the caller's own; an admin may name any, so they get "Another team…" too. */
export function ownerOptions(me: Me | undefined): SelectOption[] {
  const options: SelectOption[] = (me?.teams ?? []).map((t) => ({ value: t, label: t }))
  if (me?.admin) options.push({ value: OTHER_TEAM, label: 'Another team…', hint: 'Type its name' })
  return options
}

/** Whether the team is typed rather than picked: an admin chose "Another team…", or the caller has no team to pick from. */
export function typesOwner(options: SelectOption[], value: string, loading?: boolean): boolean {
  return value === OTHER_TEAM || (!loading && options.length === 0)
}

export interface OwnerFieldProps {
  me: Me | undefined
  /** /me has not answered yet */
  loading?: boolean
  /** the picked choice: a team, or OTHER_TEAM */
  value: string
  /** the typed team, used with OTHER_TEAM or when there is nothing to pick */
  other: string
  onChange: (value: string) => void
  onOtherChange: (team: string) => void
}

/** The team that may use the credential: one of the caller's, or any team an admin names. */
export function OwnerField({ me, loading, value, other, onChange, onOtherChange }: OwnerFieldProps) {
  const options = useMemo(() => ownerOptions(me), [me])
  const typed = typesOwner(options, value, loading)
  const invalid = typed && !!other && !TEAM_RE.test(other)
  const hint = !loading && options.length === 0 ? 'Only members of a team can store credentials.' : me?.admin ? 'The team whose members may use it from the wizard and the imports; as an admin you may name any team.' : 'The team whose members may use it from the wizard and the imports: one of yours.'
  return (
    <Field label="Owner team" htmlFor={typed ? 'cred-owner' : undefined} required hint={hint} error={invalid ? 'Lowercase letters, digits and dashes, like team-data' : undefined}>
      <div className="flex flex-col gap-2 sm:flex-row">
        {(loading || options.length > 0) && (
          <Select ariaLabel="Owner team" value={value} onChange={onChange} options={options} mono disabled={loading} placeholder={loading ? 'Loading your teams…' : 'Pick a team'} className="min-w-0 flex-1" />
        )}
        {typed && <Input id="cred-owner" mono value={other} onChange={(e) => onOtherChange(e.target.value.trim())} invalid={invalid} placeholder="team-data" className="min-w-0 flex-1" />}
      </div>
    </Field>
  )
}

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
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  const [name, setName] = useState('')
  const [type, setType] = useState('token')
  const [value, setValue] = useState('')
  const [owner, setOwner] = useState('')
  const [otherTeam, setOtherTeam] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bare = name.startsWith('vault:') ? name.slice('vault:'.length) : name
  const nameOk = NAME_RE.test(bare)
  const teams = useMemo(() => ownerOptions(me.data), [me.data])
  // the caller's first team, until they pick another
  const picked = owner || teams[0]?.value || ''
  const team = typesOwner(teams, picked, me.loading) ? otherTeam : picked
  const teamOk = TEAM_RE.test(team)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!nameOk || !value || !teamOk) return
    setBusy(true)
    setError(null)
    try {
      const accepted = await api.createCredential({ name: bare, type, value, owner: team })
      setValue('')
      invalidate('credentials')
      // the store answers with the reference a provider file uses, vault:<name>
      const ref = accepted?.id?.startsWith('vault:') ? accepted.id : credentialRef({ name: bare, path: '' })
      toast.success('Credential stored', `${bare} is in the store for ${team}. Its value is never shown again.`)
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
        <Field label="Type">
          <Select ariaLabel="Credential type" value={type} onChange={setType} options={CREDENTIAL_TYPES} />
        </Field>
        <OwnerField me={me.data} loading={me.loading} value={picked} other={otherTeam} onChange={setOwner} onOtherChange={setOtherTeam} />
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
        <Button type="submit" variant="primary" icon="lock" loading={busy} disabled={!nameOk || !value || !teamOk}>
          Store credential
        </Button>
      </OverlayFooter>
    </form>
  )
}
