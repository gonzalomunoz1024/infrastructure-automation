import { Button, Card, Field, Icon, Input, Textarea, cx } from '@iaf/ui'
import type { ProviderType } from '../../lib/types'
import { NAME_RE, TYPES, VERSION_RE, kebab, typeChange, type StepProps } from './state'

/* ---- 1 · Type ------------------------------------------------------------ */

export function TypeStep({ state, update, onAsk }: StepProps & { onAsk: () => void }) {
  const pick = (type: ProviderType) => {
    if (type === state.type) return
    update(typeChange(state, type))
  }
  return (
    <div className="space-y-5">
      <Card title="What kind of system is it?" subtitle="The type decides how the platform calls it and what the next steps ask.">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" role="radiogroup" aria-label="Provider type">
          {TYPES.map((t) => {
            const on = t.value === state.type
            return (
              <button
                key={t.value}
                type="button"
                role="radio"
                aria-checked={on}
                aria-label={t.label}
                onClick={() => pick(t.value)}
                className={cx(
                  'flex items-start gap-3 rounded-lg border p-4 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-accent',
                  on ? 'border-accent bg-accent-tint' : 'border-stroke-light bg-surface hover:bg-surface-secondary',
                )}
              >
                <span className={cx('mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md', on ? 'bg-accent text-white' : 'bg-surface-secondary text-secondary')}>
                  <Icon name={t.icon} size={16} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold text-primary">{t.label}</span>
                  <span className="mt-0.5 block text-caption leading-snug text-secondary">{t.text}</span>
                </span>
              </button>
            )
          })}
        </div>
      </Card>

      <Card title="Name it" subtitle="The file lands at providers/<owner>/<name>.yaml.">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Title" htmlFor="prov-title" required>
            <Input id="prov-title" value={state.title} onChange={(e) => update({ title: e.target.value, ...(state.nameTouched ? {} : { name: kebab(e.target.value) }) })} placeholder="VMware vSphere" autoFocus />
          </Field>
          <Field label="Name" htmlFor="prov-name" required hint="Lowercase, dashes; workflows call it by this" error={state.name && !NAME_RE.test(state.name) ? 'Lowercase letters, digits and dashes' : undefined}>
            <Input id="prov-name" mono value={state.name} onChange={(e) => update({ name: e.target.value, nameTouched: true })} invalid={!!state.name && !NAME_RE.test(state.name)} placeholder="vsphere" />
          </Field>
          <Field label="Owner" htmlFor="prov-owner" required hint="The team whose folder it lives in">
            <Input id="prov-owner" mono value={state.owner} onChange={(e) => update({ owner: e.target.value, ownerTouched: true })} placeholder="team-compute" />
          </Field>
          <Field label="Version" htmlFor="prov-version" required>
            <Input id="prov-version" mono value={state.version} onChange={(e) => update({ version: e.target.value })} invalid={!VERSION_RE.test(state.version.trim())} />
          </Field>
          <Field label="Description" htmlFor="prov-description" className="sm:col-span-2">
            <Textarea id="prov-description" rows={2} value={state.description} onChange={(e) => update({ description: e.target.value })} placeholder="Virtual machines, templates and snapshots in the vCenter of each environment." />
          </Field>
        </div>
      </Card>

      <Card title="Rather describe it in words?" subtitle="Devin drafts the provider file from a few sentences. The draft opens on the review, where its checks run before anything is published." data-testid="ask-devin">
        <Button icon="sparkles" onClick={onAsk}>
          Ask Devin to draft it
        </Button>
      </Card>
    </div>
  )
}
